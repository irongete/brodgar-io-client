package io.brodgar.prof;

/**
 * What profiling itself costs, per tier (spec 019-profiling, task 019.7) — the accounting behind
 * {@code hafen.client:profiling():overhead()}.
 *
 * <p>019's second guarantee is a <b>budget, not a hope</b>: armed overhead ≤5% of frame time, target ≤2%, and
 * a tier that cannot meet it ships behind its own checkbox instead of dragging the feature down. That rule is
 * only enforceable if the cost is (a) known and (b) <b>attributed to a tier</b>. This class is both.
 *
 * <h2>Three layers, none of which pays for the others</h2>
 *
 * <ol>
 *   <li><b>The aggregator is timed directly.</b> One {@code nanoTime} pair per frame around the whole
 *       end-of-frame fold in {@link Prof#frame} — the only place this feature does real work rather than
 *       incrementing something. Exact, and its own cost is that one pair.
 *
 *       <p>Note where it sits: the fold runs in {@code UILoop.framedone}, <b>after</b> {@code CPUProfile.end}
 *       has closed the frame, so it is outside every phase the client measures and no control frame could ever
 *       see it. It is still real cost — it delays the next frame — which is exactly why it is measured on its
 *       own and added to the total rather than being folded into layer 3.</li>
 *
 *   <li><b>Probe cost is counted, not timed.</b> Timing each probe would roughly double the cost of the thing
 *       being reported, so every probe instead bumps a counter it was going to touch anyway, and the per-hit
 *       cost is <b>calibrated once</b> when the switch arms ({@link #calibrate()}). Overhead is then
 *       {@code hits × unitCost}. Two units are measured, because the probes come in two shapes: a
 *       {@link #unitBracket} (a {@code System.nanoTime()} pair plus an accumulate — the widget and pass
 *       probes) and a {@link #unitAccum} (an accumulate inside a bracket that already existed — the per-addon
 *       category split and the GL submission counters).</li>
 *
 *   <li><b>Control frames.</b> The last frame of every {@value #PERIOD} runs with the probes <b>disarmed</b>
 *       ({@link Prof#begin()}), so comparing it against the armed frames around it is a <b>measured</b> total
 *       at essentially zero marginal cost — and it catches everything layers 1–2 model badly: cache effects,
 *       JIT deopt, GPU query stalls.
 *
 *       <p><b>Work, not frame time.</b> The comparison is over frame time <b>minus the {@code wait} and
 *       {@code dwait} phases</b>, because a client running under a frame cap or vsync spends the difference
 *       idling: total frame time is pinned to the cap and would show a delta of zero no matter what the
 *       probes cost. Work time is what actually moves.
 *
 *       <p><b>Paired and median-based.</b> Each period yields one delta — the median work of its armed frames
 *       minus its control frame — and the reported figure is the median of those deltas over the last
 *       {@value #DELTAS} periods. Pairing inside a period removes drift; the medians remove the spikes. Two
 *       running means, which is the obvious implementation, do neither: measured against a real session they
 *       reported the overhead as <b>negative by a millisecond</b>, because frame work time is spiky and the
 *       signal here is a fraction of a percent of it.
 *
 *       <p><b>When the measurement says nothing.</b> A delta at or below zero is not a client that got
 *       faster; it is a cost below this comparison's noise floor, which is reported alongside it as
 *       {@link #measuredSpreadMs()}. In that case the model — which at least knows how many probes ran —
 *       is the better total, so {@code method} stays {@code "model"} and the measured figure is reported
 *       anyway. For a feature whose whole claim is that it costs almost nothing, an unresolvable measurement
 *       is the expected outcome, not a failure.</p></li>
 * </ol>
 *
 * <h2>Tiers</h2>
 *
 * Five, one per independently armable body of probes — a superset of the four the task names, because the GL
 * submission counters (019.6) are a separate probe set from the named passes and could be moved behind their
 * own checkbox separately:
 *
 * <table>
 *   <tr><th>Tier</th><th>Probes</th><th>How its cost is known</th></tr>
 *   <tr><td>{@code frame}</td><td>the end-of-frame fold</td><td>timed directly, exact</td></tr>
 *   <tr><td>{@code addons}</td><td>the {@code callLua} category split (019.4)</td><td>modelled: hits × accum</td></tr>
 *   <tr><td>{@code widgets}</td><td>the tick/draw brackets on {@code Widget} (019.5)</td><td>modelled: hits × bracket</td></tr>
 *   <tr><td>{@code passes}</td><td>the {@code shadow}/{@code scene}/{@code ui2d} seams (019.6)</td>
 *       <td>modelled brackets <b>plus</b> the GL timestamp queries, which are timed directly</td></tr>
 *   <tr><td>{@code gl}</td><td>the draw/bind/vertex counters (019.6)</td><td>modelled: hits × accum</td></tr>
 * </table>
 *
 * When the measured (control-frame) total is available it supersedes the modelled sum, and each tier is
 * attributed its <b>share of the measured total in the modelled proportion</b> — the model is a good relative
 * answer even when it is a poor absolute one, and this way the tiers always add up to the number the budget is
 * judged against. Both figures are reported, so nothing is hidden behind the scaling.
 *
 * <h2>Threading and cost when off</h2>
 *
 * Everything here runs on the Haven UI thread except the counter increments, which happen wherever their probe
 * does and are plain {@code long}s for the reason {@link GlCount} gives: a torn increment costs a count, not
 * correctness, and an atomic on a per-widget path is the always-on cost 019 exists to avoid. Disarmed, nothing
 * in this class is reached at all — every increment sits inside a probe that has already read {@link Prof#on}.
 */
