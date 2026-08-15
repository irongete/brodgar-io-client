package io.brodgar.session;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

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
import haven.MapView;
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
 * The sessions this client holds open beside the one it draws.
 *
 * <p>The client renders exactly one session — the <em>anchor</em>, which is {@code UILoop.ui} and is
 * reached the way it always was. A member is a second, third, fourth session: a real
 * {@link Session} with a real {@link UI} and a real widget tree, connected, ticked and answering the
 * server, whose view is never drawn. When the characters stand in the same area the anchor already
 * sees them all — its {@code OCache} receives every nearby object — so there is nothing to compose,
 * and a member exists to be <em>commanded</em>, not to be looked at.
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
     * Is this session one the client holds but does not draw? Asked by {@code MapView}'s constructor,
     * so it must answer correctly from the moment the session exists — which is why a member registers
     * its {@code Session} before its {@code UI} is built.
     */
    public static boolean ismember(Session sess) {
	if(sess == null)
	    return(false);
	for(Member m : members) {
	    if(m.sess == sess)
		return(true);
	}
	return(false);
    }

    /**
     * The same question, asked where only the {@code Glob} is in hand — and since F5 it has a second
     * half. A session is dormant when it is not the one being drawn, and that now includes the
     * <em>main</em> session, which gives up the screen whenever a member takes it.
     */
    public static boolean dormant(Glob glob) {
	if(glob == null)
	    return(false);
	if(glob == anchorglob())
	    return(false);
	UI mu = mainui();
	if((mu != null) && (mu.sess != null) && (mu.sess.glob == glob))
	    return(true);
	for(Member m : members) {
	    Session s = m.sess;
	    if((s != null) && (s.glob == glob))
		return(true);
	}
	return(false);
    }

    /* ------------------------------------------------------------------ *
     * The per-frame tick
     * ------------------------------------------------------------------ */

    /**
     * Advance every member by one frame. Deliberately no {@code gtick}: a member has nothing in a
     * render tree, so there is no render stream of its own to feed. No members at all is one list check.
     */
    public static void tick() {
	invalidate();
	flushsay();
	reclaim();          // rts: (F5)
	tickrebind();       // rts: (F6)
	applymute();        // rts: (F6)
	SessionWnd.tick();    // rts: the session switcher, on the HUD of whichever session is drawn
	if(members.isEmpty())
	    return;
	tickmainoffset();   // rts: (F5)
	UI an = anchor();
	for(Member m : members) {
	    UI u = m.ui;
	    /* The member holding the anchor is ticked by the frame itself, in full. Ticking it again
	     * here would advance its clock twice a frame. */
	    if((u == null) || m.dead || (u == an))
		continue;
	    try {
		synchronized(u) {
		    if(u.sess != null)
			u.sess.glob.ctick();
		    u.tick();
		}
		m.autoplay(u);
		m.tickoffset(anchorglob());
	    } catch(RuntimeException e) {
		new Warning(e, String.format("session: tick failed for %s", m.user)).issue();
	    }
	}
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


    /** rts: (F5) the main runner's session — the one {@code Client.Main} owns, drawn or not. */
    public static UI mainui() {
	UILoop lp = loop;
	return((lp == null) ? null : lp.ui);
    }

    static Glob anchorglob() {
	UI u = anchor();
	return(((u == null) || (u.sess == null)) ? null : u.sess.glob);
    }

    /* rts: (F6) the addon engine follows the session on screen.
     *
     * It is a single-session static hub, so "follows" can only mean AddonManager.init -- the same call
     * a relog makes, and the only rebind that exists: it tears the addons down, resets every
     * per-session cache that names the old session's widgets, gobs and markers, and loads them again.
     * Leaving it behind instead was tried and is worse in a visible way: an addon's own windows live
     * on ITS session's root, so they vanish from the screen the moment you tab, while the addon goes
     * on acting on a character you are not looking at.
     *
     * Deferred to the tick rather than done in anchor(), which runs inside the drawn UI's monitor:
     * init() takes the AddonManager lock and then the target UI's, and this way no two of those three
     * are ever held at once. It also coalesces a burst of tabbing into one rebind.
     *
     * The cost is a full addon reload per switch, and it is reported rather than hidden -- if that
     * number is bad, the fix is per-session addon state, which is its own project and not this one. */
    private static volatile boolean rebind = false;

    private static void tickrebind() {
	if(!rebind)
	    return;
	rebind = false;
	long t0 = System.nanoTime();
	UI u = anchor();
	if((u == null) || (u.sess == null))
	    return;
	io.brodgar.addon.AddonManager.init(u);
	MapView mv = mapview(u);
	if(mv != null)
	    io.brodgar.addon.AddonManager.attach(mv);
	say("addons rebound in %d ms", (System.nanoTime() - t0) / 1000000L);
    }

    /* rts: (F6) one shared Audio.Root feeds every session, so without this they all play at once --
     * a member's chat pings and its ambient noise over the view you are actually watching. Only ever
     * on a change of anchor: nothing else can alter the answer, and this runs every frame. */
    private static UI mutedfor = null;

    private static void applymute() {
	UI an = anchor();
	if(an == mutedfor)
	    return;
	mutedfor = an;
	for(Placed ss : placed()) {
	    if(ss.ui.audio != null)
		ss.ui.audio.mute(!ss.isanchor);
	}
    }

    /* rts: (F5) a session that has gone must not keep the screen. A member holding the anchor can die
     * at any moment -- dropped, disconnected, logged out from the other end -- and its run() unwinds
     * on its own thread, where touching the render trees would be wrong. It leaves the corpse in
     * `members`-minus-one and this notices, on the UI thread, on the next frame. */
    private static void reclaim() {
	UI an = anchor();
	UI mu = mainui();
	if((an == null) || (an == mu))
	    return;
	for(Member m : members) {
	    if(m.ui == an)
		return;
	}
	anchor(null);
    }

    /** rts: (F5) advance a session that is alive but not on screen. */
    public static void tickbg(UI u) {
	if(u == null)
	    return;
	try {
	    synchronized(u) {
		if(u.sess != null)
		    u.sess.glob.ctick();
		u.tick();
	    }
	} catch(RuntimeException e) {
	    new Warning(e, "rts: background tick failed").issue();
	}
    }

    /* ------------------------------------------------------------------ *
     * F5: whose screen it is
     * ------------------------------------------------------------------ */

    /** The member currently holding the anchor, or null when the main session has it. */
    public static Member anchormember() {
	UI an = anchor();
	for(Member m : members) {
	    if(m.ui == an)
		return(m);
	}
	return(null);
    }

    /**
     * Hand the screen to a member, or back to the main session with {@code null}.
     *
     * <p>Three things move, and nothing else has to: which UI the frame draws and dispatches to, and
     * the dormancy of the two views involved. Everything else in the project reads
     * {@link #anchor()} — the offsets, the orders, the merged patches, the selection — so it all
     * follows by itself. The offsets in particular are measured <em>against</em> the anchor, and
     * {@code tickoffset} already drops one whose anchor changed, so they re-derive on their own.
     */
    public static synchronized void anchor(Member m) {
	UILoop lp = loop;
	if(lp == null)
	    return;
	UI target = (m == null) ? mainui() : m.ui;
	if(target == null) {
	    say("that session has no screen yet");
	    return;
	}
	UI cur = anchor();
	if(target == cur)
	    return;
	MapView oldmv = mapview(cur), newmv = mapview(target);
	lp.drawn(target);
	if(newmv != null) {
	    synchronized(target) {
		newmv.dormant(false);
	    }
	}
	if((oldmv != null) && (cur != null)) {
	    synchronized(cur) {
		oldmv.dormant(true);
	    }
	}
	/* The session cache says which one is the anchor, and it was built before this line. Anything
	 * asked between here and the next tick -- recam() first of all -- would otherwise be answered
	 * about the session that just lost the screen. */
	invalidate();
	Control.recam();
	rebind = true;
	say("anchor: %s", (m == null) ? "main" : m.user);
    }

    /** Cycle: main, then each member in turn, then back. */
    public static void next() {
	List<Member> ms = members();
	Member cur = anchormember();
	if(cur == null) {
	    anchor(ms.isEmpty() ? null : ms.get(0));
	    return;
	}
	int i = ms.indexOf(cur);
	anchor(((i < 0) || (i + 1 >= ms.size())) ? null : ms.get(i + 1));
    }

    @SuppressWarnings("deprecation")
    static MapView mapview(UI u) {
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

    /* ------------------------------------------------------------------ *
     * F2: the order
     * ------------------------------------------------------------------ */

    /**
     * Walk a member to a place named in the <em>anchor's</em> coordinates.
     *
     * <p>This is the whole thesis of the project in six lines. The member is not drawn, has no camera,
     * no click-map and no scene — and none of that is needed, because an order is not a mouse event.
     * It is the widget message a left-click on that patch of ground would have produced, and
     * {@code UI.rawWdgmsg} resolves the widget id from the member's <em>own</em> tree and hands it to
     * the member's <em>own</em> session. The one thing that has to be right is the coordinate, and
     * that is F1.
     *
     * <p>The screen coord the protocol carries is not what picks the destination — the map coord is.
     * The centre of the member's own view is passed for it, which is both plausible and meaningless.
     *
     * @return false if the member cannot be ordered yet — no offset, or not in the world.
     */
    public static boolean order(Member m, Coord2d anchorpos, long targetgob, int btn, int mods) {
	Coord2d mc = m.tomember(anchorpos);
	if(mc == null)
	    return(false);
	GameUI gui = m.gameui();
	UI u = m.ui;
	Session s = m.sess;
	if((gui == null) || (gui.map == null) || (u == null) || (s == null))
	    return(false);
	send(u, gui.map, s.glob, mc, targetgob, btn, mods, true);
	return(true);
    }

    /**
     * The grid-coordinate difference between two sessions' frames, or null when they share no loaded
     * ground. Every shared grid must agree -- two frames are rigid translations of one another or they
     * are not frames -- and disagreement is reported, never averaged away.
     */
    static Coord findoffgc(Glob anchor, Glob mine, int[] nshared, boolean[] conflict) {
	Map<Long, Coord> aids = anchor.map.gridids();
	if(aids.isEmpty())
	    return(null);
	Coord d = null;
	int n = 0;
	for(Map.Entry<Long, Coord> e : mine.map.gridids().entrySet()) {
	    Coord agc = aids.get(e.getKey());
	    if(agc == null)
		continue;
	    Coord cd = e.getValue().sub(agc);
	    if(d == null)
		d = cd;
	    else if(!d.equals(cd))
		conflict[0] = true;
	    n++;
	}
	nshared[0] = n;
	return(d);
    }

    static Coord2d worldoff(Coord gcoff) {
	Coord t = gcoff.mul(MCache.cmaps);
	return(Coord2d.of(t.x * MCache.tilesz.x, t.y * MCache.tilesz.y));
    }

    /* rts: (F5) the MAIN session's own offset. It was never needed while the main session WAS the
     * anchor -- an offset to itself is zero -- but the moment a member can hold the screen, the main
     * session becomes just another session standing somewhere else, and everything the members needed
     * (a merged patch, a selectable character, an order in its own frame) it needs too. */
    private static Coord2d mainoff = null;
    private static Glob mainoffanchor = null, mainoffglob = null;
    private static double mainofftry = 0;

    private static void tickmainoffset() {
	UI mu = mainui();
	Glob an = anchorglob();
	if((mu == null) || (mu.sess == null) || (an == null))
	    return;
	Glob mine = mu.sess.glob;
	if(mine == an) {
	    mainoff = Coord2d.of(0, 0);
	    mainoffanchor = an;
	    mainoffglob = mine;
	    return;
	}
	if((mainoffanchor != an) || (mainoffglob != mine)) {
	    mainoff = null;
	    mainoffanchor = an;
	    mainoffglob = mine;
	    mainofftry = 0;
	}
	if(mainoff != null)
	    return;
	double now = Utils.rtime();
	if((mainofftry != 0) && (now - mainofftry < 1.0))
	    return;
	mainofftry = now;
	int[] n = new int[1];
	boolean[] conflict = new boolean[1];
	Coord d = findoffgc(an, mine, n, conflict);
	if(d == null)
	    return;
	mainoff = worldoff(d);
	say("main session anchored on %d shared grid%s%s", n[0], (n[0] == 1) ? "" : "s",
	    conflict[0] ? " -- GRIDS DISAGREE" : "");
    }

    /**
     * rts: (F5) one live session, located relative to whichever session currently holds the screen.
     *
     * <p>Introduced because the anchor stopped being the main session. Up to F4 "the anchor's own
     * character" and "the main character" were the same thing and every lookup could special-case it;
     * once a member can hold the screen they are different, and the special case turns into a
     * character that cannot be seen, selected or ordered the moment you tab away from it. One uniform
     * list, the anchor being simply the session whose offset is zero.
     */
    public static class Placed {
	public final String user;
	public final UI ui;
	public final GameUI gui;
	public final Glob glob;
	public final Coord2d offset;   // this session's frame minus the anchor's
	public final Member member;    // null for the main session
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

	/** This character's place in the ANCHOR's coordinates, seen from its own session. */
	public Coord2d charpos() {
	    Gob g = glob.oc.getgob(gui.plid);
	    return((g == null) ? null : g.rc.sub(offset));
	}
    }

    /* rts: rebuilt at most once a frame. It is asked for by the merged views, by the selection, by
     * the switcher window and by the mute -- and it allocates a list, a Placed per session and (before
     * the cache above) a tree walk per session every single time. Nothing it reports can change
     * within a frame: the tick is the only thing that moves any of it. */
    private static List<Placed> placedcache = null;

    static void invalidate() {
	placedcache = null;
    }

    public static List<Placed> placed() {
	List<Placed> c = placedcache;
	if(c != null)
	    return(c);
	return(placedcache = buildplaced());
    }

    private static List<Placed> buildplaced() {
	List<Placed> ret = new ArrayList<Placed>();
	UI an = anchor();
	UI mu = mainui();
	if((mu != null) && (mu.sess != null) && (mu.root != null)) {
	    GameUI gui = mainguiof(mu);
	    Coord2d off = (mu == an) ? Coord2d.of(0, 0) : mainoff;
	    if((gui != null) && (off != null))
		ret.add(new Placed("main", mu, gui, mu.sess.glob, off, null, mu == an));
	}
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
    public static Placed bysess(long gobid) {
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
     */
    public static double groundz(Coord2d anchorpos, double dflt) {
	for(Member m : members) {
	    Coord2d off = m.offset();
	    Session s = m.sess;
	    if((off == null) || (s == null))
		continue;
	    try {
		return(s.glob.map.getzp(anchorpos.add(off)).z);
	    } catch(Loading e) {
	    }
	}
	return(dflt);
    }

    /**
     * Order one unit, named by its gob id. Ids are global, so the same number identifies the character
     * in the anchor's view, in its own session, and in the selection — there is no per-session
     * bookkeeping to keep straight. An id that belongs to no member is the anchor's own character,
     * which needs no translation and sends through its ordinary click path.
     */
    public static boolean orderunit(long unitid, Coord2d anchorpos, long targetgob, int btn, int mods) {
	Placed ss = bysess(unitid);
	if((ss == null) || (ss.gui.map == null))
	    return(false);
	/* One rule for every session: translate into its frame, send down its own socket. The anchor's
	 * offset is zero, so it needs no special case -- and the session on screen sends through wdgmsg
	 * so its orders stay indistinguishable from real clicks to the addon action hooks. */
	send(ss.ui, ss.gui.map, ss.glob, anchorpos.add(ss.offset), targetgob, btn, mods, !ss.isanchor);
	return(true);
    }

    /**
     * The message a left-click would have produced, built in the recipient's own frame.
     *
     * <p>A target gob is passed by <b>id</b> and looked up here, in the recipient's own {@code OCache},
     * rather than relayed: {@code Gob.GobClick.clickargs} is
     * {@code {0, id, gob.rc.floor(posres), 0, -1}} and that {@code rc} is in the <em>observer's</em>
     * frame. Copying the anchor's version of it would send every member a coordinate belonging to
     * somebody else's login. A unit that cannot see the target walks to the spot instead, which is
     * what a click on ground it could not identify would have done anyway.
     *
     * <p>The anchor sends through {@code wdgmsg} — its orders are indistinguishable from real clicks
     * and should stay visible to the addon action hooks. A member sends through {@code rawWdgmsg}:
     * that chain belongs to the anchor and knows nothing about the session it would be walking for.
     */
    private static void send(UI u, MapView mv, Glob glob, Coord2d mc, long targetgob, int btn, int mods, boolean raw) {
	Object[] args = {mv.sz.div(2), mc.floor(OCache.posres), btn, mods};
	if(targetgob >= 0) {
	    Gob g = glob.oc.getgob(targetgob);
	    if(g != null)
		args = Utils.extend(args, new Object[] {0, (int)g.id, g.rc.floor(OCache.posres), 0, -1});
	}
	synchronized(u) {
	    if(raw)
		u.rawWdgmsg(mv, "click", args);
	    else
		u.wdgmsg(mv, "click", args);
	}
    }



    static GameUI anchorgameui() {
	Placed ss = anchorsess();
	return((ss == null) ? null : ss.gui);
    }

    /* rts: Widget.findchild is a RECURSIVE walk of the whole tree, and this used to run once per
     * session per placed() call, three times a frame. A GameUI is made once when a session enters
     * the world and stays put, so the answer is cached and re-derived only when the widget it names
     * has left the tree. */
    @SuppressWarnings("deprecation")
    static GameUI findgui(UI u) {
	return(((u == null) || (u.root == null)) ? null : u.root.findchild(GameUI.class));
    }

    private static UI mainguifor = null;
    private static GameUI mainguicache = null;

    static GameUI mainguiof(UI u) {
	if(u == null)
	    return(null);
	if((mainguifor == u) && (mainguicache != null) && (mainguicache.parent != null))
	    return(mainguicache);
	mainguifor = u;
	return(mainguicache = findgui(u));
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

    /**
     * Connect an account with a saved token and hold it open.
     *
     * @param user the account name, as the login screen knows it
     * @param chr  the character to play, or {@code null} to play whichever the server offers first
     */
    public static Member add(String user, String chr) throws IOException {
	UILoop lp = loop;
	if(lp == null)
	    throw(new IllegalStateException("rts: no UI loop yet"));
	for(Member m : members) {
	    if(m.user.equals(user))
		throw(new IOException("already a live session: " + user));
	}
	Session sess = connect(user);
	Member m = new Member(user, chr, sess);
	/* Registered BEFORE the UI exists: UI's constructor calls Runner.init, which is where a session
	 * would otherwise capture the addon engine, and the server's first widgets can arrive at once. */
	members.add(m);
	try {
	    m.start(lp);
	} catch(RuntimeException e) {
	    members.remove(m);
	    sess.close();
	    throw(e);
	}
	return(m);
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
	 * has a different number in each. A server grid id does not move: one grid both sessions have
	 * loaded pins the whole frame, as the difference of its two session-local grid coords. Constant
	 * for a session pair, so it is found once and kept -- and dropped whole if either side relogs. */
	private volatile Coord offgc = null;      // in grids
	private volatile Coord2d offset = null;   // the same, in world units
	private volatile int offshared = 0;
	private volatile boolean offconflict = false;
	private Glob offmine = null, offanchor = null;
	private double offtry = 0;

	private Member(String user, String chr, Session sess) {
	    this.user = user;
	    this.chr = chr;
	    this.sess = sess;
	}

	private void start(UILoop lp) {
	    RemoteUI rui = new RemoteUI(sess);
	    UI u = lp.bgui(rui);
	    this.ui = u;
	    Thread t = new HackThread(() -> run(lp, rui, u), "session-" + user);
	    t.setDaemon(true);
	    this.th = t;
	    t.start();
	}

	/**
	 * The member's own runner chain, exactly the shape {@code Client.Main} runs for the anchor: a
	 * {@code RemoteUI} that returns another one when the server hands the session on, and null when
	 * it is over.
	 */
	private void run(UILoop lp, UI.Runner fun, UI first) {
	    UI u = first;
	    try {
		while(true) {
		    UI.Runner next = fun.run(u);
		    u.destroy();
		    u = null;
		    if(next == null)
			break;
		    fun = next;
		    /* Re-register the new session before its UI exists, for the same reason as in add(). */
		    if(fun instanceof RemoteUI)
			this.sess = ((RemoteUI)fun).sess;
		    this.played = false;
		    this.ui = u = lp.bgui(fun);
		}
	    } catch(InterruptedException e) {
	    } catch(RuntimeException e) {
		new Warning(e, String.format("session: %s died", user)).issue();
	    } finally {
		boolean announce = !dead;   /* a drop() already said so */
		dead = true;
		members.remove(this);
		this.ui = null;
		if(announce)
		    say("%s: session ended", user);
		if(u != null) {
		    try {
			u.destroy();
		    } catch(RuntimeException e) {
		    }
		}
	    }
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
	 * Find the offset, once, and keep it. Runs every frame and costs one reference comparison after
	 * it has succeeded; before that it is throttled, because each attempt snapshots both sessions'
	 * grid tables.
	 *
	 * <p>Every shared grid must yield the same difference — the two frames are rigid translations of
	 * one another, or they are not frames at all. Disagreement is reported rather than averaged
	 * away: it would mean something is wrong with this premise, and quietly picking one answer is
	 * how that would stay hidden.
	 */
	private void tickoffset(Glob anchor) {
	    Session s = this.sess;
	    if((s == null) || (anchor == null))
		return;
	    Glob mine = s.glob;
	    if(mine == anchor)
		return;
	    if((offmine != mine) || (offanchor != anchor)) {
		offmine = mine;
		offanchor = anchor;
		offgc = null;
		offset = null;
		offshared = 0;
		offconflict = false;
		offtry = 0;
	    }
	    if(offset != null)
		return;
	    double now = Utils.rtime();
	    if((offtry != 0) && (now - offtry < 1.0))
		return;
	    offtry = now;
	    int[] n = new int[1];
	    boolean[] conflict = new boolean[1];
	    Coord d = findoffgc(anchor, mine, n, conflict);
	    if(d == null)
		return;   /* no ground in common: the two are not near each other, which is a state, not a fault */
	    offshared = n[0];
	    offconflict = conflict[0];
	    offgc = d;
	    Coord t = d.mul(MCache.cmaps);
	    offset = Coord2d.of(t.x * MCache.tilesz.x, t.y * MCache.tilesz.y);
	    say("%s: anchored on %d shared grid%s, offset %s tiles%s", user, n[0], (n[0] == 1) ? "" : "s", t,
		conflict[0] ? " -- GRIDS DISAGREE, offset is not trustworthy" : "");
	}

	/** The member's frame relative to the anchor's, in world units, or null while it is unknown. */
	public Coord2d offset() {
	    return(offset);
	}

	/**
	 * This character's position in the <em>anchor's</em> coordinates, wherever it is seen from.
	 *
	 * <p>The anchor's own view of it is preferred and is all there is while they are near each other.
	 * Once they separate the anchor stops streaming the gob entirely — the character is still there,
	 * still walking, still rendered into the scene by the merged view, but only its own session can
	 * see it. Asking that session and translating is what keeps it selectable at any distance.
	 */
	public Coord2d anchorpos() {
	    GameUI gui = gameui();
	    Glob anchor = anchorglob();
	    if((gui == null) || (anchor == null))
		return(null);
	    Gob g = anchor.oc.getgob(gui.plid);
	    if(g != null)
		return(g.rc);
	    Session s = this.sess;
	    Gob mine = (s == null) ? null : s.glob.oc.getgob(gui.plid);
	    return((mine == null) ? null : toanchor(mine.rc));
	}

	/** A place in this member's own coordinates, said in the anchor's. */
	public Coord2d toanchor(Coord2d p) {
	    Coord2d off = this.offset;
	    return((off == null) ? null : p.sub(off));
	}

	/** A place in the anchor's coordinates, said in this member's — which is what an order needs. */
	public Coord2d tomember(Coord2d p) {
	    Coord2d off = this.offset;
	    return((off == null) ? null : p.add(off));
	}

	/**
	 * F1's proof, and it proves two things at once. This member's own character, translated into the
	 * anchor's frame, against where the anchor independently sees that same character standing — gob
	 * ids are global, so it is one object observed by two sessions. Zero is the answer. A number
	 * that grows as they walk means the offset is wrong; a number that is simply large means it was
	 * computed against the wrong grid.
	 */
	public String check() {
	    Coord2d off = this.offset;
	    if(off == null)
		return("unanchored");
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
		return("out of the anchor's range");
	    Coord2d predicted = mine.rc.sub(off);
	    return(String.format("err %.3ft", predicted.dist(seen.rc) / MCache.tilesz.x));
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

	/** rts: the long form of {@link #check()} — every number the anchoring rests on. */
	public String where() {
	    GameUI gui = gameui();
	    Session s = this.sess;
	    Glob anchor = anchorglob();
	    if((gui == null) || (s == null) || (anchor == null))
		return(user + ": not in the world yet");
	    Gob mine = s.glob.oc.getgob(gui.plid);
	    if(mine == null)
		return(user + ": no character gob yet");
	    Coord2d off = this.offset;
	    String own = tiles(mine.rc);
	    if(off == null)
		return(String.format("%s: at %s in its own frame -- unanchored (%d grids loaded, none shared with the anchor)",
				     user, own, s.glob.map.numgrids()));
	    Coord2d pred = mine.rc.sub(off);
	    Gob seen = anchor.oc.getgob(gui.plid);
	    if(seen == null)
		return(String.format("%s: own %s -> anchor %s, %d shared grids -- gob %d is not in the anchor's view, nothing to check against",
				     user, own, tiles(pred), offshared, gui.plid));
	    return(String.format("%s: own %s -> anchor %s, anchor sees %s, err %.3f tiles (%d shared grids%s)",
				 user, own, tiles(pred), tiles(seen.rc),
				 pred.dist(seen.rc) / MCache.tilesz.x, offshared,
				 offconflict ? ", DISAGREEING" : ""));
	}

	private static String tiles(Coord2d p) {
	    return(String.format("(%.1f, %.1f)", p.x / MCache.tilesz.x, p.y / MCache.tilesz.y));
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
