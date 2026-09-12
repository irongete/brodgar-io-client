package io.brodgar.voice.crypto;

import java.nio.charset.StandardCharsets;

/**
 * The two directional channel keys for one session, derived from the X25519
 * shared secret with HKDF, one key per direction.
 *
 * <p>The session token is used as the HKDF salt, binding the keys to the exact
 * session the server issued.
 */
public final class SessionKeys {

    private static final byte[] INFO_C2S = "brodgar-udp-c2s-v1".getBytes(StandardCharsets.UTF_8);
    private static final byte[] INFO_S2C = "brodgar-udp-s2c-v1".getBytes(StandardCharsets.UTF_8);
    private static final int KEY_BYTES = 32;

    /** Key protecting client-to-server datagrams. */
    public final byte[] c2s;
    /** Key protecting server-to-client datagrams. */
    public final byte[] s2c;

    private SessionKeys(byte[] c2s, byte[] s2c) {
        this.c2s = c2s;
        this.s2c = s2c;
    }

    public static SessionKeys derive(byte[] sharedSecret, byte[] salt) {
        return new SessionKeys(
                Hkdf.deriveKey(sharedSecret, salt, INFO_C2S, KEY_BYTES),
                Hkdf.deriveKey(sharedSecret, salt, INFO_S2C, KEY_BYTES));
    }

    /** Channel as seen by the client: it seals c2s and opens s2c. */
    public AeadChannel clientChannel() {
        return new AeadChannel(c2s, s2c);
    }

    /** Channel as seen by the server for this session: it seals s2c and opens c2s. */
    public AeadChannel serverChannel() {
        return new AeadChannel(s2c, c2s);
    }
}
