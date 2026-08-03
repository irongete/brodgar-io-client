package io.brodgar.addon;

import haven.Coord;
import haven.GOut;
import haven.IBox;
import haven.Tex;
import haven.TexSI;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;

import java.awt.Color;

/**
 * The <b>chrome</b> properties of a stylesheet rule (spec {@code 035-ui-chrome}, feature C2): {@code bg} and
 * {@code border}, which paint, and {@code pad}, which <b>moves</b>. Everything C1 shipped is text — {@code font}
 * and {@code color}; these are the first properties that put pixels of their own on a surface, and the first that
 * can change where the client's own content sits.
 *
 * <pre>
 *   hafen.ui.skin{
 *     ["window.frame"] = { bg     = { color = {26, 26, 28, 240} },
 *                          border = { image = hafen.asset("img/panel.png"), slice = {8, 8, 8, 8} },
 *                          pad    = 6 },
 *   }
 * </pre>
 *
 * <p><b>Plain data, parsed once.</b> A rule carries no Lua and no callback: a {@link Bg} is a colour <i>or</i> an
 * image, a {@link Border} is an image plus its 9-slice insets, and the engine paints from that — the per-frame
 * Lua {@code Deco} callback was rejected on cost at design time (a frame is redrawn every frame with no raster
 * cache to amortise it) and stays rejected. It is also what lets a theme be a {@code theme.json} rather than
 * code: every field here is a number, a string or an asset handle.
 *
 * <p><b>Where they resolve is the sheet's business, not this class's.</b> These values ride the same cascade
 * {@code font} and {@code color} do — folded per property by {@code Fonts.combine} (D-076), carried by a site
 * rule ({@code "window.frame"}) or a tree rule / {@code widget:skin} alike — and this class only says what one
 * of them <i>is</i> and how it paints. The one consumer in 035.1 is {@link SkinDeco}, the sheet-fed window
 * chrome; 035.3 adds the window-less {@code IBox} panels through the very {@link Border#box} built here.
 *
 * <p><b>A border costs no texture.</b> Its nine slices are {@link TexSI} views over the addon's <i>one</i>
 * uploaded image, so a border neither uploads a second copy nor owns anything to dispose — the image's own
 * {@code :dispose()}/teardown is still the whole lifetime, and a disposed image simply stops painting.
 */
final class Chrome {
    private Chrome() {}

    // ---- bg ----------------------------------------------------------------------------------------

    /**
     * A rule's {@code bg}: a flat colour <b>or</b> a tiled image, never both (one canonical way per operation).
     * Immutable, with value equality — the resolved style is interned on it ({@code Sheet.SKey}), and two rules
     * that say the same thing must intern to the same style or every routed site inside them rebuilds.
     */
    static final class Bg {
        /** The flat fill, or {@code null} when this is an image background. */
        final Color color;
        /** The tiled image, or {@code null} when this is a colour background. */
        final LuaImage image;

        Bg(Color color, LuaImage image) {
            this.color = color;
            this.image = image;
        }

        /** Paint this background over {@code [ul, ul+sz)} — a filled rect, or the image tiled and clipped. */
        void draw(GOut g, Coord ul, Coord sz) {
            if(color != null) {
                g.chcolor(color);
                g.frect(ul, sz);
                g.chcolor();
            } else if((image != null) && !image.dead) {
                g.rimage(image.tex, ul, sz);
            }
        }

        public int hashCode() {
            return ((color == null) ? 0 : color.hashCode()) + (System.identityHashCode(image) * 31);
        }

        public boolean equals(Object o) {
            if(!(o instanceof Bg))
                return false;
            Bg b = (Bg)o;
            return (image == b.image) && ((color == null) ? (b.color == null) : color.equals(b.color));
        }

        /** {@code widget:style().bg} — the value as {@code reader} may hold it. */
        LuaValue toLua(Addon reader) {
            LuaTable t = new LuaTable();
            if(color != null)
                t.set("color", AddonManager.color(color));
            else if(image != null)
                t.set("image", AssetApi.imageFor(reader, image));
            return t;
        }
    }

    // ---- border ------------------------------------------------------------------------------------

    /**
     * A rule's {@code border}: one image plus the four insets that cut it into a 9-slice — the corners are drawn
     * at their own size and the four edges stretch between them, which is exactly what {@link IBox} already means
     * in this engine ({@code IBox.Scaled}, the window-less panels' own border). The centre is <b>not</b> painted:
     * that is {@link Bg}'s job, so the two properties compose instead of overwriting each other.
     *
     * <p>The insets are in the image's own pixels and are <b>not</b> UI-scaled: an addon's PNG draws at its
     * natural size everywhere else in this API ({@code g:image}, {@code render.sprite}), and a border that
     * silently grew on a hi-dpi client would be the one place that disagreed.
     */
    static final class Border {
        final LuaImage image;
        final int l, t, r, b;
        private IBox box;              // built on first draw from TexSI views -- no second upload, nothing to free

