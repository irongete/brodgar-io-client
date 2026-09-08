package io.brodgar.addon;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;

/**
 * The <b>nil discipline</b> of the uniform grammar (spec {@code 039-uniform-api} §2.9): <b>an explicit
 * {@code nil} argument is an ERROR unless the verb documents a meaning for it</b>. The meanings documented in
 * the API are <i>undo your layer</i> ({@code w:position(nil)}, {@code w:size(nil)}, {@code w:text(nil)},
 * {@code w:title(nil)}) and <i>none</i> ({@code ov:tint(nil)}) — and everywhere else a {@code nil} is an
 * accident with nothing to undo. A verb whose {@code nil} means something reads its own argument (the
 * {@link #passed} arity test) rather than calling {@link #written}, which is what refuses one.
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
 * <p><b>And the type discipline beside it.</b> {@link #str} and {@link #num} are the API's only two type
 * assertions, and they ask {@link LuaValue#type()} rather than {@code isstring()}/{@code isnumber()} for the
 * reason written on {@link #str}: in LuaJ those two predicates coerce, so the laxer test lets a number
 * through as a name and the defensive one refuses a perfectly ordinary string. Stating the rule once is what
 * keeps the twelve hand-rolled variants of it from disagreeing.
 *
 * <p><b>And the VALUE discipline the type one is only half of.</b> {@code 0/0} and {@code math.huge} are
 * numbers by type, so a test that asks the type alone takes them — and every range check written after one
 * is <b>false for NaN</b>, both comparisons at once, which is how a volume, a fraction, a radius and a
 * timer's delay each passed their own bounds and reached the mixer, the GPU, the preference file and the
 * clock. So {@link #num} asks the value too: a number here is <b>finite</b>. And where the thing named is an
 * INDEX, an ID, a COUNT or a number of PIXELS, {@link #integer} asks the other half — LuaJ's {@code toint()}
 * truncates through {@code long} with nothing said ({@code 2.7} is {@code 2}, {@code 1e10} is
 * {@code 1410065408} and {@code math.huge} is {@code -1}), so a range test written after one never sees what
 * the caller wrote. {@link #optnum} is the optional twin of {@link #num}, as {@link #optint} is of
 * {@link #integer}.
 *
 * <p><b>And the optional twins, {@link #optnum} and {@link #optint}, are part of that discipline rather
 * than a convenience.</b> An argument a verb can do without is still an argument whose type and value are
 * checked, and an options-table field is such an argument: LuaJ's {@code optint} and
 * {@code optdouble} coerce a numeric string and fall through to a raw <i>bad argument</i> on anything else,
 * so a verb that reaches for them hands both mistakes back at once. Where a helper here does not cover a
 * shape, that is the thing to fix — an optional argument left to LuaJ is how a swept file stays unswept.
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

    /**
     * A <b>required string</b> argument, and the one place the API states LuaJ's coercion rule.
     *
     * <p><b>In LuaJ a number IS a string and a numeric string IS a number.</b> {@code isstring()} answers
     * {@code true} for {@code 42} and {@code isnumber()} answers {@code true} for {@code "42"} — they are
     * arithmetic-coercion predicates, not type tests. So a hand-rolled {@code !v.isstring()} lets a number
     * through as a name, and the defensive {@code !v.isstring() || v.isnumber()} refuses {@code "061.8"},
     * which is an ordinary string. Both mistakes were shipped, in opposite directions, and both are the same
     * mistake: the question here is the <b>type</b>, so the type is what is asked.
     *
     * <p>{@code hint} is the half of the sentence only the call site knows — <i>the other player's hearth
     * secret</i> — or {@code null} where the parameter name says it all.
     */
    static LuaValue str(Varargs a, int i, String verb, String param, String hint) {
        return str(required(a, i, verb, param), verb, param, hint);
    }

    /** {@link #str(Varargs, int, String, String, String)} over a value already in hand (a table field, a
     *  {@link #written} result, a control's {@code value(v)}). */
    static LuaValue str(LuaValue v, String verb, String param, String hint) {
        if(v.type() != LuaValue.TSTRING)
            throw new LuaError(verb + ": " + param + " must be a string" + hint(hint) + ", got " + v.typename()
                + ((v.type() == LuaValue.TNUMBER) ? NUMBER_IS_NOT : ""));
        return v;
    }

    /** A <b>required number</b> argument, by type and by value — the other half of {@link #str}, same reason. */
    static LuaValue num(Varargs a, int i, String verb, String param, String hint) {
        return num(required(a, i, verb, param), verb, param, hint);
    }

    /**
     * {@link #num(Varargs, int, String, String, String)} over a value already in hand.
     *
     * <p><b>Finite, and that is not a second check but the same one.</b> A number an API can do something
     * with is a number a comparison can order: {@code NaN < 0} and {@code NaN > 1} are <i>both</i> false, so
     * every {@code 0..1} guard in the bridge waved it through, and {@code math.huge} divides into a zero and
     * multiplies into a matrix with no inverse. Refused here rather than at each of them, because the verb
     * that took it is the only place that still knows the caller's own spelling.
     */
    static LuaValue num(LuaValue v, String verb, String param, String hint) {
        if(v.type() != LuaValue.TNUMBER)
            throw new LuaError(verb + ": " + param + " must be a number" + hint(hint) + ", got " + v.typename()
                + ((v.type() == LuaValue.TSTRING) ? STRING_IS_NOT : ""));
        double d = v.todouble();
        if(!Double.isFinite(d))
            throw new LuaError(verb + ": " + param + " must be a finite number" + hint(hint) + ", got "
                + show(d));
        return v;
    }

    /**
     * A <b>required whole number</b> argument — an index, an id, a count, a number of design pixels. The
     * value {@link #num} passes, plus the two things a cast does silently and this refuses by name.
     *
     * <p><b>Why a cast is not a check.</b> LuaJ's {@code toint()} is {@code (int)(long)d}: it truncates
     * {@code 2.7} to {@code 2}, wraps {@code 1e10} to {@code 1410065408} and answers {@code -1} for
     * {@code math.huge}. Every one of those is a number a range test written afterwards then approves,
     * because the test never sees what the caller wrote — which is how {@code s:speed():set(2.7)} chose
     * speed 2, a glow took a radius of 1.4 billion design pixels, and a fractional gob id minted the handle
     * for its neighbour. The API's rule is that an index is a whole number, so that is what is asked, once,
     * here.
     */
    static int integer(Varargs a, int i, String verb, String param, String hint) {
        return integer(required(a, i, verb, param), verb, param, hint);
    }

    /** {@link #integer(Varargs, int, String, String, String)} over a value already in hand (a table field,
     *  a {@link #written} result, a collection key). */
    static int integer(LuaValue v, String verb, String param, String hint) {
        return (int)integer(v, verb, param, hint, Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    /**
     * {@link #integer} for a whole number too wide for an {@code int} — a gob id is the server's own
     * unsigned 32-bit number, and a party member is addressed by one. {@code lo}/{@code hi} are the range
     * the receiver can actually hold; past {@code 2^53} a Lua number no longer names one integer, which is
     * what {@link #EXACT} bounds an id door by.
     */
    static long integer(LuaValue v, String verb, String param, String hint, long lo, long hi) {
        double d = num(v, verb, param, hint).todouble();
        if(d != Math.floor(d))
            throw new LuaError(verb + ": " + param + " must be a whole number" + hint(hint) + ", got "
                + show(d));
        if((d < lo) || (d > hi))
            throw new LuaError(verb + ": " + param + " must be a whole number" + hint(hint) + " from " + lo
                + " to " + hi + ", got " + show(d));
        return (long)d;
    }

    /** The widest whole number a Lua number names exactly ({@code 2^53}) — the bound of an id door. */
    static final long EXACT = 9007199254740992L;

    /**
     * An <b>optional number</b>: the caller's finite number, or {@code def} when they passed nothing at all.
     * The twin of {@link #num} for the argument a verb can do without — a volume, a line's width, an angle.
     * An explicit {@code nil} in a slot the caller passed is refused by {@link #written}, like any other.
     */
    static double optnum(Varargs a, int i, String verb, String param, String hint, double def) {
        LuaValue v = written(a, i, verb, param);
        return (v == null) ? def : num(v, verb, param, hint).todouble();
    }

    /**
     * {@link #optnum(Varargs, int, String, String, String, double)} over a value already in hand — an
     * options-table field, which is absent as a {@code nil} rather than as a missing slot. <b>Absent is the
     * default; present and not a finite number is a refusal</b>, because a field spelled wrong that silently
     * became the default is the write nobody made, which is the whole argument {@link #written} rests on.
     */
    static double optnum(LuaValue v, String verb, String param, String hint, double def) {
        return v.isnil() ? def : num(v, verb, param, hint).todouble();
    }

    /**
     * An <b>optional whole number</b> argument: the caller's number, or {@code def} when they passed nothing
     * at all. The twin of {@link #integer} for the argument a verb can do without — a modifier bitfield, a
     * button, a count.
     *
     * <p><b>Why an optional argument needs a helper of its own.</b> LuaJ's {@code optint}/{@code optdouble}
     * do two wrong things in one call. A value of the wrong kind falls through to {@code checkint()} and
     * surfaces as LuaJ's own <i>bad argument: number expected, got table</i>, which names neither the verb
     * nor the parameter — the message a caller reads at the worst moment. And a numeric <b>string</b> scans
     * as a number, so {@code place(p, 0, "1", "0")} is taken and <b>sent</b>. Those are the two mistakes
     * {@link #num} exists to refuse, and an argument being optional changes neither of them.
     *
     * <p><b>Omitted and explicitly {@code nil} are not the same thing</b>, here as everywhere else: an
     * absent argument is the default, and a {@code nil} in a slot the caller passed is refused by
     * {@link #written} like any other. {@code place(p, ang, nil, 0)} passes a fourth argument and so passes
     * a third, which is exactly the accident this refuses.
     */
    static int optint(Varargs a, int i, String verb, String param, String hint, int def) {
        LuaValue v = written(a, i, verb, param);
        return (v == null) ? def : integer(v, verb, param, hint);
    }

    /** What the call site knows and the parameter name does not, in parentheses, or nothing. */
    private static String hint(String hint) {
        return (hint == null) ? "" : (" (" + hint + ")");
    }

    /**
     * The number as the caller wrote it, for a refusal. {@code Double.toString} spells a whole number
     * {@code 12.0} and a big one {@code 1.0E10}, neither of which is what was typed; and the two values this
     * class exists to refuse have no decimal spelling at all, so they are named.
     */
    private static String show(double d) {
        if(Double.isNaN(d))
            return "nan";
        if(Double.isInfinite(d))
            return (d > 0) ? "inf" : "-inf";
        if((d == Math.floor(d)) && (Math.abs(d) < 1e15))
            return Long.toString((long)d);
        return Double.toString(d);
    }

    private static final String NUMBER_IS_NOT =
        " — a number is not a string here, whatever Lua does with it in a concatenation; tostring(n) is the"
        + " conversion if that is what you meant";
    private static final String STRING_IS_NOT =
        " — a string that merely scans as a number is still a string; tonumber(s) is the conversion if that"
        + " is what you meant";

    /** The refusal itself, worded so the reader knows both what went wrong and what the read arity is. */
    static LuaError nilRefused(String verb, String param) {
        return new LuaError(verb + ": " + param + " must not be nil — arity is the verb here, so call it"
            + " with no argument to read. Test the variable before passing it.");
    }
}
