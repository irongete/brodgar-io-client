package io.brodgar.addon;

import haven.UI;
import haven.Widget;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.VarArgFunction;

/**
 * The <b>event object</b> — the {@code ev} a handler is given when its key has more than one thing to say
 * (spec {@code 041-unified-events} §R4: <i>one thing to say → the thing itself; more than one → an event
 * object</i>). One axis, mechanical: {@code chk:on("Changed", function(v) end)} receives the value itself
 * and {@code hafen.event():action():on("click", function(ev) end)} receives one of these.
 *
 * <p><b>Every member is a colon verb.</b> {@code ev:msg()}, {@code ev:args()}, {@code ev:sender()} — never
 * {@code ev.msg}. Before 041 this was a plain {@link LuaTable} with fields, built by {@code LuaActionHook}
 * before 039 existed and never reached by it, which would have left {@code ev} the one object in the API
 * mixing {@code .} and {@code :} — the single most common Lua footgun. (A live verb answers the dotted read
 * with the FUNCTION rather than {@code nil}: no metamethod can tell {@code ev.msg} from {@code ev:msg()},
 * which {@link Retired} already records for the Item entity. What is gone is the <i>value</i> at that
 * spelling, not the key.)
 *
 * <p><b>One class, a per-shape methods table</b> chosen at construction, rather than one subclass per shape:
 * ten near-identical classes would give the same refusal behaviour and ten places to change. The metatable's
 * {@code __index} consults that table and <b>throws naming the verb and listing what this shape does
 * answer</b> ({@link Retired#closedIndex}, D-125), so {@code ev:buton()} fails where it is written instead of
 * one character later as <i>"attempt to call a nil value"</i>. The table is minted once per (addon, shape) and
 * cached on the {@link Addon} — per addon like every other metatable in the bridge, so no Lua value crosses a
 * sandbox boundary (D-017).
 *
 * <p><b>Cancelling is {@code ev:preventDefault()}</b> and nothing else: no handler's return value is ever
 * read. The flag lives in the {@link Subs.Cancel} shared by every handler of one fire — <b>and across every
 * addon of one fire</b> — so any handler cancels, every handler still runs, and the outcome never depends on
 * registration order (spec §R3).
 *
 * <p><b>{@code ev:sender()} / {@code ev:target()} are Widget handles</b> (spec §R6), interned lazily through
 * the existing per-addon weak cache ({@link LuaWidget#of}, D-064): {@code UI.wdgmsg} is hot and most handlers
 * never ask. Before 041 they were the widget's class-name STRING, so a handler could not navigate to the
 * thing the event was about; the string is still one token away as {@code ev:sender():type()}.
 */
public final class LuaEvent {
    /**
     * What an event object can say — and therefore which methods table answers on it. One shape per payload
     * kind, not per emitter: the two message streams differ only in the noun for the widget ({@code sender}
     * for what sent an action, {@code target} for what is about to receive a message) and in what may be done
     * about it ({@code resend}/{@code send} outbound, {@code rewrite} inbound).
     */
    public enum Shape {
        /** {@code hafen.event():action():on(msg, fn)} — an outbound {@code wdgmsg}, before the server sees it. */
        ACTION("action", "an action event answers :msg() :sender() :args() :preventDefault() :resend()"
               + " :send(t)"),
        /** {@code hafen.event():message():on(msg, fn)} — an inbound {@code uimsg}, before the widget applies it. */
        MESSAGE("message", "a message event answers :msg() :target() :args() :preventDefault() :rewrite(t)"),
        /**
         * {@code w:on("MouseDown"/"MouseUp"/"MouseMove"/"Wheel", fn)} — the four universal widget input keys
         * (041.3), on a widget you built, one you found by selector, or one an event handed you.
         */
        INPUT("input", "an input event answers :x() :y() :button() :amount() :preventDefault()");

