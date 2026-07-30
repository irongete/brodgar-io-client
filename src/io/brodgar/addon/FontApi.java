package io.brodgar.addon;

import haven.Fonts;
import haven.Text;
import haven.UI;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.ZeroArgFunction;

import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.nio.file.Path;

import static io.brodgar.addon.AddonManager.*;

/**
 * The per-addon font subsystem (spec {@code specs/addons/21-fonts.md}, F-series / D-043): {@code hafen.font.*}
 * gives an addon <b>complete control over the client's typography</b> without a shared registry. It has three
 * parts, all built here:
 * <ul>
 *   <li><b>{@code hafen.font.load(source[, opts])} &rarr; a private {@link FontHandle}.</b> {@code source} = a
 *       built-in name ({@code "sans"/"serif"/"mono"/"fraktur"}) or a {@code .ttf}/{@code .otf} under the addon's
 *       own folder (sandboxed, D-017); {@code opts = {size,aa,bold,italic,color}}. The handle is an opaque,
 *       per-addon Lua value with {@code :derive}/{@code :family}/{@code :size}.</li>
 *   <li><b>Global overrides</b> on named client surfaces (F1 ships {@code "default"}): {@code setFont(scope, h)}
 *       / {@code reset(scope)} / {@code scopes()}, driving the {@code haven}-reachable {@link Fonts} provider.
 *       Each override is <b>owner-tagged</b> and reverted on the addon's teardown ({@link #teardownFonts}) —
 *       the owned-resource model (spec 05).</li>
 *   <li><b>Own-widget application</b> (F2, shipped): a {@code font=} option on {@code hafen.ui.window}/{@code
 *       widget} ({@link LuaWidget}) and a per-call {@code {font,color}} on the {@code g:text}/{@code g:atext} draw
 *       wrapper ({@link LuaGOut}) + a custom TTF in the {@code $font[…]} rich-text tag (family AWT-registered at
 *       {@code load}). Isolated — touches only the addon's own pixels; no global state, nothing to revert.</li>
 *   <li><b>Per-instance overrides</b> (F5): {@code node:setFont(h)} / {@code node:resetFont()} on any
 *       {@link LuaWidgetNode} (spec 20) restyle <b>one</b> native widget and its subtree while its siblings keep the
 *       scope/{@code "default"} font — the top of the resolution chain ({@link #setNodeFont}, built into the node
 *       handle by {@link UiApi}). Owner-tagged and reverted on teardown like a scope override.</li>
 * </ul>
 * The Lua handle is facade-safe (no AWT {@code Font} crosses into Lua, D-017): a widget/scope stores the handle,
 * the bridge {@link FontHandle#resolve}s it back. Not instantiable.
 */
final class FontApi {
    private FontApi() {}

