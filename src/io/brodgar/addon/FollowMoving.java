package io.brodgar.addon;

import haven.Coord3f;
import haven.Gob;
import haven.Moving;

/**
 * A client-side {@link Moving} that anchors a client-only world entity (a {@link LuaGhost}, {@link LuaSprite} or
 * {@link LuaObject}) to a <b>target gob</b>, so it <b>follows that gob automatically</b>. Since 043.2 it is what
 * {@code hafen.vr():<kind>():add(what, gob)} builds — the anchor is an ARGUMENT of the placement, because an
 * anchor is half of where a thing is rather than a property set afterwards — and since 043.3 that is its one
 * caller: {@code gob:overlay()}'s world kinds, which used to build it too, are gone. Attach once and it tracks
 * the gob every frame, with no per-tick polling in Lua.
 *
 * <p><b>A lost target is a MOMENT, not a state</b> (plan §2b). {@link #getc()} holds at the entity's last
 * position when the target has left {@code OCache} — which, under the old {@code follow=} option, was forever: a
 * sprite following a felled tree floated there with no owner. It is now at most one frame, because
 * {@code VrApi.anchorGone} destroys every entity anchored to that gob from the same tick that dispatches the
 * client's own {@code GobRemoved}, found in O(1) through the by-target index (D-185). Reporting the loss from
 * here instead would need someone to report it TO, and the only such someone is a sweep — which is the thing
 * this feature deleted.
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
 * target, and the only thing an addon moves is the offset, through the entity's own
 * {@code :offset(x, y, z)} (which is why {@code :position(p)} on an anchored entity is refused naming it, D-186).
 *
 * <p><b>Threading.</b> {@link #getc()} runs on the render/loader threads (the placement pass); {@code oc.getgob}
 * is {@code synchronized} and {@code Gob.getc()} is the engine's own thread-safe position read, so no extra
 * locking is needed. {@link #off} is {@code volatile} because the entity's {@code :offset} writes it from the UI thread
 * while the placement pass reads it. Attaching /
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
