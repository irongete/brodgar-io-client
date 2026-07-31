package io.brodgar.prof;

import haven.Profile;
import haven.UILoop;
import haven.Utils;

import java.util.Arrays;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * The profiling master switch (spec 019-profiling, task 019.1). Every probe this feature adds — in
 * {@code haven} and in the addon layer — is guarded by {@link #on}, and nothing else decides whether profiling
 * runs.
 *
 * <p><b>Why this is not in {@code io.brodgar.addon}.</b> This package profiles the <b>client</b>: its
 * consumers are {@code UILoop}, {@code Widget}, {@code MapView} and the GL layer, and only one of them —
 * {@code ProfHandle}, the Lua bridge — belongs to the addon system. The engine is a client feature; the handle
 * is the addon surface. Keeping them in one package would have made the hottest classes in {@code haven}
 * depend on something named "addon".
 *
 * <p><b>Why a plain {@code static volatile boolean}.</b> The off-state guarantee of 019 is that a disarmed
 * probe costs one branch: no {@code nanoTime}, no allocation, no map lookup, no string formatting. A
 * {@code Config.Variable.get()} would be an object dereference plus an unbox on paths that run hundreds of
 * times per frame; a plain static field is a branch the JIT hoists out of loops. This is the whole reason the
 * switch is a field here rather than a preference read at the probe.
 *
 * <p><b>One switch, written in one place.</b> {@link #arm(boolean)} is the only writer, and it writes the live
 * field, the {@code "profiling"} preference and {@link UILoop#profile} in one statement each, so the checkbox,
 * the persisted pref, the Lua accessor and the client's own {@code :profile} machinery ({@code CPUProfile} /
 * {@code GPUProfile} / {@code Profwnd}) can never diverge. A pref-only write would be a no-op until restart;
 * a field-only write would not survive one.
 *
 * <p><b>Arming is next-frame.</b> {@code UILoop.Frame} decides in its constructor whether to build profile
 * objects, so a flip mid-frame takes effect on the following frame. The first frame after arming has no tree —
 * documented behaviour, not a bug.
 *
 * <p><b>What 019.2 adds.</b> The <b>frame ring</b> and the per-frame <b>fold</b>: {@link #frame} is the
 * end-of-frame handoff {@code UILoop} makes, and it reads the client's own finished {@code uprof}/{@code rprof}/
 * {@code gprof} parts rather than timing anything a second time — one source of truth with {@code :stats on}
 * and {@code Profwnd}. The per-addon accumulators (019.4), the per-widget cost (019.5), the named passes
 * (019.6) and the overhead accounting (019.7) land behind this same field.
 *
 * <p><b>No allocation per frame.</b> The ring is preallocated primitive arrays written by index, and the fold
 * walks the frame parts by index (never a for-each, which would allocate an iterator): a profiler that
 * allocated per frame would show up in its own per-frame-allocation figure. Lua tables are built <b>on
 * demand</b>, when an addon calls a read verb — the same discipline as every other {@code hafen.*} surface.
 *
 * <p><b>Threading.</b> Everything here runs on the Haven UI thread: the fold at end-of-frame, the snapshots
 * from Lua. No locking. The one exception is the GPU timing, which arrives late through GL fences and is
 * therefore folded in <b>by frame number</b> (see {@link #frame}).
 */
public final class Prof {
    private Prof() {}

    /** The preference key behind the "Enable profiling" checkbox — the same store every other option uses. */
    private static final String PREF = "profiling";

    /**
     * The master switch every probe reads. Written only by {@link #arm(boolean)}, on the UI thread; volatile so
     * the render thread's probes see the flip on the frame after it happens.
     */
    public static volatile boolean on = false;

    private static boolean inited = false;

    /**
     * Restore the persisted switch, once per JVM. Called from {@code AddonManager.init} — the panel that owns
     * the checkbox is in-game only (D-004), and everything 019 profiles is the in-game frame loop, so session
     * init is the earliest point at which arming means anything.
     */
    public static synchronized void init() {
        if(inited)
            return;
        inited = true;
        arm(Utils.getprefb(PREF, false));
    }

    /**
     * Arm or disarm profiling: the live switch, the persisted pref and the client's own profile machinery, in
     * that one place. Idempotent, and safe to call before the UI exists.
     */
    public static void arm(boolean v) {
        Utils.setprefb(PREF, on = v);   // pref and switch in one statement: they cannot drift apart
        UILoop.profile.set(v);          // the client's own uprof/rprof/gprof frames — one switch, not two
        reset();                        // 019.2: a session starts empty, and disarming leaves nothing stale
    }

    /** The current state of the master switch (the reader for the panel and for {@code options():client()}). */
    public static boolean armed() {
        return on;
    }

    // --------------------------------------------------------------------- the frame ring

    /** Ring capacity in frames — ~10 s at 60 fps, which is what a frame graph wants to show. */
    public static final int CAP = 600;

    /** The UI-thread phases, in ring order. These are the part names {@code UILoop.Frame} gives {@code uprof}. */
    public static final String[] PHASES = {"dwait", "stick", "utick", "draw", "aux", "wait"};
    /** The render-thread phases, in ring order — the part names {@code UILoop.RenderProfile} gives {@code rprof}. */
    public static final String[] RPHASES = {"tick", "draw", "swap", "finish"};

    /** Indices into {@link #PHASES} — {@code utick} and {@code draw} are the two the {@code ui} roll-up sums. */
    public static final int P_DWAIT = 0, P_STICK = 1, P_UTICK = 2, P_DRAW = 3, P_AUX = 4, P_WAIT = 5;

    private static final int NPH = PHASES.length, NRP = RPHASES.length;

    private static final long[] rfno = new long[CAP];        // the client's frame number for this slot
    private static final double[] rt = new double[CAP];      // wall time (Utils.rtime) at end of frame
    private static final double[] rms = new double[CAP];     // total UI-thread frame time, ms
    private static final double[] rgpu = new double[CAP];    // GPU frame time, ms — written LATE (see below)
    private static final double[] raddon = new double[CAP];  // Lua time charged to addons this frame, ms
    private static final double[] rph = new double[CAP * NPH];
    private static final double[] rrp = new double[CAP * NRP];
    private static int cursor = 0;    // the next slot to write
    private static int filled = 0;    // samples written since the last reset, clamped to CAP

    /** The named render passes, in report order (019.6) — {@link Passes#NAMES}, re-exported for the ring. */
    public static final String[] PASSES = Passes.NAMES;
    private static final int NPS = PASSES.length;
    private static final double[] rpcpu = new double[CAP * NPS];   // exclusive CPU ms per pass
    private static final double[] rpgpu = new double[CAP * NPS];   // exclusive GPU ms per pass — written LATE
    /* The armed-only GL submission counters (019.6), snapshotted off GlCount and zeroed once per frame. */
    private static final long[] rgldraw = new long[CAP];
    private static final long[] rglprog = new long[CAP];
    private static final long[] rglvert = new long[CAP];
    private static final long[] rgltri = new long[CAP];

    /**
     * The frame stamp the <b>per-widget</b> accumulators carry (019.5). {@code Widget.prof} is a plain
     * {@code long[]} with no owner to sweep it — there is no list of live widgets and building one per frame
     * would cost more than the probe — so each array carries the frame it was last written in and zeroes
     * itself on the first write of a new one. That makes the whole per-widget tier a stamp compare plus an
     * add, and it makes a widget that stopped being drawn simply age out of {@code p:widgets()} instead of
     * needing to be found and cleared.
     *
     * <p>Advanced once per armed frame, in {@link #frame}. Starts at 1 so a freshly allocated (all-zero)
     * array is always stale on its first write, and is bumped by {@link #reset()} so every stale widget
     * total is dropped along with the ring.
     */
    public static volatile long gen = 1;

    /**
     * Whether a per-widget stamp still describes a live measurement (019.5): the frame in progress, or the one
     * that just finished. A snapshot may be taken at any point in a frame — from a tick callback, before the
     * widget has been drawn again — so both stamps are current; anything older belongs to a widget that has
     * not been ticked or drawn since, and reads as zero rather than as a stale cost.
     */
    public static boolean fresh(long stamp) {
        long g = gen;
        return (stamp == g) || (stamp == g - 1);
    }

    /** Scratch for the percentile sort. Snapshot-time only, UI thread only — never allocated per frame. */
    private static final double[] sortbuf = new double[CAP];

    /* The last frame's scalars: fps/uidle/framelag are private to UILoop and are handed over as arguments
     * rather than widening the fields, so nothing else in the client can start writing them. */
    private static int lfps;
    private static double lidle, llatency;

    /**
     * Where the per-addon Lua cost comes from. Set by {@code AddonManager.init} rather than imported, so this
     * package — the profiling <b>engine</b>, whose other consumers are {@code UILoop}, {@code Widget} and the
     * GL layer — never depends on the addon system. 019.4 replaces the single total with the per-addon,
     * per-category accumulators; the roll-up in {@link #frame} stays the same shape.
     */
    private static volatile LongSupplier addonNanos = null;

    /** Register the per-frame addon-cost source (from {@code AddonManager.init}). */
    public static void addonCost(LongSupplier src) {
        addonNanos = src;
    }

    /**
     * The addon layer's own "drop everything" hook (019.4), registered the same way and for the same reason:
     * the per-addon rows and named scopes are profiling state, so they must clear whenever the ring does —
     * on {@code p:reset()} and on every arming — without this package knowing what an addon is.
     */
    private static volatile Runnable addonResetter = null;

    /** Register the per-addon reset hook (from {@code AddonManager.init}). */
    public static void addonReset(Runnable r) {
        addonResetter = r;
    }

    /* GPU frames arrive late: a small FIFO of (slot, frame number, the still-pending frame). Held as the
     * Profile.Part supertype -- all the fold ever asks a GPU frame is d(), and that keeps this queue
     * exercisable without a GL context. */
    private static final int PENDING = 64;
    private static final Profile.Part[] pgf = new Profile.Part[PENDING];
    private static final int[] pgslot = new int[PENDING];
    private static final long[] pgfno = new long[PENDING];
    private static int phead = 0, ptail = 0;

    /**
     * The end-of-frame handoff, called from {@code UILoop.framedone} on the UI thread once the frame's own
     * profile parts are finished. Everything here is a read of what the client already built.
     *
     * <p>{@code uframe} is this frame's finished {@code uprof} frame; {@code rframe} is the most recently
     * <b>completed</b> render-thread frame, which lags the UI thread by about one frame (the render profile
     * closes a frame on the next frame's fence) — documented, not corrected, because correcting it would mean
     * a second source of truth. {@code gframe} is this frame's GPU frame, which is typically <b>not finished
     * yet</b>: GL timestamp results come back through fences several frames later, so it is queued and folded
     * into <b>its own slot, by frame number</b>, whenever it completes. A slot the ring has already wrapped
     * past simply drops its late GPU write.
     */
    public static void frame(long fno, double t, Profile.Part uframe, Profile.Part rframe,
                             Profile.Part gframe, int fps, double idle, double latency) {
        if(!on)
            return;
        gen++;              // 019.5: the per-widget accumulators roll over on the first write of the new frame
        if(uframe == null)
            return;
        drainGpu();

        int s = cursor;
        rfno[s] = fno;
        rt[s] = t;
        rms[s] = ms(uframe.d());
        rgpu[s] = 0;
        lfps = fps; lidle = idle; llatency = latency;

        int pb = s * NPH;
        for(int i = 0; i < NPH; i++)
            rph[pb + i] = 0;
        List<Profile.Part> sub = uframe.sub();
        for(int i = 0, n = sub.size(); i < n; i++) {   // indexed: a for-each would allocate an iterator
            Profile.Part p = sub.get(i);
            int k = index(PHASES, p.nm);
            if(k >= 0)
                rph[pb + k] += ms(p.d());   // += : "dwait" is entered twice per frame (tick + syncwait)
        }

        int rb = s * NRP;
        for(int i = 0; i < NRP; i++)
            rrp[rb + i] = 0;
        if(rframe != null) {
            List<Profile.Part> rsub = rframe.sub();
            for(int i = 0, n = rsub.size(); i < n; i++) {
                Profile.Part p = rsub.get(i);
                int k = index(RPHASES, p.nm);
                if(k >= 0)
                    rrp[rb + k] += ms(p.d());
            }
        }

        LongSupplier src = addonNanos;
        raddon[s] = (src == null) ? 0 : (src.getAsLong() * 1e-6);

        // 019.6: the named passes' CPU side is measured directly at their seams (the GPU side arrives late and
        // is folded by frame number below), and the GL submission counters are read and zeroed here -- one
        // place, once a frame, so nothing downstream has to know when a frame started.
        int qb = s * NPS;
        for(int i = 0; i < NPS; i++) {
            rpcpu[qb + i] = Passes.cpuMs(i);
            rpgpu[qb + i] = 0;
        }
        rgldraw[s] = GlCount.draws;   GlCount.draws = 0;
        rglprog[s] = GlCount.progBinds; GlCount.progBinds = 0;
        rglvert[s] = GlCount.verts;   GlCount.verts = 0;
        rgltri[s]  = GlCount.tris;    GlCount.tris = 0;

        if(gframe != null) {
            int nt = (ptail + 1) % PENDING;
            if(nt == phead)                       // full: the oldest pending GPU frame is the one to lose
                phead = (phead + 1) % PENDING;
            pgf[ptail] = gframe;
            pgslot[ptail] = s;
            pgfno[ptail] = fno;
            ptail = nt;
        }

        cursor = (s + 1) % CAP;
        if(filled < CAP)
            filled++;
    }

    /** Fold every GPU frame whose timestamps have come back into the slot it belongs to. */
    private static void drainGpu() {
        while(phead != ptail) {
            Profile.Part f = pgf[phead];
            double d = (f == null) ? 0 : f.d();
            if(d <= 0)
                return;                            // still in flight — and they complete in order
            int s = pgslot[phead];
            if(rfno[s] == pgfno[phead]) {          // the ring may have wrapped past this slot
                rgpu[s] = ms(d);
                foldPasses(f, s);                  // 019.6: the named passes ride the same late arrival
            }
            pgf[phead] = null;
            phead = (phead + 1) % PENDING;
        }
    }

    /**
     * Fold a resolved GPU frame's named passes into its ring slot (019.6). The passes hang under the frame's
     * own {@code draw} part rather than beside it, so this is a walk of the whole little tree — a handful of
     * parts, once per frame, only while armed. Each pass reports <b>self</b> time: its span minus the passes
     * nested inside it ({@code shadow} and {@code scene} run inside the widget draw, because the
     * {@code MapView} is a widget), which is what keeps the three disjoint and their sum under the frame.
     */
    private static void foldPasses(Profile.Part p, int slot) {
        List<Profile.Part> sub = p.sub();
        for(int i = 0, n = sub.size(); i < n; i++) {
            Profile.Part c = sub.get(i);
            int k = index(PASSES, c.nm);
            if(k >= 0)
                rpgpu[(slot * NPS) + k] = ms(c.d() - passSum(c));
            foldPasses(c, slot);
        }
    }

    /** The time of the passes nested directly inside {@code p}, skipping any un-named parts in between. */
    private static double passSum(Profile.Part p) {
        double s = 0;
        List<Profile.Part> sub = p.sub();
        for(int i = 0, n = sub.size(); i < n; i++) {
            Profile.Part c = sub.get(i);
            s += (index(PASSES, c.nm) >= 0) ? Math.max(0, c.d()) : passSum(c);
        }
        return s;
    }

    /** Seconds to milliseconds, with an unfinished part (t still 0, so d() is negative) clamped to zero. */
    private static double ms(double sec) {
        return (sec > 0) ? (sec * 1e3) : 0;
    }

    private static int index(String[] names, Object nm) {
        if(!(nm instanceof String))
            return -1;
        for(int i = 0; i < names.length; i++) {
            if(names[i].equals(nm))
                return i;
        }
        return -1;
    }

    /** Drop every sample and every pending GPU frame ({@code p:reset()}, and every arming). */
    public static synchronized void reset() {
        cursor = filled = 0;
        gen++;                          // 019.5: every per-widget total still carrying the old stamp is now stale
        lfps = 0;
        lidle = llatency = 0;
        Arrays.fill(rfno, 0L);
        Arrays.fill(rt, 0); Arrays.fill(rms, 0); Arrays.fill(rgpu, 0); Arrays.fill(raddon, 0);
        Arrays.fill(rph, 0); Arrays.fill(rrp, 0);
        Arrays.fill(rpcpu, 0); Arrays.fill(rpgpu, 0);
        Arrays.fill(rgldraw, 0L); Arrays.fill(rglprog, 0L);
        Arrays.fill(rglvert, 0L); Arrays.fill(rgltri, 0L);
        GlCount.draws = GlCount.progBinds = GlCount.verts = GlCount.tris = 0;
        Arrays.fill(pgf, null);
        phead = ptail = 0;
        Runnable r = addonResetter;
        if(r != null)
            r.run();                    // 019.4: the per-addon rows and scopes are part of "everything"
    }

    // --------------------------------------------------------------------- reading the ring

    /** How many samples the ring holds right now (0 when off or freshly armed). */
    public static int count() {
        return filled;
    }

    /** The ring index of the {@code i}-th oldest sample held, {@code i} in {@code [0, count())}. */
    public static int slot(int i) {
        return ((cursor - filled + i) % CAP + CAP) % CAP;
    }

    public static long frameno(int s) {return rfno[s];}
    public static double time(int s)  {return rt[s];}
    public static double ms(int s)    {return rms[s];}
    public static double gpuMs(int s) {return rgpu[s];}
    public static double addonMs(int s) {return raddon[s];}
    /** The UI-thread phase {@code p} ({@link #PHASES}) of slot {@code s}, ms. */
    public static double phase(int s, int p)  {return rph[(s * NPH) + p];}
    /** The render-thread phase {@code p} ({@link #RPHASES}) of slot {@code s}, ms. */
    public static double rphase(int s, int p) {return rrp[(s * NRP) + p];}
    /** Named pass {@code p} ({@link #PASSES}) of slot {@code s}: exclusive CPU ms. */
    public static double passCpu(int s, int p) {return rpcpu[(s * NPS) + p];}
    /** Named pass {@code p} of slot {@code s}: exclusive GPU ms, 0 while its timestamps are still in flight. */
    public static double passGpu(int s, int p) {return rpgpu[(s * NPS) + p];}

    public static long glDraws(int s)     {return rgldraw[s];}
    public static long glProgBinds(int s) {return rglprog[s];}
    public static long glVerts(int s)     {return rglvert[s];}
    public static long glTris(int s)      {return rgltri[s];}

    /**
     * The newest slot whose named passes have actually come back, or {@code -1} while none has (019.6). Like
     * {@link #gpuSlot()}, and for the same reason: the GPU column of a pass arrives through fences several
     * frames after the CPU column, so {@code p:passes()} reports the newest <b>resolved</b> frame and says
     * which one it is, instead of a fresh frame with an empty GPU side.
     */
    public static int passSlot() {
        for(int i = filled - 1; i >= 0; i--) {
            int s = slot(i), b = s * NPS;
            for(int k = 0; k < NPS; k++) {
                if(rpgpu[b + k] > 0)
                    return s;
            }
        }
        return -1;
    }

    /**
     * The newest slot whose GPU time has actually come back, or {@code -1} while none has. GL timestamp results
     * arrive through fences several frames after the CPU frame they belong to, so the <b>newest</b> slot is
     * essentially never the one to read a GPU number off: {@code p:frame()} reports this one instead, and says
     * which frame it is. A slot that never resolved (the ring wrapped past it) simply stays 0.
     */
    public static int gpuSlot() {
        for(int i = filled - 1; i >= 0; i--) {
            int s = slot(i);
            if(rgpu[s] > 0)
                return s;
        }
        return -1;
    }

    public static int fps()        {return lfps;}
    public static double idle()    {return lidle;}
    public static double latency() {return llatency;}

    /** Mean frame time over the ring, ms. */
    public static double msAvg() {
        if(filled == 0) return 0;
        double sum = 0;
        for(int i = 0; i < filled; i++)
            sum += rms[slot(i)];
        return sum / filled;
    }

    /** Shortest frame time over the ring, ms. */
    public static double msMin() {
        if(filled == 0) return 0;
        double v = Double.MAX_VALUE;
        for(int i = 0; i < filled; i++)
            v = Math.min(v, rms[slot(i)]);
        return v;
    }

    /** Longest frame time over the ring, ms. */
    public static double msMax() {
        if(filled == 0) return 0;
        double v = 0;
        for(int i = 0; i < filled; i++)
            v = Math.max(v, rms[slot(i)]);
        return v;
    }

    /**
     * The 95th-percentile frame time over the ring, ms — the number that says how bad the bad frames are,
     * which an average hides. Sorted into a shared scratch buffer (UI thread only, snapshot time only).
     */
    public static double msP95() {
        if(filled == 0) return 0;
        for(int i = 0; i < filled; i++)
            sortbuf[i] = rms[slot(i)];
        Arrays.sort(sortbuf, 0, filled);
        return sortbuf[Math.min(filled - 1, (int)Math.floor(filled * 0.95))];
    }
}
