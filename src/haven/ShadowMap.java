/*
 *  This file is part of the Haven & Hearth game client.
 *  Copyright (C) 2009 Fredrik Tolf <fredrik@dolda2000.com>, and
 *                     Björn Johannessen <johannessen.bjorn@gmail.com>
 *
 *  Redistribution and/or modification of this file is subject to the
 *  terms of the GNU Lesser General Public License, version 3, as
 *  published by the Free Software Foundation.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  Other parts of this source tree adhere to other copying
 *  rights. Please see the file `COPYING' in the root directory of the
 *  source tree for details.
 *
 *  A copy the GNU Lesser General Public License is distributed along
 *  with the source tree of which this file is a part in the file
 *  `doc/LPGL-3'. If it is missing for any reason, please see the Free
 *  Software Foundation's website at <http://www.fsf.org/>, or write
 *  to the Free Software Foundation, Inc., 59 Temple Place, Suite 330,
 *  Boston, MA 02111-1307 USA
 */

package haven;

import java.util.*;
import haven.render.*;
import haven.render.sl.*;
import java.awt.image.*;
import haven.render.DataBuffer;
import static haven.render.sl.Cons.*;
import static haven.render.sl.Function.PDir.*;
import static haven.render.sl.Type.*;

public class ShadowMap extends State {
    public final static Slot<ShadowMap> smap = new Slot<ShadowMap>(Slot.Type.DRAW, ShadowMap.class);
    public final static State.StandAlone maskshadow = new State.StandAlone(Slot.Type.GEOM) {
	    public ShaderMacro shader() {return(null);}
	};
    public final Texture2D lbuf;
    public final Texture2D.Sampler2D lsamp;
    private final Projection lproj;
    private final Pipe.Op basic;
    private DirLight light;
    private Camera lcam;
    private Pipe.Op curbasic;
    private final static Matrix4f texbias = new Matrix4f(0.5f, 0.0f, 0.0f, 0.5f,
							 0.0f, 0.5f, 0.0f, 0.5f,
							 0.0f, 0.0f, 0.5f, 0.5f,
							 0.0f, 0.0f, 0.0f, 1.0f);

    @Material.SpecName("maskshadow")
    public static class $maskshadow implements Material.Spec {
	public void cons(Material.Buffer buf, Object... args) {buf.states.add(maskshadow);}
    }

    public ShadowMap(Coord res, float size, float depth, float dthr) {
	lbuf = new Texture2D(res, DataBuffer.Usage.STATIC, Texture.DEPTH, new VectorFormat(1, NumberFormat.FLOAT32), null);
	/* addon: sampled as a comparison, LINEAR both ways: every fetch is the hardware's blend of the four
	 * comparisons round its point (bilinear PCF), magnified or minified. Shader.shcalc reads it so. */
	(lsamp = new Texture2D.Sampler2D(lbuf)).magfilter(Texture.Filter.LINEAR).minfilter(Texture.Filter.LINEAR)
	    .wrapmode(Texture.Wrapping.CLAMP).compare(true);
	/* XXX: It would arguably be nice to intern the shader. */
	shader = Shader.get(1.0 / res.x, 1.0 / res.y, 4, dthr / depth);
	lproj = Projection.ortho(-size, size, -size, size, 1, depth);
	basic = Pipe.Op.compose(new DepthBuffer<>(lbuf.image(0)),
				new States.Viewport(Area.sized(Coord.z, res)),
				lproj);
    }

    private ShadowMap(ShadowMap that) {
	this.lbuf     = that.lbuf;
	this.lsamp    = that.lsamp;
	this.shader   = that.shader;
	this.lproj    = that.lproj;
	this.basic    = that.basic;
	this.light    = that.light;
	this.lcam     = that.lcam;
	this.curbasic = that.curbasic;
    }

    public void dispose() {
	lbuf.dispose();
    }

