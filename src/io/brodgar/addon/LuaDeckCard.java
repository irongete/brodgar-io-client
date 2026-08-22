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
 * A <b>DeckCard object</b> — one hotkey slot of the combat school a character has loaded
 * ({@code s:fight():deck()}). A card is a <i>place in the layout</i>: the slot index the write path uses and
 * the hotkey label the window paints under it, plus whichever maneuver is sitting there right now.
 *
 * <p><b>The intern key is the slot</b> (§2.4), not the maneuver in it. The slot is what the layout is made of
 * and what the server addresses — loading another school rewrites every slot's contents while the slots
 * themselves stay put — so a stashed card follows the hotkey rather than following a maneuver that has been
 * dealt somewhere else.
 *
 * <p><b>A deck index counts inside one character's school</b> (077.4), which is why the account is half the
 * handle. The layout is an array of one {@link FightWnd}, and every character configures its own — so hotkey
 * 3 on two characters is two different places, and a handle that carried the index alone would call them
 * one. Two levels of intern map, on {@code (account, slot)}: the {@link LuaGob} shape.
 *
 * <p><b>{@code :exists()} means the slot is filled.</b> An emptied hotkey keeps answering {@code :slot()} and
 * {@code :key()} — they are properties of the place — while {@code :res()}, {@code :name()} and
 * {@code :maneuver()} go {@code nil}. {@code s:fight():deck()} lists only the filled slots, so an empty one
 * is reachable solely through a card you were already holding.
 *
 * <p>The deck is a <b>plain array</b> rather than a collection (§2.3): it is a layout, addressed by nothing but
 * its own order, and there is nothing to search it by that {@code s:fight():maneuver()} does not already
 * answer.
 */
public final class LuaDeckCard {
    /** The account whose school this place is in — half the address, and what makes the index mean one hotkey. */
    public final String user;
    /** The raw 0-based deck index, in that character's layout, and what the write path takes. */
    public final int slot;

    private LuaDeckCard(String user, int slot) {
        this.user = user;
        this.slot = slot;
    }

    /** {@code tostring(card)}: {@code DeckCard(<slot>)}. */
    public String toString() {
        return "DeckCard(" + slot + ")";
    }

    /** An interned DeckCard object for deck slot {@code slot} <b>of {@code user}'s school</b>, in {@code owner}'s env. */
    static LuaValue of(Addon owner, String user, int slot) {
        return owner.deckCards.of(user, slot);
    }

    /** The {@code LuaDeckCard} behind a Lua value, or {@code null} for anything else. */
    static LuaDeckCard resolve(LuaValue v) {
        if((v == null) || !v.isuserdata())
            return null;
        Object o = v.touserdata();
        return (o instanceof LuaDeckCard) ? (LuaDeckCard)o : null;
    }

    // ---- the per-addon intern cache + metatable ----------------------------------------------------

    /**
     * One addon's DeckCard cache and metatable (its {@link Addon#deckCards}), keyed by the <b>account plus</b>
     * the deck slot: a layout is one character's, so two characters both holding a maneuver on hotkey 3 hold
     * two different places and must have two handles. Two levels of map, the {@link LuaGob} shape.
     */
    static final class Cache {
        private final Addon owner;
        private final Map<String, Map<Integer, Ref>> live = new HashMap<String, Map<Integer, Ref>>();
        private final ReferenceQueue<LuaValue> dead = new ReferenceQueue<LuaValue>();
        private LuaValue mt;

        Cache(Addon owner) {
            this.owner = owner;
        }

        synchronized LuaValue of(String user, int slot) {
            drain();
            Map<Integer, Ref> byslot = live.get(user);
            if(byslot == null)
                live.put(user, byslot = new HashMap<Integer, Ref>());
            Integer key = Integer.valueOf(slot);
            Ref r = byslot.get(key);
            if(r != null) {
                LuaValue v = r.get();
                if(v != null)
                    return v;
                byslot.remove(key);
            }
            LuaValue v = LuaValue.userdataOf(new LuaDeckCard(user, slot), meta());
            byslot.put(key, new Ref(v, user, key, dead));
            return v;
        }

        private void drain() {
            Reference<? extends LuaValue> r;
            while((r = dead.poll()) != null) {
                Ref cr = (Ref)r;
                Map<Integer, Ref> byslot = live.get(cr.user);
                if(byslot == null)
                    continue;
                if(byslot.get(cr.key) == cr)     // not already replaced by a fresh handle for the same slot
                    byslot.remove(cr.key);
                if(byslot.isEmpty())
                    live.remove(cr.user);
            }
        }