        /** The shape's name, for {@code tostring(ev)}. */
        final String label;
        /** What this shape answers, listed in the refusal an unknown verb throws (D-125). */
        final String vocabulary;

        Shape(String label, String vocabulary) {
            this.label = label;
            this.vocabulary = vocabulary;
        }
    }

    /** The addon this event was minted for — whose intern cache, metatables and CPU budget it belongs to. */
    private final Addon owner;
    /** Which shape this is; picks the methods table and what the fields below mean. */
    private final Shape shape;
    /** The shared cancel flag of this one fire ({@code ev:preventDefault()}), across every handler and addon. */
    private final Subs.Cancel cancel;

    /** The message name ({@code "click"}, {@code "set"}). */
    private final String msg;
    /** The sending widget (ACTION) or the receiving one (MESSAGE) — handed to Lua as a handle, lazily. */
    private final Widget wdg;
    /** The original Java argument array, as the engine built it. */
    private final Object[] args;
    /** The live UI, captured so a DEFERRED {@code resend}/{@code send} (from a timer) still reaches the server. */
    private final UI ui;
    /** MESSAGE: where {@code ev:rewrite(t)} leaves the new Java args for the caller to apply. */
    private final Object[][] rewritten;
    /** INPUT: widget-local pixels (both keys), the button (down/up only, else {@code null}) and the wheel
     * amount ({@code Wheel} only, else {@code null}) — the fields {@link Widget.PointerEvent} subclasses carry
     * only some of, so a shape that does not apply reads {@code nil} rather than throwing (EXAMPLES §1.1). */
    private final int x, y;
    private final Integer button, amount;

    /** The interned Widget handle, minted on the first {@code ev:sender()}/{@code ev:target()}. */
    private LuaValue wdgObj;
    /** The 1-based argument table, built on the first {@code ev:args()} and handed back by identity after. */
    private LuaValue argsObj;

    private LuaEvent(Addon owner, Shape shape, Subs.Cancel cancel, String msg, Widget wdg, Object[] args,
                     UI ui, Object[][] rewritten) {
        this(owner, shape, cancel, msg, wdg, args, ui, rewritten, 0, 0, null, null);
    }

    /** INPUT shape: no sender/target/args, just the pointer coordinates and (maybe) a button or wheel amount. */
    private LuaEvent(Addon owner, Shape shape, Subs.Cancel cancel, String key, int x, int y, Integer button,
                     Integer amount) {
        this(owner, shape, cancel, key, null, null, null, null, x, y, button, amount);
    }

    private LuaEvent(Addon owner, Shape shape, Subs.Cancel cancel, String msg, Widget wdg, Object[] args,
                     UI ui, Object[][] rewritten, int x, int y, Integer button, Integer amount) {
        this.owner = owner;
        this.shape = shape;
        this.cancel = cancel;
        this.msg = msg;
        this.wdg = wdg;
        this.args = args;
        this.ui = ui;
        this.rewritten = rewritten;
        this.x = x;
        this.y = y;
        this.button = button;
        this.amount = amount;
    }

    /** {@code tostring(ev)} → {@code Event(action:click)}. */
    public String toString() {
        return "Event(" + shape.label + ":" + msg + ")";
    }

    /**
     * The {@code ev} for one outbound action ({@code hafen.event():action()}), minted per addon that listens —
     * the {@code hasSub} gate (spec §2.1) keeps an unlistened action free. {@code u} is captured for a
     * deferred {@code resend}/{@code send}.
     */
    static LuaValue action(Addon owner, Widget sender, String msg, Object[] args, Subs.Cancel c, UI u) {
        return of(new LuaEvent(owner, Shape.ACTION, c, msg, sender, args, u, null));
    }

