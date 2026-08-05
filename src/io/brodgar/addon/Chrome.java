package io.brodgar.addon;

import haven.Coord;
import haven.Fonts;
import haven.GOut;
import haven.IBox;
import haven.Tex;
import haven.TexSI;
import haven.Widget;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;

import java.awt.Color;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The <b>chrome</b> properties of a stylesheet rule (spec {@code 035-ui-chrome}, feature C2): {@code bg} and
 * {@code border}, which paint, and {@code pad}, which <b>moves</b>. Everything C1 shipped is text — {@code font}
 * and {@code color}; these are the first properties that put pixels of their own on a surface, and the first that
 * can change where the client's own content sits.
 *
 * <pre>
 *   hafen.ui():sheet():rule("window.frame")
 *     :bg{ color = {26, 26, 28, 240} }
 *     :border{ image = hafen.asset("img/panel.png"), slice = {8, 8, 8, 8} }
 *     :pad(6)
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
 * rule ({@code "window.frame"}) or a tree rule / {@code widget:rule()} alike — and this class only says what one
 * of them <i>is</i> and how it paints. The two consumers are {@link SkinDeco}, the sheet-fed window chrome
 * (035.1), and {@link SkinBox}, the sheet-fed 9-slice the window-less panels draw with (035.3) — both built
 * from the very {@link Border#box} below.
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

    // ---- the window-LESS panels (035.3) ------------------------------------------------------------

    /**
     * The <b>sheet-fed panel box</b> (035.3): the {@link IBox} a routed window-less panel — a {@code Frame}
     * around a list, a flower-menu petal, a dropdown menu, an {@code ISBox} — draws with while a rule names it.
     *
     * <p><b>It is the twin of {@link SkinDeco}, one level down.</b> A window's chrome is a widget of its own and
     * could therefore be <i>replaced</i>; a panel's chrome is an {@code IBox} the panel draws itself, and
     * {@code IBox} is already an interface, so here the replacement is the box rather than the widget. Same
     * source (the rule this widget resolves through {@code Fonts.styleFor}), same two properties, same rule for
     * where a background stops (D-079): with a {@code border} in the rule the {@code bg} fills the whole panel,
     * because our 9-slice is now the entire frame and is transparent between its slices; with no border the
     * stock box still paints the panel's edge, so the {@code bg} stays inside it.
     *
     * <p><b>A {@code bg} reaches only a panel that paints its own surface BEFORE its content</b> — the petals,
     * the dropdowns, an {@code ISBox}, a {@code DynresWindow.Image}. A {@code Frame} is not one: it is a border
     * placed <i>around</i> a region and drawn <i>after</i> what it frames (and {@code Frame.around} leaves that
     * content a sibling of the frame entirely), so a fill would bury it. There a rule's {@code border} applies
     * and its {@code bg} is inert — the same doctrine as everywhere else in this feature, and the same reason:
     * the sheet replaces a surface the client already paints, it does not invent one.
     *
     * <p><b>Geometry is stock, and that is a finding rather than a shortcut.</b> All six measuring methods
     * delegate to the box this one stands in for, because a panel decides its size and places its children when
     * it is <i>built</i> — {@code Frame}'s constructor adds {@code box.bisz()} to its content, {@code SListMenu}
     * lays its list out in its own — and nothing re-runs that when a sheet changes. A box whose insets differed
     * from the stock ones would move a panel's frame without moving anything inside it. So {@code pad} is inert
     * on a panel, and so are a border's own insets: a border image is drawn <i>into</i> the room the stock art
     * had. Which is the doctrine's geometry row applied honestly — a size-changing property applies only where
     * the surface owns its geometry and re-lays-out, and a panel does neither.
     */
    static final class SkinBox implements Fonts.Box {
        private final IBox stock;
        private final Bg bg;
        private final Border border;

        private SkinBox(IBox stock, Bg bg, Border border) {
            this.stock = stock;
            this.bg = bg;
            this.border = border;
        }

        // The measurements are the STOCK box's, always -- see the class comment.
        public Coord btloff() {return stock.btloff();}
        public Coord ctloff() {return stock.ctloff();}
        public Coord bbroff() {return stock.bbroff();}
        public Coord cbroff() {return stock.cbroff();}
        public Coord bisz()   {return stock.bisz();}
        public Coord cisz()   {return stock.cisz();}

        /*
         * Where the bg stops -- D-079 one level down, written as the two halves of a rectangle rather than
         * inline, because it is the rule and not an implementation detail: with our own border the bg fills the
         * WHOLE panel (a 9-slice is transparent between its slices, so anything less would show through), and
         * with the stock box still framing the panel it stays inside that box's own pixels (filling over them
         * would square off their shaped corners). No allocation beyond the Coord arithmetic itself.
         */
        Coord bgul(Coord tl) {return (border != null) ? tl : tl.add(stock.btloff());}
        Coord bgsz(Coord sz) {return (border != null) ? sz : sz.sub(stock.bisz());}

        public boolean drawbg(GOut g, Coord tl, Coord sz) {
            Bg b = this.bg;
            if(b == null)
                return false;                          // a border-only rule leaves the panel's own surface alone
            b.draw(g, bgul(tl), bgsz(sz));
            return true;
        }

        public void draw(GOut g, Coord tl, Coord sz) {
            if(border != null)
                border.draw(g, tl, sz);
            else
                stock.draw(g, tl, sz);                 // a bg-only rule keeps the panel's stock frame
        }
    }

    /**
     * One {@link SkinBox} per (resolved style, stock box) pair — the interning that lets a panel ask on every
     * frame and allocate nothing. The outer keys are {@code Fonts}' own interned style objects, held weakly
     * because they stop existing when the rules change (a {@code WeakHashMap} over a type that overrides neither
     * {@code equals} nor {@code hashCode} is an identity map for free, the same trick {@link Sheet}'s per-widget
     * cache uses); the inner ones are the panels' {@code static final} stock boxes. Guarded by {@code Chrome.class}.
     */
    private static final Map<Fonts.Style, Map<IBox, SkinBox>> boxes =
        new WeakHashMap<Fonts.Style, Map<IBox, SkinBox>>();

    /**
     * {@code Fonts.box(scope, wdg, stock)} — the box {@code wdg}'s panel should draw with, or {@code null} when
     * no rule paints it (the overwhelmingly common case, and the one that keeps a stock client stock).
     *
     * <p>A rule that names only <i>text</i> answers {@code null} too: a {@code ["*"] = {font = body}} sheet
     * cascades into every scope, this one included, and a panel must not start drawing a box of its own because
     * somebody set a font.
     */
    static Fonts.Box box(String scope, Widget wdg, IBox stock) {
        Fonts.Style st = Fonts.styleFor(scope, wdg);
        if((st == null) || (stock == null))
            return null;
        Bg bg = (Bg)st.bg();
        Border border = (Border)st.border();
        if((bg == null) && (border == null))
            return null;                    // nothing to paint -- hand the site its own box back
        synchronized(Chrome.class) {
            Map<IBox, SkinBox> m = boxes.get(st);
            if(m == null)
                boxes.put(st, m = new IdentityHashMap<IBox, SkinBox>(2));
            SkinBox b = m.get(stock);
            if(b == null)
                m.put(stock, b = new SkinBox(stock, bg, border));
            return b;
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
     * could only ever draw inside out is refused where the rule is written rather than silently at every frame.
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
