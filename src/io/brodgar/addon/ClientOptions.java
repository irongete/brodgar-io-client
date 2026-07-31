package io.brodgar.addon;

import io.brodgar.prof.Prof;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

/**
 * Client options subsystem (spec 019-profiling, task 019.1) — {@code hafen.client:options():client()}, the Lua
 * side of the new Options → <b>Client</b> panel ({@link io.brodgar.ui.ClientPanel}). This class is the
 * <b>addon surface</b> over {@link Prof}, which is a client feature in its own package: the handle lives here,
 * the engine does not.
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
    public static LuaValue create() {
        LuaTable client = new LuaTable();
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, methods(client));
        client.setmetatable(mt);
        return client;
    }

    private static LuaTable methods(final LuaValue handle) {
        LuaTable m = new LuaTable();
        m.set("profiling", new OptionsMethod(handle) {
            protected LuaValue onRead() {
                return LuaValue.valueOf(Prof.armed());
            }
            protected void onWrite(LuaValue value) {
                Prof.arm(value.toboolean());
            }
        });
        return m;
    }
}
