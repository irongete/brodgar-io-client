package io.brodgar.addon;

import haven.EventHandler;
import haven.Widget;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaValue;

import java.util.HashMap;
import java.util.Map;

/**
 * One addon's <b>input subscriptions on one widget</b> — {@code widget:on("MouseDown"/"MouseUp"/"MouseMove"/
 * "Wheel", fn)}, the feature's one new reach (spec {@code 041-unified-events} §Input on any widget, 041.3): a
 * widget you built, one you found by selector, or one an event handed you all answer the same four keys
 * through the same door, over {@link Widget#listen}/{@link Widget#deafen} — which are plain {@link Widget}
 * methods, so the three magic tokens {@code hafen.hook():input} used to require were an API limit, never an
 * engine one.
 *
 * <p><b>The {@link Subs} plan.md describes, plus what {@code Subs} alone does not know</b>: the ONE engine
 * {@link EventHandler} this addon has installed per event class on this widget. Several {@code :on} calls on
 * the same key share it — {@code Subs} already fans one fire out to N Lua handlers — so at most four engine
 * listeners exist per (addon, widget) no matter how many Lua subscriptions ride them, and the last
 * {@code sub:off()} on a key ({@link Subs.Idle}) deafens it rather than leaving it firing into an empty list.
 *
 * <p><b>One per (addon, widget), never shared across addons</b> (D-100 one level down, spec §R2's table):
 * several addons watching the same native widget each get their own record, their own engine listener, and
 * their own teardown — exactly {@link Addon}'s per-widget map, keyed like every intern cache here.
 */
final class WidgetSubs {
    /** The four universal keys every widget answers (041.3) — §1.1's catalogue, D-125's closed set. */
    private static final String[] KEYS = { "MouseDown", "MouseUp", "MouseMove", "Wheel" };
    /** The listing a refusal names ({@code widget:on(key, fn): a Button has no event 'X' — it has: ...}). */
    static final String KEY_LIST = "MouseDown, MouseUp, MouseMove, Wheel";

    /** Is {@code key} one of the four universal widget keys? */
    static boolean isKey(String key) {
        for(String k : KEYS) {
            if(k.equals(key))
                return true;
        }
        return false;
    }

    /** {@code key} → its engine event class, or {@code null} for anything {@link #isKey} does not accept. */
    private static Class<? extends Widget.Event> eventClass(String key) {
        if(key.equals("MouseDown"))  return Widget.MouseDownEvent.class;
        if(key.equals("MouseUp"))    return Widget.MouseUpEvent.class;
        if(key.equals("MouseMove"))  return Widget.MouseMoveEvent.class;
        if(key.equals("Wheel"))      return Widget.MouseWheelEvent.class;
        return null;
    }

    private final Widget wdg;
    final Subs subs;
    /** {@code key → the ONE engine listener installed for it}, or absent when nobody currently subscribes. */
    private final Map<String, EventHandler<Widget.Event>> installed =
        new HashMap<String, EventHandler<Widget.Event>>();

    WidgetSubs(Addon owner, Widget wdg) {
        this.wdg = wdg;
        this.subs = new Subs(owner, Addon.C_WIDGET, new Subs.Idle() {
            public void idle(String key) {
                deafenKey(key);
            }
        });
    }

    /** {@code widget:on(key, fn)} — install the engine listener on first ask, then subscribe like any {@code Subs}. */
    LuaValue on(String key, LuaValue fn) {
        if(!installed.containsKey(key))
            install(key);
        return subs.on(key, fn);
    }

    @SuppressWarnings("unchecked")
    private void install(String key) {
        Class<? extends Widget.Event> cls = eventClass(key);
        if(cls == null)
            throw new LuaError("widget:on(key, fn): unknown event '" + key + "'");   // isKey already guards this
        EventHandler<Widget.Event> h = new EventHandler<Widget.Event>() {
            public boolean handle(Widget.Event ev) {
                return fire(key, ev);
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
    private boolean fire(String key, Widget.Event ev) {
        Subs.Cancel c = new Subs.Cancel();
        LuaValue evObj = LuaEvent.input(subs.owner, key, (Widget.PointerEvent)ev, c);
        return subs.fire(key, c, evObj);
    }

    /** {@link Subs.Idle}: the last {@code sub:off()} on {@code key} just ran — nobody is listening any more. */
    private void deafenKey(String key) {
        EventHandler<?> h = installed.remove(key);
        if(h != null)
            deafenQuietly(h);
    }

    /** Teardown (P2): drop every engine listener this addon installed on this widget, whatever is left live. */
    void teardown() {
        for(EventHandler<?> h : installed.values())
            deafenQuietly(h);
        installed.clear();
        subs.clear();
    }

    private void deafenQuietly(EventHandler<?> h) {
        try {
            wdg.deafen(h);
        } catch(RuntimeException e) {
            /* the widget is already gone: harmless — its own listener list went with it */
        }
    }
}
