package io.brodgar.ambience;

import java.nio.*;
import java.util.*;
import haven.*;
import haven.render.*;
import haven.render.sl.*;
import static haven.render.sl.Cons.*;
import static haven.render.sl.Type.*;

/* The rain, on the graphics card, as the game's own draws it (gfx/fx/rain v2).
 *
 * The same drops: each aimed at a point of the ground within 75 tiles of the player each way, falling for
 * a second and a half along the server's wind with its spread, a streak of the distance it falls in 0.03
 * seconds, fading from half opaque at its head to nothing; where it lands, four splashes thrown up and
 * out, each a parabola a sixth to a third of a second long. The same material -- the game's blue-white
 * Phong light, vertex colours, one-pixel lines, drawn late and without depth writes -- and the same
 * normals, packed the way the game packs them.
 *
 * A drop is one instance of ten vertices: the streak's two, then two for each splash. Its record says where
 * it lands, how it moves, when it was born, its normal and its four splashes; the program has the rest. */
class AmbRain extends Precip {
    static final float SZ = 75 * 11, FT = 0.03f, DROPLIFE = 1.5f, SPLASHLIFE = 0.15f, SVF = 15, G = -98.2f;

    /* The game's own material for it. */
    static final Pipe.Op mat = new Light.PhongLight(true,
						    new FColor(1.00f, 1.50f, 2.00f),
						    new FColor(1.00f, 1.50f, 2.00f),
						    new FColor(1.00f, 1.50f, 2.00f),
						    new FColor(0.25f, 0.37f, 0.50f), 10);
    static final Rendered.Order draworder = new Rendered.Order.Default(20000);

    /* Per vertex: its role (x 0 the streak, 1 a splash; y 0 the head, 1 the tail), its colour, and which
     * splash it is (one of four, as a mask). The primary attribute is a vertex's, not an instance's. */
    static final Attribute a_sel = new Attribute(VEC4, "prsel");
    /* Per drop: where it lands; its velocity and its birth; its splashes' two speeds and their lives. */
    static final Attribute a_t = new Attribute(VEC3, "prt");
    static final Attribute a_vb = new Attribute(VEC4, "prvb");
    static final Attribute a_sx = new Attribute(VEC4, "prsx");
    static final Attribute a_sy = new Attribute(VEC4, "prsy");
    static final Attribute a_sl = new Attribute(VEC4, "prsl");

    static final int VSTRIDE = 32, STRIDE = 80, BIRTH = 28;
    static final VertexArray.Layout fmt = new VertexArray.Layout(
	in(Homo3D.vertex, 3, NumberFormat.FLOAT32, 0, 0, VSTRIDE, false),
	in(VertexColor.color, 4, NumberFormat.UNORM8, 0, 12, VSTRIDE, false),
	in(a_sel, 4, NumberFormat.FLOAT32, 0, 16, VSTRIDE, false),
	in(a_t, 3, NumberFormat.FLOAT32, 1, 0, STRIDE, true),
	in(Homo3D.normal, 3, NumberFormat.SNORM8, 1, 12, STRIDE, true),
	in(a_vb, 4, NumberFormat.FLOAT32, 1, 16, STRIDE, true),
	in(a_sx, 4, NumberFormat.FLOAT32, 1, 32, STRIDE, true),
	in(a_sy, 4, NumberFormat.FLOAT32, 1, 48, STRIDE, true),
	in(a_sl, 4, NumberFormat.FLOAT32, 1, 64, STRIDE, true));

    static ByteBuffer vertices() {
	ByteBuffer buf = ByteBuffer.allocate(10 * VSTRIDE).order(ByteOrder.nativeOrder());
	for(int v = 0; v < 10; v++) {
	    int sp = (v / 2) - 1;
	    buf.putFloat((v < 2) ? 0 : 1).putFloat(v % 2).putFloat(0);
	    buf.putInt(((v % 2) == 0) ? 0x80ffffff : 0x00ffffff);
	    for(int k = 0; k < 4; k++)
		buf.putFloat((k == sp) ? 1 : 0);
	}
	buf.flip();
	return(buf);
    }

    static Expression age() {return(sub(u_time.ref(), pick(a_vb.ref(), "w")));}
    static Expression kind() {return(pick(Homo3D.vertex.ref(), "x"));}
    static Expression end() {return(pick(Homo3D.vertex.ref(), "y"));}
    static Expression slife() {return(dot(a_sl.ref(), a_sel.ref()));}

