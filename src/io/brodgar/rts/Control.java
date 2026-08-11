package io.brodgar.rts;

import java.awt.Color;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import haven.Coord;
import haven.Coord2d;
import haven.Coord3f;
import haven.GOut;
import haven.GameUI;
import haven.Glob;
import haven.Gob;
import haven.MapView;
import haven.UI;
import haven.Widget;

/**
 * The RTS layer: who is selected, and what a click means while it is on.
 *
 * <p>{@link Fleet} holds the sessions; this holds the <em>control</em>. The two are separate because
 * they answer different questions — a session exists whether or not anyone is commanding it, and the
 * selection is about the anchor's screen, not about anybody's connection.
 *
 * <p>Everything here rests on the one fact that makes the whole project cheap: when the characters are
 * in the same area, <b>the anchor already sees them all</b>. Its {@code OCache} carries the other
 * accounts' characters as ordinary gobs, and gob ids are global, so a unit is found by asking the
 * anchor for the id the member's own {@code GameUI} reports. Nothing is composed, nothing extra is
 * rendered, and the picking machinery below is the client's own.
 *
 * <p>F3 of {@code specs/rts/plan.md}.
 */
public class Control {
    /**
     * While off, the map view behaves exactly as it always did. While on, <b>it still does</b>: the
     * mouse keeps Haven's own bindings and the mode adds exactly one gesture — {@link #MOD_SEL} plus
     * the left button selects. What the mode changes is the <em>recipient</em>: with units selected, a
     * click commands them instead of the character on screen.
     */
    public static volatile boolean on = false;

    /**
     * The one binding the mode adds: ALT. It is held to select — click a unit, or drag a box — and it
     * is the only thing standing between the marquee and Haven's left click, which keeps walking you
     * where you clicked exactly as it always did.
     */
    private static final int MOD_SEL = UI.MOD_META;

    private static final Set<Long> sel = new LinkedHashSet<Long>();
    private static Coord dragfrom = null, dragto = null;
    private static boolean dragging = false;
    private static UI.Grab grab = null;

    /** How near a click must land, in pixels, to count as picking a unit rather than starting a box. */
    private static final int pickrad = 24;
    private static final int slop = 5;

    /* ------------------------------------------------------------------ *
     * Units
     * ------------------------------------------------------------------ */

    /** A character the client can command, as the anchor sees it. */
    public static class Unit {
	public final long gobid;
	public final Fleet.Member member;   // null means the anchor's own character
	public final Coord2d rc;            // in the ANCHOR's frame -- there is no other frame on screen
	public final Coord sc;              // projected, or null when it cannot be placed on screen

	Unit(long gobid, Fleet.Member member, Coord2d rc, Coord sc) {
	    this.gobid = gobid;
	    this.member = member;
	    this.rc = rc;
	    this.sc = sc;
	}

	public String name() {
	    return((member == null) ? "main" : member.user);
	}
    }

    /**
     * Every commandable character, located on the anchor's screen.
     *
     * <p>The projection is {@code MapView.screenxf} — the client's own, the same one the party arrows
     * use. A unit that does not project into the view is carried with a null {@code sc}: it is still
     * commandable, it just cannot be clicked or boxed.
     */
    public static List<Unit> units(MapView mv) {
	List<Unit> ret = new ArrayList<Unit>();
	/* Every session, located by its OWN session and translated -- not by asking the anchor's
	 * OCache. The anchor stops streaming a character's gob the moment it walks out of range, and a
	 * unit that cannot be clicked then is a unit you cannot call back. Since F5 that cuts both
	 * ways: the main character is the one out of range while a member holds the screen. */
	for(Fleet.Sess ss : Fleet.sessions()) {
	    Coord2d rc = ss.charpos();
	    if(rc != null)
		add(ret, mv, ss.gui.plid, rc, ss.member);
	}
	return(ret);
    }

    private static void add(List<Unit> ret, MapView mv, long id, Coord2d rc, Fleet.Member m) {
	Coord sc = null;
	try {
	    Coord3f s = mv.screenxf(rc);
	    if(s != null) {
		Coord c = Coord.of((int)s.x, (int)s.y);
		if(c.isect(Coord.z, mv.sz))
		    sc = c;
	    }
	} catch(RuntimeException e) {
	    /* Loading, mostly: the ground under it is not there yet. It has no place on screen this frame. */
	}
	ret.add(new Unit(id, m, rc, sc));
    }

    /* ------------------------------------------------------------------ *
     * Selection
     * ------------------------------------------------------------------ */

    public static List<Long> selection() {
	synchronized(Control.class) {
	    return(new ArrayList<Long>(sel));
	}
    }

    public static boolean selected(long gobid) {
	synchronized(Control.class) {
	    return(sel.contains(gobid));
	}
    }

