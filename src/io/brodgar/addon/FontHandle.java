package io.brodgar.addon;

import haven.RichText;
import haven.UI;

import java.awt.Color;
import java.awt.Font;
import java.util.HashMap;
import java.util.Map;

import org.luaj.vm2.LuaValue;

/**
 * A loaded, per-addon <b>font</b> (spec {@code specs/addons/21-fonts.md}, F1 / D-043) — the Java half of a
 * font handle — {@code hafen.font(name)} for a built-in ({@code Text.sans/serif/mono/fraktur}) or
 * {@code hafen.asset(path)} for a {@code .ttf}/{@code .otf} the addon ships (via {@code Font.createFont}) —
 * with its bold/italic already baked in, plus the optional {@code size} (<b>logical</b> px), {@code aa}, and
 * default {@code color}. It is a <b>private value the addon holds</b> — there is no shared cross-addon registry
 * (D-043): another addon cannot look it up, so there are no name collisions and no coupling.
 *
 * <p><b>Handle, not a ref.</b> Like {@link LuaImage}, a font has no server identity, so it is addressed by a
 * bridge-owned handle ({@link FontApi#fontHandle}) exposing {@code :derive(opts)} &rarr; a cheap variant,
 * {@code :family()} &rarr; the AWT family name (feed it to a {@code $font[…]} rich-text tag, F2), and
 * {@code :size()} &rarr; the handle's logical px size. The handle table carries this object as an <b>opaque
 * userdata</b> (the {@link #KEY} field) so {@code hafen.font.setFont}, a widget's {@code font=} option (F2), and
 * the {@code g:text} draw wrapper (F2) can {@link #resolve} it back — <b>facade-safe</b> (no AWT {@code Font}
 * crosses into Lua, D-017): the userdata has no metatable, so no Java method is reachable, and it cannot be
 * forged (the sandbox omits {@code luajava}).
 *
 * <p><b>Immutable.</b> All fields are final; {@code :derive} builds a fresh handle rather than mutating. Loading a
 * {@code .ttf} also registers its family into AWT (see {@link FontApi}) so {@link #family()} resolves in a
 * {@code $font} tag with zero {@code RichText} edit.
 */
public final class FontHandle {
    /** The handle-table field carrying this object as an opaque userdata (read by {@link #resolve}). */
    static final LuaValue KEY = LuaValue.valueOf("__font");

    final Font    font;    // the AWT font with bold/italic baked in (size applied per-site by the provider)
    final Integer size;    // logical px, or null = "use the stock size of whatever surface this is applied to"
    final Boolean aa;      // or null = inherit the surface's stock antialias flag
    final Color   color;   // or null = inherit the surface's stock default colour
    LuaValue handle;       // the Lua handle table (set by FontApi.fontHandle)

    // F2 own-widget drawing (g:text / a window's or widget's :font(h)): a cached RichText.Foundry per effective
    // px, so the per-frame draw wrapper does not rebuild one each call. Rendering through RichText (not a plain
    // Text.Foundry) is what makes a $font[family,sz]{…} tag work in an addon's own text (the family was
    // AWT-registered at load). Glyphs are rasterised WHITE (defcol) so the GOut draw colour / a per-call colour
    // still tints on blit — the foundries stay colour-agnostic and cache well. Guarded by `this` (built on the
    // UI/render thread). Lazily allocated (an addon may never draw with its font).
    private Map<Integer, RichText.Foundry> richCache;

    FontHandle(Font font, Integer size, Boolean aa, Color color) {
        this.font = font;
        this.size = size;
        this.aa = aa;
        this.color = color;
    }

    /** The AWT family name — what {@code h:family()} returns and what a {@code $font[family,sz]{…}} tag resolves by (F2). */
    String family() {
        return font.getFamily();
    }

    /**
     * A cached {@link RichText.Foundry} for own-widget drawing (F2) at this handle's effective px — its own
     * {@code size} (UI-scaled) if set, else the caller's {@code stockPx}. The base font carries this handle's
     * family + bold/italic; a {@code $font[…]} tag overrides per run because the family was AWT-registered at
     * load. Glyphs render WHITE so the GOut draw colour tints on blit (see the field note); {@code aa}
     * follows the handle (default off, matching {@link haven.Text#std}). Immutable handle &rarr; the cache is
     * stable and small (typically one entry).
     */
    synchronized RichText.Foundry rich(int stockPx) {
        int px = (size != null) ? Math.round(UI.scale((float)size.intValue())) : stockPx;
        if(richCache == null)
            richCache = new HashMap<Integer, RichText.Foundry>();
        RichText.Foundry f = richCache.get(px);
        if(f == null) {
            f = new RichText.Foundry(font.deriveFont((float)px), Color.WHITE).aa((aa != null) && aa.booleanValue());
            richCache.put(px, f);
        }
        return f;
    }

    /**
     * Resolve a Lua value (a font handle table — {@code hafen.asset}/{@code hafen.font} — or the raw backing userdata) back to its
     * {@link FontHandle}; {@code null} for anything else (a nil/typo/foreign value). Mirrors {@link LuaImage#resolve}.
     */
    static FontHandle resolve(LuaValue v) {
        if(v == null)
            return null;
        LuaValue u = v.istable() ? v.get(KEY) : v;
        if(u.isuserdata()) {
            Object o = u.touserdata();
            if(o instanceof FontHandle)
                return (FontHandle)o;
        }
        return null;
    }
}
