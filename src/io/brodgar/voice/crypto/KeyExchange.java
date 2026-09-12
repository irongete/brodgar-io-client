package io.brodgar.voice.crypto;

import javax.crypto.KeyAgreement;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.XECPublicKey;
import java.security.spec.NamedParameterSpec;
import java.security.spec.XECPublicKeySpec;

/**
 * X25519 (Curve25519) ephemeral key agreement, using only the JDK provider
 * (available since Java 11, no native libraries). Each connection generates a
 * fresh keypair, exchanges 32-byte raw public keys over the WebSocket, and
 * derives a shared secret that never travels the wire — the basis for the UDP
 * channel keys.
 */
public final class KeyExchange {

    public static final int PUBLIC_KEY_BYTES = 32;
    private static final String ALG = "X25519";

    private KeyExchange() {
    }

    public static KeyPair generateKeyPair() {
        try {
            return KeyPairGenerator.getInstance(ALG).generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new CryptoException("X25519 unavailable", e);
        }
    }

    /** Raw 32-byte little-endian u-coordinate, as used on the wire and by RFC 7748. */
    public static byte[] encodePublicKey(PublicKey key) {
        if (!(key instanceof XECPublicKey)) {
            throw new CryptoException("not an X25519 public key: " + key.getClass());
        }
        BigInteger u = ((XECPublicKey) key).getU();
        return toLittleEndian(u, PUBLIC_KEY_BYTES);
    }

    public static PublicKey decodePublicKey(byte[] raw) {
        if (raw == null || raw.length != PUBLIC_KEY_BYTES) {
            throw new CryptoException("X25519 public key must be " + PUBLIC_KEY_BYTES + " bytes");
        }
        try {
            BigInteger u = fromLittleEndianMasked(raw);
            KeyFactory kf = KeyFactory.getInstance(ALG);
            return kf.generatePublic(new XECPublicKeySpec(NamedParameterSpec.X25519, u));
        } catch (GeneralSecurityException e) {
            throw new CryptoException("cannot decode X25519 public key", e);
        }
    }

    /** ECDH shared secret (32 bytes). */
    public static byte[] agree(PrivateKey ourPrivate, PublicKey peerPublic) {
        try {
            KeyAgreement ka = KeyAgreement.getInstance(ALG);
            ka.init(ourPrivate);
            ka.doPhase(peerPublic, true);
            return ka.generateSecret();
        } catch (GeneralSecurityException e) {
            throw new CryptoException("X25519 agreement failed", e);
        }
    }

    private static byte[] toLittleEndian(BigInteger u, int len) {
        byte[] be = u.toByteArray(); // big-endian, possibly with a leading sign byte
        byte[] le = new byte[len];
        for (int i = 0; i < be.length && i < len; i++) {
            le[i] = be[be.length - 1 - i];
        }
        return le;
    }

    private static BigInteger fromLittleEndianMasked(byte[] raw) {
        byte[] le = raw.clone();
        le[le.length - 1] &= 0x7f; // RFC 7748: ignore the most-significant bit
        byte[] be = new byte[le.length];
        for (int i = 0; i < le.length; i++) {
            be[i] = le[le.length - 1 - i];
        }
        return new BigInteger(1, be);
    }
}
