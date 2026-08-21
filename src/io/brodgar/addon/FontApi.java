package io.brodgar.addon;

import haven.Fonts;
import haven.Text;

import java.util.List;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.ZeroArgFunction;

import java.awt.Color;
import java.awt.Font;

import static io.brodgar.addon.AddonManager.*;

/**
 * The per-addon font subsystem (spec {@code specs/addons/21-fonts.md}, F-series / D-043): {@code hafen.font.*}
 * gives an addon <b>complete control over the client's typography</b> without a shared registry. It has three
 * parts, all built here:
 * <ul>
 *   <li><b>{@code hafen.font(name)} &rarr; a private {@link FontHandle} for a BUILT-IN font</b>
 *       ({@code "sans"/"serif"/"mono"/"fraktur"}) — engine-owned, so addressed by name and interned per addon,
 *       with no lifetime and none of the asset verbs (D-060). The addon's <b>own</b> {@code .ttf}/{@code .otf} is
 *       loaded by {@link AssetApi} instead ({@code hafen.asset("fonts/Inter.ttf")}, which also AWT-registers its
 *       family for {@code $font[…]}); {@code hafen.font.load} is a hard cut (028.1, D-013). Either way the handle
 *       is an opaque per-addon Lua value with {@code :derive}/{@code :family}/{@code :size}, and the size/style
 *       variant comes from {@code :derive{…}} — the load takes a name or a path and nothing else.</li>
 *   <li><b>Global surfaces are the STYLESHEET's</b>, not this file's: {@code hafen.font.setFont(scope, h)} /
 *       {@code reset(scope)} / {@code scopes()} are a <b>hard cut</b> (033.1) — a font became one property of a
 *       rule, and {@code hafen.ui():sheet():rule("window.title"):font(h)} ({@link Sheet}) is the single place
 *       that says what a client surface looks like. That rule takes the handle, or the same face <b>named</b>
 *       ({@link #face}, 065.3) — {@code {builtin = "mono", size = 11}} / {@code {asset = "fonts/x.ttf"}} —
 *       which is what lets a whole theme be a file with no handle in it. The {@link Fonts} provider and its
 *       owner-tagged stack are unchanged: only <i>who fills them</i> moved. Their teardown still runs from here
 *       ({@link #teardownFonts}) — the owned-resource model (spec 05).</li>
 *   <li><b>Own-widget application</b> (F2, shipped): the {@code :font(h)} setter on {@code hafen.ui():window()}/{@code
 *       widget} ({@link AddonWidget}) and a per-call {@code {font,color}} on the {@code g:text}/{@code g:atext} draw
 *       wrapper ({@link LuaGOut}) + a custom TTF in the {@code $font[…]} rich-text tag (family AWT-registered
 *       when the asset is loaded). Isolated — touches only the addon's own pixels; no global state, nothing to revert.</li>
 *   <li><b>Per-widget styles are the SHEET's too</b> (F5, widened by 034.3): {@code widget:setFont(h)} /
 *       {@code widget:resetFont()} are a <b>hard cut</b> — {@code widget:rule():font(h):color(…)}
 *       ({@link Sheet#applySkin}) restyles <b>one</b> native widget and its subtree, and a font is one of its
 *       properties rather than the whole verb. It is the top of the resolution chain, owner-tagged, and reverted on
 *       teardown here ({@link #teardownFonts}) like every other level.</li>
 * </ul>
 * The Lua handle is facade-safe (no AWT {@code Font} crosses into Lua, D-017): a widget/scope stores the handle,
 * the bridge {@link FontHandle#resolve}s it back. Not instantiable.
 */
final class FontApi {
    private FontApi() {}

    /** The built-in font names {@code hafen.font(name)} answers to, as the error text lists them. */
    private static final String BUILTINS = "\"sans\", \"serif\", \"mono\" or \"fraktur\"";

    /**
     * Build {@code hafen.font} for {@code owner}: <b>the section object IS the collection</b> of the client's
     * built-in fonts this addon has named (spec §2.1). {@code hafen.font():get(name)} is one of
     * {@code "sans"/"serif"/"mono"/"fraktur"}, interned per addon; {@code :list(filter)} is the ones it has asked
     * for so far. There is no {@code :add} — the built-ins are the engine's and an addon does not make one — and
     * no {@code :remove}: a built-in has no lifetime to end (D-060). The addon's own {@code .ttf}/{@code .otf} is
     * a file it ships, so it comes through {@code hafen.asset():get(path)}.
     */
    static void installFont(LuaTable hafen, final Addon owner) {
        Section.mount(hafen, "font", collection(owner), "hafen.font(name) is now hafen.font():get(name)");
    }

