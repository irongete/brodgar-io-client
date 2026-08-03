package io.brodgar.addon;

import haven.Coord;
import haven.Fonts;
import haven.GOut;
import haven.Window;

/**
 * The <b>sheet-fed window chrome</b> (spec {@code 035-ui-chrome}, feature C2): a {@link Window.Deco} that paints
 * its background and its frame from the {@code bg}/{@code border} a stylesheet rule resolved for that window,
 * and paints exactly the stock chrome for every property the rule does <b>not</b> name.
 *
 * <pre>
 *   hafen.ui.skin{ ["window.frame"] = { bg = { color = {26,26,28,240} } } }
 * </pre>
 *
 * <p><b>Both halves of this are seams the engine already has.</b> {@code Window.deco} is swappable live through
 * the public {@link Window#chdeco} — {@code DefaultDeco} is merely <i>one</i> implementation, and {@code dhide}
 * already swaps it from a server message — and {@code IBox} is already an interface, so 9-slice is a first-class
 * engine concept rather than something this feature invents. (nurgling2 hardcoded texture paths into
 * {@code haven.Window}'s constants instead: one skin, not selectable, not revertible. We use the seam.)
 *
 * <p><b>It extends {@code DefaultDeco} on purpose.</b> The chrome is not just a background and a frame — it is
 * also the close button, the sizer, the caption plate's hit-test and, above all, {@code iresize}/{@code contarea},
 * the one place that decides where a window's content starts. Subclassing keeps every one of them identical
 * (035.1 leaves geometry entirely stock; {@code pad} is 035.2), so the whole diff is <i>which pixels</i>
 * {@code drawbg}/{@code drawframe} put down. It also keeps the caption alive: a skinned frame still renders
 * {@code cap} through {@code DefaultDeco.checkcap()}, which is F3a's routed blur/tex furnace — paint it any other
 * way and {@code window.title} would silently stop working the moment a theme was installed.
 *
 * <p><b>No Lua runs to paint a frame.</b> The resolved {@link Chrome.Bg}/{@link Chrome.Border} are plain parsed
 * data, refreshed by {@link #check} once per window per tick; the draw reads two fields. Chrome has no raster
 * cache (unlike text, which got 026's), so it is redrawn every frame and a per-frame Lua callback was rejected on
 * cost at design time.
 *
 * <p><b>The stock chrome always comes back.</b> When no rule names a window any more — {@code skin(nil)},
 * disable, {@code :reload}, relog — {@link #check} swaps a stock {@code DefaultDeco} back, built with the very
 * {@code lg}/{@code dragsize} the deco it displaced carried. It cannot be the <i>same object</i>: {@code chdeco}
 * destroys the deco it replaces, so keeping one would mean re-adding a destroyed widget. What is restored is its
 * configuration exactly, which is also all {@code Window.makedeco()} itself ever builds.
 */
final class SkinDeco extends Window.DefaultDeco {
    /** The scope key a window's chrome resolves through — the sibling of {@code "window.title"} (030 found the frame is a CHILD, so a {@code window} rule never reaches it). */
    static final String SCOPE = "window.frame";

    private Chrome.Bg bg;
    private Chrome.Border border;

    private SkinDeco(boolean lg) {
        super(lg);
    }

    // ---- painting ----------------------------------------------------------------------------------

    /**
     * The rule's background, or the stock tiled one when the rule names none.
     *
     * <p><b>A background is bounded by the frame that sits on it.</b> The stock {@code drawbg} fills the content
     * area alone because the stock frame's own art is opaque and covers everything outside it; a <i>skinned</i>
     * border is the whole frame, and a 9-slice is transparent wherever the image is, so a bg that stopped at the
     * content area would leave the window's margin — 18x30 logical px, far thicker than a typical border image —
     * showing the empty buffer {@code Window.draw} clears to transparent black. So the rule's bg fills the whole
     * deco when the rule frames it too, and the content area when the STOCK frame is still in charge of the
     * margin (where filling the whole deco would square off the stock chrome's shaped corners).
     */
    protected void drawbg(GOut g) {
        Chrome.Bg b = this.bg;
        if(b == null) {
            super.drawbg(g);
            return;
        }
        if(this.border != null)
            b.draw(g, Coord.z, sz);          // our own border covers it: the bg is the whole surface under it
        else
            b.draw(g, ca.ul, ca.sz());       // the stock frame still paints the margin: stay inside it
    }

    /**
     * The rule's 9-slice frame, or the stock one when the rule names none. A skinned frame keeps the sizer and
     * the caption — the caption through {@code checkcap()}, so it is still the {@code "window.title"} font — but
     * not the stock caption plate: the border image owns the frame art, plate included.
     */
    protected void drawframe(GOut g) {
        Chrome.Border b = this.border;
        if(b == null) {
            super.drawframe(g);
            return;
        }
        checkcap();
        b.draw(g, Coord.z, sz);
        if(dragsize)
            g.image(Window.sizer, ca.br.sub(Window.sizer.sz()));
        if(cap != null)
            g.image(cap.tex(), Window.cpo);
    }

    // ---- the swap ----------------------------------------------------------------------------------

    /**
     * Should {@code wnd} be carrying a skin deco right now, and with what? Called from {@code Window.tick}
     * through {@code haven.AddonWidgets} — once per window per frame, which is why the whole no-addon path is
     * one {@code instanceof} and one {@code volatile} read.
     *
     * <p><b>Three rules decide, and each of them is a refusal to guess.</b> Only a window whose deco is
     * <i>exactly</i> the stock {@code DefaultDeco} is dressed: a window with a deco of its own (a
     * {@code GItem.HoverDeco}, any subclass) built it for a reason, and replacing it would throw that away. A
     * window is dressed only while {@link Window#visible()} — animation-aware since 031, so one that is hidden,
     * fading in or dying is left to settle rather than swapped mid-{@code animst}. Undressing, by contrast,
     * happens whenever the rules stop naming it, visible or not: a restore that waited would leave a themed
     * frame on screen after the addon that asked for it was gone.
     */
    static void check(Window wnd) {
        Window.Deco d = wnd.deco;
        boolean have = (d instanceof SkinDeco);
        if(!have) {
            if(!Fonts.styled())
                return;                                     // no override anywhere: nothing could name this window
            if((d == null) || (d.getClass() != Window.DefaultDeco.class))
                return;                                     // its own deco, or none — not ours to replace
            if(!wnd.visible())
                return;                                     // hidden, opening or dying: not while animst runs
        }
        Fonts.Style st = Fonts.styleFor(SCOPE, wnd);
        Chrome.Bg bg = (st == null) ? null : (Chrome.Bg)st.bg();
        Chrome.Border bd = (st == null) ? null : (Chrome.Border)st.border();
        boolean want = (bg != null) || (bd != null);
        if(have) {
            SkinDeco sd = (SkinDeco)d;
            if(want) {
                sd.bg = bg;                                 // a changed rule repaints; the deco itself stays put
                sd.border = bd;
            } else {
                wnd.chdeco(new Window.DefaultDeco(sd.lg).dragsize(sd.dragsize));
            }
        } else if(want) {
            Window.DefaultDeco od = (Window.DefaultDeco)d;
            SkinDeco sd = new SkinDeco(od.lg);
            sd.dragsize(od.dragsize);
            sd.bg = bg;
            sd.border = bd;
            wnd.chdeco(sd);
        }
    }
}
