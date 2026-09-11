package io.brodgar.addon;

import haven.Coord;
import haven.GOut;
import haven.Widget;

import java.awt.Color;

/**
 * <b>Provenance as a CONTRACT, not a class</b> (spec {@code 040-ui-controls}, task 040.1) — the three questions
 * {@link LuaWidget#ownedContent} has always asked of a widget, extracted from the one class that could answer
 * them so that a second kind of owned widget can answer them too.
 *
 * <p>029.2 decided <i>is this widget mine?</i> is <b>derived from the tree and never stored</b>, because the
 * Widget entity's intern cache is weak on both axes and a flag on a handle would silently be lost. That
 * reasoning stands; what this interface widens is the <b>type</b> the test is written against. Against
 * {@link AddonWidget} — the addon's own painted surface — a {@code haven.Button} an addon builds is not
 * one, so every control this feature ships would read as <b>borrowed</b> and {@code :destroy()}, the
 * builder setters and the whole owned half would refuse on the addon's own button. The test is
 * {@code instanceof Owned}: the same test, against a wider type, in one file.
 *
 * <p><b>Eight methods, and each is a thing the bridge already does to an owned widget.</b> The first three are
 * the provenance triple {@code isOwn} asks for; {@link #kill()} is what teardown does to every entry of
 * {@link Addon#widgets} (which is why the registry can be a list of this type at all); {@link #pending()} /
 * {@link #armed()} are the arming rule of D-112/D-119 (<i>attached inert, completed on the tick after the
 * statement that built it</i>), which a control obeys exactly as a surface does; {@link #widget()} is the
 * one place that says the obvious — <b>an owned thing IS a widget</b> — so the geometry writes need no cast;
 * and {@link #minsz()} (058.4) is the box the thing's own art needs, which is the one fact about a control an
 * addon has no way to find out and used to measure by hand.
 *
 * <p>{@link AddonWidget} implements it with the methods it already had. Every control adapter implements
 * {@link Control}, which is the same contract over one shared {@link State} object, so an adapter's whole
 * ownership boilerplate is a field and a getter.
 *
 * <p><b>And a widget you built can be disabled</b> (spec {@code 139-a-column-lays-its-rows-out}, task 139.3):
 * {@link #enabled()} is the widget's OWN flag, {@code true} from birth, and {@link #enabled(boolean)} writes it.
 * What a disabled widget IS is decided in three places, each reading the <b>effective</b> state — the flag,
 * and every {@code Owned} ancestor's ({@link #effective}): the input cut in {@code Widget.handle} ({@link #cuts},
 * one tagged line, where a press is swallowed and a move and a key pass by), the dim at the draw
 * ({@link #dim}, a tinted {@code GOut} the topmost disabled widget paints its whole subtree through), and the
 * {@code disabled} face a button and a checkbox wear ({@link CtlButton}, {@link CCheck}). A borrowed ancestor
 * never disables: the client's own widgets carry no flag, so a control of yours inside one of its windows is
 * greyed by you alone.
 */
