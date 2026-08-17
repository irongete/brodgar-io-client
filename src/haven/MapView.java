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

import static haven.MCache.cmaps;
import static haven.MCache.tilesz;
import static haven.OCache.posres;
import java.awt.Color;
import java.awt.event.KeyEvent;
import java.util.*;
import java.util.function.*;
import java.lang.ref.*;
import java.lang.reflect.*;
import haven.render.*;
import haven.MCache.OverlayInfo;
import haven.render.sl.Uniform;
import haven.render.sl.Type;

public class MapView extends PView implements DTarget, Console.Directory {
    public static boolean clickdb = false;
    public long plgob = -1;
    public Coord2d cc;
    private final Glob glob;
    private int view = 2;

    private Collection<Delayed> delayed = new LinkedList<Delayed>();
    private Collection<Delayed> delayed2 = new LinkedList<Delayed>();
    public Camera camera = restorecam();
    private Loader.Future<Plob> placing = null;
    private Grabber grab;
    private Selector selection;
    private Coord3f camoff = new Coord3f(Coord3f.o);
    public double shake = 0.0;
    public static double plobpgran = Utils.getprefd("plobpgran", 8);
    public static double plobagran = Utils.getprefd("plobagran", 12);
    public static boolean invcamx = Utils.getprefb("invcamx", false);
    public static boolean invcamy = Utils.getprefb("invcamy", false);
    /* addon: (066.2) linked, so camnames() -- and the Options ▸ Camera dropdown reading it -- comes
     * out in the order the camera classes are declared, rather than in a hash order nobody chose. */
    private static final Map<String, Class<? extends Camera>> camtypes = new LinkedHashMap<String, Class<? extends Camera>>();
    
    public interface Delayed {
	public void run(GOut g);
    }

    public interface Grabber {
	boolean mmousedown(Coord mc, int button);
	boolean mmouseup(Coord mc, int button);
	boolean mmousewheel(Coord mc, int amount);
	void mmousemove(Coord mc);
    }

    public abstract class Camera implements Pipe.Op {
	protected haven.render.Camera view = new haven.render.Camera(Matrix4f.identity());
	protected Projection proj = new Projection(Matrix4f.identity());
	
	public Camera() {
	    resized();
	}

	public boolean keydown(KeyDownEvent ev) {
	    return(false);
	}

	public boolean click(Coord sc) {
	    return(false);
	}
	public void drag(Coord sc) {}
	public void release() {}
	public boolean wheel(MouseWheelEvent ev) {
	    return(false);
	}

	/* Camera-axis inversion (Options -> Camera). Applied to the raw pixel
	 * delta before it drives rotation/elevation, so every camera type shares
	 * the same two toggles. */
	protected float invdx(int dx) {return(invcamx ? -dx : dx);}
	protected float invdy(int dy) {return(invcamy ? -dy : dy);}

	public void resized() {
	    float field = 0.5f;
	    float aspect = ((float)sz.y) / ((float)sz.x);
	    proj = Projection.frustum(-field, field, -aspect * field, aspect * field, 1, 2000);
	}

	public void apply(Pipe p) {
	    proj.apply(p);
	    view.apply(p);
	}
	
	public abstract float angle();
	public abstract void tick(double dt);

	public String stats() {return("N/A");}

	/* rts: become the camera another session was being played with. A camera is an inner class of
	 * the view it draws, so several sessions cannot share one object -- what is shared is its
	 * STATE, copied here on every switch of screen so that the player has one camera and not one
	 * per character.
	 *
	 * Copied by reflection, over the subclass chain and no further: the angle, the zoom, the
	 * elevation and every option a camera type of its own invents are the player's settings and
	 * are all it has, so listing them by hand would be five lists to keep in step with upstream and
	 * one silently missed field per camera added. The chain stops at Camera itself because `view`
	 * and `proj` are not settings -- they are this view's own render state, derived from a size the
	 * other view need not share, and resized() below rebuilds them.
	 *
	 * A field holding a PLACE is nulled instead of copied, whatever its class invented it for.
	 * Coordinates are per-session: the other login's frame is a different one, and its screen is a
	 * drag that ended when the screen changed hands. Every such field in the shipped cameras is a
	 * cache the next tick refills -- except the RTS camera's pan, which is a setting, and is put
	 * back in this session's own frame by the override there.
	 *
	 * @param off this session's frame minus the other's, or null when the two cannot be related. */
	public void restate(Camera from, Coord2d off) {
	    if((from == null) || (from.getClass() != getClass()))
		return;
	    for(Class<?> c = getClass(); (c != Camera.class) && (c != null); c = c.getSuperclass()) {
		for(Field f : c.getDeclaredFields()) {
		    int mod = f.getModifiers();
		    if(Modifier.isStatic(mod) || Modifier.isFinal(mod) || f.isSynthetic())
			continue;
		    Class<?> t = f.getType();
		    boolean place = (t == Coord.class) || (t == Coord2d.class) || (t == Coord3f.class);
		    try {
			f.setAccessible(true);
			f.set(this, place ? null : f.get(from));
		    } catch(ReflectiveOperationException e) {
			new Warning(e, "camera: could not carry " + f.getName() + " across").issue();
		    }
		}
	    }
	    resized();
	}
    }
    
    public class FollowCam extends Camera {
	private final float fr = 0.0f, h = 10.0f;
	private float ca, cd;
	private Coord3f curc = null;
	private float elev, telev;
	private float angl, tangl;
	private Coord dragorig = null;
	private float anglorig;
	
	public FollowCam() {
	    elev = telev = (float)Math.PI / 6.0f;
	    angl = tangl = 0.0f;
	}
	
	public void resized() {
	    ca = (float)sz.y / (float)sz.x;
	    cd = 400.0f * ca;
	}
	
	public boolean click(Coord c) {
	    anglorig = tangl;
	    dragorig = c;
	    return(true);
	}
	
	public void drag(Coord c) {
	    tangl = anglorig + (invdx(c.x - dragorig.x) / 100.0f);
	    tangl = tangl % ((float)Math.PI * 2.0f);
	}

	private double f0 = 0.2, f1 = 0.5, f2 = 0.9;
	private double fl = Math.sqrt(2);
	private double fa = ((fl * (f1 - f0)) - (f2 - f0)) / (fl - 2);
	private double fb = ((f2 - f0) - (2 * (f1 - f0))) / (fl - 2);
	private float field(float elev) {
	    double a = elev / (Math.PI / 4);
	    return((float)(f0 + (fa * a) + (fb * Math.sqrt(a))));
	}

	private float dist(float elev) {
	    float da = (float)Math.atan(ca * field(elev));
	    return((float)(((cd - (h / Math.tan(elev))) * Math.sin(elev - da) / Math.sin(da)) - (h / Math.sin(elev))));
	}

	public void tick(double dt) {
	    elev += (telev - elev) * (float)(1.0 - Math.pow(500, -dt));
	    if(Math.abs(telev - elev) < 0.0001)
		elev = telev;
	    
	    float dangl = tangl - angl;
	    while(dangl >  Math.PI) dangl -= (float)(2 * Math.PI);
	    while(dangl < -Math.PI) dangl += (float)(2 * Math.PI);
	    angl += dangl * (float)(1.0 - Math.pow(500, -dt));
	    if(Math.abs(tangl - angl) < 0.0001)
		angl = tangl;
	    
	    Coord3f cc = getcc().invy();
	    if(curc == null)
		curc = cc;
	    float dx = cc.x - curc.x, dy = cc.y - curc.y;
	    float dist = (float)Math.sqrt((dx * dx) + (dy * dy));
	    if(dist > 250) {
		curc = cc;
	    } else if(dist > fr) {
		Coord3f oc = curc;
		float pd = (float)Math.cos(elev) * dist(elev);
		Coord3f cambase = new Coord3f(curc.x + ((float)Math.cos(tangl) * pd), curc.y + ((float)Math.sin(tangl) * pd), 0.0f);
		float a = cc.xyangle(curc);
		float nx = cc.x + ((float)Math.cos(a) * fr), ny = cc.y + ((float)Math.sin(a) * fr);
		Coord3f tgtc = new Coord3f(nx, ny, cc.z);
		curc = curc.add(tgtc.sub(curc).mul((float)(1.0 - Math.pow(500, -dt))));
		if(curc.dist(tgtc) < 0.01)
		    curc = tgtc;
		tangl = curc.xyangle(cambase);
	    }
	    
	    float field = field(elev);
	    view = haven.render.Camera.pointed(curc.add(camoff).add(0.0f, 0.0f, h), dist(elev), elev, angl);
	    proj = Projection.frustum(-field, field, -ca * field, ca * field, 1, 2000);
	}

	public float angle() {
	    return(angl);
	}
	
	private static final float maxang = (float)(Math.PI / 2 - 0.1);
	private static final float mindist = 50.0f;
	public boolean wheel(MouseWheelEvent ev) {
	    float fe = telev;
	    telev += ev.s * telev * 0.02f;
	    if(telev > maxang)
		telev = maxang;
	    if(dist(telev) < mindist)
		telev = fe;
	    return(true);
	}

	public String stats() {
	    return(String.format("%f %f %f", elev, dist(elev), field(elev)));
	}
    }
    static {camtypes.put("follow", FollowCam.class);}

    public class SimpleCam extends Camera {
	private float dist = 50.0f;
	private float elev = (float)Math.PI / 4.0f;
	private float angl = 0.0f;
	private Coord dragorig = null;
	private float elevorig, anglorig;

	public void tick(double dt) {
	    Coord3f cc = getcc().invy();
	    view = haven.render.Camera.pointed(cc.add(camoff).add(0.0f, 0.0f, 15f), dist, elev, angl);
	}
	
	public float angle() {
	    return(angl);
	}
	
	public boolean click(Coord c) {
	    elevorig = elev;
	    anglorig = angl;
	    dragorig = c;
	    return(true);
	}
	
	public void drag(Coord c) {
	    elev = elevorig - (invdy(c.y - dragorig.y) / 100.0f);
	    if(elev < 0.0f) elev = 0.0f;
	    if(elev > (Math.PI / 2.0)) elev = (float)Math.PI / 2.0f;
	    angl = anglorig + (invdx(c.x - dragorig.x) / 100.0f);
	    angl = angl % ((float)Math.PI * 2.0f);
	}

	public boolean wheel(MouseWheelEvent ev) {
	    float d = dist + (float)(ev.s * 25);
	    if(d < 5)
		d = 5;
	    dist = d;
	    return(true);
	}
    }
    static {camtypes.put("worse", SimpleCam.class);}

    public class FreeCam extends Camera {
	/* rts: (F4) where the camera looks -- see OrthoCam.camcc(). */
	protected Coord3f camcc() {
	    return(getcc().invy());
	}

	/* rts: (F4) protected, not private -- RTSCam drives the same controls from the keyboard and
	 * sizes its frustum from the distance. */
	protected float dist = 50.0f, tdist = dist;
	protected float elev = (float)Math.PI / 4.0f, telev = elev;
	protected float angl = 0.0f, tangl = angl;
	private Coord dragorig = null;
	private float elevorig, anglorig;
	private final float pi2 = (float)(Math.PI * 2);
	private Coord3f cc = null;

	public void tick(double dt) {
	    float cf = (1f - (float)Math.pow(500, -dt * 3));
	    angl = angl + ((tangl - angl) * cf);
	    while(angl > pi2) {angl -= pi2; tangl -= pi2; anglorig -= pi2;}
	    while(angl < 0)   {angl += pi2; tangl += pi2; anglorig += pi2;}
	    if(Math.abs(tangl - angl) < 0.0001) angl = tangl;

	    elev = elev + ((telev - elev) * cf);
	    if(Math.abs(telev - elev) < 0.0001) elev = telev;

	    dist = dist + ((tdist - dist) * cf);
	    if(Math.abs(tdist - dist) < 0.0001) dist = tdist;

	    Coord3f mc = camcc();   // rts: (F4) -- was getcc().invy()
	    if((cc == null) || (Math.hypot(mc.x - cc.x, mc.y - cc.y) > 250))
		cc = mc;
	    else
		cc = cc.add(mc.sub(cc).mul(cf));
	    view = haven.render.Camera.pointed(cc.add(0.0f, 0.0f, 15f), dist, elev, angl);
	}

	public float angle() {
	    return(angl);
	}

	public boolean click(Coord c) {
	    elevorig = elev;
	    anglorig = angl;
	    dragorig = c;
	    return(true);
	}

	public void drag(Coord c) {
	    telev = elevorig - (invdy(c.y - dragorig.y) / 100.0f);
	    if(telev < 0.0f) telev = 0.0f;
	    if(telev > (Math.PI / 2.0)) telev = (float)Math.PI / 2.0f;
	    tangl = anglorig + (invdx(c.x - dragorig.x) / 100.0f);
	}

	public boolean wheel(MouseWheelEvent ev) {
	    float d = tdist + (float)(ev.s * 25);
	    if(d < 5)
		d = 5;
	    tdist = d;
	    return(true);
	}
    }
    static {camtypes.put("bad", FreeCam.class);}
    
    public class OrthoCam extends Camera {
	public boolean exact = true;
	protected float dfield = (float)(100 * Math.sqrt(2));
	protected float dist = 500.0f;
	protected float elev = (float)Math.PI / 6.0f;
	protected float angl = -(float)Math.PI / 4.0f;
	protected float field = dfield;
	private Coord dragorig = null;
	private float anglorig;
	protected Coord3f cc, jc;

	/* rts: (F4, specs/rts/plan.md) where the camera looks. Every shipped camera looks at the
	 * player and at nothing else, and this is the one line that said so -- factored out so that a
	 * camera can look somewhere else without reimplementing the smoothing, the isometric snap and
	 * the pixel-exact correction below. */
	protected Coord3f camcc() {
	    return(getcc().invy());
	}

	public void tick2(double dt) {
	    this.cc = camcc();
	}

	public void tick(double dt) {
	    tick2(dt);
	    float aspect = ((float)sz.y) / ((float)sz.x);
	    Matrix4f vm = haven.render.Camera.makepointed(new Matrix4f(), cc.add(camoff).add(0.0f, 0.0f, 15f), dist, elev, angl);
	    if(exact) {
		if(jc == null)
		    jc = cc;
		float pfac = rsz.x / (field * 2);
		Coord3f vjc = vm.mul4(jc).mul(pfac);
		Coord3f corr = new Coord3f(Math.round(vjc.x) - vjc.x, Math.round(vjc.y) - vjc.y, 0).div(pfac);
		if((Math.abs(vjc.x) > 500) || (Math.abs(vjc.y) > 500))
		    jc = null;
		vm = Location.makexlate(new Matrix4f(), corr).mul1(vm);
	    }
	    view = new haven.render.Camera(vm);
	    proj = Projection.ortho(-field, field, -field * aspect, field * aspect, 1, 5000);
	}

	public float angle() {
	    return(angl);
	}

	public boolean click(Coord c) {
	    anglorig = angl;
	    dragorig = c;
	    return(true);
	}

	public void drag(Coord c) {
	    angl = anglorig + (invdx(c.x - dragorig.x) / 100.0f);
	    angl = angl % ((float)Math.PI * 2.0f);
	}

	public String stats() {
	    return(String.format("%.1f %.2f %.2f %.1f", dist, elev / Math.PI, angl / Math.PI, field));
	}
    }

    public static KeyBinding kb_camleft  = KeyBinding.get("cam-left",  KeyMatch.forcode(KeyEvent.VK_LEFT, 0));
    public static KeyBinding kb_camright = KeyBinding.get("cam-right", KeyMatch.forcode(KeyEvent.VK_RIGHT, 0));
    public static KeyBinding kb_camin    = KeyBinding.get("cam-in",    KeyMatch.forcode(KeyEvent.VK_UP, 0));
    public static KeyBinding kb_camout   = KeyBinding.get("cam-out",   KeyMatch.forcode(KeyEvent.VK_DOWN, 0));
    public static KeyBinding kb_camreset = KeyBinding.get("cam-reset", KeyMatch.forcode(KeyEvent.VK_HOME, 0));
    public class SOrthoCam extends OrthoCam {
	private Coord dragorig = null;
	private float anglorig;
	private float tangl = angl;
	private float tfield = field;
	private boolean isometric = true;
	private final float pi2 = (float)(Math.PI * 2);
	private double tf = 1.0;

	public SOrthoCam(String... args) {
	    PosixArgs opt = PosixArgs.getopt(args, "enift:Z:");
	    for(char c : opt.parsed()) {
		switch(c) {
		case 'e':
		    exact = true;
		    break;
		case 'n':
		    exact = false;
		    break;
		case 'i':
		    isometric = true;
		    break;
		case 'f':
		    isometric = false;
		    break;
		case 't':
		    tf = Double.parseDouble(opt.arg);
		    break;
		case 'Z':
		    field = tfield = dfield = Float.parseFloat(opt.arg);
		    break;
		}
	    }
	}

	public void tick2(double dt) {
	    dt *= tf;
	    float cf = 1f - (float)Math.pow(500, -dt);
	    Coord3f mc = camcc();   // rts: (F4) -- was getcc().invy()
	    if((cc == null) || (Math.hypot(mc.x - cc.x, mc.y - cc.y) > 250))
		cc = mc;
	    else if(!exact || (mc.dist(cc) > 2))
		cc = cc.add(mc.sub(cc).mul(cf));

	    angl = angl + ((tangl - angl) * cf);
	    while(angl > pi2) {angl -= pi2; tangl -= pi2; anglorig -= pi2;}
	    while(angl < 0)   {angl += pi2; tangl += pi2; anglorig += pi2;}
	    if(Math.abs(tangl - angl) < 0.001)
		angl = tangl;
	    else
		jc = cc;

	    field = field + ((tfield - field) * cf);
	    if(Math.abs(tfield - field) < 0.1)
		field = tfield;
	    else
		jc = cc;
	}

