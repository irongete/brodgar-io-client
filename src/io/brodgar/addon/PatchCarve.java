package io.brodgar.addon;

import java.util.ArrayList;
import java.util.List;

import haven.Coord2d;
import haven.FColor;
import haven.render.FragColor;
import haven.render.Homo3D;
import haven.render.Pipe;
import haven.render.State;
import haven.render.gl.UniformApplier;
import haven.render.sl.Array;
import haven.render.sl.Expression;
import haven.render.sl.For;
import haven.render.sl.Function;
import haven.render.sl.LValue;
import haven.render.sl.Return;
import haven.render.sl.ShaderMacro;
import haven.render.sl.Symbol;
import haven.render.sl.Type;
import haven.render.sl.Uniform;

import static haven.render.sl.Cons.*;

/**
 * <b>The silhouette of a patch, carved per fragment</b> (118) — the {@link State} that turns the engine's
 * whole-tile ground overlay ({@link PatchOverlay}) into the ring's own shape.
 *
 * <p>The mask {@code PatchOverlay} hands the engine is the ring's bounding box in whole tiles, because
 * {@code MCache.LocalOverlay.fill} cannot say anything finer; a tile is 11 world units, so a mask used as the
 * shape would be an 11-unit staircase. The shape is therefore decided <b>here</b>, on the fragment, against
 * {@link Homo3D#fragmapv} — the fragment's own place in map space, which is world space with {@code y}
 * negated ({@code Gob.Placed} negates it before {@code Transform.makexlate}).
 *
 * <p><b>Half-planes, which is why the ring must be convex.</b> A convex polygon is exactly the intersection of
 * its edges' half-planes, so each edge contributes the signed distance {@code dot(p, n) - d} — positive inside
 * — and the polygon's own signed distance is the <b>minimum</b> across the edges. One {@code smoothstep} over
 * that minimum's own {@code fwidth} then antialiases every edge and every corner at once, and does it in
 * SCREEN derivatives rather than world units: the rim is one pixel at every zoom rather than a slab of world
 * that thickens as you pull the camera back. It is exact and resolution-independent — there is no rasterised
 * approximation of the ring anywhere.
 *
 * <p><b>The inward normal is chosen by the centroid</b>, per edge, so a ring may be wound either way: an
 * addon feeding {@code gob:hitbox()} straight in does not have to know which direction the resource happened
 * to record its {@code obst} points in.
 *
 * <p><b>The edges are ONE array uniform, walked by a {@code for}</b> — not one uniform per edge. GLSL declares
 * that array at a fixed length, so a limit exists and is {@link #EDGES}; a longer ring is refused by name
 * rather than truncated to the wrong shape. {@code haven.render.sl} carries {@link Array}, {@code Index} and
 * {@link For}, and {@code UniformApplier.TypeMapping.register} is public, so mapping {@code float[][]} onto an
 * array of {@code vec4} is the static initialiser below and no core edit.
 *
 * <p><b>The border is a second band off the very same minimum</b> (121.1). {@code m} is the ring's own signed
 * distance, so the fragments whose {@code m} lies between {@code 0} and the border's width are the ones inside
 * the edge — a band, in the same expression, on the same fragment, with no second overlay, no second mesh and
 * no tile re-laid. The band is mixed over what {@code BaseColor} wrote <b>before</b> the silhouette scales the
 * alpha, so {@code mix} carries the two opacities across with the two colours and the line comes out at its
 * own while the interior stays at the fill's — which is the one thing two concentric patches cannot do.
 *
 * <p><b>The width is world units with a one-pixel floor</b>, and the floor is what lets it be world units:
 * {@code fwidth(m)} is how much world one pixel spans, so {@code max(width, fwidth(m))} is the silhouette's
 * own pixel and a border never thins below it as the camera pulls back. A width of {@code 0} is therefore
 * legal and means exactly that hairline. <b>No border at all is {@code width < 0}</b> — a sentinel derived
 * from {@code LuaPatch.border == null} and never seen from Lua — which {@code step(0, width)} switches the
 * band off by, rather than a second {@link ShaderMacro} that would double the terrain programs to save a
 * multiply.
 *
 * <p><b>A uniform is baked, not re-read.</b> {@code GLDrawList.DrawSlot.getsettings} resolves every uniform
 * once, at slot construction, and caches it — so mutating {@link #e} afterwards propagates nothing. A patch
 * that moves, turns or is tinted builds a NEW carve and pushes it through the slot
 * ({@code MapView.Overlay.rematerial}, the {@code // addon:} seam).
 */
public class PatchCarve extends State {
    /**
     * <b>How many edges a patch's ring may have.</b> The array is declared at this length in the fragment
     * stage, where {@code GL_MAX_FRAGMENT_UNIFORM_COMPONENTS} is guaranteed to be at least 1024: 32 half-planes
     * is 128 of those components, an eighth of the floor, leaving the terrain program's own uniforms — its
     * matrices, its lights — the rest. A 32-gon is a circle to the eye, and the footprints this exists to take
     * ({@code gob:hitbox()}'s {@code obst} rings) are four to eight points.
     */
    public static final int EDGES = 32;