public final class Overhead {
    private Overhead() {}

    /** Tier names, in report order. */
    public static final String[] TIERS = {"frame", "addons", "widgets", "passes", "gl"};
    /** Indices into {@link #TIERS}. */
    public static final int T_FRAME = 0, T_ADDONS = 1, T_WIDGETS = 2, T_PASSES = 3, T_GL = 4;
    private static final int N = TIERS.length;

    /** One frame in this many runs disarmed, as the control sample. */
    public static final int PERIOD = 64;
    /** How many paired periods the measured comparison needs before it may supersede the model. */
    public static final int MINCTL = 16;

    // ------------------------------------------------------------------ the hit counters (layer 2)

    /*
     * Cumulative since the last arm/reset, divided by the armed frame count at snapshot time. Cumulative
     * rather than per-frame because the interesting number is the average: a per-frame figure would be noise,
     * and keeping a per-frame ring here would cost more than the counters it holds.
     *
     * Public fields, incremented in place by the probes: a setter would be a call on the widget path, which is
     * the one place in this feature where a call is worth avoiding.
     */

    /** One widget tick or draw bracket ({@code Widget}/{@code UI}, 019.5). */
    public static long hWidget;
    /** One {@code callLua} category split ({@code AddonManager}, 019.4). */
    public static long hAddon;
    /** One named-pass open or close ({@link Passes}, 019.6). */
    public static long hPass;
    /** One GL submission count ({@link GlCount}, 019.6). */
    public static long hGl;

    // ------------------------------------------------------------------ the directly-timed parts (layer 1)

    private static long aggNanos;      // the end-of-frame fold, summed over armed frames
    private static long queryNanos;    // the GL timestamp queries the named passes insert

    /** Add {@code d} nanos of GL-timestamp-query time ({@link Passes}, which times its own query calls). */
    public static void query(long d) {
        queryNanos += d;
    }

    // ------------------------------------------------------------------ the control comparison (layer 3)

    private static long armedFrames, ctlFrames;

