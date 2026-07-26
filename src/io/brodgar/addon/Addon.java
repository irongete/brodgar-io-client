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
     * 2a). Each entry is the {@link LuaWidget} content; {@link LuaWidget#kill()} destroys its <i>root</i>
     * (the window chrome, or the widget itself), which cascades to children — so the addon's UI vanishes
     * cleanly on reload/disable.
     */
    public final List<LuaWidget> widgets = new CopyOnWriteArrayList<LuaWidget>();
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
     * addon wants gob overlays at all (see {@link AddonManager#teardown}).
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
     * Live global hotkeys owned by this addon ({@code hafen.key.bind}, Phase 2e-2): each pairs a client
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
     * Live adopted widget models owned by this addon ({@code hafen.ui.adopt}, Phase 3b): each wraps a live
     * server-bound widget (by id) so the addon can hide it as a headless model + present a custom view (D-009).
     * They live in a flat global list in {@link AddonManager} (polled each tick for item add/remove + server
     * destroy); teardown marks each dead, drops it from that list, and <b>un-hides</b> any widget the addon had
     * hidden so disabling restores the stock UI (spec 08). Copy-on-write: a firing lifecycle callback may adopt
     * or drop a model mid-poll.
     */
    public final List<LuaModel> models = new CopyOnWriteArrayList<LuaModel>();
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
     * ({@link AddonManager#teardownSprites}) destroys each — removes its scene slot + disposes the quad geometry
     * (the shared {@code TexI} is freed by {@link AddonManager#teardownImages}) — so a reload/disable/relogin
     * leaks nothing. Copy-on-write: a firing callback may create or destroy a sprite.
     */
    public final List<LuaSprite> sprites = new CopyOnWriteArrayList<LuaSprite>();
    /**
     * Live custom images owned by this addon ({@code hafen.render.image}, R1): each is a PNG decoded from the
     * addon's own folder into a {@link haven.TexI} GPU texture — a client-only render asset that is NOT an
     * engine {@code .res} (SAFE-tier, D-034). Like the hook lists there is no global dispatch/poll list; an
     * image is a passive texture drawn on demand through the {@code g} wrapper, so it lives only here. Teardown
     * ({@link AddonManager#teardownImages}) disposes each ({@code TexI.dispose()} frees the GL texture) so a
     * reload/disable/relogin leaks no GPU resource — the same guarantee as windows, overlays, and ghosts.
     * Copy-on-write: a firing callback may load or {@code :dispose()} an image.
     */
    public final List<LuaImage> images = new CopyOnWriteArrayList<LuaImage>();
    /**
     * Live custom 3D models owned by this addon ({@code hafen.render.model}, R3): each is a glTF mesh
     * ({@code .glb}/{@code .gltf}) decoded from the addon's own folder into baked, H&amp;H-local geometry (a
     * {@link Gltf}) — a client-only render asset that is NOT an engine {@code .res} (SAFE-tier, D-034). Like
     * {@link #images} there is no global dispatch/poll list; a mesh is a passive geometry source that
     * {@link #objects} build engine {@code Model}s from on demand, so it lives only here. Teardown
     * ({@link AddonManager#teardownMeshes}) marks each dead and drops it (frees the CPU geometry for GC; its
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
     * ({@link AddonManager#teardownObjects}) destroys each — removes its scene slot + disposes its engine
     * {@code Model}s (the shared mesh is freed by {@link AddonManager#teardownMeshes}) — so a reload/disable/relogin
     * leaks nothing. Copy-on-write: a firing callback may create or destroy an object.
     */
    public final List<LuaObject> objects = new CopyOnWriteArrayList<LuaObject>();
    /**
     * Live modal mouse-drag captures owned by this addon ({@code hafen.hook.grab}, V5): each is a
     * {@link LuaMouseGrab} widget on {@code ui.root} that forwards mouse move/up to Lua while capturing the drag
     * (the gizmo's drag primitive). Normally transient (one per active drag) and self-releasing on mouse-up;
     * teardown ({@link AddonManager#teardownMouseGrabs}) releases any still-active grab so a {@code :reload}/disable
     * mid-drag drops the {@code UI.Grab} and unlinks the widget, leaking nothing. Copy-on-write: releasing removes.
     */
    public final List<LuaMouseGrab> mouseGrabs = new CopyOnWriteArrayList<LuaMouseGrab>();
    /**
     * Live in-flight HTTP requests owned by this addon ({@code hafen.http.get}/{@code post}, N2a): each is a
     * {@link LuaHttpRequest} submitted to {@link AddonManager}'s shared bounded pool, whose result is drained on
     * the tick and delivered to the request's callback (the gob-delta async pattern). Bridge-owned like every
     * other owned resource; teardown ({@link AddonManager#teardownRequests}) marks each dead so a
     * {@code :reload}/disable/relogin cancels any in-flight request — its pool result is discarded on drain and
     * the callback never fires (D-037 §3.3). Copy-on-write: the drain removes a completed request while a
     * firing callback may start another.
     */
    public final List<LuaHttpRequest> requests = new CopyOnWriteArrayList<LuaHttpRequest>();

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
