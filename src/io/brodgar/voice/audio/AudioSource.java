package io.brodgar.voice.audio;

import io.brodgar.voice.VoiceException;

/**
 * Supplier of 48 kHz mono 16-bit PCM frames. The default implementation is
 * the microphone ({@link MicSource}); tests and custom clients plug their own.
 *
 * <p>{@link #read(short[])} must BLOCK until a full frame is available — it is
 * the pacing clock of the transmit pipeline.
 */
public interface AudioSource extends AutoCloseable {

    void start() throws VoiceException;

    /**
     * Fills {@code frame} completely (960 samples = 20 ms) and returns the
     * sample count, or -1 on end of stream.
     */
    int read(short[] frame) throws VoiceException;

    @Override
    void close();
}