        Border(LuaImage image, int l, int t, int r, int b) {
            this.image = image;
            this.l = l; this.t = t; this.r = r; this.b = b;
        }

        /**
         * The 9-slice box over the addon's image. Built lazily and once: it is nine {@link TexSI} windows onto the
         * same texture, so this allocates eight small objects and <b>no</b> GPU memory.
         */
        IBox box() {
            IBox c = this.box;
            if(c == null) {
                Tex tx = image.tex;
                int w = tx.sz().x, h = tx.sz().y;
                this.box = c = new IBox.Scaled(sub(tx, 0,     0,     l,         t),           // ctl
                                               sub(tx, w - r, 0,     r,         t),           // ctr
                                               sub(tx, 0,     h - b, l,         b),           // cbl
                                               sub(tx, w - r, h - b, r,         b),           // cbr
                                               sub(tx, 0,     t,     l,         h - t - b),   // left edge
                                               sub(tx, w - r, t,     r,         h - t - b),   // right edge
                                               sub(tx, l,     0,     w - l - r, t),           // top edge
                                               sub(tx, l,     h - b, w - l - r, b));          // bottom edge
            }
            return c;
        }

        private static Tex sub(Tex tx, int x, int y, int w, int h) {
            return new TexSI(tx, Coord.of(x, y), Coord.of(x + w, y + h));
        }

        /**
         * Paint this border around {@code [ul, ul+sz)}. A surface smaller than the border's own corners is left
         * alone rather than drawn inside out — inert, never an error, which is the doctrine every other
         * unappliable property here follows.
         */
        void draw(GOut g, Coord ul, Coord sz) {
            if((image == null) || image.dead)
                return;
            if((sz.x < l + r) || (sz.y < t + b))
                return;
            box().draw(g, ul, sz);
        }

        public int hashCode() {
            return (System.identityHashCode(image) * 31) + (l * 7) + (t * 13) + (r * 17) + (b * 19);
        }

        public boolean equals(Object o) {
            if(!(o instanceof Border))
                return false;
            Border x = (Border)o;
            return (image == x.image) && (l == x.l) && (t == x.t) && (r == x.r) && (b == x.b);
        }

        /** {@code widget:style().border} — the value as {@code reader} may hold it. */
        LuaValue toLua(Addon reader) {
            LuaTable t = new LuaTable();
            t.set("image", AssetApi.imageFor(reader, image));
            LuaTable s = new LuaTable();
            s.set("l", LuaValue.valueOf(this.l));
            s.set("t", LuaValue.valueOf(this.t));
            s.set("r", LuaValue.valueOf(this.r));
            s.set("b", LuaValue.valueOf(this.b));
            t.set("slice", s);
            return t;
        }
    }

    // ---- parsing -----------------------------------------------------------------------------------

    /**
     * Parse a rule's {@code bg = { color = {r,g,b[,a]} }} or {@code bg = { image = hafen.asset("…") }}.
     * <b>Exactly one</b> of the two: a table carrying both would have to pick a winner silently, and a table
     * carrying neither is a typo the API can only answer with an error (D-072 — an unknown <i>key</i> here has no
     * future meaning to wait for, unlike an unresolved selector).
     */
    static Bg parseBg(String ctx, LuaValue v) {
        if(!v.istable())
            throw new LuaError(ctx + ".bg: expected { color = {r,g,b[,a]} } or { image = hafen.asset(\"panel.png\") },"
                + " got " + v.typename());
        Color color = null;
        LuaImage image = null;
        LuaValue k = LuaValue.NIL;
        while(true) {
            Varargs n = v.next(k);
            k = n.arg1();
            if(k.isnil())
                break;
            String p = key(k);
            LuaValue pv = n.arg(2);
            if("color".equals(p)) {
                color = pv.istable() ? AddonManager.luaColor(pv, null) : null;
                if(color == null)
                    throw new LuaError(ctx + ".bg.color: expected a colour table with 0..255 components"
                        + " — { 26, 26, 28, 240 } or { r = 26, g = 26, b = 28, a = 240 }");
            } else if("image".equals(p)) {
                image = LuaImage.resolve(pv);
                if(image == null)
                    throw new LuaError(ctx + ".bg.image: expected an image asset handle"
                        + " — hafen.asset(\"img/panel.png\")");
            } else {
                throw new LuaError(ctx + ".bg: \"" + k.tojstring() + "\" is not a background property"
                    + " — a bg is { color = … } or { image = … }");
            }
        }
        if((color != null) && (image != null))
            throw new LuaError(ctx + ".bg: a background is a colour OR an image, not both"
                + " — draw the image over a coloured surface by putting the colour on the rule beneath it");
        if((color == null) && (image == null))
            throw new LuaError(ctx + ".bg: says nothing — a bg is { color = {r,g,b[,a]} } or { image = <asset> }");
        return new Bg(color, image);
    }

