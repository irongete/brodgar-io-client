package io.brodgar.addon;

import java.util.List;

import haven.Coord;
import haven.Coord2d;
import haven.Coord3f;
import haven.MCache;
import haven.MapView;

/**
 * <b>Where the pointer lands on a patch</b> (118.3) — the screen-point &rarr; patch test, the front-to-back
 * resolution between several patches lying over one another, and the world point the click met the ground at.
 * The patch's half of what {@link SurfaceInput} is for a widget standing in the world.
 *
 * <p><b>Synchronous, because a patch is not in any pick pass at all.</b> The four kinds of {@code hafen.virtual()}
 * that are gobs render into the clickmap and are resolved by {@code MapView.Hittest}, which answers a frame
 * later on a callback thread of its own; a patch is a ground overlay ({@link PatchOverlay}) and renders into no
 * clickmap, so there is nothing there to resolve. It is hit-tested here instead, from the {@code // addon:}
 * branch of {@code MapView.mousedown}, and it answers inside the event that asked.
 *
 * <p><b>The test is the ring projected, solved against the same half-planes the carve uses.</b> Each ring point
 * goes to the screen at its own ground height ({@link Eye}, which has no point to give for one behind the eye),
 * and {@link PatchCarve#planes} builds the inward half-planes of that projected ring exactly as it builds the
 * map-space ones the shader carves with — the centroid picks each inward side, so the winding the projection
 * happens to come out in decides nothing. A convex ring on a plane projects to a convex screen polygon, which
 * is why one solve answers it; over ground that bends inside the ring the corners are not quite coplanar and
 * the solve answers their screen hull, which for a footprint is a fraction of a pixel.
 *
 * <p><b>Nothing here consults the depth buffer</b>, so a patch behind a hill still answers a click — the same
 * remainder a standing panel has, and 118 states it. Depth decides only which of two <i>patches</i> wins: the
 * nearer ring centre, which is how they look.
 *
 * <p><b>The world point is the click met with the patch's own plane.</b> The projection restricted to a
 * horizontal plane is a homography, so four probe points on the plane under the patch determine it and its
 * inverse turns the pointer back into world coordinates exactly — {@link SurfaceInput}'s three pure
 * {@code 3&times;3} helpers, which is the very arithmetic that maps a pointer onto a standing panel. It is the
 * ground at the patch's own height rather than the terrain's true point under the cursor: reading that is the
 * asynchronous pass this class exists to avoid, and across a footprint the two differ by less than the slope
 * under it.
 */
final class PatchClick {
    private PatchClick() {}

    /** One resolved landing: the patch the pointer is on, and the world point it is on. */
    static final class Hit {
        final LuaPatch patch;
        /** The world point the click met the patch's own plane at — what {@code ev:x()}/{@code ev:y()} carry. */
        final Coord2d at;

        Hit(LuaPatch patch, Coord2d at) {
            this.patch = patch;
            this.at = at;
        }
    }

    /**
     * The frontmost patch of {@code live} under {@code pc} — a point in the map view's own pixels, the space
     * {@code MapView}'s mouse events speak — or {@code null} when the pointer is on none of them, which is the
     * fall-through that leaves the click to the world beneath it.
     */
    static Hit hit(MapView mv, Coord pc, List<LuaPatch> live) {
        MCache map = LuaPatch.mapOf(mv);
        if((map == null) || (pc == null))
            return null;
        LuaPatch best = null;
        List<Coord2d> bring = null;
        float bd = Float.MAX_VALUE;
        for(LuaPatch p : live) {
            List<Coord2d> ring;
            synchronized(p) {
                if(p.dead || !p.clickable || !p.drawn() || (p.rc == null))
                    continue;
                ring = p.worldRing();
            }
            int n = ring.size();
            double[] sx = new double[n], sy = new double[n];
            double depth = 0;
            boolean whole = true;
            for(int i = 0; whole && (i < n); i++) {
                Coord3f s = project(mv, map, ring.get(i));
                if(s == null) {
                    whole = false;                     // off-stream ground, or a corner behind the eye
                    break;
                }
                sx[i] = s.x;
                sy[i] = s.y;
                depth += s.z;
            }
            if(!whole)
                continue;
            float[][] e = PatchCarve.planes(sx, sy, n);
            if((e.length < 3) || !PatchCarve.inside(e, pc.x, pc.y))
                continue;                              // fewer than three edges on screen encloses no pixels
            float d = (float)(depth / n);
            if((best == null) || (d < bd)) {
                best = p;
                bring = ring;
                bd = d;
            }
        }
        return (best == null) ? null : new Hit(best, world(mv, map, bring, pc));
    }

    /** A world point at its own ground height, in the map view's own pixels — or {@code null} where it has none. */
    private static Coord3f project(MapView mv, MCache map, Coord2d wc) {
        try {
            return Eye.view(mv, map.getzp(wc));
        } catch(RuntimeException ex) {
            return null;                               // Loading: ground the client has not got has no height
        }
    }

    /**
     * The world point {@code pc} meets the horizontal plane the patch lies at. The four probes are the corners
     * of one tile beside the ring's own centre, which is a span wide enough for the inverse to be well
     * conditioned and near enough to the patch to be in front of the eye whenever the ring itself was. The
     * centre is the answer where there is no inverse to be had — a degenerate case the ring having projected
     * already rules out, and never a number measured from somewhere else.
     */
    private static Coord2d world(MapView mv, MCache map, List<Coord2d> ring, Coord pc) {
        double cx = 0, cy = 0;
        for(Coord2d p : ring) {
            cx += p.x;
            cy += p.y;
        }
        cx /= ring.size();
        cy /= ring.size();
        Coord2d b = Coord2d.of(cx, cy);
        float h;
        try { h = map.getzp(b).z; }
        catch(RuntimeException ex) { return b; }
        double span = MCache.tilesz.x;
        double[] u = {0, span, 0, span}, v = {0, 0, span, span};   // SurfaceInput's order: (0,0) (1,0) (0,1) (1,1)
        float[] q = new float[8];
        for(int i = 0; i < 4; i++) {
            Coord3f s = Eye.view(mv, new Coord3f((float)(b.x + u[i]), (float)(b.y + v[i]), h));
            if(s == null)
                return b;
            q[i * 2] = s.x;
            q[(i * 2) + 1] = s.y;
        }
        float[] uv = SurfaceInput.apply(SurfaceInput.invert(SurfaceInput.forward(q)), pc.x, pc.y);
        return (uv == null) ? b : Coord2d.of(b.x + (uv[0] * span), b.y + (uv[1] * span));
    }
}
