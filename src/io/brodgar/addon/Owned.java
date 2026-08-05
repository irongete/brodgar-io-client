package io.brodgar.addon;

import haven.Widget;

/**
 * <b>Provenance as a CONTRACT, not a class</b> (spec {@code 040-ui-controls}, task 040.1) — the three questions
 * {@link LuaWidget#ownedContent} has always asked of a widget, extracted from the one class that could answer
 * them so that a second kind of owned widget can answer them too.
 *
 * <p>029.2 decided <i>is this widget mine?</i> is <b>derived from the tree and never stored</b>, because the
 * Widget entity's intern cache is weak on both axes and a flag on a handle would silently be lost. That
 * reasoning is untouched here; what changes is the <b>type</b> the test is written against. It used to be
 * {@link AddonWidget} — the addon's own painted surface — and a {@code haven.Button} an addon builds is not
 * one, so under the old test every control this feature ships would have read as <b>borrowed</b> and
 * {@code :destroy()}, the builder setters and the whole owned half would have refused on the addon's own
 * button. The test is now {@code instanceof Owned}: the same test, against a wider type, in one file.
 *
 * <p><b>Seven methods, and each is a thing the bridge already does to an owned widget.</b> The first three are
 * the provenance triple {@code isOwn} asks for; {@link #kill()} is what teardown does to every entry of
 * {@link Addon#widgets} (which is why the registry can be a list of this type at all); {@link #pending()} /
 * {@link #armed()} are the arming rule of D-112/D-119 (<i>attached inert, completed on the tick after the
 * statement that built it</i>), which a control obeys exactly as a surface does; and {@link #widget()} is the
 * one place that says the obvious — <b>an owned thing IS a widget</b> — so the geometry writes need no cast.
 *
 * <p>{@link AddonWidget} implements it with the methods it already had. Every control adapter implements
 * {@link Control}, which is the same contract over one shared {@link State} object, so an adapter's whole
 * ownership boilerplate is a field and a getter.
 */
interface Owned {
    /** The addon that built this widget — the owner half of the provenance test, and the profiling attribution. */
    Addon profOwner();

    /**
     * The top-level widget this content lives under: a window's chrome, or the widget itself. It is the widget
     * the Lua entity is interned on (029.2), so {@code w} is owned exactly when {@code w} is (or directly
     * contains) this addon's content whose root is {@code w}.
     */
    Widget rootw();

    /** Already torn down? (A killed widget owns nothing any more, and takes no further callback.) */
    boolean dead();

    /** Still waiting for its arming tick — built and in the tree, but painting nothing (D-112/D-119). */
    boolean pending();

    /** Cleared by {@link UiApi#armPending()} on the first tick after the statement that built it. */
    void armed();

    /** Mark torn-down and remove this widget's root from the tree. Bridge-only (teardown, {@code :destroy()}). */
    void kill();

    /** The widget itself — the content leaf, which for a control is the control. */
    Widget widget();

    /**
     * The ownership state one control adapter carries. It exists because the adapters cannot share a base class
     * (each extends the {@code haven} control it wraps), so what they share is an <b>object</b> instead: the
     * owner, the widget itself, its root, and the two lifecycle flags. Everything else about an adapter is the
     * one Lua callback the engine wants as an override.
     */
    final class State {
        /** The addon that built the control. */
        final Addon owner;
        /** The control widget itself — {@code this} at the adapter's construction. */
        final Widget self;
        private Widget root;
        private boolean dead;
        /** Volatile: the draw pass reads it while the arming tick writes it, exactly as {@code AddonWidget} does. */
        private volatile boolean pending = true;

        State(Addon owner, Widget self) {
            this.owner = owner;
            this.self = self;
            this.root = self;
        }

        Widget root() {
            return root;
        }

        /** Record an enclosing widget as the root (a control that is really a small tree of its own). */
        void root(Widget r) {
            this.root = (r != null) ? r : self;
        }

        boolean dead() {
            return dead;
        }

        boolean pending() {
            return pending;
        }

        void armed() {
            this.pending = false;
        }

        void kill() {
            if(dead)
                return;
            dead = true;
            pending = false;
            root.destroy();
        }
    }

    /**
     * The mixin every control adapter implements: {@link Owned} over one {@link State}. An adapter therefore
     * carries exactly two lines of ownership boilerplate — the field and {@link #own()} — and spends the rest of
     * itself on the control it actually is.
     */
    interface Control extends Owned {
        /** This control's ownership state. The one method an adapter has to write. */
        State own();

        default Addon profOwner() {
            return own().owner;
        }

        default Widget rootw() {
            return own().root();
        }

        default boolean dead() {
            return own().dead();
        }

        default boolean pending() {
            return own().pending();
        }

        default void armed() {
            own().armed();
        }

        default void kill() {
            own().kill();
        }

        default Widget widget() {
            return own().self;
        }
    }
}