    /** {@code hafen.font()} — the built-in fonts this addon has named, keyed by that name. */
    private static LuaValue collection(final Addon owner) {
        return LuaCollection.create("hafen.font()", new LuaCollection.Source() {
            public List<LuaValue> members() {
                return owner.assets.builtinFonts();
            }

            public String needle(LuaValue member) {
                return owner.assets.builtinFontName(member);
            }

            /** These have a name, so a string filter is a substring test over {@link #needle}. */
            public boolean named() {
                return true;
            }

            public boolean addressable() {
                return true;
            }

            public LuaValue getMember(LuaValue key) {
                return builtin(owner, key);
            }

            /** The built-in faces are a closed set, so a name outside it is a typo. */
            public LuaCollection.Missing missing() {
                return LuaCollection.Missing.RAISE;
            }

            /** The key is a built-in font NAME; a font file this addon ships is an asset. */
            public String keyName() {
                return "name";
            }
        }, null);
    }

    // ------------------------------------------------------------------ built-ins + handle

    /**
     * {@code hafen.font():get(name)}: the interned {@link FontHandle} for one of the client's built-in fonts
     * ({@code Text.sans}/{@code serif}/{@code mono}/{@code fraktur}). Engine-owned, so — exactly like
     * {@code hafen.sound():get(name)} — it is keyed by <b>name</b>, has no lifetime, and gets none of the
     * asset verbs ([D-060]). Interned per addon, so {@code hafen.font():get("mono")} is always the same
     * handle. A path, a typo or a missing argument all raise an error naming this call and {@code hafen.asset}.
     */
    private static LuaValue builtin(Addon owner, LuaValue namev) {
        if(namev.isnumber())        // BEFORE isstring(): in LuaJ a number IS a string
            throw new LuaError("hafen.font():get(name): the key is a built-in font NAME (" + BUILTINS + "),"
                + " not a number");
        if(!namev.isstring())
            throw new LuaError("hafen.font():get(name): expected a built-in font name (" + BUILTINS + "), got "
                + namev.typename() + " — the addon's own .ttf/.otf is hafen.asset():get(\"fonts/Inter.ttf\")");
        String name = namev.tojstring();
        LuaValue h = owner.assets.builtinFont(name);
        if(h != null)
            return h;
        Font base = builtinFont(name);
        if(base == null)
            throw new LuaError("hafen.font():get(\"" + name + "\"): not a built-in font — the built-ins are "
                + BUILTINS + "; a font FILE this addon ships is an asset: hafen.asset():get(\"fonts/Inter.ttf\")");
        h = fontHandle(new FontHandle(base, null, null, null));
        owner.assets.putBuiltinFont(name, h);
        return h;
    }

    /**
     * The AWT font behind a built-in name, or {@code null} for anything else — the one place the four are
     * listed, and therefore the membership test both doors that take a name are refused by.
     */
    private static Font builtinFont(String name) {
        if("sans".equals(name))    return Text.sans;
        if("serif".equals(name))   return Text.serif;
        if("mono".equals(name))    return Text.mono;
        if("fraktur".equals(name)) return Text.fraktur;
        return null;
    }

    // ------------------------------------------------------------------ the NAMED face (065.3)

    /** The two ways a face is named rather than handed over, as every error below lists them. */
    private static final String FACE = "{ builtin = \"mono\" } or { asset = \"fonts/Inter.ttf\" },"
        + " either with size, bold, italic and aa beside the name";

