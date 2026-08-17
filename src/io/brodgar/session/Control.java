package io.brodgar.session;

import java.awt.Color;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import haven.Coord;
import haven.Coord2d;
import haven.Coord3f;
import haven.GOut;
import haven.Glob;
import haven.Gob;
import haven.MapView;
import haven.UI;
import haven.Widget;

/**
 * The RTS layer: who is selected, and what a click means while it is on.
 *
 * <p>{@link Sessions} holds the sessions; this holds the <em>control</em>. The two are separate because
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
	public final Sessions.Member member;   // null means the anchor's own character
	public final Coord2d rc;            // in the ANCHOR's frame -- there is no other frame on screen
	public final Coord sc;              // projected, or null when it cannot be placed on screen

	Unit(long gobid, Sessions.Member member, Coord2d rc, Coord sc) {
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
	for(Sessions.Placed ss : Sessions.placed()) {
	    Coord2d rc = ss.charpos();
	    if(rc != null)
		add(ret, mv, ss.gui.plid, rc, ss.member);
	}
	return(ret);
    }

    private static void add(List<Unit> ret, MapView mv, long id, Coord2d rc, Sessions.Member m) {
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

    /**
     * Is there anybody to command who is not simply the character on screen?
     *
     * <p>Ordering your own character to walk somewhere <em>is</em> a left click, so the mode must not
     * stand in front of one — and this is not a corner case but the state the client is in for most of
     * its life: {@link #take} makes the anchor's own character the whole selection on every switch, so
     * without this the very first thing a second session costs you is the left button, on every gob,
     * for as long as the mode is on. The mode adds a recipient; when the recipient is the character
     * already receiving your clicks, it adds nothing and stays out of the way.
     */
    private static boolean commands() {
	List<Long> ids = selection();
	if(ids.isEmpty())
	    return(false);
	if(ids.size() > 1)
	    return(true);
	Sessions.Placed an = Sessions.anchorsess();
	return((an == null) || (ids.get(0) != an.gui.plid));
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

    /**
     * Make one character the whole selection, whatever was selected before. The list's own gesture:
     * naming a character is naming exactly one, and anything else left selected would take the next
     * order with it.
     */
    public static void only(long gobid) {
	synchronized(Control.class) {
	    sel.clear();
	    sel.add(gobid);
	}
	Sessions.say("selected: %s", names());
    }

    private static void select(List<Unit> us, boolean add) {
	synchronized(Control.class) {
	    if(!add)
		sel.clear();
	    for(Unit un : us)
		sel.add(un.gobid);
	}
	if(us.isEmpty() && !add)
	    Sessions.say("selection cleared");
	else
	    Sessions.say("selected: %s", names());
    }

    private static String names() {
	List<Long> ids = selection();
	if(ids.isEmpty())
	    return("nothing");
	StringBuilder buf = new StringBuilder();
	for(Long id : ids) {
	    if(buf.length() > 0)
		buf.append(", ");
	    Sessions.Placed ss = Sessions.bysess(id);
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
	if((ev.b == 1) && commands()) {
	    /* The left button alone, and it means one thing: walk there. The destination is resolved by
	     * the client's OWN pick pass -- the same machinery a real click uses -- so an order lands
	     * exactly where a click would have. It answers a frame or two later, on the UI thread, in
	     * Control.hit below, and it is that answer that decides whether there was an order at all:
	     * a click that landed on a gob is handed straight back to the map view. */
	    mv.new ClickOrder(ev.c, ev.b, mods).run();
	    return(true);
	}
	/* Nothing selected, or a button that is not the left one: the map view does what it has always
	 * done. The right button in particular is never an order -- interacting with what is under the
	 * cursor is the drawn character's own business, and stays where the player can see it. */
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
	if(a.dist(b) > slop) {
	    /* A box is a group gesture: it says who is commanded and nothing about whose screen this is. */
	    select(inside(units(mv), a, b), add);
	    return(true);
	}
	List<Unit> us = nearest(units(mv), b);
	if(!add && !us.isEmpty()) {
	    /* Naming ONE character, which is the switcher window's gesture said on the map instead of in
	     * the list -- so it does what the list does: its screen, and it alone selected. Extending with
	     * shift or ctrl is not naming one, and a click on empty ground clears; both fall through. */
	    take(us.get(0).member);
	    return(true);
	}
	select(us, add);
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
     * The move order, once the pick pass has said where the cursor actually was. Every selected unit
     * is sent the same destination, named in the anchor's coordinates; {@link Sessions} translates it
     * per session.
     *
     * <p>A destination is <b>all</b> that travels. What the pick found under the cursor is not asked
     * for and not sent: the one thing this layer ever says to another login is where to walk, and a
     * click that means anything else stays with the character on screen.
     */
    public static void hit(Coord2d mc, int mods) {
	List<Long> ids = selection();
	if(ids.isEmpty())
	    return;   /* it was let through to the map view in the first place; nothing to say */
	int n = 0;
	for(Long id : ids) {
	    if(Sessions.orderunit(id, mc, mods))
		n++;
	}
	if(n < ids.size())
	    Sessions.say("ordered %d of %d -- the rest are unanchored or not in the world", n, ids.size());
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

    /**
     * Take the mode on or off. Package-visible because {@link Sessions#tick} derives it from the
     * membership and is the only caller there is.
     *
     * <p><b>The camera is not the mode's.</b> A second session changes who can be commanded and
     * nothing about how the world is looked at, so no view is touched here and none is remembered to
     * be put back: whatever camera each character was being played with is the camera it keeps. The
     * RTS camera ({@link haven.MapView.RTSCam}) stays a camera among the client's own, installed by
     * hand with {@code :cam rts} by whoever wants it, mode or no mode.
     */
    static void mode(boolean v) {
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
	Sessions.say("rts mode %s", v ? "on -- alt-click or alt-drag selects, a left click walks the selection" : "off");
    }

    /**
     * Go to a character: give it the screen, and make it the whole selection. The one gesture behind
     * every way of naming a single character — the switcher window's buttons, an ALT-click on one of
     * them in the world, the {@code rts-next-anchor} key and {@code :session anchor} — because they
     * are the same intent spelled four times, and a switch that left the previous character selected
     * would send the next order to somebody off screen.
     *
     * <p>Nothing here duplicates {@link Sessions#anchor}: that call is the whole UI switch, since every
     * other part of the layer reads {@code Sessions.anchor()} and follows by itself. What is added is
     * the selection, which the anchor knows nothing about — and <b>only</b> that. Each session's camera
     * is its own and is left exactly as the player had it, here as everywhere else in this layer.
     *
     * @param m the member to go to, or {@code null} for the main session.
     */
    public static void take(Sessions.Member m) {
	Sessions.anchor(m);
	/* Already the anchor is not a failure -- the selection still follows. A switch that did NOT
	 * happen (a session with no screen yet) is, and it leaves the character on screen alone rather
	 * than selecting somebody the player did not ask for. */
	if(Sessions.anchormember() != m)
	    return;
	/* Asked of the anchor rather than of m: the same character either way, and this spelling is
	 * uniform over the main session, which has no Member to ask. */
	Sessions.Placed ss = Sessions.anchorsess();
	if((ss == null) || !on)
	    return;
	only(ss.gui.plid);
    }

    /**
     * rts: (F5) keys the RTS layer owns, ahead of the camera's. {@code MapView.keydown} calls this
     * before {@code camera.keydown}, so a key the mode owns is dispatched here whatever camera is
     * installed — and the camera answers the client's own {@code cam-*} bindings and nothing else.
     */
    public static boolean keydown(MapView mv, Widget.KeyDownEvent ev) {
	if(!on)
	    return(false);
	if(MapView.kb_rtsnext.key().match(ev)) {
	    Sessions.next();
	    return(true);
	}
	if(MapView.kb_rtsfocus.key().match(ev)) {
	    focus(mv);
	    return(true);
	}
	return(false);
    }

    /**
     * Centre the camera on the selection — the space bar, and the RTS gesture. With nothing selected
     * it centres on every session instead, which is what you want when you have lost track of them.
     */
    public static void focus(MapView mv) {
	if(!(mv.camera instanceof MapView.RTSCam)) {
	    Sessions.say("not on the rts camera");
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
	    Sessions.say("nothing to centre on");
	    return;
	}
	((MapView.RTSCam)mv.camera).focus(Coord2d.of(x / n, y / n));
    }
}
