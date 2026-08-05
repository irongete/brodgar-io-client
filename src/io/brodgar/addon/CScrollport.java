package io.brodgar.addon;

import haven.Coord;
import haven.GOut;
import haven.Scrollbar;
import haven.Scrollport;
import haven.UI;
import haven.Widget;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

/**
 * The adapter behind {@code hafen.ui():scroll()} — a scrolling container built from the client's own
 * {@link Scrollbar} and the nested {@link Scrollport.Scrollcont} (spec {@code 040-ui-controls}, task 040.8): a
 * child {@code :parent(sp)}'d to it lands INSIDE the scrolling area, and the bar comes alive on its own once
 * the content it holds outgrows the box.
 *
 * <p><b>This does not extend {@code haven.Scrollport}.</b> {@code Scrollport}'s constructor builds its own
 * {@code bar} as a fixed anonymous {@link Scrollbar} subclass — wiring {@code changed()} to {@code cont.sy =
 * bar.val} and nothing else — a decision already made before any subclass of {@code Scrollport} runs, with no
 * seam left for one to add its own hook to that same object. So this control rebuilds the same two-widget shape
 * from the pieces {@code Scrollport} already exposes as {@code public} — {@link Scrollbar} itself, and the
 * nested {@link Scrollport.Scrollcont} — with the bar its OWN adapter ({@link Bar}) instead of the engine's
 * plain one, which is what lets the standard {@code :range}/{@code :value}/{@code :onChange} contract (the same
 * one {@link CScrollbar} answers for a bare {@code :scrollbar()}) reach it: {@code hafen.ui():all("@Scrollbar")}
 * (or {@code sp:children()}) finds it exactly as it would the standalone control.
 *
 * <p><b>{@code :type()} reads {@code "Widget"}</b> — the same answer {@link CRadio} gives, and for the same
 * reason: {@code Scrollport}+{@code Scrollbar} is two engine classes glued into one control, and there is no
 * third, single class this composite instance IS.
 *
 * <p><b>The container itself answers no new verb.</b> A child's door in is {@code :parent(sp)}, redirected by
 * {@code LuaWidget}'s {@code parent(w)} write into {@link #cont} rather than this widget directly — <b>the trap
 * this task exists to not fall into</b>: {@code Widget.add} does not route through {@code addchild}, and this
 * class (like the engine's own {@code Scrollport}) only overrides {@code addchild} — so a plain {@code add()}
 * would drop the child beside the bar instead of inside the scrolling area, and it would look almost right.
 */
final class CScrollport extends Widget implements Owned.Control {
    /** A default box; {@code :size(w, h)} overrides it, same as every other control here. */
    static final Coord DEF_SZ = new Coord(160, 120);

    private final Owned.State own;
    final Bar bar;
    final Scrollport.Scrollcont cont;

    CScrollport(Addon owner) {
        super(DEF_SZ);
        this.own = new Owned.State(owner, this);
        this.bar = adda(new Bar(owner), sz.x, 0, 1, 0);
        this.cont = add(new Scrollport.Scrollcont(sz.sub(bar.sz.x, 0)) {
            public void update() {
                bar.max = Math.max(0, contentsz().y + 10 - sz.y);
            }
        }, Coord.z);
    }

    public Owned.State own() {
        return own;
    }

    /** Redirects a wire-protocol child creation into {@link #cont}, exactly as {@code haven.Scrollport} does. */
    public void addchild(Widget child, Object... args) {
        cont.addchild(child, args);
    }

    public boolean mousewheel(MouseWheelEvent ev) {
        bar.ch(ev.s * UI.scale(15));
        return true;
    }

    public void resize(Coord nsz) {
        super.resize(nsz);
        bar.c = new Coord(nsz.x - bar.sz.x, 0);
        bar.resize(nsz.y);
        cont.resize(nsz.sub(bar.sz.x, 0));
    }

    public void draw(GOut g) {
        if(own.pending())   // built this statement and not armed yet: a half-configured control paints NOTHING
            return;
        super.draw(g);
    }

    /**
     * The port's own {@link Scrollbar}, but a real {@link Owned.Control} — {@link CScrollbar}'s shape exactly,
     * plus the one line {@code haven.Scrollport}'s own anonymous bar carries and this one must keep too: a real
     * drag (or a programmatic write) moves {@link #cont}'s {@code sy}, which is what actually scrolls the
     * content — {@code :onChange} still fires from a real drag only, never from the programmatic writes.
     */
    final class Bar extends Scrollbar implements Owned.Control, Controls.Value, Controls.Change, Controls.Range {
        private final Owned.State own;
        /** {@code :onChange(fn)}. Volatile: the engine fires it from the input pass while Lua may be replacing it. */
        private volatile LuaValue onChange;

        Bar(Addon owner) {
            super(DEF_SZ.y, 0, 0);
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
                throw new LuaError("widget:value(v) on a scrollbar is a NUMBER within its range, got "
                    + v.typename());
            this.val = clamp(v.toint());
            cont.sy = this.val;
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
                throw new LuaError("widget:range(min, max) — min (" + nmin + ") must not exceed max (" + nmax
                    + ")");
            this.min = nmin;
            this.max = nmax;
            this.val = clamp(this.val);
            cont.sy = this.val;
        }

        public LuaValue onChange() {
            return onChange;
        }

        public void onChange(LuaValue fn) {
            this.onChange = fn;
        }

        /** A real drag step (Scrollbar.mousedown/mousemove -> update -> here): scrolls the content AND fires. */
        public void changed() {
            cont.sy = val;
            LuaValue fn = onChange;
            if(!own.dead() && (fn != null))
                AddonManager.callLua(own.owner, Addon.C_WIDGET, fn, LuaValue.valueOf(val));
        }
    }
}
