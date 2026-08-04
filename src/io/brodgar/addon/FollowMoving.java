package io.brodgar.addon;

import haven.Coord3f;
import haven.Gob;
import haven.Moving;

/**
 * A client-side {@link Moving} that anchors a client-only world entity (a {@link LuaGhost}, {@link LuaSprite} or
 * {@link LuaObject}) to a <b>target gob</b>, so it <b>follows that gob automatically</b>. Since 038.2 it is the
 * world-space half of {@code gob:overlay(key, spec)} — {@code {image = asset}}, {@code {model = asset}},
 * {@code {ghost = res}} — and reachable from Lua no other way: the {@code follow = gob} option and the handles'
 * {@code :follow}/{@code :offset} it used to serve are a hard cut, because an anchor belongs on the thing it is
 * anchored to. Attach once and it tracks the gob every frame, with no per-tick polling in Lua.
 *
 * <p><b>A lost target is a MOMENT, not a state</b> (plan §2b). {@link #getc()} holds at the entity's last
 * position when the target has left {@code OCache} — which, under {@code follow=}, was forever: a sprite
 * following a felled tree floated there with no owner. It is now at most one frame, because the record that owns
 * the entity lives on the target gob and {@code LuaGobOverlay.gobGone} destroys it from the same tick that
 * dispatches the client's own {@code GobRemoved}. Reporting the loss from here instead would need someone to
 * report it TO, and the only such someone is a sweep — which is the thing this feature deleted.
 *
 * <p><b>Mechanism.</b> The engine's {@code Gob.getc()} uses a gob's {@link Moving} attrib for its live position;
 * the render tree re-evaluates each client gob's placement every frame ({@code Gob.Placed.autotick} → a new
 * {@code Placement} → {@code getc()}). So attaching this {@code Moving} makes the entity's position resolve to the
 * <b>target's</b> current interpolated position each frame — the exact path the engine's own {@code Following}
 * (held items following a hand) uses, minus the bone-offset machinery. A fixed world-space {@link #off} (x east,
 * y north, z up) shifts it relative to the target — e.g. {@code {z=10}} floats it above the head. The entity keeps
 * its <b>own</b> facing ({@code gob.a}, driven by {@code :rotate}) and scale, independent of the target.
 *
 * <p>Unlike {@code Following} this is NOT a {@code Following} subclass, so {@code Gob.Placed} takes the plain
 * {@code getc()} path (translate to the followed point, then rotate by the entity's own {@code a}) rather than the
 * bone-transform path. {@code move(Coord2d)} is inherited as a no-op — while anchored the position is owned by the
 * target, and the only way to move an overlay is to re-attach it with a different {@code offset}.
 *
 * <p><b>Threading.</b> {@link #getc()} runs on the render/loader threads (the placement pass); {@code oc.getgob}
 * is {@code synchronized} and {@code Gob.getc()} is the engine's own thread-safe position read, so no extra
 * locking is needed. {@link #off} is {@code volatile} (a leftover from the retired :offset verb; it is now set once, at create). Attaching /
 * detaching this attrib is done under {@code synchronized(gob)} by {@link AddonManager}.
 */
public final class FollowMoving extends Moving {
    /** The gob id being followed (re-resolved each frame, so it survives the target unloading/reloading). */
    final long tgt;
    /** The world-space offset added to the target's position, or {@code null} for none. Live-updated by {@code :offset}. */
    volatile Coord3f off;

    FollowMoving(Gob gob, long tgt, Coord3f off) {
        super(gob);
        this.tgt = tgt;
        this.off = off;
    }

    public Coord3f getc() {
        Gob t = gob.glob.oc.getgob(tgt);
        if(t == null)
            return gob.getrc();          // target gone (unloaded/never existed) → hold at the entity's last position
        Coord3f c = t.getc();            // the target's live, interpolated world position
        Coord3f o = off;                 // snapshot the volatile once
        return (o == null) ? c : c.add(o);
    }

    public double getv() {
        Gob t = gob.glob.oc.getgob(tgt);
        if(t == null)
            return 0.0;
        Moving m = t.getattr(Moving.class);
        return (m == null) ? 0.0 : m.getv();   // inherit the target's speed (for any animated visual)
    }
}
