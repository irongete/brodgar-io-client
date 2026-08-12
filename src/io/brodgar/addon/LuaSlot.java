package io.brodgar.addon;

import haven.GameUI;
import haven.MenuGrid;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.VarArgFunction;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * An <b>action-bar Slot object</b> — the OOP successor of the flat action-bar snapshot reader and its
 * {@code use(n, mods)} verb (spec {@code 021-actionbar-oop}), built on exactly the
 * mechanism {@link LuaGob} (017) and {@link LuaKin} (020) established. <b>The section object IS the bar</b>
 * (uniform grammar §2.1): {@code hafen.actionbar()} is the {@link LuaCollection} of every slot and
 * {@code hafen.actionbar():get(n)} is one Slot.
 *
 * <p><b>Addressing is 0-based and single.</b> {@code :get(n)} takes the <b>raw game index</b> (0..143 — the
 * index the server uses in {@code setbelt}), and that is the <i>one</i> way to address a slot.
 * {@code :list()} is not a second way in: it is the iteration view (a 1-based Lua array), whose position is a
 * position and not an index — it hands back the very same interned objects, so
 * {@code hafen.actionbar():list()[1] == hafen.actionbar():get(0)}. A Slot always knows its own game index
 * ({@code slot:index()}), so nothing has to reconstruct it from the array position.
 *
 * <p><b>Wraps only the index.</b> Every method re-reads through one funnel — {@link AddonManager#gui()}{@code
 * .belt[index]} — so a stashed Slot tracks the slot being set, cleared or dragged, and goes
 * {@code :empty() == true} the moment it is cleared (D-012's freshness, verbatim). {@code :info()} is the one
 * snapshot escape hatch (today's {@code ActionbarSlot} shape, {@link CharApi#actionbarSnapshot}).
 *
 * <p><b>Userdata + per-addon interning</b> (D-017 / D-045), identical to {@link LuaGob}/{@link LuaKin}: the
 * handle crosses as {@code LuaValue.userdataOf(luaSlot, mt)} so Lua cannot scribble on it, and the
 * {@link Cache} on the owning {@link Addon} (weak values + a {@link ReferenceQueue} drained on every access —
 * <i>not</i> a {@code WeakHashMap}, which is weak <i>keys</i>) makes {@code hafen.actionbar():get(n) ==
 * hafen.actionbar():get(n)} and {@code seen[slot] = true} reliable. Never static: no Lua value crosses a
 * sandbox boundary and the cache dies whole with the {@link Addon} on {@code :reload}.
 *
 * <p><b>{@code :list()} is a fresh array per call</b> and always <b>exactly 144 elements</b> — the belt is a
 * fixed-size array, never sparse, so every position holds a Slot object whether or not it has content (ask
 * {@code :empty()}). A string filter matches a slot's <b>resource name</b>, so {@code :list("act/")} is the
 * populated ability slots.
 *
 * <p><b>Writes</b> ({@code :use}, {@code :res(name)}) keep the {@code requirePermission} gate (D-027/D-028) and
 * go through the client's own paths (wrap-not-reimplement, D-009): {@code :use} drives {@code Belt.act},
 * {@code :res(name)} sends the very {@code wdgmsg("setbelt", n, "res", name)} a drag from the menu grid sends
 * ({@code GameUI.Belt.dropthing}). Both return <b>self</b> so they chain, and {@code :res()} with no argument
 * is the read half of that one name.
 *
 * <p><b>{@code :pagina(pagOrNil)} is the third write, and it is UNPROTECTED</b> (059.4): it puts one of this
 * addon's own menu entries on the bar, which reaches no server and needs no more permission than drawing a HUD
 * overlay does. It is not an assignment but a <b>hold</b> — see {@link BeltHold} — so it lands immediately
 * where {@code :res(name)} round-trips the server, and the slot's own content comes back untouched when the
 * hold ends. A held slot reads as the entry throughout: {@code :res()} answers the {@code addon/…} identity
 * ({@link CharApi#actionbarRes}), {@code :empty()} is false, and {@code ActionbarChanged} fires on both edges.
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
     * {@link Addon} because the protected {@code :use} verb needs the owner to check the {@code actionbar.use}
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
        mt.set(LuaValue.INDEX, Retired.methodIndex("slot", methods(owner)));
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
     * {@code :use} and {@code :res} are protected by the {@code actionbar.*} keys and return <b>self</b> so they chain.
     */
    private static LuaTable methods(final Addon owner) {
        LuaTable m = new LuaTable();
        // index() — the raw 0-based game index this Slot addresses. Answers from the handle alone, so it is
        // the reliable way back from an array POSITION (hafen.actionbar():list()[i], 1-based) to the game INDEX.
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
        // res() reads the slot's action by resource name; res(name) ASSIGNS one (protected) — one name for the
        // pair the old set() made two. The write is exactly the message dragging that action off the menu grid
        // onto the bar sends (GameUI.Belt.dropthing -> wdgmsg("setbelt", n, "res", pag.res().name)), so the
        // server treats it identically. It lands ASYNCHRONOUSLY (the server echoes a "setbelt" uimsg back), so
        // the slot still reads the OLD content on the next line; the change surfaces as an ActionbarChanged on
        // this very Slot. An unknown resource name is simply ignored by the server — same as a drag of
        // something that does not exist — so there is nothing to report back here. No "pag" variant: pagina
        // ids are session-local and opaque to addons (022 spec, out of scope).
        m.set("res", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = a.arg1();
                LuaValue rv = Args.written(a, 2, "slot:res", "resourceName");
                if(rv == null) {
                    String rn = CharApi.actionbarRes(belt(self, "res"));
                    return (rn == null) ? LuaValue.NIL : LuaValue.valueOf(rn);
                }
                // 059.4: a custom entry's identity is refused BEFORE the gate, unlike the live-entry lookup
                // in pagina:use() (D-213). The gate answers "may this addon assign one of the game's actions";
                // this string is not one, whoever asks — the caller wants the other verb, and that is decided
                // at the line that wrote it rather than by what the manifest happens to declare.
                if(rv.isstring() && !rv.isnumber() && rv.tojstring().trim().startsWith(AddonPagina.PREFIX)) {
                    throw new LuaError("slot:res(resourceName): \"" + rv.tojstring().trim() + "\" is an entry"
                        + " an addon added to the menu, and the server has never heard of it — this verb"
                        + " assigns one of the game's own actions, by the name the server publishes. Hold the"
                        + " slot for the entry instead: slot:pagina(pag).");
                }
                AddonManager.requirePermission(owner, Permission.ACTIONBAR_RES);
                int n = handle(self, "res").index;
                if(!rv.isstring())
                    throw new LuaError("slot:res(resourceName): expected a resource name string, got "
                        + rv.typename() + " (e.g. slot:res(\"gfx/hud/act/mine\"))");
                String res = rv.tojstring().trim();
                if(res.isEmpty())
                    throw new LuaError("slot:res(resourceName): the resource name is empty");
                GameUI g = AddonManager.gui();
                if(g == null)
                    throw new LuaError("slot:res(): no game UI (not in the world yet)");
                g.wdgmsg("setbelt", Integer.valueOf(n), "res", res);
                return self;
            }
        });
        // pagina() reads the entry an addon is HOLDING this slot for, nil for every slot the server owns;
        // pagina(pag) holds it for one of THIS addon's menu entries, and pagina(nil) gives it back (059.4).
        // Nothing is sent: the client draws over the slot and keeps what the server has there, so the write
        // is UNPROTECTED (like a HUD overlay) and lands immediately, unlike :res(name)'s server round trip.
        // nil is DOCUMENTED here (end the hold), so it is the write and not the read: Args.passed, never
        // Args.written. The read half is the hold alone — a slot holding one of the game's own actions is
        // already named by :res(), and hafen.menugrid():get(that) is the Pagina for it.
        m.set("pagina", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = a.arg1();
                int n = handle(self, "pagina").index;
                if(!Args.passed(a, 2)) {
                    AddonPagina p = BeltHold.held(n);
                    return (p == null) ? LuaValue.NIL : LuaPagina.of(owner, p.id);
                }
                LuaValue v = a.arg(2);
                if(v.isnil()) {
                    BeltHold.release(n);
                    return self;
                }
                LuaPagina h = LuaPagina.resolve(v);
                if(h == null)
                    throw new LuaError("slot:pagina(pagOrNil): expected the Pagina object"
                        + " hafen.menugrid():add(id) handed you, or nil to end the hold, got " + v.typename());
                if(!h.res.startsWith(AddonPagina.PREFIX))
                    throw new LuaError("slot:pagina(pagOrNil): \"" + h.res + "\" is the client's own entry,"
                        + " and a slot is held for an entry your addon added (hafen.menugrid():add(id)). To"
                        + " put one of the game's own actions on the bar, assign it: slot:res(name).");
                BeltHold.hold(n, AddonPagina.owned(owner, h.res, "slot:pagina(pagOrNil)"));
                return self;
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
        // -- the protected write (D-027/D-028): drive the client's own Belt.act (D-009), return self -----------
        // use([mods]) — exactly what a LEFT-click on that button does (GameUI.Belt.mousedown b==1 ->
        // wdgmsg("belt", n, ...)), so a ground-targeted ability enters targeting mode just as clicking would.
        // mods is the optional modifier bitfield (Shift=1, Ctrl=2, Alt=4).
        m.set("use", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = a.arg1();
                AddonManager.requirePermission(owner, Permission.ACTIONBAR_USE);
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
            throw new LuaError("slot:" + method + "() — use a COLON call on a Slot object"
                + " (hafen.actionbar():get(n), hafen.actionbar():list()[i])");
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
     * {@code hafen.actionbar()} — the bar, as the {@link LuaCollection} the section object IS:
     * {@code :get(n)} is one slot by its raw 0-based game index, {@code :list(filter)} all 144 in game-index
     * order (a fresh 1-based array), {@code :count}/{@code :find} the usual pair. There is no {@code :add} or
     * {@code :remove}: the bar is a fixed 144-slot array and what changes is a slot's <i>content</i>
     * ({@code slot:res(name)}).
     */
    static LuaValue collection(final Addon owner) {
        return LuaCollection.create("hafen.actionbar()", new LuaCollection.Source() {
            public List<LuaValue> members() {
                List<LuaValue> out = new ArrayList<LuaValue>(SLOTS);
                for(int n = 0; n < SLOTS; n++)
                    out.add(of(owner, n));
                return out;
            }

            // An EMPTY slot has no resource, and there are usually many: it matches no string filter rather
            // than refusing the filter for everybody (which a null needle would do).
            public String needle(LuaValue member) {
                String rn = CharApi.actionbarRes(belt(member, "list"));
                return (rn == null) ? "" : rn;
            }

            /** These have a name, so a string filter is a substring test over {@link #needle}. */
            public boolean named() {
                return true;
            }

            public boolean addressable() {
                return true;
            }

            public LuaValue getMember(LuaValue key) {
                if(!key.isnumber())             // BEFORE isstring(): in LuaJ a number IS a string
                    throw new LuaError("hafen.actionbar():get(n): the key is the raw 0-based game index"
                        + " (0.." + (SLOTS - 1) + "), got " + key.typename());
                int n = key.toint();
                // Bounded, unlike hafen.kin():get(id): the belt is a fixed array, so an out-of-range index is
                // a bug in the addon (a typo'd loop), never a slot that merely does not exist yet.
                if((n < 0) || (n >= SLOTS))
                    throw new LuaError("hafen.actionbar():get(n): slot index out of range (0.."
                        + (SLOTS - 1) + "), got " + n);
                return of(owner, n);
            }
        }, null);
    }
}
