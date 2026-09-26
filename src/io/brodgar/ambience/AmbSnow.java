package io.brodgar.ambience;

import java.nio.*;
import java.util.*;
import haven.*;
import haven.render.*;
import haven.render.sl.*;
import static haven.render.sl.Cons.*;
import static haven.render.sl.Type.*;

/* The snow, on the graphics card, looking as the game's own does (gfx/fx/snow v2).
 *
 * The same flakes: the game's own material (gfx/fx/snow-1), the same quad -- 1.5 units, its four corners
 * worked out from the flake's normal by the game's own formulas, so that it tumbles and twinkles the same
 * way -- the same normal, born 350 units over a point of the ground within 75 tiles of the player each way.
 *
 * Only the motion is not the game's step by step: there every flake is pushed about at random every tick,
 * which no program can replay. Here it is a function of the flake's age with the same measure, taken off
 * the game's own physics run for hundreds of flakes: from rest, it falls as a body does for three seconds
 * and then at some 29 units a second, a little slower as it goes, one flake up to a tenth faster or slower
 * than another; it wanders a few units off its line and drifts some 8 in its fall; it spins about an axis
 * of its own at 0.8 to 8 radians a second, coming up to speed over its first seconds. No wind, as the
 * game's snow has none. It is gone where it reaches the ground under where it will land. And no ceiling:
 * the game stops at 50,000 flakes, which a blizzard reaches.
 *
 * A flake is one instance of four vertices; each vertex says how its corner stands from the normal. */
class AmbSnow extends Precip {
    static final float SZ = 75 * 11, UP = 350, FSZ = 1.5f, MAXLIFE = 25;
    /* The fall: A t^2 up to T1, then D1 + V1 u - C u^2 at u past T1 (350 units in 13.8 s). */
    static final float A = 4.91f, T1 = 3.05f, D1 = A * T1 * T1, V1 = 2 * A * T1, C = 0.15f;
    /* The drift grows as (t / DT)^DP, DT the mean fall's time. */
    static final float DT = 13.8f, DP = 1.8f;
    /* How long a snowfall is filled at once when the snow is drawn anew (fill). */
    static final float PREFILL = 16;

    /* Per vertex: how its corner stands from the normal, a row a coordinate (the primary attribute is the
     * x row), its texture coordinates and the game's normal. */
    static final Attribute a_ry = new Attribute(VEC3, "psry");
    static final Attribute a_rz = new Attribute(VEC3, "psrz");
    /* Per flake: where it is born and when; the height it is gone under, its speed, its spin and its drift's
     * heading; its first normal and a seed; its spin's axis and its drift's size. */
    static final Attribute a_fs = new Attribute(VEC4, "psfs");
    static final Attribute a_fk = new Attribute(VEC4, "psfk");
    static final Attribute a_fn = new Attribute(VEC4, "psfn");
    static final Attribute a_fa = new Attribute(VEC4, "psfa");

    static final int VSTRIDE = 44, STRIDE = 64, BIRTH = 12;
    static final VertexArray.Layout fmt = new VertexArray.Layout(
	in(Homo3D.vertex, 3, NumberFormat.FLOAT32, 0, 0, VSTRIDE, false),
	in(a_ry, 3, NumberFormat.FLOAT32, 0, 12, VSTRIDE, false),
	in(a_rz, 3, NumberFormat.FLOAT32, 0, 24, VSTRIDE, false),
	in(Tex2D.texc, 2, NumberFormat.UNORM8, 0, 36, VSTRIDE, false),
	in(Homo3D.normal, 3, NumberFormat.SNORM8, 0, 40, VSTRIDE, false),
	in(a_fs, 4, NumberFormat.FLOAT32, 1, 0, STRIDE, true),
	in(a_fk, 4, NumberFormat.FLOAT32, 1, 16, STRIDE, true),
	in(a_fn, 4, NumberFormat.FLOAT32, 1, 32, STRIDE, true),
	in(a_fa, 4, NumberFormat.FLOAT32, 1, 48, STRIDE, true));

    /* The game's corners (Snow.fillvert), as rows over its normal n: corner 0 at (nz, -nz, ny - nx),
     * 1 at (nz, nz, ny - nx), 2 at (-nz, nz, nx - ny), 3 at (-nz, -ny, nx + ny), each times the size. */
    static final float[][] ROWS = {
	{0, 0, 1,   0, 0, -1,   -1, 1, 0},
	{0, 0, 1,   0, 0, 1,    -1, 1, 0},
	{0, 0, -1,  0, 0, 1,    1, -1, 0},
	{0, 0, -1,  0, -1, 0,   1, 1, 0},
    };
    static final int[][] TEXC = {{0, 0}, {0, 255}, {255, 255}, {255, 0}};

