package io.brodgar.addon;

import haven.Loading;

import java.util.List;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.TwoArgFunction;
import org.luaj.vm2.lib.VarArgFunction;
import java.util.ArrayList;

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
 *
 * <p><b>A verb that is not mounted still answers for itself.</b> {@code :get} on a keyless collection is the
 * commonest reach for a verb that is not there, so the source declares {@link Source#noGet} — the sentence
 * naming what to search with instead — and an accessor that has to say how a member is reached asks
 * {@link #reach} rather than assuming every collection has a {@code :get}. Where there IS one,
 * {@link Source#missing} declares which of {@link Missing}'s three answers a key that names nothing gets, so
 * the fact is stated beside the lookup rather than read out of it.
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
     * What {@code :get(key)} answers for a key that names nothing. <b>Declared per collection</b>
     * ({@link Source#missing}) rather than read out of each {@code getMember}, because it is the one thing
     * about {@code :get} an author has to know before they write the line after it — and a fact spread over
     * twenty method bodies is the fact the page documenting them gets wrong.
     */
    public enum Missing {
        /** A key it does not hold answers {@code nil}. The common case, and what the default source does. */
        NIL,
        /** It always hands back an object, so {@code :exists()} is the question rather than {@code nil}. */
        MINT,
        /** The set is closed, so a key outside it is a mistake and the call says which keys there are. */
        RAISE
    }

    /**
     * What a collection is a collection <i>of</i>. {@link #members()} is the only method every source has;
     * the rest declare, by answering true, which of §2.3's optional verbs this collection carries.
     */
    public abstract static class Source {
        /** Every member, in the collection's own order, as the Lua values the API hands out. */
        public abstract List<LuaValue> members();

        /**
         * How many members there are, for {@code :count()} with no filter — {@link #members()}'s size unless
         * the source can answer without minting a member per line. A read the page recommends INSTEAD of
         * {@code :list()} has to cost less than it, and over a scrollback of ten thousand lines it did not.
         */
        public int size() {
            return members().size();
        }

        /**
         * The text a <b>string</b> filter matches as a substring, or {@code null} when <b>this member's</b>
         * name has not arrived yet — a gob whose {@code Drawable} is still resolving, a pagina whose
         * resource has not loaded. Such a member simply <b>does not match</b>, exactly as
         * {@code AddonManager.gobMatches} has always treated it: "not yet" is not "no" (D-095), and a read
         * that answers {@code nil} must not make the call that contains it throw.
         *
         * <p><b>This is per MEMBER; namelessness is per KIND and is {@link #named()}.</b> Conflating the two
         * is the defect 039.2 shipped and 039.15 found: {@code s:world():gob():count("terobjs/tree")}
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

        /**
         * What {@code :get}'s argument is called when it is missing — {@code "key"} unless the collection
         * addresses its members by something the reader would name otherwise ({@code "user"}). The arity
         * refusal is the one message a caller who omitted it ever sees, so it is worth its own word.
         */
        public String keyName() {
            return "key";
        }

        /**
         * Which of the three answers {@link #getMember} gives a key that names nothing. Read only where
         * {@link #addressable()}; the default is {@link Missing#NIL} because that is what the {@code
         * getMember} above does, so a source declares this exactly where it overrides that.
         *
         * <p><b>It is the promise the KEY carries.</b> {@code session:kin()} declares {@link Missing#MINT}
         * because {@code :get(id)} always hands back a Kin — the name form it also takes is a lookup rather
         * than an address and answers {@code nil}, which is the kind of thing that belongs on the page rather
         * than in an enum.
         */
        public Missing missing() {
            return Missing.NIL;
        }

        /**
         * The sentence a {@link Missing#RAISE} collection refuses a key outside its set with — the keys
         * there are, in the source's own words — or {@code null} where {@link #getMember} raises naming them
         * itself. Read only where {@link #missing()} is {@code RAISE}: the collection writes the sentence
         * once, beside the declaration, so the lookup cannot answer {@code nil} and the refusal cannot name a
         * key the set has lost.
         */
        public String keys() {
            return null;
        }

        /**
         * Why this collection has no {@code :get}, and what to write instead — the hint the refusal carries,
         * in the {@link Refusal#closedIndex} shape ({@code "<coll> has no verb 'get' — <this>"}). Declared by
         * every collection that is not {@link #addressable()}; {@code null} falls back to the sentence that is
         * true of all of them, which names {@code :find} and {@code :list} and teaches nothing else.
         *
         * <p>It is also what an accessor's own refusal quotes ({@link LuaCollection#reach}), so the two places
         * that have to say how a member is reached say the same words.
         */
        public String noGet() {
            return null;
        }

        /** Does {@code :add(…)} apply? */
        public boolean creatable() {
            return false;
        }

        /**
         * What {@code :add}'s first argument is called when it is missing — {@link #keyName()} unless the
         * member is created from something other than its key ({@code "secret"}, {@code "image"}). The
         * arity refusal is the shared verb's, so the word has to be declared where the verb can read it.
         */
        public String addName() {
            return keyName();
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

    /**
     * How one member of {@code coll} is reached, as a clause an accessor's own refusal can carry —
     * {@code hafen.map():marker(1)} names {@code :find}/{@code :nearest}, {@code hafen.map():segment(1)}
     * names {@code :get(id)}. <b>The collection answers for itself</b>, so an accessor written over five of
     * them cannot name a {@code :get} one of the five has not got.
     */
    static String reach(LuaValue coll) {
        LuaCollection c = (LuaCollection)coll.touserdata();
        return c.src.addressable()
            ? (c.name + ":get(" + c.src.keyName() + ") addresses one member of it")
            : c.noGet();
    }

    /** The sentence the missing {@code :get} carries: the source's own, or the one true of every collection. */
    private String noGet() {
        String own = src.noGet();
        return (own != null) ? own
            : ("these members have no key: " + name + ":find(filter) is the search and " + name
               + ":list()[n] takes a position");
    }

    /**
     * The collection behind a method's {@code self}, or a guiding error naming the collection and what the
     * call handed it instead — a dot call passes its first argument as the receiver, and "got a string" is
     * what tells the author which mistake they made. {@code coll} is the spelling the message quotes: the
     * caller has it and the wrong receiver has not.
     */
    static LuaCollection receiver(LuaValue v, String coll, String method) {
        if((v != null) && v.isuserdata()) {
            Object o = v.touserdata();
            if(o instanceof LuaCollection)
                return (LuaCollection)o;
        }
        throw new LuaError(coll + ":" + method + "() — use a COLON call on the collection (" + coll + ":"
            + method + "(…)), got " + ((v == null) ? "nothing" : v.typename()));
    }

    // ---- the verbs ---------------------------------------------------------------------------------

    private static LuaTable methods(final LuaCollection coll, LuaTable extra) {
        LuaTable m = new LuaTable();
        // list(filter) — the members as a plain 1-based array, which IS a value: index that, not the
        // collection. Empty (never nil) when nothing matches.
        m.set("list", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaCollection c = receiver(a.arg1(), coll.name, "list");
                Args.only(a, 1, c.name + ":list");
                LuaValue filter = a.arg(2);
                LuaTable t = new LuaTable();
                int n = 0;
                for(LuaValue member : c.src.members()) {
                    if(keeps(filter, member, c.src, c.name, "list"))
                        t.set(++n, member);
                }
                return t;
            }
        });
        // count(filter) — how many, without building the array: with no filter it is the source's own
        // size(), so the read the page recommends over :list() never mints a member to count it.
        m.set("count", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaCollection c = receiver(a.arg1(), coll.name, "count");
                Args.only(a, 1, c.name + ":count");
                LuaValue filter = a.arg(2);
                if(filter.isnil())
                    return LuaValue.valueOf(c.src.size());
                int n = 0;
                for(LuaValue member : c.src.members()) {
                    if(keeps(filter, member, c.src, c.name, "count"))
                        n++;
                }
                return LuaValue.valueOf(n);
            }
        });
        // find(filter) — the FIRST member that matches, or nil. The one-result twin of list().
        m.set("find", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaCollection c = receiver(a.arg1(), coll.name, "find");
                Args.only(a, 1, c.name + ":find");
                LuaValue filter = a.arg(2);
                for(LuaValue member : c.src.members()) {
                    if(keeps(filter, member, c.src, c.name, "find"))
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
                    LuaCollection c = receiver(a.arg1(), coll.name, "get");
                    Args.only(a, 1, c.name + ":get");
                    LuaValue key = Args.required(a, 2, c.name + ":get", c.src.keyName());
                    LuaValue v = c.src.getMember(key);
                    // The two directions of Missing that are a PROMISE rather than a fact about the game, held
                    // to here so the declaration and the lookup cannot drift while conventions.md quotes the
                    // former. A set declared CLOSED refuses the key naming the keys there are, in the
                    // source's own sentence; a set declared MINT hands back an object for every key, so a
                    // nil out of either is a defect in the client and is said to be one.
                    if(v.isnil() && (c.src.missing() == Missing.RAISE)) {
                        String keys = c.src.keys();
                        throw new LuaError(c.name + ":get(" + shown(key) + "): " + ((keys != null) ? keys
                            : ("this set is closed, so a key outside it says which keys there are —"
                               + " answering nil is a bug in the client, not an answer")));
                    }
                    if(v.isnil() && (c.src.missing() == Missing.MINT))
                        throw new LuaError(c.name + ":get(" + shown(key) + "): this collection mints an object"
                            + " for every " + c.src.keyName() + " it takes, so :exists() is the question —"
                            + " answering nil is a bug in the client, not an answer");
                    return v;
                }
            });
        }
        if(coll.src.creatable()) {
            // add(...) — create a member and hand back the new entity. The first argument is required here,
            // under the source's own name for it, so a bare :add() is refused in one shape everywhere and no
            // source can forget to.
            m.set("add", new VarArgFunction() {
                public Varargs invoke(Varargs a) {
                    LuaCollection c = receiver(a.arg1(), coll.name, "add");
                    Args.required(a, 2, c.name + ":add", c.src.addName());
                    return c.src.addMember(a);
                }
            });
        }
        if(coll.src.destroyable()) {
            // remove(keyOrMember) — destroy a member, and return the COLLECTION so removals chain.
            m.set("remove", new VarArgFunction() {
                public Varargs invoke(Varargs a) {
                    LuaValue me = a.arg1();
                    LuaCollection c = receiver(me, coll.name, "remove");
                    Args.only(a, 1, c.name + ":remove");
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

    /**
     * Every value of a 1-based Lua array, as the member list a {@link Source} hands back — for a relation
     * that was an array before 091 and whose builder still hands one back. It copies: a Source is read fresh
     * on every call, so the array it walked is not held.
     */
    public static List<LuaValue> fromArray(LuaValue arr) {
        List<LuaValue> out = new ArrayList<LuaValue>();
        if((arr == null) || !arr.istable())
            return out;
        int n = arr.length();
        for(int i = 1; i <= n; i++)
            out.add(arr.get(i));
        return out;
    }

    /**
     * The canonical filter, as one shared decision: {@code nil} keeps everything, a function is a predicate
     * over the member <b>object</b>, a string is a substring test against the source's {@link Source#needle},
     * and anything else is an error naming the three forms.
     *
     * <p><b>The needle is read inside the string branch and nowhere else.</b> It takes the {@link Source}
     * rather than a needle already computed, because a needle is a re-scan of the backing list on most
     * sources, and one computed as an argument was paid per member on every {@code :list()}, {@code :count()}
     * and {@code :find()} whatever the filter — including none. A filter that never looks at a name costs no
     * name.
     *
     * <p><b>The two ways a string filter meets a member with no text are different questions.</b> A
     * collection whose <i>kind</i> is nameless ({@code named} false) refuses the string, because matching
     * nothing would be a lie. A <i>member</i> of a named kind whose name has not arrived yet ({@code needle}
     * null) simply does not match, because a read that is not ready answers {@code nil} and must not throw
     * out of the call that contains it.
     *
     * <p>Package-visible and static because a collection's own <b>extra</b> verbs filter too — a sub-list
     * like {@code s:char():skill():available(filter)} is the same argument over a different set, and it
     * has to behave identically or the filter would mean two things one verb apart.
     */
    static boolean keeps(LuaValue filter, LuaValue member, Source src, String coll, String verb) {
        if((filter == null) || filter.isnil())
            return true;
        if(filter.isfunction()) {
            try {
                return Args.truthy(filter.call(member));
            } catch(Loading l) {
                // NOT RuntimeException, which LuaError is too. A read the predicate made that is not ready
                // yet is not a "no" -- Loading is control flow, and the member simply does not match, exactly
                // as a needle that has not arrived does not. A LuaError is a MISTAKE, and it goes on out of
                // :list/:count/:find at the line that wrote it: catching it made a typo inside a predicate
                // look like an empty world, which is the one report an author cannot act on.
                return false;
            }
        }
        if(filter.isstring()) {
            if(!src.named())
                throw new LuaError(coll + ":" + verb + "(filter): these have no name to match a string"
                    + " against — pass a function, or nothing for all of them");
            String needle = src.needle(member);
            return (needle != null) && needle.contains(filter.tojstring());
        }
        throw new LuaError(coll + ":" + verb + "(filter): expected nothing, a string or a function, got "
            + filter.typename());
    }

    /** A key as the caller wrote it, for a refusal: a string in quotes, anything else as it prints. */
    private static String shown(LuaValue key) {
        return (key.type() == LuaValue.TSTRING) ? ("\"" + key.tojstring() + "\"") : key.tojstring();
    }

    /**
     * {@code pairs(coll)} and {@code ipairs(coll)}, refused in the collection's own words. LuaJ's two
     * iterators call {@code checktable} before they consult any metamethod, so this refusal cannot hang off
     * the metatable the way {@code #} and {@code [n]} do below — it stands where the globals are installed,
     * and the sandbox calls this once per environment for exactly that. Anything that is not a collection
     * goes through to the iterator it replaced, untouched.
     */
    public static void guardIteration(LuaTable g) {
        for(String fn : new String[] {"pairs", "ipairs"})
            g.set(fn, iterationGuard(fn, g.get(fn)));
    }

    private static LuaValue iterationGuard(final String fn, final LuaValue real) {
        return new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue v = a.arg1();
                if(v.isuserdata() && (v.touserdata() instanceof LuaCollection)) {
                    String nm = ((LuaCollection)v.touserdata()).name;
                    throw new LuaError(fn + " is refused on " + nm + ": it is a collection, not a table — "
                        + nm + ":list() is the array, and you walk that");
                }
                return real.invoke(a);
            }
        };
    }

    // ---- the metatable: methods by name, and everything else refused -------------------------------

    private static LuaValue meta(final LuaCollection coll, final LuaTable methods) {
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, new TwoArgFunction() {
            public LuaValue call(LuaValue self, LuaValue key) {
                LuaValue m = methods.rawget(key);
                if(!m.isnil())
                    return m;
                // A verb this collection USED TO have throws its own message first, exactly as Section.meta
                // does, and keyed the same way: by the spelling the call site writes ("session:speed():max",
                // never a hafen. door the collection is not reached through). A collection mounted AS a
                // section object (§2.1) would otherwise swallow the replacement message under the generic
                // "has no verb", which says the call is wrong without saying what is right.
                if(key.isstring()) {
                    String msg = Refusal.message(coll.name + ":" + key.tojstring());
                    if(msg != null)
                        throw new LuaError(msg);
                }
                if(key.isnumber())
                    throw new LuaError(coll.name + " is a collection, not an array: " + coll.name
                        + ":list() is the array and you index that");
                // The commonest reach for a verb a collection has not got. "has no verb 'get'" is true and
                // teaches nothing: what the author wants is a member, and there is always a way to one.
                if(!coll.src.addressable() && key.isstring() && key.tojstring().equals("get"))
                    throw new LuaError(coll.name + " has no verb 'get' — " + coll.noGet());
                String verbs = Refusal.vocabulary(methods);
                throw new LuaError(coll.name + " has no verb '" + key.tojstring() + "'"
                    + (verbs.isEmpty() ? "" : " — it answers " + verbs));
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