    public static class ShadowList implements RenderList<Rendered>, RenderList.Adapter, Disposable {
	public static final Pipe.Op shadowbasic = Pipe.Op.compose(new States.Depthtest(States.Depthtest.Test.LE),
								  new States.Facecull(),
								  Homo3D.state);
	private final RenderList.Adapter master;
	private final ProxyPipe basic = new ProxyPipe();
	private final Map<Slot<? extends Rendered>, Shadowslot> slots = new HashMap<>();
	/* addon: THE CASTERS THE MAP CAN HOLD. Upstream drew every lit slot of the scene into the shadow
	 * map, and the map covers a box round the player: whatever stood past it was drawn and clipped, a draw
	 * call and its vertices for nothing. `slots` still holds every candidate; `back` holds the ones whose
	 * bounds reach the box (Shadowslot.drawn), and cull() re-asks a few of them each time the map is drawn,
	 * round-robin over `order`, so a caster that walks in or out, or a box that moves with the player, is
	 * followed within a sweep. The margins are the box's own units (its half-width is 1): a caster comes in
	 * within ENTER of the box and goes out past LEAVE, so one on the edge does not flicker in and out,
	 * and ENTER is wider than the 50 units the box jumps by when the player walks (MapView.updsmap). A
	 * caster nothing locates -- an instanced batch, which stands all over the scene, or a slot with no
	 * Location or no mesh bounds -- is always drawn: culling it would need an answer this does not have.
	 * Every call here is under the tree's lock, as add() and remove() are. */
	private static final double ENTER = 0.1, LEAVE = 0.2;
	private static final int SWEEP = 64, CHANGES = 32;
	private final List<Shadowslot> order = new ArrayList<>();
	private int cursor = 0;
	private Matrix4f zone = null;
	private DrawList back = null;
	private DefPipe curbasic = null;

	public ShadowList(RenderList.Adapter master) {
	    asyncadd(this.master = master, Rendered.class);
	}

	public class Shadowslot implements Slot<Rendered>, GroupPipe {
	    static final int idx_bas = 0, idx_back = 1;
	    public final Slot<? extends Rendered> bk;
	    int oidx;          // addon: its place in `order`
	    boolean drawn;     // addon: whether it stands in `back`

	    public Shadowslot(Slot<? extends Rendered> bk) {
		this.bk = bk;
	    }

	    public Rendered obj() {
		return(bk.obj());
	    }

	    public GroupPipe state() {
		return(this);
	    }

	    public Pipe group(int idx) {
		switch(idx) {
		case idx_bas: return(basic);
		default: return(bk.state().group(idx - idx_back));
		}
	    }

	    public int gstate(int id) {
		if(State.Slot.byid(id).type == State.Slot.Type.GEOM) {
		    int ret = bk.state().gstate(id);
		    if(ret >= 0)
			return(ret + idx_back);
		}
		if((id < curbasic.mask.length) && curbasic.mask[id])
		    return(idx_bas);
		return(-1);
	    }

	    public int nstates() {
		return(Math.max(bk.state().nstates(), curbasic.mask.length));
	    }
	}

	public void add(Slot<? extends Rendered> slot) {
	    if((slot.state().get(Light.lighting) == null) || (slot.state().get(maskshadow.slot) != null))
		return;
	    Shadowslot ns = new Shadowslot(slot);
	    ns.drawn = inzone(ns, ENTER);   // addon:
	    if(ns.drawn && (back != null))
		back.add(ns);
	    if((slots.put(slot, ns)) != null)
		throw(new AssertionError());
	    ns.oidx = order.size();         // addon:
	    order.add(ns);
	}

	public void remove(Slot<? extends Rendered> slot) {
	    Shadowslot cs = slots.remove(slot);
	    if(cs != null) {
		if(cs.drawn && (back != null))   // addon: only what stands in it
		    back.remove(cs);
		/* addon: out of the sweep: the last one takes its place. */
		Shadowslot last = order.remove(order.size() - 1);
		if(last != cs) {
		    order.set(cs.oidx, last);
		    last.oidx = cs.oidx;
		}
	    }
	}

