package io.brodgar.addon;

import haven.AddonWidgets;
import haven.Astronomy;
import haven.Audio;
import haven.BAttrWnd;
import haven.BuddyWnd;
import haven.Buff;
import haven.Bufflist;
import haven.CharWnd;
import haven.Console;
import haven.Coord;
import haven.Coord2d;
import haven.Coord3f;
import haven.Coord3f;
import haven.Drawable;
import haven.Equipory;
import haven.FightWnd;
import haven.FlowerMenu;
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
import haven.Makewindow;
import haven.MapFile;
import haven.MapView;
import haven.Message;
import haven.MessageBuf;
import haven.MCache;
import haven.MenuGrid;
import haven.MiniMap;
import haven.Moving;
import haven.Music;
import haven.OCache;
import haven.Party;
import haven.QuestWnd;
import haven.ResDrawable;
import haven.Resource;
import haven.SAttrWnd;
import haven.SkillWnd;
import haven.Speaking;
import haven.Speedget;
import haven.SprDrawable;
import haven.TexI;
import haven.UI;
import haven.Utils;
import haven.WItem;
import haven.Widget;
import haven.Window;
import haven.WoundWnd;
import haven.render.RenderTree;
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
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
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

import javax.imageio.ImageIO;

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

    // -- addon slash commands (gap subsystem A11 / api-reference "hafen.slash"): WoW-style :command ----------
    // hafen.slash.register(name, fn) routes ":name args…" to a Lua handler. The one reload-leak the audit flagged
    // (coverage-gaps C1): Console.setscmd has NO unregister, so re-registering a command per reload would leak /
    // duplicate commands. The fix is a SINGLE ENGINE-LIFETIME DISPATCHER per name — the first time a name is
    // registered we install one Console command that forever routes to slashHandlers.get(name), the CURRENT live
    // handler, and never touch Console again for that name. Reload/disable only swaps (or drops) the entry in
    // slashHandlers; a dropped name's dispatcher stays installed but replies "no addon handles :name". So the count
    // of Console commands is bounded by the distinct names ever used, and each always routes to live state or none.
    // slashHandlers = name -> current live handler; slashDispatched = names whose dispatcher is installed (grows
    // only — engine-lifetime, deliberately NOT reset per session, UNLIKE the session-scoped hooks above).
    // Registration may arrive on the UI thread (in-game ":") or the stdin reader thread, so both are concurrent
    // and the install path synchronizes on slashDispatched.
    private static final Map<String, LuaSlashCommand> slashHandlers = new ConcurrentHashMap<String, LuaSlashCommand>();
    private static final Set<String> slashDispatched = ConcurrentHashMap.newKeySet();

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

    // -- write-actions permission (spec 12-security-and-permissions / D-010 / D-025 / D-027; refined by D-028):
    // the ONE gated surface. Every hafen.act.* verb (and the per-subsystem *(gated action)* verbs — speed.set,
    // craft.make, actionbar.use, kin.* — arriving in later Phase-4 slices) DRIVES the character by sending a
    // player-action wdgmsg — it acts on the user's behalf (moves them, uses items, interacts with the world),
    // which is powerful, so it is a PER-ADDON permission granted to an addon that DECLARED "actions" in its
    // manifest ("permissions": ["actions"]) — requireActions throws a guiding Lua error otherwise. The read/UI/
    // event tiers are unaffected.
    //   D-028: there is NO global master switch (D-027's was dropped). The action tier is ALWAYS available at the
    //   system level; the user's control is entirely PER-ADDON: a write-declaring addon is DISABLED BY DEFAULT
    //   (opt-in; a persisted "seen" set distinguishes a new one from one the user deliberately chose — handled in
    //   scanAddonDefaults) and enabling it in the AddOns panel raises a CONSENT DIALOG (slice 4c). So a running
    //   write addon is, by construction, one the user knowingly permitted — which is why requireActions need only
    //   re-check the manifest declaration, with no runtime switch. It stays server-authoritative regardless: an
    //   addon can only ever send what a player click could send.
    private static final String PREF_ACTIONS_SEEN = "addons/actions.seen";    // write-addon ids we've applied the default-disable to

    private AddonManager() {
    }

    // ------------------------------------------------------------- write-actions permission

    /**
     * Whether {@code owner} may call an action verb: it DECLARED the {@code "actions"} permission (D-027; D-028 —
     * per-addon only, no global switch). A running write addon is one the user already opted into — write addons
     * are disabled by default and enabling one goes through the AddOns-panel consent dialog (slice 4c) — so the
     * manifest declaration is the only per-verb check.
     */
    static boolean actionsGranted(Addon owner) {
        return (owner != null) && owner.manifest.usesActions();
    }

    /**
     * Gate an action verb (D-027; D-028): the calling addon must have DECLARED the {@code "actions"} permission in
     * its manifest, or this throws a guiding Lua error. The user's consent is enforced at ENABLE time by the AddOns
     * panel's consent dialog (a running write addon is already permitted), so there is no separate runtime switch.
     */
    private static void requireActions(Addon owner, String verb) {
        if((owner == null) || !owner.manifest.usesActions())
            throw new LuaError(verb + ": this addon did not declare the \"actions\" permission — add"
                + " \"permissions\": [\"actions\"] to its manifest.json (D-027: write-actions must be declared).");
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
        synchronized(markerById) {    // A1: drop the per-session marker-ref map (Marker identities are per-session)
            markerIds.clear();
            markerById.clear();
        }
        markersPrimed = false;        // re-prime MarkersChanged against the new session's map DB
        treeDirty.clear();            // reset the widget-tree read mechanism for the new session
        vitalsCache = null;
        treeAdapters.clear();
        treeAdapters.add(new VitalsAdapter());   // hp/stamina/energy (uimsg-driven)
        treeAdapters.add(new BuffsAdapter());    // buff add/remove (per-tick poll) + change (uimsg)
        treeAdapters.add(new FepAdapter());      // FEP/food + hunger (uimsg-driven)
        treeAdapters.add(new StudyAdapter());    // study/curiosity slots (per-tick poll)
        treeAdapters.add(new ActionbarAdapter()); // action-bar / hotbar slots (per-tick poll)
        treeAdapters.add(new EquipAdapter());    // equipment add/remove (per-tick poll)
        treeAdapters.add(new KinAdapter());      // kin/buddy roster add/remove/status (uimsg-driven)
        treeAdapters.add(new QuestAdapter());    // quest log add / complete (uimsg-driven)
        treeAdapters.add(new WoundAdapter());    // wounds add / heal / severity change (per-tick poll)

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
        scanAddonDefaults();          // D-027: default-disable newly-discovered write addons; refresh the write-addon cache
        File dir = addonDir();
        log("addons dir: " + dir);
        File[] subs = dir.listFiles(File::isDirectory);
        if(subs == null) {
            log("no addons/ directory");
            return;
        }
        // D-006: honor the persisted enabled set (skip disabled). A write-declaring addon is disabled by default
        // (D-027/D-028) until the user enables it through the AddOns-panel consent dialog (slice 4c); once enabled
        // it loads like any other addon (there is no global actions switch to also satisfy — D-028).
        Set<String> disabled = disabledSet();
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
        teardownSlashCommands(a);     // A11: drop the addon's live slash handlers (Console dispatchers stay — C1)
        teardownGhosts(a);            // V1: destroy client-only world ghosts (remove the scene slot + free the sprite)
        teardownSprites(a);           // R2: destroy client-only world sprites (remove the slot + free the quad geometry)
        teardownObjects(a);           // R3: destroy client-only world objects (remove the slot + free the glTF Models; before meshes)
        teardownImages(a);            // R1: dispose custom images (frees each TexI's GL texture — no leak; after sprites)
        teardownMeshes(a);            // R3: drop custom models (frees the CPU geometry; after the objects that used them)
        teardownMouseGrabs(a);        // V5: release any active mouse-drag grab (drops the UI.Grab + unlinks the widget)
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

    /**
     * Scan {@link #addonDir()} and apply the write-addon default (disabled-by-default, opt-in per addon —
     * D-027/D-028): a discovered addon that declares the {@code "actions"} permission and has NOT been seen before
     * is added to the persisted disabled set (and to a persisted "seen" set so it is defaulted exactly once — a
     * later scan then respects whatever the user has since chosen, i.e. the enable made through the consent
     * dialog). Cheap disk I/O (a handful of small manifests); call on a (re)load / panel build, not per frame. The
     * pure policy is {@link #applyActionsDefaults} (headless-testable).
     */
    private static void scanAddonDefaults() {
        File dir = addonDir();
        File[] subs = dir.listFiles(File::isDirectory);
        if(subs == null)
            return;
        Map<String, Boolean> declares = new LinkedHashMap<String, Boolean>();
        for(File sub : subs) {
            if(!new File(sub, "manifest.json").isFile())
                continue;
            try {
                declares.put(sub.getName(), Manifest.load(sub.toPath()).usesActions());
            } catch(Exception e) {
                /* a broken manifest surfaces as an error row elsewhere; no default to apply here */
            }
        }
        List<String> seenL = Utils.getprefsl(PREF_ACTIONS_SEEN, new String[0]);
        Set<String> seen = (seenL == null) ? new LinkedHashSet<String>() : new LinkedHashSet<String>(seenL);
        Set<String> disabled = disabledSet();
        int seenBefore = seen.size(), disBefore = disabled.size();   // applyActionsDefaults only ADDS to both
        applyActionsDefaults(seen, disabled, declares);
        if(seen.size() != seenBefore)
            Utils.setprefsl(PREF_ACTIONS_SEEN, seen);
        if(disabled.size() != disBefore)
            Utils.setprefsl(PREF_DISABLED, disabled);
    }

    /**
     * The pure write-addon default policy (no I/O — D-027/D-028): for each entry in {@code declares} that is a
     * write addon (value {@code true}) and NOT already in {@code seen}, mark it seen and add it to {@code disabled}
     * (disabled-by-default — write addons are opt-in per addon; enabling one goes through the consent dialog). A
     * write addon already in {@code seen} is left to the user's enable/disable choice; read addons are ignored
     * entirely. {@code seen} and {@code disabled} are mutated in place (additions only). Returns the ids of ALL
     * write addons in {@code declares}. Headless-testable.
     */
    static Set<String> applyActionsDefaults(Set<String> seen, Set<String> disabled, Map<String, Boolean> declares) {
        Set<String> writeIds = new LinkedHashSet<String>();
        for(Map.Entry<String, Boolean> e : declares.entrySet()) {
            if(!Boolean.TRUE.equals(e.getValue()))
                continue;
            String id = e.getKey();
            writeIds.add(id);
            if(seen.add(id))          // first time we've seen this addon AS a write addon → default it disabled
                disabled.add(id);
        }
        return writeIds;
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
        public final boolean enabled;          // persisted enabled state (the checkbox) — NOT the live-loaded state
        public final boolean loaded;           // currently running this session
        public final boolean declaresActions;  // declares the "actions" write permission (D-027: default-disabled, master-gated)
        public final String error;             // load/runtime error, or null
        public final String warning;           // session warning (e.g. auto-disabled by the CPU watchdog), or null

        AddonInfo(String id, String name, String version, String author, String description,
                  int apiVersion, boolean enabled, boolean loaded, boolean declaresActions,
                  String error, String warning) {
            this.id = id; this.name = name; this.version = version; this.author = author;
            this.description = description; this.apiVersion = apiVersion; this.enabled = enabled;
            this.loaded = loaded; this.declaresActions = declaresActions; this.error = error; this.warning = warning;
        }
    }

    /**
     * Every discovered addon (a folder under {@link #addonDir()} with a {@code manifest.json}), sorted by
     * id, as {@link AddonInfo} for the AddOns panel. Reads each manifest fresh from disk so disabled /
     * not-loaded addons still show name/version/author. Call on panel build/reload (it does disk I/O), not
     * per frame — use {@link #liveStatus(String)} for the cheap per-frame status refresh.
     */
    public static List<AddonInfo> describeAddons() {
        scanAddonDefaults();          // D-027: reflect the write-addon default (+ refresh the cache) for any new addon
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
                (m != null) && m.usesActions(),
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
        if(!isEnabled(id))
            return "disabled";
        return "not loaded";
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

            // 1d. Map markers (A1): fire MarkersChanged when the on-disk map DB's markerseq changes (a
            //     marker add/remove is not a uimsg — the server pushes SMarkers via markobj, the player/
            //     addon adds PMarkers, and segment merges re-key them; all bump markerseq). Global event.
            pollMarkers();

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

    /**
     * Kin/buddy roster (A6) — the {@link BuddyWnd.Buddy} entries in the Kin window ({@link
     * GameUI#buddies}, a {@link BuddyWnd}). Every roster change the client learns of arrives as a
     * targeted {@code uimsg} to the {@code BuddyWnd} — a kin added ({@code "add"}), removed ({@code
     * "rm"}), edited ({@code "upd"}: nick/group) or an online-status flip ({@code "chst"}) — so unlike
     * the buff/study adapters (whose add/remove is a widget create, invisible to the tap) this is
     * <b>uimsg-driven</b>: {@link #interested} flags those four messages, and {@link #refresh} re-reads
     * the snapshot list and fires {@code KinChanged} (with the new list) when it actually differs.
     * Change-detection is a snapshot diff, NOT {@code BuddyWnd.serial} — {@code serial} does not bump on
     * {@code "chst"} (an online/offline flip), which a kin-alert addon most wants to hear.
     */
    private static final class KinAdapter implements TreeAdapter {
        private LuaValue cache = LuaValue.NIL;   // last kin snapshot list (UI thread; change-detect)

        public boolean interested(Widget w, String msg) {
            return (w instanceof BuddyWnd) &&
                   ("add".equals(msg) || "rm".equals(msg) || "chst".equals(msg) || "upd".equals(msg));
        }

        public void refresh() {
            LuaValue snap = kinList(LuaValue.NIL);
            if(!kinListEqual(snap, cache)) {
                cache = snap;
                fire("KinChanged", snap);
            }
        }
    }

    /**
     * Quest log (A9) — the quests under the character sheet's "Quest Log" tab ({@link QuestWnd},
     * reached via the public {@code CharWnd.quest} field). Every quest change the client learns of
     * arrives as a targeted {@code "quests"} {@code uimsg} to the {@code QuestWnd} (a quest added, its
     * status advanced, or removed) — so, like the {@link KinAdapter}, this is <b>uimsg-driven</b>:
     * {@link #interested} flags that message and {@link #refresh} re-reads the full quest set and diffs
     * it against a per-id status cache via the pure {@link #questDiff}. Fires {@code QuestAdded} when a
     * new <i>active</i> quest (pending/disabled) appears and {@code QuestDone} when a previously-active
     * quest becomes <i>finished</i> (done/failed) — mirroring {@code QuestWnd}'s own completion trigger.
     * Completed quests already present at login are recorded silently (no {@code QuestAdded}), so the
     * quest history doesn't spam events. Payload = the quest snapshot.
     */
    private static final class QuestAdapter implements TreeAdapter {
        // quest id -> its last-seen status int. UI-thread-only (refresh); reset per session by
        // re-instantiation in init().
        private final Map<Integer, Integer> cache = new HashMap<Integer, Integer>();

        public boolean interested(Widget w, String msg) {
            return (w instanceof QuestWnd) && "quests".equals(msg);
        }

        public void refresh() {
            QuestWnd qw = questwnd();
            UI u = ui;
            if((qw == null) || (u == null))
                return;
            // Copy both quest lists under the ui monitor (QuestWnd.uimsg mutates them off-thread), then
            // build snapshots outside it (names may Loading) — the marker "copy under the lock" discipline.
            List<QuestWnd.Quest> all = new ArrayList<QuestWnd.Quest>();
            synchronized(u) {
                all.addAll(qw.cqst.quests);          // "Current" tab (pending / disabled)
                all.addAll(qw.dqst.quests);          // "Completed" tab (done / failed)
            }
            Map<Integer, Integer> fresh = new LinkedHashMap<Integer, Integer>();   // id -> done (in order)
            Map<Integer, QuestWnd.Quest> byId = new HashMap<Integer, QuestWnd.Quest>();
            for(QuestWnd.Quest q : all) {
                fresh.put(q.id, q.done);
                byId.put(q.id, q);
            }
            for(Object[] ev : questDiff(cache, fresh)) {          // pure diff (also updates the cache)
                QuestWnd.Quest q = byId.get((Integer)ev[1]);
                if(q != null)
                    fire((String)ev[0], questSnapshot(q));
            }
        }
    }

    /**
     * Diff a fresh {@code id -> done} quest map against {@code cache}, returning the events to fire as
     * {@code {String event, Integer id}} pairs and updating {@code cache} to match {@code fresh}. Pure
     * (no widget / Lua access) so the add/complete semantics are headless-testable:
     * <ul>
     *   <li><b>QuestAdded</b> — an id not previously cached whose status is <i>active</i>
     *       (pending/disabled). A quest already finished when first seen (e.g. the completed history that
     *       streams in at login) is recorded silently — no event.</li>
     *   <li><b>QuestDone</b> — a previously-<i>active</i> id that is now <i>finished</i> (done/failed),
     *       mirroring {@code QuestWnd}'s own completion trigger.</li>
     * </ul>
     * An id absent from {@code fresh} (server-removed) is pruned with no event, so a later re-add re-fires
     * {@code QuestAdded}.
     */
    private static List<Object[]> questDiff(Map<Integer, Integer> cache, Map<Integer, Integer> fresh) {
        List<Object[]> events = new ArrayList<Object[]>();
        for(Map.Entry<Integer, Integer> e : fresh.entrySet()) {
            Integer id = e.getKey();
            int done = e.getValue().intValue();
            Integer prev = cache.get(id);
            if(prev == null) {
                if(questActive(done))
                    events.add(new Object[]{"QuestAdded", id});
            } else if(questActive(prev.intValue()) && !questActive(done)) {
                events.add(new Object[]{"QuestDone", id});
            }
        }
        cache.keySet().retainAll(fresh.keySet());    // prune ids the server dropped
        cache.putAll(fresh);                          // update to the current statuses
        return events;
    }

    /**
     * Wounds (A9-2) — the wounds under the character sheet's "Health &amp; Wounds" tab ({@link WoundWnd},
     * reached via the public {@code CharWnd.wound} field). A wound being added / healed / worsening arrives
     * as a {@code "wounds"} {@code uimsg}, but a wound's <b>severity</b> (its {@link WoundWnd.QuickInfo}
     * magnitude) comes from resource-published {@code ItemInfo} that <b>streams in a beat after</b> the
     * wound row (its {@code res.get()} still Loading on the first refresh) — exactly the {@link StudyAdapter}
     * situation. So, like study/buffs, this is <b>poll-driven</b>: each tick it re-reads the full wound
     * list and fires <b>{@code WoundChanged}</b> only when it differs from the cache (an add/heal, a
     * severity resolving nil→value, or a wound getting worse) — the {@link #woundListEqual} change-detection,
     * with {@code severity} in the key so a worsening fires it. While the wound tab is not up yet the poll
     * is skipped (the cache is kept), so nothing fires before there is anything to read. Payload = the list
     * (the {@code KinChanged} shape). Read the initial state with {@code list()}; listen for deltas after.
     */
    private static final class WoundAdapter implements TreeAdapter {
        private LuaValue cache;   // last wound snapshot list (UI thread; change-detect)

        public boolean interested(Widget w, String msg) {
            return false;         // wound add/heal is a uimsg, but severity streams in a beat later — see poll()
        }

        public void refresh() {}

        public void poll() {
            if(woundwnd() == null)
                return;           // Health & Wounds tab not up yet — keep the cache, fire nothing
            LuaValue snap = woundList(LuaValue.NIL);
            if(!woundListEqual(snap, cache)) {
                cache = snap;
                fire("WoundChanged", snap);
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
        // fromGridPos({gridId, x, y}) — the INVERSE of gridPos: resolve a saved grid-anchored position back to
        // a login-relative WORLD coord in THIS session, or nil if that grid is not currently loaded here (the
        // caller retries as the map streams in). Persist a layout by storing gridPos(...) verbatim and reloading
        // through this — grid ids are the stable cross-session anchor, raw world coords are login-relative and do
        // not survive a relog (the marker/ghost rule). Accepts the exact {gridId=<string>, x=, y=} table gridPos
        // returns, so fromGridPos(gridPos(x,y)) round-trips to (x,y) whenever the grid is loaded.
        map.set("fromGridPos", new OneArgFunction() {
            public LuaValue call(LuaValue anchor) {
                MCache mc = mcache();
                if((mc == null) || !anchor.istable())
                    return LuaValue.NIL;
                LuaValue idv = anchor.get("gridId"), xv = anchor.get("x"), yv = anchor.get("y");
                if(!idv.isstring() || !xv.isnumber() || !yv.isnumber())
                    return LuaValue.NIL;
                long id;
                try {
                    id = Long.parseLong(idv.tojstring());
                } catch(NumberFormatException e) {   // gridId is not a valid 64-bit id string
                    return LuaValue.NIL;
                }
                Coord2d ul = AddonWidgets.gridWorldUL(mc, id);   // that grid's current UL, or null if not loaded
                if(ul == null)
                    return LuaValue.NIL;
                return xy(ul.x + xv.todouble(), ul.y + yv.todouble());
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
        // screenToWorld(sx, sy, fn) — the RAYCAST INVERSE of hafen.player.worldToScreen (spec 16 §3, V5): fn({x,y})
        // is called with the WORLD ground coord under game-window pixel (sx,sy), or fn(nil) if the pixel hit no
        // terrain (sky/off-map). It is ASYNCHRONOUS by necessity — the engine reads the true terrain point from the
        // GPU (MapView.Maptest, the same pass the client's own building placement uses), so a synchronous return
        // would stall the UI thread on a GPU fence; instead the result arrives a frame later via fn (exactly the
        // one-frame lag a placement ghost has). (sx,sy) are the same pixel space worldToScreen returns (top-left
        // origin; for the standard fullscreen MapView these are screen pixels). Requires being in the world.
        map.set("screenToWorld", new ThreeArgFunction() {
            public LuaValue call(LuaValue sx, LuaValue sy, LuaValue fn) {
                MapView m = view;
                if((m == null) || !sx.isnumber() || !sy.isnumber() || !fn.isfunction())
                    return LuaValue.NIL;
                screenToWorld(owner, m, (int)Math.round(sx.todouble()), (int)Math.round(sy.todouble()), fn);
                return LuaValue.NIL;   // async — the answer arrives through fn
            }
        });
        // snapPlace(x, y [, fine]) — snap a WORLD coord to the client's PLACEMENT grid, IDENTICALLY to placing a
        // building (spec 16 §4.1, D-033): no fine -> the tile centre; fine=true -> the sub-tile :placegrid
        // (MapView.plobpgran divisions, or free when placegrid is 0). Returns {x,y}. Pure static math shared with
        // the engine's StdPlace (MapView.placeSnap), so it always honours the live :placegrid; no map data needed.
        map.set("snapPlace", new ThreeArgFunction() {
            public LuaValue call(LuaValue x, LuaValue y, LuaValue fine) {
                if(!x.isnumber() || !y.isnumber())
                    return LuaValue.NIL;
                int modflags = fine.toboolean() ? UI.MOD_SHIFT : 0;
                Coord2d s = MapView.placeSnap(new Coord2d(x.todouble(), y.todouble()), modflags);
                return xy(s.x, s.y);
            }
        });
        // placeGrid() — the current :placegrid setting (MapView.plobpgran, the sub-tile divisions snapPlace(...,true)
        // uses; default 8, 0 = free). Read it to label a gizmo / mirror the user's placement preference (V5).
        map.set("placeGrid", new ZeroArgFunction() {
            public LuaValue call() {
                return LuaValue.valueOf(MapView.plobpgran);
            }
        });
        // snapAngle(a [, fine]) — snap a facing angle (RADIANS) to the client's placement-ANGLE grid, so a gizmo
        // rotate feels IDENTICAL to rotating a building (spec 16 §4.1, D-033): no fine -> 45° (π/4) steps; fine=true
        // -> the finer :placeangle grid (π/MapView.plobagran). Returns the snapped angle in radians, normalized to
        // (-π, π]. The absolute-angle analog of the client's (wheel-relative) StdPlace.rotate — see snapPlaceAngle;
        // reads the live public plobagran so it always honours :placeangle.
        map.set("snapAngle", new TwoArgFunction() {
            public LuaValue call(LuaValue a, LuaValue fine) {
                if(!a.isnumber())
                    return LuaValue.NIL;
                return LuaValue.valueOf(snapPlaceAngle(a.todouble(), fine.toboolean()));
            }
        });
        // placeAngle() — the current :placeangle setting (MapView.plobagran, the FINE rotation divisions
        // snapAngle(...,true) uses; default 12). The coarse 45° default is independent of it; this is the fine grain.
        map.set("placeAngle", new ZeroArgFunction() {
            public LuaValue call() {
                return LuaValue.valueOf(MapView.plobagran);
            }
        });
        hafen.set("map", map);

        // hafen.markers.* — client-side map markers (A1), read/added/removed against the client's on-disk
        // map DB (MapFile, owned by the map window / corner minimap — the same instance). Two kinds:
        // PLAYER markers (user pins: a name + colour) and SYSTEM markers (server/quest pins: a name +
        // icon). A snapshot is { id, name, type ("player"|"system"), seg (id string), tc={x,y} (the
        // segment tile coord — the PERSISTENT anchor that survives a relog, coverage-gaps C4),
        // color={r,g,b,a}+onmap (player) | icon (system), and x,y (world) + dist (from the player) which
        // are SESSION-LOCAL, present only when the marker is in the player's current segment }. add()
        // creates a PLAYER marker and persists it; remove() takes a ref from list()/add(). The DB streams
        // in a beat after enter-world (nil/empty until then — read on a timer); MarkersChanged fires on any
        // change. Coords are WORLD units (matching hafen.gob.pos/hafen.map), converted to the persistent
        // segment anchor at add time — there is no global position (anchor on grid ids / segment tc — C4).
        LuaTable markers = new LuaTable();
        markers.set("list", new OneArgFunction() {
            public LuaValue call(LuaValue filter) {
                LuaTable out = new LuaTable();
                int i = 0;
                for(LuaValue snap : markerSnapshots()) {
                    if(matches(filter, snap))
                        out.set(++i, snap);
                }
                return out;
            }
        });
        markers.set("nearest", new OneArgFunction() {
            public LuaValue call(LuaValue filter) {
                LuaValue best = LuaValue.NIL;
                double bestd = Double.POSITIVE_INFINITY;
                for(LuaValue snap : markerSnapshots()) {
                    if(!matches(filter, snap))
                        continue;
                    LuaValue d = snap.get("dist");
                    if(!d.isnumber())
                        continue;                        // cross-segment marker → no world distance
                    double dd = d.todouble();
                    if(dd < bestd) { bestd = dd; best = snap; }
                }
                return best;
            }
        });
        markers.set("add", new VarArgFunction() {
            // add(name, x, y [, opts{color={r,g,b[,a]}, onmap=bool}]) -> ref | nil  (world coords; player marker)
            public Varargs invoke(Varargs a) {
                String nm = a.optjstring(1, null);
                if((nm == null) || !a.arg(2).isnumber() || !a.arg(3).isnumber())
                    return LuaValue.NIL;
                return addMarker(nm, a.arg(2).todouble(), a.arg(3).todouble(), a.arg(4));
            }
        });
        markers.set("remove", new OneArgFunction() {
            public LuaValue call(LuaValue ref) {
                return LuaValue.valueOf(removeMarker(ref));
            }
        });
        hafen.set("markers", markers);

        // hafen.radar.* — the minimap icon registry (A2): each gob-icon "category" (a boar, a fir tree, a
        // player, …) has a show flag (draw it on the minimap/radar) and a notify flag (a sound + chat msg
        // when one appears). Backed by GobIcon.Settings (GameUI.iconconf) — the SAME registry the in-client
        // "Icon settings" window drives, so changes show there too and persist per character. A category
        // snapshot is { name (the icon tooltip), res (the resource name — the stable id), show, notify }.
        // categories([filter]) reads the current set; setVisible(filter,on)/setNotify(filter,on) flip a flag
        // on EVERY category the filter matches and persist it (debounced), returning the number matched. The
        // filter is the canonical one used across the API (nil = all, string = name substring, function =
        // predicate(snapshot)->truthy — use a predicate to match on res). The registry is empty until the
        // HUD is up and grows as the character sees new icon types (read on demand — no *Changed event).
        LuaTable radar = new LuaTable();
        radar.set("categories", new OneArgFunction() {
            public LuaValue call(LuaValue filter) {
                LuaTable out = new LuaTable();
                int i = 0;
                for(LuaValue snap : radarSnapshots()) {
                    if(matches(filter, snap))
                        out.set(++i, snap);
                }
                return out;
            }
        });
        radar.set("setVisible", new TwoArgFunction() {
            public LuaValue call(LuaValue filter, LuaValue on) {
                return LuaValue.valueOf(radarSet(filter, on.toboolean(), false));
            }
        });
        radar.set("setNotify", new TwoArgFunction() {
            public LuaValue call(LuaValue filter, LuaValue on) {
                return LuaValue.valueOf(radarSet(filter, on.toboolean(), true));
            }
        });
        hafen.set("radar", radar);

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
        // api-reference.md). Bulk reads return point-in-time snapshots carrying name/res/num/wear/pos plus a
        // `handle` (the item's server widget id = the ItemRef the gated hafen.act.item(item, verb) verb takes to
        // re-resolve + drive the live GItem — 4f; the only stable way to address an item, D-022). Reads walk the
        // WItem children of the inventory/equipory widgets (both public) → zero core edit; item names/resources
        // are Loading-guarded → nil while resolving.
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
        // A4 (completes the study/skills subsystem): the rest of the "Lore & Skills" window beyond the
        // known skills above. skillsAvailable() = the BUYABLE skills {name,res,cost} (the nsk group next
        // to the known csk group; cost = LP price). credos() = the Credos tab — {acquired, available}
        // lists (each of {name,res}) + the currently-pursued credo under `pursuing` ({name,res,level,
        // levelTotal,quest,questTotal,questId}, absent when none) + `cost` (LP to begin pursuing one), or
        // nil until the window exists. experiences() = the Lore tab {name,res,score,mtime}. All stream in
        // after enter-world like the known skills; there is no *Changed event — these change only on
        // explicit, infrequent player actions (buy / pursue / quest progress), so read them on demand.
        chr.set("skillsAvailable", new ZeroArgFunction() {
            public LuaValue call() {
                return readAvailableSkills();
            }
        });
        chr.set("credos", new ZeroArgFunction() {
            public LuaValue call() {
                return readCredos();
            }
        });
        chr.set("experiences", new ZeroArgFunction() {
            public LuaValue call() {
                return readExperiences();
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

        // hafen.kin.* — the kin/buddy roster (A6), read from the Kin window (GameUI.buddies, a BuddyWnd —
        // the same list the in-client Kin tab shows). list([filter]) returns kin snapshots {id, name,
        // group (0..7), color={r,g,b,a} (the group's colour), online (bool)} in the window's current sort
        // order; filter is the canonical nil=all / name-substring / predicate. find(nameOrId) returns one
        // snapshot — a number matches by id, a string by exact (case-insensitive) name. Subscribe to
        // KinChanged (fired with the new list when a kin is added/removed, renamed/regrouped, or flips
        // online/offline). The GATED write verbs (4g, requireActions) mutate the roster. add(secret) adds a kin
        // by the other player's HEARTH SECRET — the Kin window's "Make kin by hearth secret / Add kin" field
        // (wdgmsg("bypwd", secret)); the roster has no add-by-NAME message. remove(kin) and forget(kin) are the
        // TWO STEPS of dropping a kin — the game's own "End kinship" then "Forget" (a state machine):
        //   remove(kin)  = END KINSHIP (Buddy.endkin) — ends the kinship; the kin STAYS in the list, now merely
        //                  memorized (un-kinned). This is the "End kinship" petal (shown while the kin is active).
        //   forget(kin)  = FORGET (Buddy.forget) — drops a memorized kin from the list entirely. This is the
        //                  "Forget" petal (shown once the kin is un-kinned). To fully remove an ACTIVE kin:
        //                  remove(kin), then forget(kin) once it is memorized.
        // Both send the same wdgmsg("rm", id); the SERVER advances the state (active → memorized → gone), exactly
        // as clicking the two petals in turn does. rename(kin, name)=wdgmsg("nick"), setGroup(kin, group)=wdgmsg(
        // "grp"). `kin` = a kin snapshot (from list/find), its id, or a name (exact, case-insensitive).
        LuaTable kin = new LuaTable();
        kin.set("list", new OneArgFunction() {
            public LuaValue call(LuaValue filter) {
                return kinList(filter);
            }
        });
        kin.set("find", new OneArgFunction() {
            public LuaValue call(LuaValue key) {
                return kinFind(key);
            }
        });
        // add(secret) / remove(kin) / forget(kin) / rename(kin, name) / setGroup(kin, group) — the gated write
        // verbs (4g). requireActions-gated (D-027/D-028); `kin` resolves via resolveKin (snapshot / id / name).
        kin.set("add", new OneArgFunction() {
            public LuaValue call(LuaValue secret) {
                requireActions(owner, "hafen.kin.add");
                if(!secret.isstring())
                    throw new LuaError("hafen.kin.add(secret): secret must be a string (the other player's hearth secret)");
                actKinAdd(secret.tojstring());              // wdgmsg("bypwd", secret) — the "Add kin" field
                return LuaValue.NIL;
            }
        });
        // remove(kin) = END KINSHIP (step 1): ends the kinship; the kin stays memorized in the list.
        kin.set("remove", new OneArgFunction() {
            public LuaValue call(LuaValue ref) {
                requireActions(owner, "hafen.kin.remove");
                requireKin(ref).endkin();                  // wrap (D-009): Buddy.endkin ("End kinship") → wdgmsg("rm", id)
                return LuaValue.NIL;
            }
        });
        // forget(kin) = FORGET (step 2): drops a memorized (un-kinned) kin from the list entirely.
        kin.set("forget", new OneArgFunction() {
            public LuaValue call(LuaValue ref) {
                requireActions(owner, "hafen.kin.forget");
                requireKin(ref).forget();                  // wrap (D-009): Buddy.forget ("Forget") → wdgmsg("rm", id)
                return LuaValue.NIL;
            }
        });
        kin.set("rename", new TwoArgFunction() {
            public LuaValue call(LuaValue ref, LuaValue name) {
                requireActions(owner, "hafen.kin.rename");
                if(!name.isstring())
                    throw new LuaError("hafen.kin.rename(kin, name): name must be a string");
                requireKin(ref).chname(name.tojstring());   // wdgmsg("nick", id, name)
                return LuaValue.NIL;
            }
        });
        kin.set("setGroup", new TwoArgFunction() {
            public LuaValue call(LuaValue ref, LuaValue group) {
                requireActions(owner, "hafen.kin.setGroup");
                if(!group.isnumber())
                    throw new LuaError("hafen.kin.setGroup(kin, group): group must be a number (0..7)");
                actKinSetGroup(ref, group.toint());
                return LuaValue.NIL;
            }
        });
        hafen.set("kin", kin);

        // hafen.speed.* — movement speed (A7), read from the speed selector widget (Speedget: the four-way
        // crawl/walk/run/sprint toggle at the bottom of the HUD). get() returns the CURRENT speed as 0..3
        // (0=crawl 1=walk 2=run 3=sprint), or nil if the widget isn't up yet. max() returns the highest
        // speed currently SELECTABLE (0..3) — speeds 0..max() are available, higher ones are disabled (e.g.
        // sprint locked); nil if not up. name([n]) returns the display name of speed n (default = current;
        // from the widget's own tooltips), or nil. set(n) selects speed n (0..3) — the GATED write verb (4g,
        // requireActions): it drives the client's own Speedget.set (wrap-not-reimplement, D-009 → wdgmsg("set",
        // n)), exactly what clicking/hotkeying that speed does. No SpeedChanged event: speed is read on demand
        // (the classic use is a speed-toggle keybind that reads get() then sets), like the other gap surfaces.
        LuaTable speed = new LuaTable();
        speed.set("get", new ZeroArgFunction() {
            public LuaValue call() {
                Speedget s = speedget();
                return (s == null) ? LuaValue.NIL : LuaValue.valueOf(s.cur);
            }
        });
        speed.set("max", new ZeroArgFunction() {
            public LuaValue call() {
                Speedget s = speedget();
                return (s == null) ? LuaValue.NIL : LuaValue.valueOf(s.max);
            }
        });
        speed.set("name", new OneArgFunction() {
            public LuaValue call(LuaValue n) {
                int idx;
                if(n.isnumber()) {
                    idx = n.toint();
                } else {                       // no/absent arg → the current speed
                    Speedget s = speedget();
                    if(s == null)
                        return LuaValue.NIL;
                    idx = s.cur;
                }
                return speedName(idx);
            }
        });
        // set(n) — the gated write verb (4g): select movement speed n (0..3). requireActions-gated like every
        // hafen.act.* verb (D-027/D-028): only an addon that declared "actions" may call it.
        speed.set("set", new OneArgFunction() {
            public LuaValue call(LuaValue n) {
                requireActions(owner, "hafen.speed.set");
                if(!n.isnumber())
                    throw new LuaError("hafen.speed.set(n): n must be a number (0=crawl 1=walk 2=run 3=sprint)");
                actSpeedSet(n.toint());
                return LuaValue.NIL;
            }
        });
        hafen.set("speed", speed);

        // hafen.craft.* — crafting read (A8), off the crafting/recipe window (Makewindow: the widget the
        // server places under the HUD when the player opens a recipe — its input slots, output slots, the
        // quality-affecting inputs and the required tools). current() returns a snapshot of the OPEN recipe
        // {recipe, inputs, outputs, qmod, tools}, or nil when no craft window is up. inputs/outputs are spec
        // snapshots {res, name, num, opt} (res = the DISPLAYED resource's stable name — the constraint
        // category when the recipe accepts one, else the concrete item; name = its tooltip; num = the
        // required/produced count, -1 = unspecified ≈ 1; opt = an optional ingredient / chance byproduct).
        // qmod (quality-affecting inputs) and tools (required tools) are {res, name} arrays. make([all]) is the
        // GATED write verb (4g, requireActions): it presses the recipe's Craft button (all=false/absent → make
        // one, wdgmsg("make", 0)) or Craft All (all=true → wdgmsg("make", 1)) — exactly the two buttons, so it
        // CONSUMES the ingredients like a manual craft. No CraftChanged event (read on demand, like A7 speed /
        // A2 radar — a recipe changes only when the player opens/updates one).
        LuaTable craft = new LuaTable();
        craft.set("current", new ZeroArgFunction() {
            public LuaValue call() {
                return readCraft();
            }
        });
        // make([all]) — the gated write verb (4g): craft the OPEN recipe (all → Craft All). requireActions-gated
        // (D-027/D-028). all is a boolean (Lua truthiness: nil/false → one, anything else → all).
        craft.set("make", new OneArgFunction() {
            public LuaValue call(LuaValue all) {
                requireActions(owner, "hafen.craft.make");
                actCraftMake(all.toboolean());
                return LuaValue.NIL;
            }
        });
        hafen.set("craft", craft);

        // hafen.quests.* — the quest log (A9), read from the character sheet's "Quest Log" tab (QuestWnd,
        // reached via CharWnd.quest — created hidden at login but live, so quests are readable without ever
        // opening the window). list([filter]) returns quest snapshots {id, name (the quest title), res
        // (stable resource id), status ("pending"/"done"/"failed"/"disabled"), mtime (the server change
        // stamp; higher = more recent)} for BOTH the Current (active) and Completed tabs, filtered by the
        // canonical nil=all / name-substring / predicate (e.g. only-active = a predicate on status).
        // selected() returns the quest currently OPEN in the log — the only one whose conditions the client
        // loads — as a list snapshot plus conds={{desc, status ("pending"/"done"/"failed"), text?}}, or nil
        // when none is selected. Subscribe to QuestAdded (a new active quest appears) and QuestDone (an
        // active quest is completed/failed). Read-only — there is no quest action tier.
        LuaTable quests = new LuaTable();
        quests.set("list", new OneArgFunction() {
            public LuaValue call(LuaValue filter) {
                return questList(filter);
            }
        });
        quests.set("selected", new ZeroArgFunction() {
            public LuaValue call() {
                return questSelected();
            }
        });
        hafen.set("quests", quests);

        // hafen.wounds.* — the character's wounds (A9-2), read from the character sheet's "Health &
        // Wounds" tab (WoundWnd, reached via CharWnd.wound — created hidden at login but live, so wounds
        // read without ever opening the window). list([filter]) returns wound snapshots {id, name, res,
        // severity, parentid, level}: wounds form a TREE (parentid = the parent wound's id, -1 = a root
        // wound; level = the client's computed tree depth), and severity is the magnitude string the
        // client shows beside the wound (the highest-priority QuickInfo — content-defined, usually the
        // wound's number, NOT seconds; omitted while it Loads). filter is the canonical nil=all / name-
        // substring / predicate. has(needle) tests whether any wound's name/res contains needle (like
        // buffs.has). Subscribe to WoundChanged (the wound set or a severity changed; payload = the new
        // list). Read-only — there is no wound action tier (wounds heal by playing / tending).
        LuaTable wounds = new LuaTable();
        wounds.set("list", new OneArgFunction() {
            public LuaValue call(LuaValue filter) {
                return woundList(filter);
            }
        });
        wounds.set("has", new OneArgFunction() {
            public LuaValue call(LuaValue q) {
                if(!q.isstring())
                    return LuaValue.FALSE;
                String needle = q.tojstring();
                for(WoundWnd.Wound w : copyWounds()) {
                    String res = resIdent(w.res), name = woundName(w);
                    if(((res != null) && res.contains(needle)) || ((name != null) && name.contains(needle)))
                        return LuaValue.TRUE;
                }
                return LuaValue.FALSE;
            }
        });
        hafen.set("wounds", wounds);

        // hafen.fight.* — combat schools / the maneuver deck builder (A10), read from the character
        // sheet's "Martial Arts & Combat Schools" tab (FightWnd, @RName("fmg"), reached via CharWnd.fight —
        // created hidden at login but live, so it reads without opening the window). This is the OUT-OF-COMBAT
        // configuration editor (distinct from the in-combat hafen.combat.* view, which is Fightview/Fightsess
        // with live cooldowns). maneuvers([filter]) returns every combat maneuver/attack you know as {res,
        // name, avail (how many you can slot), used (how many you have slotted)}, filtered by the canonical
        // nil=all / name-substring / predicate. deck() returns the current school's configured card LAYOUT —
        // the filled key slots in order, each {slot (raw 0-based deck index), key (the hotkey label
        // "1".."5"/"⇧1".."⇧5"), res, name, used}. summary() returns the scalars {maxact (the action-point
        // budget cap), used (total points spent = sum of maneuvers' used), nact (deck size), nsave (number of
        // saved-school slots), usesave (the active saved-school slot, 0-based)}, or nil before the tab exists.
        // Read-only — editing a school / switching saved schools (load/save/use, drag cards, set counts) is
        // the gated Phase-4 action tier; no FightChanged event (a school changes only on explicit player
        // action, like A4 skills / A8 craft — read on demand). Saved-school NAMES are deferred (the private
        // FightWnd.saves[] would need a haven-package accessor; usesave/nsave identify the active slot).
        LuaTable fight = new LuaTable();
        fight.set("maneuvers", new OneArgFunction() {
            public LuaValue call(LuaValue filter) {
                return fightManeuvers(filter);
            }
        });
        fight.set("deck", new ZeroArgFunction() {
            public LuaValue call() {
                return fightDeck();
            }
        });
        fight.set("summary", new ZeroArgFunction() {
            public LuaValue call() {
                return fightSummary();
            }
        });
        hafen.set("fight", fight);

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
        // changes — a set/clear/drag or its data resolving). use(n [, mods]) is the GATED write verb (4g,
        // requireActions): activate slot n (the same raw 0-based index slot(n) reads) — exactly a LEFT-click on
        // that action-bar button (GameUI belt act → wdgmsg("belt", n, …)); mods is an optional modifier bitfield
        // (0 default; Shift=1 Ctrl=2 Alt=4, matching hafen.key). A ground-targeted ability then enters targeting
        // mode (as clicking the button does) — supply the target with the MapView verbs.
        LuaTable actionbar = new LuaTable();
        actionbar.set("slot", new OneArgFunction() {
            public LuaValue call(LuaValue n) {
                return n.isnumber() ? actionbarSlot(n.toint()) : LuaValue.NIL;
            }
        });
        // use(n [, mods]) — the gated write verb (4g): activate action-bar slot n. requireActions-gated (D-027/D-028).
        actionbar.set("use", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                requireActions(owner, "hafen.actionbar.use");
                if(!a.arg1().isnumber())
                    throw new LuaError("hafen.actionbar.use(n): n must be a number (the raw 0-based slot index 0..143)");
                actActionbarUse(a.arg1().toint(), a.arg(2).optint(0));
                return LuaValue.NIL;
            }
        });
        hafen.set("actionbar", actionbar);

        // hafen.act.* — the GATED write-actions surface (spec 12 / D-010 / D-025 / D-027; D-028), the ONLY part of
        // hafen.* that DRIVES the character: it sends player-action wdgmsgs to the server. Everything else observes;
        // this acts. A verb runs only when THIS addon declared the "actions" permission in its manifest (else
        // requireActions throws a guiding error) — a PER-ADDON permission (D-028: no global master switch; the tier
        // is always available at the system level). The user opts in per addon: a write addon is disabled by default
        // and enabling it goes through the AddOns-panel consent dialog (slice 4c), so a running addon is one the user
        // permitted. It stays server-authoritative: an addon can only send what a player click could send.
        //   enabled()   -> bool; is THIS addon allowed to act (did it declare the "actions" permission)? Reports
        //                  WITHOUT throwing, so an addon can adapt (no pcall needed).
        //   moveTo(x,y) -> walk the character to a WORLD position (the same coords hafen.gob.pos returns). This is
        //                  exactly the MapView "click" a left-click on that ground spot sends; the screen coord it
        //                  carries is a dummy (the current mouse pos), like MiniMap.mvclick when you click the
        //                  minimap to walk. Off-screen destinations are fine (the server uses the world coord).
        //   clickGob/useItemOn/place/select (4d) -> the rest of the MapView action verbs; all send a Widget.wdgmsg
        //                  from the MapView, sharing moveTo's coord encoding (world → Coord via moveClickCoord; the
        //                  dummy pc). raw(target,msg,…) is the escape hatch (send any wdgmsg from a bound widget).
        // Later Phase-4 slices add menu/flower/item + the per-subsystem gated verbs (speed.set, craft.make,
        // actionbar.use, kin.*); they all share this same gate (requireActions(owner, …)).
        LuaTable act = new LuaTable();
        act.set("enabled", new ZeroArgFunction() {
            public LuaValue call() {
                return LuaValue.valueOf(actionsGranted(owner));
            }
        });
        act.set("moveTo", new TwoArgFunction() {
            public LuaValue call(LuaValue x, LuaValue y) {
                requireActions(owner, "hafen.act.moveTo");
                if(!x.isnumber() || !y.isnumber())
                    throw new LuaError("hafen.act.moveTo(x, y): x and y must be numbers (world coordinates)");
                actMoveTo(x.todouble(), y.todouble());
                return LuaValue.NIL;
            }
        });
        // clickGob(ref [, button [, mods]]) — click a game object: exactly the MapView "click" that a
        // left/right-click on that gob sends. ref = the SAME GobRef the read API takes (a gob id, "player"/
        // "me", "partyN", or nil = you). button: 1 = left (default; select/interact), 3 = right (the context/
        // flower-menu click). mods = a modifier bitfield (0 default; Shift=1 Ctrl=2 Alt=4, matching hafen.key).
        // Sends the bare gob-click encoding {…, 0, gobid, gobrc, 0, -1} — a generic "click the whole object",
        // faithful for world objects (trees/containers/…); a specific sub-mesh / composite body part is not
        // targeted (deferred). Throws if the ref or map view is gone.
        act.set("clickGob", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                requireActions(owner, "hafen.act.clickGob");
                actClickGob(a.arg1(), a.arg(2).optint(1), a.arg(3).optint(0));
                return LuaValue.NIL;
            }
        });
        // useItemOn(x, y [, mods]) — use the item on your cursor on the GROUND at world (x, y): the MapView
        // "itemact". With nothing on the cursor the server ignores it. mods optional (0 default).
        act.set("useItemOn", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                requireActions(owner, "hafen.act.useItemOn");
                if(!a.arg1().isnumber() || !a.arg(2).isnumber())
                    throw new LuaError("hafen.act.useItemOn(x, y): x and y must be numbers (world coordinates)");
                actUseItemOn(a.arg1().todouble(), a.arg(2).todouble(), a.arg(3).optint(0));
                return LuaValue.NIL;
            }
        });
        // place(x, y, angle [, button [, mods]]) — place the object currently on your cursor at world (x, y),
        // rotated by `angle` RADIANS (the MapView "place"; the engine encodes angle as round(angle*32768/PI)).
        // With nothing being placed the server ignores it. button 1 = confirm (default); mods 0 default.
        act.set("place", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                requireActions(owner, "hafen.act.place");
                if(!a.arg1().isnumber() || !a.arg(2).isnumber() || !a.arg(3).isnumber())
                    throw new LuaError("hafen.act.place(x, y, angle): x, y and angle must be numbers");
                actPlace(a.arg1().todouble(), a.arg(2).todouble(), a.arg(3).todouble(),
                         a.arg(4).optint(1), a.arg(5).optint(0));
                return LuaValue.NIL;
            }
        });
        // select(x1, y1, x2, y2 [, mods]) — area-select the tile rectangle spanned by world corners
        // (x1,y1)–(x2,y2): the MapView "sel" (world → tile via hafen.map.worldToTile). Drives tile-area tools.
        act.set("select", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                requireActions(owner, "hafen.act.select");
                if(!a.arg1().isnumber() || !a.arg(2).isnumber() || !a.arg(3).isnumber() || !a.arg(4).isnumber())
                    throw new LuaError("hafen.act.select(x1, y1, x2, y2): all four must be numbers (world coordinates)");
                actSelect(a.arg1().todouble(), a.arg(2).todouble(), a.arg(3).todouble(), a.arg(4).todouble(),
                          a.arg(5).optint(0));
                return LuaValue.NIL;
            }
        });
        // raw(target, msg, ...) — the escape hatch: send an arbitrary wdgmsg from a BOUND widget. target = a
        // server widget id (number; e.g. model:raw() from hafen.ui.adopt, or a 3a desc.id) or a token
        // "mapview"/"gameui". The trailing args are marshalled exactly like the action/message hooks
        // (a {x=,y=} table ↔ Coord; numbers/strings/bools direct). For power users — the typed verbs above
        // cover the common cases; raw covers messages they don't.
        act.set("raw", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                requireActions(owner, "hafen.act.raw");
                actRaw(a);
                return LuaValue.NIL;
            }
        });
        // menu(path...) — invoke a menu/pagina action by its path tokens, via GameUI.act (the "act" wdgmsg
        // the action-bar menu grid sends when you click through a pagina tree; the client itself uses it,
        // e.g. act("lo","cs") = log out to character select). CAVEAT (coverage-gaps C3): paginae are
        // server-fetched and their names are content-defined / localized / versioned — this is NOT a stable
        // address space, and a path resolves only if that page is currently loaded. Some paths COMMIT real
        // actions (e.g. "lo" logs out), so the addon supplies the tokens deliberately.
        act.set("menu", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                requireActions(owner, "hafen.act.menu");
                actMenu(a);
                return LuaValue.NIL;
            }
        });
        // flower(label) — select a petal of the OPEN radial context menu (FlowerMenu) by its label (the petal
        // name, matched case-insensitively), driving the client's own FlowerMenu.choose (wrap-not-reimplement,
        // D-009: reuses the client's selection, including its client-side petals). Returns true if a matching
        // petal was chosen, false if no flower menu is open or no petal matched (never throws for those — an
        // addon can just test the result). The classic use is automation: an addon right-clicks a target
        // (clickGob button 3) and then auto-picks a petal — while a flower menu is open it grabs the mouse +
        // keyboard, so a programmatic pick (from a timer / event) is the only way to select without a click.
        act.set("flower", new OneArgFunction() {
            public LuaValue call(LuaValue label) {
                requireActions(owner, "hafen.act.flower");
                if(!label.isstring())
                    throw new LuaError("hafen.act.flower(label): label must be a string (a petal name)");
                return LuaValue.valueOf(actFlower(label.tojstring()));
            }
        });
        // item(item, verb [, n]) — the gated item verbs. `item` = an item you got from a READ: a snapshot from
        // hafen.items.* (inventory/equipment/hand/find) or model:items(), OR its numeric `handle` field directly.
        // The handle (the item's server widget id) re-resolves the LIVE GItem each call (a stale/used/moved item →
        // a guiding error, like a GobRef that no longer resolves), then sends exactly the GItem.wdgmsg a click on
        // the item sends (WItem.mousedown / iteminteract) — so the client stays server-authoritative. `verb`:
        //   "take"     pick it up onto your cursor/hand (from a container, or unequip a worn item).
        //   "drop"     drop it on the ground; `n` = how many of a stack (default -1 = the whole stack/item).
        //   "transfer" move it to the linked container (an open container / your inventory); `n` as for drop.
        //   "iact"     right-click / activate it (its default context action: eat, open, light, …).
        //   "itemact"  apply the item on your cursor ONTO this item (e.g. pour a waterskin onto a plant).
        // `n` is ignored for take/iact/itemact (no count). iact/itemact send no modifiers; for a MODIFIED item
        // interaction use the escape hatch: hafen.act.raw(item.handle, "iact", {x=0,y=0}, mods).
        act.set("item", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                requireActions(owner, "hafen.act.item");
                LuaValue verb = a.arg(2);
                if(!verb.isstring())
                    throw new LuaError("hafen.act.item(item, verb): verb must be a string"
                        + " (\"take\", \"drop\", \"transfer\", \"iact\" or \"itemact\")");
                actItem(a.arg1(), verb.tojstring(), a.arg(3).optint(-1));
                return LuaValue.NIL;
            }
        });
        hafen.set("act", act);

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

        // hafen.ghost — CLIENT-ONLY world ghosts (spec 16-virtual-entities, V1). A ghost is a virtual prop
        // rendered in the 3D world at arbitrary world coords: a Gob with NO server id, so it never reaches the
        // server and grants no gameplay advantage — a visualization, like a HUD overlay (SAFE-tier, NOT gated;
        // D-029). The motivating use is city/base planning: lay ghost buildings over the real terrain. new{...}
        // spawns one and returns a bridge-owned handle (D-030); list([filter]) returns THIS addon's live ghosts
        // (canonical filter: nil=all / a string matched against the ghost's res / a predicate over the handle).
        // Ghosts are torn down on reload/disable/relogin (P2). Coords are WORLD (login-relative), like hafen.gob.pos.
        LuaTable ghost = new LuaTable();
        // hafen.ghost.new{res, x, y [, a] [, sdt] [, alpha] [, tint] [, clickable] [, onClick]} — res = a resource
        // name (e.g. "gfx/terobjs/arch/logcabin"); x,y = world coords; a = facing radians (optional, default 0).
        //   sdt      = {bytes}        -- V3: optional spawn-data bytes (resource variant/state); rarely needed
        //   alpha    = 0.5           -- V3: opacity 0..1 (default 1 = opaque); < 1 = the translucent "ghost" look
        //   tint     = {r=,g=,b=[,a=]} -- V3: colour overlay 0..255 (a = blend strength, default 255)
        //   clickable = true         -- V2: opt-in pick-selectability (default false)
        //   onClick = fn(g,button,x,y) -- V2: fires on click (also via the GhostClicked event)
        //   follow = gob             -- ANCHOR to a gob (id / "player" / "me"): the ghost tracks it every frame
        //   offset = {x=,y=,z=}      -- fixed world offset from the followed gob (z = up)
        // Returns a handle:
        //   :move(x, y [, a])  -- reposition (+ optional facing); DETACHES any :follow anchor
        //   :rotate(a)         -- V3: set facing (radians), keeping position
        //   :setRes(res[,sdt]) -- V3: swap the visual (streams in like new)
        //   :alpha(a)          -- V3: opacity 0..1 (1 = opaque)
        //   :tint(color|nil)   -- V3: colour overlay {r=,g=,b=[,a=]} (nil clears)
        //   :show() / :hide()  -- V3: add / remove the scene slot (keeps the ghost)
        //   :follow(gob[,{x=,y=,z=}]) -- ANCHOR to a gob and auto-track it (like a gob overlay); :follow(nil) detaches
        //   :offset{x=,y=,z=}  -- move it relative to the followed gob (keeps following)
        //   :pos()             -- {x, y, a, scale [, following]} (following = the anchored gob id, if any)
        //   :res()             -- the resource name (string)
        //   :clickable(bool)   -- V2: toggle the pick surface
        //   :destroy()         -- remove now (also automatic on reload/disable)
        // The visual streams in a beat later (the resource resolves on a loader thread, dodging Loading — the
        // Plob / hafen.sound precedent), so the handle works immediately while the prop appears shortly after.
        // Returns nil only if there is no map view yet (not in the world). V2: a CLICK on a clickable ghost is
        // detected client-side and CONSUMED (no server contact ⇒ still SAFE-tier); it fires onClick + the
        // owner-scoped GhostClicked{ghost,button,x,y} event.
        ghost.set("new", new OneArgFunction() {
            public LuaValue call(LuaValue opts) {
                return newGhost(owner, opts);
            }
        });
        ghost.set("list", new OneArgFunction() {
            public LuaValue call(LuaValue filter) {
                return ghostList(owner, filter);
            }
        });
        hafen.set("ghost", ghost);

        // hafen.render — render CUSTOM assets that are NOT engine `.res` (spec 17-custom-rendering). The sibling of
        // hafen.ghost (which places `.res` game models in the world): this is for the addon's OWN files. R1 ships the
        // 2D-image loader; world sprites (R2) and glTF models (R3) join it later. Client-only ⇒ SAFE-tier, NOT gated
        // (D-034), like a HUD overlay. A `.res` file is already a PNG under the hood (Resource.Image = new TexI(
        // ImageIO.read(...))), so this just exposes that substrate directly, skipping the `.res` container.
        LuaTable render = new LuaTable();
        // hafen.render.image(path) — load a PNG (or any ImageIO-decodable image) from THIS addon's folder into a
        // cached, bridge-owned handle. `path` is addon-relative (e.g. "icon.png", "img/sign.png"); absolute paths
        // and ".." escapes are REJECTED (D-017 — an addon reads only its own assets). Repeated loads of the same
        // path return the SAME handle (one TexI per (addon, path)). Call it from setup code (OnLoad/OnEnterWorld/a
        // command), never inside a draw (v1 decodes synchronously). Returns a handle:
        //   :size()     -- {w, h} in pixels
        //   :dispose()  -- free the GPU texture now (also automatic on reload/disable/relogin, P2)
        // Draw it inside any draw callback via the `g` wrapper: g:image(img, x, y[, w, h]) / g:aimage(img, x, y,
        // ax, ay). A disposed/typo'd handle simply draws nothing (the draw verbs are forgiving).
        render.set("image", new OneArgFunction() {
            public LuaValue call(LuaValue path) {
                return newImage(owner, path);
            }
        });
        // hafen.render.sprite{image, x, y [, a] [, scale] [, alpha] [, tint] [, billboard] [, clickable] [, onClick]}
        // — stand a custom PNG in the 3D world (spec 17 §5, R2). The non-`.res` sibling of hafen.ghost, on the SAME
        // virtual-entity core + gizmo: a Gob with no server id, so it never reaches the server (SAFE-tier, NOT gated,
        // D-034). image = a hafen.render.image handle OR an addon-relative path (auto-loaded + cached, D-017-sandboxed);
        // x,y = world coords (like hafen.gob.pos); a = facing radians (default 0). Options:
        //   scale = 2               -- uniform scale (default 1); fixed = ~1 tile tall, billboard = screen-size ×
        //   alpha = 0.5             -- opacity 0..1 (default 1); combines with the PNG's own transparency
        //   tint  = {r=,g=,b=[,a=]} -- colour overlay 0..255 (a = blend strength)
        //   billboard = false       -- false (default) = a FIXED upright quad (R2a); true = a CAMERA-FACING screen blit (R2b)
        //   clickable = true        -- opt into the V2 pick (fixed sprites only; a billboard has no world mesh → never picked)
        //   onClick = fn(s,btn,x,y) -- per-sprite click callback (also delivered as the owner-scoped SpriteClicked event)
        //   follow = gob            -- ANCHOR to a gob (id / "player" / "me"): the sprite tracks it every frame
        //   offset = {x=,y=,z=}     -- fixed world offset from the followed gob (z = up; e.g. {z=10} floats it overhead)
        // Returns a transform handle (gizmo-compatible), like a ghost but with :image() in place of :res():
        //   :move(x,y[,a]) :rotate(a) :scale(s) :alpha(a) :tint(color|nil) :clickable(bool) :show() :hide() :pos() :image() :destroy()
        //   :follow(gob[, {x=,y=,z=}])  -- ANCHOR to a gob and auto-track it (like a gob overlay); :follow(nil) detaches
        //   :offset{x=,y=,z=}           -- move it relative to the followed gob (it keeps following); a plain :move detaches
        // Returns nil only if there is no map view yet (not in the world). Both forms are resource-free visuals on the
        // shared core, so they get the full transform + look + gizmo for free (a billboard ignores world-rotate/scale).
        render.set("sprite", new OneArgFunction() {
            public LuaValue call(LuaValue opts) {
                return newSprite(owner, opts);
            }
        });
        // hafen.render.model(path) — load a glTF 2.0 STATIC model (.glb preferred, or .gltf + buffers) from THIS
        // addon's folder into a cached, bridge-owned handle (spec 18-custom-models-gltf, R3). `path` is addon-relative
        // and sandboxed (absolute / ".." rejected, D-017); repeated loads of the same path return the SAME handle. The
        // mesh is parsed to baked, H&H-local geometry (Z up; 1 glTF metre = 1 tile) — POSITION + indices, multiple
        // primitives/materials, TEXCOORD_0 + baseColorTexture (R3b: embedded/data-URI/external PNG-JPG, decoded to
        // shared TexIs) × baseColorFactor, alpha modes OPAQUE/MASK/BLEND + doubleSided cull — UNLIT (normals/lighting
        // are R3c). SAFE-tier, NOT gated (D-034). Returns:
        //   :bounds()   -- {min={x,y,z}, max={x,y,z}, size={x,y,z}} in world units
        //   :info()     -- {prims, textured, textures, verts, tris} (R3b: what the parser produced)
        //   :dispose()  -- free the geometry + shared textures now (also automatic on reload/disable, P2)
        // Stand it in the world with hafen.render.object{model=…}. A model using an unsupported feature (skins,
        // animation, sparse accessors, …) raises a clear error naming it — never a crash.
        render.set("model", new OneArgFunction() {
            public LuaValue call(LuaValue path) {
                return newMesh(owner, path);
            }
        });
        // hafen.render.object{model, x, y [, a] [, scale] [, alpha] [, tint] [, clickable] [, onClick] [, follow]
        // [, offset]} — stand a custom glTF MODEL in the 3D world (spec 18, R3). The mesh sibling of a sprite/ghost,
        // on the SAME virtual-entity core + gizmo: a Gob with no server id (SAFE-tier, NOT gated, D-034). model = a
        // hafen.render.model handle OR an addon-relative path (auto-loaded + cached, D-017-sandboxed); x,y = world
        // coords (like hafen.gob.pos); a = facing radians (default 0). Options mirror hafen.render.sprite:
        //   scale = 2               -- uniform scale (default 1) on top of the baked model→world size
        //   alpha = 0.5             -- opacity 0..1 (default 1); tint = {r=,g=,b=[,a=]} colour overlay 0..255
        //   clickable = true        -- opt into the V2 pick (the mesh renders into the clickmap) → ObjectClicked / onClick
        //   follow = gob / offset = {x=,y=,z=}   -- anchor to a gob and track it every frame (like a sprite)
        // Returns a transform handle (gizmo-compatible), like a sprite but with :mesh() in place of :image():
        //   :move(x,y[,a]) :rotate(a) :scale(s) :alpha(a) :tint(color|nil) :clickable(bool) :show() :hide() :pos() :mesh() :destroy()
        //   :follow(gob[, {x=,y=,z=}])  :offset{x=,y=,z=}
        // Returns nil only if there is no map view yet (not in the world). The glTF origin maps to the gob position, so
        // author a model with its base at Y=0 to stand on the ground.
        render.set("object", new OneArgFunction() {
            public LuaValue call(LuaValue opts) {
                return newObject(owner, opts);
            }
        });
        hafen.set("render", render);

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
        //   grab{move=fn, up=fn}  (V5) — MODAL mouse-drag capture (spec 16 §2/§4, the gizmo's drag primitive). NOT a
        //       pre-hook: it captures the mouse for a press-drag-release loop. move(x, y, mods) fires on every mouse
        //       move (x,y = game-window pixels; mods = {shift,ctrl,alt}); up(x, y, button, mods) fires once on release,
        //       then the grab auto-releases. While it is active the MapView neither pans nor clicks (the drag is
        //       captured), so a gizmo drag leaves the camera put. Returns a handle { :release() } to end it early;
        //       bridge-owned, so :reload/disable releases it too. Pair with hafen.map.screenToWorld (pixel->world) +
        //       snapPlace (placegrid snapping). Returns nil if the UI is not up yet.
        hook.set("grab", new OneArgFunction() {
            public LuaValue call(LuaValue handlers) {
                return newMouseGrab(owner, handlers);
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

        // hafen.slash.register(name, fn) — a WoW-style ":name" console command routed to fn (gap subsystem A11).
        // fn(args) runs when the user types ":name a b c" in the console, with args = a 1-based table of the
        // whitespace-split arguments AFTER the name (Utils.splitwords, so "quoted words" group and \\ escapes),
        // the command name excluded. Returns a handle { :remove() }. Needs no live target — the file body is a
        // fine place to register. Reload-safe: a single engine-lifetime dispatcher routes to the current handler,
        // so editing + :reload swaps the handler with no duplicate/leaked command (coverage-gaps C1). Reserved
        // engine names (lua / addons / reload) and names a client command already owns are refused (a clear error).
        LuaTable slash = new LuaTable();
        slash.set("register", new TwoArgFunction() {
            public LuaValue call(LuaValue name, LuaValue fn) {
                return newSlashCommand(owner, name, fn);
            }
        });
        hafen.set("slash", slash);

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

    // ---------------------------------------------------------------- mouse grab + screen->world (hafen.hook.grab, V5)

    /**
     * {@code hafen.hook.grab{move=fn, up=fn}} (V5) — start a modal mouse-drag capture: a {@link LuaMouseGrab} widget
     * on {@code ui.root} that forwards mouse move/up to Lua while the grab captures the drag (so the MapView neither
     * pans nor clicks). Returns a handle {@code { :release() }}; bridge-owned for teardown. Nil if the UI is not up.
     */
    private static LuaValue newMouseGrab(final Addon owner, LuaValue handlers) {
        if(!handlers.istable())
            throw new LuaError("hafen.hook.grab{move=fn, up=fn} expects a handlers table");
        UI u = ui;
        if((u == null) || (u.root == null))
            return LuaValue.NIL;                        // no UI yet
        LuaValue mv = handlers.get("move"), up = handlers.get("up");
        final LuaMouseGrab g = new LuaMouseGrab(owner, mv.isfunction() ? mv : null, up.isfunction() ? up : null);
        owner.mouseGrabs.add(g);
        u.root.add(g);                                  // add() synchronizes on ui; visible -> receives broadcast moves
        g.arm(u);                                       // ui.grabmouse(this) — capture the terminating up wherever it lands
        LuaTable handle = new LuaTable();
        handle.set("release", new ZeroArgFunction() {
            public LuaValue call() {
                g.release();
                return LuaValue.NIL;
            }
        });
        return handle;
    }

    /** Release every active mouse grab this addon owns (teardown on reload/disable, P2). */
    private static void teardownMouseGrabs(Addon a) {
        for(LuaMouseGrab g : a.mouseGrabs)
            g.release();               // drops the UI.Grab + marks dead; the widget unlinks on its next (or the last) tick
        a.mouseGrabs.clear();
    }

    /**
     * {@code hafen.map.screenToWorld} (V5): raycast the terrain under game-window pixel {@code (px,py)} via the
     * engine's own {@link haven.MapView.Maptest} (the pass the client's building placement uses), then call {@code fn}
     * with the world {@code {x,y}} (or nil for no terrain). Asynchronous: {@code Maptest.run()} submits a GPU readback
     * and its callback fires later under {@code synchronized(ui)} (so {@link #callLua} is safe there, serialized with
     * every other addon Lua — same as the V2 ghost-click dispatch). Errors installing the test are swallowed (nil).
     */
    private static void screenToWorld(final Addon owner, MapView mv, int px, int py, final LuaValue fn) {
        final Coord pc = new Coord(px, py);
        try {
            mv.new Maptest(pc) {
                protected void hit(Coord pc, Coord2d mc) {
                    callLua(owner, fn, xy(mc.x, mc.y));
                }
                protected void nohit(Coord pc) {
                    callLua(owner, fn, LuaValue.NIL);
                }
            }.run();
        } catch(RuntimeException e) {
            /* couldn't submit the readback (e.g. no render env yet) — the caller simply gets no callback */
        }
    }

    /**
     * {@code hafen.map.snapAngle} (V6): snap a facing angle (radians) to the client's placement-angle grid — the
     * <b>absolute</b> analog of {@code MapView.StdPlace.rotate} (which is wheel-<i>relative</i>, so there is no
     * verbatim engine code to share, unlike position's {@link haven.MapView#placeSnap}). Coarse (no {@code fine}) =
     * 45° (π/4) steps; {@code fine} = the {@code :placeangle} grid (π/{@code MapView.plobagran}). Normalized to
     * (-π, π] via {@link haven.Utils#cangle}. Reads the live public {@code MapView.plobagran} so it honours
     * {@code :placeangle} with no drift — the §4.1 zero-{@code haven}-edit mirror.
     */
    private static double snapPlaceAngle(double a, boolean fine) {
        double step = fine ? (Math.PI / MapView.plobagran) : (Math.PI / 4);
        if(step <= 0)
            return Utils.cangle(a);                 // guard a pathological :placeangle (console clamps it >= 2)
        return Utils.cangle(Math.round(a / step) * step);
    }

    /** A {@code {shift,ctrl,alt}} table from {@code UI.modflags()} bits — handed to the grab callbacks (no Lua bit ops). */
    static LuaTable modsTable(int mf) {
        LuaTable t = new LuaTable();
        t.set("shift", LuaValue.valueOf((mf & UI.MOD_SHIFT) != 0));
        t.set("ctrl",  LuaValue.valueOf((mf & UI.MOD_CTRL) != 0));
        t.set("alt",   LuaValue.valueOf((mf & UI.MOD_META) != 0));   // MOD_META = Alt in this client (UI.setmods)
        return t;
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

    // ------------------------------------------------------------------ slash commands (hafen.slash, A11)

    /**
     * Register an addon slash command ({@code hafen.slash.register(name, fn)}, gap subsystem A11): route the
     * console command {@code :name} to {@code fn(args)}. Reload-safe by construction — see {@link #dispatchSlash}
     * and the {@code slashHandlers}/{@code slashDispatched} field notes (coverage-gaps C1: {@code Console} has no
     * unregister, so we install ONE engine-lifetime dispatcher per name and only ever swap the live handler).
     * Refuses a reserved engine name (lua/addons/reload) and a name a client command already owns. Returns the Lua
     * handle ({@code :remove()}). Needs no live target, so the file body / OnLoad is a fine place to call it.
     */
    private static LuaValue newSlashCommand(final Addon owner, LuaValue name, LuaValue fn) {
        if(!name.isstring() || !fn.isfunction())
            throw new LuaError("hafen.slash.register(name, fn) expects (string, function)");
        final String cmd = name.tojstring();
        if((cmd.length() == 0) || hasWhitespace(cmd))
            throw new LuaError("hafen.slash.register: name must be a non-empty word with no spaces (got '" + cmd + "')");
        if(isReservedSlash(cmd))
            throw new LuaError("hafen.slash.register: ':" + cmd + "' is a reserved engine command");
        final LuaSlashCommand h = new LuaSlashCommand(owner, cmd, fn);
        synchronized(slashDispatched) {
            if(!slashDispatched.contains(cmd)) {
                // First time we see this name: refuse if a client command already owns it (our dispatcher would
                // otherwise clobber a static command, or be silently shadowed by an instance/dir command), then
                // install the ONE engine-lifetime dispatcher that forever routes to slashHandlers.get(cmd) (C1).
                boolean exists = false;
                try {
                    UI u = ui;
                    exists = (u != null) && (u.cons != null) && (u.cons.findcmd(cmd) != null);
                } catch(RuntimeException e) {
                    /* best-effort collision check — proceed if the console can't be queried right now */
                }
                if(exists)
                    throw new LuaError("hafen.slash.register: ':" + cmd + "' is already a client command");
                Console.setscmd(cmd, new Console.Command() {
                    public void run(Console cons, String[] args) {
                        dispatchSlash(cmd, args);
                    }
                });
                slashDispatched.add(cmd);
            } else {
                // A dispatcher already exists for this name — a :reload re-register (same addon) or a takeover by a
                // different addon. If a LIVE handler owned by a different addon holds it, note the reassignment
                // (last registration wins, WoW-like); a same-owner re-register (the reload case) is silent.
                LuaSlashCommand cur = slashHandlers.get(cmd);
                if((cur != null) && cur.alive && (cur.owner != owner))
                    log("slash ':" + cmd + "' reassigned from '" + idOf(cur.owner) + "' to '" + idOf(owner) + "'");
            }
            slashHandlers.put(cmd, h);   // last registration wins (the current live handler the dispatcher routes to)
        }
        owner.slashCommands.add(h);
        LuaTable handle = new LuaTable();
        handle.set("remove", new ZeroArgFunction() {
            public LuaValue call() {
                removeSlashCommand(owner, h);
                return LuaValue.NIL;
            }
        });
        return handle;
    }

    /**
     * Run the current handler for console command {@code :name}. Installed ONCE per name (engine-lifetime) and
     * routes to {@code slashHandlers.get(name)} — the live handler — so it survives {@code :reload} with no
     * re-registration (C1). If no addon currently owns the name (torn down / disabled), it replies with a friendly
     * notice rather than the raw "no such command" (the name is still known to the console — its dispatcher
     * persists). Runs on the calling thread — the UI thread for the in-game ":" console (like tick/draw), or the
     * stdin reader thread in a terminal build (the {@code :lua} REPL's threading profile); {@code invoke} routes
     * through {@link #callLua} (watchdog-armed, error-isolated, CPU-accounted).
     */
    private static void dispatchSlash(String name, String[] words) {
        LuaSlashCommand h = slashHandlers.get(name);
        if((h == null) || !h.alive) {
            log("no addon currently handles :" + name);
            return;
        }
        h.invoke(words);
    }

    /**
     * Remove one slash command: drop the live handler (so {@code :name} stops routing to it) and drop it from the
     * owner (the handle's {@code :remove()}). The engine-lifetime {@code Console} dispatcher is deliberately KEPT
     * (C1) — it will simply report "no addon handles :name" until the name is registered again.
     */
    private static void removeSlashCommand(Addon owner, LuaSlashCommand h) {
        h.alive = false;
        slashHandlers.remove(h.name, h);   // only if h is STILL the current handler (a later addon may own it now)
        owner.slashCommands.remove(h);
    }

    /**
     * Drop every slash command this addon owns (teardown on reload/disable, P2). The {@code Console} dispatchers
     * stay installed (engine-lifetime, C1); we only clear the live handlers this addon still owns — an already
     * reassigned name (a different addon took it over) is left alone via the identity-checked map remove.
     */
    private static void teardownSlashCommands(Addon a) {
        for(LuaSlashCommand h : a.slashCommands) {
            h.alive = false;
            slashHandlers.remove(h.name, h);
        }
        a.slashCommands.clear();
    }

    /** Is {@code name} one of the addon engine's own console commands (which live in the same static map)? */
    private static boolean isReservedSlash(String name) {
        return name.equals("lua") || name.equals("addons") || name.equals("reload");
    }

    /** True if {@code s} contains any whitespace (a console command name is a single whitespace-split word). */
    private static boolean hasWhitespace(String s) {
        for(int i = 0; i < s.length(); i++) {
            if(Character.isWhitespace(s.charAt(i)))
                return true;
        }
        return false;
    }

    /** An addon's id for a log line (owner/manifest are non-null on the live paths; guarded for safety). */
    private static String idOf(Addon a) {
        return ((a != null) && (a.manifest != null)) ? a.manifest.id : "?";
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

    // ------------------------------------------------------------------ world ghosts (hafen.ghost, V1)

    /**
     * Spawn a client-only world ghost ({@code hafen.ghost.new{res, x, y [, a]}}, spec 16 / V1): validate the
     * options, register a bridge-owned {@link LuaGhost} in the addon's owned-resource registry (P2), and
     * <b>defer</b> the visual to a loader thread — {@code res.get()} throws {@code Loading} until the resource is
     * cached, so, exactly like {@code MapView.Plob} and {@link #playSound}, {@code glob.loader.defer} re-runs the
     * task when the resource lands, then builds the {@link Gob} + {@link ResDrawable} and adds it to the MapView
     * {@code basic} scene ({@link MapView#addClientGob}). The handle is returned <b>immediately</b> and works while
     * the prop streams in (a {@code :move} before the gob exists just updates the target the create applies). All
     * publish/destroy handoff is guarded by the ghost's monitor so the loader-thread create never races a
     * concurrent {@code :move}/{@code :destroy}. Returns {@code nil} if there is no map view yet (not in the world);
     * throws a {@link LuaError} for a malformed table.
     */
    private static LuaValue newGhost(final Addon owner, LuaValue opts) {
        if(!opts.istable())
            throw new LuaError("hafen.ghost.new{res=..., x=..., y=...} expects an options table");
        LuaValue resv = opts.get("res");
        if(!resv.isstring())
            throw new LuaError("hafen.ghost.new: 'res' must be a resource name string (e.g. \"gfx/terobjs/arch/logcabin\")");
        LuaValue xv = opts.get("x"), yv = opts.get("y");
        if(!xv.isnumber() || !yv.isnumber())
            throw new LuaError("hafen.ghost.new: 'x' and 'y' must be numbers (world coordinates, like hafen.gob.pos)");
        final MapView mv = view;
        final Glob g = glob();
        if((mv == null) || (g == null))
            return LuaValue.NIL;                       // not in the world yet — no scene to add to
        LuaValue av = opts.get("a");
        LuaValue clickablev = opts.get("clickable");   // V2: opt-in pick-selectability (default false)
        LuaValue onclickv = opts.get("onClick");       // V2: per-ghost click callback fn(g, button, x, y)
        final String resName = resv.tojstring();
        // remote() = the game/server resource pool (terobjs, gobs, …), with local() as a fallback for
        // client-bundled resources — the pool the engine itself uses for gob drawables (Session/Music/Widget).
        // local() alone would only find the client jar, so a terobj like gfx/terobjs/arch/logcabin never resolves.
        final Indir<Resource> resid = Resource.remote().load(resName);
        final LuaGhost gh = new LuaGhost(owner, resid, resName,
                                         new Coord2d(xv.todouble(), yv.todouble()),
                                         av.isnumber() ? av.todouble() : 0.0);
        gh.sdt = luaSdt(opts.get("sdt"));              // V3: optional spawn-data bytes (null ⇒ MessageBuf.nil)
        gh.alpha = luaAlpha(opts.get("alpha"));        // V3: opacity 0..1 (default 1 = opaque)
        gh.tint = luaTint(opts.get("tint"));           // V3: colour overlay {r=,g=,b=[,a=]}, or null
        gh.scale = luaScale(opts.get("scale"));        // V6: uniform scale (default 1 = original size)
        LuaValue gfollowv = opts.get("follow");        // ANCHOR: follow a gob (id / "player" / "me"), optional
        if(!gfollowv.isnil()) {
            gh.followTgt = followTargetId(gfollowv);
            gh.followOff = luaOffset(opts.get("offset"));   // {x=,y=,z=} world offset from the gob (default none)
        }
        gh.clickable = clickablev.toboolean();         // V2: nil/false → not clickable; true → clickable
        if(onclickv.isfunction())
            gh.onClick = onclickv;
        owner.ghosts.add(gh);
        LuaValue handle = ghostHandle(gh);
        gh.handle = handle;
        g.loader.defer(new Runnable() {
            public void run() {
                // Read the DESIRED res/sdt fresh each run so a :setRes that landed before we published is honoured
                // (and so a Loading re-run picks up a swapped resource). Guarded by the ghost monitor.
                Indir<Resource> res; Message sdt; String rnm;
                synchronized(gh) {
                    if(gh.dead)
                        return;                        // destroyed before we ran → nothing to build
                    res = gh.res; sdt = gh.sdt; rnm = gh.resName;
                }
                try {
                    res.get();                         // Loading → the loader re-runs this task when it resolves
                } catch(Loading l) {
                    throw(l);
                } catch(RuntimeException e) {
                    UI u = ui;
                    if(u != null)
                        u.error(clampMsg("addon: ghost resource '" + rnm + "' could not be loaded"));
                    synchronized(gh) { gh.failed = true; }
                    return;
                }
                // Build the gob + drawable OUTSIDE the ghost lock (no scene mutation yet), then publish atomically.
                Coord2d rc0; double a0;
                synchronized(gh) {
                    if(gh.dead) return;
                    rc0 = gh.rc; a0 = gh.a;
                }
                GhostGob gob = new GhostGob(g, rc0);      // V2/V3: a Gob subclass whose obstate adds the click surface + look
                gob.a = a0;
                gob.setattr(new ResDrawable(gob, res, (sdt == null) ? MessageBuf.nil : sdt));  // res cached now → no Loading here
                synchronized(gh) {
                    if(gh.dead) { gob.dispose(); return; }   // destroyed mid-build → discard (never added to scene)
                    gob.clickable = gh.clickable;            // V2: reflect opt-in clickability BEFORE the gob enters the scene
                    gob.alpha = gh.alpha;                    // V3: reflect the desired look before the first scene add
                    gob.tint = gh.tint;
                    gob.scale = gh.scale;                    // V6: reflect the desired scale before the first scene add
                    gob.move(gh.rc, gh.a);                   // apply any :move that landed while we were building
                    gh.gob = gob;
                    gh.mv = mv;
                    applyEntityFollow(gh, gob);              // ANCHOR: if follow= was given, start tracking the gob now
                    if(!gh.hidden)                           // V3: a ghost hidden before it published stays out of the scene
                        gh.slot = mv.addClientGob(gob);      // the // addon: MapView seam (spec 16 §6); MapView now ticks it
                }
            }
        }, null);
        return handle;
    }

    /**
     * Install the handle verbs common to EVERY client-only world entity (a {@link LuaGhost} or {@link LuaSprite}) —
     * {@code :move}/{@code :rotate}/{@code :alpha}/{@code :tint}/{@code :scale}/{@code :show}/{@code :hide}/
     * {@code :pos}/{@code :destroy} — onto the handle table {@code h}, all closing over the shared
     * {@link LuaWorldEntity} state + the {@code *Entity} scene helpers. Each subclass's handle builder
     * ({@link #ghostHandle} / {@link #spriteHandle}) calls this and then adds its own identity/extra verbs
     * ({@code :res}/{@code :setRes}/{@code :clickable} for a ghost, {@code :image} for a sprite). The colon-call
     * convention passes {@code self} as arg1, so a verb reads arg2.. and returns arg1 (the handle) for chaining;
     * every verb is a clean no-op once the entity is dead.
     */
    private static void addEntityHandle(LuaTable h, final LuaWorldEntity e) {
        h.set("move", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue xv = a.arg(2), yv = a.arg(3), av = a.arg(4);
                if(!xv.isnumber() || !yv.isnumber())
                    throw new LuaError(":move(x, y [, a]) expects world coordinates (numbers) — use a COLON call");
                synchronized(e) {
                    if(!e.dead) {
                        e.followTgt = 0;             // a manual move takes control back from any :follow anchor
                        e.rc = new Coord2d(xv.todouble(), yv.todouble());
                        if(av.isnumber())
                            e.a = av.todouble();
                        if(e.gob != null) {
                            detachFollowAttr(e.gob);  // drop the FollowMoving so the manual position sticks
                            e.gob.move(e.rc, e.a);   // live gob → reposition now; else the deferred create applies it
                        }
                    }
                }
                return a.arg1();
            }
        });
        h.set("rotate", new VarArgFunction() {          // V3: set facing (radians), keeping position — Gob.move(rc, a)
            public Varargs invoke(Varargs a) {
                LuaValue av = a.arg(2);
                if(!av.isnumber())
                    throw new LuaError(":rotate(a) expects a facing angle in radians (number) — use a COLON call");
                synchronized(e) {
                    if(!e.dead) {
                        e.a = av.todouble();
                        if(e.gob != null)
                            e.gob.move(e.rc, e.a);
                    }
                }
                return a.arg1();
            }
        });
        h.set("alpha", new VarArgFunction() {           // V3: opacity 0..1 (1 = opaque)
            public Varargs invoke(Varargs a) {
                LuaValue av = a.arg(2);
                if(!av.isnumber())
                    throw new LuaError(":alpha(a) expects a number 0..1 (1 = opaque) — use a COLON call");
                setEntityAlpha(e, clampAlpha(av.todouble()));
                return a.arg1();
            }
        });
        h.set("tint", new VarArgFunction() {            // V3: colour overlay {r=,g=,b=[,a=]}, or nil to clear
            public Varargs invoke(Varargs a) {
                LuaValue cv = a.arg(2);
                if(!cv.isnil() && !cv.istable())
                    throw new LuaError(":tint(color) expects {r=,g=,b=[,a=]} (0..255) or nil — use a COLON call");
                setEntityTint(e, cv.isnil() ? null : luaColor(cv, null));
                return a.arg1();
            }
        });
        h.set("scale", new VarArgFunction() {           // V6: uniform scale (1 = original size)
            public Varargs invoke(Varargs a) {
                LuaValue sv = a.arg(2);
                if(!sv.isnumber())
                    throw new LuaError(":scale(s) expects a positive number (1 = original size) — use a COLON call");
                setEntityScale(e, clampScale(sv.todouble()));
                return a.arg1();
            }
        });
        h.set("show", new VarArgFunction() {            // V3: (re)add the scene slot
            public Varargs invoke(Varargs a) { showEntity(e); return a.arg1(); }
        });
        h.set("hide", new VarArgFunction() {            // V3: remove the scene slot (keeps the entity)
            public Varargs invoke(Varargs a) { hideEntity(e); return a.arg1(); }
        });
        h.set("follow", new VarArgFunction() {          // ANCHOR: track a gob automatically (like a gob overlay)
            public Varargs invoke(Varargs a) {
                LuaValue ref = a.arg(2), offv = a.arg(3);
                if(ref.isnil()) {                        // :follow(nil) → detach, hold current position
                    setEntityFollow(e, 0, null);
                } else {
                    long tgt = followTargetId(ref);
                    if(tgt == 0)
                        throw new LuaError(":follow(gob [, {x=,y=,z=}]) — no such gob (pass a gob id, \"player\"/\"me\", or nil to detach)");
                    setEntityFollow(e, tgt, luaOffset(offv));
                }
                return a.arg1();
            }
        });
        h.set("offset", new VarArgFunction() {          // ANCHOR: the fixed world offset from the followed gob
            public Varargs invoke(Varargs a) {
                LuaValue ov = a.arg(2);
                if(!ov.isnil() && !ov.istable())
                    throw new LuaError(":offset{x=,y=,z=} expects a table of world-unit offsets (or nil to clear) — use a COLON call");
                setEntityOffset(e, ov.isnil() ? null : luaOffset(ov));
                return a.arg1();
            }
        });
        h.set("pos", new ZeroArgFunction() {
            public LuaValue call() {
                LuaTable t = new LuaTable();
                synchronized(e) {
                    Coord2d rc = entityWorldPos(e);      // the LIVE position (the followed gob's, while anchored)
                    t.set("x", LuaValue.valueOf(rc.x));
                    t.set("y", LuaValue.valueOf(rc.y));
                    t.set("a", LuaValue.valueOf(e.a));
                    t.set("scale", LuaValue.valueOf((double)e.scale));   // V6: the full transform is {x,y,a,scale}
                    if(e.followTgt != 0)
                        t.set("following", LuaValue.valueOf((double)e.followTgt));   // the anchored gob id, if any
                }
                return t;
            }
        });
        h.set("destroy", new VarArgFunction() {
            public Varargs invoke(Varargs a) { destroyEntity(e); return a.arg1(); }
        });
    }

    /**
     * The Lua handle for a {@link LuaGhost} (V1 + V2 + V3): the shared entity verbs ({@link #addEntityHandle}) plus
     * the ghost-only {@code :res()} / {@code :setRes(res[,sdt])} (V3, swap the {@code .res} model) and
     * {@code :clickable(bool)} (V2 — pick-selectability, which needs the ghost-scoped V2 click dispatch). A
     * mutation before the deferred create has published the gob updates the desired-state the create will apply;
     * afterwards it acts on the live gob.
     */
    private static LuaValue ghostHandle(final LuaGhost gh) {
        LuaTable h = new LuaTable();
        addEntityHandle(h, gh);
        h.set("setRes", new VarArgFunction() {          // V3: swap the visual (streams in like new)
            public Varargs invoke(Varargs a) {
                LuaValue resv = a.arg(2), sdtv = a.arg(3);
                if(!resv.isstring())
                    throw new LuaError("ghost:setRes(res [, sdt]) expects a resource name string — use a COLON call");
                setGhostRes(gh, resv.tojstring(), luaSdt(sdtv));
                return a.arg1();
            }
        });
        h.set("res", new ZeroArgFunction() {
            public LuaValue call() { return (gh.resName == null) ? LuaValue.NIL : LuaValue.valueOf(gh.resName); }
        });
        h.set("clickable", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                setEntityClickable(gh, a.arg(2).toboolean());   // g:clickable(true|false); default (no arg) → false
                return a.arg1();
            }
        });
        return h;
    }

    /**
     * {@code hafen.ghost.list([filter])} — this addon's live ghosts as an array of their (stable) handles.
     * Canonical filter adapted to handles: {@code nil} = all; a <b>string</b> = substring match on the ghost's
     * {@code res} name; a <b>function</b> = called with the ghost <i>handle</i> (so it can call {@code :pos()}
     * etc.), truthy keeps it (errors drop it). Dead/failed ghosts are skipped.
     */
    private static LuaValue ghostList(Addon owner, LuaValue filter) {
        LuaTable out = new LuaTable();
        int i = 0;
        for(LuaGhost gh : owner.ghosts) {              // copy-on-write: a filter fn may create/destroy a ghost
            if(gh.dead || (gh.handle == null))
                continue;
            if(entityMatches(filter, gh))
                out.set(++i, gh.handle);
        }
        return out;
    }

    /**
     * Does world entity {@code e} pass {@code filter}? The canonical filter adapted to handles: {@code nil} = all;
     * a <b>string</b> = substring match on the entity's {@link LuaWorldEntity#visualName()} (a ghost's {@code res}
     * name / a sprite's image path); a <b>function</b> = called with the entity <i>handle</i> (so it can call
     * {@code :pos()} etc.), truthy keeps it (errors drop it). Shared by ghost and (future) sprite listings.
     */
    private static boolean entityMatches(LuaValue filter, LuaWorldEntity e) {
        if((filter == null) || filter.isnil())
            return true;
        if(filter.isfunction()) {
            try {
                return filter.call(e.handle).toboolean();
            } catch(RuntimeException ex) {   // LuaError is a RuntimeException
                return false;
            }
        }
        if(filter.isstring()) {
            String nm = e.visualName();
            return (nm != null) && nm.contains(filter.tojstring());
        }
        return true;
    }

    /**
     * Destroy one world entity now (its {@code :destroy()}, and teardown): flip {@link LuaWorldEntity#dead} + hand
     * off the slot/gob under the entity's monitor (so a still-pending deferred create sees {@code dead} and discards
     * its un-added gob instead of leaking it), then remove the scene slot ({@link MapView#removeClientGob}, which
     * swallows {@code SlotRemoved} for an already-torn-down scene) and dispose the gob's visual — all OUTSIDE the
     * entity lock (no lock-ordering with the render tree's own lock). {@link LuaWorldEntity#unregister()} drops it
     * from its addon's registry (ghosts / sprites). Shared by ghosts and sprites. Idempotent.
     */
    private static void destroyEntity(LuaWorldEntity e) {
        RenderTree.Slot slot; Gob gob; MapView mv;
        synchronized(e) {
            if(e.dead)
                return;
            e.dead = true;
            slot = e.slot; e.slot = null;
            gob  = e.gob;  e.gob  = null;
            mv   = e.mv;   e.mv   = null;
        }
        e.unregister();
        if(mv != null) {
            mv.removeClientGob(gob, slot);            // drops it from the MapView tick list + removes the slot (swallows SlotRemoved)
        } else if(slot != null) {
            try { slot.remove(); } catch(RuntimeException ex) { /* scene already gone (relog) */ }
        }
        if(gob != null) {
            try { gob.dispose(); } catch(RuntimeException ex) { /* best-effort: free the visual */ }
        }
    }

    /** Tear down every ghost this addon owns (reload/disable/relogin, P2): destroy each (slot removed + visual freed). */
    private static void teardownGhosts(Addon a) {
        if(a.ghosts.isEmpty())
            return;
        for(LuaGhost gh : new ArrayList<LuaGhost>(a.ghosts))
            destroyEntity(gh);          // removes each from a.ghosts as it goes (copy-on-write list)
    }

    /** Tear down every sprite this addon owns (reload/disable/relogin, P2): destroy each (slot removed + quad freed). */
    private static void teardownSprites(Addon a) {
        if(a.sprites.isEmpty())
            return;
        for(LuaSprite sp : new ArrayList<LuaSprite>(a.sprites))
            destroyEntity(sp);          // removes each from a.sprites as it goes (copy-on-write list)
    }

    // ---- R1: custom images (hafen.render.image) --------------------------------------------------------------

    /**
     * Resolve an addon-relative asset path to a filesystem {@link Path} <b>inside</b> the addon's own folder,
     * rejecting absolute paths and {@code ..} escapes (D-017 — an addon reads only its own assets). {@code ctx}
     * names the caller in the error text. After {@code normalize()}, both an absolute path and a {@code ..} that
     * climbs out of the folder fail the containment check (they no longer start with the folder), while an
     * internal {@code a/../b} is allowed. Never returns a path outside {@link Addon#dir}.
     */
    private static Path resolveAddonAsset(Addon owner, String name, String ctx) {
        if((name == null) || name.isEmpty())
            throw new LuaError(ctx + ": path must be a non-empty string (addon-relative, e.g. \"icon.png\")");
        Path base = owner.dir.toAbsolutePath().normalize();
        Path p;
        try {
            p = base.resolve(name).normalize();
        } catch(RuntimeException e) {                 // InvalidPathException — a malformed name
            throw new LuaError(ctx + ": invalid path '" + name + "'");
        }
        if(!p.startsWith(base))                        // absolute, or a ".." that climbs out → rejected
            throw new LuaError(ctx + ": path '" + name + "' escapes the addon folder (absolute paths and '..' are not allowed)");
        return p;
    }

    /**
     * {@code hafen.render.image(path)} (R1): load a PNG (or any {@code ImageIO}-decodable image) from the addon's
     * own folder into a cached, bridge-owned {@link LuaImage} handle. Rejects a non-string / out-of-folder path
     * (D-017); decodes <b>synchronously</b> on the UI thread (small local assets — spec 17 §3) via {@code ImageIO}
     * → {@link TexI}; a repeated load of the same path returns the <b>same</b> handle (one {@code TexI} per
     * {@code (addon, path)}). A decode failure raises a clear {@link LuaError}.
     */
    private static LuaValue newImage(Addon owner, LuaValue pathv) {
        if(!pathv.isstring())
            throw new LuaError("hafen.render.image(path) expects a string (an addon-relative file name, e.g. \"icon.png\")");
        String name = pathv.tojstring();
        for(LuaImage ex : owner.images) {              // cache: one handle per (addon, path)
            if(!ex.dead && name.equals(ex.name) && (ex.handle != null))
                return ex.handle;
        }
        Path p = resolveAddonAsset(owner, name, "hafen.render.image");
        BufferedImage img;
        try {
            img = ImageIO.read(p.toFile());
        } catch(IOException | RuntimeException e) {
            throw new LuaError("hafen.render.image: could not read '" + name + "': " + e.getMessage());
        }
        if(img == null)
            throw new LuaError("hafen.render.image: '" + name + "' is not a decodable image (PNG/JPG/GIF/BMP)");
        LuaImage li = new LuaImage(owner, name, new TexI(img));
        owner.images.add(li);
        LuaValue handle = imageHandle(li);
        li.handle = handle;
        return handle;
    }

    /**
     * The Lua handle for a {@link LuaImage} (R1): {@code :size()} → {@code {w,h}} and {@code :dispose()}. The
     * table also carries the {@link LuaImage} as an <b>opaque userdata</b> (its {@link LuaImage#KEY} field) so
     * {@code g:image}/{@code g:aimage} can {@link LuaImage#resolve} it back to the texture — facade-safe (no Java
     * method is reachable from Lua; the userdata has no metatable and cannot be forged without {@code luajava}).
     */
    private static LuaValue imageHandle(final LuaImage li) {
        LuaTable h = new LuaTable();
        h.set(LuaImage.KEY, LuaValue.userdataOf(li));  // opaque backing ref for g:image / g:aimage
        h.set("size", new ZeroArgFunction() {
            public LuaValue call() {
                LuaTable t = new LuaTable();
                t.set("w", LuaValue.valueOf(li.sz.x));
                t.set("h", LuaValue.valueOf(li.sz.y));
                return t;
            }
        });
        h.set("dispose", new VarArgFunction() {
            public Varargs invoke(Varargs a) { disposeImage(li); return a.arg1(); }
        });
        return h;
    }

    /**
     * Free one image now (its {@code :dispose()}, and teardown): flip {@link LuaImage#dead} (so an in-flight
     * {@code g:image} on the draw thread no-ops instead of re-uploading the texture via {@code TexI.st()}), drop
     * it from the addon's registry, and dispose the {@link TexI} (frees the GL texture). Idempotent.
     */
    private static void disposeImage(LuaImage li) {
        if(li.dead)
            return;
        li.dead = true;
        li.owner.images.remove(li);
        try {
            li.tex.dispose();
        } catch(RuntimeException e) { /* best-effort: free the GL texture */ }
    }

    /** Dispose every image this addon owns (reload/disable/relogin, P2): frees each {@code TexI}'s GL texture. */
    private static void teardownImages(Addon a) {
        if(a.images.isEmpty())
            return;
        for(LuaImage li : new ArrayList<LuaImage>(a.images))
            disposeImage(li);          // removes each from a.images as it goes (copy-on-write list)
    }

    // ---- R3: custom 3D models (hafen.render.model / hafen.render.object) ----------------------------------------

    /**
     * {@code hafen.render.model(path)} (R3a): load a glTF 2.0 <b>static</b> model ({@code .glb} preferred, or
     * {@code .gltf} + buffers) from the addon's own folder into a cached, bridge-owned {@link LuaMesh} handle.
     * Rejects a non-string / out-of-folder path (D-017); parses <b>synchronously</b> on the UI thread (small local
     * assets — spec 17 §3) via {@link Gltf} into baked, H&amp;H-local geometry; a repeated load of the same path
     * returns the <b>same</b> handle (one parse per {@code (addon, path)}). External {@code .gltf} buffer/image URIs
     * are resolved <b>relative to the model file</b> and re-sandboxed to the addon folder. A parse failure (malformed
     * data, or an unsupported feature named by {@link Gltf}) raises a clear {@link LuaError}.
     */
    private static LuaValue newMesh(Addon owner, LuaValue pathv) {
        if(!pathv.isstring())
            throw new LuaError("hafen.render.model(path) expects a string (an addon-relative file name, e.g. \"chair.glb\")");
        String name = pathv.tojstring();
        for(LuaMesh ex : owner.meshes) {               // cache: one parse per (addon, path)
            if(!ex.dead && name.equals(ex.name) && (ex.handle != null))
                return ex.handle;
        }
        Path p = resolveAddonAsset(owner, name, "hafen.render.model");
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(p);
        } catch(IOException | RuntimeException e) {
            throw new LuaError("hafen.render.model: could not read '" + name + "': " + e.getMessage());
        }
        final Path base = owner.dir.toAbsolutePath().normalize();
        final Path parent = p.getParent();             // external URIs resolve relative to the model file...
        Gltf.Loader loader = new Gltf.Loader() {
            public byte[] read(String uri) throws Exception {
                Path q = parent.resolve(uri).normalize();
                if(!q.startsWith(base))                // ...but never escape the addon folder (D-017)
                    throw new IOException("external asset '" + uri + "' escapes the addon folder");
                return Files.readAllBytes(q);
            }
        };
        Gltf mesh;
        try {
            mesh = Gltf.parse(bytes, name, loader);
        } catch(RuntimeException e) {
            throw new LuaError("hafen.render.model: " + e.getMessage());
        }
        TexI[] textures = buildMeshTextures(mesh, name);   // R3b: decode the shared base-colour textures (owned by the mesh)
        LuaMesh lm = new LuaMesh(owner, name, mesh, textures);
        owner.meshes.add(lm);
        LuaValue handle = meshHandle(lm);
        lm.handle = handle;
        return handle;
    }

    /**
     * Decode a parsed model's referenced texture image blobs ({@link Gltf#images}) into shared {@link TexI}s (R3b) —
     * the same {@code ImageIO} → {@code TexI} substrate as {@code hafen.render.image}, but built with <b>no
     * power-of-two rounding</b> ({@code new TexI(img, false)}) so glTF {@code [0,1]} UVs sample the whole image
     * regardless of its dimensions (NPOT-safe). One {@code TexI} per glTF image (already deduped by {@link Gltf}); the
     * {@link LuaMesh} owns them and frees them in {@link #disposeMesh}. A blob that fails to decode raises a clear
     * {@link LuaError} naming the image (never a crash). Empty array for an untextured model.
     */
    private static TexI[] buildMeshTextures(Gltf mesh, String name) {
        int n = mesh.images.size();
        TexI[] out = new TexI[n];
        for(int i = 0; i < n; i++) {
            Gltf.Image im = mesh.images.get(i);
            String kind = (im.mime != null) ? im.mime : "unknown type";
            BufferedImage bi;
            try {
                bi = ImageIO.read(new ByteArrayInputStream(im.bytes));
            } catch(IOException | RuntimeException e) {
                throw new LuaError("hafen.render.model: could not decode texture image " + i + " (" + kind + ") in '" + name + "': " + e.getMessage());
            }
            if(bi == null)
                throw new LuaError("hafen.render.model: texture image " + i + " (" + kind + ") in '" + name + "' is not a decodable image (PNG/JPG/GIF/BMP)");
            out[i] = new TexI(bi, false);   // no POT rounding → [0,1] glTF UVs map to the full image (NPOT-safe)
        }
        return out;
    }

    /**
     * The Lua handle for a {@link LuaMesh} (R3): {@code :bounds()} → {@code {min={x,y,z}, max={x,y,z},
     * size={x,y,z}}} (world units) and {@code :dispose()}. The table also carries the {@link LuaMesh} as an
     * <b>opaque userdata</b> ({@link LuaMesh#KEY}) so {@code hafen.render.object{model=…}} can {@link LuaMesh#resolve}
     * it back to the parsed geometry — facade-safe (no Java method reachable from Lua; unforgeable without {@code luajava}).
     */
    private static LuaValue meshHandle(final LuaMesh lm) {
        LuaTable h = new LuaTable();
        h.set(LuaMesh.KEY, LuaValue.userdataOf(lm));   // opaque backing ref for hafen.render.object
        h.set("bounds", new ZeroArgFunction() {
            public LuaValue call() {
                LuaTable t = new LuaTable();
                t.set("min", vec3Table(lm.mesh.min));
                t.set("max", vec3Table(lm.mesh.max));
                t.set("size", vec3Table(new float[] {
                    lm.mesh.max[0] - lm.mesh.min[0], lm.mesh.max[1] - lm.mesh.min[1], lm.mesh.max[2] - lm.mesh.min[2] }));
                return t;
            }
        });
        // :info() → a small summary of what the parser produced (R3b): primitive/texture/triangle counts. Useful for
        // an addon (or the hello harness) to confirm a model loaded textured, and for logging.
        h.set("info", new ZeroArgFunction() {
            public LuaValue call() {
                int textured = 0, lit = 0;
                for(Gltf.Prim p : lm.mesh.prims) {
                    if(p.textured()) textured++;
                    if(p.nrm != null) lit++;                         // R3c: primitives shaded by the world lights
                }
                LuaTable t = new LuaTable();
                t.set("prims", LuaValue.valueOf(lm.mesh.prims.size()));
                t.set("textured", LuaValue.valueOf(textured));       // primitives with a base-colour texture
                t.set("lit", LuaValue.valueOf(lit));                 // primitives with normals → Phong-lit (R3c)
                t.set("textures", LuaValue.valueOf(lm.textures.length));   // distinct decoded texture images
                t.set("verts", LuaValue.valueOf((double)lm.mesh.nvert));
                t.set("tris", LuaValue.valueOf((double)lm.mesh.ntri));
                return t;
            }
        });
        h.set("dispose", new VarArgFunction() {
            public Varargs invoke(Varargs a) { disposeMesh(lm); return a.arg1(); }
        });
        return h;
    }

    /** A {@code {x,y,z}} Lua table from a 3-float array (mesh bounds). */
    private static LuaTable vec3Table(float[] v) {
        LuaTable t = new LuaTable();
        t.set("x", LuaValue.valueOf((double)v[0]));
        t.set("y", LuaValue.valueOf((double)v[1]));
        t.set("z", LuaValue.valueOf((double)v[2]));
        return t;
    }

    /**
     * Free one model now (its {@code :dispose()}, and teardown): flip {@link LuaMesh#dead} (so a later
     * {@code render.object} refuses it), drop it from the addon's registry, and (R3b) dispose the mesh's <b>shared
     * base-colour textures</b> (the first GPU state a mesh owns). Each {@link LuaObject} owns its own engine
     * {@code Model}s (freed by {@code teardownObjects}, which runs first), so at teardown a live object never
     * references a freed texture; a manual {@code mesh:dispose()} while an object still draws it does free the
     * textures out from under it (dispose only when unused — see {@link LuaMesh}). Idempotent.
     */
    private static void disposeMesh(LuaMesh lm) {
        if(lm.dead)
            return;
        lm.dead = true;
        lm.owner.meshes.remove(lm);
        for(TexI t : lm.textures) {                   // R3b: free the shared base-colour textures
            if(t != null) {
                try { t.dispose(); } catch(RuntimeException e) { /* best-effort: free the GL texture */ }
            }
        }
    }

    /** Dispose every model this addon owns (reload/disable/relogin, P2). Objects are torn down first ({@link #teardownObjects}). */
    private static void teardownMeshes(Addon a) {
        if(a.meshes.isEmpty())
            return;
        for(LuaMesh lm : new ArrayList<LuaMesh>(a.meshes))
            disposeMesh(lm);           // removes each from a.meshes as it goes (copy-on-write list)
    }

    /**
     * {@code hafen.render.object{model, x, y [, a] [, scale] [, alpha] [, tint] [, clickable] [, onClick] [, follow]
     * [, offset]}} (R3a): stand a custom glTF model in the 3D world — the mesh sibling of a sprite/ghost, on the same
     * virtual-entity core (spec 18 §3). Validates the options, resolves the {@code model} (a {@code hafen.render.model}
     * handle or an addon-relative path, auto-loaded + cached), builds a {@link GhostGob}, attaches a {@link MeshSprite}
     * ({@code SprDrawable}), and adds it to the MapView {@code basic} scene ({@link MapView#addClientGob}). Everything
     * else — transform, look, {@code follow} anchor, {@code clickable}/{@code onClick}, gizmo — is shared with sprites.
     * Because the geometry is already decoded ({@link Gltf}), there is NO {@code Loading} to dodge — the gob is built
     * and published <b>synchronously</b> on the calling UI thread (mirrors {@link #newSprite}). Registered in the
     * addon's owned-resource registry (P2). Returns {@code nil} if there is no map view (not in the world); throws a
     * {@link LuaError} for a malformed table / a bad {@code model}.
     */
    private static LuaValue newObject(Addon owner, LuaValue opts) {
        if(!opts.istable())
            throw new LuaError("hafen.render.object{model=..., x=..., y=...} expects an options table");
        LuaValue xv = opts.get("x"), yv = opts.get("y");
        LuaValue followv = opts.get("follow");
        boolean hasFollow = !followv.isnil();
        if(!hasFollow && (!xv.isnumber() || !yv.isnumber()))
            throw new LuaError("hafen.render.object: 'x' and 'y' must be numbers (world coordinates, like hafen.gob.pos) — or pass follow=gob instead");
        final MapView mv = view;
        final Glob g = glob();
        if((mv == null) || (g == null))
            return LuaValue.NIL;                       // not in the world yet — no scene to add to
        LuaMesh mesh = resolveObjectMesh(owner, opts.get("model"));   // AFTER the world check (don't parse when not in world)
        LuaValue av = opts.get("a");
        double a = av.isnumber() ? av.todouble() : 0.0;
        Coord2d rc = new Coord2d(xv.optdouble(0.0), yv.optdouble(0.0));   // 0,0 placeholder when following
        LuaObject ob = new LuaObject(owner, mesh, rc, a);
        ob.alpha = luaAlpha(opts.get("alpha"));
        ob.tint = luaTint(opts.get("tint"));
        ob.scale = luaScale(opts.get("scale"));        // uniform scale on top of the baked model→world size
        ob.clickable = opts.get("clickable").toboolean();
        LuaValue onclickv = opts.get("onClick");
        if(onclickv.isfunction())
            ob.onClick = onclickv;
        if(hasFollow) {
            ob.followTgt = followTargetId(followv);
            ob.followOff = luaOffset(opts.get("offset"));
        }
        owner.objects.add(ob);
        LuaValue handle = objectHandle(ob);
        ob.handle = handle;
        // Build the gob + visual, then publish atomically. No defer: the glTF geometry is already parsed (R3), so
        // nothing here throws Loading. The MeshSprite adds one Model per primitive; the shared core supplies
        // transform/look/gizmo, exactly like a sprite's quad.
        GhostGob gob = new GhostGob(g, rc);
        gob.a = a;
        gob.alpha = ob.alpha; gob.tint = ob.tint; gob.scale = ob.scale;   // reflect the look before the first scene add
        gob.clickable = ob.clickable;                  // a clickable object's mesh renders into the clickmap → V2-pickable
        gob.setattr(new SprDrawable(gob, MeshSprite.mill(mesh)));    // resource-free glTF-model visual (R3b: shared textures + per-material states)
        gob.move(rc, a);
        synchronized(ob) {
            if(ob.dead) { gob.dispose(); return handle; }   // destroyed mid-build (defensive; all UI-thread)
            ob.gob = gob;
            ob.mv = mv;
            applyEntityFollow(ob, gob);                 // if follow= was given, start tracking the gob now
            if(!ob.hidden)
                ob.slot = mv.addClientGob(gob);         // the // addon: MapView seam (spec 16 §6); MapView now ticks it
        }
        return handle;
    }

    /**
     * The Lua handle for a {@link LuaObject} (R3): the shared entity verbs ({@link #addEntityHandle}) plus the
     * object's {@code :mesh()} identity accessor (its addon-relative model path) and {@code :clickable(bool)} (the
     * V2 pick surface, mirroring a sprite/ghost). No {@code :setRes}/{@code :setModel} — an object's mesh is fixed at create (R3a).
     */
    private static LuaValue objectHandle(final LuaObject ob) {
        LuaTable h = new LuaTable();
        addEntityHandle(h, ob);
        h.set("mesh", new ZeroArgFunction() {
            public LuaValue call() { return (ob.meshName == null) ? LuaValue.NIL : LuaValue.valueOf(ob.meshName); }
        });
        h.set("clickable", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                setEntityClickable(ob, a.arg(2).toboolean());   // o:clickable(true|false); default (no arg) → false
                return a.arg1();
            }
        });
        return h;
    }

    /**
     * Resolve the object {@code model=} option to a live {@link LuaMesh}: a {@code hafen.render.model} handle (or its
     * raw backing userdata), or an addon-relative <b>path</b> string (auto-loaded + cached from the addon's own
     * folder, D-017-sandboxed, via {@link #newMesh}). Throws a {@link LuaError} for anything else / a disposed model.
     */
    private static LuaMesh resolveObjectMesh(Addon owner, LuaValue modelv) {
        LuaMesh lm = modelv.isstring() ? LuaMesh.resolve(newMesh(owner, modelv))   // load+cache from the addon folder
                                       : LuaMesh.resolve(modelv);                   // a handle or its raw userdata
        if((lm == null) || lm.dead)
            throw new LuaError("hafen.render.object: 'model' must be a hafen.render.model handle or an addon-relative path string");
        return lm;
    }

    /** Tear down every object this addon owns (reload/disable/relogin, P2): destroy each (slot removed + Models freed). */
    private static void teardownObjects(Addon a) {
        if(a.objects.isEmpty())
            return;
        for(LuaObject ob : new ArrayList<LuaObject>(a.objects))
            destroyEntity(ob);          // removes each from a.objects as it goes (copy-on-write list)
    }

    // ---- R2: custom world sprites (hafen.render.sprite) --------------------------------------------------------

    /**
     * {@code hafen.render.sprite{image, x, y [, a] [, scale] [, alpha] [, tint] [, billboard] [, clickable] [, onClick]}}
     * (R2): stand a custom PNG in the 3D world — the non-{@code .res} sibling of a ghost, on the same virtual-entity
     * core (spec 17 §5). Validates the options, resolves the {@code image} (a {@code hafen.render.image} handle or an
     * addon-relative path, auto-loaded + cached), builds a {@link GhostGob}, attaches the visual, and adds it to the
     * MapView {@code basic} scene ({@link MapView#addClientGob}). The visual is the ONLY thing {@code billboard}
     * selects: {@code false} (default) → a resource-free {@link SprDrawable} quad ({@link SpriteQuad}) standing
     * upright, sized to the image aspect (R2a); {@code true} → a resource-free {@link LuaSpriteBillboard} camera-facing
     * screen blit (R2b). Everything else — transform, look, {@code follow} anchor, gizmo — is shared. Unlike a ghost
     * the texture is already decoded, so there is NO {@code Loading} to dodge — the gob is built and published
     * <b>synchronously</b> on the calling UI thread (the handle's gob is live before it is returned). {@code clickable}
     * (+ per-sprite {@code onClick}) opts a <b>fixed</b> sprite into the V2 pick dispatch (its quad renders into the
     * clickmap); a <b>billboard</b> has no world mesh so it is never picked (the flag is a harmless no-op). Registered
     * in the addon's owned-resource registry (P2). Returns {@code nil} if there is no map view (not in the world);
     * throws a {@link LuaError} for a malformed table.
     */
    private static LuaValue newSprite(Addon owner, LuaValue opts) {
        if(!opts.istable())
            throw new LuaError("hafen.render.sprite{image=..., x=..., y=...} expects an options table");
        LuaValue xv = opts.get("x"), yv = opts.get("y");
        LuaValue followv = opts.get("follow");         // ANCHOR: follow a gob (id / "player" / "me"), optional
        boolean hasFollow = !followv.isnil();
        if(!hasFollow && (!xv.isnumber() || !yv.isnumber()))   // x/y are the placement; when following, the gob supplies it
            throw new LuaError("hafen.render.sprite: 'x' and 'y' must be numbers (world coordinates, like hafen.gob.pos) — or pass follow=gob instead");
        boolean billboard = opts.get("billboard").toboolean();   // R2b: true = camera-facing screen blit; false = fixed world quad
        final MapView mv = view;
        final Glob g = glob();
        if((mv == null) || (g == null))
            return LuaValue.NIL;                       // not in the world yet — no scene to add to
        LuaImage img = resolveSpriteImage(owner, opts.get("image"));   // AFTER the world check (don't load when not in world)
        LuaValue av = opts.get("a");
        double a = av.isnumber() ? av.todouble() : 0.0;
        Coord2d rc = new Coord2d(xv.optdouble(0.0), yv.optdouble(0.0));   // 0,0 placeholder when following (the gob overrides)
        LuaSprite sp = new LuaSprite(owner, img, rc, a, billboard);
        sp.alpha = luaAlpha(opts.get("alpha"));        // opacity 0..1 (default 1); combines with the PNG's own alpha
        sp.tint = luaTint(opts.get("tint"));           // colour overlay {r=,g=,b=[,a=]}, or null
        sp.scale = luaScale(opts.get("scale"));        // uniform scale (fixed quad: ~1 tile tall; billboard: screen-size ×)
        sp.clickable = opts.get("clickable").toboolean();   // R2b: opt-in pick (fixed sprites only; a billboard has no world mesh → never picked)
        LuaValue onclickv = opts.get("onClick");       // R2b: per-sprite click callback fn(s, button, x, y) — like a ghost
        if(onclickv.isfunction())
            sp.onClick = onclickv;
        if(hasFollow) {                                // ANCHOR: track a gob every frame (the gob-overlay analog)
            sp.followTgt = followTargetId(followv);
            sp.followOff = luaOffset(opts.get("offset"));   // {x=,y=,z=} world offset from the gob (default none)
        }
        owner.sprites.add(sp);
        LuaValue handle = spriteHandle(sp);
        sp.handle = handle;
        // Build the gob + visual OUTSIDE the sprite lock (no scene mutation yet), then publish atomically. No defer:
        // the TexI is already decoded (R1), so nothing here throws Loading. The visual is the ONLY thing that differs
        // between a fixed quad (SpriteQuad, a resource-free SprDrawable) and a camera-facing billboard
        // (LuaSpriteBillboard, a resource-free Drawable+Render2D) — the shared core supplies transform/look/gizmo.
        GhostGob gob = new GhostGob(g, rc);
        gob.a = a;
        gob.alpha = sp.alpha; gob.tint = sp.tint; gob.scale = sp.scale;   // reflect the look before the first scene add
        gob.clickable = sp.clickable;                  // R2b: a fixed sprite's quad renders into the clickmap → V2-pickable (see onGhostClick)
        if(billboard) {
            gob.setattr(new LuaSpriteBillboard(gob, img));               // camera-facing screen blit (reads the look live each frame)
        } else {
            float[] wh = spriteWorldDims(img.sz);
            gob.setattr(new SprDrawable(gob, SpriteQuad.mill(img.tex, wh[0], wh[1])));   // resource-free textured quad
        }
        gob.move(rc, a);
        synchronized(sp) {
            if(sp.dead) { gob.dispose(); return handle; }   // destroyed mid-build (defensive; all UI-thread) → discard
            sp.gob = gob;
            sp.mv = mv;
            applyEntityFollow(sp, gob);                 // ANCHOR: if follow= was given, start tracking the gob now
            if(!sp.hidden)                              // a sprite hidden before it published stays out of the scene
                sp.slot = mv.addClientGob(gob);         // the // addon: MapView seam (spec 16 §6); MapView now ticks it
        }
        return handle;
    }

    /**
     * The Lua handle for a {@link LuaSprite} (R2): the shared entity verbs ({@link #addEntityHandle}) plus the
     * sprite's {@code :image()} identity accessor (its addon-relative path) and {@code :clickable(bool)} (R2b — the
     * V2 pick surface, mirroring a ghost). {@code :clickable} works on a <b>fixed</b> sprite (its quad renders into
     * the clickmap); on a billboard it is a harmless no-op (no world mesh to pick). No {@code :setRes} — a sprite's
     * visual (its image) is fixed at create.
     */
    private static LuaValue spriteHandle(final LuaSprite sp) {
        LuaTable h = new LuaTable();
        addEntityHandle(h, sp);
        h.set("image", new ZeroArgFunction() {
            public LuaValue call() { return (sp.imgName == null) ? LuaValue.NIL : LuaValue.valueOf(sp.imgName); }
        });
        h.set("clickable", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                setEntityClickable(sp, a.arg(2).toboolean());   // s:clickable(true|false); default (no arg) → false
                return a.arg1();
            }
        });
        return h;
    }

    /**
     * Resolve the sprite {@code image=} option to a live {@link LuaImage}: a {@code hafen.render.image} handle (or
     * its raw backing userdata), or an addon-relative <b>path</b> string (auto-loaded + cached from the addon's own
     * folder, D-017-sandboxed, via {@link #newImage}). Throws a {@link LuaError} for anything else / a disposed image.
     */
    private static LuaImage resolveSpriteImage(Addon owner, LuaValue imgv) {
        LuaImage li = imgv.isstring() ? LuaImage.resolve(newImage(owner, imgv))   // load+cache from the addon folder
                                      : LuaImage.resolve(imgv);                    // a handle or its raw userdata
        if((li == null) || li.dead)
            throw new LuaError("hafen.render.sprite: 'image' must be a hafen.render.image handle or an addon-relative path string");
        return li;
    }

    /**
     * The world size {@code {w, h}} in map units of a fixed sprite, aspect-preserved from the image's pixel size:
     * the height is one tile ({@link MCache#tilesz}) and the width follows the image aspect, so the sprite stands
     * ~1 tile tall at {@code scale=1}; the uniform {@code :scale} (obstate) then adjusts both. A degenerate (zero)
     * pixel dimension falls back to a square tile. Pure — headless-testable.
     */
    private static float[] spriteWorldDims(Coord isz) {
        float base = (float)MCache.tilesz.y;               // ≈ 1 tile tall at scale 1
        float w = ((isz != null) && (isz.x > 0) && (isz.y > 0)) ? base * ((float)isz.x / (float)isz.y) : base;
        return new float[] { w, base };
    }

    /**
     * Toggle an entity's pick surface ({@code g:clickable(bool)}, V2 — ghosts only in R2a). The MapView click-list
     * decides membership <b>at slot-add time</b> — a later ancestor-state change does <i>not</i> re-run its
     * {@code Clickable} filter — so a live entity is toggled by removing and re-adding it to the scene
     * ({@link #refreshEntityScene}), where {@link GhostGob#obstate} is applied fresh and reads the updated
     * {@link GhostGob#clickable}. An entity whose deferred create has not published its gob yet just records the
     * desired state (the create applies it before the gob enters the scene). No-op when unchanged/dead.
     */
    private static void setEntityClickable(LuaWorldEntity e, boolean on) {
        synchronized(e) {
            if(e.dead || (e.clickable == on))
                return;
            e.clickable = on;
            if(e.gob instanceof GhostGob)
                ((GhostGob)e.gob).clickable = on;      // read by obstate on the next scene (re)add
            refreshEntityScene(e);
        }
    }

    /**
     * Set an entity's opacity ({@code g:alpha(a)}, V3): {@code 1} = opaque (no extra render state), {@code < 1} =
     * translucent. Like {@link #setEntityClickable}, the change is applied by re-adding the scene slot (obstate's
     * output is not part of {@code GobState.equals}, so the normal update path won't re-apply it). No-op if
     * unchanged/dead. Under the entity monitor.
     */
    private static void setEntityAlpha(LuaWorldEntity e, float alpha) {
        synchronized(e) {
            if(e.dead || (e.alpha == alpha))
                return;
            e.alpha = alpha;
            if(e.gob instanceof GhostGob)
                ((GhostGob)e.gob).alpha = alpha;       // read by obstate on the next scene (re)add
            refreshEntityScene(e);
        }
    }

    /**
     * Set an entity's colour-overlay tint ({@code g:tint(color)}, V3); {@code null} clears it. Applied by re-adding
     * the scene slot, like {@link #setEntityAlpha}. Under the entity monitor.
     */
    private static void setEntityTint(LuaWorldEntity e, java.awt.Color tint) {
        synchronized(e) {
            if(e.dead)
                return;
            e.tint = tint;
            if(e.gob instanceof GhostGob)
                ((GhostGob)e.gob).tint = tint;         // read by obstate on the next scene (re)add
            refreshEntityScene(e);
        }
    }

    /**
     * Set an entity's uniform scale ({@code g:scale(s)}, V6): {@code 1} = original size. Applied by re-adding the
     * scene slot like {@link #setEntityAlpha} (obstate's scaling {@code Location} is not part of
     * {@code GobState.equals}, so the normal update path won't re-apply it). No-op if unchanged/dead. Under the
     * entity monitor.
     */
    private static void setEntityScale(LuaWorldEntity e, float scale) {
        synchronized(e) {
            if(e.dead || (e.scale == scale))
                return;
            e.scale = scale;
            if(e.gob instanceof GhostGob)
                ((GhostGob)e.gob).scale = scale;       // read by obstate on the next scene (re)add
            refreshEntityScene(e);
        }
    }

    /**
     * Remove the entity from the scene ({@code g:hide()}, V3) — drops the scene slot (so it stops rendering/ticking)
     * but <b>keeps</b> the gob so {@code :show()} can re-add it. Marks {@link LuaWorldEntity#hidden} so a hide that
     * lands before the deferred create published keeps the prop out of the scene. No-op if already hidden/dead.
     * Under the entity monitor.
     */
    private static void hideEntity(LuaWorldEntity e) {
        synchronized(e) {
            if(e.dead || e.hidden)
                return;
            e.hidden = true;
            if((e.gob != null) && (e.mv != null) && (e.slot != null)) {
                try { e.mv.removeClientGob(e.gob, e.slot); }
                catch(RuntimeException ex) { /* scene gone (relog) — flag set, no scene op */ }
                e.slot = null;
            }
        }
    }

    /**
     * (Re)add the entity to the scene ({@code g:show()}, V3) — the inverse of {@link #hideEntity}. No-op if not
     * hidden/dead. Under the entity monitor.
     */
    private static void showEntity(LuaWorldEntity e) {
        synchronized(e) {
            if(e.dead || !e.hidden)
                return;
            e.hidden = false;
            if((e.gob != null) && (e.mv != null) && (e.slot == null)) {
                try {
                    e.slot = e.mv.addClientGob(e.gob);
                    e.gob.move(e.rc, e.a);             // re-assert position/facing after the re-add
                } catch(RuntimeException ex) {
                    /* scene gone (relog) — flag cleared, no scene op */
                }
            }
        }
    }

    /**
     * Swap a ghost's visual ({@code g:setRes(res[,sdt])}, V3). Records the new desired res/sdt (so a still-pending
     * create uses them) and, if the gob is already live, <b>defers</b> building the new {@link ResDrawable} —
     * {@code res.get()} throws {@code Loading} until cached, the same reason {@code new} defers — then {@code setattr}s
     * it on the gob under {@code synchronized(gob)} (the exact lock the engine's own live res-swap {@code $cres.apply}
     * holds; the gob's {@code slots} list is a plain {@code ArrayList} shared with the {@code ctick} path). A newer
     * {@code :setRes} that swapped {@code gh.res} in the meantime wins — this task drops its stale drawable.
     */
    private static void setGhostRes(final LuaGhost gh, final String resName, final MessageBuf sdt) {
        final Glob g = glob();
        final Indir<Resource> rid = Resource.remote().load(resName);
        boolean live;
        synchronized(gh) {
            if(gh.dead)
                return;
            gh.res = rid; gh.resName = resName; gh.sdt = sdt;
            gh.failed = false;
            live = (gh.gob != null);
        }
        if(!live || (g == null))
            return;                                    // no live gob yet → the pending create will use the new res
        g.loader.defer(new Runnable() {
            public void run() {
                Indir<Resource> res; Message sd; GhostGob gob; String nm;
                synchronized(gh) {
                    if(gh.dead)
                        return;
                    gob = (gh.gob instanceof GhostGob) ? (GhostGob)gh.gob : null;
                    res = gh.res; sd = gh.sdt; nm = gh.resName;
                }
                if((gob == null) || (res != rid))
                    return;                            // gob gone, or a newer :setRes superseded this one
                try {
                    res.get();                         // Loading → the loader re-runs when it resolves
                } catch(Loading l) {
                    throw(l);
                } catch(RuntimeException e) {
                    UI u = ui;
                    if(u != null)
                        u.error(clampMsg("addon: ghost resource '" + nm + "' could not be loaded"));
                    return;                            // keep the old visual (non-fatal)
                }
                ResDrawable dr = new ResDrawable(gob, res, (sd == null) ? MessageBuf.nil : sd);  // built outside the lock
                synchronized(gh) {
                    if(gh.dead || (gh.gob != gob) || (gh.res != res)) { dr.dispose(); return; }
                    synchronized(gob) {
                        gob.setattr(dr);               // swaps the Drawable attrib; old sprite's slots removed, new added
                    }
                }
            }
        }, null);
    }

    /**
     * Re-apply a live entity's render state (clickable/alpha/tint/scale) by removing and re-adding its scene slot so
     * {@link GhostGob#obstate} runs fresh — needed because neither the click-list membership nor obstate's colour
     * output propagates through the normal {@code Gob.updated()}/{@code updstate()} path. No-op if the gob is not
     * currently in the scene (pending create / hidden) — obstate reads the updated fields when it is (re)added.
     * <b>Caller must hold the entity monitor</b> (same entity→tree lock order the deferred create uses; no new hazard).
     */
    private static void refreshEntityScene(LuaWorldEntity e) {
        if((e.gob != null) && (e.mv != null) && (e.slot != null)) {
            try {
                e.mv.removeClientGob(e.gob, e.slot);
                e.slot = e.mv.addClientGob(e.gob);
                e.gob.move(e.rc, e.a);                 // re-assert position/facing after the re-add
            } catch(RuntimeException ex) {
                /* the entity's scene is gone (e.g. a REPL entity changed after a relog) — fields set, no scene op */
            }
        }
    }

    // ---- ANCHOR: follow a gob (hafen.render.sprite / hafen.ghost :follow) — the world-space gob-overlay analog ---

    /**
     * Anchor an entity to a target gob ({@code :follow(gob[, offset])}) or detach it ({@code tgt == 0}). While
     * anchored, a {@link FollowMoving} on the gob makes the render tree place the entity at the target's live
     * position + {@code off} <b>every frame</b> — no Lua polling (the {@code hafen.ui.gobOverlay} analog for a
     * world entity). Detaching freezes it at the current followed position. Records the desired state so a
     * still-pending (deferred ghost) create attaches it on publish ({@link #applyEntityFollow}). Under the entity
     * monitor; the attrib attach/detach is done under {@code synchronized(gob)} (the lock the live res-swap uses).
     */
    private static void setEntityFollow(LuaWorldEntity e, long tgt, Coord3f off) {
        synchronized(e) {
            if(e.dead)
                return;
            e.followTgt = tgt;
            e.followOff = off;
            Gob gob = e.gob;
            if(gob == null)
                return;                                  // no live gob yet → the pending create applies the follow
            synchronized(gob) {
                if(tgt != 0) {
                    gob.setattr(new FollowMoving(gob, tgt, off));   // start following — autotick picks it up next frame
                } else {
                    Moving m = gob.getattr(Moving.class);
                    if(m instanceof FollowMoving) {                 // detach: freeze at the last followed point
                        Coord3f cur;
                        try { cur = gob.getc(); } catch(RuntimeException ex) { cur = null; }
                        gob.delattr(Moving.class);
                        if(cur != null)
                            e.rc = new Coord2d(cur.x, cur.y);        // hold there (ground z re-derived at placement)
                        gob.move(e.rc, e.a);
                    }
                }
            }
        }
    }

    /**
     * Update the fixed world offset of an anchored entity ({@code :offset{x=,y=,z=}}). Live on the
     * {@link FollowMoving} (its {@code off} is {@code volatile}) → takes effect next frame with no re-attach; stored
     * on the entity for a still-pending create too. This is the "move it relative to the gob while it keeps
     * following" verb (a manual {@code :move} would instead detach). No-op if dead. Under the entity monitor.
     */
    private static void setEntityOffset(LuaWorldEntity e, Coord3f off) {
        synchronized(e) {
            if(e.dead)
                return;
            e.followOff = off;
            if(e.gob != null) {
                Moving m = e.gob.getattr(Moving.class);
                if(m instanceof FollowMoving)
                    ((FollowMoving)m).off = off;
            }
        }
    }

    /** Drop any {@link FollowMoving} from {@code gob} (a manual {@code :move} detaches the follow). Under {@code synchronized(gob)}. */
    private static void detachFollowAttr(Gob gob) {
        synchronized(gob) {
            if(gob.getattr(Moving.class) instanceof FollowMoving)
                gob.delattr(Moving.class);
        }
    }

    /**
     * Attach the {@link FollowMoving} at (deferred/immediate) create time when the entity is anchored — called by
     * {@code newSprite}/{@code newGhost} once the gob is built (before it enters the scene), so a {@code :follow}
     * that landed before the visual streamed in is honoured. Caller holds the entity monitor; the fresh gob is not
     * yet published, so no {@code synchronized(gob)} is needed.
     */
    private static void applyEntityFollow(LuaWorldEntity e, Gob gob) {
        if(e.followTgt != 0)
            gob.setattr(new FollowMoving(gob, e.followTgt, e.followOff));
    }

    /**
     * Resolve a {@code :follow} / {@code follow=} target to a gob id: a raw <b>number</b> is used as-is (the gob
     * need not be loaded yet — {@link FollowMoving} re-resolves each frame), a token ({@code "player"}/{@code "me"}/
     * {@code "partyN"}/an id string) goes through the read-API {@link #resolve}. Returns {@code 0} if unresolvable.
     */
    private static long followTargetId(LuaValue ref) {
        if(ref.isnumber())
            return (long)ref.todouble();
        Gob g = resolve(ref);
        return (g == null) ? 0L : g.id;
    }

    /** Parse a {@code {x=,y=,z=}} world-offset table → a {@link Coord3f} (missing components 0), or {@code null} (not a table). */
    private static Coord3f luaOffset(LuaValue v) {
        if((v == null) || !v.istable())
            return null;
        float x = (float)v.get("x").optdouble(0.0);
        float y = (float)v.get("y").optdouble(0.0);
        float z = (float)v.get("z").optdouble(0.0);
        return new Coord3f(x, y, z);
    }

    /**
     * The entity's LIVE world position (a {@link Coord2d}) for {@code :pos()}: while anchored, the followed gob's
     * current position (+ offset) via {@code gob.getc()}; otherwise the entity's own {@code rc}. Caller holds the
     * entity monitor.
     */
    private static Coord2d entityWorldPos(LuaWorldEntity e) {
        if((e.followTgt != 0) && (e.gob != null)) {
            try {
                Coord3f c = e.gob.getc();
                if(c != null)
                    return new Coord2d(c.x, c.y);
            } catch(RuntimeException ex) { /* placement still loading → fall back to rc */ }
        }
        return e.rc;
    }

    /** Parse a ghost {@code alpha} option/arg → clamped 0..1; a non-number defaults to 1 (opaque). */
    private static float luaAlpha(LuaValue v) {
        return v.isnumber() ? clampAlpha(v.todouble()) : 1f;
    }
    private static float clampAlpha(double a) {
        return (a < 0.0) ? 0f : ((a > 1.0) ? 1f : (float)a);
    }

    /** Parse a ghost {@code scale} option/arg → clamped positive (0.01..100); a non-number defaults to 1 (original size). */
    private static float luaScale(LuaValue v) {
        return v.isnumber() ? clampScale(v.todouble()) : 1f;
    }
    private static float clampScale(double s) {
        return (s < 0.01) ? 0.01f : ((s > 100.0) ? 100f : (float)s);   // never 0/negative (would collapse/invert the mesh)
    }

    /** Parse a ghost {@code tint} option/arg → a {@link java.awt.Color}, or {@code null} for none (nil / not a table). */
    private static java.awt.Color luaTint(LuaValue v) {
        return (v == null) || !v.istable() ? null : luaColor(v, null);
    }

    /**
     * Parse a ghost {@code sdt} option/arg → a {@link MessageBuf} of raw bytes, or {@code null} (⇒ {@code MessageBuf.nil})
     * when omitted. Accepts a 1-based Lua array of byte values (0..255); a non-table is treated as "none".
     */
    private static MessageBuf luaSdt(LuaValue v) {
        if((v == null) || !v.istable())
            return null;
        int n = v.length();
        byte[] b = new byte[n];
        for(int i = 0; i < n; i++)
            b[i] = (byte)(v.get(i + 1).toint() & 0xff);
        return new MessageBuf(b);
    }

    /**
     * V2: {@code MapView.Click.hit} resolved a click to virtual gob {@code cg}, BEFORE its {@code wdgmsg("click",
     * …)}. If {@code cg} is a <b>clickable</b> client world entity — a ghost <i>or</i> a fixed {@link LuaSprite}
     * (R2b generalized the dispatch beyond ghosts) — fire its owner-scoped click event ({@code GhostClicked{ghost,
     * …}} / {@code SpriteClicked{sprite, …}}, keyed by {@link LuaWorldEntity#clickEvent()}/{@link
     * LuaWorldEntity#clickKey()}; owner-scoped because the handle is private to its addon, not a global {@link #fire})
     * and its per-entity {@code onClick(handle, button, x, y)}, then return {@code true} so the caller CONSUMES the
     * click — no {@code wdgmsg}, so nothing reaches the server (client-only ⇒ still SAFE-tier, D-032). Returns {@code
     * false} for any non-entity / non-clickable gob, so a normal click proceeds. (A billboard sprite has no world
     * mesh, so it never renders into the clickmap and never reaches here — only fixed sprites are pickable.) {@code x,
     * y} = the world coord the click resolved to. Reached under {@code synchronized(ui)} (like the L3 message hook),
     * so {@link #callLua} is safe with no extra thread guard; the entity lock is released before dispatch so a handler
     * may re-entrantly {@code :destroy()}/{@code :move()} it.
     */
    public static boolean onGhostClick(Gob cg, int button, Coord2d mc) {
        if(cg == null)
            return false;
        LuaWorldEntity e = findEntityByGob(cg);
        if(e == null)
            return false;
        LuaValue handle, onClick;
        synchronized(e) {
            if(e.dead || !e.clickable)
                return false;
            handle  = e.handle;
            onClick = e.onClick;
        }
        if(handle == null)
            return false;
        LuaValue bt = LuaValue.valueOf(button);
        LuaValue xv = LuaValue.valueOf((mc == null) ? 0 : mc.x);
        LuaValue yv = LuaValue.valueOf((mc == null) ? 0 : mc.y);
        LuaTable ev = new LuaTable();
        ev.set(e.clickKey(), handle);                  // "ghost" / "sprite" (constant per subclass; no lock needed)
        ev.set("button", bt);
        ev.set("x", xv);
        ev.set("y", yv);
        fireTo(e.owner, e.clickEvent(), ev);           // owner-scoped: an entity belongs to exactly one addon
        if((onClick != null) && onClick.isfunction())
            callLua(e.owner, onClick, handle, bt, xv, yv);
        return true;                                   // consume — client-only detection, no server wdgmsg
    }

    /** Find the live world entity (ghost or sprite) whose gob is {@code cg}, across all addons + the REPL owner (V2 click dispatch). */
    private static LuaWorldEntity findEntityByGob(Gob cg) {
        for(Addon a : addons) {
            LuaWorldEntity e = findEntityIn(a, cg);
            if(e != null)
                return e;
        }
        Addon c = consoleOwner;
        return (c == null) ? null : findEntityIn(c, cg);
    }

    private static LuaWorldEntity findEntityIn(Addon a, Gob cg) {
        for(LuaGhost gh : a.ghosts) {
            Gob g;
            synchronized(gh) { g = gh.dead ? null : gh.gob; }
            if(g == cg)
                return gh;
        }
        for(LuaSprite sp : a.sprites) {
            Gob g;
            synchronized(sp) { g = sp.dead ? null : sp.gob; }
            if(g == cg)
                return sp;
        }
        for(LuaObject ob : a.objects) {           // R3: a clickable glTF object is pickable too (its mesh is in the clickmap)
            Gob g;
            synchronized(ob) { g = ob.dead ? null : ob.gob; }
            if(g == cg)
                return ob;
        }
        return null;
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

    /** The in-game notice sink ({@link UI#msg}/{@link UI#error}) renders a line as ONE text texture; a very
     *  long single line (a big compact-JSON REPL result, or an addon logging a large value) can exceed the
     *  GL max texture size and crash the render thread (GL_INVALID_VALUE, 1281). Clamp what we hand it — the
     *  full text always goes to stdout, and addons receive the real Lua value regardless. */
    static final int NOTICE_MAX = 500;
    static String clampMsg(String s) {
        if((s == null) || (s.length() <= NOTICE_MAX))
            return s;
        return s.substring(0, NOTICE_MAX) + "... (" + s.length() + " chars; full output on the terminal)";
    }

    /** Engine-level output: stdout (prefixed) and in-game notice when available. */
    static void log(String msg) {
        System.out.println("[addon] " + msg);
        UI u = ui;
        if(u != null) {
            try {
                u.msg(clampMsg(msg));
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
                u.msg(clampMsg(id + ": " + msg));
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
                System.out.println("[console] " + out);            // full result to the terminal...
                if(u != null)
                    u.msg(clampMsg(out));                          // ...clamped in-game (a huge one-line result crashes the text renderer)
            }
        } catch(LuaError e) {
            String err = "lua: " + e.getMessage();
            System.out.println("[console] " + err);
            if(u != null)
                u.error(clampMsg(err));
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
     *
     * <p>{@code handle} is the item's <b>server widget id</b> ({@link GItem#wdgid()}): the stable, facade-safe
     * {@code ItemRef} (principle P1 — just an int) that the gated {@code hafen.act.item(item, verb)} verb (4f)
     * takes to re-resolve the live {@link GItem} and drive it. It is a live reference on an otherwise
     * point-in-time snapshot (the other fields are a copy, like {@code hafen.gob.info}) — the only way to
     * address an item, since items carry no other stable id (D-022: handle-only). Omitted for an unbound item.
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
        int handle = it.wdgid();
        if(handle >= 0)
            t.set("handle", LuaValue.valueOf(handle));   // server widget id → the ItemRef hafen.act.item(item, verb) takes (4f)
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

    /**
     * Display name from a resource's tooltip layer, else {@code fallback} (which may be null).
     * Loading-guarded (returns {@code fallback} while the resource is still resolving). Shared by the
     * skill / credo / experience reads — all name themselves off the resource tooltip.
     */
    private static String resTipName(Indir<Resource> res, String fallback) {
        try {
            Resource r = res.get();
            if(r != null) {
                Resource.Tooltip tt = r.layer(Resource.tooltip);
                if((tt != null) && (tt.t != null))
                    return tt.t;
            }
        } catch(RuntimeException e) {   // Loading etc.
        }
        return fallback;
    }

    /** Stable resource identity (its {@code name}), or {@code null} (Loading-guarded). */
    private static String resIdent(Indir<Resource> res) {
        try {
            Resource r = res.get();
            return (r == null) ? null : r.name;
        } catch(RuntimeException e) {
            return null;
        }
    }

    /** Display name of a skill: the resource tooltip, else the internal skill token ({@code Skill.nm}). */
    private static String skillName(SkillWnd.Skill s) {
        return resTipName(s.res, s.nm);
    }

    /** Resource name (stable identity) of a skill, or {@code null} (Loading-guarded). */
    private static String skillRes(SkillWnd.Skill s) {
        return resIdent(s.res);
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

    /**
     * The character's AVAILABLE (buyable) skills ({@code SkillWnd.skg.nsk}) as {@code {name, res, cost}}
     * snapshots — {@code cost} = the LP price to learn. The counterpart to {@link #readSkills()} (known
     * skills). Same discipline: {@code name} always present, {@code res} Loading-guarded, the list copied
     * defensively (the {@code Group.items} reference is swapped wholesale off-thread by the {@code nsk} uimsg).
     */
    private static LuaValue readAvailableSkills() {
        LuaTable out = new LuaTable();
        SkillWnd w = skillwnd();
        if(w == null)
            return out;
        int i = 0;
        try {
            for(SkillWnd.Skill s : new ArrayList<SkillWnd.Skill>(w.skg.nsk.items)) {
                LuaTable t = new LuaTable();
                t.set("name", LuaValue.valueOf(skillName(s)));
                String res = skillRes(s);
                if(res != null)
                    t.set("res", LuaValue.valueOf(res));
                t.set("cost", LuaValue.valueOf(s.cost));
                out.set(++i, t);
            }
        } catch(RuntimeException e) {
            /* nsk not ready or the list was swapped mid-read — return what we have */
        }
        return out;
    }

    /** A credo snapshot {@code {name, res}} — name from the resource tooltip (else the {@code Credo.nm} token). */
    private static LuaValue credoSnapshot(SkillWnd.Credo c) {
        LuaTable t = new LuaTable();
        t.set("name", LuaValue.valueOf(resTipName(c.res, c.nm)));
        String res = resIdent(c.res);
        if(res != null)
            t.set("res", LuaValue.valueOf(res));
        return t;
    }

    /** A list of credos as {@code {name,res}} snapshots (defensively copied — swapped off-thread). */
    private static LuaValue readCredoList(List<SkillWnd.Credo> list) {
        LuaTable out = new LuaTable();
        int i = 0;
        for(SkillWnd.Credo c : new ArrayList<SkillWnd.Credo>(list))
            out.set(++i, credoSnapshot(c));
        return out;
    }

    /**
     * The Credos tab ({@code SkillWnd.credos}): {@code acquired}/{@code available} (arrays of
     * {@code {name,res}}), the currently-pursued credo under {@code pursuing} ({@code {name,res,level,
     * levelTotal,quest,questTotal,questId}}, absent when none), and {@code cost} (LP to begin pursuing a
     * new credo). nil until the "Lore &amp; Skills" window exists (it streams in after enter-world).
     */
    private static LuaValue readCredos() {
        SkillWnd w = skillwnd();
        if(w == null)
            return LuaValue.NIL;
        SkillWnd.CredoGrid cg = w.credos;
        LuaTable out = new LuaTable();
        try {
            out.set("acquired", readCredoList(cg.ccr));
            out.set("available", readCredoList(cg.ncr));
            out.set("cost", LuaValue.valueOf(cg.cost));
            SkillWnd.Credo p = cg.pcr;
            if(p != null) {
                LuaTable pt = new LuaTable();
                pt.set("name", LuaValue.valueOf(resTipName(p.res, p.nm)));
                String res = resIdent(p.res);
                if(res != null)
                    pt.set("res", LuaValue.valueOf(res));
                pt.set("level",      LuaValue.valueOf(cg.pcl));
                pt.set("levelTotal", LuaValue.valueOf(cg.pclt));
                pt.set("quest",      LuaValue.valueOf(cg.pcql));
                pt.set("questTotal", LuaValue.valueOf(cg.pcqlt));
                pt.set("questId",    LuaValue.valueOf(cg.pqid));
                out.set("pursuing", pt);
            }
        } catch(RuntimeException e) {
            /* credo lists swapped mid-read — return the partial table */
        }
        return out;
    }

    /**
     * An experience/lore snapshot {@code {name, res, score, mtime}} from the Lore tab. An {@code Experience}
     * has no internal token, so {@code name} is the resource tooltip (else the resource path, else absent);
     * {@code score} = experience points, {@code mtime} = the server-supplied time field (faithful passthrough).
     */
    private static LuaValue experienceSnapshot(SkillWnd.Experience e) {
        LuaTable t = new LuaTable();
        String nm = resTipName(e.res, resIdent(e.res));
        if(nm != null)
            t.set("name", LuaValue.valueOf(nm));
        String res = resIdent(e.res);
        if(res != null)
            t.set("res", LuaValue.valueOf(res));
        t.set("score", LuaValue.valueOf(e.score));
        t.set("mtime", LuaValue.valueOf(e.mtime));
        return t;
    }

    /**
     * The character's seen experiences / lore ({@code SkillWnd.exps.seen}) as {@code {name,res,score,mtime}}
     * snapshots (the "Lore" tab). Defensively copied (swapped off-thread by the {@code exps} uimsg).
     */
    private static LuaValue readExperiences() {
        LuaTable out = new LuaTable();
        SkillWnd w = skillwnd();
        if(w == null)
            return out;
        int i = 0;
        try {
            for(SkillWnd.Experience e : new ArrayList<SkillWnd.Experience>(w.exps.seen.items))
                out.set(++i, experienceSnapshot(e));
        } catch(RuntimeException ex) {
            /* seen not ready or swapped mid-read — return what we have */
        }
        return out;
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
     * {@code hafen.actionbar.use} backing (4g, gated) — activate action-bar slot {@code n} (the raw 0-based
     * index) with modifier bitfield {@code mods}. Drives the client's own belt {@code act(idx, Interaction)} —
     * exactly what a LEFT-click on that button does ({@code GameUI.Belt.mousedown} b==1) → {@code wdgmsg("belt",
     * n, …)} — so a ground-targeted ability enters targeting mode just as clicking it would. Wrap-not-reimplement
     * (D-009). Throws for an out-of-range or empty slot, or before the HUD exists.
     */
    private static void actActionbarUse(int n, int mods) {
        GameUI g = gui();
        if(g == null)
            throw new LuaError("hafen.actionbar.use: no game UI (not in the world yet)");
        if((g.belt == null) || (n < 0) || (n >= g.belt.length))
            throw new LuaError("hafen.actionbar.use(n): slot index out of range (0..143), got " + n);
        if(g.belt[n] == null)
            throw new LuaError("hafen.actionbar.use: slot " + n + " is empty (read hafen.actionbar.slot(n) first)");
        if(g.beltwdg == null)
            throw new LuaError("hafen.actionbar.use: no action-bar widget yet");
        g.beltwdg.act(n, new MenuGrid.Interaction(1, mods));   // button 1 (left) — the on-screen slot click
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

    // ---- kin / buddy (A6: hafen.kin) -------------------------------------------------------------
    // The kin/buddy roster lives in the BuddyWnd (GameUI.buddies) — the same widget the in-client Kin tab
    // shows. Its Buddy list is mutated on the network/loader thread as the server pushes add/rm/chst/upd
    // uimsgs; BuddyWnd.iterator() copies the list under its own lock, so iterating it is snapshot-safe.
    // All our reads run on the UI thread (addon tick / REPL). online is a tri-state internally (1 online,
    // 0 offline, -1 hearth-secret-only) that we expose as a boolean (online == 1) — the common "is this
    // kin online" question; the group index maps to a fixed colour palette (BuddyWnd.gc).

    /** The Kin/buddy window ({@link GameUI#buddies}), or {@code null} before the HUD/Kin window exists. */
    private static BuddyWnd buddywnd() {
        GameUI g = gui();
        return (g == null) ? null : g.buddies;
    }

    /** A kin snapshot: {@code {id, name, group, color={r,g,b,a}, online(bool)}}. */
    private static LuaValue kinSnapshot(BuddyWnd.Buddy b) {
        if(b == null)
            return LuaValue.NIL;
        LuaTable t = new LuaTable();
        t.set("id", LuaValue.valueOf(b.id));
        if(b.name != null)
            t.set("name", LuaValue.valueOf(b.name));
        t.set("group", LuaValue.valueOf(b.group));
        if((b.group >= 0) && (b.group < BuddyWnd.gc.length))
            t.set("color", color(BuddyWnd.gc[b.group]));
        t.set("online", LuaValue.valueOf(b.online == 1));
        return t;
    }

    /** Kin snapshots in the window's current sort order, passing the canonical {@code matches} filter. */
    private static LuaValue kinList(LuaValue filter) {
        LuaTable out = new LuaTable();
        BuddyWnd bw = buddywnd();
        if(bw == null)
            return out;
        int i = 0;
        for(BuddyWnd.Buddy b : bw) {               // iterator() copies under the BuddyWnd's own lock
            LuaValue snap = kinSnapshot(b);
            if(matches(filter, snap))
                out.set(++i, snap);
        }
        return out;
    }

    /** One kin snapshot: a number matches by id, a string by exact (case-insensitive) name; else nil. */
    private static LuaValue kinFind(LuaValue key) {
        BuddyWnd bw = buddywnd();
        if(bw == null)
            return LuaValue.NIL;
        if(key.isnumber())
            return kinSnapshot(bw.find(key.toint()));
        if(key.isstring()) {
            String needle = key.tojstring();
            for(BuddyWnd.Buddy b : bw) {
                if((b.name != null) && b.name.equalsIgnoreCase(needle))
                    return kinSnapshot(b);
            }
        }
        return LuaValue.NIL;
    }

    /** Do two kin snapshot lists carry the same id/name/group/online per entry? (change-detection.) */
    private static boolean kinListEqual(LuaValue a, LuaValue b) {
        if((a == null) || (b == null) || !a.istable() || !b.istable())
            return a == b;
        int n = a.length();
        if(n != b.length())
            return false;
        for(int i = 1; i <= n; i++) {
            LuaValue ea = a.get(i), eb = b.get(i);
            if(ea.get("id").toint() != eb.get("id").toint())
                return false;
            if(!ea.get("name").tojstring().equals(eb.get("name").tojstring()))
                return false;
            if(ea.get("group").toint() != eb.get("group").toint())
                return false;
            if(ea.get("online").toboolean() != eb.get("online").toboolean())
                return false;
        }
        return true;
    }

    // -- 4g: kin write verbs (hafen.kin.add/remove/forget/rename/setGroup) ---------------------------
    // The gated kin-roster mutations. add(secret) adds a kin by the other player's HEARTH SECRET — the Kin
    // window's "Add kin" field, BuddyWnd.wdgmsg("bypwd", secret) (no add-by-NAME message exists). The rest take a
    // `kin` ref — a kin snapshot (from hafen.kin.list/find, read for its `id`), the id number directly, or a name
    // string (exact, case-insensitive) — resolved to the LIVE BuddyWnd.Buddy, then driving the client's own Buddy
    // method (wrap-not-reimplement, D-009). remove/forget are the TWO STEPS of dropping a kin (the game's "End
    // kinship" then "Forget"): endkin() ends the kinship (the kin stays memorized), forget() drops the memorized
    // kin from the list — both send wdgmsg("rm", id), and the SERVER advances active → memorized → gone (so a full
    // removal of an active kin is remove() then forget()). rename→chname()=wdgmsg("nick"), setGroup→chgrp()=wdgmsg(
    // "grp"). All run on the UI thread (addon callback / REPL / timer / slash command).

    /** {@code hafen.kin.add} backing — add a kin by the other player's hearth secret, exactly what the Kin
     *  window's "Add kin" button/field sends ({@code BuddyWnd.wdgmsg("bypwd", secret)}, {@link BuddyWnd} :504/:509).
     *  The server validates the secret (a wrong/empty one just does nothing); we reject an empty string up front. */
    private static void actKinAdd(String secret) {
        if(secret.isEmpty())
            throw new LuaError("hafen.kin.add(secret): secret must not be empty (the other player's hearth secret)");
        BuddyWnd bw = buddywnd();
        if(bw == null)
            throw new LuaError("hafen.kin.add: no Kin window (not in the world yet)");
        bw.wdgmsg("bypwd", secret);
    }

    /** Resolve a kin ref (snapshot table with {@code id} / id number / name string) to the live
     *  {@link BuddyWnd.Buddy}, or {@code null} if no such kin; throws for a wrong argument TYPE or no window. */
    private static BuddyWnd.Buddy resolveKin(LuaValue ref) {
        BuddyWnd bw = buddywnd();
        if(bw == null)
            throw new LuaError("hafen.kin: no Kin window (not in the world yet)");
        if(ref.istable()) {                            // a kin snapshot from hafen.kin.list/find → its id
            LuaValue id = ref.get("id");
            if(!id.isnumber())
                throw new LuaError("hafen.kin: the kin table has no numeric 'id' field"
                    + " (pass a kin from hafen.kin.list/find, its id, or a name)");
            return bw.find(id.toint());
        }
        if(ref.isnumber())
            return bw.find(ref.toint());
        if(ref.isstring()) {
            String needle = ref.tojstring();
            for(BuddyWnd.Buddy b : bw)                  // iterator() copies under the BuddyWnd's own lock
                if((b.name != null) && b.name.equalsIgnoreCase(needle))
                    return b;
            return null;
        }
        throw new LuaError("hafen.kin: kin must be a kin snapshot (a table), its id (a number), or a name (a string)");
    }

    /** {@link #resolveKin} but throws a guiding error when the ref resolves to no one on the roster. */
    private static BuddyWnd.Buddy requireKin(LuaValue ref) {
        BuddyWnd.Buddy b = resolveKin(ref);
        if(b == null)
            throw new LuaError("hafen.kin: no such kin (the ref did not match anyone on your roster)");
        return b;
    }

    /** {@code hafen.kin.setGroup} backing — resolve the kin, validate group 0..7, then move it to that
     *  group/colour. Resolve FIRST so that with no live window we fail with "no Kin window" before touching
     *  {@link BuddyWnd#gc} (a static-field read that would force the resource-loading class init — headless-unsafe,
     *  like A7's {@code Speedget.tips}); in-game the range is validated against the real palette length. */
    private static void actKinSetGroup(LuaValue ref, int group) {
        BuddyWnd.Buddy b = requireKin(ref);            // needs a live Kin window; instance-only, no BuddyWnd statics
        if((group < 0) || (group >= BuddyWnd.gc.length))
            throw new LuaError("hafen.kin.setGroup(kin, group): group must be 0.." + (BuddyWnd.gc.length - 1)
                + " (the kin colour groups), got " + group);
        b.chgrp(group);                                // wdgmsg("grp", id, group)
    }

    // ---- actions tier (Phase 4: hafen.act) -------------------------------------------------------
    // The GATED automation surface (gate: requireActions / the declared per-addon permission, above). Every verb is a
    // Widget.wdgmsg from a bound widget — literally what a player click would send, so the client stays
    // server-authoritative (an addon can do only what a player could do; the permission is about user control,
    // not a client exploit — spec 12). moveTo sends the MapView "click" that a left-click on the ground sends:
    // {pc (screen coord), mc (world coord floored to posres), button, mods}. For a PROGRAMMATIC move the
    // destination is the world coord (2nd arg); the screen coord (pc) is a dummy — the current mouse position
    // — exactly as MiniMap.mvclick does when you click the minimap to walk (MiniMap.java:1218), so an
    // off-screen destination is fine. button 1 = walk; mods 0 = no modifier. Runs on the UI thread (addon
    // callback / REPL); wdgmsg queues to the session, and any 2d "click" action-hook sees it (it is a real
    // action) — the 2d re-entrancy guard prevents a hook-issued moveTo from looping.

    /** The world "click" destination Coord for a move to world (x, y) — MapView floors world coords to posres. */
    static Coord moveClickCoord(double x, double y) {
        return new Coord2d(x, y).floor(OCache.posres);
    }

    /** {@code hafen.act.moveTo} backing — send the ground-"click" that walks the character to world (x, y). */
    private static void actMoveTo(double x, double y) {
        MapView m = view;
        if(m == null)
            throw new LuaError("hafen.act.moveTo: no map view (not in the world yet)");
        Coord pc = (m.ui != null) ? m.ui.mc : Coord.z;   // dummy screen coord (current mouse), like MiniMap.mvclick
        m.wdgmsg("click", pc, moveClickCoord(x, y), 1, 0);
    }

    // -- 4d: the rest of the MapView action verbs (clickGob / useItemOn / place / select) + raw --------------
    // Each is the SAME kind of send as moveTo — a Widget.wdgmsg from the MapView, exactly what the matching
    // mouse gesture produces (MapView.Click.hit / iteminteract / mousedown-place / Selector.mmouseup). They
    // reuse moveTo's world→Coord encoding (moveClickCoord = Coord2d.floor(posres)) and its dummy screen coord
    // (pc = the current mouse, meaningless for a programmatic action but part of the wire shape). The arg-array
    // BUILDERS below are pure (no live state) so they are headless-testable; the act* SENDERS grab the live
    // MapView, fill pc, and wdgmsg. All run on the UI thread (addon callback / REPL); wdgmsg queues to the
    // session, and a 2d "click" action-hook sees a clickGob (it is a real "click") — the 2d re-entrancy guard
    // stops a hook-issued verb from looping.

    /**
     * The full MapView {@code "click"} args for a generic click on the gob {@code (gobId, gobRc)} — the
     * {@code {pc, mc, button, mods}} prefix extended with {@link haven.Gob.GobClick#clickargs}'
     * {@code {0, gobid, gobrc, 0, -1}} (no overlay, no specific sub-mesh). {@code mc} = the gob's own floored
     * position, as a click landing on its base would carry. Pure/testable.
     */
    static Object[] clickGobArgs(Coord pc, int button, int mods, int gobId, Coord gobRc) {
        return new Object[] {pc, gobRc, button, mods, 0, gobId, gobRc, 0, -1};
    }

    /** {@code hafen.act.clickGob} backing — click the resolved gob (the ref = the read API's GobRef). */
    private static void actClickGob(LuaValue ref, int button, int mods) {
        MapView m = view;
        if(m == null)
            throw new LuaError("hafen.act.clickGob: no map view (not in the world yet)");
        Gob g = resolve(ref);
        if(g == null)
            throw new LuaError("hafen.act.clickGob: no such gob (the ref did not resolve to a visible object)");
        Coord2d rc;
        synchronized(g) { rc = g.rc; }                   // OCache discipline: copy under the gob lock
        if(rc == null)
            throw new LuaError("hafen.act.clickGob: the gob has no position yet");
        Coord pc = (m.ui != null) ? m.ui.mc : Coord.z;
        m.wdgmsg("click", clickGobArgs(pc, button, mods, (int)g.id, rc.floor(OCache.posres)));
    }

    /** The MapView {@code "itemact"} args (use held item on the ground at world x,y). Pure/testable. */
    static Object[] itemactArgs(Coord pc, double x, double y, int mods) {
        return new Object[] {pc, moveClickCoord(x, y), mods};
    }

    /** {@code hafen.act.useItemOn} backing — apply the cursor item to the ground at world (x, y). */
    private static void actUseItemOn(double x, double y, int mods) {
        MapView m = view;
        if(m == null)
            throw new LuaError("hafen.act.useItemOn: no map view (not in the world yet)");
        Coord pc = (m.ui != null) ? m.ui.mc : Coord.z;
        m.wdgmsg("itemact", itemactArgs(pc, x, y, mods));
    }

    /** The MapView {@code "place"} angle encoding: radians → the server's {@code round(angle*32768/PI)}. Pure. */
    static int placeAngle(double radians) {
        return (int)Math.round(radians * 32768 / Math.PI);
    }

    /** The MapView {@code "place"} args ({@code {rc, angleInt, button, mods}}). Pure/testable. */
    static Object[] placeArgs(double x, double y, double angle, int button, int mods) {
        return new Object[] {moveClickCoord(x, y), placeAngle(angle), button, mods};
    }

    /** {@code hafen.act.place} backing — place the cursor object at world (x, y) rotated by {@code angle} rad. */
    private static void actPlace(double x, double y, double angle, int button, int mods) {
        MapView m = view;
        if(m == null)
            throw new LuaError("hafen.act.place: no map view (not in the world yet)");
        m.wdgmsg("place", placeArgs(x, y, angle, button, mods));
    }

    /**
     * The MapView {@code "sel"} args ({@code {tc1, tc2, mods}}) — world corners floored to TILE coords, the
     * same conversion {@code hafen.map.worldToTile} exposes ({@code Coord2d.floor(MCache.tilesz)}). Pure/testable.
     */
    static Object[] selArgs(double x1, double y1, double x2, double y2, int mods) {
        Coord tc1 = Coord2d.of(x1, y1).floor(MCache.tilesz);
        Coord tc2 = Coord2d.of(x2, y2).floor(MCache.tilesz);
        return new Object[] {tc1, tc2, mods};
    }

    /** {@code hafen.act.select} backing — area-select the tile rectangle between world corners. */
    private static void actSelect(double x1, double y1, double x2, double y2, int mods) {
        MapView m = view;
        if(m == null)
            throw new LuaError("hafen.act.select: no map view (not in the world yet)");
        m.wdgmsg("sel", selArgs(x1, y1, x2, y2, mods));
    }

    /**
     * {@code hafen.act.raw} backing — send an arbitrary wdgmsg from a bound widget. {@code a.arg1()} = the
     * target (a numeric server widget id, or a "mapview"/"gameui"/"root" token); {@code a.arg(2)} = the message
     * name; the rest are the message args (marshalled via {@link LuaMarshal#toJava}). "Bound widgets only" — a
     * looked-up widget id and the core-widget tokens are all server-bound.
     */
    private static void actRaw(Varargs a) {
        LuaValue msgv = a.arg(2);
        if(!msgv.isstring())
            throw new LuaError("hafen.act.raw(target, msg, ...): msg must be a string");
        Widget w = rawTarget(a.arg1());
        if(w == null)
            throw new LuaError("hafen.act.raw: target did not resolve to a live widget"
                + " (expected a bound widget id, \"mapview\", or \"gameui\")");
        int n = a.narg();
        Object[] args = new Object[Math.max(0, n - 2)];
        for(int i = 3; i <= n; i++)
            args[i - 3] = LuaMarshal.toJava(a.arg(i), "hafen.act.raw");
        w.wdgmsg(msgv.tojstring(), args);
    }

    /** Resolve a {@code raw} target: a numeric server widget id ({@code UI.getwidget}), or a hook-style token. */
    private static Widget rawTarget(LuaValue target) {
        if(target.isnumber()) {
            UI u = ui;
            return (u == null) ? null : u.getwidget(target.toint());
        }
        if(target.isstring()) {
            String tok = target.tojstring().toLowerCase();
            if(isKnownTarget(tok))
                return hookTarget(tok);          // "mapview"/"gameui"/"root" → the live bound widget (reuse 2c)
        }
        return null;
    }

    // -- 4e: menu + flower verbs -----------------------------------------------------------------------
    // menu goes through GameUI.act (the "act" wdgmsg by path — what the action-bar menu grid sends); flower
    // through the OPEN FlowerMenu's own choose (wrap-not-reimplement, D-009 — reuses the client's petal
    // selection, including its client-side petals). Both run on the UI thread (addon callback / REPL / timer),
    // like the MapView verbs above, and locate their target by walking the live widget tree (gui() / the
    // recursive children(FlowerMenu.class)). flower is non-throwing on "no menu / no match" (returns false).

    /** Build the menu path {@code String[]} from the 1-based varargs; throws on an empty path or a non-string
     *  token (numbers coerce to their string form, like a console token). Pure/testable. */
    static String[] menuPath(Varargs a) {
        int n = a.narg();
        if(n < 1)
            throw new LuaError("hafen.act.menu(path...): at least one path token is required");
        String[] path = new String[n];
        for(int i = 1; i <= n; i++) {
            LuaValue v = a.arg(i);
            if(!v.isstring())
                throw new LuaError("hafen.act.menu(path...): every path token must be a string");
            path[i - 1] = v.tojstring();
        }
        return path;
    }

    /** {@code hafen.act.menu} backing — send the "act" menu-path message via {@link GameUI#act(String...)}. */
    private static void actMenu(Varargs a) {
        GameUI g = gui();
        if(g == null)
            throw new LuaError("hafen.act.menu: no game UI (not in the world yet)");
        g.act(menuPath(a));
    }

    /** The single OPEN radial context menu ({@link FlowerMenu}), or {@code null} if none is up. */
    private static FlowerMenu openFlower() {
        UI u = ui;
        if((u == null) || (u.root == null))
            return null;
        for(FlowerMenu fm : u.root.children(FlowerMenu.class))   // recursive walk; only one is ever open (it grabs input)
            return fm;
        return null;
    }

    /** Index of the first petal name equal to {@code label} (case-insensitive), or {@code -1}. Pure/testable. */
    static int flowerPetalIndex(String[] names, String label) {
        if(names == null)
            return -1;
        for(int i = 0; i < names.length; i++) {
            if((names[i] != null) && names[i].equalsIgnoreCase(label))
                return i;
        }
        return -1;
    }

    /**
     * {@code hafen.act.flower} backing — select the open flower menu's petal whose name equals {@code label}
     * (case-insensitive), via the client's own {@link FlowerMenu#choose}. Returns whether a petal matched.
     */
    private static boolean actFlower(String label) {
        FlowerMenu fm = openFlower();
        if(fm == null)
            return false;                        // no menu open
        FlowerMenu.Petal[] opts = fm.opts;
        if(opts == null)
            return false;
        String[] names = new String[opts.length];
        for(int i = 0; i < opts.length; i++)
            names[i] = (opts[i] == null) ? null : opts[i].name;
        int idx = flowerPetalIndex(names, label);
        if(idx < 0)
            return false;                        // no petal matched
        fm.choose(opts[idx]);                    // wrap-not-reimplement: the client's own petal selection
        return true;
    }

    // -- 4f: item verbs (hafen.act.item) ---------------------------------------------------------------
    // The item half of the gated tier. Unlike the MapView verbs (which act on world coords) an item verb acts
    // on a specific item, addressed by a HANDLE = the item's server widget id (GItem.wdgid(), carried on every
    // item snapshot as `handle` — D-022: handle-only). We re-resolve the live GItem from that id each call
    // (ui.getwidget(id); a stale/used/moved item no longer maps to a GItem → a guiding error, exactly like a
    // GobRef that no longer resolves) and send the SAME GItem.wdgmsg the corresponding click sends
    // (WItem.mousedown: take/drop/transfer/iact; WItem.iteminteract: itemact) — the client stays server-
    // authoritative. The coord these messages carry is the intra-item grab point; Coord.z (the item's corner)
    // is a faithful, deterministic substitute for a programmatic action. The arg BUILDER is pure/testable; the
    // sender resolves the live GItem and wdgmsgs. Runs on the UI thread (addon callback / REPL / timer), like
    // every act verb; a 2d "take"/… action-hook can still see it (it is a real wdgmsg).

    /**
     * The {@link GItem} {@code wdgmsg} args for an item {@code verb}, or {@code null} for an unknown verb.
     * {@code n} is the stack count for {@code drop}/{@code transfer} ({@code -1} = the whole stack). The others
     * carry no count: {@code take} is a bare grab; {@code iact}/{@code itemact} send modifiers {@code 0} (a
     * modified interaction goes through {@code hafen.act.raw}). Pure/testable — the grab coord is a fixed corner.
     */
    static Object[] itemVerbArgs(String verb, int n) {
        switch(verb) {
            case "take":     return new Object[] {Coord.z};
            case "drop":     return new Object[] {Coord.z, n};
            case "transfer": return new Object[] {Coord.z, n};
            case "iact":     return new Object[] {Coord.z, 0};   // mods 0 — plain right-click / activate
            case "itemact":  return new Object[] {0};            // mods 0 — apply the held item onto this one
            default:         return null;
        }
    }

    /**
     * Resolve an {@code ItemRef} to the live {@link GItem}: {@code item} is either a numeric handle (the item's
     * server widget id) or an item snapshot table carrying a numeric {@code handle} field. Throws a guiding
     * {@link LuaError} when it is neither (a programmer error), and returns {@code null} when the handle no
     * longer maps to a live {@link GItem} (a stale/used item — the caller turns that into an action error).
     */
    private static GItem resolveItemHandle(LuaValue item) {
        int id;
        if(item.isnumber()) {
            id = item.toint();
        } else if(item.istable()) {
            LuaValue h = item.get("handle");
            if(!h.isnumber())
                throw new LuaError("hafen.act.item: the item table has no numeric 'handle' field"
                    + " (pass an item from hafen.items.* / model:items(), or its .handle)");
            id = h.toint();
        } else {
            throw new LuaError("hafen.act.item(item, verb): item must be an item snapshot (a table) or a"
                + " handle id (a number)");
        }
        UI u = ui;
        if(u == null)
            return null;
        Widget w = u.getwidget(id);
        return (w instanceof GItem) ? (GItem)w : null;
    }

    /** {@code hafen.act.item} backing — resolve the live {@link GItem} by its handle and send the verb's wdgmsg. */
    private static void actItem(LuaValue item, String verb, int n) {
        Object[] args = itemVerbArgs(verb, n);
        if(args == null)
            throw new LuaError("hafen.act.item(item, verb): verb must be one of \"take\", \"drop\","
                + " \"transfer\", \"iact\", \"itemact\" (got \"" + verb + "\")");
        GItem g = resolveItemHandle(item);
        if(g == null)
            throw new LuaError("hafen.act.item: the item did not resolve to a live item — its handle is stale"
                + " (it was moved/used/consumed, or you are not in the world). Re-read hafen.items.* and retry.");
        g.wdgmsg(verb, args);
    }

    // ---- movement speed (A7: hafen.speed) --------------------------------------------------------
    // The speed selector is a Speedget widget (crawl/walk/run/sprint) the server places under the HUD.
    // It has no named GameUI field, so we locate it with the 1d-1 Locator (a children(Class) subtree
    // walk from the HUD) — the same way vitals finds its IMeters. Both fields we read (cur = current
    // speed, max = highest currently-selectable speed) are public ints, so this is a zero-haven-edit
    // read. All calls run on the UI thread (addon tick / REPL). Changing speed is the gated Phase-4 tier.

    /** The (unique) movement-speed widget under the HUD, or {@code null} before it has streamed in. */
    private static Speedget speedget() {
        GameUI g = gui();
        if(g == null)
            return null;
        for(Speedget s : g.children(Speedget.class))   // recursive subtree walk; take the first
            return s;
        return null;
    }

    /** The display name of speed {@code n} (0..3) from the widget's own tooltips, or nil if out of range. */
    private static LuaValue speedName(int n) {
        String[] tips = Speedget.tips;                 // "Crawl"/"Walk"/"Run"/"Sprint" (resource tooltips)
        if((tips == null) || (n < 0) || (n >= tips.length))
            return LuaValue.NIL;
        String t = tips[n];
        return (t == null) ? LuaValue.NIL : LuaValue.valueOf(t);
    }

    /**
     * {@code hafen.speed.set} backing (4g, gated) — select movement speed {@code n} (0..3) via the client's own
     * {@link Speedget#set} (wrap-not-reimplement, D-009 → {@code wdgmsg("set", n)}). The server is authoritative
     * on whether a speed is currently allowed (e.g. sprint may be locked); this only sends the request, exactly
     * as clicking/hotkeying that speed would. Throws for out-of-range {@code n} or before the selector exists.
     */
    private static void actSpeedSet(int n) {
        if((n < 0) || (n > 3))
            throw new LuaError("hafen.speed.set(n): n must be 0..3 (0=crawl 1=walk 2=run 3=sprint), got " + n);
        Speedget s = speedget();
        if(s == null)
            throw new LuaError("hafen.speed.set: no speed selector (not in the world yet)");
        s.set(n);
    }

    // ---- crafting (A8: hafen.craft) --------------------------------------------------------------
    // The crafting/recipe window is a Makewindow (@RName("make")) the server places under the HUD when
    // the player opens a recipe. It is wrapped in GameUI.makewnd (a private Window), so — like A7's speed
    // selector — we locate the content widget with the 1d-1 Locator (a children(Class) subtree walk from
    // the HUD), not a named GameUI field. A recipe carries: rcpnm (the recipe name), inputs (ingredient
    // slots), outputs (product slots), qmod (quality-affecting input resources) and tools (required tool
    // resources). Read-only here — craft.make is the gated Phase-4 action tier.
    //
    // Threading: inputs/outputs/qmod are List references the "inpop"/"opop"/"qmod" uimsgs swap WHOLESALE
    // off the UI thread (on a Loader thread, under synchronized(ui)); tools is mutated IN PLACE ("tool"
    // uimsg → tools.add). So we copy all four lists under the ui monitor (the marker "copy under the lock,
    // snapshot outside it" discipline), then resolve resource names outside the lock (res.get() may Loading).
    // All backings are public (Makewindow.rcpnm/inputs/outputs/qmod/tools, SpecWidget.spec, Spec.item/
    // constraint/num/opt(), ResData.res) → zero haven edit, like A7/A6/A4/A2.

    /** The (unique) crafting window content under the HUD, or {@code null} if no recipe is open. */
    private static Makewindow makewindow() {
        GameUI g = gui();
        if(g == null)
            return null;
        for(Makewindow m : g.children(Makewindow.class))   // recursive subtree walk; take the first
            return m;
        return null;
    }

    /**
     * {@code hafen.craft.make} backing (4g, gated) — press the open recipe's Craft button ({@code all=false} →
     * {@code wdgmsg("make", 0)}, one item) or Craft All ({@code all=true} → {@code wdgmsg("make", 1)}), exactly
     * what the two buttons send ({@link Makewindow} :147/:148). CONSUMES the ingredients like a manual craft.
     * Throws when no crafting window is open.
     */
    private static void actCraftMake(boolean all) {
        Makewindow mw = makewindow();
        if(mw == null)
            throw new LuaError("hafen.craft.make: no crafting window open (open a recipe first)");
        mw.wdgmsg("make", all ? 1 : 0);
    }

    /** {@code hafen.craft.current()} — a snapshot of the open recipe, or {@code nil}. */
    private static LuaValue readCraft() {
        Makewindow mw = makewindow();
        UI u = ui;
        if((mw == null) || (u == null))                    // mw is found via gui() (needs ui) → u!=null here
            return LuaValue.NIL;
        String recipe;
        List<Makewindow.Input> inputs;
        List<Makewindow.SpecWidget> outputs;
        List<Indir<Resource>> qmod, tools;
        synchronized(u) {                                  // copy the off-thread-mutated lists under the lock
            recipe = mw.rcpnm;
            inputs = new ArrayList<Makewindow.Input>(mw.inputs);
            outputs = new ArrayList<Makewindow.SpecWidget>(mw.outputs);
            qmod = new ArrayList<Indir<Resource>>(mw.qmod);
            tools = new ArrayList<Indir<Resource>>(mw.tools);
        }
        LuaTable t = new LuaTable();                       // ...then snapshot outside it (names may Loading)
        t.set("recipe", LuaValue.valueOf(recipe == null ? "" : recipe));
        t.set("inputs", craftSpecs(inputs));
        t.set("outputs", craftSpecs(outputs));
        t.set("qmod", craftReses(qmod));
        t.set("tools", craftReses(tools));
        return t;
    }

    /** An array (1-based) of crafting-spec snapshots for the given input/output widgets. */
    private static LuaTable craftSpecs(List<? extends Makewindow.SpecWidget> widgets) {
        LuaTable out = new LuaTable();
        int i = 0;
        for(Makewindow.SpecWidget w : widgets)
            out.set(++i, craftSpec(w.spec));
        return out;
    }

    /** A crafting spec (one input or output slot) as {@code {res, name, num, opt}}. Loading-guarded. */
    private static LuaValue craftSpec(Makewindow.Spec spec) {
        LuaTable t = new LuaTable();
        // The displayed resource is the constraint (a category, e.g. "any board") when the recipe accepts
        // one, else the concrete item — mirroring Makewindow.Spec.display(): that is what fills the slot.
        Indir<Resource> res = (spec.constraint != null) ? spec.constraint.res : spec.item.res;
        String id = resIdent(res);
        if(id != null)
            t.set("res", LuaValue.valueOf(id));
        String name = resTipName(res, id);
        if(name != null)
            t.set("name", LuaValue.valueOf(name));
        t.set("num", LuaValue.valueOf(spec.num));          // -1 = unspecified (≈ 1); exposed faithfully
        boolean opt;
        try {
            opt = spec.opt();                              // reads info() — may Loading before resources land
        } catch(RuntimeException e) {
            opt = false;
        }
        t.set("opt", LuaValue.valueOf(opt));
        return t;
    }

    /** An array (1-based) of {@code {res, name}} snapshots for bare resource lists (qmod / tools). */
    private static LuaTable craftReses(List<Indir<Resource>> reses) {
        LuaTable out = new LuaTable();
        int i = 0;
        for(Indir<Resource> res : reses)
            out.set(++i, craftRes(res));
        return out;
    }

    /** A bare resource reference as {@code {res, name}} (a quality modifier or a tool). Loading-guarded. */
    private static LuaValue craftRes(Indir<Resource> res) {
        LuaTable t = new LuaTable();
        String id = resIdent(res);
        if(id != null)
            t.set("res", LuaValue.valueOf(id));
        String name = resTipName(res, id);
        if(name != null)
            t.set("name", LuaValue.valueOf(name));
        return t;
    }

    // ---- quest log (A9: hafen.quests) ------------------------------------------------------------
    // The quest log is a QuestWnd (@RName("quests")) — the character sheet's "Quest Log" tab, held by the
    // public CharWnd.quest field (created hidden at login but live, so quests read without opening it). It
    // keeps two lists: cqst (the "Current" tab: pending/disabled quests) and dqst (the "Completed" tab:
    // done/failed). Each Quest carries {id, res (Indir<Resource>), title (may be null), done (a status
    // int), mtime}. The conditions/objectives of a quest are loaded only for the one the player has SELECTED
    // (QuestWnd.quest, a Quest.Box with a Condition[]) — a faithful client limitation (like A8's no per-item
    // countdown), so selected() is the only place conds appear. All backings are public (QuestWnd.cqst/dqst/
    // quest, QuestList.quests/get, Quest.id/res/title/done/mtime, Quest.Box.id/cond, Quest.Condition.desc/
    // done/status) → zero haven edit, like A8/A7/A6/A4/A2. The status ints (QST_PEND/DONE/FAIL/DISABLED) are
    // compile-time constants → inlined, so the status helpers do NOT load Quest (whose <clinit> renders text
    // and would fail headless) — they stay headless-testable.
    //
    // Threading: the quest lists (and the selected box's cond[]) are mutated on a Loader thread by
    // QuestWnd.uimsg("quests")/Box.uimsg("conds") under synchronized(ui). So we copy the list/array refs
    // under the ui monitor, then build the Lua snapshots outside it (res.get() may Loading) — the marker
    // "copy under the lock, snapshot outside it" discipline (A1/A8).

    /** The Quest Log window (the character sheet's "Quest Log" tab — created hidden at login but live),
     *  or {@code null} before it exists. Via the public {@code CharWnd.quest} field (no tree-walk). */
    private static QuestWnd questwnd() {
        CharWnd c = charwnd();
        return (c == null) ? null : c.quest;
    }

    /** {@code hafen.quests.list([filter])} — every quest (Current + Completed) as {@code {id, name, res,
     *  status, mtime}} snapshots, filtered by the canonical nil=all / name-substring / predicate. */
    private static LuaValue questList(LuaValue filter) {
        LuaTable out = new LuaTable();
        QuestWnd qw = questwnd();
        UI u = ui;
        if((qw == null) || (u == null))
            return out;
        List<QuestWnd.Quest> all = new ArrayList<QuestWnd.Quest>();
        synchronized(u) {                            // the quest lists mutate off-thread (QuestWnd.uimsg)
            all.addAll(qw.cqst.quests);              // "Current" tab (pending / disabled)
            all.addAll(qw.dqst.quests);              // "Completed" tab (done / failed)
        }
        int i = 0;
        for(QuestWnd.Quest q : all) {                // resolve names outside the lock (res.get() may Loading)
            LuaValue snap = questSnapshot(q);
            if(matches(filter, snap))
                out.set(++i, snap);
        }
        return out;
    }

    /** {@code hafen.quests.selected()} — the quest currently open in the log (the only one whose conditions
     *  the client loads), as a list snapshot plus {@code conds={{desc, status, text?}}}, or {@code nil}. */
    private static LuaValue questSelected() {
        QuestWnd qw = questwnd();
        UI u = ui;
        if((qw == null) || (u == null))
            return LuaValue.NIL;
        QuestWnd.Quest q;
        QuestWnd.Quest.Condition[] conds;
        synchronized(u) {                            // qw.quest / box.cond are swapped off-thread (uimsg)
            QuestWnd.Quest.Info info = qw.quest;     // the selected quest's Box, or null (nothing selected)
            if(!(info instanceof QuestWnd.Quest.Box))
                return LuaValue.NIL;
            QuestWnd.Quest.Box box = (QuestWnd.Quest.Box)info;
            conds = box.cond;                        // Condition[] (swapped wholesale on the "conds" uimsg)
            q = qw.cqst.get(box.id);                 // the matching Quest (for status/mtime) in either tab
            if(q == null)
                q = qw.dqst.get(box.id);
        }
        if(q == null)
            return LuaValue.NIL;                     // selected id not in either list (shouldn't happen)
        LuaTable t = (LuaTable)questSnapshot(q);
        t.set("conds", questConds(conds));
        return t;
    }

    /** One quest as {@code {id, name, res, status, mtime}}. {@code name} = the quest title (the explicit
     *  title, else the resource tooltip); {@code res} = the stable resource id. Loading-guarded. */
    private static LuaValue questSnapshot(QuestWnd.Quest q) {
        LuaTable t = new LuaTable();
        t.set("id", LuaValue.valueOf(q.id));
        // Quest.title() prefers the explicit title over the tooltip; mirror it (both Loading-guarded).
        String name = (q.title != null) ? q.title : resTipName(q.res, null);
        if(name != null)
            t.set("name", LuaValue.valueOf(name));
        String res = resIdent(q.res);
        if(res != null)
            t.set("res", LuaValue.valueOf(res));
        t.set("status", LuaValue.valueOf(questStatus(q.done)));
        t.set("mtime", LuaValue.valueOf(q.mtime));
        return t;
    }

    /** The selected quest's conditions as a 1-based array of {@code {desc, status, text?}}. */
    private static LuaValue questConds(QuestWnd.Quest.Condition[] cond) {
        LuaTable out = new LuaTable();
        if(cond == null)
            return out;
        int i = 0;
        for(QuestWnd.Quest.Condition c : cond)
            out.set(++i, questCond(c));
        return out;
    }

    /** One condition as {@code {desc, status ("pending"/"done"/"failed"), text?}}. {@code text} = the
     *  condition's extra status string (absent when none). */
    private static LuaValue questCond(QuestWnd.Quest.Condition c) {
        LuaTable t = new LuaTable();
        if(c.desc != null)
            t.set("desc", LuaValue.valueOf(c.desc));
        t.set("status", LuaValue.valueOf(questCondStatus(c.done)));
        if(c.status != null)
            t.set("text", LuaValue.valueOf(c.status));
        return t;
    }

    /** Is a quest status "active" (shown in the Quest Log's Current tab)? — pending or disabled. */
    private static boolean questActive(int done) {
        return (done == QuestWnd.Quest.QST_PEND) || (done == QuestWnd.Quest.QST_DISABLED);
    }

    /** The API status string for a {@code Quest.done} code (QST_PEND/DONE/FAIL/DISABLED). */
    private static String questStatus(int done) {
        if(done == QuestWnd.Quest.QST_DONE)     return "done";
        if(done == QuestWnd.Quest.QST_FAIL)     return "failed";
        if(done == QuestWnd.Quest.QST_DISABLED) return "disabled";
        return "pending";                            // QST_PEND (and any unexpected code)
    }

    /** The API status string for a condition's {@code done} code (0=pending, 1=done, 2=failed). */
    private static String questCondStatus(int done) {
        if(done == QuestWnd.Quest.QST_DONE) return "done";
        if(done == QuestWnd.Quest.QST_FAIL) return "failed";
        return "pending";
    }

    // ---- wounds (A9-2: hafen.wounds) -------------------------------------------------------------
    // Wounds are a WoundWnd (@RName("wounds")) — the character sheet's "Health & Wounds" tab, held by the
    // public CharWnd.wound field (created hidden at login but live, so wounds read without opening it). The
    // window keeps a WoundList whose public List<Wound> is the flat set of wounds; the client renders it as
    // a TREE (Wound.parentid links a complication to its parent wound, -1 = a root; Wound.level = the depth
    // the WoundList's treesort computes for indentation). Each Wound carries {id, parentid (public final
    // int), res (Indir<Resource>), level (public int)} and, from its resource-published ItemInfo, a display
    // name (ItemInfo.Name) and a severity indicator (the highest-priority WoundWnd.QuickInfo's qstr() — the
    // magnitude the client shows beside the wound; content-defined, usually a number, NOT seconds). All
    // backings are public (WoundWnd.wounds, WoundList.wounds, Wound.id/parentid/res/level/info(),
    // WoundWnd.QuickInfo.qstr/qprio) → zero haven edit, like A9-1/A8/A7/A6/A4/A2.
    //
    // Threading: the wound list is mutated on a Loader thread by WoundWnd.uimsg("wounds") (decwound adds /
    // updates / removes) under synchronized(ui), and reassigned by WoundList.tick's treesort on the UI
    // thread. So copyWounds() copies the list reference under the ui monitor (the marker "copy under the
    // lock, snapshot outside it" discipline), then names/severity resolve outside it (res.get()/info() may
    // Loading — guarded). WoundChanged is fired by the poll-driven WoundAdapter (severity streams in a beat
    // after the wound row, like study's Curiosity info, so a per-tick snapshot diff catches it) — not a
    // targeted uimsg, since a uimsg refresh would see severity still Loading and miss it.

    /** The Health &amp; Wounds window (the character sheet's "Health & Wounds" tab — created hidden at login
     *  but live), or {@code null} before it exists. Via the public {@code CharWnd.wound} field (no tree-walk). */
    private static WoundWnd woundwnd() {
        CharWnd c = charwnd();
        return (c == null) ? null : c.wound;
    }

    /** The live wound list copied under the {@code ui} monitor (WoundWnd.uimsg mutates it off-thread), or
     *  empty when the character sheet's wound tab isn't up yet. Snapshot the copies outside the lock. */
    private static List<WoundWnd.Wound> copyWounds() {
        List<WoundWnd.Wound> out = new ArrayList<WoundWnd.Wound>();
        WoundWnd ww = woundwnd();
        UI u = ui;
        if((ww == null) || (u == null))
            return out;
        synchronized(u) {
            out.addAll(ww.wounds.wounds);
        }
        return out;
    }

    /** {@code hafen.wounds.list([filter])} — every wound as {@code {id, name, res, severity, parentid,
     *  level}} snapshots, filtered by the canonical nil=all / name-substring / predicate. */
    private static LuaValue woundList(LuaValue filter) {
        LuaTable out = new LuaTable();
        int i = 0;
        for(WoundWnd.Wound w : copyWounds()) {        // resolve names/severity outside the lock (may Loading)
            LuaValue snap = woundSnapshot(w);
            if(matches(filter, snap))
                out.set(++i, snap);
        }
        return out;
    }

    /** One wound as {@code {id, name, res, severity, parentid, level}}. {@code name}/{@code res}/{@code
     *  severity} are Loading-guarded (omitted while resolving); {@code id}/{@code parentid}/{@code level}
     *  are plain public ints. */
    private static LuaValue woundSnapshot(WoundWnd.Wound w) {
        LuaTable t = new LuaTable();
        t.set("id", LuaValue.valueOf(w.id));
        String name = woundName(w);
        if(name != null)
            t.set("name", LuaValue.valueOf(name));
        String res = resIdent(w.res);
        if(res != null)
            t.set("res", LuaValue.valueOf(res));
        String sev = woundSeverity(w);
        if(sev != null)
            t.set("severity", LuaValue.valueOf(sev));
        t.set("parentid", LuaValue.valueOf(w.parentid));
        t.set("level", LuaValue.valueOf(w.level));
        return t;
    }

    /** Display name of a wound: the resource tooltip, else the server-pushed {@code ItemInfo.Name}, else
     *  {@code null} (Loading-guarded — like {@code buffName}). */
    private static String woundName(WoundWnd.Wound w) {
        String tip = resTipName(w.res, null);
        if(tip != null)
            return tip;
        try {
            ItemInfo.Name n = ItemInfo.find(ItemInfo.Name.class, w.info());
            return ((n == null) || (n.str == null)) ? null : n.str.text;
        } catch(RuntimeException e) {   // info() still Loading / no rawinfo yet
            return null;
        }
    }

    /**
     * The severity indicator the client shows beside a wound — its highest-priority {@link
     * WoundWnd.QuickInfo}'s {@code qstr()} (a content-defined string, usually the wound's magnitude
     * number; <b>not</b> seconds), or {@code null} if the wound publishes none / is still Loading. Mirrors
     * the client's own quick-info pick ({@code WoundWnd.WoundList.Item.getqdat}: the highest {@code qprio}).
     */
    private static String woundSeverity(WoundWnd.Wound w) {
        try {
            List<ItemInfo> info = w.info();           // may throw Loading
            WoundWnd.QuickInfo best = null;
            for(ItemInfo inf : info) {
                if(inf instanceof WoundWnd.QuickInfo) {
                    WoundWnd.QuickInfo qi = (WoundWnd.QuickInfo)inf;
                    if((best == null) || (best.qprio() < qi.qprio()))
                        best = qi;
                }
            }
            return (best == null) ? null : best.qstr();   // qstr() itself may be null (no quick string)
        } catch(RuntimeException e) {   // info() still Loading
            return null;
        }
    }

    /** Do two wound snapshot lists carry the same id/parentid/level/name/res/severity per entry? (change-
     *  detection for {@code WoundChanged}, mirroring {@code kinListEqual}). */
    private static boolean woundListEqual(LuaValue a, LuaValue b) {
        if((a == null) || (b == null) || !a.istable() || !b.istable())
            return a == b;
        int n = a.length();
        if(n != b.length())
            return false;
        for(int i = 1; i <= n; i++) {
            LuaValue ea = a.get(i), eb = b.get(i);
            if(ea.get("id").toint() != eb.get("id").toint())
                return false;
            if(ea.get("parentid").toint() != eb.get("parentid").toint())
                return false;
            if(ea.get("level").toint() != eb.get("level").toint())
                return false;
            if(!luaFieldEq(ea, eb, "name") || !luaFieldEq(ea, eb, "res") || !luaFieldEq(ea, eb, "severity"))
                return false;
        }
        return true;
    }

    // ---- combat schools (A10: hafen.fight) -------------------------------------------------------
    // The combat-school / maneuver-deck builder is a FightWnd (@RName("fmg")) — the character sheet's
    // "Martial Arts & Combat Schools" tab, held by the public CharWnd.fight field (created hidden at login
    // but live, so it reads without opening the window, exactly like A9's quests/wounds). This is the
    // OUT-OF-COMBAT configuration surface, distinct from the in-combat Fightview/Fightsess deck (which has
    // live rtime cooldowns and is the separate hafen.combat.* view). It keeps three data structures:
    //   • acts   — public List<Action>: every maneuver/attack you know. Each Action {res (public Indir<
    //              Resource>), a (public int = how many you can slot), u (public int = how many slotted)}.
    //   • order  — public final Action[]: the current school's card LAYOUT, index i → the maneuver bound to
    //              key FightWnd.keys[i] ("1".."5","⇧1".."⇧5"); a null entry is an empty slot.
    //   • saves[] + usesave/nsave/maxact — the saved schools (names in the PRIVATE saves[], so deferred) plus
    //              the active slot (usesave), slot count (nsave) and the action-point budget cap (maxact).
    // All the fields we read are public → zero haven edit (like A9/A8/A7/A6/A4/A2). Read-only; editing/
    // switching schools (wdgmsg load/save/use, drag, set counts) is the gated Phase-4 tier.
    //
    // Threading: the FightWnd.uimsg handlers run on a Loader thread under synchronized(ui): "avail" REPLACES
    // acts wholesale, "used"/"max" mutate act.u / maxact / order[] entries, and Actions.tick re-sorts acts on
    // the UI thread. So — the marker discipline — we copy the acts list / order[] array and read the scalars
    // under the ui monitor, then resolve resource names OUTSIDE the lock (res.get() may Loading). The public
    // int reads (a/u/maxact/usesave) outside the lock are snapshot-atomic like A9-2's wound ints.

    /** The Combat Schools window (the character sheet's "Martial Arts & Combat Schools" tab — created hidden
     *  at login but live), or {@code null} before it exists. Via the public {@code CharWnd.fight} field. */
    private static FightWnd fightwnd() {
        CharWnd c = charwnd();
        return (c == null) ? null : c.fight;
    }

    /** {@code hafen.fight.maneuvers([filter])} — every known combat maneuver/attack as {@code {res, name,
     *  avail, used}} snapshots, filtered by the canonical nil=all / name-substring / predicate. */
    private static LuaValue fightManeuvers(LuaValue filter) {
        LuaTable out = new LuaTable();
        FightWnd fw = fightwnd();
        UI u = ui;
        if((fw == null) || (u == null))
            return out;
        List<FightWnd.Action> acts = new ArrayList<FightWnd.Action>();
        synchronized(u) {                          // acts is swapped wholesale off-thread (the "avail" uimsg)
            acts.addAll(fw.acts);
        }
        int i = 0;
        for(FightWnd.Action a : acts) {            // resolve names outside the lock (res.get() may Loading)
            LuaValue snap = maneuverSnapshot(a);
            if(matches(filter, snap))
                out.set(++i, snap);
        }
        return out;
    }

    /** One maneuver as {@code {res, name, avail, used}}. {@code res}/{@code name} are Loading-guarded;
     *  {@code avail} ({@code Action.a}) / {@code used} ({@code Action.u}) are plain public ints. */
    private static LuaValue maneuverSnapshot(FightWnd.Action a) {
        LuaTable t = new LuaTable();
        String res = resIdent(a.res);
        if(res != null)
            t.set("res", LuaValue.valueOf(res));
        String name = resTipName(a.res, res);
        if(name != null)
            t.set("name", LuaValue.valueOf(name));
        t.set("avail", LuaValue.valueOf(a.a));
        t.set("used", LuaValue.valueOf(a.u));
        return t;
    }

    /** {@code hafen.fight.deck()} — the current school's configured card layout: the filled {@code order[]}
     *  slots in key order, each {@code {slot, key, res, name, used}}. Empty deck slots are omitted. */
    private static LuaValue fightDeck() {
        LuaTable out = new LuaTable();
        FightWnd fw = fightwnd();
        UI u = ui;
        if((fw == null) || (u == null))
            return out;
        FightWnd.Action[] order;
        synchronized(u) {                          // order[] entries are reassigned off-thread (the "used" uimsg)
            order = java.util.Arrays.copyOf(fw.order, fw.order.length);
        }
        int i = 0;
        for(int slot = 0; slot < order.length; slot++) {
            FightWnd.Action a = order[slot];
            if(a == null)
                continue;                          // an empty deck slot — omit (slot/key convey position)
            LuaTable t = new LuaTable();
            t.set("slot", LuaValue.valueOf(slot));
            t.set("key", LuaValue.valueOf(deckKey(slot)));
            String res = resIdent(a.res);          // resolved outside the lock (may Loading)
            if(res != null)
                t.set("res", LuaValue.valueOf(res));
            String name = resTipName(a.res, res);
            if(name != null)
                t.set("name", LuaValue.valueOf(name));
            t.set("used", LuaValue.valueOf(a.u));
            out.set(++i, t);
        }
        return out;
    }

    /** The hotkey label for deck slot {@code slot} (the game's own {@code FightWnd.keys}: "1".."5",
     *  "⇧1".."⇧5"), or a 1-based fallback if the deck is larger than the key table. */
    private static String deckKey(int slot) {
        String[] keys = FightWnd.keys;
        if((keys != null) && (slot >= 0) && (slot < keys.length) && (keys[slot] != null))
            return keys[slot];
        return String.valueOf(slot + 1);
    }

    /** {@code hafen.fight.summary()} — the scalars {@code {maxact, used, nact, nsave, usesave}}, or
     *  {@code nil} before the Combat Schools tab exists. {@code used} = the total action points spent
     *  (sum of every maneuver's {@code u}), mirroring the window's own "Used: u/maxact" count. */
    private static LuaValue fightSummary() {
        FightWnd fw = fightwnd();
        UI u = ui;
        if((fw == null) || (u == null))
            return LuaValue.NIL;
        int maxact, usesave, nsave, nact, used;
        synchronized(u) {                          // acts/order/maxact all mutate off-thread — read under the lock
            maxact = fw.maxact;
            usesave = fw.usesave;
            nsave = fw.nsave;
            nact = fw.order.length;
            used = 0;
            for(FightWnd.Action a : fw.acts)
                used += a.u;
        }
        LuaTable t = new LuaTable();
        t.set("maxact", LuaValue.valueOf(maxact));
        t.set("used", LuaValue.valueOf(used));
        t.set("nact", LuaValue.valueOf(nact));
        t.set("nsave", LuaValue.valueOf(nsave));
        t.set("usesave", LuaValue.valueOf(usesave));
        return t;
    }

    // ---- markers (A1: hafen.markers) -------------------------------------------------------------
    // Client-side map markers live in the on-disk map DB (MapFile), owned by the map window / corner
    // minimap (both hold the same MapFile). A marker's PERSISTENT identity is its segment id + segment
    // tile coord (survives a relog — coverage-gaps C4); the world x,y/dist a snapshot also carries are
    // SESSION-LOCAL conveniences, present only when the marker is in the player's current segment. Reads
    // copy the marker list under the MapFile read lock (it is mutated on loader threads — server markobj
    // adds, segment merges), then build snapshots outside the lock (the OCache gob-read discipline). Adds/
    // removes go straight to the shared DB and persist. MarkersChanged is fired by pollMarkers() when
    // MapFile.markerseq changes (a marker add/remove is not a uimsg — poll it, like buffs/study).

    private static final java.awt.Color DEFAULT_MARKER_COLOR = new java.awt.Color(255, 215, 0);  // gold pin

    /** Facade-safe marker refs (P1: no Java Marker crosses to Lua). Per-session (Marker identity is per-session). */
    private static final IdentityHashMap<MapFile.Marker, Long> markerIds = new IdentityHashMap<MapFile.Marker, Long>();
    private static final Map<Long, MapFile.Marker> markerById = new HashMap<Long, MapFile.Marker>();
    private static long markerIdSeq = 0;

    /** MarkersChanged is primed (not fired) the first time the map DB is seen, then fired on each markerseq change. */
    private static boolean markersPrimed = false;
    private static int lastMarkerSeq = 0;

    /** The client's on-disk map DB (markers/segments), or null before the HUD/map is up. */
    private static MapFile mapfile() {
        GameUI g = gui();
        if(g == null)
            return null;
        if(g.mapfile != null)          // the big Map window (MapWnd.file)
            return g.mapfile.file;
        MiniMap mm = g.mmap;            // fall back to the corner minimap (same MapFile instance)
        return (mm == null) ? null : mm.file;
    }

    /**
     * The session location — the segment plus the segment-tile-coord of session tile (0,0): the bridge
     * between session-local WORLD coords and the persistent segment coords markers store (world→segment =
     * sessloc.tc + floor(world/tilesz), mirroring {@code MapWnd.FindMark.hit}). Resolved live by the
     * corner minimap; null until the map grid-info has streamed in (a beat after enter-world).
     */
    private static MiniMap.Location sessloc() {
        GameUI g = gui();
        MiniMap mm = (g == null) ? null : g.mmap;
        return (mm == null) ? null : mm.sessloc;
    }

    /** Assign (or look up) a stable per-session ref id for a marker. Touched from UI + REPL threads → guarded. */
    private static long markerId(MapFile.Marker m) {
        synchronized(markerById) {
            Long id = markerIds.get(m);
            if(id == null) {
                id = Long.valueOf(++markerIdSeq);
                markerIds.put(m, id);
                markerById.put(id, m);
            }
            return id.longValue();
        }
    }
    private static MapFile.Marker markerByRef(long id) {
        synchronized(markerById) {
            return markerById.get(Long.valueOf(id));
        }
    }

    /** Snapshots of every marker in the DB (list copied under the read lock, snapshots built outside it). */
    private static List<LuaValue> markerSnapshots() {
        List<LuaValue> out = new ArrayList<LuaValue>();
        MapFile file = mapfile();
        if(file == null)
            return out;
        List<MapFile.Marker> copy = new ArrayList<MapFile.Marker>();
        file.lock.readLock().lock();
        try {
            copy.addAll(file.markers);
        } finally {
            file.lock.readLock().unlock();
        }
        MiniMap.Location sl = sessloc();
        Coord2d prc = pos(LuaValue.NIL);   // player world pos (may be null before the player gob is up)
        for(MapFile.Marker m : copy)
            out.add(markerSnapshot(m, sl, prc));
        return out;
    }

    /**
     * One marker → a Lua snapshot: {@code {id, name, type, seg, tc}} plus type-specific fields
     * ({@code color}/{@code onmap} for a player marker, {@code icon} for a system marker) and, when the
     * marker shares the player's current segment, the session-local {@code x,y} (world, tile centre) +
     * {@code dist} (from the player). {@code seg} is a decimal string (64-bit id); {@code tc} the segment
     * tile coord — those two are the persistent anchor, {@code x,y}/{@code dist} the session convenience.
     */
    private static LuaValue markerSnapshot(MapFile.Marker m, MiniMap.Location sl, Coord2d prc) {
        LuaTable t = new LuaTable();
        t.set("id", LuaValue.valueOf((double)markerId(m)));
        if(m.nm != null)
            t.set("name", LuaValue.valueOf(m.nm));
        t.set("seg", LuaValue.valueOf(Long.toString(m.seg)));   // 64-bit segment id (local anchor) → string
        t.set("tc", xy(m.tc.x, m.tc.y));                        // segment tile coord (the persistent position)
        if(m instanceof MapFile.PMarker) {
            MapFile.PMarker pm = (MapFile.PMarker)m;
            t.set("type", LuaValue.valueOf("player"));
            if(pm.color != null)
                t.set("color", color(pm.color));
            t.set("onmap", LuaValue.valueOf(pm.onmap));
        } else if(m instanceof MapFile.SMarker) {
            MapFile.SMarker sm = (MapFile.SMarker)m;
            t.set("type", LuaValue.valueOf("system"));
            if((sm.res != null) && (sm.res.name != null))
                t.set("icon", LuaValue.valueOf(sm.res.name));
        }
        // Session-local WORLD position (tile centre) + distance — only when the marker shares the player's
        // segment (a marker in another explored area has no valid world coord this session).
        if((sl != null) && (m.seg == sl.seg.id)) {
            double wx = ((m.tc.x - sl.tc.x) * MCache.tilesz.x) + (MCache.tilesz.x / 2);
            double wy = ((m.tc.y - sl.tc.y) * MCache.tilesz.y) + (MCache.tilesz.y / 2);
            t.set("x", LuaValue.valueOf(wx));
            t.set("y", LuaValue.valueOf(wy));
            if(prc != null)
                t.set("dist", LuaValue.valueOf(Math.hypot(wx - prc.x, wy - prc.y)));
        }
        return t;
    }

    /** add(name, worldX, worldY, opts) — create a PLAYER marker at a world position; returns its ref or nil. */
    private static LuaValue addMarker(String nm, double wx, double wy, LuaValue opts) {
        MapFile file = mapfile();
        MiniMap.Location sl = sessloc();
        if((file == null) || (sl == null))
            return LuaValue.NIL;                  // map / session location not up yet
        // world → segment tile coord (mirrors MapWnd.FindMark.hit: sessloc.tc + floor(world / tilesz)).
        Coord segTc = sl.tc.add(Coord2d.of(wx, wy).floor(MCache.tilesz));
        java.awt.Color col = DEFAULT_MARKER_COLOR;
        boolean onmap = false;
        if((opts != null) && opts.istable()) {
            LuaValue c = opts.get("color");
            if(c.istable())
                col = luaColor(c, col);
            LuaValue om = opts.get("onmap");
            if(!om.isnil())
                onmap = om.toboolean();
        }
        MapFile.PMarker pm = new MapFile.PMarker(file, sl.seg.id, segTc, nm, col, onmap);
        file.add(pm);                             // takes the write lock, persists (defersave), bumps markerseq
        return LuaValue.valueOf((double)markerId(pm));
    }

    /** remove(ref) — remove a marker by the ref id list()/add() handed out. Returns whether it was removed. */
    private static boolean removeMarker(LuaValue ref) {
        if(!ref.isnumber())
            return false;
        MapFile file = mapfile();
        if(file == null)
            return false;
        MapFile.Marker m = markerByRef((long)ref.todouble());
        if(m == null)
            return false;
        file.remove(m);                           // no-ops if already gone; bumps markerseq if it removed one
        return true;
    }

    private static java.awt.Color luaColor(LuaValue t, java.awt.Color dflt) {
        LuaValue r = t.get("r"), g = t.get("g"), b = t.get("b"), a = t.get("a");
        if(!r.isnumber() || !g.isnumber() || !b.isnumber())
            return dflt;
        int ai = a.isnumber() ? clampByte(a.toint()) : 255;
        return new java.awt.Color(clampByte(r.toint()), clampByte(g.toint()), clampByte(b.toint()), ai);
    }
    private static int clampByte(int v) {
        return (v < 0) ? 0 : ((v > 255) ? 255 : v);
    }

    /** Fire MarkersChanged when the DB's markerseq changes (a marker add/remove is not a uimsg — poll it). */
    private static void pollMarkers() {
        MapFile file = mapfile();
        if(file == null)
            return;
        int seq = file.markerseq;
        if(!markersPrimed) {
            markersPrimed = true;
            lastMarkerSeq = seq;                  // prime silently; the initial set is read via markers.list()
            return;
        }
        if(seq != lastMarkerSeq) {
            lastMarkerSeq = seq;
            int count;
            file.lock.readLock().lock();
            try { count = file.markers.size(); }
            finally { file.lock.readLock().unlock(); }
            LuaTable ev = new LuaTable();
            ev.set("count", LuaValue.valueOf(count));
            fire("MarkersChanged", ev);
        }
    }

    // ---- radar (A2: hafen.radar) -----------------------------------------------------------------
    // The minimap icon registry (GobIcon.Settings, GameUI.iconconf) — one "category" per gob-icon kind
    // (a boar, a fir tree, a player, …), each with a show flag (draw it on the minimap) and a notify flag
    // (sound + chat msg when one appears). This is the same registry the in-client "Icon settings" window
    // edits, so our reads/writes are the exact ones it uses (its checkboxes flip set.show/set.notify and
    // call dsave(); we do the same). settings is a Map the loader thread swaps WHOLESALE (it builds a fresh
    // map and assigns it), so a local reference is a stable snapshot to iterate; individual boolean flags
    // may be written on the UI thread (as the checkboxes already do) with no torn read. All access is on the
    // UI thread (addon tick / REPL), matching the settings window. No *Changed event — categories change
    // only as new icon types are seen (rare); read on demand (the A4 precedent for rarely-changing data).

    /** The character's minimap icon registry (GameUI.iconconf), or null before the HUD is up. */
    private static GobIcon.Settings iconconf() {
        GameUI g = gui();
        return (g == null) ? null : g.iconconf;
    }

    /** Snapshots of every radar category (icon setting) in the registry. */
    private static List<LuaValue> radarSnapshots() {
        List<LuaValue> out = new ArrayList<LuaValue>();
        GobIcon.Settings conf = iconconf();
        if(conf == null)
            return out;
        Map<GobIcon.Setting.ID, GobIcon.Setting> m = conf.settings;   // swapped wholesale by the loader → a stable ref
        if(m == null)
            return out;
        for(GobIcon.Setting set : m.values())
            out.add(radarSnapshot(set));
        return out;
    }

    /** One category snapshot: { name, res, show, notify }. */
    private static LuaValue radarSnapshot(GobIcon.Setting set) {
        LuaTable t = new LuaTable();
        t.set("name", LuaValue.valueOf(radarName(set)));
        t.set("res", LuaValue.valueOf(set.id.res));
        t.set("show", LuaValue.valueOf(set.show));
        t.set("notify", LuaValue.valueOf(set.notify));
        return t;
    }

    /** The category's display name (the icon tooltip), falling back to the resource name; never throws. */
    private static String radarName(GobIcon.Setting set) {
        try {
            if(set.icon != null) {
                String nm = set.icon.name();
                if(nm != null)
                    return nm;
            }
        } catch(RuntimeException e) {   // Loading, or a custom mapicon name() that blows up → fall back to res
        }
        return set.id.res;
    }

    /** Flip show (notifyFlag=false) or notify (true) on every category the filter matches; persist if any
     *  actually changed. Returns the number of categories matched. UI thread (like the settings checkboxes). */
    private static int radarSet(LuaValue filter, boolean value, boolean notifyFlag) {
        return radarSetIn(iconconf(), filter, value, notifyFlag);
    }

    /** Testable core of {@link #radarSet}: operates on a given Settings (null-safe), so it can be exercised
     *  headlessly without a live GameUI. */
    static int radarSetIn(GobIcon.Settings conf, LuaValue filter, boolean value, boolean notifyFlag) {
        if(conf == null)
            return 0;
        Map<GobIcon.Setting.ID, GobIcon.Setting> m = conf.settings;
        if(m == null)
            return 0;
        int matched = 0;
        boolean changed = false;
        for(GobIcon.Setting set : m.values()) {
            if(!matches(filter, radarSnapshot(set)))
                continue;
            matched++;
            if(notifyFlag) {
                if(set.notify != value) { set.notify = value; changed = true; }
            } else {
                if(set.show != value) { set.show = value; changed = true; }
            }
        }
        if(changed)
            conf.dsave();   // debounced persist, exactly like the icon-settings checkboxes (andsave -> dsave)
        return matched;
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
