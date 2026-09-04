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
import haven.ChatUI;
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
import haven.res.lib.obst.Obstacle;
import haven.MCache;
import haven.MenuGrid;
import haven.MiniMap;
import haven.Moving;
import haven.Music;
import haven.OCache;
import haven.QuestWnd;
import haven.RenderLink;
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
import haven.Waitable;
import haven.WItem;
import haven.Widget;
import haven.Window;
import haven.WoundWnd;
import haven.render.RenderTree;
import haven.resutil.Curiosity;
import io.brodgar.prof.Prof;
import io.brodgar.session.Sessions;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.ThreeArgFunction;
import org.luaj.vm2.lib.TwoArgFunction;
import org.luaj.vm2.lib.VarArgFunction;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
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
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

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
 * <em>enqueues</em> deltas that {@link #tick(UI)} drains and dispatches on the UI thread.
 */
public final class AddonManager {

    /**
     * <b>The loaded addons — the client's, not a login's</b> (074.2, settling the row {@code 073}'s census
     * deferred to this feature). It was per-session in everything but its declaration while {@code init}
     * replaced the whole set on every anchor change; deleting that made the deferral answer itself, and the
     * answer is the opposite of what deferring it assumed. An {@code Addon} is loaded once for the client,
     * outlives every session, and reaches sessions rather than belonging to one.
     */
    static final List<Addon> addons = new CopyOnWriteArrayList<Addon>();
    static Addon consoleOwner;      // the :lua REPL, as a resource owner (persists across sessions)

    // -- engine runtime state ---------------------------------------------------------------------
    // 073.1: the engine's own per-session state stands in ONE {@link SessionState} per session, reached
    // through {@link #state(UI)} and through no field anything writes by hand. What is left here is what
    // does NOT name one login's things, and each of those has its verdict and its reason written down in
    // specs/073-caches-know-their-session/census.md, which is where the split is decided once.

    /** How long SessionEnteredWorld waits for the server to place the action menu before firing regardless. */
    private static final double MENU_WAIT = 5.0;
    // 074.2: the engine clock a timer is due on — process-wide with the addons whose timers it measures (the
    // census row deferred to this feature, settled with `addons`). It accrues on the LAYER's tick, so it counts
    // one second per second however many sessions are up, and it is never reset: a clock that restarted would
    // make a timer set before a switch due at an instant that has already passed.
    static double clock;
    // 038.3: `overlaySubs` is a FAST PATH, not a correctness gate: Gob.addol runs on the loader threads for
    // every decoration the server sends, so the seam must cost one volatile read when nobody listens. It is
    // set by a subscription and cleared per session/reload; a stale `true` (someone unsubscribed) only means
    // the drain finds no subscriber and drops the event, which is exactly what hasSub does for every other
    // event here. It ARMS a seam in Gob, which belongs to no session, so it stays one flag for the client.
    static volatile boolean overlaySubs;
    // 113.3: the same fast path, for the ResDrawable.$cres.apply seam -- it runs on the delta stream for
    // every gob that carries state bytes (most resource-drawn objects, whether or not they ever change),
    // so it must cost one volatile read when nobody listens. Set by a subscription, cleared per reload.
    static volatile boolean sdtSubs;
    // 114.1: the same fast path, for the render gate in Gob.added -- it sits on every entry into a render
    // tree, so an object arriving must cost one volatile read when nobody is listening for GobAdded. Set by
    // a subscription, cleared per reload. A stale `true` (the last subscriber went away) costs a hold that
    // the very next drain releases, which is the same beat the object would have been drawn on anyway.
    static volatile boolean gobAddedSubs;
    // 042.1: the Resolve (M2) marshalling queue — a Loading's wnotify() runs on whichever thread finished
    // the load (Loader, Defer pool), so a retry callback never touches Lua directly; it enqueues here and the
    // layer's tick drains it on the UI thread (P5), same shape as the per-session queues in SessionState. A
    // retry is owned by an ADDON and cancelled by that addon's teardown, so it is indexed where `addons` is —
    // process-wide since 074.2, with the set it is indexed by, and the seam is handed a bare Runnable that
    // names no session anyway.
    private static final Queue<Runnable> resolveQueue = new ConcurrentLinkedQueue<Runnable>();

    // -- widget-tree read mechanism (spec 14): Locator + Adapters + inbound-uimsg update hook -------
    // Adapters read a GameUI widget tree into a Lua snapshot and fire a semantic event on change. The
    // UI.uimsg core tap runs off the UI thread, so it only marks the interested adapter(s) dirty; the
    // tick re-reads + fires on the UI thread (principle P5). Both collections are session-scoped.

    // -- saved variables (spec 1e / D-002 / D-023): hafen.store persisted as JSON under savedata/ ------
    // Per-character vars key on <genus>_<char>, known only once the HUD is up (SessionEnteredWorld) —
    // captured here and reused on flush so a relog (which rebinds ui before the new GameUI exists) writes to
    // the OLD character's folder. Account-scope vars need no char and load at addon-load time.

    // -- enabled set + reload (spec 1f-2 / D-005 / D-006): which addons run, persisted client-side --------
    // WoW "apply on reload" model: toggling enable/disable updates a persisted DISABLED set (an addon runs
    // unless its id is in it — so a freshly-installed addon defaults to enabled) and marks changes pending;
    // the change takes effect on the next :reload / login, never live. Stored in the client's own
    // preferences (Utils.getprefsl → under the client folder), NOT per-character.

    // -- soft CPU-budget auto-disable (spec 1f-3 / D-018 layer 2): id -> reason for an addon torn down by the
    // per-tick CPU watchdog. Not the persisted disabled set: it is surfaced in the AddOns panel and cleared on
    // the next (re)load, so the addon gets a fresh start from a :reload and only from one.
    // 074.2: process-wide with the addons it names (the census row deferred to this feature). It lasts until a
    // reload rather than until a switch, because a switch no longer ends anything — and one addon over budget
    // is one warning, not one per character.
    static final Map<String, String> autoDisabledWarn = new ConcurrentHashMap<String, String>();

    /**
     * <b>One addon filed for quarantine</b> (126.1) — an {@link Error} escaped its Lua at {@link #callLua},
     * which no {@code pcall} of the addon's could see and which would otherwise leave the choke point, leave
     * the step and end the UI thread. The failure is contained where it happened; the tear-down is not, so
     * this is what carries the addon from there to the safe point.
     */
    private static final class Quarantine {
        final Addon addon;
        final String reason;

        Quarantine(Addon addon, String reason) {
            this.addon = addon;
            this.reason = reason;
        }
    }

    /**
     * The quarantines {@link #callLua} has filed and {@link #enforceSoftBudget} has not yet spent (126.1).
     * A queue and not a direct call, for the reason {@link #autoDisable} states in its own javadoc: it
     * mutates {@code addons}, and {@code callLua} is called from inside the step's iteration over it — and
     * from off-thread handlers, which may not touch that list at all. Concurrent because those threads file
     * here, exactly as they write {@link #autoDisabledWarn}.
     */
    private static final Queue<Quarantine> quarantines = new ConcurrentLinkedQueue<Quarantine>();

    // -- the permissions tier (spec 12-security-and-permissions / D-010 / D-025 / D-027; refined by D-028):
    // the protected surface. Every protected verb — player:move, hand:use, the four item verbs,
    // world:click/:place/:select, pag:use, widget:send, speed:set, craft:make, slot:use/:res, the kin writes,
    // flowermenu:select/:cancel — DRIVES the character by sending a
    // player-action wdgmsg — it acts on the user's behalf (moves them, uses items, interacts with the world),
    // which is powerful, so each is a PER-ADDON permission granted only to an addon that DECLARED that verb's
    // own KEY in its manifest ("permissions": ["gob.click", "item.*"]) — requirePermission throws a guiding Lua
    // error otherwise. The keys are the Permission catalogue; the read/UI/event tiers are unaffected.
    //   The declaration is per VERB, not one blanket tier: what the user grants is the list they read in the
    //   consent dialog, so an addon that wants to move the character cannot also send raw widget messages.
    //   D-028: there is NO global master switch (D-027's was dropped). The tier is ALWAYS available at the
    //   system level; the user's control is entirely PER-ADDON: a declaring addon is DISABLED BY DEFAULT
    //   (opt-in; the persisted CONSENTED set — what the user approved, per addon — distinguishes a new
    //   declaration from one they deliberately chose, handled in scanAddonDefaults) and enabling it in the
    //   AddOns panel raises a CONSENT DIALOG (slice 4c). So a running declaring addon is, by construction, one
    //   the user knowingly permitted for exactly these keys — which is why requirePermission need only re-check
    //   the manifest declaration, with no runtime switch. It stays server-authoritative regardless: an addon
    //   can only ever send what a player click could send.

    private AddonManager() {
    }

    // ------------------------------------------------------------- the permission gate

    // 048.7: actionsGranted(owner) is DELETED with hafen.act():enabled(), its only caller. It was literally a
    // static fact about the CALLING addon's own manifest file — and D-028 had already removed the global switch
    // it was built to report, so the one caller it could answer `false` was an addon that can read the same
    // answer in its own manifest.json. A feature-detection verb whose answer is a fact about the caller detects
    // nothing. The gate itself (requirePermission, below) is untouched in shape: the model is D-027/D-028
    // exactly as before, only its granularity — and the read of it is still gone.

    /**
     * Gate a protected verb (D-027; D-028): the calling addon must have DECLARED this verb's own catalogue key
     * in its manifest — exactly, or through the group that covers it — or this throws a guiding Lua error
     * naming the verb and the key it needs. The user's consent is enforced at ENABLE time by the AddOns panel's
     * consent dialog (a running declaring addon is already permitted for what it declared), so there is no
     * separate runtime switch. Always the FIRST statement of the verb it guards (D-213).
     */
    static void requirePermission(Addon owner, Permission perm) {
        requirePermission(owner, perm, perm.lua);
    }

    /**
     * The same gate, for a verb that is <b>not</b> the one the catalogue names. A key covers an
     * <i>action</i>, and an action can have more than one door: {@code session.close} gates
     * {@code s:close()} and {@code hafen.session():remove(s)} alike (087.3). The catalogue still owns the
     * key and the consent line — only the spelling the refusal <b>opens</b> with is the caller's own, because
     * a refusal that names a verb the author did not write sends them to fix the wrong line.
     *
     * @param lua the verb as the caller wrote it ({@code "hafen.session():remove(s)"})
     */
    static void requirePermission(Addon owner, Permission perm, String lua) {
        if((owner == null) || !owner.manifest.permissions.has(perm))
            throw new LuaError(lua + ": this addon did not declare the \"" + perm.key + "\" permission —"
                + " add \"permissions\": [\"" + perm.key + "\"] to its manifest.json (or the group \""
                + perm.group() + ".*\"). A protected verb is granted per key, and the user approves the list"
                + " when they enable the addon.");
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
                // The console can flip the enabled bit, but it cannot GRANT: a declaration the user has not
                // consented to is defaulted back to disabled on the next scan (D-027/D-028, and the same
                // reason "Enable all" skips one). Say so here rather than letting the reload look broken.
                String pending = AddonRegistry.consentPending(args[2]);
                if(pending != null)
                    log("addon '" + args[2] + "' asks for " + pending + " — enable it in Options > AddOns"
                        + " instead: the consent dialog is the only way to grant a permission, so it stays"
                        + " disabled until you approve it there");
                else
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

    // ------------------------------------------------------------- which UI

    /**
     * <b>The addon layer's own tree</b> (074.1) — the {@code UI} an addon's own windows are built into, which
     * is not a session's and never becomes one. It has a null {@code Session}, it is drawn above whichever
     * session holds the screen and above the login screen when none does, and it is built once for the life of
     * the client — so a window in it keeps its place, its focus and any grab it holds across a character
     * switch, because nothing about it moves.
     *
     * <p><b>Derived, never stored</b>, for {@link #screen()}'s reason one line down: {@code UILoop} owns every
     * {@code UI} the client has, and a second copy of which one is the layer is a copy that can disagree.
     * {@code null} before {@link Sessions#init} has a loop, exactly as {@code screen()} is.
     */
    static UI layer() {
        return Sessions.layer();
    }

    /**
     * <b>The screen</b> (072.3) — the {@code UI} the client actually draws, which is where the pointer is, what
     * the modifier keys are being held over, and where a message printed for the user lands. <b>There is one
     * pointer and one coordinate space however many sessions are live</b>, so a verb reading it never has a
     * session to be handed.
     *
     * <p><b>It is one of three names, and the whole of the choice</b> (078.4). A tree read is one of exactly
     * three questions, and the site says which by the name it calls: {@link #sessionui(String)} for <i>that
     * character's</i> widgets, {@link #layer()} for the addon's own windows, and this for the screen. <b>There
     * is no fourth name</b> meaning "whichever session happens to be drawn, for want of an address": every site
     * that could call one is a site that has stopped saying which session it meant, and the three above are the
     * whole vocabulary. Nothing derived from a widget in hand asks here either — the tree guarding a widget,
     * holding its id and receiving its restore is {@code w.ui}, and a site with a widget already has its
     * answer.
     *
     * <p><b>Derived, never stored.</b> It answers {@link Sessions#anchor()} rather than a field kept in sync by
     * hand, which is the shape {@code 071} spent three tasks deleting one layer down: two copies of "which
     * session is drawn" disagree eventually, and the one that disagrees is the one nothing reads on the tick.
     * Since 074.2 nothing could keep such a field anyway — no moment tears the drawn session down and rebuilds
     * it.
     *
     * <p><b>It answers the login screen when the client holds no session</b>, because that is what is drawn —
     * and since 074.2 the addon layer is running there, loaded and ticking, with every session-shaped read
     * refusing for want of a session rather than for want of an engine.
     *
     * <p>{@code null} before {@link Sessions#init} has a loop, and the {@link UI#root} of what it answers may
     * be null as well — every caller guards.
     */
    static UI screen() {
        return Sessions.anchor();
    }

    /**
     * <b>The drawn {@link MapView}</b> (072.3) — the 3D scene on screen, and the one every world verb aims at.
     * Never takes a session, for {@link #screen()}'s reason: the client draws one scene.
     *
     * <p><b>Derived, and cached against the thing it is derived from.</b> The answer is the {@code MapView} in
     * {@code screen()}'s own tree, which {@link Sessions#mapview} finds with a recursive walk — far too much for
     * {@code SurfaceInput}'s per-panel-per-frame origin read or for the per-entity ground pass. So the walk is
     * paid once per view and the result kept, exactly as {@code Sessions.Member.gameui()} keeps its {@code
     * GameUI} and for the same measured reason. <b>This is not the field it replaces</b>: that one was written
     * by hand from the {@code MapView} constructor and could therefore disagree with the session on screen,
     * where every read of this one re-checks its own answer — a cached view whose {@code ui} is no longer the
     * drawn one, or that has left its tree, fails the test and is derived again. A stale answer is not
     * reachable, only a repeated walk is.
     *
     * <p>{@code null} before the world is up, which every caller already guards for.
     */
    static MapView screenView() {
        UI u = screen();
        if(u == null)
            return null;
        MapView mv = viewcache;
        if((mv != null) && (mv.ui == u) && (mv.parent != null))
            return mv;
        return viewcache = Sessions.mapview(u);
    }

    /** {@link #screenView()}'s memo. Volatile: written from whichever thread first re-derives, and a lost
     *  race costs one extra walk, never a wrong answer — the reader re-checks what it reads. */
    private static volatile MapView viewcache;

    // ------------------------------------------------------------- whose state it is (073.1)

    /**
     * <b>One session's worth of the engine's own runtime</b> (073.1) — the tick pump attached to that
     * session's tree, the gob source registered on its {@link OCache}, how far it has got into the world, and
     * the queues its own seams fill from the Loader and network threads.
     *
     * <p><b>What belongs here is what names one login's things.</b> A widget in a queue is a widget of one
     * tree; a {@link GobEvent} names a gob id, which means a different object in the next session; "the world
     * came up" is one session's world. What does not is left where it was, with its reason written down in
     * the census — the engine clock the addons' timers are due on, the volatile that arms a seam in
     * {@link Gob}, the two queues whose seam is handed no session at all.
     *
     * <p><b>Threading.</b> The queues are concurrent because their seams are not the UI thread's — that is
     * unchanged, and all that moved is which queue an enqueue picks. Everything else here is written on the
     * UI thread, except the two flags a seam off it raises, which are volatile exactly as they were.
     */
    static final class SessionState {
        /** The session this state is for. Held so the sweep and the accessor can say so; never read as
         *  "the session", which is the ambient answer this whole sequence is deleting. */
        final UI ui;

        /** The attached tick widget, on <i>this</i> session's root. */
        AddonRoot addonRoot;
        /** Strong ref to this session's gob callback: {@link OCache} keeps callbacks weakly. */
        OCache.ChangeCallback ocCb;

        /** Set off-thread (the {@link MapView} that came up), read on the tick. */
        volatile boolean enterWorldPending;
        /** When this session's {@link GameUI} entered the tree; -1 = not yet (UI thread). */
        double hudUpSince = -1;
        /** A {@code :reload} queued against this session (set off the tick, applied on it). */
        volatile boolean reloadPending;
        /** Re-entrancy guard for {@link #dispatchAction}: a handler body that itself sends a {@code wdgmsg}. */
        boolean dispatchingAction;

        /** Gob spawn/despawn from this session's {@link OCache}, captured on the network/loader threads.
         *  Filled per session and drained by the LAYER (079.4): the deltas are one session's, the object they
         *  are about is the client's, and the edge cannot be settled until every session's are in. */
        final Queue<GobEvent> gobEvents = new ConcurrentLinkedQueue<GobEvent>();
        /** 038.3: the same marshalling for the two gob-overlay events. */
        final Queue<OverlayEvent> overlayEvents = new ConcurrentLinkedQueue<OverlayEvent>();
        /** 113.3: the same marshalling for a gob's state-byte change ({@code ResDrawable.$cres.apply}'s seam). */
        final Queue<SdtEvent> sdtEvents = new ConcurrentLinkedQueue<SdtEvent>();
        // 112.3: the widget-ENTRY seam — Widget.add0 runs under synchronized(w.ui) on whatever thread placed
        // the widget (a Loader thread applying a server update, the UI thread for a client-side add), so the
        // tap only enqueues; the LAYER's step drains it (drainEnteredWidgets), holding no tree monitor, which
        // is what lets an s:ui():on(sel, "Added") handler build a window and write any tree. Drained BEFORE
        // the removals below, so a widget that came and went in one frame can never report Added after
        // Removed. Not a per-session drain: this one must run where nothing is held.
        final Queue<Widget> enteredWidgets = new ConcurrentLinkedQueue<Widget>();
        // 112.3: the item-info seam — GItem.info()'s build runs on whichever thread asked for the item first,
        // and the draw that usually asks holds that tree's monitor, so the tap only enqueues and the layer's
        // step fires item:on("Changed", fn). It also takes the fire-side read of Addon.itemSubs — a plain
        // WeakHashMap — off whichever thread happened to build the info, and onto the step.
        final Queue<GItem> itemInfos = new ConcurrentLinkedQueue<GItem>();
        // 042.1: the widget-removal seam (M1) — Widget.remove() runs on whatever thread reached it (a Loader
        // thread under synchronized(ui) from the server command queue, or the UI thread from a client-side
        // destroy()), so the tap only enqueues; tick() drains one frame's worth (D-106) and dispatches to the
        // adapters that fire *Removed.
        final Queue<Widget> removedWidgets = new ConcurrentLinkedQueue<Widget>();
        // 128.1: the widget-DISPOSAL seam — Widget.rdispose() runs on whatever thread reached destroy() (the
        // same uncertainty as onWidgetRemoved), and the retirement it feeds is a WRITE to a map the fire path
        // reads every frame, so the tap only enqueues; the step drains it and drops every addon's
        // widget:on() subscriptions on the widget. Every disposed widget in the tree passes through here,
        // including the client's own, so the per-widget cost stays one map lookup per addon.
        final Queue<Widget> disposedWidgets = new ConcurrentLinkedQueue<Widget>();
        // 042.6: the deferred-belt-write notify — two of GameUI's five setbelt/setbelt2 paths write belt[slot]
        // from a glob.loader.defer task that runs on a Loader thread AFTER the uimsg tap already fired (D-178:
        // the notify goes where the write lands, not where the message arrived), so this only enqueues.
        final Queue<Integer> beltSetQueue = new ConcurrentLinkedQueue<Integer>();
        // 042.10: the geometry seam (M4) — Widget.resize() runs on whatever thread reached it (same
        // uncertainty as onWidgetRemoved), so the tap only enqueues; tick() drains one frame's worth (D-106)
        // and offers each to Layout.dispatchResized, which is free when nothing is anchored.
        final Queue<Widget> resizedWidgets = new ConcurrentLinkedQueue<Widget>();
        // 061.6: the text level's re-apply queue — a server update that rewrites a Label's, a Button's or a
        // Window's caption may have painted over a level an addon holds (widget:text(s)/:title(s)). The tap
        // that sees it (onUimsg) runs on a Loader thread OUTSIDE the ui monitor, and text rasterisation
        // belongs on the UI thread anyway, so it only records the widget; tick() re-reads and re-applies.
        final Queue<Widget> textRewrites = new ConcurrentLinkedQueue<Widget>();
        // 110.2: the three chat seams — ChatUI.add, ChatUI.cdestroy and ChatUI.select all run on the thread
        // that applies this session's server update, so they only enqueue {key, channel} and the tick fires
        // them. The chat is one login's HUD, so the queue is that login's state and the payload needs no
        // second lookup to say whose character it was.
        final Queue<Object[]> chatEvents = new ConcurrentLinkedQueue<Object[]>();

        // ---- the widget layer (073.2) ------------------------------------------------------------
        // Every one of these names WIDGETS OF ONE TREE, and each is filled from the widget it is about:
        // w.ui at the placement, removal and caption seams, the handle's own ui at a press, the tree a
        // builder attached to. None of them is reached through screen(), which answers "the session on
        // screen" — the taps that fill them run on a Loader thread as often as not, and an addon teardown
        // walks whatever that addon owns across every tree it drew into (074.2: one addon, many sessions).
        // See the reason column of census.md's widget-layer table for each.

        /** {@code s:ui():on(sel, …)} subscriptions watching THIS tree ({@link UiApi}, 030.2). */
        final List<LuaSelectorWatch> selectorWatches = new CopyOnWriteArrayList<LuaSelectorWatch>();
        /** Widgets of this tree still awaiting a late {@code [title=]}/{@code [res=]} (030.2's re-check). */
        final List<UiApi.PendingMatch> selectorPending = new CopyOnWriteArrayList<UiApi.PendingMatch>();
        /** Windows of this tree whose caption changed, for that re-check (042.9/049.3). Appended off the
         *  UI thread by the caption seam, drained on this session's own tick. */
        final Queue<Widget> selectorCapChanged = new ConcurrentLinkedQueue<Widget>();
        /** {@code widget:on("ItemAdded"/"ItemRemoved"/"Removed", fn)} records on widgets of this tree (041.4). */
        final List<WidgetSubs> widgetSubsWatching = new CopyOnWriteArrayList<WidgetSubs>();
        /** Surfaces built into this tree since its last tick and not yet painting (039.6's arming tick). */
        final List<Owned> unarmed = new ArrayList<Owned>();
        /** Widgets of this tree whose layout rule may still start matching ({@link Layout}, 036.2). */
        final List<Layout.Pending> layoutPending = new CopyOnWriteArrayList<Layout.Pending>();
        /** Has a caption changed in THIS tree since its last tick ({@link Layout}, 092.5)? Volatile: the
         *  caption seam runs on the Loader thread that applied the message, and the drain is this tree's own
         *  tick. Per session because {@link SessionState#layoutPending} is: one flag for the client meant the
         *  first tree to drain it cleared it for every other. */
        volatile boolean layoutCapDirty;
        /** Windows of this tree whose caption invalidated a cached style ({@link Sheet}, 049.3). */
        final Queue<Widget> styleCapChanged = new ConcurrentLinkedQueue<Widget>();
        /** Popups of this tree to re-raise before the next draw ({@link CDropdown}, 040.10). */
        final List<Widget> dropdownRaises = new ArrayList<Widget>();
        /** The gesture running on this tree right now — normally none, and at most one (062). */
        final List<Gesture> gesturesRunning = new CopyOnWriteArrayList<Gesture>();
        /** Every widget standing in THIS session's 3D world (044.1) — the render pass walks one session's. */
        final CopyOnWriteArrayList<WidgetSurface> surfaces = new CopyOnWriteArrayList<WidgetSurface>();

        // ---- the HUD readers (073.3) -------------------------------------------------------------
        // Each of these names ONE CHARACTER'S HUD. An adapter reads a GameUI, and a session has one; a
        // slot index names a bar, and each character has their own. Both are reached with the ui of the
        // widget the seam was handed — w.ui at the uimsg and placement taps, the GameUI itself at the three
        // seams inside it — and never through screen(), for the reason the widget layer above gives.

        /** The nine change-detection adapters reading THIS session's HUD ({@link CharApi}). Built with the
         *  state and never rebuilt: a session's HUD does not become another HUD because the player tabbed,
         *  and the widgets these cache are still standing in the tree they were found in.
         *  092.4: and each is handed the state, so the seven that read a HUD by ACCOUNT read this one's --
         *  before, they were held per session and every one of them read the DRAWN session's widgets. */
        final List<CharApi.TreeAdapter> treeAdapters;
        /** Which of those an inbound {@code uimsg} marked. Concurrent: the tap that marks is off-thread,
         *  and the tick that drains is this session's own. */
        final Set<CharApi.TreeAdapter> treeDirty = ConcurrentHashMap.newKeySet();
        /** Slot index &rarr; the hold on it, on THIS character's action bar ({@link BeltHold}). */
        final Map<Integer, BeltHold.Hold> beltHolds = new HashMap<Integer, BeltHold.Hold>();
        /** Slot index &rarr; the identity of the entry that BELONGS there — this character's placements.
         *  Sorted, so one state has one serialization and an unchanged map costs no disk write. */
        final Map<Integer, String> beltPlaced = new TreeMap<Integer, String>();
        /** Has {@link #beltPlaced} changed since the last write? Marked on the message thread too. */
        boolean beltDirty;
        /** The last serialization written (or read) for this character, so an unchanged map writes nothing. */
        String beltLastJson;

        // ---- the world (073.4) -------------------------------------------------------------------
        // A session coordinate names ONE LOGIN'S world: the origin the server re-bases whenever it drops the
        // map is that login's own. It is reached with the ui of the thing the seam was handed — the MiniMap
        // that re-based, the map file a notify names — and never through screen(), which answers the scene
        // being DRAWN while the seams here run on a loader thread and on the disk layer's own. A marker ref
        // is NOT one of them (075.2): there is one database per (store, filename) for the client, so the
        // Marker objects behind the refs are the client's too.
        //
        // 075.3: AND NEITHER ARE THE TWO ENTITY REGISTRIES. They were here on the reasoning that a gob id
        // "means a different object in the next session" and that a free entity stands "in one session's
        // coordinate frame" — both wrong. A gob id is the server's, one object observed by two sessions
        // (docs/client/multi-session.md), and a free entity holds a grid id and an offset within it, which is
        // the server's naming of a place. So they are one set for the client, in VirtualApi: you stand a thing in
        // the world, and it draws for whichever character is looking at that patch of world.

        /** Has this session been told its marker count once, and at which seq ({@link MapApi}). */
        boolean markersPrimed;
        int lastMarkerSeq;
        /**
         * <b>The on-disk map database this session's HUD holds</b> (073.4) — and the one thing that can name
         * a session for a marker-change notify, which is handed a {@link MapFile} and nothing else. Claimed
         * by the session's own tick from the {@code GameUI} the file was read out of; volatile, because the
         * seam that matches against it runs on whichever thread bumped the seq (the processor's, the UI's, a
         * loader's). Two characters on one server claim the <b>same</b> file (075.2): the database is one, so
         * the bump is one event and the first claimant found carries it.
         */
        volatile MapFile mapFile;
        /** The marker-count changes captured since this session's last tick (042.11, per session since 073.4). */
        final Queue<Integer> markerChanges = new ConcurrentLinkedQueue<Integer>();

        // ---- the rest (073.5) --------------------------------------------------------------------
        // An in-flight request and a character folder both name ONE LOGIN'S things: the request was made
        // by an addon running for one session and its callback has to reach that session's tick, and
        // "<genus>_<char>" is the character that session is playing. Both are reached through the addon
        // that owns them ({@link Addon#state()}), which since 074.2 is the session on SCREEN — an addon is
        // the client's now and runs beside every session it holds, so the one it is acting for is the one
        // being looked at. A pool thread has no tree to read, which is why the answer is resolved on the UI
        // thread and closed over rather than asked again when the completion lands.

        /** Finished requests of this session's addons, captured on a pool thread ({@link HttpApi}). */
        final Queue<HttpApi.HttpCompletion> httpResults = new ConcurrentLinkedQueue<HttpApi.HttpCompletion>();
        /** Addons of this session with a request created since its last tick ({@link HttpApi}). UI thread only. */
        final Queue<Addon> httpStarts = new ConcurrentLinkedQueue<Addon>();

        /** {@code "<genus>_<char>"} once this session is in world, else {@code null} ({@link StoreApi}) —
         *  the folder every per-character file of that session is read from and written back to. Volatile:
         *  it is written on the tick that entered the world and read by whatever thread flushes. */
        volatile String charScope;
        /**
         * <b>The per-character saved variables of this session</b> (079.1, {@link StoreApi.CharStore}), one
         * entry per addon and minted on the first ask. They are the session's rather than the screen's,
         * which is what lets {@code s:store()} answer about the character it names — the tables of two
         * logins are two objects, and neither can be handed out under the other's name.
         *
         * <p>Concurrent because the map is reached from every thread that runs Lua (the loop's tick, the
         * console's own), and because the entry for one addon is minted where it is first needed.
         */
        final Map<Addon, StoreApi.CharStore> charStores =
            new ConcurrentHashMap<Addon, StoreApi.CharStore>();
        /** Engine-clock time of this session's last throttled flush ({@link StoreApi}). */
        double storeLastAutoSave;

        SessionState(UI ui) {
            this.ui = ui;
            this.treeAdapters = CharApi.newAdapters(this);   // after ui: the adapters read it (092.4)
        }
    }

    /**
     * Every live session's state, keyed by the {@link UI} it runs in. Identity-keyed by construction:
     * {@code haven.UI} overrides neither {@code equals} nor {@code hashCode}. Concurrent because an enqueue
     * reaches it from the Loader and network threads.
     */
    private static final Map<UI, SessionState> states = new ConcurrentHashMap<UI, SessionState>();

    /**
     * <b>The engine's state for one session</b> (073.1), created on first ask — and the only door to it.
     *
     * <p><b>The key is the {@code UI}</b>, for three reasons that agree. It is what the engine already holds
     * at nearly every site ({@code w.ui} at a monitor, {@link #sessionui(String)} at a tree read); it
     * is what a relogin <i>replaces</i>, through {@code UILoop.bgui}, which is exactly the moment every cached
     * widget id, gob id and slot index stops meaning anything; and it is not {@code Sessions.Member}, which
     * survives a relogin by swapping its {@code sess} and {@code ui} and so means "this slot in the switcher".
     *
     * <p><b>There is no argumentless form.</b> An accessor that guesses which session it is about is the
     * ambient field {@code 072} spent three tasks deleting, under a longer name — every site that could call
     * it is a site that has stopped saying which session it meant.
     *
     * <p><b>{@code null} for a {@code UI} that is not a session</b>: none at all, the login screen (which
     * holds no {@code Session} and runs no addons), or one already destroyed. A destroyed {@code UI} must
     * never mint an entry here, or {@link #sweepStates()} and this would race each other forever — and the
     * caller's own guard is the same one it already had for a null {@code screen()}.
     */
    static SessionState state(UI u) {
        /* 074.1: the ADDON LAYER has a state too, and for the reason the widget-layer half of this object
         * already gives: those rows name the widgets of ONE TREE, and the layer is a tree — an arming queue, a
         * removal queue, a running gesture and a pending layout belong to it exactly as they belong to a
         * session's. What it never fills is the session-shaped half: it has no Glob to report gobs, no HUD to
         * read and no connection to answer, so those collections stay empty for the client's whole life. */
        if((u == null) || u.destroyed || ((u.sess == null) && (u != layer())))
            return null;
        SessionState st = states.get(u);
        if(st != null)
            return st;
        SessionState mk = new SessionState(u);
        st = states.putIfAbsent(u, mk);
        return (st != null) ? st : mk;
    }

    /**
     * The state to <b>file an event under</b>, which asks the one thing a read does not: <b>is anything
     * draining it?</b> A session's queues are drained by that session's own tick pump, and a session that
     * does not hold the addon engine has none — so an event filed there would sit for the life of the client.
     *
     * <p>It is also what those events did before 073.1, minus the pile: they went into <i>one</i> queue for
     * the whole client, and the session that did have the pump drained them and handed another session's
     * widgets to adapters that could only find nothing in them. Dropping them is that outcome, said out loud.
     */
    static SessionState queueState(UI u) {
        SessionState st = state(u);
        if(st == null)
            return null;
        // 074.1: the layer's step is called by the frame itself (112.1) and that tree is built with the
        // client, so the layer is always draining. A session's pump is a widget attached by init, which is
        // what addonRoot answers.
        return ((st.addonRoot != null) || (u == layer())) ? st : null;
    }

    /**
     * <b>A session's {@code UI} died, so its state does</b> (073.1) — the {@code // addon:} line at the end
     * of {@link UI#destroy()}, which is the one place every {@code UI} in the client is taken down: a session
     * ending or being handed on ({@code Sessions.Member.discard} → {@code UILoop.bgdestroy}) and the login
     * slot being replaced ({@code UILoop.newui}) both arrive here.
     *
     * <p>Hung on the {@code UI}'s death and not on the anchor moving: a session tabbed away from is still
     * live, still ticking and still answering the server, and its widgets and gobs still mean what they meant.
     * What ends them is the {@code UI} being replaced, which is what a relogin does.
     */
    public static void uiDestroyed(UI u) {
        if(u == null)
            return;
        // 079.1: ...and the tables that session's saved variables live in go with it, so they are handed on
        // before the state is dropped rather than looked for afterwards in a map they are no longer in.
        StoreApi.sessionEnded(states.remove(u));
        // 079.4: ...and every object that session alone could see has left its last session, which nothing
        // else will ever say — OCache reports an arrival and a departure and has no third callback, so a
        // cache going whole goes in silence. The held set is re-asked on the next drain.
        gobRescans++;
        // 074.2: ...and every record an addon left naming a widget of that tree. It was `init` that pruned
        // these, on a switch, for the one owner that outlived one; now that EVERY addon outlives a session,
        // the prune belongs where the tree actually ends — which is here, and is what the census's route (b)
        // says in the first place.
        UiApi.pruneDeadTrees();
        // 074.4/079.1: what this does NOT do is WRITE that session's per-character saved variables, though
        // this is where they stop being reachable. It runs on the dying session's OWN thread, and the store's
        // tables are Lua, which runs on the UI thread and nowhere else (P5). So the write is left to the
        // layer's next tick, which drains what sessionEnded filed above — and it lands in the right folder
        // because each set of tables HOLDS the one it was loaded for rather than asking a session that has
        // stopped being able to answer.
    }

    /**
     * <b>Every session's state</b>, for the handful of sweeps that are about all of them at once (073.2): an
     * addon teardown dropping records it made in whichever tree it was running in, and the two profiler
     * counters that roll one figure up for the whole client. Not a door to "the" session: nothing here picks
     * one, which is the point — a caller that wants one names it, through {@link #state(UI)}.
     */
    static Iterable<SessionState> allStates() {
        return states.values();
    }

    // ------------------------------------------------------------- the way out (079.2)

    /**
     * <b>The thread running the client's shutdown, once one is</b> (079.2) — {@code null} for the whole of
     * an ordinary run, and set by {@link AddonRegistry#shutdown} to the one thread that is allowed to enter
     * Lua from then on.
     *
     * <p>It exists because a quit has to write an addon's tables out while the frame loop is <b>still
     * running</b>: the flush must go before {@code UILoop.dispose()}, and until that call returns the UI
     * thread goes on ticking, drawing and firing handlers. Lua runs on one thread and nowhere else (P5), so
     * a second one reading those tables while a handler writes them is exactly the torn file this task is
     * closing, one layer down.
     *
     * <p>Written once, before the thread it names is started, and read on every entry into Lua.
     * {@link #quiet()} is the read.
     */
    private static volatile Thread quitThread;

    /**
     * <b>Is addon Lua closed to this thread?</b> True on every thread but the one running the shutdown, from
     * the moment the shutdown starts. Read by {@link #callLua} — the one choke point every handler, timer,
     * draw callback and file body goes through — and by the two tick entries, so the engine's own drains
     * stop as well and nothing mints a payload for a client that is leaving.
     */
    static boolean quiet() {
        Thread t = quitThread;
        return (t != null) && (t != Thread.currentThread());
    }

    /**
     * <b>Close addon Lua to every thread but {@code owner}</b> (079.2). Instant and blocking on nothing, so
     * the exit path can do it before it starts anything: from here no frame enters an addon's environment
     * again, and the client can leave whatever else does or does not happen.
     */
    static void quiesce(Thread owner) {
        quitThread = owner;
    }

    /**
     * <b>Wait out the tick or draw that was already inside when {@link #quiesce} ran</b> (079.2) — by taking
     * each tree's monitor once, the layer's and every live session's, <b>one at a time and never nested</b>,
     * which is this client's one lock direction. That is the monitor a frame holds while it runs one
     * ({@code UILoop.Frame.tick}, {@code UILoop.display}), so holding it for an instant is the whole wait for
     * a tree — and {@link #stepping} is the same instant for the ENGINE STEP, which since 112.1 runs inside no
     * tree's monitor and would otherwise be the one thing in flight that this waits for and does not find.
     *
     * <p><b>Called on the shutdown's own thread and never on the exit path's</b>, because a monitor cannot be
     * taken with a timeout: a UI thread wedged inside a frame would hold one for ever, and an exit that waited
     * here would be the hang this task exists to make impossible. On the abandonable thread it costs at most
     * the shutdown's budget, after which the flush runs anyway.
     */
    static void awaitIdle() {
        // 112.1: the engine STEP first, which is no longer inside a tree's monitor and so is no longer waited
        // out by taking one. Given up again before the first tree's is taken, so the two never nest.
        synchronized(stepping) { /* a step in flight has finished by the time this is taken */ }
        UI l = layer();
        if(l != null) {
            synchronized(l) { /* a layer tick or draw in flight has finished by the time this is taken */ }
        }
        for(SessionState st : allStates()) {
            UI u = st.ui;
            if(u != null) {
                synchronized(u) { /* ...and the same for each session's own tree */ }
            }
        }
    }

    /**
     * The backstop under {@link #uiDestroyed}: drop any state whose {@code UI} is destroyed. A missed hook is
     * a leak of one entry per relogin and would show as nothing at all — so it is checked rather than trusted,
     * and {@code states} in {@code hafen.client():profiling():session()} is what makes the check readable.
     * A handful of entries, so it costs a walk of a one-element map on the frames it runs.
     */
    private static void sweepStates() {
        for(Iterator<Map.Entry<UI, SessionState>> it = states.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UI, SessionState> e = it.next();
            if(e.getKey().destroyed) {
                StoreApi.sessionEnded(e.getValue());   // 079.1: a missed hook must not cost that character
                it.remove();                           //   their saved variables as well as their state
                gobRescans++;                          // 079.4: ...nor the world it alone could see its GobRemoved
            }
        }
    }

    /**
     * How many sessions hold engine state right now — the {@code states} figure of
     * {@code hafen.client():profiling():session()}, whose whole claim is that it <b>equals {@code live}</b>.
     * Higher means a {@code UI} died without its state going with it; lower means a session is holding none
     * yet, which is true for the beat between a member registering and its {@code UI} being built.
     * It sweeps before answering, so the number is honest on a client whose tick has stopped.
     */
    public static int stateCount() {
        sweepStates();
        // 074.1: SESSIONS, which is what the figure is named for and compared against. The addon layer holds
        // one of these too and is no session — it is one tree for the client's life, so counting it would put
        // this number one above `live` for ever and say nothing about anything.
        int n = 0;
        for(Map.Entry<UI, SessionState> e : states.entrySet()) {
            if(e.getKey().sess != null)
                n++;
        }
        return n;
    }

    /**
     * <b>How many addons are running right now</b> — the {@code addonsLive} figure of
     * {@code hafen.client():profiling():session()}. A gauge, not a total: it is what a {@code :reload} last
     * loaded, and <b>it does not move on a character switch</b>, which is the whole of what 074 changed.
     */
    public static int addonsLive() {
        return addons.size();
    }

    /**
     * <b>Engine reloads nobody asked for</b> — the {@code engineReloads} figure of
     * {@code hafen.client():profiling():session()}, and the number this feature exists to hold at <b>zero</b>.
     *
     * <p><b>Derived, not counted</b>, which is what makes it a check rather than a claim. The layer is built
     * exactly twice as often as it is torn down and rebuilt: once at boot, and once per reload the user asked
     * for. So every load beyond {@code 1 + <reloads the user asked for>} is one the engine performed behind
     * their back, which is nothing at all: the layer outlives a character switch. A counter incremented at a
     * site that does not exist would read zero for the wrong reason; this reads zero because the arithmetic
     * says so, and climbs the moment it stops being true.
     */
    public static int engineReloads() {
        int n = AddonRegistry.loadGen() - 1 - AddonRegistry.reloadGen();
        return (n > 0) ? n : 0;
    }

    // ------------------------------------------------------------- lifecycle

    /**
     * Call site — end of the MapView constructor, <b>dormant or not</b> (074.3): a session that reaches the
     * world while another holds the screen is dormant at that instant, and a constructor runs once, so
     * gating this on the drawn view is gating it on nothing ever. <b>It flags "entered
     * world", and that is all it does</b> (072.3): capturing the view here is what made the engine's idea of
     * the drawn scene a hand-written copy, and {@link #screenView()} derives it from the session on screen
     * instead. The flag stays because the moment a scene is built is not something the tree can be asked
     * about afterwards — {@link #tick(UI)} turns it into {@code SessionEnteredWorld} once the HUD is up.
     *
     * <p><b>It is handed the scene's own {@link Glob}</b> (073.1), because that is what names the session
     * this world came up for. {@code mv.ui} cannot: the seam is the <i>end of the constructor</i>, and a
     * widget gets its {@code ui} when it is added to a tree, which has not happened yet. Reading
     * {@link #screen()} here would be worse than useless — a session reaching the world while another is on
     * screen would raise the flag on the session the player is looking at.
     */
    public static void attach(MapView mv, Glob glob) {
        if(mv == null)
            return;
        SessionState st = state(io.brodgar.session.Sessions.uifor(glob));
        if(st != null)
            st.enterWorldPending = true;   // SessionEnteredWorld is fired on the next tick (UI thread)
    }

    /**
     * Call site — both mutation points of MapView's terrain cut map (044.9). A cut entering or leaving the
     * scene is the one event that says the ground under a free {@code hafen.virtual()} entity has come or gone;
     * nothing else would, because a client-only gob is in no {@code OCache}. Raises a flag and returns — the
     * work happens on the addon tick ({@code VirtualApi.drainGround}), which is where every other tap in this layer
     * puts it (D-106).
     */
    public static void groundChanged() {
        VirtualApi.groundChanged();
    }

    /**
     * Call site — {@code Sessions.Member.setbase}, where that session's <b>base</b> is replaced (109.4). The
     * base is the session coordinate space: a free {@code hafen.virtual()} entity holds a durable place and
     * derives its coordinate through it off the ground the character is streaming, so when the base moves —
     * or is proved, having moved — every one of those coordinates has moved. The terrain's cuts (above) say
     * the ground came and went; this says the numbers naming it changed, and the two are not the same frame.
     *
     * <p><b>Already an edge when it gets here</b>: the base is derived every frame and replaced only when it
     * differs, so this is called a handful of times an hour and needs no memo of its own. Everything past
     * the flag happens on the addon tick (D-106).
     */
    public static void sessionRebased(haven.UI ui) {
        VirtualApi.sessionRebased(ui);
    }

    // ------------------------------------------------------------- the overlay build counters (119.1)
    //
    // A CUT'S OVERLAY MESH IS BUILT IN EXACTLY ONE PLACE -- MCache.Grid.getolcut, which lays every masked tile
    // of that cut a second time through MapMesh.makeol, and the edges of the same mask once more through
    // makeolol. Both passes are counted here, so that what registering one more overlay costs is a number an
    // addon reads back rather than a stopwatch: hafen.client():profiling():render()'s overlayMeshes and
    // overlayOutlines.
    //
    // Incremented on the thread that builds cuts and read from Lua on the UI thread, hence the atomics.
    // Nothing gates them on profiling being ARMED: this is work the client does whether or not anyone is
    // watching, exactly like gobsHeld beside them in the same table (D-051).

    /** <b>Cut overlay meshes built</b>, cumulative since the client started -- one per pass through
     *  {@code MapMesh.makeol}. A pass whose mask reaches none of that cut's tiles answers {@code null} and
     *  counts too: the pass over the cut is what it costs, and an empty result is not a saving. */
    private static final AtomicLong overlayMeshes = new AtomicLong(0L);

    /** <b>Cut overlay OUTLINE meshes built</b>, cumulative since the client started -- the same count for
     *  {@code MapMesh.makeolol}, the second tile-laying pass {@code getolcut} makes over the same cut. */
    private static final AtomicLong overlayOutlines = new AtomicLong(0L);

    /** Call site -- {@code MCache.Grid.getolcut}, once {@code makeol} has answered. @see #overlayMeshes */
    public static void overlayMeshBuilt() {
        overlayMeshes.incrementAndGet();
    }

    /** Call site -- {@code MCache.Grid.getolcut}, once {@code makeolol} has answered. @see #overlayOutlines */
    public static void overlayOutlineBuilt() {
        overlayOutlines.incrementAndGet();
    }

    /** @see #overlayMeshes */
    public static long overlayMeshes() {
        return overlayMeshes.get();
    }

    /** @see #overlayOutlines */
    public static long overlayOutlines() {
        return overlayOutlines.get();
    }

    /**
     * <b>The per-client half of the old {@code init}</b> (074.2) — load the addons and fire {@code Load},
     * once, for the whole client. It runs at boot (the first turn of {@link #layerTick}) and on a
     * {@code :reload}, and <b>nowhere else</b>: an addon stopped belonging to a login the moment it outlived a
     * character switch, so there is no third moment left that could want it.
     *
     * <p><b>On the UI thread, from the layer's own pump</b>, and not from the {@code UILoop} constructor a few
     * lines above it. A file body is Lua (P5), and the constructor runs on the client's main thread; the layer
     * is built there and loaded on the first frame it is ticked, which is the first instant this client has a
     * thread Lua may run on.
     *
     * <p><b>A change of screen never reaches here.</b> An addon is the client's and not a session's, so moving
     * the anchor tears nothing down and loads nothing again: {@code Sessions.anchor} fires
     * {@code SessionSelected} and that is the whole of what a switch costs this layer. Every Lua value an addon
     * holds is the value it held, which is what lets a handler's own tables outlive a character.
     */
    static synchronized void boot() {
        Prof.init();  // 019.1: restore the persisted profiling switch (once per JVM)
        Prof.addonCost(AddonManager::luaNanosThisFrame);   // 019.2: the addons roll-up source
        Prof.addonReset(AddonManager::resetProfiling);     // 019.4: p:reset()/arming clears the per-addon rows too
        AddonRegistry.loadAll();                           // discover + run addons, fire Load for each
    }

    /**
     * <b>The per-session half of the old {@code init}</b> (074.2) — attach this session's tick pump and its
     * gob event source, and mint the {@link SessionState} the rest of the layer reads it through (which is
     * what builds its nine HUD adapters, 073.3). Called from {@code Sessions.Member} the moment that session's
     * {@code UI} exists, on that session's own thread: <b>a session's arrival is what drives it</b>, never the
     * screen moving to it, so a session that is never looked at is served exactly like one that is.
     *
     * <p>Deliberately <b>not</b> {@code synchronized}: it takes the new {@code UI}'s own monitor (a widget
     * added to its root) and the class monitor is what {@code AddonRegistry.reload} holds while the UI thread
     * is inside {@code synchronized(ui)}. Taking both here, in the other order, is the one deadlock this
     * layer can build — and nothing here needs the class monitor, because everything it touches is this
     * session's alone.
     */
    public static void sessionArrived(UI u) {
        SessionState st = state(u);
        if(st == null)
            return;                   // no session behind it, or already destroyed: nothing to pump
        attachRoot(st, u);            // invisible per-frame tick widget (drives this session's engine step)
        registerOcache(st, u);        // GobAdded/GobRemoved source (marshalled to the UI thread)
    }

    /** Attach the invisible tick widget to {@code ui.root} (guarded — root must exist). */
    private static void attachRoot(SessionState st, UI u) {
        if(u.root == null) {
            log("no ui.root; tick pump not attached");
            return;
        }
        try {
            AddonRoot r = new AddonRoot();
            u.root.add(r);            // add() synchronizes on ui; the widget then ticks each frame
            st.addonRoot = r;
        } catch(RuntimeException e) {
            log("failed to attach tick widget: " + e);
        }
    }

    /**
     * Register a weak-safe {@link OCache} callback that enqueues gob spawn/despawn for the tick. The callback
     * <b>closes over its own session's state</b> (073.1) rather than looking one up when it fires: it runs on
     * the network and Loader threads of <i>this</i> session, which are not the drawn session's, so
     * {@link #screen()} at that moment would file another session's gobs under whichever one holds the screen.
     */
    private static void registerOcache(final SessionState st, UI u) {
        try {
            OCache oc = u.sess.glob.oc;
            OCache.ChangeCallback cb = new OCache.ChangeCallback() {
                // ...and only once this session's own step is up: the layer drains the queue (079.4), but a
                // state that never finished init is one whose gobs nothing else in this layer knows about,
                // and its queue would grow for the life of the session.
                public void added(Gob g)   {
                    if(st.addonRoot == null)
                        return;
                    // 114.1: hold this copy out of the render tree until the drain below has announced it.
                    // BEFORE the enqueue -- a drain landing between the two would clear a flag not yet set.
                    if(gobAddedSubs)
                        armHold(g);
                    st.gobEvents.add(new GobEvent(true, g));
                }
                public void removed(Gob g) { if(st.addonRoot != null) st.gobEvents.add(new GobEvent(false, g)); }
            };
            oc.callback(cb);
            st.ocCb = cb;             // hold a strong ref (OCache stores callbacks in a WeakList)
        } catch(RuntimeException e) {
            log("failed to register gob callback: " + e);
        }
    }

    // ------------------------------------------------------------- the tick pump

    /**
     * <b>One step of the addon layer</b> (074.1, and since 074.2 the engine's own step), called by
     * {@code UILoop.Frame.tick} each frame. The layer is where an addon's own windows live and where the
     * addons themselves now live, so this is both: what a <i>tree</i> owes per frame — the surfaces built into
     * it since the last one, the popup a click buried, the removals and resizes its own widgets recorded off
     * the tick — and what the <i>client</i> owes its addons, which is everything that must happen exactly once
     * however many sessions are up: the clock, {@code Update}, the timers, the CPU budget and a queued
     * {@code :reload}.
     *
     * <p><b>That second half moved here from {@link #tick(UI)}</b> and the move is the feature: a
     * session's step runs once per session, and an addon is the client's now. Left where it was, two sessions
     * would fire two {@code Update}s a frame, run every timer twice and charge each addon's Lua budget twice.
     *
     * <p>It is also the <b>only</b> pump that is always running — the layer is built with the client and never
     * replaced — which is why the boot below is hung on it: a file body is Lua, Lua runs on this thread (P5),
     * and this is the first turn of it the client has.
     *
     * <p><b>NO TREE MONITOR IS HELD HERE</b> (112.1), and that is what the step is <i>for</i>. It is NOT a
     * widget on the layer's root, which would mean every handler below it began with {@code synchronized(layer)}
     * already taken — while most of what an addon does per frame is write a <i>session's</i> widget, which
     * takes that tree's monitor under the layer's. A Loader thread placing a widget holds the two in the other
     * order, and the client freezes. So the call site sits outside the tree: {@code Frame.tick} calls this
     * between its input dispatch and its two blocks, and everything reached from here — the drains, the bus
     * events, the timers and {@code widget:on("Update", fn)} — may reach any tree because it holds none.
     *
     * <p><b>It takes its own delta</b> for the same reason: no {@code TickEvent} carries one to it any more.
     * {@link Utils#rtime()} is the clock {@link UI#tick()} reads too, so a frame measures the same here as it
     * did in the tree, and the first turn of the client measures nothing.
     */
    public static void layerTick(UI u) {
        // 112.1: `stepping` is what a shutdown waits a step out by, now that no tree monitor is held here —
        // see the field. 112.3: and `stepThread` is what hafen.client():stepping() compares against.
        synchronized(stepping) {
            Thread prev = stepThread;
            stepThread = Thread.currentThread();
            try {
                layerStep(u);
            } finally {
                stepThread = prev;
            }
        }
    }

    /**
     * <b>The thread running the layer's step, while one is</b> (112.3) — {@code null} between steps, and what
     * {@code hafen.client():stepping()} compares against. It is the thread and not a boolean because the
     * question is <i>where is this code running</i>: a flag would read {@code true} on a Loader thread that is
     * placing a widget while the step runs beside it, which is the exact confusion the verb exists to end.
     */
    private static volatile Thread stepThread = null;

    /** Whether the caller is running on the layer's step — {@code hafen.client():stepping()}. @see #stepThread */
    public static boolean onStep() {
        return Thread.currentThread() == stepThread;
    }

    /** {@link #layerTick}'s body, inside the barrier. */
    private static void layerStep(UI u) {
        try {
            double now = Utils.rtime();
            double dt = (lastStep < 0) ? 0.0 : (now - lastStep);
            lastStep = now;
            if(quiet())
                return;      // 079.2: the client is quitting; the layer stops stepping before anything is read
            // 114.1: the deadline under the render hold, before any of the returns below — a step that has
            // nothing else to do must still be able to let the world be drawn. Free while nothing is held.
            sweepHolds();
            SessionState st = state(u);
            if(st == null)
                return;
            sweepStates();   // the backstop under uiDestroyed, on the one thread that is always running
            clock += dt;

            // 074.2: the client's addons, loaded once, on the first frame the layer is ticked.
            if(!booted) {
                booted = true;
                boot();
                return;      // the loaded addons begin their own tick cleanly next frame
            }

            // 0. A queued :reload / Reload UI — rebuild the addon layer on the UI thread (spec 1f-2, D-005).
            //    Queued against the LAYER since 074.2, because that is what a reload rebuilds: the addons are
            //    the client's, so a reload typed on the login screen is as real as one typed in the world.
            //    Done first + return so the reloaded addons begin their own tick cleanly next frame (this
            //    frame's Update/timers belonged to the addons we just tore down).
            if(st.reloadPending) {
                st.reloadPending = false;
                for(SessionState ss : allStates())
                    ss.overlayEvents.clear();   // 038.3: the addons that queued these are being torn down
                sessionEvents.clear();          // 074.3: ...and so are the ones these were queued for
                overlaySubs = false;            //   (the reloaded ones re-subscribe inside reload())
                sdtSubs = false;                // 113.3: same reset, same reason
                gobAddedSubs = false;           // 114.1: same reset...
                releaseAllHolds();              //   ...and nothing is left that could announce what is held
                AddonRegistry.reload();
                return;
            }

            // 0b. The arming tick (039.6): every surface a builder made into THIS tree since the last tick goes
            //     in now. Done FIRST, so a window built in an input handler — which the engine dispatches before
            //     ui.tick() — is on screen in the very frame it was asked for, fully configured, rather than a
            //     frame later. Not before the reload above: a widget whose addon is being torn down is never
            //     placed at all.
            UiApi.armPending(st);
            CDropdown.drainRaises(st);    // 040.10: ...and a popup its own window's click-to-raise buried
            // 112.3: the two seams that used to run Lua on the thread that placed a widget or built an item's
            // info, each of them holding that tree's monitor. EVERY tree's queue, because this is the one pump
            // in the frame that holds none, and ENTERED before REMOVED so a widget that came and went inside
            // one frame never reports its Added after its Removed.
            drainEnteredWidgets();        // 112.3: s:ui():on(sel, "Added", fn)
            drainItemInfos();             // 112.3: item:on("Changed", fn)
            drainWidgetDeaths(st);        // 042.1: a widget of ours that left the tree, and what watched it
                                          // 128.1: ...and then the subscriptions of everything that was destroyed
            drainResizedWidgets(st);      // 042.10: ...and one that changed shape, for whatever is anchored

            // Soft CPU-budget accounting (D-018 layer 2): zero every addon's per-tick Lua time before any
            // handler runs this tick; callLua accumulates into it, enforceSoftBudget() evaluates it at the
            // end. (Skipped on a reload tick, which returns above — its Load is a one-off.)
            // 019.4: this instant is also where the PREVIOUS frame closes — tickLuaNanos accrues through the
            // tick and the draw callbacks that follow it, so right here it holds exactly one whole frame.
            // profRoll() moves it into the addon's "last completed frame" figures before it is cleared, which
            // is why p:addons() never shows a half-accumulated frame and its total matches p:frame().addons.
            // 019.7: `armed` is the MASTER switch, so the ms figures keep rolling across a control frame;
            // `probed` describes the frame being CLOSED (this tick opens the next one), so the category and
            // scope split — which a control frame does not measure — holds its last measured value instead
            // of rolling a row of zeroes in one frame out of every Overhead.PERIOD.
            // 074.2: on the LAYER's tick, so an addon's frame closes once however many sessions are up.
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

            // Resolve (M2, 042.1) retries queued by a Loading resolving off-thread → run on the UI thread.
            // One frame's worth (a retry that re-registers must not spin this tick forever). Process-wide
            // with the addons that own them (074.2): the seam is handed a bare Runnable and knows no session.
            drainResolveQueue();

            // 079.4: every session's gob queue, settled and reported ONCE for the client — GobAdded into the
            // first session that sees an object, GobRemoved out of the last, and nothing in between. Before
            // drainGround, because a spawn or a despawn is exactly what moves the ground under a thing
            // standing on it, and this is the drain that raises that flag.
            drainGobEvents();

            // The ground under a thing standing in the world (044.9), and since 075.3 the scene it stands in
            // as well: the terrain's cut map changed — the player crossed a cut boundary, or a grid streamed
            // in or out — or the screen moved to another session, which changes where every entity is drawn
            // and whether the character now looking can see its place at all. On the LAYER's tick because
            // there is ONE set of entities for the client and one scene being drawn: run per session it would
            // walk the same entities once per login and re-derive their coordinates against a map that is not
            // the one they are drawn in. Free when nothing moved: two reference reads.
            VirtualApi.drainGround();

            // 118.2: and the patches that follow a gob, which is the one thing in this layer that has to be
            // polled. A patch has no gob of its own, so it has no FollowMoving for the placement pass to
            // re-evaluate, and the events above fire when an object comes into view rather than while it
            // walks. Free when nothing follows anything: two reference reads.
            VirtualApi.followPatches();

            // 079.1: the saved variables of any session that ended since the last frame, written back into
            // that character's own folder — the seam that saw it die runs on the dying session's thread and
            // may only file it, so the write is here, where Lua is read (P5).
            StoreApi.drainEnded();

            // 074.4: whose character the remembered placements belong to, which is the session on SCREEN —
            // so this runs before the two fires below and an addon told the screen moved reads the character
            // it moved to. One string compare on a frame that changed nothing.
            StoreApi.rescope();

            // 074.3: sessions that came, were picked or went since the last frame. Before Update, so a handler
            // that keeps its own map of who is up has it right for the frame it is about to be told about.
            drainSessionEvents();

            // Per-frame update, and the due timers behind it. Once per frame for the client — an addon has one
            // Update however many characters it is watching.
            LuaValue dtv = LuaValue.valueOf(dt);
            fire("Update", dtv);
            // ...and a SURFACE's own Update, fired from the step rather than from the widget's tick (112.1) —
            // after the bus's, which is the order a surface handler sees.
            fireSurfaceUpdates(dtv);
            runTimers();

            // Custom UI overlays (2b): queue the HUD-overlay afterdraw for THIS frame if any addon has one.
            // UI.drawafter is one-shot, tick precedes draw, so it paints above the HUD this frame. The SCREEN's
            // after-draw: a HUD overlay paints over what is drawn.
            UI hu = screen();
            if((hu != null) && UiApi.anyHudOverlays())
                hu.drawafter(UiApi.hudAfterDraw);

            // Soft per-tick CPU budget (D-018 layer 2): auto-disable an addon that has been over budget for too
            // many consecutive ticks — a sustained runaway the hard per-call cap doesn't catch.
            enforceSoftBudget();
        } catch(RuntimeException e) {
            log("layer tick error: " + e);
        }
    }

    /** Whether {@link #boot} has run. One client, one boot — see {@link #layerTick}. */
    private static boolean booted = false;

    /** {@link Utils#rtime()} at the previous step, or {@code -1} before the first — {@link #layerTick}'s own
     *  delta, since nothing hands it one any more (112.1). */
    private static double lastStep = -1;

    /**
     * <b>The step's own barrier</b> (112.1) — held for the length of one {@link #layerTick} and taken for an
     * instant by {@link #awaitIdle}. The step used to run inside {@code synchronized(layer)}, so the layer's
     * TREE monitor was what a shutdown waited a step out by; it holds no tree monitor at all now, and this is
     * what says "a step is in flight" in its place.
     *
     * <p><b>It guards no state, and nothing else ever takes it</b>, so it adds no direction to the lock graph:
     * inside the step it sits above every tree monitor the step's own Lua takes, and {@link #awaitIdle} gives
     * it up before it takes the first tree's. There is no pair of threads that can hold one of these and want
     * the other.
     */
    private static final Object stepping = new Object();

    /**
     * <b>One session's step</b> — every session's, every frame, and not only the one on screen. Everything is
     * error-isolated so an addon bug never breaks the frame or another addon.
     *
     * <p><b>NO TREE MONITOR IS HELD HERE</b> (112.4), which is what the drains below are <i>for</i>. It used to
     * be a widget on that session's root ({@link AddonRoot}), so it ran inside {@link UI#tick()}'s
     * {@code TickEvent} broadcast — and both drivers hold the tree while they do: {@code UILoop.Frame.tick}'s
     * {@code synchronized(ui)} for the session on screen, {@code Sessions.tick}'s {@code synchronized(u)} for
     * every background member. So an {@code s:ui():on(sel, "Removed", fn)} handler began with that tree's
     * monitor already taken, and building a window or writing another character's widget from it took a second
     * one under the first — the nesting {@code docs/client/multi-session.md}'s one lock direction forbids and
     * 112.2 now refuses at the line. Both drivers call this <b>after</b> their own block closes, which also
     * means a drain reads geometry the frame has already settled.
     *
     * <p><b>It is told which session it is stepping</b> (073.1): the driver names the tree, and what it drains
     * is that session's own queues — its HUD adapters, its widgets, its world, its store. Its <b>gobs</b> are
     * not among them since 079.4: an object is the client's and its queue is drained with every other
     * session's, on the layer's tick.
     *
     * <p><b>What is NOT here is what an addon has one of</b> (074.2): {@code Update}, the timers, the Lua
     * budget and the engine clock run on the layer's own pump ({@link #layerTick}), once for the client, so
     * two sessions are two sets of drains and still one addon being stepped.
     *
     * <p><b>It raises {@link #stepThread} as the layer's step does</b>, so {@code hafen.client():stepping()}
     * is one answer for the client rather than one per tree: every seam that reaches Lua with no tree monitor
     * held answers {@code true}, whichever pump it hangs off. The {@link #stepping} barrier comes with it —
     * with no tree monitor held here, taking the session's is no longer how {@link #awaitIdle} waits a step
     * out.
     */
    public static void tick(UI u) {
        synchronized(stepping) {
            Thread prev = stepThread;
            stepThread = Thread.currentThread();
            try {
                sessionStep(u);
            } finally {
                stepThread = prev;
            }
        }
    }

    /**
     * {@link #tick(UI)}'s body, inside the barrier.
     *
     * <p><b>No delta</b> (112.4). Nothing here has ever read one — the engine clock is the layer's
     * ({@link #clock}, advanced once a frame however many sessions are up) and the autosave and the
     * {@code SessionEnteredWorld} gate both measure against it — so the {@code TickEvent}'s {@code dt} was
     * passed in and dropped. With the {@code TickEvent} gone there is nothing to pass, and computing a
     * per-tree one off {@link Utils#rtime()} would be a second clock with no reader.
     */
    private static void sessionStep(UI u) {
        try {
            if(quiet())
                return;      // 079.2: ...and so does every session's, for the same reason
            SessionState st = state(u);
            if(st == null)
                return;
            // 0. The arming tick (039.6): every surface a builder made into THIS session's tree since the last
            //    tick goes in now. Done FIRST, so a window built in an input handler — which the engine
            //    dispatches before ui.tick() — is on screen in the very frame it was asked for, fully
            //    configured, rather than a frame later.
            UiApi.armPending(st);
            CDropdown.drainRaises(st);    // 040.10: re-raise a popup the enclosing window's own click-to-raise
                                           //   buried this same frame (see CDropdown's class doc)

            // 1. The gob queue is NOT drained here (079.4). A gob is one object for the client, so the four
            //    world events are settled once for the client on the layer's own tick (drainGobEvents) — a
            //    per-session drain can only report per session, which is the defect.

            // 1'. The two gob-overlay events (038.3), captured on the loader threads (the game's own) and inside
            //     gob:overlay (an addon's own). Drained on the session that captured them and AFTER the layer's
            //     gob drain — the layer ticks first in a frame — so an overlay the server hangs on a gob that
            //     just spawned is reported after the GobAdded that introduced it, and a removal caused by the
            //     gob leaving has already been fired synchronously by LuaGobOverlay.gobGone in that drain,
            //     before the gob's own GobRemoved, so an overlay is never reported dying after the thing it
            //     was on.
            drainOverlayEvents(st);

            // 1''. A gob's state bytes changed (113.3), captured on the loader threads inside
            //      ResDrawable.$cres.apply's own gob-monitor seam. Same reasoning as 1': drained on the
            //      session that captured it, after the layer's gob drain, so a growing crop's first state
            //      is reported after the GobAdded that introduced it.
            drainSdtEvents(st);

            // 1a. HTTP results (N2a): a pool worker finished a request → deliver its res table to the addon's
            //     callback on the UI thread (armed + isolated, like every other event). A cancelled/torn-down
            //     request (dead) is discarded — its callback never fires (D-037 §3.3). Draining a request frees
            //     an in-flight slot, so re-run the per-addon scheduler to launch any queued request.
            HttpApi.drainHttp(st);

            // 1b. Widget-tree adapters flagged dirty by an inbound uimsg → re-read + fire the semantic
            //     event, now on the UI thread. (Marked off-thread in onUimsg; drained here.) Refresh before
            //     the removal/resolve/belt/resize drains below so a brand-new buff surfaces as a single
            //     BuffAdded (with its content already applied), not BuffChanged-then-BuffAdded.
            CharApi.refreshTreeAdapters(st);

            // 1b'. Widget removals (M1, 042.1) captured off-thread by the Widget.remove() tap → dispatched on
            //      the UI thread, one frame's worth (D-106). After refresh, so a removal never races a content
            //      update the same frame. 128.1: and the widgets destroyed this frame are retired right after
            //      they are announced — drainWidgetDeaths is that pair, and the order is the whole of it.
            drainWidgetDeaths(st);

            // 1b'a. WidgetSubs' deep ItemAdded/ItemRemoved diff (064.3), once per tick now that this tick's
            //       placements and removals have landed: a container and everything it gains or loses arrives
            //       as SEPARATE messages, one per widget id, not one for the whole move — diffing inline at
            //       each would report every contained item on its own instead of once for the outermost. See
            //       UiApi.flushItemWatchers / WidgetSubs.markDirty.
            UiApi.flushItemWatchers(st);

            // 1b''. Deferred belt-slot writes (042.6, D-178) captured off-thread by the two GameUI
            //        setbelt/setbelt2 loader tasks → dispatched on the UI thread, one frame's worth
            //        (D-106). The uimsg tap already re-diffed the whole bar against the OLD value for
            //        these two paths (the write lands after the message is dispatched); this re-checks
            //        just the one slot now that the write is actually there.
            drainBeltSet(st);

            // 1b''''. Widget resizes (M4, 042.10) captured off-thread by the Widget.resize() tap → offered on
            //         the UI thread, one frame's worth (D-106), to Layout.dispatchResized — an anchor target
            //         resizing, a window packing itself, or the screen changing all funnel through this one
            //         seam, and it is free (derived.isEmpty()) for a client with nothing anchored.
            drainResizedWidgets(st);

            // 1b'''''. Caption rewrites (061.6) recorded off-thread by the inbound-uimsg tap → re-read and
            //          re-applied on the UI thread, one frame's worth (D-106). BEFORE the caption-driven
            //          drains below: each of those re-offers a widget to Layout.apply, which would put the
            //          level back on before this one has read what the server actually wrote.
            drainTextRewrites(st);

            // 1b''''''. The chat's four seams (110.2, 110.3), captured off-thread by ChatUI.add / cdestroy /
            //           select and Channel.append -> ChannelAdded, ChannelRemoved, ChannelSelected and
            //           MessageAdded on the UI thread, one frame's worth (D-106). ONE queue, so the order the
            //           seams recorded holds. This session's own, because a chat is one login's HUD.
            drainChatEvents(st);

            // 1c. Replacements (032.1, event-driven since 042.8): the server destroying a window an addon
            //     replaced with widget:replace(view) is a removal, so it is offered at the removal seam
            //     (drainRemovedWidgets, via UiApi.dispatchReplacedRemoved) — nothing left for the tick to drive
            //     here.

            // 1c'. WidgetSubs tree keys (041.4, event-driven since 042.7): widget:on("ItemAdded"/"ItemRemoved", fn)
            //      and widget:on("Removed", fn) no longer poll — they are offered every placement (above, via
            //      onWidgetPlaced -> UiApi.dispatchWidgetSubsPlaced) and every removal (drainRemovedWidgets, via
            //      UiApi.dispatchWidgetSubsRemoved), so there is nothing left for the tick to drive here.

            // 1c''. Selector subscriptions (030.2, event-driven since 042.9): `Removed` is fully driven by the
            //       removal seam above (drainRemovedWidgets, via UiApi.dispatchSelectorRemoved) — nothing to do
            //       here for it. The [title=]/[res=] refiner's re-check is woken by the caption seam
            //       (Window.chcap -> onCaptionChanged -> UiApi.markCaptionChanged), but that seam runs OFF the UI
            //       thread (gotcha 1) and so only records the window; the actual re-check (widget reads + any Lua)
            //       happens here, on the UI thread — an idle client, or one with no subscription at all, pays one
            //       isEmpty(). Since 049.3 the captioned window's own SUBTREE is re-offered as well, because a
            //       chain's [title=] sits on an ancestor step and what starts matching is a widget below it.
            UiApi.drainSelectorCaptionCheck(st);

            // 1c''''. The stylesheet's per-widget resolution cache (049.3): the same caption seam, one consumer
            //         along. A chain tree key (["window[title=Cupboard] label"]) makes a widget's style depend on
            //         an ANCESTOR's caption, so a settled answer below a renamed window is an answer to a question
            //         that changed. Drops those cache entries; the next draw re-folds them.
            Sheet.drainCaptionInvalidation(st);

            // 1c'''. Layout (036.2, event-driven since 042.10): the late [title=]/[res=] refiner's bounded
            //        re-check is woken by the same caption uimsg as the line above (CharApi.dispatchUimsg ->
            //        Layout.markCaptionChanged, off the UI thread, flag-only per gotcha 1); the actual re-check
            //        runs here, on the UI thread. Pruning a departed widget's layout record and re-deriving an
            //        anchor's followers moved onto the removal seam (drainRemovedWidgets, via
            //        Layout.dispatchRemoved) and the geometry seam (drainResizedWidgets, via
            //        Layout.dispatchResized) above — redrive() and its per-tick fold over every anchored
            //        widget are DELETED (D-181, superseding D-091): an anchored widget re-derives on its
            //        inputs' own events now, never on a fold.
            Layout.drainPendingCaption(st);

            // 1d. Map markers (A1, 042.11, event-driven): fire MarkerChanged when the on-disk map DB's
            //     markerseq changes (a marker add/remove is not a uimsg — the server pushes SMarkers via
            //     markobj, the player/addon adds PMarkers, and segment merges re-key them; all bump markerseq).
            //     The bump is caught at its source and marshalled onto the tick to avoid deadlock with the
            //     map DB's RW lock. Global event.
            drainMarkerChanges(st);

            // 2. "Entered the world" — fire SessionEnteredWorld once the HUD (GameUI) is not just built but
            //    ATTACHED to ui.root. The map view sets enterWorldPending from its ctor (loader thread),
            //    and gui() finds GameUI via the map view a beat BEFORE GameUI is added to the RootWidget
            //    (confirmed via the widget-place trace: the old "gui()!=null" signal fired one line before
            //    "ADD GameUI -> RootWidget"). Firing then would add an addon window to ui.root as a sibling
            //    placed *before* GameUI, so the full-screen HUD draws on top of it (invisible until a
            //    :reload re-adds it after GameUI). Gating on gui().parent != null (GameUI is in the tree)
            //    fires the tick after the HUD mounts, so ui.root windows land on top. enterWorldPending is
            //    reset per session in init(), so it can't stick. (GameUI-backed reads still stream in a beat
            //    later — read them on a timer, not synchronously here.)
            //    059.5: ...and the ACTION MENU with it. GameUI.menu is not built by GameUI — it is a child the
            //    server places ("menu"), so it arrives some ticks AFTER the HUD is in the tree. Firing between
            //    the two hands every addon a SessionEnteredWorld in which s:menugrid():add(id) refuses
            //    with "the action menu is not up yet", which is the one call an addon's own entries — and the
            //    action-bar slots held for them — come back through at login. Waiting for it is what makes
            //    "add your entries from SessionEnteredWorld or later" true rather than a race an addon has to
            //    code around. BOUNDED, because nothing here can prove the server always sends one: after
            //    MENU_WAIT seconds it fires anyway, with a log line saying the menu never came, so a session
            //    that has no action menu at all still gets everything else.
            if(st.enterWorldPending) {
                // 074.2: THIS session's HUD — every session steps, not only the one on screen, so "the" HUD
                //   would be the wrong character's.
                // 112.4: the three reads are the tree's — gui() walks child/next, and the two fields are
                //   written by the Loader thread that placed the HUD and its menu — so they are taken under
                //   that tree's own monitor and everything below fires outside it. One tree, and the step
                //   arrives holding none: this is exactly the one monitor the step may take.
                GameUI hud;
                boolean up, menu;
                synchronized(LuaWidget.monitorOf(st.ui)) {
                    hud = gui(st.ui);
                    up = (hud != null) && (hud.parent != null);
                    menu = up && (hud.menu != null);
                }
                if(up) {
                    if(st.hudUpSince < 0)
                        st.hudUpSince = clock;
                    boolean late = (clock - st.hudUpSince) >= MENU_WAIT;
                    if(menu || late) {
                        if(late && !menu)
                            log("SessionEnteredWorld: no action menu after " + MENU_WAIT
                                + "s — firing without it");
                        st.enterWorldPending = false;
                        StoreApi.enterWorld(st, hud);   // 073.5/074.4: THIS session learns its own
                                                   //   <genus>_<char>, from the very HUD this gate just read —
                                                   //   and its per-character saved variables are loaded BEFORE
                                                   //   the event fires, on screen or not: the loadChar loop is
                                                   //   per session, and only the rescope() at its tail is the
                                                   //   screen's
                        BeltHold.restore(st);      // 059.5: ...and this character's action-bar placements, so the
                                                   //   first :add an addon makes puts its button straight back
                        // 074.3: fired DIRECTLY and not through the session queue — this already runs on the UI
                        //   thread, and the ordering that matters is the one above it: the per-character saved
                        //   variables and the held slots are in place before any handler reads them (spec 1e).
                        //   The payload names THIS session, never the one on screen.
                        String who = io.brodgar.session.Sessions.nameof(st.ui);
                        if(who != null)
                            fireSession("SessionEnteredWorld", who);
                        else       // the member went between the world coming up and this tick. The flag
                            log("SessionEnteredWorld: the session ended before it could be named");
                    }
                }
            }

            // 3. Throttled auto-save of this session's saved variables (mirrors GameUI's window-position
            //    saves). Covers an unclean exit. flush() skips unchanged files, so this is cheap when nothing
            //    changed. On the UI thread → no races reading the Lua tables.
            StoreApi.autosave(st, clock);
            BeltHold.flush(st);     // 059.5: and the action-bar placements, if one changed since the last tick
                                    //   (a no-op otherwise — the message thread only ever marks them dirty)
        } catch(RuntimeException e) {
            log("session tick error: " + e);
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
     * {@link Sandbox#SOFT_STRIKE_LIMIT} consecutive over-budget ticks it is auto-disabled until the next load
     * (torn down + a warning surfaced in the AddOns panel). Runs at end of tick, so mutating {@code addons}
     * via {@link #autoDisable} is safe — which is why {@link #drainQuarantines} is spent here too (126.1),
     * above the config gate, since a contained {@link Error} is not a budget and does not turn off with one.
     * The {@code :lua} REPL owner is exempt (it is not in {@code addons} — the sandbox constrains shared
     * addon code, not the operator's console).
     */
    private static void enforceSoftBudget() {
        drainQuarantines();   // 126.1: FIRST, and above the config gate below — containment is not a budget
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
     * <b>Spend the quarantines {@link #callLua} filed</b> (126.1), at the end-of-tick safe point the CPU
     * watchdog already auto-disables from — outside the step's iteration over {@code addons}, on the UI
     * thread, whichever thread was inside Lua when the failure happened. From there it is the CPU watchdog's
     * own path exactly: {@link #autoDisable} announces it, tears the addon down and leaves the reason on its
     * AddOns row until the next load.
     *
     * <p>An addon already gone — torn down by a reload, or by the quarantine its own {@code Disable} handler
     * then raised — is dropped, so a second failure on the way out cannot tear one down twice. So is the
     * {@code :lua} REPL owner, which is not in {@code addons} and is not the sandbox's business: taking the
     * console away from the operator because a typed expression overflowed the stack is the opposite of the
     * point, so it is said and dropped.
     */
    private static void drainQuarantines() {
        for(Quarantine q = quarantines.poll(); q != null; q = quarantines.poll()) {
            if(q.addon == consoleOwner) {
                log("the :lua console raised a " + q.reason + " - contained; the console stays");
                continue;
            }
            if(addons.contains(q.addon))
                autoDisable(q.addon, q.reason);
        }
    }

    /**
     * Auto-disable an addon (D-018): record a panel warning, run its teardown ({@code Disable} → flush saved
     * vars → drop owned resources) and drop it from the live set so it stops ticking. This does NOT touch the
     * persisted enabled set — a {@code :reload} gives the addon a fresh start (the user can persist-disable it
     * via the panel checkbox). Called from {@link #enforceSoftBudget} at end of tick, so mutating
     * {@code addons} here is safe — and since 126.1 from {@link #drainQuarantines} beside it, for an addon
     * whose Lua raised something no {@code pcall} could catch. One tear-down, one announcement and one panel
     * string for both, which is why the {@code reason} names its own cause rather than this line doing it.
     */
    private static void autoDisable(Addon a, String reason) {
        String id = (a.manifest != null) ? a.manifest.id : "addon";
        log(a, "AUTO-DISABLED (" + reason + ") - see Options -> AddOns");
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
                if(t.repeats) {
                    t.due += t.secs;                         // repeating: reschedule (fires once/tick)
                } else {
                    t.fired = true;                          // one-shot: it ran, which tostring says
                    t.alive = false;
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
     * interested adapter(s) dirty; {@link #tick(UI)} drains them and fires the semantic event on the
     * UI thread (principle P5).
     */
    public static void onUimsg(Widget w, String msg) {
        CharApi.dispatchUimsg(w, msg);
        // 061.6: the server may have just painted over a text level (widget:text(s) / widget:title(s)). Record
        // the widget and nothing else — this thread holds no monitor, and the level goes back on at the tick
        // ({@link #drainTextRewrites}). One volatile read for a client nobody is holding anything on; a client
        // being laid out but not captioned enqueues a widget the drain then finds nothing standing on.
        if(LuaWidget.anyMoved && LuaWidget.rewritesText(w, msg)) {
            SessionState st = queueState(w.ui);   // 073.1: the widget's OWN session, never the drawn one
            if(st != null)
                st.textRewrites.add(w);
        }
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
     * naive thread test would wrongly skip the commonest action in the game. A rare off-lock sender is passed
     * straight through, and — because we only <i>test</i> the lock, never acquire a new one — this seam adds no
     * edge of its own. The re-entrancy guard makes a handler body that itself triggers a {@code wdgmsg} pass
     * through rather than recurse ({@code resend}/{@code send} themselves bypass this via {@code rawWdgmsg}).
     * Returns {@code true} (proceed) on every fast-path exit, so an unsubscribed action is unaffected.
     *
     * <p><b>Why this one cannot be hoisted, and what that costs (112.7).</b> Every other seam in this layer that
     * ran Lua under a tree monitor was moved off it — {@link #onWidgetEntered} and {@link #onItemInfo} queue for
     * the step (112.3), {@link #onWidgetPlaced}'s adapters fire from that drain (112.5), and {@link #onMessage}
     * is hoisted clean out of {@code UiMessage.run}'s block (112.7). This one has nowhere to go: it is reached
     * <i>from</i> input dispatch, which took the monitor before it called anything, and the answer it exists to
     * give ({@code ev:preventDefault} cancelling the send) has to be given now, on this thread. So it stays in
     * family A and holds exactly one tree — the sender's own. Two things follow, both by design. A handler here
     * may read and write the sender's tree freely, and a reach into <b>another</b> tree meets
     * {@link LuaWidget#monitor}'s refusal (112.2) rather than a deadlock; the refusal names the
     * {@code Update} handler or timer that does the same work holding nothing. And the Lua it runs is not
     * serialized against the engine step, which since 112.4 holds no monitor at all: this is the one remainder
     * {@code docs/addons/api/threading.md} states.
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
     * <p><b>Threading (112.7).</b> Called from {@code UiMessage.run} on a Loader thread, <b>above</b> that
     * method's {@code synchronized(ui)} block rather than inside it: this seam holds <b>no</b> tree monitor, so
     * a handler may build a window in the layer or write a widget of any session — the one-monitor rule
     * ({@code docs/client/multi-session.md}), which this seam broke every time a handler reached across trees.
     * It still decides <i>before</i> the widget applies, and stays ordered against the apply: {@code UI}'s
     * command queue chains every {@code UiMessage} aimed at one widget id ({@code Command.dep}/{@code bars}),
     * so no other update to that widget runs between this call and the {@code dispatch} it answers. What the
     * hoist gives up is the serialization that block bought for free — two updates to <i>different</i> widgets
     * can now run their handlers side by side on two Loader threads, beside the step's own Lua, which since
     * 112.4 holds no monitor either. That is the remainder {@code docs/addons/api/threading.md} states, and it
     * is why a handler here keeps a short body. No {@code holdsLock} guard, unlike {@link #onWdgmsg}: there is
     * no monitor left to test for. The fast path (nobody subscribes to this name) returns the original args
     * immediately, so an unsubscribed message is unaffected — important, as uimsg application is hot.
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
     * Delegates to {@link VirtualApi}, which owns the world-entity registry.
     */
    public static boolean onGhostClick(Gob cg, int button, Coord2d mc) {
        return VirtualApi.onGhostClick(cg, button, mc);
    }

    /**
     * The <b>patch click seam</b> (118.3) — called from {@code haven.MapView.mousedown}, in the branch that
     * would otherwise have become a {@code Click}. A patch is a ground overlay and renders into no clickmap, so
     * the pick pass {@link #onGhostClick} answers has nothing of it to resolve; this tests the pointer against
     * the patch's own ring, projected, and so answers inside the event. {@code true} means a clickable patch
     * took the press and it is consumed — no {@code wdgmsg}, no walk; {@code false} leaves the map view's own
     * behaviour completely untouched. Delegates to {@link VirtualApi}, which owns the world-entity registry.
     */
    public static boolean onPatchClick(MapView mv, Coord pc, int button) {
        return VirtualApi.onPatchClick(mv, pc, button);
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
     * {@code pwdg.addchild(wdg, pargs)}, i.e. the first COMPLETE moment for a widget the SERVER places: it is
     * in the tree, so a {@link Selector} can be applied to it. The {@code hafen.ui.onWidgetCreate} consumer
     * went with 030.2 and {@code hafen.ui.replace}'s {@code {id, type, place, caption, parentType}} descriptor
     * (D-024) with 032.2, so the seam needs neither the parent nor the placement args any more, and the
     * {@code UI.NewWidget.run} edit that recorded the server type string for that descriptor is gone too.
     *
     * <p><b>Nothing here runs Lua any more, and that is what keeps the seam where it sits</b> (112.5). It is
     * reached inside {@code AddWidget.run}'s {@code synchronized(ui)}, on whichever Loader thread applied the
     * server's message, so a handler raised from here would run with that tree's monitor held — the nesting
     * this feature removes. Both halves that called Lua have gone to the widget-entry seam's drain, which runs
     * on the layer's step holding no tree monitor at all: the 030.2 selector subscriptions in 112.3, and the
     * 042.1 tree adapters ({@link CharApi#dispatchPlaced} — {@code MeterAdded}, {@code BuffAdded} and the study
     * and equipment fires) in 112.5. What is left is {@link UiApi#onWidgetPlaced}: 036.2's layout rules and
     * 042.7's {@link UiApi#dispatchWidgetSubsPlaced}, neither of which reaches Lua.
     *
     * <p><b>The adapters were not merely moved off a bad thread — they were widened</b> (112.5). This seam is
     * the server's message handler, so it never saw a widget the client mints for itself (an {@code Inventory}'s
     * {@code WItem} per item, the {@code ItemDrag} under the cursor, a {@code ContentsWindow}), and it announced
     * a widget the instant its <i>parent</i> took it — which for a subtree built before it is hung is before the
     * widget is in any tree. The entry seam has neither fault, so an adapter is now offered every widget that
     * enters any tree. Each already filters by type and dedups on its own cache, so the wider offer costs them
     * an {@code instanceof} and buys them the arrivals this seam could not report.
     */
    public static void onWidgetPlaced(int id, Widget wdg) {
        UiApi.onWidgetPlaced(id, wdg);
    }

    /**
     * The <b>widget-entry seam</b> — called from the {@code Widget.add0} core edit, the one point every widget
     * passes on its way into a tree, and the exact mirror of the removal seam in {@code Widget.remove}.
     *
     * <p><b>Why the placement seam above is not enough.</b> {@link #onWidgetPlaced} sits in
     * {@code UI.AddWidget.run}, which is the code that applies a <b>server</b> message. Everything the client
     * mints for itself — a {@code WItem} for each item an {@code Inventory} or an {@code Equipory} is given, the
     * {@code ItemDrag} under the cursor, a {@code ContentsWindow} — reaches the tree through {@code add} alone
     * and announced nothing, so {@code s:ui():on(sel, "Added")} could only ever see those in its registration
     * scan: right at {@code :reload}, and never again. Item icons are exactly that population.
     *
     * <p><b>And it fires only once the widget is really up.</b> A subtree is routinely built before it is hung:
     * the server gives a chest window its grid and hangs the window afterwards, so at the placement seam the
     * grid is in its window and the window is nowhere. {@code s:ui():on("inventory", "Added", …)} handed that
     * grid over, and the first verb on it refused with "this widget is no longer in the tree" — true at that
     * instant and false a moment later. Here the answer is asked <b>at the moment it is already true</b>: a
     * widget whose chain does not reach the root announces nothing, and is announced when its ancestor enters,
     * because that ancestor passes this very seam. Nothing waits, nothing is polled, and nothing re-checks.
     *
     * <p><b>Threading, and why it only enqueues</b> (112.3). {@code Widget.add} wraps {@code add0} in
     * {@code synchronized(ui)} whenever the parent has a {@code UI}, on whatever thread placed the widget — a
     * Loader thread applying a server update as often as the UI thread — so this seam is reached with that
     * tree's monitor already held. Running a handler here is what the deadlock was: the handler builds a window,
     * the builder takes the LAYER's monitor under the session's, and the frame holds the two in the other order.
     * So the tap records the widget and {@link #drainEnteredWidgets} dispatches it on the layer's step, where
     * no tree monitor is held and a handler may reach any tree. A widget with no {@code UI} yet cannot reach the
     * root, so it returns before touching anything.
     */
    public static void onWidgetEntered(Widget wdg) {
        // Never throws into `add`. Every other seam guards a path the client takes now and then; this one is on
        // the path the client takes to build ANY widget, its own login screen included, so a fault here would
        // not break a feature — it would break starting up.
        try {
            UiApi.enqueueEntered(wdg);
        } catch(RuntimeException e) {
            log("widget-entry seam error: " + e);
        }
    }

    /**
     * Deliver the widget entries captured since the last step, <b>every tree's</b> and on the layer's own pump
     * (112.3) — the one place in the frame that holds no tree monitor, which is the whole point of moving them
     * here. Bounded to one step's worth (D-106), the same bound the removal drain keeps and for the same
     * reason: a handler that opens a window whose subtree enters must not spin this step forever.
     *
     * <p><b>Every tree, not this one</b> — the shape {@link #drainGobEvents} has. A widget entering is one
     * client's news whichever tree it landed in, and the layer's pump is the one that runs whether or not any
     * session is up. (A session's own step holds no tree monitor either since 112.4, so either would be legal
     * now; this one stays because it is the only pump that is always there.)
     *
     * <p><b>Before the removals</b>, so a widget that entered and left inside one frame can never report its
     * {@code Added} after its {@code Removed}. The order is the reason this call sits where it does.
     */
    private static void drainEnteredWidgets() {
        for(SessionState st : allStates()) {
            for(int n = st.enteredWidgets.size(); n > 0; n--) {
                Widget w = st.enteredWidgets.poll();
                if(w == null)
                    break;
                UiApi.dispatchEntered(st, w);
            }
        }
    }

    /**
     * Deliver the item-info builds captured since the last step, every tree's, on the layer's pump (112.3) —
     * {@code item:on("Changed", fn)}. Bounded to one step's worth (D-106) like every other drain here: a
     * handler that reads a second item's contents builds that item's info and files another entry.
     *
     * <p><b>After the entry drain, and that ordering is load-bearing.</b> An item's icon cannot draw before
     * its widget is in the tree, so the build is never earlier than the entry — and running the entry drain
     * first means an {@code s:ui():on("item", "Added")} handler that subscribes {@code item:on("Changed")}
     * on what it was just handed still catches that item's <i>first</i> {@code Changed}, in this same step.
     *
     * <p>An item whose widget has left the tree between the build and here is still reported: what the event
     * says is that the client can now describe the item, which does not stop being true because the icon was
     * put away — and {@link #drainRemovedWidgets} ends its subscriptions a beat later, in the same frame.
     */
    private static void drainItemInfos() {
        for(SessionState st : allStates()) {
            for(int n = st.itemInfos.size(); n > 0; n--) {
                GItem it = st.itemInfos.poll();
                if(it == null)
                    break;
                for(Addon a : addons)
                    fireItem(a, it);
                fireItem(consoleOwner, it);
            }
        }
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
     * The <b>interception seam</b> (061.1) — called from {@code haven.AddonWidgets} at the sites where the
     * client itself receives an input, immediately before it runs its own method: fire the capability
     * {@code key} for every addon that holds it on {@code wdg} and answer whether to go on
     * ({@link Controls#activate}). {@code value} is what the control is about to take, for the keys that carry
     * one.
     *
     * <p><b>Threading.</b> UI thread, from an input pass, and it may raise Lua — which
     * {@link Subs#fire}/{@link #callLua} already isolate per handler, so a broken addon cannot break the click.
     * With nobody listening it is one map lookup per loaded addon and allocates nothing.
     */
    public static boolean activate(Widget wdg, String key, Object value) {
        return Controls.activate(wdg, wdg, key, value);
    }

    /**
     * The same seam where <b>the widget an addon holds is not the widget the client acts on</b> (061.3): a
     * dropdown's rows live in a popup that is not even its child, and a menu's in its own inner list, so the
     * key is addressed to {@code wdg} while {@code ev:resend()} must run the held-back method on {@code actor}.
     * Everywhere else the two are one widget and the three-argument form above says so.
     */
    public static boolean activate(Widget wdg, Widget actor, String key, Object value) {
        return Controls.activate(wdg, actor, key, value);
    }

    /**
     * The <b>reporting</b> half of the same seam (061.4) — called where the client has ALREADY written the
     * value it is announcing (a slider's drag, a scrollbar's drag, wheel and step), so there is nothing to
     * answer and the {@code ev} refuses both verbs that would pretend otherwise ({@link Controls#report}).
     *
     * <p><b>Threading.</b> As above, and from a per-{@code mousemove} path while a drag is in flight — which is
     * why nobody is asked anything here: a cancel would mean revert-and-repaint, a different verb entirely.
     */
    public static void report(Widget wdg, String key, Object value) {
        Controls.report(wdg, key, value);
    }

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

    /**
     * The <b>re-layout seam</b> (062) — the one {@code // addon:} line at the end of {@code GameUI.resize},
     * which re-places {@code chat}, {@code beltwdg}, {@code prog} and the map unconditionally on every screen
     * resize. Every hand-named level over one of that widget's own children goes back on top of what the
     * client just wrote, so a window the user dragged stays where they put it.
     *
     * <p>It runs <b>after</b> the client's own placement (it is the last line of that method), so it
     * overwrites rather than the reverse. Idempotent, and bounded by the held records: with no addon laying
     * anything out the whole call is one volatile read.
     */
    public static void relayout(Widget parent) {         Layout.reapply(parent);         }

    /**
     * The <b>radial-menu seams</b> (047.1) — the four {@code // addon:} lines in {@link FlowerMenu} that turn
     * the client's own context menu into {@code FlowerMenuAdded}/{@code FlowerMenuRemoved} and feed
     * {@code s:flowermenu()}. {@link FlowerMenuApi} holds the rules; these are the door haven calls
     * through.
     *
     * <p>{@link #flowerOpened} is the end of {@code added()} (the only point where the petal set is complete);
     * {@link #flowerClosed} is both {@code uimsg} branches <i>and</i> the {@code destroy()} fallback, and fires
     * at most once per open however the menu ended; {@link #flowerChoosing} records the petal a click picked,
     * so a client-side one closes carrying its own label.
     *
     * <p><b>Threading.</b> UI thread throughout, under the monitor the caller already holds, so the Lua raised
     * here never races other Lua. With nobody subscribing each call is a hash lookup and allocates nothing.
     */
    public static void flowerOpened(FlowerMenu m) {      FlowerMenuApi.opened(m);        }

    /** @see #flowerOpened */
    public static void flowerClosed(FlowerMenu m, String label) { FlowerMenuApi.closed(m, label); }

    /** @see #flowerOpened */
    public static void flowerChoosing(FlowerMenu m, FlowerMenu.Petal p) { FlowerMenuApi.choosing(m, p); }

    /**
     * The <b>click token</b> seam (047.3) — the one {@code // addon:} line in {@code MapView.Click.hit}, beside
     * the voice feature's own {@code VoiceTarget.note}. It records which object a press resolved to, so a radial
     * menu that opens straight afterwards can say what it belongs to ({@code s:flowermenu():gob()}); the
     * server sends no such thing, so the client correlates it itself. {@link ClickToken} holds the rules.
     *
     * <p>{@code g} is {@code null} for a press that hit the ground, and that is recorded too — it <b>replaces</b>
     * any older attribution, which is what a click on nothing should do.
     *
     * <p><b>Threading.</b> The click hit-test's own thread, under {@link ClickToken}'s monitor. It raises no Lua
     * and allocates one {@link Coord}.
     */
    /* addon: (105) the gob the click CURRENTLY being dispatched landed on, or -1 for ground. Set by
     * MapView.clickhit around its own wdgmsg and cleared in a finally, so it is live for exactly the window
     * in which an action handler can be running, and reads -1 for every other message. UI thread only, and
     * volatile because reading it is the only thing Lua does with it. */
    private static volatile long clickGobId = -1;

    /** addon: (105) hold the gob a click resolved to for the length of its dispatch; -1 clears it. */
    public static void clickgob(long id) {
        clickGobId = id;
    }

    /** addon: (105) what {@code ev:gob()} answers — see {@link #clickgob}. */
    static long clickGobId() {
        return clickGobId;
    }

    public static void noteClick(Gob g, Coord lcc) {
        ClickToken.note((g == null) ? -1 : g.id, lcc);
    }

    // The widget-targeting descriptor {id, type, place, caption, parentType} (D-024) is GONE (032.2). It was the
    // argument of replace{match=fn} and nothing else once 030.2 hard-cut the onWidgetCreate observer that shared
    // it; with hafen.ui.replace deleted there is exactly one vocabulary for "which window" left — the Selector.

    /** Re-read each dirty adapter and fire its semantic event (UI thread, drained from the tick). */

    // ------------------------------------------------------------- event dispatch

    /**
     * The bus's <b>closed key set</b> — every event {@code hafen.event():on(key, fn)} accepts, in the order
     * the catalogue lists them (lifecycle, sessions, world, character, roster, own entities). Closed because
     * the client knows the whole set at load, so an unknown key is a typo with no future meaning to wait for
     * (D-129): before 041 {@code hafen.event():on("GobAdded ", fn)} was accepted and simply never fired,
     * which is the most common silent addon bug there is.
     *
     * <p>PascalCase throughout, and it is the bus's <i>own</i> spelling that the rest of the API adopted in
     * 041 — so most of what that feature found was already the exact string the corpus called. The four
     * lifecycle keys moved there, dropping the {@code On} prefix that {@code :on} already says, and 074.3
     * moved one of those again: an addon no longer enters the world, a <b>session</b> does, so the moment is
     * {@code SessionEnteredWorld} and the spelling it replaced throws (see {@link Refusal#eventKey}).
     *
     * <p><b>The session family is four keys and one payload</b> (076.2): a {@link LuaSession}, the address
     * every read the handler goes on to make is named by. They are the vocabulary the addon layer needs now
     * that it outlives a character switch — nothing else says the screen changed, or that the character an
     * addon cached a handle from is gone — and a near miss among them is refused naming all four
     * ({@link #busKeyRefusal}), because three of the four differ by a single word.
     */
    static final String[] BUS_KEYS = {
        "Load", "Update", "Disable",
        "SessionAdded", "SessionEnteredWorld", "SessionSelected", "SessionRemoved",
        "GobAdded", "GobRemoved", "GobOverlayAdded", "GobOverlayRemoved", "GobSdtChanged",
        "MeterAdded", "MeterRemoved", "MeterChanged",
        "BuffAdded", "BuffRemoved", "BuffChanged",
        "FepChanged", "StudyChanged", "EquipChanged", "ActionbarChanged", "WoundChanged",
        "KinChanged", "QuestAdded", "QuestCompleted", "QuestFailed", "MarkerChanged",
        "FlowerMenuAdded", "FlowerMenuRemoved",
        "ChannelAdded", "ChannelRemoved", "ChannelSelected", "MessageAdded",
        "GhostClicked", "SpriteClicked", "ObjectClicked", "PatchClicked",
    };

    /**
     * The refusal for a key that is not one of the {@link #BUS_KEYS}. A near miss inside the <b>session
     * family</b> gets all four spelled out (076.2): they differ by one word each, they are what an addon
     * subscribes to before it has anything to read, and a subscription that silently never fires is the most
     * expensive way there is to learn a name.
     *
     * <p>{@link Subs#WILD} is refused for that same reason, and its hint names <b>where it does mean
     * everything</b> (082.3): the bus is a closed set of facts whose payload IS the fact — a {@code Gob}, a
     * {@code Meter}, a {@code Session} — so a handler here has no parameter a key could arrive in, while
     * each stream's key set is open and {@code "*"} is the whole of it. The pointer is the point: a reader
     * who wrote {@code "*"} on the bus wants the streams and does not yet know they are there.
     */
    private static String busKeyRefusal(String key) {
        String hint;
        if(Subs.WILD.equals(key))
            hint = " — the bus keys are a closed set of facts and each hands your handler the fact itself,"
                + " so there is nothing here for \"*\" to name; it is every message on a stream instead:"
                + " hafen.event():action():on(\"*\", fn) and hafen.event():message():on(\"*\", fn)";
        else if(key.toLowerCase().startsWith("session"))
            hint = " — the session family is SessionAdded, SessionEnteredWorld, SessionSelected and"
                + " SessionRemoved";
        else if(key.toLowerCase().contains("sdt"))
            hint = " — the key is GobSdtChanged";
        else
            hint = "";
        return "hafen.event():on(key, fn): unknown event '" + key + "'" + hint
            + " — see docs/addons/api/event/bus/ for the catalogue";
    }

    /** Is {@code key} one of the {@link #BUS_KEYS}? (Linear over the constants, once per subscription.) */
    private static boolean busKey(String key) {
        for(String k : BUS_KEYS) {
            if(k.equals(key))
                return true;
        }
        return false;
    }

    // ------------------------------------------------- the two message streams (hafen.event():action/:message)

    /**
     * One addon's <b>stream emitter</b> — the object {@code hafen.event():action()} and
     * {@code hafen.event():message()} hand back (041.2). It is minted once per addon and per stream, and the
     * Lua value IS its {@link Subs}: the emitter has no state beyond its subscriptions, so wrapping it in a
     * second object would only be a second thing to keep in step.
     *
     * <p>One verb, {@code :on(msg, fn)}, and a CLOSED vocabulary around an OPEN key set — the two are
     * different questions. An unknown <i>verb</i> on the emitter throws (the grammar is the client's), while
     * an unknown <i>msg</i> is accepted (the name is the protocol's, D-129).
     *
     * <p><b>One key is reserved</b>: {@link Subs#WILD} means every message on this stream, which is the one
     * subscription a caller cannot write by hand — the key set is open precisely because the list is
     * unknowable, so enumerating it is the thing this exists to avoid. It is validated nowhere because
     * nothing about it needs refusing: it goes into the same {@link Subs} as any other key, and it is the
     * dispatch that looks its list up beside the named one.
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
        mt.set(LuaValue.INDEX, Refusal.closedIndex("hafen.event():" + nm + "()", m,
            "a message stream",
            "its key set is OPEN: any message name is accepted, because a wdgmsg name is protocol rather"
            + " than a catalogue the client owns, and \"*\" is every message on this stream"));
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
        // 073.1: the SENDER's own session, which is the tree the message is leaving and the monitor that
        // guards it. screen() would answer the wrong thing the moment a message is sent from a session
        // that is not the one drawn.
        UI u = (sender == null) ? null : sender.ui;
        SessionState st = state(u);
        if((st == null) || !Thread.holdsLock(u))
            return true;                              // only run Lua on a UI-locked (Lua-safe) send path
        if(st.dispatchingAction)
            return true;                              // re-entrancy: a handler body sent another wdgmsg
        Subs.Cancel c = new Subs.Cancel();
        st.dispatchingAction = true;
        try {
            for(Addon a : addons)
                fireAction(a, sender, msg, args, c, u);
            Addon co = consoleOwner;
            if(co != null)
                fireAction(co, sender, msg, args, c, u);
        } finally {
            st.dispatchingAction = false;
        }
        return !c.prevented();
    }

    /**
     * Run one owner's action handlers, with its own {@code ev} over the shared cancel flag — <b>the named
     * list first, then the wildcard one</b> ({@link Subs#WILD}), over the SAME {@code ev}.
     *
     * <p><b>One payload, two lists.</b> An addon holding both {@code "click"} and {@code "*"} has both
     * handlers handed the very same value, so the shared {@link Subs.Cancel} behaves exactly as it does
     * between two handlers on one key: either one cancels the send once, and the last {@code send}/
     * {@code resend} wins. Named first because the named key is the SPECIFIC claim on this message and the
     * wildcard the ambient one — the specific handler sees the {@code ev} first, the ambient one sees what
     * was done to it.
     *
     * <p>{@code named} is {@code has(msg) && !WILD.equals(msg)} so a message that somehow arrived called
     * {@code *} fires one list rather than the same list twice.
     */
    private static void fireAction(Addon a, Widget sender, String msg, Object[] args, Subs.Cancel c, UI u) {
        boolean named = a.actionSubs.has(msg) && !Subs.WILD.equals(msg);
        boolean wild = a.actionSubs.wild();
        if(!named && !wild)
            return;                                   // the hasSub gate, kept: no ev for an owner not listening
        LuaValue ev = LuaEvent.action(a, sender, msg, args, c, u);
        if(named)
            a.actionSubs.fire(msg, c, ev);
        if(wild)
            a.actionSubs.fire(Subs.WILD, c, ev);
    }

    /**
     * The inbound-{@code uimsg} dispatch (041.2, over the L3 hook level it replaced) — the body behind
     * {@link #onMessage}. Every {@code hafen.event():message():on(msg, fn)} handler runs before the target
     * widget applies the update, and the answer is what to apply: the original {@code args}, a rewritten
     * array ({@code ev:rewrite(t)}), or {@code null} to swallow it ({@code ev:preventDefault()}).
     *
     * <p><b>{@code preventDefault} beats {@code rewrite}</b>, and the last {@code rewrite} of one message
     * wins — the precedence the hook levels had, unchanged. No {@code holdsLock} guard, unlike
     * {@link #dispatchAction}: since 112.7 this seam is reached from <i>above</i> {@code UiMessage.run}'s
     * {@code synchronized(ui)} block, so there is no tree monitor held here to test for — see
     * {@link #onMessage} for what that buys and what it costs.
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

    /**
     * Run one owner's message handlers, with its own {@code ev} over the shared cancel and rewrite slots —
     * <b>the named list first, then the wildcard one</b> ({@link Subs#WILD}), over the SAME {@code ev}. The
     * inbound mirror of {@link #fireAction}, shape for shape (082.2).
     *
     * <p><b>One payload, two lists.</b> An addon holding both {@code "set"} and {@code "*"} has both handlers
     * handed the very same value, so the shared {@link Subs.Cancel} and the shared {@code rewritten} slot
     * behave exactly as they do between two handlers on one key: either one swallows the update once, and the
     * last {@code rewrite} wins. Named first because the named key is the SPECIFIC claim on this message and
     * the wildcard the ambient one — the specific handler sees the {@code ev} first, the ambient one sees
     * what was done to it.
     *
     * <p>{@code named} is {@code has(msg) && !WILD.equals(msg)} so a message that somehow arrived called
     * {@code *} fires one list rather than the same list twice.
     */
    private static void fireMessage(Addon a, Widget target, String msg, Object[] args, Subs.Cancel c,
                                    Object[][] rewritten) {
        boolean named = a.messageSubs.has(msg) && !Subs.WILD.equals(msg);
        boolean wild = a.messageSubs.wild();
        if(!named && !wild)
            return;                                   // the hasSub gate, kept: no ev for an owner not listening
        LuaValue ev = LuaEvent.message(a, target, msg, args, c, rewritten);
        if(named)
            a.messageSubs.fire(msg, c, ev);
        if(wild)
            a.messageSubs.fire(Subs.WILD, c, ev);
    }

    /**
     * Does any owner subscribe to {@code msg} on the action ({@code true}) or message stream — <b>or to the
     * whole of it</b> ({@link Subs#WILD})? The wildcard is one volatile field read in FRONT of the map lookup
     * this gate already did, so an addon that named its key pays less here than it did rather than more:
     * a hit on the field short-circuits, and a miss costs a boolean.
     */
    private static boolean anyStreamSub(String msg, boolean action) {
        for(Addon a : addons) {
            Subs s = action ? a.actionSubs : a.messageSubs;
            if(s.wild() || s.has(msg))
                return true;
        }
        Addon c = consoleOwner;
        if(c == null)
            return false;
        Subs s = action ? c.actionSubs : c.messageSubs;
        return s.wild() || s.has(msg);
    }

    // --------------------------------------------------- the session family (074.3)

    /**
     * <b>Sessions coming, being picked, and going</b> — captured wherever they happen and fired on the
     * layer's own tick. Each entry is {@code {key, account name}}: what the <i>queue</i> carries is the account
     * name, because a seam on another thread may hold nothing else by the time the tick reads it — and because
     * the name is the whole of a {@link LuaSession} anyway, so the payload is minted from it at fire time by
     * {@link #fireSession}.
     *
     * <p><b>Queued, never fired at the seam.</b> A session is added from a console command's thread, picked
     * from whatever thread reached {@code Sessions.anchor}, and destroyed from its own runner thread; Lua
     * runs on the UI thread and nowhere else (P5), so all three only enqueue and {@link #drainSessionEvents}
     * turns them into a fire — the same marshalling every off-thread seam in this layer uses (D-106). The
     * <b>layer's</b> tick and not a session's: these events are the client's, and the session one of them is
     * about may be the one that just ended.
     */
    private static final Queue<String[]> sessionEvents = new ConcurrentLinkedQueue<String[]>();

    /** Call site — {@code Sessions.add} and {@code Sessions.adopt}: a session connected. */
    public static void sessionAdded(String user) { queueSession("SessionAdded", user); }

    /** Call site — {@code Sessions.anchor(Member)}: the screen changed to this session. */
    public static void sessionSelected(String user) { queueSession("SessionSelected", user); }

    /** Call site — {@code Sessions.Member.run}'s {@code finally}: this session ended, however it ended. */
    public static void sessionDestroyed(String user) { queueSession("SessionRemoved", user); }

    /** Enqueue one session event. A nameless session is not one of these — nothing could act on it. */
    private static void queueSession(String key, String user) {
        if((user != null) && !user.isEmpty())
            sessionEvents.add(new String[] {key, user});
    }

    /**
     * Drain one frame's worth of {@link #sessionEvents} on the UI thread, in the order the seams recorded
     * them — so an addon hears a session arrive before it is picked, and be picked before it ends.
     */
    private static void drainSessionEvents() {
        for(String[] e = sessionEvents.poll(); e != null; e = sessionEvents.poll())
            fireSession(e[0], e[1]);
    }

    /**
     * Fire one of the four session events, whose payload is a <b>Session object</b> (076.2) — the address an
     * addon names a character by, rather than a bare account name a handler would have to hand back to
     * {@code hafen.session():get} before it could read anything with it.
     *
     * <p>The {@link #fireGob} shape exactly, and for its two reasons: interning is <b>per addon</b> (D-045),
     * so the payload cannot be shared — one object handed to every owner would cross a sandbox boundary — and
     * it is minted only for an owner that actually subscribes, so the addons that do not listen pay nothing.
     *
     * <p>A {@code SessionRemoved} payload names a session that is <b>already gone</b>: the account name is
     * the whole of the ref, so {@code :user()} answers there while {@code :exists()} is {@code false}, which
     * is what lets a handler drop its own tables by the very key it was handed.
     */
    /**
     * {@code MarkerChanged} — the marker COLLECTION, per owner (091, A-083). It was a bare count, which
     * answered a question nobody asked ({@code :count()} is one call away) and not the one they did.
     */
    static void fireMarkers() {
        for(Addon a : addons) {
            if(hasSub(a, "MarkerChanged"))
                fireTo(a, "MarkerChanged", MapApi.markers(a));
        }
        Addon c = consoleOwner;
        if((c != null) && hasSub(c, "MarkerChanged"))
            fireTo(c, "MarkerChanged", MapApi.markers(c));
    }

    /**
     * The <b>item-info seam</b> — the core edit at the end of {@code GItem.info()}'s build block, and the moment
     * an item stops being a picture and starts being a thing the client can describe. Fires
     * {@code item:on("Changed", fn)} on the addons that hold that item.
     *
     * <p><b>Why here and not at the server's message.</b> Two things have to happen before a quality, a name, a
     * wear row or a contents block can be read, and only the first is a message: the server sends the tooltip
     * ({@code GItem.uimsg "tt"}, which clears the built list) and the code that renders it — which ships inside
     * a resource — has to be loaded. Until the second, building the list throws {@code Loading} and every read
     * answers {@code nil}. This seam sits where the build SUCCEEDS, so it names the moment both are true,
     * whichever of them was last. That is the moment an addon drawing a number on an icon is waiting for, and
     * before 104 there was no way to be told it: an author had no choice but to keep asking.
     *
     * <p><b>It costs nothing per frame.</b> {@code info()} caches into {@code GItem.info} and only enters its
     * build block when that field is null — once per arrival and once per revision, never per draw. A theme
     * change also rebuilds the list (the tooltip is re-rendered in the new font) and so fires this too: the
     * words are the same and a handler re-reading them writes what it wrote before, which is why that is left
     * as an honest extra rather than filtered with a second flag.
     *
     * <p><b>Threading, and why it only enqueues</b> (112.3). The build runs on whichever thread first asks for
     * the item's info, and the two that usually ask are holding a tree monitor when they do: the draw of the
     * icon, and an addon's own read from a handler that already has one. Both dumps of the freeze this feature
     * ends run through here — {@code item:contents()} forces the build, the build fires a second addon's
     * {@code item:on("Changed")}, and that handler writes another tree's widget. So the tap records the item
     * and {@link #drainItemInfos} fires it on the layer's step, holding nothing.
     *
     * <p>It also takes the fire-side read of {@link Addon#itemSubs} — a plain {@code WeakHashMap} — off
     * whichever thread happened to build the info and onto the step, beside the {@code item:on} that writes it.
     */
    public static void onItemInfo(GItem it) {
        // Never throws into info(). This runs inside the build that WItem.draw asks for, so a fault here would
        // not break a subscription — it would break drawing the icon, and info() already has a meaningful
        // throw of its own (Loading) that callers handle.
        try {
            SessionState st = queueState((it == null) ? null : it.ui);   // the tree the item is in, and no other
            if(st != null)
                st.itemInfos.add(it);
        } catch(RuntimeException e) {
            log("item-info seam error: " + e);
        }
    }

    /** {@code Changed} to one owner, and only if that owner is actually holding this item's door open. */
    private static void fireItem(Addon a, GItem it) {
        if(a == null)
            return;
        Subs s = a.itemSubsOrNull(it);            // never mints: an addon that never subscribed pays a map get
        if((s == null) || !s.has(LuaItem.CHANGED))
            return;
        s.fire(LuaItem.CHANGED, LuaItem.of(a, it));
    }

    static void fireSession(String event, String user) {
        for(Addon a : addons) {
            if(hasSub(a, event))
                fireTo(a, event, LuaSession.of(a, user));
        }
        Addon c = consoleOwner;
        if((c != null) && hasSub(c, event))
            fireTo(c, event, LuaSession.of(c, user));
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
     * {@code widget:on("Update", fn)} on every surface that has one, once for this frame (112.1). It is NOT
     * fired from {@code AddonWidget.tick}: that is a {@code TickEvent} callback and runs with that tree's
     * monitor held, so a handler writing another tree's widget would take a second monitor under the first —
     * the deadlock this feature ends. Firing it from the step costs nothing and buys it every tree.
     *
     * <p><b>A per-addon list, not a walk of the widgets</b> ({@link Addon#updateSurfaces}). The fire-side
     * lookup {@code widget:on} is built on ({@link Addon#widgetSubsOrNull}) exists so that a surface nobody
     * subscribed to costs one map lookup a frame and nothing else; folding over every widget-subs entry here
     * would spend that saving on behalf of the addons that are not listening. The list holds exactly the
     * surfaces with a live {@code Update} handler, maintained at {@link WidgetSubs#on} and at the
     * {@code Subs.Idle} that says the key emptied out.
     *
     * <p><b>The two guards came with it.</b> A surface that is {@link AddonWidget#dead() dead} is dropped from
     * the list here, and one still {@link AddonWidget#pending() pending} is skipped for this frame, exactly as
     * a half-configured widget paints nothing until its arming tick. The drop is also the list's own sweep: a
     * surface whose only subscription is {@code Update} joins no removal watch list, so nothing else would
     * ever tell this list that it is gone. Unlinked counts as gone — {@code Widget.remove()} nulls the parent —
     * and both the content and the root are asked, since a window's chrome is the half that leaves the tree.
     */
    private static void fireSurfaceUpdates(LuaValue dt) {
        for(Addon a : addons)
            fireSurfaceUpdates(a, dt);
        Addon c = consoleOwner;
        if(c != null)
            fireSurfaceUpdates(c, dt);
    }

    /** One addon's surfaces, in the order they subscribed — see {@link #fireSurfaceUpdates(LuaValue)}. */
    private static void fireSurfaceUpdates(Addon a, LuaValue dt) {
        if(a.updateSurfaces.isEmpty())
            return;
        for(WidgetSubs s : a.updateSurfaces) {   // a snapshot: a handler may subscribe or off() while it runs
            AddonWidget w = s.surface();
            if((w == null) || w.dead() || (w.parent == null) || (w.rootw().parent == null)) {
                a.updateSurfaces.remove(s);
                continue;
            }
            if(w.pending())
                continue;
            if(s.subs.has("Update"))
                s.subs.fire("Update", dt);
        }
    }

    /**
     * Fire a gob event ({@code GobAdded}/{@code GobRemoved}) whose payload is a <b>Gob object</b> (D-044). Unlike
     * {@link #fire} the payload cannot be shared: interning is per-addon (D-045), so each owner gets <i>its</i>
     * handle for the id — minted only when that owner actually subscribes, so a busy spawn stream costs nothing
     * for the addons that don't listen. On {@code GobRemoved} the gob is already gone, so only {@code :id()}
     * answers — an addon that needs the name must have indexed it on {@code GobAdded}.
     *
     * <p><b>Reached once per object and not once per session</b> (079.4): the payload is the interned handle
     * for the id — the same one {@code s:world():gob():get(id)} answers with — and which characters can see it
     * is {@code gob:sessions()}, a live read. {@link #drainGobEvents} is the one caller, and the edge it
     * settles is what makes "once" true.
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
            queueOverlayEvent(g, new OverlayEvent(added, g.id, key, true, null));
        } catch(RuntimeException e) {
            /* the engine's overlay path is not ours to break */
        }
    }

    /** An addon's own attach/remove ({@code gob:overlay}), queued onto the tick like the game's. */
    static void queueGobOverlay(boolean added, Gob g, String key, Addon owner) {
        if(overlaySubs && (g != null))
            queueOverlayEvent(g, new OverlayEvent(added, g.id, key, false, owner));
    }

    /**
     * File one overlay event under <b>the session the gob is in</b> (073.1). A {@link Gob} carries its own
     * {@link Glob}, which is what names that session — and it has to, because this runs on the loader threads
     * of whichever session the decoration arrived for, which is not the one on screen: a gob id means a
     * different object in each. Dropped for a gob whose session holds no state, which is a gob of a world
     * nothing is draining.
     */
    private static void queueOverlayEvent(Gob g, OverlayEvent oe) {
        SessionState st = queueState(io.brodgar.session.Sessions.uifor(g.glob));
        if(st != null)
            st.overlayEvents.add(oe);
    }

    /**
     * Deliver the overlay events captured since the last tick. <b>Bounded by what is in the queue right now</b>:
     * a handler that attaches or removes an overlay of its own queues another event, and draining until empty
     * would let a handler that re-attaches under the same key spin the frame forever. One frame's worth per
     * frame turns that into a slow loop the addon can see and its watchdog can price, instead of a hang.
     *
     * <p><b>The game's own overlays are a world fact and fire once</b> (079.4) — every session that has loaded
     * the object is told about the decoration on it, and one flame on one fire is one event however many
     * characters can see it. {@link #nativeEdge} is that gate; an addon's own attach is queued at a single
     * gob and needs none.
     */
    private static void drainOverlayEvents(SessionState st) {
        for(int n = st.overlayEvents.size(); n > 0; n--) {
            OverlayEvent oe = st.overlayEvents.poll();
            if(oe == null)
                break;
            if(oe.nat && !nativeEdge(oe))
                continue;
            fireGobOverlay(oe.added ? "GobOverlayAdded" : "GobOverlayRemoved", oe.gobId, oe.key, oe.nat, oe.owner);
        }
    }

    /**
     * <b>A gob's state bytes changed</b> (113.3) — the {@code // addon:} line at the end of
     * {@code ResDrawable.$cres.apply}'s body, the one place the wire's {@code OD_RES} delta replaces
     * them (see {@code docs/client/resources.md}). {@code sdt} is THIS delta's own bytes — read once,
     * here, into a plain {@code byte[]} rather than kept as the {@link haven.MessageBuf} the sprite still
     * shares, since that object's cursor is not this seam's to disturb.
     *
     * <p>Called from whichever thread applies the delta — a Loader thread as often as not, under
     * {@code OCache.ObjDelta.apply}'s {@code synchronized(gob)} — so, like {@link #gobOverlayCame}, it
     * does the least possible: never Lua, never a throw back into the engine's own delta path.
     */
    public static void gobSdtChanged(Gob g, MessageBuf sdt) {
        if(!sdtSubs || (g == null))
            return;
        try {
            queueSdtEvent(g, sdt.clone().bytes());
        } catch(RuntimeException e) {
            /* the engine's delta path is not ours to break */
        }
    }

    /**
     * File one sdt-change event under <b>the session the gob is in</b> (073.1) — see
     * {@link #queueOverlayEvent}, the same reasoning one field over: a {@link Gob}'s own {@link Glob}
     * names the session this delta belongs to, which is not necessarily the one on screen.
     */
    private static void queueSdtEvent(Gob g, byte[] bytes) {
        SessionState st = queueState(io.brodgar.session.Sessions.uifor(g.glob));
        if(st != null)
            st.sdtEvents.add(new SdtEvent(g.id, bytes));
    }

    /**
     * Deliver the sdt-change events captured since the last tick, bounded by the queue's size at entry —
     * see {@link #drainOverlayEvents} for why a handler that reacts by re-reading cannot spin the frame.
     *
     * <p><b>The state is a world fact and fires once</b> (113.3), the same shape as the game's own
     * overlays one section up: the delta stream is per session (two sessions holding one gob each apply
     * it off their own {@code OCache}), so {@link #sdtEdge} is what settles two reports of one change
     * into the single fire {@code GobSdtChanged} promises.
     */
    private static void drainSdtEvents(SessionState st) {
        for(int n = st.sdtEvents.size(); n > 0; n--) {
            SdtEvent se = st.sdtEvents.poll();
            if(se == null)
                break;
            if(sdtEdge(se.gobId, se.sdt))
                fireGobSdt(se.gobId, se.sdt);
        }
    }

    /** The bytes last REPORTED for a gob's state, keyed by id — the fire-once gate {@link #sdtEdge} reads
     *  and updates, in the shape of {@link #nativeEdge} one level down: not "is a session's copy new" but
     *  "is this the OBJECT's edge", since two sessions can race to report one delta. Dropped whole with
     *  the gob in {@link #gobLeft}, like {@link #heldNative}. */
    private static final Map<Long, byte[]> lastSdt = new HashMap<Long, byte[]>();

    /**
     * <b>Is {@code bytes} a real change from what was last reported for {@code gobId}?</b> True and
     * recorded exactly when it differs from the last firing's own bytes — so a second session queuing
     * the identical delta a moment later finds nothing to report, and a later delta that changes the
     * bytes again does.
     */
    private static boolean sdtEdge(long gobId, byte[] bytes) {
        Long key = Long.valueOf(gobId);
        byte[] was = lastSdt.get(key);
        if(java.util.Arrays.equals(was, bytes))
            return false;
        lastSdt.put(key, bytes);
        return true;
    }

    /**
     * Fire {@code GobSdtChanged} (a {@link LuaEvent}, payload {@code :gob()} {@code :sdt()}) — 113.3.
     * <b>Broadcasts</b>, like {@link #fireGob}: the state is the SERVER's object's, owned by no one
     * addon, so every subscriber is told alike. {@code bytes} is THIS firing's own, converted fresh per
     * owner ({@link #sdtTable}) so one addon's handler cannot scribble on another's copy of one event —
     * never a live re-read, so two deltas landing in one drain cannot make an intermediate stage vanish.
     */
    static void fireGobSdt(long gobId, byte[] bytes) {
        for(Addon a : addons) {
            if(hasSub(a, "GobSdtChanged"))
                fireTo(a, "GobSdtChanged", LuaEvent.sdt(a, gobId, sdtTable(bytes)));
        }
        Addon c = consoleOwner;
        if((c != null) && hasSub(c, "GobSdtChanged"))
            fireTo(c, "GobSdtChanged", LuaEvent.sdt(c, gobId, sdtTable(bytes)));
    }

    // ------------------------------------------------------------- the world's edge (079.4)
    //
    // THE FOUR WORLD EVENTS FIRE ONCE. A gob id is the server's and names one object; five characters standing
    // together see one tree, and five GobAdded for it is the defect rather than the reporting of it. So the
    // client keeps the one thing a live read cannot answer -- WHICH SIDE OF THE EDGE IT WAS ON LAST -- and
    // reads everything else off the object caches at the moment it settles:
    //
    //   enters any session      -> GobAdded
    //   leaves one, still in another -> nothing (gob:sessions() is a live read; there is nothing to announce)
    //   leaves the last         -> GobRemoved
    //
    // Two sets, UI-thread only, and nothing else is kept: no reference count, which would be a second copy of
    // what the OCaches already know and the one that disagrees is the one nothing reads.

    /** The ids at least one live session holds, as of the last settle — the edge {@code GobAdded}/{@code
     *  GobRemoved} fire on. */
    private static final Set<Long> heldGobs = new LinkedHashSet<Long>();

    /** The game's own overlay keys at least one session carries, per gob id — the same edge, one level down,
     *  and dropped whole with the gob it hangs on. */
    private static final Map<Long, Set<String>> heldNative = new HashMap<Long, Set<String>>();

    /**
     * <b>How many sessions have gone</b> (079.4) — the signal that what one of them alone could see has to be
     * re-asked. {@link OCache} reports an object arriving and leaving and has no third callback: a session
     * ending drops its whole cache in silence, so nothing would ever say that the last holder of an object is
     * gone.
     *
     * <p>A counter and not a flag, because it is bumped off the tick — on the dying session's own thread — and
     * a flag the drain cleared could swallow one raised an instant before it. A lost increment cannot hide a
     * death either: two at once still move the number, and one rescan re-asks the whole set anyway.
     */
    private static volatile int gobRescans = 0;

    /** The {@link #gobRescans} the drain has already answered. UI thread only. */
    private static int gobRescansSeen = 0;

    // ------------------------------------------------------------- the hold (114.1)
    //
    // AN OBJECT REACHES THE SCREEN BEFORE THE EVENT THAT ANNOUNCES IT, and that frame is not an addon's to
    // close: OCache.add fires its callbacks on a Loader thread, MapView.Gobs.addgob puts the object in the
    // render tree on that same thread, and the layer's copy of the same news waits in gobEvents for the next
    // step. So the render add asks one question -- has the layer already drained this object's event? -- and
    // while the answer is no it throws a Loading, which Loader.Future.run parks and re-queues on notify. The
    // event is not moved and no Lua runs off the step; the picture waits for it.
    //
    // Three releases, and the world is drawable if any one of them fires: the drain clears every copy it
    // walked, a session dying releases the copies no live session can see any more, and a wall-clock
    // deadline swept every step releases anything held past it regardless.

    /** How long a copy may be held before it is drawn anyway, seconds of wall clock. The drain runs every
     *  step, so this is the belt: nothing in this layer can leave the world undrawn for longer. */
    private static final double HOLD_MAX = 1.0;

    /** <b>How many times the gate has parked a render add</b> — {@code hafen.client():profiling():render()}'s
     *  {@code gobsHeld}, cumulative since the client started. Counts holds and not objects: a copy released
     *  and re-parked counts twice, which is what makes it the witness that the gate engaged at all. */
    private static final AtomicLong gobsHeld = new AtomicLong(0L);

    /** @see #gobsHeld */
    public static long gobsHeld() {
        return gobsHeld.get();
    }

    /** The one queue every parked add waits on. One for the client, not one per gob: the wakeup is a
     *  re-check, and what it costs is bounded by the objects in flight, which is single digits — a queue per
     *  arriving object would buy precision with an allocation per object. */
    private static final Waitable.Queue gobHoldWait = new Waitable.Queue();

    /** Every copy being held, and the wall clock it is released at regardless. Written from the network and
     *  Loader threads that arm a hold and from the step that releases one, hence concurrent. */
    private static final Map<Gob, Double> gobHolds = new ConcurrentHashMap<Gob, Double>();

    /**
     * <b>What the gate throws.</b> The re-check in {@link #waitfor} is the whole of why it is a class of its
     * own: the gate reads the flag and throws, and only afterwards does {@link haven.Loader.Future} register
     * a waiter — so a drain landing in that gap would clear the flag and notify an empty queue, and the add
     * would park for ever. Asking again under the queue's own monitor, which is the monitor the release
     * takes, closes it. {@code Gob.DataLoading} is the in-tree precedent for the same shape.
     */
    private static final class GobHold extends Loading {
        private final transient Gob gob;

        GobHold(Gob gob) {
            super("gob held for GobAdded");
            this.gob = gob;
        }

        public void waitfor(Runnable callback, Consumer<Waitable.Waiting> reg) {
            boolean released;
            synchronized(gobHoldWait) {
                released = !gob.addonpend;
                if(released)
                    reg.accept(Waitable.Waiting.dummy);
                else
                    gobHoldWait.waitfor(callback, reg);
            }
            if(released)                // outside the monitor: the callback takes the Loader's own
                callback.run();         //   (boostprio is inherited false -- there is nothing to boost)
        }
    }

    /**
     * Hold this copy out of the render tree until its {@code GobAdded} has fired. Called from the
     * {@link OCache.ChangeCallback} that queues the event, <b>before</b> the enqueue: a drain that ran
     * between the two would clear a flag that was then set, and the copy would be held with nothing left to
     * release it but the deadline.
     */
    private static void armHold(Gob g) {
        g.addonpend = true;
        gobHolds.put(g, Double.valueOf(Utils.rtime() + HOLD_MAX));
    }

    /**
     * <b>The gate</b>, called from {@code Gob.added(RenderTree.Slot)} while {@code addonpend} — throws, every
     * time, and the caller's own {@code if} is what keeps the disarmed path to one volatile read.
     */
    public static void holdRender(Gob g) {
        gobsHeld.incrementAndGet();
        throw new GobHold(g);
    }

    /** Release these copies and wake every parked add. The flags are written under the queue's monitor, which
     *  is what {@link GobHold#waitfor} re-checks under; the notify is outside it, as {@code Waitable.Queue}
     *  runs its callbacks outside its own. */
    private static void releaseHolds(List<Gob> gobs) {
        if((gobs == null) || gobs.isEmpty())
            return;
        synchronized(gobHoldWait) {
            for(int i = 0, n = gobs.size(); i < n; i++) {
                Gob g = gobs.get(i);
                g.addonpend = false;
                gobHolds.remove(g);
            }
        }
        gobHoldWait.wnotify();
    }

    /** Everything being held, released — the reload's own case: the addons that armed the gate are being torn
     *  down, so nothing is left that could ever answer for these copies. */
    static void releaseAllHolds() {
        if(!gobHolds.isEmpty())
            releaseHolds(new ArrayList<Gob>(gobHolds.keySet()));
    }

    /**
     * <b>The deadline</b>, swept every step: a copy held past {@link #HOLD_MAX} is drawn anyway. It is the
     * belt under the drain, so it runs before the step can return early for any other reason — no defect in
     * this layer may leave the world undrawn. Two reference reads on the steps that hold nothing.
     */
    private static void sweepHolds() {
        if(gobHolds.isEmpty())
            return;
        double now = Utils.rtime();
        List<Gob> late = null;
        for(Map.Entry<Gob, Double> e : gobHolds.entrySet()) {
            if(now >= e.getValue().doubleValue()) {
                if(late == null)
                    late = new ArrayList<Gob>();
                late.add(e.getKey());
            }
        }
        releaseHolds(late);
    }

    /**
     * <b>Every session's gob queue, drained together and settled before anything is emitted</b> (079.4) — on
     * the LAYER's tick, because these events are the client's now and there is one edge for the client.
     *
     * <p><b>Settling is the whole of it.</b> {@code 073} queues the deltas per session, so one frame's worth
     * is several sessions' worth: a gob that leaves A and enters B in that window never really left, and a
     * drain that emitted as it walked would report the removal it saw first and then an arrival, for an object
     * that never went anywhere. So the walk only applies the per-gob consequences and remembers which ids were
     * touched; the caches are asked afterwards, when they have stopped moving, and only the ids that crossed
     * the edge are reported. That is what works with one session and fails with five moving.
     */
    private static void drainGobEvents() {
        List<Long> touched = null;
        List<Gob> held = null;          // 114.1: the copies this drain is releasing, once it has announced them
        for(SessionState st : allStates()) {
            GobEvent ge;
            while((ge = st.gobEvents.poll()) != null) {
                // 038.2: an overlay dies with its gob. Done BEFORE the event reaches Lua, so a GobRemoved
                // handler already reads the truth — and it is what a world-space overlay costs: its visual is
                // a client-only gob of its own, which nothing disposes just because the target left OCache.
                // 043.2: the same is true of a hafen.virtual() entity that :add(what, gob) anchored, which has no
                // record on the gob to be found through — VirtualApi's by-target index is what makes that O(1) too.
                if(!ge.added) {
                    LuaGobOverlay.gobGone(ge.gob);
                    VirtualApi.anchorGone(ge.gob.id);   // 075.3: the client's one index, and only if no session still sees it
                }
                // 092.7 (A-087): a copy of the object just arrived in THIS session, and a visual write made
                // before it did landed only on the copies that existed then. The per-session edge the ROADMAP
                // said did not exist is this queue entry — the settle below throws the per-session half away
                // to fire one client-wide event, which is right for the event and is why nothing re-applied.
                if(ge.added)
                    GobIntent.applyTo(ge.gob);
                // 075.3: ...and either way, which characters can see that object just changed — so a thing
                // standing on it that survived because ANOTHER character has it in view is re-asked whether
                // the one on screen does. A flag, and only for the ids something is actually standing on.
                VirtualApi.anchorSeen(ge.gob.id);
                // 114.1: ...and the copy itself, released after the settle below rather than here -- the
                // event has not fired yet, and a copy let go before it is a copy that can be drawn first.
                // Per COPY, which is why the queue entry is what carries it: settleGob fires ONE client-wide
                // event into the first session, so a later session's copy gets no event of its own and this
                // walk is the only place that ever sees it.
                if(ge.added && ge.gob.addonpend) {
                    if(held == null)
                        held = new ArrayList<Gob>();
                    held.add(ge.gob);
                }
                if(touched == null)
                    touched = new ArrayList<Long>();
                touched.add(Long.valueOf(ge.gob.id));
            }
        }
        int gen = gobRescans;
        boolean rescan = (gen != gobRescansSeen);
        gobRescansSeen = gen;
        if(touched != null) {
            for(int i = 0, n = touched.size(); i < n; i++)
                settleGob(touched.get(i).longValue());   // a repeated id is idempotent: only an edge fires
        }
        if(rescan) {
            // A session ended. Whatever it alone could see left its last session at that moment, and the
            // caches are the only place that says so — so the held set is re-asked whole. Collected first and
            // reported afterwards, because a handler runs between the two.
            List<Long> gone = new ArrayList<Long>();
            for(Long id : heldGobs) {
                if(!gobHeld(id.longValue()))
                    gone.add(id);
            }
            heldGobs.removeAll(gone);
            for(int i = 0, n = gone.size(); i < n; i++)
                gobLeft(gone.get(i).longValue());
            // 114.1: ...and a copy that session was holding is a copy nothing will ever announce -- its queue
            // went with its state. The ones no live session can see any more are exactly the stranded ones:
            // an object still in somebody's cache is still on its way to a drain that will release it.
            for(Gob g : gobHolds.keySet()) {
                if(!gobHeld(g.id)) {
                    if(held == null)
                        held = new ArrayList<Gob>();
                    held.add(g);
                }
            }
        }
        // 114.1: every copy this drain announced, let into the tree, and every parked add woken to re-ask.
        releaseHolds(held);
    }

    /** One id, settled against the caches: {@code GobAdded} into the first session, {@code GobRemoved} out of
     *  the last, and nothing at all for the sessions in between. */
    private static void settleGob(long id) {
        Long key = Long.valueOf(id);
        if(gobHeld(id)) {
            if(heldGobs.add(key))
                fireGob("GobAdded", id);
        } else if(heldGobs.remove(key)) {
            gobLeft(id);
        }
    }

    /** The object left its last session: forget the game's overlays that hung on it, then report it gone. */
    private static void gobLeft(long id) {
        heldNative.remove(Long.valueOf(id));   // the client drops a departing gob whole, decorations and all
        lastSdt.remove(Long.valueOf(id));      // 113.3: ...and what it last reported for the object's state
        GobIntent.forget(id);                  // 092.7: ...and what was ASKED for at it goes with the object
        fireGob("GobRemoved", id);
    }

    /** <b>Does any live session hold {@code id}?</b> Asked of the object caches, never counted. */
    private static boolean gobHeld(long id) {
        for(Sessions.Member m : Sessions.members()) {
            if(holds(m, id))
                return true;
        }
        return false;
    }

    /**
     * <b>Is this event the client's edge for one of the game's own overlays?</b> True exactly when the key
     * crossed into its first session or out of its last, and the set is updated as it answers.
     *
     * <p>The per-session union gate in {@link #nativeOverlayEvent} still stands in front of this and answers a
     * different question: a second overlay of one resource on one gob is not a second key. This one is about
     * one key seen by several characters.
     */
    private static boolean nativeEdge(OverlayEvent oe) {
        Long id = Long.valueOf(oe.gobId);
        Set<String> keys = heldNative.get(id);
        boolean was = (keys != null) && keys.contains(oe.key);
        boolean now = nativeHeld(oe.gobId, oe.key);
        if(now == was)
            return false;
        if(now) {
            if(keys == null) {
                keys = new LinkedHashSet<String>();
                heldNative.put(id, keys);
            }
            keys.add(oe.key);
        } else {
            keys.remove(oe.key);
            if(keys.isEmpty())
                heldNative.remove(id);
        }
        return true;
    }

    /** Does any live session's copy of that gob carry one of the game's overlays under that resource name? */
    private static boolean nativeHeld(long gobId, String key) {
        for(Sessions.Member m : Sessions.members()) {
            Gob g = getgob(m.user, gobId);
            if((g != null) && (LuaGobOverlay.countNative(g, key) > 0))
                return true;
        }
        return false;
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
     * #tick(UI)} drains and dispatches on the UI thread, exactly like the gob and overlay queues
     * beside it in {@link SessionState} (038.3) — and, since 073.1, into the state of the tree the widget
     * was actually in ({@code w.ui}), which on these threads is not the tree on screen.
     */
    public static void onWidgetRemoved(Widget w) {
        SessionState st = queueState(w.ui);   // 073.1: the tree the widget was in, and no other
        // 044.8: mark a standing panel's content as GONE here, at the tap, rather than when the queue below is
        // drained. A widget announces its removal before it unlinks (a Window says so as its fade starts, which
        // is the path the server's own destroy takes), so between this line and the drain there is a window in
        // which the widget is dying and still looks perfectly ordinary — and anything that ends its entity in
        // that window (a :remove because the replacement window has already appeared, a :reload, a disable)
        // would put a dead widget back on the flat UI, where nothing owns it and no teardown collects it.
        // Marking here makes "it is on its way out" true for every door at once. Flag-only, so it is safe on
        // whatever thread reached remove().
        VirtualApi.markContentGone(w);
        if(st != null)
            st.removedWidgets.add(w);
    }

    /**
     * The <b>disposal seam</b> — the core edit at the end of {@code Widget.rdispose()} (061.7), and the half of
     * a destroy the removal seam above cannot see. {@code Widget.destroy()} is {@code remove()} on the widget
     * itself plus {@code rdispose()}, which recurses {@code dispose()} <b>only</b>: everything below the widget
     * being destroyed stays linked to its parent and never runs {@code remove()}, so a control an addon built
     * into one of the client's windows would leave the tree with its {@code widget:on("Removed", fn)} silent —
     * while every read on it correctly goes stale, since {@code hasparent(ui.root)} is false the moment the
     * widget above it unlinks.
     *
     * <p><b>Only a widget an addon owns is reported</b>, and the tap is otherwise nothing. A native widget's
     * descendants keep the client's own semantics — a window closing does not mint a removal for each of the
     * hundred widgets inside it — and the one thing this closes is the gap an addon can see from Lua, which is
     * a widget it built and holds a subscription on.
     *
     * <p><b>{@code parent != null} is what keeps it from firing twice.</b> {@code remove()} nulls the parent
     * link, so the widget {@code destroy()} was called on has already been reported by the time its own
     * {@code dispose()} runs; every descendant still carries its parent and is reported here, exactly once.
     * Like {@link #onWidgetRemoved} it may run on either thread and only enqueues.
     *
     * <p><b>The retirement is ahead of the guard, and the guard keeps the event half exactly as it was</b>
     * (128.1). {@code parent} is already null for the widget {@code destroy()} was called on, and a native
     * descendant is not {@code Owned}, so the two clauses below are precisely what an addon's {@code
     * widget:on(key, fn)} bookkeeping must NOT skip: a window that closes has to end the subscriptions on
     * itself and on every widget under it, whoever built them. Queued here and retired on the step
     * ({@link #drainDisposedWidgets}); {@code UiApi.prune}'s tree-death sweep stays as the backstop.
     *
     * <p><b>{@code Widget.remove()} is deliberately not this seam.</b> It is a death notice rather than a
     * detach — a re-home is {@code remove(); other.add(w)} — so retiring there would unsubscribe a widget that
     * is alive one line later. {@code rdispose()} does not run on a re-home, which is what makes it the only
     * safe place for this.
     */
    public static void onWidgetDisposed(Widget w) {
        SessionState dst = queueState(w.ui);   // 073.1: the tree the widget was in, and no other
        if(dst != null)
            dst.disposedWidgets.add(w);
        if((w.parent == null) || !(w instanceof Owned))
            return;
        onWidgetRemoved(w);
    }

    // ------------------------------------------------------------- caption changes (049.3)

    /**
     * The <b>caption seam</b> — the core edit at the end of {@code Window.chcap} (049.3), and the one place a
     * window's caption changes after construction. Both paths reach it: the server's {@code "cap"} uimsg
     * ({@code Window.uimsg}) and an addon's own {@code widget:title("…")}.
     *
     * <p><b>Why it moved here from the uimsg tap</b> (042.9's {@code CharApi.dispatchUimsg}, which set two flags
     * and forgot which window it was about). {@code [title=]} is a selector attribute, so with 049's descendant
     * combinator a caption landing on a window can start — or stop — a match on that window <b>and on every
     * widget below it</b> ({@code window[title=Cupboard] label}). Both consumers that cache an answer need the
     * <i>widget</i> to know what to re-ask about, and only this seam carries it. It also catches an addon's own
     * title write, which the uimsg tap never saw.
     *
     * <p><b>Must not touch Lua, and must read no widget state.</b> {@code chcap} runs on whatever thread applied
     * the message — a Loader thread, <b>outside</b> {@code synchronized(ui)} (UI.java:730-732) — or on the UI
     * thread for an addon's write. So each consumer only records the window here; the tree walks and any Lua run
     * on the tick, on the UI thread (P5), from {@link UiApi#drainSelectorCaptionCheck} and
     * {@link Sheet#drainCaptionInvalidation}.
     */
    public static void onCaptionChanged(Widget w) {
        UiApi.markCaptionChanged(w);      // 030.2/049.3: an appear subscription's chain may now resolve below w
        Layout.markCaptionChanged(w);     // 036.2: ...and so may a late layout rule's [title=] refiner
        Sheet.markCaptionChanged(w);      // 049.3: ...and w's subtree's cached styles are answers to a stale question
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
            u = screen();
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
    /**
     * addon: (105) the cursor an addon has forced with {@code hafen.ui():mouse():cursor(name)}, or
     * {@code null} when none is — the seam {@link haven.UI#getcurs} reads ahead of every widget's own.
     * Public because the engine calls it; the state and the rules are {@link UiApi#forcedCursor}'s.
     */
    public static Object forcedCursor() {
        return UiApi.forcedCursor();
    }

    public static boolean surfaceQuery(Widget.PointerEvent ev, Coord c) {
        try {
            return SurfaceInput.query(ev, c);
        } catch(RuntimeException e) {
            log("surface query error: " + e);
            return false;
        }
    }

    /**
     * <b>Where a standing widget's tree continues upward</b> (044.8) — the facade behind the {@code // addon:}
     * lines in {@code haven.Widget.getparent}, and {@code null} for every widget that is not a surface, which
     * is all of them but a handful.
     *
     * <p>Standing re-homes a widget into a {@link WidgetSurface} hanging off {@code ui.root}, so an upward walk
     * out of a standing panel reaches the root without ever passing the {@link GameUI} it came from. Nearly
     * thirty places in {@code haven} ask for exactly that — {@code Inventory}'s shift-wheel transfer reads
     * {@code getparent(GameUI.class).maininv} with no guard at all and threw, while {@code GItem.contparent}
     * and {@code Equipory.drawslots} guard and quietly do the lesser thing — so the walk has to cross, or
     * "if it works on screen, it works in the world" is false for every one of them at once.
     *
     * <p><b>It crosses to the record, not to the {@code GameUI}</b>, which is the same rule the rest of this
     * feature restores under: where the widget <i>was</i>. So the answer while it stands is the answer it gave
     * a moment before it stood and the one it gives again when it is put back, and a widget that stood from
     * somewhere else gets that somewhere else rather than a guess. Null when the record is gone or has left the
     * live tree, and the caller then walks on exactly as it did before — a surface with nothing recorded is
     * still an honest child of the root.
     */
    public static Widget standingFrom(Widget w) {
        if(!(w instanceof WidgetSurface))
            return null;
        LuaWidgetEntity e = ((WidgetSurface)w).ent;
        Widget p = (e == null) ? null : e.prevParent;
        UI u = w.ui;                     // 078.4: the surface's own tree — the record is where the widget WAS
        if((p == null) || (p == w) || (u == null) || (u.root == null) || !p.hasparent(u.root))
            return null;
        return p;
    }

    /**
     * <b>Drop something onto the panel under the pointer</b> (044.6) — the facade behind the {@code // addon:}
     * lines in {@code haven.ItemDrag} and {@code haven.DropTarget}. A drop is dispatched from the dragged
     * thing's own parent rather than from {@code ui.root}, so it reaches neither a standing panel's surface nor
     * {@code MapView}'s intercept; this asks the corner map first, in the panel's own pixels.
     *
     * <p>{@code true} means a widget in the panel <b>accepted</b> it and the caller must stop — a drop that
     * lands on a panel nothing takes goes on falling through exactly as it does on screen. Never throws into
     * the click path.
     */
    public static boolean surfaceDrop(Widget.PointerEvent ev, Coord c) {
        try {
            return SurfaceInput.drop(ev, c);
        } catch(RuntimeException e) {
            log("surface drop error: " + e);
            return false;
        }
    }

    /**
     * Deliver the widget removals captured since the last tick, one frame's worth (D-106) — the same bound as
     * {@link #drainOverlayEvents}, for the same reason: a torn-down parent whose own removal triggers more
     * removals must not spin this tick forever.
     *
     * <p><b>Second consumer since 042.7</b>: {@link UiApi#dispatchWidgetSubsRemoved}, for {@code widget:on(
     * "Removed"/"ItemAdded"/"ItemRemoved", fn)} — same drain, same thread, so firing those here is exactly as
     * safe as the tree-adapter dispatch above. <b>Third since 042.8</b>: {@link UiApi#dispatchReplacedRemoved},
     * for the {@code widget:replace(view)} substitution's own death test — the server destroying a window an
     * addon replaced is a removal like any other. <b>Fifth since 044.6</b>:
     * {@link VirtualApi#dispatchStandingRemoved}, for a widget standing in the 3D world — the same removal, one
     * subsystem along, and the two meet where a replaced stand-in is also a standing panel. <b>Since 128.4</b>:
     * {@link Gesture#dispatchRemoved}, for the drag and resize bindings that named the widget — the half of a
     * departure this drain announces, and {@link #drainDisposedWidgets} the half only a death reaches.
     */
    private static void drainRemovedWidgets(SessionState st) {
        for(int n = st.removedWidgets.size(); n > 0; n--) {
            Widget w = st.removedWidgets.poll();
            if(w == null)
                break;
            CharApi.dispatchRemoved(st, w);
            UiApi.dispatchWidgetSubsRemoved(st, w);
            UiApi.dispatchReplacedRemoved(w);
            VirtualApi.dispatchStandingRemoved(w);        // addon: 044.6 — a widget standing in the 3D world whose
                                                          //   content was destroyed (the server closing a container,
                                                          //   a replaced stand-in dying with its substitution) ends
                                                          //   its entity and frees its surface
            UiApi.dispatchSelectorRemoved(st, w);         // addon: 042.9 — widget removal → fire selector disappear
            Layout.dispatchRemoved(st, w);                // addon: 042.10 — drop its layout record, its pending
                                                           // late-caption entry, and (if it was an anchor target)
                                                           // any now-unused drag listener
            Gesture.dispatchRemoved(w);                   // addon: 128.4 — ...and every drag/resize binding that
                                                           //   named it, as the target or as the grip pressed
            if(w instanceof GItem)                        // addon: 104 — and an item takes item:on() with it
                dropItemSubs((GItem)w);
        }
    }

    /**
     * <b>One frame's deaths, announced and then retired, in that order</b> (128.1) — the pair every step calls
     * where it used to call {@link #drainRemovedWidgets} alone.
     *
     * <p>The count is taken <b>before</b> the announcements, and that is the whole point of the method: a
     * {@code widget:on("Removed", fn)} handler may destroy another widget, which lands in both queues while
     * this frame's removal batch is already fixed — so retiring everything present after the dispatch would
     * drop that widget's subscriptions a frame before its own {@code Removed} is dispatched, and eat it.
     * Everything queued before the announcements has already been announced by the time they return.
     */
    private static void drainWidgetDeaths(SessionState st) {
        int retiring = st.disposedWidgets.size();
        drainRemovedWidgets(st);
        drainDisposedWidgets(st, retiring);
    }

    /**
     * <b>A widget died, so every {@code widget:on(key, fn)} on it does</b> (128.1) — the drain of the disposal
     * seam's queue, and the retirement {@link Addon#widgetSubs} never had for the ordinary case: a window, an
     * inventory or a control leaving with the session still logged in used to keep its {@link WidgetSubs}, and
     * every engine listener and watch-list registration in it, until the next {@code :reload}.
     *
     * <p>{@link Addon#dropWidgetSubs} is the whole job — it removes the entry and tears it down, releasing
     * every listener and marking each handler dead so a {@code Sub} kept in Lua still finds nothing to end —
     * and it <b>fires nothing</b>, which is what keeps a closing window from minting an announcement per widget
     * inside it. Every addon {@link #profOwners} lists is offered the widget, the same set {@code
     * UiApi.pruneDeadTrees} walks, and the owner list is built once per drain rather than once per widget:
     * almost every disposed widget in the client is one nobody subscribed on, and that miss must cost one map
     * lookup per addon and nothing else.
     *
     * <p><b>Second consumer since 128.2</b>: {@link UiApi#retireSelectorMatches}, for what a selector
     * subscription matched. Same drain, same rule — <b>the removal drain announces, the disposal drain
     * retires</b> — so it drops the widget from every {@code matched} set and from the bounded re-check
     * <i>without</i> firing {@code "Removed"}, which the dispatch above it has already fired for every widget
     * that is properly removed. It is addressed at the tree rather than at an addon, so it takes the whole
     * frame's deaths at once and the tree's monitor with them.
     *
     * <p><b>Third since 128.3</b>: {@link Layout#dispatchDisposed}, for the widget's layout record — the same
     * call {@link #drainRemovedWidgets} makes, plus the half only a death may do. A widget that is merely
     * removed can be re-homed one line later, so what ANCHORS to it, and the drag listener installed on it,
     * survive a removal and are retired only here.
     *
     * <p><b>Fourth since 128.4</b>: {@link Gesture#dispatchRemoved}, for what {@code widget:draggable(h)}
     * armed — the one subsystem here that had no departure entry point at all, so a {@link Gesture.Bind}
     * holding a target and a grip <b>strongly</b> outlived both of them for the rest of the session. The same
     * call the removal drain makes, and for the same reason {@code Layout}'s is on both: a widget that dies as
     * a descendant reaches this drain and no other.
     */
    private static void drainDisposedWidgets(SessionState st, int n) {
        if(n <= 0)
            return;
        List<Addon> owners = profOwners();
        // 128.2: the batch, for the retirement that is addressed at the TREE rather than at an addon —
        // UiApi.retireSelectorMatches takes this tree's monitor once for the frame's deaths instead of once per
        // widget, and is built only when something is actually watching with a selector.
        List<Widget> dead = st.selectorWatches.isEmpty() && st.selectorPending.isEmpty()
            ? null : new ArrayList<Widget>(n);
        for(; n > 0; n--) {
            Widget w = st.disposedWidgets.poll();
            if(w == null)
                break;
            for(int i = 0, m = owners.size(); i < m; i++)
                owners.get(i).dropWidgetSubs(w);
            Layout.dispatchDisposed(st, w);                  // addon: 128.3 — ...and its layout record, plus the
                                                             //   anchors and the drag listener that named IT
            Gesture.dispatchRemoved(w);                      // addon: 128.4 — ...and its gesture bindings, which
                                                             //   a widget dying as a DESCENDANT reaches here only
            if(dead != null)
                dead.add(w);
        }
        if((dead != null) && !dead.isEmpty())
            UiApi.retireSelectorMatches(st, dead);           // addon: 128.2 — ...and what a selector matched
    }

    /**
     * An item is gone: end every addon's {@code item:on("Changed", fn)} on it. <b>Not housekeeping</b> — a
     * handler closes over the item it watches, so the record reaches the item it is keyed by and a weak map
     * cannot collect the pair on its own; see {@link Addon#dropItemSubs}. The same discipline
     * {@link WidgetSubs} keeps for a watched widget, at the same seam.
     */
    private static void dropItemSubs(GItem it) {
        for(Addon a : addons)
            a.dropItemSubs(it);
        Addon c = consoleOwner;
        if(c != null)
            c.dropItemSubs(it);
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
     * enqueues; {@link #tick(UI)} drains it on the UI thread, exactly like {@link #onWidgetRemoved}.
     */
    public static void onBeltSet(Widget gui, int slot) {
        SessionState st = queueState((gui == null) ? null : gui.ui);   // 073.1: whose bar the slot is on
        if(st != null)
            st.beltSetQueue.add(slot);
    }

    /**
     * Deliver the deferred belt-slot writes captured since the last tick, one frame's worth (D-106) — the
     * same bound as the other marshalled queues, for the same reason.
     */
    private static void drainBeltSet(SessionState st) {
        for(int n = st.beltSetQueue.size(); n > 0; n--) {
            Integer slot = st.beltSetQueue.poll();
            if(slot == null)
                break;
            try {
                BeltHold.writeLanded(st, slot);   // 059.5: a deferred server write lands here, one tick after
                                              //   the message — re-assert a hold taken in between, BEFORE the
                                              //   notify, so the slot the handler reads is the slot on screen
                CharApi.dispatchBeltSet(st, slot);
            } catch(RuntimeException e) {
                log("belt-set dispatch error: " + e);
            }
        }
    }

    // ------------------------------------------------------------- map markers (A1, 042.11)

    /**
     * Map marker count changed — the on-disk map DB's {@link haven.MapFile#markerseq} bumped on add/remove/
     * update (UI or processor thread) or segment merge (loader thread). Fire MarkerChanged with the new count
     * payload. This only enqueues; {@link #tick(UI)} drains it on the UI thread, same shape as the other
     * marshalled queues (D-106, to avoid deadlock with the map DB's RW lock).
     *
     * <p><b>It is handed the file that bumped</b> (073.4), which is the only thing here that names a session:
     * a {@code MapFile} is a map database some HUD holds, and the session holding that HUD claims it on its own
     * tick ({@link SessionState#mapFile}). A notify no session claims is <b>dropped</b>. Where two sessions
     * share the database (075.2 — two characters on one server), the <b>first</b> claimant queues it and the
     * rest do not: one change to one database is one {@code MarkerChanged}, not one per login.
     *
     * <p><b>Must not touch Lua.</b>
     */
    public static void onMarkersChanged(MapFile file, int count) {
        if(file == null)
            return;
        for(SessionState st : states.values()) {
            if(st.mapFile == file) {          // 075.2: one database, so one queue — the first claimant carries it
                st.markerChanges.add(count);
                return;
            }
        }
    }

    /**
     * Deliver the marker-count changes captured since this session's last tick, one frame's worth (D-106) —
     * fire MarkerChanged with each count.
     *
     * <p>The claim is refreshed first, and by the drawn session's own map read (073.4): a session learns which
     * {@code MapFile} is its own through the {@code GameUI} that holds it, so it is never told about another
     * one's. Done every tick rather than at a marker read, so an addon that only <i>subscribes</i> — and never
     * calls a map verb — still hears the server's own markers arrive.
     */
    private static void drainMarkerChanges(SessionState st) {
        MapApi.claimMapFile();
        for(int n = st.markerChanges.size(); n > 0; n--) {
            Integer count = st.markerChanges.poll();
            if(count == null)
                break;
            try {
                MapApi.fireMarkerChanged(st, count);
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
     * from server message application as well as from tick/draw), so this only enqueues; {@link #tick(UI)}
     * drains and dispatches on the UI thread, exactly like {@link #onWidgetRemoved}.
     */
    public static void onWidgetResized(Widget w) {
        SessionState st = queueState(w.ui);   // 073.1: the tree the widget is in, and no other
        if(st != null)
            st.resizedWidgets.add(w);
    }

    /**
     * Deliver the widget resizes captured since the last tick, one frame's worth (D-106) — offered to
     * {@link Layout#dispatchResized}, which re-derives whatever hangs off {@code w} (M4) and is a near-zero
     * cost ({@code derived.isEmpty()}) for a client with nothing anchored.
     */
    private static void drainResizedWidgets(SessionState st) {
        for(int n = st.resizedWidgets.size(); n > 0; n--) {
            Widget w = st.resizedWidgets.poll();
            if(w == null)
                break;
            try {
                Layout.dispatchResized(w);
            } catch(RuntimeException e) {
                log("widget-resize dispatch error: " + e);
            }
        }
    }

    // ------------------------------------------------------------- the text level's re-apply (061.6)

    /**
     * Re-apply the text levels the server painted over since the last tick, one frame's worth (D-106) — the
     * same bound and the same shape as the queues above, and the body is {@link Layout#serverWroteText}: the
     * stock caption becomes what the widget is showing, and this addon's level goes back on top of it.
     */
    private static void drainTextRewrites(SessionState st) {
        for(int n = st.textRewrites.size(); n > 0; n--) {
            Widget w = st.textRewrites.poll();
            if(w == null)
                break;
            try {
                Layout.serverWroteText(w);
            } catch(RuntimeException e) {
                log("text re-apply error: " + e);
            }
        }
    }

    // ------------------------------------------------------------- the chat's four seams (110.2, 110.3)

    /**
     * <b>A channel appeared in a character's chat</b> — the {@code // addon:} line in {@code ChatUI.add},
     * placed <i>before</i> the {@code select(chan, false)} beneath it so a brand-new tab is reported as added
     * before it is reported as picked. The server places a channel as a widget, on the thread that applies
     * its update, so this only enqueues.
     */
    public static void chatChannelAdded(ChatUI.Channel chan) {
        queueChannel("ChannelAdded", chan);
    }

    /** <b>A channel left</b> — the {@code // addon:} line in {@code ChatUI.cdestroy}. Already unlinked when
     *  this runs ({@code Widget.remove} unlinks first), so the payload reads {@code :exists() == false}. */
    public static void chatChannelRemoved(ChatUI.Channel chan) {
        queueChannel("ChannelRemoved", chan);
    }

    /** <b>The chat changed tab</b> — the {@code // addon:} line in {@code ChatUI.select(Channel, boolean)},
     *  which every door onto a selection funnels through. Only a real change is queued: naming the tab that
     *  is already up fires nothing, exactly as writing the screen to the session already drawn does. */
    public static void chatChannelSelected(ChatUI.Channel chan) {
        queueChannel("ChannelSelected", chan);
    }

    /**
     * <b>A line landed in a channel</b> — the {@code // addon:} line in {@code Channel.append}, which is the
     * one funnel every message goes through whichever of the three {@code "msg"} shapes it arrived as, and
     * which runs on the thread that applies the server's update. The line's own index is captured under the
     * lock that assigned it, so the payload addresses that line and not whatever is last by the time the
     * tick drains this.
     */
    public static void chatMessageAdded(ChatUI.Channel chan, int idx) {
        if(chan == null)
            return;
        SessionState st = queueState(chan.ui);
        if(st != null)
            st.chatEvents.add(new Object[] {"MessageAdded", chan, Integer.valueOf(idx)});
    }

    /** Enqueue one chat event against the tree the channel stands in — never {@link #screen()}, which is the
     *  session being drawn rather than the one whose chat moved. */
    private static void queueChannel(String key, ChatUI.Channel chan) {
        if(chan == null)
            return;
        SessionState st = queueState(chan.ui);
        if(st != null)
            st.chatEvents.add(new Object[] {key, chan});
    }

    /**
     * Deliver one frame's worth of {@link SessionState#chatEvents} on the UI thread (D-106), in the order the
     * seams recorded them — so a new channel is heard added before it is heard picked, a tab that goes away
     * is heard about after whatever selection preceded it, and a line is heard about after the channel it
     * landed in was heard to arrive. <b>One queue for all four</b>, precisely so that order survives.
     */
    private static void drainChatEvents(SessionState st) {
        String user = userOf(st);
        for(int n = st.chatEvents.size(); n > 0; n--) {
            Object[] e = st.chatEvents.poll();
            if(e == null)
                break;
            try {
                if(e.length > 2)                // a line carries its index; a channel event carries nothing
                    fireMessage((ChatUI.Channel)e[1], ((Integer)e[2]).intValue(), user);
                else
                    fireChannel((String)e[0], (ChatUI.Channel)e[1], user);
            } catch(RuntimeException ex) {
                log("chat event dispatch error: " + ex);
            }
        }
    }

    /**
     * Fire a chat event ({@code ChannelAdded}/{@code ChannelRemoved}/{@code ChannelSelected}) whose payload is
     * the <b>Channel object</b>, with that character's {@link LuaSession} last. The {@link #fireQuest} shape:
     * interning is per-addon (D-045), so each owner gets <i>its</i> handle, minted only for an owner that
     * actually subscribes.
     *
     * <p>On {@code ChannelRemoved} the channel is <b>already out of its tree</b>, so every read on the payload
     * answers {@code nil} and only its identity is left — which is enough, because the object is interned and
     * a handler compares it against what it indexed on {@code ChannelAdded}.
     */
    static void fireChannel(String event, ChatUI.Channel chan, String user) {
        for(Addon a : addons) {
            if(hasSub(a, event))
                fireTo(a, event, LuaChannel.of(a, chan), sessionArg(a, user));
        }
        Addon c = consoleOwner;
        if((c != null) && hasSub(c, event))
            fireTo(c, event, LuaChannel.of(c, chan), sessionArg(c, user));
    }

    /**
     * Fire {@code MessageAdded}, whose payload is the <b>Message object</b> for the line at {@code idx} of
     * {@code chan}, with that character's {@link LuaSession} last. Interned on {@code (channel, index)}, so
     * the payload is the very object {@code ch:message():get(idx + 1)} answers and a handler may key a table
     * by it. Minted only for an owner that actually subscribes: a busy Area Chat costs nothing for the
     * addons that do not listen.
     */
    static void fireMessage(ChatUI.Channel chan, int idx, String user) {
        for(Addon a : addons) {
            if(hasSub(a, "MessageAdded"))
                fireTo(a, "MessageAdded", LuaMessage.of(a, chan, idx), sessionArg(a, user));
        }
        Addon c = consoleOwner;
        if((c != null) && hasSub(c, "MessageAdded"))
            fireTo(c, "MessageAdded", LuaMessage.of(c, chan, idx), sessionArg(c, user));
    }

    // ------------------------------------------------------------- whose character it was (079.4)
    //
    // THE CHARACTER EVENTS CARRY THEIR SESSION, AND CARRY IT LAST. The meters, buffs, food, study, equipment,
    // action bar, wounds, roster, quests, radial menu and chat of five characters are five different facts, so
    // five firings are right and the label is what makes them usable: fn(payload, session).
    //
    // Last and not first, because an addon that does not care which character an event came from is not wrong.
    // Lua drops a trailing argument a function did not declare, so `function(m) … end` goes on working exactly
    // as it did and one that cares writes `function(m, s) … end`.
    //
    // The world events grow none of this: a gob is one object and there is no character it belongs to. Nor do
    // Load, Update, Disable, MarkerChanged or the three virtual click events, which are the client's or the
    // addon's own — see BUS_KEYS.

    /**
     * One owner's <b>session argument</b> — the last argument of every character event. {@code nil} for a
     * session that cannot be named, which is what a tree with no login behind it answers; a handler that took
     * the parameter reads nil there rather than a Session that answers about nobody.
     */
    private static LuaValue sessionArg(Addon owner, String user) {
        return (user == null) ? LuaValue.NIL : LuaSession.of(owner, user);
    }

    /**
     * <b>The account of the tree a HUD widget stands in</b> — the character the event that carries it is
     * about. {@code w.ui} and never {@link #screen()}: the widget is the whole of the address here, and the
     * session on screen need not be the one whose bar changed.
     */
    static String userOf(Widget w) {
        return (w == null) ? null : Sessions.nameof(w.ui);
    }

    /**
     * <b>The account of the session a state belongs to</b> (092.4) — what a HUD reader built with that state
     * is about. Re-read rather than held: a state is minted with its {@link UI} and a {@code UI} picks up its
     * {@code Session} afterwards, and a session that changes character keeps the tree it had.
     *
     * <p>{@code null} for the addon layer's own state, which has no session and whose HUD readers therefore
     * find nothing — the same {@code null} the login screen answers, and the same one every one of those
     * readers already had to handle.
     */
    static String userOf(SessionState st) {
        return (st == null) ? null : Sessions.nameof(st.ui);
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
    static void fireKin(String user, int[] ids) {
        for(Addon a : addons) {
            if(hasSub(a, "KinChanged"))
                fireTo(a, "KinChanged", kinPayload(a, user, ids), sessionArg(a, user));
        }
        Addon c = consoleOwner;
        if((c != null) && hasSub(c, "KinChanged"))
            fireTo(c, "KinChanged", kinPayload(c, user, ids), sessionArg(c, user));
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
    static void fireSlot(String user, int index) {
        for(Addon a : addons) {
            if(hasSub(a, "ActionbarChanged"))
                fireTo(a, "ActionbarChanged", LuaSlot.of(a, user, index), sessionArg(a, user));
        }
        Addon c = consoleOwner;
        if((c != null) && hasSub(c, "ActionbarChanged"))
            fireTo(c, "ActionbarChanged", LuaSlot.of(c, user, index), sessionArg(c, user));
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
        String user = userOf(b);
        for(Addon a : addons) {
            if(hasSub(a, event))
                fireTo(a, event, LuaBuff.of(a, b), sessionArg(a, user));
        }
        Addon c = consoleOwner;
        if((c != null) && hasSub(c, event))
            fireTo(c, event, LuaBuff.of(c, b), sessionArg(c, user));
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
        String user = userOf(m);
        for(Addon a : addons) {
            if(hasSub(a, event))
                fireTo(a, event, LuaMeter.of(a, m), sessionArg(a, user));
        }
        Addon c = consoleOwner;
        if((c != null) && hasSub(c, event))
            fireTo(c, event, LuaMeter.of(c, m), sessionArg(c, user));
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
        String user = userOf(w);
        for(Addon a : addons) {
            if(hasSub(a, "FepChanged"))
                fireTo(a, "FepChanged", LuaFood.of(a, w), sessionArg(a, user));
        }
        Addon c = consoleOwner;
        if((c != null) && hasSub(c, "FepChanged"))
            fireTo(c, "FepChanged", LuaFood.of(c, w), sessionArg(c, user));
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
    static void fireStudy(String user, java.util.List<GItem> items) {
        for(Addon a : addons) {
            if(hasSub(a, "StudyChanged"))
                fireTo(a, "StudyChanged", studyPayload(a, items), sessionArg(a, user));
        }
        Addon c = consoleOwner;
        if((c != null) && hasSub(c, "StudyChanged"))
            fireTo(c, "StudyChanged", studyPayload(c, items), sessionArg(c, user));
    }

    /**
     * Fire {@code EquipChanged} whose payload is an array of <b>Item objects</b> (039.14) — what is worn right
     * now, each item once whatever number of slots it fills. Same shape as {@link #fireStudy}: interning is
     * per-addon (D-045) and the array is minted only for an owner that actually subscribes.
     *
     * <p>Change <i>detection</i> stays in {@code CharApi}'s equipment adapter, which keeps a string rather than
     * these objects: an interned item compares by identity, so it cannot see the very change the event reports.
     */
    static void fireEquip(String user, java.util.List<GItem> items) {
        for(Addon a : addons) {
            if(hasSub(a, "EquipChanged"))
                fireTo(a, "EquipChanged", itemPayload(a, items), sessionArg(a, user));
        }
        Addon c = consoleOwner;
        if((c != null) && hasSub(c, "EquipChanged"))
            fireTo(c, "EquipChanged", itemPayload(c, items), sessionArg(c, user));
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
     * Fire a quest event ({@code QuestAdded}/{@code QuestCompleted}/{@code QuestFailed}) whose payload is
     * the <b>Quest object</b>
     * (039.13). Same shape as {@link #fireGob}: interning is per-addon (D-045), so each owner gets <i>its</i>
     * handle for the id, minted only for an owner that actually subscribes.
     *
     * <p>The object matters more here than in most events: a completion fires <i>because</i> the status
     * changed, and a snapshot would freeze the very field the handler is being told about. A stashed Quest
     * goes on reading — including through the completion that fired this.
     */
    static void fireQuest(String user, String event, int id) {
        for(Addon a : addons) {
            if(hasSub(a, event))
                fireTo(a, event, LuaQuest.of(a, user, id), sessionArg(a, user));
        }
        Addon c = consoleOwner;
        if((c != null) && hasSub(c, event))
            fireTo(c, event, LuaQuest.of(c, user, id), sessionArg(c, user));
    }

    /**
     * Fire {@code WoundChanged} whose payload is an array of <b>Wound objects</b> (039.13) — every wound the
     * character has, in the window's own tree order. Same shape as {@link #fireKin}: interning is per-addon
     * (D-045), and the array is minted only for an owner that actually subscribes.
     *
     * <p>Change <i>detection</i> stays in {@code CharApi}'s wound adapter (the per-wound snapshot diff, which
     * is what sees a severity resolve or a wound worsen); the ids arrive already diffed.
     */
    static void fireWounds(String user, int[] ids) {
        for(Addon a : addons) {
            if(hasSub(a, "WoundChanged"))
                fireTo(a, "WoundChanged", woundPayload(a, user, ids), sessionArg(a, user));
        }
        Addon c = consoleOwner;
        if((c != null) && hasSub(c, "WoundChanged"))
            fireTo(c, "WoundChanged", woundPayload(c, user, ids), sessionArg(c, user));
    }

    /** One owner's {@code WoundChanged} payload: its own interned Wound objects, in tree order. */
    private static LuaValue woundPayload(Addon owner, String user, int[] ids) {
        LuaTable t = new LuaTable();
        for(int i = 0; i < ids.length; i++)
            t.set(i + 1, LuaWound.of(owner, user, ids[i]));
        return t;
    }

    /**
     * Fire a radial-menu event (047.1) — {@code FlowerMenuAdded}, whose payload is the petal captions as an
     * array of strings in ring order, or {@code FlowerMenuRemoved}, whose payload is the label picked or
     * {@code nil}. Same {@code hasSub} shape as {@link #fireGob}: the payload is built only for an owner that
     * actually subscribes, and it is built <i>per owner</i> even though nothing here is interned — a table
     * handed to Lua is mutable, and one addon must not be able to edit another's petal list.
     */
    static void fireFlowerMenu(String user, String event, String[] petals, String label) {
        for(Addon a : addons) {
            if(hasSub(a, event))
                fireTo(a, event, flowerPayload(a, user, petals, label), sessionArg(a, user));
        }
        Addon c = consoleOwner;
        if((c != null) && hasSub(c, event))
            fireTo(c, event, flowerPayload(c, user, petals, label), sessionArg(c, user));
    }

    /** One owner's radial-menu payload: the captions on an open, the label (or nil) on a close. */
    private static LuaValue flowerPayload(Addon owner, String user, String[] petals, String label) {
        if(petals == null)
            return (label == null) ? LuaValue.NIL : LuaValue.valueOf(label);
        // 091/A-083: the PETALS, as the objects s:flowermenu() hands back one call away. It was an array of
        // caption strings, so `function(petals) petals[1]:select() end` -- what flowermenu.md teaches --
        // failed as "attempt to index a string".
        LuaTable t = new LuaTable();
        for(int i = 0; i < petals.length; i++)
            t.set(i + 1, LuaPetal.of(owner, user, i));
        return t;
    }

    /** One owner's {@code KinChanged} payload: its own interned Kin objects, in roster order. */
    private static LuaValue kinPayload(Addon owner, String user, int[] ids) {
        LuaTable t = new LuaTable();
        for(int i = 0; i < ids.length; i++)
            t.set(i + 1, LuaKin.of(owner, user, ids[i]));
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
        // 079.2: the client is on its way out and this is not the thread taking it out. THE choke point, so
        // one test closes every door into Lua at once — a handler, a timer, a draw callback, an arming tick —
        // and the shutdown reads what the addons hold without a frame writing it underneath.
        if(quiet())
            return LuaValue.NIL;
        long t0 = System.nanoTime();
        // 126.2: the instruction budget for THIS entry, on THIS thread — outside the try, so what is
        // claimed here is exactly what the finally below releases. The value is the budget it displaced:
        // an entry nested inside another (a handler that fires an event of its own) puts its caller's
        // back, and a thread's outermost entry drops its claim entirely.
        long budget = Sandbox.arm(owner.env);
        try {
            return fn.invoke((args.length == 0) ? LuaValue.NONE : LuaValue.varargsOf(args));
        } catch(LuaError e) {
            log(owner, "handler error: " + e.getMessage());
            trace(e.getCause());
        } catch(RuntimeException e) {
            log(owner, "handler error: " + e);
            trace(e);
        } catch(Throwable t) {
            // 126.1: AND AN Error. Everything above this line was already contained; a StackOverflowError or
            // an OutOfMemoryError raised under an addon's Lua was not — it left this choke point, left the
            // step, left UILoop's frame loop (which catches InterruptedException and nothing else) and ended
            // the UI thread, taking every other addon and the client with the one that failed. The addon's
            // own pcall never saw it either — LuaJ's pcall catches LuaError and Exception, and an Error is
            // neither — so there is nowhere but here for it to be caught.
            //   TWO KINDS ARE RETHROWN. ThreadDeath belongs to whoever raised it, and anything caught while
            // this thread is interrupted belongs to the QUIT — the exit path interrupts the thread held in
            // Client.mt, and a containment that swallowed that would keep the client alive past its own exit.
            if((t instanceof ThreadDeath) || Thread.currentThread().isInterrupted()) {
                if(t instanceof Error)
                    throw (Error)t;
                throw new RuntimeException(t);   // a checked throwable sneaked through: still not ours to eat
            }
            contain(owner, t);
        } finally {
            Sandbox.disarm(owner.env, budget);   // 126.2: released where it was claimed, on every path out
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

    /**
     * <b>Contain a fatal failure and quarantine the addon that raised it</b> (126.1) — {@link #callLua}'s
     * {@code Throwable} guard, and the only caller.
     *
     * <p>It runs on a client that has just failed to grow its stack or its heap, so it stays
     * <b>constant-shaped</b>: it files the addon and names the failure's class, and it builds no report of
     * any kind. The Java stack goes to stdout through the same {@link #trace} every other handler error uses
     * — for an {@link Error} that is the only evidence there is, since the message is usually null and the
     * Lua side never saw it.
     *
     * <p>The filing comes FIRST and the announcement second, each guarded: saying so allocates (a notice
     * line, a widget's text) and may fail again on an {@code OutOfMemoryError}, and a quarantine that was
     * lost because the log line could not be drawn is the failure this whole path exists to prevent.
     */
    private static void contain(Addon owner, Throwable t) {
        String kind = t.getClass().getName();
        quarantines.add(new Quarantine(owner, "fatal: " + kind));
        try {
            log(owner, "FATAL " + kind + " out of a callback - contained; this addon is quarantined until"
                + " the next load - see Options -> AddOns");
            trace(t);
        } catch(Throwable ignored) {
            /* the addon is already filed above, which is the part that must survive; stdout keeps the rest */
        }
    }

    // ------------------------------------------------------------- the hafen facade

    /** Install the stable {@code hafen.*} facade into an owner's Lua env (addons and the REPL). */
    static void installHafen(Globals g, final Addon owner) {
        LuaTable hafen = new LuaTable();

        // hafen.session() — the LOGINS this client holds, and the ADDRESS a character is named by (076.1).
        // The section object IS the collection: :list/:count/:find enumerate the membership in the order it
        // joined, :get(user) addresses one by its ACCOUNT name and always hands back an object (the name is
        // the whole of the ref, so a name out of a saved file is holdable before that account logs in and
        // after it goes), and :current() is the session on screen — nil on the login screen, and a read
        // only, since taking the screen is the player's own gesture. A Session answers :user(), :character()
        // (that session's own GameUI.chrid, not the name `:session add` asked for), :exists() and :info().
        //   076.3: and it is the ADDRESS the first two namespaces hang off — s:world() and s:player(), each
        // minted once per (addon, session) on the interned Session handle. Both are gone from `hafen`: reading
        // hafen.world or hafen.player throws out of Refusal naming the session, which is the hard cut.
        SessionApi.installSession(hafen, owner);

        // hafen.gob is GONE into s:world():gob() (039.2, D-066): a gob lives IN the world, so the by-id door
        // is the world's Gob collection, :get(id) — still never nil, and the Gob OBJECT itself
        // (gob:position()/:name()/:health()/…) is unchanged. It is interned per (addon, SESSION) now: the id is
        // the server's and names one object, the session says whose copy of it, and a read taken on the session
        // an addon named is about that character. See WorldApi and CharApi, which SessionApi mounts nothing
        // for — LuaSession's :world()/:player() are the only doors, and there is no field on `hafen` to find.

        // s:menugrid() — the ACTION MENU (the 4x4 "scm" grid) as Pagina OBJECTS, off the Session and no longer
        // off `hafen` (077.3): the catalogue of everything THAT character can do, in its grid's own order and
        // category tree. Two characters know different actions, through two grids, so a Pagina carries the
        // account beside the resource name and an entry an addon adds goes into the grid it was addressed at.
        // The section object IS the collection (039.9): :list(filter)/:count/:find for the catalogue,
        // :get(key) for one entry, and :roots() for the root screen. The key is always a STRING and splits by
        // SHAPE — a "/" makes it a resource name (the identity), anything else a display name (a search
        // convenience, not unique) — and a miss is plain nil. There is no addressing by position: the
        // catalogue grows on every discovery. Resource-backed reads are Loading-guarded, so a scan right at
        // SessionEnteredWorld may be short and fills in sub-second. The PROTECTED pag:use() ("menugrid.use")
        // keeps its one key addressed at any character, and sends through that grid's own button.

        // s:flowermenu() — the OPEN RADIAL MENU (the ring of petals a right-click puts up), off the Session
        // (077.4) and a different thing from the action menu above: that one is a catalogue the character
        // carries, this one is a menu that exists for a second. So the section IS the open menu and its
        // members are bare LABELS — :list() and :count() — because a petal set is frozen from the moment it
        // opens until it dies and there is nothing for a live object to track. Every read answers with no
        // menu up ({} and 0): none being open is the normal state, not an error. A menu is a WIDGET IN ONE
        // SESSION'S TREE rather than the gesture that raised it, so the finder walks the named session's own
        // root: a ring the player left up and tabbed away from is still open, still readable, and still
        // selectable — and every other session answers the same nothing it answers with none up. :gob()
        // names the object the ring was opened ON — a correlation the client makes from the press that
        // opened it, since the server sends no such thing, and nil wherever that correlation cannot vouch
        // for an answer; the id resolves in the tree the menu stands in. The two PROTECTED verbs,
        // :select(label|n) and :cancel(), keep the one key each has addressed at any character. The two
        // events, FlowerMenuAdded and FlowerMenuRemoved, are where an automation addon actually reacts.

        // hafen.map():* — the RECORDED map (037): the client's on-disk map database (MapFile), the map the
        // player has EXPLORED, as opposed to the live terrain above. Five collections, re-shaped in 039.4:
        //   :segment() — the contiguous explored areas, with :current() for the one you are standing in.
        //   :grid() — the recorded 100x100-tile squares, addressed by the id the SERVER published. That id
        //     is the only thing the live and recorded halves share, so this and s:world():grid() hand
        //     back the SAME Grid object: :live() asks whether it is streamed in, :exists() whether it is
        //     written down, and where both answer, grid:tile(c) and s:world():tile(p) agree by name.
        //   :marker() — the pins (the old hafen.markers). Two kinds: PLAYER markers (user pins: a name +
        //     colour) and SYSTEM markers (server/quest pins: a name + icon). :add(name, p) takes a Position
        //     and hands back a bare pin whose colour and on-map flag are setters; :remove(m) takes it out.
        //     Both writes are unprotected — they edit the user's own on-disk database. The DB streams in a beat
        //     after enter-world (empty until then); MarkerChanged fires on any change, ours or the user's.
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
        // s:ui():inventory():items() / s:ui():equipment():items() / s:player():hand():item(), and widget:items()
        // answers on ANY container — a chest, a cupboard — with its window visible and interactive. What it hands
        // back is an interned LuaItem keyed on the item WIDGET (039.14): a server widget id is recycled, so an
        // entity keyed on the number would silently start naming a different item and a protected write through it
        // would move the wrong thing. The four item verbs are ON the object (048.3) and never take a number.

        // 077.1: hafen.char, hafen.study, hafen.buff, hafen.meter, hafen.quest and hafen.wound are GONE onto
        // the Session, where LuaSession mints each per (addon, session) and hangs it on the interned handle.
        // Every one of them names ONE CHARACTER's own state, and the client holds several logins at once, so
        // the read says which: hafen.session():current():meter():list() are the bars on screen and
        // hafen.session():get(user):meter():list() are another character's. Reading `hafen.meter` at all now
        // throws from the hafen table's own __index (Refusal.hafenIndex), naming the replacement.
        //   The reads themselves did not change shape — what changed is the funnel each resolves through:
        // CharApi.charwnd(user) and AddonManager.gameui(user), the named session's own HUD, in place of the
        // drawn one. The six factories are CharApi.chr/study/buffs/meters/quests/wounds.

        // s:kin() and s:party() — the two ROSTERS (077.2), off the Session and no longer off `hafen`. Each is
        // one character's: a kin roster is that login's Kin window (GameUI.buddies, a BuddyWnd) and a party is
        // that login's Glob.party, so the buddy ids, the colours and the last-known positions are all read
        // through the character the addon named. The section object IS the roster in both cases (039.9), and
        // the entities carry the account beside their key — a buddy id counts inside one roster, and one
        // person in two of your characters' parties is two members with two positions. See LuaKin and
        // LuaPartyMember. Subscribe to KinChanged (a Kin[] payload, minted per subscribing addon by fireKin)
        // for a kin added/removed, renamed/regrouped, or flipping online/offline.
        //   The five PROTECTED kin verbs (the "kin.*" keys) drive BuddyWnd.Buddy's own methods (D-009) and are
        // now ADDRESSABLE, each keeping the one key it has: a key names the ACTION and not the target, since
        // the player could have tabbed to that character and performed it, and a second grant per session
        // would mean an addon allowed to add kin cannot add kin on an alt. The send needs no anchor either —
        // Widget.wdgmsg walks that widget's own tree to that session's own UI.
        //   :add(secret)   — kinning needs the other player's HEARTH SECRET (wdgmsg("bypwd", secret), the
        //                  Kin window's "Add kin" field); there is no add-by-NAME message.
        //   kin:endKin()   = END KINSHIP (Buddy.endkin) — ends the kinship; the kin STAYS in the list, now
        //                  merely memorized (un-kinned). The "End kinship" petal, shown while the kin is active.
        //   kin:forget()   = FORGET (Buddy.forget) — drops a memorized kin from the list entirely. The "Forget"
        //                  petal, shown once un-kinned. To fully remove an ACTIVE kin: endKin(), then forget().
        // Both send the same wdgmsg("rm", id); the SERVER advances the state (active → memorized → gone), exactly
        // as clicking the two petals in turn does. kin:rename(name)=wdgmsg("nick"), kin:group(g)=wdgmsg("grp")
        // with g validated 0..254 (the range the SERVER accepts; the client only draws 8 colours).

        // s:speed() — movement speed (A7, re-shaped in 060), off the Session (077.3) and read from THAT
        // character's speed selector (Speedget: the four-way crawl/walk/run/sprint toggle at the bottom of its
        // HUD). Both fields behind it — cur and max — are one login's, so a Speed carries the account beside
        // the wire index: sprint unlocked here says nothing about the alt. The section contains exactly one
        // thing, so the section object IS the collection of SPEEDS: :list(filter)/:count/:find enumerate the
        // ones selectable right now (crawl→sprint order, empty before the selector streams in AND when the
        // server has locked every speed), :get(key) addresses any of the four — selectable or not — by index
        // 0..3 or by whole case-insensitive display name, and :current() is the one that character is on. Each
        // member is a Speed object, interned per (addon, session): sp:index() :name() :available() :exists()
        // :info(), and s:speed():current() == sp is the "am I on this one" test rather than a second verb.
        //   :set(speed|index|name) is the PROTECTED verb ("speed.set"), keeping its one key addressed at any
        // character: it drives the client's own Speedget.set (wrap-not-reimplement, D-009 → wdgmsg("set", n)),
        // exactly what clicking/hotkeying that speed does, and it returns the collection so writes chain. It
        // refuses a speed that is not selectable, naming the ones that are — but the SERVER still has the last
        // word, and Speedget.cur only moves when its uimsg("cur") lands, so the read-back is a round trip. No
        // SpeedChanged event: speed is read on demand (the classic use is a speed-toggle keybind), like the
        // other gap surfaces.

        // s:craft() — the recipe window (A8: the Makewindow the server places under the HUD when the
        // player opens a recipe), off the Session (077.3). A section of one verb: :current() is the recipe THAT
        // character has open as a Craft object, or NIL when none is. A background session keeps its GameUI, so
        // its recipe window is open and answers — which is what makes a crafting addon across characters worth
        // writing — and the Craft is keyed on the WINDOW alone, since a widget already names the tree it
        // stands in: :exists() walks up from it rather than comparing against the recipe on screen. The Craft carries :name() (the recipe), :inputs()/:outputs() (the slots, as
        // {res, name, num, opt} values — res is the DISPLAYED resource, i.e. the constraint category when the
        // recipe accepts one, else the concrete item; num = the required/produced count, -1 = unspecified ~ 1;
        // opt = an optional ingredient / chance byproduct), :qualityInputs() and :tools() ({res, name} values),
        // :exists() and :info(). The PROTECTED :make(all) is on it too (039.13 — the button belongs to the recipe),
        // keeping its one key addressed at any character: it presses Craft (wdgmsg("make", 0)) or Craft All
        // (all=true → 1) through the WINDOW's own wdgmsg, so it CONSUMES the ingredients exactly as a click
        // does, in the session that window stands in. No CraftChanged event (read on demand, like A7 speed — a
        // recipe changes only when the player opens one).

        // s:fight() — combat schools / the maneuver deck builder (A10), off the Session (077.4) and read
        // from THAT character's own sheet's "Martial Arts & Combat Schools" tab (FightWnd, @RName("fmg"),
        // reached via CharWnd.fight —
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
        // the protected Phase-4 action tier; no FightChanged event (a school changes only on explicit player
        // action, like A4 skills / A8 craft — read on demand). Saved-school NAMES are deferred (the private
        // FightWnd.saves[] would need a haven-package accessor; usesave/nsave identify the active slot).
        // A school is configured on one character and a fight is fought by one body, so a deck index and an
        // opponent's gob id both count inside one login: the cards and the target carry the account beside
        // their key, and :target():gob() resolves in that session's own object cache.

        // s:actionbar() — the action bar / hotbar (the engine calls it the "belt": GameUI.belt, a
        // BeltSlot[144]), off the Session (077.3) and via the widget-tree mechanism (1d-4). A slot index names
        // ONE character's bar — slot 11 on two characters is two different buttons — so a Slot carries the
        // account beside the index. The section object IS the collection (039.9): s:actionbar():get(n) is the
        // Slot at the RAW 0-based game index 0..143 (out of range throws), s:actionbar():list() the 1-based
        // array of all 144 (the iteration view — same interned objects, and slot:index() is the game index).
        // Reads on the object, live per call: :res()/:name()/:cooldown() (0..1, a pagina action's meter —
        // ability slots only, NOT seconds)/:empty()/:info() (the old flat snapshot). Subscribe to
        // ActionbarChanged{slot} (fired when a slot's content changes — a set/clear/drag or its data
        // resolving, event-driven off the belt uimsg/notify, never per frame; the payload is that Slot).
        // slot:use([mods]) is the PROTECTED write verb (4g, "actionbar.use"), keeping its one key addressed at
        // any character — exactly a LEFT-click on that action-bar button (that HUD's own beltwdg act →
        // wdgmsg("belt", n, …)); mods is an optional modifier bitfield (0 default; Shift=1 Ctrl=2 Alt=4,
        // matching the keybind syntax). A ground-targeted ability then enters targeting mode (as clicking
        // the button does) — supply the target with the MapView verbs.

        // 048.7: there is NO hafen.act() to install any more. The PROTECTED tier (spec 12 / D-010 /
        // D-025 / D-027; D-028) is unchanged as a model — a verb runs only when THIS addon declared that verb's
        // own key in its manifest (else requirePermission throws a guiding error), a PER-ADDON permission with no
        // global master switch, opted into at enable time through the AddOns panel's consent dialog, and still
        // server-authoritative: an addon can only send what a player click could send. What changed is WHERE the
        // verbs live. A verb belongs with what it CHANGES, not with what it COSTS — a permission is not a
        // namespace — so 048 dissolved the one section that was grouped by its gate, verb by verb:
        //   s:player():move(p) walks and s:world():click(gob, button, mods) clicks; s:player():hand() is the
        // cursor and :use(target, mods) applies what you hold to an Item, a Position or a Gob (048.2); an item
        // answers item:use/:take/:drop/:transfer (048.3); s:world():place / :select stand beside the
        // snapPlace/snapAngle that prepare their arguments (048.4); a menu entry is
        // s:menugrid():get(name):use(), which gained this same permission (048.5); the escape hatch is
        // widget:send(msg, ...), where the receiver IS the target (048.6); and a petal is
        // s:flowermenu():select(label|n) (048.7, which also deleted act():enabled() — see actionsGranted
        // above). Same messages, same gate, on the things they act on; hafen.act and every one of its ten verb
        // names throw from Refusal naming the new home.
        // The per-subsystem protected verbs that always lived on their own subsystem (speed:current(n),
        // craft:make, slot:use, pag:use, the kin writes, flowermenu:select) share the one gate,
        // requirePermission — each asking for its own key out of the Permission catalogue.

        // hafen.ui — custom client-side UI (spec 07, Phase 2a). window(opts) = a draggable, titled window;
        // widget(opts) = a bare rectangle (no chrome). opts: size={w,h}, pos={x,y}, parent="root"|"gameui",
        // title (window only), and callbacks onDraw(g,w,h) / onTick(dt) / onClick(x,y,button) / onMouseUp /
        // onMouseMove(x,y) / onWheel(x,y,amount) / onClose (window). Returns a handle:
        //   :move(x,y)  :show()  :hide()  :visible()  :pack()  :size(w,h)  :destroy()
        // The widget is bridge-owned (P2) and torn down on reload/disable. Client-side only: it cannot
        // wdgmsg the server — that is widget:send(msg, ...) on a BOUND widget, 048.6. See AddonWidget for the
        // callback plumbing.
        UiApi.installUi(hafen, owner);

        // hafen.asset(path) — the ONE loader for the files THIS addon ships (spec 028-asset-loader). A CALLABLE
        // namespace (D-056): hafen.asset(path) is one interned, typed handle; hafen.asset() is the array of the
        // addon's live assets. Dispatch is by EXTENSION — .png/.jpg/.jpeg/.gif/.bmp = an image (draw it with
        // g:image / stand it with hafen.virtual():sprite()), .ttf/.otf = a font (hafen.font.setFont / window{font=} /
        // g:text{font=}), .glb/.gltf = a glTF mesh (hafen.virtual():object()) — and anything else errors listing them.
        // Paths are addon-relative and SANDBOXED (absolute paths and ".." escapes are rejected, D-017; the one
        // containment check lives here now). It takes a PATH AND NOTHING ELSE: loading a file is expensive and
        // happens once, configuring a use of it is cheap and happens many times, so a font's size/style is
        // h:derive{size=12} — AWT's own split, and what keeps == free of an options table. Interned per (addon,
        // resolved path), so repeating the load costs nothing and identity is stable WHILE ALIVE: a remove
        // drops the entry, so the next load of that path is a NEW object. Every asset answers :type()/:path()/
        // :dispose() on top of its own verbs. The client's four BUILT-IN fonts are engine-owned, so they are
        // addressed rather than loaded: hafen.font("sans"|"serif"|"mono"|"fraktur").
        AssetApi.install(hafen, owner);

        // hafen.virtual() — the ONE section for CLIENT-ONLY things standing in the 3D world (043; specs
        // 16-virtual-entities + 17-custom-rendering). Five collections, one per kind: :ghost() places a `.res`
        // game model, :sprite() one of the addon's own PNGs, :object() one of its glTF models, :widget() one of
        // its UI surfaces (044) and :patch() a shape lying on the ground (118) — each :add(what, p) standing it
        // at a Position and handing back a bridge-owned handle (D-030) that speaks one vocabulary
        // (:position/:rotate/:scale/:alpha/:tint/:visible/:clickable/:onClick), each :list([filter]) reading
        // THIS addon's (canonical filter: nil=all / a string matched against the visual's name / a predicate),
        // and :entity() the same collection shape over all five at once.
        // None of it is a Gob the server knows: no wdgmsg, invisible to OCache and every read API, so it grants
        // no gameplay advantage — a visualization, like a HUD overlay (SAFE-tier, NOT protected; D-029/D-034). The
        // motivating use is city/base planning: lay ghost buildings over the real terrain. Everything here is
        // torn down on reload/disable/relogin (P2). It is a SCENE section only: the addon's own files come from
        // hafen.asset (028.1).
        VirtualApi.installVirtual(hafen, owner);

        // hafen.console (the client's own :name console commands) — L1 input, L2 action, L3 message and V5 grab have all
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

        // hafen.json — parse/encode JSON (N1 / D-036). Unprotected (pure CPU), independent of the network.
        // parse(str) -> Lua value: objects -> string-keyed tables, arrays -> 1-based tables; a JSON null
        // becomes nil (an absent key in an object, a hole in an array — the standard Lua-JSON trade-off);
        // integral numbers come back as Lua ints. Malformed input, or input over the size/depth caps
        // (-Dhaven.addon.json.maxlen / .maxdepth), throws a pcall-able LuaError. encode(value) -> compact
        // JSON and is STRICT (a function/userdata/thread, a reference cycle, a table nested past the SAME
        // depth cap parse reads to, or a non-finite number throws) so the result is always valid JSON —
        // unlike the REPL echo's forgiving Json.write, which places a marker and keeps writing.
        LuaTable json = new LuaTable();
        json.set("parse", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "json", "parse");
                // The parameter is `str` on the page, so it is `str` in the refusal too.
                String s = Args.str(a, 2, "hafen.json():parse", "str", "the JSON document to read").tojstring();
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

        // hafen.http — external HTTP requests (N2a / D-037), protected by a manifest "network" host allowlist.
        HttpApi.install(hafen, owner);

        // hafen.locale() — what this client DISPLAYS (102-translation). One catalogue per addon, loaded as a
        // document (:load(doc)), installed and released like a stylesheet, and read back through the strings
        // it did NOT answer (:miss()). Unprotected: it writes client-local and its release undoes it.
        // The catalogue lands at Fonts.display, the last thing that happens to a string before it becomes a
        // raster, so the MODEL is not translated: w:text(), a petal's name and an action's name all still
        // answer the client's own English while it is installed.
        LocaleApi.install(hafen, owner);

        // hafen.event():on(key, fn) -> a Sub; sub:off() ends it. The bus is the door for a notification with
        // no object to hang off (041 R2: have you got the object? obj:on(...); no? hafen.event()), and it is
        // the same one verb every emitter answers. 097: the SUBJECT of a key is singular too -- MarkersChanged
        // was the one plural in the set, and it named the collection its handler is given rather than the
        // change that happened, which is one marker's.
        //
        // The key set is CLOSED (D-129): the client fires every one of BUS_KEYS and knows them at load, so an
        // unknown one throws rather than being accepted and never firing. The four lifecycle keys dropped their On prefix
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
                String moved = Refusal.eventKey("hafen.event()", key);
                if(moved != null)
                    throw new LuaError(moved);
                if(!busKey(key))
                    throw new LuaError(busKeyRefusal(key));
                if(key.startsWith("GobOverlay"))   // 038.3: arm the two Gob seams (see `overlaySubs`)
                    overlaySubs = true;
                if(key.equals("GobSdtChanged"))    // 113.3: arm the $cres.apply seam (see `sdtSubs`)
                    sdtSubs = true;
                if(key.equals("GobAdded"))         // 114.1: arm the render gate (see `gobAddedSubs`)
                    gobAddedSubs = true;
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
        // introduces tomorrow. Which is also why one key is RESERVED (Subs.WILD, 082.1): a list that cannot
        // be known is a list an addon cannot enumerate, so "*" is how it asks for the whole of it.
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
        // hafen.event():list(filter) / :count(filter) — what THIS addon is currently listening to, across the
        // hub's three emitters and in that order: the bus, the outbound action stream, the inbound message
        // stream (086.2). Members are the very Subs :on handed back, so a predicate reads them with sub:key()
        // and == finds the one you hold; a string filter is a substring match on that key.
        //
        // Two verbs on the hub rather than mounting hafen.event() AS a collection: it is the door for three
        // emitters, not a set of one kind, and making it a collection would have to elect one of the three to
        // be "the" members. A widget's own subscriptions stay out — they belong to the widget and die with it.
        event.set("list", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "event", "list");
                LuaValue filter = a.arg(2);
                LuaTable t = new LuaTable();
                int n = 0;
                for(LuaSub s : hubSubs(owner)) {
                    LuaValue h = s.handle();
                    if(LuaCollection.keeps(filter, h, true, s.key, "hafen.event()", "list"))
                        t.set(++n, h);
                }
                return t;
            }
        });
        event.set("count", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "event", "count");
                LuaValue filter = a.arg(2);
                int n = 0;
                for(LuaSub s : hubSubs(owner)) {
                    if(LuaCollection.keeps(filter, s.handle(), true, s.key, "hafen.event()", "count"))
                        n++;
                }
                return LuaValue.valueOf(n);
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
                return newTimer(owner, a, false);
            }
        });
        timerVerbs.set("every", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaCollection.receiver(a.arg1(), "every");
                return newTimer(owner, a, true);
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

            public String noGet() {
                return "a timer has no key: hafen.timer():after(s, fn) and hafen.timer():every(s, fn)"
                    + " hand you the timer they make, and hafen.timer():list() is every one of yours";
            }
        }, timerVerbs), null);

        // hafen.store() — saved variables (1e / D-002 / D-023). One Lua table per manifest-declared saved
        // variable, persisted to JSON under savedata/. :get(name) hands back that table — the LIVE persisted
        // one, never a copy, so hafen.store():get("cfg").foo = 1 still saves; an undeclared name throws listing
        // the declared ones, because the set is closed by the manifest at load. :flush() forces a write now.
        // Per-character vars are restored at SessionEnteredWorld (the <genus>_<char> folder is only known
        // then); account-scope vars are loaded here, before the addon's files run, ready in the file body /
        // Load. The table object for each name is STABLE for the addon's whole life (restore fills it in
        // place), so a cached reference stays valid. This is the one section whose ACCESS PATTERN changed
        // rather than its spelling, so the old field form throws from a per-owner __index built off the
        // manifest (StoreApi.index) — a static refusal table cannot know an addon's own variable names.
        StoreApi.installStore(hafen, owner);

        // The door every section name goes through: one this table answers for throws saying what to write
        // instead, and every other reads as plain nil, so a feature probe keeps working.
        Refusal.install(hafen);

        g.set("hafen", hafen);
    }

    /**
     * The three emitters {@code hafen.event()} is the door for — the bus, the outbound action stream and the
     * inbound message stream — as one list of live subscriptions, in that order (086.2). Concatenated on
     * every call rather than cached: each {@link Subs#live} is already a snapshot of a copy-on-write list,
     * and a subscription made or ended inside a handler must show up on the next read.
     */
    private static List<LuaSub> hubSubs(Addon owner) {
        List<LuaSub> out = new ArrayList<LuaSub>();
        out.addAll(owner.subs.live());
        out.addAll(owner.actionSubs.live());
        out.addAll(owner.messageSubs.live());
        return out;
    }

    private static LuaValue newTimer(final Addon owner, Varargs a, boolean repeat) {
        // Args first, and one argument at a time: "expects (seconds, function)" named neither which of the
        // two was missing nor which was the wrong kind — and an accidental nil (a config field that is not
        // there) is the commonest of the two, which is what Args.required says in the house's own words.
        String verb = "hafen.timer():" + (repeat ? "every" : "after");
        LuaValue sec = Args.num(a, 2, verb, "seconds", null);
        LuaValue fn = Args.required(a, 3, verb, "fn");
        if(!fn.isfunction())
            throw new LuaError(verb + ": fn must be a function — it is what the timer runs, got "
                + fn.typename());
        double s = sec.todouble();
        if(s < 0)
            s = 0;
        Timer t = new Timer(owner, clock + s, s, repeat, fn);
        t.handle = LuaValue.userdataOf(t, timerMeta(owner));  // set BEFORE the timer is live: list() reads it
        owner.timers.add(t);
        return t.handle;    // so hafen.timer():list() hands back the SAME handle the caller holds
    }

    /**
     * The per-addon <b>Timer metatable</b> (086.4) — the vocabulary a scheduled timer answers, shared by every
     * handle this addon is handed rather than a table of closures minted per timer, so scheduling one costs a
     * userdata and nothing else.
     *
     * <p><b>Why the handle reads at all.</b> {@code hafen.timer()} is a collection and its {@code :list}
     * {@code :count} {@code :find} take a predicate called with each handle — which, over a handle carrying
     * {@code cancel} alone, could test identity and nothing else, a question {@code ==} already answers. With
     * {@code :interval()} {@code :repeats()} {@code :due()} and {@code :alive()} the documented filter means
     * something: {@code hafen.timer():count(function(t) return t:repeats() end)}.
     *
     * <p>Userdata rather than a table for the two things a table cannot do: {@code t.cancel = nil} is refused
     * (an addon cannot break its own teardown), and a typo throws naming the vocabulary instead of reading
     * {@code nil} and failing one call later as <i>attempt to call a nil value</i>. Per addon for the reason
     * every metatable in the bridge is: no Lua value crosses a sandbox boundary (D-017).
     */
    private static LuaValue timerMeta(Addon owner) {
        if(owner.timerMeta != null)
            return owner.timerMeta;
        LuaTable m = new LuaTable();
        // cancel() — stop it. Idempotent, and legal on one that has already fired: the flag is what the fire
        // loop reads, and the list drops it on its next pass whichever path got there first.
        m.set("cancel", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Timer t = timer(self, "cancel");
                t.alive = false;
                t.owner.timers.remove(t);
                return self;              // the receiver: every ending chains
            }
        });
        // interval() — the seconds between runs, and 0 for a one-shot, which has none. The delay a :after
        // was scheduled with is over once it has run; :due() is the live half of that question.
        m.set("interval", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Timer t = timer(self, "interval");
                return LuaValue.valueOf(t.repeats ? t.secs : 0);
            }
        });
        // repeats() — :every or :after. The one property a predicate over this collection most wants.
        m.set("repeats", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(timer(self, "repeats").repeats);
            }
        });
        // due() — seconds until it next runs, 0 when it is due on this tick, and nil once it is dead, where
        // "when does it next run" has no answer.
        m.set("due", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Timer t = timer(self, "due");
                return t.alive ? LuaValue.valueOf(Math.max(0.0, t.due - clock)) : LuaValue.NIL;
            }
        });
        // alive() — still scheduled? False once cancelled, and once a one-shot has run.
        m.set("alive", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(timer(self, "alive").alive);
            }
        });
        // info() — the one snapshot escape hatch, the same four reads in a plain table.
        m.set("info", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Timer t = timer(self, "info");
                LuaTable out = new LuaTable();
                out.set("interval", LuaValue.valueOf(t.repeats ? t.secs : 0));
                out.set("repeats", LuaValue.valueOf(t.repeats));
                out.set("due", t.alive ? LuaValue.valueOf(Math.max(0.0, t.due - clock)) : LuaValue.NIL);
                out.set("alive", LuaValue.valueOf(t.alive));
                return out;
            }
        });
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, Refusal.closedIndex("timer", m,
            "a timer"));
        mt.set("__name", LuaValue.valueOf("Timer"));
        mt.set("__tostring", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(timer(self, "tostring").toString());
            }
        });
        owner.timerMeta = mt;
        return mt;
    }

    /** The receiver of a colon call on a timer handle, or the error that says a dot call passed the wrong self. */
    private static Timer timer(LuaValue self, String method) {
        Object o = self.isuserdata() ? self.touserdata() : null;
        if(!(o instanceof Timer))
            throw new LuaError("t:" + method + "() — use a COLON call on the timer hafen.timer():after(s, fn)"
                + " or :every(s, fn) handed back (t:" + method + "())");
        return (Timer)o;
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

    /**
     * Engine-level output: stdout (prefixed) and in-game notice when available.
     *
     * <p><b>The notice goes to {@link #screen()}, and deliberately not to {@link #layer()}</b> (075.1). A
     * line is written to be read <i>again</i>, and what keeps one is the chat's <i>System</i> channel:
     * {@code UI.msg} dispatches a {@code NoticeEvent} down the tree, and the handler that takes it is
     * {@code GameUI.msg}, which appends to {@code syslog} besides rendering the timed line. The layer's own
     * {@code RootWidget} is a {@code Notice.Handler} too, so a line posted there is not lost — it is drawn
     * and then gone with {@code msgtime}, with no scrollback anywhere, because only a {@code GameUI} has a
     * channel to append to. So the line goes where the player is looking, which is also the only tree that
     * remembers it; and this never grows a session argument for the same reason {@code screen()} does not:
     * there is one screen. With no session up there is nothing to post to and the stdout half above carries
     * the line alone, which is what the login screen has.
     */
    static void log(String msg) {
        System.out.println("[addon] " + msg);
        UI u = screen();
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

    /**
     * Addon-level output ({@code hafen.log():write} + handler errors): tagged with the addon id. The notice
     * half goes to {@link #screen()} for {@link #log(String)}'s reason.
     */
    static void log(Addon owner, String msg) {
        String id = ownerName(owner);
        System.out.println("[" + id + "] " + msg);
        UI u = screen();
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
            g.STDOUT = consoleStdout();             // ...whose print() lands where the console's output does
            Addon owner = new Addon(Manifest.internal("(console)"), null, g);
            installHafen(g, owner);
            consoleOwner = owner;
        }
        return consoleOwner.env;
    }

    /**
     * <b>Where {@code :lua print(...)} goes.</b> LuaJ's {@code print} writes to {@code Globals.STDOUT},
     * which is {@code System.out} out of the box — so a line printed from the console line the user typed
     * at went to the terminal alone, while the same command's <i>result</i> was already answered in game as
     * {@code lua= …}. One command, two destinations, and the half most people reach for was the invisible
     * one.
     *
     * <p><b>It tees to {@code cons.out}, not to {@link UI#msg}.</b> {@code cons.out} <i>is</i> the console's
     * output stream — the one {@code :threads} dumps to and the one {@code GameUI.added} re-points at the
     * chat's <i>System</i> channel — so a printed line lands exactly where a command's output belongs. A
     * notice would also carpet the screen: {@code print} in a loop is normal and ten timed lines over the
     * world are not. Before the HUD is up, {@code Console.clearout}'s sink swallows it and the terminal
     * copy is the whole of it, which is what a typed command's output does there too.
     *
     * <p><b>Bytes, decoded once per line.</b> LuaJ hands a {@code LuaString}'s raw bytes straight to the
     * stream, so a UTF-8 source file's accents arrive as UTF-8 and are decoded as such — going through
     * {@code PrintStream}'s own character encoder instead would re-encode them in the platform charset. The
     * line is split on {@code \n} and any {@code \r} before it is dropped, because {@code println} ends its
     * line {@code \r\n} on Windows and a stray CR in a chat line is a box drawn in the text.
     */
    private static PrintStream consoleStdout() {
        return new PrintStream(new OutputStream() {
            private final ByteArrayOutputStream line = new ByteArrayOutputStream();

            public void write(int b) {
                if(b == '\n')
                    emit();
                else
                    line.write(b);
            }

            public void flush() {
                /* NOT emit(): a flush lands mid-line (print writes each argument separately), and a line
                 * is what the System log takes. The newline is the only terminator. */
            }

            private void emit() {
                byte[] raw = line.toByteArray();
                line.reset();
                int n = raw.length;
                while((n > 0) && (raw[n - 1] == '\r'))
                    n--;
                String s = new String(raw, 0, n, StandardCharsets.UTF_8);
                System.out.println("[console] " + s);   // the terminal keeps the whole of it, unclamped
                UI u = screen();
                if(u != null) {
                    try {
                        u.cons.out.println(clampMsg(s));
                    } catch(RuntimeException e) {
                        /* pre-HUD or no console sink yet; stdout still has it */
                    }
                }
            }
        }, true);
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
        UI u = screen();   // where the answer is printed: the console the line was typed into
        System.out.println("[console] :lua " + src);               // echo the input to the terminal
        try {
            LuaValue chunk;
            try {
                chunk = console().load("return " + src, "=lua");   // expression form: show its value
            } catch(LuaError e) {
                chunk = console().load(src, "=lua");                // statement form (e.g. print(...))
            }
            // watchdog the console too (e.g. a stray `while true do end`); 126.2: paired with a release,
            // so this entry's budget is this entry's and the next line typed gets a full one of its own.
            long budget = Sandbox.arm(consoleOwner.env);
            LuaValue r;
            try {
                r = chunk.call();
            } finally {
                Sandbox.disarm(consoleOwner.env, budget);
            }
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
        // 072.3: asked of the SESSION, not of the drawn view. The old read was `view.ui.sess.glob`, and
        // `view.ui` is the session on screen — so the view was never anything but a pointer back to it, on
        // the hottest path in the layer (every gob resolution comes through here, via oc()).
        UI u = screen();
        return ((u == null) || (u.sess == null)) ? null : u.sess.glob;
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

    /**
     * <b>Any live session's {@link Glob}</b> (075.1) — the read behind {@code hafen.time()}, and the one
     * shape of world read that is not the drawn session's.
     *
     * <p>Distinct from {@link #glob()} in the question it answers, not in the object it usually hands back.
     * {@code glob()} is <b>this</b> session's world: its gobs, its map, the ground one character is standing
     * on, and two characters genuinely disagree about all of it. The clock does not — every session
     * interpolates the same server time — so a namespace that reads it has no session to be handed, and
     * picking the drawn one in particular buys nothing and costs an answer: it goes {@code null} through a
     * character switch, for a number that did not change.
     *
     * <p>{@code null} only when the client holds no session at all, which is the login screen.
     */
    static Glob anyglob() {
        return Sessions.anyglob();
    }

    /**
     * The current astronomy snapshot, or {@code null} before the first "astro" update.
     *
     * <p>Off {@link #anyglob()}, not {@link #glob()}: day, night, season and the moon are published by the
     * server to every session alike, and {@code hafen.time()} is this method's only reader.
     */
    static Astronomy astro() {
        Glob g = anyglob();
        return (g == null) ? null : g.ast;
    }

    /**
     * The in-game HUD ({@link GameUI}). Fast path: walk up from the map view. Fallback: scan down from
     * {@code ui.root} — right at {@code SessionEnteredWorld} the map view exists (it fired the event) but may
     * not be parented to {@code GameUI} yet, whereas {@code GameUI} is already a child of the root
     * (its widget message arrives before the map view's). {@code null} before the HUD is up.
     */
    static GameUI gui() {
        MapView m = screenView();
        if(m != null) {
            GameUI g = m.getparent(GameUI.class);
            if(g != null)
                return g;
        }
        UI u = screen();
        return (u == null) ? null : findGui(u.root);
    }

    /**
     * <b>One named session's HUD</b> (073.5) — {@link #gui()} for the session you say rather than for the
     * one on screen. The two answer the same {@code GameUI} whenever the session named is the drawn one, and
     * the callers here are the ones that must not settle for that: which character a session is playing
     * decides which folder its saved data is read from, and asking the screen would file one login's data
     * under another's. Cold paths only (world entry, a {@code :reload}), so the plain walk is the whole
     * implementation — {@link #gui()}'s map-view fast path is about the drawn scene and has no session form.
     */
    static GameUI gui(UI u) {
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
     * map-marker pins (MapApi), ghost/entity {@code tint} (VirtualApi), the {@code g:text} draw
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
     * A <b>colour argument</b> to a setter: the TABLE a colour is, keyed or positional. A colour <i>value</i>
     * read back from anywhere in the API is one of those, so {@code s:tint(other:tint())} is one expression and
     * the read and the write of one property genuinely take the same thing.
     *
     * <p>Loose components are not a colour here: a third spelling of one value leaves a write verb guessing
     * which of them a caller meant, and the value it hands back is then the one thing it cannot take. The one
     * exception is the draw context, where loose numbers are the language of every verb ({@code g:line},
     * {@code g:frect}), and {@code g:color} states the exception on its own page.
     */
    static java.awt.Color colorArg(Varargs a, int i, String verb) {
        LuaValue v = a.arg(i);
        java.awt.Color c = v.istable() ? luaColor(v, null) : null;
        if(c == null)
            throw new LuaError(colorRefusal(verb));
        return c;
    }

    /**
     * The one refusal a colour write raises, wherever it is parsed — {@link #colorArg}, {@link LuaMarker} and
     * {@link LuaRule} share it, so the three doors into a colour cannot come to say different things about
     * what one is.
     */
    static String colorRefusal(String verb) {
        return verb + "(color): a colour is a table — {200, 210, 220} or {r = 200, g = 210, b = 220[, a]},"
            + " 0..255 each, or a colour value read back from the API";
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

    // ------------------------------------------------------------- the picture registry (063.3)
    /**
     * <b>Every picture object the client ever shows, filed under the resource it came out of</b> — the
     * whole of {@code widget:picture()}'s knowledge, and the only place it exists.
     *
     * <p>The identity is thrown away downstream: an {@link haven.Img} keeps a {@link haven.Tex}, an
     * {@link haven.IButton} three {@link BufferedImage}s, and neither carries a back-reference to the
     * {@code .res} it was decoded from. So it is recorded <b>upstream</b>, at the single place every
     * picture in the client is minted — {@link Resource.Image}'s {@code scaled}/{@code tex}/{@code rawtex}
     * — and the read below asks the widget which object it is holding.
     *
     * <p><b>Identity, not equality</b>: {@code Tex} and {@code BufferedImage} define no {@code equals}, so
     * this map's keys compare by reference by construction. It is weak on the key, so a picture nothing
     * shows any more leaves it with the texture. One picture shared by two widgets names one resource for
     * both, which is the true answer — they are showing the same art.
     */
    private static final Map<Object, String> pictures =
        java.util.Collections.synchronizedMap(new java.util.WeakHashMap<Object, String>());

    /**
     * File a picture under the resource it came out of. Called from {@link Resource.Image} as each object
     * is minted — once per resource per kind, never on the repeat call, so nothing here sits on a draw path.
     */
    public static void onPicture(Object picture, String res) {
        if((picture == null) || (res == null))
            return;
        pictures.put(picture, res);
    }

    /** The resource name a picture object was decoded from, or {@code null} for one the client composed. */
    static String pictureName(Object picture) {
        return (picture == null) ? null : pictures.get(picture);
    }

    /**
     * The colour READ every colour property in the API hands back: the keyed {@code {r=,g=,b=,a=}} table,
     * 0..255 per component, and {@link LuaValue#NIL} for no colour at all. One output shape, so {@code .r}
     * answers on every colour this API produces and a value lifted out of one snapshot goes straight into
     * any colour write.
     */
    static LuaValue color(java.awt.Color c) {
        if(c == null)
            return LuaValue.NIL;
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
        MapView m = screenView();
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

    // ------------------------------------------------------------- one NAMED session's world (076.3)
    //
    // The ambient readers above answer for the session on SCREEN. These answer for the session you say, and
    // they are what every verb under `session:world()` / `session:player()` reads: a read taken on the
    // session an addon named has to be about that character, whichever one the player is looking at.
    //
    // The account name is the whole of the address (LuaSession wraps nothing else), so each of these starts
    // from `Sessions.byuser` and re-resolves the rest on the call. A member is off that list before anything
    // is told its session ended, so a name that answers null here has no session — which is exactly what a
    // handle held across the end must report, and why none of these caches a member.

    /** The member logged in as {@code user}, or {@code null} when the client holds no such session. */
    static Sessions.Member member(String user) {
        return Sessions.byuser(user);
    }

    /** That session's {@link Glob} — its world — or {@code null} while it has none (connecting, gone). */
    static Glob glob(String user) {
        Sessions.Member m = Sessions.byuser(user);
        if(m == null)
            return null;
        haven.Session s = m.sess;
        return (s == null) ? null : s.glob;
    }

    /** That session's live map cache, or {@code null}. */
    static MCache mcache(String user) {
        Glob g = glob(user);
        return (g == null) ? null : g.map;
    }

    /**
     * <b>That session's map view</b>, or {@code null} while it has none (connecting, not in the world, gone).
     *
     * <p>The read counterpart of {@link #sendView(String, String)}: that one refuses a session that is not on
     * screen, because a gesture with the pointer belongs to the character being drawn. This one does not,
     * because <b>what is on a character's cursor is a fact about that character</b> rather than about the
     * screen — every session has its own {@code MapView}, and the server puts a placement on the one it
     * addressed. So this reads the session it was asked about, like every other reader in this block.
     */
    static MapView view(String user) {
        Sessions.Member m = Sessions.byuser(user);
        return (m == null) ? null : Sessions.mapview(m.ui);
    }

    /**
     * <b>What that character is placing right now</b> — the client's own {@code MapView.Plob}, sitting on the
     * cursor — or {@code null} when it is placing nothing. The one door to it: a Plob is in no
     * {@link OCache}, so {@link #getgob(String, long)} cannot reach it and its {@code Gob.id} is {@code -1}
     * (every Plob's is), which is why it is read from the view and never addressed by an id.
     */
    static MapView.Plob placing(String user) {
        MapView mv = view(user);
        return (mv == null) ? null : mv.addonPlacing();
    }

    /** That session's live object cache, or {@code null}. */
    private static OCache oc(String user) {
        Glob g = glob(user);
        return (g == null) ? null : g.oc;
    }

    /**
     * The live {@link Gob} for an id <b>in that session</b>, or {@code null} — what every read addressed at a
     * named character funnels through.
     *
     * <p>A gob id is the <b>server's</b> and names the same object in every session that has loaded it, but
     * the {@link Gob} is not shared: each {@link OCache} holds its own, placed against its own session's map.
     * So the id decides <i>which object</i> and the session decides <i>whose copy of it</i>, and a read taken
     * on the session an addon named is about that character's view.
     */
    static Gob getgob(String user, long id) {
        OCache oc = oc(user);
        return (oc == null) ? null : oc.getgob(id);
    }

    // ------------------------------------------------------- one OBJECT, however many hold it (079.3)
    //
    // A LuaGob is keyed on the gob id alone, so a read on one has to say which session computes it. These
    // three are that rule, in one place:
    //
    //   gobUsers(id)  every live session that holds the object, in membership order -- gob:sessions()
    //   gobUser(id)   the ONE a bare read resolves through: the session on screen when it holds the
    //                 object, and otherwise the first that does
    //   anygob(id)    the Gob that session holds
    //
    // The screen first, because that is the rule this API already has for a value carrying no session: a
    // bare p:distance() and p:x() answer for the character on screen. With one session logged in -- the
    // whole of the client until 076 -- it resolves to that one and every read costs exactly what it did.
    //
    // Nothing is kept. The set is asked of the OCaches at the moment of the call, so a session that has
    // ended drops out of every answer with nothing having to be notified, and a count that could disagree
    // with the caches is never built.

    /**
     * <b>The accounts of every live session whose object cache holds {@code id}</b>, in membership order —
     * {@code gob:sessions()}'s answer. Empty when nobody holds it, which is what a despawned object reads.
     */
    static List<String> gobUsers(long id) {
        List<String> out = new ArrayList<String>();
        for(Sessions.Member m : Sessions.members()) {
            if(holds(m, id))
                out.add(m.user);
        }
        return out;
    }

    /**
     * <b>Every live session's copy of one object</b>, in membership order — what a visual WRITE lands on
     * (080.1). A gob id is the server's and names one object, but a {@link Gob} is per {@link OCache}: the
     * object is what an addon addresses, and the copies are what the engine paints. So a read resolves
     * through {@link #gobUser(long)} and picks one, while {@code gob:scale(k)} and an overlay attach take
     * this list and land on all of them — one object drawn the same whichever character is looking at it.
     *
     * <p>Live, like the set it is built from: the caches are asked at the moment of the call, so a session
     * that joined since the last write is in it with nothing having been notified, and one that ended is
     * simply not. <b>A session that does not hold the object is not in the list at all</b> — a state, not a
     * fault, exactly as an empty {@code gob:sessions()} is.
     */
    static List<Gob> gobCopies(long id) {
        List<Gob> out = new ArrayList<Gob>();
        for(String user : gobUsers(id)) {
            Gob g = getgob(user, id);
            if(g != null)                  // it left that cache between the two reads: skipped, not a fault
                out.add(g);
        }
        return out;
    }

    /**
     * <b>The accounts of every live session</b>, in membership order — the walk a client-wide sweep takes
     * when it has no object to ask about. {@link UiApi#teardownGobScales} and
     * {@link UiApi#teardownGobOverlays} are what it is for: an addon going away has to be undone in every
     * session it wrote in, and by then there is nothing left to say which those were.
     */
    static List<String> users() {
        List<String> out = new ArrayList<String>();
        for(Sessions.Member m : Sessions.members())
            out.add(m.user);
        return out;
    }

    /**
     * <b>The session a bare Gob read resolves through</b> — the one on screen when it holds the object,
     * otherwise the first that does, {@code null} when none does. The drawn session is tried without
     * copying the membership list, so the ordinary read is one lookup and one {@code OCache} probe.
     */
    static String gobUser(long id) {
        String drawn = drawnUser();
        if((drawn != null) && (getgob(drawn, id) != null))
            return drawn;
        for(Sessions.Member m : Sessions.members()) {
            if(holds(m, id))
                return m.user;
        }
        return null;
    }

    /**
     * <b>The session a read across TWO gobs resolves through</b> — the one on screen when it holds both,
     * otherwise the first that does, {@code null} when no single character can see them together.
     * {@code gob:distance(other)}'s door: {@code Gob.rc} is one session's frame, so a pair measured across two
     * of them measures nothing, and the pair has to be resolved before it is subtracted.
     */
    static String gobUser(long a, long b) {
        String drawn = drawnUser();
        if((drawn != null) && (getgob(drawn, a) != null) && (getgob(drawn, b) != null))
            return drawn;
        for(Sessions.Member m : Sessions.members()) {
            if(holds(m, a) && holds(m, b))
                return m.user;
        }
        return null;
    }

    /** The live {@link Gob} for an id, in whichever session {@link #gobUser(long)} names; {@code null} for none. */
    static Gob anygob(long id) {
        String user = gobUser(id);
        return (user == null) ? null : getgob(user, id);
    }

    /** Does this member's object cache hold {@code id}? A session being taken down answers no, not a throw. */
    private static boolean holds(Sessions.Member m, long id) {
        haven.Session s = m.sess;
        Glob gl = (s == null) ? null : s.glob;
        if(gl == null)
            return false;
        try {
            return gl.oc.getgob(id) != null;
        } catch(RuntimeException e) {
            return false;
        }
    }

    /** A copy of that session's gob list (taken under its OCache lock; snapshots built by the caller). */
    static List<Gob> allGobs(String user) {
        List<Gob> out = new ArrayList<Gob>();
        OCache oc = oc(user);
        if(oc == null)
            return out;
        synchronized(oc) {
            for(Gob g : oc)
                out.add(g);
        }
        return out;
    }

    /**
     * That session's HUD, or {@code null} while it has none (connecting, on the character list, gone). Off the
     * member's own cache, which keeps its {@link GameUI} and re-checks it, so this is a field read on the
     * paths that ask it every frame.
     */
    static GameUI gameui(String user) {
        Sessions.Member m = Sessions.byuser(user);
        return (m == null) ? null : m.gameui();
    }

    /**
     * <b>That session's whole UI</b>, or {@code null} while the client holds no such session — the tree its
     * widgets stand in, root included, which is what a walk for a widget the server placed anywhere at all
     * has to start from ({@link GameUI} is only the HUD subtree, and a radial menu is not under it).
     *
     * <p>Never {@link #screen()}: that answers the session on screen, and the whole point of addressing one is
     * that the two part company. A member between trees answers {@code null}, exactly as {@link
     * #gameui(String)} does, so every caller guards.
     */
    static UI sessionui(String user) {
        Sessions.Member m = Sessions.byuser(user);
        return (m == null) ? null : m.ui;
    }

    /**
     * That character's own gob id, or {@code -1} before its HUD is up.
     *
     * <p><b>{@link GameUI#plid} and not {@code MapView.plgob}</b>: both carry the id the server published for
     * the character, and the HUD's is reachable without walking a widget tree for the view — and is there a
     * beat earlier, since the {@code GameUI} widget arrives before the map view is parented to it.
     */
    static long plgob(String user) {
        GameUI g = gameui(user);
        return (g == null) ? -1 : g.plid;
    }

    /** That character's own {@link Gob}, or {@code null} before its HUD is up or while it is streaming in. */
    static Gob playerGob(String user) {
        long id = plgob(user);
        return (id < 0) ? null : getgob(user, id);
    }

    /** That character's live world position, or {@code null} before its gob is up. */
    static Coord2d playerPos(String user) {
        Gob g = playerGob(user);
        if(g == null)
            return null;
        synchronized(g) {
            return g.rc;
        }
    }

    /** Is that session the one on screen? {@code false} for a session the client no longer holds. */
    static boolean drawn(String user) {
        Sessions.Member m = Sessions.byuser(user);
        return (m != null) && (m == Sessions.anchormember());
    }

    /** The account on screen, or {@code null} on the login screen — what an ambient derivation resolves in. */
    static String drawnUser() {
        Sessions.Member m = Sessions.anchormember();
        return (m == null) ? null : m.user;
    }

    /**
     * <b>Send a walk to that session</b>, drawn or not (076.5) — {@code session:player():move(p)}'s door, and
     * the whole reason it is not {@link #sendView}: an order is the one thing a character nobody is looking at
     * takes, so it is the one send that does not refuse a background session.
     *
     * <p><b>The send differs by one word, and the difference is observable.</b> {@code Sessions.ordermember}
     * puts the drawn session's click through {@link UI#wdgmsg}, so an addon's own action hooks see an order to
     * the character on screen exactly as they see a real click; a background session's goes through
     * {@link UI#rawWdgmsg}, because that chain belongs to the anchor and knows nothing about the character
     * being walked — a handler intercepting it would be rewriting a destination read in one session's frame
     * with a Position resolved in another's. {@code Sessions.send} already draws that line for the client's
     * own orders, and this is the same line rather than a second one.
     *
     * <p>{@code rc} is in <b>that session's own</b> frame: {@link LuaPosition#worldArg} resolved the Position
     * through that session's {@code MCache}, so there is nothing to translate here.
     */
    static void order(String user, Coord2d rc, String verb) {
        Sessions.Member m = Sessions.byuser(user);
        if(m == null)
            throw new LuaError(verb + ": that session is gone (s:exists() is false). Nothing was sent.");
        if(!Sessions.ordermember(m, rc, 0))
            throw new LuaError(verb + ": no map view (that character is not in the world yet)."
                + " Nothing was sent.");
    }

    /**
     * <b>The map view a SEND aims at</b>, or a refusal naming why this session has none.
     *
     * <p><b>A walk is the whole of what a character you are not looking at takes</b>, and that line is the
     * client's own rather than this API's: {@code Sessions.send} builds the four arguments of a ground click
     * and no fifth, so an order carries a destination and never a target. Everything else a click can mean —
     * clicking an object, placing what is on the cursor, an area select, applying a held item — is the drawn
     * character's own business, and it also travels through the hook chain that belongs to the anchor. So a
     * send addressed to a background session is refused here, at the door, naming the session on screen; the
     * walk does not come through here at all, and {@link #order} is where it goes instead.
     */
    static MapView sendView(String user, String verb) {
        Sessions.Member m = Sessions.byuser(user);
        if(m == null)
            throw new LuaError(verb + ": that session is gone (s:exists() is false). Nothing was sent.");
        if(m != Sessions.anchormember())
            throw new LuaError(verb + ": that character is not on screen. Walking is the whole of what a"
                + " character you are not looking at takes — hafen.session():current() is the one you can"
                + " click, place and select with. Nothing was sent.");
        MapView mv = screenView();
        if(mv == null)
            throw new LuaError(verb + ": no map view (not in the world yet)");
        return mv;
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
     *
     * <p>The Gob a predicate is handed is the object itself (079.3), interned on the id alone, so it is the
     * very handle the {@code :list()} around it is building an array of — the filter and the array cannot
     * disagree about what they are talking about.
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
            if(sp == null)
                return null;
            // addon: (102.6) what the character was made to SAY, not the bubble's raster: a catalogue naming
            // that line changes the bubble, and this verb goes on answering the client's own English.
            String src = sp.source();
            if(src != null)
                return src;
            return (sp.text == null) ? null : sp.text.text;
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

    /**
     * {@code gob:sdt()} — the state bytes the server sent with the gob's resource, as a 1-based array
     * of {@code 0..255} numbers: the honest read, since what the bytes mean belongs to that resource's
     * own published code and never to this client. {@code nil} once the gob is gone or its body is
     * composed rather than resource-drawn ({@link AddonWidgets#gobSdt} states which); the empty array
     * for a resource-drawn gob the server sent none for — the two are not the same answer.
     */
    static LuaValue gobSdt(Gob g) {
        if(g == null)
            return LuaValue.NIL;
        byte[] b = AddonWidgets.gobSdt(g);
        return (b == null) ? LuaValue.NIL : sdtTable(b);
    }

    /** {@code 0..255} bytes as a 1-based Lua array — the one conversion the live read above and
     *  {@link #fireGobSdt}'s event payload share. A fresh table every call: a handler must never be able
     *  to scribble on another owner's copy of one firing, or on what a later live read hands back. */
    private static LuaTable sdtTable(byte[] b) {
        LuaTable t = new LuaTable();
        for(int i = 0; i < b.length; i++)
            t.set(i + 1, LuaValue.valueOf(b[i] & 0xff));
        return t;
    }

    /**
     * {@code gob:hitbox()} — the collision footprint the object's resource carries, as an array of
     * polygons, each an array of {@link LuaPosition}s, rotated by the object's facing and anchored at
     * its place. The rotation is the same arithmetic {@link Gob.BasePlace#getz} already does to place
     * the object on the terrain: each {@code Obstacle.p} point turned by {@code Gob.a} about the
     * origin, then offset by {@code Gob.rc}. {@code nil} once the gob is gone, its resource has not
     * resolved, or its resource carries no {@code obst} layer or an empty one.
     */
    static LuaValue gobHitbox(Addon owner, long id) {
        String user = gobUser(id);
        if(user == null)
            return LuaValue.NIL;
        return hitboxOf(owner, user, getgob(user, id));
    }

    /**
     * <b>Any gob's footprint as Positions in {@code user}'s world</b> — the whole of {@code gob:hitbox()}
     * below the address, and what {@code s:world():placing()} reads too ({@code placing:hitbox()}).
     *
     * <p>It is factored out because the two differ only in <i>how the gob is found</i>: one is an id in that
     * session's {@link OCache}, the other is the client's own {@code MapView.Plob}, which is in no OCache at
     * all. What follows the lookup — the {@code obst}/{@code neg} rings, the object's own facing, the point it
     * stands on — is the same object in both cases, because a Plob <b>is</b> a {@link Gob}. {@code NIL} for a
     * {@code null} gob, so a caller with nothing to read passes it straight through.
     */
    static LuaValue hitboxOf(Addon owner, String user, Gob g) {
        if(g == null)
            return LuaValue.NIL;
        Coord2d[][] rings;
        Coord2d rc;
        double ra;
        synchronized(g) {
            Drawable d = g.getattr(Drawable.class);
            if(d == null)
                return LuaValue.NIL;
            Resource res;
            try {
                res = d.getres();          // may throw Loading, or be null before it resolves
            } catch(RuntimeException e) {
                return LuaValue.NIL;
            }
            if(res == null)
                return LuaValue.NIL;
            rings = hitboxRings(res);
            if(rings == null)
                rings = sdtRings(res, g);      // ...and the one the SERVER sent, for a resource built that way
            if(rings == null)
                return LuaValue.NIL;
            rc = g.rc;
            ra = g.a;
        }
        if(rc == null)
            return LuaValue.NIL;
        double s = Math.sin(ra), c = Math.cos(ra);
        LuaTable polys = new LuaTable();
        for(int i = 0; i < rings.length; i++) {
            Coord2d[] ring = rings[i];
            LuaTable poly = new LuaTable();
            for(int o = 0; o < ring.length; o++) {
                Coord2d p = ring[o];
                Coord2d wp = Coord2d.of((p.x * c) - (p.y * s), (p.y * c) + (p.x * s)).add(rc);
                poly.set(o + 1, LuaPosition.of(owner, user, wp));
            }
            polys.set(i + 1, poly);
        }
        return polys;
    }

    /**
     * {@code gob:hitbox()}'s polygons, model-local and unrotated: every {@code obst} ring the resource
     * carries bar its {@code build} box, plus a rectangle per {@code neg} layer from that layer's
     * click-box corners ({@code Resource.Neg.ac}/{@code .bc}).
     *
     * <p><b>The two are different facts in the same units,</b> and this verb does not distinguish them --
     * see {@code docs/addons/api/gob.md}. {@code obst} is what the server collides against; a resource
     * may carry none and still carry a {@code neg} box, {@code gfx/terobjs/log} being the case in point:
     * nothing stops you walking through a felled trunk, and it plainly lies somewhere all the same.
     * Both are world units -- {@code Obstacle} ends each point with {@code .mul(MCache.tilesz)} and a
     * {@code neg} corner is already on that grid, a tile being 11 units precisely because the old 2D
     * client drew one as 11 pixels.
     *
     * <p>{@code null} where the resource carries neither, which is what a decoration nothing walks into
     * and nothing marks the ground under -- a sign, a cursor's 0x0 box -- looks like.
     */
    private static Coord2d[][] hitboxRings(Resource res) {
        res = shaperes(res);
        List<Coord2d[]> rings = new ArrayList<Coord2d[]>();
        // EVERY obst layer, not the one at id "". A resource may carry several under ids of its own and
        // the shape is the union of them -- a gate's leaf and its post, a building's wings. Only "build"
        // is left out, and it is not a footprint at all: it is the box the placement ghost checks for
        // clearance before you may put one down, and it is larger than the thing that ends up there.
        for(Resource.Obstacle obst : res.layers(Resource.obst)) {
            if("build".equals(obst.id))
                continue;
            for(Coord2d[] ring : obst.p) {
                if(ring.length >= 3)
                    rings.add(ring);
            }
        }
        // And every neg box, which is a DIFFERENT fact in the same units -- see the doc comment.
        for(Resource.Neg neg : res.layers(Resource.negc)) {
            if((neg.ac == null) || (neg.bc == null) || neg.ac.equals(neg.bc))
                continue;
            Coord a = neg.ac, b = neg.bc;
            rings.add(new Coord2d[] {
                Coord2d.of(a.x, a.y), Coord2d.of(b.x, a.y), Coord2d.of(b.x, b.y), Coord2d.of(a.x, b.y)
            });
        }
        return rings.isEmpty() ? null : rings.toArray(new Coord2d[0][]);
    }

    /** The published library whose parser reads an obstacle out of a gob's state bytes. */
    private static final String OBST_LIB = "lib/obst";

    /**
     * <b>The obstacle the SERVER sent in a gob's state bytes</b>, in {@link #hitboxRings}' own shape, or
     * {@code null} where there is none to read.
     *
     * <p><b>A construction site has no footprint of its own.</b> {@code gfx/terobjs/consobj} carries neither
     * an {@code obst} nor a {@code neg} layer — the stakes and the string you see are drawn by the
     * resource's <i>own published code</i>, out of a shape the server sends per object, because the shape is
     * whatever building is going up there and is not a property of the site resource at all. So the reader
     * for it cannot be the resource's layers, and is this.
     *
     * <p><b>It runs the resource's own parser rather than decoding the bytes.</b>
     * {@code docs/addons/api/gob.md} says the client never decodes state bytes, because what they mean
     * belongs to that resource's published code — and this does not break that rule, it obeys it: the parse
     * is {@code lib/obst}'s own {@link Obstacle#parse}, adopted verbatim under {@code @FromResource} and
     * pinned to the version served. A server-side bump makes the engine prefer the fetched code and warn.
     *
     * <p><b>The gate is the resource's own declaration.</b> A resource whose {@code codeentry} lists
     * {@code lib/obst} on its classpath is one whose code speaks that format, and in every resource that
     * ships with the client the obstacle is the <b>first</b> thing its constructor reads out of the SDT.
     * That is the whole warrant for reading it from byte zero, so a parse that does not come out clean is
     * dropped rather than drawn: a shape guessed wrong is worse than no shape, and {@code null} here is
     * exactly the "nothing to draw a box from" the verb already answers.
     */
    private static Coord2d[][] sdtRings(Resource res, Gob g) {
        try {
            Resource.CodeEntry code = res.layer(Resource.CodeEntry.class);
            if((code == null) || !code.uses(OBST_LIB))
                return null;
            byte[] sdt = AddonWidgets.gobSdt(g);
            if((sdt == null) || (sdt.length == 0))
                return null;
            List<Coord2d[]> rings = new ArrayList<Coord2d[]>();
            for(Coord2d[] ring : Obstacle.parse(new MessageBuf(sdt)).p) {
                if(ring.length >= 3)
                    rings.add(ring);
            }
            return rings.isEmpty() ? null : rings.toArray(new Coord2d[0][]);
        } catch(RuntimeException e) {
            return null;                       // Loading, a short message, an unknown obstacle type
        }
    }

    /**
     * The resource the SHAPE layers live on. A gob's own resource may be a thin wrapper that render-links
     * to a shared mesh, and it is the linked mesh that carries the {@code obst}/{@code neg} the wrapper
     * has none of -- so a reader that stops at {@code Drawable.getres()} finds nothing on exactly the
     * resources built that way. Returns {@code res} itself when there is no such link, which is the
     * common case.
     */
    private static Resource shaperes(Resource res) {
        try {
            for(RenderLink.Res link : res.layers(RenderLink.Res.class)) {
                if(link.l instanceof RenderLink.MeshMat) {
                    Resource mesh = ((RenderLink.MeshMat)link.l).mesh.get();
                    if(mesh != null)
                        return mesh;
                }
            }
        } catch(RuntimeException e) {
            /* Loading, or a link to a resource that will not resolve: the wrapper is the honest answer */
        }
        return res;
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
                LuaValue sdt = gobSdt(g);
                if(!sdt.isnil())
                    t.set("sdt", sdt);
                // 114.3: whether the client draws this object at all. Always present, like `moving`: an
                // object nobody hid answers true, and the two are different facts rather than one absence.
                t.set("visible", LuaValue.valueOf(!g.addoninvis));
            }
        } catch(RuntimeException e) {
            /* partial snapshot is fine (e.g. world data still resolving) */
        }
        return t;
    }

    // ------------------------------------------------------------- owned-resource records

    /*
     * There is no event-subscription record here, beside the Timer below (041.1): a subscription is an entry
     * in the emitter's own Subs and the LuaSub handle Lua holds is that entry, so there is no second object to
     * keep in step — and no `Sub` sitting one character away from `Subs` in the same package for a later
     * reader to confuse.
     */

    /** A live timer: {@code due} is engine-clock seconds, {@code secs} is what was asked for. */
    public static final class Timer {
        final Addon owner;
        double due;
        /** The seconds asked for: a one-shot's delay, a repeating timer's period. */
        final double secs;
        /**
         * Whether it reschedules after each run — {@code :every} rather than {@code :after} (086.4). Its own
         * field rather than {@code secs > 0}, because {@code :every(0, fn)} is a repeating timer with a zero
         * period: it is due again the moment it has run, which is once a tick, and that is what
         * {@code timer.md} has always said a repeating timer does.
         */
        final boolean repeats;
        final LuaValue fn;
        boolean alive = true;
        /** Set when a one-shot has run, so {@code tostring} tells a fired timer from a cancelled one. */
        boolean fired;
        /** The Lua handle this timer was handed out as, so {@code hafen.timer():list()} answers by identity. */
        LuaValue handle;

        Timer(Addon owner, double due, double secs, boolean repeats, LuaValue fn) {
            this.owner = owner;
            this.due = due;
            this.secs = secs;
            this.repeats = repeats;
            this.fn = fn;
        }

        /** {@code tostring(t)}: {@code Timer(every 5s)}, {@code Timer(after 2s, fired)}. */
        public String toString() {
            return "Timer(" + (repeats ? "every " : "after ") + fmtSecs(secs) + "s"
                + (alive ? "" : (fired ? ", fired" : ", cancelled")) + ")";
        }
    }

    /** Seconds as a person writes them: {@code 5}, {@code 0.25}. */
    private static String fmtSecs(double s) {
        return (s == Math.rint(s)) ? Long.toString((long)s) : Double.toString(s);
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
     * One addon's declared options, for the <b>AddOns</b> tab of the settings window (spec
     * {@code 115-the-addon-declares-its-options}). Immutable; built by {@link #describeOptions()}. Its
     * {@link #addon} is the row the tab's list draws and the heading of the page behind it, and {@link #id}
     * is what that selection is remembered by across a re-read — the group object itself is minted fresh
     * every time, so nothing may key on its identity.
     */
    public static final class OptionGroup {
        public final String id;                  // the manifest id — the row's stable key
        public final String addon;               // the addon's display name — what the list draws
        public final List<OptionEntry> options;  // its rows, in declaration order
        OptionGroup(String id, String addon, List<OptionEntry> options) {
            this.id = id;
            this.addon = addon;
            this.options = options;
        }
    }

    /**
     * One option row: the live {@link LuaOption} the panel draws a control from, reads every frame and
     * writes through. It is the row itself rather than a copy of what it said, because a control that
     * caches a value is a second place for it to be wrong.
     */
    public static final class OptionEntry {
        public final LuaOption option;
        OptionEntry(LuaOption option) { this.option = option; }
    }

    /**
     * The declared addon options grouped by owning addon, for the AddOns tab of the settings window. Only
     * addons with at least one <b>live</b> declared option appear — the same WoW-style rule
     * {@link #describeKeyBinds()} follows, and for the same reason: a row for an addon with nothing to
     * configure is a page the user opens once. Order is {@link #addons}, which is the order they were
     * loaded in; within an addon, declaration order.
     *
     * <p>Read on the UI thread, when the tab is shown. A disabled or unloaded addon holds no live option, so
     * it does not appear — its stored values stay in the client's preference store, exactly as a re-mapped
     * keybinding does.
     */
    /**
     * <b>How many declarations there have been</b> — bumped by every {@code :add()} that takes a name. The
     * AddOns tab watches it beside {@link AddonRegistry#reloadGen()}, which is what makes its list what the
     * addons have declared <i>now</i> rather than what they had declared when the window was last opened: an
     * addon may declare a row at any point in its life, and one that speaks from a console command or a
     * timer would otherwise be missing from a list built before it spoke.
     */
    private static volatile int optionsGen;

    /** {@link #optionsGen} — read by the AddOns tab, once a frame. */
    public static int optionsGen() {
        return optionsGen;
    }

    /** A row was declared. Called from {@code AddonOptions.Builder.add()}, the one place a row is taken. */
    static void optionDeclared() {
        optionsGen++;
    }

    public static List<OptionGroup> describeOptions() {
        List<OptionGroup> out = new ArrayList<OptionGroup>();
        for(Addon a : addons) {
            List<OptionEntry> rows = new ArrayList<OptionEntry>();
            synchronized(a.addonOptions) {
                for(LuaOption o : a.addonOptions.values())
                    rows.add(new OptionEntry(o));
            }
            if(!rows.isEmpty())
                out.add(new OptionGroup(a.manifest.id, a.manifest.name, rows));
        }
        return out;
    }
    /**
     * A HUD overlay ({@code hafen.ui():overlay():add(key)}): a draw fn painted on top of the HUD each frame
     * (2b). Built <b>bare</b> — {@code fn} is installed by {@code :draw(fn)} and is {@code volatile} because
     * the paint pass reads it while Lua writes it. A bare overlay paints nothing, which is the same
     * "incomplete draws nothing" rule the widget builder gets from not yet being in the tree.
     *
     * <p>The {@code key} is the addon's own name for it, and the record's <b>place in
     * {@link Addon#hudOverlays} is the draw order</b> — the very list {@link UiApi#paintHudOverlays} walks,
     * so the census {@code hafen.ui():overlay():list()} gives and the order things are painted in are one
     * fact. {@code lua} caches the handle {@link LuaHudOverlay} hands out, which is what makes two lookups of
     * one painter the same value.
     */
    public static final class HudOverlay {
        final Addon owner;
        /** This addon's own name for the painter — the key it is addressed by, unique within the list. */
        final String key;
        volatile LuaValue fn;
        volatile boolean active = true;
        /** The interned Lua handle, minted on the first hand-out ({@link LuaHudOverlay#of}). */
        volatile LuaValue lua;

        HudOverlay(Addon owner, String key) {
            this.owner = owner;
            this.key = key;
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

    /** A gob state-byte change captured off-thread, awaiting UI-thread dispatch — 113.3. {@code sdt} is
     *  the delta's OWN bytes, not a live re-read, so a later delta in the same drain cannot overwrite
     *  the one this event is about. */
    private static final class SdtEvent {
        final long gobId;
        final byte[] sdt;

        SdtEvent(long gobId, byte[] sdt) {
            this.gobId = gobId;
            this.sdt = sdt;
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
