package io.brodgar.voice.audio;

import io.brodgar.voice.VoiceException;

/**
 * Consumer of the mixed 48 kHz 16-bit playback signal. Output is <b>stereo</b>
 * (interleaved L,R); capture stays mono. The default implementation is the
 * speakers ({@link SpeakerSink}); tests and custom clients plug their own.
 *
 * <p>{@link #write(short[], int)} must BLOCK to pace the mixer at real-time
 * audio rate (the sink is the playback clock).
 */
public interface AudioSink extends AutoCloseable {

    void start() throws VoiceException;

    /**
     * @param frame interleaved stereo samples (L, R, L, R, …)
     * @param frames number of stereo frames; {@code frame} holds {@code 2*frames} shorts
     */
    void write(short[] frame, int frames) throws VoiceException;

    @Override
    void close();
}
