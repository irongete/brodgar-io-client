package io.brodgar.addon;

import haven.ActAudio;
import haven.Audio;
import haven.Glob;
import haven.Indir;
import haven.Loading;
import haven.Resource;
import haven.UI;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.VarArgFunction;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A <b>Sound object</b> — one client sound effect, addressed by its <b>resource name</b> (spec
 * {@code 024-audio-oop}), the OOP successor of the flat fire-and-forget {@code hafen.sound.play(res)}. Built on
 * exactly the mechanism {@link LuaGob} (017), {@link LuaKin} (020), {@link LuaSlot} (021) and
 * {@link LuaPagina} (023) established; <b>the section object IS the collection</b> (uniform grammar §2.1):
 * {@code hafen.sound():get(name)} is one Sound and {@code hafen.sound():list()} is what this addon still has
 * in the air.
 *
 * <p><b>The key is a resource name and nothing else</b> — {@code "sfx/msg"} — and, unlike every prior section,
 * there is <b>no catalogue behind it</b>: sound resources are not enumerable, so a Sound simply <i>exists on
 * demand</i>. For the same reason it carries <b>no {@code :exists()}</b>: elsewhere that method answers a
 * <i>staleness</i> question about an entity with a lifetime (a gob despawns, a slot is cleared), and a
 * resource name has no lifetime to go stale. Whether the name resolves is answered by the loader, later and
 * off this thread — a bogus name is simply silent.
 *
 * <p><b>Volume is an argument of {@code :play}, not entity state.</b> The Sound is interned and shared, so a
 * stored volume would leak between unrelated uses of the same clip; {@code :play(0.2)} is one quiet blip and
 * nothing else. Omitted ⇒ {@code 1.0}; outside {@code 0..1} it errors naming the method
 * ({@code AudioOptions}' convention). {@code :play()} returns <b>self</b> so it chains.
 *
 * <p><b>The play path is the client's own</b> (wrap-not-reimplement, D-009), and it is <b>deferred</b>:
 * {@code Resource.local().load(name)} hands back an {@link Indir} and {@code get()} throws {@code Loading}
 * until the resource is cached, so — exactly like {@code MapView.Plob} and {@link LuaGhost} — the resolve runs
 * on a loader thread ({@code glob.loader.defer} re-runs the task when the resource lands) and never throws
 * {@code Loading} into Lua. The resolved clip goes to {@link UI#sfx} on <b>the addon layer's</b>
 * {@code UI} — the {@code ActAudio.Root.aui} channel, the same one the client's own blips use in each
 * session's tree — wrapped in an {@link Audio.VolAdjust} when the volume is not 1. The layer rather than the
 * drawn session because a background session is muted every frame and the addon is not a background
 * character; see {@link #play} for the whole of that.
 * {@code Resource.local()} — <b>not</b> {@code remote()} — is the client jar's own pool, where the bundled
 * {@code sfx/*} live; music (a content resource) is the remote pool's business.
 *
 * <p><b>Userdata + per-addon interning</b> (D-017 / D-045), identical to the prior sections: the handle
 * crosses as {@code LuaValue.userdataOf(luaSound, mt)} so Lua cannot scribble on it, and the {@link Cache} on
 * the owning {@link Addon} (weak values + a {@link ReferenceQueue} drained on every access) makes
 * {@code :get("sfx/msg") == :get("sfx/msg")} and {@code seen[sound] = true} reliable. Never
 * static: no Lua value crosses a sandbox boundary and the cache dies whole with the {@link Addon} on
 * {@code :reload}.
 *
 * <p><b>Unprotected</b> (no {@code requirePermission}): playback is client-local and sends nothing to the server.
 *
 * <p><b>What is playing (024.2) lives in the {@link Cache}, not in the handle.</b> The clips a name has in the
 * air are keyed by that <i>name</i> in the owning addon's {@link Live} map, so every handle for {@code "sfx/x"}
 * — the interned one, or a fresh one minted after Lua dropped it and the weak cache dropped its entry — talks
 * to the same playback state. {@code :stop()} and {@code :playing()} are pure engine surface (no core edit):
 * {@code ActAudio.RootChannel.remove(cs)} stops, {@code mixer().playing(cs)} tests. There is no end-of-clip
 * callback and none is needed — {@code Audio.Mixer.get} drops a drained clip <b>lazily</b>, so asking is also
 * how a Sound prunes its own list. {@code :list()} is that prune across the whole map: the
 * addon's still-playing Sounds, and only the addon's — the client's own blips run through the same kind of
 * channel in each session's own tree, and are not ours to enumerate or stop.
 *
 * <p><b>Teardown</b> ({@link #teardownSounds}, from {@code AddonRegistry.teardown} + the {@code :reload} sweep
 * of the REPL): a disabled addon making noise is a bug, so everything it left in the air is stopped. And
 * because {@code :play()} returns <i>before</i> the loader has produced the clip, stopping also has to cancel a
 * play still in flight — a per-name generation stamp the deferred task re-checks, or {@code :play():stop()}
 * would still blip.
 */
public final class LuaSound {
    /** The addon this handle belongs to — its {@link Addon#sounds} cache owns the playback state. */
    private final Addon owner;
    /** The resource name this Sound addresses — the whole state of a handle. */
    public final String res;

    private LuaSound(Addon owner, String res) {
        this.owner = owner;
        this.res = res;
    }

    /** {@code tostring(sound)} (also the {@code __tostring} answer): {@code Sound(<resname>)}. */
    public String toString() {
        return "Sound(" + res + ")";
    }

    /** An interned Sound object for {@code res} in {@code owner}'s env — the one way a Sound reaches Lua. */
    static LuaValue of(Addon owner, String res) {
        return owner.sounds.of(res);
    }

    /** The {@code LuaSound} behind a Lua value, or {@code null} for anything that is not a Sound object. */
    static LuaSound resolve(LuaValue v) {
        if((v == null) || !v.isuserdata())
            return null;
        Object o = v.touserdata();
        return (o instanceof LuaSound) ? (LuaSound)o : null;
    }

    // ---- the per-addon intern cache + metatable ---------------------------------------------------

    /**
     * One addon's Sound interning cache and metatable (its {@link Addon#sounds}). Weak values + a
     * {@link ReferenceQueue} drained on every access; the metatable is built once, lazily. Keyed by resource
     * name, like {@link LuaPagina.Cache} — but unbounded in principle (any name is a key), which is exactly
     * why the values are weak: a handle Lua has dropped takes its map entry with it.
     */
    static final class Cache {
        private final Addon owner;
        private final Map<String, Ref> live = new HashMap<String, Ref>();
        private final ReferenceQueue<LuaValue> dead = new ReferenceQueue<LuaValue>();
        /**
         * The addon's <b>playback state</b>, keyed by resource name — the clips in the air plus the plays still
         * resolving. Deliberately here and not on the handle: the handles are weak, so a Sound Lua has dropped
         * (or re-fetched) must not lose track of what it started. Insertion-ordered, so {@code :list()}
         * lists in the order the addon started them; entries are dropped as they drain.
         */
        private final Map<String, Live> sounding = new LinkedHashMap<String, Live>();
        private LuaValue mt;

        Cache(Addon owner) {
            this.owner = owner;
        }

        /** The interned handle for {@code res} — a cache hit, or a freshly minted (and inserted) one. */
        synchronized LuaValue of(String res) {
            drain();
            Ref r = live.get(res);
            if(r != null) {
                LuaValue v = r.get();
                if(v != null)
                    return v;
                live.remove(res);
            }
            LuaValue v = LuaValue.userdataOf(new LuaSound(owner, res), meta());
            live.put(res, new Ref(v, res, dead));
            return v;
        }

        /** This name's playback state, created on the first {@code :play()} of it. */
        synchronized Live sounding(String res) {
            Live l = sounding.get(res);
            if(l == null)
                sounding.put(res, l = new Live());
            return l;
        }

        /** Is this name still sounding (or still resolving)? Prunes what the mixer has drained. */
        synchronized boolean playing(String res) {
            Live l = sounding.get(res);
            if(l == null)
                return false;
            if(prune(l))
                return true;
            sounding.remove(res);
            return false;
        }

        /** Stop everything this name has in the air, and cancel any play of it still resolving. */
        synchronized void stop(String res) {
            Live l = sounding.remove(res);
            if(l != null)
                silence(l);
        }

        /**
         * {@code hafen.sound():list()}: the addon's still-playing Sounds, pruning as it goes — so the same
         * call that counts them is the call that drains the drained ones.
         */
        synchronized List<LuaValue> members() {
            List<LuaValue> out = new ArrayList<LuaValue>();
            for(Iterator<Map.Entry<String, Live>> i = sounding.entrySet().iterator(); i.hasNext();) {
                Map.Entry<String, Live> e = i.next();
                if(prune(e.getValue()))
                    out.add(of(e.getKey()));
                else
                    i.remove();
            }
            return out;
        }

        /** Teardown: silence everything this addon left in the air (disable / {@code :reload}). */
        synchronized void stopAll() {
            for(Live l : sounding.values())
                silence(l);
            sounding.clear();
        }

        /** Drop the map entries whose handle Lua has released (the key + dead ref would leak otherwise). */
        private void drain() {
            Reference<? extends LuaValue> r;
            while((r = dead.poll()) != null) {
                Ref sr = (Ref)r;
                if(live.get(sr.key) == sr)      // not already replaced by a fresh handle for the same name
                    live.remove(sr.key);
            }
        }

        private LuaValue meta() {
            if(mt == null)
                mt = buildMeta(owner);
            return mt;
        }
    }

    /** A weak handle reference that remembers its map key, so the {@link ReferenceQueue} drain can unmap it. */
    private static final class Ref extends WeakReference<LuaValue> {
        final String key;

        Ref(LuaValue v, String key, ReferenceQueue<LuaValue> q) {
            super(v, q);
            this.key = key;
        }
    }

    // ---- what one name has in the air --------------------------------------------------------------

    /**
     * One resource name's live playback, shared by every handle for that name in one addon. {@link #clips} are
     * the {@link Audio.CS} streams handed to the mixer, each with the channel it went to (appended from a
     * <b>loader</b> thread, read and cleared from the <b>UI</b> thread — hence the object's own monitor,
     * never a Lua-side lock);
     * {@link #pending} counts the plays the loader has not resolved yet, so {@code :playing()} is true from the
     * instant {@code :play()} returns; {@link #gen} is bumped by every stop, which is how a play still in flight
     * is cancelled rather than blipping after the fact.
     */
    private static final class Live {
        final List<Clip> clips = new ArrayList<Clip>();
        int pending;
        int gen;
    }

    /**
     * One clip in the air, and <b>the channel it went to</b>. The channel is remembered rather than asked for
     * again at the stop: {@link #play} hands the clip to the layer that was up when it resolved, and a layer
     * replaced since would leave a stop asking a mixer that never had it — the clip would go on sounding with
     * every handle to it already gone.
     */
    private static final class Clip {
        final UI ui;                 // the channel this clip was handed to, and the only one that can take it back
        final Drained cs;

        Clip(UI ui, Drained cs) {
            this.ui = ui;
            this.cs = cs;
        }
    }

    /**
     * The stream the mixer is handed, remembering the moment it drained. The mixer drops a finished clip
     * from a list only it can walk, so asking it per clip was a scan of every clip in the air per clip of
     * ours; the stream itself is told first, by its own return value, and that is what {@link #prune} reads.
     */
    private static final class Drained implements Audio.CS {
        private final Audio.CS real;
        volatile boolean done;

        Drained(Audio.CS real) {
            this.real = real;
        }

        public int get(double[][] buf, int len) {
            int n = real.get(buf, len);
            if(n < 0)
                done = true;
            return n;
        }
    }

    /**
     * Drop the clips the mixer has already drained ({@code Audio.Mixer.get} removes a finished clip lazily, so
     * asking IS the prune), and answer whether anything of this name is still sounding or still resolving.
     */
    private static boolean prune(Live l) {
        synchronized(l) {
            for(Iterator<Clip> i = l.clips.iterator(); i.hasNext();) {
                Clip c = i.next();
                if(c.cs.done || (c.ui.audio == null))
                    i.remove();                  // drained, or its channel went with the UI that held it
            }
            return (l.pending > 0) || !l.clips.isEmpty();
        }
    }

    /**
     * Cut this name's live clips out of the {@code aui} channel and cancel anything still resolving.
     *
     * <p>The removal happens <b>inside</b> the monitor, exactly like the deferred play's hand-off to
     * {@code UI.sfx}: releasing it in between would reopen the very window the generation stamp exists to
     * close — a play that has registered its clip but not yet reached the mixer would be "stopped" first and
     * started after. (Lock order is always {@code Live} → the channel/mixer, never the other way.)
     */
    private static void silence(Live l) {
        synchronized(l) {
            l.gen++;            // a deferred play stamped with the old generation now drops its clip
            l.pending = 0;
            for(int i = 0; i < l.clips.size(); i++) {
                Clip c = l.clips.get(i);
                if(c.ui.audio != null)          // ITS channel: a layer replaced since this clip started
                    c.ui.audio.aui.remove(c.cs);   //   still has to be the one told to drop it
            }
            l.clips.clear();
        }
    }

    /** Teardown (disable / {@code :reload}): silence everything this addon left playing. */
    static void teardownSounds(Addon a) {
        if(a != null)
            a.sounds.stopAll();
    }

    // ---- the Sound metatable -----------------------------------------------------------------------

    /**
     * The per-addon metatable: {@code __index} = the methods table through {@link Refusal#closedIndex} (so
     * an unknown verb throws naming what this type does answer), plus {@code __tostring}/{@code __name}.
     */
    private static LuaValue buildMeta(final Addon owner) {
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, Refusal.closedIndex("sound", methods(owner),
            "a sound is one clip you can fire"));
        mt.set("__name", LuaValue.valueOf("Sound"));
        mt.set("__tostring", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaSound h = resolve(self);
                return LuaValue.valueOf((h == null) ? "Sound(?)" : h.toString());
            }
        });
        return mt;
    }

    /** The method set. {@code :res()}/{@code :info()} answer from the handle alone; {@code :play} is the verb. */
    private static LuaTable methods(final Addon owner) {
        LuaTable m = new LuaTable();
        // res() — the resource name this Sound addresses. Always answers: it IS the handle.
        m.set("res", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = Args.only(a, 0, "sound:res");
                return LuaValue.valueOf(handle(self, "res").res);
            }
        });
        // info() — the one SNAPSHOT escape hatch, for logging/serialising.
        m.set("info", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = Args.only(a, 0, "sound:info");
                LuaSound h = handle(self, "info");
                LuaTable t = new LuaTable();
                t.set("res", LuaValue.valueOf(h.res));
                t.set("playing", LuaValue.valueOf(h.owner.sounds.playing(h.res)));
                return t;
            }
        });
        // play([volume]) — fire the clip once into the UI sfx channel, return SELF so it chains. UNPROTECTED:
        // client-local, nothing reaches the server. Volume is an ARGUMENT, never entity state (the Sound is
        // interned and shared) — omitted = 1.0, outside 0..1 is an error naming the method. A name that does
        // not resolve is silent here: the resolve happens later, on a loader thread.
        m.set("play", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = a.arg1();
                LuaSound h = handle(self, "play");
                Args.only(a, 1, "sound:play");
                play(h.owner, h.res, volume(a.arg(2), "sound:play"));
                return self;
            }
        });
        // stop() — cut every clip of this name THIS addon has in the air (and cancel a play still resolving,
        // so :play():stop() never blips), return SELF so it chains. Never touches another addon's clips or the
        // client's own blips, which run through the sessions' own channels. Silent when nothing is playing.
        m.set("stop", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = Args.only(a, 0, "sound:stop");
                LuaSound h = handle(self, "stop");
                h.owner.sounds.stop(h.res);
                return self;
            }
        });
        // playing() — is a clip of this name still sounding (or still resolving)? The mixer drops a drained
        // clip lazily, so this call is also the prune: ask it and the finished ones stop being counted.
        m.set("playing", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = Args.only(a, 0, "sound:playing");
                LuaSound h = handle(self, "playing");
                return LuaValue.valueOf(h.owner.sounds.playing(h.res));
            }
        });
        return m;
    }

    // ---- self resolution + argument checking -------------------------------------------------------

    /** The handle behind a method's {@code self}, or a guiding error (a dot-call passes the wrong self). */
    private static LuaSound handle(LuaValue self, String method) {
        LuaSound h = resolve(self);
        if(h == null)
            throw new LuaError("sound:" + method + "() — use a COLON call on a Sound object (hafen.sound():get(name))");
        return h;
    }

    /**
     * The optional {@code volume} argument of {@code sound:play}: absent ⇒ {@code 1.0}, a number in
     * {@code 0..1}, anything else a {@link LuaError} naming the method. It takes the method name because it
     * was written to be shared with the Track section, which 024.3 cut (D-058); {@code sound:play} is the
     * only caller today, and the parameter still buys the caller-accurate message.
     */
    static double volume(LuaValue v, String method) {
        double vol = Args.optnum(v, method, "volume", "a fraction of the clip's own loudness, 0..1", 1.0);
        if((vol < 0.0) || (vol > 1.0))
            throw new LuaError(method + "(volume): volume must be 0..1, got " + vol);
        return vol;
    }

    // ---- the play path -----------------------------------------------------------------------------

    /**
     * Play a client sound by resource name without blocking the UI thread: resolve the resource on a loader
     * thread ({@code Loading} re-runs the task), wrap it in an {@link Audio.VolAdjust} when the volume is not
     * 1, then hand the clip to {@link UI#sfx}. Mirrors {@code GobIcon.resnotif}. Non-{@code Loading} resolve
     * failures (a bogus name) are reported to the client's error line and swallowed — never a Lua error.
     * A no-op before the layer exists or before any session does.
     *
     * <p><b>The clip goes to the addon layer's channel</b> (075.1), not to the drawn session's. One shared
     * {@code Audio.Root} feeds every {@code UI} the client has, so a session that is not on screen has its
     * own {@link ActAudio.Root} muted every frame — which is what keeps three background characters from
     * playing their chat pings over the one you are watching. An addon is not one of those characters: it
     * plays a sound because it wants the player to hear it, and routing it through the session it happened
     * to be looking at made it silent exactly when the addon was watching something in the background.
     * {@code UILoop.mkui} hands the layer the same shared {@code Audio.Root} every session gets, and
     * {@code Sessions.applymute} walks the login {@code UI} and the members — the layer is neither, so it
     * is never muted. Nothing is constructed here; only the {@code UI} the clip is handed to changes.
     *
     * <p>The {@link Glob} is still the drawn session's, because what it is wanted for is
     * {@code loader.defer} — a thread to re-run the resolve on, which is no part of where the sound comes
     * out. With no session at all there is no loader and nothing plays.
     *
     * <p>The resulting clip is <b>registered on the owner's {@link Live} state for this name</b> before it goes
     * to the mixer, which is what makes {@code :stop()}/{@code :playing()}/{@code :list()} possible at
     * all. Because the resolve lands later, the play carries the {@link Live#gen} it started under: a
     * {@code :stop()} in between bumps that stamp and the clip is dropped instead of blipping (024.2).
     */
    private static void play(final Addon owner, final String name, final double vol) {
        final Glob g = AddonManager.glob();
        final UI u = AddonManager.layer();
        if((g == null) || (u == null))
            return;
        final Live live = owner.sounds.sounding(name);
        final int gen;
        synchronized(live) {
            live.pending++;         // :playing() is true from the instant :play() returns, not from the resolve
            gen = live.gen;
        }
        final Indir<Resource> resid = Resource.local().load(name);
        g.loader.defer(new Runnable() {
            public void run() {
                Audio.CS cs;
                try {
                    cs = Audio.fromres(resid.get());   // Loading → the loader re-runs this task (pending stands)
                } catch(Loading l) {
                    throw(l);
                } catch(RuntimeException e) {
                    synchronized(live) {
                        if(live.gen == gen)            // a stop already zeroed pending — do not go negative
                            live.pending--;
                    }
                    // Not u.error(): u is the layer now, and the layer's RootWidget draws a notice without
                    // keeping it — no GameUI under it, so no System channel to append to and no scrollback
                    // for a failure worth going back to. log() posts to the drawn session and to stdout.
                    AddonManager.log("could not play " + name);
                    return;
                }
                if(vol != 1.0)
                    cs = new Audio.VolAdjust(cs, vol);
                Drained dc = new Drained(cs);
                synchronized(live) {
                    if(live.gen != gen)                // stopped while we resolved: never blip (pending zeroed)
                        return;
                    live.pending--;
                    live.clips.add(new Clip(u, dc));   // the clip AND the channel it is about to go to
                    u.sfx(dc);      // INSIDE the monitor: registering and starting must be one step, or a
                                    // :stop() landing between them removes a clip the mixer has not got yet
                }
            }
        }, null);
    }

    // ---- the collection ----------------------------------------------------------------------------

    /**
     * {@code hafen.sound()} — audio, as the {@link LuaCollection} the section object IS: {@code :get(name)}
     * interns the Sound for a resource name (any name is addressable, whether or not it has ever played), and
     * {@code :list(filter)} is <i>this addon's</i> still-playing Sounds (024.2), an empty array when it has
     * none. The two halves are deliberately different sets — the game owns every clip, the addon owns only
     * what it started — which is why there is no {@code :add} (playing is {@code s:play(volume)}) and no
     * {@code :remove} (silencing is {@code s:stop()}).
     */
    static LuaValue collection(final Addon owner) {
        LuaTable extra = new LuaTable();
        // playing(filter) -- the clips of yours that are AUDIBLE right now (091, A-081). It was :list(),
        // which promised the set :get() addresses and delivered a different one.
        extra.set("playing", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaCollection.receiver(a.arg1(), "hafen.sound()", "playing");
                Args.only(a, 1, "hafen.sound():playing");
                final LuaValue filter = a.arg(2);
                return LuaCollection.create("hafen.sound():playing()", new LuaCollection.Source() {
                    public List<LuaValue> members() {
                        List<LuaValue> out = new ArrayList<LuaValue>();
                        for(LuaValue m : owner.sounds.members()) {
                            if(LuaCollection.keeps(filter, m, this, "hafen.sound()", "playing"))
                                out.add(m);
                        }
                        return out;
                    }

                    public boolean named() {
                        return true;
                    }

                    public String needle(LuaValue member) {
                        LuaSound h = resolve(member);
                        return (h == null) ? "" : h.res;
                    }

                    public String noGet() {
                        return "the audible clips are a partition of what you have addressed:"
                            + " hafen.sound():get(name) mints one by resource name, playing or not";
                    }
                }, null);
            }
        });
        return LuaCollection.create("hafen.sound()", new LuaCollection.Source() {
            public List<LuaValue> members() {
                // 091/A-081: this collection ADDRESSES by name and does not enumerate. :get(name) mints a
                // Sound for ANY clip, playing or not, so a :list() of the PLAYING ones was a different set
                // under the same collection -- :count() answered "how many are audible", which is correct
                // and is not the question :count() asks anywhere else. The playing ones are :playing(f).
                throw new LuaError("hafen.sound() does not enumerate: hafen.sound():get(name) mints a Sound"
                    + " for any clip, playing or not, so there is no set of \"your sounds\" to count."
                    + " hafen.sound():playing(filter) is the ones audible right now");
            }

            public String needle(LuaValue member) {
                LuaSound h = resolve(member);
                return (h == null) ? "" : h.res;
            }

            /** These have a name, so a string filter is a substring test over {@link #needle}. */
            public boolean named() {
                return true;
            }

            public boolean addressable() {
                return true;
            }

            public LuaValue getMember(LuaValue key) {
                if(key.isnumber())              // BEFORE isstring(): in LuaJ a number IS a string
                    throw new LuaError("hafen.sound():get(name): the key is a RESOURCE NAME string (e.g."
                        + " \"sfx/msg\"), not a number");
                if(!key.isstring())
                    throw new LuaError("hafen.sound():get(name): expected a resource name string (e.g."
                        + " hafen.sound():get(\"sfx/msg\")), got " + key.typename());
                String res = key.tojstring().trim();
                if(res.isEmpty())
                    throw new LuaError("hafen.sound():get(name): the resource name is empty");
                return of(owner, res);
            }

            /** Any resource name is addressable, whether or not it has ever played. */
            public LuaCollection.Missing missing() {
                return LuaCollection.Missing.MINT;
            }

            /** The key is the sound's resource name. */
            public String keyName() {
                return "name";
            }
        }, extra);
    }
}
