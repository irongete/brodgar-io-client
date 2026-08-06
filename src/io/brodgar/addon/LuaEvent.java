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
        INPUT("input", "an input event answers :x() :y() :button() :amount() :preventDefault()"),
        /** {@code w:on("Draw", fn)} — an own widget's paint (041.4): three things to say, none cancelable. */
        DRAW("draw", "a draw event answers :g() :w() :h()"),
        /** {@code grid:on("Cell", fn)} — one grid cell's paint (041.4): four things to say, none cancelable. */
        CELL("cell", "a cell event answers :g() :item() :w() :h()"),
        /** {@code w:on("Drop", fn)} — a "thing" dropped on an own widget (041.4): three things to say, cancelable. */
        DROP("drop", "a drop event answers :x() :y() :thing() :preventDefault()"),
        /** {@code slider:on("Changed", fn)} — a slider's drag step (041.4): two things to say, uncancelable. */
        SLIDER("slider", "a slider's Changed event answers :value() :final()"),
        /** {@code g:on("Move", fn)} — a mouse grab's drag step (041.5): the pointer plus the live modifiers. */
        GRAB_MOVE("grabmove", "a grab's Move event answers :x() :y() :shift() :ctrl() :alt()"),
        /** {@code g:on("Up", fn)} — a mouse grab's release (041.5): {@code GRAB_MOVE} plus which button ended it. */
        GRAB_UP("grabup", "a grab's Up event answers :x() :y() :shift() :ctrl() :alt() :button()"),
        /**
         * {@code hafen.event():on("GobOverlayAdded"/"GobOverlayRemoved", fn)} — 038.3's payload, objectified
         * (041.7): three things to say, so an {@code ev} rather than the plain {@code {gob, key, native}} table
         * it was before this feature reached it.
         */
        OVERLAY("overlay", "an overlay event answers :gob() :key() :native()"),
        /**
         * {@code hafen.event():on("GhostClicked"/"SpriteClicked"/"ObjectClicked", fn)} — the V2 click payload,
         * objectified (041.7): all three answer the same shape, and only the noun matching {@code clickKey()}
         * (the emitter that actually fired) reads non-nil — the other two read {@code nil} rather than throwing,
         * the same "the data decides which field applies" rule {@code Shape.INPUT} already has for its
         * {@code button}/{@code amount} (EXAMPLES.md §1.1).
         */
        CLICKED("clicked", "a clicked event answers :ghost() :sprite() :object() :button() :x() :y()");

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
    /** GRAB_MOVE/GRAB_UP: the live modifier keys ({@code UI.modflags()} bits) at the moment of the fire. */
    private final int mods;
    /** DRAW/CELL: the bound {@code g} wrapper table (already inert once its own bind cycle ends). */
    private final LuaValue g;
    /** CELL: the row being painted. DROP: the neutral drop descriptor. Otherwise {@code null}. */
    private final LuaValue extra;
    /** SLIDER: whether this step ended the drag ({@code ev:final()}). Otherwise unused. */
    private final boolean flag;

    /** The interned Widget handle, minted on the first {@code ev:sender()}/{@code ev:target()}. */
    private LuaValue wdgObj;
    /** The 1-based argument table, built on the first {@code ev:args()} and handed back by identity after. */
    private LuaValue argsObj;

    /** OVERLAY: the gob's id — minted into a handle lazily on first {@code :gob()}, like {@code :sender()}. */
    private final long gobId;
    /** OVERLAY: the overlay's key. CLICKED: which noun answers — {@code "ghost"}/{@code "sprite"}/{@code "object"}
     * ({@link LuaWorldEntity#clickKey()}). */
    private final String key;
    /** OVERLAY: whether this is one of the game's own overlays, vs. one the owning addon attached itself. */
    private final boolean nat;
    /** CLICKED: the world coordinate the click resolved to — a double, unlike {@code Shape.INPUT}'s widget-local
     * pixel ints, so it does not reuse the {@code x}/{@code y} fields above. */
    private final double wx, wy;
    /** OVERLAY: the interned Gob handle, minted lazily on first {@code :gob()} (D-064-style, like {@code :sender()}). */
    private LuaValue gobObj;

    /** OVERLAY shape (041.7): {@code hafen.event():on("GobOverlayAdded"/"GobOverlayRemoved", fn)}'s payload. */
    private LuaEvent(Addon owner, Shape shape, long gobId, String key, boolean nat) {
        this.owner = owner;
        this.shape = shape;
        this.cancel = null;
        this.msg = null;
        this.wdg = null;
        this.args = null;
        this.ui = null;
        this.rewritten = null;
        this.x = 0;
        this.y = 0;
        this.button = null;
        this.amount = null;
        this.g = null;
        this.extra = null;
        this.flag = false;
        this.mods = 0;
        this.gobId = gobId;
        this.key = key;
        this.nat = nat;
        this.wx = 0;
        this.wy = 0;
    }

    /** CLICKED shape (041.7): {@code hafen.event():on("GhostClicked"/"SpriteClicked"/"ObjectClicked", fn)}'s
     * payload — {@code entity} is the already-interned handle (V2 click dispatch mints it before this is built,
     * unlike a lazily-minted Gob), {@code key} which noun it answers to. */
    private LuaEvent(Addon owner, Shape shape, LuaValue entity, String key, int button, double wx, double wy) {
        this.owner = owner;
        this.shape = shape;
        this.cancel = null;
        this.msg = null;
        this.wdg = null;
        this.args = null;
        this.ui = null;
        this.rewritten = null;
        this.x = 0;
        this.y = 0;
        this.button = Integer.valueOf(button);
        this.amount = null;
        this.g = null;
        this.extra = entity;
        this.flag = false;
        this.mods = 0;
        this.gobId = 0;
        this.key = key;
        this.nat = false;
        this.wx = wx;
        this.wy = wy;
    }

    private LuaEvent(Addon owner, Shape shape, Subs.Cancel cancel, String msg, Widget wdg, Object[] args,
                     UI ui, Object[][] rewritten) {
        this(owner, shape, cancel, msg, wdg, args, ui, rewritten, 0, 0, null, null, null, null, false, 0);
    }

    /** INPUT shape: no sender/target/args, just the pointer coordinates and (maybe) a button or wheel amount. */
    private LuaEvent(Addon owner, Shape shape, Subs.Cancel cancel, String key, int x, int y, Integer button,
                     Integer amount) {
        this(owner, shape, cancel, key, null, null, null, null, x, y, button, amount, null, null, false, 0);
    }

    /** DRAW/CELL/DROP/SLIDER (041.4): no message, no sender/args — a small, shape-specific payload instead. */
    private LuaEvent(Addon owner, Shape shape, Subs.Cancel cancel, int x, int y, LuaValue g, LuaValue extra,
                     boolean flag) {
        this(owner, shape, cancel, null, null, null, null, null, x, y, null, null, g, extra, flag, 0);
    }

    /** GRAB_MOVE/GRAB_UP (041.5): no message/sender/args/cancel — the pointer, the live modifiers, and (UP
     * only) which button ended the drag. Not cancelable, like DRAW/CELL/TICK. */
    private LuaEvent(Addon owner, Shape shape, int x, int y, int mods, Integer button) {
        this(owner, shape, null, null, null, null, null, null, x, y, button, null, null, null, false, mods);
    }

    private LuaEvent(Addon owner, Shape shape, Subs.Cancel cancel, String msg, Widget wdg, Object[] args,
                     UI ui, Object[][] rewritten, int x, int y, Integer button, Integer amount, LuaValue g,
                     LuaValue extra, boolean flag, int mods) {
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
        this.g = g;
        this.extra = extra;
        this.flag = flag;
        this.mods = mods;
        // OVERLAY/CLICKED (041.7) never reach this constructor — each has its own, below — so these five
        // are always their zero value here.
        this.gobId = 0;
        this.key = null;
        this.nat = false;
        this.wx = 0;
        this.wy = 0;
    }

    /** {@code tostring(ev)} → {@code Event(action:click)}, or {@code Event(draw)} for a shape with no message. */
    public String toString() {
        return "Event(" + shape.label + ((msg == null) ? "" : (":" + msg)) + ")";
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

    /**
     * The {@code ev} for one {@code Draw} fire ({@code w:on("Draw", fn)}, 041.4) — {@code g} is the ALREADY-BOUND
     * {@link LuaGOut} wrapper table, shared by every handler of this one fire (they paint into the same frame),
     * and goes inert with it on unbind — so a stashed {@code ev} is exactly as inert as a stashed {@code g}.
     */
    static LuaValue draw(Addon owner, LuaValue g, int w, int h) {
        return of(new LuaEvent(owner, Shape.DRAW, null, w, h, g, null, false));
    }

    /** The {@code ev} for one grid {@code Cell} fire ({@code grid:on("Cell", fn)}, 041.4) — {@code g} as above. */
    static LuaValue cell(Addon owner, LuaValue g, LuaValue item, int w, int h) {
        return of(new LuaEvent(owner, Shape.CELL, null, w, h, g, item, false));
    }

    /** The {@code ev} for one {@code Drop} fire ({@code w:on("Drop", fn)}, 041.4) — {@code thing} the descriptor. */
    static LuaValue drop(Addon owner, Subs.Cancel c, int x, int y, LuaValue thing) {
        return of(new LuaEvent(owner, Shape.DROP, c, x, y, null, thing, false));
    }

    /** The {@code ev} for a slider's {@code Changed} fire ({@code slider:on("Changed", fn)}, 041.4). */
    static LuaValue slider(Addon owner, int value, boolean fin) {
        return of(new LuaEvent(owner, Shape.SLIDER, null, value, 0, null, null, fin));
    }

    /** The {@code ev} for one grab {@code Move} fire ({@code g:on("Move", fn)}, 041.5). */
    static LuaValue grabMove(Addon owner, int x, int y, int mods) {
        return of(new LuaEvent(owner, Shape.GRAB_MOVE, x, y, mods, null));
    }

    /** The {@code ev} for one grab {@code Up} fire ({@code g:on("Up", fn)}, 041.5) — {@code button} is which
     * one ended the drag, exactly as an input key's {@code :button()} does. */
    static LuaValue grabUp(Addon owner, int x, int y, int mods, int button) {
        return of(new LuaEvent(owner, Shape.GRAB_UP, x, y, mods, Integer.valueOf(button)));
    }

    /**
     * The {@code ev} for one {@code GobOverlayAdded}/{@code GobOverlayRemoved} fire (038.3's payload,
     * objectified 041.7) — {@code owner} is the one addon this event is being minted for (per-addon interning,
     * D-045), same as {@link AddonManager#fireGobOverlay} already required of its table.
     */
    static LuaValue overlay(Addon owner, long gobId, String key, boolean nat) {
        return of(new LuaEvent(owner, Shape.OVERLAY, gobId, key, nat));
    }

    /**
     * The {@code ev} for one {@code GhostClicked}/{@code SpriteClicked}/{@code ObjectClicked} fire (V2 click
     * dispatch, objectified 041.7) — {@code entity} is the already-interned ghost/sprite/object handle,
     * {@code clickKey} which noun answers it ({@link LuaWorldEntity#clickKey()}).
     */
    static LuaValue clicked(Addon owner, LuaValue entity, String clickKey, int button, double x, double y) {
        return of(new LuaEvent(owner, Shape.CLICKED, entity, clickKey, button, x, y));
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

    /** {@code ev:gob()} (OVERLAY) — the interned Gob handle, minted on the first ask (like {@code :sender()}). */
    private LuaValue gob() {
        if(gobObj == null)
            gobObj = LuaGob.of(owner, gobId);
        return gobObj;
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
        } else if(shape == Shape.DRAW) {
            draw(m);
        } else if(shape == Shape.CELL) {
            cell(m);
        } else if(shape == Shape.DROP) {
            drop(m);
        } else if(shape == Shape.SLIDER) {
            slider(m);
        } else if(shape == Shape.GRAB_MOVE) {
            grabMove(m);
        } else if(shape == Shape.GRAB_UP) {
            grabUp(m);
        } else if(shape == Shape.OVERLAY) {
            overlay(m);
        } else if(shape == Shape.CLICKED) {
            clicked(m);
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
     * {@code w:on("Draw", fn)} (041.4): three things to say, so an {@code ev} — {@code :g()} the bound
     * {@link LuaGOut} wrapper, {@code :w()}/{@code :h()} the area to paint. Uncancelable: {@code Draw} carries
     * no {@code preventDefault}, so an unlisted verb (including that one) throws naming the vocabulary — the
     * "throws on :preventDefault()" the suite asserts falls straight out of D-125's closed-shape refusal.
     */
    private static void draw(LuaTable m) {
        m.set("g", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                return self(a.arg1(), Shape.DRAW, "g").g;
            }
        });
        m.set("w", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                return LuaValue.valueOf(self(a.arg1(), Shape.DRAW, "w").x);
            }
        });
        m.set("h", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                return LuaValue.valueOf(self(a.arg1(), Shape.DRAW, "h").y);
            }
        });
    }

    /** {@code grid:on("Cell", fn)} (041.4): {@link #draw} plus {@code :item()}, the row being painted. */
    private static void cell(LuaTable m) {
        m.set("g", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                return self(a.arg1(), Shape.CELL, "g").g;
            }
        });
        m.set("item", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                return self(a.arg1(), Shape.CELL, "item").extra;
            }
        });
        m.set("w", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                return LuaValue.valueOf(self(a.arg1(), Shape.CELL, "w").x);
            }
        });
        m.set("h", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                return LuaValue.valueOf(self(a.arg1(), Shape.CELL, "h").y);
            }
        });
    }

    /**
     * {@code w:on("Drop", fn)} (041.4): {@code :x()}/{@code :y()} widget-local pixels, {@code :thing()} the
     * neutral drop descriptor ({@code {kind=,res=}}, D-038), {@code :preventDefault()} — a truthy return no
     * longer consumes the drop (R3), a cancelled fire does.
     */
    private static void drop(LuaTable m) {
        m.set("x", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                return LuaValue.valueOf(self(a.arg1(), Shape.DROP, "x").x);
            }
        });
        m.set("y", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                return LuaValue.valueOf(self(a.arg1(), Shape.DROP, "y").y);
            }
        });
        m.set("thing", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                return self(a.arg1(), Shape.DROP, "thing").extra;
            }
        });
        preventDefault(m, Shape.DROP);
    }

    /**
     * {@code slider:on("Changed", fn)} (041.4): two things to say — {@code :value()} the position,
     * {@code :final()} whether this step ended the drag. Uncancelable, like every other {@code Changed}.
     */
    private static void slider(LuaTable m) {
        m.set("value", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                return LuaValue.valueOf(self(a.arg1(), Shape.SLIDER, "value").x);
            }
        });
        m.set("final", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                return LuaValue.valueOf(self(a.arg1(), Shape.SLIDER, "final").flag);
            }
        });
    }

    /**
     * Shared by both grab shapes (041.5, spec §2.2): the pointer coordinates (window pixels, since a grab has
     * no single owning widget to be local to) and the three live modifier keys — the same flat booleans
     * {@code hafen.ui():mouse()} itself answers, read off the {@code mods} bits captured at fire time rather
     * than polled again (a handler must see what was true at the moment of the move, not now).
     */
    private static void grabCommon(LuaTable m, final Shape shape) {
        m.set("x", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                return LuaValue.valueOf(self(a.arg1(), shape, "x").x);
            }
        });
        m.set("y", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                return LuaValue.valueOf(self(a.arg1(), shape, "y").y);
            }
        });
        m.set("shift", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                return LuaValue.valueOf((self(a.arg1(), shape, "shift").mods & UI.MOD_SHIFT) != 0);
            }
        });
        m.set("ctrl", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                return LuaValue.valueOf((self(a.arg1(), shape, "ctrl").mods & UI.MOD_CTRL) != 0);
            }
        });
        m.set("alt", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                return LuaValue.valueOf((self(a.arg1(), shape, "alt").mods & UI.MOD_META) != 0);
            }
        });
    }

    /** {@code g:on("Move", fn)} (041.5): the pointer and the live modifiers, nothing to cancel. */
    private static void grabMove(LuaTable m) {
        grabCommon(m, Shape.GRAB_MOVE);
    }

    /** {@code g:on("Up", fn)} (041.5): {@link #grabCommon} plus {@code :button()} — which one ended the drag. */
    private static void grabUp(LuaTable m) {
        grabCommon(m, Shape.GRAB_UP);
        m.set("button", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Integer b = self(a.arg1(), Shape.GRAB_UP, "button").button;
                return (b == null) ? LuaValue.NIL : LuaValue.valueOf(b.intValue());
            }
        });
    }

    /**
     * {@code hafen.event():on("GobOverlayAdded"/"GobOverlayRemoved", fn)} (041.7): three things to say —
     * {@code :gob()} the owner's own interned handle (minted lazily, like {@code :sender()}), {@code :key()}
     * the overlay's key, {@code :native()} whether the game put it there. Uncancelable, like every other bus
     * payload: an unlisted verb (including {@code :preventDefault()}) throws naming the vocabulary.
     */
    private static void overlay(LuaTable m) {
        m.set("gob", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                return self(a.arg1(), Shape.OVERLAY, "gob").gob();
            }
        });
        m.set("key", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                return LuaValue.valueOf(self(a.arg1(), Shape.OVERLAY, "key").key);
            }
        });
        m.set("native", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                return LuaValue.valueOf(self(a.arg1(), Shape.OVERLAY, "native").nat);
            }
        });
    }

    /**
     * {@code hafen.event():on("GhostClicked"/"SpriteClicked"/"ObjectClicked", fn)} (041.7): one shape for all
     * three, since they differ only in which noun answers — {@code :ghost()}/{@code :sprite()}/{@code :object()}
     * all exist on every CLICKED event, but only the one matching {@link LuaWorldEntity#clickKey()} (the emitter
     * that actually fired) reads the handle; the other two read {@code nil}, the same "the data decides, not a
     * typo" rule {@code Shape.INPUT} already has for {@code :button()}/{@code :amount()}. Uncancelable: a click
     * on a client-only entity is already consumed by the time this fires (no server message was ever sent).
     */
    private static void clicked(LuaTable m) {
        m.set("ghost", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaEvent e = self(a.arg1(), Shape.CLICKED, "ghost");
                return "ghost".equals(e.key) ? e.extra : LuaValue.NIL;
            }
        });
        m.set("sprite", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaEvent e = self(a.arg1(), Shape.CLICKED, "sprite");
                return "sprite".equals(e.key) ? e.extra : LuaValue.NIL;
            }
        });
        m.set("object", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaEvent e = self(a.arg1(), Shape.CLICKED, "object");
                return "object".equals(e.key) ? e.extra : LuaValue.NIL;
            }
        });
        m.set("button", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                return LuaValue.valueOf(self(a.arg1(), Shape.CLICKED, "button").button.intValue());
            }
        });
        m.set("x", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                return LuaValue.valueOf(self(a.arg1(), Shape.CLICKED, "x").wx);
            }
        });
        m.set("y", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                return LuaValue.valueOf(self(a.arg1(), Shape.CLICKED, "y").wy);
            }
        });
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