    static ByteBuffer vertices() {
	ByteBuffer buf = ByteBuffer.allocate(4 * VSTRIDE).order(ByteOrder.nativeOrder());
	for(int c = 0; c < 4; c++) {
	    for(float r : ROWS[c])
		buf.putFloat(r);
	    buf.put((byte)TEXC[c][0]).put((byte)TEXC[c][1]).put((byte)0).put((byte)0);
	    /* The normal the game gives every flake: the bytes 0, 0, 1. */
	    buf.put((byte)0).put((byte)0).put((byte)1).put((byte)0);
	}
	buf.flip();
	return(buf);
    }

    static Expression age() {return(sub(u_time.ref(), pick(a_fs.ref(), "w")));}
    static Expression seed(double k) {return(fract(mul(pick(a_fn.ref(), "w"), l(k))));}
    static Expression phase(double k) {return(mul(seed(k), l(Math.PI * 2)));}

    /* How far a flake of speed k has fallen at age t. */
    static Expression fall(Expression t, Expression k) {
	Expression u = max(sub(t, l(T1)), l(0.0));
	Expression early = mul(l(A), t, t);
	Expression late = add(l(D1), mul(l(V1), u), neg(mul(l(C), u, u)));
	return(mul(k, mix(early, late, step(l(T1), t))));
    }

    /* The height of its middle, without its bob: what its end is tested on. */
    static Expression zc() {return(sub(pick(a_fs.ref(), "z"), fall(max(age(), l(0.0)), pick(a_fk.ref(), "y"))));}

    static Expression wobble(double f, double p) {
	return(sin(add(mul(add(l(1.5), mul(seed(f), l(2.0))), age()), phase(p))));
    }

    static Expression pos() {
	Expression t = max(age(), l(0.0));
	Expression ramp = clamp(mul(t, l(0.25)), l(0.0), l(1.0));
	Expression h = mul(pick(a_fa.ref(), "w"), pow(div(t, l(DT)), l(DP)));
	Expression psi = add(pick(a_fk.ref(), "w"), mul(sub(seed(41.3), l(0.5)), l(0.6), t));
	Expression wx = mul(l(0.35), add(wobble(3.17, 5.11), wobble(7.31, 11.3)));
	Expression wy = mul(l(0.35), add(wobble(13.7, 17.9), wobble(19.3, 23.9)));
	Expression wz = mul(l(2.0), sin(add(mul(add(l(2.5), mul(seed(29.1), l(1.5))), t), phase(31.7))));
	Expression mid = vec3(add(pick(a_fs.ref(), "x"), mul(h, Function.Builtin.cos.call(psi)), mul(ramp, wx)),
			      add(pick(a_fs.ref(), "y"), mul(h, sin(psi)), mul(ramp, wy)),
			      add(zc(), mul(ramp, wz)));
	/* The spin: about its axis, coming up to its speed w over its first seconds, a little uneven. */
	Expression th = add(mul(pick(a_fk.ref(), "z"), sub(t, mul(l(1.5), sub(l(1.0), exp(div(neg(t), l(1.5))))))),
			    mul(l(0.8), sin(add(mul(add(l(0.5), seed(43.7)), t), phase(47.3)))));
	Expression ax = pick(a_fa.ref(), "xyz"), n0 = pick(a_fn.ref(), "xyz");
	Expression ct = Function.Builtin.cos.call(th), sn = sin(th);
	Expression n = add(mul(n0, ct), mul(cross(ax, n0), sn), mul(ax, dot(ax, n0), sub(l(1.0), ct)));
	Expression off = mul(vec3(dot(Homo3D.vertex.ref(), n), dot(a_ry.ref(), n), dot(a_rz.ref(), n)), l(FSZ));
	return(add(mid, off));
    }

    static Expression alive() {
	Expression t = age();
	return(mul(step(l(0.0), t), sub(l(1.0), step(l(MAXLIFE), t)), step(pick(a_fk.ref(), "x"), zc())));
    }

    static final ShaderMacro shader = prog -> {
	Homo3D homo = Homo3D.get(prog);
	homo.objv.mod(in -> vec4(pos(), l(1.0)), 0);
	prog.vctx.posv.mod(in -> mix(vec4(l(2.0), l(2.0), l(2.0), l(1.0)), in, alive()), 100);
    };

    ShaderMacro shader() {return(shader);}

    final Material mat;

    Pipe.Op states() {
	return(Pipe.Op.compose(mat, clock));
    }

    private final Random rnd = new Random();
    private float acc = 0;

    AmbSnow(Material mat) {
	super(STRIDE, BIRTH, fmt, Model.Mode.TRIANGLES, vertices(), 6, new short[] {0, 1, 3, 1, 2, 3});
	this.mat = mat;
    }

