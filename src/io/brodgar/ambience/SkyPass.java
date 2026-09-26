package io.brodgar.ambience;

import java.io.StringWriter;
import java.util.Map;
import java.util.WeakHashMap;
import haven.*;
import haven.render.*;
import haven.render.gl.BGL;
import haven.render.gl.GL;
import haven.render.gl.GLEnvironment;
import haven.render.gl.GLRender;
import haven.render.gl.UniformApplier;
import haven.render.sl.*;
import static haven.render.sl.Cons.*;
import static haven.render.sl.Type.*;

/* The sky, its clouds and the fog, in one pass over the whole screen.
 *
 * It runs after the solid scene and the water (order 6500) and reads the depth the scene wrote, the
 * way Outlines does: where nothing was drawn (depth 1) it paints the sky -- a gradient off the
 * server's light, the sun's disc and glow, stars at night -- and everywhere else it lays the horizon's
 * colour over the scene by distance, so the drawn ground dissolves into the sky instead of ending at
 * the far plane against black. Unlit and outside the shadow list.
 *
 * The clouds are a handful of separate ones (Ambience.View keeps them: where they are, how big, how
 * dark, how formed), each a cluster of PUFFS soft balls cut flat at a common base. Every pixel's ray is
 * tested against each cloud's bounding sphere and, where it hits, against its balls -- a few dozen
 * sphere tests a pixel, no marching -- and it stops at the scene: a hill in front hides a cloud, and
 * from above the clouds hide the ground.
 *
 * Each of the three is switched on its own (Ambience.skyon, .cloudson, .fogon): with the sky off the
 * open sky is left to the game's own background and only clouds are laid over it; with the fog off its
 * reach is nothing; with the clouds off there are none to draw.
 *
 * The clouds are the dear part, so they are worked out in a pass of their own at half the screen's
 * resolution each way (a quarter of the rays), into a texture of their own, and the sky pass reads them
 * back filtered: soft shapes lose nothing to it. The sky, the stars, the moon and the fog stay at full
 * resolution. */
public class SkyPass implements RenderTree.Node {
    /* The clouds' pass first, then the sky's, which reads what it wrote. */
    static final Rendered.Order corder = new Rendered.Order.Default(6500);
    static final Rendered.Order order = new Rendered.Order.Default(6501);
    /* How many screen pixels each way a cloud texel covers. */
    static final int CLOUDRES = 2;
    /* NEAR clouds form round the player, FAR ones out on the horizon; the arrays hold the near first.
     * These three size the clouds' program: MAXCL * (PUFFS + 3) vec4 uniforms, 735 of them, and with the
     * rest about 750 of the 1024 vec4 registers a GTX 1660 gives a fragment shader. It refuses the link
     * past 1018 (C6020): PUFFS 18 at 49 clouds, or 68 clouds at 12, is refused there -- and GL's own
     * minimum is 256. Probe says so at run time; this says so before a number is raised. */
    public static final int NEAR = 29, FAR = 20, MAXCL = NEAR + FAR, PUFFS = 12;
    /* The moon's angular radius: some three times the real one's, or it is a speck on a game screen. */
    static final double MOONR = 0.04;
    /* The edge noise's scale: the game's cloud texture, a texel to every four units or so. */
    static final double NOISE = 0.0035;

    static {
	/* An array of vec4 as one flat float[], uploaded in one call. The engine maps only mat4 arrays. */
	UniformApplier.TypeMapping.register(new Array(VEC4), float[].class,
					    (gl, var, type, a) -> gl.glUniform4fv(var, Math.min(a.length / 4, ((Array)type).sz), a));
    }

    public SkyPass() {}

    /* One of the two passes: its program, the scene's depth, and -- for the sky's -- the clouds' texture. */
    static class Draw extends State {
	static final Slot<Draw> slot = new Slot<>(Slot.Type.DRAW, Draw.class);
	final ShaderMacro code;
	final Texture2D.Sampler2D depth, clouds;

	Draw(ShaderMacro code, Texture2D.Sampler2D depth, Texture2D.Sampler2D clouds) {
	    this.code = code; this.depth = depth; this.clouds = clouds;
	}

	public ShaderMacro shader() {return(code);}
	public void apply(Pipe p) {p.put(slot, this);}
    }

