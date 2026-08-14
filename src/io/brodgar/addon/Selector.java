package io.brodgar.addon;

import haven.Widget;
import haven.Window;

import org.luaj.vm2.LuaError;

import java.util.ArrayList;
import java.util.List;

/**
 * A <b>UI selector</b> — the one way to point at a part of the client's UI (spec {@code 030-ui-selectors}, feature
 * B2; regrammared by {@code 049-css-selectors}). A selector is a <b>string</b>, parsed here ONCE into this small
 * immutable value object and then applied as a predicate over one widget ({@link #matches}), so the same object
 * serves {@code hafen.ui():find/:all}, {@code widget:find/:all}, the selector events and the stylesheet. Parsing
 * never happens per node.
 *
 * <p><b>The grammar is CSS</b>, not merely CSS-shaped — because that is the mental model everyone already has, and
 * a grammar that looks like CSS while meaning something else is worse than one that looks like neither:
 *
 * <pre>
 *   selector := step ( WS+ step )*
 *   step     := ( '*' | role )? refiner*
 *   refiner  := '&#64;' ClassName | '[' ('title'|'text'|'res') op value ']'
 *   op       := '=' (exact) | '*=' (contains) | '^=' (starts with) | '$=' (ends with)
 * </pre>
 *
 * <p>e.g. {@code "*"} · {@code "window"} · {@code "&#64;Equipory"} · {@code "window[title=Cupboard]"} ·
 * {@code "window[title=Cupboard] inventory"} · {@code "window[title=Foo] button[text=Close]"} ·
 * {@code "*[res*=gfx/hud]"}. Within a step the refiners may appear in any order and each key at most once; a step
 * with no part at all is an error, as is an unknown role, refiner key or operator.
 *
 * <p><b>The space is the descendant combinator, and it is what let every attribute become honest.</b> Until 049
 * {@code [title=]} tested the nearest <i>enclosing</i> window's caption — an attribute that walked UP the tree,
 * the one part of the grammar that lied to a CSS reader. It did so because without a combinator the obvious
 * {@code inventory[title=Cupboard]} had nowhere else to be said. With one, the ancestor test is the
 * <b>combinator's</b> job ({@code window[title=Cupboard] inventory}) and an attribute tests the widget its step is
 * written on, exactly as CSS says. {@code windowTitle} is gone.
 *
 * <p><b>Matching is greedy right-to-left, and for descendant-only chains that needs no backtracking.</b> The LAST
 * step is tested against the widget; then, for each step leftward, we walk up to the <i>nearest</i> ancestor
 * satisfying it and continue strictly above that one. Greedy can never paint itself into a corner: if the nearest
 * satisfying ancestor has no valid chain above it, neither does a farther one, whose own ancestors are a subset of
 * its. (A child combinator {@code >} would break exactly this property, which is one more reason it is not here.)
 *
 * <p><b>title and text are disjoint BY CONSTRUCTION</b>, at parse time and again at the read:
 * <ul>
 *   <li>{@code [title=]} is accepted only in a step whose role is {@code window} — it is a window's own caption.
 *       {@code inventory[title=X]} would still <i>parse</i> under CSS semantics and simply never match, which is
 *       the silent failure the old ancestor rule existed to prevent; so it is <b>refused</b>, and the error hands
 *       back {@code window[title=X] inventory}.</li>
 *   <li>{@code [text=]} is refused <i>on</i> the {@code window} role, and its reader skips a {@link Window} even
 *       where the step did not name one ({@code *[text=Cupboard]}), because {@link LuaWidget#text} answers
 *       {@code Window.cap} and would otherwise give the two keys one overlapping meaning.</li>
 * </ul>
 *
 * <p><b>Two rules kept from 030 unchanged.</b> {@code @Class} goes through {@link LuaWidget#typeName}, never
 * {@code getSimpleName()}: Hafen builds a great many widgets as anonymous subclasses, whose simple name is the
 * empty string. And a role that classifies no widget stays valid grammar and matches nothing — never a wrong
 * answer in place of no answer.
 *
 * <p>Immutable, package-private, and carries no Lua: it is pure matching, so the events, the stylesheet and the
 * inspector reuse it unchanged.
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
        "window.title", "window.frame", "panel", "heading", "tooltip", "world.nick", "world.speech",
        "inventory.slot", "checkbox", "checkbox.mark", "scrollbar", "scrollbar.knob", "slider",
        "slider.knob", "hud.belt", "hud.menu.left", "hud.menu.right", "hud.search", "minimap.frame",
    };

    /**
     * One attribute test — the CSS operator and the value it compares against. {@code null} is never a match: an
     * absent caption/text/resource fails every operator rather than matching an empty needle.
     */
    static final class Attr {
        static final int EQ = 0, CONTAINS = 1, PREFIX = 2, SUFFIX = 3;

        final int op;
        final String val;

        Attr(int op, String val) {
            this.op = op;
            this.val = val;
        }

        boolean test(String s) {
            if(s == null)
                return false;
            if(op == CONTAINS)
                return s.contains(val);
            if(op == PREFIX)
                return s.startsWith(val);
            if(op == SUFFIX)
                return s.endsWith(val);
            return s.equals(val);
        }

        static String opText(int op) {
            if(op == CONTAINS)
                return "*=";
            if(op == PREFIX)
                return "^=";
            if(op == SUFFIX)
                return "$=";
            return "=";
        }
    }

    /**
     * One step of the chain — everything a single CSS compound selector can say about ONE widget. The last step of
     * a selector names the widget you are asking for; every step before it names an ancestor.
     */
    static final class Step {
        final String role;        // the required role, or null for `*` / a refiner-only step
        final String cls;         // the required LuaWidget.typeName, or null
        final Attr title;         // the required own caption (window steps only), or null
        final Attr text;          // the required displayed text (never a Window), or null
        final Attr res;           // the required resource name, or null

        Step(String role, String cls, Attr title, Attr text, Attr res) {
            this.role = role;
            this.cls = cls;
            this.title = title;
            this.text = text;
            this.res = res;
        }

        /**
         * The <b>structural</b> half — role and class, both derived from the widget's Java type and therefore fixed
         * for its whole life. The event seam splits the match here: a widget failing this can never start matching.
         */
        boolean matchesStructure(Widget w) {
            if(w == null)
                return false;
            if(role != null) {
                String r = LuaWidget.role(w);
                if((r == null) || !role.equals(r))
                    return false;
            }
            return (cls == null) || cls.equals(LuaWidget.typeName(w));
        }

        /** The refiner half — each attribute against the widget's OWN value (049: never an ancestor's). */
        boolean matchesRefiners(Widget w) {
            if((title != null) && !title.test((w instanceof Window) ? ((Window)w).cap : null))
                return false;
            if((text != null) && !text.test((w instanceof Window) ? null : LuaWidget.text(w)))
                return false;
            return (res == null) || res.test(LuaWidget.resName(w));
        }

        boolean matches(Widget w) {
            return matchesStructure(w) && matchesRefiners(w);
        }

        /** Does this step carry a refiner that can land AFTER the widget is placed? */
        boolean late() {
            return (title != null) || (text != null) || (res != null);
        }

        /** This step's share of the specificity: role 1 · {@code @Class} 2 · title/text 4 · res 8. */
        int specificity() {
            return ((role != null) ? 1 : 0) + ((cls != null) ? 2 : 0)
                + (((title != null) || (text != null)) ? 4 : 0) + ((res != null) ? 8 : 0);
        }

        boolean bare() {
            return (cls == null) && (title == null) && (text == null) && (res == null);
        }
    }

    /** The selector as written (trimmed) — what the errors and {@code tostring} quote. */
    final String src;
    /** The chain, outermost first; {@code steps[steps.length - 1]} is the widget being named. Never empty. */
    private final Step[] steps;

    private Selector(String src, Step[] steps) {
        this.src = src;
        this.steps = steps;
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
            throw new LuaError("a selector must be a string (e.g. \"window[title=Cupboard] button\")");
        String s = raw.trim();
        if(s.isEmpty())
            throw bad(raw, "a selector must name something: \"*\", a role, @Class or [title=...]/[text=...]/[res=...]");
        List<Step> out = new ArrayList<Step>();
        for(String part : split(s))
            out.add(step(s, part));
        return new Selector(s, out.toArray(new Step[out.size()]));
    }

    /**
     * Split on the descendant combinator — whitespace <b>outside</b> brackets. Inside them it is part of the value:
     * {@code window[title=Character Sheet]} is a live key in the shipped {@code theme} addon, so a naive split on
     * space would break an installed sheet on the day this shipped.
     */
    private static List<String> split(String s) {
        List<String> parts = new ArrayList<String>();
        int start = 0;
        boolean inbr = false;
        for(int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if(c == '[')
                inbr = true;
            else if(c == ']')
                inbr = false;
            else if(!inbr && Character.isWhitespace(c)) {
                if(i > start)
                    parts.add(s.substring(start, i));
                start = i + 1;
            }
        }
        if(start < s.length())
            parts.add(s.substring(start));
        return parts;
    }

    /** Parse ONE step. {@code src} is the whole selector, quoted by every error this raises. */
    private static Step step(String src, String s) {
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
                throw bad(src, "\"" + role + "\" is not a role. " + roleList());
            i = j;
        }
        String cls = null;
        Attr title = null, text = null, res = null;
        while(i < s.length()) {
            char c = s.charAt(i);
            if(c == '@') {
                int j = i + 1;
                while((j < s.length()) && (s.charAt(j) != '@') && (s.charAt(j) != '['))
                    j++;
                String v = s.substring(i + 1, j).trim();
                if(v.isEmpty())
                    throw bad(src, "\"@\" must be followed by a widget class name (e.g. \"@Equipory\")");
                if(cls != null)
                    throw bad(src, "\"@" + v + "\": a step may name only one class");
                cls = v;
                i = j;
            } else if(c == '[') {
                int j = s.indexOf(']', i);
                if(j < 0)
                    throw bad(src, "unclosed \"[\" — a refiner is written [title=...], [text=...] or [res=...]");
                String body = s.substring(i + 1, j);
                int eq = body.indexOf('=');
                if(eq < 0)
                    throw bad(src, "\"[" + body + "]\" is not key=value — the refiners are [title=...], [text=...]"
                        + " and [res=...]");
                int op = Attr.EQ, kend = eq;
                if(eq > 0) {
                    char o = body.charAt(eq - 1);
                    if(o == '*') { op = Attr.CONTAINS; kend = eq - 1; }
                    else if(o == '^') { op = Attr.PREFIX; kend = eq - 1; }
                    else if(o == '$') { op = Attr.SUFFIX; kend = eq - 1; }
                }
                String k = body.substring(0, kend).trim();
                String v = body.substring(eq + 1).trim();
                if(v.isEmpty())
                    throw bad(src, "\"[" + body + "]\" has an empty value");
                if(k.isEmpty())
                    throw bad(src, "\"[" + body + "]\" has no key — the refiners are [title=...], [text=...]"
                        + " and [res=...]");
                if(!k.equals("title") && !k.equals("text") && !k.equals("res"))
                    throw bad(src, "\"" + k + "\" is not a refiner key — the refiners are [title=...] (a WINDOW's"
                        + " own caption), [text=...] (the words a widget displays) and [res=...] (its resource"
                        + " name). Each takes = (exact), *= (contains), ^= (starts with) or $= (ends with)");
                Attr a = new Attr(op, v);
                if(k.equals("title")) {
                    if(title != null)
                        throw bad(src, "[title" + Attr.opText(op) + "...] is given more than once");
                    if(!"window".equals(role))
                        throw bad(src, "[title" + Attr.opText(op) + v + "] is a WINDOW's own caption, so it belongs"
                            + " in a step whose role is window — write \"" + rewrite(s, i, j) + "\" (the space"
                            + " is the descendant combinator, which is what tests an ancestor now)");
                    title = a;
                } else if(k.equals("text")) {
                    if(text != null)
                        throw bad(src, "[text" + Attr.opText(op) + "...] is given more than once");
                    if("window".equals(role))
                        throw bad(src, "[text" + Attr.opText(op) + v + "] is the words a widget displays; a window's"
                            + " own caption is [title" + Attr.opText(op) + v + "]");
                    text = a;
                } else {
                    if(res != null)
                        throw bad(src, "[res" + Attr.opText(op) + "...] is given more than once");
                    res = a;
                }
                i = j + 1;
            } else {
                throw bad(src, "unexpected \"" + c + "\" in step \"" + s + "\" — after the role come only @Class"
                    + " and [title=...]/[text=...]/[res=...], and a space starts a new step");
            }
        }
        if((role == null) && (cls == null) && (title == null) && (text == null) && (res == null) && (c0 != '*'))
            throw bad(src, "\"" + s + "\" names nothing");
        return new Step(role, cls, title, text, res);
    }

    /**
     * The rewrite a refused {@code [title=]} hands back: {@code inventory[title=Cupboard]} &rarr;
     * {@code window[title=Cupboard] inventory}. The bracket moves onto a {@code window} step and whatever else the
     * step said stays behind it, which is the same widget the old ancestor rule used to name.
     */
    private static String rewrite(String s, int brOpen, int brClose) {
        String bracket = s.substring(brOpen, brClose + 1);
        String rest = (s.substring(0, brOpen) + s.substring(brClose + 1)).trim();
        if(rest.isEmpty() || rest.equals("*"))
            return "window" + bracket;
        return "window" + bracket + " " + rest;
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
     * Does this selector match {@code w}? The last step is tested against the widget itself, then each step
     * leftward against the nearest ancestor satisfying it (see the class comment for why greedy is enough). Must be
     * called under the {@code ui} monitor — it reads the tree, and now walks it upward.
     */
    boolean matches(Widget w) {
        return walk(w, true);
    }

    /**
     * The <b>structural</b> half of the whole chain — every step's role/class, which are fixed for a widget's life.
     * A widget that fails this can never start matching (short of being re-parented, which re-enters the placement
     * seam anyway), while one that passes it and {@link #late}s may simply not have its caption yet.
     */
    boolean matchesStructure(Widget w) {
        return walk(w, false);
    }

    /** The shared greedy right-to-left walk; {@code full} picks the whole step predicate over its structural half. */
    private boolean walk(Widget w, boolean full) {
        if(w == null)
            return false;
        int i = steps.length - 1;
        if(full ? !steps[i].matches(w) : !steps[i].matchesStructure(w))
            return false;
        Widget p = w.parent;
        while(--i >= 0) {
            while((p != null) && (full ? !steps[i].matches(p) : !steps[i].matchesStructure(p)))
                p = p.parent;
            if(p == null)
                return false;
            p = p.parent;                             // the next step leftward must match strictly above this one
        }
        return true;
    }

    /**
     * Does this selector carry a refiner that can land <b>after</b> the widget is placed, on any step? A caption
     * arrives by {@code uimsg} and a resource resolves asynchronously, so an attribute can be absent at placement
     * and present a tick later — which is what the bounded re-check exists for. With a chain the common case is a
     * caption landing late on an ANCESTOR step, not on the widget itself.
     */
    boolean late() {
        for(int i = 0; i < steps.length; i++) {
            if(steps[i].late())
                return true;
        }
        return false;
    }

    /**
     * CSS specificity — the sum over steps, so a chain outranks the bare step it ends with. Read by the stylesheet
     * to decide which of two matching rules wins a property.
     */
    int specificity() {
        int n = 0;
        for(int i = 0; i < steps.length; i++)
            n += steps[i].specificity();
        return n;
    }

    /**
     * Is this a single step naming only a role (or {@code *})? That is the shape a {@link haven.Fonts} scope key
     * can have — a render site is one bare word, so a chain, a class or any refiner makes the key a TREE one.
     */
    boolean bare() {
        return (steps.length == 1) && steps[0].bare();
    }

    /** The role of a {@link #bare} selector — {@code null} for {@code *}, whose scope twin is {@code "default"}. */
    String role() {
        return steps[0].role;
    }
}
