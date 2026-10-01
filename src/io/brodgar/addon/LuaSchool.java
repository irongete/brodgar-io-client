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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A <b>School object</b> — one saved-school slot of a character's combat-schools tab ({@code s:fight():school()},
 * 172.1): a line of the tab's save list. The server gives the character {@code FightWnd.nsave} slots when it
 * builds the tab, names the used ones with its {@code saved} message and marks the loaded one with {@code use}
 * ({@code FightWnd.usesave}, {@code 0} until the first). An unused slot is a place that holds nothing, so it is
 * {@code :empty()} rather than gone.
 *
 * <p><b>The intern key is the place</b> (account, 0-based slot), the {@link LuaCombatAction} shape: a stashed
 * school follows its slot through a save, a load and a rename, and the account is half the handle because every
 * character keeps its own list. Every read re-resolves the tab through that character's sheet
 * ({@link CharApi#fightwnd}) and reads the name under the tab's monitor: {@code saved} writes it from a loader
 * thread, and the tab's own rename from the UI thread.
 *
 * <p><b>The name is the one the tab paints</b>, read through {@code FightWnd.savename}, the {@code addon:}
 * reader of its private {@code saves[]}: {@code null} for an unused slot, and the server's name or the tab's
 * "Saved school n" for a used one. A rename typed into the tab reads at once, before any save carries it.
 */
public final class LuaSchool {
    /** The account whose save list this slot is in. */
    public final String user;
    /** The 0-based slot, what {@code school:wire()} answers and the tab's messages carry. */
    public final int slot;

    private LuaSchool(String user, int slot) {
        this.user = user;
        this.slot = slot;
    }

    /** {@code tostring(school)}: {@code School(<1-based position>)}. */
    public String toString() {
        return "School(" + (slot + 1) + ")";
    }

    /** An interned School object for {@code slot} of {@code user}'s save list, in {@code owner}'s env. */
    static LuaValue of(Addon owner, String user, int slot) {
        return owner.schools.of(user, slot);
    }

    /** The {@code LuaSchool} behind a Lua value, or {@code null} for anything else. */
    static LuaSchool resolve(LuaValue v) {
        if((v == null) || !v.isuserdata())
            return null;
        Object o = v.touserdata();
        return (o instanceof LuaSchool) ? (LuaSchool)o : null;
    }

    // ---- the per-addon intern cache + metatable ----------------------------------------------------

    /** One addon's School cache and metatable (its {@link Addon#schools}), keyed by account and slot. */
    static final class Cache {
        private final Map<String, Map<Integer, Ref>> live = new HashMap<String, Map<Integer, Ref>>();
        private final ReferenceQueue<LuaValue> dead = new ReferenceQueue<LuaValue>();
        private LuaValue mt;

        Cache(Addon owner) {
        }

        synchronized LuaValue of(String user, int slot) {
            drain();
            Map<Integer, Ref> bys = live.get(user);
            if(bys == null)
                live.put(user, bys = new HashMap<Integer, Ref>());
            Integer key = Integer.valueOf(slot);
            Ref r = bys.get(key);
            if(r != null) {
                LuaValue v = r.get();
                if(v != null)
                    return v;
                bys.remove(key);
            }
            LuaValue v = LuaValue.userdataOf(new LuaSchool(user, slot), meta());
            bys.put(key, new Ref(v, user, key, dead));
            return v;
        }

        private void drain() {
            Reference<? extends LuaValue> r;
            while((r = dead.poll()) != null) {
                Ref sr = (Ref)r;
                Map<Integer, Ref> bys = live.get(sr.user);
                if(bys == null)
                    continue;
                if(bys.get(sr.key) == sr)     // not already replaced by a fresh handle for the same slot
                    bys.remove(sr.key);
                if(bys.isEmpty())
                    live.remove(sr.user);
            }
        }

        private LuaValue meta() {
            if(mt == null)
                mt = buildMeta();
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

    // ---- the School metatable -----------------------------------------------------------------------

    private static LuaValue buildMeta() {
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, Refusal.closedIndex("school", methods(),
            "a school is one saved-school slot of the combat-schools tab"));
        mt.set("__name", LuaValue.valueOf("School"));
        mt.set("__tostring", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaSchool h = resolve(self);
                return LuaValue.valueOf((h == null) ? "School(?)" : h.toString());
            }
        });
        return mt;
    }

    private static LuaTable methods() {
        LuaTable m = new LuaTable();
        // index() — the 1-based position in the save list, the number :get(n) takes. Always answers.
        m.set("index", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                return LuaValue.valueOf(handle(Args.only(a, 0, "school:index"), "index").slot + 1);
            }
        });
        // wire() — the server's 0-based slot number, what the tab's messages carry. Always answers.
        m.set("wire", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                return LuaValue.valueOf(handle(Args.only(a, 0, "school:wire"), "wire").slot);
            }
        });
        // empty() — is the slot unused? True too before the tab has built, when no slot holds anything.
        m.set("empty", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaSchool h = handle(Args.only(a, 0, "school:empty"), "empty");
                return LuaValue.valueOf(savedName(h.user, h.slot) == null);
            }
        });
        // name() — the name the tab paints; nil for an unused slot.
        m.set("name", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaSchool h = handle(Args.only(a, 0, "school:name"), "name");
                String n = savedName(h.user, h.slot);
                return (n == null) ? LuaValue.NIL : LuaValue.valueOf(n);
            }
        });
        // info() — the one SNAPSHOT escape hatch: {name}, nil for an unused slot (the Slot shape).
        m.set("info", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaSchool h = handle(Args.only(a, 0, "school:info"), "info");
                String n = savedName(h.user, h.slot);
                if(n == null)
                    return LuaValue.NIL;
                LuaTable t = new LuaTable();
                t.set("name", LuaValue.valueOf(n));
                return t;
            }
        });
        return m;
    }

    /** The handle behind a method's {@code self}, or a guiding error (a dot-call passes the wrong self). */
    private static LuaSchool handle(LuaValue self, String method) {
        LuaSchool h = resolve(self);
        if(h == null)
            throw new LuaError("school:" + method + "() — use a COLON call on a School object ("
                + CharApi.FS + ":get(n), " + CharApi.FS + ":list()[n])");
        return h;
    }

    // ---- the reads ----------------------------------------------------------------------------------

    /**
     * The name of slot {@code slot} in that character's save list, or {@code null} for an unused slot, a slot
     * past the list and no tab. Read under the tab's monitor: {@code saved} writes the names off-thread.
     */
    private static String savedName(String user, int slot) {
        FightWnd fw = CharApi.fightwnd(user);
        if(fw == null)
            return null;
        synchronized(LuaWidget.monitor(fw)) {
            return ((slot < 0) || (slot >= fw.nsave)) ? null : fw.savename(slot);
        }
    }

    // ---- the collection -----------------------------------------------------------------------------

    /**
     * {@code s:fight():school()} — every slot of THAT character's save list, in the list's order, every one of
     * them a School whatever it holds; none before the tab has built. {@code :get(n)} takes the 1-based position
     * and answers {@code nil} outside the list (0 included): the length is the server's, as the speed selector's
     * is, so a number past it is a miss rather than a mistake. A string filter matches the name. {@code
     * :current()} is the slot the tab marks as loaded. Minted once per (addon, session) by {@code CharApi.fight}.
     */
    static LuaValue collection(final Addon owner, final String user) {
        LuaTable extra = new LuaTable();
        // current() — the school the tab marks as loaded, as the very member :list() holds, so
        // `s:fight():school():current() == school` is the "is this one loaded" test. It may be an unused slot:
        // usesave is 0 until the server's first "use". nil before the tab has built.
        extra.set("current", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaCollection.receiver(a.arg1(), CharApi.FS, "current");
                if(Args.passed(a, 2))
                    throw new LuaError(CharApi.FS + ":current() takes no argument — it ADDRESSES the school the"
                        + " tab marks as loaded, and a member address is not a property to write: loading one is"
                        + " the tab's Load button");
                FightWnd fw = CharApi.fightwnd(user);
                if(fw == null)
                    return LuaValue.NIL;
                int cur;
                synchronized(LuaWidget.monitor(fw)) {   // usesave is written from the "use" uimsg off-thread
                    cur = fw.usesave;
                }
                return ((cur < 0) || (cur >= fw.nsave)) ? LuaValue.NIL : of(owner, user, cur);
            }
        });
        return LuaCollection.create(CharApi.FS, new LuaCollection.Source() {
            public List<LuaValue> members() {
                FightWnd fw = CharApi.fightwnd(user);
                int n = (fw == null) ? 0 : fw.nsave;
                List<LuaValue> out = new ArrayList<LuaValue>(n);
                for(int i = 0; i < n; i++)
                    out.add(of(owner, user, i));
                return out;
            }

            // An UNUSED slot has no name: it matches no string filter, "" included, rather than refusing it.
            public String needle(LuaValue member) {
                LuaSchool h = resolve(member);
                return (h == null) ? null : savedName(h.user, h.slot);
            }

            /** These have a name, so a string filter is a substring test over {@link #needle}. */
            public boolean named() {
                return true;
            }

            public boolean addressable() {
                return true;
            }

            public LuaValue getMember(LuaValue key) {
                int n = Args.integer(key, CharApi.FS + ":get", "n", "the 1-based position school:index() answers;"
                                     + " a name is a search, " + CharApi.FS + ":find(filter)");
                FightWnd fw = CharApi.fightwnd(user);
                if((fw == null) || (n < 1) || (n > fw.nsave))
                    return LuaValue.NIL;
                return of(owner, user, n - 1);
            }

            /** The key is the 1-based position {@code school:index()} answers. */
            public String keyName() {
                return "n";
            }
        }, extra);
    }
}
