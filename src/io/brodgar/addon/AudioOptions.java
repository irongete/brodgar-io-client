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
        LuaTable audio = new LuaTable();
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, methods(audio));
        audio.setmetatable(mt);
        return audio;
    }

    /** The live audio roots, or null before the UI exists. */
    private static ActAudio.Root audio() {
        UI u = AddonManager.ui;
        return (u == null) ? null : u.audio;
    }

    /** A volume argument, rejected outside 0..1 rather than silently clipped. */
    private static double volume(LuaValue value, String method) {
        double v = value.checkdouble();
        if((v < 0) || (v > 1))
            throw new LuaError("audio:" + method + "(v) — volume must be between 0.0 and 1.0 (got " + v + ")");
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
                ActAudio.Root a = audio();
                if(a != null)
                    a.sys.volume(volume(value, "masterVolume"));
            }
        });
        m.set("uiVolume", new OptionsMethod(handle, "audio:uiVolume") {
            protected LuaValue onRead() {
                ActAudio.Root a = audio();
                return (a == null) ? LuaValue.NIL : LuaValue.valueOf(a.aui.volume);
            }
            protected void onWrite(LuaValue value) {
                ActAudio.Root a = audio();
                if(a != null)
                    a.aui.setvolume(volume(value, "uiVolume"));
            }
        });
        m.set("eventVolume", new OptionsMethod(handle, "audio:eventVolume") {
            protected LuaValue onRead() {
                ActAudio.Root a = audio();
                return (a == null) ? LuaValue.NIL : LuaValue.valueOf(a.pos.volume);
            }
            protected void onWrite(LuaValue value) {
                ActAudio.Root a = audio();
                if(a != null)
                    a.pos.setvolume(volume(value, "eventVolume"));
            }
        });
        m.set("ambientVolume", new OptionsMethod(handle, "audio:ambientVolume") {
            protected LuaValue onRead() {
                ActAudio.Root a = audio();
                return (a == null) ? LuaValue.NIL : LuaValue.valueOf(a.amb.volume);
            }
            protected void onWrite(LuaValue value) {
                ActAudio.Root a = audio();
                if(a != null)
                    a.amb.setvolume(volume(value, "ambientVolume"));
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
                ActAudio.Root a = audio();
                if(a == null)
                    return;
                long samples = Math.round((value.checkdouble() * Audio.SAMPLE_RATE) / 1000.0);
                long min = 128, max = Math.round(Audio.SAMPLE_RATE / 4.0);
                if((samples < min) || (samples > max))
                    throw new LuaError("audio:latency(ms) — must be between "
                                       + ((min * 1000) / Audio.SAMPLE_RATE) + " and "
                                       + ((max * 1000) / Audio.SAMPLE_RATE) + " ms (got " + value.tojstring() + ")");
                a.sys.bufsize((int)samples);   // reopens the output line
            }
        });
        return m;
    }
}