    /*
     * The estimator: PAIRED and MEDIAN-based, not two running means.
     *
     * Frame work time is a spiky distribution -- a GC, a resource landing, a window opening -- and the
     * overhead being measured is a fraction of a percent of it. Two running means over samples 63:1 apart in
     * count give an answer dominated by whatever drifted between them; measured against a real session it
     * came out NEGATIVE by a millisecond, which is not a client that got faster, it is an estimator that
     * cannot see the signal.
     *
     * So: each period contributes exactly ONE delta -- the median work of that period's armed frames minus
     * that period's control frame -- and the reported figure is the median of those deltas. Pairing inside a
     * period removes drift (both samples come from the same second of play); the two medians remove the
     * spikes. The spread of the deltas is reported alongside as the comparison's own noise floor, which is
     * what says whether a small measured number means anything at all.
     */
    private static final double[] period = new double[PERIOD];   // this period's armed work times
    private static int pn = 0;
    private static final int DELTAS = 256;
    private static final double[] delta = new double[DELTAS];    // one per completed period, ms
    private static int dn = 0, dcur = 0;
    private static final double[] medbuf = new double[Math.max(PERIOD, DELTAS)];

    /** Close an armed frame: its directly-timed fold cost and its work time. */
    static void armedFrame(long agg, double workMs) {
        aggNanos += agg;
        armedFrames++;
        if(pn < period.length)
            period[pn++] = workMs;
    }

    /**
     * Close a control frame — the last frame of a period, so this is where the period's paired delta is
     * formed and the next period starts.
     */
    static void controlFrame(double workMs) {
        ctlFrames++;
        if(pn > 0) {
            delta[dcur] = median(period, pn) - workMs;
            dcur = (dcur + 1) % DELTAS;
            if(dn < DELTAS)
                dn++;
        }
        pn = 0;
    }

    /** The median of {@code src[0, n)}, via the shared scratch buffer. UI thread only, once per period. */
    private static double median(double[] src, int n) {
        System.arraycopy(src, 0, medbuf, 0, n);
        java.util.Arrays.sort(medbuf, 0, n);
        return medbuf[n / 2];
    }

    /** The {@code q}-quantile of {@code src[0, n)}. */
    private static double quantile(double[] src, int n, double q) {
        System.arraycopy(src, 0, medbuf, 0, n);
        java.util.Arrays.sort(medbuf, 0, n);
        return medbuf[Math.min(n - 1, Math.max(0, (int)Math.floor(n * q)))];
    }

    /** Drop every figure ({@code p:reset()} and every arming of the switch). */
    public static void reset() {
        hWidget = hAddon = hPass = hGl = 0;
        aggNanos = queryNanos = 0;
        armedFrames = ctlFrames = 0;
        pn = dn = dcur = 0;
    }

    // ------------------------------------------------------------------ calibration

    /** Nanoseconds for one {@code nanoTime} pair plus an accumulate — the widget and pass probe shape. */
    public static double unitBracket = 0;
    /** Nanoseconds for one accumulate inside a bracket that already existed — the addon and GL probe shape. */
    public static double unitAccum = 0;

    /* The calibration's own scratch. Static and read back by calibrate(), so the loops below cannot be
     * optimised away as dead. */
    private static final long[] cal = new long[4];
    private static final int[] caln = new int[4];
    private static long calsink;

    private static final int CALN = 50000, CALREP = 3;

    /**
     * Measure what one probe of each shape costs, once, when the switch arms. Three repetitions and the
     * <b>minimum</b> is taken: a scheduler hiccup can only make a run slower, so the minimum is the closest
     * this can get to the steady-state cost.
     *
     * <p><b>This is an upper bound, and deliberately so.</b> The loops below run cold — interpreted, then
     * C1 — while the real probes run inside methods HotSpot has compiled and inlined for the whole session,
     * where a {@code nanoTime} pair costs less than it does here. So the modelled number errs high, which is
     * the right direction for a budget, and it is superseded by the control-frame measurement as soon as
     * {@value #MINCTL} control frames exist.
     *
     * <p>Cost: a few milliseconds, once, on the frame the checkbox is ticked. Nothing calls this again.
     */
    public static void calibrate() {
        double base = Double.MAX_VALUE, bracket = Double.MAX_VALUE, accum = Double.MAX_VALUE;
        loopBase(); loopBracket(); loopAccum();          // warm
        for(int r = 0; r < CALREP; r++) {
            base = Math.min(base, loopBase());
            bracket = Math.min(bracket, loopBracket());
            accum = Math.min(accum, loopAccum());
        }
        calsink += cal[0] + cal[1] + cal[2] + caln[0];   // keep the loops alive
        unitBracket = Math.max(MINUNIT, bracket - base);
        unitAccum = Math.max(MINUNIT, accum - base);
    }

