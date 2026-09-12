package io.brodgar.voice.audio;

import io.brodgar.voice.Protocol;
import io.brodgar.voice.VoiceException;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;

/**
 * Default microphone capture via {@code javax.sound.sampled}:
 * 48 kHz, mono, 16-bit signed little-endian.
 */
public final class MicSource implements AudioSource {

    private static final AudioFormat FORMAT =
            new AudioFormat(Protocol.SAMPLE_RATE, 16, Protocol.CHANNELS, true, false);
    private static final int FRAME_BYTES = Protocol.FRAME_SAMPLES * 2;

    private final String deviceNameSubstring;
    private TargetDataLine line;
    private final byte[] bytes = new byte[FRAME_BYTES];

    /** Captures from the system default input device. */
    public MicSource() {
        this(null);
    }

    /** Captures from the first mixer whose name contains {@code deviceNameSubstring}. */
    public MicSource(String deviceNameSubstring) {
        this.deviceNameSubstring = deviceNameSubstring;
    }

    @Override
    public void start() throws VoiceException {
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, FORMAT);
        try {
            if (deviceNameSubstring == null) {
                line = (TargetDataLine) AudioSystem.getLine(info);
            } else {
                line = fromNamedMixer(info);
            }
            // Small line buffer (4 frames): bounded capture latency.
            line.open(FORMAT, FRAME_BYTES * 4);
            line.start();
        } catch (LineUnavailableException | IllegalArgumentException e) {
            throw new VoiceException("cannot open microphone at 48kHz mono 16-bit: " + e.getMessage(), e);
        }
    }

    private TargetDataLine fromNamedMixer(DataLine.Info info) throws LineUnavailableException, VoiceException {
        for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
            if (mi.getName().toLowerCase().contains(deviceNameSubstring.toLowerCase())) {
                Mixer mixer = AudioSystem.getMixer(mi);
                if (mixer.isLineSupported(info)) {
                    return (TargetDataLine) mixer.getLine(info);
                }
            }
        }
        throw new VoiceException("no capture device matching '" + deviceNameSubstring + "'");
    }

    @Override
    public int read(short[] frame) throws VoiceException {
        int off = 0;
        while (off < FRAME_BYTES) {
            int n;
            try {
                n = line.read(bytes, off, FRAME_BYTES - off);
            } catch (RuntimeException e) {
                return -1; // line closed during shutdown
            }
            if (n <= 0) {
                return -1;
            }
            off += n;
        }
        for (int i = 0; i < frame.length; i++) {
            frame[i] = (short) ((bytes[i * 2] & 0xFF) | (bytes[i * 2 + 1] << 8));
        }
        return frame.length;
    }

    @Override
    public void close() {
        if (line != null) {
            line.stop();
            line.close();
        }
    }
}