	public boolean click(Coord c) {
	    anglorig = angl;
	    dragorig = c;
	    return(true);
	}

	public void drag(Coord c) {
	    tangl = anglorig + (invdx(c.x - dragorig.x) / 100.0f);
	}

	public void release() {
	    if(isometric && (tfield > 100))
		tangl = (float)(Math.PI * 0.5 * (Math.floor(tangl / (Math.PI * 0.5)) + 0.5));
	}

	private void chfield(float nf) {
	    tfield = nf;
	    tfield = Math.max(Math.min(tfield, sz.x * (float)Math.sqrt(2) / 8f), 50);
	    if(tfield > 100)
		release();
	}

	public boolean wheel(MouseWheelEvent ev) {
	    chfield(tfield + (float)ev.s * 10);
	    return(true);
	}

	public boolean keydown(KeyDownEvent ev) {
	    if(kb_camleft.key().match(ev)) {
		tangl = (float)(Math.PI * 0.5 * (Math.floor((tangl / (Math.PI * 0.5)) - 0.51) + 0.5));
		return(true);
	    } else if(kb_camright.key().match(ev)) {
		tangl = (float)(Math.PI * 0.5 * (Math.floor((tangl / (Math.PI * 0.5)) + 0.51) + 0.5));
		return(true);
	    } else if(kb_camin.key().match(ev)) {
		chfield(tfield - 50);
		return(true);
	    } else if(kb_camout.key().match(ev)) {
		chfield(tfield + 50);
		return(true);
	    } else if(kb_camreset.key().match(ev)) {
		tangl = angl + (float)Utils.cangle(-(float)Math.PI * 0.25f - angl);
		chfield(dfield);
		return(true);
	    }
	    return(false);
	}
    }
    static {camtypes.put("ortho", SOrthoCam.class);}

    /* rts: the two keys the multi-session mode owns, listed as "Multi session" in the keybind panel.
     * Both start UNBOUND (KeyMatch.nil, the client's idiom for a remappable id with no default) and the
     * user assigns them: no default can be conflict-free, since `get` runs none of `set`'s exclusivity
     * pass, so a default sharing a key with another binding leaves both firing and neither repairable. */
    // rts: centre the view on the selection -- the RTS gesture, through the client's own rebindable
    // binding system rather than a hard-wired key.
    public static KeyBinding kb_rtsfocus = KeyBinding.get("rts-focus", KeyMatch.nil);
    // rts: hand the screen to the next session -- the only way to reach another character's HUD.
    public static KeyBinding kb_rtsnext = KeyBinding.get("rts-next-anchor", KeyMatch.nil);

    /* rts: (F4, specs/rts/plan.md) the RTS camera, `:cam rts`. It is an SOrthoCam in every respect --
     * the same isometric snap, the same wheel zoom, the same rotation on the arrow keys -- except that
     * it has a centre of its own instead of being bolted to the player. That is the whole difference
     * between a camera you play a character with and one you command a group with.
     *
     * It belongs to no mode: it is one of the client's cameras, installed by name like any other, and
     * the RTS mode merely happens to install it. So it answers the client's own cam-* bindings and
     * nothing else -- the keys the mode owns are dispatched by io.brodgar.session.Control, which
     * MapView.keydown already runs ahead of the camera.
     *
     * The middle button pans instead of rotating: rotation is on the arrow keys already, and an RTS
     * without panning is not one. The pan solves the screen delta back into world units through the
     * view's OWN projection (three probes and a 2x2 inverse) rather than by rebuilding the camera's
     * trigonometry -- so it stays exact at any angle, elevation or zoom, and cannot drift out of
     * agreement with what is actually on screen. */
    public class RTSCam extends FreeCam {
	private Coord2d center = null;      // null = follow the player, exactly as FreeCam does
	private Coord2d dragorig = null;
	private Coord dragsc = null;
	private boolean rotating = false;

	public RTSCam(String... args) {
	    super();
	}

	private float lastz = 0;

	protected Coord3f camcc() {
	    Coord2d c = this.center;
	    if(c == null)
		return(super.camcc());
	    /* This must NOT throw Loading. MapView.draw turns a camera Loading into a black screen and
	     * "Waiting for map data...", which is right for a camera bolted to a character -- it cannot
	     * be anywhere the player has not been. A free camera can, and routinely is: panned over a
	     * merged patch, the ANCHOR has no map there at all and never will. So ask the session that
	     * does have that ground, and failing that keep the last height rather than the whole view. */
	    try {
		Coord3f p = glob.map.getzp(c);
		lastz = p.z;
		return(p.invy());
	    } catch(Loading e) {
		lastz = (float)io.brodgar.session.Sessions.groundz(c, lastz);
		return(new Coord3f((float)c.x, -(float)c.y, lastz));
	    }
	}

	/** Where the camera is looking now, whether or not it has been panned. */
	public Coord2d center() {
	    Coord2d c = this.center;
	    if(c != null)
		return(c);
	    try {
		Coord3f p = getcc();
		return(Coord2d.of(p.x, p.y));
	    } catch(Loading e) {
		return(null);
	    }
	}

	public void focus(Coord2d c) {this.center = c;}
	public void follow()         {this.center = null;}

	/* rts: the pan is the one place this camera holds that is a SETTING and not a cache, so it is
	 * the one Camera.restate cannot simply drop. It is carried over translated: a panned camera
	 * looking at a patch of ground goes on looking at that same patch when the screen changes
	 * hands, which is what one camera across the characters means. Untranslatable -- the two
	 * sessions share no ground and `off` is null -- and it falls back to following the character
	 * this view draws, since a coordinate from another login would point at nowhere in particular. */
	public void restate(Camera from, Coord2d off) {
	    super.restate(from, off);
	    if((off != null) && (from instanceof RTSCam)) {
		Coord2d c = ((RTSCam)from).center;
		this.center = (c == null) ? null : c.add(off);
	    }
	}

	public boolean click(Coord sc) {
	    /* Ctrl keeps FreeCam's own gesture -- rotate and elevate -- because this camera is that camera
	     * in every other respect and there is nowhere else to put it. Bare drag pans.
	     *
	     * Hard-wired to a modifier rather than a rebindable id, because the keybind panel cannot express
	     * one: KeyMatch.Capture.handle refuses VK_SHIFT/VK_CONTROL/VK_ALT/VK_META/VK_WINDOWS, and that
	     * refusal is what keeps the key grab open across the modifier press so the chord that follows it
	     * can be captured at all. A binding could therefore only ever hold a NON-modifier key, which is
	     * the wrong shape for a hold-while-dragging gesture.
	     *
	     * No modifier collides here: mousedown sends ev.b == 2 straight to camera.click with no modifier
	     * branch and no fallthrough, so the middle button on this widget is the camera and nothing else.
	     * The other Ctrl gestures in reach are on different events -- StdPlace.rotate is Ctrl and the
	     * wheel TURNING while placing, and the RTS marquee's additive select (io.brodgar.session.Control)
	     * takes Shift or Ctrl on buttons 1 and 3. Read once, at the press, so letting go mid-drag does
	     * not change what the drag is already doing. */
	    rotating = (ui.modflags() & UI.MOD_CTRL) != 0;
	    if(rotating)
		return(super.click(sc));
	    dragsc = sc;
	    dragorig = center();
	    return(true);
	}

	public void drag(Coord sc) {
	    if(rotating) {
		super.drag(sc);
		return;
	    }
	    Coord2d o = dragorig;
	    if((o == null) || (dragsc == null))
		return;
	    Coord2d d = unproject(o, sc.sub(dragsc));
	    if(d != null)
		this.center = o.sub(d);   // the ground follows the cursor, so the centre moves against it
	}

	public void release() {
	    if(rotating)
		super.release();
	    rotating = false;
	}

	/** The world delta that would move a point at {@code at} by {@code dsc} pixels on this screen. */
	private Coord2d unproject(Coord2d at, Coord dsc) {
	    Coord3f p0 = screenxf(at), px = screenxf(at.add(1, 0)), py = screenxf(at.add(0, 1));
	    if((p0 == null) || (px == null) || (py == null))
		return(null);
	    double a = px.x - p0.x, b = py.x - p0.x, c = px.y - p0.y, d = py.y - p0.y;
	    double det = (a * d) - (b * c);
	    if(Math.abs(det) < 1e-9)
		return(null);
	    return(Coord2d.of(((d * dsc.x) - (b * dsc.y)) / det,
			      ((a * dsc.y) - (c * dsc.x)) / det));
	}

	/* rts: (F4) the frustum follows the distance.
	 *
	 * Camera.resized() fixes the far plane at 2000, which is generous for a camera bolted to a
	 * character and is the entire world for one that is not: pull back past it and everything --
	 * ground included -- is clipped, and the screen goes black. It is not a culling setting
	 * anywhere, it is the projection, so no amount of draw-distance tuning would have touched it.
	 * The near plane rises with the distance too, or the depth buffer would lose all its precision
	 * to the first ten metres. */
	private void setproj() {
	    float aspect = ((float)sz.y) / ((float)sz.x);
	    float near = Math.max(1f, dist / 200f);
	    /* The frustum rectangle is given AT THE NEAR PLANE -- makefrustum's scale term is
	     * 2*near/(right-left) -- so `field` is not an angle, it is a size, and holding it fixed
	     * while the near plane moves changes the field of view by exactly that ratio. Scaling it
	     * with `near` is what keeps the angle constant; the shipped cameras get away with the
	     * constant 0.5f only because their near plane never moves off 1. */
	    float field = 0.5f * near;
	    float far = (dist * 4f) + 5000f;
	    proj = Projection.frustum(-field, field, -aspect * field, aspect * field, near, far);
	}

	public void resized() {
	    super.resized();
	    setproj();
	}

	public void tick(double dt) {
	    super.tick(dt);
	    setproj();
	}

	/* Proportional, not a fixed step: 25 units a notch is a shove up close and imperceptible far
	 * out, which is what makes pulling back feel like it has a wall in front of it. */
	public boolean wheel(MouseWheelEvent ev) {
	    zoom((float)Math.pow(1.15, ev.s));
	    return(true);
	}

	private void zoom(float f) {
	    tdist = Math.max(5f, Math.min(tdist * f, 20000f));
	}

	public boolean keydown(KeyDownEvent ev) {
	    if(kb_camreset.key().match(ev)) {
		follow();
		return(true);
	    }
	    /* FreeCam has no keyboard at all -- its rotation is a drag and nothing else. An RTS camera
	     * needs turning without giving up the mouse, and these are the client's own rebindable
	     * camera bindings, so they are where a player would already look for it. */
	    if(kb_camleft.key().match(ev)) {
		tangl -= (float)(Math.PI / 8);
		return(true);
	    }
	    if(kb_camright.key().match(ev)) {
		tangl += (float)(Math.PI / 8);
		return(true);
	    }
	    if(kb_camin.key().match(ev)) {
		zoom(1f / 1.4f);
		return(true);
	    }
	    if(kb_camout.key().match(ev)) {
		zoom(1.4f);
		return(true);
	    }
	    return(super.keydown(ev));
	}

	public String stats() {
	    return(String.format("%.0f %.2f%s", dist, angl / Math.PI, (center == null) ? " follow" : " free"));
	}
    }
    static {camtypes.put("rts", RTSCam.class);}

    @RName("mapview")
    public static class $_ implements Factory {
	public Widget create(UI ui, Object[] args) {
	    Coord sz = UI.scale((Coord)args[0]);
	    Coord2d mc = ((Coord)args[1]).mul(posres);
	    long pgob = -1;
	    if(args.length > 2)
		pgob = Utils.uiv(args[2]);
	    return(new MapView(sz, ui.sess.glob, mc, pgob));
	}
    }
    
    public MapView(Coord sz, Glob glob, Coord2d cc, long plgob) {
	super(sz);
	this.glob = glob;
	this.cc = cc;
	this.plgob = plgob;
	basic.add(new Outlines(false));
	this.gobs = new Gobs();
	this.terrain = new Terrain();
	this.clickmap = new ClickMap();
	setcanfocus(true);
	/* rts: a member's view is built but never enters the render tree (F0, specs/rts/plan.md).
	 * That is not cosmetic. MapRaster.Grid.tick() is a no-op while its node has no slot, and Gobs
	 * registers its OCache callback only when it is added -- so this one branch is the whole
	 * difference between a dormant session costing network and one meshing terrain, building gob
	 * sprites and rasterizing a click-map for a picture nobody will ever look at. The voice channel
	 * and the addon engine are the client's one live pair for the same reason: they follow the view
	 * that is drawn. Promoting a member to anchor (F5) is what will need the inverse of this. */
	this.dormant = io.brodgar.session.Sessions.dormant(glob);
	if(!dormant) {
	    attachscene();
	    io.brodgar.voice.Voice.attach(this);   // brodgar voice: connect on entering the game
	    io.brodgar.addon.AddonManager.attach(this);   // addon: the world came up -> EnterWorld on the next tick
	}
    }

    // rts: (F0) the scene's own slots, so that not attaching them is expressible -- and (F5) reversible.
    private boolean dormant;
    private RenderTree.Slot s_gobs = null, s_terrain = null, s_clickmap = null;

    private void attachscene() {
	if(s_gobs == null)     s_gobs = basic.add(gobs);
	if(s_terrain == null)  s_terrain = basic.add(terrain);
	if(s_clickmap == null) s_clickmap = clmaptree.add(clickmap);
    }

    private void detachscene() {
	/* The merged member patches are the anchor's business and nobody else's: a dormant view has no
	 * frame of reference to hang them in and nothing to draw them to. Dropped whole; sessiontick()
	 * builds them again from scratch if this view is ever promoted back. */
	for(SessionView fv : sessionviews.values())
	    fv.remove();
	sessionviews.clear();
	droprecall();   // 068.2: a dormant view draws no ground of its own and none it remembers
	if(s_gobs != null)     {s_gobs.remove();     s_gobs = null;}
	if(s_terrain != null)  {s_terrain.remove();  s_terrain = null;}
	if(s_clickmap != null) {s_clickmap.remove(); s_clickmap = null;}
    }

    /** rts: is this the view of a session the client holds but does not draw? (F0) */
    public boolean dormant() {
	return(dormant);
    }

    /**
     * rts: (F5) become, or stop being, the view that is drawn.
     *
     * <p>The scene is torn out and rebuilt rather than hidden, because "in a render tree" is exactly
     * what decides whether terrain is meshed at all — the same fact that makes a dormant session cheap
     * (F0) is what makes this switch cost something. Promotion re-meshes everything in view, which is
     * the hitch this phase exists to measure.
     *
     * <p>Deliberately does <em>not</em> move the addon engine or the voice channel. Both are
     * single-session static hubs bound at the main session's own view, and rebinding them is
     * {@code AddonManager.init} — a full teardown and reload of every addon, which is not what a key
     * press should do. They stay where they are until F6 makes them per-session.
     */
    public void dormant(boolean d) {
	if(d == dormant)
	    return;
	dormant = d;
	if(d)
	    detachscene();
	else
	    attachscene();
    }
    
    protected void envdispose() {
	if(smap != null) {
	    smap.dispose(); smap = null;
	    slist.dispose(); slist = null;
	}
	super.envdispose();
    }

    public void dispose() {
	io.brodgar.voice.Voice.detach(this);   // brodgar voice: stop instantly on logout
	if(s_gobs != null) {   // rts: a dormant view never attached its scene (F0)
	    s_gobs.remove();
	    s_gobs = null;
	}
	clmaplist.dispose();
	clobjlist.dispose();
	super.dispose();
    }

    public boolean visol(String tag) {
	synchronized(oltags) {
	    return(oltags.containsKey(tag));
	}
    }

    public void enol(String tag) {
	synchronized(oltags) {
	    oltags.put(tag, oltags.getOrDefault(tag, 0) + 1);
	}
    }

    public void disol(String tag) {
	synchronized(oltags) {
	    Integer rc = oltags.get(tag);
	    if((rc != null) && (--rc > 0))
		oltags.put(tag, rc);
	    else
		oltags.remove(tag);;
	}
    }

    private final Gobs gobs;
    private class Gobs implements RenderTree.Node, OCache.ChangeCallback {
	/* rts: (F7, specs/rts/plan.md) the Glob is a constructor argument now -- it was `glob`, this
	 * view's own, and nothing else. A member's objects are rendered into this same scene by a
	 * second instance reading the member's Glob, so the field had to stop being a constant. */
	final Glob g;
	final OCache oc;
	final Map<Gob, Loader.Future<?>> adding = new HashMap<>();
	final Map<Gob, RenderTree.Slot> current = new HashMap<>();
	RenderTree.Slot slot;

	Gobs() {this(glob);}

	Gobs(Glob g) {
	    this.g = g;
	    this.oc = g.oc;
	}


	/* rts: (F7) a gob this instance must not draw. Always false for the view's own objects; a
	 * member's instance uses it to yield anything the anchor is already drawing, so the overlap
	 * between two sessions' worlds is rendered once, not twice. */
	boolean skipgob(Gob ob) {
	    return(false);
	}

	private void addgob(Gob ob) {
	    RenderTree.Slot slot = this.slot;
	    if(slot == null)
		return;
	    synchronized(ob) {
		synchronized(this) {
		    if(!adding.containsKey(ob))
			return;
		}
		if(skipgob(ob)) {   // rts: (F7)
		    synchronized(this) {adding.remove(ob);}
		    return;
		}
		RenderTree.Slot nslot;
		try {
		    nslot = slot.add(ob.placed);
		} catch(RenderTree.SlotRemoved e) {
		    /* Ignore here as there is a harmless remove-race
		     * on disposal. */
		    return;
		}
		synchronized(this) {
		    if(adding.remove(ob) != null)
			current.put(ob, nslot);
		    else
			nslot.remove();
		}
	    }
	    dedupe(ob);   // rts: outside both monitors held here -- it reaches into another Gobs and takes its
	}

