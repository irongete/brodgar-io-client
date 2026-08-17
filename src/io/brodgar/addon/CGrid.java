package io.brodgar.addon;

import haven.Coord;
import haven.GOut;
import haven.GridList;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The adapter behind {@code hafen.ui():grid()} — a real {@link GridList}, the client's own laid-out icon grid
 * (spec {@code 040-ui-controls}, task 040.11): the fourth of the model-backed five, and the odd one out —
 * {@code GridList} does not build row WIDGETS the way {@code SListWidget} does, it DRAWS cells
 * ({@code drawitem(GOut, T)}), so this adapter takes no part in the {@link LuaRows} bridge the other four
 * share. {@code :rows(t)} is a plain array of arbitrary Lua values (item shape is the addon's own, unlike the
 * string/{@code {icon=,text=}} rows the bridge enforces); {@code :cell(w, h)} is the cell box, building-only
 * like {@code :rowHeight(n)} ({@code Group.itemsz} is {@code final}); {@code :onCell(g, item, w, h)} paints one
 * cell through the SAME {@link LuaGOut} wrapper {@code widget:onDraw(fn)} hands a surface (task 040.11,
 * D-163) — bound for the duration of one {@link #drawitem} call and inert otherwise, exactly
 * {@link AddonWidget#draw}'s own shape.
 *
 * <p><b>One {@link Group}, built once, at the constructor's cell size.</b> {@code GridList} lays several named
 * groups (a caption each) downward, but a Lua grid is one flat item list with no heading, so this control
 * builds exactly one — {@code marg = (-1, 5)}, the client's own even-spread-across-the-width shape
 * ({@code SkillWnd.SkillGrid}/{@code ExpGrid} use the same margin for their icon grids) — and every
 * {@code :rows(t)} feeds it through {@link Group#update}, which is public and live. The cell BOX itself
 * (`itemsz`) is {@code final} on {@code Group}, so — like a list's row height — choosing a different one is
 * not a property write but a different widget under the same Lua handle (D-164): {@link Controls#cell} rebuilds
 * exactly as {@link Controls#rowHeight} does, carrying the current rows and {@code :onCell} handler across.
 *
 * <p><b>A handler that throws is isolated PER CELL, not per frame.</b> {@link AddonManager#callLua} already
 * catches every Lua/Java error a callback raises and returns without rethrowing — the same choke point
 * {@code widget:onDraw(fn)} goes through — so one cell's {@code :onCell} throwing costs that cell's line in
 * the log and nothing else: {@code GridList.draw}'s own loop keeps calling {@link #drawitem} for every
 * remaining item in the list on the very same frame.
 */
final class CGrid extends GridList<LuaValue> implements Owned.Control, Controls.Rows, Controls.Cell,
        Controls.OnCell {
    /** A default box, in DESIGN pixels; {@code :size(w, h)} overrides it, same as every other control here. */
    static final Coord DEF_SZ = new Coord(200, 200);
    /**
     * The client's own inventory-slot size, in DESIGN pixels — what a bare {@code hafen.ui():grid()} cells at until
     * {@code :cell(w, h)}, and the same integer {@code :cell()} reads back at every UI scale.
     */
    static final Coord DEF_CELL = new Coord(32, 32);
    /**
     * The engine's own even-spread-across-the-width margin ({@code SkillGrid}/{@code ExpGrid}): no fixed x gap, and
     * <b>5 DESIGN px</b> of row gap, converted at the one place it is spent (the constructor below). The {@code -1}
     * is {@code GridList}'s sentinel for "spread the columns", tested as {@code marg.x >= 0} — a number, not a
     * length, so it is the one term here that must not be scaled.
     */
    private static final Coord MARG = new Coord(-1, 5);

    private final Owned.State own;
    private final LuaGOut gwrap = new LuaGOut();   // this control's own g wrapper (026.1's per-owner text cache)
    final Coord cellSz;
    private final Group group;
    private List<LuaValue> curItems = Collections.<LuaValue>emptyList();
    /** Exactly the table {@code :rows(t)} was last given — what the bare read hands back. */
    private LuaValue lastRows;

    CGrid(Addon owner, Coord sz, Coord cellSz) {
        super(sz);
        this.cellSz = cellSz;
        this.group = new Group(cellSz, Coord.of(MARG.x, Px.in(MARG.y)), null,
                               Collections.<LuaValue>emptyList());
        this.own = new Owned.State(owner, this);
    }

    public Owned.State own() {
        return own;
    }

    /** {@code g:rows()} — exactly the table last given, or {@code null} before the first one. */
    public LuaValue rows() {
        return lastRows;
    }

    /**
     * {@code g:rows(t)} — a plain array of arbitrary Lua values (040.11); replaces the whole set. Validated
     * before anything is torn down, mirroring the other model-backed controls: a bad table leaves the existing
     * rows exactly as they were.
     */
    public void rows(LuaValue t) {
        List<LuaValue> parsed = parseRows(t);
        synchronized(LuaWidget.monitor(this)) {
            curItems = parsed;
            group.update(curItems);
        }
        lastRows = t;
    }

    /** {@code g:cell()} — the current cell box, in pixels. */
    public Coord cell() {
        return cellSz;
    }

    /**
     * The one cell painter (040.11, re-spelled 041.4): binds THIS control's own {@link LuaGOut} to {@code g} —
     * reclipped to the cell's own box by {@code GridList.draw} before it ever reaches here, so {@code (0,0)} is
     * the cell's own top-left, exactly like a surface's local draw space — and fires {@code "Cell"} with an
     * {@code ev} answering {@code :g()}/{@code :item()}/{@code :w()}/{@code :h()} (R4: four things to say)
     * through the same error-isolated, watchdog-armed choke point every other Lua callback in this bridge uses.
     * Skips the bind entirely when nobody is listening — the {@code hasSub} gate one cell at a time.
     */
    protected void drawitem(GOut g, LuaValue item) {
        if(own.dead())
            return;
        WidgetSubs s = own.owner.widgetSubsOrNull(this);
        if((s == null) || !s.subs.has("Cell"))
            return;
        LuaTable gt = gwrap.bind(g, own.owner, null);
        try {
            LuaValue ev = LuaEvent.cell(own.owner, gt, item, cellSz.x, cellSz.y);
            s.subs.fire("Cell", ev);
        } finally {
            gwrap.unbind();   // invalidate the wrapper outside the callback (no stashing) -- LuaGOut's own rule
        }
    }

    public void draw(GOut g) {
        if(own.pending())   // built this statement and not armed yet: a half-configured control paints NOTHING
            return;
        super.draw(g);
    }

    /** An array of arbitrary, non-nil Lua values — the item shape itself is entirely {@code :onCell}'s to read. */
    private static List<LuaValue> parseRows(LuaValue t) {
        if(!t.istable())
            throw new LuaError("widget:rows(t) is an ARRAY of grid items, got " + t.typename());
        int n = t.length();
        List<LuaValue> rows = new ArrayList<LuaValue>(n);
        for(int i = 1; i <= n; i++) {
            LuaValue e = t.get(i);
            if(e.isnil())
                throw new LuaError("widget:rows(t): row " + i + " is nil");
            rows.add(e);
        }
        return rows;
    }
}
