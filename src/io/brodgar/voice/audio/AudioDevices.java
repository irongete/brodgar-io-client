package io.brodgar.voice.audio;

import io.brodgar.voice.Protocol;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;
import java.util.ArrayList;
import java.util.List;

/** Enumerates capture/playback devices usable at the voice format. */
public final class AudioDevices {

    private static final AudioFormat FORMAT =
            new AudioFormat(Protocol.SAMPLE_RATE, 16, Protocol.CHANNELS, true, false);

    private AudioDevices() {
    }

    public static final class Device {
        public final String name;
        public final String description;
        public final boolean capture;
        public final boolean playback;

        Device(String name, String description, boolean capture, boolean playback) {
            this.name = name;
            this.description = description;
            this.capture = capture;
            this.playback = playback;
        }
    }

    public static List<Device> list() {
        List<Device> out = new ArrayList<>();
        DataLine.Info capInfo = new DataLine.Info(TargetDataLine.class, FORMAT);
        DataLine.Info playInfo = new DataLine.Info(SourceDataLine.class, FORMAT);
        for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
            Mixer mixer = AudioSystem.getMixer(mi);
            boolean cap = mixer.isLineSupported(capInfo);
            boolean play = mixer.isLineSupported(playInfo);
            if (cap || play) {
                out.add(new Device(mi.getName(), mi.getDescription(), cap, play));
            }
        }
        return out;
    }
}
