package io.brodgar.session;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import haven.AddonWidgets;
import haven.AuthClient;
import haven.Bootstrap;
import haven.Charlist;
import haven.Connection;
import haven.Coord;
import haven.Coord2d;
import haven.GameUI;
import haven.Glob;
import haven.Gob;
import haven.Loading;
import haven.MCache;
import haven.MapFile;
import haven.MapView;
import haven.MiniMap;
import haven.OCache;
import haven.HackThread;
import haven.NamedSocketAddress;
import haven.RemoteUI;
import haven.Session;
import haven.UI;
import haven.UILoop;
import haven.Utils;
import haven.Warning;

/**
 * The game sessions this client holds open, of which it draws one.
 *
 * <p>Every game session the client holds is a <em>member</em> of this one list, the one on screen
 * included: a real {@link Session} with a real {@link UI} and a real widget tree, connected, ticked and
 * answering the server. Exactly one of them is the <em>anchor</em>, the session the frame draws and
 * dispatches to; the rest are live with no view of their own. {@code UILoop.ui} holds the <b>login
 * screen</b> and no game session at all — {@code Client.Main} logs one in and hands it to
 * {@link #adopt}, so no session arrives differently from the others and none is privileged. When the
 * characters stand in the same area the anchor already sees them all — its {@code OCache} receives
 * every nearby object — so there is nothing to compose, and a session that is not drawn exists to be
 * <em>commanded</em>, not to be looked at.
 *
 * <p>All-static, mirroring {@code io.brodgar.addon.AddonManager}: there is one client process and one
 * set of sessions in it. {@link #tick()} runs on the UI thread from {@code UILoop.Frame.tick}, outside the
 * anchor's monitor; each member is ticked under its own, so no two UI monitors are ever held at once.
 *
 * <p>This is F0 of {@code specs/rts/plan.md}: the scaffolding, and the phase that measures whether the
 * whole design is affordable. There is no selection, no order and no shared view here — only sessions
 * that stay alive and cost something measurable.
 */
public class Sessions {
    private static volatile UILoop loop;
    private static final List<Member> members = new CopyOnWriteArrayList<Member>();

    /** Called once from the {@code UILoop} constructor: the sessions layer needs the loop to build a UI. */
    public static void init(UILoop loop_) {
	loop = loop_;
    }

    public static List<Member> members() {
	return(new ArrayList<Member>(members));
    }

    /**
     * How many game sessions the client holds right now, the one on screen included.
     *
     * <p>A <b>gauge</b>, not a total: it goes up when a session joins and down when one ends, and the
     * login screen is not one of them — with nobody logged in it is zero. It is the one number that
     * makes the set readable from outside the layer, which is what
     * {@code hafen.client():profiling():session()} publishes it for.
     */
    public static int live() {
	return(members.size());
    }

    /**
     * Is this session one the client holds but does not draw? Asked where only the {@code Glob} is in
     * hand. A session is dormant when it is not the one being drawn — which is this one list minus
     * whichever member currently holds the screen, and no second place to look: every game session the
     * client holds is in it.
     */
    public static boolean dormant(Glob glob) {
	if(glob == null)
	    return(false);
	if(glob == anchorglob())
	    return(false);
	for(Member m : members) {
	    Session s = m.sess;
	    if((s != null) && (s.glob == glob))
		return(true);
	}
	return(false);
    }

    /**
     * The session running this world, or null when no live member does.
     *
     * <p>Asked where a seam is handed a {@link Glob} and nothing else — a gob's decoration arriving on a
     * loader thread, a {@link MapView} whose constructor has not been added to a tree yet — and has to say
     * which session that is. The alternative at those two sites is {@link #anchor()}, which would file one
     * session's things under whichever one holds the screen, and that is exactly what a per-session cache
     * exists to stop. A walk of a list with as many entries as the client has logins.
     */
    public static UI uifor(Glob glob) {
	if(glob == null)
	    return(null);
	for(Member m : members) {
	    UI u = m.ui;
	    if((u != null) && (u.sess != null) && (u.sess.glob == glob))
		return(u);
	}
	return(null);
    }

    /* ------------------------------------------------------------------ *
     * The per-frame tick
     * ------------------------------------------------------------------ */

    /**
     * Advance every member by one frame, and publish where they all are. Deliberately no {@code gtick}:
     * a member has nothing in a render tree, so there is no render stream of its own to feed. No members
     * at all is one list check and the publication below.
     */
    public static void tick() {
	flushsay();
	reclaim();          // rts: (F5)
	applymute();        // rts: (F6)
	tickmode();         // rts: (F3) the mode, derived from the membership, outside the branch below
	if(!members.isEmpty()) {
	    UI an = anchor();
	    /* rts: (109.2) the member the offsets are measured against, taken once. Every offset is now the
	     * difference of two bases and the anchor's is one of them, so the loop below needs the member and
	     * not merely its Glob. */
	    Member anm = anchormember();
	    /* rts: (109.1) the base pass, and a loop of its own precisely because it must reach EVERY member.
	     * The loop below skips the one holding the screen -- the frame has already ticked that session in
	     * full -- and the anchor's base is one half of every difference between two frames, so a base
	     * derived inside that loop would be derived for everybody except the session the rest are measured
	     * against. It runs ahead of the tick rather than after it: a member's own sessloc is then a frame
	     * old, which only ever lengthens the window in which a base is refused, and the offset the loop
	     * below derives needs the anchor's base to be there already. */
	    for(Member m : members) {
		try {
		    m.tickbase();
		} catch(RuntimeException e) {
		    new Warning(e, String.format("session: base failed for %s", m.user)).issue();
		}
	    }
	    for(Member m : members) {
		UI u = m.ui;
		/* The member holding the anchor is ticked by the frame itself, in full. Ticking it again
		 * here would advance its clock twice a frame. */
		if((u == null) || m.dead || (u == an))
		    continue;
		try {
		    synchronized(u) {
			/* rts: (071.1) re-read under the UI's own monitor. A session ends on its own thread
			 * and clears `ui` before destroying it, and UI.destroy takes this same monitor -- so a
			 * UI the member still names while this is held is one that cannot be taken apart
			 * underneath the tick. Read outside it, the answer can be a frame old, which is a tick
			 * into a disposed widget tree. */
			if(m.ui != u)
			    continue;
			if(u.sess != null)
			    u.sess.glob.ctick();
			u.tick();
		    }
		    /* addon: (112.4) this member's addon step, OUTSIDE its own monitor and holding no other --
		     * the same move UILoop.Frame.tick makes for the session on screen, and for the same reason:
		     * a drain that fires Lua must be free to reach any tree, and a handler holding this one
		     * could only take a second. The member the anchor holds never reaches here (it is ticked by
		     * the frame, in full), so no session is stepped twice. */
		    io.brodgar.addon.AddonManager.tick(u);
		    m.autoplay(u);
		    m.tickoffset(anm);
		} catch(RuntimeException e) {
		    new Warning(e, String.format("session: tick failed for %s", m.user)).issue();
		}
	    }
	}
	republish();
    }

    /** What the membership last asked the mode to be, so that only a change acts. */
    private static boolean modeon = false;

    /**
     * The RTS mode, derived rather than switched. It is wanted exactly while there is a second
     * session to command, so <em>more than one member</em> is the switch and there is no console verb
     * for it: a verb would be a second authority over one boolean, and the one the user typed is the
     * one that goes stale the moment a session joins.
     *
     * <p>The count, and not merely a non-empty list: since the handoff the session on screen is a
     * member too, so one member is the ordinary single-character client and turning the mode on for it
     * would cost the player the left button for a selection of exactly themselves.
     *
     * <p>Only the edge acts. {@link Control#mode} clears the selection, so calling it on a frame that
     * changed nothing would wipe a selection the user had just made.
     *
     * <p>The mode takes on any frame at all, because it touches nothing but its own state: no camera
     * is installed, no view is needed, and a session that has not reached the world yet simply has
     * nothing to select until it does.
     *
     * <p>Called from {@link #tick()} <b>outside</b> the branch that ticks the members: dropping the last
     * member is exactly the frame on which the list is empty and the mode still has to go off.
     */
    private static void tickmode() {
	boolean want = (members.size() > 1);
	if(want == modeon)
	    return;
	Control.mode(want);
	modeon = want;
    }

    /**
     * Say something where it will actually be seen. {@code Console.out} lands in the chat's "System"
     * channel, which is easy to have closed — and a member is invisible by construction, so the
     * only evidence it exists is what it says. This goes through the anchor's on-screen notice.
     */
    public static void say(String fmt, Object... args) {
	pending.add("session: " + String.format(fmt, args));
    }

    private static final Queue<String> pending = new ConcurrentLinkedQueue<String>();

    /* rts: why the login the player just performed did not become a session. It cannot be said with
     * say(): that delivers to the anchor, and the anchor at that moment is the login screen, whose
     * widget tree answers no notice. So it is left here for the screen the player is about to be handed
     * back -- Bootstrap.run shows it as it builds the next LoginScreen -- and taken exactly once, because
     * a reason left lying about is one shown over the login after it. */
    private static volatile String denial = null;

