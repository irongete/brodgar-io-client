package io.brodgar.addon;

import haven.GSettings;
import haven.RUtils;
import haven.RenderedNormals;
import haven.UI;
import haven.render.DepthBuffer;
import haven.render.FragColor;
import haven.render.FrameConfig;
import haven.render.Pipe;
import haven.render.RenderTree;
import haven.render.Rendered;
import haven.render.Tex2D;
import haven.render.Texture;
import haven.render.Texture2D;
import haven.render.TickList;
import haven.render.sl.Block;
import haven.render.sl.Discard;
import haven.render.sl.Expression;
import haven.render.sl.For;
import haven.render.sl.Function;
import haven.render.sl.If;
import haven.render.sl.LValue;
import haven.render.sl.Return;
import haven.render.sl.ShaderMacro;
import haven.render.sl.Uniform;

import java.util.ArrayList;
import java.util.List;

import static haven.render.sl.Cons.*;
import static haven.render.sl.Type.*;

/**
 * <b>The rings</b> (spec {@code 165-gob-outline}): two screen passes over the {@link OutlineMask}, in the map
 * view's {@code basic} slot beside {@code haven.Outlines}, drawn only while {@link GobOutline#widest()} says a
 * ring is live. {@code haven.Outlines}' pattern — a {@code Rendered.ScreenQuad} whose state samples a buffer the
 * scene wrote — twice over, because a ring up to eight design pixels wide searched in one pass costs the square of
 * its reach a pixel.
 *
 * <p><b>Pass A</b> renders into the mask's row target: for each pixel, the marked texel along its row within the
 * reach that minimises {@code dx² − R²} ({@code R} that texel's ring radius in render pixels), as {@code dx}.
 * <b>Pass B</b> renders into the scene colour under the view's blend: it discards a marked pixel (nothing is
 * painted inside), walks its column within the reach, reads each row's best seed, and keeps the one whose ring
 * edge is nearest — {@code d − R} least. It paints that seed's colour with {@code clamp(R + ½ − d, 0, 1)} coverage,
 * scaled by the seed's strength, and discards beyond it. With one width in view the choice per row is exact; at
 * the seam of two rings of different widths it is the nearer edge of the two a row offers.
 *
 * <p><b>The reach</b> is {@code ceil(widest · pf)} taps each way, {@code pf = UI.scale(1) × rscale} render pixels
 * a design pixel, so a width means the same on screen at any interface or render scale. Its own tick watches the
 * widest live ring and the interface scale, and re-adds both passes when either moves: the passes' state reads
 * them when it is built.
 *
 * <p><b>The order</b>: {@code 5600} and {@code 5601}, after {@code postfx} (5000) and {@code postpfx} (5500),
 * before water (6000) and the eye-sorted translucent geometry (10000) — so what is see-through in front veils the
 * ring, as it veils the object.
 */
public final class OutlineRing implements RenderTree.Node, TickList.TickNode, TickList.Ticking {
    private static final Rendered.Order ORDER_A = new Rendered.Order.Default(5600);
    private static final Rendered.Order ORDER_B = new Rendered.Order.Default(5601);

    private static final Uniform smask = new Uniform(SAMPLER2D, p -> ((Draw)p.get(RUtils.adhoc)).mask, RUtils.adhoc);
    private static final Uniform srow = new Uniform(SAMPLER2D, p -> ((Draw)p.get(RUtils.adhoc)).row, RUtils.adhoc);
    private static final Uniform upf = new Uniform(FLOAT, p -> ((Draw)p.get(RUtils.adhoc)).pf, RUtils.adhoc);
    private static final Uniform ureach = new Uniform(INT, p -> ((Draw)p.get(RUtils.adhoc)).reach, RUtils.adhoc);

    /** The samplers and the two numbers the passes read, per pass. */
    private static final class Draw extends RUtils.AdHoc {
        final Texture2D.Sampler2D mask, row;
        final float pf;
        final int reach;

