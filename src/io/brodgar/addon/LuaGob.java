package io.brodgar.addon;

import haven.Coord2d;
import haven.Gob;
import haven.GobHealth;
import haven.Moving;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.TwoArgFunction;
import org.luaj.vm2.lib.VarArgFunction;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

/**
 * A <b>Gob object</b> — the OOP successor of the flat {@code hafen.gob.*(ref)} accessor (spec
 * {@code 017-gob-oop}, D-044). {@code hafen.gob(id)} mints one; {@code gob:pos()} / {@code gob:name()} /
 * {@code gob:health()} … read it. It wraps <b>only the id</b>: every method re-resolves against the
 * {@link haven.OCache} through {@link AddonManager#getgob} and returns {@code nil} if the gob is gone, so the
 * freshness semantics of D-012 survive verbatim — what changed is that the reference stopped being an argument
 * and became the object.
 *
 * <p><b>Userdata, not a table (P1 / D-017).</b> The handle crosses into Lua as
 * {@code LuaValue.userdataOf(luaGob, mt)} with a <b>per-addon</b> metatable ({@code __index} = the shared
 * methods table, {@code __tostring}, {@code __name}) — the R1 handle pattern of {@code luaj-bridge.md}. The
 * interned object is shared by all of the addon's own code, so it must be <b>immutable from Lua</b> (a LuaTable
 * handle could be scribbled on: {@code gob.pos = nil}); userdata with no {@code __newindex} rejects writes, and
 * the raw {@link Gob} never crosses the facade. Field access is methods-only — {@code gob.id} is the function,
 * {@code gob:id()} the number.
 *
 * <p><b>Identity by interning (D-045).</b> Each addon's {@link Cache} (held in {@link Addon#gobs}) is a
 * {@code Map<Long, WeakReference<LuaValue>>} + a {@link ReferenceQueue}, so {@code hafen.gob(id) ==
 * hafen.gob(id)} and {@code seen[gob] = true} are reliable, and {@code hafen.player():gob()} is literally the
 * same object as {@code hafen.gob(<player id>)}. Weak <b>values</b> (not a {@code WeakHashMap}: that is weak
 * <i>keys</i>) so an entry dies when the addon drops its last reference; the queue is drained on every access
 * (amortised, no sweep timer) because the {@code Long} key + the dead {@code WeakReference} would otherwise
 * accumulate — a per-tick world sweep sees tens of thousands of ids over a session. The cache is
 * <b>per-addon</b> (never static in {@link AddonManager}): no Lua value crosses a sandbox boundary, and it dies
 * whole with the {@link Addon} on {@code :reload}/disable.
 *
 * <p><b>No pinning.</b> A {@code LuaGob} holds no {@link Gob} reference, so a stashed handle can never keep a
 * despawned gob (or its {@code .res}/overlays) alive — strictly better than {@link LuaWidget}, which has to
 * null its {@code Widget} by hand.
 *
 * <p><b>Threading.</b> Reads run on the UI thread (addon tick / REPL), each attribute under the gob monitor and
 * {@code Loading}-guarded (a resource-backed read throws before it resolves → {@code nil}), exactly as the flat
 * accessor did. The {@link Cache} map itself is guarded on its own monitor (UI + REPL threads touch it).
 */
public final class LuaGob {
    /** The gob id — the whole state of a handle. */
    public final long id;

    private LuaGob(long id) {
        this.id = id;
    }

    /** {@code tostring(gob)} (also the {@code __tostring} answer): {@code Gob(<id>)}. */
    public String toString() {
        return "Gob(" + id + ")";
    }

    /** An interned Gob object for {@code id} in {@code owner}'s env — the one way a Gob reaches Lua. */
    static LuaValue of(Addon owner, long id) {
        return owner.gobs.of(id);
    }

    /**
     * Resolve a Lua value handed to a Gob consumer ({@code hafen.act.clickGob}, {@code follow=}, {@code :same}-
     * style comparisons) back to its {@code LuaGob}; {@code null} for anything that is not a Gob object (nil, a
     * raw id, a token string — the hard cut accepts none of those, D-044).
     */
    static LuaGob resolve(LuaValue v) {
        if((v == null) || !v.isuserdata())
            return null;
        Object o = v.touserdata();
        return (o instanceof LuaGob) ? (LuaGob)o : null;
    }

