package io.brodgar.addon;

import haven.GameUI;
import haven.MenuGrid;
import haven.Resource;

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

/**
 * An <b>action-bar Slot object</b> — the OOP successor of the flat action-bar snapshot reader and its
 * {@code use(n, mods)} verb (spec {@code 021-actionbar-oop}), built on exactly the
 * mechanism {@link LuaGob} (017) and {@link LuaKin} (020) established. <b>Arity is the verb on the namespace
 * itself</b>: {@code hafen.actionbar(n)} is one Slot, {@code hafen.actionbar()} the collection of all of them.
 *
 * <p><b>Addressing is 0-based and single.</b> {@code hafen.actionbar(n)} takes the <b>raw game index</b>
 * (0..143 — the index the server uses in {@code setbelt}), and that is the <i>one</i> way to address a slot.
 * {@code hafen.actionbar()} is not a second way in: it is the iteration view (a 1-based Lua array, like
 * {@code hafen.kin()}'s roster), whose position is a position and not an index — it hands back the very same
 * interned objects, so {@code hafen.actionbar()[1] == hafen.actionbar(0)}. A Slot always knows its own game
 * index ({@code slot:index()}), so nothing has to reconstruct it from the array position.
 *
 * <p><b>Wraps only the index.</b> Every method re-reads through one funnel — {@link AddonManager#gui()}{@code
 * .belt[index]} — so a stashed Slot tracks the slot being set, cleared or dragged, and goes
 * {@code :empty() == true} the moment it is cleared (D-012's freshness, verbatim). {@code :info()} is the one
 * snapshot escape hatch (today's {@code ActionbarSlot} shape, {@link CharApi#actionbarSnapshot}).
 *
 * <p><b>Userdata + per-addon interning</b> (D-017 / D-045), identical to {@link LuaGob}/{@link LuaKin}: the
 * handle crosses as {@code LuaValue.userdataOf(luaSlot, mt)} so Lua cannot scribble on it, and the
 * {@link Cache} on the owning {@link Addon} (weak values + a {@link ReferenceQueue} drained on every access —
 * <i>not</i> a {@code WeakHashMap}, which is weak <i>keys</i>) makes {@code hafen.actionbar(n) ==
 * hafen.actionbar(n)} and {@code seen[slot] = true} reliable. Never static: no Lua value crosses a sandbox
 * boundary and the cache dies whole with the {@link Addon} on {@code :reload}.
 *
 * <p><b>The collection is a fresh array per call</b> and always <b>exactly 144 elements</b> — the belt is a
 * fixed-size array, never sparse, so every position holds a Slot object whether or not it has content (ask
 * {@code :empty()}). No {@code :find()} / {@code :list()}: with a fixed dense array there is nothing to look
 * up that {@code hafen.actionbar(n)} does not already answer.
 *
 * <p><b>Writes</b> ({@code :use}) keep the {@code requireActions} gating (D-027/D-028) and drive the client's
 * own {@code Belt.act} (wrap-not-reimplement, D-009); {@code :use} returns <b>self</b> so it chains.
 *
 * <p><b>Threading.</b> Every read/write runs on the UI thread (addon tick / REPL / timer / slash command);
 * {@code belt[n]} is a plain array read, but the resource-backed fields behind it are {@code Loading}-guarded
 * in {@link CharApi}. The {@link Cache} map is guarded on its own monitor (UI + REPL threads touch it).
 */
public final class LuaSlot {
    /** How many slots the action bar has — {@code GameUI.belt} is a fixed {@code BeltSlot[144]}. */
    public static final int SLOTS = 144;

    /** The raw 0-based game slot index — the whole state of a handle. */
    public final int index;

    private LuaSlot(int index) {
        this.index = index;
    }

    /** {@code tostring(slot)} (also the {@code __tostring} answer): {@code Slot(<index>)}. */
    public String toString() {
        return "Slot(" + index + ")";
    }

    /** An interned Slot object for {@code index} in {@code owner}'s env — the one way a Slot reaches Lua. */
    static LuaValue of(Addon owner, int index) {
        return owner.slots.of(index);
    }

    /** The {@code LuaSlot} behind a Lua value, or {@code null} for anything that is not a Slot object. */
    static LuaSlot resolve(LuaValue v) {
        if((v == null) || !v.isuserdata())
            return null;
        Object o = v.touserdata();
        return (o instanceof LuaSlot) ? (LuaSlot)o : null;
    }

