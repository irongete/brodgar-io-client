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
 * An <b>Opponent object</b> — who you are fighting ({@code hafen.fight():target()}). The combat view keeps one
 * of these per creature you are in a fight with and paints the one you have picked; this is that one.
 *
 * <p><b>{@code target:gob()} is what this entity exists for.</b> The combat target used to be a number the API
 * never published and could not resolve, so the creature you are fighting was the one thing in the world you
 * could see on screen and not read. It is now one verb, and like every other door onto a gob it is never
 * {@code nil}: an id the object cache does not hold answers a Gob whose {@code :exists()} is false.
 *
 * <p><b>The intern key is the gob id</b> (§2.4) — the only thing the server publishes about an opponent. The
 * combat view mints a fresh record whenever a fight starts, so keying on the record would call the same
 * creature two opponents across two fights.
 *
 * <p><b>What is deliberately not here.</b> An opponent carries no name and no moment-to-moment combat numbers:
 * everything you can read about the creature belongs to its gob, and is read there. There is no collection
 * either — {@code :target()} is the one who is picked, which is the question an addon has.
 *
 * <p><b>Threading.</b> The combat view's records are added and removed from a loader thread under the UI
 * monitor, so the list is walked inside it.
 */
public final class LuaOpponent {
    /** The opponent's gob id — the whole state of a handle. */
    public final long gobid;

    private LuaOpponent(long gobid) {
        this.gobid = gobid;
    }

    /** {@code tostring(opp)}: {@code Opponent(<gobid>)}. */
    public String toString() {
        return "Opponent(" + gobid + ")";
    }

    /** An interned Opponent object for {@code gobid} in {@code owner}'s env. */
    static LuaValue of(Addon owner, long gobid) {
        return owner.opponents.of(gobid);
    }

    /** The {@code LuaOpponent} behind a Lua value, or {@code null} for anything else. */
    static LuaOpponent resolve(LuaValue v) {
        if((v == null) || !v.isuserdata())
            return null;
        Object o = v.touserdata();
        return (o instanceof LuaOpponent) ? (LuaOpponent)o : null;
    }

    // ---- the per-addon intern cache + metatable ----------------------------------------------------

    /** One addon's Opponent cache and metatable (its {@link Addon#opponents}), keyed by gob id. */
    static final class Cache {
        private final Addon owner;
        private final Map<Long, Ref> live = new HashMap<Long, Ref>();
        private final ReferenceQueue<LuaValue> dead = new ReferenceQueue<LuaValue>();
        private LuaValue mt;

        Cache(Addon owner) {
            this.owner = owner;
        }

        synchronized LuaValue of(long gobid) {
            drain();
            Long key = Long.valueOf(gobid);
            Ref r = live.get(key);
            if(r != null) {
                LuaValue v = r.get();
                if(v != null)
                    return v;
                live.remove(key);
            }
            LuaValue v = LuaValue.userdataOf(new LuaOpponent(gobid), meta());
            live.put(key, new Ref(v, key, dead));
            return v;
        }

        private void drain() {
            Reference<? extends LuaValue> r;
            while((r = dead.poll()) != null) {
                Ref or = (Ref)r;
                if(live.get(or.key) == or)
                    live.remove(or.key);
            }
        }

        private LuaValue meta() {
            if(mt == null)
                mt = buildMeta(owner);
            return mt;
        }
    }

    private static final class Ref extends WeakReference<LuaValue> {
        final Long key;

        Ref(LuaValue v, Long key, ReferenceQueue<LuaValue> q) {
            super(v, q);
            this.key = key;
        }
    }

    // ---- the Opponent metatable ---------------------------------------------------------------------

    private static LuaValue buildMeta(final Addon owner) {
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, Retired.methodIndex("opponent", methods(owner)));
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
        // :exists() is false, exactly as hafen.world():gob():get(id) does.
        m.set("gob", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaGob.of(owner, handle(self, "gob").gobid);
            }
        });
        // exists() — are you still in a fight with them?
        m.set("exists", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(fighting(handle(self, "exists").gobid));
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
                + " (hafen.fight():target())");
        return h;
    }

    // ---- the reads ----------------------------------------------------------------------------------

    /** The combat view, or {@code null} before the HUD is up (it is created with the rest of the HUD). */
    private static Fightview view() {
        GameUI g = AddonManager.gui();
        return (g == null) ? null : g.fv;
    }

    /** Are you still in a fight with {@code gobid}? The predicate {@code :exists()} answers. */
    private static boolean fighting(long gobid) {
        Fightview fv = view();
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

    /** {@code hafen.fight():target()} — the opponent the combat view has picked, or {@code NIL} out of a fight. */
    static LuaValue target(Addon owner) {
        Fightview fv = view();
        if(fv == null)
            return LuaValue.NIL;
        Fightview.Relation rel;
        synchronized(LuaWidget.monitor(fv)) {   // `current` is reassigned from the "cur" uimsg off-thread
            rel = fv.current;
        }
        return ((rel == null) || rel.invalid) ? LuaValue.NIL : of(owner, rel.gobid);
    }
}
