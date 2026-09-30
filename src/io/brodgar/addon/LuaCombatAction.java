package io.brodgar.addon;

import haven.FightWnd;
import haven.Fightsess;
import haven.Fightview;
import haven.GameUI;
import haven.Indir;
import haven.Resource;
import haven.Utils;

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
 * A <b>CombatAction object</b> — one place of a character's combat row ({@code s:fight():action()}, 170.3): the
 * slot the client's key "Combat action n" presses. The row is the fight's own ({@code Fightsess.actions}, filled
 * by the server's {@code act} messages while a fight is up), not the deck the schools tab lays out: an edit in
 * the tab is the tab's until it is saved, and the key sends the row's slot number.
 *
 * <p><b>Ten places, always.</b> The client has ten combat keys ({@code Fightsess.kb_acts}), and a slot no key
 * reaches is one no player can use, so the row is those ten whatever the server's own row holds. Out of a fight
 * (no {@code Fightsess} in that character's HUD) every place is {@code :empty()}, and so is a place past the
 * server's row. The length is a literal rather than {@code kb_acts.length}: reading that field would run
 * {@code Fightsess}'s static initializer (textures and a {@code loadwait}) on whatever thread asked first.
 *
 * <p><b>The intern key is the place</b> (account, 0-based slot), the {@link LuaDeckCard} shape: a stashed action
 * follows the key rather than the manoeuvre in it, and the account is half the handle because every character
 * has its own row. Every read re-resolves the row through that character's HUD, copying the slot under the
 * row's monitor, where the loader thread writes {@code act} and {@code acool}.
 */
public final class LuaCombatAction {
    /** The places of the row: the client's own combat keys, "Combat action 1".."Combat action 10". */
    static final int SLOTS = 10;

    /** The account whose row this place is in. */
    public final String user;
    /** The 0-based slot, what {@code action:wire()} answers and the {@code use} message carries. */
    public final int slot;

    private LuaCombatAction(String user, int slot) {
        this.user = user;
        this.slot = slot;
    }

    /** {@code tostring(action)}: {@code CombatAction(<1-based position>)}. */
    public String toString() {
        return "CombatAction(" + (slot + 1) + ")";
    }

    /** An interned CombatAction object for {@code slot} of {@code user}'s row, in {@code owner}'s env. */
    static LuaValue of(Addon owner, String user, int slot) {
        return owner.combatActions.of(user, slot);
    }

    /** The {@code LuaCombatAction} behind a Lua value, or {@code null} for anything else. */
    static LuaCombatAction resolve(LuaValue v) {
        if((v == null) || !v.isuserdata())
            return null;
        Object o = v.touserdata();
        return (o instanceof LuaCombatAction) ? (LuaCombatAction)o : null;
    }

    // ---- the per-addon intern cache + metatable ----------------------------------------------------

    /** One addon's CombatAction cache and metatable (its {@link Addon#combatActions}), keyed by account and slot. */
    static final class Cache {
        private final Addon owner;
        private final Map<String, Map<Integer, Ref>> live = new HashMap<String, Map<Integer, Ref>>();
        private final ReferenceQueue<LuaValue> dead = new ReferenceQueue<LuaValue>();
        private LuaValue mt;

        Cache(Addon owner) {
            this.owner = owner;
        }

        synchronized LuaValue of(String user, int slot) {
            drain();
            Map<Integer, Ref> bys = live.get(user);
            if(bys == null)
                live.put(user, bys = new HashMap<Integer, Ref>());
            Integer key = Integer.valueOf(slot);
            Ref r = bys.get(key);
            if(r != null) {
                LuaValue v = r.get();
                if(v != null)
                    return v;
                bys.remove(key);
            }
            LuaValue v = LuaValue.userdataOf(new LuaCombatAction(user, slot), meta());
            bys.put(key, new Ref(v, user, key, dead));
            return v;
        }

        private void drain() {
            Reference<? extends LuaValue> r;
            while((r = dead.poll()) != null) {
                Ref ar = (Ref)r;
                Map<Integer, Ref> bys = live.get(ar.user);
                if(bys == null)
                    continue;
                if(bys.get(ar.key) == ar)     // not already replaced by a fresh handle for the same place
                    bys.remove(ar.key);
                if(bys.isEmpty())
                    live.remove(ar.user);
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
        final Integer key;

        Ref(LuaValue v, String user, Integer key, ReferenceQueue<LuaValue> q) {
            super(v, q);
            this.user = user;
            this.key = key;
        }
    }

    // ---- the CombatAction metatable -----------------------------------------------------------------

    private static LuaValue buildMeta(final Addon owner) {
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, Refusal.closedIndex("action", methods(owner),
            "one place of a character's combat row, the slot a combat key presses"));
        mt.set("__name", LuaValue.valueOf("CombatAction"));
        mt.set("__tostring", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaCombatAction h = resolve(self);
                return LuaValue.valueOf((h == null) ? "CombatAction(?)" : h.toString());
            }
        });
        return mt;
    }

    private static LuaTable methods(final Addon owner) {
        LuaTable m = new LuaTable();
        // index() — the 1-based position, the number :get(n) takes and "Combat action n" names. Always answers.
        m.set("index", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                return LuaValue.valueOf(handle(Args.only(a, 0, "action:index"), "index").slot + 1);
            }
        });
        // wire() — the server's 0-based slot number, what the row's messages carry. Always answers.
        m.set("wire", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                return LuaValue.valueOf(handle(Args.only(a, 0, "action:wire"), "wire").slot);
            }
        });
        // empty() — does the place hold nothing? True out of a fight and past the server's row.
        m.set("empty", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaCombatAction h = handle(Args.only(a, 0, "action:empty"), "empty");
                return LuaValue.valueOf(held(h.user, h.slot) == null);
            }
        });
        // res() — the manoeuvre's resource name, its identity; nil for an empty place or while it resolves.
        m.set("res", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaCombatAction h = handle(Args.only(a, 0, "action:res"), "res");
                Held s = held(h.user, h.slot);
                String r = (s == null) ? null : AddonManager.resIdent(s.res);
                return (r == null) ? LuaValue.NIL : LuaValue.valueOf(r);
            }
        });
        // name() — the manoeuvre's display name, the tooltip the row paints; nil for an empty place.
        m.set("name", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaCombatAction h = handle(Args.only(a, 0, "action:name"), "name");
                Held s = held(h.user, h.slot);
                String n = (s == null) ? null : AddonManager.resTipName(s.res, null);
                return (n == null) ? LuaValue.NIL : LuaValue.valueOf(n);
            }
        });
        // maneuver() — the entry of s:fight():maneuver() with the same resource, nil when the character's list
        // has none (or the place is empty).
        m.set("maneuver", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaCombatAction h = handle(Args.only(a, 0, "action:maneuver"), "maneuver");
                Held s = held(h.user, h.slot);
                String r = (s == null) ? null : AddonManager.resIdent(s.res);
                if(r == null)
                    return LuaValue.NIL;
                for(FightWnd.Action act : LuaManeuver.actions(h.user)) {
                    if(r.equals(AddonManager.resIdent(act.res)))
                        return LuaManeuver.of(owner, h.user, act);
                }
                return LuaValue.NIL;
            }
        });
        // cooldown() — the 0..1 fraction of this action's cooldown still to run, 0 when it is ready; nil for an
        // empty place. The dark pie the row paints over the icon.
        m.set("cooldown", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaCombatAction h = handle(Args.only(a, 0, "action:cooldown"), "cooldown");
                Held s = held(h.user, h.slot);
                return (s == null) ? LuaValue.NIL : LuaValue.valueOf(left(s.cs, s.ct));
            }
        });
        // info() — the one SNAPSHOT escape hatch: {res, name, cooldown}, nil for an empty place (the Slot shape).
        m.set("info", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaCombatAction h = handle(Args.only(a, 0, "action:info"), "info");
                Held s = held(h.user, h.slot);
                if(s == null)
                    return LuaValue.NIL;
                LuaTable t = new LuaTable();
                String r = AddonManager.resIdent(s.res), n = AddonManager.resTipName(s.res, null);
                if(r != null)
                    t.set("res", LuaValue.valueOf(r));
                if(n != null)
                    t.set("name", LuaValue.valueOf(n));
                t.set("cooldown", LuaValue.valueOf(left(s.cs, s.ct)));
                return t;
            }
        });
        return m;
    }

    /** The handle behind a method's {@code self}, or a guiding error (a dot-call passes the wrong self). */
    private static LuaCombatAction handle(LuaValue self, String method) {
        LuaCombatAction h = resolve(self);
        if(h == null)
            throw new LuaError("action:" + method + "() — use a COLON call on a CombatAction object ("
                + CharApi.FA + ":get(n), " + CharApi.FA + ":list()[n])");
        return h;
    }

    // ---- the reads ----------------------------------------------------------------------------------

    /** One place's content, copied under the row's monitor. */
    private static final class Held {
        final Indir<Resource> res;
        final double cs, ct;

        Held(Indir<Resource> res, double cs, double ct) {
            this.res = res;
            this.cs = cs;
            this.ct = ct;
        }
    }

    /**
     * <b>That character's</b> combat row, or {@code null} out of a fight: the {@code Fightsess} the server puts
     * up for a fight is a direct child of the HUD with no field of its own, so it is found by class under the
     * HUD's monitor.
     */
    static Fightsess row(String user) {
        GameUI g = AddonManager.gameui(user);
        if(g == null)
            return null;
        synchronized(LuaWidget.monitor(g)) {
            return g.getchild(Fightsess.class);
        }
    }

    /** What place {@code slot} of that character's row holds, or {@code null} for an empty place or no row. */
    private static Held held(String user, int slot) {
        Fightsess fs = row(user);
        if(fs == null)
            return null;
        synchronized(LuaWidget.monitor(fs)) {
            if((slot < 0) || (slot >= fs.actions.length) || (fs.actions[slot] == null))
                return null;
            Fightsess.Action act = fs.actions[slot];
            return new Held(act.res, act.cs, act.ct);
        }
    }

    /**
     * The ten places' manoeuvres, copied under the row's monitor: {@code null} for an empty place, and all
     * {@code null} for no row. What {@code CharApi}'s combat-row adapter diffs.
     */
    static List<Indir<Resource>> contents(Fightsess fs) {
        List<Indir<Resource>> out = new ArrayList<Indir<Resource>>(SLOTS);
        for(int n = 0; n < SLOTS; n++)
            out.add(null);
        if(fs == null)
            return out;
        synchronized(LuaWidget.monitor(fs)) {
            for(int n = 0; (n < SLOTS) && (n < fs.actions.length); n++)
                out.set(n, (fs.actions[n] == null) ? null : fs.actions[n].res);
        }
        return out;
    }

    /** How much of a cooldown from {@code cs} to {@code ct} is left now, as a {@code 0..1} fraction. */
    static double left(double cs, double ct) {
        double now = Utils.rtime();
        if((now >= ct) || (ct <= cs))
            return 0;
        return Math.min(1, (ct - now) / (ct - cs));
    }

    /** {@code s:fight():cooldown()} — the global cooldown left, {@code NIL} out of a fight. */
    static LuaValue globalCooldown(String user) {
        Fightview fv = LuaOpponent.view(user);
        if((fv == null) || (row(user) == null))
            return LuaValue.NIL;
        double cs, ct;
        synchronized(LuaWidget.monitor(fv)) {   // atkcs/atkct are written from the "atkc" uimsg off-thread
            cs = fv.atkcs;
            ct = fv.atkct;
        }
        return LuaValue.valueOf(left(cs, ct));
    }

    /** {@code s:fight():last()} — the resource name of the manoeuvre that character used last, {@code NIL} out of a fight. */
    static LuaValue lastOwn(String user) {
        Fightview fv = LuaOpponent.view(user);
        if((fv == null) || (row(user) == null))
            return LuaValue.NIL;
        Indir<Resource> res;
        synchronized(LuaWidget.monitor(fv)) {   // lastact is written from the "used" uimsg off-thread
            res = fv.lastact;
        }
        String r = (res == null) ? null : AddonManager.resIdent(res);
        return (r == null) ? LuaValue.NIL : LuaValue.valueOf(r);
    }

    // ---- the collection -----------------------------------------------------------------------------

    /**
     * {@code s:fight():action()} — the ten places of THAT character's combat row, in key order, every one of them
     * a CombatAction whatever it holds (the action bar's shape). {@code :get(n)} takes the 1-based position; 0 is
     * refused naming {@code action:wire()}, and a position past ten naming the range. A string filter matches the
     * manoeuvre's resource and display name, the needle {@code s:fight():maneuver()} matches too. Minted once
     * per (addon, session) by {@code CharApi.fight}.
     */
    static LuaValue collection(final Addon owner, final String user) {
        return LuaCollection.create(CharApi.FA, new LuaCollection.Source() {
            public List<LuaValue> members() {
                List<LuaValue> out = new ArrayList<LuaValue>(SLOTS);
                for(int n = 0; n < SLOTS; n++)
                    out.add(of(owner, user, n));
                return out;
            }

            // An EMPTY place has no resource: it matches no string filter rather than refusing the filter.
            public String needle(LuaValue member) {
                LuaCombatAction h = resolve(member);
                Held s = (h == null) ? null : held(h.user, h.slot);
                if(s == null)
                    return "";
                String r = AddonManager.resIdent(s.res), n = AddonManager.resTipName(s.res, null);
                return ((r == null) ? "" : r) + "\n" + ((n == null) ? "" : n);
            }

            /** These have a name, so a string filter is a substring test over {@link #needle}. */
            public boolean named() {
                return true;
            }

            public boolean addressable() {
                return true;
            }

            public LuaValue getMember(LuaValue key) {
                int n = Args.integer(key, CharApi.FA + ":get", "n", "the 1-based position action:index()"
                                     + " answers, 1.." + SLOTS + ": :get(1) is Combat action 1");
                if(n == 0)
                    throw new LuaError(CharApi.FA + ":get(n): the key is the 1-based position action:index()"
                        + " answers, so :get(1) is Combat action 1; the server's 0-based slot number is"
                        + " action:wire()");
                if((n < 1) || (n > SLOTS))
                    throw new LuaError(CharApi.FA + ":get(n): position out of range (1.." + SLOTS + "), got " + n
                        + " — the row is the client's " + SLOTS + " combat keys");
                return of(owner, user, n - 1);
            }

            /** The row is the client's keys: every position in range is a place, holding something or not. */
            public LuaCollection.Missing missing() {
                return LuaCollection.Missing.MINT;
            }

            /** The key is the 1-based position {@code action:index()} answers. */
            public String keyName() {
                return "n";
            }
        }, null);
    }
}
