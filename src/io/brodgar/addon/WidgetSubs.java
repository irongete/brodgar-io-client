package io.brodgar.addon;

import haven.EventHandler;
import haven.GItem;
import haven.UI;
import haven.WItem;
import haven.Widget;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaValue;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * One addon's <b>subscriptions on one widget</b> — the single address for everything a widget can say
 * (spec {@code 041-unified-events}, 041.3/041.4): a widget you built, one you found by selector, or one an
 * event handed you, all answer {@code widget:on(key, fn)} through this one record, over ONE {@link Subs}.
 *
 * <p><b>Four keys need an engine listener</b> ({@code MouseDown}/{@code MouseUp}/{@code MouseMove}/{@code Wheel},
 * 041.3) — {@link Widget#listen}/{@link Widget#deafen}, one {@link EventHandler} per event class, installed on
 * first subscription and released when the last {@link Subs.Idle} on that key goes.
 *
 * <p><b>Three keys ride the placement/removal seams</b> ({@code ItemAdded}/{@code ItemRemoved}/{@code Destroy},
 * 041.4, event-driven since 042.7) — a container's items are a {@link WItem} create/{@code cdestroy}, not a
 * {@code uimsg}, and a widget's own death has no engine event of its own, so both are SEEN at the moment the
 * client's own placement ({@link #offerPlaced}) and removal ({@link #offerRemoved}, M1) seams fire, the same
 * moments the buff/meter/equipment adapters read. Registered with {@link UiApi}'s flat watch list on the first
 * of the three, dropped on the last — the same {@code hasSub} gate every other emitter here has.
 *
 * <p><b>The rest need nothing installed at all</b> — a control's {@code Pressed}/{@code Changed}/…, a
 * surface's {@code Draw}/{@code Tick}/{@code Drop}/{@code Close} and the two gesture keys
 * {@code Dragged}/{@code Resized} fire from the Java method that already runs (a click, a tick, a paint, a
 * release), straight into this record's {@link #subs} (via
 * {@link Addon#widgetSubsOrNull}, which costs one map lookup and mints nothing for a widget nobody listens to).
 *
 * <p><b>One per (addon, widget), never shared across addons</b> (D-100 one level down, spec §R2's table):
 * several addons watching the same native widget each get their own record, their own engine listeners and
 * tree-key watch-list registration, and their own teardown.
 */
final class WidgetSubs {
    /** The four keys with an engine listener behind them (041.3) — §1.1's input catalogue. */
    private static final String[] INPUT_KEYS = { "MouseDown", "MouseUp", "MouseMove", "Wheel" };
    /** The three keys seen at the placement/removal seams (041.4) — §1.1/§1.2's Destroy and the container pair. */
    private static final Set<String> TREE_KEYS = new java.util.HashSet<String>(
        java.util.Arrays.asList("ItemAdded", "ItemRemoved", "Destroy"));

    /** Is {@code key} one of the four input keys? */
    private static boolean isInputKey(String key) {
        for(String k : INPUT_KEYS) {
            if(k.equals(key))
                return true;
        }
        return false;
    }

    /** {@code key} → its engine event class, or {@code null} for anything {@link #isInputKey} does not accept. */
    private static Class<? extends Widget.Event> eventClass(String key) {
        if(key.equals("MouseDown"))  return Widget.MouseDownEvent.class;
        if(key.equals("MouseUp"))    return Widget.MouseUpEvent.class;
        if(key.equals("MouseMove"))  return Widget.MouseMoveEvent.class;
        if(key.equals("Wheel"))      return Widget.MouseWheelEvent.class;
        return null;
    }

    private final Addon owner;
    private final Widget wdg;
    final Subs subs;
    /** {@code key → the ONE engine listener installed for it}, or absent when nobody currently subscribes. */
    private final Map<String, EventHandler<Widget.Event>> installed =
        new HashMap<String, EventHandler<Widget.Event>>();
    /** Registered with {@link UiApi}'s watch list while any of {@link #TREE_KEYS} has a live subscriber. */
    private boolean listening;
    /** The server widget id at the first tree-key subscription ({@code -1} for a client-only widget) — used only
     *  by {@link #live}, at {@link #startListening}'s one-off check for a widget already gone by then. */
    private int boundId = -1;
    /** Present items, for the {@code ItemAdded}/{@code ItemRemoved} diff — empty until listening starts. */
    private final Map<GItem, LuaValue> items = new IdentityHashMap<GItem, LuaValue>();

    WidgetSubs(Addon owner, Widget wdg) {
        this.owner = owner;
        this.wdg = wdg;
        this.subs = new Subs(owner, new Subs.Cats() {
            public int cat(String key) {   // 041.4: Draw/Cell paint, everything else here is widgets (plan.md)
                return (key.equals("Draw") || key.equals("Cell")) ? Addon.C_DRAW : Addon.C_WIDGET;
            }
        }, new Subs.Idle() {
            public void idle(String key) {
                if(isInputKey(key))
                    deafenKey(key);
                else if(TREE_KEYS.contains(key) && !anyTreeKeyLive())
                    stopListening();
            }
        });
    }

    /** {@code widget:on(key, fn)} — register the handler first, then install/start whatever the key needs; a
     *  tree key whose widget is already gone (see {@link #startListening}) fires {@code Destroy} on the handler
     *  just registered, so the order here matters. */
    LuaValue on(String key, LuaValue fn) {
        boolean hadItemInterest = subs.has("ItemAdded") || subs.has("ItemRemoved");
        LuaValue h = subs.on(key, fn);
        if(isInputKey(key)) {
            if(!installed.containsKey(key))
                installInput(key);
        } else if(TREE_KEYS.contains(key)) {
            if(!listening)
                startListening();   // seeds current items itself when this first ask is an item key
            else if(!hadItemInterest && (key.equals("ItemAdded") || key.equals("ItemRemoved")))
                refreshItems();     // already listening (e.g. via an earlier Destroy sub) — first item-key ask
        }
        return h;
    }

    private boolean anyTreeKeyLive() {
        return subs.has("ItemAdded") || subs.has("ItemRemoved") || subs.has("Destroy");
    }

    // ---- the four input keys (041.3) ----------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private void installInput(String key) {
        Class<? extends Widget.Event> cls = eventClass(key);
        if(cls == null)
            throw new LuaError("widget:on(key, fn): unknown event '" + key + "'");   // the caller already guards this
        EventHandler<Widget.Event> h = new EventHandler<Widget.Event>() {
            public boolean handle(Widget.Event ev) {
                return fireInput(key, ev);
            }
        };
        wdg.listen((Class<Widget.Event>)(Class<?>)cls, h);
        installed.put(key, h);
    }

    /**
     * Run the engine's own pre-hook: build the {@code ev}, fire every Lua handler on {@code key} (in
     * registration order, all of them, even after one cancels — {@link Subs#fire}'s OR rule), and answer
     * whether to short-circuit {@link Widget#handle} the way {@code ev:preventDefault()} asked to.
     */
    private boolean fireInput(String key, Widget.Event ev) {
        Subs.Cancel c = new Subs.Cancel();
        LuaValue evObj = LuaEvent.input(owner, key, (Widget.PointerEvent)ev, c);
        return subs.fire(key, c, evObj);
    }

    // ---- the borrowed capability keys (061.1) -------------------------------------------------------

    /**
     * Fire a capability key on a widget this addon <b>borrowed</b> (061.1): the client's own activation site
     * asked ({@link Controls#activate}), and every handler this owner holds on {@code key} runs with an
     * {@code ev} that can stop the client's own action ({@code ev:preventDefault()}) or run it itself
     * ({@code ev:resend()}).
     *
     * <p>Nothing is installed for these, exactly as for the owned half: the Java method that already runs is
     * the emitter, and this record is only where the handlers live. What differs is the {@link Subs.Cancel} —
     * it is the caller's, shared with every OTHER addon holding the same key on the same widget, because they
     * are all deciding one thing.
     *
     * <p>{@code actor} is the widget whose method {@code ev:resend()} runs, which is {@link #wdg} itself for
     * every family but the lists, where a dropdown's popup and a menu's inner list carry the click for a
     * control one level up (061.3). {@code moved} says the client has ALREADY written the value it is
     * reporting (a slider, a scrollbar — 061.4), which is what makes both of those verbs raise on this
     * {@code ev} rather than pretend.
     */
    void fireBorrowed(String key, Widget actor, Object value, Subs.Cancel c, boolean moved) {
        subs.fire(key, c, LuaEvent.control(owner, key, wdg, actor, value, c, moved));
    }

    /** {@link Subs.Idle}: the last {@code sub:off()} on {@code key} just ran — nobody is listening any more. */
    private void deafenKey(String key) {
        EventHandler<?> h = installed.remove(key);
        if(h != null)
            deafenQuietly(h);
    }

    private void deafenQuietly(EventHandler<?> h) {
        try {
            wdg.deafen(h);
        } catch(RuntimeException e) {
            /* the widget is already gone: harmless — its own listener list went with it */
        }
    }

    // ---- the three tree keys (041.4, event-driven since 042.7): Destroy, on ANY widget; ItemAdded/ItemRemoved
    // ---- on a container, both seen at the placement/removal seams instead of diffed every tick -------------

    /**
     * First tree-key ask on this widget: join {@link UiApi}'s flat watch list — unless the widget is ALREADY
     * gone, in which case there is nothing to watch for and {@code Destroy} is fired right here, mirroring what
     * the old per-tick poll would have found on its very next look. Reuses {@link #live}'s two-branch liveness
     * test rather than re-deriving it, so a subscription minted after the fact behaves exactly like one minted
     * before it. A live container is seeded with its CURRENT items right away — {@code ItemAdded} for what is
     * already inside, exactly what the old poll's first tick did (docs/addons/api/ui/items.md), not just for
     * what enters afterward.
     */
    private void startListening() {
        UI u = AddonManager.ui;
        this.boundId = (u == null) ? -1 : u.widgetid(wdg);
        if((u != null) && !live(u)) {
            subs.fire("Destroy");
            subs.clear();
            return;
        }
        this.listening = true;
        UiApi.registerInterest(this);
        if(subs.has("ItemAdded") || subs.has("ItemRemoved"))
            refreshItems();
    }

    private void stopListening() {
        this.listening = false;
        items.clear();
        UiApi.unregisterInterest(this);
    }

    /**
     * The two-branch liveness test ({@code learnings/hooks-hotkeys.md}'s rule — by id when server-bound, by tree
     * reachability otherwise), used once at {@link #startListening} for a widget that may already be gone by the
     * time an addon asks. Every LIVE widget's death is instead seen at {@link #offerRemoved} (M1), so nothing
     * here re-checks liveness on a schedule.
     */
    private boolean live(UI u) {
        return (boundId >= 0) ? (u.getwidget(boundId) == wdg) : wdg.hasparent(u.root);
    }

    /**
     * The widget-placement seam's offer (042.7): {@code w} just entered the tree — is it an item widget inside
     * MY subtree? If so, re-derive the item set. {@code hasparent} is safe here because placement fires while the
     * child's parent chain is intact (the mirror of removal below, where it no longer is).
     *
     * <p><b>Both {@code GItem} AND {@code WItem} are checked</b> — not a redundancy. {@code onWidgetPlaced} only
     * ever fires for the widget the SERVER placed ({@code UI.AddWidget.run}'s {@code child}), which for a
     * container like {@code Inventory} is the {@code GItem}; the {@code WItem} wrapper is a second widget the
     * container's own {@code addchild} mints and {@code add()}s LOCALLY (a client-side add, which per this
     * feature's own hazard list never reaches the placement seam — {@code Widget.add} does not route through
     * {@code addchild}). An {@code Equipory}, by contrast, has no such wrapper: the {@code GItem} IS its child, so
     * only the {@code GItem} branch ever fires there. Missing the {@code GItem} case left a real gap: an item
     * arriving from outside the container (picked up off the ground) never re-derived, while one arriving from a
     * sibling WItem shuffling elsewhere in the SAME container happened to (that reflow's own WItem create/destroy
     * still triggered a refresh) — an inconsistency indistinguishable from a race until traced to this.
     */
    void offerPlaced(Widget w) {
        if(!((w instanceof WItem) || (w instanceof GItem)))
            return;
        if((subs.has("ItemAdded") || subs.has("ItemRemoved")) && w.hasparent(wdg))
            refreshItems();
    }

    /**
     * The widget-removal seam's offer (042.7, M1): either {@code w} IS the widget being watched — fire
     * {@code Destroy} once (nothing to say, uncancelable) and drop every remaining sub, since a dead widget has
     * nothing left to report — or it may be an item widget ({@code WItem} or {@code GItem}, see {@link
     * #offerPlaced}) that just left MY subtree. Its parent link is already gone by now (M1 fires after
     * {@code unlink()}), so membership is read from the cache re-derive below rather than a {@code hasparent}
     * check.
     */
    void offerRemoved(Widget w) {
        if(w == wdg) {
            subs.fire("Destroy");
            subs.clear();
            listening = false;
            items.clear();
            UiApi.unregisterInterest(this);
            return;
        }
        if(((w instanceof WItem) || (w instanceof GItem)) && (subs.has("ItemAdded") || subs.has("ItemRemoved")))
            refreshItems();
    }

    /**
     * Diff this widget's items against the cache, firing {@code ItemAdded}/{@code ItemRemoved} — the item
     * WIDGETS, so a worn item filling two equipment slots is one addition, and what fires is this owner's Item
     * object, the same one {@code :items()} hands back. Driven by {@link #offerPlaced}/{@link #offerRemoved} now
     * (042.7) rather than a per-tick poll; the diff itself is unchanged.
     */
    private void refreshItems() {
        Set<GItem> present = new LinkedHashSet<GItem>(LuaItem.items(wdg));
        for(GItem g : present) {
            if(!items.containsKey(g)) {
                LuaValue item = LuaItem.of(owner, g);
                items.put(g, item);
                subs.fire("ItemAdded", item);
            }
        }
        for(Iterator<Map.Entry<GItem, LuaValue>> it = items.entrySet().iterator(); it.hasNext();) {
            Map.Entry<GItem, LuaValue> e = it.next();
            if(!present.contains(e.getKey())) {
                LuaValue item = e.getValue();
                it.remove();
                subs.fire("ItemRemoved", item);
            }
        }
    }

    // ---- teardown -------------------------------------------------------------------------------------

    /** Teardown (P2): drop every engine listener and watch-list registration this addon installed on this widget. */
    void teardown() {
        for(EventHandler<?> h : installed.values())
            deafenQuietly(h);
        installed.clear();
        if(listening)
            stopListening();
        subs.clear();
    }
}
