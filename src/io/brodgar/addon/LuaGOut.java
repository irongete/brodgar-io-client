package io.brodgar.addon;

import haven.Coord;
import haven.Fonts;
import haven.GOut;
import haven.Indir;
import haven.Loading;
import haven.Resource;
import haven.RichText;
import haven.Tex;
import haven.Text;
import haven.render.Model;

import java.awt.Color;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;

/**
 * The Lua {@code g} drawing wrapper over {@link GOut} — the single, canonical draw surface shared by
 * <b>every</b> addon draw callback: custom widgets/windows ({@link AddonWidget}, Phase 2a), HUD overlays,
 * gob overlays ({@link LuaGobOverlay}, Phase 2b) and what is drawn over one widget
 * ({@link LuaWidgetOverlay}). It is built once; the live {@code GOut} is
 * bound only for the duration of a single draw callback via {@link #bind}/{@link #unbind}. Outside a draw
 * the wrapper is <b>inert</b> — every method no-ops on a {@code null} target — so an addon that stashes
 * {@code g} in a timer/handler and tries to draw later cannot corrupt the client's draw pipeline.
 *
 * <p>Methods are Lua colon-calls ({@code g:text(...)}), so argument 1 is {@code self} and the real
 * parameters start at {@code arg(2)}; coercions are forgiving (a bad arg draws garbage rather than
 * throwing). Coordinates are the callback's local pixel space (widget-local for a widget, screen for a
 * HUD overlay, the gob's projected screen point for a gob overlay). Maps 1:1 to {@link GOut}.
 *
 * <p><b>Every length here is a DESIGN pixel</b> (058.2) — a coordinate, a width, a height, a line's stroke
 * and a wedge's radius alike — so the rectangle an addon draws is the rectangle it laid out, in the very
 * numbers {@code widget:size()} and {@code ev:w()} answer in. {@link Px} converts at each read, and nowhere
 * else: {@link GOut} itself is device-space throughout, and so is everything below this wrapper.
 *
 * <p><b>An addon's own PNG is design-sized too.</b> {@code g:image}/{@code g:aimage} (R1) take a
 * {@code hafen.asset} image handle (a {@link LuaImage}) and blit its <b>scaled</b> texture
 * ({@link LuaImage#stex}, a {@link haven.ScaledTex} over the raw {@link haven.TexI}), so a 16&times;16 icon
 * covers 16 design pixels beside the client's own 16-design-pixel art at every scale — the rule
 * {@code hafen.vr}'s screen sprites already follow. A nil/typo/disposed image simply draws nothing.
 * {@code g:resource} is the trap in the other direction: an engine {@code .res} texture comes from
 * {@code Resource.Image.scaled()} and is <b>already</b> device-sized, so only its explicit {@code w, h} box
 * converts and the blit itself does not.
 */
final class LuaGOut {
    /**
     * The engine-resource cache for {@code g:resource(name, ...)} (D-039): resource name &rarr; its
     * {@link Indir}, so a per-frame draw does not re-issue the lookup. The referenced resources are the
     * client's own global engine resources (shared via {@link Resource#remote()}, which caches them anyway),
     * so this map holds only lightweight refs and leaks nothing; it is cleared on {@code :reload} for
     * faithfulness (see {@link #clearResourceCache}).
     */
    private static final ConcurrentHashMap<String, Indir<Resource>> resCache = new ConcurrentHashMap<String, Indir<Resource>>();

    /** Drop the {@code g:resource} name cache (called from a full {@code :reload}). */
    static void clearResourceCache() {
        resCache.clear();
    }

    /**
     * The stock {@link RichText.Foundry} (F2): {@link Text#std}'s family/size, glyphs WHITE, {@code aa} off — used
     * when a {@code g:text} string carries {@code $font}/markup but no explicit font handle, so a {@code $font[…]}
     * tag still resolves. Built lazily (most draws never hit the rich path); {@link Text#std}'s font is already
     * {@code UI.scale}d and never changes, so this needs no invalidation.
     */
    private static RichText.Foundry stockRich;
    private static synchronized RichText.Foundry stockRich() {
        if(stockRich == null)
            stockRich = new RichText.Foundry(Text.std.font, Color.WHITE).aa(Text.std.aa);
        return stockRich;
    }