        Draw(ShaderMacro code, Texture2D mask, Texture2D row, float pf, int reach) {
            super(code);
            this.mask = sampler(mask);
            this.row = (row == null) ? null : sampler(row);
            this.pf = pf;
            this.reach = reach;
        }

        private static Texture2D.Sampler2D sampler(Texture2D tex) {
            Texture2D.Sampler2D s = new Texture2D.Sampler2D(tex);
            s.magfilter(Texture.Filter.NEAREST).minfilter(Texture.Filter.NEAREST);
            s.swrap(Texture.Wrapping.CLAMP).twrap(Texture.Wrapping.CLAMP);
            return s;
        }
    }

    /** The mask texel {@code dx}, {@code dy} render pixels from the fragment's own. */
    private static Expression tap(Uniform s, Expression tc, Expression dx, Expression dy) {
        return texture2D(s.ref(), add(tc, mul(vec2(dx, dy), FrameConfig.u_pixelpitch.ref())));
    }

    /** The alpha byte of a texel, as the whole number it was written as. */
    private static Expression abyte(Expression texel) {
        return floor(add(mul(pick(texel, "a"), l(255.0)), l(0.5)));
    }

    private static final ShaderMacro passA = new ShaderMacro() {
            final Function best = new Function.Def(VEC4) {{
                Expression tc = Tex2D.rtexcoord.ref();
                LValue bs = code.local(FLOAT, l(1e9)).ref();
                LValue bdx = code.local(FLOAT, l(0.0)).ref();
                LValue i = code.local(INT, null).ref();
                Block body = new Block();
                Expression fi = body.local(FLOAT, floatcons(i)).ref();
                Expression tx = body.local(VEC4, tap(smask, tc, fi, l(0.0))).ref();
                Expression ab = body.local(FLOAT, abyte(tx)).ref();
                Block marked = new Block();
                Expression rad = marked.local(FLOAT, mul(add(floor(div(ab, l(32.0))), l(1.0)), upf.ref())).ref();
                Expression sc = marked.local(FLOAT, sub(mul(fi, fi), mul(rad, rad))).ref();
                marked.add(new If(lt(sc, bs), new Block(stmt(ass(bs, sc)), stmt(ass(bdx, fi)))));
                body.add(new If(gt(ab, l(0.5)), marked));
                code.add(new For(ass(i, neg(ureach.ref())), le(i, ureach.ref()), linc(i), body));
                code.add(new Return(vec4(div(add(bdx, l(128.0)), l(255.0)), step(bs, l(1e8)), l(0.0), l(1.0))));
            }};

            public void modify(haven.render.sl.ProgramContext prog) {
                FragColor.fragcol(prog.fctx).mod(in -> best.call(), 0);
            }
        };

    private static final ShaderMacro passB = new ShaderMacro() {
            final Function ring = new Function.Def(VEC4) {{
                Expression tc = Tex2D.rtexcoord.ref();
                code.add(new If(gt(pick(texture2D(smask.ref(), tc), "a"), l(0.0)), new Discard()));
                LValue bs = code.local(FLOAT, l(1e9)).ref();
                LValue col = code.local(VEC4, vec4(l(0.0), l(0.0), l(0.0), l(0.0))).ref();
                LValue j = code.local(INT, null).ref();
                Block body = new Block();
                Expression fj = body.local(FLOAT, floatcons(j)).ref();
                Expression rt = body.local(VEC4, tap(srow, tc, l(0.0), fj)).ref();
                Block seeded = new Block();
                Expression dx = seeded.local(FLOAT, sub(floor(add(mul(pick(rt, "r"), l(255.0)), l(0.5))), l(128.0))).ref();
                Expression tx = seeded.local(VEC4, tap(smask, tc, dx, fj)).ref();
                Expression ab = seeded.local(FLOAT, abyte(tx)).ref();
                Expression wi = seeded.local(FLOAT, floor(div(ab, l(32.0)))).ref();
                Expression sc = seeded.local(FLOAT, sub(sqrt(add(mul(dx, dx), mul(fj, fj))),
                                                        mul(add(wi, l(1.0)), upf.ref()))).ref();
                seeded.add(new If(lt(sc, bs), new Block(
                    stmt(ass(bs, sc)),
                    stmt(ass(col, vec4(pick(tx, "rgb"), sub(ab, mul(l(32.0), wi))))))));
                body.add(new If(gt(pick(rt, "g"), l(0.5)), seeded));
                code.add(new For(ass(j, neg(ureach.ref())), le(j, ureach.ref()), linc(j), body));
                Expression cov = code.local(FLOAT, clamp(sub(l(0.5), bs), l(0.0), l(1.0))).ref();
                code.add(new If(le(cov, l(0.0)), new Discard()));
                code.add(new Return(vec4(pick(col, "rgb"), div(mul(cov, pick(col, "a")), l(31.0)))));
            }};

            public void modify(haven.render.sl.ProgramContext prog) {
                FragColor.fragcol(prog.fctx).mod(in -> ring.call(), 0);
            }
        };

