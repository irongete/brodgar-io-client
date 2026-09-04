package io.brodgar.addon;

import haven.Button;
import haven.Coord;
import haven.GameUI;
import haven.Gob;
import haven.GOut;
import haven.Indir;
import haven.Label;
import haven.Loading;
import haven.Resource;
import haven.Text;
import haven.TextEntry;
import haven.UI;
import haven.WItem;
import haven.Widget;
import haven.Window;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.VarArgFunction;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;


import static io.brodgar.addon.AddonManager.*;

/**
 * The custom-UI + widget-introspection subsystem:
 * {@code hafen.ui} — custom windows/widgets (2a), HUD + world-space gob overlays (2b), selector lookups and
 * selector events (030), the window-toggle seam + the {@code widget:replace(view)} substitution (031/032), and the
 * read-only widget-tree walk + hit-testing (W1/W2 node API). Owns the subscription registries + overlay paint
 * state. The widget-placement seam {@code onWidgetPlaced} (called from {@code haven.UI}) stays a facade in
 * {@link AddonManager} and delegates here — placement also drives {@link #dispatchWidgetSubsPlaced} (042.7) and
 * {@link #offerPlaced}'s selector matching (030.2); the tick still drives {@link #anyHudOverlays}, but {@code
 * WidgetSubs}'s {@code ItemAdded}/{@code ItemRemoved}/{@code Destroy} keys moved onto the placement/removal seams
 * ({@link #dispatchWidgetSubsPlaced}/{@link #dispatchWidgetSubsRemoved}, 042.7), so has the {@code
 * widget:replace(view)} substitution's own death test ({@link #dispatchReplacedRemoved}, 042.8), and so has the
 * selector subscriptions' late-refiner re-check ({@link #markCaptionChanged}/{@link #drainSelectorCaptionCheck},
 * 042.9) and its {@code "Removed"} firing ({@link #dispatchSelectorRemoved}, 042.9); the per-gob overlay attrib
 * {@code LuaGobOverlay.draw} calls {@link #paintGobOverlays}. Shared gob-read/engine helpers stay in
 * {@link AddonManager}. Not instantiable.
 */
final class UiApi {
    private UiApi() {}

    // ===== registries + overlay paint state =====
    // -- HUD overlays (spec 07 / Phase 2b) ----------------------------------------------------------------
    // They paint ON TOP of the HUD via a one-shot UI.drawafter re-registered each tick (drawafter is cleared
    // every UI.draw; tick precedes draw in the frame loop, so the afterdraw runs this same frame after
    // root.draw — above GameUI). On the UI thread (paint runs inside UI.draw).
    //   Their world-space sibling is GONE from here (038.1): hafen.ui.gobOverlay's filter list and its
    // throttled sweep are deleted, the state lives ON THE GOB (LuaGobOverlay is now the store), and the only
    // thing left in this file is paintGobOverlays — which no longer matches anything.
    private static final LuaGOut hudGout = new LuaGOut();                 // shared g wrapper for the HUD pass
    static final UI.AfterDraw hudAfterDraw = new UI.AfterDraw() { // one-shot afterdraw, re-queued each tick
        public void draw(GOut g) { paintHudOverlays(g); }
    };

    // -- selector subscriptions (030.2): s:ui():on(sel, "Added"|"Removed", fn) — the discovery primitive that
    // replaced onWidgetCreate. A FLAT global list (a subscription watches the whole tree, not one keyed target),
    // consulted at the widget-entry seam's drain (112.3 — it was the placement seam until then) and at the
    // removal seam (dispatchSelectorRemoved, 042.9, event-driven — no more per-tick poll); globally empty = a
    // near-zero fast path, so a client with no subscription pays one isEmpty() per widget entry/removal
    // (offerEntered's own, since 112.5: the entry queue itself is filled for the tree adapters too). `pending`
    // is the ENTRY-scoped re-check: a widget that matched a selector's structure (role/class, fixed for its
    // life) but not its [title=]/[res=] refiner may simply not have its caption or its resource yet, so it is
    // re-offered — since 042.9, whenever a window's caption changes
    // rather than on a fixed countdown — for up to RECHECK_TICKS re-checks and then dropped. Since 049.3 it is no
    // longer the only path: the caption seam records the WINDOW (`capChanged`) and the drain re-offers that
    // window's whole SUBTREE, because with the descendant combinator a [title=] sits on an ancestor step and what
    // starts matching is a widget below it, possibly one placed long ago. `pending` stays because a [res=] refiner
    // resolves with no event of its own and that sweep is all it has. Both end in the same offer(), so a widget
    // reachable through both fires exactly once (`matched` is the dedup). Cost scales with widget creation and
    // caption changes, not with frames (a per-tick diff of the whole tree was the discarded alternative). Owned
    // copies live on each Addon for teardown; removing the last subscription (or tearing an addon down) clears
    // both queues, or a stalled entry would outlive every listener with nothing left to drain it. Session-scoped.
    // THREADING (112): every reader and writer of these two lists runs on a STEP holding no tree monitor — the
    // entry drain on the layer's, the removal seam and the caption re-check on the session's — and each of them
    // takes the tree's monitor itself, for the walk and the match alone, giving it up before any Lua runs
    // (offerEntered, offer). The taps that feed them hold whatever their caller held and only append: the entry
    // tap under add0's synchronized(ui), the caption seam (Window.chcap) under nothing at all, which is why it
    // records a Widget (markCaptionChanged) rather than walking the tree or calling Lua inline (P5). So the tree
    // monitor still guards every actual reader/writer here and each subscription's `matched` map needs no lock
    // of its own.
    // 073.2: and BOTH LISTS ARE ONE SESSION'S. They hold widgets of a tree, so they are
    // {@code SessionState.selectorWatches} / {@code .selectorPending}, reached with the ui of the widget the
    // seam was handed — never {@link AddonManager#screen()}, which answers the session on screen and is a
    // different one at every seam here: the entry tap runs on whichever thread placed the widget — a Loader
    // thread of whichever session sent the message, as often as the UI thread — and the teardown below runs
    // from {@code init} once the anchor has ALREADY moved to the session being switched to. A subscription
    // records the tree it was made against ({@link LuaSelectorWatch#ui}), which is what lets it be dropped from
    // that tree's list rather than from the list of whichever session happens to hold the screen when the
    // addon is torn down.
    private static final int RECHECK_TICKS =              // how long a late caption/res has to land (in ticks)
        Integer.getInteger("haven.addon.selrecheck", 20).intValue();

    /** One recently-placed widget still awaiting a late {@code [title=]}/{@code [res=]} (030.2's bounded re-check). */
    static final class PendingMatch {
        final Widget wdg;
        final int id;                 // server widget id, or -1 (client-only) — the two-branch death test
        int ticks = RECHECK_TICKS;

        PendingMatch(Widget wdg, int id) {
            this.wdg = wdg;
            this.id = id;
        }
    }

    // -- WidgetSubs interest registrations (041.4, event-driven since 042.7): widget:on("ItemAdded"/
    // "ItemRemoved"/"Removed", fn) can only be SEEN at the moment/removal/placement seams (an item is a Widget
    // create/cdestroy, not a uimsg; a widget's death has no engine event of its own), so every WidgetSubs
    // currently subscribed to one of the three lives in this FLAT list — offered every placement and removal
    // (dispatchWidgetSubsPlaced/Removed) instead of diffed every tick — hasSub-GATED by construction (WidgetSubs
    // registers/unregisters itself, see its Idle hook), so a widget nobody subscribed to costs nothing and an
    // idle client pays one isEmpty(). Owned copies live on each Addon's widgetSubs map for teardown.
    // 073.2: ONE SESSION'S — the list holds records of widgets of one tree, so it is
    // {@code SessionState.widgetSubsWatching}, reached with the ui of the very widget each record was made
    // on ({@link WidgetSubs#ui}). Still cleared per init; the tree is rebuilt.

    /** {@link WidgetSubs#on}: the first tree-key ({@code ItemAdded}/{@code ItemRemoved}/{@code Destroy})
     *  subscription on a widget joins its own tree's flat watch list. */
    static void registerInterest(WidgetSubs s) {
        SessionState st = state(s.ui());
        if(st != null)
            st.widgetSubsWatching.add(s);
    }

    /** {@link Subs.Idle}, or {@link WidgetSubs#offerRemoved} on the watched widget's own death. */
    static void unregisterInterest(WidgetSubs s) {
        SessionState st = state(s.ui());
        if(st != null)
            st.widgetSubsWatching.remove(s);
    }

    /**
     * The widget-placement seam's offer to every watching {@link WidgetSubs} (042.7): a new {@code WItem} may
     * have just entered one of their subtrees. Fast-paths out when nobody is watching, the normal case.
     */
    static void dispatchWidgetSubsPlaced(Widget w) {
        SessionState st = state(w.ui);        // 073.2: the tree the widget was placed into, and no other
        if((st == null) || st.widgetSubsWatching.isEmpty())
            return;
        for(WidgetSubs s : st.widgetSubsWatching) {   // copy-on-write: a firing handler may (un)subscribe here
            try {
                s.offerPlaced(w);
            } catch(RuntimeException e) {
                log("widget-subs placed error: " + e);
            }
        }
    }

    /**
     * The widget-removal seam's offer to every watching {@link WidgetSubs} (042.7): either {@code w} IS the
     * widget one of them is watching (fires {@code Destroy}) or it may be a {@code WItem} that just left one of
     * their subtrees. Fast-paths out when nobody is watching, the normal case.
     */
    static void dispatchWidgetSubsRemoved(SessionState st, Widget w) {
        if(st.widgetSubsWatching.isEmpty())
            return;
        for(WidgetSubs s : st.widgetSubsWatching) {   // copy-on-write: a firing handler may (un)subscribe here
            try {
                s.offerRemoved(w);
            } catch(RuntimeException e) {
                log("widget-subs removed error: " + e);
            }
        }
    }

    /**
     * Once per tick ({@code AddonManager.tick}, right after {@code drainRemovedWidgets}): let every watching
     * {@link WidgetSubs} diff now if a placement or removal touched it since the last flush (064.3) — see
     * {@link WidgetSubs#markDirty} for why the diff waits for the tick boundary rather than running inline at
     * {@code offerPlaced}/{@code offerRemoved}. Fast-paths out when nobody is watching.
     */
    static void flushItemWatchers(SessionState st) {
        if(st.widgetSubsWatching.isEmpty())
            return;
        for(WidgetSubs s : st.widgetSubsWatching) {   // copy-on-write: a firing handler may (un)subscribe here
            try {
                s.flush();
            } catch(RuntimeException e) {
                log("widget-subs flush error: " + e);
            }
        }
    }

    // ===== the widget-placement seam (the body behind AddonManager.onWidgetPlaced) =====
    // TWO consumers: 036.2's layout rules and 042.7's dispatchWidgetSubsPlaced above. Neither runs Lua, which
    // is what keeps this seam legal where it sits — inside AddWidget.run's synchronized(ui), on a Loader
    // thread. The 030.2 selector subscriptions are NOT here any more (112.3): they are the entry seam's, and
    // the entry seam fires from the step.
    static void onWidgetPlaced(int id, Widget wdg) {
        // 073.2: whose tree the widget entered is the widget's own question — this seam runs inside
        // AddWidget.run on a Loader thread, which is the thread of the session that sent the message and
        // not of the one on screen, so screen() here could offer another session's placement to these lists.
        // 112.3: the selector offer is GONE from here. It was already a no-op — every widget this seam sees
        // reached its parent through Widget.add and so passed the entry seam a few instructions earlier, where
        // `matched` recorded it — and with that seam deferred to the step, an offer left here would be the one
        // that FIRES, on the placing thread, under the monitor this task takes off the handler. The entry
        // seam's drain is the one door.
        if(Sheet.anyLayout)               // 036.2: a layout rule reaches a window the moment it opens, not a frame
            Layout.placed(wdg, id);       //   later — and never at the draw (035.1's chdeco lesson)
        dispatchWidgetSubsPlaced(wdg);
    }

    /**
     * The <b>widget-entry seam</b>'s tap (behind {@link AddonManager#onWidgetEntered}, called from
     * {@code Widget.add0}) — the selector subscriptions' real feed, and the mirror of the removal seam. It
     * <b>records the widget and nothing else</b> (112.3): {@code add0} holds the tree's monitor, so the walk
     * and the handlers are {@link #dispatchEntered}'s, on the layer's step.
     *
     * <p><b>Two faults it closes, and they are the same fault seen twice.</b> The placement seam above is the
     * server's message handler: it never sees a widget the client mints for itself (every {@code WItem}, the
     * {@code ItemDrag} under the cursor), and it announces a widget the instant its <i>parent</i> takes it,
     * which for a subtree built before it is hung (a chest's grid, given to a window that is not up yet) is
     * before the widget is in any tree at all. Here the question is asked where the answer is already true:
     * a widget whose chain does not reach the root announces nothing and is announced later, when the ancestor
     * that was missing enters — because that ancestor passes this same seam. There is nothing to re-check.
     *
     * <p><b>Every widget the client builds passes here, and every one of them is recorded</b> (112.5). "Is
     * anybody watching with a selector" is not the whole question: the {@link CharApi.TreeAdapter}s read this
     * drain too, and they are nine per session, built with the state and never absent — so a client with no
     * {@code s:ui():on} at all still has a consumer, and a tap that fast-pathed on the selector list would
     * stop its buffs and meters arriving. What is left to fast-path on is the state itself: no state, or one
     * whose pump is not running, and there is nothing to drain into. The bound is the drain's, not the tap's
     * ({@code AddonManager.drainEnteredWidgets} takes one step's worth), and the per-widget cost on the other
     * side is a subtree walk and an {@code instanceof} per adapter.
     */
    static void enqueueEntered(Widget wdg) {
        UI u = wdg.ui;
        if((u == null) || (u.root == null))
            return;                            // not in any tree yet — it will pass here again when it is
        SessionState st = AddonManager.queueState(u);   // 073.2: the tree the widget entered, and no other
        if(st == null)
            return;
        if(!wdg.hasparent(u.root))
            return;                            // hung under something that is not up: announced with it, later
        st.enteredWidgets.add(wdg);
    }

    /**
     * The widget-entry seam's <b>dispatch</b>, from {@code AddonManager.drainEnteredWidgets} on the layer's
     * step (112.3) — the walk and the offer, with no tree monitor held.
     *
     * <p><b>The whole subtree is offered, not just the widget.</b> What was built while its ancestor was
     * detached said nothing at the time, so the entry of the ancestor is the moment all of it becomes true.
     * {@link LuaSelectorWatch#matched} is the dedup that keeps the ones already announced from firing twice.
     *
     * <p><b>The re-test is here rather than at the tap</b>, and that is the promise this seam makes: the widget
     * handed to a handler is in the tree at the moment it is handed over. A step later than the placement, that
     * is a question worth asking again — the widget may have been taken away between the two — and the handler
     * this loop calls may itself close a window that is further down the list.
     *
     * <p><b>The tree is READ under its own monitor and the handler runs outside it</b>, which is the whole
     * shape of this task. A selector walks parents and a subtree walk follows {@code child}/{@code next}, and a
     * Loader thread re-links both under {@code synchronized(ui)}; but a handler holding that monitor is the
     * deadlock. Taking one tree's monitor here is exactly what the step is allowed to do — it arrives holding
     * none — and giving it up before {@link #offerEntered} calls Lua is what keeps it to one.
     *
     * <p><b>Two consumers, in the order the placement seam used to run them</b> (112.5): the 030.2 selector
     * subscriptions, then {@link CharApi#dispatchPlaced}'s tree adapters. The adapters were the last Lua left
     * on {@code AddWidget.run}'s thread, and moving them here is what makes {@code BuffAdded} and
     * {@code MeterAdded} handlers as free as an {@code Added} handler already is — and offers the adapters
     * every widget that enters, not only the ones the server places.
     */
    static void dispatchEntered(SessionState st, Widget wdg) {
        UI u = wdg.ui;
        if((u == null) || (u.root == null))
            return;
        if(AddonManager.state(u) != st)
            return;                            // re-homed into another tree since the tap: not this queue's
        List<Widget> entered = new ArrayList<Widget>();
        synchronized(LuaWidget.monitorOf(u)) {
            if(!wdg.hasparent(u.root))
                return;                        // gone, or hung under something that is not up
            collectSubtree(wdg, entered);
        }
        for(Widget w : entered) {
            offerEntered(st, u, w);
            CharApi.dispatchPlaced(st, w);
        }
    }

    /**
     * One entered widget offered to every selector subscription: <b>matched under the tree's monitor, fired
     * outside it</b> (112.3). The two halves are split rather than folded into {@link #offerPlaced} because
     * {@code record} calls Lua inline, and Lua called from inside a tree's monitor is the whole defect.
     *
     * <p>Per widget rather than per subtree, so the re-test and the match are asked at the moment this widget
     * is handed over — a handler fired for an earlier one may have closed the window a later one sits in.
     *
     * <p>The empty-list fast path is here rather than at the tap (112.5): the queue is filled for the tree
     * adapters as well now, so "nobody is watching with a selector" is a question about this half alone.
     */
    private static void offerEntered(SessionState st, UI u, Widget wdg) {
        if(st.selectorWatches.isEmpty())
            return;
        List<LuaSelectorWatch> fire = null;
        synchronized(LuaWidget.monitorOf(u)) {
            if(!wdg.hasparent(u.root))
                return;
            int id = u.widgetid(wdg);
            boolean recheck = false;
            for(LuaSelectorWatch w : st.selectorWatches) {   // copy-on-write: a handler may subscribe here
                if(!w.alive || w.matched.containsKey(wdg))
                    continue;
                if(w.sel.matches(wdg)) {
                    w.matched.put(wdg, Integer.valueOf(id));   // record: both events track, only one fires
                    if(w.event == LuaSelectorWatch.ADDED) {
                        if(fire == null)
                            fire = new ArrayList<LuaSelectorWatch>();
                        fire.add(w);
                    }
                } else if(w.sel.late() && w.sel.matchesStructure(wdg)) {
                    recheck = true;
                }
            }
            if(recheck)
                st.selectorPending.add(new PendingMatch(wdg, id));
        }
        if(fire == null)
            return;
        for(LuaSelectorWatch w : fire)
            callLua(w.owner, Addon.C_WIDGET, w.fn, LuaWidget.of(w.owner, wdg));
    }

