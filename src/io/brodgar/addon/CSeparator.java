package io.brodgar.addon;

import haven.Coord;
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
    /** A default DESIGN width; {@code :size(w, h)} overrides it (the height stays the rule's own). */
    static final int DEF_W = 100;

    private final Owned.State own;

    CSeparator(Addon owner) {
        super(Px.in(DEF_W));
        this.own = new Owned.State(owner, this);
    }

    public Owned.State own() {
        return own;
    }

    /**
     * <b>The rule, and the air above and below it</b> (058.4) — {@link HRuler}'s own constructor formula,
     * {@code (marg.y * 2) + 1}, which is where its line is drawn. A shorter box clips the line out of the
     * widget altogether, so the separator disappears rather than merely looking thin. Any width: a rule is a
     * line, and it is drawn to whatever box it is given.
     */
    public Coord minsz() {
        return Coord.of(0, (marg.y * 2) + 1);
    }

    public void draw(GOut g) {
        if(own.pending())   // built this statement and not armed yet: a half-configured control paints NOTHING
            return;
        super.draw(g);
    }
}
