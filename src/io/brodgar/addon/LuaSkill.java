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
 * A <b>Skill object</b> — one entry of the character sheet's "Lore &amp; Skills" tab, known or buyable
 * ({@code hafen.char():skill()}). One type covers both groups, because they are one set the server
 * partitions: buying a skill moves it from <i>available</i> to <i>known</i> without it becoming a different
 * thing, and an addon that stashed the handle goes on reading the same skill afterwards.
 *
 * <p><b>The intern key is the server's own skill token</b> ({@code SkillWnd.Skill.nm}, D-094). The
 * {@code Skill} records themselves are rebuilt wholesale off-thread every time the server resends a group,
 * so their Java identity is worthless as a key; the token is what the server addresses them by and it
 * survives the swap. Every read re-resolves through that token, so a handle tracks its own skill across a
 * rebuild — including the move from buyable to known, which flips {@code :known()} and nothing else.
 *
 * <p><b>The display name may be {@code nil} for a beat.</b> It comes from the skill's resource tooltip, and
 * a resource still {@code Loading} has none; the token behind {@code :name()} is used as the fallback, so
 * {@code :name()} in practice always answers while {@code :res()} may not.
 */
public final class LuaSkill {
    /** The server's skill token — the whole state of a handle, and its intern key. */
    public final String token;

    private LuaSkill(String token) {
        this.token = token;
    }

    /** {@code tostring(skill)}: {@code Skill(<token>)}. */
    public String toString() {
        return "Skill(" + token + ")";
    }

    /** An interned Skill object for the token {@code nm} in {@code owner}'s env. */
    static LuaValue of(Addon owner, String nm) {
        return owner.skills.of(nm);
    }

    /** The {@code LuaSkill} behind a Lua value, or {@code null} for anything that is not a Skill object. */
    static LuaSkill resolve(LuaValue v) {
        if((v == null) || !v.isuserdata())
            return null;
        Object o = v.touserdata();
        return (o instanceof LuaSkill) ? (LuaSkill)o : null;
    }

    // ---- the per-addon intern cache + metatable ---------------------------------------------------

    /** One addon's Skill interning cache and metatable (its {@link Addon#skills}), keyed by the token. */
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
            LuaValue v = LuaValue.userdataOf(new LuaSkill(nm), meta());
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

    // ---- the Skill metatable ------------------------------------------------------------------------