	public void update(Slot<? extends Rendered> slot) {
	    if(back != null) {
		Shadowslot cs = slots.get(slot);
		if((cs != null) && cs.drawn) {   // addon: one out of the zone has nothing in `back` to update
		    back.update(cs);
		}
	    }
	}

	/* addon: does this caster's mesh reach the box, `margin` beyond its edge? `zone` takes the world to
	 * the map's clip space (the light's projection times the light's camera), where the box is [-1, 1] in x
	 * and y; the mesh's bounds are taken there corner by corner, so a rotated or scaled object is tested as
	 * drawn. Anything it cannot answer is in. */
	private boolean inzone(Shadowslot s, double margin) {
	    Matrix4f zone = this.zone;
	    if(zone == null)
		return(true);
	    try {
		if(s.bk instanceof InstanceBatch)
		    return(true);
		GroupPipe st = s.bk.state();
		if(st.get(Homo3D.loc) == null)
		    return(true);
		Rendered obj = s.bk.obj();
		if(!(obj instanceof FastMesh))
		    return(true);
		Volume3f b = ((FastMesh)obj).bounds();
		Matrix4f xf = zone.mul(Homo3D.locxf(st));
		float nx = Float.POSITIVE_INFINITY, ny = Float.POSITIVE_INFINITY;
		float px = Float.NEGATIVE_INFINITY, py = Float.NEGATIVE_INFINITY;
		for(int i = 0; i < 8; i++) {
		    Coord3f c = xf.mul4(new Coord3f(((i & 1) == 0) ? b.n.x : b.p.x,
						    ((i & 2) == 0) ? b.n.y : b.p.y,
						    ((i & 4) == 0) ? b.n.z : b.p.z));
		    nx = Math.min(nx, c.x); px = Math.max(px, c.x);
		    ny = Math.min(ny, c.y); py = Math.max(py, c.y);
		}
		float e = (float)(1.0 + margin);
		return((px >= -e) && (nx <= e) && (py >= -e) && (ny <= e));
	    } catch(RuntimeException exc) {
		return(true);
	    }
	}

	/* addon: the sweep, from ShadowMap.update each time the map is drawn: SWEEP candidates asked, at
	 * most CHANGES of them moved in or out of `back`, since each move compiles or drops a draw slot. A
	 * caster that cannot be compiled yet (a texture still loading) stays out and is asked again next
	 * round, rather than throwing out of the frame. */
	public void cull(Matrix4f zone) {
	    try(Locked lk = lock()) {
		this.zone = zone;
		int n = Math.min(order.size(), SWEEP), changed = 0;
		for(int i = 0; (i < n) && (changed < CHANGES); i++) {
		    if(cursor >= order.size())
			cursor = 0;
		    Shadowslot s = order.get(cursor++);
		    boolean want = inzone(s, s.drawn ? LEAVE : ENTER);
		    if(want == s.drawn)
			continue;
		    changed++;
		    if(want) {
			if(back != null) {
			    try {
				back.add(s);
			    } catch(RuntimeException exc) {
				continue;
			    }
			}
			s.drawn = true;
		    } else {
			if(back != null)
			    back.remove(s);
			s.drawn = false;
		    }
		}
	    }
	}

	public void update(Pipe group, int[] statemask) {
	    if(back != null)
		back.update(group, statemask);
	}

	public Locked lock() {
	    return(master.lock());
	}

	public Iterable<? extends Slot<?>> slots() {
	    /* addon: what `back` is built from when it is made anew: the candidates in the zone. */
	    List<Shadowslot> ret = new ArrayList<>(order.size());
	    for(Shadowslot s : order) {
		if(s.drawn)
		    ret.add(s);
	    }
	    return(ret);
	}

	/* Shouldn't have to care. */
	public <R> void add(RenderList<R> list, Class<? extends R> type) {}
	public void remove(RenderList<?> list) {}