    /**
     * The floor under a calibrated unit, nanoseconds.
     *
     * <p>{@link #loopAccum()} routinely measures at or below {@link #loopBase()}: two array read-modify-writes
     * over four indices are something HotSpot keeps in registers across the loop, so the difference sinks
     * under the loop's own noise. The real probe is not free — it is a couple of cycles — and a modelled
     * <b>zero</b> is worse than a rough number, because a tier with zero modelled cost gets zero weight when
     * the measured total is attributed and so reads as free in {@code p:overhead()}. One nanosecond is a
     * deliberately conservative stand-in for "too cheap for this loop to see"; the control-frame measurement
     * is what actually decides the total.
     */
    private static final double MINUNIT = 1.0;

    /**
     * The bare loop, to be subtracted from the two below — the counter and the branch, and no memory traffic
     * of its own. It must not do a probe's worth of work itself: an earlier version accumulated into
     * {@link #cal} here, which made the loop below indistinguishable from it and calibrated the accumulate
     * probe at zero.
     */
    private static double loopBase() {
        long t0 = System.nanoTime();
        int s = 0;
        for(int i = 0; i < CALN; i++)
            s += i & 3;
        calsink += s;
        return (System.nanoTime() - t0) / (double)CALN;
    }

    /** {@code Widget}'s shape: a {@code nanoTime} pair around the call, plus the accumulate that follows. */
    private static double loopBracket() {
        long t0 = System.nanoTime();
        for(int i = 0; i < CALN; i++) {
            long a = System.nanoTime();
            cal[1] += System.nanoTime() - a;
        }
        return (System.nanoTime() - t0) / (double)CALN;
    }

    /**
     * {@code callLua}'s shape: an add and a count inside a bracket that was already there. The index varies
     * so the loop cannot be strength-reduced into something cheaper than the probe it stands for — the probe
     * indexes by category, and a constant index here would calibrate a loop the JIT can collapse.
     */
    private static double loopAccum() {
        long t0 = System.nanoTime();
        for(int i = 0; i < CALN; i++) {
            int c = i & 3;
            cal[c] += t0;
            caln[c]++;
        }
        return (System.nanoTime() - t0) / (double)CALN;
    }

    /** Keep the calibration sink reachable (and give a reader something to look at). */
    public static long calsink() {
        return calsink;
    }

    // ------------------------------------------------------------------ the report

    /** How many armed frames have been folded since the last arm/reset. */
    public static long armedFrames() {return armedFrames;}
    /** How many control frames have been collected since the last arm/reset. */
    public static long ctlFrames() {return ctlFrames;}

    /** The directly-timed end-of-frame fold, mean ms per armed frame. */
    public static double aggregatorMs() {
        return (armedFrames == 0) ? 0 : ((aggNanos * 1e-6) / armedFrames);
    }

    /** The directly-timed GL timestamp queries the named passes insert, mean ms per armed frame. */
    public static double gpuQueryMs() {
        return (armedFrames == 0) ? 0 : ((queryNanos * 1e-6) / armedFrames);
    }

    /** Whether the control comparison has produced a delta yet — not whether that delta means anything. */
    public static boolean sampled() {
        return dn >= MINCTL;
    }

    /**
     * Whether the control comparison is what the totals are built from. It takes over from the model only
     * when it has enough periods <b>and</b> its answer clears its own uncertainty
     * ({@link #measuredErrorMs()}) — not merely when it lands positive. A median that happens to come out at
     * +0.004 ms with an error bar of ±0.13 ms has measured nothing, and letting it supersede the model would
     * replace a rough number with a random one. A delta at or below zero, likewise, does not mean profiling
     * made the client faster; it means the cost is under the noise. {@link #measuredMs()} is reported either
     * way, so the fallback is never silent.
     */
    public static boolean measured() {
        return sampled() && (measuredMs() > measuredErrorMs());
    }

