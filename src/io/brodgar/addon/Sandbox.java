package io.brodgar.addon;

import haven.Utils;

import java.util.concurrent.ConcurrentHashMap;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LoadState;
import org.luaj.vm2.LuaClosure;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaFunction;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.compiler.LuaC;
import org.luaj.vm2.lib.DebugLib;
import org.luaj.vm2.lib.PackageLib;
import org.luaj.vm2.lib.StringLib;
import org.luaj.vm2.lib.TableLib;
import org.luaj.vm2.lib.jse.JseBaseLib;
import org.luaj.vm2.lib.jse.JseMathLib;
import org.luaj.vm2.lib.jse.JseOsLib;
import org.luaj.vm2.lib.jse.JsePlatform;

/**
 * The addon <b>Lua sandbox</b> (spec {@code 12-security-and-permissions.md}, decisions D-017 + D-018).
 *
 * <p>Two protections, both aimed at <em>addon</em> code (not the operator):
 * <ul>
 *   <li><b>Environment whitelist (D-017).</b> {@link #create()} builds a {@link Globals} with only
 *       the safe stdlib — {@code string}, {@code table}, {@code math}, a trimmed {@code os}
 *       ({@code time}/{@code clock}/{@code date}/{@code difftime} only), and the safe base functions
 *       ({@code pairs}/{@code ipairs}/{@code next}/{@code select}/{@code type}/{@code tostring}/
 *       {@code tonumber}/{@code pcall}/{@code xpcall}/{@code error}/{@code assert}/…). LuaJ is 5.2:
 *       {@code unpack} is {@code table.unpack}, and there is no base-level alias.
 *       It is built <b>constructively</b> (load only safe libraries) rather than by neutering
 *       {@link JsePlatform#standardGlobals()} — so the dangerous surfaces are <i>absent</i>, not
 *       merely hidden: no {@code io}, no {@code luajava} (the Java-reflection escape hatch, which
 *       {@code standardGlobals()} bundles — the whole point of P1/D-017), no {@code debug} table, no
 *       {@code require}/{@code package}, and no {@code load}/{@code loadfile}/{@code dofile}. The few
 *       dangerous entries that ride along inside otherwise-safe libraries ({@code os.execute}/
 *       {@code exit}/{@code getenv}/{@code remove}/{@code rename}/{@code tmpname}, and the base
 *       loaders) are stripped explicitly.</li>
 *   <li><b>Instruction hard-stop watchdog (D-018, layer 1).</b> Every thread that enters Lua is a thread
 *       the client needs back — the frame's own, a Loader carrying an inbound message, the render query
 *       that resolved a click — so an infinite loop would freeze it. Each {@link Globals} gets a {@link Watchdog}
 *       installed as its {@code debuglib}: LuaJ then calls {@code onInstruction} on every VM
 *       instruction (guarded only by {@code debuglib != null} in {@code LuaClosure.execute}), so the
 *       watchdog decrements a per-call budget and raises a {@link LuaError} when it is exhausted —
 *       which the engine's per-callback error isolation catches. Setting the field <b>without</b>
 *       {@code load()}-ing a {@code DebugLib} means the hook is active yet no {@code debug} table is
 *       reachable from Lua (D-017).</li>
 * </ul>
 *
 * <p>The {@link #arm(Globals)} / call / {@link #disarm(Globals, long)} pattern gives a full budget to
 * <em>every</em> entry into Lua (the call sites: each handler/timer via {@code AddonManager.callLua},
 * each file body in {@link Addon#run()}, and each {@code :lua} REPL evaluation), so a legitimate
 * callback always gets the full budget and only a genuine runaway trips it. <b>The budget is per entry,
 * not per environment</b> (126.2): {@code threading.md} states that an action handler and an inbound
 * message handler can be running one addon's Lua while the step is running its Lua too, so a single
 * counter per {@link Globals} would have one entry reset the other's budget and lose the other's
 * decrements. {@link Watchdog} therefore <i>confines</i> the budget to the thread that armed it, and
 * {@code arm} hands back the budget it displaced so a nested entry puts its caller's back.
 *
 * <p><b>The {@code :lua} REPL is deliberately NOT whitelisted</b> ({@link #consoleGlobals()}): it is
 * the operator's own trusted debugging console (full {@code standardGlobals()}, incl. {@code luajava}
 * for poking the engine), and the sandbox exists to constrain <em>shared addon code</em>, not the
 * user at their own console. It still gets the watchdog, so an accidental {@code :lua while true do
 * end} is aborted rather than freezing the client.
 *
 * <p><b>Known limitation (deferred hardening):</b> LuaJ's string metatable is a process-global static
 * ({@code LuaString.s_metatable}); a hostile addon calling {@code getmetatable("")} could tamper with
 * string handling for everyone. D-017's explicit list does not cover it; per-env string metatables
 * are a later refinement. (The soft per-tick time budget + auto-disable, D-018 layer 2, is implemented
 * in Phase 1f-3 — see {@link #SOFT_BUDGET_NANOS} + {@link AddonManager#enforceSoftBudget()}.)
 */
