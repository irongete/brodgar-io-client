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
import haven.iosys.audio.*;
import haven.iosys.tk.*;
import java.awt.image.BufferedImage;
import haven.GSettings.SyncMode;
import haven.render.gl.GLEnvironment;
import haven.render.gl.GLRender;

public abstract class UILoop implements Console.Directory {
    public static final Config.Variable<Boolean> dbtext = Config.Variable.propb("haven.dbtext", false);
    public static final Config.Variable<Boolean> profile = Config.Variable.propb("haven.profile", false);
    public final Windeye wnd;
    public final Thread th;
    public final CPUProfile uprof = new CPUProfile(300), rprof = new CPUProfile(300);
    public final GPUProfile gprof = new GPUProfile(300);
    public Environment env;
    public UI ui;
    /* rts: (071.1) which UI is actually DRAWN: the session holding the screen, or `ui` -- the LOGIN
     * screen -- when no session does. Two fields and not one because Client.Main owns `ui` and replaces
     * and destroys it as its chain advances, and that must never be able to destroy a session's UI nor
     * be confused by one being on screen. That reason is stronger since the handoff, not weaker: `ui`
     * holds no game session at all now, so newui's destroy cannot reach one. Everything the frame does
     * (dispatch, gtick, draw, tooltip, cursor) follows drawn(); every session that is not drawn is
     * ticked by Sessions.tick(). */
    private volatile UI drawui = null;
    /* addon: (074.1) THE ADDON LAYER -- the second tree the frame attends, drawn above whichever session holds
     * the screen and above the login screen when none does. It is a UI of its own with a NULL sess, which is
     * what makes it not a session: nothing in it ticks a Glob and no server widget can be handed to it. Built
     * once, here, and never replaced or destroyed, so an addon's own window keeps its place, its focus and any
     * grab it holds across a character switch -- nothing moves, so there is nothing to repair. */
    public final UI layer;
    private final Cursor.Caps curscaps;
    private final Object uilock = new Object();
    private UI lockedui;
    private long frameno = 0;

    public UILoop(Windeye wnd) {
	this.wnd = wnd;
	wnd.drophandler(new Dropper());
	setenv(wnd.env());
	this.curscaps = wnd.toolkit().cursorcaps();
	newui(null);
	/* addon: (074.1) built exactly as bgui builds one -- no slot, no replace, no destroy, no uilock -- but
	 * NOT through bgui itself: this runs inside the constructor, where an override of it reaches a subclass
	 * whose own fields are still unassigned. The tick pump is a widget on its root, the same zero-core-edit
	 * shape a session's is, so what the layer does each frame is said in the addon layer and not here. */
	this.layer = mkui(null);
	this.layer.root.add(new io.brodgar.addon.LayerRoot(), Coord.z);
	io.brodgar.session.Sessions.init(this);   // rts: the sessions layer needs the loop to build a UI (F0)
	this.th = new HackThread(this::run, "Haven UI thread");
    }

    public void start() {
	this.th.start();
    }

    private void setenv(Environment env) {
	this.env = env;
	if(ui != null)
	    ui.env = env;
	if(layer != null)   // addon: (074.1) the second tree draws through the same environment
	    layer.env = env;
	haven.error.ErrorHandler errh = haven.error.ErrorHandler.find();
	if(errh != null) {
	    Environment.Caps caps = env.caps();
	    errh.lsetprop("tk.desc", wnd.toolkit().description());
	    errh.lsetprop("gl.vendor", caps.vendor());
	    errh.lsetprop("gl.version", caps.driver());
	    errh.lsetprop("gl.renderer", caps.device());
	    errh.lsetprop("render.caps", caps);
	}
    }

    private Audio.Root audio = null;
    public UI newui(UI.Runner fun) {
	if(audio == null)
	    audio = new Audio.Root(audiosink());
	UI prevui, newui = new UI(wnd, audio, new Coord(wnd.size()), fun);
	newui.env = this.env;
	newui.cons.add(this);
	synchronized(uilock) {
	    prevui = this.ui;
	    this.ui = newui;
	    ui.root.guprof = uprof;
	    ui.root.grprof = rprof;
	    ui.root.ggprof = gprof;
	    while((this.lockedui != null) && (this.lockedui == prevui)) {
		try {
		    uilock.wait();
		} catch(InterruptedException e) {
		    Thread.currentThread().interrupt();
		    break;
		}
	    }
	}
	if(prevui != null) {
	    if(drawui == prevui)
		drawui = null;   // rts: (F5) the drawn UI just went away with the runner chain
	    prevui.destroy();
	}
	return(newui);
    }

