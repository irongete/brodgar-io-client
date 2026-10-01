package io.brodgar.addon;

import haven.FightWnd;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.VarArgFunction;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;

/**
 * A <b>DeckCard object</b> — one hotkey place of the combat school a character has loaded
 * ({@code s:fight():deck()}, reshaped 172.2): a place in the tab's layout, {@code FightWnd.order}, with the
 * hotkey label the tab paints under it, plus whichever maneuver is sitting there right now. The deck is read
 * as the combat row is ({@link LuaCombatAction}): every place, filled or not, addressed by its hotkey number.
 *
 * <p><b>The intern key is the place</b> (§2.4), not the maneuver in it. The place is what the layout is made of
 * and what the server addresses — loading another school rewrites every place's contents while the places
 * themselves stay put — so a stashed card follows the hotkey rather than a maneuver that has been dealt
 * somewhere else.
 *
 * <p><b>A deck index counts inside one character's school</b> (077.4), which is why the account is half the
 * handle. The layout is an array of one {@link FightWnd}, and every character configures its own — so hotkey
 * 3 on two characters is two different places, and a handle that carried the index alone would call them
 * one. Two levels of intern map, on {@code (account, slot)}: the {@link LuaGob} shape.
 *
 * <p><b>{@code :empty()} means the place holds nothing.</b> An empty card keeps answering {@code :index()},
 * {@code :wire()} and {@code :key()} — they are properties of the place — while every reader of the maneuver
 * half and {@code :info()} answer {@code nil}, the {@code Slot} shape. {@code :index()} is the hotkey number,
 * {@code :wire()} that minus one, and {@code :get(n)} takes the same number.
 *
 * <p><b>The length is the server's</b>: {@code order} is as long as the tab the server built, so {@code :get(n)}
 * answers {@code nil} past it, {@code 0} included, as the saved schools' {@code :get(n)} does.
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
        mt.set(LuaValue.INDEX, Refusal.closedIndex("deckcard", methods(owner),
            "a deck card is one hotkey place of the loaded school's layout"));
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
        // index() — the 1-based position, the hotkey number and the n :get(n) takes. Always answers.
        m.set("index", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                return LuaValue.valueOf(handle(Args.only(a, 0, "card:index"), "index").slot + 1);
            }
        });
        // wire() — the raw 0-based deck index, the same one the tab's messages carry. Always answers.
        m.set("wire", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                return LuaValue.valueOf(handle(Args.only(a, 0, "card:wire"), "wire").slot);
            }
        });
        // key() — the hotkey label the tab paints under this place, or nil for a place it paints none for.
        m.set("key", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                String k = keyLabel(handle(Args.only(a, 0, "card:key"), "key").slot);
                return (k == null) ? LuaValue.NIL : LuaValue.valueOf(k);
            }
        });
        // empty() — does the place hold nothing? True too before the tab has built and past its layout.
        m.set("empty", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaDeckCard h = handle(Args.only(a, 0, "card:empty"), "empty");
                return LuaValue.valueOf(action(h.user, h.slot) == null);
            }
        });
        // maneuver() — the maneuver dealt into this place, or nil while the place is empty.
        m.set("maneuver", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaDeckCard h = handle(Args.only(a, 0, "card:maneuver"), "maneuver");
                FightWnd.Action act = action(h.user, h.slot);
                return (act == null) ? LuaValue.NIL : LuaManeuver.of(owner, h.user, act);
            }
        });
        // res() — the dealt maneuver's resource name, or nil (empty place, or the resource is still resolving).
        m.set("res", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaDeckCard h = handle(Args.only(a, 0, "card:res"), "res");
                FightWnd.Action act = action(h.user, h.slot);
                String r = (act == null) ? null : AddonManager.resIdent(act.res);
                return (r == null) ? LuaValue.NIL : LuaValue.valueOf(r);
            }
        });
        // name() — the dealt maneuver's display name, or nil.
        m.set("name", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaDeckCard h = handle(Args.only(a, 0, "card:name"), "name");
                FightWnd.Action act = action(h.user, h.slot);
                String n = (act == null) ? null : AddonManager.resTipName(act.res, null);
                return (n == null) ? LuaValue.NIL : LuaValue.valueOf(n);
            }
        });
        // used() — how many copies of the dealt maneuver that character's deck holds, or nil for an empty place.
        m.set("used", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaDeckCard h = handle(Args.only(a, 0, "card:used"), "used");
                FightWnd.Action act = action(h.user, h.slot);
                return (act == null) ? LuaValue.NIL : LuaValue.valueOf(act.u);
            }
        });
        // info() — the one SNAPSHOT escape hatch: {key?, res?, name?, used}, nil for an empty place (the Slot shape).
        m.set("info", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaDeckCard h = handle(Args.only(a, 0, "card:info"), "info");
                return snapshot(h.user, h.slot);
            }
        });
        return m;
    }

    private static LuaDeckCard handle(LuaValue self, String method) {
        LuaDeckCard h = resolve(self);
        if(h == null)
            throw new LuaError("card:" + method + "() — use a COLON call on a DeckCard object ("
                + CharApi.FD + ":get(n), " + CharApi.FD + ":list()[n])");
        return h;
    }

    // ---- the reads ----------------------------------------------------------------------------------

    /** How many places that character's layout has: {@code order} is final and fixed-length, {@code 0} with no tab. */
    private static int places(String user) {
        FightWnd fw = CharApi.fightwnd(user);
        return (fw == null) ? 0 : fw.order.length;
    }

    /**
     * The maneuver dealt into {@code slot} of {@code user}'s deck, or {@code null} for an empty or vanished
     * place — one element read under the monitor, never a copy of the whole layout per verb.
     */
    private static FightWnd.Action action(String user, int slot) {
        FightWnd fw = CharApi.fightwnd(user);
        if(fw == null)
            return null;
        synchronized(LuaWidget.monitor(fw)) {
            FightWnd.Action[] order = fw.order;
            return ((slot < 0) || (slot >= order.length)) ? null : order[slot];
        }
    }

    /**
     * The hotkey label the window paints under a deck slot, or {@code null} for a slot past the labels there
     * are (audit2 B16, fg-10). {@code FightWnd.keys} is the whole of them and the window blits from an array
     * of exactly as many, so beyond it there IS no label — inventing {@code slot + 1} answered a number where
     * the verb promises the window's own word, and a reader printing it got a hotkey nobody can press.
     */
    static String keyLabel(int slot) {
        String[] keys = FightWnd.keys;
        return ((keys != null) && (slot >= 0) && (slot < keys.length)) ? keys[slot] : null;
    }

    /**
     * The documented {@code DeckCard} snapshot, content only: {@code nil} for an empty place, whose place is read
     * live by {@code :index()} and {@code :wire()}.
     */
    private static LuaValue snapshot(String user, int slot) {
        FightWnd.Action a = action(user, slot);
        if(a == null)
            return LuaValue.NIL;
        LuaTable t = new LuaTable();
        String k = keyLabel(slot);             // absent for a place the window paints no label under, as :key() is
        if(k != null)
            t.set("key", LuaValue.valueOf(k));
        String res = AddonManager.resIdent(a.res);
        if(res != null)
            t.set("res", LuaValue.valueOf(res));
        String nm = AddonManager.resTipName(a.res, null);
        if(nm != null)
            t.set("name", LuaValue.valueOf(nm));
        t.set("used", LuaValue.valueOf(a.u));
        return t;
    }

    // ---- the layout ---------------------------------------------------------------------------------

    /**
     * {@code s:fight():deck()} — every hotkey place of the school <b>that character</b> has loaded, in hotkey
     * order, every one of them a DeckCard whatever it holds; none before that character's schools tab has
     * built. {@code :get(n)} takes the hotkey number and answers {@code nil} outside the layout, {@code 0}
     * included. A string filter matches the dealt maneuver's own needle, the one {@code s:fight():maneuver()}
     * matches, and an empty place matches none. Minted once per (addon, session) by {@code CharApi.fight}.
     */
    static LuaValue deck(final Addon owner, final String user) {
        return LuaCollection.create(CharApi.FD, new LuaCollection.Source() {
            public List<LuaValue> members() {
                int n = places(user);
                List<LuaValue> out = new ArrayList<LuaValue>(n);
                for(int slot = 0; slot < n; slot++)
                    out.add(of(owner, user, slot));
                return out;
            }

            /** A card names the maneuver it holds, so a string filter is a substring test over that. */
            public boolean named() {
                return true;
            }

            public String needle(LuaValue member) {
                // Resolved, never member.get("name"): since 084 a field read on a closed type hands back the
                // METHOD, so a filter written that way would silently match nothing.
                //   audit2 B16 (fg-09): and it is the MANEUVER'S own needle, res-plus-name, which is what
                // s:fight():maneuver():find takes. A card names the maneuver in it, so one filter string had
                // better find the same maneuver through both doors. An EMPTY place has no maneuver: it matches
                // no string filter rather than refusing the filter.
                LuaDeckCard h = resolve(member);
                FightWnd.Action a = (h == null) ? null : action(h.user, h.slot);
                return (a == null) ? null : LuaManeuver.needleOf(a);
            }

            public boolean addressable() {
                return true;
            }

            public LuaValue getMember(LuaValue key) {
                int n = Args.integer(key, CharApi.FD + ":get", "n", "the hotkey number card:index() answers;"
                                     + " a manoeuvre is a search, " + CharApi.FD + ":find(filter)");
                if((n < 1) || (n > places(user)))
                    return LuaValue.NIL;
                return of(owner, user, n - 1);
            }

            /** The key is the hotkey number {@code card:index()} answers. */
            public String keyName() {
                return "n";
            }
        }, null);
    }
}
