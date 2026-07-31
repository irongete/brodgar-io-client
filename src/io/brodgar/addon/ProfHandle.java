package io.brodgar.addon;

import haven.Connection;
import haven.Defer;
import haven.MapView;
import haven.Resource;
import haven.UI;
import haven.UILoop;
import haven.render.DrawList;
import haven.render.Environment;
import haven.render.InstanceList;
import haven.render.State;
import haven.render.gl.GLEnvironment;
import io.brodgar.prof.Prof;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;

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
 * <p><b>The counters are pull-only.</b> {@code :memory()}, {@code :net()}, {@code :loader()} and
 * {@code :render()} (019.3) answer whether profiling is armed or not, because every number in them is one
 * the client counts anyway and then formats into a {@code :stats on} string. 019.3 adds structured getters
 * beside those strings rather than re-counting anything, so there is one source of truth with the HUD and
 * no cost when disarmed. The frame surface above is the opposite: it exists only while armed.
 *
 * <p>Task 019.2 ships {@code :frame()}, {@code :history(n)} and {@code :reset()}; 019.3 the four counters.
 * {@code :addons()}, {@code :scope()}, {@code :widgets()}, {@code :passes()}, {@code :gl()} and
 * {@code :overhead()} land in 019.4+ on this same handle.
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

        // --------------------------------------------------------------- the pull-only counters (019.3)
        //
        // These four answer with profiling OFF, and that is the point of them: every number below is a
        // counter the client maintains anyway and then throws away into a `:stats on` format string. 019.3
        // adds structured getters BESIDE those strings -- no new counting, no behaviour change, nothing to
        // arm -- so an addon can read graphics, memory, network and loader state at any time without the
        // client paying for a profiler it is not running.
        //
        // A key is ABSENT when its source is not there (no MapView before the world loads, a non-GL
        // environment, an unconnected session), never 0: an absent key means "not measured", which a 0
        // would not (D-050). Render counters are written on the render side and may be one frame stale,
        // network counters on the connection worker and may be one packet stale -- both by design.

        // p:memory() -- the JVM heap, in BYTES, plus the client's own per-frame allocation estimate.
        m.set("memory", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                return memory();
            }
        });

        // p:net() -- the connection's packet/byte counters and its smoothed round-trip time (ms).
        m.set("net", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                return net();
            }
        });

        // p:loader() -- the async queue depths: the UI loader, the Defer pool, the resource queues.
        m.set("loader", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                return loader();
            }
        });

        // p:render() -- the graphics counters as NUMBERS: draw slots, batching, tree size, VRAM, programs.
        m.set("render", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                return render();
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

    // ------------------------------------------------------------------------- the counter snapshots

    /**
     * {@code p:memory()} — heap in <b>bytes</b> ({@code heapUsed}/{@code heapFree}/{@code heapTotal}/
     * {@code heapMax}), the client's per-frame allocation estimate, and the JVM's cumulative GC totals.
     *
     * <p>{@code allocPerFrame} is {@code UILoop}'s own smoothed estimate, not a second one computed here —
     * one source of truth with the {@code Mem:} line of {@code :stats on}. It is only advanced while that
     * HUD is being drawn, so it is <b>absent</b> until the client has computed it at least once; putting
     * the estimate on the frame loop instead would mean a {@code freeMemory()} call every frame whether
     * profiling is armed or not, which is the always-on cost this feature exists to avoid.
     *
     * <p>{@code gcCount}/{@code gcMs} are summed across the JVM's collectors and are cumulative since JVM
     * start — a profiler wants the delta between two reads, not the absolute. Both are absent if the
     * management beans are unavailable (a stripped runtime image).
     */
    private static LuaTable memory() {
        LuaTable t = new LuaTable();
        Runtime rt = Runtime.getRuntime();
        long free = rt.freeMemory(), total = rt.totalMemory();
        t.set("heapFree", LuaValue.valueOf((double)free));
        t.set("heapUsed", LuaValue.valueOf((double)(total - free)));
        t.set("heapTotal", LuaValue.valueOf((double)total));
        t.set("heapMax", LuaValue.valueOf((double)rt.maxMemory()));
        long alloc = UILoop.framealloc();
        if(alloc > 0)
            t.set("allocPerFrame", LuaValue.valueOf((double)alloc));
        try {
            long n = 0, ms = 0;
            List<GarbageCollectorMXBean> gcs = ManagementFactory.getGarbageCollectorMXBeans();
            for(int i = 0; i < gcs.size(); i++) {
                GarbageCollectorMXBean gc = gcs.get(i);
                long c = gc.getCollectionCount(), d = gc.getCollectionTime();
                if(c > 0) n += c;               // -1 means "this collector does not count"
                if(d > 0) ms += d;
            }
            t.set("gcCount", LuaValue.valueOf((double)n));
            t.set("gcMs", LuaValue.valueOf((double)ms));
        } catch(Throwable e) {
            /* No management beans: leave both keys absent rather than report a zero that would read as
             * "the JVM has never collected". */
        }
        return t;
    }

    /**
     * {@code p:net()} — the counters {@code Connection.Stats} already keeps for the {@code Connection:}
     * HUD line. Empty while there is no connection (the login screen). {@code rtt}/{@code rttVar} are
     * converted to <b>milliseconds</b> here, the unit everything else in this surface reports time in;
     * the client stores them in seconds.
     */
    private static LuaTable net() {
        LuaTable t = new LuaTable();
        UI u = AddonManager.ui;
        if((u == null) || (u.sess == null) || !(u.sess.conn instanceof Connection))
            return t;
        Connection.Stats s = ((Connection)u.sess.conn).stats;
        t.set("packetsTx", LuaValue.valueOf((double)s.ptx()));
        t.set("packetsRx", LuaValue.valueOf((double)s.prx()));
        t.set("bytesTx", LuaValue.valueOf((double)s.btx()));
        t.set("bytesRx", LuaValue.valueOf((double)s.brx()));
        t.set("resentTx", LuaValue.valueOf((double)s.pretx()));
        t.set("resentRx", LuaValue.valueOf((double)s.prerx()));
        t.set("reorderedRx", LuaValue.valueOf((double)s.prorx()));
        t.set("rtt", LuaValue.valueOf(s.srtt() * 1e3));
        t.set("rttVar", LuaValue.valueOf(s.rttv() * 1e3));
        return t;
    }

    /**
     * {@code p:loader()} — the async queue depths behind the {@code Async:} and {@code RQ depth:} HUD
     * lines: the UI resource loader, the shared {@code Defer} pool (nested under {@code defer}), and the
     * local+remote resource queues.
     */
    private static LuaTable loader() {
        LuaTable t = new LuaTable();
        UI u = AddonManager.ui;
        if(u == null)
            return t;
        int[] l = u.loader.statcounts();
        t.set("queued", LuaValue.valueOf(l[0]));
        t.set("loading", LuaValue.valueOf(l[1]));
        t.set("busy", LuaValue.valueOf(l[2]));
        t.set("poolSize", LuaValue.valueOf(l[3]));
        int[] d = Defer.gstatcounts();
        LuaTable dt = new LuaTable();
        dt.set("queued", LuaValue.valueOf(d[0]));
        dt.set("busy", LuaValue.valueOf(d[1]));
        dt.set("poolSize", LuaValue.valueOf(d[2]));
        t.set("defer", dt);
        t.set("resQueue", LuaValue.valueOf(Resource.local().qdepth() + Resource.remote().qdepth()));
        t.set("resLoaded", LuaValue.valueOf(Resource.local().numloaded() + Resource.remote().numloaded()));
        return t;
    }

    /**
     * {@code p:render()} — the graphics counters as numbers. {@code stateSlots} is process-wide;
     * {@code programs} and {@code vram} come from the GL environment; everything else describes the
     * <b>scene</b> and is therefore absent until a {@code MapView} exists and has drawn once.
     *
     * <p>{@code drawSlots} is the draw-slot count, the closest thing the tree has to "draw calls this
     * frame"; {@code uniqueInstances} + {@code batches} (holding {@code instances} between them) is the
     * batching split; {@code invalid} and {@code bypass} are the slots instancing could not take.
     * {@code vram} is keyed by pool name ({@code indices}/{@code vertices}/{@code textures}/{@code vaos}/
     * {@code fbos}), each {@code {objects=, bytes=}}.
     */
    @SuppressWarnings("deprecation")
    private static LuaTable render() {
        LuaTable t = new LuaTable();
        UI u = AddonManager.ui;
        if(u == null)
            return t;
        t.set("stateSlots", LuaValue.valueOf(State.Slot.numslots()));
        Environment env = u.getenv();
        if(env instanceof GLEnvironment) {
            GLEnvironment gl = (GLEnvironment)env;
            t.set("programs", LuaValue.valueOf(gl.numprogs()));
            LuaTable vram = new LuaTable();
            String[] pools = GLEnvironment.mempools();
            for(int i = 0; i < pools.length; i++) {
                LuaTable p = new LuaTable();
                p.set("objects", LuaValue.valueOf(gl.memobjects(i)));
                p.set("bytes", LuaValue.valueOf((double)gl.membytes(i)));
                vram.set(pools[i], p);
            }
            t.set("vram", vram);
        }
        MapView mv = u.root.findchild(MapView.class);
        if(mv == null)
            return t;
        t.set("treeLeaves", LuaValue.valueOf(mv.tree.nleaves()));
        t.set("treeNodes", LuaValue.valueOf(mv.tree.nslots()));
        InstanceList il = mv.instancer();
        if(il != null) {
            t.set("uniqueInstances", LuaValue.valueOf(il.nuinst()));
            t.set("batches", LuaValue.valueOf(il.nbatches()));
            t.set("instances", LuaValue.valueOf(il.ninst()));
            t.set("invalid", LuaValue.valueOf(il.ninvalid()));
            t.set("bypass", LuaValue.valueOf(il.nbypass()));
        }
        DrawList dl = mv.drawlist();
        if(dl != null) {
            int ds = dl.drawslots();
            if(ds >= 0)                 // negative = this DrawList implementation does not count them
                t.set("drawSlots", LuaValue.valueOf(ds));
        }
        return t;
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
