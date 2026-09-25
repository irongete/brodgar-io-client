package io.brodgar.ambience;

import haven.*;
import haven.render.*;
import haven.render.gl.UniformApplier;
import haven.render.sl.*;
import static haven.render.sl.Cons.*;
import static haven.render.sl.Type.*;

/* SPIKE: the sky, its clouds and the fog, in one pass over the whole screen.
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
 * from above the clouds hide the ground. */
public class SkyPass implements RenderTree.Node {
    static final Rendered.Order order = new Rendered.Order.Default(6500);
    /* NEAR clouds form round the player, FAR ones out on the horizon; the arrays hold the near first. */
    public static final int NEAR = 29, FAR = 15, MAXCL = NEAR + FAR, PUFFS = 12;
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

    static class Draw extends State {
	static final Slot<Draw> slot = new Slot<>(Slot.Type.DRAW, Draw.class);
	final Texture2D.Sampler2D depth;

	Draw(Texture2D.Sampler2D depth) {this.depth = depth;}

	public ShaderMacro shader() {return(shader);}
	public void apply(Pipe p) {p.put(slot, this);}
    }

    static final Uniform sdep = new Uniform(SAMPLER2D, p -> p.get(Draw.slot).depth, Draw.slot);
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
    static final Uniform night = new Uniform(FLOAT, p -> Ambience.frame().night, FrameInfo.slot);
    static final Uniform disc = new Uniform(FLOAT, p -> Ambience.frame().disc, FrameInfo.slot);
    static final Uniform ccol = new Uniform(VEC3, p -> Ambience.frame().ccol, FrameInfo.slot);
    static final Uniform mdir = new Uniform(VEC3, p -> Ambience.frame().mdir, FrameInfo.slot);
    static final Uniform mcol = new Uniform(VEC3, p -> Ambience.frame().mcol, FrameInfo.slot);
    static final Uniform mphase = new Uniform(FLOAT, p -> Ambience.frame().mphase, FrameInfo.slot);
    static final Uniform mvis = new Uniform(FLOAT, p -> Ambience.frame().mvis, FrameInfo.slot);
    /* Where the player is: seen from above, the clouds clear a circle round it. */
    static final Uniform focus = new Uniform(VEC3, p -> Ambience.frame().focus, FrameInfo.slot);
    /* The clouds: how many; each one's bounding sphere (centre, radius); each one's opacity, base height,
     * darkness and noise seed; each one's edge -- how soft, how ragged -- its footprint's radius on the
     * ground and the scale of its noise; and each one's PUFFS balls (centre, radius), cloud k's at
     * k * PUFFS. */
    static final Uniform ncl = new Uniform(INT, p -> Ambience.frame().ncl, FrameInfo.slot);
    /* How many of them are near: only those cast a shadow on the ground that is drawn. */
    static final Uniform nnear = new Uniform(INT, p -> Ambience.frame().nnear, FrameInfo.slot);
    static final Uniform cl = new Uniform(new Array(VEC4, MAXCL), p -> Ambience.frame().cl, FrameInfo.slot);
    static final Uniform ci = new Uniform(new Array(VEC4, MAXCL), p -> Ambience.frame().ci, FrameInfo.slot);
    static final Uniform cx = new Uniform(new Array(VEC4, MAXCL), p -> Ambience.frame().cx, FrameInfo.slot);
    static final Uniform pf = new Uniform(new Array(VEC4, MAXCL * PUFFS), p -> Ambience.frame().pf, FrameInfo.slot);
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
	code.add(aadd(c, vec3(mul(step(l(0.997), hash), night.ref(), clamp(mul(pick(v, "z"), l(6.0)), l(0.0), l(1.0)), l(1.1)))));
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
	Expression spots = add(l(0.72), mul(pick(texture2D(sclouds.ref(), add(mul(vec2(mx, my), l(0.18)), vec2(l(0.3), l(0.6)))), "r"), l(0.35)));
	Expression disc = mul(mcol.ref(), add(mul(lit, spots, l(1.5)), l(0.04)));
	code.add(ass(c, mix(c, disc, inside)));
	Expression full = mul(sub(l(1.0), Function.Builtin.cos.call(ph)), l(0.5));
	code.add(aadd(c, mul(mcol.ref(), mul(pow(max(mu2, l(0.0)), l(900.0)), l(0.18), full, mvis.ref()))));
	code.add(new Return(c));
    }};

    /* THE CLOUDS along one ray, up to tmax (where the scene is). Answers their light, premultiplied, and
     * how much of what is behind them they cover.
     *
     * Each ball the ray meets gives an entry point, cut at the cloud's base: a ray from below that enters
     * a ball under the base enters instead where it crosses the base, facing down -- which is what makes
     * the flat grey bellies. Its cover is softest at the rim, ragged by the game's cloud noise, and thins
     * with distance; its light is the cloud colour, brighter up the cloud, plus the sun on the side that
     * faces it and through the thin edges toward it. The balls of all clouds are summed without sorting --
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
	LValue k = code.local(INT, null).ref();
	Block cb = new Block();
	/* Nearest first (Ambience.View.write sorts them), and no further once what is in front is opaque:
	 * under a full sky a ray stops at the first cloud or two. */
	code.add(new For(ass(k, l(0)), and(lt(k, ncl.ref()), gt(tr, l(0.02))), linc(k), cb));

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
	Expression r = pb.local(FLOAT, pick(pp, "w")).ref();
	Expression o2 = pb.local(VEC3, sub(e, pick(pp, "xyz"))).ref();
	Expression b2 = pb.local(FLOAT, dot(o2, v)).ref();
	Expression d2 = pb.local(FLOAT, sub(mul(b2, b2), sub(dot(o2, o2), mul(r, r)))).ref();
	Block ph = new Block();
	pb.add(new If(gt(d2, l(0.0)), ph));

	Expression s = ph.local(FLOAT, sqrt(d2)).ref();
	Expression t1 = ph.local(FLOAT, add(neg(b2), s)).ref();
	Expression te = ph.local(FLOAT, max(sub(neg(b2), s), l(0.0))).ref();
	/* Cut at the base: an entry below it moves up to where the ray crosses it, if the ray rises and
	 * crosses it inside the ball; otherwise the ball is not seen at all. */
	Expression below = ph.local(FLOAT, sub(l(1.0), step(base, pick(add(e, mul(v, te)), "z")))).ref();
	Expression tp = ph.local(FLOAT, div(sub(base, pick(e, "z")), max(vz, l(0.0001)))).ref();
	Expression ok = ph.local(FLOAT, add(sub(l(1.0), below), mul(below, step(l(0.0001), vz), step(tp, t1), step(te, tp)))).ref();
	Expression t0 = ph.local(FLOAT, mix(te, tp, below)).ref();
	Block pv = new Block();
	ph.add(new If(and(gt(ok, l(0.5)), and(lt(t0, tmax), gt(t1, l(0.0)))), pv));

	Expression p0 = pv.local(VEC3, add(e, mul(v, t0))).ref();
	/* The ball's own normal, bent halfway toward the whole cloud's, so the light rounds the cloud and
	 * does not pick out every ball as a sphere of its own. */
	Expression nb = normalize(add(normalize(sub(p0, pick(pp, "xyz"))), normalize(sub(p0, pick(b, "xyz")))));
	Expression n = pv.local(VEC3, mix(nb, vec3(l(0.0), l(0.0), l(-1.0)), below)).ref();
	/* 0 at the rim, 1 straight through the middle. */
	Expression th = pv.local(FLOAT, div(s, r)).ref();
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

    /* Where this pixel's ray goes, how far it gets before the scene, and what lies over it. What reaches
     * the screen is one blend: the clouds, plus what shows through them of the sky, or of the fog laid
     * over the scene. */
    static final Function main = new Function.Def(VEC4) {{
	Expression tc = Tex2D.rtexcoord.ref();
	Expression d = code.local(FLOAT, pick(texture2D(sdep.ref(), tc), "r")).ref();
	Expression ndc = vec4(sub(mul(tc, l(2.0)), l(1.0)), sub(mul(d, l(2.0)), l(1.0)), l(1.0));
	Expression hp = code.local(VEC4, mul(ivp.ref(), ndc)).ref();
	Expression rel = code.local(VEC3, sub(div(pick(hp, "xyz"), pick(hp, "w")), eye.ref())).ref();
	Expression dist = code.local(FLOAT, length(rel)).ref();
	Expression v = code.local(VEC3, div(rel, max(dist, l(0.0001)))).ref();
	Expression issky = code.local(FLOAT, step(l(0.9999999), d)).ref();
	Expression fogc = code.local(VEC3, skybase.call(normalize(vec3(pick(v, "xy"), l(0.03))))).ref();
	Expression fp = fog.ref();
	Expression f1 = mul(smoothstep(pick(fp, "x"), pick(fp, "y"), dist), pick(fp, "z"));
	Expression f2 = sub(l(1.0), exp(neg(mul(max(sub(dist, fognear.ref()), l(0.0)), pick(fp, "w")))));
	Expression f = code.local(FLOAT, clamp(max(f1, f2), l(0.0), l(1.0))).ref();
	Expression sky = code.local(VEC3, skyopen.call(v)).ref();
	Expression fc = code.local(VEC3, mix(fogc, sky, issky)).ref();
	Expression ff = code.local(FLOAT, mix(f, l(1.0), issky)).ref();
	Expression m = code.local(VEC4, clouds.call(eye.ref(), v, mix(dist, l(1.0e6), issky))).ref();
	Expression tr = code.local(FLOAT, sub(l(1.0), pick(m, "a"))).ref();
	Expression a = code.local(FLOAT, sub(l(1.0), mul(tr, sub(l(1.0), ff)))).ref();
	code.add(new Return(vec4(div(add(pick(m, "rgb"), mul(fc, tr, ff)), max(a, l(0.0001))), a)));
    }};

    static final ShaderMacro shader = prog -> {
	FragColor.fragcol(prog.fctx).mod(in -> main.call(), 0);
    };

    public void added(RenderTree.Slot slot) {
	slot.add(new Rendered.ScreenQuad(false), p -> {
		DepthBuffer<?> dbuf = p.get(DepthBuffer.slot);
		p.prep(order);
		/* A pass that inherits the depth buffer it samples would also write it (Outlines). */
		p.put(DepthBuffer.slot, null);
		p.put(RenderedNormals.slot, null);
		/* The ground's cloud shadow is a Phong state; this pass is unlit, but take it out regardless. */
		p.put(AmbShadow.slot, null);
		/* The default sampler, exactly as Outlines makes its own: without sampler objects (GL 3.0) a
		 * texture holds ONE set of sampler parameters, and a second, different one over the same depth
		 * buffer throws in GLTexture.Tex2D.setsampler. The quad samples at texel centres anyway. */
		p.prep(new Draw(new Texture2D.Sampler2D((Texture2D)((Texture.Image)dbuf.image).tex)));
	    });
    }
}
