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
 * {@code hafen.ui} — custom windows/widgets (2a), HUD + world-space gob overlays (2b), widget-creation
 * observers (3a), adopted-widget models (3b), widget replacers (3c), and the read-only widget-tree walk +
 * hit-testing (W1/W2 node API). Owns the observer/model/replacer registries + overlay paint state. The
 * widget-creation seams {@code onWidgetCreated}/{@code onWidgetPlaced} (called from {@code haven.UI}) stay
 * facades in {@link AddonManager} and delegate here; the tick drives {@link #pollModels}/
 * {@link #sweepGobOverlays}/{@link #anyHudOverlays}; {@code haven}-side {@code LuaGobOverlay.draw} calls
 * {@link #paintGobOverlays}. Shared gob-read/engine helpers stay in {@link AddonManager}. Not instantiable.
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

    // -- widget-creation interception (spec 08 / Phase 3a): observe server widgets as the server creates them ---
    // hafen.ui.onWidgetCreate(fn) fires fn(desc) for every SERVER widget as it is placed into the tree, where
    // desc = {id, type, place, caption, parentType} (the targeting descriptor, D-024). Two UI.java edits feed it:
    // NewWidget.run records the server type name (onWidgetCreated), AddWidget.run fires onWidgetPlaced once the
    // widget is in the tree — the first moment place + parent exist. onWidgetPlaced runs inside AddWidget.run's
    // synchronized(ui) block (the monitor tick/draw hold), so observer Lua never races other Lua. A FLAT list
    // (observers watch EVERY creation, not one keyed target); globally empty = a near-zero fast path, so an
    // unobserving client pays only an isEmpty() check per placement. widgetTypes holds only in-flight creations
    // (recorded at NewWidget, removed at the matching AddWidget) and is recorded only while an observer exists;
    // owned observer copies live on each Addon for teardown. Both are session-scoped (cleared per init).
    private static final Map<Integer, String> widgetTypes = new ConcurrentHashMap<Integer, String>();
    private static final List<LuaWidgetObserver> widgetObservers = new CopyOnWriteArrayList<LuaWidgetObserver>();

    // -- adopted widget models (spec 08 / Phase 3b): hafen.ui.adopt(id) wraps a live server-bound widget so an
    // addon can hide it as a headless model + present a custom view (D-009). A FLAT global list, polled each tick
    // (pollModels) for item add/remove (a WItem create/cdestroy, not a uimsg) and server destroy (its id stops
    // mapping to the widget); globally empty = a near-zero fast path. Owned copies live on each Addon for teardown
    // (which un-hides anything the addon hid, restoring the stock UI). Session-scoped (cleared per init).
    private static final List<LuaModel> models = new CopyOnWriteArrayList<LuaModel>();

    // -- widget replacers (spec 08 / Phase 3c): hafen.ui.replace(type, opts, fn) — the high-level sugar over 3a+3b.
    // Watch for a server widget matching a descriptor, then adopt+hide it and hand the addon a custom view (D-009).
    // A FLAT global list consulted at widget placement (onWidgetPlaced, alongside the observers); registration also
    // SCANS the live tree once to catch an already-open target (the :reload case, where no creation event fires).
    // Owned copies live on each Addon for teardown. Session-scoped (cleared per init; widget ids are per-session).
    private static final List<LuaReplacer> widgetReplacers = new CopyOnWriteArrayList<LuaReplacer>();

    // ===== widget-creation seams (bodies behind AddonManager.onWidgetCreated/onWidgetPlaced) =====
    static void onWidgetCreated(int id, String typenm) {
        if((widgetObservers.isEmpty() && widgetReplacers.isEmpty()) || (typenm == null))
            return;                                   // fast path: nobody is observing/replacing, or no type string
        widgetTypes.put(Integer.valueOf(id), typenm);
    }

    static void onWidgetPlaced(int id, Widget wdg, Widget pwdg, Object[] pargs) {
        if(widgetObservers.isEmpty() && widgetReplacers.isEmpty())
            return;                                   // fast path: no widget-create observers/replacers anywhere
        String type = widgetTypes.remove(Integer.valueOf(id));
        String place = ((pargs != null) && (pargs.length > 0) && (pargs[0] instanceof String))
                       ? (String)pargs[0] : null;
        String parentType = (pwdg == null) ? null : pwdg.getClass().getSimpleName();
        String caption = (wdg instanceof Window) ? ((Window)wdg).cap : null;
        for(LuaWidgetObserver o : widgetObservers) {  // copy-on-write: an observer may :remove() itself here
            if(o.alive)
                o.invoke(id, type, place, caption, parentType);
        }
        // 3c: also offer this newly-placed widget to every replacer (a target opened AFTER the replacer registered).
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
        // hafen.ui.onWidgetCreate(fn) — observe the server's own UI as it is built (spec 08, Phase 3a). fn(desc)
        // runs for every SERVER widget as it is placed into the tree, with desc = {id, type, place, caption,
        // parentType} (the targeting descriptor, D-024) — e.g. the inventory is {type="inv", place="inv",
        // parentType="GameUI"}; a cupboard is {type="wnd", place="misc", caption="Cupboard", parentType="GameUI"}.
        // A HUD-placed window always reports parentType="GameUI"; item widgets streaming into an inventory report
        // their container instead, so an addon filters by parentType/type/place. This slice is observe-only
        // (adopting the real widget as a hidden model + drawing a custom view is a later slice); the return is
        // ignored. Returns a handle with :remove(); auto-removed on reload/disable (P2). Register any time (no
        // live target needed) — the file body catches the login window burst too.
        uiT.set("onWidgetCreate", new OneArgFunction() {
            public LuaValue call(LuaValue fn) {
                return newWidgetObserver(owner, fn);
            }
        });
        // hafen.ui.adopt(id) — adopt a live SERVER widget by its id (the desc.id an onWidgetCreate observer hands
        // out) as a MODEL (spec 08 / Phase 3b): keep the real, server-bound widget as a hidden model and present
        // your own view over it — "wrap, don't reimplement" (D-009). Returns a model handle, or nil if no widget
        // has that id (e.g. it was already destroyed). The handle:
        //   :hide() / :show()      -- toggle the widget's visibility (chainable). A HIDDEN server widget stays
        //                             bound to its id, so it keeps receiving item adds / updates — a headless model.
        //   :visible()             -- is it currently visible?
        //   :raw()                 -- the server widget id (a WidgetRef); the facade-safe escape hatch.
        //   :items()               -- array of Item snapshots (same shape as hafen.items.inventory) off the
        //                             widget's WItem children; empty for a non-inventory widget. READ-ONLY:
        //                             item verbs (take/drop/transfer/use) are gameplay actions -> the gated
        //                             actions tier (Phase 4, D-010/D-025), not here.
        //   :onItemAdded(fn)/:onItemRemoved(fn)  -- fn(item) when an item enters/leaves (poll-diffed each tick).
        //   :onDestroy(fn)         -- fn() once when the SERVER destroys the widget (the view must die with it).
        // Bridge-owned (P2): :reload/disable drops the model and UN-HIDES anything it hid (restoring the stock UI).
        // Adopt from an onWidgetCreate observer (which fires as the widget is built); re-finding an ALREADY-open
        // window by type/descriptor is hafen.ui.replace (Phase 3c).
        uiT.set("adopt", new OneArgFunction() {
            public LuaValue call(LuaValue id) {
                return newModel(owner, id);
            }
        });
        // hafen.ui.replace(type, opts, fn) — the high-level "replace a native window with your own view" sugar over
        // 3a (observe) + 3b (adopt), spec 08 / Phase 3c. It watches for a SERVER widget matching a descriptor, then
        // adopts the real widget as a hidden MODEL and calls fn(model); fn draws a custom VIEW (e.g. a hafen.ui.window)
        // and RETURNS it — "wrap, don't reimplement" (D-009). The native window is hidden but stays server-bound, so
        // model:items()/events keep working; disabling/reloading the addon (or handle:remove()) UN-HIDES it, restoring
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
        // hafen.ui.root() / hafen.ui.node(id) / hafen.ui.at(x,y) — the widget-tree entry points (spec 20 W1/W2,
        // rebuilt on the ONE entity by 029-widget-oop). Walk ANY widget's children to arbitrary depth from Lua (the
        // generic reader that complements the spec-14 typed adapters). Two entry points for two distinct inputs
        // (D-012): root() = the top of the WHOLE client tree (discovery, walk DOWN to any open window); node(id) =
        // the Widget object for a SERVER widget id (a desc.id from onWidgetCreate, a model:raw(), or another
        // widget's :id()) — nil if it doesn't resolve. All of them hand back the SAME type: opaque, facade-safe
        // userdata (no raw haven.Widget crosses into Lua, P1/D-017), INTERNED per addon — so two lookups of one
        // live widget are the SAME value and `==` is the identity test (:same() is GONE, 029.1). Not an
        // owned-registry entry: it checks liveness per access, and once its widget leaves the tree it nulls its
        // reference (no pin, D-041), every read answers nil/empty and :exists() is false. The entity:
        //   :type()          -- class simple name (e.g. "Inventory", "Label", "Button")
        //   :id()            -- server widget id (int), or nil if the widget is NOT server-bound (client-only)
        //   :children()      -- array of child Widget objects, in tree order (empty if a leaf)
        //   :parent()        -- parent Widget object, or nil at the root
        //   :pos()           -- {x=,y=} position within the parent (widget-local px)
        //   :size()          -- {x=,y=}
        //   :visible()       -- boolean
        //   :text()          -- best-effort text for text-bearing widgets (Label/Button/Window/TextEntry), else nil
        //   :exists()        -- is it still in the tree? (the one read that always answers)
        //   :info()          -- the snapshot escape hatch {type,id,pos,size,visible,text}
        //   :walk(fn)        -- depth-first: fn(widget, depth); return false to PRUNE the subtree
        //   :at(coord)       -- W2: the DEEPEST Widget object under a {x=,y=} root-coord point WITHIN this subtree
        //   :rootpos()       -- W2: {x=,y=} its top-left in root coords (with :size() = a highlight box)
        // READ-ONLY: to ACT, read a server-bound widget's :id() and pass it to the gated hafen.act.raw (D-025) — no
        // new action surface, no new gate (reading the tree is ungated client-side data).
        uiT.set("root", new ZeroArgFunction() {
            public LuaValue call() { return nodeRoot(owner); }
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
        uiT.set("at", new TwoArgFunction() {
            public LuaValue call(LuaValue x, LuaValue y) { return nodeAt(owner, x, y); }
        });
        hafen.set("ui", uiT);
    }

    /** Session init: drop per-session widget-type records, adopted models, and replacers (from AddonManager.init). */
    static void resetSession() {
        lastGobSweep = 0;             // 2b: sweep gob overlays promptly on the new session
        widgetTypes.clear();
        models.clear();
        widgetReplacers.clear();
        if(consoleOwner != null) {
            consoleOwner.models.clear();
            consoleOwner.replacers.clear();
        }
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
        return uiHandle(owner, content, rootw, isWindow);
    }

    /**
     * The Lua handle for a {@link #newUi} element: {@code :move/:show/:hide/:visible/:pack/:size/:destroy}.
     * Geometry ops target the root (the window chrome, or the widget); {@code :size} resizes the content
     * (and repacks a window). {@code :pack} is a no-op for a bare widget (a leaf has no children to fit).
     * Handle methods are safe to call after teardown (they act on a detached widget).
     */
    private static LuaValue uiHandle(final Addon owner, final AddonWidget content, final Widget rootw,
                                     final boolean isWindow) {
        LuaTable h = new LuaTable();
        h.set("move", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                rootw.move(Coord.of(a.arg(2).toint(), a.arg(3).toint()));
                return a.arg1();          // return the handle for chaining (win:move(..):show())
            }
        });
        h.set("show", new VarArgFunction() {
            public Varargs invoke(Varargs a) { rootw.show(); return a.arg1(); }
        });
        h.set("hide", new VarArgFunction() {
            public Varargs invoke(Varargs a) { rootw.hide(); return a.arg1(); }
        });
        h.set("visible", new ZeroArgFunction() {
            public LuaValue call() { return LuaValue.valueOf(rootw.visible()); }
        });
        h.set("pack", new VarArgFunction() {
            public Varargs invoke(Varargs a) { if(isWindow) rootw.pack(); return a.arg1(); }
        });
        h.set("size", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                content.resize(Coord.of(a.arg(2).toint(), a.arg(3).toint()));
                if(isWindow)
                    rootw.pack();
                return a.arg1();
            }
        });
        h.set("destroy", new ZeroArgFunction() {
            public LuaValue call() {
                content.kill();
                owner.widgets.remove(content);
                return LuaValue.NIL;
            }
        });
        return h;
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


    // ------------------------------------------------------- widget-creation observers (hafen.ui, 3a)

    /**
     * Register a widget-creation observer ({@code hafen.ui.onWidgetCreate(fn)}, spec 08 / Phase 3a): install
     * {@code fn} in the global observer list (fired by {@link #onWidgetPlaced} for every server widget) and in the
     * addon's owned-resource registry (dropped on reload/disable, P2). Returns the Lua handle ({@code :remove()}).
     * Needs no live target, so it can be registered any time — the file body is fine (and catches the login
     * window burst). An observer watches EVERY creation (there is no per-target keying), so this is a flat list,
     * not a per-name map like the hook levels.
     */
    private static LuaValue newWidgetObserver(final Addon owner, LuaValue fn) {
        if(!fn.isfunction())
            throw new LuaError("hafen.ui.onWidgetCreate(fn) expects a function");
        final LuaWidgetObserver o = new LuaWidgetObserver(owner, fn);
        widgetObservers.add(o);
        owner.widgetObservers.add(o);
        LuaTable handle = new LuaTable();
        handle.set("remove", new ZeroArgFunction() {
            public LuaValue call() {
                removeWidgetObserver(owner, o);
                return LuaValue.NIL;
            }
        });
        return handle;
    }

    /** Remove one widget-creation observer: stop it firing + drop it from both lists (the handle's {@code :remove()}). */
    private static void removeWidgetObserver(Addon owner, LuaWidgetObserver o) {
        o.alive = false;
        widgetObservers.remove(o);
        owner.widgetObservers.remove(o);
    }

    /** Mark dead + drop every widget-creation observer this addon owns (teardown on reload/disable, P2). */
    static void teardownWidgetObservers(Addon a) {
        for(LuaWidgetObserver o : a.widgetObservers)
            o.alive = false;
        widgetObservers.removeAll(a.widgetObservers);
        a.widgetObservers.clear();
    }

    // -------------------------------------------------------------- adopted widget models (hafen.ui, 3b)

    /**
     * Adopt a live server widget as a {@link LuaModel} ({@code hafen.ui.adopt(id)}, spec 08 / Phase 3b): look the
     * widget up by its server id ({@code desc.id}), wrap it, register it globally (polled each tick) + in the
     * addon's owned-resource registry (P2), and return the Lua handle. Returns {@code nil} when no widget has that
     * id (it may have been destroyed) — the caller tests it the idiomatic way. A non-number id is a clear error.
     */
    private static LuaValue newModel(final Addon owner, LuaValue idv) {
        if(!idv.isnumber())
            throw new LuaError("hafen.ui.adopt(id) expects a widget id (number)");
        UI u = ui;
        if(u == null)
            return LuaValue.NIL;
        int id = idv.toint();
        Widget w = u.getwidget(id);
        if(w == null)
            return LuaValue.NIL;                 // no server widget with that id (already destroyed, etc.)
        LuaModel m = new LuaModel(owner, id, w);
        models.add(m);
        owner.models.add(m);
        return modelHandle(m);
    }

    /**
     * The Lua handle for an adopted {@link LuaModel}: {@code :hide/:show/:visible/:raw/:items} +
     * {@code :onItemAdded/:onItemRemoved/:onDestroy}. Geometry-free (a model is the real widget, not our chrome);
     * the mutating item verbs are deliberately absent (gated actions tier, Phase 4). Every method no-ops safely
     * once the model is dead (server-destroyed or torn down). The colon-call convention passes {@code self} as
     * arg1, so a callback setter reads {@code arg(2)} and returns arg1 (the handle) for chaining.
     */
    private static LuaValue modelHandle(final LuaModel m) {
        LuaTable h = new LuaTable();
        h.set("hide", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                if(m.alive) { m.hideTarget.hide(); m.hidden = true; }   // stays server-bound → still a live model
                return a.arg1();
            }
        });
        h.set("show", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                if(m.alive) { m.hideTarget.show(); m.hidden = false; }
                return a.arg1();
            }
        });
        h.set("visible", new ZeroArgFunction() {
            public LuaValue call() { return LuaValue.valueOf(m.alive && m.hideTarget.visible()); }
        });
        h.set("raw", new ZeroArgFunction() {
            public LuaValue call() { return LuaValue.valueOf(m.id); }   // the server id: a facade-safe WidgetRef (P1)
        });
        h.set("items", new ZeroArgFunction() {
            public LuaValue call() {
                LuaTable out = new LuaTable();
                if(m.alive) {
                    int i = 0;
                    for(WItem w : m.wdg.children(WItem.class))
                        out.set(++i, CharApi.itemSnapshot(w.item, cellPos(w)));
                }
                return out;
            }
        });
        h.set("onItemAdded", new VarArgFunction() {
            public Varargs invoke(Varargs a) { m.onItemAdded = fnOrNull(a.arg(2)); return a.arg1(); }
        });
        h.set("onItemRemoved", new VarArgFunction() {
            public Varargs invoke(Varargs a) { m.onItemRemoved = fnOrNull(a.arg(2)); return a.arg1(); }
        });
        h.set("onDestroy", new VarArgFunction() {
            public Varargs invoke(Varargs a) { m.onDestroy = fnOrNull(a.arg(2)); return a.arg1(); }
        });
        // :node() — sugar for hafen.ui.node(model:raw()): the Widget OBJECT for this model's server widget (029.1),
        // so an addon can walk the adopted widget's full child tree generically. nil once the model is dead.
        h.set("node", new ZeroArgFunction() {
            public LuaValue call() {
                return m.alive ? LuaWidget.of(m.owner, m.wdg) : LuaValue.NIL;
            }
        });
        return h;
    }

    /** A Lua function value, or {@code null} if it is not a function (an unset callback). */
    private static LuaValue fnOrNull(LuaValue v) {
        return v.isfunction() ? v : null;
    }

    /**
     * Per-tick poll of every adopted model (UI thread, spec 08 / Phase 3b). For each live model: if the server
     * destroyed the widget (its id no longer maps to it), fire {@code onDestroy} once and drop the model; else
     * diff its {@link WItem} children for add/remove when a listener is registered. Fast-paths out when nothing is
     * adopted. Item add/remove is a widget create/{@code cdestroy}, not a {@code uimsg}, so it can only be seen by
     * polling — the same discipline as the buff/study adapters.
     */
    static void pollModels() {
        if(models.isEmpty())
            return;
        UI u = ui;
        if(u == null)
            return;
        for(LuaModel m : models) {           // copy-on-write: a callback may adopt/drop a model here
            if(!m.alive)
                continue;
            if(u.getwidget(m.id) != m.wdg) {  // server destroyed it (or reused the id) → the model is gone
                m.alive = false;
                models.remove(m);
                m.owner.models.remove(m);
                if(m.onDestroy != null)
                    callLua(m.owner, Addon.C_WIDGET, m.onDestroy);
                if(m.fromReplace != null)     // 3c: the view dies with the native widget (spec 08); notify the replacer
                    m.fromReplace.active.remove(m);
                destroyReplaceView(m);
                continue;
            }
            if((m.onItemAdded != null) || (m.onItemRemoved != null))
                pollModelItems(m);
        }
    }

    /** Diff one model's {@link WItem} children against its cache, firing onItemAdded/onItemRemoved (mirrors BuffsAdapter). */
    private static void pollModelItems(LuaModel m) {
        Set<WItem> present = new LinkedHashSet<WItem>();
        for(WItem w : m.wdg.children(WItem.class))
            present.add(w);
        for(WItem w : present) {                        // additions (unseen items)
            if(!m.items.containsKey(w)) {
                LuaValue snap = CharApi.itemSnapshot(w.item, cellPos(w));
                m.items.put(w, snap);
                if(m.onItemAdded != null)
                    callLua(m.owner, Addon.C_WIDGET, m.onItemAdded, snap);
            }
        }
        for(Iterator<Map.Entry<WItem, LuaValue>> it = m.items.entrySet().iterator(); it.hasNext();) {
            Map.Entry<WItem, LuaValue> e = it.next();   // removals (items that left)
            if(!present.contains(e.getKey())) {
                LuaValue snap = e.getValue();
                it.remove();
                if(m.onItemRemoved != null)
                    callLua(m.owner, Addon.C_WIDGET, m.onItemRemoved, snap);
            }
        }
    }

    /**
     * Tear down every adopted model this addon owns (reload/disable, P2): mark each dead, drop it from the global
     * poll list, and — critically — <b>restore</b> a window the addon had hidden to its ORIGINAL visibility, so
     * disabling a UI-replacement addon restores the stock UI (spec 08). "Restore", not blindly "show": a plain
     * {@code adopt} model hid a visible grid (→ show it), but a {@code replace} model hid the native window (the
     * {@code Hidewnd} around {@code maininv}), which is hidden-by-default (→ put it back to hidden), so we replay
     * {@link LuaModel#hideTargetOrigVisible}. Only a still-live server-bound widget is touched (a stale or
     * already-destroyed one is skipped). The restore is a tree op → done under {@code synchronized(ui)}, like
     * {@link #destroyWidgets}.
     */
    static void teardownModels(Addon a) {
        if(a.models.isEmpty())
            return;
        final UI u = ui;
        final List<LuaModel> ms = new ArrayList<LuaModel>(a.models);
        a.models.clear();
        models.removeAll(ms);
        Runnable restore = () -> {
            for(LuaModel m : ms) {
                m.alive = false;
                if(m.hidden && (u != null) && (u.getwidget(m.id) == m.wdg) && (m.hideTarget != null)) {
                    try {
                        if(m.hideTargetOrigVisible) m.hideTarget.show(); else m.hideTarget.hide();
                    } catch(RuntimeException e) { /* best-effort: never abort teardown */ }
                }
            }
        };
        if(u != null) {
            synchronized(u) { restore.run(); }
        } else {
            restore.run();
        }
    }

    // -------------------------------------------------- generic widget-tree introspection (hafen.ui, W1, spec 20)

    /**
     * {@code hafen.ui.root()} — the {@link LuaWidget} entity for {@code ui.root}, the top of the whole client tree
     * (spec 20, W1). Returns {@code nil} if no UI is up yet. From here an addon walks DOWN to any open window.
     */
    private static LuaValue nodeRoot(Addon owner) {
        UI u = ui;
        if((u == null) || (u.root == null))
            return LuaValue.NIL;
        return LuaWidget.of(owner, u.root);
    }

    /**
     * {@code hafen.ui.node(id)} — the {@link LuaWidget} entity for a SERVER widget id (a {@code desc.id}, a
     * {@code model:raw()}, or another widget's {@code :id()}), or {@code nil} if the id doesn't resolve (no such
     * server widget, or it was destroyed). A non-number id is a clear error, like {@code hafen.ui.adopt}.
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
     * stock window disappears, not just its content — recording its original visibility for teardown), then call
     * the addon's {@code fn(model)} builder and keep the view it returns. Runs on the UI thread under {@code
     * synchronized(ui)} (from {@code onWidgetPlaced}, or the tick-driven reload scan) — the tree ops are locked; the
     * builder's own {@code hafen.ui.window} re-locks reentrantly.
     */
    private static void fireReplace(final LuaReplacer r, int id, Widget wdg) {
        final UI u = ui;
        if(u == null)
            return;
        final LuaModel m = new LuaModel(r.owner, id, wdg);
        m.fromReplace = r;
        final Widget nativeWin = nativeWindowOf(wdg);   // the wrapper (e.g. the "Inventory" Hidewnd), or the widget itself
        m.hideTarget = nativeWin;
        m.hideTargetOrigVisible = nativeWin.visible();
        synchronized(u) {
            nativeWin.hide();
        }
        m.hidden = true;
        models.add(m);
        r.owner.models.add(m);
        r.handled.add(Integer.valueOf(id));
        r.active.add(m);
        LuaValue view = callLua(r.owner, Addon.C_WIDGET, r.builderFn, modelHandle(m)).arg1();   // fn(model) -> the addon's view handle
        m.replaceView = ((view != null) && view.istable()) ? view : null;
    }

    /** The nearest enclosing {@link Window} of a widget (or the widget itself if none) — the native window to hide. */
    private static Widget nativeWindowOf(Widget w) {
        for(Widget p = w; p != null; p = p.parent) {
            if(p instanceof Window)
                return p;
        }
        return w;
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

    /** Undo one active replacement (live {@code :remove()}): drop the model, restore the native window, destroy the view. */
    private static void undoReplace(LuaModel m) {
        m.alive = false;
        models.remove(m);
        m.owner.models.remove(m);
        final UI u = ui;
        if(m.hidden && (u != null) && (u.getwidget(m.id) == m.wdg) && (m.hideTarget != null)) {
            synchronized(u) {
                try {
                    if(m.hideTargetOrigVisible) m.hideTarget.show(); else m.hideTarget.hide();
                } catch(RuntimeException e) { /* best-effort */ }
            }
        }
        destroyReplaceView(m);
    }

    /** Destroy a replace model's view (call the Lua handle's {@code :destroy()}), if any. Idempotent. */
    private static void destroyReplaceView(LuaModel m) {
        if((m.replaceView != null) && m.replaceView.istable()) {
            LuaValue d = m.replaceView.get("destroy");
            if(d.isfunction())
                callLua(m.owner, Addon.C_WIDGET, d);
        }
        m.replaceView = null;
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
