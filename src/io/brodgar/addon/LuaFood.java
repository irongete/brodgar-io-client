package io.brodgar.addon;

import haven.BAttrWnd;
import haven.CharWnd;
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
import java.util.Map;

/**
 * A <b>Food object</b> — the character's food-event points and hunger ({@code s:char():food()}), read
 * off the character sheet's base-attributes tab. It is <b>the one place in the client with absolute numbers
 * about the character</b>: everywhere else a bar is a fraction.
 *
 * <p><b>The intern key is the widget's identity</b> (§2.4: the engine exposes only a widget). There is one
 * food state per character and no id for it, and the tab is rebuilt on a relog, so the widget is both the
 * identity and the lifetime — the same choice {@link LuaBuff} and {@link LuaMeter} make.
 *
 * <p><b>Every read is a live re-read and may answer {@code nil}</b> while the numbers stream in, which they
 * do for a beat after entering the world and again after every meal. {@code s:char():food()} itself is
 * {@code nil} until the tab exists at all.
 */
public final class LuaFood {
    /** The base-attributes widget this handle addresses — the whole state of a handle. */
    public final BAttrWnd wdg;

    private LuaFood(BAttrWnd wdg) {
        this.wdg = wdg;
    }

    /** {@code tostring(food)}: {@code Food}. */
    public String toString() {
        return "Food";
    }

    /** An interned Food object for {@code w} in {@code owner}'s env. */
    static LuaValue of(Addon owner, BAttrWnd w) {
        return owner.foods.of(w);
    }

    /** The {@code LuaFood} behind a Lua value, or {@code null} for anything that is not a Food object. */
    static LuaFood resolve(LuaValue v) {
        if((v == null) || !v.isuserdata())
            return null;
        Object o = v.touserdata();
        return (o instanceof LuaFood) ? (LuaFood)o : null;
    }

    // ---- the per-addon intern cache + metatable ---------------------------------------------------

    /** One addon's Food cache and metatable (its {@link Addon#foods}), keyed by widget identity. */
    static final class Cache {
        private final Map<BAttrWnd, Ref> live = new IdentityHashMap<BAttrWnd, Ref>();
        private final ReferenceQueue<LuaValue> dead = new ReferenceQueue<LuaValue>();
        private LuaValue mt;

        private final Addon owner;

        Cache(Addon owner) {
            this.owner = owner;
        }

        synchronized LuaValue of(BAttrWnd w) {
            drain();
            if(w == null)
                return LuaValue.NIL;
            Ref r = live.get(w);
            if(r != null) {
                LuaValue v = r.get();
                if(v != null)
                    return v;
                live.remove(w);
            }
            LuaValue v = LuaValue.userdataOf(new LuaFood(w), meta());
            live.put(w, new Ref(v, w, dead));
            return v;
        }

        private void drain() {
            Reference<? extends LuaValue> r;
            while((r = dead.poll()) != null) {
                Ref br = (Ref)r;
                if(live.get(br.key) == br)
                    live.remove(br.key);
            }
        }

        private LuaValue meta() {
            if(mt == null)
                mt = buildMeta(owner);
            return mt;
        }
    }

    private static final class Ref extends WeakReference<LuaValue> {
        final BAttrWnd key;

        Ref(LuaValue v, BAttrWnd key, ReferenceQueue<LuaValue> q) {
            super(v, q);
            this.key = key;
        }
    }

    // ---- the Food metatable -------------------------------------------------------------------------

