package io.brodgar.addon;

import haven.GOut;
import haven.Label;

/**
 * The adapter behind {@code hafen.ui():label()} — a real {@link Label}, the client's own live-restyling text
 * widget (spec {@code 040-ui-controls}, task 040.3): its caption is {@code :text(s)}, which is R2 completing a
 * verb that already reads on any text-bearing widget rather than a new name.
 *
 * <p><b>{@code ILabel} is NOT this builder's second class.</b> The plan expected {@code :image(h)} to complete
 * the builder as an {@link haven.ILabel} the way {@code :image(up, down, hover)} completes {@code :button()}
 * as an {@link haven.IButton} — but {@code ILabel} carries no picture at all: its constructor is
 * {@code ILabel(String text, Text.Furnace f)}, a label whose {@code Furnace} is baked once and never
 * live-restyled, which is exactly the opposite of what "a control the stylesheet can dress" needs (a
 * {@code Label} re-renders when a font override on its scope moves; an {@code ILabel} never does). Corrected
 * with the maintainer during 040.3: {@code hafen.ui():label()} builds a plain {@link Label} only, and
 * {@code :image()} keeps meaning what it means everywhere else — a button/checkbox face, refused here naming
 * that this control has none.
 *
 * <p>Otherwise {@link CtlButton}'s shape verbatim: the ownership contract over one {@link Owned.State}, and the
 * one override every adapter owes the engine — {@link #draw(GOut)} skips the paint while
 * {@link Owned#pending() pending}, so a label configured across several lines is never drawn half-built. No
 * {@code resize()} override is needed: unlike {@link haven.SIWidget}, a {@code Label} caches nothing keyed on
 * its size — {@link Label#settext} already calls {@code resize()} itself.
 */
final class CLabel extends Label implements Owned.Control {
    private final Owned.State own;

    CLabel(Addon owner) {
        super("");
        this.own = new Owned.State(owner, this);
    }

    public Owned.State own() {
        return own;
    }

    public void draw(GOut g) {
        if(own.pending())   // built this statement and not armed yet: a half-configured control paints NOTHING
            return;
        super.draw(Owned.dim(this, g));   // 139.3: disabled? the whole control paints dimmed
    }
}
