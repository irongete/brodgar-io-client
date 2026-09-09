package io.brodgar.addon;

import java.awt.Color;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import haven.Area;
import haven.Coord;
import haven.Coord2d;
import haven.FColor;
import haven.MCache;
import haven.MapMesh;
import haven.Material;
import haven.render.BaseColor;
import haven.render.States;

/**
 * <b>A patch's ground, in the engine's own words</b> (118) — one object that is both a
 * {@link MCache.OverlayInfo} (what an overlay is drawn with) and a {@link MCache.LocalOverlay} (which tiles it
 * covers), registered with {@code MCache.add}.
 *
 * <p><b>The geometry is the engine's, and that is the whole point.</b> {@code MapView.oltick} finds this
 * through {@code MCache.getols}, tests {@link #tags()} against its own tag counts — seeded {@code {"show": 1}},
 * which nothing decrements — and adds a {@code MapView.Overlay} raster for it. {@code MapMesh.makeol} then
 * re-lays every masked tile through that tile's own {@code Tiler.lay}, over the ground's <b>own vertices</b>,
 * and {@code MapMesh.OLOrder}'s {@code mainorder} 1002 draws the result after the ground in the same pass,
 * where {@code States.Depthtest(LE)} lets the exact tie through. So there is no lift, no depth bias and
 * nothing that comes apart at distance, and a gob standing on the patch occludes it because its depth is
 * genuinely nearer — until {@link #set} is told otherwise, which takes the depth <i>test</i> off and leaves
 * everything else exactly where it is. It needs no {@code .res}: {@code OverlayInfo} is a plain interface, and
 * {@code MapView.selol} is the precedent for an anonymous one.
 *
 * <p><b>The mask is generous; the silhouette is carved.</b> {@link #fill} marks every tile a piece's bounding
 * box touches, so the mask never decides the shape — a tile is 11 world units and a piece is not on that
 * grid. What decides the shape is {@link PatchCarve}, per fragment, in the material below.
 *
 * <p><b>A box per piece, and one that bounds them all</b> (136.1). A patch of many pieces asked as one box is
 * the whole hull of the shape, and a stroke laid across the screen would mask — and re-lay, and test per cut
 * — every tile of the rectangle it spans. So {@link #boxes} is the pieces' own boxes and {@link #tiles} is the
 * box round those: {@link #filter} answers with the bound first, which is one {@code Area.isects} and rejects
 * nearly everything, and pays the per-piece walk only where the bound already said maybe.
 *
 * <p><b>One overlay per patch, for the life of the patch</b>, because its identity is what
 * {@code MapView.ols}, {@code MCache.Grid.Cut.ols} and {@code MapMesh.OLOrder.equals} all key on. It is
 * mutable instead: {@link #set} re-derives both halves and says whether the <i>mask</i> moved, which is the
 * only change that costs a re-cut ({@code MCache.add}/{@code remove} bump this overlay's own sequence, and
 * {@code Grid.getolcut} re-lays the cuts of <i>this</i> overlay and no other one's — 119.2). A patch that
 * merely changed colour, or turned inside the tiles it already covers, pushes the new material through the
 * slot instead.
 *
 * <p><b>UI thread.</b> Every caller here is on it — the addon tick and the {@code hafen.virtual()} verbs —
 * and that is this class's own discipline rather than anything {@code MCache} guarantees: resource-published
 * gob code registers a {@code LocalOverlay} of its own from a loader thread, so {@code MCache.ols} and the
 * id index beside it are concurrent collections and {@code getols} may be walking either at the time.
 */
final class PatchOverlay implements MCache.LocalOverlay, MCache.OverlayInfo {
    /** The tiles the mask marks: one box per piece, each that piece's bounding box a tile proud each way. */
    private Area[] boxes;
    /** The box that bounds {@link #boxes} — the one question {@link #filter} asks before it walks them. */
    private Area tiles;
    /** What the masked ground is re-laid with — the colours, and the carve that cuts the shape out of them. */
    private Material mat;

    PatchOverlay(List<List<Coord2d>> pieces, Color fill, Color edge, float width, boolean occluded) {
        set(pieces, fill, edge, width, occluded);
    }

    /** Read where {@link #set} is given no edge: a colour the band is switched off over and so never reads. */
    private static final FColor NORIM = new FColor(0f, 0f, 0f, 0f);

