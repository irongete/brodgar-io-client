package io.brodgar.addon;

import haven.Coord;
import haven.Coord2d;
import haven.OCache;
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
 * <p><b>Every member is a colon verb.</b> {@code ev:msg()}, {@code ev:args()}, {@code ev:widget()} — never
 * {@code ev.msg}. Before 041 this was a plain {@link LuaTable} with fields, built by {@code LuaActionHook}
 * before 039 existed and never reached by it, which would have left {@code ev} the one object in the API
 * mixing {@code .} and {@code :} — the single most common Lua footgun. (A live verb answers the dotted read
 * with the FUNCTION rather than {@code nil}: no metamethod can tell {@code ev.msg} from {@code ev:msg()},
 * so the key is answered by the function itself.)
 *
 * <p><b>One class, a per-shape methods table</b> chosen at construction, rather than one subclass per shape:
 * ten near-identical classes would give the same refusal behaviour and ten places to change. The metatable's
 * {@code __index} consults that table and <b>throws naming the verb and listing what this shape does
 * answer</b> ({@link Refusal#closedIndex}, D-125), so {@code ev:buton()} fails where it is written instead of
 * one character later as <i>"attempt to call a nil value"</i>. The table is minted once per (addon, shape) and
 * cached on the {@link Addon} — per addon like every other metatable in the bridge, so no Lua value crosses a
 * sandbox boundary (D-017).
 *
 * <p><b>Cancelling is {@code ev:preventDefault()}</b> and nothing else: no handler's return value is ever
 * read. The flag lives in the {@link Subs.Cancel} shared by every handler of one fire — <b>and across every
 * addon of one fire</b> — so any handler cancels, every handler still runs, and the outcome never depends on
 * registration order (spec §R3).
 *
 * <p><b>{@code ev:widget()} is a Widget handle</b> (spec §R6), interned lazily through
 * the existing per-addon weak cache ({@link LuaWidget#of}, D-064): {@code UI.wdgmsg} is hot and most handlers
 * never ask. Before 041 they were the widget's class-name STRING, so a handler could not navigate to the
 * thing the event was about; the string is still one token away as {@code ev:widget():type()}.
 */
public final class LuaEvent {
    /**
     * <b>Why a slider's and a scrollbar's {@code Changed} cannot be held back</b> (061.4) — the opening of both
     * refusals, so an author who tried either verb is told the same thing once and where they wrote it.
     */
    private static final String MOVED = "a slider's and a scrollbar's 'Changed' is a REPORT, not a question:"
        + " the client writes the new value and tells you afterwards, so by the time your handler runs the"
        + " value has already moved.";

    /**
     * What an event object can say — and therefore which methods table answers on it. One shape per payload
     * kind, not per emitter: the two message streams differ only in what may be done about the widget
     * ({@code resend}/{@code send} outbound, {@code rewrite} inbound). The widget itself is {@code ev:widget()}
     * on both — it is the same widget either way, and the DIRECTION is already named by which stream you
     * subscribed on.
     */
    public enum Shape {
        /** {@code hafen.event():action():on(msg, fn)} — an outbound {@code wdgmsg}, before the server sees it. */
        ACTION("action", "an action event"),
        /** {@code hafen.event():message():on(msg, fn)} — an inbound {@code uimsg}, before the widget applies it. */
        MESSAGE("message", "a message event"),
        /**
         * {@code w:on("MouseDown"/"MouseUp"/"MouseMove"/"Wheel", fn)} — the four universal widget input keys
         * (041.3), on a widget you built, one you found by selector, or one an event handed you.
         */
        INPUT("input", "an input event"),
        /**
         * {@code w:on("Pressed"/…, fn)} on a control the addon <b>borrowed</b> (061.1) — the client is about to
         * run its own action and is asking first, so unlike the owned half this one can be stopped
         * ({@code :preventDefault()}) or run by the handler itself ({@code :resend()}). {@code :value()} is
         * what the control is about to take where the key carries one (061.2), {@code nil} where it does not.
         *
         * <p>One family is the exception, and it is the same shape from the other side (061.4): a slider's and
         * a scrollbar's {@code Changed} is a <b>report</b> — the client wrote the value first — so both of
         * those verbs raise there rather than lying about what they did. See {@link #MOVED}.
         */
        CONTROL("control", "a control event"),
        /** {@code w:on("Draw", fn)} — an own widget's paint (041.4): three things to say, none cancelable. */
        DRAW("draw", "a draw event"),
        /** {@code grid:on("Cell", fn)} — one grid cell's paint (041.4): four things to say, none cancelable. */
        CELL("cell", "a cell event"),
        /** {@code w:on("Drop", fn)} — a "thing" dropped on an own widget (041.4): three things to say, cancelable. */
        DROP("drop", "a drop event"),
        /** {@code slider:on("Changed", fn)} — a slider's drag step (041.4): two things to say, uncancelable. */
        SLIDER("slider", "a slider's Changed event"),
        /** {@code g:on("Move", fn)} — a mouse grab's drag step (041.5): the pointer plus the live modifiers. */
        GRAB_MOVE("grabmove", "a grab's Move event"),
        /** {@code g:on("Up", fn)} — a mouse grab's release (041.5): {@code GRAB_MOVE} plus which button ended it. */
        GRAB_UP("grabup", "a grab's Up event"),
        /**
         * {@code w:on("Dragged", fn)} / {@code w:on("Resized", fn)} — the user finished dragging or resizing a
         * widget by a handle {@code widget:draggable(h)}/{@code widget:resizable(h)} armed (062). Two things to
         * say, and they are where the widget <b>landed</b>: the numbers {@code widget:position()} — or
         * {@code widget:size()} — read in that same frame, the client's own off-screen clamp and a window that
         * re-packs itself included. Uncancelable — it reports a gesture that is already over.
         */
        GESTURE("gesture", "a gesture event"),
        /**
         * {@code hafen.event():on("GobOverlayAdded"/"GobOverlayRemoved", fn)} — 038.3's payload, objectified
         * (041.7): three things to say, so an {@code ev} rather than the plain {@code {gob, key, native}} table
         * it was before this feature reached it.
         */
        OVERLAY("overlay", "an overlay event"),
        /**
         * {@code hafen.event():on("GobSdtChanged", fn)} — 113.3's payload, objectified like {@code OVERLAY}:
         * two things to say, {@code :gob()} the object and {@code :sdt()} the bytes THIS firing made — never
         * a live re-read, so two deltas landing in one drain cannot make an intermediate stage vanish.
         */
        SDT("sdt", "a state-change event"),
        /**
         * {@code hafen.event():on("GhostClicked"/"SpriteClicked"/"ObjectClicked"/"PatchClicked", fn)} — the V2 click payload,
         * objectified (041.7): all four answer the same shape, and only the noun matching {@code clickKey()}
         * (the emitter that actually fired) reads non-nil — the other three read {@code nil} rather than throwing,
         * the same "the data decides which field applies" rule {@code Shape.INPUT} already has for its
         * {@code button}/{@code amount} (EXAMPLES.md §1.1).
         */
        CLICKED("clicked", "a clicked event");

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
     * only some of, so a shape that does not apply reads {@code nil} rather than throwing (EXAMPLES §1.1).
     * <b>Stored as the engine gave them — DEVICE pixels</b>; {@link #px()}/{@link #py()} are the one place they
     * become the design pixels Lua reads (058.1). */
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

    /** The interned Widget handle, minted on the first {@code ev:widget()}. */
    private LuaValue wdgObj;
    /** The 1-based argument table, built on the first {@code ev:args()} and handed back by identity after. */
    private LuaValue argsObj;

    /** OVERLAY: the gob's id — minted into a handle lazily on first {@code :gob()}, like {@code :widget()}. */
    private final long gobId;
    /** OVERLAY: the overlay's key. CLICKED: which noun answers —
     * {@code "ghost"}/{@code "sprite"}/{@code "object"}/{@code "patch"} ({@link LuaWorldEntity#clickKey()}). */
    private final String key;
    /** OVERLAY: whether this is one of the game's own overlays, vs. one the owning addon attached itself. */
    private final boolean nat;
    /** CLICKED: the world coordinate the click resolved to — a double, unlike {@code Shape.INPUT}'s widget-local
     * pixel ints, so it does not reuse the {@code x}/{@code y} fields above. */
    private final double wx, wy;
    /** OVERLAY: the interned Gob handle, minted lazily on first {@code :gob()} (D-064-style, like {@code :widget()}). */
    private LuaValue gobObj;
    /** CONTROL: the value the control is ABOUT to take, as the client holds it — {@code null} for a key that
     * carries none ({@code Pressed} is an activation and holds nothing). Set by {@link #control} rather than by
     * a constructor, since it belongs to one shape out of the twelve. */
    private Object nval;
    /** CONTROL: the widget whose own method {@code ev:resend()} runs — {@link #wdg} itself everywhere but the
     * list family, where the click funnels through a popup or an inner list belonging to the control one level
     * up (061.3). Set by {@link #control} alongside {@link #nval}. */
    private Widget actor;
    /** CONTROL: <b>the value has already moved</b> (061.4) — a slider and a scrollbar tell the client their
     * value after writing it, so this fire is a report rather than a question: both {@code preventDefault} and
     * {@code resend} raise naming that, instead of pretending to hold back something that already happened. */
    private boolean moved;

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

    /** SDT shape (113.3): {@code hafen.event():on("GobSdtChanged", fn)}'s payload — {@code gobId} the object
     *  this firing is about, {@code bytes} the 1-based {@code 0..255} array this firing's own state already
     *  converted to (by the caller, {@link AddonManager#fireGobSdt}, one fresh table per owner — {@code extra}
     *  is reused rather than a new field, the same slot {@code DROP}'s {@code :thing()} and {@code CELL}'s
     *  {@code :item()} already carry a payload-specific value in). */
    private LuaEvent(Addon owner, Shape shape, long gobId, LuaValue bytes) {
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
        this.extra = bytes;
        this.flag = false;
        this.mods = 0;
        this.gobId = gobId;
        this.key = null;
        this.nat = false;
        this.wx = 0;
        this.wy = 0;
    }

    /** CLICKED shape (041.7): {@code hafen.event():on("GhostClicked"/"SpriteClicked"/"ObjectClicked"/"PatchClicked", fn)}'s
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

    /** INPUT shape: no widget/args, just the pointer coordinates and (maybe) a button or wheel amount. */
    private LuaEvent(Addon owner, Shape shape, Subs.Cancel cancel, String key, int x, int y, Integer button,
                     Integer amount) {
        this(owner, shape, cancel, key, null, null, null, null, x, y, button, amount, null, null, false, 0);
    }

    /** DRAW/CELL/DROP/SLIDER (041.4): no message, no widget/args — a small, shape-specific payload instead. */
    private LuaEvent(Addon owner, Shape shape, Subs.Cancel cancel, int x, int y, LuaValue g, LuaValue extra,
                     boolean flag) {
        this(owner, shape, cancel, null, null, null, null, null, x, y, null, null, g, extra, flag, 0);
    }

    /** GRAB_MOVE/GRAB_UP (041.5): no message/widget/args/cancel — the pointer, the live modifiers, and (UP
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

    /**
     * <b>The {@code x}/{@code y} pair as Lua sees it: design pixels</b> (058.1) — the one conversion site for
     * every shape that carries a screen measurement in those two fields, which is {@code INPUT}'s and
     * {@code DROP}'s pointer, {@code GRAB_MOVE}/{@code GRAB_UP}'s pointer, and {@code DRAW}/{@code CELL}'s
     * {@code w}/{@code h}. Five vocabularies, one pair, one conversion.
     *
     * <p>The two shapes that do <b>not</b> go through it are the two that never held a screen measurement:
     * {@code SLIDER} parks its value in {@code x}, and {@code CLICKED}'s {@code :x()}/{@code :y()} are the
     * world coordinate a click resolved to, which is not a pixel at all.
     */
    private int px() {
        return Px.out(x);
    }

    private int py() {
        return Px.out(y);
    }

    /** {@code tostring(ev)} → {@code Event(action:click)}, or {@code Event(draw)} for a shape with no message. */
    public String toString() {
        return "Event(" + shape.label + ((msg == null) ? "" : (":" + msg)) + ")";
    }

    /**
     * The {@code ev} for one outbound action ({@code hafen.event():action()}), minted per addon that listens —
     * the {@code hasSub} gate (spec §2.1) keeps an unlistened action free. What that gate lets through is an
     * addon holding this message's NAME <b>or</b> {@link Subs#WILD}, the whole stream (082.1); when it holds
     * both, this one value is what both lists are fired over, so a cancel from either stops the send once.
     * {@code u} is captured for a deferred {@code resend}/{@code send}.
     */
    static LuaValue action(Addon owner, Widget sender, String msg, Object[] args, Subs.Cancel c, UI u) {
        return of(new LuaEvent(owner, Shape.ACTION, c, msg, sender, args, u, null));
    }

    /**
     * The {@code ev} for one inbound message ({@code hafen.event():message()}), minted per addon that listens —
     * the {@code hasSub} gate, which lets a message through for an owner holding this update's NAME <b>or</b>
     * {@link Subs#WILD}, the whole stream (082.2); when it holds both, this one value is what both lists are
     * fired over. {@code rewritten} is shared across every handler and addon of this one message: the LAST
     * {@code ev:rewrite(t)} wins, and {@code preventDefault} beats all of them (the caller applies nothing,
     * and skips the post-apply tap besides).
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
     * The {@code ev} for one capability key on a <b>borrowed</b> control ({@code w:on("Pressed", fn)} on one of
     * the client's own buttons, 061.1) — minted per (addon, widget, key) fire like {@link #input}, since the
     * caller is that owner's own {@link Subs} firing. {@code w} is the widget this addon holds the key on,
     * {@code actor} the one whose own method {@code ev:resend()} re-issues (the same widget everywhere but the
     * list family, 061.3), and {@code u} — the borrowed widget's own tree, which is where a resend has to
     * land — is captured so a resend from a LATER frame still finds a session to run in.
     *
     * <p>{@code moved} is the one family where this event is a <b>report</b> (061.4): a slider and a scrollbar
     * write their value and say so afterwards, so there is nothing left to hold back and both verbs that would
     * pretend otherwise raise instead.
     */
    static LuaValue control(Addon owner, String key, Widget w, Widget actor, Object value, Subs.Cancel c,
                            boolean moved) {
        LuaEvent e = new LuaEvent(owner, Shape.CONTROL, c, key, w, null, w.ui, null);
        e.nval = value;
        e.actor = actor;
        e.moved = moved;
        return of(e);
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
     * The {@code ev} for one {@code Dragged}/{@code Resized} fire (062) — {@code x}/{@code y} are the widget's
     * own parent-relative coordinate, or its outer box, <b>as it landed</b>, in the client's device pixels like
     * every other pair in this class, so {@link #px()}/{@link #py()} hand Lua exactly the numbers
     * {@code widget:position()} and {@code widget:size()} read. Minted per (addon, widget) fire, like
     * {@link #input}: the caller is that owner's own {@link Subs} firing, so there is no gate left to skip
     * minting for.
     */
    static LuaValue gesture(Addon owner, int x, int y) {
        return of(new LuaEvent(owner, Shape.GESTURE, (Subs.Cancel)null, (String)null, x, y, null, null));
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
     * The {@code ev} for one {@code GobSdtChanged} fire (113.3) — {@code owner} is the one addon this event
     * is being minted for (per-addon interning, D-045, like every other payload here), {@code bytes} the
     * already-converted array this firing's own state made ({@link AddonManager#fireGobSdt} builds one per
     * owner, so no two addons' handlers ever share one mutable table).
     */
    static LuaValue sdt(Addon owner, long gobId, LuaValue bytes) {
        return of(new LuaEvent(owner, Shape.SDT, gobId, bytes));
    }

    /**
     * The {@code ev} for one {@code GhostClicked}/{@code SpriteClicked}/{@code ObjectClicked}/{@code PatchClicked} fire (V2 click
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

    /** {@code ev:widget()} — the interned handle, minted on the first ask (D-064). */
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

    /**
     * <b>The {@link Coord} at 1-based argument {@code i}</b> — the one lookup both typed readers below share
     * (067.2), and the one place that refuses an index holding something else.
     *
     * <p><b>Why a reader beside {@code :args()} rather than a conversion inside it.</b> A {@code Coord} reaches
     * an argument array in one of two spaces and there is nothing in its <i>shape</i> to tell them apart: a
     * {@code click} carries a screen pixel at 1 and a world point scaled by {@link OCache#posres} at 2, adjacent
     * and identical. {@code :args()} cannot pick — and must not, because {@link #outbound}'s {@code resend}/
     * {@code send} round-trip through it and a converted snapshot would reach the server as different bytes. So
     * the caller names the space, at the index they mean, and the raw snapshot stays raw.
     *
     * <p>The refusal names <b>what is actually there</b> and <b>the other reader</b>: reading the wrong index of
     * the right message and reading the right index with the wrong verb are the same mistake seen from two
     * sides, and either one otherwise walks the character somewhere far away in silence.
     */
    private Coord coordArg(Varargs a, String verb, String other) {
        int i = Args.integer(a, 2, "ev:" + verb, "i", "the 1-based argument INDEX; ev:args() shows what"
                             + " each index holds");
        int n = (args == null) ? 0 : args.length;
        if((i < 1) || (i > n))
            throw new LuaError("ev:" + verb + "(" + i + "): '" + msg + "' carries " + n + " argument"
                + ((n == 1) ? "" : "s") + ", so index " + i + " is not one of them.");
        Object o = args[i - 1];
        if(!(o instanceof Coord))
            throw new LuaError("ev:" + verb + "(" + i + "): argument " + i + " of '" + msg + "' is "
                + describe(o) + ", not a coordinate — only a coordinate argument names a place or a pixel."
                + " ev:args() shows what each index holds, and the other reader is ev:" + other + "(i).");
        return (Coord)o;
    }

    /** What an argument that is not a coordinate actually is, for {@link #coordArg}'s refusal. */
    private static String describe(Object o) {
        if(o == null)
            return "absent (the message has a nil there)";
        if(o instanceof Number)
            return "a number (" + o + ")";
        if(o instanceof String)
            return "the string \"" + o + "\"";
        if(o instanceof Boolean)
            return "a boolean (" + o + ")";
        return "a " + o.getClass().getSimpleName();
    }

    /** {@code ev:gob()} (OVERLAY) — the interned Gob handle, minted on the first ask (like {@code :widget()}). */
    private LuaValue gob() {
        if(gobObj == null)
            // A Gob is the object, so this is the same handle s:world():gob():get(id) hands back (079.3).
            gobObj = LuaGob.of(owner, gobId);
        return gobObj;
    }

    // ---- the per-(addon, shape) metatable ----------------------------------------------------------

    /**
     * The metatable for {@code shape} in {@code owner}'s env, built once and cached on the {@link Addon}. An
     * unknown verb throws listing what this shape answers ({@link Refusal#closedIndex}) rather than reading
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
        } else if(shape == Shape.CONTROL) {
            control(m);
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
        } else if(shape == Shape.GESTURE) {
            gesture(m);
        } else if(shape == Shape.OVERLAY) {
            overlay(m);
        } else if(shape == Shape.SDT) {
            sdt(m);
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
        mt.set(LuaValue.INDEX, Refusal.closedIndex("ev", m, shape.vocabulary));
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
        // widget() — what sent the action, or what is about to receive the message: the SAME widget either
        // way, so it wears one word. The direction is already named by which stream you subscribed on, and a
        // handler copied from one to the other goes on working.
        m.set("widget", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                return self(a.arg1(), shape, "widget").widget();
            }
        });
        m.set("args", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                return self(a.arg1(), shape, "args").args();
            }
        });
        // position(i) / pixel(i) (067.2) — the two spaces a raw protocol coordinate can be in, each named by
        // the caller at the index they mean. They serve these two shapes ALONE, because these are the two that
        // carry raw arguments at all: every other shape has its own builder, which already converted.
        //
        // position(i) undoes the mc.floor(posres) every map message is built with — the exact inverse, so a
        // destination read here is the destination the client resolved. pixel(i) is the SENDER's own design
        // pixels, the space ev:x()/ev:y() speak on a MouseDown, so a press and the message it caused agree
        // about the same widget.
        m.set("position", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaEvent e = self(a.arg1(), shape, "position");
                Coord c = e.coordArg(a, "position", "pixel");
                return LuaPosition.of(e.owner, AddonManager.drawnUser(), Coord2d.of(c).mul(OCache.posres));
            }
        });
        m.set("pixel", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaEvent e = self(a.arg1(), shape, "pixel");
                Coord c = e.coordArg(a, "pixel", "position");
                return AddonManager.xy(Px.out((double)c.x), Px.out((double)c.y));
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
     * The input half (041.3): {@code ev:x()}/{@code :y()} (widget-local design pixels, always present),
     * {@code ev:button()} (down/up only) and {@code ev:amount()} (wheel only) — the two answer {@code nil}
     * where they do not apply, rather than throwing, since which fields a concrete gesture carries is a
     * property of the DATA and not a typo (§1.1's per-key table already says which).
     */
    private static void input(LuaTable m) {
        m.set("x", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                return LuaValue.valueOf(self(a.arg1(), Shape.INPUT, "x").px());
            }
        });
        m.set("y", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                return LuaValue.valueOf(self(a.arg1(), Shape.INPUT, "y").py());
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
     * The borrowed control half (061.1): {@code ev:preventDefault()} stops the client's own action, and
     * {@code ev:resend()} runs it — implying the cancel, so the action happens exactly once however many
     * handlers ask for it.
     *
     * <p><b>{@code resend} raises on a widget that has left the tree.</b> The point of re-issuing is that
     * something happens, and a silent no-op there would lie — the same refusal {@code widget:send} gives, for
     * the same reason. It may be called more than once and from a later frame: the seam sits after the client
     * released its mouse grab, so no gesture state is in flight waiting for it.
     *
     * <p><b>There is no {@code ev:send(t)} twin</b>, so this shape spells the refusal out rather than letting
     * it fall into the generic unknown-verb one: what is deferred here is a METHOD, not a message, so there are
     * no arguments to rewrite.
     *
     * <p><b>And one family reports rather than asks</b> (061.4): a slider and a scrollbar write their value
     * before they tell anyone, so both verbs above raise on those, naming that the value has already moved.
     * That is why {@code preventDefault} is spelled out here instead of coming from {@link #preventDefault}.
     */
    private static void control(LuaTable m) {
        // ev:value() — what the control is ABOUT to take (061.2), read through the one canonical
        // Java→Lua marshal: a checkbox's boolean, a radio's row, a native list's own row object as an
        // opaque handle that still compares ==. nil where the key carries none — a Pressed is an
        // activation and holds nothing — the same "the data decides, not a typo" rule Shape.INPUT's
        // :button()/:amount() already have.
        m.set("value", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                return LuaMarshal.toLua(self(a.arg1(), Shape.CONTROL, "value").nval);
            }
        });
        m.set("preventDefault", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaEvent e = self(a.arg1(), Shape.CONTROL, "preventDefault");
                if(e.moved)
                    throw new LuaError(MOVED + " ev:preventDefault() would have nothing to stop: read"
                        + " ev:value() and act on where the control now is.");
                e.cancel.prevent();
                return LuaValue.NIL;
            }
        });
        m.set("resend", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaEvent e = self(a.arg1(), Shape.CONTROL, "resend");
                if(e.moved)
                    throw new LuaError(MOVED + " ev:resend() has no held-back action of its own to run.");
                UI u = e.ui;
                // Both halves have to still be there: the widget the handler holds, and — for a list one
                // level inside a control (061.3) — the popup or inner list whose change() is what runs.
                if((e.wdg == null) || (u == null) || (u.root == null) || !e.wdg.hasparent(u.root)
                   || (e.actor == null) || !e.actor.hasparent(u.root))
                    throw new LuaError("ev:resend(): the widget this " + e.msg + " came from has LEFT THE TREE"
                        + " (widget:exists() is false), so there is no action of its own left to run. Nothing"
                        + " was re-sent.");
                e.cancel.prevent();
                Controls.replay(e.wdg, e.actor, e.msg, e.nval);
                return LuaValue.NIL;
            }
        });
        m.set("send", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                self(a.arg1(), Shape.CONTROL, "send");
                throw new LuaError("ev:send(t) — there is no message here to rewrite: what this event holds"
                    + " back is the client's own METHOD, not a wdgmsg with arguments. ev:resend() runs the"
                    + " action the control already had, and ev:preventDefault() stops it.");
            }
        });
    }

    /**
     * {@code w:on("Draw", fn)} (041.4): three things to say, so an {@code ev} — {@code :g()} the bound
     * {@link LuaGOut} wrapper, {@code :w()}/{@code :h()} the area to paint, in design pixels. Uncancelable:
     * {@code Draw} carries no {@code preventDefault}, so an unlisted verb (including that one) throws naming
     * the vocabulary — the "throws on :preventDefault()" the suite asserts falls straight out of D-125's
     * closed-shape refusal.
     */
    private static void draw(LuaTable m) {
        m.set("g", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                return self(a.arg1(), Shape.DRAW, "g").g;
            }
        });
        m.set("w", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                return LuaValue.valueOf(self(a.arg1(), Shape.DRAW, "w").px());
            }
        });
        m.set("h", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                return LuaValue.valueOf(self(a.arg1(), Shape.DRAW, "h").py());
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
                return LuaValue.valueOf(self(a.arg1(), Shape.CELL, "w").px());
            }
        });
        m.set("h", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                return LuaValue.valueOf(self(a.arg1(), Shape.CELL, "h").py());
            }
        });
    }

    /**
     * {@code w:on("Drop", fn)} (041.4): {@code :x()}/{@code :y()} widget-local design pixels, {@code :thing()} the
     * neutral drop descriptor ({@code {kind=,res=}}, D-038), {@code :preventDefault()} — a truthy return no
     * longer consumes the drop (R3), a cancelled fire does.
     */
    private static void drop(LuaTable m) {
        m.set("x", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                return LuaValue.valueOf(self(a.arg1(), Shape.DROP, "x").px());
            }
        });
        m.set("y", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                return LuaValue.valueOf(self(a.arg1(), Shape.DROP, "y").py());
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
     * Shared by both grab shapes (041.5, spec §2.2): the pointer coordinates (window design pixels, since a grab has
     * no single owning widget to be local to) and the three live modifier keys — the same flat booleans
     * {@code hafen.ui():mouse()} itself answers, read off the {@code mods} bits captured at fire time rather
     * than polled again (a handler must see what was true at the moment of the move, not now).
     */
    private static void grabCommon(LuaTable m, final Shape shape) {
        m.set("x", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                return LuaValue.valueOf(self(a.arg1(), shape, "x").px());
            }
        });
        m.set("y", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                return LuaValue.valueOf(self(a.arg1(), shape, "y").py());
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
     * {@code w:on("Dragged", fn)} / {@code w:on("Resized", fn)} (062): where the widget landed, or the box it
     * landed at, and nothing else. No modifiers — a drop is a place, and which keys were held while it happened
     * is a question about the pointer ({@code hafen.ui():mouse()}) rather than about the widget that moved.
     */
    private static void gesture(LuaTable m) {
        m.set("x", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                return LuaValue.valueOf(self(a.arg1(), Shape.GESTURE, "x").px());
            }
        });
        m.set("y", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                return LuaValue.valueOf(self(a.arg1(), Shape.GESTURE, "y").py());
            }
        });
    }

    /**
     * {@code hafen.event():on("GobOverlayAdded"/"GobOverlayRemoved", fn)} (041.7): three things to say —
     * {@code :gob()} the owner's own interned handle (minted lazily, like {@code :widget()}), {@code :key()}
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
     * {@code hafen.event():on("GobSdtChanged", fn)} (113.3): two things to say — {@code :gob()} the owner's
     * own interned handle (minted lazily, like {@code :widget()} and {@code OVERLAY}'s), {@code :sdt()} the
     * 1-based {@code 0..255} array THIS firing made. Uncancelable, like every other bus payload.
     */
    private static void sdt(LuaTable m) {
        m.set("gob", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                return self(a.arg1(), Shape.SDT, "gob").gob();
            }
        });
        m.set("sdt", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                return self(a.arg1(), Shape.SDT, "sdt").extra;
            }
        });
    }

    /**
     * {@code hafen.event():on("GhostClicked"/"SpriteClicked"/"ObjectClicked"/"PatchClicked", fn)} (041.7, 118.3): one shape for
     * all four, since they differ only in which noun answers —
     * {@code :ghost()}/{@code :sprite()}/{@code :object()}/{@code :patch()} all exist on every CLICKED event,
     * but only the one matching {@link LuaWorldEntity#clickKey()} (the emitter that actually fired) reads the
     * handle; the other three read {@code nil}, the same "the data decides, not a typo" rule
     * {@code Shape.INPUT} already has for {@code :button()}/{@code :amount()}. Uncancelable: a click on a
     * client-only entity is already consumed by the time this fires (no server message was ever sent).
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
        m.set("patch", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaEvent e = self(a.arg1(), Shape.CLICKED, "patch");
                return "patch".equals(e.key) ? e.extra : LuaValue.NIL;
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
        // gob() — the game OBJECT the click landed on, or nil for ground and for every action that is not a
        // map click (105). The id is in the arguments already, and it is exactly the thing an addon must not
        // read: the wire carries a gob id as a SIGN-TRUNCATED int32 (Gob.GobClick.clickargs casts, while an id
        // arrives over the wire as uint32), so args[6] disagrees with gob:id() for every id past 2^31 and
        // agrees for every id below it — a comparison that works until one day it does not. The client knows
        // the object, so it hands the object over: MapView.clickhit holds it for the length of the dispatch.
        m.set("gob", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaEvent e = self(a.arg1(), Shape.ACTION, "gob");
                // audit2 B01: the EVENT's own session. The click being dispatched is one login's, so the
                // object it landed on is read out of that login's state rather than out of one field the
                // whole client shared — where a handler running for a background character could read what
                // the drawn one had just clicked.
                long id = AddonManager.clickGobId(e.ui);
                return (id < 0) ? LuaValue.NIL : LuaGob.of(e.owner, id);
            }
        });
        // 093.1 (A-095): BOTH are PROTECTED, under the existing widget.send -- the same wire, the same key,
        // and the consent line the user already reads for it ("send any message the client itself could
        // send") is exactly what these do. Two arguments for it, neither covered by conventions.md's old
        // half-sentence ("Nor does replacing an action the client is already sending"): ev:send(t) takes an
        // ARBITRARY argument table, so replacing an itemact's target or a transfer's count is a different
        // action wearing the same name; and neither verb is once-only, so a handler that loops turns one
        // user click into N server messages. ev:preventDefault() stays unprotected -- cancelling reaches
        // nothing. The gate runs FIRST (D-213), before the receiver is even resolved.
        m.set("resend", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                AddonManager.requirePermission(ownerOf(a.arg1()), Permission.WIDGET_SEND, "ev:resend");
                LuaEvent e = self(a.arg1(), Shape.ACTION, "resend");
                e.cancel.prevent();
                e.ui.rawWdgmsg(e.wdg, e.msg, e.args);      // the ORIGINAL args, verbatim and lossless
                return LuaValue.NIL;
            }
        });
        m.set("send", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                AddonManager.requirePermission(ownerOf(a.arg1()), Permission.WIDGET_SEND, "ev:send");
                LuaEvent e = self(a.arg1(), Shape.ACTION, "send");
                LuaValue t = Args.required(a, 2, "ev:send", "args");
                e.cancel.prevent();
                e.ui.rawWdgmsg(e.wdg, e.msg, LuaMarshal.luaToArgs(t, "ev:send"));
                return LuaValue.NIL;
            }
        });
    }

    /**
     * The addon whose {@code ev} this is, for a gate that has to run <b>before</b> the receiver is checked
     * (D-213). {@code null} for anything that is not an event, which {@link AddonManager#requirePermission}
     * refuses exactly as it refuses an addon that declared nothing — and {@link #self} then raises the shape
     * refusal a beat later for a caller that passed something else entirely.
     */
    private static Addon ownerOf(LuaValue self) {
        if((self == null) || !self.isuserdata())
            return null;
        Object o = self.touserdata();
        return (o instanceof LuaEvent) ? ((LuaEvent)o).owner : null;
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
