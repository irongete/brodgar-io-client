package io.brodgar.addon;

import haven.AddonWidgets;
import haven.Buff;
import haven.Bufflist;
import haven.Fightsess;
import haven.Fightview;
import haven.GameUI;
import haven.GItem;
import haven.ItemInfo;
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
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * A <b>Buff object</b> — one buff the client draws for a character (spec {@code 025-buffs-oop}): on its buff
 * bar, and (170.1) in a fight, where the combat view paints the buffs beside the character and beside each
 * opponent from lists of its own. The OOP successor of the flat {@code hafen.buffs.list()}/{@code has()}
 * snapshot reader. Built on exactly the
 * mechanism {@link LuaGob} (017), {@link LuaKin} (020), {@link LuaSlot} (021), {@link LuaPagina} (023) and
 * {@link LuaSound} (024) established; <b>the section object IS the buff bar</b> (uniform grammar §2.1):
 * {@code s:buff()} is the collection of the active buffs and {@code s:buff():find(needle)} is one of
 * them.
 *
 * <p><b>The handle wraps the {@link Buff} widget and nothing else.</b> Every read goes through it live, so a
 * stashed Buff tracks its own meters as the server pushes {@code "ch"}/{@code "tt"} updates —
 * interned userdata is an <i>identity</i>, not a record. {@code :info()} is the one snapshot escape hatch and
 * keeps the documented {@code Buff} table shape.
 *
 * <p><b>The intern key is the widget object itself</b> (identity), which is the first cache in the series
 * keyed by an object rather than a name/index/id. It has to be: two buffs can share a resource, and a
 * {@code "ch"} uimsg <i>replaces</i> {@code Buff.res} under a live buff, so the res name is neither unique
 * nor stable. Hence an {@link IdentityHashMap} with <b>weak values</b> + a {@link ReferenceQueue} drained on
 * every access — never a {@code WeakHashMap}, which is weak on the wrong axis. The strong keys are
 * deliberate and bounded: an entry outlives its buff only until the next access drains it, and a {@link Buff}
 * the addon still holds a handle to is <i>meant</i> to stay readable — that is what makes a
 * {@code BuffRemoved} payload worth having.
 *
 * <p><b>Removed but still readable.</b> {@code Widget.destroy()} unlinks a buff; it does not null its
 * {@code res} or its {@code info}. So a Buff whose widget is gone keeps answering {@code :res()}/{@code
 * :name()}/… and reports {@code :exists()} <b>false</b> — the staleness question {@link LuaSound}
 * deliberately has no answer for (D-060), and which a buff, having a lifetime, does. {@code :exists()} is
 * exactly the predicate every door's {@code :list()} filters on: a child of a list the client draws, the list
 * standing in the tree, and the buff not fading out after a server removal ({@code Buff.dest}, via
 * {@link AddonWidgets#buffDest}) — see {@link #active}.
 *
 * <p><b>Every read is {@code Loading}-guarded and may answer {@code nil}.</b> {@code Buff.info()} throws
 * {@code Loading} and is empty until the first {@code "tt"} lands, so a brand-new buff is routinely
 * res-only for a beat. That is normal, never an error into Lua.
 *
 * <p><b>Userdata + per-addon interning</b> (D-017 / D-045), identical to the prior sections: the handle
 * crosses as {@code LuaValue.userdataOf(luaBuff, mt)} so Lua cannot scribble on it, and the {@link Cache}
 * lives on the owning {@link Addon}, never statically — no Lua value crosses a sandbox boundary and the
 * cache dies whole with the {@link Addon} on {@code :reload}.
 *
 * <p><b>No verb.</b> {@code Buff.mousedown} does send a {@code wdgmsg("cl", …)}, but no buff is known to do
 * anything with it, so there is no verifiable behaviour to gate and ship (spec 025, out of scope).
 */
public final class LuaBuff {
    /** The buff widget this handle addresses — the whole state of a handle. */
    public final Buff wdg;

    private LuaBuff(Buff wdg) {
        this.wdg = wdg;
    }

    /** {@code tostring(buff)} (also the {@code __tostring} answer): {@code Buff(<resname>)}. */
    public String toString() {
        String r = res(wdg);
        return "Buff(" + ((r == null) ? "?" : r) + ")";
    }

    /** An interned Buff object for {@code b} in {@code owner}'s env — the one way a Buff reaches Lua. */
    static LuaValue of(Addon owner, Buff b) {
        return owner.buffs.of(b);
    }

    /** The {@code LuaBuff} behind a Lua value, or {@code null} for anything that is not a Buff object. */
    static LuaBuff resolve(LuaValue v) {
        if((v == null) || !v.isuserdata())
            return null;
        Object o = v.touserdata();
        return (o instanceof LuaBuff) ? (LuaBuff)o : null;
    }

    // ---- the per-addon intern cache + metatable ---------------------------------------------------

    /**
     * One addon's Buff interning cache and metatable (its {@link Addon#buffs}). Weak values + a
     * {@link ReferenceQueue} drained on every access; the metatable is built once, lazily. Keyed by the
     * {@link Buff} widget's <b>identity</b> — see the class comment for why neither the res name nor a
     * widget id would do.
     */
    static final class Cache {
        private final Addon owner;
        // retired: Cache.retire -- the key is STRONG, so a dead Buff is pinned until Addon.dropInternedHandles
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
            LuaValue v = LuaValue.userdataOf(new LuaBuff(b), meta());
            live.put(b, new Ref(v, b, dead));
            return v;
        }

        /**
         * Drop the map entries whose handle Lua has released. Mandatory on every access: the keys are
         * STRONG, so skipping it would pin every destroyed {@link Buff} widget for the session.
         */
        private void drain() {
            Reference<? extends LuaValue> r;
            while((r = dead.poll()) != null) {
                Ref br = (Ref)r;
                if(live.get(br.key) == br)      // not already replaced by a fresh handle for the same widget
                    live.remove(br.key);
            }
        }

        /**
         * <b>The widget died, so its entry goes</b> (128.5) — the buff widget is gone. The key is
         * STRONG, and {@link #drain} clears an entry only where Lua has already released the handle <i>and</i>
         * something mints again, so without this the map pins the widget for the session; see
         * {@link Addon#dropInternedHandles}, which is the only caller.
         *
         * <p>{@code synchronized}, which is the monitor {@link #of} takes — the drain runs on the step and a
         * mint runs wherever Lua ran. A handle Lua is still holding goes on answering: what is dropped is the
         * cache's claim on a widget that no longer exists, not the object an author stashed.
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

    // ---- the Buff metatable ------------------------------------------------------------------------

    /**
     * The per-addon metatable: {@code __index} = the methods table through {@link Refusal#closedIndex} (so
     * an unknown verb throws naming what this type does answer), plus {@code __tostring}/{@code __name}.
     */
    private static LuaValue buildMeta(Addon owner) {
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, Refusal.closedIndex("buff", methods(owner),
            "a buff is one icon on the buff bar"));
        mt.set("__name", LuaValue.valueOf("Buff"));
        mt.set("__tostring", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaBuff h = resolve(self);
                return LuaValue.valueOf((h == null) ? "Buff(?)" : h.toString());
            }
        });
        return mt;
    }

    /**
     * The method set. Every reader re-reads through the widget and answers {@code nil} when the value is not
     * (yet) published or is still {@code Loading}; {@code :exists()} always answers.
     */
    private static LuaTable methods(final Addon owner) {
        LuaTable m = new LuaTable();
        // 170.1: every verb counts its arguments (Args.only). A OneArgFunction dropped a surplus one unseen,
        // where conventions.md promises it raises.
        // res() — the buff's resource name, its stable identity ("paginae/buff/poison"). Mutable: a "ch"
        // uimsg replaces it under a live buff, so this reads through the widget every call.
        m.set("res", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                String r = res(handle(Args.only(a, 0, "buff:res"), "res").wdg);
                return (r == null) ? LuaValue.NIL : LuaValue.valueOf(r);
            }
        });
        // name() — the display name: the resource tooltip, else a server-pushed Name info, else nil.
        m.set("name", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                // LuaBuff.-qualified: inside a LuaFunction, a bare name() would be the function's own.
                String n = LuaBuff.name(handle(Args.only(a, 0, "buff:name"), "name").wdg);
                return (n == null) ? LuaValue.NIL : LuaValue.valueOf(n);
            }
        });
        // amount() — the 0..1 fraction of the buff's own meter frame (Buff.AMeterInfo), nil when the buff
        // publishes none. Content-defined, NOT seconds.
        m.set("amount", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Double am = amount(handle(Args.only(a, 0, "buff:amount"), "amount").wdg);
                return (am == null) ? LuaValue.NIL : LuaValue.valueOf(am.doubleValue());
            }
        });
        // remaining() — the 0..1 fraction of the radial overlay (GItem.MeterInfo): how much of the buff's run
        // is left. Content-defined and nil when the buff publishes none, and NOT seconds: the client has no
        // seconds-based buff timer to read.
        m.set("remaining", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Double c = duration(handle(Args.only(a, 0, "buff:remaining"), "remaining").wdg);
                return (c == null) ? LuaValue.NIL : LuaValue.valueOf(c.doubleValue());
            }
        });
        // number() — the integer badge drawn on the icon (GItem.NumberInfo), e.g. a stack count; nil when
        // the buff publishes none.
        m.set("number", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Integer n = number(handle(Args.only(a, 0, "buff:number"), "number").wdg);
                return (n == null) ? LuaValue.NIL : LuaValue.valueOf(n.intValue());
            }
        });
        // widget() — 094 (A-104): THE CROSSING BACK. The widget tree and the domain objects are two address
        // spaces, and a badge over the buff about to expire needs the buff's widget. The bridge was already
        // holding it -- it IS the handle -- so the crossing is one closure. A buff of a fight answers the
        // widget that HOLDS it, in a list the combat view hides and paints elsewhere.
        m.set("widget", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaBuff h = handle(Args.only(a, 0, "buff:widget"), "widget");
                return LuaWidget.of(owner, h.wdg);
            }
        });
        // exists() — is this buff still on the list it was drawn from? False once the server removes it
        // (including while it fades out), once its list leaves the tree (a fight relation ending), and across
        // a relog. The reads keep working either way, which is what makes a stashed BuffRemoved payload useful.
        m.set("exists", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                return LuaValue.valueOf(active(handle(Args.only(a, 0, "buff:exists"), "exists").wdg));
            }
        });
        // opponent() — 170.1: whose it is. The Opponent beside whom the combat view draws it; nil for one of
        // your own in a fight and for a buff on the bar. The inverse of opponent:opening().
        m.set("opponent", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                return opponentOf(owner, handle(Args.only(a, 0, "buff:opponent"), "opponent").wdg);
            }
        });
        // info() — the one SNAPSHOT escape hatch (the documented Buff table shape), for logging/serialising.
        m.set("info", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                return snapshot(handle(Args.only(a, 0, "buff:info"), "info").wdg);
            }
        });
        return m;
    }

    /** The handle behind a method's {@code self}, or a guiding error (a dot-call passes the wrong self). */
    private static LuaBuff handle(LuaValue self, String method) {
        LuaBuff h = resolve(self);
        if(h == null)
            throw new LuaError("buff:" + method + "() — use a COLON call on a Buff object (s:buff():find(needle),"
                + " s:buff():list()[i])");
        return h;
    }

    // ---- the reads (all Loading-guarded) -----------------------------------------------------------

    /** That character's buff bar ({@link GameUI#buffs}), or {@code null} before its HUD is up. */
    private static Bufflist bufflist(String user) {
        GameUI g = AddonManager.gameui(user);
        return (g == null) ? null : g.buffs;
    }

    /**
     * The bar <b>a buff is standing on</b>, walked up from the widget itself rather than named by an account:
     * a Buff handle wraps the icon, and the icon already knows whose HUD it hangs in, so {@code :exists()}
     * answers about that character's bar however many sessions are live.
     */
    private static Bufflist barOf(Buff b) {
        return (b == null) ? null : b.getparent(Bufflist.class);
    }

    /**
     * The buffs currently ON that character's bar, in {@link Bufflist} child order (which is the order they
     * are drawn): one pass over the live children, minus any fading out after a server removal. The single
     * scan both {@code :list()} and the {@code BuffsAdapter} share.
     */
    static List<Buff> actives(String user) {
        Bufflist bl = bufflist(user);
        return (bl == null) ? new ArrayList<Buff>() : live(bl);
    }

    /**
     * Is {@code b} a live buff the client draws? The predicate every door's {@code :list()} filters on, the one
     * {@code :exists()} answers and the one {@code CharApi.BuffsAdapter} announces on. Past the fade, four
     * conditions (170.1):
     * <ul>
     *   <li><b>its list stands in the tree.</b> A fight relation's lists are destroyed on {@code "del"}, and
     *       {@code Widget.destroy} unlinks the LIST and leaves every buff linked under it, so a membership scan
     *       alone went on answering true for a relation that had ended;</li>
     *   <li><b>the list is one the client draws</b>: the bar, your own list in a fight, an opponent's list, and
     *       never a relation's {@code relbuffs}, which nothing paints and no door lists;</li>
     *   <li><b>under the combat view, the combat row stands</b> ({@link #rowUp}): the row paints those lists,
     *       and the view outlives the fight, keeping your buffs the server has not yet expired;</li>
     *   <li><b>it is a child of that list.</b></li>
     * </ul>
     * A list under the combat view is read under that view's monitor, where the loader thread adds and removes
     * relations. The bar's is read lock-free, as it always was.
     */
    static boolean active(Buff b) {
        if((b == null) || AddonWidgets.buffDest(b))
            return false;
        Bufflist bl = barOf(b);
        if((bl == null) || (bl.ui == null) || !bl.hasparent(bl.ui.root))
            return false;
        if(bl.parent instanceof Fightview) {
            Fightview fv = (Fightview)bl.parent;
            synchronized(LuaWidget.monitor(fv)) {
                return rowUp(fv) && drawn(fv, bl) && holds(bl, b);
            }
        }
        return holds(bl, b);
    }

    /**
     * Does the combat row stand? {@code Fightsess} is the one widget that paints the combat view's buff lists,
     * and the server takes it down with the fight, while the {@code Fightview} and the buffs of yours the
     * server has not yet expired stay in the tree. Unpainted, they are not what the client draws. The row is a
     * direct child of the {@code GameUI} the view stands in. The caller holds the view's monitor.
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

    /** Is {@code b} one of {@code bl}'s own buffs? */
    private static boolean holds(Bufflist bl, Buff b) {
        for(Buff c : bl.children(Buff.class)) {
            if(c == b)
                return true;
        }
        return false;
    }

    /** {@code bl}'s buffs in child order, which is the order drawn, minus any fading out after a server removal. */
    private static List<Buff> live(Bufflist bl) {
        List<Buff> out = new ArrayList<Buff>();
        for(Buff b : bl.children(Buff.class)) {
            if(!AddonWidgets.buffDest(b))
                out.add(b);
        }
        return out;
    }

    /** {@code bl}'s live buffs of a list under the combat view, or none while the combat row is down. The caller holds the view's monitor. */
    private static List<Buff> drawnLive(Fightview fv, Bufflist bl) {
        return rowUp(fv) ? live(bl) : new ArrayList<Buff>();
    }

    /**
     * 170.1: every buff the combat view holds in a list it paints, yours and each opponent's, for the adapter to
     * announce when the combat row comes up after them. Each still has to pass {@link #active}.
     */
    static List<Buff> fought(Fightview fv) {
        List<Buff> out = new ArrayList<Buff>();
        synchronized(LuaWidget.monitor(fv)) {
            out.addAll(live(fv.buffs));
            for(Fightview.Relation rel : fv.lsrel)
                out.addAll(live(rel.buffs));
        }
        return out;
    }

    /**
     * 170.1: {@code buff:opponent()} — the opponent beside whom the combat view draws {@code b}, as the interned
     * Opponent of the character whose fight it is; {@code NIL} for a buff on the bar, for one of your own in a
     * fight, and for one whose relation has ended. The relation is found under the view's monitor and the
     * handle minted outside it.
     */
    static LuaValue opponentOf(Addon owner, Buff b) {
        Place p = placeOf(b);
        return ((p == null) || (p.opponent < 0)) ? LuaValue.NIL : LuaOpponent.of(owner, AddonManager.userOf(b), p.opponent);
    }

    /**
     * 170.1: where the client draws a buff — the bar, your own row in a fight, or beside one opponent. What the
     * buff adapter records when it announces a buff, because an opponent's list is destroyed with its relation
     * and by the time {@code OpeningRemoved} fires the buff no longer says whose it was.
     */
    static final class Place {
        /** {@link #opponent} for a buff on the bar and for one of yours in a fight. */
        static final long NONE = -1;
        /** Drawn by the combat view, so announced as an opening. */
        final boolean fight;
        /** The gob id of the opponent it is drawn beside, or {@link #NONE}. */
        final long opponent;

        Place(boolean fight, long opponent) {
            this.fight = fight;
            this.opponent = opponent;
        }
    }

    /**
     * 170.1: the {@link Place} of {@code b}, or {@code null} when it has none — no list, or a list whose
     * relation has just ended. The relation is found under the view's monitor, in one look, so a buff is never
     * taken for one of yours because its relation went between two reads.
     */
    static Place placeOf(Buff b) {
        Bufflist bl = barOf(b);
        if(bl == null)
            return null;
        if(!(bl.parent instanceof Fightview))
            return new Place(false, Place.NONE);
        Fightview fv = (Fightview)bl.parent;
        synchronized(LuaWidget.monitor(fv)) {
            if(bl == fv.buffs)
                return new Place(true, Place.NONE);
            for(Fightview.Relation rel : fv.lsrel) {
                if((rel.buffs == bl) && !rel.invalid)
                    return new Place(true, rel.gobid);
            }
        }
        return null;
    }

    /** Resource name (stable identity) of a buff, or {@code null} (Loading-guarded). */
    static String res(Buff b) {
        if(b == null)
            return null;
        try {
            Resource r = b.res.get();
            return (r == null) ? null : r.name;
        } catch(RuntimeException e) {   // Loading etc.
            return null;
        }
    }

    /** Display name of a buff: the resource tooltip, else a server-pushed Name info, else {@code null}. */
    static String name(Buff b) {
        if(b == null)
            return null;
        try {
            Resource r = b.res.get();
            if(r != null) {
                Resource.Tooltip tt = r.layer(Resource.tooltip);
                if((tt != null) && (tt.t != null))
                    return tt.t;
            }
        } catch(RuntimeException e) {   // Loading etc.
        }
        try {
            // addon: (102.6) the name row's SOURCE, not the raster it drew -- CharApi.nameStr is the one read.
            return CharApi.nameStr(ItemInfo.find(ItemInfo.Name.class, b.info()));
        } catch(RuntimeException e) {   // info() still Loading / no rawinfo yet
            return null;
        }
    }

    /** The buff's own meter fraction (0..1, {@link Buff.AMeterInfo}), or {@code null} if it publishes none. */
    static Double amount(Buff b) {
        Buff.AMeterInfo am = info(b, Buff.AMeterInfo.class);
        return (am == null) ? null : Double.valueOf(am.ameter());
    }

    /** The radial meter fraction (0..1, {@link GItem.MeterInfo}) — the buff's remaining run, never seconds. */
    static Double duration(Buff b) {
        GItem.MeterInfo mi = info(b, GItem.MeterInfo.class);
        return (mi == null) ? null : Double.valueOf(mi.meter());
    }

    /** The integer badge on the icon ({@link GItem.NumberInfo}), or {@code null} if it publishes none. */
    static Integer number(Buff b) {
        GItem.NumberInfo ni = info(b, GItem.NumberInfo.class);
        return (ni == null) ? null : Integer.valueOf(ni.itemnum());
    }

    /**
     * One resource-published {@link ItemInfo} of a buff, or {@code null}. {@code Buff.info()} throws
     * {@code Loading} until the resource resolves and is empty until the first {@code "tt"} lands, so
     * "absent" and "not here yet" are the same answer — deliberately, since the next update fills it in.
     */
    private static <T> T info(Buff b, Class<T> cl) {
        if(b == null)
            return null;
        try {
            return ItemInfo.find(cl, b.info());
        } catch(RuntimeException e) {   // info() still Loading
            return null;
        }
    }

    /**
     * A Buff snapshot — the documented {@code Buff} table shape, {@code buff:info()} and (until 025.2) the
     * {@code Buff*} event payload: {@code res}/{@code name} (stable), plus {@code amount}/{@code duration}/
     * {@code number}, which come from resource-published {@link ItemInfo} and are 0..1 fractions / an integer,
     * content-dependent and often absent. Expressed over the same accessors the methods use, so there is one
     * source of truth per field; an absent value is simply an unset key, as before.
     */
    static LuaValue snapshot(Buff b) {
        if(b == null)
            return LuaValue.NIL;
        LuaTable t = new LuaTable();
        String res = res(b);
        if(res != null)
            t.set("res", LuaValue.valueOf(res));
        String name = name(b);
        if(name != null)
            t.set("name", LuaValue.valueOf(name));
        Double am = amount(b);
        if(am != null)
            t.set("amount", LuaValue.valueOf(am.doubleValue()));
        Double cd = duration(b);
        if(cd != null)
            t.set("duration", LuaValue.valueOf(cd.doubleValue()));
        Integer num = number(b);
        if(num != null)
            t.set("number", LuaValue.valueOf(num.intValue()));
        return t;
    }

    // ---- the collection ----------------------------------------------------------------------------

    /**
     * {@code s:buff()} — the active buffs, as the {@link LuaCollection} the section object IS:
     * {@code :list(filter)} is a fresh 1-based array of (interned) Buff objects in bar order,
     * {@code :find(needle)} the first that matches, {@code :count(filter)} how many.
     *
     * <p><b>There is no {@code :get}</b>, and that is the point of the shape: a buff has no key. Two buffs can
     * share a resource and a {@code "ch"} uimsg replaces {@code Buff.res} under a live one, so a needle is a
     * <i>search</i>, never an address — {@code coll:get} would promise an identity the subsystem does not
     * have. A string filter matches the res <b>or</b> the display name, which is what the old lookup did.
     */
    static LuaValue collection(final Addon owner, final String user) {
        return LuaCollection.create(CharApi.B, new Door(owner, noKey(CharApi.B)) {
            List<Buff> buffs() {
                return actives(user);
            }
        }, null);
    }

    /**
     * 170.1: {@code s:fight():opening()} — yours in the fight, the list the combat view paints beside that character
     * ({@code Fightview.buffs}). Minted once per (addon, session) by {@code CharApi.fight}. Empty out of a fight
     * and before the HUD is up.
     */
    static LuaValue fightCollection(final Addon owner, final String user) {
        return LuaCollection.create(CharApi.FT + ":opening()", new Door(owner, noKey(CharApi.FT + ":opening()")) {
            List<Buff> buffs() {
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
     * 170.1: {@code opponent:opening()} — that opponent's openings, the list the combat view paints beside them
     * ({@code Relation.buffs}; the relation's {@code relbuffs} are painted by nothing and are not here). A view
     * minted per call, empty once the fight with them has ended.
     */
    static LuaValue opponentCollection(final Addon owner, final String user, final long gobid) {
        return LuaCollection.create("opponent:opening()", new Door(owner, noKey("opponent:opening()")) {
            List<Buff> buffs() {
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
     * One buff door: the live buffs of one list, in the order drawn. There is no {@code :get}, and that is the
     * point of the shape: a buff has no key. Two buffs can share a resource and a {@code "ch"} uimsg replaces
     * {@code Buff.res} under a live one, so a needle is a <i>search</i>, never an address. A string filter
     * matches the res <b>or</b> the display name. Each door passes its own spelling to
     * {@link LuaCollection#create} as a constant, which is what lets {@code tools/refusalverbs.py} see it.
     */
    private abstract static class Door extends LuaCollection.Source {
        private final Addon owner;
        private final String noKey;

        Door(Addon owner, String noKey) {
            this.owner = owner;
            this.noKey = noKey;
        }

        /** The buffs this door lists, in the order drawn. */
        abstract List<Buff> buffs();

        public List<LuaValue> members() {
            List<Buff> active = buffs();
            List<LuaValue> out = new ArrayList<LuaValue>(active.size());
            for(int i = 0; i < active.size(); i++)
                out.add(of(owner, active.get(i)));
            return out;
        }

        // res OR name, as one string the substring test runs over once. The separator is a newline, which no
        // resource name and no display name contains, so a match can never span the two halves.
        public String needle(LuaValue member) {
            LuaBuff h = resolve(member);
            Buff b = (h == null) ? null : h.wdg;
            String res = res(b), nm = LuaBuff.name(b);    // see the note in methods()
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

    /** The sentence a buff door's missing {@code :get} carries, spelled with the door's own name. */
    private static String noKey(String door) {
        return "a buff has no key, since two can share a resource and the server can replace one under a live"
            + " buff: " + door + ":find(needle) is the search and " + door + ":list()[n] takes a position";
    }
}
