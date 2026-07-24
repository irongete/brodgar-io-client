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
import haven.GOut;
import haven.IMeter;
import haven.Indir;
import haven.Inventory;
import haven.ItemInfo;
import haven.KeyBinding;
import haven.KeyMatch;
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
import haven.Window;
import haven.resutil.Curiosity;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaNumber;
import org.luaj.vm2.LuaString;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.ThreeArgFunction;
import org.luaj.vm2.lib.TwoArgFunction;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.ZeroArgFunction;

import java.awt.event.KeyEvent;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
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
    private static volatile boolean reloadPending;      // set by :reload (any thread), applied on the UI tick
    private static double clock;                        // seconds accumulated from tick dt (UI thread)
    private static final Queue<GobEvent> gobEvents = new ConcurrentLinkedQueue<GobEvent>();

    // -- custom UI overlays (spec 07 / Phase 2b): HUD overlays + world-space gob overlays -----------------
    // HUD overlays paint ON TOP of the HUD via a one-shot UI.drawafter re-registered each tick (drawafter
    // is cleared every UI.draw; tick precedes draw in the frame loop, so the afterdraw runs this same frame
    // after root.draw — above GameUI). Gob overlays attach a shared LuaGobOverlay attrib to each matching
    // gob (the SpeakerIcon pattern); a THROTTLED sweep evaluates filters + attaches, the per-frame draw
    // re-checks filters + paints. All on the UI thread (paint runs inside UI.draw).
    private static final LuaGOut hudGout = new LuaGOut();                 // shared g wrapper for the HUD pass
    private static final UI.AfterDraw hudAfterDraw = new UI.AfterDraw() { // one-shot afterdraw, re-queued each tick
        public void draw(GOut g) { paintHudOverlays(g); }
    };
    private static double lastGobSweep;                                   // engine-clock of the last gob sweep
    private static final double GOB_SWEEP_INTERVAL =                      // gob-overlay filter sweep period (s)
        Double.parseDouble(System.getProperty("haven.addon.gobsweepsec", "0.2"));

    // -- action hooks (spec 13 §L2 / Phase 2d): intercept the outbound UI.wdgmsg action stream ------------
    // hafen.hook.action(msg, fn) installs a pre-hook at the single outbound choke point (the UI.java edit
    // calls onWdgmsg here). Keyed by action name for a near-zero fast path when a given msg is unhooked, and
    // globally empty when no addon hooks anything (the common case). Lua runs ONLY while the current thread
    // holds the UI monitor (onWdgmsg's holdsLock guard) — the click send comes from the render thread but
    // under synchronized(ui), and tick/draw hold it too, so hook Lua never races other Lua. Session-scoped.
    private static final Map<String, List<LuaActionHook>> actionHooks =
        new ConcurrentHashMap<String, List<LuaActionHook>>();
    private static boolean dispatchingAction;   // re-entrancy guard (a hook body that itself sends a wdgmsg)

    // -- message hooks (spec 13 §L3 / Phase 2e): intercept the inbound UI.uimsg server-update stream ---------
    // hafen.hook.message(msg, fn) installs a pre-hook at the single inbound choke point (the UI.java edit in
    // UiMessage.run calls onMessage here, BEFORE the widget applies the update). Keyed by message name for a
    // near-zero fast path when a given msg is unhooked, and globally empty when no addon hooks anything (the
    // common case — and uimsg application is hot). Runs on a Loader thread but always inside UiMessage.run's
    // synchronized(ui) block, the same monitor tick/draw hold, so hook Lua never races other Lua. Session-scoped.
    private static final Map<String, List<LuaMessageHook>> messageHooks =
        new ConcurrentHashMap<String, List<LuaMessageHook>>();

    // -- global hotkeys (spec 07 "Input" / Phase 2e-2): hafen.key.bind over the KeyBinding registry --------
    // hafen.key.bind(name, defaultKey, fn) pairs a remappable+persisted client KeyBinding with a Lua handler.
    // Dispatch is the engine's built-in GlobKeyEvent seam (ZERO core edit, like 2c's Widget.listen): UI.keydown
    // fires a GlobKeyEvent ONLY after an unconsumed focused KeyDownEvent (so a hotkey never fires while a text
    // field has focus), which walks the widget tree calling globtype; AddonRoot.globtype runs onGlobKey below.
    // A flat list (not a per-name map — keys match by KeyMatch, not by string) iterated per unconsumed keypress
    // (NOT per frame — cheap); owned copies live on each Addon for teardown. Runs on the UI thread (input
    // dispatch), like an input hook, so callLua goes straight through with no thread guard.
    private static final List<LuaKeyBind> keyBinds = new CopyOnWriteArrayList<LuaKeyBind>();

    // Named keys the hotkey-string parser recognizes; anything else that is a single char goes through
    // KeyMatch.forchar (letters, digits, symbols). Built once (KeyEvent VK_* are compile-time constants).
    private static final Map<String, Integer> KEYCODES = new HashMap<String, Integer>();
    static {
        for(int i = 1; i <= 12; i++)                       // F1..F12 (VK_F1..VK_F12 are consecutive)
            KEYCODES.put("F" + i, KeyEvent.VK_F1 + (i - 1));
        KEYCODES.put("SPACE",     KeyEvent.VK_SPACE);
        KEYCODES.put("ENTER",     KeyEvent.VK_ENTER);
        KEYCODES.put("RETURN",    KeyEvent.VK_ENTER);
        KEYCODES.put("TAB",       KeyEvent.VK_TAB);
        KEYCODES.put("ESC",       KeyEvent.VK_ESCAPE);
        KEYCODES.put("ESCAPE",    KeyEvent.VK_ESCAPE);
        KEYCODES.put("BACKSPACE", KeyEvent.VK_BACK_SPACE);
        KEYCODES.put("DELETE",    KeyEvent.VK_DELETE);
        KEYCODES.put("DEL",       KeyEvent.VK_DELETE);
        KEYCODES.put("INSERT",    KeyEvent.VK_INSERT);
        KEYCODES.put("INS",       KeyEvent.VK_INSERT);
        KEYCODES.put("HOME",      KeyEvent.VK_HOME);
        KEYCODES.put("END",       KeyEvent.VK_END);
        KEYCODES.put("PAGEUP",    KeyEvent.VK_PAGE_UP);
        KEYCODES.put("PGUP",      KeyEvent.VK_PAGE_UP);
        KEYCODES.put("PAGEDOWN",  KeyEvent.VK_PAGE_DOWN);
        KEYCODES.put("PGDN",      KeyEvent.VK_PAGE_DOWN);
        KEYCODES.put("UP",        KeyEvent.VK_UP);
        KEYCODES.put("DOWN",      KeyEvent.VK_DOWN);
        KEYCODES.put("LEFT",      KeyEvent.VK_LEFT);
        KEYCODES.put("RIGHT",     KeyEvent.VK_RIGHT);
    }

    // -- widget-creation interception (spec 08 / Phase 3a): observe server widgets as the server creates them ---
    // hafen.ui.onWidgetCreate(fn) fires fn(desc) for every SERVER widget as it is placed into the tree, where
    // desc = {id, type, place, caption, parentType} (the targeting descriptor, D-024). Two UI.java edits feed it:
    // NewWidget.run records the server type name (onWidgetCreated), AddWidget.run fires onWidgetPlaced once the
    // widget is in the tree — the first moment place + parent exist. onWidgetPlaced runs inside AddWidget.run's
    // synchronized(ui) block (the monitor tick/draw hold), so observer Lua never races other Lua. A FLAT list
    // (observers watch EVERY creation, not one keyed target); globally empty = a near-zero fast path, so an
    // unobserving client pays only an isEmpty() check per placement. widgetTypes holds only in-flight creations
    // (recorded at NewWidget, removed at the matching AddWidget) and is recorded only while an observer exists;
    // owned observer copies live on each Addon for teardown. Both are session-scoped (cleared per init).
    private static final Map<Integer, String> widgetTypes = new ConcurrentHashMap<Integer, String>();
    private static final List<LuaWidgetObserver> widgetObservers = new CopyOnWriteArrayList<LuaWidgetObserver>();

    // -- adopted widget models (spec 08 / Phase 3b): hafen.ui.adopt(id) wraps a live server-bound widget so an
    // addon can hide it as a headless model + present a custom view (D-009). A FLAT global list, polled each tick
    // (pollModels) for item add/remove (a WItem create/cdestroy, not a uimsg) and server destroy (its id stops
    // mapping to the widget); globally empty = a near-zero fast path. Owned copies live on each Addon for teardown
    // (which un-hides anything the addon hid, restoring the stock UI). Session-scoped (cleared per init).
    private static final List<LuaModel> models = new CopyOnWriteArrayList<LuaModel>();

    // -- widget replacers (spec 08 / Phase 3c): hafen.ui.replace(type, opts, fn) — the high-level sugar over 3a+3b.
    // Watch for a server widget matching a descriptor, then adopt+hide it and hand the addon a custom view (D-009).
    // A FLAT global list consulted at widget placement (onWidgetPlaced, alongside the observers); registration also
    // SCANS the live tree once to catch an already-open target (the :reload case, where no creation event fires).
    // Owned copies live on each Addon for teardown. Session-scoped (cleared per init; widget ids are per-session).
    private static final List<LuaReplacer> widgetReplacers = new CopyOnWriteArrayList<LuaReplacer>();

    // -- widget-tree read mechanism (spec 14): Locator + Adapters + inbound-uimsg update hook -------
    // Adapters read a GameUI widget tree into a Lua snapshot and fire a semantic event on change. The
    // UI.uimsg core tap runs off the UI thread, so it only marks the interested adapter(s) dirty; the
    // tick re-reads + fires on the UI thread (principle P5). Both collections are session-scoped.
    private static final List<TreeAdapter> treeAdapters = new CopyOnWriteArrayList<TreeAdapter>();
    private static final Set<TreeAdapter> treeDirty = ConcurrentHashMap.newKeySet();
    private static LuaValue vitalsCache;                 // last vitals snapshot (UI thread; change-detect)

    // -- saved variables (spec 1e / D-002 / D-023): hafen.store persisted as JSON under savedata/ ------
    // Per-character vars key on <genus>_<char>, known only once the HUD is up (OnEnterWorld) — captured
    // here and reused on flush so a relog (which rebinds ui before the new GameUI exists) still writes to
    // the OLD character's folder. Account-scope vars need no char and load at addon-load time.
    private static volatile String charScope;           // "<genus>_<char>" once in-world, else null
    private static double lastAutoSave;                  // engine-clock time of the last throttled flush
    private static final double SAVE_INTERVAL = 30.0;    // throttled auto-save period (seconds; UI thread)

    // -- enabled set + reload (spec 1f-2 / D-005 / D-006): which addons run, persisted client-side --------
    // WoW "apply on reload" model: toggling enable/disable updates a persisted DISABLED set (an addon runs
    // unless its id is in it — so a freshly-installed addon defaults to enabled) and marks changes pending;
    // the change takes effect on the next :reload / login, never live. Stored in the client's own
    // preferences (Utils.getprefsl → under the client folder), NOT per-character.
    private static final String PREF_DISABLED = "addons/disabled";
    private static volatile boolean reloadNeeded;        // enabled set changed since the last (re)load
    private static volatile int reloadGen;               // bumped by each completed reload() (the AddOns panel watches it)

    // -- soft CPU-budget auto-disable (spec 1f-3 / D-018 layer 2): id -> reason for an addon torn down
    // mid-session by the per-tick CPU watchdog. This is a SESSION action (not the persisted disabled set),
    // surfaced in the AddOns panel and cleared on the next (re)load so the addon gets a fresh start.
    private static final Map<String, String> autoDisabledWarn = new ConcurrentHashMap<String, String>();

    private AddonManager() {
    }

    static {
        // Use the RAW console line (quotes intact) so string literals survive; args are pre-split
        // by Utils.splitwords, which strips quotes. Fall back to joined args if the raw line is absent.
        Console.setscmd("lua", (cons, args) -> {
            String raw = cons.rawcmd();
            eval((raw != null) ? stripCmd(raw) : join(args));
        });
        // :addons               list every discovered addon + its status (loaded version / disabled / error)
        // :addons enable  <id>  mark an addon enabled  (applied on the next :reload — D-006)
        // :addons disable <id>  mark an addon disabled (applied on the next :reload — D-006)
        Console.setscmd("addons", (cons, args) -> {
            if((args.length >= 3) && "enable".equals(args[1])) {
                setEnabled(args[2], true);
                log("addon '" + args[2] + "' enabled (pending — run :reload to apply)");
            } else if((args.length >= 3) && "disable".equals(args[1])) {
                setEnabled(args[2], false);
                log("addon '" + args[2] + "' disabled (pending — run :reload to apply)");
            } else {
                listAddons();
            }
        });
        // :reload  reload the addon layer from disk (D-005) — no relog. Queued to the UI-thread tick.
        Console.setscmd("reload", (cons, args) -> queueReload());
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
        for(Addon a : addons)         // fire OnDisable + flush saved vars + drop owned resources
            teardown(a);              // (flushes with the OLD charScope, still set from the last session)
        addons.clear();

        clock = 0;
        enterWorldPending = false;
        gobEvents.clear();
        addonRoot = null;
        ocCb = null;

        charScope = null;             // per-char saved-var folder is unknown until the new HUD is up
        lastAutoSave = 0;
        lastGobSweep = 0;             // 2b: sweep gob overlays promptly on the new session
        reloadPending = false;        // drop any :reload queued against the previous session

        widgetTypes.clear();          // 3a: drop in-flight widget-type records (ids are per-session)
        models.clear();               // 3b: drop adopted-widget models (widget ids are per-session; addon-owned
        widgetReplacers.clear();      // 3c: drop widget replacers (they re-scan/re-register on the new session)
        if(consoleOwner != null) {    //     addon-owned models/replacers were dropped by the teardown loop above —
            consoleOwner.models.clear();      // this catches the REPL owner's (it is not in `addons`)
            consoleOwner.replacers.clear();
        }
        treeDirty.clear();            // reset the widget-tree read mechanism for the new session
        vitalsCache = null;
        treeAdapters.clear();
        treeAdapters.add(new VitalsAdapter());   // hp/stamina/energy (uimsg-driven)
        treeAdapters.add(new BuffsAdapter());    // buff add/remove (per-tick poll) + change (uimsg)
        treeAdapters.add(new FepAdapter());      // FEP/food + hunger (uimsg-driven)
        treeAdapters.add(new StudyAdapter());    // study/curiosity slots (per-tick poll)
        treeAdapters.add(new ActionbarAdapter()); // action-bar / hotbar slots (per-tick poll)
        treeAdapters.add(new EquipAdapter());    // equipment add/remove (per-tick poll)

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
        reloadNeeded = false;         // whatever is on disk now IS the applied enabled set
        autoDisabledWarn.clear();     // a (re)load gives every addon a fresh start (drop session warnings)
        File dir = addonDir();
        log("addons dir: " + dir);
        File[] subs = dir.listFiles(File::isDirectory);
        if(subs == null) {
            log("no addons/ directory");
            return;
        }
        Set<String> disabled = disabledSet();   // D-006: honor the persisted enabled set (skip disabled)
        for(File sub : subs) {
            if(!new File(sub, "manifest.json").isFile())
                continue;
            if(disabled.contains(sub.getName())) {
                log("skipping disabled addon '" + sub.getName() + "'");
                continue;
            }
            try {
                Manifest m = Manifest.load(sub.toPath());
                Globals g = Sandbox.create();   // D-017 stdlib whitelist + D-018 instruction watchdog
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

    /** Fire {@code OnDisable}, flush the addon's saved vars, then drop its owned resources. */
    private static void teardown(Addon a) {
        try {
            fireTo(a, "OnDisable");   // the addon's last chance to write its store tables...
        } catch(RuntimeException e) {
            /* isolation is per-handler in callLua; this is just a backstop */
        }
        flush(a);                     // ...then persist them (spec 05: flushed at OnDisable)
        destroyWidgets(a);            // custom UI vanishes cleanly (2a; before subs, so no dangling callbacks)
        teardownModels(a);            // 3b: drop adopted models + un-hide any native widget the addon had hidden
        teardownHooks(a);             // 2c: deafen input hooks (engine widgets outlive a :reload — must detach)
        teardownActionHooks(a);       // 2d: unregister action hooks from the outbound-wdgmsg dispatch map
        teardownMessageHooks(a);      // 2e-1: unregister message hooks from the inbound-uimsg dispatch map
        teardownKeyBinds(a);          // 2e-2: unregister global hotkeys from the GlobKeyEvent dispatch list
        teardownWidgetObservers(a);   // 3a: unregister widget-creation observers from the placement dispatch list
        teardownReplacers(a);         // 3c: stop the replacers matching (models un-hidden above, views destroyed above)
        a.hudOverlays.clear();        // 2b: HUD overlays stop painting immediately (the paint iterates this list)
        a.gobOverlays.clear();        // 2b: gob overlays stop painting immediately
        a.subs.clear();
        a.timers.clear();
        if(!anyGobOverlays())         // no addon wants gob overlays now → detach the idle attribs (reload-safe)
            detachGobOverlays();
    }

    /** Destroy every custom UI widget/window this addon owns (2a). Widget removal locks on {@code ui}. */
    private static void destroyWidgets(Addon a) {
        if(a.widgets.isEmpty())
            return;
        UI u = ui;
        List<LuaWidget> ws = new ArrayList<LuaWidget>(a.widgets);
        a.widgets.clear();
        Runnable kill = () -> {
            for(LuaWidget w : ws) {
                try {
                    w.kill();   // stop callbacks + destroy its root (the window chrome, or the widget)
                } catch(RuntimeException e) {
                    /* a half-attached widget: best-effort, never abort teardown */
                }
            }
        };
        if(u != null) {
            synchronized(u) { kill.run(); }
        } else {
            kill.run();
        }
    }

    // ------------------------------------------------------------- reload + enabled set (1f-2)

    /** Queue a full addon-layer reload; applied on the next UI-thread tick (see {@link #tick}). */
    private static void queueReload() {
        reloadPending = true;
        log("reload queued");
    }

    /**
     * Reload the addon layer only (D-005) — no relog, the session stays connected. Tears down every
     * loaded addon (OnDisable → flush saved vars → drop owned resources, in reverse load order),
     * re-scans {@code addons/} and the enabled set, re-runs the enabled addons from disk (firing
     * {@code OnLoad}), and — if already in-world — restores per-character saved vars and re-fires
     * {@code OnEnterWorld} so addons re-initialize as if freshly logged in (the WoW {@code PLAYER_LOGIN}
     * analog). Runs on the UI thread (queued via {@link #queueReload}); the tick pump, gob callback and
     * uimsg tap are <b>session-scoped</b> and left in place — only the Lua layer is rebuilt. Per-addon
     * teardown/load is error-isolated so one bad addon cannot abort the reload.
     */
    public static synchronized void reload() {
        if(ui == null) {
            log("reload: no active session");
            return;
        }
        log("reloading addons...");
        List<Addon> cur = new ArrayList<Addon>(addons);
        for(int i = cur.size() - 1; i >= 0; i--)     // reverse load order
            teardown(cur.get(i));
        addons.clear();
        loadAll();                                   // re-scan disk + enabled set; re-run; fire OnLoad
        if(gui() != null) {                          // already in-world → re-init as a fresh login
            restorePerChar();                        // reload per-char saved vars (charScope still valid)
            fire("OnEnterWorld");
        }
        reloadGen++;                                 // notify any live AddOns panel to rebuild its rows
        log("reload complete (" + addons.size() + " addon[s] active)");
    }

    /** The persisted set of disabled addon ids (client-scope). An addon runs unless it is in here. */
    private static Set<String> disabledSet() {
        List<String> l = Utils.getprefsl(PREF_DISABLED, new String[0]);
        return (l == null) ? new LinkedHashSet<String>() : new LinkedHashSet<String>(l);
    }

    /** Is an addon enabled? (i.e. NOT in the persisted disabled set — the default for a new addon.) */
    public static boolean isEnabled(String id) {
        return !disabledSet().contains(id);
    }

    /**
     * Persist an addon's enabled state. Per D-006 (WoW model) this does NOT load/unload it live — the
     * change takes effect on the next {@link #reload} / login; {@code reloadNeeded} then flags a pending
     * reload. Idempotent: a no-op change writes nothing.
     */
    public static void setEnabled(String id, boolean enabled) {
        if((id == null) || id.isEmpty())
            return;
        Set<String> d = disabledSet();
        boolean changed = enabled ? d.remove(id) : d.add(id);
        if(changed) {
            Utils.setprefsl(PREF_DISABLED, d);
            reloadNeeded = true;
        }
    }

    /** The loaded addon with this id, or {@code null} if none is loaded (disabled, missing, or errored). */
    private static Addon findLoaded(String id) {
        for(Addon a : addons)
            if((a.manifest != null) && a.manifest.id.equals(id))
                return a;
        return null;
    }

    /** List every discovered addon (a folder with a manifest) and its status: version / disabled / error. */
    private static void listAddons() {
        File dir = addonDir();
        File[] subs = dir.listFiles(File::isDirectory);
        if(subs == null) {
            log("no addons/ directory");
            return;
        }
        Set<String> disabled = disabledSet();
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for(File sub : subs) {
            if(!new File(sub, "manifest.json").isFile())
                continue;
            String id = sub.getName();
            Addon a = findLoaded(id);
            String status;
            if(a != null)
                status = (a.error == null) ? ("v" + a.manifest.version) : "error";
            else
                status = disabled.contains(id) ? "disabled" : "not loaded";
            if(sb.length() > 0)
                sb.append(", ");
            sb.append(id).append(" [").append(status).append("]");
            n++;
        }
        if(n == 0) {
            log("no addons found");
            return;
        }
        log("addons: " + sb + (reloadNeeded ? "  (changes pending — run :reload to apply)" : ""));
    }

    // ------------------------------------------------------------- AddOns options panel API (1f-3)

    /**
     * A snapshot of one discovered addon for the AddOns options panel (spec 10): its manifest metadata
     * plus its live state. Immutable; built by {@link #describeAddons()}.
     */
    public static final class AddonInfo {
        public final String id, name, version, author, description;
        public final int apiVersion;
        public final boolean enabled;   // persisted enabled state (the checkbox) — NOT the live-loaded state
        public final boolean loaded;    // currently running this session
        public final String error;      // load/runtime error, or null
        public final String warning;    // session warning (e.g. auto-disabled by the CPU watchdog), or null

        AddonInfo(String id, String name, String version, String author, String description,
                  int apiVersion, boolean enabled, boolean loaded, String error, String warning) {
            this.id = id; this.name = name; this.version = version; this.author = author;
            this.description = description; this.apiVersion = apiVersion; this.enabled = enabled;
            this.loaded = loaded; this.error = error; this.warning = warning;
        }
    }

    /**
     * Every discovered addon (a folder under {@link #addonDir()} with a {@code manifest.json}), sorted by
     * id, as {@link AddonInfo} for the AddOns panel. Reads each manifest fresh from disk so disabled /
     * not-loaded addons still show name/version/author. Call on panel build/reload (it does disk I/O), not
     * per frame — use {@link #liveStatus(String)} for the cheap per-frame status refresh.
     */
    public static List<AddonInfo> describeAddons() {
        List<AddonInfo> out = new ArrayList<AddonInfo>();
        File dir = addonDir();
        File[] subs = dir.listFiles(File::isDirectory);
        if(subs == null)
            return out;
        java.util.Arrays.sort(subs, (x, y) -> x.getName().compareToIgnoreCase(y.getName()));
        Set<String> disabled = disabledSet();
        for(File sub : subs) {
            if(!new File(sub, "manifest.json").isFile())
                continue;
            String id = sub.getName();
            Manifest m = null;
            try { m = Manifest.load(sub.toPath()); } catch(Exception e) { /* keep an id-only row */ }
            Addon loaded = findLoaded(id);
            String error = (loaded != null) ? loaded.error : ((m == null) ? "manifest error" : null);
            out.add(new AddonInfo(id,
                (m != null) ? m.name : id,
                (m != null) ? m.version : null,
                (m != null) ? m.author : null,
                (m != null) ? m.description : null,
                (m != null) ? m.apiVersion : 0,
                !disabled.contains(id),
                loaded != null,
                error,
                autoDisabledWarn.get(id)));
        }
        return out;
    }

    /**
     * A short live status string for one addon id, cheap enough to call each frame (no manifest I/O): the
     * session auto-disable warning if any, else loaded-version / error / disabled / not-loaded. Backs the
     * per-row status label the AddOns panel refreshes on tick.
     */
    public static String liveStatus(String id) {
        String w = autoDisabledWarn.get(id);
        if(w != null)
            return "auto-disabled (" + w + ")";
        Addon a = findLoaded(id);
        if(a != null)
            return (a.error == null) ? ("loaded v" + a.manifest.version) : ("error: " + a.error);
        return isEnabled(id) ? "not loaded" : "disabled";
    }

    /** Whether the enabled set has changed since the last (re)load (a reload is pending to apply it). */
    public static boolean reloadNeeded() {
        return reloadNeeded;
    }

    /** A counter bumped by each completed {@link #reload}, so a live AddOns panel can detect a rebuild. */
    public static int reloadGen() {
        return reloadGen;
    }

    /** Queue an addon-layer reload from the AddOns panel's "Reload UI" button (applied on the next tick). */
    public static void requestReload() {
        queueReload();
    }

    /** Open the addons folder in the OS file browser (AddOns panel convenience). Best-effort, non-fatal. */
    public static void openAddonsFolder() {
        try {
            java.awt.Desktop.getDesktop().open(addonDir());
        } catch(Exception e) {
            log("could not open addons folder: " + e);
        }
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

            // 0. A queued :reload / Reload UI — rebuild the addon layer on the UI thread (spec 1f-2,
            //    D-005). Done first + return so the reloaded addons begin their own tick cleanly next
            //    frame (this frame's OnUpdate/timers belonged to the addons we just tore down).
            if(reloadPending) {
                reloadPending = false;
                reload();
                return;
            }

            // Soft CPU-budget accounting (D-018 layer 2): zero every addon's per-tick Lua time before any
            // handler runs this tick; callLua accumulates into it, enforceSoftBudget() evaluates it at the
            // end. (Skipped on a reload tick, which returns above — its OnLoad/OnEnterWorld are one-offs.)
            for(Addon a : addons)
                a.tickLuaNanos = 0L;

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

            // 1c. Adopted widget models (3b): per-tick poll for item add/remove (a WItem create/cdestroy on the
            //     model, not a uimsg — like the buff/study adapters) and server destroy (the model's id stops
            //     mapping to its widget → fire onDestroy). Fast-paths out when no addon has adopted anything.
            pollModels();

            // 2. "Entered the world" — fire OnEnterWorld once the HUD (GameUI) is not just built but
            //    ATTACHED to ui.root. The map view sets enterWorldPending from its ctor (loader thread),
            //    and gui() finds GameUI via the map view a beat BEFORE GameUI is added to the RootWidget
            //    (confirmed via the widget-place trace: the old "gui()!=null" signal fired one line before
            //    "ADD GameUI -> RootWidget"). Firing then would add an addon window to ui.root as a sibling
            //    placed *before* GameUI, so the full-screen HUD draws on top of it (invisible until a
            //    :reload re-adds it after GameUI). Gating on gui().parent != null (GameUI is in the tree)
            //    fires the tick after the HUD mounts, so ui.root windows land on top. enterWorldPending is
            //    reset per session in init(), so it can't stick. (GameUI-backed reads still stream in a beat
            //    later — read them on a timer, not synchronously here.)
            if(enterWorldPending) {
                GameUI hud = gui();
                if((hud != null) && (hud.parent != null)) {
                    enterWorldPending = false;
                    restorePerChar();     // now <genus>_<char> is known → load per-char saved vars BEFORE
                    fire("OnEnterWorld");  // the handler runs, so it can read hafen.store (spec 1e)
                }
            }

            // 3. Per-frame update.
            fire("OnUpdate", LuaValue.valueOf(dt));

            // 4. Due timers.
            runTimers();

            // 4b. Custom UI overlays (2b). Sweep gob overlays (throttled): attach a shared LuaGobOverlay to
            //     each gob matching an active filter — the per-frame Render2D pass then re-checks + paints.
            //     Then queue the HUD-overlay afterdraw for THIS frame if any addon has one (see the field
            //     note): UI.drawafter is one-shot, tick precedes draw, so it paints above the HUD this frame.
            sweepGobOverlays();
            UI u = ui;
            if((u != null) && anyHudOverlays())
                u.drawafter(hudAfterDraw);

            // 5. Throttled auto-save of saved variables (mirrors GameUI's window-position saves). Covers
            //    an unclean exit; a relog also flushes via teardown. flush() skips unchanged files, so
            //    this is cheap when nothing changed. On the UI thread → no races reading the Lua tables.
            if(clock - lastAutoSave >= SAVE_INTERVAL) {
                lastAutoSave = clock;
                for(Addon a : addons)
                    flush(a);
            }

            // 6. Soft per-tick CPU budget (D-018 layer 2): auto-disable an addon that has been over budget
            //    for too many consecutive ticks — a sustained runaway the hard per-call cap doesn't catch.
            enforceSoftBudget();
        } catch(RuntimeException e) {
            log("tick error: " + e);
        }
    }

    /**
     * Enforce the soft per-tick CPU budget (D-018 layer 2 / spec 12). Each addon accrued its total Lua
     * time this tick in {@code tickLuaNanos} (via {@link #callLua}); an addon over
     * {@link Sandbox#SOFT_BUDGET_NANOS} adds a strike, one under budget clears the count. On reaching
     * {@link Sandbox#SOFT_STRIKE_LIMIT} consecutive over-budget ticks it is auto-disabled for the session
     * (torn down + a warning surfaced in the AddOns panel). Runs at end of tick, so mutating {@code addons}
     * via {@link #autoDisable} is safe. The {@code :lua} REPL owner is exempt (it is not in {@code addons}
     * — the sandbox constrains shared addon code, not the operator's console).
     */
    private static void enforceSoftBudget() {
        if((Sandbox.SOFT_BUDGET_NANOS <= 0) || (Sandbox.SOFT_STRIKE_LIMIT <= 0))
            return;   // soft budget disabled by config
        for(Addon a : addons) {
            if(a.tickLuaNanos > Sandbox.SOFT_BUDGET_NANOS) {
                if(++a.overBudgetStrikes >= Sandbox.SOFT_STRIKE_LIMIT)
                    autoDisable(a, ">" + (Sandbox.SOFT_BUDGET_NANOS / 1_000_000L) + "ms/tick x"
                        + Sandbox.SOFT_STRIKE_LIMIT + " ticks; last " + (a.tickLuaNanos / 1_000_000L) + "ms");
            } else {
                a.overBudgetStrikes = 0;   // must be SUSTAINED — a single spike doesn't count
            }
        }
    }

    /**
     * Auto-disable an addon for the current session (D-018): record a panel warning, run its teardown
     * ({@code OnDisable} → flush saved vars → drop owned resources) and drop it from the live set so it
     * stops ticking. This does NOT touch the persisted enabled set — a {@code :reload}/login gives the
     * addon a fresh start (the user can persist-disable it via the panel checkbox). Called from
     * {@link #enforceSoftBudget} at end of tick, so mutating {@code addons} here is safe.
     */
    private static void autoDisable(Addon a, String reason) {
        String id = (a.manifest != null) ? a.manifest.id : "addon";
        log(a, "AUTO-DISABLED this session by the CPU watchdog (" + reason + ") - see Options -> AddOns");
        autoDisabledWarn.put(id, reason);
        teardown(a);
        addons.remove(a);
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

    /**
     * The outbound-{@code wdgmsg} action hook (spec 13 §L2 / Phase 2d) — the core edit in
     * {@link UI#wdgmsg(Widget, String, Object...)}. Runs every registered {@code hafen.hook.action(msg, fn)}
     * whose name matches, <b>before</b> the message reaches the server, and reports whether the default send
     * should proceed: {@code false} once any hook called {@code ev:preventDefault()} (or {@code ev:resend}/
     * {@code ev:send}, which take over the send themselves via {@link UI#rawWdgmsg}).
     *
     * <p><b>Threading.</b> It runs Lua only when the calling thread already holds the UI monitor
     * ({@code Thread.holdsLock}). A player action's {@code wdgmsg} is always sent under {@code synchronized(ui)}
     * — from input dispatch and the addon tick (both inside the frame loop's {@code synchronized(ui)}), or from
     * the MapView hit-test callback (which takes {@code synchronized(ui)} before it sends {@code "click"}). Since
     * the tick and draw also hold that monitor, holding it here means the hook Lua cannot race any other Lua —
     * and, because we only <i>test</i> the lock (never acquire a new one), there is no deadlock risk. A rare
     * off-lock sender is passed straight through (unhooked). The re-entrancy guard makes a hook body that itself
     * triggers a {@code wdgmsg} pass through rather than recurse (the spec's {@code resend} caveat; {@code resend}/
     * {@code send} themselves bypass this via {@code rawWdgmsg}). Returns {@code true} (proceed) on every fast-path
     * exit, so an unhooked action is unaffected.
     */
    public static boolean onWdgmsg(Widget sender, String msg, Object[] args) {
        if(actionHooks.isEmpty())
            return true;                              // fast path: no action hooks anywhere
        List<LuaActionHook> matching = actionHooks.get(msg);
        if((matching == null) || matching.isEmpty())
            return true;                              // fast path: nothing hooks this action
        UI u = ui;
        if((u == null) || !Thread.holdsLock(u))
            return true;                              // only run Lua on a UI-locked (Lua-safe) send path
        if(dispatchingAction)
            return true;                              // re-entrancy: a hook body sent another wdgmsg
        dispatchingAction = true;
        boolean[] prevented = new boolean[1];
        try {
            for(LuaActionHook h : matching) {         // copy-on-write: a hook may :remove() itself here
                if(h.alive)
                    h.invoke(sender, msg, args, prevented, u);
            }
        } finally {
            dispatchingAction = false;
        }
        return !prevented[0];
    }

    /**
     * The inbound-{@code uimsg} message hook (spec 13 §L3 / Phase 2e) — the core edit in {@code UI.UiMessage.run}.
     * Runs every registered {@code hafen.hook.message(msg, fn)} whose name matches, <b>before</b> the target
     * widget applies the server update, and reports what to apply:
     * <ul>
     *   <li>the original {@code args} — no hook matched, or none altered the message (apply as normal);</li>
     *   <li>a <b>rewritten</b> {@code Object[]} — a hook called {@code ev:rewrite(t)} (apply the new args);</li>
     *   <li>{@code null} — a hook called {@code ev:preventDefault()} (swallow the update; do not apply it, and
     *       the caller then also skips the post-apply widget-tree tap, since the widget did not change).</li>
     * </ul>
     * {@code preventDefault} wins over {@code rewrite} when both are used across the matching hooks.
     *
     * <p><b>Threading.</b> Called from {@code UiMessage.run} on a Loader thread but always inside that method's
     * {@code synchronized(ui)} block — the same monitor the tick and draw hold — so the hook Lua cannot race any
     * other Lua. No {@code holdsLock} guard is needed (unlike {@link #onWdgmsg}, whose senders are not all
     * UI-locked): this seam is reached only under the lock. The fast path (no hooks / this msg unhooked) returns
     * the original args immediately, so an unhooked message is unaffected — important, as uimsg application is hot.
     */
    public static Object[] onMessage(Widget target, String msg, Object[] args) {
        if(messageHooks.isEmpty())
            return args;                              // fast path: no message hooks anywhere
        List<LuaMessageHook> matching = messageHooks.get(msg);
        if((matching == null) || matching.isEmpty())
            return args;                              // fast path: nothing hooks this message
        boolean[] prevented = new boolean[1];
        Object[][] rewritten = new Object[1][];
        for(LuaMessageHook h : matching) {            // copy-on-write: a hook may :remove() itself here
            if(h.alive)
                h.invoke(target, msg, args, prevented, rewritten);
        }
        if(prevented[0])
            return null;                              // swallow (preventDefault wins over any rewrite)
        return (rewritten[0] != null) ? rewritten[0] : args;
    }

    /**
     * The global-hotkey seam (spec 07 "Input" / Phase 2e-2) — called from {@link AddonRoot#globtype} for every
     * {@link Widget.GlobKeyEvent}. {@link UI#keydown} fires that event only after an unconsumed focused
     * {@code KeyDownEvent}, so a hotkey never fires while a text field has focus; the event then walks the whole
     * widget tree calling {@code globtype}. Runs the handler of the first registered {@code hafen.key.bind}
     * whose current key matches and returns whether the key was <b>consumed</b> ({@code true} stops the
     * GlobKeyEvent walk). The addon-root is an early child of {@code ui.root}, hence walked <b>last</b>, so a
     * client binding on the same key is matched first — an addon hotkey is the fallback, never a hijack.
     *
     * <p><b>Threading.</b> Reached on the UI thread (input dispatch, under {@code synchronized(ui)}) — the same
     * path as an L1 input hook — so the handler goes straight through {@link #callLua} (watchdog-armed,
     * error-isolated, CPU-accounted), with no thread guard. The fast path (no hotkeys anywhere) returns
     * immediately, so an unbound client is unaffected.
     */
    public static boolean onGlobKey(Widget.GlobKeyEvent ev) {
        if(keyBinds.isEmpty())
            return false;                            // fast path: no addon hotkeys anywhere
        for(LuaKeyBind kb : keyBinds) {              // copy-on-write: a hotkey may :remove() itself here
            if(kb.alive && kb.matches(ev)) {
                callLua(kb.owner, kb.fn);
                return true;                         // consume: the addon bound this key
            }
        }
        return false;
    }

    /**
     * Record a server widget's <b>type name</b> (spec 08 / Phase 3a) — called from the {@code UI.NewWidget.run}
     * core edit right after the widget is bound to its id. The widget instance does not carry its registered type
     * string, so we stash {@code id -> typenm} here and read it back when the widget is placed (the descriptor's
     * {@code type} field). Kept only while an observer is registered (so an unobserving client records nothing),
     * and only the in-flight set (the matching {@link #onWidgetPlaced} removes it), so the map stays tiny.
     * {@code typenm} is {@code null} when the widget was built from a {@link Widget.Factory} directly rather than
     * a type string (never the case for a server widget) — those simply record no type.
     */
    public static void onWidgetCreated(int id, String typenm) {
        if((widgetObservers.isEmpty() && widgetReplacers.isEmpty()) || (typenm == null))
            return;                                   // fast path: nobody is observing/replacing, or no type string
        widgetTypes.put(Integer.valueOf(id), typenm);
    }

    /**
     * Fire every {@code hafen.ui.onWidgetCreate(fn)} observer for one placed server widget (spec 08 / Phase 3a) —
     * called from the {@code UI.AddWidget.run} core edit, right after {@code pwdg.addchild(wdg, pargs)}, i.e. the
     * first moment the FULL descriptor exists (placement supplies the {@code place}-string + parent that creation
     * lacks). Builds {@code desc = {id, type, place, caption, parentType}} (D-024) and hands it to each observer's
     * Lua {@code fn(desc)}. This slice is observe-only (the return is ignored — adopt/replace is 3b/3c).
     *
     * <p><b>Threading.</b> Reached only from inside {@code AddWidget.run}'s {@code synchronized(ui)} block (on a
     * Loader thread, under the monitor tick/draw hold), so observer Lua never races other Lua — the same
     * discipline as {@link #onMessage} (no {@code holdsLock} guard needed). The fast path (no observers anywhere)
     * returns immediately, so an unobserving client is unaffected even though every widget placement passes here.
     */
    public static void onWidgetPlaced(int id, Widget wdg, Widget pwdg, Object[] pargs) {
        if(widgetObservers.isEmpty() && widgetReplacers.isEmpty())
            return;                                   // fast path: no widget-create observers/replacers anywhere
        String type = widgetTypes.remove(Integer.valueOf(id));
        String place = ((pargs != null) && (pargs.length > 0) && (pargs[0] instanceof String))
                       ? (String)pargs[0] : null;
        String parentType = (pwdg == null) ? null : pwdg.getClass().getSimpleName();
        String caption = (wdg instanceof Window) ? ((Window)wdg).cap : null;
        for(LuaWidgetObserver o : widgetObservers) {  // copy-on-write: an observer may :remove() itself here
            if(o.alive)
                o.invoke(id, type, place, caption, parentType);
        }
        // 3c: also offer this newly-placed widget to every replacer (a target opened AFTER the replacer registered).
        for(LuaReplacer r : widgetReplacers) {        // copy-on-write: a builder may register/remove replacers here
            if(r.alive && !r.handled(id) && matchOnCreate(r, id, type, place, caption, parentType))
                fireReplace(r, id, wdg);
        }
    }

    /**
     * Build the widget-targeting descriptor {@code {id, type, place, caption, parentType}} (D-024) shared by the 3a
     * {@link LuaWidgetObserver} and the 3c {@link LuaReplacer} match predicate. A {@code null} field is left absent
     * (Lua {@code nil}) so an addon tests it idiomatically ({@code if desc.caption then ... end}).
     */
    static LuaTable descTable(int id, String type, String place, String caption, String parentType) {
        LuaTable desc = new LuaTable();
        desc.set("id", LuaValue.valueOf(id));
        if(type != null)       desc.set("type", LuaValue.valueOf(type));
        if(place != null)      desc.set("place", LuaValue.valueOf(place));
        if(caption != null)    desc.set("caption", LuaValue.valueOf(caption));
        if(parentType != null) desc.set("parentType", LuaValue.valueOf(parentType));
        return desc;
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

    /**
     * Action bar / hotbar — the 144 {@link GameUI.BeltSlot}s of {@code GameUI.belt} (the F-key /
     * number-key hotbar; the engine's own name for the action bar is the "belt"). Setting/clearing/
     * dragging a slot is a {@code setbelt}/{@code setbelt2} {@code uimsg} to {@code GameUI}, but for the
     * common (resource/pagina) cases the slot array is mutated on a <b>deferred loader task</b> that runs
     * after the message is dispatched — so a synchronous refresh-on-uimsg would race the write. Hence this
     * is <b>poll-driven</b> (like buffs/study): each tick it diffs the occupied slots against a per-index
     * cache and fires {@code ActionbarChanged{n}} on a set/clear/change (or a slot's data resolving).
     * Change-detection ignores {@code cooldown} (a live meter that would otherwise fire every frame while
     * an ability cools down); {@code hafen.actionbar.slot(n)} still reads it live.
     */
    private static final class ActionbarAdapter implements TreeAdapter {
        // slot index -> last snapshot, occupied slots only. UI-thread-only; reset per session by
        // re-instantiation in init(). Keyed by Integer (value identity), not widget identity.
        private final Map<Integer, LuaValue> cache = new HashMap<Integer, LuaValue>();

        public boolean interested(Widget w, String msg) {
            return false;         // slot set/clear mutates belt[] on a deferred loader task — see poll()
        }

        public void refresh() {}

        public void poll() {
            GameUI g = gui();
            if((g == null) || (g.belt == null))
                return;           // HUD not up yet — keep the cache, fire nothing
            GameUI.BeltSlot[] belt = g.belt;
            for(int n = 0; n < belt.length; n++) {
                GameUI.BeltSlot s = belt[n];
                LuaValue prev = cache.get(n);
                if(s == null) {
                    if(prev != null) {                        // occupied -> empty (cleared)
                        cache.remove(n);
                        fire("ActionbarChanged", LuaValue.valueOf(n));
                    }
                } else {
                    LuaValue snap = actionbarSnapshot(s);
                    if((prev == null) || !actionbarEqual(snap, prev)) {   // empty->occupied or content changed
                        cache.put(n, snap);
                        fire("ActionbarChanged", LuaValue.valueOf(n));
                    }
                }
            }
        }
    }

    /**
     * Equipment — the {@link WItem}s worn in the {@link Equipory}. Equipping/removing is a widget
     * create/{@code cdestroy} under the Equipory (not a targeted {@code uimsg}), and item data streams
     * in a beat after each item appears, so this is <b>poll-driven</b>: each tick it re-reads the
     * equipment snapshot (the same array {@code hafen.items.equipment()} returns) and fires {@code
     * EquipChanged} with it when the set changes. Change-detection compares {@code slot}/{@code res}/
     * {@code name}/{@code num} — not {@code wear} (a slowly-changing durability that is not an equip
     * change; read it live via {@code hafen.items.equipment()}).
     */
    private static final class EquipAdapter implements TreeAdapter {
        private LuaValue cache;   // last equipment snapshot (UI thread; change-detect)

        public boolean interested(Widget w, String msg) {
            return false;         // equip/unequip is a widget create/cdestroy, not a uimsg — see poll()
        }

        public void refresh() {}

        public void poll() {
            Equipory eq = equipory();
            if(eq == null)
                return;           // equipory not up yet — keep the cache, fire nothing
            LuaValue snap = readEquipment(eq);
            if(!equipEqual(snap, cache)) {
                cache = snap;
                fire("EquipChanged", snap);
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

    /**
     * Call into Lua with full error isolation (a Lua error never escapes the engine step) and return its
     * result varargs (or {@link LuaValue#NIL} on error). Most callers (events/timers) ignore the return;
     * the custom-UI input forwards ({@link LuaWidget}) read {@code .arg1().toboolean()} for "consume".
     * Package-visible so {@link LuaWidget} (same package) routes its draw/tick/mouse callbacks through the
     * one watchdog-armed, CPU-accounted choke point.
     */
    static Varargs callLua(Addon owner, LuaValue fn, LuaValue... args) {
        long t0 = System.nanoTime();
        try {
            Sandbox.arm(owner.env);   // reset the watchdog's instruction budget for this callback (D-018)
            return fn.invoke((args.length == 0) ? LuaValue.NONE : LuaValue.varargsOf(args));
        } catch(LuaError e) {
            log(owner, "handler error: " + e.getMessage());
        } catch(RuntimeException e) {
            log(owner, "handler error: " + e);
        } finally {
            owner.tickLuaNanos += System.nanoTime() - t0;   // soft per-tick CPU-budget accounting (D-018 layer 2)
        }
        return LuaValue.NIL;
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
                return readEquipment(equipory());   // {..., slot} per worn item; EquipChanged mirrors this
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

        // hafen.actionbar.* — the action bar / hotbar (the engine calls it the "belt": GameUI.belt, a
        // BeltSlot[144]), via the widget-tree mechanism (1d-4). slot(n) returns {res,name,cooldown} for the
        // occupied slot n (the RAW 0-based game index 0..143 — the same index action-bar USE will take in
        // Phase 4), or nil if empty; cooldown (0..1) is a pagina action's meter, present only for ability
        // slots (not seconds). Subscribe to ActionbarChanged{n} (fired per-tick when slot n's content
        // changes — a set/clear/drag or its data resolving). Action-bar USE is the gated action tier (Phase 4).
        LuaTable actionbar = new LuaTable();
        actionbar.set("slot", new OneArgFunction() {
            public LuaValue call(LuaValue n) {
                return n.isnumber() ? actionbarSlot(n.toint()) : LuaValue.NIL;
            }
        });
        hafen.set("actionbar", actionbar);

        // hafen.ui — custom client-side UI (spec 07, Phase 2a). window(opts) = a draggable, titled window;
        // widget(opts) = a bare rectangle (no chrome). opts: size={w,h}, pos={x,y}, parent="root"|"gameui",
        // title (window only), and callbacks onDraw(g,w,h) / onTick(dt) / onClick(x,y,button) / onMouseUp /
        // onMouseMove(x,y) / onWheel(x,y,amount) / onClose (window). Returns a handle:
        //   :move(x,y)  :show()  :hide()  :visible()  :pack()  :size(w,h)  :destroy()
        // The widget is bridge-owned (P2) and torn down on reload/disable. Client-side only: it cannot
        // wdgmsg the server (that is hafen.act, Phase 4). See LuaWidget for the callback plumbing.
        LuaTable uiT = new LuaTable();
        uiT.set("window", new OneArgFunction() {
            public LuaValue call(LuaValue opts) {
                return newUi(owner, opts, true);
            }
        });
        uiT.set("widget", new OneArgFunction() {
            public LuaValue call(LuaValue opts) {
                return newUi(owner, opts, false);
            }
        });
        // hafen.ui.overlay(fn) — paint on top of the HUD without owning a widget. fn(g, w, h) runs every
        // frame with the shared GOut wrapper and the screen size; draw at absolute screen coords. Returns a
        // handle with :remove(); also auto-removed on reload/disable (spec 07).
        uiT.set("overlay", new OneArgFunction() {
            public LuaValue call(LuaValue fn) {
                return newHudOverlay(owner, fn);
            }
        });
        // hafen.ui.gobOverlay(filter, fn) — label/mark game objects in the 3D view (the SpeakerIcon pattern).
        // filter(gob)->truthy (or a substring matched against gob.name) selects gobs; fn(g, gob, sx, sy) draws
        // at the gob's projected screen point (sx,sy = just above the head). gob is the same snapshot shape as
        // hafen.gob.info. Returns a handle with :remove(); auto-removed on reload/disable (spec 07).
        uiT.set("gobOverlay", new TwoArgFunction() {
            public LuaValue call(LuaValue filter, LuaValue fn) {
                return newGobOverlay(owner, filter, fn);
            }
        });
        // hafen.ui.onWidgetCreate(fn) — observe the server's own UI as it is built (spec 08, Phase 3a). fn(desc)
        // runs for every SERVER widget as it is placed into the tree, with desc = {id, type, place, caption,
        // parentType} (the targeting descriptor, D-024) — e.g. the inventory is {type="inv", place="inv",
        // parentType="GameUI"}; a cupboard is {type="wnd", place="misc", caption="Cupboard", parentType="GameUI"}.
        // A HUD-placed window always reports parentType="GameUI"; item widgets streaming into an inventory report
        // their container instead, so an addon filters by parentType/type/place. This slice is observe-only
        // (adopting the real widget as a hidden model + drawing a custom view is a later slice); the return is
        // ignored. Returns a handle with :remove(); auto-removed on reload/disable (P2). Register any time (no
        // live target needed) — the file body catches the login window burst too.
        uiT.set("onWidgetCreate", new OneArgFunction() {
            public LuaValue call(LuaValue fn) {
                return newWidgetObserver(owner, fn);
            }
        });
        // hafen.ui.adopt(id) — adopt a live SERVER widget by its id (the desc.id an onWidgetCreate observer hands
        // out) as a MODEL (spec 08 / Phase 3b): keep the real, server-bound widget as a hidden model and present
        // your own view over it — "wrap, don't reimplement" (D-009). Returns a model handle, or nil if no widget
        // has that id (e.g. it was already destroyed). The handle:
        //   :hide() / :show()      -- toggle the widget's visibility (chainable). A HIDDEN server widget stays
        //                             bound to its id, so it keeps receiving item adds / updates — a headless model.
        //   :visible()             -- is it currently visible?
        //   :raw()                 -- the server widget id (a WidgetRef); the facade-safe escape hatch.
        //   :items()               -- array of Item snapshots (same shape as hafen.items.inventory) off the
        //                             widget's WItem children; empty for a non-inventory widget. READ-ONLY:
        //                             item verbs (take/drop/transfer/use) are gameplay actions -> the gated
        //                             actions tier (Phase 4, D-010/D-025), not here.
        //   :onItemAdded(fn)/:onItemRemoved(fn)  -- fn(item) when an item enters/leaves (poll-diffed each tick).
        //   :onDestroy(fn)         -- fn() once when the SERVER destroys the widget (the view must die with it).
        // Bridge-owned (P2): :reload/disable drops the model and UN-HIDES anything it hid (restoring the stock UI).
        // Adopt from an onWidgetCreate observer (which fires as the widget is built); re-finding an ALREADY-open
        // window by type/descriptor is hafen.ui.replace (Phase 3c).
        uiT.set("adopt", new OneArgFunction() {
            public LuaValue call(LuaValue id) {
                return newModel(owner, id);
            }
        });
        // hafen.ui.replace(type, opts, fn) — the high-level "replace a native window with your own view" sugar over
        // 3a (observe) + 3b (adopt), spec 08 / Phase 3c. It watches for a SERVER widget matching a descriptor, then
        // adopts the real widget as a hidden MODEL and calls fn(model); fn draws a custom VIEW (e.g. a hafen.ui.window)
        // and RETURNS it — "wrap, don't reimplement" (D-009). The native window is hidden but stays server-bound, so
        // model:items()/events keep working; disabling/reloading the addon (or handle:remove()) UN-HIDES it, restoring
        // the stock UI (the Phase-3 DoD). Matching:
        //   type            -- the server type string (e.g. "inv"); required.
        //   opts.context    -- a semantic selector: "main" = the main inventory (GameUI.maininv).
        //   opts.caption    -- an exact window caption (for titled containers, e.g. "Cupboard").
        //   opts.match      -- an escape-hatch predicate match(desc)->truthy, desc={id,type,place,caption,parentType}.
        // Registration also SCANS the live tree ONCE for an already-open match (the :reload case, where the target
        // was created before this addon layer existed) — so it works whether the window opens before or after you
        // call replace. Item MOVING (take/transfer/drop) is a gameplay action -> the gated Phase-4 tier, not here.
        // Returns a handle { :remove() } that stops replacing AND restores the native window (destroying your view);
        // bridge-owned, so :reload/disable does the same automatically.
        uiT.set("replace", new ThreeArgFunction() {
            public LuaValue call(LuaValue type, LuaValue opts, LuaValue fn) {
                return newReplacer(owner, type, opts, fn);
            }
        });
        hafen.set("ui", uiT);

        // hafen.hook — intercept/alter client behaviour, not just observe it (spec 13-hooks-and-interception).
        // Two levels so far, both PRE-hooks (fn(ev) runs BEFORE the default; ev:preventDefault() cancels it):
        //   L1 hafen.hook.input(target, event, fn)  (Phase 2c) — the built-in zero-core-edit Widget.listen
        //       seam. Fires before a client widget's own input handler; preventDefault ALSO stops the event
        //       reaching child widgets (Event.dispatch short-circuits), so there is no separate stopPropagation.
        //       target = "mapview"(alias "map") | "gameui"(alias "hud") | "root"; event = mousedown | mouseup |
        //       mousemove | mousewheel; ev carries x,y (widget-local px) + button (down/up) / amount (wheel).
        //   L2 hafen.hook.action(msg, fn)  (Phase 2d) — the outbound-action choke point (UI.wdgmsg). Fires when
        //       a widget is about to send an action to the server, with the arguments FULLY RESOLVED (for a
        //       MapView move: the destination world coord + any clicked gob — none of which exist at L1's
        //       mousedown). ev = { msg, sender, args (1-based; Coord -> {x,y}), preventDefault(), resend(),
        //       send(t) }. resend()/send(t) re-issue the action (bypassing the hook chain, so no loop) — the
        //       "intercept my move, do X, then move" case. Each returns a handle{ :remove() }.
        //   L3 hafen.hook.message(msg, fn)  (Phase 2e) — the inbound-message choke point (UI.uimsg). The mirror
        //       of L2: fires when a SERVER update is about to be applied to a widget. ev = { msg, target, args
        //       (1-based, same marshalling as L2), preventDefault(), rewrite(t) }. preventDefault() SWALLOWS the
        //       update (the widget never applies it); rewrite(t) applies it with new args. Runs on a Loader
        //       thread under synchronized(ui) — keep handlers light (the instruction watchdog still bounds them).
        // Register hooks in OnEnterWorld (the L1 target widget must exist; L2/L3 need no target). Global
        // hotkeys are hafen.key.bind (below), not a hook level.
        LuaTable hook = new LuaTable();
        hook.set("input", new ThreeArgFunction() {
            public LuaValue call(LuaValue target, LuaValue event, LuaValue fn) {
                return newInputHook(owner, target, event, fn);
            }
        });
        hook.set("action", new TwoArgFunction() {
            public LuaValue call(LuaValue msg, LuaValue fn) {
                return newActionHook(owner, msg, fn);
            }
        });
        hook.set("message", new TwoArgFunction() {
            public LuaValue call(LuaValue msg, LuaValue fn) {
                return newMessageHook(owner, msg, fn);
            }
        });
        hafen.set("hook", hook);

        // hafen.key.bind(name, defaultKey, fn) — a remappable GLOBAL HOTKEY (spec 07 "Input"). `name` is the
        // addon-local binding name (registered as addon/<id>/<name> in the client keybind registry, persisted
        // + remappable there); `defaultKey` is a string like "F5" / "Ctrl+M" / "Shift+Alt+Left" (or nil /
        // "None" for unbound-by-default, letting the user assign it later); `fn()` runs when the key is pressed.
        // Returns a handle { :remove(), :key() -> the current key's display name }. The hotkey fires only when
        // no focused widget consumed the keypress first (so NOT while typing in chat) and no client binding on
        // the same key took it (addon hotkeys are the fallback). Register any time — no live target needed —
        // and it is auto-removed on reload/disable. The KeyBinding itself is persistent (a user's re-map
        // survives reloads); the default applies only on first creation.
        LuaTable key = new LuaTable();
        key.set("bind", new ThreeArgFunction() {
            public LuaValue call(LuaValue name, LuaValue defaultKey, LuaValue fn) {
                return newKeyBind(owner, name, defaultKey, fn);
            }
        });
        hafen.set("key", key);

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

        // hafen.store — saved variables (1e / D-002 / D-023). One Lua table per manifest-declared
        // saved variable (read/written like any table), persisted to JSON under savedata/. hafen.store.
        // flush() forces a write now. Per-character vars are restored at OnEnterWorld (the <genus>_<char>
        // folder is only known then); account-scope vars are loaded here, before the addon's files run,
        // so they are ready in the file body / OnLoad. The table object for each name is STABLE for the
        // addon's whole life (restore fills it in place), so a cached reference stays valid.
        LuaTable store = new LuaTable();
        for(Manifest.SavedVar sv : owner.manifest.savedVariables) {
            if(store.get(sv.name).istable())
                continue;                                // duplicate name in the manifest: keep the first
            store.set(sv.name, new LuaTable());          // always a usable (possibly empty) table
        }
        store.set("flush", new ZeroArgFunction() {
            public LuaValue call() {
                flush(owner);
                return LuaValue.NIL;
            }
        });
        owner.store = store;
        loadScope(owner, true);                          // account-scope vars: ready before OnLoad
        hafen.set("store", store);

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

    // ------------------------------------------------------------- custom UI (hafen.ui, 2a)

    /**
     * Build a custom UI element for {@code hafen.ui.window}/{@code widget} (spec 07): a {@link LuaWidget}
     * content leaf, optionally wrapped in a draggable {@link Window} (chrome). Reads {@code size}/{@code
     * pos} (both {@code {a,b}} arrays), {@code parent} ({@code "root"} default, or {@code "gameui"}), and
     * {@code title} from {@code opts}; the callbacks live on the same table and are wired in the
     * LuaWidget. Attaches to the tree (locks on {@code ui}), registers the content in the addon's
     * owned-resource registry (torn down on reload/disable), and returns the Lua handle.
     */
    private static LuaValue newUi(final Addon owner, LuaValue opts, boolean window) {
        String what = window ? "window" : "widget";
        if(!opts.istable())
            throw new LuaError("hafen.ui." + what + "(opts) expects a table");
        UI u = ui;
        if((u == null) || (u.root == null))
            throw new LuaError("hafen.ui." + what + ": no UI is up yet");

        LuaValue sizev = opts.get("size");
        int w = sizev.istable() ? sizev.get(1).optint(200) : 200;
        int h = sizev.istable() ? sizev.get(2).optint(140) : 140;
        LuaValue posv = opts.get("pos");
        int px = posv.istable() ? posv.get(1).optint(100) : 100;
        int py = posv.istable() ? posv.get(2).optint(100) : 100;

        final LuaWidget content = new LuaWidget(owner, Coord.of(w, h), opts);

        // Parent: default ui.root; "gameui" attaches under the HUD (falls back to root before it is up).
        Widget parent = u.root;
        if("gameui".equals(opts.get("parent").optjstring("root"))) {
            GameUI g = gui();
            if(g != null)
                parent = g;
            else
                log(owner, "hafen.ui." + what + ": HUD not up yet; attaching to root");
        }

        final Widget rootw;
        final boolean isWindow;
        if(window) {
            final Window win = new Window(Coord.of(w, h), opts.get("title").optjstring(""));
            win.add(content, Coord.z);
            content.root(win);
            LuaValue oc = opts.get("onClose");
            final LuaValue onClose = oc.isfunction() ? oc : null;
            win.reqclose(() -> {                      // the chrome close button: fire onClose, then destroy
                if(onClose != null)
                    callLua(owner, onClose);
                content.kill();
                owner.widgets.remove(content);
            });
            rootw = win;
            isWindow = true;
        } else {
            rootw = content;
            isWindow = false;
        }
        rootw.c = Coord.of(px, py);      // initial position (set before attach)
        parent.add(rootw);               // add() locks on ui; content ticks/draws from the next frame
        owner.widgets.add(content);
        return uiHandle(owner, content, rootw, isWindow);
    }

    /**
     * The Lua handle for a {@link #newUi} element: {@code :move/:show/:hide/:visible/:pack/:size/:destroy}.
     * Geometry ops target the root (the window chrome, or the widget); {@code :size} resizes the content
     * (and repacks a window). {@code :pack} is a no-op for a bare widget (a leaf has no children to fit).
     * Handle methods are safe to call after teardown (they act on a detached widget).
     */
    private static LuaValue uiHandle(final Addon owner, final LuaWidget content, final Widget rootw,
                                     final boolean isWindow) {
        LuaTable h = new LuaTable();
        h.set("move", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                rootw.move(Coord.of(a.arg(2).toint(), a.arg(3).toint()));
                return a.arg1();          // return the handle for chaining (win:move(..):show())
            }
        });
        h.set("show", new VarArgFunction() {
            public Varargs invoke(Varargs a) { rootw.show(); return a.arg1(); }
        });
        h.set("hide", new VarArgFunction() {
            public Varargs invoke(Varargs a) { rootw.hide(); return a.arg1(); }
        });
        h.set("visible", new ZeroArgFunction() {
            public LuaValue call() { return LuaValue.valueOf(rootw.visible()); }
        });
        h.set("pack", new VarArgFunction() {
            public Varargs invoke(Varargs a) { if(isWindow) rootw.pack(); return a.arg1(); }
        });
        h.set("size", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                content.resize(Coord.of(a.arg(2).toint(), a.arg(3).toint()));
                if(isWindow)
                    rootw.pack();
                return a.arg1();
            }
        });
        h.set("destroy", new ZeroArgFunction() {
            public LuaValue call() {
                content.kill();
                owner.widgets.remove(content);
                return LuaValue.NIL;
            }
        });
        return h;
    }

    // ------------------------------------------------------------- custom UI overlays (hafen.ui, 2b)

    /**
     * Register a HUD overlay ({@code hafen.ui.overlay(fn)}, spec 07): a draw callback painted on top of the
     * HUD each frame. Bridge-owned (P2) — added to the addon's registry so reload/disable drops it. Returns
     * the Lua handle ({@code :remove()}).
     */
    private static LuaValue newHudOverlay(final Addon owner, LuaValue fn) {
        if(!fn.isfunction())
            throw new LuaError("hafen.ui.overlay(fn) expects a function");
        final HudOverlay ov = new HudOverlay(owner, fn);
        owner.hudOverlays.add(ov);
        LuaTable h = new LuaTable();
        h.set("remove", new ZeroArgFunction() {
            public LuaValue call() {
                ov.active = false;
                owner.hudOverlays.remove(ov);
                return LuaValue.NIL;
            }
        });
        return h;
    }

    /**
     * Register a world-space gob overlay ({@code hafen.ui.gobOverlay(filter, draw)}, spec 07): a filter that
     * selects gobs (a function {@code filter(gob)->truthy}, or a string substring-matched on the gob's name)
     * and a draw callback {@code draw(g, gob, sx, sy)} painted over each matching gob (the SpeakerIcon
     * pattern via {@link LuaGobOverlay}). Bridge-owned (P2). Returns the Lua handle ({@code :remove()}).
     */
    private static LuaValue newGobOverlay(final Addon owner, LuaValue filter, LuaValue draw) {
        if(!(filter.isfunction() || filter.isstring()))
            throw new LuaError("hafen.ui.gobOverlay(filter, draw): filter must be a function or string");
        if(!draw.isfunction())
            throw new LuaError("hafen.ui.gobOverlay(filter, draw): draw must be a function");
        final GobOverlay ov = new GobOverlay(owner, filter, draw);
        owner.gobOverlays.add(ov);
        LuaTable h = new LuaTable();
        h.set("remove", new ZeroArgFunction() {
            public LuaValue call() {
                ov.active = false;
                owner.gobOverlays.remove(ov);
                if(!anyGobOverlays())      // last gob overlay gone → detach the idle attribs from all gobs
                    detachGobOverlays();
                return LuaValue.NIL;
            }
        });
        return h;
    }

    // ------------------------------------------------------------- input/gesture hooks (hafen.hook, 2c)

    /**
     * Register a Level-1 input hook ({@code hafen.hook.input(target, event, fn)}, spec 13 §L1): install {@code
     * fn} as a pre-hook on a client widget's input via {@link Widget#listen}. Resolves {@code target} (a
     * string naming a known client widget) and {@code event} (a mouse-event name) to a {@link Widget} + event
     * class, wires a {@link LuaInputHook} listener, registers it in the addon's owned-resource registry
     * (deafened on reload/disable, P2), and returns the Lua handle ({@code :remove()}). Throws a
     * {@link LuaError} for a bad event name, an unknown target, or a target that is not up yet (register in
     * OnEnterWorld, when the MapView/HUD exist).
     */
    private static LuaValue newInputHook(final Addon owner, LuaValue target, LuaValue event, LuaValue fn) {
        if(!event.isstring() || !fn.isfunction())
            throw new LuaError("hafen.hook.input(target, event, fn) expects (target, string, function)");
        Class<? extends Widget.Event> cls = eventClass(event.tojstring());
        if(cls == null)
            throw new LuaError("hafen.hook.input: unknown event '" + event.tojstring()
                               + "' (expected mousedown / mouseup / mousemove / mousewheel)");
        String tok = target.isstring() ? target.tojstring().toLowerCase() : null;
        if(!isKnownTarget(tok))
            throw new LuaError("hafen.hook.input: target must be \"mapview\", \"gameui\", or \"root\" (got "
                               + (target.isnil() ? "nil" : target.tojstring()) + ")");
        Widget w = hookTarget(tok);
        if(w == null)
            throw new LuaError("hafen.hook.input: the " + tok
                               + " is not up yet — register this hook in OnEnterWorld");
        final LuaInputHook h = new LuaInputHook(owner, w, event.tojstring(), fn);
        listenHook(w, cls, h);
        owner.hooks.add(h);
        LuaTable handle = new LuaTable();
        handle.set("remove", new ZeroArgFunction() {
            public LuaValue call() {
                removeHook(owner, h);
                return LuaValue.NIL;
            }
        });
        return handle;
    }

    /** Map an input-hook event name to its {@link Widget.Event} class (2c supports the mouse gestures). */
    private static Class<? extends Widget.Event> eventClass(String name) {
        if(name.equals("mousedown"))  return Widget.MouseDownEvent.class;
        if(name.equals("mouseup"))    return Widget.MouseUpEvent.class;
        if(name.equals("mousemove"))  return Widget.MouseMoveEvent.class;
        if(name.equals("mousewheel")) return Widget.MouseWheelEvent.class;
        return null;
    }

    /** Is {@code tok} a recognized hook-target token? (Distinguishes "unknown target" from "not up yet".) */
    private static boolean isKnownTarget(String tok) {
        return "mapview".equals(tok) || "map".equals(tok)
            || "gameui".equals(tok)  || "hud".equals(tok)
            || "root".equals(tok);
    }

    /** Resolve a (lower-cased, already-known) hook-target token to the live {@link Widget}, or null if not up. */
    private static Widget hookTarget(String tok) {
        if("mapview".equals(tok) || "map".equals(tok))
            return view;
        if("gameui".equals(tok) || "hud".equals(tok))
            return gui();
        if("root".equals(tok)) {
            UI u = ui;
            return (u == null) ? null : u.root;
        }
        return null;
    }

    /**
     * Register {@code h} as a typed listener on {@code w}. {@link Widget#listen} wants an
     * {@code EventHandler<? super E>}; a {@link LuaInputHook} is {@code EventHandler<Widget.Event>} (it works
     * for any concrete event type), so we widen the class token's <i>compile-time</i> type — the runtime
     * {@link Class} is unchanged, so listener matching ({@code t.isInstance}) still keys on the real subclass.
     */
    @SuppressWarnings("unchecked")
    private static void listenHook(Widget w, Class<? extends Widget.Event> cls, LuaInputHook h) {
        w.listen((Class<Widget.Event>)(Class<?>)cls, h);
    }

    /** Remove one input hook: stop it firing, deafen the target, drop it from the registry (handle :remove()). */
    private static void removeHook(Addon owner, LuaInputHook h) {
        h.alive = false;
        try {
            h.target.deafen(h);
        } catch(RuntimeException e) {
            /* target already gone (its listener list went with it): harmless */
        }
        owner.hooks.remove(h);
    }

    /** Deafen + drop every input hook this addon owns (teardown on reload/disable, P2). */
    private static void teardownHooks(Addon a) {
        for(LuaInputHook h : a.hooks) {
            h.alive = false;
            try {
                h.target.deafen(h);
            } catch(RuntimeException e) {
                /* target already destroyed; best-effort, never abort teardown */
            }
        }
        a.hooks.clear();
    }

    // ------------------------------------------------------------------ action hooks (hafen.hook, 2d)

    /**
     * Register a Level-2 action hook ({@code hafen.hook.action(msg, fn)}, spec 13 §L2): install {@code fn} as a
     * pre-hook on the outbound action {@code msg}, tracked in the engine's dispatch map (keyed by name) and in
     * the addon's owned-resource registry (unregistered on reload/disable, P2). Returns the Lua handle
     * ({@code :remove()}). Needs no live target (unlike an input hook) so it can be registered any time, but
     * OnEnterWorld onward is the natural place (matching L1).
     */
    private static LuaValue newActionHook(final Addon owner, LuaValue msg, LuaValue fn) {
        if(!msg.isstring() || !fn.isfunction())
            throw new LuaError("hafen.hook.action(msg, fn) expects (string, function)");
        final LuaActionHook h = new LuaActionHook(owner, msg.tojstring(), fn);
        registerActionHook(h);
        owner.actionHooks.add(h);
        LuaTable handle = new LuaTable();
        handle.set("remove", new ZeroArgFunction() {
            public LuaValue call() {
                removeActionHook(owner, h);
                return LuaValue.NIL;
            }
        });
        return handle;
    }

    /** Add {@code h} to the per-action dispatch list (created on demand). Copy-on-write so onWdgmsg can iterate. */
    private static void registerActionHook(LuaActionHook h) {
        List<LuaActionHook> l = actionHooks.get(h.msg);
        if(l == null) {
            l = new CopyOnWriteArrayList<LuaActionHook>();
            actionHooks.put(h.msg, l);
        }
        l.add(h);
    }

    /** Drop {@code h} from the dispatch map, removing the (now-empty) per-action list so the fast path stays cheap. */
    private static void unregisterActionHook(LuaActionHook h) {
        List<LuaActionHook> l = actionHooks.get(h.msg);
        if(l != null) {
            l.remove(h);
            if(l.isEmpty())
                actionHooks.remove(h.msg);
        }
    }

    /** Remove one action hook: stop it firing + unregister it (the handle's {@code :remove()}). */
    private static void removeActionHook(Addon owner, LuaActionHook h) {
        h.alive = false;
        unregisterActionHook(h);
        owner.actionHooks.remove(h);
    }

    /** Mark dead + unregister every action hook this addon owns (teardown on reload/disable, P2). */
    private static void teardownActionHooks(Addon a) {
        for(LuaActionHook h : a.actionHooks) {
            h.alive = false;
            unregisterActionHook(h);
        }
        a.actionHooks.clear();
    }

    // ------------------------------------------------------------------ message hooks (hafen.hook, 2e)

    /**
     * Register a Level-3 message hook ({@code hafen.hook.message(msg, fn)}, spec 13 §L3): install {@code fn} as
     * a pre-hook on the inbound message {@code msg}, tracked in the engine's dispatch map (keyed by name) and in
     * the addon's owned-resource registry (unregistered on reload/disable, P2). Returns the Lua handle
     * ({@code :remove()}). Needs no live target, so it can be registered any time — OnEnterWorld onward is the
     * natural place (matching L1/L2).
     */
    private static LuaValue newMessageHook(final Addon owner, LuaValue msg, LuaValue fn) {
        if(!msg.isstring() || !fn.isfunction())
            throw new LuaError("hafen.hook.message(msg, fn) expects (string, function)");
        final LuaMessageHook h = new LuaMessageHook(owner, msg.tojstring(), fn);
        registerMessageHook(h);
        owner.messageHooks.add(h);
        LuaTable handle = new LuaTable();
        handle.set("remove", new ZeroArgFunction() {
            public LuaValue call() {
                removeMessageHook(owner, h);
                return LuaValue.NIL;
            }
        });
        return handle;
    }

    /** Add {@code h} to the per-message dispatch list (created on demand). Copy-on-write so onMessage can iterate. */
    private static void registerMessageHook(LuaMessageHook h) {
        List<LuaMessageHook> l = messageHooks.get(h.msg);
        if(l == null) {
            l = new CopyOnWriteArrayList<LuaMessageHook>();
            messageHooks.put(h.msg, l);
        }
        l.add(h);
    }

    /** Drop {@code h} from the dispatch map, removing the (now-empty) per-message list so the fast path stays cheap. */
    private static void unregisterMessageHook(LuaMessageHook h) {
        List<LuaMessageHook> l = messageHooks.get(h.msg);
        if(l != null) {
            l.remove(h);
            if(l.isEmpty())
                messageHooks.remove(h.msg);
        }
    }

    /** Remove one message hook: stop it firing + unregister it (the handle's {@code :remove()}). */
    private static void removeMessageHook(Addon owner, LuaMessageHook h) {
        h.alive = false;
        unregisterMessageHook(h);
        owner.messageHooks.remove(h);
    }

    /** Mark dead + unregister every message hook this addon owns (teardown on reload/disable, P2). */
    private static void teardownMessageHooks(Addon a) {
        for(LuaMessageHook h : a.messageHooks) {
            h.alive = false;
            unregisterMessageHook(h);
        }
        a.messageHooks.clear();
    }

    // ------------------------------------------------------- widget-creation observers (hafen.ui, 3a)

    /**
     * Register a widget-creation observer ({@code hafen.ui.onWidgetCreate(fn)}, spec 08 / Phase 3a): install
     * {@code fn} in the global observer list (fired by {@link #onWidgetPlaced} for every server widget) and in the
     * addon's owned-resource registry (dropped on reload/disable, P2). Returns the Lua handle ({@code :remove()}).
     * Needs no live target, so it can be registered any time — the file body is fine (and catches the login
     * window burst). An observer watches EVERY creation (there is no per-target keying), so this is a flat list,
     * not a per-name map like the hook levels.
     */
    private static LuaValue newWidgetObserver(final Addon owner, LuaValue fn) {
        if(!fn.isfunction())
            throw new LuaError("hafen.ui.onWidgetCreate(fn) expects a function");
        final LuaWidgetObserver o = new LuaWidgetObserver(owner, fn);
        widgetObservers.add(o);
        owner.widgetObservers.add(o);
        LuaTable handle = new LuaTable();
        handle.set("remove", new ZeroArgFunction() {
            public LuaValue call() {
                removeWidgetObserver(owner, o);
                return LuaValue.NIL;
            }
        });
        return handle;
    }

    /** Remove one widget-creation observer: stop it firing + drop it from both lists (the handle's {@code :remove()}). */
    private static void removeWidgetObserver(Addon owner, LuaWidgetObserver o) {
        o.alive = false;
        widgetObservers.remove(o);
        owner.widgetObservers.remove(o);
    }

    /** Mark dead + drop every widget-creation observer this addon owns (teardown on reload/disable, P2). */
    private static void teardownWidgetObservers(Addon a) {
        for(LuaWidgetObserver o : a.widgetObservers)
            o.alive = false;
        widgetObservers.removeAll(a.widgetObservers);
        a.widgetObservers.clear();
    }

    // -------------------------------------------------------------- adopted widget models (hafen.ui, 3b)

    /**
     * Adopt a live server widget as a {@link LuaModel} ({@code hafen.ui.adopt(id)}, spec 08 / Phase 3b): look the
     * widget up by its server id ({@code desc.id}), wrap it, register it globally (polled each tick) + in the
     * addon's owned-resource registry (P2), and return the Lua handle. Returns {@code nil} when no widget has that
     * id (it may have been destroyed) — the caller tests it the idiomatic way. A non-number id is a clear error.
     */
    private static LuaValue newModel(final Addon owner, LuaValue idv) {
        if(!idv.isnumber())
            throw new LuaError("hafen.ui.adopt(id) expects a widget id (number)");
        UI u = ui;
        if(u == null)
            return LuaValue.NIL;
        int id = idv.toint();
        Widget w = u.getwidget(id);
        if(w == null)
            return LuaValue.NIL;                 // no server widget with that id (already destroyed, etc.)
        LuaModel m = new LuaModel(owner, id, w);
        models.add(m);
        owner.models.add(m);
        return modelHandle(m);
    }

    /**
     * The Lua handle for an adopted {@link LuaModel}: {@code :hide/:show/:visible/:raw/:items} +
     * {@code :onItemAdded/:onItemRemoved/:onDestroy}. Geometry-free (a model is the real widget, not our chrome);
     * the mutating item verbs are deliberately absent (gated actions tier, Phase 4). Every method no-ops safely
     * once the model is dead (server-destroyed or torn down). The colon-call convention passes {@code self} as
     * arg1, so a callback setter reads {@code arg(2)} and returns arg1 (the handle) for chaining.
     */
    private static LuaValue modelHandle(final LuaModel m) {
        LuaTable h = new LuaTable();
        h.set("hide", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                if(m.alive) { m.hideTarget.hide(); m.hidden = true; }   // stays server-bound → still a live model
                return a.arg1();
            }
        });
        h.set("show", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                if(m.alive) { m.hideTarget.show(); m.hidden = false; }
                return a.arg1();
            }
        });
        h.set("visible", new ZeroArgFunction() {
            public LuaValue call() { return LuaValue.valueOf(m.alive && m.hideTarget.visible()); }
        });
        h.set("raw", new ZeroArgFunction() {
            public LuaValue call() { return LuaValue.valueOf(m.id); }   // the server id: a facade-safe WidgetRef (P1)
        });
        h.set("items", new ZeroArgFunction() {
            public LuaValue call() {
                LuaTable out = new LuaTable();
                if(m.alive) {
                    int i = 0;
                    for(WItem w : m.wdg.children(WItem.class))
                        out.set(++i, itemSnapshot(w.item, cellPos(w)));
                }
                return out;
            }
        });
        h.set("onItemAdded", new VarArgFunction() {
            public Varargs invoke(Varargs a) { m.onItemAdded = fnOrNull(a.arg(2)); return a.arg1(); }
        });
        h.set("onItemRemoved", new VarArgFunction() {
            public Varargs invoke(Varargs a) { m.onItemRemoved = fnOrNull(a.arg(2)); return a.arg1(); }
        });
        h.set("onDestroy", new VarArgFunction() {
            public Varargs invoke(Varargs a) { m.onDestroy = fnOrNull(a.arg(2)); return a.arg1(); }
        });
        return h;
    }

    /** A Lua function value, or {@code null} if it is not a function (an unset callback). */
    private static LuaValue fnOrNull(LuaValue v) {
        return v.isfunction() ? v : null;
    }

    /**
     * Per-tick poll of every adopted model (UI thread, spec 08 / Phase 3b). For each live model: if the server
     * destroyed the widget (its id no longer maps to it), fire {@code onDestroy} once and drop the model; else
     * diff its {@link WItem} children for add/remove when a listener is registered. Fast-paths out when nothing is
     * adopted. Item add/remove is a widget create/{@code cdestroy}, not a {@code uimsg}, so it can only be seen by
     * polling — the same discipline as the buff/study adapters.
     */
    private static void pollModels() {
        if(models.isEmpty())
            return;
        UI u = ui;
        if(u == null)
            return;
        for(LuaModel m : models) {           // copy-on-write: a callback may adopt/drop a model here
            if(!m.alive)
                continue;
            if(u.getwidget(m.id) != m.wdg) {  // server destroyed it (or reused the id) → the model is gone
                m.alive = false;
                models.remove(m);
                m.owner.models.remove(m);
                if(m.onDestroy != null)
                    callLua(m.owner, m.onDestroy);
                if(m.fromReplace != null)     // 3c: the view dies with the native widget (spec 08); notify the replacer
                    m.fromReplace.active.remove(m);
                destroyReplaceView(m);
                continue;
            }
            if((m.onItemAdded != null) || (m.onItemRemoved != null))
                pollModelItems(m);
        }
    }

    /** Diff one model's {@link WItem} children against its cache, firing onItemAdded/onItemRemoved (mirrors BuffsAdapter). */
    private static void pollModelItems(LuaModel m) {
        Set<WItem> present = new LinkedHashSet<WItem>();
        for(WItem w : m.wdg.children(WItem.class))
            present.add(w);
        for(WItem w : present) {                        // additions (unseen items)
            if(!m.items.containsKey(w)) {
                LuaValue snap = itemSnapshot(w.item, cellPos(w));
                m.items.put(w, snap);
                if(m.onItemAdded != null)
                    callLua(m.owner, m.onItemAdded, snap);
            }
        }
        for(Iterator<Map.Entry<WItem, LuaValue>> it = m.items.entrySet().iterator(); it.hasNext();) {
            Map.Entry<WItem, LuaValue> e = it.next();   // removals (items that left)
            if(!present.contains(e.getKey())) {
                LuaValue snap = e.getValue();
                it.remove();
                if(m.onItemRemoved != null)
                    callLua(m.owner, m.onItemRemoved, snap);
            }
        }
    }

    /**
     * Tear down every adopted model this addon owns (reload/disable, P2): mark each dead, drop it from the global
     * poll list, and — critically — <b>restore</b> a window the addon had hidden to its ORIGINAL visibility, so
     * disabling a UI-replacement addon restores the stock UI (spec 08). "Restore", not blindly "show": a plain
     * {@code adopt} model hid a visible grid (→ show it), but a {@code replace} model hid the native window (the
     * {@code Hidewnd} around {@code maininv}), which is hidden-by-default (→ put it back to hidden), so we replay
     * {@link LuaModel#hideTargetOrigVisible}. Only a still-live server-bound widget is touched (a stale or
     * already-destroyed one is skipped). The restore is a tree op → done under {@code synchronized(ui)}, like
     * {@link #destroyWidgets}.
     */
    private static void teardownModels(Addon a) {
        if(a.models.isEmpty())
            return;
        final UI u = ui;
        final List<LuaModel> ms = new ArrayList<LuaModel>(a.models);
        a.models.clear();
        models.removeAll(ms);
        Runnable restore = () -> {
            for(LuaModel m : ms) {
                m.alive = false;
                if(m.hidden && (u != null) && (u.getwidget(m.id) == m.wdg) && (m.hideTarget != null)) {
                    try {
                        if(m.hideTargetOrigVisible) m.hideTarget.show(); else m.hideTarget.hide();
                    } catch(RuntimeException e) { /* best-effort: never abort teardown */ }
                }
            }
        };
        if(u != null) {
            synchronized(u) { restore.run(); }
        } else {
            restore.run();
        }
    }

    // -------------------------------------------------------------------- widget replacers (hafen.ui, 3c)

    /**
     * Register a widget replacer ({@code hafen.ui.replace(type, opts, fn)}, spec 08 / Phase 3c): build a {@link
     * LuaReplacer} from the match criteria, register it globally (consulted at widget placement) + in the addon's
     * owned-resource registry (P2), then immediately SCAN the live tree for an already-open match (the {@code
     * :reload} case). Returns the Lua handle ({@code :remove()}). Throws a {@link LuaError} for a bad {@code type}/
     * {@code fn}.
     */
    private static LuaValue newReplacer(final Addon owner, LuaValue typev, LuaValue opts, LuaValue fn) {
        if(!typev.isstring())
            throw new LuaError("hafen.ui.replace(type, opts, fn) expects a type string (e.g. \"inv\")");
        if(!fn.isfunction())
            throw new LuaError("hafen.ui.replace(type, opts, fn) expects a builder function fn(model)");
        final LuaReplacer r = new LuaReplacer(owner, typev.tojstring());
        if(opts.istable()) {
            LuaValue ctx = opts.get("context"); if(ctx.isstring())   r.context = ctx.tojstring();
            LuaValue cap = opts.get("caption"); if(cap.isstring())   r.caption = cap.tojstring();
            LuaValue mf  = opts.get("match");   if(mf.isfunction())  r.matchFn = mf;
        }
        r.builderFn = fn;
        widgetReplacers.add(r);
        owner.replacers.add(r);
        scanForReplace(r);                     // catch an ALREADY-OPEN target (the :reload / register-while-in-world case)
        LuaTable h = new LuaTable();
        h.set("remove", new ZeroArgFunction() {
            public LuaValue call() {
                removeReplacer(owner, r);
                return LuaValue.NIL;
            }
        });
        return h;
    }

    /**
     * Sweep the live widget tree once for a target the replacer would match but that already exists — the {@code
     * :reload} case (the target, e.g. the main inventory, was created before this rebuilt addon layer, so no {@code
     * onWidgetPlaced} will fire for it). For {@code context="main"} on {@code "inv"} the target is the unambiguous
     * public {@code GameUI.maininv}; otherwise scan the widgets of the type's class for a caption/match hit. The
     * server type string is not recorded for an already-live widget, so the scan keys on the Java class instead
     * ({@link #typeClass}) — a bridge-internal detail; the addon's {@code type} string stays the one canonical key.
     */
    private static void scanForReplace(LuaReplacer r) {
        UI u = ui;
        if(u == null)
            return;
        GameUI g = gui();
        if("main".equals(r.context) && "inv".equals(r.type)) {
            if((g != null) && (g.maininv != null)) {
                int id = u.widgetid(g.maininv);        // -1 for a client-side widget (no server id)
                String parentType = (g.maininv.parent != null) ? g.maininv.parent.getClass().getSimpleName() : null;
                if((id >= 0) && !r.handled(id) && matchCriteria(r, id, "inv", null, null, parentType))
                    fireReplace(r, id, g.maininv);
            }
            return;
        }
        Class<? extends Widget> cls = typeClass(r.type);
        if(cls == null)
            return;                                    // unknown type for scanning; the creation path still catches new ones
        Widget rootw = (g != null) ? g : u.root;
        if(rootw == null)
            return;
        for(Widget w : widgetsOfClass(rootw, cls)) {
            int id = u.widgetid(w);
            if((id < 0) || r.handled(id))
                continue;
            String caption = (w instanceof Window) ? ((Window)w).cap : null;
            String parentType = (w.parent != null) ? w.parent.getClass().getSimpleName() : null;
            if(matchCriteria(r, id, r.type, null, caption, parentType))
                fireReplace(r, id, w);
        }
    }

    /**
     * Creation-path match: the placed widget's full descriptor vs the replacer. Checks {@code type} and (for
     * {@code context="main"}) that this is the main inventory — server-placed with {@code place=="inv"} directly
     * under {@code GameUI} (a container inventory is placed inside its own window, so {@code place} is null) — then
     * the shared {@link #matchCriteria} (caption + match fn).
     */
    private static boolean matchOnCreate(LuaReplacer r, int id, String type, String place, String caption, String parentType) {
        if(!r.type.equals(type))
            return false;
        if("main".equals(r.context)
           && !("inv".equals(r.type) && "inv".equals(place) && "GameUI".equals(parentType)))
            return false;
        return matchCriteria(r, id, type, place, caption, parentType);
    }

    /** Shared caption + {@code match(desc)} criteria (type/context are pre-checked by the caller). */
    private static boolean matchCriteria(LuaReplacer r, int id, String type, String place, String caption, String parentType) {
        if((r.caption != null) && !r.caption.equals(caption))
            return false;
        if((r.matchFn != null)
           && !callLua(r.owner, r.matchFn, descTable(id, type, place, caption, parentType)).arg1().toboolean())
            return false;
        return true;
    }

    /**
     * Adopt the matched server widget as a hidden {@link LuaModel}, hide the native WINDOW around it (so the whole
     * stock window disappears, not just its content — recording its original visibility for teardown), then call
     * the addon's {@code fn(model)} builder and keep the view it returns. Runs on the UI thread under {@code
     * synchronized(ui)} (from {@code onWidgetPlaced}, or the tick-driven reload scan) — the tree ops are locked; the
     * builder's own {@code hafen.ui.window} re-locks reentrantly.
     */
    private static void fireReplace(final LuaReplacer r, int id, Widget wdg) {
        final UI u = ui;
        if(u == null)
            return;
        final LuaModel m = new LuaModel(r.owner, id, wdg);
        m.fromReplace = r;
        final Widget nativeWin = nativeWindowOf(wdg);   // the wrapper (e.g. the "Inventory" Hidewnd), or the widget itself
        m.hideTarget = nativeWin;
        m.hideTargetOrigVisible = nativeWin.visible();
        synchronized(u) {
            nativeWin.hide();
        }
        m.hidden = true;
        models.add(m);
        r.owner.models.add(m);
        r.handled.add(Integer.valueOf(id));
        r.active.add(m);
        LuaValue view = callLua(r.owner, r.builderFn, modelHandle(m)).arg1();   // fn(model) -> the addon's view handle
        m.replaceView = ((view != null) && view.istable()) ? view : null;
    }

    /** The nearest enclosing {@link Window} of a widget (or the widget itself if none) — the native window to hide. */
    private static Widget nativeWindowOf(Widget w) {
        for(Widget p = w; p != null; p = p.parent) {
            if(p instanceof Window)
                return p;
        }
        return w;
    }

    /** The server type string → the Java widget class, for scanning an already-open target (see {@link #scanForReplace}). */
    private static Class<? extends Widget> typeClass(String type) {
        if("inv".equals(type))  return Inventory.class;
        if("epry".equals(type)) return Equipory.class;
        if("chr".equals(type))  return CharWnd.class;
        if("wnd".equals(type))  return Window.class;
        return null;
    }

    /** Every widget of a class in a subtree ({@link Widget#children(Class)} is a deep traversal), typed as {@code Widget}. */
    @SuppressWarnings("unchecked")
    private static Set<Widget> widgetsOfClass(Widget root, Class<? extends Widget> cls) {
        return (Set<Widget>)(Set<?>)root.children(cls);
    }

    /**
     * Remove a replacer (the handle's {@code :remove()}, a live toggle-off): stop matching, then UNDO every active
     * replacement — restore the native window's original visibility and destroy the addon's view. (Teardown on
     * reload/disable takes a different path: {@link #teardownModels} restores + {@link #destroyWidgets} destroys.)
     */
    private static void removeReplacer(Addon owner, LuaReplacer r) {
        r.alive = false;
        widgetReplacers.remove(r);
        owner.replacers.remove(r);
        for(LuaModel m : new ArrayList<LuaModel>(r.active))
            undoReplace(m);
        r.active.clear();
    }

    /** Undo one active replacement (live {@code :remove()}): drop the model, restore the native window, destroy the view. */
    private static void undoReplace(LuaModel m) {
        m.alive = false;
        models.remove(m);
        m.owner.models.remove(m);
        final UI u = ui;
        if(m.hidden && (u != null) && (u.getwidget(m.id) == m.wdg) && (m.hideTarget != null)) {
            synchronized(u) {
                try {
                    if(m.hideTargetOrigVisible) m.hideTarget.show(); else m.hideTarget.hide();
                } catch(RuntimeException e) { /* best-effort */ }
            }
        }
        destroyReplaceView(m);
    }

    /** Destroy a replace model's view (call the Lua handle's {@code :destroy()}), if any. Idempotent. */
    private static void destroyReplaceView(LuaModel m) {
        if((m.replaceView != null) && m.replaceView.istable()) {
            LuaValue d = m.replaceView.get("destroy");
            if(d.isfunction())
                callLua(m.owner, d);
        }
        m.replaceView = null;
    }

    /**
     * Tear down every replacer this addon owns (reload/disable, P2): mark each dead and stop it matching. The
     * adopted models are un-hidden by {@link #teardownModels} and the views destroyed by {@link #destroyWidgets}
     * (both run in the same {@link #teardown}), so this only clears the dispatch state.
     */
    private static void teardownReplacers(Addon a) {
        for(LuaReplacer r : a.replacers) {
            r.alive = false;
            r.active.clear();
        }
        widgetReplacers.removeAll(a.replacers);
        a.replacers.clear();
    }

    // ------------------------------------------------------------------ global hotkeys (hafen.key, 2e-2)

    /**
     * Register a global hotkey ({@code hafen.key.bind(name, defaultKey, fn)}, spec 07 "Input"): create/fetch a
     * namespaced {@link KeyBinding} (remappable + persisted under {@code keybind/addon/<id>/<name>}) for the
     * parsed default key, pair it with {@code fn} in a {@link LuaKeyBind}, register it in the global dispatch
     * list + the addon's owned-resource registry (dropped on reload/disable, P2), and return the Lua handle
     * ({@code :remove()} / {@code :key()}). {@code defaultKey} may be {@code nil} or {@code "None"} (unbound by
     * default — the user assigns it in the keybind panel). Throws a {@link LuaError} for a bad {@code name}/{@code
     * fn} or an unparseable key string. Needs no live target (unlike an input hook) so it can be registered any
     * time, but the file body / OnLoad is the natural place.
     */
    private static LuaValue newKeyBind(final Addon owner, LuaValue name, LuaValue defaultKey, LuaValue fn) {
        if(!name.isstring() || !fn.isfunction())
            throw new LuaError("hafen.key.bind(name, defaultKey, fn) expects (string, string|nil, function)");
        String nm = name.tojstring();
        KeyMatch def;
        if(defaultKey.isnil()) {
            def = KeyMatch.nil;                       // unbound by default; user assigns it in the keybind panel
        } else if(defaultKey.isstring()) {
            def = parseKeyMatch(defaultKey.tojstring());
            if(def == null)
                throw new LuaError("hafen.key.bind: cannot parse key '" + defaultKey.tojstring()
                                   + "' (examples: \"F5\", \"Ctrl+M\", \"Shift+Alt+Left\", \"None\")");
        } else {
            throw new LuaError("hafen.key.bind: defaultKey must be a string like \"Ctrl+M\" or nil");
        }
        // KeyBinding.get() is a process-global registry: it returns the SAME binding across reloads/sessions, so
        // a user's re-map (persisted in the client prefs) survives; the default is used only when first created.
        KeyBinding kbnd = KeyBinding.get("addon/" + owner.manifest.id + "/" + nm, def);
        final LuaKeyBind h = new LuaKeyBind(owner, nm, kbnd, fn);
        keyBinds.add(h);
        owner.keybinds.add(h);
        LuaTable handle = new LuaTable();
        handle.set("remove", new ZeroArgFunction() {
            public LuaValue call() {
                removeKeyBind(owner, h);
                return LuaValue.NIL;
            }
        });
        handle.set("key", new ZeroArgFunction() {     // the current key's display name (e.g. "Ctrl+M" / "None")
            public LuaValue call() {
                KeyMatch km = h.binding.key();
                return (km == null) ? LuaValue.NIL : LuaValue.valueOf(km.name());
            }
        });
        return handle;
    }

    /**
     * Parse a hotkey description ("F5", "Ctrl+M", "Shift+Alt+Left", "None") into a {@link KeyMatch}. The last
     * {@code "+"}-separated token is the key; the earlier tokens are modifiers (Ctrl/Control/Ctl, Shift, Alt/Meta,
     * case-insensitive). A named key (F1..F12, arrows, Home/End/PageUp/PageDown, Space, Enter/Return, Tab,
     * Escape, Backspace, Delete, Insert — see {@link #KEYCODES}) resolves to its code via {@link KeyMatch#forcode};
     * any single character resolves via {@link KeyMatch#forchar} (so letters/digits/symbols work directly).
     * {@code "None"}/empty → {@link KeyMatch#nil}. Modifier matching is exact (no mods → the bare key only, so
     * "M" never fires on Ctrl+M). Returns {@code null} if it cannot be parsed (unknown modifier, or an unknown
     * multi-character key name), so the caller can raise a clear Lua error.
     */
    private static KeyMatch parseKeyMatch(String desc) {
        if(desc == null)
            return null;
        String s = desc.trim();
        if(s.isEmpty() || s.equalsIgnoreCase("none"))
            return KeyMatch.nil;
        String[] parts = s.split("\\+");
        String keytok = parts[parts.length - 1].trim();
        if(keytok.isEmpty())                          // e.g. a trailing '+' with no key
            return null;
        int mods = 0;
        for(int i = 0; i < parts.length - 1; i++) {
            String m = parts[i].trim().toLowerCase();
            if(m.equals("ctrl") || m.equals("control") || m.equals("ctl") || m.equals("c"))
                mods |= KeyMatch.C;
            else if(m.equals("shift") || m.equals("s"))
                mods |= KeyMatch.S;
            else if(m.equals("alt") || m.equals("meta") || m.equals("m"))
                mods |= KeyMatch.M;
            else
                return null;                          // unknown modifier token
        }
        Integer code = KEYCODES.get(keytok.toUpperCase());
        if(code != null)
            return KeyMatch.forcode(code, mods);
        if(keytok.length() == 1)
            return KeyMatch.forchar(keytok.charAt(0), mods);
        return null;                                  // unknown multi-character key name
    }

    /** Remove one hotkey: stop it firing + drop it from the global dispatch list (the handle's {@code :remove()}). */
    private static void removeKeyBind(Addon owner, LuaKeyBind h) {
        h.alive = false;
        keyBinds.remove(h);
        owner.keybinds.remove(h);
    }

    /**
     * Mark dead + unregister every hotkey this addon owns (teardown on reload/disable, P2). The {@link KeyBinding}
     * registry entries are process-global + persistent and are deliberately left intact (that is how the client
     * remembers a re-mapped addon key across reloads) — teardown drops only the Lua-handler wrapper.
     */
    private static void teardownKeyBinds(Addon a) {
        for(LuaKeyBind h : a.keybinds) {
            h.alive = false;
            keyBinds.remove(h);
        }
        a.keybinds.clear();
    }

    /**
     * One addon's registered hotkeys, for the client keybind panel (Phase 2e-3, WoW-style). Immutable; built by
     * {@link #describeKeyBinds()}. Only addons that registered at least one hotkey get a group, so the panel
     * shows a section per addon (labelled {@link #addon}) exactly when that addon has hotkeys.
     */
    public static final class KeyBindGroup {
        public final String addon;                 // the addon's display name — the section header
        public final List<KeyBindEntry> binds;     // its hotkeys, in registration order
        KeyBindGroup(String addon, List<KeyBindEntry> binds) { this.addon = addon; this.binds = binds; }
    }

    /** One hotkey row: the binding's addon-local {@link #name} (label) + its client {@link #binding} (remap target). */
    public static final class KeyBindEntry {
        public final String name;
        public final KeyBinding binding;
        KeyBindEntry(String name, KeyBinding binding) { this.name = name; this.binding = binding; }
    }

    /**
     * The registered addon hotkeys grouped by owning addon, for the client keybind panel (Options &gt;
     * Keybindings, Phase 2e-3). Only addons with at least one <b>live</b> hotkey appear (WoW-style) — each as a
     * {@link KeyBindGroup} whose {@code addon} is the section header and whose {@code binds} are the rows. Order
     * is the addons' first registration; within an addon, registration order. Reads the live {@link #keyBinds}
     * list on the UI thread (panel build): a disabled/unloaded addon has no live hotkey, so it does not appear
     * (its persisted key pref still survives in the {@link KeyBinding} registry). The panel drives each
     * {@code binding} through the client's own capture button, which persists the re-map exactly like every
     * built-in binding — so no extra persistence is needed here.
     */
    public static List<KeyBindGroup> describeKeyBinds() {
        LinkedHashMap<Addon, List<KeyBindEntry>> byAddon = new LinkedHashMap<Addon, List<KeyBindEntry>>();
        for(LuaKeyBind kb : keyBinds) {
            if(!kb.alive)
                continue;
            List<KeyBindEntry> l = byAddon.get(kb.owner);
            if(l == null)
                byAddon.put(kb.owner, l = new ArrayList<KeyBindEntry>());
            l.add(new KeyBindEntry(kb.name, kb.binding));
        }
        List<KeyBindGroup> out = new ArrayList<KeyBindGroup>();
        for(Map.Entry<Addon, List<KeyBindEntry>> e : byAddon.entrySet())
            out.add(new KeyBindGroup(e.getKey().manifest.name, e.getValue()));
        return out;
    }

    /** Any addon currently has a HUD overlay? (Decides whether to queue the per-frame afterdraw.) */
    private static boolean anyHudOverlays() {
        for(Addon a : addons)
            if(!a.hudOverlays.isEmpty())
                return true;
        return false;
    }

    /** Any addon currently has a gob overlay? (Gates the sweep and the idle-attrib cleanup.) */
    private static boolean anyGobOverlays() {
        for(Addon a : addons)
            if(!a.gobOverlays.isEmpty())
                return true;
        return false;
    }

    /**
     * Paint every addon's HUD overlays. Runs as a one-shot {@link UI.AfterDraw} (re-queued from {@link #tick})
     * after {@code root.draw}, so overlays land ON TOP of the whole HUD. The {@code g} is the full-screen
     * root GOut (absolute screen coords); {@code w,h} are the screen size. On the UI thread (inside UI.draw).
     */
    static void paintHudOverlays(GOut g) {
        LuaTable gt = hudGout.bind(g);
        try {
            LuaValue w = LuaValue.valueOf(g.sz().x), h = LuaValue.valueOf(g.sz().y);
            for(Addon a : addons) {
                for(HudOverlay o : a.hudOverlays) {
                    if(o.active)
                        callLua(a, o.fn, gt, w, h);
                }
            }
        } finally {
            hudGout.unbind();
        }
    }

    /**
     * Throttled gob-overlay sweep (2b): attach a shared {@link LuaGobOverlay} to every gob that matches at
     * least one active filter — like {@code SpeakerIcon.sweep}, but with dynamic (Lua) filters, so it is
     * rate-limited ({@link #GOB_SWEEP_INTERVAL}). Attach-only: a gob that later stops matching keeps an idle
     * attrib that draws nothing (the per-frame draw re-checks filters); the attribs are detached wholesale
     * once no addon wants gob overlays (handle {@code :remove()} / teardown). Runs in {@code tick} on the UI
     * thread, so a gob's filter is watchdog-armed + CPU-accounted via {@link #callLua}.
     */
    private static void sweepGobOverlays() {
        if(clock - lastGobSweep < GOB_SWEEP_INTERVAL)
            return;
        lastGobSweep = clock;
        if(!anyGobOverlays())
            return;
        for(Gob g : allGobs()) {
            if(g.getattr(LuaGobOverlay.class) != null)
                continue;                       // already tracked (the draw pass re-checks filters)
            if(!gobMatchesAny(gobSnapshot(g)))
                continue;
            try {
                g.setattr(new LuaGobOverlay(g));
            } catch(Loading l) {
                /* the gob's render slots aren't ready yet — retry on the next sweep */
            } catch(RuntimeException e) {
                /* never break the tick over a single gob */
            }
        }
    }

    /** Does {@code snap} match any addon's active gob-overlay filter? (Used by the sweep.) */
    private static boolean gobMatchesAny(LuaValue snap) {
        for(Addon a : addons)
            for(GobOverlay o : a.gobOverlays)
                if(o.active && gobFilterMatch(o, snap))
                    return true;
        return false;
    }

    /**
     * Evaluate one gob overlay's filter against a gob snapshot. A string filter is a cheap Java substring
     * match on the gob's name; a function filter is called through {@link #callLua} (watchdog-armed,
     * error-isolated, CPU-accounted) — an error drops the match.
     */
    private static boolean gobFilterMatch(GobOverlay o, LuaValue snap) {
        LuaValue f = o.filter;
        if(f.isstring()) {
            LuaValue name = snap.get("name");
            return name.isstring() && name.tojstring().contains(f.tojstring());
        }
        return callLua(o.owner, f, snap).arg1().toboolean();
    }

    /**
     * Paint every matching addon's gob overlay for one gob — called from {@link LuaGobOverlay#draw} with the
     * gob's projected screen point {@code sc}. Builds the gob snapshot once (lazily, only if some addon has a
     * gob overlay), re-checks each filter, and invokes the matching draw callbacks {@code draw(g, gob, sx, sy)}
     * through {@link #callLua}. On the UI thread (inside the Render2D pass of {@code UI.draw}).
     */
    static void paintGobOverlays(Gob gob, GOut g, LuaGOut gwrap, Coord sc) {
        LuaValue snap = null;
        LuaValue sx = LuaValue.valueOf(sc.x), sy = LuaValue.valueOf(sc.y);
        for(Addon a : addons) {
            if(a.gobOverlays.isEmpty())
                continue;
            for(GobOverlay o : a.gobOverlays) {
                if(!o.active)
                    continue;
                if(snap == null)
                    snap = gobSnapshot(gob);
                if(!gobFilterMatch(o, snap))
                    continue;
                LuaTable gt = gwrap.bind(g);
                try {
                    callLua(a, o.draw, gt, snap, sx, sy);
                } finally {
                    gwrap.unbind();
                }
            }
        }
    }

    /**
     * Detach every {@link LuaGobOverlay} attrib from all live gobs (best-effort; no session/world → no-op).
     * Mutating a gob's render slots is done under {@code synchronized(ui)} — like {@link #destroyWidgets} —
     * because teardown may run off the UI thread (session bind), while the sweep's {@code setattr} is already
     * on the UI thread (inside {@code tick}).
     */
    private static void detachGobOverlays() {
        UI u = ui;
        Runnable detach = () -> {
            try {
                for(Gob g : allGobs()) {
                    if(g.getattr(LuaGobOverlay.class) != null)
                        g.delattr(LuaGobOverlay.class);
                }
            } catch(RuntimeException e) {
                /* best-effort cleanup — a leftover idle attrib draws nothing anyway */
            }
        };
        if(u != null) {
            synchronized(u) { detach.run(); }
        } else {
            detach.run();
        }
    }

    // ------------------------------------------------------------- saved variables (hafen.store, 1e)

    /**
     * The {@code savedata/} directory: sibling of the resolved addon dir (so it follows the same
     * dev/release resolution as {@link #addonDir} — {@code bin/savedata} in a release,
     * {@code ${basedir}/savedata} under the dev override). {@code -Dhaven.savedatadir} overrides.
     */
    static File saveDir() {
        String override = System.getProperty("haven.savedatadir");
        if((override != null) && !override.isEmpty())
            return new File(override);
        File addons = addonDir();
        File parent = addons.getParentFile();
        return new File((parent != null) ? parent : new File("."), "savedata");
    }

    /**
     * Capture the per-character scope folder ({@code <genus>_<char>}) now that the HUD is up, and load
     * every addon's per-character saved variables into its {@code hafen.store} <b>before</b>
     * {@code OnEnterWorld} fires (so handlers see restored data). Called once per world entry.
     */
    private static void restorePerChar() {
        GameUI g = gui();
        if(g == null)
            return;
        charScope = scopeKey(g.genus, g.chrid);
        if(charScope == null)
            return;
        for(Addon a : addons)
            loadScope(a, false);
    }

    /** Build the {@code <genus>_<char>} folder name (path-sanitized), or {@code null} if no character. */
    private static String scopeKey(String genus, String chrid) {
        if((chrid == null) || chrid.isEmpty())
            return null;
        String g = sanitize((genus == null) ? "" : genus);
        String c = sanitize(chrid);
        return g.isEmpty() ? c : (g + "_" + c);
    }

    /** Replace filesystem-hostile characters so a genus/char string is a safe single path segment. */
    private static String sanitize(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for(int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            b.append(((c >= 'a') && (c <= 'z')) || ((c >= 'A') && (c <= 'Z')) ||
                     ((c >= '0') && (c <= '9')) || (c == '.') || (c == '-') ? c : '_');
        }
        return b.toString().trim();
    }

    /** The on-disk JSON file for one addon + scope (may not exist yet). */
    private static File storeFile(Addon a, boolean account) {
        File dir = account ? new File(saveDir(), "account") : new File(saveDir(), charScope);
        return new File(dir, a.manifest.id + ".json");
    }

    /**
     * Load one scope's saved variables from disk into the addon's {@code hafen.store} tables (filling
     * them in place, preserving table identity). Missing/malformed files leave the tables as-is. After
     * loading, the write-skip cache is primed with the canonical serialization of what we now hold, so
     * an unchanged first flush writes nothing.
     */
    private static void loadScope(Addon a, boolean account) {
        if((a.store == null) || !hasScope(a, account))
            return;
        if(!account && (charScope == null))
            return;                                     // per-char load needs a known character
        String text = readFile(storeFile(a, account));
        if(text != null) {
            try {
                Object root = Json.parse(text);
                if(root instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = (Map<String, Object>)root;
                    for(Manifest.SavedVar sv : a.manifest.savedVariables) {
                        if(sv.account != account)
                            continue;
                        LuaValue cur = a.store.get(sv.name);
                        LuaTable tgt;
                        if(cur.istable()) {
                            tgt = (LuaTable)cur;
                            clearTable(tgt);            // refill in place → the addon's ref stays valid
                        } else {
                            tgt = new LuaTable();
                            a.store.set(sv.name, tgt);
                        }
                        fillTable(tgt, m.get(sv.name));  // object or array; absent/scalar → left empty
                    }
                }
            } catch(RuntimeException e) {
                log(a, "store: could not read " + storeFile(a, account).getName() + ": " + e);
            }
        }
        String canon = scopeJson(a, account);          // prime the write-skip cache
        if(account) a.lastAccountJson = canon; else a.lastCharJson = canon;
    }

    /** Write an addon's changed saved variables to disk (both scopes). Skips unchanged files. */
    private static void flush(Addon a) {
        if((a == null) || (a.store == null) || a.manifest.savedVariables.isEmpty())
            return;
        try {
            writeScope(a, true);                        // account (always resolvable)
            if(charScope != null)
                writeScope(a, false);                   // per-char (only once in-world)
        } catch(RuntimeException e) {
            log(a, "store: flush failed: " + e);
        }
    }

    /** Serialize one scope's vars and write the file if it differs from the last write. */
    private static void writeScope(Addon a, boolean account) {
        String out = scopeJson(a, account);
        if(out == null)
            return;                                     // this addon declares no vars of this scope
        String last = account ? a.lastAccountJson : a.lastCharJson;
        if(out.equals(last))
            return;                                     // unchanged since the last write → skip disk I/O
        if(writeFile(storeFile(a, account), out)) {
            if(account) a.lastAccountJson = out; else a.lastCharJson = out;
        }
    }

    /**
     * Serialize one scope's saved vars as a JSON object {@code {name: table, …}} (reusing the compact
     * REPL writer), or {@code null} if the addon declares no vars of this scope. A non-table value at a
     * declared name is written as {@code {}} (the contract is "a table per name").
     */
    private static String scopeJson(Addon a, boolean account) {
        LuaTable wrap = new LuaTable();
        boolean any = false;
        for(Manifest.SavedVar sv : a.manifest.savedVariables) {
            if(sv.account != account)
                continue;
            any = true;
            LuaValue v = a.store.get(sv.name);
            wrap.set(sv.name, v.istable() ? v : new LuaTable());
        }
        return any ? json(wrap) : null;
    }

    /** Does the addon declare at least one saved variable of the given scope? */
    private static boolean hasScope(Addon a, boolean account) {
        for(Manifest.SavedVar sv : a.manifest.savedVariables)
            if(sv.account == account)
                return true;
        return false;
    }

    /** Convert a parsed-JSON value ({@link Json} shapes) to a Lua value (arrays → 1-based tables). */
    private static LuaValue jsonToLua(Object o) {
        if(o == null)
            return LuaValue.NIL;
        if(o instanceof Boolean)
            return LuaValue.valueOf(((Boolean)o).booleanValue());
        if(o instanceof Number)
            return LuaValue.valueOf(((Number)o).doubleValue());
        if(o instanceof String)
            return LuaValue.valueOf((String)o);
        if((o instanceof Map) || (o instanceof List)) {
            LuaTable t = new LuaTable();
            fillTable(t, o);
            return t;
        }
        return LuaValue.NIL;                            // unknown type → drop
    }

    /**
     * Fill a Lua table from a parsed-JSON object (string keys) or array (1-based). Anything else is a
     * no-op (so a missing/scalar value leaves the table empty). JSON nulls are skipped.
     */
    private static void fillTable(LuaTable t, Object o) {
        if(o instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>)o;
            for(Map.Entry<String, Object> e : m.entrySet()) {
                LuaValue v = jsonToLua(e.getValue());
                if(!v.isnil())
                    t.set(e.getKey(), v);
            }
        } else if(o instanceof List) {
            int i = 1;
            for(Object e : (List<?>)o)
                t.set(i++, jsonToLua(e));               // our writes never put null in an array (no holes)
        }
    }

    /** Remove every key from a Lua table (keys() is a snapshot, so this is safe). */
    private static void clearTable(LuaTable t) {
        for(LuaValue k : t.keys())
            t.set(k, LuaValue.NIL);
    }

    /** Read a UTF-8 file, or {@code null} if it is absent/unreadable. */
    private static String readFile(File f) {
        if((f == null) || !f.isFile())
            return null;
        try {
            return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } catch(Exception e) {
            log("store: could not read " + f + ": " + e);
            return null;
        }
    }

    /**
     * Write text to a file atomically (temp file + move), creating parent dirs. Returns whether it
     * succeeded (a failure — e.g. a read-only install — is logged, not thrown).
     */
    private static boolean writeFile(File f, String text) {
        try {
            File parent = f.getParentFile();
            if(parent != null)
                parent.mkdirs();
            Path dst = f.toPath();
            Path tmp = dst.resolveSibling(f.getName() + ".tmp");
            Files.write(tmp, text.getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch(Exception atomicUnsupported) {
                Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch(Exception e) {
            log("store: could not write " + f + ": " + e);
            return false;
        }
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
            Globals g = Sandbox.consoleGlobals();   // trusted operator console (full stdlib) + watchdog
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
            Sandbox.arm(consoleOwner.env);   // watchdog the console too (e.g. a stray `while true do end`)
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

    // ------------------------------------------------ action bar / hotbar + equipment (1d-4)
    // NB the engine calls the action bar the "belt" (GameUI.belt / BeltSlot / setbelt) — H&H's own term;
    // the addon-facing API deliberately exposes it as `hafen.actionbar` (clearer, WoW-like). These helpers
    // are named actionbar* but read the engine's belt[] array; the two names denote the same thing.

    /**
     * Read action-bar slot {@code n} into a snapshot, or nil for an out-of-range or empty slot. Slots are
     * the <b>raw 0-based game index</b> (0..143 — the same index the server uses and that action-bar
     * <i>use</i> will take in Phase 4), NOT a 1-based Lua position: the slot index is the game's index
     * everywhere (one canonical way).
     */
    private static LuaValue actionbarSlot(int n) {
        GameUI g = gui();
        if((g == null) || (g.belt == null) || (n < 0) || (n >= g.belt.length))
            return LuaValue.NIL;
        return actionbarSnapshot(g.belt[n]);
    }

    /**
     * An action-bar slot snapshot: {@code res} (the icon resource — stable identity), {@code name} (the
     * action's display name for a pagina slot, else the resource tooltip), and {@code cooldown} (0..1,
     * present only for a pagina action carrying a meter — e.g. an ability recharging; not seconds). Every
     * field is optional / Loading-guarded, so a slot resolving surfaces as a partial-then-full snapshot.
     */
    private static LuaValue actionbarSnapshot(GameUI.BeltSlot s) {
        if(s == null)
            return LuaValue.NIL;
        LuaTable t = new LuaTable();
        Resource r = actionbarResObj(s);
        if(r != null)
            t.set("res", LuaValue.valueOf(r.name));
        String name = actionbarName(s, r);
        if(name != null)
            t.set("name", LuaValue.valueOf(name));
        Double cd = actionbarCooldown(s);
        if(cd != null)
            t.set("cooldown", LuaValue.valueOf(cd));
        return t;
    }

    /** The icon {@link Resource} behind an action-bar slot (a {@code ResBeltSlot} item or a
     *  {@code PagBeltSlot} action), or {@code null} (Loading-guarded). */
    private static Resource actionbarResObj(GameUI.BeltSlot s) {
        try {
            if(s instanceof GameUI.ResBeltSlot)
                return ((GameUI.ResBeltSlot)s).getres();
            if(s instanceof GameUI.PagBeltSlot)
                return ((GameUI.PagBeltSlot)s).pag.res();
        } catch(RuntimeException e) {   // Loading etc.
        }
        return null;
    }

    /** Display name of an action-bar slot: the pagina action's name, else the resource tooltip, else nil. */
    private static String actionbarName(GameUI.BeltSlot s, Resource r) {
        if(s instanceof GameUI.PagBeltSlot) {
            try {
                return ((GameUI.PagBeltSlot)s).pag.button().name();
            } catch(RuntimeException e) {   // Loading — fall through to the tooltip
            }
        }
        if(r != null) {
            try {
                Resource.Tooltip tt = r.layer(Resource.tooltip);
                if((tt != null) && (tt.t != null))
                    return tt.t;
            } catch(RuntimeException e) {
            }
        }
        return null;
    }

    /** Cooldown/meter fraction (0..1) of a pagina action-bar slot, or {@code null} (none / Loading). */
    private static Double actionbarCooldown(GameUI.BeltSlot s) {
        if(s instanceof GameUI.PagBeltSlot) {
            try {
                return ((GameUI.PagBeltSlot)s).pag.button().meter.get();   // AttrCache swallows Loading -> null
            } catch(RuntimeException e) {   // button() itself may be Loading
            }
        }
        return null;
    }

    /** Do two action-bar slot snapshots carry the same res/name? ({@code cooldown} is excluded — a live
     *  meter must not fire {@code ActionbarChanged} every frame; for change-detection only.) */
    private static boolean actionbarEqual(LuaValue a, LuaValue b) {
        if((a == null) || (b == null))
            return false;
        return luaFieldEq(a, b, "res") && luaFieldEq(a, b, "name");
    }

    /**
     * Read an {@link Equipory}'s worn {@link WItem} children into an array of item snapshots, each with
     * its equipment {@code slot} index and slot {@code pos} name. Backs both {@code hafen.items.equipment}
     * and the {@code EquipChanged} change-detection. A two-slot item appears as two entries (distinct
     * {@code slot}).
     */
    private static LuaValue readEquipment(Equipory eq) {
        LuaTable out = new LuaTable();
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

    /** Do two equipment snapshots carry the same slot/res/name/num? ({@code wear} is excluded — a slow
     *  durability drift is not an equip change; positional, for change-detection.) */
    private static boolean equipEqual(LuaValue a, LuaValue b) {
        if((a == null) || (b == null) || !a.istable() || !b.istable())
            return false;
        int n = a.length();
        if(n != b.length())
            return false;
        for(int i = 1; i <= n; i++) {
            LuaValue ea = a.get(i), eb = b.get(i);
            if(!luaFieldEq(ea, eb, "slot") || !luaFieldEq(ea, eb, "res")
               || !luaFieldEq(ea, eb, "name") || !luaFieldEq(ea, eb, "num"))
                return false;
        }
        return true;
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

    /** A HUD overlay ({@code hafen.ui.overlay}): a draw fn painted on top of the HUD each frame (2b). */
    public static final class HudOverlay {
        final Addon owner;
        final LuaValue fn;
        boolean active = true;

        HudOverlay(Addon owner, LuaValue fn) {
            this.owner = owner;
            this.fn = fn;
        }
    }

    /**
     * A world-space gob overlay ({@code hafen.ui.gobOverlay}): a filter (function or name-substring string)
     * that selects gobs and a draw fn painted over each matching gob (2b).
     */
    public static final class GobOverlay {
        final Addon owner;
        final LuaValue filter, draw;
        boolean active = true;

        GobOverlay(Addon owner, LuaValue filter, LuaValue draw) {
            this.owner = owner;
            this.filter = filter;
            this.draw = draw;
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
