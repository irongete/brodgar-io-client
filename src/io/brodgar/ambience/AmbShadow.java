package io.brodgar.ambience;

import haven.*;
import haven.render.*;
import haven.render.sl.*;
import static haven.render.sl.Cons.*;
import static haven.render.sl.Type.*;

/* SPIKE: the clouds' shadows on the ground.
 *
 * The game's own CloudShadow in shape -- it dims the sun's light alone (MapView.amblight_idx), in every
 * per-fragment Phong program -- but cast by the ambience's own clouds: for each cloud whose shadow falls
 * where the camera sees, where the ray from this fragment toward the sun reaches the cloud's middle
 * height, and how close that falls to the cloud's centre, gives a soft round shadow under it, whichever
 * way the sun stands. A loop over a handful of clouds, no texture. Its numbers are uniforms over the frame, so the state itself is one
 * instance, installed once while there are clouds and never re-pushed. */
public class AmbShadow extends State {
    static final Slot<AmbShadow> slot = new Slot<>(Slot.Type.DRAW, AmbShadow.class);
    static final AmbShadow instance = new AmbShadow();

    /* The run toward the sun per unit of rise. */
    static final Uniform ssh = new Uniform(VEC2, p -> Ambience.frame().ssh, FrameInfo.slot);
    /* The shadows that fall where this camera sees (Ambience.Seen): how many, and each one's centre less
     * its run toward the sun from height zero, its radius and its strength. */
    static final Uniform nsh = new Uniform(INT, p -> SkyPass.seen(p).nsh, Homo3D.prj, Homo3D.cam, FrameInfo.slot);
    static final Uniform sh = new Uniform(new Array(VEC4, SkyPass.NEAR), p -> SkyPass.seen(p).sh, Homo3D.prj, Homo3D.cam, FrameInfo.slot);

    /* How much of the sun reaches a point of the ground, 1 for all of it. */
    static final Function shade = new Function.Def(FLOAT) {{
	Expression g = param(PDir.IN, VEC3).ref();
	LValue s = code.local(FLOAT, l(1.0)).ref();
	/* The ray from here toward the sun reaches a cloud's middle height z at g.xy + (z - g.z) * ssh; its
	 * distance from the cloud's centre is that of this point, moved back along the run by its own
	 * height, from the centre moved back by the cloud's. */
	Expression gp = code.local(VEC2, sub(pick(g, "xy"), mul(pick(g, "z"), ssh.ref()))).ref();
	LValue k = code.local(INT, null).ref();
	Block cb = new Block();
	code.add(new For(ass(k, l(0)), lt(k, nsh.ref()), linc(k), cb));
	Expression c = cb.local(VEC4, idx(sh.ref(), k)).ref();
	Expression d = length(sub(gp, pick(c, "xy")));
	/* Round, the size of the cloud's footprint; a faint cloud casts a faint one, a dark one takes more
	 * than half the sun away. */
	cb.add(amul(s, sub(l(1.0), mul(pick(c, "w"), sub(l(1.0), smoothstep(mul(pick(c, "z"), l(0.35)), pick(c, "z"), d))))));
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