public final class Sandbox {

    /**
     * Per-call instruction hard-stop cap (D-018). Generous enough that no legitimate single callback
     * or file body approaches it, yet low enough that a runaway loop is aborted within a brief hitch
     * rather than a real freeze. Override with {@code -Dhaven.addon.insncap=<n>} ({@code <= 0} disables
     * the hard stop entirely). Read once at class-load, like the other addon-layer properties.
     */
    static final long INSN_CAP = propLong("haven.addon.insncap", 10_000_000L);

    /**
     * Soft per-tick CPU budget (D-018 <b>layer 2</b>), the complement to the hard instruction cap above.
     * The hard stop bounds a <em>single</em> call; this bounds an addon's <em>total</em> Lua time within
     * one engine tick (summed across its {@code Update}, timers, and event handlers). An addon whose
     * per-tick Lua time exceeds {@link #SOFT_BUDGET_NANOS} for {@link #SOFT_STRIKE_LIMIT} <b>consecutive</b>
     * ticks is a sustained offender and is auto-disabled until the next load — a runaway the per-call cap
     * cannot catch (a handler that individually stays under the instruction cap yet burns most of every
     * frame). A single spike (a heavy {@code SessionEnteredWorld}, one janky frame) resets the strike counter, so
     * only genuinely sustained overrun trips it. Enforced in {@link AddonManager#enforceSoftBudget()} and
     * surfaced in the AddOns panel. Override with {@code -Dhaven.addon.tickbudgetms} (milliseconds;
     * {@code <= 0} disables the soft budget) and {@code -Dhaven.addon.tickstrikes}.
     */
    static final long SOFT_BUDGET_NANOS = propLong("haven.addon.tickbudgetms", 10L) * 1_000_000L;
    static final int  SOFT_STRIKE_LIMIT = (int)propLong("haven.addon.tickstrikes", 30L);

    private Sandbox() {
    }

    /**
     * Build a fresh <b>sandboxed</b> environment for an addon: the D-017 stdlib whitelist plus the
     * D-018 instruction watchdog. The returned {@link Globals} still needs the {@code hafen} facade
     * and {@code ADDON} table installed by the caller.
     */
    public static Globals create() {
        Globals g = new Globals();
        // Whitelist: load ONLY the safe libraries (constructive sandbox — dangerous libs are never
        // present, so there is nothing to forget to strip). Intentionally omitted vs standardGlobals():
        // JseIoLib (io), LuajavaLib (Java reflection), CoroutineLib, Bit32Lib, and DebugLib-as-a-table.
        // PackageLib IS loaded (the stdlib modules register themselves in package.loaded on load, so
        // omitting it makes TableLib/StringLib/… fail) — but require/module/package are stripped in
        // harden(): the LIBRARIES are wanted, the require MACHINERY is not (D-017 "withhold require").
        g.load(new JseBaseLib());   // assert/error/pcall/xpcall/select/type/tostring/tonumber/pairs/… (+ load*, stripped below)
        g.load(new PackageLib());   // needed only so the modules below can register; then stripped
        g.load(new TableLib());     // table.*
        g.load(new StringLib());    // string.* (+ the string metatable)
        g.load(new JseMathLib());   // math.*
        g.load(new JseOsLib());     // os.* (dangerous entries stripped below)
        LoadState.install(g);       // so env.load(src, name) can decode/compile addon chunks...
        LuaC.install(g);            // ...(the Lua->bytecode compiler)
        harden(g);
        return g;
    }

    /**
     * The {@code :lua} REPL environment: full {@link JsePlatform#standardGlobals()} (trusted operator
     * console — NOT whitelisted, see class doc) but with the watchdog installed so a runaway console
     * expression is still aborted.
     */
    public static Globals consoleGlobals() {
        Globals g = JsePlatform.standardGlobals();
        g.debuglib = new Watchdog();   // typo protection; does NOT install a `debug` table
        return g;
    }

