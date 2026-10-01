package io.brodgar.addon;

import haven.AddonWidgets;
import haven.Buff;
import haven.Bufflist;
import haven.Fightsess;
import haven.Fightview;
import haven.GameUI;

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
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * An <b>Opening object</b> — one icon the combat view draws for a fight (171): beside the character, from
 * {@code Fightview.buffs}, or beside an opponent, from that relation's {@code Relation.buffs}. Reached through
 * {@code s:fight():opening()} and {@code opponent:opening()}, and handed by {@code OpeningAdded},
 * {@code OpeningChanged} and {@code OpeningRemoved}.
 *
 * <p><b>Its own type over the bar's widget class.</b> Upstream draws an opening with the same {@link Buff} widget
 * the buff bar uses, in a {@link Bufflist} of its own, and a widget never moves between lists. So the list a
 * widget stands in decides once whether Lua sees it as a {@link LuaBuff} (the bar, {@code GameUI.buffs}) or as
 * this. The two share the widget readers, {@link LuaBuff#res}, {@link LuaBuff#name}, {@link LuaBuff#amount},
 * {@link LuaBuff#duration} and {@link LuaBuff#number}: that sharing is code, never API.
 *
 * <p><b>The intern key is the widget</b>, the {@link LuaBuff} shape and for its reasons: two openings can share a
 * resource and a {@code "ch"} replaces the resource under a live one. An {@link IdentityHashMap} with strong keys
 * and weak values, drained on every access and emptied of a dead widget by {@link Cache#retire}, which
 * {@link Addon#dropInternedHandles} calls.
 *
 * <p><b>Drawn while the combat row stands.</b> {@code Fightsess} is the one widget that paints these lists, and
 * the server takes it down with the fight while the {@code Fightview} keeps the openings of yours not yet
 * expired; and a relation's lists are destroyed on {@code "del"}, which leaves every opening linked under a
 * detached list. {@link #active} is what an opening's {@code :exists()} answers and what every door lists, so
 * neither of those reads as drawn.
 *
 * <p><b>Threading.</b> The combat view's relations and lists are added and removed by the loader thread under
 * the UI monitor, so every walk of them is inside {@link LuaWidget#monitor} and every handle is minted outside.
 */
public final class LuaOpening {
    /** The opening widget this handle addresses — the whole state of a handle. */
    public final Buff wdg;

    private LuaOpening(Buff wdg) {
        this.wdg = wdg;
    }

    /** {@code tostring(opening)} (also the {@code __tostring} answer): {@code Opening(<resname>)}. */
    public String toString() {
        String r = LuaBuff.res(wdg);
        return "Opening(" + ((r == null) ? "?" : r) + ")";
    }

    /** An interned Opening object for {@code b} in {@code owner}'s env — the one way an opening reaches Lua. */
    static LuaValue of(Addon owner, Buff b) {
        return owner.openings.of(b);
    }

    /** The {@code LuaOpening} behind a Lua value, or {@code null} for anything that is not an Opening object. */
    static LuaOpening resolve(LuaValue v) {
        if((v == null) || !v.isuserdata())
            return null;
        Object o = v.touserdata();
        return (o instanceof LuaOpening) ? (LuaOpening)o : null;
    }

    // ---- the per-addon intern cache + metatable ---------------------------------------------------

    /**
     * One addon's Opening interning cache and metatable (its {@link Addon#openings}), the {@link LuaBuff.Cache}
     * shape: strong {@link Buff} keys by identity, weak handle values, a {@link ReferenceQueue} drained on every
     * access, the metatable built once, lazily.
     */
    static final class Cache {
        private final Addon owner;
        // retired: Cache.retire -- the key is STRONG, so a dead widget is pinned until Addon.dropInternedHandles
        //   takes the entry: drain() clears one only where Lua released the handle AND something mints again.
        private final Map<Buff, Ref> live = new IdentityHashMap<Buff, Ref>();
        private final ReferenceQueue<LuaValue> dead = new ReferenceQueue<LuaValue>();
        private LuaValue mt;

        Cache(Addon owner) {
            this.owner = owner;
        }

        /** The interned handle for {@code b} — a cache hit, or a freshly minted (and inserted) one. */
        synchronized LuaValue of(Buff b) {
            drain();
            if(b == null)
                return LuaValue.NIL;
            Ref r = live.get(b);
            if(r != null) {
                LuaValue v = r.get();
                if(v != null)
                    return v;
                live.remove(b);
            }
            LuaValue v = LuaValue.userdataOf(new LuaOpening(b), meta());
            live.put(b, new Ref(v, b, dead));
            return v;
        }

        /** Drop the map entries whose handle Lua has released; mandatory on every access, the keys being strong. */
        private void drain() {
            Reference<? extends LuaValue> r;
            while((r = dead.poll()) != null) {
                Ref or = (Ref)r;
                if(live.get(or.key) == or)      // not already replaced by a fresh handle for the same widget
                    live.remove(or.key);
            }
        }

        /**
         * <b>The widget died, so its entry goes</b> — {@link Addon#dropInternedHandles} is the only caller, on the
         * disposal drain, which runs after the removal drain that fires {@code OpeningRemoved} with this handle.
         * A handle Lua still holds goes on answering.
         */
        synchronized void retire(Buff b) {
            if(b != null)
                live.remove(b);
        }

        private LuaValue meta() {
            if(mt == null)
                mt = buildMeta(owner);
            return mt;
        }
    }

    /** A weak handle reference that remembers its map key, so the {@link ReferenceQueue} drain can unmap it. */
    private static final class Ref extends WeakReference<LuaValue> {
        final Buff key;

        Ref(LuaValue v, Buff key, ReferenceQueue<LuaValue> q) {
            super(v, q);
            this.key = key;
        }
    }

    // ---- the Opening metatable ---------------------------------------------------------------------

    private static LuaValue buildMeta(Addon owner) {
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, Refusal.closedIndex("opening", methods(owner),
            "an opening is one icon the fight draws beside you or an opponent"));
        mt.set("__name", LuaValue.valueOf("Opening"));
        mt.set("__tostring", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaOpening h = resolve(self);
                return LuaValue.valueOf((h == null) ? "Opening(?)" : h.toString());
            }
        });
        return mt;
    }

    /**
     * The method set. Every reader re-reads through the widget and answers {@code nil} when the value is not
     * (yet) published or is still {@code Loading}; {@code :exists()} always answers. Every verb counts its
     * arguments ({@link Args#only}).
     */
    private static LuaTable methods(final Addon owner) {
        LuaTable m = new LuaTable();
        // res() — the resource name, its identity. A "ch" replaces it under a live opening, so read every call.
        m.set("res", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                String r = LuaBuff.res(handle(Args.only(a, 0, "opening:res"), "res").wdg);
                return (r == null) ? LuaValue.NIL : LuaValue.valueOf(r);
            }
        });
        // name() — the display name. LuaBuff.-qualified: inside a LuaFunction a bare name is LibFunction's field.
        m.set("name", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                String n = LuaBuff.name(handle(Args.only(a, 0, "opening:name"), "name").wdg);
                return (n == null) ? LuaValue.NIL : LuaValue.valueOf(n);
            }
        });
        // amount() — the 0..1 fraction of the icon's own meter frame, nil when it publishes none.
        m.set("amount", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Double am = LuaBuff.amount(handle(Args.only(a, 0, "opening:amount"), "amount").wdg);
                return (am == null) ? LuaValue.NIL : LuaValue.valueOf(am.doubleValue());
            }
        });
        // remaining() — the 0..1 fraction of the radial overlay: how much of its run is left, never seconds.
        m.set("remaining", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Double c = LuaBuff.duration(handle(Args.only(a, 0, "opening:remaining"), "remaining").wdg);
                return (c == null) ? LuaValue.NIL : LuaValue.valueOf(c.doubleValue());
            }
        });
        // number() — the integer badge drawn on the icon, nil when it publishes none.
        m.set("number", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Integer n = LuaBuff.number(handle(Args.only(a, 0, "opening:number"), "number").wdg);
                return (n == null) ? LuaValue.NIL : LuaValue.valueOf(n.intValue());
            }
        });
        // widget() — the crossing back into the tree: the widget that HOLDS it, in a list the combat view keeps
        // hidden and paints elsewhere, so it does not mark where the icon is drawn.
        m.set("widget", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaOpening h = handle(Args.only(a, 0, "opening:widget"), "widget");
                return LuaWidget.of(owner, h.wdg);
            }
        });
        // exists() — does the fight still draw it? False once the server removes it (fading included), once its
        // relation has ended, and once the combat row is down. The reads keep answering either way.
        m.set("exists", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                return LuaValue.valueOf(active(handle(Args.only(a, 0, "opening:exists"), "exists").wdg));
            }
        });
        // opponent() — whose it is: the Opponent it is drawn beside; nil for one of yours, and once it is no
        // longer drawn beside anyone. The inverse of opponent:opening().
        m.set("opponent", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                return opponentOf(owner, handle(Args.only(a, 0, "opening:opponent"), "opponent").wdg);
            }
        });
        // info() — the one SNAPSHOT escape hatch, each field named after its read.
        m.set("info", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                return snapshot(handle(Args.only(a, 0, "opening:info"), "info").wdg);
            }
        });
        return m;
    }

    /** The handle behind a method's {@code self}, or a guiding error (a dot-call passes the wrong self). */
    private static LuaOpening handle(LuaValue self, String method) {
        LuaOpening h = resolve(self);
        if(h == null)
            throw new LuaError("opening:" + method + "() — use a COLON call on an Opening object ("
                + CharApi.FT + ":opening():list()[i], opponent:opening():list()[i])");
        return h;
    }

    // ---- the reads ----------------------------------------------------------------------------------

    /**
     * Is {@code b} an opening the client draws right now? The predicate {@code :exists()} answers, every door
     * lists by, and {@code CharApi.BuffsAdapter} announces an opening on. Past the fade:
     * <ul>
     *   <li><b>its list stands under the combat view, in the tree.</b> A relation's lists are destroyed on
     *       {@code "del"}, and {@code Widget.destroy} unlinks the LIST and leaves every widget linked under it;</li>
     *   <li><b>the combat row stands</b> ({@link #rowUp});</li>
     *   <li><b>the list is one the view paints</b> ({@link #drawn}): yours or a relation's {@code buffs}, never its
     *       {@code relbuffs};</li>
     *   <li><b>it is a child of that list.</b></li>
     * </ul>
     */
    static boolean active(Buff b) {
        if((b == null) || AddonWidgets.buffDest(b))
            return false;
        Bufflist bl = LuaBuff.barOf(b);
        if((bl == null) || !(bl.parent instanceof Fightview) || (bl.ui == null) || !bl.hasparent(bl.ui.root))
            return false;
        Fightview fv = (Fightview)bl.parent;
        synchronized(LuaWidget.monitor(fv)) {
            return rowUp(fv) && drawn(fv, bl) && LuaBuff.holds(bl, b);
        }
    }

    /**
     * Does the combat row stand? {@code Fightsess} is the one widget that paints the combat view's lists, and the
     * server takes it down with the fight, while the {@code Fightview} and the openings of yours the server has
     * not yet expired stay in the tree. The row is a direct child of the {@code GameUI} the view stands in. The
     * caller holds the view's monitor.
     */
    private static boolean rowUp(Fightview fv) {
        GameUI g = fv.getparent(GameUI.class);
        return (g != null) && (g.getchild(Fightsess.class) != null);
    }

    /** Is {@code bl} a list the combat view paints, yours or an opponent's? The caller holds the view's monitor. */
    private static boolean drawn(Fightview fv, Bufflist bl) {
        if(bl == fv.buffs)
            return true;
        for(Fightview.Relation rel : fv.lsrel) {
            if(rel.buffs == bl)
                return true;
        }
        return false;
    }

    /** {@code bl}'s live openings, or none while the combat row is down. The caller holds the view's monitor. */
    private static List<Buff> drawnLive(Fightview fv, Bufflist bl) {
        return rowUp(fv) ? LuaBuff.live(bl) : new ArrayList<Buff>();
    }

    /**
     * Every opening the combat view holds in a list it paints, yours and each opponent's, for the adapter to
     * announce when the combat row comes up after them. Each still has to pass {@link #active}.
     */
    static List<Buff> fought(Fightview fv) {
        List<Buff> out = new ArrayList<Buff>();
        synchronized(LuaWidget.monitor(fv)) {
            out.addAll(LuaBuff.live(fv.buffs));
            for(Fightview.Relation rel : fv.lsrel)
                out.addAll(LuaBuff.live(rel.buffs));
        }
        return out;
    }

    /**
     * {@code opening:opponent()} — the opponent beside whom the combat view draws {@code b}, as the interned
     * Opponent of the character whose fight it is; {@code NIL} for one of yours and for one the fight no longer
     * draws ({@link #active}: fading out, its relation ended, or the row down). The relation is found under the
     * view's monitor and the handle minted outside it.
     */
    static LuaValue opponentOf(Addon owner, Buff b) {
        if(!active(b))
            return LuaValue.NIL;
        Place p = placeOf(b);
        return ((p == null) || (p.opponent == Place.YOURS)) ? LuaValue.NIL
            : LuaOpponent.of(owner, AddonManager.userOf(b), p.opponent);
    }

    /**
     * Where the combat view draws an opening: beside you, or beside one opponent. What the buff adapter records
     * when it announces an opening, because an opponent's list is destroyed with its relation and by the time
     * {@code OpeningRemoved} fires the opening no longer says whose it was.
     */
    static final class Place {
        /** {@link #opponent} for one of yours. */
        static final long YOURS = -1;
        /** The gob id of the opponent it is drawn beside, or {@link #YOURS}. */
        final long opponent;

        Place(long opponent) {
            this.opponent = opponent;
        }
    }

    /**
     * The {@link Place} of {@code b}, or {@code null} when it has none — not under the combat view, or in a list
     * whose relation has just ended. The relation is found under the view's monitor, in one look, so an opening is
     * never taken for one of yours because its relation went between two reads.
     */
    static Place placeOf(Buff b) {
        Bufflist bl = LuaBuff.barOf(b);
        if((bl == null) || !(bl.parent instanceof Fightview))
            return null;
        Fightview fv = (Fightview)bl.parent;
        synchronized(LuaWidget.monitor(fv)) {
            if(bl == fv.buffs)
                return new Place(Place.YOURS);
            for(Fightview.Relation rel : fv.lsrel) {
                if((rel.buffs == bl) && !rel.invalid)
                    return new Place(rel.gobid);
            }
        }
        return null;
    }

    /**
     * The {@code Opening} snapshot, {@code opening:info()}: {@code res}, {@code name}, {@code amount},
     * {@code remaining} and {@code number}, each named after its read and absent where the read is {@code nil}.
     */
    static LuaValue snapshot(Buff b) {
        if(b == null)
            return LuaValue.NIL;
        LuaTable t = new LuaTable();
        String res = LuaBuff.res(b);
        if(res != null)
            t.set("res", LuaValue.valueOf(res));
        String nm = LuaBuff.name(b);
        if(nm != null)
            t.set("name", LuaValue.valueOf(nm));
        Double am = LuaBuff.amount(b);
        if(am != null)
            t.set("amount", LuaValue.valueOf(am.doubleValue()));
        Double rem = LuaBuff.duration(b);
        if(rem != null)
            t.set("remaining", LuaValue.valueOf(rem.doubleValue()));
        Integer num = LuaBuff.number(b);
        if(num != null)
            t.set("number", LuaValue.valueOf(num.intValue()));
        return t;
    }

    // ---- the doors ----------------------------------------------------------------------------------

    /**
     * {@code s:fight():opening()} — yours, the list the combat view paints beside that character
     * ({@code Fightview.buffs}). Minted once per (addon, session) by {@code CharApi.fight}. Empty out of a fight
     * and before the HUD is up.
     */
    static LuaValue fightCollection(final Addon owner, final String user) {
        return LuaCollection.create(CharApi.FT + ":opening()", new Door(owner, noKey(CharApi.FT + ":opening()")) {
            List<Buff> openings() {
                Fightview fv = LuaOpponent.view(user);
                if(fv == null)
                    return new ArrayList<Buff>();
                synchronized(LuaWidget.monitor(fv)) {
                    return drawnLive(fv, fv.buffs);
                }
            }
        }, null);
    }

    /**
     * {@code opponent:opening()} — that opponent's openings, the list the combat view paints beside them
     * ({@code Relation.buffs}; the relation's {@code relbuffs} are painted by nothing and are not here). A view
     * minted per call, empty once the fight with them has ended.
     */
    static LuaValue opponentCollection(final Addon owner, final String user, final long gobid) {
        return LuaCollection.create("opponent:opening()", new Door(owner, noKey("opponent:opening()")) {
            List<Buff> openings() {
                Fightview fv = LuaOpponent.view(user);
                if(fv != null) {
                    synchronized(LuaWidget.monitor(fv)) {
                        for(Fightview.Relation rel : fv.lsrel) {
                            if((rel.gobid == gobid) && !rel.invalid)
                                return drawnLive(fv, rel.buffs);
                        }
                    }
                }
                return new ArrayList<Buff>();
            }
        }, null);
    }

    /**
     * One opening door: the live openings of one list, in the order drawn. There is no {@code :get}: two openings
     * can share a resource and a {@code "ch"} replaces the resource under a live one, so a needle is a
     * <i>search</i>, never an address. A string filter matches the res <b>or</b> the display name. Each door
     * passes its own spelling to {@link LuaCollection#create} as a constant, which is what lets
     * {@code tools/refusalverbs.py} see it.
     */
    private abstract static class Door extends LuaCollection.Source {
        private final Addon owner;
        private final String noKey;

        Door(Addon owner, String noKey) {
            this.owner = owner;
            this.noKey = noKey;
        }

        /** The openings this door lists, in the order drawn. */
        abstract List<Buff> openings();

        public List<LuaValue> members() {
            List<Buff> active = openings();
            List<LuaValue> out = new ArrayList<LuaValue>(active.size());
            for(int i = 0; i < active.size(); i++)
                out.add(of(owner, active.get(i)));
            return out;
        }

        // res OR name, as one string the substring test runs over once. The separator is a newline, which no
        // resource name and no display name contains, so a match can never span the two halves.
        public String needle(LuaValue member) {
            LuaOpening h = resolve(member);
            Buff b = (h == null) ? null : h.wdg;
            String res = LuaBuff.res(b), nm = LuaBuff.name(b);
            return ((res == null) ? "" : res) + "\n" + ((nm == null) ? "" : nm);
        }

        /** These have a name, so a string filter is a substring test over {@link #needle}. */
        public boolean named() {
            return true;
        }

        public String noGet() {
            return noKey;
        }
    }

    /** The sentence an opening door's missing {@code :get} carries, spelled with the door's own name. */
    private static String noKey(String door) {
        return "an opening has no key, since two can share a resource and the server can replace one under a"
            + " live opening: " + door + ":find(needle) is the search and " + door + ":list()[n] takes a position";
    }
}
