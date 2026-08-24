package io.brodgar.session;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import haven.Coord2d;
import haven.MapView;
import haven.Widget;

/**
 * The RTS layer: who is selected, and what a click means while it is on.
 *
 * <p>{@link Sessions} holds the sessions; this holds the <em>control</em>. The two are separate because
 * they answer different questions — a session exists whether or not anyone is commanding it, and the
 * selection is about the anchor's screen, not about anybody's connection.
 *
 * <p><b>It owns no gesture and draws nothing.</b> Naming a character — with the mouse, with a key, or
 * from a list — is the ADDON layer's, and it arrives here through {@code hafen.session():current(s)},
 * which is {@link #take}. Showing who is selected is the addon layer's too. What is left in Java is the
 * part no addon can hold: the selection itself, and the one click the mode re-addresses.
 *
 * <p>Everything here rests on the one fact that makes the whole project cheap: when the characters are
 * in the same area, <b>the anchor already sees them all</b>. Its {@code OCache} carries the other
 * accounts' characters as ordinary gobs, and gob ids are global, so a character is found by the id the
 * member's own {@code GameUI} reports. Nothing is composed and nothing extra is rendered.
 *
 * <p>F3 of {@code specs/rts/plan.md}.
 */
public class Control {
    /**
     * While off, the map view behaves exactly as it always did. While on, <b>it still does</b>: the
     * mouse keeps every one of Haven's own bindings, and no modifier over it is claimed. What the mode
     * changes is the <em>recipient</em>: with somebody other than the drawn character selected, a left
     * click on the ground walks the selection instead of the character on screen.
     */
    public static volatile boolean on = false;

    private static final Set<Long> sel = new LinkedHashSet<Long>();

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
	/* Silent: who is selected is drawn, by the addon layer, on the character itself. A line per
	 * selection is a line per switch, and switching is what this client is for. */
    }

    /* ------------------------------------------------------------------ *
     * Input
     * ------------------------------------------------------------------ */

    public static boolean mousedown(MapView mv, Widget.MouseDownEvent ev) {
	if(!on)
	    return(false);
	int mods = mv.ui.modflags();
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
	if(!v)
	    clear();
	/* Kept, where the running commentary below it went: this one fires on the edge alone -- once when a
	 * second session arrives and once when the last one goes -- and it is the only announcement that the
	 * client now has a recipient it did not have a moment ago. What NAMES that recipient is the addon
	 * layer's hotkey, so this line does not describe a gesture it no longer owns. */
	Sessions.say("rts mode %s", v ? "on" : "off");
    }

    /**
     * Go to a character: give it the screen, and make it the whole selection. The one gesture behind
     * every way of naming a single character — {@code :session anchor}, and everything the addon layer
     * spells through {@code hafen.session():current(s)}: the switcher window's rows, its cycle hotkey and
     * its click-a-character hotkey. They are one intent said several ways, and a switch that left the
     * previous character selected would send the next order to somebody off screen.
     *
     * <p>Nothing here duplicates {@link Sessions#anchor}: that call is the whole UI switch, since every
     * other part of the layer reads {@code Sessions.anchor()} and follows by itself. What is added is
     * the selection, which the anchor knows nothing about — and <b>only</b> that. Each session's camera
     * is its own and is left exactly as the player had it, here as everywhere else in this layer.
     *
     * <p><b>A null goes to the LOGIN SCREEN</b>, which is a place to go to and not merely where dropping
     * the last session leaves you: the client's own login screen is live behind every session
     * ({@code Client.Main} builds a fresh {@code Bootstrap} the moment it hands one to
     * {@link Sessions#adopt}), so handing it the screen is how an <b>account with no saved token</b> is
     * logged in — the door {@link Sessions#add} has not got, and the one {@code hafen.session():current(nil)}
     * spells for the switcher window's <i>New session</i> button. The selection goes with it: nobody is
     * drawn, so there is no character on screen for a selection to be about, and one left naming the
     * character that just lost the screen is exactly the state the {@code ss == null} branch below clears,
     * for the same reason.
     *
     * <p>Nothing takes the screen back on its own: {@link Sessions#reclaim} returns while the login screen
     * holds it, and {@link Sessions#adopt} anchors the session it logs in precisely because none does. So
     * the sessions behind it go on ticking and answering the server, and a row of the switcher window — or
     * the login the player performs — is what leaves it.
     *
     * @param m the session to go to, or null for the login screen.
     */
    public static void take(Sessions.Member m) {
	if(m == null) {
	    Sessions.anchor(null);
	    clear();
	    return;
	}
	Sessions.anchor(m);
	/* Already the anchor is not a failure -- the selection still follows. A switch that did NOT happen
	 * (a session with no screen yet) is, and it leaves the character on screen alone rather than
	 * selecting somebody the player did not ask for. */
	if(Sessions.anchormember() != m)
	    return;
	/* Asked of the anchor rather than of m: the same character either way, and the anchor's own row is
	 * where the character id has already been worked out. */
	Sessions.Placed ss = Sessions.anchorsess();
	if(!on)
	    return;
	if(ss == null) {
	    /* The screen moved and we could not work out whose character is now on it. CLEAR rather than
	     * leave: a selection still naming the character that just LOST the screen is the worst of the
	     * three states, because Control.commands then reports somebody to command and the next left
	     * click on the ground is silently an order to a character the player is no longer looking at --
	     * they see the one they left walk off. An empty selection is the honest state, and the very next
	     * switch fills it. */
	    clear();
	    return;
	}
	only(ss.gui.plid);
    }
}
