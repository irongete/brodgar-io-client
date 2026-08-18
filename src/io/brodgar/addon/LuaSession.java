package io.brodgar.addon;

import haven.GameUI;
import io.brodgar.session.Sessions;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.OneArgFunction;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

/**
 * A <b>Session object</b> — one of the logins this client holds, and the <b>address</b> an addon names a
 * character by (spec {@code 076-the-session-is-the-address}). {@code hafen.session():get(user)} mints one,
 * {@code hafen.session():current()} is the one on screen, and the collection over them is {@link SessionApi}.
 *
 * <p><b>It wraps the account name and nothing else</b>, exactly as {@link LuaGob} wraps only the gob id:
 * every verb re-resolves through {@link Sessions#byuser} on the call, so a handle kept across a character
 * switch, a relogin or the session ending stays meaningful. That is what makes the ref a
 * {@code SessionDestroyed} handler is given still answer {@code :user()} while {@code :exists()} is
 * {@code false} — the name IS the ref, so there is nothing left to resolve and nothing to go stale.
 *
 * <p><b>The account, and not the character.</b> {@code Sessions.Member.chr} is the name {@code :session add}
 * asked for and may be absent, and picking another character keeps the session alive — the server hands it a
 * new world rather than ending it. So the key is the account, and {@code :character()} reads
 * {@link GameUI#chrid} off <b>that</b> session's own HUD, which is what that login is actually playing.
 *
 * <p><b>Userdata + per-addon interning</b> (D-017 / D-045), the {@link LuaGob} mechanism verbatim: the handle
 * crosses as {@code LuaValue.userdataOf(luaSession, mt)} so Lua cannot scribble on it, and the {@link Cache}
 * on the owning {@link Addon} (weak values + a {@link ReferenceQueue} drained on every access) makes
 * {@code :get(u) == :get(u)} and {@code seen[s] = true} reliable. Never static: no Lua value crosses a
 * sandbox boundary, and the cache dies whole with the {@link Addon} on {@code :reload}.
 *
 * <p><b>What hangs on it.</b> {@code s:world()} and {@code s:player()} (076.3) are the first two namespaces
 * addressed through a Session rather than off {@code hafen}, and both are minted once per
 * {@code (addon, session)} and kept on the handle — see {@link #worldObj}. Every verb under them reads the
 * session named rather than the one on screen; the ones that are inherently the screen's say so where they are
 * defined ({@code screenToWorld}, {@code worldToScreen}) and the ones that <b>send</b> go through
 * {@link AddonManager#sendView}, because a walk is the whole of what a character nobody is looking at takes.
 *
 * <p><b>Threading.</b> Every read runs on the UI thread. {@code Sessions.members()} copies the membership
 * list, and a member's {@code ui} is null in the gaps ({@code Sessions.Member.run} clears it while the UI is
 * taken down and during a character handoff), so every verb answers {@code nil}-shaped there rather than
 * throwing. The {@link Cache} map is guarded on its own monitor (UI + REPL threads touch it).
 */
public final class LuaSession {
    /** The account name — the whole address, and the one thing that survives the session. */
    public final String user;

    /**
     * <b>The namespaces that hang on this session</b> (076.3), minted lazily and held here rather than on the
     * {@link Addon}: they belong to a {@code (addon, session)} pair, and this handle <i>is</i> that pair. So
     * {@code s:world() == s:world()} and {@code s:player() == s:player()} come out of interning the handle and
     * need no cache of their own — and when the addon drops its last reference to {@code s}, the whole bundle
     * goes with it, because the handle is weakly held (see {@link Cache}).
     *
     * <p>They are <b>not</b> discarded when the session ends. A handle held past the end still answers
     * {@code :user()}, and its {@code :world()} answers {@code nil}-shaped rather than throwing — every verb
     * re-resolves the member, so there is no stale state for an ended session to leave behind.
     */
    private LuaValue worldObj, playerObj;

    private LuaSession(String user) {
        this.user = user;
    }

    /** {@code tostring(s)} (also the {@code __tostring} answer): {@code Session(<user>)}. */
    public String toString() {
        return "Session(" + user + ")";
    }

    /** An interned Session object for {@code user} in {@code owner}'s env — the one way one reaches Lua. */
    static LuaValue of(Addon owner, String user) {
        return owner.sessions.of(user);
    }

    /** The {@code LuaSession} behind a Lua value, or {@code null} for anything that is not a Session. */
    static LuaSession resolve(LuaValue v) {
        if((v == null) || !v.isuserdata())
            return null;
        Object o = v.touserdata();
        return (o instanceof LuaSession) ? (LuaSession)o : null;
    }

    // ---- the per-addon intern cache + metatable ----------------------------------------------------

    /**
     * One addon's Session interning cache and metatable (its {@link Addon#sessions}). Weak values + a
     * {@link ReferenceQueue} drained on every access — <i>not</i> a {@code WeakHashMap}, which is weak
     * <i>keys</i>; the metatable is built once, lazily. Holds its {@link Addon} because the verbs a Session
     * grows mint the <b>owner's</b> own interned objects.
     */
    static final class Cache {
        private final Addon owner;
        private final Map<String, Ref> live = new HashMap<String, Ref>();
        private final ReferenceQueue<LuaValue> dead = new ReferenceQueue<LuaValue>();
        private LuaValue mt;