    /** rts: (071.1) the UI being drawn — the session holding the screen, or the login screen when none does. */
    public UI drawn() {
	UI d = drawui;
	return((d != null) ? d : ui);
    }

    /** rts: (071.1) draw this UI instead. Passing the runner's own UI hands the screen to the login screen. */
    public void drawn(UI u) {
	drawui = (u == ui) ? null : u;
    }

    /* rts: (071.1) destroy a UI that may be on screen, from a thread that is not the frame's.
     *
     * A session owns its UI and ends on its OWN thread, where newui's guard does not apply -- and a widget
     * tree disposed between a frame's tick and its draw takes its render slots with it, so that draw throws
     * SlotRemoved and the loop's thread dies. This is newui's own handshake, minus the replace: the caller
     * has already taken the screen off this UI, and here we wait until no frame is still holding it.
     *
     * The screen is taken off it here too if the caller did not, because a UI that is still drawn is one
     * `drawn()` goes on answering with, and the wait below would never end. */
    public void bgdestroy(UI u) {
	if(u == null)
	    return;
	synchronized(uilock) {
	    if(drawui == u)
		drawui = null;
	    while((this.lockedui != null) && (this.lockedui == u)) {
		try {
		    uilock.wait();
		} catch(InterruptedException e) {
		    Thread.currentThread().interrupt();
		    break;
		}
	    }
	}
	u.destroy();
    }

    /* rts: a member session's UI (F0, specs/rts/plan.md). Built exactly like the anchor's -- same window,
     * same audio root, same environment -- but it does NOT become `this.ui`, so it replaces nothing,
     * destroys nothing and is never drawn. The frame loop reaches it through Sessions.tick() instead, and
     * the profiling fields are deliberately left off it: uprof/rprof/gprof describe the frame, and only
     * the anchor has one. */
    public UI bgui(UI.Runner fun) {
	return(mkui(fun));
    }

    /* addon: (074.1) bgui's body, callable from the constructor. bgui itself is overridden -- ClientLoop adds
     * the client's own console directory to what it builds -- and an override reached from a superclass
     * constructor reads its own fields null. */
    private final UI mkui(UI.Runner fun) {
	/* Built outside uilock, exactly as newui() does: UI's constructor runs Runner.init, and no other
	 * lock may be taken underneath this one. */
	if(audio == null)
	    audio = new Audio.Root(audiosink());
	UI ret = new UI(wnd, audio, new Coord(wnd.size()), fun);
	ret.env = this.env;
	ret.cons.add(this);
	return(ret);
    }

    /* XXX: Move to UI? */
    private Object prevtooltip = null;
    private Indir<Tex> prevtooltex = null;
    private Disposable freetooltex = null;
    private int tipfontgen = -1;   // addon: Fonts.gen() at the last String-tooltip render (F3d)
    /* addon: (065.17) what a tip's box is made of, said where it is drawn. The surface has no resource behind
     * it at all -- it is two colours and a rectangle, which is exactly what a flat bg and a line border are.
     * Its MARGIN is not said: the two pixels below are kept whether or not a rule names a padding, so a
     * catalogue carrying one would widen every tip by that much again. */
    private static final java.awt.Color tipbg = new java.awt.Color(35, 35, 35, 192);
    private static final java.awt.Color tipbd = new java.awt.Color(244, 247, 21, 192);
    private static void stocktip() {
	Fonts.stock("tooltip", "bg", Fonts.piece(tipbg));
	Fonts.stock("tooltip", "border", Fonts.piece(tipbd).width(1));
    }

