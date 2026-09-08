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
 * <p><b>An argument is checked before the subsystem is looked up</b>, so a bad write is refused whether or
 * not the client's UI is up yet. The alternative is a refusal that depends on when you called it, which is
 * the one thing a type error must never be.
 *
 * <p><b>An explicit {@code nil} is refused</b> (§2.9). Arity is the verb here, so {@code opt:name(v)} with a
 * {@code v} that is accidentally {@code nil} does not fail — it becomes a <i>read</i>, and the write nobody
 * made is a bug with no symptom. It is one of the two silent no-ops measured in shipped code, and an option
 * has no undo for the refusal to cost anything against.
 *
 * <p><b>Every WRITE is protected, and this is the one place it is gated</b> (093.3, A-096's sibling A-097).
 * {@code client.settings} covers all of them, because all of them persist to the user's own preference
 * stores — {@code client/README.md} says so itself: "indistinguishable from the same edit made in the
 * Options window". The old reading was that these are client-local and so need no key, and the examples
 * {@code conventions.md} gave for that rule are a map marker, an icon flag and a sound. Halving someone's
 * render scale, muting their audio and rebinding every hotkey they have is a different order of thing, it
 * persists, and the consent dialog said nothing about any of it. The reads stay unprotected.
 *
 * <p>The gate is the FIRST thing the write branch does (D-213) — before the {@code nil} refusal, before the
 * type check and before the subsystem is looked up — so an addon that declared nothing is told <i>that</i>
 * rather than being told its argument is wrong. Which arity was called is what says there is a write to gate
 * at all, so it cannot be moved above the {@code switch}.
 */
abstract class OptionsMethod extends VarArgFunction {
    /** The addon whose write this is — what {@code client.settings} is checked against (093.3). */
    private final Addon owner;
    /** The subsystem handle a write answers with (the chaining target). */
    private final LuaValue handle;
    /** How this option is spelled at the call site ({@code "interface:scale"}), for the refusals. */
    private final String verb;

    OptionsMethod(Addon owner, LuaValue handle, String verb) {
        this.owner = owner;
        this.handle = handle;
        this.verb = verb;
    }

    public Varargs invoke(Varargs a) {
        switch(a.narg()) {
        case 1:                       // opt:name()   — read
            return onRead();
        case 2:                       // opt:name(v)  — write, then chain
            AddonManager.requirePermission(AddonManager.current(), Permission.CLIENT_SETTINGS, verb);
            if(a.arg(2).isnil())
                throw Args.nilRefused(verb, "value");
            onWrite(a.arg(2));
            return handle;
        default:
            throw new LuaError(verb + ": an option is read as opt:name() and written as opt:name(value)"
                               + " — use a COLON call with at most one argument");
        }
    }

    /** How this option is spelled at the call site, for a refusal an {@link #onWrite} raises itself. */
    protected String verb() {
        return verb;
    }

    /**
     * The written value as a <b>number</b>, refused by type with this option's own spelling
     * ({@link Args#num}) — so an option and a control refuse a numeric string in the same sentence, and the
     * verb is spelled once, here, rather than again inside every {@link #onWrite}.
     */
    protected LuaValue num(LuaValue value, String param, String hint) {
        return Args.num(value, verb, param, hint);
    }

    /**
     * The written value as a <b>whole number</b> ({@link Args#integer}), with this option's own spelling —
     * for a row the client stores as an {@code int}, where {@code (int)v} would otherwise take {@code 3.7}
     * and set 3 with nothing said.
     */
    protected int integer(LuaValue value, String param, String hint) {
        return Args.integer(value, verb, param, hint);
    }

    /** The written value as a <b>string</b>, by type — the other half of {@link #num}. */
    protected LuaValue str(LuaValue value, String param, String hint) {
        return Args.str(value, verb, param, hint);
    }

    /** The current value, or {@code nil} when the backing subsystem is not up yet. */
    protected abstract LuaValue onRead();

    /** Apply {@code value} to the client's own preference store — the same one the Options window writes. */
    protected abstract void onWrite(LuaValue value);
}
