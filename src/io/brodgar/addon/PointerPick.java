package io.brodgar.addon;

import haven.ClickData;
import haven.Coord;
import haven.Coord2d;
import haven.Gob;
import haven.MapView;
import haven.UI;

import org.luaj.vm2.LuaValue;

/**
 * <b>The pointer's pick pass</b> — what {@code hafen.ui():mouse():pick()} answers and what fires
 * {@code m:on("PickChanged", fn)}: the object the client's own machinery finds under the pointer, resolved
 * by the very pass a right-click goes through ({@link MapView.Hittest} → {@code checkgobclick} →
 * {@code MapView.clickedgob}), so the answer is never an approximation of the click's and never disagrees
 * with it.
 *
 * <p><b>ONE PASS, TWO ANSWERS.</b> {@code Hittest} resolves the ground point and the object together —
 * {@code checkmapclick} and {@code checkgobclick} in the one submission — so {@code m:ground()} is published
 * here beside {@code m:pick()} rather than costing a {@code Maptest} readback of its own. That is not only
 * cheaper: the two come out of the SAME instant, so what an addon says is under the pointer cannot be a tile
 * from one frame under an object from another.
 *
 * <p><b>"Pick" is the word on purpose.</b> The 2D question — <i>which widget contains this point</i> — is a
 * HIT TEST everywhere in the industry ({@code UIView.hitTest}, {@code VisualTreeHelper.HitTest},
 * {@code elementFromPoint}), and this API already spends {@code hit} on it ({@code hafen.ui():hit(x, y)},
 * {@code m:over()}). The 3D question — <i>which object is under this pixel</i> — is PICKING, and the
 * particular technique here, rendering ids to a buffer and reading the pixel back
 * ({@code FragID.fragid} + {@code checkgobclick}), is <i>ID-buffer picking</i> by its standard name. So the
 * two words in this API mean what they mean everywhere else, and neither has to be read twice. The engine's
 * own class calls the pass {@code Hittest}, which is the 2D word used for a 3D pass; the API does not
 * inherit that.
 *
 * <p><b>The subscription is the opt-in, and it is the whole gate.</b> A pick is a render pass and a GPU
 * readback — which is why the client itself only picks on a click — so nothing here runs unless some addon
 * holds a live {@code PickChanged} subscription. {@link #armed} is asked live rather than cached, over the
 * copy-on-write addon list: a reload that throws every {@link Addon} away disarms the pass by construction,
 * with no bookkeeping to get wrong and nothing to tear down.
 *
 * <p><b>The seam is the frame's hover, not a mouse move</b> ({@code MapView.mousehover}). It is dispatched
 * every frame and carries whether this view is the thing under the pointer, which is exactly the question
 * the pick answers for — and the world moves under a pointer that is standing still, which a move-driven
 * pick would never notice. Being told every frame, it is paced here: one pick in flight at a time, at once
 * when the pointer has moved, and otherwise no more often than {@link #IDLE}.
 *
 * <p><b>Threading.</b> {@link #onHover} runs on the UI thread, under that tree's monitor. The answer comes
 * back on {@code GLEnvironment}'s render-query callback thread, inside {@code Hittest}'s own
 * {@code synchronized(ui)} — the same thread and the same monitor a click's own dispatch already fires the
 * action stream from, so the lock order is the established one. What crosses between them is a
 * {@code volatile long} on the session's own state and this class's small monitor: nothing walks a structure
 * the frame is mutating.
 */
final class PointerPick {
    /** The one key the pointer emits. Closed set of exactly this. */
    static final String KEY = "PickChanged";

    /** No second pick until this long after the last, while the pointer holds still (the world still moves). */
    private static final long IDLE = 200_000_000L;
    /** A pick whose callback never came is abandoned after this, so a dropped readback cannot wedge the pass. */
    private static final long STALE = 2_000_000_000L;

    private static boolean inFlight = false;
    private static long flight = 0, picked = 0;
    /** The point the last pick was run for. A COPY: {@link Coord} is mutable in this engine. */
    private static Coord at = null;

    private PointerPick() {}

    /** Does any addon hold a live {@code PickChanged} subscription? Asked live — see the class comment. */
    private static boolean armed() {
        for(Addon a : AddonManager.addons) {
            if(a.pickSubs.has(KEY))
                return true;
        }
        return false;
    }

    /**
     * The frame's hover on a map view: {@code hovering} is whether the pointer is on it, {@code c} the point
     * in that view's own pixels — the space {@link MapView.Hittest} takes.
     *
     * <p>A pointer that is <b>not</b> on the world publishes "nothing" whatever else is true, subscription or
     * no subscription: a pick left standing while the pointer sits on an inventory would name an object the
     * user has plainly stopped pointing at, and that is the one answer this verb must never give.
     */
    static void onHover(final MapView mv, Coord c, boolean hovering) {
        if(mv == null)
            return;
        if(!hovering) {
            publish(mv, -1, null);
            return;
        }
        if((c == null) || !armed())
            return;
        long now = System.nanoTime();
        synchronized(PointerPick.class) {
            if(inFlight && ((now - flight) < STALE))
                return;
            if(c.equals(at) && ((now - picked) < IDLE))
                return;
            inFlight = true;
            flight = now;
            picked = now;
            at = new Coord(c);
        }
        try {
            mv.new Hittest(c) {
                protected void hit(Coord pc, Coord2d mc, ClickData inf) {
                    Gob g = MapView.clickedgob(inf);
                    // A FRESH Coord2d: the engine's own is mutable and the frame moves it.
                    done(mv, (g == null) ? -1 : g.id, (mc == null) ? null : new Coord2d(mc.x, mc.y));
                }

                protected void nohit(Coord pc) {
                    done(mv, -1, null);    // the pass reached no object AND no ground: off the map entirely
                }
            }.run();
        } catch(RuntimeException e) {
            // A view with no environment yet, a submission the frame refused: let the next frame try again
            // rather than leaving the pass wedged on a callback that will never come.
            synchronized(PointerPick.class) { inFlight = false; }
        }
    }

    /** The callback's own thread: the flight is over, and what it found is published. */
    private static void done(MapView mv, long id, Coord2d ground) {
        synchronized(PointerPick.class) { inFlight = false; }
        publish(mv, id, ground);
    }

    /**
     * Write BOTH halves of what the pass found onto that session's state, and tell the addons that asked —
     * <b>only when the OBJECT changed</b>, which is what makes the key an edge rather than a per-frame drip.
     * The ground moves with every pixel the pointer travels and is a live read instead.
     */
    private static void publish(MapView mv, long id, Coord2d ground) {
        UI ui = mv.ui;
        if((ui == null) || !AddonManager.notePick(ui, id, ground))
            return;
        String user = AddonManager.userOf(ui);
        for(Addon a : AddonManager.addons) {
            if(a.pickSubs.has(KEY))
                a.pickSubs.fire(KEY, (id < 0) ? LuaValue.NIL : LuaGob.of(a, user, id));
        }
    }
}
