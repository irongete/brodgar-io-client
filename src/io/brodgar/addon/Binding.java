package io.brodgar.addon;

import haven.UI;
import haven.Widget;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * <b>{@code w:bind(opt)} — the one link between a control and an option</b> (spec
 * {@code 140-the-options-page-is-the-addons}, task 140.3), both ways and typed. A control the addon built
 * is joined to an option it declared: the control takes the option's value at once, the user moving the
 * control writes the option ({@link #push}, from {@link Controls#fire}), and a write to the option from
 * anywhere moves every bound control ({@link #pull}, from {@link LuaOption#value(LuaValue)}) — silently,
 * so no control's own {@code Changed} fires for a value the addon wrote.
 *
 * <p><b>The record is on both ends.</b> A control is bound to one option ({@link Owned.State#bound}) while
 * an option may have many controls ({@link LuaOption#bound}), and that is why the verb is the widget's and
 * not the option's: the thing that changes look is the control. {@code w:bind()} reads the option,
 * {@code w:bind(nil)} unbinds, a second {@code bind} replaces, and a binding ends with the control
 * ({@link #drop}, from {@link Owned.State#kill}).
 *
 * <p><b>Typed by the adapter</b> ({@link #kindOf}): a {@link CCheck} takes a boolean, a {@link CSlider} a
 * number, a {@link CDropdown} or a {@link CRadio} a choice, a {@link CEntry} a text. The control is
 * configured from the option at the bind — the slider's range from the option's bounds, the dropdown's or
 * radio's rows from its choices — and the value is written through the adapter's own silent
 * {@code value(LuaValue)}, the path {@code widget:value(v)} takes on a control the addon built.
 *
 * <p><b>The pull crosses trees the way every cross-tree write in this layer does.</b> A write to the option
 * made on the step holds no tree and moves a control in any tree at once. A write made from inside a tree —
 * the push from a control's own {@code Changed}, {@code opt:value(v)} from a {@code Draw} handler — moves
 * the controls of that tree at once, and hands a control standing in another tree to the step
 * ({@link #later}, drained by {@link #drainPulls}), where the same write is made holding none: taking a
 * second tree's monitor is the nesting {@link LuaWidget#monitor} refuses (112.2), and a refusal here
 * would be raised at a line the author never wrote. The step re-reads the option, so a value queued twice
 * lands once, as what the option holds then.
 */
final class Binding {
    private Binding() {
    }

    /** The verb, for its messages. */
    static final String VERB = "widget:bind";

    /**
     * The kind of option a control adapter binds to — {@code null} for a control that holds nothing an option
     * stores (a button, a label, a listbox, a scrollbar, a progress bar) and for a surface the addon painted.
     * One {@code instanceof} chain in one place, the discipline every other capability table here keeps.
     */
    static LuaOption.Kind kindOf(Owned c) {
        if(c instanceof CCheck)
            return LuaOption.Kind.BOOLEAN;
        if(c instanceof CSlider)
            return LuaOption.Kind.NUMBER;
        if((c instanceof CDropdown) || (c instanceof CRadio))
            return LuaOption.Kind.CHOICE;
        if(c instanceof CEntry)
            return LuaOption.Kind.TEXT;
        return null;
    }

    /** The control a kind takes, spelled as its builder — what a mismatch names. */
    static String takes(LuaOption.Kind k) {
        switch(k) {
        case BOOLEAN: return "hafen.ui():check()";
        case NUMBER:  return "hafen.ui():slider()";
        case CHOICE:  return "hafen.ui():dropdown() or hafen.ui():radio()";
        default:      return "hafen.ui():entry()";
        }
    }

    /** The whole table, for the refusal on a control that binds nothing. */
    private static final String PAIRS = "a check binds to a boolean option, a slider to a number, a dropdown or a"
        + " radio to a choice, an entry to a text";

    /** {@code w:bind()} — the option {@code c} is bound to, or {@code nil}; {@code c} may be {@code null}. */
    static LuaValue read(Owned c) {
        LuaOption o = (c instanceof Owned.Control) ? ((Owned.Control)c).own().bound : null;
        return (o == null) ? LuaValue.NIL : o.handle();
    }

    /**
     * {@code w:bind(opt)} — join the control to the option. Caller has established that {@code c} is
     * {@code owner}'s own content behind {@code w}. The kinds are checked against the adapter, the control is
     * configured from the option, the value is taken, and the record is written on both ends; a previous
     * binding is dropped first, so a second {@code bind} replaces.
     */
    static void bind(Addon owner, Owned c, Widget w, LuaValue v) {
        LuaOption o = LuaOption.resolve(v);
        if(o == null)
            throw new LuaError(VERB + "(opt): opt is an Option your addon declared — what "
                + AddonOptions.HANDLE + ":boolean(name):default(v):add() and the three builders beside it hand"
                + " back, or " + LuaOption.COLL + ":get(name) — got " + v.typename());
        if(o.owner != owner)
            throw new LuaError(VERB + "(opt): the option '" + o.name + "' is another addon's — a control binds"
                + " to an option your own addon declared, and " + LuaOption.COLL + " is every one of those");
        LuaOption.Kind k = kindOf(c);
        if(k == null)
            throw new LuaError(VERB + "(opt) joins a control you built to an option, and " + LuaWidget.typeName(w)
                + " holds nothing an option stores — " + PAIRS + ".");
        if(k != o.kind)
            throw new LuaError(VERB + "(opt): a " + LuaWidget.typeName(w) + " binds to a " + k.word
                + " option, and '" + o.name + "' is a " + o.kind.word + " option — " + takes(o.kind)
                + " is the control a " + o.kind.word + " takes.");
        Owned.State st = ((Owned.Control)c).own();
        unbind(st);
        configure(c, w, o);
        st.bound = o;
        o.bound.add(c);
    }

    /** {@code w:bind(nil)} — drop the binding, if any. */
    static void unbind(Owned c) {
        if(c instanceof Owned.Control)
            unbind(((Owned.Control)c).own());
    }

    /** {@link Owned.State#kill}: a binding ends with the control. */
    static void drop(Owned.State st) {
        unbind(st);
    }

    private static void unbind(Owned.State st) {
        LuaOption o = st.bound;
        if(o == null)
            return;
        st.bound = null;
        o.bound.remove((Owned)st.self);
    }

    /**
     * The control as the option declares it: a slider's range from the option's bounds, a dropdown's or a
     * radio's rows from its choices, then the value in force — each through the same silent setter
     * {@code widget:range}/{@code :rows}/{@code :value} write on a control the addon built.
     */
    private static void configure(Owned c, Widget w, LuaOption o) {
        switch(o.kind) {
        case NUMBER:
            ((Controls.Range)c).range(LuaValue.valueOf(o.lo), LuaValue.valueOf(o.hi));
            break;
        case CHOICE: {
            LuaTable rows = new LuaTable();
            for(int i = 0; i < o.choices.size(); i++)
                rows.set(i + 1, LuaValue.valueOf(o.choices.get(i)));
            Controls.rows(c, w, rows);
            break;
        }
        default:
            break;
        }
        Controls.value(c, w, o.value());
    }

    // ---- the push: the user moved a bound control ------------------------------------------------------

    /**
     * From {@link Controls#fire} on {@code "Changed"}, ahead of the control's own handlers: a bound control's
     * move IS the option's write. The value is the adapter's own, read after its write — never the fire's
     * argument, which is a bare value on a check and an {@code ev} on a slider — and it goes through the one
     * write path, {@link LuaOption#value(LuaValue)}: checked, stored, {@code Changed} fired, every other bound
     * control pulled. Runs inside the tree that dispatched the press, which is why a refusal is logged rather
     * than thrown: nothing above this frame could show it, and the press itself has already happened.
     */
    static void push(Owned c) {
        if(!(c instanceof Owned.Control))
            return;
        Owned.State st = ((Owned.Control)c).own();
        LuaOption o = st.bound;
        if(o == null)
            return;
        try {
            o.value(Controls.value(c));
        } catch(LuaError e) {
            AddonManager.logAbout(st.owner, "bind: the " + LuaWidget.typeName(c.widget()) + " bound to the option '"
                + o.name + "' moved to a value the option refuses — " + Refusal.reason(e));
        }
    }

    // ---- the pull: the option moved ----------------------------------------------------------------------

    /**
     * From {@link LuaOption#value(LuaValue)}, after the store: every bound control takes {@code v}, through
     * the silent path, so no control's own {@code Changed} fires. A control in another tree, reached from
     * inside a tree, goes to {@link #later}; a control gone with the page it stood in is dropped here, since
     * nothing else reaches a widget the client destroyed.
     */
    static void pull(LuaOption o, LuaValue v) {
        for(Owned c : o.bound) {
            if(c.dead()) {
                o.bound.remove(c);
                continue;
            }
            Widget w = c.widget();
            if(LuaWidget.wouldNest(w)) {
                later.add(c);
                continue;
            }
            apply(o, c, w, v);
        }
    }

    /**
     * The controls whose pull was made from inside another tree's monitor, waiting for the step. Concurrent
     * because a push comes from the input pass and a Lua write from whatever thread ran the handler.
     */
    private static final Queue<Owned> later = new ConcurrentLinkedQueue<Owned>();

    /** On the layer's step, holding no tree: the pulls that could not be made where the write was. */
    static void drainPulls() {
        for(int n = later.size(); n > 0; n--) {
            Owned c = later.poll();
            if(c == null)
                break;
            LuaOption o = ((Owned.Control)c).own().bound;
            if((o == null) || c.dead())
                continue;                          // unbound or ended since: nothing to move
            apply(o, c, c.widget(), o.value());
        }
    }

    /**
     * One control takes the value — or leaves the binding, where the client destroyed it. Never throws.
     *
     * <p><b>A control already holding the value is left alone.</b> The control the user is moving is one of
     * the option's own bound controls, so the push comes straight back to it as a pull; on an entry that
     * write is {@code rsettext}, a fresh buffer with the caret at its end, which would throw the caret every
     * keystroke. The test is the value, not the control, so it also spares a radio's re-render and covers a
     * second control already in step.
     */
    private static void apply(LuaOption o, Owned c, Widget w, LuaValue v) {
        if(gone(w)) {
            o.bound.remove(c);
            return;
        }
        if(Controls.value(c).eq_b(v))
            return;
        try {
            Controls.value(c, w, v);
        } catch(LuaError e) {
            AddonManager.logAbout(c.profOwner(), "bind: the option '" + o.name + "' moved and the "
                + LuaWidget.typeName(w) + " bound to it refuses the value — " + Refusal.reason(e));
        }
    }

    /**
     * Is {@code w} out of its tree for good — destroyed with the page it stood in, or its whole tree gone? The
     * read {@link LuaWidget#live} makes, on a widget in hand; caller has established that its monitor may be
     * taken. An unattached widget is not gone, it is not there yet.
     */
    private static boolean gone(Widget w) {
        UI u = w.ui;
        if(u == null)
            return false;
        synchronized(LuaWidget.monitor(w)) {
            return u.destroyed || !w.hasparent(u.root);
        }
    }
}