    /**
     * A <b>face</b>, as a stylesheet rule's {@code font} property takes one (065.3): the {@link FontHandle}
     * itself — {@code hafen.font():get(name)}, {@code hafen.asset():get(path)}, either through a
     * {@code :derive()} variant — or <b>the same face named</b>, {@code {builtin = …}} / {@code {asset = …}}
     * with the variant's own properties beside it.
     *
     * <p><b>Naming it is what makes a whole look a file.</b> A handle is the one value JSON cannot carry, so
     * the two spellings here are what a {@code theme.json} says a face with, and they resolve to exactly the
     * object the loader interns: {@code {builtin = "serif"}} <i>is</i> {@code hafen.font():get("serif")}, one
     * face and one parse per file however many rules name it. A name plus a variant derives, once, and the
     * result is sealed — the rule read it the instant it was parsed, so a setter on the handle it hands back
     * would take and change nothing.
     *
     * <p><b>{@code color} is refused here</b>, and that is not an omission: a handle's own colour never
     * styled a client surface (D-073), so a face that could name one would be a second, invisible answer to
     * "what colour is this surface". The rule's own {@code color} property is the answer, where it can be read.
     */
    static FontHandle face(Addon owner, String what, LuaValue v) {
        FontHandle h = FontHandle.resolve(v);
        if(h != null) {                     // handed over: a built-in, a file, or a :derive()d variant of one
            // ...and if it carries a colour, it is refused for the same reason a NAMED face carrying one is
            // (084.5). It used to be accepted and the colour dropped on the floor: h:color() went on reading
            // back the value that was set, the surface was drawn in the client's own colour, and nothing said
            // which of the two was the answer. The colour of a client surface is the rule's own property.
            if(h.color != null)
                throw new LuaError(what + ": this font carries a colour, and a font's colour never styles a"
                    + " client surface — it is for your OWN drawing (g:text and widget:font). The colour of a"
                    + " surface is the rule's own property, said where it can be read: rule:color(c)."
                    + " Hand this rule a face that carries none — derive one and leave :color off — and say"
                    + " the colour beside the font");
            return h;
        }
        if(!v.istable())
            throw new LuaError(what + ": expected a face — a handle from hafen.font():get(\"serif\") or"
                + " hafen.asset():get(\"fonts/Inter.ttf\"), or the same face NAMED: " + FACE
                + ", got " + v.typename());
        String builtin = null, asset = null, named = null;
        Integer size = null;
        Boolean aa = null, bold = null, italic = null;
        LuaValue k = LuaValue.NIL;
        while(true) {
            Varargs n = v.next(k);
            k = n.arg1();
            if(k.isnil())
                break;
            // BEFORE isstring(): in LuaJ a number IS a string, so a numeric key would read as a property name
            String p = (!k.isnumber() && k.isstring()) ? k.tojstring() : null;
            LuaValue pv = n.arg(2);
            if("builtin".equals(p) || "asset".equals(p)) {
                String s = (pv.isstring() && !pv.isnumber()) ? pv.tojstring() : null;
                if(s == null)
                    throw new LuaError(what + "." + p + ": expected "
                        + ("builtin".equals(p) ? "a built-in font name (" + BUILTINS + ")"
                                               : "the path of a font file this addon ships"
                                                 + " — \"fonts/Inter.ttf\"")
                        + ", got " + pv.typename());
                if(named != null)
                    throw new LuaError(what + ": says \"" + named + "\" AND \"" + p + "\" — a face is one of"
                        + " the client's own OR a file this addon ships, not both");
                named = p;
                if("builtin".equals(p))
                    builtin = s;
                else
                    asset = s;
            } else if("size".equals(p)) {
                size = optSize(pv, what + ".size");
            } else if("aa".equals(p) || "bold".equals(p) || "italic".equals(p)) {
                if(!pv.isboolean())
                    throw new LuaError(what + "." + p + ": expected true or false, got " + pv.typename());
                Boolean f = Boolean.valueOf(pv.toboolean());
                if("aa".equals(p))
                    aa = f;
                else if("bold".equals(p))
                    bold = f;
                else
                    italic = f;
            } else if("color".equals(p)) {
                throw new LuaError(what + ": a face carries no colour on a client surface — the colour of a"
                    + " surface is the rule's own property, said where it can be read: rule:color(c)");
            } else {
                throw new LuaError(what + ": \"" + k.tojstring() + "\" is not a face property — a face is "
                    + FACE);
            }
        }
        if(named == null)
            throw new LuaError(what + ": says nothing — a face is a font handle, or " + FACE);
        FontHandle base = (builtin != null) ? builtinFace(owner, what, builtin)
                                            : assetFace(owner, what, asset);
        if((size == null) && (aa == null) && (bold == null) && (italic == null))
            return base;                    // no variant: the very handle the loader interns for that face
        FontHandle d = base.draft();
        if(size != null)
            d.size = size;
        if(aa != null)
            d.aa = aa;
        if(bold != null)
            d.style(true, bold.booleanValue());
        if(italic != null)
            d.style(false, italic.booleanValue());
        d.seal();                           // the rule reads it HERE, as resolve() seals one handed over
        return d;
    }

