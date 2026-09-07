package io.brodgar.addon;

import haven.GAttrib;
import haven.Gob;
import haven.FColor;
import haven.render.Pipe;
import haven.render.State;
import haven.render.FragColor;
import haven.render.sl.MiscLib;
import haven.render.sl.ShaderMacro;
import haven.render.sl.Uniform;

import java.awt.Color;

import static haven.render.sl.Type.VEC4;

/**
 * <b>A colour laid over a game object</b> — the tint an addon asked for on a gob the <i>game</i> owns (spec
 * {@code 135-gob-tint}, task 135.1). Cut from {@link GobScale}'s mould: a {@link GAttrib} that is also a
 * {@link Gob.SetupMod}, contributing one cached {@link Pipe.Op} to the gob's own render slot, so no engine
 * file is edited and a change lands on the next {@code ctick}.
 *
 * <p><b>The op is a {@code State} of its own, not a {@code MixColor}.</b> A {@code State.Slot} holds ONE
 * state, and {@code haven.render.MixColor}'s slot is the one {@code haven.GobHealth} fills with the red damage
 * wash — a second {@code MixColor} on the gob would REPLACE it, by arrival order, and a damaged box would lose
 * either its cracks' red or the addon's colour. {@link Wash} is {@code haven.ColorMask}'s shape with the same
 * {@code colblend} at fragment order <b>50</b>: the damage wash (order 0) is applied first, this colour over
 * it, and {@code ColorMask} (100) over both. Same blend function as {@code e:tint} and the damage wash, so the
 * colour's alpha means the same strength everywhere: {@code a = 255} is a flat fill with the lit shading gone.
 *
 * <p><b>Cached once per value</b>, for {@link GobScale}'s reason exactly: {@code Gob.ctick} rebuilds a
 * {@code GobState} every tick and pushes it only when {@code Utils.eq} says it differs, {@link Wash} has no
 * {@code equals}, so a fresh instance per tick would re-push {@code slot.ostate} every tick for every tinted
 * gob. One instance per colour, {@code Color.equals} deciding — writing the same colour twice mints nothing.
 *
 * <p><b>Instancing.</b> {@code InstanceList.InstKey} keys a batch on every non-instanced slot's state, so a
 * tinted gob leaves the batch of its untinted twins and draws in a draw call of its own — correctly, and with no
 * {@code Instancable} to keep right.
 *
 * <p><b>Client-local, purely visual, one tint, last write wins, ends with the loaded object</b> — every rule
 * on {@link GobScale}'s javadoc carries over unchanged. Writing {@code null} deletes the attrib outright: no
 * tint is the absence of this state, so clearing leaves nothing behind.
 */
public final class GobTint extends GAttrib implements Gob.SetupMod {
    /**
     * The render state: a colour blended over the fragment colour at order 50. {@code haven.ColorMask} with
     * the order changed and the {@code preblend} composition dropped — one tint per gob, so {@link #apply}
     * simply puts.
     */
    public static final class Wash extends State {
        public static final Slot<Wash> slot = new Slot<Wash>(Slot.Type.DRAW, Wash.class);
        public static final Uniform u_col = new Uniform(VEC4, p -> p.get(slot).col, slot);
        private static final ShaderMacro sh = prog -> {
            FragColor.fragcol(prog.fctx).mod(in -> MiscLib.colblend.call(in, u_col.ref()), 50);
        };
        public final FColor col;

        Wash(Color c) {
            this.col = new FColor(c);
        }

        public ShaderMacro shader() {
            return sh;
        }

        public void apply(Pipe p) {
            p.put(slot, this);
        }
    }

    /** The colour last written. Volatile: written from a Lua verb, read from {@code ctick}'s state rebuild. */
    private volatile Color color;

    /** The cached {@link Wash} — one instance per colour value. */
    private volatile Pipe.Op op;

    /** The addon that last wrote the tint, so teardown knows whose colour to undo. */
    private volatile Addon owner;

    private GobTint(Gob gob, Addon owner) {
        super(gob);
        this.owner = owner;
    }

    /** The engine asks every tick; a cached instance is what keeps that from re-pushing the render state. */
    public Pipe.Op gobstate() {
        return op;
    }

    /** Point this attrib at a colour, minting the op only because the colour actually changed. */
    private void set(Addon owner, Color c) {
        this.owner = owner;
        if(!c.equals(this.color)) {
            this.op = new Wash(c);
            this.color = c;
        }
    }

    // ---- the per-gob store ------------------------------------------------------------------------

    /** The tint attrib on {@code g}, or {@code null} — nothing is created and nothing is tinted. */
    static GobTint on(Gob g) {
        return (g == null) ? null : g.getattr(GobTint.class);
    }

    /** The colour laid over {@code g}, or {@code null} for a gob nobody tinted. */
    static Color value(Gob g) {
        GobTint t = on(g);
        return (t == null) ? null : t.color;
    }

    /**
     * Lay {@code c} over {@code g}, on {@code owner}'s account. {@code null} removes the attrib outright rather
     * than leaving one behind that means "nothing" — no tint is the absence of this state, so clearing a gob is
     * clearing it completely.
     *
     * <p>Called on the UI thread (a Lua verb is marshalled onto the tick), where {@code ctick} runs too.
     */
    static void apply(Gob g, Addon owner, Color c) {
        GobTint t = on(g);
        if(c == null) {
            if(t != null)
                g.delattr(GobTint.class);
            return;
        }
        if(t == null) {
            t = new GobTint(g, owner);
            t.set(owner, c);
            g.setattr(t);           // cannot throw Loading: this attrib is not a RenderTree.Node
        } else {
            t.set(owner, c);
        }
    }

    /**
     * Undo {@code a}'s tint on {@code g} if {@code a} is who set it, and answer whether anything was undone.
     * The teardown sweep's per-gob half ({@link UiApi#teardownGobScales}), in the same loop as the size.
     */
    static boolean revert(Gob g, Addon a) {
        GobTint t = on(g);
        if((t == null) || (t.owner != a))
            return false;
        g.delattr(GobTint.class);
        return true;
    }
}
