package io.brodgar.addon;

import haven.CharWnd;
import haven.Coord;
import haven.GOut;
import haven.SDropBox;
import haven.UI;
import haven.Widget;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaValue;

import java.util.Collections;
import java.util.List;

/**
 * The adapter behind {@code hafen.ui():dropdown()} — a real {@link SDropBox}, the client's own closed-until-
 * clicked row list (spec {@code 040-ui-controls}, task 040.10): the second of the model-backed five, reusing
 * {@link LuaRows} exactly as {@link CList} does (D-108). {@code :rows(t)} is the row source,
 * {@code :value()}/{@code :value(v)} the current pick, {@code :onChange(fn)} fires on a real pick only, and
 * {@code :rowHeight(n)} — like {@link CList}'s — is building-only.
 *
 * <p><b>Subclasses {@code SDropBox} directly, exactly like {@link CList} subclasses {@code SListBox}</b> — the
 * task's own open question (how ownership attaches when the engine's {@code SDropBox.of(...)} factory hands
 * back an anonymous subclass) does not arise, because this adapter never calls that factory: it IS the
 * concrete class, so {@link Owned.Control} is implemented on it directly, the same shape every other
 * model-backed control in this feature already uses.
 *
 * <p><b>{@code :value(v)} calls {@code super.change(item)} directly, NOT a raw {@code sel} field write.</b>
 * {@link CList}'s {@code :value(v)} writes {@code sel} alone because {@code SListBox} draws its highlight from
 * that field fresh every frame; {@code SDropBox} additionally keeps a second piece of state — the widget shown
 * in the closed box — that is built and swapped only inside {@link SDropBox#change}. Calling
 * {@code super.change(item)} from the write runs exactly that state update (and its {@code makeitem} rebuild
 * of the closed-box widget through {@link #makeitem}) while skipping THIS adapter's own override below, which
 * is where the {@code :onChange} notify lives — the same D-153 outcome (no re-entering the handler) reached
 * through the shape this engine class actually has.
 *
 * <p><b>{@link #makeitem} must tolerate a {@code null} item.</b> {@link SDropBox#change} calls it with
 * whatever it is given, including {@code null} — the engine's own path for "no selection" — and
 * {@link LuaRows#makeitem} would NPE on one. {@link #rows} uses exactly that {@code null} path (via
 * {@code super.change(null)}) to clear the closed-box widget when {@code :rows(t)} replaces the table out from
 * under the current selection, mirroring {@link CList#rows}'s own "the previous selection does not carry
 * over" rule.
 *
 * <p><b>The open popup is raised again on the SAME frame's tick — the click that opens it would otherwise
 * bury it behind its own enclosing window.</b> Found in-game, not by the suite: {@code SDropList} is added as
 * the LAST child of {@code ui.root} from inside {@code drop.click()} — reached while the click is still
 * propagating DOWN through the tree — but {@code Window.mousedown} only calls {@code raise()} on ITSELF after
 * that propagation returns, which re-appends the enclosing window (its own opaque {@code drawbg} included)
 * AFTER the popup, covering it for as long as the window stays on top. There is no point inside this class's
 * own click handling where "after the ancestor's raise()" already happened — that only exists on a LATER
 * pass — so {@link #drop} queues the just-opened popup (found structurally, the {@code SDropList} class
 * offers no other handle) and {@link #drainRaises} — called from {@code AddonManager.tick()} right beside
 * {@code UiApi.armPending()} — re-raises it. Input dispatch for a frame finishes before that tick runs (the
 * same ordering {@code armPending()} already relies on: "a window built in an input handler is on screen in
 * the very frame it was asked for"), so this resolves within the SAME frame the click landed in — no visible
 * flicker.
 */