    /**
     * The {@code ev} for one inbound message ({@code hafen.event():message()}). {@code rewritten} is shared
     * across every handler and addon of this one message: the LAST {@code ev:rewrite(t)} wins, and
     * {@code preventDefault} beats all of them (the caller applies nothing).
     */
    static LuaValue message(Addon owner, Widget target, String msg, Object[] args, Subs.Cancel c,
                            Object[][] rewritten) {
        return of(new LuaEvent(owner, Shape.MESSAGE, c, msg, target, args, null, rewritten));
    }

    /**
     * The {@code ev} for one widget input key ({@code w:on("MouseDown"/…, fn)}, 041.3) — minted per (addon,
     * widget, key) fire, never interned: unlike {@code action}/{@code message} there is no {@code hasSub} gate
     * to skip minting for (the caller already knows somebody is listening, since it is that Sub firing).
     */
    static LuaValue input(Addon owner, String key, Widget.PointerEvent ev, Subs.Cancel c) {
        Integer button = (ev instanceof Widget.MouseButtonEvent) ? Integer.valueOf(((Widget.MouseButtonEvent)ev).b)
            : null;
        Integer amount = (ev instanceof Widget.MouseWheelEvent) ? Integer.valueOf(((Widget.MouseWheelEvent)ev).a)
            : null;
        return of(new LuaEvent(owner, Shape.INPUT, c, key, ev.c.x, ev.c.y, button, amount));
    }

    private static LuaValue of(LuaEvent ev) {
        return LuaValue.userdataOf(ev, meta(ev.owner, ev.shape));
    }

    /** The receiver of a colon call, or the error that says a dot call passed the wrong self. */
    private static LuaEvent self(LuaValue v, Shape want, String method) {
        LuaEvent e = null;
        if((v != null) && v.isuserdata()) {
            Object o = v.touserdata();
            if(o instanceof LuaEvent)
                e = (LuaEvent)o;
        }
        if((e == null) || (e.shape != want))
            throw new LuaError("ev:" + method + "() — use a COLON call on the event object the handler was"
                + " given (ev:" + method + "())");
        return e;
    }

    /** {@code ev:sender()} / {@code ev:target()} — the interned handle, minted on the first ask (D-064). */
    private LuaValue widget() {
        if(wdgObj == null)
            wdgObj = LuaWidget.of(owner, wdg);
        return wdgObj;
    }

    /** {@code ev:args()} — the 1-based snapshot, built once and handed back by identity within one event. */
    private LuaValue args() {
        if(argsObj == null)
            argsObj = LuaMarshal.argsToLua(args);
        return argsObj;
    }

    // ---- the per-(addon, shape) metatable ----------------------------------------------------------

