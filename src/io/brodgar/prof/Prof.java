package io.brodgar.prof;

import haven.UILoop;
import haven.Utils;

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
 * <p>Task 019.1 ships the switch only: there is no data surface yet. The ring, the per-frame fold, the
 * per-addon accumulators and the overhead accounting land in 019.2+ behind this same field.
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
    }

    /** The current state of the master switch (the reader for the panel and for {@code options():client()}). */
    public static boolean armed() {
        return on;
    }
}