	/* rts: the merged views reconcile their dedupe on a quarter-second timer, which is right for
	 * geometry -- the boundary between two sessions' worlds moves at walking pace -- but not for an
	 * object the anchor has just this moment learned about. A member's copy of it is already in the
	 * tree, so for that quarter second BOTH copies are drawn and both tick: coincident geometry, and
	 * an effect that fires in the window is played twice. This view gaining a gob is the one event
	 * that changes the answer, so the copies go with it, and they go once the anchor's own is
	 * actually in the tree rather than when it was merely promised -- otherwise the object blinks
	 * out for however long its model takes to build. */
	void dedupe(Gob ob) {
	    for(SessionView fv : fvorder)
		fv.fgobs.drop(ob.id);
	}

	/* rts: this view's copy of an object somebody else has claimed.
	 *
	 * Under the gob's OWN monitor, which is not the one addgob was holding -- that one is the
	 * anchor's copy, and this is the member's, a different object. A gob's monitor is what TickList
	 * serializes its subtree on (GobState preps a Monitor of it), so an unsynchronized removal from
	 * a Loader thread tears the slot out from under a sprite's autotick, and the SlotRemoved that
	 * escapes TickList.tick kills the UI thread. Every other caller of removed(Gob) already holds
	 * it: OCache invokes its callbacks inside synchronized(ob), and the reconcile timer runs on the
	 * tick thread itself. */
	void drop(long id) {
	    Gob ob = oc.getgob(id);
	    if(ob != null) {
		synchronized(ob) {
		    removed(ob);
		}
	    }
	}

	public void added(RenderTree.Slot slot) {
	    synchronized(this) {
		if(this.slot != null)
		    throw(new RuntimeException());
		this.slot = slot;
		synchronized(oc) {
		    for(Gob ob : oc) {
			if(!skipgob(ob))   // rts: (F7)
			    adding.put(ob, g.loader.defer(() -> addgob(ob), null));
		    }
		    oc.callback(this);
		}
	    }
	}

	public void removed(RenderTree.Slot slot) {
	    synchronized(this) {
		if(this.slot != slot)
		    throw(new RuntimeException());
		this.slot = null;
		oc.uncallback(this);
		Collection<Loader.Future<?>> tasks = new ArrayList<>(adding.values());
		adding.clear();
		for(Loader.Future<?> task : tasks)
		    task.restart();
		current.clear();
	    }
	}

	public void added(Gob ob) {
	    if(skipgob(ob))   // rts: (F7)
		return;
	    synchronized(this) {
		if(current.containsKey(ob))
		    return;
		adding.put(ob, g.loader.defer(() -> addgob(ob), null));
	    }
	}

	public void removed(Gob ob) {
	    RenderTree.Slot slot;
	    synchronized(this) {
		slot = current.remove(ob);
		if(slot == null) {
		    Loader.Future<?> t = adding.remove(ob);
		    if(t != null)
			t.restart();
		}
	    }
	    if(slot != null) {
		try {
		    slot.remove();
		} catch(RenderTree.SlotRemoved e) {
		    /* Ignore here as there is a harmless remove-race
		     * on disposal. */
		}
	    }
	}

	public Loading loading() {
	    synchronized(this) {
		if(adding.isEmpty())
		    return(null);
		for(Loader.Future<?> t : adding.values()) {
		    Loading l = t.lastload();
		    if(l != null)
			return(l);
		}
	    }
	    return(new Loading("Loading objects..."));
	}
    }

    private class MapRaster extends RenderTree.Node.Track1 {
	/* rts: (F7) an argument, for the same reason as Gobs.g above -- a member's ground is
	 * rasterized into this scene out of the member's own MCache. */
	final MCache map;
	Area area;
	Loading lastload = new Loading("Initializing map...");

	MapRaster() {this(glob.map);}

	MapRaster(MCache map) {
	    this.map = map;
	}

	/* rts: (F7) a cut this raster must not draw. A member's ground overlaps the anchor's
	 * wherever the two are near each other, and two identical meshes in one place is z-fighting,
	 * not a merge -- so the member yields every cut the anchor is already drawing. */
	boolean skipcut(Coord cc) {
	    return(false);
	}

	abstract class Grid<T> extends RenderTree.Node.Track1 {
	    final Map<Coord, Pair<T, RenderTree.Slot>> cuts = new HashMap<>();
	    final boolean position;
	    Loading lastload = new Loading("Initializing map...");

	    Grid(boolean position) {
		this.position = position;
	    }

	    Grid() {this(true);}

	    abstract T getcut(Coord cc);
	    RenderTree.Node produce(T cut) {return((RenderTree.Node)cut);}

	    void tick() {
		if(slot == null)
		    return;
		Loading curload = null;
		for(Coord cc : area) {
		    if(MapRaster.this.skipcut(cc)) {   // rts: (F7)
			Pair<T, RenderTree.Slot> cur = cuts.remove(cc);
			if(cur != null)
			    cur.b.remove();
			continue;
		    }
		    try {
			T cut = getcut(cc);
			Pair<T, RenderTree.Slot> cur = cuts.get(cc);
			if((cur == null) || (cur.a != cut)) {
			    Coord2d pc = cc.mul(MCache.cutsz).mul(tilesz);
			    RenderTree.Node draw = produce(cut);
			    Pipe.Op cs = null;
			    if(position)
				cs = Location.xlate(new Coord3f((float)pc.x, -(float)pc.y, 0));
			    cuts.put(cc, new Pair<>(cut, slot.add(draw, cs)));
			    if(cur != null)
				cur.b.remove();
			    io.brodgar.addon.AddonManager.groundChanged();   // addon: 044.9 -- a cut's ground entered the scene
			}
		    } catch(Loading l) {
			l.boostprio(5);
			curload = l;
		    }
		}
		this.lastload = curload;
		for(Iterator<Map.Entry<Coord, Pair<T, RenderTree.Slot>>> i = cuts.entrySet().iterator(); i.hasNext();) {
		    Map.Entry<Coord, Pair<T, RenderTree.Slot>> ent = i.next();
		    if(!area.contains(ent.getKey())) {
			ent.getValue().b.remove();
			i.remove();
			io.brodgar.addon.AddonManager.groundChanged();   // addon: 044.9 -- ...and one left it
		    }
		}
	    }

	    public void removed(RenderTree.Slot slot) {
		super.removed(slot);
		cuts.clear();
	    }
	}

	void tick() {
	    /* XXX: Should be taken out of the main rendering
	     * loop. Probably not a big deal, but still. */
	    try {
		Coord cc = new Coord2d(getcc()).floor(tilesz).div(MCache.cutsz);
		area = new Area(cc.sub(view, view), cc.add(view, view).add(1, 1));
		lastload = null;
	    } catch(Loading l) {
		l.boostprio(5);
		lastload = l;
	    }
	}

	public Loading loading() {
	    if(this.lastload != null)
		return(this.lastload);
	    return(null);
	}
    }

    public final Terrain terrain;
    public class Terrain extends MapRaster {
	/* Computed once per tick and shared by both grids below, so the frustum test runs 25 times a
	 * frame rather than 50 -- and so that the ground and the grass on it can never disagree about
	 * which cuts are there. */
	final Set<Coord> vis = new HashSet<>();

	final Grid main = new Grid<MapMesh>() {
		MapMesh getcut(Coord cc) {
		    return(map.getcut(cc));
		}
	    };
	final Grid flavobjs = new Grid<RenderTree.Node>(false) {
		RenderTree.Node getcut(Coord cc) {
		    return(map.getfo(cc));
		}
	    };

	private Terrain() {
	}

	void tick() {
	    super.tick();
	    if(area != null) {
		vis.clear();
		if(culling()) {
		    for(Coord cc : area) {
			if(cutvisible(cc))
			    vis.add(cc);
		    }
		}
		main.tick();
		flavobjs.tick();
	    }
	}

	boolean skipcut(Coord cc) {
	    return(culling() && !vis.contains(cc));
	}

	/* rts: the Video option. A member's patch is culled unconditionally -- it is somewhere else and
	 * routinely off screen altogether, and it casts no shadow either way -- but the ground around
	 * the player sits inside the shadow map's own box, so culling it there is a trade the player
	 * makes rather than one the client makes for them. */
	private boolean culling() {
	    return((ui != null) && ui.gprefs.cullterrain.val);
	}

	public void added(RenderTree.Slot slot) {
	    slot.add(main);
	    slot.add(flavobjs);
	    super.added(slot);
	}

	public Loading loading() {
	    Loading ret = super.loading();
	    if(ret != null)
		return(ret);
	    if((ret = main.lastload) != null)
		return(ret);
	    if((ret = flavobjs.lastload) != null)
		return(ret);
	    return(null);
	}
    }

    /* rts: (F7, specs/rts/plan.md) the merged view.
     *
     * Up to here the project rested on the characters being near enough that the anchor's own session
     * already saw all of them. Separate them and that stops being true -- the anchor streams neither
     * the ground under the others nor their gobs, so they simply vanish, and an RTS whose units
     * disappear when they spread out is not one.
     *
     * A member's surroundings are therefore rasterized into THIS scene, out of the member's own MCache
     * and OCache, under one translation: F1's offset, which is exactly what makes two login-relative
     * frames addressable in one space. The member is still never drawn as a view -- it has no camera
     * and no click-map. What is added here are nodes in the anchor's tree that happen to read another
     * session's data.
     *
     * Both halves yield to the anchor where the two worlds overlap: the same ground drawn twice is
     * z-fighting and the same tree drawn twice is a double tree, so the anchor wins every cut and every
     * gob it already has, and the member fills in only what is missing. */
    private class SessionTerrain extends MapRaster {
	final Glob mglob;
	final long mplgob;
	final Coord cutoff;   // member cut coords -> anchor cut coords: subtract
	SessionView fv;

	final Grid main = new Grid<MapMesh>() {
		MapMesh getcut(Coord cc) {
		    return(map.getcut(cc));
		}
	    };
	SessionTerrain(Glob mglob, long mplgob, Coord cutoff) {
	    super(mglob.map);
	    this.mglob = mglob;
	    this.mplgob = mplgob;
	    this.cutoff = cutoff;
	}

	void tick() {
	    /* Centred on the MEMBER's character, in the MEMBER's tile coords -- this raster reads that
	     * session's map, so it must be addressed in that session's frame. The translation on the
	     * slot above is what puts the result in the right place on screen. */
	    area = sessionarea(mglob, mplgob, this);
	    if(area != null)
		main.tick();
	}

	boolean skipcut(Coord cc) {
	    return(!fv.mine.contains(cc));
	}

	public void added(RenderTree.Slot slot) {
	    slot.add(main);
	    super.added(slot);
	}
    }

    private class SessionGobs extends Gobs {
	private double lastdedupe = 0;
	private final Coord2d fvoff;
	SessionView fv;

	SessionGobs(Glob mglob, Coord2d fvoff) {
	    super(mglob);
	    this.fvoff = fvoff;
	}

	/* One object, one copy -- the same rule the ground got, and it was missing here.
	 *
	 * Asking only the anchor was enough while there was one member. With two, both of them see the
	 * trees standing between them and both drew them: coincident geometry, z-fighting, and two
	 * Drawables animating out of phase, which is what "the trees are dancing" looks like.
	 *
	 * Ownership is positional rather than claimed: a gob belongs to the FIRST view, in a stable
	 * order, whose session can see it. That needs no shared set and no agreement about who ran
	 * first, which matters because this is called from Loader threads as well as from the tick --
	 * hence the immutable snapshot rather than the live map. */
	/* rts: a merged view claims nothing from anybody -- the anchor's own view is the only one whose
	 * gaining an object settles the question. Ownership between two merged views is positional and
	 * needs no eviction: skipgob answers it the same way on every thread. */
	void dedupe(Gob ob) {}

	boolean skipgob(Gob ob) {
	    if(glob.oc.getgob(ob.id) != null)
		return(true);
	    SessionView[] order = fvorder;
	    for(int i = 0; i < order.length; i++) {
		if(order[i] == fv)
		    return(false);
		if(order[i].mglob.oc.getgob(ob.id) != null)
		    return(true);
	    }
	    return(false);
	}

	/* A member's OCache holds everything the server has told it about -- every tree, bush and animal
	 * around it -- and none of that was being culled, only its ground was. Off-screen objects are as
	 * free to leave the scene as off-screen ground, and there are far more of them. */
	private boolean vis(Gob ob) {
	    try {
		return(gobvisible(ob.rc.sub(fvoff)));
	    } catch(RuntimeException e) {
		return(true);   /* if it cannot be placed, keep it rather than blink it out */
	    }
	}

	/* The dedupe boundary moves as the characters walk toward and away from each other, and neither
	 * OCache fires anything when it does -- a gob the anchor gains was never removed from the
	 * member. So the two sets are reconciled on a slow timer rather than per frame: the boundary
	 * moves at walking pace and this is a few hundred map lookups. No two monitors are held at once
	 * anywhere in here. */
	void tick() {
	    double now = Utils.rtime();
	    if(now - lastdedupe < 0.25)
		return;
	    lastdedupe = now;
	    List<Gob> all = new ArrayList<>();
	    synchronized(oc) {
		for(Gob ob : oc)
		    all.add(ob);
	    }
	    List<Gob> mine;
	    synchronized(this) {
		mine = new ArrayList<>(current.keySet());
	    }
	    for(Gob ob : mine) {
		if(skipgob(ob) || !vis(ob))
		    removed(ob);
	    }
	    for(Gob ob : all) {
		if(skipgob(ob) || !vis(ob))
		    continue;
		boolean have;
		synchronized(this) {
		    have = current.containsKey(ob) || adding.containsKey(ob);
		}
		if(!have)
		    added(ob);
	    }
	}
    }

    /* rts: (F7) the member's ground in the CLICK tree. Without this the merged terrain is scenery:
     * visible, and completely unclickable, because the pick pass tests against clmaptree alone -- which
     * is exactly why an order only landed while the anchor stood near the ground being clicked. */
    private class SessionClickMap extends ClickMap {
	final Glob mglob;
	final long mplgob;
	final Coord cutoff;
	SessionView fv;

	SessionClickMap(Glob mglob, long mplgob, Coord cutoff) {
	    super(mglob.map);
	    this.mglob = mglob;
	    this.mplgob = mplgob;
	    this.cutoff = cutoff;
	}

	void tick() {
	    area = sessionarea(mglob, mplgob, this);
	    if(area != null)
		grid.tick();
	}

	boolean skipcut(Coord cc) {
	    return(!fv.mine.contains(cc));
	}
    }

    /* rts: is any part of this cut on screen? The renderer does no frustum culling of its own --
     * States.Facecull is back-face, per triangle, and nothing in RenderList or DrawList tests
     * visibility -- so every node in the tree is submitted every frame whether or not it can be seen.
     * For the client's own terrain that hardly matters: it is a small square around the camera and
     * almost all of it is in view. A member's patch is somewhere else entirely and is routinely off
     * screen altogether, and paying full price for it is what makes a second character expensive.
     *
     * Conservative on purpose. The test is per-plane -- a cut is dropped only when all four corners
     * fall outside the SAME side of the frustum, never merely when none of them is inside, which is
     * the classic way to make a quad larger than the screen vanish. The corners are pushed outward by
     * a whole cut and the height range is generous, so ground rising into view at the edge is kept
     * rather than popped. Anything behind the camera has no meaningful projection at all, so a cut is
     * dropped for that only when every corner is behind it. */
    private boolean cutvisible(Coord anc) {
	Coord2d csz = new Coord2d(MCache.cutsz).mul(tilesz);
	Coord2d ul = new Coord2d(anc.mul(MCache.cutsz)).mul(tilesz).sub(csz);
	return(boxvisible(ul, ul.add(csz.mul(3))));
    }

    /** rts: the same test for a single object -- a couple of tiles across, tall enough for a tree. */
    private boolean gobvisible(Coord2d rc) {
	Coord2d m = tilesz.mul(3);
	return(boxvisible(rc.sub(m), rc.add(m)));
    }

    private boolean boxvisible(Coord2d ul, Coord2d br) {
	float zlo = -50, zhi = 100;
	try {
	    Coord3f cc = getcc();
	    zlo = cc.z - 100;
	    zhi = cc.z + 100;
	} catch(Loading e) {
	}
	boolean left = true, right = true, down = true, up = true, behind = true;
	for(int i = 0; i < 8; i++) {
	    double x = ((i & 1) == 0) ? ul.x : br.x;
	    double y = ((i & 2) == 0) ? ul.y : br.y;
	    float z = ((i & 4) == 0) ? zlo : zhi;
	    HomoCoord4f h = clipxf(new Coord3f((float)x, (float)y, z), false);
	    if(h.w > 0)
		behind = false;
	    if(!(h.x < -h.w)) left = false;
	    if(!(h.x >  h.w)) right = false;
	    if(!(h.y < -h.w)) down = false;
	    if(!(h.y >  h.w)) up = false;
	}
	return(!(behind || left || right || up || down));
    }

    /** rts: (F7) the cut rectangle around a member's character, in that member's own tile coords. */
    private Area sessionarea(Glob mglob, long mplgob, MapRaster raster) {
	try {
	    Gob pl = mglob.oc.getgob(mplgob);
	    if(pl == null)
		return(null);
	    Coord cc = new Coord2d(pl.getc()).floor(tilesz).div(MCache.cutsz);
	    raster.lastload = null;
	    /* One ring wider than the anchor draws of itself. The member already REQUESTS this much
	     * ground (the dormant branch of tick(), below), so the cuts are in its cache either way and
	     * this only meshes them -- and it is the cheapest cut of the gap between the two patches
	     * there is. Anything beyond it is ground nobody has asked the server for, and no rendering
	     * setting can draw ground the client does not have. */
	    int r = view + 1;
	    return(new Area(cc.sub(r, r), cc.add(r, r).add(1, 1)));
	} catch(Loading l) {
	    l.boostprio(5);
	    raster.lastload = l;
	    return(null);
	}
    }

