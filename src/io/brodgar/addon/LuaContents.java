package io.brodgar.addon;

import haven.GItem;
import haven.ItemInfo;
import haven.Text;
import haven.Widget;
import haven.res.ui.tt.level.Level;

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
 * something: {@code :text()} is the line that block states, {@code :quality()} is the <i>content's</i> own
 * number read out of it, and {@code :level()} is the fill meter's two counts. So a stack answers the first two
 * reads with items and {@code nil} to the last three, a bucket does the reverse, and neither has to be told
 * apart from the other to be read.
 *
 * <p><b>The substance a liquid container holds is never named to the client.</b> What arrives is a rendered
 * line, a quality and a fill; there is no liquid type behind them, which is why the API states what the tooltip
 * states and invents nothing above it.
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
    static ItemInfo.Contents block(GItem it) {
        List<ItemInfo> info = LuaItem.info(it);
        if(info == null)
            return null;
        for(ItemInfo inf : info) {
            if(inf instanceof ItemInfo.Contents)
                return (ItemInfo.Contents)inf;
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
        mt.set(LuaValue.INDEX, Retired.closedIndex("contents", methods(owner),
            "what one item holds answers :items() :name() :text() :quality() :fill() and :info()"));
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
                final LuaContents h = handle(self, "items");
                return LuaCollection.create("contents:items()", new LuaCollection.Source() {
                    public List<LuaValue> members() {
                        List<LuaValue> out = new ArrayList<LuaValue>();
                        for(GItem g : items(h.cont))
                            out.add(LuaItem.of(owner, g));
                        return out;
                    }

                    public String noGet() {
                        return "the things inside a container have no key of their own:"
                            + " contents:items():find(filter) is the search and"
                            + " contents:items():list()[n] takes a position";
                    }
                }, null);
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
        // text() — what the tooltip STATES about what is inside ("5.00 l of Water"), or nil for a container that
        // carries its contents as items instead of stating them. This is the whole of what a bucket can say: the
        // substance itself is never sent to the client, only that rendered line.
        m.set("text", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                String s = text(handle(self, "text").cont);
                return (s == null) ? LuaValue.NIL : LuaValue.valueOf(s);
            }
        });
        // quality() — the CONTENT's own quality, which is not the container's: the water in a bucket publishes a
        // quality inside the contents block exactly as an item publishes its own, and item:quality() goes on
        // answering the bucket's. nil when the block states none.
        m.set("quality", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Double q = quality(handle(self, "quality").cont);
                return (q == null) ? LuaValue.NIL : LuaValue.valueOf(q.doubleValue());
            }
        });
        // level() — the fill meter's {cur, max}, read off the adopted ui/tt/level class, or nil for a container
        // that draws none. Two ABSOLUTE counts: the engine itself only ever asks that class for the bare fraction
        // it paints over the icon, so this is the one place the numbers behind the bar are reachable.
        m.set("fill", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return level(handle(self, "level").cont);
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
        if(w == null)
            return out;
        synchronized(LuaWidget.monitor(w)) {
            for(GItem it : w.children(GItem.class))
                out.add(it);
        }
        return out;
    }

    /** The caption the server gave this inside ({@code GItem.contentsnm}), or {@code null}. */
    static String name(GItem cont) {
        return (cont == null) ? null : cont.contentsnm;
    }

    /**
     * What the tooltip <b>states</b> about what is inside, or {@code null} for a container that carries items
     * instead of stating anything.
     *
     * <p>The block's payload is a <b>whole nested tooltip</b> — the {@code ui/tt/cont} factory builds it with
     * {@code ItemInfo.buildinfo}, so what is inside describes itself with the same rows an item uses. The plain
     * text of that nested tip is what a reader sees as the line, so it is the plain-text rows that are taken here
     * and the structured ones ({@link #quality}, {@link #level}) that are read as themselves. Where a block states
     * more than one line they are joined in the tooltip's own order, because dropping one silently is the worse
     * failure of the two.
     *
     * <p><b>The substance is never named to the client.</b> That rendered line is the whole of what arrived; there
     * is no liquid type behind it to answer instead, which is why this reads as text and nothing here pretends
     * otherwise.
     */
    static String text(GItem cont) {
        ItemInfo.Contents b = block(cont);
        if((b == null) || (b.sub == null))
            return null;
        StringBuilder sb = new StringBuilder();
        for(ItemInfo inf : b.sub) {
            String ln = line(inf);
            if((ln == null) || (ln.length() == 0))
                continue;
            if(sb.length() > 0)
                sb.append('\n');
            sb.append(ln);
        }
        return (sb.length() == 0) ? null : sb.toString();
    }

    /**
     * One nested tooltip row as plain text, or {@code null} for a row that is not one.
     *
     * <p>(102.6) Each row is read at its <b>source</b> — the string that tip was written — rather than at
     * {@code str.text}, the string it drew: printing the contents is itself what builds the tip, so a
     * {@code tooltip} entry naming one of these rows would otherwise come straight back out of this verb.
     * A {@link ItemInfo.Name} the caller handed a rendered {@link Text} has no source, and its raster is
     * the only row there is.
     */
    private static String line(ItemInfo inf) {
        if(inf instanceof ItemInfo.Name)
            return CharApi.nameStr((ItemInfo.Name)inf);
        if(inf instanceof ItemInfo.AdHoc) {
            ItemInfo.AdHoc ah = (ItemInfo.AdHoc)inf;
            String src = ah.source();
            return (src != null) ? src : str(ah.str);
        }
        return null;
    }

    private static String str(Text t) {
        return (t == null) ? null : t.text;
    }

    /**
     * The <b>content's own</b> quality, or {@code null} when the block states none — the same read
     * {@code item:quality()} makes, over the nested tooltip instead of the item's own, so the water answers the
     * water's number and the bucket goes on answering the bucket's.
     */
    static Double quality(GItem cont) {
        ItemInfo.Contents b = block(cont);
        return (b == null) ? null : LuaItem.quality(b.sub);
    }

    /**
     * The fill meter's {@code {cur, max}}, or {@code nil} for a container that draws none.
     *
     * <p>Read <b>by type</b>, off the local copy of the meter's own published class ({@code ui/tt/level}, pinned
     * by version): the engine asks that class for {@code overlay()} alone — the bare fraction it paints over the
     * icon — so the two counts behind the bar are reachable nowhere else. The class is an overlay rather than a
     * tooltip row, so it sits in the item's <b>own</b> info list; a block that nests one instead is read too,
     * since where the server files it is its business and an empty answer would be silent.
     */
    static LuaValue level(GItem cont) {
        Level l = find(LuaItem.info(cont));
        if(l == null) {
            ItemInfo.Contents b = block(cont);
            l = (b == null) ? null : find(b.sub);
        }
        if(l == null)
            return LuaValue.NIL;
        LuaTable t = new LuaTable();
        t.set("cur", LuaValue.valueOf(l.cur));
        t.set("max", LuaValue.valueOf(l.max));
        return t;
    }

    private static Level find(List<ItemInfo> info) {
        if(info == null)
            return null;
        for(ItemInfo inf : info) {
            if(inf instanceof Level)
                return (Level)inf;
        }
        return null;
    }

    /** The documented {@code Contents} snapshot — every field optional, absent rather than empty. */
    static LuaValue snapshot(GItem cont) {
        if(cont == null)
            return LuaValue.NIL;
        LuaTable t = new LuaTable();
        String n = name(cont);
        if(n != null)
            t.set("name", LuaValue.valueOf(n));
        String s = text(cont);
        if(s != null)
            t.set("text", LuaValue.valueOf(s));
        Double q = quality(cont);
        if(q != null)
            t.set("quality", LuaValue.valueOf(q.doubleValue()));
        LuaValue l = level(cont);
        if(!l.isnil())
            t.set("level", l);
        return t;
    }
}
