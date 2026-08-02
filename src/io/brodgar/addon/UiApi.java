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
import java.util.concurrent.CopyOnWriteArrayList;


import static io.brodgar.addon.AddonManager.*;

/**
 * The custom-UI + widget-introspection subsystem:
 * {@code hafen.ui} — custom windows/widgets (2a), HUD + world-space gob overlays (2b), selector lookups and
 * selector events (030), the window-toggle seam + the {@code widget:replace(view)} substitution (031/032), and the
 * read-only widget-tree walk + hit-testing (W1/W2 node API). Owns the subscription registries + overlay paint
 * state. The widget-placement seam {@code onWidgetPlaced} (called from {@code haven.UI}) stays a facade in
 * {@link AddonManager} and delegates here; the tick drives {@link #pollReplaced}/{@link #pollWatches}/
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

    // -- container subscriptions (029.3): widget:onItemAdded/:onItemRemoved/:onDestroy. A FLAT global list of the
    // widgets SOMEBODY is listening to, diffed each tick (pollWatches) for WItem add/remove (a create/cdestroy, not
    // a uimsg) and for the widget's own death. hasSub-GATED by construction — an entry exists only while at least
    // one callback is set, so a widget nobody subscribed to is never polled and an idle client pays one isEmpty().
    // Owned copies live on each Addon for teardown. Session-scoped (cleared per init; the tree is rebuilt).
    private static final List<LuaWidget.Watch> watches = new CopyOnWriteArrayList<LuaWidget.Watch>();

    // ===== the widget-placement seam (the body behind AddonManager.onWidgetPlaced) =====
    // ONE consumer since 032.2: the 030.2 selector subscriptions, which see the LIVE widget itself. The
    // {id,type,place,caption,parentType} descriptor that used to be built here for hafen.ui.replace went with it —
    // and with it the NewWidget seam that recorded the server type string, since nothing reads it any more.
    static void onWidgetPlaced(int id, Widget wdg) {
        if(!selectorWatches.isEmpty())
            offerPlaced(wdg, id);
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
        // separate, explicit act it always should have been.
        // hafen.ui.replace(type, opts, fn) is GONE too (032.2, hard cut — it reads as plain nil). It was the LAST
        // place that named a window a different way: its {id,type,place,caption,parentType} descriptor (D-024) was
        // a second vocabulary for "which window", and it was PRIVILEGED — only it could bind a view to a hidden
        // native window, so an addon doing the same by hand got a swallowed toggle and nothing driving it. Both
        // halves are ordinary API now: hafen.ui.on(sel, "appear", fn) does the WAITING (and fires for what is
        // already open, D-068), and widget:replace(view) does the REPLACING. The whole pattern is
        //     hafen.ui.on("inventory[title=Inventory]", "appear", function(w) w:replace(buildMyView(w)) end)
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

    /** Session init: drop every per-session widget record (from AddonManager.init). */
    static void resetSession() {
        lastGobSweep = 0;             // 2b: sweep gob overlays promptly on the new session
        watches.clear();
        selectorWatches.clear();      // 030.2: the tree of the session just ended; nothing matches any more
        pending.clear();
        if(consoleOwner != null) {
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
     * Per-tick destroy-detection for the substitutions themselves (032.1, UI thread): the server destroying a
     * replaced window — a chest closed, a relog — <b>ends the substitution</b>, so the record goes and the view dies
     * with it rather than hanging over a container that no longer exists.
     *
     * <p><b>Keyed on the hide record, not on a model</b> (D-071): a relationship's lifetime is watched on the
     * relationship. The retired {@code hafen.ui.replace} minted a {@code LuaModel} around the widget it adopted and
     * polled that; the verb adopts nothing, so the thing to watch is the record that IS the substitution — which is
     * why 032.2 could delete the model and its poll outright rather than port them. The death test is
     * {@link #stillHidable}, the same two-branch guard the teardown uses (by server id when the window has one, by
     * tree reachability for a client-side wrapper like the inventory's, which never dies). A record with no view
     * bound — a bare {@code w:hide()} — is not a substitution and is left alone.
     *
     * <p>Gated on the {@link LuaWidget#anyHidden} volatile the toggle seam already maintains, so a client that hides
     * nothing pays one read per tick. {@link #endReplacement} is idempotent, so this sweep racing an undo or a
     * teardown over the same record is harmless.
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