interface Owned {
    /** The tint a disabled widget's subtree is drawn through: lighter grey, and two-fifths see-through. */
    Color DIM = new Color(190, 190, 190, 150);

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
     * <b>The smallest box this widget's art fits in</b> (058.4), in <b>DEVICE</b> pixels — the engine's own
     * numbers, like {@link LuaWidget.Moved#pos}, converted at the Lua edge by {@link Px} and nowhere below it.
     * {@code null} means <i>there is no art to ask</i>: an addon's own painted {@link AddonWidget} surface has
     * none, and neither has a control the client draws to whatever box it is given.
     *
     * <p>It answers the one question about a control an addon <b>cannot</b> answer for itself — a button is as
     * tall as its bottom border says it is — and that is what {@code widget:size(w)} is built on: the width is
     * the addon's, the height is this. A {@code widget:size(w, h)} under it <b>raises</b>, because a box the art
     * will not fit in draws a button with no bottom edge and teaches nobody why.
     *
     * <p><b>{@code 0} in an axis means the art does not constrain that axis</b>, and is not the same as
     * {@code null}: a slider is exactly as tall as its knob and as wide as you like, so it answers
     * {@code (0, knob)} — it has a height to give {@code :size(w)}, and no width to refuse.
     *
     * <p>Answered from the art at the moment it is asked, never cached: a checkbox's box grows with its
     * caption, and the number an addon reads must be the one in force.
     */
    default Coord minsz() {
        return null;
    }

    /** This widget's OWN flag (139.3) — {@code true} from birth; a child of a disabled column still reads {@code true}. */
    boolean enabled();

    /**
     * Write the flag (139.3). Bridge-only, and only through {@link #enable}, which does the focus work beside it:
     * the implementations here keep the flag and their own {@code canfocus} record and nothing else.
     */
    void enabled(boolean b);

    // ---------------------------------------------------------------- disabled (139.3): the three readers

    /**
     * The {@link Owned} a widget IS, whoever built it — {@code w} itself, or the content a window's chrome
     * directly holds (the shape {@link LuaWidget#ownedContent} matches, minus the owner test). {@code null} for
     * one of the client's own, which carries no flag and reads as enabled.
     */
    static Owned of(Widget w) {
        if(w == null)
            return null;
        if(w instanceof Owned)
            return (Owned)w;
        for(Widget c = w.child; c != null; c = c.next) {
            if((c instanceof Owned) && (((Owned)c).rootw() == w))
                return (Owned)c;
        }
        return null;
    }

    /**
     * <b>The effective state</b>: is {@code w} enabled, itself and through every {@code Owned} above it? A
     * plain parent walk — {@code w} is asked as {@link #of} resolves it, so a window's chrome answers for its
     * content, and each ancestor is asked as itself. A borrowed ancestor is skipped: the client's own widgets
     * never disable what an addon built inside them.
     */
    static boolean effective(Widget w) {
        Owned o = of(w);
        if((o != null) && !o.enabled())
            return false;
        for(Widget p = (w == null) ? null : w.parent; p != null; p = p.parent) {
            if((p instanceof Owned) && !((Owned)p).enabled())
                return false;
        }
        return true;
    }

    /**
     * <b>The input cut</b>, asked first thing in {@code Widget.handle(Event)} through
     * {@code haven.AddonWidgets.disabled}: is {@code ev} an input a disabled widget takes no part in, and is
     * {@code w} disabled? The caller then swallows a press, a release, a wheel turn, a hover and a drop
     * (returns {@code true}: no listener, no {@code mousedown}, no child walk, nothing beneath), and lets a
     * move and a key pass by (returns {@code false}: the widget's own method never runs and the walk goes on,
     * so a disabled column never eats every move on screen nor every hotkey). A tick, a tooltip query and a
     * cursor query are not asked at all — the type test is first, so on a stock client this is two
     * {@code instanceof} checks per event and never a walk.
     */
    static boolean cuts(Widget w, Widget.Event ev) {
        if(!(ev instanceof Widget.MouseEvent) && !(ev instanceof Widget.KbdEvent))
            return false;
        return !effective(w);
    }

    /**
     * <b>The dim.</b> The {@code GOut} a widget paints through: {@code g} itself while it is enabled, or while
     * an ancestor already disabled it (the tint descends with the {@code GOut}); {@code g} tinted with
     * {@link #DIM} where this widget is the TOPMOST disabled one — its own flag off, its parent's effective
     * state on. {@code GOut.tinted} bakes the tint into the view's DEFAULT state, which is the one thing a
     * child's {@code reclip} copies (a plain {@code chcolor} on the parent's {@code GOut} would reach no child),
     * so one call here dims the whole subtree: every control, every bare surface, and every colour a
     * {@code Draw} handler names.
     */
    static GOut dim(Owned c, GOut g) {
        if(c.enabled())
            return g;
        Widget w = c.widget();
        return effective(w.parent) ? g.tinted(DIM) : g;
    }

    /**
     * <b>The write</b> behind {@code widget:enabled(b)}: the flag, and the keyboard beside it. A disabled widget
     * cannot be focused ({@code setcanfocus(false)}, which also moves the focus off anything INSIDE it through
     * {@code delfocusable}), and one enabled again is focusable exactly where it was. Caller holds the widget's
     * tree monitor. Idempotent: writing the state a widget is in does nothing at all.
     */
    static void enable(Owned c, boolean b) {
        if(c.enabled() == b)
            return;
        c.enabled(b);
        WidgetSurface.touch(c.widget());   // 044.1: standing in the world, its picture is out of date
    }

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
        /** {@code widget:enabled(b)} (139.3) — the control's own flag; the draw and the input cut read it off-thread. */
        private volatile boolean enabled = true;
        /** What {@code canfocus} was when the control was disabled, put back when it is enabled again. */
        private boolean canfocus;
        /**
         * {@code widget:bind(opt)} (140.3) — the option this control is joined to, {@code null} while none. The
         * control's end of the record ({@link Binding}); the option's is {@link LuaOption#bound}. Volatile:
         * written from Lua, read by the push on the input pass and by the pull on whatever thread wrote the
         * option.
         */
        volatile LuaOption bound;

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
            Binding.drop(this);   // 140.3: a binding ends with the control
            root.destroy();
        }

        boolean enabled() {
            return enabled;
        }

        /** The flag, and the keyboard with it — see {@link Owned#enable}. */
        void enabled(boolean b) {
            enabled = b;
            if(b) {
                if(canfocus)
                    self.setcanfocus(true);
            } else {
                canfocus = self.canfocus;
                self.setcanfocus(false);   // ...and delfocusable moves the focus off whatever is inside it
            }
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

        default boolean enabled() {
            return own().enabled();
        }

        default void enabled(boolean b) {
            own().enabled(b);
        }
    }
}
