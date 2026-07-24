package io.brodgar.addon;

import haven.Coord;
import haven.UI;
import haven.Widget;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaInteger;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.TwoArgFunction;
import org.luaj.vm2.lib.ZeroArgFunction;

/**
 * An <b>action hook</b> (Level 2, spec {@code 13-hooks-and-interception.md} §L2) — the Java half of
 * {@code hafen.hook.action(msg, fn)}, Phase 2d. Every player action is a {@link Widget#wdgmsg} that funnels
 * through the single outbound choke point {@link UI#wdgmsg(Widget, String, Object...)}; the one core edit
 * there calls {@link AddonManager#onWdgmsg}, which builds an {@code ev} for each registered hook whose
 * {@code msg} matches and runs it <b>before</b> the message reaches the server. Unlike an L1 input hook
 * ({@link LuaInputHook}), the arguments here are already <b>fully resolved</b> — for a MapView move-click
 * ({@code "click"}) that means the destination world coordinate and any clicked gob, which do not exist yet
 * at {@code mousedown} time.
 *
 * <p>The {@code ev} handed to {@code fn(ev)}:
 * <ul>
 *   <li>{@code ev.msg} — the action name (string).</li>
 *   <li>{@code ev.sender} — the sending widget's class simple name (string; e.g. {@code "MapView"}).</li>
 *   <li>{@code ev.args} — a 1-based snapshot of the arguments. {@link Coord} becomes a {@code {x=,y=}} table,
 *       numbers/strings/booleans map directly, and any other Java object is an opaque value that round-trips
 *       unchanged. Read it to inspect the action; to change it, build new args and call {@code ev:send}.</li>
 *   <li>{@code ev:preventDefault()} — do not send the action to the server.</li>
 *   <li>{@code ev:resend()} — send the action now with the <b>original</b> arguments (verbatim, lossless),
 *       bypassing the hook chain; implies {@code preventDefault}. Use it to re-issue an intercepted action
 *       (optionally later, from a timer) — the spec's <i>"do something intermediate, then move"</i> case.</li>
 *   <li>{@code ev:send(argsTable)} — send the action now with a new argument table (converted Lua&rarr;Java),
 *       bypassing the hook chain; implies {@code preventDefault}.</li>
 * </ul>
 *
 * <p>{@code resend}/{@code send} deliberately bypass the hook chain (they call {@link UI#rawWdgmsg}, not
 * {@link UI#wdgmsg}) so a hook that re-issues its own action cannot loop (the spec's re-entrancy caveat). The
 * Lua call goes through {@link AddonManager#callLua} — watchdog-armed (D-018), error-isolated, CPU-accounted.
 * The addon only ever sees an opaque {@code :remove()} handle; the bridge owns the hook and unregisters it on
 * reload/disable (principle P2). The {@link #alive} flag makes a dispatch that races teardown a no-op.
 */
public final class LuaActionHook {
    final Addon owner;
    final String msg;      // the action name this hook matches (exact); also the key in the dispatch map
    final LuaValue fn;     // the Lua handler fn(ev)
    boolean alive = true;

    LuaActionHook(Addon owner, String msg, LuaValue fn) {
        this.owner = owner;
        this.msg = msg;
        this.fn = fn;
    }

    /**
     * Build the {@code ev} for one outbound action and run this hook's {@code fn(ev)}. Called by
     * {@link AddonManager#onWdgmsg} on the UI-locked send path (so the Lua is serialized with tick/draw).
     * {@code prevented} is shared across every hook matching this one wdgmsg — {@code preventDefault}/
     * {@code resend}/{@code send} all set it, and the caller suppresses the default send if it ends up true.
     * {@code u} is the live UI, captured so a deferred {@code resend}/{@code send} (e.g. from a timer) still
     * reaches the server.
     */
    void invoke(final Widget sender, final String message, final Object[] origArgs,
                final boolean[] prevented, final UI u) {
        LuaTable ev = new LuaTable();
        ev.set("msg", LuaValue.valueOf(message));
        ev.set("sender", LuaValue.valueOf(sender.getClass().getSimpleName()));
        ev.set("args", argsToLua(origArgs));
        ev.set("preventDefault", new ZeroArgFunction() {
            public LuaValue call() {
                prevented[0] = true;
                return LuaValue.NIL;
            }
        });
        ev.set("resend", new ZeroArgFunction() {
            public LuaValue call() {
                prevented[0] = true;
                u.rawWdgmsg(sender, message, origArgs);   // original args, verbatim — bypasses the hook chain
                return LuaValue.NIL;
            }
        });
        ev.set("send", new TwoArgFunction() {             // ev:send(t) — colon-call: self=arg1, the table=arg2
            public LuaValue call(LuaValue self, LuaValue nargs) {
                prevented[0] = true;
                u.rawWdgmsg(sender, message, luaToArgs(nargs));   // new args — bypasses the hook chain
                return LuaValue.NIL;
            }
        });
        AddonManager.callLua(owner, fn, ev);
    }

