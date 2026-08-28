package io.brodgar.addon;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.VarArgFunction;

import java.util.Map;

/**
 * <b>What a request came back with</b> (095, A-117) &mdash; the {@code res} an
 * {@code req:on("done", fn)} handler is handed.
 *
 * <p><b>An object, not a plain Lua table</b>, because every other callback payload in the API is one:
 * {@code ev:x()}, {@code ev:args()}, {@code gob:name()}, {@code w:title()} are all colon verbs, and a lone
 * {@code res.status} beside them would teach the dot habit. The dot habit is a trap everywhere else &mdash; a
 * field read on any of the closed entity types hands back the <b>method</b>, so {@code if gob.name then} is
 * always true and {@code item.quantity > 5} fails as "attempt to compare function with number" a line away
 * from the mistake.
 *
 * <p><b>{@code :header(name)} is why the object earns its keep</b> rather than merely matching the grammar. A
 * raw {@code headers} map lower-cases its keys, which is a fact the reader has to carry; the verb does the
 * same case-insensitive match {@code req:header(name)} already does, and the rule disappears.
 *
 * <p><b>A value, not an entity.</b> A result is delivered once and named by nothing, so it is not interned:
 * two deliveries are two objects and there is no key for identity to buy. Immutable &mdash; the worker built
 * it and the UI thread only reads it.
 */
final class LuaHttpResult {
    private final LuaHttp.Result r;

    private LuaHttpResult(LuaHttp.Result r) {
        this.r = r;
    }

    public String toString() {
        return r.ok ? ("Result(" + r.status + ", " + r.body.length() + " bytes)")
                    : ("Result(failed: " + r.error + ")");
    }

    static LuaValue of(Addon owner, LuaHttp.Result r) {
        return (r == null) ? LuaValue.NIL : LuaValue.userdataOf(new LuaHttpResult(r), meta(owner));
    }

    static LuaHttpResult resolve(LuaValue v) {
        if((v == null) || !v.isuserdata())
            return null;
        Object o = v.touserdata();
        return (o instanceof LuaHttpResult) ? (LuaHttpResult)o : null;
    }

    private static LuaHttpResult handle(LuaValue self, String method) {
        LuaHttpResult h = resolve(self);
        if(h == null)
            throw new LuaError("res:" + method + "() — use a COLON call on the result your"
                + " req:on(\"done\", fn) handler was handed");
        return h;
    }

    private static LuaValue meta(Addon owner) {
        if(owner.httpResMeta != null)
            return owner.httpResMeta;
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, Refusal.closedIndex("res", methods(),
            "a result"));
        mt.set("__name", LuaValue.valueOf("Result"));
        mt.set("__tostring", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaHttpResult h = resolve(self);
                return LuaValue.valueOf((h == null) ? "Result(?)" : h.toString());
            }
        });
        owner.httpResMeta = mt;
        return mt;
    }

    private static LuaTable methods() {
        LuaTable m = new LuaTable();
        // ok() — did the exchange complete at all? False for a transport failure (DNS, timeout, TLS, a
        // refused host), which is a different thing from a 404: an HTTP status IS a completed exchange.
        m.set("ok", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(handle(self, "ok").r.ok);
            }
        });
        // status() — the HTTP status number, or nil when the exchange never completed.
        m.set("status", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaHttp.Result r = handle(self, "status").r;
                return r.ok ? LuaValue.valueOf(r.status) : LuaValue.NIL;
            }
        });
        // body() — the response body as a string, or nil when the exchange never completed.
        m.set("body", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaHttp.Result r = handle(self, "body").r;
                return r.ok ? LuaValue.valueOf(r.body) : LuaValue.NIL;
            }
        });
        // header(name) — one response header, matched case-insensitively, or nil. The same match
        // req:header(name) does, so a header is one header however either side spelled it -- which is what
        // the old res.headers table asked the reader to remember for it (its keys were lower-cased).
        m.set("header", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaHttp.Result r = handle(a.arg1(), "header").r;
                String name = Args.str(a, 2, "res:header", "name", null).tojstring();
                if(!r.ok)
                    return LuaValue.NIL;
                for(Map.Entry<String, String> e : r.headers.entrySet()) {
                    if(e.getKey().equalsIgnoreCase(name))
                        return LuaValue.valueOf(e.getValue());
                }
                return LuaValue.NIL;
            }
        });
        // error() — why the exchange did not complete, or nil when it did. :ok() is the test; this is the
        // sentence to log.
        m.set("error", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaHttp.Result r = handle(self, "error").r;
                return r.ok ? LuaValue.NIL : LuaValue.valueOf(r.error);
            }
        });
        return m;
    }
}
