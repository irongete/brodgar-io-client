package io.brodgar.voice.crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Authenticated encryption for the UDP channel, ChaCha20-Poly1305 (JDK, Java
 * 11+). Directional: {@code sealKey} protects outbound datagrams, {@code
 * openKey} verifies inbound ones. Client and server hold mirror-image channels
 * over the same pair of keys.
 *
 * <p>Wire form of a sealed payload: {@code nonce(12) || ciphertext || tag(16)}.
 * A fresh random 96-bit nonce is generated per datagram; at voice packet rates
 * over a per-session key the reuse probability is negligible.
 *
 * <p>Each call builds its own {@link Cipher}, so instances are safe to share
 * across threads.
 */
public final class AeadChannel {

    public static final int NONCE_BYTES = 12;
    public static final int TAG_BYTES = 16;
    private static final String TRANSFORM = "ChaCha20-Poly1305";
    private static final String KEY_ALG = "ChaCha20";

    private final SecretKeySpec sealKey;
    private final SecretKeySpec openKey;
    private final SecureRandom rng = new SecureRandom();

    public AeadChannel(byte[] sealKey, byte[] openKey) {
        if (sealKey.length != 32 || openKey.length != 32) {
            throw new CryptoException("ChaCha20 keys must be 32 bytes");
        }
        this.sealKey = new SecretKeySpec(sealKey, KEY_ALG);
        this.openKey = new SecretKeySpec(openKey, KEY_ALG);
    }

    /** @return {@code nonce || ciphertext || tag} */
    public byte[] seal(byte[] plaintext, int off, int len) {
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            rng.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.ENCRYPT_MODE, sealKey, new IvParameterSpec(nonce));
            byte[] ct = cipher.doFinal(plaintext, off, len);
            byte[] out = new byte[NONCE_BYTES + ct.length];
            System.arraycopy(nonce, 0, out, 0, NONCE_BYTES);
            System.arraycopy(ct, 0, out, NONCE_BYTES, ct.length);
            return out;
        } catch (GeneralSecurityException e) {
            throw new CryptoException("seal failed", e);
        }
    }

    public byte[] seal(byte[] plaintext) {
        return seal(plaintext, 0, plaintext.length);
    }

    /**
     * @return the recovered plaintext
     * @throws GeneralSecurityException on a bad tag / tampered or forged packet
     *                                  (the caller drops it)
     */
    public byte[] open(byte[] sealed, int off, int len) throws GeneralSecurityException {
        if (len < NONCE_BYTES + TAG_BYTES) {
            throw new GeneralSecurityException("sealed payload too short");
        }
        IvParameterSpec iv = new IvParameterSpec(Arrays.copyOfRange(sealed, off, off + NONCE_BYTES));
        Cipher cipher = Cipher.getInstance(TRANSFORM);
        cipher.init(Cipher.DECRYPT_MODE, openKey, iv);
        return cipher.doFinal(sealed, off + NONCE_BYTES, len - NONCE_BYTES);
    }

    public byte[] open(byte[] sealed) throws GeneralSecurityException {
        return open(sealed, 0, sealed.length);
    }
}