    private void drawtooltip(UI ui, GOut g) {
	Object tooltip;
	synchronized(ui) {
	    tooltip = ui.tooltip(ui.mc);
	}
	Indir<Tex> tt = null;
	// addon: a "tooltip" font override moved (F3d, D-043) -> drop the cached render of the SAME tooltip object.
	int fontgen = Fonts.gen();
	if(fontgen != tipfontgen) {
	    tipfontgen = fontgen;
	    prevtooltip = null;
	}
	if(Utils.eq(tooltip, prevtooltip)) {
	    tt = prevtooltex;
	} else {
	    if(freetooltex != null) {
		freetooltex.dispose();
		freetooltex = null;
	    }
	    prevtooltip = null;
	    prevtooltex = null;
	    Disposable free = null;
	    if(tooltip != null) {
		if(tooltip instanceof Text) {
		    Tex t = ((Text)tooltip).tex();
		    tt = () -> t;
		} else if(tooltip instanceof Tex) {
		    Tex t = (Tex)tooltip;
		    tt = () -> t;
		} else if(tooltip instanceof Indir<?>) {
		    @SuppressWarnings("unchecked")
			Indir<Tex> c = (Indir<Tex>)tooltip;
		    tt = c;
		} else if(tooltip instanceof String) {
		    if(((String)tooltip).length() > 0) {
			// addon: the "tooltip" scope (F3d) -- was Text.render(...), i.e. the "default" scope.
			Tex r = new TexI(Fonts.foundry("tooltip", Text.std).render((String)tooltip, Text.white).img, false);
			tt = () -> r;
			free = r;
		    }
		}
	    }
	    prevtooltip = tooltip;
	    prevtooltex = tt;
	    freetooltex = free;
	}
	Tex tex = (tt == null) ? null : tt.get();
	if(tex != null) {
	    Coord sz = tex.sz();
	    Coord pos = ui.mc.sub(sz).sub(curshotspot);
	    if(pos.x < 0)
		pos.x = 0;
	    if(pos.y < 0)
		pos.y = 0;
	    Coord br = pos.add(sz);
	    Coord m = UI.scale(2, 2);
	    // addon: (065.6) a tip's BOX is the "tooltip" rule's -- its padding widens the room around the text,
	    // its bg and border paint what fills that box. With no rule the two rects below are what they always
	    // were, at the coordinates they always had; the tip's own text is placed and drawn unchanged either way.
	    stocktip();            // addon: (065.17) what a tip's box is made of
	    Coord[] tpad = Fonts.chromepad("tooltip", null);
	    Coord tul = pos.sub(m).sub((tpad == null) ? Coord.z : tpad[0]);
	    Coord tbr = br.add(m).add((tpad == null) ? Coord.z : tpad[1]);
	    if(!Fonts.drawchrome("tooltip", null, g, tul, tbr.sub(tul))) {
		g.chcolor(244, 247, 21, 192);
		g.rect2(tul.sub(1, 1), tbr);
		g.chcolor(35, 35, 35, 192);
		g.frect2(tul, tbr);
		g.chcolor();
	    }
	    g.image(tex, pos);
	}
	ui.lasttip = tooltip;
    }

    private final Map<Resource, Cursor> cursors = new WeakHashMap<>();
    private final Map<Cursor, Coord> curshotspots = new WeakHashMap<>();
    private Object lastcursor = null;
    private Coord curshotspot = Coord.z;
    protected void drawcursor(UI ui, GOut g) {
	Object curs;
	synchronized(ui) {
	    curs = ui.getcurs(ui.mc);
	}
	if(curs instanceof Resource) {
	    Resource res = (Resource)curs;
	    if(curscaps == null) {
		if(!(lastcursor instanceof Resource))
		    wnd.cursor(Cursor.Std.NONE);
		curshotspot = UI.scale(res.flayer(Resource.negc).cc);
		Coord dc = ui.mc.sub(curshotspot);
		g.image(res.flayer(Resource.imgc), dc);
	    } else {
		if(curs != lastcursor) {
		    Cursor tkc = cursors.get(res);
		    if(tkc == null) {
			Coord hotspot = res.flayer(Resource.negc).cc;
			BufferedImage img = res.flayer(Resource.imgc).img;
			Coord sz = PUtils.imgsz(img);
			Coord tsz;
			if(curscaps.pref != 0) {
			    tsz = sz.mul(curscaps.pref).div(sz.max());
			} else {
			    tsz = UI.scale(sz);
			    if((tsz.x > curscaps.max) || (tsz.y > curscaps.max))
				tsz = tsz.mul(curscaps.max).div(tsz.max());
			}
			if(!Utils.eq(tsz, sz)) {
			    img = PUtils.uiscale(img, tsz);
			    hotspot = hotspot.mul(tsz).div(sz);
			}
			cursors.put(res, tkc = wnd.toolkit().makecursor(img, hotspot));
			curshotspots.put(tkc, hotspot);
		    }
		    curshotspot = curshotspots.get(tkc);
		    wnd.cursor(tkc);
		}
	    }
	} else if(curs instanceof Cursor.Std) {
	    if(curs != lastcursor)
		wnd.cursor((Cursor.Std)curs);
	} else {
	    if(curs != lastcursor)
		Warning.warn("unexpected cursor specification: %s", curs);
	}
	lastcursor = curs;
    }