    public static final Slot<PatchCarve> slot = new Slot<>(Slot.Type.DRAW, PatchCarve.class);

    /** One {@code (nx, ny, d, _)} inward half-plane per edge, in MAP space. Never mutated after construction. */
    public final float[][] e;

    /**
     * The border's colour at the border's own opacity, or an unread value while {@link #width} says there is
     * none. Never null: the uniform is resolved whether or not the band is switched on.
     */
    public final FColor rim;

    /** The border's thickness in MAP (= world) units, or <b>negative</b> for "no border" — see the class doc. */
    public final float width;

    PatchCarve(float[][] e, FColor rim, float width) {
        this.e = e;
        this.rim = rim;
        this.width = width;
    }

    /**
     * {@code float[][]} onto an array of {@code vec4}. The row for {@code Array(MAT4)} is already upstream and
     * this is its twin; without it {@code UniformApplier} would fall through to its element-by-element path,
     * which resolves one uniform location per index. Registered under the <b>unsized</b> array type, which is
     * the key {@code UniformApplier.apply} looks an array value up under.
     */
    static {
        UniformApplier.TypeMapping.register(new Array(Type.VEC4), float[][].class, (gl, var, type, rows) -> {
            int n = Math.min(rows.length, ((Array)type).sz);
            float[] buf = new float[n * 4];
            for(int i = 0, o = 0; i < n; i++) {
                buf[o++] = rows[i][0];
                buf[o++] = rows[i][1];
                buf[o++] = rows[i][2];
                buf[o++] = rows[i][3];
            }
            gl.glUniform4fv(var, n, buf);
        });
    }

    private static final Uniform u_edge =
        new Uniform(new Array(Type.VEC4, EDGES), "patchedge", p -> p.get(slot).e, slot);
    /** How many of {@link #u_edge} are this patch's — the loop bound, so a four-edge ring costs four dots. */
    private static final Uniform u_edges =
        new Uniform(Type.INT, "patchedges", p -> Integer.valueOf(p.get(slot).e.length), slot);

    /** The border's colour, at the border's own opacity — mixed over the fill inside the band. */
    private static final Uniform u_rim =
        new Uniform(Type.VEC4, "patchrim", p -> p.get(slot).rim, slot);
    /** The border's width in map units, or negative for none: the sentinel {@code step} reads. */
    private static final Uniform u_rimw =
        new Uniform(Type.FLOAT, "patchrimw", p -> Float.valueOf(p.get(slot).width), slot);

    /** Core GLSL the DSL has no name for; {@code Function.Builtin}'s constructor is public, so this is it. */
    private static final Function.Builtin fwidth =
        new Function.Builtin(Type.FLOAT, new Symbol.Fix("fwidth"), 1);

    /**
     * <b>The fill, the border and the silhouette, in one expression</b> — the fragment's incoming colour in,
     * the finished colour out. {@code fwidth} is taken on the finished minimum, after the loop, where the
     * control flow is uniform across the quad (the bound is a uniform) and the derivative is therefore
     * defined; every later term is that one {@code fw}, so the band and the silhouette are measured against
     * the very same pixel.
     *
     * <p>Both halves are ONE function rather than two, because both are read off {@code m} and a second call
     * would walk the edge loop a second time. They are one {@code mod} for the same reason the mix sits
     * inside it: a mod above this one would be handed the mixed colour with no way left to tell the fill from
     * the line.
     */
    private static final Function.Def carve = new Function.Def(Type.VEC4, "patchcarve") {{
        Expression in = param(Function.PDir.IN, Type.VEC4).ref();
        Expression p = pick(Homo3D.fragmapv.ref(), "xy");
        /* Bigger than any distance a fragment inside the mask can be from an edge, and written so that
         * Double.toString emits it without an exponent -- the loop always runs (a patch has three edges at
         * the least), so it is only ever the seed of the minimum. */
        LValue m = code.local(Type.FLOAT, l(1000000.0)).ref();
        LValue i = code.local(Type.INT, null).ref();
        Expression edge = idx(u_edge.ref(), i);
        code.add(new For(ass(i, l(0)), lt(i, u_edges.ref()), linc(i),
                         stmt(ass(m, min(m, sub(dot(p, pick(edge, "xy")), pick(edge, "z")))))));
        LValue fw = code.local(Type.FLOAT, fwidth.call(m)).ref();
        /* The floor: one screen pixel of world, so a world-unit width never thins out of sight at distance. */
        LValue w = code.local(Type.FLOAT, max(u_rimw.ref(), fw)).ref();
        /* Inside the band, and switched on at all: 1 at the ring's own edge, 0 once m has run w past it. */
        Expression rim = mul(step(l(0.0), u_rimw.ref()),
                             sub(l(1.0), smoothstep(sub(w, fw), add(w, fw), m)));
        /* The line over the fill, each at its own opacity, and then the silhouette over the pair. */
        code.add(new Return(mul(mix(in, u_rim.ref(), rim),
                                vec4(l(1.0), l(1.0), l(1.0), smoothstep(neg(fw), fw, m)))));
    }};

