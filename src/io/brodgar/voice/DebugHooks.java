package io.brodgar.voice;

/**
 * Optional instrumentation taps used by test harnesses to measure the audio
 * pipeline (end-to-end latency, RTT). No-ops by default; not intended for
 * production use.
 */
public interface DebugHooks {

    DebugHooks NONE = new DebugHooks() {
    };

    /** A local frame was captured and is about to be encoded and sent. */
    default void onFrameCaptured(int seq, long nanoTime) {
    }

    /** A remote frame was mixed into the playback signal. */
    default void onFramePlayed(long senderGob, int seq, long nanoTime) {
    }

    /** A UDP ping/pong round-trip completed. */
    default void onUdpRtt(long rttNanos) {
    }
}