    public static void clear() {
	synchronized(Control.class) {
	    sel.clear();
	}
    }

    private static void select(List<Unit> us, boolean add) {
	synchronized(Control.class) {
	    if(!add)
		sel.clear();
	    for(Unit un : us)
		sel.add(un.gobid);
	}
	if(us.isEmpty() && !add)
	    Fleet.say("selection cleared");
	else
	    Fleet.say("selected: %s", names());
    }

    private static String names() {
	List<Long> ids = selection();
	if(ids.isEmpty())
	    return("nothing");
	StringBuilder buf = new StringBuilder();
	for(Long id : ids) {
	    if(buf.length() > 0)
		buf.append(", ");
	    Fleet.Sess ss = Fleet.bysess(id);
	    buf.append((ss == null) ? "?" : ss.user);
	}
	return(buf.toString());
    }

    /* ------------------------------------------------------------------ *
     * Input
     * ------------------------------------------------------------------ */

    public static boolean mousedown(MapView mv, Widget.MouseDownEvent ev) {
	if(!on)
	    return(false);
	int mods = mv.ui.modflags();
	if((ev.b == 1) && ((mods & MOD_SEL) != 0)) {
	    dragfrom = dragto = ev.c;
	    dragging = true;
	    grab = mv.ui.grabmouse(mv);
	    return(true);
	}
	if(((ev.b == 1) || (ev.b == 3)) && !selection().isEmpty()) {
	    /* The button travels with the order: what a click means is Haven's business, not ours, and
	     * the selection is only the list of characters it is asked of. The destination is resolved by
	     * the client's OWN pick pass -- the same machinery a real click uses -- so an order lands
	     * exactly where a click would have. It answers a frame or two later, on the UI thread, in
	     * Control.hit below. */
	    mv.new FleetClick(ev.c, ev.b, mods).run();
	    return(true);
	}
	/* Nothing selected, or the middle button: the map view does what it has always done -- the
	 * camera's drag stays the camera's, and a click still walks the character on screen. */
	return(false);
    }

    public static boolean mousemove(MapView mv, Widget.MouseMoveEvent ev) {
	if(!on || !dragging)
	    return(false);
	dragto = ev.c;
	return(true);
    }

    public static boolean mouseup(MapView mv, Widget.MouseUpEvent ev) {
	if(!on)
	    return(false);
	if(!dragging || (ev.b != 1))
	    return(false);
	dragging = false;
	if(grab != null) {
	    grab.remove();
	    grab = null;
	}
	Coord a = dragfrom, b = ev.c;
	dragfrom = dragto = null;
	if((a == null) || (b == null))
	    return(true);
	/* Shift or ctrl -- alongside the ALT already held -- extends instead of replacing. */
	boolean add = (mv.ui.modflags() & (UI.MOD_SHIFT | UI.MOD_CTRL)) != 0;
	if(a.dist(b) < slop)
	    select(nearest(units(mv), b), add);
	else
	    select(inside(units(mv), a, b), add);
	return(true);
    }

    /**
     * A click picks the nearest unit within {@link #pickrad} pixels — deliberately not the pick pass.
     * We only ever select our own characters and we know exactly where they are, so projecting them
     * and measuring is both cheaper and more forgiving than rasterizing a click-map.
     */
    private static List<Unit> nearest(List<Unit> us, Coord c) {
	List<Unit> ret = new ArrayList<Unit>();
	Unit best = null;
	double bd = pickrad;
	for(Unit un : us) {
	    if(un.sc == null)
		continue;
	    double d = un.sc.dist(c);
	    if(d <= bd) {
		bd = d;
		best = un;
	    }
	}
	if(best != null)
	    ret.add(best);
	return(ret);
    }

    private static List<Unit> inside(List<Unit> us, Coord a, Coord b) {
	Coord ul = Coord.of(Math.min(a.x, b.x), Math.min(a.y, b.y));
	Coord br = Coord.of(Math.max(a.x, b.x), Math.max(a.y, b.y));
	List<Unit> ret = new ArrayList<Unit>();
	for(Unit un : us) {
	    if((un.sc != null) && (un.sc.x >= ul.x) && (un.sc.x <= br.x) && (un.sc.y >= ul.y) && (un.sc.y <= br.y))
		ret.add(un);
	}
	return(ret);
    }

