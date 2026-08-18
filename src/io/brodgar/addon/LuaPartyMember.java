package io.brodgar.addon;

import haven.Coord2d;
import haven.Party;

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
 * A <b>PartyMember object</b> — one member of the party you are in ({@code hafen.party()}). <b>The section
 * object IS the roster</b> (uniform grammar §2.1): {@code hafen.party()} is the collection,
 * {@code hafen.party():get(gobId)} is one member and {@code hafen.party():leader()} the distinguished one.
 *
 * <p><b>{@code member:gob()} is what this entity exists for.</b> The roster has always published a gob id and
 * nothing that resolved it, so reaching a party member's live object meant handing that number back to another
 * section. It is now one verb, and it is never {@code nil}: an id the object cache does not hold answers a Gob
 * whose {@code :exists()} is false, which is the same asymmetry {@code s:world():gob():get(id)} has.
 *
 * <p><b>The intern key is the gob id</b> (§2.4), because that is the only thing the server publishes about a
 * member. The engine keeps {@code Party.Member} objects across a roster push — {@code Partyview}'s
 * {@code "list"} message rebuilds the map but carries the existing member over for an id that stayed — and
 * mints a fresh one where somebody joined, so keying on the object would call one member two. Keying on the id
 * also makes a stashed member self-heal: leave the party and rejoin, and the same handle is live again.
 *
 * <p><b>A party member has no name.</b> The client is never sent one — a member is an id, a position, a colour
 * and the leader flag. The name you see over their head belongs to the gob, so it is
 * {@code member:gob():name()}.
 *
 * <p><b>The position may be absent.</b> {@code Party.Member.getc()} is the live gob position while the member
 * is in view and the last-known one otherwise, and it is {@code null} until the server has sent either — so
 * {@code member:position()} is genuinely {@code nil} for a member you have never seen.
 *
 * <p><b>Threading.</b> {@code party.memb} is replaced wholesale from the network thread, so every read takes
 * the map reference once and works off a {@code values()} copy; the {@link Cache} map is guarded on its own
 * monitor (UI + REPL threads touch it).
 */
public final class LuaPartyMember {
    /** The member's gob id — the whole state of a handle. */
    public final long gobid;

    private LuaPartyMember(long gobid) {
        this.gobid = gobid;
    }

    /** {@code tostring(member)}: {@code PartyMember(<gobid>)}. */
    public String toString() {
        return "PartyMember(" + gobid + ")";
    }

    /** An interned PartyMember object for {@code gobid} in {@code owner}'s env. */
    static LuaValue of(Addon owner, long gobid) {
        return owner.partyMembers.of(gobid);
    }

    /** The {@code LuaPartyMember} behind a Lua value, or {@code null} for anything else. */
    static LuaPartyMember resolve(LuaValue v) {
        if((v == null) || !v.isuserdata())
            return null;
        Object o = v.touserdata();
        return (o instanceof LuaPartyMember) ? (LuaPartyMember)o : null;
    }

    // ---- the per-addon intern cache + metatable ----------------------------------------------------