final class CDropdown extends SDropBox<LuaRows.Row, Widget> implements Owned.Control, Controls.Rows,
        Controls.Value, Controls.Change, Controls.RowHeight {
    /** Defaults; {@code :size(w, h)} overrides the width, {@code :rowHeight(n)} the item height (building-only). */
    static final int DEF_W = 200, DEF_LISTH = 160;

    /** Popups queued by {@link #drop} this frame, raised by {@link #drainRaises} before the next draw. */
    private static final List<Widget> toRaise = new java.util.ArrayList<Widget>();

    private final Owned.State own;
    private List<LuaRows.Row> curItems = Collections.<LuaRows.Row>emptyList();
    /** Exactly the table {@code :rows(t)} was last given — what the bare read hands back. */
    private LuaValue lastRows;

    CDropdown(Addon owner, int w, int listh, int itemh) {
        super(w, listh, itemh);
        this.own = new Owned.State(owner, this);
    }

    /** The client's own label height — what a bare {@code hafen.ui():dropdown()} rows at until {@code :rowHeight(n)}. */
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
     * {@code SDropBox}'s own {@code SDropList.Item} (open list) and its {@code change()} (closed box) each do
     * their own click-wrapping around whatever this returns. {@code null} is the engine's own "nothing
     * selected" case ({@link SDropBox#change}, and {@link #rows}'s clear).
     */
    protected Widget makeitem(LuaRows.Row item, int idx, Coord sz) {
        if(item == null)
            return new Widget(sz) {};
        return LuaRows.content(item, sz);
    }

    /** {@code d:rows()} — exactly the table last given, or {@code null} before the first one. */
    public LuaValue rows() {
        return lastRows;
    }

    /**
     * {@code d:rows(t)} — an array of rows (040.10); replaces the whole set. Validated and resolved BEFORE
     * anything is torn down (see {@link LuaRows#parse}), so a bad table leaves the existing rows exactly as
     * they were. The previous selection does not carry over — it may not name a row in the new set — so the
     * closed box is cleared via {@code super.change(null)}, bypassing this adapter's own {@code :onChange}
     * notify (clearing on a table replacement is not a user pick).
     */
    public void rows(LuaValue t) {
        List<LuaRows.Row> parsed = LuaRows.parse(t, "widget:rows");
        UI u = AddonManager.ui;
        synchronized(u) {
            curItems = parsed;
            super.change(null);
        }
        lastRows = t;
    }

    /** {@code d:value()} — the picked row's ORIGINAL value, or {@code null} if nothing is picked. */
    public LuaValue value() {
        return (sel == null) ? null : sel.raw;
    }

    /** {@code d:value(v)} — {@code v} must be one of the current {@code :rows(t)}, matched by Lua equality. */
    public void value(LuaValue v) {
        for(LuaRows.Row item : curItems) {
            if(item.raw.eq_b(v)) {
                UI u = AddonManager.ui;
                synchronized(u) { super.change(item); }   // D-153: bypasses THIS class's onChange-firing override
                return;
            }
        }
        throw new LuaError("widget:value(v) on a dropdown — that value is not one of its current rows"
            + " (widget:rows(t) gave it, or widget:onChange's argument did)");
    }

    /** {@code d:rowHeight()} — the row height in pixels, as it stands right now. */
    public int rowHeight() {
        return itemh;
    }

    /** A REAL pick only: {@code SDropBox.SDropList}'s own {@code Item.mousedown} lands here via {@code change}. */
    public void change(LuaRows.Row item) {
        super.change(item);
        Controls.fire(this, "Changed", (item == null) ? LuaValue.NIL : item.raw);
    }

    public void draw(GOut g) {
        if(own.pending())   // built this statement and not armed yet: a half-configured control paints NOTHING
            return;
        super.draw(g);
    }

    /**
     * The engine's own open/close toggle for the popup list (bound to the drop arrow's click). On OPEN, the
     * popup was just added as the last child of {@code ui.root} inside {@link SDropBox#drop} above — found
     * structurally (there is no other handle to it: {@code SDropList}'s field on the superclass is private,
     * even to this subclass) and queued for {@link #drainRaises} to re-raise once this frame's input
     * dispatch — including the enclosing window's own {@code raise()} — has finished. See the class doc.
     */
    public void drop(boolean st) {
        super.drop(st);
        if(st) {
            Widget last = null;
            for(Widget w = ui.root.child; w != null; w = w.next)
                last = w;
            if(last instanceof SDropBox.SDropList) {
                synchronized(toRaise) { toRaise.add(last); }
            }
        }
    }

    /** Called from {@code AddonManager.tick()}, right beside {@code UiApi.armPending()} — see the class doc. */
    static void drainRaises() {
        if(toRaise.isEmpty())
            return;
        List<Widget> due;
        synchronized(toRaise) {
            due = new java.util.ArrayList<Widget>(toRaise);
            toRaise.clear();
        }
        for(Widget w : due) {
            if(w.parent != null)
                w.raise();
        }
    }
}
