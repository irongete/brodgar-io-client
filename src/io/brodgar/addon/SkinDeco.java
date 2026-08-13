package io.brodgar.addon;

import haven.Area;
import haven.Coord;
import haven.Fonts;
import haven.GOut;
import haven.IButton;
import haven.TexI;
import haven.Widget;
import haven.Window;

import java.awt.image.BufferedImage;

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
 * and {@code padding} are plain parsed data, refreshed by {@link #check} once per window per tick — and only a change
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
    private Chrome.Pad padding;
    private Chrome.Spot caption;      // 065.4: where the caption goes, or null for the stock place
    private Chrome.Art sizer;         // 065.4: the sizer's own art and place, or null for the client's
    private Chrome.Close close;       // 065.5: the close button's art and place, or null for the client's
    private Chrome.Close faced;       // ...and the value the button STANDING THERE was built from -- see checkbtn

    /**
     * The blank the themed close button is constructed with. {@code IButton} takes three rasters and sizes itself
     * to the first; this one's faces are {@link Chrome.Art}, painted straight from their textures at the draw, so
     * the raster {@code SIWidget} would cache is never built and one pixel is all the constructor needs.
     */
    private static final BufferedImage BLANK = TexI.mkbuf(Coord.of(1, 1));

    private SkinDeco(boolean lg) {
        super(lg);
    }

    // ---- geometry (035.2) --------------------------------------------------------------------------

    /**
     * Where this window's content starts and how big the frame around it is — the one method that can <b>move</b>
     * the client's own layout, and the reason {@code padding} is a new risk class rather than another paint property.
     *
     * <p><b>The formula is the stock one with the theme's numbers in it</b>, not a formula of its own.
     * {@code DefaultDeco} computes {@code content + margin*2 + tlm + brm}: an inner <i>margin</i> (breathing room
     * between the frame art and the content) and an outer pair of <i>frame insets</i> (the room the art itself
     * needs). A rule replaces exactly the half it owns:
     * <ul>
     *   <li><b>{@code padding} takes the margin's place</b> — it is the breathing room, so it is added to the
     *       stock margin when the stock art is still there, and <i>is</i> the whole margin when a {@code border}
     *       has replaced that art. It says all four sides separately, so the two halves of the sum are two
     *       {@link Coord}s rather than one doubled: a theme that wants its caption clear of the content asks for
     *       height at the top alone.</li>
     *   <li><b>A {@code border}'s slice insets take {@code tlm}/{@code brm}'s place</b>, because they are the same
     *       quantity: the room the frame art needs. This is the geometry twin of D-079 — <i>the margin belongs to
     *       whoever paints the frame</i>. A theme whose caption needs room says so in its own top inset; the
     *       engine adds no hidden minimum, which is what keeps every number here predictable from the rule alone.</li>
     * </ul>
     *
     * <p><b>Every term of this sum is device</b>, which is why the two the rule contributes convert on the way in
     * (058.3): the stock margins and insets are {@code UI.scale}d constants, so a padding or a slice inset left
     * in the design pixels the rule wrote them in would be the one summand meaning something else — and the frame
     * would then reserve less room than the draw paints. {@link Chrome.Border#tlIn} is the very conversion the draw
     * scales each corner by, so the two cannot drift apart, and {@link Chrome.Pad} answers the same pair for the
     * same reason.
     *
     * <p><b>{@code isz} is the CONTENT size.</b> So padding a window grows it <i>outward</i> around fixed content;
     * it never shrinks the content to fit. Everything a rule does not name is stock and inherited —
     * {@code contarea()} answering {@code aa}, the sizer inside {@code ca}, the close button at the top right —
     * so a themed window resizes, drags and closes with exactly the stock code.
     */
    public void iresize(Coord isz) {
        Chrome.Border b = this.border;
        Chrome.Pad pd = this.padding;
        Coord ptl = (pd == null) ? Coord.z : pd.tlIn();            // 058.3: the rule said design px; this sum is device
        Coord pbr = (pd == null) ? Coord.z : pd.brIn();
        Coord ftl, fbr, mtl, mbr;
        if(b == null) {
            ftl = Window.tlm; fbr = Window.brm;                    // the stock art still owns the frame insets
            Coord m = lg ? Window.dlmrgn : Window.dsmrgn;
            mtl = m.add(ptl); mbr = m.add(pbr);                    // ...and the padding simply widens its margin
        } else {
            ftl = b.tlIn(); fbr = b.brIn();                        // our 9-slice owns them instead, at drawn size
            mtl = ptl; mbr = pbr;                                  // ...and the padding IS the whole margin
        }
        Coord csz = isz.add(mtl).add(mbr);
        resize(csz.add(ftl).add(fbr));
        ca = Area.sized(ftl, csz);
        aa = Area.sized(ca.ul.add(mtl), isz);
        placebtn();                                                // 065.5: the rule's corner, or the stock one
    }

    /**
     * Re-run the geometry in place after a rule changed the insets or the padding, <b>keeping the content where it
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

    // ---- the placed ornaments (065.4) --------------------------------------------------------------

    /**
     * Where the caption is drawn: the rule's {@link Chrome.Spot} over this deco's own box, or — with no
     * {@code caption} property — the very {@code Window.cpo} the stock client uses, to the pixel.
     *
     * <p>The spot is resolved against the <b>caption's own size</b>, so {@code at = "topright"} is the caption's
     * right edge at the frame's right edge rather than its origin there. That is what makes the nine names mean
     * the same thing here as they do on a picture, which has a size of its own for the same reason.
     */
    public Coord capc() {
        Chrome.Spot sp = this.caption;
        if(sp == null)
            return super.capc();
        return sp.place(sz, (cap == null) ? Coord.z : cap.sz());
    }

    /** Where the sizer is drawn — the rule's art at the rule's spot, else the client's own at its own place. */
    public Coord sizerc() {
        Chrome.Art a = this.sizer;
        Area at = (a == null) ? null : a.place(Coord.z, sz);
        return (at == null) ? super.sizerc() : at.ul;
    }

    /** ...and the picture it is drawn with, which is the half a spot cannot say. */
    protected void drawsizer(GOut g) {
        Chrome.Art a = this.sizer;
        if(a == null)
            super.drawsizer(g);
        else
            a.draw(g, Coord.z, sz);
    }

    // ---- the close button (065.5) ------------------------------------------------------------------

    /**
     * Where the close button sits: the rule's {@link Chrome.Spot} over this deco's box, or — with no place of its
     * own — pinned to the top right exactly as {@code DefaultDeco.iresize} pins it.
     *
     * <p>It is called from {@link #iresize}, so a window that is dragged out to a new size finds its button at
     * the same corner rather than at the pixel it happened to be at; and from {@link #checkbtn}, because a
     * rebuilt button has a size of its own and the corner is measured against it.
     */
    private void placebtn() {
        Chrome.Close cl = this.close;
        Chrome.Spot sp = (cl == null) ? null : cl.at;
        cbtn.c = (sp == null) ? Coord.of(sz.x - cbtn.sz.x, 0) : sp.place(sz, cbtn.sz);
    }

    /**
     * The button a theme's own art needs, <b>built</b> — an {@code IButton} whose faces are {@link Chrome.Art}
     * rather than rasters, and whose box is the art's own drawn size ({@link Chrome.Close#size}). A face that is
     * a flat colour has no size to give, so the client's own box stands and the colour fills it.
     *
     * <p><b>It is the client's button in every other respect</b>: the same class, the same input path, and
     * {@code mkcbtn}'s own action — the X still runs {@code Window.reqclose()}, which is what a window's own
     * close handler is hung on. Anonymous on purpose, so the widget's reported type stays {@code IButton}: what
     * an addon sees is one of the client's buttons wearing a theme, not a class of this bridge's.
     *
     * <p>The hit test is the box rather than the art's alpha. {@code IButton}'s own samples the {@code up}
     * raster, and this button has none to sample; a themed button therefore takes a click anywhere in the
     * rectangle its art was drawn at.
     */
    private IButton mkbtn(final Chrome.Close cl) {
        IButton b = new IButton(BLANK, BLANK, BLANK, (Runnable)null) {
                public void draw(GOut g) {
                    cl.face(a, h).draw(g, Coord.z, sz);
                }

                public boolean checkhit(Coord c) {
                    return c.isect(Coord.z, sz);
                }
            };
        b.action(() -> ((Window)parent).reqclose());
        Coord bsz = cl.size();
        b.resize((bsz == null) ? cbtn.sz : bsz);
        return b;
    }

    /**
     * Put the right button there, and put it in the right place. <b>Only a changed FACE costs a rebuild</b> —
     * {@code IButton}'s three are {@code final}, so re-facing one in place is impossible and {@code chcbtn}
     * destroys what it displaces, the same discipline {@code chdeco} has one level up. A changed spot moves the
     * button that is already there, and so a theme may tune where the X sits without the button under the
     * pointer being swapped out from under it.
     */
    private void checkbtn() {
        Chrome.Close cl = this.close;
        if((cl != null) && (cl.up != null)) {
            if((faced == null) || !cl.sameFaces(faced)) {
                chcbtn(mkbtn(cl));
                faced = cl;
            }
        } else if(faced != null) {
            chcbtn(mkcbtn());                 // the client's own button, built exactly where the client builds it
            faced = null;
        }
        placebtn();
    }

    /**
     * The rule's 9-slice frame, or the stock one when the rule names none. A skinned frame keeps the sizer and
     * the caption — the caption through {@code checkcap()}, so it is still the {@code "window.title"} font, and
     * at {@link #capc()}, so a {@code caption} rule reaches it either way — and it keeps the caption
     * <b>plate</b>, which is {@code window.title}'s own {@code bg}/{@code border} at the box the client sized.
     * With no plate rule the border image owns the frame art, plate included, exactly as the stock frame's does.
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
            drawsizer(g);
        drawplate(g);
        if(cap != null)
            g.image(cap.tex(), capc());
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
        Chrome.Bg bg = Chrome.bg(st);
        Chrome.Border bd = Chrome.border(st);
        Chrome.Pad pd = Chrome.padding(st);
        Chrome.Spot cp = Chrome.caption(st);                // 065.4: the two ornaments this deco PLACES...
        Chrome.Art szr = Chrome.sizer(st);                  //   ...neither of which moves the window's content
        Chrome.Close cl = Chrome.close(st);                 // 065.5: ...and the one it BUILDS, which moves none either
        // A padding of zero on every side says the same thing as no padding at all, so it alone never dresses a
        // window: the deco would then draw stock pixels at stock coordinates, and installing one for that is a
        // swap nobody asked for.
        if((pd != null) && pd.zero())
            pd = null;
        boolean want = (bg != null) || (bd != null) || (pd != null) || (cp != null) || (szr != null)
            || (cl != null);
        if(have) {
            SkinDeco sd = (SkinDeco)d;
            if(want) {
                // Only these two decide the geometry. Compared BY VALUE: re-applying the same sheet parses a
                // fresh Border, and a repack per tick would be a real cost for a rule that did not change.
                boolean moved = ((sd.padding == null) ? (pd != null) : !sd.padding.equals(pd))
                    || ((sd.border == null) ? (bd != null) : !sd.border.equals(bd));
                sd.bg = bg;                                 // a changed rule repaints; the deco itself stays put
                sd.border = bd;
                sd.padding = pd;
                sd.caption = cp;
                sd.sizer = szr;
                sd.close = cl;
                sd.checkbtn();                              // a changed FACE rebuilds the button; a changed spot moves it
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
            sd.padding = pd;                                // BEFORE the swap: chdeco lays the window out with it
            sd.caption = cp;
            sd.sizer = szr;
            sd.close = cl;
            wnd.chdeco(sd);
            sd.checkbtn();                                  // ...and AFTER it: a button is destroyed and re-added
        }
    }
}
