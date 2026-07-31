package io.brodgar.prof;

import haven.GPUProfile;
import haven.render.Render;

/**
 * The named render passes (spec 019-profiling, task 019.6) — a fixed, short, curated list of sections of the
 * frame timed on the <b>CPU and the GPU side by side</b>, so "what do shadows cost me" is a number rather than
 * a guess.
 *
 * <table>
 *   <tr><th>Pass</th><th>Seam</th></tr>
 *   <tr><td>{@code shadow}</td><td>{@code MapView.drawsmap} → {@code smap.update(out, slist)} — the entire
 *       shadow render in one call</td></tr>
 *   <tr><td>{@code scene}</td><td>{@code MapView.maindraw} → {@code PView.maindraw} — the 3D draw list</td></tr>
 *   <tr><td>{@code ui2d}</td><td>{@code UILoop.display} → {@code ui.draw(g)} — the widget tree</td></tr>
 * </table>
 *
 * <p><b>Why the list is fixed.</b> Every boundary is a real GL timestamp query, which is not free and can stall
 * the pipeline if overused. Passes are therefore curated in this file forever and never swept per draw call —
 * that is RenderDoc territory, and the plan says so explicitly.
 *
 * <p><b>The passes are disjoint.</b> {@code shadow} and {@code scene} both run <b>inside</b> the widget draw,
 * because the {@code MapView} is a widget: nested naively, the three would double-count and their sum would
 * exceed the frame. So each pass reports <b>self</b> time — its own span minus the passes nested inside it —
 * exactly the inclusive/self split 019.5 already established for widgets. {@code ui2d} is therefore what the
 * <b>2D</b> UI cost, which is what the name says, and the three sum to less than the frame by construction.
 *
 * <p><b>How the GPU side works.</b> {@code GPUProfile.Part.part(out, name)} inserts a GL timestamp query and
 * hangs the new part under the one it was called on, so arbitrary named GPU sections are an operation the
 * engine already supports — the client simply never names more than {@code tick}/{@code draw}/{@code swap}.
 * These parts hang under this frame's <b>{@code draw}</b> part (handed over by {@code UILoop.Frame.display}),
 * so the pass tree nests inside the client's own tree and {@code Profwnd} keeps showing exactly what it did,
 * with three extra rows. The results arrive late through fences, so {@link Prof} folds them into the ring by
 * frame number, the same way it already folds the GPU frame time.
 *
 * <p><b>Threading and lifetime.</b> Everything here runs on the Haven UI thread, between
 * {@link #frame(GPUProfile.Part)} at the top of the draw phase and the end-of-frame fold — one frame, no
 * locking, no allocation beyond the GPU parts the engine allocates anyway. Every seam brackets its pass in a
 * {@code try}/{@code finally}: a pass left open would leave a GPU part that never completes, and
 * {@code GPUProfile.check()} would then stall every later frame's timing behind it.
 */
public final class Passes {
    private Passes() {}

    /** The pass names, in report order. Fixed on purpose — see the class comment. */
    public static final String[] NAMES = {"shadow", "scene", "ui2d"};
    /** Indices into {@link #NAMES}. */
    public static final int SHADOW = 0, SCENE = 1, UI2D = 2;
    private static final int N = NAMES.length;

    private static final long[] start = new long[N];      // nanoTime at begin()
    private static final long[] childs = new long[N];     // nested pass time inside the open pass
    private static final long[] self = new long[N];       // this frame's exclusive CPU nanos
    private static final boolean[] open = new boolean[N];
    private static final GPUProfile.Part[] gpart = new GPUProfile.Part[N];
    private static final int[] stack = new int[N];        // the currently open passes, outermost first
    private static int depth = 0;
    private static GPUProfile.Part gparent = null;        // this frame's "draw" GPU part, or null when disarmed

    /**
     * Start a frame's pass accounting, from {@code UILoop.Frame.display}. {@code par} is this frame's
     * {@code draw} GPU part, or {@code null} when the client is not building GPU frames — in which case the
     * passes still report their CPU time and simply have no GPU column.
     */
    public static void frame(GPUProfile.Part par) {
        if(!Prof.on) {
            gparent = null;     // never hold a part from an already-finished frame across a disarm
            return;
        }
        gparent = par;
        depth = 0;
        for(int i = 0; i < N; i++) {
            open[i] = false;
            self[i] = 0;
            childs[i] = 0;
            gpart[i] = null;
        }
    }

    /**
     * Open pass {@code p}. A second open of a pass already open is ignored rather than nested — the seams are
     * once-per-frame by construction, and a profiler must never be the thing that breaks a frame.
     */
    public static void begin(Render out, int p) {
        if(!Prof.on || open[p])
            return;
        GPUProfile.Part par = (depth > 0) ? gpart[stack[depth - 1]] : gparent;
        if(par != null)
            gpart[p] = par.part(out, NAMES[p]);
        open[p] = true;
        childs[p] = 0;
        stack[depth++] = p;
        start[p] = System.nanoTime();
    }

    /** Close pass {@code p}, charging its span to itself and, minus its own nested passes, to its parent. */
    public static void end(Render out, int p) {
        if(!open[p])
            return;
        long d = System.nanoTime() - start[p];
        open[p] = false;
        if((depth > 0) && (stack[depth - 1] == p))
            depth--;
        self[p] += d - childs[p];
        if(depth > 0)
            childs[stack[depth - 1]] += d;
        GPUProfile.Part gp = gpart[p];
        if(gp != null) {
            gp.fin(out);
            gpart[p] = null;
        }
    }

    /** Pass {@code p}'s exclusive CPU time this frame, ms — read by the end-of-frame fold. */
    public static double cpuMs(int p) {
        long v = self[p];
        return (v > 0) ? (v * 1e-6) : 0;
    }
}
