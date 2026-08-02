package io.brodgar.addon;

import haven.Button;
import haven.CharWnd;
import haven.Coord;
import haven.Equipory;
import haven.GameUI;
import haven.Gob;
import haven.GOut;
import haven.Inventory;
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
import org.luaj.vm2.lib.ThreeArgFunction;
import org.luaj.vm2.lib.TwoArgFunction;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.ZeroArgFunction;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;


import static io.brodgar.addon.AddonManager.*;

/**
 * The custom-UI + widget-introspection subsystem:
 * {@code hafen.ui} — custom windows/widgets (2a), HUD + world-space gob overlays (2b), selector lookups and
 * selector events (030), adopted-widget models (3b), widget replacers (3c), and the read-only widget-tree walk +
 * hit-testing (W1/W2 node API). Owns the subscription/model/replacer registries + overlay paint state. The
 * widget-creation seams {@code onWidgetCreated}/{@code onWidgetPlaced} (called from {@code haven.UI}) stay
 * facades in {@link AddonManager} and delegate here; the tick drives {@link #pollModels}/{@link #pollWatches}/
 * {@link #pollSelectorWatches}/{@link #sweepGobOverlays}/{@link #anyHudOverlays}; {@code haven}-side
 * {@code LuaGobOverlay.draw} calls {@link #paintGobOverlays}. Shared gob-read/engine helpers stay in
 * {@link AddonManager}. Not instantiable.
 */
final class UiApi {
    private UiApi() {}

    // ===== registries + overlay paint state =====
    // -- custom UI overlays (spec 07 / Phase 2b): HUD overlays + world-space gob overlays -----------------
    // HUD overlays paint ON TOP of the HUD via a one-shot UI.drawafter re-registered each tick (drawafter
    // is cleared every UI.draw; tick precedes draw in the frame loop, so the afterdraw runs this same frame
    // after root.draw — above GameUI). Gob overlays attach a shared LuaGobOverlay attrib to each matching
    // gob (the SpeakerIcon pattern); a THROTTLED sweep evaluates filters + attaches, the per-frame draw
    // re-checks filters + paints. All on the UI thread (paint runs inside UI.draw).
    private static final LuaGOut hudGout = new LuaGOut();                 // shared g wrapper for the HUD pass
    static final UI.AfterDraw hudAfterDraw = new UI.AfterDraw() { // one-shot afterdraw, re-queued each tick
        public void draw(GOut g) { paintHudOverlays(g); }
    };
    private static double lastGobSweep;                                   // engine-clock of the last gob sweep
    private static final double GOB_SWEEP_INTERVAL =                      // gob-overlay filter sweep period (s)
        Double.parseDouble(System.getProperty("haven.addon.gobsweepsec", "0.2"));

    // -- widget-creation interception (spec 08 / Phase 3a): the server type string, for replace's descriptor -------
    // widgetTypes holds only IN-FLIGHT creations (recorded at NewWidget.run by onWidgetCreated, removed at the
    // matching AddWidget.run by onWidgetPlaced) and is recorded only while a REPLACER exists, so the map stays tiny
    // and an unreplacing client records nothing. Since 030.2 that is its only consumer: hafen.ui.onWidgetCreate and
    // its {id,type,place,caption,parentType} descriptor are HARD CUT — an addon names the widget it is waiting for
    // with a SELECTOR now (see selectorWatches below), not with a second vocabulary. The descriptor survives only as
    // the argument of replace{match=fn}, which goes with replace itself in B3. Session-scoped (cleared per init).
    private static final Map<Integer, String> widgetTypes = new ConcurrentHashMap<Integer, String>();

    // -- selector subscriptions (030.2): hafen.ui.on(sel, "appear"|"disappear", fn) — the discovery primitive that
    // replaced onWidgetCreate. A FLAT global list (a subscription watches the whole tree, not one keyed target),
    // consulted at the placement seam and polled each tick; globally empty = a near-zero fast path, so a client with
    // no subscription pays one isEmpty() per widget placement and one per tick. `pending` is the BOUNDED re-check:
    // a widget that matched a selector's structure (role/class, fixed for its life) but not its [title=]/[res=]
    // refiner may simply not have its caption yet, so it is re-offered for RECHECK_TICKS ticks and then dropped —
    // cost scales with widget creation, not with frames (a per-tick diff of the whole tree was the discarded
    // alternative). Owned copies live on each Addon for teardown. Session-scoped (cleared per init).
    // THREADING: the placement seam runs on a Loader thread but inside AddWidget.run's synchronized(ui), and the
    // per-tick poll runs on the UI thread inside UILoop's synchronized(ui) — so the UI monitor already guards both
    // ends and each subscription's `matched` map needs no lock of its own.
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

    // -- adopted widget models (spec 08 / Phase 3b): hafen.ui.adopt(id) wraps a live server-bound widget so an
    // addon can hide it as a headless model + present a custom view (D-009). A FLAT global list, polled each tick
    // (pollModels) for item add/remove (a WItem create/cdestroy, not a uimsg) and server destroy (its id stops
    // mapping to the widget); globally empty = a near-zero fast path. Owned copies live on each Addon for teardown
    // (which un-hides anything the addon hid, restoring the stock UI). Session-scoped (cleared per init).
    private static final List<LuaModel> models = new CopyOnWriteArrayList<LuaModel>();

    // -- container subscriptions (029.3): widget:onItemAdded/:onItemRemoved/:onDestroy. A FLAT global list of the
    // widgets SOMEBODY is listening to, diffed each tick (pollWatches) for WItem add/remove (a create/cdestroy, not
    // a uimsg) and for the widget's own death. hasSub-GATED by construction — an entry exists only while at least
    // one callback is set, so a widget nobody subscribed to is never polled and an idle client pays one isEmpty().
    // Owned copies live on each Addon for teardown. Session-scoped (cleared per init; the tree is rebuilt).
    private static final List<LuaWidget.Watch> watches = new CopyOnWriteArrayList<LuaWidget.Watch>();

    // -- widget replacers (spec 08 / Phase 3c): hafen.ui.replace(type, opts, fn) — the high-level sugar over 3a+3b.
    // Watch for a server widget matching a descriptor, then adopt+hide it and hand the addon a custom view (D-009).
    // A FLAT global list consulted at widget placement (onWidgetPlaced, alongside the observers); registration also
    // SCANS the live tree once to catch an already-open target (the :reload case, where no creation event fires).
    // Owned copies live on each Addon for teardown. Session-scoped (cleared per init; widget ids are per-session).
    private static final List<LuaReplacer> widgetReplacers = new CopyOnWriteArrayList<LuaReplacer>();

    // ===== widget-creation seams (bodies behind AddonManager.onWidgetCreated/onWidgetPlaced) =====
    static void onWidgetCreated(int id, String typenm) {
        if(widgetReplacers.isEmpty() || (typenm == null))
            return;                                   // fast path: nobody is replacing, or no type string
        widgetTypes.put(Integer.valueOf(id), typenm);
    }

    static void onWidgetPlaced(int id, Widget wdg, Widget pwdg, Object[] pargs) {
        if(!selectorWatches.isEmpty())
            offerPlaced(wdg, id);                     // 030.2: the selector subscriptions see the LIVE widget itself
        if(widgetReplacers.isEmpty())
            return;                                   // fast path: nothing is being replaced
        String type = widgetTypes.remove(Integer.valueOf(id));
        String place = ((pargs != null) && (pargs.length > 0) && (pargs[0] instanceof String))
                       ? (String)pargs[0] : null;
        String parentType = (pwdg == null) ? null : pwdg.getClass().getSimpleName();
        String caption = (wdg instanceof Window) ? ((Window)wdg).cap : null;
        // 3c: offer this newly-placed widget to every replacer (a target opened AFTER the replacer registered).
        for(LuaReplacer r : widgetReplacers) {        // copy-on-write: a builder may register/remove replacers here
            if(r.alive && !r.handled(id) && matchOnCreate(r, id, type, place, caption, parentType))
                fireReplace(r, id, wdg);
        }
    }