    private long prevfree = 0;
    /* addon: static, and readable, so hafen.client:profiling():memory() can report the client's OWN
     * per-frame allocation estimate rather than computing a second one (spec 019, task 019.3). There is
     * one UI loop per process, so static costs nothing in accuracy. It stays an EWMA maintained by
     * statlines() below, which means it only advances while the stats HUD is being drawn -- the profiler
     * reports it as an ABSENT key, not a 0, until the client has actually computed it. Moving the
     * estimate onto the frame loop instead would put a freeMemory() call on every frame, armed or not,
     * which is exactly the always-on cost 019 exists to avoid. */
    private static volatile long framealloc = 0;
    public static long framealloc() {return(framealloc);}

    protected void statlines(Collection<String> buf, UI ui) {
	buf.add(String.format("FPS: %d (%d%% idle, latency %.2f ms)", fps, (int)(uidle * 100.0), framelag * 1000));
	Runtime rt = Runtime.getRuntime();
	long free = rt.freeMemory(), total = rt.totalMemory();
	if(free < prevfree)
	    framealloc = ((prevfree - free) + (framealloc * 19)) / 20;
	prevfree = free;
	buf.add(String.format("Mem: %,011d/%,011d/%,011d/%,011d (%,d)", free, total - free, total, rt.maxMemory(), framealloc));
	buf.add(String.format("State slots: %d", State.Slot.numslots()));
	Environment env = ui.getenv();
	if(env instanceof GLEnvironment) {
	    GLEnvironment gl = (GLEnvironment)env;
	    buf.add(String.format("GL progs: %d", gl.numprogs()));
	    buf.add(String.format("V-Mem: %s", gl.memstats()));
	}
	@SuppressWarnings("deprecation") MapView map = ui.root.findchild(MapView.class);
	if((map != null) && (map.back != null)) {
	    buf.add(String.format("Camera: %s", map.camstats()));
	    buf.add(String.format("Mapview: %s", map.stats()));
	    // buf.add(String.format("Click: Map: %s, Obj: %s", map.clmaplist.stats(), map.clobjlist.stats()));
	}
	if((ui.sess != null) && (ui.sess.conn instanceof Connection))
	    buf.add(String.format("Connection: %s", ((Connection)ui.sess.conn).stats));
	// rts: what the extra sessions are costing, beside the numbers it is compared against (F0)
	String sstats = io.brodgar.session.Sessions.stats();
	if(!sstats.isEmpty())
	    buf.add(String.format("Sessions: %s", sstats));
	buf.add(String.format("Async: L %s, D %s", ui.loader.stats(), Defer.gstats()));
	int rqd = Resource.local().qdepth() + Resource.remote().qdepth();
	if(rqd > 0)
	    buf.add(String.format("RQ depth: %d (%d)", rqd, Resource.local().numloaded() + Resource.remote().numloaded()));
	wnd.stats(buf);
    }

    private void drawstats(UI ui, GOut g, Render buf) {
	Collection<String> lines = new ArrayList<>();
	statlines(lines, ui);
	synchronized(Debug.framestats) {
	    Debug.framestats.forEach(s -> lines.add(String.valueOf(s)));
	}
	int y = g.sz().y - UI.scale(190), dy = FastText.h;
	for(String ln : lines)
	    FastText.aprint(g, new Coord(10, y -= dy), 0, 1, ln);
    }

    protected Pipe basestate() {
	Pipe base = new BufPipe();
	base.prep(new FragColor<>(FragColor.defcolor)).prep(new DepthBuffer<>(DepthBuffer.defdepth));
	return(base);
    }

