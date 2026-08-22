package io.brodgar.addon;

import haven.Coord;
import haven.Resource;
import haven.SListWidget;
import haven.Widget;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaValue;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * The Lua-array &rarr; {@link SListWidget#items()}/{@link SListWidget#makeitem} bridge every model-backed
 * control shares (spec {@code 040-ui-controls}, task 040.9, D-108 — a mechanism ships with its first
 * consumer). {@link CList} is that consumer; the later model-backed controls (040.10's dropdown/menu, 040.12's
 * table) reuse {@link #parse}/{@link #makeitem} rather than re-deriving the row-shape rules.
 *
 * <p><b>Two row shapes ship</b> (spec 040 §5), both the client's own ready-made rows, so v1 needs no custom-row
 * surface: a plain string becomes an {@link SListWidget.TextItem}, an {@code {icon =, text =}} table an
 * {@link SListWidget.IconText}. Which shape a row takes is decided <b>per element</b>, so a mixed table is
 * legal — {@link #parse} tags each one with the {@link Row} it resolves to.
 *
 * <p><b>Every row is resolved once, at {@code :rows(t)} time</b> — shape, text and any icon image alike —
 * rather than lazily when a row scrolls into view: {@code SListBox.update()} calls {@code makeitem} from the
 * UI tick with no error isolation around it, so a bad icon has to fail at the verb that can still refuse
 * cleanly, not partway through a frame. This is the same "validated before anything is torn down" discipline
 * {@link CRadio#rows} already follows for its labels.
 *
 * <p>{@link Row#raw} is the exact {@link LuaValue} {@code t:get(i)} produced, kept for identity — {@code
 * SListWidget}'s own selection is IDENTITY-keyed ({@code IdentityHashMap}, {@code !=} comparisons in its diff),
 * so a {@link CList} keeps {@link Row} objects (not the raw values) as its {@code I} type and hands the raw
 * value back out through {@code :value()}, which is what lets a later {@code :value(row)} name one by the very
 * object an earlier {@code :rows(t)} or an {@code :onChange} handler already gave the caller.
 */
final class LuaRows {
    private LuaRows() {
    }

    /** One resolved row: what {@code :rows(t)} was given, its text, and its icon (or {@code null} for a text row). */
    static final class Row {
        final LuaValue raw;
        final String text;
        final BufferedImage icon;

        Row(LuaValue raw, String text, BufferedImage icon) {
            this.raw = raw;
            this.text = text;
            this.icon = icon;
        }
    }

    /**
     * An array of rows, validated and resolved: each element is a STRING (a text row) or an
     * {@code {icon =, text =}} TABLE (an icon row), never {@code nil} or any other shape. {@code verb} names
     * the caller for the error text ({@code "widget:rows"} today; the later model-backed controls share it).
     */
    static List<Row> parse(LuaValue t, String verb) {
        if(!t.istable())
            throw new LuaError(verb + "(t) is an ARRAY of rows, got " + t.typename());
        int n = t.length();
        List<Row> rows = new ArrayList<Row>(n);
        for(int i = 1; i <= n; i++) {
            LuaValue e = t.get(i);
            if(e.isnil())
                throw new LuaError(verb + "(t): row " + i + " is nil");
            if(e.istable()) {
                LuaValue textv = e.get("text");
                if(!textv.isstring() || textv.isnumber())
                    throw new LuaError(verb + "(t): row " + i + " is a table and must have a string \"text\""
                        + " key ({icon =, text =})");
                LuaValue iconv = e.get("icon");
                if(iconv.isnil())
                    throw new LuaError(verb + "(t): row " + i + " is a table and must have an \"icon\" key"
                        + " ({icon =, text =}) — a plain string is the door for a text-only row");
                rows.add(new Row(e, textv.tojstring(), icon(iconv, verb, i)));
            } else if(e.isstring() && !e.isnumber()) {   // in LuaJ a number IS a string -- wrong type, not a row
                rows.add(new Row(e, e.tojstring(), null));
            } else {
                throw new LuaError(verb + "(t): row " + i + " must be a STRING or an {icon =, text =} table,"
                    + " got " + e.typename());
            }
        }
        return rows;
    }

    /** One row's icon &rarr; a {@link BufferedImage}. The same two doors {@link Controls#face} resolves a face. */
    private static BufferedImage icon(LuaValue v, String verb, int row) {
        LuaImage li = LuaImage.resolve(v);
        if(li != null) {
            if(li.dead)
                throw new LuaError(verb + "(t): row " + row + "'s icon has been freed — after"
                    + " hafen.asset():remove(a), hafen.asset():get(path) loads the file again as a NEW asset");
            return li.tex.back;
        }
        if(v.isstring() && !v.isnumber()) {       // in LuaJ a number IS a string -- that one is just wrong type
            String name = v.tojstring();
            if(Controls.fileish(name))
                throw new LuaError(verb + "(t): row " + row + "'s icon \"" + name + "\" looks like a file in"
                    + " your own addon folder, and a STRING here names one of the client's own resources"
                    + " (\"gfx/invobjs/bucket\") — load your own image with hafen.asset():get(\"" + name
                    + "\") and pass the handle");
            Resource.Image ri;
            try {
                ri = Resource.loadrimg(name);
            } catch(RuntimeException ex) {
                throw new LuaError(verb + "(t): the client has no resource named \"" + name + "\" (row " + row
                    + "'s icon) — an icon is either a name from the game's own art (\"gfx/invobjs/bucket\") or"
                    + " a hafen.asset():get(\"icon.png\") handle");
            }
            if(ri == null)
                throw new LuaError(verb + "(t): the client resource \"" + name + "\" (row " + row + "'s icon)"
                    + " carries no image layer — name the image resource itself (\"gfx/invobjs/bucket\", not"
                    + " its folder)");
            return ri.scaled();
        }
        throw new LuaError(verb + "(t): row " + row + "'s icon is a hafen.asset image handle"
            + " (hafen.asset():get(\"icon.png\")) or a client resource name (\"gfx/invobjs/bucket\"), got "
            + v.typename());
    }

    /** How many rows a refusal spells out before it stops: a refusal is read, not scrolled. */
    private static final int NAMED = 12;

    /**
     * The current rows, quoted, for a refusal that has to say which values ARE pickable — the shape
     * {@link CRadio}'s own {@code :value(v)} refusal already gives. Every model-backed control with a
     * selection shares it, so one wording answers on all of them.
     */
    static String names(List<Row> rows) {
        if(rows.isEmpty())
            return "it has no rows";
        StringBuilder sb = new StringBuilder("its rows are ");
        int n = Math.min(rows.size(), NAMED);
        for(int i = 0; i < n; i++) {
            if(i > 0)
                sb.append(", ");
            sb.append('"').append(rows.get(i).text).append('"');
        }
        if(rows.size() > n)
            sb.append(", and ").append(rows.size() - n).append(" more");
        return sb.toString();
    }

    /**
     * One resolved {@link Row} &rarr; the client's own ready-made row widget — {@code TextItem} or
     * {@code IconText}, chosen by whether {@link Row#icon} is set. This is the BARE content, with no
     * click-to-select wrapper: the right shape for {@link haven.SDropBox}/{@link haven.SListMenu}, whose own
     * inner list classes ({@code SDropList}, {@code InnerList}) wrap whatever their outer {@code makeitem}
     * returns in their OWN {@code ItemWidget} — wrapping it again here would nest two click handlers over one
     * row. {@link CDropdown}/{@link CMenu} call this directly.
     */
    static Widget content(Row item, Coord sz) {
        return (item.icon != null)
            ? SListWidget.IconText.of(sz, () -> item.icon, () -> item.text)
            : SListWidget.TextItem.of(sz, () -> item.text);
    }

    /**
     * One resolved {@link Row} &rarr; {@link #content}, wrapped in an {@link SListWidget.ItemWidget} so a click
     * selects it directly — the shape {@code haven.SListBox} itself demands, since (unlike
     * {@code SDropBox}/{@code SListMenu}) it has no separate inner list class to do that wrapping for it.
     * {@link CList} is its one caller.
     */
    static Widget makeitem(SListWidget<Row, ?> list, Row item, Coord sz) {
        final Widget content = content(item, sz);
        return new SListWidget.ItemWidget<Row>(list, sz, item) {
            {
                add(content, Coord.z);
            }
        };
    }
}
