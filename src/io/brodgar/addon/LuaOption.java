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
 * One <b>option an addon declared</b> (spec {@code 115-the-addon-declares-its-options}) — what
 * {@code hafen.client():options():addon():<type>(name)…:add()} hands back, and the record the client draws a
 * control from and stores a value for.
 *
 * <p><b>Six kinds, and the kind decides the vocabulary.</b> Four of them carry a value — a
 * {@link Kind#BOOLEAN}, a {@link Kind#NUMBER} over a declared range, a {@link Kind#CHOICE} out of a declared
 * list and a {@link Kind#TEXT} — and answer {@code :value()}/{@code :value(v)}, {@code :default()} and
 * {@code :on("Changed", fn)}. Two do not: a {@link Kind#BUTTON} runs the function it was declared with and a
 * {@link Kind#LABEL} states a line the addon rewrites, and neither persists anything. {@code :value()} on
 * either of those is refused naming what the row does carry, rather than answering {@code nil} and leaving
 * the author to guess which half of the surface they are on.
 *
 * <p><b>The value in force lives here, not in the preference store.</b> {@link #value} is loaded from
 * {@code addon/<addonid>/opt/<name>} once, at declaration, and written back on every change: nothing but this
 * client writes that key, so a read costs a field rather than a {@code java.util.prefs} lookup — which
 * matters because the panel that draws the row re-reads it every frame. A stored value the declaration can no
 * longer honour (a choice that is no longer offered, a number outside the range as it now reads) falls back
 * to the declared default, so narrowing a range in a new version of an addon cannot leave a value nothing
 * accepts.
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

    /** The six kinds of row, and the name each is spelled by in Lua. */
    public enum Kind {
        BOOLEAN("boolean"), NUMBER("number"), CHOICE("choice"), TEXT("text"), BUTTON("button"), LABEL("label");

        /** The verb that builds this kind, and what {@code opt:type()} answers. */
        public final String word;

        Kind(String word) {
            this.word = word;
        }

        /** Does this kind carry a value the client stores? (The other two run or state something.) */
        public boolean valued() {
            return (this != BUTTON) && (this != LABEL);
        }
    }

    /** The addon that declared it — whose store the value lands in, and whose panel page the row is on. */
    public final Addon owner;
    /** This addon's own name for the row, unique within it: the key, and the label's fallback. */
    public final String name;
    public final Kind kind;
    /** The caption the client draws beside the control. Defaults to {@link #name}; empty on a label row. */
    public final String label;
    /** The hover text, or {@code null}. */
    public final String tooltip;

    /** The declared default — {@code nil} on the two kinds that carry no value. */
    final LuaValue def;
    /** The value in force, written through to the preference store. {@code nil} on the two kinds without one. */
    private LuaValue value;
    /** A {@link Kind#NUMBER}'s inclusive bounds, whole numbers because the control that draws it is. */
    public final int lo, hi;
    /** A {@link Kind#CHOICE}'s offered values, in declaration order; {@code null} on every other kind. */
    public final List<String> choices;
    /** A {@link Kind#BUTTON}'s handler — what the client runs when the row is pressed. */
    final LuaValue press;
    /** A {@link Kind#LABEL}'s line, the one piece of a row the addon rewrites live. */
    private String text;

    /** {@code opt:on("Changed", fn)} — the only key, and only on a kind that carries a value. */
    final Subs subs;
    /** This option as Lua holds it, minted on the first hand-out and handed back by identity ever after. */
    private LuaValue self;

    LuaOption(AddonOptions.Builder b) {
        this.owner = b.owner;
        this.name = b.name;
        this.kind = b.kind;
        this.label = (b.label != null) ? b.label : ((b.kind == Kind.LABEL) ? "" : b.name);
        this.tooltip = b.tooltip;
        this.def = b.def;
        this.lo = b.lo;
        this.hi = b.hi;
        this.choices = b.choices;
        this.press = b.press;
        this.text = (b.text != null) ? b.text : "";
        this.subs = new Subs(b.owner, Addon.C_HOOK);
        this.value = kind.valued() ? load() : LuaValue.NIL;
    }

    /** {@code tostring(opt)} → {@code Option(show-timer, boolean)}. */
    public String toString() {
        return "Option(" + name + ", " + kind.word + ")";
    }

    /** Where this option's value is stored — one key per addon per name, like the keybinding registry's id. */
    public String prefKey() {
        return prefKey(owner, name);
    }

    /** {@link #prefKey()} for a row that is not built yet — what {@code :add()} checks the length of. */
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
            return LuaValue.valueOf(Utils.getprefb(key, def.toboolean()));
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
        case BOOLEAN: Utils.setprefb(key, value.toboolean()); break;
        case NUMBER:  Utils.setprefi(key, value.toint());     break;
        default:      Utils.setpref(key, value.tojstring());  break;
        }
    }

    /** The value in force, as Lua reads it. {@code nil} on the two kinds that carry none. */
    public LuaValue value() {
        return value;
    }

    /**
     * <b>The one write path</b>, and the whole of it: the value is checked against this row's own
     * declaration, stored, and {@code Changed} is fired — once, and only where the value actually moved. The
     * control the client draws writes through here too, which is what makes a panel's move and a Lua write
     * one fact rather than two that have to be kept in step.
     */
    public void value(LuaValue v) {
        LuaValue nv = check(v);
        if(nv.eq_b(value))
            return;                     // a write of the value already held is not a change
        value = nv;
        store();
        subs.fire(CHANGED, nv);
    }

    /** {@code v} as this row accepts it, or the refusal naming what this row does accept. */
    private LuaValue check(LuaValue v) {
        switch(kind) {
        case BOOLEAN:
            if(!v.isboolean())
                throw new LuaError("option:value(v): the row '" + name + "' is a boolean, got " + v.typename()
                    + " — pass true or false");
            return LuaValue.valueOf(v.toboolean());
        case NUMBER: {
            Args.num(v, "option:value", "v", "a whole number between " + lo + " and " + hi);
            double d = v.todouble();
            if(d != Math.rint(d))
                throw new LuaError("option:value(v): the row '" + name + "' is a whole number and the client"
                    + " draws it as a slider, got " + d + " — round it, or scale the range you declared");
            int n = (int)d;
            if((n < lo) || (n > hi))
                throw new LuaError("option:value(v): " + n + " is outside the range " + lo + ".." + hi
                    + " the row '" + name + "' declared");
            return LuaValue.valueOf(n);
        }
        case CHOICE: {
            Args.str(v, "option:value", "v", "one of the choices this row declared");
            String s = v.tojstring();
            if(!choices.contains(s))
                throw new LuaError("option:value(v): '" + s + "' is not one of the choices the row '" + name
                    + "' declared — they are " + list(choices));
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

    /** A label row's line, as it stands. */
    public String text() {
        return text;
    }

    /** Rewrite a label row's line — the one part of a row an addon moves without it being a value. */
    void text(String s) {
        this.text = s;
    }

    /** A button row's handler, for the client to run when the row is pressed. */
    public LuaValue handler() {
        return press;
    }

    // ---- the same value, in Java's words -------------------------------------------------------------
    //
    // The panel that draws the row is in another package and has no business holding LuaValues: it reads a
    // boolean, an int or a String and writes one back. Every write below funnels through value(LuaValue)
    // above -- THE one write path -- so a control's move and a Lua write are one fact, and the panel gets
    // Changed and the store for free rather than having a second half to keep in step.

    /** A boolean row's value. */
    public boolean bool() {
        return value.toboolean();
    }

    /** A number row's value, inside the range it declared. */
    public int num() {
        return value.toint();
    }

    /**
     * A text row's line, or a choice row's pick — and on a choice, the element of {@link #choices}
     * <b>by identity</b>. {@code SDropBox.change(I)} compares the incoming item against its own {@code sel}
     * with {@code !=}, so a fresh String equal to the pick would rebuild the closed box on every frame the
     * panel reads its value.
     */
    public String str() {
        String s = value.tojstring();
        if(choices != null) {
            for(String c : choices) {
                if(c.equals(s))
                    return c;
            }
        }
        return s;
    }

    /** Write a boolean row. */
    public void set(boolean b) {
        value(LuaValue.valueOf(b));
    }

    /** Write a number row. */
    public void set(int n) {
        value(LuaValue.valueOf(n));
    }

    /** Write a text or choice row. */
    public void set(String s) {
        value(LuaValue.valueOf(s));
    }

    /**
     * Run a button row's handler — the client pressing the row on the user's behalf. It goes through
     * {@link AddonManager#callLua}, the one watchdog-armed, CPU-accounted, error-isolated door into Lua, so
     * a handler that throws cannot escape into the input pass that pressed it.
     */
    public void press() {
        if(press != null)
            AddonManager.callLua(owner, Addon.C_HOOK, press);
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

    /** The one event key an option fires, on the four kinds that carry a value. */
    static final String CHANGED = "Changed";

    /** This option as Lua holds it, minted on the first ask. */
    LuaValue handle() {
        if(self == null)
            self = LuaValue.userdataOf(this, meta());
        return self;
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
     * metatable in the bridge (D-017), and per kind because the kind IS the vocabulary: a button has no
     * value to read and a label has no default, and answering those with {@code nil} is the one answer that
     * teaches nothing.
     */
    private LuaValue meta() {
        LuaValue mt = owner.optionMeta[kind.ordinal()];
        if(mt != null)
            return mt;
        LuaTable m = methods(kind);
        LuaTable t = new LuaTable();
        t.set(LuaValue.INDEX, Refusal.closedIndex("option", m, "an option your addon declared",
            kind.valued() ? "its value is read as :value() and written as :value(v)"
                          : ("a " + kind.word + " row carries no value: "
                             + ((kind == Kind.BUTTON) ? "it runs the function you gave :press(fn)"
                                                      : "it states the line :text(s) holds"))));
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

    private static LuaTable methods(final Kind kind) {
        LuaTable m = new LuaTable();
        // name() — this addon's own name for the row: what it declared, and what addresses it in the
        // collection. Its identity, so there is nothing to write.
        m.set("name", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(self(self, "name").name);
            }
        });
        // type() — which of the six kinds this row is, spelled as the builder that made it.
        m.set("type", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(self(self, "type").kind.word);
            }
        });
        // label() / tooltip() — what the client draws for the row. Reads only: they are the declaration, and
        // the builder is where a declaration is made.
        m.set("label", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(self(self, "label").label);
            }
        });
        m.set("tooltip", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                String t = self(self, "tooltip").tooltip;
                return (t == null) ? LuaValue.NIL : LuaValue.valueOf(t);
            }
        });
        if(kind.valued()) {
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
            // default() — the value the row was declared with, which is what it reads on a client that has
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
        } else {
            // value() is REFUSED on the two kinds that carry none, rather than answering nil: a button and a
            // label are the halves of this surface an author reaches for by analogy, and nil would let the
            // mistake fail one line later saying nothing about which half they are on.
            m.set("value", new VarArgFunction() {
                public Varargs invoke(Varargs a) {
                    LuaOption o = self(a.arg1(), "value");
                    throw new LuaError("option:value(): the row '" + o.name + "' is a " + o.kind.word
                        + " row and carries no value — "
                        + ((o.kind == Kind.BUTTON)
                           ? "it runs the function you gave :press(fn), and nothing about it is stored"
                           : "it states the line :text(s) holds, which is what to write instead"));
                }
            });
        }
        if(kind == Kind.LABEL) {
            // text() reads the line, text(s) rewrites it. A label is the one row an addon moves that is not a
            // value: nothing is stored and nothing is notified, because the client re-reads it as it draws.
            m.set("text", new VarArgFunction() {
                public Varargs invoke(Varargs a) {
                    LuaOption o = self(a.arg1(), "text");
                    LuaValue v = Args.written(a, 2, "option:text", "s");
                    if(v == null)
                        return LuaValue.valueOf(o.text());
                    o.text(Args.str(v, "option:text", "s", null).tojstring());
                    return a.arg1();
                }
            });
        }
        // info() — the one snapshot escape hatch, carrying exactly what this kind of row has.
        m.set("info", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaOption o = self(self, "info");
                LuaTable t = new LuaTable();
                t.set("name", LuaValue.valueOf(o.name));
                t.set("type", LuaValue.valueOf(o.kind.word));
                t.set("label", LuaValue.valueOf(o.label));
                if(o.tooltip != null)
                    t.set("tooltip", LuaValue.valueOf(o.tooltip));
                if(o.kind.valued()) {
                    t.set("value", o.value());
                    t.set("default", o.def);
                }
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
                if(o.kind == Kind.LABEL)
                    t.set("text", LuaValue.valueOf(o.text()));
                return t;
            }
        });
        return m;
    }
}
