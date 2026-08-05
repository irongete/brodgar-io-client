package io.brodgar.addon;

import haven.GOut;
import haven.HRuler;

/**
 * The adapter behind {@code hafen.ui():separator()} — a real {@link HRuler}, the client's own horizontal rule
 * (spec {@code 040-ui-controls}, task 040.3). It has no verb of its own: a separator holds nothing, so
 * {@code :value()} reads {@code nil} on it exactly as it does on a {@link CLabel} or a {@link CImg}, and
 * {@code :text}/{@code :image}/{@code :source} all refuse naming the builder that answers instead. The plain
 * word ({@code separator}, not {@code ruler}) is spec 040 decision F.
 *
 * <p>{@link CtlButton}'s shape verbatim: the ownership contract over one {@link Owned.State}, and the one
 * override every adapter owes the engine — {@link #draw(GOut)} skips the paint while
 * {@link Owned#pending() pending}.
 */
final class CSeparator extends HRuler implements Owned.Control {
    /** The client's own default width; {@code :size(w, h)} overrides it (the height stays the rule's own). */
    static final int DEF_W = 100;

    private final Owned.State own;

    CSeparator(Addon owner) {
        super(DEF_W);
        this.own = new Owned.State(owner, this);
    }

    public Owned.State own() {
        return own;
    }

    public void draw(GOut g) {
        if(own.pending())   // built this statement and not armed yet: a half-configured control paints NOTHING
            return;
        super.draw(g);
    }
}