    private void display(UI ui, UI layer, boolean layerhot, Render buf) {
	Pipe base = basestate();
	base.prep(FragColor.blend(new BlendMode()));
	Area wnd = Area.sized(ui.root.sz);
	base.prep(new States.Viewport(wnd)).prep(new Ortho2D(wnd));
	base.prep(new FrameInfo());
	buf.clear(base, FragColor.fragcol, FColor.BLACK);
	GOut g = new GOut(buf, base, wnd.sz());
	// addon: spatial UI (spec 044, task 044.1). Every widget standing in the 3D world is drawn into its own
	// offscreen texture HERE, ahead of the traversal below -- and the 3D scene is drawn INSIDE that traversal
	// (the MapView is a widget), so the commands that write a surface's texture precede the commands that
	// sample it in this same frame's Render. One stream, in order: same frame, never one frame stale. Off
	// (nothing standing), it is one empty-list check.
	io.brodgar.addon.AddonManager.drawSurfaces(ui, buf);
	// addon: the "ui2d" named pass (spec 019, task 019.6). It brackets the WHOLE widget draw, of which
	// the 3D scene is a part (the MapView is a widget) -- so shadow/scene nest inside it and are
	// subtracted out at snapshot time, leaving ui2d meaning what its name says. try/finally because a
	// pass left open would leave a GL timestamp query that never completes, stalling every later frame.
	// 074.1: both trees are inside it -- the addon layer is 2D UI by every measure the pass names.
	io.brodgar.prof.Passes.begin(buf, io.brodgar.prof.Passes.UI2D);
	try {
	    synchronized(ui) {
		ui.draw(g);
	    }
	    /* addon: (074.1) THE LAYER IS DRAWN LAST, over the session and over the login screen when there is
	     * no session -- "above everything" with no exception, since a window that vanished at a logout was
	     * living in a session after all. Its own monitor, taken after the session's is given up. */
	    synchronized(layer) {
		layer.draw(g);
	    }
	} finally {
	    io.brodgar.prof.Passes.end(buf, io.brodgar.prof.Passes.UI2D);
	}
	if(dbtext.get())
	    drawstats(ui, g, buf);
	/* addon: (074.1) the tip and the cursor are the tree's that answered the hover: over an addon window
	 * they are the layer's, and everywhere else the session's. Two answers to "what is under the pointer"
	 * is what one pointer cannot have. */
	UI hot = layerhot ? layer : ui;
	drawtooltip(hot, g);
	drawcursor(hot, g);
    }

    public static class Fence implements Runnable, Abortable {
	private int state = 0;

	public void run() {
	    synchronized(this) {
		state = 1;
		notifyAll();
	    }
	}

	public void abort() {
	    synchronized(this) {
		state = 2;
		notifyAll();
	    }
	}

	public boolean waitfor() throws InterruptedException {
	    synchronized(this) {
		while(state == 0)
		    wait();
		return(state == 1);
	    }
	}
    }

    public static class RenderProfile implements Runnable {
	private final CPUProfile prof;
	private RenderProfile prev;
	private CPUProfile.Frame frame;

	public RenderProfile(CPUProfile prof, RenderProfile prev, Render out) {
	    this.prof = prof;
	    this.prev = prev;
	    out.fence(this);
	}

	public void run() {
	    if(prev != null) {
		if(prev.frame != null) {
		    /* The reason frame would be null is if the
		     * environment has become invalid and the previous
		     * cycle never ran. */
		    prev.frame.fin();
		}
		prev = null;
	    }
	    frame = prof.new Frame();
	}

	public class Part implements Runnable {
	    private final String label;

	    public Part(String label, Render out) {
		this.label = label;
		out.fence(this);
	    }

	    public void run() {
		if(frame != null)
		    frame.part(label);
	    }
	}
    }

    protected class Dropper implements DropHandler {
	public Action drophover(DropHoverEvent ev) {
	    if(DropTarget.drophover(ui.root, ev.wndc(), SystemDrop.of(ev)))
		return(DropHandler.Action.COPY);
	    return(null);
	}
	public boolean dropped(DroppedEvent ev) {
	    return(DropTarget.dropthing(ui.root, ev.wndc(), SystemDrop.of(ev)));
	}
    }

    /* addon: (074.1) input goes to two trees, and the order is the drawing order upside down: the LAYER is
     * offered the event first and the session under it sees only what the layer did not consume. */
    protected abstract void dispatch(UI layer, UI ui);

