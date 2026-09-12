package io.brodgar.voice.wire;

import io.brodgar.voice.Protocol;
import io.brodgar.voice.ProtocolException;

import java.util.Arrays;

/**
 * Binary framing for the v1 UDP audio relay. A datagram is a small cleartext
 * frame wrapping an AEAD-sealed payload:
 *
 * <pre>
 * header (4):     'B' 'V' version type
 * c2s frame:      header | token[16] | sealed        (PING, AUDIO)
 * s2c frame:      header | sealed                    (PONG, AUDIO_FWD)
 * sealed:         nonce[12] | ciphertext | tag[16]   (see {@link io.brodgar.voice.crypto.AeadChannel})
 * </pre>
 *
 * <p>Only the routing header and the session token travel in the clear; the
 * token merely selects which session key opens the payload. The token alone
 * grants no authority: a forged datagram fails the Poly1305 tag. The sealed
 * <em>inner</em> plaintext, once opened, is:
 *
 * <pre>
 * PING  inner:  clientNanos[8]
 * PONG  inner:  echoedNanos[8]
 * AUDIO inner:  seq[2] | flags[1] | opus[n]
 * FWD   inner:  senderGobId[8] | seq[2] | flags[1] | opus[n]
 * </pre>
 *
 * <p>This class only frames and validates structure; sealing/opening is done by
 * the caller's {@code AeadChannel}. Integers are big-endian.
 */
public final class UdpPackets {

    public static final int HEADER_BYTES = 4;
    public static final int MIN_SEALED_BYTES = Protocol.UDP_NONCE_BYTES + Protocol.UDP_TAG_BYTES + 1;
    private static final int AUDIO_INNER_MIN = 2 + 1 + 1;      // seq + flags + >=1 opus byte
    private static final int FWD_INNER_MIN = 8 + 2 + 1 + 1;    // gob + seq + flags + >=1 opus byte
    private static final int TS_INNER = 8;                     // ping/pong timestamp

    private UdpPackets() {
    }

    // ------------------------------------------------------------- frame types

    /** A parsed client-to-server frame: type, routing token, still-sealed payload.
     *  Server-side (the client sends these, never parses them); used by wire tests. */
    static final class ClientFrame {
        public final byte type;
        public final byte[] token;
        public final byte[] sealed;

        ClientFrame(byte type, byte[] token, byte[] sealed) {
            this.type = type;
            this.token = token;
            this.sealed = sealed;
        }
    }

    /** A parsed server-to-client frame: type and still-sealed payload. */
    public static final class ServerFrame {
        public final byte type;
        public final byte[] sealed;

        ServerFrame(byte type, byte[] sealed) {
            this.type = type;
            this.sealed = sealed;
        }
    }

    /** Opened AUDIO inner payload. Server-side (the client seals these, never opens
     *  its own); used by wire tests. */
    static final class AudioInner {
        public final int seq;
        public final int flags;
        public final byte[] opus;

        AudioInner(int seq, int flags, byte[] opus) {
            this.seq = seq;
            this.flags = flags;
            this.opus = opus;
        }
    }

    /** Opened AUDIO_FWD inner payload. */
    public static final class ForwardedInner {
        public final long senderGob;
        public final int seq;
        public final int flags;
        public final byte[] opus;

        ForwardedInner(long senderGob, int seq, int flags, byte[] opus) {
            this.senderGob = senderGob;
            this.seq = seq;
            this.flags = flags;
            this.opus = opus;
        }
    }

    // ------------------------------------------------------------- frame build

    public static byte[] clientFrame(byte type, byte[] token, byte[] sealed) {
        if (token == null || token.length != Protocol.UDP_TOKEN_BYTES) {
            throw new IllegalArgumentException("token must be " + Protocol.UDP_TOKEN_BYTES + " bytes");
        }
        byte[] out = new byte[HEADER_BYTES + Protocol.UDP_TOKEN_BYTES + sealed.length];
        writeHeader(out, type);
        System.arraycopy(token, 0, out, HEADER_BYTES, Protocol.UDP_TOKEN_BYTES);
        System.arraycopy(sealed, 0, out, HEADER_BYTES + Protocol.UDP_TOKEN_BYTES, sealed.length);
        return out;
    }

    static byte[] serverFrame(byte type, byte[] sealed) {
        byte[] out = new byte[HEADER_BYTES + sealed.length];
        writeHeader(out, type);
        System.arraycopy(sealed, 0, out, HEADER_BYTES, sealed.length);
        return out;
    }

    // ------------------------------------------------------------- frame parse

    static ClientFrame parseClientFrame(byte[] data, int len) throws ProtocolException {
        byte type = parseHeader(data, len);
        if (type != Protocol.PT_PING && type != Protocol.PT_AUDIO) {
            throw new ProtocolException("not a client packet type: " + type);
        }
        int min = HEADER_BYTES + Protocol.UDP_TOKEN_BYTES + MIN_SEALED_BYTES;
        if (len < min) {
            throw new ProtocolException("client frame too short: " + len);
        }
        byte[] token = Arrays.copyOfRange(data, HEADER_BYTES, HEADER_BYTES + Protocol.UDP_TOKEN_BYTES);
        byte[] sealed = Arrays.copyOfRange(data, HEADER_BYTES + Protocol.UDP_TOKEN_BYTES, len);
        return new ClientFrame(type, token, sealed);
    }

