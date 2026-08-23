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
 * (uniform grammar §2.1): {@code s:actionbar()} is the {@link LuaCollection} of every slot and
 * {@code s:actionbar():get(n)} is one Slot.
 *
 * <p><b>Addressing is 1-based and single</b> (090). {@code :get(n)} takes the position {@code slot:index()}
 * answers, {@code 1..144}, and that is the <i>one</i> way to address a slot; {@code slot:wire()} is the raw
 * number the server's {@code setbelt} carries, which is that position minus one and is a wire fact rather
 * than an address. {@code :list()} is not a second way in: it hands back the very same interned objects, so
 * {@code s:actionbar():list()[1] == s:actionbar():get(1)}.
 *
 * <p><b>Wraps the account and the index.</b> Every method re-reads through one funnel —
 * {@link AddonManager#gameui(String)}{@code .belt[index]} — so a stashed Slot tracks the slot being set,
 * cleared or dragged, and goes {@code :empty() == true} the moment it is cleared (D-012's freshness,
 * verbatim). {@code :info()} is the one snapshot escape hatch (today's {@code ActionbarSlot} shape,
 * {@link CharApi#actionbarSnapshot}).
 *
 * <p><b>A slot index counts inside one character's bar</b> (077.3), which is why the account is half the
 * handle. {@code GameUI.belt} is one login's array and the server fills each one on its own, so slot 11 on
 * two characters is two different buttons — and a handle that carried the index alone would call them one.
 * Two levels of intern map, on {@code (account, index)}: the {@link LuaGob} shape.
 *
 * <p><b>Userdata + per-addon interning</b> (D-017 / D-045), identical to {@link LuaGob}/{@link LuaKin}: the
 * handle crosses as {@code LuaValue.userdataOf(luaSlot, mt)} so Lua cannot scribble on it, and the
 * {@link Cache} on the owning {@link Addon} (weak values + a {@link ReferenceQueue} drained on every access —
 * <i>not</i> a {@code WeakHashMap}, which is weak <i>keys</i>) makes {@code s:actionbar():get(n) ==
 * s:actionbar():get(n)} and {@code seen[slot] = true} reliable. Never static: no Lua value crosses a
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
 * <p><b>Each keeps the ONE key it has, addressed or not</b> (077.3). A key names the <i>action</i>, not the
 * target: the player could have tabbed to that character and pressed that button. Nor does either send need
 * the anchor — both leave from that session's own widgets, its {@code GameUI} and that HUD's own
 * {@code beltwdg} — so a write lands on the bar it was addressed at whether or not it is on screen.
 *
 * <p><b>{@code :pagina(pagOrNil)} is the third write, and it is UNPROTECTED</b> (059.4): it puts one of this
 * addon's own menu entries on the bar, which reaches no server and needs no more permission than drawing a HUD
 * overlay does. It is not an assignment but a <b>hold</b> — see {@link BeltHold} — so it lands immediately
 * where {@code :res(name)} round-trips the server, and the slot's own content comes back untouched when the
 * hold ends. A held slot reads as the entry throughout: {@code :res()} answers the {@code addon/…} identity
 * ({@link CharApi#actionbarRes}), {@code :empty()} is false, and {@code ActionbarChanged} fires on both edges.
 *
 * <p><b>Threading.</b> Every read/write runs on the UI thread (addon tick / REPL / timer / console command);
 * {@code belt[n]} is a plain array read, but the resource-backed fields behind it are {@code Loading}-guarded
 * in {@link CharApi}. The {@link Cache} map is guarded on its own monitor (UI + REPL threads touch it).
 */
public final class LuaSlot {
    /** How many slots the action bar has — {@code GameUI.belt} is a fixed {@code BeltSlot[144]}. */
    public static final int SLOTS = 144;

    /** The account whose bar this slot is on — half the address, and what makes the index mean one button. */
    public final String user;
    /** The raw 0-based game slot index, on that character's bar. */
    public final int index;

    private LuaSlot(String user, int index) {
        this.user = user;
        this.index = index;
    }

    /** {@code tostring(slot)} (also the {@code __tostring} answer): {@code Slot(<index>)}. */
    public String toString() {
        return "Slot(" + index + ")";
    }

    /** An interned Slot object for {@code index} <b>on {@code user}'s bar</b>, in {@code owner}'s env. */
    static LuaValue of(Addon owner, String user, int index) {
        return owner.slots.of(user, index);
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
     * One addon's Slot interning cache and metatable (its {@link Addon#slots}), keyed by the <b>account plus</b>
     * the slot index: a bar is one character's, so two characters both holding something in slot 11 hold two
     * different buttons and must have two handles. Two levels of map, the {@link LuaGob} shape. Weak values +
     * a {@link ReferenceQueue} drained on every access; the metatable is built once, lazily. Holds its
     * {@link Addon} because the protected {@code :use} verb needs the owner to check the {@code actionbar.use}
     * permission against.
     *
     * <p>A 144-entry map per account would not strictly need weak values, but the shape is kept identical to
     * {@link LuaGob.Cache}/{@link LuaKin.Cache} on purpose: one interning idiom, one place to get it right.
     */
    static final class Cache {
        private final Addon owner;
        private final Map<String, Map<Integer, Ref>> live = new HashMap<String, Map<Integer, Ref>>();
        private final ReferenceQueue<LuaValue> dead = new ReferenceQueue<LuaValue>();
        private LuaValue mt;

        Cache(Addon owner) {
            this.owner = owner;
        }

        /** The interned handle for {@code (user, index)} — a cache hit, or a freshly minted (inserted) one. */
        synchronized LuaValue of(String user, int index) {
            drain();
            Map<Integer, Ref> byidx = live.get(user);
            if(byidx == null)
                live.put(user, byidx = new HashMap<Integer, Ref>());
            Integer key = Integer.valueOf(index);
            Ref r = byidx.get(key);
            if(r != null) {
                LuaValue v = r.get();
                if(v != null)
                    return v;
                byidx.remove(key);
            }
            LuaValue v = LuaValue.userdataOf(new LuaSlot(user, index), meta());
            byidx.put(key, new Ref(v, user, key, dead));
            return v;
        }

        /** Drop the map entries whose handle Lua has released (the key + dead ref would leak otherwise). */
        private void drain() {
            Reference<? extends LuaValue> r;
            while((r = dead.poll()) != null) {
                Ref sr = (Ref)r;
                Map<Integer, Ref> byidx = live.get(sr.user);
                if(byidx == null)
                    continue;
                if(byidx.get(sr.key) == sr)     // not already replaced by a fresh handle for the same index
                    byidx.remove(sr.key);
                if(byidx.isEmpty())
                    live.remove(sr.user);
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
        final String user;
        final Integer key;

        Ref(LuaValue v, String user, Integer key, ReferenceQueue<LuaValue> q) {
            super(v, q);
            this.user = user;
            this.key = key;
        }
    }

    // ---- the Slot metatable ------------------------------------------------------------------------

    /**
     * The per-addon metatable: {@code __index} = the methods table through {@link Refusal#closedIndex} (so
     * an unknown verb throws naming what this type does answer), plus {@code __tostring}/{@code __name}.
     */
    private static LuaValue buildMeta(final Addon owner) {
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, Refusal.closedIndex("slot", methods(owner),
            "one action-bar slot",
            ":use() presses it"));
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
        // index() — the 1-based position in s:actionbar():list(), which is the number :get(n) takes, so
        // s:actionbar():list()[n] == s:actionbar():get(n) holds (090, A-071/A-072). It was the raw 0-based
        // game index, and the array beside it was 1-based, which is the one off-by-one this API could still
        // remove for free: get(1) was a real slot, just the wrong one, and nothing raised.
        m.set("index", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(handle(self, "index").index + 1);
            }
        });
        // wire() — the raw 0-based game index, the number the server's own message carries. The server owns
        // the bar and 0 is its number, which is why the raw one is still reachable; it is just no longer the
        // thing a verb named `index` answers while :list() counts from one.
        m.set("wire", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(handle(self, "wire").index);
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
                LuaSlot h = handle(self, "res");
                if(!rv.isstring())
                    throw new LuaError("slot:res(resourceName): expected a resource name string, got "
                        + rv.typename() + " (e.g. slot:res(\"gfx/hud/act/mine\"))");
                String res = rv.tojstring().trim();
                if(res.isEmpty())
                    throw new LuaError("slot:res(resourceName): the resource name is empty");
                // THAT character's own HUD sends it: GameUI.wdgmsg walks its own tree to its own Session, so
                // the assignment lands on the bar the slot names whether or not it is the one on screen.
                GameUI g = AddonManager.gameui(h.user);
                if(g == null)
                    throw new LuaError("slot:res(): no game UI (that character is not in the world yet)");
                g.wdgmsg("setbelt", Integer.valueOf(h.index), "res", res);
                return self;
            }
        });
        // pagina() reads the entry an addon is HOLDING this slot for, nil for every slot the server owns;
        // pagina(pag) holds it for one of THIS addon's menu entries, and pagina(nil) gives it back (059.4).
        // Nothing is sent: the client draws over the slot and keeps what the server has there, so the write
        // is UNPROTECTED (like a HUD overlay) and lands immediately, unlike :res(name)'s server round trip.
        // nil is DOCUMENTED here (end the hold), so it is the write and not the read: Args.passed, never
        // Args.written. The read half is the hold alone — a slot holding one of the game's own actions is
        // already named by :res(), and s:menugrid():get(that) is the Pagina for it.
        m.set("hold", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = a.arg1();
                LuaSlot h = handle(self, "hold");
                if(!Args.passed(a, 2)) {
                    AddonPagina p = BeltHold.held(h.user, h.index);
                    return (p == null) ? LuaValue.NIL : LuaPagina.of(owner, h.user, p.id);
                }
                LuaValue v = a.arg(2);
                if(v.isnil()) {
                    BeltHold.release(h.user, h.index);
                    return self;
                }
                LuaPagina ph = LuaPagina.resolve(v);
                if(ph == null)
                    throw new LuaError("slot:pagina(pagOrNil): expected the Pagina object"
                        + " " + CharApi.MG + ":add(id) handed you, or nil to end the hold, got " + v.typename());
                if(!ph.res.startsWith(AddonPagina.PREFIX))
                    throw new LuaError("slot:pagina(pagOrNil): \"" + ph.res + "\" is the client's own entry,"
                        + " and a slot is held for an entry your addon added (" + CharApi.MG + ":add(id)). To"
                        + " put one of the game's own actions on the bar, assign it: slot:res(name).");
                // The entry has to be in THIS character's menu: an entry added to another login's grid draws
                // nothing here, and the bar and the grid are that one character's pair.
                BeltHold.hold(h.user, h.index,
                              AddonPagina.owned(owner, h.user, ph.res, "slot:pagina(pagOrNil)"));
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
                // The modifiers are read before the bar is looked up: an argument the caller got wrong is
                // the caller's to hear about whether or not that slot happens to be empty right now.
                int mods = Args.optint(a, 2, "slot:use", "mods", null, 0);
                LuaSlot h = handle(self, "use");
                int n = h.index;
                // That character's own action-bar widget presses it, so the press lands on the bar it was
                // addressed at — a button on a character nobody is looking at is still that character's.
                GameUI g = AddonManager.gameui(h.user);
                if(g == null)
                    throw new LuaError("slot:use(): no game UI (that character is not in the world yet)");
                if((g.belt == null) || (n < 0) || (n >= g.belt.length))
                    throw new LuaError("slot:use(): slot index out of range (0.." + (SLOTS - 1) + "), got " + n);
                if(g.belt[n] == null)
                    throw new LuaError("slot:use(): slot " + n + " is empty (check slot:empty() first)");
                if(g.beltwdg == null)
                    throw new LuaError("slot:use(): no action-bar widget yet");
                g.beltwdg.act(n, new MenuGrid.Interaction(1, mods));
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
            throw new LuaError("slot:" + method + "() — use a COLON call on a Slot object ("
                + CharApi.AB + ":get(n), " + CharApi.AB + ":list()[i])");
        return h;
    }

    /** The LIVE belt slot behind a method's {@code self}: re-read every call, {@code null} when empty. */
    private static GameUI.BeltSlot belt(LuaValue self, String method) {
        LuaSlot h = handle(self, method);
        GameUI g = AddonManager.gameui(h.user);     // THAT character's bar, not the drawn one's
        if((g == null) || (g.belt == null) || (h.index < 0) || (h.index >= g.belt.length))
            return null;        // pre-HUD / mid-:reload — an unpopulated slot, not an error
        return g.belt[h.index];
    }

    // ---- the collection --------------------------------------------------------------------------

    /**
     * {@code s:actionbar()} — <b>that character's bar</b>, as the {@link LuaCollection} the section object IS:
     * {@code :get(n)} is one slot by its 1-based position, {@code :list(filter)} all 144 in that order (a
     * fresh 1-based array), {@code :count}/{@code :find} the usual pair. There is no {@code :add} or
     * {@code :remove}: the bar is a fixed 144-slot array and what changes is a slot's <i>content</i>
     * ({@code slot:res(name)}).
     *
     * <p>The 144 members are the array's shape rather than the HUD's, so they answer before that character is
     * in the world too — every one of them {@code :empty()}, which is what an unpopulated bar is.
     */
    static LuaValue collection(final Addon owner, final String user) {
        return LuaCollection.create(CharApi.AB, new LuaCollection.Source() {
            public List<LuaValue> members() {
                List<LuaValue> out = new ArrayList<LuaValue>(SLOTS);
                for(int n = 0; n < SLOTS; n++)
                    out.add(of(owner, user, n));
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
                    throw new LuaError(CharApi.AB + ":get(n): the key is the 1-based position"
                        + " slot:index() answers (1.." + SLOTS + "), got " + key.typename());
                int n = key.toint();
                // Bounded, unlike s:kin():get(id): the belt is a fixed array, so an out-of-range index is
                // a bug in the addon (a typo'd loop), never a slot that merely does not exist yet.
                //
                // 090: the key moved from the raw 0-based game index to the 1-based list position, so
                // s:actionbar():list()[n] == s:actionbar():get(n) holds. get(0) is the commonest thing an
                // addon written before that says, so the refusal names the change rather than the range.
                if(n == 0)
                    throw new LuaError(CharApi.AB + ":get(n): the key is the 1-based position"
                        + " slot:index() answers, so :list()[n] == :get(n) \u2014 :get(1) is the first slot."
                        + " The raw 0-based game index the server carries is slot:wire()");
                if((n < 1) || (n > SLOTS))
                    throw new LuaError(CharApi.AB + ":get(n): slot position out of range (1.."
                        + SLOTS + "), got " + n);
                return of(owner, user, n - 1);
            }

            /** The belt is a fixed array: every index in range is a slot, holding something or not. */
            public LuaCollection.Missing missing() {
                return LuaCollection.Missing.MINT;
            }

            /** The key is the 1-based position {@code slot:index()} answers. */
            public String keyName() {
                return "n";
            }
        }, null);
    }
}
