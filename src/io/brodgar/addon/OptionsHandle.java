package io.brodgar.addon;

import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;

/**
 * {@code hafen.client} and the Options handle behind {@code hafen.client:options()} (spec 018-client-options).
 * The handle groups the settings subsystems the client's Options window edits — {@code interface()},
 * {@code video()}, {@code audio()}, {@code camera()}, {@code client()}, {@code keybindings()} — one per OptWnd
 * panel, each returning its own handle
 * whose methods are read/write in one name: {@code scale()} reads, {@code scale(1.2)} writes and returns the
 * subsystem back, so writes chain.
 *
 * <p>Nothing is cached: the subsystem handles are stateless proxies over the client's live preference stores
 * ({@code Utils.pref*}, {@link haven.GSettings}, the audio roots, the {@link haven.KeyBinding} registry), so a
 * handle stashed by an addon never goes stale and never pins client state. The keybindings subsystem is the
 * one that needs the addon's identity (its hotkeys are namespaced and torn down with it), which is why the
 * whole tree is built per-addon.
 */
public final class OptionsHandle {
    private OptionsHandle() {}

    /** Build {@code hafen.client} for {@code owner}. From {@code installHafen}. */
    static void install(LuaTable hafen, final Addon owner) {
        LuaTable client = new LuaTable();
        client.set("options", new VarArgFunction() {
            public Varargs invoke(Varargs a) {   // colon call: a.arg(1) is hafen.client itself
                return create(owner);
            }
        });
        // hafen.client:profiling() — the profiling READ surface (spec 019). It hangs directly off hafen.client,
        // not off options(): options() is settings, and a measurement is data. The switch that arms it is the
        // setting, and that one does live in the tree, at options():client():profiling().
        client.set("profiling", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                return ProfHandle.create();
            }
        });
        hafen.set("client", client);
    }

    /** A fresh Options handle for {@code owner}: one accessor per Options panel. */
    static LuaValue create(final Addon owner) {
        LuaTable opts = new LuaTable();
        opts.set("interface", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                return InterfaceOptions.create();
            }
        });
        opts.set("video", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                return VideoOptions.create();
            }
        });
        opts.set("audio", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                return AudioOptions.create();
            }
        });
        opts.set("camera", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                return CameraOptions.create();
            }
        });
        opts.set("client", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                return ClientOptions.create();
            }
        });
        opts.set("keybindings", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                return KeybindingsOptions.create(owner);
            }
        });
        return opts;
    }
}
