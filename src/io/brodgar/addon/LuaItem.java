package io.brodgar.addon;

import haven.Equipory;
import haven.GItem;
import haven.Inventory;
import haven.ItemInfo;
import haven.UI;
import haven.WItem;
import haven.Widget;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.OneArgFunction;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An <b>Item object</b> — one thing inside a container ({@code widget:items()}) or on the cursor
 * ({@code hafen.ui():hand()}), with what the client knows about it: its resource, its display name, a
 * stack count, wear, quality, and where it is sitting.
 *
 * <p><b>The intern key is the item widget's own identity, and that is the whole point of this type.</b>
 * An item has no content id; the server addresses it by a <i>widget id</i>, and a widget id is handed
 * back to the pool when the widget dies, so the server may give the same number to something else. A
 * handle keyed on that number would therefore not go stale — it would silently start naming a different
 * item, and a write made through it would land on whatever now holds the id. That is the failure this
 * whole entity exists to delete, so the handle holds the {@link GItem} <b>object</b>: it is the item it
 * was, for as long as anyone holds it, and when the item moves or is consumed the object is simply gone
 * ({@code :exists()} false) rather than repointed. {@link ActApi} resolves a gated verb through this
 * object and never through the number.
 *
 * <p><b>A stale Item still answers.</b> {@code Widget.destroy()} unlinks the item without clearing it, so
 * {@code :res()}, {@code :name()}, {@code :num()} and {@code :quality()} go on reading the thing it was —
 * which is what makes a stashed {@code onItemRemoved} payload worth holding. What a stale item has no
 * answer for is <i>where</i> it is: {@code :cell()} is nil, {@code :slots()} is empty and
 * {@code :handle()} is nil, because the id is exactly the thing that is no longer its.
 *
 * <p><b>Where it sits is two verbs, not one shape.</b> A backpack cell is a lattice coordinate and an
 * equipment slot is a name, so {@code :cell()} and {@code :slots()} say which you meant instead of one
 * verb whose return type depends on the container. {@code :slots()} is plural because an
 * {@link Equipory} draws one worn item in as many slots as it fills — the same item, twice on screen.
 *
 * <p><b>Quality is read off the item's own published tooltip code, by class name.</b> The engine has no
 * quality type: the number lives in a field of a class that ships inside a resource. Reading it by name
 * (rather than pinning a local copy of that class) cannot go wrong when the resource is revised — it can
 * only stop answering, which is what {@code nil} already means everywhere else here.
 */
public final class LuaItem {
    /** The item widget this handle addresses — the whole state of a handle. */
    public final GItem wdg;

    private LuaItem(GItem wdg) {
        this.wdg = wdg;
    }

    /** {@code tostring(item)}: {@code Item(<resname>)}. */
    public String toString() {
        String r = CharApi.itemResOf(wdg);
        return "Item(" + ((r == null) ? "?" : r) + ")";
    }

    /** An interned Item object for {@code it} in {@code owner}'s env. */
    static LuaValue of(Addon owner, GItem it) {
        return owner.items.of(it);
    }

    /** The {@code LuaItem} behind a Lua value, or {@code null} for anything else. */
    static LuaItem resolve(LuaValue v) {
        if((v == null) || !v.isuserdata())
            return null;
        Object o = v.touserdata();
        return (o instanceof LuaItem) ? (LuaItem)o : null;
    }

    // ---- the per-addon intern cache + metatable ---------------------------------------------------

    /** One addon's Item cache and metatable (its {@link Addon#items}), keyed by widget identity. */
    static final class Cache {
        private final Map<GItem, Ref> live = new IdentityHashMap<GItem, Ref>();
        private final ReferenceQueue<LuaValue> dead = new ReferenceQueue<LuaValue>();
        private LuaValue mt;

        Cache(Addon owner) {
        }