    /**
     * The metatable for {@code shape} in {@code owner}'s env, built once and cached on the {@link Addon}. An
     * unknown verb throws listing what this shape answers ({@link Retired#closedIndex}) rather than reading
     * {@code nil}: a payload's vocabulary is the whole of its grammar, closed at construction, so a misspelt
     * member has no future meaning to wait for (D-125).
     */
    private static LuaValue meta(Addon owner, final Shape shape) {
        LuaValue cached = owner.eventMeta[shape.ordinal()];
        if(cached != null)
            return cached;
        LuaTable m = new LuaTable();
        if(shape == Shape.INPUT) {
            input(m);
        } else {
            common(m, shape);
            if(shape == Shape.ACTION)
                outbound(m);
            else
                inbound(m);
        }
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, Retired.closedIndex("ev", m, shape.vocabulary));
        mt.set("__name", LuaValue.valueOf("Event"));
        mt.set("__tostring", new OneArgFunction() {
            public LuaValue call(LuaValue v) {
                LuaEvent e = ((v != null) && v.isuserdata() && (v.touserdata() instanceof LuaEvent))
                    ? (LuaEvent)v.touserdata() : null;
                return LuaValue.valueOf((e == null) ? "Event(?)" : e.toString());
            }
        });
        owner.eventMeta[shape.ordinal()] = mt;
        return mt;
    }

    /** The three verbs both shapes answer, plus the one moment that cancels ({@code preventDefault}). */
    private static void common(LuaTable m, final Shape shape) {
        m.set("msg", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                return LuaValue.valueOf(self(a.arg1(), shape, "msg").msg);
            }
        });
        // sender (what sent the action) / target (what is about to receive the message): the same widget,
        // named for the direction it is on. Both are HANDLES now — ev:sender():type() is the old string.
        final String noun = (shape == Shape.ACTION) ? "sender" : "target";
        m.set(noun, new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                return self(a.arg1(), shape, noun).widget();
            }
        });
        m.set("args", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                return self(a.arg1(), shape, "args").args();
            }
        });
        preventDefault(m, shape);
    }

    /** {@code ev:preventDefault()} — shared by every shape that cancels (all three, currently). */
    private static void preventDefault(LuaTable m, final Shape shape) {
        m.set("preventDefault", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                self(a.arg1(), shape, "preventDefault").cancel.prevent();
                return LuaValue.NIL;
            }
        });
    }

    /**
     * The input half (041.3): {@code ev:x()}/{@code :y()} (widget-local pixels, always present),
     * {@code ev:button()} (down/up only) and {@code ev:amount()} (wheel only) — the two answer {@code nil}
     * where they do not apply, rather than throwing, since which fields a concrete gesture carries is a
     * property of the DATA and not a typo (§1.1's per-key table already says which).
     */
    private static void input(LuaTable m) {
        m.set("x", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                return LuaValue.valueOf(self(a.arg1(), Shape.INPUT, "x").x);
            }
        });
        m.set("y", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                return LuaValue.valueOf(self(a.arg1(), Shape.INPUT, "y").y);
            }
        });
        m.set("button", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Integer b = self(a.arg1(), Shape.INPUT, "button").button;
                return (b == null) ? LuaValue.NIL : LuaValue.valueOf(b.intValue());
            }
        });
        m.set("amount", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Integer amt = self(a.arg1(), Shape.INPUT, "amount").amount;
                return (amt == null) ? LuaValue.NIL : LuaValue.valueOf(amt.intValue());
            }
        });
        preventDefault(m, Shape.INPUT);
    }

    /**
     * The outbound half: re-issue the action yourself. Both {@code resend} and {@code send} imply
     * {@code preventDefault} and both go through {@link UI#rawWdgmsg} rather than {@link UI#wdgmsg}, so a
     * handler that re-issues its own action <b>cannot loop</b> — they bypass the stream that called them.
     */
    private static void outbound(LuaTable m) {
        m.set("resend", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaEvent e = self(a.arg1(), Shape.ACTION, "resend");
                e.cancel.prevent();
                e.ui.rawWdgmsg(e.wdg, e.msg, e.args);      // the ORIGINAL args, verbatim and lossless
                return LuaValue.NIL;
            }
        });
        m.set("send", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaEvent e = self(a.arg1(), Shape.ACTION, "send");
                LuaValue t = Args.required(a, 2, "ev:send", "args");
                e.cancel.prevent();
                e.ui.rawWdgmsg(e.wdg, e.msg, LuaMarshal.luaToArgs(t, "ev:send"));
                return LuaValue.NIL;
            }
        });
    }

    /**
     * The inbound half: apply the message with different arguments. Unlike {@code preventDefault} it does
     * <b>not</b> swallow — the (rewritten) update is still applied — and unlike it, it does not accumulate:
     * the last {@code rewrite} of one message wins, and any {@code preventDefault} beats all of them.
     */
    private static void inbound(LuaTable m) {
        m.set("rewrite", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaEvent e = self(a.arg1(), Shape.MESSAGE, "rewrite");
                LuaValue t = Args.required(a, 2, "ev:rewrite", "args");
                e.rewritten[0] = LuaMarshal.luaToArgs(t, "ev:rewrite");
                return LuaValue.NIL;
            }
        });
    }
}
