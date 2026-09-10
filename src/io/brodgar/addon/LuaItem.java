package io.brodgar.addon;

import haven.Coord;
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
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.VarArgFunction;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An <b>Item object</b> — one thing the client draws, found through the icon drawing it: a cell in a
 * container ({@code widget:items()}), the cursor ({@code s:player():hand():item()}), a crafting recipe's
 * input or output slot, a food icon on a constipation row, or a depiction a resource ships its own widget
 * for. What the client knows about it is the same set for all of them: its resource, its display name, how
 * many it is, the arc over its icon, quality — and, for one the server actually put in a container, where
 * it is sitting.
 *
 * <p><b>Two kinds of thing, one type.</b> The engine models an item twice. The server pushes a
 * {@link GItem}, an addressable widget a container mints a {@link WItem} to draw. A <i>depiction</i> is no
 * {@code GItem} at all — a resource, an {@code sdt} and a tooltip list held by whoever draws it, with
 * nothing on the wire behind it. {@link ItemInfo.SpriteOwner} is the engine's own name for the family, and
 * it is what this handle is interned on, so a recipe slot and a backpack cell answer the same verbs. What a
 * depiction cannot answer, it answers <b>absence</b> for — {@code :cell()}, {@code :slots()},
 * {@code :handle()}, {@code :container()} and {@code :contents()} — and the four protected verbs refuse it
 * naming why: it is drawn, not held, and there is no message to send. See {@link LuaWidget#itemOf}, the one
 * decision behind every entry point.
 *
 * <p><b>The intern key is the depicted thing's own identity, and that is the whole point of this type.</b>
 * An item has no content id; the server addresses a {@code GItem} by a <i>widget id</i>, and a widget id is
 * handed back to the pool when the widget dies, so the server may give the same number to something else. A
 * handle keyed on that number would therefore not go stale — it would silently start naming a different
 * item, and a write made through it would land on whatever now holds the id. That is the failure this
 * whole entity exists to delete, so the handle holds the <b>object</b>: it is the item it was, for as long
 * as anyone holds it, and when the item moves or is consumed the object is simply gone ({@code :exists()}
 * false) rather than repointed. A protected verb resolves through this object and never through the number.
 *
 * <p><b>The handle holds the icon too, and the icon is what liveness means.</b> A depiction has no death of
 * its own to announce — it is a field of the widget drawing it — so {@code :exists()} is that widget's
 * reachability, and the widget is captured at the mint because it cannot be derived from the owner
 * afterwards ({@code fcontext(Widget.class)} on a recipe slot answers the crafting <i>window</i>). For a
 * {@code GItem} the two are one thing: a {@code GItem} is itself a widget in the tree, so it is its own
 * icon and the test is the one it has always had.
 *
 * <p><b>A stale Item still answers.</b> {@code Widget.destroy()} unlinks the item without clearing it, so
 * {@code :res()}, {@code :name()}, {@code :quantity()} and {@code :quality()} go on reading the thing it was —
 * which is what makes a stashed {@code ItemRemoved} payload worth holding. What a stale item has no
 * answer for is <i>where</i> it is: {@code :cell()} is nil, {@code :slots()} is empty and
 * {@code :handle()} is nil, because the id is exactly the thing that is no longer its.
 *
 * <p><b>Where it sits is two verbs, not one shape.</b> A backpack cell is a lattice coordinate and an
 * equipment slot is a name, so {@code :cell()} and {@code :slots()} say which you meant instead of one
 * verb whose return type depends on the container. {@code :slots()} is plural because an
 * {@link Equipory} draws one worn item in as many slots as it fills — the same item, twice on screen.
 *
 * <p><b>What it holds and what holds it are a pair</b> (064): {@code :contents()} hands back the
 * {@link LuaContents} object for a stack, a creel or a bucket ({@code nil} for an item holding nothing), and
 * {@code :container()} is its exact inverse — the item this one sits inside, {@code nil} for one sitting in a
 * container widget. A contained item is <i>not</i> in that container's {@code widget:items()}: a stack is one
 * item there because it is one cell on screen, and the caller recurses through {@code :contents()} and picks
 * its own depth.
 *
 * <p><b>What you can do TO an item is on the item</b> (048.3): {@code :use(mods)} activates it (the
 * {@code iact} gesture — eat, open, light), {@code :take()} lifts it onto the cursor or unequips it, and
 * {@code :drop(n)} / {@code :transfer(n)} move it, {@code n} defaulting to the whole stack. All four are
 * <b>protected</b> by the per-addon {@code item.*} keys, all four hand the Item back so they chain,
 * and all four <b>refuse a stale one</b>: the handle is the item it was, so a verb through it can only reach
 * <i>that</i> item or nothing, and nothing is sent rather than a write landing on whatever took its place.
 *
 * <p><b>Only {@code :use} takes modifiers, and that is the wire talking, not a style choice.</b> Of the four
 * messages only {@code iact} carries a modifier field ({@code {cc, modflags}}); {@code take},
 * {@code drop} and {@code transfer} carry none, because in the client the modifier keys select the
 * <i>count</i> ({@link WItem#mousedown}: shift = transfer 1, ctrl = drop 1, …). So {@code n} <i>is</i> the
 * modifier for those three, and a {@code mods} parameter beside it would be a field with nowhere to go.
 *
 * <p><b>Quality is read off the item's own published tooltip code, by class name.</b> The engine has no
 * quality type: the number lives in a field of a class that ships inside a resource. Reading it by name
 * (rather than pinning a local copy of that class) cannot go wrong when the resource is revised — it can
 * only stop answering, which is what {@code nil} already means everywhere else here. {@code :durability()}
 * is read the same way and for the same reason: the two counts a worn item's tooltip prints live in fields
 * of another such class, and nothing in the engine reaches them either.
 */
public final class LuaItem {
    /** The thing this handle addresses: a {@link GItem} the server pushed, or a depiction somebody draws. */
    public final ItemInfo.SpriteOwner owner;
    /** The widget drawing it — what {@code :exists()} tests, and what retires this handle when it dies. */
    public final Widget icon;

    private LuaItem(ItemInfo.SpriteOwner owner, Widget icon) {
        this.owner = owner;
        this.icon = icon;
    }

    /** {@code tostring(item)}: {@code Item(<resname>)}. */
    public String toString() {
        String r = CharApi.itemResOf(owner);
        return "Item(" + ((r == null) ? "?" : r) + ")";
    }

    /**
     * <b>The widget an owner is drawn by</b> — {@code drawn} for a depiction, and the owner itself for one
     * that <i>is</i> a widget (a {@link GItem}, and a {@code .res} widget holding its own depiction).
     *
     * <p>One rule in one place, because two entry points ask it: the mint from a widget verb, which has the
     * icon in hand, and every {@code GItem} read, which has none and needs none.
     */
    static Widget iconOf(ItemInfo.SpriteOwner o, Widget drawn) {
        return (o instanceof Widget) ? (Widget)o : drawn;
    }

    /**
     * An interned Item object for {@code o}, minted from the widget {@code drawn} that draws it — what
     * {@code widget:item()} hands back, and the only door a depiction can enter through.
     */
    static LuaValue of(Addon owner, ItemInfo.SpriteOwner o, Widget drawn) {
        return owner.items.of(o, iconOf(o, drawn));
    }

    /**
     * An interned Item object for {@code o} where the caller has no icon to offer — every {@code GItem}
     * read ({@code widget:items()}, the cursor, an {@code ItemAdded} payload), which needs none because a
     * {@code GItem} is its own icon.
     *
     * <p>For a depiction this re-mints through the icon the cache's entry remembers, and answers
     * {@code nil} where there is no entry: a depiction nothing ever minted has no address to hand back.
     */
    static LuaValue of(Addon owner, ItemInfo.SpriteOwner o) {
        return of(owner, o, null);
    }

    /** The {@code LuaItem} behind a Lua value, or {@code null} for anything else. */
    static LuaItem resolve(LuaValue v) {
        if((v == null) || !v.isuserdata())
            return null;
        Object o = v.touserdata();
        return (o instanceof LuaItem) ? (LuaItem)o : null;
    }

    // ---- the per-addon intern cache + metatable ---------------------------------------------------

    /**
     * One addon's Item cache and metatable (its {@link Addon#items}), keyed by the identity of the thing
     * drawn — a {@link GItem}, or the depiction an icon holds. Holds its {@link Addon} because the four
     * protected verbs (048.3) turn on the <b>caller's</b> declared permission, and the metatable is where
     * the gate has to be closed — the same reason {@link LuaGob.Cache} holds one.
     *
     * <p><b>The key is the owner and the entry remembers the icon</b> (137.1). Two icons never draw one
     * depiction, but a {@code GItem} is drawn by as many {@link WItem}s as it fills slots, so keying on the
     * icon would make one worn item two objects and {@code ==} would stop being the identity test. What the
     * entry holds the icon <i>for</i> is the mint a caller has no icon for: the entry is the only record of
     * where a depiction is drawn, since nothing in the owner points back at its widget.
     */
    static final class Cache {
        private final Addon owner;
        // retired: Cache.retire ALONE (137.4) -- the key is STRONG, and the entry is the only record of which
        //   icon draws this owner, so it is kept until that icon dies and Addon.dropInternedHandles takes it.
        private final Map<ItemInfo.SpriteOwner, Ref> live = new IdentityHashMap<ItemInfo.SpriteOwner, Ref>();
        private final ReferenceQueue<LuaValue> dead = new ReferenceQueue<LuaValue>();
        private LuaValue mt;

        Cache(Addon owner) {
            this.owner = owner;
        }

        /**
         * The interned Item for {@code o}, minted from {@code icon} where there is nothing to hand back yet.
         * A {@code null} icon and no entry is {@code NIL} — there is no third place to look.
         */
        synchronized LuaValue of(ItemInfo.SpriteOwner o, Widget icon) {
            drain();
            if(o == null)
                return LuaValue.NIL;
            Ref r = live.get(o);
            if(r != null) {
                LuaValue v = r.get();
                if(v != null)
                    return v;
                live.remove(o);
                if(icon == null)
                    icon = r.icon;              // Lua let the handle go: re-mint through the icon it named,
                                                //   which is the whole reason the entry outlives the handle
            }
            if(icon == null)
                return LuaValue.NIL;
            LuaValue v = LuaValue.userdataOf(new LuaItem(o, icon), meta());
            live.put(o, new Ref(v, o, icon, dead));
            return v;
        }

        /**
         * Take what the collector enqueued, and <b>leave the map alone</b> (137.4). A handle Lua released is
         * not a depiction that stopped being drawn: the entry is the only record of which icon draws this
         * owner, and it is exactly the record a fire needs — {@link AddonManager#fireItem} has an owner and
         * no icon, so unmapping here handed a {@code Changed} handler {@code nil} for the very depiction it
         * had subscribed on. The map's bound is {@link #retire}, at the icon's own disposal, which reaches
         * every kind of icon there is.
         *
         * <p><b>The poll is still worth making</b>: an unpolled {@link ReferenceQueue} holds every enqueued
         * {@link Ref} strongly and a {@code Ref} holds the owner and the icon, so this is what lets a retired
         * entry actually go. Clearing is the whole of the work.
         */
        private void drain() {
            Reference<? extends LuaValue> r;
            while((r = dead.poll()) != null)
                r.clear();
        }

        /**
         * <b>The icon died, so its entry goes</b> (128.5) — an inventory that closes DESTROYS the items
         * inside it and a recipe change destroys every slot widget, so this is the only seam that reaches
         * one. It is also the <b>only</b> thing that unmaps an entry (137.4): the key is STRONG and
         * {@link #drain} unmaps nothing, so without this the map pins the depiction and its icon for the
         * session. It reaches every kind of icon, which is what lets the entry outlive the handle — see
         * {@link Addon#dropInternedHandles}, its only caller, and {@code AddonManager.drainDisposedWidgets},
         * which resolves the owner through {@link LuaWidget#itemOf} and so calls it for a depiction too.
         *
         * <p>{@code synchronized}, which is the monitor {@link #of} takes — the drain runs on the step and a
         * mint runs wherever Lua ran. A handle Lua is still holding goes on answering: what is dropped is the
         * cache's claim on something that no longer exists, not the object an author stashed.
         */
        synchronized void retire(ItemInfo.SpriteOwner o) {
            if(o != null)
                live.remove(o);
        }

        private LuaValue meta() {
            if(mt == null)
                mt = buildMeta(owner);
            return mt;
        }
    }

    private static final class Ref extends WeakReference<LuaValue> {
        final ItemInfo.SpriteOwner key;
        /**
         * The widget that minted it, held <b>strongly</b> and retired with the entry. A depiction is a field
         * of its icon and reaches it through nothing, so the entry is where the pair is kept — and the pin
         * is bounded by {@link Cache#retire}, which the icon's own disposal calls.
         */
        final Widget icon;

        Ref(LuaValue v, ItemInfo.SpriteOwner key, Widget icon, ReferenceQueue<LuaValue> q) {
            super(v, q);
            this.key = key;
            this.icon = icon;
        }
    }

    // ---- the Item metatable -------------------------------------------------------------------------

    private static LuaValue buildMeta(final Addon owner) {
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, Refusal.closedIndex("item", methods(owner),
            "an item",
            ":use(), :take(), :drop() and :transfer() act; everything else reads"));
        mt.set("__name", LuaValue.valueOf("Item"));
        mt.set("__tostring", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaItem h = resolve(self);
                return LuaValue.valueOf((h == null) ? "Item(?)" : h.toString());
            }
        });
        return mt;
    }

    private static LuaTable methods(final Addon owner) {
        LuaTable m = new LuaTable();
        // res() — the item's resource name, its stable identity, or nil while it resolves.
        m.set("res", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                String r = CharApi.itemResOf(handle(self, "res").owner);
                return (r == null) ? LuaValue.NIL : LuaValue.valueOf(r);
            }
        });
        // name() — the display name, or nil until the item's tooltip info lands.
        m.set("name", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                String n = CharApi.itemNameOf(handle(self, "name").owner);
                return (n == null) ? LuaValue.NIL : LuaValue.valueOf(n);
            }
        });
        // quantity() — HOW MANY THIS ONE ITEM IS (064.4): the number the icon shows, for a counted item
        // (42 seeds of Hemp) and for a stack alike, and nil for one that shows none. On a stack it equals
        // #item:contents():items(), because there the same number IS how many are inside.
        m.set("quantity", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Integer n = quantity(handle(self, "quantity").owner);
                return (n == null) ? LuaValue.NIL : LuaValue.valueOf(n.intValue());
            }
        });
        // progress() — THE ARC the client paints over the icon (064.4), 0..1, or nil for an item painting
        // none. A fraction with no units: WItem.draw paints it as a wedge and the client cannot say "132 of
        // 150" there, only how far round. The two absolute counts are item:durability(), which is other data.
        m.set("progress", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Double p = progress(handle(self, "progress").owner);
                return (p == null) ? LuaValue.NIL : LuaValue.valueOf(p.doubleValue());
            }
        });
        // durability() — THE TWO COUNTS the item's tooltip prints (064.5), {cur, max}, or nil for an item that
        // prints none. NOT the arc above: :progress() is a fraction with no units, this is two absolute numbers,
        // and neither converts into the other — an item may answer both, and each is read on its own.
        m.set("durability", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return durability(handle(self, "durability").owner);
            }
        });
        // quality() — the number the tooltip shows, or nil while the item's info resolves (and for the
        // things that have no quality at all).
        m.set("quality", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Double q = quality(handle(self, "quality").owner);
                return (q == null) ? LuaValue.NIL : LuaValue.valueOf(q.doubleValue());
            }
        });
        // contents() — WHAT THIS ITEM HOLDS (064.1): a live Contents object, or nil for an item holding
        // nothing. One type for both insides — a stack and a creel carry real items, a bucket carries a line
        // its tooltip states — because the client cannot tell the two apart and a guess would be confident and
        // wrong. Interned on this item, so two reads are ==; see LuaContents.
        m.set("contents", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                GItem it = held(handle(self, "contents"));
                return ((it != null) && LuaContents.holds(it)) ? LuaContents.of(owner, it) : LuaValue.NIL;
            }
        });
        // container() — the Item this one sits INSIDE, or nil for one sitting in a container widget. The exact
        // inverse of :contents(): a:contents():items() holds b if and only if b:container() is a, and it chains
        // (a dandelion in a stack in a creel answers the stack, and the stack answers the creel). A WHERE read,
        // so like :cell(), :slots() and :handle() it answers nil on a stale item AND on a depiction, which is
        // drawn rather than put anywhere (137.1).
        m.set("container", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                GItem c = container(liveHeld(handle(self, "container")));
                return (c == null) ? LuaValue.NIL : of(owner, c);
            }
        });
        // cell() — the {x, y} grid cell in the container holding it, or nil (worn, on the cursor, gone, or
        // inside another item — where an item inside a stack is, is :container()).
        m.set("cell", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return cell(liveHeld(handle(self, "cell")));
            }
        });
        // slots() — the equipment slots this item fills, by name; empty for anything not worn.
        m.set("slots", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return slots(liveHeld(handle(self, "slots")));
            }
        });
        // handle() — the server widget id, the same number widget:id() answers. nil once the item is gone: it is
        // no longer its, and the server may already have given it to something else.
        m.set("handle", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                int id = wdgid(liveHeld(handle(self, "handle")));
                return (id < 0) ? LuaValue.NIL : LuaValue.valueOf(id);
            }
        });
        // exists() — is the icon drawing this still in the tree? For a GItem that is the item's own
        // reachability, which is what it has always been; for a depiction it is the widget holding it, since a
        // depiction has no death of its own to announce (137.1).
        m.set("exists", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(live(handle(self, "exists")) != null);
            }
        });
        // info() — the one SNAPSHOT escape hatch.
        m.set("info", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return snapshot(handle(self, "info").owner);
            }
        });
        // on(key, fn) — the item's own door (104). ONE key: `Changed`, "what this item says about itself is
        // not what it said". An item arrives before its tooltip does and the code that reads a quality out of
        // that tooltip ships inside a resource that may still be loading when it lands, so every read here goes
        // from nil to an answer at a moment no other event names. Both waits end at ONE seam rather than two,
        // because the client cannot describe the item until both have ended: AddonManager.onItemInfo, where
        // GItem.info() succeeds in building the list.
        m.set("on", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaItem h = handle(a.arg1(), "on");
                LuaValue keyArg = Args.required(a, 2, "item:on", "key");
                LuaValue fnArg = Args.required(a, 3, "item:on", "fn");
                if(!keyArg.isstring() || !fnArg.isfunction())
                    throw new LuaError("item:on(key, fn) expects (string, function)");
                String key = keyArg.tojstring();
                String moved = Refusal.eventKey("item", key);
                if(moved != null)
                    throw new LuaError(moved);
                if(!CHANGED.equals(key))
                    throw new LuaError("item:on(key, fn): an item has no event '" + key + "' — it has: "
                        + CHANGED + " (its tooltip resolved, or the server revised it). What an item does and"
                        + " where it sits are the container's events, widget:on(\"ItemAdded\"/\"ItemRemoved\")");
                // A STALE item is a legal receiver and the subscription is inert: what it was is all it will
                // ever say, so there is nothing left to change and nothing to fire. Refusing here would make
                // an addon guard a call that has no wrong outcome. It is registered and ended in one breath
                // rather than skipped, because the removal seam that ends a live item's subscriptions has
                // ALREADY run for this one -- leaving the handler in place would leave it there for good, and
                // a handler closing over its own item is what a weak map cannot collect (Addon#dropItemSubs).
                LuaValue sub = owner.itemSubs(h.owner).on(key, fnArg);
                AddonManager.anyItemSubs = true;   // audit2 B15: somebody is watching now — see onItemInfo
                if(live(h) == null)
                    owner.dropItemSubs(h.owner);
                return sub;
            }
        });
        // -- the four PROTECTED verbs (048.3) ----------------------------------------------------------
        // What you can do TO an item, on the item — the old hafen.act():item(item, verb, n)'s five verb
        // strings become four named verbs plus s:player():hand():use(item) (048.2). Each sends exactly
        // the GItem.wdgmsg the matching click sends (WItem.mousedown), so the client stays server-
        // authoritative, and each hands the Item back so a run of verbs chains.
        //   The gate runs FIRST — before the argument check and before the live item is looked up (D-213),
        // so an addon that never declared "item.use" is told THAT rather than "this item is gone".

        // use([mods]) — the "iact" gesture: activate it (eat, open, light), what a right-click on the item
        // does. mods optional (0 default; Shift=1 Ctrl=2 Alt=4). iact is one of the two item messages that
        // carries a modifier field at all, and the verb this replaces hardcoded it to 0.
        m.set("use", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = a.arg1();
                AddonManager.requirePermission(AddonManager.current(), Permission.ITEM_USE);
                int mods = Args.optint(a, 2, "item:use", "mods", null, 0);
                GItem g = target(self, "use");
                Wire.send(owner, AddonManager.userOf(g), "item:use", g, "iact", iactArgs(mods));
                return self;
            }
        });
        // take() — lift it onto the cursor (from a container), or unequip a worn one. NO arguments: the
        // message carries a grab point and nothing else, so there is no count and no modifier to state.
        m.set("take", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = a.arg1();
                AddonManager.requirePermission(AddonManager.current(), Permission.ITEM_TAKE);
                noArgs(a, "item:take");
                GItem g = target(self, "take");
                Wire.send(owner, AddonManager.userOf(g), "item:take", g, "take", takeArgs());
                return self;
            }
        });
        // drop([n]) — drop it on the ground; n = how many of the stack, -1 (the default) being all of it.
        m.set("drop", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = a.arg1();
                AddonManager.requirePermission(AddonManager.current(), Permission.ITEM_DROP);
                int n = Args.optint(a, 2, "item:drop", "n", null, -1);
                GItem g = target(self, "drop");
                Wire.send(owner, AddonManager.userOf(g), "item:drop", g, "drop", countArgs(n));
                return self;
            }
        });
        // transfer([n]) — move it to the linked container (an open container, or your inventory); n as for
        // drop. This is the verb the modifier keys spell as shift / shift+ctrl on a real click.
        m.set("transfer", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = a.arg1();
                AddonManager.requirePermission(AddonManager.current(), Permission.ITEM_TRANSFER);
                int n = Args.optint(a, 2, "item:transfer", "n", null, -1);
                GItem g = target(self, "transfer");
                Wire.send(owner, AddonManager.userOf(g), "item:transfer", g, "transfer", countArgs(n));
                return self;
            }
        });
        return m;
    }

    // ---- the protected verbs' shared plumbing --------------------------------------------------------

    /**
     * The live {@link GItem} a protected verb acts on, or the guiding refusal that <b>nothing was sent</b>.
     *
     * <p>This is the whole reason the handle holds the item widget rather than its server id: a stale
     * handle can only resolve to <i>that item</i> or to nothing, so the failure is a message the caller
     * reads instead of a write landing on whatever inherited the recycled number.
     *
     * <p><b>A depiction is refused first</b> (137.1), before the stale test, because the two are different
     * facts and only one of them is worth retrying: an item that left may come back in the next read, while
     * a recipe slot will never have a message to send however long you wait.
     */
    private static GItem target(LuaValue self, String verb) {
        LuaItem h = handle(self, verb);
        if(!(h.owner instanceof GItem))
            throw new LuaError(drawnNotHeld("item:" + verb));
        GItem g = liveHeld(h);
        if(g == null)
            throw new LuaError("item:" + verb + ": this item is gone — it was moved, used or consumed, or"
                + " you are not in the world (item:exists() is false). Nothing was sent: an item that has"
                + " left is not the item that took its place. Re-read the container and retry.");
        return g;
    }

    /**
     * <b>The refusal a depiction gets from any verb that would send something</b> — written inside the verb
     * rather than keyed in {@link Refusal}, because nothing was renamed: the spelling is the one it always
     * was and what changed is which receivers it accepts, which a name-keyed table has nothing to match on.
     */
    static String drawnNotHeld(String verb) {
        return verb + ": this item is drawn, not held — it is a recipe slot, a listing or a price the client"
            + " paints from a resource, and the server has no widget behind it to address. Nothing was sent,"
            + " and there is nothing to retry: read it (:res(), :name(), :quality()) or act on the item in a"
            + " container instead. item:handle() is nil for exactly this reason.";
    }

    /** Refuse an argument to a verb that has none, naming what the caller probably meant instead. */
    private static void noArgs(Varargs a, String verb) {
        if(Args.passed(a, 2))
            throw new LuaError(verb + "() takes no arguments — a take lifts the whole thing, the count is"
                + " item:drop(n) / item:transfer(n), and this message carries no modifier field: on a real"
                + " click the modifier keys select the COUNT, which n states directly.");
    }

    // ---- the four wire shapes, pure so they are testable without a session ---------------------------
    // The coord every one of them carries is the intra-item GRAB POINT; Coord.z (the item's own corner) is a
    // faithful, deterministic substitute for a programmatic action, exactly as it was under hafen.act():item.

    /** The {@link GItem} {@code "take"} args ({@code {grab}}) — a bare grab, no count, no modifiers. */
    static Object[] takeArgs() {
        return new Object[] {Coord.z};
    }

    /** The {@code "drop"} / {@code "transfer"} args ({@code {grab, n}}); {@code n == -1} is the whole stack. */
    static Object[] countArgs(int n) {
        return new Object[] {Coord.z, n};
    }

    /** The {@code "iact"} args ({@code {grab, mods}}) — the one of the four that carries modifiers. */
    static Object[] iactArgs(int mods) {
        return new Object[] {Coord.z, mods};
    }

    private static LuaItem handle(LuaValue self, String method) {
        LuaItem h = resolve(self);
        if(h == null)
            throw new LuaError("item:" + method + "() — use a COLON call on an Item object"
                + " (widget:items()[i], or session:player():hand():item())");
        return h;
    }

    // ---- liveness ------------------------------------------------------------------------------------

    /**
     * The live <b>icon</b> behind a handle, or {@code null} once it is gone. Tree reachability is the test,
     * exactly as it is for a widget: the server destroying an item unlinks it, a recipe change destroys
     * every slot widget, and what is not under the root is not being drawn anywhere. <b>The tree is the
     * icon's own</b> ({@code icon.ui}), so an item in a container one character has open is live while the
     * player looks at another. A missing {@link UI} answers {@code null} without clearing anything —
     * unresolvable now is not proven dead.
     */
    static Widget live(LuaItem h) {
        return (h == null) ? null : live(h.icon);
    }

    /** As {@link #live(LuaItem)}, on the icon itself. */
    static Widget live(Widget icon) {
        if(icon == null)
            return null;
        UI u = icon.ui;
        if((u == null) || (u.root == null))
            return null;
        return icon.hasparent(u.root) ? icon : null;
    }

    /**
     * The {@link GItem} behind a handle, live or stale, and {@code null} for a depiction — the read every
     * verb that touches a {@code GItem} member starts from, so "this is not a thing the server pushed" is
     * answered in one place and each of them answers its own absence rather than reaching a field that is
     * not there.
     */
    static GItem held(LuaItem h) {
        return ((h != null) && (h.owner instanceof GItem)) ? (GItem)h.owner : null;
    }

    /** {@link #held}, and only while its icon is still in the tree — the WHERE reads and the four verbs. */
    static GItem liveHeld(LuaItem h) {
        return (live(h) == null) ? null : held(h);
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
        if(p == null)
            return out;
        synchronized(LuaWidget.monitor(p)) {
            for(WItem w : p.children(WItem.class)) {
                if(w.item == it)
                    out.add(w);
            }
        }
        return out;
    }

    /**
     * The {@link GItem} this one sits <b>inside</b> ({@code item:container()}), or {@code null} for an item
     * sitting in a container widget of its own.
     *
     * <p>The engine already carries the link, one hop above the item: a contents widget is added to a
     * {@code GItem.ContentsWindow} whose {@code cont} is the item that pushed it. So the walk is upward from the
     * item until that window is met — and climbing on from there is what makes the relation chain through a
     * container inside a container. The window hangs off the {@code GameUI} rather than off the holding item
     * ({@code GItem.contparent}), so an item's <i>own</i> contents window is never above it and this can only
     * ever answer the thing it is in.
     */
    static GItem container(GItem it) {
        if(it == null)
            return null;
        synchronized(LuaWidget.monitor(it)) {
            for(Widget p = it.parent; p != null; p = p.parent) {
                if(p instanceof GItem.ContentsWindow)
                    return ((GItem.ContentsWindow)p).cont;
            }
        }
        return null;
    }

    /**
     * {@code item:cell()} — the inventory grid cell, or nil for a worn / cursor / departed item, <b>and for one
     * inside another item</b>: the grid a stack's contents are drawn in is not a container the player has open,
     * so a cell in it would read exactly like a cell of the backpack it is standing in. Where a contained item
     * is, is {@code :container()}, and that is the one answer that holds whatever widget the server pushed as
     * the contents.
     */
    private static LuaValue cell(GItem it) {
        if((it == null) || !(it.parent instanceof Inventory) || (container(it) != null))
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

    /**
     * The item's one event key — <b>a subject and an edge</b>: what this item says about itself is not what it
     * said. One key rather than several because the client is never told <i>which</i> part of a tooltip a
     * revision touched: the server resends the whole thing, and the resource that renders it either has loaded
     * or has not. A key per field would be a promise the wire cannot keep.
     */
    static final String CHANGED = "Changed";

    /** The class every quality tooltip derives from, wherever it was loaded from. */
    private static final String QBUFF = "haven.res.ui.tt.q.qbuff.QBuff";
    /** Its plain-quality subclass — the one to prefer when an item publishes several. */
    private static final String QUALITY = "haven.res.ui.tt.q.quality.Quality";
    /** Each info class seen → its {@code q} field, or {@code null} for "this one is not a quality". */
    private static final Map<Class<?>, Field> qfields = new HashMap<Class<?>, Field>();
    /** The class the wear row ships as, inside its own resource ({@code ui/tt/wear}). */
    private static final String WEAR = "haven.res.ui.tt.wear.Wear";
    /** Each info class seen → its two count fields, or {@code null} for "this one is not a wear row". */
    private static final Map<Class<?>, Field[]> wfields = new HashMap<Class<?>, Field[]>();

    /**
     * The item's own tooltip info, or {@code null} while it is still resolving: {@code info()} throws a bare
     * {@code Loading} while the resource streams, and that is not resolvable here. Every read that goes
     * through the tooltip comes through this one door, and answers {@code nil} rather than a half-built value.
     *
     * <p>Typed on {@link ItemInfo.Owner} because that is where {@code info()} is declared: a depiction builds
     * its rows through the very same {@code ItemInfo.buildinfo}, so a name and a quality read off a recipe
     * slot the way they read off a backpack cell, with no branch here at all.
     */
    static List<ItemInfo> info(ItemInfo.Owner it) {
        if(it == null)
            return null;
        try {
            return it.info();
        } catch(RuntimeException e) {   // info() still Loading
            return null;
        }
    }

    /**
     * The item's quality, or {@code null} when it has none and while its info is still resolving.
     *
     * <p>Read <b>by class name</b>: quality is published by code that ships inside a resource
     * ({@code ui/tt/q/quality}, whose class extends {@code ui/tt/q/qbuff}'s), so there is no type here to
     * compare against. Naming the class instead of pinning a local copy of it means a revised resource
     * makes this answer {@code nil}, never something wrong — the same trade {@code gob:kin()} makes.
     */
    static Double quality(ItemInfo.Owner it) {
        return quality(info(it));
    }

    /**
     * The same read over <b>one tooltip list</b>, whichever list that is — which is what lets a container's
     * <b>content</b> answer its own quality: {@code contents:quality()} runs this over the nested tooltip a
     * contents block carries ({@code ItemInfo.Contents.sub}), where the water in a bucket publishes its
     * quality exactly as an item publishes its own.
     */
    static Double quality(List<ItemInfo> info) {
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

    // ---- the two numbers an item wears on its icon, each folded from its two sources ---------------------

    /**
     * {@code item:quantity()} — how many this one item <b>is</b>, or {@code null} for one showing no number
     * (and while its info is still resolving).
     *
     * <p><b>Two sources, folded in one read</b>, which is the whole of why this verb exists: a server message
     * writes {@link GItem#num}, and the item's own tooltip publishes a {@link GItem.NumberInfo} — and the icon's
     * number is drawn from the <i>second</i> alone ({@code WItem.draw} paints {@code itemols}, the
     * {@code OverlayInfo}s of {@code info()}, and reads {@code num} nowhere). Reading the field by itself
     * therefore answers {@code nil} on the very items that visibly show a number. So the field answers when the
     * server set it, and the published number answers otherwise, which makes the contract checkable by eye:
     * <b>if you can see it on the icon, this answers it.</b>
     *
     * <p><b>A depiction has only the second half</b> (137.1). The field is a {@code GItem}'s, written by a
     * server message; nothing writes one for a recipe slot or a listing, so the published number is the whole
     * of what such an icon can say — which is also the whole of what it draws.
     *
     * <p><b>What the published number counts is the implementor's, and the engine has no finer type</b>:
     * {@code GItem.Amount} renders an amount, and the gilding tooltip renders how many gildings a piece of gear
     * carries, {@code 0} included — both through the one {@code NumberInfo}. So this answers the number
     * <i>drawn</i> and does not assert what it means, for the same reason {@code :contents()} covers a stack and
     * a bucket with one type: guessing between them would answer confidently and wrongly.
     */
    static Integer quantity(ItemInfo.Owner it) {
        if(it == null)
            return null;
        if((it instanceof GItem) && (((GItem)it).num != -1))
            return Integer.valueOf(((GItem)it).num);
        List<ItemInfo> info = info(it);
        if(info == null)
            return null;
        GItem.NumberInfo n = ItemInfo.find(GItem.NumberInfo.class, info);
        if(n == null)
            return null;
        try {
            return Integer.valueOf(n.itemnum());
        } catch(RuntimeException e) {   // published code, still resolving what it counts
            return null;
        }
    }

    /**
     * {@code item:progress()} — the arc painted over the icon as a {@code 0..1} fraction, or {@code null} for an
     * item painting none.
     *
     * <p><b>{@code WItem.draw} is the authority and this mirrors it exactly</b>, both halves and their order:
     * the field {@link GItem#meter} when the server set it, and the item's published {@link GItem.MeterInfo}
     * otherwise. The field is a percentage on the wire, so it is divided here — the one conversion in the fold,
     * and the reason this can never answer a number above 1, which reading that field raw would give.
     *
     * <p><b>A depiction has only the second half</b>, for the reason {@link #quantity} gives: the field is a
     * {@code GItem}'s and nothing writes one for an icon the client paints out of a resource.
     *
     * <p>What the arc <i>measures</i> is the server's business: the client paints a wedge and cannot say
     * <i>132 of 150</i> there. The two absolute counts an item's tooltip may print are {@code :durability()},
     * and neither number converts into the other.
     */
    static Double progress(ItemInfo.Owner it) {
        if(it == null)
            return null;
        if((it instanceof GItem) && (((GItem)it).meter > 0))
            return Double.valueOf(((GItem)it).meter / 100.0);
        List<ItemInfo> info = info(it);
        if(info == null)
            return null;
        GItem.MeterInfo m = ItemInfo.find(GItem.MeterInfo.class, info);
        if(m == null)
            return null;
        try {
            double p = m.meter();
            return (p > 0) ? Double.valueOf(p) : null;
        } catch(RuntimeException e) {   // published code, still resolving what it measures
            return null;
        }
    }

    /**
     * {@code item:durability()} — the two counts the item's wear row prints, {@code {cur, max}}, or {@code nil}
     * for an item that prints none (and while its info is still resolving).
     *
     * <p><b>Read by class name</b>, the technique {@link #quality} already uses: the row is rendered by code
     * that ships inside a resource ({@code ui/tt/wear}), so there is no type here to compare against, and
     * naming the class instead of pinning a local copy of it means a revised resource makes this answer
     * {@code nil} rather than something wrong. The per-class field lookup is cached exactly as
     * {@link #qfield} caches its own, so the reflection is paid once per info class ever seen.
     *
     * <p><b>The counts are handed over as the tooltip prints them</b>, {@code cur} first: the resource renders
     * them as one row and colours it once {@code cur} reaches {@code max}, and what they measure beyond that
     * is the server's. Nothing is derived, converted or clamped here — {@code :progress()} is the other number
     * an item may wear, it is a fraction with no units, and an item may answer both without either being a
     * view of the other.
     */
    static LuaValue durability(ItemInfo.Owner it) {
        List<ItemInfo> info = info(it);
        if(info == null)
            return LuaValue.NIL;
        for(ItemInfo inf : info) {
            Field[] f = wfield(inf.getClass());
            if(f == null)
                continue;
            try {
                LuaTable t = new LuaTable();
                t.set("cur", LuaValue.valueOf(f[0].getInt(inf)));
                t.set("max", LuaValue.valueOf(f[1].getInt(inf)));
                return t;
            } catch(Exception e) {
                return LuaValue.NIL;
            }
        }
        return LuaValue.NIL;
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

    /** The two count fields {@code c} inherits from the wear-tooltip class, {@code cur} first, or {@code null}. */
    private static synchronized Field[] wfield(Class<?> c) {
        if(wfields.containsKey(c))
            return wfields.get(c);
        Field[] f = null;
        for(Class<?> k = c; k != null; k = k.getSuperclass()) {
            if(k.getName().equals(WEAR)) {
                try {
                    Field cur = k.getDeclaredField("d"), max = k.getDeclaredField("m");
                    cur.setAccessible(true);
                    max.setAccessible(true);
                    f = new Field[] {cur, max};
                } catch(Exception e) {
                    f = null;
                }
                break;
            }
        }
        wfields.put(c, f);
        return f;
    }

    // ---- the snapshot + the container read -------------------------------------------------------------

    /**
     * The documented {@code Item} snapshot — every field optional, absent rather than empty.
     *
     * <p>A depiction's is the same table with the halves it has no answer for simply absent, which is what
     * {@code optional} already means here: no {@code handle}, no {@code cell}, no {@code slots}, no
     * {@code contents}. That falls out of the reads rather than being branched on — {@link #held} answers
     * {@code null} and each of them answers its own absence.
     */
    static LuaValue snapshot(ItemInfo.SpriteOwner it) {
        if(it == null)
            return LuaValue.NIL;
        LuaTable t = new LuaTable();
        String res = CharApi.itemResOf(it);
        if(res != null)
            t.set("res", LuaValue.valueOf(res));
        String name = CharApi.itemNameOf(it);
        if(name != null)
            t.set("name", LuaValue.valueOf(name));
        Integer n = quantity(it);
        if(n != null)
            t.set("quantity", LuaValue.valueOf(n.intValue()));
        Double p = progress(it);
        if(p != null)
            t.set("progress", LuaValue.valueOf(p.doubleValue()));
        LuaValue d = durability(it);
        if(!d.isnil())
            t.set("durability", d);
        Double q = quality(it);
        if(q != null)
            t.set("quality", LuaValue.valueOf(q.doubleValue()));
        GItem g = (it instanceof GItem) ? (GItem)it : null;
        // What it holds, as that Contents' OWN snapshot — and there is deliberately no `container` beside it:
        // a snapshot holds no live objects, and a snapshot naming the item it sits in would nest snapshots of
        // bags without end. Where it is, is item:container(), on the live object.
        if((g != null) && LuaContents.holds(g))
            t.set("contents", LuaContents.snapshot(g));
        GItem l = ((g != null) && (live(g) != null)) ? g : null;
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
     * <b>Everything a widget draws, each thing once</b> ({@code widget:items()}), as one owner's array of Item
     * objects. The {@link LuaWidget#icons} walk is what finds them (deep, so a whole window answers for the
     * grid — or for the recipe slots — inside it), and the list is de-duplicated on <b>the thing drawn</b>:
     * an {@link Equipory} draws one worn item in every slot it fills, and two entries that are {@code ==}
     * would make {@code #items} a lie about how many things you have. Which slots one fills is read off the
     * item with {@code :slots()}.
     *
     * <p><b>The mint carries the icon</b> (137.2), because for a depiction that widget is the only address
     * there is — and the first icon in tree order is the one the entry keeps, which for a depiction is also
     * the only one, since nothing draws one twice. For a {@link GItem} the icon is discarded at the mint:
     * a {@code GItem} is its own icon.
     */
    static LuaValue list(Addon owner, Widget container) {
        LuaTable out = new LuaTable();
        Set<ItemInfo.SpriteOwner> seen =
            Collections.newSetFromMap(new IdentityHashMap<ItemInfo.SpriteOwner, Boolean>());
        int n = 0;
        for(Widget icon : LuaWidget.icons(container)) {
            ItemInfo.SpriteOwner o = LuaWidget.itemOf(icon);
            if((o != null) && seen.add(o))
                out.set(++n, of(owner, o, icon));
        }
        return out;
    }

    /**
     * The <b>items a container holds</b>, each one once — the {@link GItem} half of {@link #list}, and never
     * handed to Lua. A depiction is deliberately absent: this is the set the container LIFECYCLE is about
     * ({@link #deepItems}, {@code ItemAdded}/{@code ItemRemoved}, {@code EquipChanged}), and every one of
     * those asks what the server put somewhere. A recipe slot is drawn, not held: it never entered a
     * container, so it can never enter or leave one.
     */
    static List<GItem> held(Widget container) {
        List<GItem> out = new ArrayList<GItem>();
        Set<GItem> seen = Collections.newSetFromMap(new IdentityHashMap<GItem, Boolean>());
        for(Widget icon : LuaWidget.icons(container)) {
            ItemInfo.SpriteOwner o = LuaWidget.itemOf(icon);
            if((o instanceof GItem) && seen.add((GItem)o))
                out.add((GItem)o);
        }
        return out;
    }

    /**
     * Every item {@code container} holds, INCLUDING what sits inside a stack or a creel it holds, at any
     * depth (064.3) — unlike {@link #held}, which is one entry per cell and never a stack's own contents
     * flattened in. This is the set {@link WidgetSubs}'s {@code ItemAdded}/{@code ItemRemoved} diff runs
     * against: the events answer what ENTERED this container, at any depth, while {@code widget:items()}
     * answers what it DRAWS. Never handed to Lua.
     *
     * <p><b>The value is each item's own immediate container</b> ({@code null} for a top-level one) — read
     * HERE, while every item in the map is still live, rather than later through {@link #container}, which
     * climbs {@code Widget.parent} and answers {@code null} on anything already unlinked. {@link
     * WidgetSubs#refreshItems} needs exactly that for a REMOVED item: by the time it is missing from this
     * map, its own parent chain is already gone, so the only trustworthy record of what it sat inside is one
     * taken before that happened.
     */
    static Map<GItem, GItem> deepItems(Widget container) {
        Map<GItem, GItem> out = new LinkedHashMap<GItem, GItem>();
        for(GItem it : held(container))
            collectDeep(it, null, out);
        return out;
    }

    /** One item (under {@code parent}, {@code null} at the top) and — recursively — everything a stack or a
     *  creel it holds carries, into {@code out}. */
    private static void collectDeep(GItem it, GItem parent, Map<GItem, GItem> out) {
        if(out.containsKey(it))
            return;
        out.put(it, parent);
        Widget w = LuaContents.widget(it);
        if(w == null)
            return;
        List<GItem> kids;
        synchronized(LuaWidget.monitor(w)) { kids = new ArrayList<GItem>(w.children(GItem.class)); }
        for(GItem k : kids)
            collectDeep(k, it, out);
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
