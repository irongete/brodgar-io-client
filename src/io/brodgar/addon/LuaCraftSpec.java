package io.brodgar.addon;

import haven.Makewindow;
import haven.Resource;
import haven.Indir;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.OneArgFunction;

/**
 * One <b>slot of a recipe</b> (091, A-076) &mdash; an ingredient, a product, a quality input or a tool.
 *
 * <p><b>Why it is an object.</b> The four reads were the only ones in the API where <i>both</i> levels were
 * plain: not a collection, and not objects inside it. So {@code i.name or i.res} &mdash; the page's own
 * example &mdash; was dot-access on an anonymous table, a typo read {@code nil} with nothing to say so, and
 * nothing could be passed back or compared. And {@code num == -1} was a sentinel meaning "unspecified,
 * behaves as 1", which a reader had to remember; {@code :count()} answers {@code 1}.
 *
 * <p><b>One type, four uses.</b> An ingredient and a product carry a count and an optional flag; a quality
 * input and a tool carry neither, and answer {@code nil} for both. That is the shape the window itself has,
 * and it is why {@code types.md} documents {@code ResRef} as a {@code CraftSpec} with two fields missing
 * rather than as a type of its own.
 *
 * <p>It holds the values it was minted from rather than re-resolving: a recipe's slots are <b>data</b>
 * (§2.8), rebuilt wholesale by the server on every update, so there is no key to address one by and nothing
 * to track. Read the recipe again rather than holding a slot across one.
 */
final class LuaCraftSpec {
    private final String res;
    private final String name;
    /** The required or produced count; {@code -1} is the wire's "unspecified". Absent for a tool. */
    private final Integer num;
    /** Whether it is optional. Absent for a tool and a quality input. */
    private final Boolean opt;

    private LuaCraftSpec(String res, String name, Integer num, Boolean opt) {
        this.res = res;
        this.name = name;
        this.num = num;
        this.opt = opt;
    }

    public String toString() {
        return "CraftSpec(" + ((name != null) ? name : ((res != null) ? res : "?")) + ")";
    }

    /** An ingredient or a product: it carries a count and an optional flag. */
    static LuaValue of(Addon owner, Makewindow.Spec spec) {
        Indir<Resource> r = (spec.constraint != null) ? spec.constraint.res : spec.item.res;
        String rid = AddonManager.resIdent(r);
        boolean o;
        try {
            o = spec.opt();                       // reads info() — may Loading before resources land
        } catch(RuntimeException e) {
            o = false;
        }
        return LuaValue.userdataOf(new LuaCraftSpec(rid, AddonManager.resTipName(r, rid),
                                                    Integer.valueOf(spec.num), Boolean.valueOf(o)),
                                   meta(owner));
    }

    /** A tool or a quality input: a resource and a name, and nothing else to say. */
    static LuaValue of(Addon owner, Indir<Resource> r) {
        String rid = AddonManager.resIdent(r);
        return LuaValue.userdataOf(new LuaCraftSpec(rid, AddonManager.resTipName(r, rid), null, null),
                                   meta(owner));
    }

    static LuaCraftSpec resolve(LuaValue v) {
        if((v == null) || !v.isuserdata())
            return null;
        Object o = v.touserdata();
        return (o instanceof LuaCraftSpec) ? (LuaCraftSpec)o : null;
    }

    /** What a <b>string</b> filter matches: the display name, else the resource. */
    static String needle(LuaValue member) {
        LuaCraftSpec h = resolve(member);
        if(h == null)
            return null;
        return (h.name != null) ? h.name : h.res;
    }

    private static LuaCraftSpec handle(LuaValue self, String method) {
        LuaCraftSpec h = resolve(self);
        if(h == null)
            throw new LuaError("craftspec:" + method + "() — use a COLON call on one of the slots"
                + " session:craft():inputs() / :outputs() / :qualityInputs() / :tools() hands back");
        return h;
    }

    private static LuaValue meta(Addon owner) {
        if(owner.craftSpecMeta != null)
            return owner.craftSpecMeta;
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, Retired.closedIndex("craftspec", methods(),
            "one slot of a recipe answers :res() :name() :count() :optional() and :info()"));
        mt.set("__name", LuaValue.valueOf("CraftSpec"));
        mt.set("__tostring", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaCraftSpec h = resolve(self);
                return LuaValue.valueOf((h == null) ? "CraftSpec(?)" : h.toString());
            }
        });
        owner.craftSpecMeta = mt;
        return mt;
    }

    private static LuaTable methods() {
        LuaTable m = new LuaTable();
        // res() — the DISPLAYED resource: the constraint category when the recipe accepts one ("any board"),
        // else the concrete item, mirroring what the window itself draws in the slot.
        m.set("res", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                String r = handle(self, "res").res;
                return (r == null) ? LuaValue.NIL : LuaValue.valueOf(r);
            }
        });
        // name() — its display name, from the resource's own tooltip. nil while that is still loading.
        m.set("name", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                String n = handle(self, "name").name;
                return (n == null) ? LuaValue.NIL : LuaValue.valueOf(n);
            }
        });
        // count() — how many are required or produced. The wire's -1 meant "unspecified, behaves as 1", and
        // this answers 1 rather than making a reader remember a sentinel. nil on a tool and a quality input,
        // which carry no count at all.
        m.set("count", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Integer n = handle(self, "count").num;
                if(n == null)
                    return LuaValue.NIL;
                return LuaValue.valueOf((n.intValue() < 0) ? 1 : n.intValue());
            }
        });
        // optional() — an optional ingredient or a chance byproduct. nil where the idea does not apply.
        m.set("optional", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Boolean o = handle(self, "optional").opt;
                return (o == null) ? LuaValue.NIL : LuaValue.valueOf(o.booleanValue());
            }
        });
        // info() — the one SNAPSHOT escape hatch, in the client's own spelling: num keeps the -1.
        m.set("info", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaCraftSpec h = handle(self, "info");
                LuaTable t = new LuaTable();
                if(h.res != null)
                    t.set("res", LuaValue.valueOf(h.res));
                if(h.name != null)
                    t.set("name", LuaValue.valueOf(h.name));
                if(h.num != null)
                    t.set("num", LuaValue.valueOf(h.num.intValue()));
                if(h.opt != null)
                    t.set("opt", LuaValue.valueOf(h.opt.booleanValue()));
                return t;
            }
        });
        return m;
    }
}
