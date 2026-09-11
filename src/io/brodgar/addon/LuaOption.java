package io.brodgar.addon;

import haven.Utils;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.VarArgFunction;

import java.util.List;

/**
 * One <b>option an addon declared</b> (spec {@code 115-the-addon-declares-its-options}; the model half of
 * spec {@code 140-the-options-page-is-the-addons}, 140.2) — what
 * {@code hafen.client():options():addon():<type>(name)…:add()} hands back, and the record the client stores a
 * value for.
 *
 * <p><b>An option is the model.</b> Four kinds, each a stored value and nothing else: a {@link Kind#BOOLEAN},
 * a {@link Kind#NUMBER} over a declared range, a {@link Kind#CHOICE} out of a declared list and a
 * {@link Kind#TEXT}. Every one answers {@code :value()}/{@code :value(v)}, {@code :default()},
 * {@code :on("Changed", fn)} and {@code :info()}. Nothing here is drawn: the page an option is shown on is
 * the addon's own ({@code opts:panel(fn)}, {@link AddonOptions}), built out of the client's controls, and
 * a control is joined to an option by {@code w:bind(opt)} — so a caption, a hover text, a button and a
 * line of text are a control's business, and the option carries none of them.
 *
 * <p><b>The value in force lives here, not in the preference store.</b> {@link #value} is loaded from
 * {@code addon/<addonid>/opt/<name>} once, at declaration, and written back on every change: nothing but this
 * client writes that key, so a read costs a field rather than a {@code java.util.prefs} lookup. A stored value
 * the declaration can no longer honour (a choice that is no longer offered, a number outside the range as it
 * now reads) falls back to the declared default, so narrowing a range in a new version of an addon cannot
 * leave a value nothing accepts.
 *
 * <p><b>{@code Changed} fires on a change, never on a write.</b> Writing the value already held is a no-op
 * with no event, which is what lets a control re-write what it reads without a feedback loop and what makes a
 * handler counting edges count edges.
 *
 * <p><b>The handle is interned per option</b>: one userdata minted on the first hand-out and given back by
 * identity ever after, so {@code opts:option():get("x") == opts:option():get("x")} and an option works as a
 * table key. The whole registry hangs off {@link Addon#addonOptions} and dies with the addon on
 * {@code :reload} or disable; the stored values are the client's and survive it.
 */
public final class LuaOption {
    /** How the collection is spelled in Lua, for its own messages and its members'. */
    static final String COLL = "hafen.client():options():addon():option()";

    /** The four kinds of option, and the name each is spelled by in Lua. */
    public enum Kind {
        BOOLEAN("boolean"), NUMBER("number"), CHOICE("choice"), TEXT("text");

        /** The verb that builds this kind, and what {@code opt:type()} answers. */
        public final String word;

        Kind(String word) {
            this.word = word;
        }
    }

    /** The addon that declared it — whose store the value lands in. */
    public final Addon owner;
    /** This addon's own name for the option, unique within it: the key, and its identity. */
    public final String name;
    public final Kind kind;

    /** The declared default. */
    final LuaValue def;
    /**
     * The value in force, written through to the preference store.
     *
     * <p><b>Volatile</b> (audit2 B06): Lua writes it from whatever thread the addon's handler ran on, and a
     * control bound to it reads it on the frame's own — two threads with no monitor between them, so the
     * barrier is the field's.
     */
    private volatile LuaValue value;
    /** A {@link Kind#NUMBER}'s inclusive bounds, whole numbers because the control that shows one is. */
    public final int lo, hi;
    /** A {@link Kind#CHOICE}'s offered values, in declaration order; {@code null} on every other kind. */
    public final List<String> choices;

    /** {@code opt:on("Changed", fn)} — the only key. */
    final Subs subs;

    LuaOption(AddonOptions.Builder b) {
        this.owner = b.owner;
        this.name = b.name;
        this.kind = b.kind;
        this.def = b.def;
        this.lo = b.lo;
        this.hi = b.hi;
        this.choices = b.choices;
        this.subs = new Subs(b.owner, Addon.C_HOOK);
        this.value = load();
    }

    /** {@code tostring(opt)} → {@code Option(show-timer, boolean)}. */
    public String toString() {
        return "Option(" + name + ", " + kind.word + ")";
    }

    /** Where this option's value is stored — one key per addon per name, like the keybinding registry's id. */
    public String prefKey() {
        return prefKey(owner, name);
    }

    /** {@link #prefKey()} for an option that is not built yet — what {@code :add()} checks the length of. */
    static String prefKey(Addon owner, String name) {
        return "addon/" + owner.manifest.id + "/opt/" + name;
    }