    private static final ShaderMacro shader = prog -> {
        /* After BaseColor's 0: what it wrote is the patch's FILL, and this lays the border over that and then
         * scales the pair's alpha by the silhouette. */
        FragColor.fragcol(prog.fctx).mod(in -> carve.call(in), 500);
    };

    public ShaderMacro shader() {return(shader);}
    public void apply(Pipe p) {p.put(slot, this);}

    public String toString() {
        return(String.format("#<patch-carve %d%s>", e.length, (width < 0) ? "" : (" rim " + width)));
    }

    /**
     * <b>The inward half-planes of a convex ring given in WORLD coordinates.</b> Every point crosses into map
     * space here and once ({@code y} negated), the inward side is decided per edge by which side the centroid
     * falls on — so the winding does not matter — and a repeated point contributes no edge, because a
     * zero-length segment constrains nothing.
     *
     * <p>Its length is therefore the number of edges the ring really has, which is what
     * {@link #u_edges} publishes and what {@link #tooMany} is measured against.
     */
    static float[][] of(List<Coord2d> ring) {
        int n = ring.size();
        double[] px = new double[n], py = new double[n];
        for(int i = 0; i < n; i++) {
            px[i] = ring.get(i).x;
            py[i] = -ring.get(i).y;                    /* into map space, once, here */
        }
        return planes(px, py, n);
    }

    /**
     * <b>The inward half-planes of a convex ring whose points are already in the space they are wanted in</b>
     * — the body {@link #of} is the map-space caller of, and the one {@link PatchClick} is the SCREEN-space
     * caller of (118.3). Nothing here knows which space it is in: the inward side is decided per edge by
     * which side the centroid falls on, so a ring may be wound either way and a space whose {@code y} grows
     * the other way is simply a ring wound the other way.
     */
    static float[][] planes(double[] px, double[] py, int n) {
        double cx = 0, cy = 0;
        for(int i = 0; i < n; i++) {
            cx += px[i];
            cy += py[i];
        }
        cx /= n;
        cy /= n;
        List<float[]> out = new ArrayList<float[]>();
        for(int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            double ex = px[j] - px[i], ey = py[j] - py[i];
            double nx = -ey, ny = ex;                  /* one of the two normals */
            double len = Math.hypot(nx, ny);
            if(len < EPS)
                continue;                              /* a repeated point: no edge, and so no half-plane */
            nx /= len;
            ny /= len;
            if(((nx * (cx - px[i])) + (ny * (cy - py[i]))) < 0) {
                nx = -nx;                              /* the centroid says that was the outward one */
                ny = -ny;
            }
            out.add(new float[] {(float)nx, (float)ny, (float)((nx * px[i]) + (ny * py[i])), 0f});
        }
        return out.toArray(new float[0][]);
    }

    /**
     * <b>Is {@code (x, y)} inside the ring these half-planes are the intersection of?</b> — the fragment's own
     * test ({@link #carve}) in Java, without the smoothstep: the polygon's signed distance is the MINIMUM over
     * the edges, and a point is inside wherever that is not negative. What answers a click is therefore the very
     * arithmetic that carved what was drawn, run over the ring projected to the screen rather than over the ring
     * in map space (118.3, {@link PatchClick}).
     */
    static boolean inside(float[][] e, double x, double y) {
        for(float[] p : e) {
            if((((p[0] * x) + (p[1] * y)) - p[2]) < 0)
                return false;
        }
        return true;
    }

    /** Below this a segment is a repeated point rather than an edge, in world units (a tile is 11). */
    private static final double EPS = 1e-9;

    /**
     * <b>Is this ring convex?</b> — the one question the carve cannot answer for itself, because the
     * intersection of a concave ring's half-planes is not that ring but its hull, and drawing the hull would be
     * the wrong shape rather than a refusal. Collinear points are convex; the winding may be either way, since
     * every cross product flips together with it.
     */
    static boolean convex(List<Coord2d> ring) {
        int n = ring.size();
        int sign = 0;
        for(int i = 0; i < n; i++) {
            Coord2d a = ring.get(i), b = ring.get((i + 1) % n), c = ring.get((i + 2) % n);
            double cross = ((b.x - a.x) * (c.y - b.y)) - ((b.y - a.y) * (c.x - b.x));
            if(Math.abs(cross) < EPS)
                continue;                              /* three points on a line bend neither way */
            int s = (cross > 0) ? 1 : -1;
            if(sign == 0)
                sign = s;
            else if(s != sign)
                return false;
        }
        return true;
    }

    /** Does this ring carry more edges than the fragment stage holds ({@link #EDGES})? */
    static boolean tooMany(float[][] e) {
        return e.length > EDGES;
    }
}