    /**
     * rts: the reason the last login was refused, taken and cleared, or null. Read by {@code Bootstrap.run}
     * as it puts the login screen back up, which is the one place the player is looking when it matters.
     */
    public static String takedenial() {
	String d = denial;
	denial = null;
	return(d);
    }

    /* Queued, and drained on the UI thread by tick(), rather than said where it is thought. say() is
     * called from session threads, from the console and from the tick itself, and delivering it means
     * taking the ANCHOR's monitor -- which a member's Loader thread may one day do while holding its
     * own. One direction only, from a thread that holds nothing, and that whole class of deadlock
     * cannot form. It also puts the messages in the order they were said. */
    private static void flushsay() {
	if(pending.isEmpty())
	    return;
	UI u = anchor();
	String s;
	while((s = pending.poll()) != null) {
	    if(u == null) {
		System.err.println(s);
	    } else {
		try {
		    synchronized(u) {
			u.msg(s);
		    }
		} catch(RuntimeException e) {
		    System.err.println(s);
		}
	    }
	}
    }

    /** The session the client actually draws, and the frame every other one is measured against. */
    public static UI anchor() {
	UILoop lp = loop;
	return((lp == null) ? null : lp.drawn());
    }

    /* addon: (074.1) the ADDON LAYER's tree -- the one UI that is no session's, built once by the loop and
     * drawn above whichever session holds the screen. Read from the loop rather than held here for
     * anchor()'s reason: the loop owns every UI, and a second copy of which one this is can disagree. */
    public static UI layer() {
	UILoop lp = loop;
	return((lp == null) ? null : lp.layer);
    }


    /**
     * The login slot: {@code UILoop.ui}, the UI {@code Client.Main}'s own chain owns and replaces. It
     * holds the login screen and never a game session, so this is what the screen falls back to when no
     * session holds it — the client with nobody logged in, which is where dropping the last session
     * leaves you.
     */
    static UI loginui() {
	UILoop lp = loop;
	return((lp == null) ? null : lp.ui);
    }

    static Glob anchorglob() {
	UI u = anchor();
	return(((u == null) || (u.sess == null)) ? null : u.sess.glob);
    }

    /* addon: (075.1) ANY live session's Glob -- what a namespace reads when every session answers the
     * same number. The game clock is the server's: each session interpolates it against its own epoch,
     * but they are all reading one world, so which one is asked is arbitrary. Asking the drawn one in
     * particular is worse than arbitrary -- it answers null through a character switch, for a value
     * that has not changed.
     *
     * The anchor first, because it is the likeliest to be live and costs one field read; then the
     * membership in the order it was joined, so the answer is stable rather than whichever session
     * happens to hold the screen. A Session's glob is final and never null, so holding the Session is
     * the whole test. null only when the client holds no session at all. */
    public static Glob anyglob() {
	Glob g = anchorglob();
	if(g != null)
	    return(g);
	for(Member m : members) {
	    Session s = m.sess;
	    if(s != null)
		return(s.glob);
	}
	return(null);
    }

    /* rts: (F6) one shared Audio.Root feeds every session, so without this they all play at once --
     * a member's chat pings, its minimap alerts and every sound the server sends it, over the view
     * you are actually watching. The server tells all of them about the same event when they stand
     * together, and each UI rate-limits only its own (UI.lastmsgsfx), so what that sounds like is
     * one noise played four times.
     *
     * Every live session, every frame, and deliberately not through placed(): a member added without
     * the anchor changing would never be silenced at all, and one still on the character list or
     * standing too far away to be anchored is absent from placed() while already making noise.
     * RootChannel.mute returns on no change, so a frame that alters nothing costs a comparison. */
    private static void applymute() {
	UI an = anchor();
	UI mu = loginui();
	if(mu != null)
	    mute(mu, mu != an);
	for(Member m : members) {
	    UI u = m.ui;
	    if(u != null)
		mute(u, u != an);
	}
    }

    private static void mute(UI u, boolean m) {
	if(u.audio != null)
	    u.audio.mute(m);
    }

    /* rts: (F5) a session that has gone must not keep the screen. A member holding the anchor can die
     * at any moment -- dropped, disconnected, logged out from the other end -- and its run() unwinds
     * on its own thread, where touching the render trees would be wrong. It leaves the corpse in
     * `members`-minus-one and this notices, on the UI thread, on the next frame.
     *
     * Where the screen goes is the whole of what the handoff changed here: there is no session to fall
     * back to any more, so it goes to another live one, and to the LOGIN SCREEN when that was the last.
     * Nothing has to enforce "the client cannot close its last session" -- closing it is logging out,
     * and logging out is the login screen. */
    private static void reclaim() {
	UI an = anchor();
	if((an == null) || (an == loginui()))
	    return;   /* the login screen holds it: there is no dead session under it to reclaim */
	Member next = null;
	for(Member m : members) {
	    if(m.ui == an)
		return;
	    if((next == null) && !m.dead && (m.ui != null))
		next = m;
	}
	anchor(next);
    }

    /* rts: (071.1) the screen leaves a UI before that UI does.
     *
     * Called from a session's own thread as it ends or is handed on, and it cannot wait for reclaim():
     * reclaim notices a frame later, and a frame later is a frame the loop spent drawing a widget tree
     * that was being taken apart underneath it. Another live session takes the screen, or the login
     * screen does -- the same choice reclaim() makes, made a frame earlier by the one thread that knows
     * the session is going. */
    static void relinquish(UI u) {
	if(u == null)
	    return;
	/* rts: (122.1) the anchor is re-read and the successor picked UNDER the lock anchor(Member) settles
	 * the switch under, because both halves race the player. Read outside it, the screen can move between
	 * the test and the pick -- and then this hands it away from the session the player has just switched
	 * to, from a thread that is only ending a different one. The lock is a leaf: nothing in here and
	 * nothing in anchor(Member) takes a widget tree's monitor, so a session's own thread may hold it while
	 * the frame is drawing. */
	synchronized(Sessions.class) {
	    if(anchor() != u)
		return;
	    Member next = null;
	    for(Member m : members) {
		if((m.ui != u) && !m.dead && (m.ui != null)) {
		    next = m;
		    break;
		}
	    }
	    anchor(next);
	}
    }

    /* ------------------------------------------------------------------ *
     * F5: whose screen it is
     * ------------------------------------------------------------------ */

    /* addon: (074.3) the ACCOUNT NAME of the session drawn in `u`, or null when `u` is no member's --
     * the login slot, the addon layer, or a UI already taken down. It is what every session-lifecycle
     * event on the addon bus carries, because after 071 a name is what every session has, whichever
     * door it came through, and it is what `:session list` prints. */
    public static String nameof(UI u) {
	if(u == null)
	    return(null);
	for(Member m : members) {
	    if(m.ui == u)
		return(m.user);
	}
	return(null);
    }

    /* addon: (076.1) THE MEMBER LOGGED IN AS THIS ACCOUNT, or null when the client holds none. The account
     * name is what addresses a session from outside this layer -- it is what `:session add` took, what
     * `:session list` prints, what the session events carry and what a Lua Session object wraps -- and this
     * is the one funnel that turns it back into a member. A member is off this list before anything is told
     * its session ended, so a name that answers null here has no session, which is exactly what a handle
     * held across the end has to report. */
    public static Member byuser(String user) {
	if(user == null)
	    return(null);
	for(Member m : members) {
	    if(m.user.equals(user))
		return(m);
	}
	return(null);
    }

    /** The member currently holding the anchor, or null when the login screen has it. */
    public static Member anchormember() {
	UI an = anchor();
	for(Member m : members) {
	    if(m.ui == an)
		return(m);
	}
	return(null);
    }