    /**
     * Build {@code hafen.ui} for {@code owner}. From installHafen.
     *
     * <p><b>The section, and the root that moved</b> (spec {@code 039-uniform-api} §2.1). {@code hafen.ui()} was
     * the ROOT widget — the no-argument form of a callable namespace — and is now the section object, so the tree's
     * top is {@code s:ui():root()} and every lookup is a colon verb on the section. The collision is why the
     * root could not simply stay: one expression cannot be both the namespace and a member of it.
     *
     * <p><b>Every verb is on the section now</b> (039.7). {@code skin} was the last one still a plain field on
     * the callable table — it did not merely move, it became a {@link LuaSheet} of {@link LuaRule}s — so the
     * transitional {@link Section#mount(LuaTable, String, LuaValue, String, LuaTable)} is gone with it and this
     * is the plain {@link Section#install} again.
     */
    static void installUi(LuaTable hafen, final Addon owner) {
        // hafen.ui.gobOverlay(filter, fn) is GONE (038.1, hard cut — it reads as plain nil). `overlay` is the
        // engine's own word for a thing attached to a gob, and this spent it on a screen-space painter that was
        // not one: a FILTER re-evaluated against every gob by a 5 Hz sweep, and once more per gob per frame.
        // The verb is on the thing now (D-044) — gob:overlay(key, spec) attaches, gob:overlay() reads back what
        // is attached (the game's own overlays included), and the state lives on the gob, so it dies with it.
        // "Every player gets a label" is a GobAdded handler plus a loop; that trade is the point.
        // 094 (A-113): the role collection is minted ONCE per addon, like every other section-held set, so
        // hafen.ui():role() == hafen.ui():role() and a per-frame read allocates nothing. The set is closed
        // and static, so its Source holds nothing either.
        final LuaValue roleColl = LuaCollection.create("hafen.ui():role()", new LuaCollection.Source() {
            public java.util.List<LuaValue> members() {
                return LuaRole.members(owner);
            }

            public String needle(LuaValue member) {
                return LuaRole.name(member);
            }

            /** A role IS its name, so a string filter is a substring test over it. */
            public boolean named() {
                return true;
            }

            public boolean addressable() {
                return true;
            }

            public LuaValue getMember(LuaValue key) {
                if(key.type() != LuaValue.TSTRING)
                    throw new LuaError("hafen.ui():role():get(name): a role is addressed by its name, a"
                        + " string (\"window\", \"window.title\"), got " + key.typename());
                return LuaRole.named(owner, key.tojstring());
            }

            /** The key is the role's own name. */
            public String keyName() {
                return "name";
            }
        }, null);
        LuaTable m = new LuaTable();
        // hafen.ui.adopt(id) is GONE (029.2). It only ever existed to get a readable handle on a native widget, and
        // it charged you a hidden window for the privilege. Now every widget IS an entity: s:ui():node(id) hands
        // you the same one WITHOUT hiding anything, and widget:visible(false) (which records the restore, see below) is the
        // separate, explicit act it always should have been.
        // hafen.ui.replace(type, opts, fn) is GONE too (032.2, hard cut — it reads as plain nil). It was the LAST
        // place that named a window a different way: its {id,type,place,caption,parentType} descriptor (D-024) was
        // a second vocabulary for "which window", and it was PRIVILEGED — only it could bind a view to a hidden
        // native window, so an addon doing the same by hand got a swallowed toggle and nothing driving it. Both
        // halves are ordinary API now: s:ui():on(sel, "Added", fn) does the WAITING (and fires for what is
        // already open, D-068), and widget:replace(view) does the REPLACING. The whole pattern is
        //     s:ui():on("inventory[title=Inventory]", "Added", function(w) w:replace(buildMyView(w)) end)
        // THE WIDGET ENTITY (spec 20 W1/W2, rebuilt on the ONE entity by 029-widget-oop) — what every door of
        // this section hands back, whether the widget is one the game placed or one you built. The doors onto
        // the CLIENT's own widgets are the session's, 078.2: s:ui():match(sel), :matchAll(sel), :root(), :node(id),
        // :inventory(), :equipment() — see {@link #ui}. The ones left here answer about YOUR layer or about the
        // SCREEN: :hit(x, y) hit-tests a point, and every constructor mints one of your own. All of them hand
        // back the SAME type: opaque, facade-safe
        // userdata (no raw haven.Widget crosses into Lua, P1/D-017), INTERNED per addon — so two lookups of one
        // live widget are the SAME value and `==` is the identity test (:same() is GONE, 029.1). Not an
        // owned-registry entry: it checks liveness per access, and once its widget leaves the tree it nulls its
        // reference (no pin, D-041), every read answers nil/empty and :exists() is false. The entity:
        //   :type()          -- class simple name (e.g. "Inventory", "Label", "Button")
        //   :role()          -- 030.1: what it IS in the selector vocabulary ("window"/"inventory"/"button"/…), or
        //                       nil when nothing classifies it (an honest no answer, never a guess)
        //   :res()           -- 030.1: its resource name ("gfx/hud/…"), the stable key [res=] matches, or nil
        //   :id()            -- server widget id (int), or nil if the widget is NOT server-bound (client-only)
        //   :children()      -- array of child Widget objects, in tree order (empty if a leaf)
        //   :parent()        -- parent Widget object, or nil at the root
        //   :position()      -- {x=,y=} position within the parent (widget-local DESIGN px -- not a Position, §2.7)
        //   :size()          -- {x=,y=}
        //   :visible()       -- boolean (and :visible(b) writes it, 039.5)
        //   :text()          -- best-effort text for text-bearing widgets (Label/Button/CheckBox/Window/TextEntry),
        //                       else nil. :text(s) WRITES a control's caption, on one you built and (061.5, as a
        //                       restoring level) on one of the client's; :text(nil) drops the level. A window's
        //                       caption is :title(s)/:title(nil), the same level through the verb that owns it.
        //   :exists()        -- is it still in the tree? (the one read that always answers)
        //   :info()          -- the snapshot escape hatch {type,role,res,id,pos,size,visible,text,owned}
        //   :walk(fn)        -- depth-first: fn(widget, depth); return false to PRUNE the subtree
        //   :match(selector) -- 049.2: the same search, scoped to THIS widget's subtree (inclusive) — the only
        //                       correct lookup inside an :on(sel, "Added", fn) callback, where the root-anchored
        //                       form would re-match whichever matching window it met first. Strict, like the
        //                       section's own :match. :matchAll(selector) is its array form (empty, never nil).
        //   :hit(coord)      -- W2: the DEEPEST Widget object under a {x=,y=} root-coord point WITHIN this subtree
        //   :rootPos()       -- W2: {x=,y=} its top-left in root coords (with :size() = a highlight box)
        //   :visible(b)      -- the ONE write that answers on a native widget (:show()/:hide() are CUT, 039.5 R6 --
        //                       a boolean property is a property). Hiding one you do not own records
        //                       the restore, so :reload/disable puts it back exactly as it was (029.2). It hides
        //                       EXACTLY what you point at (:replace does not — see below).
        //   :replace(view)   -- 032.1: put your own window in place of the native one, and inherit its toggle
        //                       (031, D-069). :replacement() reads the installed view or nil,
        //                       :replace(view) installs, :replace(nil) undoes. It hides the ENCLOSING WINDOW, not
        //                       the widget you point at, so replacing the inventory GRID takes the whole stock
        //                       window with it; the view is destroyed when the substitution ends (undo, teardown,
        //                       or the server destroying the window). Wait for the target with
        //                       s:ui():on(sel, "Added", fn) — that half is not part of the verb.
        //   :items()         -- 029.3: the Item snapshots inside this container (a relation, like :children()) —
        //                       an Inventory, an Equipory (each entry also carrying its `slot`), or any widget with
        //                       WItems under it. Read it with the window VISIBLE and interactive: nothing is hidden.
        //   :onItemAdded(fn) / :onItemRemoved(fn) -- fn(item) as items enter/leave this container (an item add is
        //                       a widget create, not a uimsg — seen at the placement/removal seams). Pass nil to
        //                       unsubscribe.
        //   :onDestroy(fn)   -- fn() once, when this widget leaves the tree. All three chain; subscribing is what
        //                       registers the widget as watched, so an unwatched widget costs nothing.
        // OWNED-ONLY (a widget YOUR addon created with hafen.ui():window() / hafen.ui():widget()); on a native widget
        // each raises a clear error, the geometry ones naming layout (feature E):
        //   :position(x, y)  -- move + chain (arity is the verb, the 018 shape; :move() is GONE)
        //   :size(w, h)      -- resize the content (+ repack a window's chrome) + chain
        //   :pack()          -- shrink the chrome to fit (no-op for a bare widget) + chain
        //   :destroy()       -- remove it and drop it from the addon's owned registry
        // Otherwise READ-ONLY: to ACT on the GAME, send from a server-bound widget itself — the protected
        // widget:send(msg, ...) (D-025) — no new action surface, no new gate (reading the tree is unprotected
        // client data).
        // hafen.ui():mouse() / :hit(x,y) — W2 hit-testing, the WoW /framestack enabler (spec 20 §W2, D-042).
        // Both speak DESIGN PIXELS (058.1), the same space :position()/:size() do — which is what makes
        // hafen.ui():hit(m:x(), m:y()) == m:over() true rather than nearly true on a scaled client.
        // mouse() is the POINTER ENTITY (041.5, LuaMouse) — :x()/:y() the cursor in root coords (public UI.mc),
        // :over() the deepest Widget under it, :shift()/:ctrl()/:alt() the live modifiers, :grab() a modal drag
        // capture — a per-addon singleton like s:player(), not a {x=,y=} table any more. hit(x,y) = the
        // DEEPEST Widget object under an ARBITRARY root-coord point, or nil (not absorbed into the mouse: it
        // takes any point). hit() MIRRORS the engine's own pointer dispatch (PointerEvent.propagation): it
        // walks children topmost-first, skips !visible(), descends by xlate (so SCROLL offsets are honoured) +
        // rect-intersect, and honours checkhit at the leaf (non-rectangular hit areas) — so it resolves EXACTLY the
        // widget a real click would hit (a naive pos..pos+size rect test is wrong under scroll / custom hit shapes).
        // Walk :parent() up from the hit for the full stack. Read-only, unprotected (client data, never reaches the
        // server); acting still goes through the protected widget:send(msg, ...) on a server-bound widget.
        m.set("mouse", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Section.self(self, "ui", "mouse");
                return LuaMouse.of(owner);
            }
        });
        m.set("hit", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "ui", "hit");
                LuaValue x = Args.required(a, 2, "hafen.ui():hit", "x");
                LuaValue y = Args.required(a, 3, "hafen.ui():hit", "y");
                return nodeHit(owner, x, y);
            }
        });
        // :tipAt(x, y) — 044.5: the Widget whose TOOLTIP the client would show at a root-coord point, or nil.
        // A sibling of :hit(x, y) and a different question: :hit answers what is under the point, this answers who
        // would speak for it, which is not always the same widget (a tooltip is inherited from whatever ancestor
        // carries one). The text is w:tooltip() on what comes back. It resolves the way the client itself does,
        // panels standing in the 3D world first — which is what makes it the read that says a tooltip on a
        // standing widget is the standing widget's, not the map's.
        // :scale() — 058.1: THE RUNNING DEVICE FACTOR, and a read only. Every coordinate and every size this
        // section takes or gives is a DESIGN pixel — the space the client's own art is authored in — so this is
        // not a unit and nothing in an addon multiplies by it: it is here to be PRINTED, in a log line or a
        // diagnostic, when you want to know what the client is running at.
        //   It is the factor IN FORCE, read off the live UI. hafen.client():options():interface():scale() is a
        // different number and stays where it is: that one is the persisted preference, defaulting to 1.0 where
        // the client's own default is derived from the display's density, unclamped by the display's maximum,
        // and it takes a restart. A write here is REFUSED naming that verb — the scale is the user's, set in
        // their Options, and a second door onto it would be the dual style the grammar removes.
        m.set("scale", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "ui", "scale");
                if(Args.passed(a, 2))
                    throw new LuaError("hafen.ui():scale() READS the UI scale in force and does not write it —"
                        + " hafen.client():options():interface():scale(v) is the setting, and it takes a client"
                        + " restart. Every coordinate here is a design pixel, so there is nothing to multiply.");
                return LuaValue.valueOf(Px.factor());
            }
        });
        // :measure(s, opts) — 110.4: THE BOX `g:text(s, x, y, opts)` WOULD OCCUPY, {w =, h =} in design pixels,
        // asked from anywhere and not only from inside a draw. It is not a second opinion about the box: it
        // rasterises through the very render, the very key and the very cache the draw does, so the measure and
        // the draw that follows it cost ONE rasterisation between them, and a laid-out line and a drawn one can
        // never disagree. `opts` is the SAME table g:text takes — { font = h, width = n } — so a wrap measured
        // here is the wrap drawn there; `color` is accepted and ignored, being a tint over the raster rather
        // than part of it. Without opts.font it measures the client's stock font: a widget's own :font(h)
        // default is not this section's to know, so pass the handle you set.
        //   Read-only and unprotected: it renders into this addon's own cache and touches nothing else.
        m.set("measure", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "ui", "measure");
                String s = Args.str(a, 2, "hafen.ui():measure", "str",
                                    "the string to lay out, markup and all").tojstring();
                LuaValue opts = a.arg(3);
                if(!opts.isnil() && !opts.istable())
                    throw new LuaError("hafen.ui():measure(str, opts): opts must be a table — the same one"
                        + " g:text takes, { font = h, width = n } — got " + opts.typename());
                FontHandle fh = opts.istable() ? FontHandle.resolve(opts.get("font")) : null;
                int w = LuaGOut.optWidth(opts, "hafen.ui():measure");
                return LuaWidget.whTable(Px.out(LuaGOut.measure(owner, s, fh, w)));
            }
        });
        m.set("tipAt", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "ui", "tipAt");
                LuaValue x = Args.required(a, 2, "hafen.ui():tipAt", "x");
                LuaValue y = Args.required(a, 3, "hafen.ui():tipAt", "y");
                if(!x.isnumber() || !y.isnumber())
                    throw new LuaError("hafen.ui():tipAt(x, y) expects numbers");
                UI u = screen();
                if((u == null) || (u.root == null))
                    return LuaValue.NIL;
                Widget from = LuaWidget.tipAt(u, Px.in(new Coord(x.toint(), y.toint())));   // design px, like :hit
                return (from == null) ? LuaValue.NIL : LuaWidget.of(owner, from);
            }
        });
        // :window() / :widget() — YOUR OWN surface, built BARE and configured by chained setters (039.6, §2.5).
        // The thirteen keys of the old opts table are verbs on the Widget the builder hands back, each with a
        // matching bare read: :title(s) :parent(w) :position(x,y) :size(w,h) :font(h) and the eight callbacks
        // :onDraw :onTick :onClick :onMouseUp :onMouseMove :onWheel :onDrop :onClose. Lua has no keyword
        // arguments — f{…} is only sugar for f({…}) — so the config table was never a style choice, and chaining
        // is the one other spelling of named arguments the language has.
        //   The constructor itself takes NOTHING. A window is born with the client's own defaults (200x140 at
        // 100,100, no caption) and, crucially, IS NOT IN THE TREE: it is added on the next tick (armPending),
        // so a widget halfway through its own configuration cannot be drawn, hit-tested or laid out. That is a
        // property of the shape rather than a rule to remember, and it is why the builder needs no "commit" verb.
        // role() -- 094 (A-113): THE SELECTOR LANGUAGE DESCRIBES ITSELF. A collection of every role the
        // client publishes, each a Role answering :name() and :selector(). w:role() reported a widget's role
        // and nothing enumerated what a role could BE: the vocabulary was a page, which is why the inspector
        // in addons/widgetstack exists partly to answer it at runtime.
        m.set("role", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "ui", "role");
                return roleColl;
            }
        });
        m.set("window", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "ui", "window");
                return newUi(owner, a, true);
            }
        });
        m.set("widget", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "ui", "widget");
                return newUi(owner, a, false);
            }
        });
        // :button() — 040.1, THE CONTROLS. The client already has them — Button, TextEntry, SListBox and fifteen
        // more, the same classes its own windows are built from — and until now nothing in the bridge so much as
        // named one: an addon that wanted a button drew a rectangle, drew a caption in it, read :onClick, and
        // reimplemented hover, press and focus outside the theme permanently. A real control is dressed by the
        // stylesheet for free, which is the whole payoff.
        //   A CONTROL IS A WIDGET, not a nineteenth entity: :position :size :parent :visible :destroy :style
        // :type :role and every selector answer on one with nothing written for them, and a verb answers where
        // it applies. What a button adds is :text(s) (its caption) and :onPress(fn) (it fired, and holds
        // nothing) — :onClick(fn) stays what it always was, the raw mouse event. Built bare and configured by
        // chained setters like every other builder, arming rule included, so it is findable from the first
        // instant and never drawn half-built.
        m.set("button", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "ui", "button");
                return Controls.button(owner, a);
            }
        });
        // :label() / :image() / :separator() / :progress() — 040.3, THE DISPLAY CONTROLS. Four more of the
        // client's own — a live-restyling text label, a static picture, a horizontal rule and a fill-fraction
        // bar — each built bare and configured by chained setters like every other builder here.
        //   :label() answers :text(s) (R2 completing the read every text-bearing widget already had) and
        // nothing else — the plan expected an :image(h) completion to haven.ILabel the way a button completes
        // to IButton, but ILabel carries no picture at all (a fixed, non-restyling font furnace instead), so it
        // is not shipped; :image() on a label refuses naming the builder that does take a face.
        m.set("label", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "ui", "label");
                return Controls.label(owner, a);
            }
        });
        //   :image() answers :source(h) — a hafen.asset handle or a client resource name, the same two doors a
        // button face resolves — live at any time (haven.Img.setimg is not building-only the way a face is).
        m.set("image", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "ui", "image");
                return Controls.image(owner, a);
            }
        });
        //   :separator() has no verb of its own — a plain rule, :size(w, h) the only thing that shapes it.
        m.set("separator", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "ui", "separator");
                return Controls.separator(owner, a);
            }
        });
        //   :progress() answers :value() — 0..1 — the first control in this feature that HOLDS something
        // rather than merely reading nil on the verb every control answers.
        m.set("progress", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "ui", "progress");
                return Controls.progress(owner, a);
            }
        });
        // :check() — 040.4, THE VALUE SPINE. A haven.CheckBox, where :image(up, down, hoverUp, hoverDown)
        // completes it as an ICheckBox exactly as :image(up, down[, hover]) completes :button() as an IButton
        // (040.2's rule, one more control). Its caption is :text(s), its state :value(v), and it is the first
        // control in this feature to answer :onChange(fn) -- fires from a real click only; a programmatic
        // :value(v) never re-enters it.
        m.set("check", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "ui", "check");
                return Controls.check(owner, a);
            }
        });
        // :radio() — 040.5, ONE control, not a group object plus N buttons. hafen.ui():radio():rows{"Quality",
        // "Amount", "Name"} builds three real haven.RadioGroup.RadioButtons, stacked downward from this
        // control's own :position, one row height apart; RadioGroup/RadioButton never surface. :value(label)
        // checks one and :onChange(fn) fires on a real pick only -- a programmatic :value(v) flips the two
        // buttons' own state directly rather than going through RadioGroup.check() (which always fires the
        // group's changed hook), so it never re-enters :onChange, the same rule 040.4 pinned for the checkbox.
        m.set("radio", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "ui", "radio");
                return Controls.radio(owner, a);
            }
        });
        // :slider() — 040.6, a real haven.HSlider. :range(min, max) sets the bounds, :value(n) the position
        // within them -- CLAMPED on a write outside the range rather than refused, unlike :progress()'s hard
        // 0..1 -- and :onChange(v, final) is ONE callback over the engine's changed()/fchanged() pair, final
        // false while dragging and true once on release. Narrowing :range re-clamps an existing value without
        // firing :onChange, since that is not a user interaction.
        m.set("slider", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "ui", "slider");
                return Controls.slider(owner, a);
            }
        });
        //   :scrollbar() — a bare haven.Scrollbar for driving something yourself: the same :range/:value as
        // the slider, minus the final flag on :onChange(fn) -- the engine gives this one no separate "drag
        // ended" hook.
        m.set("scrollbar", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "ui", "scrollbar");
                return Controls.scrollbar(owner, a);
            }
        });
        // :entry() — 040.7, a real haven.TextEntry. Its content is :value(s), the ONE door (decision A) --
        // :onChange(fn) fires on every keystroke and
        // :onSubmit(fn) once, on Enter -- two names for two gestures, not one name with a flag. Typing into it
        // never reaches the game: it takes keyboard focus like any TextEntry, and nothing here calls wdgmsg.
        m.set("entry", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "ui", "entry");
                return Controls.entry(owner, a);
            }
        });
        // :scroll() — 040.8, a scrolling container over haven.Scrollport's own two pieces. :parent(sp) on any
        // control puts it INSIDE the scrolling area (LuaWidget's parent(w) write redirects into the port's own
        // inner container for this one control) -- never beside the bar, which is the trap a plain add() would
        // fall into. The bar itself answers the same :range/:value/:onChange as a bare :scrollbar(), found the
        // ordinary way once content taller than the box makes it live; the container itself has no verb of its
        // own.
        m.set("scroll", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "ui", "scroll");
                return Controls.scroll(owner, a);
            }
        });
        // :listbox() — 040.9, the first of the MODEL-BACKED five: a real haven.SListBox, and NAMED for it. Every
        // other :list() in the API enumerates a set, and this one builds a control and attaches it -- so the verb
        // is the client's own class name, sitting beside :dropdown() and :menu(), and `list` is left to mean one
        // thing. :rows(t) is a plain Lua array -- a string becomes a text row, {icon=, text=} an icon+text row,
        // and a table may mix both freely -- built through LuaRows, the bridge the later model-backed controls
        // (040.10's dropdown/menu, 040.12's table) reuse rather than re-deriving. :value()/:value(v) is the
        // selected row -- the SAME Lua value :rows(t) was given, so it can be handed straight back to :value(v)
        // or compared with == -- :onChange(fn) fires on a real pick only, and :rowHeight(n) -- defaulting to the
        // client's own label height -- is building-only like a face setter, since the engine fixes a row-list's
        // item height at construction.
        m.set("listbox", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "ui", "listbox");
                return Controls.listbox(owner, a);
            }
        });
        // :dropdown() — 040.10, the second of the MODEL-BACKED five: a real haven.SDropBox, closed until
        // clicked, over the same LuaRows bridge :listbox() uses. :rows(t)/:value()/:value(v)/:onChange(fn) answer
        // exactly as they do on :listbox() -- the same spine, a different engine class underneath.
        m.set("dropdown", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "ui", "dropdown");
                return Controls.dropdown(owner, a);
            }
        });
        // :menu() — 040.10, the third of the MODEL-BACKED five: a real haven.SListMenu. It FIRES and holds
        // nothing -- :value() reads nil on it -- so :onSelect(fn), not :onChange(fn), carries the picked row.
        // The engine's own SListMenu grabs all mouse/keyboard input the instant it is attached; this builder
        // opts out (haven.SListMenu.nograb()) so a menu behaves like any other control you place and configure,
        // not a modal popup.
        m.set("menu", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "ui", "menu");
                return Controls.menu(owner, a);
            }
        });
        // :grid() — 040.11, the fourth of the MODEL-BACKED five: a real haven.GridList, and the odd one out --
        // it DRAWS cells rather than building row widgets, so :rows(t) is a plain array of arbitrary Lua values
        // and :onCell(g, item, w, h) paints one through the SAME g wrapper :onDraw(fn) hands a surface, rather
        // than the LuaRows bridge the other four share. :cell(w, h) is the cell box and, like :rowHeight(n), is
        // building-only -- GridList.Group.itemsz is final.
        m.set("grid", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "ui", "grid");
                return Controls.grid(owner, a);
            }
        });
        // :table() — 040.12, the fifth and last of the MODEL-BACKED five: a real haven.TableBox. Built with NO
        // columns until :columns(t) names them -- {title=, width=, of(row)} per column, over ColSpec.of -- and
        // :rows(t) is a plain array of arbitrary Lua values, the same shape :grid()'s row source has (a table
        // row is not a string or {icon=, text=} pair; it is whatever of(row) reads from it). Both :columns(t)
        // and :rowHeight(n) are building-only, like :cell(w, h) -- the client's own TableBox fixes its columns
        // and row height at construction.
        m.set("table", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "ui", "table");
                return Controls.table(owner, a);
            }
        });
        // :overlay() — paint on top of the HUD without owning a widget, as a COLLECTION of KEYED painters:
        // :add(key) attaches a bare one and :draw(fn) says what it paints, fn(g, w, h) every frame with the
        // shared GOut wrapper and the screen size, in absolute screen coords. :get(key) reads one back,
        // :remove(key) ends it, and :list() is the DRAW ORDER.
        //   It is gob:overlay()'s shape, said of the screen instead of an object, because `overlay` names ONE
        // thing in this API — keyed decorations bound to a thing — and a reader who learned the world already
        // knows the HUD. Teardown on reload/disable drops every painter (P2).
        final LuaValue hud = LuaHudOverlay.collection(owner);   // per-addon: a view, minted once, holds nothing
        m.set("overlay", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "ui", "overlay");
                if(Args.passed(a, 2))
                    throw new LuaError("hafen.ui():overlay() takes no arguments — it IS the collection of your"
                        + " HUD painters, and hafen.ui():overlay():add(key) attaches one whose :draw(fn) says"
                        + " what it paints");
                return hud;
            }
        });
        // :sheet() — 033.1 feature C1a, reshaped into objects by 039.7: THE STYLESHEET. It says what the client
        // looks like, as a SELECTOR (the very string s:ui():match takes — one vocabulary, not two) naming a
        // RULE whose properties are setters:
        //     local s = hafen.ui():sheet()
        //     s:rule("*"):font(body)
        //     s:rule("window.title"):font(body:derive{ size = 14 })
        //     s:install()                       -- ...and s:release()
        // An addon has exactly ONE sheet, handed back by identity. :install() applies what it says, replacing
        // whatever this addon had installed WHOLE (a site the sheet no longer names falls back); an edit to an
        // installed sheet applies at once; :release() takes it off, and a :reload/disable drops it too — the stock
        // client is always restorable. s:load(t) is the DATA door: a whole sheet as a parsed table (a theme.json
        // goes straight in), replacing what the sheet said. hafen.font.setFont / .reset / .scopes are a HARD CUT:
        // a font is one PROPERTY of a rule, not an API of its own. hafen.font(name) is untouched — it still names
        // an engine font (D-060), and a .ttf this addon ships is still hafen.asset(path):derive{…}.
        //   Keys resolve one of two ways. A SITE key — `*` (the global fallback) or one of the twelve routed
        //   surfaces (window.title / window.frame / panel / heading / button / label / textentry / tooltip / menu /
        //   chat / world.nick / world.speech) — is resolved where that site DRAWS, exactly as the font scopes
        //   always were, so no render site is re-routed and no drawing code changed: what changed is who fills the
        //   provider stack. A TREE key (@Class, [title=…], [res=…], or a role that classifies a widget rather than
        //   a site, like `window`/`inventory`) is resolved per widget against the live tree (034, C1b) and folded
        //   over the site half per property. A malformed key errors exactly as s:ui():match(sel) does.
        //   Conflict between addons is D-043 reused literally: last applied wins, an addon's entries are pulled on
        //   its teardown, the surface falls back to the next owner beneath and finally to stock.
        // Properties, one setter each: `font` (a handle from hafen.font(name) or hafen.asset(path), optionally
        // :derive{size=,bold=,…}), `color` (the table a colour is: {200, 210, 220} or {r=,g=,b=[,a=]}), and the
        // chrome three of 035 — `bg` ({color=…} or {image=<asset>}), `border` ({image=<asset>, slice={l,t,r,b}})
        // and `pad` (pixels) — plus the layout three of 036 on a rule that names a WIDGET: `position`, `anchor`
        // and `size`. Any may stand alone: a colour-only rule keeps the site's own font, a border-only rule keeps
        // its background. Each has a matching bare read, and an unknown verb is an ERROR naming the rule — a key
        // may mean something later, a misspelt property never will (D-072).
        //   033.2 settles a duplication: a SURFACE's colour comes from the SHEET; a font handle's own `color`
        //   (hafen.font("serif"):derive{color=…}) applies only to YOUR OWN drawing — g:text and your own widgets —
        //   and is ignored when that handle is installed on a surface. Otherwise there would be two answers to
        //   "what colour is this text", one of them invisible in the sheet. Where a rule sets a colour the site
        //   draws in it even when the site itself asks for another; rich-text $col markup inside the text still
        //   wins, and a surface whose colour is not the font's (a window caption is tiled from a texture) simply
        //   ignores it.
        final LuaValue sheet = LuaSheet.of(owner);      // per-addon, minted once: hafen.ui():sheet() is identity
        m.set("sheet", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "ui", "sheet");
                if(Args.passed(a, 2))
                    throw new LuaError("hafen.ui():sheet() takes no arguments — it hands back this addon's one"
                        + " sheet, and a rule of it is sheet:rule(selector)");
                return sheet;
            }
        });
        // 039.5: hafen.ui() is the SECTION and takes no argument. Both old arities were widget lookups —
        // hafen.ui(selector) is :match(selector) and the bare hafen.ui() was the root, now :root() — and neither
        // could survive as the call itself, since a section object is not a member of the tree it addresses.
        // 039.7: `skin` was the last verb still a plain field on the callable table, so the table is empty and
        // this is the plain mount again.
        Section.install(hafen, "ui", m,
                        "hafen.ui(selector) is now s:ui():match(selector) and the tree's own top is s:ui():root(),"
                        + " where s is a Session — a widget the game put up stands in the tree of the character"
                        + " it put it up for. Your own surfaces are hafen.ui():window() and the constructors");
    }

    /**
     * Build the {@code ui} section object for {@code (owner, user)} — <b>the widgets the client put up for one
     * character</b>, reached as {@code session:ui()} (078.2).
     *
     * <p><b>Your window and the client's window are not the same thing</b>, which is why this namespace SPLITS
     * rather than moves. {@code hafen.ui():window()}, {@code :widget()}, {@code :overlay()} and the sixteen
     * constructors build something of <i>yours</i> — parented into the addon layer's own root since 074.1, drawn above
     * every session — and keep their global spelling; {@code :sheet()} is a rule declaration owned by the addon
     * and applies in every session at once; {@code :mouse()}, {@code :hit(x, y)}, {@code :tipAt(x, y)} and
     * {@code :scale()} ask about the screen, and there is one pointer and one coordinate space however many
     * characters are logged in. The seven verbs here are the ones that reach a widget <b>the game placed for
     * one character</b>, so each grows an address. Only a client with one login could pretend the two halves
     * shared a namespace.
     *
     * <p><b>Two trees, and the search says which.</b> A lookup here starts at that session's own
     * {@link UI#root}; the addon layer is a separate {@code UI} and is not under it. So nothing you built is
     * findable through this door, and {@code widget:parent()} walked up from anything it hands back arrives at
     * that session's root rather than at a window of yours.
     *
     * <p><b>Any session, drawn or not.</b> Every verb resolves through {@link AddonManager#sessionui(String)} —
     * that login's own {@code UI} — and never through {@link AddonManager#screen()}, which answers the session on
     * screen. A background session keeps its whole tree, so its Inventory is open, findable and readable while
     * the player is looking at another character. A session the client does not hold, or one between trees
     * (connecting, on the character list, gone), answers {@code nil}-shaped rather than throwing, exactly as
     * the sections 076 and 077 put on the Session do.
     *
     * <p><b>{@code :on(sel, "Added", fn)} scans the live tree at registration</b>, and here it scans the tree
     * of the session it was addressed at — so subscribing on a background session fires at once for what that
     * character already has open. The watch records that tree ({@link LuaSelectorWatch#ui}), which is what lets
     * its {@code :remove()}, the addon's teardown and the prune following that tree's death all name it rather
     * than whichever session holds the screen at the time. Its handle ends with {@code :remove()}.
     *
     * <p>Minted once per {@code (addon, session)} and hung on the interned Session handle, the shape
     * {@link WorldApi#world} established — so {@code s:ui() == s:ui()}.
     */
    static LuaValue ui(final Addon owner, final String user) {
        LuaTable m = new LuaTable();
        // :root() — the top of THAT session's whole widget tree, or nil before it has one. From here an addon
        // walks DOWN to any open window; a selector names one directly. There is no layer twin: an addon that
        // wants its own window holds the handle the builder gave it.
        m.set("root", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "ui", "root", UIS);
                if(Args.passed(a, 2))
                    throw new LuaError(UIS + ":root() takes no arguments — it IS the top of that session's"
                        + " tree, and a widget under it is :match(selector) or :node(id)");
                return nodeRoot(owner, sessionui(user));
            }
        });
        // :match(selector) — THE widget matching sel in THAT session's tree, or nil, and a REFUSAL when two or
        // more match (049.2): "the first in tree order" is a wrong answer in place of no answer. Strict.
        //   The verb is named for the LANGUAGE it takes. A collection's :find(filter) is a substring of a name
        // or a predicate; this argument is a SELECTOR, a grammar — so s:kin():find("Bo") matches by substring
        // while a bare "Cupboard" here is a ROLE, and the role called Cupboard does not exist. One spelling
        // over two query languages made that miss silent; two spellings make the reader say which they meant.
        m.set("match", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "ui", "match", UIS);
                LuaValue sel = Args.required(a, 2, UIS + ":match", "selector");
                return matchOne(owner, sessionui(user), selArg(sel, UIS + ":match(selector)"));
            }
        });
        // :matchAll(selector) — EVERY widget matching the selector in THAT session's tree, as a 1-based array in
        // tree order (empty, never nil). One walk testing each node, not a deep helper per node (that is
        // O(n²)); the selector is parsed ONCE here, never per node. HOLD the result — entities are interned,
        // so keeping it is free, while re-matching every frame is a whole tree walk every frame.
        //   It also settles "give me all of them": :list() enumerates a COLLECTION and :matchAll() runs a
        // selector, so the two never wear one word between them.
        m.set("matchAll", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "ui", "matchAll", UIS);
                LuaValue sel = Args.required(a, 2, UIS + ":matchAll", "selector");
                return matchEvery(owner, sessionui(user), selArg(sel, UIS + ":matchAll(selector)"));
            }
        });
        // :node(id) — the Widget for a SERVER widget id (another widget's :id(), typically) in THAT session's
        // tree, or nil if it does not resolve. A widget id counts inside one session's own tree: the same
        // number names a different widget on the other character, which is why this reads through the address.
        m.set("node", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "ui", "node", UIS);
                return nodeById(owner, sessionui(user), Args.required(a, 2, UIS + ":node", "id"));
            }
        });
        // :on(selector, "Added"|"Removed", fn) — WATCH THAT session's own tree for a part of it, named with
        // the same selector a lookup uses. fn(widget) receives the Widget entity, interned — so `==` and your
        // own tables work across the two events. Registration SCANS the live tree, so an already-open target
        // fires at once; addressed at a background session that is that session's tree, which exists whether or
        // not it is drawn. Both events are about the TREE, not visibility: a window the client merely hides
        // (the inventory's Tab toggle) never left, so it fires neither. One event per call — subscribe twice to
        // watch both. Returns a Sub, like every other :on in the API: sub:key() is the event it carries and
        // sub:off() ends it; auto-removed on reload/disable (P2), which fires nothing — a reload is not a
        // destroy.
        m.set("on", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "ui", "on", UIS);
                LuaValue sel = Args.required(a, 2, UIS + ":on", "selector");
                LuaValue event = Args.required(a, 3, UIS + ":on", "event");
                LuaValue fn = Args.required(a, 4, UIS + ":on", "fn");
                return newSelectorWatch(owner, sessionui(user), sel, event, fn);
            }
        });
        // :inventory() / :equipment() — LOOKUPS, not a section of their own: the Widget entity for THAT
        // character's backpack (GameUI.maininv) and its Equipory, so their contents are read the same way as
        // any other container's — s:ui():inventory():items() — and every other widget verb answers on them
        // too. Two characters carry two backpacks, and a background session's is open and readable. nil before
        // that session's HUD is up.
        m.set("inventory", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "ui", "inventory", UIS);
                if(Args.passed(a, 2))
                    throw new LuaError(UIS + ":inventory() takes no arguments — it hands back that character's"
                        + " backpack, and what is in it is :items()");
                return LuaWidget.of(owner, CharApi.maininv(user));
            }
        });
        m.set("equipment", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "ui", "equipment", UIS);
                if(Args.passed(a, 2))
                    throw new LuaError(UIS + ":equipment() takes no arguments — it hands back that character's"
                        + " equipment, and what is worn is :items(), each entry carrying its own slot");
                return LuaWidget.of(owner, CharApi.equipory(user));
            }
        });
        return Section.object("ui", m, UIS);
    }

    /** How {@link #ui}'s section is reached, and the spelling every one of its messages quotes. */
    private static final String UIS = "session:ui()";

    // ------------------------------------------------------------------ selectors (hafen.ui(sel), 030.1)

    /**
     * A selector argument &rarr; a parsed {@link Selector}, or a clear error. The number check comes BEFORE
     * {@code isstring()} because in LuaJ a number IS a string (the {@code hafen.asset} lesson, 028).
     */
    static Selector selArg(LuaValue v, String where) {
        if(v.isnumber())
            throw new LuaError(where + ": the argument is a SELECTOR string (e.g. \"window[title=Cupboard]\"),"
                + " not a number — s:ui():node(id) is the one that takes a widget id");
        if(!v.isstring())
            throw new LuaError(where + " expects a selector string (e.g. \"*\", \"inventory\","
                + " \"@Equipory\", \"window[title=Cupboard]\"), got " + v.typename());
        return Selector.parse(v.tojstring());
    }

    /**
     * {@code session:ui():match(selector)} — <b>THE</b> widget matching {@code sel} in {@code u}, or {@code nil},
     * and (049.2) a <b>refusal</b> when two or more match. "The first in tree order" would be a wrong answer
     * in place of no answer the moment a second window matches: an addon that reached the Close button of
     * "the" Foo window would keep working right up to the day the player opened a second Foo, and then quietly
     * click the other one. So the walk does not short-circuit — it collects every match and says how
     * many there were, which is the rule {@link LuaWidget#role} beside it has always followed.
     *
     * <p><b>{@code u} is the tree the caller named</b> (078.2), that session's own and not the drawn one, so a
     * character nobody is looking at is searched exactly as one on screen is. {@code null} — no such session, or
     * one between trees — answers {@code nil}, the same nothing an empty tree answers.
     *
     * <p>The price is the whole tree on every call, which is nothing once per event and a real slice of the frame
     * budget sixty times a second — so <i>hold your result</i> stopped being advice and became load-bearing.
     */
    private static LuaValue matchOne(Addon owner, UI u, Selector sel) {
        if((u == null) || (u.root == null))
            return LuaValue.NIL;
        List<Widget> hits = new ArrayList<Widget>();
        synchronized(u) { collect(u.root, sel, hits); }
        return one(owner, hits, sel, UIS + ":");
    }

    /**
     * {@code session:ui():matchAll(selector)} — every match in {@code u} as a 1-based Lua array in tree order;
     * <b>empty, never nil</b> (the collection form always answers). ONE pre-order walk testing each node — never
     * {@link Widget#children} per node, which is a deep traversal and would make this O(n²) (the 029.4 lesson).
     * The whole walk runs under that tree's own {@code ui} monitor, so it never races its mutation, and the
     * matcher calls no Lua.
     */
    private static LuaValue matchEvery(Addon owner, UI u, Selector sel) {
        if((u == null) || (u.root == null))
            return new LuaTable();
        List<Widget> hits = new ArrayList<Widget>();
        synchronized(u) { collect(u.root, sel, hits); }
        return table(owner, hits);
    }

    /**
     * {@code widget:match(selector)} — the same search from {@code scope} instead of {@code ui.root}, and just as
     * strict. The scope decides which widgets are <b>candidates</b> (this one and everything under it); the
     * selector is still matched against the whole tree, so an ancestor step may name a widget <i>above</i> the
     * scope — exactly what {@code element.querySelector} does in CSS.
     */
    static LuaValue scopedMatch(Addon owner, Widget scope, Selector sel) {
        List<Widget> hits = new ArrayList<Widget>();
        synchronized(LuaWidget.monitor(scope)) { collect(scope, sel, hits); }
        return one(owner, hits, sel, "widget:");
    }

    /** {@code widget:matchAll(selector)} — every match inside {@code scope} (inclusive), 1-based; empty, never nil. */
    static LuaValue scopedMatchAll(Addon owner, Widget scope, Selector sel) {
        List<Widget> hits = new ArrayList<Widget>();
        synchronized(LuaWidget.monitor(scope)) { collect(scope, sel, hits); }
        return table(owner, hits);
    }

    /**
     * The strict answer of a {@code match} door: {@code nil} for no match, the widget for exactly one, and an
     * error for two or more that says <b>how many</b> and hands back the two spellings that do have an answer —
     * the array with an index, and (since the chain exists) a selector that names the one widget exactly.
     * {@code door} is the receiver the caller wrote, so the message quotes {@code s:ui():matchAll(…)} or
     * {@code widget:matchAll(…)} rather than a form the reader was not using.
     */
    private static LuaValue one(Addon owner, List<Widget> hits, Selector sel, String door) {
        if(hits.isEmpty())
            return LuaValue.NIL;
        if(hits.size() > 1)
            throw new LuaError(door + "match(\"" + sel.src + "\") matches " + hits.size() + " widgets, so there is"
                + " no ONE widget to hand back — name the one you mean (a chain reaches an exact nested widget:"
                + " \"window[title=Foo] button[text=Close]\"), search inside a single widget with"
                + " widget:match(selector), or take one by index with " + door + "matchAll(\"" + sel.src
                + "\")[i]");
        return LuaWidget.of(owner, hits.get(0));
    }

    /** A list of widgets as the 1-based Lua array every collection door hands back, each entity interned. */
    private static LuaValue table(Addon owner, List<Widget> hits) {
        LuaTable out = new LuaTable();
        int i = 0;
        for(Widget w : hits)
            out.set(++i, LuaWidget.of(owner, w));
        return out;
    }

    /** Pre-order collection of every match under {@code w} (inclusive). Under the {@code ui} monitor. */
    static void collect(Widget w, Selector sel, List<Widget> out) {
        if(sel.matches(w))
            out.add(w);
        for(Widget c = w.child; c != null; c = c.next)
            collect(c, sel, out);
    }

    /**
     * <b>A widget tree died, so every record naming one of its widgets does</b> (074.2) — from
     * {@code AddonManager.uiDestroyed}, the one place a {@code UI} in this client is taken down.
     *
     * <p>It was {@code pruneConsole}, and it walked the {@code :lua} REPL owner alone: the REPL was the only
     * Lua owner that outlived a session, so it was the only one that could hold a record of a tree that had
     * ended. <b>Every addon outlives a session now</b>, which is the feature — so every addon is walked, and
     * for the very reason 073.2 gave for walking the REPL: a widget of a dead tree has nothing to restore,
     * nothing to put back where it was, and nobody left to hear it.
     *
     * <p>Hung on the tree's death rather than on a switch, which is what makes it right rather than merely
     * later: an anchor change forgetting a hidden window of the session the player is coming BACK to would
     * leave it hidden with nothing holding the toggle that reopens it.
     *
     * <p>A subscription names no single widget, so it is dropped by the tree it was registered against
     * ({@link LuaSelectorWatch#ui}) instead. On whatever thread destroyed the {@code UI}; every collection
     * here is copy-on-write or concurrent, and none of it runs Lua.
     */
    static void pruneDeadTrees() {
        for(Addon a : AddonManager.profOwners())
            prune(a);
        LuaWidget.recountHidden();               // 031.1: whatever was given back is no longer being held
        LuaWidget.recountMoved();                // 036.1: ...and no longer being laid out
    }

    private static void prune(Addon co) {
        if(co == null)
            return;
        for(LuaWidget.Hidden h : co.hiddenNative) {  // 029.2: a widget of a dead tree has nothing to restore
            if(dead(h.wdg))
                co.hiddenNative.remove(h);
        }
        for(LuaWidget.Moved m : co.movedNative) {    // 036.1: ...nor anything left to put back where it was
            if(dead(m.wdg))
                co.movedNative.remove(m);
        }
        for(LuaWidget.Rehomed r : co.rehomedNative) {   // ...nor any home to send it back to
            if(dead(r.wdg))
                co.rehomedNative.remove(r);
        }
        for(Gesture.Bind b : co.gestures) {          // 062: ...nor is it left armed for the user
            if(dead(b.target) || dead(b.handle))
                co.gestures.remove(b);
        }
        for(Map.Entry<String, Widget> e : co.remembered.entrySet()) {   // 062: ...nor remembered by name
            if(dead(e.getValue()))
                co.remembered.remove(e.getKey(), e.getValue());
        }
        for(Iterator<Widget> it = co.widgetSubs.keySet().iterator(); it.hasNext(); ) {
            if(dead(it.next()))                      // 041.3/041.4: ...and so is every widget:on() subscription
                it.remove();                         // 128.1: the BACKSTOP, not the mechanism — a widget that
        }                                            //   dies on its own is retired at the disposal seam
                                                     //   (AddonManager.drainDisposedWidgets); this is what is
                                                     //   left when the whole tree goes and nobody is drained
        for(LuaSelectorWatch w : co.selectorWatches) {                  // 030.2: ...and the selectors it watched
            if((w.ui == null) || w.ui.destroyed) {
                w.alive = false;
                w.matched.clear();
                co.selectorWatches.remove(w);
            }
        }
    }

    /** Is this widget's whole session gone? (Never {@code remove()}d-but-live: that tree may still be played.) */
    private static boolean dead(Widget w) {
        return (w == null) || (w.ui == null) || w.ui.destroyed;
    }

    /**
     * Build one bare surface for {@code hafen.ui():window()} / {@code :widget()} (039.6): an
     * {@link AddonWidget} content leaf, optionally wrapped in a draggable {@link Window} (chrome), with the
     * client's own defaults, attached to {@code ui.root} at once and <b>painting nothing</b> until
     * {@link #armPending()} arms it on the next tick. Registered in the addon's owned-resource registry
     * (torn down on reload/disable, P2).
     */
    private static LuaValue newUi(final Addon owner, Varargs a, boolean window) {
        String what = window ? "window" : "widget";
        if(Args.passed(a, 2))
            throw new LuaError("hafen.ui():" + what + "() takes no arguments — it is built bare and configured"
                + " by chained setters: hafen.ui():" + what + "()"
                + (window ? ":title(\"…\")" : "") + ":size(w, h):position(x, y):onDraw(fn)");
        UI u = requireUi(what);

        final AddonWidget content = new AddonWidget(owner, Px.in(Coord.of(DEF_W, DEF_H)));
        final Widget rootw;
        if(window) {
            // ANONYMOUS on purpose: the chrome must skip its own draw while the content is unarmed, and
            // LuaWidget.typeName climbs past an anonymous subclass — so w:type() still reads "Window" and every
            // selector, deco and toggle that names one keeps matching. A named subclass would rename the widget.
            final Window win = new Window(Px.in(Coord.of(DEF_W, DEF_H)), "") {
                public void draw(GOut g) {
                    if(!content.pending())
                        super.draw(g);
                }
            };
            win.add(content, Coord.z);
            content.root(win);
            win.reqclose(() -> {                      // the chrome close button: fire :onClose(), then destroy
                content.closed();                     //   read from the slot, so a handler set later is the one that runs
                content.kill();
                dropPending(content);
                owner.widgets.remove(content);
            });
            rootw = win;
        } else {
            rootw = content;
        }
        return attach(u, owner, content);
    }

    /**
     * <b>The tree a builder puts its surface in</b> — the {@link AddonManager#layer() addon layer} (074.1),
     * never the session on screen. Your own windows are a layer above the sessions: one tree for the client's
     * life, drawn over whichever session holds the screen and over the login screen when none does. That is
     * the whole of why a window keeps its place, its focus and any grab it holds across a character switch —
     * nothing is re-homed, because nothing it lives in ends.
     *
     * <p>The tree an addon <b>searches</b> is the other thing entirely: {@link AddonManager#sessionui(String)},
     * one character's own, reached through {@code session:ui()} and never from here.
     */
    static UI requireUi(String what) {
        UI u = AddonManager.layer();
        if((u == null) || (u.root == null))
            throw new LuaError("hafen.ui():" + what + "(): no UI is up yet");
        return u;
    }

    /**
     * Put an owned thing in the tree — the tail every builder in this section shares (039.6's window and widget,
     * 040.1's controls): the client's own default place, attached to {@code ui.root} at once, registered in the
     * addon's owned-resource registry (torn down on reload/disable, P2) and queued for its arming tick.
     *
     * <p>029.2: what you CREATE and what you FIND are the same type. The entity is interned on the <b>root</b>
     * (the window chrome, or the widget/control itself) — the widget the addon positions, shows and destroys —
     * and {@link LuaWidget#ownedContent} derives OWNED from the tree, so {@code hafen.ui():hit(x, y)} over this
     * same widget hands back this very value.
     */
    static LuaValue attach(UI u, Addon owner, Owned c) {
        Widget rootw = c.rootw();
        rootw.c = Px.in(Coord.of(DEF_X, DEF_Y));   // the default place, movable before it is ever painted
        u.root.add(rootw);                  // add() locks on ui; :parent(w) re-homes it while it is still pending
        owner.widgets.add(c);
        queueArming(u, c);                  // 073.2: armed by the tick of the tree it was just attached to
        return LuaWidget.of(owner, rootw);
    }

    /**
     * <b>Replace one owned widget with the widget it becomes</b> — the face setter's rebuild (040.2): the same
     * control, the same Lua handle, a different {@code haven} class. {@code hafen.ui():button():image(u, d)} has
     * to hand back an {@link haven.IButton} where a {@code Button} stood, because the two are different widgets
     * to the client and one control to the author, and an {@code IButton}'s faces are {@code final}.
     *
     * <p><b>What moves is everything keyed on the old widget</b>, and that list is the whole reason this is one
     * method rather than five lines at the call site: its place in the tree (parent, coordinate, visibility), its
     * entry in the owned registry teardown walks, its slot in the arming queue, this addon's interned Widget
     * handle — <i>so the Lua value the author is chaining stays {@code ==} itself across the swap</i> — the Rule
     * object {@code widget:rule()} interned on it, and the per-instance style level that rule installed. Anything
     * left behind would fail silently and late: a style that stopped applying, a handle that went stale
     * mid-statement, a control teardown no longer reaches.
     *
     * <p><b>Legal only while the control is pending</b>, which the caller has already checked — that is what
     * makes the swap invisible: no frame has drawn the old widget, and no other addon can have seen it, because
     * one Lua statement runs to its end before anything else does.
     *
     * <p>The old widget is killed rather than merely unlinked, so its cached face texture is released with it.
     * The new one keeps the place the old one was given, so a {@code :position(x, y)} before the face setter and
     * one after it mean the same thing. Its SIZE is the picture's, deliberately: an image button <i>is</i> its
     * image, and a box wider than the face would draw the picture in a corner of empty space.
     */
    static void rebuild(Addon owner, Owned old, Owned neu) {
        Widget oldw = old.rootw(), neww = neu.rootw();
        // 072.2: the monitor is the widget's own (072.1's rule — this block mutates oldw and neww), and the
        // root the new one falls back to is the OLD WIDGET'S OWN (073.2): a control this layer built is in the
        // tree it built it in, and asking screen() for it was asking which session is on screen — a different
        // question, and a different answer the moment the player tabs to another one mid-statement.
        UI u = oldw.ui;
        synchronized(LuaWidget.monitor(oldw)) {
            Widget parent = oldw.parent;
            Coord at = oldw.c;
            boolean shown = oldw.visible();
            old.kill();                       // unlink + dispose: the old face texture goes with it
            if(!shown)
                neww.hide();
            ((parent != null) ? parent : u.root).add(neww, at);
        }
        owner.widgets.remove(old);
        owner.widgets.add(neu);
        dropPending(old);
        queueArming(u, neu);
        owner.widgetObjs.rekey(oldw, neww);   // the Lua handle follows the widget it names...
        owner.styleRules.rekey(oldw, neww);   // ...and so does the Rule object interned on it...
        Sheet.rekeyWidget(oldw, neww);        // ...and the level that rule installed
    }

    // ---------------------------------------------------- the arming tick (039.6, spec 039-uniform-api §2.5)

    /**
     * The client's own defaults for a bare surface: what a window is before any setter touches it. <b>Design
     * pixels</b> (058.1), like every other box in this section — put through {@link Px#in} where the widget is
     * built, so a bare {@code hafen.ui():window()} reads back this very pair at every UI scale and looks the
     * same size beside the client's own windows.
     */
    private static final int DEF_W = 200, DEF_H = 140, DEF_X = 100, DEF_Y = 100;

    // 073.2: the arming queue is ONE TREE'S ({@code SessionState.unarmed}) — what is waiting is a widget
    // already attached to that session's root, and what arms it is that session's own tick. Every writer
    // below names the tree: the builder was handed one at attach, and a surface already in the queue can be
    // found through the very widget it is.

    /**
     * Arm every surface built since the last tick — the "arming tick" of §2.5, called first thing from
     * {@link AddonManager#tick(haven.UI)}. Until this runs, a built widget answers every read and takes every
     * setter, and paints nothing.
     *
     * <p><b>Attached inert, rather than held out of the tree</b> — D-112's answer, one level up. Deferring the
     * <i>attach</i> was the other candidate and is worse: it would silently break "find the widget I just
     * built" ({@code hafen.ui():hit}, {@code :matchAll}, a selector subscription), which is a capability, to buy a
     * guarantee about painting that skipping the draw already gives in full. What the draw skips is the
     * <b>whole</b> surface, chrome included, which is why {@link #newUi} builds an anonymous {@code Window}.
     */
    static void armPending(SessionState st) {
        List<Owned> queue = st.unarmed;
        if(queue.isEmpty())
            return;
        List<Owned> due;
        synchronized(queue) {
            due = new ArrayList<Owned>(queue);
            queue.clear();
        }
        for(Owned c : due)
            c.armed();
    }

    /** Queue one just-built surface for the arming tick of the tree it was attached to (073.2). */
    private static void queueArming(UI u, Owned c) {
        SessionState st = state(u);
        if(st == null)
            return;
        synchronized(st.unarmed) { st.unarmed.add(c); }
    }

    /**
     * Drop a surface from its own tree's arming queue (destroyed, or torn down, before it ever painted).
     * Two trees are asked, not one (074.1): a surface is built into the addon layer and {@code
     * widget:parent(w)} may re-home it into one of the client's windows before it ever armed, so the queue it
     * is waiting in is the one it was BUILT in, which is no longer the one it is in.
     */
    static void dropPending(Owned c) {
        unqueueArming(state(c.rootw().ui), c);
        unqueueArming(state(AddonManager.layer()), c);
    }

    private static void unqueueArming(SessionState st, Owned c) {
        if(st != null)
            synchronized(st.unarmed) { st.unarmed.remove(c); }
    }

    // ------------------------------------------------------------- custom UI overlays (hafen.ui, 2b)


    // ------------------------------------------------- selector subscriptions (s:ui():on, 030.2)

    /**
     * Register a selector subscription ({@code s:ui():on(selector, "Added"|"Removed", fn)}): parse the selector
     * ONCE, install the {@link LuaSelectorWatch} in the global list (consulted at the widget-entry seam's drain
     * and the removal seam, event-driven since 042.9 — no per-tick sweep) and in the addon's owned-resource
     * registry (dropped on reload/disable, P2), then SCAN the live tree once so an already-open target is not
     * missed. Returns the
     * {@link LuaSub} — this is a subscription like every other {@code :on} in the API (086.1), keyed by the
     * <b>event</b> it carries, with the {@link LuaSelectorWatch} on {@link LuaSub#tag}.
     */
    private static LuaValue newSelectorWatch(final Addon owner, UI wu, LuaValue selv, LuaValue eventv,
                                             LuaValue fn) {
        final String where = UIS + ":on(selector, event, fn)";
        Selector sel = selArg(selv, where);
        if(!eventv.isstring())
            throw new LuaError(where + ": event must be \"Added\" or \"Removed\", got " + eventv.typename());
        // 097: a key that MOVED is caught HERE, before the event is decoded, so an addon written against the
        // old spelling dies naming the new one rather than being told its own spelling is not an event.
        String moved = Refusal.eventKey(UIS, eventv.tojstring());
        if(moved != null)
            throw new LuaError(moved);
        int ev = LuaSelectorWatch.eventCode(eventv.tojstring());
        if(ev < 0)
            throw new LuaError(where + ": \"" + eventv.tojstring() + "\" is not an event — the events are"
                + " \"Added\" (a matching widget entered the tree, or was already in it) and \"Removed\""
                + " (one that had matched left it)");
        if(!fn.isfunction())
            throw new LuaError(where + " expects a handler function fn(widget)");
        // 073.2: the tree this subscription is about is recorded on the watch, so that every later act on it —
        // the handle's :remove(), the addon's teardown, the prune that follows that tree's death — names THIS
        // tree rather than re-asking at a moment when the screen has moved. 078.2: and it is the tree of the
        // session the addon ADDRESSED rather than the one on screen, which is what lets a subscription be made
        // on a character nobody is looking at and fire for what that character already has open.
        final LuaSelectorWatch w = new LuaSelectorWatch(owner, wu, sel, ev, fn);
        SessionState wst = state(wu);
        if(wst != null)                        // no session behind it (the login screen's console): owned,
            wst.selectorWatches.add(w);        //   removable, and it never fires — exactly as before
        owner.selectorWatches.add(w);
        // 086.1: the Sub is minted BEFORE the scan, because scanForWatch calls an `Added` handler back inside
        // this very registration — so the handler must never be able to run before the value this call is
        // about to hand back exists. The key is the event, which is what a person would name; the selector
        // lives on the watch record, where it already lived.
        LuaSub sub = owner.watchSubs.add(eventv.tojstring(), fn);
        sub.tag = w;
        LuaValue handle = sub.handle();
        scanForWatch(w);                       // catch what is ALREADY open (the :reload / subscribe-in-world case)
        return handle;
    }

    /**
     * Sweep the live tree once for widgets this subscription already matches — the {@code :reload} case, and the
     * ordinary one of subscribing while the game is running. This is the difference between a discovery primitive
     * and a creation feed: {@code onWidgetCreate} could never fire for a widget that already existed, so an addon
     * that only watched creations lost every window open at the moment it was edited. An {@code "Added"}
     * subscription is called back here, inside its own registration (the {@code replace} precedent); a
     * {@code "Removed"} one records silently, which is what lets a later close still fire.
     */
    private static void scanForWatch(LuaSelectorWatch w) {
        UI u = w.ui;                           // 073.2: the tree it was registered against, and no other
        if((u == null) || (u.root == null))
            return;
        // Under the ui monitor for the WHOLE scan, not just the walk: the placement seam and the tick both hold it,
        // so this is what keeps the subscription's `matched` map from being written by two threads at once (a
        // registration can arrive off the UI thread, from the session bind). Lua under the monitor is the seam's
        // own discipline, and re-entrant for the walk the handler may itself do.
        synchronized(u) {
            List<Widget> hits = new ArrayList<Widget>();
            collect(u.root, w.sel, hits);
            for(Widget hit : hits)
                record(w, hit, u.widgetid(hit));
        }
    }

    /**
     * Offer one newly-placed widget to every selector subscription (from {@link #onWidgetPlaced}, inside
     * {@code AddWidget.run}'s {@code synchronized(ui)} block). A full match fires {@code "Added"} at once. A widget
     * that matches only the STRUCTURE of a selector carrying a {@code [title=]}/{@code [res=]} refiner is queued for
     * the bounded re-check instead: role and class are fixed for a widget's life, but a caption arrives by
     * {@code uimsg} and can land a tick or two after placement, and a {@code .res} window would otherwise be
     * unmatchable by the very key that identifies it.
     */
    private static void offerPlaced(SessionState st, Widget wdg, int id) {
        boolean recheck = false;
        for(LuaSelectorWatch w : st.selectorWatches) {   // copy-on-write: a handler may subscribe/remove here
            if(!w.alive || w.matched.containsKey(wdg))
                continue;
            if(w.sel.matches(wdg))
                record(w, wdg, id);
            else if(w.sel.late() && w.sel.matchesStructure(wdg))
                recheck = true;
        }
        if(recheck)
            st.selectorPending.add(new PendingMatch(wdg, id));
    }

    /**
     * Record a match on one subscription and, for an {@code "Added"} one, fire it. The tracked set is what keeps the
     * bounded re-check from firing twice for the same widget, and what a {@code "Removed"} subscription later reads
     * — so both events record, only one calls Lua. The payload is the interned Widget entity, the same value every
     * other {@code hafen.ui} door hands back (029.1), so {@code ==} identifies it across the two events.
     */
    private static void record(LuaSelectorWatch w, Widget wdg, int id) {
        w.matched.put(wdg, Integer.valueOf(id));
        if(w.event == LuaSelectorWatch.ADDED)
            callLua(w.owner, Addon.C_WIDGET, w.fn, LuaWidget.of(w.owner, wdg));
    }

    /**
     * Offer a removed widget to every selector subscription (from {@link AddonManager#drainRemovedWidgets}, M1 seam
     * 042.9). Check whether it was matched and, for {@code "Removed"} subscriptions, fire the event. Also remove it
     * from pending if it's there (042.9: a widget that dies before its caption arrives is dropped from the bounded
     * re-check). Deaths are processed immediately as widgets are removed, not batched and polled — cost scales with
     * widget REMOVAL, not with frames, and a removed widget fires exactly once per subscription that matched it (no
     * miss-fire or double-fire even if multiple handlers unsubscribe).
     */
    static void dispatchSelectorRemoved(SessionState st, Widget w) {
        if(st.selectorWatches.isEmpty() && st.selectorPending.isEmpty())
            return;
        for(LuaSelectorWatch watch : st.selectorWatches) {   // copy-on-write: a handler may unsubscribe here
            if(!watch.alive)
                continue;
            Integer id = watch.matched.remove(w);            // was this widget matched by this subscription?
            if((id == null) || (watch.event != LuaSelectorWatch.REMOVED))
                continue;
            callLua(watch.owner, Addon.C_WIDGET, watch.fn, LuaWidget.of(watch.owner, w));
        }
        for(PendingMatch p : st.selectorPending) {           // drop it from pending too if it's there
            if(p.wdg == w) {
                st.selectorPending.remove(p);
                break;
            }
        }
    }

    // 042.9/049.3: a window's caption changed (from the caption seam, AddonManager.onCaptionChanged <- Window.chcap,
    // which runs on whatever thread wrote it — a Loader thread applying a uimsg, OUTSIDE synchronized(ui):
    // UI.java:730-732 closes the monitor before calling AddonManager.onUimsg — or the UI thread for an addon's own
    // widget:title(…)). Everything the re-check does reads widget state (matchLive, Selector.matches, the subtree
    // walk) and can call Lua (record -> callLua), so none of it may run from that thread (P5) — this queue is the
    // whole marshal: appended here, drained on the tick (UI thread, under synchronized(ui) per AddonRoot's class
    // doc). The WINDOW is what is recorded, not a bare flag: with 049's combinator a [title=] sits on an ancestor
    // step, so what may start matching is a widget somewhere BELOW the window whose caption landed.
    // 073.2: and it is ONE SESSION'S — the recorded windows are windows of one tree, so the queue is
    // {@code SessionState.selectorCapChanged}, reached with w.ui at the seam. That is not a nicety on this
    // thread: chcap runs on whichever Loader thread applied the message, so screen() would file a background
    // session's renamed window under the drawn session and its tick would walk a subtree of another tree.


    /**
     * Record that {@code w}'s caption changed (the caption seam, {@link AddonManager#onCaptionChanged}, 049.3).
     * Appends one reference — no widget read, no tree walk, no Lua — so it is safe on any thread. Free unless some
     * live subscription actually carries a late refiner: with none, no caption can make anything newly match, and
     * the shipped example addons all subscribe on a bare role.
     */
    static void markCaptionChanged(Widget w) {
        if(w == null)
            return;
        SessionState st = state(w.ui);        // 073.2: the tree the renamed window is in, and no other
        if(st == null)
            return;
        for(LuaSelectorWatch s : st.selectorWatches) {
            if(s.alive && s.sel.late()) {     // pure (the parsed selector's own shape) — no widget read, any thread
                st.selectorCapChanged.add(w);
                return;
            }
        }
    }

    /**
     * Tick-side drain (UI thread, {@link AddonManager#tick}) — the caption half of the {@code "Added"} event, in
     * two parts, both of which end in the same {@link #offer} and so dedup against the same {@code matched} map:
     *
     * <ul>
     *   <li><b>The captioned window's own subtree</b> (049.3). {@code window[title=Cupboard] inventory} names the
     *       grid, not the window, so the widget that starts matching when the caption lands is one below the one
     *       that changed — and it may have been placed long before, which is more than the placement-scoped list
     *       below can promise. Walking the subtree of the window that actually changed is exact, and costs a walk
     *       of one window per caption rather than anything per frame.</li>
     *   <li><b>The placement-scoped list</b> ({@code SessionState.selectorPending}, 030.2), swept whole rather
     *       than by which caption
     *       moved: a {@code [res=]} refiner resolves asynchronously with no event of its own, so this is the only
     *       thing that ever re-checks one, and the list is short-lived and bounded by construction
     *       ({@link #RECHECK_TICKS}).</li>
     * </ul>
     *
     * <p>Gated so an idle client — or one with no subscription at all — pays two {@code isEmpty()} calls.
     *
     * <p><b>The tree is READ under its own monitor and the handler runs outside it</b> (112.4), the shape
     * {@link #dispatchEntered} took in 112.3. Until the step left {@code synchronized(ui)} both halves were
     * covered by the caller's monitor; now the reachability test, the subtree walk and the match each take
     * this one tree's — which is exactly the one monitor a step holding none may take — and the handler is
     * called with it given up again.
     */
    static void drainSelectorCaptionCheck(SessionState st) {
        if(st.selectorCapChanged.isEmpty())
            return;
        UI u = st.ui;                 // 073.2: the tree whose tick this is, which is the tree those windows are in
        if((u.root == null) || st.selectorWatches.isEmpty()) {
            st.selectorCapChanged.clear();   // nothing left watching: the recorded windows are owed nothing
            return;
        }
        for(int n = st.selectorCapChanged.size(); n > 0; n--) {
            Widget w = st.selectorCapChanged.poll();
            if(w == null)
                break;
            List<Widget> sub = new ArrayList<Widget>();
            synchronized(LuaWidget.monitorOf(u)) {
                if(w.hasparent(u.root))   // inclusive of the root itself; a window removed before the step is
                    collectSubtree(w, sub);   //   offered nothing — out of the tree, it matches nothing any more
            }
            for(int i = 0; i < sub.size(); i++)
                offer(st, u, sub.get(i));
        }
        recheckPending(st, u);
    }

    /**
     * One candidate against every live subscription of its own tree whose refiner could only just have
     * resolved — <b>matched under the tree's monitor, fired outside it</b> (112.4), the same split
     * {@link #offerEntered} makes. The re-test is inside with the match, because a handler fired for an
     * earlier widget of the same subtree may have closed the window a later one sits in.
     */
    private static void offer(SessionState st, UI u, Widget wdg) {
        List<LuaSelectorWatch> fire = null;
        synchronized(LuaWidget.monitorOf(u)) {
            if(!wdg.hasparent(u.root))
                return;
            int id = u.widgetid(wdg);
            for(LuaSelectorWatch w : st.selectorWatches) {   // copy-on-write: a handler may subscribe/remove here
                if(!w.alive || w.matched.containsKey(wdg))
                    continue;
                if(w.sel.late() && w.sel.matches(wdg)) {
                    w.matched.put(wdg, Integer.valueOf(id));   // record: both events track, only one fires
                    if(w.event == LuaSelectorWatch.ADDED) {
                        if(fire == null)
                            fire = new ArrayList<LuaSelectorWatch>();
                        fire.add(w);
                    }
                }
            }
        }
        if(fire == null)
            return;
        for(LuaSelectorWatch w : fire)
            callLua(w.owner, Addon.C_WIDGET, w.fn, LuaWidget.of(w.owner, wdg));
    }

    /**
     * The bounded re-check (030.2): re-offer each recently-placed candidate to every subscription whose refiner had
     * not yet resolved, then age it out. A candidate that dies, or that survives {@link #RECHECK_TICKS} ticks
     * without matching, is dropped — so this list is short-lived by construction and the cost of the whole event
     * mechanism scales with widget CREATION, not with frames. An entry ageing out is still offered one last time.
     */
    private static void recheckPending(SessionState st, UI u) {
        if(st.selectorPending.isEmpty())
            return;
        for(PendingMatch p : st.selectorPending) {           // copy-on-write: entries drop out as we go
            boolean live;
            synchronized(LuaWidget.monitorOf(u)) {           // 112.4: the read is the tree's; the offer's fire is not
                live = matchLive(u, p.wdg, p.id);
            }
            if(!live) {
                st.selectorPending.remove(p);                // it died before its caption arrived
                continue;
            }
            if(--p.ticks <= 0)
                st.selectorPending.remove(p);
            offer(st, u, p.wdg);
        }
    }

    /** Is a matched/pending widget still the same live one? (Server-bound: by id; client-only: by reachability.) */
    private static boolean matchLive(UI u, Widget w, int id) {
        return (id >= 0) ? (u.getwidget(id) == w) : w.hasparent(u.root);
    }

    /**
     * Remove one selector subscription: stop it firing + drop it from both lists. Since 086.1 this is the
     * {@link Subs.Ended} hook of {@link Addon#watchSubs} — {@code sub:off()} and the teardown below both reach
     * it through there, and nothing else calls it. Per-SUB and not per-key: several watches share the key
     * {@code "Added"}, so removing one must remove one record.
     */
    static void removeSelectorWatch(Addon owner, LuaSelectorWatch w) {
        if(w == null)
            return;
        w.alive = false;
        w.matched.clear();
        owner.selectorWatches.remove(w);
        SessionState st = state(w.ui);   // 073.2: the tree it was registered against, which it recorded
        if(st == null)
            return;
        st.selectorWatches.remove(w);
        if(st.selectorWatches.isEmpty()) {
            st.selectorPending.clear();     // 042.9: last subscription gone — nothing left to re-check, and
            st.selectorCapChanged.clear();  //   nothing would ever drain these Widget refs again otherwise
        }
    }

    /**
     * Drop every selector subscription this addon owns (reload/disable, P2). Nothing is fired: a {@code :reload} is
     * not a destroy — the widgets go on living, this addon simply stops watching (and its Lua callbacks are about to
     * cease to exist with its env). Same rule as {@link #teardownWatches}.
     */
    static void teardownSelectorWatches(Addon a) {
        a.watchSubs.clear();          // 086.1: one drop, and each sub's Ended runs removeSelectorWatch above
        if(a.selectorWatches.isEmpty())
            return;                   // which is every one of them — the sweep below is the belt
        // 073.2: each one is dropped from the tree IT recorded, not from the tree on screen. An addon may have
        // subscribed in several sessions and the screen is on one of them, so screen() here would leave every
        // subscription made in any other standing in its list, still matching, and still calling an addon that
        // no longer exists.
        for(LuaSelectorWatch w : a.selectorWatches) {
            w.alive = false;
            w.matched.clear();
            SessionState st = state(w.ui);
            if(st != null)
                st.selectorWatches.remove(w);
        }
        a.selectorWatches.clear();
        for(SessionState st : AddonManager.allStates()) {
            if(st.selectorWatches.isEmpty()) {
                st.selectorPending.clear();     // 042.9: same as removeSelectorWatch — nothing left to own
                st.selectorCapChanged.clear();  //   the re-check in that tree
            }
        }
    }

    /**
     * Give back every NATIVE widget this addon hid with {@code widget:visible(false)} (029.2, reload/disable, P2) — the
     * restore that {@code hafen.ui.adopt} used to carry — and with it the window's toggle (031).
     *
     * <p><b>One rule, no branches: the window ends up as the user was seeing it</b> (031.2). The addon's view was
     * open ⇒ the stock window opens; nothing was on screen ⇒ it stays closed. That single expression
     * ({@link #restoreHidden}) covers the bare {@code w:hide()} case too — a window hidden with nothing put in its
     * place was not being seen, so it stays hidden, and its toggle (handed back here) is what opens it again. A
     * blind {@code show()} would be wrong the other way: the inventory wrapper is hidden by default, so it would
     * hand the user a window they never opened.
     *
     * <p><b>This must run BEFORE the addon's own widgets are destroyed</b> ({@code AddonRegistry.teardown} orders
     * it so): the rule reads the view's visibility, and a destroyed view stands for nothing.
     *
     * <p><b>And it ends the substitution, view included</b> (032.1). Restoring the window is only half of an
     * ending: the stand-in has to go with it, or a {@code :reload} leaves a custom window floating over the stock
     * one it no longer replaces. For a loaded addon {@code destroyWidgets} would have caught it a moment later
     * anyway (the kill is idempotent), but the {@code :lua} REPL owner <b>survives</b> a reload and has no such
     * sweep — which is exactly where the leak showed. The rule is read first, the view killed after.
     *
     * <p><b>The {@code :lua} REPL is torn down here too</b>, from {@code AddonRegistry.reload} — the REPL owner
     * itself survives a reload, but the windows it hid do not, exactly as its sounds (024.2) and cached text
     * (026.1) do not. Since 031.1 a hidden window's <i>toggle</i> is owned as well, which makes {@code :reload}
     * the escape hatch for a hide typed into the console: without this the key stays swallowed until a relog.
     *
     * <p><b>Each record names its own tree</b> (078.4). The window is put back in the {@code UI} it was hidden
     * in — {@code h.wdg.ui} — so an addon that hid a window on each of two characters gives both of them back,
     * and the monitor taken is the one guarding that widget rather than whichever session holds the screen. A
     * relog leaves the old tree behind entirely: a server-bound entry's id no longer maps to the recorded widget
     * there and a client-only one is no longer under that root, so the restore is correctly skipped. Tree ops →
     * under the {@code ui} monitor, like every other write into the client's tree.
     */
    static void teardownHidden(Addon a) {
        if((a == null) || a.hiddenNative.isEmpty())    // null: the :lua REPL owner, which exists only once used
            return;
        final List<LuaWidget.Hidden> hs = new ArrayList<LuaWidget.Hidden>(a.hiddenNative);
        a.hiddenNative.clear();
        LuaWidget.recountHidden();      // 031.1: the toggles this addon owned go back to the client
        for(LuaWidget.Hidden h : hs)
            endHidden(h);
    }

    /**
     * Put ONE hidden window back and end its substitution, under the monitor of the tree that window stands in.
     * The rule is asked BEFORE the view is killed, because it reads the view's visibility.
     */
    private static void endHidden(final LuaWidget.Hidden h) {
        final UI u = h.wdg.ui;
        Runnable restore = () -> {
            restoreHidden(u, h);              // the rule — asked BEFORE the view is killed
            AddonWidget v = h.view;
            h.view = null;
            destroyView(h.owner, v);          // 032.1: the view's fate follows the substitution, teardown included
        };
        if(u != null) {
            synchronized(u) { restore.run(); }
        } else {
            restore.run();
        }
    }

    /**
     * The <b>one teardown rule</b> for a single hide record (031.2): the window ends up as the user was seeing it
     * — visible exactly when the addon's view was on screen. Skips a record whose widget is no longer the live one
     * (a relog: the whole old tree is gone, see {@link #stillHidable}). Best-effort; never aborts a teardown.
     */
    private static void restoreHidden(UI u, LuaWidget.Hidden h) {
        if(!stillHidable(u, h))
            return;
        Widget view = h.liveView();
        try {
            h.wdg.show((view != null) && view.visible());
        } catch(RuntimeException e) { /* best-effort: never abort teardown */ }
    }

    /**
     * Drop ONE hide record and apply the same rule — the live undo path ({@code replacer:remove()}, a server
     * destroy), where the addon goes on living and only this window goes back. Gives the toggle back with it.
     */
    private static void releaseHidden(LuaWidget.Hidden h) {
        if(h == null)
            return;
        h.owner.hiddenNative.remove(h);
        LuaWidget.recountHidden();
        UI u = h.wdg.ui;                      // the tree that window stands in, not the one on screen
        if(u != null) {
            synchronized(u) { restoreHidden(u, h); }
        } else {
            restoreHidden(null, h);
        }
    }

    // ---- the window toggle a hidden native window carries (031.1) ------------------------------------

    /**
     * The restore-list entry for this window, or {@code null} if no live addon hid it — the ownership lookup
     * behind the 031 toggle seam. <b>Identity, and only the CURRENT owners.</b> A torn-down addon's list is
     * already cleared ({@link #teardownHidden}) and it is off {@link AddonManager#addons} anyway, so a stale
     * owner simply is not found here and the client's stock behaviour runs — a dead Tab being worse than a stock
     * Tab. The same identity test disposes of 030's other corpse case: a window fading out after
     * {@code reqdestroy} lingers in the tree, but it is not the object {@code GameUI} now holds, so it cannot
     * answer for the live one.
     *
     * <p><b>Allocates nothing and, in the common case, reads one volatile.</b> {@code GameUI.wndstate} is a
     * per-frame {@code state()} supplier on six menu checkboxes, so this is on the frame path: the
     * {@link LuaWidget#anyHidden} fast path returns immediately for a client that hides nothing, and both walks
     * below are indexed over {@link CopyOnWriteArrayList}s (an enhanced-for would allocate an iterator per
     * checkbox per frame).
     */
    static LuaWidget.Hidden hiddenOwner(Widget wnd) {
        if(!LuaWidget.anyHidden || (wnd == null))
            return null;
        List<Addon> as = AddonManager.addons;
        for(int i = 0, n = as.size(); i < n; i++) {
            LuaWidget.Hidden h = hiddenIn(as.get(i), wnd);
            if(h != null)
                return h;
        }
        Addon c = consoleOwner;               // the :lua REPL hides windows too, and owns them the same way
        return (c == null) ? null : hiddenIn(c, wnd);
    }

    /** One owner's restore list, by widget identity. Indexed: no iterator on the frame path. */
    private static LuaWidget.Hidden hiddenIn(Addon a, Widget wnd) {
        List<LuaWidget.Hidden> hs = a.hiddenNative;
        for(int i = 0, n = hs.size(); i < n; i++) {
            LuaWidget.Hidden h = hs.get(i);
            if(h.wdg == wnd)
                return h;
        }
        return null;
    }

    // ---------------------------------------------------------------- taken into a surface of the addon's own

    /** File the home of a widget an addon has just taken ({@code widget:parent(p)}). */
    static void rehomedAdd(LuaWidget.Rehomed r) {
        if(r != null)
            r.owner.rehomedNative.add(r);
    }

    /** This owner's record for {@code w}, or {@code null} — the read behind {@code widget:parent(nil)}. */
    static LuaWidget.Rehomed rehomedIn(Addon a, Widget w) {
        if((a == null) || (w == null))
            return null;
        List<LuaWidget.Rehomed> rs = a.rehomedNative;
        for(int i = 0, n = rs.size(); i < n; i++) {
            LuaWidget.Rehomed r = rs.get(i);
            if(r.wdg == w)
                return r;
        }
        return null;
    }

    /**
     * <b>Whoever</b> holds {@code w}, across every live owner — the one-widget-one-place refusal, the same shape
     * as {@link #hiddenOwner}. Off the frame path entirely: only the verb itself asks.
     */
    static LuaWidget.Rehomed rehomedOwner(Widget w) {
        if(w == null)
            return null;
        List<Addon> as = AddonManager.addons;
        for(int i = 0, n = as.size(); i < n; i++) {
            LuaWidget.Rehomed r = rehomedIn(as.get(i), w);
            if(r != null)
                return r;
        }
        return rehomedIn(consoleOwner, w);      // the :lua REPL takes widgets too, and owns them the same way
    }

    /**
     * Put one taken widget back where the client had it: its parent, its place, and <b>its order among its
     * siblings</b>. Best-effort by construction — the four guards are the ones {@code LuaWidgetEntity.destroyed}
     * pays for the same move: the widget must still be live and in a tree, and that tree must still be the one it
     * came from (after a relog it is not, and the record has nothing to put back), and the recorded parent must
     * still be in it, or the widget goes to the root rather than into a dead frame.
     *
     * <p>What it does <b>not</b> restore is where the widget stands: that is {@link Addon#movedNative}'s, which
     * runs later in the same teardown and therefore has the last word, and is the record that knows what the
     * <i>user</i> had. This one only answers what the widget hangs under.
     */
    static void home(LuaWidget.Rehomed r, boolean drop) {
        if(r == null)
            return;
        if(drop)
            r.owner.rehomedNative.remove(r);
        UI u = r.ui;
        Widget w = r.wdg;
        if((u == null) || (u.root == null) || (w == null) || (w.parent == null) || !w.hasparent(u.root))
            return;
        Widget np = ((r.from != null) && r.from.hasparent(u.root)) ? r.from : u.root;
        try {
            synchronized(u) {
                // THE BOX GOES BACK BEFORE THE WIDGET DOES, and the order is the whole of it. A parent that
                // packs itself around its children measures each one AS IT ARRIVES -- every corner Hidepanel
                // does, in its own `add` -- and it derives its place on screen from the box that comes out.
                // Restore the size afterwards and the panel is left fitted to OUR box, with a gap under it
                // that nothing corrects until the next fold. So this addon's whole layout level is given
                // back first: a place inside a surface it is leaving means nothing anywhere else.
                LuaWidget.Moved m = LuaWidget.findMoved(r.owner, w);
                if(m != null) {
                    restoreMoved(u, m, true, true);
                    r.owner.movedNative.remove(m);
                    LuaWidget.recountMoved();
                }
                WidgetSurface.reparent(u, w, np, new Coord(r.at));   // a copy: haven.Coord is mutable
                LuaWidget.relink(w, r.after);
            }
            Layout.apply(w);      // a sheet rule that still names it resolves again, now in the home parent
        } catch(RuntimeException e) {
            AddonManager.log("a widget could not be put back where the client had it: " + e);
        }
    }

    /**
     * Every widget this addon took, put back — {@code :reload}, disable, and the way out of the client. Runs
     * <b>early</b> in the teardown, beside the standing-widget sweep and for the identical reason: a surface of
     * the addon's own is about to be destroyed, and {@code Widget.destroy} disposes recursively, so a client
     * widget still inside one would go down with it.
     */
    static void teardownRehomed(Addon a) {
        if((a == null) || a.rehomedNative.isEmpty())
            return;
        List<LuaWidget.Rehomed> rs = new ArrayList<LuaWidget.Rehomed>(a.rehomedNative);
        a.rehomedNative.clear();
        for(int i = 0, n = rs.size(); i < n; i++)
            home(rs.get(i), false);
    }

    /**
     * The same rescue for <b>one</b> surface, before an addon destroys it by hand ({@code widget:destroy()}):
     * anything of the client's standing inside it goes home first. Without this, destroying your own panel would
     * take the client's minimap with it — and the client has no way to build another.
     */
    static void homeInside(Widget container) {
        if(container == null)
            return;
        List<Addon> as = AddonManager.addons;
        for(int i = 0, n = as.size(); i < n; i++)
            homeInside(as.get(i), container);
        homeInside(consoleOwner, container);
    }

    private static void homeInside(Addon a, Widget container) {
        if(a == null)
            return;
        List<LuaWidget.Rehomed> rs = a.rehomedNative;
        for(int i = 0, n = rs.size(); i < n; i++) {
            LuaWidget.Rehomed r = rs.get(i);
            Widget w = r.wdg;
            if((w != null) && ((w == container) || w.hasparent(container)))
                home(r, true);
        }
    }

    /**
     * {@code GameUI.togglewnd} asks first (031.1, through {@code haven.AddonWidgets}): has an addon taken this
     * window over? Returns whether the toggle was <b>handled</b> — {@code true} stops the client's own
     * {@code show(!visible())} dead.
     *
     * <p><b>A native window you hid is a window you own.</b> {@code MenuCheckBox} calls {@code setgkey}, so the
     * keybinding and the menu button fire the same click and both land here; without this the client would flip
     * {@code visible} back on the very window the addon hid, which is why the stock inventory used to come back
     * on Tab.
     *
     * <p><b>What the toggle drives is the view</b> (031.2). {@code hafen.ui.replace} binds the widget its builder
     * returned to the hide record, so Tab and the menu button open and close the addon's own window exactly as
     * they would the stock one. With nothing bound — a bare {@code w:hide()}, or a view already destroyed — the
     * toggle is <b>swallowed</b>: the window stays hidden and nothing appears. Either way it is handled.
     */
    static boolean toggleWnd(Window wnd) {
        LuaWidget.Hidden h = hiddenOwner(wnd);
        if(h == null)
            return false;                     // nobody owns it: the client's own toggle runs, unchanged
        Widget view = h.liveView();
        if(view != null)
            toggleView(view);
        return true;
    }

    /**
     * {@code GameUI.wndstate} asks first (031.1): what should the menu checkbox's tick say? {@code null} = not
     * owned, read the window as usual. For an owned window the answer is <b>the view's own visibility</b> (031.2)
     * — no bookkeeping boolean, so the tick cannot drift out of sync with what is on screen — and {@code false}
     * when nothing stands in for it, because the tick must not claim a window the user cannot see.
     */
    static Boolean wndState(Window wnd) {
        LuaWidget.Hidden h = hiddenOwner(wnd);
        if(h == null)
            return null;
        Widget view = h.liveView();
        return ((view != null) && view.visible()) ? Boolean.TRUE : Boolean.FALSE;
    }

    /**
     * Flip the addon's view, doing to it precisely what {@code GameUI.togglewnd} would have done to the window it
     * stands in for: {@code show(!visible())}, and when it comes up, raise it, clamp it back on screen and give it
     * the focus. The clamp is {@code GameUI.fitwdg}'s rule ({@link #fitView}) — that method is private and this
     * feature adds no core call site, so it is applied against the view's OWN parent, which is the more correct
     * frame anyway (a view may hang off {@code ui.root} rather than the HUD). Click path, not the frame path.
     */
    private static void toggleView(final Widget view) {
        synchronized(LuaWidget.monitor(view)) {
            if(!view.show(!view.visible()))
                return;                       // just closed it: nothing to raise or focus
            view.raise();
            fitView(view);
            if(view.parent != null)
                view.parent.setfocus(view);
        }
    }

    /** {@code GameUI.fitwdg}'s off-screen clamp, applied to a widget within its own parent (see {@link #toggleView}). */
    private static void fitView(Widget w) {
        if((w.parent == null) || (w.c == null))
            return;
        w.c = fitc(w, w.c);
    }

    /**
     * <b>{@code GameUI.fitwdg}'s formula</b>, re-derived rather than exposed (031): where {@code c} becomes once
     * the client's own rule that a widget stays graspable — at least {@code min(100 px, its own size)} of it inside
     * the parent — has had its say. The client runs it when it places or toggles a window; 036.3's layout layer
     * runs it on the same widgets for the same reason, so "is this on screen" has <b>one</b> answer and an
     * off-screen rule leaves the window reachable rather than lost. Hands {@code c} straight back when there is
     * nothing to measure against.
     */
    static Coord fitc(Widget w, Coord c) {
        Widget p = w.parent;
        if((p == null) || (p.sz == null) || (w.sz == null) || (c == null))
            return c;
        int marg = UI.scale(100);
        int x = Math.max(c.x, Math.min(0, marg - w.sz.x));
        int y = Math.max(c.y, Math.min(0, marg - w.sz.y));
        return Coord.of(Math.min(x, p.sz.x - Math.min(marg, w.sz.x)),
                        Math.min(y, p.sz.y - Math.min(marg, w.sz.y)));
    }

    /** Is a restore-list entry still the same live widget? (Server-bound: by id; client-only: by tree reachability.) */
    private static boolean stillHidable(UI u, LuaWidget.Hidden h) {
        if((u == null) || (u.root == null))
            return false;
        return (h.id >= 0) ? (u.getwidget(h.id) == h.wdg) : h.wdg.hasparent(u.root);
    }

    // ---- the layout layer over the client's own (036.1, feature E) -----------------------------------

    /**
     * Give back every native widget this addon moved or resized ({@code :reload}/disable), then drop the list.
     * The counterpart of {@link #teardownHidden} one property along, and it names trees the same way: each record
     * is restored in the {@code UI} its widget stands in ({@code m.wdg.ui}), under that tree's own monitor, so a
     * layout an addon held on two characters at once is given back on both. The same guard rides along — a record
     * whose widget is no longer the live one of that tree is skipped, so a relog correctly restores nothing (that
     * tree is gone), while a same-session {@code :reload} puts every widget back.
     *
     * <p><b>The restore is the exact inverse of the write</b>: the position half goes back through
     * {@link Widget#move}, the size half through {@link Widget#resize} with the argument
     * {@link LuaWidget#sizeArg} recorded — which is a window's CONTENT size, so its chrome re-derives exactly the
     * outer box it had. The size goes back <b>first</b>: {@link Widget#resize} tells the parent
     * ({@code parent.cresize(this)}), which is free to re-place the child, so the position must have the last
     * word. Best-effort per record; never aborts a teardown. Tree ops under the {@code ui} monitor.
     *
     * <p><b>Then the cascade is re-run</b> (036.2): this addon's levels are gone, but another addon's rule may
     * still name a widget it was standing on, and that rule has to take it back rather than leave it at stock.
     * Which is why the registry runs this <b>after</b> {@code FontApi.teardownFonts}: the addon's own sheet must
     * have stopped resolving first, or the sweep would put its rules straight back on.
     */
    static void teardownMoved(Addon a) {
        if((a == null) || a.movedNative.isEmpty())    // null: the :lua REPL owner, which exists only once used
            return;
        final List<LuaWidget.Moved> ms = new ArrayList<LuaWidget.Moved>(a.movedNative);
        a.movedNative.clear();
        LuaWidget.recountMoved();
        for(final LuaWidget.Moved m : ms) {
            final UI u = m.wdg.ui;             // the tree that widget stands in, not the one on screen
            if(u != null) {
                synchronized(u) { restoreMoved(u, m, true, true); }
            } else {
                restoreMoved(null, m, true, true);
            }
        }
        Layout.sweep();
    }

    /**
     * Put one record's halves back where the widget was ({@code pos} and/or {@code size}), leaving the record's
     * own fields to the caller. Skips a widget that is no longer the live one ({@link #stillMovable}).
     */
    private static void restoreMoved(UI u, LuaWidget.Moved m, boolean pos, boolean size) {
        if(!stillMovable(u, m))
            return;
        try {
            if(m.text != null)                    // 061.5: what it SAYS goes back first — a caption resizes a
                LuaWidget.writeCap(m.wdg, m.text);   //   Label, and an explicit size level must have the last word
            if(size && (m.size != null))
                m.wdg.resize(m.size);
            if(pos && (m.pos != null))
                m.wdg.move(m.pos);
        } catch(RuntimeException e) { /* best-effort: never abort a teardown or a live undo */ }
    }

    /**
     * The live undo behind {@code widget:position(nil)} / {@code widget:size(nil)}: drop <b>one half</b> of this addon's
     * hand-named layout and let the cascade say what happens next (036.2). It removes a <i>level</i>, it does not
     * empty the layout: a sheet rule that also names this widget takes it back at once, and only when nothing names
     * that half at all does the stock value return and the record's half go with it. A record with nothing left is
     * dropped entirely, which is what puts {@link LuaWidget#anyMoved} back to {@code false} for a client nobody is
     * laying out any more. A widget this addon never touched is a silent no-op — there is nothing of ours on it.
     */
    static void releaseMoved(Addon owner, Widget w, boolean pos) {
        LuaWidget.Moved m = LuaWidget.findMoved(owner, w);
        if(m == null)
            return;
        synchronized(LuaWidget.monitor(w)) {
            if(pos)
                m.wantPos = null;
            else
                m.wantSize = null;
            if(m.idle() && owner.movedNative.remove(m))
                LuaWidget.recountMoved();
        }
        // The fold again, one level shorter — and 112.6: below the block, since a follower of this
        // widget may stand in another tree, which is a second monitor while this one is still held.
        Layout.apply(w);
    }

    /**
     * The live undo behind {@code widget:text(nil)} / {@code widget:title(nil)} (061.5): drop this addon's text
     * level and let {@link Layout#applyText} say what happens next — another addon's level takes the widget back
     * at once, and only when nothing names its caption at all does the stock one return and the record's half go
     * with it. A widget this addon never wrote on is a silent no-op; so is one it built, which carries no level
     * (the write went straight to the control, and there is nothing recorded to give back).
     */
    static void releaseText(Addon owner, Widget w) {
        LuaWidget.Moved m = LuaWidget.findMoved(owner, w);
        if(m == null)
            return;
        synchronized(LuaWidget.monitor(w)) {
            m.wantText = null;
            Layout.applyText(w);                 // the fold again, one level shorter
            if(m.idle() && owner.movedNative.remove(m))
                LuaWidget.recountMoved();
        }
    }

    /**
     * <b>The caption the text level must give back</b> (061.5) — the stock one recorded at the layer's first
     * touch, and the widget's own where no addon is standing on it. {@link #stockPos}'s answer one property
     * along, and it is what makes a second addon's record hold what the <i>user</i> had rather than what the
     * first addon wrote.
     */
    static LuaWidget.Cap stockText(Widget w) {
        LuaWidget.Moved m = movedTextOwner(w);
        return (m != null) ? m.text : LuaWidget.readCap(w);
    }

    /** The first live owner holding a stock caption for {@code w}, or {@code null} ({@link #movedOwner}'s twin). */
    private static LuaWidget.Moved movedTextOwner(Widget w) {
        if(!LuaWidget.anyMoved || (w == null))
            return null;
        List<Addon> as = AddonManager.addons;
        for(int i = 0, n = as.size(); i < n; i++) {
            LuaWidget.Moved m = movedTextIn(as.get(i), w);
            if(m != null)
                return m;
        }
        Addon c = consoleOwner;
        return (c == null) ? null : movedTextIn(c, w);
    }

    private static LuaWidget.Moved movedTextIn(Addon a, Widget w) {
        List<LuaWidget.Moved> ms = a.movedNative;
        for(int i = 0, n = ms.size(); i < n; i++) {
            LuaWidget.Moved m = ms.get(i);
            if((m.wdg == w) && (m.text != null))
                return m;
        }
        return null;
    }

    /**
     * <b>The position {@code GameUI.savewndpos} must persist</b> — what the <i>user</i> last placed, which is the
     * widget's own {@code c} unless an addon's layout is standing on it, and then the stock value recorded at
     * first touch (036.1, through {@code haven.AddonWidgets.stockc}).
     *
     * <p>This is the acceptance criterion the whole feature exists to satisfy: {@code savewndpos} writes
     * {@code wndc-inv}/{@code -equ}/{@code -chr}/{@code -zerg}/{@code -map} through {@code Utils.setprefc} from
     * {@code dispose()} at logout <i>and</i> every 60 s from {@code tick}, so a layer that merely restores at
     * teardown would still have the client save our position as the user's own preference — and uninstalling the
     * addon would leave those windows displaced <b>forever</b>, D-070's discipline defeated by a write to disk.
     * Substituting the value rather than restoring the widget is also what keeps the 60 s tick invisible: nothing
     * on screen moves, only what is written down. <b>An addon's layout is a layer over the client's, never a
     * write into it.</b>
     *
     * <p>First live owner wins, which is the same order the moves themselves happened in. One volatile read for
     * a client no addon has laid out.
     *
     * <p><b>Standing a window in the 3D world is the same layer, and the same trap</b> (044.6): a standing
     * widget's {@code c} is pinned at its surface's own origin ({@code WidgetSurface.tick}), so the client
     * asking a standing window where it is would write {@code 0, 0} down as the user's preference and displace
     * it forever — the exact failure this method exists to prevent, arriving through a different door. The
     * answer is the place the entity recorded when it stood, which is by definition what the user last had.
     * One reference comparison on the widget's own parent, for a question asked six times a minute.
     */
    static Coord stockPos(Widget w) {
        LuaWidget.Moved m = movedOwner(w, true);
        if(m != null)
            return m.pos;
        if(w == null)
            return null;
        if(w.parent instanceof WidgetSurface) {
            LuaWidgetEntity e = ((WidgetSurface)w.parent).ent;
            if(e != null)
                return e.prevPos;
        }
        // ...and the flat twin of that line, for the same reason: a widget an addon has TAKEN into a surface of
        // its own (widget:parent(p)) has a `c` in somebody else's coordinate space, and "what the user had" is
        // not a number from there. Without this the layer's stock is captured wherever the first
        // widget:position(x, y) happens to be written — inside the addon's panel, if that is where the addon
        // placed it — and widget:position(nil) then puts the widget back at a place that never existed.
        LuaWidget.Rehomed r = rehomedOwner(w);
        if(r != null)
            return r.at;
        return w.c;
    }

    /** The size argument {@code savewndpos} must persist ({@code wndsz-map}) — see {@link #stockPos}. */
    static Coord stockSizeArg(Widget w) {
        LuaWidget.Moved m = movedOwner(w, false);
        return (m != null) ? m.size : ((w == null) ? null : LuaWidget.sizeArg(w));
    }

    /** The first live owner holding the asked-for half of a layout record for {@code w}, or {@code null}. */
    private static LuaWidget.Moved movedOwner(Widget w, boolean pos) {
        if(!LuaWidget.anyMoved || (w == null))
            return null;
        List<Addon> as = AddonManager.addons;
        for(int i = 0, n = as.size(); i < n; i++) {
            LuaWidget.Moved m = movedIn(as.get(i), w, pos);
            if(m != null)
                return m;
        }
        Addon c = consoleOwner;               // the :lua REPL lays windows out too, and owns them the same way
        return (c == null) ? null : movedIn(c, w, pos);
    }

    /** One owner's layout list, by widget identity, for the half asked about. Indexed: allocates no iterator. */
    private static LuaWidget.Moved movedIn(Addon a, Widget w, boolean pos) {
        List<LuaWidget.Moved> ms = a.movedNative;
        for(int i = 0, n = ms.size(); i < n; i++) {
            LuaWidget.Moved m = ms.get(i);
            if((m.wdg == w) && ((pos ? m.pos : m.size) != null))
                return m;
        }
        return null;
    }

    /** Is a layout record still the same live widget? (The {@link #stillHidable} test, one list along.) */
    private static boolean stillMovable(UI u, LuaWidget.Moved m) {
        if((u == null) || (u.root == null))
            return false;
        return (m.id >= 0) ? (u.getwidget(m.id) == m.wdg) : m.wdg.hasparent(u.root);
    }

    // ---- widget:revert(): one undo for a whole edit (061.9) ----------------------------------------

    /**
     * <b>Give back everything THIS addon holds on {@code w} and the widgets under it</b>
     * ({@code widget:revert()}, 061.9) — the text level, the position and size levels, the hide record, this
     * addon's {@code widget:rule()} level, every subscription it holds anywhere in that subtree, and every
     * widget it adopted into it, destroyed. Every one of those already has an undo of its own; what this
     * verb adds is undoing them <b>together</b>, at a moment the addon chooses rather than at a
     * {@code :reload}.
     *
     * <p><b>The subtree is the scope, as the tree stands when it is called</b>, because an edit is never
     * confined to one widget: a caption goes on the window, an adopted control hangs under it and the
     * taken-over close button is neither. Per widget, so an addon that edited two windows can give back one.
     *
     * <p><b>Two things it deliberately does not do.</b> A {@code widget:value(v)} is an <i>act</i> — it went
     * to the server as a real interaction, and putting the control back is another interaction rather than an
     * undo — and a {@code widget:replace(view)} is the <i>alternative</i> to editing rather than a kind of it,
     * so a hide record carrying a view is left standing for {@code widget:replace(nil)} to end.
     *
     * <p><b>An adopted widget keeps the death it would have had</b>: it is destroyed through the same
     * {@link Owned#kill()} {@code widget:destroy()} runs, so its own {@code widget:on("Removed", fn)} fires
     * from the removal seam exactly as it would have when the window closed. Which is why this addon's
     * subscriptions are dropped on every OTHER widget of the subtree and not on that one — dropping them
     * there would silence the one notification a revert owes it.
     */
    static void revert(Addon owner, Widget w) {
        if(w == null)
            return;
        List<Owned> adopted = new ArrayList<Owned>();
        synchronized(LuaWidget.monitor(w)) {
            List<Widget> sub = new ArrayList<Widget>();
            collectSubtree(w, sub);           // a snapshot: what follows destroys widgets and writes geometry
            for(int i = 0; i < sub.size(); i++) {
                Widget x = sub.get(i);
                Owned c = (x == w) ? null : LuaWidget.ownedContent(owner, x);
                if(c != null) {
                    if(!standingIn(owner, c))
                        adopted.add(c);       // ours, and going away whole: its Destroy is what says so
                    continue;
                }
                revertLevels(owner, x);
                Gesture.release(owner, x);    // 062: ...and it stops being the user's to drag or resize
                LuaWidget.rememberDrop(owner, x);   // 062: ...and this addon stops remembering where it was —
                                                    //   the BINDING, never the record: a revert gives back what
                                                    //   the addon took, and where the user put a window is not
                                                    //   something it took. widget:remember(nil) deletes one.
                LuaWidget.Hidden h = LuaWidget.findHidden(owner, x);
                if((h != null) && (h.view == null))
                    releaseHidden(h);         // the same rule teardown applies: as the user was SEEING it
                if(owner.skinNodes)
                    Sheet.setWidgetProps(owner, x, null);
                owner.dropWidgetSubs(x);
            }
            for(int i = 0; i < adopted.size(); i++) {
                Owned c = adopted.get(i);
                c.kill();
                dropPending(c);               // adopted and reverted in one statement: never placed at all
                owner.widgets.remove(c);
            }
        }
    }

    /**
     * Is {@code c} one of this addon's <b>stand-in views</b> right now? Then it belongs to a substitution and
     * not to an edit, and a revert that reached it leaves it alone: destroying the view is half of ending a
     * {@code widget:replace(view)} — the worse half, since the window it stands in for would be left hidden
     * with a dead view and its toggle swallowed. {@code widget:replace(nil)} is what ends one.
     */
    private static boolean standingIn(Addon owner, Owned c) {
        List<LuaWidget.Hidden> hs = owner.hiddenNative;
        for(int i = 0, n = hs.size(); i < n; i++) {
            if(hs.get(i).view == c)
                return true;
        }
        return false;
    }

    /** {@code w} and everything under it, in tree order. Caller holds the {@code ui} monitor. */
    private static void collectSubtree(Widget w, List<Widget> out) {
        out.add(w);
        for(Widget c = w.child; c != null; c = c.next)
            collectSubtree(c, out);
    }

    /**
     * Drop this addon's text, position and size levels on one widget and let the cascade say what happens
     * next — {@code widget:text(nil)}, {@code :position(nil)} and {@code :size(nil)} in one pass, so a sheet
     * rule that still names the widget takes it back and only a half nothing names reaches the stock value.
     * A widget this addon never touched is a silent no-op. Caller holds the {@code ui} monitor.
     */
    private static void revertLevels(Addon owner, Widget w) {
        LuaWidget.Moved m = LuaWidget.findMoved(owner, w);
        if(m == null)
            return;
        m.wantText = null;
        m.wantPos = null;
        m.wantSize = null;
        Layout.apply(w);                      // the fold, three levels shorter
        if(m.idle() && owner.movedNative.remove(m))
            LuaWidget.recountMoved();
    }

    // -------------------------------------------------- generic widget-tree introspection (hafen.ui, W1, spec 20)

    /**
     * {@code session:ui():root()} — the {@link LuaWidget} entity for that session's {@code ui.root}, the top of
     * one character's whole widget tree (spec 20, W1). {@code nil} while that session has no tree. From here an
     * addon walks DOWN to any window that character has open — or names one directly with a selector.
     */
    private static LuaValue nodeRoot(Addon owner, UI u) {
        if((u == null) || (u.root == null))
            return LuaValue.NIL;
        return LuaWidget.of(owner, u.root);
    }

    /**
     * {@code session:ui():node(id)} — the {@link LuaWidget} entity for a SERVER widget id (another widget's
     * {@code :id()}, typically), or {@code nil} if the id doesn't resolve (no such server widget in that
     * session's tree, or it was destroyed). <b>A widget id counts inside one tree</b>: the same number names a
     * different widget on the other character, which is why the read goes through the address. A non-number id
     * is a clear error.
     */
    private static LuaValue nodeById(Addon owner, UI u, LuaValue idv) {
        if(!idv.isnumber())
            throw new LuaError(UIS + ":node(id) expects a widget id (number)");
        if(u == null)
            return LuaValue.NIL;
        Widget w = u.getwidget(idv.toint());
        if(w == null)
            return LuaValue.NIL;
        return LuaWidget.of(owner, w);
    }

    /**
     * {@code hafen.ui():hit(x, y)} — the DEEPEST {@link LuaWidget} entity under a root-coord point (spec 20, W2),
     * or {@code nil}. Runs {@link LuaWidget#hitTest} from {@code ui.root} (the point is already in root-local coords),
     * under the {@code ui} monitor so the walk never races tree mutation. Non-number args are a clear error, like
     * {@code s:ui():node}. Interned, so two calls on the same widget answer the SAME value — which is what let
     * {@code :same()} be cut (029.1).
     */
    private static LuaValue nodeHit(Addon owner, LuaValue xv, LuaValue yv) {
        if(!xv.isnumber() || !yv.isnumber())
            throw new LuaError("hafen.ui():hit(x, y) expects numbers");
        UI u = screen();
        if((u == null) || (u.root == null))
            return LuaValue.NIL;
        Widget hit;
        // DESIGN PIXELS in, exactly like widget:position/:size (058.1) — so hafen.ui():hit(m:x(), m:y()) is the
        // widget the pointer is over, and an addon's own hit rectangle is the box it drew.
        synchronized(u) { hit = LuaWidget.hitTest(u.root, Px.in(new Coord(xv.toint(), yv.toint()))); }
        return (hit == null) ? LuaValue.NIL : LuaWidget.of(owner, hit);
    }

    // ------------------------------------------------------ the replacement verb (widget:replace, 032.1)

    /**
     * {@code w:replace()} — the view this addon has standing in for {@code w}'s enclosing window, or {@code nil}.
     * Read through the one {@link LuaWidget.Hidden} record, so it is the field the toggle itself reads: a view the
     * addon destroyed (its own X, a teardown) answers {@code nil}, exactly as the toggle then swallows.
     * Per-addon by construction ({@link LuaWidget#findHidden} looks only at this owner's list), so it never reports
     * another addon's stand-in.
     */
    static LuaValue installedView(Addon owner, Widget w) {
        LuaWidget.Hidden h = LuaWidget.findHidden(owner, LuaWidget.nativeWindowOf(w));
        return (h == null) ? LuaValue.NIL : LuaWidget.of(owner, h.liveView());
    }

    /**
     * {@code w:replace(view)} — put the addon's own {@code view} in place of the native window around {@code w}
     * (032.1). This is 031's internal wiring made into the verb: hide {@link LuaWidget#nativeWindowOf the enclosing
     * window}, take (or join) the single {@link Addon#hiddenNative} record {@code w:hide()} makes, and bind the view
     * to it — from which point the client's own toggle drives the view and the menu tick reads it (D-069/D-070).
     * Nothing new is stored: the verb fills in a field that already exists.
     *
     * <p><b>The refusals are thrown</b> (this is a Lua call, unlike the placement path that can only log), and they
     * are: a value that is not a Widget, a view the addon does not own, a widget with no enclosing window (nothing
     * to stand in for), one of the addon's <i>own</i> windows, and a window another addon already holds — <i>one
     * window, one owner</i>, since the toggle goes with it (031.2). A view that has <b>left the tree</b> is none of
     * them: 125.3 stops the substitution there instead, which is the rule every Widget argument on this surface
     * takes.
     *
     * <p><b>One window, one view.</b> Installing a different view ends the previous substitution and destroys that
     * view, for the same reason every other ending does: a stand-in that stands for nothing is an orphan window over
     * a container it no longer represents. Re-installing the SAME view is a no-op that still re-hides the window.
     */
    static void replaceWith(Addon owner, Widget w, LuaValue viewv) {
        LuaWidget vh = LuaWidget.resolve(viewv);
        if(vh == null)
            throw new LuaError("widget:replace(view) expects a widget YOUR addon created (hafen.ui():window() or"
                + " hafen.ui():widget()) to stand in for the native one — pass nil to undo a replacement. Got "
                + viewv.typename());
        // 125.3: THE VIEW LEFT THE TREE, and that is not a mistake anyone can guard against — the view was built
        // on an earlier step and may have gone since (its own X, a teardown, a relog), with no :exists() of yours
        // able to sit inside the instant between the check and this call. So the substitution STOPS: the window is
        // not hidden, its toggle stays the client's, nothing is bound, and w:replacement() goes on reading nil.
        // Nothing is left half-done by that — unlike the builder direction of :parent(w), the receiver here is one
        // of the client's own windows, and it is left exactly as the user has it.
        //
        // AHEAD OF THE OWNERSHIP REFUSALS, deliberately: whose a view is, and whether it is a surface rather than
        // a control, are facts a handle that has left the tree can no longer answer.
        Widget vw = LuaWidget.live(vh);
        if(vw == null)
            return;                        // ...and widget:replace hands the receiver back whatever this call did
        Owned own = LuaWidget.ownedContent(owner, vw);
        // A SURFACE, never a control (040.1): what stands in for a window is a window of yours, and a lone
        // button inheriting a container's toggle would be a stand-in that stands for nothing.
        AddonWidget view = (own instanceof AddonWidget) ? (AddonWidget)own : null;
        if(view == null)
            throw new LuaError("widget:replace(view) expects a widget YOUR addon created (hafen.ui():window() or"
                + " hafen.ui():widget()) to stand in for the native one — pass nil to undo a replacement.");
        Widget wnd = LuaWidget.nativeWindowOf(w);
        if(!(wnd instanceof Window))
            throw new LuaError("widget:replace(view) — " + LuaWidget.typeName(w) + " is not inside a window, so"
                + " there is nothing to stand in for (no window to hide, and no toggle to inherit). Point at a"
                + " widget inside a client window, or just show your own with hafen.ui():window().");
        if(LuaWidget.ownedContent(owner, wnd) != null)
            throw new LuaError("widget:replace(view) — " + LuaWidget.typeName(wnd) + " is a window your OWN addon"
                + " created; replacing stands in for the CLIENT's windows. Move, resize or destroy yours instead.");
        LuaWidget.refuseSecondOwner(owner, wnd, "widget:replace(view)");
        LuaWidget.Hidden h = LuaWidget.recordHidden(owner, wnd);
        if(h == null)
            return;                        // raced another owner between the check and the record: leave it be
        if(h.view != view) {               // one window, one view: the previous stand-in's substitution has ended
            AddonWidget old = h.view;
            h.view = view;
            destroyView(owner, old);
        }
        synchronized(LuaWidget.monitor(wnd)) { wnd.hide(); }
        assertToggleTarget(owner, w, wnd);
    }

    /**
     * {@code w:replace(nil)} — undo the substitution there and then: the window (and its toggle) go back under the
     * one rule, and the view is destroyed. A widget with <b>no view</b> bound is left alone: a bare
     * {@code w:hide()} is not a replacement, and giving it back is {@code w:show()}'s job.
     */
    static void unreplace(Addon owner, Widget w) {
        LuaWidget.Hidden h = LuaWidget.findHidden(owner, LuaWidget.nativeWindowOf(w));
        if((h == null) || (h.view == null))
            return;
        endReplacement(h);
    }

    /**
     * End one substitution — the shared body of {@code w:replace(nil)} and the server-destroy sweep: give the window
     * and its toggle back under the one rule ({@link #restoreHidden}), then destroy the view.
     *
     * <p><b>The order is load-bearing</b> (the 031.2 lesson): the rule reads the view's visibility, so it must be
     * asked <i>before</i> the view is killed — a destroyed view stands for nothing, and the failure would be silent
     * and always in the plausible direction.
     */
    private static void endReplacement(LuaWidget.Hidden h) {
        AddonWidget v = h.view;
        releaseHidden(h);                  // the rule: the window ends up as the user was SEEING it
        h.view = null;                     // ...and the record has nothing left to drive
        destroyView(h.owner, v);
    }

    /**
     * Destroy a stand-in view and drop it from the addon's owned registry — the view's fate following the
     * substitution. Idempotent ({@link AddonWidget#kill} guards a double kill, which is what lets the undo path,
     * this sweep and a teardown all reach the same view without ordering rules between them) and best-effort, under
     * the {@code ui} monitor like every other tree op.
     */
    private static void destroyView(Addon owner, final AddonWidget v) {
        if(v == null)
            return;
        owner.widgets.remove(v);
        synchronized(LuaWidget.monitor(v)) {
            try { v.kill(); } catch(RuntimeException e) { /* best-effort: never abort a teardown/undo */ }
        }
    }

    /**
     * The widget-removal seam's offer to the replacement substitutions (042.8): {@code w} may be the native
     * window a hide record stands in for, just destroyed by the server (a chest closed, a relog) — <b>ends the
     * substitution</b>, so the record goes and the view dies with it rather than hanging over a container that no
     * longer exists. M1 already fires after the tree has settled, so the record's own two-branch death test
     * ({@link #stillHidable}, reused unchanged inside {@link #endReplacement}'s {@link #releaseHidden}) is already
     * false by the time this runs — nothing here re-derives that, it only says WHICH record to end.
     *
     * <p><b>Keyed on the hide record, not on a model</b> (D-071): a relationship's lifetime is watched on the
     * relationship. {@code w} is checked by identity against each owner's {@code hiddenNative} list — the same
     * lookup {@link #hiddenOwner} uses on the click path — rather than rebuilding a model to poll. A record with no
     * view bound — a bare {@code w:hide()} — is not a substitution and is left alone.
     *
     * <p>Gated on the {@link LuaWidget#anyHidden} volatile the toggle seam already maintains — not a loop to skip
     * any more, but still the cheap "does anything hold a hide record at all" test, so a removal on a client that
     * has never replaced or hidden a window costs one volatile read. The {@code :lua} REPL owner is covered the
     * same way as every loaded addon: replacing a window from the console is the same substitution, held in the
     * same {@code hiddenNative} list. {@link #endReplacement} is idempotent, so this racing an undo or a teardown
     * over the same record is harmless.
     */
    static void dispatchReplacedRemoved(Widget w) {
        if(!LuaWidget.anyHidden)
            return;
        List<Addon> as = AddonManager.addons;
        for(int i = 0, n = as.size(); i < n; i++)
            endReplacedIfHeld(as.get(i), w);
        endReplacedIfHeld(consoleOwner, w);    // the :lua REPL replaces windows too, and owns them the same way
    }

    /** End {@code a}'s substitution over {@code w}, if it holds one. */
    private static void endReplacedIfHeld(Addon a, Widget w) {
        if((a == null) || a.hiddenNative.isEmpty())
            return;
        LuaWidget.Hidden h = hiddenIn(a, w);
        if((h != null) && (h.view != null))
            endReplacement(h);
    }

    /**
     * Check — rather than assume — that the window a replacement just hid is the object {@code GameUI} toggles
     * (031.2): for the main inventory, that the hidden wrapper really is {@code maininv.parent}, which is what
     * {@code togglewnd(invwnd)} is called with. Silent on the normal path, so an in-game replace that logs nothing
     * has proved the identity the seam rests on. It only logs — a wrong wrapper costs the Tab toggle, not the
     * replacement. (Its other case, a widget with no enclosing {@link Window} at all, is a hard refusal in
     * {@link #replaceWith} since 032.2 left the verb as the only caller: the placement path that could merely log
     * went with {@code hafen.ui.replace}.)
     */
    private static void assertToggleTarget(Addon owner, Widget wdg, Widget nativeWin) {
        GameUI g = gui();
        if((g != null) && (wdg == g.maininv) && (nativeWin != g.maininv.parent))
            log(owner, "widget:replace(view): internal — the hidden window is not the main inventory's own"
                + " wrapper, so the client's Tab toggle will not follow this replacement");
    }

    // ============================================ the forced cursor (hafen.ui():mouse():cursor)

    /* The cursor an addon has forced over the whole client, and who forced it. ONE override, not one per
     * addon: there is one pointer, so two addons wanting two pictures on it is a conflict rather than a
     * composition, and the last writer holding it plainly is better than a stack nobody can see the top of.
     * Volatile: written from Lua on the UI thread, read by UI.getcurs on it too, but through a different
     * call chain -- and cleared from teardown, which is not always the same thread. */
    private static volatile Addon cursOwner = null;
    private static volatile String cursName = null;
    private static volatile Indir<Resource> cursRes = null;

    /**
     * The cursor an addon has forced, or {@code null} for "nobody has": the answer {@link UI#getcurs} takes
     * ahead of every widget's own. A resource still loading answers null — the pointer keeps the picture it
     * has for that frame rather than blinking to the default and back — and one that cannot load at all drops
     * the override and says so, because a cursor stuck on a name that resolves to nothing is invisible.
     */
    public static Object forcedCursor() {
        Indir<Resource> ind = cursRes;
        if(ind == null)
            return null;
        try {
            return ind.get();
        } catch(Loading l) {
            return null;
        } catch(RuntimeException e) {
            Addon a = cursOwner;
            String nm = cursName;
            clearCursor();
            if(a != null)
                log(a, "mouse:cursor(\"" + nm + "\"): no such cursor resource — the pointer is back to normal");
            return null;
        }
    }

    /** Drop the override, whoever set it. */
    private static void clearCursor() {
        cursOwner = null;
        cursName = null;
        cursRes = null;
    }

    /** The name currently forced by {@code owner}, or null — a read answers only your own. */
    static String cursorOf(Addon owner) {
        return (cursOwner == owner) ? cursName : null;
    }

    /**
     * {@code m:cursor(name)} — force the pointer's picture, or {@code m:cursor(nil)} to put it back. A short
     * name is one of the client's own under {@code gfx/hud/curs/}; anything with a slash in it is a resource
     * path taken as written.
     */
    static void setCursor(Addon owner, String name) {
        if(name == null) {
            if(cursOwner == owner)     // your own only: dropping somebody else's is not yours to do
                clearCursor();
            return;
        }
        String res = (name.indexOf('/') >= 0) ? name : ("gfx/hud/curs/" + name);
        cursOwner = owner;
        cursName = name;
        cursRes = Resource.local().load(res);
    }

    /** Teardown: an addon that has stopped running does not go on holding the pointer. */
    static void teardownCursor(Addon a) {
        if(cursOwner == a)
            clearCursor();
    }

    /** Any addon currently has a HUD overlay? (Decides whether to queue the per-frame afterdraw.) */
    static boolean anyHudOverlays() {
        for(Addon a : addons)
            if(!a.hudOverlays.isEmpty())
                return true;
        return false;
    }

    /**
     * Paint every addon's HUD overlays. Runs as a one-shot {@link UI.AfterDraw} (re-queued from {@link #tick})
     * after {@code root.draw}, so overlays land ON TOP of the whole HUD. The {@code g} is the full-screen
     * root GOut (absolute screen coords); {@code w,h} are the screen size, in <b>design</b> pixels (058.2) —
     * the pair {@code s:ui():root():size()} answers, and the space every {@code g:} coordinate the painter
     * then writes is read in. On the UI thread (inside UI.draw).
     *
     * <p><b>The list order IS the draw order</b>, and it is the very order
     * {@code hafen.ui():overlay():list()} reports — one list, walked here and censused there, so the two can
     * never disagree.
     */
    static void paintHudOverlays(GOut g) {
        Coord sz = Px.out(g.sz());
        LuaValue w = LuaValue.valueOf(sz.x), h = LuaValue.valueOf(sz.y);
        for(Addon a : addons) {
            if(a.hudOverlays.isEmpty())
                continue;
            // 026.1: bound PER ADDON (it used to wrap the whole loop) — the wrapper now carries the owner of the
            // g:text cache, and a cache is per-addon.
            LuaTable gt = hudGout.bind(g, a);
            try {
                for(HudOverlay o : a.hudOverlays) {
                    LuaValue fn = o.fn;                 // bare until :draw(fn) — an incomplete overlay
                    if(o.active && (fn != null))        //   paints nothing rather than painting badly
                        callLua(a, Addon.C_DRAW, fn, gt, w, h);
                }
            } finally {
                hudGout.unbind();
            }
        }
    }

    /**
     * Paint the overlays attached to ONE gob — called from {@link LuaGobOverlay#draw} with that attrib's own
     * records and the gob's projected screen point {@code sc}. Nothing is matched and nothing is searched here
     * since 038.1: the records are the ones standing on this very gob, and each is painted for its own owner
     * (its {@code g} wrapper carries that addon's text cache, its cost lands on that addon's row).
     *
     * <p>A {@code {draw = fn}} record calls back into Lua through {@link #callLua} (watchdog-armed,
     * error-isolated, CPU-accounted) with the owner's interned {@link LuaGob} object (D-044), so the callback
     * reads the gob LIVE rather than from a per-frame snapshot. A {@code {text = …}} record never enters Lua at
     * all — it is drawn in Java through the same cached text path {@code g:text} uses, so a label costs one
     * rasterisation for its lifetime instead of one per frame. On the UI thread (inside the Render2D pass of
     * {@code UI.draw}).
     */
    static void paintGobOverlays(Gob gob, List<LuaGobOverlay.Attach> recs, GOut g, LuaGOut gwrap, Coord sc) {
        // 058.2: the projected point reaches Lua in DESIGN pixels, because everything the callback then draws
        // from it is read in design pixels — and a record's own :offset(x, y) is written in them, so the label
        // path converts it back on the way to the device-space blit. `sc` itself stays device below this line.
        Coord dsc = Px.out(sc);
        LuaValue sx = LuaValue.valueOf(dsc.x), sy = LuaValue.valueOf(dsc.y);
        for(LuaGobOverlay.Attach o : recs) {
            LuaTable gt = gwrap.bind(g, o.owner);
            try {
                if(o.draw != null)
                    // A gob overlay is drawn into the scene on screen, so its gob is that session's.
                    callLua(o.owner, Addon.C_DRAW, o.draw, gt,
                            LuaGob.of(o.owner, gob.id), sx, sy);
                else
                    gwrap.label(g, o.text, sc.add(Px.in(o.screenOffset())), 0.5, 1.0, o.color);
            } catch(RuntimeException e) {
                /* never throw into the render pass — callLua already isolates a Lua error */
            } finally {
                gwrap.unbind();
            }
        }
    }

    /**
     * Drop every overlay {@code a} attached to any gob, and detach the attrib from the gobs left with nothing
     * ({@code :reload}/disable). This is the ONE place that has to find an addon's overlays across gobs — the
     * state lives on the gob precisely so that nothing else ever sweeps — and it runs at a rare moment.
     * The game's own overlays are untouched.
     *
     * <p><b>Every live session, because an overlay was attached to the object</b> (080.1): the write reached
     * every copy of it, so the undo reaches every copy too, and what an addon left on a thing only a
     * background character has loaded goes with the rest. A session that dropped the gob in between leaves
     * nothing behind — the copy went with it — which looks like a leak and is not.
     *
     * <p><b>One monitor at a time.</b> Mutating a gob's render slots is done under {@code synchronized(ui)}
     * (like {@link #destroyWidgets}) because teardown may run off the UI thread (session bind) — so each
     * session's own monitor is taken for that session's walk and released before the next, and two are never
     * held at once: the tick holds one UI monitor at a time, and that is the direction everything here takes.
     * A session whose {@code UI} has gone is skipped, not a reason to run unguarded.
     *
     * <p>Since 043.3 a record owns nothing but itself — the world kinds left {@code gob:overlay()}, so dropping
     * it from the map IS its end, and what stands in the 3D scene is freed by {@code VirtualApi}'s own per-kind
     * teardowns like any other entity this addon placed.
     */
    static void teardownGobOverlays(Addon a) {
        for(String user : users()) {
            UI u = sessionui(user);
            if(u == null)
                continue;
            synchronized(u) {
                try {
                    for(Gob g : allGobs(user)) {
                        LuaGobOverlay ol = LuaGobOverlay.on(g);
                        if(ol == null)
                            continue;
                        if(!ol.removeOwner(a).isEmpty())
                            LuaGobOverlay.prune(g);
                    }
                } catch(RuntimeException e) {
                    /* best-effort cleanup — a leftover idle attrib draws nothing anyway */
                }
            }
        }
    }

    /**
     * Put every gob {@code a} resized back to its original size, and every gob it hid back into the scene
     * ({@code :reload}/disable) — the twin of {@link #teardownGobOverlays} and, like it, one walk of the
     * object caches at a rare moment. Nothing an addon that is no longer running left distorted stays
     * distorted and nothing it left hidden stays hidden, <b>in any session it wrote in</b>, which is what
     * makes a purely visual write on the game's own objects safe to leave unprotected.
     *
     * <p><b>An object the addon hid is put back by id</b>, because that is where the record is: a gob is
     * drawn or it is not, one object at a time, so who asked for that is held once against the object
     * ({@link GobIntent}) rather than once per copy. The size goes the other way — {@link GobScale} is an
     * attrib, so it carries its own owner on each copy — and the two are read in the same walk.
     *
     * <p>Every live session, for its twin's reason (080.1): {@code gob:scale(k)} lands on every copy of the
     * object, so the sweep that undoes it reads every session's cache — the guarantee is about the object,
     * and an object only a background character has loaded is one of them.
     *
     * <p><b>Widening the walk does not widen what is reverted.</b> A gob scaled by a <i>different</i> addon is
     * untouched in every session alike: the size records who wrote it, and a gob has one size, so teardown
     * reverts only what this addon last set on that copy.
     *
     * <p>Under each session's own {@code synchronized(ui)}, taken and released one at a time like its twin,
     * because teardown may run off the UI thread (session bind) while {@code ctick} rebuilds the state.
     */
    static void teardownGobScales(Addon a) {
        List<Long> hidden = GobIntent.hiddenBy(a);   // 114.3: read BEFORE the record is dropped
        GobIntent.dropOwner(a);   // 092.7: ...including at objects no session holds yet — nothing this addon
        for(String user : users()) {   //   asked for is re-applied to a copy that arrives after it is gone
            UI u = sessionui(user);
            if(u == null)
                continue;
            synchronized(u) {
                try {
                    for(Gob g : allGobs(user))
                        GobScale.revert(g, a);
                    // 114.3: ...and every object it was holding out of the scene is drawn again. By id and
                    // not by a mark on the copy: an object is drawn or it is not, so the record of who asked
                    // lives once against the object rather than once per session that happens to hold it.
                    for(int i = 0, n = hidden.size(); i < n; i++) {
                        Gob g = AddonManager.getgob(user, hidden.get(i).longValue());
                        if(g != null)
                            g.addonvisible(true);
                    }
                } catch(RuntimeException e) {
                    /* best-effort cleanup — a leftover scale is visual only, and dies with the gob anyway */
                }
            }
        }
    }
}
