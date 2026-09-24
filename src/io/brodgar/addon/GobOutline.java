package io.brodgar.addon;

import haven.GAttrib;
import haven.Gob;
import haven.render.Pipe;
import haven.render.State;
import haven.render.sl.ShaderMacro;
import haven.render.sl.Uniform;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

import static haven.render.sl.Type.VEC4;

/**
 * <b>A ring round what the client draws of a game object</b> (spec {@code 165-gob-outline}, task 165.1). Cut from
 * {@link GobTint}'s mould: a {@link GAttrib} that is also a {@link Gob.SetupMod}, contributing one cached
 * {@link Pipe.Op} to the gob's own render slot, so no engine file is edited and a change lands on the next
 * {@code ctick}.
 *
 * <p><b>What the op does.</b> {@link Paint} marks the object's fragments in the {@link OutlineMask}: every
 * fragment it draws — model, equipment, the game's overlay sprites at it, whatever the gob slot's subtree
 * holds — writes the ring's colour and width there instead of {@code 0}. {@link OutlineRing} then draws the ring
 * round the marked region in two screen passes. Nothing about the object's own colour changes: the mask is an
 * output of its own.
 *
 * <p><b>{@code a = 0} contributes no state.</b> A ring of no strength draws nothing, so the attrib holds the
 * colour for the read and hands the engine {@code null}.
 *
 * <p><b>Liveness.</b> The two passes cost a screen's worth of taps, so they run only while some ring is live:
 * {@link #widest()} reads every instance through a weak set, and an instance is live while it is not disposed
 * (a clear or a revert deletes the attrib) and its gob is not {@code removed} — {@code OCache.remove} never
 * disposes a gob's attribs, so {@code removed} is what ends a game object's ring.
 *
 * <p><b>Client-local, purely visual, one ring, last write wins, ends with the loaded object</b> — {@link GobTint}'s
 * rules unchanged. Setting or clearing it leaves the tint alone: two attribs, two slots.
 */
public final class GobOutline extends GAttrib implements Gob.SetupMod {
    /** The width a write without one draws, in design pixels. */
    static final int DEFAULT_WIDTH = 2;

    /**
     * The render state: the {@link OutlineMask} value replaced by the encoded texel. A DRAW slot, not instanced,
     * so an outlined object leaves its batch as a tinted one does.
     */
    public static final class Paint extends State {
        public static final Slot<Paint> slot = new Slot<Paint>(Slot.Type.DRAW, Paint.class);
        public static final Uniform u_col = new Uniform(VEC4, p -> p.get(slot).texel, slot);
        private static final ShaderMacro sh = prog -> OutlineMask.value(prog.fctx).mod(in -> u_col.ref(), 0);
        final float[] texel;

        Paint(Color c, int width) {
            int q = Math.max(1, Math.round(c.getAlpha() * 31f / 255f));
            this.texel = new float[] {
                c.getRed() / 255f, c.getGreen() / 255f, c.getBlue() / 255f, ((32 * (width - 1)) + q) / 255f};
        }

        public ShaderMacro shader() {
            return sh;
        }

        public void apply(Pipe p) {
            p.put(slot, this);
        }
    }

    /** Every instance ever minted, weakly: the registry {@link #widest()} reads. */
    private static final Set<GobOutline> all = Collections.newSetFromMap(new WeakHashMap<GobOutline, Boolean>());

    private volatile Color color;
    private volatile int width;
    /** The cached {@link Paint}, or {@code null} for {@code a = 0}. */
    private volatile Pipe.Op op;
    /** The addon that last wrote the ring, so teardown knows whose ring to undo. */
    private volatile Addon owner;
    private volatile boolean disposed;

    private GobOutline(Gob gob, Addon owner) {
        super(gob);
        this.owner = owner;
        synchronized(all) {
            all.add(this);
        }
    }

    public Pipe.Op gobstate() {
        return op;
    }

    public void dispose() {
        disposed = true;
    }

    /** Point this attrib at a ring, minting the op only because the ring actually changed. */
    private void set(Addon owner, Color c, int w) {
        this.owner = owner;
        if(!c.equals(this.color) || (w != this.width)) {
            this.op = (c.getAlpha() == 0) ? null : new Paint(c, w);
            this.color = c;
            this.width = w;
        }
    }

    private boolean live() {
        return !disposed && !gob.removed && (op != null);
    }

    // ---- the per-gob store ------------------------------------------------------------------------

    /** The ring attrib on {@code g}, or {@code null}. */
    static GobOutline on(Gob g) {
        return (g == null) ? null : g.getattr(GobOutline.class);
    }

    /** The ring's colour on {@code g}, or {@code null} for a gob nobody outlined. */
    static Color value(Gob g) {
        GobOutline o = on(g);
        return (o == null) ? null : o.color;
    }

    /** The ring's width on {@code g} in design pixels; meaningful only where {@link #value} is not {@code null}. */
    static int width(Gob g) {
        GobOutline o = on(g);
        return (o == null) ? DEFAULT_WIDTH : o.width;
    }

    /**
     * Ring {@code g} in {@code c}, {@code w} design pixels wide, on {@code owner}'s account. {@code null} deletes
     * the attrib outright: no ring is the absence of this state.
     */
    static void apply(Gob g, Addon owner, Color c, int w) {
        GobOutline o = on(g);
        if(c == null) {
            if(o != null)
                g.delattr(GobOutline.class);
            return;
        }
        if(o == null) {
            o = new GobOutline(g, owner);
            o.set(owner, c, w);
            g.setattr(o);           // cannot throw Loading: this attrib is not a RenderTree.Node
        } else {
            o.set(owner, c, w);
        }
    }

    /** Undo {@code a}'s ring on {@code g} if {@code a} is who set it, and answer whether anything was undone. */
    static boolean revert(Gob g, Addon a) {
        GobOutline o = on(g);
        if((o == null) || (o.owner != a))
            return false;
        g.delattr(GobOutline.class);
        return true;
    }

    /**
     * The widest live ring in design pixels, or {@code 0} with none — what switches {@link OutlineRing}'s passes
     * on and sizes their reach. Reads a copy of the registry, and takes no gob monitor under its lock.
     */
    static int widest() {
        List<GobOutline> copy;
        synchronized(all) {
            copy = new ArrayList<GobOutline>(all);
        }
        int w = 0;
        for(GobOutline o : copy) {
            if(o.live())
                w = Math.max(w, o.width);
        }
        return w;
    }

    // ---- parsing, shared by every :outline verb ----------------------------------------------------

    /** The width argument at {@code i}: {@link #DEFAULT_WIDTH} when absent, else a whole number {@code 1..8}. */
    static int widthArg(Varargs a, int i, String verb) {
        LuaValue v = Args.written(a, i, verb, "width");
        if(v == null)
            return DEFAULT_WIDTH;
        return (int)Args.integer(v, verb, "width", "design pixels", 1, 8);
    }

    /** {@code :outline(nil, w)}: clearing takes no width. */
    static LuaError nilWithWidth(String verb) {
        return new LuaError(verb + "(nil) takes the ring off and takes no width — " + verb
            + "(color, width) draws one");
    }
}
