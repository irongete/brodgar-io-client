package io.brodgar.addon;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.VarArgFunction;

/**
 * <b>What a connection's ending says</b> (142.1) — the {@code ev} a {@code conn:on("Close", fn)} or
 * {@code conn:on("Error", fn)} handler is handed. {@code Open} hands the connection itself, because it has
 * one thing to say; these have more, so they are an event object ({@link LuaEvent}'s rule: <i>one thing to
 * say → the thing; more → an {@code ev}</i>), one shape per key, each answering {@code :connection()} and
 * then what its key carries.
 *
 * <p><b>A value, not an entity.</b> A payload is delivered once and named by nothing, so it is not
 * interned: it is the {@link LuaHttpResult} shape, immutable and per fire, with the metatable of each shape
 * built once per addon and cached on it ({@link Addon#wsEventMeta}) so no Lua value crosses a sandbox
 * boundary (D-017). Every member is a colon verb, and an unknown one raises naming the vocabulary
 * ({@link Refusal#closedIndex}).
 */
final class LuaWebSocketEvent {
    /** Which key minted this, and so which methods table answers on it. */
    enum Shape {
        /** {@code conn:on("Close", fn)} — the code and reason the connection ended with. */
        CLOSE("close", "a close event"),
        /** {@code conn:on("Error", fn)} — why the connection ended, or never opened. */
        ERROR("error", "an error event");

        /** The shape's name, for {@code tostring(ev)}. */
        final String label;
        /** What this shape answers, for the refusal an unknown verb throws. */
        final String vocabulary;

        Shape(String label, String vocabulary) {
            this.label = label;
            this.vocabulary = vocabulary;
        }
    }

    private final Shape shape;
    /** The connection's own handle — the very object {@code :connection(url)} handed out. */
    private final LuaValue conn;
    /** CLOSE: the reason. ERROR: the sentence. */
    private final String text;
    /** CLOSE: the status code. */
    private final int code;

    private LuaWebSocketEvent(Shape shape, LuaValue conn, String text, int code) {
        this.shape = shape;
        this.conn = conn;
        this.text = text;
        this.code = code;
    }

    public String toString() {
        return "Event(" + shape.label + ")";
    }

    /** The {@code ev} for one {@code Close} fire — {@code code} and {@code reason} as the page defines them. */
    static LuaValue close(Addon owner, LuaValue conn, int code, String reason) {
        return of(owner, new LuaWebSocketEvent(Shape.CLOSE, conn, (reason == null) ? "" : reason, code));
    }

    /** The {@code ev} for one {@code Error} fire — {@code error} the one line that says why. */
    static LuaValue error(Addon owner, LuaValue conn, String error) {
        return of(owner, new LuaWebSocketEvent(Shape.ERROR, conn, error, 0));
    }

    private static LuaValue of(Addon owner, LuaWebSocketEvent ev) {
        return LuaValue.userdataOf(ev, meta(owner, ev.shape));
    }

    /** The receiver of a colon call, or the error that says a dot call passed the wrong self. */
    private static LuaWebSocketEvent self(LuaValue v, Shape want, String method) {
        LuaWebSocketEvent e = null;
        if((v != null) && v.isuserdata()) {
            Object o = v.touserdata();
            if(o instanceof LuaWebSocketEvent)
                e = (LuaWebSocketEvent)o;
        }
        if((e == null) || (e.shape != want))
            throw new LuaError("ev:" + method + "() — use a COLON call on the event object the handler was"
                + " given (ev:" + method + "())");
        return e;
    }

    /** The metatable for {@code shape} in {@code owner}'s env, built once and cached on the {@link Addon}. */
    private static LuaValue meta(Addon owner, final Shape shape) {
        LuaValue cached = owner.wsEventMeta[shape.ordinal()];
        if(cached != null)
            return cached;
        LuaTable m = new LuaTable();
        // connection() — the connection this is about, by identity: ev:connection() == conn holds, so a
        // handler shared across several connections tells them apart with == or a table keyed by them.
        m.set("connection", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Args.only(a, 0, "ev:connection");
                return self(a.arg1(), shape, "connection").conn;
            }
        });
        if(shape == Shape.CLOSE) {
            // code() / reason() — what the connection closed with: the pair conn:close(code, reason) asked
            // for where the addon ended it, the peer's own where the peer did, and the client's own 1008 pair
            // where the client refused what the peer sent.
            m.set("code", new VarArgFunction() {
                public Varargs invoke(Varargs a) {
                    Args.only(a, 0, "ev:code");
                    return LuaValue.valueOf(self(a.arg1(), shape, "code").code);
                }
            });
            m.set("reason", new VarArgFunction() {
                public Varargs invoke(Varargs a) {
                    Args.only(a, 0, "ev:reason");
                    return LuaValue.valueOf(self(a.arg1(), shape, "reason").text);
                }
            });
        } else {
            // error() — why the connection ended, or why it never opened: one sentence to log.
            m.set("error", new VarArgFunction() {
                public Varargs invoke(Varargs a) {
                    Args.only(a, 0, "ev:error");
                    return LuaValue.valueOf(self(a.arg1(), shape, "error").text);
                }
            });
        }
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, Refusal.closedIndex("ev", m, shape.vocabulary));
        mt.set("__name", LuaValue.valueOf("Event"));
        mt.set("__tostring", new OneArgFunction() {
            public LuaValue call(LuaValue v) {
                LuaWebSocketEvent e = ((v != null) && v.isuserdata() && (v.touserdata() instanceof LuaWebSocketEvent))
                    ? (LuaWebSocketEvent)v.touserdata() : null;
                return LuaValue.valueOf((e == null) ? "Event(?)" : e.toString());
            }
        });
        owner.wsEventMeta[shape.ordinal()] = mt;
        return mt;
    }
}
