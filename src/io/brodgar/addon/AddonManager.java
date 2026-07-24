package io.brodgar.addon;

import haven.AddonWidgets;
import haven.Astronomy;
import haven.Audio;
import haven.BAttrWnd;
import haven.Buff;
import haven.Bufflist;
import haven.CharWnd;
import haven.Console;
import haven.Coord;
import haven.Coord2d;
import haven.Coord3f;
import haven.Drawable;
import haven.Equipory;
import haven.GameUI;
import haven.GItem;
import haven.Glob;
import haven.Gob;
import haven.GobHealth;
import haven.GobIcon;
import haven.IMeter;
import haven.Indir;
import haven.Inventory;
import haven.ItemInfo;
import haven.LayerMeter;
import haven.Loading;
import haven.MapView;
import haven.MCache;
import haven.Moving;
import haven.Music;
import haven.OCache;
import haven.Party;
import haven.Resource;
import haven.SAttrWnd;
import haven.SkillWnd;
import haven.Speaking;
import haven.UI;
import haven.Utils;
import haven.WItem;
import haven.Widget;
import haven.resutil.Curiosity;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaNumber;
import org.luaj.vm2.LuaString;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.TwoArgFunction;
import org.luaj.vm2.lib.ZeroArgFunction;
import org.luaj.vm2.lib.jse.JsePlatform;

