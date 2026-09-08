package io.brodgar.addon;

import haven.Widget;

import org.luaj.vm2.LuaValue;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A <b>selector subscription</b> (spec {@code 030-ui-selectors}, task 030.2) — the Java half of
 * {@code s:ui():on(selector, "Added"|"Removed", fn)}, the client's <b>discovery primitive</b>. It replaces
 * {@code hafen.ui.onWidgetCreate} and its {@code desc} descriptor, both hard cut: an addon no longer describes the
 * widget it is waiting for in a second vocabulary (a server type string, a placement string, a parent class name)
 * — it names it with the same {@link Selector} it would use for a lookup, and the callback receives the same
 * interned Widget entity {@code hafen.ui(sel)} would hand back.
 *
 * <p><b>One subscription carries exactly one event.</b> Both are about the widget's presence in the TREE, not its
 * visibility: {@code "Added"} fires when a matching widget is placed (or is already there — see the scan below),
 * {@code "Removed"} when one that had matched leaves the tree. A window the client merely hides (the inventory's
 * Tab toggle) never left, so it fires neither; that is a property of the tree, and the honest answer.
 *
 * <p><b>Every subscription tracks what it matched</b> ({@link #matched}), whichever event it carries. For
 * {@code "Removed"} that set IS the question. For {@code "Added"} it is the dedup: a candidate carrying a
 * {@code [title=]}/{@code [res=]} refiner is re-checked after placement (a {@code .res} window can receive its
 * caption a tick late), from two triggers since 049.3 — the placement-scoped list and the caption seam's walk of
 * the renamed window's subtree — and the tracked set is what keeps a widget both reach from firing twice. The
 * subtree walk is what a <b>chain</b> needs: {@code window[title=Cupboard] inventory} names the grid, so the
 * caption that decides it lands on the grid's <i>ancestor</i>. The set is pruned at the same removal seam that fires
 * {@code "Removed"} (event-driven since 042.9), so it holds only live widgets — a dead one is dropped the moment
 * it is removed, never held as a pin.
 *
 * <p><b>And a widget that dies as a DESCENDANT is dropped too</b> (128.2), on the disposal seam
 * ({@link UiApi#retireSelectorMatches}, from {@code AddonManager}'s disposal drain) — <b>silently</b>. The
 * removal seam reaches only what runs {@code Widget.remove()}, and {@code docs/client/widgets.md} records that
 * {@code rdispose} recurses {@code dispose()} alone: every client-minted widget inside a closing window used to
 * stay in this map for the rest of the session. The retirement fires nothing, because firing {@code "Removed"}
 * there would mint one announcement per widget in that window; so this map holds only live widgets, and the
 * event still says exactly what it said.
 *
 * <p>The recorded value is the server widget id captured when the widget matched ({@code -1} for a client-only
 * one), because the death test is the same <b>two-branch</b> guard the restore list and the container watches use
 * — by id when server-bound, by tree reachability otherwise — and a matching widget can be either. For a
 * {@link haven.Window} the id branch is not merely an optimisation but the only <i>prompt</i> answer:
 * {@code UI.destroy} unbinds the id and then calls {@code reqdestroy()}, which {@code Window} overrides to start a
 * <b>fade-out</b> rather than to leave the tree — so a closing window stays reachable, and readable, for the whole
 * animation. {@code "Removed"} therefore fires when the widget stops being <i>real</i>, not when it stops being
 * <i>drawn</i>, and the entity it hands over may still answer its reads. Match it, do not rely on reading it.
 *
 * <p><b>Registration SCANS the live tree once</b> (in {@link UiApi}), so a subscription made while the target is
 * already open still sees it. That is what makes this a discovery primitive rather than a creation feed:
 * {@code onWidgetCreate} could not fire for a widget that already existed, which is exactly the {@code :reload}
 * case an addon hits every time it is edited.
 *
 * <p><b>The selector is a CHAIN</b> since 049. Nothing here changed for it: {@link Selector#matchesStructure} and
 * {@link Selector#late} already answer over every step ({@code late()} is the OR — one step's caption is enough to
 * make the whole selector worth re-checking), and both events remain about the widget the LAST step names. What did
 * change is which widget the deciding attribute sits on; see the dedup paragraph above.
 *
 * <p><b>Threading.</b> Every {@code "Added"} and every {@code "Removed"} is matched under its tree's monitor
 * and fired outside it, on the layer's step, holding none — {@code UiApi.offerEntered}, {@code UiApi.offer} and
 * the registration scan alike. All of them go through {@link AddonManager#callLua}, which is watchdog-armed,
 * error-isolated, CPU-accounted, and holds this addon's own lock, so one addon's handlers run one at a time
 * whichever seam raised them.
 * The addon only ever sees the {@link LuaSub} its registration handed back — this object hangs off that sub's
 * {@link LuaSub#tag} (086.1), and {@code sub:off()} is what ends it; the bridge owns the subscription and drops
 * it on reload/disable (principle P2), and {@link #alive} makes a dispatch that races teardown a no-op.
 */
final class LuaSelectorWatch {
    /** The two events; a subscription carries exactly one (subscribe twice to watch both). */
    static final int ADDED = 0, REMOVED = 1;

    final Addon owner;
    /**
     * <b>The tree this subscription watches</b> (073.2) — the session's {@code UI}, recorded once at
     * registration and never re-derived. A subscription is not about a widget, so nothing else on it can say
     * which session it belongs to; without this the only answer available at teardown would be
     * {@code AddonManager.screen()}, which by then is the session the anchor has already moved TO, and the
     * subscription would be dropped from that session's list while staying in the one it was made against.
     *
     * <p>{@code null} when there was no session to register in (the login screen's {@code :lua} console) — the
     * subscription exists, is owned and can be removed, and never fires, which is exactly what it did before.
     */
    final haven.UI ui;
    /** The selector, parsed ONCE at subscription time — never per widget, never per tick. */
    final Selector sel;
    /** {@link #ADDED} or {@link #REMOVED}. */
    final int event;
    /** The Lua handler {@code fn(widget)}. */
    final LuaValue fn;
    boolean alive = true;
    /**
     * The widgets currently matching this selector &rarr; the server widget id captured when they matched
     * ({@code -1} = client-only). Identity-keyed ({@link Widget} does not override {@code equals}). See the class
     * comment for why an {@code "Added"} subscription keeps it too.
     *
     * <p><b>Concurrent</b> (audit2 B06): the seams that record a match hold the tree's monitor, and the three
     * that drop one do not — a {@code "Removed"} dispatch runs on the step, and {@code sub:off()} and the
     * teardown run wherever the addon's Lua did. Nothing walks it (the order it kept bought nothing: the tree
     * order events fire in is the walk's, not this map's), so every use is one atomic map operation.
     */
    // retired: UiApi.retireSelectorMatches -- strong keys, and the removal seam's own dispatch reaches only a
    //   widget that was removed; one that died as a descendant is retired here, silently.
    final Map<Widget, Integer> matched = new ConcurrentHashMap<Widget, Integer>();

    LuaSelectorWatch(Addon owner, haven.UI ui, Selector sel, int event, LuaValue fn) {
        this.owner = owner;
        this.ui = ui;
        this.sel = sel;
        this.event = event;
        this.fn = fn;
    }

    /** {@code "Added"}/{@code "Removed"} &rarr; {@link #ADDED}/{@link #REMOVED}, or {@code -1}. */
    static int eventCode(String s) {
        if("Added".equals(s))
            return ADDED;
        if("Removed".equals(s))
            return REMOVED;
        return -1;
    }
}