    public static ServerFrame parseServerFrame(byte[] data, int len) throws ProtocolException {
        byte type = parseHeader(data, len);
        if (type != Protocol.PT_PONG && type != Protocol.PT_AUDIO_FWD) {
            throw new ProtocolException("not a server packet type: " + type);
        }
        if (len < HEADER_BYTES + MIN_SEALED_BYTES) {
            throw new ProtocolException("server frame too short: " + len);
        }
        byte[] sealed = Arrays.copyOfRange(data, HEADER_BYTES, len);
        return new ServerFrame(type, sealed);
    }

    // ------------------------------------------------------------- inner codecs

    public static byte[] encodeTimestampInner(long nanos) {
        byte[] out = new byte[TS_INNER];
        writeLong(out, 0, nanos);
        return out;
    }

    public static long decodeTimestampInner(byte[] pt) throws ProtocolException {
        if (pt.length != TS_INNER) {
            throw new ProtocolException("bad timestamp inner length: " + pt.length);
        }
        return readLong(pt, 0);
    }

    public static byte[] encodeAudioInner(int seq, int flags, byte[] opus, int opusLen) {
        checkOpus(opusLen);
        byte[] out = new byte[2 + 1 + opusLen];
        out[0] = (byte) (seq >>> 8);
        out[1] = (byte) seq;
        out[2] = (byte) flags;
        System.arraycopy(opus, 0, out, 3, opusLen);
        return out;
    }

    static AudioInner decodeAudioInner(byte[] pt) throws ProtocolException {
        if (pt.length < AUDIO_INNER_MIN) {
            throw new ProtocolException("audio inner too short: " + pt.length);
        }
        int opusLen = pt.length - 3;
        checkOpusParse(opusLen);
        int seq = ((pt[0] & 0xFF) << 8) | (pt[1] & 0xFF);
        int flags = pt[2] & 0xFF;
        return new AudioInner(seq, flags, Arrays.copyOfRange(pt, 3, pt.length));
    }

    static byte[] encodeForwardedInner(long senderGob, int seq, int flags, byte[] opus, int opusLen) {
        checkOpus(opusLen);
        byte[] out = new byte[8 + 2 + 1 + opusLen];
        writeLong(out, 0, senderGob);
        out[8] = (byte) (seq >>> 8);
        out[9] = (byte) seq;
        out[10] = (byte) flags;
        System.arraycopy(opus, 0, out, 11, opusLen);
        return out;
    }

    public static ForwardedInner decodeForwardedInner(byte[] pt) throws ProtocolException {
        if (pt.length < FWD_INNER_MIN) {
            throw new ProtocolException("forwarded inner too short: " + pt.length);
        }
        int opusLen = pt.length - 11;
        checkOpusParse(opusLen);
        long gob = readLong(pt, 0);
        int seq = ((pt[8] & 0xFF) << 8) | (pt[9] & 0xFF);
        int flags = pt[10] & 0xFF;
        return new ForwardedInner(gob, seq, flags, Arrays.copyOfRange(pt, 11, pt.length));
    }

    // ------------------------------------------------------------- util

    private static void writeHeader(byte[] out, byte type) {
        out[0] = Protocol.UDP_MAGIC_0;
        out[1] = Protocol.UDP_MAGIC_1;
        out[2] = Protocol.UDP_VERSION;
        out[3] = type;
    }

    private static byte parseHeader(byte[] data, int len) throws ProtocolException {
        if (len < HEADER_BYTES || len > Protocol.MAX_DATAGRAM_BYTES) {
            throw new ProtocolException("bad datagram length: " + len);
        }
        if (data[0] != Protocol.UDP_MAGIC_0 || data[1] != Protocol.UDP_MAGIC_1) {
            throw new ProtocolException("bad magic");
        }
        if (data[2] != Protocol.UDP_VERSION) {
            throw new ProtocolException("unsupported UDP protocol version: " + data[2]);
        }
        return data[3];
    }

    private static void writeLong(byte[] out, int off, long v) {
        for (int i = 0; i < 8; i++) {
            out[off + i] = (byte) (v >>> (56 - 8 * i));
        }
    }

    private static long readLong(byte[] in, int off) {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (in[off + i] & 0xFF);
        }
        return v;
    }

    private static void checkOpus(int opusLen) {
        if (opusLen <= 0 || opusLen > Protocol.MAX_OPUS_FRAME_BYTES) {
            throw new IllegalArgumentException("bad opus frame length: " + opusLen);
        }
    }

    private static void checkOpusParse(int opusLen) throws ProtocolException {
        if (opusLen <= 0 || opusLen > Protocol.MAX_OPUS_FRAME_BYTES) {
            throw new ProtocolException("bad opus frame length: " + opusLen);
        }
    }
}