    protected AudioSystem.SinkLine audiosink() {
	return(DummyAudio.DummySink.instance);
	// return(AudioSystem.instance().sinkline(Audio.defspec()));
    }

    protected boolean bgmode() {
	return(false);
    }

    protected double framedur() {
	/* rts: (071.1) the DRAWN UI's settings, which is where the player changes them. `ui` is the login
	 * screen and holds the copy it loaded when it was built, so reading it here would cap the frame
	 * rate at whatever the FPS setting was before the session on screen was logged in. */
	GSettings gp = drawn().gprefs;
	double hz = gp.hz.val, bghz = gp.bghz.val;
	if(bgmode()) {
	    if(bghz != Double.POSITIVE_INFINITY)
		return(1.0 / bghz);
	}
	if(hz == Double.POSITIVE_INFINITY)
	    return(0.0);
	return(1.0 / hz);
    }

    private final double[] frames = new double[128], waited = new double[frames.length];
    private int fps;
    private double framelag, uidle;
    protected void updstats(Frame f) {
	int fi = (int)(f.frameno % frames.length);
	frames[fi] = f.ftime;
	waited[fi] = f.waited;
	double twait = 0;
	int i = 0, ckf = fi;
	for(; i < frames.length - 1; i++) {
	    twait += waited[ckf];
	    if(f.ftime - frames[ckf] > 1)
		break;
	    ckf = (ckf - 1 + frames.length) % frames.length;
	}
	if(f.ftime > frames[ckf]) {
	    fps = (int)Math.round(i / (f.ftime - frames[ckf]));
	    uidle = twait / (f.ftime - frames[ckf]);
	}
    }

    protected void framedone(Frame f) {
	updstats(f);
	// addon: the end-of-frame handoff to the profiling ring (spec 019, task 019.2). Off, this is one
	// read of a static volatile boolean and nothing else. On, it hands over the frame parts the client
	// has ALREADY built -- uprof (this frame, finished by Frame.fin above), rprof (the last completed
	// render-thread frame; it closes a frame late by design) and this frame's gprof frame, whose GL
	// timestamps come back through fences several frames later and are folded in by frame number.
	// fps/uidle/framelag are passed as arguments rather than made visible: nothing else may write them.
	// 019.7: the guard is the MASTER switch, not the per-frame one -- a control frame runs with the probes
	// disarmed but must still hand its work time over, since that comparison is the measured overhead.
	if(io.brodgar.prof.Prof.sampling && (f.prof != null))
	    io.brodgar.prof.Prof.frame(f.frameno, f.ftime, uprof.last(), rprof.last(), f.gprof, fps, uidle, framelag);
    }

    public static class Frame {
	public final UILoop loop;
	public final long frameno;
	public final UI ui;
	public final Render out;
	public final Fence sync = new Fence();
	public Frame prev;
	public CPUProfile.Current prof = null;
	public GPUProfile.Frame gprof = null;
	public RenderProfile rprofc = null;
	public double ttime, ftime, waited;

	public Frame(UILoop loop, UI ui, Render out, Frame prev) {
	    this.loop = loop;
	    this.frameno = loop.frameno++;
	    this.ui = ui;
	    this.out = out;
	    this.prev = prev;
	}

	/* addon: (074.1) whether the pointer is in the addon layer -- settled by the hover below, in the tick,
	 * and read by the draw, which is the same frame. The tooltip and the cursor belong to whichever tree
	 * answered the hover, so this is that answer carried the few lines to where they are drawn. */
	public boolean layerhot = false;