    /** Strip the dangerous entries that ride inside otherwise-safe libs, then install the watchdog. */
    private static void harden(Globals g) {
        // require machinery — a controlled addon-folder-only require is a deferred nice-to-have; the
        // strict default withholds it entirely (D-017). The stdlib modules are already installed as
        // globals (string/table/…), so dropping package/require does not remove them.
        g.set("require", LuaValue.NIL);
        g.set("module", LuaValue.NIL);
        g.set("package", LuaValue.NIL);
        // Base loaders — arbitrary code / path loading (D-017 "withhold load/loadfile/dofile").
        g.set("load", LuaValue.NIL);
        g.set("loadfile", LuaValue.NIL);
        g.set("dofile", LuaValue.NIL);
        g.set("loadstring", LuaValue.NIL);   // 5.1-compat alias, if present
        // os.* — keep only time/clock/date/difftime; drop process/filesystem/env access.
        LuaValue os = g.get("os");
        if(!os.isnil()) {
            os.set("execute", LuaValue.NIL);
            os.set("exit", LuaValue.NIL);
            os.set("getenv", LuaValue.NIL);
            os.set("remove", LuaValue.NIL);
            os.set("rename", LuaValue.NIL);
            os.set("tmpname", LuaValue.NIL);
            os.set("setlocale", LuaValue.NIL);   // process-global locale state; not in the D-017 whitelist
        }
        // Watchdog: enabling the per-instruction hook without exposing a `debug` table (D-017).
        g.debuglib = new Watchdog();
    }

    /**
     * Claim a full {@link #INSN_CAP} instruction budget <b>on the calling thread</b> for the entry into
     * {@code g}'s Lua that is about to run. Call immediately before every entry into Lua, and hand the
     * value it returns to {@link #disarm(Globals, long)} in a {@code finally} — that is what releases
     * the claim, and what puts a caller's own budget back when one entry is nested inside another.
     * No-op (returning {@link #UNARMED}) if {@code g} has no watchdog.
     *
     * <p>A disabled cap ({@code -Dhaven.addon.insncap} {@code <= 0}) arms {@link Long#MAX_VALUE}, which
     * is how the hard stop is switched off: the arithmetic below is unchanged and simply never underflows.
     */
    public static long arm(Globals g) {
        if((g != null) && (g.debuglib instanceof Watchdog))
            return ((Watchdog)g.debuglib).arm();
        return UNARMED;
    }

    /**
     * Release what {@link #arm(Globals)} claimed, handing back the value it returned. The outermost
     * entry on a thread drops that thread's claim entirely — so a Loader thread that made one off-thread
     * call leaves nothing behind — and a nested one restores the budget its caller was spending.
     */
    public static void disarm(Globals g, long armed) {
        if((g != null) && (g.debuglib instanceof Watchdog))
            ((Watchdog)g.debuglib).disarm(armed);
    }

    /**
     * What {@link #arm(Globals)} returns when there was no budget to displace — no watchdog on {@code g},
     * or this thread's outermost entry into it. {@link #disarm(Globals, long)} reads it as "drop the
     * claim" rather than "restore this many instructions", and no real budget can collide with it because
     * a budget is only ever {@link #INSN_CAP} or {@link Long#MAX_VALUE} counting down.
     */
    static final long UNARMED = Long.MIN_VALUE;

    /**
     * <b>The thread the frame runs on</b> — {@link Watchdog}'s fast path, and {@code null} until the
     * {@code UILoop} constructor has finished. Asked of the loop once and then remembered: the loop is
     * built once and its thread never changes, so a second read can only agree. The field is plain
     * rather than {@code volatile} because it is written with a value that is already safely published
     * (through {@code Sessions}' own volatile) and every racing writer writes the same reference; a
     * thread that has not seen the write merely asks again.
     */
    private static Thread uith = null;

    static Thread uiThread() {
        Thread t = uith;
        return (t != null) ? t : (uith = io.brodgar.session.Sessions.uithread());
    }

    private static long propLong(String name, long def) {
        try {
            String v = Utils.getprop(name, null);
            return (v == null) ? def : Long.parseLong(v.trim());
        } catch(RuntimeException e) {
            return def;
        }
    }

