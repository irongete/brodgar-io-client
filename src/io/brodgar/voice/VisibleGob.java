package io.brodgar.voice;

import java.util.Objects;

/**
 * One player gob visible to the local player, positioned by a vector relative
 * to the local player, in tiles.
 *
 * <p>Privacy invariant: only relative vectors ever leave the client. Absolute
 * world coordinates and grid ids must never appear anywhere in the protocol.
 */
public final class VisibleGob {

    private final long gobId;
    private final double dx;
    private final double dy;

    public VisibleGob(long gobId, double dx, double dy) {
        this.gobId = gobId;
        this.dx = dx;
        this.dy = dy;
    }

    public long gobId() {
        return gobId;
    }

    /** Tiles east of the local player (negative = west). */
    public double dx() {
        return dx;
    }

    /** Tiles south of the local player (negative = north). */
    public double dy() {
        return dy;
    }

    public double distance() {
        return Math.hypot(dx, dy);
    }

    /** This observation as a {@link Vec} in the shared tile frame. */
    public Vec vec() {
        return new Vec(dx, dy);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof VisibleGob)) {
            return false;
        }
        VisibleGob that = (VisibleGob) o;
        return gobId == that.gobId
                && Double.compare(dx, that.dx) == 0
                && Double.compare(dy, that.dy) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(gobId, dx, dy);
    }

    @Override
    public String toString() {
        return "VisibleGob{" + gobId + " @ (" + dx + ", " + dy + ")}";
    }
}
