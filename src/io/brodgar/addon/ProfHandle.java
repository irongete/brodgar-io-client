package io.brodgar.addon;

import io.brodgar.prof.Prof;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;

/**
 * {@code hafen.client:profiling()} (spec 019-profiling) — the addon-facing read surface over {@link Prof}, the
 * profiling engine. The engine is a <b>client</b> feature in its own package ({@code io.brodgar.prof}); this
 * class is the only thing that knows it is also an addon API.
 *
 * <p><b>Handle, snapshots.</b> The handle itself is an object with verbs (it has identity and actions:
 * {@code :reset()}, and in 019.4 {@code :scope()}/{@code :measure()}); everything the read verbs answer is a
 * plain Lua <b>table</b> — a frozen measurement with no identity to re-resolve and no write path, exactly the
 * "snapshots vs handles" split {@code docs/addons/api/conventions.md} already ships (and what
 * {@code gob:pos()} does for a value). Making a measurement object-oriented would add no capability and would
 * cost real time in the one loop this feature exists to keep cheap: drawing a 600-sample frame graph is 600
 * table lookups as a table, versus 600 bridge invocations as objects.
 *
 * <p>Nothing is cached: the handle is a stateless proxy over {@link Prof}, so an addon may stash it forever
 * without pinning client state. Reads are snapshots taken on the UI thread, like every other {@code hafen.*}
 * read.
 *
 * <p><b>Off ⇒ empty, never nil.</b> With profiling off, {@code :frame()} and {@code :history()} answer an
 * empty table rather than {@code nil}, so addon code needs no branch. Arming is <b>next-frame</b>
 * ({@code UILoop.Frame} decides in its constructor whether to build profile objects), so the first valid frame
 * arrives one frame after the switch flips — documented behaviour, not a bug.
 *
 * <p>Task 019.2 ships {@code :frame()}, {@code :history(n)} and {@code :reset()}. The counters
 * ({@code :memory()}/{@code :net()}/{@code :loader()}/{@code :render()}), {@code :addons()}, {@code :scope()},
 * {@code :widgets()}, {@code :passes()}, {@code :gl()} and {@code :overhead()} land in 019.3+ on this same
 * handle.
 */
public final class ProfHandle {
    private ProfHandle() {}

    /** Create the profiling handle ({@code hafen.client:profiling()}). */
    static LuaValue create() {
        LuaTable p = new LuaTable();
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, methods(p));
        mt.set("__name", LuaValue.valueOf("Profiling"));
        mt.set("__tostring", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                return LuaValue.valueOf("Profiling");
            }
        });
        p.setmetatable(mt);
        return p;
    }

    private static LuaTable methods(final LuaValue handle) {
        LuaTable m = new LuaTable();

        // p:frame() -- the frame that just finished, in milliseconds throughout. Empty when profiling is off
        // or when the ring has not filled its first sample yet.
        m.set("frame", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaTable t = new LuaTable();
                int n = Prof.count();
                if(!Prof.armed() || (n == 0))
                    return t;
                int s = Prof.slot(n - 1);
                t.set("frameno", LuaValue.valueOf((double)Prof.frameno(s)));
                t.set("t", LuaValue.valueOf(Prof.time(s)));
                t.set("fps", LuaValue.valueOf(Prof.fps()));
                t.set("ms", LuaValue.valueOf(Prof.ms(s)));
                t.set("msAvg", LuaValue.valueOf(Prof.msAvg()));
                t.set("msMin", LuaValue.valueOf(Prof.msMin()));
                t.set("msMax", LuaValue.valueOf(Prof.msMax()));
                t.set("msP95", LuaValue.valueOf(Prof.msP95()));
                t.set("idle", LuaValue.valueOf(Prof.idle()));
                t.set("latency", LuaValue.valueOf(Prof.latency() * 1e3));
                // gpuMs is the newest frame whose GL timestamps have COME BACK, not this frame's: they arrive
                // through fences several frames late, so this frame's is essentially never ready. gpuFrameno
                // says which frame the number belongs to, and both keys are simply absent until the first one
                // lands (an absent key means "not measured", the way a 0 would not).
                int gs = Prof.gpuSlot();
                if(gs >= 0) {
                    t.set("gpuMs", LuaValue.valueOf(Prof.gpuMs(gs)));
                    t.set("gpuFrameno", LuaValue.valueOf((double)Prof.frameno(gs)));
                }
                t.set("phases", phases(s));
                t.set("render", rphases(s));
                // The roll-ups. ui = utick + draw, the whole cost of the widget tree this frame; addons is
                // the Lua time charged to addons (the same accounting the D-018 watchdog reads). "scene" is
                // NOT here: the 3D scene has no boundary of its own until the named passes of 019.6, and a
                // zero that means "not measured yet" would be worse than an absent key.
                t.set("ui", LuaValue.valueOf(Prof.phase(s, Prof.P_UTICK) + Prof.phase(s, Prof.P_DRAW)));
                t.set("addons", LuaValue.valueOf(Prof.addonMs(s)));
                return t;
            }
        });

        // p:history(n) -- the last n samples, OLDEST FIRST, for drawing a frame graph. n is clamped to what
        // the ring holds (~600 frames, about 10 s at 60 fps); omitting it answers everything held.
        m.set("history", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaTable out = new LuaTable();
                int have = Prof.count();
                if(!Prof.armed() || (have == 0))
                    return out;
                int n = have;
                LuaValue arg = a.arg(2);            // colon call: arg(1) is the handle
                if(arg.isnumber())
                    n = Math.max(0, Math.min(have, arg.toint()));
                for(int i = 0; i < n; i++) {
                    int s = Prof.slot(have - n + i);
                    LuaTable e = new LuaTable();
                    e.set("frameno", LuaValue.valueOf((double)Prof.frameno(s)));
                    e.set("t", LuaValue.valueOf(Prof.time(s)));
                    e.set("ms", LuaValue.valueOf(Prof.ms(s)));
                    double g = Prof.gpuMs(s);
                    if(g > 0)                       // absent, not 0, while the GL timestamps are still in flight
                        e.set("gpuMs", LuaValue.valueOf(g));
                    e.set("addons", LuaValue.valueOf(Prof.addonMs(s)));
                    e.set("phases", phases(s));
                    out.set(i + 1, e);
                }
                return out;
            }
        });

        // p:reset() -- drop the ring and start measuring afresh. Chains, like every other write in hafen.*.
        m.set("reset", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Prof.reset();
                return handle;
            }
        });

        return m;
    }

    /** The UI-thread phase breakdown of one ring slot, ms — the parts {@code UILoop} names in {@code uprof}. */
    private static LuaTable phases(int s) {
        LuaTable t = new LuaTable();
        for(int i = 0; i < Prof.PHASES.length; i++)
            t.set(Prof.PHASES[i], LuaValue.valueOf(Prof.phase(s, i)));
        return t;
    }

    /**
     * The render-thread phase breakdown, ms. Lagged by about one frame on purpose: the render profile closes
     * a frame on the following frame's fence, and re-timing it here would be a second source of truth.
     */
    private static LuaTable rphases(int s) {
        LuaTable t = new LuaTable();
        for(int i = 0; i < Prof.RPHASES.length; i++)
            t.set(Prof.RPHASES[i], LuaValue.valueOf(Prof.rphase(s, i)));
        return t;
    }
}