	protected void tick() {
	    /* addon: (074.1) TWO TREES, ONE FRAME. The layer is attended alongside the session on screen: it is
	     * ticked, resized and hovered like any tree, and it is offered the input first. What it is NOT given
	     * is the ctick/gtick pair below -- it has no Glob, because it has no session.
	     *
	     * One monitor at a time, and never both: the frame takes the layer's, gives it up, then takes the
	     * session's. Nesting them would invent a second lock direction for the addon layer's own Lua to
	     * deadlock against, since a handler running under the layer's monitor reaches into a session's tree
	     * to read it (docs/client/multi-session.md's one lock direction). */
	    UI layer = loop.layer;
	    Coord sz = loop.wnd.size();
	    CPUProfile.phase(prof, "dwait");
	    if(rprofc != null) rprofc.new Part("tick", out);
	    if(gprof  != null) gprof.part(out, "tick");
	    loop.dispatch(layer, ui);
	    CPUProfile.phase(prof, "ltick");
	    synchronized(layer) {
		layer.tick();
		layer.gtick(out);
		layerhot = layer.mousehover(layer.mc);
		if(!layer.root.sz.equals(sz))
		    layer.root.resize(sz);
	    }
	    synchronized(ui) {
		CPUProfile.phase(prof, "stick");
		if(ui.sess != null) {
		    ui.sess.glob.ctick();
		    ui.sess.glob.gtick(out);
		}
		CPUProfile.phase(prof, "utick");
		ui.tick();
		ui.gtick(out);
		ui.mousehover(ui.mc, !layerhot);   // addon: (074.1) nothing hovers through an addon window
		if(!ui.root.sz.equals(sz))
		    ui.root.resize(sz);
	    }
	    /* rts: the background sessions (F0, specs/rts/plan.md). OUTSIDE the anchor's monitor,
	     * and each member under its own, so no two UI monitors are ever held at once -- the Loader
	     * threads take exactly one, so there is no cycle to make. No gtick and no resize: a member has
	     * nothing in a render tree and no pixels of its own. Its own phase, because the whole point of
	     * F0 is to read what a second session costs. No members = one list check. */
	    CPUProfile.phase(prof, "sessions");
	    io.brodgar.session.Sessions.tick();
	}

	protected void display() {
	    CPUProfile.phase(prof, "draw");
	    if(rprofc != null) rprofc.new Part("draw", out);
	    // addon: the named-pass tier (spec 019, task 019.6). The frame's "draw" GPU part is now CAPTURED and
	    // handed to Passes, which hangs shadow/scene/ui2d UNDER it -- so the pass timestamps nest inside the
	    // client's own tree instead of splitting its tick/draw/swap sequence, and Profwnd shows exactly what
	    // it did with three extra rows. Disarmed, this is a null argument and one branch in Passes.frame.
	    // 019.7: the "draw" part is the CLIENT's own and is created exactly as it always was -- withholding
	    // it on a control frame would leave the client's GPU tree missing a part, which is Profwnd's data,
	    // not ours. It is only kept FROM Passes on a control frame, so the pass tier's own timestamp queries
	    // are the thing absent from the control and therefore the thing it measures.
	    GPUProfile.Part gdraw = (gprof != null) ? gprof.part(out, "draw") : null;
	    io.brodgar.prof.Passes.frame(io.brodgar.prof.Prof.on ? gdraw : null);
	    loop.display(ui, loop.layer, layerhot, out);
	}

	protected void swapbuffers() {
	    if(rprofc != null) rprofc.new Part("swap", out);
	    if(gprof  != null) gprof.part(out, "swap");
	    loop.wnd.swapbuffers(out, ui.gprefs.vsync.val);
	    out.fence(() -> loop.framelag = Utils.rtime() - ttime);
	    if(gprof  != null) gprof.fin(out);
	}

	protected void fin() throws InterruptedException {
	    CPUProfile.phase(prof, "wait");
	    double now = Utils.rtime();
	    double fd = loop.framedur();
	    if((prev != null) && (prev.ftime + fd > now)) {
		this.ftime = prev.ftime + fd;
		long nanos = (long)((this.ftime - now) * 1e9);
		Thread.sleep(nanos / 1000000, (int)(nanos % 1000000));
		waited += this.ftime - now;
	    } else {
		this.ftime = now;
	    }
	    CPUProfile.end(prof);
	}

	protected void syncwait() throws InterruptedException {
	    CPUProfile.phase(prof, "dwait");
	    if(prev != null) {
		double then = Utils.rtime();
		prev.sync.waitfor();
		waited += Utils.rtime() - then;
	    }
	}