    /**
     * The stored value, or the declared default where nothing is stored or what is stored is no longer one
     * this declaration accepts. Called once, at declaration: this client is the only writer of the key.
     */
    private LuaValue load() {
        String key = prefKey();
        switch(kind) {
        case BOOLEAN:
            return LuaValue.valueOf(Utils.getprefb(key, Args.truthy(def)));
        case NUMBER: {
            int v = Utils.getprefi(key, def.toint());
            return LuaValue.valueOf((v < lo) || (v > hi) ? def.toint() : v);
        }
        case CHOICE: {
            String v = Utils.getpref(key, def.tojstring());
            return LuaValue.valueOf(choices.contains(v) ? v : def.tojstring());
        }
        default:
            return LuaValue.valueOf(Utils.getpref(key, def.tojstring()));
        }
    }

    /** Write {@link #value} through to the preference store — the same store every client setting lands in. */
    private void store() {
        String key = prefKey();
        switch(kind) {
        case BOOLEAN: Utils.setprefb(key, Args.truthy(value)); break;
        case NUMBER:  Utils.setprefi(key, value.toint());     break;
        default:      Utils.setpref(key, value.tojstring());  break;
        }
    }

    /** The value in force, as Lua reads it. */
    public LuaValue value() {
        return value;
    }

    /**
     * <b>The one write path</b>, and the whole of it: the value is checked against this option's own
     * declaration, stored, and {@code Changed} is fired — once, and only where the value actually moved. A
     * control bound to the option writes through here too, which is what makes the user's move and a Lua
     * write one fact rather than two that have to be kept in step.
     */
    public void value(LuaValue v) {
        LuaValue nv = check(v);
        if(nv.eq_b(value))
            return;                     // a write of the value already held is not a change
        value = nv;
        store();
        subs.fire(CHANGED, nv);
    }

    /** {@code v} as this option accepts it, or the refusal naming what it does accept. */
    private LuaValue check(LuaValue v) {
        switch(kind) {
        case BOOLEAN:
            return LuaValue.valueOf(Args.bool(v, "option:value", "v",
                                                 "the option '" + name + "' is a boolean"));
        case NUMBER: {
            int n = Args.integer(v, "option:value", "v", "between " + lo + " and " + hi + "; the option '"
                                 + name + "' is a whole number");
            if((n < lo) || (n > hi))
                throw new LuaError("option:value(v): " + n + " is outside the range " + lo + ".." + hi
                    + " the option '" + name + "' declared");
            return LuaValue.valueOf(n);
        }
        case CHOICE: {
            Args.str(v, "option:value", "v", "one of the choices this option declared");
            String s = v.tojstring();
            if(!choices.contains(s))
                throw new LuaError("option:value(v): '" + s + "' is not one of the choices the option '"
                    + name + "' declared — they are " + list(choices));
            return LuaValue.valueOf(s);
        }
        default: {
            Args.str(v, "option:value", "v", null);
            return LuaValue.valueOf(capped(v.tojstring(), "option:value"));
        }
        }
    }

    /**
     * A string as the preference store will take it. {@code java.util.prefs} throws
     * {@link IllegalArgumentException} past {@code MAX_VALUE_LENGTH}, and it throws it from inside
     * {@code Utils.setpref}, which catches only {@link SecurityException} — so the write would land as a raw
     * Java error naming neither the option nor the limit. Refused here instead, saying both.
     */
    static String capped(String s, String verb) {
        if(s.length() > java.util.prefs.Preferences.MAX_VALUE_LENGTH)
            throw new LuaError(verb + ": the value is " + s.length() + " characters and the client's"
                + " preference store takes " + java.util.prefs.Preferences.MAX_VALUE_LENGTH
                + " — keep the setting short and put the bulk in your addon's own saved variables");
        return s;
    }

    /** The list of choices as an English list, for a refusal that has to say what is on offer. */
    private static String list(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for(int i = 0; i < l.size(); i++) {
            if(i > 0)
                sb.append((i == l.size() - 1) ? " and " : ", ");
            sb.append('"').append(l.get(i)).append('"');
        }
        return sb.toString();
    }

    // ---- the Lua object ------------------------------------------------------------------------------

    /** The one event key an option fires. */
    static final String CHANGED = "Changed";

    /**
     * This option as Lua holds it, minted on the first ask and handed back by identity ever after —
     * interned on {@link Addon#optionHandles} (audit2 B10) rather than on a field of its own, so the check
     * and the mint are one act. {@code opts:addon():get("mode")} is read from a settings panel and from an
     * addon's own Lua, and two of those racing used to be able to hand out two userdata for one option and
     * break the {@code ==} an option's identity is.
     */
    LuaValue handle() {
        return owner.optionHandles.of(this, () -> LuaValue.userdataOf(this, meta()));
    }

