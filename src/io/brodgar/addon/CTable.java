package io.brodgar.addon;

import haven.CharWnd;
import haven.Coord;
import haven.GOut;
import haven.SListWidget;
import haven.TableBox;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The adapter behind {@code hafen.ui():table()} — a real {@link TableBox}, the client's own columned row list
 * (spec {@code 040-ui-controls}, task 040.12): the fifth and last of the model-backed five. Unlike
 * {@link CList}/{@link CDropdown}/{@link CMenu} it takes no part in the {@link LuaRows} bridge — a table row is
 * not a string or an {@code {icon=, text=}} pair, it is whatever shape the addon's own {@code :columns(t)}
 * {@code of(row)} accessors read from it ({@code {name=, q=}}, in the worked panel) — so {@code :rows(t)} here
 * is a plain array of arbitrary Lua values, the same shape {@link CGrid}'s row source has.
 *
 * <p><b>{@code TableBox.spec()}/{@code itemh()} are called from {@code TableBox}'s OWN constructor</b> — before
 * this class's field initializers have run, since {@code super(sz)} is necessarily this constructor's first
 * statement — so a plain instance field written in the constructor BODY reads back its default (0 / null) the
 * one time it matters. The fix is the same one {@code haven}'s own {@code SListWidget.TextItem.of}/{@code
 * IconText.of} lean on throughout this bridge: an ANONYMOUS subclass captures the column spec and row height as
 * effectively-final locals of {@link #create}, and the compiler assigns an anonymous class's captured-variable
 * fields to it BEFORE it calls its super constructor — which is exactly early enough for {@code TableBox}'s own
 * constructor, one level up, to see them. {@link #create} is the only place this class is ever instantiated.
 *
 * <p><b>Every cell is resolved WHOLE at {@code :rows(t)}/{@code :columns(t)} time, not lazily per visible
 * row</b> — the same D-159 discipline {@link LuaRows#parse} follows and for the identical reason:
 * {@code SListBox.update()} (the {@code MainList} inside a {@code TableBox}) calls {@code makeitem} — which
 * calls each column's {@code of(row)} through {@code Row}'s constructor — from the ordinary per-frame tick,
 * with none of {@link AddonManager#callLua}'s error isolation around it. So every {@code of(row)} call happens
 * synchronously inside the {@code :rows(t)}/{@code :columns(t)} verb that triggered it (a normal Lua call, whose
 * error is a normal {@link LuaError} at that call site), and each row's resulting per-column strings are baked
 * into the {@link TRow} the widget actually holds — a cell's {@code makecell} lambda only ever reads back a
 * plain {@code String}, never calls Lua.
 *
 * <p><b>{@code :columns(t)} is building-only, exactly like {@link CGrid}'s {@code :cellSize(w, h)}</b> — a
 * {@code TableBox}'s columns ({@code cols}, {@code main}) are {@code public final}, fixed at construction from
 * {@code spec()}, so a different column set is a different widget under the same Lua handle. {@link
 * Controls#columns} carries the current rows across that rebuild, exactly as {@link Controls#cellSize} carries a
 * grid's rows and {@code :onCell} handler. {@code :rowHeight(n)} is the SAME shape, over the same rebuild.
 */
abstract class CTable extends TableBox<CTable.TRow> implements Owned.Control, Controls.Rows, Controls.RowHeight,
        Controls.Columns {
    /** A default box, in DESIGN pixels; {@code :size(w, h)} overrides it, same as every other control here. */
    static final Coord DEF_SZ = new Coord(200, 160);

    /** One resolved column: its heading, pixel width, and the Lua accessor {@code of(row)} names. */
    static final class ColDef {
        final String title;
        final int width;
        final LuaValue of;

        ColDef(String title, int width, LuaValue of) {
            this.title = title;
            this.width = width;
            this.of = of;
        }
    }

    /** One resolved row: the raw value {@code :rows(t)} was given, and its cell text, one per current column. */
    static final class TRow {
        final LuaValue raw;
        final String[] cells;

        TRow(LuaValue raw, String[] cells) {
            this.raw = raw;
            this.cells = cells;
        }
    }

    private final Owned.State own;
    private final List<ColDef> cols;
    private List<TRow> curItems = Collections.<TRow>emptyList();
    /** Exactly the table {@code :rows(t)} was last given — what the bare read hands back. */
    private LuaValue lastRows;
    /** Exactly the table {@code :columns(t)} was last given — what the bare read hands back. */
    private final LuaValue lastColumns;

    private CTable(Addon owner, Coord sz, List<ColDef> cols, LuaValue lastColumns) {
        super(sz);
        this.cols = cols;
        this.lastColumns = lastColumns;
        this.own = new Owned.State(owner, this);
    }

    /** The client's own label height — what a bare {@code hafen.ui():table()} rows at until {@code :rowHeight(n)}. */
    static int defaultItemHeight() {
        return CharWnd.attrfont().height();
    }

    /**
     * The one place a {@link CTable} is built — see the class doc for why {@code spec}/{@code itemh} have to
     * arrive as captured locals of an anonymous subclass rather than fields of this class.
     */
    static CTable create(Addon owner, Coord sz, final int itemh, List<ColDef> cols, LuaValue lastColumns) {
        final List<TableBox.ColSpec<? super TRow>> specs = buildSpecs(cols);
        return new CTable(owner, sz, cols, lastColumns) {
            protected List<TableBox.ColSpec<? super TRow>> spec() {
                return specs;
            }

            protected int itemh() {
                return itemh;
            }
        };
    }

    public Owned.State own() {
        return own;
    }

    protected List<? extends TRow> items() {
        return curItems;
    }

    protected int headh() {
        return defaultItemHeight();
    }

    /** This control's current columns — what a {@code :rowHeight(n)} rebuild carries forward. */
    List<ColDef> cols() {
        return cols;
    }

    /** {@code t:rows()} — exactly the table last given, or {@code null} before the first one. */
    public LuaValue rows() {
        return lastRows;
    }

    /**
     * {@code t:rows(t)} — a plain array of arbitrary Lua values (040.12), resolved against the CURRENT columns
     * before anything is torn down: a bad table, or an {@code of(row)} that errors or answers the wrong type,
     * leaves the existing rows exactly as they were, mirroring every other model-backed control's {@code
     * :rows(t)}.
     */
    public void rows(LuaValue t) {
        List<TRow> parsed = resolveRows(t, cols);
        synchronized(LuaWidget.monitor(this)) { curItems = parsed; }
        lastRows = t;
    }

    /** {@code t:rowHeight()} — the row height in pixels, as it stands right now. */
    public int rowHeight() {
        return itemh();
    }

    /** {@code t:columns()} — exactly the table last given, or {@code null} before the first one. */
    public LuaValue columns() {
        return lastColumns;
    }

    public void draw(GOut g) {
        if(own.pending())   // built this statement and not armed yet: a half-configured control paints NOTHING
            return;
        super.draw(Owned.dim(this, g));   // 139.3: disabled? the whole control paints dimmed
    }

    /** An array of column descriptors ({@code {title=, width=, of=}}), each key required and type-checked. */
    static List<ColDef> parseColumns(LuaValue t) {
        if(!t.istable())
            throw new LuaError("widget:columns(t) is an ARRAY of column descriptors ({title=, width=, of=}),"
                + " got " + t.typename());
        int n = t.length();
        List<ColDef> cols = new ArrayList<ColDef>(n);
        for(int i = 1; i <= n; i++) {
            LuaValue e = t.get(i);
            if(!e.istable())
                throw new LuaError("widget:columns(t): column " + i + " must be a table ({title=, width=, of=}),"
                    + " got " + e.typename());
            LuaValue titlev = e.get("title");
            // A genuine string ONLY -- isstring()/isnumber() are ARITHMETIC-coercion predicates in LuaJ, true
            // for BOTH directions (a number reads as a string, AND a numeric-content string reads as a number),
            // so "10" would fail !isstring()||isnumber() even though it is a perfectly good string. type() is
            // the intrinsic representation and is not fooled by content.
            if(titlev.type() != LuaValue.TSTRING)
                throw new LuaError("widget:columns(t): column " + i + " is missing a string \"title\" key"
                    + " ({title=, width=, of=})");
            LuaValue widthv = e.get("width");
            if(widthv.isnil())
                throw new LuaError("widget:columns(t): column " + i + " is missing a number \"width\" key"
                    + " ({title=, width=, of=})");
            int width = Args.integer(widthv, "widget:columns", "column " + i + "'s \"width\"",
                                     "the column's box in design pixels");
            if(width <= 0)
                throw new LuaError("widget:columns(t): column " + i + "'s \"width\" must be a POSITIVE number"
                    + " of pixels, got " + width);
            LuaValue ofv = e.get("of");
            if(!ofv.isfunction())
                throw new LuaError("widget:columns(t): column " + i + " is missing a function \"of\" key"
                    + " ({title=, width=, of=})");
            cols.add(new ColDef(titlev.tojstring(), width, ofv));
        }
        return cols;
    }

    /**
     * An array of table rows, resolved against {@code cols} — {@code of(row)} is called ONCE per row per
     * column, right here, synchronously inside the {@code :rows(t)}/{@code :columns(t)} call (see the class
     * doc for why that is the safe place). Every {@code of(row)} must answer a STRING; a row is any non-nil
     * Lua value (its shape is entirely the addon's {@code of} accessors to read).
     */
    private static List<TRow> resolveRows(LuaValue t, List<ColDef> cols) {
        if(!t.istable())
            throw new LuaError("widget:rows(t) is an ARRAY of table rows, got " + t.typename());
        int n = t.length();
        List<TRow> rows = new ArrayList<TRow>(n);
        for(int i = 1; i <= n; i++) {
            LuaValue e = t.get(i);
            if(e.isnil())
                throw new LuaError("widget:rows(t): row " + i + " is nil");
            String[] cells = new String[cols.size()];
            for(int c = 0; c < cols.size(); c++) {
                ColDef col = cols.get(c);
                LuaValue v = col.of.call(e);
                // A genuine string ONLY -- see parseColumns's "title" check for why type() and not
                // isstring()/isnumber() (a numeric-content string like tostring(10) answers isnumber()==true too).
                if(v.type() != LuaValue.TSTRING)
                    throw new LuaError("widget:rows(t): row " + i + ", column \"" + col.title + "\"'s of(row)"
                        + " must return a string, got " + v.typename());
                cells[c] = v.tojstring();
            }
            rows.add(new TRow(e, cells));
        }
        return rows;
    }

    /** One {@link ColDef} per column &rarr; the {@code TableBox.ColSpec} {@code spec()} hands back, once. */
    private static List<TableBox.ColSpec<? super TRow>> buildSpecs(List<ColDef> cols) {
        List<TableBox.ColSpec<? super TRow>> specs = new ArrayList<TableBox.ColSpec<? super TRow>>(cols.size());
        for(int i = 0; i < cols.size(); i++) {
            final ColDef col = cols.get(i);
            final int ci = i;
            TableBox.ColSpec<TRow> spec = TableBox.ColSpec.<TRow>of(col.width, 0.0, 0.0, 0.0,
                (c, sz) -> SListWidget.TextItem.of(sz, () -> col.title),
                (item, idx, sz) -> SListWidget.TextItem.of(sz, () -> item.cells[ci]));
            specs.add(spec);
        }
        return specs;
    }
}
