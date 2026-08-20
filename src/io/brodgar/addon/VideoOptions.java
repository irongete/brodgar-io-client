package io.brodgar.addon;

import haven.GSettings;
import haven.UI;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

/**
 * Video options subsystem (spec 018-client-options, task 018.1). Backs the graphics settings that
 * {@code OptWnd.CPanel} edits, over the same seam it uses: {@link UI#gprefs} to read, and
 * {@code ui.setgprefs(gprefs.update(null, setting, value))} to write — {@link GSettings} is immutable, so a
 * write swaps the whole object in and {@link UI} persists it on its next tick.
 *
 * <p>Framerate limits are {@code Float.POSITIVE_INFINITY} for "no limit" on both sides of the facade, so
 * {@code math.huge} round-trips verbatim. Lighting mode crosses as the lowercase enum name
 * ({@code "simple"} / {@code "zoned"}).
 */
public final class VideoOptions {
    private VideoOptions() {}

    /** Create the video options subsystem handle. */
    public static LuaValue create() {
        LuaTable video = new LuaTable();
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, Retired.closedIndex("video", methods(video),
            "the video options answer :shadows() :renderScale() :vsync() :fpsLimit() :bgFpsLimit()"
            + " :lightingMode() and :lightLimit(), each reading with no argument and writing with one"));
        video.setmetatable(mt);
        return video;
    }

    /** The live GSettings, or null before the UI exists. */
    private static GSettings prefs() {
        UI u = AddonManager.screen();
        return (u == null) ? null : u.gprefs;
    }

    /** Apply one setting: rebuild the GSettings and hand it to the UI (which validates + persists it). */
    private static <T> void apply(GSettings.Setting<T> setting, T val, String method) {
        UI u = AddonManager.screen();
        if(u == null)
            return;
        try {
            u.setgprefs(u.gprefs.update(null, setting, val));
        } catch(GSettings.SettingException e) {
            throw new LuaError("video:" + method + "(): " + e.getMessage());
        }
    }

    private static LuaTable methods(final LuaValue handle) {
        LuaTable m = new LuaTable();
        m.set("shadows", new OptionsMethod(handle, "video:shadows") {
            protected LuaValue onRead() {
                GSettings gs = prefs();
                return (gs == null) ? LuaValue.NIL : LuaValue.valueOf(gs.lshadow.val);
            }
            protected void onWrite(LuaValue value) {
                GSettings gs = prefs();
                if(gs != null)
                    apply(gs.lshadow, value.toboolean(), "shadows");
            }
        });
        m.set("renderScale", new OptionsMethod(handle, "video:renderScale") {
            protected LuaValue onRead() {
                GSettings gs = prefs();
                return (gs == null) ? LuaValue.NIL : LuaValue.valueOf((double)gs.rscale.val);
            }
            protected void onWrite(LuaValue value) {
                double v = num(value, "v", "the render resolution multiplier").todouble();
                GSettings gs = prefs();
                if(gs != null)
                    apply(gs.rscale, (float)v, "renderScale");
            }
        });
        m.set("vsync", new OptionsMethod(handle, "video:vsync") {
            protected LuaValue onRead() {
                GSettings gs = prefs();
                return (gs == null) ? LuaValue.NIL : LuaValue.valueOf(gs.vsync.val);
            }
            protected void onWrite(LuaValue value) {
                GSettings gs = prefs();
                if(gs != null)
                    apply(gs.vsync, value.toboolean(), "vsync");
            }
        });
        m.set("fpsLimit", new OptionsMethod(handle, "video:fpsLimit") {
            protected LuaValue onRead() {
                GSettings gs = prefs();
                return (gs == null) ? LuaValue.NIL : LuaValue.valueOf((double)gs.hz.val);
            }
            protected void onWrite(LuaValue value) {
                double v = num(value, "v", "frames per second, or math.huge for no limit").todouble();
                GSettings gs = prefs();
                if(gs != null)
                    apply(gs.hz, (float)v, "fpsLimit");
            }
        });
        m.set("bgFpsLimit", new OptionsMethod(handle, "video:bgFpsLimit") {
            protected LuaValue onRead() {
                GSettings gs = prefs();
                return (gs == null) ? LuaValue.NIL : LuaValue.valueOf((double)gs.bghz.val);
            }
            protected void onWrite(LuaValue value) {
                double v = num(value, "v", "frames per second, or math.huge for no limit").todouble();
                GSettings gs = prefs();
                if(gs != null)
                    apply(gs.bghz, (float)v, "bgFpsLimit");
            }
        });
        m.set("lightingMode", new OptionsMethod(handle, "video:lightingMode") {
            protected LuaValue onRead() {
                GSettings gs = prefs();
                return (gs == null) ? LuaValue.NIL : LuaValue.valueOf(gs.lightmode.val.name().toLowerCase());
            }
            protected void onWrite(LuaValue value) {
                String nm = str(value, "mode", "\"simple\" or \"zoned\"").tojstring().toUpperCase();
                GSettings.LightMode mode;
                try {
                    mode = GSettings.LightMode.valueOf(nm);
                } catch(IllegalArgumentException e) {
                    throw new LuaError("video:lightingMode(mode) — mode must be \"simple\" or \"zoned\" (got \""
                                       + value.tojstring() + "\")");
                }
                GSettings gs = prefs();
                if(gs == null)
                    return;
                apply(gs.lightmode, mode, "lightingMode");
            }
        });
        m.set("lightLimit", new OptionsMethod(handle, "video:lightLimit") {
            protected LuaValue onRead() {
                GSettings gs = prefs();
                return (gs == null) ? LuaValue.NIL : LuaValue.valueOf((double)gs.maxlights.val);
            }
            protected void onWrite(LuaValue value) {
                double v = num(value, "n", "how many dynamic lights at once").todouble();
                GSettings gs = prefs();
                if(gs != null)
                    apply(gs.maxlights, (int)v, "lightLimit");
            }
        });
        return m;
    }
}
