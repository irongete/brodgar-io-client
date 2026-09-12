package io.brodgar.voice;

/** Any library-level failure: connection, audio device, codec. */
public class VoiceException extends Exception {

    private static final long serialVersionUID = 1L;

    public VoiceException(String message) {
        super(message);
    }

    public VoiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
