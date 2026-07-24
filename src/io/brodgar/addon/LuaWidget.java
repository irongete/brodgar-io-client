package io.brodgar.addon;

import haven.Coord;
import haven.GOut;
import haven.Widget;

import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;

/**
 * A <b>client-side</b> {@link Widget} whose lifecycle callbacks forward to an addon's Lua functions —
 * the Java half of {@code hafen.ui.widget} / {@code hafen.ui.window} (spec {@code 07-ui-and-drawing.md},
 * Phase 2a). It is a leaf widget: {@link #draw}, {@link #tick}, and the mouse handlers each call the
 * addon's matching callback ({@code onDraw}/{@code onTick}/{@code onClick}/{@code onMouseUp}/
 * {@code onMouseMove}/{@code onWheel}) through {@link AddonManager#callLua}, so every forward is
 * <b>watchdog-armed</b> (D-018 layer 1), <b>error-isolated</b> (a Lua error is logged, never thrown into
 * the render/tick loop), and CPU-accounted exactly like an event handler.
 *
 * <p><b>Not bound to a server id</b> — a LuaWidget cannot {@code wdgmsg} the server (its
 * {@code wdgmsg} falls through to {@code ui.root} and is dropped). That is correct for custom UI; game
 * interaction goes through {@code hafen.act} (Phase 4). The addon never sees this object: it holds an
 * opaque handle built in {@link AddonManager}, and the bridge owns the widget for teardown
 * (registered in {@link Addon}'s owned-resource registry, destroyed on reload/disable, principle P2).
 *
 * <p>The draw callback receives {@link #gwrap}, a thin wrapper over the live {@link GOut} — allocated
 * once and re-bound each frame ({@link #curg}). Outside a draw the wrapper is inert (its methods no-op),
 * so an addon that stashes {@code g} and uses it later cannot corrupt the client's draw pipeline.
 * Coordinates are the widget's own pixel space (top-left = {@code 0,0}); {@code onDraw(g, w, h)} gets the
 * widget size. UI scaling ({@code UI.scale}) is <b>not</b> applied in 2a — sizes and draw coords are raw
 * pixels (a later slice may add a scale option).
 */
public final class LuaWidget extends Widget {
    private final Addon owner;
    private final LuaValue onDraw, onTick, onClick, onMouseUp, onMouseMove, onWheel;
    private final LuaTable gwrap;   // the GOut draw wrapper `g`; built once, bound per draw
    private Widget root = this;     // the widget to destroy on kill(): the window chrome, or this
    private GOut curg;              // the live GOut during onDraw, else null (wrapper is then inert)
    private boolean dead;           // set on teardown so a late tick/draw callback is a no-op

    LuaWidget(Addon owner, Coord sz, LuaValue opts) {
        super(sz);
        this.owner = owner;
        this.onDraw      = fn(opts, "onDraw");
        this.onTick      = fn(opts, "onTick");
        this.onClick     = fn(opts, "onClick");
        this.onMouseUp   = fn(opts, "onMouseUp");
        this.onMouseMove = fn(opts, "onMouseMove");
        this.onWheel     = fn(opts, "onWheel");
        this.gwrap = buildG();
    }

    /** An optional callback from the opts table, or {@code null} if the key is absent / not a function. */
    private static LuaValue fn(LuaValue opts, String key) {
        LuaValue v = opts.get(key);
        return v.isfunction() ? v : null;
    }

    /** Record the top-level widget that owns this content (a window's chrome) so {@link #kill} removes it. */
    void root(Widget root) {
        this.root = (root != null) ? root : this;
    }

    /** Already torn down? (guards a double kill from close-button + teardown.) */
    boolean dead() {
        return dead;
    }

    /** Mark torn-down (no further callbacks) and remove this widget's root from the tree. Bridge-only. */
    void kill() {
        if(dead)
            return;
        dead = true;
        root.destroy();
    }

    // ---------------------------------------------------------------- lifecycle forwards

    public void tick(double dt) {
        super.tick(dt);
        if(!dead && (onTick != null))
            AddonManager.callLua(owner, onTick, LuaValue.valueOf(dt));
    }

    public void draw(GOut g) {
        if(!dead && (onDraw != null)) {
            curg = g;
            try {
                AddonManager.callLua(owner, onDraw, gwrap, LuaValue.valueOf(sz.x), LuaValue.valueOf(sz.y));
            } finally {
                curg = null;   // invalidate the wrapper outside the callback (no stashing)
            }
        }
        super.draw(g);   // draw any child widgets (none for a leaf; future-proofing)
    }