    private static LuaValue buildMeta(final Addon owner) {
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, Refusal.closedIndex("food", methods(owner),
            "the food meter"));
        mt.set("__name", LuaValue.valueOf("Food"));
        mt.set("__tostring", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf("Food");
            }
        });
        return mt;
    }

    private static LuaTable methods(final Addon owner) {
        LuaTable m = new LuaTable();
        // cap() — how much the FEP bar holds before it stops accepting more.
        // fep() — the FEP bar as an OBJECT (091, A-077): :cap(), :total() and :entry(), which is the
        // same nesting food:info().fep has always had. It was three flat verbs and an array of anonymous
        // tables, so the live shape and the snapshot disagreed about their own structure.
        m.set("fep", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaFep.of(owner, handle(self, "fep").wdg);
            }
        });
        // hunger() — the hunger meter as an OBJECT: :level(), :label() and :efficacy(), mirroring
        // food:info().hunger. It was three flat verbs, one of them called :hunger() and answering a number.
        m.set("hunger", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaHunger.of(owner, handle(self, "hunger").wdg);
            }
        });
        m.set("exists", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                BAttrWnd w = handle(self, "exists").wdg;
                CharWnd c = (w == null) ? null : w.getparent(CharWnd.class);
                return LuaValue.valueOf((c != null) && (c.battr == w));
            }
        });
        // info() — the one SNAPSHOT escape hatch, the whole of the above in the documented shape.
        m.set("info", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return snapshot(handle(self, "info").wdg);
            }
        });
        return m;
    }

    private static LuaFood handle(LuaValue self, String method) {
        LuaFood h = resolve(self);
        if(h == null)
            throw new LuaError("food:" + method + "() — use a COLON call on a Food object"
                + " (s:char():food())");
        return h;
    }

    // ---- the reads (all Loading-guarded) -------------------------------------------------------------

    static BAttrWnd.FoodMeter feps(BAttrWnd w) {
        return (w == null) ? null : w.feps;
    }

    static BAttrWnd.GlutMeter glut(BAttrWnd w) {
        return (w == null) ? null : w.glut;
    }

    /** A defensive copy of the food-event list (it is rebuilt off-thread as the server pushes updates). */
    /** One {@link LuaFepEntry} from one food-bar element — what {@code fep:entry()} hands back. */
    static LuaValue entry(Addon owner, BAttrWnd.FoodMeter.El el) {
        String res = null, name = null;
        try {
            haven.Resource r = el.res.get();
            if(r != null)
                res = r.name;
            BAttrWnd.FoodMeter.Event ev = el.ev();
            if((ev != null) && (ev.nm != null))
                name = ev.nm;
        } catch(RuntimeException ex) {
            /* this event's resource is still Loading — keep the amount */
        }
        return LuaFepEntry.of(owner, res, name, el.a);
    }

    static java.util.List<BAttrWnd.FoodMeter.El> els(BAttrWnd.FoodMeter f) {
        try {
            return new ArrayList<BAttrWnd.FoodMeter.El>(f.els);
        } catch(RuntimeException e) {
            return new ArrayList<BAttrWnd.FoodMeter.El>();
        }
    }

    /** The food-event groups as {@code {{res?, name?, amount}}} — a plain value table (§2.8). */
    private static LuaValue entries(BAttrWnd.FoodMeter f) {
        LuaTable out = new LuaTable();
        int i = 0;
        for(BAttrWnd.FoodMeter.El el : els(f)) {
            LuaTable e = new LuaTable();
            try {
                Resource r = el.res.get();
                if(r != null)
                    e.set("res", LuaValue.valueOf(r.name));
                BAttrWnd.FoodMeter.Event ev = el.ev();
                if((ev != null) && (ev.nm != null))
                    e.set("name", LuaValue.valueOf(ev.nm));
            } catch(RuntimeException ex) {
                /* this event's resource is still Loading — keep the amount */
            }
            e.set("amount", LuaValue.valueOf(el.a));
            out.set(++i, e);
        }
        return out;
    }

    /**
     * The documented {@code Food} snapshot:
     * {@code {fep = {cap, total, entries}, hunger = {level, label, efficacy}}}. Either half is absent while
     * its meter has not arrived, so a partial snapshot is normal rather than an error.
     */
    static LuaValue snapshot(BAttrWnd w) {
        if(w == null)
            return LuaValue.NIL;
        LuaTable t = new LuaTable();
        BAttrWnd.FoodMeter f = feps(w);
        if(f != null) {
            LuaTable fep = new LuaTable();
            fep.set("cap", LuaValue.valueOf(f.cap));
            double sum = 0;
            for(BAttrWnd.FoodMeter.El el : els(f))
                sum += el.a;
            fep.set("total", LuaValue.valueOf(sum));
            fep.set("entries", entries(f));
            t.set("fep", fep);
        }
        BAttrWnd.GlutMeter g = glut(w);
        if(g != null) {
            LuaTable h = new LuaTable();
            h.set("level", LuaValue.valueOf(g.glut));
            if(g.lbl != null)
                h.set("label", LuaValue.valueOf(g.lbl));
            h.set("efficacy", LuaValue.valueOf(g.gmod));
            t.set("hunger", h);
        }
        return t;
    }
}
