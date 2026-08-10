package io.brodgar.addon;

import haven.Button;
import haven.Coord;
import haven.GameUI;
import haven.Gob;
import haven.GOut;
import haven.Label;
import haven.Loading;
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
import org.luaj.vm2.lib.ZeroArgFunction;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
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
 * 042.9) and its {@code disappear} firing ({@link #dispatchSelectorRemoved}, 042.9); the per-gob overlay attrib
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

    // -- selector subscriptions (030.2): hafen.ui():on(sel, "appear"|"disappear", fn) — the discovery primitive that
    // replaced onWidgetCreate. A FLAT global list (a subscription watches the whole tree, not one keyed target),
    // consulted at the placement seam and at the removal seam (dispatchSelectorRemoved, 042.9, event-driven —
    // no more per-tick poll); globally empty = a near-zero fast path, so a client with no subscription pays one
    // isEmpty() per widget placement/removal. `pending` is the PLACEMENT-scoped re-check: a widget that matched a
    // selector's structure (role/class, fixed for its life) but not its [title=]/[res=] refiner may simply not
    // have its caption or its resource yet, so it is re-offered — since 042.9, whenever a window's caption changes
    // rather than on a fixed countdown — for up to RECHECK_TICKS re-checks and then dropped. Since 049.3 it is no
    // longer the only path: the caption seam records the WINDOW (`capChanged`) and the drain re-offers that
    // window's whole SUBTREE, because with the descendant combinator a [title=] sits on an ancestor step and what
    // starts matching is a widget below it, possibly one placed long ago. `pending` stays because a [res=] refiner
    // resolves with no event of its own and that sweep is all it has. Both end in the same offer(), so a widget
    // reachable through both fires exactly once (`matched` is the dedup). Cost scales with widget creation and
    // caption changes, not with frames (a per-tick diff of the whole tree was the discarded alternative). Owned
    // copies live on each Addon for teardown; removing the last subscription (or tearing an addon down) clears
    // both queues, or a stalled entry would outlive every listener with nothing left to drain it. Session-scoped.
    // THREADING: the placement seam runs on a Loader thread but inside AddWidget.run's synchronized(ui). The
    // removal seam and the caption re-check both run from AddonManager.tick, on the UI thread under
    // synchronized(ui) (AddonRoot's class doc) — the caption seam itself (Window.chcap) does NOT hold that monitor
    // (UI.java:730-732), which is why it only appends a Widget (markCaptionChanged) rather than walking the tree or
    // calling Lua inline (P5) — so the UI monitor guards every actual reader/writer here and each subscription's
    // `matched` map needs no lock of its own.
    private static final List<LuaSelectorWatch> selectorWatches = new CopyOnWriteArrayList<LuaSelectorWatch>();
    private static final List<PendingMatch> pending = new CopyOnWriteArrayList<PendingMatch>();
    private static final int RECHECK_TICKS =              // how long a late caption/res has to land (in ticks)
        Integer.getInteger("haven.addon.selrecheck", 20).intValue();

    /** One recently-placed widget still awaiting a late {@code [title=]}/{@code [res=]} (030.2's bounded re-check). */
    private static final class PendingMatch {
        final Widget wdg;
        final int id;                 // server widget id, or -1 (client-only) — the two-branch death test
        int ticks = RECHECK_TICKS;

        PendingMatch(Widget wdg, int id) {
            this.wdg = wdg;
            this.id = id;
        }
    }

    // -- WidgetSubs interest registrations (041.4, event-driven since 042.7): widget:on("ItemAdded"/
    // "ItemRemoved"/"Destroy", fn) can only be SEEN at the moment/removal/placement seams (an item is a Widget
    // create/cdestroy, not a uimsg; a widget's death has no engine event of its own), so every WidgetSubs
    // currently subscribed to one of the three lives in this FLAT list — offered every placement and removal
    // (dispatchWidgetSubsPlaced/Removed) instead of diffed every tick — hasSub-GATED by construction (WidgetSubs
    // registers/unregisters itself, see its Idle hook), so a widget nobody subscribed to costs nothing and an
    // idle client pays one isEmpty(). Owned copies live on each Addon's widgetSubs map for teardown.
    // Session-scoped (cleared per init; the tree is rebuilt).
    private static final List<WidgetSubs> widgetSubsWatching = new CopyOnWriteArrayList<WidgetSubs>();

    /** {@link WidgetSubs#on}: the first tree-key ({@code ItemAdded}/{@code ItemRemoved}/{@code Destroy})
     *  subscription on a widget joins the flat watch list. */
    static void registerInterest(WidgetSubs s) {
        widgetSubsWatching.add(s);
    }

    /** {@link Subs.Idle}, or {@link WidgetSubs#offerRemoved} on the watched widget's own death. */
    static void unregisterInterest(WidgetSubs s) {
        widgetSubsWatching.remove(s);
    }

    /**
     * The widget-placement seam's offer to every watching {@link WidgetSubs} (042.7): a new {@code WItem} may
     * have just entered one of their subtrees. Fast-paths out when nobody is watching, the normal case.
     */
    static void dispatchWidgetSubsPlaced(Widget w) {
        if(widgetSubsWatching.isEmpty())
            return;
        for(WidgetSubs s : widgetSubsWatching) {   // copy-on-write: a firing handler may (un)subscribe here
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
    static void dispatchWidgetSubsRemoved(Widget w) {
        if(widgetSubsWatching.isEmpty())
            return;
        for(WidgetSubs s : widgetSubsWatching) {   // copy-on-write: a firing handler may (un)subscribe here
            try {
                s.offerRemoved(w);
            } catch(RuntimeException e) {
                log("widget-subs removed error: " + e);
            }
        }
    }

    // ===== the widget-placement seam (the body behind AddonManager.onWidgetPlaced) =====
    // ONE consumer since 032.2: the 030.2 selector subscriptions, which see the LIVE widget itself. The
    // {id,type,place,caption,parentType} descriptor that used to be built here for hafen.ui.replace went with it —
    // and with it the NewWidget seam that recorded the server type string, since nothing reads it any more.
    // Second consumer since 042.1 (dispatchPlaced, added in AddonManager.onWidgetPlaced itself); third since
    // 042.7's dispatchWidgetSubsPlaced above.
    static void onWidgetPlaced(int id, Widget wdg) {
        if(!selectorWatches.isEmpty())
            offerPlaced(wdg, id);
        if(Sheet.anyLayout)               // 036.2: a layout rule reaches a window the moment it opens, not a frame
            Layout.placed(wdg, id);       //   later — and never at the draw (035.1's chdeco lesson)
        dispatchWidgetSubsPlaced(wdg);
    }

    /**
     * Build {@code hafen.ui} for {@code owner}. From installHafen.
     *
     * <p><b>The section, and the root that moved</b> (spec {@code 039-uniform-api} §2.1). {@code hafen.ui()} was
     * the ROOT widget — the no-argument form of a callable namespace — and is now the section object, so the tree's
     * top is {@code hafen.ui():root()} and every lookup is a colon verb on the section. The collision is why the
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
        LuaTable m = new LuaTable();
        // hafen.ui():on(selector, "appear"|"disappear", fn) — 030.2: WATCH the client's own UI for a part of it, named
        // with the same selector a lookup uses. fn(widget) receives the Widget ENTITY (the very value hafen.ui(sel)
        // would hand back, interned — so `==` and your own tables work across the two events). This is the discovery
        // primitive: hafen.ui.onWidgetCreate and its {id,type,place,caption,parentType} descriptor are HARD CUT, and
        // with them the second vocabulary an addon had to learn to say "wait for the cupboard".
        //   appear     -- a matching widget was PLACED into the tree, or was ALREADY there when you subscribed
        //                 (registration scans the live tree once — which is exactly what onWidgetCreate could not
        //                 do, and why a :reload used to lose every window already open).
        //   disappear  -- a widget that had matched is GONE, where GONE means the server destroyed it (its id stops
        //                 resolving) -- the moment it stops being REAL, not the moment it stops being DRAWN: a
        //                 Window only starts a fade-out in reqdestroy(), so it lingers in the tree, unbound and
        //                 still readable, for the length of that animation. Take the entity as a KEY to match (==)
        //                 against what you kept at appear; do not count on being able to read it.
        // Both are about the TREE, not visibility: a window the client merely hides (the inventory's Tab toggle)
        // never left, so it fires neither. One event per call — subscribe twice to watch both. A [title=]/[res=]
        // selector still fires exactly once for a window whose caption lands late — and that holds for a CHAIN
        // ("window[title=Cupboard] inventory"), whose caption lands on the ANCESTOR step: the caption seam
        // re-offers the renamed window's whole subtree (049.3). Returns a handle with :remove(); auto-removed
        // on reload/disable (P2), which fires nothing — a reload is not a destroy.
        m.set("on", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "ui", "on");
                LuaValue sel = Args.required(a, 2, "hafen.ui():on", "selector");
                LuaValue event = Args.required(a, 3, "hafen.ui():on", "event");
                LuaValue fn = Args.required(a, 4, "hafen.ui():on", "fn");
                return newSelectorWatch(owner, sel, event, fn);
            }
        });
        // hafen.ui.adopt(id) is GONE (029.2). It only ever existed to get a readable handle on a native widget, and
        // it charged you a hidden window for the privilege. Now every widget IS an entity: hafen.ui.node(id) hands
        // you the same one WITHOUT hiding anything, and widget:visible(false) (which records the restore, see below) is the
        // separate, explicit act it always should have been.
        // hafen.ui.replace(type, opts, fn) is GONE too (032.2, hard cut — it reads as plain nil). It was the LAST
        // place that named a window a different way: its {id,type,place,caption,parentType} descriptor (D-024) was
        // a second vocabulary for "which window", and it was PRIVILEGED — only it could bind a view to a hidden
        // native window, so an addon doing the same by hand got a swallowed toggle and nothing driving it. Both
        // halves are ordinary API now: hafen.ui.on(sel, "appear", fn) does the WAITING (and fires for what is
        // already open, D-068), and widget:replace(view) does the REPLACING. The whole pattern is
        //     hafen.ui.on("inventory[title=Inventory]", "appear", function(w) w:replace(buildMyView(w)) end)
        // hafen.ui():find(sel) / :all(sel) / :root() / :node(id) / :at(x,y) — the widget-tree entry points (spec 20
        // W1/W2, rebuilt on the ONE entity by 029-widget-oop). Walk ANY widget's children to arbitrary depth from Lua
        // (the generic reader that complements the spec-14 typed adapters). Entry points for distinct inputs (D-012):
        // :find(selector) = THE widget matching a selector string, or nil — and since 049.2 an ERROR when two or
        // more match, because "the first in tree order" is a wrong answer in place of no answer; :all(selector) =
        // every match as a 1-based array (empty, never nil); :root() = the ROOT of the whole client tree (discovery,
        // walk DOWN to any open window — it is a VERB now, because hafen.ui() is the section, 039.5);
        // node(id) = the Widget object for a SERVER widget id (another widget's :id(), typically) — nil if it
        // doesn't resolve. All of them hand back the SAME type: opaque, facade-safe
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
        //   :position()      -- {x=,y=} position within the parent (widget-local PX -- not a Position, §2.7)
        //   :size()          -- {x=,y=}
        //   :visible()       -- boolean (and :visible(b) writes it, 039.5)
        //   :text()          -- best-effort text for text-bearing widgets (Label/Button/Window/TextEntry), else nil
        //   :exists()        -- is it still in the tree? (the one read that always answers)
        //   :info()          -- the snapshot escape hatch {type,role,res,id,pos,size,visible,text,owned}
        //   :walk(fn)        -- depth-first: fn(widget, depth); return false to PRUNE the subtree
        //   :find(selector)  -- 049.2: the same search, scoped to THIS widget's subtree (inclusive) — the only
        //                       correct lookup inside an :on(sel, "appear", fn) callback, where the root-anchored
        //                       form would re-find whichever matching window it met first. Strict, like the
        //                       section's own :find. :all(selector) is its collection form (empty, never nil).
        //   :at(coord)       -- W2: the DEEPEST Widget object under a {x=,y=} root-coord point WITHIN this subtree
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
        //                       hafen.ui():on(sel, "appear", fn) — that half is not part of the verb.
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
        m.set("root", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Section.self(self, "ui", "root");
                return nodeRoot(owner);
            }
        });
        m.set("find", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "ui", "find");
                LuaValue sel = Args.required(a, 2, "hafen.ui():find", "selector");
                return selectFirst(owner, selArg(sel, "hafen.ui():find(selector)"));
            }
        });
        // :all(selector) — EVERY widget matching the selector, as a 1-based array in tree order (empty,
        // never nil). One walk of the tree testing each node, not a deep helper per node (that is O(n²)); the
        // selector is parsed ONCE here, never per node. HOLD the result — entities are interned, so keeping it is
        // free, while re-selecting every frame is a whole tree walk every frame.
        m.set("all", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "ui", "all");
                LuaValue sel = Args.required(a, 2, "hafen.ui():all", "selector");
                return selectAll(owner, selArg(sel, "hafen.ui():all(selector)"));
            }
        });
        m.set("node", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "ui", "node");
                return nodeById(owner, Args.required(a, 2, "hafen.ui():node", "id"));
            }
        });
        // hafen.ui():mouse() / :at(x,y) — W2 hit-testing, the WoW /framestack enabler (spec 20 §W2, D-042).
        // mouse() is the POINTER ENTITY (041.5, LuaMouse) — :x()/:y() the cursor in root coords (public UI.mc),
        // :over() the deepest Widget under it, :shift()/:ctrl()/:alt() the live modifiers, :grab() a modal drag
        // capture — a per-addon singleton like hafen.player(), not a {x=,y=} table any more. at(x,y) = the
        // DEEPEST Widget object under an ARBITRARY root-coord point, or nil (not absorbed into the mouse: it
        // takes any point). at() MIRRORS the engine's own pointer dispatch (PointerEvent.propagation): it
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
        // :inventory() / :equipment() — 029.3, what replaced the hafen.items section (hard cut). They are
        // LOOKUPS, not a section of their own: they hand back the Widget entity for the player's backpack
        // (GameUI.maininv) and Equipory, so the items are read the same way as any other container's —
        // hafen.ui():inventory():items() — and every other widget verb answers on them too. nil before the HUD
        // is up.
        //   048.2: :hand() has LEFT. The cursor is not a container and never was a widget lookup — it is the
        // one place that carries a verb of its own (apply what you are holding), so it became an object on the
        // character it belongs to: hafen.player():hand(), nil while the cursor is empty, and the Item is
        // hafen.player():hand():item(). Retired names its replacement.
        m.set("inventory", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Section.self(self, "ui", "inventory");
                return LuaWidget.of(owner, CharApi.maininv());
            }
        });
        m.set("equipment", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Section.self(self, "ui", "equipment");
                return LuaWidget.of(owner, CharApi.equipory());
            }
        });
        m.set("at", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "ui", "at");
                LuaValue x = Args.required(a, 2, "hafen.ui():at", "x");
                LuaValue y = Args.required(a, 3, "hafen.ui():at", "y");
                return nodeAt(owner, x, y);
            }
        });
        // :tipAt(x, y) — 044.5: the Widget whose TOOLTIP the client would show at a root-coord point, or nil.
        // A sibling of :at(x, y) and a different question: :at answers what is under the point, this answers who
        // would speak for it, which is not always the same widget (a tooltip is inherited from whatever ancestor
        // carries one). The text is w:tooltip() on what comes back. It resolves the way the client itself does,
        // panels standing in the 3D world first — which is what makes it the read that says a tooltip on a
        // standing widget is the standing widget's, not the map's.
        m.set("tipAt", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "ui", "tipAt");
                LuaValue x = Args.required(a, 2, "hafen.ui():tipAt", "x");
                LuaValue y = Args.required(a, 3, "hafen.ui():tipAt", "y");
                if(!x.isnumber() || !y.isnumber())
                    throw new LuaError("hafen.ui():tipAt(x, y) expects numbers");
                UI u = ui;
                if((u == null) || (u.root == null))
                    return LuaValue.NIL;
                Widget from = LuaWidget.tipAt(u, new Coord(x.toint(), y.toint()));
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
        // entry:text() is retired, throwing and naming :value(). :onChange(fn) fires on every keystroke and
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
        // :list() — 040.9, the first of the MODEL-BACKED five: a real haven.SListBox. :rows(t) is a plain Lua
        // array -- a string becomes a text row, {icon=, text=} an icon+text row, and a table may mix both
        // freely -- built through LuaRows, the bridge the later model-backed controls (040.10's dropdown/menu,
        // 040.12's table) reuse rather than re-deriving. :value()/:value(v) is the selected row -- the SAME
        // Lua value :rows(t) was given, so it can be handed straight back to :value(v) or compared with == --
        // :onChange(fn) fires on a real pick only, and :rowHeight(n) -- defaulting to the client's own label
        // height -- is building-only like a face setter, since the engine fixes a row-list's item height at
        // construction.
        m.set("list", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "ui", "list");
                return Controls.list(owner, a);
            }
        });
        // :dropdown() — 040.10, the second of the MODEL-BACKED five: a real haven.SDropBox, closed until
        // clicked, over the same LuaRows bridge :list() uses. :rows(t)/:value()/:value(v)/:onChange(fn) answer
        // exactly as they do on :list() -- the same spine, a different engine class underneath.
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
        // :overlay() — paint on top of the HUD without owning a widget: :onDraw(fn) runs fn(g, w, h) every frame
        // with the shared GOut wrapper and the screen size, in absolute screen coords. It MINTS one rather than
        // handing back a collection, which is the one place `overlay` is a builder and not a set — gob:overlay()
        // and hafen.map():overlay() are collections because their members have keys (an overlay key, a tag), and
        // a HUD painter has none: there is nothing to :get(). It ends with :destroy(), like the other two things
        // this section builds; teardown on reload/disable drops it either way (P2).
        m.set("overlay", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "ui", "overlay");
                if(Args.passed(a, 2))
                    throw new LuaError("hafen.ui():overlay() takes no arguments — the painter is a setter on the"
                        + " overlay it hands back: hafen.ui():overlay():onDraw(fn)");
                return newHudOverlay(owner);
            }
        });
        // :sheet() — 033.1 feature C1a, reshaped into objects by 039.7: THE STYLESHEET. It says what the client
        // looks like, as a SELECTOR (the very string hafen.ui():find takes — one vocabulary, not two) naming a
        // RULE whose properties are setters:
        //     local s = hafen.ui():sheet()
        //     s:rule("*"):font(body)
        //     s:rule("window.title"):font(body:derive{ size = 14 })
        //     s:install()                       -- ...and s:drop()
        // An addon has exactly ONE sheet, handed back by identity. :install() applies what it says, replacing
        // whatever this addon had installed WHOLE (a site the sheet no longer names falls back); an edit to an
        // installed sheet applies at once; :drop() takes it off, and a :reload/disable drops it too — the stock
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
        //   over the site half per property. A malformed key errors exactly as hafen.ui():find(sel) does.
        //   Conflict between addons is D-043 reused literally: last applied wins, an addon's entries are pulled on
        //   its teardown, the surface falls back to the next owner beneath and finally to stock.
        // Properties, one setter each: `font` (a handle from hafen.font(name) or hafen.asset(path), optionally
        // :derive{size=,bold=,…}), `color` (r, g, b[, a] — or a colour value read back from the API), and the
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
        // hafen.ui(selector) is :find(selector) and the bare hafen.ui() was the root, now :root() — and neither
        // could survive as the call itself, since a section object is not a member of the tree it addresses.
        // 039.7: `skin` was the last verb still a plain field on the callable table, so the table is empty and
        // this is the plain mount again.
        Section.install(hafen, "ui", m,
                        "hafen.ui(selector) is now hafen.ui():find(selector), and the tree's own top,"
                        + " which the bare hafen.ui() used to be, is hafen.ui():root()");
    }

    // ------------------------------------------------------------------ selectors (hafen.ui(sel), 030.1)

    /**
     * A selector argument &rarr; a parsed {@link Selector}, or a clear error. The number check comes BEFORE
     * {@code isstring()} because in LuaJ a number IS a string (the {@code hafen.asset} lesson, 028).
     */
    static Selector selArg(LuaValue v, String where) {
        if(v.isnumber())
            throw new LuaError(where + ": the argument is a SELECTOR string (e.g. \"window[title=Cupboard]\"),"
                + " not a number — hafen.ui.node(id) is the one that takes a widget id");
        if(!v.isstring())
            throw new LuaError(where + " expects a selector string (e.g. \"*\", \"inventory\","
                + " \"@Equipory\", \"window[title=Cupboard]\"), got " + v.typename());
        return Selector.parse(v.tojstring());
    }

    /**
     * {@code hafen.ui():find(selector)} — <b>THE</b> widget matching {@code sel}, or {@code nil}, and (049.2) a
     * <b>refusal</b> when two or more match. It used to be "the first in tree order", which is a wrong answer in
     * place of no answer the moment a second window matches: an addon that reached the Close button of "the" Foo
     * window kept working right up to the day the player opened a second Foo, and then quietly clicked the other
     * one. So the walk no longer short-circuits — it collects every match and says how many there were, which is
     * the rule {@link LuaWidget#role} beside it has always followed.
     *
     * <p>The price is the whole tree on every call (0.08 ms / 625 widgets, measured 030.1), which is nothing once
     * per event and a real slice of the frame budget sixty times a second — so <i>hold your result</i> stopped
     * being advice and became load-bearing.
     */
    private static LuaValue selectFirst(Addon owner, Selector sel) {
        UI u = ui;
        if((u == null) || (u.root == null))
            return LuaValue.NIL;
        List<Widget> hits = new ArrayList<Widget>();
        synchronized(u) { collect(u.root, sel, hits); }
        return one(owner, hits, sel, "hafen.ui():");
    }

    /**
     * {@code hafen.ui.all(selector)} — every match as a 1-based Lua array in tree order; <b>empty, never nil</b>
     * (the collection form always answers). ONE pre-order walk testing each node — never {@link Widget#children}
     * per node, which is a deep traversal and would make this O(n²) (the 029.4 lesson). The whole walk runs under
     * the {@code ui} monitor, so it never races tree mutation, and the matcher calls no Lua.
     */
    private static LuaValue selectAll(Addon owner, Selector sel) {
        UI u = ui;
        if((u == null) || (u.root == null))
            return new LuaTable();
        List<Widget> hits = new ArrayList<Widget>();
        synchronized(u) { collect(u.root, sel, hits); }
        return table(owner, hits);
    }

    /**
     * {@code widget:find(selector)} — the same search from {@code scope} instead of {@code ui.root}, and just as
     * strict. The scope decides which widgets are <b>candidates</b> (this one and everything under it); the
     * selector is still matched against the whole tree, so an ancestor step may name a widget <i>above</i> the
     * scope — exactly what {@code element.querySelector} does in CSS.
     */
    static LuaValue scopedFind(Addon owner, Widget scope, Selector sel) {
        UI u = ui;
        if(u == null)
            return LuaValue.NIL;
        List<Widget> hits = new ArrayList<Widget>();
        synchronized(u) { collect(scope, sel, hits); }
        return one(owner, hits, sel, "widget:");
    }

    /** {@code widget:all(selector)} — every match inside {@code scope} (inclusive), 1-based; empty, never nil. */
    static LuaValue scopedAll(Addon owner, Widget scope, Selector sel) {
        UI u = ui;
        if(u == null)
            return new LuaTable();
        List<Widget> hits = new ArrayList<Widget>();
        synchronized(u) { collect(scope, sel, hits); }
        return table(owner, hits);
    }

    /**
     * The strict answer of a {@code find} door: {@code nil} for no match, the widget for exactly one, and an error
     * for two or more that says <b>how many</b> and hands back the two spellings that do have an answer — the
     * collection with an index, and (since the chain exists) a selector that names the one widget exactly.
     * {@code door} is the receiver the caller wrote, so the message quotes {@code hafen.ui():all(…)} or
     * {@code widget:all(…)} rather than a form the reader was not using.
     */
    private static LuaValue one(Addon owner, List<Widget> hits, Selector sel, String door) {
        if(hits.isEmpty())
            return LuaValue.NIL;
        if(hits.size() > 1)
            throw new LuaError(door + "find(\"" + sel.src + "\") matches " + hits.size() + " widgets, so there is no"
                + " ONE widget to hand back — name the one you mean (a chain reaches an exact nested widget:"
                + " \"window[title=Foo] button[text=Close]\"), search inside a single widget with"
                + " widget:find(selector), or take one by index with " + door + "all(\"" + sel.src + "\")[i]");
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

    /** Session init: drop every per-session widget record (from AddonManager.init). */
    static void resetSession() {
        widgetSubsWatching.clear();    // 041.4: last session's widgets are gone; nothing left to watch
        selectorWatches.clear();      // 030.2: the tree of the session just ended; nothing matches any more
        pending.clear();
        capChanged.clear();           // 042.9/049.3: and no re-check is owed to a tree that no longer exists
        if(consoleOwner != null) {
            consoleOwner.hiddenNative.clear();   // 029.2: last session's widgets are gone; nothing left to restore
            consoleOwner.movedNative.clear();    // 036.1: ...nor is there anything left to put back where it was
            consoleOwner.widgetSubs.clear();     // 041.3/041.4: ...and so is every widget:on() subscription
            consoleOwner.selectorWatches.clear();// 030.2: ...and the selectors it was watching for
        }
        resetPending();                          // 039.6: ...and nothing built for the old tree is waiting to be placed
        LuaWidget.recountHidden();               // 031.1: nothing is hidden in a session that has not started
        LuaWidget.recountMoved();                // 036.1: ...and nothing is laid out in one either
        Layout.resetSession();                   // 036.2: ...and no widget of the old tree is awaiting its caption
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

        final AddonWidget content = new AddonWidget(owner, Coord.of(DEF_W, DEF_H));
        final Widget rootw;
        if(window) {
            // ANONYMOUS on purpose: the chrome must skip its own draw while the content is unarmed, and
            // LuaWidget.typeName climbs past an anonymous subclass — so w:type() still reads "Window" and every
            // selector, deco and toggle that names one keeps matching. A named subclass would rename the widget.
            final Window win = new Window(Coord.of(DEF_W, DEF_H), "") {
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

    /** The UI, or a clear error naming the builder that has nothing to attach to yet. */
    static UI requireUi(String what) {
        UI u = ui;
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
     * and {@link LuaWidget#ownedContent} derives OWNED from the tree, so {@code hafen.ui():at(x, y)} over this
     * same widget hands back this very value.
     */
    static LuaValue attach(UI u, Addon owner, Owned c) {
        Widget rootw = c.rootw();
        rootw.c = Coord.of(DEF_X, DEF_Y);   // the client's own default place, movable before it is ever painted
        u.root.add(rootw);                  // add() locks on ui; :parent(w) re-homes it while it is still pending
        owner.widgets.add(c);
        synchronized(unarmed) { unarmed.add(c); }
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
        UI u = ui;
        Widget oldw = old.rootw(), neww = neu.rootw();
        synchronized(u) {
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
        synchronized(unarmed) { unarmed.add(neu); }
        owner.widgetObjs.rekey(oldw, neww);   // the Lua handle follows the widget it names...
        owner.styleRules.rekey(oldw, neww);   // ...and so does the Rule object interned on it...
        Sheet.rekeyWidget(oldw, neww);        // ...and the level that rule installed
    }

    // ---------------------------------------------------- the arming tick (039.6, spec 039-uniform-api §2.5)

    /** The client's own defaults for a bare surface: what a window is before any setter touches it. */
    private static final int DEF_W = 200, DEF_H = 140, DEF_X = 100, DEF_Y = 100;

    /** Surfaces and controls built since the last tick and not yet drawing. Drained on the UI thread only. */
    private static final List<Owned> unarmed = new ArrayList<Owned>();

    /**
     * Arm every surface built since the last tick — the "arming tick" of §2.5, called first thing from
     * {@link AddonManager#tick(double)}. Until this runs, a built widget answers every read and takes every
     * setter, and paints nothing.
     *
     * <p><b>Attached inert, rather than held out of the tree</b> — D-112's answer, one level up. Deferring the
     * <i>attach</i> was the other candidate and is worse: it would silently break "find the widget I just
     * built" ({@code hafen.ui():at}, {@code :all}, a selector subscription), which is a capability, to buy a
     * guarantee about painting that skipping the draw already gives in full. What the draw skips is the
     * <b>whole</b> surface, chrome included, which is why {@link #newUi} builds an anonymous {@code Window}.
     */
    static void armPending() {
        if(unarmed.isEmpty())
            return;
        List<Owned> due;
        synchronized(unarmed) {
            due = new ArrayList<Owned>(unarmed);
            unarmed.clear();
        }
        for(Owned c : due)
            c.armed();
    }

    /** Drop a surface from the arming queue (destroyed, or torn down, before it ever painted). */
    static void dropPending(Owned c) {
        synchronized(unarmed) { unarmed.remove(c); }
    }

    /** Reset the arming queue for a new session (nothing built for the old tree is armed in the new one). */
    static void resetPending() {
        synchronized(unarmed) { unarmed.clear(); }
    }

    // ------------------------------------------------------------- custom UI overlays (hafen.ui, 2b)

    /**
     * Build a HUD overlay ({@code hafen.ui():overlay()}, spec 07 / 039.6): a bare painter, its draw callback
     * installed by {@code :onDraw(fn)}. Bridge-owned (P2) — added to the addon's registry so reload/disable
     * drops it. A bare overlay paints nothing, which is the same "incomplete draws nothing" rule the widget
     * builder gets from not being in the tree.
     */
    private static LuaValue newHudOverlay(final Addon owner) {
        final HudOverlay ov = new HudOverlay(owner);
        owner.hudOverlays.add(ov);
        return LuaHudOverlay.of(ov);
    }


    // ------------------------------------------------- selector subscriptions (hafen.ui.on, 030.2)

    /**
     * Register a selector subscription ({@code hafen.ui.on(selector, "appear"|"disappear", fn)}): parse the selector
     * ONCE, install the {@link LuaSelectorWatch} in the global list (consulted at the placement seam and the removal
     * seam, event-driven since 042.9 — no per-tick sweep) and in the addon's owned-resource registry (dropped on
     * reload/disable, P2), then SCAN the live tree once so an already-open target is not missed. Returns the Lua
     * handle ({@code :remove()}).
     */
    private static LuaValue newSelectorWatch(final Addon owner, LuaValue selv, LuaValue eventv, LuaValue fn) {
        final String where = "hafen.ui.on(selector, event, fn)";
        Selector sel = selArg(selv, where);
        if(!eventv.isstring())
            throw new LuaError(where + ": event must be \"appear\" or \"disappear\", got " + eventv.typename());
        int ev = LuaSelectorWatch.eventCode(eventv.tojstring());
        if(ev < 0)
            throw new LuaError(where + ": \"" + eventv.tojstring() + "\" is not an event — the events are"
                + " \"appear\" (a matching widget entered the tree, or was already in it) and \"disappear\""
                + " (one that had matched left it)");
        if(!fn.isfunction())
            throw new LuaError(where + " expects a handler function fn(widget)");
        final LuaSelectorWatch w = new LuaSelectorWatch(owner, sel, ev, fn);
        selectorWatches.add(w);
        owner.selectorWatches.add(w);
        scanForWatch(w);                       // catch what is ALREADY open (the :reload / subscribe-in-world case)
        LuaTable handle = new LuaTable();
        handle.set("remove", new ZeroArgFunction() {
            public LuaValue call() {
                removeSelectorWatch(owner, w);
                return LuaValue.NIL;
            }
        });
        return handle;
    }

    /**
     * Sweep the live tree once for widgets this subscription already matches — the {@code :reload} case, and the
     * ordinary one of subscribing while the game is running. This is the difference between a discovery primitive
     * and a creation feed: {@code onWidgetCreate} could never fire for a widget that already existed, so an addon
     * that only watched creations lost every window open at the moment it was edited. An {@code appear}
     * subscription is called back here, inside its own registration (the {@code replace} precedent); a
     * {@code disappear} one records silently, which is what lets a later close still fire.
     */
    private static void scanForWatch(LuaSelectorWatch w) {
        UI u = ui;
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
     * {@code AddWidget.run}'s {@code synchronized(ui)} block). A full match fires {@code appear} at once. A widget
     * that matches only the STRUCTURE of a selector carrying a {@code [title=]}/{@code [res=]} refiner is queued for
     * the bounded re-check instead: role and class are fixed for a widget's life, but a caption arrives by
     * {@code uimsg} and can land a tick or two after placement, and a {@code .res} window would otherwise be
     * unmatchable by the very key that identifies it.
     */
    private static void offerPlaced(Widget wdg, int id) {
        boolean recheck = false;
        for(LuaSelectorWatch w : selectorWatches) {   // copy-on-write: a handler may subscribe/remove here
            if(!w.alive || w.matched.containsKey(wdg))
                continue;
            if(w.sel.matches(wdg))
                record(w, wdg, id);
            else if(w.sel.late() && w.sel.matchesStructure(wdg))
                recheck = true;
        }
        if(recheck)
            pending.add(new PendingMatch(wdg, id));
    }

    /**
     * Record a match on one subscription and, for an {@code appear} one, fire it. The tracked set is what keeps the
     * bounded re-check from firing twice for the same widget, and what a {@code disappear} subscription later reads
     * — so both events record, only one calls Lua. The payload is the interned Widget entity, the same value every
     * other {@code hafen.ui} door hands back (029.1), so {@code ==} identifies it across the two events.
     */
    private static void record(LuaSelectorWatch w, Widget wdg, int id) {
        w.matched.put(wdg, Integer.valueOf(id));
        if(w.event == LuaSelectorWatch.APPEAR)
            callLua(w.owner, Addon.C_WIDGET, w.fn, LuaWidget.of(w.owner, wdg));
    }

    /**
     * Offer a removed widget to every selector subscription (from {@link AddonManager#drainRemovedWidgets}, M1 seam
     * 042.9). Check whether it was matched and, for {@code disappear} subscriptions, fire the event. Also remove it
     * from pending if it's there (042.9: a widget that dies before its caption arrives is dropped from the bounded
     * re-check). Deaths are processed immediately as widgets are removed, not batched and polled — cost scales with
     * widget REMOVAL, not with frames, and a removed widget fires exactly once per subscription that matched it (no
     * miss-fire or double-fire even if multiple handlers unsubscribe).
     */
    static void dispatchSelectorRemoved(Widget w) {
        if(selectorWatches.isEmpty() && pending.isEmpty())
            return;
        for(LuaSelectorWatch watch : selectorWatches) {      // copy-on-write: a handler may unsubscribe here
            if(!watch.alive)
                continue;
            Integer id = watch.matched.remove(w);            // was this widget matched by this subscription?
            if((id == null) || (watch.event != LuaSelectorWatch.DISAPPEAR))
                continue;
            callLua(watch.owner, Addon.C_WIDGET, watch.fn, LuaWidget.of(watch.owner, w));
        }
        for(PendingMatch p : pending) {                      // drop it from pending too if it's there
            if(p.wdg == w) {
                pending.remove(p);
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
    private static final Queue<Widget> capChanged = new ConcurrentLinkedQueue<Widget>();

    /**
     * Record that {@code w}'s caption changed (the caption seam, {@link AddonManager#onCaptionChanged}, 049.3).
     * Appends one reference — no widget read, no tree walk, no Lua — so it is safe on any thread. Free unless some
     * live subscription actually carries a late refiner: with none, no caption can make anything newly match, and
     * the shipped example addons all subscribe on a bare role.
     */
    static void markCaptionChanged(Widget w) {
        if(w == null)
            return;
        for(LuaSelectorWatch s : selectorWatches) {
            if(s.alive && s.sel.late()) {     // pure (the parsed selector's own shape) — no widget read, any thread
                capChanged.add(w);
                return;
            }
        }
    }

    /**
     * Tick-side drain (UI thread, {@link AddonManager#tick}) — the caption half of the {@code appear} event, in
     * two parts, both of which end in the same {@link #offer} and so dedup against the same {@code matched} map:
     *
     * <ul>
     *   <li><b>The captioned window's own subtree</b> (049.3). {@code window[title=Cupboard] inventory} names the
     *       grid, not the window, so the widget that starts matching when the caption lands is one below the one
     *       that changed — and it may have been placed long before, which is more than the placement-scoped list
     *       below can promise. Walking the subtree of the window that actually changed is exact, and costs a walk
     *       of one window per caption rather than anything per frame.</li>
     *   <li><b>The placement-scoped list</b> ({@link #pending}, 030.2), swept whole rather than by which caption
     *       moved: a {@code [res=]} refiner resolves asynchronously with no event of its own, so this is the only
     *       thing that ever re-checks one, and the list is short-lived and bounded by construction
     *       ({@link #RECHECK_TICKS}).</li>
     * </ul>
     *
     * <p>Gated so an idle client — or one with no subscription at all — pays two {@code isEmpty()} calls.
     */
    static void drainSelectorCaptionCheck() {
        if(capChanged.isEmpty())
            return;
        UI u = ui;
        if((u == null) || (u.root == null) || selectorWatches.isEmpty()) {
            capChanged.clear();       // no tree, or nothing left watching: the recorded windows are owed nothing
            return;
        }
        for(int n = capChanged.size(); n > 0; n--) {
            Widget w = capChanged.poll();
            if(w == null)
                break;
            if(w.hasparent(u.root))   // inclusive of the root itself; a window removed before the tick is offered
                offerSubtree(u, w);   //   nothing, because a widget out of the tree matches nothing any more
        }
        recheckPending(u);
    }

    /**
     * Offer {@code w} and everything below it to every {@code late()} subscription that has not matched it yet —
     * the caption seam's own re-check (049.3). Inclusive of {@code w} itself, because a one-step
     * {@code window[title=Cupboard]} is the same event seen at depth zero.
     */
    private static void offerSubtree(UI u, Widget w) {
        offer(w, u.widgetid(w));
        for(Widget c = w.child; c != null; c = c.next)
            offerSubtree(u, c);
    }

    /** One candidate against every live subscription whose refiner could only just have resolved. */
    private static void offer(Widget wdg, int id) {
        for(LuaSelectorWatch w : selectorWatches) {   // copy-on-write: a handler may subscribe/remove here
            if(!w.alive || w.matched.containsKey(wdg))
                continue;
            if(w.sel.late() && w.sel.matches(wdg))
                record(w, wdg, id);
        }
    }

    /**
     * The bounded re-check (030.2): re-offer each recently-placed candidate to every subscription whose refiner had
     * not yet resolved, then age it out. A candidate that dies, or that survives {@link #RECHECK_TICKS} ticks
     * without matching, is dropped — so this list is short-lived by construction and the cost of the whole event
     * mechanism scales with widget CREATION, not with frames. An entry ageing out is still offered one last time.
     */
    private static void recheckPending(UI u) {
        if(pending.isEmpty())
            return;
        for(PendingMatch p : pending) {                      // copy-on-write: entries drop out as we go
            if(!matchLive(u, p.wdg, p.id)) {
                pending.remove(p);                           // it died before its caption arrived
                continue;
            }
            if(--p.ticks <= 0)
                pending.remove(p);
            offer(p.wdg, p.id);
        }
    }

    /** Is a matched/pending widget still the same live one? (Server-bound: by id; client-only: by reachability.) */
    private static boolean matchLive(UI u, Widget w, int id) {
        return (id >= 0) ? (u.getwidget(id) == w) : w.hasparent(u.root);
    }

    /** Remove one selector subscription: stop it firing + drop it from both lists (the handle's {@code :remove()}). */
    private static void removeSelectorWatch(Addon owner, LuaSelectorWatch w) {
        w.alive = false;
        w.matched.clear();
        selectorWatches.remove(w);
        owner.selectorWatches.remove(w);
        if(selectorWatches.isEmpty()) {
            pending.clear();          // 042.9: last subscription gone — nothing left to re-check, and nothing
            capChanged.clear();       //   would ever drain these Widget refs again otherwise (no poll left to do it)
        }
    }

    /**
     * Drop every selector subscription this addon owns (reload/disable, P2). Nothing is fired: a {@code :reload} is
     * not a destroy — the widgets go on living, this addon simply stops watching (and its Lua callbacks are about to
     * cease to exist with its env). Same rule as {@link #teardownWatches}.
     */
    static void teardownSelectorWatches(Addon a) {
        if(a.selectorWatches.isEmpty())
            return;
        for(LuaSelectorWatch w : a.selectorWatches) {
            w.alive = false;
            w.matched.clear();
        }
        selectorWatches.removeAll(a.selectorWatches);
        a.selectorWatches.clear();
        if(selectorWatches.isEmpty()) {
            pending.clear();          // 042.9: same as removeSelectorWatch — nothing left to own the re-check
            capChanged.clear();
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
     * <p><b>The guard decides relog vs {@code :reload}.</b> {@code AddonManager.init} binds the NEW session's
     * {@code ui} <i>before</i> the teardown loop, so after a relog a server-bound entry's id no longer maps to the
     * recorded widget (and a client-only one is no longer under the live root) — the restore is correctly skipped,
     * the old tree being gone entirely. Within one session both tests still hold and the widget is put back.
     * Tree ops → under the {@code ui} monitor, like every other write into the client's tree.
     */
    static void teardownHidden(Addon a) {
        if((a == null) || a.hiddenNative.isEmpty())    // null: the :lua REPL owner, which exists only once used
            return;
        final UI u = ui;
        final List<LuaWidget.Hidden> hs = new ArrayList<LuaWidget.Hidden>(a.hiddenNative);
        a.hiddenNative.clear();
        LuaWidget.recountHidden();      // 031.1: the toggles this addon owned go back to the client
        Runnable restore = () -> {
            for(LuaWidget.Hidden h : hs) {
                restoreHidden(u, h);          // the rule — asked BEFORE the view is killed
                AddonWidget v = h.view;
                h.view = null;
                destroyView(h.owner, v);      // 032.1: the view's fate follows the substitution, teardown included
            }
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
        UI u = ui;
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
        UI u = ui;
        Runnable act = () -> {
            if(!view.show(!view.visible()))
                return;                       // just closed it: nothing to raise or focus
            view.raise();
            fitView(view);
            if(view.parent != null)
                view.parent.setfocus(view);
        };
        if(u != null) {
            synchronized(u) { act.run(); }
        } else {
            act.run();
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
     * The counterpart of {@link #teardownHidden} one property along, and the same guard: a record whose widget is
     * no longer the live one is skipped, so a relog — which rebinds {@code ui} <i>before</i> the teardown loop —
     * correctly restores nothing (that tree is gone), while a same-session {@code :reload} puts every widget back.
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
        final UI u = ui;
        final List<LuaWidget.Moved> ms = new ArrayList<LuaWidget.Moved>(a.movedNative);
        a.movedNative.clear();
        LuaWidget.recountMoved();
        Runnable restore = () -> {
            for(LuaWidget.Moved m : ms)
                restoreMoved(u, m, true, true);
        };
        if(u != null) {
            synchronized(u) { restore.run(); }
        } else {
            restore.run();
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
        UI u = ui;
        Runnable act = () -> {
            if(pos)
                m.wantPos = null;
            else
                m.wantSize = null;
            Layout.apply(w);                     // the fold again, one level shorter
            if(m.idle() && owner.movedNative.remove(m))
                LuaWidget.recountMoved();
        };
        if(u != null) {
            synchronized(u) { act.run(); }
        } else {
            act.run();
        }
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

    // -------------------------------------------------- generic widget-tree introspection (hafen.ui, W1, spec 20)

    /**
     * {@code hafen.ui()} — the {@link LuaWidget} entity for {@code ui.root}, the top of the whole client tree
     * (spec 20, W1; the no-argument form of the callable namespace since 030.1, which hard-cut
     * {@code hafen.ui.root()}). Returns {@code nil} if no UI is up yet. From here an addon walks DOWN to any open
     * window — or names one directly with a selector.
     */
    private static LuaValue nodeRoot(Addon owner) {
        UI u = ui;
        if((u == null) || (u.root == null))
            return LuaValue.NIL;
        return LuaWidget.of(owner, u.root);
    }

    /**
     * {@code hafen.ui.node(id)} — the {@link LuaWidget} entity for a SERVER widget id (another widget's
     * {@code :id()}, typically), or {@code nil} if the id doesn't resolve (no such server widget, or it was
     * destroyed). A non-number id is a clear error.
     */
    private static LuaValue nodeById(Addon owner, LuaValue idv) {
        if(!idv.isnumber())
            throw new LuaError("hafen.ui.node(id) expects a widget id (number)");
        UI u = ui;
        if(u == null)
            return LuaValue.NIL;
        Widget w = u.getwidget(idv.toint());
        if(w == null)
            return LuaValue.NIL;
        return LuaWidget.of(owner, w);
    }

    /**
     * {@code hafen.ui.at(x, y)} — the DEEPEST {@link LuaWidget} entity under a root-coord point (spec 20, W2), or
     * {@code nil}. Runs {@link LuaWidget#hitTest} from {@code ui.root} (the point is already in root-local coords),
     * under the {@code ui} monitor so the walk never races tree mutation. Non-number args are a clear error, like
     * {@code hafen.ui.node}. Interned, so two calls on the same widget answer the SAME value — which is what let
     * {@code :same()} be cut (029.1).
     */
    private static LuaValue nodeAt(Addon owner, LuaValue xv, LuaValue yv) {
        if(!xv.isnumber() || !yv.isnumber())
            throw new LuaError("hafen.ui.at(x, y) expects numbers");
        UI u = ui;
        if((u == null) || (u.root == null))
            return LuaValue.NIL;
        Widget hit;
        synchronized(u) { hit = LuaWidget.hitTest(u.root, new Coord(xv.toint(), yv.toint())); }
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
     * <p><b>Four refusals, all clear and all thrown</b> (this is a Lua call, unlike the placement path that can only
     * log): a view the addon does not own, a widget with no enclosing window (nothing to stand in for), one of the
     * addon's <i>own</i> windows, and a window another addon already holds — <i>one window, one owner</i>, since the
     * toggle goes with it (031.2).
     *
     * <p><b>One window, one view.</b> Installing a different view ends the previous substitution and destroys that
     * view, for the same reason every other ending does: a stand-in that stands for nothing is an orphan window over
     * a container it no longer represents. Re-installing the SAME view is a no-op that still re-hides the window.
     */
    static void replaceWith(Addon owner, Widget w, LuaValue viewv) {
        Owned own = LuaWidget.ownedContent(owner, LuaWidget.live(LuaWidget.resolve(viewv)));
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
        UI u = ui;
        if(u != null) {
            synchronized(u) { wnd.hide(); }
        } else {
            wnd.hide();
        }
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
        UI u = ui;
        Runnable kill = () -> {
            try { v.kill(); } catch(RuntimeException e) { /* best-effort: never abort a teardown/undo */ }
        };
        if(u != null) {
            synchronized(u) { kill.run(); }
        } else {
            kill.run();
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
     * root GOut (absolute screen coords); {@code w,h} are the screen size. On the UI thread (inside UI.draw).
     */
    static void paintHudOverlays(GOut g) {
        LuaValue w = LuaValue.valueOf(g.sz().x), h = LuaValue.valueOf(g.sz().y);
        for(Addon a : addons) {
            if(a.hudOverlays.isEmpty())
                continue;
            // 026.1: bound PER ADDON (it used to wrap the whole loop) — the wrapper now carries the owner of the
            // g:text cache, and a cache is per-addon.
            LuaTable gt = hudGout.bind(g, a);
            try {
                for(HudOverlay o : a.hudOverlays) {
                    LuaValue fn = o.fn;                 // 039.6: bare until :onDraw(fn) — an incomplete overlay
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
        LuaValue sx = LuaValue.valueOf(sc.x), sy = LuaValue.valueOf(sc.y);
        for(LuaGobOverlay.Attach o : recs) {
            LuaTable gt = gwrap.bind(g, o.owner);
            try {
                if(o.draw != null)
                    callLua(o.owner, Addon.C_DRAW, o.draw, gt, LuaGob.of(o.owner, gob.id), sx, sy);
                else
                    gwrap.label(g, o.text, sc.add(o.screenOffset()), 0.5, 1.0, o.color);
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
     * Mutating a gob's render slots is done under {@code synchronized(ui)} (like {@link #destroyWidgets})
     * because teardown may run off the UI thread (session bind). The game's own overlays are untouched.
     *
     * <p>Since 043.3 a record owns nothing but itself — the world kinds left {@code gob:overlay()}, so dropping
     * it from the map IS its end, and what stands in the 3D scene is freed by {@code VrApi}'s own per-kind
     * teardowns like any other entity this addon placed.
     */
    static void teardownGobOverlays(Addon a) {
        UI u = ui;
        Runnable detach = () -> {
            try {
                for(Gob g : allGobs()) {
                    LuaGobOverlay ol = LuaGobOverlay.on(g);
                    if(ol == null)
                        continue;
                    if(!ol.removeOwner(a).isEmpty())
                        LuaGobOverlay.prune(g);
                }
            } catch(RuntimeException e) {
                /* best-effort cleanup — a leftover idle attrib draws nothing anyway */
            }
        };
        if(u != null) {
            synchronized(u) { detach.run(); }
        } else {
            detach.run();
        }
    }

    /**
     * Put every gob {@code a} resized back to its original size ({@code :reload}/disable) — the twin of
     * {@link #teardownGobOverlays} and, like it, one walk of the object cache at a rare moment. Nothing an
     * addon that is no longer running left distorted stays distorted, which is what makes a purely visual
     * write on the game's own objects safe to leave unprotected.
     *
     * <p>A gob scaled by a <i>different</i> addon is untouched: the size records who wrote it, and a gob has
     * one size, so teardown reverts only what this addon last set. Under {@code synchronized(ui)} like its
     * twin, because teardown may run off the UI thread (session bind) while {@code ctick} rebuilds the state.
     */
    static void teardownGobScales(Addon a) {
        UI u = ui;
        Runnable unscale = () -> {
            try {
                for(Gob g : allGobs())
                    GobScale.revert(g, a);
            } catch(RuntimeException e) {
                /* best-effort cleanup — a leftover scale is visual only, and dies with the gob anyway */
            }
        };
        if(u != null) {
            synchronized(u) { unscale.run(); }
        } else {
            unscale.run();
        }
    }
}
