package io.brodgar.addon;

import haven.CharWnd;
import haven.Coord;
import haven.GOut;
import haven.SListBox;
import haven.UI;
import haven.Widget;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaValue;

import java.util.Collections;
import java.util.List;

/**
 * The adapter behind {@code hafen.ui():list()} — a real {@link SListBox}, the client's own scrolling row list
 * (spec {@code 040-ui-controls}, task 040.9): the first of the model-backed five, and the one {@link LuaRows}
 * ships alongside (D-108). {@code :rows(t)} is the row source, {@code :value()}/{@code :value(v)} the
 * selection, {@code :onChange(fn)} fires on a real pick only, and {@code :rowHeight(n)} — defaulting to the
 * client's own label height — chooses the row height while the control is being built.
 *
 * <p><b>{@code I} is {@link LuaRows.Row}, not the raw Lua value.</b> {@code SListWidget}'s selection is
 * IDENTITY-keyed, so keeping the resolved {@link LuaRows.Row} (text and icon already pulled out, at
 * {@code :rows(t)} time — see {@link LuaRows#parse}) is what lets {@code sel} stay a stable reference across
 * ticks; {@code :value()} unwraps it back to {@link LuaRows.Row#raw}, the very value {@code :rows(t)} was
 * given, so a caller can hand it straight back to {@code :value(v)} or compare it with {@code ==}.
 *
 * <p><b>{@code :value(v)} writes {@code sel} directly, never through {@link #change}.</b> D-153's rule again:
 * a programmatic write must not re-enter {@code :onChange}. The USER-driven half is untouched —
 * {@code SListWidget.ItemWidget#mousedown} (built into every row {@link LuaRows#makeitem} hands back) still
 * calls {@code list.change(item)} on a real click, which is exactly what the override below exists to notify.
 *
 * <p><b>{@code :rowHeight(n)} rebuilds</b>, like a button's face (D-148): {@code SListBox.itemh} is
 * {@code final}, fixed at construction, so choosing a different one is not a property write but a different
 * widget under the same Lua handle — {@link Controls#rowHeight} carries the current rows, selection and
 * {@code :onChange} handler across the swap exactly as {@link Controls#image} carries a button's
 * {@code :onPress}.
 */
final class CList extends SListBox<LuaRows.Row, Widget> implements Owned.Control, Controls.Rows, Controls.Value,
        Controls.Change, Controls.RowHeight {
    /** A default box; {@code :size(w, h)} overrides it, same as every other control here. */
    static final Coord DEF_SZ = new Coord(200, 160);

    private final Owned.State own;
    private List<LuaRows.Row> curItems = Collections.<LuaRows.Row>emptyList();
    /** Exactly the table {@code :rows(t)} was last given — what the bare read hands back. */
    private LuaValue lastRows;
    /** {@code :onChange(fn)}. Volatile: the engine fires it from the input pass while Lua may be replacing it. */
    private volatile LuaValue onChange;

    CList(Addon owner, Coord sz, int itemh) {
        super(sz, itemh);
        this.own = new Owned.State(owner, this);
    }

    /** The client's own label height — what a bare {@code hafen.ui():list()} rows at until {@code :rowHeight(n)}. */
    static int defaultItemHeight() {
        return CharWnd.attrfont().height();
    }

    public Owned.State own() {
        return own;
    }

    protected List<? extends LuaRows.Row> items() {
        return curItems;
    }

    protected Widget makeitem(LuaRows.Row item, int idx, Coord sz) {
        return LuaRows.makeitem(this, item, sz);
    }

    /** {@code l:rows()} — exactly the table last given, or {@code null} before the first one. */
    public LuaValue rows() {
        return lastRows;
    }

    /**
     * {@code l:rows(t)} — an array of rows (040.9); replaces the whole set. Validated and resolved BEFORE
     * anything is torn down (see {@link LuaRows#parse}), so a bad table leaves the existing rows exactly as
     * they were. The previous selection does not carry over — it may not name a row in the new set — mirroring
     * {@link CRadio#rows}.
     */
    public void rows(LuaValue t) {
        List<LuaRows.Row> parsed = LuaRows.parse(t, "widget:rows");
        UI u = AddonManager.ui;
        synchronized(u) {
            curItems = parsed;
            sel = null;
        }
        lastRows = t;
    }

    /** {@code l:value()} — the selected row's ORIGINAL value, or {@code null} if none is selected. */
    public LuaValue value() {
        return (sel == null) ? null : sel.raw;
    }

    /** {@code l:value(v)} — {@code v} must be one of the current {@code :rows(t)}, matched by Lua equality. */
    public void value(LuaValue v) {
        for(LuaRows.Row item : curItems) {
            if(item.raw.eq_b(v)) {
                sel = item;     // direct field write -- D-153: a programmatic write never re-enters :onChange
                return;
            }
        }
        throw new LuaError("widget:value(v) on a list — that value is not one of its current rows"
            + " (widget:rows(t) gave it, or widget:onChange's argument did)");
    }

    /** {@code l:rowHeight()} — the row height in pixels, as it stands right now. */
    public int rowHeight() {
        return itemh;
    }

    public LuaValue onChange() {
        return onChange;
    }

    public void onChange(LuaValue fn) {
        this.onChange = fn;
    }

    /** A REAL selection only: a row's own {@code ItemWidget.mousedown}, or a click-away deselect, land here. */
    public void change(LuaRows.Row item) {
        super.change(item);
        LuaValue fn = onChange;
        if(!own.dead() && (fn != null))
            AddonManager.callLua(own.owner, Addon.C_WIDGET, fn, (item == null) ? LuaValue.NIL : item.raw);
    }

    public void draw(GOut g) {
        if(own.pending())   // built this statement and not armed yet: a half-configured control paints NOTHING
            return;
        super.draw(g);
    }
}
