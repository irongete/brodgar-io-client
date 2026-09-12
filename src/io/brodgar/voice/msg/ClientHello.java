package io.brodgar.voice.msg;

/** First message a client sends after the WebSocket opens. */
public final class ClientHello implements Message {

    public static final String TYPE = "hello";

    private final int protoVersion;
    private final String clientInfo;
    private final byte[] publicKey;

    public ClientHello(int protoVersion, String clientInfo, byte[] publicKey) {
        this.protoVersion = protoVersion;
        this.clientInfo = clientInfo == null ? "" : clientInfo;
        this.publicKey = publicKey == null ? new byte[0] : publicKey.clone();
    }

    @Override
    public String type() {
        return TYPE;
    }

    public int protoVersion() {
        return protoVersion;
    }

    /** Free-form client identification, e.g. {@code "my-client/1.0"}. */
    public String clientInfo() {
        return clientInfo;
    }

    /** Client's ephemeral X25519 public key (32 raw bytes) for channel key agreement. */
    public byte[] publicKey() {
        return publicKey.clone();
    }
}
