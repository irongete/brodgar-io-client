package io.brodgar.addon;

import haven.SkillWnd;

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
 * A <b>Credo object</b> — one entry of the character sheet's Credos tab ({@code hafen.char():credo()}),
 * acquired or available. Interned on the server's own credo token ({@code SkillWnd.Credo.nm}, D-094) for
 * the same reason {@link LuaSkill} is: the records are rebuilt wholesale off-thread whenever the server
 * resends a group, so their Java identity is worthless as a key while the token survives every swap.
 *
 * <p><b>The credo being PURSUED is one of these, with five more reads that answer.</b> The flat reader had
 * it as a table of its own beside two lists, which made "the credo I am pursuing" a different shape from
 * "a credo" — so an addon could not compare them. Here it is the same object, {@code :pursuing()} says so,
 * and the progress reads ({@code :level()}, {@code :quest()}, …) answer {@code nil} on every other credo.
 *
 * <p><b>The cost of beginning one belongs to the set, not to a member</b>: the server publishes a single
 * learning-point price for taking up any credo, so it is {@code hafen.char():credo():cost()} rather than a
 * verb on a credo that would report the same number nine times.
 */
public final class LuaCredo {
    /** The server's credo token — the whole state of a handle, and its intern key. */
    public final String token;

    private LuaCredo(String token) {
        this.token = token;
    }

    /** {@code tostring(credo)}: {@code Credo(<token>)}. */
    public String toString() {
        return "Credo(" + token + ")";
    }

    /** An interned Credo object for the token {@code nm} in {@code owner}'s env. */
    static LuaValue of(Addon owner, String nm) {
        return owner.credos.of(nm);
    }

    /** The {@code LuaCredo} behind a Lua value, or {@code null} for anything that is not a Credo object. */
    static LuaCredo resolve(LuaValue v) {
        if((v == null) || !v.isuserdata())
            return null;
        Object o = v.touserdata();
        return (o instanceof LuaCredo) ? (LuaCredo)o : null;
    }

    // ---- the per-addon intern cache + metatable ---------------------------------------------------

    /** One addon's Credo interning cache and metatable (its {@link Addon#credos}), keyed by the token. */
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
            LuaValue v = LuaValue.userdataOf(new LuaCredo(nm), meta());
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

    // ---- the Credo metatable ------------------------------------------------------------------------