        synchronized LuaValue of(GItem it) {
            drain();
            if(it == null)
                return LuaValue.NIL;
            Ref r = live.get(it);
            if(r != null) {
                LuaValue v = r.get();
                if(v != null)
                    return v;
                live.remove(it);
            }
            LuaValue v = LuaValue.userdataOf(new LuaItem(it), meta());
            live.put(it, new Ref(v, it, dead));
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
        final GItem key;

        Ref(LuaValue v, GItem key, ReferenceQueue<LuaValue> q) {
            super(v, q);
            this.key = key;
        }
    }

    // ---- the Item metatable -------------------------------------------------------------------------

    private static LuaValue buildMeta() {
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, Retired.methodIndex("item", methods()));
        mt.set("__name", LuaValue.valueOf("Item"));
        mt.set("__tostring", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaItem h = resolve(self);
                return LuaValue.valueOf((h == null) ? "Item(?)" : h.toString());
            }
        });
        return mt;
    }

    private static LuaTable methods() {
        LuaTable m = new LuaTable();
        // res() — the item's resource name, its stable identity, or nil while it resolves.
        m.set("res", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                String r = CharApi.itemResOf(handle(self, "res").wdg);
                return (r == null) ? LuaValue.NIL : LuaValue.valueOf(r);
            }
        });
        // name() — the display name, or nil until the item's tooltip info lands.
        m.set("name", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                String n = CharApi.itemNameOf(handle(self, "name").wdg);
                return (n == null) ? LuaValue.NIL : LuaValue.valueOf(n);
            }
        });
        // num() — how many are in the stack, or nil for a thing that is not a stack.
        m.set("num", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                int n = handle(self, "num").wdg.num;
                return (n == -1) ? LuaValue.NIL : LuaValue.valueOf(n);
            }
        });
        // wear() — the item's meter, 0..100, or nil when it carries none.
        m.set("wear", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                int w = handle(self, "wear").wdg.meter;
                return (w > 0) ? LuaValue.valueOf(w) : LuaValue.NIL;
            }
        });
        // quality() — the number the tooltip shows, or nil while the item's info resolves (and for the
        // things that have no quality at all).
        m.set("quality", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Double q = quality(handle(self, "quality").wdg);
                return (q == null) ? LuaValue.NIL : LuaValue.valueOf(q.doubleValue());
            }
        });
        // cell() — the {x, y} grid cell in the container holding it, or nil (worn, on the cursor, gone).
        m.set("cell", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return cell(live(handle(self, "cell")));
            }
        });
        // slots() — the equipment slots this item fills, by name; empty for anything not worn.
        m.set("slots", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return slots(live(handle(self, "slots")));
            }
        });
        // handle() — the server widget id, for hafen.act():raw. nil once the item is gone: the number is
        // no longer its, and the server may already have given it to something else.
        m.set("handle", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                int id = wdgid(live(handle(self, "handle")));
                return (id < 0) ? LuaValue.NIL : LuaValue.valueOf(id);
            }
        });
        // exists() — is this still a live item somewhere in the tree?
        m.set("exists", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(live(handle(self, "exists")) != null);
            }
        });
        // info() — the one SNAPSHOT escape hatch.
        m.set("info", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return snapshot(handle(self, "info").wdg);
            }
        });
        return m;
    }

    private static LuaItem handle(LuaValue self, String method) {
        LuaItem h = resolve(self);
        if(h == null)
            throw new LuaError("item:" + method + "() — use a COLON call on an Item object"
                + " (widget:items()[i], or hafen.ui():hand())");
        return h;
    }

    // ---- liveness ------------------------------------------------------------------------------------

    /**
     * The live {@link GItem} behind a handle, or {@code null} once it is gone. Tree reachability is the
     * test, exactly as it is for a widget: the server destroying an item unlinks it, and an item that is
     * not under the root is not in any container. A missing {@link UI} answers {@code null} without
     * clearing anything — unresolvable now is not proven dead.
     */
    static GItem live(LuaItem h) {
        return (h == null) ? null : live(h.wdg);
    }

    /** As {@link #live(LuaItem)}, on the widget itself. */
    static GItem live(GItem it) {
        if(it == null)
            return null;
        UI u = AddonManager.ui;
        if((u == null) || (u.root == null))
            return null;
        return it.hasparent(u.root) ? it : null;
    }

    /** The item's server widget id, or {@code -1} (gone, or never bound). */
    private static int wdgid(GItem it) {
        if((it == null) || (it.ui == null))
            return -1;
        return it.wdgid();
    }

    // ---- where it sits (derived from the container, never stored) --------------------------------------

    /** The {@link WItem}s drawing {@code it} in its container — one per inventory cell or equipment slot. */
    private static List<WItem> wits(GItem it) {
        List<WItem> out = new ArrayList<WItem>();
        Widget p = (it == null) ? null : it.parent;
        UI u = AddonManager.ui;
        if((p == null) || (u == null))
            return out;
        synchronized(u) {
            for(WItem w : p.children(WItem.class)) {
                if(w.item == it)
                    out.add(w);
            }
        }
        return out;
    }

    /** {@code item:cell()} — the inventory grid cell, or nil for a worn / cursor / departed item. */
    private static LuaValue cell(GItem it) {
        if((it == null) || !(it.parent instanceof Inventory))
            return LuaValue.NIL;
        for(WItem w : wits(it))
            return AddonManager.cellPos(w);
        return LuaValue.NIL;
    }

    /** {@code item:slots()} — the names of the equipment slots this item fills, in the window's order. */
    private static LuaValue slots(GItem it) {
        LuaTable out = new LuaTable();
        if((it == null) || !(it.parent instanceof Equipory))
            return out;
        Equipory eq = (Equipory)it.parent;
        int i = 0;
        for(WItem w : wits(it)) {
            LuaValue nm = slotName(eq, w);
            if(!nm.isnil())
                out.set(++i, nm);
        }
        return out;
    }

    /**
     * One worn {@link WItem}'s slot name, from the coordinate the {@link Equipory} drew it at.
     *
     * <p><b>A slot the client does not name is still a slot.</b> The equipment window publishes a display
     * name for all but one of its places (the name comes off that slot's own background resource, and one
     * slot ships without one), so naming only what it names would drop a worn item's place entirely —
     * {@code :slots()} would read empty, which is what "not worn" reads. So an unnamed slot falls back to
     * the engine's own identifier for it, and the list is complete by construction.
     */
    private static LuaValue slotName(Equipory eq, WItem w) {
        try {
            int ep = eq.epat(w.c);
            if(ep < 0)
                return LuaValue.NIL;                       // not in any slot: nothing to name
            LuaValue nm = CharApi.slotName(ep);
            return nm.isnil() ? LuaValue.valueOf("ep" + ep) : nm;
        } catch(RuntimeException e) {
            return LuaValue.NIL;
        }
    }

    // ---- quality: the one number only the resource's own code knows -------------------------------------

    /** The class every quality tooltip derives from, wherever it was loaded from. */
    private static final String QBUFF = "haven.res.ui.tt.q.qbuff.QBuff";
    /** Its plain-quality subclass — the one to prefer when an item publishes several. */
    private static final String QUALITY = "haven.res.ui.tt.q.quality.Quality";
    /** Each info class seen → its {@code q} field, or {@code null} for "this one is not a quality". */
    private static final Map<Class<?>, Field> qfields = new HashMap<Class<?>, Field>();

    /**
     * The item's quality, or {@code null} when it has none and while its info is still resolving.
     *
     * <p>Read <b>by class name</b>: quality is published by code that ships inside a resource
     * ({@code ui/tt/q/quality}, whose class extends {@code ui/tt/q/qbuff}'s), so there is no type here to
     * compare against. Naming the class instead of pinning a local copy of it means a revised resource
     * makes this answer {@code nil}, never something wrong — the same trade {@code gob:kin()} makes.
     */
    static Double quality(GItem it) {
        List<ItemInfo> info;
        try {
            info = it.info();
        } catch(RuntimeException e) {   // info() still Loading
            return null;
        }
        if(info == null)
            return null;
        Double any = null;
        for(ItemInfo inf : info) {
            Field f = qfield(inf.getClass());
            if(f == null)
                continue;
            try {
                double q = f.getDouble(inf);
                if(inf.getClass().getName().equals(QUALITY))
                    return Double.valueOf(q);   // the plain quality wins over any other buff row
                if(any == null)
                    any = Double.valueOf(q);
            } catch(Exception e) {
                return null;
            }
        }
        return any;
    }

    /** The {@code q} field {@code c} inherits from the quality-tooltip class, or {@code null}. */
    private static synchronized Field qfield(Class<?> c) {
        if(qfields.containsKey(c))
            return qfields.get(c);
        Field f = null;
        for(Class<?> k = c; k != null; k = k.getSuperclass()) {
            if(k.getName().equals(QBUFF)) {
                try {
                    f = k.getDeclaredField("q");
                    f.setAccessible(true);
                } catch(Exception e) {
                    f = null;
                }
                break;
            }
        }
        qfields.put(c, f);
        return f;
    }

    // ---- the snapshot + the container read -------------------------------------------------------------

    /** The documented {@code Item} snapshot — every field optional, absent rather than empty. */
    static LuaValue snapshot(GItem it) {
        if(it == null)
            return LuaValue.NIL;
        LuaTable t = new LuaTable();
        String res = CharApi.itemResOf(it);
        if(res != null)
            t.set("res", LuaValue.valueOf(res));
        String name = CharApi.itemNameOf(it);
        if(name != null)
            t.set("name", LuaValue.valueOf(name));
        if(it.num != -1)
            t.set("num", LuaValue.valueOf(it.num));
        if(it.meter > 0)
            t.set("wear", LuaValue.valueOf(it.meter));
        Double q = quality(it);
        if(q != null)
            t.set("quality", LuaValue.valueOf(q.doubleValue()));
        GItem l = live(it);
        int id = wdgid(l);
        if(id >= 0)
            t.set("handle", LuaValue.valueOf(id));
        LuaValue c = cell(l);
        if(!c.isnil())
            t.set("cell", c);
        LuaValue sl = slots(l);
        if(sl.length() > 0)
            t.set("slots", sl);
        return t;
    }

    /**
     * The items inside a container, <b>each one once</b> ({@code widget:items()}). The {@link WItem} walk is
     * what defines a container (and it is deep, so a whole window answers for the grid inside it) — but an
     * {@link Equipory} draws one worn item in every slot it fills, and two entries that are {@code ==} would
     * make {@code #items} a lie about how many things you have. So the list is de-duplicated on the item
     * itself, and the slots it fills are read from it with {@code :slots()}.
     */
    static List<GItem> items(Widget container) {
        List<GItem> out = new ArrayList<GItem>();
        Set<GItem> seen = Collections.newSetFromMap(new IdentityHashMap<GItem, Boolean>());
        for(WItem w : LuaWidget.witems(container)) {
            if((w.item != null) && seen.add(w.item))
                out.add(w.item);
        }
        return out;
    }

    /** {@link #items} as one owner's array of Item objects — what {@code widget:items()} hands back. */
    static LuaValue list(Addon owner, Widget container) {
        LuaTable out = new LuaTable();
        List<GItem> its = items(container);
        for(int i = 0; i < its.size(); i++)
            out.set(i + 1, of(owner, its.get(i)));
        return out;
    }

    /**
     * A worn item's description for the equipment adapter's change detection: what it is, how many, and
     * which slots it fills. <b>Wear and quality are deliberately absent</b> — a durability drifting down or
     * a tooltip resolving is not an equipment change, and firing {@code EquipChanged} for either would make
     * the event useless to the handler that wants to know you put a hat on.
     */
    static String equipKey(GItem it) {
        StringBuilder sb = new StringBuilder();
        sb.append(CharApi.itemResOf(it)).append('\n').append(CharApi.itemNameOf(it))
          .append('\n').append(it.num);
        LuaValue sl = slots(it);
        for(int i = 1; i <= sl.length(); i++)
            sb.append('\n').append(sl.get(i).tojstring());
        return sb.toString();
    }
}
