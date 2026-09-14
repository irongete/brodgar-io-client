package io.brodgar.addon;

import haven.Widget;

/**
 * <b>Whose string is being rendered on this thread</b> — the client's own, or an addon's. A catalogue
 * ({@link LocaleApi}) translates both alike, at the raster; what differs is the <b>miss</b>: a string the
 * client drew and no catalogue named is what a translator wants to read back, and a string an addon drew
 * is the addon's own words, which the author writing the client's file does not want in the list. The
 * render site cannot tell the two apart — {@code Fonts.display} sees a scope and a string — so the sites
 * that draw an addon's text say so around the draw, and {@link LocaleApi.Held#missed} asks here.
 *
 * <p><b>Two signals, one answer.</b> A string rendered while an addon's Lua is running
 * ({@link AddonManager#current}) is that addon's: a {@code g:text} in a {@code Draw} handler, a
 * {@code w:text(s)} whose label renders on the write, a {@code hafen.ui():measure}. A string rendered by
 * the engine on the addon's behalf, outside its Lua, is bracketed by {@link #enter}/{@link #exit} at the
 * site that draws it: the whole addon layer ({@code UILoop.display}), a control of the addon's standing in
 * a session's tree (the text adapters' {@code draw}, the rows {@link LuaRows} builds), a label an overlay
 * record paints ({@link LuaGOut}), and the tip a widget of the addon's answers ({@code UILoop.drawtooltip}).
 * Nesting is fine: the layer's bracket holds the controls' inside it.
 *
 * <p>A depth per thread, because a render is answered on the thread that draws, and one frame draws every
 * tree in turn. Not instantiable.
 */
public final class AddonText {
    private AddonText() {}

    /** How deep inside an addon's own draw this thread is; a render at depth 0 is the client's own. */
    private static final ThreadLocal<int[]> depth = new ThreadLocal<int[]>() {
        protected int[] initialValue() {
            return new int[1];
        }
    };

    /** Every string rendered until the matching {@link #exit} is an addon's. Nests. */
    public static void enter() {
        depth.get()[0]++;
    }

    /** The other half of {@link #enter}. */
    public static void exit() {
        depth.get()[0]--;
    }

    /** Is the string being rendered right now an addon's — inside its draw, or inside its Lua? */
    static boolean drawing() {
        return (depth.get()[0] > 0) || (AddonManager.current() != null);
    }

    /** Did an addon build {@code w} — a control, a surface, or the chrome around one? {@code false} for {@code null}. */
    public static boolean built(Widget w) {
        return Owned.of(w) != null;
    }
}
