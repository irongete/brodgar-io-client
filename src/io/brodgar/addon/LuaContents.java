package io.brodgar.addon;

import haven.GItem;
import haven.ItemInfo;
import haven.UI;
import haven.Widget;

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
 * A <b>Contents object</b> — what one item holds ({@code item:contents()}), and the answer to a question the
 * Item entity could not put a shape on: a <b>stack</b> and a <b>creel</b> carry real items, each with its own
 * quality and its own server address, while a bucket carries a line its tooltip states and no items at all.
 *
 * <p><b>One type covers both insides, because the client cannot tell them apart.</b> The difference between a
 * stack and a creel is the server's and it never states it — the only mark is whether it ever sends the
 * message that pins the contents window open — so reading both through one verb means nothing has to guess. A
 * design that guessed would answer confidently and wrongly.
 *
 * <p><b>Interned on the owning {@link GItem}</b>, exactly as {@link LuaItem} is interned on the item widget and
 * for the same reason: two reads of one item's contents are {@code ==}, and the object goes on naming
 * <i>that</i> item's inside rather than being repointed. {@code item:contents()} answers {@code nil} — never a
 * half-built object — for an item that holds nothing and while its info is still resolving, which is what makes
 * {@code nil} mean "holds nothing" and an empty {@code :items()} mean "an empty container".
 *
 * <p><b>Its reads come from two unrelated places, and that is the whole point of the type.</b> The items are the
 * {@link GItem} children of the <b>widget</b> the server pushed ({@code GItem.contents}), and the caption is
 * {@code GItem.contentsnm}. What a bucket holds is stated by the item's own <b>tooltip info</b>
 * ({@link ItemInfo.Contents}) instead, which is also why an item carrying that block alone still holds
 * something.
 *
 * <p><b>The hover window is irrelevant to every one of them.</b> Hiding a {@code GItem.ContentsWindow} is
 * {@code chstate("hide")} and nothing else — only the contents widget being destroyed clears the fields — so
 * these reads answer the same with the window down, and no addon has to open anything to read what an item
 * holds.
 *
 * <p><b>{@code :info()} carries no {@code items}.</b> A snapshot holds no live objects, and a snapshot of a bag
 * that nested snapshots of bags would have no end; the items are read live off the object.
 */
public final class LuaContents {
    /** The item whose inside this is — the whole state of the handle, and its intern key. */
    public final GItem cont;

    private LuaContents(GItem cont) {
        this.cont = cont;
    }

    /** {@code tostring(c)}: {@code Contents(<resname>)} — the item it belongs to. */
    public String toString() {
        String r = CharApi.itemResOf(cont);
        return "Contents(" + ((r == null) ? "?" : r) + ")";
    }

    /** An interned Contents object for {@code it}'s inside, in {@code owner}'s env. */
    static LuaValue of(Addon owner, GItem it) {
        return owner.contents.of(it);
    }

    /** The {@code LuaContents} behind a Lua value, or {@code null} for anything else. */
    static LuaContents resolve(LuaValue v) {
        if((v == null) || !v.isuserdata())
            return null;
        Object o = v.touserdata();
        return (o instanceof LuaContents) ? (LuaContents)o : null;
    }

    // ---- what "holds something" means ---------------------------------------------------------------

    /**
     * Does this item hold anything at all — the test behind {@code item:contents()} answering an object rather
     * than {@code nil}. Two independent halves, either of which is enough: the contents <b>widget</b> the server
     * pushed (a stack, a creel), and the contents block of the item's own <b>tooltip</b> (a bucket, a jug).
     */
    static boolean holds(GItem it) {
        return (widget(it) != null) || (block(it) != null);
    }

    /** The contents widget the server pushed under this item, or {@code null} for an item that got none. */
    static Widget widget(GItem it) {
        return (it == null) ? null : it.contents;
    }

    /**
     * The item's contents tooltip block, or {@code null} when it prints none <b>and while its info is still
     * resolving</b>: {@code GItem.info()} throws a bare {@code Loading} while the resource streams, and an item
     * that cannot yet say what it holds holds nothing as far as this reads — never a half-built object.
     */
    static ItemInfo block(GItem it) {
        if(it == null)
            return null;
        List<ItemInfo> info;
        try {
            info = it.info();
        } catch(RuntimeException e) {   // info() still Loading
            return null;
        }
        if(info == null)
            return null;
        for(ItemInfo inf : info) {
            if(inf instanceof ItemInfo.Contents)
                return inf;
        }
        return null;
    }

