package io.brodgar.voice.internal;

import io.brodgar.voice.Protocol;
import io.brodgar.voice.DebugHooks;
import io.brodgar.voice.VoiceException;
import io.brodgar.voice.audio.AudioSource;
import org.concentus.OpusApplication;
import org.concentus.OpusEncoder;
import org.concentus.OpusException;

import java.util.concurrent.locks.LockSupport;
import java.util.function.BiConsumer;

/**
 * Capture &rarr; Opus encode &rarr; UDP send. One thread, paced by the blocking
 * {@link AudioSource#read(short[])}. The source is drained continuously,
 * including while not transmitting.
 */
public final class TxPipeline implements Runnable, AutoCloseable {

    /** Voice-activity indicator hangover, matching the receive side. */
    private static final long SPEAKING_HANGOVER_NANOS = 300_000_000L; // 300 ms

    private final AudioSource source;
    /** Current relay socket; swapped on reconnect, null while disconnected. */
    private volatile UdpClient udp;
    private final DebugHooks hooks;
    private final BiConsumer<String, String> errorSink;
    private final int bitrate;
    private final int complexity;
    private volatile boolean vadEnabled;
    private final EnergyVad vad;
    private volatile boolean agcEnabled;
    private final AutoGain autoGain = AutoGain.defaults();

    private final Thread thread;
    private volatile boolean running = true;
    private volatile boolean transmitting;
    private volatile boolean micMuted;

    private long framesSent;
    /** nanoTime of the last encoded voice frame; drives isSpeaking() with a hangover. */
    private volatile long lastVoiceFrameNanos = 0L;

    public TxPipeline(AudioSource source, int bitrate, int complexity,
                      DebugHooks hooks, boolean vadEnabled, double vadThresholdRms, int vadHangoverMs,
                      boolean agc, BiConsumer<String, String> errorSink) {
        this.source = source;
        this.bitrate = bitrate;
        this.complexity = complexity;
        this.hooks = hooks;
        this.vadEnabled = vadEnabled;
        this.agcEnabled = agc;
        int hangoverFrames = Math.max(0, vadHangoverMs / Protocol.FRAME_MS);
        this.vad = new EnergyVad(vadThresholdRms, hangoverFrames);
        this.errorSink = errorSink;
        this.thread = new Thread(this, "bv-tx");
        this.thread.setDaemon(true);
    }

    public void start() {
        thread.start();
    }

    public void setTransmitting(boolean on) {
        transmitting = on;
    }

    /** Hard mute independent of the push-to-talk gate: while true, nothing is sent. */
    public void setMicMuted(boolean muted) {
        micMuted = muted;
    }

    public boolean isMicMuted() {
        return micMuted;
    }

    /** Point at a new relay socket after a reconnect, or {@code null} while down. */
    public void setUdp(UdpClient udp) {
        this.udp = udp;
    }

    /** Toggle energy VAD at runtime. Off + PTT (setTransmitting) = pure push-to-talk. */
    public void setVadEnabled(boolean on) {
        this.vadEnabled = on;
    }

    public boolean isVadEnabled() {
        return vadEnabled;
    }

    public void setVadThresholdRms(double rms) {
        vad.setThresholdRms(rms);
    }

    /** Toggle transmit-path automatic gain control at runtime. */
    public void setAgcEnabled(boolean on) {
        this.agcEnabled = on;
    }

    public boolean isAgcEnabled() {
        return agcEnabled;
    }

    public boolean isTransmitting() {
        return transmitting;
    }

    public long framesSent() {
        return framesSent;
    }

    /** True while the local player is actively producing voice (post PTT + VAD). */
    public boolean isSpeaking() {
        long t = lastVoiceFrameNanos;
        return t != 0L && System.nanoTime() - t < SPEAKING_HANGOVER_NANOS;
    }

    @Override
    public void run() {
        OpusEncoder encoder;
        try {
            encoder = new OpusEncoder(Protocol.SAMPLE_RATE, Protocol.CHANNELS,
                    OpusApplication.OPUS_APPLICATION_VOIP);
            encoder.setBitrate(bitrate);
            encoder.setComplexity(complexity);
            encoder.setUseInbandFEC(true);      // embed a recovery copy of the previous frame
            encoder.setPacketLossPercent(10);   // tune FEC strength for typical UDP loss
        } catch (OpusException e) {
            errorSink.accept("codec", "cannot create Opus encoder: " + e.getMessage());
            return;
        }
        try {
            source.start();
        } catch (VoiceException e) {
            errorSink.accept("capture", e.getMessage());
            return;
        }

        short[] pcm = new short[Protocol.FRAME_SAMPLES];
        byte[] opus = new byte[Protocol.MAX_OPUS_FRAME_BYTES];
        int seq = 0;
        boolean wasTransmitting = false;

        try {
            while (running) {
                int n;
                try {
                    n = source.read(pcm);
                } catch (VoiceException e) {
                    if (running) {
                        errorSink.accept("capture", e.getMessage());
                    }
                    return;
                }
                if (n < 0) {
                    if (running) {
                        errorSink.accept("capture", "audio source ended");
                    }
                    return;
                }
                if (n == 0) {
                    LockSupport.parkNanos(1_000_000);
                    continue;
                }
                if (micMuted || !transmitting) {
                    wasTransmitting = false;
                    continue; // muted or not transmitting: drain and discard
                }
                if (vadEnabled && !vad.accept(pcm, n)) {
                    wasTransmitting = false; // below the voice threshold: end the spurt
                    continue;
                }

                long captureNanos = System.nanoTime();
                int flags = wasTransmitting ? 0 : Protocol.FLAG_SPURT_START;
                wasTransmitting = true;

                if (agcEnabled) {
                    autoGain.process(pcm, Protocol.FRAME_SAMPLES);
                }

                int encoded;
                try {
                    encoded = encoder.encode(pcm, 0, Protocol.FRAME_SAMPLES, opus, 0, opus.length);
                } catch (OpusException e) {
                    errorSink.accept("codec", "encode failed: " + e.getMessage());
                    continue;
                }
                if (encoded <= 0) {
                    continue;
                }
                lastVoiceFrameNanos = captureNanos; // real voice frame: mark speaking
                seq = (seq + 1) & 0xFFFF;
                UdpClient u = udp;
                if (u == null) {
                    continue; // disconnected / reconnecting: drop the frame
                }
                hooks.onFrameCaptured(seq, captureNanos);
                u.sendAudio(seq, flags, opus, encoded);
                framesSent++;
            }
        } finally {
            source.close();
        }
    }

    @Override
    public void close() {
        running = false;
        transmitting = false;
        // Closing the source is what unblocks a thread stuck in a blocking
        // device read (interrupts do not reach javax.sound line reads).
        source.close();
        thread.interrupt();
        try {
            thread.join(2_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
