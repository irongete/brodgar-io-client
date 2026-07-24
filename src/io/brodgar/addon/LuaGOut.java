package io.brodgar.addon;

import haven.Coord;
import haven.GOut;

import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;

/**
 * The Lua {@code g} drawing wrapper over {@link GOut} — the single, canonical draw surface shared by
 * <b>every</b> addon draw callback: custom widgets/windows ({@link LuaWidget}, Phase 2a), HUD overlays and
 * world-space gob overlays ({@link LuaGobOverlay}, Phase 2b). It is built once; the live {@code GOut} is
 * bound only for the duration of a single draw callback via {@link #bind}/{@link #unbind}. Outside a draw
 * the wrapper is <b>inert</b> — every method no-ops on a {@code null} target — so an addon that stashes
 * {@code g} in a timer/handler and tries to draw later cannot corrupt the client's draw pipeline.
 *
 * <p>Methods are Lua colon-calls ({@code g:text(...)}), so argument 1 is {@code self} and the real
 * parameters start at {@code arg(2)}; coercions are forgiving (a bad arg draws garbage rather than
 * throwing). Coordinates are the callback's local pixel space (widget-local for a widget, screen for a
 * HUD overlay, the gob's projected screen point for a gob overlay). Maps 1:1 to {@link GOut}. Image
 * drawing ({@code g:image}) needs resource/{@code Tex} resolution and is deferred to a later UI slice.
 */
final class LuaGOut {
    /** The live {@link GOut} during the current draw callback, else {@code null} (the wrapper is then inert). */
    private GOut cur;
    /** The Lua {@code g} table of drawing primitives; built once, its closures read {@link #cur}. */
    private final LuaTable table;

    LuaGOut() {
        this.table = build();
    }

    /** Bind the live {@code GOut} for one draw callback and return the {@code g} table to hand to Lua. */
    LuaTable bind(GOut g) {
        this.cur = g;
        return table;
    }

    /** Invalidate the wrapper after a draw callback (no stashing — see the class note). */
    void unbind() {
        this.cur = null;
    }

    private LuaTable build() {
        LuaTable t = new LuaTable();

        // g:text(str, x, y) — draw text at the top-left of (x, y), client font.
        t.set("text", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                GOut d = cur; if(d == null) return NIL;
                d.text(a.arg(2).tojstring(), Coord.of(a.arg(3).toint(), a.arg(4).toint()));
                return NIL;
            }
        });
        // g:atext(str, x, y, ax, ay) — anchored text (ax/ay 0..1 = which point of the text sits at x,y).
        t.set("atext", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                GOut d = cur; if(d == null) return NIL;
                d.atext(a.arg(2).tojstring(), Coord.of(a.arg(3).toint(), a.arg(4).toint()),
                        a.arg(5).todouble(), a.arg(6).todouble());
                return NIL;
            }
        });
        // g:rect(x, y, w, h) — one-pixel outline rectangle.
        t.set("rect", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                GOut d = cur; if(d == null) return NIL;
                d.rect(Coord.of(a.arg(2).toint(), a.arg(3).toint()),
                       Coord.of(a.arg(4).toint(), a.arg(5).toint()));
                return NIL;
            }
        });
        // g:frect(x, y, w, h) — filled rectangle.
        t.set("frect", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                GOut d = cur; if(d == null) return NIL;
                d.frect(Coord.of(a.arg(2).toint(), a.arg(3).toint()),
                        Coord.of(a.arg(4).toint(), a.arg(5).toint()));
                return NIL;
            }
        });
        // g:line(x1, y1, x2, y2 [, width=1]) — a line.
        t.set("line", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                GOut d = cur; if(d == null) return NIL;
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
                GOut d = cur; if(d == null) return NIL;
                int r = a.arg(4).toint();
                d.prect(Coord.of(a.arg(2).toint(), a.arg(3).toint()),
                        Coord.of(-r, -r), Coord.of(r, r), a.arg(5).todouble() * Math.PI * 2.0);
                return NIL;
            }
        });
        // g:color(r, g, b [, a=255]) sets the draw color; g:color() resets to default (white).
        t.set("color", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                GOut d = cur; if(d == null) return NIL;
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
