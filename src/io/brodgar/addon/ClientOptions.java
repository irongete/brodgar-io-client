package io.brodgar.addon;

import haven.MapView;
import haven.Utils;
import io.brodgar.prof.Prof;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

/**
 * Client options subsystem (spec 019-profiling, task 019.1) — {@code hafen.client:options():client()}, the Lua
 * side of the Options → <b>Client</b> panel ({@link io.brodgar.ui.ClientPanel}). This class is the
 * <b>addon surface</b> over client features that live in packages of their own: {@link Prof}, and the
 * remembered ground's three statics on {@link haven.MapView}. The handle lives here, the engine does not.
 *
 * <p>One OptWnd panel is one options subsystem — the shape 018 already ships for
 * {@code interface}/{@code video}/{@code audio}/{@code camera}/{@code keybindings} — which is why the switch
 * lives here and not directly on {@code hafen.client}. Arity is the verb, as everywhere else in the options
 * tree: {@code client:profiling()} reads, {@code client:profiling(true)} writes and answers the handle so
 * writes chain.
 *
 * <p>The write goes through {@link Prof#arm}, exactly the call the panel's checkbox makes, so a Lua write and a
 * click are indistinguishable: both flip the live switch, persist the pref and arm the client's own profile
 * machinery. The panel re-reads {@link Prof#on} every frame, so a Lua write moves an <b>open</b> panel's
 * checkbox with no event plumbing.
 */
public final class ClientOptions {
    private ClientOptions() {}

    /** Create the client options subsystem handle. */
    public static LuaValue create(final Addon owner) {
        LuaValue client = OptionsHandle.open("Options(client)");
        return OptionsHandle.close(client, "client", methods(owner, client),
            "the client options", "each of them reads with no argument and writes with one");
    }

    private static LuaTable methods(final Addon owner, final LuaValue handle) {
        LuaTable m = new LuaTable();
        m.set("profiling", new OptionsMethod(owner, handle, "client:profiling") {
            protected LuaValue onRead() {
                return LuaValue.valueOf(Prof.armed());
            }
            protected void onWrite(LuaValue value) {
                Prof.arm(bool(value, "on", "whether the profiler collects frames"));
            }
        });

        // recall() — whether the remembered ground is drawn at all. The live field and its pref go in one
        // statement, exactly as the panel's checkbox writes them, so a Lua write and a click are the same act.
        m.set("recall", new OptionsMethod(owner, handle, "client:recall") {
            protected LuaValue onRead() {
                return LuaValue.valueOf(MapView.recallon);
            }
            protected void onWrite(LuaValue value) {
                Utils.setprefb("recallon", MapView.recallon = bool(value, "on",
                                                                  "whether remembered ground is drawn"));
            }
        });

        // recallRange() — the drawn reach in grids. The bounds are MapView's own, stated once there and read
        // here, so the slider, the field's clamp and this refusal cannot drift apart. A range is a whole
        // number of grids: the refusal names the bounds AND says the value must be one of them, because a
        // fractional range written and read back as something else is a write that quietly did not happen.
        m.set("recallRange", new OptionsMethod(owner, handle, "client:recallRange") {
            protected LuaValue onRead() {
                return LuaValue.valueOf(MapView.recallrange);
            }
            protected void onWrite(LuaValue value) {
                double v = num(value, "grids", "how far around the camera, in grids").todouble();
                if((v != Math.floor(v)) || (v < MapView.recallrangemin) || (v > MapView.recallrangemax))
                    throw new LuaError("client:recallRange(grids) — a whole number of grids from "
                                       + MapView.recallrangemin + " to " + MapView.recallrangemax
                                       + " (got " + v + ")");
                Utils.setprefi("recallrange", MapView.recallrange = (int)v);
            }
        });

        // recallGrey() — whether remembered ground is drawn without colour. A switch and not an amount: an
        // off state and a wash of zero would be two spellings of one setting.
        m.set("recallGrey", new OptionsMethod(owner, handle, "client:recallGrey") {
            protected LuaValue onRead() {
                return LuaValue.valueOf(MapView.recallgrey);
            }
            protected void onWrite(LuaValue value) {
                Utils.setprefb("recallgrey", MapView.recallgrey = bool(value, "on",
                                                                      "whether it is drawn without colour"));
            }
        });
        return m;
    }
}
