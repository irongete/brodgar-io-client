package io.brodgar.addon;

import haven.ActAudio;
import haven.Audio;
import haven.UI;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

/**
 * Audio options subsystem (spec 018-client-options, task 018.1) — the settings on {@code OptWnd}'s Audio
 * panel, over the same objects it drives: the master volume on {@link Audio.Root} (which persists itself to
 * the {@code sfxvol} pref) and the three {@link ActAudio.RootChannel} sub-mixes for interface, in-game event
 * and ambient sound.
 *
 * <p>Volumes are {@code 0.0 .. 1.0} here, not the panel's 0..1000 slider units. Latency crosses as
 * milliseconds (what the panel displays) rather than the sample count the engine stores.
 *
 * <p>Every method answers {@code nil} before the UI exists — the audio roots are built with it.
 */
public final class AudioOptions {
    private AudioOptions() {}

    /** Create the audio options subsystem handle. */
    public static LuaValue create() {
        LuaValue audio = OptionsHandle.open("Options(audio)");
        return OptionsHandle.close(audio, "audio", methods(audio),
            "the audio options answer :masterVolume() :uiVolume() :eventVolume() :ambientVolume() and"
            + " :latency(), each reading with no argument and writing with one");
    }

    /** The live audio roots, or null before the UI exists. */
    private static ActAudio.Root audio() {
        UI u = AddonManager.screen();
        return (u == null) ? null : u.audio;
    }

    /** A volume, refused outside 0..1 rather than silently clipped. The TYPE is refused first, by the
     *  caller's {@code num} — so "loud" names a number and 2.0 goes on naming the range. */
    private static double volume(double v, String verb) {
        if((v < 0) || (v > 1))
            throw new LuaError(verb + "(v) — volume must be between 0.0 and 1.0 (got " + v + ")");
        return v;
    }

    private static LuaTable methods(final LuaValue handle) {
        LuaTable m = new LuaTable();
        m.set("masterVolume", new OptionsMethod(handle, "audio:masterVolume") {
            protected LuaValue onRead() {
                ActAudio.Root a = audio();
                return (a == null) ? LuaValue.NIL : LuaValue.valueOf(a.sys.volume());
            }
            protected void onWrite(LuaValue value) {
                double v = volume(num(value, "v", "a volume from 0.0 to 1.0").todouble(), verb());
                ActAudio.Root a = audio();
                if(a != null)
                    a.sys.volume(v);
            }
        });
        m.set("uiVolume", new OptionsMethod(handle, "audio:uiVolume") {
            protected LuaValue onRead() {
                ActAudio.Root a = audio();
                return (a == null) ? LuaValue.NIL : LuaValue.valueOf(a.aui.volume);
            }
            protected void onWrite(LuaValue value) {
                double v = volume(num(value, "v", "a volume from 0.0 to 1.0").todouble(), verb());
                ActAudio.Root a = audio();
                if(a != null)
                    a.aui.setvolume(v);
            }
        });
        m.set("eventVolume", new OptionsMethod(handle, "audio:eventVolume") {
            protected LuaValue onRead() {
                ActAudio.Root a = audio();
                return (a == null) ? LuaValue.NIL : LuaValue.valueOf(a.pos.volume);
            }
            protected void onWrite(LuaValue value) {
                double v = volume(num(value, "v", "a volume from 0.0 to 1.0").todouble(), verb());
                ActAudio.Root a = audio();
                if(a != null)
                    a.pos.setvolume(v);
            }
        });
        m.set("ambientVolume", new OptionsMethod(handle, "audio:ambientVolume") {
            protected LuaValue onRead() {
                ActAudio.Root a = audio();
                return (a == null) ? LuaValue.NIL : LuaValue.valueOf(a.amb.volume);
            }
            protected void onWrite(LuaValue value) {
                double v = volume(num(value, "v", "a volume from 0.0 to 1.0").todouble(), verb());
                ActAudio.Root a = audio();
                if(a != null)
                    a.amb.setvolume(v);
            }
        });

        // latency() — the output buffer, in MILLISECONDS (what the panel displays); the engine stores it as a
        // sample count. The panel's slider bounds are the real limits: below 128 samples the line will not
        // open, and a quarter-second buffer is as laggy as the client allows.
        m.set("latency", new OptionsMethod(handle, "audio:latency") {
            protected LuaValue onRead() {
                ActAudio.Root a = audio();
                if(a == null)
                    return LuaValue.NIL;
                return LuaValue.valueOf((a.sys.bufsize() * 1000.0) / Audio.SAMPLE_RATE);
            }
            protected void onWrite(LuaValue value) {
                double ms = num(value, "ms", "the output buffer in milliseconds").todouble();
                long samples = Math.round((ms * Audio.SAMPLE_RATE) / 1000.0);
                long min = 128, max = Math.round(Audio.SAMPLE_RATE / 4.0);
                if((samples < min) || (samples > max))
                    throw new LuaError("audio:latency(ms) — must be between "
                                       + ((min * 1000) / Audio.SAMPLE_RATE) + " and "
                                       + ((max * 1000) / Audio.SAMPLE_RATE) + " ms (got " + value.tojstring() + ")");
                ActAudio.Root a = audio();
                if(a == null)
                    return;
                a.sys.bufsize((int)samples);   // reopens the output line
            }
        });
        return m;
    }
}