import java.io.File;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The AddOn engine (see {@code specs/addons/04-engine.md}).
 *
 * <p>Phase 0 proved a Lua VM (LuaJ) reads live state on the UI thread. Phase 1a added loading addons
 * from disk (per-addon Lua envs, {@code hafen.log}, the {@code :lua}/{@code :addons} console). Phase
 * 1b adds the <b>runtime</b>: a per-frame <b>tick pump</b> (via an invisible {@link AddonRoot}
 * widget), a synthesized <b>event bus</b> ({@code hafen.events}), core lifecycle/update/gob events,
 * and <b>timers</b> ({@code hafen.timer}). All of it is <b>zero core edit</b> — it reuses the
 * existing {@code RemoteUI.init} and {@code MapView} hooks plus the public {@link OCache#callback}.
 *
 * <p>All-static facade, mirroring {@code io.brodgar.voice.Voice}. Everything Lua runs on the UI
 * thread (principle P5): the {@link OCache} callback fires on network/loader threads, so it only
 * <em>enqueues</em> deltas that {@link #tick(double)} drains and dispatches on the UI thread.
 */
public final class AddonManager {

    private static volatile MapView view;   // live map view (for hafen.gob.pos)
    private static volatile UI ui;          // live UI (for output; set at RemoteUI.init)
    private static final List<Addon> addons = new CopyOnWriteArrayList<Addon>();
    private static Addon consoleOwner;      // the :lua REPL, as a resource owner (persists across sessions)

    // -- engine runtime state (all touched on the UI thread, except the gob queue) ---------------
    private static AddonRoot addonRoot;                 // the attached tick widget (per session)
    private static OCache.ChangeCallback ocCb;          // strong ref: OCache keeps callbacks weakly
    private static volatile boolean enterWorldPending;  // set off-thread (MapView attach), read on tick
    private static double clock;                        // seconds accumulated from tick dt (UI thread)
    private static final Queue<GobEvent> gobEvents = new ConcurrentLinkedQueue<GobEvent>();

    // -- widget-tree read mechanism (spec 14): Locator + Adapters + inbound-uimsg update hook -------
    // Adapters read a GameUI widget tree into a Lua snapshot and fire a semantic event on change. The
    // UI.uimsg core tap runs off the UI thread, so it only marks the interested adapter(s) dirty; the
    // tick re-reads + fires on the UI thread (principle P5). Both collections are session-scoped.
    private static final List<TreeAdapter> treeAdapters = new CopyOnWriteArrayList<TreeAdapter>();
    private static final Set<TreeAdapter> treeDirty = ConcurrentHashMap.newKeySet();
    private static LuaValue vitalsCache;                 // last vitals snapshot (UI thread; change-detect)

    private AddonManager() {
    }

    static {
        // Use the RAW console line (quotes intact) so string literals survive; args are pre-split
        // by Utils.splitwords, which strips quotes. Fall back to joined args if the raw line is absent.
        Console.setscmd("lua", (cons, args) -> {
            String raw = cons.rawcmd();
            eval((raw != null) ? stripCmd(raw) : join(args));
        });
        Console.setscmd("addons", (cons, args) -> listAddons());
    }

    // ------------------------------------------------------------- lifecycle

    /** Call site — end of the MapView constructor. Captures the live view and flags "entered world". */
    public static void attach(MapView mv) {
        if(mv != null) {
            view = mv;
            enterWorldPending = true;   // OnEnterWorld is fired on the next tick (UI thread)
        }
    }

    /** Call site — first line of MapView.dispose(). */
    public static void detach(MapView mv) {
        if(view == mv)
            view = null;
    }

    /**
     * Per-session init (from RemoteUI.init, where ui.sess is bound): tear down the previous session's
     * addons, reset engine state, attach the tick pump + gob event source, then (re)load from disk.
     */
    public static synchronized void init(UI ui_) {
        ui = ui_;
        for(Addon a : addons)         // fire OnDisable + drop owned resources of the old session
            teardown(a);
        addons.clear();

        clock = 0;
        enterWorldPending = false;
        gobEvents.clear();
        addonRoot = null;
        ocCb = null;

        treeDirty.clear();            // reset the widget-tree read mechanism for the new session
        vitalsCache = null;
        treeAdapters.clear();
        treeAdapters.add(new VitalsAdapter());   // hp/stamina/energy (uimsg-driven)
        treeAdapters.add(new BuffsAdapter());    // buff add/remove (per-tick poll) + change (uimsg)
        treeAdapters.add(new FepAdapter());      // FEP/food + hunger (uimsg-driven)
        treeAdapters.add(new StudyAdapter());    // study/curiosity slots (per-tick poll)

        attachRoot(ui_);              // invisible per-frame tick widget (drives the engine)
        registerOcache(ui_);          // GobAdded/GobRemoved source (marshalled to the UI thread)
        loadAll();                    // discover + run addons, fire OnLoad for each
    }

    /** Attach the invisible tick widget to {@code ui.root} (guarded — root must exist). */
    private static void attachRoot(UI u) {
        if((u == null) || (u.root == null)) {
            log("no ui.root; tick pump not attached");
            return;
        }
        try {
            AddonRoot r = new AddonRoot();
            u.root.add(r);            // add() synchronizes on ui; the widget then ticks each frame
            addonRoot = r;
        } catch(RuntimeException e) {
            log("failed to attach tick widget: " + e);
        }
    }

    /** Register a weak-safe {@link OCache} callback that enqueues gob spawn/despawn for the tick. */
    private static void registerOcache(UI u) {
        try {
            OCache oc = u.sess.glob.oc;
            OCache.ChangeCallback cb = new OCache.ChangeCallback() {
                public void added(Gob g)   { gobEvents.add(new GobEvent(true, g)); }
                public void removed(Gob g) { gobEvents.add(new GobEvent(false, g)); }
            };
            oc.callback(cb);
            ocCb = cb;                // hold a strong ref (OCache stores callbacks in a WeakList)
        } catch(RuntimeException e) {
            log("failed to register gob callback: " + e);
        }
    }

    // ------------------------------------------------------------- discovery + loading

    /** Default: the {@code addons/} folder beside the client jar. {@code -Dhaven.addondir} overrides. */
    static File addonDir() {
        String override = System.getProperty("haven.addondir");
        if((override != null) && !override.isEmpty())
            return new File(override);
        try {
            return Utils.srcpath(AddonManager.class).resolveSibling("addons").toFile();
        } catch(RuntimeException e) {
            return new File("addons");
        }
    }

    private static void loadAll() {
        File dir = addonDir();
        log("addons dir: " + dir);
        File[] subs = dir.listFiles(File::isDirectory);
        if(subs == null) {
            log("no addons/ directory");
            return;
        }
        for(File sub : subs) {
            if(!new File(sub, "manifest.json").isFile())
                continue;
            try {
                Manifest m = Manifest.load(sub.toPath());
                Globals g = JsePlatform.standardGlobals();   // sandbox hardening = later phase
                Addon addon = new Addon(m, sub.toPath(), g);
                installHafen(g, addon);
                LuaTable ad = new LuaTable();
                ad.set("id", LuaValue.valueOf(m.id));
                ad.set("dir", LuaValue.valueOf(sub.getAbsolutePath()));
                g.set("ADDON", ad);
                addon.run();
                addons.add(addon);
                if(addon.error == null) {
                    fireTo(addon, "OnLoad");                  // the addon's file body just ran
                    log("loaded " + m.id + " v" + m.version);
                } else {
                    log("error in " + m.id + ": " + addon.error);
                }
            } catch(Exception e) {
                log("failed to load '" + sub.getName() + "': " + e.getMessage());
            }
        }
        log(addons.size() + " addon(s) loaded");
    }

    /** Fire {@code OnDisable} then drop an addon's owned resources (events + timers). */
    private static void teardown(Addon a) {
        try {
            fireTo(a, "OnDisable");
        } catch(RuntimeException e) {
            /* isolation is per-handler in callLua; this is just a backstop */
        }
        a.subs.clear();
        a.timers.clear();
    }

    private static void listAddons() {
        if(addons.isEmpty()) {
            log("no addons loaded");
            return;
        }
        StringBuilder sb = new StringBuilder();
        for(Addon a : addons) {
            if(sb.length() > 0)
                sb.append(", ");
            sb.append(a.manifest.id).append((a.error == null) ? "" : " (error)");
        }
        log("addons: " + sb);
    }

    // ------------------------------------------------------------- the tick pump

    /**
     * One engine step, driven by {@link AddonRoot#tick(double)} on the UI thread each frame. Order
     * per {@code 04-engine.md}: drain the marshalled event queue, then {@code OnUpdate}, then timers.
     * Everything is error-isolated so an addon bug never breaks the frame or another addon.
     */
    static void tick(double dt) {
        try {
            clock += dt;

            // 1. Gob spawn/despawn captured on network/loader threads → dispatch on the UI thread.
            GobEvent ge;
            while((ge = gobEvents.poll()) != null)
                fire(ge.added ? "GobAdded" : "GobRemoved", gobSnapshot(ge.gob));

            // 1b. Widget-tree adapters flagged dirty by an inbound uimsg → re-read + fire the semantic
            //     event, now on the UI thread. (Marked off-thread in onUimsg; drained here.) Then the
            //     per-tick poll for changes the uimsg tap can't see (buff add/remove is widget
            //     create/cdestroy on the Bufflist, not a uimsg — spec 14). Refresh before poll so a
            //     brand-new buff surfaces as a single BuffAdded (with its content already applied),
            //     not BuffChanged-then-BuffAdded.
            refreshTreeAdapters();
            pollTreeAdapters();

            // 2. "Entered the world" — defer until the HUD (GameUI) is actually up, so GameUI-backed
            //    reads (player.name, and later items/char/party) work INSIDE the handler. The map view
            //    attaches from its ctor (on a loader thread) a few frames before the HUD finishes
            //    assembling; enterWorldPending is reset per session in init(), so it can't get stuck.
            if(enterWorldPending && (gui() != null)) {
                enterWorldPending = false;
                fire("OnEnterWorld");
            }

            // 3. Per-frame update.
            fire("OnUpdate", LuaValue.valueOf(dt));

            // 4. Due timers.
            runTimers();
        } catch(RuntimeException e) {
            log("tick error: " + e);
        }
    }

    private static void runTimers() {
        for(Addon a : addons)
            runTimers(a);
        Addon c = consoleOwner;
        if(c != null)
            runTimers(c);
    }

    private static void runTimers(Addon a) {
        for(Timer t : a.timers) {
            if(!t.alive) {
                a.timers.remove(t);
                continue;
            }
            if(clock >= t.due) {
                callLua(a, t.fn);
                if(t.interval > 0) {
                    t.due += t.interval;                     // repeating: reschedule (fires once/tick)
                } else {
                    t.alive = false;                         // one-shot
                    a.timers.remove(t);
                }
            }
        }
    }

    // ------------------------------------------------------------- widget-tree read mechanism (1d)

    /**
     * The inbound-{@code uimsg} tap — the core edit in {@code UI.UiMessage.run} (spec 13, Level 3),
     * called <b>after</b> the target widget applies a server update, on a Loader thread under
     * {@code synchronized(ui)}. Much high-value state (vitals, buffs, FEP, …) lives in widget trees
     * updated by targeted {@code uimsg} (audit B1); this is where the engine learns about it. It must
     * <b>not</b> touch Lua — it only flags the interested adapter(s) dirty; {@link #tick(double)}
     * drains them and fires the semantic event on the UI thread (principle P5).
     */
    public static void onUimsg(Widget w, String msg) {
        if((w == null) || treeAdapters.isEmpty())
            return;
        for(TreeAdapter a : treeAdapters) {
            try {
                if(a.interested(w, msg))
                    treeDirty.add(a);
            } catch(RuntimeException e) {
                /* an adapter's recognizer must never break server message application */
            }
        }
    }

    /** Re-read each dirty adapter and fire its semantic event (UI thread, drained from the tick). */
    private static void refreshTreeAdapters() {
        if(treeDirty.isEmpty())
            return;
        for(TreeAdapter a : treeAdapters) {
            if(treeDirty.remove(a)) {
                try {
                    a.refresh();
                } catch(RuntimeException e) {
                    log("tree adapter error: " + e);
                }
            }
        }
    }

    /** Give every adapter a per-tick look (UI thread) for changes no inbound uimsg announces. */
    private static void pollTreeAdapters() {
        for(TreeAdapter a : treeAdapters) {
            try {
                a.poll();
            } catch(RuntimeException e) {
                log("tree adapter poll error: " + e);
            }
        }
    }

    /**
     * A widget-tree read adapter (spec {@code 14-widget-tree-reads.md}): the one place that knows a
     * target widget tree's shape, localizing that upstream-volatile knowledge. Two update paths:
     * <ul>
     *   <li><b>uimsg-driven</b> ({@link #interested} off-thread → dirty → {@link #refresh} on the UI
     *       thread): for state the server pushes via a targeted {@code uimsg} (vitals, FEP, buff
     *       content).</li>
     *   <li><b>poll-driven</b> ({@link #poll} every tick, UI thread): for structural changes the tap
     *       can't see — buff add/remove is a widget create/{@code cdestroy} on the {@code Bufflist},
     *       not a {@code uimsg}. Default is a no-op; only adapters that need it override it.</li>
     * </ul>
     */
    private interface TreeAdapter {
        boolean interested(Widget w, String msg);
        void refresh();
        default void poll() {}
    }

    /**
     * Player vitals — hp / stamina / energy as bar fractions (0..1). The {@link IMeter} widgets are
     * <i>located</i> by walking the HUD (public {@code children(Class)} — no reflection to find them);
     * the bar value is the {@code protected LayerMeter.meters}, reached via the {@link AddonWidgets}
     * haven-package accessor — the admitted non-zero-edit read (audit B5). The three vitals are created
     * in a fixed order (hp, stamina, energy), so they are mapped positionally. Fires {@code
     * VitalsChanged} only when a value actually changes (driven by the {@code IMeter "set"} uimsg).
     */
    private static final class VitalsAdapter implements TreeAdapter {
        public boolean interested(Widget w, String msg) {
            return w instanceof IMeter;
        }

        public void refresh() {
            LuaValue snap = readVitals();
            if(snap.isnil())
                return;                              // meters not up / no values yet — nothing to fire
            if(!vitalsEqual(snap, vitalsCache)) {
                vitalsCache = snap;
                fire("VitalsChanged", snap);
            }
        }
    }

    /** The vitals keys, in the server's fixed meter-creation order. */
    private static final String[] VITAL_KEYS = {"hp", "stamina", "energy"};

    /**
     * A {@code {hp,stamina,energy}} snapshot (0..1) read live from the HUD's {@link IMeter} widgets in
     * tree (= creation) order, or nil if none are up yet. Backs both {@code hafen.player.vitals} and
     * the {@code VitalsChanged} change-detection. Extra meters beyond the three vitals are ignored.
     */
    private static LuaValue readVitals() {
        GameUI g = gui();
        if(g == null)
            return LuaValue.NIL;
        LuaTable t = new LuaTable();
        boolean any = false;
        int i = 0;
        for(IMeter m : g.children(IMeter.class)) {
            if(i < VITAL_KEYS.length) {
                Double v = meterValue(m);
                if(v != null) {
                    t.set(VITAL_KEYS[i], LuaValue.valueOf(v));
                    any = true;
                }
            }
            i++;
        }
        return any ? t : LuaValue.NIL;
    }

    /** The first bar fraction (0..1) of a meter, or null (empty / still resolving). */
    private static Double meterValue(IMeter m) {
        try {
            List<LayerMeter.Meter> ms = AddonWidgets.meters(m);
            if((ms == null) || ms.isEmpty())
                return null;
            return ms.get(0).a;
        } catch(RuntimeException e) {
            return null;
        }
    }

    /** Do two vitals snapshots carry the same hp/stamina/energy? (nil-safe; for change-detection.) */
    private static boolean vitalsEqual(LuaValue a, LuaValue b) {
        if((a == null) || a.isnil() || (b == null) || b.isnil())
            return false;
        for(String k : VITAL_KEYS) {
            LuaValue va = a.get(k), vb = b.get(k);
            if(va.isnil() != vb.isnil())
                return false;
            if(va.isnumber() && (va.todouble() != vb.todouble()))
                return false;
        }
        return true;
    }

    /**
     * Buffs/debuffs — the {@link Buff} widgets under {@link GameUI#buffs} (a {@link Bufflist}). Add and
     * remove are widget create/{@code cdestroy}, NOT a {@code uimsg}, so they are detected by
     * <b>poll</b> (diffing {@code children(Buff.class)} each tick against a cache keyed by widget
     * identity); the per-buff {@code "ch"}/{@code "tt"} content updates ARE {@code uimsg}s, so
     * <b>refresh</b> re-reads the cached buffs and fires {@code BuffChanged}. Fires {@code BuffAdded}/
     * {@code BuffRemoved}/{@code BuffChanged} with the {@code Buff} snapshot. A buff fading out after a
     * server removal ({@code Buff.dest}) is treated as already gone (excluded), so removal is timely.
     */
    private static final class BuffsAdapter implements TreeAdapter {
        // Active buff -> its last snapshot. UI-thread-only (poll + refresh); reset per session by
        // re-instantiation in init(). IdentityHashMap: Buff widgets are keyed by object identity.
        private final Map<Buff, LuaValue> cache = new IdentityHashMap<Buff, LuaValue>();

        public boolean interested(Widget w, String msg) {
            return (w instanceof Buff) && ("ch".equals(msg) || "tt".equals(msg));
        }

        public void refresh() {
            for(Map.Entry<Buff, LuaValue> e : cache.entrySet()) {
                LuaValue snap = buffSnapshot(e.getKey());
                if(!buffEqual(snap, e.getValue())) {
                    e.setValue(snap);
                    fire("BuffChanged", snap);
                }
            }
        }

        public void poll() {
            Bufflist bl = bufflist();
            Set<Buff> active = new LinkedHashSet<Buff>();
            if(bl != null) {
                for(Buff b : bl.children(Buff.class)) {
                    if(!AddonWidgets.buffDest(b))
                        active.add(b);
                }
            }
            for(Buff b : active) {                        // additions (unseen buffs)
                if(!cache.containsKey(b)) {
                    LuaValue snap = buffSnapshot(b);
                    cache.put(b, snap);
                    fire("BuffAdded", snap);
                }
            }
            for(Iterator<Map.Entry<Buff, LuaValue>> it = cache.entrySet().iterator(); it.hasNext();) {
                Map.Entry<Buff, LuaValue> e = it.next();  // removals (gone or fading out)
                if(!active.contains(e.getKey())) {
                    fire("BuffRemoved", e.getValue());
                    it.remove();
                }
            }
        }
    }

    /**
     * FEP + hunger — the {@link BAttrWnd} (character-sheet "Base Attributes" tab). Located directly via
     * the public {@code CharWnd.battr} field (no tree-walk), then its public {@code feps}
     * ({@link BAttrWnd.FoodMeter}) and {@code glut} ({@link BAttrWnd.GlutMeter}) are read — all public
     * fields, so this needs no {@code haven}-package accessor. Both update via a {@code BAttrWnd}
     * {@code "food"}/{@code "glut"} {@code uimsg}, so it is purely uimsg-driven; each is a genuine
     * server change, so {@code FepChanged} fires whenever one lands (no change-detection needed).
     */
    private static final class FepAdapter implements TreeAdapter {
        public boolean interested(Widget w, String msg) {
            return (w instanceof BAttrWnd) && ("food".equals(msg) || "glut".equals(msg));
        }

        public void refresh() {
            LuaValue snap = readFood();
            if(!snap.isnil())
                fire("FepChanged", snap);
        }
    }

    /**
     * Study / curiosity — the items placed in the study window, each carrying a {@link Curiosity}
     * study profile. Located via the public {@code CharWnd.sattr} ({@link SAttrWnd}) → its
     * {@link SAttrWnd.StudyInfo} child → the study inventory it wraps. Like buffs, a curiosity being
     * added/removed is a widget create/{@code cdestroy} (not a {@code uimsg}) and its study data
     * streams in a beat after the item appears, so this is <b>poll-driven</b>: each tick it re-reads
     * the slots snapshot and fires {@code StudyChanged} only when it differs from the cache (an
     * add/remove, or a slot's fields resolving/changing). While the sattr tab is not up yet the poll is
     * skipped (the cache is kept), so no spurious event fires before there is anything to read.
     */
    private static final class StudyAdapter implements TreeAdapter {
        private LuaValue cache;   // last study-slots snapshot (UI thread; change-detect)

        public boolean interested(Widget w, String msg) {
            return false;         // study changes are structural / streamed, not a targeted uimsg — see poll()
        }

        public void refresh() {}

        public void poll() {
            SAttrWnd.StudyInfo si = studyInfo();
            if(si == null)
                return;           // study window not up yet — keep the cache, fire nothing
            LuaValue snap = readStudySlots(si.study);
            if(!studySlotsEqual(snap, cache)) {
                cache = snap;
                fire("StudyChanged", snap);
            }
        }
    }

    /** The player's buff bar ({@link GameUI#buffs}), or {@code null} before the HUD is up. */
    private static Bufflist bufflist() {
        GameUI g = gui();
        return (g == null) ? null : g.buffs;
    }

    /** Resource name (stable identity) of a buff, or {@code null} (Loading-guarded). */
    private static String buffRes(Buff b) {
        try {
            Resource r = b.res.get();
            return (r == null) ? null : r.name;
        } catch(RuntimeException e) {   // Loading etc.
            return null;
        }
    }

    /** Display name of a buff: the resource tooltip, else a server-pushed Name info, else nil. */
    private static String buffName(Buff b) {
        try {
            Resource r = b.res.get();
            if(r != null) {
                Resource.Tooltip tt = r.layer(Resource.tooltip);
                if((tt != null) && (tt.t != null))
                    return tt.t;
            }
        } catch(RuntimeException e) {   // Loading etc.
        }
        try {
            ItemInfo.Name n = ItemInfo.find(ItemInfo.Name.class, b.info());
            return ((n == null) || (n.str == null)) ? null : n.str.text;
        } catch(RuntimeException e) {   // info() still Loading / no rawinfo yet
            return null;
        }
    }

    /**
     * A Buff snapshot (the {@code Buff} shape in api-reference.md): {@code res}/{@code name} (stable),
     * plus {@code amount}/{@code cooldown}/{@code number} which come from resource-published
     * {@link ItemInfo} over {@link Buff#info} and are 0..1 fractions / an integer, content-dependent
     * and often absent. All Loading-guarded — a partial snapshot (res only) is fine while the buff
     * resource/tooltip is still resolving; the rest arrives on the next {@code "tt"} update.
     */
    private static LuaValue buffSnapshot(Buff b) {
        if(b == null)
            return LuaValue.NIL;
        LuaTable t = new LuaTable();
        String res = buffRes(b);
        if(res != null)
            t.set("res", LuaValue.valueOf(res));
        String name = buffName(b);
        if(name != null)
            t.set("name", LuaValue.valueOf(name));
        try {
            List<ItemInfo> info = b.info();   // may throw Loading
            Buff.AMeterInfo am = ItemInfo.find(Buff.AMeterInfo.class, info);
            if(am != null)
                t.set("amount", LuaValue.valueOf(am.ameter()));
            GItem.MeterInfo mi = ItemInfo.find(GItem.MeterInfo.class, info);
            if(mi != null)
                t.set("cooldown", LuaValue.valueOf(mi.meter()));
            GItem.NumberInfo ni = ItemInfo.find(GItem.NumberInfo.class, info);
            if(ni != null)
                t.set("number", LuaValue.valueOf(ni.itemnum()));
        } catch(RuntimeException e) {
            /* info still Loading — res/name may already be set; the rest arrives on a later update */
        }
        return t;
    }

    /** Do two buff snapshots carry the same res/name/amount/cooldown/number? (for change-detection.) */
    private static boolean buffEqual(LuaValue a, LuaValue b) {
        if((a == null) || (b == null))
            return false;
        return luaFieldEq(a, b, "res") && luaFieldEq(a, b, "name") && luaFieldEq(a, b, "amount")
            && luaFieldEq(a, b, "cooldown") && luaFieldEq(a, b, "number");
    }

    /** Field-level equality for a snapshot key: nil/number/string aware (used by buffEqual). */
    private static boolean luaFieldEq(LuaValue a, LuaValue b, String k) {
        LuaValue va = a.get(k), vb = b.get(k);
        if(va.isnil() != vb.isnil())
            return false;
        if(va.isnumber())
            return vb.isnumber() && (va.todouble() == vb.todouble());
        if(va.isstring())
            return vb.isstring() && va.tojstring().equals(vb.tojstring());
        return true;
    }

    /** The character sheet's Base-Attributes widget ({@code CharWnd.battr}), or {@code null}. */
    private static BAttrWnd battrwnd() {
        CharWnd c = charwnd();
        return (c == null) ? null : c.battr;
    }

    /**
     * A food snapshot ({@code hafen.char.food}): {@code fep = {cap,total,entries=[{res,name,amount}]}}
     * from the {@link BAttrWnd.FoodMeter}, and {@code hunger = {level,label,efficacy}} from the
     * {@link BAttrWnd.GlutMeter}. All backing fields are public; per-entry name/res are Loading-guarded
     * (skipped while resolving). nil until the character sheet's {@code battr} tab exists (it streams
     * in a beat after enter-world, like vitals/char/items).
     */
    private static LuaValue readFood() {
        BAttrWnd w = battrwnd();
        if(w == null)
            return LuaValue.NIL;
        LuaTable t = new LuaTable();
        try {
            BAttrWnd.FoodMeter fm = w.feps;
            if(fm != null) {
                LuaTable fep = new LuaTable();
                fep.set("cap", LuaValue.valueOf(fm.cap));
                LuaTable entries = new LuaTable();
                double total = 0;
                int i = 0;
                for(BAttrWnd.FoodMeter.El el : new ArrayList<BAttrWnd.FoodMeter.El>(fm.els)) {
                    LuaTable e = new LuaTable();
                    try {
                        Resource r = el.res.get();
                        if(r != null)
                            e.set("res", LuaValue.valueOf(r.name));
                        BAttrWnd.FoodMeter.Event ev = el.ev();
                        if((ev != null) && (ev.nm != null))
                            e.set("name", LuaValue.valueOf(ev.nm));
                    } catch(RuntimeException ex) {
                        /* this event's resource is still Loading — keep the amount */
                    }
                    e.set("amount", LuaValue.valueOf(el.a));
                    total += el.a;
                    entries.set(++i, e);
                }
                fep.set("total", LuaValue.valueOf(total));
                fep.set("entries", entries);
                t.set("fep", fep);
            }
        } catch(RuntimeException e) {
            /* partial snapshot is fine while food data streams in */
        }
        try {
            BAttrWnd.GlutMeter gm = w.glut;
            if(gm != null) {
                LuaTable h = new LuaTable();
                h.set("level", LuaValue.valueOf(gm.glut));
                if(gm.lbl != null)
                    h.set("label", LuaValue.valueOf(gm.lbl));
                h.set("efficacy", LuaValue.valueOf(gm.gmod));
                t.set("hunger", h);
            }
        } catch(RuntimeException e) {
            /* partial */
        }
        return t;
    }

    // ------------------------------------------------------------- event dispatch

    /** Fire an event to every owner (all addons + the REPL). */
    static void fire(String event, LuaValue... args) {
        for(Addon a : addons)
            fireTo(a, event, args);
        Addon c = consoleOwner;
        if(c != null)
            fireTo(c, event, args);
    }

    /** Fire an event to a single owner's matching subscriptions. */
    static void fireTo(Addon a, String event, LuaValue... args) {
        for(Sub s : a.subs) {
            if(!s.alive) {
                a.subs.remove(s);
                continue;
            }
            if(s.event.equals(event))
                callLua(a, s.fn, args);
        }
    }

    /** Call into Lua with full error isolation (a Lua error never escapes the engine step). */
    private static void callLua(Addon owner, LuaValue fn, LuaValue... args) {
        try {
            fn.invoke((args.length == 0) ? LuaValue.NONE : LuaValue.varargsOf(args));
        } catch(LuaError e) {
            log(owner, "handler error: " + e.getMessage());
        } catch(RuntimeException e) {
            log(owner, "handler error: " + e);
        }
    }

    // ------------------------------------------------------------- the hafen facade

    /** Install the stable {@code hafen.*} facade into an owner's Lua env (addons and the REPL). */
    private static void installHafen(Globals g, final Addon owner) {
        LuaTable hafen = new LuaTable();

        // hafen.gob.*(ref) — the canonical per-gob accessor. ref = gob id, "player"/"me", or nil
        // (=player). Each call re-resolves the gob → always fresh; returns nil if it's gone. Unknown
        // tokens ("target"/"partyN"/…) resolve to nil for now (added with their subsystems).
        LuaTable gob = new LuaTable();
        gob.set("exists", new OneArgFunction() {
            public LuaValue call(LuaValue ref) {
                return LuaValue.valueOf(resolve(ref) != null);
            }
        });
        gob.set("info", new OneArgFunction() {
            public LuaValue call(LuaValue ref) {
                return gobSnapshot(resolve(ref));
            }
        });
        gob.set("pos", new OneArgFunction() {
            public LuaValue call(LuaValue ref) {
                Coord2d rc = pos(ref);
                if(rc == null)
                    return LuaValue.NIL;
                LuaTable t = new LuaTable();
                t.set("x", LuaValue.valueOf(rc.x));
                t.set("y", LuaValue.valueOf(rc.y));
                return t;
            }
        });
        gob.set("facing", new OneArgFunction() {
            public LuaValue call(LuaValue ref) {
                Gob g = resolve(ref);
                if(g == null)
                    return LuaValue.NIL;
                synchronized(g) {
                    return LuaValue.valueOf(g.a);
                }
            }
        });
        gob.set("name", new OneArgFunction() {
            public LuaValue call(LuaValue ref) {
                Gob g = resolve(ref);
                String n = (g == null) ? null : gobName(g);
                return (n == null) ? LuaValue.NIL : LuaValue.valueOf(n);
            }
        });
        gob.set("health", new OneArgFunction() {
            public LuaValue call(LuaValue ref) {
                Gob g = resolve(ref);
                if(g == null)
                    return LuaValue.NIL;
                GobHealth h = g.getattr(GobHealth.class);
                return (h == null) ? LuaValue.NIL : LuaValue.valueOf(h.hp);
            }
        });
        gob.set("moving", new OneArgFunction() {
            public LuaValue call(LuaValue ref) {
                Gob g = resolve(ref);
                if(g == null)
                    return LuaValue.NIL;
                return LuaValue.valueOf(g.getattr(Moving.class) != null);
            }
        });
        gob.set("speed", new OneArgFunction() {
            public LuaValue call(LuaValue ref) {
                Gob g = resolve(ref);
                if(g == null)
                    return LuaValue.NIL;
                Moving mv = g.getattr(Moving.class);
                if(mv == null)
                    return LuaValue.NIL;
                try {
                    return LuaValue.valueOf(mv.getv());
                } catch(RuntimeException e) {
                    return LuaValue.NIL;
                }
            }
        });
        gob.set("speech", new OneArgFunction() {
            public LuaValue call(LuaValue ref) {
                Gob g = resolve(ref);
                String s = (g == null) ? null : gobSpeech(g);
                return (s == null) ? LuaValue.NIL : LuaValue.valueOf(s);
            }
        });
        gob.set("icon", new OneArgFunction() {
            public LuaValue call(LuaValue ref) {
                Gob g = resolve(ref);
                String s = (g == null) ? null : gobIcon(g);
                return (s == null) ? LuaValue.NIL : LuaValue.valueOf(s);
            }
        });
        // distance(ref [, ref2]); ref2 defaults to "player".
        gob.set("distance", new TwoArgFunction() {
            public LuaValue call(LuaValue ref, LuaValue ref2) {
                Gob a = resolve(ref);
                Gob b = resolve(ref2.isnil() ? LuaValue.valueOf("player") : ref2);
                if((a == null) || (b == null))
                    return LuaValue.NIL;
                Coord2d ra, rb;
                synchronized(a) { ra = a.rc; }
                synchronized(b) { rb = b.rc; }
                if((ra == null) || (rb == null))
                    return LuaValue.NIL;
                return LuaValue.valueOf(ra.dist(rb));
            }
        });
        hafen.set("gob", gob);

        // hafen.world.* — enumerate gobs as snapshots. nearest/within measure from the player and skip
        // the player's own gob. Prefer the GobAdded/GobRemoved events over per-frame scanning.
        LuaTable world = new LuaTable();
        world.set("gobs", new OneArgFunction() {
            public LuaValue call(LuaValue filter) {
                LuaTable out = new LuaTable();
                int i = 0;
                for(Gob g : allGobs()) {
                    LuaValue snap = gobSnapshot(g);
                    if(matches(filter, snap))
                        out.set(++i, snap);
                }
                return out;
            }
        });
        world.set("count", new OneArgFunction() {
            public LuaValue call(LuaValue filter) {
                List<Gob> all = allGobs();
                if(filter.isnil())
                    return LuaValue.valueOf(all.size());
                int n = 0;
                for(Gob g : all)
                    if(matches(filter, gobSnapshot(g)))
                        n++;
                return LuaValue.valueOf(n);
            }
        });
        world.set("nearest", new OneArgFunction() {
            public LuaValue call(LuaValue filter) {
                Gob pl = playerGob();
                if(pl == null)
                    return LuaValue.NIL;
                Coord2d prc;
                synchronized(pl) { prc = pl.rc; }
                if(prc == null)
                    return LuaValue.NIL;
                long self = pl.id;
                LuaValue best = LuaValue.NIL;
                double bestd = Double.POSITIVE_INFINITY;
                for(Gob g : allGobs()) {
                    if(g.id == self)
                        continue;
                    LuaValue snap = gobSnapshot(g);
                    if(!matches(filter, snap))
                        continue;
                    double d = distTo(snap, prc);
                    if(Double.isNaN(d) || (d >= bestd))
                        continue;
                    bestd = d;
                    best = snap;
                }
                return best;
            }
        });
        world.set("within", new TwoArgFunction() {
            public LuaValue call(LuaValue radius, LuaValue filter) {
                double r = radius.optdouble(0);
                LuaTable out = new LuaTable();
                Gob pl = playerGob();
                if(pl == null)
                    return out;
                Coord2d prc;
                synchronized(pl) { prc = pl.rc; }
                if(prc == null)
                    return out;
                long self = pl.id;
                int i = 0;
                for(Gob g : allGobs()) {
                    if(g.id == self)
                        continue;
                    LuaValue snap = gobSnapshot(g);
                    if(!matches(filter, snap))
                        continue;
                    double d = distTo(snap, prc);
                    if(Double.isNaN(d) || (d > r))
                        continue;
                    out.set(++i, snap);
                }
                return out;
            }
        });
        hafen.set("world", world);

        // hafen.map.* — terrain reads. Positional args are WORLD coords (matching hafen.gob.pos);
        // convert with worldToTile/tileToWorld/tileToGrid. Grid-backed reads swallow Loading (the map
        // for that spot isn't here yet) → nil. Grid ids are 64-bit → exposed as decimal STRINGS so the
        // persistent/shareable anchor round-trips exactly (Lua numbers are doubles; see gridPos).
        LuaTable map = new LuaTable();
        map.set("tile", new TwoArgFunction() {
            public LuaValue call(LuaValue x, LuaValue y) {
                MCache mc = mcache();
                if((mc == null) || !x.isnumber() || !y.isnumber())
                    return LuaValue.NIL;
                try {
                    Coord tc = Coord2d.of(x.todouble(), y.todouble()).floor(MCache.tilesz);
                    int id = mc.gettile(tc);
                    LuaTable t = new LuaTable();
                    t.set("id", LuaValue.valueOf(id));
                    Resource r = mc.tilesetr(id);
                    if(r != null)
                        t.set("name", LuaValue.valueOf(r.name));
                    return t;
                } catch(RuntimeException e) {   // Loading etc.
                    return LuaValue.NIL;
                }
            }
        });
        map.set("height", new TwoArgFunction() {
            public LuaValue call(LuaValue x, LuaValue y) {
                MCache mc = mcache();
                if((mc == null) || !x.isnumber() || !y.isnumber())
                    return LuaValue.NIL;
                try {
                    return LuaValue.valueOf(mc.getcz(x.todouble(), y.todouble()));
                } catch(RuntimeException e) {
                    return LuaValue.NIL;
                }
            }
        });
        map.set("grid", new TwoArgFunction() {
            public LuaValue call(LuaValue x, LuaValue y) {
                MCache mc = mcache();
                if((mc == null) || !x.isnumber() || !y.isnumber())
                    return LuaValue.NIL;
                try {
                    Coord tc = Coord2d.of(x.todouble(), y.todouble()).floor(MCache.tilesz);
                    MCache.Grid g = mc.getgrid(tc.div(MCache.cmaps));
                    LuaTable t = new LuaTable();
                    t.set("id", LuaValue.valueOf(Long.toString(g.id)));   // 64-bit → string (exact anchor)
                    t.set("gc", xy(g.gc.x, g.gc.y));
                    return t;
                } catch(RuntimeException e) {
                    return LuaValue.NIL;
                }
            }
        });
        // gridPos([x,y]) — the shareable/persistent position: stable grid id + within-grid WORLD offset
        // (0..1100). No args = the player. Use this, not raw rc, across sessions/players.
        map.set("gridPos", new TwoArgFunction() {
            public LuaValue call(LuaValue x, LuaValue y) {
                MCache mc = mcache();
                if(mc == null)
                    return LuaValue.NIL;
                Coord2d wc = (x.isnumber() && y.isnumber())
                    ? Coord2d.of(x.todouble(), y.todouble())
                    : pos(LuaValue.NIL);   // player
                if(wc == null)
                    return LuaValue.NIL;
                try {
                    MCache.Grid g = mc.getgrid(wc.floor(MCache.tilesz).div(MCache.cmaps));
                    LuaTable t = new LuaTable();
                    t.set("gridId", LuaValue.valueOf(Long.toString(g.id)));
                    t.set("x", LuaValue.valueOf(wc.x - (g.ul.x * MCache.tilesz.x)));
                    t.set("y", LuaValue.valueOf(wc.y - (g.ul.y * MCache.tilesz.y)));
                    return t;
                } catch(RuntimeException e) {
                    return LuaValue.NIL;
                }
            }
        });
        // Pure coordinate conversions (no map data needed). worldToTile floors; tileToWorld returns the
        // tile's upper-left world corner; tileToGrid floor-divides into grid coords.
        map.set("worldToTile", new TwoArgFunction() {
            public LuaValue call(LuaValue x, LuaValue y) {
                if(!x.isnumber() || !y.isnumber())
                    return LuaValue.NIL;
                Coord tc = Coord2d.of(x.todouble(), y.todouble()).floor(MCache.tilesz);
                return xy(tc.x, tc.y);
            }
        });
        map.set("tileToWorld", new TwoArgFunction() {
            public LuaValue call(LuaValue tx, LuaValue ty) {
                if(!tx.isnumber() || !ty.isnumber())
                    return LuaValue.NIL;
                return xy(tx.todouble() * MCache.tilesz.x, ty.todouble() * MCache.tilesz.y);
            }
        });
        map.set("tileToGrid", new TwoArgFunction() {
            public LuaValue call(LuaValue tx, LuaValue ty) {
                if(!tx.isnumber() || !ty.isnumber())
                    return LuaValue.NIL;
                Coord gc = Coord.of((int)tx.todouble(), (int)ty.todouble()).div(MCache.cmaps);
                return xy(gc.x, gc.y);
            }
        });
        hafen.set("map", map);

        // hafen.player.* — only data with NO per-gob equivalent (position/health/moving/… of the player
        // come from hafen.gob.*("player")). name() is the LOCAL character name (GameUI.chrid); other
        // players' display names are not reliably available. worldToScreen is MAP-VIEW-relative pixels.
        LuaTable player = new LuaTable();
        player.set("exists", new ZeroArgFunction() {
            public LuaValue call() {
                MapView m = view;
                return LuaValue.valueOf((m != null) && (m.plgob >= 0));
            }
        });
        player.set("id", new ZeroArgFunction() {
            public LuaValue call() {
                MapView m = view;
                return ((m == null) || (m.plgob < 0)) ? LuaValue.NIL : LuaValue.valueOf((double)m.plgob);
            }
        });
        player.set("name", new ZeroArgFunction() {
            public LuaValue call() {
                GameUI g = gui();
                return ((g == null) || (g.chrid == null)) ? LuaValue.NIL : LuaValue.valueOf(g.chrid);
            }
        });
        // vitals() — {hp,stamina,energy} bar fractions (0..1), read live from the HUD meters via the
        // widget-tree mechanism (1d). Bar-fraction ONLY: no absolute values, no hunger (those don't
        // exist as client state — coverage-gaps B5). nil until the meters are up. Subscribe to
        // VitalsChanged for updates; the initial values arrive as widget-creation args, not a uimsg.
        player.set("vitals", new ZeroArgFunction() {
            public LuaValue call() {
                return readVitals();
            }
        });
        player.set("worldToScreen", new TwoArgFunction() {
            public LuaValue call(LuaValue x, LuaValue y) {
                MapView m = view;
                if((m == null) || !x.isnumber() || !y.isnumber())
                    return LuaValue.NIL;
                try {
                    Coord3f sc = m.screenxf(Coord2d.of(x.todouble(), y.todouble()));
                    return (sc == null) ? LuaValue.NIL : xy(sc.x, sc.y);
                } catch(RuntimeException e) {
                    return LuaValue.NIL;
                }
            }
        });
        hafen.set("player", player);

        // hafen.time.* — game clock + astronomy. clock() is always available; the astronomy readers are
        // nil until the first "astro" update lands (Glob.ast is nil before then).
        LuaTable time = new LuaTable();
        time.set("clock", new ZeroArgFunction() {
            public LuaValue call() {
                Glob g = glob();
                return (g == null) ? LuaValue.NIL : LuaValue.valueOf(g.globtime());
            }
        });
        time.set("dayFraction", new ZeroArgFunction() {
            public LuaValue call() {
                Astronomy a = astro();
                return (a == null) ? LuaValue.NIL : LuaValue.valueOf(a.dt);
            }
        });
        time.set("isNight", new ZeroArgFunction() {
            public LuaValue call() {
                Astronomy a = astro();
                return (a == null) ? LuaValue.NIL : LuaValue.valueOf(a.night);
            }
        });
        time.set("season", new ZeroArgFunction() {
            public LuaValue call() {
                Astronomy a = astro();
                return (a == null) ? LuaValue.NIL : LuaValue.valueOf(a.is);
            }
        });
        time.set("moon", new ZeroArgFunction() {
            public LuaValue call() {
                Astronomy a = astro();
                return (a == null) ? LuaValue.NIL : LuaValue.valueOf(a.mp);
            }
        });
        time.set("yearFraction", new ZeroArgFunction() {
            public LuaValue call() {
                Astronomy a = astro();
                return (a == null) ? LuaValue.NIL : LuaValue.valueOf(a.yt);
            }
        });
        hafen.set("time", time);

        // hafen.sound.play(resname) — fire a client sound. The resource resolves OFF the UI thread
        // (loader.defer, mirroring GobIcon.resnotif) so a not-yet-loaded resource never throws Loading
        // into Lua. Client-bundled names resolve locally (e.g. "sfx/msg", "sfx/error").
        LuaTable sound = new LuaTable();
        sound.set("play", new OneArgFunction() {
            public LuaValue call(LuaValue resname) {
                if(resname.isstring())
                    playSound(resname.tojstring());
                return LuaValue.NIL;
            }
        });
        hafen.set("sound", sound);

        // hafen.music.play(resname, loop) — background music (a content resource; interrupts current
        // music). A nil/empty resname STOPS playback. Music.play takes a lazy Indir and resolves on its
        // own player thread, so no defer is needed here.
        LuaTable music = new LuaTable();
        music.set("play", new TwoArgFunction() {
            public LuaValue call(LuaValue resname, LuaValue loop) {
                if(!resname.isstring() || resname.tojstring().isEmpty()) {
                    Music.play(null, false);            // stop
                } else {
                    Music.play(Resource.remote().load(resname.tojstring()), loop.optboolean(false));
                }
                return LuaValue.NIL;
            }
        });
        hafen.set("music", music);

        // hafen.items.* — inventory / equipment / cursor items as snapshots (the "Item" shape in
        // api-reference.md). Items have no stable addon-visible id yet, so bulk reads return
        // point-in-time snapshots carrying name/res/num/wear/pos; per-item live accessors wait for
        // item handles (the UI phase). Reads walk the WItem children of the inventory/equipory widgets
        // (both public) → zero core edit; item names/resources are Loading-guarded → nil while resolving.
        LuaTable items = new LuaTable();
        items.set("inventory", new ZeroArgFunction() {
            public LuaValue call() {
                LuaTable out = new LuaTable();
                Inventory inv = maininv();
                if(inv == null)
                    return out;
                int i = 0;
                for(WItem w : inv.children(WItem.class))
                    out.set(++i, itemSnapshot(w.item, cellPos(w)));
                return out;
            }
        });
        items.set("equipment", new ZeroArgFunction() {
            public LuaValue call() {
                LuaTable out = new LuaTable();
                Equipory eq = equipory();
                if(eq == null)
                    return out;
                int i = 0;
                for(WItem w : eq.children(WItem.class)) {
                    int ep = slotOf(eq, w);
                    LuaValue snap = itemSnapshot(w.item, slotName(ep));
                    if((ep >= 0) && snap.istable())
                        ((LuaTable)snap).set("slot", LuaValue.valueOf(ep));
                    out.set(++i, snap);
                }
                return out;
            }
        });
        items.set("hand", new ZeroArgFunction() {
            public LuaValue call() {
                GameUI g = gui();
                if((g == null) || (g.vhand == null))
                    return LuaValue.NIL;
                return itemSnapshot(g.vhand.item, LuaValue.NIL);
            }
        });
        items.set("find", new OneArgFunction() {
            public LuaValue call(LuaValue q) {
                LuaTable out = new LuaTable();
                Inventory inv = maininv();
                if((inv == null) || !q.isstring())
                    return out;
                String needle = q.tojstring();
                int i = 0;
                for(WItem w : inv.children(WItem.class)) {
                    LuaValue snap = itemSnapshot(w.item, cellPos(w));
                    LuaValue nm = snap.get("name"), rs = snap.get("res");
                    if((nm.isstring() && nm.tojstring().contains(needle)) ||
                       (rs.isstring() && rs.tojstring().contains(needle)))
                        out.set(++i, snap);
                }
                return out;
            }
        });
        hafen.set("items", items);

        // hafen.char.* — character attributes (Glob.getcattr; a zero-info entry is reported as nil),
        // plus learning points (CharWnd.exp) and encumbrance/weight (CharWnd.enc) — public live fields
        // on the character window (created hidden at login). attrs() returns the nine base attributes
        // that have data, keyed by name. ("char" is a Java keyword → the local is named "chr".)
        LuaTable chr = new LuaTable();
        chr.set("attr", new OneArgFunction() {
            public LuaValue call(LuaValue name) {
                return name.isstring() ? attrSnapshot(name.tojstring()) : LuaValue.NIL;
            }
        });
        chr.set("attrs", new ZeroArgFunction() {
            public LuaValue call() {
                LuaTable out = new LuaTable();
                for(String nm : ATTR_NAMES) {
                    LuaValue a = attrSnapshot(nm);
                    if(!a.isnil())
                        out.set(nm, a);
                }
                return out;
            }
        });
        chr.set("lp", new ZeroArgFunction() {
            public LuaValue call() {
                CharWnd c = charwnd();
                return (c == null) ? LuaValue.NIL : LuaValue.valueOf(c.exp);
            }
        });
        chr.set("weight", new ZeroArgFunction() {
            public LuaValue call() {
                CharWnd c = charwnd();
                return (c == null) ? LuaValue.NIL : LuaValue.valueOf(c.enc);
            }
        });
        // food() — FEP + hunger via the widget-tree mechanism (BAttrWnd; 1d-2). Returns
        // { fep = {cap,total,entries={{res,name,amount}}}, hunger = {level,label,efficacy} } or nil
        // until the character sheet's base-attributes tab exists (it streams in after enter-world).
        // Subscribe to FepChanged for updates (fired on the server's "food"/"glut" uimsgs).
        chr.set("food", new ZeroArgFunction() {
            public LuaValue call() {
                return readFood();
            }
        });
        // skills() — the character's KNOWN skills as {name, res} snapshots; skill(name) — a substring
        // membership test over them (name OR res, matching hafen.buffs.has). Backed by the SkillWnd
        // "Skills" tab (widget-tree), which streams in after enter-world like the rest of the sheet.
        // Credos and experiences (the other SkillWnd tabs) are deferred.
        chr.set("skills", new ZeroArgFunction() {
            public LuaValue call() {
                return readSkills();
            }
        });
        chr.set("skill", new OneArgFunction() {
            public LuaValue call(LuaValue name) {
                return (name.isstring() && hasSkill(name.tojstring())) ? LuaValue.TRUE : LuaValue.FALSE;
            }
        });
        hafen.set("char", chr);

        // hafen.study.* — the study window (curiosities being studied), via the widget-tree mechanism
        // (1d-3). slots() = the curiosities, each {res,name,lp,attention,cost,time,progress?}; summary()
        // = the live totals {lp,attention,cost}. Both empty/nil until the character sheet's "Abilities"
        // (sattr) tab streams in, a beat after enter-world. Subscribe to StudyChanged (fired per-tick
        // when the slots change — an add/remove or study data resolving), not per frame.
        LuaTable study = new LuaTable();
        study.set("slots", new ZeroArgFunction() {
            public LuaValue call() {
                SAttrWnd.StudyInfo si = studyInfo();
                return (si == null) ? new LuaTable() : readStudySlots(si.study);
            }
        });
        study.set("summary", new ZeroArgFunction() {
            public LuaValue call() {
                return studySummary();
            }
        });
        hafen.set("study", study);

        // hafen.party.* — the party roster (Glob.party). Members are ordered by Member.seq (the ordinal
        // behind the "partyN" GobRef). A PartyMember is DERIVED: id=gobid, x,y=getc() (live gob pos if in
        // view, else last-known), color={r,g,b,a}, leader=(member==party.leader). There is NO name field
        // for party members (a client/protocol limitation).
        LuaTable party = new LuaTable();
        party.set("members", new ZeroArgFunction() {
            public LuaValue call() {
                LuaTable out = new LuaTable();
                int i = 0;
                for(Party.Member m : partyMembers())
                    out.set(++i, memberSnapshot(m));
                return out;
            }
        });
        party.set("leader", new ZeroArgFunction() {
            public LuaValue call() {
                Party p = party();
                return ((p == null) || (p.leader == null)) ? LuaValue.NIL : memberSnapshot(p.leader);
            }
        });
        party.set("member", new OneArgFunction() {
            public LuaValue call(LuaValue id) {
                Party p = party();
                if((p == null) || !id.isnumber())
                    return LuaValue.NIL;
                Party.Member m = p.memb.get(Long.valueOf((long)id.todouble()));
                return (m == null) ? LuaValue.NIL : memberSnapshot(m);
            }
        });
        hafen.set("party", party);

        // hafen.buffs.* — active buffs/debuffs (GameUI.buffs → Buff widgets), via the widget-tree
        // mechanism (1d-2). list() returns Buff snapshots {res,name,amount,cooldown,number}; amount/
        // cooldown/number are 0..1 fractions / an integer from resource-published ItemInfo (often nil,
        // NOT seconds). A buff fading out after removal is omitted. Subscribe to BuffAdded/BuffRemoved/
        // BuffChanged (add/remove detected per-tick; content changes on the buff's "ch"/"tt" uimsg).
        LuaTable buffs = new LuaTable();
        buffs.set("list", new ZeroArgFunction() {
            public LuaValue call() {
                LuaTable out = new LuaTable();
                Bufflist bl = bufflist();
                if(bl == null)
                    return out;
                int i = 0;
                for(Buff b : bl.children(Buff.class)) {
                    if(!AddonWidgets.buffDest(b))
                        out.set(++i, buffSnapshot(b));
                }
                return out;
            }
        });
        buffs.set("has", new OneArgFunction() {
            public LuaValue call(LuaValue q) {
                if(!q.isstring())
                    return LuaValue.FALSE;
                String needle = q.tojstring();
                Bufflist bl = bufflist();
                if(bl == null)
                    return LuaValue.FALSE;
                for(Buff b : bl.children(Buff.class)) {
                    if(AddonWidgets.buffDest(b))
                        continue;
                    String res = buffRes(b), name = buffName(b);
                    if(((res != null) && res.contains(needle)) || ((name != null) && name.contains(needle)))
                        return LuaValue.TRUE;
                }
                return LuaValue.FALSE;
            }
        });
        hafen.set("buffs", buffs);

        hafen.set("log", new OneArgFunction() {
            public LuaValue call(LuaValue msg) {
                log(owner, msg.isnil() ? "nil" : msg.tojstring());
                return LuaValue.NIL;
            }
        });

        // hafen.events.on(name, fn) -> handle; handle:off() unsubscribes.
        LuaTable events = new LuaTable();
        events.set("on", new TwoArgFunction() {
            public LuaValue call(LuaValue name, LuaValue fn) {
                if(!name.isstring() || !fn.isfunction())
                    throw new LuaError("hafen.events.on(name, fn) expects (string, function)");
                final Sub sub = new Sub(owner, name.tojstring(), fn);
                owner.subs.add(sub);
                LuaTable h = new LuaTable();
                h.set("off", new ZeroArgFunction() {
                    public LuaValue call() {
                        sub.alive = false;
                        owner.subs.remove(sub);
                        return LuaValue.NIL;
                    }
                });
                return h;
            }
        });
        hafen.set("events", events);

        // hafen.timer.after(sec, fn) one-shot · hafen.timer.every(sec, fn) repeating.
        // Both return a handle; handle:cancel() stops it.
        LuaTable timer = new LuaTable();
        timer.set("after", new TwoArgFunction() {
            public LuaValue call(LuaValue sec, LuaValue fn) {
                return newTimer(owner, sec, fn, false);
            }
        });
        timer.set("every", new TwoArgFunction() {
            public LuaValue call(LuaValue sec, LuaValue fn) {
                return newTimer(owner, sec, fn, true);
            }
        });
        hafen.set("timer", timer);

        g.set("hafen", hafen);
    }

    private static LuaValue newTimer(final Addon owner, LuaValue sec, LuaValue fn, boolean repeat) {
        if(!sec.isnumber() || !fn.isfunction())
            throw new LuaError("hafen.timer expects (seconds, function)");
        double s = sec.todouble();
        if(s < 0)
            s = 0;
        final Timer t = new Timer(owner, clock + s, repeat ? s : 0, fn);
        owner.timers.add(t);
        LuaTable h = new LuaTable();
        h.set("cancel", new ZeroArgFunction() {
            public LuaValue call() {
                t.alive = false;
                owner.timers.remove(t);
                return LuaValue.NIL;
            }
        });
        return h;
    }

    /** Engine-level output: stdout (prefixed) and in-game notice when available. */
    static void log(String msg) {
        System.out.println("[addon] " + msg);
        UI u = ui;
        if(u != null) {
            try {
                u.msg(msg);
            } catch(RuntimeException e) {
                /* pre-HUD or no notice sink yet; stdout still has it */
            }
        }
    }

    /** Addon-level output ({@code hafen.log} + handler errors): tagged with the addon id. */
    static void log(Addon owner, String msg) {
        String id = ((owner != null) && (owner.manifest != null)) ? owner.manifest.id : "addon";
        System.out.println("[" + id + "] " + msg);
        UI u = ui;
        if(u != null) {
            try {
                u.msg(id + ": " + msg);
            } catch(RuntimeException e) {
                /* pre-HUD or no notice sink yet; stdout still has it */
            }
        }
    }

    // ------------------------------------------------------------- :lua REPL

    private static synchronized Globals console() {
        if(consoleOwner == null) {
            Globals g = JsePlatform.standardGlobals();
            Addon owner = new Addon(Manifest.internal("(console)"), null, g);
            installHafen(g, owner);
            consoleOwner = owner;
        }
        return consoleOwner.env;
    }

    private static void eval(String src) {
        if(src.isEmpty())
            return;
        UI u = ui;
        System.out.println("[console] :lua " + src);               // echo the input to the terminal
        try {
            LuaValue chunk;
            try {
                chunk = console().load("return " + src, "=lua");   // expression form: show its value
            } catch(LuaError e) {
                chunk = console().load(src, "=lua");                // statement form (e.g. print(...))
            }
            LuaValue r = chunk.call();
            if(!r.isnil()) {
                String out = "lua= " + json(r);
                System.out.println("[console] " + out);            // ...and mirror the result there
                if(u != null)
                    u.msg(out);
            }
        } catch(LuaError e) {
            String err = "lua: " + e.getMessage();
            System.out.println("[console] " + err);
            if(u != null)
                u.error(err);
        }
    }

    // ------------------------------------------------------------- GobRef resolution + snapshots

    /** The player body resource — identity test for the {@code isplayer} snapshot field. */
    private static final String PLAYER_RES = "gfx/borka/body";

    /** The live session root ({@link Glob}), or {@code null} before a session/world is up. */
    private static Glob glob() {
        MapView m = view;
        if((m == null) || (m.ui == null) || (m.ui.sess == null))
            return null;
        return m.ui.sess.glob;
    }

    /** The live object cache, or {@code null} before a session/world is up. */
    private static OCache oc() {
        Glob g = glob();
        return (g == null) ? null : g.oc;
    }

    /** The live map cache, or {@code null} before a session/world is up. */
    private static MCache mcache() {
        Glob g = glob();
        return (g == null) ? null : g.map;
    }

    /** The current astronomy snapshot, or {@code null} before the first "astro" update. */
    private static Astronomy astro() {
        Glob g = glob();
        return (g == null) ? null : g.ast;
    }

    /**
     * The in-game HUD ({@link GameUI}). Fast path: walk up from the map view. Fallback: scan down from
     * {@code ui.root} — right at {@code OnEnterWorld} the map view exists (it fired the event) but may
     * not be parented to {@code GameUI} yet, whereas {@code GameUI} is already a child of the root
     * (its widget message arrives before the map view's). {@code null} before the HUD is up.
     */
    private static GameUI gui() {
        MapView m = view;
        if(m != null) {
            GameUI g = m.getparent(GameUI.class);
            if(g != null)
                return g;
        }
        UI u = ui;
        return (u == null) ? null : findGui(u.root);
    }

    /** Depth-first search of the widget tree for the (unique) {@link GameUI}. */
    private static GameUI findGui(Widget w) {
        for(Widget c = (w == null) ? null : w.child; c != null; c = c.next) {
            if(c instanceof GameUI)
                return (GameUI)c;
            GameUI g = findGui(c);
            if(g != null)
                return g;
        }
        return null;
    }

    /** A Lua {@code {x=..,y=..}} table (the shape returned by the coordinate/position readers). */
    private static LuaValue xy(double x, double y) {
        LuaTable t = new LuaTable();
        t.set("x", LuaValue.valueOf(x));
        t.set("y", LuaValue.valueOf(y));
        return t;
    }

    /**
     * Play a client sound by resource name without blocking the UI thread: resolve the resource on a
     * loader thread ({@code Loading} re-runs the task), then hand the clip to {@link UI#sfx}. Mirrors
     * {@code GobIcon.resnotif}. Non-{@code Loading} resolve failures are reported and swallowed.
     */
    private static void playSound(final String name) {
        final Glob g = glob();
        final UI u = ui;
        if((g == null) || (u == null))
            return;
        final Indir<Resource> resid = Resource.local().load(name);
        g.loader.defer(new Runnable() {
            public void run() {
                Resource res;
                try {
                    res = resid.get();               // Loading → the loader re-runs this task
                } catch(Loading l) {
                    throw(l);
                } catch(RuntimeException e) {
                    u.error("addon: could not play " + name);
                    return;
                }
                u.sfx(Audio.fromres(res));
            }
        }, null);
    }

    private static Gob getgob(long id) {
        OCache oc = oc();
        return (oc == null) ? null : oc.getgob(id);
    }

    private static Gob playerGob() {
        MapView m = view;
        return (m == null) ? null : m.player();
    }

    /**
     * Resolve a GobRef to a live {@link Gob}: {@code nil}/"player"/"me" = the player, {@code "partyN"}
     * = the Nth party member by {@link Party.Member#seq} (nil if out of view), a number (or numeric
     * string) = that gob id. Other unknown string tokens ("target"/"mouseover"/…) return {@code null}
     * for now — they are wired up when their subsystems land. Never throws into Lua.
     */
    private static Gob resolve(LuaValue ref) {
        MapView m = view;
        if(m == null)
            return null;
        try {
            if((ref == null) || ref.isnil())
                return m.player();
            if(ref.isnumber())
                return getgob((long)ref.todouble());
            String s = ref.tojstring();
            if(s.equals("player") || s.equals("me"))
                return m.player();
            if(s.startsWith("party")) {         // "partyN" → member N by seq (empty/non-numeric → NFE → nil)
                Party.Member pm = partyMemberByOrdinal(Integer.parseInt(s.substring(5)));
                return (pm == null) ? null : getgob(pm.gobid);
            }
            return getgob(Long.parseLong(s));   // numeric string; unknown token → NumberFormatException
        } catch(RuntimeException e) {
            return null;
        }
    }

    /** Resolve a GobRef to a live position (backs {@code hafen.gob.pos}). */
    private static Coord2d pos(LuaValue ref) {
        Gob g = resolve(ref);
        if(g == null)
            return null;
        synchronized(g) {
            return g.rc;
        }
    }

    /** A copy of the live gob list (taken under the OCache lock; snapshots built by the caller). */
    private static List<Gob> allGobs() {
        List<Gob> out = new ArrayList<Gob>();
        OCache oc = oc();
        if(oc == null)
            return out;
        synchronized(oc) {
            for(Gob g : oc)
                out.add(g);
        }
        return out;
    }

    /**
     * Does {@code snap} pass {@code filter}? {@code nil} → all; a string → substring match on the
     * gob's {@code name}; a function → called with the snapshot, truthy keeps it (errors drop it).
     */
    private static boolean matches(LuaValue filter, LuaValue snap) {
        if((filter == null) || filter.isnil())
            return true;
        if(filter.isfunction()) {
            try {
                return filter.call(snap).toboolean();
            } catch(RuntimeException e) {   // LuaError is a RuntimeException
                return false;
            }
        }
        if(filter.isstring()) {
            LuaValue name = snap.get("name");
            return name.isstring() && name.tojstring().contains(filter.tojstring());
        }
        return true;
    }

    /** Distance from {@code from} to a snapshot's {@code {x,y}}, or NaN if it has no position. */
    private static double distTo(LuaValue snap, Coord2d from) {
        LuaValue x = snap.get("x"), y = snap.get("y");
        if(!x.isnumber() || !y.isnumber())
            return Double.NaN;
        return from.dist(Coord2d.of(x.todouble(), y.todouble()));
    }

    // -- per-attribute readers (each Loading-guarded: resource-backed reads can throw before load) --

    private static String gobName(Gob g) {
        try {
            Drawable d = g.getattr(Drawable.class);
            if(d == null)
                return null;
            Resource r = d.getres();   // may throw Loading, or be null before it resolves
            return (r == null) ? null : r.name;
        } catch(RuntimeException e) {
            return null;
        }
    }

    private static String gobSpeech(Gob g) {
        try {
            Speaking sp = g.getattr(Speaking.class);
            return ((sp == null) || (sp.text == null)) ? null : sp.text.text;
        } catch(RuntimeException e) {
            return null;
        }
    }

    private static String gobIcon(Gob g) {
        try {
            GobIcon ic = g.getattr(GobIcon.class);
            if(ic == null)
                return null;
            GobIcon.Icon icon = ic.icon();   // resolves the icon resource; may throw Loading
            return (icon == null) ? null : icon.name();
        } catch(RuntimeException e) {
            return null;
        }
    }

    /** Best-effort active-overlay resource names ({@code Gob.ols}); unresolved ones are skipped. */
    private static LuaTable overlayNames(Gob g) {
        LuaTable out = new LuaTable();
        int i = 0;
        try {
            for(Gob.Overlay ol : g.ols) {
                try {
                    if((ol.spr != null) && (ol.spr.res != null))
                        out.set(++i, LuaValue.valueOf(ol.spr.res.name));
                } catch(RuntimeException e) {
                    /* skip an overlay still resolving */
                }
            }
        } catch(RuntimeException e) {
            /* concurrent overlay mutation etc. — return what we have */
        }
        return out;
    }

    /**
     * A full gob snapshot (the {@code Gob} shape in api-reference.md), used by {@code hafen.gob.info},
     * {@code hafen.world.*}, and the {@code GobAdded}/{@code GobRemoved} payloads. Read on the UI
     * thread under the gob lock; every field is optional and defensive against transient/{@code
     * Loading} state (a partial snapshot is fine while world data is still resolving).
     */
    private static LuaValue gobSnapshot(Gob g) {
        if(g == null)
            return LuaValue.NIL;
        LuaTable t = new LuaTable();
        try {
            synchronized(g) {
                t.set("id", LuaValue.valueOf((double)g.id));
                Coord2d rc = g.rc;
                if(rc != null) {
                    t.set("x", LuaValue.valueOf(rc.x));
                    t.set("y", LuaValue.valueOf(rc.y));
                }
                t.set("angle", LuaValue.valueOf(g.a));
                String name = gobName(g);
                if(name != null) {
                    t.set("name", LuaValue.valueOf(name));
                    t.set("isplayer", LuaValue.valueOf(name.equals(PLAYER_RES)));
                }
                GobHealth h = g.getattr(GobHealth.class);
                if(h != null)
                    t.set("hp", LuaValue.valueOf(h.hp));
                Moving mv = g.getattr(Moving.class);
                t.set("moving", LuaValue.valueOf(mv != null));
                if(mv != null) {
                    try {
                        t.set("speed", LuaValue.valueOf(mv.getv()));
                    } catch(RuntimeException e) {
                        /* speed unavailable this frame */
                    }
                }
                String speech = gobSpeech(g);
                if(speech != null)
                    t.set("speech", LuaValue.valueOf(speech));
                String icon = gobIcon(g);
                if(icon != null)
                    t.set("icon", LuaValue.valueOf(icon));
                LuaTable ols = overlayNames(g);
                if(ols.length() > 0)
                    t.set("overlays", ols);
            }
        } catch(RuntimeException e) {
            /* partial snapshot is fine (e.g. world data still resolving) */
        }
        return t;
    }

    // ------------------------------------------------------------- items / char / party reads

    /** The nine base character attributes (content-defined; not discoverable from {@link Glob}). */
    private static final String[] ATTR_NAMES =
        {"str", "agi", "int", "con", "prc", "csm", "dex", "wil", "psy"};

    /** The player's main inventory widget, or {@code null} before the HUD/inventory exists. */
    private static Inventory maininv() {
        GameUI g = gui();
        return (g == null) ? null : g.maininv;
    }

    /**
     * The player's equipment widget: the {@link Equipory} under the HUD. {@code GameUI.equwnd} is a
     * private {@code Window}, so we descend to the Equipory itself — typically the only one open (a
     * second appears only while inspecting another gob's equipment). {@code null} before it exists.
     */
    private static Equipory equipory() {
        GameUI g = gui();
        if(g != null) {
            for(Equipory e : g.children(Equipory.class))
                return e;
        }
        return null;
    }

    /** The character window (created hidden at login, but live), or {@code null} before it exists. */
    private static CharWnd charwnd() {
        GameUI g = gui();
        return (g == null) ? null : g.chrwdg;
    }

    /** The inventory grid cell {@code {x,y}} of an inventory {@link WItem} (reverses the placement). */
    private static LuaValue cellPos(WItem w) {
        Coord cell = w.c.sub(1, 1).div(Inventory.sqsz);
        return xy(cell.x, cell.y);
    }

    /** The equipment slot index of a {@link WItem} under an {@link Equipory}, or {@code -1}. */
    private static int slotOf(Equipory eq, WItem w) {
        try {
            return eq.epat(w.c);
        } catch(RuntimeException e) {
            return -1;
        }
    }

    /** The human-readable equipment slot name for an ep index, or nil. */
    private static LuaValue slotName(int ep) {
        if((ep >= 0) && (ep < Equipory.etts.length) && (Equipory.etts[ep] != null))
            return LuaValue.valueOf(Equipory.etts[ep].text);
        return LuaValue.NIL;
    }

    /** {@code ItemInfo.Name} display text for an item, or {@code null} (Loading-guarded). */
    private static String itemName(GItem it) {
        try {
            ItemInfo.Name n = ItemInfo.find(ItemInfo.Name.class, it.info());
            return ((n == null) || (n.str == null)) ? null : n.str.text;
        } catch(RuntimeException e) {   // Loading etc.
            return null;
        }
    }

    /** Resource name (stable identity) for an item, or {@code null} (Loading-guarded). */
    private static String itemRes(GItem it) {
        try {
            Resource r = it.res.get();
            return (r == null) ? null : r.name;
        } catch(RuntimeException e) {
            return null;
        }
    }

    /**
     * An Item snapshot (the {@code Item} shape in api-reference.md) from a {@link GItem}. {@code pos}
     * is supplied by the caller (grid cell for inventory, slot name for equipment; nil for the hand).
     * Every field is optional / Loading-guarded: {@code num == -1} and {@code meter == 0} are treated
     * as "absent" (matching the client's own convention). {@code quality}/{@code contents} are deferred
     * (content-defined value / container widgets).
     */
    private static LuaValue itemSnapshot(GItem it, LuaValue pos) {
        if(it == null)
            return LuaValue.NIL;
        LuaTable t = new LuaTable();
        String res = itemRes(it);
        if(res != null)
            t.set("res", LuaValue.valueOf(res));
        String name = itemName(it);
        if(name != null)
            t.set("name", LuaValue.valueOf(name));
        if(it.num != -1)
            t.set("num", LuaValue.valueOf(it.num));
        if(it.meter > 0)
            t.set("wear", LuaValue.valueOf(it.meter));   // 0..100 %, only meaningful when > 0
        if((pos != null) && !pos.isnil())
            t.set("pos", pos);
        return t;
    }

    /**
     * A character attribute {@code {base, comp}} for a name, or nil. {@link Glob#getcattr} never
     * returns {@code null} (it auto-creates a zero entry for unknown names), so a {@code base==0 &&
     * comp==0} entry is reported as nil ("not populated by the server yet").
     */
    private static LuaValue attrSnapshot(String name) {
        Glob g = glob();
        if(g == null)
            return LuaValue.NIL;
        Glob.CAttr a = g.getcattr(name);
        if((a == null) || ((a.base == 0) && (a.comp == 0)))
            return LuaValue.NIL;
        LuaTable t = new LuaTable();
        t.set("base", LuaValue.valueOf(a.base));
        t.set("comp", LuaValue.valueOf(a.comp));
        return t;
    }

    // -- study / curiosity + skills (1d-3): all-public reads off the character sheet (no haven edit) --

    /**
     * The character sheet's study widget ({@link SAttrWnd}, the "Abilities / Study Report" tab) → its
     * {@link SAttrWnd.StudyInfo} (which references the study inventory and holds the live totals), or
     * {@code null} until the sattr tab streams in (a beat after enter-world, like {@code battr}). There
     * is exactly one StudyInfo per study inventory, so the first hit is it.
     */
    private static SAttrWnd.StudyInfo studyInfo() {
        CharWnd c = charwnd();
        if((c == null) || (c.sattr == null))
            return null;
        for(SAttrWnd.StudyInfo si : c.sattr.children(SAttrWnd.StudyInfo.class))
            return si;
        return null;
    }

    /** Read a study inventory's {@link GItem} children into an array of curiosity snapshots. */
    private static LuaValue readStudySlots(Widget study) {
        LuaTable out = new LuaTable();
        if(study == null)
            return out;
        int i = 0;
        for(GItem it : study.children(GItem.class))
            out.set(++i, studySnapshot(it));
        return out;
    }

    /**
     * A study-slot snapshot: {@code res}/{@code name} (the curiosity item) plus its {@link Curiosity}
     * study profile — {@code lp} (learning points), {@code attention} (mental weight), {@code cost}
     * (experience cost), {@code time} (total study time, seconds). {@code progress} (0..1) is the item
     * meter, best-effort (present only when the client tracks it for that item). All Loading-guarded:
     * {@code res} may be the only field until the item's info resolves, then the rest fills in (which
     * surfaces as another {@code StudyChanged}). NB {@code time} is the TOTAL study time — the client
     * has no per-item countdown, so there is no true "time left".
     */
    private static LuaValue studySnapshot(GItem it) {
        if(it == null)
            return LuaValue.NIL;
        LuaTable t = new LuaTable();
        String res = itemRes(it);
        if(res != null)
            t.set("res", LuaValue.valueOf(res));
        String name = itemName(it);
        if(name != null)
            t.set("name", LuaValue.valueOf(name));
        try {
            Curiosity ci = ItemInfo.find(Curiosity.class, it.info());   // may throw Loading
            if(ci != null) {
                t.set("lp", LuaValue.valueOf(ci.exp));
                t.set("attention", LuaValue.valueOf(ci.mw));
                t.set("cost", LuaValue.valueOf(ci.enc));
                t.set("time", LuaValue.valueOf(ci.time));
            }
        } catch(RuntimeException e) {
            /* info still Loading — res/name may be set; the Curiosity fields arrive on a later read */
        }
        if(it.meter > 0)
            t.set("progress", LuaValue.valueOf(it.meter / 100.0));   // 0..1, best-effort (item meter)
        return t;
    }

    /** Do two study-slot arrays carry the same items/fields? (positional; for change-detection.) */
    private static boolean studySlotsEqual(LuaValue a, LuaValue b) {
        if((a == null) || (b == null) || !a.istable() || !b.istable())
            return false;
        int n = a.length();
        if(n != b.length())
            return false;
        for(int i = 1; i <= n; i++) {
            LuaValue ea = a.get(i), eb = b.get(i);
            if(!luaFieldEq(ea, eb, "res") || !luaFieldEq(ea, eb, "name") || !luaFieldEq(ea, eb, "lp")
               || !luaFieldEq(ea, eb, "attention") || !luaFieldEq(ea, eb, "cost")
               || !luaFieldEq(ea, eb, "time") || !luaFieldEq(ea, eb, "progress"))
                return false;
        }
        return true;
    }

    /**
     * Study totals from {@link SAttrWnd.StudyInfo} (recomputed live each tick): {@code lp} (total
     * learning points across the curiosities), {@code attention} (total mental weight used — compare
     * with {@code hafen.char.attr("int").comp}, the cap), {@code cost} (total experience cost). nil
     * until the study window exists.
     */
    private static LuaValue studySummary() {
        SAttrWnd.StudyInfo si = studyInfo();
        if(si == null)
            return LuaValue.NIL;
        LuaTable t = new LuaTable();
        t.set("lp", LuaValue.valueOf(si.texp));
        t.set("attention", LuaValue.valueOf(si.tw));
        t.set("cost", LuaValue.valueOf(si.tenc));
        return t;
    }

    /** The character sheet's "Lore &amp; Skills" widget ({@link SkillWnd}), or {@code null}. */
    private static SkillWnd skillwnd() {
        CharWnd c = charwnd();
        return (c == null) ? null : c.skill;
    }

    /** Display name of a skill: the resource tooltip, else the internal skill token ({@code Skill.nm}). */
    private static String skillName(SkillWnd.Skill s) {
        try {
            Resource r = s.res.get();
            if(r != null) {
                Resource.Tooltip tt = r.layer(Resource.tooltip);
                if((tt != null) && (tt.t != null))
                    return tt.t;
            }
        } catch(RuntimeException e) {   // Loading etc.
        }
        return s.nm;
    }

    /** Resource name (stable identity) of a skill, or {@code null} (Loading-guarded). */
    private static String skillRes(SkillWnd.Skill s) {
        try {
            Resource r = s.res.get();
            return (r == null) ? null : r.name;
        } catch(RuntimeException e) {
            return null;
        }
    }

    /**
     * The character's KNOWN skills ({@code SkillWnd.skg.csk}) as {@code {name, res}} snapshots.
     * {@code name} is always present (the resource tooltip, else the internal token); {@code res} is
     * Loading-guarded. The list is copied defensively (the {@code Group.items} reference is swapped
     * wholesale off-thread by the {@code csk}/{@code nsk} uimsgs). "Available to learn" ({@code nsk})
     * and credos/experiences are deferred.
     */
    private static LuaValue readSkills() {
        LuaTable out = new LuaTable();
        SkillWnd w = skillwnd();
        if(w == null)
            return out;
        int i = 0;
        try {
            for(SkillWnd.Skill s : new ArrayList<SkillWnd.Skill>(w.skg.csk.items)) {
                LuaTable t = new LuaTable();
                t.set("name", LuaValue.valueOf(skillName(s)));
                String res = skillRes(s);
                if(res != null)
                    t.set("res", LuaValue.valueOf(res));
                out.set(++i, t);
            }
        } catch(RuntimeException e) {
            /* skg/csk not ready or the list was swapped mid-read — return what we have */
        }
        return out;
    }

    /** Does the character KNOW a skill whose name or resource contains {@code needle}? */
    private static boolean hasSkill(String needle) {
        SkillWnd w = skillwnd();
        if(w == null)
            return false;
        try {
            for(SkillWnd.Skill s : new ArrayList<SkillWnd.Skill>(w.skg.csk.items)) {
                String name = skillName(s), res = skillRes(s);
                if(((name != null) && name.contains(needle)) || ((res != null) && res.contains(needle)))
                    return true;
            }
        } catch(RuntimeException e) {
            /* list swapped mid-read — treat as not found */
        }
        return false;
    }

    /** The live {@link Party}, or {@code null} before a session is up. */
    private static Party party() {
        Glob g = glob();
        return (g == null) ? null : g.party;
    }

    /**
     * Party members ordered by {@link Party.Member#seq} (the ordinal behind the {@code "partyN"}
     * GobRef). {@code party.memb} is replaced wholesale off-thread, so a {@code values()} copy is
     * snapshot-safe (defensive catch for the rare in-flight swap).
     */
    private static List<Party.Member> partyMembers() {
        List<Party.Member> out = new ArrayList<Party.Member>();
        Party p = party();
        if(p == null)
            return out;
        try {
            out.addAll(p.memb.values());
        } catch(RuntimeException e) {
            return out;
        }
        out.sort((a, b) -> Integer.compare(a.seq, b.seq));
        return out;
    }

    /** The Nth party member (1-based, by seq order) for the {@code "partyN"} GobRef, or {@code null}. */
    private static Party.Member partyMemberByOrdinal(int n) {
        if(n < 1)
            return null;
        List<Party.Member> ms = partyMembers();
        return (n <= ms.size()) ? ms.get(n - 1) : null;
    }

    /** A PartyMember snapshot: id / x,y / color / leader (there is no name for party members). */
    private static LuaValue memberSnapshot(Party.Member m) {
        if(m == null)
            return LuaValue.NIL;
        LuaTable t = new LuaTable();
        t.set("id", LuaValue.valueOf((double)m.gobid));
        Coord2d c = m.getc();               // live gob pos if in view, else last-known; may be null
        if(c != null) {
            t.set("x", LuaValue.valueOf(c.x));
            t.set("y", LuaValue.valueOf(c.y));
        }
        if(m.col != null)
            t.set("color", color(m.col));
        Party p = party();
        t.set("leader", LuaValue.valueOf((p != null) && (p.leader == m)));
        return t;
    }

    /** A {@code {r,g,b,a}} table (0..255) for an AWT color. */
    private static LuaValue color(java.awt.Color c) {
        LuaTable t = new LuaTable();
        t.set("r", LuaValue.valueOf(c.getRed()));
        t.set("g", LuaValue.valueOf(c.getGreen()));
        t.set("b", LuaValue.valueOf(c.getBlue()));
        t.set("a", LuaValue.valueOf(c.getAlpha()));
        return t;
    }

    private static String join(String[] args) {
        StringBuilder sb = new StringBuilder();
        for(int i = 1; i < args.length; i++) {
            if(sb.length() > 0)
                sb.append(' ');
            sb.append(args[i]);
        }
        return sb.toString();
    }

    /** Drop the leading command word (and following whitespace) from a raw console line. */
    private static String stripCmd(String line) {
        int i = 0;
        while((i < line.length()) && !Character.isWhitespace(line.charAt(i)))
            i++;
        while((i < line.length()) && Character.isWhitespace(line.charAt(i)))
            i++;
        return line.substring(i);
    }

    // ------------------------------------------------------------- owned-resource records

    /** A live event subscription: {@code hafen.events.on(event, fn)} in addon {@code owner}. */
    public static final class Sub {
        final Addon owner;
        final String event;
        final LuaValue fn;
        boolean alive = true;

        Sub(Addon owner, String event, LuaValue fn) {
            this.owner = owner;
            this.event = event;
            this.fn = fn;
        }
    }

    /** A live timer: {@code due} is engine-clock seconds; {@code interval<=0} means one-shot. */
    public static final class Timer {
        final Addon owner;
        double due;
        final double interval;
        final LuaValue fn;
        boolean alive = true;

        Timer(Addon owner, double due, double interval, LuaValue fn) {
            this.owner = owner;
            this.due = due;
            this.interval = interval;
            this.fn = fn;
        }
    }

    /** A gob spawn/despawn captured off-thread, awaiting UI-thread dispatch. */
    private static final class GobEvent {
        final boolean added;
        final Gob gob;

        GobEvent(boolean added, Gob gob) {
            this.added = added;
            this.gob = gob;
        }
    }

    // -------------------------------------------------- compact JSON for the REPL (copy-friendly)

    /** Serialize a Lua value to compact single-line JSON so console output is inspectable/copyable. */
    private static String json(LuaValue v) {
        StringBuilder sb = new StringBuilder();
        json(v, sb, java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
        return sb.toString();
    }

    private static void json(LuaValue v, StringBuilder sb, java.util.Set<LuaValue> seen) {
        if(v.isnil()) {
            sb.append("null");
        } else if(v.isboolean()) {
            sb.append(v.toboolean() ? "true" : "false");
        } else if(v instanceof LuaNumber) {
            double d = v.todouble();
            if(!Double.isFinite(d))
                sb.append("null");                       // JSON has no NaN/Infinity
            else if((d == Math.rint(d)) && (Math.abs(d) < 1e15))
                sb.append(Long.toString((long)d));       // clean integers (no trailing .0)
            else
                sb.append(Double.toString(d));
        } else if(v instanceof LuaString) {
            jsonstr(v.tojstring(), sb);
        } else if(v instanceof LuaTable) {
            jsontab((LuaTable)v, sb, seen);
        } else {
            jsonstr(v.tojstring(), sb);                  // function/userdata/thread → quoted tostring
        }
    }

    private static void jsontab(LuaTable t, StringBuilder sb, java.util.Set<LuaValue> seen) {
        if(!seen.add(t)) {                               // break reference cycles
            sb.append("\"<cycle>\"");
            return;
        }
        try {
            LuaValue[] keys = t.keys();
            int len = t.length();
            boolean array = (keys.length == len);
            if(array) {
                for(LuaValue k : keys) {
                    if(!k.isint() || (k.toint() < 1) || (k.toint() > len)) {
                        array = false;
                        break;
                    }
                }
            }
            if(array) {
                sb.append('[');
                for(int i = 1; i <= len; i++) {
                    if(i > 1)
                        sb.append(',');
                    json(t.get(i), sb, seen);
                }
                sb.append(']');
            } else {
                sb.append('{');
                boolean first = true;
                for(LuaValue k : keys) {
                    if(!first)
                        sb.append(',');
                    first = false;
                    jsonstr(k.tojstring(), sb);           // JSON keys are strings
                    sb.append(':');
                    json(t.get(k), sb, seen);
                }
                sb.append('}');
            }
        } finally {
            seen.remove(t);
        }
    }

    private static void jsonstr(String s, StringBuilder sb) {
        sb.append('"');
        for(int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch(c) {
            case '"':  sb.append("\\\""); break;
            case '\\': sb.append("\\\\"); break;
            case '\n': sb.append("\\n"); break;
            case '\r': sb.append("\\r"); break;
            case '\t': sb.append("\\t"); break;
            case '\b': sb.append("\\b"); break;
            case '\f': sb.append("\\f"); break;
            default:
                if(c < 0x20)
                    sb.append(String.format("\\u%04x", (int)c));
                else
                    sb.append(c);
            }
        }
        sb.append('"');
    }
}
