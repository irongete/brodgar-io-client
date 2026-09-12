package io.brodgar.voice;

/**
 * Protocol constants for the Brodgar voice wire format (WebSocket presence + UDP
 * relay).
 *
 * <p>Version 1 uses an X25519 key agreement in the WebSocket handshake and an
 * authenticated-encrypted (ChaCha20-Poly1305) UDP channel. Sessions are anonymous.
 */
public final class Protocol {

    private Protocol() {
    }

    /** Wire protocol version negotiated in hello/welcome. */
    public static final int VERSION = 1;

    /** WebSocket endpoint path of the presence service. */
    public static final String WS_PATH = "/v1/ws";

    // --- Audio profile ---
    public static final int SAMPLE_RATE = 48_000;
    public static final int CHANNELS = 1;
    public static final int FRAME_MS = 20;
    public static final int FRAME_SAMPLES = SAMPLE_RATE / 1000 * FRAME_MS; // 960
    /** Maximum size of a single encoded Opus frame accepted by the relay. */
    public static final int MAX_OPUS_FRAME_BYTES = 512;

    // --- Presence report limits ---
    /** Cadence at which the client sends presence reports. */
    public static final int REPORT_INTERVAL_MS = 500;
    /** Reports arriving faster than this are dropped by the server. */
    public static final int MIN_REPORT_INTERVAL_MS = 200;
    /** A session's last report older than this makes all its edges expire. */
    public static final int FRESHNESS_MS = 2500;
    public static final int MAX_VISIBLE = 256;
    public static final int MAX_INTENTS_PER_REPORT = 8;
    /** Relative vectors (in tiles) beyond this magnitude are rejected as garbage. */
    public static final double MAX_VECTOR_TILES = 10_000.0;

    // --- UDP relay ---
    public static final int UDP_TOKEN_BYTES = 16;
    public static final byte UDP_MAGIC_0 = 'B';
    public static final byte UDP_MAGIC_1 = 'V';
    public static final byte UDP_VERSION = 1;
    /** Largest datagram either side will parse. */
    public static final int MAX_DATAGRAM_BYTES = 1024;

    // --- Channel crypto ---
    /** Raw X25519 public key length exchanged in the handshake. */
    public static final int HANDSHAKE_KEY_BYTES = 32;
    /** ChaCha20-Poly1305 nonce prefixed to every sealed UDP payload. */
    public static final int UDP_NONCE_BYTES = 12;
    /** Poly1305 authentication tag appended to every sealed UDP payload. */
    public static final int UDP_TAG_BYTES = 16;

    /** Client -&gt; server: address binding + RTT probe. */
    public static final byte PT_PING = 0x00;
    /** Client -&gt; server: one Opus frame from the local player. */
    public static final byte PT_AUDIO = 0x01;
    /** Server -&gt; client: echo of a ping. */
    public static final byte PT_PONG = 0x02;
    /** Server -&gt; client: one Opus frame forwarded from another player. */
    public static final byte PT_AUDIO_FWD = 0x03;

    /** Audio flag: first frame of a talk spurt (transmission just started). */
    public static final int FLAG_SPURT_START = 0x01;

    // --- Default ports ---
    public static final int DEFAULT_WS_PORT = 7770;
    public static final int DEFAULT_UDP_PORT = 7771;

    // --- Error codes (ErrorMessage.code) ---
    public static final String ERR_PROTO_MISMATCH = "proto_mismatch";
    public static final String ERR_BAD_MESSAGE = "bad_message";
    public static final String ERR_HELLO_REQUIRED = "hello_required";
    public static final String ERR_LIMIT_EXCEEDED = "limit_exceeded";
    public static final String ERR_SERVER_FULL = "server_full";
    public static final String ERR_INTERNAL = "internal";
}
