package io.brodgar.addon;

import haven.FightWnd;
import haven.UI;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.OneArgFunction;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * A <b>Maneuver object</b> — one combat maneuver or attack a character knows ({@code s:fight():maneuver()}),
 * as its Martial Arts and Combat Schools tab lists it: the resource that names it, and how many copies of it
 * that character may slot against how many it has slotted.
 *
 * <p><b>The intern key is the engine's own object</b> (§2.4's <i>mutates one object in place</i> row). The
 * server's {@code "avail"} message rebuilds the maneuver list, but it carries the existing entry over for a
 * maneuver already known and only mints one for a maneuver just learnt — and the {@code "used"} message then
 * writes the slot count straight onto those same entries. So the object is the identity, and a stashed
 * Maneuver tracks its counts rather than freezing them.
 *
 * <p><b>The account rides beside that key without joining it</b> (077.4). An {@code Action} is a record of
 * one {@link FightWnd}, which stands in exactly one session's tree — so two characters knowing the same
 * maneuver hold two different {@code Action}s and the object alone already tells them apart, which is why
 * the intern map stays one level deep. The handle carries the account anyway, because {@code :exists()} has
 * to re-read the list this entry is in and an {@code Action} does not name its own window.
 *
 * <p><b>The collection has no {@code :get}</b> (D-128): a maneuver is addressed by nothing the reader has —
 * the server's numeric resource id is private to the window — so a string is a search over the resource and
 * the display name, and {@code :find(needle)} is the whole door.
 *
 * <p><b>Threading.</b> The window's {@code uimsg} handlers run on a loader thread under the UI monitor and
 * replace the list wholesale, so the list is copied under that monitor and every resource name is resolved
 * <i>outside</i> it, where a {@code Loading} can be caught and answered as {@code nil}.
 */
public final class LuaManeuver {
    /** The account whose schools tab this entry is in — not part of the key, but the funnel {@code :exists()} re-reads through. */
    public final String user;
    /** The window's own entry for this maneuver — the identity of a handle. */
    public final FightWnd.Action act;

    private LuaManeuver(String user, FightWnd.Action act) {
        this.user = user;
        this.act = act;
    }

    /** {@code tostring(man)}: {@code Maneuver(<resname>)}. */
    public String toString() {
        String r = AddonManager.resIdent(act.res);
        return "Maneuver(" + ((r == null) ? "?" : r) + ")";
    }

    /** An interned Maneuver object for {@code act}, on {@code user}'s schools tab, in {@code owner}'s env. */
    static LuaValue of(Addon owner, String user, FightWnd.Action act) {
        return owner.maneuvers.of(user, act);
    }

    /** The {@code LuaManeuver} behind a Lua value, or {@code null} for anything else. */
    static LuaManeuver resolve(LuaValue v) {
        if((v == null) || !v.isuserdata())
            return null;
        Object o = v.touserdata();
        return (o instanceof LuaManeuver) ? (LuaManeuver)o : null;
    }

    // ---- the per-addon intern cache + metatable ----------------------------------------------------

    /**
     * One addon's Maneuver cache and metatable (its {@link Addon#maneuvers}), keyed by engine identity — the
     * {@code Action}, which belongs to one character's window and so needs no account beside it (077.4).
     *
     * <p><b>Weak on BOTH axes</b> (audit2 B01), for {@link LuaFightSummary.Cache}'s reason exactly and with
     * the same consequence: {@code FightWnd.Action} is a <b>non-static inner</b> class of {@code FightWnd}, so
     * a strongly-keyed entry pinned the whole character window through the Action's own enclosing reference,
     * and the {@code "avail"} message mints a fresh Action for every newly learnt maneuver. {@code Action}
     * overrides neither {@code equals} nor {@code hashCode}, so the weak map keys by identity exactly as the
     * {@code IdentityHashMap} did.
     */
    static final class Cache {
        // retained: weak on both axes -- the value is a WeakReference, so nothing here reaches the Action.
        private final Map<FightWnd.Action, WeakReference<LuaValue>> live =
            new WeakHashMap<FightWnd.Action, WeakReference<LuaValue>>();
        private LuaValue mt;

        Cache(Addon owner) {
        }

        synchronized LuaValue of(String user, FightWnd.Action act) {
            if(act == null)
                return LuaValue.NIL;
            WeakReference<LuaValue> r = live.get(act);
            if(r != null) {
                LuaValue v = r.get();
                if(v != null)
                    return v;
            }
            LuaValue v = LuaValue.userdataOf(new LuaManeuver(user, act), meta());
            live.put(act, new WeakReference<LuaValue>(v));
            return v;
        }

        private LuaValue meta() {
            if(mt == null)
                mt = buildMeta();
            return mt;
        }
    }

    // ---- the Maneuver metatable ---------------------------------------------------------------------

