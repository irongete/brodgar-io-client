package io.brodgar.addon;

import haven.UI;
import haven.Widget;

import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.TwoArgFunction;
import org.luaj.vm2.lib.ZeroArgFunction;

/**
 * A <b>message hook</b> (Level 3, spec {@code 13-hooks-and-interception.md} §L3) — the Java half of
 * {@code hafen.hook.message(msg, fn)}, Phase 2e. It is the inbound mirror of the L2 action hook
 * ({@link LuaActionHook}): server&rarr;client UI updates all funnel through {@link UI#uimsg}, which queues a
 * {@code UiMessage} that (on a Loader thread, under {@code synchronized(ui)}) applies the update to the target
 * widget. The one core edit in {@code UI.UiMessage.run} calls {@link AddonManager#onMessage} <b>before</b> the
 * widget applies the message, so a hook can <b>suppress</b> the update or <b>rewrite</b> its arguments.
 *
 * <p>The {@code ev} handed to {@code fn(ev)}:
 * <ul>
 *   <li>{@code ev.msg} — the message name (string; e.g. {@code "set"}, {@code "food"}).</li>
 *   <li>{@code ev.target} — the receiving widget's class simple name (string; e.g. {@code "IMeter"}).</li>
 *   <li>{@code ev.args} — a 1-based snapshot of the arguments (Java&harr;Lua via {@link LuaMarshal}). Read it
 *       to inspect the update; to change it, build new args and call {@code ev:rewrite}.</li>
 *   <li>{@code ev:preventDefault()} — swallow the update; the widget never applies it (and, because the
 *       widget did not change, the widget-tree read tap does not fire either).</li>
 *   <li>{@code ev:rewrite(argsTable)} — apply the message with a new argument table (converted Lua&rarr;Java)
 *       instead of the original. The message name is unchanged (rewriting the name is deferred — see the doc).
 *       Unlike {@code preventDefault} it does <b>not</b> swallow: the (rewritten) update is still applied.</li>
 * </ul>
 *
 * <p>Precedence when several hooks match one message: {@code preventDefault} wins (a suppressed message is
 * never applied, whatever any hook rewrote); otherwise the last {@code rewrite} wins. The Lua call goes
 * through {@link AddonManager#callLua} — watchdog-armed (D-018), error-isolated, CPU-accounted. The addon only
 * ever sees an opaque {@code :remove()} handle; the bridge owns the hook and unregisters it on reload/disable
 * (principle P2). The {@link #alive} flag makes a dispatch that races teardown a no-op.
 *
 * <p><b>Threading.</b> Unlike L1/L2 (UI thread), an L3 hook runs on a <b>Loader thread</b> — but always inside
 * the {@code synchronized(ui)} block of {@code UiMessage.run}, the same monitor the tick and draw hold, so the
 * hook Lua cannot race any other Lua. Keep handlers light: they run inline with server-message application and
 * hold the UI monitor while doing so (the spec's non-blocking caveat; the instruction watchdog still bounds a
 * single runaway).
 */
public final class LuaMessageHook {
    final Addon owner;
    final String msg;      // the message name this hook matches (exact); also the key in the dispatch map
    final LuaValue fn;     // the Lua handler fn(ev)
    boolean alive = true;

    LuaMessageHook(Addon owner, String msg, LuaValue fn) {
        this.owner = owner;
        this.msg = msg;
        this.fn = fn;
    }

    /**
     * Build the {@code ev} for one inbound message and run this hook's {@code fn(ev)}. Called by
     * {@link AddonManager#onMessage} from inside {@code UiMessage.run}'s {@code synchronized(ui)} block.
     * {@code prevented}/{@code rewritten} are shared across every hook matching this one message:
     * {@code preventDefault} sets {@code prevented[0]}, {@code rewrite(t)} sets {@code rewritten[0]} to the new
     * Java args, and the caller decides what to apply (suppress if prevented, else the rewritten args if any,
     * else the originals).
     */
    void invoke(final Widget target, final String message, final Object[] origArgs,
                final boolean[] prevented, final Object[][] rewritten) {
        LuaTable ev = new LuaTable();
        ev.set("msg", LuaValue.valueOf(message));
        ev.set("target", LuaValue.valueOf(target.getClass().getSimpleName()));
        ev.set("args", LuaMarshal.argsToLua(origArgs));
        ev.set("preventDefault", new ZeroArgFunction() {
            public LuaValue call() {
                prevented[0] = true;
                return LuaValue.NIL;
            }
        });
        ev.set("rewrite", new TwoArgFunction() {          // ev:rewrite(t) — colon-call: self=arg1, the table=arg2
            public LuaValue call(LuaValue self, LuaValue nargs) {
                rewritten[0] = LuaMarshal.luaToArgs(nargs, "hafen.hook.message ev:rewrite");
                return LuaValue.NIL;
            }
        });
        AddonManager.callLua(owner, Addon.C_HOOK, fn, ev);
    }
}
