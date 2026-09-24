package io.brodgar.addon;

import haven.Disposable;
import haven.FColor;
import haven.RenderContext;
import haven.render.BufPipe;
import haven.render.DataBuffer;
import haven.render.FragColor;
import haven.render.FrameConfig;
import haven.render.NumberFormat;
import haven.render.Pipe;
import haven.render.Render;
import haven.render.State;
import haven.render.States;
import haven.render.Texture;
import haven.render.Texture2D;
import haven.render.VectorFormat;
import haven.render.sl.Block;
import haven.render.sl.Expression;
import haven.render.sl.FragData;
import haven.render.sl.FragmentContext;
import haven.render.sl.LBinOp;
import haven.render.sl.ShaderMacro;
import haven.render.sl.Type;
import haven.render.sl.ValBlock;

import static haven.Utils.eq;
import static haven.render.sl.Cons.l;
import static haven.render.sl.Cons.vec4;

/**
 * <b>The outline mask</b> (spec {@code 165-gob-outline}): a second colour output every program of the map view
 * writes, beside {@code haven.RenderedNormals}' normals, and read back by {@link OutlineRing}'s two screen passes.
 * A copy of {@code RenderedNormals}, output for output: a SYS {@link State} naming the image, a {@link FragData}
 * whose value is that image or {@code null} ({@code GL_NONE}) under {@code States.maskdepth}, and a {@link Canon}
 * the render context shares out by reference count.
 *
 * <p><b>The texel.</b> {@code rgb} is the ring's colour, and the alpha byte carries the rest: {@code 32·(width−1)
 * + q}, {@code width} the ring's in design pixels ({@code 1..8}) and {@code q = max(1, round(a·31/255))} its
 * strength in 31 steps. "Marked" is an alpha above zero. Everything this state's macro reaches writes the mask,
 * an unmarked fragment writing {@code 0}: so an occluder in front of a marked object clears the mask where it
 * stands, and the ring follows the visible part only. {@link GobOutline.Paint} is what writes a mark, as a
 * {@link ValBlock.Value#mod} over this state's value.
 *
 * <p><b>Always installed.</b> The mask is in every map-view program whether or not an outline is live, so the
 * first outline recompiles nothing: the cost is four bytes a solid fragment and a clear a frame. What an outline
 * switches on is the two passes that read it.
 */
public final class OutlineMask extends State {
    public static final Slot<OutlineMask> slot = new Slot<OutlineMask>(Slot.Type.SYS, OutlineMask.class);
    public static final FragData fragoutl = new FragData(Type.VEC4, "fragoutl",
        p -> ((p.get(States.maskdepth.slot) == null) ? p.get(slot).img : null), slot, States.maskdepth.slot);

    /** The mask image every scene program writes. */
    public final Texture.Image<?> img;
    /** The row target {@link OutlineRing}'s first pass writes and its second reads: the same size as the mask. */
    public final Texture2D mask, row;

    OutlineMask(Texture2D mask, Texture2D row) {
        this.mask = mask;
        this.row = row;
        this.img = mask.image(0);
    }

    public boolean equals(Object o) {
        return (o instanceof OutlineMask) && eq(((OutlineMask)o).img, this.img)
            && (((OutlineMask)o).row == this.row);
    }

    public int hashCode() {
        return System.identityHashCode(mask);
    }

    /**
     * The mask's value in a fragment program: {@code vec4(0)} unless a {@link GobOutline.Paint} modifies it, and
     * assigned to {@link #fragoutl}. Shared through {@code mainvals.ext}, so the state that forces it and the
     * paint that marks it reach the one value.
     */
    public static ValBlock.Value value(final FragmentContext fctx) {
        return fctx.mainvals.ext(fragoutl, () -> fctx.mainvals.new Value(Type.VEC4) {
                public Expression root() {
                    return vec4(l(0.0), l(0.0), l(0.0), l(0.0));
                }

                protected void cons2(Block blk) {
                    blk.add(new LBinOp.Assign(fragoutl.ref(), init));
                }
            });
    }

    private static final ShaderMacro shader = prog -> value(prog.fctx).force();

    public ShaderMacro shader() {
        return shader;
    }

    public void apply(Pipe p) {
        p.put(slot, this);
    }

    /**
     * <b>The two textures, one per render context</b>, at {@code FrameConfig.sz} and re-allocated with it. The
     * mask is cleared before every frame; the row target is written whole by the pass that fills it, so it is
     * never cleared.
     */
    public static final class Canon implements Pipe.Op, Disposable, RenderContext.Global {
        private Texture2D mask, row;
        private OutlineMask state;
        private int refcount = 0;

        public void apply(Pipe p) {
            FrameConfig fb = p.get(FrameConfig.slot);
            if((mask == null) || !mask.sz().equals(fb.sz)) {
                dispose();
                mask = new Texture2D(fb.sz, DataBuffer.Usage.STATIC, new VectorFormat(4, NumberFormat.UNORM8), null);
                row = new Texture2D(fb.sz, DataBuffer.Usage.STATIC, new VectorFormat(2, NumberFormat.UNORM8), null);
                state = new OutlineMask(mask, row);
            }
            p.prep(state);
        }

        /** Both textures, once: {@code RenderContext.put} disposes a {@code Global} and {@link #put} does too. */
        public void dispose() {
            if(mask != null)
                mask.dispose();
            if(row != null)
                row.dispose();
            mask = row = null;
            state = null;
        }

        public void prerender(Render out) {
            OutlineMask st = state;
            if(st != null)
                out.clear(new BufPipe().prep(new FragColor<>(st.img)), FragColor.fragcol, new FColor(0, 0, 0, 0));
        }
    }

    /** Take a reference on the context's {@link Canon}, installing it on the first. {@code null} with no context. */
    public static Canon get(Pipe state) {
        RenderContext ctx = state.get(RenderContext.slot);
        if(ctx == null)
            return null;
        Canon ret;
        synchronized(ctx) {
            ret = (Canon)ctx.basic(Canon.class);
            if(ret == null) {
                ret = new Canon();
                ctx.basic(Canon.class, ret);
                ctx.add(ret);
            }
            ret.refcount++;
        }
        return ret;
    }

    /** Drop the reference {@link #get} took, uninstalling the {@link Canon} with the last. */
    public static void put(Pipe state) {
        RenderContext ctx = state.get(RenderContext.slot);
        if(ctx == null)
            return;
        synchronized(ctx) {
            Canon cur = (Canon)ctx.basic(Canon.class);
            if(cur == null)
                throw new IllegalStateException();
            if(--cur.refcount <= 0) {
                ctx.basic(Canon.class, null);
                ctx.put(cur);
                cur.dispose();
            }
        }
    }
}
