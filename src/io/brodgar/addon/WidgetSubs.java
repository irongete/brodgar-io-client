package io.brodgar.addon;

import haven.EventHandler;
import haven.GItem;
import haven.Inventory;
import haven.UI;
import haven.WItem;
import haven.Widget;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaValue;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * One addon's <b>subscriptions on one widget</b> — the single address for everything a widget can say
 * (spec {@code 041-unified-events}, 041.3/041.4): a widget you built, one you found by selector, or one an
 * event handed you, all answer {@code widget:on(key, fn)} through this one record, over ONE {@link Subs}.
 *
 * <p><b>Four keys need an engine listener</b> ({@code MouseDown}/{@code MouseUp}/{@code MouseMove}/{@code Wheel},
 * 041.3) — {@link Widget#listen}/{@link Widget#deafen}, one {@link EventHandler} per event class, installed on
 * first subscription and released when the last {@link Subs.Idle} on that key goes. They are installed on the
 * {@linkplain #inwdg input target}, which is the widget the addon PAINTS rather than the one it holds
 * (063.1).
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
        java.util.Arrays.asList("ItemAdded", "ItemRemoved", "Removed"));
    /** The one key the ENGINE STEP fires (112.1) — see {@link Addon#updateSurfaces}. */
    private static final String UPDATE_KEY = "Update";

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

    /**
     * <b>The tree this record is about</b> (073.2) — the {@code UI} of the very widget it watches, which is
     * what indexes it in {@link UiApi}'s per-session watch list. Never {@code AddonManager.screen()}: this
     * record is offered every placement and every removal in its own tree, and a subscription on a widget of
     * a background session must not be walked by the drawn session's seams.
     */
    UI ui() {
        return wdg.ui;
    }
    /**
     * <b>The widget the four input listeners are installed on</b> (063.1) — the addon's own content leaf where
     * there is one ({@link LuaWidget#ownedContent}'s {@link Owned#widget()}), and {@link #wdg} itself
     * everywhere else.
     *
     * <p>It exists because a window an addon builds is TWO widgets: the Lua entity is interned on the chrome
     * ({@link UiApi#attach}), while the surface it paints is the {@link AddonWidget} content one level down,
     * and the two are {@code Window.contarea().ul} apart. A listener on the chrome therefore reported a press
     * in outer pixels while {@code Draw} paints in content-local ones, and every press on the caption, the
     * frame and the close button arrived as an addon's to handle — so a drag of the title bar was a click on
     * whatever the addon had drawn under it. Resolving to the content instead makes {@code Draw} and the four
     * input keys speak ONE coordinate system, and leaves the chrome to the client, whose {@code DragDeco}
     * moves the window.
     *
     * <p>Resolved ONCE, at construction, and final: {@code deafen} must reach the very widget {@code listen}
     * did even if the tree moved underneath in between. Everything but the owned-window case resolves to
     * {@code wdg} — a borrowed widget owns no content of this addon's, a control IS its own content, and a
     * bare {@code hafen.ui():widget()}'s root is the content — so nothing else changes address.
     */
    private final Widget inwdg;
    final Subs subs;
    /** {@code key → the ONE engine listener installed for it}, or absent when nobody currently subscribes. */
    private final Map<String, EventHandler<Widget.Event>> installed =
        new HashMap<String, EventHandler<Widget.Event>>();
    /** Registered with {@link UiApi}'s watch list while any of {@link #TREE_KEYS} has a live subscriber. */
    private boolean listening;
    /** The server widget id at the first tree-key subscription ({@code -1} for a client-only widget) — used only
     *  by {@link #live}, at {@link #startListening}'s one-off check for a widget already gone by then. */
    private int boundId = -1;
    /** Present items, for the {@code ItemAdded}/{@code ItemRemoved} diff — empty until listening starts. DEEP
     *  (064.3): every item this widget holds at any depth, not just {@code widget:items()}'s one-per-cell set. */
    // retained: instance state of one WidgetSubs, which Addon.dropWidgetSubs removes and tears down whole on
    //   the disposal drain -- there is no entry here that can outlive the widget this record is about.
    private final Map<GItem, LuaValue> items = new IdentityHashMap<GItem, LuaValue>();
    /** Each item currently in {@link #items}' own immediate container, {@code null} for a top-level one —
     *  recorded while the item is still LIVE (064.3). {@link LuaItem#container} cannot answer this once an
     *  item has left: {@code Widget.remove()} nulls its parent chain before the removal seam ever fires, so
     *  by the time {@link #refreshItems} sees an item missing, re-deriving its container from the item itself
     *  would always read {@code null} — this is the one place that fact is still on record. */
    // retained: instance state of one WidgetSubs, which Addon.dropWidgetSubs removes and tears down whole on
    //   the disposal drain -- there is no entry here that can outlive the widget this record is about.
    private final Map<GItem, GItem> containerOf = new IdentityHashMap<GItem, GItem>();
    /** A placement/removal touched something of ours since the last {@link #flush} — 064.3, see
     *  {@link #markDirty} for why the diff itself waits for the tick boundary rather than running inline. */
    private boolean dirty;

    WidgetSubs(Addon owner, Widget wdg) {
        this.owner = owner;
        this.wdg = wdg;
        Owned content = LuaWidget.ownedContent(owner, wdg);
        this.inwdg = (content != null) ? content.widget() : wdg;
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
                else if(UPDATE_KEY.equals(key))
                    owner.unwatchUpdate(WidgetSubs.this);   // 112.1: nobody left to step this surface
            }
        });
    }

    /**
     * <b>The surface this record is about</b> (112.1), or {@code null} for a widget that is not one of this
     * addon's own — what the engine step fires {@code Update} on, and where its {@code dead}/{@code pending}
     * guards are read. It is {@link #inwdg} because that is already the resolved content leaf: for a window
     * {@link #wdg} is the chrome and the {@link AddonWidget} is one level down, and for a bare surface the two
     * coincide.
     */
    AddonWidget surface() {
        return (inwdg instanceof AddonWidget) ? (AddonWidget)inwdg : null;
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
        } else if(UPDATE_KEY.equals(key)) {
            owner.watchUpdate(this);   // 112.1: the engine step fires this one, not the widget's own tick
        }
        return h;
    }

    private boolean anyTreeKeyLive() {
        return subs.has("ItemAdded") || subs.has("ItemRemoved") || subs.has("Removed");
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
        inwdg.listen((Class<Widget.Event>)(Class<?>)cls, h);   // the content leaf, not the chrome (063.1)
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
            inwdg.deafen(h);   // the widget listen() reached, resolved once (063.1)
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
        UI u = ui();     // 078.4: the widget's own tree — its id counts there, and so does its liveness
        this.boundId = (u == null) ? -1 : u.widgetid(wdg);
        if((u != null) && !live(u)) {
            subs.fire("Removed");
            endEverySub();
            return;
        }
        this.listening = true;
        UiApi.registerInterest(this);
        if(subs.has("ItemAdded") || subs.has("ItemRemoved"))
            refreshItems();
    }

    /**
     * End every subscription this record holds, one at a time, through the very path {@code sub:off()} takes
     * (uw-22) — the dead-widget arm of {@link #startListening}, which used to drop the whole record with
     * {@code subs.clear()}.
     *
     * <p><b>Why per subscription and not wholesale.</b> A record holds more than the tree key that found the
     * widget dead: the four input keys have an engine listener installed on {@link #inwdg}, and {@code Update}
     * has this surface on the owner's step list. Those are registrations held OUTSIDE this record, and the
     * only thing that releases them is the {@link Subs.Idle} hook — which is what {@link Subs#off} runs, key
     * by key, as each key's last handler goes. So each sub is marked dead and ended exactly as the addon's own
     * {@code sub:off()} would have ended it, and the record is left the way a fully unsubscribed one is.
     */
    private void endEverySub() {
        for(LuaSub s : subs.live()) {
            s.alive = false;      // the guard sub:off() sets, for a fire already walking its snapshot
            subs.off(s);          // ...and the path that runs the ended/idle hooks this record's releases hang on
        }
    }

    private void stopListening() {
        this.listening = false;
        items.clear();
        containerOf.clear();
        dirty = false;
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
     * MY subtree, at any depth? If so, re-derive the item set.
     *
     * <p><b>{@code hasparent} alone answers that only for a DIRECT arrival</b> (064.3) — dropped straight into
     * this container. Something arriving inside a stack or a creel HELD in this container arrives off a {@code
     * GItem.ContentsWindow} hung under {@code GameUI}, never under {@link #wdg}, so {@code w.hasparent(wdg)}
     * would miss it. {@link #belongsTo} climbs {@code item:container()}'s own {@code ContentsWindow} → {@code
     * cont} walk out to the OUTERMOST item that moved — the one drawn as a real child of a container widget —
     * and tests THAT one against the tree the ordinary way; {@code hasparent} is safe on it because placement
     * fires while the child's parent chain is intact (the mirror of removal below, where it no longer is).
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
     *
     * <p><b>Marks {@link #dirty} rather than diffing right here</b> (064.3) — see {@link #markDirty} for why:
     * a stack arriving already full sends one placement per widget id the server frees for it, not one for the
     * whole arrival, and diffing after each would report every one of them individually instead of once for
     * the outermost.
     */
    void offerPlaced(Widget w) {
        if(!((w instanceof WItem) || (w instanceof GItem)))
            return;
        if((subs.has("ItemAdded") || subs.has("ItemRemoved")) && belongsTo(w))
            markDirty();
    }

    /**
     * Does {@code w} — a {@code WItem} or {@code GItem} the placement seam just saw — belong to {@link #wdg},
     * directly or through a chain of stacks/creels it holds (064.3)? Climbs from the item {@code w} draws out
     * to the outermost item that moved via {@link LuaItem#container}, then tests that one the ordinary way:
     * everything BELOW the outermost item lives off a {@code ContentsWindow} hung under {@code GameUI}, never
     * under the container it is conceptually inside.
     */
    private boolean belongsTo(Widget w) {
        GItem it = (w instanceof WItem) ? ((WItem)w).item : (GItem)w;
        if(it == null)
            return w.hasparent(wdg);
        GItem outer = it, c;
        while((c = LuaItem.container(outer)) != null)
            outer = c;
        return outer.hasparent(wdg);
    }

    /**
     * The widget-removal seam's offer (042.7, M1): either {@code w} IS the widget being watched — fire
     * {@code Destroy} once (nothing to say, uncancelable) and drop every remaining sub, since a dead widget has
     * nothing left to report — or it may be an item widget ({@code WItem} or {@code GItem}, see {@link
     * #offerPlaced}) that just left MY subtree. Its parent link is already gone by now (M1 fires after
     * {@code unlink()}), so membership is read from the cache re-derive below rather than a {@code hasparent}
     * check. Marks a touch rather than diffing right here — see {@link #markDirty}.
     */
    void offerRemoved(Widget w) {
        if(w == wdg) {
            subs.fire("Removed");
            subs.clear();
            owner.unwatchUpdate(this);   // 112.1: clear() fires no Idle, and a dead surface steps no more
            listening = false;
            items.clear();
            containerOf.clear();
            dirty = false;
            UiApi.unregisterInterest(this);
            return;
        }
        if(((w instanceof WItem) || (w instanceof GItem)) && (subs.has("ItemAdded") || subs.has("ItemRemoved")))
            markDirty();
    }

    /**
     * A placement or removal may concern us — record the touch and diff at the tick boundary ({@link
     * #flush}), rather than inline right here (064.3).
     *
     * <p><b>Removals need no more than that.</b> {@code Widget.remove()} unlinks synchronously, before the
     * removal seam even fires, and {@code drainRemovedWidgets} drains a whole tick's worth of them in one
     * loop — so by the time ANY widget from this tick's batch reaches {@link #offerRemoved}, every OTHER
     * widget that tick also touched is already gone from the live tree too, regardless of dispatch order.
     * {@link LuaItem#deepItems}, read at the tick boundary, therefore already sees a stack and everything it
     * lost together — the outermost rule falls out of the filter over that one diff (it still needs {@link
     * #containerOf}, not {@link LuaItem#container}, since a removed item's own parent chain is already gone
     * by then — see {@link #refreshItems}).
     *
     * <p><b>Additions are the harder half.</b> The server frees or fills a stack's whole subtree as SEPARATE
     * per-widget messages — the stack's own, and one for every item inside it — and unlike removals, a
     * placement dispatches immediately as its own message is processed, with no equivalent queue collecting a
     * whole tick's worth first. In practice the server sends a stack and its contents as one network burst,
     * fully processed before the next tick, so the tick boundary still catches them together — but there is
     * no engine guarantee of that the way there is for removals.
     */
    private void markDirty() {
        dirty = true;
    }

    /** Once per tick ({@link UiApi#flushItemWatchers}, 064.3): diff now if a placement or removal touched us
     *  since the last flush — see {@link #markDirty}. An idle container (the ordinary case) costs one read of
     *  the flag. */
    void flush() {
        if(dirty) {
            dirty = false;
            refreshItems();
        }
    }

    /**
     * Diff this widget's items against the cache, firing {@code ItemAdded}/{@code ItemRemoved} — the item
     * WIDGETS, so a worn item filling two equipment slots is one addition, and what fires is this owner's Item
     * object, the same one {@code :items()} hands back. Driven by {@link #offerPlaced}/{@link #offerRemoved}
     * (042.7) rather than a per-tick poll.
     *
     * <p><b>The set diffed is DEEP</b> ({@link LuaItem#deepItems}, 064.3): everything this widget holds at any
     * depth, so a dandelion moving inside a stack that stayed put is seen even though {@code widget:items()}
     * never lists it. <b>Only the OUTERMOST thing that moved is fired</b> — {@link #isOutermost} keeps an
     * added or removed item only when its container did NOT also change in this same pass, which is what
     * makes a stack arriving or leaving with three things inside it one event, for the stack, rather than
     * four. A thing that moves inside a container that itself stayed put has no such container in the batch,
     * so it fires on its own, exactly as it always did.
     *
     * <p><b>An added item's container is read FRESH</b> — {@code present}'s own value, since {@link
     * LuaItem#deepItems} walked the live tree to build it. <b>A removed item's is read from {@link
     * #containerOf}</b> instead, the record kept while it was still present: by the time it is missing here,
     * its own {@code :container()} would answer {@code null} regardless of what it actually sat inside, since
     * {@code Widget.remove()} nulls the parent chain before the removal seam ever runs.
     */
    private void refreshItems() {
        Map<GItem, GItem> present = LuaItem.deepItems(wdg);
        Map<GItem, LuaValue> added = new LinkedHashMap<GItem, LuaValue>();
        for(Map.Entry<GItem, GItem> e : present.entrySet()) {
            GItem g = e.getKey();
            if(!items.containsKey(g)) {
                LuaValue item = LuaItem.of(owner, g);
                items.put(g, item);
                added.put(g, item);
            }
            containerOf.put(g, e.getValue());   // keep it current for everything still present, not just new
        }
        Map<GItem, LuaValue> removed = new LinkedHashMap<GItem, LuaValue>();
        Map<GItem, GItem> removedContainer = new LinkedHashMap<GItem, GItem>();
        for(Iterator<Map.Entry<GItem, LuaValue>> it = items.entrySet().iterator(); it.hasNext();) {
            Map.Entry<GItem, LuaValue> e = it.next();
            GItem g = e.getKey();
            if(!present.containsKey(g)) {
                removed.put(g, e.getValue());
                removedContainer.put(g, containerOf.remove(g));
                it.remove();
            }
        }
        for(Map.Entry<GItem, LuaValue> e : added.entrySet()) {
            if(isOutermost(present.get(e.getKey()), added.keySet()))
                subs.fire("ItemAdded", e.getValue());
        }
        for(Map.Entry<GItem, LuaValue> e : removed.entrySet()) {
            if(isOutermost(removedContainer.get(e.getKey()), removed.keySet()))
                subs.fire("ItemRemoved", e.getValue());
        }
    }

    /** Is {@code container} — the thing an added or removed item sat inside, already resolved by the caller
     *  the right way for which of the two this is — absent, or not itself part of {@code batch} (the same
     *  added/removed pass)? Absent or foreign either way means the item itself is the outermost thing that
     *  moved (064.3). */
    private static boolean isOutermost(GItem container, Set<GItem> batch) {
        return (container == null) || !batch.contains(container);
    }

    // ---- teardown -------------------------------------------------------------------------------------

    /** Teardown (P2): drop every engine listener and watch-list registration this addon installed on this widget. */
    void teardown() {
        for(EventHandler<?> h : installed.values())
            deafenQuietly(h);
        installed.clear();
        if(listening)
            stopListening();
        owner.unwatchUpdate(this);   // 112.1: as above — a teardown is a clear, and a clear fires no Idle
        subs.clear();
    }
}
