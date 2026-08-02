package io.brodgar.addon;

import haven.AddonWidgets;
import haven.Astronomy;
import haven.Audio;
import haven.BAttrWnd;
import haven.BuddyWnd;
import haven.Buff;
import haven.Button;
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
import haven.Label;
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
import haven.QuestWnd;
import haven.ResDrawable;
import haven.Resource;
import haven.SAttrWnd;
import haven.SkillWnd;
import haven.Speaking;
import haven.Speedget;
import haven.SprDrawable;
import haven.TexI;
import haven.Text;
import haven.TextEntry;
import haven.UI;
import haven.Utils;
import haven.WItem;
import haven.Widget;
import haven.Window;
import haven.WoundWnd;
import haven.render.RenderTree;
import haven.resutil.Curiosity;
import io.brodgar.prof.Prof;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.ThreeArgFunction;
import org.luaj.vm2.lib.TwoArgFunction;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.ZeroArgFunction;

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

    static volatile MapView view;           // live map view (for gob:pos()) — pkg-private: shared hub state
    static volatile UI ui;                  // live UI (for output; set at RemoteUI.init) — pkg-private: shared hub state
    static final List<Addon> addons = new CopyOnWriteArrayList<Addon>();
    static Addon consoleOwner;      // the :lua REPL, as a resource owner (persists across sessions)

    // -- engine runtime state (all touched on the UI thread, except the gob queue) ---------------
    private static AddonRoot addonRoot;                 // the attached tick widget (per session)
    private static OCache.ChangeCallback ocCb;          // strong ref: OCache keeps callbacks weakly
    private static volatile boolean enterWorldPending;  // set off-thread (MapView attach), read on tick
    static volatile boolean reloadPending;      // set by :reload (any thread), applied on the UI tick
    static double clock;                        // seconds accumulated from tick dt (UI thread)
    private static final Queue<GobEvent> gobEvents = new ConcurrentLinkedQueue<GobEvent>();

    // -- widget-tree read mechanism (spec 14): Locator + Adapters + inbound-uimsg update hook -------
    // Adapters read a GameUI widget tree into a Lua snapshot and fire a semantic event on change. The
    // UI.uimsg core tap runs off the UI thread, so it only marks the interested adapter(s) dirty; the
    // tick re-reads + fires on the UI thread (principle P5). Both collections are session-scoped.

    // -- saved variables (spec 1e / D-002 / D-023): hafen.store persisted as JSON under savedata/ ------
    // Per-character vars key on <genus>_<char>, known only once the HUD is up (OnEnterWorld) — captured
    // here and reused on flush so a relog (which rebinds ui before the new GameUI exists) still writes to
    // the OLD character's folder. Account-scope vars need no char and load at addon-load time.

    // -- enabled set + reload (spec 1f-2 / D-005 / D-006): which addons run, persisted client-side --------
    // WoW "apply on reload" model: toggling enable/disable updates a persisted DISABLED set (an addon runs
    // unless its id is in it — so a freshly-installed addon defaults to enabled) and marks changes pending;
    // the change takes effect on the next :reload / login, never live. Stored in the client's own
    // preferences (Utils.getprefsl → under the client folder), NOT per-character.

    // -- soft CPU-budget auto-disable (spec 1f-3 / D-018 layer 2): id -> reason for an addon torn down
    // mid-session by the per-tick CPU watchdog. This is a SESSION action (not the persisted disabled set),
    // surfaced in the AddOns panel and cleared on the next (re)load so the addon gets a fresh start.
    static final Map<String, String> autoDisabledWarn = new ConcurrentHashMap<String, String>();

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
    static void requireActions(Addon owner, String verb) {
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
                AddonRegistry.setEnabled(args[2], true);
                log("addon '" + args[2] + "' enabled (pending — run :reload to apply)");
            } else if((args.length >= 3) && "disable".equals(args[1])) {
                AddonRegistry.setEnabled(args[2], false);
                log("addon '" + args[2] + "' disabled (pending — run :reload to apply)");
            } else {
                AddonRegistry.listAddons();
            }
        });
        // :reload  reload the addon layer from disk (D-005) — no relog. Queued to the UI-thread tick.
        Console.setscmd("reload", (cons, args) -> AddonRegistry.queueReload());
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
        Prof.init();  // 019.1: restore the persisted profiling switch (once per JVM)
        Prof.addonCost(AddonManager::luaNanosThisFrame);   // 019.2: the addons roll-up source
        Prof.addonReset(AddonManager::resetProfiling);     // 019.4: p:reset()/arming clears the per-addon rows too
        for(Addon a : addons)         // fire OnDisable + flush saved vars + drop owned resources
            AddonRegistry.teardown(a);              // (flushes with the OLD charScope, still set from the last session)
        addons.clear();

        clock = 0;
        enterWorldPending = false;
        gobEvents.clear();
        HttpApi.reset();              // N2a: drop stale HTTP completions (their requests were torn down above)
        addonRoot = null;
        ocCb = null;

        StoreApi.resetSession();      // per-char scope + auto-save clock reset for the new session
        reloadPending = false;        // drop any :reload queued against the previous session

        UiApi.resetSession();         // 2b/3a/3b/3c: reset overlay sweep + per-session widget registries
        WorldApi.resetMarkers();      // A1: drop per-session marker maps + re-prime MarkersChanged
        CharApi.resetSession();       // re-register the change-detection adapters

        attachRoot(ui_);              // invisible per-frame tick widget (drives the engine)
        registerOcache(ui_);          // GobAdded/GobRemoved source (marshalled to the UI thread)
        AddonRegistry.loadAll();                    // discover + run addons, fire OnLoad for each
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
                AddonRegistry.reload();
                return;
            }

            // Soft CPU-budget accounting (D-018 layer 2): zero every addon's per-tick Lua time before any
            // handler runs this tick; callLua accumulates into it, enforceSoftBudget() evaluates it at the
            // end. (Skipped on a reload tick, which returns above — its OnLoad/OnEnterWorld are one-offs.)
            // 019.4: this instant is also where the PREVIOUS frame closes — tickLuaNanos accrues through the
            // tick and the draw callbacks that follow it, so right here it holds exactly one whole frame.
            // profRoll() moves it into the addon's "last completed frame" figures before it is cleared, which
            // is why p:addons() never shows a half-accumulated frame and its total matches p:frame().addons.
            // 019.7: `armed` is the MASTER switch, so the ms figures keep rolling across a control frame;
            // `probed` describes the frame being CLOSED (this tick opens the next one), so the category and
            // scope split — which a control frame does not measure — holds its last measured value instead
            // of rolling a row of zeroes in one frame out of every Overhead.PERIOD.
            boolean armed = Prof.armed(), probed = prevProbed;
            prevProbed = Prof.on;
            for(int i = 0, n = addons.size(); i < n; i++) {
                Addon a = addons.get(i);
                if(armed)
                    a.profRoll(probed);
                a.tickLuaNanos = 0L;
            }
            Addon co = consoleOwner;
            if(co != null) {                 // the REPL is not an addon (no watchdog), but its Lua time is
                if(armed)                    // real frame cost and it owns the scopes of a :lua snippet
                    co.profRoll(probed);
                co.tickLuaNanos = 0L;
            }

            // 1. Gob spawn/despawn captured on network/loader threads → dispatch on the UI thread.
            GobEvent ge;
            while((ge = gobEvents.poll()) != null)
                fireGob(ge.added ? "GobAdded" : "GobRemoved", ge.gob.id);

            // 1a. HTTP results (N2a): a pool worker finished a request → deliver its res table to the addon's
            //     callback on the UI thread (armed + isolated, like every other event). A cancelled/torn-down
            //     request (dead) is discarded — its callback never fires (D-037 §3.3). Draining a request frees
            //     an in-flight slot, so re-run the per-addon scheduler to launch any queued request.
            HttpApi.drainHttp();

            // 1b. Widget-tree adapters flagged dirty by an inbound uimsg → re-read + fire the semantic
            //     event, now on the UI thread. (Marked off-thread in onUimsg; drained here.) Then the
            //     per-tick poll for changes the uimsg tap can't see (buff add/remove is widget
            //     create/cdestroy on the Bufflist, not a uimsg — spec 14). Refresh before poll so a
            //     brand-new buff surfaces as a single BuffAdded (with its content already applied),
            //     not BuffChanged-then-BuffAdded.
            CharApi.refreshTreeAdapters();
            CharApi.pollTreeAdapters();

            // 1c. Replacements (032.1): per-tick check for the server destroying a window an addon replaced with
            //     widget:replace(view) — the substitution ends, the window and its toggle go back under the one
            //     rule, and the stand-in view is destroyed with it. Gated on the same volatile the toggle seam
            //     reads, so a client that hides nothing pays one read.
            UiApi.pollReplaced();

            // 1c'. Watched containers (029.3): per-tick diff of every widget an addon subscribed to, for item
            //      add/remove (a WItem create/cdestroy, not a uimsg — like the buff/meter adapters) and for the
            //      widget's own death (→ onDestroy). hasSub-gated: a widget nobody listens to is never polled.
            UiApi.pollWatches();

            // 1c''. Selector subscriptions (030.2): re-check the widgets placed in the last few ticks whose
            //       [title=]/[res=] refiner had not resolved yet (a caption arrives by uimsg, a tick after
            //       placement), then fire `disappear` for every tracked widget that has left the tree. Gated on
            //       somebody having subscribed — an idle client pays one isEmpty().
            UiApi.pollSelectorWatches();

            // 1d. Map markers (A1): fire MarkersChanged when the on-disk map DB's markerseq changes (a
            //     marker add/remove is not a uimsg — the server pushes SMarkers via markobj, the player/
            //     addon adds PMarkers, and segment merges re-key them; all bump markerseq). Global event.
            WorldApi.pollMarkers();

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
                    StoreApi.restorePerChar();     // now <genus>_<char> is known → load per-char saved vars BEFORE
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
            UiApi.sweepGobOverlays();
            UI u = ui;
            if((u != null) && UiApi.anyHudOverlays())
                u.drawafter(UiApi.hudAfterDraw);

            // 5. Throttled auto-save of saved variables (mirrors GameUI's window-position saves). Covers
            //    an unclean exit; a relog also flushes via teardown. flush() skips unchanged files, so
            //    this is cheap when nothing changed. On the UI thread → no races reading the Lua tables.
            StoreApi.autosave(clock);

            // 6. Soft per-tick CPU budget (D-018 layer 2): auto-disable an addon that has been over budget
            //    for too many consecutive ticks — a sustained runaway the hard per-call cap doesn't catch.
            enforceSoftBudget();
        } catch(RuntimeException e) {
            log("tick error: " + e);
        }
    }

    /**
     * Total Lua CPU time (ns) charged to addons during the current frame — the {@code addons} roll-up in
     * {@code p:frame()} (spec 019, task 019.2). This is the D-018 accounting {@link #callLua} already keeps,
     * read a second time rather than measured a second time: {@code tickLuaNanos} is zeroed at the top of
     * each tick and accrues through the tick AND the draw callbacks that follow it, so at end-of-frame it
     * holds exactly this frame's cost. Read by {@code Prof} on the UI thread, through the supplier registered
     * in {@link #init} (the profiling engine must not depend on the addon system). Indexed rather than
     * for-each: no iterator allocation on a per-frame path. 019.4 keeps this as the roll-up and adds the
     * per-addon breakdown behind it ({@link #profOwners}), reading the same {@code tickLuaNanos} so the
     * {@code total} row of {@code p:addons()} and the {@code addons} figure of {@code p:frame()} can never
     * disagree — including the {@code :lua} REPL, whose Lua time is frame cost like any other even though the
     * watchdog exempts it.
     */
    static long luaNanosThisFrame() {
        long sum = 0;
        for(int i = 0, n = addons.size(); i < n; i++)
            sum += addons.get(i).tickLuaNanos;
        Addon c = consoleOwner;
        if(c != null)
            sum += c.tickLuaNanos;
        return sum;
    }

    /**
     * Every Lua owner {@code p:addons()} reports a row for: the loaded addons plus the {@code :lua} REPL
     * (which owns the scopes of a console snippet). Built on demand, at snapshot time only — never per frame.
     */
    static List<Addon> profOwners() {
        List<Addon> out = new ArrayList<Addon>(addons);
        Addon c = consoleOwner;
        if(c != null)
            out.add(c);
        return out;
    }

    /** Clear every per-addon profiling figure — registered with {@code Prof}, so arming and {@code p:reset()} hit it. */
    static void resetProfiling() {
        prevProbed = false;   // 019.7: nothing measured yet, so the next roll has no split to carry over
        for(Addon a : profOwners())
            a.profReset();
    }

    /**
     * Whether the frame the next {@link #tick} closes was one the category probes were armed for (019.7) —
     * false on a control frame. Written at the top of every tick, read one tick later, UI thread only.
     */
    private static boolean prevProbed = false;

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
        AddonRegistry.teardown(a);
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
                callLua(a, Addon.C_TIMER, t.fn);
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
        CharApi.dispatchUimsg(w, msg);
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
        return HookApi.dispatchAction(sender, msg, args);
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
        return HookApi.dispatchMessage(target, msg, args);
    }

    /**
     * The global-hotkey seam (spec 07 "Input" / Phase 2e-2) — called from {@link AddonRoot#globtype} for every
     * {@link Widget.GlobKeyEvent}. {@link UI#keydown} fires that event only after an unconsumed focused
     * {@code KeyDownEvent}, so a hotkey never fires while a text field has focus; the event then walks the whole
     * widget tree calling {@code globtype}. Runs the handler of the first addon hotkey ({@code keybindings:register})
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
        return HookApi.dispatchKey(ev);
    }

    /**
     * The V2 ghost/entity click seam — called from {@code haven.MapView} when a virtual (client-only) gob is
     * clicked. Finds the owning addon world-entity and fires its {@code onClick} + the owner-scoped event.
     * Delegates to {@link RenderApi}, which owns the world-entity registry.
     */
    public static boolean onGhostClick(Gob cg, int button, Coord2d mc) {
        return RenderApi.onGhostClick(cg, button, mc);
    }

    /**
     * The <b>widget-placement seam</b> — called from the {@code UI.AddWidget.run} core edit, right after
     * {@code pwdg.addchild(wdg, pargs)}, i.e. the first COMPLETE moment: the widget is in the tree, so a
     * {@link Selector} can be applied to it. <b>One consumer since 032.2</b>: the 030.2 selector subscriptions
     * ({@code hafen.ui.on}), which see the live widget itself. The other two are gone — {@code
     * hafen.ui.onWidgetCreate} with 030.2, and {@code hafen.ui.replace}'s {@code {id, type, place, caption,
     * parentType}} descriptor (D-024) with 032.2 — so the seam no longer needs the parent or the placement args,
     * and the {@code UI.NewWidget.run} edit that recorded the server type string for that descriptor is gone too.
     *
     * <p><b>Threading.</b> Reached only from inside {@code AddWidget.run}'s {@code synchronized(ui)} block (on a
     * Loader thread, under the monitor tick/draw hold), so the Lua raised here never races other Lua — the same
     * discipline as {@link #onMessage} (no {@code holdsLock} guard needed). The fast path (nobody subscribing)
     * returns immediately, so an uninterested client is unaffected even though every widget placement passes here.
     */
