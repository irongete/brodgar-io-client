package io.brodgar.voice.msg;

/**
 * Server reply to a valid hello. Carries the session identity, the UDP relay
 * endpoint with the session's routing token, the fixed audio profile and the
 * limits the server will enforce.
 */
public final class ServerWelcome implements Message {

    public static final String TYPE = "welcome";

    private final int protoVersion;
    private final long sessionId;
    private final String udpHost;
    private final int udpPort;
    private final String udpTokenHex;
    private final byte[] publicKey;
    private final int sampleRate;
    private final int channels;
    private final int frameMs;
    private final int maxFrameBytes;
    private final int reportIntervalMs;
    private final int freshnessMs;
    private final int maxVisible;

    public ServerWelcome(int protoVersion, long sessionId, String udpHost, int udpPort,
                         String udpTokenHex, byte[] publicKey, int sampleRate, int channels, int frameMs,
                         int maxFrameBytes, int reportIntervalMs, int freshnessMs, int maxVisible) {
        this.protoVersion = protoVersion;
        this.sessionId = sessionId;
        this.udpHost = udpHost;
        this.udpPort = udpPort;
        this.udpTokenHex = udpTokenHex;
        this.publicKey = publicKey == null ? new byte[0] : publicKey.clone();
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.frameMs = frameMs;
        this.maxFrameBytes = maxFrameBytes;
        this.reportIntervalMs = reportIntervalMs;
        this.freshnessMs = freshnessMs;
        this.maxVisible = maxVisible;
    }

    @Override
    public String type() {
        return TYPE;
    }

    public int protoVersion() {
        return protoVersion;
    }

    public long sessionId() {
        return sessionId;
    }

    public String udpHost() {
        return udpHost;
    }

    public int udpPort() {
        return udpPort;
    }

    /** Session token identifying this session on the UDP relay (also the HKDF salt). */
    public String udpTokenHex() {
        return udpTokenHex;
    }

    /** Server's ephemeral X25519 public key (32 raw bytes) for channel key agreement. */
    public byte[] publicKey() {
        return publicKey.clone();
    }

    public int sampleRate() {
        return sampleRate;
    }

    public int channels() {
        return channels;
    }

    public int frameMs() {
        return frameMs;
    }

    public int maxFrameBytes() {
        return maxFrameBytes;
    }

    public int reportIntervalMs() {
        return reportIntervalMs;
    }

    public int freshnessMs() {
        return freshnessMs;
    }

    public int maxVisible() {
        return maxVisible;
    }
}
