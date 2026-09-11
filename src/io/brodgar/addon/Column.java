package io.brodgar.addon;

import haven.Coord;
import haven.Widget;

import java.util.List;

import org.luaj.vm2.LuaError;

/**
 * <b>A column lays its rows out</b> (spec {@code 139-a-column-lays-its-rows-out}, task 139.1) — the layout behind
 * {@code hafen.ui():column()} / {@code :row()}: an {@link AddonWidget} with an {@link AddonWidget.Axis}, whose
 * children are placed along that axis in tree order, {@code gap} apart, one {@code padding} in from the edge, each
 * inside its own {@code margin} (139.2), and whose box is its content's unless {@code :size} pinned an axis.
 *
 * <p><b>Laid out on the events that change it, never per frame</b> — {@code geometry.md}'s discipline for an
 * anchor, kept here. Every change of a child already passes a seam: entering ({@link AddonWidget#add}),
 * resizing ({@link AddonWidget#cresize}, reached from {@code Widget.resize}), leaving
 * ({@link AddonWidget#cdestroy}), hiding (the {@code visible} write in {@link LuaWidget}, the only writer for an
 * owned child), and the cascade moving ({@link Layout#apply}, the {@code stock} write and a {@code widget:rule()}
 * write — the column's own {@code padding}, or a child's {@code margin}). Each of those calls {@link #relayout}
 * or {@link #childChanged}, so by the time the call that changed a child returns, the rest of the column is
 * where it belongs.
 *
 * <p><b>It never resizes a child</b>, only moves it and resizes the column itself — which is what keeps the walk
 * from cycling: a column's own resize reaches its parent's {@code cresize}, so a column inside a column re-lays
 * the outer one, and the recursion goes up and ends at the top.
 *
 * <p><b>The child's place is its order</b>: {@code :position(x, y)} and {@code :position(nil)} on one refuse
 * here, {@link Layout#applyHalf} skips the position half of the cascade for it, and a window or a borrowed widget
 * is refused as a child ({@link #refuseChild}). The refusal texts live on this class so the verbs that raise them
 * say the same thing.
 *
 * <p>Caller conventions: {@link #relayout} takes the column's own tree monitor ({@link LuaWidget#monitor}) and
 * reads {@link Sheet#styleOf} inside it — the order the draw pass established — so it must never be called with
 * {@code Sheet.class} held, which is why the stock and rule writes call it after their registry call returns.
 */
final class Column {
    private Column() {}

    /** The column or row {@code w} is, or {@code null} for every other widget. */
    static AddonWidget of(Widget w) {
        if(!(w instanceof AddonWidget))
            return null;
        AddonWidget a = (AddonWidget)w;
        return (a.axis == AddonWidget.Axis.NONE) ? null : a;
    }

    /** Does {@code w} lay its children out? */
    static boolean stacks(Widget w) {
        return of(w) != null;
    }

    /** Is {@code w} laid out by a column or a row — is its place its order? */
    static boolean stacked(Widget w) {
        return (w != null) && stacks(w.parent);
    }

    /** A child of {@code w} moved, resized, hid or showed: re-lay the column it stands in, if it stands in one. */
    static void childChanged(Widget w) {
        AddonWidget col = (w == null) ? null : of(w.parent);
        if(col != null)
            relayout(col);
    }

    /**
     * {@link Layout#apply} ran for {@code w}: its own cascade (a {@code padding} it now wears, or the
     * {@code margin} the column around it keeps) or its parent's (the size the rule gave it) may have moved
     * something. Caller holds {@code w}'s tree monitor.
     */
    static void applied(Widget w) {
        AddonWidget col = of(w);
        if(col != null)
            relayout(col);
        childChanged(w);
    }

    /**
     * Every live column of every addon, re-laid — the sheet just changed, so a {@code padding} a tree rule gives
     * a column may have moved. Bounded by the owned-widget registries, not by the tree: a client with no column
     * walks two empty lists. Called from {@link Layout#sweep} holding no monitor; each column takes its own.
     */
    static void sweep() {
        List<Addon> as = AddonManager.addons;
        for(int i = 0, n = as.size(); i < n; i++)
            sweep(as.get(i));
        Addon c = AddonManager.consoleOwner;
        if(c != null)
            sweep(c);
    }

    private static void sweep(Addon a) {
        for(Owned o : a.widgets) {
            AddonWidget col = of(o.widget());
            // A column in a tree this thread is not already inside: taking a SECOND tree's monitor is the
            // nesting LuaWidget.monitor refuses (112.2), and a sweep is not the place to raise it -- the column
            // is re-laid by its own next event instead.
            if((col != null) && !col.dead() && !LuaWidget.wouldNest(col))
                relayout(col);
        }
    }