    /**
     * Re-derive both halves from a patch's pieces in <b>world</b> coordinates, and answer whether the
     * <b>mask</b> moved — which is the caller's cue to pay for a re-cut rather than a state push. The answer
     * is over the box <b>array</b>: a patch that merely turned or was tinted inside the tiles it already
     * covers gives the same boxes and pays nothing, while a piece laid or taken up changes the array and so
     * costs this overlay's own cuts. That is the honest answer for a piece that reaches ground the others do
     * not — which is the point of laying one — and one box short of it for a piece that lands wholly inside
     * them.
     *
     * <p><b>Three values, two states</b> (121.1): {@code fill} is what {@code BaseColor} writes at order 0 and
     * {@code edge} with {@code width} is what {@link PatchCarve} lays over it at 500, so each carries its own
     * opacity and the line can stand solid round ground you can see through. A {@code null} {@code edge} is no
     * border, and it reaches the fragment as {@link PatchCarve}'s negative-width sentinel.
     *
     * <p><b>{@code occluded} is one more op in the same list</b> (132.1), and the whole of what the world
     * hiding a patch is. {@code States.Depthtest.none} puts {@code null} in the depth-test slot, which
     * {@code GLPipeState} answers with {@code glDisable(GL_DEPTH_TEST)}, so the fragment is written whatever
     * stands in front of it. It is <b>not</b> {@link States#maskdepth}, which stays in the list either way and
     * answers {@code glDepthMask(false)}: that one is whether the fragment writes depth of its own, and an
     * overlay wants it off whether or not it is tested. Reading the two as one state is the mistake this verb
     * turns on.
     *
     * <p>Because the flag is only an op, it never moves the tiles: the answer below is the same one this
     * method would give without it, so a patch told the world may not hide it re-pushes its material down
     * the path a colour change takes and nothing is re-carved.
     */
    boolean set(List<List<Coord2d>> pieces, Color fill, Color edge, float width, boolean occluded) {
        Area[] was = boxes;
        boxes = coverage(pieces);
        tiles = bounds(boxes);
        PatchCarve carve = new PatchCarve(PatchCarve.of(pieces),
                                          (edge == null) ? NORIM : new FColor(edge),
                                          (edge == null) ? -1f : width);
        MapMesh.OLOrder order = new MapMesh.OLOrder(this);
        mat = occluded
            ? new Material(new BaseColor(fill), States.maskdepth, order, carve)
            : new Material(new BaseColor(fill), States.maskdepth, States.Depthtest.none, order, carve);
        return !Arrays.equals(boxes, was);
    }

    /**
     * Every tile each piece's bounding box touches, and one more each way. Generous on purpose: the shader
     * does the cutting and the mask only has to reach past it, so a rounding difference at the edge of a box
     * can never clip the shape.
     */
    private static Area[] coverage(List<List<Coord2d>> pieces) {
        Area[] out = new Area[pieces.size()];
        for(int i = 0; i < out.length; i++) {
            double lox = Double.MAX_VALUE, loy = Double.MAX_VALUE;
            double hix = -Double.MAX_VALUE, hiy = -Double.MAX_VALUE;
            for(Coord2d p : pieces.get(i)) {
                lox = Math.min(lox, p.x); hix = Math.max(hix, p.x);
                loy = Math.min(loy, p.y); hiy = Math.max(hiy, p.y);
            }
            out[i] = Area.corn(Coord2d.of(lox, loy).floor(MCache.tilesz).sub(1, 1),
                               Coord2d.of(hix, hiy).floor(MCache.tilesz).add(2, 2));
        }
        return out;
    }

    /**
     * The box round the boxes — what {@link #filter} rejects nearly every cut of the map with, in one
     * comparison. A patch with no pieces left gets an empty box at the origin, and that is only ever a fast
     * reject: the walk below it is over no boxes at all, so such a patch answers "nowhere in b" for every
     * cut, the one that spans the origin included.
     */
    private static Area bounds(Area[] boxes) {
        if(boxes.length == 0)
            return Area.corn(Coord.z, Coord.z);
        Area b = boxes[0];
        for(int i = 1; i < boxes.length; i++)
            b = b.include(boxes[i]);
        return b;
    }

    public MCache.OverlayInfo id() {return(this);}

    /**
     * {@code "show"} — the tag {@code MapView} seeds its own count with and nothing decrements, so a patch is
     * drawn from the moment it is registered. The client's own overlay tags ({@code "prov"}, {@code "cplot"})
     * are the player's to turn on and off; a patch is the addon's, and {@code patch:visible(b)} is where it
     * says so.
     *
     * <p>One list for every patch there will ever be (132.2), because every patch answers this same one tag and
     * {@code MapView.oltick} asks per overlay per frame.
     */
    private static final Collection<String> TAGS = Collections.unmodifiableList(Arrays.asList("show"));

    public Collection<String> tags() {return(TAGS);}

    public Material mat() {return(mat);}

    /**
     * No outline pass, border or none: {@code patch:border(c, w)} is a band carved off the same signed distance
     * the silhouette is, inside {@link PatchCarve}, and the engine's own outline would outline the masked
     * <b>tiles</b> — a rectangle round the shape rather than the shape. So {@code overlayOutlines} stands still
     * whatever a patch is wearing.
     */
    public Material omat() {return(null);}

    /**
     * <b>Is this mask nowhere in {@code b}?</b> — the question {@code MCache.getols} puts to the whole drawn
     * area and {@code MCache.olreaches} puts to one cut, per overlay, per frame, under {@code MapView.oltick}.
     *
     * <p><b>The pure comparison, not the intersection thrown away</b> (132.2): {@code Area.overlap} answers by
     * building two {@code Coord} and an {@code Area}, and reading it for its nullness alone discards all three
     * — at the rate this is asked, that is the map's <i>"exactly and with no allocation"</i> broken by one
     * expression. {@code Area.isects} is the test {@code overlap} itself runs first, so the answer is the same
     * one and nothing is built to reach it.
     */
    public boolean filter(Area b) {
        if(!b.isects(tiles))
            return true;                               // the one comparison that rejects nearly every cut
        for(Area box : boxes) {
            if(b.isects(box))
                return false;
        }
        return true;                                   // inside the hull, between the pieces: nothing here
    }

    public void fill(Area b, boolean[] buf) {
        for(Area box : boxes) {
            Area ol = box.overlap(b);
            if(ol == null)
                continue;
            for(Coord lc : ol)
                buf[b.ri(lc)] = true;                  // two pieces over one tile write the same true twice
        }
    }

    public String toString() {
        return(String.format("#<patch-overlay %d piece(s) in %s>", boxes.length, tiles));
    }
}
