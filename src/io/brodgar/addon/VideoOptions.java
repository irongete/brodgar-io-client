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
 *
 * <p><b>A write reaches EVERY tree</b> (092.6, A-091). {@code hafen.client()} is the client, not a character:
 * there is no session in this address to be right or wrong about. But {@link UI#gprefs} is per {@code UI},
 * and until 092 a write moved the drawn one alone — so with two sessions up a graphics setting changed one
 * scene and left the other at whatever it loaded, and since both persist to the one {@code gconf/*} store,
 * which value survived the next client start was whichever tree published last. Now the write walks every
 * live tree, so the setting is one setting and the read answers it whoever holds the screen.
 *
 * <p>The <b>read</b> stays on the drawn tree, and that is not an asymmetry: after a write the trees agree,
 * and before any write they were loaded from the same store. Reading the one being drawn is reading the one
 * the user can see the effect of.
 */
public final class VideoOptions {
    private VideoOptions() {}

    /** Create the video options subsystem handle. */
    public static LuaValue create() {
        LuaValue video = OptionsHandle.open("Options(video)");
        return OptionsHandle.close(video, "video", methods(video),
            "the video options answer :shadows() :renderScale() :vsync() :fpsLimit() :bgFpsLimit()"
            + " :lightingMode() and :lightLimit(), each reading with no argument and writing with one");
    }

    /** The live GSettings, or null before the UI exists. */
    private static GSettings prefs() {
        UI u = AddonManager.screen();
        return (u == null) ? null : u.gprefs;
    }

    /**
     * Apply one setting to <b>every live tree</b>: rebuild each one's {@link GSettings} and hand it to that
     * {@link UI}, which validates and persists it (092.6).
     *
     * <p>The drawn tree goes first, so a value the setting refuses is refused before anything has moved —
     * every tree holds the same setting object and validates identically, so the first answer is the answer.
     * A tree that goes between the walk and the write is skipped rather than aborting the others: the write
     * is one setting reaching every scene, and a scene that has ended has nothing to be wrong about.
     */
    private static <T> void apply(GSettings.Setting<T> setting, T val, String method) {
        UI drawn = AddonManager.screen();
        if(drawn != null)
            set(drawn, setting, val, method);
        for(AddonManager.SessionState st : AddonManager.allStates()) {
            if(st.ui != drawn)
                set(st.ui, setting, val, method);
        }
    }

    /** One tree's half of {@link #apply}. */
    private static <T> void set(UI u, GSettings.Setting<T> setting, T val, String method) {
        if((u == null) || u.destroyed)
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
