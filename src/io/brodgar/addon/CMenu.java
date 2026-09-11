package io.brodgar.addon;

import haven.CharWnd;
import haven.Coord;
import haven.GOut;
import haven.SListMenu;
import haven.Widget;

import org.luaj.vm2.LuaValue;

import java.util.Collections;
import java.util.List;

/**
 * The adapter behind {@code hafen.ui():menu()} — a real {@link SListMenu}, the client's own row-of-actions
 * widget (spec {@code 040-ui-controls}, task 040.10): the third of the model-backed five, reusing
 * {@link LuaRows} exactly as {@link CList}/{@link CDropdown} do (D-108). It FIRES and holds nothing —
 * {@code :value()} reads {@code nil} on it (this class does not implement {@link Controls.Value}) — so
 * {@code :onSelect(fn)} is its own name rather than the shared {@code :onChange(fn)} spine.
 *
 * <p><b>Subclasses {@code SListMenu} directly</b>, the same shape {@link CDropdown} settles for {@code SDropBox}:
 * neither adapter goes through the engine's {@code of(...)} factories (which hand back anonymous subclasses),
 * so the anonymous-subclass ownership question the task poses does not arise — this class simply implements
 * {@link Owned.Control} on itself.
 *
 * <p><b>{@link SListMenu#added()} otherwise grabs ALL mouse and keyboard input the instant the widget attaches
 * to the tree</b> ({@code ui.grab}/{@code ui.grabkeys}, gated only by the {@code grab} field, default
 * {@code true}) — found reading the source, not by the suite. That is the client's own modal-popup-menu
 * behaviour (a right-click petal menu, a search results list) and is exactly wrong for a control an addon
 * builds bare and configures over several statements (D-112/D-119): the very first tick after construction
 * would steal every click and keypress in the game before {@code :rows(t)} even runs. The constructor calls
 * the engine's own {@link SListMenu#nograb()} to opt out, which is the intended escape hatch rather than a
 * workaround — this control behaves like an ordinary embedded widget, not a floating popup. One side effect:
 * the ESC-cancels / click-outside-cancels paths ({@code keydown}/{@code mousedown} calling
 * {@code choice(null)}) go inert without the grab, since neither reaches this widget for an event outside its
 * own box any more. That costs nothing this feature promises — {@code :onSelect} fires from a real row pick
 * either way.
 */
final class CMenu extends SListMenu<LuaRows.Row, Widget> implements Owned.Control, Controls.Rows,
        Controls.RowHeight, Controls.Select {
    /** A default box, in DESIGN pixels; {@code :size(w, h)} overrides it, same as every other control here. */
    static final Coord DEF_SZ = new Coord(160, 120);

    private final Owned.State own;
    /**
     * The box {@code sz} this control was BUILT with — not {@link #sz} itself, which {@code SListMenu}'s own
     * constructor immediately starts animating toward its chrome-padded resting size (a 150ms {@code NormAnim}
     * from {@code (osz.x, 0)}). A {@code :rowHeight(n)} rebuild runs on the SAME statement's tick, before that
     * animation has advanced at all, so reading the live {@code sz} there would rebuild from a near-zero-height
     * box; this field is what {@link Controls#rowHeight} reads instead.
     */
    private final Coord boxSz;
    private List<LuaRows.Row> curItems = Collections.<LuaRows.Row>emptyList();
    /** Exactly the table {@code :rows(t)} was last given — what the bare read hands back. */
    private LuaValue lastRows;

    CMenu(Addon owner, Coord sz, int itemh) {
        super(sz, itemh);
        this.boxSz = sz;
        nograb();   // see class doc: a control you place, not a modal popup
        this.own = new Owned.State(owner, this);
    }

    /** The box this control was built with (see {@link #boxSz}) — what a {@code :rowHeight(n)} rebuild reuses. */
    Coord boxSz() {
        return boxSz;
    }

    /** The client's own label height — what a bare {@code hafen.ui():menu()} rows at until {@code :rowHeight(n)}. */
    static int defaultItemHeight() {
        return CharWnd.attrfont().height();
    }

    public Owned.State own() {
        return own;
    }

    protected List<? extends LuaRows.Row> items() {
        return curItems;
    }

    /**
     * The BARE content widget, unwrapped ({@link LuaRows#content}, not {@link LuaRows#makeitem}) —
     * {@code SListMenu}'s own inner {@code InnerList.makeitem} wraps whatever this returns in ITS OWN
     * {@code Item} (an {@code ItemWidget} bound to {@code box}, the actual clickable list); wrapping it again
     * here would nest two click handlers over one row.
     */
    protected Widget makeitem(LuaRows.Row item, int idx, Coord sz) {
        return LuaRows.content(item, sz);
    }

    /** {@code d:rows()} — exactly the table last given, or {@code null} before the first one. */
    public LuaValue rows() {
        return lastRows;
    }

    /**
     * {@code m:rows(t)} — an array of rows (040.10); replaces the whole set. Validated and resolved BEFORE
     * anything is torn down (see {@link LuaRows#parse}), so a bad table leaves the existing rows exactly as
     * they were. A menu holds no selection, so there is nothing to clear on a replacement — unlike
     * {@link CList#rows}/{@link CDropdown#rows}.
     */
    public void rows(LuaValue t) {
        List<LuaRows.Row> parsed = LuaRows.parse(t, "widget:rows");
        synchronized(LuaWidget.monitor(this)) { curItems = parsed; }
        lastRows = t;
    }

    /** {@code m:rowHeight()} — the row height in pixels, as it stands right now. */
    public int rowHeight() {
        return box.itemh;
    }

    /** A REAL pick (or an inert {@code null} — see class doc, the grab that would have sent one is off). */
    protected void choice(LuaRows.Row item) {
        Controls.fire(this, "Selected", (item == null) ? LuaValue.NIL : item.raw);
    }

    public void draw(GOut g) {
        if(own.pending())   // built this statement and not armed yet: a half-configured control paints NOTHING
            return;
        super.draw(Owned.dim(this, g));   // 139.3: disabled? the whole control paints dimmed
    }
}