    /**
     * Hand the screen to a member, or to the <em>login screen</em> with {@code null} — which is where
     * it goes when the client holds no session at all.
     *
     * <p>Three things move, and nothing else has to: which UI the frame draws and dispatches to, and
     * the dormancy of the two views involved. Everything else in the project reads
     * {@link #anchor()} — the offsets, the orders, the merged patches, the selection — so it all
     * follows by itself. The offsets in particular are measured <em>against</em> the anchor, and each
     * is the difference of two bases derived again every tick, so they follow the screen by themselves.
     *
     * <p><b>This half publishes and moves no view.</b> It touches no widget tree at all — which is what
     * makes {@code Sessions.class} a <em>leaf</em> lock, and therefore what makes this callable from a
     * handler that already holds one: a keybinding, a control's own notification, a draw handler, a
     * console line, an action hook. Every one of those arrives holding the drawn session's monitor, and a
     * session ending takes the two in the other order from its own thread through {@link #relinquish}.
     * What the two views owe each other is left in a request, and {@link #tickview()} spends it on the
     * frame's own thread.
     */
    public static synchronized void anchor(Member m) {
	UILoop lp = loop;
	if(lp == null)
	    return;
	UI target = (m == null) ? loginui() : m.ui;
	if(target == null) {
	    say("that session has no screen yet");
	    return;
	}
	UI cur = anchor();
	if(target == cur)
	    return;
	/* One camera across the characters. Each session's MapView owns a camera object -- a camera is
	 * an inner class of the view it draws -- so the one the player has been using is carried over as
	 * state, and `offset` is what names its pan in the incoming session's frame. The offset is read
	 * HERE and not in tickview(): after drawn(target) and invalidate(), buildplaced() rebuilds with
	 * the incoming session as the anchor and answers Coord2d.of(0, 0) for it, so a view moved on a
	 * later read would land the pan a whole offset away between two distant characters. */
	Coord2d off = null;
	for(Placed ss : placed()) {
	    if(ss.ui == target)
		off = ss.offset;
	}
	lp.drawn(target);
	/* The session cache says which one is the anchor, and it was built before this line. Anything
	 * asked between here and the next tick -- Control.take's selection first of all -- would
	 * otherwise be answered about the session that just lost the screen. */
	invalidate();
	/* And the two views are left for the frame. Published BEFORE the request, so a switch a session
	 * thread makes between one tickview() and the drawn() read below it costs the INCOMING session a
	 * frame with its scene detached, rather than blanking the outgoing one. */
	request(cur, target, off);
	/* addon: (074.3) the screen changed, and the layer above it may care which character it is over.
	 * After the invalidate, so a handler asking anything session-shaped is answered about the session
	 * that just took the screen. Going to the LOGIN SCREEN fires nothing -- no session was picked -- and
	 * where that is the last session falling, its own SessionRemoved is what says the screen emptied. */
	if(m != null)
	    io.brodgar.addon.AddonManager.sessionSelected(m.user);
	/* rts: the switch says nothing on screen. Which character has it is visible in the character, and
	 * SessionSelected above is how a layer that cares is told -- a line per switch is noise in a client
	 * whose whole point is that you switch often. */
    }

    /* rts: (122.1) what one change of screen leaves for the frame's own thread: the view losing it, the
     * view taking it, and the offset that names the outgoing pan in the incoming session's frame. Read
     * once and never written, so the frame reads a whole request or none of it. */
    private static class ViewSwitch {
	final UI cur, target;
	final Coord2d off;

	ViewSwitch(UI cur, UI target, Coord2d off) {
	    this.cur = cur;
	    this.target = target;
	    this.off = off;
	}
    }

    /* rts: (122.1) the one pending change of screen, or null. ONE slot and not a queue: a switch
     * superseded inside a frame is a switch that never happened, and replaying it would sleep a view
     * twice. Atomic rather than volatile because two threads publish -- the frame's, from a handler, and
     * a session's own, through relinquish -- and the coalesce below reads and writes it as one step. */
    private static final AtomicReference<ViewSwitch> pendingview = new AtomicReference<ViewSwitch>(null);

    /* rts: (122.1) coalesce: the FIRST cur, the LAST target, and that target's own offset. The first cur
     * because it is the view still being drawn and still holding the camera the player has been using;
     * the last target because it is where the screen ends up. A pair that meets -- A to B and back to A
     * inside one step -- comes out with cur == target and is dropped by tickview() rather than applied:
     * dormant(false) and dormant(true) landing on the same view would take the drawn session's scene out
     * from under it, click-map and all. */
    private static void request(UI cur, UI target, Coord2d off) {
	ViewSwitch prev, next;
	do {
	    prev = pendingview.get();
	    next = new ViewSwitch((prev == null) ? cur : prev.cur, target, off);
	} while(!pendingview.compareAndSet(prev, next));
    }

    /**
     * Move the two views the last change of screen named, on the thread that draws them.
     *
     * <p>Called from {@code UILoop.run} at the top of the frame — after {@code env.render()} and
     * <b>before</b> the {@code uilock} block that reads {@code drawn()} — holding nothing whatsoever. So a
     * switch made from inside the frame publishes mid-frame and is spent at the top of the next one,
     * before anything reads which UI to draw: the session taking the screen is never drawn with its scene
     * detached. A switch made from a session's own thread can land in the gap between the two and costs
     * that session one frame, which is the whole price of the split.
     *
     * <p>Each tree is walked and written under its <b>own</b> monitor and never both at once, which is
     * the one lock direction this layer permits. Nothing here is allowed to fail the frame: a view taken
     * down between the publication and this call is a walk that answers null, and a throw out of either
     * view is a warning rather than the end of the loop.
     */
    public static void tickview() {
	ViewSwitch req = pendingview.getAndSet(null);
	if(req == null)
	    return;
	/* Away and straight back inside one step. The screen never moved, so neither may the views. */
	if(req.cur == req.target)
	    return;
	try {
	    MapView oldmv = lockedview(req.cur), newmv = lockedview(req.target);
	    if(newmv != null) {
		synchronized(req.target) {
		    if(oldmv != null)
			newmv.adoptcam(oldmv.camera, req.off);
		    newmv.dormant(false);
		}
	    }
	    if(oldmv != null) {
		synchronized(req.cur) {
		    oldmv.dormant(true);
		}
	    }
	} catch(RuntimeException e) {
	    new Warning(e, "session: the screen moved but the views did not").issue();
	}
    }

    /* rts: (122.1) mapview(u) under u's OWN monitor, and private because the public verb may not do this.
     * Widget.child and Widget.next are not volatile and Widget.unlink ends by setting next = null, so a
     * findchild racing that session's own Loader -- UI.CommandQueue.execute defers NewWidget.run,
     * AddWidget.run and DstWidget.run, each under synchronized(UI.this) -- stops early and answers null in
     * silence. A spurious null here is STICKY: miss dormant(false) and the session that has just taken the
     * screen draws with no scene at all until the player switches away and back.
     *
     * mapview(UI) itself stays unguarded on purpose. AddonManager.screenView() reaches it from handlers
     * already holding the addon LAYER's monitor, so a synchronized(u) inside it would nest layer ->
     * session, the one nesting UILoop.Frame.tick's own comment forbids. This one is called from tickview()
     * alone, which holds nothing. A UI already destroyed has no tree worth walking and says so. */
    @SuppressWarnings("deprecation")
    private static MapView lockedview(UI u) {
	if((u == null) || (u.root == null) || u.destroyed)
	    return(null);
	synchronized(u) {
	    return(u.root.findchild(MapView.class));
	}
    }

    /* rts: public since 072.3 -- AddonManager.screenView() derives the drawn scene from the anchor
     * through this, rather than keeping a hand-written copy of it. A recursive walk, so its one caller
     * there caches what it gets and re-checks the answer instead of walking again. */
    @SuppressWarnings("deprecation")
    public static MapView mapview(UI u) {
	if((u == null) || (u.root == null))
	    return(null);
	return(u.root.findchild(MapView.class));
    }

    /** One line for the {@code :stats on} HUD — F0 exists to read this. */
    public static String stats() {
	if(members.isEmpty())
	    return("");
	StringBuilder buf = new StringBuilder();
	for(Member m : members) {
	    if(buf.length() > 0)
		buf.append(", ");
	    buf.append(m.status());
	}
	return(buf.toString());
    }

    /**
     * rts: (F5) one live session, located relative to whichever session currently holds the screen.
     *
     * <p>The equality abstraction this layer is read through: <b>one uniform list, the anchor being
     * simply the session whose offset is zero</b>. Any session can be the one on screen and any of them
     * can be the one you have tabbed away from, so a lookup that names one of them differently is a
     * lookup that loses it — a character that cannot be seen, selected or ordered the moment it stops
     * being the one drawn.
     */
    public static class Placed {
	public final String user;
	public final UI ui;
	public final GameUI gui;
	public final Glob glob;
	public final Coord2d offset;   // this session's frame minus the anchor's
	public final Member member;    // the session itself, and never null: every session is a member
	public final boolean isanchor;

	Placed(String user, UI ui, GameUI gui, Glob glob, Coord2d offset, Member member, boolean isanchor) {
	    this.user = user;
	    this.ui = ui;
	    this.gui = gui;
	    this.glob = glob;
	    this.offset = offset;
	    this.member = member;
	    this.isanchor = isanchor;
	}
    }

    /* rts: rebuilt at most once a frame. It is asked for by the merged views, by the selection, by
     * the switcher window and by the mute -- and it allocates a list, a Placed per session and (before
     * the cache above) a tree walk per session every single time. Nothing it reports can change
     * within a frame: the tick is the only thing that moves any of it.
     *
     * Volatile, and with exactly ONE builder. buildplaced() runs Member.gameui() -> Widget.findchild, a
     * recursive walk of a widget tree the UI thread is mutating, so the thread that walks it has to be
     * the thread that mutates it. The pick pass arrives here from somewhere else entirely: MapView's
     * checkmapclick reads offsetfor(cut.map) inside a GPU readback callback, which GLEnvironment runs on
     * a queue thread of its own, neither the UI thread nor the render thread. That caller READS what the
     * tick published and never builds -- letting it take the anchor's monitor and build safely instead
     * inverts the one lock direction this layer permits (flushsay states it), which is a new class of
     * deadlock rather than a fix. */
    private static volatile List<Placed> placedcache = null;

