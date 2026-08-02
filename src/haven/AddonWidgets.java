package haven;

import java.util.List;

/**
 * addon: package-scoped accessors for the AddOn widget-tree read adapters
 * ({@code io.brodgar.addon}, spec {@code 14-widget-tree-reads.md}).
 *
 * <p>Much high-value client state lives in {@link GameUI} widget trees whose fields are
 * {@code private}/{@code protected} (audit B5), so the bridge cannot read them from its own package.
 * Rather than raw reflection, the adapters go through this single {@code haven}-package helper — the
 * same trick {@link SpeakerIcon} uses for the buddy label. It localizes the one non-zero-edit read
 * surface in one place: upstream churn breaks this file, not every adapter, and Lua never gets
 * reflection (decision D-017).
 *
 * <p>Most methods are pure reads, tolerate {@code null}, and never throw {@code Loading} — a partial
 * read is reported as {@code null} to the adapter. The exception is the <b>window-toggle seam</b>
 * ({@link #toggleWnd}/{@link #wndState}, spec {@code 031-window-lifecycle}), which runs the other way
 * round: {@link GameUI} asks the addon layer whether an addon has taken a window over. It lives here for
 * the same reason as the reads — one file in {@code haven} carries the whole non-zero-edit surface, so
 * {@code GameUI} itself keeps to a one-line question inside a method body.
 */
public final class AddonWidgets {
    private AddonWidgets() {
    }

    /**
     * The bar segments of a {@link LayerMeter} (the {@code protected meters} list), backing
     * {@code meter:value()}/{@code :color()}/{@code :segments()}. Each {@link LayerMeter.Meter} carries a
     * fraction {@code a} (0..1) and a colour; a vital bar has a single segment, but the type is genuinely
     * multi-segment. Never {@code null}.
     */
    public static List<LayerMeter.Meter> meters(LayerMeter m) {
        return (m == null) ? java.util.Collections.<LayerMeter.Meter>emptyList() : m.meters;
    }

    /**
     * The HUD's meter bars, in HUD (layout) order — the {@link GameUI} {@code meters} list itself, filtered
     * to {@link IMeter}. Backs {@code hafen.meter()} (spec {@code 027-meters-oop}).
     *
     * <p>This is the engine's <i>own</i> ordered record of what sits in the {@code place == "meter"} HUD
     * slot (appended there, removed in {@code cdestroy}), which is why it is preferred over a
     * {@code gui.children(IMeter.class)} walk: that would be a DFS over the whole HUD in tree order, and
     * would also collect any {@link IMeter} placed somewhere else. The list is {@code List<Widget>}, so it
     * is filtered rather than cast. A fresh list; never {@code null}.
     */
    public static List<IMeter> hudMeters(GameUI g) {
        List<IMeter> out = new java.util.ArrayList<IMeter>();
        if(g != null) {
            for(Widget w : g.meters) {
                if(w instanceof IMeter)
                    out.add((IMeter)w);
            }
        }
        return out;
    }

    /**
     * Whether a {@link Buff} is fading out after a server-side removal (the {@code protected dest}
     * flag). The bridge treats a {@code dest} buff as already gone, so {@code hafen.buff()} omits
     * it and {@code BuffRemoved} fires at removal time rather than 0.35s later when the fade finishes.
     */
    public static boolean buffDest(Buff b) {
        return (b != null) && b.dest;
    }

    /**
     * Resolve a stable grid id ({@link MCache.Grid#id} — the cross-session, cross-player map anchor) to the
     * world coordinate of that grid's upper-left corner in the CURRENT session, or {@code null} if no loaded
     * grid has that id. Backs {@code hafen.map.fromGridPos}, the inverse of {@code hafen.map.gridPos}: raw
     * world coords are login-relative and cannot be persisted, so a saved layout anchors on grid ids plus a
     * within-grid offset and re-resolves to login-relative world coords on load (the marker/ghost rule). The
     * same {@code g.ul} basis {@code gridPos} subtracts is added back here, so the round-trip is exact.
     *
     * <p>The grid map is a {@code haven}-package field (audit B5), so the bridge reaches it through this one
     * accessor rather than reflection (decision D-017). Pure read; tolerates a {@code null} cache; never throws.
     */
    public static Coord2d gridWorldUL(MCache mc, long id) {
        if(mc == null)
            return null;
        synchronized(mc.grids) {
            for(MCache.Grid g : mc.grids.values()) {
                if((g.id == id) && !g.removed)
                    return Coord2d.of(g.ul.x * MCache.tilesz.x, g.ul.y * MCache.tilesz.y);
            }
        }
        return null;
    }

    /**
     * Has an AddOn taken this window's toggle over? Asked at the top of {@link GameUI}'s {@code togglewnd},
     * which both the menu checkbox and its keybinding reach ({@code MenuCheckBox} calls {@code setgkey}, so the
     * key fires the button's own click). {@code true} = handled, leave the window alone.
     *
     * <p>An addon that hid a native window with {@code widget:hide()} <b>owns</b> it (spec
     * {@code 031-window-lifecycle}): without this the client would flip {@code visible} straight back on the very
     * window the addon hid, which is why the stock inventory used to reappear on Tab beside a replacement. A
     * window nobody owns — and every window at all when no addon is loaded — answers {@code false} here and the
     * client behaves exactly as before.
     */
    public static boolean toggleWnd(Window wnd) {
        return io.brodgar.addon.AddonManager.toggleWnd(wnd);
    }

    /**
     * What the menu checkbox's tick should say for a window an AddOn owns, or {@code null} for one it does not
     * (read the window itself, as stock). Counterpart of {@link #toggleWnd}: the tick has to follow whatever the
     * toggle now drives, or the button lies about what is on screen.
     *
     * <p>This is a per-frame {@code state()} supplier on six checkboxes, so the addon side is a volatile read
     * and an identity walk that allocates nothing.
     */
    public static Boolean wndState(Window wnd) {
        return io.brodgar.addon.AddonManager.wndState(wnd);
    }
}
