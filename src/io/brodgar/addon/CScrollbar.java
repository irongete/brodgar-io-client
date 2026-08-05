package io.brodgar.addon;

import haven.GOut;
import haven.Scrollbar;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

/**
 * The adapter behind {@code hafen.ui():scrollbar()} — a bare {@link Scrollbar}, the client's own (spec
 * {@code 040-ui-controls}, task 040.6), for driving something yourself: the same {@code :range}/{@code :value}
 * as {@link CSlider}, minus the {@code final} flag — the engine gives this control no separate "drag ended"
 * hook, only {@link #changed()}, called on every step of a real drag.
 *
 * <p><b>{@code ctl} stays {@code null}.</b> The engine also lets a {@link Scrollbar} slave itself to a
 * {@link haven.Scrollable} (the constructor {@code haven.Scrollport} uses); this adapter always takes the bare
 * {@code (h, min, max)} constructor, so {@link Scrollbar#draw} never overwrites {@code min}/{@code max}/{@code
 * val} out from under an addon's own {@code :range}/{@code :value} writes.
 *
 * <p>{@link CSlider}'s shape otherwise, down to the clamp-not-refuse {@code :value(v)} and the
 * fires-nothing re-clamp on {@code :range(min, max)}.
 */
final class CScrollbar extends Scrollbar implements Owned.Control, Controls.Value, Controls.Change, Controls.Range {
    /** A default track length; {@code :size(w, h)} overrides it, the width stays the engine's own. */
    static final int DEF_H = 100;

    private final Owned.State own;
    /** {@code :onChange(fn)}. Volatile: the engine fires it from the input pass while Lua may be replacing it. */
    private volatile LuaValue onChange;

    CScrollbar(Addon owner) {
        super(DEF_H, 0, 0);
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
            throw new LuaError("widget:value(v) on a scrollbar is a NUMBER within its range, got " + v.typename());
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

    /** A real drag step ({@code Scrollbar.mousedown}/{@code mousemove} &rarr; {@code update} &rarr; here). */
    public void changed() {
        LuaValue fn = onChange;
        if(!own.dead() && (fn != null))
            AddonManager.callLua(own.owner, Addon.C_WIDGET, fn, LuaValue.valueOf(val));
    }

    public void draw(GOut g) {
        if(own.pending())   // built this statement and not armed yet: a half-configured control paints NOTHING
            return;
        super.draw(g);
    }
}