    private static LuaValue buildMeta() {
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, Retired.methodIndex("skill", methods()));
        mt.set("__name", LuaValue.valueOf("Skill"));
        mt.set("__tostring", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaSkill h = resolve(self);
                return LuaValue.valueOf((h == null) ? "Skill(?)" : h.toString());
            }
        });
        return mt;
    }

    private static LuaTable methods() {
        LuaTable m = new LuaTable();
        // name() — the display name from the skill's resource tooltip, falling back to the server's token.
        m.set("name", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaSkill h = handle(self, "name");
                SkillWnd.Skill s = find(h.token);
                return (s == null) ? LuaValue.valueOf(h.token)
                                   : LuaValue.valueOf(AddonManager.resTipName(s.res, h.token));
            }
        });
        // res() — the icon resource name (stable identity), or nil while it is still Loading.
        m.set("res", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                SkillWnd.Skill s = find(handle(self, "res").token);
                String r = (s == null) ? null : AddonManager.resIdent(s.res);
                return (r == null) ? LuaValue.NIL : LuaValue.valueOf(r);
            }
        });
        // cost() — the learning-point price the server published for this skill, or nil once it is gone.
        m.set("cost", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                SkillWnd.Skill s = find(handle(self, "cost").token);
                return (s == null) ? LuaValue.NIL : LuaValue.valueOf(s.cost);
            }
        });
        // known() — is it learnt, rather than merely buyable? Buying one flips this and nothing else.
        m.set("known", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                SkillWnd.Skill s = find(handle(self, "known").token);
                return LuaValue.valueOf((s != null) && s.has);
            }
        });
        // exists() — is this skill still listed at all? False once the window is gone or the server drops it.
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

    private static LuaSkill handle(LuaValue self, String method) {
        LuaSkill h = resolve(self);
        if(h == null)
            throw new LuaError("skill:" + method + "() — use a COLON call on a Skill object"
                + " (hafen.char():skill():list()[i], :find(name), :available()[i])");
        return h;
    }

    // ---- the reads (all Loading-guarded, all through the token) --------------------------------------

    /** The character sheet's "Lore &amp; Skills" widget, or {@code null}. */
    static SkillWnd skillwnd() {
        return CharApi.skillwnd();
    }

    /** The live record for a token, known group first, or {@code null} — the one resolve funnel (D-012). */
    static SkillWnd.Skill find(String token) {
        SkillWnd w = skillwnd();
        if((w == null) || (token == null))
            return null;
        try {
            for(SkillWnd.Skill s : new ArrayList<SkillWnd.Skill>(w.skg.csk.items)) {
                if(token.equals(s.nm))
                    return s;
            }
            for(SkillWnd.Skill s : new ArrayList<SkillWnd.Skill>(w.skg.nsk.items)) {
                if(token.equals(s.nm))
                    return s;
            }
        } catch(RuntimeException e) {
            /* the group list was swapped mid-read — treat as not found; the next read sees it */
        }
        return null;
    }

    /** A copy of one group's tokens, in the window's own order ({@code known} = the learnt group). */
    static List<String> tokens(boolean known) {
        List<String> out = new ArrayList<String>();
        SkillWnd w = skillwnd();
        if(w == null)
            return out;
        try {
            for(SkillWnd.Skill s : new ArrayList<SkillWnd.Skill>(known ? w.skg.csk.items : w.skg.nsk.items))
                out.add(s.nm);
        } catch(RuntimeException e) {
            /* not ready or swapped mid-read — return what we have */
        }
        return out;
    }

    /** The documented {@code Skill} snapshot {@code {name, res, cost, known}}, or nil once it is gone. */
    static LuaValue snapshot(String token) {
        SkillWnd.Skill s = find(token);
        if(s == null)
            return LuaValue.NIL;
        LuaTable t = new LuaTable();
        t.set("name", LuaValue.valueOf(AddonManager.resTipName(s.res, token)));
        String res = AddonManager.resIdent(s.res);
        if(res != null)
            t.set("res", LuaValue.valueOf(res));
        t.set("cost", LuaValue.valueOf(s.cost));
        t.set("known", LuaValue.valueOf(s.has));
        return t;
    }

    /** The text a string filter matches: display name and resource name, one substring test over both. */
    static String needleOf(LuaValue member) {
        LuaSkill h = resolve(member);
        if(h == null)
            return "";
        SkillWnd.Skill s = find(h.token);
        String res = (s == null) ? null : AddonManager.resIdent(s.res);
        String name = (s == null) ? h.token : AddonManager.resTipName(s.res, h.token);
        return ((name == null) ? "" : name) + "\n" + ((res == null) ? "" : res);
    }

    // ---- the collection ------------------------------------------------------------------------------

    /**
     * {@code hafen.char():skill()} — the skills the character <b>knows</b>, with the buyable ones a verb
     * away. {@code :available(filter)} is the second group: a distinguished sub-list is a verb on the
     * collection (§2.3), never a second accessor, which is what the flat {@code skillsAvailable()} was.
     *
     * <p><b>There is no {@code :get}</b>: a skill's only key is the server's internal token, which is not
     * what anyone writes. {@code :find(name)} searches the display name and the resource, exactly as the
     * flat membership test did — and it now hands back the skill rather than a boolean.
     */
    static LuaValue collection(final Addon owner) {
        LuaTable extra = new LuaTable();
        extra.set("available", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaCollection.receiver(a.arg1(), "available");
                LuaValue filter = a.arg(2);
                LuaTable t = new LuaTable();
                int n = 0;
                for(String nm : tokens(false)) {
                    LuaValue member = of(owner, nm);
                    if(LuaCollection.keeps(filter, member, needleOf(member),
                                           "hafen.char():skill()", "available"))
                        t.set(++n, member);
                }
                return t;
            }
        });
        return LuaCollection.create("hafen.char():skill()", new LuaCollection.Source() {
            public List<LuaValue> members() {
                List<LuaValue> out = new ArrayList<LuaValue>();
                for(String nm : tokens(true))
                    out.add(of(owner, nm));
                return out;
            }

            public String needle(LuaValue member) {
                return needleOf(member);
            }
        }, extra);
    }
}
