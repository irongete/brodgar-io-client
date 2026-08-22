package io.brodgar.addon;

import haven.Waitable;
import haven.Widget;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

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
     * <p>It replaced the flat list of subscription records this field used to be: one emitter owns one
     * {@code Subs}, and {@link LuaSub} is both the Lua handle and the entry, so there is nothing to keep in
     * step. Teardown drops it wholesale ({@link Subs#clear}), so an addon never unsubscribes by hand.
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
     * widget this addon has subscribed on, keyed exactly like every other per-widget registry here (weak, so a
     * widget that leaves the tree needs nothing done on this side). It started (041.3) as just the four input
     * keys over the engine {@code EventHandler}s {@link haven.Widget#listen} installs — replacing the fixed
     * three-token {@code hooks} list ({@code hafen.hook():input}) — and 041.4 folds the REST of the widget
     * vocabulary onto the same record: {@code Pressed}/{@code Changed}/…'s controls fire straight into its
     * {@link Subs}, {@code Draw}/{@code Tick}/{@code Drop}/{@code Close} do the same for an owned surface, and
     * {@code ItemAdded}/{@code ItemRemoved}/{@code Destroy} add the one thing none of those needed — a
     * placement/removal watch-list registration (event-driven since 042.7) — so one class is the address for
     * everything a widget can say, the same way {@link Subs} is the one mechanism under every {@code :on(key, fn)}
     * in the API. A NATIVE widget that survives {@code :reload} is what {@link #teardownWidgetSubs} walks
     * instead, releasing every listener and watch-list registration this addon installed before the Lua layer
     * that owns them is rebuilt (P2).
     */
    final Map<Widget, WidgetSubs> widgetSubs = new WeakHashMap<Widget, WidgetSubs>();

    /** This addon's {@link WidgetSubs} for {@code w}, minted on the first {@code w:on(key, fn)}. */
    WidgetSubs widgetSubs(Widget w) {
        WidgetSubs s = widgetSubs.get(w);
        if(s == null) {
            s = new WidgetSubs(this, w);
            widgetSubs.put(w, s);
        }
        return s;
    }

    /**
     * This addon's {@link WidgetSubs} for {@code w}, or {@code null} — the FIRE-side lookup (041.4), which must
     * never mint one: a control's every press/tick/draw runs through this, so an unlistened widget must cost one
     * map lookup and nothing else (the {@code hasSub} gate one level up from {@link Subs#has}).
     */
    WidgetSubs widgetSubsOrNull(Widget w) {
        return widgetSubs.get(w);
    }

    /**
     * Drop this addon's subscriptions on <b>one</b> widget ({@code widget:revert()}, 061.9) — the same
     * release {@link #teardownWidgetSubs} does for all of them, one widget at a time: every engine listener
     * and watch-list registration goes, and each handler is marked dead so a {@code sub:off()} kept in Lua
     * still finds nothing to end. Nothing is fired: a revert is not a destroy.
     */
    void dropWidgetSubs(Widget w) {
        WidgetSubs s = widgetSubs.remove(w);
        if(s != null)
            s.teardown();
    }

    /** Deafen every engine listener this addon's {@link WidgetSubs} installed (teardown, P2). */
    void teardownWidgetSubs() {
        for(WidgetSubs s : widgetSubs.values())
            s.teardown();
        widgetSubs.clear();
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
     * {@code hooks} column is what is left of {@code hafen.hook()} — hotkeys and slash commands.
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
     * This addon's <b>selector subscriptions</b> as subscriptions ({@code s:ui():on(sel, "appear", fn)},
     * 086.1) — the emitter that mints what that verb hands back, so it is a {@link LuaSub} like every other
     * {@code :on} in the API rather than a one-verb table. The key is the <b>event</b>, {@code "appear"} or
     * {@code "disappear"}, because that is what a person would name and the selector already lives on the
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
     * This addon's <b>slash commands</b> as subscriptions ({@code hafen.slash():on(name, fn)}, 086.1), keyed
     * by the command name, with the {@link LuaSlashCommand} on {@link LuaSub#tag}. Its {@link Subs.Ended}
     * clears the live handler and nothing else: a {@link haven.Console} command is <b>register only, no
     * unregister</b>, so the one engine-lifetime dispatcher stays installed and reports "no addon handles
     * :name" (coverage-gaps C1).
     */
    public final Subs slashSubs = new Subs(this, Addon.C_HOOK, new Subs.Ended() {
        public void ended(LuaSub s) {
            HookApi.endSlashCommand(Addon.this, (LuaSlashCommand)s.tag);
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
     * Live selector subscriptions owned by this addon ({@code s:ui():on(sel, "appear"|"disappear", fn)}, 030.2 —
     * what replaced {@code hafen.ui.onWidgetCreate} and its descriptor): each watches the whole tree for widgets
     * matching one {@link Selector}, fired from the placement seam and the removal seam (event-driven since
     * 042.9). They live in a flat global dispatch list in {@link UiApi} (a subscription watches the whole tree,
     * not one keyed target); teardown ({@link UiApi#teardownSelectorWatches}) marks each dead and drops both
     * copies <b>without firing</b> — a {@code :reload}/disable is not a destroy, exactly as for a
     * {@link WidgetSubs}'s watch-list registration.
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
     * What is saved under each of those names for the character on screen (062) — a place, a box, or both,
     * in design pixels. Loaded from {@code savedata/<genus>_<char>/<id>.layout.json} by
     * {@link StoreApi#rescope} when that character comes on screen, and written back by every flush and
     * when they leave it, so a remembered placement needs no {@code saved_variables} declaration and no
     * handler of the addon's own. A window an addon builds stands in the layer over whichever session is
     * drawn, which is why this one set follows the screen where a saved variable follows its session.
     */
    public final Map<String, StoreApi.Placement> placements = new ConcurrentHashMap<String, StoreApi.Placement>();
    /** The last placement JSON written for this addon, so an unchanged file is not rewritten. */
    public String lastPlacementJson;
    /**
     * Live addon slash commands owned by this addon ({@code hafen.slash():on}, gap subsystem A11): each routes
     * a console command {@code :name} to a Lua handler. Unlike the hook lists, the engine's {@link haven.Console}
     * dispatcher for a name is <b>engine-lifetime</b> and is deliberately <b>not</b> removed on teardown (coverage-
     * gaps C1: {@code Console.setscmd} has no unregister, so a single dispatcher per name routes to the current live
     * handler and is never re-registered). Teardown only marks each dead and drops it from {@link AddonManager}'s
     * {@code slashHandlers} registry (principle P2) — after which the dispatcher reports "no addon handles :name".
     * Since 086.1 the ending runs through {@link #slashSubs}, whose {@link Subs.Ended} hook this list is kept in
     * step by. Copy-on-write: a firing command may {@code sub:off()} itself.
     */
    public final List<LuaSlashCommand> slashCommands = new CopyOnWriteArrayList<LuaSlashCommand>();
    /**
     * Live client-only world ghosts owned by this addon ({@code hafen.vr():ghost():add}, V1): each is a virtual
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
     * Live client-only world sprites owned by this addon ({@code hafen.vr():sprite()}, R2): each is a custom PNG
     * (an {@link #images} texture) standing in the 3D world as a {@link haven.Gob} with no server id — the
     * non-{@code .res} sibling of a {@link #ghosts ghost}, on the same virtual-entity core (spec
     * {@code 17-custom-rendering.md} §2, SAFE-tier, D-034). Like ghosts there is no global dispatch/poll list (a
     * passive render node driven by the render tree's own tick); it lives only here. Teardown
     * ({@link VrApi#teardownSprites}) destroys each — removes its scene slot + disposes the quad geometry
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
     * Live client-only world 3D objects owned by this addon ({@code hafen.vr():object()}, R3): each is a custom
     * glTF model (a {@link #meshes} mesh) standing in the 3D world as a {@link haven.Gob} with no server id — the
     * mesh sibling of a {@link #sprites sprite} and a {@link #ghosts ghost}, on the same virtual-entity core (spec
     * {@code 18-custom-models-gltf.md}, SAFE-tier, D-034). Like ghosts/sprites there is no global dispatch/poll
     * list (a passive render node driven by the render tree's own tick); it lives only here. Teardown
     * ({@link VrApi#teardownObjects}) destroys each — removes its scene slot + disposes its engine
     * {@code Model}s (the shared mesh is freed by {@link AssetApi#teardownAssets}) — so a reload/disable/relogin
     * leaks nothing. Copy-on-write: a firing callback may create or destroy an object.
     */
    public final List<LuaObject> objects = new CopyOnWriteArrayList<LuaObject>();
    /**
     * Live <b>widgets this addon has stood in the 3D world</b> ({@code hafen.vr():widget()}, 044): each is one of
     * the addon's own UI surfaces drawn into an offscreen texture and hung on a virtual {@link haven.Gob} — the
     * fourth kind on the same client-only entity core as {@link #ghosts}, {@link #sprites} and {@link #objects},
     * and unprotected for the same reason (nothing here reaches the server; only where a button is drawn changed).
     * Teardown ({@link VrApi#teardownSurfaces}) destroys each, which puts the widget back where it stood from
     * (D-070's rule for the borrowed case, the default parent for an owned one) and frees the surface's texture,
     * so a reload/disable/relogin leaves neither an orphaned widget nor GPU memory. Copy-on-write: a firing
     * callback may stand or end one.
     */
    public final List<LuaWidgetEntity> surfaces = new CopyOnWriteArrayList<LuaWidgetEntity>();
    /**
     * <b>This addon's whole {@code hafen.vr()} is switched off</b> ({@code hafen.vr():visible(false)}, 043.4) — one
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
    public volatile boolean vrHidden;
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
     * Has this addon styled any single widget by hand ({@code widget:rule()}, 034.3)? Only a flag, not a list: the
     * styles are keyed by widget inside {@link Sheet}, whose map holds its widget keys <b>weakly</b> — a list here
     * would pin a closed window's widget tree in memory. Teardown ({@link FontApi#teardownFonts}) sweeps this
     * addon's entries out of that map along with its tree rules ({@link Sheet#forget}); this flag only tells it
     * whether the sweep is needed at all (so an addon that never styled anything costs no generation bump).
     */
    public volatile boolean skinNodes = false;
    /**
     * This addon's <b>Rule cache</b>: the interned {@code widget:rule()} handle per widget, and the one
     * {@link LuaRule} metatable a sheet rule shares with it. Weak on both axes, so styling a window pins
     * nothing once that window closes — the level itself lives in {@link Sheet}'s weak per-widget map, and a
     * handle is only a name for it.
     */
    final LuaRule.Cache styleRules = new LuaRule.Cache(this);

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
    LuaValue subMeta;

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
     */
    LuaValue timerMeta;

    /**
     * This addon's <b>client handles</b> ({@link OptionsHandle}) — {@code hafen.client():options()}, its six
     * subsystem handles ({@code interface} {@code video} {@code audio} {@code camera} {@code client}
     * {@code keybindings}) and {@code hafen.client():profiling()} — each built on first use and handed back
     * <b>by identity</b> ever after, so {@code opts:video() == opts:video()} and a draw callback reading one
     * allocates nothing. That is {@link Section}'s rule, which every other section has kept since it was
     * written; this namespace was converted to the section shape without the identity half.
     *
     * <p>Per addon like every other Lua value here (D-017), and lazily for the same reason as
     * {@link #subMeta}: an addon that never opens the settings never builds them. Unlocked for the same
     * reason too — two threads racing build two equal handles and one wins. They are <b>stateless proxies</b>
     * over the client's live preference stores, so there is nothing to invalidate and nothing to tear down:
     * the fields go with this {@link Addon}.
     */
    LuaValue clientOpts, clientInterface, clientVideo, clientAudio, clientCamera, clientClient,
             clientKeybindings, clientProfiling;

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
     * This addon's <b>Contents interning cache</b> ({@code item:contents()}), keyed on the <b>owning item
     * widget</b> — the same key {@link #items} uses, and for the same reason. What an item holds is a thing of
     * its own rather than a field of the Item, because a stack's insides are live items and a bucket's are a
     * stated line, and one object answers for both; see {@link LuaContents}. Weak-valued, dead with this
     * {@link Addon} on {@code :reload}/disable, with nothing to tear down.
     */
    final LuaContents.Cache contents = new LuaContents.Cache(this);

    final LuaQuest.Cache quests = new LuaQuest.Cache(this);
    final LuaCondition.Cache conditions = new LuaCondition.Cache(this);
    final LuaWound.Cache wounds = new LuaWound.Cache(this);
    final LuaCraft.Cache crafts = new LuaCraft.Cache(this);

    /**
     * This addon's <b>Widget interning cache</b> ({@code s:ui():root()}/{@code node(id)}/{@code at(x,y)}, spec
     * {@code 029-widget-oop}): the {@code Widget → Widget object} map and the per-addon metatable that make
     * {@code hafen.ui.at(m.x,m.y) == hafen.ui.at(m.x,m.y)} true and let {@code node:same()} be cut. Per-addon like
     * every other cache here — no Lua value crosses a sandbox boundary (D-017) and the whole cache dies with this
     * {@link Addon} on {@code :reload}/disable.
     *
     * <p>It is the one intern cache that is weak on <b>both</b> axes ({@code WeakHashMap<Widget,
     * WeakReference<LuaValue>>}), not the {@link #buffs}/{@link #meters} strong-key shape: over a whole widget tree
     * a strong key would pin every destroyed widget until the next queue drain, breaking the D-041 no-pin rule.
     * Nothing to tear down — this is an identity map over engine-owned widgets, deliberately NOT an owned-resource
     * registry like {@link #widgets} (which holds the addon's own drawn {@link AddonWidget}s).
     */
    final LuaWidget.Cache widgetObjs = new LuaWidget.Cache(this);

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
     * are not here at all — they live on the gob (see the note where {@code gobOverlays} used to be), which is
     * what makes an Overlay object a view of engine state rather than a record of ours.
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
     * Soft per-tick CPU-budget accounting (D-018 layer 2). {@link #tickLuaNanos} is the total time this
     * addon spent in Lua during the current engine tick (summed across its {@code Update}/timers/event
     * handlers by {@link AddonManager#callLua}); {@link #overBudgetStrikes} counts consecutive ticks over
     * the budget. {@link AddonManager#tick(haven.UI, double)} zeroes {@code tickLuaNanos} each tick and
     * {@link AddonManager#enforceSoftBudget()} evaluates the strikes — see {@link Sandbox#SOFT_BUDGET_NANOS}.
     */
    public long tickLuaNanos;
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
     * ADDITION inside the same finally, never a replacement. */
    final long[] catNanos = new long[CATS.length];
    final int[] catCalls = new int[CATS.length];

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

    /** Get (or create) this addon's scope by name. Created lazily, so an off-state {@code p:scope()} costs nothing. */
    Scope scope(String name) {
        Scope s = scopes.get(name);
        if(s == null)
            scopes.put(name, s = new Scope(name));
        return s;
    }

    /**
     * Close the frame: move this frame's accounting into the "last completed frame" fields and start the
     * next one at zero. Called from {@link AddonManager#tick(haven.UI, double)} immediately before
     * {@code tickLuaNanos} is zeroed, which is exactly the point at which that field holds the whole of the
     * previous frame (it accrues through the tick <b>and</b> the draw callbacks that follow it).
     */
    void profRoll(boolean probed) {
        profNanos = tickLuaNanos;
        profSumNanos += tickLuaNanos;
        profFrames++;
        if(tickLuaNanos > profPeakNanos)
            profPeakNanos = tickLuaNanos;
        // 019.7: a CONTROL frame ran with the category probes disarmed, so catNanos/catCalls and the scopes
        // are all zero for it -- while tickLuaNanos above is not, because the D-018 watchdog measures it
        // whether we are profiling or not. Rolling zeroes in would make one row in 64 read as "this addon
        // did nothing", so the split simply holds its last measured frame across a control frame.
        if(!probed)
            return;
        for(int i = 0; i < CATS.length; i++) {
            profCat[i] = catNanos[i];   catNanos[i] = 0;
            profCalls[i] = catCalls[i]; catCalls[i] = 0;
        }
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

    /** Drop every profiling figure ({@code p:reset()} and every arming of the switch). */
    void profReset() {
        profNanos = profPeakNanos = profSumNanos = 0;
        profFrames = 0;
        for(int i = 0; i < CATS.length; i++) {
            catNanos[i] = profCat[i] = 0;
            catCalls[i] = profCalls[i] = 0;
        }
        scopes.clear();
    }

    Addon(Manifest manifest, Path dir, Globals env) {
        this.manifest = manifest;
        this.dir = dir;
        this.env = env;
    }

    /** Run the addon's Lua files in manifest order. On the first failure, record it and stop. */
    void run() {
        for(String file : manifest.files) {
            try {
                Path fp = dir.resolve(file);
                String src = new String(Files.readAllBytes(fp), StandardCharsets.UTF_8);
                LuaValue chunk = env.load(src, "@" + manifest.id + "/" + file);
                Sandbox.arm(env);   // watchdog the file body too (D-018) — reset the budget per file
                chunk.call();
            } catch(Exception e) {
                error = file + ": " + e.getMessage();
                return;
            }
        }
    }
}