	public void basic(Pipe.Op st) {
	    try(Locked lk = lock()) {
		DefPipe buf = new DefPipe();
		buf.prep(st);
		if(curbasic != null) {
		    int[] mask = curbasic.maskdiff(buf);
		    if(mask.length != 0) {
			for(int id : mask)
			    System.err.println(State.Slot.byid(id));
			throw(new RuntimeException("changing shadowlist basic definition mask is not supported"));
		    }
		}
		int[] mask = basic.dupdate(buf);
		curbasic = buf;
		if(back != null)
		    back.update(basic, mask);
	    }
	}

	public void draw(Render out) {
	    if((back == null) || !out.env().compatible(back)) {
		if(back != null)
		    back.dispose();
		back = out.env().drawlist().desc("shadow-list: " + this);
		back.asyncadd(this, Rendered.class);
	    }
	    back.draw(out);
	}

	public void dispose() {
	    if(back != null)
		back.dispose();
	}
    }

    public ShadowMap light(DirLight light) {
	if(light == this.light)
	    return(this);
	ShadowMap ret = new ShadowMap(this);
	ret.light = light;
	return(ret);
    }

    public boolean haspos() {
	return(lcam != null);
    }

    /* addon: does `that` hold the depth this one would draw -- the same buffer, seen from the same light
     * camera? Not identity: MapView.amblight() builds a new DirLight every tick, and light() a new ShadowMap
     * for it, though neither moves the map. What the map holds depends on the camera alone. */
    public boolean samezone(ShadowMap that) {
	return((that != null) && (that.lbuf == this.lbuf) && Utils.eq(that.lcam, this.lcam));
    }

    public ShadowMap setpos(Coord3f base, Coord3f dir) {
	Camera lcam = Camera.dir(base, dir);
	if(Utils.eq(this.lcam, lcam))
	    return(this);
	ShadowMap ret = new ShadowMap(this);
	ret.lcam = lcam;
	ret.curbasic = Pipe.Op.compose(ShadowList.shadowbasic, ret.basic, lcam);
	return(ret);
    }

    public void update(Render out, ShadowList data) {
	/* XXX: FrameInfo, and potentially others, should quite
	 * arguably be inherited from some parent context instead. */
	Pipe.Op basic = Pipe.Op.compose(curbasic, new FrameInfo());
	Pipe bstate = new BufPipe().prep(basic);
	out.clear(bstate, 1.0);
	if(lcam != null)   // addon: the casters in the box, before the list is drawn
	    data.cull(lproj.fin(Matrix4f.id).mul(lcam.fin(Matrix4f.id)));
	data.basic(basic);
	data.draw(out);
	if(false)
	    GOut.debugimage(out, lbuf.image(0), new VectorFormat(1, NumberFormat.DEPTH), false, Debug::dumpimage);
    }

    public void apply(Pipe buf) {
	buf.put(smap, this);
    }

    public static class Shader implements ShaderMacro {
	public static final Uniform txf = new Uniform(MAT4, p -> {
		ShadowMap sm = p.get(smap);
		Matrix4f cm = Transform.rxinvert(p.get(Homo3D.cam).fin(Matrix4f.id));
		Matrix4f proj = sm.lproj.fin(Matrix4f.id);
		Matrix4f lcam = sm.lcam.fin(Matrix4f.id);
		Matrix4f txf = texbias.mul(proj).mul(lcam).mul(cm);
		return(txf);
	    }, smap, Homo3D.cam);
	public static final Uniform sl = new Uniform(INT, p -> {
		DirLight light = p.get(smap).light;
		Light.LightList lights = p.get(Light.lights);
		int idx = -1;
		if(light != null)
		    idx = lights.index(light);
		return(idx);
	    }, smap, Light.lights);
	public static final Uniform map = new Uniform(SAMPLER2DSHADOW, p -> p.get(smap).lsamp, smap);   // addon:
	/* addon: GLSL's texture() on a sampler2DShadow, which answers a float. It names the SAME symbol as
	 * Function.Builtin.texture: a Symbol.Fix is unique per program, and a program that samples the map samples
	 * ordinary textures too. */
	private static final Function.Builtin shtexture = new Function.Builtin(FLOAT, Function.Builtin.texture.name, 2);
	public static final AutoVarying stc = new AutoVarying(VEC4) {
		public Expression root(VertexContext vctx) {
		    return(mul(txf.ref(), Homo3D.get(vctx.prog).eyev.depref()));
		}
	    };

