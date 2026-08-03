package io.brodgar.addon;

import haven.Widget;
import haven.Window;

import org.luaj.vm2.LuaError;

/**
 * A <b>UI selector</b> — the one way to point at a part of the client's UI (spec {@code 030-ui-selectors},
 * feature B2). A selector is a <b>string</b>, parsed here ONCE into this small value object and then applied as a
 * predicate over one widget ({@link #matches}), so the same object serves {@code hafen.ui(sel)}, {@code
 * hafen.ui.all(sel)}, the events (030.2) and, later, C's stylesheet. Parsing never happens per node.
 *
 * <p><b>The grammar is deliberately tiny and CSS-shaped</b>, because that is the mental model everyone already
 * has. There are <b>no descendant selectors</b> in v1:
 *
 * <pre>
 *   selector := ( '*' | role )? refiner*
 *   refiner  := '&#64;' ClassName | '[' ('title'|'res') '=' value ']'
 * </pre>
 *
 * <p>e.g. {@code "*"} · {@code "window"} · {@code "&#64;Equipory"} · {@code "window[title=Cupboard]"} ·
 * {@code "inventory&#64;Inventory[title=Cupboard]"}. The refiners may appear in any order and each at most once;
 * a selector with no part at all is an error, as is an unknown role or refiner key.
 *
 * <p><b>Roles are the {@link haven.Fonts#SCOPES} vocabulary, promoted — but not 1:1.</b> Of the 11 font scopes,
 * {@code "default"} is a cascade fallback and does not carry over (its selector twin is {@code *}); the other 10
 * do, and <b>two roles are new</b>: {@code window} (the frame itself — fonts only ever needed its title) and
 * {@code inventory}. Reusing those names is what keeps this from becoming a second vocabulary.
 *
 * <p><b>Five of those names classify no widget, and say so.</b> A font scope names a <i>render site</i>, and some
 * sites are not widgets at all: a window's caption is drawn by {@code Window.Deco}, a tooltip is painted rather
 * than placed, and {@code world.nick}/{@code world.speech} live over the 3D view. Those roles stay <b>valid
 * grammar</b> (one vocabulary, shared with fonts, and coverage may grow) but match nothing today — an honest
 * nothing, which is the rule the classifier itself follows ({@link LuaWidget#role}): never a wrong answer in
 * place of no answer.
 *
 * <p><b>Two matching rules that are easy to get wrong</b>, both settled here rather than at each call site:
 * <ul>
 *   <li><b>{@code [title=]} matches the nearest enclosing {@link Window}'s caption</b> ({@link #windowTitle}), not
 *       the widget's own. A bare widget the engine wraps in a titled window — an {@code Inventory} inside a
 *       {@code Hidewnd "Inventory"} — has no {@code cap} of its own, so matching the widget's own caption would
 *       make {@code inventory[title=Cupboard]}, the single most obvious selector a user will write, silently
 *       never match.</li>
 *   <li><b>{@code &#64;Class} goes through {@link LuaWidget#typeName}</b>, never {@code getSimpleName()}: Hafen
 *       builds a great many widgets as anonymous subclasses, whose simple name is the empty string.</li>
 * </ul>
 *
 * <p><b>title is exact, res is a substring</b> — each follows the convention already established for its kind of
 * key: {@code replace{caption=…}} matches a caption exactly, while {@code hafen.meter(needle)} and the gob-overlay
 * string filter match a resource name by {@code contains}. A res name is a path
 * ({@code "gfx/hud/wnd/…"}), so a substring is what an addon author can actually type; a caption is the whole
 * human label.
 *
 * <p>Immutable, package-private, and carries no Lua: it is pure matching, so the events and the inspector reuse it
 * unchanged.
 */
final class Selector {
    /** The roles that classify a real widget today ({@link LuaWidget#role} answers one of these, or {@code null}). */
    static final String[] WIDGET_ROLES = {
        "window", "inventory", "button", "label", "textentry", "chat", "menu",
    };
    /**
     * The remaining promoted {@link haven.Fonts#SCOPES} names: valid roles that name a <b>render site</b> rather
     * than a widget, so nothing is ever classified as one. Kept valid so the vocabulary stays single.
     */
    static final String[] SITE_ROLES = {
        "window.title", "window.frame", "heading", "tooltip", "world.nick", "world.speech",
    };

    /** The selector as written (trimmed) — what the errors and {@code tostring} quote. */
    final String src;
    /** The required role, or {@code null} for {@code *} / a refiner-only selector. */
    final String role;
    /** The required {@link LuaWidget#typeName}, or {@code null}. */
    final String cls;
    /** The required enclosing-window caption (exact), or {@code null}. */
    final String title;
    /** A substring the widget's resource name must contain, or {@code null}. */
    final String res;

    private Selector(String src, String role, String cls, String title, String res) {
        this.src = src;
        this.role = role;
        this.cls = cls;
        this.title = title;
        this.res = res;
    }

    public String toString() {
        return src;
    }

    // ---- parsing -----------------------------------------------------------------------------------

