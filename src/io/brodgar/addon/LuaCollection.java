package io.brodgar.addon;

import java.util.List;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.TwoArgFunction;
import org.luaj.vm2.lib.VarArgFunction;

/**
 * A <b>collection object</b> — the one shape a set you can address into has (spec {@code 039-uniform-api}
 * §2.3). <b>The noun names the kind and the verb says how many</b>: the accessor is the singular kind name
 * and hands back this object, which carries {@code :list(filter)}, {@code :count(filter)},
 * {@code :find(filter)} and, where they apply, {@code :get(key)}, {@code :add(…)} and {@code :remove(x)}.
 * A plural name would be redundant with {@code :list()}.
 *
 * <p><b>Not indexable, and the reason is a design one rather than a capability one.</b> A 0-keyed
 * {@link LuaTable} lies about its length and ignores {@code __len}; userdata does not, so {@code #coll} and
 * {@code coll[1]} are perfectly implementable here — and are refused anyway, because an object that is also
 * a sequence gives the reader two ways to enumerate one thing, and {@code coll:list()} is one call away. The
 * refusal is a message rather than a {@code nil}, so the mistake fails where it is written.
 *
 * <p><b>It is a VIEW, never a handle.</b> The collection reads its members through {@link Source} on every
 * call and holds nothing between them, so one owned by an entity cannot outlive that entity and needs no
 * pruning of its own.
 *
 * <p><b>Only the verbs that apply are mounted.</b> A read-only collection has no {@code :add}/{@code
 * :remove}; a collection whose members have no key has no {@code :get}; a collection whose members have no
 * name refuses a string filter naming why, rather than silently matching nothing. Everything else about it
 * is the shared machinery here, so a section that gains a collection gains no vocabulary of its own.
 */
public final class LuaCollection {
    /** How the collection is spelled in Lua ({@code "hafen.timer()"}), for its messages. */
    private final String name;
    /** What the collection reads, and — where they apply — how a member is addressed, created and destroyed. */
    private final Source src;

    private LuaCollection(String name, Source src) {
        this.name = name;
        this.src = src;
    }

    /** {@code tostring(coll)} → the spelling that produced it. */
    public String toString() {
        return name;
    }

    /**
     * What a collection is a collection <i>of</i>. {@link #members()} is the only method every source has;
     * the rest declare, by answering true, which of §2.3's optional verbs this collection carries.
     */
    public abstract static class Source {
        /** Every member, in the collection's own order, as the Lua values the API hands out. */
        public abstract List<LuaValue> members();

        /**
         * The text a <b>string</b> filter matches as a substring, or {@code null} when <b>this member's</b>
         * name has not arrived yet — a gob whose {@code Drawable} is still resolving, a pagina whose
         * resource has not loaded. Such a member simply <b>does not match</b>, exactly as
         * {@code AddonManager.gobMatches} has always treated it: "not yet" is not "no" (D-095), and a read
         * that answers {@code nil} must not make the call that contains it throw.
         *
         * <p><b>This is per MEMBER; namelessness is per KIND and is {@link #named()}.</b> Conflating the two
         * is the defect 039.2 shipped and 039.15 found: {@code hafen.world():gob():count("terobjs/tree")}
         * threw as soon as one loaded gob had an unresolved resource, while {@code :nearest("terobjs/tree")}
         * one verb away skipped it — the two filter paths meaning different things, which the contract
         * below exists to forbid.
         */
        public String needle(LuaValue member) {
            return null;
        }

        /**
         * Do members of this <b>kind</b> have a name at all? {@code false} — the default — makes a string
         * filter a refusal naming the two verbs that do work (D-115): a buff bar and a grid database have no
         * name to match, and matching nothing would be a lie.
         *
         * <p>A source that supplies a {@link #needle} declares {@code true} beside it. The default is the
         * <b>refusing</b> one on purpose: a source that forgets the pair fails loudly on the next string
         * filter rather than quietly matching nothing.
         */
        public boolean named() {
            return false;
        }

        /** Does {@code :get(key)} apply? (Only where a member has a key that addresses it.) */
        public boolean addressable() {
            return false;
        }

        /** The member for {@code key}, or {@code NIL} on a miss. Called only when {@link #addressable()}. */
        public LuaValue getMember(LuaValue key) {
            return LuaValue.NIL;
        }

        /** Does {@code :add(…)} apply? */
        public boolean creatable() {
            return false;
        }

        /** Create a member and hand it back. {@code a} starts at argument 2 (argument 1 is the collection). */
        public LuaValue addMember(Varargs a) {
            return LuaValue.NIL;
        }

        /** Does {@code :remove(keyOrMember)} apply? */
        public boolean destroyable() {
            return false;
        }

        /** Destroy the member named by {@code x} (a key or the member itself). */
        public void removeMember(LuaValue x) {
        }
    }

    /**
     * Build a collection object: userdata over {@code src}, with the §2.3 verbs that apply plus whatever
     * {@code extra} verbs the owning section carries ({@code hafen.timer():every(s, fn)}).
     *
     * @param name  how the collection is spelled in Lua, for its messages ({@code "hafen.timer()"})
     * @param extra the section's own verbs, or {@code null}
     */
    public static LuaValue create(String name, Source src, LuaTable extra) {
        LuaCollection coll = new LuaCollection(name, src);
        return LuaValue.userdataOf(coll, meta(coll, methods(coll, extra)));
    }

    /** The collection behind a method's {@code self}, or a guiding error (a dot call passes the wrong one). */
    static LuaCollection receiver(LuaValue v, String method) {
        if((v != null) && v.isuserdata()) {
            Object o = v.touserdata();
            if(o instanceof LuaCollection)
                return (LuaCollection)o;
        }
        throw new LuaError(":" + method + "() — use a COLON call on a collection object");
    }