	public void run() throws InterruptedException {
	    this.prof   = profile.get() ? CPUProfile.set(loop.uprof.new Frame()) : null;
	    this.gprof  = profile.get() ? loop.gprof.new Frame(out) : null;
	    this.rprofc = profile.get() ? new RenderProfile(loop.rprof, (prev == null) ? null : prev.rprofc, out) : null;
	    SyncMode syncmode = ui.gprefs.syncmode.val;
	    boolean swapsync = (syncmode != SyncMode.FRAME);
	    boolean tickwait = (syncmode == SyncMode.FRAME) || (syncmode == SyncMode.TICK);

	    if(!swapsync) out.fence(sync);
	    if(!tickwait) syncwait();
	    ttime = Utils.rtime();
	    tick();
	    if(tickwait) syncwait();
	    display();
	    CPUProfile.phase(prof, "aux");
	    swapbuffers();
	    if(swapsync) out.fence(sync);
	}
    }

    public static class GLFrame extends Frame {
	public final GLRender gl;
	private final haven.render.gl.BufferBGL.Profile frameprof = false ? new haven.render.gl.BufferBGL.Profile() : null;

	public GLFrame(UILoop loop, UI ui, GLRender out, Frame prev) {
	    super(loop, ui, out, prev);
	    this.gl = out;
	    if(frameprof != null) gl.submit(frameprof.start);
	}

	protected void swapbuffers() {
	    super.swapbuffers();
	    if(ui.gprefs.syncmode.val == SyncMode.FINISH) {
		if(rprofc != null) rprofc.new Part("finish", out);
		gl.finish();
	    }
	    if(frameprof != null) {
		gl.submit(frameprof.stop);
		gl.submit(frameprof.dump(Utils.path("frameprof")));
	    }
	}
    }

    protected Frame frame(UI ui, Render out, Frame prev) {
	if(out instanceof GLRender)
	    return(new GLFrame(this, ui, (GLRender)out, prev));
	return(new Frame(this, ui, out, prev));
    }

    private void run() {
	Render buf = null;
	try {
	    Frame prevframe = null;
	    double then = Utils.rtime();
	    while(true) {
		Environment env = wnd.env();
		if(env != this.env)
		    setenv(env);
		buf = env.render();
		try {
		    UI ui;
		    synchronized(uilock) {
			this.lockedui = ui = drawn();   // rts: (F5)
			uilock.notifyAll();
		    }
		    Debug.cycle(ui.modflags());

		    // addon: open the frame for the profiler (spec 019, task 019.7). This is where the
		    // control-frame decision has to be made: everything the probes hang off is created in the
		    // Frame constructor below, so a flip any later would leave the frame half-armed. Off, it is
		    // one read of a static volatile boolean.
		    io.brodgar.prof.Prof.begin();
		    Frame curframe = frame(ui, buf, prevframe);
		    prevframe = null;
		    curframe.run();
		    env.submit(buf); buf = null;
		    curframe.fin();

		    framedone(curframe);
		    (prevframe = curframe).prev = null;
		} finally {
		    if(buf != null)
			buf.dispose();
		}
	    }
	} catch(InterruptedException e) {
	} finally {
	    synchronized(uilock) {
		lockedui = null;
		uilock.notifyAll();
	    }
	}
    }

    public void dispose() {
	th.interrupt();
	try {
	    th.join(5000);
	} catch(InterruptedException e) {
	    Thread.currentThread().interrupt();
	}
	if(th.isAlive())
	    Warning.warn("ui thread failed to terminate");
    }

    private Map<String, Console.Command> cmdmap = new TreeMap<String, Console.Command>();
    {
	cmdmap.put("stats", (cons, args) -> {
	    dbtext.set(Utils.parsebool(args[1]));
	});
	cmdmap.put("profile", (cons, args) -> {
	    // addon: one switch, not two (spec 019, task 019.1) — Prof.arm sets `profile` as well as the
	    // addon-side master switch and the pref, so :profile, the Options "Client" checkbox and
	    // hafen.client:options():client():profiling() can never show different states.
	    io.brodgar.prof.Prof.arm(Utils.parsebool(args[1]));
	});
	cmdmap.put("renderer", new Console.Command() {
	    public void run(Console cons, String[] args) {
		cons.out.printf("Toolkit: %s\n", wnd.toolkit().description());
		if(env != null) {
		    Environment.Caps caps = env.caps();
		    cons.out.printf("Rendering device: %s, %s\n", caps.vendor(), caps.device());
		    cons.out.printf("Driver version: %s\n", caps.driver());
		}
	    }
	});
    }
    public Map<String, Console.Command> findcmds() {
	return(cmdmap);
    }
}
