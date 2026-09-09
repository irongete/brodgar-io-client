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
 * <p><b>The test is one PIECE projected, solved against the same half-planes the carve uses</b> (136.3) —
 * and the patch answers wherever <b>any</b> of its pieces contains the point, which is the click's half of the
 * union the shader draws. Each ring point goes to the screen at its own ground height ({@link Eye}, which has
 * no point to give for one behind the eye), and {@link PatchCarve#planes} builds the inward half-planes of that
 * projected ring exactly as it builds the map-space ones the shader carves with — the centroid picks each
 * inward side, so the winding the projection happens to come out in decides nothing. A convex ring on a plane
 * projects to a convex screen polygon, which is why one solve answers it; over ground that bends inside the
 * ring the corners are not quite coplanar and the solve answers their screen hull, which for a footprint is a
 * fraction of a pixel. <b>The ground between two pieces is inside no piece</b>, so a press there is a press on
 * no patch and falls through to the world, exactly as one outside the shape does.
 *
 * <p><b>A piece that cannot be projected whole is dropped alone</b> (136.3). A shape at the edge of the view
 * has pieces the eye is behind and pieces it is not, and one such corner dropping the <i>patch</i> meant the
 * half of the shape plainly on screen took no clicks at all.
 *
 * <p><b>Nothing here consults the depth buffer</b>, so a patch behind a hill still answers a click — the same
 * remainder a standing panel has, and 118 states it. Depth decides only which of two <i>patches</i> wins, and
 * it is measured at <b>the piece the pointer is on</b> rather than over the whole shape: a long patch running
 * away from the eye is in front where its near end is, which is how it looks.
 *
 * <p><b>The world point is the click met with the patch's own plane.</b> The projection restricted to a
 * horizontal plane is a homography, so four probe points on the plane under the piece that was hit determine
 * it and its inverse turns the pointer back into world coordinates exactly — {@link SurfaceInput}'s three pure
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
     *
     * <p><b>Every piece of every patch, folded into one frontmost</b> (136.3). The fold is over pieces rather
     * than over patches, so the piece that wins is the nearest one anybody has under the pointer and the patch
     * that answers is the one holding it — which is at once the union test (a patch is hit wherever any one of
     * its pieces is), the ordering (nearest at the point, rather than averaged over a whole shape), and the
     * ring the world point below comes off. A patch holding no piece takes no click and needs no test of its
     * own to say so: the inner loop runs over nothing.
     */
    static Hit hit(MapView mv, Coord pc, List<LuaPatch> live) {
        MCache map = LuaPatch.mapOf(mv);
        if((map == null) || (pc == null))
            return null;
        LuaPatch best = null;
        List<Coord2d> bring = null;
        float bd = Float.MAX_VALUE;
        for(LuaPatch p : live) {
            List<List<Coord2d>> shape;
            synchronized(p) {
                if(p.dead || !p.clickable || !p.drawn() || (p.rc == null))
                    continue;
                shape = p.worldPieces();
            }
            for(List<Coord2d> ring : shape) {
                float d = depthAt(mv, map, ring, pc);
                if(Float.isNaN(d))
                    continue;                          // not under the pointer, or not wholly on the screen
                if((best == null) || (d < bd)) {
                    best = p;
                    bring = ring;
                    bd = d;
                }
            }
        }
        return (best == null) ? null : new Hit(best, world(mv, map, bring, pc));
    }

    /**
     * <b>How deep ONE piece lies at the pointer</b> (136.3) — the average screen depth of its projected ring
     * where {@code pc} falls inside that ring, and {@code NaN} where it does not. {@code NaN} is also the
     * answer where the piece cannot be projected <b>whole</b>: a corner behind the eye, or over ground the
     * client has not got, has no screen point at all, and a ring one point short is a different shape rather
     * than a smaller one.
     *
     * <p><b>It drops the PIECE, and the patch goes on being tested.</b> A shape at the edge of the view has
     * pieces the eye is behind and pieces it is not, and one such corner dropping the whole patch is what left
     * the half of it plainly on screen taking no clicks.
     */
    private static float depthAt(MapView mv, MCache map, List<Coord2d> ring, Coord pc) {
        int n = ring.size();
        double[] sx = new double[n], sy = new double[n];
        double sum = 0;
        for(int i = 0; i < n; i++) {
            Coord3f s = project(mv, map, ring.get(i));
            if(s == null)
                return Float.NaN;                      // off-stream ground, or a corner behind the eye
            sx[i] = s.x;
            sy[i] = s.y;
            sum += s.z;
        }
        float[][] e = PatchCarve.planes(sx, sy, n);
        if((e.length < 3) || !PatchCarve.inside(e, pc.x, pc.y))
            return Float.NaN;                          // fewer than three edges on screen encloses no pixels
        return (float)(sum / n);
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
     * of one tile beside the centre of <b>the piece that was hit</b> (136.3), which is a span wide enough for
     * the inverse to be well conditioned and near enough to the pointer to be in front of the eye whenever
     * that piece itself was — a shape running away from the eye would otherwise probe the plane beside a piece
     * of it the click never touched. The centre is the answer where there is no inverse to be had — a
     * degenerate case the ring having projected already rules out, and never a number measured from somewhere
     * else.
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
