package io.brodgar.addon;

import haven.Fightview;
import haven.GameUI;

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
 * An <b>Opponent object</b> — who a character is fighting ({@code s:fight():target()}). That character's
 * combat view keeps one of these per creature it is in a fight with and paints the one it has picked; this is
 * that one.
 *
 * <p><b>{@code target:gob()} is what this entity exists for.</b> The creature is otherwise the one thing in
 * the world you can see and not read: the view publishes a number and nothing else. It is one verb, and like
 * every other door onto a gob it is never {@code nil} — an id the object cache does not hold answers a Gob
 * whose {@code :exists()} is false.
 *
 * <p><b>The intern key is the gob id</b> (§2.4) — the only thing the server publishes about an opponent. The
 * combat view mints a fresh record whenever a fight starts, so keying on the record would call the same
 * creature two opponents across two fights.
 *
 * <p><b>And a gob id counts inside one session's object cache</b> (077.4), which is why the account is half
 * the handle: two characters fighting are two fights, each with its own view and its own ids, and id 4711 in
 * one of them is not the creature id 4711 names in the other. Two levels of intern map, on
 * {@code (account, id)} — the {@link LuaGob} shape, and what makes {@code target:gob()} resolve in the same
 * cache {@code s:world():gob():get(id)} reads.
 *
 * <p><b>What is deliberately not here.</b> An opponent carries no name and no moment-to-moment combat numbers:
 * everything you can read about the creature belongs to its gob, and is read there. There is no collection
 * either — {@code :target()} is the one who is picked, which is the question an addon has.
 *
 * <p><b>Threading.</b> The combat view's records are added and removed from a loader thread under the UI
 * monitor, so the list is walked inside it.
 */
public final class LuaOpponent {
    /** The account whose fight this is — half the address, and the cache the id resolves in. */
    public final String user;
    /** The opponent's gob id, in that session's own object cache. */
    public final long gobid;

    private LuaOpponent(String user, long gobid) {
        this.user = user;
        this.gobid = gobid;
    }

    /** {@code tostring(opp)}: {@code Opponent(<gobid>)}. */
    public String toString() {
        return "Opponent(" + gobid + ")";
    }

    /** An interned Opponent object for {@code gobid} <b>in {@code user}'s fight</b>, in {@code owner}'s env. */
    static LuaValue of(Addon owner, String user, long gobid) {
        return owner.opponents.of(user, gobid);
    }

    /** The {@code LuaOpponent} behind a Lua value, or {@code null} for anything else. */
    static LuaOpponent resolve(LuaValue v) {
        if((v == null) || !v.isuserdata())
            return null;
        Object o = v.touserdata();
        return (o instanceof LuaOpponent) ? (LuaOpponent)o : null;
    }

    // ---- the per-addon intern cache + metatable ----------------------------------------------------

    /**
     * One addon's Opponent cache and metatable (its {@link Addon#opponents}), keyed by the <b>account plus</b>
     * the gob id: an id is one session's object cache's, so the same number in two fights is two creatures.
     * Two levels of map, the {@link LuaGob} shape.
     */
    static final class Cache {
        private final Addon owner;
        private final Map<String, Map<Long, Ref>> live = new HashMap<String, Map<Long, Ref>>();
        private final ReferenceQueue<LuaValue> dead = new ReferenceQueue<LuaValue>();
        private LuaValue mt;

        Cache(Addon owner) {
            this.owner = owner;
        }

        synchronized LuaValue of(String user, long gobid) {
            drain();
            Map<Long, Ref> byid = live.get(user);
            if(byid == null)
                live.put(user, byid = new HashMap<Long, Ref>());
            Long key = Long.valueOf(gobid);
            Ref r = byid.get(key);
            if(r != null) {
                LuaValue v = r.get();
                if(v != null)
                    return v;
                byid.remove(key);
            }
            LuaValue v = LuaValue.userdataOf(new LuaOpponent(user, gobid), meta());
            byid.put(key, new Ref(v, user, key, dead));
            return v;
        }