    /** One addon's PartyMember cache and metatable (its {@link Addon#partyMembers}), keyed by gob id. */
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
            LuaValue v = LuaValue.userdataOf(new LuaPartyMember(gobid), meta());
            live.put(key, new Ref(v, key, dead));
            return v;
        }

        private void drain() {
            Reference<? extends LuaValue> r;
            while((r = dead.poll()) != null) {
                Ref pr = (Ref)r;
                if(live.get(pr.key) == pr)
                    live.remove(pr.key);
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

    // ---- the PartyMember metatable ------------------------------------------------------------------

    private static LuaValue buildMeta(final Addon owner) {
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, Retired.methodIndex("partymember", methods(owner)));
        mt.set("__name", LuaValue.valueOf("PartyMember"));
        mt.set("__tostring", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaPartyMember h = resolve(self);
                return LuaValue.valueOf((h == null) ? "PartyMember(?)" : h.toString());
            }
        });
        return mt;
    }

    private static LuaTable methods(final Addon owner) {
        LuaTable m = new LuaTable();
        // id() — the member's gob id, the only thing the server publishes about them.
        m.set("id", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf((double)handle(self, "id").gobid);
            }
        });
        // gob() — the member's live object. NEVER nil: an id the object cache does not hold answers a Gob
        // whose :exists() is false, exactly as s:world():gob():get(id) does.
        m.set("gob", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                // The party is the drawn character's (077 gives it its own address).
                return LuaGob.of(owner, AddonManager.drawnUser(), handle(self, "gob").gobid);
            }
        });
        // position() — where the member is: the live gob position while they are in view, the last-known one
        // otherwise, and nil for a member the server has not placed at all.
        m.set("position", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Party.Member pm = member(handle(self, "position").gobid);
                return (pm == null) ? LuaValue.NIL : LuaPosition.of(owner, AddonManager.drawnUser(), coord(pm));
            }
        });
        // color() — the party colour the client paints this member with, or nil before one arrives.
        m.set("color", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Party.Member pm = member(handle(self, "color").gobid);
                return ((pm == null) || (pm.col == null)) ? LuaValue.NIL : AddonManager.color(pm.col);
            }
        });
        // leader() — is this member the one leading the party?
        m.set("leader", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Party.Member pm = member(handle(self, "leader").gobid);
                Party p = CharApi.party();
                return LuaValue.valueOf((pm != null) && (p != null) && (p.leader == pm));
            }
        });
        // exists() — is this member still in the party?
        m.set("exists", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(member(handle(self, "exists").gobid) != null);
            }
        });
        // info() — the one SNAPSHOT escape hatch.
        m.set("info", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return snapshot(member(handle(self, "info").gobid));
            }
        });
        return m;
    }

    private static LuaPartyMember handle(LuaValue self, String method) {
        LuaPartyMember h = resolve(self);
        if(h == null)
            throw new LuaError("member:" + method + "() — use a COLON call on a PartyMember object"
                + " (hafen.party():list()[i])");
        return h;
    }

    // ---- the reads ----------------------------------------------------------------------------------

    /** The live {@link Party.Member} for {@code gobid}, or {@code null} once they are out of the party. */
    static Party.Member member(long gobid) {
        Party p = CharApi.party();
        if(p == null)
            return null;
        try {
            return p.memb.get(Long.valueOf(gobid));
        } catch(RuntimeException e) {   // the map is replaced wholesale off-thread
            return null;
        }
    }

    /** Where a member is, {@code null} when the server has not placed them at all (Loading-guarded). */
    private static Coord2d coord(Party.Member pm) {
        try {
            return pm.getc();
        } catch(RuntimeException e) {
            return null;
        }
    }

    /** The documented {@code PartyMember} snapshot — there is no name, and the position may be absent. */
    private static LuaValue snapshot(Party.Member pm) {
        if(pm == null)
            return LuaValue.NIL;
        LuaTable t = new LuaTable();
        t.set("id", LuaValue.valueOf((double)pm.gobid));
        Coord2d c = coord(pm);
        if(c != null) {
            t.set("x", LuaValue.valueOf(c.x));
            t.set("y", LuaValue.valueOf(c.y));
        }
        if(pm.col != null)
            t.set("color", AddonManager.color(pm.col));
        Party p = CharApi.party();
        t.set("leader", LuaValue.valueOf((p != null) && (p.leader == pm)));
        return t;
    }

    // ---- the collection -----------------------------------------------------------------------------

    /**
     * {@code hafen.party()} — the roster, in the party sequence order the client itself uses. Addressable by
     * gob id; {@code :leader()} is the distinguished member (§2.2's R8) rather than a second accessor. There
     * is no {@code :add}/{@code :remove}: joining and leaving a party is a menu action, so it goes through
     * {@code hafen.menugrid():get(name):use()}. A <b>string</b> filter is refused naming why — party members
     * have no name.
     */
    static LuaValue collection(final Addon owner) {
        LuaTable extra = new LuaTable();
        extra.set("leader", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaCollection.receiver(a.arg1(), "leader");
                if(Args.passed(a, 2))
                    throw new LuaError("hafen.party():leader() takes no arguments — it reads who leads the"
                        + " party, and the party leader is not something an addon sets");
                Party p = CharApi.party();
                return ((p == null) || (p.leader == null)) ? LuaValue.NIL : of(owner, p.leader.gobid);
            }
        });
        return LuaCollection.create("hafen.party()", new LuaCollection.Source() {
            public List<LuaValue> members() {
                List<Party.Member> pms = CharApi.partyMembers();
                List<LuaValue> out = new ArrayList<LuaValue>(pms.size());
                for(int i = 0; i < pms.size(); i++)
                    out.add(of(owner, pms.get(i).gobid));
                return out;
            }

            public boolean addressable() {
                return true;
            }

            public LuaValue getMember(LuaValue key) {
                if(!key.isnumber())
                    throw new LuaError("hafen.party():get(gobId): a party member is addressed by GOB ID, a"
                        + " number — they have no name (member:gob():name() is the name over their head)");
                long id = (long)key.todouble();
                return (member(id) == null) ? LuaValue.NIL : of(owner, id);
            }
        }, extra);
    }
}