    // ---- the per-addon intern cache + metatable ------------------------------------------------------

    /**
     * One addon's Contents cache and metatable (its {@link Addon#contents}), keyed by the <b>owning item
     * widget's</b> identity — the same key {@link LuaItem.Cache} uses, for the same reason: the server addresses
     * an item by a widget id it recycles, so a handle keyed on the number would silently start naming a
     * different item's inside.
     */
    static final class Cache {
        private final Addon owner;
        private final Map<GItem, Ref> live = new IdentityHashMap<GItem, Ref>();
        private final ReferenceQueue<LuaValue> dead = new ReferenceQueue<LuaValue>();
        private LuaValue mt;

        Cache(Addon owner) {
            this.owner = owner;
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
            LuaValue v = LuaValue.userdataOf(new LuaContents(it), meta());
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
                mt = buildMeta(owner);
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

    // ---- the Contents metatable ----------------------------------------------------------------------

    private static LuaValue buildMeta(final Addon owner) {
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, Retired.methodIndex("contents", methods(owner)));
        mt.set("__name", LuaValue.valueOf("Contents"));
        mt.set("__tostring", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaContents h = resolve(self);
                return LuaValue.valueOf((h == null) ? "Contents(?)" : h.toString());
            }
        });
        return mt;
    }

    private static LuaTable methods(final Addon owner) {
        LuaTable m = new LuaTable();
        // items() — what is inside, as live Item OBJECTS, each interned like any other item and each answering
        // its own :res(), :quality() and :container(). An EMPTY ARRAY, never nil, for a container that states
        // what it holds instead of carrying it (a bucket) and for one that is simply empty.
        m.set("items", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaTable out = new LuaTable();
                List<GItem> its = items(handle(self, "items").cont);
                for(int i = 0; i < its.size(); i++)
                    out.set(i + 1, LuaItem.of(owner, its.get(i)));
                return out;
            }
        });
        // name() — what the server calls this inside: the caption its own window carries, or nil when it gave
        // none (and for a container that carries no widget at all).
        m.set("name", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                String n = LuaContents.name(handle(self, "name").cont);
                return (n == null) ? LuaValue.NIL : LuaValue.valueOf(n);
            }
        });
        // info() — the one SNAPSHOT escape hatch, and it carries NO items: a snapshot holds no live objects, so
        // a snapshot of a bag would otherwise nest snapshots of bags without end.
        m.set("info", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return snapshot(handle(self, "info").cont);
            }
        });
        return m;
    }

    private static LuaContents handle(LuaValue self, String method) {
        LuaContents h = resolve(self);
        if(h == null)
            throw new LuaError("contents:" + method + "() — use a COLON call on a Contents object"
                + " (item:contents())");
        return h;
    }

    // ---- the reads ------------------------------------------------------------------------------------

    /**
     * The items inside, as the {@link GItem} children of the contents widget — the very walk
     * {@code GItem.addcontinfo} makes over {@code contents.children()}, and deliberately <b>not</b> the
     * {@code WItem} walk {@code widget:items()} uses: what class of widget the server pushes as the contents is
     * its own business, and a class that mints no {@code WItem}s would make this read empty in silence, which is
     * the worst failure available here. Taken under the {@code ui} monitor, like every other tree read.
     */
    static List<GItem> items(GItem cont) {
        List<GItem> out = new ArrayList<GItem>();
        Widget w = widget(cont);
        UI u = AddonManager.ui;
        if((w == null) || (u == null))
            return out;
        synchronized(u) {
            for(GItem it : w.children(GItem.class))
                out.add(it);
        }
        return out;
    }

    /** The caption the server gave this inside ({@code GItem.contentsnm}), or {@code null}. */
    static String name(GItem cont) {
        return (cont == null) ? null : cont.contentsnm;
    }

    /** The documented {@code Contents} snapshot — every field optional, absent rather than empty. */
    static LuaValue snapshot(GItem cont) {
        if(cont == null)
            return LuaValue.NIL;
        LuaTable t = new LuaTable();
        String n = name(cont);
        if(n != null)
            t.set("name", LuaValue.valueOf(n));
        return t;
    }
}
