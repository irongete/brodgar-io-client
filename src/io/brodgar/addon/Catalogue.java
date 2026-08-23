package io.brodgar.addon;

import haven.Fonts;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * A <b>catalogue</b> — one addon's document of what this client <b>displays</b>, parsed once and immutable
 * from the moment it exists ({@code hafen.locale():load(doc)}). {@link Sheet}'s twin, one layer thinner: a
 * stylesheet says what a surface is drawn <i>with</i>, and this says what it draws.
 *
 * <p><b>An entry is keyed on two things</b>: the <b>surface</b> the string is drawn at, and the string the
 * client would otherwise have drawn. That is what tells a button caption from a chat line reading the same
 * word, and it is why {@code "*"} exists — one key that answers at every surface, consulted only after the
 * named one has missed.
 *
 * <p><b>And a pattern reaches what the client composed.</b> A string the site assembled as it drew it — a
 * row carrying a number, a line carrying a name — is a different string every time, so no exact key can
 * name it. A pattern names the shape instead, and its capture groups come back through {@code %1$s}-style
 * positional arguments. Patterns are an <b>ordered list</b>, resolved in the document's own order once every
 * exact key has missed: two of them can describe one string, and the order is the author's answer to which
 * of the two wins. That is why the property is an array — a JSON object has no order to say it in.
 *
 * <p><b>Immutable, and that is what keeps it off a lock.</b> {@link Fonts#display} is called from inside a
 * render; a document that could change under it would need a lock taken on the render path and held across
 * the call into this layer. {@code :load} builds a whole new one instead, and installing it is one volatile
 * write.
 *
 * <p><b>The refusals live here</b> for the reason they live in {@link Sheet#parseSheet}: a document is parsed
 * <b>entirely</b> before anything is committed, so a bad surface key or a misspelt property leaves the addon
 * holding exactly the catalogue it had.
 */
final class Catalogue {
    /** A catalogue that names nothing — what {@code load({})} builds, and what records every string that misses. */
    static final Catalogue EMPTY = new Catalogue(Collections.<String, Map<String, String>>emptyMap(),
                                                 Collections.<Pat>emptyList(),
                                                 Collections.<String>emptyList(), 0);

    /** surface &rarr; the English the client would draw &rarr; what to draw instead. */
    private final Map<String, Map<String, String>> text;
    /** The patterns, in the order the document wrote them — which is the order they are resolved in. */
    private final List<Pat> pats;
    /** The surfaces this document names, in the order it named them — what {@code :info()} reads back. */
    private final List<String> surfaces;
    /** How many exact entries it carries in total, across every surface. */
    final int entries;

    private Catalogue(Map<String, Map<String, String>> text, List<Pat> pats, List<String> surfaces,
                      int entries) {
        this.text = text;
        this.pats = pats;
        this.surfaces = surfaces;
        this.entries = entries;
    }

    /** The surfaces this catalogue names, in the order the document named them. */
    List<String> surfaces() {
        return surfaces;
    }

    /** How many patterns it carries — the other half of {@link #entries}, and a count of its own. */
    int patterns() {
        return pats.size();
    }

    /**
     * What this catalogue says the client displays for {@code s} at {@code scope}, or {@code null} when it
     * names nothing for it. The surface's own key first, then {@link Fonts#EVERY} — so an entry written for
     * every surface is the fallback rather than the winner, and naming one surface exactly is always the
     * stronger statement.
     *
     * <p><b>Every exact key first, and the patterns only after all of them have missed.</b> This is called
     * from inside a render, and an immediate-mode site renders its string every frame — so a scan that
     * compiled nothing and matched nothing is what an exact hit has to cost, and a pattern is walked only
     * for a string no key names. The patterns are then taken in the document's <b>own order</b>, {@code "*"}
     * and a named surface alike: two patterns can describe one string, and the array is where the author
     * said which of them wins.
     */
    String display(String scope, String s) {
        Map<String, String> m = text.get(scope);
        String d = (m == null) ? null : m.get(s);
        if(d != null)
            return d;
        m = text.get(Fonts.EVERY);
        d = (m == null) ? null : m.get(s);
        if(d != null)
            return d;
        for(int i = 0; i < pats.size(); i++) {
            Pat p = pats.get(i);
            if(!p.reaches(scope))
                continue;
            Matcher mt = p.re.matcher(s);
            if(mt.matches())
                return p.expand(mt);
        }
        return null;
    }

    /**
     * Parse a whole document. {@code ctx} is the call the refusals quote.
     *
     * <p>Two properties and no others. {@code text} is the exact keys — an object of surface &rarr;
     * {@code {english = display}} — and {@code pattern} is the ordered list of the ones with capture groups.
     * A third property is a typo, and a typo that did nothing is the worst answer available.
     */
    static Catalogue parse(String ctx, LuaValue doc) {
        if(!doc.istable())
            throw new LuaError(ctx + ": a catalogue is a table of { text = {...}, pattern = {...} }, got "
                + doc.typename());
        Map<String, Map<String, String>> out = new LinkedHashMap<String, Map<String, String>>();
        List<Pat> pats = new ArrayList<Pat>();
        List<String> order = new ArrayList<String>();
        int n = 0;
        LuaValue k = LuaValue.NIL;
        while(true) {
            Varargs it = doc.next(k);
            k = it.arg1();
            if(k.isnil())
                break;
            String p = (!k.isnumber() && k.isstring()) ? k.tojstring() : null;
            LuaValue v = it.arg(2);
            if("text".equals(p)) {
                n = parseText(ctx, v, out, order);
            } else if("pattern".equals(p)) {
                parsePatterns(ctx + ".pattern", v, pats, order);
            } else {
                throw new LuaError(ctx + ": \"" + k.tojstring() + "\" is not a catalogue property — a"
                    + " catalogue carries \"text\", the strings it names exactly, and \"pattern\", the ones"
                    + " it matches with capture groups");
            }
        }
        if(out.isEmpty() && pats.isEmpty())
            return EMPTY;
        return new Catalogue(out, Collections.unmodifiableList(pats), Collections.unmodifiableList(order), n);
    }

    /** The {@code text} property: surface &rarr; english &rarr; display, every key checked as it is read. */
    private static int parseText(String ctx, LuaValue v, Map<String, Map<String, String>> out,
                                 List<String> order) {
        if(!v.istable())
            throw new LuaError(ctx + ".text: expected a table keyed by surface — { button = { Cancel ="
                + " \"Cancelar\" } }, got " + v.typename());
        int n = 0;
        LuaValue k = LuaValue.NIL;
        while(true) {
            Varargs it = v.next(k);
            k = it.arg1();
            if(k.isnil())
                break;
            // BEFORE isstring(): in LuaJ a number IS a string (the hafen.asset lesson), so a list of
            // surfaces would read as a table of nameless ones.
            if(k.isnumber())
                throw new LuaError(ctx + ".text: a surface is named by a string (\"button\", \"chat\","
                    + " \"*\"), not a number — this half of a catalogue is keyed, not a list");
            if(!k.isstring())
                throw new LuaError(ctx + ".text: a surface is named by a string, got " + k.typename());
            String surface = k.tojstring();
            surface(ctx, surface);
            Map<String, String> m = out.get(surface);
            if(m == null) {
                out.put(surface, m = new LinkedHashMap<String, String>());
                order.add(surface);
            }
            n += parseEntries(ctx + ".text[\"" + surface + "\"]", it.arg(2), m);
        }
        return n;
    }

    /** One surface's entries: the string the client would draw, and the string to draw instead. */
    private static int parseEntries(String ctx, LuaValue v, Map<String, String> into) {
        if(!v.istable())
            throw new LuaError(ctx + ": expected a table of { [\"what the client draws\"] = \"what to draw"
                + " instead\" }, got " + v.typename());
        int n = 0;
        LuaValue k = LuaValue.NIL;
        while(true) {
            Varargs it = v.next(k);
            k = it.arg1();
            if(k.isnil())
                break;
            if(k.isnumber() || !k.isstring())
                throw new LuaError(ctx + ": an entry is keyed on the STRING the client would draw, got "
                    + k.typename() + " — a catalogue matches text, not a position");
            LuaValue d = it.arg(2);
            if(d.isnumber() || !d.isstring())
                throw new LuaError(ctx + "[\"" + k.tojstring() + "\"]: what to display is a string, got "
                    + d.typename());
            into.put(k.tojstring(), d.tojstring());
            n++;
        }
        return n;
    }

    /**
     * The {@code pattern} property: the patterns, <b>in order</b>, because the order is the answer to which of
     * two overlapping ones wins. An object could not have given one, which is why this half of a catalogue is
     * a list and the refusal below says so rather than quietly taking the keys.
     */
    private static void parsePatterns(String ctx, LuaValue v, List<Pat> into, List<String> order) {
        if(!v.istable())
            throw new LuaError(ctx + ": expected an ordered list of patterns — { { surface = \"tooltip\","
                + " match = \"...\", text = \"...\" } }, got " + v.typename());
        int n = 0;
        LuaValue k = LuaValue.NIL;
        while(true) {
            Varargs it = v.next(k);
            k = it.arg1();
            if(k.isnil())
                break;
            if((k.type() != LuaValue.TNUMBER) || (k.todouble() != k.toint()) || (k.toint() < 1))
                throw new LuaError(ctx + ": \"" + k.tojstring() + "\" — the patterns are an ORDERED LIST,"
                    + " not an object: two of them can describe one string, and a position is what says which"
                    + " one wins. Write { { surface = \"tooltip\", match = \"...\", text = \"...\" }, ... }");
            n++;
        }
        int len = v.length();
        if(n != len)
            throw new LuaError(ctx + ": the list runs to " + len + " and carries " + n + " patterns — a"
                + " pattern's position IS its priority, so the list is 1..n with no gap in it");
        for(int i = 1; i <= len; i++)
            into.add(parsePattern(ctx + "[" + i + "]", v.get(i), order));
    }

    /** One pattern: the surface it is written under, the shape it matches, and what to draw instead. */
    private static Pat parsePattern(String ctx, LuaValue v, List<String> order) {
        if(!v.istable())
            throw new LuaError(ctx + ": a pattern is { surface = \"tooltip\", match = \"...\","
                + " text = \"...\" }, got " + v.typename());
        String surface = null, match = null, display = null;
        LuaValue k = LuaValue.NIL;
        while(true) {
            Varargs it = v.next(k);
            k = it.arg1();
            if(k.isnil())
                break;
            String p = (k.type() == LuaValue.TSTRING) ? k.tojstring() : null;
            if("surface".equals(p))
                surface = prop(ctx, "surface", it.arg(2));
            else if("match".equals(p))
                match = prop(ctx, "match", it.arg(2));
            else if("text".equals(p))
                display = prop(ctx, "text", it.arg(2));
            else
                throw new LuaError(ctx + ": \"" + k.tojstring() + "\" is not a pattern property — a pattern"
                    + " carries \"surface\", the key it is written under, \"match\", the shape of the"
                    + " string the client would draw, and \"text\", what to draw in its place");
        }
        if(surface == null)
            throw missing(ctx, "surface", "the key it is written under, one of " + names());
        if(match == null)
            throw missing(ctx, "match", "the shape of the string the client would draw, capture groups and"
                + " all");
        if(display == null)
            throw missing(ctx, "text", "what to draw instead, with %1$s where a capture group goes");
        surface(ctx + ".surface", surface);
        if(!order.contains(surface))
            order.add(surface);
        Pattern re;
        try {
            re = Pattern.compile(match);
        } catch(PatternSyntaxException e) {
            throw new LuaError(ctx + ".match: \"" + match + "\" is not a pattern this client can read — "
                + e.getDescription() + " at index " + e.getIndex() + ". A capture group is written ( ... ) and"
                + " every one that opens has to close");
        }
        return Pat.make(ctx, surface, match, re, display);
    }

    /** A pattern's property, which is a string in all three cases, or the refusal that says so. */
    private static String prop(String ctx, String name, LuaValue v) {
        if(v.type() != LuaValue.TSTRING)
            throw new LuaError(ctx + "." + name + ": expected a string, got " + v.typename());
        return v.tojstring();
    }

    /** A pattern is three properties and needs all three: none of them has a sane default to fall back on. */
    private static LuaError missing(String ctx, String name, String what) {
        return new LuaError(ctx + ": a pattern needs \"" + name + "\" — " + what);
    }

    /**
     * One pattern, compiled: the surface it is written under, the expression, and the display string cut into
     * the literal pieces around its {@code %1$s} arguments.
     *
     * <p><b>The template is cut at load rather than at the render</b>, and its arguments are checked against
     * the expression's own group count there too — a {@code %3$s} over two groups is a mistake the author
     * can only be told about at {@code :load}, since a render has nowhere to say it and would have to draw
     * <i>something</i>.
     *
     * <p><b>An argument is the only thing that is not a literal.</b> Every other per-cent sign is drawn as
     * one, because a translation says "50% quality" far more often than it names an argument, and a
     * formatter that took the whole string would refuse that line rather than draw it.
     */
    private static final class Pat {
        /** The locale key, or {@link Fonts#EVERY} — which reaches every surface, as it does for an exact key. */
        private final String surface;
        private final Pattern re;
        /** The literal pieces of the display string; one more of these than there are arguments. */
        private final String[] lits;
        /** The capture group each gap between two literals takes. */
        private final int[] args;

        private Pat(String surface, Pattern re, String[] lits, int[] args) {
            this.surface = surface;
            this.re = re;
            this.lits = lits;
            this.args = args;
        }

        /** Cut {@code display} at its arguments, refusing one the expression has no group for. */
        static Pat make(String ctx, String surface, String match, Pattern re, String display) {
            int groups = re.matcher("").groupCount();
            List<String> lits = new ArrayList<String>();
            List<Integer> args = new ArrayList<Integer>();
            StringBuilder lit = new StringBuilder();
            int i = 0;
            while(i < display.length()) {
                char c = display.charAt(i);
                if(c != '%') {
                    lit.append(c);
                    i++;
                    continue;
                }
                int j = i + 1;
                while((j < display.length()) && (display.charAt(j) >= '0') && (display.charAt(j) <= '9'))
                    j++;
                if((j == i + 1) || (j + 1 >= display.length()) || (display.charAt(j) != '$')
                   || (display.charAt(j + 1) != 's')) {
                    lit.append(c);           // an ordinary per-cent sign, and it stays one
                    i++;
                    continue;
                }
                String num = display.substring(i + 1, j);
                long n = 0;
                for(int q = 0; (q < num.length()) && (n <= 1000000); q++)
                    n = (n * 10) + (num.charAt(q) - '0');
                if((n < 1) || (n > groups))
                    throw new LuaError(ctx + ".text: %" + num + "$s asks for capture group " + num + ", and"
                        + " \"" + match + "\" has " + groups + " — an argument names the group it takes,"
                        + " so %1$s is the first ( ... ) in the match and there is no argument past the last"
                        + " one");
                lits.add(lit.toString());
                lit.setLength(0);
                args.add(Integer.valueOf((int)n));
                i = j + 2;
            }
            lits.add(lit.toString());
            int[] a = new int[args.size()];
            for(int q = 0; q < a.length; q++)
                a[q] = args.get(q).intValue();
            return new Pat(surface, re, lits.toArray(new String[0]), a);
        }

        /** Is this pattern written at {@code scope}? {@link Fonts#EVERY} reaches every surface. */
        boolean reaches(String scope) {
            return Fonts.EVERY.equals(surface) || surface.equals(scope);
        }

        /** The display string for a match: the literals, with each argument's group between them. */
        String expand(Matcher m) {
            StringBuilder sb = new StringBuilder(lits[0]);
            for(int i = 0; i < args.length; i++) {
                String g = m.group(args[i]);
                sb.append((g == null) ? "" : g).append(lits[i + 1]);
            }
            return sb.toString();
        }
    }

    /**
     * Is {@code surface} a key a catalogue may be written under? Two refusals rather than one, because they
     * are two different mistakes: a surface that draws no text at all is the wrong <i>kind</i> of key, and
     * {@code textentry} is a text surface deliberately left out of the set.
     */
    static void surface(String ctx, String surface) {
        if(Fonts.isDisplayScope(surface))
            return;
        if("textentry".equals(surface))
            throw new LuaError(ctx + ": \"textentry\" is not a catalogue key — what the user types is theirs,"
                + " and no catalogue matches it, an entry under \"*\" included");
        throw new LuaError(ctx + ": \"" + surface + "\" is not a surface this client draws text at — the"
            + " surfaces a catalogue names are " + names());
    }

    /** The keys a catalogue may be written under, as the refusals spell them. */
    private static String names() {
        StringBuilder sb = new StringBuilder();
        for(String s : Fonts.displayScopes()) {
            if(sb.length() > 0)
                sb.append(", ");
            sb.append('"').append(s).append('"');
        }
        return sb.toString();
    }
}