    /** rts: (F7) one member's ground and objects, hanging in this scene under F1's offset. */
    private class SessionView {
	final Glob mglob;
	final long mplgob;
	final Coord2d off;
	final SessionTerrain fterrain;
	final SessionGobs fgobs;
	final SessionClickMap fclick;
	final RenderTree.Slot slot, clslot;
	final Coord cutoff;
	/* Which cuts THIS view is the one to draw, in its own cut coords. Recomputed each sessiontick and
	 * consulted by both of its rasters, so the terrain, the flavour layer and the click geometry
	 * always agree about what belongs to whom. */
	final Set<Coord> mine = new HashSet<>();

	SessionView(Glob mglob, long mplgob, Coord2d off) {
	    this.mglob = mglob;
	    this.mplgob = mplgob;
	    this.off = off;
	    Coord toff = Coord.of((int)Math.round(off.x / tilesz.x), (int)Math.round(off.y / tilesz.y));
	    this.cutoff = toff.div(MCache.cutsz);
	    this.fterrain = new SessionTerrain(mglob, mplgob, cutoff);
	    this.fgobs = new SessionGobs(mglob, off);
	    this.fclick = new SessionClickMap(mglob, mplgob, cutoff);
	    fterrain.fv = this;
	    fclick.fv = this;
	    fgobs.fv = this;
	    /* The scene negates y, so a world offset (ox, oy) is a scene offset (ox, -oy) -- and we are
	     * subtracting it, hence (-ox, +oy). One translation for the whole member: everything below
	     * it can go on speaking its own session's coordinates, which is exactly what it does.
	     *
	     * lockstate() is not optional. A slot whose state is locked -- and the render tree locks
	     * plenty of them: every map cut's click geometry, every composited gob -- may not depend on
	     * an ancestor whose state could still change, and it throws rather than risk a stale bake.
	     * Nothing above these two ever defined a Location before, so nothing was ever that ancestor;
	     * introducing one is what makes the declaration necessary. It is also simply true: the offset
	     * is fixed for this member, and a SessionView is rebuilt whole if it ever changes. */
	    /* ShadowMap.maskshadow keeps this whole patch out of the shadow pass, which is a second full
	     * render of every triangle in it. The shadow map is a 750-unit box around the ANCHOR's
	     * character, so a patch far enough away to need merging at all contributes nothing to it and
	     * is being rasterized for no pixels; and when the two characters are close enough for it to
	     * matter, the anchor has claimed that ground and this patch is drawing almost none of it. It
	     * still receives shadows -- only casting into the map is given up. */
	    Pipe.Op xl = Pipe.Op.compose(Location.xlate(new Coord3f((float)-off.x, (float)off.y, 0)),
					 ShadowMap.maskshadow);
	    this.slot = basic.add((RenderTree.Node)null, xl);
	    slot.lockstate();
	    slot.add(fterrain);
	    slot.add(fgobs);
	    this.clslot = clmaptree.add((RenderTree.Node)null, xl);
	    clslot.lockstate();
	    clslot.add(fclick);
	}

	void tick(Set<Coord> claimed) {
	    mine.clear();
	    Area a = sessionarea(mglob, mplgob, fterrain);
	    if(a != null) {
		for(Coord cc : a) {
		    Coord anc = cc.sub(cutoff);
		    /* First claim wins, and the anchor's own ground is claimed before any of this runs.
		     * Two sessions standing together see the SAME ground and the same objects; drawing
		     * it once per session is not a merge, it is the same picture stacked on itself --
		     * z-fighting, doubled trees and twice the geometry for no pixels gained. */
		    if(!claimed.add(anc))
			continue;
		    if(!cutvisible(anc))
			continue;
		    mine.add(cc);
		}
	    }
	    fterrain.tick();
	    fgobs.tick();
	    fclick.tick();
	}

	/* rts: a rendered gob needs gtick() EVERY frame -- that is what uploads its animated pose, and
	 * a Drawable whose pose is never refreshed is drawn from whatever happened to be in its buffers,
	 * which is why merged trees jittered in position and height. Glob.gtick was deliberately not
	 * called for member sessions in F0, and that was right then: nothing of theirs was in a render
	 * tree. The merged view made it wrong and nothing said so.
	 *
	 * Only the gobs this view actually put in the scene, not the member's whole OCache -- the culled
	 * ones have no pose to upload and iterating them all would undo the culling's saving. */
	void gtick(Render out) {
	    List<Gob> obs;
	    synchronized(fgobs) {
		obs = new ArrayList<>(fgobs.current.keySet());
	    }
	    for(Gob ob : obs) {
		try {
		    synchronized(ob) {
			ob.gtick(out);
		    }
		} catch(RuntimeException e) {
		    /* one gob's failure is not the frame's */
		}
	    }
	}

	void remove() {
	    slot.remove();
	    clslot.remove();
	}
    }

    private final Map<String, SessionView> sessionviews = new LinkedHashMap<>();
    /* An immutable snapshot of sessionviews in a stable order, safe to walk from a Loader thread. */
    private volatile SessionView[] fvorder = new SessionView[0];
    private double lastreq = 0, lastsession = 0;

    /* 068: the remembered ground's source -- an MCache of its own, filled out of the map database.
     * Built on the first tick that has a minimap to take sessloc from; RecallTerrain below is what
     * draws it, and :recall is what reports it. */
    private io.brodgar.session.Recall recall = null;
    private RecallTerrain recallterrain = null;
    private RenderTree.Slot s_recall = null;
    private boolean recallon = true;
    private double lastrecall = 0;

    /* 068.3: the wash that tells remembered ground from live ground.
     *
     * It is the COLOUR that is taken out, and that is not something a sheet laid over the ground can do.
     * Alpha blending interpolates toward one colour: a translucent white sheet moves every fragment the
     * same fraction toward white, so the ground goes pale and stays green, brown and blue -- which is a
     * haze over live-looking ground, not a memory of it. Desaturating is an operation on the fragment's own
     * three channels against each other, and the fragment shader is the only place that can be written.
     *
     * io.brodgar.session.Greyscale is that state, and the reason it reaches ground whose colour comes from a
     * tileset's own Material is that a program here is compiled from the COMPOSED Pipe of the slot being
     * drawn -- so a State installed once at this subtree's root is compiled into every material under it.
     * BaseColor and ColorMask work exactly this way.
     *
     * It costs one shader program, compiled on the first frame that needs it: no second mesh, no second
     * draw, no second pass. The amount is a uniform rather than a compiled-in constant, so `:recall wash`
     * pushes a new instance through Slot.ostate and nothing is rebuilt at all -- not the program, not the
     * slot tree, not a single cut. */
    private int washamt = 255;

    private Pipe.Op greyscale() {
	return(new io.brodgar.session.Greyscale(washamt / 255f));
    }

    private void setwash(int a) {
	if(a == washamt)
	    return;
	washamt = a;
	if(s_recall != null)
	    s_recall.ostate(greyscale());
    }

    /* 068.2: the remembered ground, in the scene.
     *
     * The client's own Terrain centres its area on getcc() -- the player's own cut -- so a camera panned
     * off the character arrives at a void, and widening THAT raster is not the fix: every cut outside the
     * streamed set calls MCache.getgrid, which asks the server for ground the character is nowhere near.
     * This is the same machinery pointed at the source filled from the map database instead, which has
     * that ground already and no wire to ask down.
     *
     * Three things make it a second raster rather than a second copy of the first.
     *
     * The area is centred on where the CAMERA is looking, and it is bounded by what the source has
     * actually read: Recall fills a square of grids around that same point, and this draws all of it but
     * the outermost ring. That ring is the fill margin MapMesh.dotrans needs -- the transition pass reads
     * one tile across the cut edge, so a cut with no neighbour grid throws LoadingMap and never completes.
     * Drawing to the edge of the fill would leave a permanent ring of Loading cuts, each of them putting
     * its absent neighbour into a request queue nothing may ever send.
     *
     * It yields every cut the live Terrain claims. The two sources hold the SAME ground wherever they
     * overlap -- same tiles, same heights, straight off the same server -- so drawing both is not a merge
     * but one mesh laid on its twin, which is z-fighting. The live one wins, exactly as the anchor wins
     * over a member's patch, and the join has no step in it because the height either side came from
     * the same record.
     *
     * It culls unconditionally rather than on the cullterrain option. That setting exists because ground
     * culled around the player stops casting into the shadow map, which is a trade the player makes for
     * themselves; this ground is masked out of the shadow pass at its slot regardless (the shadow box is
     * 750 units around the character and this is by definition somewhere else), so the one reason to keep
     * an invisible cut cannot apply to it. */
    /* 068.4: the cut cap, and it is a stated number rather than one derived from the frustum.
     *
     * The camera can frame more ground than this client was ever built to draw: the read square is 5x5
     * grids and a grid is 4x4 cuts, so a wide view can want 400 cuts where the live raster around the
     * player keeps 25. A cut is not a cheap thing to want -- two passes over 625 tiles plus dotrans's
     * eight neighbour reads each, then a slot compile and a VBO upload -- so the cuts beyond the cap are
     * simply not drawn, and because the set is filled nearest to where the camera is looking first, what
     * is dropped is always the farthest ground on screen. */
    private static final int recallcutcap = 160;
    /* And a limit on how many of those may be STARTED in one tick. The mesh build is the whole cost of
     * this feature and it arrives as a burst -- a pan into unread ground wants a hundred cuts at once,
     * on the Defer threads every other loading thing in the client shares. A cut not started this tick
     * is started next one; at five ticks a second the cap fills in a few seconds of continuous panning,
     * and a cut still building is not in `cuts` yet, so this bounds what is in flight and not merely
     * what is begun. */
    private static final int recallmaxbuild = 6;

    private class RecallTerrain extends MapRaster {
	/* Which cuts this raster is to hold, computed once per tick: visible, not the live raster's, read
	 * back from the record, and within both budgets. Nearest-first, so a pan grows the drawn ground
	 * outward from where the camera is looking rather than in grid order. */
	final Set<Coord> draw = new HashSet<>();
	Coord2d center = null;
	int nwanted = 0;

	final Grid main = new Grid<MapMesh>() {
		MapMesh getcut(Coord cc) {
		    return(map.getcut(cc));
		}
	    };
	RecallTerrain(MCache map) {
	    super(map);
	}

	void tick() {
	    Coord2d c = this.center;
	    if(c == null) {
		area = null;
		draw.clear();
		nwanted = 0;
		return;
	    }
	    /* In grids rather than in cuts, because grids are the unit the source reads and the margin is
	     * a grid wide. cutn is cmaps/cutsz, so a grid coord scales to the cut coord of its corner. */
	    Coord gc = c.floor(tilesz).div(MCache.cmaps);
	    int r = io.brodgar.session.Recall.radius - 1;
	    area = new Area(gc.sub(r, r).mul(MCache.cutn), gc.add(r + 1, r + 1).mul(MCache.cutn));
	    List<Coord> cand = new ArrayList<>();
	    Area own = terrain.area;
	    for(Coord cc : area) {
		/* The live raster's ground wins every cut the two share: both sources hold the same
		 * tiles and the same heights there, so a second mesh is not a merge but a twin, and two
		 * twins in one place is z-fighting. */
		if((own != null) && own.contains(cc))
		    continue;
		/* Ask the cache what it HOLDS rather than let getcut ask for it. MCache.getcut ends in
		 * getgrid, which on a miss queues a request -- harmless on a source nothing sends for, but
		 * it fills that queue with every unrecorded grid in the area and buries the one number
		 * :recall exists to report. Ground the character has never walked is simply not drawn. */
		if(AddonWidgets.loadedGrid(map, cc.div(MCache.cutn)) == null)
		    continue;
		if(!cutvisible(cc))
		    continue;
		cand.add(cc);
	    }
	    nwanted = cand.size();
	    final Coord cen = c.floor(tilesz).div(MCache.cutsz);
	    Collections.sort(cand, new Comparator<Coord>() {
		    public int compare(Coord a, Coord b) {
			return(Long.compare(dist2(a, cen), dist2(b, cen)));
		    }
		});
	    draw.clear();
	    int building = 0;
	    for(Coord cc : cand) {
		if(draw.size() >= recallcutcap)
		    break;
		if(!main.cuts.containsKey(cc)) {
		    /* Not `break`: a cut already built and still in view is kept whatever this tick's
		     * build budget is, because keeping it costs nothing and dropping it would only have
		     * it rebuilt. What the budget bounds is starting new ones. */
		    if(building >= recallmaxbuild)
			continue;
		    building++;
		}
		draw.add(cc);
	    }
	    main.tick();
	}

	private long dist2(Coord a, Coord b) {
	    long dx = a.x - b.x, dy = a.y - b.y;
	    return((dx * dx) + (dy * dy));
	}

	/* Everything the budget left out leaves the scene here, and so does everything that left the
	 * view: Grid.tick removes the slot of every cut this refuses, and of every cut outside `area`. */
	boolean skipcut(Coord cc) {
	    return(!draw.contains(cc));
	}

	public void added(RenderTree.Slot slot) {
	    slot.add(main);
	    super.added(slot);
	}
    }

    private void droprecall() {
	if(s_recall != null) {
	    s_recall.remove();
	    s_recall = null;
	}
    }

    private void recalltick() {
	GameUI gui = getparent(GameUI.class);
	MiniMap mm = (gui == null) ? null : gui.mmap;
	if(mm == null) {
	    droprecall();
	    return;
	}
	if(recall == null)
	    recall = new io.brodgar.session.Recall(glob.sess);
	/* Where the ground is read around. The RTS camera is the one that leaves the character, and it
	 * is the reason the record is read at all; every other camera is bolted to the player, where
	 * getcc() says the same thing. */
	boolean rts = camera instanceof RTSCam;
	Coord2d c = null;
	if(rts)
	    c = ((RTSCam)camera).center();
	if(c == null) {
	    try {
		c = new Coord2d(getcc());
	    } catch(Loading e) {
		/* No player yet, or no ground under them: nothing to centre a read on. */
	    }
	}
	recall.tick(mm, c);
	/* The raster goes in with the RTS camera and comes out with it. Every other camera is bolted to
	 * the character, where the live Terrain already draws everything in view and this would have
	 * nothing to add but a second mesh over the first -- and with the raster out of the tree its
	 * grids stop being meshed at all, which is the whole of what it costs.
	 *
	 * It also comes out whenever the source cannot vouch for where its ground goes. Walking into a
	 * house or a cave re-bases the session coordinate space while sessloc still names the segment just
	 * left, and everything read through that offset is now ground drawn somewhere it never was --
	 * which is worse than no ground at all. It returns of its own accord once a sweep has proved the
	 * new base, and in the right place. */
	if(!recallon || !rts || (c == null) || !recall.ready()) {
	    droprecall();
	    /* In this order and not the other: release() disposes every cut mesh the source holds, and a
	     * raster still in the tree holding one goes on drawing it. Out of the scene, then disposed. */
	    recall.release();
	    return;
	}
	if(recallterrain == null)
	    recallterrain = new RecallTerrain(recall.map);
	if(s_recall == null) {
	    /* ShadowMap.ShadowList.add mirrors every lit slot into the shadow pass and skips only one
	     * carrying maskshadow, so without this every recalled cut is rasterized twice -- for a shadow
	     * box of 750 units around the player that this ground is, by construction, outside of. */
	    /* No lockstate() here, unlike a SessionView's slot: what locks a descendant is a Composited gob
	     * or a click-map cut, and this subtree holds neither -- only MapMesh cuts, exactly as the
	     * plain Terrain slot beside it does. Locking a slot whose state has already been used throws,
	     * and adding the node is what uses it. */
	    s_recall = basic.add(recallterrain, ShadowMap.maskshadow);
	    /* The wash. On the slot rather than in the raster, because it is one state over the whole
	     * subtree and because ostate is what lets `:recall wash` change it without touching anything
	     * the raster built. */
	    s_recall.ostate(greyscale());
	}
	/* The cut set changes at panning pace, and maintaining it is a frustum test per cut of a square
	 * far larger than the one the live raster keeps. Five times a second delays a cut entering the
	 * scene by rather less than building its mesh does. */
	double now = Utils.rtime();
	if((now - lastrecall) < 0.2)
	    return;
	lastrecall = now;
	recallterrain.center = c;
	recallterrain.tick();
    }

    /* rts: every frame, unlike sessiontick() -- an animated pose that is 200ms stale is a visible jump. */
    public void gtick(Render out) {
	super.gtick(out);
	if(dormant || sessionviews.isEmpty())
	    return;
	for(SessionView fv : sessionviews.values())
	    fv.gtick(out);
    }

    private void sessiontick() {
	/* The cut set under a member changes when it walks across a cut boundary, which at running pace
	 * is a few times a second at most -- and maintaining it means a getcut() and a map lookup for
	 * every cut of a 7x7 patch, three times over (terrain, flavour objects, click geometry), per
	 * member, per frame. Five times a second delays a new cut appearing by 200ms and nothing else. */
	double now = Utils.rtime();
	if((now - lastsession) < 0.2)
	    return;
	lastsession = now;
	sessiontick2();
    }

