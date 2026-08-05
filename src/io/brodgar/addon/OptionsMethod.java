package io.brodgar.addon;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;

/**
 * One read/write option method (spec 018-client-options). The arity IS the verb: {@code opt:name()} reads and
 * answers the value, {@code opt:name(v)} writes and answers the subsystem handle so writes chain
 * ({@code video:shadows(true):vsync(false)}). This is why every option is a single name rather than a
 * {@code get}/{@code set} pair — one canonical way per operation.
 *
 * <p>Handed a colon call, {@code arg(1)} is the receiver and the value (if any) is {@code arg(2)}; a dot call
 * would shift both, so a wrong-arity call is an error rather than a silently misread write.
 *
 * <p><b>An explicit {@code nil} is refused</b> (§2.9). Arity is the verb here, so {@code opt:name(v)} with a
 * {@code v} that is accidentally {@code nil} does not fail — it becomes a <i>read</i>, and the write nobody
 * made is a bug with no symptom. It is one of the two silent no-ops measured in shipped code, and an option
 * has no undo for the refusal to cost anything against.
 */
abstract class OptionsMethod extends VarArgFunction {
    /** The subsystem handle a write answers with (the chaining target). */
    private final LuaValue handle;
    /** How this option is spelled at the call site ({@code "interface:scale"}), for the refusals. */
    private final String verb;

    OptionsMethod(LuaValue handle, String verb) {
        this.handle = handle;
        this.verb = verb;
    }

    public Varargs invoke(Varargs a) {
        switch(a.narg()) {
        case 1:                       // opt:name()   — read
            return onRead();
        case 2:                       // opt:name(v)  — write, then chain
            if(a.arg(2).isnil())
                throw Args.nilRefused(verb, "value");
            onWrite(a.arg(2));
            return handle;
        default:
            throw new LuaError(verb + ": an option is read as opt:name() and written as opt:name(value)"
                               + " — use a COLON call with at most one argument");
        }
    }

    /** The current value, or {@code nil} when the backing subsystem is not up yet. */
    protected abstract LuaValue onRead();

    /** Apply {@code value} to the client's own preference store — the same one the Options window writes. */
    protected abstract void onWrite(LuaValue value);
}
