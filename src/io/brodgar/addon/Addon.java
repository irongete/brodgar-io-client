package io.brodgar.addon;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * One loaded addon: its {@link Manifest}, folder, Lua environment, and load status. Per-addon
 * environments give each addon its own globals (sandbox hardening arrives in a later phase).
 *
 * <p>The {@link #subs} and {@link #timers} lists are the addon's <b>owned-resource registry</b>
 * (principle P2): everything it creates through the facade is tracked here so the engine can tear
 * it down cleanly on reload/disable. They are copy-on-write because a running handler may
 * unsubscribe or cancel while the engine iterates them.
 */
public final class Addon {
    public final Manifest manifest;
    public final Path dir;
    public final Globals env;
    public String error;   // null if the addon loaded cleanly

    /** Live event subscriptions owned by this addon (see {@link AddonManager.Sub}). */
    public final List<AddonManager.Sub> subs = new CopyOnWriteArrayList<AddonManager.Sub>();
    /** Live timers owned by this addon (see {@link AddonManager.Timer}). */
    public final List<AddonManager.Timer> timers = new CopyOnWriteArrayList<AddonManager.Timer>();
    /**
     * Live custom UI widgets/windows owned by this addon ({@code hafen.ui.widget}/{@code window}, Phase
     * 2a). Each entry is the {@link AddonWidget} content; {@link AddonWidget#kill()} destroys its <i>root</i>
     * (the window chrome, or the widget itself), which cascades to children — so the addon's UI vanishes
     * cleanly on reload/disable.
     */
    public final List<AddonWidget> widgets = new CopyOnWriteArrayList<AddonWidget>();
    /**
     * Live HUD overlays owned by this addon ({@code hafen.ui.overlay}, Phase 2b): draw callbacks painted on
     * top of the HUD each frame. The engine iterates this list to paint (so clearing it stops the overlays
     * immediately) — no widget, nothing else to release, so teardown is just {@code clear()}.
     */
    public final List<AddonManager.HudOverlay> hudOverlays = new CopyOnWriteArrayList<AddonManager.HudOverlay>();
    /**
     * Live world-space gob overlays owned by this addon ({@code hafen.ui.gobOverlay}, Phase 2b): a
     * filter + draw callback painted over each matching gob (via a shared {@link LuaGobOverlay} attrib per
     * gob). Clearing this list stops the overlays immediately; the engine detaches the idle attribs once no
     * addon wants gob overlays at all (see {@link AddonRegistry#teardown}).
     */
    public final List<AddonManager.GobOverlay> gobOverlays = new CopyOnWriteArrayList<AddonManager.GobOverlay>();
    /**
     * Live input/gesture hooks owned by this addon ({@code hafen.hook.input}, Phase 2c): pre-hooks registered
     * on a client widget via {@link haven.Widget#listen}. Teardown deafens each ({@link haven.Widget#deafen})
     * and marks it dead, so a {@code :reload}/disable (which keeps the engine widgets alive) never leaves a
     * listener firing into a torn-down env (principle P2). Copy-on-write: a firing hook may {@code :remove()}.
     */
    public final List<LuaInputHook> hooks = new CopyOnWriteArrayList<LuaInputHook>();
    /**
     * Live action hooks owned by this addon ({@code hafen.hook.action}, Phase 2d): pre-hooks on the outbound
     * {@link haven.UI#wdgmsg} choke point, keyed by action name in {@link AddonManager}'s dispatch map.
     * Teardown marks each dead and unregisters it from that map (principle P2) — unlike an input hook there is
     * no widget to deafen; the hook lives only in the engine's dispatcher. Copy-on-write: a firing hook may
     * {@code :remove()} itself while the dispatcher iterates the per-action list.
     */
    public final List<LuaActionHook> actionHooks = new CopyOnWriteArrayList<LuaActionHook>();
    /**
     * Live message hooks owned by this addon ({@code hafen.hook.message}, Phase 2e): pre-hooks on the inbound
     * {@link haven.UI#uimsg} choke point, keyed by message name in {@link AddonManager}'s dispatch map.
     * Teardown marks each dead and unregisters it from that map (principle P2) — like an action hook (and
     * unlike an input hook) there is no widget to deafen; the hook lives only in the engine's dispatcher, which
     * a {@code :reload} keeps alive while the Lua layer rebuilds. Copy-on-write: a firing hook may
     * {@code :remove()} itself while the dispatcher iterates the per-message list.
     */
    public final List<LuaMessageHook> messageHooks = new CopyOnWriteArrayList<LuaMessageHook>();
    /**
     * Live global hotkeys owned by this addon ({@code keybindings:register}, Phase 2e-2): each pairs a client
     * {@link haven.KeyBinding} with a Lua handler, dispatched from {@link AddonRoot#globtype} via the engine's
     * {@code GlobKeyEvent} seam. Teardown marks each dead and drops it from {@link AddonManager}'s global
     * dispatch list (principle P2) — like an action/message hook there is no widget to deafen. The
     * {@code KeyBinding} itself is process-global + persistent and is deliberately <b>not</b> removed (that is how
     * the client remembers a re-mapped key across reloads/sessions). Copy-on-write: a firing hotkey may
     * {@code :remove()} itself while the dispatcher iterates.
     */
    public final List<LuaKeyBind> keybinds = new CopyOnWriteArrayList<LuaKeyBind>();
    /**
     * Live widget-creation observers owned by this addon ({@code hafen.ui.onWidgetCreate}, Phase 3a): each runs a
     * Lua handler for every server widget as it is placed into the tree (spec 08's creation seam). They live in a
     * flat global dispatch list in {@link AddonManager} (an observer watches EVERY creation, not one keyed
     * target); teardown marks each dead and drops it from that list (principle P2) — like an action/message hook
     * there is no widget to deafen. Copy-on-write: a firing observer may {@code :remove()} itself mid-dispatch.
     */
    public final List<LuaWidgetObserver> widgetObservers = new CopyOnWriteArrayList<LuaWidgetObserver>();
    /**
     * Live replaced widget models owned by this addon ({@code hafen.ui.replace}, Phase 3c — {@code hafen.ui.adopt}
     * was deleted by 029.2, so this is now reached only through a replacer): each wraps a live
     * server-bound widget (by id) so the addon can hide it as a headless model + present a custom view (D-009).
     * They live in a flat global list in {@link AddonManager} (polled each tick for item add/remove + server
     * destroy); teardown marks each dead, drops it from that list, and <b>un-hides</b> any widget the addon had
     * hidden so disabling restores the stock UI (spec 08). Copy-on-write: a firing lifecycle callback may adopt
     * or drop a model mid-poll.
     */
    public final List<LuaModel> models = new CopyOnWriteArrayList<LuaModel>();
    /**
     * Native widgets this addon has <b>hidden</b> with {@code widget:hide()} (029.2) — the restore list that
     * replaced {@code hafen.ui.adopt}. Hiding a widget the addon does not own is the one write that reaches the
     * client's own UI, so it is bridge-owned like everything else: {@link UiApi#teardownHidden} replays each
     * entry's ORIGINAL visibility on {@code :reload}/disable, guarded on the widget still being the same live one
     * (so a relog — which rebinds {@code ui} before the teardown loop — correctly skips it while a same-session
     * {@code :reload} performs it). {@code widget:show()} drops its own entry: nothing left to undo. Distinct from
     * {@link #models}, which is {@code replace}'s bookkeeping and hides the enclosing <i>wrapper</i> instead —
     * the two rules are deliberately not merged. Copy-on-write like the other owned lists.
     */
    public final List<LuaWidget.Hidden> hiddenNative = new CopyOnWriteArrayList<LuaWidget.Hidden>();
    /**
     * Container subscriptions this addon holds ({@code widget:onItemAdded/:onItemRemoved/:onDestroy}, 029.3) — one
     * entry per watched widget, created by the FIRST callback set on it and dropped when the last one is cleared
     * (the {@code hasSub} gate: an unsubscribed widget is never polled). They also live in a flat global list in
     * {@link UiApi}, diffed each tick for {@code WItem} add/remove and for the widget's death; teardown
     * ({@link UiApi#teardownWatches}) drops both copies without firing anything — a {@code :reload}/disable is not
     * a destroy. Copy-on-write: a firing callback may subscribe or unsubscribe mid-poll.
     */
    public final List<LuaWidget.Watch> itemWatches = new CopyOnWriteArrayList<LuaWidget.Watch>();
    /**
     * Live widget replacers owned by this addon ({@code hafen.ui.replace}, Phase 3c): each watches for a server
     * widget matching a descriptor (by type/context/caption), then adopts it as a hidden {@link LuaModel} and
     * hands the addon a custom view — "wrap, don't reimplement" (D-009). They live in a flat global dispatch list
     * in {@link AddonManager} (consulted at widget placement, like {@link #widgetObservers}) and also scan the live
     * tree once at registration to catch an already-open target (the {@code :reload} case). Teardown marks each dead
     * and drops it from that list (principle P2); the adopted models are un-hidden by {@link AddonManager}'s model
     * teardown and the views destroyed with the rest of {@link #widgets}. Copy-on-write: a firing replacer may
     * {@code :remove()} itself mid-dispatch.
     */
    public final List<LuaReplacer> replacers = new CopyOnWriteArrayList<LuaReplacer>();
    /**
     * Live addon slash commands owned by this addon ({@code hafen.slash.register}, gap subsystem A11): each routes
     * a console command {@code :name} to a Lua handler. Unlike the hook lists, the engine's {@link haven.Console}
     * dispatcher for a name is <b>engine-lifetime</b> and is deliberately <b>not</b> removed on teardown (coverage-
     * gaps C1: {@code Console.setscmd} has no unregister, so a single dispatcher per name routes to the current live
     * handler and is never re-registered). Teardown only marks each dead and drops it from {@link AddonManager}'s
     * {@code slashHandlers} registry (principle P2) — after which the dispatcher reports "no addon handles :name".
     * Copy-on-write: a firing command may {@code :remove()} itself.
     */
    public final List<LuaSlashCommand> slashCommands = new CopyOnWriteArrayList<LuaSlashCommand>();
    /**
     * Live client-only world ghosts owned by this addon ({@code hafen.ghost.new}, V1): each is a virtual
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
     * Live client-only world sprites owned by this addon ({@code hafen.render.sprite}, R2): each is a custom PNG
     * (an {@link #images} texture) standing in the 3D world as a {@link haven.Gob} with no server id — the
     * non-{@code .res} sibling of a {@link #ghosts ghost}, on the same virtual-entity core (spec
     * {@code 17-custom-rendering.md} §2, SAFE-tier, D-034). Like ghosts there is no global dispatch/poll list (a
     * passive render node driven by the render tree's own tick); it lives only here. Teardown
     * ({@link RenderApi#teardownSprites}) destroys each — removes its scene slot + disposes the quad geometry
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
     * Copy-on-write: a firing callback may load or {@code :dispose()} an image.
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
     * Copy-on-write: a firing callback may load or {@code :dispose()} a model.
     */
    public final List<LuaMesh> meshes = new CopyOnWriteArrayList<LuaMesh>();
    /**
     * Live client-only world 3D objects owned by this addon ({@code hafen.render.object}, R3): each is a custom
     * glTF model (a {@link #meshes} mesh) standing in the 3D world as a {@link haven.Gob} with no server id — the
     * mesh sibling of a {@link #sprites sprite} and a {@link #ghosts ghost}, on the same virtual-entity core (spec
     * {@code 18-custom-models-gltf.md}, SAFE-tier, D-034). Like ghosts/sprites there is no global dispatch/poll
     * list (a passive render node driven by the render tree's own tick); it lives only here. Teardown
     * ({@link RenderApi#teardownObjects}) destroys each — removes its scene slot + disposes its engine
     * {@code Model}s (the shared mesh is freed by {@link AssetApi#teardownAssets}) — so a reload/disable/relogin
     * leaks nothing. Copy-on-write: a firing callback may create or destroy an object.
     */
    public final List<LuaObject> objects = new CopyOnWriteArrayList<LuaObject>();
    /**
     * Live modal mouse-drag captures owned by this addon ({@code hafen.hook.grab}, V5): each is a
     * {@link LuaMouseGrab} widget on {@code ui.root} that forwards mouse move/up to Lua while capturing the drag
     * (the gizmo's drag primitive). Normally transient (one per active drag) and self-releasing on mouse-up;
     * teardown ({@link HookApi#teardownMouseGrabs}) releases any still-active grab so a {@code :reload}/disable
     * mid-drag drops the {@code UI.Grab} and unlinks the widget, leaking nothing. Copy-on-write: releasing removes.
     */
    public final List<LuaMouseGrab> mouseGrabs = new CopyOnWriteArrayList<LuaMouseGrab>();
    /**
     * Live in-flight HTTP requests owned by this addon ({@code hafen.http.get}/{@code post}, N2a): each is a
     * {@link LuaHttpRequest} submitted to {@link HttpApi}'s shared bounded pool, whose result is drained on
     * the tick and delivered to the request's callback (the gob-delta async pattern). Bridge-owned like every
     * other owned resource; teardown ({@link HttpApi#teardownRequests}) marks each dead so a
     * {@code :reload}/disable/relogin cancels any in-flight request — its pool result is discarded on drain and
     * the callback never fires (D-037 §3.3). Copy-on-write: the drain removes a completed request while a
     * firing callback may start another.
     */
    public final List<LuaHttpRequest> requests = new CopyOnWriteArrayList<LuaHttpRequest>();
    /**
     * Named client-surface font overrides this addon has installed ({@code hafen.font.setFont(scope, h)}, F-series):
     * the {@code scope} names (e.g. {@code "default"}) it currently overrides in the {@link haven.Fonts} provider.
     * Owner-tagged in the provider by <b>this</b> {@code Addon} instance (spec 05); teardown
     * ({@link FontApi#teardownFonts}) removes them from every scope stack ({@code Fonts.removeOwner(this)} bumps the
     * generation counter → routed sites revert to the stock foundry) and clears the list, so a reload/disable
     * restores the stock UI. Copy-on-write for symmetry (font ops run on the UI thread; a {@code :reload} mid-op
     * cannot happen).
     */
    public final List<String> fontOverrides = new CopyOnWriteArrayList<String>();
    /**
     * Has this addon installed any <b>per-instance</b> font override ({@code node:setFont(h)}, F5)? Only a flag, not
     * a list: the overrides are keyed by widget inside the {@link haven.Fonts} provider, whose registry holds its
     * widget keys <b>weakly</b> — a list here would pin a closed window's widget tree in memory. Teardown
     * ({@link FontApi#teardownFonts}) sweeps this addon's entries out of that registry with the same
     * {@code Fonts.removeOwner(this)} call the named scopes use; this flag only tells it whether the sweep is
     * needed at all (so an addon that never touched fonts costs no generation bump).
     */
    public volatile boolean fontNodes = false;

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
     * This addon's <b>Kin interning cache</b> ({@code hafen.kin(idOrName)}, spec {@code 020-kin-oop}): the
     * weak-valued {@code buddy id → Kin object} map, its {@link java.lang.ref.ReferenceQueue}, and the two
     * per-addon metatables (the Kin one and the roster's). Same contract as {@link #gobs} — per-addon so no
     * Lua value crosses a sandbox boundary (D-017) and the whole cache dies with this {@link Addon} on
     * {@code :reload}/disable; nothing to tear down (weak entries, and a handle holds only an int id). It
     * carries the {@link Addon} because the gated Kin verbs check the {@code actions} permission against it.
     */
    final LuaKin.Cache kins = new LuaKin.Cache(this);

    /**
     * This addon's <b>action-bar Slot interning cache</b> ({@code hafen.actionbar(n)}, spec
     * {@code 021-actionbar-oop}): the weak-valued {@code slot index → Slot object} map, its
     * {@link java.lang.ref.ReferenceQueue} and the per-addon metatable. Same contract as {@link #gobs} and
     * {@link #kins} — per-addon so no Lua value crosses a sandbox boundary (D-017) and the whole cache dies
     * with this {@link Addon} on {@code :reload}/disable; nothing to tear down (weak entries, and a handle
     * holds only the int index). It carries the {@link Addon} because the gated {@code slot:use} verb checks
     * the {@code actions} permission against it.
     */
    final LuaSlot.Cache slots = new LuaSlot.Cache(this);

    /**
     * This addon's <b>action-menu Pagina interning cache</b> ({@code hafen.menugrid(key)}, spec
     * {@code 023-menugrid-oop}): the weak-valued {@code resource name → Pagina object} map, its
     * {@link java.lang.ref.ReferenceQueue} and the two per-addon metatables (the Pagina one and the
     * catalogue's). Same contract as {@link #gobs}, {@link #kins} and {@link #slots} — per-addon so no Lua
     * value crosses a sandbox boundary (D-017) and the whole cache dies with this {@link Addon} on
     * {@code :reload}/disable; nothing to tear down (weak entries, and a handle holds only the resource name).
     */
    final LuaPagina.Cache paginae = new LuaPagina.Cache(this);

    /**
     * This addon's <b>Sound interning cache</b> ({@code hafen.sound(name)}, spec {@code 024-audio-oop}): the
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
     * This addon's <b>Buff interning cache</b> ({@code hafen.buff(needle)}, spec {@code 025-buffs-oop}): the
     * weak-valued {@code Buff widget → Buff object} map, its {@link java.lang.ref.ReferenceQueue} and the
     * per-addon metatable. Same contract as {@link #gobs}, {@link #kins}, {@link #slots}, {@link #paginae}
     * and {@link #sounds} — per-addon so no Lua value crosses a sandbox boundary (D-017) and the whole cache
     * dies with this {@link Addon} on {@code :reload}/disable. The <b>key is the widget's identity</b> (a
     * res name is neither unique nor stable under a {@code "ch"} update), which makes it the one cache in
     * the series with strong keys — bounded only because every access drains the queue; see {@link LuaBuff}.
     */
    final LuaBuff.Cache buffs = new LuaBuff.Cache(this);

    /**
     * This addon's <b>Meter interning cache</b> ({@code hafen.meter(needle)}, spec {@code 027-meters-oop}): the
     * weak-valued {@code IMeter widget → Meter object} map, its {@link java.lang.ref.ReferenceQueue} and the
     * per-addon metatable. Same contract as {@link #gobs}, {@link #kins}, {@link #slots}, {@link #paginae},
     * {@link #sounds} and {@link #buffs} — per-addon so no Lua value crosses a sandbox boundary (D-017) and the
     * whole cache dies with this {@link Addon} on {@code :reload}/disable. Like {@link #buffs} the <b>key is the
     * widget's identity</b> (the meter's res name is server-published and not guaranteed unique across the HUD
     * slot), so the keys are strong — bounded only because every access drains the queue; see {@link LuaMeter}.
     */
    final LuaMeter.Cache meters = new LuaMeter.Cache(this);

    /**
     * This addon's <b>Widget interning cache</b> ({@code hafen.ui.root()}/{@code node(id)}/{@code at(x,y)}, spec
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
     * The single {@code hafen.player()} object for this addon ({@code Player} by composition, D-046) — built
     * lazily by {@code CharApi.installPlayer} and cached so {@code hafen.player() == hafen.player()}. Per-addon
     * for the same reason as {@link #gobs}.
     */
    LuaValue playerObj;

    /**
     * The {@code hafen.store} proxy table (saved variables, Phase 1e). Holds one Lua table per
     * declared saved variable plus the {@code flush} function. Populated in
     * {@link AddonManager#installHafen}; the engine reads it on flush. {@code null} until installed.
     */
    public LuaTable store;
    /** Write-skip caches: the last JSON serialized for each scope, so an unchanged flush skips disk I/O. */
    public String lastCharJson, lastAccountJson;

    /**
     * Soft per-tick CPU-budget accounting (D-018 layer 2). {@link #tickLuaNanos} is the total time this
     * addon spent in Lua during the current engine tick (summed across its {@code OnUpdate}/timers/event
     * handlers by {@link AddonManager#callLua}); {@link #overBudgetStrikes} counts consecutive ticks over
     * the budget. {@link AddonManager#tick(double)} zeroes {@code tickLuaNanos} each tick and
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
     * next one at zero. Called from {@link AddonManager#tick(double)} immediately before
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
