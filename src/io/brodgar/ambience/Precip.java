package io.brodgar.ambience;

import java.nio.*;
import java.util.Random;
import haven.*;
import haven.render.*;
import haven.render.sl.*;
import static haven.render.sl.Cons.*;
import static haven.render.sl.Type.*;

/* SPIKE: rain and snow of our own, moved entirely on the GPU.
 *
 * Every drop is a fixed random point in a box that stands around the player; the vertex shader moves
 * it with the wind and wraps it round the box, so nothing is simulated on the CPU and nothing is
 * uploaded per frame -- one static buffer, one draw call. The wrap is anchored to the world rather
 * than to the player, so walking through the rain passes drops rather than dragging them along.
 * Rain is a line from a bright head to a faded tail along the wind; snow is a round soft point that
 * sways as it falls. Both fade out with distance, and are depth-tested but write no depth. */
public class Precip implements RenderTree.Node, Rendered {
    public static final int MAXRAIN = 20000, MAXSNOW = 16000;
    static final Attribute seed = new Attribute(VEC4, "ambseed").primary();
    static final Rendered.Order order = new Rendered.Order.Default(9000);

    public final boolean snow;
    public final int n;
    private final Model model;
    private final Pipe.Op state;

    public Precip(boolean snow, int n) {
	this.snow = snow;
	this.n = n;
	this.model = snow ? new Model(Model.Mode.POINTS, snowdata(), null, 0, n)
	                  : new Model(Model.Mode.LINES, raindata(), null, 0, n * 2);
	/* No normals output either: RenderedNormals, when the outlines have installed it, would pull
	 * Homo3D's own vertex transform into the program beside this one's. */
	this.state = Pipe.Op.compose(new Xf(snow), States.maskdepth, order, p -> p.put(RenderedNormals.slot, null));
    }

    private static VertexArray rainva = null, snowva = null;

    private static VertexArray mkva(float[] data) {
	FloatBuffer fb = FloatBuffer.wrap(data);
	VertexArray.Layout fmt = new VertexArray.Layout(new VertexArray.Layout.Input(seed, new VectorFormat(4, NumberFormat.FLOAT32), 0, 0, 16));
	return(new VertexArray(fmt, new VertexArray.Buffer(data.length * 4, DataBuffer.Usage.STATIC, DataBuffer.Filler.of(fb))).shared());
    }

    /* A drop is two vertices sharing one place: w = 0 is its head, w = 1 its tail. */
    private static synchronized VertexArray raindata() {
	if(rainva == null) {
	    Random rnd = new Random(1);
	    float[] d = new float[MAXRAIN * 8];
	    for(int i = 0; i < MAXRAIN; i++) {
		float x = rnd.nextFloat(), y = rnd.nextFloat(), z = rnd.nextFloat();
		d[(i * 8) + 0] = x; d[(i * 8) + 1] = y; d[(i * 8) + 2] = z; d[(i * 8) + 3] = 0;
		d[(i * 8) + 4] = x; d[(i * 8) + 5] = y; d[(i * 8) + 6] = z; d[(i * 8) + 7] = 1;
	    }
	    rainva = mkva(d);
	}
	return(rainva);
    }

    /* A flake is one vertex; w is its phase in the sway. */
    private static synchronized VertexArray snowdata() {
	if(snowva == null) {
	    Random rnd = new Random(2);
	    float[] d = new float[MAXSNOW * 4];
	    for(int i = 0; i < d.length; i++)
		d[i] = rnd.nextFloat();
	    snowva = mkva(d);
	}
	return(snowva);
    }

    /* The box, per kind: how far it reaches around the player and how far up and down. */
    static float[] box(boolean snow) {
	return(snow ? new float[] {900, 900, 500} : new float[] {1100, 1100, 700});
    }

    static float[] vel(boolean snow) {
	float[] w = Ambience.frame().wind;
	if(snow)
	    return(new float[] {w[0] * 0.15f, w[1] * 0.15f, -38});
	return(new float[] {w[0], w[1], w[2]});
    }

    /* The box's low corner: the player, less half the box across and 200 units down. */
    static float[] lo(boolean snow) {
	float[] b = box(snow);
	Coord3f f = Ambience.frame().focus;
	return(new float[] {f.x - (b[0] / 2), f.y - (b[1] / 2), f.z - 200});
    }

    /* How far the world-anchored drops have drifted into the box at time t, worked in doubles so hours
     * of play lose nothing to float precision; the shader adds it and wraps. */
    static float[] off(boolean snow, double t) {
	float[] b = box(snow), v = vel(snow), lo = lo(snow);
	float[] ret = new float[3];
	for(int i = 0; i < 3; i++) {
	    double x = ((v[i] * t) - lo[i]) % b[i];
	    ret[i] = (float)((x < 0) ? (x + b[i]) : x);
	}
	return(ret);
    }

