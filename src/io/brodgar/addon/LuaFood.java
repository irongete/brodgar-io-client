package io.brodgar.addon;

import haven.BAttrWnd;
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
 * A <b>Food object</b> — the character's food-event points and hunger ({@code hafen.char():food()}), read
 * off the character sheet's base-attributes tab. It is <b>the one place in the client with absolute numbers
 * about the character</b>: everywhere else a bar is a fraction.
 *
 * <p><b>The intern key is the widget's identity</b> (§2.4: the engine exposes only a widget). There is one
 * food state per character and no id for it, and the tab is rebuilt on a relog, so the widget is both the
 * identity and the lifetime — the same choice {@link LuaBuff} and {@link LuaMeter} make.
 *
 * <p><b>Every read is a live re-read and may answer {@code nil}</b> while the numbers stream in, which they
 * do for a beat after entering the world and again after every meal. {@code hafen.char():food()} itself is
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

        Cache(Addon owner) {
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
                mt = buildMeta();
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

    private static LuaValue buildMeta() {
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, Retired.methodIndex("food", methods()));
        mt.set("__name", LuaValue.valueOf("Food"));
        mt.set("__tostring", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf("Food");
            }
        });
        return mt;
    }

    private static LuaTable methods() {
        LuaTable m = new LuaTable();
        // cap() — how much the FEP bar holds before it stops accepting more.
        m.set("cap", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                BAttrWnd.FoodMeter f = feps(handle(self, "cap").wdg);
                return (f == null) ? LuaValue.NIL : LuaValue.valueOf(f.cap);
            }
        });
        // total() — the sum of the current food-event amounts: how full the bar actually is.
        m.set("total", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                BAttrWnd.FoodMeter f = feps(handle(self, "total").wdg);
                if(f == null)
                    return LuaValue.NIL;
                double sum = 0;
                for(BAttrWnd.FoodMeter.El el : els(f))
                    sum += el.a;
                return LuaValue.valueOf(sum);
            }
        });
        // feps() — the food-event groups as a plain array of {res?, name?, amount}: a value, not entities.
        m.set("feps", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                BAttrWnd.FoodMeter f = feps(handle(self, "feps").wdg);
                return (f == null) ? new LuaTable() : entries(f);
            }
        });
        // hunger() — the hunger level itself, the number the client draws the glut bar from.
        m.set("hunger", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                BAttrWnd.GlutMeter g = glut(handle(self, "hunger").wdg);
                return (g == null) ? LuaValue.NIL : LuaValue.valueOf(g.glut);
            }
        });
        // label() — the client's own word for that level ("Satiated", …), or nil before one arrives.
        m.set("label", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                BAttrWnd.GlutMeter g = glut(handle(self, "label").wdg);
                return ((g == null) || (g.lbl == null)) ? LuaValue.NIL : LuaValue.valueOf(g.lbl);
            }
        });
        // efficacy() — how much food is worth at the current hunger: the multiplier on what you eat next.
        m.set("efficacy", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                BAttrWnd.GlutMeter g = glut(handle(self, "efficacy").wdg);
                return (g == null) ? LuaValue.NIL : LuaValue.valueOf(g.gmod);
            }
        });
        // exists() — is this still the live base-attributes tab? False after a relog rebuilt it.
        m.set("exists", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(handle(self, "exists").wdg == CharApi.battrwnd());
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
                + " (hafen.char():food())");
        return h;
    }

    // ---- the reads (all Loading-guarded) -------------------------------------------------------------

    private static BAttrWnd.FoodMeter feps(BAttrWnd w) {
        return (w == null) ? null : w.feps;
    }

    private static BAttrWnd.GlutMeter glut(BAttrWnd w) {
        return (w == null) ? null : w.glut;
    }

    /** A defensive copy of the food-event list (it is rebuilt off-thread as the server pushes updates). */
    private static java.util.List<BAttrWnd.FoodMeter.El> els(BAttrWnd.FoodMeter f) {
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
