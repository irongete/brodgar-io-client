package io.brodgar.addon;

import haven.GAttrib;
import haven.Gob;
import haven.render.Location;
import haven.render.Pipe;

/**
 * <b>How big a game object is drawn</b> — the size an addon asked for on a gob the <i>game</i> owns (spec
 * {@code 046-gob-scale}, task 046.1). It is a {@link GAttrib} that is also a {@link Gob.SetupMod}, which is
 * the engine's own shape for "per-gob state that changes how the gob is drawn": {@code haven.GobHealth} is
 * the same three lines with a crack texture where this has a scaling {@link Location}.
 *
 * <p><b>Client-local and purely visual.</b> Nothing here goes on the wire and nothing here changes what the
 * gob <i>is</i> — its hitbox on the server, what it collides with, what a click sends. It is the same footing
 * {@code gob:overlay()} stands on, which is why it is unprotected like that one.
 *
 * <p><b>The op is CACHED, and the cache is the mechanism rather than an optimisation.</b> {@code Gob.ctick}
 * rebuilds a {@code GobState} every tick and pushes it to the render slots only when {@code Utils.eq} says it
 * differs from the last one; {@code Location} does not override {@code equals}, so a fresh
 * {@code Location.scale(k)} on every tick would compare unequal on every tick and re-push {@code slot.ostate}
 * for every scaled gob, forever. Minting it once per <i>value</i> makes a scaled gob cost exactly what an
 * unscaled one costs, and makes a change land on the next tick with no new seam anywhere in {@code haven} —
 * this feature edits no engine file at all.
 *
 * <p><b>Where it composes: T·R·S.</b> {@code gobstate()} is the gob's own child render slot, under
 * {@code Placed}'s {@code "gobx"} translate and {@code "gob"} rotate, and {@code Location} composes
 * multiplicatively down the tree — so the model is scaled <b>in place</b> around the gob's own origin (its
 * feet), then rotated, then translated. That is the same level and the same math
 * {@code GhostGob.obstate} already uses for a client-only ghost, which is the whole argument for a native gob
 * and a virtual entity carrying one verb. (The same caveat carries over too: a {@code goback("gobx")}
 * resource re-establishes its own transform and ignores both facing and scale.)
 *
 * <p><b>One size, last write wins.</b> A gob has one size, so this records the {@link Addon} that last wrote
 * it rather than layering two addons' factors or refusing the second. The owner is what
 * {@link UiApi#teardownGobScales} reads: when an addon goes away ({@code :reload} or disable) every gob it
 * left distorted goes back to its original size.
 *
 * <p><b>It ends with the loaded object, by directive.</b> The state lives on the {@link Gob}, and a gob that
 * unloads and comes back is a <i>new</i> {@code Gob} — so walking out of range and back gives you the
 * original size. That is the contract, not a defect: an addon that wants it back re-applies on
 * {@code GobAdded}, a subscription it already has.
 */
public final class GobScale extends GAttrib implements Gob.SetupMod {
    /** The original size, and the value a gob nobody scaled reads back. */
    static final float NONE = 1f;

    /** The factor last written. Volatile: written from a Lua verb, read from {@code ctick}'s state rebuild. */
    private volatile float scale = NONE;

    /** The cached {@code Location.scale(scale)} — one instance per value, {@code null} at {@link #NONE}. */
    private volatile Pipe.Op op = null;

    /** The addon that last wrote the size, so teardown knows whose distortion to undo. */
    private volatile Addon owner;

    private GobScale(Gob gob, Addon owner) {
        super(gob);
        this.owner = owner;
    }

    /** The engine asks every tick; a cached instance is what keeps that from re-pushing the render state. */
    public Pipe.Op gobstate() {
        return op;
    }

    /** Point this attrib at a new factor, minting the op only because the number actually changed. */
    private void set(Addon owner, float k) {
        this.owner = owner;
        if(k != this.scale) {
            this.op = (k == NONE) ? null : Location.scale(k);
            this.scale = k;
        }
    }

    // ---- the per-gob store ------------------------------------------------------------------------

    /** The scale attrib on {@code g}, or {@code null} — nothing is created and nothing is scaled. */
    static GobScale on(Gob g) {
        return (g == null) ? null : g.getattr(GobScale.class);
    }

    /** How big {@code g} is drawn: what the last write set, or {@link #NONE} for a gob nobody scaled. */
    static float value(Gob g) {
        GobScale s = on(g);
        return (s == null) ? NONE : s.scale;
    }

    /**
     * Draw {@code g} at {@code k} times its size, on {@code owner}'s account. Writing exactly {@code 1}
     * removes the attrib outright rather than leaving one behind that means "nothing" — the original size is
     * the absence of this state, so putting a gob back is putting it back completely.
     *
     * <p>Called on the UI thread (a Lua verb is marshalled onto the tick), where {@code ctick} runs too.
     */
    static void apply(Gob g, Addon owner, float k) {
        GobScale s = on(g);
        if(k == NONE) {
            if(s != null)
                g.delattr(GobScale.class);
            return;
        }
        if(s == null) {
            s = new GobScale(g, owner);
            s.set(owner, k);
            g.setattr(s);           // cannot throw Loading: this attrib is not a RenderTree.Node
        } else {
            s.set(owner, k);
        }
    }

    /**
     * Undo {@code a}'s scale on {@code g} if {@code a} is who set it, and answer whether anything was undone.
     * The teardown sweep's per-gob half ({@link UiApi#teardownGobScales}).
     */
    static boolean revert(Gob g, Addon a) {
        GobScale s = on(g);
        if((s == null) || (s.owner != a))
            return false;
        g.delattr(GobScale.class);
        return true;
    }
}
