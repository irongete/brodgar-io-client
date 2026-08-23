package io.brodgar.addon;

import haven.BAttrWnd;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.OneArgFunction;

import java.util.ArrayList;
import java.util.List;

/**
 * The <b>FEP bar</b> of one character (091, A-077) &mdash; its cap, its total, and the food events in it.
 *
 * <p><b>Why it is an object.</b> The live reads flattened a nested snapshot: what {@code food:info()} calls
 * {@code fep.cap} and {@code fep.total} were {@code food:cap()} and {@code food:total()} at the top level,
 * and what it calls {@code fep.entries} was {@code food:feps()}, an array of anonymous tables. So two
 * shapes described one fact and disagreed about their own structure, and a reader translating a snapshot
 * into live reads had to guess. Now {@code food:fep():cap()}, {@code :total()} and {@code :entry()} mirror
 * the snapshot exactly, which is what {@code types.md} always implied.
 *
 * <p>It re-resolves: the Fep holds the tab widget, never a number.
 */
final class LuaFep {
    private final BAttrWnd wdg;

    private LuaFep(BAttrWnd wdg) {
        this.wdg = wdg;
    }

    public String toString() {
        return "Fep()";
    }

    static LuaValue of(Addon owner, BAttrWnd wdg) {
        return (wdg == null) ? LuaValue.NIL : LuaValue.userdataOf(new LuaFep(wdg), meta(owner));
    }

    static LuaFep resolve(LuaValue v) {
        if((v == null) || !v.isuserdata())
            return null;
        Object o = v.touserdata();
        return (o instanceof LuaFep) ? (LuaFep)o : null;
    }

    private static LuaFep handle(LuaValue self, String method) {
        LuaFep h = resolve(self);
        if(h == null)
            throw new LuaError("fep:" + method + "() — use a COLON call on the Fep"
                + " session:char():food():fep() hands back");
        return h;
    }

    private static LuaValue meta(final Addon owner) {
        if(owner.fepMeta != null)
            return owner.fepMeta;
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, Refusal.closedIndex("fep", methods(owner),
            "the FEP bar"));
        mt.set("__name", LuaValue.valueOf("Fep"));
        mt.set("__tostring", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaFep h = resolve(self);
                return LuaValue.valueOf((h == null) ? "Fep(?)" : h.toString());
            }
        });
        owner.fepMeta = mt;
        return mt;
    }

    private static LuaTable methods(final Addon owner) {
        LuaTable m = new LuaTable();
        // cap() — how much the bar can hold before it stops counting.
        m.set("cap", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                BAttrWnd.FoodMeter f = LuaFood.feps(handle(self, "cap").wdg);
                return (f == null) ? LuaValue.NIL : LuaValue.valueOf(f.cap);
            }
        });
        // total() — the sum of the current food-event amounts: how full the bar actually is.
        m.set("total", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                BAttrWnd.FoodMeter f = LuaFood.feps(handle(self, "total").wdg);
                if(f == null)
                    return LuaValue.NIL;
                double sum = 0;
                for(BAttrWnd.FoodMeter.El el : LuaFood.els(f))
                    sum += el.a;
                return LuaValue.valueOf(sum);
            }
        });
        // entry() — the food events in the bar, a collection of FepEntry.
        m.set("entry", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                final LuaFep h = handle(self, "entry");
                return LuaCollection.create("fep:entry()", new LuaCollection.Source() {
                    public List<LuaValue> members() {
                        List<LuaValue> out = new ArrayList<LuaValue>();
                        BAttrWnd.FoodMeter f = LuaFood.feps(h.wdg);
                        if(f == null)
                            return out;
                        for(BAttrWnd.FoodMeter.El el : LuaFood.els(f))
                            out.add(LuaFood.entry(owner, el));
                        return out;
                    }

                    /** These have a name, so a string filter is a substring test over it. */
                    public boolean named() {
                        return true;
                    }

                    public String needle(LuaValue member) {
                        return LuaFepEntry.needle(member);
                    }

                    public String noGet() {
                        return "a food event has no key: fep:entry():find(needle) searches the display name"
                            + " and the resource, and fep:entry():list()[n] takes a position";
                    }
                }, null);
            }
        });
        m.set("exists", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(LuaFood.feps(handle(self, "exists").wdg) != null);
            }
        });
        // info() — the one SNAPSHOT escape hatch, the same shape food:info().fep carries.
        m.set("info", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaFep h = handle(self, "info");
                BAttrWnd.FoodMeter f = LuaFood.feps(h.wdg);
                if(f == null)
                    return LuaValue.NIL;
                LuaTable t = new LuaTable();
                t.set("cap", LuaValue.valueOf(f.cap));
                double sum = 0;
                LuaTable es = new LuaTable();
                int i = 0;
                for(BAttrWnd.FoodMeter.El el : LuaFood.els(f)) {
                    sum += el.a;
                    LuaValue e = LuaFood.entry(owner, el);
                    es.set(++i, e.get("info").call(e));
                }
                t.set("total", LuaValue.valueOf(sum));
                t.set("entries", es);
                return t;
            }
        });
        return m;
    }
}
