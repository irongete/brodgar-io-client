package io.brodgar.voice;

import java.util.Objects;

/**
 * A movement order issued by the local player (click / pathfinding step),
 * captured at the moment it is issued.
 *
 * <p>The destination is expressed as a vector relative to the local player's
 * position at emission time, in tiles.
 */
public final class MovementIntent {

    private final long tMillis;
    private final double dx;
    private final double dy;

    public MovementIntent(long tMillis, double dx, double dy) {
        this.tMillis = tMillis;
        this.dx = dx;
        this.dy = dy;
    }

    /** Client wall-clock time (ms since epoch) at which the order was issued. */
    public long tMillis() {
        return tMillis;
    }

    public double dx() {
        return dx;
    }

    public double dy() {
        return dy;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MovementIntent)) {
            return false;
        }
        MovementIntent that = (MovementIntent) o;
        return tMillis == that.tMillis
                && Double.compare(dx, that.dx) == 0
                && Double.compare(dy, that.dy) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(tMillis, dx, dy);
    }

    @Override
    public String toString() {
        return "MovementIntent{t=" + tMillis + ", (" + dx + ", " + dy + ")}";
    }
}