    /* ---- the rendered-Text cache (026.1) ------------------------------------------------------------------
     *
     * `g:text`/`g:atext` are immediate-mode, and both paths used to end render -> tex() -> blit -> dispose EVERY
     * FRAME (the fast path inside GOut.atext, the rich path explicitly) -- ~0.28 ms per line (019.8), i.e. ~50x
     * the cost of geometry. A client Label dodges that by HOLDING its rendered Text and rebuilding only when
     * Fonts.gen() moves; an addon cannot, because it has no handle to hold. So the wrapper holds it for them:
     * a per-addon, content-keyed, bounded LRU of the rendered Text + its Tex. No API change, no addon edit.
     *
     * Deliberately NOT in the key: the COLOUR. blitText applies it as a chcolor tint around the blit and the rich
     * path rasterises glyphs white (F2), so one string in two colours is ONE entry.
     *
     * Deliberately IN the key: Fonts.gen(). The plan called for one `cachegen` compared against it, dropping
     * everything when it moved -- the Label model. That is wrong HERE, because Fonts.gen() is not a frame-global:
     * while a per-widget style frame is open (F5, `widget:rule()`, Widget.draw's child loop) it carries that
     * style's stamp, so it differs BETWEEN draw sites within a single frame. A drop-on-move cache would then
     * clear itself on every alternation -- worse than no cache. As a key component it costs the same one int and
     * is correct under F5: an override install/move/reset simply lands on fresh keys and the stale generation's
     * entries fall out of the LRU. (An addon that draws text under a per-widget style override is exactly this
     * case, so it is not hypothetical.)
     */
    static final class Cache {
        /*
         * The caps, TUNED IN 026.2 against a measured full cache (see plan.md). Both must be able to bind: a
         * cache of many tiny labels should hit MAXENTRIES first, one of few wide lines MAXBYTES first.
         *
         * The measurement: a full cache of ordinary HUD text is 512 entries / 7.91 MiB, i.e. ~15.8 KiB an
         * entry (a ~256x16 texture rounded up to powers of two). At that width the provisional 16 MiB could
         * NEVER be reached before the entry cap -- it was decoration rather than a bound -- so it comes down
         * to 8 MiB, where the two caps meet almost exactly at the measured average width. Narrow text is then
         * bounded by count, wide text by bytes, which is the whole reason for having two.
         *
         * MAXENTRIES STAYS at 512, deliberately above the working set it needs to hold (a text-heavy addon's is
         * ~40 lines a frame; the 512 it fills to are overwhelmingly dead strings from a volatile line, which
         * are never looked up again). Headroom is not waste here: a cap BELOW one frame's distinct strings
         * would evict every entry before its next use, paying eviction and dispose on top of the rasterisation
         * it failed to save -- strictly worse than no cache. 512 keeps a text-heavy addon well clear of that
         * cliff, and the byte cap bounds what the headroom can cost.
         *
         * Package-visible because `hafen.client:profiling():textcache()` reports what the cache is bounded BY
         * beside what it is holding (026.2) — a count means nothing without its ceiling.
         */
        static final int  MAXENTRIES = 512;
        static final long MAXBYTES   = 8L << 20;    // 8 MiB of GL texture

        /** The LRU itself, in ACCESS order (`true`) so `removeEldest` below is genuinely least-recently-used. */
        private final Map<Key, Entry> live = new LinkedHashMap<Key, Entry>(64, 0.75f, true);
        private long bytes;
        /* Pull-only counters for hafen.client:profiling():textcache() (026.2). Plain longs, UI thread only —
         * cumulative since this addon loaded (the Cache is a final field of the Addon, so a :reload starts a
         * fresh count along with a fresh cache). */
        private long hits, misses, evictions;

        /** The cached {@link Tex} for {@code k}, or {@code null} (a miss — the caller renders and {@link #put}s). */
        synchronized Tex get(Key k) {
            Entry e = live.get(k);
            if(e == null) {
                misses++;
                return null;
            }
            hits++;
            return e.tex;
        }

        /** Adopt a freshly rendered {@code t}/{@code tex} under {@code k}, then evict down to the caps. */
        synchronized void put(Key k, Text t, Tex tex) {
            Coord sz = tex.sz();
            long b = 4L * Tex.nextp2(sz.x) * Tex.nextp2(sz.y);
            Entry old = live.put(k, new Entry(t, tex, b));
            bytes += b;
            if(old != null) {                       // cannot normally happen (we only put on a miss), but stay exact
                bytes -= old.bytes;
                old.text.dispose();
            }
            trim();
        }