    private void sessiontick2() {
	List<io.brodgar.session.Sessions.View> vs = io.brodgar.session.Sessions.views();
	if(vs.isEmpty() && sessionviews.isEmpty())
	    return;
	/* The anchor's own terrain claims its ground before anyone else is asked, so a member never
	 * draws over the session that is actually on screen. */
	Set<Coord> claimed = new HashSet<>();
	Area own = terrain.area;
	if(own != null) {
	    for(Coord cc : own)
		claimed.add(cc);
	}
	Set<String> live = new HashSet<>();
	for(io.brodgar.session.Sessions.View v : vs) {
	    live.add(v.user);
	    SessionView fv = sessionviews.get(v.user);
	    /* A relog gives the member a new Glob and a new frame: the old node is not adjustable, it
	     * is wrong. Drop it and build again. */
	    if((fv != null) && ((fv.mglob != v.glob) || !fv.off.equals(v.offset))) {
		fv.remove();
		sessionviews.remove(v.user);
		fv = null;
	    }
	    if(fv == null)
		sessionviews.put(v.user, fv = new SessionView(v.glob, v.plgob, v.offset));
	    fv.tick(claimed);
	}
	for(Iterator<Map.Entry<String, SessionView>> i = sessionviews.entrySet().iterator(); i.hasNext();) {
	    Map.Entry<String, SessionView> e = i.next();
	    if(!live.contains(e.getKey())) {
		e.getValue().remove();
		i.remove();
	    }
	}
	fvorder = sessionviews.values().toArray(new SessionView[0]);
    }

    public class Overlay extends MapRaster {
	final OverlayInfo id;
	int rc = 0;
	boolean used;

	final Grid base = new Grid<RenderTree.Node>() {
		RenderTree.Node getcut(Coord cc) {
		    return(map.getolcut(id, cc));
		}
	    };
	final Grid outl = new Grid<RenderTree.Node>() {
		RenderTree.Node getcut(Coord cc) {
		    return(map.getololcut(id, cc));
		}
	    };

	private Overlay(OverlayInfo id) {
	    this.id = id;
	}

	void tick() {
	    super.tick();
	    if(area != null) {
		base.tick();
		outl.tick();
	    }
	}

	public void added(RenderTree.Slot slot) {
	    slot.add(base, id.mat());
	    Material omat = id.omat();
	    if(omat != null)
		slot.add(outl, omat);
	    super.added(slot);
	}

	public Loading loading() {
	    Loading ret = super.loading();
	    if(ret != null)
		return(ret);
	    if((ret = base.lastload) != null)
		return(ret);
	    return(null);
	}

	public void remove() {
	    slot.remove();
	}
    }

    private final Map<String, Integer> oltags = new HashMap<>();
    private final Map<OverlayInfo, Overlay> ols = new HashMap<>();
    {oltags.put("show", 1);}
    private void oltick() {
	try {
	    for(Overlay ol : ols.values())
		ol.used = false;
	    if(terrain.area != null) {
		for(OverlayInfo id : glob.map.getols(terrain.area.mul(MCache.cutsz))) {
		    boolean vis = false;
		    synchronized(oltags) {
			for(String tag : id.tags()) {
			    if(oltags.containsKey(tag)) {
				vis = true;
				break;
			    }
			}
		    }
		    if(vis) {
			Overlay ol = ols.get(id);
			if(ol == null) {
			    try {
				basic.add(ol = new Overlay(id));
				ols.put(id, ol);
			    } catch(Loading l) {
				l.boostprio(2);
				continue;
			    }
			}
			ol.used = true;
		    }
		}
	    }
	    for(Iterator<Overlay> i = ols.values().iterator(); i.hasNext();) {
		Overlay ol = i.next();
		if(!ol.used) {
		    ol.remove();
		    i.remove();
		}
	    }
	} catch(Loading l) {
	    l.boostprio(2);
	}
	for(Overlay ol : ols.values())
	    ol.tick();
    }

    private static final Material gridmat = new Material(new BaseColor(255, 255, 255, 48), States.maskdepth, new MapMesh.OLOrder(null),
							 Location.xlate(new Coord3f(0, 0, 0.5f))   /* Apparently, there is no depth bias for lines. :P */
							 );
    private class GridLines extends MapRaster {
	final Grid grid = new Grid<RenderTree.Node>() {
		RenderTree.Node getcut(Coord cc) {
		    return(map.getcut(cc).grid());
		}
	    };

	private GridLines() {}

	void tick() {
	    super.tick();
	    if(area != null)
		grid.tick();
	}

	public void added(RenderTree.Slot slot) {
	    slot.ostate(gridmat);
	    slot.add(grid);
	    super.added(slot);
	}

	public void remove() {
	    slot.remove();
	}
    }

    GridLines gridlines = null;
    public void showgrid(boolean show) {
	if((gridlines == null) && show) {
	    basic.add(gridlines = new GridLines());
	} else if((gridlines != null) && !show) {
	    gridlines.remove();
	    gridlines = null;
	}
    }

    static class MapClick extends Clickable {
	final MapMesh cut;

	MapClick(MapMesh cut) {
	    this.cut = cut;
	}

	public String toString() {
	    return(String.format("#<mapclick %s>", cut));
	}
    }

    private final ClickMap clickmap;
    private class ClickMap extends MapRaster {
	ClickMap() {super();}
	ClickMap(MCache map) {super(map);}   // rts: (F7)

	final Grid grid = new Grid<MapMesh>() {
		MapMesh getcut(Coord cc) {
		    return(map.getcut(cc));
		}
		RenderTree.Node produce(MapMesh cut) {
		    return(new MapClick(cut).apply(cut.flat));
		}
	    };

	void tick() {
	    super.tick();
	    if(area != null) {
		grid.tick();
	    }
	}

	public void added(RenderTree.Slot slot) {
	    slot.add(grid);
	    super.added(slot);
	}

	public Loading loading() {
	    Loading ret = super.loading();
	    if(ret != null)
		return(ret);
	    if((ret = grid.lastload) != null)
		return(ret);
	    return(null);
	}
    }

    public String camstats() {
	String cc;
	try {
	    Coord3f c = getcc();
	    cc = String.format("(%.1f %.1f %.1f)", c.x / tilesz.x, c.y / tilesz.y, c.z / tilesz.x);
	} catch(Loading l) {
	    cc = "<nil>";
	}
	return(String.format("C: %s, Cam: %s", cc, camera.stats()));
    }

    public String stats() {
	String ret = String.format("Tree %s", tree.stats());
	if(back != null)
	    ret = String.format("%s, Inst %s, Draw %s", ret, instancer.stats(), back.stats());
	return(ret);
    }

    private Coord3f smapcc = null;
    private ShadowMap.ShadowList slist = null;
    private ShadowMap smap = null;
    private double lsmch = 0;
    private void updsmap(DirLight light) {
	boolean usesdw = ui.gprefs.lshadow.val;
	int sdwres = ui.gprefs.shadowres.val;
	sdwres = (sdwres < 0) ? (2048 >> -sdwres) : (2048 << sdwres);
	if(usesdw) {
	    Coord3f dir, cc;
	    try {
		dir = new Coord3f(-light.dir[0], -light.dir[1], -light.dir[2]);
		cc = getcc().invy();
	    } catch(Loading l) {
		return;
	    }
	    if(smap == null) {
		if(instancer == null)
		    return;
		slist = new ShadowMap.ShadowList(instancer);
		smap = new ShadowMap(new Coord(sdwres, sdwres), 750, 5000, 1);
	    } else if(smap.lbuf.w != sdwres) {
		smap.dispose();
		smap = new ShadowMap(new Coord(sdwres, sdwres), 750, 5000, 1);
		smapcc = null;
		basic(ShadowMap.class, null);
	    }
	    smap = smap.light(light);
	    boolean ch = false;
	    double now = Utils.rtime();
	    if((smapcc == null) || (smapcc.dist(cc) > 50)) {
		smapcc = cc;
		ch = true;
	    } else {
		if(now - lsmch > 0.1)
		    ch = true;
	    }
	    if(ch || !smap.haspos()) {
		smap = smap.setpos(smapcc.add(dir.neg().mul(1000f)), dir);
		lsmch = now;
	    }
	    basic(ShadowMap.class, smap);
	} else {
	    if(smap != null) {
		instancer.remove(slist);
		smap.dispose(); smap = null;
		slist.dispose(); slist = null;
		basic(ShadowMap.class, null);
	    }
	    smapcc = null;
	}
    }

    private void drawsmap(Render out) {
	// addon: the "shadow" named pass (spec 019, task 019.6) -- smap.update is the ENTIRE shadow render in
	// one call, which is what makes "what do shadows cost me" answerable as a number. With shadows off
	// there is no shadow map and the pass is never opened, so its row falls to zero along with the GPU
	// frame time -- the headline check of this task.
	if(smap != null) {
	    io.brodgar.prof.Passes.begin(out, io.brodgar.prof.Passes.SHADOW);
	    try {
		smap.update(out, slist);
	    } finally {
		io.brodgar.prof.Passes.end(out, io.brodgar.prof.Passes.SHADOW);
	    }
	}
    }

    public DirLight amblight = null;
    private RenderTree.Slot s_amblight = null;
    private void amblight() {
	synchronized(glob) {
	    if(glob.lightamb != null) {
		amblight = new DirLight(glob.lightamb, glob.lightdif, glob.lightspc, Coord3f.o.sadd((float)glob.lightelev, (float)glob.lightang, 1f));
		amblight.prio(100);
	    } else {
		amblight = null;
	    }
	}
	if(s_amblight != null) {
	    s_amblight.remove();
	    s_amblight = null;
	}
	if(amblight != null)
	    s_amblight = basic.add(amblight);
    }

    public static class LightCompiler {
	public final GSettings gprefs;
	private final Lighting.LightGrid zgrid;
	private final int maxlights;

	public LightCompiler(GSettings gprefs) {
	    this.gprefs = gprefs;
	    if(gprefs == null) {
		zgrid = null;
		maxlights = 0;
	    } else {
		maxlights = gprefs.maxlights.val;
		if(gprefs.lightmode.val == GSettings.LightMode.ZONED) {
		    zgrid = new Lighting.LightGrid(64, 64, 64);
		    if(maxlights != 0)
			zgrid.maxlights = maxlights;
		} else {
		    zgrid = null;
		}
	    }
	}

	public boolean valid(GSettings prefs) {
	    return((prefs == gprefs) ||
		   (((prefs == null) == (gprefs == null)) &&
		    (prefs.lightmode.val == gprefs.lightmode.val) &&
		    (prefs.maxlights.val == gprefs.maxlights.val)));
	}

	public Pipe.Op compile(Object[][] params, Projection proj) {
	    if(zgrid == null) {
		Lighting.SimpleLights ret = new Lighting.SimpleLights(params);
		if(maxlights != 0)
		    ret.maxlights = maxlights;
		return(ret);
	    } else {
		return(zgrid.compile(params, proj));
	    }
	}
    }

    private LightCompiler lighting;
    protected void lights() {
	GSettings gprefs = basic.state().get(GSettings.slot);
	if((lighting == null) || !lighting.valid(gprefs)) {
	    basic(Light.class, null);
	    lighting = new LightCompiler(gprefs);
	}
	Projection proj = (camera == null) ? new Projection(Matrix4f.id) : camera.proj;
	basic(Light.class, Pipe.Op.compose(lights, lighting.compile(lights.params(), proj)));
    }

    public static final Uniform amblight_idx = new Uniform(Type.INT, p -> {
	    DirLight light = ((MapView)((WidgetContext)p.get(RenderContext.slot)).widget()).amblight;
	    Light.LightList lights = p.get(Light.lights);
	    int idx = -1;
	    if(light != null)
		idx = lights.index(light);
	    return(idx);
	}, RenderContext.slot, Light.lights);

    private final Map<RenderTree.Node, RenderTree.Slot> rweather = new HashMap<>();
    private void updweather() {
	Glob.Weather[] wls = glob.weather().toArray(new Glob.Weather[0]);
	Pipe.Op[] wst = new Pipe.Op[wls.length];
	for(int i = 0; i < wls.length; i++)
	    wst[i] = wls[i].state();
	try {
	    basic(Glob.Weather.class, Pipe.Op.compose(wst));
	} catch(Loading l) {
	}
	Collection<RenderTree.Node> old =new ArrayList<>(rweather.keySet());
	for(Glob.Weather w : wls) {
	    if(w instanceof RenderTree.Node) {
		RenderTree.Node n = (RenderTree.Node)w;
		old.remove(n);
		if(rweather.get(n) == null) {
		    try {
			rweather.put(n, basic.add(n));
		    } catch(Loading l) {
		    }
		}
	    }
	}
	for(RenderTree.Node rem : old)
	    rweather.remove(rem).remove();
    }

    public RenderTree.Slot drawadd(RenderTree.Node extra) {
	return(basic.add(extra));
    }

    public Gob player() {
	return((plgob < 0) ? null : glob.oc.getgob(plgob));
    }
    
    public Coord3f getcc() {
	Gob pl = player();
	if(pl != null)
	    return(pl.getc());
	else
	    return(glob.map.getzp(cc));
    }

    public static class Clicklist implements RenderList<Rendered>, RenderList.Adapter {
	public static final Pipe.Op clickbasic = Pipe.Op.compose(new States.Depthtest(States.Depthtest.Test.LE),
								 new States.Facecull(),
								 Homo3D.state);
	private static final int MAXID = 0xffffff;
	private final RenderList.Adapter master;
	private final boolean doinst;
	private final ProxyPipe basic = new ProxyPipe();
	private final Map<Slot<? extends Rendered>, Clickslot> slots = new HashMap<>();
	private final Map<Integer, Clickslot> idmap = new HashMap<>();
	private DefPipe curbasic = null;
	private RenderList<Rendered> back;
	private DrawList draw;
	private InstanceList instancer;
	private int nextid = 1;

	public class Clickslot implements Slot<Rendered> {
	    public final Slot<? extends Rendered> bk;
	    public final int id;
	    final Pipe idp;
	    private GroupPipe state;

	    public Clickslot(Slot<? extends Rendered> bk, int id) {
		this.bk = bk;
		this.id = id;
		this.idp = new SinglePipe<>(FragID.id, new FragID.ID(id));
	    }

	    public Rendered obj() {
		return(bk.obj());
	    }

	    public GroupPipe state() {
		if(state == null)
		    state = new IDState(bk.state());
		return(state);
	    }

	    private class IDState implements GroupPipe {
		static final int idx_bas = 0, idx_idp = 1, idx_back = 2;
		final GroupPipe back;

		IDState(GroupPipe back) {
		    this.back = back;
		}

		public Pipe group(int idx) {
		    switch(idx) {
		    case idx_bas: return(basic);
		    case idx_idp: return(idp);
		    default: return(back.group(idx - idx_back));
		    }
		}

		public int gstate(int id) {
		    if(id == FragID.id.id)
			return(idx_idp);
		    if(State.Slot.byid(id).type == State.Slot.Type.GEOM) {
			int ret = back.gstate(id);
			if(ret >= 0)
			    return(ret + idx_back);
		    }
		    if((id < curbasic.mask.length) && curbasic.mask[id])
			return(idx_bas);
		    return(-1);
		}

		public int nstates() {
		    return(Math.max(Math.max(back.nstates(), curbasic.mask.length), FragID.id.id + 1));
		}
	    }
	}

	public Clicklist(RenderList.Adapter master, boolean doinst) {
	    this.master = master;
	    this.doinst = doinst;
	    asyncadd(this.master, Rendered.class);
	}

	public void add(Slot<? extends Rendered> slot) {
	    if(slot.state().get(Clickable.slot) == null)
		return;
	    int id;
	    while(idmap.get(id = nextid) != null) {
		if(++nextid > MAXID)
		    nextid = 1;
	    }
	    Clickslot ns = new Clickslot(slot, id);
	    if(back != null)
		back.add(ns);
	    if(((slots.put(slot, ns)) != null) || (idmap.put(id, ns) != null))
		throw(new AssertionError());
	}

	public void remove(Slot<? extends Rendered> slot) {
	    Clickslot cs = slots.remove(slot);
	    if(cs != null) {
		if(idmap.remove(cs.id) != cs)
		    throw(new AssertionError());
		if(back != null)
		    back.remove(cs);
	    }
	}

