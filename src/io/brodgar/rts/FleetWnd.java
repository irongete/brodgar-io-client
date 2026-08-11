package io.brodgar.rts;

import java.util.List;

import haven.Button;
import haven.Coord;
import haven.GameUI;
import haven.UI;
import haven.Widget;
import haven.Window;

/**
 * One button per live session; pressing it hands that session the screen.
 *
 * <p>Exactly what Tab does, made visible. An ordinary {@link Window} with ordinary {@link Button}s —
 * it draws nothing of its own, so it inherits the client's look, its dragging, its focus handling and
 * its theming for free, and an addon can restyle it like any other window.
 *
 * <p>It lives on the <b>drawn</b> session's HUD, which means it is rebuilt on every anchor switch:
 * each session has its own widget tree and a widget belongs to exactly one. That is also why it is
 * driven from {@link Fleet#tick()} rather than constructed once — the tree it belongs to keeps
 * changing underneath it.
 */
public class FleetWnd extends Window {
    private static final int btnw = UI.scale(120);
    private static FleetWnd cur = null;
    private static String sig = null;

    private FleetWnd() {
	super(Coord.z, "Fleet");
	/* The close button would otherwise send "close" to the server, which knows nothing about this
	 * window. Closing it simply takes it off the screen until the fleet changes again. */
	reqclose(() -> hide());
    }

    /**
     * Make the window match the fleet, and put it where it can be seen. Called every frame; does
     * nothing at all unless the answer has changed, which it does only when a session joins, leaves,
     * enters the world or takes the screen.
     */
    private static double last = 0;

    static void tick() {
	/* Nothing here is urgent -- it notices a session joining, leaving or taking the screen -- and
	 * the signature it compares allocates. Four times a second is imperceptible and free. */
	double now = haven.Utils.rtime();
	if((now - last) < 0.25)
	    return;
	last = now;
	List<Fleet.Sess> ss = Fleet.sessions();
	UI an = Fleet.anchor();
	if((ss.size() < 2) || (an == null)) {
	    /* One session is not a fleet, and a window with a single button that does nothing is
	     * clutter. It comes back by itself when a second one connects. */
	    drop();
	    return;
	}
	StringBuilder buf = new StringBuilder();
	for(Fleet.Sess s : ss)
	    buf.append(s.user).append('/').append(label(s)).append(s.isanchor ? "*" : "").append(';');
	String nsig = buf.toString();
	if((cur != null) && (cur.ui == an) && nsig.equals(sig))
	    return;
	drop();
	sig = nsig;
	build(ss, an);
    }

    private static void build(List<Fleet.Sess> ss, UI an) {
	Widget parent = host(an);
	if(parent == null)
	    return;
	FleetWnd w = new FleetWnd();
	int y = 0;
	for(Fleet.Sess s : ss) {
	    final Fleet.Member m = s.member;
	    /* The session already on screen gets a button too, disabled-looking by its label rather
	     * than by being absent: a list that hides the one you are on is a list you have to think
	     * about. */
	    /* ASCII on purpose: build.xml sets no javac encoding, so a source file is decoded with the
	     * platform default and a non-ASCII literal would reach the screen as mojibake on some
	     * machines and not others. */
	    String cap = (s.isanchor ? "> " : "") + label(s);
	    w.add(new Button(btnw, cap, () -> Fleet.anchor(m)), new Coord(0, y));
	    y += UI.scale(24);
	}
	w.pack();
	parent.add(w, new Coord(UI.scale(10), UI.scale(80)));
	cur = w;
    }

    /** The character's own name where the client knows it, and the account's where it does not yet. */
    private static String label(Fleet.Sess s) {
	String nm = (s.gui == null) ? null : s.gui.chrid;
	return(((nm == null) || nm.equals("")) ? s.user : nm);
    }

    @SuppressWarnings("deprecation")
    private static Widget host(UI u) {
	if((u == null) || (u.root == null))
	    return(null);
	GameUI gui = u.root.findchild(GameUI.class);
	return((gui != null) ? gui : u.root);
    }

    /** Bring it back after the close button — the fleet has not changed, so tick() would not rebuild. */
    public static void reopen() {
	drop();
	last = 0;
	tick();
    }

    private static void drop() {
	FleetWnd w = cur;
	cur = null;
	sig = null;
	if(w != null) {
	    try {
		w.destroy();
	    } catch(RuntimeException e) {
	    }
	}
    }
}
