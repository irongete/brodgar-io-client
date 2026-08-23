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
                                                 Collections.<String>emptyList(), 0);

    /** surface &rarr; the English the client would draw &rarr; what to draw instead. */
    private final Map<String, Map<String, String>> text;
    /** The surfaces this document names, in the order it named them — what {@code :info()} reads back. */
    private final List<String> surfaces;
    /** How many entries it carries in total, across every surface. */
    final int entries;

    private Catalogue(Map<String, Map<String, String>> text, List<String> surfaces, int entries) {
        this.text = text;
        this.surfaces = surfaces;
        this.entries = entries;
    }

    /** The surfaces this catalogue names, in the order the document named them. */
    List<String> surfaces() {
        return surfaces;
    }

    /**
     * What this catalogue says the client displays for {@code s} at {@code scope}, or {@code null} when it
     * names nothing for it. The surface's own key first, then {@link Fonts#EVERY} — so an entry written for
     * every surface is the fallback rather than the winner, and naming one surface exactly is always the
     * stronger statement.
     */
    String display(String scope, String s) {
        Map<String, String> m = text.get(scope);
        String d = (m == null) ? null : m.get(s);
        if(d != null)
            return d;
        m = text.get(Fonts.EVERY);
        return (m == null) ? null : m.get(s);
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
                if(!v.istable())
                    throw new LuaError(ctx + ".pattern: expected a list of patterns, got " + v.typename());
            } else {
                throw new LuaError(ctx + ": \"" + k.tojstring() + "\" is not a catalogue property — a"
                    + " catalogue carries \"text\", the strings it names exactly, and \"pattern\", the ones"
                    + " it matches with capture groups");
            }
        }
        if(out.isEmpty())
            return EMPTY;
        return new Catalogue(out, Collections.unmodifiableList(order), n);
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