    /** What a caller that may not build is answered with: as far as it can tell, no session is placed. */
    private static final List<Placed> unpublished = Collections.emptyList();

    /**
     * Drop what was published, because what it describes has moved. The tick republishes at its end, and
     * {@link #anchor(Member)} leaves it unpublished until then on purpose — between an anchor switch and the
     * next frame the truthful answer about who holds the screen is "not known yet", and a caller that may
     * not rebuild is better told that than told the session that has just lost it.
     */
    static void invalidate() {
	placedcache = null;
    }

    /* rts: the tick's own build, and the reason nothing else needs one. Unconditional -- with no members,
     * and on the login screen where it publishes an empty list -- because everything off this thread is
     * answered out of what is published, so "nothing was published this frame" and "no session holds that
     * map" have to come out as the same answer, and only a list that is always there makes them one. The
     * cost is a list and a Placed per session per frame; the tree walk each Placed once needed is already
     * cached (Member.gameui). */
    private static void republish() {
	placedcache = buildplaced();
    }

    /* rts: the thread the frame runs on, and the only one that may build the cache above. Asked of the
     * loop rather than recorded here: UILoop.th is the loop's own field, so there is no second copy of it
     * to fall out of step. */
    private static boolean ontick() {
	UILoop lp = loop;
	return((lp != null) && (Thread.currentThread() == lp.th));
    }

    /**
     * Every live session, located against the one on screen — and two kinds of caller, of which only one
     * may build.
     *
     * <p>On the thread that runs the frame this is the ordinary lazy cache: a null means {@link
     * #invalidate()} discarded it and the next ask rebuilds it. Off that thread it is a <em>read of what
     * the tick published</em>, and an empty list when the tick has published nothing yet — which is what
     * {@link #offsetfor(MCache)} already answers for a map it does not recognise, so no caller of that
     * gains a case to handle. Each such ask is counted by {@link #placedRebuiltOffTick()}, whose whole
     * meaning is that it stays at zero.
     */
    public static List<Placed> placed() {
	List<Placed> c = placedcache;
	if(c != null)
	    return(c);
	if(!ontick()) {
	    placedRebuiltOffTick.incrementAndGet();
	    return(unpublished);
	}
	return(placedcache = buildplaced());
    }

    /* rts: the times placed() was asked to build where it may not. The one counter in this class written
     * from a thread that is not the frame's, hence atomic rather than a volatile ++: several readback
     * callbacks can be in flight at once, and a lost increment on a number whose entire claim is "still
     * zero" is the one error it cannot afford. */
    private static final AtomicLong placedRebuiltOffTick = new AtomicLong(0);

    /**
     * Times {@link #placed()} was asked to build its cache off the frame's own thread, cumulative since
     * the client started. Zero says the pick pass never reached the builder.
     */
    public static long placedRebuiltOffTick() {
	return(placedRebuiltOffTick.get());
    }

    /* rts: (071.2) one loop, and no second kind of row. Every session the client holds is a member, so a
     * Placed always carries the member it describes and is always named by the ACCOUNT that member logged
     * in as -- there is no session here for which the client has to invent a name. */
    private static List<Placed> buildplaced() {
	List<Placed> ret = new ArrayList<Placed>();
	UI an = anchor();
	/* NOTHING IS PLACED WHILE THE LOGIN SCREEN HOLDS THE SCREEN, members or no members. Every row here
	 * is located against the anchor's frame, and the login screen has no frame to locate anything in --
	 * a member's cached offset still names the session that held the screen a moment ago, which is a
	 * number about nobody now. It became reachable with sessions live when the login screen became a
	 * place to go to (Control.take with a null); before that it only meant the last session had gone,
	 * and the list was empty because the membership was. */
	if((an == null) || (an == loginui()))
	    return(ret);
	for(Member m : members) {
	    UI u = m.ui;
	    GameUI gui = m.gameui();
	    Session ms = m.sess;
	    Coord2d off = (u == an) ? Coord2d.of(0, 0) : m.offset();
	    if((u == null) || (gui == null) || (ms == null) || (off == null))
		continue;
	    ret.add(new Placed(m.user, u, gui, ms.glob, off, m, u == an));
	}
	return(ret);
    }

    /** The session this character belongs to, by its (global) gob id. */
    static Placed bysess(long gobid) {
	for(Placed ss : placed()) {
	    if(ss.gui.plid == gobid)
		return(ss);
	}
	return(null);
    }

    /**
     * What the anchor's scene needs to draw one member's surroundings: whose data, which character to
     * centre on, and where that session's frame sits relative to this one.
     */
    public static class View {
	public final String user;
	public final Glob glob;
	public final long plgob;
	public final Coord2d offset;

	View(String user, Glob glob, long plgob, Coord2d offset) {
	    this.user = user;
	    this.glob = glob;
	    this.plgob = plgob;
	    this.offset = offset;
	}
    }


    /** Every session that can be drawn into the anchor's scene -- anchored, in the world, not the anchor. */
    public static List<View> views() {
	List<View> ret = new ArrayList<View>();
	for(Placed ss : placed()) {
	    if(ss.isanchor)
		continue;   /* the session holding the anchor IS the scene; it is not merged into itself */
	    ret.add(new View(ss.user, ss.glob, ss.gui.plid, ss.offset));
	}
	return(ret);
    }

    /**
     * Whose map is this, and how far is its frame from the anchor's? Asked by the pick pass: a click
     * resolves against a cut, and a cut knows which {@code MCache} produced it, so the answer needs no
     * tagging of the geometry.
     *
     * <p>That caller is <b>not on the UI thread</b> — the pick resolves in a GPU readback callback — so
     * this goes through {@link #placed()}'s read-only path and never builds anything. {@code null}
     * already meant two things the caller treats alike, the anchor's own map and a map this client has
     * not placed; a frame the tick has not published yet is a third, and it is the same answer: use the
     * coordinate the cut gave, untranslated.
     */
    public static Coord2d offsetfor(MCache map) {
	if(map == null)
	    return(null);
	for(Placed ss : placed()) {
	    if(ss.glob.map == map)
		return(ss.isanchor ? null : ss.offset);
	}
	return(null);
    }

    /**
     * The ground height at a place in the anchor's coordinates, asked of whichever session actually
     * has that ground. Over a merged patch the anchor has no map at all — and a camera that cannot
     * answer "how high is it here" without the anchor's map is a camera that blacks the screen out
     * the moment it is panned somewhere interesting.
     *
     * <p>Every placed session but the anchor's own is asked, each in the frame it is actually in, and
     * {@link #placed()} rather than {@code members} is why the frames are right: the {@code isanchor ?
     * zero : offset} correction it applies is the one {@link Member#offset()} does not carry once a
     * member takes the screen ({@code Sessions.tick} skips the member holding it, so {@code tickoffset}
     * never runs for that one and the field keeps whatever it last held). The anchor's own entry is
     * skipped because the caller is here <em>because</em> the anchor's map threw {@link Loading} for
     * this place.
     *
     * <p>{@code Loading} is caught per session rather than once around the loop: one session lacking
     * that ground is the ordinary case and must not stop the next from answering.
     */
    public static double groundz(Coord2d anchorpos, double dflt) {
	for(Placed ss : placed()) {
	    if(ss.isanchor)
		continue;
	    try {
		double z = ss.glob.map.getzp(anchorpos.add(ss.offset)).z;
		groundAnswered++;
		return(z);
	    } catch(Loading e) {
	    }
	}
	groundMissed++;
	return(dflt);
    }

    /* rts: what the layer answered, for hafen.client():profiling():session(). Written at the two exits
     * above and read from Lua, both on the UI thread -- the camera asks from its own tick. Volatile
     * rather than plain for the same reason every other field a reader crosses into is: this class
     * states its threading rather than assuming a caller's. */
    private static volatile long groundAnswered = 0, groundMissed = 0;

    /** Times {@link #groundz} found a session holding that ground, cumulative since the client started. */
    public static long groundAnswered() {
	return(groundAnswered);
    }

    /** Times {@link #groundz} fell through to its default instead, cumulative since the client started. */
    public static long groundMissed() {
	return(groundMissed);
    }

    /**
     * Walk one unit, named by its gob id, to a place named in the <em>anchor's</em> coordinates. Ids
     * are global, so the same number identifies the character in the anchor's view, in its own
     * session, and in the selection — there is no per-session bookkeeping to keep straight.
     *
     * <p>This is the whole thesis of the project in six lines. A member is not drawn, has no camera,
     * no click-map and no scene — and none of that is needed, because an order is not a mouse event.
     * It is the widget message a left-click on that patch of ground would have produced, and
     * {@code UI.rawWdgmsg} resolves the widget id from that session's <em>own</em> tree and hands it
     * to that session's <em>own</em> receiver. The one thing that has to be right is the coordinate.
     */
    public static boolean orderunit(long unitid, Coord2d anchorpos, int mods) {
	Placed ss = bysess(unitid);
	if((ss == null) || (ss.gui.map == null))
	    return(false);
	/* One rule for every session: translate into its frame, send down its own socket. The anchor's
	 * offset is zero, so it needs no special case -- and the session on screen sends through wdgmsg
	 * so its orders stay indistinguishable from real clicks to the addon action hooks. */
	send(ss.ui, ss.gui.map, anchorpos.add(ss.offset), mods, !ss.isanchor);
	return(true);
    }

