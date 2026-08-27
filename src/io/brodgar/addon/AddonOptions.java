package io.brodgar.addon;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.VarArgFunction;

import java.util.ArrayList;
import java.util.List;

/**
 * The <b>addon's own options</b> — {@code hafen.client():options():addon()} (spec
 * {@code 115-the-addon-declares-its-options}), the sibling of {@code keybindings()} that gives a setting of
 * an addon's own the standing its hotkeys already have. The addon names a row and its type; the client
 * renders the control, stores the value in its own preference store and answers reads.
 *
 * <pre>
 *   local opts = hafen.client():options():addon()
 *   local show = opts:boolean("show-timer"):label("Show the timer"):default(true):add()
 *   show:value(false)
 *   show:on("Changed", function(v) end)
 *   opts:button("reset"):label("Reset to defaults"):press(reset):add()
 * </pre>
 *
 * <p><b>Six typed builders, one per control the client draws.</b> All six take {@code :label} and
 * {@code :tooltip} and are dispatched by {@code :add()}; beyond that the kind decides the vocabulary, which
 * is why there are six verbs here rather than one {@code add(name, type, …)}: a single verb could not carry
 * {@code :range} for a number and {@code :choices} for a choice without both being ignorable on the wrong
 * type. A verb the kind does not carry is refused naming the kind that does and what this one takes instead
 * ({@link Builder#misfit}), because {@code has no verb 'press'} says the call is wrong without saying which
 * builder to reach for.
 *
 * <p><b>Built bare, dispatched on purpose</b> — the {@link HttpApi} request's shape, and the rule the API's
 * grammar states for every builder: every setter is legal until {@code :add()} and none after, which is a
 * rule with no timing in it. Nothing is declared until then either, so a builder that is abandoned half-way
 * costs the registry nothing and cannot take a name.
 *
 * <p><b>Unprotected, and client-scoped.</b> An addon's own option writes nothing outside itself, so it needs
 * no permission key — {@code client.settings} guards the <i>client's</i> settings, and gating this would
 * teach the wrong thing about what that key means. The value lands under
 * {@code addon/<addonid>/opt/<name>}, mirroring the keybinding id: it survives {@code :reload}, a disable
 * and a restart, and it is one per client rather than one per character, like every other setting the
 * Options window edits.
 */
public final class AddonOptions {
    private AddonOptions() {}

    /** How the handle is spelled in Lua, for its messages. */
    static final String HANDLE = "hafen.client():options():addon()";

    /** Create the addon-options handle for {@code owner} — one per addon, from {@link OptionsHandle}. */
    public static LuaValue create(final Addon owner) {
        return OptionsHandle.close(OptionsHandle.open("Options(addon)"), "addon options", methods(owner),
            "the options your addon declares",
            "each of the six builders is dispatched by :add(), and :option() is what it declared");
    }