        /** Drop the least-recently-used entries until both caps hold, disposing each. Never drops the newcomer. */
        private void trim() {
            while(((live.size() > MAXENTRIES) || (bytes > MAXBYTES)) && (live.size() > 1)) {
                Iterator<Map.Entry<Key, Entry>> it = live.entrySet().iterator();
                Entry e = it.next().getValue();     // access-order head = least recently used
                it.remove();
                bytes -= e.bytes;
                e.text.dispose();
                evictions++;
            }
        }

        /** Drop and dispose everything (teardown: disable / {@code :reload} / session init). */
        synchronized void clear() {
            for(Entry e : live.values())
                e.text.dispose();
            live.clear();
            bytes = 0;
        }

        /** Live entry count / total texture bytes / the lifetime counters — the 026.2 readers. */
        synchronized int entries()     {return live.size();}
        synchronized long bytes()      {return bytes;}
        synchronized long hits()       {return hits;}
        synchronized long misses()     {return misses;}
        synchronized long evictions()  {return evictions;}
    }

    /**
     * A cache key: the string, the {@link FontHandle} it renders through (by IDENTITY — handles are immutable and
     * interned per load), the <b>wrap width</b> it was laid out at ({@code 0} = one line) and the
     * {@link Fonts#gen()} in force at the draw. Whether the string takes the fast or the rich path is a pure
     * function of {@code (str, font, width)}, so the path is not a separate component.
     *
     * <p><b>The width has to be in here</b> (110.4). The raster IS the wrap: the same string at two widths is two
     * different rasters, so a key without the width blits the first one at the second width — the re-wrap that
     * silently never happens. It is the same one-int argument the generation is, and it costs the same nothing.
     */
    static final class Key {
        private final String str;
        private final FontHandle font;
        private final int width;
        private final int gen;
        private final int hash;

        Key(String str, FontHandle font, int width, int gen) {
            this.str = str;
            this.font = font;
            this.width = width;
            this.gen = gen;
            this.hash = ((str.hashCode() * 31 + System.identityHashCode(font)) * 31 + width) * 31 + gen;
        }

        public int hashCode() {return hash;}
        public boolean equals(Object o) {
            if(!(o instanceof Key))
                return false;
            Key k = (Key)o;
            return (k.hash == hash) && (k.gen == gen) && (k.width == width) && (k.font == font) && k.str.equals(str);
        }
    }

    /** One cached rendering. We are the ONLY disposer of this {@link Text} (a double dispose leaks, see plan). */
    private static final class Entry {
        final Text text;
        final Tex  tex;
        final long bytes;

        Entry(Text text, Tex tex, long bytes) {
            this.text = text;
            this.tex = tex;
            this.bytes = bytes;
        }
    }

    /** Teardown (disable / {@code :reload}): drop this addon's cached text and free its GL textures. */
    static void teardownTexts(Addon a) {
        if(a != null)
            a.texts.clear();
    }

    /** The live {@link GOut} during the current draw callback, else {@code null} (the wrapper is then inert). */
    private GOut cur;
    /**
     * The addon whose draw callback is running (026.1) — the owner of the {@link Cache} {@code g:text} renders
     * through. Set per draw callback beside {@link #cur}; {@code null} only defensively (then every draw
     * re-rasterises, the pre-026 behaviour).
     */
    private Addon owner;
    /**
     * The widget's default font (F2), from {@code widget:font(h)} — the base font
     * for {@code g:text}/{@code g:atext} when a call gives no per-call {@code opts.font}. {@code null} for a widget
     * with no {@code font=}, and always {@code null} for a HUD/gob overlay (they {@link #bind(GOut)} without one).
     * Set per draw callback beside {@link #cur}.
     */
    private FontHandle defFont;
    /** The Lua {@code g} table of drawing primitives; built once, its closures read {@link #cur}. */
    private final LuaTable table;

    LuaGOut() {
        this.table = build();
    }

    /**
     * Bind the live {@code GOut} for one draw callback of {@code owner} (no widget default font) and return the
     * {@code g} table. The owner is the text cache's (026.1) — every draw site knows it, so it is not optional.
     */
    LuaTable bind(GOut g, Addon owner) {
        return bind(g, owner, null);
    }