        private void drain() {
            Reference<? extends LuaValue> r;
            while((r = dead.poll()) != null) {
                Ref or = (Ref)r;
                Map<Long, Ref> byid = live.get(or.user);
                if(byid == null)
                    continue;
                if(byid.get(or.key) == or)     // not already replaced by a fresh handle for the same id
                    byid.remove(or.key);
                if(byid.isEmpty())
                    live.remove(or.user);
            }
        }

        private LuaValue meta() {
            if(mt == null)
                mt = buildMeta(owner);
            return mt;
        }
    }

    private static final class Ref extends WeakReference<LuaValue> {
        final String user;
        final Long key;

        Ref(LuaValue v, String user, Long key, ReferenceQueue<LuaValue> q) {
            super(v, q);
            this.user = user;
            this.key = key;
        }
    }

    // ---- the Opponent metatable ---------------------------------------------------------------------

    private static LuaValue buildMeta(final Addon owner) {
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, Refusal.closedIndex("opponent", methods(owner),
            "someone you are fighting"));
        mt.set("__name", LuaValue.valueOf("Opponent"));
        mt.set("__tostring", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaOpponent h = resolve(self);
                return LuaValue.valueOf((h == null) ? "Opponent(?)" : h.toString());
            }
        });
        return mt;
    }

    private static LuaTable methods(final Addon owner) {
        LuaTable m = new LuaTable();
        // id() — the opponent's gob id, the only thing the server publishes about them.
        m.set("id", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf((double)handle(self, "id").gobid);
            }
        });
        // gob() — the creature itself. NEVER nil: an id the object cache does not hold answers a Gob whose
        // :exists() is false, exactly as s:world():gob():get(id) does. Resolved in the object cache of the
        // session whose fight this is (077.4), which is the cache the id came out of.
        m.set("gob", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaOpponent h = handle(self, "gob");
                return LuaGob.of(owner, h.user, h.gobid);
            }
        });
        // exists() — is that character still in a fight with them?
        m.set("exists", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaOpponent h = handle(self, "exists");
                return LuaValue.valueOf(fighting(h.user, h.gobid));
            }
        });
        // info() — the one SNAPSHOT escape hatch.
        m.set("info", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaTable t = new LuaTable();
                t.set("id", LuaValue.valueOf((double)handle(self, "info").gobid));
                return t;
            }
        });
        return m;
    }

    private static LuaOpponent handle(LuaValue self, String method) {
        LuaOpponent h = resolve(self);
        if(h == null)
            throw new LuaError("opp:" + method + "() — use a COLON call on an Opponent object"
                + " (" + CharApi.FT + ":target())");
        return h;
    }

    // ---- the reads ----------------------------------------------------------------------------------

    /** <b>That character's</b> combat view, or {@code null} before its HUD is up (it is created with the rest of the HUD). */
    private static Fightview view(String user) {
        GameUI g = AddonManager.gameui(user);
        return (g == null) ? null : g.fv;
    }

    /** Is {@code user} still in a fight with {@code gobid}? The predicate {@code :exists()} answers. */
    private static boolean fighting(String user, long gobid) {
        Fightview fv = view(user);
        if(fv == null)
            return false;
        synchronized(LuaWidget.monitor(fv)) {   // lsrel is added to / removed from on a loader thread
            for(Fightview.Relation rel : fv.lsrel) {
                if((rel.gobid == gobid) && !rel.invalid)
                    return true;
            }
        }
        return false;
    }

    /** {@code s:fight():target()} — the opponent <b>that character's</b> combat view has picked, or {@code NIL} out of a fight. */
    static LuaValue target(Addon owner, String user) {
        Fightview fv = view(user);
        if(fv == null)
            return LuaValue.NIL;
        Fightview.Relation rel;
        synchronized(LuaWidget.monitor(fv)) {   // `current` is reassigned from the "cur" uimsg off-thread
            rel = fv.current;
        }
        return ((rel == null) || rel.invalid) ? LuaValue.NIL : of(owner, user, rel.gobid);
    }
}
