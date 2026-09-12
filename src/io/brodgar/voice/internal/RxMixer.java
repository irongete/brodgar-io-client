package io.brodgar.voice.internal;

import io.brodgar.voice.Protocol;
import io.brodgar.voice.Vec;
import io.brodgar.voice.wire.UdpPackets;
import io.brodgar.voice.DebugHooks;
import io.brodgar.voice.VoiceException;
import io.brodgar.voice.audio.AudioSink;
import io.brodgar.voice.audio.Spatializer;
import org.concentus.OpusDecoder;
import org.concentus.OpusException;

import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * Receive side: one {@link Stream} (jitter buffer + Opus decoder) per audible
 * sender. Each stream is jitter-buffered, Opus-decoded and spatialized
 * (per-stream attenuation + stereo pan), then per-sender gain and mute are
 * applied, and all streams are mixed every 20 ms into the sink, which paces
 * the loop.
 */
public final class RxMixer implements Runnable, AutoCloseable {

    private static final long SPEAKING_HANGOVER_NANOS = TimeUnit.MILLISECONDS.toNanos(300);
    private static final long IDLE_STREAM_EVICT_NANOS = TimeUnit.SECONDS.toNanos(30);
    private static final int MAX_STREAMS = 16;
    private static final int MAX_CONSECUTIVE_PLC = 5;

    private final class Stream {
        final long gob;
        final JitterBuffer jitter;
        final OpusDecoder decoder;
        final short[] pcm = new short[Protocol.FRAME_SAMPLES];
        volatile long lastPacketNanos;
        boolean speaking;
        int consecutivePlc;

        Stream(long gob) throws OpusException {
            this.gob = gob;
            this.jitter = new JitterBuffer(jitterPrefill, jitterMax);
            this.decoder = new OpusDecoder(Protocol.SAMPLE_RATE, Protocol.CHANNELS);
        }
    }

    private final AudioSink sink;
    private final DebugHooks hooks;
    private final BiConsumer<Long, Boolean> speakingSink;
    private final BiConsumer<String, String> errorSink;
    private final int jitterPrefill;
    private final int jitterMax;
    private final boolean spatialAudio;
    private final float spatialNear;
    private final float spatialMinGain;

    private final ConcurrentHashMap<Long, Stream> streams = new ConcurrentHashMap<>();
    private final Map<Long, Float> gains = new ConcurrentHashMap<>();
    private final Set<Long> muted = ConcurrentHashMap.newKeySet();
    private volatile float masterGain = 1f;
    private volatile boolean deafened = false;
    private volatile Set<Long> audible = java.util.Collections.emptySet();
    /** gobId → local relative vector, refreshed each report tick; drives spatialization. */
    private volatile Map<Long, Vec> localVectors = Collections.emptyMap();

    private final Thread thread;
    private volatile boolean running = true;

    private long framesPlayed;

    public RxMixer(AudioSink sink, int jitterPrefill, int jitterMax, DebugHooks hooks,
                   boolean spatialAudio, float spatialNear, float spatialMinGain,
                   BiConsumer<Long, Boolean> speakingSink, BiConsumer<String, String> errorSink) {
        this.sink = sink;
        this.jitterPrefill = jitterPrefill;
        this.jitterMax = jitterMax;
        this.hooks = hooks;
        this.spatialAudio = spatialAudio;
        this.spatialNear = spatialNear;
        this.spatialMinGain = spatialMinGain;
        this.speakingSink = speakingSink;
        this.errorSink = errorSink;
        this.thread = new Thread(this, "bv-mix");
        this.thread.setDaemon(true);
    }

    /** Refreshes the local vectors used for panning/attenuation (called each report tick). */
    public void setLocalVectors(Map<Long, Vec> vectors) {
        this.localVectors = vectors;
    }

    private Spatializer.Gains spatialGains(long gob) {
        if (!spatialAudio) {
            return Spatializer.CENTERED_FULL;
        }
        Vec v = localVectors.get(gob);
        if (v == null) {
            return Spatializer.CENTERED_FULL;
        }
        return Spatializer.compute(v.dx(), v.dy(), spatialNear, spatialMinGain);
    }

    public void start() {
        thread.start();
    }

    /** Called by the UDP receive thread for every forwarded audio packet. */
    public void enqueue(UdpPackets.ForwardedInner pkt) {
        if (!audible.contains(pkt.senderGob)) {
            return; // discard audio from a sender not in the audible set
        }
        Stream s = streams.get(pkt.senderGob);
        if (s == null) {
            if (streams.size() >= MAX_STREAMS) {
                return;
            }
            try {
                Stream fresh = new Stream(pkt.senderGob);
                Stream prev = streams.putIfAbsent(pkt.senderGob, fresh);
                s = prev != null ? prev : fresh;
            } catch (OpusException e) {
                errorSink.accept("codec", "cannot create Opus decoder: " + e.getMessage());
                return;
            }
        }
        s.lastPacketNanos = System.nanoTime();
        s.jitter.put(pkt.seq, pkt.opus, (pkt.flags & Protocol.FLAG_SPURT_START) != 0);
    }

    /** Called from the presence thread when the server pushes a new edge set. */
    public void setAudible(Set<Long> gobs) {
        audible = gobs;
    }

    public void setMuted(long gob, boolean m) {
        if (m) {
            muted.add(gob);
        } else {
            muted.remove(gob);
        }
    }

    public void setGain(long gob, float gain) {
        gains.put(gob, Math.max(0f, Math.min(4f, gain)));
    }

