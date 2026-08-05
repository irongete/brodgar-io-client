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
 * An <b>Attr object</b> — one of the character's base attributes ({@code hafen.char():attr():get("str")}),
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

    /** The attribute name this handle addresses — the whole state of a handle. */
    public final String name;

    private LuaAttr(String name) {
        this.name = name;
    }

    /** {@code tostring(attr)}: {@code Attr(str)}. */
    public String toString() {
        return "Attr(" + name + ")";
    }

    /** An interned Attr object for {@code name} in {@code owner}'s env. */
    static LuaValue of(Addon owner, String name) {
        return owner.attrs.of(name);
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

    /** One addon's Attr interning cache and metatable (its {@link Addon#attrs}), keyed by name. */
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
            LuaValue v = LuaValue.userdataOf(new LuaAttr(nm), meta());
            live.put(nm, new Ref(v, nm, dead));
            return v;
        }

        /** Drop the entries whose handle Lua has released (mandatory on every access: the keys are strong). */
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

    /** A weak handle reference that remembers its map key, so the {@link ReferenceQueue} drain can unmap it. */
    private static final class Ref extends WeakReference<LuaValue> {
        final String key;

        Ref(LuaValue v, String key, ReferenceQueue<LuaValue> q) {
            super(v, q);
            this.key = key;
        }
    }

    // ---- the Attr metatable -------------------------------------------------------------------------

    private static LuaValue buildMeta() {
        LuaTable mt = new LuaTable();
        LuaTable m = methods();
        // Retired.methodIndex, never the methods table itself: a verb this migration renamed must throw
        // naming its replacement rather than read as plain nil and fail one character later.
        mt.set(LuaValue.INDEX, Retired.methodIndex("attr", m));
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
                Glob.CAttr a = attr(handle(self, "base").name);
                return (a == null) ? LuaValue.NIL : LuaValue.valueOf(a.base);
            }
        });
        // composite() — the computed, buffed value: base plus whatever food, gear and buffs add to it.
        m.set("composite", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Glob.CAttr a = attr(handle(self, "composite").name);
                return (a == null) ? LuaValue.NIL : LuaValue.valueOf(a.comp);
            }
        });
        // info() — the one SNAPSHOT escape hatch, or nil while the attribute is unpublished.
        m.set("info", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return snapshot(handle(self, "info").name);
            }
        });
        return m;
    }

    /** The handle behind a method's {@code self}, or a guiding error (a dot call passes the wrong self). */
    private static LuaAttr handle(LuaValue self, String method) {
        LuaAttr h = resolve(self);
        if(h == null)
            throw new LuaError("attr:" + method + "() — use a COLON call on an Attr object"
                + " (hafen.char():attr():get(name))");
        return h;
    }

    // ---- the reads ----------------------------------------------------------------------------------

    /**
     * The live {@link Glob.CAttr} for {@code name}, or {@code null} when there is no session or the server
     * has published nothing for it. {@code getcattr} auto-creates a zero entry for any name, so a
     * {@code base == 0 && comp == 0} record is <i>unpublished</i> rather than an attribute of zero.
     */
    static Glob.CAttr attr(String name) {
        Glob g = AddonManager.glob();
        if(g == null)
            return null;
        Glob.CAttr a = g.getcattr(name);
        return ((a == null) || ((a.base == 0) && (a.comp == 0))) ? null : a;
    }

    /** The documented {@code Attr} snapshot {@code {base, comp}}, or nil while unpublished. */
    static LuaValue snapshot(String name) {
        Glob.CAttr a = attr(name);
        if(a == null)
            return LuaValue.NIL;
        LuaTable t = new LuaTable();
        t.set("base", LuaValue.valueOf(a.base));
        t.set("comp", LuaValue.valueOf(a.comp));
        return t;
    }

    // ---- the collection ------------------------------------------------------------------------------

    /**
     * {@code hafen.char():attr()} — the base attributes. {@code :list()} is every one the server has
     * <b>populated</b>, in the client's own order; {@code :get(name)} is one of the nine and is never
     * {@code nil}; a name outside the nine is refused naming them all.
     */
    static LuaValue collection(final Addon owner) {
        return LuaCollection.create("hafen.char():attr()", new LuaCollection.Source() {
            public List<LuaValue> members() {
                List<LuaValue> out = new ArrayList<LuaValue>();
                for(String nm : NAMES) {
                    if(attr(nm) != null)
                        out.add(of(owner, nm));
                }
                return out;
            }

            public String needle(LuaValue member) {
                LuaAttr h = resolve(member);
                return (h == null) ? "" : h.name;
            }

            public boolean addressable() {
                return true;
            }

            public LuaValue getMember(LuaValue key) {
                // type(), not isstring(): a LuaJ number IS a string by coercion, so isstring() would let
                // :get(3) through to the name test and report it as a missing ATTRIBUTE rather than as the
                // wrong kind of key.
                if(key.type() != LuaValue.TSTRING)
                    throw new LuaError("hafen.char():attr():get(name): expected an attribute name, got "
                        + key.typename());
                String nm = key.tojstring();
                if(!known(nm))
                    throw new LuaError("hafen.char():attr():get(\"" + nm + "\"): there is no such attribute."
                        + " The base attributes are: " + namesList());
                return of(owner, nm);
            }
        }, null);
    }
}
