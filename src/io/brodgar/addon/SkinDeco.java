package io.brodgar.addon;

import haven.Area;
import haven.Coord;
import haven.Fonts;
import haven.GOut;
import haven.Widget;
import haven.Window;

/**
 * The <b>sheet-fed window chrome</b> (spec {@code 035-ui-chrome}, feature C2): a {@link Window.Deco} that paints
 * its background and its frame from the {@code bg}/{@code border} a stylesheet rule resolved for that window,
 * and paints exactly the stock chrome for every property the rule does <b>not</b> name.
 *
 * <pre>
 *   hafen.ui():sheet():rule("window.frame"):bg{ color = {26,26,28,240} }
 * </pre>
 *
 * <p><b>Both halves of this are seams the engine already has.</b> {@code Window.deco} is swappable live through
 * the public {@link Window#chdeco} — {@code DefaultDeco} is merely <i>one</i> implementation, and {@code dhide}
 * already swaps it from a server message — and {@code IBox} is already an interface, so 9-slice is a first-class
 * engine concept rather than something this feature invents. (nurgling2 hardcoded texture paths into
 * {@code haven.Window}'s constants instead: one skin, not selectable, not revertible. We use the seam.)
 *
 * <p><b>It extends {@code DefaultDeco} on purpose.</b> The chrome is not just a background and a frame — it is
 * also the close button, the sizer, the caption plate's hit-test and {@code iresize}/{@code contarea}, the one
 * place that decides where a window's content starts. Subclassing keeps every one of them identical except the
 * two draws and, since 035.2, that one geometry method — whose formula is the stock one with the <i>theme's</i>
 * numbers rather than a formula of its own. It also keeps the caption alive: a skinned frame still renders
 * {@code cap} through {@code DefaultDeco.checkcap()}, which is F3a's routed blur/tex furnace — paint it any other
 * way and {@code window.title} would silently stop working the moment a theme was installed.
 *
 * <p><b>No Lua runs to paint a frame, or to lay one out.</b> The resolved {@link Chrome.Bg}/{@link Chrome.Border}
 * and {@code pad} are plain parsed data, refreshed by {@link #check} once per window per tick — and only a change
 * to the two that decide geometry re-lays anything out; the draw reads fields. Chrome has no raster
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
    private int pad;

    private SkinDeco(boolean lg) {
        super(lg);
    }

    // ---- geometry (035.2) --------------------------------------------------------------------------

    /**
     * Where this window's content starts and how big the frame around it is — the one method that can <b>move</b>
     * the client's own layout, and the reason {@code pad} is a new risk class rather than another paint property.
     *
     * <p><b>The formula is the stock one with the theme's numbers in it</b>, not a formula of its own.
     * {@code DefaultDeco} computes {@code content + margin*2 + tlm + brm}: an inner <i>margin</i> (breathing room
     * between the frame art and the content) and an outer pair of <i>frame insets</i> (the room the art itself
     * needs). A rule replaces exactly the half it owns:
     * <ul>
     *   <li><b>{@code pad} takes the margin's place</b> — it is the breathing room, so it is added to the stock
     *       margin when the stock art is still there, and <i>is</i> the whole margin when a {@code border} has
     *       replaced that art.</li>
     *   <li><b>A {@code border}'s slice insets take {@code tlm}/{@code brm}'s place</b>, because they are the same
     *       quantity: the room the frame art needs. This is the geometry twin of D-079 — <i>the margin belongs to
     *       whoever paints the frame</i>. A theme whose caption needs room says so in its own top inset; the
     *       engine adds no hidden minimum, which is what keeps every number here predictable from the rule alone.</li>
     * </ul>
     *
     * <p><b>{@code isz} is the CONTENT size.</b> So padding a window grows it <i>outward</i> around fixed content;
     * it never shrinks the content to fit. Everything else — {@code contarea()} answering {@code aa}, the close
     * button at the top right, the sizer inside {@code ca} — is stock and inherited, so a themed window resizes,
     * drags and closes with exactly the stock code.
     */
    public void iresize(Coord isz) {
        Chrome.Border b = this.border;
        int p = this.pad;
        Coord ftl, fbr, mrgn;
        if(b == null) {
            ftl = Window.tlm; fbr = Window.brm;                    // the stock art still owns the frame insets
            mrgn = (lg ? Window.dlmrgn : Window.dsmrgn).add(p, p); // ...and pad simply widens its margin
        } else {
            ftl = Coord.of(b.l, b.t); fbr = Coord.of(b.r, b.b);    // our 9-slice owns them instead
            mrgn = Coord.of(p, p);                                 // ...and pad IS the whole margin
        }
        Coord csz = isz.add(mrgn.mul(2));
        resize(csz.add(ftl).add(fbr));
        ca = Area.sized(ftl, csz);
        aa = Area.sized(ca.ul.add(mrgn), isz);
        cbtn.c = Coord.of(sz.x - cbtn.sz.x, 0);
    }

    /**
     * Re-run the geometry in place after a rule changed the insets or the pad, <b>keeping the content where it
     * is</b> — which is exactly what {@link Window#chdeco} does around a swap, and the only reason installing and
     * then re-tuning a theme do not disagree about where a window sits. The content size is preserved (it is what
     * {@code iresize} is fed) and the window's own {@code c} absorbs the change in {@code contarea().ul}, so the
     * frame grows outward around the content rather than dragging it across the screen.
     */
    private void repack() {
        Widget p = parent;
        if(!(p instanceof Window))
            return;                        // detached mid-swap: the next chdeco/iresize will do it anyway
        Window wnd = (Window)p;
        Area prev = contarea();
        wnd.resize(prev.sz());             // Window.resize takes the CONTENT size -- see iresize
        wnd.c = wnd.c.add(prev.ul.sub(contarea().ul));
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
        Integer pv = (st == null) ? null : st.pad();
        int pad = (pv == null) ? 0 : pv.intValue();
        // A pad of zero says the same thing as no pad at all, so it alone never dresses a window: the deco would
        // then draw stock pixels at stock coordinates, and installing one for that is a swap nobody asked for.
        boolean want = (bg != null) || (bd != null) || (pad != 0);
        if(have) {
            SkinDeco sd = (SkinDeco)d;
            if(want) {
                // Only these two decide the geometry. Compared BY VALUE: re-applying the same sheet parses a
                // fresh Border, and a repack per tick would be a real cost for a rule that did not change.
                boolean moved = (sd.pad != pad)
                    || ((sd.border == null) ? (bd != null) : !sd.border.equals(bd));
                sd.bg = bg;                                 // a changed rule repaints; the deco itself stays put
                sd.border = bd;
                sd.pad = pad;
                if(moved)
                    sd.repack();                            // ...but a changed GEOMETRY has to re-lay the window out
            } else {
                wnd.chdeco(new Window.DefaultDeco(sd.lg).dragsize(sd.dragsize));
            }
        } else if(want) {
            Window.DefaultDeco od = (Window.DefaultDeco)d;
            SkinDeco sd = new SkinDeco(od.lg);
            sd.dragsize(od.dragsize);
            sd.bg = bg;
            sd.border = bd;
            sd.pad = pad;                                   // BEFORE the swap: chdeco lays the window out with it
            wnd.chdeco(sd);
        }
    }
}
