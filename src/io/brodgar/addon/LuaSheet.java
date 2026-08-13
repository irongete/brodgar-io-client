package io.brodgar.addon;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.VarArgFunction;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A <b>Sheet object</b> — one addon's stylesheet as Lua writes it (spec {@code 039-uniform-api} §5.4, task
 * 039.7), and what replaced {@code hafen.ui.skin{…}}:
 *
 * <pre>
 *   local s = hafen.ui():sheet()
 *   s:rule("window.title"):font(body)
 *   s:rule("chat"):color(200, 210, 200)
 *   s:install()                                  -- and s:drop()
 * </pre>
 *
 * <p><b>An addon has exactly one sheet</b>, handed back by identity from {@code hafen.ui():sheet()} — a
 * stylesheet is a selector &rarr; properties map, which has no chained spelling of its own, so the answer is a
 * document you edit and then apply. {@link LuaRule} is the level; this is the set of them, and the two verbs
 * that say whether it is in force.
 *
 * <p><b>The document and the application are separate, and only one of them is a verb.</b> Editing a rule
 * changes what the sheet <i>says</i>; {@code :install()} makes it what the client <i>looks like</i>. An edit to
 * an installed sheet applies at once — which is one rule rather than two, and is why there is no re-apply verb:
 * a sheet is either in force, in which case what it says is what you see, or it is not.
 *
 * <p><b>{@code :load(t)} is the DATA door</b> (§2.8): a whole sheet as a parsed table, the shape a
 * {@code theme.json} arrives in, replacing whatever this sheet said. It is not a config table standing in for
 * named arguments — it is a document, and the capability it keeps is a client theme with no Lua that names a
 * surface, a font, a colour or a pixel.
 *
 * <p><b>Whether it is applied is DERIVED, never stored</b>: teardown drops an addon's sheet without asking this
 * object, so a stored flag would be a second answer that could go stale. {@link Sheet#applied} is the one.
 */
public final class LuaSheet {
    /** The addon whose sheet this is — the owner tag on everything it installs. */
    private final Addon owner;
    /**
     * The rules, in the order they were first named: a later rule wins an equal-specificity tie, so the order
     * is part of what the sheet says. Entries are never dropped, only emptied, because a {@link LuaRule} handle
     * is a NAME for its level and must go on answering after a {@code :remove()}.
     */
    private final Map<String, Rec> rules = new LinkedHashMap<String, Rec>();
    /** This object as Lua holds it — minted once, handed back by identity. */
    private LuaValue self;

    private LuaSheet(Addon owner) {
        this.owner = owner;
    }

    /** {@code tostring(s)} → {@code Sheet(n rules)}. */
    public String toString() {
        synchronized(this) {
            return "Sheet(" + rules.size() + " rule" + ((rules.size() == 1) ? "" : "s") + ")";
        }
    }

    /** One named level: the key as written, what it resolves to, what it says, and its Lua handle. */
    private static final class Rec {
        final Selector sel;         // the parsed key — a bad one errors exactly as hafen.ui():find(sel) does
        final String site;          // the Fonts scope this key names, or null when it is a tree key
        final Sheet.Props props = new Sheet.Props();
        LuaValue handle;            // the interned LuaRule for this selector

        Rec(Selector sel, String site) {
            this.sel = sel;
            this.site = site;
        }
    }

    /** {@code hafen.ui():sheet()} — mint this addon's one sheet. From {@code installUi}, once. */
    static LuaValue of(Addon owner) {
        LuaSheet s = new LuaSheet(owner);
        s.self = LuaValue.userdataOf(s, meta(owner));
        return s.self;
    }

    /** This sheet as Lua holds it — what {@code rule:sheet()} climbs back to. */
    LuaValue handle() {
        return self;
    }

    /** The {@code LuaSheet} behind a Lua value, or {@code null} for anything that is not one. */
    static LuaSheet resolve(LuaValue v) {
        if((v == null) || !v.isuserdata())
            return null;
        Object o = v.touserdata();
        return (o instanceof LuaSheet) ? (LuaSheet)o : null;
    }

    // ---- what a Rule reads and writes through --------------------------------------------------------

    /** The live property record behind {@code selector} — a {@link LuaRule} only ever holds the name. */
    synchronized Sheet.Props props(String selector) {
        Rec r = rules.get(selector);
        return (r == null) ? null : r.props;
    }

    /** The render site {@code selector} names, or {@code null} when it is a tree key (see {@link Sheet#siteOf}). */
    synchronized String site(String selector) {
        Rec r = rules.get(selector);
        return (r == null) ? null : r.site;
    }

    /** The parsed selector behind a key — what tells a layout refusal a tree rule from a widget's own level. */
    synchronized Selector selector(String selector) {
        Rec r = rules.get(selector);
        return (r == null) ? null : r.sel;
    }

    /** {@code rule:remove()} on a sheet rule: this level stops saying anything, the name goes on existing. */
    void clear(String selector) {
        synchronized(this) {
            Rec r = rules.get(selector);
            if(r == null)
                return;
            r.props.clear();
        }
        changed();
    }

    /** A rule changed: an installed sheet is what the client looks like, so it says so now. */
    void changed() {
        if(Sheet.applied(owner))
            apply();
    }

    /**
     * Freeze what this sheet says and make it the applied one. A rule that names no property is left out
     * entirely, which is what keeps the provider's identity fast path intact for a sheet of empty rules.
     */
    private void apply() {
        List<Sheet.Rule> out = new ArrayList<Sheet.Rule>();
        synchronized(this) {
            for(Map.Entry<String, Rec> e : rules.entrySet()) {
                Rec r = e.getValue();
                if(r.props.empty())
                    continue;
                out.add(new Sheet.Rule(r.site, (r.site == null) ? r.sel : null, r.props));
            }
        }
        Sheet.apply(owner, out);   // outside this object's lock: it sweeps the tree (036.2)
    }

    // ---- the metatable ------------------------------------------------------------------------------

    private static LuaValue meta(final Addon owner) {
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, Retired.closedIndex("sheet", methods(owner),
            "a sheet's verbs are :rule(selector) :load(rules) :install() :drop() and :info()"));
        mt.set("__name", LuaValue.valueOf("Sheet"));
        mt.set("__tostring", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaSheet s = resolve(self);
                return LuaValue.valueOf((s == null) ? "Sheet(?)" : s.toString());
            }
        });
        return mt;
    }

    private static LuaTable methods(final Addon owner) {
        LuaTable m = new LuaTable();
        // rule(selector) — the level this sheet says about everything that selector matches, interned per
        // selector so naming it twice is naming it once. The selector is the very string hafen.ui():find takes
        // (one vocabulary, not two) and a malformed one errors the same way.
        m.set("rule", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaSheet s = handle(a.arg1(), "rule");
                LuaValue k = Args.required(a, 2, "sheet:rule", "selector");
                if(k.isnumber())    // BEFORE isstring(): in LuaJ a number IS a string (the hafen.asset lesson)
                    throw new LuaError("sheet:rule(selector): a rule is named by a SELECTOR string (e.g. \"*\","
                        + " \"chat\", \"window.title\"), not a number");
                if(!k.isstring())
                    throw new LuaError("sheet:rule(selector): expected a selector string, got " + k.typename());
                return s.ruleFor(owner, k.tojstring());
            }
        });
        // load(t) — a whole sheet as DATA: [selector] = { property = value }, replacing what this sheet said.
        // The theme door (a parsed theme.json goes straight in, unmapped except for the two values JSON cannot
        // carry, which are handles). The table is parsed entirely BEFORE anything is committed, so a malformed
        // rule leaves the sheet exactly as it was.
        m.set("load", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue me = a.arg1();
                LuaSheet s = handle(me, "load");
                LuaValue t = Args.required(a, 2, "sheet:load", "rules");
                if(!t.istable())
                    throw new LuaError("sheet:load(rules): expected a table of [\"selector\"] = { font = h }"
                        + " rules, got " + t.typename() + " — sheet:rule(selector) names one by hand");
                s.load(owner, t);
                return me;
            }
        });
        // install() — apply what this sheet says, replacing whatever this addon had installed before (whole,
        // not rule by rule: a surface the sheet no longer names falls back on the spot). Live, and OWNED — a
        // :reload or disable drops it, so the stock client is always restorable.
        m.set("install", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                handle(self, "install").apply();
                return self;
            }
        });
        // drop() — stop applying it; every surface it styled falls back to another addon's sheet, else to
        // stock. The document is untouched, so :install() puts it back. Inert when nothing was installed.
        m.set("drop", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                handle(self, "drop");
                Sheet.dropSheet(owner);
                return self;
            }
        });
        // info() — the snapshot hatch: whether the sheet is applied right now, and the selectors it names, in
        // the order it named them (which is the order that breaks an equal-specificity tie).
        m.set("info", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaSheet s = handle(self, "info");
                LuaTable t = new LuaTable();
                t.set("installed", LuaValue.valueOf(Sheet.applied(owner)));
                LuaTable rs = new LuaTable();
                int n = 0;
                for(String key : s.selectors())
                    rs.set(++n, LuaValue.valueOf(key));
                t.set("rules", rs);
                return t;
            }
        });
        return m;
    }

    /** The selectors this sheet says something about, in the order it first named them. */
    private synchronized List<String> selectors() {
        List<String> out = new ArrayList<String>();
        for(Map.Entry<String, Rec> e : rules.entrySet()) {
            if(!e.getValue().props.empty())
                out.add(e.getKey());
        }
        return out;
    }

    /** {@code sheet:rule(selector)} — the interned Rule for that key, minting the level's record on first use. */
    private synchronized LuaValue ruleFor(Addon owner, String selector) {
        Rec r = rules.get(selector);
        if(r == null) {
            Selector sel = Selector.parse(selector);   // a bad key errors exactly as hafen.ui():find(sel) does
            r = new Rec(sel, Sheet.siteOf(sel));
            rules.put(selector, r);
        }
        if(r.handle == null)
            r.handle = LuaRule.ofSheet(owner, this, selector);
        return r.handle;
    }

    /** {@code sheet:load(t)}: parse the whole table, then replace every rule with what it says. */
    private void load(Addon owner, LuaValue t) {
        List<Sheet.Parsed> rows = Sheet.parseSheet(owner, "sheet:load", t);
        synchronized(this) {
            for(Map.Entry<String, Rec> e : rules.entrySet())
                e.getValue().props.clear();            // ...a sheet is replaced WHOLE, never merged into
            for(int i = 0; i < rows.size(); i++) {
                Sheet.Parsed row = rows.get(i);
                Rec r = rules.get(row.key);
                if(r == null)
                    rules.put(row.key, r = new Rec(row.sel, row.site));
                r.props.set(row.props);
            }
        }
        changed();
    }

    /** The sheet behind a method's {@code self}, or a guiding error (a dot call passes the wrong self). */
    private static LuaSheet handle(LuaValue self, String method) {
        LuaSheet s = resolve(self);
        if(s == null)
            throw new LuaError("sheet:" + method + "() — use a COLON call on the sheet object"
                + " (hafen.ui():sheet())");
        return s;
    }
}