    /* Where the vertex is: on the streak, T - (life - age + end * ft) * v; on a splash thrown from T at
     * its own speed, (T.xy + v * t, G t^2 - G life t + T.z) at t its age, the tail 0.06 seconds behind. */
    static Expression pos() {
	Expression rem = add(sub(l(DROPLIFE), age()), mul(end(), l(FT)));
	Expression dpos = sub(a_t.ref(), mul(rem, pick(a_vb.ref(), "xyz")));
	Expression st = max(sub(sub(age(), l(DROPLIFE)), mul(end(), l(FT * 2))), l(0.0));
	Expression spos = vec3(add(pick(a_t.ref(), "x"), mul(dot(a_sx.ref(), a_sel.ref()), st)),
			       add(pick(a_t.ref(), "y"), mul(dot(a_sy.ref(), a_sel.ref()), st)),
			       add(mul(l(G), st, st), mul(l(-G), slife(), st), pick(a_t.ref(), "z")));
	return(mix(dpos, spos, kind()));
    }

    /* Whether it is there: the streak for its second and a half, a splash for its own life after. Else it
     * is put outside the view, where nothing of it is drawn. */
    static Expression alive() {
	Expression a = sub(age(), mul(kind(), l(DROPLIFE)));
	Expression life = mix(l(DROPLIFE), slife(), kind());
	return(mul(step(l(0.0), a), sub(l(1.0), step(life, a))));
    }

    static final ShaderMacro shader = prog -> {
	Homo3D homo = Homo3D.get(prog);
	homo.objv.mod(in -> vec4(pos(), l(1.0)), 0);
	prog.vctx.posv.mod(in -> mix(vec4(l(2.0), l(2.0), l(2.0), l(1.0)), in, alive()), 100);
    };

    ShaderMacro shader() {return(shader);}

    Pipe.Op states() {
	return(Pipe.Op.compose(VertexColor.instance, new States.LineWidth(1), mat, draworder, States.maskdepth, clock));
    }

    private final Random rnd = new Random();
    private float acc = 0;
    private boolean first = true;

    AmbRain() {
	super(STRIDE, BIRTH, fmt, Model.Mode.LINES, vertices(), 10, null);
    }

    static int snorm8(float v) {
	return(((int)(v * 0x7f)) & 0xff);
    }

    /* One tick's drops, as the game's Rain.tick makes them: a rate a second, around cc, the first tick
     * filling the whole of a drop's life at once. A drop is born some of the tick ago and then aged by the
     * tick, as the game ages its new drops in the very tick that makes them. */
    void spawn(java.util.function.Function<Coord2d, Coord3f> ground, Coord2d cc, double dt, float rate, Coord3f wv, Coord3f wvr) {
	double now = now();
	float itm = first ? DROPLIFE : (float)dt;
	first = false;
	acc += rate * itm;
	int n = (int)Math.floor(acc);
	acc -= n;
	synchronized(this) {
	    for(int i = 0; i < n; i++) {
		Coord3f tc;
		try {
		    tc = ground.apply(Coord2d.of(((rnd.nextFloat() * 2) - 1) * SZ, ((rnd.nextFloat() * 2) - 1) * SZ).add(cc));
		} catch(Loading l) {
		    continue;
		}
		float xv = wv.x + (((rnd.nextFloat() * 2) - 1) * wvr.x);
		float yv = wv.y + (((rnd.nextFloat() * 2) - 1) * wvr.y);
		float zv = wv.z + (((rnd.nextFloat() * 2) - 1) * wvr.z);
		float nx = (rnd.nextFloat() * 2) - 1, ny = (rnd.nextFloat() * 2) - 1;
		float nz = (float)Math.sqrt(1 - (nx * nx) - (ny * ny));
		/* The game's own packing, the third component shifted into the second's byte and all. */
		int norm = (snorm8(nx) << 0) | (snorm8(ny) << 8) | (snorm8(nz) << 8);
		double birth = now - dt - (rnd.nextFloat() * itm);
		float[] sx = new float[4], sy = new float[4], sl = new float[4];
		float lmax = 0;
		for(int k = 0; k < 4; k++) {
		    sx[k] = ((rnd.nextFloat() * 2) - 1) * SVF;
		    sy[k] = ((rnd.nextFloat() * 2) - 1) * SVF;
		    sl[k] = SPLASHLIFE + (rnd.nextFloat() * SPLASHLIFE);
		    lmax = Math.max(lmax, sl[k]);
		}
		int s = alloc(now, birth + DROPLIFE + lmax);
		putf(s, 0, tc.x); putf(s, 4, -tc.y); putf(s, 8, tc.z);
		puti(s, 12, norm);
		putf(s, 16, xv); putf(s, 20, yv); putf(s, 24, zv);
		birth(s, birth);
		for(int k = 0; k < 4; k++) {
		    putf(s, 32 + (k * 4), sx[k]);
		    putf(s, 48 + (k * 4), sy[k]);
		    putf(s, 64 + (k * 4), sl[k]);
		}
	    }
	}
    }
}
