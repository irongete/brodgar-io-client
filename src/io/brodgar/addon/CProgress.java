package io.brodgar.addon;

import haven.GOut;
import haven.Progress;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaValue;

/**
 * The adapter behind {@code hafen.ui():progress()} — a real {@link Progress} bar, the client's own (spec
 * {@code 040-ui-controls}, task 040.3): the fill fraction is {@code :value()}, the first control this feature
 * ships that ANSWERS on the six-name {@code :value()} verb (spec 040 §1) rather than merely reading {@code nil}
 * on it. That is what {@link Controls.Value} exists to dispatch on: the interface this task introduces, and
 * every later control with a value (checkbox, radio, slider, entry, list, dropdown) implements it the same way.
 *
 * <p><b>The value IS {@link Progress#a}</b>, the engine's own fraction field — {@code public}, and read by
 * {@code draw()} directly whenever no {@code Supplier} is installed (the ctor never installs one), so writing
 * it here needs no lambda indirection. Range-checked to {@code 0..1} on every write; a write outside it is
 * refused rather than clamped, naming the range, so a caller passing a raw percentage (0..100) by mistake fails
 * loudly instead of pinning silently at 1.0.
 *
 * <p>{@link CtlButton}'s shape otherwise: the ownership contract over one {@link Owned.State}, and the one
 * override every adapter owes the engine — {@link #draw(GOut)} skips the paint while
 * {@link Owned#pending() pending}.
 */
final class CProgress extends Progress implements Owned.Control, Controls.Value {
    /** The client's own default width, at the engine's own {@link Progress#defh} height. */
    static final int DEF_W = 100;

    private final Owned.State own;

    CProgress(Addon owner) {
        super(DEF_W);
        this.own = new Owned.State(owner, this);
    }

    public Owned.State own() {
        return own;
    }

    /** {@code p:value()} — the fill fraction, {@code 0..1}. */
    public LuaValue value() {
        return LuaValue.valueOf(a);
    }

    /** {@code p:value(v)} — {@code v} must be a number in {@code 0..1}; anything else is refused, not clamped. */
    public void value(LuaValue v) {
        if(!v.isnumber())
            throw new LuaError("widget:value(v) on a progress bar is a NUMBER in 0..1 (the fraction filled),"
                + " got " + v.typename());
        double d = v.todouble();
        if((d < 0.0) || (d > 1.0))
            throw new LuaError("widget:value(v) on a progress bar must be in 0..1 (the fraction filled), got "
                + d + " — a percentage is v / 100, not v");
        this.a = (float)d;
    }

    public void draw(GOut g) {
        if(own.pending())   // built this statement and not armed yet: a half-configured control paints NOTHING
            return;
        super.draw(g);
    }
}
