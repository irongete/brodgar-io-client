package io.brodgar.addon;

import haven.SkillWnd;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.OneArgFunction;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * An <b>Experience object</b> — one entry of the character sheet's Lore tab
 * ({@code hafen.char():experience()}): a piece of lore the character has seen, its score, and when the
 * server last touched it.
 *
 * <p><b>The intern key is the resource name</b> (D-094). An experience carries no token of its own — the
 * resource <i>is</i> its identity, which is also the only thing about it that never changes — and the
 * records are rebuilt wholesale whenever the server resends the tab, so Java identity would hand back a
 * different object for the same piece of lore every time.
 *
 * <p><b>A lore entry appears once its resource resolves.</b> The name is the resource's tooltip and the key
 * is the resource's name, so an entry whose resource is still loading has neither and is simply not listed
 * yet; the next read has it. That is the same beat every other character-sheet read takes, one field
 * deeper.
 */
public final class LuaExperience {
    /** The resource name this handle addresses — the whole state of a handle, and its intern key. */
    public final String res;

    private LuaExperience(String res) {
        this.res = res;
    }

    /** {@code tostring(exp)}: {@code Experience(<res>)}. */
    public String toString() {
        return "Experience(" + res + ")";
    }

    /** An interned Experience object for the resource name {@code nm} in {@code owner}'s env. */
    static LuaValue of(Addon owner, String nm) {
        return owner.experiences.of(nm);
    }

    /** The {@code LuaExperience} behind a Lua value, or {@code null} for anything else. */
    static LuaExperience resolve(LuaValue v) {
        if((v == null) || !v.isuserdata())
            return null;
        Object o = v.touserdata();
        return (o instanceof LuaExperience) ? (LuaExperience)o : null;
    }

    // ---- the per-addon intern cache + metatable ---------------------------------------------------

    /** One addon's Experience cache and metatable (its {@link Addon#experiences}), keyed by resource name. */
    static final class Cache {
        private final Map<String, Ref> live = new HashMap<String, Ref>();
        private final ReferenceQueue<LuaValue> dead = new ReferenceQueue<LuaValue>();
        private LuaValue mt;

        Cache(Addon owner) {
        }

        synchronized LuaValue of(String nm) {
            drain();
            if(nm == null)
                return LuaValue.NIL;
            Ref r = live.get(nm);
            if(r != null) {
                LuaValue v = r.get();
                if(v != null)
                    return v;
                live.remove(nm);
            }
            LuaValue v = LuaValue.userdataOf(new LuaExperience(nm), meta());
            live.put(nm, new Ref(v, nm, dead));
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
                mt = buildMeta();
            return mt;
        }
    }

    private static final class Ref extends WeakReference<LuaValue> {
        final String key;

        Ref(LuaValue v, String key, ReferenceQueue<LuaValue> q) {
            super(v, q);
            this.key = key;
        }
    }

    // ---- the Experience metatable -------------------------------------------------------------------

