package io.brodgar.addon;

import haven.AddonWidgets;
import haven.Buff;
import haven.Bufflist;
import haven.GameUI;
import haven.GItem;
import haven.ItemInfo;
import haven.Resource;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.OneArgFunction;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * A <b>Buff object</b> — one buff on the player's buff bar (spec {@code 025-buffs-oop}), the OOP
 * successor of the flat {@code hafen.buffs.list()}/{@code has()} snapshot reader. Built on exactly the
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
 * exactly the predicate {@code :list()} filters on: a current {@link Bufflist} child that is not fading
 * out after a server removal ({@code Buff.dest}, via {@link AddonWidgets#buffDest}).
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

        private LuaValue meta() {
            if(mt == null)
                mt = buildMeta();
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

    /** The per-addon metatable: {@code __index} = the methods table, plus {@code __tostring}/{@code __name}. */
    private static LuaValue buildMeta() {
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, methods());
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
    private static LuaTable methods() {
        LuaTable m = new LuaTable();
        // res() — the buff's resource name, its stable identity ("paginae/buff/poison"). Mutable: a "ch"
        // uimsg replaces it under a live buff, so this reads through the widget every call.
        m.set("res", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                String r = res(handle(self, "res").wdg);
                return (r == null) ? LuaValue.NIL : LuaValue.valueOf(r);
            }
        });
        // name() — the display name: the resource tooltip, else a server-pushed Name info, else nil.
        m.set("name", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                // LuaBuff.-qualified: inside a LuaFunction, a bare name() would be the function's own.
                String n = LuaBuff.name(handle(self, "name").wdg);
                return (n == null) ? LuaValue.NIL : LuaValue.valueOf(n);
            }
        });
        // amount() — the 0..1 fraction of the buff's own meter frame (Buff.AMeterInfo), nil when the buff
        // publishes none. Content-defined, NOT seconds.
        m.set("amount", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Double a = amount(handle(self, "amount").wdg);
                return (a == null) ? LuaValue.NIL : LuaValue.valueOf(a.doubleValue());
            }
        });
        // duration() — the 0..1 fraction of the radial overlay (GItem.MeterInfo): how much of the buff's
        // run is left, which on a buff is what that meter means (the action bar's identical meter is a
        // cooldown, hence the different name there). Content-defined and nil when the buff publishes none,
        // and NOT seconds: the client has no seconds-based buff timer to read.
        m.set("duration", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Double c = duration(handle(self, "duration").wdg);
                return (c == null) ? LuaValue.NIL : LuaValue.valueOf(c.doubleValue());
            }
        });
        // number() — the integer badge drawn on the icon (GItem.NumberInfo), e.g. a stack count; nil when
        // the buff publishes none.
        m.set("number", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Integer n = number(handle(self, "number").wdg);
                return (n == null) ? LuaValue.NIL : LuaValue.valueOf(n.intValue());
            }
        });
        // exists() — is this buff still ON the bar? False once the server removes it (including while it
        // fades out), and false across a relog. The reads keep working either way, which is what makes a
        // stashed BuffRemoved payload useful.
        m.set("exists", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(active(handle(self, "exists").wdg));
            }
        });
        // info() — the one SNAPSHOT escape hatch (the documented Buff table shape), for logging/serialising.
        m.set("info", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return snapshot(handle(self, "info").wdg);
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
        List<Buff> out = new ArrayList<Buff>();
        Bufflist bl = bufflist(user);
        if(bl != null) {
            for(Buff b : bl.children(Buff.class)) {
                if(!AddonWidgets.buffDest(b))
                    out.add(b);
            }
        }
        return out;
    }

    /** Is {@code b} on the bar right now? The predicate {@link #actives} filters on — {@code :exists()}. */
    static boolean active(Buff b) {
        if((b == null) || AddonWidgets.buffDest(b))
            return false;
        Bufflist bl = barOf(b);
        if(bl == null)
            return false;
        for(Buff c : bl.children(Buff.class)) {
            if(c == b)
                return true;
        }
        return false;
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
            ItemInfo.Name n = ItemInfo.find(ItemInfo.Name.class, b.info());
            return ((n == null) || (n.str == null)) ? null : n.str.text;
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
        return LuaCollection.create(CharApi.B, new LuaCollection.Source() {
            public List<LuaValue> members() {
                List<Buff> active = actives(user);
                List<LuaValue> out = new ArrayList<LuaValue>(active.size());
                for(int i = 0; i < active.size(); i++)
                    out.add(of(owner, active.get(i)));
                return out;
            }

            // res OR name, as one string the substring test runs over once. The separator is a newline, which
            // no resource name and no display name contains, so a match can never span the two halves.
            public String needle(LuaValue member) {
                LuaBuff h = resolve(member);
                Buff b = (h == null) ? null : h.wdg;
                String res = res(b), name = LuaBuff.name(b);    // see the note in methods()
                return ((res == null) ? "" : res) + "\n" + ((name == null) ? "" : name);
            }

            /** These have a name, so a string filter is a substring test over {@link #needle}. */
            public boolean named() {
                return true;
            }
        }, null);
    }
}