    // ---- the per-addon intern cache + metatable ---------------------------------------------------

    /**
     * One addon's Slot interning cache and metatable (its {@link Addon#slots}). Weak values + a
     * {@link ReferenceQueue} drained on every access; the metatable is built once, lazily. Holds its
     * {@link Addon} because the gated {@code :use} verb needs the owner to check the {@code actions}
     * permission against.
     *
     * <p>A bounded 144-entry map would not strictly need weak values, but the shape is kept identical to
     * {@link LuaGob.Cache}/{@link LuaKin.Cache} on purpose: one interning idiom, one place to get it right.
     */
    static final class Cache {
        private final Addon owner;
        private final Map<Integer, Ref> live = new HashMap<Integer, Ref>();
        private final ReferenceQueue<LuaValue> dead = new ReferenceQueue<LuaValue>();
        private LuaValue mt;

        Cache(Addon owner) {
            this.owner = owner;
        }

        /** The interned handle for {@code index} — a cache hit, or a freshly minted (and inserted) one. */
        synchronized LuaValue of(int index) {
            drain();
            Integer key = Integer.valueOf(index);
            Ref r = live.get(key);
            if(r != null) {
                LuaValue v = r.get();
                if(v != null)
                    return v;
                live.remove(key);
            }
            LuaValue v = LuaValue.userdataOf(new LuaSlot(index), meta());
            live.put(key, new Ref(v, key, dead));
            return v;
        }

        /** Drop the map entries whose handle Lua has released (the key + dead ref would leak otherwise). */
        private void drain() {
            Reference<? extends LuaValue> r;
            while((r = dead.poll()) != null) {
                Ref sr = (Ref)r;
                if(live.get(sr.key) == sr)      // not already replaced by a fresh handle for the same index
                    live.remove(sr.key);
            }
        }

        private LuaValue meta() {
            if(mt == null)
                mt = buildMeta(owner);
            return mt;
        }
    }

    /** A weak handle reference that remembers its map key, so the {@link ReferenceQueue} drain can unmap it. */
    private static final class Ref extends WeakReference<LuaValue> {
        final Integer key;

        Ref(LuaValue v, Integer key, ReferenceQueue<LuaValue> q) {
            super(v, q);
            this.key = key;
        }
    }

    // ---- the Slot metatable ------------------------------------------------------------------------

