package io.brodgar.addon;

import haven.Fightview;
import haven.GameUI;
import haven.Indir;
import haven.Resource;

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
import java.util.List;
import java.util.Map;

/**
 * An <b>Opponent object</b> — one creature a character is fighting: a relation its combat view keeps
 * ({@code Fightview.Relation}), reached through {@code s:fight():opponent()}, the collection of every one of them,
 * whose {@code :current()} is the one the view has picked (170.2).
 *
 * <p><b>The creature is its gob, the relation is this object.</b> Everything about the creature (name, health,
 * position) is read off {@code opponent:gob()}, which is never {@code nil}: an id the object cache does not hold
 * answers a Gob whose {@code :exists()} is false. What the view holds about the RELATION is read here: the IP
 * pair it paints ({@code :ip()}), the two halves of the give button ({@code :give()}), the manoeuvre they
 * used last ({@code :last()}) and the openings drawn beside the creature ({@code :opening()}).
 *
 * <p><b>The intern key is the gob id</b> (§2.4). The combat view mints a fresh record whenever a fight starts, so
 * keying on the record would call the same creature two opponents across two fights. <b>And a gob id counts
 * inside one session's object cache</b> (077.4), which is why the account is half the handle: two characters
 * fighting are two fights, each with its own view and its own ids, and id 4711 in one of them is not the
 * creature id 4711 names in the other. Two levels of intern map, on {@code (account, id)}: the {@link LuaGob}
 * shape, and what makes {@code opponent:gob()} resolve in the same cache {@code s:world():gob():get(id)} reads.
 *
 * <p><b>Once the fight with them ends</b> the relation's reads go {@code nil}, {@code :opening()} is empty and
 * {@code :exists()} is false, while {@code :id()} and {@code :gob()} go on answering. A later fight with the
 * same creature hands back the same object.
 *
 * <p><b>Threading.</b> The combat view's records are added, removed and rewritten from a loader thread under the
 * UI monitor, and "Switch targets" reorders them on the UI thread, so every read copies what it needs inside
 * {@link LuaWidget#monitor} and mints its handles outside it.
 */
public final class LuaOpponent {
    /** The account whose fight this is — half the address, and the cache the id resolves in. */
    public final String user;
    /** The opponent's gob id, in that session's own object cache. */
    public final long gobid;