    static final Uniform sdep = new Uniform(SAMPLER2D, p -> p.get(Draw.slot).depth, Draw.slot);
    static final Uniform scl = new Uniform(SAMPLER2D, p -> p.get(Draw.slot).clouds, Draw.slot);
    static final Uniform sclouds = new Uniform(SAMPLER2D, p -> Ambience.cloudtex().img, Draw.slot);
    static final Uniform ivp = new Uniform(MAT4, p -> Homo3D.prjxf(p).mul(Homo3D.camxf(p)).invert(), Homo3D.prj, Homo3D.cam);
    static final Uniform eye = new Uniform(VEC3, p -> Ambience.eyeof(Homo3D.camxf(p)), Homo3D.cam);
    static final Uniform fog = new Uniform(VEC4, p -> Ambience.fogparams(Homo3D.prjxf(p), Homo3D.camxf(p)), Homo3D.prj, Homo3D.cam, FrameInfo.slot);
    static final Uniform fognear = new Uniform(FLOAT, p -> Ambience.fognear(Homo3D.camxf(p)), Homo3D.cam, FrameInfo.slot);
    /* What the tick computed; FrameInfo changes every frame, so these are re-read every frame. */
    static final Uniform zen = new Uniform(VEC3, p -> Ambience.frame().zen, FrameInfo.slot);
    static final Uniform hor = new Uniform(VEC3, p -> Ambience.frame().hor, FrameInfo.slot);
    static final Uniform sun = new Uniform(VEC3, p -> Ambience.frame().sun, FrameInfo.slot);
    static final Uniform sdir = new Uniform(VEC3, p -> Ambience.frame().sdir, FrameInfo.slot);
    static final Uniform stars = new Uniform(FLOAT, p -> Ambience.frame().stars, FrameInfo.slot);
    static final Uniform disc = new Uniform(FLOAT, p -> Ambience.frame().disc, FrameInfo.slot);
    static final Uniform ccol = new Uniform(VEC3, p -> Ambience.frame().ccol, FrameInfo.slot);
    static final Uniform mdir = new Uniform(VEC3, p -> Ambience.frame().mdir, FrameInfo.slot);
    static final Uniform mcol = new Uniform(VEC3, p -> Ambience.frame().mcol, FrameInfo.slot);
    static final Uniform mphase = new Uniform(FLOAT, p -> Ambience.frame().mphase, FrameInfo.slot);
    static final Uniform mvis = new Uniform(FLOAT, p -> Ambience.frame().mvis, FrameInfo.slot);
    /* Whether the open sky is ours to paint: 0 leaves it to the game's own background, clouds over it. */
    static final Uniform skyon = new Uniform(FLOAT, p -> Ambience.skyon ? 1f : 0f, FrameInfo.slot);
    /* Where the player is: seen from above, the clouds clear a circle round it. */
    static final Uniform focus = new Uniform(VEC3, p -> Ambience.frame().focus, FrameInfo.slot);
    /* The clouds this camera sees (Ambience.Seen): how many; each one's bounding sphere (centre, radius);
     * each one's opacity, base height, darkness and noise seed; each one's edge -- how soft, how ragged
     * -- its footprint's radius on the ground and the scale of its noise; and each one's PUFFS balls
     * (centre, radius), cloud k's at k * PUFFS. And the lowest of their bases. */
    static final Uniform ncl = new Uniform(INT, p -> seen(p).ncl, Homo3D.prj, Homo3D.cam, FrameInfo.slot);
    static final Uniform cbot = new Uniform(FLOAT, p -> seen(p).cbot, Homo3D.prj, Homo3D.cam, FrameInfo.slot);
    static final Uniform cl = new Uniform(new Array(VEC4, MAXCL), p -> seen(p).cl, Homo3D.prj, Homo3D.cam, FrameInfo.slot);
    static final Uniform ci = new Uniform(new Array(VEC4, MAXCL), p -> seen(p).ci, Homo3D.prj, Homo3D.cam, FrameInfo.slot);
    static final Uniform cx = new Uniform(new Array(VEC4, MAXCL), p -> seen(p).cx, Homo3D.prj, Homo3D.cam, FrameInfo.slot);
    static final Uniform pf = new Uniform(new Array(VEC4, MAXCL * PUFFS), p -> seen(p).pf, Homo3D.prj, Homo3D.cam, FrameInfo.slot);