    /** {@code {builtin = "mono"}} — one of the client's own faces, interned exactly as {@code :get} interns it. */
    private static FontHandle builtinFace(Addon owner, String what, String name) {
        if(builtinFont(name) == null)
            throw new LuaError(what + ".builtin: \"" + name + "\" is not a built-in font — the built-ins are "
                + BUILTINS + "; a font FILE this addon ships is named { asset = \"fonts/Inter.ttf\" }");
        return FontHandle.resolve(builtin(owner, LuaValue.valueOf(name)));
    }

    /** {@code {asset = "fonts/Inter.ttf"}} — a file this addon ships, through {@link AssetApi}'s own door. */
    private static FontHandle assetFace(Addon owner, String what, String path) {
        FontHandle h = FontHandle.resolve(AssetApi.load(owner, path));
        if(h == null)
            throw new LuaError(what + ".asset: \"" + path + "\" is not a font — a face's asset is a font file"
                + " this addon ships (.ttf/.otf)");
        return h;
    }

    /**
     * The Lua handle for a {@link FontHandle}: the opaque backing userdata ({@link FontHandle#KEY}) plus
     * {@code :derive(opts)} / {@code :family()} / {@code :size()}. Facade-safe (no AWT {@code Font} reaches Lua).
     * A font <b>asset</b> ({@link AssetApi}) is this table plus the shared {@code :type()}/{@code :path()}/
     * {@code :dispose()}; a built-in or a {@code :derive}d variant is this table alone — it was never loaded from
     * a file, so it has no path and no lifetime.
     */
    static LuaTable fontHandle(final FontHandle fh) {
        LuaTable h = mint(fh);
        fh.handle = h;                                    // the table its OWN addon holds (read by handleFor)
        return h;
    }

    /**
     * The resolved {@code font} of a stylesheet rule, as {@code reader} may hold it ({@code widget:style()},
     * 034.1). Its own rule hands back the very table it wrote — so {@code w:style().font == body} — while a rule
     * from <b>another addon's</b> sheet is minted into {@code reader}'s own interned view: the two addons then
     * share the {@link FontHandle} (an immutable, facade-safe Java value) and not a {@link LuaValue}, which is the
     * sandbox rule every intern cache in this bridge follows (D-017).
     */
    static LuaValue handleFor(Addon reader, FontHandle fh, Addon origin) {
        if((reader == origin) && (fh.handle != null))
            return fh.handle;
        LuaValue v = reader.assets.fontView(fh);
        if(v == null)
            reader.assets.putFontView(fh, v = mint(fh));
        return v;
    }