	public final Function.Def shcalc;
	private final Object id;

	private Shader(double xd, double yd, int res, double thr) {
	    this.id = Arrays.asList(xd, yd, res, thr);
	    this.shcalc = new Function.Def(FLOAT) {
		    {
			Expression mapc = code.local(VEC3, div(pick(stc.ref(), "xyz"), pick(stc.ref(), "w"))).ref();
			/* addon: the map covers a box around the player and nothing past it. A fragment outside
			 * that box read the CLAMPed edge texel, or lay past the light's far depth, and came out
			 * shadowed -- black smears over all distant ground. Outside the box is lit. */
			code.add(new If(or(or(lt(min(pick(mapc, "x"), pick(mapc, "y")), l(0.0)),
					      gt(max(pick(mapc, "x"), pick(mapc, "y")), l(1.0))),
					   gt(pick(mapc, "z"), l(1.0))),
					new Return(l(1.0))));
			/* addon: HARDWARE PCF. The map is sampled as a comparison (Texture.Sampler.compare, LINEAR),
			 * so one fetch is the hardware's blend of the comparisons of the four texels round its point.
			 * The loop this replaces compared res x res points a texel apart round the fragment; a fetch
			 * at (+-1, +-1) texels blends the 2 x 2 texels round it, so (res/2)^2 fetches span the same
			 * res x res texels -- four instead of sixteen -- and answer a continuous shade rather than
			 * one of res^2 + 1 steps. The reference is the fragment's depth less the bias: lit where
			 * ref <= stored, which is the loop's stored + thr > z. */
			Expression ref = code.local(FLOAT, sub(pick(mapc, "z"), l(thr))).ref();
			int n = Math.max(res / 2, 1);
			Expression[] taps = new Expression[n * n];
			for(int yi = 0, t = 0; yi < n; yi++) {
			    for(int xi = 0; xi < n; xi++, t++) {
				double xo = ((2 * xi) - (n - 1)) * xd, yo = ((2 * yi) - (n - 1)) * yd;
				taps[t] = shtexture.call(map.ref(), vec3(add(pick(mapc, "xy"), vec2(l(xo), l(yo))), ref));
			    }
			}
			code.add(new Return(mul(add(taps), l(1.0 / (n * n)))));
		    }
		};
	}

	public void modify(ProgramContext prog) {
	    final Phong ph = prog.getmod(Phong.class);
	    if((ph == null) || !ph.pfrag)
		return;
	    
	    ph.dolight.mod(new Runnable() {
		    public void run() {
			ph.dolight.dcalc.add(new If(eq(sl.ref(), ph.dolight.i),
						    stmt(amul(ph.dolight.dl.tgt, shcalc.call()))),
					     ph.dolight.dcurs);
		    }
		}, 0);
	}

	public int hashCode() {
	    return(id.hashCode());
	}

	public boolean equals(Object that) {
	    return((that instanceof Shader) && Utils.eq(this.id, ((Shader)that).id));
	}

	private static final WeakHashedSet<Shader> interned = new WeakHashedSet<>(Hash.eq);
	public static Shader get(double xd, double yd, int res, double thr) {
	    return(interned.intern(new Shader(xd, yd, res, thr)));
	}
    }

    public final Shader shader;

    public ShaderMacro shader() {return(shader);}
}
