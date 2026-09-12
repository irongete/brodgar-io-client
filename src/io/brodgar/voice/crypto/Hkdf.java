package io.brodgar.voice.crypto;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;

/**
 * HKDF-SHA256 (RFC 5869), extract-then-expand, implemented on top of the JDK's
 * HMAC. Used to turn the raw X25519 shared secret into distinct, direction-
 * specific channel keys.
 */
public final class Hkdf {

    private static final String HMAC = "HmacSHA256";
    private static final int HASH_LEN = 32;

    private Hkdf() {
    }

    public static byte[] deriveKey(byte[] ikm, byte[] salt, byte[] info, int length) {
        if (length <= 0 || length > 255 * HASH_LEN) {
            throw new CryptoException("invalid HKDF output length: " + length);
        }
        try {
            byte[] prk = extract(salt, ikm);
            return expand(prk, info, length);
        } catch (GeneralSecurityException e) {
            throw new CryptoException("HKDF failed", e);
        }
    }

    private static byte[] extract(byte[] salt, byte[] ikm) throws GeneralSecurityException {
        byte[] effectiveSalt = (salt == null || salt.length == 0) ? new byte[HASH_LEN] : salt;
        Mac mac = Mac.getInstance(HMAC);
        mac.init(new SecretKeySpec(effectiveSalt, HMAC));
        return mac.doFinal(ikm);
    }

    private static byte[] expand(byte[] prk, byte[] info, int length) throws GeneralSecurityException {
        Mac mac = Mac.getInstance(HMAC);
        mac.init(new SecretKeySpec(prk, HMAC));
        byte[] out = new byte[length];
        byte[] t = new byte[0];
        int pos = 0;
        byte counter = 1;
        while (pos < length) {
            mac.update(t);
            if (info != null) {
                mac.update(info);
            }
            mac.update(counter);
            t = mac.doFinal();
            int n = Math.min(t.length, length - pos);
            System.arraycopy(t, 0, out, pos, n);
            pos += n;
            counter++;
        }
        return out;
    }
}