    /**
     * Parse a selector string, or raise a {@link LuaError} naming the offending part (and, for a bad role, listing
     * every valid one). Called once per lookup or subscription — never per node.
     */
    static Selector parse(String raw) {
        if(raw == null)
            throw new LuaError("a selector must be a string (e.g. \"window[title=Cupboard]\")");
        String s = raw.trim();
        if(s.isEmpty())
            throw bad(raw, "a selector must name something: \"*\", a role, @Class or [title=...]/[res=...]");
        int i = 0;
        String role = null;
        char c0 = s.charAt(0);
        if(c0 == '*') {
            i = 1;                                    // '*' = any role, including an unclassified widget
        } else if((c0 != '@') && (c0 != '[')) {
            int j = i;
            while((j < s.length()) && (s.charAt(j) != '@') && (s.charAt(j) != '['))
                j++;
            role = s.substring(i, j).trim();
            if(!isRole(role))
                throw bad(s, "\"" + role + "\" is not a role. " + roleList());
            i = j;
        }
        String cls = null, title = null, res = null;
        while(i < s.length()) {
            char c = s.charAt(i);
            if(c == '@') {
                int j = i + 1;
                while((j < s.length()) && (s.charAt(j) != '@') && (s.charAt(j) != '['))
                    j++;
                String v = s.substring(i + 1, j).trim();
                if(v.isEmpty())
                    throw bad(s, "\"@\" must be followed by a widget class name (e.g. \"@Equipory\")");
                if(cls != null)
                    throw bad(s, "\"@" + v + "\": a selector may name only one class");
                cls = v;
                i = j;
            } else if(c == '[') {
                int j = s.indexOf(']', i);
                if(j < 0)
                    throw bad(s, "unclosed \"[\" — a refiner is written [title=...] or [res=...]");
                String body = s.substring(i + 1, j);
                int eq = body.indexOf('=');
                if(eq < 0)
                    throw bad(s, "\"[" + body + "]\" is not key=value — the refiners are [title=...] and [res=...]");
                String k = body.substring(0, eq).trim();
                String v = body.substring(eq + 1).trim();
                if(v.isEmpty())
                    throw bad(s, "\"[" + body + "]\" has an empty value");
                if("title".equals(k)) {
                    if(title != null)
                        throw bad(s, "[title=...] is given more than once");
                    title = v;
                } else if("res".equals(k)) {
                    if(res != null)
                        throw bad(s, "[res=...] is given more than once");
                    res = v;
                } else {
                    throw bad(s, "\"" + k + "\" is not a refiner key — the refiners are [title=...] (the enclosing"
                        + " window's caption, exact) and [res=...] (a substring of the resource name)");
                }
                i = j + 1;
            } else {
                throw bad(s, "unexpected \"" + c + "\" at position " + (i + 1)
                    + " — after the role come only @Class and [title=...]/[res=...]");
            }
        }
        return new Selector(s, role, cls, title, res);
    }

    /** Is {@code s} one of the valid role names (classified or render-site)? */
    static boolean isRole(String s) {
        for(String r : WIDGET_ROLES) {
            if(r.equals(s))
                return true;
        }
        for(String r : SITE_ROLES) {
            if(r.equals(s))
                return true;
        }
        return false;
    }

    /** The valid roles, grouped — an unknown role is the one error worth spelling out in full. */
    private static String roleList() {
        StringBuilder sb = new StringBuilder("Roles that classify a widget: ");
        for(int i = 0; i < WIDGET_ROLES.length; i++)
            sb.append((i == 0) ? "" : ", ").append(WIDGET_ROLES[i]);
        sb.append(". Roles shared with the font scopes that name a render site and so match no widget: ");
        for(int i = 0; i < SITE_ROLES.length; i++)
            sb.append((i == 0) ? "" : ", ").append(SITE_ROLES[i]);
        sb.append(". Use \"*\" for any widget.");
        return sb.toString();
    }

    /** A parse error quoting the whole selector plus the offending part. */
    private static LuaError bad(String src, String why) {
        return new LuaError("bad selector \"" + src + "\": " + why);
    }

    // ---- matching ----------------------------------------------------------------------------------

    /**
     * Does this selector match {@code w}? Cheapest test first: the role is an {@code instanceof} chain, the class a
     * string compare, {@code [title=]} an O(depth) walk to the enclosing window, and {@code [res=]} last because it
     * may have to resolve a resource. Must be called under the {@code ui} monitor (it reads the tree).
     */
    boolean matches(Widget w) {
        return matchesStructure(w) && matchesRefiners(w);
    }

    /**
     * The <b>structural</b> half — role and class, both derived from the widget's Java type and therefore fixed for
     * its whole life. 030.2's event seam splits the match here: a widget that fails this can never start matching,
     * while one that passes it but fails a refiner ({@link #late}) may simply not have its caption yet, and is worth
     * re-checking for a bounded number of ticks.
     */
    boolean matchesStructure(Widget w) {
        if(w == null)
            return false;
        if(role != null) {
            String r = LuaWidget.role(w);
            if((r == null) || !role.equals(r))
                return false;
        }
        if((cls != null) && !cls.equals(LuaWidget.typeName(w)))
            return false;
        return true;
    }

    /** The refiner half — {@code [title=]} (exact, against the enclosing window) and {@code [res=]} (substring). */
    private boolean matchesRefiners(Widget w) {
        if(title != null) {
            String cap = windowTitle(w);
            if((cap == null) || !title.equals(cap))
                return false;
        }
        if(res != null) {
            String r = LuaWidget.resName(w);
            if((r == null) || !r.contains(res))
                return false;
        }
        return true;
    }

    /**
     * Does this selector carry a refiner that can land <b>after</b> the widget is placed? A caption arrives by
     * {@code uimsg} and a resource resolves asynchronously, so both {@code [title=]} and {@code [res=]} can be
     * absent at placement time and present a tick later — which is what 030.2's bounded re-check exists for.
     */
    boolean late() {
        return (title != null) || (res != null);
    }

    /**
     * The caption {@code [title=]} resolves against: the {@code cap} of the nearest enclosing {@link Window},
     * counting {@code w} itself. {@code null} when nothing above it is a window, or the window has no caption.
     * See the class comment for why this is not the widget's own text.
     */
    static String windowTitle(Widget w) {
        for(Widget p = w; p != null; p = p.parent) {
            if(p instanceof Window)
                return ((Window)p).cap;
        }
        return null;
    }
}
