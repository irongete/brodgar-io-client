package io.brodgar.addon;

import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;

/**
 * {@code hafen.client()} and the Options handle behind {@code hafen.client():options()} (spec 018-client-options).
 * The handle groups the settings subsystems the client's Options window edits — {@code interface()},
 * {@code video()}, {@code audio()}, {@code camera()}, {@code client()}, {@code keybindings()} — one per OptWnd
 * panel, each returning its own handle
 * whose methods are read/write in one name: {@code scale()} reads, {@code scale(1.2)} writes and returns the
 * subsystem back, so writes chain.
 *
 * <p>Every handle here is a <b>stateless proxy</b> over the client's live preference stores
 * ({@code Utils.pref*}, {@link haven.GSettings}, the audio roots, the {@link haven.KeyBinding} registry): it
 * holds no value of its own, so a handle stashed by an addon never goes stale and never pins client state.
 * The keybindings subsystem is the one that needs the addon's identity (its hotkeys are namespaced and torn
 * down with it), which is why the whole tree is built per-addon.
 *
 * <p><b>Each of the eight is minted once per addon and handed back by identity</b> — held on the
 * {@link Addon} ({@link Addon#clientOpts} and the seven beside it), built on first use. So
 * {@code hafen.client():options() == hafen.client():options()}, {@code opts:video() == opts:video()}, a
 * handle works as a table key, and a HUD reading {@code opts:video():fpsLimit()} every frame allocates
 * nothing — {@link Section}'s contract, which every section in the API keeps. <b>Identity is not
 * caching</b>: a handle carries no value of its own, so every read still goes to the store.
 */
public final class OptionsHandle {
    private OptionsHandle() {}

    /**
     * Build {@code hafen.client()} for {@code owner}. From {@code installHafen}. This was the API's one
     * colon-on-the-namespace ({@code hafen.client:options()}) and is now a section like every other one: the
     * table is CALLED and both verbs are colon calls on what it hands back.
     */
    static void install(LuaTable hafen, final Addon owner) {
        LuaTable client = new LuaTable();
        client.set("options", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "client", "options");
                if(owner.clientOpts == null)
                    owner.clientOpts = create(owner);
                return owner.clientOpts;
            }
        });
        // hafen.client():profiling() — the profiling READ surface (spec 019). It hangs directly off the section,
        // not off options(): options() is settings, and a measurement is data. The switch that arms it is the
        // setting, and that one does live in the tree, at options():client():profiling().
        client.set("profiling", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "client", "profiling");
                if(owner.clientProfiling == null)   // per-owner: p:scope()/p:measure() charge the CALLING addon
                    owner.clientProfiling = ProfHandle.create(owner);
                return owner.clientProfiling;
            }
        });
        Section.install(hafen, "client", client,
                        "hafen.client:options() is now hafen.client():options()");
    }

    /**
     * The Options handle for {@code owner}: one accessor per Options panel. Called once, from the
     * {@code options} closure above, which then holds what it built.
     */
    static LuaValue create(final Addon owner) {
        LuaTable opts = new LuaTable();
        opts.set("interface", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                if(owner.clientInterface == null)
                    owner.clientInterface = InterfaceOptions.create();
                return owner.clientInterface;
            }
        });
        opts.set("video", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                if(owner.clientVideo == null)
                    owner.clientVideo = VideoOptions.create();
                return owner.clientVideo;
            }
        });
        opts.set("audio", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                if(owner.clientAudio == null)
                    owner.clientAudio = AudioOptions.create();
                return owner.clientAudio;
            }
        });
        opts.set("camera", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                if(owner.clientCamera == null)
                    owner.clientCamera = CameraOptions.create();
                return owner.clientCamera;
            }
        });
        opts.set("client", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                if(owner.clientClient == null)
                    owner.clientClient = ClientOptions.create();
                return owner.clientClient;
            }
        });
        opts.set("keybindings", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                if(owner.clientKeybindings == null)
                    owner.clientKeybindings = KeybindingsOptions.create(owner);
                return owner.clientKeybindings;
            }
        });
        return opts;
    }
}