    /**
     * The two halves of the give state {@code Relation.gst}, as {@code GiveButton.draw} paints them: bit 1 the
     * left half, the side the combat view paints as yours, and bit 2 the right.
     */
    static final int MINE = 1, THEIRS = 2;

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
        // 170.2: every verb counts its arguments (Args.only), where a OneArgFunction dropped a surplus one unseen.
        // id() — the opponent's gob id, the only thing the server publishes about them.
        m.set("id", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                return LuaValue.valueOf((double)handle(Args.only(a, 0, "opponent:id"), "id").gobid);
            }
        });
        // gob() — the creature itself. NEVER nil: an id the object cache does not hold answers a Gob whose
        // :exists() is false, exactly as s:world():gob():get(id) does. Resolved in the object cache of the
        // session whose fight this is (077.4), which is the cache the id came out of.
        m.set("gob", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaOpponent h = handle(Args.only(a, 0, "opponent:gob"), "gob");
                return LuaGob.of(owner, h.user, h.gobid);
            }
        });
        // exists() — is that character still in a fight with them?
        m.set("exists", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaOpponent h = handle(Args.only(a, 0, "opponent:exists"), "exists");
                return LuaValue.valueOf(fighting(h.user, h.gobid));
            }
        });
        // ip() — 170.2: the IP pair the view paints, {mine, theirs}. Upstream's Relation.ip is yours (painted on
        // the left) and Relation.oip theirs. nil once the fight with them has ended.
        m.set("ip", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaOpponent h = handle(Args.only(a, 0, "opponent:ip"), "ip");
                int[] n = numbers(h.user, h.gobid);
                return (n == null) ? LuaValue.NIL : ip(n);
            }
        });
        // give() — 170.2: the give button's two halves, {mine, theirs}, as booleans. nil once the fight with them
        // has ended.
        m.set("give", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaOpponent h = handle(Args.only(a, 0, "opponent:give"), "give");
                int[] n = numbers(h.user, h.gobid);
                return (n == null) ? LuaValue.NIL : give(n);
            }
        });
        // last() — 170.3: the resource name of the manoeuvre they used last (Relation.lastact, from the "ruse"
        // uimsg). A string rather than a Maneuver: theirs need not be one this character knows. nil before their
        // first, and once the fight with them has ended.
        m.set("last", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaOpponent h = handle(Args.only(a, 0, "opponent:last"), "last");
                Indir<Resource> res = lastact(h.user, h.gobid);
                String r = (res == null) ? null : AddonManager.resIdent(res);
                return (r == null) ? LuaValue.NIL : LuaValue.valueOf(r);
            }
        });
        // opening() — 170.1: that opponent's openings, the list the combat view paints beside them. A view minted
        // per call; empty once the fight with them has ended.
        m.set("opening", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaOpponent h = handle(Args.only(a, 0, "opponent:opening"), "opening");
                return LuaBuff.opponentCollection(owner, h.user, h.gobid);
            }
        });
        // info() — the one SNAPSHOT escape hatch: {id, ip, give, last} while the fight lasts, {id} after it.
        m.set("info", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaOpponent h = handle(Args.only(a, 0, "opponent:info"), "info");
                LuaTable t = new LuaTable();
                t.set("id", LuaValue.valueOf((double)h.gobid));
                int[] n = numbers(h.user, h.gobid);
                if(n != null) {
                    t.set("ip", ip(n));
                    t.set("give", give(n));
                    Indir<Resource> res = lastact(h.user, h.gobid);
                    String r = (res == null) ? null : AddonManager.resIdent(res);
                    if(r != null)
                        t.set("last", LuaValue.valueOf(r));
                }
                return t;
            }
        });
        return m;
    }

    private static LuaOpponent handle(LuaValue self, String method) {
        LuaOpponent h = resolve(self);
        if(h == null)
            throw new LuaError("opponent:" + method + "() — use a COLON call on an Opponent object"
                + " (" + CharApi.FO + ":current(), " + CharApi.FO + ":list()[n])");
        return h;
    }

    /** {@code {mine = m, theirs = t}}: a relation's two sides, the shape shapes.md names. */
    private static LuaTable pair(LuaValue mine, LuaValue theirs) {
        LuaTable t = new LuaTable();
        t.set("mine", mine);
        t.set("theirs", theirs);
        return t;
    }

    /** The IP pair out of {@link #numbers}. */
    private static LuaTable ip(int[] n) {
        return pair(LuaValue.valueOf(n[0]), LuaValue.valueOf(n[1]));
    }

    /** The give state out of {@link #numbers}, as its two halves. */
    private static LuaTable give(int[] n) {
        return pair(LuaValue.valueOf((n[2] & MINE) != 0), LuaValue.valueOf((n[2] & THEIRS) != 0));
    }

    // ---- the reads ----------------------------------------------------------------------------------

    /**
     * <b>That character's</b> combat view, or {@code null} before its HUD is up. {@code GameUI.fv} is never
     * cleared, so a view that has left the tree answers {@code null} too (170.1).
     */
    static Fightview view(String user) {
        GameUI g = AddonManager.gameui(user);
        Fightview fv = (g == null) ? null : g.fv;
        return ((fv == null) || (fv.ui == null) || !fv.hasparent(fv.ui.root)) ? null : fv;
    }

    /** Is {@code user} still in a fight with {@code gobid}? The predicate {@code :exists()} answers. */
    static boolean fighting(String user, long gobid) {
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

    /**
     * {@code {ip, oip, gst}} of the relation with {@code gobid}, copied under the view's monitor, or {@code null}
     * once the fight with them has ended. The three ints are written from the {@code new}/{@code upd} uimsgs.
     */
    static int[] numbers(String user, long gobid) {
        Fightview fv = view(user);
        if(fv == null)
            return null;
        synchronized(LuaWidget.monitor(fv)) {
            for(Fightview.Relation rel : fv.lsrel) {
                if((rel.gobid == gobid) && !rel.invalid)
                    return new int[] {rel.ip, rel.oip, rel.gst};
            }
        }
        return null;
    }

    /**
     * The manoeuvre the relation with {@code gobid} used last, copied under the view's monitor; {@code null}
     * before their first and once the fight with them has ended.
     */
    static Indir<Resource> lastact(String user, long gobid) {
        Fightview fv = view(user);
        if(fv == null)
            return null;
        synchronized(LuaWidget.monitor(fv)) {
            for(Fightview.Relation rel : fv.lsrel) {
                if((rel.gobid == gobid) && !rel.invalid)
                    return rel.lastact;
            }
        }
        return null;
    }

    /** The gob ids of the live relations, in the view's own order, copied under its monitor. */
    static List<Long> ids(String user) {
        List<Long> out = new ArrayList<Long>();
        Fightview fv = view(user);
        if(fv == null)
            return out;
        synchronized(LuaWidget.monitor(fv)) {
            for(Fightview.Relation rel : fv.lsrel) {
                if(!rel.invalid)
                    out.add(Long.valueOf(rel.gobid));
            }
        }
        return out;
    }

    /**
     * {@code s:fight():opponent():current()} — the opponent <b>that character's</b> combat view has picked, or
     * {@code NIL} out of a fight.
     */
    static LuaValue current(Addon owner, String user) {
        Fightview fv = view(user);
        if(fv == null)
            return LuaValue.NIL;
        boolean live;
        Fightview.Relation rel;
        synchronized(LuaWidget.monitor(fv)) {   // `current` is reassigned from the "cur" uimsg off-thread
            rel = fv.current;
            // audit2 B06: and `invalid` is read HERE, inside the same block. Relation.remove() sets it from
            // the message thread under this very monitor, so a read taken after the block was a read of a
            // field with no barrier behind it -- an opponent invalidated in that window came back as live.
            live = (rel != null) && !rel.invalid;
        }
        return live ? of(owner, user, rel.gobid) : LuaValue.NIL;
    }

    // ---- the collection -----------------------------------------------------------------------------

    /**
     * {@code s:fight():opponent()} (170.2) — every opponent THAT character's combat view holds, in the view's own
     * order ({@code Fightview.lsrel}: a new relation joins at the front, the target is moved to the front, and
     * "Switch targets" rotates it). Addressed by gob id, the one thing the server publishes about an opponent; a
     * string filter is refused, since an opponent has no name of its own. {@code :current()} is the
     * distinguished member, the one the view has picked (§2.2). Minted once per (addon, session) by
     * {@code CharApi.fight}.
     */
    static LuaValue collection(final Addon owner, final String user) {
        LuaTable extra = new LuaTable();
        extra.set("current", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaCollection.receiver(a.arg1(), CharApi.FO, "current");
                if(Args.passed(a, 2))
                    throw new LuaError(CharApi.FO + ":current() takes no argument — it reads the opponent that"
                        + " character's fight has picked");
                return current(owner, user);
            }
        });
        return LuaCollection.create(CharApi.FO, new LuaCollection.Source() {
            public List<LuaValue> members() {
                List<Long> ids = ids(user);
                List<LuaValue> out = new ArrayList<LuaValue>(ids.size());
                for(int i = 0; i < ids.size(); i++)
                    out.add(of(owner, user, ids.get(i).longValue()));
                return out;
            }

            public boolean addressable() {
                return true;
            }

            public LuaValue getMember(LuaValue key) {
                long id = Args.integer(key, CharApi.FO + ":get", "gobId", "a GOB ID — an opponent has no name"
                                       + " of its own (opponent:gob():name() is the creature's)",
                                       -Args.EXACT, Args.EXACT);
                return fighting(user, id) ? of(owner, user, id) : LuaValue.NIL;
            }

            /** An opponent is addressed by gob id: it has no name of its own. */
            public String keyName() {
                return "gobId";
            }
        }, extra);
    }
}
