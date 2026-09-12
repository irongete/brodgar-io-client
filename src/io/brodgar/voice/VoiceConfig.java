package io.brodgar.voice;

import io.brodgar.voice.Protocol;
import io.brodgar.voice.audio.AudioSink;
import io.brodgar.voice.audio.AudioSource;

import java.util.Objects;

/**
 * Library configuration. Only {@link Builder#serverUri(String)} is mandatory;
 * everything else has sensible defaults. Audio source/sink are pluggable so
 * clients can integrate custom audio backends (and tests can run headless).
 */
public final class VoiceConfig {

    private final String serverUri;
    private final String clientInfo;
    private final int reportIntervalMs;
    private final long connectTimeoutMs;
    private final int bitrate;
    private final int complexity;
    private final int jitterPrefillFrames;
    private final int jitterMaxFrames;
    private final boolean spatialAudio;
    private final float spatialNearTiles;
    private final float spatialMinGain;
    private final boolean vad;
    private final double vadThresholdRms;
    private final int vadHangoverMs;
    private final boolean agc;
    private final boolean autoReconnect;
    private final long reconnectMinBackoffMs;
    private final long reconnectMaxBackoffMs;
    private final AudioSource audioSource;
    private final AudioSink audioSink;
    private final DebugHooks debugHooks;

