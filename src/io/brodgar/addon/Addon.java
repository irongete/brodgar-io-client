package io.brodgar.addon;

import haven.Waitable;
import haven.BAttrWnd;
import haven.Buff;
import haven.GItem;
import haven.IMeter;
import haven.ItemInfo;
import haven.Widget;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One loaded addon: its {@link Manifest}, folder, Lua environment, and load status. Per-addon
 * environments give each addon its own globals (sandbox hardening arrives in a later phase).
 *
 * <p>The {@link #subs} bus and the {@link #timers} list are the addon's <b>owned-resource registry</b>
 * (principle P2): everything it creates through the facade is tracked here so the engine can tear
 * it down cleanly on reload/disable. Both are copy-on-write inside, because a running handler may
 * unsubscribe or cancel while the engine iterates them.
 */
public final class Addon {
    public final Manifest manifest;
    public final Path dir;
    public final Globals env;
    public String error;   // null if the addon loaded cleanly

    /**
     * <b>Has this addon been told {@code Load}?</b> — and so, is there a {@code Disable} owed to it (audit2
     * B16, lc-05). The moments table presents the two as a PAIR, and a teardown fired the second half for
     * things that never got the first: an addon whose file body threw is torn down without ever having
     * loaded, and {@link AddonManager#consoleOwner} is an {@link Addon} that is torn down on every reload
     * and never loads at all. Set where {@code Load} is fired and read where {@code Disable} is, which is
     * one place each; UI thread, like the whole of the load and the teardown.
     */
    public boolean loaded;

    /**
     * <b>The hosts the USER approved for this addon</b> — read out of the consent record at load
     * ({@code AddonRegistry.loadAll}), never off the manifest. The manifest is the <i>request</i>; this is the
     * <i>answer</i>, and it is what the network gate asks ({@link #hostGranted}).
     *
     * <p>The two differ exactly when the addon's {@code network} block has grown since the user last said yes,
     * which is the case the record exists for: a request to a host they never saw is refused at the call, so
     * the refusal does not depend on the re-prompt having fired first.
     *
     * <p>Empty for an addon that declared no network, and empty for the engine-internal owner, which passes
     * through no dialog and is exempted by {@link Manifest#anyHost()} instead.
     */
    public final List<String> grantedHosts;

    /**
     * <b>The catalogue keys the USER approved for this addon</b> (audit2 B08) — read out of the consent
     * record at load ({@code AddonRegistry.loadAll}), never off the manifest, exactly as
     * {@link #grantedHosts} is. The manifest is the <i>request</i>; this is the <i>answer</i>, and it is what
     * the key gate asks ({@link #keyGranted}).
     *
     * <p>The two differ exactly when the addon's {@code permissions} list has grown since the user last said
     * yes — which the enable-time scan defaults back to disabled, but which the gate must not depend on
     * having fired: a key the user never saw is refused at the call.
     *
     * <p>Empty for an addon that declared none, and empty for the engine-internal owner, which passes
     * through no dialog and is exempted by {@link Manifest#internal()} instead.
     */
    public final Set<Permission> grantedKeys;

    /**
     * <b>The one entry into this addon's Lua</b> (audit2 B06) — every call that runs a chunk of this addon's
     * code holds it, so one {@link Globals} and every {@link LuaTable} in it is entered by one thread at a
     * time. {@link AddonManager#callLua} is the only door that takes it, the addon's file bodies take it for
     * a load ({@link #run}), and {@link AddonManager#awaitIdle} takes each one for an instant to wait a
     * shutdown out.
     *
     * <p><b>Re-entrant, because Lua re-enters</b>: a handler that fires an event of its own, a control's
     * notification answering on the widget the handler just wrote, a {@code pcall} around a verb that calls
     * back. Every one of those is the same thread coming through the door again, and a plain mutex would
     * deadlock the addon against itself.
     *
     * <p><b>It sits ABOVE a tree monitor, and never under one.</b> Lua reaches a widget through
     * {@code LuaWidget.monitor}, so a thread holding this wants tree monitors; a thread that took a tree
     * monitor first and then enters Lua is the opposite order and the pair is the deadlock. There is no way
     * to forbid the second — a {@code Draw} painter runs inside {@code ui.draw}'s block, the pick completion
     * and {@code screenToWorld}'s answer inside {@code MapView}'s — so {@code callLua} does not <i>wait</i>
     * for this lock on an entry made under a tree monitor: it takes it or it drops that one call.
     * {@code threading.md} states it, and it is why the seams that <i>can</i> fire outside a tree's monitor
     * all do.
     */
    final ReentrantLock luaLock = new ReentrantLock();

    /**
     * <b>The session this addon's Lua is acting for</b> — the {@code UI} on screen's
     * {@link AddonManager.SessionState}, asked at the moment it is needed and <b>never stored</b> (074.2).
     *
     * <p>It was a field, set where {@code init} loaded the addon into one session. Since 074.2 there is no
     * such moment: an addon is loaded once for the client and runs beside every session it holds, so a field
     * naming one of them would be a second copy of "which session is drawn" — the shape {@code 071} spent
     * three tasks deleting one layer down, and the copy that disagrees is always the one nothing re-reads.
     *
     * <p>One subsystem asks, on the UI thread, and carries the answer with it: an HTTP completion lands on a
     * pool thread that holds no tree, so which session the addon was acting for has to be resolved before the
     * request is made. The screen is the only referent it can have before {@code hafen.session()} exists, and
     * 075 is where it takes an address instead.
     *
     * <p>{@code null} with no session at all — the login screen, where an addon runs and a character's
     * questions have no answer yet.
     */
    AddonManager.SessionState state() {
        return AddonManager.state(AddonManager.screen());
    }

    /**
     * This addon's subscriptions to the <b>event bus</b> ({@code hafen.event():on(key, fn)}, 041.1) — one
     * {@link Subs} for the whole bus, because a bus event is addon-wide and has no object to hang off. Every
     * key it carries charges {@link #C_EVENT}, which is what a bus handler has always cost.
     *
     * <p><b>One emitter owns one {@code Subs}</b>, and {@link LuaSub} is both the Lua handle and the entry, so
     * there is nothing to keep in step — no flat list of subscription records beside it to fall out of date.
     * Teardown drops it wholesale ({@link Subs#clear}), so an addon never unsubscribes by hand.
     */
    public final Subs subs = new Subs(this, Addon.C_EVENT);
    /** Live timers owned by this addon (see {@link AddonManager.Timer}). */
    public final List<AddonManager.Timer> timers = new CopyOnWriteArrayList<AddonManager.Timer>();
    /**
     * This addon's live {@link Resolve} registrations (spec {@code 042-event-driven-reads} M2): a value the
     * client is still loading, waited on rather than re-read every frame. Teardown ({@link
     * #teardownWaitings}) cancels every entry so a retry callback never fires into a torn-down addon layer
     * (P2) — the marshalled callback also re-checks liveness itself, since the cancel and the notify can race
     * on two threads. Copy-on-write: a firing retry may register another before this one is removed.
     */
    final List<Waitable.Waiting> waitings = new CopyOnWriteArrayList<Waitable.Waiting>();

    /** Cancel every pending {@link Resolve} registration this addon owns (teardown, P2). */
    void teardownWaitings() {
        for(Waitable.Waiting w : waitings)
            w.cancel();
        waitings.clear();
    }
    /**
     * Live custom UI widgets/windows/controls owned by this addon ({@code hafen.ui():widget()}/{@code :window()},
     * Phase 2a, and the control builders of 040). Each entry is the owned content; {@link Owned#kill()} destroys
     * its <i>root</i> (the window chrome, or the widget itself), which cascades to children — so the addon's UI
     * vanishes cleanly on reload/disable.
     *
     * <p>Typed by the {@link Owned} <b>contract</b> rather than by {@link AddonWidget} (040.1), which is what
     * lets one teardown path reach a painted surface and a {@code haven} control an addon built without a
     * second registry to keep in step.
     */
    final List<Owned> widgets = new CopyOnWriteArrayList<Owned>();
    /**
     * Live HUD overlays owned by this addon ({@code hafen.ui():overlay()}, Phase 2b): draw callbacks painted on
     * top of the HUD each frame. The engine iterates this list to paint (so clearing it stops the overlays
     * immediately) — no widget, nothing else to release, so teardown is just {@code clear()}.
     */
    public final List<AddonManager.HudOverlay> hudOverlays = new CopyOnWriteArrayList<AddonManager.HudOverlay>();
    /**
     * Live <b>widget</b> overlays owned by this addon ({@code widget:overlay()}, 103.3): what it draws over
     * one widget rather than over the screen. This list is the <b>census and the teardown</b> only — the
     * draw order is the widget's own {@code Widget.addonovs} field, which is what the paint walks, and a
     * record stands in both. So teardown has two ends: dropping this list is not enough, each record must
     * leave its widget's field too ({@link LuaWidgetOverlay#teardown}), or a disabled addon goes on
     * painting until that widget dies. A record holds its widget <b>weakly</b>, so nothing here pins a
     * destroyed subtree, and the list is swept of the dead at the one moment it grows.
     */
    public final List<LuaWidgetOverlay.Rec> widgetOverlays = new CopyOnWriteArrayList<LuaWidgetOverlay.Rec>();
    /*
     * There is NO list of gob overlays here, and that is the point of 038.1. What an addon attaches to a
     * game object (gob:overlay(key, spec)) lives on the gob itself, inside the shared LuaGobOverlay attrib,
     * partitioned per addon — so the engine ends it: a gob's attribs are disposed with the gob and dropped with
     * it from OCache, and nothing here has to be swept for a felled tree that will never come back. Teardown
     * (UiApi.teardownGobOverlays) is the one caller that must find this addon's overlays across gobs, and it is
     * a single sweep of the object cache at a rare moment.
     */
    /**
     * This addon's <b>per-widget subscriptions</b> ({@code widget:on(key, fn)}): one {@link WidgetSubs} per
     * widget this addon has subscribed on, keyed by widget identity ({@link haven.Widget} overrides neither
     * {@code equals} nor {@code hashCode}). It started (041.3) as just the four input
     * keys over the engine {@code EventHandler}s {@link haven.Widget#listen} installs — replacing the fixed
     * three-token {@code hooks} list ({@code hafen.hook():input}) — and 041.4 folds the REST of the widget
     * vocabulary onto the same record: {@code Pressed}/{@code Changed}/…'s controls fire straight into its
     * {@link Subs}, {@code Draw}/{@code Tick}/{@code Drop}/{@code Close} do the same for an owned surface, and
     * {@code ItemAdded}/{@code ItemRemoved}/{@code Removed} add the one thing none of those needed — a
     * placement/removal watch-list registration (event-driven since 042.7) — so one class is the address for
     * everything a widget can say, the same way {@link Subs} is the one mechanism under every {@code :on(key, fn)}
     * in the API. A NATIVE widget that survives {@code :reload} is what {@link #teardownWidgetSubs} walks
     * instead, releasing every listener and watch-list registration this addon installed before the Lua layer
     * that owns them is rebuilt (P2).
     *
     * <p><b>Retired explicitly, and by the widget's own death</b> (128.1). It was a {@link WeakHashMap}, which
     * collected nothing: {@link WidgetSubs} holds its own key in {@code wdg} and again in {@code inwdg}, so no
     * entry was ever weakly unreachable and every window, inventory and flower menu that closed left its
     * listeners here for the rest of the session. What retires an entry now is the disposal seam
     * ({@link AddonManager#onWidgetDisposed}, which every descendant of a destroyed widget reaches), through
     * {@link #dropWidgetSubs} on the step; {@code UiApi.prune} keeps its sweep as the BACKSTOP for a whole
     * tree dying rather than as the mechanism. Nothing is fired either way — the retirement is bookkeeping.
     *
     * <p><b>Concurrent because there are two writers.</b> The mint runs wherever {@code widget:on(key, fn)} was
     * called, which may be beside the step, and the retiring drain runs on the step.
     */
    // retired: Addon.dropWidgetSubs -- the disposal drain offers every dead widget to every addon, and the
    //   value holds the key in wdg and again in inwdg, so nothing here ever collected on its own.
    final Interned<Widget, WidgetSubs> widgetSubs = Interned.held();

    /** This addon's {@link WidgetSubs} for {@code w}, minted on the first {@code w:on(key, fn)}. */
    WidgetSubs widgetSubs(final Widget w) {
        return widgetSubs.of(w, () -> new WidgetSubs(this, w));
    }

    /**
     * This addon's {@link WidgetSubs} for {@code w}, or {@code null} — the FIRE-side lookup (041.4), which must
     * never mint one: a control's every press/tick/draw runs through this, so an unlistened widget must cost one
     * map lookup and nothing else (the {@code hasSub} gate one level up from {@link Subs#has}).
     *
     * <p>{@code null} in, {@code null} out (128.1): the map is a {@link ConcurrentHashMap} now, which throws on
     * a null key where the {@link WeakHashMap} it replaces answered {@code null} — and a fire-side caller that
     * resolves its own widget ({@code AddonWidget.rootw()}, a gesture's target) may hand one over.
     */
    WidgetSubs widgetSubsOrNull(Widget w) {
        return (w == null) ? null : widgetSubs.get(w);
    }

    /**
     * This addon's surfaces with a live {@code widget:on("Update", fn)} handler, in the order they subscribed
     * — what the engine step walks each frame (112.1, {@code AddonManager.fireSurfaceUpdates}). The fire used
     * to be the widget's own {@code tick}, which runs under its tree's monitor; from the step it holds none,
     * so a handler may write any tree.
     *
     * <p><b>A list of exactly the subscribers, never a fold over {@link #widgetSubs}.</b> That map is keyed
     * per widget so that an unlistened surface costs one lookup and mints nothing; walking all of it once a
     * frame would spend the saving on the addons that are not listening. Copy-on-write because a handler may
     * subscribe or {@code sub:off()} from inside the very walk that is firing it.
     *
     * <p>Maintained on both edges — {@link WidgetSubs#on} adds, the {@code Subs.Idle} hook removes when the
     * key's last handler goes, and {@link WidgetSubs#teardown}/{@link WidgetSubs#offerRemoved} remove where a
     * clear fires no {@code Idle} at all. The walk drops a dead or unlinked surface as a backstop, since a
     * surface with nothing but an {@code Update} handler joins no removal watch list.
     */
    final CopyOnWriteArrayList<WidgetSubs> updateSurfaces = new CopyOnWriteArrayList<WidgetSubs>();

    /** {@code widget:on("Update", fn)} on a surface that had none — join the step's walk (112.1). */
    void watchUpdate(WidgetSubs s) {
        updateSurfaces.addIfAbsent(s);
    }

    /** The last {@code Update} handler on a surface went — leave the walk (112.1). Idempotent. */
    void unwatchUpdate(WidgetSubs s) {
        updateSurfaces.remove(s);
    }

    /**
     * Drop this addon's subscriptions on <b>one</b> widget ({@code widget:revert()}, 061.9; the disposal drain,
     * 128.1) — the same release {@link #teardownWidgetSubs} does for all of them, one widget at a time: every
     * engine listener and watch-list registration goes, and each handler is marked dead so a {@code sub:off()}
     * kept in Lua still finds nothing to end.
     *
     * <p><b>Nothing is fired, and nothing here ever may be.</b> A revert is not a destroy, and since 128.1 this
     * also runs for every descendant of a destroyed widget — so an announcement added here would mint one per
     * widget inside a closing window, which is the cost the disposal seam is written to avoid.
     *
     * <p>A widget no addon subscribed on is almost all of them, and the drain offers each to every addon, so
     * this must stay one map lookup and nothing else for the miss.
     */
    void dropWidgetSubs(Widget w) {
        if(w == null)
            return;
        WidgetSubs s = widgetSubs.drop(w);
        if(s != null)
            s.teardown();       // outside the cache's lock: a teardown deafens engine listeners as it goes
    }

    /**
     * Deafen every engine listener this addon's {@link WidgetSubs} installed (teardown, P2) — and drop the
     * {@link #itemSubs} with them. Those installed nothing in the engine (the item seam asks the addon, never
     * the other way round), so dropping the map IS their teardown: what it holds is one {@link Subs} per item
     * this addon was listening to, and after a {@code :reload} the addon that was listening no longer exists.
     */
    void teardownWidgetSubs() {
        itemSubs.clear();
        AddonManager.recountItemSubs();   // audit2 B15: this addon has stopped watching items — see onItemInfo
        List<WidgetSubs> all = widgetSubs.values();
        widgetSubs.clear();
        for(WidgetSubs s : all)
            s.teardown();         // over the snapshot, and outside the lock, for dropWidgetSubs' reason
        updateSurfaces.clear();   // 112.1: each teardown() already left, so this is the backstop
    }

    /**
     * This addon's subscriptions to the <b>outbound action stream</b> ({@code hafen.event():action():on(msg,
     * fn)}, 041.2) — every player action, at the single {@link haven.UI#wdgmsg} choke point, before the server
     * sees it. A separate {@link Subs} from {@link #subs} because the key set is a different KIND: a
     * {@code wdgmsg} name is protocol, so this one is OPEN (any string is accepted) where the bus keys are
     * closed (D-129).
     *
     * <p><b>Two doors in, and {@link AddonManager#fireAction} fires them in that order.</b> A key names one
     * message; {@link Subs#WILD} names every message on the stream (082.1), which is the subscription an
     * open key set cannot be enumerated into — a message name is protocol the server can introduce, so the
     * list is unknowable by construction. The {@code hasSub} gate lets a message through for this addon when
     * EITHER holds, and both are handed one {@code ev}.
     *
     * <p>It charges {@link #C_EVENT}, not {@link #C_HOOK}: the two streams are doors of the bus now, and the
     * {@code hooks} column is what is left of {@code hafen.hook()} — hotkeys and console commands.
     *
     * <p>Teardown drops it wholesale ({@link Subs#clear}). Unlike the hook records it replaced there is
     * nothing to unregister from a global dispatch map: {@link AddonManager#dispatchAction} asks each owner's
     * own {@code Subs} whether it listens, so the state lives on the thing that owns it (D-100).
     */
    public final Subs actionSubs = new Subs(this, Addon.C_EVENT);
    /**
     * This addon's subscriptions to the <b>inbound message stream</b> ({@code hafen.event():message():on(msg,
     * fn)}, 041.2) — every server UI update, at the {@link haven.UI#uimsg} choke point, before the target
     * widget applies it. The inbound mirror of {@link #actionSubs} in every respect: open key set, the
     * {@code events} category, one {@link Subs#clear} at teardown, and no global registry to keep in step.
     *
     * <p><b>Two doors in here as well</b>, fired in the same order by {@link AddonManager#fireMessage}: a key
     * names one update, {@link Subs#WILD} names every update on the stream (082.2), and an addon holding both
     * is handed one {@code ev} for the one that matches its name. What is the inbound stream's ALONE is the
     * weight of it — a wildcard here runs Lua on every server update, on a Loader thread under the {@code ui}
     * monitor the tick and draw wait for, and a {@code preventDefault} from it swallows the client's whole
     * inbound half rather than one message.
     */
    public final Subs messageSubs = new Subs(this, Addon.C_EVENT);
    /**
     * This addon's subscriptions to the <b>pointer's pick pass</b> ({@code hafen.ui():mouse():on(
     * "PickChanged", fn)}) — the object the client's own machinery finds under the pointer, by the very pass
     * a right-click goes through. The key set is CLOSED to exactly {@code PickChanged}.
     *
     * <p><b>Holding one is what arms the pass.</b> A pick is a render pass and a GPU readback, so
     * {@link PointerPick} asks this list live and runs nothing while it is empty — which is also why there
     * is no teardown to write here: a reload throws the {@link Addon} away and the pass disarms itself.
     */
    public final Subs pickSubs = new Subs(this, Addon.C_EVENT);
    /**
     * This addon's <b>selector subscriptions</b> as subscriptions ({@code s:ui():on(sel, "Added", fn)},
     * 086.1) — the emitter that mints what that verb hands back, so it is a {@link LuaSub} like every other
     * {@code :on} in the API rather than a one-verb table. The key is the <b>event</b>, {@code "Added"} or
     * {@code "Removed"}, because that is what a person would name and the selector already lives on the
     * {@link LuaSelectorWatch} — which hangs off {@link LuaSub#tag}, and is what the {@link Subs.Ended} hook
     * releases. It charges {@link #C_WIDGET}, which is what a watch handler costs today.
     *
     * <p>The dispatch is unchanged and does not run through {@link Subs#fire}: a watch is fired from the
     * placement and removal seams against {@link #selectorWatches}, which is still the list those seams walk.
     * This emitter owns the <i>handle</i> and the <i>ending</i>, and {@link Subs#clear} is the whole teardown.
     */
    public final Subs watchSubs = new Subs(this, Addon.C_WIDGET, new Subs.Ended() {
        public void ended(LuaSub s) {
            UiApi.removeSelectorWatch(Addon.this, (LuaSelectorWatch)s.tag);
        }
    });
    /**
     * This addon's <b>console commands</b> as subscriptions ({@code hafen.console():on(name, fn)}, 086.1), keyed
     * by the command name, with the {@link LuaConsoleCommand} on {@link LuaSub#tag}. Its {@link Subs.Ended}
     * clears the live handler and nothing else: a {@link haven.Console} command is <b>register only, no
     * unregister</b>, so the one engine-lifetime dispatcher stays installed and reports "no addon handles
     * :name" (coverage-gaps C1).
     */
    public final Subs consoleSubs = new Subs(this, Addon.C_HOOK, new Subs.Ended() {
        public void ended(LuaSub s) {
            HookApi.endConsoleCommand(Addon.this, (LuaConsoleCommand)s.tag);
        }
    });
    /**
     * This addon's <b>global hotkeys</b> as subscriptions ({@code keybindings:on(name, fn)}, 086.1), keyed by
     * the addon-local binding name, with the {@link LuaKeyBind} on {@link LuaSub#tag}. Its {@link Subs.Ended}
     * drops the handler from the {@code GlobKeyEvent} dispatch list; the {@link haven.KeyBinding} registry
     * entry is process-global and persistent and is deliberately left standing, which is how the client
     * remembers a re-mapped addon key across reloads.
     */
    public final Subs keySubs = new Subs(this, Addon.C_HOOK, new Subs.Ended() {
        public void ended(LuaSub s) {
            HookApi.removeKeyBindsNamed(Addon.this, s.key);
        }
    });
    /**
     * Live global hotkeys owned by this addon ({@code keybindings:on}, Phase 2e-2): each pairs a client
     * {@link haven.KeyBinding} with a Lua handler, dispatched from {@link AddonRoot#globtype} via the engine's
     * {@code GlobKeyEvent} seam. Teardown marks each dead and drops it from {@link AddonManager}'s global
     * dispatch list (principle P2) — like an action/message hook there is no widget to deafen. The
     * {@code KeyBinding} itself is process-global + persistent and is deliberately <b>not</b> removed (that is how
     * the client remembers a re-mapped key across reloads/sessions). Copy-on-write: a firing hotkey may
     * {@code sub:off()} itself while the dispatcher iterates.
     */
    public final List<LuaKeyBind> keybinds = new CopyOnWriteArrayList<LuaKeyBind>();
    /**
     * Live selector subscriptions owned by this addon ({@code s:ui():on(sel, "Added"|"Removed", fn)}, 030.2 —
     * what replaced {@code hafen.ui.onWidgetCreate} and its descriptor): each watches the whole tree for widgets
     * matching one {@link Selector}, fired from the widget-entry seam's drain (112.3 — it was the placement
     * seam until then) and from the removal seam (event-driven since 042.9). They live in a flat global
     * dispatch list in {@link UiApi} (a subscription watches the whole tree, not one keyed target); teardown
     * ({@link UiApi#teardownSelectorWatches}) marks each dead and drops both copies <b>without firing</b> — a
     * {@code :reload}/disable is not a destroy, exactly as for a {@link WidgetSubs}'s watch-list registration.
     * Copy-on-write: a firing handler may subscribe or {@code sub:off()} itself mid-dispatch.
     */
    public final List<LuaSelectorWatch> selectorWatches = new CopyOnWriteArrayList<LuaSelectorWatch>();
    /**
     * Native widgets this addon has <b>hidden</b> with {@code widget:visible(false)} (029.2) — the restore list that
     * replaced {@code hafen.ui.adopt}, and <b>the one record a substitution lives on</b> since 031.2: a
     * {@code widget:replace(view)} joins the very entry a bare hide makes and fills in its view, so there is no
     * second bookkeeping object beside it (D-071, which is what let 032.2 delete {@code LuaModel} with the
     * function that minted it). Hiding a widget the addon does not own is the one write that reaches the client's
     * own UI, so it is bridge-owned like everything else: {@link UiApi#teardownHidden} restores each entry on
     * {@code :reload}/disable under the one rule (<i>the window ends up as the user was seeing it</i>) and destroys
     * the stand-in with it, guarded on the widget still being the same live one (so a relog — where the host
     * is the NEW session by the time the teardown runs — correctly skips it while a same-session
     * {@code :reload} performs it).
     * {@code widget:visible(true)} drops its own entry: nothing left to undo. Copy-on-write like the other owned lists.
     */
    public final List<LuaWidget.Hidden> hiddenNative = new CopyOnWriteArrayList<LuaWidget.Hidden>();
    /**
     * Native widgets this addon has <b>moved or resized</b> with {@code widget:position(x,y)}/{@code widget:size(w,h)}
     * (036.1, feature E) — the same shape as {@link #hiddenNative} one property along: <i>what it was before we
     * touched it</i>. One entry per widget, minted at the FIRST touch and carrying the stock position and the
     * stock size argument independently (an addon that only moved a window has nothing to say about its size).
     *
     * <p><b>An addon's layout is a layer over the client's, never a write into it.</b> The record is what makes
     * that true in both directions: {@link UiApi#teardownMoved} puts every widget back on {@code :reload}/disable
     * (guarded on it still being the same live one, so a relog correctly skips it), and {@link UiApi#stockPos}
     * answers {@code GameUI.savewndpos} with the coordinate the <i>user</i> last placed, so the client never
     * persists our layout as their preference. {@code widget:position(nil)}/{@code :size(nil)} drop their own half and
     * restore it there and then; an entry with neither half left is dropped. Copy-on-write like the lists above.
     */
    public final List<LuaWidget.Moved> movedNative = new CopyOnWriteArrayList<LuaWidget.Moved>();
    /**
     * Native widgets this addon has <b>taken into a surface of its own</b> with {@code widget:parent(p)} — the
     * same shape as the two lists above one property along: <i>which parent it came out of, where in it, and
     * behind which sibling</i>. One entry per widget, minted at the re-home and dropped by
     * {@code widget:parent(nil)}.
     *
     * <p><b>The sibling is in the record because the child list is a paint order.</b> {@code Widget.add} appends,
     * so a widget put back by adding alone comes back on top of everything that used to paint over it — the
     * corner minimap is the case that names it: the client {@code lower()}s it so its own frame paints over the
     * map, and a map restored by appending would hide the very frame it belongs under.
     *
     * <p>The place and the size are <b>not</b> this record's: they are {@link #movedNative}'s, which restores
     * later in the same teardown and therefore has the last word. This one answers where the widget <i>lives</i>,
     * that one where it stands. {@link UiApi#teardownRehomed} runs early — before the addon's own surfaces are
     * destroyed, or a container going down would take the client's widget with it. Copy-on-write like the lists
     * above.
     */
    public final List<LuaWidget.Rehomed> rehomedNative = new CopyOnWriteArrayList<LuaWidget.Rehomed>();
    /**
     * Widgets this addon has handed to the <b>user</b> to drag or resize ({@code widget:draggable(h)},
     * {@code widget:resizable(h)}, 062) — one entry per (target, mode), since arming a target again in the same
     * mode is a change of handle rather than a second binding, while the two modes are independent. What a
     * gesture then <i>writes</i> is the layout level above ({@link #movedNative}), so this list holds the arming
     * and nothing else: the two {@code nil} arities and {@code widget:revert()} drop an entry, and
     * {@link Gesture#teardown} drops the rest on {@code :reload}/disable — ending a gesture that is running at
     * that moment and deafening the last listener on each handle. Copy-on-write like the lists above: a
     * {@code Dragged} or {@code Resized} handler may arm or drop one.
     */
    public final List<Gesture.Bind> gestures = new CopyOnWriteArrayList<Gesture.Bind>();
    /**
     * Widgets this addon has asked to <b>survive the session</b> ({@code widget:remember(name)}, 062), by the
     * name each is remembered under — one name, one widget, which is what makes the name answerable when a
     * second widget asks for it. This is the <b>binding</b> alone: what is actually saved sits in
     * {@link #placements} and on disk, and the two are deliberately dropped by different things.
     *
     * <p><b>Dropping is not forgetting.</b> {@code widget:revert()}, {@code :reload} and disable clear the
     * binding and leave the record standing, which is the one place this layer's <i>put everything back</i>
     * instinct is wrong: what the user dragged a window to is theirs, and an addon reloading is not them
     * changing their mind. Only {@code widget:remember(nil)} deletes it.
     */
    public final Map<String, Widget> remembered = new ConcurrentHashMap<String, Widget>();
    /**
     * What is saved under each of those names, <b>by scope</b> (062, re-keyed by 092.8) — a place, a box, or
     * both, in design pixels. The key is the folder the set belongs to: a character's
     * {@code <genus>_<char>} for a widget standing in that session's own tree, and {@code "account"} for one
     * standing in the addon's layer.
     *
     * <p><b>Which is the whole of A-087's fix.</b> A single set keyed by the character on SCREEN is the wrong
     * address: a widget of a background session's own tree ({@code s:ui():find("@ChatUI")}, the case
     * {@code native.md} documents) would have where the user dragged it written into another character's
     * folder and read back out of it, silently and in both directions. Every saved variable beside it is
     * addressed by the session it belongs to, and so is this one.
     *
     * <p>A scope's set is loaded from disk the first time something in it is touched, and every loaded set is
     * written by each flush — so a remembered placement still needs no {@code saved_variables} declaration and
     * no handler of the addon's own.
     */
    public final Map<String, StoreApi.PlaceSet> placeSets =
        new ConcurrentHashMap<String, StoreApi.PlaceSet>();
    /**
     * Live addon console commands owned by this addon ({@code hafen.console():on}, gap subsystem A11): each routes
     * a console command {@code :name} to a Lua handler. Unlike the hook lists, the engine's {@link haven.Console}
     * dispatcher for a name is <b>engine-lifetime</b> and is deliberately <b>not</b> removed on teardown (coverage-
     * gaps C1: {@code Console.setscmd} has no unregister, so a single dispatcher per name routes to the current live
     * handler and is never re-registered). Teardown only marks each dead and drops it from {@link AddonManager}'s
     * {@code consoleHandlers} registry (principle P2) — after which the dispatcher reports "no addon handles :name".
     * Since 086.1 the ending runs through {@link #consoleSubs}, whose {@link Subs.Ended} hook this list is kept in
     * step by. Copy-on-write: a firing command may {@code sub:off()} itself.
     */
    public final List<LuaConsoleCommand> consoleCommands = new CopyOnWriteArrayList<LuaConsoleCommand>();
    /**
     * Live client-only world ghosts owned by this addon ({@code hafen.virtual():ghost():add}, V1): each is a virtual
     * {@link haven.Gob} (no server id) rendered in the MapView's {@code basic} scene via
     * {@link haven.MapView#addClientGob} — a SAFE-tier visualization, never sent to the server (D-029). Unlike
     * the hook lists there is <b>no</b> global dispatch/poll list: a ghost is a passive render node driven by the
     * render tree's own tick, not the addon tick loop, so it lives only here. Teardown ({@link
     * AddonManager#teardownGhosts}) destroys each — removes its scene slot + disposes the sprite — so a
     * reload/disable/relogin leaks nothing, the same guarantee as windows and overlays. Copy-on-write: a firing
     * callback may create or destroy a ghost.
     */
    public final List<LuaGhost> ghosts = new CopyOnWriteArrayList<LuaGhost>();
    /**
     * Live client-only world sprites owned by this addon ({@code hafen.virtual():sprite()}, R2): each is a custom PNG
     * (an {@link #images} texture) standing in the 3D world as a {@link haven.Gob} with no server id — the
     * non-{@code .res} sibling of a {@link #ghosts ghost}, on the same virtual-entity core (spec
     * {@code 17-custom-rendering.md} §2, SAFE-tier, D-034). Like ghosts there is no global dispatch/poll list (a
     * passive render node driven by the render tree's own tick); it lives only here. Teardown
     * ({@link VirtualApi#teardownSprites}) destroys each — removes its scene slot + disposes the quad geometry
     * (the shared {@code TexI} is freed by {@link AssetApi#teardownAssets}) — so a reload/disable/relogin
     * leaks nothing. Copy-on-write: a firing callback may create or destroy a sprite.
     */
    public final List<LuaSprite> sprites = new CopyOnWriteArrayList<LuaSprite>();
    /**
     * Live custom images owned by this addon ({@code hafen.asset("icon.png")}, R1): each is a PNG decoded from the
     * addon's own folder into a {@link haven.TexI} GPU texture — a client-only render asset that is NOT an
     * engine {@code .res} (SAFE-tier, D-034). Like the hook lists there is no global dispatch/poll list; an
     * image is a passive texture drawn on demand through the {@code g} wrapper, so it lives only here. Teardown
     * ({@link AssetApi#teardownAssets}) disposes each ({@code TexI.dispose()} frees the GL texture) so a
     * reload/disable/relogin leaks no GPU resource — the same guarantee as windows, overlays, and ghosts.
     * Copy-on-write: a firing callback may load or {@code hafen.asset():remove(a)} an image.
     */
    public final List<LuaImage> images = new CopyOnWriteArrayList<LuaImage>();
    /**
     * Live custom 3D models owned by this addon ({@code hafen.asset("chair.glb")}, R3): each is a glTF mesh
     * ({@code .glb}/{@code .gltf}) decoded from the addon's own folder into baked, H&amp;H-local geometry (a
     * {@link Gltf}) — a client-only render asset that is NOT an engine {@code .res} (SAFE-tier, D-034). Like
     * {@link #images} there is no global dispatch/poll list; a mesh is a passive geometry source that
     * {@link #objects} build engine {@code Model}s from on demand, so it lives only here. Teardown
     * ({@link AssetApi#teardownAssets}) marks each dead and drops it (frees the CPU geometry for GC; its
     * per-object GPU {@code Model}s are freed with the objects) so a reload/disable/relogin leaks nothing.
     * Copy-on-write: a firing callback may load or {@code hafen.asset():remove(a)} a model.
     */
    public final List<LuaMesh> meshes = new CopyOnWriteArrayList<LuaMesh>();
    /**
     * Live client-only world 3D objects owned by this addon ({@code hafen.virtual():object()}, R3): each is a custom
     * glTF model (a {@link #meshes} mesh) standing in the 3D world as a {@link haven.Gob} with no server id — the
     * mesh sibling of a {@link #sprites sprite} and a {@link #ghosts ghost}, on the same virtual-entity core (spec
     * {@code 18-custom-models-gltf.md}, SAFE-tier, D-034). Like ghosts/sprites there is no global dispatch/poll
     * list (a passive render node driven by the render tree's own tick); it lives only here. Teardown
     * ({@link VirtualApi#teardownObjects}) destroys each — removes its scene slot + disposes its engine
     * {@code Model}s (the shared mesh is freed by {@link AssetApi#teardownAssets}) — so a reload/disable/relogin
     * leaks nothing. Copy-on-write: a firing callback may create or destroy an object.
     */
    public final List<LuaObject> objects = new CopyOnWriteArrayList<LuaObject>();
    /**
     * Live <b>widgets this addon has stood in the 3D world</b> ({@code hafen.virtual():widget()}, 044): each is one of
     * the addon's own UI surfaces drawn into an offscreen texture and hung on a virtual {@link haven.Gob} — the
     * fourth kind on the same client-only entity core as {@link #ghosts}, {@link #sprites} and {@link #objects},
     * and unprotected for the same reason (nothing here reaches the server; only where a button is drawn changed).
     * Teardown ({@link VirtualApi#teardownSurfaces}) destroys each, which puts the widget back where it stood from
     * (D-070's rule for the borrowed case, the default parent for an owned one) and frees the surface's texture,
     * so a reload/disable/relogin leaves neither an orphaned widget nor GPU memory. Copy-on-write: a firing
     * callback may stand or end one.
     */
    public final List<LuaWidgetEntity> surfaces = new CopyOnWriteArrayList<LuaWidgetEntity>();
    /**
     * Live <b>patches this addon has laid on the ground</b> ({@code hafen.virtual():patch()}, 118): each is a convex
     * ring of Positions drawn through the engine's own ground overlay — the fifth kind on the same client-only
     * entity core as the four above, and the one that is not a {@link haven.Gob} at all. Unprotected for the
     * same reason as its siblings: nothing here reaches the server. Teardown
     * ({@link VirtualApi#teardownPatches}) destroys each, which takes its overlay back out of the {@code MCache} it
     * was registered in, so a reload/disable/relogin leaves no shape on the ground and no mesh in the grid.
     * Copy-on-write: a firing callback may lay or end one.
     */
    public final List<LuaPatch> patches = new CopyOnWriteArrayList<LuaPatch>();
    /**
     * <b>This addon's whole {@code hafen.virtual()} is switched off</b> ({@code hafen.virtual():visible(false)}, 043.4) — one
     * flag beside the three registries above, because the switch is the SECTION's state and there is exactly one
     * section per addon. It destroys nothing: every entity keeps its gob, its transform and its handle, and only
     * its scene slot goes.
     *
     * <p><b>It never overwrites what an entity was told.</b> An entity is in the scene when its own
     * {@link LuaWorldEntity#hidden} says so AND this says so, so switching the section back on restores <i>what was
     * visible</i> rather than turning everything on — one the addon had hidden with {@code <entity>:visible(false)}
     * stays hidden, and a {@code :visible(b)} written while the section is off is remembered and takes effect when
     * it comes back. Two independent booleans, neither consulted at draw time: the scene slot is added and removed
     * when one of them changes.
     *
     * <p>Volatile rather than guarded: the writes are UI-thread (the Lua verb) and the reads are the entity
     * publishes, which include a ghost's deferred create on a loader thread. Reset with the addon object itself on
     * {@code :reload}, so a reloaded addon starts visible.
     */
    public volatile boolean virtualHidden;
    /**
     * Live modal mouse-drag captures owned by this addon ({@code hafen.ui():mouse():grab()}, 041.5 — before,
     * {@code hafen.hook():grab}): each is a {@link LuaMouseGrab} widget on {@code ui.root} that forwards mouse
     * move/up to Lua over its own {@link Subs} while capturing the drag (the gizmo's drag primitive). Normally
     * transient (one per active drag) and self-releasing on mouse-up; teardown ({@link LuaGrab#teardownGrabs})
     * releases any still-active grab so a {@code :reload}/disable mid-drag drops the {@code UI.Grab} and
     * unlinks the widget, leaking nothing. Copy-on-write: releasing removes.
     */
    public final List<LuaMouseGrab> mouseGrabs = new CopyOnWriteArrayList<LuaMouseGrab>();
    /**
     * Live <b>action-menu entries</b> this addon added ({@code s:menugrid():add(id)}, 059): each is an
     * {@link AddonPagina} standing in the client's own {@code MenuGrid.paginae} set beside the entries the
     * server granted — a client-only entry that reaches no server, so it is unprotected like a HUD overlay.
     * Bridge-owned like every list here: teardown ({@link AddonPagina#teardownEntries}) takes each back out of
     * the grid it was added to and relayouts, so a {@code :reload}/disable/relogin leaves the menu holding
     * exactly the game's own catalogue. Copy-on-write: a firing handler may add or remove one.
     */
    public final List<AddonPagina> menuEntries = new CopyOnWriteArrayList<AddonPagina>();

    /**
     * Live in-flight HTTP requests owned by this addon ({@code hafen.http():get}/{@code post}, N2a): each is a
     * {@link LuaHttpRequest} submitted to {@link HttpApi}'s shared bounded pool, whose result is drained on
     * the tick and delivered to the request's callback (the gob-delta async pattern). Bridge-owned like every
     * other owned resource; teardown ({@link HttpApi#teardownRequests}) marks each dead so a
     * {@code :reload}/disable/relogin cancels any in-flight request — its pool result is discarded on drain and
     * the callback never fires (D-037 §3.3). Copy-on-write: the drain removes a completed request while a
     * firing callback may start another.
     */
    public final List<LuaHttpRequest> requests = new CopyOnWriteArrayList<LuaHttpRequest>();
    /**
     * The <b>one stylesheet</b> this addon has applied ({@code hafen.ui():sheet():install()},
     * 033-ui-stylesheet), or {@code null}. An addon owns exactly one: installing again replaces it whole and
     * {@code sheet:release()} removes it ({@link Sheet#apply}). Each of its site keys is an owner-tagged entry in
     * the {@link haven.Fonts} provider, tagged by <b>this</b> {@code Addon} instance (spec 05); teardown
     * ({@link FontApi#teardownFonts}) removes them ({@code Fonts.removeOwner(this)} bumps the generation counter →
     * routed sites revert to the stock foundry), so a reload/disable restores the stock UI. What is here is a
     * frozen snapshot of what {@link LuaSheet} says, so the field is a plain volatile reference rather than a
     * mutable collection — and, being the one truth about whether a sheet is applied, it is also what
     * {@code sheet:info().installed} derives its answer from.
     */
    volatile Sheet skin = null;
    /**
     * The <b>one catalogue</b> this addon holds ({@code hafen.locale()}, 102-translation) — what it says the
     * client <b>displays</b>. Minted with the sandbox and never replaced: the object is the owner tag the
     * {@link haven.Fonts} catalogue stack carries, so installing again re-raises this very one. Teardown
     * ({@link LocaleApi#teardown}) releases it, and the client's own English comes back.
     */
    volatile LocaleApi.Held locale = null;
    /**
     * Has this addon styled any single widget by hand ({@code widget:rule()}, 034.3)? Only a flag, not a list: the
     * styles are keyed by widget inside {@link Sheet}, whose map holds its widget keys <b>weakly</b> — a list here
     * would pin a closed window's widget tree in memory. Teardown ({@link FontApi#teardownFonts}) sweeps this
     * addon's entries out of that map along with its tree rules ({@link Sheet#forget}); this flag only tells it
     * whether the sweep is needed at all (so an addon that never styled anything costs no generation bump).
     */
    public volatile boolean skinNodes = false;
    /**
     * This addon's <b>Rule cache</b>: the interned {@code widget:rule()} handle, keyed on the <b>Widget
     * object</b> whose level it names ({@link LuaRule#ofWidget} says why the handle and not the widget).
     * Weak on both axes, so styling a window pins nothing once that window closes — the level itself lives
     * in {@link Sheet}'s weak per-widget map, and a handle is only a name for it.
     */
    final Interned<LuaWidget, LuaValue> styleRules = Interned.identity();

    /**
     * This addon's <b>Rule metatable</b> ({@link LuaRule}), shared by a sheet rule and a widget's own level
     * and built on the first of either. A plain lazy field for the reason every metatable here is (audit2
     * B06): it is only ever built from inside this addon's Lua, under {@link #luaLock}.
     */
    LuaValue ruleMeta;

    /**
     * This addon's <b>Gob interning cache</b> ({@code hafen.gob(id)}, D-045): the weak-valued
     * {@code id → Gob object} map (plus its {@link java.lang.ref.ReferenceQueue} and the per-addon metatable)
     * that makes {@code hafen.gob(id) == hafen.gob(id)} and {@code seen[gob] = true} reliable. Deliberately
     * <b>per-addon and not static</b>: no Lua value crosses a sandbox boundary (D-017), and the cache dies whole
     * with this {@link Addon} on {@code :reload}/disable — a static one would outlive the reload (the C1 trap).
     * Unlike the owned-resource lists there is nothing to tear down: the entries are weak and the handles hold
     * no engine object (see {@link LuaGob}).
     */
    final LuaGob.Cache gobs = new LuaGob.Cache(this);

    /**
     * This addon's <b>Session interning cache</b> ({@code hafen.session():get(user)}): the weak-valued
     * {@code account name → Session object} map, its {@link java.lang.ref.ReferenceQueue} and the per-addon
     * metatable. Same contract as {@link #gobs} — per-addon so no Lua value crosses a sandbox boundary
     * (D-017), and the whole cache dies with this {@link Addon} on {@code :reload}/disable with nothing to
     * tear down (weak values, and a handle holds only the name). Keyed on the <b>account</b> because that is
     * what survives a character switch, and what a handle held past the end of a session still answers with.
     */
    final LuaSession.Cache sessions = new LuaSession.Cache(this);

    /**
     * This addon's <b>Position metatable</b> ({@link LuaPosition}). A Position is a <b>value</b>, not an entity:
     * it is never interned and has no lifetime, so unlike every cache around it this holds nothing but the
     * metatable — built once, lazily, and per addon for the one reason the caches are (no Lua value crosses a
     * sandbox boundary, D-017; a shared metatable would be reachable through {@code getmetatable}).
     */
    final LuaPosition.Meta positions = new LuaPosition.Meta(this);

    /**
     * This addon's <b>Sub metatable</b> ({@link LuaSub}) — the one verb {@code :off()} every subscription in
     * the API answers, built once on the first {@code X:on(key, fn)}. Per addon for the same reason every
     * metatable here is: no Lua value crosses a sandbox boundary (D-017). A Sub holds no engine object, so
     * there is nothing to tear down.
     */
    /**
     * The six per-addon metatables 091 added, each built once on the first read of its kind (D-017).
     *
     * <p>Plain fields, and safely so for the reason every lazy value on this class is (audit2 B06): a
     * metatable is only ever built from inside this addon's Lua, so the check and the build are one act
     * under {@link #luaLock} and its release is what publishes the finished table.
     */
    LuaValue meterSegMeta;
    LuaValue craftSpecMeta;
    LuaValue fepEntryMeta;
    LuaValue fepMeta;
    LuaValue hungerMeta;
    LuaValue petalMeta;

    /**
     * The four caches those metatables belong to (audit2 B10) — the objects 091 added minted a fresh
     * userdata per call, against the grammar's own rule that a read hands back the same object every time.
     *
     * <p>A {@link LuaPetal} is keyed by {@code <user>@<wire position>}, which its own comment calls its
     * identity; a {@link LuaFepEntry} by what it carries, because an entry is data and there is no other key;
     * a {@link LuaFep} and a {@link LuaHunger} by the character's sheet widget, which is the character. The
     * two widget-keyed ones hold nothing: weak on both axes, so a sheet that closes takes them with it.
     */
    final Interned<String, LuaValue> petals = Interned.keyed();
    final Interned<String, LuaValue> fepEntries = Interned.keyed();
    // retained: weak on both axes -- the value is held weakly, so nothing here reaches the sheet it is keyed on.
    final Interned<BAttrWnd, LuaValue> feps = Interned.identity();
    // retained: weak on both axes -- the value is held weakly, so nothing here reaches the sheet it is keyed on.
    final Interned<BAttrWnd, LuaValue> hungers = Interned.identity();

    /** {@link LuaRole}'s metatable, and its intern cache: a closed set of names that never dies (094). */
    LuaValue roleMeta;
    /** {@link LuaHttpResult}'s metatable — the result is a value, so only the metatable is held (095). */
    LuaValue httpResMeta;
    final java.util.Map<String, LuaValue> roles = new java.util.HashMap<String, LuaValue>();

    LuaValue subMeta;

    /**
     * The <b>Sub handles</b> this addon holds ({@link LuaSub#handle}) and the <b>Option handles</b> it holds
     * ({@link LuaOption#handle}) — each interned on the thing itself (audit2 B10), where each used to be a
     * lazy field on that thing, minted with no lock on a path two threads reach. Weak on both axes: the value
     * is the userdata over its own key, so an entry goes when Lua lets the handle go and the emitter lets the
     * subscription go.
     */
    final Interned<LuaSub, LuaValue> subHandles = Interned.identity();
    final Interned<LuaOption, LuaValue> optionHandles = Interned.identity();

    /**
     * The <b>Hand</b> of each Player object this addon holds ({@code s:player():hand()}), keyed on the Player
     * — see {@link CharApi.PlayerMark} for why the identity hangs there. Weak on both axes; the value holds
     * only the account name, so nothing here reaches anything of the client's.
     */
    final Interned<CharApi.PlayerMark, LuaValue> handObjs = Interned.identity();

    /**
     * This addon's <b>Grab metatable</b> ({@link LuaGrab}) — {@code hafen.ui():mouse():grab()}'s handle,
     * built once on the first grab. Per addon for the same reason every metatable here is (D-017). A grab
     * holds a reference to its {@link LuaMouseGrab} widget, torn down through {@link #mouseGrabs} rather
     * than here.
     */
    LuaValue grabMeta;

    /**
     * This addon's <b>Timer metatable</b> ({@link AddonManager.Timer}) — the vocabulary
     * {@code hafen.timer():after}/{@code :every} hands back, built once on the first timer scheduled. Per
     * addon for the same reason every metatable here is (D-017). The handle holds the {@link
     * AddonManager.Timer} itself, which is torn down through {@link #timers}, not here.
     *
     * <p>Plain, and safely so: it is built from inside this addon's Lua, under {@link #luaLock}, whose
     * release publishes it — a timer scheduled from an HTTP completion and one scheduled by the step are
     * two entries, never two builders (audit2 B06).
     */
    LuaValue timerMeta;

    /**
     * This addon's <b>client handles</b> ({@link OptionsHandle}) — {@code hafen.client():options()}, the
     * subsystem handles under it ({@code interface} {@code video} {@code audio} {@code camera}
     * {@code client} {@code keybindings} {@code addon}) and {@code hafen.client():profiling()} — each built
     * on first use and handed back
     * <b>by identity</b> ever after, so {@code opts:video() == opts:video()} and a draw callback reading one
     * allocates nothing. That is {@link Section}'s rule, which every other section has kept since it was
     * written; this namespace was converted to the section shape without the identity half.
     *
     * <p>Per addon like every other Lua value here (D-017), and lazily for the same reason as
     * {@link #subMeta}: an addon that never opens the settings never builds them, and under
     * {@link #luaLock} like every other lazy value here (audit2 B06). All but one are <b>stateless
     * proxies</b> over the client's live preference stores, so there is nothing to invalidate and nothing to
     * tear down: the fields go with this {@link Addon}. The exception is {@code addon()}, whose registry is
     * {@link #addonOptions} and which dies with the addon in exactly the same way.
     */
    LuaValue clientOpts, clientInterface, clientVideo, clientAudio, clientCamera, clientClient,
             clientKeybindings, clientAddonOpts, clientProfiling;

    /**
     * This addon's <b>declared options</b> ({@code hafen.client():options():addon()}, 115.2), keyed by the
     * addon's own name for the row and held in <b>declaration order</b> — which is the order the client
     * draws them in, so the registry and the page are one fact. The handle above is the door; this is what
     * it declared.
     *
     * <p>Unlike its siblings on that handle this one holds state, and it holds it for as long as the addon
     * lives: an {@link LuaOption} carries the value in force, so a read costs a field rather than a
     * {@code java.util.prefs} lookup on a path the panel walks every frame. There is still nothing to tear
     * down — a {@code :reload} builds a fresh {@link Addon} and the whole registry goes with the old one,
     * while the values are the client's and stay in its own preference store, exactly as a re-mapped
     * keybinding does.
     *
     * <p>Locked rather than concurrent: a declaration reads the map and writes it as one act (a name is
     * taken only if it is free), which no concurrent map makes atomic on its own.
     */
    final LinkedHashMap<String, LuaOption> addonOptions = new LinkedHashMap<String, LuaOption>();

    /**
     * This addon's <b>Option metatables</b> ({@link LuaOption}), one per {@link LuaOption.Kind}, each built
     * on the first option of that kind. Per addon for the reason every metatable here is (D-017), and per
     * KIND because the kind is the vocabulary: a button has no value to read and a label has no default, and
     * a single table would have to answer for verbs half of them have not got.
     */
    final LuaValue[] optionMeta = new LuaValue[LuaOption.Kind.values().length];

    /**
     * This addon's <b>Binding interning cache</b> ({@code keybindings():binding():get(id)}, 086.3): the
     * weak-valued {@code registry id -> Binding object} map, its {@link java.lang.ref.ReferenceQueue} and the
     * per-addon metatable. Same contract as {@link #kins} — per-addon so no Lua value crosses a sandbox
     * boundary (D-017), and the whole cache dies with this {@link Addon} on {@code :reload}/disable. Nothing
     * to tear down: a handle holds a String id, and the {@link haven.KeyBinding} it addresses is
     * process-global and deliberately outlives the addon, which is how the user's assignment survives.
     */
    final LuaBinding.Cache bindings = new LuaBinding.Cache(this);

    /**
     * This addon's <b>event-object metatables</b> ({@link LuaEvent}), one per shape, each built on the first
     * {@code ev} of that shape. Per addon for the reason every metatable here is (D-017), and indexed by
     * {@link LuaEvent.Shape#ordinal()} rather than kept in a map: the shapes are a closed enum known at
     * compile time, so an array is both the smaller and the more honest structure. An event object holds no
     * engine resource beyond the widget it may intern (weakly), so there is nothing to tear down.
     */
    final LuaValue[] eventMeta = new LuaValue[LuaEvent.Shape.values().length];

    /**
     * This addon's <b>loaded-file metatables</b> ({@link AssetApi.Kind}) — the vocabulary an image, a mesh, a
     * data file, a font and a rendered map image answer, each built on the first handle of its kind. Indexed
     * by ordinal for the reason {@link #eventMeta} is: the kinds are a closed enum known at compile time.
     *
     * <p>Two of them are the <b>same file seen from two sides</b>: the handle its owner holds and the view
     * another addon reads off a rule, which {@code hafen.asset():remove(a)} refuses, because freeing an asset
     * is the owner's to do. The record behind the handle is what is shared across that boundary, never a Lua
     * value (D-017), which is why the metatables are per addon like every other one here. The records are
     * torn down through {@link #images}/{@link #meshes} and {@link #assets}, not here.
     */
    final LuaValue[] assetMeta = new LuaValue[AssetApi.Kind.values().length];

    /**
     * This addon's <b>Kin interning cache</b> ({@code hafen.kin():get(idOrName)}, spec {@code 020-kin-oop}): the
     * weak-valued {@code buddy id → Kin object} map, its {@link java.lang.ref.ReferenceQueue}, and the two
     * per-addon metatable. Same contract as {@link #gobs} — per-addon so no
     * Lua value crosses a sandbox boundary (D-017) and the whole cache dies with this {@link Addon} on
     * {@code :reload}/disable; nothing to tear down (weak entries, and a handle holds only an int id). It
     * carries the {@link Addon} because the protected Kin verbs check their {@code kin.*} permissions against it.
     */
    final LuaKin.Cache kins = new LuaKin.Cache(this);

    /**
     * This addon's <b>action-bar Slot interning cache</b> ({@code s:actionbar():get(n)}, spec
     * {@code 021-actionbar-oop}): the weak-valued {@code slot index → Slot object} map, its
     * {@link java.lang.ref.ReferenceQueue} and the per-addon metatable. Same contract as {@link #gobs} and
     * {@link #kins} — per-addon so no Lua value crosses a sandbox boundary (D-017) and the whole cache dies
     * with this {@link Addon} on {@code :reload}/disable; nothing to tear down (weak entries, and a handle
     * holds only the int index). It carries the {@link Addon} because the protected {@code slot:use} verb checks
     * its {@code actionbar.*} permissions against it.
     */
    final LuaSlot.Cache slots = new LuaSlot.Cache(this);

    /**
     * This addon's <b>movement-Speed interning cache</b> ({@code s:speed():get(key)}, spec
     * {@code 060-speed-collection}): the weak-valued {@code speed index → Speed object} map, its
     * {@link java.lang.ref.ReferenceQueue} and the per-addon metatable. The same contract — and the same
     * shape — as {@link #slots}, the other cache keyed by a small int: per-addon so no Lua value crosses a
     * sandbox boundary (D-017) and the whole cache dies with this {@link Addon} on {@code :reload}/disable;
     * nothing to tear down (weak entries, and a handle holds only the index). It is what makes
     * {@code s:speed():current() == s:speed():get(2)} the "am I on this one" test.
     */
    final LuaSpeed.Cache speeds = new LuaSpeed.Cache(this);

    /**
     * This addon's <b>action-menu Pagina interning cache</b> ({@code s:menugrid():get(key)}, spec
     * {@code 023-menugrid-oop}): the weak-valued {@code resource name → Pagina object} map, its
     * {@link java.lang.ref.ReferenceQueue} and the per-addon metatable. Same contract as {@link #gobs}, {@link #kins} and {@link #slots} — per-addon so no Lua
     * value crosses a sandbox boundary (D-017) and the whole cache dies with this {@link Addon} on
     * {@code :reload}/disable; nothing to tear down (weak entries, and a handle holds only the resource name).
     */
    final LuaPagina.Cache paginae = new LuaPagina.Cache(this);

    /**
     * This addon's <b>Sound interning cache</b> ({@code hafen.sound():get(name)}, spec {@code 024-audio-oop}): the
     * weak-valued {@code resource name → Sound object} map, its {@link java.lang.ref.ReferenceQueue} and the
     * per-addon metatable. Same contract as {@link #gobs}, {@link #kins}, {@link #slots} and {@link #paginae}
     * — per-addon so no Lua value crosses a sandbox boundary (D-017) and the whole cache dies with this
     * {@link Addon} on {@code :reload}/disable. Unbounded in principle (any name is a key), which is why the
     * values are weak; a handle holds only the resource name. The cache also owns this addon's <b>playback
     * state</b> — the clips each name has in the air, keyed by name rather than by handle precisely because
     * the handles are weak (024.2) — which {@link LuaSound#teardownSounds} silences on {@code :reload}/disable.
     */
    final LuaSound.Cache sounds = new LuaSound.Cache(this);

    /**
     * This addon's <b>Buff interning cache</b> ({@code hafen.buff():find(needle)}, spec {@code 025-buffs-oop}): the
     * weak-valued {@code Buff widget → Buff object} map, its {@link java.lang.ref.ReferenceQueue} and the
     * per-addon metatable. Same contract as {@link #gobs}, {@link #kins}, {@link #slots}, {@link #paginae}
     * and {@link #sounds} — per-addon so no Lua value crosses a sandbox boundary (D-017) and the whole cache
     * dies with this {@link Addon} on {@code :reload}/disable. The <b>key is the widget's identity</b> (a
     * res name is neither unique nor stable under a {@code "ch"} update), which makes it the one cache in
     * the series with strong keys — bounded only because every access drains the queue; see {@link LuaBuff}.
     */
    final LuaBuff.Cache buffs = new LuaBuff.Cache(this);

    /**
     * This addon's <b>Meter interning cache</b> ({@code hafen.meter():find(needle)}, spec {@code 027-meters-oop}): the
     * weak-valued {@code IMeter widget → Meter object} map, its {@link java.lang.ref.ReferenceQueue} and the
     * per-addon metatable. Same contract as {@link #gobs}, {@link #kins}, {@link #slots}, {@link #paginae},
     * {@link #sounds} and {@link #buffs} — per-addon so no Lua value crosses a sandbox boundary (D-017) and the
     * whole cache dies with this {@link Addon} on {@code :reload}/disable. Like {@link #buffs} the <b>key is the
     * widget's identity</b> (the meter's res name is server-published and not guaranteed unique across the HUD
     * slot), so the keys are strong — bounded only because every access drains the queue; see {@link LuaMeter}.
     */
    final LuaMeter.Cache meters = new LuaMeter.Cache(this);

    /**
     * This addon's <b>character-sheet interning caches</b> (spec {@code 039-uniform-api} §4.1/§4.2):
     * {@code hafen.char():attr()} keyed by attribute name, {@code :skill()} and {@code :credo()} by the
     * server's own token, {@code :experience()} by the lore resource name, {@code :food()} and
     * {@code hafen.study():slot()} by widget identity — each key chosen by what the engine keeps stable
     * (D-094), since the skill, credo and lore records are all rebuilt wholesale off-thread whenever the
     * server resends a group. Same contract as {@link #gobs}: per-addon so no Lua value crosses a sandbox
     * boundary (D-017) and the whole cache dies with this {@link Addon} on {@code :reload}/disable, with
     * nothing to tear down (weak values, and a handle holds only its key).
     */
    final LuaAttr.Cache attrs = new LuaAttr.Cache(this);
    final LuaSkill.Cache skills = new LuaSkill.Cache(this);
    final LuaCredo.Cache credos = new LuaCredo.Cache(this);
    final LuaExperience.Cache experiences = new LuaExperience.Cache(this);
    final LuaFood.Cache foods = new LuaFood.Cache(this);
    final LuaStudySlot.Cache studySlots = new LuaStudySlot.Cache(this);

    /**
     * This addon's <b>party and combat interning caches</b> (spec {@code 039-uniform-api} §4.3/§4.6):
     * {@code s:party()} and {@code s:fight():target()} keyed by the <b>account plus the gob id</b> — the id is
     * the only thing the server publishes about a member or an opponent, and what makes a stashed handle
     * self-heal when they come back, while the account is what makes it mean one creature, since an id counts
     * inside one session's object cache (077) — {@code s:fight():maneuver()} by the window's own record alone
     * (the server carries it over across a refresh and writes the slot counts onto it, and a record belongs to
     * one character's window), {@code :deck()} by the account plus the hotkey <b>slot</b> (the layout is made
     * of places, every character configures its own, and loading another school rewrites what is in them) and
     * {@code :summary()} by the window. D-094 throughout: the key is whatever the engine keeps stable. Same
     * contract as {@link #gobs} — per-addon, weak-valued, dead with this {@link Addon} on
     * {@code :reload}/disable.
     */
    final LuaPartyMember.Cache partyMembers = new LuaPartyMember.Cache(this);
    final LuaManeuver.Cache maneuvers = new LuaManeuver.Cache(this);
    final LuaDeckCard.Cache deckCards = new LuaDeckCard.Cache(this);
    final LuaFightSummary.Cache fightSummaries = new LuaFightSummary.Cache(this);
    final LuaOpponent.Cache opponents = new LuaOpponent.Cache(this);

    /**
     * This addon's <b>study-totals cache</b> ({@code s:study():summary()}), keyed by the study-report
     * <b>window</b> — the same key and the same reason as {@link #fightSummaries}, which is the point of the
     * two answering the same kind of thing: the tab is one character's, it is what has a lifetime behind the
     * numbers, and a character whose sheet has not built has no window and so no summary. Weak-valued, dead
     * with this {@link Addon} on {@code :reload}/disable.
     */
    final LuaStudySummary.Cache studySummaries = new LuaStudySummary.Cache(this);

    /**
     * This addon's <b>quest, wound and crafting interning caches</b> (spec {@code 039-uniform-api}
     * §4.4/§4.5/§4.7): {@code hafen.quest()} and {@code hafen.wound()} keyed by the server's own id — both
     * windows look their record up by it and mutate it in place, so a quest completing and a wound worsening
     * are the same quest and the same wound — {@code quest:conditions()} by the quest id plus the
     * objective's text, which is the pair the engine itself matches on when it carries an objective across a
     * resend, and {@code s:craft():current()} by the recipe <b>window</b>, since a different recipe is a
     * different window rather than a change to this one. D-094 throughout. Same contract as {@link #gobs} —
     * per-addon, weak-valued, dead with this {@link Addon} on {@code :reload}/disable.
     */
    /**
     * This addon's <b>Item interning cache</b> (spec {@code 039-uniform-api} §4.8): {@code widget:items()},
     * {@code s:player():hand():item()} and {@code EquipChanged}, keyed by the <b>item widget's identity</b>.
     * That key is the decision the type exists for: the server addresses an item by a widget id it recycles, so
     * a cache keyed on the number would hand a stashed handle back pointing at whatever now holds it — and a
     * protected write through that handle would move the wrong item. Keyed on the object, a departed item is departed
     * ({@code :exists()} false) and can never become another one. Same contract as {@link #gobs} — per-addon
     * so no Lua value crosses a sandbox boundary (D-017), weak-valued, dead with this {@link Addon} on
     * {@code :reload}/disable.
     */
    final LuaItem.Cache items = new LuaItem.Cache(this);

    /**
     * This addon's <b>per-item subscriptions</b> ({@code item:on("Changed", fn)}, 104): one {@link Subs} per item
     * this addon actually listens to, minted on the first subscription and keyed on the item widget's identity —
     * the same key {@link #items} uses, and for the same reason.
     *
     * <p><b>Why an item needs its own door.</b> What an item <i>is</i> does not arrive with the item: the server
     * sends the widget first and its tooltip after, and the code that reads a quality out of that tooltip ships
     * inside a resource that may still be loading when it lands. So every read of a name, a quality, a wear row
     * or a contents block answers {@code nil} for a while and then answers, with nothing in the API to say when
     * — which left an addon with no way to draw a number on an icon except to keep asking. This is the address
     * that says it: <i>hold the item, subscribe on it</i>, exactly as the widget keys read.
     *
     * <p><b>Retired explicitly, and by the item's own death</b> (128.5). It was a {@link WeakHashMap}, and the
     * paragraph {@link #dropItemSubs} opens with is why it collected nothing: a handler written the way the
     * page writes it closes over the very item it was subscribed on, so the map's value reaches its own key
     * through a Lua closure and no entry was ever weakly unreachable. What retires an entry now is the item's
     * death — the removal seam for an item taken out of a container, and since 128.5 the disposal seam
     * ({@link AddonManager#drainDisposedWidgets}) for an item destroyed WITH the container that held it, which
     * is what an inventory closing does and which no removal ever reaches. Nothing is fired either way.
     *
     * <p><b>Concurrent because there are two writers</b>, the same pair {@link #widgetSubs} has: the mint runs
     * wherever {@code item:on(key, fn)} was called, which may be beside the step, and the retiring drain runs
     * on the step.
     */
    // retired: Addon.dropItemSubs -- an item destroyed WITH the container that held it reaches the disposal
    //   drain and no removal, and a handler closing over its own item makes the value reach the key.
    final Interned<ItemInfo.SpriteOwner, Subs> itemSubs = Interned.held();

    /** This addon's {@link Subs} for {@code it}, minted on the first {@code item:on(key, fn)}. */
    Subs itemSubs(ItemInfo.SpriteOwner it) {
        return itemSubs.of(it, () -> new Subs(this, Addon.C_EVENT));
    }

    /**
     * This addon's {@link Subs} for {@code it}, or {@code null} — the FIRE-side lookup, which mints nothing.
     *
     * <p>{@code null} in, {@code null} out (128.5), for the reason {@link #widgetSubsOrNull} gives: the map is
     * a {@link ConcurrentHashMap} now, which throws on a null key where the {@link WeakHashMap} it replaces
     * answered {@code null}.
     */
    Subs itemSubsOrNull(ItemInfo.SpriteOwner it) {
        return (it == null) ? null : itemSubs.get(it);
    }

    /**
     * Drop this addon's subscriptions on <b>one</b> item, from the removal seam — and <b>clearing is the point,
     * not the removal</b>. A handler written the way the page writes it closes over the very item it was
     * subscribed on ({@code item:on("Changed", function() … item:quality() … end)}), so the map's VALUE reaches
     * its own KEY: weak keys cannot collect that, and the entry would outlive the item forever, holding the
     * {@code GItem} and everything under it. {@link Subs#clear} drops the handlers, which is what breaks the
     * cycle — after it the entry is collectable whether or not it was removed, and a {@code sub:off()} kept in
     * Lua finds a subscription already ended. This is exactly what {@link WidgetSubs} does at its own removal.
     *
     * <p><b>And from the disposal seam too</b> (128.5). An inventory that closes <i>destroys</i> the items
     * inside it rather than removing them, so the removal seam never reaches one — which is precisely the case
     * the cycle above makes permanent. {@link AddonManager#drainDisposedWidgets} offers every disposed
     * {@link GItem} to every addon, so this must stay one map lookup and nothing else for the miss.
     */
    void dropItemSubs(ItemInfo.SpriteOwner it) {
        if(it == null)
            return;
        Subs s = itemSubs.drop(it);
        if(s != null) {
            s.clear();
            AddonManager.recountItemSubs();   // audit2 B15: one watcher fewer — see onItemInfo
        }
    }

    /**
     * Drop every handle this addon <b>interned</b> for a widget that has died (128.5) — the other half of an
     * item's retirement, and the half that has nothing to do with subscriptions.
     *
     * <p>The item caches ({@link #items}, {@link #contents}, {@link #studySlots}) are
     * {@code IdentityHashMap}s keyed <b>strongly</b> on the {@link GItem}, holding a weak reference to the
     * handle and swept only on the next {@code of()}. So an addon that interns items and then stops minting
     * them pins every {@code GItem} it ever touched — handle or no handle — and an inventory closing is where
     * that bites, because the items in it are destroyed rather than removed. {@link #meters} and {@link #buffs}
     * are the same shape one subsystem along, keyed on the {@code IMeter} and the {@code Buff} widget.
     *
     * <p>Each cache is emptied of the entry <b>through the monitor its own {@code of()} takes</b>, never by
     * reaching the map: the mint runs wherever Lua ran, and this runs on the step.
     *
     * <p>A widget of none of those kinds is almost every widget the drain sees, so the kind is tested once per
     * widget by the drain and this is called only for one that can be in a cache at all.
     *
     * <p><b>The Item cache is keyed on what is drawn, not on the widget drawing it</b> (137.1), so the widget
     * is resolved through {@link LuaWidget#itemOf} — a recipe slot dying retires the depiction it held, the
     * way an item dying retires itself. The other three stay a {@code GItem}'s: what an item holds and what a
     * study slot is are things only the server pushes.
     */
    void dropInternedHandles(Widget w) {
        if(w instanceof GItem) {
            GItem it = (GItem)w;                              // a GItem is its own icon: no itemOf detour
            items.retire(it);
            contents.retire(it);
            studySlots.retire(it);
        } else if(w instanceof IMeter) {
            meters.retire((IMeter)w);
        } else if(w instanceof Buff) {
            buffs.retire((Buff)w);
        } else {
            items.retire(LuaWidget.itemOf(w));                // the icon of a depiction, and nothing else
        }
    }

    /**
     * This addon's <b>Contents interning cache</b> ({@code item:contents()}), keyed on the <b>owning item
     * widget</b> — the same key {@link #items} uses, and for the same reason. What an item holds is a thing of
     * its own rather than a field of the Item, because a stack's insides are live items and a bucket's are a
     * stated line, and one object answers for both; see {@link LuaContents}. Weak-valued, dead with this
     * {@link Addon} on {@code :reload}/disable, with nothing to tear down.
     */
    final LuaContents.Cache contents = new LuaContents.Cache(this);

    /**
     * This addon's <b>Channel interning cache</b> ({@code s:chat()}, spec {@code 110-the-channel-and-the-line}):
     * the {@code Channel widget -> Channel object} map and the per-addon metatable that make
     * {@code s:chat():list()[1] == s:chat():selected()} true. Weak on <b>both</b> axes, the
     * {@link #widgetObjs} shape rather than the strong-key one: a channel holds its whole scrollback, so a
     * strong key would pin every closed conversation for the life of the addon.
     */
    final LuaChannel.Cache channels = new LuaChannel.Cache(this);

    /**
     * This addon's <b>Message interning cache</b> ({@code ch:message()}, spec
     * {@code 110-the-channel-and-the-line}): the {@code (Channel widget, index) -> Message object} map that
     * makes {@code ch:message():get(n)} the very object {@code MessageAdded} handed over. Weak on the channel
     * axis and reference-queue-drained on the index one, because a scrollback is never trimmed and walking
     * one would otherwise leave a handle per line behind it; see {@link LuaMessage}.
     */
    final LuaMessage.Cache messages = new LuaMessage.Cache(this);

    final LuaQuest.Cache quests = new LuaQuest.Cache(this);
    final LuaCondition.Cache conditions = new LuaCondition.Cache(this);
    final LuaWound.Cache wounds = new LuaWound.Cache(this);

    /**
     * This addon's <b>Widget interning cache</b> ({@code s:ui():root()}/{@code node(id)}/{@code at(x,y)}, spec
     * {@code 029-widget-oop}): the {@code Widget → Widget object} map and the per-addon metatable that make
     * {@code hafen.ui.at(m.x,m.y) == hafen.ui.at(m.x,m.y)} true and let {@code node:same()} be cut. Per-addon like
     * every other cache here — no Lua value crosses a sandbox boundary (D-017) and the whole cache dies with this
     * {@link Addon} on {@code :reload}/disable.
     *
     * <p>It is the one intern cache that is weak on <b>both</b> axes ({@link Interned#identity}), not the
     * {@link #buffs}/{@link #meters} strong-key shape: over a whole widget tree a strong key would pin every
     * destroyed widget until the next queue drain, breaking the D-041 no-pin rule.
     * Nothing to tear down — this is an identity map over engine-owned widgets, deliberately NOT an owned-resource
     * registry like {@link #widgets} (which holds the addon's own drawn {@link AddonWidget}s).
     */
    // retained: weak on both axes -- the value is held weakly, so nothing here reaches the widget it is keyed on.
    final Interned<Widget, LuaValue> widgetObjs = Interned.identity();

    /**
     * This addon's <b>Widget metatable</b> ({@link LuaWidget}), built inside the mint above rather than
     * lazily beside it: a widget handle is minted for an event payload as well as from Lua, so what
     * publishes this one is {@link #widgetObjs}'s own lock and not {@link #luaLock}.
     */
    LuaValue widgetMeta;

    /**
     * This addon's <b>marker collection</b> ({@code hafen.map():marker()}), minted once when its env is built
     * and handed back by identity ever after — including to the {@code MarkerChanged} handler, which is the
     * one door that used to build a second one ({@link MapApi#markers}). It is the collection, not the
     * {@link #mapMarkers} cache of the Marker objects in it. {@code null} until the env is installed, and
     * dead with this {@link Addon} like every other Lua value here (D-017).
     */
    LuaValue markerColl;

    /**
     * This addon's <b>minimap icon-category interning cache</b> ({@code hafen.map():icon():get(res)}, spec
     * {@code 037-map-database}): the weak-valued {@code icon resource name → IconCat object} map, its
     * {@link java.lang.ref.ReferenceQueue} and the per-addon metatable. Same contract as {@link #paginae},
     * whose {@code String} key it copies — per-addon so no Lua value crosses a sandbox boundary (D-017) and
     * the whole cache dies with this {@link Addon} on {@code :reload}/disable; nothing to tear down (weak
     * entries, and a handle holds only the resource name).
     */
    final LuaIconCat.Cache iconCats = new LuaIconCat.Cache(this);

    /**
     * This addon's <b>map-database interning caches</b> ({@code hafen.map()}'s segment/grid/marker collections, spec
     * {@code 037-map-database} task 037.2): weak-valued {@code id → Segment/Grid/Marker object} maps with
     * their {@link java.lang.ref.ReferenceQueue}s and per-addon metatables. Same contract as {@link #gobs}
     * — per-addon so no Lua value crosses a sandbox boundary (D-017) and the whole cache dies with this
     * {@link Addon} on {@code :reload}/disable; nothing to tear down (weak entries, and a handle holds only
     * an id).
     *
     * <p>The keys are the ids the <b>engine</b> publishes (D-063), never Java identity: a {@code Segment}
     * lives in a {@code BackCache(5)} and a {@code Grid} in a weak {@code CacheMap}, so the same segment or
     * grid comes back as a different object after an eviction and an identity map would go stale
     * invisibly. A marker's key is the per-session ref {@link MapApi} already mints — a
     * {@code MapFile.Marker} is loaded once and mutated in place, so that identity is stable for the session
     * and is dropped with the rest of the session state on relog.
     */
    final LuaSegment.Cache mapSegments = new LuaSegment.Cache(this);
    final LuaMapGrid.Cache mapGrids = new LuaMapGrid.Cache(this);
    final LuaMarker.Cache mapMarkers = new LuaMarker.Cache(this);
    /**
     * ...and the same for the recorded <b>masks</b> ({@code grid:mask():get(tag)}, task 037.3), keyed on
     * the pair the engine publishes — the grid id and the overlay <i>tag</i> — for the same reason: the mask
     * lives inside a {@code Grid} that the weak {@code CacheMap} rebuilds from disk after an eviction.
     */
    final LuaMask.Cache mapMasks = new LuaMask.Cache(this);
    /**
     * ...and the same for the client's four <b>display toggles</b> ({@code hafen.map():display():get(tag)},
     * task 039.4), keyed on the tag. The hold itself is <b>not</b> here — it lives in {@link #overlayHolds},
     * because a handle is a name for a switch and a hold is a resource that has to be given back.
     */
    final LuaOverlayToggle.Cache overlayToggles = new LuaOverlayToggle.Cache(this);

    /**
     * This addon's <b>gob-overlay interning cache</b> ({@code gob:overlay(key)}, spec
     * {@code 038-gob-overlays}): the weak-valued {@code (gob id, native?, key) → Overlay object} map, its
     * {@link java.lang.ref.ReferenceQueue} and the per-addon metatable. Same contract as {@link #mapMasks},
     * whose composite {@code String} key it copies — per-addon so no Lua value crosses a sandbox boundary
     * (D-017) and the whole cache dies with this {@link Addon} on {@code :reload}/disable.
     *
     * <p>Nothing to tear down: the entries are weak and a handle holds only ids. The <b>overlays themselves</b>
     * are not here at all — they live on the gob, which is what makes an Overlay object a view of engine state
     * rather than a record of ours.
     */
    final LuaOverlay.Cache gobOverlayObjs = new LuaOverlay.Cache(this);

    /**
     * This addon's <b>rendered map images</b> ({@code grid:image(lvl)} / {@code grid:overlayImage(tag)}, task
     * 037.4) — unlike the caches above this one holds an owned RESOURCE, so it is strong, bounded and torn
     * down. Each entry is a {@link haven.TexI} the client's own renderer built on {@link haven.Defer} out of
     * the map database, wrapped in an ordinary {@link LuaImage} that also sits in {@link #images}; the LRU
     * bound is what keeps a panel that scrolls across a continent from holding every grid it ever drew.
     * {@link MapImages#teardown} runs before {@link AssetApi#teardownAssets}, so the textures are freed once
     * and by the path that already frees every other image.
     */
    final MapImages.Cache mapImages = new MapImages.Cache(this);

    /**
     * Display overlays this addon is <b>holding</b> ({@code toggle:hold()}, task 037.3) — the
     * {@link #hiddenNative} shape one subsystem along: <i>what we asked the client to draw, and how to stop
     * asking</i>. One entry per tag at most, because a hold is idempotent (D-097).
     *
     * <p><b>A write here is a HOLD, not a switch.</b> {@code MapView.oltags} is a ref-counted multiset shared
     * with the client's own menu checkbox and with the server's {@code flashol}, so an addon can only add its
     * own {@code +1} and take it away again; {@link MapApi#teardownOverlays} releases every entry exactly once
     * on {@code :reload}/disable, and an unbalanced write would otherwise leave an overlay on the screen
     * forever. The {@code realm} tag is the map window's plain set instead, so its entry also remembers
     * whether the tag was already there — releasing must never switch off what the user's checkbox turned on.
     * Copy-on-write like the other owned lists.
     */
    public final List<MapApi.Hold> overlayHolds = new CopyOnWriteArrayList<MapApi.Hold>();

    /**
     * This addon's <b>asset intern cache</b> ({@code hafen.asset(path)}, spec {@code 028-asset-loader}): the
     * {@code resolved path → loaded asset} map behind the one loader for the files this addon ships — images,
     * fonts and glTF meshes alike, replacing the two linear scans over {@link #images}/{@link #meshes} and
     * giving fonts the cache they never had (one {@code Font.createFont} + one {@code registerFont} per file,
     * not one per call). Per-addon like every other cache here — no Lua value crosses a sandbox boundary
     * (D-017) and the whole thing dies with this {@link Addon} on {@code :reload}/disable.
     *
     * <p>Unlike the weak intern caches ({@link #gobs}, {@link #sounds}, …) the values are <b>strong</b>: an
     * asset is an owned resource with a lifetime, not an identity map over something the engine owns. The typed
     * lists above stay the teardown units (they encode the R3b order); {@link AssetApi#teardownAssets} runs them
     * and then clears this. It also interns the addon's <b>built-in</b> fonts ({@code hafen.font("sans")}),
     * which are engine-owned and therefore not assets — never listed, never disposed.
     */
    final AssetApi.Cache assets = new AssetApi.Cache();

    /**
     * This addon's <b>rendered-text cache</b> ({@code g:text}/{@code g:atext}, spec {@code 026-text-cache}): the
     * bounded LRU of {@code (string, font handle, Fonts.gen()) → the rendered Text + its Tex}, so an immediate-mode
     * draw stops re-rasterising and re-uploading the same line every frame. Per-addon like every other cache here
     * — one addon cannot evict another's entries, and {@link LuaGOut#teardownTexts} drops it (disposing the GL
     * textures, which we own) on {@code :reload}/disable. Unlike the intern caches this one holds STRONG values on
     * purpose: it is a cache, not an identity map, and it is bounded by entry count and texture bytes instead.
     */
    final LuaGOut.Cache texts = new LuaGOut.Cache();

    /**
     * This addon's <b>{@code g} draw wrapper</b> (audit2 B01) — the one {@link LuaGOut} every painter of
     * <i>this</i> addon binds for the length of one draw callback: a HUD overlay, a widget overlay. It was one
     * wrapper for the client, on the premise that the draw traversal is single-threaded and never re-entrant,
     * which made a nested painter of ANOTHER addon rebind the outer painter's wrapper mid-call — its remaining
     * verbs going inert and its text rendering into the inner addon's cache. Per addon the premise is no longer
     * needed: two addons painting inside one another hold two wrappers, and each stays bound to its own owner.
     *
     * <p><b>And it is the only one</b> (audit2 B15). {@link AddonWidget}, {@link CGrid} and
     * {@link LuaGobOverlay} each held a narrower wrapper of their own — one per widget, per control, per
     * OVERLAID GOB — and a wrapper is a whole table of drawing closures built in its owner's constructor, so a
     * label on each of two hundred crops was two hundred tables for records that never enter Lua at all. Per
     * addon is already the right grain, because every bind in this bridge is strictly scoped: it is released
     * before the traversal reaches anything else that could paint, so no second painter of THIS addon can ever
     * be inside one. What the client-wide wrapper could not do — keep two addons painting inside one another
     * apart — is exactly what per-addon does.
     */
    final LuaGOut gout = new LuaGOut();

    /**
     * This addon's <b>engine-resource caches</b> for {@code g:resource(name, ...)} (audit2 B01, D-039): the
     * resource name &rarr; its {@link haven.Indir} lookup, and beside it the LINEAR-sampled {@link haven.Tex}
     * copy that name is drawn through. Both were one map for the client, keyed by any Lua string an addon ever
     * passed and dropped only by a full {@code :reload} — never by a disable, and never per addon. The second
     * holds GL memory, one texture per distinct name, which is why {@link LuaGOut#dropResources} disposes each
     * before it drops the maps.
     *
     * <p>Concurrent because {@code g:resource} is reached from every thread that draws.
     */
    final Map<String, haven.Indir<haven.Resource>> resCache =
        new ConcurrentHashMap<String, haven.Indir<haven.Resource>>();
    final Map<String, haven.Tex> resTexCache = new ConcurrentHashMap<String, haven.Tex>();

    /**
     * <b>What this addon has asked to be drawn at a game object</b> (audit2 B01, {@link GobIntent}): gob id
     * &rarr; the size, the tint, the hiding and the overlays it wrote there, so a session that loads the object
     * afterwards draws it the same. Only ids this addon actually asked for something at are in it.
     *
     * <p><b>Per addon and not for the client</b>, which is what makes a teardown free: the wishes of an addon
     * that has stopped running are gone with the addon, so nothing an unguarded sweep may skip can re-apply
     * them to a copy that arrives later. The gob id stays the key because a gob id is the <i>server's</i> and
     * names one object — {@link GobIntent} holds the whole reason.
     */
    final Map<Long, GobIntent.Record> gobIntents = new java.util.HashMap<Long, GobIntent.Record>();

    /**
     * This addon's <b>marker refs</b> ({@link MapApi#markerId}) — the {@link haven.MapFile.Marker} an
     * interned Marker object stands for, in both directions, minted on the first hand-out. Per addon
     * because a ref is minted only because an addon asked for one, and weak on the marker so a pin the map
     * database itself has dropped is not held here alone.
     */
    final MapApi.MarkerRefs markerRefs = new MapApi.MarkerRefs();

    /**
     * The single {@code hafen.ui():mouse()} object for this addon ({@link LuaMouse}, 041.5) — the pointer
     * entity, built lazily and cached so {@code hafen.ui():mouse() == hafen.ui():mouse()}, the same singleton
     * shape every singleton here has. Holds no engine resource itself (its verbs read live UI state on every
     * call), so there is nothing to tear down.
     */
    LuaValue mouseObj;

    /**
     * The {@code hafen.store()} tables — <b>the ACCOUNT scope</b>, one Lua table per declared account-scope
     * saved variable. Populated in {@link AddonManager#installHafen}; the engine reads it on flush.
     * {@code null} until installed.
     *
     * <p>The other scope is nowhere near here (079.1): a character's saved variables are one session's, so
     * they live in that session's own {@link AddonManager.SessionState#charStores} and there are as many
     * sets as the client has logins.
     */
    public LuaTable store;
    /** Write-skip cache: the last JSON serialized for the account scope, so an unchanged flush skips disk I/O. */
    public String lastAccountJson;
    /**
     * <b>The account scope is read-only until a load succeeds</b> — set when the file was there and could
     * not be read or parsed. What {@link #store} then holds is empty because the client could not read the
     * file, and writing that back is an atomic replacement of the only copy. Cleared by the next successful
     * load, which is a {@code :reload} or the next launch once the file is readable again.
     */
    public boolean accountReadOnly;

    /**
     * Soft per-tick CPU-budget accounting (D-018 layer 2). {@link #tickLuaNanos} is the total time this
     * addon spent in Lua during the current engine tick (summed across its {@code Update}/timers/event
     * handlers by {@link AddonManager#callLua}); {@link #overBudgetStrikes} counts consecutive ticks over
     * the budget. The layer's step takes the frame out of it with {@link LongAdder#sumThenReset()} and
     * {@link AddonManager#enforceSoftBudget()} evaluates the strikes — see {@link Sandbox#SOFT_BUDGET_NANOS}.
     *
     * <p><b>A {@link LongAdder} and not a {@code long}</b> (audit2 B06): the accumulate happens inside
     * {@link AddonManager#callLua}, which several threads enter — and the {@code += } was a read, an add and
     * a store the step's zeroing could land in the middle of, so the watchdog judged a number that had lost
     * whole handlers. The reset is the same instant the frame closes, and {@code sumThenReset} is what makes
     * the close and the zero one step rather than two.
     */
    public final LongAdder tickLuaNanos = new LongAdder();
    public int  overBudgetStrikes;

    // ------------------------------------------------------------- per-addon profiling (spec 019, 019.4)

    /**
     * The call categories {@link AddonManager#callLua} splits its accounting by — what the addon's Lua time
     * was spent <b>doing</b>, which the single {@code tickLuaNanos} total cannot say. Every call site passes
     * one; the argument is mandatory precisely so a new one cannot be added without choosing.
     */
    public static final int C_EVENT = 0, C_TIMER = 1, C_DRAW = 2, C_HOOK = 3, C_WIDGET = 4;
    /** The category names, in {@link #C_EVENT}… order — the keys of the {@code calls}/{@code cost} tables. */
    public static final String[] CATS = {"events", "timers", "draw", "hooks", "widgets"};

    /* Current frame, written by callLua only while armed. tickLuaNanos above stays byte-for-byte what it
     * was: the D-018 watchdog must keep auto-disabling at exactly the same point, so the split is an
     * ADDITION inside the same finally, never a replacement.
     *   Adders for tickLuaNanos' reason (audit2 B06): the same finally on the same threads, and profRoll
     * takes the frame out of them where the total is taken out of it. */
    final LongAdder[] catNanos = adders(CATS.length);
    final LongAdder[] catCalls = adders(CATS.length);

    /** {@code n} fresh adders — {@link #catNanos}/{@link #catCalls}, which Java will not fill by declaration. */
    private static LongAdder[] adders(int n) {
        LongAdder[] a = new LongAdder[n];
        for(int i = 0; i < n; i++)
            a[i] = new LongAdder();
        return a;
    }

    /* The last COMPLETED frame, plus the rolling figures, snapshotted by profRoll() at the top of the next
     * tick — the same instant tickLuaNanos is zeroed, so the row and :frame()'s addons roll-up read one
     * number. A Lua reader always sees a whole frame, never a half-accumulated one. */
    long profNanos, profPeakNanos, profSumNanos;
    int profFrames;
    final long[] profCat = new long[CATS.length];
    final int[] profCalls = new int[CATS.length];

    /**
     * This addon's named profiling scopes ({@code p:scope(name)} / {@code p:measure(name, fn)}) — the
     * {@code ProfilerMarker} equivalent, keyed by name. Per-addon, so two addons may both use
     * {@code "update"} without colliding, and so the whole map dies with this {@link Addon} on
     * {@code :reload}/disable — there is nothing to tear down. UI thread only (like every Lua call);
     * insertion-ordered so a profiler window lists scopes the way the addon declared them.
     */
    final java.util.Map<String, Scope> scopes = new java.util.LinkedHashMap<String, Scope>();

    /** One named scope's accounting. Same shape as the addon row: this frame, rolling average, peak. */
    static final class Scope {
        final String name;
        long nanos, peakNanos, sumNanos;      // nanos = current frame
        int calls, frames;
        long lastNanos;
        int lastCalls;
        int depth;                            // re-entrancy: only the outermost begin/finish pair counts
        long t0;

        Scope(String name) {this.name = name;}
    }

    /**
     * Get (or create) this addon's scope by name. Created lazily, so an off-state {@code p:scope()} costs
     * nothing.
     *
     * <p>Under the map's own monitor (audit2 B06): {@code p:addons()} walks ANOTHER addon's scopes to build
     * its row, so a lazy put here could throw a {@code ConcurrentModificationException} out of that read.
     * The scopes' own accounting is serialized by this addon's lock, which every open, close and roll is
     * inside; the map is the one thing a second addon touches.
     */
    Scope scope(String name) {
        synchronized(scopes) {
            Scope s = scopes.get(name);
            if(s == null) {
                // audit2 B14 (pf-08): AND THE SET OF NAMES IS BOUNDED. This map is cleared at teardown and
                // nowhere else, and every snapshot of p:addons() builds one Lua table per entry -- so a
                // scope named by a generated string (an item id, a frame number, a coordinate) grew the map
                // and the per-frame snapshot together, without limit, for as long as the profiler was
                // armed. A scope is a NAME an author writes, so a few hundred is already more than a
                // program has; past that the write is refused rather than the memory taken.
                if(scopes.size() >= MAX_SCOPES)
                    throw new org.luaj.vm2.LuaError("p:scope(name): this addon has already named "
                        + MAX_SCOPES + " scopes, which is the limit — a scope names a SECTION of your code,"
                        + " so a generated name (an id, a coordinate, a frame) is measuring one thing under"
                        + " a million labels. Name the section instead.");
                scopes.put(name, s = new Scope(name));
            }
            return s;
        }
    }

    /** How many distinct scope names one addon may hold — see {@link #scope}. */
    private static final int MAX_SCOPES = 256;

    /**
     * Close the frame: move this frame's accounting into the "last completed frame" fields and start the
     * next one at zero. Called from the layer's step with the frame's own total, taken out of
     * {@link #tickLuaNanos} in one step — which is exactly the point at which that adder held the whole of
     * the previous frame (it accrues through the tick <b>and</b> the draw callbacks that follow it).
     */
    void profRoll(boolean probed, long frameNanos) {
        profNanos = frameNanos;
        profSumNanos += frameNanos;
        profFrames++;
        if(frameNanos > profPeakNanos)
            profPeakNanos = frameNanos;
        // 019.7: a CONTROL frame ran with the category probes disarmed, so catNanos/catCalls and the scopes
        // are all zero for it -- while tickLuaNanos above is not, because the D-018 watchdog measures it
        // whether we are profiling or not. Rolling zeroes in would make one row in 64 read as "this addon
        // did nothing", so the split simply holds its last measured frame across a control frame.
        if(!probed)
            return;
        for(int i = 0; i < CATS.length; i++) {
            profCat[i] = catNanos[i].sumThenReset();
            profCalls[i] = (int)catCalls[i].sumThenReset();
        }
        synchronized(scopes) {
        for(Scope s : scopes.values()) {
            s.lastNanos = s.nanos;
            s.lastCalls = s.calls;
            s.sumNanos += s.nanos;
            s.frames++;
            if(s.nanos > s.peakNanos)
                s.peakNanos = s.nanos;
            s.nanos = 0;
            s.calls = 0;
            s.depth = 0;   // a scope left open by an erroring handler recovers here instead of never closing
        }
        }
    }

    /** Drop every profiling figure ({@code p:reset()} and every arming of the switch). */
    void profReset() {
        profNanos = profPeakNanos = profSumNanos = 0;
        profFrames = 0;
        for(int i = 0; i < CATS.length; i++) {
            catNanos[i].reset();  profCat[i] = 0;
            catCalls[i].reset();  profCalls[i] = 0;
        }
        synchronized(scopes) {
            scopes.clear();
        }
    }

    Addon(Manifest manifest, Path dir, Globals env) {
        this(manifest, dir, env, Collections.<String>emptyList(), EnumSet.noneOf(Permission.class));
    }

    Addon(Manifest manifest, Path dir, Globals env, List<String> grantedHosts, Set<Permission> grantedKeys) {
        this.manifest = manifest;
        this.dir = dir;
        this.env = env;
        this.grantedHosts = Collections.unmodifiableList(new ArrayList<String>(grantedHosts));
        this.grantedKeys = Collections.unmodifiableSet(grantedKeys.isEmpty()
                                                       ? EnumSet.noneOf(Permission.class)
                                                       : EnumSet.copyOf(grantedKeys));
    }

    /**
     * Whether this addon may reach {@code origin} ({@code scheme://host:port}, {@link Manifest#origin}) —
     * <b>the record, not the manifest</b>. The engine-internal owner's any-host exemption is tested first,
     * because it consented to nothing and has nothing recorded; everything else is matched against
     * {@link #grantedHosts} by the manifest's own rule ({@link Manifest#hostMatches}), so
     * {@code *.example.com} means the same thing on both sides.
     *
     * <p>An <b>origin</b> and not a host since audit2 B08 (ht-05): a grant carries the scheme and the port,
     * so a host the user approved for {@code https} is not also reachable in cleartext on any port.
     */
    public boolean hostGranted(String origin) {
        return manifest.anyHost() || Manifest.hostMatches(grantedHosts, origin);
    }

    /**
     * Whether this addon holds {@code perm} — <b>the record, not the manifest</b> (audit2 B08, pm-03), the
     * key half of {@link #hostGranted} and written to read the same way. The engine-internal owner is
     * exempted first for the same reason: the {@code :lua} REPL is the operator's own console and passes
     * through no consent dialog, so it has nothing recorded to be granted by.
     *
     * <p>The manifest still has to declare the key — the record is additive and per addon, so a key granted
     * once stays granted, and an addon that has since dropped the declaration should not keep the door. Both
     * halves, and the refusal names the manifest because that is the half an author can fix.
     */
    public boolean keyGranted(Permission perm) {
        return manifest.internal()
            || (manifest.permissions.has(perm) && grantedKeys.contains(perm));
    }

    /**
     * Run the addon's Lua files in manifest order. On the first failure, record it and stop.
     *
     * <p>A {@code files} entry is a name out of a JSON file, so it is resolved through {@link Inside} rather
     * than by {@code dir.resolve} alone: an entry naming anything but a file inside this addon's own folder
     * is the load error the panel shows, not a chunk the client executes.
     *
     * <p><b>The whole load is one entry</b> (audit2 B06): a file body builds this addon's tables and may
     * schedule a timer or subscribe on its last line, so the load holds {@link #luaLock} across every file
     * rather than per call. It runs on a Loader thread holding no tree monitor, so waiting for the lock is
     * the ordinary direction and never the deadlock.
     *
     * <p>It takes that lock through {@link AddonManager#enterLua} and not by hand (audit2 B08), because the
     * entry is what names the running addon: a file body that calls a protected verb on its last line is
     * this addon's code exactly as a handler is, and a gate keyed on {@link AddonManager#current()} has to
     * see it. Waiting is the ordinary direction here, so the entry is never the one that is declined.
     */
    void run() {
        if(!AddonManager.enterLua(this)) {
            error = "the addon layer was busy when this addon was loaded";
            return;
        }
        try {
            for(String file : manifest.files) {
                try {
                    Path fp = Inside.inside(dir, file, "manifest 'files'");
                    String src = new String(Files.readAllBytes(fp), StandardCharsets.UTF_8);
                    LuaValue chunk = env.load(src, "@" + manifest.id + "/" + file);
                    // watchdog the file body too (D-018) — a full budget per file. 126.2: paired with a
                    // release, because loading runs off the frame's thread and an unreleased claim would be
                    // a holder left behind on a Loader thread that is about to end.
                    long budget = Sandbox.arm(env);
                    try {
                        chunk.call();
                    } finally {
                        Sandbox.disarm(env, budget);
                    }
                } catch(Throwable e) {
                    // AND AN Error, which is the half of this path that was contained nowhere. A file body is
                    // addon Lua exactly as a handler is, and deep Lua recursion under it raises
                    // StackOverflowError -- neither an Exception nor a LuaError, so it left this catch, left
                    // AddonRegistry.loadAll's, left the layer step's catch(RuntimeException) and ended the UI
                    // thread, taking the client with the addon that failed. The addon's own pcall cannot see
                    // one either (LuaJ's pcall catches LuaError and Exception), so this is the only place.
                    //   It is reported as the manifest's failure, which is what a load error IS: the panel
                    // names the file and the addon does not run. ThreadDeath and an interrupted thread are
                    // rethrown for the reason AddonManager.callLua states -- the quit path interrupts the
                    // thread it is waiting on, and a containment that swallowed that would outlive the exit.
                    if((e instanceof ThreadDeath) || Thread.currentThread().isInterrupted()) {
                        if(e instanceof Error)
                            throw (Error)e;
                        if(e instanceof RuntimeException)
                            throw (RuntimeException)e;
                    }
                    error = file + ": " + Refusal.reason(e);
                    return;
                }
            }
        } finally {
            AddonManager.leaveLua(this);
        }
    }
}