    static Ambience.Seen seen(Pipe p) {return(Ambience.seen(Homo3D.prjxf(p), Homo3D.camxf(p)));}
    /* Texels of the noise per pixel, per unit of distance: what picks the mip level a sample reads. */
    static final Uniform lodk = new Uniform(FLOAT, p -> {
	    float texels = (float)(1024 * NOISE);
	    float pixel = 2f / (Homo3D.prjxf(p).m[5] * p.get(FrameConfig.slot).sz.y);
	    return(texels * pixel);
	}, Homo3D.prj, FrameConfig.slot);

    /* A sample with its mip level named: inside the clouds' loops and branches the implicit derivatives a
     * plain texture() takes its level from are undefined. GLSL 1.40 has it; the DSL has no name for it. */
    static final Function textureLod = new Function.Builtin(VEC4, new Symbol.Fix("textureLod"), 3);

    /* The sky's gradient and the sun's glow, without the things only an open sky shows: the fog's
     * colour is this, looked at level. */
    static final Function skybase = new Function.Def(VEC3) {{
	Expression v = param(PDir.IN, VEC3).ref();
	Expression mu = code.local(FLOAT, max(dot(v, sdir.ref()), l(0.0))).ref();
	Expression h = code.local(FLOAT, clamp(pick(v, "z"), l(0.0), l(1.0))).ref();
	LValue c = code.local(VEC3, mix(hor.ref(), zen.ref(), pow(h, l(0.45)))).ref();
	code.add(aadd(c, mul(sun.ref(), add(mul(pow(mu, l(6.0)), l(0.30)), mul(pow(mu, l(48.0)), l(0.35))))));
	code.add(new Return(c));
    }};

    /* The open sky: the gradient, the sun's disc and, behind everything else, the stars -- a hash of a
     * fine grid of directions. */
    static final Function skyopen = new Function.Def(VEC3) {{
	Expression v = param(PDir.IN, VEC3).ref();
	LValue c = code.local(VEC3, skybase.call(v)).ref();
	Expression mu = code.local(FLOAT, dot(v, sdir.ref())).ref();
	code.add(aadd(c, mul(sun.ref(), mul(smoothstep(l(0.9990), l(0.9996), mu), disc.ref()))));
	Expression cell = floor(mul(v, l(360.0)));
	Expression hash = fract(mul(sin(dot(cell, vec3(12.9898, 78.233, 37.719))), l(43758.5453)));
	code.add(aadd(c, vec3(mul(step(l(0.997), hash), stars.ref(), clamp(mul(pick(v, "z"), l(6.0)), l(0.0), l(1.0)), l(1.1)))));
	/* The moon: a disc MOONR across, shaded as a ball lit from the side its phase puts the sun on -- the
	 * right at first quarter, the front at full -- mottled by the cloud noise, with a little earthshine
	 * on its dark part (which hides the stars behind it too) and a halo as bright as it is full. */
	Expression m = mdir.ref();
	Expression mu2 = code.local(FLOAT, dot(v, m)).ref();
	Expression rt = code.local(VEC3, normalize(cross(m, mix(vec3(l(0.0), l(0.0), l(1.0)), vec3(l(0.0), l(1.0), l(0.0)), step(l(0.99), abs(pick(m, "z"))))))).ref();
	Expression upv = code.local(VEC3, cross(rt, m)).ref();
	Expression mx = code.local(FLOAT, div(dot(v, rt), l(MOONR))).ref();
	Expression my = code.local(FLOAT, div(dot(v, upv), l(MOONR))).ref();
	Expression r2 = code.local(FLOAT, add(mul(mx, mx), mul(my, my))).ref();
	Expression inside = code.local(FLOAT, mul(sub(l(1.0), smoothstep(l(0.9), l(1.0), r2)), step(l(0.0), mu2), mvis.ref())).ref();
	Expression ph = code.local(FLOAT, mul(mphase.ref(), l(Math.PI * 2))).ref();
	Expression lit = smoothstep(l(-0.06), l(0.08), add(mul(mx, sin(ph)), mul(sqrt(max(sub(l(1.0), r2), l(0.0))), neg(Function.Builtin.cos.call(ph)))));
	/* Its level named, as the clouds' are: the open sky is drawn in a branch of its own (main). */
	Expression mlod = max(log2(mul(lodk.ref(), l(0.18 / (MOONR * NOISE)))), l(0.0));
	Expression spots = add(l(0.72), mul(pick(textureLod.call(sclouds.ref(), add(mul(vec2(mx, my), l(0.18)), vec2(l(0.3), l(0.6))), mlod), "r"), l(0.35)));
	Expression disc = mul(mcol.ref(), add(mul(lit, spots, l(1.5)), l(0.04)));
	code.add(ass(c, mix(c, disc, inside)));
	Expression full = mul(sub(l(1.0), Function.Builtin.cos.call(ph)), l(0.5));
	code.add(aadd(c, mul(mcol.ref(), mul(pow(max(mu2, l(0.0)), l(900.0)), l(0.18), full, mvis.ref()))));
	code.add(new Return(c));
    }};