    /** One handle table over {@code fh} — {@link #fontHandle} plus every per-addon view of the same font. */
    private static LuaTable mint(final FontHandle fh) {
        LuaTable h = new LuaTable();
        h.set(FontHandle.KEY, LuaValue.userdataOf(fh));   // opaque backing ref for a rule's font / a widget's / g:text
        // derive() -- a DRAFT variant of this font, configured by the setters below. It takes no ARGUMENT: the
        // options table is gone, and { size = 12 } would be the last config table left in this section.
        h.set("derive", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                if(Args.passed(a, 2))
                    throw new LuaError("font:derive() takes no arguments — the variant is chained setters on"
                        + " what it hands back: h:derive():size(12):bold(true):color(255, 200, 200), and a read"
                        + " of each is the same name with none: d:size(), d:bold(), d:color()");
                return fontHandle(fh.draft());
            }
        });
        h.set("family", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                if(Args.passed(a, 2))
                    throw new LuaError("font:family() reads the AWT family name and does not write it — another"
                        + " family is another font: hafen.font():get(name), or hafen.asset():get(path) for one"
                        + " this addon ships");
                return LuaValue.valueOf(fh.family());
            }
        });
        h.set("size", property(fh, "size"));
        h.set("color", property(fh, "color"));
        h.set("aa", property(fh, "aa"));
        h.set("bold", property(fh, "bold"));
        h.set("italic", property(fh, "italic"));
        return h;
    }

    /**
     * One of the five properties a {@code :derive()}d handle carries, as the read/write pair every property in
     * this API is: {@code d:size()} reads and {@code d:size(12)} writes and hands the handle back, so the whole
     * variant is one chain.
     *
     * <p><b>A write is legal on a DRAFT, and only until that draft is used.</b> A built-in and a loaded
     * {@code .ttf} are shared, interned values — writing one would restyle every surface already holding it — so
     * they refuse, naming {@code :derive()}. And once a draft has been handed to a rule, to a widget or to a draw
     * call it is SEALED, because each of those reads it at that moment: a later write would look like it took and
     * change nothing, which is the silent failure this grammar exists to delete.
     *
     * <p><b>{@code size} and {@code aa} take an explicit {@code nil}</b>, and the other three do not. Those two
     * are the pair the page documents an inherited state for — "the stock size of whatever surface it is
     * applied to", "inherits the surface's stock setting" — so writing one is the "undo your layer" meaning
     * {@code conventions.md} already gives {@code w:size(nil)}, on the same word. {@code bold} and
     * {@code italic} are baked into the AWT face and have no such state; a {@code nil} at either is the
     * accident {@link Args#nilRefused} names.
     */
    private static LuaValue property(final FontHandle fh, final String prop) {
        // `prop`, never `name`: LuaJ's LibFunction declares a `protected String name`, and an inherited field
        // shadows an enclosing method's parameter of the same name inside an anonymous subclass (019.4/029.1).
        // It compiles, it runs, and every read silently answers the LAST branch of the dispatch below.
        return new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = a.arg1();
                if(!Args.passed(a, 2))
                    return read(fh, prop);
                LuaValue v = a.arg(2);
                // An explicit nil is an accident everywhere the page does not document a meaning for it -- and
                // on these two the page documents one: `nil` is the stock of whatever surface the handle is
                // applied to, so h:size(nil) UNDOES this variant's layer, the same meaning w:size(nil) carries
                // on the same word. It is a write like any other, so the ownership guard judges it first: a
                // sealed draft refuses it naming :derive(), rather than clearing a field nothing will re-read.
                boolean clear = v.isnil();
                if(clear && !"size".equals(prop) && !"aa".equals(prop))
                    throw Args.nilRefused("font:" + prop, prop);
                fh.writable("font:" + prop);
                if("size".equals(prop))
                    fh.size = clear ? null : optSize(v, "font:size");
                else if("aa".equals(prop))
                    fh.aa = clear ? null : Boolean.valueOf(v.toboolean());
                else if("color".equals(prop))
                    fh.color = colorArg(a, 2, "font:color");
                else
                    fh.style("bold".equals(prop), v.toboolean());
                return self;
            }
        };
    }

    /** The read half of {@link #property}: nil where the handle inherits the surface's own stock value. */
    private static LuaValue read(FontHandle fh, String prop) {
        if("size".equals(prop))
            return (fh.size == null) ? LuaValue.NIL : LuaValue.valueOf(fh.size.intValue());
        if("aa".equals(prop))
            return (fh.aa == null) ? LuaValue.NIL : LuaValue.valueOf(fh.aa.booleanValue());
        if("color".equals(prop))
            return AddonManager.color(fh.color);
        if("bold".equals(prop))
            return LuaValue.valueOf(fh.font.isBold());
        return LuaValue.valueOf(fh.font.isItalic());
    }

    /**
     * Tear down everything this addon styled (reload/disable/relogin, spec 05): its <b>stylesheet</b>'s site
     * entries ({@code hafen.ui():sheet()}, 033.1) through {@link Fonts#removeOwner}, then everything it styled
     * <b>per widget</b> — the sheet's tree rules and its {@code widget:rule()} levels — through
     * {@link Sheet#forget}, which is where the whole per-widget cascade lives. Both bump the generation counter, so
     * every routed site reverts to the stock foundry. Called from {@link AddonRegistry#teardown}.
     */
    static void teardownFonts(Addon a) {
        if((a.skin == null) && !a.skinNodes)
            return;                       // never styled anything → nothing to revert (avoids a needless gen bump)
        Fonts.removeOwner(a);             // the sheet's named scopes
        Sheet.forget(a);                  // its TREE rules and its widget:rule() levels leave with it (034.1/034.3)
    }

    // ------------------------------------------------------------------ opt parsing

    /** A positive logical-px size from a Lua value. Rejects a non-number / a non-positive one. */
    private static Integer optSize(LuaValue v, String ctx) {
        if(!v.isnumber())
            throw new LuaError(ctx + ": 'size' must be a number (design px)");
        int px = v.toint();
        if(px <= 0)
            throw new LuaError(ctx + ": 'size' must be a positive number (design px)");
        return Integer.valueOf(px);
    }

}
