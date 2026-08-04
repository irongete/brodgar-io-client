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
     * grid has that id. Backs the recorded&rarr;live half of a Position: raw world coords are login-relative
     * and cannot be persisted, so a saved place anchors on a grid id plus a within-grid offset and re-resolves
     * to login-relative world coords on load (the marker/ghost rule). The same {@code g.ul} basis the anchor
     * subtracts is added back here, so the round-trip is exact.
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
     * The streamed grid at a grid coord, or {@code null} — a <b>plain lookup</b>, backing the live half of a
     * Position's durable form. Deliberately not {@link MCache#getgrid}: that one <i>requests</i> the grid from
     * the server on a miss, and asking "where is this place" must never put traffic on the wire for ground the
     * caller is only asking about. A grid being absent is an answer here, not a load to kick.
     */
    public static MCache.Grid loadedGrid(MCache mc, Coord gc) {
        if(mc == null)
            return null;
        synchronized(mc.grids) {
            MCache.Grid g = mc.grids.get(gc);
            return ((g == null) || g.removed) ? null : g;
        }
    }

    /**
     * The map grids streamed in right now, backing {@code hafen.world():grid():list()}. A copy taken under the
     * cache's own monitor, the way {@link MCache} takes it itself; grids being removed are left out, so what
     * comes back is what is actually on the map. The grid map is a {@code haven}-package field (audit B5), so
     * the bridge reaches it through this one accessor rather than reflection (decision D-017).
     */
    public static List<MCache.Grid> loadedGrids(MCache mc) {
        List<MCache.Grid> out = new java.util.ArrayList<MCache.Grid>();
        if(mc == null)
            return out;
        synchronized(mc.grids) {
            for(MCache.Grid g : mc.grids.values()) {
                if(!g.removed)
                    out.add(g);
            }
        }
        return out;
    }

    /**
     * Has an AddOn taken this window's toggle over? Asked at the top of {@link GameUI}'s {@code togglewnd},
     * which both the menu checkbox and its keybinding reach ({@code MenuCheckBox} calls {@code setgkey}, so the
     * key fires the button's own click). {@code true} = handled, leave the window alone.
     *
     * <p>An addon that hid a native window with {@code widget:visible(false)} <b>owns</b> it (spec
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

    /**
     * The <b>window-chrome seam</b> (spec {@code 035-ui-chrome}, C2) — asked once per window per
     * {@link Window#tick}: should this window be carrying the sheet-fed {@code Deco} right now, or the stock one?
     * A {@code hafen.ui.skin{["window.frame"] = {bg=…, border=…}}} rule is what puts it on, and dropping the
     * sheet is what takes it off; the swap goes through the public {@link Window#chdeco} the engine already
     * provides, so nothing about {@code Window} is re-routed.
     *
     * <p>It lives in {@code tick} rather than in a draw because {@code chdeco} destroys a widget and re-lays the
     * window out — neither belongs inside a draw pass. With no addon override installed anywhere the whole call
     * is two {@code volatile} reads and allocates nothing.
     */
    public static void chrome(Window wnd) {
        io.brodgar.addon.AddonManager.chrome(wnd);
    }

    /**
     * The <b>layout-persistence seam</b> (spec {@code 036-ui-layout}, E) — the position the client should write
     * down for a window it persists ({@link GameUI}'s {@code savewndpos}, {@code cdestroy}'s {@code wndc-misc},
     * the crafting window's {@code makewndc}). Normally the widget's own {@code c}; for a widget an AddOn's
     * layout is standing on, the coordinate the <b>user</b> last placed it at.
     *
     * <p>An addon's layout is a <i>layer</i> over the client's own, never a write into it. Everything else it does
     * is reversible in memory, but {@code Utils.setprefc} is not: without this, moving {@code invwnd} would have
     * the client persist the addon's position as the user's own preference, and uninstalling the addon would leave
     * those windows displaced forever. Substituting the value (rather than shoving the window back and forth
     * around the write) is also what keeps the 60 s {@code savewndpos} tick invisible: nothing on screen moves,
     * only what is written down.
     *
     * <p>Tolerates {@code null} and never throws; with no AddOn laying anything out it is one volatile read and
     * hands {@code wnd.c} straight back, so a stock client writes exactly the bytes it wrote before.
     */
    public static Coord stockc(Widget wnd) {
        return io.brodgar.addon.AddonManager.stockPos(wnd);
    }

    /**
     * The <b>size</b> counterpart of {@link #stockc}, for the one geometry the client persists beside a position
     * ({@code wndsz-map}): a {@link Window}'s content size — the same value {@code csz()} answers — or the stock
     * one when an AddOn has resized it.
     */
    public static Coord stockcsz(Window wnd) {
        return io.brodgar.addon.AddonManager.stockSize(wnd);
    }
}
