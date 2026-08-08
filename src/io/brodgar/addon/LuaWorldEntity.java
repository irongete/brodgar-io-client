package io.brodgar.addon;

import java.awt.Color;

import haven.Coord2d;
import haven.Coord3f;
import haven.Gob;
import haven.MapView;
import haven.render.RenderTree;

import org.luaj.vm2.LuaValue;

/**
 * The shared base of a <b>client-only world entity</b> — a virtual {@link Gob} (id {@code -1} ⇒
 * {@code Gob.virtual}: no server id, never in {@code OCache}, invisible to the server and every read API) placed
 * on the MapView {@code basic} scene via {@link MapView#addClientGob} and given a <i>pluggable visual</i>. It is
 * the generalization of the V-series ghost core (spec {@code 16-virtual-entities.md}) into a reusable placement
 * engine (spec {@code 17-custom-rendering.md} §2, R-series): only the visual differs between subclasses —
 * <ul>
 *   <li>{@link LuaGhost} — a {@code .res} game model ({@code ResDrawable}), {@code hafen.vr():ghost()} (V1–V6);</li>
 *   <li>{@link LuaSprite} — a custom PNG as a world quad ({@code SprDrawable}), {@code hafen.vr():sprite()} (R2).</li>
 * </ul>
 * Everything else — the transform ({@link #rc}/{@link #a}), the look ({@link #alpha}/{@link #tint}/{@link #scale}),
 * pick-selectability ({@link #clickable}/{@link #onClick}), scene add/hide/show/destroy, the deferred-vs-immediate
 * publish, teardown, and the gizmo — is identical and lives here + in {@link AddonManager}'s {@code *Entity} scene
 * helpers. Because every entity is a {@link GhostGob} (whose {@code obstate} preps the click surface + look
 * states), a new visual only has to build a {@link haven.Drawable}; the transform/gizmo come for free.
 *
 * <p><b>Handle, not a ref.</b> A world entity has no server identity, so re-resolution is meaningless; it is
 * addressed by a bridge-owned <b>handle</b> (D-030), like a {@code hafen.ui():window()}. The common handle verbs
 * ({@code :move}/{@code :rotate}/{@code :scale}/{@code :alpha}/{@code :tint}/{@code :show}/{@code :hide}/
 * {@code :pos}/{@code :destroy}) are built by {@link AddonManager}'s {@code addEntityHandle}; each subclass adds
 * its own identity accessor ({@code :res()} for a ghost, {@code :image()} for a sprite).
 *
 * <p><b>Desired-state fields.</b> {@link #alpha}, {@link #tint}, {@link #scale}, {@link #clickable}, and
 * {@link #hidden} are the entity's <i>desired</i> state, guarded by {@code this}. They are mirrored onto the
 * {@link #gob}'s {@link GhostGob} fields when it exists and are read by the (possibly deferred) create at publish
 * time — so a verb that lands <i>before</i> the visual streams in still takes effect.
 *
 * <p><b>Ownership (P2).</b> The entity is bridge-owned: it lives only in its addon's owned-resource registry
 * ({@link Addon#ghosts} / {@link Addon#sprites}). There is no global tick/poll list, because it is a passive
 * render node driven by the render tree's own tick, not the addon tick loop. {@code Disable} / {@code :reload} /
 * relogin teardown ({@link AddonManager}'s {@code teardownGhosts}/{@code teardownSprites}) destroys each (removes
 * its scene slot + disposes the visual), leaking nothing. The {@link #dead} flag makes any late handle call — or a
 * deferred create landing after a destroy — a clean no-op.
 *
 * <p><b>Threading.</b> The handle methods, the {@code new*} facade, and teardown run on the UI thread (P5); only a
 * ghost's one-shot deferred create runs on a loader thread. {@code synchronized(this)} guards the transform/scene
 * publish so a deferred create never races a concurrent {@code :move}/{@code :destroy}.
 */
public abstract class LuaWorldEntity {
    final Addon owner;

    Coord2d rc;                    // target/current world position (login-relative), guarded by this
    double  a;                     // target/current facing (radians), guarded by this

    boolean clickable;             // V2: opt-in pick-selectability (mirrored onto the GhostGob's flag); guarded by this
    float   alpha = 1f;            // V3: desired opacity 0..1 (1 = opaque); mirrored onto the GhostGob; guarded by this
    Color   tint;                  // V3: desired colour-overlay tint, or null; mirrored onto the GhostGob; guarded by this
    float   scale = 1f;            // V6: desired uniform scale (1 = original size); mirrored onto the GhostGob; guarded by this
    boolean hidden;                // V3: :hide() removed the scene slot (gob kept); :show() re-adds it; guarded by this
    LuaValue onClick;              // V2: per-entity click callback fn(handle, button, x, y), or null; set at create

    long    followTgt;             // ANCHOR: the gob id this entity follows, or 0 = free (not anchored); set at create only
    Coord3f followOff;             // the world-space follow offset (x east, y north, z up), or null = none; live (:offset)