    /**
     * The order, once the pick pass has said where the cursor actually was. Every selected unit is
     * sent the same destination, named in the anchor's coordinates; {@link Fleet} translates it per
     * member. A gob under the cursor is passed by <em>id</em>, never by the anchor's coordinates for
     * it — {@code GobClick.clickargs} encodes the observer's own frame, so each unit rebuilds those
     * arguments from its own {@code OCache}.
     *
     * <p>The button is the one that was pressed, so each unit receives the very message that button
     * would have produced for the character on screen.
     */
    public static void hit(MapView mv, Coord pc, Coord2d mc, long targetgob, int btn, int mods) {
	List<Long> ids = selection();
	if(ids.isEmpty())
	    return;   /* it was let through to the map view in the first place; nothing to say */
	int n = 0;
	for(Long id : ids) {
	    if(Fleet.orderunit(id, mc, targetgob, btn, mods))
		n++;
	}
	if(n < ids.size())
	    Fleet.say("ordered %d of %d -- the rest are unanchored or not in the world", n, ids.size());
    }

    /* ------------------------------------------------------------------ *
     * Drawing
     * ------------------------------------------------------------------ */

    private static final Color c_sel = new Color(64, 255, 64, 255);
    private static final Color c_unsel = new Color(255, 255, 255, 96);
    private static final Color c_box = new Color(64, 255, 64, 200);
    private static final Color c_boxfill = new Color(64, 255, 64, 32);

    public static void draw(MapView mv, GOut g) {
	if(!on)
	    return;
	for(Unit un : units(mv)) {
	    if(un.sc == null)
		continue;
	    boolean s = selected(un.gobid);
	    g.chcolor(s ? c_sel : c_unsel);
	    int r = s ? 14 : 9;
	    g.rect2(un.sc.sub(r, r), un.sc.add(r, r));
	}
	if(dragging && (dragfrom != null) && (dragto != null)) {
	    Coord ul = Coord.of(Math.min(dragfrom.x, dragto.x), Math.min(dragfrom.y, dragto.y));
	    Coord br = Coord.of(Math.max(dragfrom.x, dragto.x), Math.max(dragfrom.y, dragto.y));
	    g.chcolor(c_boxfill);
	    g.frect2(ul, br);
	    g.chcolor(c_box);
	    g.rect2(ul, br);
	}
	g.chcolor();
    }

    /* ------------------------------------------------------------------ *
     * The switch
     * ------------------------------------------------------------------ */

    private static final java.util.Map<MapView, MapView.Camera> prevcam = new java.util.HashMap<MapView, MapView.Camera>();

    public static void mode(boolean v) {
	on = v;
	if(!v) {
	    dragging = false;
	    dragfrom = dragto = null;
	    if(grab != null) {
		grab.remove();
		grab = null;
	    }
	    clear();
	}
	/* The camera comes with the mode. Playing a character and commanding a group want different
	 * cameras, and asking the maintainer to remember `:cam fleet` beside `:fleet rts on` would be
	 * two switches for one decision. The previous one is put back on the way out, and neither is
	 * written to the `defcam` pref -- this is a mode, not a preference. */
	if(v) {
	    recam();
	} else {
	    for(java.util.Map.Entry<MapView, MapView.Camera> e : prevcam.entrySet())
		e.getKey().camera = e.getValue();
	    prevcam.clear();
	}
	Fleet.say("rts mode %s", v ? "on -- alt-click or alt-drag selects, the usual clicks command the selection" : "off");
    }

    /**
     * rts: (F5) give the session now on screen the fleet camera, remembering what it had. Called on
     * every anchor switch as well as when the mode goes on, because each session has its own MapView
     * and therefore its own camera -- there is no one camera to move across.
     */
    public static void recam() {
	if(!on)
	    return;
	GameUI gui = Fleet.anchorgameui();
	MapView mv = (gui == null) ? null : gui.map;
	if((mv == null) || (mv.camera instanceof MapView.FleetCam))
	    return;
	prevcam.put(mv, mv.camera);
	mv.camera = mv.new FleetCam();
    }

    /** rts: (F5) keys the RTS layer owns, ahead of the camera's. */
    public static boolean keydown(MapView mv, Widget.KeyDownEvent ev) {
	if(!on)
	    return(false);
	if(MapView.kb_rtsnext.key().match(ev)) {
	    Fleet.next();
	    return(true);
	}
	return(false);
    }

    /**
     * Centre the camera on the selection — the space bar, and the RTS gesture. With nothing selected
     * it centres on the whole fleet instead, which is what you want when you have lost track of them.
     */
    public static void focus(MapView mv) {
	if(!(mv.camera instanceof MapView.FleetCam)) {
	    Fleet.say("not on the fleet camera");
	    return;
	}
	List<Unit> us = units(mv);
	List<Long> ids = selection();
	double x = 0, y = 0;
	int n = 0;
	for(Unit un : us) {
	    if(!ids.isEmpty() && !ids.contains(un.gobid))
		continue;
	    x += un.rc.x;
	    y += un.rc.y;
	    n++;
	}
	if(n == 0) {
	    Fleet.say("nothing to centre on");
	    return;
	}
	((MapView.FleetCam)mv.camera).focus(Coord2d.of(x / n, y / n));
    }
}