    /* THE CLOUDS along one ray, up to tmax (where the scene is). Answers their light, premultiplied, and
     * how much of what is behind them they cover.
     *
     * Each ball is an egg, as wide as its radius and as high as its squash makes it (the fraction of the
     * radius it is handed in: the cloud's lowest balls lie flattest). The ray's entry into it is cut at
     * the cloud's base: a ray from below that enters an egg under the base enters instead where it
     * crosses the base, facing down -- which is what makes the flat grey bellies. Its cover is softest at
     * the rim, ragged by the game's cloud noise, and thins with distance; its light is the cloud colour,
     * brighter up the cloud, plus the sun on the side that faces it and through the thin edges toward it. The balls of all clouds are summed without sorting --
     * a weighted blend, which soft white shapes do not give away. */
    static final Function clouds = new Function.Def(VEC4) {{
	Expression e = param(PDir.IN, VEC3).ref();
	Expression v = param(PDir.IN, VEC3).ref();
	Expression tmax = param(PDir.IN, FLOAT).ref();
	LValue acc = code.local(VEC3, vec3(l(0.0), l(0.0), l(0.0))).ref();
	LValue wsum = code.local(FLOAT, l(0.0)).ref();
	LValue tr = code.local(FLOAT, l(1.0)).ref();
	Expression mu = code.local(FLOAT, max(dot(v, sdir.ref()), l(0.0))).ref();
	Expression vz = code.local(FLOAT, pick(v, "z")).ref();
	/* Nothing of a cloud shows below its base, so a ray that does not rise to the lowest base before
	 * the scene meets none: from the usual camera, under the clouds and looking down, that is all the
	 * ground, which skips the clouds altogether. */
	Expression ez = pick(e, "z");
	Block any = new Block();
	code.add(new If(or(ge(ez, cbot.ref()), and(gt(vz, l(0.0)), lt(sub(cbot.ref(), ez), mul(tmax, vz)))), any));
	LValue k = any.local(INT, null).ref();
	Block cb = new Block();
	/* Nearest first (Ambience.View.write sorts them), and no further once what is in front is opaque:
	 * under a full sky a ray stops at the first cloud or two. */
	any.add(new For(ass(k, l(0)), and(lt(k, ncl.ref()), gt(tr, l(0.02))), linc(k), cb));

	/* The cloud's bounding sphere first: most rays miss most clouds. */
	Expression b = cb.local(VEC4, idx(cl.ref(), k)).ref();
	Expression oc = cb.local(VEC3, sub(e, pick(b, "xyz"))).ref();
	Expression bq = cb.local(FLOAT, dot(oc, v)).ref();
	Expression dsc = cb.local(FLOAT, sub(mul(bq, bq), sub(dot(oc, oc), mul(pick(b, "w"), pick(b, "w"))))).ref();
	Expression sq = cb.local(FLOAT, sqrt(max(dsc, l(0.0)))).ref();
	Block hit = new Block();
	cb.add(new If(and(gt(dsc, l(0.0)), and(lt(sub(neg(bq), sq), tmax), gt(add(neg(bq), sq), l(0.0)))), hit));

	Expression info = hit.local(VEC4, idx(ci.ref(), k)).ref();
	Expression ext = hit.local(VEC4, idx(cx.ref(), k)).ref();
	Expression base = hit.local(FLOAT, pick(info, "y")).ref();
	/* The cloud's height: its bounding centre stands at half of it. */
	Expression tall = hit.local(FLOAT, max(mul(sub(pick(b, "z"), base), l(2.0)), l(1.0))).ref();
	LValue j = hit.local(INT, null).ref();
	Block pb = new Block();
	hit.add(new For(ass(j, l(0)), and(lt(j, l(PUFFS)), gt(tr, l(0.02))), linc(j), pb));

	Expression pp = pb.local(VEC4, idx(pf.ref(), add(mul(k, l(PUFFS)), j))).ref();
	Expression r = pb.local(FLOAT, floor(pick(pp, "w"))).ref();
	/* The egg is a ball in a space stretched upward by its squash: the ray is met there, and t stays
	 * the distance along the ray in the world. */
	Expression sc = pb.local(VEC3, vec3(l(1.0), l(1.0), div(l(1.0), sub(pick(pp, "w"), r)))).ref();
	Expression o2 = pb.local(VEC3, mul(sub(e, pick(pp, "xyz")), sc)).ref();
	Expression v2 = pb.local(VEC3, mul(v, sc)).ref();
	Expression qa = pb.local(FLOAT, dot(v2, v2)).ref();
	Expression b2 = pb.local(FLOAT, dot(o2, v2)).ref();
	Expression d2 = pb.local(FLOAT, sub(mul(b2, b2), mul(qa, sub(dot(o2, o2), mul(r, r))))).ref();
	Block ph = new Block();
	pb.add(new If(gt(d2, l(0.0)), ph));

	Expression s = ph.local(FLOAT, sqrt(d2)).ref();
	Expression t1 = ph.local(FLOAT, div(add(neg(b2), s), qa)).ref();
	Expression te = ph.local(FLOAT, max(div(sub(neg(b2), s), qa), l(0.0))).ref();
	/* Cut at the base: an entry below it moves up to where the ray crosses it, if the ray rises and
	 * crosses it inside the egg; otherwise the egg is not seen at all. */
	Expression below = ph.local(FLOAT, sub(l(1.0), step(base, pick(add(e, mul(v, te)), "z")))).ref();
	Expression tp = ph.local(FLOAT, div(sub(base, pick(e, "z")), max(vz, l(0.0001)))).ref();
	Expression ok = ph.local(FLOAT, add(sub(l(1.0), below), mul(below, step(l(0.0001), vz), step(tp, t1), step(te, tp)))).ref();
	Expression t0 = ph.local(FLOAT, mix(te, tp, below)).ref();
	Block pv = new Block();
	ph.add(new If(and(gt(ok, l(0.5)), and(lt(t0, tmax), gt(t1, l(0.0)))), pv));

	Expression p0 = pv.local(VEC3, add(e, mul(v, t0))).ref();
	/* The egg's own normal, bent halfway toward the whole cloud's, so the light rounds the cloud and
	 * does not pick out every ball as a shape of its own; on the cut, straight down. */
	Expression nb = normalize(add(normalize(mul(sub(p0, pick(pp, "xyz")), mul(sc, sc))), normalize(sub(p0, pick(b, "xyz")))));
	Expression n = pv.local(VEC3, mix(nb, vec3(l(0.0), l(0.0), l(-1.0)), below)).ref();
	/* 0 at the rim, 1 straight through the middle: the half-chord against the radius, in the egg's space. */
	Expression th = pv.local(FLOAT, div(s, mul(sqrt(qa), r))).ref();
	Expression lod = pv.local(FLOAT, max(add(log2(max(mul(t0, lodk.ref()), l(0.0001))), log2(pick(ext, "w"))), l(0.0))).ref();
	Expression nz = pv.local(FLOAT, pick(textureLod.call(sclouds.ref(), add(mul(pick(p0, "xy"), l(NOISE), pick(ext, "w")), vec2(pick(info, "w"), mul(pick(info, "w"), l(1.7)))), lod), "r")).ref();
	/* Each cloud's own edge: how far in from the rim it turns solid, and how ragged the noise makes it. */
	Expression soft = smoothstep(l(0.0), pick(ext, "x"), add(th, mul(sub(nz, l(0.5)), pick(ext, "y"))));
	/* Looked down on, a cloud never hides the player: within a few hundred units of it, horizontally,
	 * a cloud the camera is above thins away, so a low cloud cannot come between the play and a camera
	 * pulled back over it. From below, nothing is cleared. */
	Expression clear = mix(l(1.0), smoothstep(l(250.0), l(800.0), length(sub(pick(p0, "xy"), pick(focus.ref(), "xy")))),
			       step(pick(p0, "z"), pick(e, "z")));
	Expression a = pv.local(FLOAT, mul(pick(info, "x"), soft, clear, exp(neg(div(t0, l(40000.0)))))).ref();
	Expression up = clamp(div(sub(pick(p0, "z"), base), tall), l(0.0), l(1.0));
	Expression lam = max(dot(n, sdir.ref()), l(0.0));
	Expression dark = pick(info, "z");
	Expression sh = add(mul(ccol.ref(), add(l(0.5), mul(up, l(0.5))), sub(l(1.0), mul(dark, l(0.5)))),
			    mul(sun.ref(), add(mul(lam, l(0.55)), mul(pow(mu, l(6.0)), sub(l(1.0), th), l(0.6))), sub(l(1.0), mul(dark, l(0.7)))));
	/* Far clouds take on the horizon's haze, and the farthest, on the horizon itself, keep their shape. */
	Expression col = mix(sh, hor.ref(), mul(sub(l(1.0), exp(neg(div(t0, l(22000.0))))), l(0.85)));
	pv.add(aadd(acc, mul(col, a)));
	pv.add(aadd(wsum, a));
	pv.add(amul(tr, sub(l(1.0), a)));

	Expression alpha = code.local(FLOAT, sub(l(1.0), tr)).ref();
	code.add(new Return(vec4(mul(div(acc, max(wsum, l(0.0001))), alpha), alpha)));
    }};

