package io.brodgar.addon;

import haven.MapView;
import haven.Utils;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

/**
 * Interface options subsystem (spec 018-client-options, task 018.1) — the settings on {@code OptWnd}'s
 * Interface panel: UI scale and the two fine-placement granularities.
 *
 * <p>Each write mirrors what the panel's slider does: the granularities live in {@code static} fields on
 * {@link MapView} that the placement code reads every frame, with {@code Utils.setprefd} only persisting them,
 * so <b>both</b> must be written or the change either does not apply or does not survive a restart. UI scale
 * has no live field — it is read once at startup, which is why it takes a restart.
 *
 * <p>Angle granularity crosses the facade as <b>degrees</b> (what the panel displays), while
 * {@link MapView#plobagran} stores the divisor behind {@code π/plobagran}: {@code degrees = 180 / plobagran}
 * in both directions.
 */
public final class InterfaceOptions {
    private InterfaceOptions() {}

    /** Create the interface options subsystem handle. */
    public static LuaValue create(final Addon owner) {
        LuaValue iface = OptionsHandle.open("Options(interface)");
        return OptionsHandle.close(iface, "interface", methods(owner, iface),
            "the interface options", "each of them reads with no argument and writes with one");
    }

    private static LuaTable methods(final Addon owner, final LuaValue handle) {
        LuaTable m = new LuaTable();

        // scale() — the "Interface scale (requires restart)" slider. Persisted only; UI.scale reads it at
        // startup, so a write takes effect on the next client launch (matching the panel's own label).
        m.set("scale", new OptionsMethod(owner, handle, "interface:scale") {
            protected LuaValue onRead() {
                return LuaValue.valueOf(Utils.getprefd("uiscale", 1.0));
            }
            protected void onWrite(LuaValue value) {
                double v = num(value, "v", "1.0 is the display's own pixels").todouble();
                if(!(v > 0))
                    throw new LuaError("interface:scale(v) — scale must be positive (got " + v + ")");
                Utils.setprefd("uiscale", v);
            }
        });

        // posGran() — object fine-placement position granularity: subdivisions per tile, 2..17, or 0 for
        // "infinite" (the panel's ∞, i.e. unsnapped placement). Applies live.
        m.set("posGran", new OptionsMethod(owner, handle, "interface:posGran") {
            protected LuaValue onRead() {
                return LuaValue.valueOf(MapView.plobpgran);
            }
            protected void onWrite(LuaValue value) {
                double v = num(value, "v", "subdivisions per tile, or 0 for unsnapped").todouble();
                if(v < 0)
                    throw new LuaError("interface:posGran(v) — expected 0 (infinite) or a positive number (got " + v + ")");
                Utils.setprefd("plobpgran", MapView.plobpgran = v);
            }
        });

        // angGran() — object fine-placement angle granularity, in DEGREES per step. Applies live.
        m.set("angGran", new OptionsMethod(owner, handle, "interface:angGran") {
            protected LuaValue onRead() {
                return LuaValue.valueOf(180.0 / MapView.plobagran);
            }
            protected void onWrite(LuaValue value) {
                double deg = num(value, "degrees", "degrees per step, not the divisor").todouble();
                if(!(deg > 0))
                    throw new LuaError("interface:angGran(degrees) — degrees must be positive (got " + deg + ")");
                Utils.setprefd("plobagran", MapView.plobagran = (180.0 / deg));
            }
        });
        return m;
    }
}
