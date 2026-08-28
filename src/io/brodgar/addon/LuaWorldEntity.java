package io.brodgar.addon;

import java.awt.Color;

import haven.Coord2d;
import haven.Coord3f;
import haven.Gob;
import haven.MapView;
import haven.UI;
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
 * <p><b>{@link #grounded} is not one of them</b> (044.9) — it is what the WORLD says rather than what the addon
 * asked for, and it is the third boolean {@code VrApi.shows} ANDs: an entity is in the scene only while the
 * character on screen can see the place it stands in. Nothing the addon writes ever touches it, which is why
 * {@code :visible()} keeps reading back exactly what it was told while the thing itself waits out a walk to the
 * far side of the map. Since 045.1 it is false whenever there is no coordinate at all ({@link #rc} null) — the
 * same sentence said about a place this session cannot locate rather than one it can see is bare — and since
 * 075.3 it answers for an anchored entity too, whose place is an object the drawn character either has in view
 * or has not.
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

    /**
     * <b>The SESSION coordinate this entity is drawn at — derived, and nullable</b> (045.1). For a free entity
     * it is {@link #anchorGrid}/{@link #agx}/{@link #agy} resolved against this session's map, recomputed
     * whenever the world moves under it, and {@code null} while this session cannot locate that grid at all
     * (every overworld place, while the player is in a cave). Since 045.2 it may be null <b>from birth</b>: a
     * place that has not been reached is a legal place to stand something, and the entity simply waits for it.
     * For an anchored one it is the target's point at create and is never null. <b>The invariant is {@code rc == null ⇒ !grounded}</b> (see {@link #grounded}),
     * which is what makes the null safe: nothing on a scene path is reached without {@code VrApi.shows}, and
     * that is false the moment the coordinate is gone. Guarded by {@code this}.
     */
    Coord2d rc;
    double  a;                     // target/current facing (radians), guarded by this

    /**
     * <b>WHERE A FREE ENTITY IS, durably</b> (045.1): the server's own grid id plus the offset within that grid
     * — the same pair a {@code Position}'s {@code :info()} publishes, and the only form of a place that
     * survives the map being dropped. It is what the entity <i>holds</i>; {@link #rc} is what it currently
     * derives from it. Meaningless when {@link #followTgt} is set, because an anchored entity's place is its
     * gob's and has nothing of its own to keep. Plain fields rather than a {@code LuaPosition.Anchor}, so the
     * word "anchor" here can never be read as the other one (the gob an entity follows). Guarded by
     * {@code this}; written at create and by {@code :position(p)}, never derived from {@link #rc}.
     */
    long    anchorGrid;
    double  agx, agy;

    boolean clickable;             // V2: opt-in pick-selectability (mirrored onto the GhostGob's flag); guarded by this
    float   alpha = 1f;            // V3: desired opacity 0..1 (1 = opaque); mirrored onto the GhostGob; guarded by this
    Color   tint;                  // V3: desired colour-overlay tint, or null; mirrored onto the GhostGob; guarded by this
    float   scale = 1f;            // V6: desired uniform scale (1 = original size); mirrored onto the GhostGob; guarded by this
    boolean hidden;                // V3: :hide() removed the scene slot (gob kept); :show() re-adds it; guarded by this
    boolean grounded = true;       // 044.9/075.3: can the DRAWN character see where it stands? the ground under a free
                                   //   one (false whenever rc == null, 045.1), the object under an anchored one; guarded by this
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

    /**
     * <b>The tree whose scene it is standing in right now</b> — the {@code ui} of the {@link MapView} its gob
     * was added to. An entity IS a client-only gob in one scene, so this is not a guess about which session it
     * belongs to but the thing itself. <b>It is not where the entity belongs</b> (075.3): the entity belongs to
     * the world, and this follows the screen — rewritten every time {@code VrApi.rehome} rebuilds the visual for
     * the character now looking, and {@code null} while it stands in no scene at all.
     */
    UI ui;

    /**
     * 075.3: <b>a create is still streaming in</b> on a loader thread (a ghost's resource), so this entity has
     * no visual yet and the one that is coming names the scene it was started for. The pass that re-homes an
     * entity into the scene being drawn leaves it alone while this is set, and the create raises the pass's own
     * flag when it lands — otherwise the two would both publish a gob and one of them would be lost. Guarded by
     * {@code this}; only a ghost ever sets it.
     */
    boolean streaming;

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
     * <b>Build this entity's visual on a fresh gob</b> (075.3) — the same picture, in another character's
     * scene. An entity holds a place in the world rather than a session, so the screen moving to a character
     * who can see that place rebuilds it there ({@code VrApi.rehome}); a {@code Gob} carries the {@code Glob}
     * it asks for the tile under itself, so a rebuild is what "the same thing, drawn from over there" is.
     * Defaults to the facing form, which is the one the two flat kinds already answer; the two model kinds
     * override it with the one visual they have. May throw {@link haven.Loading} — a resource that is not in
     * the pool right now is a retry, not a loss.
     */
    haven.Drawable visual(Gob gob) {
        return visual(gob, facing);
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
     * <b>Is it in the scene being drawn right now?</b> — what {@code :drawn()} answers and what the {@code drawn}
     * key of {@code :info()} carries. Four of the five kinds are a client-only {@link Gob} in the render tree, so
     * the answer is their scene slot; a patch is a ground overlay registered in an {@code MCache}, so the answer
     * is whether it is registered in the one being drawn. One question with one name, asked of whichever half a
     * kind is made of. Caller holds the entity monitor.
     */
    boolean drawn() {
        return !dead && (slot != null);
    }

    /**
     * <b>Has a place for this kind a HEIGHT?</b> Four of the five stand UP, so where one sits relative to the gob
     * it follows is three numbers and {@code :offset(x, y, z)} lifts it off the ground. A patch lies ON the
     * terrain — that is what a patch is, and one held above the ground is the other mechanism entirely (118,
     * out of scope) — so its offset is two numbers, a {@code z} is refused naming why, and the {@code offset} it
     * publishes in {@code :info()} has no {@code z} either.
     */
    boolean height() {
        return true;
    }

    /**
     * <b>Does this kind answer the click vocabulary</b> — {@code :clickable(b)}, {@code :onClick(fn)} and the
     * {@code clickable} key of {@code :info()}? Every kind that is a gob does: it renders into the clickmap and
     * the engine's own pick pass reaches it. A patch is not a gob and is in no pick at all; it is hit-tested
     * against its own ring instead (118.3), which is what lifts this.
     */
    boolean clicks() {
        return true;
    }

    /**
     * <b>Which collection of {@code hafen.vr()} this entity belongs to</b> — {@code "ghost"}, {@code "sprite"} or
     * {@code "object"}. One word, three readers: it is the {@code ev} field name its {@link #clickEvent()}
     * delivers the handle under, the {@code ov:kind()} of the read-only entry an anchored entity gets in
     * {@code gob:overlay():list()} (043.3), and the collection every refusal on that entry names.
     */
    abstract String kind();

    /**
     * <b>This kind's own contribution to {@code e:info()}</b> (098). The ten readers every entity shares are
     * written by {@code VrApi.entityHandle}; each subclass adds the one or two verbs only it answers, under
     * the key its verb is spelled with, so the snapshot and the vocabulary can never name a thing differently.
     *
     * <p>Only READERS belong here. {@code panel:screen(x, y)} is a projection that takes a point, so it has
     * nothing to put in a snapshot; {@code :onClick(fn)} is a callback slot rather than a fact.
     */
    abstract void infoInto(org.luaj.vm2.LuaTable t);

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