    // ---- the verbs ---------------------------------------------------------------------------------

    private static LuaTable methods(final LuaCollection coll, LuaTable extra) {
        LuaTable m = new LuaTable();
        // list(filter) — the members as a plain 1-based array, which IS a value: index that, not the
        // collection. Empty (never nil) when nothing matches.
        m.set("list", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaCollection c = receiver(a.arg1(), "list");
                LuaValue filter = a.arg(2);
                LuaTable t = new LuaTable();
                int n = 0;
                for(LuaValue member : c.src.members()) {
                    if(c.keeps(filter, member, "list"))
                        t.set(++n, member);
                }
                return t;
            }
        });
        // count(filter) — how many, without building the array.
        m.set("count", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaCollection c = receiver(a.arg1(), "count");
                LuaValue filter = a.arg(2);
                int n = 0;
                for(LuaValue member : c.src.members()) {
                    if(c.keeps(filter, member, "count"))
                        n++;
                }
                return LuaValue.valueOf(n);
            }
        });
        // find(filter) — the FIRST member that matches, or nil. The one-result twin of list().
        m.set("find", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaCollection c = receiver(a.arg1(), "find");
                LuaValue filter = a.arg(2);
                for(LuaValue member : c.src.members()) {
                    if(c.keeps(filter, member, "find"))
                        return member;
                }
                return LuaValue.NIL;
            }
        });
        if(coll.src.addressable()) {
            // get(key) — one member by its key, nil on a miss. It ADDRESSES a member; it does not read a
            // property, which is why the one-name-per-property rule does not reach it.
            m.set("get", new VarArgFunction() {
                public Varargs invoke(Varargs a) {
                    LuaCollection c = receiver(a.arg1(), "get");
                    return c.src.getMember(Args.required(a, 2, c.name + ":get", "key"));
                }
            });
        }
        if(coll.src.creatable()) {
            // add(...) — create a member and hand back the new entity.
            m.set("add", new VarArgFunction() {
                public Varargs invoke(Varargs a) {
                    LuaCollection c = receiver(a.arg1(), "add");
                    return c.src.addMember(a);
                }
            });
        }
        if(coll.src.destroyable()) {
            // remove(keyOrMember) — destroy a member, and return the COLLECTION so removals chain.
            m.set("remove", new VarArgFunction() {
                public Varargs invoke(Varargs a) {
                    LuaValue me = a.arg1();
                    LuaCollection c = receiver(me, "remove");
                    c.src.removeMember(Args.required(a, 2, c.name + ":remove", "keyOrMember"));
                    return me;
                }
            });
        }
        if(extra != null) {
            LuaValue k = LuaValue.NIL;
            while(true) {
                Varargs n = extra.next(k);
                k = n.arg1();
                if(k.isnil())
                    break;
                m.set(k, n.arg(2));
            }
        }
        return m;
    }

    /** Does {@code member} pass {@code filter}? The canonical filter: nil = all, predicate, or substring. */
    private boolean keeps(LuaValue filter, LuaValue member, String verb) {
        return keeps(filter, member, src.named(), src.needle(member), name, verb);
    }

    /**
     * The canonical filter, as one shared decision: {@code nil} keeps everything, a function is a predicate
     * over the member <b>object</b>, a string is a substring test against {@code needle}, and anything else
     * is an error naming the three forms.
     *
     * <p><b>The two ways a string filter meets a member with no text are different questions.</b> A
     * collection whose <i>kind</i> is nameless ({@code named} false) refuses the string, because matching
     * nothing would be a lie. A <i>member</i> of a named kind whose name has not arrived yet ({@code needle}
     * null) simply does not match, because a read that is not ready answers {@code nil} and must not throw
     * out of the call that contains it.
     *
     * <p>Package-visible and static because a collection's own <b>extra</b> verbs filter too — a sub-list
     * like {@code hafen.char():skill():available(filter)} is the same argument over a different set, and it
     * has to behave identically or the filter would mean two things one verb apart.
     */
    static boolean keeps(LuaValue filter, LuaValue member, boolean named, String needle, String coll,
                         String verb) {
        if((filter == null) || filter.isnil())
            return true;
        if(filter.isfunction()) {
            try {
                return filter.call(member).toboolean();
            } catch(RuntimeException e) {   // LuaError is a RuntimeException: a throwing predicate drops it
                return false;
            }
        }
        if(filter.isstring()) {
            if(!named)
                throw new LuaError(coll + ":" + verb + "(filter): these have no name to match a string"
                    + " against — pass a function, or nothing for all of them");
            return (needle != null) && needle.contains(filter.tojstring());
        }
        throw new LuaError(coll + ":" + verb + "(filter): expected nothing, a string or a function, got "
            + filter.typename());
    }

    // ---- the metatable: methods by name, and everything else refused -------------------------------

    private static LuaValue meta(final LuaCollection coll, final LuaTable methods) {
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, new TwoArgFunction() {
            public LuaValue call(LuaValue self, LuaValue key) {
                LuaValue m = methods.rawget(key);
                if(!m.isnil())
                    return m;
                if(key.isnumber())
                    throw new LuaError(coll.name + " is a collection, not an array: " + coll.name
                        + ":list() is the array and you index that");
                throw new LuaError(coll.name + " has no verb '" + key.tojstring() + "'");
            }
        });
        mt.set("__len", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                throw new LuaError("# is refused on " + coll.name + ": " + coll.name + ":count() is how"
                    + " many, " + coll.name + ":list() is the array");
            }
        });
        mt.set("__name", LuaValue.valueOf("Collection"));
        mt.set("__tostring", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(coll.name);
            }
        });
        return mt;
    }
}
