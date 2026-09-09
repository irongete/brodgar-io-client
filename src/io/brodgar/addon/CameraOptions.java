package io.brodgar.addon;

import haven.MapView;
import haven.Utils;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

/**
 * Camera options subsystem (spec 018-client-options, task 018.1) — the camera in force and the camera-drag
 * inversion toggles.
 *
 * <p>The inversions are the {@code invcamx}/{@code invcamy} preferences behind {@link MapView#invcamx} /
 * {@link MapView#invcamy}, which {@code MapView.Camera.invdx}/{@code invdy} consult on every drag. The write
 * is byte-for-byte what {@code OptWnd.CameraPanel}'s checkboxes do — live field <b>and</b> pref in one
 * statement — so a write from Lua and a click in the Options window are indistinguishable, and the panel shows
 * the new state the next time it is opened.
 *
 * <p>{@code mode} (spec 066) is the same deal one level up: it reads {@link MapView#camname()} — the camera
 * actually installed, not the {@code defcam} preference, which the RTS mode leaves alone while it holds a
 * camera of its own — and writes through {@link MapView#setcam}, the one writer the {@code :cam} command and
 * the Options ▸ Camera dropdown also end in.
 */
public final class CameraOptions {
    private CameraOptions() {}

    /** Create the camera options subsystem handle. */
    public static LuaValue create(final Addon owner) {
        LuaValue camera = OptionsHandle.open("Options(camera)");
        return OptionsHandle.close(camera, "camera", methods(owner, camera),
            "the camera options", "each of them reads with no argument and writes with one");
    }

    private static LuaTable methods(final Addon owner, final LuaValue handle) {
        LuaTable m = new LuaTable();
        m.set("invertHorizontal", new OptionsMethod(owner, handle, "camera:invertHorizontal") {
            protected LuaValue onRead() {
                return LuaValue.valueOf(MapView.invcamx);
            }
            protected void onWrite(LuaValue value) {
                Utils.setprefb("invcamx", MapView.invcamx = bool(value, "on", "whether dragging left"
                                                                 + " turns the camera right"));
            }
        });
        m.set("invertVertical", new OptionsMethod(owner, handle, "camera:invertVertical") {
            protected LuaValue onRead() {
                return LuaValue.valueOf(MapView.invcamy);
            }
            protected void onWrite(LuaValue value) {
                Utils.setprefb("invcamy", MapView.invcamy = bool(value, "on", "whether dragging up"
                                                                 + " tilts the camera down"));
            }
        });
        m.set("mode", new OptionsMethod(owner, handle, "camera:mode") {
            protected LuaValue onRead() {
                /* camname() off the live view, and the pref only when there is none -- the mode swaps a
                 * camera in without writing defcam, so before login the pref is all there is and after it
                 * the pref is the one thing that does not say what is on screen. A pref naming a camera the
                 * registry does not have reads nil rather than that dead name: restorecam silently comes up
                 * on ortho for it, so the name answers for nothing. Same rule as the dropdown's current(). */
                MapView mv = AddonManager.screenView();
                String nm = (mv == null) ? Utils.getpref("defcam", null) : mv.camname();
                for(String cand : MapView.camnames()) {
                    if(cand.equals(nm))
                        return LuaValue.valueOf(cand);
                }
                return LuaValue.NIL;
            }
            protected void onWrite(LuaValue value) {
                String nm = str(value, "name", "one of the cameras the client has").tojstring();
                /* Checked HERE rather than left to setcam, so the refusal is the same one whether or not a
                 * view exists -- with none, the write below never reaches setcam's own check at all. */
                if(!MapView.camnames().contains(nm))
                    throw new LuaError("camera:mode(name) — no such camera: \"" + nm + "\" — the client has "
                                       + String.join(", ", MapView.camnames()));
                MapView mv = AddonManager.screenView();
                if(mv != null) {
                    mv.setcam(nm);
                } else {
                    /* No map view to install onto, exactly as at the login screen: write the preference
                     * alone and the session that comes up reads it through restorecam. Both prefs, since
                     * setcam writes both and a stale camargs would be handed to the wrong camera. */
                    Utils.setpref("defcam", nm);
                    Utils.setprefb("camargs", Utils.serialize(new String[0]));
                }
            }
        });
        return m;
    }
}
