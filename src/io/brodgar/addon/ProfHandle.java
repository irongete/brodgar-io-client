package io.brodgar.addon;

import haven.Connection;
import haven.Defer;
import haven.MapView;
import haven.Resource;
import haven.UI;
import haven.UILoop;
import haven.Widget;
import haven.render.DrawList;
import haven.render.Environment;
import haven.render.InstanceList;
import haven.render.State;
import haven.render.gl.GLEnvironment;
import io.brodgar.prof.Overhead;
import io.brodgar.prof.Prof;
import io.brodgar.session.Sessions;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
 * no cost when disarmed. The frame surface above is the opposite: it exists only while armed. {@code :textcache()}
     * (026.2) joins them on the same terms: the text cache counts its own hits and bytes in order to bound
     * itself, so reading them needs nothing armed. {@code :session()} (070.1) joins them for the sharper
     * reason stated on the method: what it counts happens on a camera nobody arms a profiler to watch.
 *
 * <p>Task 019.2 ships {@code :frame()}, {@code :history(n)} and {@code :reset()}; 019.3 the four counters;
 * 019.4 {@code :addons()} plus {@code :scope()}/{@code :measure()}; 019.5 {@code :widgets()}.
 * {@code :passes()}, {@code :gl()} and {@code :overhead()} land in 019.6+ on this same handle.
 */
public final class ProfHandle {
    private ProfHandle() {}

