package io.brodgar.addon;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.TwoArgFunction;
import org.luaj.vm2.lib.VarArgFunction;

/**
 * A <b>section object</b> — the one shape every {@code hafen.*} subsystem now has (spec
 * {@code 039-uniform-api} §2.1): <b>a section is CALLED, and everything after it is a colon verb.</b>
 * {@code hafen.time()} hands back the section object; {@code hafen.time():clock()} reads the clock. There
 * are no dotted sub-verbs and no colon-on-the-namespace anywhere.
 *
 * <p><b>Per-addon singleton, handed back by identity.</b> The object is minted once, in {@code installHafen},
 * and the callable table's {@code __call} closes over it — so {@code hafen.time() == hafen.time()} and a
 * section called inside a draw callback at 60 fps allocates nothing. That is the whole reason it is not
 * constructed per call.
 *
 * <p><b>The section itself stays a callable TABLE, never a bare function.</b> A function would make
 * {@code hafen.time.clock} fail as <i>"attempt to index a function"</i>; a table with {@code __call} makes
 * the field read reach {@link Retired}, which is where the cut becomes a message naming its replacement.
 * It also keeps {@code pcall(hafen.x, …)} working, which several addons rely on.
 *
 * <p><b>Arguments are refused, and that is §2.9's discipline at the door.</b> {@code hafen.time(nil)} throws
 * rather than quietly answering the section object, because an explicit {@code nil} is an accident
 * everywhere the page does not document a meaning for it. The one gap is inherent to the bridge:
 * {@code hafen.time(f())} where {@code f} returns <i>nothing</i> arrives as no argument at all and is read as
 * the plain call — see {@link Args}.
 *
 * <p><b>A section object is immutable from Lua</b> (userdata with a metatable: {@code set} errors without a
 * {@code __newindex}), and an unknown verb <b>throws naming the section</b> rather than reading {@code nil},
 * so a typo fails where it was written instead of one call later.
 *
 * <p><b>Where a section contains exactly one thing, the section object IS that thing</b> — the roster, the
 * collection, the character. That is not an exception to the rule but the rule with a collection of one, and
 * it is mounted with {@link #mount} rather than {@link #install}.
 */
public final class Section {
    /** The section's name, as it is spelled in Lua ({@code "time"}). The whole state of the object. */
    public final String name;

    private Section(String name) {
        this.name = name;
    }

    /** {@code tostring(hafen.time())} → {@code hafen.time()}. */
    public String toString() {
        return "hafen." + name + "()";
    }

    /**
     * Mount a plain section: mint its singleton object over {@code methods}, hang the callable table on
     * {@code hafen}, and hand the object back (callers that need it for a later verb).
     */
    static LuaValue install(LuaTable hafen, String nm, LuaTable methods) {
        return install(hafen, nm, methods, null);
    }

    /** As {@link #install(LuaTable, String, LuaTable)}, with a hint appended to the takes-no-arguments error. */
    static LuaValue install(LuaTable hafen, String nm, LuaTable methods, String hint) {
        LuaValue obj = object(nm, methods);
        mount(hafen, nm, obj, hint);
        return obj;
    }

    /**
     * Mint a section object over {@code methods} <b>without</b> mounting it, for a section whose callable table
     * is not empty yet: a migration that moves a section's verbs one task at a time leaves the ones a later task
     * owns standing as plain fields, and those go on the table handed to {@link #mount}. A field the table
     * carries is found by {@code rawget} and never reaches {@link Retired}, so the two halves coexist without a
     * rule between them — and the day the last field moves, the caller drops back to {@link #install}.
     */
    static LuaValue object(String nm, LuaTable methods) {
        return LuaValue.userdataOf(new Section(nm), meta(nm, methods));
    }

    /**
     * Mount {@code obj} as the section object of {@code name}: the callable table hands it back by identity,
     * and a retired verb of that section throws from the table's {@code __index}. Used directly where the
     * section object is not a {@link Section} but the one thing the section contains (§2.1) — a collection,
     * a roster, the player.
     *
     * @param hint appended to the "takes no arguments" error, for a section whose old shortcut form took one
     *             ({@code hafen.log():write(msg)}); {@code null} for the rest.
     */
    static void mount(LuaTable hafen, final String nm, final LuaValue obj, final String hint) {
        mount(hafen, nm, obj, hint, new LuaTable());
    }