    /**
     * Walk one member to a place named in <em>its own</em> coordinates &mdash; the door an addon's
     * <code>session:player():move(p)</code> sends through, where {@link #orderunit} is the RTS mode's.
     *
     * <p>The two differ in one thing only: whose frame the destination arrives in. A place picked off the
     * anchor's scene is in the anchor's, so {@code orderunit} translates it; a place an addon resolved
     * through the addressed session's <em>own</em> map is already that session's, and translating it again
     * would walk the character a second offset away. Everything after that is the same send.
     *
     * <p>{@code false}, and nothing sent, when that session has no view to send to &mdash; connecting, on
     * the character list, or gone. The caller named the session, so it is the one that can say which.
     */
    public static boolean ordermember(Member m, Coord2d mc, int mods) {
	if(m == null)
	    return(false);
	UI u = m.ui;
	GameUI gui = m.gameui();
	if((u == null) || (gui == null) || (gui.map == null))
	    return(false);
	send(u, gui.map, mc, mods, m != anchormember());
	return(true);
    }

    /**
     * The message a left-click on empty ground would have produced, built in the recipient's own
     * frame. <b>Ground and nothing else</b>: no gob travels with an order, and none is looked up
     * here. A click that landed on something is an interaction, which belongs to the character the
     * player is actually looking at and is never handed to another login — the only thing a session
     * is ever told from outside is where to walk.
     *
     * <p>The screen coord the protocol carries is not what picks the destination — the map coord is.
     * The centre of the recipient's own view is passed for it, which is both plausible and meaningless.
     *
     * <p>The anchor sends through {@code wdgmsg} — its orders are indistinguishable from real clicks
     * and should stay visible to the addon action hooks. A member sends through {@code rawWdgmsg}:
     * that chain belongs to the anchor and knows nothing about the session it would be walking for.
     */
    private static void send(UI u, MapView mv, Coord2d mc, int mods, boolean raw) {
	Object[] args = {mv.sz.div(2), mc.floor(OCache.posres), 1, mods};
	synchronized(u) {
	    if(raw)
		u.rawWdgmsg(mv, "click", args);
	    else
		u.wdgmsg(mv, "click", args);
	}
    }



    /* rts: Widget.findchild is a RECURSIVE walk of the whole tree, and this used to run once per
     * session per placed() call, three times a frame. A GameUI is made once when a session enters
     * the world and stays put, so the answer is cached and re-derived only when the widget it names
     * has left the tree. */
    @SuppressWarnings("deprecation")
    static GameUI findgui(UI u) {
	return(((u == null) || (u.root == null)) ? null : u.root.findchild(GameUI.class));
    }

    static Placed anchorsess() {
	for(Placed ss : placed()) {
	    if(ss.isanchor)
		return(ss);
	}
	return(null);
    }

    /* ------------------------------------------------------------------ *
     * Membership
     * ------------------------------------------------------------------ */

    /* rts: (122.3) THE ACCOUNT NAMES A LOGIN IS BEING BUILT FOR, and the monitor they are reserved under.
     * One session per account is the rule both doors hold, and the check that held it was a read of
     * `members` separated from the write that adds one by connect()'s two blocking network round-trips --
     * long enough for a second add of the same name to pass the same read and for the client to end up
     * holding two members of one account, which is a state nothing above this layer can express.
     *
     * A LEAF, and it has to be: claim, enlist and unclaim take nothing whatever underneath this monitor --
     * `members` is a CopyOnWriteArrayList and needs none -- and both callers reach them from a thread of
     * their own, holding no widget tree and not Sessions.class. */
    private static final Set<String> claiming = new HashSet<String>();

    /**
     * Reserve an account name, or answer {@code false} when the client already holds a session for it or is
     * building one. Every refusal of a second login for one account comes through here; the words are the
     * caller's, because the two doors throw different things at different readers.
     */
    private static boolean claim(String user) {
	synchronized(claiming) {
	    for(Member m : members) {
		if(m.user.equals(user))
		    return(false);
	    }
	    return(claiming.add(user));
	}
    }

    /**
     * Put a built member on the list and relieve its reservation, in that order and under the one monitor:
     * in between, the name still has to be refused, and {@code members} is what refuses it from here on.
     */
    private static void enlist(Member m) {
	synchronized(claiming) {
	    members.add(m);
	    claiming.remove(m.user);
	}
    }

    /**
     * Release a reservation that never became a member. Called from a {@code finally} and never a
     * {@code catch}: {@link #connect} is blocking network, and a catch misses the {@code InterruptedException}
     * and the {@code Error} and leaves the account unusable for the rest of the login, which is worse than
     * the race it closes. A no-op once {@link #enlist} has taken the name off.
     */
    private static void unclaim(String user) {
	synchronized(claiming) {
	    claiming.remove(user);
	}
    }

    /**
     * Connect an account with a saved token and hold it open.
     *
     * <p>The account name is <b>reserved before anything connects</b>: {@link #connect} is two blocking
     * network round-trips, and a check reading {@code members} on this side of them lets a second add of the
     * same name past the same read. {@link #enlist} relieves the reservation and the {@code finally} releases
     * it, so an add that dies -- on a refused token, on an interrupt, on an {@code Error} -- leaves the name
     * usable.
     *
     * @param user the account name, as the login screen knows it
     * @param chr  the character to play, or {@code null} to play whichever the server offers first
     */
    public static Member add(String user, String chr) throws IOException {
	UILoop lp = loop;
	if(lp == null)
	    throw(new IllegalStateException("rts: no UI loop yet"));
	if(!claim(user))
	    throw(new IOException("already a live session: " + user));
	try {
	    Session sess = connect(user);
	    Member m = new Member(user, chr, sess);
	    /* Registered BEFORE the UI exists, and before the thread that pumps it: MapView's constructor
	     * asks Sessions.dormant(glob) once and keeps that answer for the life of the view, so a view
	     * built for a member not yet on this list comes up NON-dormant -- attaching its scene and meshing
	     * terrain for a session nobody draws, until the player switches away and back. */
	    enlist(m);
	    try {
		m.start(lp);
	    } catch(RuntimeException | Error e) {
		/* Error too, and not for tidiness: past enlist it is the MEMBER that holds the account name,
		 * so a start dying on one leaves an entry with no UI and no thread sitting on that name for
		 * the rest of the client's life -- the very state the reservation's finally exists to prevent. */
		members.remove(m);
		sess.close();
		throw(e);
	    }
	    io.brodgar.addon.AddonManager.sessionAdded(user);   // addon: (074.3) a session connected
	    return(m);
	} finally {
	    unclaim(user);
	}
    }

    /**
     * Adopt the session the client's own runner chain just logged in, and hold it like every other.
     *
     * <p>{@code Client.Main} hands its {@link RemoteUI} here instead of running it: the slot it would
     * otherwise have gone in is {@code UILoop.ui}, which the runner chain replaces and destroys as it
     * advances, and a session living there is one this layer does not own — no offset, no
     * {@link Member#drop()}, no row in {@link #placed()}. It is built through {@code UILoop.bgui},
     * which replaces nothing, destroys nothing and takes no {@code uilock}, and runs on a thread of its
     * own, exactly as {@link #add} does for a saved token. The one difference is where the runner came
     * from: this one is already logged in, so it is used rather than made.
     *
     * <p>The account name is the one the auth server returned ({@code Session.User.name}) — the same
     * string {@link #add} is given for a saved token — so a session is named the same way whichever
     * door it came through.
     *
     * <p>It takes the screen unless a live session already holds it: a login the player has just
     * performed is one they want to look at, and a character already on screen is not one they asked to
     * leave. {@link #anchor} directly rather than {@link Control#take}: this runs on the client's main
     * thread, {@code take} ends in {@link #anchorsess()}, and a {@link #placed()} read off the frame's
     * own thread is exactly what {@link #placedRebuiltOffTick()} counts. There is nothing to select
     * either — the session has not reached the world yet, so there is no character to name.
     *
     * <p><b>One session per account</b>, the rule {@link #add} holds and this door holds with it: the login
     * screen is somewhere the player can go with sessions running ({@link Control#take} with a null, which
     * {@code hafen.session():current(nil)} spells), so logging the same account in twice is a thing they can
     * do by typing a name they already have live. Two members of one account name is a state nothing above
     * here can express — the account <em>is</em> the address, so they share a row in the switcher and a
     * {@code Session} object in every addon, and the server ends one of the two connections a moment later
     * anyway. Both doors refuse through the same {@link #claim}, so neither can pass the other; refused
     * before anything is built, and the runner chain the throw unwinds into puts the player back on the
     * login screen, which is where a login that did not take is retried.
     */
    public static Member adopt(RemoteUI fun) {
	UILoop lp = loop;
	if(lp == null)
	    throw(new IllegalStateException("session: no UI loop yet"));
	Session sess = fun.sess;
	String user = sess.user.name;
	if(!claim(user)) {
	    /* Closed here rather than left to the caller: this session is ours the moment we refuse it,
	     * and a connection nobody holds is one the server keeps open. */
	    sess.close();
	    denial = user + " is already logged in";
	    throw(new IllegalStateException("already a live session: " + user));
	}
	try {
	    Member m = new Member(user, null, sess);
	    /* Registered BEFORE the UI exists, for the reason add() gives: a MapView built for a member not
	     * yet on this list reads Sessions.dormant(glob) false and keeps that answer. */
	    enlist(m);
	    try {
		m.start(lp, fun);
	    } catch(RuntimeException | Error e) {   // Error too, for the reason add() gives
		members.remove(m);
		sess.close();
		throw(e);
	    }
	    /* addon: (074.3) ...and one that came through the client's own login screen is a session like any
	     * other. Before the anchor below, so an addon hears the session arrive and then be picked. */
	    io.brodgar.addon.AddonManager.sessionAdded(m.user);
	    if(anchormember() == null)
		anchor(m);
	    return(m);
	} finally {
	    unclaim(user);
	}
    }

