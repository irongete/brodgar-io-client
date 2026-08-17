package io.brodgar.addon;

import haven.FightWnd;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.OneArgFunction;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * A <b>DeckCard object</b> — one hotkey slot of the combat school you have loaded ({@code hafen.fight():deck()}).
 * A card is a <i>place in the layout</i>: the slot index the write path uses and the hotkey label the window
 * paints under it, plus whichever maneuver is sitting there right now.
 *
 * <p><b>The intern key is the slot</b> (§2.4), not the maneuver in it. The slot is what the layout is made of
 * and what the server addresses — loading another school rewrites every slot's contents while the slots
 * themselves stay put — so a stashed card follows the hotkey rather than following a maneuver that has been
 * dealt somewhere else.
 *
 * <p><b>{@code :exists()} means the slot is filled.</b> An emptied hotkey keeps answering {@code :slot()} and
 * {@code :key()} — they are properties of the place — while {@code :res()}, {@code :name()} and
 * {@code :maneuver()} go {@code nil}. {@code hafen.fight():deck()} lists only the filled slots, so an empty one
 * is reachable solely through a card you were already holding.
 *
 * <p>The deck is a <b>plain array</b> rather than a collection (§2.3): it is a layout, addressed by nothing but
 * its own order, and there is nothing to search it by that {@code hafen.fight():maneuver()} does not already
 * answer.
 */
public final class LuaDeckCard {
    /** The raw 0-based deck index — the whole state of a handle, and what the write path takes. */
    public final int slot;

    private LuaDeckCard(int slot) {
        this.slot = slot;
    }

    /** {@code tostring(card)}: {@code DeckCard(<slot>)}. */
    public String toString() {
        return "DeckCard(" + slot + ")";
    }

    /** An interned DeckCard object for deck slot {@code slot} in {@code owner}'s env. */
    static LuaValue of(Addon owner, int slot) {
        return owner.deckCards.of(slot);
    }

    /** The {@code LuaDeckCard} behind a Lua value, or {@code null} for anything else. */
    static LuaDeckCard resolve(LuaValue v) {
        if((v == null) || !v.isuserdata())
            return null;
        Object o = v.touserdata();
        return (o instanceof LuaDeckCard) ? (LuaDeckCard)o : null;
    }

    // ---- the per-addon intern cache + metatable ----------------------------------------------------

    /** One addon's DeckCard cache and metatable (its {@link Addon#deckCards}), keyed by deck slot. */
    static final class Cache {
        private final Addon owner;
        private final Map<Integer, Ref> live = new HashMap<Integer, Ref>();
        private final ReferenceQueue<LuaValue> dead = new ReferenceQueue<LuaValue>();
        private LuaValue mt;

        Cache(Addon owner) {
            this.owner = owner;
        }

        synchronized LuaValue of(int slot) {
            drain();
            Integer key = Integer.valueOf(slot);
            Ref r = live.get(key);
            if(r != null) {
                LuaValue v = r.get();
                if(v != null)
                    return v;
                live.remove(key);
            }
            LuaValue v = LuaValue.userdataOf(new LuaDeckCard(slot), meta());
            live.put(key, new Ref(v, key, dead));
            return v;
        }

        private void drain() {
            Reference<? extends LuaValue> r;
            while((r = dead.poll()) != null) {
                Ref cr = (Ref)r;
                if(live.get(cr.key) == cr)
                    live.remove(cr.key);
            }
        }

        private LuaValue meta() {
            if(mt == null)
                mt = buildMeta(owner);
            return mt;
        }
    }

    private static final class Ref extends WeakReference<LuaValue> {
        final Integer key;

        Ref(LuaValue v, Integer key, ReferenceQueue<LuaValue> q) {
            super(v, q);
            this.key = key;
        }
    }

    // ---- the DeckCard metatable ---------------------------------------------------------------------