    /**
     * As {@link #mount(LuaTable, String, LuaValue, String)}, over a callable table that already carries fields —
     * the verbs of this section a later task still owns (see {@link #object}). Their presence changes nothing:
     * {@code __index} is consulted on a miss only.
     */
    static void mount(LuaTable hafen, final String nm, final LuaValue obj, final String hint, LuaTable t) {
        mount(hafen, nm, obj, hint, t, Retired.sectionIndex(nm));
    }

    /**
     * As {@link #mount(LuaTable, String, LuaValue, String, LuaTable)}, with the callable table's {@code __index}
     * supplied by the caller. {@link Retired} is a <b>static</b> table of names this migration renamed, which is
     * enough for every section whose verbs are the same for every addon — and not enough for the one whose field
     * names are the <i>addon's own</i>: {@code hafen.store.<name>} is a manifest-declared saved variable, so the
     * spellings that have to throw are only known per owner. Such a section builds its own index and falls
     * through to {@link Retired#sectionIndex} for everything else.
     */
    static void mount(LuaTable hafen, final String nm, final LuaValue obj, final String hint, LuaTable t,
                      LuaValue index) {
        LuaTable mt = new LuaTable();
        // `nm`, never `name`: LuaJ's LibFunction declares a `protected String name`, and an inherited field
        // shadows an enclosing method's parameter of the same name inside an anonymous subclass (019.4).
        mt.set(LuaValue.CALL, new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                // arg1 is the callable table itself, so a real argument starts at 2. narg() separates
                // hafen.x() from hafen.x(v) exactly, including a v that is nil (§2.9 / Args).
                if(Args.passed(a, 2))
                    throw new LuaError("hafen." + nm + "(...) takes no arguments: hafen." + nm
                        + "() IS the section object and every verb is a colon call on it"
                        + ((hint == null) ? "" : " — " + hint));
                return obj;
            }
        });
        mt.set(LuaValue.INDEX, index);
        t.setmetatable(mt);
        hafen.set(nm, t);
    }

    /**
     * The receiver of a colon call on section {@code name}, or a guiding error. A dot call
     * ({@code hafen.time().clock()}) passes the wrong self and is the mistake this message exists for.
     */
    static Section self(LuaValue v, String nm, String method) {
        Section s = null;
        if((v != null) && v.isuserdata()) {
            Object o = v.touserdata();
            if(o instanceof Section)
                s = (Section)o;
        }
        if((s == null) || !s.name.equals(nm))
            throw new LuaError("hafen." + nm + "():" + method + "() — use a COLON call on the section"
                + " object (hafen." + nm + "():" + method + "(…))");
        return s;
    }

    /**
     * The per-section metatable: methods by name, an unknown verb throws, and a readable {@code tostring}.
     *
     * <p>A verb the section <b>used to</b> have throws its own message first ({@link Retired}, keyed
     * {@code "hafen.<section>():<verb>"}): a section that loses a verb to somewhere else in the API — 041.2's
     * {@code hafen.hook():action} to {@code hafen.event():action():on} — would otherwise fail with the generic
     * "has no verb", which says the call is wrong without saying what is right. The dotted pre-039 spelling of
     * the same verb is a separate row on the callable table's own {@code __index}, so both call sites are
     * answered.
     */
    private static LuaValue meta(final String nm, final LuaTable methods) {
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, new TwoArgFunction() {
            public LuaValue call(LuaValue self, LuaValue key) {
                LuaValue m = methods.rawget(key);
                if(!m.isnil())
                    return m;
                if(key.isstring()) {
                    String msg = Retired.message("hafen." + nm + "():" + key.tojstring());
                    if(msg != null)
                        throw new LuaError(msg);
                }
                throw new LuaError("hafen." + nm + "() has no verb '" + key.tojstring() + "'");
            }
        });
        mt.set("__name", LuaValue.valueOf("Section"));
        mt.set("__tostring", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf("hafen." + nm + "()");
            }
        });
        return mt;
    }
}