    public static boolean drop(String user) {
	for(Member m : members) {
	    if(m.user.equals(user)) {
		m.drop();
		return(true);
	    }
	}
	return(false);
    }

    public static void dropall() {
	for(Member m : members)
	    m.drop();
    }

    /** The accounts with a token saved by the login screen — what {@link #add} can reach. */
    public static List<String> savedusers() {
	String confname = Bootstrap.authserv.get().host;
	return(Utils.getprefsl("saved-tokens@" + confname, new String[] {}));
    }

    /* ------------------------------------------------------------------ *
     * A member
     * ------------------------------------------------------------------ */

    public static class Member {
	public final String user;
	public final String chr;
	public volatile Session sess;
	public volatile UI ui;
	private volatile Thread th;
	private volatile boolean played = false;
	private volatile double charlistat = 0;
	volatile boolean dead = false;

	/* rts: this member's frame, against the anchor's (F1, specs/rts/plan.md). Every session logged in
	 * somewhere else and every coordinate it reports is relative to that, so the same patch of ground
	 * has a different number in each. Both sessions record their ground into one map database, so the
	 * difference is the difference of their two bases -- which needs no ground in common and is derived
	 * again every tick, because either base moves the moment the server re-bases that session. */
	private volatile Coord2d offset = null;   // this session's frame minus the anchor's, in world units
	/* Why there is no offset, or null while there is one. Held rather than derived at the point of
	 * printing so that the line and the number can never name different reasons. */
	private volatile String offwhy = "no proved base yet";
	/* The reason last said out loud. A reason that holds is not news -- a pair walking in and out of a
	 * house refuses and relates on every transit, and saying it per tick is a line per frame. */
	private String offsaid = null;

	/**
	 * Where this session's own coordinates sit in the map database, and whether that has been
	 * <b>proved</b>.
	 *
	 * <p>Immutable and replaced whole, {@code Recall}'s shape and for its reason: the tick writes it
	 * while the console and every reader of a session's place read it, and a half-updated base puts
	 * ground and objects in a world they are not in.
	 */
	public static final class Base {
	    /** Which map database this session names — two characters need not share one ({@code chrmap}). */
	    public final MapFile file;
	    /**
	     * The segment this session is standing in and the segment tile coord of its tile {@code (0, 0)}:
	     * segment tile = session tile + {@code loc.tc}. The client's own type, held whole rather than
	     * copied apart, because it is what everything that converts a place converts <em>through</em>.
	     */
	    public final MiniMap.Location loc;
	    /** Whether a live grid's id has been checked against the record through this base. */
	    public final boolean proven;

	    Base(MapFile file, MiniMap.Location loc, boolean proven) {
		this.file = file;
		this.loc = loc;
		this.proven = proven;
	    }

	    /** The segment this session is standing in — a segment id means nothing outside {@link #file}. */
	    public long seg() {
		return(loc.seg.id);
	    }

	    /** The segment tile coord of this session's tile {@code (0, 0)}. */
	    public Coord tc() {
		return(loc.tc);
	    }

	    boolean sameas(MapFile file, MiniMap.Location loc) {
		return((this.file == file) && (seg() == loc.seg.id) && tc().equals(loc.tc));
	    }
	}

	/* rts: (109.1) this session's base, and why there is no proved one when there is not. Exactly one
	 * of the two is null at any moment. Written by tickbase() alone, on the frame's own thread. */
	private volatile Base base = null;
	private volatile String baseless = "no HUD yet";

	/**
	 * This session's <b>proved</b> base, or null while it has none.
	 *
	 * <p>A base that has merely been derived is not one that may be read through. {@code sessloc} goes
	 * stale rather than null, so for a window after the server re-bases the session's coordinate space
	 * (a cave, a house) it still names the segment just left — and everything converted through it in
	 * that window is placed somewhere it never was. Nothing gets an unproved base from here.
	 */
	public Base base() {
	    Base b = this.base;
	    return(((b != null) && b.proven) ? b : null);
	}

	/**
	 * Derive this session's base from its own corner minimap and prove it against a live grid. Runs
	 * every frame, for every member, the anchor included.
	 *
	 * <p>The minimap is {@code GameUI.mmap} and no other: {@code MapWnd} carries a second {@link MiniMap}
	 * over the same database, and a base taken from that one would follow a window the user panned.
	 *
	 * <p>Nothing here is cached across a change. The session coordinate space is re-based mid-play and a
	 * tile coord that meant somewhere before means somewhere else afterwards, so the base is derived
	 * again every tick and needs no event to tell it that the ground moved.
	 */
	void tickbase() {
	    if(dead) {
		setbase(null);
		baseless = "the session has ended";
		return;
	    }
	    Session s = this.sess;
	    GameUI gui = gameui();
	    MiniMap mm = (gui == null) ? null : gui.mmap;
	    if((s == null) || (mm == null)) {
		setbase(null);
		baseless = "no HUD yet";
		return;
	    }
	    MapFile file = mm.file;
	    if(file == null) {
		setbase(null);
		baseless = "no map database yet";
		return;
	    }
	    MiniMap.Location loc = mm.sessloc;
	    if(loc == null) {
		setbase(null);
		baseless = "no session location yet";
		return;
	    }
	    /* sessloc.tc comes off a live grid's GridInfo and is grid-aligned. If it ever is not, the
	     * division below is a truncation and everything read through the base lands a fraction of a
	     * grid out -- so refuse the base rather than answer a place that is nearly right. */
	    if(!loc.tc.mod(MCache.cmaps).equals(Coord.z)) {
		setbase(null);
		baseless = "the session location " + loc.tc + " is not grid-aligned";
		return;
	    }
	    Base cur = this.base;
	    Boolean v = prove(file, loc, s.glob.map);
	    boolean proven;
	    if(v == null) {
		/* The database is busy. Keep the previous verdict rather than drop it: the processor thread
		 * holds that lock for as long as a segment save takes, and a base that flapped on every save
		 * would rebuild the merged scene each time it did. A verdict about a base that has since
		 * moved is not kept -- sameas is what says so. */
		proven = (cur != null) && cur.sameas(file, loc) && cur.proven;
		baseless = proven ? null : "the map database is busy and nothing has proved this base yet";
	    } else {
		proven = v.booleanValue();
		baseless = proven ? null : "no live grid's id matches what the record holds through it";
	    }
	    if((cur == null) || !cur.sameas(file, loc) || (cur.proven != proven))
		setbase(new Base(file, loc, proven));
	}

	/**
	 * Replace this session's base, and tell the addon layer when that actually changed anything.
	 *
	 * <p>rts: (109.4) <b>the base moving is the event</b>, and it is the only one. A place off the
	 * ground a character is streaming resolves through this base, so everything holding such a place
	 * has to be asked again the moment it moves — and the moment it is <em>proved</em>, which is a
	 * frame or more after the location it was derived from last changed. Hanging that notice on the
	 * location instead, where it used to hang, missed exactly that second edge: what the server had
	 * re-based came back unproved, and nothing woke the readers when the record caught up.
	 *
	 * <p>The comparison here is what keeps it an event: {@link #tickbase} runs every frame for every
	 * member and replaces the {@link Base} only when it differs, so the notice fires a handful of
	 * times an hour rather than sixty times a second.
	 */
	private void setbase(Base b) {
	    if(this.base == b)
		return;
	    this.base = b;
	    io.brodgar.addon.AddonManager.sessionRebased(this.ui);   // addon: (109.4) the frame moved under it
	}

