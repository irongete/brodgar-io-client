package io.brodgar.addon;

import haven.Coord;
import haven.GOut;
import haven.render.Model;

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
 * drawing ({@code g:image}/{@code g:aimage}, R1) takes a {@code hafen.render.image} handle (a
 * {@link LuaImage}) and blits its {@link haven.TexI}; a nil/typo/disposed image simply draws nothing.
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
        // g:poly(x1,y1, x2,y2, x3,y3, ...) — a FILLED convex polygon (>= 3 points) in the current colour, via a
        // GPU triangle fan. Points are (x,y) pairs after self; fewer than 3 draws nothing. Used for the ghost
        // gizmo's arrow-heads (V5b) — Unity-style filled triangles drawn on the HUD overlay — but generic for any
        // addon. Coordinates go through the GOut translation (tx) exactly like g:line/g:frect, so a poly lines up
        // with lines drawn on the same surface. Not clipped to the widget bounds (like GOut.fellipse); intended
        // for full-screen overlay draw where there is nothing to clip against.
        t.set("poly", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                GOut d = cur; if(d == null) return NIL;
                int npt = (a.narg() - 1) / 2;                    // arg1 = self; then x,y pairs
                if(npt < 3) return NIL;
                float[] data = new float[npt * 2];
                for(int i = 0; i < npt; i++) {
                    data[i * 2]     = (float)(d.tx.x + a.arg(2 + (i * 2)).todouble());
                    data[i * 2 + 1] = (float)(d.tx.y + a.arg(3 + (i * 2)).todouble());
                }
                d.drawp(Model.Mode.TRIANGLE_FAN, data);
                return NIL;
            }
        });
        // g:image(img, x, y)         — draw a hafen.render.image (R1) at its native size, top-left at (x, y).
        // g:image(img, x, y, w, h)   — the same, scaled into a w×h box.
        // `img` is the handle from hafen.render.image; a nil / wrong-type / disposed handle draws nothing (the
        // resolve returns null / the dead guard skips it) — never throws, matching the forgiving g wrapper.
        t.set("image", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                GOut d = cur; if(d == null) return NIL;
                LuaImage img = LuaImage.resolve(a.arg(2));
                if((img == null) || img.dead || (img.tex == null)) return NIL;
                // colon call: arg1 = self, arg2 = img, arg3 = x, arg4 = y, arg5 = w, arg6 = h
                Coord c = Coord.of(a.arg(3).toint(), a.arg(4).toint());
                LuaValue wv = a.arg(5), hv = a.arg(6);
                if(wv.isnumber() && hv.isnumber())
                    d.image(img.tex, c, Coord.of(wv.toint(), hv.toint()));   // scaled → GOut.image(Tex,Coord,Coord)
                else
                    d.image(img.tex, c);                                     // native → GOut.image(Tex,Coord)
                return NIL;
            }
        });
        // g:aimage(img, x, y, ax, ay) — anchored image (ax/ay 0..1 = which point of the image sits at x,y),
        // mirroring g:atext. Same forgiving nil/disposed handling as g:image.
        t.set("aimage", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                GOut d = cur; if(d == null) return NIL;
                LuaImage img = LuaImage.resolve(a.arg(2));
                if((img == null) || img.dead || (img.tex == null)) return NIL;
                d.aimage(img.tex, Coord.of(a.arg(3).toint(), a.arg(4).toint()),
                         a.arg(5).todouble(), a.arg(6).todouble());          // → GOut.aimage(Tex,Coord,ax,ay)
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
