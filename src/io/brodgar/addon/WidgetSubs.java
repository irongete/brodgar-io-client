package io.brodgar.addon;

import haven.EventHandler;
import haven.GItem;
import haven.UI;
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
 * <p><b>Three keys need a per-tick poll</b> ({@code ItemAdded}/{@code ItemRemoved}/{@code Destroy}, 041.4) — a
 * container's items are a {@link Widget} create/{@code cdestroy}, not a {@code uimsg}, and a widget's own death
 * has no engine event at all, so both can only be SEEN by polling ({@link #poll}), exactly the discipline the
 * buff/meter adapters and the old per-widget {@code Watch} used. Registered with {@link UiApi}'s flat poll list
 * on the first of the three, dropped on the last — the same {@code hasSub} gate every other emitter here has.
 *
 * <p><b>The other nine keys need nothing installed at all</b> — a control's {@code Pressed}/{@code Changed}/…
 * and a surface's {@code Draw}/{@code Tick}/{@code Drop}/{@code Close} fire from the Java method that already
 * runs (a click, a tick, a paint), straight into this record's {@link #subs} (via
 * {@link Addon#widgetSubsOrNull}, which costs one map lookup and mints nothing for a widget nobody listens to).
 *
 * <p><b>One per (addon, widget), never shared across addons</b> (D-100 one level down, spec §R2's table):
 * several addons watching the same native widget each get their own record, their own engine listeners and
 * poll registration, and their own teardown.
 */
final class WidgetSubs {
    /** The four keys with an engine listener behind them (041.3) — §1.1's input catalogue. */
    private static final String[] INPUT_KEYS = { "MouseDown", "MouseUp", "MouseMove", "Wheel" };
    /** The three keys that can only be seen by polling (041.4) — §1.1/§1.2's Destroy and the container pair. */
    private static final Set<String> POLL_KEYS = new java.util.HashSet<String>(
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
    /** Registered with {@link UiApi}'s poll list while any of {@link #POLL_KEYS} has a live subscriber. */
    private boolean polling;
    /** The server widget id at the first poll-key subscription ({@code -1} for a client-only widget). */
    private int pollId = -1;
    /** Present items, for the {@code ItemAdded}/{@code ItemRemoved} diff — empty until polling starts. */
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
                else if(POLL_KEYS.contains(key) && !anyPollKeyLive())
                    stopPoll();
            }
        });
    }

    /** {@code widget:on(key, fn)} — install/register on first ask for a key that needs it, then subscribe. */
    LuaValue on(String key, LuaValue fn) {
        if(isInputKey(key)) {
            if(!installed.containsKey(key))
                installInput(key);
        } else if(POLL_KEYS.contains(key) && !polling) {
            startPoll();
        }
        return subs.on(key, fn);
    }

    private boolean anyPollKeyLive() {
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

    // ---- the three poll keys (041.4): Destroy, on ANY widget; ItemAdded/ItemRemoved on a container ------

    private void startPoll() {
        UI u = AddonManager.ui;
        this.pollId = (u == null) ? -1 : u.widgetid(wdg);
        this.polling = true;
        UiApi.registerPoll(this);
    }

    private void stopPoll() {
        this.polling = false;
        items.clear();
        UiApi.unregisterPoll(this);
    }

    /**
     * Per-tick poll (UI thread, from {@link UiApi}'s flat list): the two-branch liveness test
     * ({@code learnings/hooks-hotkeys.md}'s rule — by id when server-bound, by tree reachability otherwise). A
     * dead widget fires {@code Destroy} once (nothing to say, uncancelable) and every remaining sub is dropped;
     * a live one diffs its items when anybody is listening for them.
     *
     * @return whether this record should stay in the poll list (false ⇒ the widget is gone; {@link UiApi} drops it).
     */
    boolean poll(UI u) {
        if(!live(u)) {
            subs.fire("Destroy");
            subs.clear();
            polling = false;
            return false;
        }
        if(subs.has("ItemAdded") || subs.has("ItemRemoved"))
            pollItems();
        return true;
    }

    private boolean live(UI u) {
        return (pollId >= 0) ? (u.getwidget(pollId) == wdg) : wdg.hasparent(u.root);
    }

    /**
     * Diff this widget's items against the cache, firing {@code ItemAdded}/{@code ItemRemoved} — the item
     * WIDGETS, so a worn item filling two equipment slots is one addition, and what fires is this owner's Item
     * object, the same one {@code :items()} hands back.
     */
    private void pollItems() {
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

    /** Teardown (P2): drop every engine listener and poll registration this addon installed on this widget. */
    void teardown() {
        for(EventHandler<?> h : installed.values())
            deafenQuietly(h);
        installed.clear();
        if(polling)
            stopPoll();
        subs.clear();
    }
}