	/**
	 * Does the record agree with the ground the server is streaming? A live grid's coord translated by
	 * the base must find that same grid's id in the segment. A grid id is the server's and means the
	 * same thing in every frame, so the comparison is the base checking itself.
	 *
	 * <p>{@code true} proved, {@code false} refused, {@code null} the lock was not free. Nothing
	 * recorded anywhere near counts as a refusal and not as an unknown: the grid the character is
	 * standing on is recorded within a second of arriving, so nothing to compare against means the
	 * record does not know where this session is — which is exactly the window a re-base opens.
	 */
	private static Boolean prove(MapFile file, MiniMap.Location loc, MCache mc) {
	    Coord off = loc.tc.div(MCache.cmaps);
	    /* Snapshot the live grids OUTSIDE the file lock: MapFile's own writers walk a map cache while
	     * holding it, and taking the two in the other order here is how that becomes a deadlock. */
	    List<MCache.Grid> live = AddonWidgets.loadedGrids(mc);
	    /* tryLock and never lock: the processor thread holds the write lock across disk I/O, and the
	     * frame may not wait for a segment save. Everything under it is an in-memory map lookup --
	     * Segment.gridid answers from the coord map that arrived with the segment. */
	    if(!file.lock.readLock().tryLock())
		return(null);
	    try {
		int checked = 0;
		for(MCache.Grid g : live) {
		    /* Grid.fill writes the id when the "m" layer lands, so a grid exists briefly with none. */
		    if(g.id == 0)
			continue;
		    Long rid = loc.seg.gridid(g.gc.add(off));
		    if(rid == null)
			continue;
		    checked++;
		    if(rid.longValue() != g.id)
			return(Boolean.FALSE);
		}
		return((checked > 0) ? Boolean.TRUE : Boolean.FALSE);
	    } finally {
		file.lock.readLock().unlock();
	    }
	}

	private Member(String user, String chr, Session sess) {
	    this.user = user;
	    this.chr = chr;
	    this.sess = sess;
	}

	private void start(UILoop lp) {
	    start(lp, new RemoteUI(sess));
	}

	/* The adopted session brings its OWN runner: Client.Main's login produced it and it is already
	 * bound to this Session, so making a second one would be making a second session. Everything else
	 * is the same call either way -- bgui, which replaces and destroys nothing, and one thread. */
	private void start(UILoop lp, UI.Runner fun) {
	    UI u = lp.bgui(fun);
	    this.ui = u;
	    io.brodgar.addon.AddonManager.sessionArrived(u);   // addon: (074.2) this session's own tick pump
	    Thread t = new HackThread(() -> run(lp, fun, u), "session-" + user);
	    t.setDaemon(true);
	    this.th = t;
	    try {
		t.start();
	    } catch(RuntimeException | Error e) {
		/* rts: (122.3) that thread is the only thing that ever runs this UI's runner chain and the
		 * only thing that takes the UI down, so a start that does not take leaves `ui` naming a live
		 * widget tree that nothing drives and nothing will ever destroy -- run()'s own finally is
		 * what does that, and it never arrives. Unwound in the order run() unwinds it: the field is
		 * cleared BEFORE the tree comes down, so no reader of it reaches a disposed one. */
		this.ui = null;
		this.th = null;
		try {
		    discard(lp, u);
		} catch(RuntimeException de) {
		}
		throw(e);
	    }
	}

	/**
	 * This session's own runner chain, the same shape {@code Client.run} drives for the login screen:
	 * a {@code RemoteUI} that returns another one when the server hands the session on, and null when
	 * it is over.
	 */
	private void run(UILoop lp, UI.Runner fun, UI first) {
	    UI u = first;
	    try {
		while(true) {
		    UI.Runner next = fun.run(u);
		    boolean drawn = (anchor() == u);
		    /* Cleared BEFORE the UI is taken down, never after: the tick reads this field every
		     * frame, and a field still naming a destroyed UI is a tick into a disposed widget tree.
		     * Ordering it this way is half of what makes that impossible; the re-check under the
		     * UI's own monitor in tick() is the other half. */
		    this.ui = null;
		    discard(lp, u);
		    u = null;
		    if(next == null)
			break;
		    fun = next;
		    /* Re-register the new session before its UI exists, for the same reason as in add(). */
		    if(fun instanceof RemoteUI)
			this.sess = ((RemoteUI)fun).sess;
		    this.played = false;
		    this.ui = u = lp.bgui(fun);
		    io.brodgar.addon.AddonManager.sessionArrived(u);   // addon: (074.2) and the new one's, on a handoff
		    /* The session did not go anywhere -- the server handed it on, which is what choosing
		     * another character is -- so the screen it held comes back to it, on its new UI. */
		    if(drawn)
			anchor(this);
		}
	    } catch(InterruptedException e) {
	    } catch(RuntimeException e) {
		new Warning(e, String.format("session: %s died", user)).issue();
	    } finally {
		boolean announce = !dead;   /* a drop() already said so */
		dead = true;
		members.remove(this);
		this.ui = null;
		/* addon: (074.3) the session is over, whatever ended it -- a :session drop, the server, or a
		 * throw out of the runner chain. This finally is the one place all three arrive. */
		io.brodgar.addon.AddonManager.sessionDestroyed(user);
		if(announce)
		    say("%s: session ended", user);
		if(u != null) {
		    try {
			discard(lp, u);
		    } catch(RuntimeException e) {
		    }
		}
	    }
	}

	/**
	 * Take this session's UI down, in the one order that is safe from the session's own thread: the
	 * screen leaves it first, and the destroy then waits out any frame still holding it. Destroying it
	 * where it is finished with instead is a widget tree disposed between a frame's tick and its draw,
	 * which is a {@code SlotRemoved} on the loop's thread and the end of the loop.
	 */
	private void discard(UILoop lp, UI u) {
	    if(u == null)
		return;
	    relinquish(u);
	    lp.bgdestroy(u);
	}

	/**
	 * Closing the session is what ends a member: {@code RemoteUI.run} is blocked in
	 * {@code getuimsg}, which answers null once the session is closed, so the runner chain unwinds
	 * through its own cleanup instead of being torn out from under it.
	 */
	public void drop() {
	    dead = true;
	    Session s = this.sess;
	    if(s != null) {
		try {
		    s.close();
		} catch(RuntimeException e) {
		}
	    }
	    Thread t = this.th;
	    if(t != null)
		t.interrupt();
	}

	/**
	 * A token login lands on the character list, and nobody is going to click it: a member is never
	 * drawn. Sent through {@code rawWdgmsg} so the addon action-hook chain — which is bound to the
	 * anchor and to no one else — is not walked for a session it knows nothing about.
	 */
	@SuppressWarnings("deprecation")
	private void autoplay(UI u) {
	    if(played)
		return;
	    if(u.root.findchild(GameUI.class) != null) {
		played = true;
		say("%s: in the world", user);
		return;
	    }
	    Charlist cl = u.root.findchild(Charlist.class);
	    if(cl == null)
		return;
	    if(charlistat == 0)
		charlistat = Utils.rtime();
	    String pick = null;
	    List<String> offered = new ArrayList<String>();
	    synchronized(cl.chars) {
		for(Charlist.Char c : cl.chars) {
		    offered.add(c.name);
		    if((chr == null) || chr.equals(c.name)) {
			pick = c.name;
			break;
		    }
		}
	    }
	    if(pick != null) {
		played = true;
		u.rawWdgmsg(cl, "play", pick);
		say("%s: playing %s", user, pick);
		return;
	    }
	    /* The list arrives one character per message, so a name absent from the first frame may still
	     * be coming. Wait, then say what was actually offered -- a member that silently sits on a
	     * character list forever is the one failure this whole path can produce without a trace. */
	    if(Utils.rtime() - charlistat > 3.0) {
		played = true;
		say("%s: no character named \"%s\" -- offered: %s", user, chr,
		       offered.isEmpty() ? "(none)" : String.join(", ", offered));
	    }
	}

	/**
	 * Derive this member's offset from the anchor's — the difference between two bases, and nothing
	 * else. Runs every frame, and needs the two sessions to have no ground whatever in common: a base
	 * is where a session sits in the map database, so subtracting two of them relates two frames
	 * across any distance.
	 *
	 * <p>Derived again every tick and never kept. The server re-bases a session's coordinate space
	 * mid-play — a cave, a house — and a kept offset would go on placing that session's ground and
	 * objects where they have never been, silently, for the rest of the login.
	 *
	 * <p><b>Three refusals, and each is named.</b> Two bases relate or they do not: one of them not
	 * proved, two different map databases, or two different segments of one. The database is asked
	 * before the segment because a segment id means nothing outside the file that minted it.
	 */
	private void tickoffset(Member an) {
	    if(an == null)
		return;
	    Base mine = base();
	    Base ab = an.base();
	    String why = refusal(mine, ab);
	    if(why == null) {
		/* The two bases say where each session's tile (0, 0) sits in one segment, so the difference
		 * of the two is the whole translation between the frames. Grid-aligned by construction --
		 * tickbase refuses a base that is not -- so this is exact. */
		Coord d = ab.tc().sub(mine.tc());
		offset = Coord2d.of(d.x * MCache.tilesz.x, d.y * MCache.tilesz.y);
	    } else {
		offset = null;
	    }
	    offwhy = why;
	    /* Only the reason CHANGING is news. Anchoring itself stays silent, as it always has: it happens
	     * whenever two characters come to stand in one segment, and saying so is a line per meeting. */
	    if((why != null) && !why.equals(offsaid))
		say("%s: no offset -- %s", user, why);
	    offsaid = why;
	}

