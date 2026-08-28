package io.brodgar.addon;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

import haven.Coord2d;
import haven.Glob;
import haven.MCache;
import haven.MapView;

import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

/**
 * A client-only <b>patch</b> (118) — the Java half of {@code hafen.vr():patch():add(ring, anchor)}: a convex
 * ring of {@link LuaPosition}s lying exactly on the terrain, occluded by whatever stands on it. The fifth kind
 * of {@code hafen.vr()}, and the first that lies <b>down</b>: the four before it — a prop, a picture, a model,
 * a window — all stand up.
 *
 * <p><b>It is the one kind that is not a {@link haven.Gob}.</b> The drawn terrain surface cannot be reproduced
 * from outside ({@code MapMesh.MapSurface} is per cut and per tiler), so independently tessellated geometry
 * cannot land on it: lifted it floats, coplanar it speckles, and a depth bias big enough to beat the ground at
 * distance also beats the character standing on it. A patch therefore goes through the engine's own ground
 * overlay — {@link PatchOverlay}, registered with {@code MCache.add} — which re-lays each masked tile over the
 * ground's own vertices, and carves its silhouette per fragment ({@link PatchCarve}) rather than from the tile
 * mask. Everything the shared core does through {@link #gob} and {@link #slot} a patch does through
 * {@link #ol} and {@link #laid} instead; everything else — the anchors, the durable place, the ground test,
 * the visibility switches, the teardown — is the shared core unchanged.
 *
 * <p><b>The ring is held as offsets, not as points.</b> What {@code :add(ring, anchor)} is given is a ring of
 * places and a place to hold it by; what a patch keeps is {@link #local}, each point as a displacement from
 * that anchor in world units. That is what lets it outlive the coordinate space — it re-derives its place
 * every time the world moves under it ({@code VrApi.reground}) and the ring simply comes along — and it is why
 * a ring straight out of {@code gob:hitbox()} lands exactly on that object's footprint when the object's own
 * {@code gob:position()} is the anchor.
 *
 * <p><b>Unprotected</b>, like every other kind here: a patch has no server id, never reaches the wire, and
 * grants nothing. It is drawn on your own screen.
 */
public final class LuaPatch extends LuaWorldEntity {
    /**
     * The ring, as offsets from this patch's own place, in world units (x east, y south). Never mutated: a
     * patch that is scaled or turned recomputes its world ring from these, so the shape it was given stays the
     * shape it holds.
     */
    final Coord2d[] local;

    /**
     * The engine-side overlay this patch is drawn through — built on the first lay and kept for the life of the
     * patch, because its identity is what {@code MapView.ols}, {@code MCache.Grid.Cut.ols} and
     * {@code MapMesh.OLOrder.equals} key on. Guarded by {@code this}.
     */
    private PatchOverlay ol;

    /**
     * The map {@link #ol} is registered in right now, or {@code null} while it is registered in none — which
     * is what {@code patch:drawn()} reads. It is the drawn session's {@code MCache}: a patch holds a place in
     * the world rather than a session, so the screen moving to another character re-registers it in that
     * character's map ({@code VrApi.rehome}). Guarded by {@code this}.
     */
    private MCache laid;

    /**
     * What the ground is showing right now — the world ring and the colour {@link #lay} last built from. The
     * ground pass re-asks every free entity about its place whenever a cut comes or goes, and for a patch that
     * has not moved the honest answer is that there is nothing to do: without this, standing still would build
     * a fresh carve and push a fresh draw state several times a minute. Guarded by {@code this}.
     */
    private List<Coord2d> shown;
    private Color shownCol;

    LuaPatch(Addon owner, Coord2d rc, Coord2d[] local) {
        super(owner, rc, 0.0);
        this.local = local;
    }

    void unregister() { owner.patches.remove(this); }

    /**
     * <b>Where the ring's points are right now</b>, in world coordinates: this patch's place plus each held
     * offset, turned by its facing and taken out to its scale. Caller holds the monitor; {@link #rc} must not
     * be null (nothing is laid without a coordinate).
     */
    List<Coord2d> worldRing() {
        double s = Math.sin(a), c = Math.cos(a);
        List<Coord2d> out = new ArrayList<Coord2d>(local.length);
        for(Coord2d p : local) {
            double x = p.x * scale, y = p.y * scale;
            out.add(Coord2d.of(rc.x + ((x * c) - (y * s)), rc.y + ((y * c) + (x * s))));
        }
        return out;
    }

    /**
     * The colour the masked ground is re-laid in: the tint, or white where there is none, at this patch's
     * opacity. The carve then scales that alpha again per fragment, which is what gives the rim its one pixel.
     * Caller holds the monitor.
     */
    private Color colour() {
        Color t = (tint == null) ? Color.WHITE : tint;
        int al = Math.round(clamp01(alpha) * 255f);
        return new Color(t.getRed(), t.getGreen(), t.getBlue(), al);
    }

