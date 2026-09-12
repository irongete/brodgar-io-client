package io.brodgar.voice.internal;

/**
 * Energy-based voice activity detection with hangover. Gates the transmit path:
 * frames below the RMS threshold are treated as silence and dropped, so the
 * client stops sending between utterances, forming discrete talk spurts. The
 * hangover keeps the tail of a word from being clipped.
 *
 * <p>Stateful (holds the hangover countdown); used from the single transmit
 * thread.
 */
public final class EnergyVad {

    private volatile double thresholdRms;
    private final int hangoverFrames;
    private int hangoverLeft;

    public EnergyVad(double thresholdRms, int hangoverFrames) {
        this.thresholdRms = thresholdRms;
        this.hangoverFrames = Math.max(0, hangoverFrames);
    }

    /** Adjust the RMS voice threshold at runtime (a "mic sensitivity" control). */
    public void setThresholdRms(double thresholdRms) {
        this.thresholdRms = thresholdRms;
    }

    /** @return true if this frame should be transmitted (voice, or within hangover). */
    public boolean accept(short[] frame, int samples) {
        if (rms(frame, samples) >= thresholdRms) {
            hangoverLeft = hangoverFrames;
            return true;
        }
        if (hangoverLeft > 0) {
            hangoverLeft--;
            return true;
        }
        return false;
    }

    public static double rms(short[] frame, int samples) {
        if (samples <= 0) {
            return 0;
        }
        double sum = 0;
        for (int i = 0; i < samples; i++) {
            double s = frame[i];
            sum += s * s;
        }
        return Math.sqrt(sum / samples);
    }
}