    /* This pixel's ray out of the scene's depth: where it goes, how far it gets before the scene, and
     * whether it reaches no scene at all. */
    static Expression[] ray(Block code) {
	Expression tc = Tex2D.rtexcoord.ref();
	Expression d = code.local(FLOAT, pick(texture2D(sdep.ref(), tc), "r")).ref();
	Expression ndc = vec4(sub(mul(tc, l(2.0)), l(1.0)), sub(mul(d, l(2.0)), l(1.0)), l(1.0));
	Expression hp = code.local(VEC4, mul(ivp.ref(), ndc)).ref();
	Expression rel = code.local(VEC3, sub(div(pick(hp, "xyz"), pick(hp, "w")), eye.ref())).ref();
	Expression dist = code.local(FLOAT, length(rel)).ref();
	Expression v = code.local(VEC3, div(rel, max(dist, l(0.0001)))).ref();
	Expression issky = code.local(FLOAT, step(l(0.9999999), d)).ref();
	return(new Expression[] {v, dist, issky});
    }

    /* The clouds' pass: the clouds along this texel's ray, premultiplied, into the clouds' texture. */
    static final Function cmain = new Function.Def(VEC4) {{
	Expression[] r = ray(code);
	code.add(new Return(clouds.call(eye.ref(), r[0], mix(r[1], l(1.0e6), r[2]))));
    }};

