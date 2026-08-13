package io.brodgar.addon;

import haven.RichText;
import haven.UI;

import java.awt.Color;
import java.awt.Font;
import java.util.HashMap;
import java.util.Map;

import org.luaj.vm2.LuaError;
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
 * <p><b>Immutable once it is USED.</b> {@code :derive()} hands back a draft whose five properties are chained
 * setters; the moment {@link #resolve} gives the object to a consumer it is sealed, and every other handle (a
 * built-in, a loaded file) is shared and refuses a setter outright. So nothing that a surface is drawing with
 * can change under it. Loading a
 * {@code .ttf} also registers its family into AWT (see {@link FontApi}) so {@link #family()} resolves in a
 * {@code $font} tag with zero {@code RichText} edit.
 */
public final class FontHandle {
    /** The handle-table field carrying this object as an opaque userdata (read by {@link #resolve}). */
    static final LuaValue KEY = LuaValue.valueOf("__font");

    Font    font;          // the AWT font with bold/italic baked in (size applied per-site by the provider)
    Integer size;          // logical px, or null = "use the stock size of whatever surface this is applied to"
    Boolean aa;            // or null = inherit the surface's stock antialias flag
    Color   color;         // or null = inherit the surface's stock default colour
    LuaValue handle;       // the Lua handle table (set by FontApi.fontHandle)

    // The two flags that make the four fields above safe to be non-final. A handle is WRITABLE only while it is
    // a draft that nothing has used yet: `draft` is set for what :derive() hands back and for nothing else (a
    // built-in and a loaded .ttf are shared, interned values), and `used` is set the moment resolve() hands this
    // object to a consumer -- a rule, a widget, a draw call -- each of which reads it right then. Guarded by this.
    private boolean draft;
    private boolean used;

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

    /**
     * A fresh <b>draft</b> of this font — what {@code h:derive()} hands back. It starts as a copy, so an omitted
     * setter inherits, and it is the only shape of this object whose properties may be written.
     */
    synchronized FontHandle draft() {
        FontHandle d = new FontHandle(font, size, aa, color);
        d.draft = true;
        return d;
    }

    /**
     * Assert that a property of this handle may be written, or refuse naming what to write instead. The two
     * refusals are different mistakes and say so: a shared font was never yours to restyle, and a draft already
     * in use would take the write and show nothing for it.
     */
    synchronized void writable(String verb) {
        if(!draft)
            throw new LuaError(verb + ": this font is shared — a built-in and a file this addon loaded are"
                + " interned values, and writing one would restyle every surface already using it. The variant"
                + " is h:derive(), whose properties are yours to set");
        if(used)
            throw new LuaError(verb + ": this font is already in use — a rule, a widget or a draw call read it"
                + " when you handed it over, so a write now would change nothing. Derive another variant from"
                + " it: h:derive():" + verb.substring(verb.indexOf(':') + 1) + "(...)");
    }

    /**
     * Seal a variant this bridge built for a consumer that is reading it right now — a face a rule
     * <b>named</b> rather than was handed ({@link FontApi#face}). It is the same moment {@link #resolve}
     * marks, said from the other side: the rule read this face the instant it was parsed, so a later setter
     * on the handle it reads back would take and change nothing.
     */
    synchronized void seal() {
        used = true;
    }

    /** Write {@code bold} or {@code italic} by re-deriving the AWT font, keeping the other of the two. */
    synchronized void style(boolean bold, boolean on) {
        boolean b = bold ? on : font.isBold();
        boolean i = bold ? font.isItalic() : on;
        font = font.deriveFont((b ? Font.BOLD : 0) | (i ? Font.ITALIC : 0));
        richCache = null;                 // the cached foundries were built from the old face
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
            if(o instanceof FontHandle) {
                FontHandle fh = (FontHandle)o;
                synchronized(fh) { fh.used = true; }   // a consumer read it HERE: a later setter would be silent
                return fh;
            }
        }
        return null;
    }
}