    private static LuaTable methods(final Addon owner) {
        final LuaValue coll = collection(owner);
        LuaTable m = new LuaTable();
        for(final LuaOption.Kind kind : LuaOption.Kind.values()) {
            // boolean(name) / number(name) / choice(name) / text(name) / button(name) / label(name) — the six
            // builders. The name is this addon's own, and it is not taken until :add().
            m.set(kind.word, new VarArgFunction() {
                public Varargs invoke(Varargs a) {
                    String verb = "addon options:" + kind.word;
                    String name = Args.str(a, 2, verb, "name",
                        "your own name for the row, and how you address it afterwards").tojstring();
                    if(name.isEmpty())
                        throw new LuaError(verb + ": name must not be empty — it is what addresses the row");
                    return new Builder(owner, name, kind).handle();
                }
            });
        }
        // option() — the collection of the options THIS addon declared. It is the address, so there is no
        // get-by-name verb beside it: one collection and one object.
        m.set("option", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                if(Args.passed(a, 2))
                    throw new LuaError(HANDLE + ":option() takes no arguments — it IS the collection:"
                        + " :get(name) addresses one option and :list(filter) / :count(filter) search them");
                return coll;
            }
        });
        return m;
    }

    /**
     * {@code opts:option()} — every option this addon has declared, in declaration order, which is the order
     * the client draws them in. Another addon's options are not reachable through it: remapping any key is
     * something a user asks an addon to do, and writing another addon's settings behind its back is not.
     */
    private static LuaValue collection(final Addon owner) {
        return LuaCollection.create(LuaOption.COLL, new LuaCollection.Source() {
            public List<LuaValue> members() {
                List<LuaValue> out = new ArrayList<LuaValue>();
                synchronized(owner.addonOptions) {
                    for(LuaOption o : owner.addonOptions.values())
                        out.add(o.handle());
                }
                return out;
            }

            public boolean named() {
                return true;
            }

            public String needle(LuaValue member) {
                LuaOption o = LuaOption.resolve(member);
                return (o == null) ? null : o.name;
            }

            public boolean addressable() {
                return true;
            }

            public String keyName() {
                return "name";
            }

            public LuaValue getMember(LuaValue key) {
                if(key.type() != LuaValue.TSTRING)
                    throw new LuaError(LuaOption.COLL + ":get(name): expected an option name, got "
                        + key.typename());
                LuaOption o;
                synchronized(owner.addonOptions) {
                    o = owner.addonOptions.get(key.tojstring());
                }
                return (o == null) ? LuaValue.NIL : o.handle();
            }
        }, null);
    }

    // ---- the builder ---------------------------------------------------------------------------------

    /**
     * One row being declared. Bare at construction and dispatched by {@code :add()}, which is the whole of its
     * lifetime rule: everything below is legal until then and nothing after.
     */
    static final class Builder {
        final Addon owner;
        final String name;
        final LuaOption.Kind kind;
        String label, tooltip, text;
        LuaValue def, press;
        int lo, hi;
        boolean ranged;
        List<String> choices;
        /** Dispatched — {@code :add()} has run, so every setter refuses from here on. */
        boolean added;

        Builder(Addon owner, String name, LuaOption.Kind kind) {
            this.owner = owner;
            this.name = name;
            this.kind = kind;
        }

        public String toString() {
            return "OptionBuilder(" + name + ", " + kind.word + (added ? ", added" : "") + ")";
        }

        /** A setter refuses once the row has been ADDED: it is declared, and writing the field would lie. */
        void requireBare(String verb) {
            if(added)
                throw new LuaError("option:" + verb + ": the row '" + name + "' has already been added —"
                    + " configure it before :add(), which is the whole of the rule. What a declared row"
                    + " answers is on the Option " + LuaOption.COLL + ":get(\"" + name + "\") hands back");
        }

        /**
         * The refusal for a setter this kind does not carry — {@code :press} on a boolean, {@code :range} on
         * a text row. It names the builder that does take it and what this one takes instead, which is the
         * half a bare "has no verb" cannot say.
         */
        LuaError misfit(String verb, String owns, String instead) {
            return new LuaError("option:" + verb + ": only a " + owns + " row takes it, and '" + name
                + "' is a " + kind.word + " row — " + HANDLE + ":" + owns + "(name) is the builder that"
                + " does. A " + kind.word + " row takes " + instead);
        }

        /** What this kind carries beyond {@code :label} and {@code :tooltip}, for {@link #misfit}. */
        String carries() {
            switch(kind) {
            case BOOLEAN: return ":default(true|false)";
            case NUMBER:  return ":range(lo, hi) and :default(n)";
            case CHOICE:  return ":choices(t) and :default(s)";
            case TEXT:    return ":default(s)";
            case BUTTON:  return ":press(fn)";
            default:      return ":text(s)";
            }
        }

        LuaValue handle() {
            LuaTable m = new LuaTable();
            m.set("label", setter("label"));
            m.set("tooltip", setter("tooltip"));
            m.set("default", (kind.valued()) ? valueSetter()
                  : misfitSetter("default", "boolean", "a button row runs :press(fn) and a label row states"
                                 + " :text(s), and neither stores anything to have a default for"));
            m.set("range", (kind == LuaOption.Kind.NUMBER) ? rangeSetter() : misfitSetter("range", "number"));
            m.set("choices", (kind == LuaOption.Kind.CHOICE) ? choicesSetter()
                  : misfitSetter("choices", "choice"));
            m.set("press", (kind == LuaOption.Kind.BUTTON) ? pressSetter() : misfitSetter("press", "button"));
            m.set("text", (kind == LuaOption.Kind.LABEL) ? setter("text") : misfitSetter("text", "label"));
            m.set("add", new VarArgFunction() {
                public Varargs invoke(Varargs a) {
                    return Builder.this.add();
                }
            });
            final LuaValue h = LuaValue.userdataOf(this);
            LuaTable mt = new LuaTable();
            mt.set(LuaValue.INDEX, Refusal.closedIndex("option", m, "a row your addon is declaring",
                "it is dispatched by :add(): every setter is legal until then and none after"));
            mt.set("__name", LuaValue.valueOf("OptionBuilder"));
            mt.set("__tostring", new OneArgFunction() {
                public LuaValue call(LuaValue v) {
                    return LuaValue.valueOf(toString());
                }
            });
            h.setmetatable(mt);
            this.self = h;
            return h;
        }

        /** This builder as Lua holds it — what every setter chains back. */
        private LuaValue self;

        /** A plain string setter ({@code :label}, {@code :tooltip}, a label row's {@code :text}). */
        private VarArgFunction setter(final String verb) {
            return new VarArgFunction() {
                public Varargs invoke(Varargs a) {
                    requireBare(verb);
                    String s = Args.str(a, 2, "option:" + verb, "s", null).tojstring();
                    if("label".equals(verb))
                        label = s;
                    else if("tooltip".equals(verb))
                        tooltip = s;
                    else
                        text = LuaOption.capped(s, "option:text");
                    return self;
                }
            };
        }

        /** {@code :default(v)} — checked at {@link #add}, where the range and the choices are both known. */
        private VarArgFunction valueSetter() {
            return new VarArgFunction() {
                public Varargs invoke(Varargs a) {
                    requireBare("default");
                    def = Args.required(a, 2, "option:default", "v");
                    return self;
                }
            };
        }

        private VarArgFunction rangeSetter() {
            return new VarArgFunction() {
                public Varargs invoke(Varargs a) {
                    requireBare("range");
                    int l = Args.num(a, 2, "option:range", "lo", "the lowest whole number the row takes")
                                .toint();
                    int h = Args.num(a, 3, "option:range", "hi", "the highest whole number the row takes")
                                .toint();
                    if(l >= h)
                        throw new LuaError("option:range(lo, hi): lo must be below hi, got " + l + " and "
                            + h + " for the row '" + name + "'");
                    lo = l;
                    hi = h;
                    ranged = true;
                    return self;
                }
            };
        }

        private VarArgFunction choicesSetter() {
            return new VarArgFunction() {
                public Varargs invoke(Varargs a) {
                    requireBare("choices");
                    LuaValue t = Args.required(a, 2, "option:choices", "t");
                    if(!t.istable())
                        throw new LuaError("option:choices(t): t must be an array of strings — what the"
                            + " dropdown offers, got " + t.typename());
                    List<String> l = new ArrayList<String>();
                    for(int i = 1; ; i++) {
                        LuaValue v = t.get(i);
                        if(v.isnil())
                            break;
                        l.add(Args.str(v, "option:choices", "t[" + i + "]", "every choice is a string")
                                  .tojstring());
                    }
                    if(l.isEmpty())
                        throw new LuaError("option:choices(t): the row '" + name + "' offers nothing — t is"
                            + " a 1-based array of the strings the dropdown lists");
                    choices = l;
                    return self;
                }
            };
        }

        private VarArgFunction pressSetter() {
            return new VarArgFunction() {
                public Varargs invoke(Varargs a) {
                    requireBare("press");
                    LuaValue fn = Args.required(a, 2, "option:press", "fn");
                    if(!fn.isfunction())
                        throw new LuaError("option:press(fn): fn must be a function — it runs when the user"
                            + " presses the row, got " + fn.typename());
                    press = fn;
                    return self;
                }
            };
        }

        /** The setter this kind has not got: the same shape, and a refusal that names where to go. */
        private VarArgFunction misfitSetter(final String verb, final String owns) {
            return misfitSetter(verb, owns, null);
        }

        private VarArgFunction misfitSetter(final String verb, final String owns, final String why) {
            return new VarArgFunction() {
                public Varargs invoke(Varargs a) {
                    throw (why == null) ? misfit(verb, owns, carries())
                                        : new LuaError("option:" + verb + ": " + why);
                }
            };
        }

        /**
         * <b>Dispatch.</b> The declaration is checked whole here — this is the first moment every part of it
         * is in hand — the row is registered under its name, and the {@link LuaOption} it becomes is what
         * comes back.
         */
        LuaValue add() {
            if(added)
                throw new LuaError("option:add(): the row '" + name + "' has already been added — build"
                    + " another with " + HANDLE + ":" + kind.word + "(name)");
            String key = LuaOption.prefKey(owner, name);
            if(key.length() > java.util.prefs.Preferences.MAX_KEY_LENGTH)
                throw new LuaError("option:add(): the row '" + name + "' stores under '" + key + "', which is"
                    + " " + key.length() + " characters and the client's preference store takes "
                    + java.util.prefs.Preferences.MAX_KEY_LENGTH + " — give the row a shorter name");
            if(kind == LuaOption.Kind.BUTTON) {
                if(press == null)
                    throw new LuaError("option:add(): the button row '" + name + "' does nothing — give it"
                        + " the function to run with :press(fn)");
            }
            if(kind == LuaOption.Kind.CHOICE) {
                if(choices == null)
                    throw new LuaError("option:add(): the choice row '" + name + "' offers nothing — list"
                        + " what the dropdown holds with :choices(t)");
            }
            if(kind == LuaOption.Kind.NUMBER) {
                if(!ranged)
                    throw new LuaError("option:add(): the number row '" + name + "' has no bounds — the"
                        + " client draws it as a slider, so declare them with :range(lo, hi)");
            }
            if(kind.valued()) {
                if(def == null)
                    throw new LuaError("option:add(): the " + kind.word + " row '" + name + "' carries a"
                        + " value, so it needs the one it reads on a client that has never been told"
                        + " otherwise — declare it with :default(v)");
                checkDefault();
            }
            synchronized(owner.addonOptions) {
                if(owner.addonOptions.containsKey(name))
                    throw new LuaError("option:add(): this addon already declared an option named '" + name
                        + "' — " + LuaOption.COLL + ":get(\"" + name + "\") is the one it has, and a row"
                        + " needs a name of its own");
                added = true;
                LuaOption o = new LuaOption(this);
                owner.addonOptions.put(name, o);
                return o.handle();
            }
        }

        /** The declared default, against the declaration it is a default for. */
        private void checkDefault() {
            switch(kind) {
            case BOOLEAN:
                if(!def.isboolean())
                    throw new LuaError("option:add(): the row '" + name + "' is a boolean and its default is"
                        + " " + def.typename() + " — pass true or false to :default(v)");
                break;
            case NUMBER: {
                Args.num(def, "option:add", "the default of '" + name + "'",
                         "a whole number between " + lo + " and " + hi);
                double d = def.todouble();
                if(d != Math.rint(d))
                    throw new LuaError("option:add(): the default " + d + " of the row '" + name + "' is not"
                        + " a whole number, and the client draws a number row as a slider — round it, or"
                        + " scale the range you declared");
                int n = (int)d;
                if((n < lo) || (n > hi))
                    throw new LuaError("option:add(): the default " + n + " of the row '" + name + "' is"
                        + " outside the range " + lo + ".." + hi + " it declared — widen :range(lo, hi) or"
                        + " move :default(v) inside it");
                def = LuaValue.valueOf(n);
                break;
            }
            case CHOICE: {
                Args.str(def, "option:add", "the default of '" + name + "'", "one of the choices it offers");
                if(!choices.contains(def.tojstring()))
                    throw new LuaError("option:add(): the default '" + def.tojstring() + "' of the row '"
                        + name + "' is not one of the choices it offers — :default(v) takes one of them");
                break;
            }
            default:
                Args.str(def, "option:add", "the default of '" + name + "'", null);
                LuaOption.capped(def.tojstring(), "option:add");
                break;
            }
        }
    }
}
