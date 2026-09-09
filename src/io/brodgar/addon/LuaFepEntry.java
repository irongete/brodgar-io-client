package io.brodgar.addon;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.OneArgFunction;

/**
 * One <b>food event</b> on the FEP bar (091, A-077) &mdash; a resource, its display name and its amount.
 *
 * <p>Data, like a recipe's slots: the client rebuilds the bar wholesale, so an entry has no key to be
 * addressed by and nothing to track. It carries what it was minted from and {@code food:fep():entry()} is
 * read again rather than held.
 *
 * <p><b>Interned on what it carries</b> (audit2 B10). It is a collection MEMBER, which is exactly where the
 * grammar puts identity, so {@code fep:entry():list()[1] == fep:entry():list()[1]} — and since the entry is
 * data and nothing else, what it is data <i>about</i> is the only key there is: the resource, the name and
 * the amount. Two reads of one food event are one object for as long as the bar says the same thing, and a
 * bar the client has rebuilt mints new ones and lets the old go.
 */
final class LuaFepEntry {
    /** The intern key's separator: a character neither a resource name nor a display name can carry. */
    private static final String SEP = String.valueOf((char)0);

    private final String res;
    private final String name;
    private final double amount;

    private LuaFepEntry(String res, String name, double amount) {
        this.res = res;
        this.name = name;
        this.amount = amount;
    }

    public String toString() {
        return "FepEntry(" + ((name != null) ? name : ((res != null) ? res : "?")) + ")";
    }

    static LuaValue of(final Addon owner, final String res, final String name, final double amount) {
        String key = res + SEP + name + SEP + amount;
        return owner.fepEntries.of(key,
            () -> LuaValue.userdataOf(new LuaFepEntry(res, name, amount), meta(owner)));
    }

    static LuaFepEntry resolve(LuaValue v) {
        if((v == null) || !v.isuserdata())
            return null;
        Object o = v.touserdata();
        return (o instanceof LuaFepEntry) ? (LuaFepEntry)o : null;
    }

    /** What a <b>string</b> filter matches: the display name, else the resource. */
    static String needle(LuaValue member) {
        LuaFepEntry h = resolve(member);
        if(h == null)
            return null;
        return (h.name != null) ? h.name : h.res;
    }

    private static LuaFepEntry handle(LuaValue self, String method) {
        LuaFepEntry h = resolve(self);
        if(h == null)
            throw new LuaError("fepentry:" + method + "() — use a COLON call on one of the entries"
                + " session:char():food():fep():entry():list() hands back");
        return h;
    }

    private static LuaValue meta(Addon owner) {
        if(owner.fepEntryMeta != null)
            return owner.fepEntryMeta;
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, Refusal.closedIndex("fepentry", methods(),
            "one food event"));
        mt.set("__name", LuaValue.valueOf("FepEntry"));
        mt.set("__tostring", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaFepEntry h = resolve(self);
                return LuaValue.valueOf((h == null) ? "FepEntry(?)" : h.toString());
            }
        });
        owner.fepEntryMeta = mt;
        return mt;
    }

    private static LuaTable methods() {
        LuaTable m = new LuaTable();
        m.set("res", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                String r = handle(self, "res").res;
                return (r == null) ? LuaValue.NIL : LuaValue.valueOf(r);
            }
        });
        m.set("name", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                String n = handle(self, "name").name;
                return (n == null) ? LuaValue.NIL : LuaValue.valueOf(n);
            }
        });
        m.set("amount", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(handle(self, "amount").amount);
            }
        });
        m.set("info", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaFepEntry h = handle(self, "info");
                LuaTable t = new LuaTable();
                if(h.res != null)
                    t.set("res", LuaValue.valueOf(h.res));
                if(h.name != null)
                    t.set("name", LuaValue.valueOf(h.name));
                t.set("amount", LuaValue.valueOf(h.amount));
                return t;
            }
        });
        return m;
    }
}