    /**
     * The uncertainty on {@link #measuredMs()}, ms — the spread of the per-period deltas divided by the root
     * of how many there are, i.e. the usual shrinking of a median's error bar with sample count. An overhead
     * has to clear this to count as measured.
     */
    public static double measuredErrorMs() {
        return (dn < 4) ? Double.MAX_VALUE : (measuredSpreadMs() / Math.sqrt(dn));
    }

    /** The measured overhead: the median paired per-period delta, ms per frame. 0 until {@link #sampled()}. */
    public static double measuredMs() {
        return (dn == 0) ? 0 : median(delta, dn);
    }

    /**
     * The comparison's own noise floor: the interquartile spread of the per-period deltas. A
     * {@link #measuredMs()} well inside this is a measurement of nothing — which, for a profiler whose whole
     * claim is that it costs almost nothing, is the expected and desirable outcome.
     */
    public static double measuredSpreadMs() {
        if(dn < 4)
            return 0;
        return quantile(delta, dn, 0.75) - quantile(delta, dn, 0.25);
    }

    /** How many paired periods the comparison has collected. */
    public static int periods() {return dn;}

    /**
     * The modelled cost of tier {@code t}, mean ms per armed frame — {@code hits × unitCost} for the probe
     * tiers, the directly-timed figure for {@code frame}, and both for {@code passes}.
     */
    public static double modelMs(int t) {
        if(armedFrames == 0)
            return 0;
        double f = 1.0 / armedFrames;
        switch(t) {
        case T_FRAME:   return aggregatorMs();
        case T_ADDONS:  return hAddon * f * unitAccum * 1e-6;
        case T_WIDGETS: return hWidget * f * unitBracket * 1e-6;
        case T_PASSES:  return (hPass * f * unitBracket * 1e-6) + gpuQueryMs();
        case T_GL:      return hGl * f * unitAccum * 1e-6;
        }
        return 0;
    }

    /** The hit count of tier {@code t}, mean per armed frame ({@code -1} for the directly-timed one). */
    public static double hits(int t) {
        if(armedFrames == 0)
            return 0;
        double f = 1.0 / armedFrames;
        switch(t) {
        case T_ADDONS:  return hAddon * f;
        case T_WIDGETS: return hWidget * f;
        case T_PASSES:  return hPass * f;
        case T_GL:      return hGl * f;
        }
        return -1;
    }

    /** The modelled probe cost — every tier but {@code frame}, which is timed rather than modelled. */
    public static double probeMs() {
        double s = 0;
        for(int t = 0; t < N; t++) {
            if(t != T_FRAME)
                s += modelMs(t);
        }
        return s;
    }

    /**
     * The number the ≤5% budget is judged against: the directly-timed aggregator plus the probe cost —
     * measured by the control frames once there are enough of them, modelled from the calibration until then.
     * Never negative: a measured total below zero is control-frame noise, not a client that got faster.
     */
    public static double totalMs() {
        return aggregatorMs() + Math.max(0, measured() ? measuredMs() : probeMs());
    }

    /** How many control periods {@link #sampled()} still wants. */
    public static int periodsNeeded() {
        return Math.max(0, MINCTL - dn);
    }

    /**
     * Tier {@code t}'s share of {@link #totalMs()}. With only the model, that is the modelled figure; with a
     * measured total, the probe tiers are scaled to it in the modelled proportion — the model is a good
     * <b>relative</b> answer even where it is a poor absolute one, and scaling keeps the tiers adding up to
     * the number the budget is judged against. {@code frame} is never scaled: it is exact.
     */
    public static double tierMs(int t) {
        if(t == T_FRAME)
            return aggregatorMs();
        if(!measured())
            return modelMs(t);
        double model = probeMs();
        if(model <= 0)
            return 0;
        return Math.max(0, measuredMs()) * (modelMs(t) / model);
    }
}