    public boolean mousedown(MouseDownEvent ev) {
        if(!dead && (onClick != null)
           && AddonManager.callLua(owner, onClick, ci(ev.c.x), ci(ev.c.y), ci(ev.b)).arg1().toboolean())
            return true;   // a truthy return consumes the click (preventDefault)
        return super.mousedown(ev);
    }

    public boolean mouseup(MouseUpEvent ev) {
        if(!dead && (onMouseUp != null)
           && AddonManager.callLua(owner, onMouseUp, ci(ev.c.x), ci(ev.c.y), ci(ev.b)).arg1().toboolean())
            return true;
        return super.mouseup(ev);
    }

    public void mousemove(MouseMoveEvent ev) {
        super.mousemove(ev);
        if(!dead && (onMouseMove != null))
            AddonManager.callLua(owner, onMouseMove, ci(ev.c.x), ci(ev.c.y));
    }

    public boolean mousewheel(MouseWheelEvent ev) {
        if(!dead && (onWheel != null)
           && AddonManager.callLua(owner, onWheel, ci(ev.c.x), ci(ev.c.y), ci(ev.a)).arg1().toboolean())
            return true;
        return super.mousewheel(ev);
    }

    private static LuaValue ci(int v) {
        return LuaValue.valueOf(v);
    }

    // ---------------------------------------------------------------- the GOut draw wrapper `g`

    /**
     * Build the Lua {@code g} wrapper: one table of drawing primitives that operate on {@link #curg}, the
     * GOut live only during the current {@code onDraw}. Each method is a colon-call ({@code g:text(...)})
     * so argument 1 is {@code self}; coercions are forgiving (a bad arg draws garbage rather than throwing).
     * Maps 1:1 to {@link GOut}. Image drawing ({@code g:image}) needs resource/{@code Tex} resolution and
     * is deferred to a later UI slice.
     */
    private LuaTable buildG() {
        LuaTable t = new LuaTable();

        // g:text(str, x, y) — draw text at the top-left of (x, y), client font.
        t.set("text", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                GOut d = curg; if(d == null) return NIL;
                d.text(a.arg(2).tojstring(), Coord.of(a.arg(3).toint(), a.arg(4).toint()));
                return NIL;
            }
        });
        // g:atext(str, x, y, ax, ay) — anchored text (ax/ay 0..1 = which point of the text sits at x,y).
        t.set("atext", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                GOut d = curg; if(d == null) return NIL;
                d.atext(a.arg(2).tojstring(), Coord.of(a.arg(3).toint(), a.arg(4).toint()),
                        a.arg(5).todouble(), a.arg(6).todouble());
                return NIL;
            }
        });
        // g:rect(x, y, w, h) — one-pixel outline rectangle.
        t.set("rect", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                GOut d = curg; if(d == null) return NIL;
                d.rect(Coord.of(a.arg(2).toint(), a.arg(3).toint()),
                       Coord.of(a.arg(4).toint(), a.arg(5).toint()));
                return NIL;
            }
        });
        // g:frect(x, y, w, h) — filled rectangle.
        t.set("frect", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                GOut d = curg; if(d == null) return NIL;
                d.frect(Coord.of(a.arg(2).toint(), a.arg(3).toint()),
                        Coord.of(a.arg(4).toint(), a.arg(5).toint()));
                return NIL;
            }
        });
        // g:line(x1, y1, x2, y2 [, width=1]) — a line.
        t.set("line", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                GOut d = curg; if(d == null) return NIL;
                LuaValue w = a.arg(6);
                d.line(Coord.of(a.arg(2).toint(), a.arg(3).toint()),
                       Coord.of(a.arg(4).toint(), a.arg(5).toint()), w.isnil() ? 1.0 : w.todouble());
                return NIL;
            }
        });
        // g:prect(cx, cy, radius, fraction) — a clockwise pie/progress wedge (0..1) centred on (cx,cy).
        // Ergonomic form of GOut.prect for cooldowns/meters; fraction 1 = full circle.
        t.set("prect", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                GOut d = curg; if(d == null) return NIL;
                int r = a.arg(4).toint();
                d.prect(Coord.of(a.arg(2).toint(), a.arg(3).toint()),
                        Coord.of(-r, -r), Coord.of(r, r), a.arg(5).todouble() * Math.PI * 2.0);
                return NIL;
            }
        });
        // g:color(r, g, b [, a=255]) sets the draw color; g:color() resets to default (white).
        t.set("color", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                GOut d = curg; if(d == null) return NIL;
                LuaValue r = a.arg(2);
                if(r.isnil()) {
                    d.chcolor();
                } else {
                    LuaValue al = a.arg(5);
                    d.chcolor(r.toint(), a.arg(3).toint(), a.arg(4).toint(), al.isnil() ? 255 : al.toint());
                }
                return NIL;
            }
        });
        return t;
    }
}