    /* The sky's pass: what lies over this pixel. What reaches the screen is one blend: the clouds, plus
     * what shows through them of the sky, or of the fog laid over the scene. */
    static final Function main = new Function.Def(VEC4) {{
	Expression[] r = ray(code);
	Expression v = r[0], dist = r[1], issky = r[2];
	Expression fp = fog.ref();
	Expression f1 = mul(smoothstep(pick(fp, "x"), pick(fp, "y"), dist), pick(fp, "z"));
	Expression f2 = sub(l(1.0), exp(neg(mul(max(sub(dist, fognear.ref()), l(0.0)), pick(fp, "w")))));
	Expression f = code.local(FLOAT, clamp(max(f1, f2), l(0.0), l(1.0))).ref();
	/* The open sky where nothing was drawn, the fog's colour everywhere else: each only where it shows,
	 * and the sky -- the stars, the moon -- is the dearer of the two. */
	LValue fc = code.local(VEC3, null).ref();
	code.add(new If(gt(issky, l(0.5)), stmt(ass(fc, skyopen.call(v))),
			stmt(ass(fc, skybase.call(normalize(vec3(pick(v, "xy"), l(0.03))))))));
	Expression ff = code.local(FLOAT, mix(f, skyon.ref(), issky)).ref();
	Expression m = code.local(VEC4, texture2D(scl.ref(), Tex2D.rtexcoord.ref())).ref();
	Expression tr = code.local(FLOAT, sub(l(1.0), pick(m, "a"))).ref();
	Expression a = code.local(FLOAT, sub(l(1.0), mul(tr, sub(l(1.0), ff)))).ref();
	code.add(new Return(vec4(div(add(pick(m, "rgb"), mul(fc, tr, ff)), max(a, l(0.0001))), a)));
    }};

