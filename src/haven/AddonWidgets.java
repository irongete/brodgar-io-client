package haven;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * addon: package-scoped accessors for the AddOn read adapters ({@code io.brodgar.addon}), for state
 * that {@code haven} keeps {@code private}/{@code protected} — {@link GameUI} widget trees (spec
 * {@code 14-widget-tree-reads.md}), and beyond them any other field this package hides that an addon
 * still needs to read, such as a {@link ResDrawable}'s state bytes.
 *
 * <p>Rather than raw reflection, the adapters go through this single {@code haven}-package helper — the
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
     * flag). The bridge treats a {@code dest} buff as already gone, so {@code hafen.buff():list()} omits
     * it and {@code BuffRemoved} fires at removal time rather than 0.35s later when the fade finishes.
     */
    public static boolean buffDest(Buff b) {
        return (b != null) && b.dest;
    }

    /**
     * The state bytes of a gob's resource drawable ({@link ResDrawable#sdt}, package-private) —
     * backs {@code gob:sdt()}. {@code null} for a gob whose {@link Drawable} is not a
     * {@link ResDrawable} at all (a player's body is a {@code Composite}), which is a documented
     * answer rather than a hole; the empty array is a resource-drawn gob the server sent no state
     * for, and the two are not interchangeable.
     *
     * <p>Hands back {@code sdt.clone().bytes()} rather than reading the field's own bytes directly:
     * a fresh reader off {@link MessageBuf#clone()} leaves the sprite's own cursor untouched, since
     * the field is shared with whatever {@link haven.Sprite} was built from it.
     *
     * <p>Read under the gob monitor, which is the write side's own — {@code ResDrawable.$cres.apply}
     * reassigns the field from inside {@code OCache.ObjDelta.apply}'s {@code synchronized(gob)} — so
     * that is the whole of the correctness this needs; no {@code volatile}.
     */
    public static byte[] gobSdt(Gob g) {
        if(g == null)
            return null;
        synchronized(g) {
            Drawable d = g.getattr(Drawable.class);
            if(!(d instanceof ResDrawable))
                return null;
            return ((ResDrawable)d).sdt.clone().bytes();
        }
    }

    /**
     * The animation poses in force on a gob's composed body ({@link Composite#curposes} /
     * {@link Composite#curtposes}) — backs {@code gob:pose()}, and is the exact counterpart of
     * {@link #gobSdt} above: {@code null} for a gob whose {@link Drawable} is not a {@link Composite}
     * (a tree is a {@code ResDrawable}, and its drawing's state is its bytes instead), the empty array
     * for a composed body whose poses have not arrived yet. Between the two verbs, every gob's drawing
     * state has exactly one door and neither answers for the other's kind.
     *
     * <p><b>A one-shot wins while it plays.</b> {@code Composite.ctick} makes a transient set the
     * {@code Composited.Poses} that is actually drawn and puts the base back in its {@code done()}, so
     * "the pose it is in" is the transient whenever there is one — which is also what the eye sees.
     *
     * <p>Names are resolved <b>here</b>, per read, because the server sends pose resources as ids and an
     * {@code Indir} still loading throws rather than answering. A pose whose resource has not resolved is
     * left out rather than reported as a hole: the set is what it is drawing, and a name arriving a frame
     * later is the next read's.
     */
    public static String[] gobPose(Gob g) {
        if(g == null)
            return null;
        Drawable d;
        synchronized(g) {
            d = g.getattr(Drawable.class);
        }
        if(!(d instanceof Composite))
            return null;
        Composite c = (Composite)d;
        Collection<ResData> in = c.curtposes;      // one volatile read each: the pair may not be swapped
        if(in == null)                             //   under us mid-answer
            in = c.curposes;
        if(in == null)
            return new String[0];
        List<String> out = new ArrayList<String>(in.size());
        for(ResData dat : in) {
            String nm = resName(dat);
            if(nm != null)
                out.add(nm);
        }
        return out.toArray(new String[0]);
    }

    /** One pose's resource name, or {@code null} while its {@code Indir} is still loading. */
    private static String resName(ResData dat) {
        try {
            Resource r = ((dat == null) || (dat.res == null)) ? null : dat.res.get();
            return (r == null) ? null : r.name;
        } catch(RuntimeException e) {   // Loading, and a resource that never resolves
            return null;
        }
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
     * addon: install a tileset in a map cache under an id that cache chose for itself (spec
     * {@code 068-remembered-ground}) — the door to {@link MCache}'s own {@code settileset}, whose
     * {@code sets} array and {@code cktileid} are private to that class.
     *
     * <p>A live tile id is an index the <b>server</b> assigned for this session, and it arrives with
     * the grid that uses it. A map source filled from {@link MapFile} has none: the record carries
     * tileset resource names and versions, so such a source keeps a name&rarr;id map of its own and
     * registers each new one here. The {@code Indir} is the recorded {@link Resource.Saved} itself,
     * which is the same shape {@code sets[]} already holds.
     */
    public static void settileset(MCache mc, int id, Indir<Resource> res) {
        mc.settileset(id, res);
    }

    /**
     * addon: put a grid into a map cache that no server sent (spec {@code 068-remembered-ground}) —
     * the counterpart of {@code MCache.mapdata2} for ground read back out of {@link MapFile}.
     *
     * <p>A recorded grid carries exactly what a live one does — an {@code int[]} of tile indices and
     * a {@code float[]} of heights, both {@code cmaps}-sized — so the whole of the difference is
     * where the bytes came from. {@code tiles} must already be in the target cache's OWN tile ids
     * (see {@link #settileset}); {@code id} is the server's grid id the record kept, which is what
     * seeds the cut meshes' randomness so a place looks the same each time it is drawn.
     *
     * <p>The overlay arrays are empty rather than {@code null}: {@code MCache.Grid.getol} walks
     * {@code ols.length} unguarded.
     *
     * <p><b>The neighbours are deliberately not invalidated</b>, which is where this parts company
     * with {@code Grid.fill}. {@code Cut.invalidate} calls {@code Deferred.rebuild}, which schedules
     * a build whether or not that cut was ever built — so invalidating around each arrival meshes
     * the edge cuts of eight grids nobody asked to draw, and {@code MapMesh}'s transition pass reads
     * one tile across the grid border, which is a {@code getgrid} miss and therefore a
     * {@code request} on a cache that must put nothing on the wire. Nor is it needed: that same
     * cross-border read means an edge cut whose neighbour grid is absent throws {@code LoadingMap},
     * and {@code Defer.Future.run} catches {@code Loading} into {@code resched} rather than
     * completing. A cut can only finish with every grid it read present, so there is no stale edge
     * for an invalidation to repair — the caller fills a margin beyond what it draws instead.
     */
    @SuppressWarnings("unchecked")
    public static void putgrid(MCache mc, Coord gc, long id, int[] tiles, float[] z) {
        MCache.Grid g = mc.new Grid(gc);
        System.arraycopy(tiles, 0, g.tiles, 0, g.tiles.length);
        System.arraycopy(z, 0, g.z, 0, g.z.length);
        g.id = id;
        g.seq = 0;
        g.ols = new Indir[0];
        g.ol = new boolean[0][];
        synchronized(mc.grids) {
            MCache.Grid prev = mc.grids.put(gc, g);
            if(prev != null)
                prev.dispose();
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
     * The <b>interception seam</b> (spec {@code 061-editing-native-windows}) — asked by a control at the site
     * where the client itself receives an input, immediately before it runs its own method: <i>does an AddOn
     * hold this capability key on this widget, and may I proceed?</i> {@code false} = an addon cancelled, leave
     * the action undone.
     *
     * <p>It goes at the INPUT site rather than at the overridable hook ({@code click()}, {@code changed()}):
     * those are what a subclass replaces, and a notification a subclass can skip is not a seam. {@code value}
     * is what the control is about to take, for the keys that carry one ({@code null} for an activation).
     *
     * <p>With no AddOn holding that key the whole call is one map lookup per loaded addon and allocates
     * nothing, so a stock client behaves exactly as before.
     */
    public static boolean activate(Widget wdg, String key, Object value) {
        return io.brodgar.addon.AddonManager.activate(wdg, key, value);
    }

    /**
     * The <b>reporting</b> half of {@link #activate} (spec {@code 061-editing-native-windows}) — asked by a
     * control that has already written the value it is announcing, so it answers nothing: a slider and a
     * scrollbar move {@code val} first and call their own hook afterwards, and a drag emits a stream of these.
     *
     * <p>Cancelling here would mean revert-and-repaint, which is a different verb, so the {@code ev} an AddOn
     * receives refuses both {@code preventDefault} and {@code resend} naming that the value has moved — rather
     * than a call that quietly does nothing. Same cost as {@link #activate} with nobody listening.
     */
    public static void report(Widget wdg, String key, Object value) {
        io.brodgar.addon.AddonManager.report(wdg, key, value);
    }

    /**
     * What an {@link ACheckBox} is <b>about to</b> hold, for the {@code "Changed"} half of the interception
     * seam (spec {@code 061-editing-native-windows}) — the value the client would write if it goes on, so
     * cancelling means <i>it did not happen</i> rather than <i>it happened and was undone</i>.
     *
     * <p>A {@link RadioGroup.RadioButton} carries its <b>row</b> and everything else the flipped state, and
     * it is one method because a {@code RadioButton} <i>is</i> a {@link CheckBox} and does not override
     * {@code gkeytype}: a keybound radio arrives at the {@link ACheckBox} seam too, so the dispatch has to be
     * on the widget or one key would mean two shapes on one widget depending on whether the player used the
     * mouse.
     */
    public static Object checkValue(ACheckBox box) {
        if(box instanceof RadioGroup.RadioButton)
            return(((RadioGroup.RadioButton)box).row());
        return(Boolean.valueOf(!box.state()));
    }

    /**
     * The <b>list family's</b> half of {@link #activate} (spec {@code 061-editing-native-windows}) — asked
     * where the client receives a row click ({@code SListWidget.ItemWidget.mousedown}) and where it receives a
     * click on empty space ({@code SListBox.unselect}), immediately before its own {@code change}.
     *
     * <p>Two things belong to the family rather than to the call site, so both live here: <b>who the key is
     * addressed to</b> — {@link SListWidget#slistowner}, since a dropdown's popup and a menu's inner list are
     * one level removed from the control an addon holds — and <b>which key it is</b>: a menu has no value to
     * report a change against, so a pick there is {@code "Selected"} and everywhere else {@code "Changed"}.
     * {@code list} is passed on as the widget {@code ev:resend()} acts on, so a replay runs the very
     * {@code change} this held back, virtually — and a dropdown's popup still closes itself.
     */
    public static boolean listActivate(SListWidget<?, ?> list, Object item) {
        Widget owner = list.slistowner();
        String key = (owner instanceof SListMenu) ? "Selected" : "Changed";
        return(io.brodgar.addon.AddonManager.activate(owner, list, key, item));
    }

    /**
     * {@code ev:resend()}'s list arm — the {@code change} the seam above held back, called on the list the
     * click went through so a subclass's own override (a popup closing itself, a menu firing its choice) is
     * what runs. The raw call is here rather than in the bridge because the row type is the list's own.
     */
    @SuppressWarnings("unchecked")
    public static void listChange(SListWidget<?, ?> list, Object item) {
        ((SListWidget<Object, ?>)list).change(item);
    }

    /**
     * {@code ev:resend()}'s grid arm — {@code GridList.itemclick} on the selecting button, which is the whole
     * of what the {@code "Cell"} seam holds back (a {@code null} item is the click-away, and that method
     * routes it to {@code change(null)} itself). It lives here because {@code itemclick} is {@code protected}:
     * package access is what the bridge does not have.
     */
    @SuppressWarnings("unchecked")
    public static void gridClick(GridList<?> grid, Object item) {
        ((GridList<Object>)grid).itemclick(item, 1);
    }

    /**
     * Is {@code item} one of {@code list}'s own rows? (spec {@code 061-editing-native-windows}) — asked
     * before {@code widget:value(v)} drives one of the client's own lists, so a row that is not in it is a
     * refusal rather than a selection the list cannot draw. Identity, because that is what a row IS to an
     * addon: the opaque value it was handed back.
     *
     * <p>It lives here because {@code items()} is {@code protected} — package access is what the bridge does
     * not have. A list that cannot answer right now (a model still {@code Loading}) lets the drive through:
     * "I could not tell" must not read as "no".
     */
    public static boolean listHas(SListWidget<?, ?> list, Object item) {
	if(item == null)
	    return(false);
	try {
	    for(Object i : list.items()) {
		if(i == item)
		    return(true);
	    }
	} catch(RuntimeException e) {
	    return(true);
	}
	return(false);
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

    /**
     * The <b>re-layout seam</b> (spec {@code 062-drag-handles}) — asked at the end of {@link GameUI#resize},
     * which re-places {@code chat}, {@code beltwdg}, {@code prog} and the map <i>unconditionally</i> on every
     * screen resize: put back every place an AddOn (or, through one of its handles, the user) has named on a
     * child of that widget.
     *
     * <p>It is the last line of the method, so it overwrites the client's own placement rather than the
     * reverse, and it is idempotent — a widget already where the addon layer says gets no write at all — so
     * a resize it makes cannot come back round through this seam.
     *
     * <p>With no AddOn laying anything out the whole call is one {@code volatile} read and allocates nothing,
     * so a stock client resizes exactly as before.
     */
    public static void relayout(Widget parent) {
        io.brodgar.addon.AddonManager.relayout(parent);
    }
}