    static final Uniform u_pvm = new Uniform(MAT4, p -> Homo3D.prjxf(p).mul(Homo3D.camxf(p)), Homo3D.prj, Homo3D.cam);
    static final Uniform u_eye = new Uniform(VEC3, p -> Ambience.eyeof(Homo3D.camxf(p)), Homo3D.cam);
    static final Uniform u_box = new Uniform(VEC3, p -> box(((Xf)p.get(States.vxf)).snow), States.vxf);
    static final Uniform u_lo = new Uniform(VEC3, p -> lo(((Xf)p.get(States.vxf)).snow), States.vxf, FrameInfo.slot);
    static final Uniform u_off = new Uniform(VEC3, p -> off(((Xf)p.get(States.vxf)).snow, p.get(FrameInfo.slot).time), States.vxf, FrameInfo.slot);
    static final Uniform u_vel = new Uniform(VEC3, p -> vel(((Xf)p.get(States.vxf)).snow), States.vxf, FrameInfo.slot);
    static final Uniform u_col = new Uniform(VEC4, p -> ((Xf)p.get(States.vxf)).snow ? Ambience.frame().scol : Ambience.frame().rcol, States.vxf, FrameInfo.slot);
    /* How many pixels a unit of flake is at a unit of distance. */
    static final Uniform u_psz = new Uniform(FLOAT, p -> 1.8f * Homo3D.prjxf(p).m[5] * p.get(FrameConfig.slot).sz.y * 0.5f, Homo3D.prj, FrameConfig.slot);

    /* Where this vertex's drop is, in map space. */
    static Expression wpos(boolean snow) {
	Expression s = seed.ref();
	Expression w = add(u_lo.ref(), mod(add(mul(pick(s, "xyz"), u_box.ref()), u_off.ref()), u_box.ref()));
	if(snow) {
	    Expression ph = mul(pick(seed.ref(), "w"), l(6.2832));
	    Expression t = FrameInfo.time();
	    w = add(w, vec3(mul(sin(add(mul(t, l(1.3)), ph)), l(6.0)),
			    mul(sin(add(mul(t, l(0.9)), mul(ph, l(1.7)))), l(6.0)),
			    l(0.0)));
	} else {
	    /* The tail trails the head by a fifteenth of a second of its fall. */
	    w = sub(w, mul(u_vel.ref(), mul(pick(seed.ref(), "w"), l(0.07))));
	}
	return(w);
    }

    static final AutoVarying ralpha = new AutoVarying(FLOAT, "s_ambra") {
	    protected Expression root(VertexContext vctx) {
		Expression d = length(sub(wpos(false), u_eye.ref()));
		return(mul(sub(l(1.0), pick(seed.ref(), "w")),
			   smoothstep(l(8.0), l(50.0), d),
			   sub(l(1.0), smoothstep(l(350.0), l(560.0), d))));
	    }
	};

    static final AutoVarying salpha = new AutoVarying(FLOAT, "s_ambsa") {
	    protected Expression root(VertexContext vctx) {
		Expression d = length(sub(wpos(true), u_eye.ref()));
		return(mul(smoothstep(l(2.0), l(10.0), d), sub(l(1.0), smoothstep(l(250.0), l(440.0), d))));
	    }
	};

    static final ShaderMacro rainsh = prog -> {
	prog.vctx.posv.mod(in -> mul(u_pvm.ref(), vec4(wpos(false), l(1.0))), 0);
	FragColor.fragcol(prog.fctx).mod(in -> vec4(pick(u_col.ref(), "rgb"), mul(pick(u_col.ref(), "a"), ralpha.ref())), 0);
    };

    static final ShaderMacro snowsh = prog -> {
	prog.vctx.posv.mod(in -> mul(u_pvm.ref(), vec4(wpos(true), l(1.0))), 0);
	prog.vctx.ptsz.force();
	prog.vctx.ptsz.mod(in -> clamp(div(u_psz.ref(), max(length(sub(wpos(true), u_eye.ref())), l(1.0))), l(1.0), l(14.0)), 0);
	Expression round = sub(l(1.0), smoothstep(l(0.2), l(0.5), length(sub(pick(FragmentContext.ptc, "xy"), vec2(l(0.5), l(0.5))))));
	FragColor.fragcol(prog.fctx).mod(in -> vec4(pick(u_col.ref(), "rgb"), mul(pick(u_col.ref(), "a"), salpha.ref(), round)), 0);
    };

    /* The vertex transform of its own: it stands in the slot Homo3D's would, as Ortho2D does. */
    static class Xf extends State {
	final boolean snow;

	Xf(boolean snow) {this.snow = snow;}

	public ShaderMacro shader() {return(snow ? snowsh : rainsh);}
	public void apply(Pipe p) {p.put(States.vxf, this);}

	public boolean equals(Object o) {return((o instanceof Xf) && (((Xf)o).snow == snow));}
	public int hashCode() {return(snow ? 1 : 0);}
    }

    public void draw(Pipe state, Render out) {
	out.draw(state, model);
    }

    public void added(RenderTree.Slot slot) {
	slot.ostate(state);
    }
}
