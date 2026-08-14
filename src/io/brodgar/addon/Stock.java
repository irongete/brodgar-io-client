package io.brodgar.addon;

import haven.Coord;
import haven.Fonts;
import haven.IBox;
import haven.Tex;
import haven.TexI;
import haven.Text;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

import java.awt.Color;
import java.awt.Font;
import java.util.List;
import java.util.Map;

/**
 * The <b>stock catalogue</b> (065.17): what this client draws with when no rule says anything, read back as
 * the very data a rule is written in — {@code sheet:stock()}.
 *
 * <pre>
 *   local look = hafen.ui():sheet():stock()
 *   hafen.store():set("stock.json", hafen.json():encode(look))
 *   hafen.ui():sheet():load(look):install()      -- ...and the client looks exactly as it did
 * </pre>
 *
 * <p><b>Generated, never transcribed.</b> Every value here comes from the site that draws it: a routed
 * surface declares its own look beside the lookup it already performs ({@code Fonts.stock}), handing over
 * the client's <i>own</i> objects — a texture, one of its eight-part boxes, a colour, a foundry. This class
 * only <b>names</b> them, through the picture registry {@link AddonManager#pictureName} already fills, so no
 * resource name is typed anywhere and a catalogue cannot go stale against an upstream art change: it would
 * simply come back saying something else.
 *
 * <p><b>A key answers only what it can say WHOLE.</b> Several of this client's surfaces are made of things
 * the vocabulary has no word for — a fill inset a fixed margin inside its own caps, a chain of links spread
 * evenly down a bar, a corner whose width follows the caption inside it. A site declares a property only
 * where the declaration <i>is</i> what it draws, and this class drops any property whose art resolves to no
 * resource name rather than inventing one. So what comes back installs to the same client, and what is
 * missing is a hole in the grammar rather than a wrong answer.
 *
 * <p><b>A site that has not drawn answers nothing.</b> The catalogue is what the client has been offered, so
 * a window nobody has opened contributes no key until it is. That is why a reader opens what it means to
 * read.
 */
final class Stock {
    private Stock() {}

    /** The key {@code "default"} is written as, everywhere a sheet is written. */
    private static final String ALL = "*";

    /**
     * {@code sheet:stock()} — the whole look, keyed by site, in the shape {@code sheet:load} takes. A key
     * whose site has drawn nothing sayable is absent rather than empty.
     */
    static LuaValue catalogue() {
        LuaTable out = new LuaTable();
        List<String> scopes = Fonts.stocked();
        for(int i = 0; i < scopes.size(); i++) {
            String s = scopes.get(i);
            LuaValue r = ruleOf(s);
            if(!r.isnil())
                out.set("default".equals(s) ? ALL : s, r);
        }
        return out;
    }

    /**
     * {@code sheet:stock(key)} — one site's own look, or {@code nil} where that site has declared nothing
     * this vocabulary can carry. The key is a <b>site</b> key: a tree key names widgets rather than a
     * surface the client draws a kind of thing at, and no widget has a look of its own to read back.
     */
    static LuaValue one(String key) {
        Selector sel = Selector.parse(key);              // a malformed key errors exactly as every other does
        String site = Sheet.siteOf(sel);
        if(site == null)
            throw new LuaError("sheet:stock(key): \"" + key + "\" is not a render site — a stock look belongs"
                + " to a SITE (\"window.frame\", \"chat\", \"*\"), and a tree key names widgets, which draw"
                + " whatever the sites inside them draw. sheet:stock() lists every site this client has"
                + " offered one for");
        return ruleOf(site);
    }

    /** One site's declared look as a rule table, or {@code nil} where nothing of it can be said. */
    private static LuaValue ruleOf(String scope) {
        Map<String, Object[]> st = Fonts.stockOf(scope);
        if(st == null)
            return LuaValue.NIL;
        LuaTable t = new LuaTable();
        boolean any = false;
        for(Map.Entry<String, Object[]> e : st.entrySet()) {
            LuaValue v = value(scope, e.getKey(), e.getValue());
            if(v == null)
                continue;                                // no name for its art: left out, never guessed at
            t.set(e.getKey(), v);
            any = true;
        }
        return any ? t : LuaValue.NIL;
    }