    private static LuaValue buildMeta() {
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, Retired.methodIndex("credo", methods()));
        mt.set("__name", LuaValue.valueOf("Credo"));
        mt.set("__tostring", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaCredo h = resolve(self);
                return LuaValue.valueOf((h == null) ? "Credo(?)" : h.toString());
            }
        });
        return mt;
    }

    private static LuaTable methods() {
        LuaTable m = new LuaTable();
        // name() — the display name from the credo's resource tooltip, falling back to the server's token.
        m.set("name", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaCredo h = handle(self, "name");
                SkillWnd.Credo c = find(h.token);
                return (c == null) ? LuaValue.valueOf(h.token)
                                   : LuaValue.valueOf(AddonManager.resTipName(c.res, h.token));
            }
        });
        // res() — the icon resource name (stable identity), or nil while it is still Loading.
        m.set("res", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                SkillWnd.Credo c = find(handle(self, "res").token);
                String r = (c == null) ? null : AddonManager.resIdent(c.res);
                return (r == null) ? LuaValue.NIL : LuaValue.valueOf(r);
            }
        });
        // acquired() — has the character completed this credo?
        m.set("acquired", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                SkillWnd.Credo c = find(handle(self, "acquired").token);
                return LuaValue.valueOf((c != null) && c.has);
            }
        });
        // pursuing() — is this the credo currently being pursued? Only that one answers the five below.
        m.set("pursuing", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(pursued(handle(self, "pursuing").token));
            }
        });
        m.set("level", progress("level", 0));
        m.set("levelTotal", progress("levelTotal", 1));
        m.set("quest", progress("quest", 2));
        m.set("questTotal", progress("questTotal", 3));
        m.set("questId", progress("questId", 4));
        // exists() — is this credo still listed at all?
        m.set("exists", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(find(handle(self, "exists").token) != null);
            }
        });
        // info() — the one SNAPSHOT escape hatch.
        m.set("info", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return snapshot(handle(self, "info").token);
            }
        });
        return m;
    }

    /** One of the five pursuit numbers: it answers on the pursued credo and {@code nil} on every other. */
    private static OneArgFunction progress(final String verb, final int which) {
        return new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaCredo h = handle(self, verb);
                if(!pursued(h.token))
                    return LuaValue.NIL;
                SkillWnd.CredoGrid cg = grid();
                if(cg == null)
                    return LuaValue.NIL;
                switch(which) {
                case 0: return LuaValue.valueOf(cg.pcl);
                case 1: return LuaValue.valueOf(cg.pclt);
                case 2: return LuaValue.valueOf(cg.pcql);
                case 3: return LuaValue.valueOf(cg.pcqlt);
                default: return LuaValue.valueOf(cg.pqid);
                }
            }
        };
    }

    private static LuaCredo handle(LuaValue self, String method) {
        LuaCredo h = resolve(self);
        if(h == null)
            throw new LuaError("credo:" + method + "() — use a COLON call on a Credo object"
                + " (hafen.char():credo():list()[i], :find(name), :pursuing())");
        return h;
    }

    // ---- the reads ----------------------------------------------------------------------------------

    /** The Credos tab of the "Lore &amp; Skills" window, or {@code null}. */
    static SkillWnd.CredoGrid grid() {
        SkillWnd w = CharApi.skillwnd();
        return (w == null) ? null : w.credos;
    }

    /** The live record for a token — acquired, then available, then the pursued one — or {@code null}. */
    static SkillWnd.Credo find(String token) {
        SkillWnd.CredoGrid cg = grid();
        if((cg == null) || (token == null))
            return null;
        try {
            for(SkillWnd.Credo c : new ArrayList<SkillWnd.Credo>(cg.ccr)) {
                if(token.equals(c.nm))
                    return c;
            }
            for(SkillWnd.Credo c : new ArrayList<SkillWnd.Credo>(cg.ncr)) {
                if(token.equals(c.nm))
                    return c;
            }
            SkillWnd.Credo p = cg.pcr;
            if((p != null) && token.equals(p.nm))
                return p;
        } catch(RuntimeException e) {
            /* the lists were swapped mid-read — treat as not found; the next read sees it */
        }
        return null;
    }

    /** Is {@code token} the credo currently being pursued? */
    static boolean pursued(String token) {
        SkillWnd.CredoGrid cg = grid();
        SkillWnd.Credo p = (cg == null) ? null : cg.pcr;
        return (p != null) && (token != null) && token.equals(p.nm);
    }

    /** Every credo token the tab holds, acquired first, then available, then the pursued one if apart. */
    static List<String> tokens() {
        List<String> out = new ArrayList<String>();
        SkillWnd.CredoGrid cg = grid();
        if(cg == null)
            return out;
        try {
            for(SkillWnd.Credo c : new ArrayList<SkillWnd.Credo>(cg.ccr))
                out.add(c.nm);
            for(SkillWnd.Credo c : new ArrayList<SkillWnd.Credo>(cg.ncr))
                out.add(c.nm);
            SkillWnd.Credo p = cg.pcr;
            if((p != null) && !out.contains(p.nm))
                out.add(p.nm);
        } catch(RuntimeException e) {
            /* swapped mid-read — return what we have */
        }
        return out;
    }

    /** The documented {@code Credo} snapshot, or nil once it is gone. Pursuit fields only on the pursued. */
    static LuaValue snapshot(String token) {
        SkillWnd.Credo c = find(token);
        if(c == null)
            return LuaValue.NIL;
        LuaTable t = new LuaTable();
        t.set("name", LuaValue.valueOf(AddonManager.resTipName(c.res, token)));
        String res = AddonManager.resIdent(c.res);
        if(res != null)
            t.set("res", LuaValue.valueOf(res));
        t.set("acquired", LuaValue.valueOf(c.has));
        t.set("pursuing", LuaValue.valueOf(pursued(token)));
        SkillWnd.CredoGrid cg = grid();
        if(pursued(token) && (cg != null)) {
            t.set("level", LuaValue.valueOf(cg.pcl));
            t.set("levelTotal", LuaValue.valueOf(cg.pclt));
            t.set("quest", LuaValue.valueOf(cg.pcql));
            t.set("questTotal", LuaValue.valueOf(cg.pcqlt));
            t.set("questId", LuaValue.valueOf(cg.pqid));
        }
        return t;
    }

    /** The text a string filter matches: display name and resource name. */
    static String needleOf(LuaValue member) {
        LuaCredo h = resolve(member);
        if(h == null)
            return "";
        SkillWnd.Credo c = find(h.token);
        String res = (c == null) ? null : AddonManager.resIdent(c.res);
        String name = (c == null) ? h.token : AddonManager.resTipName(c.res, h.token);
        return ((name == null) ? "" : name) + "\n" + ((res == null) ? "" : res);
    }

    // ---- the collection ------------------------------------------------------------------------------

    /**
     * {@code hafen.char():credo()} — every credo the tab lists, acquired and available together, with
     * {@code cr:acquired()} saying which. {@code :pursuing()} is the distinguished member (§2.3) and
     * {@code :cost()} the learning-point price of beginning one.
     */
    static LuaValue collection(final Addon owner) {
        LuaTable extra = new LuaTable();
        extra.set("pursuing", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaCollection.receiver(a.arg1(), "pursuing");
                SkillWnd.CredoGrid cg = grid();
                SkillWnd.Credo p = (cg == null) ? null : cg.pcr;
                return (p == null) ? LuaValue.NIL : of(owner, p.nm);
            }
        });
        extra.set("cost", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaCollection.receiver(a.arg1(), "cost");
                SkillWnd.CredoGrid cg = grid();
                return (cg == null) ? LuaValue.NIL : LuaValue.valueOf(cg.cost);
            }
        });
        return LuaCollection.create("hafen.char():credo()", new LuaCollection.Source() {
            public List<LuaValue> members() {
                List<LuaValue> out = new ArrayList<LuaValue>();
                for(String nm : tokens())
                    out.add(of(owner, nm));
                return out;
            }

            public String needle(LuaValue member) {
                return needleOf(member);
            }
        }, extra);
    }
}