    /**
     * Create the profiling handle ({@code hafen.client:profiling()}). Per-owner, because {@code :scope()} and
     * {@code :measure()} (019.4) charge their time to the addon that asked — the same reason the options tree
     * is built per addon.
     */
    static LuaValue create(Addon owner) {
        // Userdata, like every handle in the API: p:frmae() is answered by the metatable below, p.reset = nil
        // is refused, and tostring(p) is Profiling rather than table: 0x...
        LuaValue p = LuaValue.userdataOf(new Mark());
        LuaTable mt = new LuaTable();
        // Refusal.closedIndex, not the methods table itself: p:frmae() would otherwise read as plain nil and
        // fail one character later as "attempt to call a nil value", naming neither the verb nor this line.
        mt.set(LuaValue.INDEX, Refusal.closedIndex("profiling", methods(p, owner),
            "the profiling handle",
            ":frame() :history() :addons() :widgets() :passes() :gl() :overhead() and :reset() need"
            + " profiling armed; the counters and :scope()/:measure() answer whether it is or not"));
        mt.set("__name", LuaValue.valueOf("Profiling"));
        mt.set("__tostring", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                return LuaValue.valueOf("Profiling");
            }
        });
        p.setmetatable(mt);
        return p;
    }

    /** The opaque instance behind the profiling userdata (facade-safe: no Java object of the client's
     *  crosses). The handle is a stateless proxy over {@link Prof}, so it has nothing else to carry. */
    private static final class Mark {
        public String toString() {
            return "Profiling";
        }
    }

    private static LuaTable methods(final LuaValue handle, final Addon owner) {
        LuaTable m = new LuaTable();

        // p:frame() -- the frame that just finished, in milliseconds throughout. Empty when profiling is off
        // or when the ring has not filled its first sample yet.
        m.set("frame", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                if(Args.passed(a, 2))
                    throw new LuaError("client:profiling():frame() is read-only — it is the frame that just"
                        + " finished and takes no argument; p:history(n) is how you ask for more than one.");
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
                if(Args.passed(a, 2))
                    throw new LuaError("client:profiling():memory() is read-only \u2014 it counts what the JVM heap"
                        + " did and takes no argument; call it with none to read.");
                return memory();
            }
        });

        // p:net() -- the connection's packet/byte counters and its smoothed round-trip time (ms).
        m.set("net", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                if(Args.passed(a, 2))
                    throw new LuaError("client:profiling():net() is read-only \u2014 it counts what the network layer"
                        + " did and takes no argument; call it with none to read.");
                return net();
            }
        });

        // p:loader() -- the async queue depths: the UI loader, the Defer pool, the resource queues.
        m.set("loader", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                if(Args.passed(a, 2))
                    throw new LuaError("client:profiling():loader() is read-only \u2014 it counts what the resource loader"
                        + " did and takes no argument; call it with none to read.");
                return loader();
            }
        });

        // p:render() -- the graphics counters as NUMBERS: draw slots, batching, tree size, VRAM, programs.
        m.set("render", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                if(Args.passed(a, 2))
                    throw new LuaError("client:profiling():render() is read-only \u2014 it counts what the render pass"
                        + " did and takes no argument; call it with none to read.");
                return render();
            }
        });

        // p:surfaces() -- what the widgets standing in the 3D world (044) actually cost: how many there are, how
        // many offscreen passes have been issued, and how many frames those passes were offered. Pull-only: the
        // surface pass keeps these to bound ITSELF, so reading them is three field reads and answers with
        // profiling off. Global rather than per addon, because the pass is one walk of one list.
        m.set("surfaces", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                if(Args.passed(a, 2))
                    throw new LuaError("client:profiling():surfaces() is read-only \u2014 it counts what the surface pass"
                        + " did and takes no argument; call it with none to read.");
                return surfaces();
            }
        });

        // p:entities() -- the things standing at a POINT in the 3D world (045.2): how many there are, how many
        // are waiting for a place this session cannot locate yet, and how many times the layer has re-derived
        // where they are. Pull-only like the counters above: two list reads and a long, answering with
        // profiling off. Global rather than per addon, because the re-derivation is one walk of one list.
        m.set("entities", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                if(Args.passed(a, 2))
                    throw new LuaError("client:profiling():entities() is read-only \u2014 it counts what the entity pass"
                        + " did and takes no argument; call it with none to read.");
                return entities();
            }
        });

        // p:session() -- what the session layer answered for the sessions this client holds open beside the one
        // it draws (070). Pull-only like the counters above, and for a sharper reason than any of them: the
        // ground query it counts runs on a camera panned over another session's ground, which is a case nobody
        // arms a profiler for. A counter that needed arming would be unreadable exactly where it is wanted.
        // Read-only in the strict sense too -- an argument is refused rather than ignored, because there is
        // nothing here to write and a caller who passed one meant something else.
        m.set("session", new VarArgFunction() {
            public Varargs invoke(Varargs a) {   // colon call: arg(1) is the handle
                if(Args.passed(a, 2))
                    throw new LuaError("client:profiling():session() is read-only — it counts what the session"
                                       + " layer did and takes no argument; call it with none to read.");
                return session();
            }
        });

        // p:textcache() -- the rendered-text cache behind g:text/g:atext (026). Pull-only like the four above:
        // it answers with profiling OFF, because nothing in it is a probe -- the cache keeps these numbers to
        // bound ITSELF, and this only reads them. Per addon, so the top level is the CALLER's own cache.
        m.set("textcache", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                if(Args.passed(a, 2))
                    throw new LuaError("client:profiling():textcache() is read-only \u2014 it counts what the text cache"
                        + " did and takes no argument; call it with none to read.");
                return textcache(owner);
            }
        });

        // --------------------------------------------------------------- per-addon cost + scopes (019.4)

        // p:addons() -- one row per Lua owner (every loaded addon, plus the :lua REPL, which owns the scopes
        // of a console snippet), sorted most expensive first, plus a `total` row. Empty when profiling is off.
        m.set("addons", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                if(Args.passed(a, 2))
                    throw new LuaError("client:profiling():addons() is read-only \u2014 it counts what the addons"
                        + " did and takes no argument; call it with none to read.");
                return addons();
            }
        });

        // p:scope(name) -- a named marker owned by the CALLING addon; s:begin()/s:finish() bracket a section
        // and the time lands under that addon's row in p:addons(). Names are per-addon, so two addons may
        // both use "update". Both verbs are no-ops when profiling is off.
        m.set("scope", new VarArgFunction() {
            public Varargs invoke(Varargs a) {   // colon call: arg(1) is the handle
                return ProfScope.create(owner, Args.str(a, 2, "client:profiling():scope", "name",
                    "the label this section shows under in p:addons()").tojstring());
            }
        });

        // p:measure(name, fn, ...) -- the wrapper form of the above: runs fn (armed or not) and returns what
        // it returns, with the scope closed even if fn errors.
        m.set("measure", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                // `nm`, never `name`: LibFunction declares a protected `name` field that a local of that
                // spelling shadows inside these anonymous subclasses (see ProfScope.create's own note).
                String nm = Args.str(a, 2, "client:profiling():measure", "name",
                    "the label this section shows under in p:addons()").tojstring();
                LuaValue fn = Args.required(a, 3, "client:profiling():measure", "fn");
                if(!fn.isfunction())
                    throw new LuaError("client:profiling():measure: fn must be a function — it is run inside"
                        + " the scope and whatever it returns is handed back, got " + fn.typename());
                return ProfScope.measure(owner, nm, fn, a.subargs(4));
            }
        });

        // --------------------------------------------------------------- per-widget cost (019.5)

        // p:widgets() -- where the UI's own frame time went, per widget type and per widget. Armed-only:
        // unlike the counters above, nothing counts widget time unless the switch is on.
        m.set("widgets", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                if(Args.passed(a, 2))
                    throw new LuaError("client:profiling():widgets() is read-only \u2014 it counts what the widget tree"
                        + " did and takes no argument; call it with none to read.");
                return widgets();
            }
        });

        // --------------------------------------------------------------- named passes + GL counters (019.6)

        // p:passes() -- the curated render passes with CPU and GPU time side by side. Armed-only.
        m.set("passes", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                if(Args.passed(a, 2))
                    throw new LuaError("client:profiling():passes() is read-only \u2014 it counts what the render passes"
                        + " did and takes no argument; call it with none to read.");
                return passes();
            }
        });

        // p:gl() -- the submission counters that need genuinely NEW counting: draw calls, program binds,
        // vertices and triangles per frame. Armed-only, unlike the pull-only counters above.
        m.set("gl", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                if(Args.passed(a, 2))
                    throw new LuaError("client:profiling():gl() is read-only \u2014 it counts what the GL driver"
                        + " did and takes no argument; call it with none to read.");
                return gl();
            }
        });

        // --------------------------------------------------------------- what profiling itself costs (019.7)

        // p:overhead() -- the cost of being profiled, per tier, so the "no more than 5% of frame time"
        // budget is enforceable rather than aspirational. Armed-only: off, there is nothing to account for.
        m.set("overhead", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                if(Args.passed(a, 2))
                    throw new LuaError("client:profiling():overhead() is read-only \u2014 it counts what the profiler itself"
                        + " did and takes no argument; call it with none to read.");
                return overhead();
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
        UI u = AddonManager.screen();
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
        UI u = AddonManager.screen();
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
        UI u = AddonManager.screen();
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

    // ------------------------------------------------------------------------- standing widgets (044.1)

    /**
     * {@code p:surfaces()} — the widgets standing in the 3D world, and what drawing them costs.
     *
     * <p>{@code live} is how many surfaces exist right now, across every addon, and {@code culled} how many of
     * those are being skipped this instant because nothing is looking at them (044.7): the camera is pointing
     * elsewhere, the entity is hidden, or its gob has left the scene. Both are instantaneous counts, not totals.
     * {@code uploads} is how many offscreen passes have actually been issued and {@code frames} how many frames
     * those passes were offered — <b>cumulative since the client started</b>, so both mean something as a
     * <b>delta between two reads</b>:
     * take one, wait, take another. That pair is the whole claim the feature makes about its own cost. A panel
     * nothing changes holds {@code uploads} still while {@code frames} climbs; a panel painted by a
     * {@code widget:on("Draw", …)} handler moves them together, because a Lua function of anything can only be
     * known by running it.
     *
     * <p><b>Pull-only</b> (D-051), like {@code p:memory()} and the four counters beside it: nothing here is a
     * probe. It answers whether profiling is armed or not.
     */
    private static LuaTable surfaces() {
        LuaTable t = new LuaTable();
        t.set("live", LuaValue.valueOf(WidgetSurface.liveCount()));
        t.set("culled", LuaValue.valueOf(WidgetSurface.culledCount()));
        t.set("uploads", LuaValue.valueOf((double)WidgetSurface.uploads()));
        t.set("frames", LuaValue.valueOf((double)WidgetSurface.frames()));
        return t;
    }

    // ------------------------------------------------------------------------- places that wait (045.2)

    /**
     * {@code p:entities()} — the client-only things standing at a <b>point</b> in the world, and what keeping
     * them there costs. An entity anchored to a game object is not counted: its place is that object's, so
     * there is nothing to re-derive for it.
     *
     * <p>{@code placed} is how many there are right now and {@code waiting} how many of those hold a place this
     * session cannot locate — a place recorded in another part of the world, or (while the player is in a cave)
     * every overworld place at once. Both are instantaneous counts. {@code passes} is how many times the layer
     * has re-derived the lot, <b>cumulative since the client started</b>, and it is the number that says this is
     * an event rather than a poll: it moves a handful of times while walking and not at all while standing
     * still.
     *
     * <p><b>Pull-only</b> (D-051), like the counters above: it answers whether profiling is armed or not.
     */
    private static LuaTable entities() {
        LuaTable t = new LuaTable();
        t.set("placed", LuaValue.valueOf(VrApi.freeCount()));
        t.set("waiting", LuaValue.valueOf(VrApi.waitingCount()));
        t.set("passes", LuaValue.valueOf((double)VrApi.regroundPasses()));
        return t;
    }

    // ------------------------------------------------------------------------- the session layer (070)

    /**
     * {@code p:session()} — what the layer holding the other sessions actually answered.
     *
     * <p>{@code live} is how many sessions the client holds, the one on screen included — a <b>gauge</b>,
     * and the one number in this group that is not a running total: it goes up when a session joins and
     * down when one ends, and reads zero on the login screen. Since 071.1 the drawn session is one of
     * them rather than the client's own, so with one character in the world this reads 1, where before
     * it could only have read 0.
     *
     * <p>{@code groundAnswered} and {@code groundMissed} are the two exits of {@code Sessions.groundz}: the
     * free camera panned over ground the drawn session has never loaded asks every other session it holds for
     * the height there, and either one of them has that ground or the camera keeps the height it last had.
     * Both are <b>cumulative since the client started</b> and therefore mean something as a <b>delta between
     * two reads</b>: take one, pan, take another. A client with one session up misses every time by
     * construction, since only the anchor is placed and the anchor is exactly the map that already threw.
     *
     * <p>{@code states} is how many sessions hold <b>engine state</b> right now — the per-session caches the
     * addon layer keys on the {@code UI} its session runs in — and its whole claim is that it <b>equals
     * {@code live}</b>. It is the one number this feature's inertness can be checked by: a state is minted
     * when a session first needs one and released when its {@code UI} is destroyed, so {@code states > live}
     * says a relogin left one behind (a leak of one entry per relogin, which shows as nothing at all
     * otherwise) and {@code states < live} says a session is holding none yet, which is true for the beat
     * between a session registering and its {@code UI} being built. Instantaneous, like {@code live}.
     *
     * <p>{@code addonsLive} and {@code engineReloads} are the addon layer's own pair (074.2), and they are here
     * rather than beside the per-addon figures because what they describe is the layer's relationship to the
     * sessions. {@code addonsLive} is how many addons are running — instantaneous, like {@code live}, and its
     * claim is that <b>it does not move when the screen does</b>. {@code engineReloads} is how many times the
     * engine rebuilt itself without being asked, and its only correct value is <b>zero</b>: it is derived from
     * the difference between the number of times the Lua layer has been built and the number of reloads the
     * user asked for, so a switch that quietly tore the addons down and loaded them again — which is what this
     * client did until 074 — shows up as a number rather than as a Lua value that silently went missing.
     *
     * <p>{@code placedRebuiltOffTick} is the fifth, and it is the one whose interesting value is the one it
     * holds: the layer caches where every session stands, that cache is built by walking a widget tree the
     * frame is mutating, and so the frame's own thread builds it and everything else reads what the frame
     * published. The pick pass is the everything else — a click resolves in a GPU readback callback, on a
     * thread of the graphics environment's own — and this counts the times it arrived before the frame had
     * published anything. A number that climbs does not say the walk went wrong; it says a click was
     * answered in the drawn session's frame when it should have been translated out of another's.
     *
     * <p>No key is ever absent. A zero here is a real count — the query has not run — rather than the
     * "not measured" an absent key means elsewhere in this surface (D-050), because the layer counts from the
     * frame the client starts.
     *
     * <p><b>Pull-only</b> (D-051): it answers whether profiling is armed or not. The case it describes is a
     * camera over another session's ground, which nobody arms a profiler to watch, so a counter that needed
     * arming would be blind precisely where it is worth reading.
     */
    private static LuaTable session() {
        LuaTable t = new LuaTable();
        t.set("live", LuaValue.valueOf(Sessions.live()));
        t.set("states", LuaValue.valueOf(AddonManager.stateCount()));
        t.set("groundAnswered", LuaValue.valueOf((double)Sessions.groundAnswered()));
        t.set("groundMissed", LuaValue.valueOf((double)Sessions.groundMissed()));
        t.set("placedRebuiltOffTick", LuaValue.valueOf((double)Sessions.placedRebuiltOffTick()));
        t.set("addonsLive", LuaValue.valueOf(AddonManager.addonsLive()));
        t.set("engineReloads", LuaValue.valueOf(AddonManager.engineReloads()));
        return t;
    }

    // ------------------------------------------------------------------------- the text cache (026.2)

    /**
     * {@code p:textcache()} — the rendered-text cache {@code g:text}/{@code g:atext} draw through (026). The
     * cache is <b>per addon</b>, so the top level is the <b>calling</b> addon's own: {@code entries},
     * {@code bytes} (GL texture bytes held), {@code hits}, {@code misses}, {@code evictions} and
     * {@code hitRate} (absent until something has been looked up — an absent key is "not measured", which a 0
     * would not be, D-050), plus {@code maxEntries}/{@code maxBytes}, the two caps it is bounded by. A count
     * without its ceiling says nothing, which is why the caps are in the table rather than only in the source.
     *
     * <p>{@code total} is the same five figures summed across <b>every</b> Lua owner (the loaded addons plus
     * the {@code :lua} REPL), with {@code owners} saying how many were summed. That is the leak check: disable
     * every addon and {@code total.bytes} goes to ~0, because teardown drops each cache and disposes its
     * textures. It is also the only view a console snippet has of somebody else's cache.
     *
     * <p><b>Pull-only</b> (D-051): this answers whether profiling is armed or not, like {@code p:memory()} and
     * the other three counters — every number is one the cache maintains anyway in order to bound itself, and
     * reading it costs one lock and five field reads. The counters are <b>cumulative since the addon loaded</b>;
     * a {@code :reload} builds a fresh {@link Addon} and therefore a fresh cache and a fresh count.
     *
     * <p><b>How to read a miss.</b> A miss is not a fault: it is a string that had never been drawn in that
     * font at that font generation, and it costs exactly what every draw cost before 026. A line whose text
     * changes every frame therefore misses every frame and always will — budget a live readout by how often
     * its <i>text</i> changes, not by how many lines it has.
     */
    private static LuaTable textcache(Addon owner) {
        LuaTable t = new LuaTable();
        if(owner != null)
            fillTextcache(t, owner.texts.entries(), owner.texts.bytes(),
                          owner.texts.hits(), owner.texts.misses(), owner.texts.evictions());
        t.set("maxEntries", LuaValue.valueOf(LuaGOut.Cache.MAXENTRIES));
        t.set("maxBytes", LuaValue.valueOf((double)LuaGOut.Cache.MAXBYTES));
        long entries = 0, bytes = 0, hits = 0, misses = 0, evictions = 0;
        List<Addon> owners = AddonManager.profOwners();
        for(int i = 0; i < owners.size(); i++) {
            LuaGOut.Cache c = owners.get(i).texts;
            entries += c.entries();  bytes += c.bytes();
            hits += c.hits();  misses += c.misses();  evictions += c.evictions();
        }
        LuaTable all = new LuaTable();
        fillTextcache(all, entries, bytes, hits, misses, evictions);
        all.set("owners", LuaValue.valueOf(owners.size()));
        t.set("total", all);
        return t;
    }

    /** The five figures of one cache (or of the sum), plus the hit rate when there has been a lookup at all. */
    private static void fillTextcache(LuaTable t, long entries, long bytes, long hits, long misses, long evictions) {
        t.set("entries", LuaValue.valueOf((double)entries));
        t.set("bytes", LuaValue.valueOf((double)bytes));
        t.set("hits", LuaValue.valueOf((double)hits));
        t.set("misses", LuaValue.valueOf((double)misses));
        t.set("evictions", LuaValue.valueOf((double)evictions));
        long look = hits + misses;
        if(look > 0)
            t.set("hitRate", LuaValue.valueOf((double)hits / (double)look));
    }

    // ------------------------------------------------------------------------- per-addon cost (019.4)

    /**
     * {@code p:addons()} — what each addon's Lua actually cost, for the last <b>completed</b> frame.
     *
     * <p>One row per Lua owner: {@code id}, {@code ms} (that frame), {@code msAvg} and {@code msPeak} (since
     * the switch was armed or {@code p:reset()} called), {@code share} of the frame, {@code calls} (the call
     * count by category: {@code events}/{@code timers}/{@code draw}/{@code hooks}/{@code widgets}),
     * {@code cost} (the same split in ms) and {@code scopes} (the addon's named markers, keyed by name). Rows
     * are sorted most expensive first, so the top of the list is the answer to "who is costing me frames".
     *
     * <p>{@code total} closes the loop with the frame surface: it is the <b>same</b> number
     * {@code p:frame().addons} reports, read from the same {@code tickLuaNanos} accounting the D-018 watchdog
     * uses — there is no second measurement of addon cost anywhere in the client.
     *
     * <p>Two things the numbers mean literally. A <b>nested</b> Lua call (an addon callback that calls back
     * into the engine, which calls Lua again) is charged to both brackets, exactly as the watchdog has always
     * charged it — the categories inherit that, so {@code cost} can sum slightly above {@code ms} on
     * re-entrant frames. And the {@code (console)} row is the {@code :lua} REPL: it is not an addon and the
     * watchdog exempts it, but its Lua time is frame cost like any other, so it is a row and it is in the
     * total.
     */
    private static LuaTable addons() {
        LuaTable out = new LuaTable();
        if(!Prof.armed())
            return out;
        double frameMs = 0;
        int n = Prof.count();
        if(n > 0)
            frameMs = Prof.ms(Prof.slot(n - 1));
        List<Addon> owners = AddonManager.profOwners();
        // Sorted at snapshot time, never on the frame path — and by the frame's own cost, so arming a runaway
        // addon puts it straight at the top of the table a profiler window draws.
        owners.sort((x, y) -> Long.compare(y.profNanos, x.profNanos));
        long total = 0;
        for(int i = 0; i < owners.size(); i++) {
            Addon a = owners.get(i);
            total += a.profNanos;
            out.set(i + 1, row(a, frameMs));
        }
        LuaTable t = new LuaTable();
        t.set("ms", LuaValue.valueOf(ms(total)));
        if(frameMs > 0)
            t.set("share", LuaValue.valueOf(ms(total) / frameMs));
        out.set("total", t);
        return out;
    }

    /** One addon's row. */
    private static LuaTable row(Addon a, double frameMs) {
        LuaTable r = new LuaTable();
        r.set("id", LuaValue.valueOf((a.manifest != null) ? a.manifest.id : "?"));
        double cur = ms(a.profNanos);
        r.set("ms", LuaValue.valueOf(cur));
        r.set("msAvg", LuaValue.valueOf((a.profFrames > 0) ? (ms(a.profSumNanos) / a.profFrames) : 0));
        r.set("msPeak", LuaValue.valueOf(ms(a.profPeakNanos)));
        if(frameMs > 0)
            r.set("share", LuaValue.valueOf(cur / frameMs));
        LuaTable calls = new LuaTable(), cost = new LuaTable();
        for(int i = 0; i < Addon.CATS.length; i++) {
            calls.set(Addon.CATS[i], LuaValue.valueOf(a.profCalls[i]));
            cost.set(Addon.CATS[i], LuaValue.valueOf(ms(a.profCat[i])));
        }
        r.set("calls", calls);
        r.set("cost", cost);
        LuaTable scopes = new LuaTable();
        for(Addon.Scope s : a.scopes.values()) {
            LuaTable e = new LuaTable();
            e.set("ms", LuaValue.valueOf(ms(s.lastNanos)));
            e.set("msAvg", LuaValue.valueOf((s.frames > 0) ? (ms(s.sumNanos) / s.frames) : 0));
            e.set("msPeak", LuaValue.valueOf(ms(s.peakNanos)));
            e.set("calls", LuaValue.valueOf(s.lastCalls));
            scopes.set(s.name, e);
        }
        r.set("scopes", scopes);
        return r;
    }

    /** Nanoseconds to milliseconds — the unit this whole surface reports time in. */
    private static double ms(long nanos) {
        return nanos * 1e-6;
    }

    // ------------------------------------------------------------------------- per-widget cost (019.5)

    /** How many individual widgets {@code p:widgets().top} lists — a table to read, not the whole tree. */
    private static final int TOPN = 20;

    /**
     * {@code p:widgets()} — the UI half of the frame, broken down. {@code utick} and {@code draw} in
     * {@code p:frame()} say <b>how much</b> the widget tree cost; this says <b>who</b>.
     *
     * <p>{@code byType} is one row per widget class ({@code type}, {@code count}, {@code tickMs} and
     * {@code drawMs} <b>inclusive</b> of children, {@code tickSelfMs}/{@code drawSelfMs}/{@code selfMs}
     * exclusive of them), sorted by self time — the top of that list is the answer to "what is my UI
     * spending its frame on". {@code top} is the heaviest individual widgets by self time, each with its
     * server-side {@code id} when it has one and its {@code owner} addon when an addon put it there.
     * {@code total} is the <b>root</b> widget's inclusive tick and draw, i.e. the whole tree: the self
     * times of every row sum to it, which is what makes the breakdown reconcilable rather than indicative.
     *
     * <p><b>Inclusive vs self.</b> Inclusive time is measured by a widget's parent, around the call that
     * draws or ticks its entire subtree; self time is that minus the sum of its children's inclusive time.
     * A container with an expensive child therefore shows a large {@code tickMs} and a near-zero
     * {@code tickSelfMs}, and only the child is blamed.
     *
     * <p><b>Which frame.</b> The last one in which the widget was ticked or drawn — the frame in progress
     * or the one just finished. Widgets not touched since (a closed window that is still in the tree, a
     * hidden tab) age out and are simply absent, rather than reporting a cost they no longer have.
     *
     * <p><b>What is not in it.</b> {@code tickMs} is the tick traversal only. The {@code utick} phase in
     * {@code p:frame()} also covers {@code gtick} (the render-thread hand-off), the hover query and any
     * resize, so the tree's tick total is a little under that phase by design — timing {@code gtick} per
     * widget would double the probe count to attribute a pass that does almost nothing per widget. Likewise
     * the {@code draw} phase covers the whole 3D scene, of which the {@code MapView} row is the widget-side
     * share; the scene's own breakdown is what the named passes of 019.6 are for.
     *
     * <p>Empty when profiling is off, and empty before the first armed frame has drawn.
     */
    private static LuaTable widgets() {
        LuaTable out = new LuaTable();
        UI u = AddonManager.screen();
        if(!Prof.armed() || (u == null) || (u.root == null))
            return out;
        Map<String, double[]> types = new HashMap<String, double[]>();
        List<Object[]> top = new ArrayList<Object[]>();
        walk(u.root, types, top);
        if(types.isEmpty())
            return out;

        // byType, sorted by self time -- at snapshot time, never on the frame path.
        List<Map.Entry<String, double[]>> rows = new ArrayList<Map.Entry<String, double[]>>(types.entrySet());
        rows.sort((x, y) -> Double.compare(y.getValue()[T_TSELF] + y.getValue()[T_DSELF],
                                           x.getValue()[T_TSELF] + x.getValue()[T_DSELF]));
        LuaTable bt = new LuaTable();
        for(int i = 0; i < rows.size(); i++) {
            double[] v = rows.get(i).getValue();
            LuaTable r = new LuaTable();
            r.set("type", LuaValue.valueOf(rows.get(i).getKey()));
            r.set("count", LuaValue.valueOf((int)v[T_COUNT]));
            r.set("tickMs", LuaValue.valueOf(v[T_TICK]));
            r.set("drawMs", LuaValue.valueOf(v[T_DRAW]));
            r.set("tickSelfMs", LuaValue.valueOf(v[T_TSELF]));
            r.set("drawSelfMs", LuaValue.valueOf(v[T_DSELF]));
            r.set("selfMs", LuaValue.valueOf(v[T_TSELF] + v[T_DSELF]));
            bt.set(i + 1, r);
        }
        out.set("byType", bt);

        top.sort((x, y) -> Double.compare((Double)y[2], (Double)x[2]));
        LuaTable tp = new LuaTable();
        for(int i = 0, n = Math.min(TOPN, top.size()); i < n; i++) {
            Object[] e = top.get(i);
            Widget w = (Widget)e[1];
            LuaTable r = new LuaTable();
            r.set("type", LuaValue.valueOf((String)e[0]));
            r.set("selfMs", LuaValue.valueOf((Double)e[2]));
            r.set("tickMs", LuaValue.valueOf((Double)e[3]));
            r.set("drawMs", LuaValue.valueOf((Double)e[4]));
            int id = u.widgetid(w);
            if(id >= 0)                     // absent, not -1: most widgets are client-side and have no server id
                r.set("id", LuaValue.valueOf(id));
            String own = owner(w);
            if(own != null)
                r.set("owner", LuaValue.valueOf(own));
            tp.set(i + 1, r);
        }
        out.set("top", tp);

        // The whole tree: the ROOT's inclusive tick and draw. Every row's self time sums to this.
        LuaTable t = new LuaTable();
        long[] rp = u.root.prof;
        if((rp != null) && Prof.fresh(rp[Widget.PR_GEN])) {
            t.set("tickMs", LuaValue.valueOf(ms(rp[Widget.PR_TICK])));
            t.set("drawMs", LuaValue.valueOf(ms(rp[Widget.PR_DRAW])));
            t.set("ms", LuaValue.valueOf(ms(rp[Widget.PR_TICK] + rp[Widget.PR_DRAW])));
        }
        t.set("count", LuaValue.valueOf(top.size()));
        out.set("total", t);
        return out;
    }

    /* Indices into the per-type accumulator. A double[] rather than a class: this is snapshot-time scratch,
     * one array per widget TYPE, and it never outlives the call. */
    private static final int T_COUNT = 0, T_TICK = 1, T_DRAW = 2, T_TSELF = 3, T_DSELF = 4, T_N = 5;

    /**
     * Walk the widget tree, folding every widget with a live measurement into its type row and into the
     * candidate list for {@code top}. Depth-first, on the UI thread, at snapshot time only — the hot path
     * never touches a map or a class name.
     */
    private static void walk(Widget w, Map<String, double[]> types, List<Object[]> top) {
        long[] p = w.prof;
        if((p != null) && Prof.fresh(p[Widget.PR_GEN])) {
            double tick = ms(p[Widget.PR_TICK]), draw = ms(p[Widget.PR_DRAW]);
            // Self can come out marginally negative when a child's bracket straddles a clock hiccup; a
            // negative cost is not a thing, so it clamps rather than propagating into the type totals.
            double tself = Math.max(0, ms(p[Widget.PR_TICK] - p[Widget.PR_TICKCH]));
            double dself = Math.max(0, ms(p[Widget.PR_DRAW] - p[Widget.PR_DRAWCH]));
            String ty = typename(w);
            double[] r = types.get(ty);
            if(r == null)
                types.put(ty, r = new double[T_N]);
            r[T_COUNT]++;
            r[T_TICK] += tick;   r[T_DRAW] += draw;
            r[T_TSELF] += tself; r[T_DSELF] += dself;
            top.add(new Object[] {ty, w, tself + dself, tick, draw});
        }
        for(Widget c = w.child; c != null; c = c.next)
            walk(c, types, top);
    }

    /** The type name a row is keyed by — the simple class name, with anonymous classes named by their base. */
    private static String typename(Widget w) {
        Class<?> c = w.getClass();
        String n = c.getSimpleName();
        while(n.isEmpty() && (c.getSuperclass() != null)) {   // an anonymous subclass: report what it IS
            c = c.getSuperclass();
            n = c.getSimpleName();
        }
        return n.isEmpty() ? w.getClass().getName() : n;
    }

    /**
     * The addon that put this widget in the tree, or {@code null} for a client widget. An {@link Owned} widget
     * is the only one that knows — which is exactly the link that makes {@code :widgets()} and
     * {@code :addons()} two views of the same cost rather than two measurements of it. Since 040.1 that is a
     * contract rather than a class, so a control an addon built is attributed to it too.
     */
    private static String owner(Widget w) {
        if(!(w instanceof Owned))
            return null;
        Addon a = ((Owned)w).profOwner();
        return ((a != null) && (a.manifest != null)) ? a.manifest.id : null;
    }

    // ------------------------------------------------------------------- named passes + GL counters (019.6)

    /**
     * {@code p:passes()} — a fixed, short, curated list of named sections of the frame with <b>CPU and GPU
     * time side by side</b>: {@code shadow} (the whole shadow-map render), {@code scene} (the 3D draw list)
     * and {@code ui2d} (the widget tree). One row per pass, in that order, each {@code {name=, cpuMs=,
     * gpuMs=}}, plus {@code frameno} saying which frame they describe.
     *
     * <p><b>Which frame, and why not the newest.</b> A pass's GPU column is a GL timestamp query and comes
     * back through fences several frames after its CPU column, so this reports the newest frame whose
     * timestamps have <b>resolved</b> — the same rule, and for the same reason, as {@code gpuMs} in
     * {@code p:frame()}. Both columns come from that one frame, so a row is internally consistent.
     *
     * <p><b>The passes are disjoint.</b> {@code shadow} and {@code scene} run <b>inside</b> the widget draw
     * (the {@code MapView} is a widget), so each row reports <b>self</b> time — its own span minus the passes
     * nested in it — exactly the inclusive/self split {@code p:widgets()} uses. {@code ui2d} is therefore what
     * the 2D UI cost, and the three sum to less than the frame rather than double-counting the scene.
     *
     * <p><b>The check this exists for:</b> turn Video → Shadows off and the {@code shadow} row falls to zero
     * while the GPU frame time drops by about what it had been reporting. That is "what do shadows cost me"
     * as a number.
     *
     * <p>The list is fixed on purpose: every boundary is a real GL timestamp query, which is not free and can
     * stall the pipeline if overused. Per-draw-call GPU attribution is out of scope and always will be.
     * Empty when profiling is off.
     */
    private static LuaTable passes() {
        LuaTable out = new LuaTable();
        if(!Prof.armed())
            return out;
        int s = Prof.passSlot();
        if(s < 0)
            return out;                 // armed, but no frame's timestamps have come back yet
        for(int i = 0; i < Prof.PASSES.length; i++) {
            LuaTable r = new LuaTable();
            r.set("name", LuaValue.valueOf(Prof.PASSES[i]));
            r.set("cpuMs", LuaValue.valueOf(Prof.passCpu(s, i)));
            r.set("gpuMs", LuaValue.valueOf(Prof.passGpu(s, i)));
            out.set(i + 1, r);
        }
        out.set("frameno", LuaValue.valueOf((double)Prof.frameno(s)));
        out.set("gpuMs", LuaValue.valueOf(Prof.gpuMs(s)));   // the whole frame, to measure the rows against
        out.set("ms", LuaValue.valueOf(Prof.ms(s)));
        return out;
    }

    /**
     * {@code p:gl()} — what the client actually handed the driver last frame: {@code drawCalls},
     * {@code programBinds}, {@code vertices} and {@code triangles}.
     *
     * <p>These four are the only counters in 019 that are <b>armed-only</b>. Everything in {@code p:render()}
     * is a number the client already keeps and merely formats into a HUD string, so it answers with profiling
     * off; nothing counts these, so they are new counting and they sit behind the master switch like every
     * other new probe. They are counted at the two per-frame submission seams — the draw-list walk and the
     * immediate path — not inside the render thread's replay loop, which no client should pay for.
     *
     * <p>{@code programBinds} against {@code drawCalls} is the batching story: the draw list is sorted by
     * program, so binds far below calls means the sort is working. Point and line geometry contributes to
     * {@code vertices} but not to {@code triangles}. Empty when profiling is off.
     */
    private static LuaTable gl() {
        LuaTable t = new LuaTable();
        int n = Prof.count();
        if(!Prof.armed() || (n == 0))
            return t;
        int s = Prof.slot(n - 1);
        t.set("drawCalls", LuaValue.valueOf((double)Prof.glDraws(s)));
        t.set("programBinds", LuaValue.valueOf((double)Prof.glProgBinds(s)));
        t.set("vertices", LuaValue.valueOf((double)Prof.glVerts(s)));
        t.set("triangles", LuaValue.valueOf((double)Prof.glTris(s)));
        t.set("frameno", LuaValue.valueOf((double)Prof.frameno(s)));
        return t;
    }

    // ------------------------------------------------------------------- what profiling costs (019.7)

    /** The budget 019 holds itself to: armed overhead no more than this share of frame time. */
    private static final double BUDGET = 0.05;

    /**
     * {@code p:overhead()} — what having profiling armed costs, as ms per frame and as a share of the frame,
     * <b>attributed per tier</b>. This is the number 019's second guarantee is judged against: armed overhead
     * ≤5% of frame time, target ≤2%, and a tier that cannot meet it ships behind its own checkbox rather than
     * dragging the feature down. Without the per-tier split that rule could not be applied, because the tier
     * to move could not be identified.
     *
     * <p>Everything is a <b>mean per frame</b> over the samples since the switch was armed (or
     * {@code p:reset()} called) — a per-frame figure would be noise at this scale.
     *
     * <table>
     *   <tr><th>Key</th><th>What it is</th></tr>
     *   <tr><td>{@code aggregatorMs}</td><td>the end-of-frame fold, <b>timed directly</b> — exact</td></tr>
     *   <tr><td>{@code gpuQueryMs}</td><td>the GL timestamp queries the named passes insert, timed directly</td></tr>
     *   <tr><td>{@code probeMs}</td><td>the <b>modelled</b> probe cost: hits × the per-hit cost calibrated when
     *       the switch armed</td></tr>
     *   <tr><td>{@code measuredMs}</td><td>the <b>measured</b> probe cost: the median paired armed-vs-control
     *       delta. Absent until enough control periods exist. May be <b>≤ 0</b>, which means the cost is
     *       under the comparison's noise floor, not that profiling made the client faster</td></tr>
     *   <tr><td>{@code measuredSpreadMs}</td><td>that comparison's noise floor (the interquartile spread of
     *       the per-period deltas) — what says whether {@code measuredMs} resolved anything</td></tr>
     *   <tr><td>{@code totalMs} / {@code shareOfFrame}</td><td>the budget number: the aggregator plus whichever
     *       of the two above is authoritative</td></tr>
     *   <tr><td>{@code method}</td><td>{@code "control"} when the measurement resolved a positive cost,
     *       {@code "model"} otherwise — i.e. whether {@code totalMs} is measured or calibrated</td></tr>
     *   <tr><td>{@code periods} / {@code periodsNeeded}</td><td>paired control periods collected, and how many
     *       more the measurement wants</td></tr>
     *   <tr><td>{@code budget} / {@code withinBudget}</td><td>the 5% ceiling and whether this run is inside it</td></tr>
     *   <tr><td>{@code tiers}</td><td>one row per tier, keyed by name — see below</td></tr>
     * </table>
     *
     * <p><b>Control frames.</b> One frame in 64 runs with every probe of this feature disarmed while the client
     * keeps profiling itself exactly as before. Each period yields one delta — the median <b>work</b> time
     * (frame time minus the {@code wait} and {@code dwait} phases; under vsync or a frame cap the total is
     * pinned to the cap and would show no delta at all) of its armed frames minus its control frame — and the
     * median of those deltas is a measured overhead at essentially zero marginal cost. It catches what the
     * model cannot: cache effects, JIT deopt, GPU query stalls. It is also frequently <b>unable to resolve
     * anything</b>, because the cost it is looking for is a fraction of a percent of a spiky frame time —
     * hence {@code measuredSpreadMs}, and hence the fall back to the model rather than to a zero.
     *
     * <p><b>Tiers.</b> {@code frame} (the fold), {@code addons} (the {@code callLua} category split),
     * {@code widgets} (the per-widget brackets), {@code passes} (the named-pass seams and their GL queries)
     * and {@code gl} (the submission counters) — five, one per independently armable probe set. Each row is
     * {@code {name=, ms=, share=, modelledMs=, method=}} plus {@code hits} (probe hits per frame) for the
     * modelled ones. {@code frame} is exact; the rest are modelled, and once the measured total exists they
     * are scaled to it in the modelled proportion, so the rows always add up to {@code totalMs}. Both figures
     * are reported, so nothing is hidden behind the scaling.
     *
     * <p><b>The modelled number errs high.</b> The calibration loops run cold, on the frame the checkbox is
     * ticked, while the real probes run inside methods HotSpot has compiled and inlined for the whole session.
     * That is the right direction for a budget, and it is superseded by the measurement as soon as there are
     * enough control frames — about 64 × 8 frames, roughly ten seconds at 60 fps.
     *
     * <p>Empty when profiling is off.
     */
    private static LuaTable overhead() {
        LuaTable t = new LuaTable();
        if(!Prof.armed())
            return t;
        double agg = Overhead.aggregatorMs(), total = Overhead.totalMs();
        t.set("aggregatorMs", LuaValue.valueOf(agg));
        t.set("gpuQueryMs", LuaValue.valueOf(Overhead.gpuQueryMs()));
        t.set("probeMs", LuaValue.valueOf(Overhead.probeMs()));
        boolean meas = Overhead.measured();
        if(Overhead.sampled()) {        // absent, not 0, until the control periods have something to say
            t.set("measuredMs", LuaValue.valueOf(Overhead.measuredMs()));
            t.set("measuredSpreadMs", LuaValue.valueOf(Overhead.measuredSpreadMs()));
            t.set("measuredErrorMs", LuaValue.valueOf(Overhead.measuredErrorMs()));
        }
        t.set("totalMs", LuaValue.valueOf(total));
        t.set("method", LuaValue.valueOf(meas ? "control" : "model"));
        t.set("armedFrames", LuaValue.valueOf((double)Overhead.armedFrames()));
        t.set("controlFrames", LuaValue.valueOf((double)Overhead.ctlFrames()));
        t.set("periods", LuaValue.valueOf(Overhead.periods()));
        t.set("periodsNeeded", LuaValue.valueOf(Overhead.periodsNeeded()));
        double frameMs = Prof.msAvg();
        t.set("budget", LuaValue.valueOf(BUDGET));
        if(frameMs > 0) {
            double share = total / frameMs;
            t.set("frameMs", LuaValue.valueOf(frameMs));
            t.set("shareOfFrame", LuaValue.valueOf(share));
            t.set("withinBudget", LuaValue.valueOf(share <= BUDGET));
        }
        LuaTable tiers = new LuaTable();
        for(int i = 0; i < Overhead.TIERS.length; i++) {
            LuaTable r = new LuaTable();
            double ms = Overhead.tierMs(i);
            r.set("name", LuaValue.valueOf(Overhead.TIERS[i]));
            r.set("ms", LuaValue.valueOf(ms));
            r.set("modelledMs", LuaValue.valueOf(Overhead.modelMs(i)));
            if(frameMs > 0)
                r.set("share", LuaValue.valueOf(ms / frameMs));
            double hits = Overhead.hits(i);
            if(hits >= 0) {             // the fold is timed, not counted: it has no hit count to report
                r.set("hits", LuaValue.valueOf(hits));
                r.set("method", LuaValue.valueOf(meas ? "control" : "model"));
            } else {
                r.set("method", LuaValue.valueOf("direct"));
            }
            tiers.set(Overhead.TIERS[i], r);
            tiers.set(i + 1, r);        // keyed AND ordered: a table to index, a list to draw in order
        }
        t.set("tiers", tiers);
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