    private final List<RenderTree.Slot> parents = new ArrayList<RenderTree.Slot>();
    private final List<RenderTree.Slot> quads = new ArrayList<RenderTree.Slot>();
    /** The widest ring and the interface scale the passes were last built for; {@code -1} forces a rebuild. */
    private int curw = -1;
    private double curscale = 0;

    public void added(RenderTree.Slot slot) {
        OutlineMask.get(slot.state());
        synchronized(this) {
            parents.add(slot);
            curw = -1;
        }
    }

    public void removed(RenderTree.Slot slot) {
        synchronized(this) {
            parents.remove(slot);
            curw = -1;
        }
        OutlineMask.put(slot.state());
    }

    public TickList.Ticking ticker() {
        return this;
    }

    public void autotick(double dt) {
        int w = GobOutline.widest();
        double sc = UI.scale(1.0);
        List<RenderTree.Slot> ps, old;
        synchronized(this) {
            if((w == curw) && (sc == curscale))
                return;
            curw = w;
            curscale = sc;
            ps = new ArrayList<RenderTree.Slot>(parents);
            old = new ArrayList<RenderTree.Slot>(quads);
            quads.clear();
        }
        for(RenderTree.Slot q : old) {
            try {
                q.remove();
            } catch(RenderTree.SlotRemoved e) {
                /* its parent went first, and took it along */
            }
        }
        if(w <= 0)
            return;
        List<RenderTree.Slot> added = new ArrayList<RenderTree.Slot>();
        for(RenderTree.Slot p : ps) {
            try {
                added.add(p.add(new Rendered.ScreenQuad(false), pass(passA, ORDER_A, w, true)));
                added.add(p.add(new Rendered.ScreenQuad(false), pass(passB, ORDER_B, w, false)));
            } catch(RenderTree.SlotRemoved e) {
                /* the view went away between the copy and the add */
            }
        }
        synchronized(this) {
            quads.addAll(added);
        }
    }

    /** One pass's state: the mask and normals outputs and the depth buffer off, and the pass's own target. */
    private static Pipe.Op pass(ShaderMacro code, Rendered.Order order, int widest, boolean rows) {
        return p -> {
            OutlineMask m = p.get(OutlineMask.slot);
            float pf = (float)(UI.scale(1.0) * p.get(GSettings.slot).rscale.val);
            int reach = (int)Math.ceil(widest * pf);
            p.prep(order);
            p.put(OutlineMask.slot, null);
            p.put(RenderedNormals.slot, null);
            p.put(DepthBuffer.slot, null);
            if(rows) {
                p.prep(new FragColor<>(m.row.image(0)));
                p.put(FragColor.blend, null);
            }
            p.prep(new Draw(code, m.mask, rows ? null : m.row, pf, reach));
        };
    }
}
