package io.brodgar.addon;

import java.awt.Color;
import java.util.Arrays;
import java.util.Collection;
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
 * genuinely nearer. It needs no {@code .res}: {@code OverlayInfo} is a plain interface, and
 * {@code MapView.selol} is the precedent for an anonymous one.
 *
 * <p><b>The mask is generous; the silhouette is carved.</b> {@link #fill} marks every tile the ring's bounding
 * box touches, so the mask never decides the shape — a tile is 11 world units and the ring is not on that
 * grid. What decides the shape is {@link PatchCarve}, per fragment, in the material below.
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
    /** The tiles the mask marks: the ring's bounding box, one tile proud on each side. */
    private Area tiles;
    /** What the masked ground is re-laid with — the colours, and the carve that cuts the ring out of them. */
    private Material mat;

    PatchOverlay(List<Coord2d> ring, Color fill, Color edge, float width) {
        set(ring, fill, edge, width);
    }

    /** Read where {@link #set} is given no edge: a colour the band is switched off over and so never reads. */
    private static final FColor NORIM = new FColor(0f, 0f, 0f, 0f);

    /**
     * Re-derive both halves from a ring in <b>world</b> coordinates, and answer whether the <b>mask</b> moved
     * — which is the caller's cue to pay for a re-cut rather than a state push.
     *
     * <p><b>Three values, two states</b> (121.1): {@code fill} is what {@code BaseColor} writes at order 0 and
     * {@code edge} with {@code width} is what {@link PatchCarve} lays over it at 500, so each carries its own
     * opacity and the line can stand solid round ground you can see through. A {@code null} {@code edge} is no
     * border, and it reaches the fragment as {@link PatchCarve}'s negative-width sentinel.
     */
    boolean set(List<Coord2d> ring, Color fill, Color edge, float width) {
        Area was = tiles;
        tiles = coverage(ring);
        mat = new Material(new BaseColor(fill), States.maskdepth, new MapMesh.OLOrder(this),
                           new PatchCarve(PatchCarve.of(ring),
                                          (edge == null) ? NORIM : new FColor(edge),
                                          (edge == null) ? -1f : width));
        return !tiles.equals(was);
    }

    /**
     * Every tile the ring's bounding box touches, and one more each way. Generous on purpose: the shader does
     * the cutting and the mask only has to reach past it, so a rounding difference at the edge of the box can
     * never clip the shape.
     */
    private static Area coverage(List<Coord2d> ring) {
        double lox = Double.MAX_VALUE, loy = Double.MAX_VALUE;
        double hix = -Double.MAX_VALUE, hiy = -Double.MAX_VALUE;
        for(Coord2d p : ring) {
            lox = Math.min(lox, p.x); hix = Math.max(hix, p.x);
            loy = Math.min(loy, p.y); hiy = Math.max(hiy, p.y);
        }
        return Area.corn(Coord2d.of(lox, loy).floor(MCache.tilesz).sub(1, 1),
                         Coord2d.of(hix, hiy).floor(MCache.tilesz).add(2, 2));
    }

    public MCache.OverlayInfo id() {return(this);}

    /**
     * {@code "show"} — the tag {@code MapView} seeds its own count with and nothing decrements, so a patch is
     * drawn from the moment it is registered. The client's own overlay tags ({@code "prov"}, {@code "cplot"})
     * are the player's to turn on and off; a patch is the addon's, and {@code patch:visible(b)} is where it
     * says so.
     */
    public Collection<String> tags() {return(Arrays.asList("show"));}

    public Material mat() {return(mat);}

    /**
     * No outline pass, border or none: {@code patch:border(c, w)} is a band carved off the same signed distance
     * the silhouette is, inside {@link PatchCarve}, and the engine's own outline would outline the masked
     * <b>tiles</b> — a rectangle round the shape rather than the shape. So {@code overlayOutlines} stands still
     * whatever a patch is wearing.
     */
    public Material omat() {return(null);}

    public boolean filter(Area b) {return(b.overlap(tiles) == null);}

    public void fill(Area b, boolean[] buf) {
        Area ol = tiles.overlap(b);
        if(ol != null) {
            for(Coord lc : ol)
                buf[b.ri(lc)] = true;
        }
    }

    public String toString() {return(String.format("#<patch-overlay %s>", tiles));}
}