public static void onWidgetPlaced(int id, Widget wdg) {        UiApi.onWidgetPlaced(id, wdg);    }

    /**
     * The <b>window-toggle seam</b> (031.1) — called from {@code haven.AddonWidgets}, which is where
     * {@code GameUI.togglewnd} and {@code GameUI.wndstate} reach the addon layer. A native window an addon hid
     * with {@code widget:hide()} is a window that addon <b>owns</b>, toggle included: {@link #toggleWnd} answers
     * whether the click was handled (so the client leaves the window alone), {@link #wndState} what the menu
     * checkbox's tick should say ({@code null} = not owned, read the window as usual).
     *
     * <p><b>Threading.</b> Both are reached on the UI thread — a click, or the checkbox's per-frame
     * {@code state()} supplier — and neither raises Lua. The fast path (nobody hid anything) is one volatile
     * read and allocates nothing, so an uninterested client is unaffected by a per-frame poll on six checkboxes.
     */
    public static boolean toggleWnd(Window wnd) {        return UiApi.toggleWnd(wnd);    }

    /** @see #toggleWnd */
    public static Boolean wndState(Window wnd) {         return UiApi.wndState(wnd);     }

    // The widget-targeting descriptor {id, type, place, caption, parentType} (D-024) is GONE (032.2). It was the
    // argument of replace{match=fn} and nothing else once 030.2 hard-cut the onWidgetCreate observer that shared
    // it; with hafen.ui.replace deleted there is exactly one vocabulary for "which window" left — the Selector.

    /** Re-read each dirty adapter and fire its semantic event (UI thread, drained from the tick). */

    // ------------------------------------------------------------- event dispatch

    /** Fire an event to every owner (all addons + the REPL). */
    static void fire(String event, LuaValue... args) {
        for(Addon a : addons)
            fireTo(a, event, args);
        Addon c = consoleOwner;
        if(c != null)
            fireTo(c, event, args);
    }

    /**
     * Fire a gob event ({@code GobAdded}/{@code GobRemoved}) whose payload is a <b>Gob object</b> (D-044). Unlike
     * {@link #fire} the payload cannot be shared: interning is per-addon (D-045), so each owner gets <i>its</i>
     * handle for the id — minted only when that owner actually subscribes, so a busy spawn stream costs nothing
     * for the addons that don't listen. On {@code GobRemoved} the gob is already gone, so only {@code :id()}
     * answers — an addon that needs the name must have indexed it on {@code GobAdded}.
     */
    static void fireGob(String event, long id) {
        for(Addon a : addons) {
            if(hasSub(a, event))
                fireTo(a, event, LuaGob.of(a, id));
        }
        Addon c = consoleOwner;
        if((c != null) && hasSub(c, event))
            fireTo(c, event, LuaGob.of(c, id));
    }

    /**
     * Fire {@code KinChanged} whose payload is an array of <b>Kin objects</b> (020.3), the roster in the Kin
     * window's sort order. Same shape as {@link #fireGob}: interning is per-addon (D-045) so the payload cannot
     * be shared, and the array is minted only for an owner that actually subscribes — a login, where the roster
     * streams in entry by entry, costs nothing for the addons that don't listen.
     *
     * <p>Change <i>detection</i> is unchanged and stays in {@code CharApi}'s kin adapter: the snapshot diff, NOT
     * {@code BuddyWnd.serial} (which does not bump on an online/offline flip). The ids arrive already diffed.
     */
    static void fireKin(int[] ids) {
        for(Addon a : addons) {
            if(hasSub(a, "KinChanged"))
                fireTo(a, "KinChanged", kinPayload(a, ids));
        }
        Addon c = consoleOwner;
        if((c != null) && hasSub(c, "KinChanged"))
            fireTo(c, "KinChanged", kinPayload(c, ids));
    }

    /**
     * Fire {@code ActionbarChanged} whose payload is a single <b>Slot object</b> (021.2) — the slot that just
     * changed. Same shape as {@link #fireGob}/{@link #fireKin}: interning is per-addon (D-045) so the payload
     * cannot be shared, and it is minted only for an owner that actually subscribes — the belt is polled every
     * tick and a login sets all 144 slots in a burst, so the {@code hasSub} gate is what keeps that free for the
     * addons that don't listen.
     *
     * <p>Change <i>detection</i> stays in {@code CharApi}'s actionbar adapter (the per-index snapshot diff,
     * cooldown excluded); the index arrives already diffed. The Slot re-reads live, so a handler that stashes it
     * keeps tracking that slot — including going {@code :empty()} when it is cleared again.
     */
    static void fireSlot(int index) {
        for(Addon a : addons) {
            if(hasSub(a, "ActionbarChanged"))
                fireTo(a, "ActionbarChanged", LuaSlot.of(a, index));
        }
        Addon c = consoleOwner;
        if((c != null) && hasSub(c, "ActionbarChanged"))
            fireTo(c, "ActionbarChanged", LuaSlot.of(c, index));
    }

    /**
     * Fire a buff event ({@code BuffAdded}/{@code BuffRemoved}/{@code BuffChanged}) whose payload is a single
     * <b>Buff object</b> (025.2) — the buff that was just added, dropped or updated. Same shape as
     * {@link #fireGob}/{@link #fireKin}/{@link #fireSlot}: interning is per-addon (D-045) so the payload cannot
     * be shared, and it is minted only for an owner that actually subscribes — a login brings the whole bar up
     * in one burst, so the {@code hasSub} gate is what keeps that free for the addons that don't listen.
     *
     * <p>Change <i>detection</i> stays in {@code CharApi}'s buff adapter (the per-buff snapshot diff); the
     * widget arrives already diffed. The Buff re-reads live, so a handler that stashes one keeps tracking it.
     * On {@code BuffRemoved} the widget is unlinked but NOT cleared, so the payload still answers
     * {@code :res()}/{@code :name()}/… and reports {@code :exists()} false — which is the whole reason the
     * object can replace the snapshot this event used to carry.
     */
    static void fireBuff(String event, Buff b) {
        for(Addon a : addons) {
            if(hasSub(a, event))
                fireTo(a, event, LuaBuff.of(a, b));
        }
        Addon c = consoleOwner;
        if((c != null) && hasSub(c, event))
            fireTo(c, event, LuaBuff.of(c, b));
    }

    /**
     * Fire a meter event ({@code MeterAdded}/{@code MeterRemoved}/{@code MeterChanged}) whose payload is a
     * single <b>Meter object</b> (027.2) — the HUD bar that just appeared, went away or changed. Same shape as
     * {@link #fireGob}/{@link #fireKin}/{@link #fireSlot}/{@link #fireBuff}: interning is per-addon (D-045) so
     * the payload cannot be shared, and it is minted only for an owner that actually subscribes — the meters are
     * polled every tick and a login brings the whole slot up in one burst, so the {@code hasSub} gate is what
     * keeps that free for the addons that don't listen.
     *
     * <p>Change <i>detection</i> stays in {@code CharApi}'s meter adapter (the per-meter segment diff, value AND
     * colour); the widget arrives already diffed. The Meter re-reads live, so a handler that stashes one keeps
     * tracking that bar. On {@code MeterRemoved} the widget is unlinked but NOT cleared, so the payload still
     * answers {@code :res()}/{@code :value()}/… and reports {@code :exists()} false.
     */
    static void fireMeter(String event, IMeter m) {
        for(Addon a : addons) {
            if(hasSub(a, event))
                fireTo(a, event, LuaMeter.of(a, m));
        }
        Addon c = consoleOwner;
        if((c != null) && hasSub(c, event))
            fireTo(c, event, LuaMeter.of(c, m));
    }

    /** One owner's {@code KinChanged} payload: its own interned Kin objects, in roster order. */
    private static LuaValue kinPayload(Addon owner, int[] ids) {
        LuaTable t = new LuaTable();
        for(int i = 0; i < ids.length; i++)
            t.set(i + 1, LuaKin.of(owner, ids[i]));
        return t;
    }

    /** Does {@code a} have a live subscription to {@code event}? (Gates minting a per-addon event payload.) */
    private static boolean hasSub(Addon a, String event) {
        for(Sub s : a.subs) {
            if(s.alive && s.event.equals(event))
                return true;
        }
        return false;
    }

    /** Fire an event to a single owner's matching subscriptions. */
    static void fireTo(Addon a, String event, LuaValue... args) {
        for(Sub s : a.subs) {
            if(!s.alive) {
                a.subs.remove(s);
                continue;
            }
            if(s.event.equals(event))
                callLua(a, Addon.C_EVENT, s.fn, args);
        }
    }

    /**
     * Call into Lua with full error isolation (a Lua error never escapes the engine step) and return its
     * result varargs (or {@link LuaValue#NIL} on error). Most callers (events/timers) ignore the return;
     * the custom-UI input forwards ({@link AddonWidget}) read {@code .arg1().toboolean()} for "consume".
     * Package-visible so {@link AddonWidget} (same package) routes its draw/tick/mouse callbacks through the
     * one watchdog-armed, CPU-accounted choke point.
     */
    static Varargs callLua(Addon owner, int cat, LuaValue fn, LuaValue... args) {
        long t0 = System.nanoTime();
        try {
            Sandbox.arm(owner.env);   // reset the watchdog's instruction budget for this callback (D-018)
            return fn.invoke((args.length == 0) ? LuaValue.NONE : LuaValue.varargsOf(args));
        } catch(LuaError e) {
            log(owner, "handler error: " + e.getMessage());
            trace(e.getCause());
        } catch(RuntimeException e) {
            log(owner, "handler error: " + e);
            trace(e);
        } finally {
            long d = System.nanoTime() - t0;
            owner.tickLuaNanos += d;   // soft per-tick CPU-budget accounting (D-018 layer 2)
            // 019.4: the SAME measurement, split by what the addon was doing. Deliberately an addition
            // inside this finally and not a second timer: tickLuaNanos above must stay byte-for-byte what
            // it was, or the watchdog would start auto-disabling at a different point. When profiling is
            // off this is one branch on a static field.
            if(io.brodgar.prof.Prof.on) {
                owner.catNanos[cat] += d;
                owner.catCalls[cat]++;
                io.brodgar.prof.Overhead.hAddon++;   // 019.7: one probe hit, for the modelled addon-tier cost
            }
        }
        return LuaValue.NIL;
    }

    /**
     * Print the Java stack behind a handler error to <b>stdout only</b> (never to chat). A Lua error carries
     * its own file:line and needs nothing more, but when the failure is a Java exception thrown inside a
     * bridge call, the Lua message says only where the addon <i>called in</i> — the engine-side site, which is
     * the one that matters, was being discarded. Errors stay isolated exactly as before; this only stops
     * throwing away the evidence.
     *
     * <p>{@link Loading} is excluded: in this client "the resource isn't here yet" is <b>control flow</b>, not a
     * fault — it is thrown routinely while the map and resources stream in, and a stack for each one buries the
     * real errors it exists to surface. The one-line handler message still names it.
     */
    private static void trace(Throwable t) {
        if((t != null) && !(t instanceof LuaError) && !(t instanceof Loading))
            t.printStackTrace();
    }

    // ------------------------------------------------------------- the hafen facade

    /** Install the stable {@code hafen.*} facade into an owner's Lua env (addons and the REPL). */
    static void installHafen(Globals g, final Addon owner) {
        LuaTable hafen = new LuaTable();

        // hafen.gob(id) — the Gob CLASS (D-044): the factory mints an interned Gob OBJECT for an id and
        // gob:pos()/:name()/:health()/… read it. A Gob wraps only the id, so every method re-resolves →
        // always fresh, nil once the gob is gone (:id() still answers). Identity is by per-addon weak
        // interning (D-045), so hafen.gob(id) == hafen.gob(id) and seen[gob] works. The flat
        // hafen.gob.*(ref) table and the "player"/"me"/"partyN" GobRef tokens are GONE (hard cut, no
        // shim, D-013): the player's gob is hafen.player():gob().
        hafen.set("gob", LuaGob.factory(owner));

        // hafen.menugrid(key) — the ACTION MENU (the 4x4 "scm" grid) as Pagina OBJECTS: the catalogue of
        // everything this character can do, in the grid's own order and category tree. Arity is the verb:
        // hafen.menugrid() is the whole catalogue (with :find/:roots/:list), hafen.menugrid(key) one entry.
        // The key is always a STRING and splits by SHAPE — a "/" makes it a resource name (the identity),
        // anything else a display name (a search convenience, not unique) — and a miss is plain nil. There is
        // no addressing by position: the catalogue grows on every discovery. Resource-backed reads are
        // Loading-guarded, so a scan right at OnEnterWorld may be short and fills in sub-second.
        hafen.set("menugrid", LuaPagina.factory(owner));

        // hafen.world.* — enumerate gobs as Gob OBJECTS (count() is still a number). nearest/within measure
        // from the player and skip the player's own gob; a function filter is called with a Gob, a string
        // filter still matches its resource name. Prefer GobAdded/GobRemoved over per-frame scanning.
        WorldApi.installWorld(hafen, owner);

        // hafen.map.* — terrain reads. Positional args are WORLD coords (matching gob:pos());
        // convert with worldToTile/tileToWorld/tileToGrid. Grid-backed reads swallow Loading (the map
        // for that spot isn't here yet) → nil. Grid ids are 64-bit → exposed as decimal STRINGS so the
        // persistent/shareable anchor round-trips exactly (Lua numbers are doubles; see gridPos).
        WorldApi.installMap(hafen, owner);

        // hafen.markers.* — client-side map markers (A1), read/added/removed against the client's on-disk
        // map DB (MapFile, owned by the map window / corner minimap — the same instance). Two kinds:
        // PLAYER markers (user pins: a name + colour) and SYSTEM markers (server/quest pins: a name +
        // icon). A snapshot is { id, name, type ("player"|"system"), seg (id string), tc={x,y} (the
        // segment tile coord — the PERSISTENT anchor that survives a relog, coverage-gaps C4),
        // color={r,g,b,a}+onmap (player) | icon (system), and x,y (world) + dist (from the player) which
        // are SESSION-LOCAL, present only when the marker is in the player's current segment }. add()
        // creates a PLAYER marker and persists it; remove() takes a ref from list()/add(). The DB streams
        // in a beat after enter-world (nil/empty until then — read on a timer); MarkersChanged fires on any
        // change. Coords are WORLD units (matching gob:pos()/hafen.map), converted to the persistent
        // segment anchor at add time — there is no global position (anchor on grid ids / segment tc — C4).
        WorldApi.installMarkers(hafen, owner);

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
        WorldApi.installRadar(hafen, owner);

        // hafen.player() — the Player object, purely the composition anchor for hafen.player():gob() (D-046):
        // position/health/moving/… of the player come from that Gob, and Player deliberately forwards NOTHING
        // (player:pos() alongside player:gob():pos() is exactly the dual style D-013 forbids). :gob() is nil
        // before entering the world. :name() is the LOCAL character name (GameUI.chrid); other players' display
        // names are not reliably available. :worldToScreen is MAP-VIEW-relative pixels.
        CharApi.installPlayer(hafen, owner);

        // hafen.time.* — game clock + astronomy. clock() is always available; the astronomy readers are
        // nil until the first "astro" update lands (Glob.ast is nil before then).
        WorldApi.installTime(hafen, owner);

        // hafen.sound(name) — a CALLABLE-ONLY namespace (D-056) over one interned Sound object per resource
        // name: :res() / :play([volume]) -> self / :info(). The flat hafen.sound.play is GONE (D-013 hard cut,
        // 024.1). Volume is an ARGUMENT of the play, never entity state — the Sound is interned and shared.
        // The resource resolves OFF the UI thread (loader.defer, mirroring GobIcon.resnotif) so a not-yet-
        // loaded resource never throws Loading into Lua. Client-bundled names resolve locally ("sfx/msg").
        hafen.set("sound", LuaSound.factory(owner));

        // hafen.music is DELIBERATELY ABSENT (024.3, maintainer 2026-08-01). haven.Music is the client's MIDI
        // player, driven by exactly one thing — RootWidget's "bgm" server message — and this server never
        // sends it: there is no MIDI content, so the whole subsystem is dead weight and an API over it would
        // answer nil forever. What players actually hear as "music" is an ActAudio.Ambience loop on the `amb`
        // channel (published by world resources, governed by Options > Audio > "Ambient volume"), which is a
        // render-tree node with a lifetime, NOT a clip handle — its own feature if ever wanted, and explicitly
        // out of scope here. The audio section is hafen.sound and nothing else.

        // hafen.items is GONE (029.3, hard cut D-013). Items are a RELATION on their container now:
        // hafen.ui.inventory():items() / hafen.ui.equipment():items() / hafen.ui.hand(), and widget:items() answers
        // on ANY container — a chest, a cupboard — with its window visible and interactive. The Item SHAPE is
        // unchanged (name/res/num/wear/pos + the `handle` the gated hafen.act.item(item, verb) takes, D-022), and it
        // is still CharApi.itemSnapshot that produces it.

        // hafen.char.* — character attributes (Glob.getcattr; a zero-info entry is reported as nil),
        // plus learning points (CharWnd.exp) and encumbrance/weight (CharWnd.enc) — public live fields
        // on the character window (created hidden at login). attrs() returns the nine base attributes
        // that have data, keyed by name. ("char" is a Java keyword → the local is named "chr".)
        CharApi.installChar(hafen, owner);

        // hafen.study.* — the study window (curiosities being studied), via the widget-tree mechanism
        // (1d-3). slots() = the curiosities, each {res,name,lp,attention,cost,time,progress?}; summary()
        // = the live totals {lp,attention,cost}. Both empty/nil until the character sheet's "Abilities"
        // (sattr) tab streams in, a beat after enter-world. Subscribe to StudyChanged (fired per-tick
        // when the slots change — an add/remove or study data resolving), not per frame.
        CharApi.installStudy(hafen, owner);

        // hafen.party.* — the party roster (Glob.party). Members are ordered by Member.seq. A PartyMember is
        // DERIVED: id=gobid, x,y=getc() (live gob pos if in view, else last-known), color={r,g,b,a},
        // leader=(member==party.leader). There is NO name field for party members (a client/protocol
        // limitation). A member's gob is hafen.gob(m.id) until Party itself migrates to OOP.
        CharApi.installParty(hafen, owner);

        // hafen.kin — the kin/buddy roster (A6), read from the Kin window (GameUI.buddies, a BuddyWnd — the
        // same list the in-client Kin tab shows). CALLABLE-ONLY since 020-kin-oop (D-013's hard cut: the flat
        // table of fields is gone, indexing the namespace reads as plain nil). Arity is the verb: hafen.kin()
        // = the roster, a fresh array of interned Kin objects in the window's sort order (plus :find(nameOrId)
        // / :list([filter]) / the gated :add(secret) on its metatable); hafen.kin(id) / hafen.kin(name) = one
        // Kin. A Kin wraps only the buddy id and re-resolves through buddywnd().find(id) every call (D-012), so
        // it tracks renames/regroups/online flips; see LuaKin. Subscribe to KinChanged (a Kin[] payload, minted
        // per subscribing addon by fireKin) for a kin added/removed, renamed/regrouped, or flipping
        // online/offline. The GATED verbs (requireActions) drive BuddyWnd.Buddy's own methods (D-009):
        //   roster:add(secret) — kinning needs the other player's HEARTH SECRET (wdgmsg("bypwd", secret), the
        //                  Kin window's "Add kin" field); there is no add-by-NAME message.
        //   kin:endkin()   = END KINSHIP (Buddy.endkin) — ends the kinship; the kin STAYS in the list, now
        //                  merely memorized (un-kinned). The "End kinship" petal, shown while the kin is active.
        //   kin:forget()   = FORGET (Buddy.forget) — drops a memorized kin from the list entirely. The "Forget"
        //                  petal, shown once un-kinned. To fully remove an ACTIVE kin: endkin(), then forget().
        // Both send the same wdgmsg("rm", id); the SERVER advances the state (active → memorized → gone), exactly
        // as clicking the two petals in turn does. kin:rename(name)=wdgmsg("nick"), kin:setGroup(g)=wdgmsg("grp")
        // with g validated 0..254 (the range the SERVER accepts; the client only draws 8 colours).
        CharApi.installKin(hafen, owner);

        // hafen.speed.* — movement speed (A7), read from the speed selector widget (Speedget: the four-way
        // crawl/walk/run/sprint toggle at the bottom of the HUD). get() returns the CURRENT speed as 0..3
        // (0=crawl 1=walk 2=run 3=sprint), or nil if the widget isn't up yet. max() returns the highest
        // speed currently SELECTABLE (0..3) — speeds 0..max() are available, higher ones are disabled (e.g.
        // sprint locked); nil if not up. name([n]) returns the display name of speed n (default = current;
        // from the widget's own tooltips), or nil. set(n) selects speed n (0..3) — the GATED write verb (4g,
        // requireActions): it drives the client's own Speedget.set (wrap-not-reimplement, D-009 → wdgmsg("set",
        // n)), exactly what clicking/hotkeying that speed does. No SpeedChanged event: speed is read on demand
        // (the classic use is a speed-toggle keybind that reads get() then sets), like the other gap surfaces.
        ActApi.installSpeed(hafen, owner);

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
        ActApi.installCraft(hafen, owner);

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
        CharApi.installQuests(hafen, owner);

        // hafen.wounds.* — the character's wounds (A9-2), read from the character sheet's "Health &
        // Wounds" tab (WoundWnd, reached via CharWnd.wound — created hidden at login but live, so wounds
        // read without ever opening the window). list([filter]) returns wound snapshots {id, name, res,
        // severity, parentid, level}: wounds form a TREE (parentid = the parent wound's id, -1 = a root
        // wound; level = the client's computed tree depth), and severity is the magnitude string the
        // client shows beside the wound (the highest-priority QuickInfo — content-defined, usually the
        // wound's number, NOT seconds; omitted while it Loads). filter is the canonical nil=all / name-
        // substring / predicate. has(needle) tests whether any wound's name/res contains needle (like
        // hafen.buff(needle)). Subscribe to WoundChanged (the wound set or a severity changed; payload = the new
        // list). Read-only — there is no wound action tier (wounds heal by playing / tending).
        CharApi.installWounds(hafen, owner);

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
        CharApi.installFight(hafen, owner);

        // hafen.buff — the active buffs (GameUI.buffs → Buff widgets), via the widget-tree mechanism
        // (1d-2), CALLABLE-ONLY since 025-buffs-oop: hafen.buff() is the active buffs as a 1-based array of
        // Buff objects in bar order, hafen.buff(needle) the FIRST one whose res or name contains that
        // substring (the old has(), now handing back the object; nil on a miss). Reads on the object, live
        // per call: :res()/:name()/:amount()/:duration() (0..1 fractions from resource-published ItemInfo,
        // often nil, NOT seconds — :duration() is the radial meter, i.e. how much of the buff's run is
        // left; the action bar calls the same meter a cooldown because there it is one)/:number()/:exists()/:info() (the old flat snapshot). A buff fading out
        // after removal is excluded (:exists() false) but still READS — Widget.destroy() does not clear it —
        // which is what makes a stashed BuffRemoved payload useful. Subscribe to BuffAdded/BuffRemoved/
        // BuffChanged (add/remove detected per-tick; content changes on the buff's "ch"/"tt" uimsg) — since
        // 025.2 all three carry the Buff OBJECT (fireBuff), not a snapshot table.
        CharApi.installBuffs(hafen, owner);

        // hafen.meter — the HUD's meter bars (GameUI's `place == "meter"` slot → IMeter widgets), via the
        // widget-tree mechanism (1d-1), CALLABLE-ONLY since 027-meters-oop: hafen.meter() is EVERY HUD meter
        // as a 1-based array of Meter objects in HUD order, hafen.meter(needle) the FIRST one whose res name
        // contains that substring (nil on a miss). There is no hp/stamina/energy triple: the slot takes any
        // number of meters and a meter is identified by its SERVER-published bg resource name, so "hp" is a
        // substring that happens to hit a bar on this server, not a key the code knows — :res() is how to list
        // the real ones off a live client. Reads on the object, live per call: :res()/:index() (1-based HUD
        // position)/:value() (the first segment, 0..1 — what vitals() used to return)/:color() ({r,g,b,a}
        // 0..255)/:segments() (the whole multi-segment bar)/:exists()/:info() (the snapshot). A destroyed meter
        // still READS — Widget.destroy() does not clear it — but reports :exists() false. The bars stream in a
        // beat after enter-world, so hafen.meter() is legitimately empty for a moment. Subscribe to
        // MeterAdded/MeterRemoved (the bars streaming in / a meter being destroyed, detected per-tick) and
        // MeterChanged (the server's "set"/"col" uimsg, fired only on a real value-OR-colour change) — all
        // three carry the Meter OBJECT (fireMeter), so MeterAdded is the honest "the bars are up" signal.
        // (hafen.player():vitals() and VitalsChanged are GONE.)
        CharApi.installMeters(hafen, owner);

        // hafen.actionbar — the action bar / hotbar (the engine calls it the "belt": GameUI.belt, a
        // BeltSlot[144]), via the widget-tree mechanism (1d-4), CALLABLE-ONLY since 021-actionbar-oop:
        // hafen.actionbar(n) is the Slot at the RAW 0-based game index 0..143 (out of range throws),
        // hafen.actionbar() the 1-based array of all 144 (the iteration view — same interned objects, and
        // slot:index() is the game index). Reads on the object, live per call: :res()/:name()/:cooldown()
        // (0..1, a pagina action's meter — ability slots only, NOT seconds)/:empty()/:info() (the old flat
        // snapshot). Subscribe to ActionbarChanged{slot} (fired per-tick when a slot's content changes — a
        // set/clear/drag or its data resolving; the payload is that Slot). slot:use([mods]) is the GATED write verb (4g,
        // requireActions) — exactly a LEFT-click on that action-bar button (GameUI belt act →
        // wdgmsg("belt", n, …)); mods is an optional modifier bitfield (0 default; Shift=1 Ctrl=2 Alt=4,
        // matching the keybind syntax). A ground-targeted ability then enters targeting mode (as clicking
        // the button does) — supply the target with the MapView verbs.
        CharApi.installActionbar(hafen, owner);

        // hafen.act.* — the GATED write-actions surface (spec 12 / D-010 / D-025 / D-027; D-028), the ONLY part of
        // hafen.* that DRIVES the character: it sends player-action wdgmsgs to the server. Everything else observes;
        // this acts. A verb runs only when THIS addon declared the "actions" permission in its manifest (else
        // requireActions throws a guiding error) — a PER-ADDON permission (D-028: no global master switch; the tier
        // is always available at the system level). The user opts in per addon: a write addon is disabled by default
        // and enabling it goes through the AddOns-panel consent dialog (slice 4c), so a running addon is one the user
        // permitted. It stays server-authoritative: an addon can only send what a player click could send.
        //   enabled()   -> bool; is THIS addon allowed to act (did it declare the "actions" permission)? Reports
        //                  WITHOUT throwing, so an addon can adapt (no pcall needed).
        //   moveTo(x,y) -> walk the character to a WORLD position (the same coords gob:pos() returns). This is
        //                  exactly the MapView "click" a left-click on that ground spot sends; the screen coord it
        //                  carries is a dummy (the current mouse pos), like MiniMap.mvclick when you click the
        //                  minimap to walk. Off-screen destinations are fine (the server uses the world coord).
        //   clickGob/useItemOn/place/select (4d) -> the rest of the MapView action verbs; all send a Widget.wdgmsg
        //                  from the MapView, sharing moveTo's coord encoding (world → Coord via moveClickCoord; the
        //                  dummy pc). raw(target,msg,…) is the escape hatch (send any wdgmsg from a bound widget).
        // Later Phase-4 slices add menu/flower/item + the per-subsystem gated verbs (speed.set, craft.make,
        // actionbar.use, kin.*); they all share this same gate (requireActions(owner, …)).
        ActApi.installAct(hafen, owner);

        // hafen.ui — custom client-side UI (spec 07, Phase 2a). window(opts) = a draggable, titled window;
        // widget(opts) = a bare rectangle (no chrome). opts: size={w,h}, pos={x,y}, parent="root"|"gameui",
        // title (window only), and callbacks onDraw(g,w,h) / onTick(dt) / onClick(x,y,button) / onMouseUp /
        // onMouseMove(x,y) / onWheel(x,y,amount) / onClose (window). Returns a handle:
        //   :move(x,y)  :show()  :hide()  :visible()  :pack()  :size(w,h)  :destroy()
        // The widget is bridge-owned (P2) and torn down on reload/disable. Client-side only: it cannot
        // wdgmsg the server (that is hafen.act, Phase 4). See AddonWidget for the callback plumbing.
        UiApi.installUi(hafen, owner);

        // hafen.asset(path) — the ONE loader for the files THIS addon ships (spec 028-asset-loader). A CALLABLE
        // namespace (D-056): hafen.asset(path) is one interned, typed handle; hafen.asset() is the array of the
        // addon's live assets. Dispatch is by EXTENSION — .png/.jpg/.jpeg/.gif/.bmp = an image (draw it with
        // g:image / stand it with hafen.render.sprite), .ttf/.otf = a font (hafen.font.setFont / window{font=} /
        // g:text{font=}), .glb/.gltf = a glTF mesh (hafen.render.object) — and anything else errors listing them.
        // Paths are addon-relative and SANDBOXED (absolute paths and ".." escapes are rejected, D-017; the one
        // containment check lives here now). It takes a PATH AND NOTHING ELSE: loading a file is expensive and
        // happens once, configuring a use of it is cheap and happens many times, so a font's size/style is
        // h:derive{size=12} — AWT's own split, and what keeps == free of an options table. Interned per (addon,
        // resolved path), so repeating the load costs nothing and identity is stable WHILE ALIVE: :dispose()
        // drops the entry, so the next load of that path is a NEW object. Every asset answers :type()/:path()/
        // :dispose() on top of its own verbs. hafen.font.load / hafen.render.image / hafen.render.model are a
        // HARD CUT (D-013) and read as nil; the client's four BUILT-IN fonts are engine-owned, so they are
        // addressed rather than loaded: hafen.font("sans"|"serif"|"mono"|"fraktur").
        AssetApi.install(hafen, owner);

        // hafen.ghost — CLIENT-ONLY world ghosts (spec 16-virtual-entities, V1). A ghost is a virtual prop
        // rendered in the 3D world at arbitrary world coords: a Gob with NO server id, so it never reaches the
        // server and grants no gameplay advantage — a visualization, like a HUD overlay (SAFE-tier, NOT gated;
        // D-029). The motivating use is city/base planning: lay ghost buildings over the real terrain. new{...}
        // spawns one and returns a bridge-owned handle (D-030); list([filter]) returns THIS addon's live ghosts
        // (canonical filter: nil=all / a string matched against the ghost's res / a predicate over the handle).
        // Ghosts are torn down on reload/disable/relogin (P2). Coords are WORLD (login-relative), like gob:pos().
        RenderApi.installGhost(hafen, owner);

        // hafen.render — stand CUSTOM assets that are NOT engine `.res` in the 3D world (spec 17-custom-rendering).
        // The sibling of hafen.ghost (which places `.res` game models): this is for the addon's OWN files —
        // sprite{image=} (R2: a PNG quad, fixed or camera-facing) and object{model=} (R3: a glTF model). Client-only
        // ⇒ SAFE-tier, NOT gated (D-034), like a HUD overlay. It is a SCENE namespace only: the files themselves come
        // from hafen.asset (028.1 — .image/.model are cut and read as nil).
        RenderApi.installRender(hafen, owner);

        // hafen.hook (L1 input / L2 action / L3 message / V5 grab) + hafen.slash (WoW-style :name console
        // commands) — the interception + input tables. Global hotkeys live under hafen.client:options():keybindings().
        HookApi.install(hafen, owner);

        // hafen.client — the client's own settings (spec 018). hafen.client:options() hands back the five
        // Options-window subsystems (interface/video/audio/camera/keybindings) over the SAME stores the GUI
        // edits (Utils.pref*, GSettings via ui.setgprefs, the audio roots, the KeyBinding registry), so a
        // write from Lua and a write from OptWnd are indistinguishable.
        OptionsHandle.install(hafen, owner);

        // hafen.font — per-addon typography (F-series, D-043). hafen.font(name) -> a PRIVATE FontHandle for one of
        // the client's four BUILT-IN fonts ("sans"/"serif"/"mono"/"fraktur"): engine-owned, so ADDRESSED by name
        // and interned, with no lifetime (D-060). The addon's OWN .ttf/.otf is a file, so it is an ASSET:
        // hafen.asset("fonts/Inter.ttf"). Either way the handle is :derive/:family/:size, and the sized/styled
        // variant is :derive{size=12} — hafen.font.load(source, opts) is a HARD CUT (028.1, D-013). Apply it to a
        // GLOBAL client surface with setFont(scope, h) — an OWNED override reverted on reload/disable (F1 routes
        // the "default" scope: Text.std / Text.render / Label, which CASCADES to most UI text). scopes() lists the
        // enumerated surfaces. No shared cross-addon registry: a handle is a value the addon holds. SAFE-tier
        // (cosmetic, client-only — no server traffic).
        FontApi.installFont(hafen, owner);

        hafen.set("log", new OneArgFunction() {
            public LuaValue call(LuaValue msg) {
                log(owner, msg.isnil() ? "nil" : msg.tojstring());
                return LuaValue.NIL;
            }
        });

        // hafen.json — parse/encode JSON (N1 / D-036). Ungated (pure CPU), independent of the network.
        // parse(str) -> Lua value: objects -> string-keyed tables, arrays -> 1-based tables; a JSON null
        // becomes nil (an absent key in an object, a hole in an array — the standard Lua-JSON trade-off);
        // integral numbers come back as Lua ints. Malformed input, or input over the size/depth caps
        // (-Dhaven.addon.json.maxlen / .maxdepth), throws a pcall-able LuaError. encode(value) -> compact
        // JSON and is STRICT (a function/userdata/thread, a reference cycle, or a non-finite number throws)
        // so the result is always valid JSON — unlike the REPL echo's forgiving Json.write.
        LuaTable json = new LuaTable();
        json.set("parse", new OneArgFunction() {
            public LuaValue call(LuaValue str) {
                if(!str.isstring())
                    throw new LuaError("hafen.json.parse(str) expects a string");
                String s = str.tojstring();
                if(s.length() > Json.MAX_INPUT)
                    throw new LuaError("hafen.json.parse: input too large (" + s.length()
                        + " > " + Json.MAX_INPUT + " chars)");
                Object parsed;
                try {
                    parsed = Json.parse(s, Json.DEFAULT_MAX_DEPTH);
                } catch(RuntimeException e) {
                    throw new LuaError(e.getMessage());  // "JSON: <msg> at offset <n>" -> pcall-able
                }
                return LuaMarshal.jsonToLua(parsed);
            }
        });
        json.set("encode", new OneArgFunction() {
            public LuaValue call(LuaValue v) {
                return LuaValue.valueOf(Json.write(v, true));   // strict: non-serializable -> LuaError
            }
        });
        hafen.set("json", json);

        // hafen.http — external HTTP requests (N2a / D-037), gated by a manifest "network" host allowlist.
        HttpApi.install(hafen, owner);

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
        StoreApi.installStore(hafen, owner);

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

    // ------------------------------------------------------------- logging (hafen.log + console output)

    /**
     * Build a custom UI element for {@code hafen.ui.window}/{@code widget} (spec 07): a {@link AddonWidget}
     * content leaf, optionally wrapped in a draggable {@link Window} (chrome). Reads {@code size}/{@code
     * pos} (both {@code {a,b}} arrays), {@code parent} ({@code "root"} default, or {@code "gameui"}), and
     * {@code title} from {@code opts}; the callbacks live on the same table and are wired in the
     * AddonWidget. Attaches to the tree (locks on {@code ui}), registers the content in the addon's
     * owned-resource registry (torn down on reload/disable), and returns the Lua handle.
     */


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

    /**
     * How an addon is named to the user — its manifest id ({@code "(console)"} for the {@code :lua} REPL). Used by
     * {@link #log(Addon, String)} and by any message that has to name <i>another</i> addon, e.g. the 031.2 refusal
     * when a second addon tries to take a window that is already owned.
     */
    static String ownerName(Addon a) {
        return ((a != null) && (a.manifest != null)) ? a.manifest.id : "addon";
    }

    /** Addon-level output ({@code hafen.log} + handler errors): tagged with the addon id. */
    static void log(Addon owner, String msg) {
        String id = ownerName(owner);
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

    /** Join console args (skipping the command word at index 0) back into a space-separated string. */
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
                String out = "lua= " + Json.write(r);
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

    // ------------------------------------------------------------- gob resolution + snapshots

    /** The player body resource — identity test for the {@code isplayer} snapshot field. */
    private static final String PLAYER_RES = "gfx/borka/body";

    /** The live session root ({@link Glob}), or {@code null} before a session/world is up. */
    static Glob glob() {
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
    static MCache mcache() {
        Glob g = glob();
        return (g == null) ? null : g.map;
    }

    /** The current astronomy snapshot, or {@code null} before the first "astro" update. */
    static Astronomy astro() {
        Glob g = glob();
        return (g == null) ? null : g.ast;
    }

    /**
     * The in-game HUD ({@link GameUI}). Fast path: walk up from the map view. Fallback: scan down from
     * {@code ui.root} — right at {@code OnEnterWorld} the map view exists (it fired the event) but may
     * not be parented to {@code GameUI} yet, whereas {@code GameUI} is already a child of the root
     * (its widget message arrives before the map view's). {@code null} before the HUD is up.
     */
    static GameUI gui() {
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
    static LuaValue xy(double x, double y) {
        LuaTable t = new LuaTable();
        t.set("x", LuaValue.valueOf(x));
        t.set("y", LuaValue.valueOf(y));
        return t;
    }

    /** Parse a Lua {@code {r,g,b[,a]}} table (0..255) into a {@link java.awt.Color}, or {@code dflt}. Shared by
     *  {@code hafen.markers} pins (WorldApi) and ghost/entity {@code tint} (RenderApi) — a hub color util. */
    static java.awt.Color luaColor(LuaValue t, java.awt.Color dflt) {
        LuaValue r = t.get("r"), g = t.get("g"), b = t.get("b"), a = t.get("a");
        if(!r.isnumber() || !g.isnumber() || !b.isnumber())
            return dflt;
        int ai = a.isnumber() ? clampByte(a.toint()) : 255;
        return new java.awt.Color(clampByte(r.toint()), clampByte(g.toint()), clampByte(b.toint()), ai);
    }

    /** Clamp an int to a 0..255 colour byte. */
    static int clampByte(int v) {
        return (v < 0) ? 0 : ((v > 255) ? 255 : v);
    }

    // ------------------------------------------------------------- shared read helpers (item / resource-name)
    /** The inventory grid cell {@code {x,y}} of an inventory {@link WItem} (reverses the placement). */
    static LuaValue cellPos(WItem w) {
        Coord cell = w.c.sub(1, 1).div(Inventory.sqsz);
        return xy(cell.x, cell.y);
    }

    /**
     * Display name from a resource's tooltip layer, else {@code fallback} (which may be null).
     * Loading-guarded (returns {@code fallback} while the resource is still resolving). Shared by the
     * skill / credo / experience reads — all name themselves off the resource tooltip.
     */
    static String resTipName(Indir<Resource> res, String fallback) {
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
    static String resIdent(Indir<Resource> res) {
        try {
            Resource r = res.get();
            return (r == null) ? null : r.name;
        } catch(RuntimeException e) {
            return null;
        }
    }

    /** A {@code {r,g,b,a}} table (0..255) for an AWT color. */
    static LuaValue color(java.awt.Color c) {
        LuaTable t = new LuaTable();
        t.set("r", LuaValue.valueOf(c.getRed()));
        t.set("g", LuaValue.valueOf(c.getGreen()));
        t.set("b", LuaValue.valueOf(c.getBlue()));
        t.set("a", LuaValue.valueOf(c.getAlpha()));
        return t;
    }

    /**
     * The live {@link Gob} for an id, or {@code null} if it is not (or no longer) in the object cache — the one
     * resolution point every {@link LuaGob} method funnels through (D-044 replaced the old GobRef
     * {@code resolve(LuaValue)} and its {@code "player"}/{@code "me"}/{@code "partyN"} token branches with it).
     */
    static Gob getgob(long id) {
        OCache oc = oc();
        return (oc == null) ? null : oc.getgob(id);
    }

    static Gob playerGob() {
        MapView m = view;
        return (m == null) ? null : m.player();
    }

    /** The player's live world position, or {@code null} before the player gob is up (no world / streaming). */
    static Coord2d playerPos() {
        Gob g = playerGob();
        if(g == null)
            return null;
        synchronized(g) {
            return g.rc;
        }
    }

    /** A copy of the live gob list (taken under the OCache lock; snapshots built by the caller). */
    static List<Gob> allGobs() {
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
    static boolean matches(LuaValue filter, LuaValue snap) {
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

    /**
     * Does gob {@code g} pass a {@code hafen.world.*} / {@code hafen.ui.gobOverlay} filter? {@code nil} → all;
     * a <b>string</b> → substring match on the gob's resource name, evaluated Java-side (no snapshot is built);
     * a <b>function</b> → called with the owner's interned {@link LuaGob} object, truthy keeps it (an error drops
     * it). The caller must already be OUTSIDE the OCache lock — a function filter re-enters Lua.
     */
    static boolean gobMatches(LuaValue filter, Addon owner, Gob g) {
        if((filter == null) || filter.isnil())
            return true;
        if(filter.isfunction()) {
            try {
                return filter.call(LuaGob.of(owner, g.id)).toboolean();
            } catch(RuntimeException e) {   // LuaError is a RuntimeException
                return false;
            }
        }
        if(filter.isstring()) {
            String name = gobName(g);
            return (name != null) && name.contains(filter.tojstring());
        }
        return true;
    }

    /** Distance from {@code from} to a gob's live position, or NaN if it has none yet. */
    static double distTo(Gob g, Coord2d from) {
        Coord2d rc;
        synchronized(g) { rc = g.rc; }
        return (rc == null) ? Double.NaN : from.dist(rc);
    }

    // -- per-attribute readers (each Loading-guarded: resource-backed reads can throw before load) --

    static String gobName(Gob g) {
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

    static String gobSpeech(Gob g) {
        try {
            Speaking sp = g.getattr(Speaking.class);
            return ((sp == null) || (sp.text == null)) ? null : sp.text.text;
        } catch(RuntimeException e) {
            return null;
        }
    }

    static String gobIcon(Gob g) {
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

    /** Is this gob a player body? (The {@code isplayer} snapshot field / {@code gob:isplayer()}.) */
    static boolean gobIsPlayer(Gob g) {
        String name = gobName(g);
        return (name != null) && name.equals(PLAYER_RES);
    }

    /** Best-effort active-overlay resource names ({@code Gob.ols}); unresolved ones are skipped. */
    static LuaTable overlayNames(Gob g) {
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
     * A full gob snapshot (the {@code GobInfo} shape in api-reference.md) — since D-044 the ONE snapshot escape
     * hatch, backing only {@code gob:info()} (for logging/serialising; {@code hafen.world.*} and the
     * {@code GobAdded}/{@code GobRemoved} payloads now carry Gob objects). Read on the UI
     * thread under the gob lock; every field is optional and defensive against transient/{@code
     * Loading} state (a partial snapshot is fine while world data is still resolving).
     */
    static LuaValue gobSnapshot(Gob g) {
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


    // ------------------------------------------------------------- shared hub utilities (mods + keybind panel)
    /** A {@code {shift,ctrl,alt}} table from {@code UI.modflags()} bits — handed to the grab callbacks (no Lua bit ops). */
    static LuaTable modsTable(int mf) {
        LuaTable t = new LuaTable();
        t.set("shift", LuaValue.valueOf((mf & UI.MOD_SHIFT) != 0));
        t.set("ctrl",  LuaValue.valueOf((mf & UI.MOD_CTRL) != 0));
        t.set("alt",   LuaValue.valueOf((mf & UI.MOD_META) != 0));   // MOD_META = Alt in this client (UI.setmods)
        return t;
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
     * is the addons' first registration; within an addon, registration order. Reads the live {@link HookApi#keyBinds}
     * list on the UI thread (panel build): a disabled/unloaded addon has no live hotkey, so it does not appear
     * (its persisted key pref still survives in the {@link KeyBinding} registry). The panel drives each
     * {@code binding} through the client's own capture button, which persists the re-map exactly like every
     * built-in binding — so no extra persistence is needed here.
     */
    public static List<KeyBindGroup> describeKeyBinds() {
        LinkedHashMap<Addon, List<KeyBindEntry>> byAddon = new LinkedHashMap<Addon, List<KeyBindEntry>>();
        for(LuaKeyBind kb : HookApi.keyBinds) {
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

    // -------------------------------------------------- compact JSON
    // The compact JSON serializer is Json.write (N1/D-013): one canonical writer shared by the REPL echo,
    // hafen.store persistence, and hafen.json.encode. See io.brodgar.addon.Json.
}
