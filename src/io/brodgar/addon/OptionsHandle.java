package io.brodgar.addon;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
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
 *
 * <p><b>Each of the eight is userdata with a closed vocabulary</b> ({@link #open}/{@link #close}), the one
 * shape every handle in the API has. So {@code opts:vidoe()} raises naming the six panels rather than
 * reading {@code nil} and failing one call later as <i>attempt to call a nil value</i>, {@code opts.video
 * = nil} is refused where a table would have let an addon delete its own way in, and {@code tostring(opts)}
 * is {@code Options} — a log line of an addon's own handles says something.
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
        // hafen.client():stepping() — WHERE THE CODE YOU ARE IN IS RUNNING (112.3). True on the client's own
        // step, which is the pump that fires Update, runs the timers and delivers the seams that queue: the one
        // place in a frame that holds no widget-tree monitor, and therefore the only one from which a handler
        // may reach a tree other than the one it was given. False everywhere else — a draw, a press, a drop, a
        // gesture, an inbound message — where a reach across trees is refused. A read, so unprotected.
        client.set("stepping", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "client", "stepping");
                if(a.narg() > 1)
                    throw new LuaError("hafen.client():stepping() takes no arguments — it answers whether the"
                        + " code you are in is running on the client's step");
                return LuaValue.valueOf(AddonManager.onStep());
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
        LuaValue opts = open("Options");
        LuaTable m = new LuaTable();
        m.set("interface", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                if(owner.clientInterface == null)
                    owner.clientInterface = InterfaceOptions.create(owner);
                return owner.clientInterface;
            }
        });
        m.set("video", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                if(owner.clientVideo == null)
                    owner.clientVideo = VideoOptions.create(owner);
                return owner.clientVideo;
            }
        });
        m.set("audio", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                if(owner.clientAudio == null)
                    owner.clientAudio = AudioOptions.create(owner);
                return owner.clientAudio;
            }
        });
        m.set("camera", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                if(owner.clientCamera == null)
                    owner.clientCamera = CameraOptions.create(owner);
                return owner.clientCamera;
            }
        });
        m.set("client", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                if(owner.clientClient == null)
                    owner.clientClient = ClientOptions.create(owner);
                return owner.clientClient;
            }
        });
        m.set("keybindings", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                if(owner.clientKeybindings == null)
                    owner.clientKeybindings = KeybindingsOptions.create(owner);
                return owner.clientKeybindings;
            }
        });
        return close(opts, "options", m,
            "the options handle", "one panel of the client's Options window per verb");
    }

    /**
     * The opaque instance behind an options userdata (facade-safe: no Java object of the client's crosses).
     * A handle here holds no value of its own, so the one thing it carries is what it prints as.
     */
    private static final class Mark {
        private final String print;

        Mark(String print) {
            this.print = print;
        }

        public String toString() {
            return print;
        }
    }

    /**
     * <b>Mint one options handle</b>, its vocabulary not yet closed: {@code print} is what
     * {@code tostring()} answers ({@code Options}, {@code Options(video)}). The two halves are separate
     * because a panel's methods table is built <i>over the handle</i> — an option written with an argument
     * hands the handle back so writes chain — so the value has to exist before the verbs that answer for
     * it. {@link #close} finishes it.
     */
    static LuaValue open(String print) {
        return LuaValue.userdataOf(new Mark(print));
    }

    /**
     * <b>Close {@code h}'s vocabulary</b>: {@code methods} is all it answers, {@code entity} names it in a
     * refusal and {@code hint} is what it does answer, spelled for the author. Everything else raises — a
     * moved spelling with its replacement, any other name with {@code hint} — and, being userdata, so
     * does a write.
     */
    static LuaValue close(LuaValue h, String entity, LuaTable methods, String blurb, String note) {
        final String print = String.valueOf(h.touserdata());
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, Refusal.closedIndex(entity, methods, blurb, note));
        mt.set("__name", LuaValue.valueOf("Options"));
        mt.set("__tostring", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(print);
            }
        });
        h.setmetatable(mt);
        return h;
    }
}
