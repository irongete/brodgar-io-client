package io.brodgar.addon;

import haven.Widget;

import org.luaj.vm2.LuaValue;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A <b>selector subscription</b> (spec {@code 030-ui-selectors}, task 030.2) — the Java half of
 * {@code hafen.ui.on(selector, "appear"|"disappear", fn)}, the client's <b>discovery primitive</b>. It replaces
 * {@code hafen.ui.onWidgetCreate} and its {@code desc} descriptor, both hard cut: an addon no longer describes the
 * widget it is waiting for in a second vocabulary (a server type string, a placement string, a parent class name)
 * — it names it with the same {@link Selector} it would use for a lookup, and the callback receives the same
 * interned Widget entity {@code hafen.ui(sel)} would hand back.
 *
 * <p><b>One subscription carries exactly one event.</b> Both are about the widget's presence in the TREE, not its
 * visibility: {@code appear} fires when a matching widget is placed (or is already there — see the scan below),
 * {@code disappear} when one that had matched leaves the tree. A window the client merely hides (the inventory's
 * Tab toggle) never left, so it fires neither; that is a property of the tree, and the honest answer.
 *
 * <p><b>Every subscription tracks what it matched</b> ({@link #matched}), whichever event it carries. For
 * {@code disappear} that set IS the question. For {@code appear} it is the dedup: a candidate carrying a
 * {@code [title=]}/{@code [res=]} refiner is re-checked after placement (a {@code .res} window can receive its
 * caption a tick late), from two triggers since 049.3 — the placement-scoped list and the caption seam's walk of
 * the renamed window's subtree — and the tracked set is what keeps a widget both reach from firing twice. The
 * subtree walk is what a <b>chain</b> needs: {@code window[title=Cupboard] inventory} names the grid, so the
 * caption that decides it lands on the grid's <i>ancestor</i>. The set is pruned at the same removal seam that fires
 * {@code disappear} (event-driven since 042.9), so it holds only live widgets — a dead one is dropped the moment
 * it is removed, never held as a pin.
 *
 * <p>The recorded value is the server widget id captured when the widget matched ({@code -1} for a client-only
 * one), because the death test is the same <b>two-branch</b> guard the restore list and the container watches use
 * — by id when server-bound, by tree reachability otherwise — and a matching widget can be either. For a
 * {@link haven.Window} the id branch is not merely an optimisation but the only <i>prompt</i> answer:
 * {@code UI.destroy} unbinds the id and then calls {@code reqdestroy()}, which {@code Window} overrides to start a
 * <b>fade-out</b> rather than to leave the tree — so a closing window stays reachable, and readable, for the whole
 * animation. {@code disappear} therefore fires when the widget stops being <i>real</i>, not when it stops being
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
 * <p><b>Threading.</b> The {@code appear} raised at placement runs inside {@code AddWidget.run}'s
 * {@code synchronized(ui)} block (on a Loader thread, under the monitor the tick and draw hold), so its Lua never
 * races other Lua — the same discipline the observers had. The re-check and {@code disappear} run in the tick, on
 * the UI thread. Both go through {@link AddonManager#callLua} (watchdog-armed, error-isolated, CPU-accounted).
 * The addon only ever sees an opaque {@code :remove()} handle; the bridge owns the subscription and drops it on
 * reload/disable (principle P2), and {@link #alive} makes a dispatch that races teardown a no-op.
 */
final class LuaSelectorWatch {
    /** The two events; a subscription carries exactly one (subscribe twice to watch both). */
    static final int APPEAR = 0, DISAPPEAR = 1;

    final Addon owner;
    /** The selector, parsed ONCE at subscription time — never per widget, never per tick. */
    final Selector sel;
    /** {@link #APPEAR} or {@link #DISAPPEAR}. */
    final int event;
    /** The Lua handler {@code fn(widget)}. */
    final LuaValue fn;
    boolean alive = true;
    /**
     * The widgets currently matching this selector &rarr; the server widget id captured when they matched
     * ({@code -1} = client-only). Identity-keyed ({@link Widget} does not override {@code equals}) and insertion-
     * ordered, so events fire in tree order. See the class comment for why an {@code appear} subscription keeps it
     * too.
     */
    final Map<Widget, Integer> matched = new LinkedHashMap<Widget, Integer>();

    LuaSelectorWatch(Addon owner, Selector sel, int event, LuaValue fn) {
        this.owner = owner;
        this.sel = sel;
        this.event = event;
        this.fn = fn;
    }

    /** {@code "appear"}/{@code "disappear"} &rarr; {@link #APPEAR}/{@link #DISAPPEAR}, or {@code -1}. */
    static int eventCode(String s) {
        if("appear".equals(s))
            return APPEAR;
        if("disappear".equals(s))
            return DISAPPEAR;
        return -1;
    }
}
