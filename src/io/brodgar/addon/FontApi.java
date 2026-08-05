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
 *       that says what a client surface looks like. The {@link Fonts} provider and its owner-tagged stack are
 *       unchanged: only <i>who fills them</i> moved. Their teardown still runs from here
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
        h = fontHandle(new FontHandle(base, null, null, null));
        owner.assets.putBuiltinFont(name, h);
        return h;
    }

    /** The AWT font behind a built-in name, or a {@link LuaError} that also points a path at {@code hafen.asset}. */
    private static Font builtinFont(String name) {
        if("sans".equals(name))    return Text.sans;
        if("serif".equals(name))   return Text.serif;
        if("mono".equals(name))    return Text.mono;
        if("fraktur".equals(name)) return Text.fraktur;
        throw new LuaError("hafen.font():get(\"" + name + "\"): not a built-in font — the built-ins are "
            + BUILTINS + "; a font FILE this addon ships is an asset: hafen.asset():get(\"fonts/Inter.ttf\")");
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
                if(v.isnil())
                    throw Args.nilRefused("font:" + prop, prop);
                fh.writable("font:" + prop);
                if("size".equals(prop))
                    fh.size = optSize(v, "font:size");
                else if("aa".equals(prop))
                    fh.aa = Boolean.valueOf(v.toboolean());
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
            return colorValue(fh.color);
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
            throw new LuaError(ctx + ": 'size' must be a number (logical px)");
        int px = v.toint();
        if(px <= 0)
            throw new LuaError(ctx + ": 'size' must be a positive number (logical px)");
        return Integer.valueOf(px);
    }

}