    /** Build {@code hafen.ui} for {@code owner}. From installHafen. */
    static void installUi(LuaTable hafen, final Addon owner) {
        LuaTable uiT = new LuaTable();
        uiT.set("window", new OneArgFunction() {
            public LuaValue call(LuaValue opts) {
                return newUi(owner, opts, true);
            }
        });
        uiT.set("widget", new OneArgFunction() {
            public LuaValue call(LuaValue opts) {
                return newUi(owner, opts, false);
            }
        });
        // hafen.ui.overlay(fn) — paint on top of the HUD without owning a widget. fn(g, w, h) runs every
        // frame with the shared GOut wrapper and the screen size; draw at absolute screen coords. Returns a
        // handle with :remove(); also auto-removed on reload/disable (spec 07).
        uiT.set("overlay", new OneArgFunction() {
            public LuaValue call(LuaValue fn) {
                return newHudOverlay(owner, fn);
            }
        });
        // hafen.ui.gobOverlay(filter, fn) — label/mark game objects in the 3D view (the SpeakerIcon pattern).
        // filter(gob)->truthy (or a substring matched against the gob's resource name) selects gobs; fn(g, gob,
        // sx, sy) draws at the gob's projected screen point (sx,sy = just above the head). gob is a live Gob
        // OBJECT (D-044) — read it with gob:name()/gob:health()/… Returns a handle with :remove(); auto-removed
        // on reload/disable (spec 07).
        uiT.set("gobOverlay", new TwoArgFunction() {
            public LuaValue call(LuaValue filter, LuaValue fn) {
                return newGobOverlay(owner, filter, fn);
            }
        });
        // hafen.ui.on(selector, "appear"|"disappear", fn) — 030.2: WATCH the client's own UI for a part of it, named
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
        // selector still fires exactly once for a window whose caption lands a tick late (the placement seam
        // re-checks such a candidate for a bounded number of ticks). Returns a handle with :remove(); auto-removed
        // on reload/disable (P2), which fires nothing — a reload is not a destroy.
        uiT.set("on", new ThreeArgFunction() {
            public LuaValue call(LuaValue sel, LuaValue event, LuaValue fn) {
                return newSelectorWatch(owner, sel, event, fn);
            }
        });
        // hafen.ui.adopt(id) is GONE (029.2). It only ever existed to get a readable handle on a native widget, and
        // it charged you a hidden window for the privilege. Now every widget IS an entity: hafen.ui.node(id) hands
        // you the same one WITHOUT hiding anything, and widget:hide() (which records the restore, see below) is the
        // separate, explicit act it always should have been. Replacing a native window is still hafen.ui.replace.
        // hafen.ui.replace(type, opts, fn) — the high-level "replace a native window with your own view" sugar over
        // 3a (observe) + 3b (adopt), spec 08 / Phase 3c. It watches for a SERVER widget matching a descriptor, then
        // adopts the real widget as a hidden MODEL and calls fn(model); fn draws a custom VIEW (e.g. a hafen.ui.window)
        // and RETURNS it — "wrap, don't reimplement" (D-009). Since 029.3 fn's argument is the WIDGET ENTITY for the
        // replaced widget (the same value hafen.ui.node(id) hands back), so model:items(), model:onItemAdded(fn) and
        // every other widget verb answer on it. The native window is hidden but stays server-bound, so
        // its items/events keep working; disabling/reloading the addon (or handle:remove()) UN-HIDES it, restoring
        // the stock UI (the Phase-3 DoD). Matching:
        //   type            -- the server type string (e.g. "inv"); required.
        //   opts.context    -- a semantic selector: "main" = the main inventory (GameUI.maininv).
        //   opts.caption    -- an exact window caption (for titled containers, e.g. "Cupboard").
        //   opts.match      -- an escape-hatch predicate match(desc)->truthy, desc={id,type,place,caption,parentType}.
        // Registration also SCANS the live tree ONCE for an already-open match (the :reload case, where the target
        // was created before this addon layer existed) — so it works whether the window opens before or after you
        // call replace. Item MOVING (take/transfer/drop) is a gameplay action -> the gated Phase-4 tier, not here.
        // Returns a handle { :remove() } that stops replacing AND restores the native window (destroying your view);
        // bridge-owned, so :reload/disable does the same automatically.
        uiT.set("replace", new ThreeArgFunction() {
            public LuaValue call(LuaValue type, LuaValue opts, LuaValue fn) {
                return newReplacer(owner, type, opts, fn);
            }
        });
        // hafen.ui(sel) / hafen.ui.all(sel) / hafen.ui() / hafen.ui.node(id) / hafen.ui.at(x,y) — the widget-tree entry points (spec 20 W1/W2,
        // rebuilt on the ONE entity by 029-widget-oop). Walk ANY widget's children to arbitrary depth from Lua (the
        // generic reader that complements the spec-14 typed adapters). Entry points for distinct inputs (D-012):
        // hafen.ui(selector) = the FIRST widget matching a selector string, or nil; hafen.ui.all(selector) = every
        // match as a 1-based array (empty, never nil); hafen.ui() = the ROOT of the whole client tree (discovery,
        // walk DOWN to any open window — hafen.ui.root() is hard cut, the no-arg collection form IS the tree);
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
        //   :pos()           -- {x=,y=} position within the parent (widget-local px)
        //   :size()          -- {x=,y=}
        //   :visible()       -- boolean
        //   :text()          -- best-effort text for text-bearing widgets (Label/Button/Window/TextEntry), else nil
        //   :exists()        -- is it still in the tree? (the one read that always answers)
        //   :info()          -- the snapshot escape hatch {type,role,res,id,pos,size,visible,text,owned}
        //   :walk(fn)        -- depth-first: fn(widget, depth); return false to PRUNE the subtree
        //   :at(coord)       -- W2: the DEEPEST Widget object under a {x=,y=} root-coord point WITHIN this subtree
        //   :rootpos()       -- W2: {x=,y=} its top-left in root coords (with :size() = a highlight box)
        //   :hide() / :show()-- the ONE write that answers on a native widget. Hiding one you do not own records
        //                       the restore, so :reload/disable puts it back exactly as it was (029.2). It hides
        //                       EXACTLY what you point at (:replace does not — see below).
        //   :replace(view)   -- 032.1: put your own window in place of the native one, and inherit its toggle
        //                       (031, D-069). Arity is the verb: :replace() reads the installed view or nil,
        //                       :replace(view) installs, :replace(nil) undoes. It hides the ENCLOSING WINDOW, not
        //                       the widget you point at, so replacing the inventory GRID takes the whole stock
        //                       window with it; the view is destroyed when the substitution ends (undo, teardown,
        //                       or the server destroying the window). Wait for the target with
        //                       hafen.ui.on(sel, "appear", fn) — that half is not part of the verb.
        //   :items()         -- 029.3: the Item snapshots inside this container (a relation, like :children()) —
        //                       an Inventory, an Equipory (each entry also carrying its `slot`), or any widget with
        //                       WItems under it. Read it with the window VISIBLE and interactive: nothing is hidden.
        //   :onItemAdded(fn) / :onItemRemoved(fn) -- fn(item) as items enter/leave this container (a per-tick diff:
        //                       an item add is a widget create, not a uimsg). Pass nil to unsubscribe.
        //   :onDestroy(fn)   -- fn() once, when this widget leaves the tree. All three chain; subscribing is what
        //                       registers the widget for polling, so an unwatched widget costs nothing.
        // OWNED-ONLY (a widget YOUR addon created with hafen.ui.window{} / hafen.ui.widget{}); on a native widget
        // each raises a clear error, the geometry ones naming layout (feature E):
        //   :pos(x, y)       -- move + chain (arity is the verb, the 018 shape; :move() is GONE)
        //   :size(w, h)      -- resize the content (+ repack a window's chrome) + chain
        //   :pack()          -- shrink the chrome to fit (no-op for a bare widget) + chain
        //   :destroy()       -- remove it and drop it from the addon's owned registry
        // Otherwise READ-ONLY: to ACT on the GAME, read a server-bound widget's :id() and pass it to the gated
        // hafen.act.raw (D-025) — no new action surface, no new gate (reading the tree is ungated client-side data).
        // hafen.ui.all(selector) — EVERY widget matching the selector, as a 1-based array in tree order (empty,
        // never nil). One walk of the tree testing each node, not a deep helper per node (that is O(n²)); the
        // selector is parsed ONCE here, never per node. HOLD the result — entities are interned, so keeping it is
        // free, while re-selecting every frame is a whole tree walk every frame.
        uiT.set("all", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                return selectAll(owner, selArg(a.arg1(), "hafen.ui.all(selector)"));
            }
        });
        uiT.set("node", new OneArgFunction() {
            public LuaValue call(LuaValue id) { return nodeById(owner, id); }
        });
        // hafen.ui.mouse() / hafen.ui.at(x,y) — W2 hit-testing, the WoW /framestack enabler (spec 20 §W2, D-042).
        // mouse() = {x=,y=} the cursor in root coords (public UI.mc); at(x,y) = the DEEPEST Widget object under that
        // root-coord point, or nil. at() MIRRORS the engine's own pointer dispatch (PointerEvent.propagation): it
        // walks children topmost-first, skips !visible(), descends by xlate (so SCROLL offsets are honoured) +
        // rect-intersect, and honours checkhit at the leaf (non-rectangular hit areas) — so it resolves EXACTLY the
        // widget a real click would hit (a naive pos..pos+size rect test is wrong under scroll / custom hit shapes).
        // Walk :parent() up from the hit for the full stack. Read-only, ungated (client-side data, never reaches the
        // server); acting still goes through the gated hafen.act.raw on a server-bound :id().
        uiT.set("mouse", new ZeroArgFunction() {
            public LuaValue call() { return nodeMouse(); }
        });
        // hafen.ui.inventory() / equipment() / hand() — 029.3, what replaced the hafen.items section (hard cut).
        // The first two are LOOKUPS, not a section: they hand back the Widget entity for the player's own backpack
        // (GameUI.maininv) and Equipory, so the items are read the same way as any other container's —
        // hafen.ui.inventory():items() — and every other widget verb answers on them too. nil before the HUD is up.
        // hand() is the odd one out and stays a plain Item snapshot: the cursor item is not a widget you can walk.
        uiT.set("inventory", new ZeroArgFunction() {
            public LuaValue call() { return LuaWidget.of(owner, CharApi.maininv()); }
        });
        uiT.set("equipment", new ZeroArgFunction() {
            public LuaValue call() { return LuaWidget.of(owner, CharApi.equipory()); }
        });
        uiT.set("hand", new ZeroArgFunction() {
            public LuaValue call() {
                GameUI g = gui();
                if((g == null) || (g.vhand == null))
                    return LuaValue.NIL;
                return CharApi.itemSnapshot(g.vhand.item, LuaValue.NIL);
            }
        });
        uiT.set("at", new TwoArgFunction() {
            public LuaValue call(LuaValue x, LuaValue y) { return nodeAt(owner, x, y); }
        });
        // 030.1: hafen.ui is CALLABLE (the hafen.asset/hafen.kin/hafen.meter shape, D-056) — arity is the verb.
        // hafen.ui(selector) is the FIRST match or nil; hafen.ui() with no argument is the ROOT, which is why
        // hafen.ui.root() is a hard cut and now reads as plain nil from Lua (D-013).
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.CALL, new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue key = a.arg(2);            // arg1 = the callable table itself
                if(key.isnil())                     // hafen.ui() — the top of the whole client tree
                    return nodeRoot(owner);
                return selectFirst(owner, selArg(key, "hafen.ui(selector)"));
            }
        });
        uiT.setmetatable(mt);
        hafen.set("ui", uiT);
    }

    // ------------------------------------------------------------------ selectors (hafen.ui(sel), 030.1)

    /**
     * A selector argument &rarr; a parsed {@link Selector}, or a clear error. The number check comes BEFORE
     * {@code isstring()} because in LuaJ a number IS a string (the {@code hafen.asset} lesson, 028).
     */
    private static Selector selArg(LuaValue v, String where) {
        if(v.isnumber())
            throw new LuaError(where + ": the argument is a SELECTOR string (e.g. \"window[title=Cupboard]\"),"
                + " not a number — hafen.ui.node(id) is the one that takes a widget id");
        if(!v.isstring())
            throw new LuaError(where + " expects a selector string (e.g. \"*\", \"inventory\","
                + " \"@Equipory\", \"window[title=Cupboard]\"), got " + v.typename());
        return Selector.parse(v.tojstring());
    }

    /**
     * {@code hafen.ui(selector)} — the FIRST widget matching {@code sel} in tree order (pre-order, depth-first from
     * {@code ui.root}), or {@code nil}. Stops at the first hit, so the common "find one window" case does not pay
     * for the whole tree.
     */
    private static LuaValue selectFirst(Addon owner, Selector sel) {
        UI u = ui;
        if((u == null) || (u.root == null))
            return LuaValue.NIL;
        Widget hit;
        synchronized(u) { hit = firstMatch(u.root, sel); }
        return (hit == null) ? LuaValue.NIL : LuaWidget.of(owner, hit);
    }

    /**
     * {@code hafen.ui.all(selector)} — every match as a 1-based Lua array in tree order; <b>empty, never nil</b>
     * (the collection form always answers). ONE pre-order walk testing each node — never {@link Widget#children}
     * per node, which is a deep traversal and would make this O(n²) (the 029.4 lesson). The whole walk runs under
     * the {@code ui} monitor, so it never races tree mutation, and the matcher calls no Lua.
     */
    private static LuaValue selectAll(Addon owner, Selector sel) {
        LuaTable out = new LuaTable();
        UI u = ui;
        if((u == null) || (u.root == null))
            return out;
        List<Widget> hits = new ArrayList<Widget>();
        synchronized(u) { collect(u.root, sel, hits); }
        int i = 0;
        for(Widget w : hits)
            out.set(++i, LuaWidget.of(owner, w));
        return out;
    }

    /** Pre-order search for the first match under {@code w} (inclusive). Under the {@code ui} monitor. */
    private static Widget firstMatch(Widget w, Selector sel) {
        if(sel.matches(w))
            return w;
        for(Widget c = w.child; c != null; c = c.next) {
            Widget hit = firstMatch(c, sel);
            if(hit != null)
                return hit;
        }
        return null;
    }

    /** Pre-order collection of every match under {@code w} (inclusive). Under the {@code ui} monitor. */
    private static void collect(Widget w, Selector sel, List<Widget> out) {
        if(sel.matches(w))
            out.add(w);
        for(Widget c = w.child; c != null; c = c.next)
            collect(c, sel, out);
    }

    /** Session init: drop per-session widget-type records, adopted models, and replacers (from AddonManager.init). */
    static void resetSession() {
        lastGobSweep = 0;             // 2b: sweep gob overlays promptly on the new session
        widgetTypes.clear();
        models.clear();
        widgetReplacers.clear();
        watches.clear();
        selectorWatches.clear();      // 030.2: the tree of the session just ended; nothing matches any more
        pending.clear();
        if(consoleOwner != null) {
            consoleOwner.models.clear();
            consoleOwner.replacers.clear();
            consoleOwner.hiddenNative.clear();   // 029.2: last session's widgets are gone; nothing left to restore
            consoleOwner.itemWatches.clear();    // 029.3: ...and so are the containers it was subscribed to
            consoleOwner.selectorWatches.clear();// 030.2: ...and the selectors it was watching for
        }
        LuaWidget.recountHidden();               // 031.1: nothing is hidden in a session that has not started
    }

    private static LuaValue newUi(final Addon owner, LuaValue opts, boolean window) {
        String what = window ? "window" : "widget";
        if(!opts.istable())
            throw new LuaError("hafen.ui." + what + "(opts) expects a table");
        UI u = ui;
        if((u == null) || (u.root == null))
            throw new LuaError("hafen.ui." + what + ": no UI is up yet");

        LuaValue sizev = opts.get("size");
        int w = sizev.istable() ? sizev.get(1).optint(200) : 200;
        int h = sizev.istable() ? sizev.get(2).optint(140) : 140;
        LuaValue posv = opts.get("pos");
        int px = posv.istable() ? posv.get(1).optint(100) : 100;
        int py = posv.istable() ? posv.get(2).optint(100) : 100;

        final AddonWidget content = new AddonWidget(owner, Coord.of(w, h), opts);

        // Parent: default ui.root; "gameui" attaches under the HUD (falls back to root before it is up).
        Widget parent = u.root;
        if("gameui".equals(opts.get("parent").optjstring("root"))) {
            GameUI g = gui();
            if(g != null)
                parent = g;
            else
                log(owner, "hafen.ui." + what + ": HUD not up yet; attaching to root");
        }

        final Widget rootw;
        final boolean isWindow;
        if(window) {
            final Window win = new Window(Coord.of(w, h), opts.get("title").optjstring(""));
            win.add(content, Coord.z);
            content.root(win);
            LuaValue oc = opts.get("onClose");
            final LuaValue onClose = oc.isfunction() ? oc : null;
            win.reqclose(() -> {                      // the chrome close button: fire onClose, then destroy
                if(onClose != null)
                    callLua(owner, Addon.C_WIDGET, onClose);
                content.kill();
                owner.widgets.remove(content);
            });
            rootw = win;
            isWindow = true;
        } else {
            rootw = content;
            isWindow = false;
        }
        rootw.c = Coord.of(px, py);      // initial position (set before attach)
        parent.add(rootw);               // add() locks on ui; content ticks/draws from the next frame
        owner.widgets.add(content);
        // 029.2: what you CREATE and what you FIND are the same type. The entity is interned on the ROOT (the window
        // chrome, or the bare widget) — the widget the addon positions, shows and destroys — and LuaWidget derives
        // OWNED from the tree, so hafen.ui.at(x,y) over this same window hands back this very value.
        return LuaWidget.of(owner, rootw);
    }

    // ------------------------------------------------------------- custom UI overlays (hafen.ui, 2b)

    /**
     * Register a HUD overlay ({@code hafen.ui.overlay(fn)}, spec 07): a draw callback painted on top of the
     * HUD each frame. Bridge-owned (P2) — added to the addon's registry so reload/disable drops it. Returns
     * the Lua handle ({@code :remove()}).
     */
    private static LuaValue newHudOverlay(final Addon owner, LuaValue fn) {
        if(!fn.isfunction())
            throw new LuaError("hafen.ui.overlay(fn) expects a function");
        final HudOverlay ov = new HudOverlay(owner, fn);
        owner.hudOverlays.add(ov);
        LuaTable h = new LuaTable();
        h.set("remove", new ZeroArgFunction() {
            public LuaValue call() {
                ov.active = false;
                owner.hudOverlays.remove(ov);
                return LuaValue.NIL;
            }
        });
        return h;
    }

    /**
     * Register a world-space gob overlay ({@code hafen.ui.gobOverlay(filter, draw)}, spec 07): a filter that
     * selects gobs (a function {@code filter(gob)->truthy}, or a string substring-matched on the gob's name)
     * and a draw callback {@code draw(g, gob, sx, sy)} painted over each matching gob (the SpeakerIcon
     * pattern via {@link LuaGobOverlay}). Bridge-owned (P2). Returns the Lua handle ({@code :remove()}).
     */
    private static LuaValue newGobOverlay(final Addon owner, LuaValue filter, LuaValue draw) {
        if(!(filter.isfunction() || filter.isstring()))
            throw new LuaError("hafen.ui.gobOverlay(filter, draw): filter must be a function or string");
        if(!draw.isfunction())
            throw new LuaError("hafen.ui.gobOverlay(filter, draw): draw must be a function");
        final GobOverlay ov = new GobOverlay(owner, filter, draw);
        owner.gobOverlays.add(ov);
        LuaTable h = new LuaTable();
        h.set("remove", new ZeroArgFunction() {
            public LuaValue call() {
                ov.active = false;
                owner.gobOverlays.remove(ov);
                if(!anyGobOverlays())      // last gob overlay gone → detach the idle attribs from all gobs
                    detachGobOverlays();
                return LuaValue.NIL;
            }
        });
        return h;
    }


    // ------------------------------------------------- selector subscriptions (hafen.ui.on, 030.2)

    /**
     * Register a selector subscription ({@code hafen.ui.on(selector, "appear"|"disappear", fn)}): parse the selector
     * ONCE, install the {@link LuaSelectorWatch} in the global list (consulted at the placement seam and polled each
     * tick) and in the addon's owned-resource registry (dropped on reload/disable, P2), then SCAN the live tree once
     * so an already-open target is not missed. Returns the Lua handle ({@code :remove()}).
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
     * Per-tick work for the selector subscriptions (UI thread): re-check the recently-placed candidates still
     * waiting for a late {@code [title=]}/{@code [res=]}, then prune every subscription's tracked set, firing
     * {@code disappear} for each widget that left the tree. Fast-paths out when nobody subscribes, which is the
     * normal case — the whole feature then costs one {@code isEmpty()} check per tick.
     *
     * <p>Deaths are collected and removed <b>before</b> any Lua runs, so a handler that unsubscribes itself
     * mid-notification (fire once, then {@code handle:remove()}) cannot invalidate the iteration.
     */
    static void pollSelectorWatches() {
        if(selectorWatches.isEmpty()) {
            pending.clear();                                 // last subscription gone: nothing left to re-check
            return;
        }
        UI u = ui;
        if((u == null) || (u.root == null))
            return;
        recheckPending(u);
        for(LuaSelectorWatch w : selectorWatches) {          // copy-on-write: a handler may subscribe/remove here
            if(!w.alive || w.matched.isEmpty())
                continue;
            List<Widget> gone = null;
            for(Map.Entry<Widget, Integer> e : w.matched.entrySet()) {
                if(matchLive(u, e.getKey(), e.getValue().intValue()))
                    continue;
                if(gone == null)
                    gone = new ArrayList<Widget>();
                gone.add(e.getKey());
            }
            if(gone == null)
                continue;
            for(Widget g : gone)
                w.matched.remove(g);                         // dropped the tick it dies: a strong ref, never a pin
            if(w.event != LuaSelectorWatch.DISAPPEAR)
                continue;
            for(Widget g : gone) {
                if(!w.alive)                                 // a handler removed the subscription mid-notification
                    break;
                callLua(w.owner, Addon.C_WIDGET, w.fn, LuaWidget.of(w.owner, g));
            }
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
            for(LuaSelectorWatch w : selectorWatches) {
                if(!w.alive || w.matched.containsKey(p.wdg))
                    continue;
                if(w.sel.late() && w.sel.matches(p.wdg))
                    record(w, p.wdg, p.id);
            }
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
    }

    // -------------------------------------------------------------- adopted widget models (hafen.ui, 3b)

    /**
     * Per-tick poll of every {@code replace}d model (UI thread, spec 08 / Phase 3c): a model whose server widget is
     * gone (its id no longer maps to it) is dropped, its replacer notified and the addon's view destroyed — the view
     * must die with the model. Fast-paths out when nothing is replaced.
     *
     * <p><b>029.3 took the items out of here.</b> {@code :items()} and the three lifecycle callbacks are now on the
     * Widget entity, where they answer for ANY container; the per-tick diff moved to {@link #pollWatches}, which is
     * gated on somebody having subscribed. What is left is purely {@code replace}'s own bookkeeping — an addon that
     * wants to know when the replaced widget dies subscribes to it like any other widget, with
     * {@code widget:onDestroy(fn)}.
     */
    static void pollModels() {
        if(models.isEmpty())
            return;
        UI u = ui;
        if(u == null)
            return;
        for(LuaModel m : models) {           // copy-on-write: a callback may drop a model here
            if(!m.alive)
                continue;
            if(u.getwidget(m.id) != m.wdg) {  // server destroyed it (or reused the id) → the model is gone
                m.alive = false;
                models.remove(m);
                m.owner.models.remove(m);
                if(m.fromReplace != null)     // 3c: the view dies with the native widget (spec 08); notify the replacer
                    m.fromReplace.active.remove(m);
                releaseHidden(m.hideRecord);  // 031.2: ...and the wrapper's toggle goes back to the client
                m.hideRecord = null;
                destroyReplaceView(m);
            }
        }
    }

    // ---------------------------------------------- container subscriptions (the Widget entity's events, 029.3)

    /**
     * Set one of a widget's lifecycle callbacks ({@code widget:onItemAdded/:onItemRemoved/:onDestroy}) — the Java
     * half of the entity's three event verbs. The FIRST callback on a widget creates its {@link LuaWidget.Watch}
     * (registering it for the per-tick diff); clearing the LAST one drops the record again, so the poll only ever
     * sees widgets somebody is actually listening to (the {@code hasSub} gate {@code fireBuff}/{@code fireMeter}
     * established). A non-function value clears; a stale widget is a silent no-op (there is nothing to watch, and
     * refusing would force an {@code :exists()} guard at every call site — the 029.2 rule for writes).
     */
    static void setItemCallback(Addon owner, Widget w, int slot, LuaValue fn) {
        if(w == null)
            return;
        LuaValue f = fn.isfunction() ? fn : null;
        LuaWidget.Watch wa = findWatch(owner, w);
        if(wa == null) {
            if(f == null)
                return;                       // clearing a callback that was never set: nothing to do
            UI u = ui;
            wa = new LuaWidget.Watch(owner, w, (u == null) ? -1 : u.widgetid(w));
            owner.itemWatches.add(wa);
            watches.add(wa);
        }
        wa.set(slot, f);
        if(!wa.subscribed())                  // last listener gone → leave the poll entirely
            dropWatch(wa);
    }

    /** This addon's subscription record for a widget, or {@code null} (identity-keyed; the list is per-addon tiny). */
    private static LuaWidget.Watch findWatch(Addon owner, Widget w) {
        for(LuaWidget.Watch wa : owner.itemWatches) {
            if(wa.wdg == w)
                return wa;
        }
        return null;
    }

    /** Drop a subscription from both lists (unsubscribed by hand, or its widget died). */
    private static void dropWatch(LuaWidget.Watch wa) {
        watches.remove(wa);
        wa.owner.itemWatches.remove(wa);
    }

    /**
     * Per-tick poll of every subscribed container (UI thread, 029.3). For each watched widget: if it is gone (a
     * server destroy, a closed window, a relog), fire {@code onDestroy} <b>once</b> and drop the record; otherwise
     * diff its {@link WItem} children for add/remove. Item add/remove is a widget create/{@code cdestroy}, not a
     * {@code uimsg}, so it can only be seen by polling — the same discipline as the buff/meter adapters. Fast-paths
     * out when nobody is subscribed, which is the normal case.
     */
    static void pollWatches() {
        if(watches.isEmpty())
            return;
        UI u = ui;
        if((u == null) || (u.root == null))
            return;
        for(LuaWidget.Watch wa : watches) {   // copy-on-write: a callback may subscribe/unsubscribe here
            if(!wa.alive)
                continue;
            if(!watchLive(u, wa)) {
                wa.alive = false;
                dropWatch(wa);
                if(wa.onDestroy != null)
                    callLua(wa.owner, Addon.C_WIDGET, wa.onDestroy);
                continue;
            }
            if((wa.onItemAdded != null) || (wa.onItemRemoved != null))
                pollWatchItems(wa);
        }
    }

    /** Is a watched widget still the same live one? (Server-bound: by id; client-only: by tree reachability.) */
    private static boolean watchLive(UI u, LuaWidget.Watch wa) {
        return (wa.id >= 0) ? (u.getwidget(wa.id) == wa.wdg) : wa.wdg.hasparent(u.root);
    }

    /** Diff one watched container's {@link WItem} children against its cache, firing onItemAdded/onItemRemoved. */
    private static void pollWatchItems(LuaWidget.Watch wa) {
        Set<WItem> present = new LinkedHashSet<WItem>(LuaWidget.witems(wa.wdg));
        for(WItem w : present) {                        // additions (unseen items)
            if(!wa.items.containsKey(w)) {
                LuaValue snap = LuaWidget.itemSnap(wa.wdg, w);
                wa.items.put(w, snap);
                if(wa.onItemAdded != null)
                    callLua(wa.owner, Addon.C_WIDGET, wa.onItemAdded, snap);
            }
        }
        for(Iterator<Map.Entry<WItem, LuaValue>> it = wa.items.entrySet().iterator(); it.hasNext();) {
            Map.Entry<WItem, LuaValue> e = it.next();   // removals (items that left)
            if(!present.contains(e.getKey())) {
                LuaValue snap = e.getValue();
                it.remove();
                if(wa.onItemRemoved != null)
                    callLua(wa.owner, Addon.C_WIDGET, wa.onItemRemoved, snap);
            }
        }
    }

    /**
     * Drop every container subscription this addon holds (reload/disable, P2). Nothing is fired: a {@code :reload}
     * is not a destroy — the widgets go on living, this addon simply stops listening (and its Lua callbacks are
     * about to cease to exist with its env).
     */
    static void teardownWatches(Addon a) {
        if(a.itemWatches.isEmpty())
            return;
        for(LuaWidget.Watch wa : a.itemWatches)
            wa.alive = false;
        watches.removeAll(a.itemWatches);
        a.itemWatches.clear();
    }

    /**
     * Tear down every adopted model this addon owns (reload/disable, P2): mark each dead and drop it from the
     * global poll list. That is all it does now — <b>the un-hide moved out</b> (031.2). Restoring the native
     * window used to live here, replaying the model's own copy of the window's original visibility; since the hide
     * is one record on {@link Addon#hiddenNative}, {@link #teardownHidden} restores it (under the one rule) and
     * this method has no tree op left, so it needs no {@code ui} lock either.
     */
    static void teardownModels(Addon a) {
        if(a.models.isEmpty())
            return;
        final List<LuaModel> ms = new ArrayList<LuaModel>(a.models);
        a.models.clear();
        models.removeAll(ms);
        for(LuaModel m : ms) {
            m.alive = false;
            m.hideRecord = null;
        }
    }

    /**
     * Give back every NATIVE widget this addon hid with {@code widget:hide()} (029.2, reload/disable, P2) — the
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
     * Tree ops → under the {@code ui} monitor, like {@link #teardownModels}.
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
        Widget p = w.parent;
        if((p == null) || (p.sz == null) || (w.sz == null) || (w.c == null))
            return;
        int marg = UI.scale(100);
        int x = Math.max(w.c.x, Math.min(0, marg - w.sz.x));
        int y = Math.max(w.c.y, Math.min(0, marg - w.sz.y));
        w.c = Coord.of(Math.min(x, p.sz.x - Math.min(marg, w.sz.x)),
                       Math.min(y, p.sz.y - Math.min(marg, w.sz.y)));
    }

    /** Is a restore-list entry still the same live widget? (Server-bound: by id; client-only: by tree reachability.) */
    private static boolean stillHidable(UI u, LuaWidget.Hidden h) {
        if((u == null) || (u.root == null))
            return false;
        return (h.id >= 0) ? (u.getwidget(h.id) == h.wdg) : h.wdg.hasparent(u.root);
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
     * {@code hafen.ui.mouse()} — the cursor position in root coords as {@code {x=,y=}} (spec 20, W2), read from the
     * public {@link UI#mc}. Returns {@code nil} if there is no UI yet. Zero-cost — the engine keeps {@code mc}
     * updated each pointer move; the {@code widgetstack} addon polls this on {@code OnUpdate} for hover.
     */
    private static LuaValue nodeMouse() {
        UI u = ui;
        if((u == null) || (u.mc == null))
            return LuaValue.NIL;
        return LuaWidget.xyTable(u.mc);
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
        AddonWidget view = LuaWidget.ownedContent(owner, LuaWidget.live(LuaWidget.resolve(viewv)));
        if(view == null)
            throw new LuaError("widget:replace(view) expects a widget YOUR addon created (hafen.ui.window{} or"
                + " hafen.ui.widget{}) to stand in for the native one — pass nil to undo a replacement.");
        Widget wnd = LuaWidget.nativeWindowOf(w);
        if(!(wnd instanceof Window))
            throw new LuaError("widget:replace(view) — " + LuaWidget.typeName(w) + " is not inside a window, so"
                + " there is nothing to stand in for (no window to hide, and no toggle to inherit). Point at a"
                + " widget inside a client window, or just show your own with hafen.ui.window{}.");
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
        assertToggleTarget(owner, w, wnd, "widget:replace(view)");
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
     * Per-tick destroy-detection for the substitutions themselves (032.1, UI thread): the server destroying a
     * replaced window — a chest closed, a relog — <b>ends the substitution</b>, so the record goes and the view dies
     * with it rather than hanging over a container that no longer exists.
     *
     * <p><b>Keyed on the hide record, not on a model.</b> {@link #pollModels} watches the widget the legacy {@code
     * hafen.ui.replace} adopted; the verb adopts nothing, so the thing to watch is the record that IS the
     * substitution. The death test is {@link #stillHidable}, the same two-branch guard the teardown uses (by server
     * id when the window has one, by tree reachability for a client-side wrapper like the inventory's, which never
     * dies). A record with no view bound — a bare {@code w:hide()} — is not a substitution and is left alone.
     *
     * <p>Gated on the {@link LuaWidget#anyHidden} volatile the toggle seam already maintains, so a client that hides
     * nothing pays one read per tick. Both paths are idempotent, so a record the legacy {@code replace} also owns
     * being swept here (and then again by {@link #pollModels}) is harmless.
     */
    static void pollReplaced() {
        if(!LuaWidget.anyHidden)
            return;
        UI u = ui;
        if((u == null) || (u.root == null))
            return;
        List<Addon> as = AddonManager.addons;
        for(int i = 0, n = as.size(); i < n; i++)
            sweepReplaced(u, as.get(i));
        sweepReplaced(u, consoleOwner);    // the :lua REPL replaces windows too, and owns them the same way
    }

    /** One owner's substitutions: end every one whose window the server has taken away. */
    private static void sweepReplaced(UI u, Addon a) {
        if((a == null) || a.hiddenNative.isEmpty())
            return;
        for(LuaWidget.Hidden h : a.hiddenNative) {   // copy-on-write: endReplacement removes from this very list
            if((h.view != null) && !stillHidable(u, h))
                endReplacement(h);
        }
    }

    // -------------------------------------------------------------------- widget replacers (hafen.ui, 3c)

    /**
     * Register a widget replacer ({@code hafen.ui.replace(type, opts, fn)}, spec 08 / Phase 3c): build a {@link
     * LuaReplacer} from the match criteria, register it globally (consulted at widget placement) + in the addon's
     * owned-resource registry (P2), then immediately SCAN the live tree for an already-open match (the {@code
     * :reload} case). Returns the Lua handle ({@code :remove()}). Throws a {@link LuaError} for a bad {@code type}/
     * {@code fn}.
     */
    private static LuaValue newReplacer(final Addon owner, LuaValue typev, LuaValue opts, LuaValue fn) {
        if(!typev.isstring())
            throw new LuaError("hafen.ui.replace(type, opts, fn) expects a type string (e.g. \"inv\")");
        if(!fn.isfunction())
            throw new LuaError("hafen.ui.replace(type, opts, fn) expects a builder function fn(model)");
        final LuaReplacer r = new LuaReplacer(owner, typev.tojstring());
        if(opts.istable()) {
            LuaValue ctx = opts.get("context"); if(ctx.isstring())   r.context = ctx.tojstring();
            LuaValue cap = opts.get("caption"); if(cap.isstring())   r.caption = cap.tojstring();
            LuaValue mf  = opts.get("match");   if(mf.isfunction())  r.matchFn = mf;
        }
        r.builderFn = fn;
        widgetReplacers.add(r);
        owner.replacers.add(r);
        scanForReplace(r);                     // catch an ALREADY-OPEN target (the :reload / register-while-in-world case)
        LuaTable h = new LuaTable();
        h.set("remove", new ZeroArgFunction() {
            public LuaValue call() {
                removeReplacer(owner, r);
                return LuaValue.NIL;
            }
        });
        return h;
    }

    /**
     * Sweep the live widget tree once for a target the replacer would match but that already exists — the {@code
     * :reload} case (the target, e.g. the main inventory, was created before this rebuilt addon layer, so no {@code
     * onWidgetPlaced} will fire for it). For {@code context="main"} on {@code "inv"} the target is the unambiguous
     * public {@code GameUI.maininv}; otherwise scan the widgets of the type's class for a caption/match hit. The
     * server type string is not recorded for an already-live widget, so the scan keys on the Java class instead
     * ({@link #typeClass}) — a bridge-internal detail; the addon's {@code type} string stays the one canonical key.
     */
    private static void scanForReplace(LuaReplacer r) {
        UI u = ui;
        if(u == null)
            return;
        GameUI g = gui();
        if("main".equals(r.context) && "inv".equals(r.type)) {
            if((g != null) && (g.maininv != null)) {
                int id = u.widgetid(g.maininv);        // -1 for a client-side widget (no server id)
                String parentType = (g.maininv.parent != null) ? g.maininv.parent.getClass().getSimpleName() : null;
                if((id >= 0) && !r.handled(id) && matchCriteria(r, id, "inv", null, null, parentType))
                    fireReplace(r, id, g.maininv);
            }
            return;
        }
        Class<? extends Widget> cls = typeClass(r.type);
        if(cls == null)
            return;                                    // unknown type for scanning; the creation path still catches new ones
        Widget rootw = (g != null) ? g : u.root;
        if(rootw == null)
            return;
        for(Widget w : widgetsOfClass(rootw, cls)) {
            int id = u.widgetid(w);
            if((id < 0) || r.handled(id))
                continue;
            String caption = (w instanceof Window) ? ((Window)w).cap : null;
            String parentType = (w.parent != null) ? w.parent.getClass().getSimpleName() : null;
            if(matchCriteria(r, id, r.type, null, caption, parentType))
                fireReplace(r, id, w);
        }
    }

    /**
     * Creation-path match: the placed widget's full descriptor vs the replacer. Checks {@code type} and (for
     * {@code context="main"}) that this is the main inventory — server-placed with {@code place=="inv"} directly
     * under {@code GameUI} (a container inventory is placed inside its own window, so {@code place} is null) — then
     * the shared {@link #matchCriteria} (caption + match fn).
     */
    private static boolean matchOnCreate(LuaReplacer r, int id, String type, String place, String caption, String parentType) {
        if(!r.type.equals(type))
            return false;
        if("main".equals(r.context)
           && !("inv".equals(r.type) && "inv".equals(place) && "GameUI".equals(parentType)))
            return false;
        return matchCriteria(r, id, type, place, caption, parentType);
    }

    /** Shared caption + {@code match(desc)} criteria (type/context are pre-checked by the caller). */
    private static boolean matchCriteria(LuaReplacer r, int id, String type, String place, String caption, String parentType) {
        if((r.caption != null) && !r.caption.equals(caption))
            return false;
        if((r.matchFn != null)
           && !callLua(r.owner, Addon.C_HOOK, r.matchFn, descTable(id, type, place, caption, parentType)).arg1().toboolean())
            return false;
        return true;
    }

    /**
     * Adopt the matched server widget as a hidden {@link LuaModel}, hide the native WINDOW around it (so the whole
     * stock window disappears, not just its content), then call the addon's {@code fn(model)} builder and keep the
     * view it returns. Runs on the UI thread under {@code synchronized(ui)} (from {@code onWidgetPlaced}, or the
     * tick-driven reload scan) — the tree ops are locked; the builder's own {@code hafen.ui.window} re-locks
     * reentrantly.
     *
     * <p><b>031.2: the hide is the ONE record, and the view is bound to it.</b> {@code replace} takes (or joins)
     * the same {@link Addon#hiddenNative} entry {@code widget:hide()} makes, so the window it hides is a window it
     * <i>owns</i> — toggle included — and then fills that record's view with whatever the builder returned. That is
     * the whole wiring: nothing for the addon to call, no second copy of the hide to keep in step, and the menu
     * checkbox reads the view rather than a bookkeeping boolean. A window another addon already owns is refused
     * here, naming it (the engine path cannot throw into Lua, so this one logs and skips).
     */
    private static void fireReplace(final LuaReplacer r, int id, Widget wdg) {
        final UI u = ui;
        if(u == null)
            return;
        final Widget nativeWin = LuaWidget.nativeWindowOf(wdg);   // the wrapper (the "Inventory" Hidewnd), or wdg itself
        LuaWidget.Hidden ex = hiddenOwner(nativeWin);
        if((ex != null) && (ex.owner != r.owner)) {
            log(r.owner, "hafen.ui.replace: " + LuaWidget.typeName(nativeWin) + " is already hidden by the addon \""
                + ownerName(ex.owner) + "\", which owns its toggle too; one window has one owner, so this"
                + " replacement was skipped");
            return;
        }
        assertToggleTarget(r.owner, wdg, nativeWin, "hafen.ui.replace");
        final LuaModel m = new LuaModel(r.owner, id, wdg);
        m.fromReplace = r;
        m.hideRecord = LuaWidget.recordHidden(r.owner, nativeWin);
        synchronized(u) {
            nativeWin.hide();
        }
        models.add(m);
        r.owner.models.add(m);
        r.handled.add(Integer.valueOf(id));
        r.active.add(m);
        // 029.3: the builder is handed the WIDGET ENTITY for the replaced widget — the same value hafen.ui.node(id)
        // or hafen.ui.inventory() gives, with :items(), the three lifecycle verbs and every read on it. The bespoke
        // model handle (:hide/:show/:visible/:items/:on*/:node) is gone: it was the last of the three objects this
        // feature collapses, and every one of its verbs now lives on the entity.
        LuaValue view = callLua(r.owner, Addon.C_WIDGET, r.builderFn, LuaWidget.of(r.owner, wdg)).arg1();
        // 029.2: the builder's hafen.ui.window{} now returns the Widget ENTITY, not a table of closures — so keep
        // the addon's own content widget directly (the same thing the old handle's :destroy() reached through Lua).
        // Anything else the builder may return (nil, a table) simply leaves no view to destroy, as before.
        m.replaceView = LuaWidget.ownedContent(r.owner, LuaWidget.live(LuaWidget.resolve(view)));
        if(m.hideRecord != null)
            m.hideRecord.view = m.replaceView;   // 031.2: from here on the client's own toggle drives the view
    }

    /**
     * Check — rather than assume — that the window a replacement just hid is the object {@code GameUI} toggles
     * (031.2). Two ways it can fail to be one, both reported and neither fatal: the widget has no enclosing
     * {@link Window} at all (nothing for a toggle to own — the view is then the addon's to show and hide), and,
     * for the main inventory, the wrapper not being {@code maininv.parent}, which is what {@code togglewnd(invwnd)}
     * is called with. On the normal path both are silent, so an in-game replace that logs nothing has proved the
     * identity the seam rests on. {@code where} names the caller, since 032.1 gave it two — the verb (which
     * refuses the first case outright, so only the second can fire) and the legacy placement path (which can only
     * log, never throw into the engine).
     */
    private static void assertToggleTarget(Addon owner, Widget wdg, Widget nativeWin, String where) {
        if(!(nativeWin instanceof Window)) {
            log(owner, where + ": " + LuaWidget.typeName(wdg) + " is not inside a window, so there is no"
                + " client toggle to take over — showing and hiding your view is yours to drive");
            return;
        }
        GameUI g = gui();
        if((g != null) && (wdg == g.maininv) && (nativeWin != g.maininv.parent))
            log(owner, where + ": internal — the hidden window is not the main inventory's own wrapper, so"
                + " the client's Tab toggle will not follow this replacement");
    }

    /** The server type string → the Java widget class, for scanning an already-open target (see {@link #scanForReplace}). */
    private static Class<? extends Widget> typeClass(String type) {
        if("inv".equals(type))  return Inventory.class;
        if("epry".equals(type)) return Equipory.class;
        if("chr".equals(type))  return CharWnd.class;
        if("wnd".equals(type))  return Window.class;
        return null;
    }

    /** Every widget of a class in a subtree ({@link Widget#children(Class)} is a deep traversal), typed as {@code Widget}. */
    @SuppressWarnings("unchecked")
    private static Set<Widget> widgetsOfClass(Widget root, Class<? extends Widget> cls) {
        return (Set<Widget>)(Set<?>)root.children(cls);
    }

    /**
     * Remove a replacer (the handle's {@code :remove()}, a live toggle-off): stop matching, then UNDO every active
     * replacement — restore the native window's original visibility and destroy the addon's view. (Teardown on
     * reload/disable takes a different path: {@link #teardownModels} restores + {@link #destroyWidgets} destroys.)
     */
    private static void removeReplacer(Addon owner, LuaReplacer r) {
        r.alive = false;
        widgetReplacers.remove(r);
        owner.replacers.remove(r);
        for(LuaModel m : new ArrayList<LuaModel>(r.active))
            undoReplace(m);
        r.active.clear();
    }

    /**
     * Undo one active replacement (live {@code :remove()}): drop the model, give the native window and its toggle
     * back under the one rule, then destroy the view. The order is load-bearing — the rule reads the view's
     * visibility, so it has to be asked before the view is killed.
     */
    private static void undoReplace(LuaModel m) {
        m.alive = false;
        models.remove(m);
        m.owner.models.remove(m);
        releaseHidden(m.hideRecord);    // 031.2: as the user was seeing it — view open ⇒ the stock window opens
        m.hideRecord = null;
        destroyReplaceView(m);
    }

    /**
     * Destroy a replace model's view, if any — the Java side of what the old table handle's {@code :destroy()} did.
     * Since 032.1 the killing itself is {@link #destroyView}, shared with the verb: this method is only the model's
     * own bookkeeping around it.
     */
    private static void destroyReplaceView(LuaModel m) {
        AddonWidget v = m.replaceView;
        m.replaceView = null;
        if(m.hideRecord != null)
            m.hideRecord.view = null;   // 031.2: the toggle has nothing left to drive (it swallows again)
        destroyView(m.owner, v);
    }

    /**
     * Tear down every replacer this addon owns (reload/disable, P2): mark each dead and stop it matching. The
     * adopted models are un-hidden by {@link #teardownModels} and the views destroyed by {@link #destroyWidgets}
     * (both run in the same {@link #teardown}), so this only clears the dispatch state.
     */
    static void teardownReplacers(Addon a) {
        for(LuaReplacer r : a.replacers) {
            r.alive = false;
            r.active.clear();
        }
        widgetReplacers.removeAll(a.replacers);
        a.replacers.clear();
    }



    /** Any addon currently has a HUD overlay? (Decides whether to queue the per-frame afterdraw.) */
    static boolean anyHudOverlays() {
        for(Addon a : addons)
            if(!a.hudOverlays.isEmpty())
                return true;
        return false;
    }

    /** Any addon currently has a gob overlay? (Gates the sweep and the idle-attrib cleanup.) */
    static boolean anyGobOverlays() {
        for(Addon a : addons)
            if(!a.gobOverlays.isEmpty())
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
                    if(o.active)
                        callLua(a, Addon.C_DRAW, o.fn, gt, w, h);
                }
            } finally {
                hudGout.unbind();
            }
        }
    }

    /**
     * Throttled gob-overlay sweep (2b): attach a shared {@link LuaGobOverlay} to every gob that matches at
     * least one active filter — like {@code SpeakerIcon.sweep}, but with dynamic (Lua) filters, so it is
     * rate-limited ({@link #GOB_SWEEP_INTERVAL}). Attach-only: a gob that later stops matching keeps an idle
     * attrib that draws nothing (the per-frame draw re-checks filters); the attribs are detached wholesale
     * once no addon wants gob overlays (handle {@code :remove()} / teardown). Runs in {@code tick} on the UI
     * thread, so a gob's filter is watchdog-armed + CPU-accounted via {@link #callLua}.
     */
    static void sweepGobOverlays() {
        if(clock - lastGobSweep < GOB_SWEEP_INTERVAL)
            return;
        lastGobSweep = clock;
        if(!anyGobOverlays())
            return;
        for(Gob g : allGobs()) {
            if(g.getattr(LuaGobOverlay.class) != null)
                continue;                       // already tracked (the draw pass re-checks filters)
            if(!gobMatchesAny(g))
                continue;
            try {
                g.setattr(new LuaGobOverlay(g));
            } catch(Loading l) {
                /* the gob's render slots aren't ready yet — retry on the next sweep */
            } catch(RuntimeException e) {
                /* never break the tick over a single gob */
            }
        }
    }

    /** Does {@code g} match any addon's active gob-overlay filter? (Used by the sweep.) */
    private static boolean gobMatchesAny(Gob g) {
        for(Addon a : addons)
            for(GobOverlay o : a.gobOverlays)
                if(o.active && gobFilterMatch(o, g))
                    return true;
        return false;
    }

    /**
     * Evaluate one gob overlay's filter against a gob. A string filter is a cheap Java substring match on the
     * gob's resource name; a function filter is called with the owner's interned {@link LuaGob} object (D-044)
     * through {@link #callLua} (watchdog-armed, error-isolated, CPU-accounted) — an error drops the match.
     */
    private static boolean gobFilterMatch(GobOverlay o, Gob g) {
        LuaValue f = o.filter;
        if(f.isstring()) {
            String name = gobName(g);
            return (name != null) && name.contains(f.tojstring());
        }
        return callLua(o.owner, Addon.C_DRAW, f, LuaGob.of(o.owner, g.id)).arg1().toboolean();
    }

    /**
     * Paint every matching addon's gob overlay for one gob — called from {@link LuaGobOverlay#draw} with the
     * gob's projected screen point {@code sc}. Re-checks each filter and invokes the matching draw callbacks
     * {@code draw(g, gob, sx, sy)} through {@link #callLua}, where {@code gob} is that addon's interned
     * {@link LuaGob} object (D-044) — so the callback reads the gob LIVE instead of from a per-frame snapshot.
     * On the UI thread (inside the Render2D pass of {@code UI.draw}).
     */
    static void paintGobOverlays(Gob gob, GOut g, LuaGOut gwrap, Coord sc) {
        LuaValue sx = LuaValue.valueOf(sc.x), sy = LuaValue.valueOf(sc.y);
        for(Addon a : addons) {
            if(a.gobOverlays.isEmpty())
                continue;
            for(GobOverlay o : a.gobOverlays) {
                if(!o.active)
                    continue;
                if(!gobFilterMatch(o, gob))
                    continue;
                LuaTable gt = gwrap.bind(g, a);
                try {
                    callLua(a, Addon.C_DRAW, o.draw, gt, LuaGob.of(a, gob.id), sx, sy);
                } finally {
                    gwrap.unbind();
                }
            }
        }
    }

    /**
     * Detach every {@link LuaGobOverlay} attrib from all live gobs (best-effort; no session/world → no-op).
     * Mutating a gob's render slots is done under {@code synchronized(ui)} — like {@link #destroyWidgets} —
     * because teardown may run off the UI thread (session bind), while the sweep's {@code setattr} is already
     * on the UI thread (inside {@code tick}).
     */
    static void detachGobOverlays() {
        UI u = ui;
        Runnable detach = () -> {
            try {
                for(Gob g : allGobs()) {
                    if(g.getattr(LuaGobOverlay.class) != null)
                        g.delattr(LuaGobOverlay.class);
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
}
