package io.brodgar.addon;

import haven.KeyBinding;
import haven.KeyMatch;

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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A <b>Binding object</b> — one entry of the client's hotkey registry ({@link KeyBinding}), reached through
 * the collection {@code hafen.client():options():keybindings():binding()}: this addon's own hotkeys, other
 * addons', and the client's own.
 *
 * <p><b>The registry is three-valued and the object exposes all three.</b> {@link KeyBinding#key} is
 * {@code null} for <i>use the client's own default</i>, {@link KeyMatch#nil} for <i>explicitly unbound</i>,
 * and anything else is an assignment — and the collapsed read {@link KeyBinding#key()} shows only the last
 * two, so a caller that saved a key and wrote it back turned every default into an assignment with no way
 * back. {@code b:key(nil)} is that way back ({@code set(null)}, the client keybind panel's own Backspace),
 * and {@code b:default()} and {@code b:assigned()} are what make it usable: without them nothing can tell
 * "the user chose F5" from "F5 is the default".
 *
 * <p><b>The intern key is the registry id</b>, which is the whole state of a handle — the {@link KeyBinding}
 * itself is not, because the registry is <b>lazily populated</b>: an id exists only once the class that
 * declares it has been loaded, so {@code :get("inv")} in an addon's file body addresses a binding that is
 * not there yet and will be after login. That is why {@code :get} always hands back an object and
 * {@code :exists()} is the question.
 *
 * <p><b>Reverting can re-create a collision the assignment could not.</b> {@link KeyBinding#set} unbinds
 * every other binding on the same key, but {@link KeyBinding#get} runs no such pass, so two <i>defaults</i>
 * sharing a key leave both firing. That is engine behaviour under this object rather than a property of it.
 */
public final class LuaBinding {
    /** How the collection is spelled in Lua, for its own messages and its members'. */
    static final String COLL = "hafen.client():options():keybindings():binding()";

    /** The registry id this addresses — the whole state of a handle. */
    public final String id;

    private LuaBinding(String id) {
        this.id = id;
    }

    /** {@code tostring(b)}: {@code Binding(addon/myaddon/toggle)}. */
    public String toString() {
        return "Binding(" + id + ")";
    }

    /** An interned Binding object for registry id {@code id} in {@code owner}'s env. */
    static LuaValue of(Addon owner, String id) {
        return owner.bindings.of(id);
    }

    /** The {@code LuaBinding} behind a Lua value, or {@code null} for anything else. */
    static LuaBinding resolve(LuaValue v) {
        if((v == null) || !v.isuserdata())
            return null;
        Object o = v.touserdata();
        return (o instanceof LuaBinding) ? (LuaBinding)o : null;
    }

    /** The registry entry this addresses, or {@code null} while nothing has declared it. */
    private KeyBinding binding() {
        return KeyBinding.get(id);
    }

    /** A {@link KeyMatch} as the API hands one out: a display string, or {@code nil} for unbound. */
    private static LuaValue keyName(KeyMatch km) {
        return ((km == null) || (km == KeyMatch.nil)) ? LuaValue.NIL : LuaValue.valueOf(km.name());
    }

    // ---- the collection ------------------------------------------------------------------------------

    /**
     * {@code keybindings():binding()} — every binding the client currently knows, ordered by id so a listing
     * reads the same way twice. {@code :get(id)} takes the registry id, and tries <b>this addon's own scope
     * first</b> ({@code addon/<id>/<name>}), so {@code on("toggle", fn)} and {@code :get("toggle")} name the
     * same binding without the addon ever spelling its own id; a name it has not declared is the raw id as
     * written.
     */
    static LuaValue collection(final Addon owner) {
        return LuaCollection.create(COLL, new LuaCollection.Source() {
            public List<LuaValue> members() {
                List<String> ids = new ArrayList<String>();
                for(KeyBinding b : KeyBinding.all())
                    ids.add(b.id);
                Collections.sort(ids);      // KeyBinding.all() is a HashMap's values: no order of its own
                List<LuaValue> out = new ArrayList<LuaValue>();
                for(String id : ids)
                    out.add(of(owner, id));
                return out;
            }

            /** A binding HAS a name — its registry id — so a string filter is a substring test on it. */
            public boolean named() {
                return true;
            }

            public String needle(LuaValue member) {
                LuaBinding h = resolve(member);
                return (h == null) ? null : h.id;
            }

            public boolean addressable() {
                return true;
            }

            public String keyName() {
                return "id";
            }

            public LuaValue getMember(LuaValue key) {
                // type(), not isstring(): a LuaJ number IS a string by coercion, so isstring() would let
                // :get(3) through and report it as a binding id nothing has declared.
                if(key.type() != LuaValue.TSTRING)
                    throw new LuaError(COLL + ":get(id): expected a binding id, got " + key.typename());
                String nm = key.tojstring();
                String own = "addon/" + owner.manifest.id + "/" + nm;
                return of(owner, (KeyBinding.get(own) != null) ? own : nm);
            }

            /**
             * MINT, not NIL: the registry fills in as classes load, so an id that names nothing yet is an
             * ordinary state of a binding rather than a miss — {@code b:exists()} is the question, and the
             * handle taken before login is the same object after it.
             */
            public LuaCollection.Missing missing() {
                return LuaCollection.Missing.MINT;
            }
        }, null);
    }

    // ---- the per-addon intern cache + metatable ----------------------------------------------------

    /** One addon's Binding cache and metatable (its {@link Addon#bindings}), keyed by the registry id. */
    static final class Cache {
        /** The addon these handles belong to — what {@code client.settings} is checked against (093.3). */
        private final Addon owner;
        private final Map<String, Ref> live = new HashMap<String, Ref>();
        private final ReferenceQueue<LuaValue> dead = new ReferenceQueue<LuaValue>();
        private LuaValue mt;

        Cache(Addon owner) {
            this.owner = owner;
        }

        synchronized LuaValue of(String id) {
            drain();
            Ref r = live.get(id);
            if(r != null) {
                LuaValue v = r.get();
                if(v != null)
                    return v;
                live.remove(id);
            }
            LuaValue v = LuaValue.userdataOf(new LuaBinding(id), meta());
            live.put(id, new Ref(v, id, dead));
            return v;
        }

        /** Drop the map entries whose handle Lua has released (the key + dead ref would leak otherwise). */
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
                mt = buildMeta(owner);
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

    // ---- the Binding metatable -----------------------------------------------------------------------

    private static LuaValue buildMeta(Addon owner) {
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, Retired.closedIndex("binding", methods(owner),
            "a binding answers :id() :key() :default() :assigned() :exists() and :info()"));
        mt.set("__name", LuaValue.valueOf("Binding"));
        mt.set("__tostring", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(handle(self, "tostring").toString());
            }
        });
        return mt;
    }

    private static LuaTable methods(final Addon owner) {
        LuaTable m = new LuaTable();
        // id() — the registry id, its identity. Your own hotkeys read addon/<your addon id>/<name>.
        m.set("id", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(handle(self, "id").id);
            }
        });
        // key() / key(k) / key(nil) — the three states, in one name, because arity is the verb. The read is
        // the EFFECTIVE key (the assignment, else the default) as a display string, nil when that is
        // unbound either way; :assigned() is what tells the two apart. The write takes "F5"-style strings
        // and "None" to unbind, and an explicit nil to put the binding back on the client's own default —
        // one of the API's documented nil meanings (Args), which is why this reads its own argument rather
        // than going through Args.written.
        //   093.3 (A-097): the two WRITE arities are PROTECTED, under client.settings, and the read is not.
        // A remap is the user's own configuration and it persists -- and it reaches the CLIENT's bindings as
        // readily as an addon's own, so binding:key("Ctrl+I") on "inv" takes the inventory key. That is a
        // control the player has in front of them, which is the tier's own definition of what it gates.
        // Arity is what says whether there is a write here at all, so the gate runs the instant that is
        // known, before the binding is resolved.
        m.set("key", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = a.arg1();
                if(Args.passed(a, 2))
                    AddonManager.requirePermission(owner, Permission.CLIENT_SETTINGS, "binding:key");
                LuaBinding h = handle(self, "key");
                KeyBinding b = h.binding();
                if(!Args.passed(a, 2))
                    return (b == null) ? LuaValue.NIL : keyName(b.key());
                if(b == null)
                    throw new LuaError("binding:key(k): there is no binding '" + h.id + "' — nothing has"
                        + " declared it, so there is nothing to remap. b:exists() is the test");
                LuaValue k = a.arg(2);
                if(k.isnil()) {
                    b.set(null);            // revert: the keybind panel's own Backspace
                    return self;
                }
                Args.str(k, "binding:key", "k",
                         "\"F5\", \"Ctrl+M\", \"None\" to unbind, or nil for the client's own default");
                KeyMatch km = HookApi.parseKeyMatch(k.tojstring());
                if(km == null)
                    throw new LuaError("binding:key: cannot parse key '" + k.tojstring()
                                       + "' (examples: \"F5\", \"Ctrl+M\", \"Shift+Alt+Left\", \"None\")");
                b.set(km);
                return self;
            }
        });
        // default() — the key the CLIENT gives this binding, as a display string, or nil where its default
        // is unbound (which every addon hotkey's is). It is what key(nil) puts back.
        m.set("default", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                KeyBinding b = handle(self, "default").binding();
                return (b == null) ? LuaValue.NIL : keyName(b.defkey);
            }
        });
        // assigned() — is the current key the USER's or the client's? The state a display string cannot
        // carry: "None" is an assignment too, and an unbound default reads the same way.
        m.set("assigned", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                KeyBinding b = handle(self, "assigned").binding();
                return LuaValue.valueOf((b != null) && b.set());
            }
        });
        // exists() — has anything declared this id yet? The registry fills in as the client's classes load
        // and as addons declare their hotkeys, so an id can be addressed before it is there.
        m.set("exists", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(handle(self, "exists").binding() != null);
            }
        });
        // info() — the one SNAPSHOT escape hatch, and nil for a binding nothing has declared.
        m.set("info", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaBinding h = handle(self, "info");
                KeyBinding b = h.binding();
                if(b == null)
                    return LuaValue.NIL;
                LuaTable t = new LuaTable();
                t.set("id", LuaValue.valueOf(h.id));
                t.set("key", keyName(b.key()));
                t.set("default", keyName(b.defkey));
                t.set("assigned", LuaValue.valueOf(b.set()));
                return t;
            }
        });
        return m;
    }

    private static LuaBinding handle(LuaValue self, String method) {
        LuaBinding h = resolve(self);
        if(h == null)
            throw new LuaError("b:" + method + "() — use a COLON call on a Binding object ("
                + COLL + ":get(id))");
        return h;
    }
}