    /** One declared property as a rule writes it, or {@code null} where it cannot be written at all. */
    private static LuaValue value(String scope, String prop, Object[] v) {
        if((v == null) || (v.length < 1))
            return null;
        if("font".equals(prop))
            return face(v[0]);
        if("color".equals(prop))
            return Chrome.seqKey(scope) ? sequence(v) : color(v[0]);
        if("emboss".equals(prop))
            return emboss(v[0]);
        if("glow".equals(prop))
            return glow(v[0]);
        if("padding".equals(prop))
            return padding(v);
        if("border".equals(prop))
            return border(v[0]);
        if("caption".equals(prop))
            return spot(v[0]);
        if("close".equals(prop))
            return close(v);
        if("bg".equals(prop))
            return layers(v);
        if("picture".equals(prop) || "sizer".equals(prop))
            return surface(v[0]);
        return null;
    }

    // ---- the values -------------------------------------------------------------------------------

    /**
     * A stock foundry as a <b>face</b>: the built-in it derives from, the size it is set at in design px, and
     * the two flags a rule carries beside them. A foundry built on a face that is none of the client's four
     * built-ins cannot be named, and answers nothing.
     */
    private static LuaValue face(Object o) {
        if(!(o instanceof Text.Foundry))
            return null;
        Text.Foundry f = (Text.Foundry)o;
        String bi = builtin(f.font);
        if(bi == null)
            return null;
        LuaTable t = new LuaTable();
        t.set("builtin", LuaValue.valueOf(bi));
        t.set("size", LuaValue.valueOf(Px.out(Math.round(f.font.getSize2D()))));
        if(f.font.isBold())
            t.set("bold", LuaValue.TRUE);
        if(f.font.isItalic())
            t.set("italic", LuaValue.TRUE);
        t.set("aa", LuaValue.valueOf(f.aa));
        return t;
    }

    /**
     * Which of the client's four built-in faces {@code f} was derived from, or {@code null} for a face this
     * client did not name. Matched on the font's own <b>name</b> rather than its family: two of the four ask
     * for a face this platform may not have and fall back to the same family, while the name a font was
     * asked for survives every {@code deriveFont} the client puts it through.
     */
    private static String builtin(Font f) {
        if(f == null)
            return null;
        String nm = f.getName();
        String[] names = {"sans", "serif", "mono", "fraktur"};
        Font[] fonts = {Text.sans, Text.serif, Text.mono, Text.fraktur};
        for(int i = 0; i < names.length; i++) {
            if((fonts[i] != null) && fonts[i].getName().equals(nm))
                return names[i];
        }
        return null;
    }

    /** A colour, as the positional array a file carries and every setter takes. */
    private static LuaValue color(Object o) {
        if(!(o instanceof Color))
            return null;
        Color c = (Color)o;
        LuaTable t = new LuaTable();
        t.set(1, LuaValue.valueOf(c.getRed()));
        t.set(2, LuaValue.valueOf(c.getGreen()));
        t.set(3, LuaValue.valueOf(c.getBlue()));
        t.set(4, LuaValue.valueOf(c.getAlpha()));
        return t;
    }

    /**
     * The two shapes a colour the client <b>walks</b> comes in: three numbers are the walk it does, and a
     * list of colours is the list it cycles.
     */
    private static LuaValue sequence(Object[] v) {
        LuaTable t = new LuaTable();
        if(v[0] instanceof Double) {
            if(v.length < 3)
                return null;
            LuaTable g = new LuaTable();
            String[] f = {"step", "saturation", "brightness"};
            for(int i = 0; i < f.length; i++) {
                if(!(v[i] instanceof Double))
                    return null;
                g.set(f[i], LuaValue.valueOf(((Double)v[i]).doubleValue()));
            }
            t.set("generate", g);
            return t;
        }
        LuaTable p = new LuaTable();
        for(int i = 0; i < v.length; i++) {
            LuaValue c = color(v[i]);
            if(c == null)
                return null;
            p.set(i + 1, c);
        }
        t.set("palette", p);
        return t;
    }

