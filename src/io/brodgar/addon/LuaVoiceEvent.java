package io.brodgar.addon;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.VarArgFunction;

/**
 * <b>What a voice link says when it ends</b> (143.1) — the {@code ev} a {@code voice:on("Close", fn)} or
 * {@code voice:on("Error", fn)} handler is handed. {@code Open} hands the link itself, because it has one
 * thing to say; these have a reason as well, so they are an event object ({@link LuaEvent}'s rule), one
 * shape per key, each answering {@code :connection()} and then what its key carries — the shape of
 * {@link LuaWebSocketEvent} with the status code left out, since the voice protocol has none.
 *
 * <p><b>A value, not an entity.</b> A payload is delivered once and named by nothing, so it is not interned:
 * immutable and per fire, with the metatable of each shape built once per addon and cached on it
 * ({@link Addon#voiceEventMeta}) so no Lua value crosses a sandbox boundary (D-017). Every member is a
 * colon verb, and an unknown one raises naming the vocabulary ({@link Refusal#closedIndex}).
 */
final class LuaVoiceEvent {
    /** Which key minted this, and so which methods table answers on it. */
    enum Shape {
        /** {@code voice:on("Close", fn)} — the link ended, by your {@code :close()} or by the server. */
        CLOSE("close", "a close event"),
        /** {@code voice:on("Error", fn)} — the link failed, or never opened. */
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
    /** The link's own handle — the very object {@code hafen.voice():connection(url)} handed out. */
    private final LuaValue conn;
    /** CLOSE: the reason. ERROR: the sentence. */
    private final String text;

    private LuaVoiceEvent(Shape shape, LuaValue conn, String text) {
        this.shape = shape;
        this.conn = conn;
        this.text = text;
    }

    public String toString() {
        return "Event(" + shape.label + ")";
    }

    /** The {@code ev} for one {@code Close} fire — {@code reason} as the page defines it, {@code ""} for yours. */
    static LuaValue close(Addon owner, LuaValue conn, String reason) {
        return of(owner, new LuaVoiceEvent(Shape.CLOSE, conn, (reason == null) ? "" : reason));
    }

    /** The {@code ev} for one {@code Error} fire — {@code error} the one line that says why. */
    static LuaValue error(Addon owner, LuaValue conn, String error) {
        return of(owner, new LuaVoiceEvent(Shape.ERROR, conn, (error == null) ? "" : error));
    }

    private static LuaValue of(Addon owner, LuaVoiceEvent ev) {
        return LuaValue.userdataOf(ev, meta(owner, ev.shape));
    }

    /** The receiver of a colon call, or the error that says a dot call passed the wrong self. */
    private static LuaVoiceEvent self(LuaValue v, Shape want, String method) {
        LuaVoiceEvent e = null;
        if((v != null) && v.isuserdata()) {
            Object o = v.touserdata();
            if(o instanceof LuaVoiceEvent)
                e = (LuaVoiceEvent)o;
        }
        if((e == null) || (e.shape != want))
            throw new LuaError("ev:" + method + "() — use a COLON call on the event object the handler was"
                + " given (ev:" + method + "())");
        return e;
    }

    /** The metatable for {@code shape} in {@code owner}'s env, built once and cached on the {@link Addon}. */
    private static LuaValue meta(Addon owner, final Shape shape) {
        LuaValue cached = owner.voiceEventMeta[shape.ordinal()];
        if(cached != null)
            return cached;
        LuaTable m = new LuaTable();
        // connection() — the link this is about, by identity: ev:connection() == voice holds, so a handler
        // shared across several links tells them apart with == or a table keyed by them.
        m.set("connection", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Args.only(a, 0, "ev:connection");
                return self(a.arg1(), shape, "connection").conn;
            }
        });
        if(shape == Shape.CLOSE) {
            // reason() — why the link closed: "" where you closed it, the server's own words where it did.
            m.set("reason", new VarArgFunction() {
                public Varargs invoke(Varargs a) {
                    Args.only(a, 0, "ev:reason");
                    return LuaValue.valueOf(self(a.arg1(), shape, "reason").text);
                }
            });
        } else {
            // error() — why the link ended, or why it never opened: one sentence to log.
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
                LuaVoiceEvent e = ((v != null) && v.isuserdata() && (v.touserdata() instanceof LuaVoiceEvent))
                    ? (LuaVoiceEvent)v.touserdata() : null;
                return LuaValue.valueOf((e == null) ? "Event(?)" : e.toString());
            }
        });
        owner.voiceEventMeta[shape.ordinal()] = mt;
        return mt;
    }
}