    // ---- the per-addon intern cache + metatable ---------------------------------------------------

    /**
     * One addon's Gob interning cache and metatable (its {@link Addon#gobs}). Weak values + a
     * {@link ReferenceQueue} drained on every access; the metatable and methods table are built once, lazily.
     * Holds its {@link Addon} because {@code gob:kin()} has to mint the <b>owner's</b> interned Kin (020.2).
     */
    static final class Cache {
        private final Addon owner;
        private final Map<Long, Ref> live = new HashMap<Long, Ref>();
        private final ReferenceQueue<LuaValue> dead = new ReferenceQueue<LuaValue>();
        private LuaValue mt;

        Cache(Addon owner) {
            this.owner = owner;
        }

        /** The interned handle for {@code id} — a cache hit, or a freshly minted (and inserted) one. */
        synchronized LuaValue of(long id) {
            drain();
            Long key = Long.valueOf(id);
            Ref r = live.get(key);
            if(r != null) {
                LuaValue v = r.get();
                if(v != null)
                    return v;
                live.remove(key);
            }
            LuaValue v = LuaValue.userdataOf(new LuaGob(id), meta());
            live.put(key, new Ref(v, key, dead));
            return v;
        }

        /** Drop the map entries whose handle Lua has released (the key + dead ref would leak otherwise). */
        private void drain() {
            Reference<? extends LuaValue> r;
            while((r = dead.poll()) != null) {
                Ref gr = (Ref)r;
                if(live.get(gr.key) == gr)      // not already replaced by a fresh handle for the same id
                    live.remove(gr.key);
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
        final Long key;

        Ref(LuaValue v, Long key, ReferenceQueue<LuaValue> q) {
            super(v, q);
            this.key = key;
        }
    }

    // ---- the metatable ---------------------------------------------------------------------------

    /** The per-addon metatable: {@code __index} = the methods table, plus {@code __tostring}/{@code __name}. */
    private static LuaValue buildMeta(final Addon owner) {
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, methods(owner));
        mt.set("__name", LuaValue.valueOf("Gob"));
        mt.set("__tostring", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaGob h = resolve(self);
                return LuaValue.valueOf((h == null) ? "Gob(?)" : h.toString());
            }
        });
        return mt;
    }

    /**
     * The method set. Each reader re-resolves the gob and answers {@code nil} when it is gone — so a stashed
     * handle tracks a moving gob and goes quiet when it despawns (D-012's freshness, kept). {@code :id()} is the
     * exception: it answers from the handle alone, so it still works inside a {@code GobRemoved} handler.
     */
    private static LuaTable methods(final Addon owner) {
        LuaTable m = new LuaTable();
        m.set("id", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf((double)handle(self, "id").id);
            }
        });
        m.set("exists", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(gob(self, "exists") != null);
            }
        });
        // info() — the one SNAPSHOT escape hatch (the old hafen.gob.info shape), for logging/serialising.
        m.set("info", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return AddonManager.gobSnapshot(gob(self, "info"));
            }
        });
        m.set("pos", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Gob g = gob(self, "pos");
                if(g == null)
                    return LuaValue.NIL;
                Coord2d rc;
                synchronized(g) { rc = g.rc; }
                if(rc == null)
                    return LuaValue.NIL;
                LuaTable t = new LuaTable();
                t.set("x", LuaValue.valueOf(rc.x));
                t.set("y", LuaValue.valueOf(rc.y));
                return t;
            }
        });
        m.set("facing", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Gob g = gob(self, "facing");
                if(g == null)
                    return LuaValue.NIL;
                synchronized(g) {
                    return LuaValue.valueOf(g.a);
                }
            }
        });
        m.set("name", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Gob g = gob(self, "name");
                String n = (g == null) ? null : AddonManager.gobName(g);
                return (n == null) ? LuaValue.NIL : LuaValue.valueOf(n);
            }
        });
        m.set("health", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Gob g = gob(self, "health");
                if(g == null)
                    return LuaValue.NIL;
                GobHealth h = g.getattr(GobHealth.class);
                return (h == null) ? LuaValue.NIL : LuaValue.valueOf(h.hp);
            }
        });
        m.set("moving", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Gob g = gob(self, "moving");
                if(g == null)
                    return LuaValue.NIL;
                return LuaValue.valueOf(g.getattr(Moving.class) != null);
            }
        });
        m.set("speed", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Gob g = gob(self, "speed");
                if(g == null)
                    return LuaValue.NIL;
                Moving mv = g.getattr(Moving.class);
                if(mv == null)
                    return LuaValue.NIL;
                try {
                    return LuaValue.valueOf(mv.getv());
                } catch(RuntimeException e) {   // Loading etc. — speed unavailable this frame
                    return LuaValue.NIL;
                }
            }
        });
        m.set("speech", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Gob g = gob(self, "speech");
                String s = (g == null) ? null : AddonManager.gobSpeech(g);
                return (s == null) ? LuaValue.NIL : LuaValue.valueOf(s);
            }
        });
        m.set("icon", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Gob g = gob(self, "icon");
                String s = (g == null) ? null : AddonManager.gobIcon(g);
                return (s == null) ? LuaValue.NIL : LuaValue.valueOf(s);
            }
        });
        // overlay([key[, spec]]) — THE ONE WAY to attach anything to a game object, and the one way to read what
        // is already attached (038.1). ARITY IS THE VERB, and the key is per addon:
        //   gob:overlay()             -- every overlay on the gob: yours, then the GAME's own (ov:native())
        //   gob:overlay(key)          -- that one, or nil
        //   gob:overlay(key, spec)    -- attach or REPLACE (the same key twice leaves one overlay)
        //   gob:overlay(key, nil)     -- remove
        // The spec says WHAT to draw, in ONE of the two spaces (038.2). SCREEN space, at the gob's projected
        // point: {draw = fn} (fn(g, gob, sx, sy)) or {text = "…", color = {r,g,b[,a]}, offset = {x=,y=}}. The 3D
        // WORLD, anchored to the gob every frame: {image = asset}, {model = asset} or {ghost = "<res>"}, with
        // offset = {x=,y=,z=} in world units (z = up) and the entity's own scale/alpha/tint/a. A spec naming
        // none of the five is an error naming them, and one naming two is an error naming both. The game's own
        // overlays are READ-ONLY: an attach onto a native key, or a remove of one, raises naming the key.
        // hafen.ui.gobOverlay (the filter form and its 5 Hz sweep), gob:overlays() and follow= are HARD CUT: the
        // state lives on the gob now, so it dies with the gob and nothing is searched per frame.
        m.set("overlay", new VarArgFunction() {
            public Varargs invoke(Varargs a) {   // overlay() → narg 1 · (key) → 2 · (key, spec) / (key, nil) → 3
                LuaValue self = a.arg1();
                LuaGob h = handle(self, "overlay");
                Gob g = AddonManager.getgob(h.id);
                if(a.narg() < 2)                                  // read ALL — nil once the gob is gone
                    return (g == null) ? LuaValue.NIL : LuaOverlay.list(owner, g);
                LuaValue kv = a.arg(2);
                if(!kv.isstring() || kv.isnumber())    // LuaJ counts a number as a string: coerce nothing
                    throw new LuaError("gob:overlay(key[, spec]): the key must be a string -- it is YOUR name for"
                        + " this overlay (keys are per addon), and a native one is the overlay's resource name");
                String key = kv.tojstring();
                if(a.narg() < 3) {                                // read ONE
                    if(g == null)
                        return LuaValue.NIL;
                    LuaGobOverlay store = LuaGobOverlay.on(g);
                    if((store != null) && (store.get(owner, key) != null))
                        return LuaOverlay.of(owner, h.id, key, false);
                    return LuaGobOverlay.findNative(g, key) ? LuaOverlay.of(owner, h.id, key, true) : LuaValue.NIL;
                }
                LuaValue spec = a.arg(3);
                if(g == null)                                     // the gob is gone: nothing to attach it to, and
                    return LuaValue.NIL;                          //   nothing left to remove (it died with it)
                if(LuaGobOverlay.findNative(g, key))
                    throw new LuaError("gob:overlay(\"" + key + "\", ...): that key names one of the GAME's own"
                        + " overlays, which are read-only -- pick a key of your own (a native key is a resource"
                        + " name; gob:overlay() lists them)");
                if(spec.isnil()) {                                // REMOVE
                    LuaGobOverlay store = LuaGobOverlay.on(g);
                    if(store != null) {
                        LuaGobOverlay.Attach old = store.remove(owner, key);
                        if(old != null)
                            old.dispose();               // a world-space record owns a live entity; the map does not
                        LuaGobOverlay.prune(g);
                    }
                    return self;
                }
                // Parsed — and, for a world-space kind, BUILT — before anything is attached, so a bad spec or a
                // disposed asset handle raises with the gob left exactly as it was.
                LuaGobOverlay.Attach rec = LuaGobOverlay.Attach.of(owner, key, spec, h.id);
                LuaGobOverlay.Attach old;
                try {
                    old = LuaGobOverlay.ensure(g).put(rec);
                } catch(RuntimeException e) {
                    rec.dispose();                       // the gob refused the attrib: do not leak the entity we built
                    throw e;
                }
                if(old != null)
                    old.dispose();                       // REPLACE: the same key twice leaves ONE overlay
                return LuaOverlay.of(owner, h.id, key, false);
            }
        });
        m.set("isplayer", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Gob g = gob(self, "isplayer");
                // nil only when the gob is GONE; a gob whose name hasn't resolved yet is simply not a player.
                return (g == null) ? LuaValue.NIL : LuaValue.valueOf(AddonManager.gobIsPlayer(g));
            }
        });
        // kin() — the O(1) half of the Kin <-> Gob link (020.2): the SERVER marks a kinned player's gob with
        // the `ui/obj/buddy` attrib, which carries the buddy id, so this is a single attribute read. nil is
        // AMBIGUOUS on purpose: not on your roster / the gob is gone / it is not a player at all.
        m.set("kin", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Gob g = gob(self, "kin");
                Integer bid = LuaKin.buddyId(g);
                return (bid == null) ? LuaValue.NIL : LuaKin.of(owner, bid.intValue());
            }
        });
        // distance([other]) — world distance to another Gob; `other` defaults to the player.
        m.set("distance", new TwoArgFunction() {
            public LuaValue call(LuaValue self, LuaValue other) {
                Gob a = gob(self, "distance");
                Gob b;
                if(other.isnil()) {
                    b = AddonManager.playerGob();
                } else {
                    LuaGob h = resolve(other);
                    if(h == null)
                        throw new LuaError("gob:distance([other]) -- 'other' must be a Gob object (hafen.gob(id)), or nil for the player");
                    b = AddonManager.getgob(h.id);
                }
                if((a == null) || (b == null))
                    return LuaValue.NIL;
                Coord2d ra, rb;
                synchronized(a) { ra = a.rc; }
                synchronized(b) { rb = b.rc; }
                if((ra == null) || (rb == null))
                    return LuaValue.NIL;
                return LuaValue.valueOf(ra.dist(rb));
            }
        });
        return m;
    }

    // ---- self resolution ------------------------------------------------------------------------

    /** The handle behind a method's {@code self}, or a guiding error (a dot-call passes the wrong self). */
    private static LuaGob handle(LuaValue self, String method) {
        LuaGob h = resolve(self);
        if(h == null)
            throw new LuaError("gob:" + method + "() -- use a COLON call on a Gob object (hafen.gob(id), hafen.world.nearest(...), hafen.player():gob())");
        return h;
    }

    /** The LIVE gob behind a method's {@code self}: re-resolved every call, {@code null} once it is gone. */
    private static Gob gob(LuaValue self, String method) {
        return AddonManager.getgob(handle(self, method).id);
    }

    /**
     * {@code hafen.gob} itself: a <b>callable table</b> ({@code __call}) so {@code hafen.gob(id)} mints a handle
     * while {@code hafen.gob.health} reads as plain {@code nil} — the hard cut is visible from Lua (D-044). Any
     * number id is accepted, loaded or not ({@code gob:exists()} is the liveness test, and {@code follow=} needs
     * a handle for a gob that hasn't streamed in yet); a non-number is a guiding error.
     */
    static LuaValue factory(final Addon owner) {
        LuaTable gob = new LuaTable();
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.CALL, new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue idv = a.arg(2);        // arg1 = the callable table itself
                if(!idv.isnumber())
                    throw new LuaError("hafen.gob(id) expects a gob id (a number) -- the \"player\"/\"me\"/\"partyN\" tokens are gone; use hafen.player():gob()");
                return of(owner, (long)idv.todouble());
            }
        });
        gob.setmetatable(mt);
        return gob;
    }
}
