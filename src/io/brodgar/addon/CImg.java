package io.brodgar.addon;

import haven.Coord;
import haven.Fonts;
import haven.GOut;
import haven.Img;
import haven.Tex;
import haven.TexI;

import org.luaj.vm2.LuaValue;

/**
 * The adapter behind {@code hafen.ui():image()} — a real {@link Img}, the client's own static picture widget
 * (spec {@code 040-ui-controls}, task 040.3): its content is {@code :source(h)} (decision E — the builder that
 * completed a face on a button is {@code hafen.ui():button():image(...)}, so the picture widget's OWN content
 * setter is a different name to avoid an {@code image():image(h)} that reads like a mistake).
 *
 * <p><b>Unlike a button's face, a picture's source is NOT building-only.</b> {@link Img#setimg} is a public,
 * live, post-construction setter — nothing about it is {@code final} the way {@link haven.IButton}'s faces are
 * — so {@code :source(h)} works at any time, before or after arming, with no rebuild and no D-113 pending
 * check. That is also why the constructor needs a placeholder: {@code Img}'s only constructor takes the
 * {@link haven.Tex} it shows, so a bare builder (R4) starts the control on one pixel of nothing until
 * {@code :source(h)} names the real picture — invisible in practice, since {@link #draw} skips the paint
 * entirely while the control is still {@link Owned#pending() pending}, and the ordinary chain
 * ({@code hafen.ui():image():source(h)}) never lets that placeholder be seen at all.
 *
 * <p>{@link CtlButton}'s shape otherwise: the ownership contract over one {@link Owned.State}, and the one
 * override every adapter owes the engine — {@link #draw(GOut)} skips the paint while pending.
 *
 * <p><b>The box is the picture's own until {@code :size(w, h)} writes one, and then the picture is drawn to the
 * box</b> (139.4). {@code Img.draw} paints its texture at the texture's own size, so a client resource drawn
 * large — a skill icon — stayed large in a row whatever box the addon gave the control, and the box only
 * clipped it. Here {@link #draw} paints it scaled to {@code sz} ({@code GOut.image(tex, c, sz)}), which is
 * identical while the box is the picture's own and is the icon's size once it is not. A {@code picture} rule
 * that names the control still fills the box by its own {@code mode}, exactly as {@code Img.draw} lets it.
 * The written box is {@link #pinned} and survives a later {@code :source(h)}, where {@link Img#setimg} would
 * have put the new picture's own box back; {@code :size(nil)} is {@link #unpin}, the box the picture's again.
 */
final class CImg extends Img implements Owned.Control, Controls.Source {
    private final Owned.State own;
    /** Exactly what {@code :source(h)} was given (a handle, or a resource name) — what the bare read hands back. */
    private volatile LuaValue sourcev;
    /** The box {@code :size(w, h)} wrote, in device pixels, or {@code null} while the box is the picture's own. */
    private Coord pinned;
    /** Inside {@link #setimg}: the resize it makes is the picture's own size, not a pin. */
    private boolean sourcing;

    CImg(Addon owner) {
        super(new TexI(TexI.mkbuf(Coord.of(1, 1))));
        this.own = new Owned.State(owner, this);
    }

    public Owned.State own() {
        return own;
    }

    /** {@code i:source()} — the handle or resource name last given, or {@code nil} before the first one. */
    public LuaValue source() {
        return sourcev;
    }

    /** Bridge-only field write; {@link Controls#source} resolves {@code h} and installs the texture first. */
    public void source(LuaValue h) {
        this.sourcev = h;
    }

    /** A new picture takes its own box — unless {@code :size(w, h)} pinned one, which it keeps. */
    public void setimg(Tex img) {
        sourcing = true;
        try {
            super.setimg(img);
        } finally {
            sourcing = false;
        }
        if(pinned != null)
            resize(pinned);
    }

    /** Every resize but {@link #setimg}'s own is {@code :size(w, h)}'s, and pins the box. */
    public void resize(Coord sz) {
        super.resize(sz);
        if(!sourcing)
            pinned = sz;
    }

    /** {@code :size(nil)}: the box is the picture's own again. */
    void unpin() {
        pinned = null;
        Tex t = img();
        if(t != null) {
            sourcing = true;
            try {
                resize(t.sz());
            } finally {
                sourcing = false;
            }
        }
    }

    public void draw(GOut g) {
        if(own.pending())   // built this statement and not armed yet: a half-configured control paints NOTHING
            return;
        g = Owned.dim(this, g);   // 139.3: disabled? the whole control paints dimmed
        // Img.draw's two branches, with the picture drawn to the BOX rather than at its own size (139.4). The
        // scope is null: a picture an addon built is no site of the client's, so what names it is a tree rule
        // or its widget:rule(), which is what Img asks for one the server placed.
        Fonts.Picture p = Fonts.picture(null, this);
        if(p != null)
            p.draw(g, Coord.z, sz);
        else
            g.image(img(), Coord.z, sz);
    }
}
