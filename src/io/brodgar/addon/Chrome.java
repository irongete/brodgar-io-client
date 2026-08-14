package io.brodgar.addon;

import haven.Area;
import haven.Coord;
import haven.Fonts;
import haven.GOut;
import haven.IBox;
import haven.PUtils;
import haven.Resource;
import haven.Tex;
import haven.TexSI;
import haven.Widget;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The <b>chrome</b> properties of a stylesheet rule: {@code bg} and {@code border}, which paint, and
 * {@code padding}, which <b>moves</b>. {@code font} and {@code color} are text; these are the properties that
 * put pixels of their own on a surface, and the ones that can change where the client's own content sits.
 *
 * <pre>
 *   hafen.ui():sheet():rule("window.frame")
 *     :bg{ color = {26, 26, 28, 240} }
 *     :border{ box = "gfx/hud/wnd" }
 *     :padding(8, 4, 8, 8)
 * </pre>
 *
 * <p><b>They travel as a bag</b> (065.1). A resolved style carries its chrome half as one opaque
 * {@code Map<String, Object>} ({@code Fonts.Style#prop}), keyed by the very property names a rule is written
 * with — {@link #BG}, {@link #BORDER}, {@link #PADDING} — and folded key by key by {@code Fonts.combine}. So a
 * new theme property costs a parser here, a slot in {@link Sheet.Props}, a setter in {@link LuaRule} and a
 * reader at its site, and <b>no edit to {@code haven}</b>. The casts back out of the bag live in one place, the
 * three accessors below, which is what keeps them honest.
 *
 * <p><b>One art value, four spellings</b> (065.2). {@link #parseArt} is the single parser behind every image
 * slot in the vocabulary: {@code {color=}}, {@code {image=<handle>}}, {@code {asset="path"}} and
 * {@code {res="gfx/…"}}. Naming the game's own art is what that buys — nothing has to be extracted from the
 * client's resources to be themed with, and nothing may be named one way in one property and another way in
 * the next. {@link Src} is where the two <i>spaces</i> meet: an addon's file is authored in design pixels and
 * is scaled on the way to the screen, while a {@code .res} carries its own scale and the client has already
 * resampled it ({@code Resource.Image.scaled()}), so a slice is written in design pixels either way and this
 * class converts.
 *
 * <p><b>...and one place value, three uses</b> (065.4). {@link Spot} is a corner out of {@link #CORNERS} plus an
 * offset, and it is what a window's decoration is told with: where its {@code caption} goes, and — as the
 * {@code at} an {@link Art} already carries — where its {@code sizer} sits and where each of a {@link Border}'s
 * {@code parts} is pinned. The fourth ornament, the caption <b>plate</b>, needs no place at all: it is
 * {@code window.title}'s own {@code bg}/{@code border} painted at the box the client sized ({@link #draw}).
 *
 * <p><b>Plain data, parsed once.</b> A rule carries no Lua and no callback: an {@link Art} is a colour or a
 * picture, a {@link Border} is a 9-slice or one of the client's own boxes, and the engine paints from that —
 * the per-frame Lua {@code Deco} callback was rejected on cost at design time (a frame is redrawn every frame
 * with no raster cache to amortise it) and stays rejected. It is also what lets a theme be a
 * {@code theme.json} rather than code: every field here is a number, a string or an asset handle.
 *
 * <p><b>Where they resolve is the sheet's business, not this class's.</b> These values ride the same cascade
 * {@code font} and {@code color} do — folded per property by {@code Fonts.combine} (D-076), carried by a site
 * rule ({@code "window.frame"}) or a tree rule / {@code widget:rule()} alike — and this class only says what one
 * of them <i>is</i> and how it paints. The two consumers are {@link SkinDeco}, the sheet-fed window chrome
 * (035.1), and {@link SkinBox}, the sheet-fed 9-slice the window-less panels draw with (035.3) — both built
 * from the very {@link Border#ibox} below.
 *
 * <p><b>A border costs no texture.</b> Its nine slices are {@link TexSI} views over the addon's <i>one</i>
 * uploaded image, so a border neither uploads a second copy nor owns anything to dispose — the image's own
 * {@code :dispose()}/teardown is still the whole lifetime, and a disposed image simply stops painting. A
 * {@code {box=}} border owns nothing at all: it is the client's own eight textures, from the resource cache.
 */
final class Chrome {
    private Chrome() {}

    // ---- the bag: the keys a resolved style carries its chrome half under (065.1) -------------------

    /** The {@code bg} property's key in a resolved style's bag. Its value is a {@link Bg}. */
    static final String BG = "bg";
    /** The {@code border} property's key. Its value is a {@link Border}. */
    static final String BORDER = "border";
    /** The {@code padding} property's key. Its value is a {@link Pad}. */
    static final String PADDING = "padding";
    /** The {@code caption} property's key (065.4). Its value is a {@link Spot}. */
    static final String CAPTION = "caption";
    /** The {@code sizer} property's key (065.4). Its value is an {@link Art}. */
    static final String SIZER = "sizer";
    /** The {@code close} property's key (065.5). Its value is a {@link Close}. */
    static final String CLOSE = "close";
    /** The {@code picture} property's key (065.12). Its value is a {@link Pic}. */
    static final String PICTURE = "picture";
    /** The {@code emboss} property's key (065.14). Its value is an {@link Emboss}. */
    static final String EMBOSS = "emboss";
    /** The {@code glow} property's key (065.15). Its value is a {@link Glow}. */
    static final String GLOW = "glow";
    /**
     * The {@code color} property's key when what it carries is a <b>sequence</b> rather than a colour (065.16).
     * Its value is a {@link Seq}. It rides the bag rather than {@code Spec}'s typed {@code color} field for the
     * plain reason that it is not a colour: no foundry can be built out of it, and the two sites that walk one
     * ask for it by name at the moment they need the next answer.
     */
    static final String COLORSEQ = "colorseq";

    /** The {@code bg} a resolved style carries, or {@code null} — for {@code null} styles too, which is the common case. */
    static Bg bg(Fonts.Style st) {
        return (st == null) ? null : (Bg)st.prop(BG);
    }

    /** The {@code border} a resolved style carries, or {@code null}. */
    static Border border(Fonts.Style st) {
        return (st == null) ? null : (Border)st.prop(BORDER);
    }

    /** The {@code padding} a resolved style carries, or {@code null}. */
    static Pad padding(Fonts.Style st) {
        return (st == null) ? null : (Pad)st.prop(PADDING);
    }

    /** The {@code caption} spot a resolved style carries, or {@code null} — the stock place, then. */
    static Spot caption(Fonts.Style st) {
        return (st == null) ? null : (Spot)st.prop(CAPTION);
    }

    /** The {@code sizer} a resolved style carries, or {@code null} — the client's own, at its own place. */
    static Art sizer(Fonts.Style st) {
        return (st == null) ? null : (Art)st.prop(SIZER);
    }

    /** The {@code close} button a resolved style carries, or {@code null} — the client's own, at its own place. */
    static Close close(Fonts.Style st) {
        return (st == null) ? null : (Close)st.prop(CLOSE);
    }

    /** The {@code picture} a resolved style carries, or {@code null} — the client's own art, then. */
    static Pic picture(Fonts.Style st) {
        return (st == null) ? null : (Pic)st.prop(PICTURE);
    }

    /** The {@code emboss} a resolved style carries, or {@code null} — the client's own relief, then. */
    static Emboss emboss(Fonts.Style st) {
        return (st == null) ? null : (Emboss)st.prop(EMBOSS);
    }

    /** The {@code glow} a resolved style carries, or {@code null} — the client's own halo, then. */
    static Glow glow(Fonts.Style st) {
        return (st == null) ? null : (Glow)st.prop(GLOW);
    }

    /** The colour {@code Seq} a resolved style carries, or {@code null} — the site walks its own, then. */
    static Seq seq(Fonts.Style st) {
        return (st == null) ? null : (Seq)st.prop(COLORSEQ);
    }

    /**
     * The bag one rule's chrome properties travel in, or {@code null} when it names none — which is what keeps
     * the provider's identity fast path intact for a sheet that says nothing about chrome.
     */
    static Map<String, Object> props(Bg bg, Border border, Pad padding, Pic picture, Emboss emboss, Glow glow,
                                     Spot caption, Art sizer, Close close, Seq seq) {
        if((bg == null) && (border == null) && (padding == null) && (picture == null) && (emboss == null)
           && (glow == null) && (caption == null) && (sizer == null) && (close == null) && (seq == null))
            return null;
        Map<String, Object> m = new LinkedHashMap<String, Object>(10);
        if(seq != null)
            m.put(COLORSEQ, seq);
        if(emboss != null)
            m.put(EMBOSS, emboss);
        if(glow != null)
            m.put(GLOW, glow);
        if(bg != null)
            m.put(BG, bg);
        if(border != null)
            m.put(BORDER, border);
        if(padding != null)
            m.put(PADDING, padding);
        if(picture != null)
            m.put(PICTURE, picture);
        if(caption != null)
            m.put(CAPTION, caption);
        if(sizer != null)
            m.put(SIZER, sizer);
        if(close != null)
            m.put(CLOSE, close);
        return m;
    }

    // ---- the nine spots, and the two fill modes (065.2) ---------------------------------------------

    /**
     * The nine spots, indexed {@code (ay * 3) + ax} — the three positions on each axis, named the CSS way. One
     * vocabulary for every place in this API: a rule's {@code anchor} ({@link Layout#parseAnchor}) picks a
     * widget's corner out of it, and an {@link Art}'s {@code at} picks the corner of the surface it is painted on.
     */
    static final String[] CORNERS = {
        "topleft",    "top",    "topright",
        "left",       "center", "right",
        "bottomleft", "bottom", "bottomright",
    };

    /** One of the nine {@link #CORNERS}, as its index. Anything else is a typo, and says so — naming all nine. */
    static int cornerOf(String ctx, String what, LuaValue v) {
        String s = (v.isstring() && !v.isnumber()) ? v.tojstring() : null;
        for(int i = 0; (s != null) && (i < CORNERS.length); i++) {
            if(CORNERS[i].equals(s))
                return i;
        }
        throw new LuaError(ctx + what + ": expected one of " + cornerList() + ", got "
            + ((s == null) ? v.typename() : ("\"" + s + "\"")));
    }

    /** The nine, spelled out — what every refusal that meets a corner name lists rather than summarises. */
    private static String cornerList() {
        StringBuilder sb = new StringBuilder();
        for(int i = 0; i < CORNERS.length; i++)
            sb.append((i == 0) ? "" : ", ").append('"').append(CORNERS[i]).append('"');
        return sb.toString();
    }

    /**
     * A <b>spot</b>: one of the nine {@link #CORNERS} plus an offset in design pixels (065.4) — the whole of what
     * it takes to say where a window's decoration puts an ornament it does not size, its caption being the one
     * that has no art of its own to carry a place inside.
     *
     * <p>It is the same nine-corner vocabulary a rule's {@code anchor} picks a widget's corner out of and an
     * {@link Art}'s {@code at} pins a picture to, at the one arity that has no picture: a corner, and how far
     * from it. Immutable, with value equality, because the resolved style is interned on it.
     */
    static final class Spot {
        /** The corner it is pinned to, as a {@link #CORNERS} index. */
        final int corner;
        /** How far from that corner, in design pixels, or {@code null}. */
        final Coord offset;

        Spot(int corner, Coord offset) {
            this.corner = corner;
            this.offset = offset;
        }

        /**
         * Where a thing {@code isz} device pixels big sits inside a {@code box}-sized surface — its top-left, in
         * device pixels. The corner decides which end of each axis the thing is measured from, and the offset
         * moves it from there, converted on the way in because the rule wrote it in design pixels.
         */
        Coord place(Coord box, Coord isz) {
            int ax = corner % 3, ay = corner / 3;
            int x = (ax == 0) ? 0 : ((ax == 2) ? (box.x - isz.x) : ((box.x - isz.x) / 2));
            int y = (ay == 0) ? 0 : ((ay == 2) ? (box.y - isz.y) : ((box.y - isz.y) / 2));
            return Coord.of(x, y).add((offset == null) ? Coord.z : Px.in(offset));
        }

        public int hashCode() {
            return (corner * 31) + ((offset == null) ? 0 : offset.hashCode());
        }

        public boolean equals(Object o) {
            if(!(o instanceof Spot))
                return false;
            Spot s = (Spot)o;
            return (corner == s.corner) && ((offset == null) ? (s.offset == null) : offset.equals(s.offset));
        }

        /** The value in the shape the setter takes, so a read round-trips into a write. */
        LuaValue toLua() {
            LuaTable t = new LuaTable();
            t.set("at", LuaValue.valueOf(CORNERS[corner]));
            if(offset != null)
                t.set("offset", LuaWidget.xyTable(offset));
            return t;
        }
    }

    /**
     * Parse a spot — {@code { at = "topleft", offset = {12, 4} }}. The corner is required: a spot with no corner
     * is not a place, and there is no default worth guessing at (033.3's lesson). The offset is not, because
     * pinning something flush to a corner is a thing a theme says.
     */
    static Spot parseSpot(String ctx, String what, LuaValue v) {
        if(!v.istable())
            throw new LuaError(ctx + what + ": expected a spot — { at = \"topleft\", offset = {12, 4} }, got "
                + v.typename());
        int corner = -1;
        Coord offset = null;
        LuaValue k = LuaValue.NIL;
        while(true) {
            Varargs n = v.next(k);
            k = n.arg1();
            if(k.isnil())
                break;
            String p = key(k);
            LuaValue pv = n.arg(2);
            if("at".equals(p)) {
                corner = cornerOf(ctx, what + ".at", pv);
            } else if("offset".equals(p)) {
                offset = Layout.parseCoord(ctx + what, "offset", pv);
            } else {
                throw new LuaError(ctx + what + ": \"" + k.tojstring() + "\" is not a spot property — a spot"
                    + " carries the two fields \"at\" (one of the nine corners) and \"offset\" ({dx, dy}, in"
                    + " design pixels), and nothing else");
            }
        }
        if(corner < 0)
            throw new LuaError(ctx + what + ": needs an \"at\" — the corner it is pinned to, one of "
                + cornerList());
        return new Spot(corner, offset);
    }

    /** {@code mode = "stretch"} — an axis this art does not size itself on is scaled to fill it. */
    static final String STRETCH = "stretch";
    /** {@code mode = "tile"} — that axis is filled by repeating the art, clipped to the run. */
    static final String TILE = "tile";

    /** {@code mode}, or {@code dflt} when the value says none. The two modes, and nothing else. */
    private static String modeOf(String ctx, String what, LuaValue v, String dflt) {
        if(v.isnil())
            return dflt;
        String s = (v.isstring() && !v.isnumber()) ? v.tojstring() : null;
        if(STRETCH.equals(s) || TILE.equals(s))
            return s;
        throw new LuaError(ctx + what + ".mode: expected \"" + STRETCH + "\" (scale the art across the run) or"
            + " \"" + TILE + "\" (repeat it), got " + ((s == null) ? v.typename() : ("\"" + s + "\"")));
    }

    // ---- the four states a surface may be in (065.8) ------------------------------------------------

    /**
     * The states a surface draws itself differently in, and the names a value varies itself by ({@link Bg}).
     * A closed vocabulary, like {@link #CORNERS}: a rule naming a fifth is a typo, and one naming a state its
     * surface never enters is simply never asked for it.
     */
    static final String[] STATES = {"hover", "pressed", "disabled", "checked"};

    /** One of the {@link #STATES}, as its index, or {@code -1} — the answer for {@code null} too. */
    static int stateOf(String name) {
        for(int i = 0; (name != null) && (i < STATES.length); i++) {
            if(STATES[i].equals(name))
                return i;
        }
        return -1;
    }

    /** The four, spelled out — what a refusal that meets a state name lists rather than summarises. */
    private static String stateList() {
        StringBuilder sb = new StringBuilder();
        for(int i = 0; i < STATES.length; i++)
            sb.append((i == 0) ? "" : ", ").append('"').append(STATES[i]).append('"');
        return sb.toString();
    }

    // ---- where a picture's pixels come from (065.2) -------------------------------------------------

    /**
     * The raster behind a piece of art, and <b>the space its own pixels are in</b> — the one difference between
     * a file an addon ships and a name it borrows from the client.
     *
     * <p>An addon's PNG is authored in <b>design</b> pixels and is scaled on the way to the screen (058.2), so
     * its raster is design-sized and every view of it is wrapped by {@link Px#in(Tex)}. A {@code .res} image
     * carries its own {@code scale} — the HUD art is authored at 4× — and the client has already resampled it
     * to the running interface scale, so {@code Resource.loadtex} hands back a <b>device</b>-sized raster that
     * must not be wrapped again. Taking the wrong one draws the frame four times too large.
     *
     * <p>So a {@code slice} is written in design pixels whichever spelling named the art, and {@link #sub} is
     * the one place that knows which space it is cutting in. Cuts are made at <b>absolute</b> coordinates, so
     * two slices that share a boundary in the source share it on screen: converting a width instead would round
     * twice and leave a seam.
     */
    static final class Src {
        /** The addon's own image, or {@code null} when this art is a client resource. */
        final LuaImage image;
        /** The client resource's name, or {@code null} when this art is an addon's file. */
        final String res;
        private final Tex raw;            // the raster in ITS OWN space
        private final boolean design;     // ...and is that space design pixels?
        private Tex drawn;
        private BufferedImage awt;        // ...and the same pixels off the GPU (065.14), built at most once

        private Src(LuaImage image, String res, Tex raw, boolean design) {
            this.image = image;
            this.res = res;
            this.raw = raw;
            this.design = design;
        }

        /** An addon's own loaded file — design pixels. */
        static Src of(LuaImage li) {
            return new Src(li, null, li.tex, true);
        }

        /** One of the client's own images, already resampled to this client's scale — device pixels. */
        static Src of(String name, Tex tex) {
            return new Src(null, name, tex, false);
        }

        /** Has the image this art draws been disposed? Then it simply stops painting, as it always has. */
        boolean dead() {
            return (image != null) && image.dead;
        }

        /** The whole raster at the size it is drawn — device pixels. */
        Tex drawn() {
            Tex d = this.drawn;
            if(d == null)
                this.drawn = d = design ? Px.in(raw) : raw;
            return d;
        }

        /** The raster's own size in <b>design</b> pixels — the space a {@code slice} is validated against. */
        Coord size() {
            return design ? image.sz : Px.out(raw.sz());
        }

        /**
         * The same pixels as an <b>AWT raster</b>, at the size they are drawn — what a glyph mask is tiled with
         * (065.14), the one consumer in this API that composes on the CPU rather than blitting on the GPU.
         * {@code null} where there is nothing to tile: a disposed image, or art that carries no raster.
         *
         * <p>Built at most once and kept, because the client rebuilds a furnace whenever the rules move and a
         * scale is a copy of every pixel. The design/device seam is the same one {@link #drawn} crosses: an
         * addon's own PNG is authored in design pixels and is resampled here exactly as the client resamples
         * its own art on the way in, so one texture reads at the same weight at every interface scale.
         */
        BufferedImage raster() {
            BufferedImage a = this.awt;
            if(a != null)
                return a;
            if(res != null) {
                a = Resource.loadsimg(res);            // the client's own, already at this client's scale
            } else if(!dead()) {
                a = image.tex.back;
                Coord tsz = Px.in(image.sz);
                if(!tsz.equals(Coord.of(a.getWidth(), a.getHeight())))
                    a = PUtils.uiscale(a, tsz);        // an addon's file is DESIGN pixels: scale it as the client does
            }
            return this.awt = a;
        }

        /** A window onto the raster, cut at <b>design</b> coordinates and viewed at the size it is drawn. */
        Tex sub(int x, int y, int w, int h) {
            if(design)
                return Px.in(new TexSI(raw, Coord.of(x, y), Coord.of(x + w, y + h)));
            return new TexSI(raw, Px.in(Coord.of(x, y)), Px.in(Coord.of(x + w, y + h)));
        }

        /** How this art names itself back to Lua — the spelling it was written with. */
        void toLua(Addon reader, LuaTable t) {
            if(image != null)
                t.set("image", AssetApi.imageFor(reader, image));
            else
                t.set("res", LuaValue.valueOf(res));
        }

        public int hashCode() {
            return (image != null) ? (System.identityHashCode(image) * 31) : res.hashCode();
        }

        public boolean equals(Object o) {
            if(!(o instanceof Src))
                return false;
            Src s = (Src)o;
            return (image == s.image) && ((res == null) ? (s.res == null) : res.equals(s.res));
        }
    }

    // ---- the surface art: a colour or a picture, placed and filled (065.2) --------------------------

    /**
     * A <b>surface</b>: the art a {@code bg} layer paints with. A flat colour, or a picture from any of the
     * three places one can come from, with an optional {@code at} spot, an {@code offset} and a fill
     * {@code mode}. Immutable, with value equality — the resolved style is interned on it ({@code Sheet.SKey}),
     * and two rules that say the same thing must intern to the same style or every routed site inside them
     * rebuilds.
     *
     * <p><b>The spot says which axes the art sizes itself on.</b> A name that pins an edge on an axis
     * ({@code left}/{@code right} for x, {@code top}/{@code bottom} for y) gives the art its own size there;
     * {@code center} gives it its own size on both, centred; and an axis the name says nothing about is
     * <b>filled</b> — which is what makes {@code at = "left"} a shade down the whole left side rather than one
     * stamp in the middle of it, and what the client's own window background is three of.
     */
    static final class Art {
        /** The flat fill, or {@code null} when this art is a picture. */
        final Color color;
        /** The picture, or {@code null} when this art is a colour. */
        final Src src;
        /** The spot it is pinned to, as a {@link #CORNERS} index, or {@code -1} for the whole surface. */
        final int spot;
        /** The offset from that spot, in design pixels, or {@code null}. */
        final Coord offset;
        /** {@link #STRETCH} or {@link #TILE} — what an axis this art does not size itself on is filled with. */
        final String mode;

        Art(Color color, Src src, int spot, Coord offset, String mode) {
            this.color = color;
            this.src = src;
            this.spot = spot;
            this.offset = offset;
            this.mode = mode;
        }

        /**
         * The size this art's <b>picture</b> is drawn at — device pixels — or {@code null} where it has none: a
         * flat colour fills whatever surface it is given, and a disposed image draws nothing at all. It is what
         * gives a themed close button its box ({@link Close}), the one place an art's own size decides a
         * widget's rather than filling a box the client already decided.
         */
        Coord natural() {
            if((color != null) || (src == null) || src.dead())
                return null;
            Coord nat = src.drawn().sz();
            return ((nat.x <= 0) || (nat.y <= 0)) ? null : nat;
        }

        /**
         * The rectangle this art's <b>picture</b> covers inside {@code [ul, ul+sz)} — device pixels — or
         * {@code null} when there is none to place. {@link #draw} paints from it, and a window's chrome reads it
         * back to say where it put an ornament ({@code widget:chrome()}).
         */
        Area place(Coord ul, Coord sz) {
            Coord nat = natural();
            if(nat == null)
                return null;
            int ax = (spot < 0) ? 1 : (spot % 3), ay = (spot < 0) ? 1 : (spot / 3);
            boolean fx = (spot < 0) || ((spot != 4) && (ax == 1));   // an axis the spot says nothing about
            boolean fy = (spot < 0) || ((spot != 4) && (ay == 1));
            int w = fx ? sz.x : nat.x, h = fy ? sz.y : nat.y;
            int x = fx ? 0 : ((ax == 0) ? 0 : ((ax == 2) ? (sz.x - w) : ((sz.x - w) / 2)));
            int y = fy ? 0 : ((ay == 0) ? 0 : ((ay == 2) ? (sz.y - h) : ((sz.y - h) / 2)));
            Coord o = (offset == null) ? Coord.z : Px.in(offset);
            return Area.sized(ul.add(x, y).add(o), Coord.of(w, h));
        }

        /** Paint this art over {@code [ul, ul+sz)} — device pixels, the client's own box. */
        void draw(GOut g, Coord ul, Coord sz) {
            if(color != null) {
                g.chcolor(color);
                g.frect(ul, sz);
                g.chcolor();
                return;
            }
            Area a = place(ul, sz);
            if(a == null)
                return;
            Tex t = src.drawn();
            Coord bsz = a.sz();
            if(bsz.equals(t.sz()))
                g.image(t, a.ul);
            else if(TILE.equals(mode))
                g.rimage(t, a.ul, bsz);
            else
                g.image(t, a.ul, bsz);
        }

        public int hashCode() {
            return ((color == null) ? 0 : color.hashCode()) + ((src == null) ? 0 : (src.hashCode() * 31))
                + (spot * 7) + ((offset == null) ? 0 : (offset.hashCode() * 13)) + (mode.hashCode() * 17);
        }

        public boolean equals(Object o) {
            if(!(o instanceof Art))
                return false;
            Art a = (Art)o;
            return ((color == null) ? (a.color == null) : color.equals(a.color))
                && ((src == null) ? (a.src == null) : src.equals(a.src))
                && (spot == a.spot)
                && ((offset == null) ? (a.offset == null) : offset.equals(a.offset))
                && mode.equals(a.mode);
        }

        /** The value as {@code reader} may hold it — the spelling it was written with, and the fields it set. */
        LuaTable toLua(Addon reader) {
            LuaTable t = new LuaTable();
            if(color != null) {
                t.set("color", AddonManager.color(color));
                return t;                       // a colour fills its surface: no spot, no offset, no mode
            }
            src.toLua(reader, t);
            if(spot >= 0)
                t.set("at", LuaValue.valueOf(CORNERS[spot]));
            if(offset != null)
                t.set("offset", LuaWidget.xyTable(offset));
            t.set("mode", LuaValue.valueOf(mode));
            return t;
        }
    }

    // ---- the relief a caption is cut out of (065.14) ------------------------------------------------

    /**
     * A rule's {@code emboss}: whether this client's own <b>relief</b> is drawn through a surface's glyphs, and
     * with what. It is the one property here that paints no box — five text surfaces render their letters as a
     * <i>mask</i> and tile a picture through it ({@link Fonts#emboss}), and this is what says which picture, or
     * that there should be none.
     *
     * <p><b>Turning it off is what makes a {@code color} rule reach those letters.</b> A texture cut to the
     * shape of a caption leaves no glyph colour behind for anything to override, so the property that names the
     * relief is also the only way to stop one — and with it gone the foundry's own colour, which is the rule's,
     * is what reaches the screen.
     *
     * <p>Immutable with value equality, because the resolved style is interned on it ({@code Sheet.SKey}), and
     * it <b>is</b> the {@link Fonts.Relief} the site asks: the value carries no state and there is nothing to
     * intern one level down.
     */
    static final class Emboss implements Fonts.Relief {
        /** Is a relief drawn at all? {@code false} is the whole of what {@code emboss(false)} says. */
        final boolean on;
        /** The texture tiled through the glyphs, or {@code null} when {@link #on} is {@code false}. */
        final Art texture;

        Emboss(boolean on, Art texture) {
            this.on = on;
            this.texture = texture;
        }

        /**
         * {@inheritDoc}
         *
         * <p>A theme's art that has been disposed answers {@code stock}: the surface keeps looking like the
         * client's own, which is the conservative half of every art read here — a picture that is gone stops
         * painting rather than blanking what it was over.
         */
        public BufferedImage texture(BufferedImage stock) {
            if(!on)
                return null;
            BufferedImage a = (texture.src == null) ? null : texture.src.raster();
            return (a == null) ? stock : a;
        }

        public int hashCode() {
            return (on ? 0x51ED : 0) + ((texture == null) ? 0 : (texture.hashCode() * 31));
        }

        public boolean equals(Object o) {
            if(!(o instanceof Emboss))
                return false;
            Emboss e = (Emboss)o;
            return (on == e.on) && ((texture == null) ? (e.texture == null) : texture.equals(e.texture));
        }

        /** The value in the shape the setter takes, so a read round-trips into a write. */
        LuaValue toLua(Addon reader) {
            if(!on)
                return LuaValue.FALSE;
            LuaTable t = new LuaTable();
            t.set("texture", texture.toLua(reader));
            return t;
        }
    }

    // ---- the halo behind it (065.15) ---------------------------------------------------------------

    /**
     * A rule's {@code glow}: the blurred <b>halo</b> the client draws behind an embossed surface's letters, as a
     * colour and a radius. It is {@link Emboss}'s neighbour and its opposite in one respect — a relief is what
     * the glyphs are <i>filled</i> with, a halo is what sits <i>behind</i> them — and the two are independent in
     * every direction: dropping the relief leaves the halo, naming a halo leaves the relief.
     *
     * <p><b>One radius, both of the client's two.</b> The blur an embossed site builds takes a gradient radius
     * and a blur radius, and the stock pairs differ by a quarter of a pixel where they differ at all; a theme
     * says one number and both take it, because the distinction is one no theme can see.
     *
     * <p><b>Zero is a value, not an omission.</b> {@code radius = 0} is the only way to say <i>no halo</i>, and
     * a rule carrying no {@code glow} at all is what keeps the client's own — the same division {@code emboss}
     * draws between {@code false} and silence.
     *
     * <p>The radius is held in <b>design</b> pixels, as the rule wrote it, and converts at the moment the site
     * asks ({@link #radius}) — so the value stays comparable across interface scales, which is what the
     * interning of a resolved style ({@code Sheet.SKey}) is keyed on. Immutable with value equality, and it
     * <b>is</b> the {@link Fonts.Halo} the site reads.
     */
    static final class Glow implements Fonts.Halo {
        /** The colour the halo is drawn in. Never {@code null}: a glow naming none is refused at its rule. */
        final Color color;
        /** How far it reaches, in <b>design</b> pixels — {@code 0} being no halo at all. */
        final int radius;

        Glow(Color color, int radius) {
            this.color = color;
            this.radius = radius;
        }

        public int radius() {
            return Px.in(radius);
        }

        public Color color() {
            return color;
        }

        public int hashCode() {
            return (color.hashCode() * 31) + radius;
        }

        public boolean equals(Object o) {
            if(!(o instanceof Glow))
                return false;
            Glow g = (Glow)o;
            return color.equals(g.color) && (radius == g.radius);
        }

        /** The value in the shape the setter takes, so a read round-trips into a write. */
        LuaTable toLua() {
            LuaTable t = new LuaTable();
            t.set("color", AddonManager.color(color));
            t.set("radius", LuaValue.valueOf(radius));
            return t;
        }
    }

    // ---- the colour a site WALKS (065.16) ----------------------------------------------------------

    /**
     * A rule's {@code color} where what it names is a <b>sequence</b>: the colours a site hands out one at a
     * time rather than the one colour it paints with. Two of this client's chat colours are of that shape — the
     * hue a multi-chat mints per speaker, and the urgency triple — and neither survives being flattened, since
     * telling one speaker or one urgency from the next is the whole of what they are for.
     *
     * <p><b>Two spellings, and a value is exactly one of them.</b> A {@code palette} is the colours themselves,
     * cycled in the order written, which is what a theme with a chosen set of colours wants. A {@code generate}
     * is the walk the client itself does — a step around the hue circle, at a fixed saturation and brightness —
     * parameterised, which is what a theme wanting <i>unlimited</i> distinct colours wants. The client's own is
     * a {@code generate} of {@code step = math.sqrt(2) % 1}, {@code saturation = 0.5}, {@code brightness = 1.0}.
     *
     * <p>Immutable with value equality, so a resolved style interns ({@code Sheet.SKey}) exactly as it does for
     * every other value here, and it <b>is</b> the {@link Fonts.Sequence} the site reads.
     */
    static final class Seq implements Fonts.Sequence {
        /** The colours to cycle, in order — {@code null} on a generated sequence. Never empty when set. */
        final Color[] palette;
        /**
          * How far around the hue circle each answer moves, {@code 0 < step <= 1}. Unread on a palette.
          *
          * <p><b>Double rather than float</b>, though the client's own walk and {@code HSBtoRGB} are both
          * float: a rule writes a Lua number, which is a double, and narrowing it here would make the value
          * that reads back a different number from the one that was written. A read round-trips into a write
          * everywhere else in this API and there is no reason for three fields to be the exception.
          */
        final double step;
        /** The saturation and brightness every generated colour is minted at, each {@code 0..1}. */
        final double saturation, brightness;

        Seq(Color[] palette) {
            this.palette = palette;
            this.step = this.saturation = this.brightness = 0;
        }

        Seq(double step, double saturation, double brightness) {
            this.palette = null;
            this.step = step;
            this.saturation = saturation;
            this.brightness = brightness;
        }

        /**
         * The {@code n}th colour. A palette wraps, so it is total however many speakers turn up; a generator
         * multiplies rather than accumulates, which is the same walk the client does and answers the same for a
         * given {@code n} however it was reached.
         */
        public Color color(int n) {
            if(n < 0)
                n = 0;
            if(palette != null)
                return palette[n % palette.length];
            return new Color(Color.HSBtoRGB((float)((((double)n + 1) * step) % 1.0),
                                            (float)saturation, (float)brightness));
        }

        public int hashCode() {
            if(palette != null)
                return Arrays.hashCode(palette);
            long b = (Double.doubleToLongBits(step) * 31 + Double.doubleToLongBits(saturation)) * 31
                + Double.doubleToLongBits(brightness);
            return (int)(b ^ (b >>> 32));
        }

        public boolean equals(Object o) {
            if(!(o instanceof Seq))
                return false;
            Seq s = (Seq)o;
            if((palette == null) != (s.palette == null))
                return false;
            if(palette != null)
                return Arrays.equals(palette, s.palette);
            return (step == s.step) && (saturation == s.saturation) && (brightness == s.brightness);
        }

        /** The value in the shape the setter takes, so a read round-trips into a write. */
        LuaTable toLua() {
            LuaTable t = new LuaTable();
            if(palette != null) {
                LuaTable p = new LuaTable();
                for(int i = 0; i < palette.length; i++)
                    p.set(i + 1, AddonManager.color(palette[i]));
                t.set("palette", p);
            } else {
                LuaTable g = new LuaTable();
                g.set("step", LuaValue.valueOf(step));
                g.set("saturation", LuaValue.valueOf(saturation));
                g.set("brightness", LuaValue.valueOf(brightness));
                t.set("generate", g);
            }
            return t;
        }
    }

    /** What a sequence is, spelled out — what every refusal here carries. */
    private static final String SEQS =
        "{ palette = { {r,g,b}, … } } — the colours to cycle — or { generate = { step = , saturation = ,"
        + " brightness = } }, a walk around the hue circle with each field 0..1";

    /**
     * Is {@code v} written as a colour SEQUENCE rather than as a colour? Asked by {@code rule:color}, which takes
     * both: the two spellings are told apart by their own field names, and nothing else in a colour value uses
     * either, so the test is exact and no colour can be mistaken for a sequence.
     */
    static boolean isSeq(LuaValue v) {
        return v.istable() && (!v.get("palette").isnil() || !v.get("generate").isnil());
    }

    /**
     * The two site keys whose {@code color} is a <b>sequence</b> and nothing else (065.16). Both are colours the
     * client hands out <i>per something</i> — one per speaker, one per urgency level — and one flat colour
     * across the lot destroys the very distinction each exists to draw. So neither takes a colour, and every
     * other key takes only a colour.
     *
     * <p><b>Every level alike is still sayable</b>, and has to be said: a one-entry {@code palette} cycles to the
     * same colour for every answer. That is a deliberate sentence rather than an accident of writing
     * {@code color(r, g, b)} on the wrong key, which is the whole reason the refusal is here.
     */
    private static final String[] SEQ_KEYS = {"chat.speaker", "chat.urgent"};

    /** Does the key {@code scope} names take a sequence rather than a colour? {@code null} is a TREE key: no. */
    static boolean seqKey(String scope) {
        for(int i = 0; i < SEQ_KEYS.length; i++) {
            if(SEQ_KEYS[i].equals(scope))
                return true;
        }
        return false;
    }

    /**
     * Which shape a {@code color} value takes on this key — {@code true} to read it as a sequence — or the
     * refusal when it takes neither. The one gate both doors go through, {@code rule:color} and
     * {@code sheet:load}, so the two cannot come to differ about it or about how they say so.
     *
     * <p>On a key the client WALKS, anything that is not recognisably a colour is read as a sequence rather
     * than measured against {@link #isSeq}. That is what makes a <b>misspelt field</b> answerable: a value is
     * refused for the field that is wrong, which is the one the author can see and fix, instead of for the
     * shape it failed to be, which tells them nothing about which of their keys was the typo.
     */
    static boolean seqShape(String verb, String scope, LuaValue v, boolean positional) {
        if(!seqKey(scope)) {
            if(!positional && isSeq(v))
                throw new LuaError(verb + ": a colour SEQUENCE is taken by \"" + SEQ_KEYS[0] + "\" and \""
                    + SEQ_KEYS[1] + "\" alone — every other surface is painted in ONE colour, written"
                    + " {r,g,b[,a]}");
            return false;
        }
        if(positional || !v.istable() || (AddonManager.luaColor(v, null) != null)) {
            throw new LuaError(verb + ": \"" + scope + "\" is a colour the client WALKS rather than one it"
                + " holds, so it takes a sequence: " + SEQS + ". For every answer alike, say so — a palette of"
                + " one colour cycles to it every time");
        }
        return true;
    }

    /**
     * Parse a rule's {@code color} where it names a sequence (065.16). Exactly one of the two spellings, because
     * a value carrying both says two different things about the same question and neither is the obvious winner.
     */
    static Seq parseSeq(String verb, LuaValue v) {
        LuaValue pal = v.get("palette"), gen = v.get("generate");
        if(!pal.isnil() && !gen.isnil())
            throw new LuaError(verb + ": names both a palette and a generator — a sequence is " + SEQS);
        for(Varargs n = v.next(LuaValue.NIL); !n.arg1().isnil(); n = v.next(n.arg1())) {
            String p = key(n.arg1());
            if(!"palette".equals(p) && !"generate".equals(p))
                throw new LuaError(verb + ": \"" + n.arg1().tojstring() + "\" is not a sequence property"
                    + " — a sequence is " + SEQS);
        }
        if(pal.isnil() && gen.isnil())
            throw new LuaError(verb + ": names neither a palette nor a generator — a sequence is " + SEQS);
        if(!pal.isnil())
            return new Seq(parsePalette(verb, pal));
        return parseGenerate(verb, gen);
    }

    private static Color[] parsePalette(String verb, LuaValue v) {
        if(!v.istable())
            throw new LuaError(verb + ".palette: expected an array of colours, got " + v.typename());
        int n = v.length();
        if(n < 1)
            throw new LuaError(verb + ".palette: is empty — a palette is the colours to cycle, and cycling"
                + " none of them leaves nothing to draw with");
        Color[] out = new Color[n];
        for(int i = 0; i < n; i++) {
            LuaValue e = v.get(i + 1);
            Color c = e.istable() ? AddonManager.luaColor(e, null) : null;
            if(c == null)
                throw new LuaError(verb + ".palette[" + (i + 1) + "]: expected a colour table with 0..255"
                    + " components — { 96, 96, 0 } or { r = 96, g = 96, b = 0, a = 255 }");
            out[i] = c;
        }
        return out;
    }

    private static Seq parseGenerate(String verb, LuaValue v) {
        if(!v.istable())
            throw new LuaError(verb + ".generate: expected " + SEQS + ", got " + v.typename());
        double[] f = new double[3];
        boolean[] set = new boolean[3];
        String[] names = {"step", "saturation", "brightness"};
        for(Varargs n = v.next(LuaValue.NIL); !n.arg1().isnil(); n = v.next(n.arg1())) {
            String p = key(n.arg1());
            LuaValue pv = n.arg(2);
            int i = 0;
            while((i < names.length) && !names[i].equals(p))
                i++;
            if(i == names.length)
                throw new LuaError(verb + ".generate: \"" + n.arg1().tojstring() + "\" is not a generator"
                    + " property — a generator names " + names[0] + ", " + names[1] + " and " + names[2]);
            // type() rather than isnumber(): in LuaJ a STRING that looks like a number answers isnumber() (028).
            if(pv.type() != LuaValue.TNUMBER)
                throw new LuaError(verb + ".generate." + p + ": expected a number 0..1, got " + pv.typename());
            double d = pv.todouble();
            if((d < 0) || (d > 1))
                throw new LuaError(verb + ".generate." + p + ": " + d + " is outside 0..1 — a step is a"
                    + " fraction of the hue circle, and a saturation and a brightness are fractions of full");
            f[i] = d;
            set[i] = true;
        }
        for(int i = 0; i < names.length; i++) {
            if(!set[i])
                throw new LuaError(verb + ".generate: names no " + names[i] + " — a generator is all three"
                    + " of " + names[0] + ", " + names[1] + " and " + names[2] + ", since each decides a"
                    + " different thing about every colour it mints");
        }
        if(f[0] <= 0)
            throw new LuaError(verb + ".generate.step: 0 never moves off one hue, so every speaker would"
                + " be the same colour — a step is how far around the circle each answer goes");
        return new Seq(f[0], f[1], f[2]);
    }

    // ---- the close button (065.5) ------------------------------------------------------------------

    /**
     * A rule's {@code close}: the <b>button</b> a window's decoration draws in one of its corners — its art, the
     * two faces that art wears while the pointer is on it and while it is held, and the {@link Spot} it sits at.
     *
     * <p><b>The art and the place are independent</b>, and either alone is a whole value: a theme that wants the
     * client's own X moved says only {@code at}, one that wants its own button where the client puts it says only
     * the art. With neither, this property is not written at all and the client's own button sits in the client's
     * own corner, to the pixel.
     *
     * <p><b>State rides inside the value</b> rather than in the selector: {@code hover} and {@code pressed} are
     * ordinary surfaces of the same shape as the face they vary, and each falls back to the released one exactly
     * as the engine's own two-image button does. The rasteriser already knows its own state; nothing publishes
     * one to the cascade.
     *
     * <p><b>The art is the button's box.</b> Its own drawn size is what the button is resized to
     * ({@link Art#natural}), so a face is never squeezed into someone else's rectangle — which is also why a flat
     * colour leaves the box alone: it has no size of its own to give, so the client's own stays.
     */
    static final class Close {
        /** The released face, or {@code null} — the client's own art, then. */
        final Art up;
        /** The face under the pointer, or {@code null} — {@link #up}, as an engine button's own default is. */
        final Art hover;
        /** The face while it is held, or {@code null} — {@link #up} again. */
        final Art pressed;
        /** Where the button is pinned, or {@code null} — the client's own top-right corner, then. */
        final Spot at;

        Close(Art up, Art hover, Art pressed, Spot at) {
            this.up = up;
            this.hover = hover;
            this.pressed = pressed;
            this.at = at;
        }

        /** The face a button in this state wears — never {@code null} where {@link #up} is not. */
        Art face(boolean held, boolean under) {
            if(held && under && (pressed != null))
                return pressed;
            if(under && (hover != null))
                return hover;
            return up;
        }

        /** The box this button's art asks for — device pixels — or {@code null} where the art gives none. */
        Coord size() {
            return (up == null) ? null : up.natural();
        }

        /**
         * Do these two say the same thing about the <b>faces</b>? That is the question a decoration asks, because
         * only a changed face costs a rebuilt button ({@code IButton}'s are final); a changed spot merely moves
         * the one that is there.
         */
        boolean sameFaces(Close o) {
            return (o != null) && same(up, o.up) && same(hover, o.hover) && same(pressed, o.pressed);
        }

        public int hashCode() {
            return ((up == null) ? 0 : up.hashCode()) + ((hover == null) ? 0 : (hover.hashCode() * 7))
                + ((pressed == null) ? 0 : (pressed.hashCode() * 13)) + ((at == null) ? 0 : (at.hashCode() * 17));
        }

        public boolean equals(Object o) {
            if(!(o instanceof Close))
                return false;
            Close c = (Close)o;
            return sameFaces(c) && ((at == null) ? (c.at == null) : at.equals(c.at));
        }

        /** {@code rule:close()} — the art's own fields, its two variants and its spot, as the setter takes them. */
        LuaValue toLua(Addon reader) {
            LuaTable t = (up == null) ? new LuaTable() : up.toLua(reader);
            if(hover != null)
                t.set("hover", hover.toLua(reader));
            if(pressed != null)
                t.set("pressed", pressed.toLua(reader));
            if(at != null) {
                t.set("at", LuaValue.valueOf(CORNERS[at.corner]));
                if(at.offset != null)
                    t.set("offset", LuaWidget.xyTable(at.offset));
            }
            return t;
        }
    }

    /** Two pieces of art, either of which may be absent — see {@link Close#sameFaces}. */
    private static boolean same(Art a, Art b) {
        return (a == null) ? (b == null) : a.equals(b);
    }

    // ---- bg ----------------------------------------------------------------------------------------

    /**
     * A rule's {@code bg}: the surface something is painted on — <b>one</b> {@link Art}, or a <b>list</b> of
     * them painted in order. The list is not a new primitive: it is the value that already exists, at a
     * different arity, and it is what the client's own window background is (a tiled field, then a shade down
     * each side). A texture under a vignette is the same shape.
     *
     * <p><b>A face per state</b> (065.8): beside its own layers a background may name a whole background for
     * each of the {@link #STATES} — the same shape as the value it varies, because it <i>is</i> that value at
     * another moment. State rides inside the value and never in the selector: the surface drawing itself
     * already knows which state it is in, and the alternative — a {@code :hover} key in the cascade — would
     * make every site publish its state to a resolution path that runs before the draw. A state a rule says
     * nothing about is the value it sits in ({@link #state}), so a theme names only what it wants to differ.
     */
    static final class Bg {
        /** The layers, in paint order. Never empty: a {@code bg} that says nothing is refused where it is written. */
        final Art[] layers;
        /** The face for each of {@link #STATES}, by index — {@code null} entries, and a {@code null} array, for none. */
        final Bg[] states;

        Bg(Art[] layers, Bg[] states) {
            this.layers = layers;
            this.states = states;
        }

        /** This background as the surface wears it in state {@code i}, which is this one where it names none. */
        Bg state(int i) {
            Bg s = ((states == null) || (i < 0)) ? null : states[i];
            return (s == null) ? this : s;
        }

        /**
         * The size this background's own art asks for — device pixels — or {@code null} where none of its
         * layers has one, a flat colour filling whatever surface it is given (065.9). The largest of them
         * on each axis, because a background is as big as the biggest thing painted in it.
         *
         * <p>It is the one place a {@code bg}'s own size decides a <b>widget's</b> rather than filling a box
         * the client already decided: a text field measures itself from its background, so a taller art
         * makes a taller field. Everywhere else this is never asked and the art fills the client's own box.
         */
        Coord natural() {
            Coord n = null;
            for(int i = 0; i < layers.length; i++) {
                Coord l = layers[i].natural();
                if(l != null)
                    n = (n == null) ? l : Coord.of(Math.max(n.x, l.x), Math.max(n.y, l.y));
            }
            return n;
        }

        /** Paint this background over {@code [ul, ul+sz)} — every layer, in the order the rule wrote them. */
        void draw(GOut g, Coord ul, Coord sz) {
            for(int i = 0; i < layers.length; i++)
                layers[i].draw(g, ul, sz);
        }

        public int hashCode() {
            int h = layers.length;
            for(int i = 0; i < layers.length; i++)
                h = (h * 31) + layers[i].hashCode();
            for(int i = 0; (states != null) && (i < states.length); i++)
                h = (h * 37) + ((states[i] == null) ? 0 : states[i].hashCode());
            return h;
        }

        public boolean equals(Object o) {
            if(!(o instanceof Bg))
                return false;
            Bg b = (Bg)o;
            if(layers.length != b.layers.length)
                return false;
            for(int i = 0; i < layers.length; i++) {
                if(!layers[i].equals(b.layers[i]))
                    return false;
            }
            for(int i = 0; i < STATES.length; i++) {
                Bg x = (states == null) ? null : states[i], y = (b.states == null) ? null : b.states[i];
                if((x == null) ? (y != null) : !x.equals(y))
                    return false;
            }
            return true;
        }

        /**
         * {@code rule:bg()} — the value as {@code reader} may hold it, at the arity it was written: one surface
         * is one table, several are an array in paint order, and each state face is keyed beside them by its own
         * name. So a read round-trips into a write either way.
         */
        LuaValue toLua(Addon reader) {
            LuaTable t;
            if(layers.length == 1) {
                LuaValue one = layers[0].toLua(reader);
                if(states == null)
                    return one;
                t = (LuaTable)one;
            } else {
                t = new LuaTable();
                for(int i = 0; i < layers.length; i++)
                    t.set(i + 1, layers[i].toLua(reader));
            }
            for(int i = 0; (states != null) && (i < states.length); i++) {
                if(states[i] != null)
                    t.set(STATES[i], states[i].toLua(reader));
            }
            return t;
        }
    }

    // ---- picture (065.12) --------------------------------------------------------------------------

    /**
     * A rule's {@code picture}: the whole plate a surface <b>is</b>, where the client blits a picture rather
     * than framing something. One {@link Art} and its {@link #STATES state} faces — not a list, because layers
     * are what a <i>background</i> has: a plate is the picture, and anything under it would never be seen.
     *
     * <p><b>It is the third arity of one value</b>, beside {@link Bg} (a stack of surfaces) and {@link Border}
     * (a frame cut out of one). What tells them apart is not their pixels but what the site does with them: a
     * {@code bg} is painted under content, a {@code border} around it, and a {@code picture} <i>instead</i> of
     * the art the site would have blitted. So a rule may carry all three and nothing collides.
     *
     * <p><b>Read at the draw, never written into the widget.</b> An {@code Img} is re-pointed by the server, so
     * a {@code setimg} write would be clobbered by the next {@code uimsg} and would fight the restore when the
     * rule goes away. The site asks instead ({@link #picture(Widget)}) and falls back to its own art, which is
     * what keeps a stock client stock and a dropped sheet exact.
     */
    static final class Pic implements Fonts.Picture {
        /** The plate at rest. Never {@code null}: a picture that says nothing is refused where it is written. */
        final Art art;
        /** The face for each of {@link #STATES}, by index — {@code null} entries, and a {@code null} array, for none. */
        final Pic[] states;

        Pic(Art art, Pic[] states) {
            this.art = art;
            this.states = states;
        }

        /** This picture as the surface wears it in state {@code i}, which is this one where it names none. */
        Pic state(int i) {
            Pic s = ((states == null) || (i < 0)) ? null : states[i];
            return (s == null) ? this : s;
        }

        /** Paint the plate over {@code [ul, ul+sz)} — the very box the site was going to blit its own art in. */
        public void draw(GOut g, Coord ul, Coord sz) {
            art.draw(g, ul, sz);
        }

        public int hashCode() {
            int h = art.hashCode();
            for(int i = 0; (states != null) && (i < states.length); i++)
                h = (h * 37) + ((states[i] == null) ? 0 : states[i].hashCode());
            return h;
        }

        public boolean equals(Object o) {
            if(!(o instanceof Pic))
                return false;
            Pic p = (Pic)o;
            if(!art.equals(p.art))
                return false;
            for(int i = 0; i < STATES.length; i++) {
                Pic x = (states == null) ? null : states[i], y = (p.states == null) ? null : p.states[i];
                if((x == null) ? (y != null) : !x.equals(y))
                    return false;
            }
            return true;
        }

        /**
         * {@code rule:picture()} — the surface's own fields, with each state face keyed beside them by its own
         * name, exactly as the setter takes them.
         */
        LuaValue toLua(Addon reader) {
            LuaTable t = art.toLua(reader);
            for(int i = 0; (states != null) && (i < states.length); i++) {
                if(states[i] != null)
                    t.set(STATES[i], states[i].toLua(reader));
            }
            return t;
        }
    }

    // ---- border ------------------------------------------------------------------------------------

    /**
     * A rule's {@code border}: a frame drawn around a surface, said one of three ways. Either <b>your own art</b>
     * plus the four insets that cut it into a 9-slice, or <b>one of the client's own boxes</b> named by its
     * resource folder ({@code {box = "gfx/hud/wnd"}}) — the engine's eight-part {@link IBox}, whose insets are
     * its corners' own sizes rather than a slice — or a <b>line</b>, {@code {color = {r,g,b[,a]}, width = n}}
     * (065.6). The centre is <b>not</b> painted any of the three ways: that is {@link Bg}'s job, so the two
     * properties compose instead of overwriting each other.
     *
     * <p><b>A line is a frame with no picture behind it</b>, and it is what the surfaces the client draws
     * <i>in code</i> rather than from a resource are made of — the tooltip's outline is two colours and a
     * rectangle, and a theme that wants to restate it has nothing to ship. So it takes none of the fields that
     * say where a picture's pixels come from: no {@code slice} (there is no art to cut), and no {@code mode}
     * (there is no edge art to repeat). Its {@code width} is design pixels, like every other distance here, and
     * it answers the same {@link #tlIn}/{@link #brIn} pair a 9-slice does, so a window framed by a line reserves
     * exactly the room the line paints.
     *
     * <p><b>{@code mode} is what a 9-slice alone cannot say.</b> {@code IBox.Scaled} stretches its four edges
     * between the corners; the client's own window decoration <i>repeats</i> them, a blit per tile clipped to
     * the run, which is why a wide window's frame art stays at its authored weight instead of smearing. So a
     * border says which of the two it wants, and {@link Tiled} is the second implementation.
     *
     * <p><b>A slice is in design pixels</b> (058.3) — the same space {@code padding} and a rule's
     * {@code position} are written in — whichever spelling named the art; {@link Src} is where that is
     * converted. The insets a layout reserves are the drawn corners themselves ({@link #tlIn}/{@link #brIn}
     * read the box), so the frame the draw paints and the room {@link SkinDeco#iresize} keeps for it are the
     * same rectangle by construction.
     *
     * <p><b>{@code parts} is what a 9-slice cannot say either</b> (065.4): the pieces a frame carries that are
     * neither a corner nor a run — the client's own foot piece at the bottom of its left edge being one, a rivet
     * or a crest being the theme's. Each is an ordinary {@link Art} pinned by its own {@code at} and
     * {@code offset}, so it is no new value, only the surface at a different arity, and they are painted over
     * the frame in the order the rule wrote them.
     */
    static final class Border {
        /** The 9-slice art, or {@code null} when this border is one of the client's own boxes, or a line. */
        final Src src;
        /** The client box's resource folder, or {@code null} when this border is art of your own, or a line. */
        final String box;
        /** The line's colour (065.6), or {@code null} when this border is art. */
        final Color line;
        /** The line's thickness in design pixels, or {@code 0} when this border is art. */
        final int width;
        /** The four slice insets, in design pixels. All zero on a {@code box} or a line, which carry their own. */
        final int l, t, r, b;
        /** {@link #STRETCH} or {@link #TILE} — what the four edges do between the corners. */
        final String mode;
        /** The pieces pinned inside this frame (065.4), in paint order, or {@code null} when it carries none. */
        final Art[] parts;
        private final Tex[] pieces;       // a box border's eight textures, resolved where the rule was written
        private IBox ibox;                // built on first use -- no upload, nothing to free

        /** A frame cut out of art — your own 9-slice, or one of the client's own boxes. */
        Border(Src src, String box, int l, int t, int r, int b, String mode, Art[] parts, Tex[] pieces) {
            this(src, box, null, 0, l, t, r, b, mode, parts, pieces);
        }

        /** A frame that is a <b>line</b> (065.6): one colour, one thickness, all the way round. */
        Border(Color line, int width, Art[] parts) {
            this(null, null, line, width, 0, 0, 0, 0, STRETCH, parts, null);
        }

        private Border(Src src, String box, Color line, int width, int l, int t, int r, int b, String mode,
                       Art[] parts, Tex[] pieces) {
            this.src = src;
            this.box = box;
            this.line = line;
            this.width = width;
            this.l = l; this.t = t; this.r = r; this.b = b;
            this.mode = mode;
            this.parts = parts;
            this.pieces = pieces;
        }

        /**
         * The left/top insets in <b>device</b> pixels — the room the drawn corners actually take, and what a
         * window's chrome lays its content out against ({@link SkinDeco#iresize}). It is the box's own
         * {@code ctloff()}, which is the very corner the draw paints, so the two cannot drift apart; a line's is
         * its own thickness, for the same reason.
         */
        Coord tlIn() {
            return (line != null) ? Px.in(Coord.of(width, width)) : ibox().ctloff();
        }

        /** The right/bottom insets in device pixels — see {@link #tlIn}. */
        Coord brIn() {
            return (line != null) ? Px.in(Coord.of(width, width)) : ibox().cbroff();
        }

        /**
         * The eight-part box this border draws with. Built lazily and once: for a 9-slice it is eight
         * {@link TexSI} windows onto the same texture, so it allocates a handful of small objects and <b>no</b>
         * GPU memory; for a {@code box} it is the client's own textures, straight from the resource cache.
         */
        IBox ibox() {
            IBox c = this.ibox;
            if(c == null) {
                Tex[] p = (pieces != null) ? pieces : slices();
                this.ibox = c = TILE.equals(mode)
                    ? new Tiled(p[0], p[1], p[2], p[3], p[4], p[5], p[6], p[7])
                    : new IBox.Scaled(p[0], p[1], p[2], p[3], p[4], p[5], p[6], p[7]);
            }
            return c;
        }

        /** The eight windows a {@code slice} cuts out of this border's art, in {@link IBox.Images} order. */
        private Tex[] slices() {
            Coord sz = src.size();
            int w = sz.x, h = sz.y;
            return new Tex[] {
                src.sub(0,     0,     l,         t),           // ctl
                src.sub(w - r, 0,     r,         t),           // ctr
                src.sub(0,     h - b, l,         b),           // cbl
                src.sub(w - r, h - b, r,         b),           // cbr
                src.sub(0,     t,     l,         h - t - b),   // left edge
                src.sub(w - r, t,     r,         h - t - b),   // right edge
                src.sub(l,     0,     w - l - r, t),           // top edge
                src.sub(l,     h - b, w - l - r, b),           // bottom edge
            };
        }

        /**
         * Paint this border around {@code [ul, ul+sz)}. A surface smaller than the border's own corners is left
         * alone rather than drawn inside out — inert, never an error, which is the doctrine every other
         * unappliable property here follows.
         */
        void draw(GOut g, Coord ul, Coord sz) {
            if((src != null) && src.dead())
                return;
            Coord tl = tlIn(), br = brIn();          // the DRAWN corners: sz is the client's own device box
            if((sz.x < tl.x + br.x) || (sz.y < tl.y + br.y))
                return;
            if(line != null)
                drawline(g, ul, sz, tl.x);
            else
                ibox().draw(g, ul, sz);
            Art[] ps = this.parts;                   // ...and the pinned pieces, over the frame they sit on
            for(int i = 0; (ps != null) && (i < ps.length); i++)
                ps[i].draw(g, ul, sz);
        }

        /**
         * The four runs of a <b>line</b> frame (065.6): an outline {@code w} device pixels thick, drawn
         * <i>inside</i> the box's own edge so that the room {@link #tlIn} reserves is the room it paints. The
         * two verticals stop short of the horizontals rather than overdrawing them, which is what keeps a
         * translucent colour one weight the whole way round instead of doubling at the corners.
         */
        private void drawline(GOut g, Coord ul, Coord sz, int w) {
            g.chcolor(line);
            g.frect(ul, Coord.of(sz.x, w));
            g.frect(ul.add(0, sz.y - w), Coord.of(sz.x, w));
            g.frect(ul.add(0, w), Coord.of(w, sz.y - (w * 2)));
            g.frect(ul.add(sz.x - w, w), Coord.of(w, sz.y - (w * 2)));
            g.chcolor();
        }

        public int hashCode() {
            int h = ((line != null) ? ((line.hashCode() * 43) + width)
                                    : ((src == null) ? box.hashCode() : (src.hashCode() * 31)))
                + (l * 7) + (t * 13) + (r * 17) + (b * 19) + (mode.hashCode() * 23);
            for(int i = 0; (parts != null) && (i < parts.length); i++)
                h = (h * 31) + parts[i].hashCode();
            return h;
        }

        public boolean equals(Object o) {
            if(!(o instanceof Border))
                return false;
            Border x = (Border)o;
            return ((src == null) ? (x.src == null) : src.equals(x.src))
                && ((box == null) ? (x.box == null) : box.equals(x.box))
                && ((line == null) ? (x.line == null) : line.equals(x.line)) && (width == x.width)
                && (l == x.l) && (t == x.t) && (r == x.r) && (b == x.b) && mode.equals(x.mode)
                && sameArt(parts, x.parts);
        }

        /** {@code rule:border()} — the value as {@code reader} may hold it, in the shape the setter takes. */
        LuaValue toLua(Addon reader) {
            LuaTable t = new LuaTable();
            if(line != null) {
                t.set("color", AddonManager.color(line));
                t.set("width", LuaValue.valueOf(width));
            } else if(box != null) {
                t.set("box", LuaValue.valueOf(box));
                t.set("mode", LuaValue.valueOf(mode));
            } else {
                src.toLua(reader, t);
                LuaTable s = new LuaTable();
                s.set("l", LuaValue.valueOf(this.l));
                s.set("t", LuaValue.valueOf(this.t));
                s.set("r", LuaValue.valueOf(this.r));
                s.set("b", LuaValue.valueOf(this.b));
                t.set("slice", s);
                t.set("mode", LuaValue.valueOf(mode));
            }
            if(parts != null) {
                LuaTable ps = new LuaTable();
                for(int i = 0; i < parts.length; i++)
                    ps.set(i + 1, parts[i].toLua(reader));
                t.set("parts", ps);
            }
            return t;
        }
    }

    /** Two lists of pinned pieces, compared by value — see {@link Border#equals}. */
    private static boolean sameArt(Art[] a, Art[] b) {
        if((a == null) || (b == null))
            return (a == null) && (b == null);
        if(a.length != b.length)
            return false;
        for(int i = 0; i < a.length; i++) {
            if(!a[i].equals(b[i]))
                return false;
        }
        return true;
    }

    /**
     * The eight-part box whose four edges <b>repeat</b> rather than stretch — the client's own decoration read
     * as a general shape (065.2). Geometry for geometry it is {@code IBox.Scaled}: the same corners at the same
     * places and the same runs between them, differing only in that each run is a blit per tile <b>clipped</b>
     * to the run. Clipping is the whole of it: a tiling box that scaled its last tile instead would draw a seam
     * at one end of every long window.
     */
    static final class Tiled extends IBox.Images {
        Tiled(Tex ctl, Tex ctr, Tex cbl, Tex cbr, Tex bl, Tex br, Tex bt, Tex bb) {
            super(ctl, ctr, cbl, cbr, bl, br, bt, bb);
        }

        public void draw(GOut g, Coord tl, Coord sz) {
            runh(g, bt, tl.add(ctl.sz().x, 0), sz.x - ctl.sz().x - ctr.sz().x);
            runh(g, bb, tl.add(cbl.sz().x, sz.y - bb.sz().y), sz.x - cbl.sz().x - cbr.sz().x);
            runv(g, bl, tl.add(0, ctl.sz().y), sz.y - ctl.sz().y - cbl.sz().y);
            runv(g, br, tl.add(sz.x - br.sz().x, ctr.sz().y), sz.y - ctr.sz().y - cbr.sz().y);
            g.image(ctl, tl);
            g.image(ctr, tl.add(sz.x - ctr.sz().x, 0));
            g.image(cbl, tl.add(0, sz.y - cbl.sz().y));
            g.image(cbr, tl.add(sz.sub(cbr.sz())));
        }

        /** One horizontal run: {@code w} device pixels of {@code t}, repeated and clipped to the run. */
        private static void runh(GOut g, Tex t, Coord c, int w) {
            if((w <= 0) || (t.sz().x <= 0))
                return;
            Coord br = c.add(w, t.sz().y);
            for(int x = 0; x < w; x += t.sz().x)
                g.image(t, c.add(x, 0), c, br);
        }

        /** One vertical run — see {@link #runh}. */
        private static void runv(GOut g, Tex t, Coord c, int h) {
            if((h <= 0) || (t.sz().y <= 0))
                return;
            Coord br = c.add(t.sz().x, h);
            for(int y = 0; y < h; y += t.sz().y)
                g.image(t, c.add(0, y), c, br);
        }
    }

    // ---- padding -----------------------------------------------------------------------------------

    /**
     * A rule's {@code padding}: the room a surface keeps between its frame and its content, on <b>each of the
     * four sides</b> — one number for all of them, or {@code {l, t, r, b}}. It answers the same pair
     * {@link Border} does, {@link #tlIn}/{@link #brIn}, because the two are summed side by side by the one
     * method that lays a window out ({@link SkinDeco#iresize}): the frame's own insets and the breathing room
     * inside them.
     *
     * <p><b>Design pixels, converted where they are spent</b>: {@code iresize} adds them to
     * {@code Window.dlmrgn}/{@code dsmrgn}, which are {@code UI.scale}d, so an unconverted padding would be the
     * one term in that sum meaning something else. Kept as written, so {@code rule:padding()} reads back the
     * four numbers the rule said on every client.
     *
     * <p>Immutable, with value equality: the resolved style is interned on it ({@code Sheet.SKey}) and the
     * window chrome compares it by value to decide whether a changed rule has to re-lay a window out.
     */
    static final class Pad {
        /** The four insets, in design pixels. */
        final int l, t, r, b;

        Pad(int l, int t, int r, int b) {
            this.l = l; this.t = t; this.r = r; this.b = b;
        }

        /** The left/top room in <b>device</b> pixels — the space a layout actually reserves. */
        Coord tlIn() {
            return Px.in(Coord.of(l, t));
        }

        /** The right/bottom room in device pixels — see {@link #tlIn}. */
        Coord brIn() {
            return Px.in(Coord.of(r, b));
        }

        /**
         * Is this padding nothing at all? A rule saying {@code padding(0)} says the same thing as one saying no
         * padding, so it alone never dresses a window — the chrome it would install would draw stock pixels at
         * stock coordinates.
         */
        boolean zero() {
            return (l == 0) && (t == 0) && (r == 0) && (b == 0);
        }

        public int hashCode() {
            return (l * 7) + (t * 13) + (r * 17) + (b * 19);
        }

        public boolean equals(Object o) {
            if(!(o instanceof Pad))
                return false;
            Pad p = (Pad)o;
            return (l == p.l) && (t == p.t) && (r == p.r) && (b == p.b);
        }

        /**
         * {@code rule:padding()} — the four numbers, keyed. The same shape a border's {@code slice} reads back
         * as, and the same reason: it is an argument the setter takes, so a read round-trips into a write.
         */
        LuaValue toLua() {
            LuaTable t = new LuaTable();
            t.set("l", LuaValue.valueOf(this.l));
            t.set("t", LuaValue.valueOf(this.t));
            t.set("r", LuaValue.valueOf(this.r));
            t.set("b", LuaValue.valueOf(this.b));
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
     * from the stock ones would move a panel's frame without moving anything inside it. So {@code padding} is inert
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
        Bg bg = bg(st);
        Border border = border(st);
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

    // ---- a box the CLIENT sizes and the theme dresses (065.4) --------------------------------------

    /**
     * {@code Fonts.drawchrome(scope, wdg, g, ul, sz)} — paint one site's own {@code bg} and {@code border} over
     * a rectangle the <b>client</b> computed, answering whether a rule named either. {@code false} is the site's
     * cue to paint exactly what it always painted.
     *
     * <p>The caption <b>plate</b> is the first of these and says what the shape is for: {@code window.title}
     * carries the plate behind a window's caption, but the box it is drawn at is
     * {@code DefaultDeco.checkcap}'s — {@code cptl}/{@code cpsz}, sized from the caption's own width — so the
     * client goes on deciding how wide a plate is and the theme says only what it looks like. That is the same
     * division of labour {@link SkinBox} has one level down, at the one arity that has no {@link IBox} in it:
     * here the site hands over a rectangle rather than a box, because it draws no frame of its own to stand in
     * for.
     *
     * <p>Nothing is interned: the two values are read straight off the resolved style and painted, because the
     * rectangle is recomputed by the site anyway and there is no object to hand back.
     */
    static boolean draw(String scope, Widget wdg, GOut g, Coord ul, Coord sz) {
        if((sz == null) || (sz.x <= 0) || (sz.y <= 0))
            return false;
        Fonts.Chrome p = chrome(scope, wdg, null);
        if(p == null)
            return false;                   // a text-only rule paints no plate: the site keeps its own art
        p.draw(g, ul, sz);
        return true;
    }

    /**
     * One site's own paint — its {@code bg} under its {@code border}, in that order — held so that a site
     * paving a surface with the <b>same</b> box many times resolves the rule once rather than once per box
     * ({@link #chrome}). It is the whole of what {@link #draw} does, named, because a grid needs the answer
     * before its loop and a plate needs it inside a single call.
     */
    static final class Paint implements Fonts.Chrome {
        private final Bg bg;
        private final Border border;

        private Paint(Bg bg, Border border) {
            this.bg = bg;
            this.border = border;
        }

        public boolean bg() {
            return bg != null;
        }

        public boolean border() {
            return border != null;
        }

        public void drawbg(GOut g, Coord ul, Coord sz) {
            if(bg != null)
                bg.draw(g, ul, sz);
        }

        public void drawborder(GOut g, Coord ul, Coord sz) {
            if(border != null)
                border.draw(g, ul, sz);
        }

        public void draw(GOut g, Coord ul, Coord sz) {
            drawbg(g, ul, sz);
            drawborder(g, ul, sz);
        }
    }

    /**
     * One {@link Paint} per (resolved style, state) — the same interning {@link #boxes} does one level up, and
     * for the same reason: an inventory asks once per draw and a button asks once per raster, and neither may
     * allocate. The array is indexed {@code state + 1}, slot {@code 0} being the surface at rest, and a state
     * the rule does not vary shares that slot rather than minting a paint of its own. Held weakly, because a
     * style stops existing when the rules change. Guarded by {@code Chrome.class}.
     */
    private static final Map<Fonts.Style, Paint[]> paints = new WeakHashMap<Fonts.Style, Paint[]>();

    /**
     * {@code Fonts.chrome(scope, wdg, state)} — the paint this site's rule does in the state it is in, or
     * {@code null} when it names neither a {@code bg} nor a {@code border} and the site should draw its own art
     * (065.7, 065.8).
     *
     * <p>The <b>inventory square</b> is the first site to want it: a grid draws one box per cell, so it reads
     * the answer at the top of its loop and paints from it, where a plate or a tooltip resolves and paints in
     * one breath ({@link #draw}). A <b>button</b> is the first to want it in a state, and asks for the one it
     * is in: only the {@code bg} varies, since a frame that changed with the pointer would change the room the
     * frame reserves ({@link Border#tlIn}) and move the very content it is drawn around.
     */
    static Fonts.Chrome chrome(String scope, Widget wdg, String state) {
        return paint(Fonts.styleFor(scope, wdg), state);
    }

    /**
     * {@code Fonts.chrome(scope)} — the same paint for a surface that is <b>no widget</b> (065.11), and so has
     * none to resolve a tree rule against: the speech bubble over a character, drawn by a {@code Speaking}
     * {@code GAttrib} rather than by anything in the tree.
     *
     * <p>It is the site half of the cascade alone, {@link #size} one property along, and the very path that
     * bubble's <i>font</i> already resolves through — so the two halves of one key cannot drift apart. A
     * surface with no widget has no state either: nothing hovers a speech bubble.
     */
    static Fonts.Chrome chrome(String scope) {
        return paint(Fonts.style(scope), null);
    }

    /** The interned {@link Paint} one resolved style does in one state, or {@code null} when it paints nothing. */
    private static Fonts.Chrome paint(Fonts.Style st, String state) {
        Bg bg = bg(st);
        Border bd = border(st);
        if((bg == null) && (bd == null))
            return null;
        int i = stateOf(state);
        Bg face = (bg == null) ? null : bg.state(i);
        int slot = ((face == bg) ? -1 : i) + 1;      // a state this rule does not vary IS the surface at rest
        synchronized(Chrome.class) {
            Paint[] ps = paints.get(st);
            if(ps == null)
                paints.put(st, ps = new Paint[STATES.length + 1]);
            Paint p = ps[slot];
            if(p == null)
                ps[slot] = p = new Paint(face, bd);
            return p;
        }
    }

    /**
     * {@code Fonts.chromepad(scope, wdg)} — the room this site's own {@code padding} asks for, as
     * {@code {tl, br}} in <b>device</b> pixels, or {@code null} when no rule names one (065.6).
     *
     * <p>It is {@link #draw}'s partner, and the two are separate because they are read at different moments: a
     * site that sizes its own box has to widen that box <i>before</i> it knows where to paint. The tooltip is
     * the first — its box is its text plus a margin, computed in {@code UILoop.drawtooltip} — and the shape is
     * the same one {@link SkinDeco#iresize} uses one level up, at the arity that has no layout to re-run: the
     * site adds the two {@link Coord}s to the rectangle it was going to draw anyway.
     */
    static Coord[] pad(String scope, Widget wdg) {
        Pad p = padding(Fonts.styleFor(scope, wdg));
        return (p == null) ? null : new Coord[] {p.tlIn(), p.brIn()};
    }

    /**
     * {@code Fonts.chromesz(scope)} — the size this site's own {@code bg} art asks for, or {@code null} when
     * its rule names none (065.9).
     *
     * <p>The <b>widget-less</b> resolution, and the site that wants it is the reason: a text field measures
     * itself from its background in its own <i>constructor</i>, so there is no widget yet for a tree rule to
     * be matched against and the site half of the cascade is the whole answer. It is the same path a
     * {@code Speaking} bubble resolves through, one property along.
     */
    static Coord size(String scope) {
        Bg bg = bg(Fonts.style(scope));
        return (bg == null) ? null : bg.natural();
    }

    /**
     * {@code Fonts.picture(scope, wdg)} — the whole plate a rule paints in place of {@code wdg}'s own art, or
     * {@code null} when nothing names it (065.12).
     *
     * <p><b>A {@code null} scope is the per-widget resolution</b>, and it is the one answer here that has no
     * site in it at all: a picture the server placed is one widget showing one image rather than a site the
     * client draws a kind of thing at, so what names it is a tree key — {@code ["@Img"]}, or a chain naming the
     * window it sits in — and {@link Sheet#specOf} is that half of the cascade whole. Nothing falls back to
     * {@code "*"} on that path, on purpose: a global rule that repainted every picture in the client would be
     * a theme's first accident.
     *
     * <p><b>A scope is the ordinary site resolution</b> (065.13), {@link Fonts#styleFor} exactly as every other
     * chrome answer above takes it: the tree rule over the site's own stack over {@code "*"}. The plates the
     * client blits at fixed places — the belt, the two menu backgrounds, the action-search plate, the
     * minimap's frame — are sites like any other, and being able to <i>name</i> them is the whole difference
     * from an {@code Img} the server hands over.
     *
     * <p>Nothing is interned: a {@link Pic} <i>is</i> the {@link Fonts.Picture} the site paints from, and the
     * resolved style already holds one object per distinct rule.
     */
    static Fonts.Picture picture(String scope, Widget wdg) {
        return picture((scope == null) ? Sheet.specOf(wdg) : Fonts.styleFor(scope, wdg));
    }

    /**
     * {@code Fonts.emboss(scope, …)} — what this site's rule says about its relief, or {@code null} when it says
     * nothing and the client tiles exactly the texture it always tiled (065.14).
     *
     * <p>The <b>widget-less</b> resolution with the ambient frame above it, which is {@link Fonts#style}: an
     * embossed site builds its furnace from a static its whole client shares, once per rule change, so there is
     * no one widget to name — and it is the very chain the foundry inside that furnace resolved through, so a
     * caption's letters and the relief cut out of them cannot answer to two different rules.
     */
    static Fonts.Relief emboss(String scope) {
        return emboss(Fonts.style(scope));
    }

    /**
     * {@code Fonts.glow(scope, …)} — what this site's rule says about the halo behind its letters, or
     * {@code null} when it says nothing and the client blurs exactly the shadow it always blurred (065.15).
     *
     * <p>The same <b>widget-less</b> resolution {@link #emboss(String)} takes, and for the same reason: the two
     * decorators are built one inside the other at one place per site, off a static the whole client shares, so
     * they resolve through one chain and cannot disagree about which rule won.
     */
    static Fonts.Halo glow(String scope) {
        return glow(Fonts.style(scope));
    }

    /**
     * {@code Fonts.sequence(scope)} — the colour sequence this site's rule names, or {@code null} when it names
     * none and the site walks exactly the colours it always walked (065.16).
     *
     * <p>The same <b>widget-less</b> resolution the two above take. Neither site that asks has a widget to name:
     * a multi-chat mints a speaker's colour where the message arrives, and the urgency colour is read by the
     * indicator and by the channel tabs, which are three different widgets sharing one answer.
     */
    static Fonts.Sequence sequence(String scope) {
        return seq(Fonts.style(scope));
    }

    // ---- parsing -----------------------------------------------------------------------------------

    /** The four spellings a picture comes in, as every error here lists them. */
    private static final String ART =
        "{ color = {r,g,b[,a]} }, { image = hafen.asset():get(\"img/panel.png\") },"
        + " { asset = \"img/panel.png\" } or { res = \"gfx/hud/wnd/lg/bg\" }";

    /**
     * Parse one <b>surface</b> — {@code {color=}}, {@code {image=}}, {@code {asset=}} or {@code {res=}}, with an
     * optional {@code at}, {@code offset} and {@code mode}. <b>Exactly one</b> of the four: a table naming two
     * would have to pick a winner silently, and a table naming none is a typo the API can only answer with an
     * error (D-072 — an unknown <i>key</i> here has no future meaning to wait for, unlike an unresolved
     * selector).
     *
     * <p>This is the one parser behind every image slot in the vocabulary, which is what makes {@code {res=}}
     * name the game's own art wherever a picture may go, and one error message answer for all of them. A
     * <b>colour</b> refuses the three placement fields: it has no size of its own to place, so it fills its
     * whole surface and there is nothing for a spot or a mode to say.
     */
    static Art parseArt(Addon owner, String ctx, String what, LuaValue v) {
        if(!v.istable())
            throw new LuaError(ctx + what + ": expected a surface — " + ART + ", got " + v.typename());
        Color color = null;
        Src src = null;
        String named = null;
        int spot = -1;
        Coord offset = null;
        LuaValue mode = LuaValue.NIL;
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
                    throw new LuaError(ctx + what + ".color: expected a colour table with 0..255 components"
                        + " — { 26, 26, 28, 240 } or { r = 26, g = 26, b = 28, a = 240 }");
                named = twice(ctx, what, named, "color");
            } else if("image".equals(p) || "asset".equals(p) || "res".equals(p)) {
                src = source(owner, ctx, what, p, pv);
                named = twice(ctx, what, named, p);
            } else if("at".equals(p)) {
                spot = cornerOf(ctx, what + ".at", pv);
            } else if("offset".equals(p)) {
                offset = Layout.parseCoord(ctx + what, "offset", pv);
            } else if("mode".equals(p)) {
                mode = pv;
            } else {
                throw new LuaError(ctx + what + ": \"" + k.tojstring() + "\" is not a surface property"
                    + " — a surface is " + ART + ", each with an optional at, offset and mode");
            }
        }
        if((color == null) && (src == null))
            throw new LuaError(ctx + what + ": says nothing — a surface is " + ART
                + ", and a bg may be an ARRAY of them, painted in order");
        if(color != null) {
            if((spot >= 0) || (offset != null) || !mode.isnil())
                throw new LuaError(ctx + what + ": a colour fills its whole surface, so it takes no \"at\","
                    + " \"offset\" or \"mode\" — those say where a PICTURE goes and how it fills the room left");
            return new Art(color, null, -1, null, STRETCH);
        }
        return new Art(null, src, spot, offset, modeOf(ctx, what, mode, TILE));
    }

    /**
     * The picture behind {@code image}/{@code asset}/{@code res}. The first two are the same art by two names —
     * {@code {asset = "img/panel.png"}} loads through {@code hafen.asset}'s own interning, so a path and a
     * handle resolve to one object and one uploaded texture. The third is the client's own, already resampled
     * to this client's interface scale.
     */
    private static Src source(Addon owner, String ctx, String what, String prop, LuaValue v) {
        if("image".equals(prop)) {
            LuaImage li = LuaImage.resolve(v);
            if(li == null)
                throw new LuaError(ctx + what + ".image: expected an image asset handle"
                    + " — hafen.asset():get(\"img/panel.png\")");
            return Src.of(li);
        }
        String s = (v.isstring() && !v.isnumber()) ? v.tojstring() : null;
        if(s == null)
            throw new LuaError(ctx + what + "." + prop + ": expected a "
                + ("asset".equals(prop) ? "path string, relative to your addon's folder — \"img/panel.png\""
                                        : "resource name string — \"gfx/hud/wnd/lg/bg\"")
                + ", got " + v.typename());
        if("asset".equals(prop)) {
            LuaImage li = LuaImage.resolve(AssetApi.load(owner, s));
            if(li == null)
                throw new LuaError(ctx + what + ".asset: \"" + s + "\" is not an image — a surface's asset is a"
                    + " picture file (.png/.jpg/.gif/.bmp)");
            return Src.of(li);
        }
        return Src.of(s, loadres(ctx, what + ".res", s));
    }

    /** One of the client's own images, or the error that names the resource rather than an engine exception. */
    private static Tex loadres(String ctx, String what, String name) {
        Tex tx;
        try {
            tx = Resource.loadtex(name);
        } catch(Resource.NoSuchResourceException e) {
            throw new LuaError(ctx + what + ": no such resource \"" + name + "\" — a resource name is the"
                + " client's own, without a leading slash (e.g. \"gfx/hud/wnd/lg/bg\")");
        } catch(RuntimeException e) {
            throw new LuaError(ctx + what + ": \"" + name + "\" is not an image resource: " + e.getMessage());
        }
        if(tx == null)
            throw new LuaError(ctx + what + ": \"" + name + "\" carries no image");
        return tx;
    }

    /** Two spellings of the same thing in one value: the winner cannot be picked silently, so neither is. */
    private static String twice(String ctx, String what, String had, String now) {
        if(had != null)
            throw new LuaError(ctx + what + ": says \"" + had + "\" AND \"" + now + "\" — a surface is one of"
                + " " + ART + ", not several. Layer them instead: a bg may be an array, painted in order");
        return now;
    }

    /** The fields a surface carries — what a {@code bg} hands down to {@link #parseArt} rather than reading itself. */
    private static boolean surfaceField(String p) {
        return "color".equals(p) || "image".equals(p) || "asset".equals(p) || "res".equals(p)
            || "at".equals(p) || "offset".equals(p) || "mode".equals(p);
    }

    /**
     * Parse a rule's {@code bg} — one surface, or an <b>array</b> of them painted in order, either of them
     * carrying a face for a {@link #STATES state} (065.8). The array is what the client's own window background
     * is, and what a texture under a vignette needs; it is no new value, only the surface at a different arity,
     * so the two are told apart by the one thing that distinguishes them: a list's first entry is a table, a
     * surface's is nothing.
     *
     * <p>The state faces are read off <b>here</b> rather than inside the surface, because a state varies the
     * whole background — every layer of it — and because a surface is also what a {@code sizer} and a border's
     * {@code parts} are made of, where no state exists to vary. So this method takes them out of the value and
     * hands the rest down.
     */
    static Bg parseBg(Addon owner, String ctx, LuaValue v) {
        return parseBg(owner, ctx, ".bg", v, true);
    }

    private static Bg parseBg(Addon owner, String ctx, String what, LuaValue v, boolean states) {
        if(!v.istable())
            throw new LuaError(ctx + what + ": expected a surface — " + ART + " — or an array of them, got "
                + v.typename());
        Bg[] st = null;
        LuaTable rest = new LuaTable();
        LuaValue k = LuaValue.NIL;
        while(true) {
            Varargs n = v.next(k);
            k = n.arg1();
            if(k.isnil())
                break;
            String p = key(k);
            int s = stateOf(p);
            if(s >= 0) {
                if(!states)
                    throw new LuaError(ctx + what + "." + p + ": a state face is a plain surface, and carries no"
                        + " state of its own — it IS the value at that moment, so name " + stateList()
                        + " beside the face they vary rather than inside one");
                if(st == null)
                    st = new Bg[STATES.length];
                st[s] = parseBg(owner, ctx, what + "." + p, n.arg(2), false);
            } else if((p != null) && !surfaceField(p)) {
                throw new LuaError(ctx + what + ": \"" + p + "\" is neither a surface property nor a state — a"
                    + " surface is " + ART + ", each with an optional at, offset and mode, and a state face is"
                    + " one of " + stateList() + ", of the same shape as the value it varies");
            } else {
                rest.set(k, n.arg(2));
            }
        }
        if((st != null) && rest.next(LuaValue.NIL).arg1().isnil())
            throw new LuaError(ctx + what + ": names a state face and no face for it to vary — a state rides"
                + " INSIDE the value it varies, so name the surface it is a state OF beside it");
        return new Bg(layers(owner, ctx, what, rest), st);
    }

    /** One {@code bg}'s own layers: one surface, or the array of them, in paint order. */
    private static Art[] layers(Addon owner, String ctx, String what, LuaTable v) {
        if(!v.get(1).istable())
            return new Art[] {parseArt(owner, ctx, what, v)};
        List<Art> ls = new ArrayList<Art>();
        for(int i = 1; ; i++) {
            LuaValue e = v.get(i);
            if(e.isnil())
                break;
            ls.add(parseArt(owner, ctx, what + "[" + i + "]", e));
        }
        return ls.toArray(new Art[ls.size()]);
    }

    /**
     * Parse a rule's {@code picture} (065.12) — <b>one</b> surface, with a face for any of the {@link #STATES}
     * beside it. It is {@link #parseBg} at the arity that has no list in it, and the difference is the value's
     * own meaning rather than a restriction: layers are what a background is painted <i>in</i>, and a plate is
     * the whole picture, so a second one under it could never be seen. A theme that wants layers wants a
     * {@code bg}.
     */
    static Pic parsePicture(Addon owner, String ctx, String what, LuaValue v) {
        return parsePicture(owner, ctx, what, v, true);
    }

    private static Pic parsePicture(Addon owner, String ctx, String what, LuaValue v, boolean states) {
        if(!v.istable())
            throw new LuaError(ctx + what + ": expected a surface — " + ART + ", got " + v.typename());
        Pic[] st = null;
        LuaTable rest = new LuaTable();
        LuaValue k = LuaValue.NIL;
        while(true) {
            Varargs n = v.next(k);
            k = n.arg1();
            if(k.isnil())
                break;
            String p = key(k);
            int s = stateOf(p);
            if(s >= 0) {
                if(!states)
                    throw new LuaError(ctx + what + "." + p + ": a state face is a plain surface, and carries no"
                        + " state of its own — it IS the value at that moment, so name " + stateList()
                        + " beside the face they vary rather than inside one");
                if(st == null)
                    st = new Pic[STATES.length];
                st[s] = parsePicture(owner, ctx, what + "." + p, n.arg(2), false);
            } else if((p != null) && !surfaceField(p)) {
                throw new LuaError(ctx + what + ": \"" + p + "\" is neither a surface property nor a state — a"
                    + " picture is " + ART + ", with an optional at, offset and mode, and a state face is one of"
                    + " " + stateList() + ", of the same shape as the value it varies");
            } else if(p == null) {
                throw new LuaError(ctx + what + ": a picture is ONE surface, not an array of them — " + ART
                    + ". Layers are what a bg is painted in; a plate is the whole picture");
            } else {
                rest.set(k, n.arg(2));
            }
        }
        if((st != null) && rest.next(LuaValue.NIL).arg1().isnil())
            throw new LuaError(ctx + what + ": names a state face and no face for it to vary — a state rides"
                + " INSIDE the value it varies, so name the surface it is a state OF beside it");
        return new Pic(parseArt(owner, ctx, what, rest), st);
    }

    /** The two things an emboss may be — what every refusal here lists, rather than summarising. */
    private static final String EMBOSSES =
        "false — no relief, so a `color` rule reaches the glyphs — or { texture = " + ART + " }";

    /**
     * Parse a rule's {@code emboss} (065.14). Two shapes, and they are the two answers there are: {@code false}
     * drops the relief this client tiles through an embossed surface's letters, and a {@code texture} tiles the
     * theme's own instead.
     *
     * <p><b>{@code true} is refused</b>, and it is the one refusal here worth spelling out: it would mean "the
     * client's own relief", which is what a key carrying no {@code emboss} at all already draws, to the pixel.
     * A property whose only effect is to say what silence says is a property that will be read as doing
     * something, so it says what to write instead.
     *
     * <p>A texture is an ordinary picture, named the same {@link #parseArt four ways} as every other art in this
     * vocabulary — but it is <b>tiled through a mask</b> rather than painted into a box, so it takes neither a
     * flat colour (there are no pixels to tile) nor the three fields that place a picture in a rectangle (there
     * is no rectangle; the letters are the shape).
     */
    static Emboss parseEmboss(Addon owner, String ctx, LuaValue v) {
        if(v.isboolean()) {
            if(v.toboolean())
                throw new LuaError(ctx + ".emboss: an emboss is " + EMBOSSES + ". \"true\" is this client's own"
                    + " relief, which is what a rule naming no emboss at all already draws — leave the property"
                    + " out to keep it");
            return new Emboss(false, null);
        }
        if(!v.istable())
            throw new LuaError(ctx + ".emboss: expected " + EMBOSSES + ", got " + v.typename());
        LuaValue tex = LuaValue.NIL;
        LuaValue k = LuaValue.NIL;
        while(true) {
            Varargs n = v.next(k);
            k = n.arg1();
            if(k.isnil())
                break;
            String p = key(k);
            if("texture".equals(p))
                tex = n.arg(2);
            else
                throw new LuaError(ctx + ".emboss: \"" + k.tojstring() + "\" is not an emboss property — an"
                    + " emboss is " + EMBOSSES + ", and \"texture\" is the only field it carries");
        }
        if(tex.isnil())
            throw new LuaError(ctx + ".emboss: says nothing — an emboss is " + EMBOSSES);
        if(tex.istable()) {
            // The two fields a picture carries that a MASK has no room for, refused where they are written
            // rather than ignored: the letters are the shape, so there is no rectangle to pin art inside and
            // no leftover axis for a mode to fill.
            for(int i = 0; i < PLACERS.length; i++) {
                if(!tex.get(PLACERS[i]).isnil())
                    throw new LuaError(ctx + ".emboss.texture: takes no \"" + PLACERS[i] + "\" — an emboss"
                        + " texture is tiled through the SHAPE OF THE LETTERS rather than painted into a box,"
                        + " so there is nowhere to pin it and nothing left over to fill");
            }
        }
        Art a = parseArt(owner, ctx, ".emboss.texture", tex);
        if(a.color != null)
            throw new LuaError(ctx + ".emboss.texture: a colour has no pixels to tile through the letters — to"
                + " paint an embossed surface one flat colour, say emboss(false) and give the rule a color");
        return new Emboss(true, a);
    }

    /** The three fields that place a picture in a rectangle — the ones a glyph mask has no rectangle for. */
    private static final String[] PLACERS = {"at", "offset", "mode"};

    /** What a glow is, spelled out — what every refusal here carries, rather than naming one missing field. */
    private static final String GLOWS =
        "{ color = {r,g,b[,a]}, radius = n } — a colour and how far it reaches, in design pixels, 0 for no"
        + " halo at all";

    /**
     * Parse a rule's {@code glow} (065.15) — the halo an embossed surface's letters are blurred behind. One
     * shape, and <b>both</b> of its fields are required: a colour with no radius says nothing about how far it
     * reaches and a radius with no colour nothing about what is drawn, so neither half has a default worth
     * guessing at.
     *
     * <p><b>A radius of zero is legal and means no halo</b>, which is what makes the property able to say the
     * one thing the client's own look cannot: letters with nothing behind them. Leaving the property out is
     * the other answer, and it is the one that keeps the client's own blur to the pixel.
     */
    static Glow parseGlow(String ctx, LuaValue v) {
        if(!v.istable())
            throw new LuaError(ctx + ".glow: expected " + GLOWS + ", got " + v.typename());
        Color col = null;
        LuaValue rad = LuaValue.NIL;
        LuaValue k = LuaValue.NIL;
        while(true) {
            Varargs n = v.next(k);
            k = n.arg1();
            if(k.isnil())
                break;
            String p = key(k);
            LuaValue pv = n.arg(2);
            if("color".equals(p)) {
                col = pv.istable() ? AddonManager.luaColor(pv, null) : null;
                if(col == null)
                    throw new LuaError(ctx + ".glow.color: expected a colour table with 0..255 components"
                        + " — { 96, 96, 0 } or { r = 96, g = 96, b = 0, a = 255 }");
            } else if("radius".equals(p)) {
                rad = pv;
            } else {
                throw new LuaError(ctx + ".glow: \"" + k.tojstring() + "\" is not a glow property — a glow is "
                    + GLOWS);
            }
        }
        if(col == null)
            throw new LuaError(ctx + ".glow: names no colour — a glow is " + GLOWS);
        if(rad.isnil())
            throw new LuaError(ctx + ".glow: names no radius — a glow is " + GLOWS);
        // type() rather than isnumber(): in LuaJ a STRING that looks like a number answers isnumber() (the 028
        // asset lesson), and `radius = "4"` is a typo.
        if(rad.type() != LuaValue.TNUMBER)
            throw new LuaError(ctx + ".glow.radius: expected a number of design pixels, got " + rad.typename()
                + " — a glow is " + GLOWS);
        int r = rad.toint();
        if(r < 0)
            throw new LuaError(ctx + ".glow.radius: a radius is a distance and cannot be negative (got " + r
                + ") — a glow is " + GLOWS);
        return new Glow(col, r);
    }

    /**
     * Parse a rule's {@code close} (065.5) — a surface, its {@code hover} and {@code pressed} variants, and the
     * {@code at}/{@code offset} that pin the button to a corner of the frame.
     *
     * <p><b>Three groups of key, and the split is what the value means.</b> The art spellings say what the button
     * looks like; {@code hover}/{@code pressed} vary that face and are surfaces themselves; {@code at} and
     * {@code offset} place the <i>button</i>, which is why they are not the art's own — a face fills the box it
     * is given, and the box is what the spot moves.
     *
     * <p><b>Either half alone is a value</b>, so a theme may move the client's own button or re-face it where it
     * stands. Saying neither is the one thing refused: a rule that names a property and then says nothing with it
     * is a typo, and there is no default worth guessing at.
     */
    static Close parseClose(Addon owner, String ctx, String what, LuaValue v) {
        if(!v.istable())
            throw new LuaError(ctx + what + ": expected a close button — a surface (" + ART + "), with an"
                + " optional \"hover\" and \"pressed\" face and an \"at\"/\"offset\", got " + v.typename());
        LuaTable art = new LuaTable();
        boolean named = false;
        Art hover = null, pressed = null;
        int corner = -1;
        Coord offset = null;
        LuaValue k = LuaValue.NIL;
        while(true) {
            Varargs n = v.next(k);
            k = n.arg1();
            if(k.isnil())
                break;
            String p = key(k);
            LuaValue pv = n.arg(2);
            if("hover".equals(p)) {
                hover = parseArt(owner, ctx, what + ".hover", pv);
            } else if("pressed".equals(p)) {
                pressed = parseArt(owner, ctx, what + ".pressed", pv);
            } else if("at".equals(p)) {
                corner = cornerOf(ctx, what + ".at", pv);
            } else if("offset".equals(p)) {
                offset = Layout.parseCoord(ctx + what, "offset", pv);
            } else if("color".equals(p) || "image".equals(p) || "asset".equals(p) || "res".equals(p)
                      || "mode".equals(p)) {
                art.set(p, pv);
                named = true;
            } else {
                throw new LuaError(ctx + what + ": \"" + k.tojstring() + "\" is not a close property — a close"
                    + " button is a surface (" + ART + ") with an optional \"hover\" and \"pressed\" face of the"
                    + " same shape, plus the \"at\" and \"offset\" that pin it to a corner of the frame");
            }
        }
        Art up = named ? parseArt(owner, ctx, what, art) : null;
        if((up == null) && ((hover != null) || (pressed != null)))
            throw new LuaError(ctx + what + ": names a \"hover\"/\"pressed\" face and no face for it to vary — a"
                + " state face rides INSIDE the value it varies, so name the button's own art beside it");
        if((corner < 0) && (offset != null))
            throw new LuaError(ctx + what + ": names an \"offset\" and no \"at\" — an offset is counted FROM a"
                + " corner, one of " + cornerList());
        if((up == null) && (corner < 0))
            throw new LuaError(ctx + what + ": says nothing — a close button is a surface (" + ART + "), a spot"
                + " (\"at\", with an optional \"offset\"), or both: the art and the place are independent");
        return new Close(up, hover, pressed, (corner < 0) ? null : new Spot(corner, offset));
    }

    /** The three ways a frame is said, as every refusal that meets one of them lists them. */
    private static final String FRAME =
        "art cut into a 9-slice ({ image = hafen.asset():get(\"img/panel.png\"), slice = {l,t,r,b} } or"
        + " { res = \"gfx/…\", slice = … }), one of the client's own frames ({ box = \"gfx/hud/wnd\" }),"
        + " or a line ({ color = {r,g,b[,a]}, width = 2 })";

    /**
     * Parse a rule's {@code border} — a 9-slice of your own ({@code image}/{@code asset}/{@code res} plus
     * {@code slice}), one of the client's own boxes ({@code {box = "gfx/hud/wnd"}}), or a <b>line</b>
     * ({@code {color = …, width = n}}, 065.6). Exactly one of the three: they are three ways of saying what a
     * frame's four sides are made of, and a value naming two is asking one question twice.
     *
     * <p>On the 9-slice form both fields are required: an image with no slice cannot be cut into a frame, and
     * there is no default worth guessing at (033.3's lesson — a guess produces a table that lies). The slice is
     * validated against the art, so a border that could only ever draw inside out is refused where the rule is
     * written rather than silently at every frame. A <b>line</b> is the same discipline at its own arity: a
     * colour needs the thickness it is drawn at, and it refuses the two fields that only mean something to a
     * picture — a {@code slice} (there is no art to cut) and a {@code mode} (there is no edge art to repeat).
     */
    static Border parseBorder(Addon owner, String ctx, LuaValue v) {
        if(!v.istable())
            throw new LuaError(ctx + ".border: expected a frame — " + FRAME + ", got " + v.typename());
        Src src = null;
        String named = null, box = null;
        Color line = null;
        Art[] parts = null;
        LuaValue slice = null, mode = LuaValue.NIL, width = null;
        LuaValue k = LuaValue.NIL;
        while(true) {
            Varargs n = v.next(k);
            k = n.arg1();
            if(k.isnil())
                break;
            String p = key(k);
            LuaValue pv = n.arg(2);
            if("image".equals(p) || "asset".equals(p) || "res".equals(p)) {
                src = source(owner, ctx, ".border", p, pv);
                named = p;
            } else if("color".equals(p)) {
                line = pv.istable() ? AddonManager.luaColor(pv, null) : null;
                if(line == null)
                    throw new LuaError(ctx + ".border.color: expected a colour table with 0..255 components"
                        + " — { 244, 247, 21, 192 } or { r = 244, g = 247, b = 21, a = 192 }");
            } else if("width".equals(p)) {
                width = pv;
            } else if("parts".equals(p)) {
                parts = parseParts(owner, ctx, ".border.parts", pv);
            } else if("box".equals(p)) {
                box = (pv.isstring() && !pv.isnumber()) ? pv.tojstring() : null;
                if(box == null)
                    throw new LuaError(ctx + ".border.box: expected the resource FOLDER of one of the client's"
                        + " own frames — { box = \"gfx/hud/wnd\" }, got " + pv.typename());
            } else if("slice".equals(p)) {
                slice = pv;
            } else if("mode".equals(p)) {
                mode = pv;
            } else {
                throw new LuaError(ctx + ".border: \"" + k.tojstring() + "\" is not a border property — a border"
                    + " is " + FRAME + ", each with an optional parts list");
            }
        }
        if(line != null)
            return parseLine(ctx, line, width, slice, mode, src, named, box, parts);
        String m = modeOf(ctx, ".border", mode, STRETCH);
        if(box != null) {
            if(src != null)
                throw new LuaError(ctx + ".border: says \"" + named + "\" AND \"box\" — a border is your own"
                    + " 9-slice art OR one of the client's own frames, not both");
            if(slice != null)
                throw new LuaError(ctx + ".border: a box carries its own insets — the corners of \"" + box
                    + "\" are its slice, so drop the \"slice\"");
            return new Border(null, box, 0, 0, 0, 0, m, parts, loadBox(ctx, box));
        }
        if(src == null)
            throw new LuaError(ctx + ".border: says nothing about what the frame is made of — a border is "
                + FRAME);
        if(slice == null)
            throw new LuaError(ctx + ".border: needs a slice — the four insets {left, top, right, bottom},"
                + " in design pixels, that cut the art into corners and edges");
        int[] s = insets(ctx, ".border.slice", "an inset", slice);
        Coord sz = src.size();
        if((s[0] + s[2] >= sz.x) || (s[1] + s[3] >= sz.y))
            throw new LuaError(ctx + ".border.slice: {" + s[0] + "," + s[1] + "," + s[2] + "," + s[3]
                + "} leaves no middle in a " + sz.x + "x" + sz.y + " image — left+right must be under its width"
                + " and top+bottom under its height");
        return new Border(src, null, s[0], s[1], s[2], s[3], m, parts, null);
    }

    /**
     * The <b>line</b> half of {@link #parseBorder} (065.6): a colour and the thickness it is drawn at, and the
     * refusal for every field that belongs to a picture instead. Written apart because it is a whole value with
     * its own rules, not a branch — a line shares nothing with a 9-slice but the property it is written under.
     */
    private static Border parseLine(String ctx, Color line, LuaValue width, LuaValue slice, LuaValue mode,
                                    Src src, String named, String box, Art[] parts) {
        if(src != null)
            throw new LuaError(ctx + ".border: says \"color\" AND \"" + named + "\" — a border is a LINE (a"
                + " colour at a thickness) or ART cut into a frame, not both");
        if(box != null)
            throw new LuaError(ctx + ".border: says \"color\" AND \"box\" — a border is a LINE (a colour at a"
                + " thickness) or one of the client's own frames, not both");
        if(slice != null)
            throw new LuaError(ctx + ".border: a line has no slice — a slice cuts ART into corners and edges,"
                + " and a line is one colour at one thickness the whole way round");
        if(!mode.isnil())
            throw new LuaError(ctx + ".border: a line has no edge art to repeat, so it takes no \"mode\" — that"
                + " says what a 9-slice's four edges do between its corners");
        if(width == null)
            throw new LuaError(ctx + ".border: a line needs a \"width\" — how thick the frame is drawn, in"
                + " design pixels: { color = {r,g,b[,a]}, width = 2 }");
        // type() rather than isnumber(): in LuaJ a STRING that looks like a number answers isnumber() (the 028
        // asset lesson), and `width = "2"` is a typo.
        if(width.type() != LuaValue.TNUMBER)
            throw new LuaError(ctx + ".border.width: expected a number of design pixels, got " + width.typename());
        int w = width.toint();
        if(w < 1)
            throw new LuaError(ctx + ".border.width: a line's width is at least 1 design pixel (got " + w
                + ") — a frame nobody can see is said by leaving the property out");
        return new Border(line, w, parts);
    }

    /**
     * Parse a border's {@code parts} — the array of pieces pinned inside the frame (065.4). Each is an ordinary
     * surface, so a piece is named the four ways every picture is; what a part <b>must</b> carry beyond that is
     * its {@code at}, because being pinned is the whole of what makes it a part rather than another
     * {@code bg} layer.
     */
    private static Art[] parseParts(Addon owner, String ctx, String what, LuaValue v) {
        if(!v.istable())
            throw new LuaError(ctx + what + ": expected an ARRAY of pinned pieces — each a surface with an"
                + " \"at\", e.g. { { res = \"gfx/hud/wnd/lg/lb\", at = \"bottomleft\" } }, got " + v.typename());
        List<Art> ls = new ArrayList<Art>();
        for(int i = 1; ; i++) {
            LuaValue e = v.get(i);
            if(e.isnil())
                break;
            Art a = parseArt(owner, ctx, what + "[" + i + "]", e);
            if(a.spot < 0)
                throw new LuaError(ctx + what + "[" + i + "]: a part is PINNED, so it needs an \"at\" — one of"
                    + " " + cornerList() + ". A picture that covers the whole surface is a bg layer");
            ls.add(a);
        }
        if(ls.isEmpty())
            throw new LuaError(ctx + what + ": says nothing — a parts list is an array of pinned pieces; drop"
                + " the key rather than writing an empty one");
        return ls.toArray(new Art[ls.size()]);
    }

    /** The four corners of one of the client's own boxes — the one naming every such frame shares. */
    private static final String[] BOX_CORNERS = {"tl", "tr", "bl", "br"};
    /**
     * ...and its four edges, which the client spells two ways: {@code gfx/hud/bosq} and {@code gfx/hud/emote}
     * carry {@code el}/{@code er}/{@code et}/{@code eb}, {@code gfx/hud/wnd} carries the {@code ext} spelling.
     * Both are tried, in order, so a theme names the folder and nothing else.
     */
    private static final String[][] BOX_EDGES = {
        {"el", "er", "et", "eb"},
        {"extvl", "extvr", "extht", "exthb"},
    };

    /**
     * Load one of the client's own eight-part boxes, in {@link IBox.Images} order. The corners resolve first, so
     * a folder that is not a box at all fails naming <i>itself</i> rather than naming an edge nobody wrote.
     */
    private static Tex[] loadBox(String ctx, String base) {
        Tex[] p = new Tex[8];
        for(int i = 0; i < 4; i++)
            p[i] = loadres(ctx, ".border.box", base + "/" + BOX_CORNERS[i]);
        for(int s = 0; s < BOX_EDGES.length; s++) {
            try {
                for(int i = 0; i < 4; i++)
                    p[4 + i] = Resource.loadtex(base + "/" + BOX_EDGES[s][i]);
                return p;
            } catch(RuntimeException e) { /* the other spelling, then */ }
        }
        throw new LuaError(ctx + ".border.box: \"" + base + "\" has corners but no edges — the four edges of one"
            + " of the client's own frames are named \"el\"/\"er\"/\"et\"/\"eb\" or"
            + " \"extvl\"/\"extvr\"/\"extht\"/\"exthb\"");
    }

    /**
     * Parse a rule's {@code padding} — the room a surface keeps between its frame and its content, in
     * <b>design</b> px: one number for all four sides, or the four themselves.
     *
     * <p><b>Four sides rather than one number</b>, because that is what the client's own margins are: a window's
     * stock breathing room is {@code 23x14} on one side and the same on the other, and a theme whose caption
     * needs height at the top wants exactly that asymmetry. One property and one slot, so a read hands the four
     * back and the write takes them again.
     */
    static Pad parsePadding(String ctx, LuaValue v) {
        // type() rather than isnumber(): in LuaJ a STRING that looks like a number answers isnumber() (the 028
        // asset lesson, and why a sheet key is type-checked the same way). `padding = "6"` is a typo.
        if(v.type() == LuaValue.TNUMBER) {
            int p = v.toint();
            int[] s = {p, p, p, p};
            nonneg(ctx, ".padding", "padding", s);
            return new Pad(p, p, p, p);
        }
        if(!v.istable())
            throw new LuaError(ctx + ".padding: expected a number of pixels for all four sides — padding(6) — or"
                + " the four themselves — padding(8, 4, 8, 8), got " + v.typename());
        int[] s = insets(ctx, ".padding", "padding", v);
        return new Pad(s[0], s[1], s[2], s[3]);
    }

    /**
     * {@code rule:padding(…)} as the setter is written: one number, four numbers, or the {@code {l=,t=,r=,b=}}
     * table the read hands back. Two or three numbers is the refusal that names both shapes — a padding is all
     * four sides or one, and there is no third arity worth guessing at.
     */
    static Pad parsePadding(String ctx, Varargs a, int i) {
        LuaValue first = a.arg(i);
        if(first.istable())
            return parsePadding(ctx, first);
        int n = 0;
        LuaTable t = new LuaTable();
        for(int j = i; (j <= a.narg()) && (a.arg(j).type() == LuaValue.TNUMBER); j++)
            t.set(++n, a.arg(j));
        if(n == 4)
            return parsePadding(ctx, t);
        if(n > 1)
            throw new LuaError(ctx + ".padding: expected one number for all four sides — padding(6) — or four,"
                + " {left, top, right, bottom} — padding(8, 4, 8, 8), got " + n);
        return parsePadding(ctx, first);   // one number, or none: one message, said in one place
    }

    /**
     * The four insets as {@code {l,t,r,b}} or {@code {l=,t=,r=,b=}} — the same two spellings a colour takes, for
     * the same reason: the positional form is what a hand-written rule (and a {@code theme.json}) says, the keyed
     * form is what {@code widget:style()} hands back, so a read round-trips into a write unchanged.
     */
    private static int[] insets(String ctx, String what, String noun, LuaValue v) {
        if(!v.istable())
            throw new LuaError(ctx + what + ": expected {left, top, right, bottom}, got " + v.typename());
        LuaValue l = v.get("l"), t = v.get("t"), r = v.get("r"), b = v.get("b");
        if(!l.isnumber() || !t.isnumber() || !r.isnumber() || !b.isnumber()) {
            l = v.get(1); t = v.get(2); r = v.get(3); b = v.get(4);
            if(!l.isnumber() || !t.isnumber() || !r.isnumber() || !b.isnumber())
                throw new LuaError(ctx + what + ": expected four numbers — {left, top, right, bottom}"
                    + " or { l = 8, t = 4, r = 8, b = 8 }");
        }
        int[] s = {l.toint(), t.toint(), r.toint(), b.toint()};
        nonneg(ctx, what, noun, s);
        return s;
    }

    /** A side of a frame is a distance, so none of the four may be negative. */
    private static void nonneg(String ctx, String what, String noun, int[] s) {
        for(int i = 0; i < s.length; i++) {
            if(s[i] < 0)
                throw new LuaError(ctx + what + ": " + noun + " cannot be negative (got " + s[i] + ")"
                    + " — every one of the four is a distance");
        }
    }

    /** A table key as a property name, or {@code null} for anything that is not a plain string (numbers included). */
    private static String key(LuaValue k) {
        return (!k.isnumber() && k.isstring()) ? k.tojstring() : null;
    }
}