    /** The relief a surface's letters are cut out of, as the texture it is. */
    private static LuaValue emboss(Object o) {
        LuaValue art = named(o);
        if(art == null)
            return null;
        LuaTable t = new LuaTable();
        t.set("texture", art);
        return t;
    }

    /** The halo behind them: the piece's colour, at the piece's own reach in design px. */
    private static LuaValue glow(Object o) {
        if(!(o instanceof Fonts.Piece))
            return null;
        Fonts.Piece p = (Fonts.Piece)o;
        LuaValue c = color(p.art);
        if(c == null)
            return null;
        LuaTable t = new LuaTable();
        t.set("color", c);
        t.set("radius", LuaValue.valueOf(Px.out(p.width)));
        return t;
    }

    /** The room a surface keeps, as the four sides it is written with — the pair {@code {tl, br}} it is declared as. */
    private static LuaValue padding(Object[] v) {
        if((v.length < 2) || !(v[0] instanceof Coord) || !(v[1] instanceof Coord))
            return null;
        Coord tl = Px.out((Coord)v[0]), br = Px.out((Coord)v[1]);
        LuaTable t = new LuaTable();
        t.set(1, LuaValue.valueOf(tl.x));
        t.set(2, LuaValue.valueOf(tl.y));
        t.set(3, LuaValue.valueOf(br.x));
        t.set(4, LuaValue.valueOf(br.y));
        return t;
    }

    /**
     * A frame: one of the client's own eight-part boxes named by its folder, or a line said as a colour and a
     * thickness. A box whose eight pieces are not one folder spelled the way this vocabulary spells one
     * answers nothing — naming the folder would then load eight different pictures.
     */
    private static LuaValue border(Object o) {
        if(!(o instanceof Fonts.Piece))
            return null;
        Fonts.Piece p = (Fonts.Piece)o;
        if(p.art instanceof Color) {
            LuaValue c = color(p.art);
            LuaTable t = new LuaTable();
            t.set("color", c);
            t.set("width", LuaValue.valueOf(Math.max(1, Px.out(p.width))));
            return t;
        }
        if(!(p.art instanceof IBox.Images))
            return null;
        String box = boxOf((IBox.Images)p.art);
        if(box == null)
            return null;
        LuaTable t = new LuaTable();
        t.set("box", LuaValue.valueOf(box));
        t.set("mode", LuaValue.valueOf(p.tile ? Chrome.TILE : Chrome.STRETCH));
        return t;
    }

    /**
     * The resource folder one of the client's own boxes is spelled by, or {@code null}. Every one of the
     * eight has to name a picture in the same folder, and the eight leaf names have to be one of the
     * spellings {@link Chrome} loads a box back by — otherwise the folder would resolve to a different box.
     */
    private static String boxOf(IBox.Images b) {
        Tex[] px = {b.ctl, b.ctr, b.cbl, b.cbr, b.bl, b.br, b.bt, b.bb};
        String[] nm = new String[8];
        String base = null;
        for(int i = 0; i < px.length; i++) {
            nm[i] = resname(px[i]);
            if(nm[i] == null)
                return null;
            int c = nm[i].lastIndexOf('/');
            if(c < 0)
                return null;
            String f = nm[i].substring(0, c);
            if(base == null)
                base = f;
            else if(!base.equals(f))
                return null;
            nm[i] = nm[i].substring(c + 1);
        }
        for(int i = 0; i < Chrome.BOX_CORNERS.length; i++) {
            if(!Chrome.BOX_CORNERS[i].equals(nm[i]))
                return null;
        }
        for(int s = 0; s < Chrome.BOX_EDGES.length; s++) {
            boolean ok = true;
            for(int i = 0; i < 4; i++)
                ok &= Chrome.BOX_EDGES[s][i].equals(nm[4 + i]);
            if(ok)
                return base;
        }
        return null;
    }