    private static LuaValue buildMeta() {
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, Refusal.closedIndex("maneuver", methods(),
            "a fight maneuver"));
        mt.set("__name", LuaValue.valueOf("Maneuver"));
        mt.set("__tostring", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaManeuver h = resolve(self);
                return LuaValue.valueOf((h == null) ? "Maneuver(?)" : h.toString());
            }
        });
        return mt;
    }

    private static LuaTable methods() {
        LuaTable m = new LuaTable();
        // res() — the maneuver's resource name, its stable identity, or nil while it resolves.
        m.set("res", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                String r = AddonManager.resIdent(handle(self, "res").act.res);
                return (r == null) ? LuaValue.NIL : LuaValue.valueOf(r);
            }
        });
        // name() — the display name off the resource tooltip, or nil until it lands.
        m.set("name", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaManeuver h = handle(self, "name");
                String n = AddonManager.resTipName(h.act.res, null);
                return (n == null) ? LuaValue.NIL : LuaValue.valueOf(n);
            }
        });
        // available() — how many copies of this maneuver you may put in a deck.
        m.set("dealable", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(handle(self, "dealable").act.a);
            }
        });
        // used() — how many you have put in the current deck.
        m.set("used", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(handle(self, "used").act.u);
            }
        });
        // exists() — does that character still know this maneuver?
        m.set("exists", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaManeuver h = handle(self, "exists");
                return LuaValue.valueOf(known(h.user, h.act));
            }
        });
        // info() — the one SNAPSHOT escape hatch.
        m.set("info", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return snapshot(handle(self, "info").act);
            }
        });
        return m;
    }

    private static LuaManeuver handle(LuaValue self, String method) {
        LuaManeuver h = resolve(self);
        if(h == null)
            throw new LuaError("man:" + method + "() — use a COLON call on a Maneuver object"
                + " (" + CharApi.FT + ":maneuver():list()[i])");
        return h;
    }

    // ---- the reads ----------------------------------------------------------------------------------

    /** Every maneuver <b>that character's</b> window lists, copied under the UI monitor (the list is swapped there). */
    static List<FightWnd.Action> actions(String user) {
        List<FightWnd.Action> out = new ArrayList<FightWnd.Action>();
        FightWnd fw = CharApi.fightwnd(user);
        if(fw == null)
            return out;
        synchronized(LuaWidget.monitor(fw)) {
            out.addAll(fw.acts);
        }
        return out;
    }

    /**
     * Is {@code act} still one of {@code user}'s maneuvers? The predicate {@code :exists()} answers — a scan
     * under the monitor, never a copy of the list per call.
     */
    private static boolean known(String user, FightWnd.Action act) {
        FightWnd fw = CharApi.fightwnd(user);
        if(fw == null)
            return false;
        synchronized(LuaWidget.monitor(fw)) {
            for(FightWnd.Action a : fw.acts) {
                if(a == act)
                    return true;
            }
        }
        return false;
    }

    /** The documented {@code Maneuver} snapshot; {@code res}/{@code name} are absent while the res resolves. */
    static LuaValue snapshot(FightWnd.Action act) {
        if(act == null)
            return LuaValue.NIL;
        LuaTable t = new LuaTable();
        String res = AddonManager.resIdent(act.res);
        if(res != null)
            t.set("res", LuaValue.valueOf(res));
        String nm = AddonManager.resTipName(act.res, null);
        if(nm != null)
            t.set("name", LuaValue.valueOf(nm));
        t.set("avail", LuaValue.valueOf(act.a));
        t.set("used", LuaValue.valueOf(act.u));
        return t;
    }

    /** The res-plus-name text a string filter matches, as one string with a separator neither can contain. */
    static String needleOf(FightWnd.Action act) {
        String res = AddonManager.resIdent(act.res), nm = AddonManager.resTipName(act.res, null);
        return ((res == null) ? "" : res) + "\n" + ((nm == null) ? "" : nm);
    }

    // ---- the collection -----------------------------------------------------------------------------

    /**
     * {@code s:fight():maneuver()} — every maneuver and attack <b>that character</b> knows, in its window's
     * own order. Read-only, and <b>keyless</b>: a maneuver's server id is private to the window, so
     * {@code :find(needle)} over the resource and display name is how you address one and {@code :list()[n]}
     * is how you take a position. Empty before that character's schools tab has built.
     */
    static LuaValue collection(final Addon owner, final String user) {
        return LuaCollection.create(CharApi.FT + ":maneuver()", new LuaCollection.Source() {
            public List<LuaValue> members() {
                List<FightWnd.Action> acts = actions(user);
                List<LuaValue> out = new ArrayList<LuaValue>(acts.size());
                for(int i = 0; i < acts.size(); i++)   // names resolved OUTSIDE the lock (res.get() may Loading)
                    out.add(of(owner, user, acts.get(i)));
                return out;
            }

            public String needle(LuaValue member) {
                LuaManeuver h = resolve(member);
                return (h == null) ? "" : needleOf(h.act);
            }

            /** These have a name, so a string filter is a substring test over {@link #needle}. */
            public boolean named() {
                return true;
            }

            public String noGet() {
                return "a maneuver's server id is private to the fight window: " + CharApi.FT
                    + ":maneuver():find(needle) is the search and " + CharApi.FT
                    + ":maneuver():list()[n] takes a position";
            }
        }, null);
    }
}
