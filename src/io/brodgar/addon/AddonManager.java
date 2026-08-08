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
 * from disk (per-addon Lua envs, {@code hafen.log():write}, the {@code :lua}/{@code :addons} console). Phase
 * 1b adds the <b>runtime</b>: a per-frame <b>tick pump</b> (via an invisible {@link AddonRoot}
 * widget), a synthesized <b>event bus</b> ({@code hafen.event()}), core lifecycle/update/gob events,
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
    // 038.3: the same marshalling for the two gob-overlay events. `overlaySubs` is a FAST PATH, not a
    // correctness gate: Gob.addol runs on the loader threads for every decoration the server sends, so the
    // seam must cost one volatile read when nobody listens. It is set by a subscription and cleared per
    // session/reload; a stale `true` (someone unsubscribed) only means the drain finds no subscriber and drops
    // the event, which is exactly what hasSub does for every other event here.
    private static final Queue<OverlayEvent> overlayEvents = new ConcurrentLinkedQueue<OverlayEvent>();
    static volatile boolean overlaySubs;
    // 042.1: the widget-removal seam (M1) — Widget.remove() runs on whatever thread reached it (a Loader
    // thread under synchronized(ui) from the server command queue, or the UI thread from a client-side
    // destroy()), so the tap only enqueues; tick() drains one frame's worth (D-106) and dispatches to the
    // adapters that fire *Removed. Cleared on session init like the queues above.
    private static final Queue<Widget> removedWidgets = new ConcurrentLinkedQueue<Widget>();
    // 042.1: the Resolve (M2) marshalling queue — a Loading's wnotify() runs on whichever thread finished
    // the load (Loader, Defer pool), so a retry callback never touches Lua directly; it enqueues here and
    // tick() drains it on the UI thread (P5), same shape as the queues above.
    private static final Queue<Runnable> resolveQueue = new ConcurrentLinkedQueue<Runnable>();
    // 042.6: the deferred-belt-write notify — two of GameUI's five setbelt/setbelt2 paths write belt[slot]
    // from a glob.loader.defer task that runs on a Loader thread AFTER the uimsg tap already fired (D-178:
    // the notify goes where the write lands, not where the message arrived), so this only enqueues; tick()
    // drains it on the UI thread, same shape as the queues above.
    private static final Queue<Integer> beltSetQueue = new ConcurrentLinkedQueue<Integer>();
    // 042.10: the geometry seam (M4) — Widget.resize() runs on whatever thread reached it (same uncertainty
    // as onWidgetRemoved), so the tap only enqueues; tick() drains one frame's worth (D-106) and offers each
    // to Layout.dispatchResized, which re-derives whatever hangs off it and is free when nothing does.
    private static final Queue<Widget> resizedWidgets = new ConcurrentLinkedQueue<Widget>();
    // 042.11: marker-change notify — MapFile.markerseq bumps from add/remove/update on the processor thread
    // or the UI thread, and from segment merges on the loader thread. The notify is marshalled onto the tick
    // to avoid deadlock with the map DB's RW lock. Only the count is queued (041.1).
    private static final Queue<Integer> markerChangeQueue = new ConcurrentLinkedQueue<Integer>();

    // -- widget-tree read mechanism (spec 14): Locator + Adapters + inbound-uimsg update hook -------
    // Adapters read a GameUI widget tree into a Lua snapshot and fire a semantic event on change. The
    // UI.uimsg core tap runs off the UI thread, so it only marks the interested adapter(s) dirty; the
    // tick re-reads + fires on the UI thread (principle P5). Both collections are session-scoped.

    // -- saved variables (spec 1e / D-002 / D-023): hafen.store persisted as JSON under savedata/ ------
    // Per-character vars key on <genus>_<char>, known only once the HUD is up (EnterWorld) — captured
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
            enterWorldPending = true;   // EnterWorld is fired on the next tick (UI thread)
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
        for(Addon a : addons)         // fire Disable + flush saved vars + drop owned resources
            AddonRegistry.teardown(a);              // (flushes with the OLD charScope, still set from the last session)
        addons.clear();

        clock = 0;
        enterWorldPending = false;
        gobEvents.clear();
        overlayEvents.clear();        // 038.3: and the overlay queue with it — the gobs it named are the old session's
        overlaySubs = false;          //   (loadAll below re-subscribes whoever listens, which re-arms the seams)
        VrApi.resetAnchors();         // 043.2: and the by-target index of anchored hafen.vr() entities — a gob id
                                      //   means a different gob next session, and the addons' own were just torn down
        removedWidgets.clear();       // 042.1: and the widget-removal queue — the old session's widgets are gone
        resolveQueue.clear();         // 042.1: and any Resolve retry queued from the old session
        beltSetQueue.clear();         // 042.6: and any deferred belt-write notify queued from the old session
        resizedWidgets.clear();       // 042.10: and any resize notify queued from the old session
        markerChangeQueue.clear();    // 042.11: and any marker-change notify queued from the old session
        HttpApi.reset();              // N2a: drop stale HTTP completions (their requests were torn down above)
        addonRoot = null;
        ocCb = null;

        StoreApi.resetSession();      // per-char scope + auto-save clock reset for the new session
        reloadPending = false;        // drop any :reload queued against the previous session

        UiApi.resetSession();         // 2b/3a/3b/3c: reset overlay sweep + per-session widget registries
        MapApi.resetMarkers();      // A1: drop per-session marker maps + re-prime MarkersChanged
        MapApi.resetOverlays();     // 037.3: forget the REPL owner's overlay holds — the MapView they named is gone
        MapImages.teardown(consoleOwner);   // 037.4: ...and free the map drawings it rendered from the OLD session's
                                            //   map file (the addons' went with the teardown above)
        CharApi.resetSession();       // re-register the change-detection adapters

        attachRoot(ui_);              // invisible per-frame tick widget (drives the engine)
        registerOcache(ui_);          // GobAdded/GobRemoved source (marshalled to the UI thread)
        AddonRegistry.loadAll();                    // discover + run addons, fire Load for each
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
     * per {@code 04-engine.md}: drain the marshalled event queue, then {@code Update}, then timers.
     * Everything is error-isolated so an addon bug never breaks the frame or another addon.
     */
    static void tick(double dt) {
        try {
            clock += dt;

            // 0. A queued :reload / Reload UI — rebuild the addon layer on the UI thread (spec 1f-2,
            //    D-005). Done first + return so the reloaded addons begin their own tick cleanly next
            //    frame (this frame's Update/timers belonged to the addons we just tore down).
            if(reloadPending) {
                reloadPending = false;
                overlayEvents.clear();   // 038.3: the addons that queued these are being torn down
                overlaySubs = false;     //   (the reloaded ones re-subscribe inside reload())
                AddonRegistry.reload();
                return;
            }

            // 0b. The arming tick (039.6): every surface a builder made since the last tick goes into the tree
            //     now. Done FIRST, so a window built in an input handler — which the engine dispatches before
            //     ui.tick() — is on screen in the very frame it was asked for, fully configured, rather than a
            //     frame later. Not before the reload above: a widget whose addon is being torn down is never
            //     placed at all.
            UiApi.armPending();
            CDropdown.drainRaises();      // 040.10: re-raise a popup the enclosing window's own click-to-raise
                                           //   buried this same frame (see CDropdown's class doc)

            // Soft CPU-budget accounting (D-018 layer 2): zero every addon's per-tick Lua time before any
            // handler runs this tick; callLua accumulates into it, enforceSoftBudget() evaluates it at the
            // end. (Skipped on a reload tick, which returns above — its Load/EnterWorld are one-offs.)
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
            while((ge = gobEvents.poll()) != null) {
                // 038.2: an overlay dies with its gob. Done BEFORE the event reaches Lua, so a GobRemoved handler
                // already reads the truth — and it is what a world-space overlay costs: its visual is a
                // client-only gob of its own, which nothing disposes just because the target left OCache.
                // 043.2: the same is true of a hafen.vr() entity that :add(what, gob) anchored, which has no
                // record on the gob to be found through — VrApi's by-target index is what makes that O(1) too.
                if(!ge.added) {
                    LuaGobOverlay.gobGone(ge.gob);
                    VrApi.anchorGone(ge.gob.id);
                }
                fireGob(ge.added ? "GobAdded" : "GobRemoved", ge.gob.id);
            }

            // 1'. The two gob-overlay events (038.3), captured on the loader threads (the game's own) and inside
            //     gob:overlay (an addon's own). Drained AFTER the gob queue, so an overlay the server hangs on a
            //     gob that just spawned is reported after the GobAdded that introduced it — and a removal caused
            //     by the gob leaving has already been fired synchronously by LuaGobOverlay.gobGone above, before
            //     that gob's own GobRemoved, so an overlay is never reported dying after the thing it was on.
            drainOverlayEvents();

            // 1a. HTTP results (N2a): a pool worker finished a request → deliver its res table to the addon's
            //     callback on the UI thread (armed + isolated, like every other event). A cancelled/torn-down
            //     request (dead) is discarded — its callback never fires (D-037 §3.3). Draining a request frees
            //     an in-flight slot, so re-run the per-addon scheduler to launch any queued request.
            HttpApi.drainHttp();

            // 1b. Widget-tree adapters flagged dirty by an inbound uimsg → re-read + fire the semantic
            //     event, now on the UI thread. (Marked off-thread in onUimsg; drained here.) Refresh before
            //     the removal/resolve/belt/resize drains below so a brand-new buff surfaces as a single
            //     BuffAdded (with its content already applied), not BuffChanged-then-BuffAdded.
            CharApi.refreshTreeAdapters();

            // 1b'. Widget removals (M1, 042.1) captured off-thread by the Widget.remove() tap → dispatched on
            //      the UI thread, one frame's worth (D-106). After refresh, so a removal never races a content
            //      update the same frame.
            drainRemovedWidgets();

            // 1b''. Resolve (M2, 042.1) retries queued by a Loading resolving off-thread → run on the UI thread.
            //       Same one-frame-per-tick bound as the queues above (a retry that re-registers must not spin
            //       this tick forever).
            drainResolveQueue();

            // 1b'''. Deferred belt-slot writes (042.6, D-178) captured off-thread by the two GameUI
            //        setbelt/setbelt2 loader tasks → dispatched on the UI thread, one frame's worth
            //        (D-106). The uimsg tap already re-diffed the whole bar against the OLD value for
            //        these two paths (the write lands after the message is dispatched); this re-checks
            //        just the one slot now that the write is actually there.
            drainBeltSet();

            // 1b''''. Widget resizes (M4, 042.10) captured off-thread by the Widget.resize() tap → offered on
            //         the UI thread, one frame's worth (D-106), to Layout.dispatchResized — an anchor target
            //         resizing, a window packing itself, or the screen changing all funnel through this one
            //         seam, and it is free (derived.isEmpty()) for a client with nothing anchored.
            drainResizedWidgets();

            // 1c. Replacements (032.1, event-driven since 042.8): the server destroying a window an addon
            //     replaced with widget:replace(view) is a removal, so it is offered at the removal seam
            //     (drainRemovedWidgets, via UiApi.dispatchReplacedRemoved) — nothing left for the tick to drive
            //     here.

            // 1c'. WidgetSubs tree keys (041.4, event-driven since 042.7): widget:on("ItemAdded"/"ItemRemoved", fn)
            //      and widget:on("Destroy", fn) no longer poll — they are offered every placement (above, via
            //      onWidgetPlaced -> UiApi.dispatchWidgetSubsPlaced) and every removal (drainRemovedWidgets, via
            //      UiApi.dispatchWidgetSubsRemoved), so there is nothing left for the tick to drive here.

            // 1c''. Selector subscriptions (030.2, event-driven since 042.9): `disappear` is fully driven by the
            //       removal seam above (drainRemovedWidgets, via UiApi.dispatchSelectorRemoved) — nothing to do
            //       here for it. The [title=]/[res=] refiner's bounded re-check is woken by the caption uimsg
            //       (CharApi.dispatchUimsg -> UiApi.markCaptionChanged), but that tap runs OFF the UI thread
            //       (gotcha 1) and so only sets a flag; the actual re-check (widget reads + any Lua) happens here,
            //       on the UI thread, gated on that flag — an idle client, or one with nothing pending, pays one
            //       boolean read.
            UiApi.drainSelectorCaptionCheck();

            // 1c'''. Layout (036.2, event-driven since 042.10): the late [title=]/[res=] refiner's bounded
            //        re-check is woken by the same caption uimsg as the line above (CharApi.dispatchUimsg ->
            //        Layout.markCaptionChanged, off the UI thread, flag-only per gotcha 1); the actual re-check
            //        runs here, on the UI thread. Pruning a departed widget's layout record and re-deriving an
            //        anchor's followers moved onto the removal seam (drainRemovedWidgets, via
            //        Layout.dispatchRemoved) and the geometry seam (drainResizedWidgets, via
            //        Layout.dispatchResized) above — redrive() and its per-tick fold over every anchored
            //        widget are DELETED (D-181, superseding D-091): an anchored widget re-derives on its
            //        inputs' own events now, never on a fold.
            Layout.drainPendingCaption();

            // 1d. Map markers (A1, 042.11, event-driven): fire MarkersChanged when the on-disk map DB's
            //     markerseq changes (a marker add/remove is not a uimsg — the server pushes SMarkers via
            //     markobj, the player/addon adds PMarkers, and segment merges re-key them; all bump markerseq).
            //     The bump is caught at its source and marshalled onto the tick to avoid deadlock with the
            //     map DB's RW lock. Global event.
            drainMarkerChanges();

            // 2. "Entered the world" — fire EnterWorld once the HUD (GameUI) is not just built but
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
                    fire("EnterWorld");    // the handler runs, so it can read hafen.store (spec 1e)
                }
            }

            // 3. Per-frame update.
            fire("Update", LuaValue.valueOf(dt));

            // 4. Due timers.
            runTimers();

            // 4b. Custom UI overlays (2b). Queue the HUD-overlay afterdraw for THIS frame if any addon has one
            //     (see the field note): UI.drawafter is one-shot, tick precedes draw, so it paints above the
            //     HUD this frame. The gob-overlay sweep that used to stand here is GONE (038.1) — the state
            //     lives on the gob, so there is nothing to match and nothing to attach per tick.
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
     * ({@code Disable} → flush saved vars → drop owned resources) and drop it from the live set so it
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
     * called <b>after</b> the target widget applies a server update, on a Loader thread. <b>Corrected
     * (042.1): this does NOT hold the UI monitor</b> — {@code UI.UiMessage.run} calls it after its own
     * {@code synchronized(UI.this)} block has already closed, so a reader here races {@code tick} and
     * {@code draw}. It is benign only because every consumer touches nothing but a
     * {@code CopyOnWriteArrayList}/concurrent set; the moment a consumer reads mutable widget state from
     * this tap, that read must take the monitor itself or move to the UI-thread drain. Much high-value
     * state (vitals, buffs, FEP, …) lives in widget trees updated by targeted {@code uimsg} (audit B1);
     * this is where the engine learns about it. It must <b>not</b> touch Lua — it only flags the
     * interested adapter(s) dirty; {@link #tick(double)} drains them and fires the semantic event on the
     * UI thread (principle P5).
     */
    public static void onUimsg(Widget w, String msg) {
        CharApi.dispatchUimsg(w, msg);
    }

    /**
     * The outbound-{@code wdgmsg} seam — the core edit in {@link UI#wdgmsg(Widget, String, Object...)}. Runs
     * every {@code hafen.event():action():on(msg, fn)} handler whose name matches, <b>before</b> the message
     * reaches the server, and reports whether the default send should proceed: {@code false} once any handler
     * called {@code ev:preventDefault()} (or {@code ev:resend}/{@code ev:send}, which take over the send
     * themselves via {@link UI#rawWdgmsg}). The body is {@link #dispatchAction}.
     *
     * <p><b>Threading.</b> It runs Lua only when the calling thread already holds the UI monitor
     * ({@code Thread.holdsLock}). A player action's {@code wdgmsg} is always sent under {@code synchronized(ui)}
     * — from input dispatch and the addon tick (both inside the frame loop's {@code synchronized(ui)}), or from
     * the MapView hit-test callback, which is on the RENDER thread and takes {@code synchronized(ui)} before it
     * sends {@code "click"}. That last one is why the test is {@code holdsLock} and not "am I the UI thread": a
     * naive thread test would wrongly skip the commonest action in the game. Since the tick and draw also hold
     * that monitor, holding it here means the handler Lua cannot race any other Lua — and, because we only
     * <i>test</i> the lock (never acquire a new one), there is no deadlock risk. A rare off-lock sender is
     * passed straight through. The re-entrancy guard makes a handler body that itself triggers a {@code wdgmsg}
     * pass through rather than recurse ({@code resend}/{@code send} themselves bypass this via
     * {@code rawWdgmsg}). Returns {@code true} (proceed) on every fast-path exit, so an unsubscribed action is
     * unaffected.
     */
    public static boolean onWdgmsg(Widget sender, String msg, Object[] args) {
        return dispatchAction(sender, msg, args);
    }

    /**
     * The inbound-{@code uimsg} seam — the core edit in {@code UI.UiMessage.run}. Runs every
     * {@code hafen.event():message():on(msg, fn)} handler whose name matches, <b>before</b> the target widget
     * applies the server update, and reports what to apply:
     * <ul>
     *   <li>the original {@code args} — nobody subscribed, or nobody altered the message (apply as normal);</li>
     *   <li>a <b>rewritten</b> {@code Object[]} — a handler called {@code ev:rewrite(t)} (apply the new args);</li>
     *   <li>{@code null} — a handler called {@code ev:preventDefault()} (swallow the update; do not apply it,
     *       and the caller then also skips the post-apply widget-tree tap, since the widget did not change).</li>
     * </ul>
     * {@code preventDefault} wins over {@code rewrite} when both are used. The body is {@link #dispatchMessage}.
     *
     * <p><b>Threading.</b> Called from {@code UiMessage.run} on a Loader thread but always inside that method's
     * {@code synchronized(ui)} block — the same monitor the tick and draw hold — so the handler Lua cannot race
     * any other Lua. No {@code holdsLock} guard is needed (unlike {@link #onWdgmsg}, whose senders are not all
     * UI-locked): this seam is reached only under the lock. The fast path (nobody subscribes to this name)
     * returns the original args immediately, so an unsubscribed message is unaffected — important, as uimsg
     * application is hot.
     */
    public static Object[] onMessage(Widget target, String msg, Object[] args) {
        return dispatchMessage(target, msg, args);
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
     * Delegates to {@link VrApi}, which owns the world-entity registry.
     */
    public static boolean onGhostClick(Gob cg, int button, Coord2d mc) {
        return VrApi.onGhostClick(cg, button, mc);
    }

    /**
     * The <b>spatial-UI pointer seam</b> (044.4) — called from {@code haven.MapView}'s four mouse entries before
     * it does anything of its own, so a widget standing in the world takes the pointer exactly where a window on
     * the flat UI would have taken it: first, and only where it actually is. Each returns {@code true} when a
     * standing panel took the event; {@code false} leaves the map view's own behaviour completely untouched,
     * which is how a click that misses a panel still reaches the world beneath it. Delegates to
     * {@link SurfaceInput}, for the same reason {@link #onGhostClick} does: {@code haven} knows one class here.
     */
    public static boolean onSurfaceMouseDown(MapView mv, Widget.MouseDownEvent ev) {
        return SurfaceInput.mouseDown(mv, ev);
    }

    public static boolean onSurfaceMouseUp(MapView mv, Widget.MouseUpEvent ev) {
        return SurfaceInput.mouseUp(mv, ev);
    }

    public static boolean onSurfaceMouseMove(MapView mv, Widget.MouseMoveEvent ev) {
        return SurfaceInput.mouseMove(mv, ev);
    }

    public static boolean onSurfaceMouseWheel(MapView mv, Widget.MouseWheelEvent ev) {
        return SurfaceInput.mouseWheel(mv, ev);
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
     *
     * <p><b>Second consumer since 042.1</b>: {@link CharApi#dispatchPlaced}, for the tree adapters that have
     * moved their "did a widget appear" detection off {@code poll()} and onto this seam (spec {@code
     * 042-event-driven-reads} M3) — same thread, same monitor, so firing their events here is exactly as safe
     * as the selector dispatch above.
     */
    public static void onWidgetPlaced(int id, Widget wdg) {
        UiApi.onWidgetPlaced(id, wdg);
        CharApi.dispatchPlaced(wdg);
    }

    /**
     * The <b>window-toggle seam</b> (031.1) — called from {@code haven.AddonWidgets}, which is where
     * {@code GameUI.togglewnd} and {@code GameUI.wndstate} reach the addon layer. A native window an addon hid
     * with {@code widget:visible(false)} is a window that addon <b>owns</b>, toggle included: {@link #toggleWnd} answers
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

    /**
     * The <b>window-chrome seam</b> (035.1, C2) — called from {@code haven.AddonWidgets} for every window on
     * every {@code Window.tick}: put the sheet-fed {@code Deco} on, take it off, or refresh what it paints
     * ({@link SkinDeco#check}). It runs in {@code tick} because the swap goes through {@code Window.chdeco},
     * which destroys a widget and re-lays the window out — neither belongs inside a draw.
     *
     * <p><b>Threading.</b> UI thread, under the monitor the tick already holds (the resolution reads the widget
     * tree), and it raises no Lua: a rule is plain parsed data by the time it gets here. With no override
     * installed anywhere the call is one {@code instanceof} plus one {@code volatile} read and allocates nothing.
     */
    public static void chrome(Window wnd) {              SkinDeco.check(wnd);            }

    /**
     * The <b>layout-persistence seam</b> (036.1, feature E) — called from {@code haven.AddonWidgets} wherever the
     * client writes a window's own geometry to disk ({@code GameUI.savewndpos}, {@code cdestroy}'s
     * {@code wndc-misc}, the crafting window's {@code makewndc}): what should be persisted is what the <b>user</b>
     * last placed, so a widget an addon's layout is standing on answers with the stock value recorded at first
     * touch, and every other widget answers with itself.
     *
     * <p>Without it a naive move has the client save the addon's position as the user's preference, and
     * uninstalling the addon leaves those windows displaced forever — the one risk of this feature that is not
     * reversible in memory. <b>An addon's layout is a layer over the client's, never a write into it.</b>
     *
     * <p><b>Threading.</b> UI thread (a 60 s tick, {@code dispose()}, or a window's destroy) and it raises no Lua.
     * With no addon laying anything out the call is one volatile read and hands the widget's own value straight
     * back, so a stock client writes exactly the bytes it wrote before.
     */
    public static Coord stockPos(Widget w) {             return UiApi.stockPos(w);       }

    /** @see #stockPos */
    public static Coord stockSize(Widget w) {            return UiApi.stockSizeArg(w);   }

    // The widget-targeting descriptor {id, type, place, caption, parentType} (D-024) is GONE (032.2). It was the
    // argument of replace{match=fn} and nothing else once 030.2 hard-cut the onWidgetCreate observer that shared
    // it; with hafen.ui.replace deleted there is exactly one vocabulary for "which window" left — the Selector.

    /** Re-read each dirty adapter and fire its semantic event (UI thread, drained from the tick). */

    // ------------------------------------------------------------- event dispatch

    /**
     * The bus's <b>closed key set</b> — the 26 events {@code hafen.event():on(key, fn)} accepts, in the order
     * the catalogue lists them (lifecycle, world, character, roster, own entities). Closed because the client
     * knows the whole set at load, so an unknown key is a typo with no future meaning to wait for (D-129):
     * before 041 {@code hafen.event():on("GobAdded ", fn)} was accepted and simply never fired, which is the
     * most common silent addon bug there is.
     *
     * <p>PascalCase throughout, and it is the bus's <i>own</i> spelling that the rest of the API adopted in
     * 041 — so 22 of these are the exact string the corpus already called. Only the four lifecycle keys moved,
     * dropping the {@code On} prefix that {@code :on} already says (see {@link Retired#eventKey}).
     */
    static final String[] BUS_KEYS = {
        "Load", "EnterWorld", "Update", "Disable",
        "GobAdded", "GobRemoved", "GobOverlayAdded", "GobOverlayRemoved",
        "MeterAdded", "MeterRemoved", "MeterChanged",
        "BuffAdded", "BuffRemoved", "BuffChanged",
        "FepChanged", "StudyChanged", "EquipChanged", "ActionbarChanged", "WoundChanged",
        "KinChanged", "QuestAdded", "QuestDone", "MarkersChanged",
        "GhostClicked", "SpriteClicked", "ObjectClicked",
    };

    /** Is {@code key} one of the {@link #BUS_KEYS}? (Linear over 26 constants, once per subscription.) */
    private static boolean busKey(String key) {
        for(String k : BUS_KEYS) {
            if(k.equals(key))
                return true;
        }
        return false;
    }

    // ------------------------------------------------- the two message streams (hafen.event():action/:message)

    /** Re-entrancy guard for {@link #dispatchAction}: a handler body that itself sends a {@code wdgmsg}. */
    private static boolean dispatchingAction;

    /**
     * One addon's <b>stream emitter</b> — the object {@code hafen.event():action()} and
     * {@code hafen.event():message()} hand back (041.2). It is minted once per addon and per stream, and the
     * Lua value IS its {@link Subs}: the emitter has no state beyond its subscriptions, so wrapping it in a
     * second object would only be a second thing to keep in step.
     *
     * <p>One verb, {@code :on(msg, fn)}, and a CLOSED vocabulary around an OPEN key set — the two are
     * different questions. An unknown <i>verb</i> on the emitter throws (the grammar is the client's), while
     * an unknown <i>msg</i> is accepted and may simply never fire (the name is the protocol's, D-129).
     */
    private static LuaValue stream(final String nm, final Subs subs) {
        LuaTable m = new LuaTable();
        m.set("on", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = a.arg1();
                if(!self.isuserdata() || (self.touserdata() != subs))
                    throw new LuaError("hafen.event():" + nm + "():on(msg, fn) — use a COLON call on the"
                        + " stream object (hafen.event():" + nm + "():on(msg, fn))");
                LuaValue nmv = Args.required(a, 2, "hafen.event():" + nm + "():on", "msg");
                LuaValue fn = Args.required(a, 3, "hafen.event():" + nm + "():on", "fn");
                if(!nmv.isstring() || !fn.isfunction())
                    throw new LuaError("hafen.event():" + nm + "():on(msg, fn) expects (string, function)");
                return subs.on(nmv.tojstring(), fn);
            }
        });
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, Retired.closedIndex("hafen.event():" + nm + "()", m,
            "a message stream answers one verb, :on(msg, fn), and its key set is OPEN: any message name is"
            + " accepted, because a wdgmsg name is protocol rather than a catalogue the client owns"));
        mt.set("__name", LuaValue.valueOf("Stream"));
        mt.set("__tostring", new OneArgFunction() {
            public LuaValue call(LuaValue v) {
                return LuaValue.valueOf("hafen.event():" + nm + "()");
            }
        });
        return LuaValue.userdataOf(subs, mt);
    }

    /**
     * The outbound-{@code wdgmsg} dispatch (041.2, over the L2 hook level it replaced) — the body behind
     * {@link #onWdgmsg}. Every {@code hafen.event():action():on(msg, fn)} handler of every owner runs before
     * the message reaches the server, and the answer is whether the default send should proceed
     * ({@code false} once any handler called {@code ev:preventDefault()}, or {@code ev:resend()}/
     * {@code ev:send()}, which take the send over themselves).
     *
     * <p><b>One {@link Subs.Cancel} for the whole fire</b>, shared across the handlers of one addon <i>and</i>
     * across addons: any handler cancels, every handler still runs, so the outcome never depends on an order
     * that is undefined between addons anyway (spec §R3). The {@code ev} itself is per addon, because a
     * Widget handle is interned per addon (D-045/D-064), and it is minted only for an owner that actually
     * subscribes — the {@code hasSub} gate, kept.
     *
     * <p><b>The fast path is a scan, not a global registry.</b> {@link #anyStreamSub} asks each owner's own
     * {@code Subs} whether it listens to this name: a handful of empty-map lookups, against the per-message
     * dispatch map this replaced. That is D-100 — the state belongs on the thing that owns it — and it is what
     * makes teardown a {@link Subs#clear} with nothing to unregister.
     */
    static boolean dispatchAction(Widget sender, String msg, Object[] args) {
        if(!anyStreamSub(msg, true))
            return true;                              // fast path: nothing anywhere listens to this action
        UI u = ui;
        if((u == null) || !Thread.holdsLock(u))
            return true;                              // only run Lua on a UI-locked (Lua-safe) send path
        if(dispatchingAction)
            return true;                              // re-entrancy: a handler body sent another wdgmsg
        Subs.Cancel c = new Subs.Cancel();
        dispatchingAction = true;
        try {
            for(Addon a : addons)
                fireAction(a, sender, msg, args, c, u);
            Addon co = consoleOwner;
            if(co != null)
                fireAction(co, sender, msg, args, c, u);
        } finally {
            dispatchingAction = false;
        }
        return !c.prevented();
    }

    /** Run one owner's action handlers, with its own {@code ev} over the shared cancel flag. */
    private static void fireAction(Addon a, Widget sender, String msg, Object[] args, Subs.Cancel c, UI u) {
        if(a.actionSubs.has(msg))
            a.actionSubs.fire(msg, c, LuaEvent.action(a, sender, msg, args, c, u));
    }

    /**
     * The inbound-{@code uimsg} dispatch (041.2, over the L3 hook level it replaced) — the body behind
     * {@link #onMessage}. Every {@code hafen.event():message():on(msg, fn)} handler runs before the target
     * widget applies the update, and the answer is what to apply: the original {@code args}, a rewritten
     * array ({@code ev:rewrite(t)}), or {@code null} to swallow it ({@code ev:preventDefault()}).
     *
     * <p><b>{@code preventDefault} beats {@code rewrite}</b>, and the last {@code rewrite} of one message
     * wins — the precedence the hook levels had, unchanged. No {@code holdsLock} guard, unlike
     * {@link #dispatchAction}: this seam is reached only from inside {@code UiMessage.run}'s
     * {@code synchronized(ui)} block, so the monitor is always already held.
     */
    static Object[] dispatchMessage(Widget target, String msg, Object[] args) {
        if(!anyStreamSub(msg, false))
            return args;                              // fast path: nothing anywhere listens to this message
        Subs.Cancel c = new Subs.Cancel();
        Object[][] rewritten = new Object[1][];
        for(Addon a : addons)
            fireMessage(a, target, msg, args, c, rewritten);
        Addon co = consoleOwner;
        if(co != null)
            fireMessage(co, target, msg, args, c, rewritten);
        if(c.prevented())
            return null;                              // swallow (preventDefault wins over any rewrite)
        return (rewritten[0] != null) ? rewritten[0] : args;
    }

    /** Run one owner's message handlers, with its own {@code ev} over the shared cancel + rewrite slots. */
    private static void fireMessage(Addon a, Widget target, String msg, Object[] args, Subs.Cancel c,
                                    Object[][] rewritten) {
        if(a.messageSubs.has(msg))
            a.messageSubs.fire(msg, c, LuaEvent.message(a, target, msg, args, c, rewritten));
    }

    /** Does any owner subscribe to {@code msg} on the action ({@code true}) or message stream? */
    private static boolean anyStreamSub(String msg, boolean action) {
        for(Addon a : addons) {
            if((action ? a.actionSubs : a.messageSubs).has(msg))
                return true;
        }
        Addon c = consoleOwner;
        return (c != null) && (action ? c.actionSubs : c.messageSubs).has(msg);
    }

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
     * Fire a gob-overlay event ({@code GobOverlayAdded}/{@code GobOverlayRemoved}, payload {@code :gob() :key()
     * :native()} — a {@link LuaEvent}, objectified 041.7) — 038.3. Two things fire it: the <b>game itself</b>,
     * through the two {@code // addon:} seams in {@link Gob} ({@link #gobOverlayCame}/{@link #gobOverlayGone}),
     * and an <b>addon's own</b> {@code gob:overlay(key, spec)} / {@code (key, nil)}.
     *
     * <p><b>The addon's half is OWNER-SCOPED, the game's broadcasts</b> — the {@code GhostClicked} shape, and for
     * the same reason one level down: an overlay key is <i>per addon</i>, so a {@code native = false} event handed
     * to a bystander would carry a key that addon cannot read ({@code gob:overlay(key)} answers nil for it). A
     * name that addresses nothing is worse than no event. A native key is a resource name, which every addon can
     * read, so those go to everyone.
     *
     * <p>Interning is per-addon (D-045) like every other payload here, so the Gob {@code :gob()} answers is
     * <i>that</i> owner's handle, minted lazily and only for an owner that actually subscribes.
     */
    static void fireGobOverlay(String event, long gobId, String key, boolean nat, Addon owner) {
        if(owner != null) {                                   // one addon's own overlay: only that addon is told
            if(hasSub(owner, event))
                fireTo(owner, event, overlayPayload(owner, gobId, key, nat));
            return;
        }
        for(Addon a : addons) {
            if(hasSub(a, event))
                fireTo(a, event, overlayPayload(a, gobId, key, nat));
        }
        Addon c = consoleOwner;
        if((c != null) && hasSub(c, event))
            fireTo(c, event, overlayPayload(c, gobId, key, nat));
    }

    /** One owner's gob-overlay payload: {@code :gob() :key() :native()} (a {@link LuaEvent}, 041.7 — a plain
     * {@code {gob, key, native}} table before this, per spec §2.3/R6's "every payload member is a colon verb"). */
    private static LuaValue overlayPayload(Addon owner, long gobId, String key, boolean nat) {
        return LuaEvent.overlay(owner, gobId, key, nat);
    }

    /**
     * <b>The game put an overlay on a gob</b> — the {@code // addon:} seam at the end of
     * {@code Gob.addol(ol, async)}'s body. Only the two-arg body is hooked: every other {@code addol} overload
     * delegates to it (the async one through {@code defer}), so an add fires exactly once however it arrived.
     *
     * <p>Called from the loader threads and from {@code Gob.ctick}, so it does the least possible: it never calls
     * into Lua and never throws back into the engine's own overlay path — it appends to a queue the tick drains.
     */
    public static void gobOverlayCame(Gob g, Gob.Overlay ol) {
        nativeOverlayEvent(g, ol, true);
    }

    /**
     * <b>The game took one of its overlays off a gob</b> — the {@code // addon:} seams at
     * {@code Gob.Overlay.remove}'s {@code gob.ols.remove(this)} and at {@code Gob.ctick}'s expiry of a sprite that
     * has finished (which is how most of the game's overlays actually end: they are transient sprites nobody
     * removes by hand). Same rules as {@link #gobOverlayCame}: queue only, never Lua, never throw.
     */
    public static void gobOverlayGone(Gob g, Gob.Overlay ol) {
        nativeOverlayEvent(g, ol, false);
    }

    /**
     * One of the game's own overlays came or went. <b>The event follows the KEY, not the engine object</b>: a
     * native overlay is addressed by its <i>resource name</i> and is therefore a UNION (038.1 measured 13 of 33
     * decorated gobs carrying two of one resource), so the second {@code foo} arriving is not an add and one of
     * two {@code foo}s leaving is not a removal — either would contradict the read, which still answers that key.
     * The count is taken after the engine's own mutation, so "first" is 1 and "last" is 0.
     */
    private static void nativeOverlayEvent(Gob g, Gob.Overlay ol, boolean added) {
        if(!overlaySubs || (g == null) || (ol == null))
            return;                     // nobody is listening: the seam costs one volatile read
        try {
            String key = LuaGobOverlay.nativeKey(ol);
            if(key == null)
                return;                 // its sprite has not resolved: no name to be addressed by, so not there yet
            if(LuaGobOverlay.countNative(g, key) != (added ? 1 : 0))
                return;                 // the union still has (or already had) another of this resource
            overlayEvents.add(new OverlayEvent(added, g.id, key, true, null));
        } catch(RuntimeException e) {
            /* the engine's overlay path is not ours to break */
        }
    }

    /** An addon's own attach/remove ({@code gob:overlay}), queued onto the tick like the game's. */
    static void queueGobOverlay(boolean added, long gobId, String key, Addon owner) {
        if(overlaySubs)
            overlayEvents.add(new OverlayEvent(added, gobId, key, false, owner));
    }

    /**
     * Deliver the overlay events captured since the last tick. <b>Bounded by what is in the queue right now</b>:
     * a handler that attaches or removes an overlay of its own queues another event, and draining until empty
     * would let a handler that re-attaches under the same key spin the frame forever. One frame's worth per
     * frame turns that into a slow loop the addon can see and its watchdog can price, instead of a hang.
     */
    private static void drainOverlayEvents() {
        for(int n = overlayEvents.size(); n > 0; n--) {
            OverlayEvent oe = overlayEvents.poll();
            if(oe == null)
                break;
            fireGobOverlay(oe.added ? "GobOverlayAdded" : "GobOverlayRemoved", oe.gobId, oe.key, oe.nat, oe.owner);
        }
    }

    // ------------------------------------------------------------- widget removal (M1, 042.1)

    /**
     * The <b>widget-removal seam</b> — the core edit at the end of {@code Widget.remove()} (spec {@code
     * 042-event-driven-reads}, D-179). {@code remove()} is overridden nowhere and runs on every removal path
     * (a server {@code destroy}, a client-side call), which is why it beats {@code cdestroy}: 9 of 17 {@code
     * cdestroy} overrides never call {@code super}. Fired <b>after</b> {@code unlink()}/{@code cdestroy}/{@code
     * parent = null}/{@code ui.removed(this)}, so a consumer sees the tree in its settled post-removal state —
     * the mirror of {@link #onWidgetPlaced}, which fires after the child is in.
     *
     * <p><b>Must not touch Lua.</b> {@code remove()} can run on a Loader thread (the server command queue) or
     * the UI thread (a client-side {@code destroy()}) — neither is guaranteed, so this only enqueues; {@link
     * #tick(double)} drains and dispatches on the UI thread, exactly like {@link #gobEvents}/{@link
     * #overlayEvents} (038.3).
     */
    public static void onWidgetRemoved(Widget w) {
        removedWidgets.add(w);
    }

    /**
     * <b>Draw every widget standing in the 3D world into its own texture</b> (044.1) — the facade behind the one
     * {@code // addon:} line in {@code UILoop.display}, called with the frame's {@link haven.render.Render}
     * before the widget traversal that draws the world. Kept here rather than called on {@link WidgetSurface}
     * directly for the same reason {@link #onGhostClick} is: {@code haven} knows one class in this package.
     * Never throws into the frame loop.
     */
    public static void drawSurfaces(UI u, haven.render.Render out) {
        try {
            WidgetSurface.renderAll(u, out);
        } catch(RuntimeException e) {
            log("surface pass error: " + e);
        }
    }

    /**
     * <b>The root a popup opens into</b> (044.5) — the facade behind {@code haven.Widget.popuproot()}. A widget
     * standing in the 3D world is hosted by a {@link WidgetSurface}, which is a real root in every sense the
     * client resolves things against; a widget that is not standing has {@code ui.root} above it and nothing
     * else, so this answers exactly what the three popup sites spelled out before it existed. Walking the
     * parent chain is O(depth) and happens when a list opens, never on a frame.
     */
    public static Widget popupRoot(Widget w) {
        for(Widget p = w; p != null; p = p.parent) {
            if(p instanceof WidgetSurface)
                return p;
        }
        UI u = (w == null) ? null : w.ui;
        if(u == null)
            u = ui;
        return ((u == null) || (u.root == null)) ? w : u.root;
    }

    /**
     * <b>Resolve a pointer query on the panel under the pointer instead of on the flat tree</b> (044.5) — the
     * facade behind the {@code // addon:} lines in {@link UI#tooltip}, {@link UI#getcurs} and
     * {@link UI#mousehover}. Those three walk down from {@code ui.root}, which by construction steps over a
     * standing widget (its surface is an invisible child, 044.1) and would hand it the screen's coordinates
     * rather than the panel's. {@code true} means a panel took the query and the flat walk must be skipped —
     * a tooltip, a cursor or a hover state resolved on a widget in the world, through the very corner map its
     * clicks already come through. Never throws into the frame loop.
     */
    public static boolean surfaceQuery(Widget.PointerEvent ev, Coord c) {
        try {
            return SurfaceInput.query(ev, c);
        } catch(RuntimeException e) {
            log("surface query error: " + e);
            return false;
        }
    }

    /**
     * Deliver the widget removals captured since the last tick, one frame's worth (D-106) — the same bound as
     * {@link #drainOverlayEvents}, for the same reason: a torn-down parent whose own removal triggers more
     * removals must not spin this tick forever.
     *
     * <p><b>Second consumer since 042.7</b>: {@link UiApi#dispatchWidgetSubsRemoved}, for {@code widget:on(
     * "Destroy"/"ItemAdded"/"ItemRemoved", fn)} — same drain, same thread, so firing those here is exactly as
     * safe as the tree-adapter dispatch above. <b>Third since 042.8</b>: {@link UiApi#dispatchReplacedRemoved},
     * for the {@code widget:replace(view)} substitution's own death test — the server destroying a window an
     * addon replaced is a removal like any other.
     */
    private static void drainRemovedWidgets() {
        for(int n = removedWidgets.size(); n > 0; n--) {
            Widget w = removedWidgets.poll();
            if(w == null)
                break;
            CharApi.dispatchRemoved(w);
            UiApi.dispatchWidgetSubsRemoved(w);
            UiApi.dispatchReplacedRemoved(w);
            UiApi.dispatchSelectorRemoved(w);             // addon: 042.9 — widget removal → fire selector disappear
            Layout.dispatchRemoved(w);                    // addon: 042.10 — drop its layout record, its pending
                                                           // late-caption entry, and (if it was an anchor target)
                                                           // any now-unused drag listener
        }
    }

    // ------------------------------------------------------------- Resolve marshalling (M2, 042.1)

    /**
     * Queue a {@link Resolve} retry callback onto the UI-thread tick — {@code Waitable.wnotify()} runs on
     * whichever thread finished the load (a Loader thread, a {@code Defer} pool thread), so the retry it wakes
     * must never run Lua inline (principle P5). Package-private: {@link Resolve} is the only caller.
     */
    static void enqueueResolve(Runnable r) {
        resolveQueue.add(r);
    }

    /**
     * Run the Resolve retries queued since the last tick, one frame's worth (D-106) — a retry that re-registers
     * (a further tile, a second resource) queues another callback rather than looping here.
     */
    private static void drainResolveQueue() {
        for(int n = resolveQueue.size(); n > 0; n--) {
            Runnable r = resolveQueue.poll();
            if(r == null)
                break;
            try {
                r.run();
            } catch(RuntimeException e) {
                log("Resolve callback error: " + e);
            }
        }
    }

    // ------------------------------------------------------------- deferred belt write (042.6)

    /**
     * The <b>deferred-belt-write notify</b> — the core edit inside the two {@code glob.loader.defer}
     * lambdas in {@code GameUI.uimsg}'s {@code setbelt}/{@code setbelt2} block (spec {@code
     * 042-event-driven-reads}, D-178). Three of the five paths write {@code belt[slot]} synchronously and
     * are already covered by the existing uimsg tap; these two defer the write onto a Loader task that
     * runs AFTER the message is dispatched, so the notify goes right where the write actually lands —
     * immediately after {@code belt[slot] = …}, the only place the change happens.
     *
     * <p><b>Must not touch Lua.</b> {@code loader.defer} runs the lambda on a Loader thread, so this only
     * enqueues; {@link #tick(double)} drains it on the UI thread, exactly like {@link #onWidgetRemoved}.
     */
    public static void onBeltSet(int slot) {
        beltSetQueue.add(slot);
    }

    /**
     * Deliver the deferred belt-slot writes captured since the last tick, one frame's worth (D-106) — the
     * same bound as the other marshalled queues, for the same reason.
     */
    private static void drainBeltSet() {
        for(int n = beltSetQueue.size(); n > 0; n--) {
            Integer slot = beltSetQueue.poll();
            if(slot == null)
                break;
            try {
                CharApi.dispatchBeltSet(slot);
            } catch(RuntimeException e) {
                log("belt-set dispatch error: " + e);
            }
        }
    }

    // ------------------------------------------------------------- map markers (A1, 042.11)

    /**
     * Map marker count changed — the on-disk map DB's {@link haven.MapFile#markerseq} bumped on add/remove/
     * update (UI or processor thread) or segment merge (loader thread). Fire MarkersChanged with the new count
     * payload. This only enqueues; {@link #tick(double)} drains it on the UI thread, same shape as the other
     * marshalled queues (D-106, to avoid deadlock with the map DB's RW lock).
     *
     * <p><b>Must not touch Lua.</b>
     */
    public static void onMarkersChanged(int count) {
        markerChangeQueue.add(count);
    }

    /**
     * Deliver the marker-count changes captured since the last tick, one frame's worth (D-106) — fire
     * MarkersChanged with each count.
     */
    private static void drainMarkerChanges() {
        for(int n = markerChangeQueue.size(); n > 0; n--) {
            Integer count = markerChangeQueue.poll();
            if(count == null)
                break;
            try {
                MapApi.fireMarkersChanged(count);
            } catch(RuntimeException e) {
                log("marker-change dispatch error: " + e);
            }
        }
    }

    // ------------------------------------------------------------- widget resize (M4, 042.10)

    /**
     * The <b>geometry seam</b> — the core edit at the end of {@code Widget.resize(Coord)} (spec {@code
     * 042-event-driven-reads}, D-181, superseding D-091). Fires after the {@code Utils.eq} early return
     * (never for a no-op resize) and after the {@code presize()} cascade and {@code parent.cresize} (so a
     * consumer reads settled geometry) — the same discipline as {@link #onWidgetRemoved}. One tap covers all
     * three of M4's size inputs: an anchor target resizing, a window packing itself ({@code pack()} ->
     * {@code resize(contentsz())}), and the screen changing ({@code UILoop} calling {@code ui.root.resize(sz)}
     * — the root is just another resize).
     *
     * <p><b>Must not touch Lua.</b> {@code resize()} is not guaranteed to run on the UI thread (it is reached
     * from server message application as well as from tick/draw), so this only enqueues; {@link #tick(double)}
     * drains and dispatches on the UI thread, exactly like {@link #onWidgetRemoved}.
     */
    public static void onWidgetResized(Widget w) {
        resizedWidgets.add(w);
    }

    /**
     * Deliver the widget resizes captured since the last tick, one frame's worth (D-106) — offered to
     * {@link Layout#dispatchResized}, which re-derives whatever hangs off {@code w} (M4) and is a near-zero
     * cost ({@code derived.isEmpty()}) for a client with nothing anchored.
     */
    private static void drainResizedWidgets() {
        for(int n = resizedWidgets.size(); n > 0; n--) {
            Widget w = resizedWidgets.poll();
            if(w == null)
                break;
            try {
                Layout.dispatchResized(w);
            } catch(RuntimeException e) {
                log("widget-resize dispatch error: " + e);
            }
        }
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
     * the payload cannot be shared, and it is minted only for an owner that actually subscribes — a meter's
     * appearance/removal is detected at the placement/removal seams (042.1) and a login brings the whole slot
     * up in one burst, so the {@code hasSub} gate is what keeps that free for the addons that don't listen.
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

    /**
     * Fire {@code FepChanged} whose payload is the <b>Food object</b> (039.11) — the character's food-event
     * points and hunger. Same shape as {@link #fireBuff}: interning is per-addon (D-045) so the payload
     * cannot be shared, and it is minted only for an owner that actually subscribes.
     *
     * <p>There is no change <i>detection</i> to do: each {@code "food"}/{@code "glut"} {@code uimsg} is a
     * genuine server change, and what the handler gets is a live object rather than the numbers at fire
     * time — so a stashed payload keeps reading the meal after it.
     */
    static void fireFood(BAttrWnd w) {
        for(Addon a : addons) {
            if(hasSub(a, "FepChanged"))
                fireTo(a, "FepChanged", LuaFood.of(a, w));
        }
        Addon c = consoleOwner;
        if((c != null) && hasSub(c, "FepChanged"))
            fireTo(c, "FepChanged", LuaFood.of(c, w));
    }

    /**
     * Fire {@code StudyChanged} whose payload is an array of <b>StudySlot objects</b> (039.11) — the
     * curiosities in the study window, in the order it holds them. Same shape as {@link #fireKin}:
     * interning is per-addon (D-045), and the array is minted only for an owner that actually subscribes —
     * the study data resolves in several steps after a login, so the gate keeps that free for the addons
     * that do not listen.
     *
     * <p>Change <i>detection</i> stays in {@code CharApi}'s study adapter (the per-slot snapshot diff); the
     * items arrive already diffed.
     */
    static void fireStudy(java.util.List<GItem> items) {
        for(Addon a : addons) {
            if(hasSub(a, "StudyChanged"))
                fireTo(a, "StudyChanged", studyPayload(a, items));
        }
        Addon c = consoleOwner;
        if((c != null) && hasSub(c, "StudyChanged"))
            fireTo(c, "StudyChanged", studyPayload(c, items));
    }

    /**
     * Fire {@code EquipChanged} whose payload is an array of <b>Item objects</b> (039.14) — what is worn right
     * now, each item once whatever number of slots it fills. Same shape as {@link #fireStudy}: interning is
     * per-addon (D-045) and the array is minted only for an owner that actually subscribes.
     *
     * <p>Change <i>detection</i> stays in {@code CharApi}'s equipment adapter, which keeps a string rather than
     * these objects: an interned item compares by identity, so it cannot see the very change the event reports.
     */
    static void fireEquip(java.util.List<GItem> items) {
        for(Addon a : addons) {
            if(hasSub(a, "EquipChanged"))
                fireTo(a, "EquipChanged", itemPayload(a, items));
        }
        Addon c = consoleOwner;
        if((c != null) && hasSub(c, "EquipChanged"))
            fireTo(c, "EquipChanged", itemPayload(c, items));
    }

    /** One owner's {@code EquipChanged} payload: its own interned Item objects, in the window's order. */
    private static LuaValue itemPayload(Addon owner, java.util.List<GItem> items) {
        LuaTable t = new LuaTable();
        for(int i = 0; i < items.size(); i++)
            t.set(i + 1, LuaItem.of(owner, items.get(i)));
        return t;
    }

    /** One owner's {@code StudyChanged} payload: its own interned StudySlot objects, in window order. */
    private static LuaValue studyPayload(Addon owner, java.util.List<GItem> items) {
        LuaTable t = new LuaTable();
        for(int i = 0; i < items.size(); i++)
            t.set(i + 1, LuaStudySlot.of(owner, items.get(i)));
        return t;
    }

    /**
     * Fire a quest event ({@code QuestAdded}/{@code QuestDone}) whose payload is the <b>Quest object</b>
     * (039.13). Same shape as {@link #fireGob}: interning is per-addon (D-045), so each owner gets <i>its</i>
     * handle for the id, minted only for an owner that actually subscribes.
     *
     * <p>The object matters more here than in most events: {@code QuestDone} fires <i>because</i> the status
     * changed, and a snapshot would freeze the very field the handler is being told about. A stashed Quest
     * goes on reading — including through the completion that fired this.
     */
    static void fireQuest(String event, int id) {
        for(Addon a : addons) {
            if(hasSub(a, event))
                fireTo(a, event, LuaQuest.of(a, id));
        }
        Addon c = consoleOwner;
        if((c != null) && hasSub(c, event))
            fireTo(c, event, LuaQuest.of(c, id));
    }

    /**
     * Fire {@code WoundChanged} whose payload is an array of <b>Wound objects</b> (039.13) — every wound the
     * character has, in the window's own tree order. Same shape as {@link #fireKin}: interning is per-addon
     * (D-045), and the array is minted only for an owner that actually subscribes.
     *
     * <p>Change <i>detection</i> stays in {@code CharApi}'s wound adapter (the per-wound snapshot diff, which
     * is what sees a severity resolve or a wound worsen); the ids arrive already diffed.
     */
    static void fireWounds(int[] ids) {
        for(Addon a : addons) {
            if(hasSub(a, "WoundChanged"))
                fireTo(a, "WoundChanged", woundPayload(a, ids));
        }
        Addon c = consoleOwner;
        if((c != null) && hasSub(c, "WoundChanged"))
            fireTo(c, "WoundChanged", woundPayload(c, ids));
    }

    /** One owner's {@code WoundChanged} payload: its own interned Wound objects, in tree order. */
    private static LuaValue woundPayload(Addon owner, int[] ids) {
        LuaTable t = new LuaTable();
        for(int i = 0; i < ids.length; i++)
            t.set(i + 1, LuaWound.of(owner, ids[i]));
        return t;
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
        return a.subs.has(event);
    }

    /**
     * Fire an event to a single owner's matching subscriptions — every one of them, in registration order,
     * each error-isolated and charged to that addon's {@code events} column ({@link Subs#fire}). Nothing on
     * the bus is cancelable, so the fire's answer is not read here.
     */
    static void fireTo(Addon a, String event, LuaValue... args) {
        a.subs.fire(event, args);
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

        // hafen.gob is GONE into hafen.world():gob() (039.2, D-066): a gob lives IN the world, so the by-id
        // door is the world's Gob collection, :get(id) — still never nil, still interned per addon, and the
        // Gob OBJECT itself (gob:position()/:name()/:health()/…) is unchanged. See WorldApi.

        // hafen.menugrid() — the ACTION MENU (the 4x4 "scm" grid) as Pagina OBJECTS: the catalogue of
        // everything this character can do, in the grid's own order and category tree. The section object IS
        // the collection (039.9): :list(filter)/:count/:find for the catalogue, :get(key) for one entry, and
        // :roots() for the root screen. The key is always a STRING and splits by SHAPE — a "/" makes it a
        // resource name (the identity), anything else a display name (a search convenience, not unique) — and
        // a miss is plain nil. There is no addressing by position: the catalogue grows on every discovery.
        // Resource-backed reads are Loading-guarded, so a scan right at EnterWorld may be short and fills in
        // sub-second.
        Section.mount(hafen, "menugrid", LuaPagina.collection(owner),
                      "hafen.menugrid(key) is now hafen.menugrid():get(key), and hafen.menugrid() is"
                      + " hafen.menugrid():list()");

        // hafen.world():* — the LIVE world (037.1, re-shaped in 039.2). hafen.world():gob() is the read-only
        // Gob collection and the ONE by-id door (:get/:list/:count/:find/:nearest/:within); nearest/within
        // measure from the player and skip the player's own gob; a function filter is called with a Gob, a
        // string filter matches its resource name. Prefer GobAdded/GobRemoved over per-frame scanning. The
        // section also holds the terrain + coordinate half — tile/height/grid, the two lattice conversions,
        // screenToWorld and the placement snappers — because every one reads MCache, the terrain streamed
        // around the player (nil off-stream, gone at logout), never the map database. Every spatial verb takes
        // a POSITION (LuaPosition): computable and durable at once, so gridPos/fromGridPos are gone and there
        // is nothing left to convert. Grid ids are 64-bit and so are exposed as decimal STRINGS.
        WorldApi.installWorld(hafen, owner);

        // hafen.map():* — the RECORDED map (037): the client's on-disk map database (MapFile), the map the
        // player has EXPLORED, as opposed to the live terrain above. Five collections, re-shaped in 039.4:
        //   :segment() — the contiguous explored areas, with :current() for the one you are standing in.
        //   :grid() — the recorded 100x100-tile squares, addressed by the id the SERVER published. That id
        //     is the only thing the live and recorded halves share, so this and hafen.world():grid() hand
        //     back the SAME Grid object: :live() asks whether it is streamed in, :exists() whether it is
        //     written down, and where both answer, grid:tile(c) and hafen.world():tile(p) agree by name.
        //   :marker() — the pins (the old hafen.markers). Two kinds: PLAYER markers (user pins: a name +
        //     colour) and SYSTEM markers (server/quest pins: a name + icon). :add(name, p) takes a Position
        //     and hands back a bare pin whose colour and on-map flag are setters; :remove(m) takes it out.
        //     Both writes are ungated — they edit the user's own on-disk database. The DB streams in a beat
        //     after enter-world (empty until then); MarkersChanged fires on any change, ours or the user's.
        //   :icon() — the minimap icon registry (the old hafen.radar; the engine has no "radar", it has
        //     GobIcon.Settings — D-061). :get(res) is one category by its icon RESOURCE NAME (the identity),
        //     :list/:find search by the display name, and the flags are arity-as-the-verb on the entity:
        //     cat:show() / cat:show(v) / cat:notify() / cat:notify(v), plus :res/:name/:exists/:info. It is
        //     the SAME registry the in-client "Icon settings" window drives, so writes show there too and
        //     persist per character; empty until the HUD is up, growing as new icon types are seen.
        //   :overlay() — the client's own four display switches for claims and provinces. A write is a HOLD
        //     (t:hold()/t:release()), never a switch: the count is shared with the user's checkbox and the
        //     server's claim flash, so an addon can stop asking and can never turn one off.
        MapApi.installMap(hafen, owner);

        // hafen.player() — the section contains exactly one thing, so the section object IS the Player: purely
        // the composition anchor for hafen.player():gob() (D-046), since position/health/moving/… of the player
        // come from that Gob and Player deliberately forwards NOTHING (player:pos() alongside
        // player:gob():position() is exactly the dual style D-013 forbids). :gob() is nil before entering the
        // world. :name() is the LOCAL character name (GameUI.chrid); other players' display names are not
        // reliably available. :worldToScreen(p) takes a POSITION and answers MAP-VIEW-relative pixels as a plain
        // {x, y} — deliberately not a Position, because a pixel is not a place in the world.
        CharApi.installPlayer(hafen, owner);

        // hafen.time():* — game clock + astronomy. clock() is always available; the astronomy readers are
        // nil until the first "astro" update lands (Glob.ast is nil before then).
        WorldApi.installTime(hafen, owner);

        // hafen.sound() — the section object IS the collection (039.9) over one interned Sound object per
        // resource name: :get(name) addresses any clip the game owns (:res() / :play([volume]) -> self /
        // :stop() / :playing() / :info()), :list(filter) is what THIS addon still has in the air. Volume is an
        // ARGUMENT of the play, never entity state — the Sound is interned and shared. The resource resolves
        // OFF the UI thread (loader.defer, mirroring GobIcon.resnotif) so a not-yet-loaded resource never
        // throws Loading into Lua. Client-bundled names resolve locally ("sfx/msg").
        Section.mount(hafen, "sound", LuaSound.collection(owner),
                      "hafen.sound(name) is now hafen.sound():get(name), and hafen.sound() is"
                      + " hafen.sound():list()");

        // hafen.music is DELIBERATELY ABSENT (024.3, maintainer 2026-08-01). haven.Music is the client's MIDI
        // player, driven by exactly one thing — RootWidget's "bgm" server message — and this server never
        // sends it: there is no MIDI content, so the whole subsystem is dead weight and an API over it would
        // answer nil forever. What players actually hear as "music" is an ActAudio.Ambience loop on the `amb`
        // channel (published by world resources, governed by Options > Audio > "Ambient volume"), which is a
        // render-tree node with a lifetime, NOT a clip handle — its own feature if ever wanted, and explicitly
        // out of scope here. The audio section is hafen.sound and nothing else.

        // hafen.items is GONE (029.3, hard cut D-013). Items are a RELATION on their container now:
        // hafen.ui():inventory():items() / hafen.ui():equipment():items() / hafen.ui():hand(), and widget:items()
        // answers on ANY container — a chest, a cupboard — with its window visible and interactive. What it hands
        // back is an interned LuaItem keyed on the item WIDGET (039.14): a server widget id is recycled, so an
        // entity keyed on the number would silently start naming a different item and a gated write through it
        // would move the wrong thing. hafen.act():item takes the object and never the number.

        // hafen.char.* — character attributes (Glob.getcattr; a zero-info entry is reported as nil),
        // plus learning points (CharWnd.exp) and encumbrance/weight (CharWnd.enc) — public live fields
        // on the character window (created hidden at login). attrs() returns the nine base attributes
        // that have data, keyed by name. ("char" is a Java keyword → the local is named "chr".)
        CharApi.installChar(hafen, owner);

        // hafen.study.* — the study window (curiosities being studied), via the widget-tree mechanism
        // (1d-3). slots() = the curiosities, each {res,name,lp,attention,cost,time,progress?}; summary()
        // = the live totals {lp,attention,cost}. Both empty/nil until the character sheet's "Abilities"
        // (sattr) tab streams in, a beat after enter-world. Subscribe to StudyChanged (fired when the
        // slots change — an add/remove or study data resolving — event-driven, never per frame).
        CharApi.installStudy(hafen, owner);

        // hafen.party.* — the party roster (Glob.party). Members are ordered by Member.seq. A PartyMember is
        // DERIVED: id=gobid, x,y=getc() (live gob pos if in view, else last-known), color={r,g,b,a},
        // leader=(member==party.leader). There is NO name field for party members (a client/protocol
        // limitation). A member's gob is hafen.gob(m.id) until Party itself migrates to OOP.
        CharApi.installParty(hafen, owner);

        // hafen.kin() — the kin/buddy roster (A6), read from the Kin window (GameUI.buddies, a BuddyWnd — the
        // same list the in-client Kin tab shows). The section object IS the roster collection (039.9):
        // :list([filter]) is a fresh array of interned Kin objects in the window's sort order, :count/:find the
        // usual pair, :get(id) / :get(name) one Kin (a number is a buddy id and is NEVER nil — :exists() is the
        // liveness test — while a name that nobody carries is), and :add(secret) is the gated add.
        // A Kin wraps only the buddy id and re-resolves through buddywnd().find(id) every call (D-012), so
        // it tracks renames/regroups/online flips; see LuaKin. Subscribe to KinChanged (a Kin[] payload, minted
        // per subscribing addon by fireKin) for a kin added/removed, renamed/regrouped, or flipping
        // online/offline. The GATED verbs (requireActions) drive BuddyWnd.Buddy's own methods (D-009):
        //   :add(secret)   — kinning needs the other player's HEARTH SECRET (wdgmsg("bypwd", secret), the
        //                  Kin window's "Add kin" field); there is no add-by-NAME message.
        //   kin:endKin()   = END KINSHIP (Buddy.endkin) — ends the kinship; the kin STAYS in the list, now
        //                  merely memorized (un-kinned). The "End kinship" petal, shown while the kin is active.
        //   kin:forget()   = FORGET (Buddy.forget) — drops a memorized kin from the list entirely. The "Forget"
        //                  petal, shown once un-kinned. To fully remove an ACTIVE kin: endKin(), then forget().
        // Both send the same wdgmsg("rm", id); the SERVER advances the state (active → memorized → gone), exactly
        // as clicking the two petals in turn does. kin:rename(name)=wdgmsg("nick"), kin:group(g)=wdgmsg("grp")
        // with g validated 0..254 (the range the SERVER accepts; the client only draws 8 colours).
        CharApi.installKin(hafen, owner);

        // hafen.speed() — movement speed (A7), read from the speed selector widget (Speedget: the four-way
        // crawl/walk/run/sprint toggle at the bottom of the HUD). :current() returns the CURRENT speed as 0..3
        // (0=crawl 1=walk 2=run 3=sprint), or nil if the widget isn't up yet, and :current(n) SELECTS speed n —
        // the get/set pair collapsed onto one name whose arity is the verb (R2). The write is the GATED verb
        // (4g, requireActions): it drives the client's own Speedget.set (wrap-not-reimplement, D-009 →
        // wdgmsg("set", n)), exactly what clicking/hotkeying that speed does, and it returns the section so a
        // run of writes chains. :max() returns the highest speed currently SELECTABLE (0..3) — speeds 0..max()
        // are available, higher ones are disabled (e.g. sprint locked); nil if not up. :name([n]) is the display
        // name of speed n (no argument = the current one; from the widget's own tooltips), or nil. No
        // SpeedChanged event: speed is read on demand (the classic use is a speed-toggle keybind that reads
        // :current() then writes it), like the other gap surfaces.
        ActApi.installSpeed(hafen, owner);

        // hafen.craft() — the recipe window (A8: the Makewindow the server places under the HUD when the
        // player opens a recipe). A section of one verb: :current() is the open recipe as a Craft object, or
        // NIL when none is open. The Craft carries :name() (the recipe), :inputs()/:outputs() (the slots, as
        // {res, name, num, opt} values — res is the DISPLAYED resource, i.e. the constraint category when the
        // recipe accepts one, else the concrete item; num = the required/produced count, -1 = unspecified ~ 1;
        // opt = an optional ingredient / chance byproduct), :qualityInputs() and :tools() ({res, name} values),
        // :exists() and :info(). The GATED :make(all) is on it too (039.13 — the button belongs to the recipe):
        // it presses Craft (wdgmsg("make", 0)) or Craft All (all=true → 1), so it CONSUMES the ingredients
        // exactly as a click does. No CraftChanged event (read on demand, like A7 speed — a recipe changes
        // only when the player opens one).
        ActApi.installCraft(hafen, owner);

        // hafen.quest() — the quest log (A9), read from the character sheet's "Quest Log" tab (QuestWnd,
        // reached via CharWnd.quest — created hidden at login but live, so quests are readable without ever
        // opening the window). The section object IS the collection over BOTH tabs (039.13): :list(filter)
        // every quest, :get(id) one by its server id, :find(filter) the first match and :selected() the one
        // the player has open. A Quest reads live per call — :id() :title() :res() :status()
        // ("pending"/"done"/"failed"/"disabled") :modified() (the server change stamp; higher = more recent)
        // :selected() :conditions() :exists() :info() — and is interned on the quest id, which is what the
        // "quests" uimsg itself looks a quest up by before mutating it in place, so a stashed Quest reports
        // its own completion. :conditions() is a plain array of Condition objects and is EMPTY on every quest
        // but the selected one: the client is sent objectives for that one alone. Subscribe to QuestAdded (a
        // new active quest appears) and QuestDone (an active quest is completed/failed), both carrying the
        // Quest. Read-only — there is no quest action tier.
        CharApi.installQuest(hafen, owner);

        // hafen.wound() — the character's wounds (A9-2), read from the character sheet's "Health & Wounds"
        // tab (WoundWnd, reached via CharWnd.wound — created hidden at login but live, so wounds read without
        // ever opening the window). The section object IS the collection (039.13): :list(filter) in the
        // window's own TREE order, :get(id) one by id, :find(needle) the first whose name or res contains it
        // (the old presence test, now handing back the Wound — still truthy). A Wound reads live per call:
        // :id() :name() :res() :severity() (the magnitude string the client paints beside it — content-
        // defined, usually a number, NOT seconds, and nil for the beat before it resolves) :parent() (the
        // wound this one complicates, nil at a root — the parent id, resolved) :level() (the indent depth)
        // :exists() :info(). Interned on the wound id, which decwound itself looks a wound up by before
        // mutating it in place, so a stashed Wound reports its own worsening. Subscribe to WoundChanged (the
        // wound set or a severity changed; payload = the new list of Wound objects). Read-only — there is no
        // wound action tier (wounds heal by playing / tending).
        CharApi.installWound(hafen, owner);

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
        // (1d-2); the section object IS the collection (039.9): hafen.buff():list() is the active buffs as a
        // 1-based array of Buff objects in bar order, hafen.buff():find(needle) the FIRST one whose res or name
        // contains that substring (the old has(), now handing back the object; nil on a miss). A buff has no
        // key, so there is no :get. Reads on the object, live
        // per call: :res()/:name()/:amount()/:duration() (0..1 fractions from resource-published ItemInfo,
        // often nil, NOT seconds — :duration() is the radial meter, i.e. how much of the buff's run is
        // left; the action bar calls the same meter a cooldown because there it is one)/:number()/:exists()/:info() (the old flat snapshot). A buff fading out
        // after removal is excluded (:exists() false) but still READS — Widget.destroy() does not clear it —
        // which is what makes a stashed BuffRemoved payload useful. Subscribe to BuffAdded/BuffRemoved
        // (add/remove seen at the placement/removal seams, the removal at the server's own "gone" moment,
        // not the fade's late unlink — 042.2, D-180)/BuffChanged (content changes on the buff's "ch"/"tt"
        // uimsg) — since 025.2 all three carry the Buff OBJECT (fireBuff), not a snapshot table.
        CharApi.installBuffs(hafen, owner);

        // hafen.meter — the HUD's meter bars (GameUI's `place == "meter"` slot → IMeter widgets), via the
        // widget-tree mechanism (1d-1); the section object IS the collection (039.9): hafen.meter():list() is
        // EVERY HUD meter as a 1-based array of Meter objects in HUD order, hafen.meter():find(needle) the
        // FIRST one whose res name contains that substring (nil on a miss). A meter has no key, so there is no
        // :get. There is no hp/stamina/energy triple: the slot takes any
        // number of meters and a meter is identified by its SERVER-published bg resource name, so "hp" is a
        // substring that happens to hit a bar on this server, not a key the code knows — :res() is how to list
        // the real ones off a live client. Reads on the object, live per call: :res()/:index() (1-based HUD
        // position)/:value() (the first segment, 0..1 — what vitals() used to return)/:color() ({r,g,b,a}
        // 0..255)/:segments() (the whole multi-segment bar)/:exists()/:info() (the snapshot). A destroyed meter
        // still READS — Widget.destroy() does not clear it — but reports :exists() false. The bars stream in a
        // beat after enter-world, so hafen.meter():list() is legitimately empty for a moment. Subscribe to
        // MeterAdded/MeterRemoved (the bars streaming in / a meter being destroyed, seen at the
        // placement/removal seams — 042.1) and MeterChanged (the server's "set"/"col" uimsg, fired only on
        // a real value-OR-colour change) — all
        // three carry the Meter OBJECT (fireMeter), so MeterAdded is the honest "the bars are up" signal.
        // (hafen.player():vitals() and VitalsChanged are GONE.)
        CharApi.installMeters(hafen, owner);

        // hafen.actionbar — the action bar / hotbar (the engine calls it the "belt": GameUI.belt, a
        // BeltSlot[144]), via the widget-tree mechanism (1d-4); the section object IS the collection (039.9):
        // hafen.actionbar():get(n) is the Slot at the RAW 0-based game index 0..143 (out of range throws),
        // hafen.actionbar():list() the 1-based array of all 144 (the iteration view — same interned objects,
        // and slot:index() is the game index). Reads on the object, live per call: :res()/:name()/:cooldown()
        // (0..1, a pagina action's meter — ability slots only, NOT seconds)/:empty()/:info() (the old flat
        // snapshot). Subscribe to ActionbarChanged{slot} (fired when a slot's content changes — a
        // set/clear/drag or its data resolving, event-driven off the belt uimsg/notify, never per frame;
        // the payload is that Slot). slot:use([mods]) is the GATED write verb (4g,
        // requireActions) — exactly a LEFT-click on that action-bar button (GameUI belt act →
        // wdgmsg("belt", n, …)); mods is an optional modifier bitfield (0 default; Shift=1 Ctrl=2 Alt=4,
        // matching the keybind syntax). A ground-targeted ability then enters targeting mode (as clicking
        // the button does) — supply the target with the MapView verbs.
        CharApi.installActionbar(hafen, owner);

        // hafen.act():* — the GATED write-actions surface (spec 12 / D-010 / D-025 / D-027; D-028), the ONLY part of
        // hafen.* that DRIVES the character: it sends player-action wdgmsgs to the server. Everything else observes;
        // this acts. A verb runs only when THIS addon declared the "actions" permission in its manifest (else
        // requireActions throws a guiding error) — a PER-ADDON permission (D-028: no global master switch; the tier
        // is always available at the system level). The user opts in per addon: a write addon is disabled by default
        // and enabling it goes through the AddOns-panel consent dialog (slice 4c), so a running addon is one the user
        // permitted. It stays server-authoritative: an addon can only send what a player click could send.
        //   enabled()   -> bool; is THIS addon allowed to act (did it declare the "actions" permission)? Reports
        //                  WITHOUT throwing, so an addon can adapt (no pcall needed).
        //   moveTo(p)   -> walk the character to a POSITION (what gob:position() hands back). This is exactly
        //                  the MapView "click" a left-click on that ground spot sends; the screen coord it
        //                  carries is a dummy (the current mouse pos), like MiniMap.mvclick when you click the
        //                  minimap to walk. Off-screen destinations are fine (the server uses the world coord).
        //   clickGob/useItemOn/place/select (4d) -> the rest of the MapView action verbs; all send a Widget.wdgmsg
        //                  from the MapView, sharing moveTo's coord encoding (world → Coord via moveClickCoord; the
        //                  dummy pc), and the three spatial ones take Positions too. raw(target,msg,…) is the
        //                  escape hatch (send any wdgmsg from a bound widget).
        // The per-subsystem gated verbs (speed.set, craft.make, slot:use, the kin writes) share this same gate
        // (requireActions(owner, …)).
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
        // g:image / stand it with hafen.vr():sprite()), .ttf/.otf = a font (hafen.font.setFont / window{font=} /
        // g:text{font=}), .glb/.gltf = a glTF mesh (hafen.vr():object()) — and anything else errors listing them.
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

        // hafen.vr() — the ONE section for CLIENT-ONLY things standing in the 3D world (043; specs
        // 16-virtual-entities + 17-custom-rendering). Three collections: :ghost() places a `.res` game model,
        // :sprite() one of the addon's own PNGs, :object() one of its glTF models — each :add(what, p) standing
        // it at a Position and handing back a bridge-owned handle (D-030) that speaks one vocabulary
        // (:position/:rotate/:scale/:alpha/:tint/:visible/:clickable/:onClick), each :list([filter]) reading
        // THIS addon's (canonical filter: nil=all / a string matched against the visual's name / a predicate).
        // None of it is a Gob the server knows: no wdgmsg, invisible to OCache and every read API, so it grants
        // no gameplay advantage — a visualization, like a HUD overlay (SAFE-tier, NOT gated; D-029/D-034). The
        // motivating use is city/base planning: lay ghost buildings over the real terrain. Everything here is
        // torn down on reload/disable/relogin (P2). It is a SCENE section only: the addon's own files come from
        // hafen.asset (028.1). hafen.ghost and hafen.render are retired rows naming this.
        VrApi.installVr(hafen, owner);

        // hafen.slash (WoW-style :name console commands) — L1 input, L2 action, L3 message and V5 grab have all
        // moved off hafen.hook() onto widgets/the bus/the mouse entity, and hook() itself is deleted (041.5).
        // Global hotkeys live under hafen.client:options():keybindings().
        HookApi.install(hafen, owner);

        // hafen.client() — the client's own settings (spec 018). hafen.client():options() hands back the five
        // Options-window subsystems (interface/video/audio/camera/keybindings) over the SAME stores the GUI
        // edits (Utils.pref*, GSettings via ui.setgprefs, the audio roots, the KeyBinding registry), so a
        // write from Lua and a write from OptWnd are indistinguishable. Every option is one name whose arity is
        // the verb (opt:name() reads, opt:name(v) writes and chains) and an explicit nil is refused (§2.9),
        // because a nil that read instead of writing is a silent no-op an option has no undo for. The keybinding
        // registry's kb:get/kb:set — the last such pair in the API — collapse onto kb:key(name[, key]).
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

        // hafen.log():write(msg) — one line to the in-game console and the terminal, tagged with the addon id.
        // The section has no shortcut form: hafen.log(msg) throws naming this one, because an exception in
        // the busiest verb in the API is the exception every reader would meet first. Returns SELF so a run of
        // lines chains, and an explicit nil is refused (§2.9) rather than printing "nil".
        LuaTable logm = new LuaTable();
        logm.set("write", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = a.arg1();
                Section.self(self, "log", "write");
                log(owner, Args.required(a, 2, "hafen.log():write", "msg").tojstring());
                return self;
            }
        });
        Section.install(hafen, "log", logm, "hafen.log(msg) is now hafen.log():write(msg)");

        // hafen.json — parse/encode JSON (N1 / D-036). Ungated (pure CPU), independent of the network.
        // parse(str) -> Lua value: objects -> string-keyed tables, arrays -> 1-based tables; a JSON null
        // becomes nil (an absent key in an object, a hole in an array — the standard Lua-JSON trade-off);
        // integral numbers come back as Lua ints. Malformed input, or input over the size/depth caps
        // (-Dhaven.addon.json.maxlen / .maxdepth), throws a pcall-able LuaError. encode(value) -> compact
        // JSON and is STRICT (a function/userdata/thread, a reference cycle, or a non-finite number throws)
        // so the result is always valid JSON — unlike the REPL echo's forgiving Json.write.
        LuaTable json = new LuaTable();
        json.set("parse", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "json", "parse");
                LuaValue str = Args.required(a, 2, "hafen.json():parse", "text");
                if(!str.isstring())
                    throw new LuaError("hafen.json():parse(text) expects a string");
                String s = str.tojstring();
                if(s.length() > Json.MAX_INPUT)
                    throw new LuaError("hafen.json():parse: input too large (" + s.length()
                        + " > " + Json.MAX_INPUT + " chars)");
                Object parsed;
                try {
                    parsed = Json.parse(s, Json.DEFAULT_MAX_DEPTH);
                } catch(RuntimeException e) {
                    throw new LuaError(e.getMessage());  // "JSON: <msg> at offset <n>" -> pcall-able
                }
                return LuaMarshal.jsonToLua(parsed, owner);
            }
        });
        json.set("encode", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "json", "encode");
                LuaValue v = Args.required(a, 2, "hafen.json():encode", "value");
                return LuaValue.valueOf(Json.write(v, true));   // strict: non-serializable -> LuaError
            }
        });
        Section.install(hafen, "json", json);

        // hafen.http — external HTTP requests (N2a / D-037), gated by a manifest "network" host allowlist.
        HttpApi.install(hafen, owner);

        // hafen.event():on(key, fn) -> a Sub; sub:off() ends it. The bus is the door for a notification with
        // no object to hang off (041 R2: ¿tienes el objeto? obj:on(...); ¿no? hafen.event()), and it is the
        // same one verb every emitter answers. The section is singular like every other one: an event NAME
        // keeps its plural (MarkersChanged is a sentence), the section does not.
        //
        // The key set is CLOSED (D-129): the client fires all 26 and knows them at load, so an unknown one
        // throws rather than being accepted and never firing. The four lifecycle keys dropped their On prefix
        // in 041 (:on already says it), and those four spellings throw naming their replacement.
        LuaTable event = new LuaTable();
        event.set("on", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "event", "on");
                LuaValue nm = Args.required(a, 2, "hafen.event():on", "key");
                LuaValue fn = Args.required(a, 3, "hafen.event():on", "fn");
                if(!nm.isstring() || !fn.isfunction())
                    throw new LuaError("hafen.event():on(key, fn) expects (string, function)");
                String key = nm.tojstring();
                String retired = Retired.eventKey("hafen.event()", key);
                if(retired != null)
                    throw new LuaError(retired);
                if(!busKey(key))
                    throw new LuaError("hafen.event():on(key, fn): unknown event '" + key + "' — see"
                        + " docs/addons/api/event.md for the catalogue");
                if(key.startsWith("GobOverlay"))   // 038.3: arm the two Gob seams (see `overlaySubs`)
                    overlaySubs = true;
                return owner.subs.on(key, fn);
            }
        });
        // hafen.event():action() / hafen.event():message() — the two message streams (041.2), which are the
        // bus's other two doors rather than a section of their own: a "click" can come from ANY widget and a
        // "set" can go to ANY widget, so neither has an object to hang off (spec R2). Each is an emitter over
        // its own Subs, and each takes no arguments — the stream IS the object, and you subscribe on it.
        //
        // Their key sets are OPEN, unlike the bus's own (D-129): a wdgmsg/uimsg name is PROTOCOL, not a
        // catalogue the client owns, so refusing an unknown one would refuse a legitimate message the server
        // introduces tomorrow.
        final LuaValue actions = stream("action", owner.actionSubs);
        final LuaValue messages = stream("message", owner.messageSubs);
        event.set("action", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "event", "action");
                if(Args.passed(a, 2))
                    throw new LuaError("hafen.event():action() takes no arguments — it IS the outbound action"
                        + " stream, and you subscribe on it: hafen.event():action():on(msg, fn)");
                return actions;
            }
        });
        event.set("message", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "event", "message");
                if(Args.passed(a, 2))
                    throw new LuaError("hafen.event():message() takes no arguments — it IS the inbound message"
                        + " stream, and you subscribe on it: hafen.event():message():on(msg, fn)");
                return messages;
            }
        });
        Section.install(hafen, "event", event);

        // hafen.timer() — the addon's own scheduling, and a section that contains exactly ONE thing is that
        // thing (§2.1): the section object IS the collection of this addon's live timers. :after(s, fn) runs
        // once and :every(s, fn) repeatedly, both handing back a handle whose :cancel() stops it; :list(),
        // :count() and :find(pred) read what is still scheduled. A timer has no name, so a string filter is
        // refused rather than silently matching nothing.
        LuaTable timerVerbs = new LuaTable();
        timerVerbs.set("after", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaCollection.receiver(a.arg1(), "after");
                return newTimer(owner, a.arg(2), a.arg(3), false);
            }
        });
        timerVerbs.set("every", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaCollection.receiver(a.arg1(), "every");
                return newTimer(owner, a.arg(2), a.arg(3), true);
            }
        });
        Section.mount(hafen, "timer", LuaCollection.create("hafen.timer()", new LuaCollection.Source() {
            public java.util.List<LuaValue> members() {
                java.util.List<LuaValue> out = new java.util.ArrayList<LuaValue>();
                for(Timer t : owner.timers) {
                    if(t.alive && (t.handle != null))
                        out.add(t.handle);
                }
                return out;
            }
        }, timerVerbs), null);

        // hafen.store() — saved variables (1e / D-002 / D-023). One Lua table per manifest-declared saved
        // variable, persisted to JSON under savedata/. :get(name) hands back that table — the LIVE persisted
        // one, never a copy, so hafen.store():get("cfg").foo = 1 still saves; an undeclared name throws listing
        // the declared ones, because the set is closed by the manifest at load. :flush() forces a write now.
        // Per-character vars are restored at EnterWorld (the <genus>_<char> folder is only known then);
        // account-scope vars are loaded here, before the addon's files run, so they are ready in the file body /
        // Load. The table object for each name is STABLE for the addon's whole life (restore fills it in
        // place), so a cached reference stays valid. This is the one section whose ACCESS PATTERN changed
        // rather than its spelling, so the old field form throws from a per-owner __index built off the
        // manifest (StoreApi.index) — a static retired table cannot know an addon's own variable names.
        StoreApi.installStore(hafen, owner);

        // Every retired spelling throws naming its replacement rather than reading as nil (§2.10). The section
        // tables carry their own verbs' rows; this one carries the sections whose NAME changed.
        Retired.install(hafen);

        g.set("hafen", hafen);
    }

    private static LuaValue newTimer(final Addon owner, LuaValue sec, LuaValue fn, boolean repeat) {
        if(!sec.isnumber() || !fn.isfunction())
            throw new LuaError("hafen.timer():" + (repeat ? "every" : "after")
                + " expects (seconds, function)");
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
        t.handle = h;    // so hafen.timer():list() hands back the SAME handle the caller holds
        return h;
    }

    // ------------------------------------------------------------- logging (hafen.log():write + console output)

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
     * Stdout-only diagnostic — unlike {@link #log(String)}, never posted to the in-game notice. For a
     * refusal or give-up that is expected/benign (nothing for the player to act on): e.g. {@link
     * Resolve}'s "not waitable" and "gave up after N retries" paths, which can fire routinely (an
     * equipped item's sprite still building) and would otherwise spam the chat with an internal
     * plumbing detail every time.
     */
    static void logDiag(String msg) {
        System.out.println("[addon] " + msg);
    }

    /**
     * How an addon is named to the user — its manifest id ({@code "(console)"} for the {@code :lua} REPL). Used by
     * {@link #log(Addon, String)} and by any message that has to name <i>another</i> addon, e.g. the 031.2 refusal
     * when a second addon tries to take a window that is already owned.
     */
    static String ownerName(Addon a) {
        return ((a != null) && (a.manifest != null)) ? a.manifest.id : "addon";
    }

    /** Addon-level output ({@code hafen.log():write} + handler errors): tagged with the addon id. */
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
     * {@code ui.root} — right at {@code EnterWorld} the map view exists (it fired the event) but may
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

    /**
     * Parse a Lua colour table (0..255 components) into a {@link java.awt.Color}, or {@code dflt}. Shared by
     * map-marker pins (MapApi), ghost/entity {@code tint} (VrApi), the {@code g:text} draw
     * wrapper and the stylesheet's {@code color} property (033.2) — a hub color util.
     *
     * <p><b>Two spellings, one shape.</b> The KEYED form {@code {r=,g=,b=[,a=]}} is what every reader in this API
     * hands back ({@link #color}, {@code kin:color()}, {@code meter:color()}), so it is what a round-trip writes;
     * the POSITIONAL shorthand {@code {r,g,b[,a]}} is what the docs and every hand-written literal actually say.
     * Accepting only the first made the second <b>silently do nothing</b> — the worst possible answer to a colour
     * that reads exactly like the documentation. The keyed form is checked first; a table carrying neither is
     * {@code dflt}, which each caller turns into its own refusal or fallback.
     */
    static java.awt.Color luaColor(LuaValue t, java.awt.Color dflt) {
        LuaValue r = t.get("r"), g = t.get("g"), b = t.get("b"), a = t.get("a");
        if(!r.isnumber() || !g.isnumber() || !b.isnumber()) {
            r = t.get(1); g = t.get(2); b = t.get(3); a = t.get(4);   // the positional shorthand {r,g,b[,a]}
            if(!r.isnumber() || !g.isnumber() || !b.isnumber())
                return dflt;
        }
        int ai = a.isnumber() ? clampByte(a.toint()) : 255;
        return new java.awt.Color(clampByte(r.toint()), clampByte(g.toint()), clampByte(b.toint()), ai);
    }

    /** Clamp an int to a 0..255 colour byte. */
    static int clampByte(int v) {
        return (v < 0) ? 0 : ((v > 255) ? 255 : v);
    }

    /**
     * A <b>colour argument</b> to a setter, in the one spelling the uniform grammar gives colours: positional
     * components, {@code x:tint(r, g, b[, a])}. A colour <i>value</i> read back from anywhere in the API passes
     * straight through as well, so {@code s:tint(other:tint())} is one call and the read and the write of one
     * property genuinely take the same thing.
     */
    static java.awt.Color colorArg(Varargs a, int i, String verb) {
        LuaValue v = a.arg(i);
        if(v.istable()) {
            java.awt.Color c = luaColor(v, null);
            if(c == null)
                throw new LuaError(verb + "(color): a colour value is {r, g, b[, a]} (0..255)");
            return c;
        }
        LuaTable t = new LuaTable();
        int n = 0;
        for(int j = i; (j <= a.narg()) && a.arg(j).isnumber(); j++)
            t.set(++n, a.arg(j));
        java.awt.Color c = luaColor(t, null);
        if(c == null)
            throw new LuaError(verb + "(r, g, b[, a]) expects three or four numbers 0..255, or a colour value"
                + " read back from the API");
        return c;
    }

    /** The colour READ every colour property in the API hands back: the {@code {r, g, b, a}} value. */
    static LuaValue colorValue(java.awt.Color c) {
        if(c == null)
            return LuaValue.NIL;
        LuaTable t = new LuaTable();
        t.set(1, LuaValue.valueOf(c.getRed()));
        t.set(2, LuaValue.valueOf(c.getGreen()));
        t.set(3, LuaValue.valueOf(c.getBlue()));
        t.set(4, LuaValue.valueOf(c.getAlpha()));
        return t;
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

    /*
     * The event-subscription record used to be here, beside the Timer below. It is gone (041.1): a
     * subscription is an entry in the emitter's own Subs and the LuaSub handle Lua holds is that entry, so
     * there is no second object to keep in step — and no `Sub` sitting one character away from `Subs` in the
     * same package for a later reader to confuse.
     */

    /** A live timer: {@code due} is engine-clock seconds; {@code interval<=0} means one-shot. */
    public static final class Timer {
        final Addon owner;
        double due;
        final double interval;
        final LuaValue fn;
        boolean alive = true;
        /** The Lua handle this timer was handed out as, so {@code hafen.timer():list()} answers by identity. */
        LuaValue handle;

        Timer(Addon owner, double due, double interval, LuaValue fn) {
            this.owner = owner;
            this.due = due;
            this.interval = interval;
            this.fn = fn;
        }
    }


    // ------------------------------------------------------------- shared hub utilities (keybind panel)

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
    /**
     * A HUD overlay ({@code hafen.ui():overlay()}): a draw fn painted on top of the HUD each frame (2b).
     * Built <b>bare</b> since 039.6 — {@code fn} is installed by {@code :onDraw(fn)} and is {@code volatile}
     * because the paint pass reads it while Lua writes it. A bare overlay paints nothing, which is the same
     * "incomplete draws nothing" rule the widget builder gets from not yet being in the tree.
     */
    public static final class HudOverlay {
        final Addon owner;
        volatile LuaValue fn;
        volatile boolean active = true;

        HudOverlay(Addon owner) {
            this.owner = owner;
        }
    }

    /* The GobOverlay record (a filter + a draw fn, swept against every gob) is GONE — 038.1 moved the state
     * onto the gob itself, where an overlay is keyed rather than matched. See LuaGobOverlay. */

    /**
     * A gob-overlay add/remove captured off-thread (or inside a Lua call), awaiting UI-thread dispatch — 038.3.
     * {@code owner} is the addon whose overlay it is, or {@code null} for one of the game's own, which is also
     * what decides who is told (see {@link #fireGobOverlay}). The <b>gob id</b> is held rather than the Gob: a
     * removal is often the last thing that happens to it, and the payload's Gob is minted per subscriber anyway.
     */
    private static final class OverlayEvent {
        final boolean added, nat;
        final long gobId;
        final String key;
        final Addon owner;

        OverlayEvent(boolean added, long gobId, String key, boolean nat, Addon owner) {
            this.added = added;
            this.gobId = gobId;
            this.key = key;
            this.nat = nat;
            this.owner = owner;
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
    // hafen.store persistence, and hafen.json():encode. See io.brodgar.addon.Json.
}