        private LuaValue meta() {
            if(mt == null)
                mt = buildMeta(owner);
            return mt;
        }
    }

    private static final class Ref extends WeakReference<LuaValue> {
        final String user;
        final Integer key;

        Ref(LuaValue v, String user, Integer key, ReferenceQueue<LuaValue> q) {
            super(v, q);
            this.user = user;
            this.key = key;
        }
    }

    // ---- the DeckCard metatable ---------------------------------------------------------------------

    private static LuaValue buildMeta(final Addon owner) {
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, Retired.closedIndex("deckcard", methods(owner),
            "a fight deck card answers :index() :wire() :key() :maneuver() :res() :name() :used() :exists() and "
            + ":info()"));
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
        // index() — the 1-based position in s:fight():deck(), so deck()[n]:index() == n (090, A-071).
        m.set("index", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(handle(self, "index").slot + 1);
            }
        });
        // wire() — the raw 0-based deck index, the same one the write path takes.
        m.set("wire", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(handle(self, "wire").slot);
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
                LuaDeckCard h = handle(self, "maneuver");
                FightWnd.Action a = action(h.user, h.slot);
                return (a == null) ? LuaValue.NIL : LuaManeuver.of(owner, h.user, a);
            }
        });
        // res() — the dealt maneuver's resource name, or nil (empty slot, or the resource is still resolving).
        m.set("res", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaDeckCard h = handle(self, "res");
                FightWnd.Action a = action(h.user, h.slot);
                String r = (a == null) ? null : AddonManager.resIdent(a.res);
                return (r == null) ? LuaValue.NIL : LuaValue.valueOf(r);
            }
        });
        // name() — the dealt maneuver's display name, or nil.
        m.set("name", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaDeckCard h = handle(self, "name");
                FightWnd.Action a = action(h.user, h.slot);
                String n = (a == null) ? null : AddonManager.resTipName(a.res, null);
                return (n == null) ? LuaValue.NIL : LuaValue.valueOf(n);
            }
        });
        // used() — how many copies of the dealt maneuver that character's deck holds, or nil for an empty slot.
        m.set("used", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaDeckCard h = handle(self, "used");
                FightWnd.Action a = action(h.user, h.slot);
                return (a == null) ? LuaValue.NIL : LuaValue.valueOf(a.u);
            }
        });
        // exists() — is this hotkey still holding a maneuver?
        m.set("exists", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaDeckCard h = handle(self, "exists");
                return LuaValue.valueOf(action(h.user, h.slot) != null);
            }
        });
        // info() — the one SNAPSHOT escape hatch.
        m.set("info", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaDeckCard h = handle(self, "info");
                return snapshot(h.user, h.slot);
            }
        });
        return m;
    }

    private static LuaDeckCard handle(LuaValue self, String method) {
        LuaDeckCard h = resolve(self);
        if(h == null)
            throw new LuaError("card:" + method + "() — use a COLON call on a DeckCard object"
                + " (" + CharApi.FT + ":deck()[i])");
        return h;
    }

    // ---- the reads ----------------------------------------------------------------------------------

    /** <b>That character's</b> loaded school's layout, copied under the UI monitor (its entries are reassigned there). */
    static FightWnd.Action[] order(String user) {
        FightWnd fw = CharApi.fightwnd(user);
        if(fw == null)
            return new FightWnd.Action[0];
        synchronized(LuaWidget.monitor(fw)) {
            return Arrays.copyOf(fw.order, fw.order.length);
        }
    }

    /** The maneuver dealt into {@code slot} of {@code user}'s deck, or {@code null} for an empty or vanished slot. */
    private static FightWnd.Action action(String user, int slot) {
        FightWnd.Action[] order = order(user);
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
    private static LuaValue snapshot(String user, int slot) {
        LuaTable t = new LuaTable();
        t.set("slot", LuaValue.valueOf(slot));
        t.set("key", LuaValue.valueOf(keyLabel(slot)));
        FightWnd.Action a = action(user, slot);
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
     * {@code s:fight():deck()} — the filled hotkey slots of the school <b>that character</b> has loaded, in
     * key order, as a plain array (§2.3: a layout is addressed by its own order and there is nothing to
     * search it by). An empty slot is omitted; the card's own {@code :slot()} and {@code :key()} carry the
     * position, so the gap is never ambiguous. Empty before that character's schools tab has built.
     */
    static LuaValue deck(Addon owner, String user) {
        FightWnd.Action[] order = order(user);
        LuaTable out = new LuaTable();
        int n = 0;
        for(int slot = 0; slot < order.length; slot++) {
            if(order[slot] != null)
                out.set(++n, of(owner, user, slot));
        }
        return out;
    }
}