    /** The per-addon metatable: {@code __index} = the methods table, plus {@code __tostring}/{@code __name}. */
    private static LuaValue buildMeta(final Addon owner) {
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, methods(owner));
        mt.set("__name", LuaValue.valueOf("Slot"));
        mt.set("__tostring", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaSlot h = resolve(self);
                return LuaValue.valueOf((h == null) ? "Slot(?)" : h.toString());
            }
        });
        return mt;
    }

    /**
     * The method set. Every reader re-resolves {@code belt[index]} and answers {@code nil} for an empty slot
     * (or before the HUD exists); {@code :index()} and {@code :empty()} are the two that always answer.
     * {@code :use} is {@code actions}-gated and returns <b>self</b> so it chains.
     */
    private static LuaTable methods(final Addon owner) {
        LuaTable m = new LuaTable();
        // index() — the raw 0-based game index this Slot addresses. Answers from the handle alone, so it is
        // the reliable way back from an array POSITION (hafen.actionbar()[i], 1-based) to the game INDEX.
        m.set("index", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(handle(self, "index").index);
            }
        });
        // empty() — has this slot no content? Also true before the HUD exists (nothing is on the bar yet).
        m.set("empty", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(belt(self, "empty") == null);
            }
        });
        // info() — the one SNAPSHOT escape hatch (the old ActionbarSlot shape), for logging/serialising.
        m.set("info", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return CharApi.actionbarSnapshot(belt(self, "info"));
            }
        });
        m.set("res", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Resource r = CharApi.actionbarResObj(belt(self, "res"));
                return (r == null) ? LuaValue.NIL : LuaValue.valueOf(r.name);
            }
        });
        m.set("name", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                GameUI.BeltSlot s = belt(self, "name");
                String n = CharApi.actionbarName(s, CharApi.actionbarResObj(s));
                return (n == null) ? LuaValue.NIL : LuaValue.valueOf(n);
            }
        });
        // cooldown() — the 0..1 meter fraction of a pagina action (an ability recharging), NOT seconds; nil
        // for a slot that has no meter (an item), and while the action's data is still Loading.
        m.set("cooldown", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Double cd = CharApi.actionbarCooldown(belt(self, "cooldown"));
                return (cd == null) ? LuaValue.NIL : LuaValue.valueOf(cd.doubleValue());
            }
        });
        // -- the gated write (D-027/D-028): drive the client's own Belt.act (D-009), return self -----------
        // use([mods]) — exactly what a LEFT-click on that button does (GameUI.Belt.mousedown b==1 ->
        // wdgmsg("belt", n, ...)), so a ground-targeted ability enters targeting mode just as clicking would.
        // mods is the optional modifier bitfield (Shift=1, Ctrl=2, Alt=4).
        m.set("use", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = a.arg1();
                AddonManager.requireActions(owner, "slot:use");
                int n = handle(self, "use").index;
                GameUI g = AddonManager.gui();
                if(g == null)
                    throw new LuaError("slot:use(): no game UI (not in the world yet)");
                if((g.belt == null) || (n < 0) || (n >= g.belt.length))
                    throw new LuaError("slot:use(): slot index out of range (0.." + (SLOTS - 1) + "), got " + n);
                if(g.belt[n] == null)
                    throw new LuaError("slot:use(): slot " + n + " is empty (check slot:empty() first)");
                if(g.beltwdg == null)
                    throw new LuaError("slot:use(): no action-bar widget yet");
                g.beltwdg.act(n, new MenuGrid.Interaction(1, a.arg(2).optint(0)));
                return self;
            }
        });
        return m;
    }

    // ---- self resolution -------------------------------------------------------------------------

    /** The handle behind a method's {@code self}, or a guiding error (a dot-call passes the wrong self). */
    private static LuaSlot handle(LuaValue self, String method) {
        LuaSlot h = resolve(self);
        if(h == null)
            throw new LuaError("slot:" + method + "() — use a COLON call on a Slot object (hafen.actionbar(n), hafen.actionbar()[i])");
        return h;
    }

    /** The LIVE belt slot behind a method's {@code self}: re-read every call, {@code null} when empty. */
    private static GameUI.BeltSlot belt(LuaValue self, String method) {
        int n = handle(self, method).index;
        GameUI g = AddonManager.gui();
        if((g == null) || (g.belt == null) || (n < 0) || (n >= g.belt.length))
            return null;        // pre-HUD / mid-:reload — an unpopulated slot, not an error
        return g.belt[n];
    }

    // ---- the collection --------------------------------------------------------------------------

    /**
     * {@code hafen.actionbar()} — all 144 slots as a fresh <b>1-based</b> Lua array of (interned) Slot
     * objects, in game-index order, so {@code #} is exactly 144 and {@code ipairs} covers every slot. A plain
     * array with no metatable: the position is only a position ({@code slot:index()} is the game index) and
     * there are no collection methods to hide the length behind.
     */
    private static LuaValue collection(Addon owner) {
        LuaTable out = new LuaTable();
        for(int n = 0; n < SLOTS; n++)
            out.set(n + 1, of(owner, n));
        return out;
    }

    /**
     * {@code hafen.actionbar} itself: a <b>callable table</b> ({@code __call}) with arity dispatch, so
     * {@code hafen.actionbar()} / {@code hafen.actionbar(n)} work while indexing it (the old {@code slot} and
     * {@code use} fields) reads as plain {@code nil} — the hard cut (D-013) is visible from Lua, exactly as
     * {@code hafen.gob} (D-044) and {@code hafen.kin} (D-056) did it.
     */
    static LuaValue factory(final Addon owner) {
        LuaTable actionbar = new LuaTable();
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.CALL, new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue key = a.arg(2);        // arg1 = the callable table itself
                if(key.isnil())
                    return collection(owner);
                if(key.isnumber()) {            // BEFORE isstring(): in LuaJ a number IS a string
                    int n = key.toint();
                    // Bounded, unlike hafen.kin(id): the belt is a fixed array, so an out-of-range index is
                    // a bug in the addon (a typo'd loop), never a slot that merely does not exist yet.
                    if((n < 0) || (n >= SLOTS))
                        throw new LuaError("hafen.actionbar(n): slot index out of range (0.." + (SLOTS - 1) + "), got " + n);
                    return of(owner, n);
                }
                throw new LuaError("hafen.actionbar([n]): no argument = all " + SLOTS + " slots,"
                    + " a number = one slot by its raw 0-based game index (0.." + (SLOTS - 1) + ")");
            }
        });
        actionbar.setmetatable(mt);
        return actionbar;
    }
}