    /** A place and nothing else — the corner an ornament is measured from, and how far. */
    private static LuaValue spot(Object o) {
        if(!(o instanceof Fonts.Piece))
            return null;
        Fonts.Piece p = (Fonts.Piece)o;
        if(p.at == null)
            return null;
        LuaTable t = new LuaTable();
        t.set("at", LuaValue.valueOf(p.at));
        if(p.offset != null)
            t.set("offset", xy(Px.out(p.offset)));
        return t;
    }

    /** A close button: its face, the two faces that vary it, and the corner the button itself is pinned to. */
    private static LuaValue close(Object[] v) {
        LuaValue up = surface(v[0]);
        if(up == null)
            return null;
        LuaTable t = (LuaTable)up;
        t.set("at", LuaValue.NIL);
        t.set("offset", LuaValue.NIL);                   // the spot places the BUTTON, not the art inside it
        String[] names = {"hover", "pressed"};
        for(int i = 0; (i < names.length) && ((i + 1) < v.length); i++) {
            LuaValue f = surface(v[i + 1]);
            if(f == null)
                return null;
            t.set(names[i], f);
        }
        Fonts.Piece p = (v[0] instanceof Fonts.Piece) ? (Fonts.Piece)v[0] : null;
        if((p != null) && (p.at != null)) {
            t.set("at", LuaValue.valueOf(p.at));
            if(p.offset != null)
                t.set("offset", xy(Px.out(p.offset)));
        }
        return t;
    }

    /** A background: one surface, or the array of them it is painted in. */
    private static LuaValue layers(Object[] v) {
        if(v.length == 1)
            return surface(v[0]);
        LuaTable t = new LuaTable();
        for(int i = 0; i < v.length; i++) {
            LuaValue l = surface(v[i]);
            if(l == null)
                return null;                             // one layer nobody can name makes the stack a lie
            t.set(i + 1, l);
        }
        return t;
    }

    /** One surface: its art, where it is pinned, how far from there, and what it does with the room left. */
    private static LuaValue surface(Object o) {
        if(!(o instanceof Fonts.Piece))
            return null;
        Fonts.Piece p = (Fonts.Piece)o;
        if(p.art instanceof Color)
            return color1(color(p.art));                 // a colour fills its surface: no spot, no mode
        LuaValue art = named(p.art);
        if(art == null)
            return null;
        LuaTable t = (LuaTable)art;
        if(p.at != null) {
            t.set("at", LuaValue.valueOf(p.at));
            if(p.offset != null)
                t.set("offset", xy(Px.out(p.offset)));
        }
        t.set("mode", LuaValue.valueOf(p.tile ? Chrome.TILE : Chrome.STRETCH));
        return t;
    }

    /** {@code {color = {r,g,b,a}}} — the surface a flat colour is. */
    private static LuaValue color1(LuaValue c) {
        if(c == null)
            return null;
        LuaTable t = new LuaTable();
        t.set("color", c);
        return t;
    }

    /** {@code {res = "gfx/…"}} for a picture the client decoded, or {@code null} for one it composed. */
    private static LuaValue named(Object art) {
        String n = resname(art);
        if(n == null)
            return null;
        LuaTable t = new LuaTable();
        t.set("res", LuaValue.valueOf(n));
        return t;
    }

    /**
     * The resource a picture was decoded from, or {@code null}. A texture the client wrapped around a raster
     * of its own is asked about that raster too: the registry is filled where a picture is <b>minted</b>, so
     * a second view of one the client already decoded is named by the pixels behind it rather than by itself.
     */
    private static String resname(Object art) {
        if(art == null)
            return null;
        String n = AddonManager.pictureName(art);
        if((n == null) && (art instanceof TexI))
            n = AddonManager.pictureName(((TexI)art).back);
        return n;
    }

    /** {@code {x, y}} — the positional pair every place in this API is written as. */
    private static LuaValue xy(Coord c) {
        LuaTable t = new LuaTable();
        t.set(1, LuaValue.valueOf(c.x));
        t.set(2, LuaValue.valueOf(c.y));
        return t;
    }
}