    private VoiceConfig(Builder b) {
        this.serverUri = b.serverUri;
        this.clientInfo = b.clientInfo;
        this.reportIntervalMs = b.reportIntervalMs;
        this.connectTimeoutMs = b.connectTimeoutMs;
        this.bitrate = b.bitrate;
        this.complexity = b.complexity;
        this.jitterPrefillFrames = b.jitterPrefillFrames;
        this.jitterMaxFrames = b.jitterMaxFrames;
        this.spatialAudio = b.spatialAudio;
        this.spatialNearTiles = b.spatialNearTiles;
        this.spatialMinGain = b.spatialMinGain;
        this.vad = b.vad;
        this.vadThresholdRms = b.vadThresholdRms;
        this.vadHangoverMs = b.vadHangoverMs;
        this.agc = b.agc;
        this.autoReconnect = b.autoReconnect;
        this.reconnectMinBackoffMs = b.reconnectMinBackoffMs;
        this.reconnectMaxBackoffMs = b.reconnectMaxBackoffMs;
        this.audioSource = b.audioSource;
        this.audioSink = b.audioSink;
        this.debugHooks = b.debugHooks;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** e.g. {@code wss://voice.brodgar.io} or a full URI ending in {@code /v1/ws}. */
    public String serverUri() {
        return serverUri;
    }

    public String clientInfo() {
        return clientInfo;
    }

    public int reportIntervalMs() {
        return reportIntervalMs;
    }

    public long connectTimeoutMs() {
        return connectTimeoutMs;
    }

    public int bitrate() {
        return bitrate;
    }

    public int complexity() {
        return complexity;
    }

    public int jitterPrefillFrames() {
        return jitterPrefillFrames;
    }

    public int jitterMaxFrames() {
        return jitterMaxFrames;
    }

    /** When true, remote players are attenuated and stereo-panned by their local vector. */
    public boolean spatialAudio() {
        return spatialAudio;
    }

    /** Full-volume radius in tiles (no attenuation within it). */
    public float spatialNearTiles() {
        return spatialNearTiles;
    }

    /** Attenuation floor so distant but in-range players stay audible. */
    public float spatialMinGain() {
        return spatialMinGain;
    }

    /** When true, the transmit path gates on energy-based voice activity. */
    public boolean vad() {
        return vad;
    }

    public double vadThresholdRms() {
        return vadThresholdRms;
    }

    public int vadHangoverMs() {
        return vadHangoverMs;
    }

    /** When true, outgoing voice is loudness-normalized (automatic gain control). */
    public boolean agc() {
        return agc;
    }

    /** When true, the library transparently reconnects after a dropped WebSocket. */
    public boolean autoReconnect() {
        return autoReconnect;
    }

    public long reconnectMinBackoffMs() {
        return reconnectMinBackoffMs;
    }

    public long reconnectMaxBackoffMs() {
        return reconnectMaxBackoffMs;
    }

    /** Null means "use the default microphone". */
    public AudioSource audioSource() {
        return audioSource;
    }

    /** Null means "use the default speakers". */
    public AudioSink audioSink() {
        return audioSink;
    }

    public DebugHooks debugHooks() {
        return debugHooks;
    }

    public static final class Builder {

        private String serverUri;
        private String clientInfo = "brodgar-voice/1.0.0";
        private int reportIntervalMs = Protocol.REPORT_INTERVAL_MS;
        private long connectTimeoutMs = 5_000;
        private int bitrate = 24_000;
        private int complexity = 3;
        private int jitterPrefillFrames = 2;
        private int jitterMaxFrames = 10;
        private boolean spatialAudio = true;
        private float spatialNearTiles = 4f;
        private float spatialMinGain = 0.08f;
        private boolean vad = true;
        private double vadThresholdRms = 350;
        private int vadHangoverMs = 250;
        private boolean agc = true;
        private boolean autoReconnect = true;
        private long reconnectMinBackoffMs = 1_000;
        private long reconnectMaxBackoffMs = 30_000;
        private AudioSource audioSource;
        private AudioSink audioSink;
        private DebugHooks debugHooks = DebugHooks.NONE;

        public Builder serverUri(String uri) {
            this.serverUri = uri;
            return this;
        }

        public Builder clientInfo(String info) {
            this.clientInfo = info;
            return this;
        }

        public Builder reportIntervalMs(int ms) {
            this.reportIntervalMs = ms;
            return this;
        }

        public Builder connectTimeoutMs(long ms) {
            this.connectTimeoutMs = ms;
            return this;
        }

        /** Opus target bitrate in bits/s, 8k-64k. Default 24k VOIP. */
        public Builder bitrate(int bitsPerSecond) {
            this.bitrate = bitsPerSecond;
            return this;
        }

        /** Opus encoder complexity 0-10; higher = better quality, more CPU. */
        public Builder complexity(int complexity) {
            this.complexity = complexity;
            return this;
        }

        public Builder jitterPrefillFrames(int frames) {
            this.jitterPrefillFrames = frames;
            return this;
        }

        public Builder jitterMaxFrames(int frames) {
            this.jitterMaxFrames = frames;
            return this;
        }

        /** Enable/disable client-side spatialization (default on). */
        public Builder spatialAudio(boolean on) {
            this.spatialAudio = on;
            return this;
        }

        public Builder spatialNearTiles(float tiles) {
            this.spatialNearTiles = tiles;
            return this;
        }

        public Builder spatialMinGain(float gain) {
            this.spatialMinGain = gain;
            return this;
        }

        /** Enable/disable energy VAD on the transmit path (default on). */
        public Builder vad(boolean on) {
            this.vad = on;
            return this;
        }

        public Builder vadThresholdRms(double rms) {
            this.vadThresholdRms = rms;
            return this;
        }

        public Builder vadHangoverMs(int ms) {
            this.vadHangoverMs = ms;
            return this;
        }

        /** Automatic gain control: normalize outgoing voice loudness (default on). */
        public Builder agc(boolean on) {
            this.agc = on;
            return this;
        }

        /** Transparent reconnect after a dropped WebSocket (default on). */
        public Builder autoReconnect(boolean on) {
            this.autoReconnect = on;
            return this;
        }

        public Builder reconnectBackoffMs(long minMs, long maxMs) {
            this.reconnectMinBackoffMs = minMs;
            this.reconnectMaxBackoffMs = maxMs;
            return this;
        }

        public Builder audioSource(AudioSource source) {
            this.audioSource = source;
            return this;
        }

        public Builder audioSink(AudioSink sink) {
            this.audioSink = sink;
            return this;
        }

        public Builder debugHooks(DebugHooks hooks) {
            this.debugHooks = hooks == null ? DebugHooks.NONE : hooks;
            return this;
        }

        public VoiceConfig build() {
            Objects.requireNonNull(serverUri, "serverUri is required");
            if (reportIntervalMs < 100 || reportIntervalMs > 2_000) {
                throw new IllegalArgumentException("reportIntervalMs out of range");
            }
            if (bitrate < 8_000 || bitrate > 64_000) {
                throw new IllegalArgumentException("bitrate out of range");
            }
            if (complexity < 0 || complexity > 10) {
                throw new IllegalArgumentException("complexity out of range");
            }
            if (jitterPrefillFrames < 1 || jitterPrefillFrames > jitterMaxFrames || jitterMaxFrames > 50) {
                throw new IllegalArgumentException("bad jitter buffer bounds");
            }
            return new VoiceConfig(this);
        }
    }
}