    /** Build {@code hafen.font} for {@code owner}. From installHafen. */
    static void installFont(LuaTable hafen, final Addon owner) {
        LuaTable font = new LuaTable();
        // hafen.font.load(source [, opts]) — load a font into a PRIVATE, per-addon handle (no shared registry, D-043).
        //   source = a built-in name "sans" | "serif" | "mono" | "fraktur", OR an addon-relative path to a .ttf/.otf
        //            (e.g. "fonts/Inter.ttf"; absolute paths and ".." escapes are REJECTED, D-017). Loading a file
        //            also registers its family into AWT so h:family() works in a $font[…] rich-text tag (F2).
        //   opts (all optional): { size = <logical px>, aa = <bool>, bold = <bool>, italic = <bool>,
        //                          color = {r,g,b[,a]} (0..255) }. size passes through UI.scale when a foundry is
        //                          built; omitted => the stock size of whatever surface the font is applied to.
        // Returns a FontHandle (opaque):
        //   :derive(opts)  -- a cheap variant with a different size/aa/bold/italic/color
        //   :family()      -- the AWT family name (feed it to $font[family,sz]{…}, F2)
        //   :size()        -- the handle's logical px size (nil if unset)
        // Apply it to a GLOBAL client surface with hafen.font.setFont(scope, h) (an owned override, reverted on
        // reload/disable); to your OWN drawing with hafen.ui.window/widget{font=h} and g:text(...,{font=h}) (F2).
        font.set("load", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                return FontApi.load(owner, a.arg(1), a.arg(2));   // qualify: LuaValue also has a load(...)
            }
        });
        // hafen.font.setFont(scope, h) — install THIS addon's font override on a named client surface (D-043). scope
        // is one of hafen.font.scopes(); F1 routes "default" (the global fallback — Text.std / Text.render / Label),
        // which CASCADES to every routed surface with no more-specific override, so setFont("default", h) really does
        // change most UI text live. Each scope holds an owner-tagged stack (last-wins); this addon's overrides are
        // reverted automatically on :reload/disable (the stock UI is always restorable). Changing text is invalidated
        // via a generation counter, so it appears live (Label re-renders; Text.render rebuilds each call). Returns nil.
        font.set("setFont", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                return setFont(owner, a.arg(1), a.arg(2));
            }
        });
        // hafen.font.reset(scope) — drop THIS addon's override on that scope (restores whatever is beneath: another
        // addon's override, or the stock foundry). A no-op if this addon had no override there. Returns nil.
        font.set("reset", new OneArgFunction() {
            public LuaValue call(LuaValue scope) {
                return reset(owner, scope);
            }
        });
        // hafen.font.scopes() — the array of valid scope names (discovery). The enum is complete from F1; a scope
        // becomes EFFECTIVE only once its render site is routed through the provider (its slice — F1 = "default").
        font.set("scopes", new ZeroArgFunction() {
            public LuaValue call() {
                LuaTable t = new LuaTable();
                String[] sc = Fonts.scopes();
                for(int i = 0; i < sc.length; i++)
                    t.set(i + 1, LuaValue.valueOf(sc[i]));
                return t;
            }
        });
        hafen.set("font", font);
    }

    // ------------------------------------------------------------------ load + handle

    /**
     * {@code hafen.font.load(source[, opts])}: resolve {@code source} to a base AWT {@link Font} (a built-in, or a
     * {@code .ttf}/{@code .otf} created + AWT-registered from the addon's own folder), apply the {@code opts}
     * (bold/italic baked into the font; size/aa/colour stored on the handle), and return an opaque
     * {@link FontHandle}. Throws a clear {@link LuaError} for a bad source / unreadable font file.
     */
    private static LuaValue load(Addon owner, LuaValue sourcev, LuaValue optsv) {
        if(!sourcev.isstring())
            throw new LuaError("hafen.font.load(source [, opts]) expects a string source (a built-in \"sans\"/\"serif\"/\"mono\"/\"fraktur\", or an addon-relative .ttf/.otf path)");
        if(!optsv.isnil() && !optsv.istable())
            throw new LuaError("hafen.font.load: opts must be a table { size=, aa=, bold=, italic=, color= }");
        String source = sourcev.tojstring();
        Font base = baseFont(owner, source);

        LuaValue opts = optsv.istable() ? optsv : LuaValue.NIL;
        boolean bold   = opts.istable() && opts.get("bold").toboolean();
        boolean italic = opts.istable() && opts.get("italic").toboolean();
        int style = (bold ? Font.BOLD : 0) | (italic ? Font.ITALIC : 0);
        if(style != Font.PLAIN)
            base = base.deriveFont(style);

        Integer size = optSize(opts.istable() ? opts.get("size") : LuaValue.NIL, "hafen.font.load");
        Boolean aa   = optBool(opts.istable() ? opts.get("aa") : LuaValue.NIL);
        Color color  = optColor(opts.istable() ? opts.get("color") : LuaValue.NIL);
        return fontHandle(new FontHandle(base, size, aa, color));
    }

    /**
     * Resolve a {@code load} source to its base AWT font: a built-in ({@code Text.sans/serif/mono/fraktur}), or a
     * {@code .ttf}/{@code .otf} under the addon's own folder — created via {@code Font.createFont} and registered
     * into AWT (so its family resolves in a {@code $font} tag, F2). Sandboxed to the addon folder (D-017).
     */
    private static Font baseFont(Addon owner, String source) {
        if("sans".equals(source))    return Text.sans;
        if("serif".equals(source))   return Text.serif;
        if("mono".equals(source))    return Text.mono;
        if("fraktur".equals(source)) return Text.fraktur;
        // otherwise a file under the addon folder (a .ttf/.otf) — sandboxed, created, and AWT-registered.
        Path p = RenderApi.resolveAddonAsset(owner, source, "hafen.font.load");
        Font f;
        try {
            f = Font.createFont(Font.TRUETYPE_FONT, p.toFile());
        } catch(java.awt.FontFormatException e) {
            throw new LuaError("hafen.font.load: '" + source + "' is not a valid TrueType/OpenType font: " + e.getMessage());
        } catch(IOException | RuntimeException e) {
            throw new LuaError("hafen.font.load: could not read font '" + source + "': " + e.getMessage());
        }
        try {
            GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(f);   // so h:family() resolves in $font (F2)
        } catch(RuntimeException e) { /* best-effort: even if registration fails the handle still draws via its Font */ }
        return f;
    }

    /**
     * The Lua handle for a {@link FontHandle}: the opaque backing userdata ({@link FontHandle#KEY}) plus
     * {@code :derive(opts)} / {@code :family()} / {@code :size()}. Facade-safe (no AWT {@code Font} reaches Lua).
     */
    private static LuaValue fontHandle(final FontHandle fh) {
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

    // ------------------------------------------------------------------ global overrides (setFont / reset)

    /** {@code hafen.font.setFont(scope, h)}: validate + install {@code owner}'s override on {@code scope} (F1: "default"). */
    private static LuaValue setFont(Addon owner, LuaValue scopev, LuaValue hv) {
        String scope = requireScope(scopev, "hafen.font.setFont");
        FontHandle fh = FontHandle.resolve(hv);
        if(fh == null)
            throw new LuaError("hafen.font.setFont(scope, h): h must be a hafen.font.load handle");
        Fonts.push(scope, owner, fh.font, fh.size, fh.aa, fh.color);
        if(!owner.fontOverrides.contains(scope))
            owner.fontOverrides.add(scope);
        return LuaValue.NIL;
    }

    /** {@code hafen.font.reset(scope)}: drop {@code owner}'s override on {@code scope}. */
    private static LuaValue reset(Addon owner, LuaValue scopev) {
        String scope = requireScope(scopev, "hafen.font.reset");
        Fonts.reset(scope, owner);
        owner.fontOverrides.remove(scope);
        return LuaValue.NIL;
    }

    // ------------------------------------------------------------------ per-instance overrides (F5, node:setFont)

    /**
     * {@code node:setFont(h)} (F5, spec 20 + 21): install {@code owner}'s <b>per-instance</b> font override on one
     * live widget — it restyles that widget and everything drawn inside it, while its siblings keep the
     * scope/{@code "default"} font (the top of the resolution chain). Owner-tagged and reverted on teardown exactly
     * like a scope override; the provider keys it by widget identity with a <b>weak</b> key, so a window that closes
     * takes its override with it. {@code w == null} = a stale node (its widget left the tree) → nothing to style,
     * but the handle is still validated so a bad call is a clear error either way.
     */
    static void setNodeFont(Addon owner, haven.Widget w, LuaValue hv) {
        FontHandle fh = FontHandle.resolve(hv);
        if(fh == null)
            throw new LuaError("node:setFont(h): h must be a hafen.font.load handle — use a COLON call");
        if(w == null)
            return;
        Fonts.pushInstance(w, owner, fh.font, fh.size, fh.aa, fh.color);
        owner.fontNodes = true;
    }

    /**
     * {@code node:resetFont()} (F5): drop {@code owner}'s per-instance override on this widget — it falls back to
     * whatever is beneath (another addon's per-instance override, else the scope/{@code "default"} chain, else
     * stock). A no-op on a stale node or when this addon had no override there.
     */
    static void resetNodeFont(Addon owner, haven.Widget w) {
        if(w != null)
            Fonts.resetInstance(w, owner);
    }

    private static String requireScope(LuaValue scopev, String ctx) {
        if(!scopev.isstring() || !Fonts.isScope(scopev.tojstring()))
            throw new LuaError(ctx + ": scope must be one of hafen.font.scopes() (e.g. \"default\")");
        return scopev.tojstring();
    }

    /**
     * Tear down every font override this addon owns (reload/disable/relogin, spec 05): remove its entries from
     * every scope stack ({@link Fonts#removeOwner}, which bumps the generation counter so routed sites revert to
     * the stock foundry) and clear the owned list. Called from {@link AddonRegistry#teardown}.
     */
    static void teardownFonts(Addon a) {
        if(a.fontOverrides.isEmpty() && !a.fontNodes)
            return;                       // never touched fonts → nothing to revert (avoids a needless gen bump)
        Fonts.removeOwner(a);             // sweeps both the named scopes and the per-instance registry (F5)
        a.fontOverrides.clear();
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
