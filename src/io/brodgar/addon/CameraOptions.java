package io.brodgar.addon;

import haven.MapView;
import haven.Utils;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

/**
 * Camera options subsystem (spec 018-client-options, task 018.1) — the camera-drag inversion toggles.
 *
 * <p>These are the {@code invcamx}/{@code invcamy} preferences behind {@link MapView#invcamx} /
 * {@link MapView#invcamy}, which {@code MapView.Camera.invdx}/{@code invdy} consult on every drag. The write
 * is byte-for-byte what {@code OptWnd.CameraPanel}'s checkboxes do — live field <b>and</b> pref in one
 * statement — so a write from Lua and a click in the Options window are indistinguishable, and the panel shows
 * the new state the next time it is opened.
 */
public final class CameraOptions {
    private CameraOptions() {}

    /** Create the camera options subsystem handle. */
    public static LuaValue create() {
        LuaTable camera = new LuaTable();
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, methods(camera));
        camera.setmetatable(mt);
        return camera;
    }

    private static LuaTable methods(final LuaValue handle) {
        LuaTable m = new LuaTable();
        m.set("invertHorizontal", new OptionsMethod(handle) {
            protected LuaValue onRead() {
                return LuaValue.valueOf(MapView.invcamx);
            }
            protected void onWrite(LuaValue value) {
                Utils.setprefb("invcamx", MapView.invcamx = value.toboolean());
            }
        });
        m.set("invertVertical", new OptionsMethod(handle) {
            protected LuaValue onRead() {
                return LuaValue.valueOf(MapView.invcamy);
            }
            protected void onWrite(LuaValue value) {
                Utils.setprefb("invcamy", MapView.invcamy = value.toboolean());
            }
        });
        return m;
    }
}