    static final ShaderMacro shader = prog -> {
	FragColor.fragcol(prog.fctx).mod(in -> main.call(), 0);
    };

    static final ShaderMacro cshader = prog -> {
	FragColor.fragcol(prog.fctx).mod(in -> cmain.call(), 0);
    };

    /* THE DRIVER'S WORD FIRST. A program the driver will not compile or link throws on the GL thread, inside
     * GLEnvironment.process, where nothing catches it: the frame's fences are dropped with it and the frame
     * loop waits on them for ever (docs/client/render-gl.md). The clouds' program is the one at risk -- its
     * arrays are some 750 of the 1024 vec4 registers a common driver gives a fragment shader, three times
     * GL's own minimum -- so both passes' programs are compiled and linked here first, in a request of its
     * own that reads the status instead of throwing, and the pass goes into the scene only on a yes. Once
     * per environment: one link each, on the GL thread. */
    static final class Probe implements BGL.Request {
	final String[][] srcs;
	volatile int state = 0;   // 0 asked, 1 linked, 2 refused, 3 lost with its environment
	volatile String why = null;

	Probe(String[][] srcs) {this.srcs = srcs;}

	public void run(GL gl) {
	    for(String[] s : srcs) {
		String err = link(gl, s[0], s[1]);
		if(err != null) {why = err; state = 2; return;}
	    }
	    state = 1;
	}

	public void abort() {state = 3;}
    }

    private static final Map<Environment, Probe> probes = new WeakHashMap<>();

    static Probe probe(Environment env) {
	synchronized(probes) {
	    Probe p = probes.get(env);
	    if((p == null) || (p.state == 3)) {
		p = new Probe(new String[][] {sources(cshader), sources(shader)});
		Environment b = env;
		while(b instanceof Environment.Proxy)
		    b = ((Environment.Proxy)b).back();
		if(b instanceof GLEnvironment) {
		    GLRender r = ((GLEnvironment)b).render();
		    r.submit(p);
		    b.submit(r);
		} else {
		    p.state = 1;   // no GL underneath: nothing that could refuse it
		}
		probes.put(env, p);
	    }
	    return(p);
	}
    }

    /* A pass's two sources as the engine writes them for the quads in added(): ScreenQuad(false)'s
     * transform, one colour output, and the pass. The fragment is written first -- it is what asks the
     * vertex stage for its varyings (GLProgram's order). */
    private static String[] sources(ShaderMacro pass) {
	ProgramContext prog = new ProgramContext();
	new Ortho2D(-1, 1, 1, -1).shader().modify(prog);
	new FragColor<>(FragColor.defcolor).shader().modify(prog);
	pass.modify(prog);
	StringWriter v = new StringWriter(), f = new StringWriter();
	prog.fctx.construct(f);
	prog.vctx.construct(v);
	return(new String[] {v.toString(), f.toString()});
    }

    /* Null when the driver compiles and links the pair, else what it said. Leaves no GL error behind. */
    private static String link(GL gl, String vsrc, String fsrc) {
	int[] st = {0};
	int prog = gl.glCreateProgram();
	int[] sh = {gl.glCreateShader(GL.GL_VERTEX_SHADER), gl.glCreateShader(GL.GL_FRAGMENT_SHADER)};
	String[] src = {vsrc, fsrc};
	try {
	    for(int i = 0; i < 2; i++) {
		gl.glShaderSource(sh[i], 1, new String[] {src[i]}, new int[] {src[i].length()});
		gl.glCompileShader(sh[i]);
		gl.glGetShaderiv(sh[i], GL.GL_COMPILE_STATUS, st);
		if(st[0] != 1)
		    return("compile: " + infolog(gl, sh[i], false));
		gl.glAttachShader(prog, sh[i]);
	    }
	    gl.glLinkProgram(prog);
	    gl.glGetProgramiv(prog, GL.GL_LINK_STATUS, st);
	    return((st[0] == 1) ? null : ("link: " + infolog(gl, prog, true)));
	} finally {
	    gl.glDeleteShader(sh[0]);
	    gl.glDeleteShader(sh[1]);
	    gl.glDeleteProgram(prog);
	}
    }