    /**
     * Parse a rule's {@code border = { image = hafen.asset("…"), slice = {l,t,r,b} }}. Both fields are required:
     * an image with no slice cannot be cut into a frame, and there is no default worth guessing at (033.3's
     * lesson — a guess produces a table that lies). The slice is validated against the image, so a border that
     * could only ever draw inside out is refused at {@code skin{}} time rather than silently at every frame.
     */
    static Border parseBorder(String ctx, LuaValue v) {
        if(!v.istable())
            throw new LuaError(ctx + ".border: expected { image = hafen.asset(\"panel.png\"), slice = {l,t,r,b} },"
                + " got " + v.typename());
        LuaImage image = null;
        LuaValue slice = null;
        LuaValue k = LuaValue.NIL;
        while(true) {
            Varargs n = v.next(k);
            k = n.arg1();
            if(k.isnil())
                break;
            String p = key(k);
            LuaValue pv = n.arg(2);
            if("image".equals(p)) {
                image = LuaImage.resolve(pv);
                if(image == null)
                    throw new LuaError(ctx + ".border.image: expected an image asset handle"
                        + " — hafen.asset(\"img/panel.png\")");
            } else if("slice".equals(p)) {
                slice = pv;
            } else {
                throw new LuaError(ctx + ".border: \"" + k.tojstring() + "\" is not a border property"
                    + " — a border is { image = …, slice = {l,t,r,b} }");
            }
        }
        if(image == null)
            throw new LuaError(ctx + ".border: needs an image — { image = hafen.asset(\"img/panel.png\"),"
                + " slice = {l,t,r,b} }");
        if(slice == null)
            throw new LuaError(ctx + ".border: needs a slice — the four insets {left, top, right, bottom},"
                + " in the image's own pixels, that cut it into corners and edges");
        int[] s = parseSlice(ctx, slice);
        int w = image.sz.x, h = image.sz.y;
        if((s[0] + s[2] >= w) || (s[1] + s[3] >= h))
            throw new LuaError(ctx + ".border.slice: {" + s[0] + "," + s[1] + "," + s[2] + "," + s[3]
                + "} leaves no middle in a " + w + "x" + h + " image — left+right must be under its width and"
                + " top+bottom under its height");
        return new Border(image, s[0], s[1], s[2], s[3]);
    }

    /**
     * Parse a rule's {@code pad = 6} — the space a surface keeps between its frame and its content, in <b>raw</b>
     * px and a single number for all four sides (one canonical way per operation).
     *
     * <p><b>Raw, not {@code UI.scale}d.</b> It is a coordinate, and every coordinate this API takes is raw: an
     * addon's window is {@code size = {90, 40}} of real pixels, a border's slice is the image's own pixels, and
     * {@code g:image} draws where it is told. Only a {@code font}'s {@code size} is scaled, because a type size is
     * not a coordinate. A scaled {@code pad} would be the one number in a rule that did not mean what it said.
     */
    static Integer parsePad(String ctx, LuaValue v) {
        // type() rather than isnumber(): in LuaJ a STRING that looks like a number answers isnumber() (the 028
        // asset lesson, and why a sheet key is type-checked the same way). `pad = "6"` is a typo, not a pad.
        if(v.type() != LuaValue.TNUMBER)
            throw new LuaError(ctx + ".pad: expected a number of pixels — pad = 6, the space between a surface's"
                + " frame and its content, got " + v.typename());
        int p = v.toint();
        if(p < 0)
            throw new LuaError(ctx + ".pad: padding cannot be negative (got " + p + ")"
                + " — a rule adds space between a frame and its content, it does not take it away");
        return Integer.valueOf(p);
    }

    /**
     * The four insets as {@code {l,t,r,b}} or {@code {l=,t=,r=,b=}} — the same two spellings a colour takes, for
     * the same reason: the positional form is what a hand-written rule (and a {@code theme.json}) says, the keyed
     * form is what {@code widget:style()} hands back, so a read round-trips into a write unchanged.
     */
    private static int[] parseSlice(String ctx, LuaValue v) {
        if(!v.istable())
            throw new LuaError(ctx + ".border.slice: expected {left, top, right, bottom}, got " + v.typename());
        LuaValue l = v.get("l"), t = v.get("t"), r = v.get("r"), b = v.get("b");
        if(!l.isnumber() || !t.isnumber() || !r.isnumber() || !b.isnumber()) {
            l = v.get(1); t = v.get(2); r = v.get(3); b = v.get(4);
            if(!l.isnumber() || !t.isnumber() || !r.isnumber() || !b.isnumber())
                throw new LuaError(ctx + ".border.slice: expected four numbers — {left, top, right, bottom}"
                    + " or { l = 8, t = 8, r = 8, b = 8 }");
        }
        int[] s = {l.toint(), t.toint(), r.toint(), b.toint()};
        for(int i = 0; i < s.length; i++) {
            if(s[i] < 0)
                throw new LuaError(ctx + ".border.slice: an inset cannot be negative (got " + s[i] + ")");
        }
        return s;
    }

    /** A table key as a property name, or {@code null} for anything that is not a plain string (numbers included). */
    private static String key(LuaValue k) {
        return (!k.isnumber() && k.isstring()) ? k.tojstring() : null;
    }
}