        Cache(Addon owner) {
            this.owner = owner;
        }

        /** The interned handle for {@code user} — a cache hit, or a freshly minted (and inserted) one. */
        synchronized LuaValue of(String user) {
            drain();
            Ref r = live.get(user);
            if(r != null) {
                LuaValue v = r.get();
                if(v != null)
                    return v;
                live.remove(user);
            }
            LuaValue v = LuaValue.userdataOf(new LuaSession(user), meta());
            live.put(user, new Ref(v, user, dead));
            return v;
        }

        /** Drop the map entries whose handle Lua has released (the key + dead ref would leak otherwise). */
        private void drain() {
            Reference<? extends LuaValue> r;
            while((r = dead.poll()) != null) {
                Ref sr = (Ref)r;
                if(live.get(sr.key) == sr)     // not already replaced by a fresh handle for the same account
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

    // ---- the Session metatable --------------------------------------------------------------------

    /** The per-addon metatable: {@code __index} = the methods table, plus {@code __tostring}/{@code __name}. */
    private static LuaValue buildMeta(final Addon owner) {
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, Retired.methodIndex("session", methods(owner)));
        mt.set("__name", LuaValue.valueOf("Session"));
        mt.set("__tostring", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaSession h = resolve(self);
                return LuaValue.valueOf((h == null) ? "Session(?)" : h.toString());
            }
        });
        return mt;
    }

    /**
     * The method set. {@code :user()} answers from the handle alone and so survives the session ending;
     * everything else re-resolves the member and answers {@code nil}-shaped once it is gone.
     */
    private static LuaTable methods(final Addon owner) {
        LuaTable m = new LuaTable();
        // user() — the account this session logged in as. The key, the ref, and the one read that never
        // fails: a handler dropping its tables for a session that has just ended needs it AFTER it ended.
        m.set("user", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(handle(self, "user").user);
            }
        });
        // character() — what this login is PLAYING, read off that session's own HUD (GameUI.chrid) rather
        // than the name `:session add` asked for: picking another character keeps the session alive, so the
        // requested name and the played one part company. nil until this session's HUD is up.
        m.set("character", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                GameUI g = gameui(handle(self, "character"));
                return ((g == null) || (g.chrid == null)) ? LuaValue.NIL : LuaValue.valueOf(g.chrid);
            }
        });
        m.set("exists", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(Sessions.byuser(handle(self, "exists").user) != null);
            }
        });
        // world() — THIS character's world: the objects it can see, the terrain it is standing on, the grids it
        // has streamed. Two characters in different places see different objects, not because there are two
        // worlds but because each looks out of its own eyes, and this is where an addon says whose eyes.
        m.set("world", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaSession h = handle(self, "world");
                if(h.worldObj == null)
                    h.worldObj = WorldApi.world(owner, h.user);
                return h.worldObj;
            }
        });
        // player() — THIS login's character, as the Player object: its own Gob, its cursor, and the walk. It is
        // never nil, because the address exists whether or not the session behind it does; what answers nil is
        // s:player():gob(), before that session is in the world and after it has gone.
        m.set("player", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaSession h = handle(self, "player");
                if(h.playerObj == null)
                    h.playerObj = CharApi.player(owner, h.user);
                return h.playerObj;
            }
        });
        // info() — the one SNAPSHOT escape hatch, and always a table: a Session that does not exist is
        // exactly what a handler wants to log, so there is nothing to answer nil with.
        m.set("info", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaSession h = handle(self, "info");
                Sessions.Member mem = Sessions.byuser(h.user);
                LuaTable t = new LuaTable();
                t.set("user", LuaValue.valueOf(h.user));
                GameUI g = (mem == null) ? null : mem.gameui();
                if((g != null) && (g.chrid != null))
                    t.set("character", LuaValue.valueOf(g.chrid));
                t.set("exists", LuaValue.valueOf(mem != null));
                t.set("current", LuaValue.valueOf((mem != null) && (mem == Sessions.anchormember())));
                return t;
            }
        });
        return m;
    }

    // ---- self resolution -------------------------------------------------------------------------

    /** The handle behind a method's {@code self}, or a guiding error (a dot-call passes the wrong self). */
    private static LuaSession handle(LuaValue self, String method) {
        LuaSession h = resolve(self);
        if(h == null)
            throw new LuaError("session:" + method + "() — use a COLON call on a Session object"
                + " (hafen.session():current(), hafen.session():get(user), hafen.session():list()[n])");
        return h;
    }

    /** This session's own HUD, or {@code null} while it has none (connecting, on the character list, gone). */
    private static GameUI gameui(LuaSession h) {
        Sessions.Member mem = Sessions.byuser(h.user);
        return (mem == null) ? null : mem.gameui();
    }
}