    /* The game's own flake material, or a Loading until it has arrived. */
    static Material material() {
	Resource res = Resource.remote().load("gfx/fx/snow-1").get();
	Material.Res mr = res.layer(Material.Res.class);
	if(mr == null)
	    throw(new RuntimeException("gfx/fx/snow-1 has no material"));
	return(mr.get());
    }

    /* The same fall, drift and seed as the program's, for where a flake will land. */
    static float fall(float t, float k) {
	float u = Math.max(t - T1, 0);
	return(k * ((t < T1) ? (A * t * t) : (D1 + (V1 * u) - (C * u * u))));
    }

    static float tland(float d, float k) {
	float dd = d / k;
	if(dd <= 0)
	    return(0);
	if(dd <= D1)
	    return((float)Math.sqrt(dd / A));
	float disc = (V1 * V1) - (4 * C * (dd - D1));
	float u = (disc <= 0) ? (V1 / (2 * C)) : ((V1 - (float)Math.sqrt(disc)) / (2 * C));
	return(T1 + u);
    }

    static float frac(float x) {return(x - (float)Math.floor(x));}

    /* One tick's flakes: a rate a second, born through the tick; or, filling, a whole snowfall's worth at
     * every age, those already down left out. */
    void spawn(java.util.function.Function<Coord2d, Coord3f> ground, Coord2d cc, double dt, float rate, boolean fill) {
	double now = now();
	int n;
	if(fill) {
	    n = (int)Math.floor(rate * PREFILL);
	} else {
	    acc += rate * (float)dt;
	    n = (int)Math.floor(acc);
	    acc -= n;
	}
	synchronized(this) {
	    for(int i = 0; i < n; i++) {
		Coord3f g0;
		try {
		    g0 = ground.apply(Coord2d.of(((rnd.nextFloat() * 2) - 1) * SZ, ((rnd.nextFloat() * 2) - 1) * SZ).add(cc));
		} catch(Loading l) {
		    continue;
		}
		float sx = g0.x, sy = -g0.y, sz = g0.z + UP;
		float k = 0.9f + (rnd.nextFloat() * 0.23f);
		float w = 0.8f + (rnd.nextFloat() * 7.2f);
		float psi0 = rnd.nextFloat() * (float)(Math.PI * 2);
		float seed = rnd.nextFloat();
		float psid = (frac(seed * 41.3f) - 0.5f) * 0.6f;
		float m = 7 * (float)Math.sqrt(-2 * Math.log(1 - (rnd.nextFloat() * 0.999)));
		/* Where it will land, twice over: the time to fall to the ground under its birth, then to the
		 * ground under where that puts it. */
		float gz = g0.z, t = 0;
		for(int it = 0; it < 2; it++) {
		    t = Math.min(tland(sz - (gz - 1), k), MAXLIFE);
		    float h = m * (float)Math.pow(t / DT, DP), psi = psi0 + (psid * t);
		    try {
			gz = ground.apply(Coord2d.of(sx + (h * (float)Math.cos(psi)), -(sy + (h * (float)Math.sin(psi))))).z;
		    } catch(Loading l) {
			break;
		    }
		}
		float killz = gz - 1;
		float life = Math.min(tland(sz - killz, k), MAXLIFE);
		double birth;
		if(fill) {
		    float a = rnd.nextFloat() * PREFILL;
		    if(a >= life)
			continue;
		    birth = now - a;
		} else {
		    birth = now - (rnd.nextFloat() * dt);
		}
		/* The game's first normal: each component a half away from nought, then made unit. */
		float nx = rnd.nextFloat(), ny = rnd.nextFloat(), nz = rnd.nextFloat();
		if(nx < 0.5f) nx -= 1.0f;
		if(ny < 0.5f) ny -= 1.0f;
		if(nz < 0.5f) nz -= 1.0f;
		float nf = 1.0f / (float)Math.sqrt((nx * nx) + (ny * ny) + (nz * nz));
		float az = (rnd.nextFloat() * 2) - 1, aa = rnd.nextFloat() * (float)(Math.PI * 2);
		float ar = (float)Math.sqrt(1 - (az * az));
		int s = alloc(now, birth + life);
		putf(s, 0, sx); putf(s, 4, sy); putf(s, 8, sz);
		birth(s, birth);
		putf(s, 16, killz); putf(s, 20, k); putf(s, 24, w); putf(s, 28, psi0);
		putf(s, 32, nx * nf); putf(s, 36, ny * nf); putf(s, 40, nz * nf); putf(s, 44, seed);
		putf(s, 48, ar * (float)Math.cos(aa)); putf(s, 52, ar * (float)Math.sin(aa)); putf(s, 56, az); putf(s, 60, m);
	    }
	}
    }
}
