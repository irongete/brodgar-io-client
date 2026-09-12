package io.brodgar.voice;

import java.util.Objects;

/**
 * A 2D vector in the shared tile frame: {@code dx} tiles east, {@code dy} tiles
 * south. Report frames are axis-aligned to the world (no per-client rotation),
 * so a displacement between two gobs is the same vector for every observer up to
 * translation.
 *
 * <p>It never carries absolute coordinates, only relative offsets.
 */
public final class Vec {

    public static final Vec ZERO = new Vec(0, 0);

    private final double dx;
    private final double dy;

    public Vec(double dx, double dy) {
        this.dx = dx;
        this.dy = dy;
    }

    public double dx() {
        return dx;
    }

    public double dy() {
        return dy;
    }

    public Vec plus(Vec o) {
        return new Vec(dx + o.dx, dy + o.dy);
    }

    public Vec minus(Vec o) {
        return new Vec(dx - o.dx, dy - o.dy);
    }

    public Vec negate() {
        return new Vec(-dx, -dy);
    }

    /** Euclidean magnitude, in tiles. */
    public double norm() {
        // Bounded inputs (<= 10000 tiles); plain sqrt is sufficient.
        return Math.sqrt(dx * dx + dy * dy);
    }

    /** Euclidean distance to another vector, in tiles. */
    public double dist(Vec o) {
        double ddx = dx - o.dx;
        double ddy = dy - o.dy;
        return Math.sqrt(ddx * ddx + ddy * ddy);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Vec)) {
            return false;
        }
        Vec vec = (Vec) o;
        return Double.compare(dx, vec.dx) == 0 && Double.compare(dy, vec.dy) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(dx, dy);
    }

    @Override
    public String toString() {
        return "(" + dx + ", " + dy + ")";
    }
}
