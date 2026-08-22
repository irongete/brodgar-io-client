package io.brodgar.addon;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.OneArgFunction;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>One role of the selector language</b> (094, A-113) &mdash; {@code window}, {@code inventory},
 * {@code window.title}, {@code chat.party}. What {@code hafen.ui():role()} is the collection of.
 *
 * <p><b>The vocabulary was a page and nothing else.</b> {@code w:role()} reported a widget's role or an honest
 * {@code nil}, and the list of what a role could be lived in {@code ui/selectors.md}. So the inspector in
 * {@code addons/widgetstack} exists partly to answer at runtime what the language is &mdash; which is evidence
 * that a runtime answer is wanted, and there was none.
 *
 * <p><b>The two verbs are the one distinction the list carries.</b> {@code :name()} is always the string.
 * {@code :selector()} is that string <i>when it can be written as a selector that matches widgets</i>, and
 * {@code nil} for a <b>render site</b> &mdash; a name the font scopes and the stylesheet share, valid in a
 * rule and matching no widget by construction. So {@code s:ui():matchAll(r:selector())} is safe for every
 * role that has one, and the roles that would silently answer empty say so instead.
 *
 * <p><b>Interned per addon by name</b>, like every other addressable thing here: the set is closed, fixed at
 * compile time and never dies, so {@code hafen.ui():role():get("window") == hafen.ui():role():get("window")}
 * and a table can be keyed by a Role. The cache is a plain map for the same reason &mdash; there is nothing to
 * collect.
 */
final class LuaRole {
    /** The role's name, which is also its identity. */
    private final String name;
    /** Does it classify a real widget, or name a render site the stylesheet paints? */
    private final boolean widget;

    private LuaRole(String name, boolean widget) {
        this.name = name;
        this.widget = widget;
    }

    public String toString() {
        return "Role(" + name + ")";
    }

    // ---- the per-addon interning ------------------------------------------------------------------

    static LuaValue of(Addon owner, String name, boolean widget) {
        synchronized(owner.roles) {
            LuaValue v = owner.roles.get(name);
            if(v == null)
                owner.roles.put(name, v = LuaValue.userdataOf(new LuaRole(name, widget), meta(owner)));
            return v;
        }
    }

    /** The role with this exact name, or {@code NIL} &mdash; the collection's {@code :get(name)}. */
    static LuaValue named(Addon owner, String name) {
        for(String r : Selector.WIDGET_ROLES) {
            if(r.equals(name))
                return of(owner, r, true);
        }
        for(String r : Selector.SITE_ROLES) {
            if(r.equals(name))
                return of(owner, r, false);
        }
        return LuaValue.NIL;
    }

    /** Every role the client publishes, the widget-classifying ones first &mdash; the order the guide lists. */
    static List<LuaValue> members(Addon owner) {
        List<LuaValue> out = new ArrayList<LuaValue>();
        for(String r : Selector.WIDGET_ROLES)
            out.add(of(owner, r, true));
        for(String r : Selector.SITE_ROLES)
            out.add(of(owner, r, false));
        return out;
    }

    static LuaRole resolve(LuaValue v) {
        if((v == null) || !v.isuserdata())
            return null;
        Object o = v.touserdata();
        return (o instanceof LuaRole) ? (LuaRole)o : null;
    }

    /** The name a filter matches on &mdash; a Role has one, so a string filter is a substring test. */
    static String name(LuaValue v) {
        LuaRole r = resolve(v);
        return (r == null) ? "" : r.name;
    }

    private static LuaRole handle(LuaValue self, String method) {
        LuaRole r = resolve(self);
        if(r == null)
            throw new LuaError("role:" + method + "() — use a COLON call on a Role"
                + " (hafen.ui():role():get(name), hafen.ui():role():list()[i])");
        return r;
    }

    // ---- the metatable -----------------------------------------------------------------------------

    private static LuaValue meta(Addon owner) {
        if(owner.roleMeta != null)
            return owner.roleMeta;
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, Retired.closedIndex("role", methods(),
            "a role answers :name() :selector() and :info()"));
        mt.set("__name", LuaValue.valueOf("Role"));
        mt.set("__tostring", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaRole r = resolve(self);
                return LuaValue.valueOf((r == null) ? "Role(?)" : r.toString());
            }
        });
        owner.roleMeta = mt;
        return mt;
    }

    private static LuaTable methods() {
        LuaTable m = new LuaTable();
        // name() — the role's own string, which is what w:role() answers for a widget of this kind.
        m.set("name", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(handle(self, "name").name);
            }
        });
        // selector() — the same string WHEN it is a selector that can match a widget, and nil for a render
        // site. A site role is valid in a stylesheet rule and matches no widget by construction, so writing
        // it into s:ui():matchAll() would answer empty forever with nothing to say why.
        m.set("selector", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaRole r = handle(self, "selector");
                return r.widget ? LuaValue.valueOf(r.name) : LuaValue.NIL;
            }
        });
        // info() — the one SNAPSHOT escape hatch.
        m.set("info", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaRole r = handle(self, "info");
                LuaTable t = new LuaTable();
                t.set("name", LuaValue.valueOf(r.name));
                if(r.widget)
                    t.set("selector", LuaValue.valueOf(r.name));
                return t;
            }
        });
        return m;
    }
}