	public void update(Slot<? extends Rendered> slot) {
	    if(back != null) {
		Clickslot cs = slots.get(slot);
		if(cs != null) {
		    cs.state = null;
		    back.update(cs);
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
	    return(slots.values());
	}

	/* Shouldn't have to care. */
	public <R> void add(RenderList<R> list, Class<? extends R> type) {}
	public void remove(RenderList<?> list) {}

	public void basic(Pipe.Op st) {
	    try(Locked lk = lock()) {
		DefPipe buf = new DefPipe();
		buf.prep(st);
		if(curbasic != null) {
		    if(curbasic.maskdiff(buf).length != 0)
			throw(new RuntimeException("changing clickbasic definition mask is not supported"));
		}
		int[] mask = basic.dupdate(buf);
		curbasic = buf;
		if(back != null)
		    back.update(basic, mask);
	    }
	}

	public Coord sz() {
	    return(basic.get(States.viewport).area.sz());
	}

	public void draw(Render out) {
	    if((draw == null) || !out.env().compatible(draw)) {
		if(draw != null)
		    dispose();
		draw = out.env().drawlist().desc("click-list: " + this);
		if(doinst) {
		    instancer = new InstanceList(this);
		    instancer.add(draw, Rendered.class);
		    instancer.asyncadd(this, Rendered.class);
		    back = instancer;
		} else {
		    draw.asyncadd(this, Rendered.class);
		    back = draw;
		}
	    }
	    try(Locked lk = lock()) {
		if(instancer != null)
		    instancer.commit(out);
		draw.draw(out);
	    }
	}

	public void get(Render out, Coord c, Consumer<ClickData> cb) {
	    out.pget(basic, FragID.fragid, Area.sized(Coord.of(c.x, sz().y - c.y), new Coord(1, 1)), new VectorFormat(1, NumberFormat.SINT32), data -> {
		    int id = data.getInt(0);
		    if(id == 0) {
			cb.accept(null);
			return;
		    }
		    Clickslot cs = idmap.get(id);
		    if(cs == null) {
			cb.accept(null);
			return;
		    }
		    cb.accept(new ClickData(cs.bk.state().get(Clickable.slot), (RenderTree.Slot)cs.bk.cast(RenderTree.Node.class)));
		});
	}

	public void fuzzyget(Render out, Coord c, int rad, Consumer<ClickData> cb) {
	    Coord gc = Coord.of(c.x, sz().y - 1 - c.y);
	    Area area = new Area(gc.sub(rad, rad), gc.add(rad + 1, rad + 1)).overlap(Area.sized(Coord.z, this.sz()));
	    out.pget(basic, FragID.fragid, area, new VectorFormat(1, NumberFormat.SINT32), data -> {
		    Clickslot cs;
		    {
			int id = data.getInt(area.ridx(gc) * 4);
			if((id != 0) && ((cs = idmap.get(id)) != null)) {
			    cb.accept(new ClickData(cs.bk.state().get(Clickable.slot), (RenderTree.Slot)cs.bk.cast(RenderTree.Node.class)));
			    return;
			}
		    }
		    int maxr = Integer.MAX_VALUE;
		    Map<Clickslot, Integer> score = new HashMap<>();
		    for(Coord fc : area) {
			int id = data.getInt(area.ridx(fc) * 4);
			if((id == 0) || ((cs = idmap.get(id)) == null))
			    continue;
			int r = (int)Math.round(fc.dist(gc) * 10);
			if(r < maxr) {
			    score.clear();
			    maxr = r;
			} else if(r > maxr) {
			    continue;
			}
			score.put(cs, score.getOrDefault(cs, 0) + 1);
		    }
		    int maxscore = 0;
		    cs = null;
		    for(Map.Entry<Clickslot, Integer> ent : score.entrySet()) {
			if((cs == null) || (ent.getValue() > maxscore)) {
			    maxscore = ent.getValue();
			    cs = ent.getKey();
			}
		    }
		    if(cs == null) {
			cb.accept(null);
			return;
		    }
		    cb.accept(new ClickData(cs.bk.state().get(Clickable.slot), (RenderTree.Slot)cs.bk.cast(RenderTree.Node.class)));
		});
	}

	public void dispose() {
	    if(instancer != null) {
		instancer.dispose();
		instancer = null;
	    }
	    if(draw != null) {
		draw.dispose();
		draw = null;
	    }
	    back = null;
	}

	public String stats() {
	    if(back == null)
		return("");
	    return(String.format("Tree %s, Inst %s, Draw %s, Map %d", master.stats(), (instancer == null) ? null : instancer.stats(), draw.stats(), idmap.size()));
	}
    }

    private final RenderTree clmaptree = new RenderTree();
    private final Clicklist clmaplist = new Clicklist(clmaptree, false);
    private final Clicklist clobjlist = new Clicklist(tree, true);
    private FragID<Texture.Image<Texture2D>> clickid;
    private ClickLocation<Texture.Image<Texture2D>> clickloc;
    private DepthBuffer<Texture.Image<Texture2D>> clickdepth;
    private Pipe.Op curclickbasic;
    private Pipe.Op clickbasic(Coord sz) {
	if((curclickbasic == null) || !clickid.image.tex.sz().equals(sz)) {
	    if(clickid != null) {
		clickid.image.tex.dispose();
		clickloc.image.tex.dispose();
		clickdepth.image.tex.dispose();
	    }
	    clickid = new FragID<>(new Texture2D(sz, DataBuffer.Usage.STATIC, new VectorFormat(1, NumberFormat.SINT32), null).image(0));
	    clickloc = new ClickLocation<>(new Texture2D(sz, DataBuffer.Usage.STATIC, new VectorFormat(2, NumberFormat.UNORM16), null).image(0));
	    clickdepth = new DepthBuffer<>(new Texture2D(sz, DataBuffer.Usage.STATIC, Texture.DEPTH, new VectorFormat(1, NumberFormat.FLOAT32), null).image(0));
	    curclickbasic = Pipe.Op.compose(Clicklist.clickbasic, clickid, clickdepth, new States.Viewport(Area.sized(Coord.z, sz)));
	}
	/* XXX: FrameInfo shouldn't be treated specially. Is a new
	 * Slot.Type in order, perhaps? */
	return(Pipe.Op.compose(curclickbasic, camera, conf.state().get(FrameInfo.slot)));
    }

    private void checkmapclick(Render out, Pipe.Op basic, Coord c, Consumer<Coord2d> cb) {
	new Object() {
	    MapMesh cut;
	    Coord2d pos;

	    {
		clmaplist.basic(Pipe.Op.compose(basic, clickloc));
		clmaplist.draw(out);
		if(clickdb) {
		    GOut.debugimage(out, clmaplist.basic, FragID.fragid, Area.sized(Coord.z, clmaplist.sz()), new VectorFormat(1, NumberFormat.SINT32),
				    img -> Debug.dumpimage(img, Debug.somedir("click1.png")));
		    GOut.debugimage(out, clmaplist.basic, ClickLocation.fragloc, Area.sized(Coord.z, clmaplist.sz()), new VectorFormat(3, NumberFormat.UNORM16),
				    img -> Debug.dumpimage(img, Debug.somedir("click2.png")));
		}
		clmaplist.get(out, c, cd -> {
			if(clickdb)
			    Debug.log.printf("map-id: %s\n", cd);
			if(cd != null)
			    this.cut = ((MapClick)cd.ci).cut;
			ckdone(1);
		    });
		out.pget(clmaplist.basic, ClickLocation.fragloc, Area.sized(Coord.of(c.x, clmaplist.sz().y - c.y), new Coord(1, 1)), new VectorFormat(2, NumberFormat.FLOAT32), data -> {
			pos = new Coord2d(data.getFloat(0), data.getFloat(4));
			if(clickdb)
			    Debug.log.printf("map-pos: %s\n", pos);
			ckdone(2);
		    });
	    }

	    int dfl = 0;
	    void ckdone(int fl) {
		synchronized(this) {
		    if((dfl |= fl) == 3) {
			if(cut == null) {
			    cb.accept(null);
			} else {
			    Coord2d wc = new Coord2d(cut.ul).add(pos.mul(new Coord2d(cut.sz))).mul(tilesz);
			    /* rts: (F7) the coordinate is derived from the CUT, so it comes out in the frame
			     * of whichever session's map that cut belongs to -- the scene translation above
			     * it does not touch this arithmetic at all. A cut from a merged member view is
			     * brought back into the anchor's frame here, once, so that everything downstream
			     * (an order, a move, a placement) goes on speaking one coordinate system. */
			    Coord2d off = io.brodgar.session.Sessions.offsetfor(cut.map);
			    if(off != null)
				wc = wc.sub(off);
			    cb.accept(wc);
			}
		    }
		}
	    }
	};
    }
    
    private static int gobclfuzz = 3;
    private void checkgobclick(Render out, Pipe.Op basic, Coord c, Consumer<ClickData> cb) {
	clobjlist.basic(basic);
	clobjlist.draw(out);
	if(clickdb) {
	    GOut.debugimage(out, clobjlist.basic, FragID.fragid, Area.sized(Coord.z, clobjlist.sz()), new VectorFormat(1, NumberFormat.SINT32),
			  img -> Debug.dumpimage(img, Debug.somedir("click3.png")));
	    Consumer<ClickData> ocb = cb;
	    cb = cl -> {
		Debug.log.printf("obj-id: %s\n", cl);
		ocb.accept(cl);
	    };
	}
	clobjlist.fuzzyget(out, c, gobclfuzz, cb);
    }
    
    public void delay(Delayed d) {
	synchronized(delayed) {
	    delayed.add(d);
	}
    }

    public void delay2(Delayed d) {
	synchronized(delayed2) {
	    delayed2.add(d);
	}
    }

    protected void undelay(Collection<Delayed> list, GOut g) {
	synchronized(list) {
	    for(Delayed d : list)
		d.run(g);
	    list.clear();
	}
    }

    static class PolText {
	Text text; double tm;
	PolText(Text text, double tm) {this.text = text; this.tm = tm;}
    }

    private static final Text.Furnace polownertf = new PUtils.BlurFurn(new Text.Foundry(Text.serif, 30).aa(true), 3, 1, Color.BLACK);
    private final Map<Integer, PolText> polowners = new HashMap<Integer, PolText>();

    public void setpoltext(int id, String text) {
	synchronized(polowners) {
	    polowners.put(id, new PolText(polownertf.render(text), Utils.rtime()));
	}
    }

    private void poldraw(GOut g) {
	if(polowners.isEmpty())
	    return;
	double now = Utils.rtime();
	synchronized(polowners) {
	    int y = (sz.y / 3) - (polowners.values().stream().map(t -> t.text.sz().y).reduce(0, (a, b) -> a + b + 10) / 2);
	    for(Iterator<PolText> i = polowners.values().iterator(); i.hasNext();) {
		PolText t = i.next();
		double poldt = now - t.tm;
		if(poldt < 6.0) {
		    int a;
		    if(poldt < 1.0)
			a = (int)(255 * poldt);
		    else if(poldt < 4.0)
			a = 255;
		    else
			a = (int)((255 * (2.0 - (poldt - 4.0))) / 2.0);
		    g.chcolor(255, 255, 255, a);
		    g.aimage(t.text.tex(), new Coord((sz.x - t.text.sz().x) / 2, y), 0.0, 0.0);
		    y += t.text.sz().y + 10;
		    g.chcolor();
		} else {
		    i.remove();
		}
	    }
	}
    }
    
    private void drawarrow(GOut g, double a) {
	Coord hsz = sz.div(2);
	double ca = -Coord.z.angle(hsz);
	Coord ac;
	if((a > ca) && (a < -ca)) {
	    ac = new Coord(sz.x, hsz.y - (int)(Math.tan(a) * hsz.x));
	} else if((a > -ca) && (a < Math.PI + ca)) {
	    ac = new Coord(hsz.x - (int)(Math.tan(a - Math.PI / 2) * hsz.y), 0);
	} else if((a > -Math.PI - ca) && (a < ca)) {
	    ac = new Coord(hsz.x + (int)(Math.tan(a + Math.PI / 2) * hsz.y), sz.y);
	} else {
	    ac = new Coord(0, hsz.y + (int)(Math.tan(a) * hsz.x));
	}
	Coord bc = ac.add(Coord.sc(a, -10));
	g.line(bc, bc.add(Coord.sc(a, -40)), 2);
	g.line(bc, bc.add(Coord.sc(a + Math.PI / 4, -10)), 2);
	g.line(bc, bc.add(Coord.sc(a - Math.PI / 4, -10)), 2);
    }

    public HomoCoord4f clipxf(Coord3f mc, boolean doclip) {
	HomoCoord4f ret = Homo3D.obj2clip(new Coord3f(mc.x, -mc.y, mc.z), basic.state());
	if(doclip && ret.clipped(HomoCoord4f.AX | HomoCoord4f.AY | HomoCoord4f.PZ)) {
	    Projection s_prj = basic.state().get(Homo3D.prj);
	    Matrix4f prj = (s_prj == null) ? Matrix4f.id : s_prj.fin(Matrix4f.id);
	    ret = HomoCoord4f.lineclip(HomoCoord4f.fromclip(prj, Coord3f.o), ret, HomoCoord4f.AX | HomoCoord4f.AY | HomoCoord4f.PZ);
	}
	return(ret);
    }

    public Coord3f screenxf(Coord3f mc) {
	return(clipxf(mc, false).toview(Area.sized(this.sz)));
    }

    public Coord3f screenxf(Coord2d mc) {
	Coord3f cc;
	try {
	    cc = getcc();
	} catch(Loading e) {
	    return(null);
	}
	return(screenxf(new Coord3f((float)mc.x, (float)mc.y, cc.z)));
    }

    public double screenangle(Coord2d mc, boolean clip) {
	Coord3f cc;
	try {
	    cc = getcc();
	} catch(Loading e) {
	    return(Double.NaN);
	}
	Coord3f mloc = new Coord3f((float)mc.x, -(float)mc.y, cc.z);
	float[] sloc = camera.proj.toclip(camera.view.fin(Matrix4f.id).mul4(mloc));
	if(clip) {
	    float w = sloc[3];
	    if((sloc[0] > -w) && (sloc[0] < w) && (sloc[1] > -w) && (sloc[1] < w))
		return(Double.NaN);
	}
	float a = ((float)sz.y) / ((float)sz.x);
	return(Math.atan2(sloc[1] * a, sloc[0]));
    }

    /* brodgar voice: horizontal azimuth of a map point relative to the camera's
     * facing, in eye space (0 = ahead, + = right) — the same as the game's own
     * positional audio (ActAudio: atan2(pos.x, -pos.z)), and continuous in every
     * camera, unlike screenangle which flips for points behind the camera plane. */
    public double spatialAzimuth(Coord2d mc) {
	Coord3f cc;
	try {
	    cc = getcc();
	} catch(Loading e) {
	    return(Double.NaN);
	}
	Coord3f eye = camera.view.fin(Matrix4f.id).mul4(new Coord3f((float)mc.x, -(float)mc.y, cc.z));
	return(Math.atan2(eye.x, -eye.z));
    }

    // addon: 044.3 — the camera's view matrix (render space -> eye space). A camera-facing world quad
    // (hafen.vr():*():facing("camera")) is turned in Gob.Placer.getr, which runs on the render tree's
    // per-frame placement tick and has no Pipe to read the camera from; Camera.view is protected and this
    // is the only thing outside the client that needs it. Null before a camera exists.
    public Matrix4f camview() {
	Camera c = this.camera;
	return((c == null) ? null : c.view.fin(Matrix4f.id));
    }

    private void partydraw(GOut g) {
	for(Party.Member m : ui.sess.glob.party.memb.values()) {
	    if(m.gobid == this.plgob)
		continue;
	    Coord2d mc = m.getc();
	    if(mc == null)
		continue;
	    double a = screenangle(mc, true);
	    if(Double.isNaN(a))
		continue;
	    g.chcolor(m.col);
	    drawarrow(g, a);
	}
	g.chcolor();
    }

    protected void maindraw(Render out) {
	drawsmap(out);
	// addon: the "scene" named pass (spec 019, task 019.6) -- PView.maindraw dispatches the 3D draw list,
	// i.e. the world itself. The rest of PView.draw (the framebuffer resolve, the 2D overlay list) is not
	// in it: this pass is the scene, and naming a boundary is only worth a GL timestamp query when the
	// boundary means something.
	io.brodgar.prof.Passes.begin(out, io.brodgar.prof.Passes.SCENE);
	try {
	    super.maindraw(out);
	} finally {
	    io.brodgar.prof.Passes.end(out, io.brodgar.prof.Passes.SCENE);
	}
    }

    private Loading camload = null, lastload = null;
    public void draw(GOut g) {
	Loader.Future<Plob> placing = this.placing;
	if((placing != null) && placing.done())
	    placing.get().gtick(g.out);
	// addon: gtick the client-only ghost gobs (hafen.vr()) — not in OCache, like the Plob above.
	for(Gob gob : clientGobs) {
	    try {
		gob.gtick(g.out);
	    } catch(RuntimeException e) {
		/* isolate one ghost's error from the frame */
	    }
	}
	glob.map.sendreqs();
	if((olftimer != 0) && (olftimer < Utils.rtime()))
	    unflashol();
	try {
	    if(camload != null)
		throw(new Loading(camload));
	    undelay(delayed, g);
	    super.draw(g);
	    undelay(delayed2, g);
	    poldraw(g);
	    partydraw(g);
	    io.brodgar.session.Control.draw(this, g);   // rts: (F3) selection brackets and the marquee
	    glob.map.reqarea(cc.floor(tilesz).sub(MCache.cutsz.mul(view + 1)),
			     cc.floor(tilesz).add(MCache.cutsz.mul(view + 1)));
	} catch(Loading e) {
	    e.boostprio(6);
	    lastload = e;
	    String text = e.getMessage();
	    if(text == null)
		text = "Loading...";
	    g.chcolor(Color.BLACK);
	    g.frect(Coord.z, sz);
	    g.chcolor(Color.WHITE);
	    g.atext(text, sz.div(2), 0.5, 0.5);
	}
    }
    
    private double initload = -2;
    private boolean initdraw = false;
    private void checkload() {
	if(initload == -1)
	    return;
	double now = Utils.rtime();
	if(initload == -2) {
	    delay2(g -> initdraw = true);
	    initload = now;
	}
	if((terrain.loading() == null) && (gobs.loading() == null) && initdraw) {
	    initload(now - initload);
	    initload = -1;
	}
    }

    protected void initload(double time) {
	wdgmsg("initload", time);
    }

    public void tick(double dt) {
	super.tick(dt);
	if(dormant) {
	    /* rts: what a dormant view still owes its session (F0, specs/rts/plan.md). draw() is where a
	     * live view asks the server for the ground around it -- sendreqs() at the top, reqarea() at
	     * the bottom -- so a view that is never drawn would never request a single grid, and both
	     * F1's cross-session offset and any order that needs a destination rest on the member having
	     * loaded the ground it is standing on. Everything below this line builds a picture, and a
	     * member makes none: no camera, no shadow map, no weather, no click-map. */
	    /* reqarea() calls getcut() once per cut of the rectangle -- eighty-odd calls -- and this ran
	     * every frame, per dormant session. The server answers on its own schedule and sendreqs()
	     * already rate-limits each grid to one request a second, so asking sixty times a second was
	     * only ever costing us. */
	    double now = Utils.rtime();
	    if((now - lastreq) < 0.25)
		return;
	    lastreq = now;
	    glob.map.sendreqs();
	    try {
		Coord tc = new Coord2d(getcc()).floor(tilesz);
		glob.map.reqarea(tc.sub(MCache.cutsz.mul(view + 2)), tc.add(MCache.cutsz.mul(view + 2)));
	    } catch(Loading e) {
		/* No player yet, or no ground under them: there is nothing to centre a request on. */
	    }
	    return;
	}
	io.brodgar.voice.Voice.tick();   // brodgar voice: per-frame spatialization, like the game's positional audio
	SpeakerIcon.sweep(this);   // brodgar voice: colour-coded speaker icon above each player
	checkload();
	camload = null;
	try {
	    if((shake = shake * Math.pow(100, -dt)) < 0.01)
		shake = 0;
	    camoff.x = (float)((Math.random() - 0.5) * shake);
	    camoff.y = (float)((Math.random() - 0.5) * shake);
	    camoff.z = (float)((Math.random() - 0.5) * shake);
	    camera.tick(dt);
	} catch(Loading e) {
	    e.boostprio(5);
	    camload = e;
	}
	basic(Camera.class, camera);
	amblight();
	updsmap(amblight);
	updweather();
	synchronized(glob.map) {
	    terrain.tick();
	    sessiontick();   // rts: (F7) the members' ground and objects, merged into this scene
	    oltick();
	    if(gridlines != null)
		gridlines.tick();
	    clickmap.tick();
	}
	/* 068: read the ground the character remembers out of the map database, and draw it. AFTER the
	 * live terrain and outside its lock: the recalled raster yields every cut terrain.area holds, so
	 * it wants this frame's area rather than the last one's, and nothing it touches is behind that
	 * monitor. */
	recalltick();
	Loader.Future<Plob> placing = this.placing;
	if((placing != null) && placing.done()) {
	    Plob ob = placing.get();
	    synchronized(ob) {
		ob.ctick(dt);
	    }
	}
	// addon: ctick the client-only ghost gobs (hafen.vr()) — not in OCache, like the Plob above.
	for(Gob gob : clientGobs) {
	    try {
		synchronized(gob) {
		    gob.ctick(dt);
		}
	    } catch(RuntimeException e) {
		/* isolate one ghost's error from the frame (Loading etc.) */
	    }
	}
    }

    public void resize(Coord sz) {
	super.resize(sz);
	camera.resized();
    }

    public static interface PlobAdjust {
	public void adjust(Plob plob, Coord pc, Coord2d mc, int modflags);
	public default boolean rotate(Plob plob, MouseWheelEvent data, int modflags) {return(rotate(plob, data.a, modflags));}
	@Deprecated public default boolean rotate(Plob plob, int amount, int modflags) {return(false);}
    }

    // addon: shared placement-snap math (spec 16-virtual-entities §4.1, D-033) — factored verbatim out of
    //        StdPlace.adjust so the hafen.vr():ghost() gizmo (hafen.map.snapPlace) snaps client ghosts through the
    //        EXACT same code the client uses to place a building, honouring the live :placegrid setting with no
    //        drift. modflags = UI.MOD_* bits; SHIFT selects the sub-tile placegrid, otherwise the tile centre.
    //        Pure + static (no MapView instance needed), so the bridge can reuse it directly.
    public static Coord2d placeSnap(Coord2d mc, int modflags) {
	if((modflags & UI.MOD_SHIFT) == 0)
	    return(mc.floor(tilesz).mul(tilesz).add(tilesz.div(2)));      // no SHIFT -> tile centre
	else if(plobpgran > 0)
	    return(mc.div(tilesz).mul(plobpgran).roundf().div(plobpgran).mul(tilesz));  // SHIFT -> sub-tile placegrid
	else
	    return(mc);                                                  // SHIFT + placegrid 0 -> free
    }

    public static class StdPlace implements PlobAdjust {
	boolean freerot = false;

	public void adjust(Plob plob, Coord pc, Coord2d mc, int modflags) {
	    Coord2d nc = placeSnap(mc, modflags);   // addon: was inline; shared with the ghost gizmo (see MapView.placeSnap)
	    Gob pl = plob.mv().player();
	    if((pl != null) && !freerot)
		plob.move(nc, Math.round(plob.rc.angle(pl.rc) / (Math.PI / 2)) * (Math.PI / 2));
	    else
		plob.move(nc);
	}

	public boolean rotate(Plob plob, MouseWheelEvent data, int modflags) {
	    if((modflags & (UI.MOD_CTRL | UI.MOD_SHIFT)) == 0)
		return(false);
	    freerot = true;
	    double na;
	    if((modflags & UI.MOD_SHIFT) == 0)
		na = (Math.PI / 4) * (Math.round(plob.a / (Math.PI / 4)) + data.a);
	    else
		na = plob.a + data.s * Math.PI / plobagran;
	    na = Utils.cangle(na);
	    plob.move(na);
	    return(true);
	}
    }

    public class Plob extends Gob {
	public PlobAdjust adjust = new StdPlace();
	Coord lastmc = null;
	RenderTree.Slot slot;

	private Plob(Indir<Resource> res, Message sdt) {
	    super(MapView.this.glob, Coord2d.of(getcc()));
	    setattr(new ResDrawable(this, res, sdt));
	}

	public MapView mv() {return(MapView.this);}

	public void move(Coord2d c, double a) {
	    super.move(c, a);
	    updated();
	}

	public void move(Coord2d c) {
	    move(c, this.a);
	}

	public void move(double a) {
	    move(this.rc, a);
	}

	void place() {
	    if(ui.mc.isect(rootpos(), sz))
		new Adjust(ui.mc.sub(rootpos()), 0).run();
	    this.slot = basic.add(this.placed);
	}

	private class Adjust extends Maptest {
	    int modflags;
	    
	    Adjust(Coord c, int modflags) {
		super(c);
		this.modflags = modflags;
	    }
	    
	    public void hit(Coord pc, Coord2d mc) {
		adjust.adjust(Plob.this, pc, mc, modflags);
		lastmc = pc;
	    }
	}

	public String toString() {
	    return("#<plob>");
	}
    }

    // addon: V1 virtual entities (hafen.vr()) — CLIENT-ONLY Gobs in the 3D `basic` scene. addClientGob
    //        does the exact operation Plob.place() does (`basic.add(placed)`), but `basic` (PView) and
    //        Gob.placed live in package `haven`, so this centralizes the scene mutation behind one public
    //        seam for `io.brodgar.addon` (spec 16 §6, option A — smallest, clearest, mirrors Plob). A ghost
    //        has NO server id (Gob id -1 ⇒ virtual), so it is invisible to OCache/reads/server (N1/N2). It
    //        is therefore ALSO not in OCache's tick/draw loop — so, exactly like the placement Plob (ctick'd
    //        in tick(), gtick'd in draw()), MapView must ctick/gtick these gobs itself or the sprite never
    //        prepares/animates and nothing renders. clientGobs holds them for that; the per-gob work is
    //        error-isolated so one bad ghost never breaks the frame. Thread-safe: RenderTree slot add/remove
    //        take the tree lock and the list is copy-on-write, so the bridge may add from a loader thread
    //        (deferred resource-resolving create, like Plob) and remove from the UI thread.
    private final java.util.Collection<Gob> clientGobs = new java.util.concurrent.CopyOnWriteArrayList<Gob>();

    public RenderTree.Slot addClientGob(Gob gob) {
	RenderTree.Slot slot = basic.add(gob.placed);
	clientGobs.add(gob);
	return(slot);
    }
    public void removeClientGob(Gob gob, RenderTree.Slot slot) {
	if(gob != null)
	    clientGobs.remove(gob);
	if(slot != null) {
	    try {
		slot.remove();
	    } catch(RenderTree.SlotRemoved e) {
		/* already gone (e.g. the scene was torn down by a relog) — teardown is idempotent */
	    }
	}
    }

    // addon: 044.9 — is the GROUND under this world point being drawn right now? A client-only gob is in no
    //        OCache, so nothing removes it when the ground it stands on goes; the addon layer asks this
    //        instead, and hides a free entity whose ground is not there. The terrain's own per-cut map IS
    //        the answer — a cut is in it exactly while its mesh is in the scene — so there is no second rule
    //        to keep in step with what the player can actually see. UI thread (the cut map is mutated by
    //        MapRaster.Grid.tick, which taps groundChanged() at both of its mutation points).
    public boolean grounddrawn(Coord2d rc) {
	Terrain t = this.terrain;
	if((t == null) || (rc == null))
	    return(true);			/* no terrain to contradict it */
	return(t.main.cuts.containsKey(rc.floor(tilesz).div(MCache.cutsz)));
    }

    private Collection<String> olflash = null;
    private double olftimer;

    private void unflashol() {
	if(olflash != null) {
	    olflash.forEach(this::disol);
	}
	olflash = null;
	olftimer = 0;
    }

    private void flashol(Collection<String> ols, double tm) {
	unflashol();
	ols.forEach(this::enol);
	olflash = ols;
	olftimer = Utils.rtime() + tm;
    }

    public void uimsg(String msg, Object... args) {
	if(msg == "place") {
	    Loader.Future<Plob> placing = this.placing;
	    if(placing != null) {
		if(!placing.cancel()) {
		    Plob ob = placing.get();
		    synchronized(ob) {
			ob.slot.remove();
			ob.removed();
		    }
		}
		this.placing = null;
	    }
	    int a = 0;
	    Indir<Resource> res = ui.sess.getresv(args[a++]);
	    Message sdt;
	    if((args.length > a) && (args[a] instanceof byte[]))
		sdt = new MessageBuf((byte[])args[a++]);
	    else
		sdt = Message.nil;
	    int oa = a;
	    this.placing = glob.loader.defer(new Supplier<Plob>() {
		    int a = oa;
		    Plob ret = null;
		    public Plob get() {
			if(ret == null)
			    ret = new Plob(res, new MessageBuf(sdt));
			while(a < args.length) {
			    int a2 = a;
			    Indir<Resource> ores = ui.sess.getresv(args[a2++]);
			    Message odt;
			    if((args.length > a2) && (args[a2] instanceof byte[]))
				odt = new MessageBuf((byte[])args[a2++]);
			    else
				odt = Message.nil;
			    ret.addol(ores, odt);
			    a = a2;
			}
			ret.place();
			return(ret);
		    }
		});
	} else if(msg == "unplace") {
	    Loader.Future<Plob> placing = this.placing;
	    if(placing != null) {
		if(!placing.cancel()) {
		    Plob ob = placing.get();
		    synchronized(ob) {
			ob.slot.remove();
			ob.removed();
		    }
		}
		this.placing = null;
	    }
	} else if(msg == "move") {
	    cc = ((Coord)args[0]).mul(posres);
	} else if(msg == "plob") {
	    if(args[0] == null)
		plgob = -1;
	    else
		plgob = Utils.uiv(args[0]);
	} else if(msg == "flashol2") {
	    Collection<String> ols = new LinkedList<>();
	    double tm = Utils.dv(args[0]) / 100.0;
	    for(int a = 1; a < args.length; a++)
		ols.add((String)args[a]);
	    flashol(ols, tm);
	} else if(msg == "sel") {
	    boolean sel = Utils.bv(args[0]);
	    synchronized(this) {
		if(selection != null) {
		    selection.destroy();
		    selection = null;
		}
		if(sel) {
		    Coord max = (args.length > 1) ? (Coord)args[1] : null;
		    selection = new Selector(max);
		}
	    }
	} else if(msg == "shake") {
	    shake += Utils.dv(args[0]);
	} else {
	    super.uimsg(msg, args);
	}
    }

    public abstract class Maptest {
	private final Coord pc;

	public Maptest(Coord c) {
	    this.pc = c;
	}

	public void run() {
	    Environment env = ui.env;
	    Render out = env.render();
	    Pipe.Op basic = clickbasic(MapView.this.sz);
	    Pipe bstate = new BufPipe().prep(basic);
	    out.clear(bstate, FragID.fragid, FColor.BLACK);
	    out.clear(bstate, 1.0);
	    checkmapclick(out, basic, pc, mc -> {
		    synchronized(ui) {
			if(mc != null)
			    hit(pc, mc);
			else
			    nohit(pc);
		    }
		});
	    env.submit(out);
	}

	protected abstract void hit(Coord pc, Coord2d mc);
	protected void nohit(Coord pc) {}
    }

    public abstract class Hittest {
	private final Coord pc;
	private Coord2d mapcl;
	private ClickData objcl;
	private int dfl = 0;
	
	public Hittest(Coord c) {
	    pc = c;
	}
	
	public void run() {
	    Environment env = ui.env;
	    Render out = env.render();
	    Pipe.Op basic = clickbasic(MapView.this.sz);
	    Pipe bstate = new BufPipe().prep(basic);
	    out.clear(bstate, FragID.fragid, FColor.BLACK);
	    out.clear(bstate, 1.0);
	    checkmapclick(out, basic, pc, mc -> {mapcl = mc; ckdone(1);});
	    out.clear(bstate, FragID.fragid, FColor.BLACK);
	    checkgobclick(out, basic, pc, cl -> {objcl = cl; ckdone(2);});
	    env.submit(out);
	}

	private void ckdone(int fl) {
	    boolean done = false;
	    synchronized(this) {
		    if((dfl |= fl) == 3)
			done = true;
	    }
	    if(done) {
		synchronized(ui) {
		    if(mapcl != null) {
			if(objcl == null)
			    hit(pc, mapcl, null);
			else
			    hit(pc, mapcl, objcl);
		    } else {
			nohit(pc);
		    }
		}
	    }
	}
	
	protected abstract void hit(Coord pc, Coord2d mc, ClickData inf);
	protected void nohit(Coord pc) {}
    }

    private class Click extends Hittest {
	int clickb;
	
	private Click(Coord c, int b) {
	    super(c);
	    clickb = b;
	}
	
	protected void hit(Coord pc, Coord2d mc, ClickData inf) {
	    clickhit(pc, mc, inf, clickb);
	}
    }

    /* rts: what an ordinary left click does, said apart from the pick pass that resolved it, because
     * ClickOrder below reaches the same conclusion by a different road: a click that landed on a gob was
     * never an order and has to be dispatched exactly as this one is -- through this very code, not
     * through an imitation of it. */
    private void clickhit(Coord pc, Coord2d mc, ClickData inf, int clickb) {
	// addon: V2 virtual entities (hafen.vr()) — a click that resolved to a CLICKABLE client ghost is
	//        dispatched to the addon and CONSUMED here, BEFORE wdgmsg (the same choke point the voice
	//        feature hooks below): the ghost is a virtual Gob with no server id, so a "click" send would be
	//        bogus, and client-only detection means no server contact ⇒ this stays SAFE-tier (D-032). Fast
	//        path: only a virtual gob can be a client ghost (real gobs are non-virtual), so nothing is asked
	//        of the addon layer for ordinary clicks; a non-clickable ghost carries no GobClick and never
	//        wins the pick, so normal game clicks fall straight through it.
	Gob cg = clickedgob(inf);
	if((cg != null) && cg.virtual && io.brodgar.addon.AddonManager.onGhostClick(cg, clickb, mc))
	    return;
	if(inf == null) io.brodgar.voice.Voice.onMove(MapView.this, mc);   // brodgar voice: report move intent
	VoiceTarget.note(clickedgob(inf), plgob);   // brodgar voice: remember clicked player for the radial menu
	io.brodgar.addon.AddonManager.noteClick(cg, ui.lcc);   // addon: 047.3 -> hafen.flowermenu():gob(). The server's "sm" carries no gob, so the client correlates it here, keyed on the press point (ui.lcc) the menu will place itself at.
	Object[] args = {pc, mc.floor(posres), clickb, ui.modflags()};
	if(inf != null)
	    args = Utils.extend(args, inf.clickargs());
	wdgmsg("click", args);
    }
    
    /* rts: (F3, specs/rts/plan.md) a move order's destination, resolved by the client's OWN pick pass
     * -- the same machinery a real click uses, so an order lands exactly where a click would have.
     * It sends nothing itself: it hands the resolved point to the RTS controller, which decides who
     * receives it. What the pick found under the cursor decides WHETHER there is an order at all: the
     * selection is only ever told to walk, and a gob is somebody to interact with, which is the drawn
     * character's own business and travels no further. */
    public class ClickOrder extends Hittest {
	private final int clickb, mods;

	public ClickOrder(Coord c, int b, int mods) {
	    super(c);
	    this.clickb = b;
	    this.mods = mods;
	}

	protected void hit(Coord pc, Coord2d mc, ClickData inf) {
	    if(inf != null) {
		/* It landed on something, so it was never an order -- and the press was swallowed before
		 * the pick could say so, which is why it is given back here rather than in mousedown. The
		 * ordinary click it always was, down the very path it always took: one press is still one
		 * pick pass and one message, just decided a frame later. */
		clickhit(pc, mc, inf, clickb);
		return;
	    }
	    io.brodgar.session.Control.hit(mc, mods);
	}
    }

    public void grab(Grabber grab) {
	this.grab = grab;
    }
    
    public void release(Grabber grab) {
	if(this.grab == grab)
	    this.grab = null;
    }
    
    private UI.Grab camdrag = null;

    public boolean mousedown(MouseDownEvent ev) {
	// addon: 044.4 spatial UI (hafen.vr():widget()) — a widget standing in the world takes the pointer here,
	//        before anything of the map view's own, exactly as a window on the flat UI takes it before the map
	//        view ever sees it. The test is the standing quad's own projected corners, so it answers inside
	//        THIS event rather than a frame later like the pick pass — which is what lets the press, the drag
	//        and the release be one gesture. A point on no panel returns false and everything below is
	//        untouched, so a click that misses still reaches the world beneath it. (Also before setfocus: the
	//        keyboard belongs to the panel that was clicked, not to the map view behind it.)
	if((camdrag == null)
	   && io.brodgar.addon.AddonManager.onSurfaceMouseDown(this, ev))
	    return(true);
	parent.setfocus(this);
	Loader.Future<Plob> placing_l = this.placing;
	if(ev.b == 2) {
	    if((camdrag == null) && camera.click(ev.c)) {
		camdrag = ui.grabmouse(this);
	    }
	} else if((placing_l != null) && placing_l.done()) {
	    Plob placing = placing_l.get();
	    if(placing.lastmc != null)
		wdgmsg("place", placing.rc.floor(posres), (int)Math.round(placing.a * 32768 / Math.PI), ev.b, ui.modflags());
	} else if((grab != null) && grab.mmousedown(ev.c, ev.b)) {
	} else if(io.brodgar.session.Control.mousedown(this, ev)) {   // rts: (F3) alt starts a marquee, and a click with units selected is theirs -- everything else falls through to Click below, unchanged
	} else {
	    new Click(ev.c, ev.b).run();
	}
	return(true);
    }
    
    public void mousemove(MouseMoveEvent ev) {
	// addon: 044.4 — hover and drag over a standing panel, on the same terms as the press above. A camera
	//        drag or a map grab already owns the pointer, so neither is interrupted.
	if((camdrag == null) && (grab == null)
	   && io.brodgar.addon.AddonManager.onSurfaceMouseMove(this, ev))
	    return;
	if(grab != null)
	    grab.mmousemove(ev.c);
	Loader.Future<Plob> placing_l = this.placing;
	if(camdrag != null) {
	    camera.drag(ev.c);
	} else if(io.brodgar.session.Control.mousemove(this, ev)) {   // rts: (F3) the marquee being dragged
	} else if((placing_l != null) && placing_l.done()) {
	    Plob placing = placing_l.get();
	    if((placing.lastmc == null) || !placing.lastmc.equals(ev.c)) {
		placing.new Adjust(ev.c, ui.modflags()).run();
	    }
	}
    }
    
    public boolean mouseup(MouseUpEvent ev) {
	// addon: 044.4 — the release of the gesture the press above started, delivered to the panel that took it
	//        even if the pointer has since left it.
	if((camdrag == null) && (grab == null)
	   && io.brodgar.addon.AddonManager.onSurfaceMouseUp(this, ev))
	    return(true);
	if(ev.b == 2) {
	    if(camdrag != null) {
		camera.release();
		camdrag.remove();
		camdrag = null;
	    }
	} else if(grab != null) {
	    grab.mmouseup(ev.c, ev.b);
	} else {
	    io.brodgar.session.Control.mouseup(this, ev);   // rts: (F3) closes the marquee started above
	}
	return(true);
    }

    public boolean mousewheel(MouseWheelEvent ev) {
	Loader.Future<Plob> placing_l = this.placing;
	if((grab != null) && grab.mmousewheel(ev.c, ev.a))
	    return(true);
	// addon: 044.4 — the wheel over a standing panel is the panel's, and it stays the panel's even when
	//        nothing inside it uses the wheel: a window on the flat UI blocks the camera zoom under it the
	//        same way, and this feature's rule is that the world one behaves identically.
	if(io.brodgar.addon.AddonManager.onSurfaceMouseWheel(this, ev))
	    return(true);
	if((placing_l != null) && placing_l.done()) {
	    Plob placing = placing_l.get();
	    if(placing.adjust.rotate(placing, ev, ui.modflags()))
		return(true);
	}
	return(camera.wheel(ev));
    }
    
    public boolean drop(final Coord cc, Coord ul) {
	new Hittest(cc) {
	    public void hit(Coord pc, Coord2d mc, ClickData inf) {
		wdgmsg("drop", pc, mc.floor(posres), ui.modflags());
	    }
	}.run();
	return(true);
    }
    
    public boolean iteminteract(Coord cc, Coord ul) {
	new Hittest(cc) {
	    public void hit(Coord pc, Coord2d mc, ClickData inf) {
		VoiceTarget.note(clickedgob(inf), plgob);   // brodgar voice
		Object[] args = {pc, mc.floor(posres), ui.modflags()};
		if(inf != null)
		    args = Utils.extend(args, inf.clickargs());
		wdgmsg("itemact", args);
	    }
	}.run();
	return(true);
    }

    /* brodgar voice: resolve the Gob a click landed on, unwrapping the composite
     * clickable that player/animal gobs use. Returns null for the ground or non-gobs. */
    static Gob clickedgob(ClickData inf) {
	if(inf == null)
	    return(null);
	if(inf.ci instanceof Gob.GobClick)
	    return(((Gob.GobClick)inf.ci).gob);
	if(inf.ci instanceof Composited.CompositeClick)
	    return(((Composited.CompositeClick)inf.ci).gi.gob);
	return(null);
    }

    public boolean keydown(KeyDownEvent ev) {
	Loader.Future<Plob> placing_l = this.placing;
	if((placing_l != null) && placing_l.done()) {
	    Plob placing = placing_l.get();
	    if((ev.code == KeyEvent.VK_LEFT) && placing.adjust.rotate(placing, new MouseWheelEvent(Coord.z, -1, -1), ui.modflags()))
		return(true);
	    if((ev.code == KeyEvent.VK_RIGHT) && placing.adjust.rotate(placing, new MouseWheelEvent(Coord.z, 1, 1), ui.modflags()))
		return(true);
	}
	if(io.brodgar.voice.Voice.kb_ptt.key().match(ev)) {
	    io.brodgar.voice.Voice.setPushToTalk(true);   // brodgar voice: push-to-talk pressed
	    return(true);
	}
	if(io.brodgar.session.Control.keydown(this, ev))   // rts: (F5) the anchor switch, while RTS mode is on
	    return(true);
	if(camera.keydown(ev))
	    return(true);
	return(super.keydown(ev));
    }

    public boolean keyup(KeyUpEvent ev) {
	if(io.brodgar.voice.Voice.kb_ptt.key().match(ev)) {
	    io.brodgar.voice.Voice.setPushToTalk(false);  // brodgar voice: push-to-talk released
	    return(true);
	}
	return(super.keyup(ev));
    }

    public static final KeyBinding kb_grid = KeyBinding.get("grid", KeyMatch.forchar('G', KeyMatch.C));
    public boolean globtype(GlobKeyEvent ev) {
	if(kb_grid.key().match(ev)) {
	    showgrid(gridlines == null);
	    return(true);
	}
	return(super.globtype(ev));
    }

    public Object tooltip(Coord c, Widget prev) {
	if(selection != null) {
	    if(selection.tt != null)
		return(selection.tt);
	}
	return(super.tooltip(c, prev));
    }

    public class GrabXL implements Grabber {
	private final Grabber bk;
	public boolean mv = false;

	public GrabXL(Grabber bk) {
	    this.bk = bk;
	}

	public boolean mmousedown(Coord cc, final int button) {
	    new Maptest(cc) {
		public void hit(Coord pc, Coord2d mc) {
		    bk.mmousedown(mc.round(), button);
		}
	    }.run();
	    return(true);
	}

	public boolean mmouseup(Coord cc, final int button) {
	    new Maptest(cc) {
		public void hit(Coord pc, Coord2d mc) {
		    bk.mmouseup(mc.round(), button);
		}
	    }.run();
	    return(true);
	}

	public boolean mmousewheel(Coord cc, final int amount) {
	    new Maptest(cc) {
		public void hit(Coord pc, Coord2d mc) {
		    bk.mmousewheel(mc.round(), amount);
		}
	    }.run();
	    return(true);
	}

	public void mmousemove(Coord cc) {
	    if(mv) {
		new Maptest(cc) {
		    public void hit(Coord pc, Coord2d mc) {
			bk.mmousemove(mc.round());
		    }
		}.run();
	    }
	}
    }

    public static final OverlayInfo selol = new OverlayInfo() {
	    final Material mat = new Material(new BaseColor(255, 255, 0, 32), States.maskdepth);

	    public Collection<String> tags() {
		return(Arrays.asList("show"));
	    }

	    public Material mat() {return(mat);}
	};
    public class Selector implements Grabber {
	public final Coord max;
	public Coord sc;
	public int modflags;
	private MCache.RectOverlay ol;
	private UI.Grab mgrab;
	private Text tt;
	final GrabXL xl = new GrabXL(this) {
		public boolean mmousedown(Coord cc, int button) {
		    if(button != 1)
			return(false);
		    return(super.mmousedown(cc, button));
		}
		public boolean mmousewheel(Coord cc, int amount) {
		    return(false);
		}
	    };

	{
	    grab(xl);
	}

	public Selector(Coord max) {
	    this.max = max;
	}

	public boolean mmousedown(Coord mc, int button) {
	    synchronized(MapView.this) {
		if(selection != this)
		    return(false);
		if(sc != null) {
		    glob.map.remove(ol);
		    mgrab.remove();
		}
		sc = mc.div(MCache.tilesz2);
		modflags = ui.modflags();
		xl.mv = true;
		mgrab = ui.grabmouse(MapView.this);
		ol = glob.map.new RectOverlay(selol, Area.sized(sc, new Coord(1, 1)));
		glob.map.add(ol);
		return(true);
	    }
	}

	public Coord getec(Coord mc) {
	    Coord tc = mc.div(MCache.tilesz2);
	    if(max != null) {
		Coord dc = tc.sub(sc);
		tc = sc.add(Utils.clip(dc.x, -(max.x - 1), (max.x - 1)),
			    Utils.clip(dc.y, -(max.y - 1), (max.y - 1)));
	    }
	    return(tc);
	}

	public boolean mmouseup(Coord mc, int button) {
	    synchronized(MapView.this) {
		if(sc != null) {
		    Coord ec = getec(mc);
		    xl.mv = false;
		    tt = null;
		    glob.map.remove(ol);
		    mgrab.remove();
		    wdgmsg("sel", sc, ec, modflags);
		    sc = null;
		}
		return(true);
	    }
	}

	public boolean mmousewheel(Coord mc, int amount) {
	    return(false);
	}

	public void mmousemove(Coord mc) {
	    synchronized(MapView.this) {
		if(sc != null) {
		    Coord tc = getec(mc);
		    Coord c1 = new Coord(Math.min(tc.x, sc.x), Math.min(tc.y, sc.y));
		    Coord c2 = new Coord(Math.max(tc.x, sc.x), Math.max(tc.y, sc.y));
		    ol.update(new Area(c1, c2.add(1, 1)));
		    tt = Text.render(String.format("%d\u00d7%d", c2.x - c1.x + 1, c2.y - c1.y + 1));
		}
	    }
	}

	public void destroy() {
	    synchronized(MapView.this) {
		if(sc != null) {
		    glob.map.remove(ol);
		    mgrab.remove();
		}
		release(xl);
	    }
	}
    }

    private Camera makecam(Class<? extends Camera> ct, String... args) {
	try {
	    try {
		Constructor<? extends Camera> cons = ct.getConstructor(MapView.class, String[].class);
		return(cons.newInstance(new Object[] {this, args}));
	    } catch(IllegalAccessException e) {
	    } catch(NoSuchMethodException e) {
	    }
	    try {
		Constructor<? extends Camera> cons = ct.getConstructor(MapView.class);
		return(cons.newInstance(new Object[] {this}));
	    } catch(IllegalAccessException e) {
	    } catch(NoSuchMethodException e) {
	    }
	} catch(InstantiationException e) {
	    throw(new Error(e));
	} catch(InvocationTargetException e) {
	    if(e.getCause() instanceof RuntimeException)
		throw((RuntimeException)e.getCause());
	    throw(new RuntimeException(e));
	}
	throw(new RuntimeException("No valid constructor found for camera " + ct.getName()));
    }

    /* rts: one camera across the characters, not one per character. Called on every switch of screen
     * with the camera the session losing it was being played with: the type is adopted first -- `:cam`
     * installs a camera on the view that is drawn, so the others are still on whatever they were built
     * with -- and then its state is copied over by Camera.restate.
     *
     * Rebuilt bare, with none of the arguments `:cam` was given, because restate copies every field a
     * camera parsed those arguments into anyway. A type that cannot be built here is left alone rather
     * than reported: the view is a frame from being drawn, and its own camera is a working camera. */
    public void adoptcam(Camera from, Coord2d off) {
	if((from == null) || (from == camera))
	    return;
	if(from.getClass() != camera.getClass()) {
	    try {
		camera = makecam(from.getClass());
	    } catch(RuntimeException e) {
		return;
	    }
	}
	camera.restate(from, off);
    }

    private Camera restorecam() {
	Class<? extends Camera> ct = camtypes.get(Utils.getpref("defcam", null));
	if(ct == null)
	    return(new SOrthoCam());
	String[] args = (String [])Utils.deserialize(Utils.getprefb("camargs", null));
	if(args == null) args = new String[0];
	try {
	    return(makecam(ct, args));
	} catch(Exception e) {
	    return(new SOrthoCam());
	}
    }

    /* addon: (066.2) every name :cam takes, in declaration order. The registry is private and each
     * camera registers itself from its own static block, so this is the only way anything outside
     * MapView can learn which names exist -- and it is what both the dropdown and setcam's own
     * refusal read, so no second list of camera names is kept anywhere. */
    public static List<String> camnames() {
	return(new ArrayList<String>(camtypes.keySet()));
    }

    /* addon: (066.2) the name of the camera actually INSTALLED, which is not what the defcam pref
     * says while the RTS mode has swapped one in without writing it. null for a camera no static
     * block registered -- OrthoCam is real and has no name. */
    public String camname() {
	Class<? extends Camera> ct = camera.getClass();
	for(Map.Entry<String, Class<? extends Camera>> e : camtypes.entrySet()) {
	    if(e.getValue() == ct)
		return(e.getKey());
	}
	return(null);
    }

    /* addon: (066.2) the one writer of the camera and its two preferences. The :cam command, the
     * Options ▸ Camera dropdown and Lua all end here, so a camera chosen one way reads back the same
     * through the other two. Unchecked, because the console catches it and the dropdown offers no
     * name that is not in the registry. */
    public void setcam(String name, String... args) {
	Class<? extends Camera> ct = camtypes.get(name);
	if(ct == null)
	    throw(new IllegalArgumentException("no such camera: " + name + " -- the client has " + String.join(", ", camnames())));
	camera = makecam(ct, args);
	Utils.setpref("defcam", name);
	Utils.setprefb("camargs", Utils.serialize(args));
    }

    private Map<String, Console.Command> cmdmap = new TreeMap<String, Console.Command>();
    {
	cmdmap.put("cam", new Console.Command() {
		public void run(Console cons, String[] args) throws Exception {
		    if(args.length >= 2)
			setcam(args[1], Utils.splice(args, 2));
		}
	    });
	/* 068: what the remembered ground's source is based on, what it has read back and what it
	 * costs. The request counts are the ones that matter: a source filled from the map database
	 * that puts anything on the wire is asking the server about ground the character is nowhere
	 * near, which is the one thing this must not do. `off` and `on` take the raster out of the
	 * scene and put it back, which is how "with it off the scene is what it is today" is a thing
	 * the maintainer can check without a rebuild. */
	cmdmap.put("recall", new Console.Command() {
		public void run(Console cons, String[] args) throws Exception {
		    if(args.length >= 2) {
			if(args[1].equals("off"))
			    recallon = false;
			else if(args[1].equals("on"))
			    recallon = true;
			else if(args[1].equals("wash")) {
			    if(args.length < 3)
				throw(new Exception("recall wash: an alpha from 0 to 255, and none given"));
			    int a;
			    try {
				a = Integer.parseInt(args[2]);
			    } catch(NumberFormatException e) {
				throw(new Exception("recall wash: `" + args[2] + "' is not a number"));
			    }
			    if((a < 0) || (a > 255))
				throw(new Exception("recall wash: alpha " + a + " is outside 0 to 255"));
			    setwash(a);
			} else
			    throw(new Exception("recall: no such argument `" + args[1] + "' -- off, on, wash <alpha>, or nothing"));
		    }
		    io.brodgar.session.Recall r = recall;
		    if(r == null)
			throw(new Exception("recall: no source yet -- no minimap to take a session location from"));
		    for(String ln : r.report())
			cons.out.println(ln);
		    cons.out.println(String.format("recall: drawing %s, raster %s, cuts drawn %d of %d (wanted %d, %d new per tick)",
						   recallon ? "on" : "off",
						   (s_recall == null) ? "out of the scene" : "in the scene",
						   (recallterrain == null) ? 0 : recallterrain.main.cuts.size(),
						   recallcutcap,
						   (recallterrain == null) ? 0 : recallterrain.nwanted,
						   recallmaxbuild));
		    cons.out.println(String.format("recall: wash %d of 255 toward grey -- `:recall wash <a>' to change it",
						   washamt));
		}
	    });
	cmdmap.put("whyload", new Console.Command() {
		public void run(Console cons, String[] args) throws Exception {
		    Loading l = lastload;
		    if(l == null)
			throw(new Exception("Not loading"));
		    l.printStackTrace(cons.out);
		}
	    });
    }
    public Map<String, Console.Command> findcmds() {
	return(cmdmap);
    }

    static {
	Console.setscmd("placegrid", new Console.Command() {
		public void run(Console cons, String[] args) {
		    if((plobpgran = Double.parseDouble(args[1])) < 0)
			plobpgran = 0;
		    Utils.setprefd("plobpgran", plobpgran);
		}
	    });
	Console.setscmd("placeangle", new Console.Command() {
		public void run(Console cons, String[] args) {
		    if((plobagran = Double.parseDouble(args[1])) < 2)
			plobagran = 2;
		    Utils.setprefd("plobagran", plobagran);
		}
	    });
	Console.setscmd("clickfuzz", new Console.Command() {
		public void run(Console cons, String[] args) {
		    if((gobclfuzz = Integer.parseInt(args[1])) < 0)
			gobclfuzz = 0;
		}
	    });
	Console.setscmd("clickdb", new Console.Command() {
		public void run(Console cons, String[] args) {
		    clickdb = Utils.parsebool(args[1], false);
		}
	    });
    }
}
