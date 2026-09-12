package io.brodgar.voice.internal;

/**
 * Simple automatic gain control for the transmit path: nudges each outgoing
 * voice frame toward a target loudness so quiet and hot microphones reach
 * listeners at a comparable level. Applied only to frames actually being sent
 * (post VAD). Pure Java, no native dependency; used only from the single tx
 * thread (not thread-safe).
 */
public final class AutoGain {

    private final double targetRms;
    private final double minGain;
    private final double maxGain;
    private final double attack;   // fast smoothing toward a lower gain
    private final double release;  // smoothing toward a higher gain (slow)
    private final double floorRms;
    private double gain = 1.0;

    public AutoGain(double targetRms, double minGain, double maxGain, double floorRms) {
        this.targetRms = targetRms;
        this.minGain = minGain;
        this.maxGain = maxGain;
        this.attack = 0.3;
        this.release = 0.05;
        this.floorRms = floorRms;
    }

    /** Target ~-18 dBFS RMS on 16-bit, up to 8x boost / 2x cut, floor to ignore near-silence. */
    public static AutoGain defaults() {
        return new AutoGain(2600.0, 0.5, 8.0, 150.0);
    }

    /** Normalizes {@code pcm[0..n)} in place toward the target loudness. */
    public void process(short[] pcm, int n) {
        double rms = rms(pcm, n);
        if (rms >= floorRms) {
            double desired = clamp(targetRms / rms, minGain, maxGain);
            double coeff = desired < gain ? attack : release;
            gain += coeff * (desired - gain);
        }
        // else: too quiet to measure reliably — hold the current gain.
        if (Math.abs(gain - 1.0) < 1e-3) {
            return; // unity: nothing to apply
        }
        for (int i = 0; i < n; i++) {
            int v = (int) Math.round(pcm[i] * gain);
            pcm[i] = (short) (v > Short.MAX_VALUE ? Short.MAX_VALUE
                    : (v < Short.MIN_VALUE ? Short.MIN_VALUE : v));
        }
    }

    private static double rms(short[] pcm, int n) {
        long sum = 0;
        for (int i = 0; i < n; i++) {
            sum += (long) pcm[i] * pcm[i];
        }
        return Math.sqrt((double) sum / Math.max(1, n));
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
