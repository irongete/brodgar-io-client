package io.brodgar.addon;

import haven.CheckBox;
import haven.GOut;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaValue;

/**
 * The adapter behind {@code hafen.ui():check()} — a real {@link CheckBox}, the client's own (spec
 * {@code 040-ui-controls}, task 040.4): the caption is {@code :text(s)}, the state is {@code :value(v)} — the
 * first control this feature ships that answers {@link Controls.Change}, the sixth of the six names spec 040
 * §1 gives the whole roster.
 *
 * <p><b>{@code :value(v)} writes {@link CheckBox#a} directly, never through {@link haven.ACheckBox#set}.</b>
 * The engine's own {@code set(boolean)} is what a USER click runs (compare-and-{@link #fire}), and going
 * through it from a programmatic write would re-enter {@code :onChange} for a value the addon itself just
 * wrote — exactly the feedback loop D-150's whole roster of value-bearing controls has to not have. So a write
 * here is a bare field assignment, symmetric with {@link CProgress#value(LuaValue)}.
 *
 * <p><b>The user-driven half goes through the engine's own click path unmodified</b>
 * ({@code CheckBox.mousedown} &rarr; {@code ACheckBox.click} &rarr; {@code set(!state())}), which is exactly
 * what has to fire {@code :onChange} — only {@link haven.ACheckBox#changed} is replaced, from the stock
 * consumer (a {@code wdgmsg} this client-side control never wants to send anyway, since {@code canactivate}
 * defaults {@code false} on a bare-constructed widget) to {@link #fire}, the one Lua callback slot.
 *
 * <p>{@link CtlButton}'s shape otherwise: the ownership contract over one {@link Owned.State}, and the one
 * override every adapter owes the engine — {@link #draw(GOut)} skips the paint while
 * {@link Owned#pending() pending}. No {@code resize()} override: {@code CheckBox} is a plain {@code Widget}
 * subclass, not an {@link haven.SIWidget}, and {@link CheckBox#settext} already calls {@code resize()} itself.
 */
final class CCheck extends CheckBox implements Owned.Control, Controls.Value, Controls.Change {
    private final Owned.State own;

    CCheck(Addon owner) {
        super("");
        this.own = new Owned.State(owner, this);
        this.changed = this::fire;   // replaces the stock wdgmsg consumer; this control sends the server nothing
    }

    public Owned.State own() {
        return own;
    }

    /** {@code c:value()} — the checked state. */
    public LuaValue value() {
        return LuaValue.valueOf(a);
    }

    /** {@code c:value(v)} — {@code v} must be a boolean; a direct field write, so it does NOT fire :onChange. */
    public void value(LuaValue v) {
        if(!v.isboolean())
            throw new LuaError("widget:value(v) on a checkbox is a BOOLEAN, got " + v.typename());
        this.a = v.toboolean();
    }

    /** The engine's own {@code changed} slot, replacing the stock {@code wdgmsg} consumer. A user click only. */
    private void fire(boolean val) {
        Controls.fire(this, "Changed", LuaValue.valueOf(val));
    }

    public void draw(GOut g) {
        if(own.pending())   // built this statement and not armed yet: a half-configured control paints NOTHING
            return;
        super.draw(g);
    }
}
