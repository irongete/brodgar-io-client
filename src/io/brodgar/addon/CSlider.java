package io.brodgar.addon;

import haven.GOut;
import haven.HSlider;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

/**
 * The adapter behind {@code hafen.ui():slider()} — a real {@link HSlider}, the client's own (spec
 * {@code 040-ui-controls}, task 040.6): {@code :range(min, max)} sets the bounds, {@code :value(n)} the
 * position within them, and {@code :onChange(v, final)} — ONE callback over the engine's two hooks.
 *
 * <p><b>{@code :onChange(v, final)} is one name for {@code changed()}/{@code fchanged()}</b> (api-sketch §4):
 * the engine calls {@link #changed()} on every step while the thumb is dragged and {@link #fchanged()} once,
 * on release — both from a real drag only. {@link #value(LuaValue)} below writes {@link HSlider#val} directly
 * and calls neither, the same feedback-loop guarantee 040.4 pinned for the checkbox.
 *
 * <p><b>{@code :value(v)} CLAMPS rather than refuses</b> — unlike {@link CProgress}'s hard {@code 0..1}, a
 * slider's range is itself an addon-chosen, moving target ({@code :range(min, max)} may narrow it after a
 * value was written), so a write outside it is silently pinned to the nearer bound instead of erroring.
 *
 * <p><b>{@code :range(min, max)} re-clamps the current value without firing {@code :onChange}</b> — narrowing
 * the range out from under a value that no longer fits is not a user interaction, so it goes through the same
 * direct field write {@link #value(LuaValue)} does.
 *
 * <p>{@link CtlButton}'s shape otherwise: the ownership contract over one {@link Owned.State}, and the one
 * override every adapter owes the engine — {@link #draw(GOut)} skips the paint while
 * {@link Owned#pending() pending}. No {@code resize()} override: {@link HSlider#resize} takes a width and
 * keeps the engine's own height, caching nothing keyed on the box.
 */
final class CSlider extends HSlider implements Owned.Control, Controls.Value, Controls.Change, Controls.Range {
    /** A default width; {@code :size(w, h)} overrides it, the height stays the engine's own ({@code sflarp}'s). */
    static final int DEF_W = 140;

    private final Owned.State own;
    /** {@code :onChange(fn)}. Volatile: the engine fires it from the input pass while Lua may be replacing it. */
    private volatile LuaValue onChange;

    CSlider(Addon owner) {
        super(DEF_W, 0, 100, 0);
        this.own = new Owned.State(owner, this);
    }

    public Owned.State own() {
        return own;
    }

    /** {@code s:value()} — the current position, within {@code :range}. */
    public LuaValue value() {
        return LuaValue.valueOf(val);
    }

    /** {@code s:value(v)} — {@code v} must be a number; CLAMPED into {@code :range}, not refused. */
    public void value(LuaValue v) {
        if(!v.isnumber())
            throw new LuaError("widget:value(v) on a slider is a NUMBER within its range, got " + v.typename());
        this.val = clamp(v.toint());
    }

    private int clamp(int v) {
        return (v < min) ? min : ((v > max) ? max : v);
    }

    /** {@code s:range()} — {@code {min =, max =}} as they stand right now. */
    public LuaValue range() {
        LuaTable t = new LuaTable();
        t.set("min", LuaValue.valueOf(min));
        t.set("max", LuaValue.valueOf(max));
        return t;
    }

    /**
     * {@code s:range(min, max)} — {@code min} must not exceed {@code max}; re-clamps the current value into
     * the new bounds WITHOUT firing {@code :onChange} (narrowing the range is not a user interaction).
     */
    public void range(LuaValue minv, LuaValue maxv) {
        if(!minv.isnumber())
            throw new LuaError("widget:range(min, max) — min must be a NUMBER, got " + minv.typename());
        if(!maxv.isnumber())
            throw new LuaError("widget:range(min, max) — max must be a NUMBER, got " + maxv.typename());
        int nmin = minv.toint(), nmax = maxv.toint();
        if(nmin > nmax)
            throw new LuaError("widget:range(min, max) — min (" + nmin + ") must not exceed max (" + nmax + ")");
        this.min = nmin;
        this.max = nmax;
        this.val = clamp(this.val);
    }

    public LuaValue onChange() {
        return onChange;
    }

    public void onChange(LuaValue fn) {
        this.onChange = fn;
    }

    /** A real drag step ({@code HSlider.mousemove} &rarr; {@code update} &rarr; here) — {@code final = false}. */
    public void changed() {
        fire(false);
    }

    /** The mouse released ({@code HSlider.mouseup}), once per drag — {@code final = true}. */
    public void fchanged() {
        fire(true);
    }

    private void fire(boolean fin) {
        LuaValue fn = onChange;
        if(!own.dead() && (fn != null))
            AddonManager.callLua(own.owner, Addon.C_WIDGET, fn, LuaValue.valueOf(val), LuaValue.valueOf(fin));
    }

    public void draw(GOut g) {
        if(own.pending())   // built this statement and not armed yet: a half-configured control paints NOTHING
            return;
        super.draw(g);
    }
}
