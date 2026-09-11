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
    /** A default track length in DESIGN pixels; {@code :size(w, h)} overrides it, the width stays the engine's own. */
    static final int DEF_H = 100;

    private final Owned.State own;

    CScrollbar(Addon owner) {
        super(Px.in(DEF_H), 0, 0);
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
        this.val = clamp(Controls.num(v, "a scrollbar"));
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
        int nmin = Controls.bound(minv, "min"), nmax = Controls.bound(maxv, "max");
        if(nmin > nmax)
            throw new LuaError("widget:range(min, max) — min (" + nmin + ") must not exceed max (" + nmax + ")");
        this.min = nmin;
        this.max = nmax;
        this.val = clamp(this.val);
    }

    /** A real drag step ({@code Scrollbar.mousedown}/{@code mousemove} &rarr; {@code update} &rarr; here). */
    public void changed() {
        Controls.fire(this, "Changed", LuaValue.valueOf(val));
    }

    public void draw(GOut g) {
        if(own.pending())   // built this statement and not armed yet: a half-configured control paints NOTHING
            return;
        super.draw(Owned.dim(this, g));   // 139.3: disabled? the whole control paints dimmed
    }
}