    /**
     * <b>The layout</b>: walk the visible children in tree order, place each one padding in and {@code gap} after
     * the previous, inside its own margin, and size the column to the content unless an axis is pinned.
     * Idempotent — a child already where the walk puts it takes no write, and {@code Widget.resize} returns
     * early on an equal box — which is what lets every seam call it freely. Device pixels throughout:
     * {@code gap}, the padding and each margin convert at the one line each meets the tree.
     *
     * <p><b>A margin is the child's, read from its own cascade</b> (139.2) — a tree rule that names it, its
     * stock, or its {@code widget:rule()} — and it is room <i>around</i> the child on all four sides: the child
     * is placed its left and top inset further in, and the walk advances by its box plus both insets along the
     * axis, then by the gap. Two margins that meet across a gap are both kept, never collapsed, so
     * {@code :position()} on the next child reads what the arithmetic says. Across the axis the margin widens
     * the content the column measures itself to, so a right or bottom inset is room the box keeps too.
     */
    static void relayout(AddonWidget col) {
        if((col == null) || col.dead())
            return;
        synchronized(LuaWidget.monitor(col)) {
            boolean down = (col.axis == AddonWidget.Axis.COLUMN);
            Sheet.Resolved r = Sheet.styleOf(col);
            Chrome.Pad pad = (r == null) ? null : r.padding;
            Coord tl = (pad == null) ? Coord.z : pad.tlIn();
            Coord br = (pad == null) ? Coord.z : pad.brIn();
            int gap = Px.in(col.gap);
            int at = down ? tl.y : tl.x;      // where the next child starts, along the axis
            int across = 0;                   // the widest child, across it
            boolean first = true;
            for(Widget ch = col.child; ch != null; ch = ch.next) {
                if(!ch.visible)
                    continue;                 // a hidden child takes no room, and the rest close up
                if(!first)
                    at += gap;
                first = false;
                Sheet.Resolved cr = Sheet.styleOf(ch);    // the CHILD's cascade: its margin is its own
                Chrome.Pad m = (cr == null) ? null : cr.margin;
                Coord mtl = (m == null) ? Coord.z : m.tlIn();
                Coord mbr = (m == null) ? Coord.z : m.brIn();
                Coord want = down ? Coord.of(tl.x + mtl.x, at + mtl.y) : Coord.of(at + mtl.x, tl.y + mtl.y);
                if(!want.equals(ch.c))
                    ch.move(want);
                at += down ? (mtl.y + ch.sz.y + mbr.y) : (mtl.x + ch.sz.x + mbr.x);
                across = Math.max(across, down ? (mtl.x + ch.sz.x + mbr.x) : (mtl.y + ch.sz.y + mbr.y));
            }
            Coord content = down ? Coord.of(tl.x + across + br.x, at + br.y)
                                 : Coord.of(at + br.x, tl.y + across + br.y);
            Coord to = Coord.of((col.pinW >= 0) ? col.pinW : content.x, (col.pinH >= 0) ? col.pinH : content.y);
            if(!to.equals(col.sz))
                col.resize(to);               // → parent.cresize: a column inside a column re-lays the outer one
        }
    }

    // ---- the refusals, in one place so the verbs agree ---------------------------------------------

    /** {@code widget:position(x, y)} / {@code :position(nil)} on a child a column lays out. */
    static LuaError placed(String verb) {
        return new LuaError(verb + ": this widget stands in a column, and its place is its order — the column"
            + " puts it after the child before it, and widget:gap(n) on the column is the room between them."
            + " widget:parent(other) takes it out while it is being built, and widget:destroy() at any time.");
    }

    /** {@code widget:pack()} on a column. */
    static LuaError packed(String role) {
        return new LuaError("widget:pack(): a " + role + " is packed by construction — its box is its content's,"
            + " re-measured whenever a child enters, resizes, hides or leaves. widget:size(w) pins the width and"
            + " leaves the height to the children, widget:size(w, h) pins both, and widget:size(nil) lets the"
            + " box follow again.");
    }

    /**
     * {@code widget:parent(col)} on a widget a column cannot lay out: a WINDOW (the user places one, its chrome
     * measures itself and never tells a parent when it resizes) or one of the CLIENT's own (its place is the
     * client's, and the column would be the second thing writing it). {@code what} names the child.
     */
    static LuaError refuseChild(String role, String what, boolean window) {
        if(window)
            return new LuaError("widget:parent(w): a window cannot stand in a " + role + " — the user drags a window"
                + " where they want it, and its chrome measures itself without telling the " + role + ". Build the"
                + " " + role + " inside the window instead (hafen.ui():column():parent(win)), or a bare"
                + " hafen.ui():widget() for a panel inside the " + role + ".");
        return new LuaError("widget:parent(w): " + what + " is one of the client's own widgets, and a " + role
            + " lays out only what your addon built — the client keeps placing its own, and the " + role + " would"
            + " be the second thing writing where it sits. Take it into a bare hafen.ui():widget() of yours instead,"
            + " and put that in the " + role + ".");
    }
}
