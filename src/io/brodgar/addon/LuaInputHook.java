package io.brodgar.addon;

import haven.Coord;
import haven.EventHandler;
import haven.Widget;

import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.ZeroArgFunction;

/**
 * An <b>input / gesture hook</b> (Level 1, spec {@code 13-hooks-and-interception.md} §L1) — the Java half
 * of {@code hafen.hook.input(target, event, fn)}, Phase 2c. It is a {@link haven.EventHandler} registered on
 * a target {@link Widget} through the engine's built-in {@link Widget#listen}/{@link Widget#deafen} seam, so
 * it needs <b>zero core edit</b>: {@link Widget#handle} runs listeners <b>before</b> the widget's own handler,
 * and a listener returning {@code true} <b>short-circuits</b> the default ({@code Event.dispatch} returns
 * immediately). A Lua handler calling {@code ev:preventDefault()} therefore <em>is</em> the pre-hook's
 * preventDefault.
 *
 * <p><b>Return-true also blocks child dispatch</b> ({@code Event.dispatch} stops before propagating to
 * descendants), so at this seam {@code preventDefault} on a container is also a {@code stopPropagation} to its
 * children — hence 2c exposes only {@code preventDefault} (one canonical consume), not a separate
 * {@code stopPropagation}. {@code ev:default()} / {@code ev:resend()} belong to the outbound-action choke
 * point (Level 2, Phase 2d); at L1 you simply <i>don't</i> preventDefault and the default runs as usual.
 *
 * <p>Input dispatch is on the <b>UI thread</b>, so the Lua callback goes straight through
 * {@link AddonManager#callLua} — watchdog-armed (D-018), error-isolated (a Lua error is logged, never thrown
 * into dispatch), CPU-accounted — exactly like an event handler. The addon never sees this object: it gets an
 * opaque {@code :remove()} handle, and the bridge owns the hook for teardown ({@link Widget#deafen} on
 * reload/disable, principle P2). The {@link #alive} flag makes a dispatch that races teardown a no-op.
 */
public final class LuaInputHook implements EventHandler<Widget.Event> {
    final Addon owner;
    final Widget target;      // the widget listened on; kept so teardown can deafen() it
    final String event;       // the event name ("mousedown"/…), for diagnostics
    final LuaValue fn;        // the Lua handler fn(ev)
    boolean alive = true;

    LuaInputHook(Addon owner, Widget target, String event, LuaValue fn) {
        this.owner = owner;
        this.target = target;
        this.event = event;
        this.fn = fn;
    }

    /**
     * Called by the engine (from {@link Widget#handle}) before the target's own handler. Builds the Lua
     * {@code ev} for the concrete event type, invokes the addon's handler, and returns whether it consumed
     * the event ({@code ev:preventDefault()}) — {@code true} suppresses the widget's default (and child
     * dispatch). Coordinates are the target widget's local pixel space.
     */
    public boolean handle(Widget.Event ev) {
        if(!alive)
            return false;
        LuaTable t = new LuaTable();
        if(ev instanceof Widget.PointerEvent) {              // all mouse events carry a coord
            Coord c = ((Widget.PointerEvent)ev).c;
            t.set("x", LuaValue.valueOf(c.x));
            t.set("y", LuaValue.valueOf(c.y));
        }
        if(ev instanceof Widget.MouseButtonEvent)            // mousedown / mouseup: which button
            t.set("button", LuaValue.valueOf(((Widget.MouseButtonEvent)ev).b));
        else if(ev instanceof Widget.MouseWheelEvent)        // mousewheel: scroll amount (+down / -up)
            t.set("amount", LuaValue.valueOf(((Widget.MouseWheelEvent)ev).a));
        final boolean[] consume = new boolean[1];
        t.set("preventDefault", new ZeroArgFunction() {
            public LuaValue call() {
                consume[0] = true;
                return LuaValue.NIL;
            }
        });
        AddonManager.callLua(owner, Addon.C_HOOK, fn, t);
        return consume[0];
    }
}
