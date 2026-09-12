package io.brodgar.voice;

/** Malformed, out-of-limits or unknown wire data (JSON message or UDP packet). */
public class ProtocolException extends Exception {

    private static final long serialVersionUID = 1L;

    public ProtocolException(String message) {
        super(message);
    }

    public ProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