	/**
	 * Why these two bases cannot be related, or null when they can. The one place that decision is
	 * made, so that a line and the number beside it can never name different reasons.
	 */
	private String refusal(Base mine, Base anchor) {
	    if(mine == null)
		return("no proved base of its own");
	    if(anchor == null)
		return("the anchor has no proved base");
	    if(mine.file != anchor.file)
		return("a different map database from the anchor's");
	    if(mine.seg() != anchor.seg())
		return("a different segment from the anchor's");
	    return(null);
	}

	/** The member's frame relative to the anchor's, in world units, or null while it is unknown. */
	public Coord2d offset() {
	    return(offset);
	}

	/**
	 * F1's proof, and it proves two things at once. This member's own character, translated into the
	 * anchor's frame, against where the anchor independently sees that same character standing — gob
	 * ids are global, so it is one object observed by two sessions. Zero is the answer. A number
	 * that grows as they walk means the offset is wrong; a number that is simply large means it was
	 * computed against the wrong grid.
	 */
	public String check() {
	    /* The screen is asked BEFORE the field, the order {@link #where()} takes and for the same
	     * reason. {@code Sessions.tick}'s member loop skips the member holding the screen, so
	     * {@code tickoffset} never runs for it and its {@code offset} still holds whatever it last
	     * measured as somebody else's member — and the anchor is at zero by construction, so reading
	     * that field here would report a distance for a session that has none. {@code placed()} applies
	     * the same correction to the same field. */
	    if(anchormember() == this)
		return("the anchor");
	    Coord2d off = this.offset;
	    if(off == null) {
		String why = this.offwhy;
		return((why == null) ? "no offset yet" : why);
	    }
	    GameUI gui = gameui();
	    Session s = this.sess;
	    Glob anchor = anchorglob();
	    if((gui == null) || (s == null) || (anchor == null))
		return("?");
	    Gob mine = s.glob.oc.getgob(gui.plid);
	    if(mine == null)
		return("self not in own view");
	    Gob seen = anchor.oc.getgob(gui.plid);
	    if(seen == null)
		return(String.format("off %s, out of the anchor's range", grids(off)));
	    Coord2d predicted = mine.rc.sub(off);
	    return(String.format("off %s err %.3ft", grids(off), predicted.dist(seen.rc) / MCache.tilesz.x));
	}

	private UI guifor = null;
	private GameUI guicache = null;

	public GameUI gameui() {
	    UI u = this.ui;
	    if(u == null)
		return(null);
	    if((guifor == u) && (guicache != null) && (guicache.parent != null))
		return(guicache);
	    guifor = u;
	    return(guicache = findgui(u));
	}

	@SuppressWarnings("deprecation")
	public String status() {
	    if(dead)
		return(user + ":dead");
	    UI u = this.ui;
	    if(u == null)
		return(user + ":connecting");
	    GameUI gui = gameui();
	    if(gui == null)
		return(user + ((u.root.findchild(Charlist.class) != null) ? ":charlist" : ":loading"));
	    Session s = this.sess;
	    int grids = (s == null) ? 0 : s.glob.map.numgrids();
	    return(String.format("%s:%s(%dg) %s", user, gui.chrid, grids, check()));
	}

	/**
	 * rts: the long form of {@link #check()} — the base this session stands in, and every number the
	 * anchoring rests on. One line, and {@code :session where} says one per member.
	 *
	 * <p>The base is the head of the line and everything else follows it, because everything else is
	 * read <em>through</em> it. Without a proved base the line ends there, carrying no coordinate at
	 * all: a place said through a base that has not been proved is a place the character has never
	 * been, and printing one is how that gets believed.
	 *
	 * <p>The refusal is decided <b>once</b>, at the top, and both halves of the line are written from
	 * that one answer. Asking twice — once for the head and once for the offset — is how a line comes
	 * to name a refusal and print a number in the same breath.
	 */
	public String where() {
	    Base b = base();
	    if(b == null)
		return(String.format("%s: no proved base -- %s", user, baseless));
	    Member an = anchormember();
	    boolean isanchor = (an == this);
	    String why = ((an == null) || isanchor) ? null : refusal(b, an.base());
	    String head = String.format("%s: base %s tile (%d, %d), proved%s",
					user, Long.toUnsignedString(b.seg(), 16), b.tc().x, b.tc().y,
					(why == null) ? "" : (", " + why));
	    GameUI gui = gameui();
	    Session s = this.sess;
	    Glob anchor = anchorglob();
	    if((gui == null) || (s == null) || (anchor == null))
		return(head);
	    Gob mine = s.glob.oc.getgob(gui.plid);
	    if(mine == null)
		return(head + " -- no character gob yet");
	    String own = tiles(mine.rc);
	    if(isanchor || (s.glob == anchor))
		return(String.format("%s -- at %s in its own frame, the anchor", head, own));
	    Coord2d off = this.offset;
	    if((why != null) || (off == null))
		return(String.format("%s -- at %s in its own frame, no offset", head, own));
	    Coord2d pred = mine.rc.sub(off);
	    Gob seen = anchor.oc.getgob(gui.plid);
	    if(seen == null)
		return(String.format("%s -- own %s -> anchor %s, offset %s grids -- gob %d is not in the anchor's view, nothing to check against",
				     head, own, tiles(pred), grids(off), gui.plid));
	    return(String.format("%s -- own %s -> anchor %s, anchor sees %s, offset %s grids, err %.3f tiles",
				 head, own, tiles(pred), tiles(seen.rc), grids(off),
				 pred.dist(seen.rc) / MCache.tilesz.x));
	}

	private static String tiles(Coord2d p) {
	    return(String.format("(%.1f, %.1f)", p.x / MCache.tilesz.x, p.y / MCache.tilesz.y));
	}

	/**
	 * The offset in grids, which is the unit it is actually measured in: two bases are grid-aligned,
	 * so the difference between them is a whole number of grids and a fraction here would mean the
	 * derivation is wrong rather than merely imprecise.
	 */
	private static String grids(Coord2d off) {
	    return(String.format("(%d, %d)",
				 Math.round(off.x / (MCache.tilesz.x * MCache.cmaps.x)),
				 Math.round(off.y / (MCache.tilesz.y * MCache.cmaps.y))));
	}
    }

    /* ------------------------------------------------------------------ *
     * Login
     * ------------------------------------------------------------------ */

    /**
     * The saved-token half of {@code Bootstrap.run}, with no login screen behind it. It reads the same
     * pref the login screen writes ({@code Bootstrap.gettoken}) and deliberately does <em>not</em>
     * clear that token when authentication fails, unlike {@code Bootstrap}: a failed session add must
     * not cost the maintainer a saved login.
     */
    private static Session connect(String user) throws IOException {
	NamedSocketAddress server = Bootstrap.authserv.get();
	String confname = server.host;
	NamedSocketAddress defserv = new NamedSocketAddress(server.host, Bootstrap.gameport.get());
	byte[] token = Bootstrap.gettoken(user, confname);
	if(token == null)
	    throw(new IOException("no saved token for: " + user + " (log it in once on the login screen)"));
	Session.User acct;
	byte[] cookie;
	List<NamedSocketAddress> hosts;
	try(AuthClient auth = new AuthClient(server)) {
	    AuthClient.Credentials creds = new AuthClient.TokenCred(user, token);
	    try {
		acct = creds.tryauth(auth);
	    } catch(AuthClient.Credentials.AuthException e) {
		throw(new IOException("saved token rejected for: " + user));
	    }
	    cookie = auth.getcookie();
	    if(Connection.encrypt.get())
		acct.alias(auth.getalias());
	    hosts = auth.gethosts(defserv);
	}
	List<InetSocketAddress> addrs = new ArrayList<InetSocketAddress>();
	if(hosts.isEmpty()) {
	    for(InetAddress host : InetAddress.getAllByName(defserv.host))
		addrs.add(new InetSocketAddress(host, defserv.port));
	} else {
	    for(NamedSocketAddress addr : hosts) {
		for(InetAddress host : InetAddress.getAllByName(addr.host))
		    addrs.add(new InetSocketAddress(host, addr.port));
	    }
	}
	if(addrs.isEmpty())
	    throw(new UnknownHostException(server.host));
	Connection.SessionError last = null;
	for(InetSocketAddress addr : addrs) {
	    try {
		return(Session.connect(addr, acct, Connection.encrypt.get(), cookie));
	    } catch(Connection.SessionConnError e) {
		last = e;
	    } catch(Connection.SessionError e) {
		throw(new IOException(e.getMessage()));
	    } catch(InterruptedException e) {
		Thread.currentThread().interrupt();
		throw(new IOException("interrupted while connecting"));
	    }
	}
	throw(new IOException((last == null) ? "could not connect" : last.getMessage()));
    }
}
