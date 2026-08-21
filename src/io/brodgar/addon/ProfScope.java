package io.brodgar.addon;

import io.brodgar.prof.Prof;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;

/**
 * {@code p:scope(name)} (spec 019-profiling, task 019.4) — a named marker an addon brackets its own code with,
 * the {@code ProfilerMarker} equivalent. The measured time shows up under that addon's row in
 * {@code p:addons()}.
 *
 * <pre>
 *   local s = p:scope("scan-gobs")
 *   s:begin(); heavy_work(); s:finish()
 *   p:measure("scan-gobs", heavy_work)     -- the wrapper form, which cannot forget to finish
 * </pre>
 *
 * <p><b>Owned by the calling addon.</b> The accounting lives in a map on the {@link Addon} itself, so names
 * are namespaced per addon (two addons may both use {@code "update"}), and a {@code :reload}/disable drops
 * the whole map with the addon — there is nothing to tear down and nothing to leak.
 *
 * <p><b>Off ⇒ nothing.</b> Both verbs return on the {@link Prof#on} branch before touching anything, so code
 * left instrumented in a shipped addon costs a static field read per call. The {@code Scope} record itself is
 * created <b>lazily</b>, on the first {@code begin()} that runs while armed, so an addon that only ever runs
 * disarmed allocates no accounting at all.
 *
 * <p><b>Why the handle is real even when profiling is off</b> (rather than a shared no-op singleton): every
 * other handle in {@code hafen.*} is a stateless proxy that an addon may stash forever, and a no-op singleton
 * would break exactly that — a scope taken before the checkbox is ticked would stay dead for the rest of the
 * session. The handle here binds only {@code (owner, name)} and re-checks the switch on each call, so it costs
 * the same branch and keeps working when the switch flips.
 *
 * <p><b>Re-entrancy.</b> Only the outermost {@code begin}/{@code finish} pair counts, so a recursive section
 * is not charged several times over; a {@code finish()} with no open {@code begin()} is ignored, and a scope
 * left open by an erroring handler is closed at the end of the frame rather than staying open forever.
 */
public final class ProfScope {
    private ProfScope() {}

    /**
     * Build the Lua handle for {@code owner}'s scope {@code nm}.
     *
     * <p>The parameter is {@code nm} and not {@code name} <b>deliberately</b>: LuaJ's {@code LibFunction} — the
     * supertype of every {@code VarArgFunction} below — declares a {@code protected String name} field, and
     * inside an anonymous subclass an inherited field <i>shadows</i> a captured local of the same name. Written
     * as {@code name}, these closures silently read LuaJ's null field instead of the scope's name, and the
     * scope ends up registered under a null key. Every bridge closure in this package that needs a name in its
     * body must avoid that identifier.
     */
    static LuaValue create(final Addon owner, final String nm) {
        // Userdata, like every handle in the API: a typo raises naming the three verbs, s.finish = nil is
        // refused, and tostring(s) names the scope rather than printing table: 0x...
        LuaValue s = LuaValue.userdataOf(new Mark(nm));
        LuaTable m = new LuaTable();
        m.set("begin", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                begin(owner, nm);
                return a.arg1();          // chains, like every other verb in hafen.*
            }
        });
        m.set("finish", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                finish(owner, nm);
                return a.arg1();
            }
        });
        m.set("name", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                return LuaValue.valueOf(nm);
            }
        });
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, Retired.closedIndex("scope", m,
            "a scope is a named marker around your own code: it answers :begin() :finish() and :name()"));
        mt.set("__name", LuaValue.valueOf("ProfScope"));
        mt.set("__tostring", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                return LuaValue.valueOf("ProfScope(" + nm + ")");
            }
        });
        s.setmetatable(mt);
        return s;
    }

    /** The opaque instance behind a scope userdata (facade-safe: no Java object of the client's crosses).
     *  A scope binds only {@code (owner, name)} and the closures carry the owner, so the name is all of it. */
    private static final class Mark {
        private final String nm;

        Mark(String nm) {
            this.nm = nm;
        }

        public String toString() {
            return "ProfScope(" + nm + ")";
        }
    }

    /** Open the scope (no-op when disarmed; only the outermost open starts the clock). */
    static void begin(Addon owner, String name) {
        if(!Prof.on)
            return;
        Addon.Scope s = owner.scope(name);
        if(s.depth++ == 0)
            s.t0 = System.nanoTime();
    }

    /** Close the scope and charge the elapsed time (no-op when disarmed or when nothing is open). */
    static void finish(Addon owner, String name) {
        if(!Prof.on)
            return;
        Addon.Scope s = owner.scopes.get(name);
        if((s == null) || (s.depth == 0))
            return;                       // an unmatched finish(), or the switch was armed mid-section
        if(--s.depth == 0) {
            s.nanos += System.nanoTime() - s.t0;
            s.calls++;
        }
    }

    /**
     * {@code p:measure(name, fn, ...)} — the wrapper form: runs {@code fn} with the extra arguments and
     * returns whatever it returns, bracketed by the scope. {@code fn} runs whether profiling is armed or not
     * (an addon must never behave differently because the checkbox is off), and the scope is closed in a
     * {@code finally}, so a Lua error inside {@code fn} still leaves the accounting balanced before it
     * propagates to the caller's own error isolation.
     */
    static Varargs measure(Addon owner, String name, LuaValue fn, Varargs args) {
        if(!Prof.on)
            return fn.invoke(args);
        begin(owner, name);
        try {
            return fn.invoke(args);
        } finally {
            finish(owner, name);
        }
    }
}