    private static LuaValue buildMeta() {
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, Retired.methodIndex("experience", methods()));
        mt.set("__name", LuaValue.valueOf("Experience"));
        mt.set("__tostring", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaExperience h = resolve(self);
                return LuaValue.valueOf((h == null) ? "Experience(?)" : h.toString());
            }
        });
        return mt;
    }

    private static LuaTable methods() {
        LuaTable m = new LuaTable();
        // name() — the display name from the lore resource's tooltip, falling back to the resource name.
        m.set("name", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaExperience h = handle(self, "name");
                SkillWnd.Experience e = find(h.res);
                String nm = (e == null) ? h.res : AddonManager.resTipName(e.res, h.res);
                return (nm == null) ? LuaValue.NIL : LuaValue.valueOf(nm);
            }
        });
        // res() — the lore resource name, this entry's stable identity. Always answers.
        m.set("res", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(handle(self, "res").res);
            }
        });
        // score() — the experience points this lore is worth, or nil once it is no longer listed.
        m.set("score", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                SkillWnd.Experience e = find(handle(self, "score").res);
                return (e == null) ? LuaValue.NIL : LuaValue.valueOf(e.score);
            }
        });
        // modified() — the server's own time field for this entry, passed through unchanged.
        m.set("modified", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                SkillWnd.Experience e = find(handle(self, "modified").res);
                return (e == null) ? LuaValue.NIL : LuaValue.valueOf(e.mtime);
            }
        });
        // exists() — is this lore still listed?
        m.set("exists", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(find(handle(self, "exists").res) != null);
            }
        });
        // info() — the one SNAPSHOT escape hatch.
        m.set("info", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return snapshot(handle(self, "info").res);
            }
        });
        return m;
    }

    private static LuaExperience handle(LuaValue self, String method) {
        LuaExperience h = resolve(self);
        if(h == null)
            throw new LuaError("experience:" + method + "() — use a COLON call on an Experience object"
                + " (hafen.char():experience():list()[i], :find(name))");
        return h;
    }

    // ---- the reads ----------------------------------------------------------------------------------

    /** The live record for a resource name, or {@code null} — the one resolve funnel (D-012). */
    static SkillWnd.Experience find(String nm) {
        SkillWnd w = CharApi.skillwnd();
        if((w == null) || (nm == null))
            return null;
        try {
            for(SkillWnd.Experience e : new ArrayList<SkillWnd.Experience>(w.exps.seen.items)) {
                if(nm.equals(AddonManager.resIdent(e.res)))
                    return e;
            }
        } catch(RuntimeException ex) {
            /* not ready or swapped mid-read — treat as not found */
        }
        return null;
    }

    /** Every listed lore resource name, in the tab's own order; an unresolved one is not listed yet. */
    static List<String> names() {
        List<String> out = new ArrayList<String>();
        SkillWnd w = CharApi.skillwnd();
        if(w == null)
            return out;
        try {
            for(SkillWnd.Experience e : new ArrayList<SkillWnd.Experience>(w.exps.seen.items)) {
                String nm = AddonManager.resIdent(e.res);
                if(nm != null)
                    out.add(nm);
            }
        } catch(RuntimeException ex) {
            /* not ready or swapped mid-read — return what we have */
        }
        return out;
    }

    /** The documented {@code Experience} snapshot {@code {name, res, score, mtime}}, or nil once gone. */
    static LuaValue snapshot(String nm) {
        SkillWnd.Experience e = find(nm);
        if(e == null)
            return LuaValue.NIL;
        LuaTable t = new LuaTable();
        String disp = AddonManager.resTipName(e.res, nm);
        if(disp != null)
            t.set("name", LuaValue.valueOf(disp));
        t.set("res", LuaValue.valueOf(nm));
        t.set("score", LuaValue.valueOf(e.score));
        t.set("mtime", LuaValue.valueOf(e.mtime));
        return t;
    }

    // ---- the collection ------------------------------------------------------------------------------

    /**
     * {@code hafen.char():experience()} — the lore the character has seen. There is no {@code :get}: the
     * key is a full resource path nobody types, and {@code :find(name)} searches the display name and the
     * resource together, which is the question anyone actually has.
     */
    static LuaValue collection(final Addon owner) {
        return LuaCollection.create("hafen.char():experience()", new LuaCollection.Source() {
            public List<LuaValue> members() {
                List<LuaValue> out = new ArrayList<LuaValue>();
                for(String nm : names())
                    out.add(of(owner, nm));
                return out;
            }

            public String needle(LuaValue member) {
                LuaExperience h = resolve(member);
                if(h == null)
                    return "";
                SkillWnd.Experience e = find(h.res);
                String disp = (e == null) ? null : AddonManager.resTipName(e.res, h.res);
                return ((disp == null) ? "" : disp) + "\n" + h.res;
            }

            /** These have a name, so a string filter is a substring test over {@link #needle}. */
            public boolean named() {
                return true;
            }
        }, null);
    }
}
