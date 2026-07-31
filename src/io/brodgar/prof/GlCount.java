package io.brodgar.prof;

import haven.render.Model;

/**
 * The armed-only GL submission counters (spec 019-profiling, task 019.6) — draw calls, program binds, and the
 * vertices and triangles handed to the driver per frame.
 *
 * <p><b>Why these are armed-only when everything in {@code p:render()} is not.</b> The counters behind
 * {@code p:render()} (draw slots, batches, instances, VRAM) are numbers the client already keeps and then
 * throws away into a {@code :stats on} format string, so 019.3 exposed them for free and they answer with
 * profiling off. These four are the opposite: <b>nothing counts them today</b>, so they are genuinely new
 * counting and they sit behind {@link Prof#on} like every other new probe in this feature.
 *
 * <p><b>Where they are counted.</b> At the two seams where the client actually submits geometry <b>per
 * frame</b>, which is not where the GL calls are written:
 *
 * <ul>
 *   <li>{@code GLDrawList.draw} — the per-frame walk of the sorted draw slots. Each slot is one draw call, and
 *       a slot whose program differs from the previous one is a program bind (that is what the list is sorted
 *       by). The vertex and triangle counts come off the slot, computed <b>once</b> when the slot is compiled
 *       (in {@code SlotRender.draw}, where the model is in hand) rather than per frame.</li>
 *   <li>{@code GLRender.draw} — the immediate path, i.e. every 2D blit and every ephemeral model, counted as
 *       it is submitted; plus {@code Applier}, whose two {@code GLProgram.apply} sites are the immediate
 *       program binds.</li>
 * </ul>
 *
 * <p>Counting inside {@code BufferBGL}'s replay instead would be exact to the GL call, but that replay is the
 * render thread's innermost loop and a counter there would be paid by every client whether armed or not. The
 * seams above are per frame, on the dispatch side, and cost one predictable branch.
 *
 * <p><b>Plain {@code long}s, not atomics.</b> These are dispatch-side counters read once per frame by the fold
 * and zeroed there; a torn increment from an off-thread submission would cost a count, not correctness, and an
 * atomic on a per-draw-call path is exactly the always-on cost 019 exists to avoid.
 */
public final class GlCount {
    private GlCount() {}

    /** This frame's counts so far, zeroed by the end-of-frame fold in {@link Prof#frame}. */
    public static long draws, progBinds, verts, tris;

    /* Overhead.hGl below counts the probe HITS, not the draw calls (spec 019, task 019.7): one hit is one
     * accumulate this feature added, which is what the modelled per-hit cost is calibrated against. */

    /** One immediate program bind ({@code Applier}). */
    public static void progBind() {
        Overhead.hGl++;
        progBinds++;
    }

    /** One immediate draw call of {@code mod} ({@code GLRender.draw}). */
    public static void draw(Model mod) {
        Overhead.hGl++;
        draws++;
        verts += verts(mod);
        tris += tris(mod);
    }

    /** A whole draw-list dispatch, folded in one add ({@code GLDrawList.draw}). */
    public static void drawlist(int ndraws, int nbinds, long nverts, long ntris) {
        // The fold itself is one hit, but the caller's walk did an accumulate PER SLOT to produce these
        // sums -- so the tier is charged for those too, or it would report the draw list as free (019.7).
        Overhead.hGl += ndraws + 1;
        draws += ndraws;
        progBinds += nbinds;
        verts += nverts;
        tris += ntris;
    }

    /** Vertices this model submits — its vertex (or index) count, once per instance. */
    public static int verts(Model mod) {
        return mod.n * mod.ninst;
    }

    /**
     * Triangles this model submits. Point and line modes contribute none: they are drawn, they are just not
     * triangles, and reporting them as such would make the number mean nothing.
     */
    public static int tris(Model mod) {
        int per;
        switch(mod.mode) {
        case TRIANGLES:
            per = mod.n / 3;
            break;
        case TRIANGLE_STRIP:
        case TRIANGLE_FAN:
            per = Math.max(0, mod.n - 2);
            break;
        default:
            per = 0;
            break;
        }
        return per * mod.ninst;
    }
}
