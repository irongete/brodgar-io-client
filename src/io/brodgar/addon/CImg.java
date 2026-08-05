package io.brodgar.addon;

import haven.Coord;
import haven.GOut;
import haven.Img;
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
 * override every adapter owes the engine — {@link #draw(GOut)} skips the paint while pending. No
 * {@code resize()} override: {@link Img#setimg} already calls {@code resize(img.sz())} itself, and {@code Img}
 * caches nothing keyed on the box the way an {@link haven.SIWidget} does.
 */
final class CImg extends Img implements Owned.Control, Controls.Source {
    private final Owned.State own;
    /** Exactly what {@code :source(h)} was given (a handle, or a resource name) — what the bare read hands back. */
    private volatile LuaValue sourcev;

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

    public void draw(GOut g) {
        if(own.pending())   // built this statement and not armed yet: a half-configured control paints NOTHING
            return;
        super.draw(g);
    }
}