    // -------------------------------------------------------------------- arg marshalling (Java <-> Lua)

    /** A 1-based Lua snapshot of the Java argument array (see {@link #toLua}). */
    static LuaTable argsToLua(Object[] args) {
        LuaTable t = new LuaTable();
        if(args != null) {
            for(int i = 0; i < args.length; i++)
                t.set(i + 1, toLua(args[i]));
        }
        return t;
    }

    /**
     * Convert one wdgmsg argument to Lua. {@link Coord} &rarr; a {@code {x=,y=}} table; primitives map
     * directly (a {@code Long} becomes a double, so ids &gt; 2^53 lose precision — the same caveat as gob ids
     * elsewhere); anything else becomes an opaque userdata that {@link #toJava} maps straight back to the
     * identical object, so exotic args survive a {@code resend}/{@code send} untouched.
     */
    static LuaValue toLua(Object o) {
        if(o == null)
            return LuaValue.NIL;
        if(o instanceof Boolean)
            return LuaValue.valueOf(((Boolean)o).booleanValue());
        if((o instanceof Integer) || (o instanceof Short) || (o instanceof Byte))
            return LuaValue.valueOf(((Number)o).intValue());
        if(o instanceof Number)                       // Long/Double/Float — Lua numbers are doubles
            return LuaValue.valueOf(((Number)o).doubleValue());
        if(o instanceof String)
            return LuaValue.valueOf((String)o);
        if(o instanceof Coord) {
            Coord c = (Coord)o;
            LuaTable t = new LuaTable();
            t.set("x", LuaValue.valueOf(c.x));
            t.set("y", LuaValue.valueOf(c.y));
            return t;
        }
        return LuaValue.userdataOf(o);                // opaque round-trip
    }

    /** Convert a Lua {@code ev:send} argument table back to a Java {@code Object[]} (see {@link #toJava}). */
    static Object[] luaToArgs(LuaValue t) {
        if(!t.istable())
            throw new LuaError("hafen.hook.action ev:send(args) expects a table");
        int n = t.length();
        Object[] out = new Object[n];
        for(int i = 0; i < n; i++)
            out[i] = toJava(t.get(i + 1));
        return out;
    }

    /** Inverse of {@link #toLua}: a {@code {x=,y=}} table &rarr; {@link Coord}, userdata &rarr; its object. */
    static Object toJava(LuaValue v) {
        switch(v.type()) {
        case LuaValue.TNIL:
            return null;
        case LuaValue.TBOOLEAN:
            return Boolean.valueOf(v.toboolean());
        case LuaValue.TNUMBER:
            return (v instanceof LuaInteger) ? (Object)Integer.valueOf(v.toint())
                                             : (Object)Double.valueOf(v.todouble());
        case LuaValue.TSTRING:
            return v.tojstring();
        case LuaValue.TUSERDATA:
            return v.touserdata();
        case LuaValue.TTABLE: {
            LuaValue x = v.get("x"), y = v.get("y");
            if(x.isnumber() && y.isnumber())
                return new Coord(x.toint(), y.toint());
            throw new LuaError("hafen.hook.action ev:send: a table argument must be a coord {x=,y=}");
        }
        default:
            throw new LuaError("hafen.hook.action ev:send: unsupported argument type " + v.typename());
        }
    }
}
