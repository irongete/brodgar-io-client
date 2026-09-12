package io.brodgar.voice.crypto;

/**
 * Unchecked wrapper for a cryptographic setup or sealing failure (a missing JCA
 * algorithm, or a local encrypt failing). Failing to <em>open</em>
 * attacker-supplied data is expected instead and is signalled with a checked
 * {@link java.security.GeneralSecurityException}, which callers handle by
 * dropping the packet.
 */
public final class CryptoException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CryptoException(String message, Throwable cause) {
        super(message, cause);
    }

    public CryptoException(String message) {
        super(message);
    }
}
