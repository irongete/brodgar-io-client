package io.brodgar.addon;

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
import java.util.HashMap;
import java.util.Map;

/**
 * A <b>Sound object</b> — one client sound effect, addressed by its <b>resource name</b> (spec
 * {@code 024-audio-oop}), the OOP successor of the flat fire-and-forget {@code hafen.sound.play(res)}. Built on
 * exactly the mechanism {@link LuaGob} (017), {@link LuaKin} (020), {@link LuaSlot} (021) and
 * {@link LuaPagina} (023) established; <b>arity is the verb on the namespace itself</b>:
 * {@code hafen.sound(name)} is one Sound.
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
 * {@code Loading} into Lua. The resolved clip goes to {@link UI#sfx}, i.e. the {@code ActAudio.Root.aui}
 * channel the client's own blips use, wrapped in an {@link Audio.VolAdjust} when the volume is not 1.
 * {@code Resource.local()} — <b>not</b> {@code remote()} — is the client jar's own pool, where the bundled
 * {@code sfx/*} live; music (a content resource) is the remote pool's business.
 *
 * <p><b>Userdata + per-addon interning</b> (D-017 / D-045), identical to the prior sections: the handle
 * crosses as {@code LuaValue.userdataOf(luaSound, mt)} so Lua cannot scribble on it, and the {@link Cache} on
 * the owning {@link Addon} (weak values + a {@link ReferenceQueue} drained on every access) makes
 * {@code hafen.sound("sfx/msg") == hafen.sound("sfx/msg")} and {@code seen[sound] = true} reliable. Never
 * static: no Lua value crosses a sandbox boundary and the cache dies whole with the {@link Addon} on
 * {@code :reload}.
 *
 * <p><b>Ungated</b> (no {@code requireActions}): playback is client-local and sends nothing to the server.
 */
public final class LuaSound {
    /** The resource name this Sound addresses — the whole state of a handle. */
    public final String res;

    private LuaSound(String res) {
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
            LuaValue v = LuaValue.userdataOf(new LuaSound(res), meta());
            live.put(res, new Ref(v, res, dead));
            return v;
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

    // ---- the Sound metatable -----------------------------------------------------------------------

    /** The per-addon metatable: {@code __index} = the methods table, plus {@code __tostring}/{@code __name}. */
    private static LuaValue buildMeta(final Addon owner) {
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, methods(owner));
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
        m.set("res", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(handle(self, "res").res);
            }
        });
        // info() — the one SNAPSHOT escape hatch, for logging/serialising.
        m.set("info", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaTable t = new LuaTable();
                t.set("res", LuaValue.valueOf(handle(self, "info").res));
                return t;
            }
        });
        // play([volume]) — fire the clip once into the UI sfx channel, return SELF so it chains. UNGATED:
        // client-local, nothing reaches the server. Volume is an ARGUMENT, never entity state (the Sound is
        // interned and shared) — omitted = 1.0, outside 0..1 is an error naming the method. A name that does
        // not resolve is silent here: the resolve happens later, on a loader thread.
        m.set("play", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = a.arg1();
                LuaSound h = handle(self, "play");
                play(h.res, volume(a.arg(2), "sound:play"));
                return self;
            }
        });
        return m;
    }

    // ---- self resolution + argument checking -------------------------------------------------------

    /** The handle behind a method's {@code self}, or a guiding error (a dot-call passes the wrong self). */
    private static LuaSound handle(LuaValue self, String method) {
        LuaSound h = resolve(self);
        if(h == null)
            throw new LuaError("sound:" + method + "() — use a COLON call on a Sound object (hafen.sound(name))");
        return h;
    }

    /**
     * The optional {@code volume} argument shared by {@code sound:play} and (024.3) {@code track:play}:
     * absent ⇒ {@code 1.0}, a number in {@code 0..1}, anything else a {@link LuaError} naming the method.
     */
    static double volume(LuaValue v, String method) {
        if(v.isnil())
            return 1.0;
        if(!v.isnumber())
            throw new LuaError(method + "(volume): expected a number 0..1, got " + v.typename());
        double vol = v.todouble();
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
     * A no-op before the UI/session exists.
     */
    private static void play(final String name, final double vol) {
        final Glob g = AddonManager.glob();
        final UI u = AddonManager.ui;
        if((g == null) || (u == null))
            return;
        final Indir<Resource> resid = Resource.local().load(name);
        g.loader.defer(new Runnable() {
            public void run() {
                Resource res;
                try {
                    res = resid.get();               // Loading → the loader re-runs this task
                } catch(Loading l) {
                    throw(l);
                } catch(RuntimeException e) {
                    u.error("addon: could not play " + name);
                    return;
                }
                Audio.CS cs = Audio.fromres(res);
                if(vol != 1.0)
                    cs = new Audio.VolAdjust(cs, vol);
                u.sfx(cs);
            }
        }, null);
    }

    // ---- the namespace -----------------------------------------------------------------------------

    /**
     * {@code hafen.sound} itself: a <b>callable table</b> ({@code __call}) taking the resource name, so the old
     * flat {@code hafen.sound.play(name)} reads as plain {@code nil} — the hard cut (D-013) is visible from
     * Lua, exactly as {@code hafen.gob} (D-044), {@code hafen.actionbar} (D-057) and {@code hafen.menugrid}
     * did it. ({@code hafen.sound()} — this addon's still-playing Sounds — arrives with 024.2.)
     */
    static LuaValue factory(final Addon owner) {
        LuaTable sound = new LuaTable();
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.CALL, new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue key = a.arg(2);        // arg1 = the callable table itself
                if(key.isnumber())              // BEFORE isstring(): in LuaJ a number IS a string
                    throw new LuaError("hafen.sound(name): the key is a RESOURCE NAME string (e.g."
                        + " \"sfx/msg\"), not a number");
                if(key.isstring()) {
                    String res = key.tojstring().trim();
                    if(res.isEmpty())
                        throw new LuaError("hafen.sound(name): the resource name is empty");
                    return of(owner, res);
                }
                throw new LuaError("hafen.sound(name): expected a resource name string (e.g."
                    + " hafen.sound(\"sfx/msg\")), got " + key.typename());
            }
        });
        sound.setmetatable(mt);
        return sound;
    }
}