    /** Master playback gain applied to every remote player, 0..4 (1 = unity). */
    public void setMasterGain(float gain) {
        this.masterGain = Math.max(0f, Math.min(4f, gain));
    }

    /** Silence all incoming audio (still paces the jitter buffers). */
    public void setDeafened(boolean deafened) {
        this.deafened = deafened;
    }

    public boolean isDeafened() {
        return deafened;
    }

    public long framesPlayed() {
        return framesPlayed;
    }

    public int activeStreams() {
        return streams.size();
    }

    /** True if this remote gob has produced voice within the speaking-hangover window. */
    public boolean isSpeaking(long gob) {
        Stream s = streams.get(gob);
        return s != null && System.nanoTime() - s.lastPacketNanos < SPEAKING_HANGOVER_NANOS;
    }

    /** Snapshot of every remote gob currently producing voice. */
    public Set<Long> speakingGobs() {
        long now = System.nanoTime();
        Set<Long> out = new HashSet<>();
        for (Stream s : streams.values()) {
            if (now - s.lastPacketNanos < SPEAKING_HANGOVER_NANOS) {
                out.add(s.gob);
            }
        }
        return Collections.unmodifiableSet(out);
    }

    @Override
    public void run() {
        try {
            sink.start();
        } catch (VoiceException e) {
            errorSink.accept("playback", e.getMessage());
            return;
        }

        int[] mixL = new int[Protocol.FRAME_SAMPLES];
        int[] mixR = new int[Protocol.FRAME_SAMPLES];
        short[] out = new short[Protocol.FRAME_SAMPLES * 2]; // interleaved stereo

        try {
            while (running) {
                java.util.Arrays.fill(mixL, 0);
                java.util.Arrays.fill(mixR, 0);
                float master = deafened ? 0f : masterGain;
                long now = System.nanoTime();

                Iterator<Map.Entry<Long, Stream>> it = streams.entrySet().iterator();
                while (it.hasNext()) {
                    Stream s = it.next().getValue();

                    if (now - s.lastPacketNanos > IDLE_STREAM_EVICT_NANOS || !audible.contains(s.gob)) {
                        if (s.speaking) {
                            s.speaking = false;
                            speakingSink.accept(s.gob, false);
                        }
                        it.remove();
                        continue;
                    }

                    JitterBuffer.Slot slot = s.jitter.poll();
                    boolean produced = false;
                    if (slot != null && slot.frame != null) {
                        try {
                            int n = s.decoder.decode(slot.frame, 0, slot.frame.length,
                                    s.pcm, 0, Protocol.FRAME_SAMPLES, false);
                            produced = n > 0;
                            s.consecutivePlc = 0;
                        } catch (OpusException e) {
                            produced = false;
                        }
                        if (produced) {
                            hooks.onFramePlayed(s.gob, slot.seq16, now);
                        }
                    } else if (slot != null && slot.fecFrame != null
                            && s.consecutivePlc < MAX_CONSECUTIVE_PLC) {
                        // In-band FEC: reconstruct the lost frame from the copy embedded
                        // in the next packet (decode with the FEC flag set).
                        try {
                            int n = s.decoder.decode(slot.fecFrame, 0, slot.fecFrame.length,
                                    s.pcm, 0, Protocol.FRAME_SAMPLES, true);
                            produced = n > 0;
                            s.consecutivePlc++;
                        } catch (OpusException e) {
                            produced = false;
                        }
                    } else if (s.consecutivePlc < MAX_CONSECUTIVE_PLC
                            && (slot != null || !s.jitter.isPriming())) {
                        // Concealment: an interior loss with no FEC, or a brief buffer
                        // underflow while the stream is still active. Genuine silence
                        // (idle / re-priming) falls through to zero output.
                        try {
                            int n = s.decoder.decode(null, 0, 0, s.pcm, 0, Protocol.FRAME_SAMPLES, false);
                            produced = n > 0;
                            s.consecutivePlc++;
                        } catch (OpusException e) {
                            produced = false;
                        }
                    }

                    if (produced && !muted.contains(s.gob)) {
                        float gain = master * gains.getOrDefault(s.gob, 1f);
                        Spatializer.Gains sg = spatialGains(s.gob);
                        float gl = gain * sg.left;
                        float gr = gain * sg.right;
                        for (int i = 0; i < Protocol.FRAME_SAMPLES; i++) {
                            int v = s.pcm[i];
                            mixL[i] += (int) (v * gl);
                            mixR[i] += (int) (v * gr);
                        }
                    }

                    boolean speakingNow = now - s.lastPacketNanos < SPEAKING_HANGOVER_NANOS;
                    if (speakingNow != s.speaking) {
                        s.speaking = speakingNow;
                        speakingSink.accept(s.gob, speakingNow);
                    }
                }

                for (int i = 0; i < Protocol.FRAME_SAMPLES; i++) {
                    out[i * 2] = clampSample(mixL[i]);
                    out[i * 2 + 1] = clampSample(mixR[i]);
                }
                try {
                    sink.write(out, Protocol.FRAME_SAMPLES); // blocking: the playback clock
                } catch (VoiceException e) {
                    if (running) {
                        errorSink.accept("playback", e.getMessage());
                    }
                    return;
                }
                framesPlayed++;
            }
        } finally {
            sink.close();
        }
    }

    private static short clampSample(int v) {
        return (short) (v > Short.MAX_VALUE ? Short.MAX_VALUE : Math.max(v, Short.MIN_VALUE));
    }

    @Override
    public void close() {
        running = false;
        sink.close();
        thread.interrupt();
        try {
            thread.join(2_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