    /**
     * Bind the live {@code GOut} for one draw callback with a widget default font ({@code null} = none) and return
     * the {@code g} table to hand to Lua. The default font is the base for {@code g:text}/{@code g:atext} calls
     * that pass no per-call {@code opts.font} (F2); {@code owner} owns the rendered-text cache (026.1).
     */
    LuaTable bind(GOut g, Addon owner, FontHandle def) {
        this.cur = g;
        this.owner = owner;
        this.defFont = def;
        return table;
    }

    /** Invalidate the wrapper after a draw callback (no stashing — see the class note). */
    void unbind() {
        this.cur = null;
        this.owner = null;
        this.defFont = null;
    }

    private LuaTable build() {
        LuaTable t = new LuaTable();

        // g:text(str, x, y [, opts]) — draw text at the top-left of (x, y).
        //   opts (all optional, F2): { font = h, color = {r,g,b[,a]} }.
        //     font  = a font handle (hafen.asset("fonts/X.ttf") / hafen.font("mono")) -> render str in THAT font (else the widget's font= default, else
        //             the client stock). Own-widget drawing is isolated (no global state touched).
        //     color = {r,g,b[,a]} 0..255 -> tint the glyphs that colour (like g:color around the call); omitted =>
        //             white glyphs tinted by the current g:color (the stock behaviour).
        //   str may carry RICH-TEXT MARKUP: $font[family,sz]{…} (feed h:family() to mix fonts on ONE line — the
        //   F2 headline), $col[r,g,b,a]{…}, $b{…}/$i{…}/$u{…}, $size[sz]{…}. Plain text with no font=/markup takes
        //   the exact stock path (zero change). Malformed markup falls back to the literal string (never throws).
        t.set("text", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                GOut d = cur; if(d == null) return NIL;
                drawText(d, a.arg(2).tojstring(), Px.in(Coord.of(a.arg(3).toint(), a.arg(4).toint())),
                         0.0, 0.0, a.arg(5), "g:text");
                return NIL;
            }
        });
        // g:atext(str, x, y, ax, ay [, opts]) — anchored text (ax/ay 0..1 = which point of the text sits at x,y).
        // opts is the same F2 table as g:text (font = h, color = {r,g,b[,a]}); markup works identically.
        t.set("atext", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                GOut d = cur; if(d == null) return NIL;
                drawText(d, a.arg(2).tojstring(), Px.in(Coord.of(a.arg(3).toint(), a.arg(4).toint())),
                         a.arg(5).todouble(), a.arg(6).todouble(), a.arg(7), "g:atext");
                return NIL;
            }
        });
        // g:rect(x, y, w, h) — one-pixel outline rectangle. The OUTLINE stays one DEVICE pixel (GOut.rect is a
        // LINE_STRIP at the default width), exactly like the client's own hairlines; the box it traces is design.
        t.set("rect", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                GOut d = cur; if(d == null) return NIL;
                d.rect(Px.in(Coord.of(a.arg(2).toint(), a.arg(3).toint())),
                       Px.in(Coord.of(a.arg(4).toint(), a.arg(5).toint())));
                return NIL;
            }
        });
        // g:frect(x, y, w, h) — filled rectangle.
        t.set("frect", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                GOut d = cur; if(d == null) return NIL;
                d.frect(Px.in(Coord.of(a.arg(2).toint(), a.arg(3).toint())),
                        Px.in(Coord.of(a.arg(4).toint(), a.arg(5).toint())));
                return NIL;
            }
        });
        // g:line(x1, y1, x2, y2 [, width=1]) — a line. The WIDTH is a design pixel too (it is a length, and a
        // 4 px rule drawn half as thick as the chrome beside it is the very mismatch this unit exists to end).
        t.set("line", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                GOut d = cur; if(d == null) return NIL;
                LuaValue w = a.arg(6);
                d.line(Px.in(Coord.of(a.arg(2).toint(), a.arg(3).toint())),
                       Px.in(Coord.of(a.arg(4).toint(), a.arg(5).toint())),
                       Px.in(w.isnil() ? 1.0 : w.todouble()));
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
                for(int i = 0; i < npt; i++) {      // design px, unrounded: a vertex reaches the GPU as a float
                    data[i * 2]     = (float)(d.tx.x + Px.in(a.arg(2 + (i * 2)).todouble()));
                    data[i * 2 + 1] = (float)(d.tx.y + Px.in(a.arg(3 + (i * 2)).todouble()));
                }
                d.drawp(Model.Mode.TRIANGLE_FAN, data);
                return NIL;
            }
        });
        // g:image(img, x, y)         — draw a hafen.asset image (R1) at its native size, top-left at (x, y).
        // g:image(img, x, y, w, h)   — the same, scaled into a w×h box.
        // `img` is the handle from hafen.asset("icon.png"); a nil / wrong-type / disposed handle draws nothing (the
        // resolve returns null / the dead guard skips it) — never throws, matching the forgiving g wrapper.
        t.set("image", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                GOut d = cur; if(d == null) return NIL;
                LuaImage img = LuaImage.resolve(a.arg(2));
                if((img == null) || img.dead || (img.tex == null)) return NIL;
                // colon call: arg1 = self, arg2 = img, arg3 = x, arg4 = y, arg5 = w, arg6 = h
                Coord c = Px.in(Coord.of(a.arg(3).toint(), a.arg(4).toint()));
                LuaValue wv = a.arg(5), hv = a.arg(6);
                // The PNG's own pixels are design pixels (058.2): img.stex blits it at UI scale, so the native
                // form covers exactly the img:size() the addon read, and the w×h form covers exactly that box.
                if(wv.isnumber() && hv.isnumber())
                    d.image(img.stex, c, Px.in(Coord.of(wv.toint(), hv.toint())));   // → GOut.image(Tex,Coord,Coord)
                else
                    d.image(img.stex, c);                                            // → GOut.image(Tex,Coord)
                return NIL;
            }
        });
        // g:resource(name, x, y)         — draw an ENGINE .res image BY NAME at its native size, top-left at (x,y).
        // g:resource(name, x, y, w, h)   — the same, scaled into a w×h box.
        // The sibling of g:image: g:image draws the addon's OWN PNGs (hafen.asset, R1), g:resource draws
        // the client's own .res art (action icons, hud pieces) — e.g. the `res` a widget receives from onDrop
        // (D-038). The name is resolved ASYNC + cached (one Indir per name) and the draw is Loading-GUARDED: it
        // draws nothing until the texture is ready, then blits the default image layer (Resource.imgc) — the
        // client's own idiom (cf. MenuGrid.draw swallowing Loading). A bad name / load error simply draws nothing
        // (never throws into the render thread). Static only — no live sprite / cooldown sweep (D-039).
        t.set("resource", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                GOut d = cur; if(d == null) return NIL;
                String name = a.arg(2).tojstring();
                if((name == null) || name.isEmpty()) return NIL;
                Tex tex = resTex(name);
                if(tex == null) return NIL;   // still Loading / failed → draw nothing this frame
                Coord c = Px.in(Coord.of(a.arg(3).toint(), a.arg(4).toint()));
                LuaValue wv = a.arg(5), hv = a.arg(6);
                // NOT img.stex's counterpart: an engine texture is Resource.Image.scaled() and is ALREADY device
                // -sized, so the native blit converts nothing and only the explicit box does (058.2).
                if(wv.isnumber() && hv.isnumber())
                    d.image(tex, c, Px.in(Coord.of(wv.toint(), hv.toint())));   // scaled
                else
                    d.image(tex, c);                                            // native
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
                d.aimage(img.stex, Px.in(Coord.of(a.arg(3).toint(), a.arg(4).toint())),
                         a.arg(5).todouble(), a.arg(6).todouble());          // → GOut.aimage(Tex,Coord,ax,ay)
                return NIL;
            }
        });
        // g:prect(cx, cy, radius, fraction) — a clockwise pie/progress wedge (0..1) centred on (cx,cy).
        // Ergonomic form of GOut.prect for cooldowns/meters; fraction 1 = full circle.
        t.set("prect", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                GOut d = cur; if(d == null) return NIL;
                int r = Px.in(a.arg(4).toint());     // a radius is a length: design px, like everything here
                d.prect(Px.in(Coord.of(a.arg(2).toint(), a.arg(3).toint())),
                        Coord.of(-r, -r), Coord.of(r, r), a.arg(5).todouble() * Math.PI * 2.0);
                return NIL;
            }
        });
        // g:color(r, g, b [, a=255]) sets the draw color; g:color() resets to default (white). THE ONE PLACE a
        // colour is also loose components: they are the language of every verb on this table (g:line(x,y,x,y),
        // g:frect(x,y,w,h)), and the exception is written down on the shapes page rather than left as a habit.
        // A colour TABLE is taken too, so g:color(kin:color()) draws that colour -- toint() on a table is 0 in
        // LuaJ, not an error, so without this branch a value the API just handed you drew BLACK.
        t.set("color", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                GOut d = cur; if(d == null) return NIL;
                LuaValue r = a.arg(2);
                if(r.isnil()) {
                    d.chcolor();
                } else if(r.istable()) {
                    Color c = AddonManager.luaColor(r, null);
                    if(c == null)
                        throw new LuaError(AddonManager.colorRefusal("g:color"));
                    d.chcolor(c);
                } else {
                    LuaValue al = a.arg(5);
                    d.chcolor(r.toint(), a.arg(3).toint(), a.arg(4).toint(), al.isnil() ? 255 : al.toint());
                }
                return NIL;
            }
        });
        return t;
    }

    /**
     * The shared implementation of {@code g:text}/{@code g:atext} (F2). Resolves the font (per-call
     * {@code opts.font} &rarr; the widget {@link #defFont} default &rarr; stock) and an optional colour tint, then:
     * <ul>
     *   <li><b>fast path</b> — no font and no {@code $} markup: the exact stock render ({@link GOut#atext}), which
     *       already routes through the F1 {@code "default"} provider — zero behaviour change for existing addons;</li>
     *   <li><b>rich path</b> — a font handle or {@code $}-markup: render through a {@link RichText.Foundry} (the
     *       handle's cached one, or {@link #stockRich()}), so {@code $font[family,sz]{…}} and the other rich tags
     *       resolve. Glyphs are WHITE; the colour (or a bare {@code g:color}) tints on blit.</li>
     * </ul>
     * A colour {@code opts.color} (else the handle's load-time colour) is applied as a temporary draw colour around
     * the blit (saved/restored) so it composes exactly like {@code g:color}. Malformed markup falls back to the
     * literal string via the fast path — never throwing into the render thread (the forgiving {@code g} contract).
     *
     * <p><b>Both paths now go through the {@link Cache}</b> (026.1). The fast path therefore no longer calls
     * {@link GOut#atext}: it does that method's own three lines ({@link Text#render(String)} &rarr; {@code tex()}
     * &rarr; {@code aimage}) minus the {@code dispose()}, so it is behaviour-identical by construction rather than
     * by inspection. On a miss we render, adopt the {@link Text} into the cache and blit it; on a hit we blit the
     * held {@link Tex}. Nothing else about the call changes — same anchors, same tint, same fallback.
     */
    private void drawText(GOut d, String str, Coord c, double ax, double ay, LuaValue opts, String verb) {
        if(str == null)
            return;
        boolean hasOpts = (opts != null) && opts.istable();
        FontHandle fh = hasOpts ? FontHandle.resolve(opts.get("font")) : null;
        if(fh == null)
            fh = defFont;
        Color col = null;
        if(hasOpts) {
            LuaValue cv = opts.get("color");
            if(cv.istable())
                col = AddonManager.luaColor(cv, null);
        }
        if((col == null) && (fh != null))
            col = fh.color;
        draw0(d, str, c, ax, ay, fh, col, null, optWidth(opts, verb));   // c is already device (058.2)
    }

    /**
     * The {@code width} an {@code opts} table carries, in DEVICE pixels, or {@code 0} when it names none —
     * shared by {@code g:text}/{@code g:atext} and {@code hafen.ui():measure} so that one table means one thing
     * wherever it is handed in (110.4). A width is a length, so it is read as a design pixel like every other
     * length here.
     *
     * <p><b>A width of {@code 0} is refused rather than read as "one line"</b>, and so is a negative one. Zero is
     * the engine's own spelling for "do not wrap", but it is also what an addon's own arithmetic produces when it
     * subtracts a padding from a width it has not measured yet — and a line that silently ran off the end of its
     * box is exactly the failure a wrap is asked for. Omitting the key entirely is the way to say one line, and
     * the refusal says so.
     */
    static int optWidth(LuaValue opts, String verb) {
        if((opts == null) || !opts.istable())
            return 0;
        LuaValue v = opts.get("width");
        if(v.isnil())
            return 0;
        int w = Args.num(v, verb, "opts.width", "the design-pixel width to wrap at").toint();
        if(w <= 0)
            throw new LuaError(verb + ": opts.width must be a positive number of design pixels, got " + w
                + " — omit width entirely to draw one unwrapped line");
        return Px.in(w);
    }

    /** The cached render-and-blit itself, shared by {@link #drawText} and the Java-side {@link #label}. */
    private void draw0(GOut d, String str, Coord c, double ax, double ay, FontHandle fh, Color col, Color bg,
                       int width) {
        Cache cache = (owner != null) ? owner.texts : null;
        Key k = (cache != null) ? new Key(str, fh, width, Fonts.gen()) : null;
        Tex T = (cache != null) ? cache.get(k) : null;
        if(T != null) {                                    // hit: blit the Text we already hold
            fill(d, T, c, ax, ay, bg);
            blitText(d, T, c, ax, ay, col);
            return;
        }
        Text t = render(str, fh, width);                   // miss: rasterise once (fast or rich path, per the key)
        T = t.tex();
        if(cache != null) {
            cache.put(k, t, T);                            // the cache owns it from here — it is the only disposer
            fill(d, T, c, ax, ay, bg);
            blitText(d, T, c, ax, ay, col);
        } else {                                           // no owner (defensive): the pre-026 per-frame lifecycle
            try {
                fill(d, T, c, ax, ay, bg);
                blitText(d, T, c, ax, ay, col);
            } finally {
                t.dispose();
            }
        }
    }

    /**
     * The box {@code str} occupies, in DEVICE pixels — {@code hafen.ui():measure(s, opts)} (110.4), and the
     * whole of it: the same {@link #render} at the same {@link Key}, through the same {@link Cache}, outside any
     * draw callback. What comes back is the <b>drawn</b> box and not a second opinion about it, because it is
     * measured off the very raster a {@code g:text} of that string in that font at that width would blit — and
     * because it lands in the cache under that call's own key, the measure and the draw that follows it
     * rasterise <b>once between them</b>.
     *
     * <p>Free of GL: {@link Text#tex()} wraps the AWT raster in a {@code TexI}, which uploads lazily on its first
     * render, so nothing here needs a bound context and a measure that is never drawn costs a texture that is
     * never uploaded.
     */
    static Coord measure(Addon owner, String str, FontHandle fh, int width) {
        Cache cache = (owner != null) ? owner.texts : null;
        Key k = (cache != null) ? new Key(str, fh, width, Fonts.gen()) : null;
        Tex T = (cache != null) ? cache.get(k) : null;
        if(T != null)
            return T.sz();
        Text t = render(str, fh, width);
        T = t.tex();
        if(cache != null) {
            cache.put(k, t, T);                            // the cache owns it from here, exactly as a draw's does
            return T.sz();
        }
        Coord sz = T.sz();                                 // no owner (defensive): measure and drop it again
        t.dispose();
        return sz;
    }

    /**
     * Draw a plain label from JAVA, through the very cache {@code g:text} uses (038.1). It is the
     * {@code {text = …}} half of {@code gob:overlay}: an engine-drawn overlay never enters Lua, so it cannot
     * call {@code g:text} itself, and {@link GOut#atext} would re-rasterise and re-upload the string every
     * frame — the pre-026 cost, one label at a time. Same key, same LRU, same owner: a {@code text} overlay and
     * a {@code g:text} of the same string in the same addon are ONE entry.
     */
    void label(GOut d, String str, Coord c, double ax, double ay, Color col) {
        draw0(d, str, c, ax, ay, null, col, null, 0);   // a client-drawn label is one line: no wrap width
    }

    /**
     * The same label, in a font of the addon's own and over a filled background — the {@code :text(s)} half of
     * {@code widget:overlay()} (103.4), which is dressed where a gob's label is not. Both extras are free of
     * the cache: {@code fh} is already a key component (a label and a {@code g:text} of the same string in the
     * same font are ONE entry), and the background is a {@code frect} <b>behind</b> the raster, measured off
     * the very {@link Tex} the cache handed back — so a colour that changes every frame still costs no
     * rasterisation, exactly as the tint does.
     */
    void label(GOut d, String str, Coord c, double ax, double ay, FontHandle fh, Color col, Color bg) {
        draw0(d, str, c, ax, ay, fh, col, bg, 0);   // one line, as the gob's label above is
    }

    /**
     * Fill {@code bg} behind a rendered text {@link Tex} about to be blitted at {@code c} with anchor
     * {@code ax,ay} — the label's own box and not a pixel more, which is why the shift is {@link GOut#aimage}'s
     * own arithmetic rather than a second guess at it. Nothing is drawn when there is no background.
     */
    private static void fill(GOut d, Tex tex, Coord c, double ax, double ay, Color bg) {
        if(bg == null)
            return;
        Coord sz = tex.sz();
        Coord ul = c.add((int)((double)sz.x * -ax), (int)((double)sz.y * -ay));
        Color save = d.getcolor();
        d.chcolor(bg);
        try {
            d.frect(ul, sz);
        } finally {
            d.chcolor(save);
        }
    }

    /**
     * Rasterise one {@code g:text} string, choosing the path exactly as before (026.1): no font handle, no
     * {@code $} markup and no wrap width &rarr; the stock {@link Text#render(String)} (the body of
     * {@link GOut#atext}, which routes through the F1 {@code "default"} provider); otherwise a
     * {@link RichText.Foundry} — the handle's cached one ({@link FontHandle#rich}; never {@code derive}, see
     * fonts.md) or {@link #stockRich()}. Malformed markup falls back to the literal string, so it lands in the
     * cache under the same key that produced it and never throws into the render thread.
     *
     * <p><b>A width forces the rich path</b> (110.4), and there is no choice about it: {@link Text.Foundry} can
     * lay a string out on one line and nothing else, so the client's own wrap
     * ({@link RichText.Foundry#render(String, int)}, what {@code Text.Foundry.renderwrap} reaches for too) is the
     * only thing that wraps. The two paths measure a line differently — the stock one takes the font's full line
     * height, the rich one the glyphs' own bounds — so a wrapped raster is a couple of pixels shorter per line
     * than the same string unwrapped. That is the drawn raster in both cases, which is what
     * {@link #measure} has to answer about.
     */
    private static Text render(String str, FontHandle fh, int width) {
        if((width <= 0) && (fh == null) && (str.indexOf('$') < 0))
            return Text.render(str);
        RichText.Foundry f = (fh != null) ? fh.rich(Text.std.font.getSize()) : stockRich();
        try {
            return f.render(str, width);                   // width 0 = single line, no wrap ($font/$col/… honoured)
        } catch(RuntimeException e) {                      // malformed markup ($/{}/\) → draw it literally, never throw
            if(width > 0) {
                // ...but a WRAPPED call still has to come back inside its box (110.4), so the literal string is
                // quoted and laid out at the width rather than run off the end of it on one line.
                try {
                    return f.render(RichText.Parser.quote(str), width);
                } catch(RuntimeException e2) {
                }
            }
            return Text.render(str);
        }
    }

    /**
     * Blit a rendered text {@link Tex} at {@code c} with anchor {@code ax,ay}, under an optional colour tint
     * ({@code col}) saved/restored around the draw — the tail of {@link GOut#atext} (its {@code aimage}), which is
     * why the colour is not part of the cache key: it is applied HERE, per call, on a shared white raster.
     */
    private static void blitText(GOut d, Tex tex, Coord c, double ax, double ay, Color col) {
        Color save = (col != null) ? d.getcolor() : null;
        if(col != null)
            d.chcolor(col);
        try {
            d.aimage(tex, c, ax, ay);
        } finally {
            if(save != null)
                d.chcolor(save);
        }
    }

    /**
     * Resolve an engine resource's default image-layer texture by name for {@code g:resource}, async + cached
     * + {@code Loading}-guarded (D-039): returns {@code null} until the resource is loaded (draw nothing this
     * frame) or on any load failure (a bad name never throws into the render thread), else the layer's
     * {@link Tex}. The {@link Indir} is cached by name (dedups the per-frame lookup); the {@code Tex} itself is
     * cached by the engine's {@code Resource.Image}.
     */
    private static Tex resTex(String name) {
        try {
            Indir<Resource> ind = resCache.get(name);
            if(ind == null) {
                ind = Resource.remote().load(name);
                resCache.put(name, ind);
            }
            Resource res = ind.get();               // throws Loading until ready
            Resource.Image img = res.layer(Resource.imgc);
            return (img == null) ? null : img.tex();
        } catch(Loading l) {
            return null;                            // still resolving → draw nothing this frame
        } catch(RuntimeException e) {
            return null;                            // bad name / load error → draw nothing, never throw
        }
    }
}
