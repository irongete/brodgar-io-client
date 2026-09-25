package io.brodgar.ambience;

import haven.*;
import haven.render.*;
import haven.render.sl.*;
import static haven.render.sl.Cons.*;
import static haven.render.sl.Type.*;

/* SPIKE: the clouds' shadows on the ground.
 *
 * The game's own CloudShadow in shape -- it dims the sun's light alone (MapView.amblight_idx), in every
 * per-fragment Phong program -- but cast by the ambience's own clouds: for each cloud, where the ray
 * from this fragment toward the sun reaches the cloud's middle height, and how close that falls to the
 * cloud's centre, gives a soft round shadow under it, whichever way the sun stands. A loop over a
 * handful of clouds, no texture. Its numbers are uniforms over the frame, so the state itself is one
 * instance, installed once while there are clouds and never re-pushed. */
public class AmbShadow extends State {
    static final Slot<AmbShadow> slot = new Slot<>(Slot.Type.DRAW, AmbShadow.class);
    static final AmbShadow instance = new AmbShadow();

    /* The run toward the sun per unit of rise. */
    static final Uniform ssh = new Uniform(VEC2, p -> Ambience.frame().ssh, FrameInfo.slot);

    /* How much of the sun reaches a point of the ground, 1 for all of it. */
    static final Function shade = new Function.Def(FLOAT) {{
	Expression g = param(PDir.IN, VEC3).ref();
	LValue s = code.local(FLOAT, l(1.0)).ref();
	LValue k = code.local(INT, null).ref();
	Block cb = new Block();
	/* The near ones only: a far one's shadow falls where no ground is drawn. */
	code.add(new For(ass(k, l(0)), lt(k, SkyPass.nnear.ref()), linc(k), cb));
	Expression b = cb.local(VEC4, idx(SkyPass.cl.ref(), k)).ref();
	Expression info = cb.local(VEC4, idx(SkyPass.ci.ref(), k)).ref();
	Expression fp = cb.local(FLOAT, pick(idx(SkyPass.cx.ref(), k), "z")).ref();
	Expression at = cb.local(VEC2, add(pick(g, "xy"), mul(sub(pick(b, "z"), pick(g, "z")), ssh.ref()))).ref();
	Expression d = length(sub(at, pick(b, "xy")));
	/* Round, the size of the cloud's footprint; a thin high cloud, being faint, casts a faint one. */
	Expression cover = mul(pick(info, "x"), sub(l(1.0), smoothstep(mul(fp, l(0.35)), fp, d)));
	/* A cloud takes half the sun away, a dark one more. */
	cb.add(amul(s, sub(l(1.0), mul(cover, add(l(0.5), mul(pick(info, "z"), l(0.35)))))));
	code.add(new Return(s));
    }};

    private AmbShadow() {}

    private static final ShaderMacro shader = prog -> {
	final Phong ph = prog.getmod(Phong.class);
	if((ph == null) || !ph.pfrag)
	    return;
	final ValBlock.Value shval = prog.fctx.uniform.new Value(FLOAT) {
		public Expression root() {
		    return(shade.call(Homo3D.fragmapv.ref()));
		}

		protected void cons2(Block blk) {
		    tgt = new Variable.Global(FLOAT).ref();
		    blk.add(ass(tgt, init));
		}
	    };
	shval.force();
	ph.dolight.mod(() -> ph.dolight.dcalc.add(new If(eq(MapView.amblight_idx.ref(), ph.dolight.i),
							 stmt(amul(ph.dolight.dl.tgt, shval.ref()))),
						  ph.dolight.dcurs), 0);
    };

    public ShaderMacro shader() {return(shader);}
    public void apply(Pipe p) {p.put(slot, this);}
}