    private static float clamp01(float v) {
        return (v < 0f) ? 0f : ((v > 1f) ? 1f : v);
    }

    /**
     * <b>Put this patch on the ground of the scene being drawn</b>, or bring what is already there up to date.
     * Idempotent, and the one place a patch reaches {@code MCache}. Caller holds the monitor;
     * {@code VrApi.attachScene} has already established that it shows and has a coordinate.
     *
     * <p>Three outcomes, and only the middle one is expensive. A patch registered in another session's map
     * moves; a patch whose <b>mask</b> moved is removed and re-added, which bumps {@code MCache.olseq} and so
     * disposes and rebuilds every overlay mesh in that grid; anything else — a colour, a turn inside the tiles
     * it already covers — is a new material pushed through the slot the {@code // addon:} seam on
     * {@code MapView.Overlay} keeps.
     */
    void lay() {
        MapView view = this.mv;
        MCache map = mapOf(view);
        if(map == null)
            return;                                    // no world to lie on: reground puts it here when there is
        List<Coord2d> ring = worldRing();
        Color col = colour();
        if(ol == null) {
            ol = new PatchOverlay(ring, col);
            map.add(ol);
            laid = map;
            shown = ring;
            shownCol = col;
            return;
        }
        if((laid == map) && ring.equals(shown) && col.equals(shownCol))
            return;                                    // nothing about it changed: no cut, and no state
        shown = ring;
        shownCol = col;
        boolean moved = ol.set(ring, col);
        if(laid != map) {
            if(laid != null)
                laid.remove(ol);
            map.add(ol);
            laid = map;
        } else if(moved) {
            map.remove(ol);                            // the mask left its tiles: this pair IS the re-cut,
            map.add(ol);                               //   because MCache.add/remove bump MCache.olseq
        }
        // ...and the material changed on every path, because the carve is derived from the very ring above.
        // A uniform is baked at slot construction and never re-read, so this is the only way a changed one
        // reaches the screen: the // addon: seam on MapView.Overlay, which is a state push and not a tile.
        view.rematerial(ol);
    }

    /** Take this patch off the ground. Idempotent; the inverse of {@link #lay}. Caller holds the monitor. */
    void lift() {
        if(laid == null)
            return;
        try { laid.remove(ol); }
        catch(RuntimeException ex) { /* the map is being taken down (relog): nothing left to remove it from */ }
        laid = null;
    }

    /** Is it on the ground of the scene being drawn right now? — what {@code patch:drawn()} answers. */
    boolean drawn() {
        return !dead && (laid != null);
    }

    /**
     * A patch has no gob and no scene slot, so what {@code VrApi.destroyEntity} undoes for the other four
     * kinds does nothing here and the whole of the ending is this. Runs on the UI thread, outside the monitor,
     * on a patch that is already {@link #dead}.
     */
    void destroyed() {
        synchronized(this) { lift(); }
    }

    /** The map behind a scene, or {@code null} — a view of a tree with no session left in it. */
    private static MCache mapOf(MapView view) {
        if((view == null) || (view.ui == null) || (view.ui.sess == null))
            return null;
        Glob g = view.ui.sess.glob;
        return (g == null) ? null : g.map;
    }

    /** A patch is not a picture of anything, so it has no name and its collection matches no string filter. */
    String visualName() { return null; }

    /** 118.3 fires this beside {@code GhostClicked}, {@code SpriteClicked} and {@code ObjectClicked}. */
    String clickEvent() { return "PatchClicked"; }

    String kind() { return "patch"; }

    /**
     * A patch's own contribution to {@code :info()}: its ring, as the durable {@code {gridId, x, y}} snapshot
     * each point would answer — the shape a live object inside a snapshot must not be. Absent while this
     * session cannot locate the patch at all, which is the shape every other {@code info()} in the API has:
     * present means known.
     */
    void infoInto(LuaTable t) {
        List<Coord2d> ring;
        synchronized(this) {
            if(rc == null)
                return;
            ring = worldRing();
        }
        String user = AddonManager.drawnUser();
        LuaTable pts = new LuaTable();
        int n = 0;
        for(Coord2d p : ring) {
            LuaValue pos = LuaPosition.of(owner, user, p);
            if(pos.isnil())
                return;                                // a ring half-answered is worse than one not answered
            LuaValue pi = pos.get("info").call(pos);
            if(pi.isnil())
                return;
            pts.set(++n, pi);
        }
        t.set("ring", pts);
    }
}