    /**
     * 044.3: <b>how this entity meets the viewer</b> — {@code "fixed"} (a world quad at {@link #a}),
     * {@code "camera"} (a world quad turned to the screen plane) or {@code "screen"} (a constant-size blit).
     * On the SHARED core because the two flat kinds — a sprite and a standing widget — pick between the same
     * three modes with the same machinery ({@link #visual}, {@code VrApi.setEntityFacing}); special-casing
     * either would be the larger change. A ghost and an object are models, so they carry the default and
     * expose no {@code :facing} verb at all. Guarded by {@code this}; a MODE string rather than a flag, so a
     * fourth would be a value and not a shape (D-190).
     */
    String facing = VrApi.FIXED;

    /**
     * This entity's identity <b>as seen from the gob it is anchored to</b> (043.3): an anchored entity is
     * surfaced read-only in {@code gob:overlay():list()}, where every member answers to a key, and a thing
     * standing in the world has no name of its own. So it gets a serial — monotonic for the client's life, so a
     * key is never reused and a stale handle never resolves onto a later entity. Free entities carry one too and
     * simply never show it.
     */
    final long eid = nextEid.incrementAndGet();

    private static final java.util.concurrent.atomic.AtomicLong nextEid =
        new java.util.concurrent.atomic.AtomicLong();

    Gob gob;                       // the client-only Gob, or null until the (possibly deferred) create publishes it
    RenderTree.Slot slot;          // its scene slot, or null until added / while hidden; removed on destroy/teardown
    MapView mv;                    // the MapView the gob was added to (so destroy removes it from THAT tick list)
    boolean dead;                  // destroyed (or torn down): every op becomes a no-op, deferred create undoes

    LuaValue handle;               // the stable Lua handle (so a list() returns the same object)

    LuaWorldEntity(Addon owner, Coord2d rc, double a) {
        this.owner = owner;
        this.rc = rc;
        this.a = a;
    }

    /**
     * Remove this entity from its addon's owned-resource registry (a {@link LuaGhost} from {@link Addon#ghosts},
     * a {@link LuaSprite} from {@link Addon#sprites}). Called by {@link AddonManager}'s {@code destroyEntity}
     * outside the entity monitor.
     */
    abstract void unregister();

    /**
     * <b>What this kind has to undo when it ends</b>, beyond the scene slot and the gob every kind shares —
     * called by {@code VrApi.destroyEntity} once the entity is out of the scene, on the UI thread and outside
     * the entity monitor. Nothing for the three kinds whose whole existence is their visual; a
     * {@link LuaWidgetEntity} puts its widget back where it stood from and frees its surface.
     */
    void destroyed() {
    }

    /**
     * <b>Build this entity's visual for facing {@code mode}</b> — the one place a kind says what it looks
     * like, so the create and {@code :facing(mode)} cannot disagree about it. Overridden by the two kinds
     * that have a facing ({@link LuaSprite}, {@link LuaWidgetEntity}); a ghost or an object is a model with
     * one visual and no facing verb, so it never reaches here.
     */
    haven.Drawable visual(Gob gob, String mode) {
        throw new IllegalStateException("a " + kind() + " has one visual and no facing");
    }

    /**
     * The entity's visual identity / filter key — a {@code .res} name for a ghost, the image path for a sprite;
     * may be {@code null}. Used by the {@code list([filter])} string-filter and the {@code :res()}/{@code :image()}
     * accessor.
     */
    abstract String visualName();

    /**
     * The owner-scoped event fired when this entity is clicked (V2 pick dispatch, {@link AddonManager#onGhostClick}):
     * {@code "GhostClicked"} for a ghost, {@code "SpriteClicked"} for a sprite. Paired with {@link #clickKey()}.
     */
    abstract String clickEvent();

    /**
     * <b>Which collection of {@code hafen.vr()} this entity belongs to</b> — {@code "ghost"}, {@code "sprite"} or
     * {@code "object"}. One word, three readers: it is the {@code ev} field name its {@link #clickEvent()}
     * delivers the handle under, the {@code ov:kind()} of the read-only entry an anchored entity gets in
     * {@code gob:overlay():list()} (043.3), and the collection every refusal on that entry names.
     */
    abstract String kind();

    /**
     * The {@code ev} field name under which the clicked entity's handle is delivered in its {@link #clickEvent()} —
     * a ghost is a ghost, a sprite is a sprite: the field is named for the kind, so there is one word, not two.
     */
    final String clickKey() {
        return kind();
    }

    /**
     * The key this entity answers to in {@code gob:overlay():list()} while it is anchored to a gob (043.3) —
     * synthetic, because a thing standing in the world has no name of its own, and prefixed so it can never be
     * mistaken for (or collide with) a key an addon chose for one of its own overlays.
     */
    final String overlayKey() {
        return "vr#" + Long.toString(eid);
    }
}
