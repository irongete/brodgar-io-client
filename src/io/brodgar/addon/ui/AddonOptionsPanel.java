package io.brodgar.addon.ui;

import haven.Coord;
import haven.OptWnd;
import haven.Scrollport;
import haven.Widget;

import io.brodgar.addon.AddonManager;

/**
 * <b>One addon's page</b> of the AddOns tab of the settings window (spec
 * {@code 140-the-options-page-is-the-addons}, 140.1) — what the tab puts in its holder when a row is picked:
 * a {@link Scrollport} filling the box, and inside the port {@link #root}, a column of the addon's own that
 * the addon fills. <b>No heading</b>: the row picked in the list beside it already reads the addon's name,
 * and the other pages of the window carry none either.
 *
 * <p><b>The client builds the frame and the addon builds the page.</b> {@code root} comes from
 * {@link AddonManager#mountPage}: an owned column, its width pinned to the port's, armed at once and registered
 * with everything else the addon built, so {@code :gap}, {@code :stock} and {@code :enabled} answer on it and
 * a control {@code :parent(root)}'d into it is placed by the column. What the addon puts in it dies with this
 * panel: the tab destroys a page on every re-visit ({@code PanelEntry.fresh}) and on every census change
 * ({@code Subject.reset}), and the column and its rows leave through the disposal seam like any control built
 * into one of the client's windows.
 *
 * <p><b>The fill is deferred, and this constructor queues it.</b> It runs inside the window's tree, and the
 * addon's builders attach to the layer, whose monitor is refused from inside another tree's — so
 * {@code mountPage} queues {@code fn(root)} for the layer's step, and the page fills a frame after it opens.
 *
 * <p><b>The port is {@code OptWnd.PAGE}.</b> How tall the page is is the addon's to choose, so this is the
 * one panel in this window whose column nothing bounds — the shape {@code OptWnd.BindingPanel} wears for
 * exactly the same reason. The port is the whole page box at every height: a short page leaves the rest of
 * the box empty rather than shrinking, because the box is what the window is drawn around, and a long one
 * scrolls, the port's own {@code Scrollcont} re-measuring its range as the column grows.
 *
 * <p>It extends {@code OptWnd.Panel} (a non-static inner class) from this package through the qualified
 * {@code opt.super()} form, and carries <b>no caption</b>: it is drawn inside the settings view's holder, so
 * the window's subject is that view's and not this page's.
 */
public class AddonOptionsPanel extends OptWnd.Panel {
    private final Scrollport port;
    /** The column the addon fills — owned by it, mounted by the client, dying with this panel. */
    public final Widget root;

    public AddonOptionsPanel(OptWnd opt, AddonManager.OptionGroup group) {
        opt.super();
        port = add(new Scrollport(OptWnd.PAGE), Coord.z);
        root = port.cont.add(AddonManager.mountPage(group, port.cont.sz.x), Coord.z);
        resize(OptWnd.PAGE);   // the page IS the box, whatever ends up in it
    }
}