    private static LuaValue buildMeta(final Addon owner) {
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, Retired.methodIndex("deckcard", methods(owner)));
        mt.set("__name", LuaValue.valueOf("DeckCard"));
        mt.set("__tostring", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaDeckCard h = resolve(self);
                return LuaValue.valueOf((h == null) ? "DeckCard(?)" : h.toString());
            }
        });
        return mt;
    }

    private static LuaTable methods(final Addon owner) {
        LuaTable m = new LuaTable();
        // slot() — the raw 0-based deck index, the same one the write path takes.
        m.set("slot", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(handle(self, "slot").slot);
            }
        });
        // key() — the hotkey label the window paints under this slot.
        m.set("key", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(keyLabel(handle(self, "key").slot));
            }
        });
        // maneuver() — the maneuver dealt into this slot, or nil while the slot is empty.
        m.set("maneuver", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                FightWnd.Action a = action(handle(self, "maneuver").slot);
                return (a == null) ? LuaValue.NIL : LuaManeuver.of(owner, a);
            }
        });
        // res() — the dealt maneuver's resource name, or nil (empty slot, or the resource is still resolving).
        m.set("res", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                FightWnd.Action a = action(handle(self, "res").slot);
                String r = (a == null) ? null : AddonManager.resIdent(a.res);
                return (r == null) ? LuaValue.NIL : LuaValue.valueOf(r);
            }
        });
        // name() — the dealt maneuver's display name, or nil.
        m.set("name", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                FightWnd.Action a = action(handle(self, "name").slot);
                String n = (a == null) ? null : AddonManager.resTipName(a.res, null);
                return (n == null) ? LuaValue.NIL : LuaValue.valueOf(n);
            }
        });
        // used() — how many copies of the dealt maneuver the current deck holds, or nil for an empty slot.
        m.set("used", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                FightWnd.Action a = action(handle(self, "used").slot);
                return (a == null) ? LuaValue.NIL : LuaValue.valueOf(a.u);
            }
        });
        // exists() — is this hotkey still holding a maneuver?
        m.set("exists", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(action(handle(self, "exists").slot) != null);
            }
        });
        // info() — the one SNAPSHOT escape hatch.
        m.set("info", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return snapshot(handle(self, "info").slot);
            }
        });
        return m;
    }

    private static LuaDeckCard handle(LuaValue self, String method) {
        LuaDeckCard h = resolve(self);
        if(h == null)
            throw new LuaError("card:" + method + "() — use a COLON call on a DeckCard object"
                + " (hafen.fight():deck()[i])");
        return h;
    }

    // ---- the reads ----------------------------------------------------------------------------------

    /** The current school's layout, copied under the UI monitor (its entries are reassigned there). */
    static FightWnd.Action[] order() {
        FightWnd fw = CharApi.fightwnd();
        if(fw == null)
            return new FightWnd.Action[0];
        synchronized(LuaWidget.monitor(fw)) {
            return Arrays.copyOf(fw.order, fw.order.length);
        }
    }

    /** The maneuver dealt into {@code slot}, or {@code null} for an empty or vanished slot. */
    private static FightWnd.Action action(int slot) {
        FightWnd.Action[] order = order();
        return ((slot < 0) || (slot >= order.length)) ? null : order[slot];
    }

    /** The hotkey label for a deck slot (the game's own table), or a 1-based fallback beyond it. */
    static String keyLabel(int slot) {
        String[] keys = FightWnd.keys;
        if((keys != null) && (slot >= 0) && (slot < keys.length) && (keys[slot] != null))
            return keys[slot];
        return String.valueOf(slot + 1);
    }

    /** The documented {@code DeckCard} snapshot; the maneuver half is absent while the slot is empty. */
    private static LuaValue snapshot(int slot) {
        LuaTable t = new LuaTable();
        t.set("slot", LuaValue.valueOf(slot));
        t.set("key", LuaValue.valueOf(keyLabel(slot)));
        FightWnd.Action a = action(slot);
        if(a != null) {
            String res = AddonManager.resIdent(a.res);
            if(res != null)
                t.set("res", LuaValue.valueOf(res));
            String nm = AddonManager.resTipName(a.res, null);
            if(nm != null)
                t.set("name", LuaValue.valueOf(nm));
            t.set("used", LuaValue.valueOf(a.u));
        }
        return t;
    }

    // ---- the layout ---------------------------------------------------------------------------------

    /**
     * {@code hafen.fight():deck()} — the filled hotkey slots of the loaded school, in key order, as a plain
     * array (§2.3: a layout is addressed by its own order and there is nothing to search it by). An empty slot
     * is omitted; the card's own {@code :slot()} and {@code :key()} carry the position, so the gap is never
     * ambiguous.
     */
    static LuaValue deck(Addon owner) {
        FightWnd.Action[] order = order();
        LuaTable out = new LuaTable();
        int n = 0;
        for(int slot = 0; slot < order.length; slot++) {
            if(order[slot] != null)
                out.set(++n, of(owner, slot));
        }
        return out;
    }
}