    /**
     * A {@link DebugLib} whose only job is the instruction hard-stop. It is <b>assigned to</b>
     * {@code Globals.debuglib} (not {@code load()}-ed), so LuaJ invokes its {@code on*} callbacks
     * during execution but no {@code debug} table is ever installed into Lua. {@code onCall}/
     * {@code onReturn} are overridden to no-ops (the default implementations maintain a call-stack for
     * {@code debug.traceback}, which this sandbox does not need and which would touch an
     * uninitialized {@code globals}); {@code onInstruction} does the budget check and never calls
     * {@code super}, so it never touches that state either.
     *
     * <p><b>The budget is per entry into Lua, whatever thread entered</b> (126.2). It used to be one
     * plain field per environment, which is what {@code threading.md} makes wrong: an action handler and
     * an inbound message handler each enter one addon's Lua off the step's thread, and can be inside it
     * while the step is inside it too — so arming one entry reset the other's budget and the other's
     * decrements were lost against a counter that had moved. The budget is <b>thread-confined</b>
     * instead, and not shared by anybody:
     * <ul>
     *   <li>{@link #uiRem} — the thread that ticks and draws, which is very nearly every entry. Reached
     *       by one reference compare against {@link Sandbox#uiThread()}; plain, not {@code volatile},
     *       because only that one thread ever touches it. That is confinement, not synchronisation.</li>
     *   <li>{@link #off} — a holder per <em>other</em> thread, for the rare off-step entries. {@code arm}
     *       puts one in and the outermost {@code disarm} takes it out, so a Loader thread that made one
     *       call leaves nothing behind.</li>
     * </ul>
     * Neither is touched per instruction by more than one thread, so no reordering is possible and there
     * is nothing to make visible. On underflow the budget self-resets and throws, so a caught error does
     * not immediately re-trip on the next instruction.
     *
     * <p>{@code disarm} asks the map <b>first</b>, and the ordering is not arbitrary: {@code UILoop.th}
     * is assigned last in its constructor, so an entry made before that reads {@code null}, fails the
     * compare and takes the map — and would then be released against {@link #uiRem} if the release asked
     * the compare again. One map lookup per entry into Lua (not per instruction) buys a claim that is
     * always released where it was made.
     *
     * <p>{@link #traceback(int)} is overridden to return {@code ""}: LuaJ's default error path
     * ({@code LuaClosure.processErrorHooks}) calls {@code debuglib.traceback(level)} on <b>every</b>
     * error, and the inherited implementation dereferences the {@code DebugLib.globals} field — which
     * is never initialized here (this watchdog is <i>assigned</i>, not {@code load()}-ed) and would
     * NPE. Since the no-op {@code onCall}/{@code onReturn} keep no call-stack, an empty traceback is
     * also the honest answer; addon errors are still reported by the engine with the LuaJ
     * {@code chunkname:line} prefix carried on the message.
     */
    static final class Watchdog extends DebugLib {
        /** Instructions left in the entry the UI thread is inside; touched by {@link Sandbox#uiThread()} only. */
        private long uiRem = Long.MAX_VALUE;
        /** One holder per other thread currently inside this environment's Lua — empty almost always. */
        private final ConcurrentHashMap<Thread, long[]> off = new ConcurrentHashMap<Thread, long[]>();

        public void onInstruction(int pc, Varargs v, int top) {
            Thread cur = Thread.currentThread();
            if(cur == uiThread()) {
                if(--uiRem < 0) {
                    uiRem = Long.MAX_VALUE;   // avoid re-throwing on every subsequent instruction
                    throw new LuaError(over());
                }
            } else {
                long[] slot = off.get(cur);
                if(slot == null)
                    return;                   // Lua on a thread that armed nothing: nothing to spend
                if(--slot[0] < 0) {
                    slot[0] = Long.MAX_VALUE;
                    throw new LuaError(over());
                }
            }
        }

        /** Claim this thread's budget, handing back whatever it displaced. @see Sandbox#arm(Globals) */
        long arm() {
            long full = (INSN_CAP > 0) ? INSN_CAP : Long.MAX_VALUE;
            Thread cur = Thread.currentThread();
            if(cur == uiThread()) {
                long prev = uiRem;
                uiRem = full;
                return prev;
            }
            long[] slot = off.get(cur);
            if(slot == null) {
                off.put(cur, new long[] {full});
                return UNARMED;               // this thread's outermost entry: the disarm drops the holder
            }
            long prev = slot[0];
            slot[0] = full;
            return prev;
        }

        /** Release the claim {@link #arm()} made on this thread. @see Sandbox#disarm(Globals, long) */
        void disarm(long armed) {
            long[] slot = off.get(Thread.currentThread());
            if(slot == null) {
                uiRem = (armed == UNARMED) ? Long.MAX_VALUE : armed;
                return;
            }
            if(armed == UNARMED)
                off.remove(Thread.currentThread());
            else
                slot[0] = armed;
        }

        private static String over() {
            return "addon watchdog: instruction budget exceeded (>" + INSN_CAP
                + " instructions in one call — possible infinite loop)";
        }

        public void onCall(LuaFunction f) {}
        public void onCall(LuaClosure c, Varargs varargs, LuaValue[] stack) {}
        public void onReturn() {}
        public String traceback(int level) { return ""; }
    }
}
