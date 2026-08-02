package io.brodgar.addon;

import haven.Fonts;
import haven.Text;

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
 *       rule, and {@code hafen.ui.skin{ ["window.title"] = { font = h } }} ({@link Sheet}) is the single place
 *       that says what a client surface looks like. The {@link Fonts} provider and its owner-tagged stack are
 *       unchanged: only <i>who fills them</i> moved. Their teardown still runs from here
 *       ({@link #teardownFonts}) — the owned-resource model (spec 05).</li>
 *   <li><b>Own-widget application</b> (F2, shipped): a {@code font=} option on {@code hafen.ui.window}/{@code
 *       widget} ({@link AddonWidget}) and a per-call {@code {font,color}} on the {@code g:text}/{@code g:atext} draw
 *       wrapper ({@link LuaGOut}) + a custom TTF in the {@code $font[…]} rich-text tag (family AWT-registered
 *       when the asset is loaded). Isolated — touches only the addon's own pixels; no global state, nothing to revert.</li>
 *   <li><b>Per-instance overrides</b> (F5): {@code node:setFont(h)} / {@code node:resetFont()} on any
 *       {@link LuaWidget} (spec 20) restyle <b>one</b> native widget and its subtree while its siblings keep the
 *       scope/{@code "default"} font — the top of the resolution chain ({@link #setNodeFont}, built into the node
 *       handle by {@link UiApi}). Owner-tagged and reverted on teardown like a scope override.</li>
 * </ul>
 * The Lua handle is facade-safe (no AWT {@code Font} crosses into Lua, D-017): a widget/scope stores the handle,
 * the bridge {@link FontHandle#resolve}s it back. Not instantiable.
 */
final class FontApi {
    private FontApi() {}

    /** The built-in font names {@code hafen.font(name)} answers to, as the error text lists them. */
    private static final String BUILTINS = "\"sans\", \"serif\", \"mono\" or \"fraktur\"";

    /** Build {@code hafen.font} for {@code owner}. From installHafen. */
    static void installFont(LuaTable hafen, final Addon owner) {
        LuaTable font = new LuaTable();
        // hafen.font.setFont(scope, h) / .reset(scope) / .scopes() are GONE (033.1, hard cut — they read as plain
        // nil). A font is not an API of its own any more, it is ONE PROPERTY of a stylesheet rule, so the surface
        // that used to be setFont("window.title", h) is now
        //     hafen.ui.skin{ ["window.title"] = { font = h } }
        // — one sheet per addon, applied live, dropped with hafen.ui.skin(nil) and reverted on :reload/disable
        // (Sheet). The scope enum is gone with it: a sheet key is a SELECTOR, the same string hafen.ui(sel) takes,
        // so there is one vocabulary for "which part of the UI" instead of two. hafen.font itself keeps its ONE
        // job below — naming an engine font (D-060).
        // hafen.font(name) — the CALL form: one of the client's four BUILT-IN fonts, "sans" | "serif" | "mono" |
        // "fraktur". They are ENGINE-owned, so they are ADDRESSED, not loaded (the hafen.sound(name) shape): the
        // handle is interned per addon, has no lifetime, and therefore carries no :dispose()/:path()/:type()
        // (D-060). The addon's OWN .ttf/.otf is an ASSET: hafen.asset("fonts/Inter.ttf"). Size/style come from
        // :derive{size=12} in both cases — the load takes a name (or a path) and nothing else.
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.CALL, new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                return builtin(owner, a.arg(2));   // arg1 = the callable table itself
            }
        });
        font.setmetatable(mt);
        hafen.set("font", font);
    }

    // ------------------------------------------------------------------ built-ins + handle

    /**
     * {@code hafen.font(name)}: the interned {@link FontHandle} for one of the client's built-in fonts
     * ({@code Text.sans}/{@code serif}/{@code mono}/{@code fraktur}). Engine-owned, so — exactly like
     * {@code hafen.sound(name)} — it is keyed by <b>name</b>, has no lifetime, and gets none of the asset verbs
     * ([D-060]). Interned per addon, so {@code hafen.font("mono") == hafen.font("mono")}. A path, a typo or a
     * missing argument all raise an error naming both this call and {@code hafen.asset}.
     */
    private static LuaValue builtin(Addon owner, LuaValue namev) {
        if(namev.isnumber())        // BEFORE isstring(): in LuaJ a number IS a string
            throw new LuaError("hafen.font(name): the key is a built-in font NAME (" + BUILTINS + "), not a number");
        if(!namev.isstring())
            throw new LuaError("hafen.font(name): expected a built-in font name (" + BUILTINS + "), got "
                + namev.typename() + " — the addon's own .ttf/.otf is hafen.asset(\"fonts/Inter.ttf\")");
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
        throw new LuaError("hafen.font(\"" + name + "\"): not a built-in font — the built-ins are " + BUILTINS
            + "; a font FILE this addon ships is an asset: hafen.asset(\"fonts/Inter.ttf\")");
    }

    /**
     * The Lua handle for a {@link FontHandle}: the opaque backing userdata ({@link FontHandle#KEY}) plus
     * {@code :derive(opts)} / {@code :family()} / {@code :size()}. Facade-safe (no AWT {@code Font} reaches Lua).
     * A font <b>asset</b> ({@link AssetApi}) is this table plus the shared {@code :type()}/{@code :path()}/
     * {@code :dispose()}; a built-in or a {@code :derive}d variant is this table alone — it was never loaded from
     * a file, so it has no path and no lifetime.
     */
    static LuaTable fontHandle(final FontHandle fh) {
        LuaTable h = new LuaTable();
        h.set(FontHandle.KEY, LuaValue.userdataOf(fh));   // opaque backing ref for setFont / font= / g:text (F2)
        h.set("derive", new VarArgFunction() {            // a cheap variant with different size/aa/bold/italic/color
            public Varargs invoke(Varargs a) {
                return derive(fh, a.arg(2));
            }
        });
        h.set("family", new ZeroArgFunction() {
            public LuaValue call() { return LuaValue.valueOf(fh.family()); }
        });
        h.set("size", new ZeroArgFunction() {
            public LuaValue call() { return (fh.size == null) ? LuaValue.NIL : LuaValue.valueOf(fh.size.intValue()); }
        });
        fh.handle = h;
        return h;
    }

    /**
     * {@code h:derive(opts)}: a fresh handle sharing {@code h}'s base family but overriding any of
     * {@code size}/{@code aa}/{@code color}/{@code bold}/{@code italic}. An omitted field inherits {@code h}'s.
     * Immutable — never mutates {@code h}.
     */
    private static LuaValue derive(FontHandle fh, LuaValue optsv) {
        if(!optsv.isnil() && !optsv.istable())
            throw new LuaError("font:derive(opts) expects a table { size=, aa=, bold=, italic=, color= } — use a COLON call");
        LuaValue opts = optsv.istable() ? optsv : LuaValue.NIL;
        Font base = fh.font;
        if(opts.istable() && (!opts.get("bold").isnil() || !opts.get("italic").isnil())) {
            boolean bold   = opts.get("bold").toboolean();
            boolean italic = opts.get("italic").toboolean();
            base = fh.font.deriveFont((bold ? Font.BOLD : 0) | (italic ? Font.ITALIC : 0));
        }
        Integer size = opts.istable() && !opts.get("size").isnil()  ? optSize(opts.get("size"), "font:derive") : fh.size;
        Boolean aa   = opts.istable() && !opts.get("aa").isnil()    ? optBool(opts.get("aa"))                  : fh.aa;
        Color color  = opts.istable() && !opts.get("color").isnil() ? optColor(opts.get("color"))             : fh.color;
        return fontHandle(new FontHandle(base, size, aa, color));
    }

    // ------------------------------------------------------------------ per-instance overrides (F5, node:setFont)

    /**
     * {@code widget:setFont(h)} (F5, spec 20 + 21): install {@code owner}'s <b>per-instance</b> font override on one
     * live widget — it restyles that widget and everything drawn inside it, while its siblings keep the
     * scope/{@code "default"} font (the top of the resolution chain). Owner-tagged and reverted on teardown exactly
     * like a scope override; the provider keys it by widget identity with a <b>weak</b> key, so a window that closes
     * takes its override with it. {@code w == null} = a stale node (its widget left the tree) → nothing to style,
     * but the handle is still validated so a bad call is a clear error either way.
     *
     * <p>The handle's <b>colour is not applied</b> (033.2): a native widget is a client SURFACE, and a surface's
     * colour comes from the stylesheet — one place, visible in the sheet — while a handle's colour is for the
     * addon's own drawing. This method takes the family/size/aa and passes {@code null} for the colour; C1b folds
     * it into {@code widget:skin{…}}, where a per-widget {@code color} is a rule property like any other.
     */
    static void setNodeFont(Addon owner, haven.Widget w, LuaValue hv) {
        FontHandle fh = FontHandle.resolve(hv);
        if(fh == null)
            throw new LuaError("widget:setFont(h): h must be a font handle — hafen.asset(\"fonts/X.ttf\") or hafen.font(\"sans\") — use a COLON call");
        if(w == null)
            return;
        Fonts.pushInstance(w, owner, fh.font, fh.size, fh.aa, null);
        owner.fontNodes = true;
    }

    /**
     * {@code widget:resetFont()} (F5): drop {@code owner}'s per-instance override on this widget — it falls back to
     * whatever is beneath (another addon's per-instance override, else the scope/{@code "default"} chain, else
     * stock). A no-op on a stale node or when this addon had no override there.
     */
    static void resetNodeFont(Addon owner, haven.Widget w) {
        if(w != null)
            Fonts.resetInstance(w, owner);
    }

    /**
     * Tear down everything this addon styled (reload/disable/relogin, spec 05): its <b>stylesheet</b>'s site
     * entries ({@code hafen.ui.skin}, 033.1) and its per-instance {@code widget:setFont} overrides (F5), in one
     * sweep — {@link Fonts#removeOwner} pulls both and bumps the generation counter, so every routed site reverts
     * to the stock foundry. Called from {@link AddonRegistry#teardown}.
     */
    static void teardownFonts(Addon a) {
        if((a.skin == null) && !a.fontNodes)
            return;                       // never styled anything → nothing to revert (avoids a needless gen bump)
        Fonts.removeOwner(a);             // sweeps both the sheet's named scopes and the per-instance registry (F5)
        a.skin = null;
        a.fontNodes = false;
    }

    // ------------------------------------------------------------------ opt parsing

    /** A positive logical-px size from a Lua value, or {@code null} if unset. Rejects a non-number / non-positive. */
    private static Integer optSize(LuaValue v, String ctx) {
        if(v.isnil())
            return null;
        if(!v.isnumber())
            throw new LuaError(ctx + ": 'size' must be a number (logical px)");
        int px = v.toint();
        if(px <= 0)
            throw new LuaError(ctx + ": 'size' must be a positive number (logical px)");
        return Integer.valueOf(px);
    }

    /** A Boolean from a Lua value, or {@code null} if unset (so the surface's stock flag is inherited). */
    private static Boolean optBool(LuaValue v) {
        return v.isnil() ? null : Boolean.valueOf(v.toboolean());
    }

    /** A {@link Color} from a {@code {r,g,b[,a]}} Lua table (0..255), or {@code null} if unset. */
    private static Color optColor(LuaValue v) {
        return (v.isnil() || !v.istable()) ? null : luaColor(v, null);
    }
}
