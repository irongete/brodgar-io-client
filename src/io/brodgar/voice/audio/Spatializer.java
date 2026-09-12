package io.brodgar.voice.audio;

/**
 * Turns a listener-relative vector into stereo gains: distance attenuation plus
 * horizontal panning, mirroring the game's own positional audio. Pure client-side
 * math; the vectors never leave the machine.
 *
 * <p>The vector is in the camera's horizontal frame: {@code right} is tiles to the
 * listener's right on screen, {@code forward} is tiles into the screen. Panning is
 * the azimuth off the forward axis, saturating to a full ear past ~22.5&deg; — the
 * same balance the game uses for footsteps and every other in-world sound.
 */
public final class Spatializer {

    /** Per-ear linear gains, already including distance attenuation. */
    public static final class Gains {
        public final float left;
        public final float right;

        public Gains(float left, float right) {
            this.left = left;
            this.right = right;
        }
    }

    /** Both ears at full volume — used when spatialization is disabled. */
    public static final Gains CENTERED_FULL = new Gains(1f, 1f);

    private static final double SATURATE = Math.PI / 8.0; // azimuth mapped to a full ear

    private Spatializer() {
    }

    /**
     * @param right   tiles to the listener's right (negative = left)
     * @param forward tiles into the screen (negative = behind)
     * @param near    full-volume radius in tiles (no attenuation within it)
     * @param minGain attenuation floor so distant-but-in-range players stay audible
     */
    public static Gains compute(double right, double forward, float near, float minGain) {
        double dist = Math.hypot(right, forward);

        float atten;
        if (dist <= near) {
            atten = 1f;
        } else {
            atten = (float) Math.max(minGain, near / dist);
        }

        // Azimuth off the forward axis, saturated like the game's balance, then
        // equal-power panned (constant perceived loudness across the sweep).
        double bal = (dist < 1e-6) ? 0.0 : clamp(Math.atan2(right, forward) / SATURATE, -1.0, 1.0);
        double angle = (bal + 1.0) * 0.25 * Math.PI; // 0 .. pi/2
        return new Gains(atten * (float) Math.cos(angle), atten * (float) Math.sin(angle));
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : Math.min(v, hi);
    }
}
