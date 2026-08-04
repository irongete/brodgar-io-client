package io.brodgar.addon;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;

/**
 * The <b>nil discipline</b> of the uniform grammar (spec {@code 039-uniform-api} §2.9): <b>an explicit
 * {@code nil} argument is an ERROR unless the verb documents a meaning for it</b>. Two meanings are
 * documented in the whole API — <i>undo your layer</i> ({@code w:position(nil)}) and <i>none</i>
 * ({@code ov:tint(nil)}) — and everywhere else a {@code nil} is an accident with nothing to undo.
 *
 * <p><b>Why it earns its keep.</b> Arity is the verb: {@code x:name()} reads and {@code x:name(v)} writes.
 * So a {@code v} that is accidentally {@code nil} does not fail — it silently becomes a <i>read</i>, and the
 * write nobody made is a bug with no symptom. Refusing it costs nothing on a property that has no undo and
 * buys the loudest possible failure at the call site that caused it.
 *
 * <p><b>The bridge separates the two cases with {@link Varargs#narg()}, and the separation is exact for a
 * direct argument.</b> {@code f()} arrives as {@code narg == 0} and {@code f(x)} with {@code x == nil} as
 * {@code narg == 1} — a Lua caller writing {@code f(x)} genuinely passes one argument whatever its value, and
 * a table field ({@code f(cfg.enabled)}) is such an argument. That covers both hazards measured in shipped
 * code.
 *
 * <p><b>The one hole, documented rather than hidden.</b> {@code f(g())} where {@code g} returns <i>nothing</i>
 * arrives as {@code narg == 0}, indistinguishable from {@code f()}, so it is treated as a read. {@code g}
 * returning an explicit {@code nil} arrives as {@code narg == 1} and is refused like any other nil. The
 * conventions page states this and the suite asserts it, because a limit that is written down is a contract
 * and a limit that is not is a defect waiting to be discovered.
 *
 * <p>Index conventions: on a colon call the receiver is argument 1, so a verb's first real argument is 2; on
 * a {@code __call} metamethod the callable table itself is argument 1, so the same holds.
 */
final class Args {
    private Args() {
    }

    /** Did the caller actually pass argument {@code i}? ({@code false} = the read arity.) */
    static boolean passed(Varargs a, int i) {
        return a.narg() >= i;
    }

    /**
     * A <b>required</b> argument: absent or explicitly {@code nil} is an error naming the verb and the
     * parameter. For a verb that takes a value and has no read arity ({@code hafen.log():write(msg)}).
     */
    static LuaValue required(Varargs a, int i, String verb, String param) {
        if(!passed(a, i))
            throw new LuaError(verb + ": " + param + " is required");
        LuaValue v = a.arg(i);
        if(v.isnil())
            throw nilRefused(verb, param);
        return v;
    }

    /**
     * The value of a <b>write</b> whose read is the same name with no argument: {@code null} when the caller
     * passed nothing (so the caller answers the read), the value when they passed one, and a {@link LuaError}
     * when they passed an explicit {@code nil}.
     */
    static LuaValue written(Varargs a, int i, String verb, String param) {
        if(!passed(a, i))
            return null;                       // the read arity
        LuaValue v = a.arg(i);
        if(v.isnil())
            throw nilRefused(verb, param);
        return v;
    }

    /** The refusal itself, worded so the reader knows both what went wrong and what the read arity is. */
    static LuaError nilRefused(String verb, String param) {
        return new LuaError(verb + ": " + param + " must not be nil — arity is the verb here, so call it"
            + " with no argument to read. Test the variable before passing it.");
    }
}
