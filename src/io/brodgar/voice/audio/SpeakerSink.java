package io.brodgar.voice.audio;

import io.brodgar.voice.Protocol;
import io.brodgar.voice.VoiceException;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;

/**
 * Default speaker playback via {@code javax.sound.sampled}:
 * 48 kHz, <b>stereo</b>, 16-bit signed little-endian (the mixer spatializes into
 * two channels). The line's internal buffer (4 frames = 80 ms) paces the mixer.
 */
public final class SpeakerSink implements AudioSink {

    private static final int OUTPUT_CHANNELS = 2;
    private static final AudioFormat FORMAT =
            new AudioFormat(Protocol.SAMPLE_RATE, 16, OUTPUT_CHANNELS, true, false);
    private static final int FRAME_BYTES = Protocol.FRAME_SAMPLES * OUTPUT_CHANNELS * 2;

    private final String deviceNameSubstring;
    private SourceDataLine line;
    private final byte[] bytes = new byte[FRAME_BYTES];

    public SpeakerSink() {
        this(null);
    }

    public SpeakerSink(String deviceNameSubstring) {
        this.deviceNameSubstring = deviceNameSubstring;
    }

    @Override
    public void start() throws VoiceException {
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, FORMAT);
        try {
            if (deviceNameSubstring == null) {
                line = (SourceDataLine) AudioSystem.getLine(info);
            } else {
                line = fromNamedMixer(info);
            }
            line.open(FORMAT, FRAME_BYTES * 4);
            line.start();
        } catch (LineUnavailableException | IllegalArgumentException e) {
            throw new VoiceException("cannot open playback at 48kHz mono 16-bit: " + e.getMessage(), e);
        }
    }

    private SourceDataLine fromNamedMixer(DataLine.Info info) throws LineUnavailableException, VoiceException {
        for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
            if (mi.getName().toLowerCase().contains(deviceNameSubstring.toLowerCase())) {
                Mixer mixer = AudioSystem.getMixer(mi);
                if (mixer.isLineSupported(info)) {
                    return (SourceDataLine) mixer.getLine(info);
                }
            }
        }
        throw new VoiceException("no playback device matching '" + deviceNameSubstring + "'");
    }

    @Override
    public void write(short[] frame, int frames) {
        int shorts = frames * OUTPUT_CHANNELS;
        for (int i = 0; i < shorts; i++) {
            bytes[i * 2] = (byte) frame[i];
            bytes[i * 2 + 1] = (byte) (frame[i] >> 8);
        }
        int len = shorts * 2;
        int off = 0;
        while (off < len) {
            int n = line.write(bytes, off, len - off);
            if (n <= 0) {
                return; // the line was stopped or closed under us: a closed line writes nothing, forever
            }
            off += n;
        }
    }

    @Override
    public void close() {
        if (line != null) {
            line.stop();
            line.close();
        }
    }
}
