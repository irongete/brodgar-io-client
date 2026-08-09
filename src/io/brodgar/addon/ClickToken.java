package io.brodgar.addon;

import haven.Coord;

/**
 * The <b>click token</b> (047.3) — the one-shot record that lets {@code hafen.flowermenu():gob()} answer
 * <i>which object this ring belongs to</i>.
 *
 * <p><b>The server never says.</b> A radial menu arrives as a bare {@code "sm"} widget carrying a list of
 * captions and nothing else: no gob id, no anchor, no hint of what was clicked. So the answer is a
 * <b>correlation the client makes for itself</b>, and the whole of this class is about making that correlation
 * exact rather than merely probable — which is also why {@code :gob()} is documented as answering {@code nil}
 * rather than guessing.
 *
 * <p><b>The correlator is {@link haven.UI#lcc}</b>, the screen point of the last mouse press.
 * {@code UI.mousedown} assigns it on <i>every</i> press, before the event is dispatched to anything at all, and
 * {@code FlowerMenu.added()} places the ring at that very point. A token noted at a click and claimed at a menu
 * therefore match only when <b>no other press happened in between</b>: any intervening interaction moves
 * {@code lcc} and the token stops matching by itself. That is what makes a menu opened from an inventory item,
 * from the Kin window, or from a click the player made and abandoned answer {@code nil} — without this class
 * ever having to enumerate what else can put a ring on the screen.
 *
 * <p><b>Consume-once.</b> The first menu to claim a token takes it and the next finds nothing, so one click
 * attributes at most one menu; and a claim that does <i>not</i> match consumes it too, because a token the
 * pointer has already moved away from is stale whoever asks. A time {@link #WINDOW} is the backstop for the one
 * case {@code lcc} alone cannot rule out: the player clicks, nothing opens, and much later the server puts a
 * menu of its own up with the mouse never having moved.
 *
 * <p>The shipped precedent is {@link haven.VoiceTarget}, which attributes a click to a menu by a time window
 * alone; this is that shape made exact. Note that {@link Coord} is <b>mutable</b> in this engine (and
 * {@code Coord.z} is one shared object), so the recorded point is copied rather than referenced.
 *
 * <p><b>Threading.</b> The note happens on whichever thread resolves the click hit-test, the claim on the UI
 * thread inside {@code added()}; both go through this class's monitor, so a token is either wholly visible or
 * not there at all. Not instantiable.
 */
final class ClickToken {
    /**
     * How long a token stays claimable. Five seconds is a click-to-menu round trip with room for a bad
     * connection — it is a <b>backstop</b>, not the mechanism: {@code lcc} equality is what actually decides,
     * and it is exact. Erring long costs nothing that the pointer moving does not already cover.
     */
    private static final long WINDOW = 5_000_000_000L;

    /** The gob the last press resolved to ({@code -1} = the ground, or nothing pending). */
    private static long gob = -1;
    /** {@code UI.lcc} as it stood at that press — a COPY, since Coord is mutable. */
    private static Coord at = null;
    /** {@code System.nanoTime()} at that press, for {@link #WINDOW}. */
    private static long nanos = 0;

    private ClickToken() {}

    /**
     * Record the object a press resolved to. {@code gobId < 0} says "the ground" — still worth noting, because
     * it <b>replaces</b> whatever was pending, which is exactly what a click on nothing should do to an older
     * attribution.
     */
    static synchronized void note(long gobId, Coord lcc) {
        gob = gobId;
        at = (lcc == null) ? null : new Coord(lcc);
        nanos = System.nanoTime();
    }

    /**
     * Claim the token for a menu that just opened at {@code lcc}, or {@code -1} if there is nothing to claim —
     * no press, the ground, a different press point, or too long ago. Either way the token is spent.
     */
    static synchronized long take(Coord lcc) {
        long g = gob;
        Coord c = at;
        long t = nanos;
        gob = -1;                       // consume-once, match or not: a token belongs to ONE menu
        at = null;
        if((g < 0) || (c == null) || (lcc == null) || !c.equals(lcc))
            return -1;
        if((System.nanoTime() - t) > WINDOW)
            return -1;
        return g;
    }
}