    private static String infolog(GL gl, int id, boolean prog) {
	int[] n = {0};
	if(prog) gl.glGetProgramiv(id, GL.GL_INFO_LOG_LENGTH, n); else gl.glGetShaderiv(id, GL.GL_INFO_LOG_LENGTH, n);
	if(n[0] <= 0)
	    return("(no log)");
	byte[] buf = new byte[n[0]];
	if(prog) gl.glGetProgramInfoLog(id, buf.length, n, buf); else gl.glGetShaderInfoLog(id, buf.length, n, buf);
	return(new String(buf, 0, n[0]).trim());
    }

    /* The clouds' texture, at the screen's size over CLOUDRES, made again when the screen's size moves.
     * One sampler over it, linear: the sky's pass reads it between texels. */
    private Texture2D ctex;
    private Texture2D.Sampler2D csamp;

    private synchronized Texture2D.Sampler2D ctarget(Coord scr) {
	Coord sz = Coord.of(Math.max((scr.x + CLOUDRES - 1) / CLOUDRES, 1), Math.max((scr.y + CLOUDRES - 1) / CLOUDRES, 1));
	if((ctex == null) || !ctex.sz().equals(sz)) {
	    if(ctex != null)
		ctex.dispose();
	    ctex = new Texture2D(sz, DataBuffer.Usage.STATIC, new VectorFormat(4, NumberFormat.FLOAT16), null);
	    csamp = new Texture2D.Sampler2D(ctex);
	    csamp.magfilter(Texture.Filter.LINEAR).minfilter(Texture.Filter.LINEAR);
	    csamp.swrap(Texture.Wrapping.CLAMP).twrap(Texture.Wrapping.CLAMP);
	}
	return(csamp);
    }

    /* The scene's depth, with the default sampler, exactly as Outlines makes its own: without sampler
     * objects (GL 3.0) a texture holds ONE set of sampler parameters, and a second, different one over the
     * same depth buffer throws in GLTexture.Tex2D.setsampler. The quads sample at texel centres anyway. */
    private static Texture2D.Sampler2D depthof(Pipe p) {
	DepthBuffer<?> dbuf = p.get(DepthBuffer.slot);
	return(new Texture2D.Sampler2D((Texture2D)((Texture.Image)dbuf.image).tex));
    }

    public void added(RenderTree.Slot slot) {
	/* The clouds, into their own texture: nothing of the scene's own outputs is written, nor blended. */
	slot.add(new Rendered.ScreenQuad(false), p -> {
		Texture2D.Sampler2D dep = depthof(p);
		Texture2D.Sampler2D tgt = ctarget(p.get(FrameConfig.slot).sz);
		Coord sz = tgt.tex.sz();
		p.prep(corder);
		p.put(DepthBuffer.slot, null);
		p.put(RenderedNormals.slot, null);
		p.put(AmbShadow.slot, null);
		p.put(io.brodgar.addon.OutlineMask.slot, null);
		p.prep(new FragColor<>(tgt.tex.image(0)));
		p.put(FragColor.blend, null);
		p.prep(new States.Viewport(Area.sized(Coord.z, sz)));
		p.prep(new FrameConfig(sz));
		p.prep(new Draw(cshader, dep, null));
	    });
	slot.add(new Rendered.ScreenQuad(false), p -> {
		Texture2D.Sampler2D dep = depthof(p);
		Texture2D.Sampler2D cl = ctarget(p.get(FrameConfig.slot).sz);
		p.prep(order);
		/* A pass that inherits the depth buffer it samples would also write it (Outlines). */
		p.put(DepthBuffer.slot, null);
		p.put(RenderedNormals.slot, null);
		/* The ground's cloud shadow is a Phong state; this pass is unlit, but take it out regardless. */
		p.put(AmbShadow.slot, null);
		p.prep(new Draw(shader, dep, cl));
	    });
    }

    public void removed(RenderTree.Slot slot) {
	synchronized(this) {
	    if(ctex != null)
		ctex.dispose();
	    ctex = null;
	    csamp = null;
	}
    }

}
