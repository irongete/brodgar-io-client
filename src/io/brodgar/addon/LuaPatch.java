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
 * A client-only <b>patch</b> (118) — the Java half of {@code hafen.virtual():patch():add(ring, anchor)}: a convex
 * ring of {@link LuaPosition}s lying exactly on the terrain, occluded by whatever stands on it unless
 * {@link #occluded} says otherwise. The fifth kind of {@code hafen.virtual()}, and the first that lies
 * <b>down</b>: the four before it — a prop, a picture, a model, a window — all stand up.
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
 * every time the world moves under it ({@code VirtualApi.reground}) and the ring simply comes along — and it is why
 * a ring straight out of {@code gob:hitbox()} lands exactly on that object's footprint, whether the anchor it
 * is held by is that object's own {@code gob:position()} or the object itself.
 *
 * <p><b>Following a gob is a poll, and it is the one in this layer</b> (118.2). The four kinds that are gobs
 * follow through a {@link FollowMoving} the render tree's own placement pass evaluates every frame; a patch has
 * no gob to hang one off, and the pass that re-asks an anchored entity about its object wakes when that object
 * enters or leaves a character's view rather than while it walks. So {@code VirtualApi.followPatches} re-reads the
 * target's live point on the addon tick and re-lays the ring where it moved — a {@code getgob} and a coordinate
 * compare per follower per frame, and nothing at all while the object stands still.
 *
 * <p><b>It is clicked without being picked</b> (118.3). The four kinds that are gobs render into the clickmap
 * and are resolved by {@code MapView.Hittest}, which answers a frame later; a ground overlay renders into no
 * clickmap at all. So a clickable patch is hit-tested against its own ring, projected, from the {@code // addon:}
 * branch of {@code MapView.mousedown} — {@link PatchClick} — and answers inside the event that asked.
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
     * <b>The line round the ring</b> ({@code patch:border(c, w)}, 121.1) — its colour, or {@code null} while
     * nothing is drawn there, and its thickness in world units, {@code 0} meaning the thinnest line the screen
     * draws. They are the patch's own, not the shared core's: the four kinds that stand up are gobs with their
     * own materials and an edge on one of those is a different mechanism entirely. Guarded by {@code this}.
     */
    Color border;
    float borderWidth;

    /**
     * <b>Whether the world is allowed to hide it</b> ({@code patch:occluded(b)}, 132.1). {@code true} — the
     * default, and what a patch that never names it does — is the depth test the terrain and everything
     * standing on it are drawn against, so a wall in front of the ring cuts it. {@code false} takes that test
     * off and nothing else: the shape is still carved the same way, still lies on the slope, and is still
     * covered by the interface, which is drawn after the world rather than in it. Guarded by {@code this}.
     */
    boolean occluded = true;

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
     * character's map ({@code VirtualApi.rehome}). Guarded by {@code this}.
     */
    private MCache laid;

    /**
     * What the ground is showing right now — the world ring, the two colours and the width {@link #lay} last
     * built from. The
     * ground pass re-asks every free entity about its place whenever a cut comes or goes, and for a patch that
     * has not moved the honest answer is that there is nothing to do: without this, standing still would build
     * a fresh carve and push a fresh draw state several times a minute. Guarded by {@code this}.
     */
    private List<Coord2d> shown;
    private Color shownFill;
    private Color shownEdge;
    private float shownWidth;
    private boolean shownOccluded;

    LuaPatch(Addon owner, Coord2d rc, Coord2d[] local) {
        super(owner, rc, 0.0);
        this.local = local;
    }

    void unregister() { owner.patches.remove(this); }

    /**
     * <b>Where the ring's points are right now</b>, in world coordinates: this patch's place plus each held
     * offset, turned by its facing and taken out to its scale. Caller holds the monitor; {@link #rc} must not
     * be null (nothing is laid without a coordinate).
     *
     * <p><b>The four verbs that change the shape all come to here</b> (118.2). {@code :scale(s)} and
     * {@code :rotate(a)} are read out of the held offsets on the spot, so turning a patch or taking it out
     * recomputes half-planes and pushes a carve state — no mesh is rebuilt and no tile is re-laid, because the
     * shape the engine lays is the whole masked box either way. {@code :offset(x, y)} slides the whole ring on
     * the ground relative to the gob it follows; a free patch is offset from nothing and moves with
     * {@code :position(p)}, which writes {@link #rc} itself.
     */
    List<Coord2d> worldRing() {
        double bx = rc.x, by = rc.y;
        if((followTgt != 0) && (followOff != null)) {
            bx += followOff.x;                         // on the ground, in world units: a patch has no height
            by += followOff.y;
        }
        double s = Math.sin(a), c = Math.cos(a);
        List<Coord2d> out = new ArrayList<Coord2d>(local.length);
        for(Coord2d p : local) {
            double x = p.x * scale, y = p.y * scale;
            out.add(Coord2d.of(bx + ((x * c) - (y * s)), by + ((y * c) + (x * s))));
        }
        return out;
    }

    /**
     * <b>The fill</b>: the tint, or white where there is none, at the tint's OWN opacity times this patch's
     * (121.1). On a patch the tint <i>is</i> the fill — there is no picture under it for a blend strength to
     * mean anything against — so its {@code a}, dropped before 121, is what makes the interior see-through
     * while {@link #edgeColour} stands solid round it. {@code :alpha(a)} multiplies both, which is why it
     * appears in each of the two and in neither of the shader's uniforms. Caller holds the monitor.
     */
    private Color fillColour() {
        Color t = (tint == null) ? Color.WHITE : tint;
        return faded(t);
    }

    /**
     * <b>The border</b>: its own colour at its own opacity times this patch's, or {@code null} while none is
     * laid — which is what {@link PatchOverlay} turns into the carve's negative-width sentinel. Caller holds
     * the monitor.
     */
    private Color edgeColour() {
        return (border == null) ? null : faded(border);
    }

    /** A colour at its own alpha taken out by this patch's {@code :alpha(a)}. Caller holds the monitor. */
    private Color faded(Color c) {
        int al = Math.round(clamp01(alpha) * c.getAlpha());
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), al);
    }

    private static float clamp01(float v) {
        return (v < 0f) ? 0f : ((v > 1f) ? 1f : v);
    }

    /**
     * <b>Put this patch on the ground of the scene being drawn</b>, or bring what is already there up to date.
     * Idempotent, and the one place a patch reaches {@code MCache}. Caller holds the monitor;
     * {@code VirtualApi.attachScene} has already established that it shows and has a coordinate.
     *
     * <p>Three outcomes, and only the middle one is expensive. A patch registered in another session's map
     * moves; a patch whose <b>mask</b> moved is removed and re-added, which bumps <i>this</i> overlay's own
     * sequence and so re-lays the cuts it covers and nobody else's (119.2); anything else — a colour, a turn
     * inside the tiles it already covers — is a new material pushed through the slot the {@code // addon:}
     * seam on {@code MapView.Overlay} keeps.
     */
    void lay() {
        MapView view = this.mv;
        MCache map = mapOf(view);
        if(map == null)
            return;                                    // no world to lie on: reground puts it here when there is
        List<Coord2d> ring = worldRing();
        Color fill = fillColour();
        Color edge = edgeColour();
        float bw = borderWidth;
        boolean occ = occluded;
        if(ol == null) {
            ol = new PatchOverlay(ring, fill, edge, bw, occ);
            map.add(ol);
            laid = map;
            shown = ring;
            shownFill = fill;
            shownEdge = edge;
            shownWidth = bw;
            shownOccluded = occ;
            return;
        }
        if((laid == map) && ring.equals(shown) && fill.equals(shownFill)
           && ((edge == null) ? (shownEdge == null) : edge.equals(shownEdge)) && (bw == shownWidth)
           && (occ == shownOccluded))
            return;                                    // nothing about it changed: no cut, and no state
        shown = ring;
        shownFill = fill;
        shownEdge = edge;
        shownWidth = bw;
        shownOccluded = occ;
        boolean moved = ol.set(ring, fill, edge, bw, occ);
        if(laid != map) {
            if(laid != null)
                laid.remove(ol);
            map.add(ol);
            laid = map;
        } else if(moved) {
            map.remove(ol);                            // the mask left its tiles: this pair IS the re-cut,
            map.add(ol);                               //   and add() takes the drop back (119.2), so it
                                                       //   costs this overlay's cuts and nothing more
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

    /**
     * Is it on the ground of the scene being drawn right now? — the patch's half of {@code :drawn()}. A patch has
     * no scene slot, so what the shared core reads off {@code slot} it reads off {@link #laid} here: the map its
     * overlay is registered in, or none.
     */
    boolean drawn() {
        return !dead && (laid != null);
    }

    /**
     * <b>A patch has no height.</b> It lies on the terrain — that is what it is — so {@code patch:offset(x, y)}
     * slides it on the ground and a {@code z} is refused by the shared verb naming why. One held above the
     * ground is the other mechanism entirely, and 118 puts it out of scope.
     */
    boolean height() {
        return false;
    }

    /**
     * A patch has no gob and no scene slot, so what {@code VirtualApi.destroyEntity} undoes for the other four
     * kinds does nothing here and the whole of the ending is this. Runs on the UI thread, outside the monitor,
     * on a patch that is already {@link #dead}.
     */
    void destroyed() {
        synchronized(this) { lift(); }
    }

    /**
     * The map behind a scene, or {@code null} — a view of a tree with no session left in it. Shared with
     * {@link PatchClick}, which needs the very same map to read the ground its ring is projected at (118.3).
     */
    static MCache mapOf(MapView view) {
        if((view == null) || (view.ui == null) || (view.ui.sess == null))
            return null;
        Glob g = view.ui.sess.glob;
        return (g == null) ? null : g.map;
    }

    /** A patch is not a picture of anything, so it has no name and its collection matches no string filter. */
    String visualName() { return null; }

    /** Fired beside {@code GhostClicked}, {@code SpriteClicked} and {@code ObjectClicked} (118.3). */
    String clickEvent() { return "PatchClicked"; }

    String kind() { return "patch"; }

    /**
     * A patch's own contribution to {@code :info()}: its {@code border}, and its ring as the durable
     * {@code {gridId, x, y}} snapshot each point would answer — the shape a live object inside a snapshot must
     * not be. Either is absent when the thing it names is: no border laid, or a session that cannot locate the
     * patch at all. That is the shape every other {@code info()} in the API has: present means known.
     *
     * <p>{@code border} is the <b>two</b> values {@code patch:border()} hands back, under the names the
     * stylesheet's own rule gives them — a snapshot is a document, which is the very distinction that keeps
     * {@code {color =, width =}} out of the call itself.
     *
     * <p>{@code occluded} is always here, unlike the two above: it is a boolean property with a value at every
     * moment rather than a thing that may or may not be laid, so an absent key would say nothing.
     */
    void infoInto(LuaTable t) {
        List<Coord2d> ring;
        synchronized(this) {
            t.set("occluded", LuaValue.valueOf(occluded));
            if(border != null) {
                LuaValue bc = AddonManager.color(border);
                if(!bc.isnil()) {
                    LuaTable b = new LuaTable();
                    b.set("color", bc);
                    b.set("width", LuaValue.valueOf((double)borderWidth));
                    t.set("border", b);
                }
            }
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
