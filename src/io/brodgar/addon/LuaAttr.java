package io.brodgar.addon;

import haven.Glob;

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
 * An <b>Attr object</b> — one of the character's base attributes ({@code s:char():attr():get("str")}),
 * the OOP successor of the flat {@code {base, comp}} snapshot. Built on the mechanism {@link LuaGob},
 * {@link LuaKin} and {@link LuaBuff} established: userdata + a per-addon weak-valued intern cache, so
 * {@code ==} is the identity test and a stashed handle stays live.
 *
 * <p><b>The intern key is the attribute's NAME</b> (D-094: the engine's own stability). {@link Glob.CAttr}
 * is mutated in place as the server pushes new values and is auto-created by {@link Glob#getcattr} for any
 * name, so the record is neither stable nor a key — the name is both, and it is what the caller writes.
 *
 * <p><b>The set of names is CLOSED, so an unknown one is refused rather than answered.</b> The nine base
 * attributes are content-defined and are not discoverable from {@link Glob}, so this bridge holds the list;
 * a name outside it can never mean anything, and answering it with a live-looking handle whose every read is
 * {@code nil} would turn a typo into a silent nothing. The refusal enumerates the nine, which is a fix
 * rather than a diagnosis.
 *
 * <p><b>A known name is never {@code nil}</b> (the {@code hafen.kin():get(<id>)} asymmetry): the attribute
 * exists whether or not the server has published it yet, and it is {@code :base()}/{@code :composite()} that
 * answer {@code nil} until it has. {@code Glob.getcattr} auto-creates a zero entry for a name it has never
 * seen, so {@code base == 0 && comp == 0} is read as <i>not published</i> — which is what the flat reader
 * reported as a {@code nil} attribute.
 */
public final class LuaAttr {
    /** The nine base character attributes (content-defined; not discoverable from {@link Glob}). */
    static final String[] NAMES =
        {"str", "agi", "int", "con", "prc", "csm", "dex", "wil", "psy"};

    /** The account whose sheet this attribute is on — half the address. */
    public final String user;
    /** The attribute name this handle addresses. */
    public final String name;

    private LuaAttr(String user, String name) {
        this.user = user;
        this.name = name;
    }

    /** {@code tostring(attr)}: {@code Attr(str)}. */
    public String toString() {
        return "Attr(" + name + ")";
    }

    /** An interned Attr object for {@code name} <b>on session {@code user}</b>, in {@code owner}'s env. */
    static LuaValue of(Addon owner, String user, String name) {
        return owner.attrs.of(user, name);
    }

    /** The {@code LuaAttr} behind a Lua value, or {@code null} for anything that is not an Attr object. */
    static LuaAttr resolve(LuaValue v) {
        if((v == null) || !v.isuserdata())
            return null;
        Object o = v.touserdata();
        return (o instanceof LuaAttr) ? (LuaAttr)o : null;
    }

    /** Is {@code name} one of the nine? */
    static boolean known(String name) {
        for(String nm : NAMES) {
            if(nm.equals(name))
                return true;
        }
        return false;
    }

    /** The nine names as one comma-separated list, for the refusal. */
    static String namesList() {
        StringBuilder b = new StringBuilder();
        for(int i = 0; i < NAMES.length; i++)
            b.append((i == 0) ? "" : ", ").append(NAMES[i]);
        return b.toString();
    }

    // ---- the per-addon intern cache + metatable ---------------------------------------------------

    /**
     * One addon's Attr interning cache and metatable (its {@link Addon#attrs}), keyed by the <b>account plus</b>
     * the attribute name: {@code "str"} names a different number on each character, so the two must be two
     * handles. Two levels of map, the {@link LuaGob} shape.
     */
    static final class Cache {
        private final Map<String, Map<String, Ref>> live = new HashMap<String, Map<String, Ref>>();
        private final ReferenceQueue<LuaValue> dead = new ReferenceQueue<LuaValue>();
        private LuaValue mt;

        Cache(Addon owner) {
        }

        synchronized LuaValue of(String user, String nm) {
            drain();
            if(nm == null)
                return LuaValue.NIL;
            Map<String, Ref> byname = live.get(user);
            if(byname == null)
                live.put(user, byname = new HashMap<String, Ref>());
            Ref r = byname.get(nm);
            if(r != null) {
                LuaValue v = r.get();
                if(v != null)
                    return v;
                byname.remove(nm);
            }
            LuaValue v = LuaValue.userdataOf(new LuaAttr(user, nm), meta());
            byname.put(nm, new Ref(v, user, nm, dead));
            return v;
        }

        /** Drop the entries whose handle Lua has released (mandatory on every access: the keys are strong). */
        private void drain() {
            Reference<? extends LuaValue> r;
            while((r = dead.poll()) != null) {
                Ref br = (Ref)r;
                Map<String, Ref> byname = live.get(br.user);
                if(byname == null)
                    continue;
                if(byname.get(br.key) == br)
                    byname.remove(br.key);
                if(byname.isEmpty())
                    live.remove(br.user);
            }
        }

        private LuaValue meta() {
            if(mt == null)
                mt = buildMeta();
            return mt;
        }
    }

    /** A weak handle reference that remembers its map key, so the {@link ReferenceQueue} drain can unmap it. */
    private static final class Ref extends WeakReference<LuaValue> {
        final String user;
        final String key;

        Ref(LuaValue v, String user, String key, ReferenceQueue<LuaValue> q) {
            super(v, q);
            this.user = user;
            this.key = key;
        }
    }

    // ---- the Attr metatable -------------------------------------------------------------------------

    private static LuaValue buildMeta() {
        LuaTable mt = new LuaTable();
        LuaTable m = methods();
        // Refusal.closedIndex, never the methods table itself: a verb this migration renamed must throw
        // naming its replacement rather than read as plain nil and fail one character later.
        mt.set(LuaValue.INDEX, Refusal.closedIndex("attr", m,
            "a character attribute"));
        mt.set("__name", LuaValue.valueOf("Attr"));
        mt.set("__tostring", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaAttr h = resolve(self);
                return LuaValue.valueOf((h == null) ? "Attr(?)" : h.toString());
            }
        });
        return mt;
    }

    private static LuaTable methods() {
        LuaTable m = new LuaTable();
        // name() — which attribute this is ("str"). Always answers: it is the handle's whole state.
        m.set("name", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(handle(self, "name").name);
            }
        });
        // base() — the raw base value, or nil until the server has published this attribute.
        m.set("base", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaAttr h = handle(self, "base");
                Glob.CAttr a = attr(h.user, h.name);
                return (a == null) ? LuaValue.NIL : LuaValue.valueOf(a.base);
            }
        });
        // composite() — the computed, buffed value: base plus whatever food, gear and buffs add to it.
        m.set("composite", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaAttr h = handle(self, "composite");
                Glob.CAttr a = attr(h.user, h.name);
                return (a == null) ? LuaValue.NIL : LuaValue.valueOf(a.comp);
            }
        });
        // info() — the one SNAPSHOT escape hatch, or nil while the attribute is unpublished.
        m.set("info", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaAttr h = handle(self, "info");
                return snapshot(h.user, h.name);
            }
        });
        return m;
    }

    /** The handle behind a method's {@code self}, or a guiding error (a dot call passes the wrong self). */
    private static LuaAttr handle(LuaValue self, String method) {
        LuaAttr h = resolve(self);
        if(h == null)
            throw new LuaError("attr:" + method + "() — use a COLON call on an Attr object"
                + " (" + CharApi.C + ":attr():get(name))");
        return h;
    }

    // ---- the reads ----------------------------------------------------------------------------------

    /**
     * The live {@link Glob.CAttr} for {@code name}, or {@code null} when there is no session or the server
     * has published nothing for it. {@code getcattr} auto-creates a zero entry for any name, so a
     * {@code base == 0 && comp == 0} record is <i>unpublished</i> rather than an attribute of zero.
     */
    static Glob.CAttr attr(String user, String name) {
        Glob g = AddonManager.glob(user);
        if(g == null)
            return null;
        Glob.CAttr a = g.getcattr(name);
        return ((a == null) || ((a.base == 0) && (a.comp == 0))) ? null : a;
    }

    /** The documented {@code Attr} snapshot {@code {base, comp}}, or nil while unpublished. */
    static LuaValue snapshot(String user, String name) {
        Glob.CAttr a = attr(user, name);
        if(a == null)
            return LuaValue.NIL;
        LuaTable t = new LuaTable();
        t.set("base", LuaValue.valueOf(a.base));
        t.set("comp", LuaValue.valueOf(a.comp));
        return t;
    }

    // ---- the collection ------------------------------------------------------------------------------

    /**
     * {@code s:char():attr()} — the base attributes. {@code :list()} is every one the server has
     * <b>populated</b>, in the client's own order; {@code :get(name)} is one of the nine and is never
     * {@code nil}; a name outside the nine is refused naming them all.
     */
    static LuaValue collection(final Addon owner, final String user) {
        return LuaCollection.create(CharApi.C + ":attr()", new LuaCollection.Source() {
            public List<LuaValue> members() {
                List<LuaValue> out = new ArrayList<LuaValue>();
                for(String nm : NAMES) {
                    if(attr(user, nm) != null)
                        out.add(of(owner, user, nm));
                }
                return out;
            }

            public String needle(LuaValue member) {
                LuaAttr h = resolve(member);
                return (h == null) ? "" : h.name;
            }

            /** These have a name, so a string filter is a substring test over {@link #needle}. */
            public boolean named() {
                return true;
            }

            public boolean addressable() {
                return true;
            }

            public LuaValue getMember(LuaValue key) {
                // type(), not isstring(): a LuaJ number IS a string by coercion, so isstring() would let
                // :get(3) through to the name test and report it as a missing ATTRIBUTE rather than as the
                // wrong kind of key.
                if(key.type() != LuaValue.TSTRING)
                    throw new LuaError(CharApi.C + ":attr():get(name): expected an attribute name, got "
                        + key.typename());
                String nm = key.tojstring();
                if(!known(nm))
                    throw new LuaError(CharApi.C + ":attr():get(\"" + nm + "\"): there is no such attribute."
                        + " The base attributes are: " + namesList());
                return of(owner, user, nm);
            }

            /** The base attributes are a closed set, so a name outside it is a typo. */
            public LuaCollection.Missing missing() {
                return LuaCollection.Missing.RAISE;
            }

            /** The key is the attribute's name. */
            public String keyName() {
                return "name";
            }
        }, null);
    }
}