    /** The {@code LuaOption} behind a Lua value, or {@code null} for anything that is not one. */
    static LuaOption resolve(LuaValue v) {
        if((v == null) || !v.isuserdata())
            return null;
        Object o = v.touserdata();
        return (o instanceof LuaOption) ? (LuaOption)o : null;
    }

    private static LuaOption self(LuaValue v, String method) {
        LuaOption o = resolve(v);
        if(o == null)
            throw new LuaError("opt:" + method + "() — use a COLON call on an Option object (what :add()"
                + " handed back, or " + COLL + ":get(name))");
        return o;
    }

    /**
     * The metatable, built once per <b>kind</b> and cached on the {@link Addon} — per addon like every other
     * metatable in the bridge (D-017), and per kind because the refusal names the kind: the vocabulary is
     * the same on all four, and what differs is the shape of the value it checks.
     */
    private LuaValue meta() {
        LuaValue mt = owner.optionMeta[kind.ordinal()];
        if(mt != null)
            return mt;
        LuaTable m = methods();
        LuaTable t = new LuaTable();
        t.set(LuaValue.INDEX, Refusal.closedIndex("option", m, "a " + kind.word + " option your addon"
            + " declared", "its value is read as :value() and written as :value(v); what shows it on your"
            + " page is a control you build inside " + AddonOptions.HANDLE + ":panel(fn)"));
        t.set("__name", LuaValue.valueOf("Option"));
        t.set("__tostring", new OneArgFunction() {
            public LuaValue call(LuaValue v) {
                LuaOption o = resolve(v);
                return LuaValue.valueOf((o == null) ? "Option(?)" : o.toString());
            }
        });
        owner.optionMeta[kind.ordinal()] = t;
        return t;
    }

    private static LuaTable methods() {
        LuaTable m = new LuaTable();
        // name() — this addon's own name for the option: what it declared, and what addresses it in the
        // collection. Its identity, so there is nothing to write.
        m.set("name", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(self(self, "name").name);
            }
        });
        // type() — which of the four kinds this option is, spelled as the builder that made it.
        m.set("type", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(self(self, "type").kind.word);
            }
        });
        // value() reads and value(v) writes, because arity is the verb. The write returns the OPTION, so
        // writes chain, and it fires Changed only where the value actually moved.
        m.set("value", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaOption o = self(a.arg1(), "value");
                LuaValue v = Args.written(a, 2, "option:value", "v");
                if(v == null)
                    return o.value();
                o.value(v);
                return a.arg1();
            }
        });
        // default() — the value the option was declared with, which is what it reads on a client that has
        // never been told otherwise. It never moves, so there is nothing to write.
        m.set("default", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return self(self, "default").def;
            }
        });
        // on("Changed", fn) — the API's one notification verb, on the object that changed. It carries the
        // NEW value, and it does not fire for a write of the value already held.
        m.set("on", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaOption o = self(a.arg1(), "on");
                LuaValue key = Args.required(a, 2, "option:on", "key");
                LuaValue fn = Args.required(a, 3, "option:on", "fn");
                if(key.type() != LuaValue.TSTRING)
                    throw new LuaError("option:on(key, fn): key must be a string, got " + key.typename());
                if(!fn.isfunction())
                    throw new LuaError("option:on(key, fn): fn must be a function — it runs with the new"
                        + " value when the option changes, got " + fn.typename());
                if(!CHANGED.equals(key.tojstring()))
                    throw new LuaError("option:on(key, fn): an option has no event '" + key.tojstring()
                        + "' — it has: Changed, which fires with the new value whenever the value moves,"
                        + " whether you wrote it or the user did");
                return o.subs.on(CHANGED, fn);
            }
        });
        // info() — the one snapshot escape hatch: the declaration and the value in force, and on a number
        // its bounds, on a choice what it offers. Nothing about how it is shown: that is the page's.
        m.set("info", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaOption o = self(self, "info");
                LuaTable t = new LuaTable();
                t.set("name", LuaValue.valueOf(o.name));
                t.set("type", LuaValue.valueOf(o.kind.word));
                t.set("value", o.value());
                t.set("default", o.def);
                if(o.kind == Kind.NUMBER) {
                    t.set("min", LuaValue.valueOf(o.lo));
                    t.set("max", LuaValue.valueOf(o.hi));
                }
                if(o.kind == Kind.CHOICE) {
                    LuaTable c = new LuaTable();
                    for(int i = 0; i < o.choices.size(); i++)
                        c.set(i + 1, LuaValue.valueOf(o.choices.get(i)));
                    t.set("choices", c);
                }
                return t;
            }
        });
        return m;
    }
}
