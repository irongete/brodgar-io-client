package io.brodgar.addon;

import haven.Coord;
import haven.UI;
import haven.Widget;

/**
 * A <b>modal mouse-drag capture</b> (spec {@code 041-unified-events} §2.2, the "grab helper") — the Java half
 * of {@code hafen.ui():mouse():grab()}. It is the drag primitive the ghost gizmo (and any addon that needs a
 * press-drag-release loop) builds on: while a grab is active, mouse <b>move</b> and <b>up</b> are forwarded to
 * Lua and the map view neither pans nor clicks.
 *
 * <p><b>Its Lua surface moved to {@link LuaGrab} (041.5).</b> Before, this widget held two bare {@code LuaValue}
 * callbacks and called {@link AddonManager#callLua} directly; now it owns a {@link Subs} — "Move"/"Up" — the
 * same mechanism every other emitter in the API fires through, charged to {@link Addon#C_HOOK} (grab is what
 * {@code hooks} has left once input/action/message all moved elsewhere, spec {@code plan.md}
 * "Profiling attribution"). {@link LuaGrab} is the userdata handle Lua holds and subscribes on; this class
 * never talks to Lua directly.
 *
 * <p><b>Why a widget.</b> Two engine facts shape this (both verified in the event system): a
 * {@link haven.Widget.MouseMoveEvent} is <b>broadcast to every visible widget</b> ({@code MouseMoveEvent.propagation}
 * dispatches to all children unconditionally, no cursor-area test) — so a visible widget on {@code ui.root} receives
 * every move, even when the cursor is outside it; whereas a {@link haven.Widget.MouseUpEvent} only reaches the widget
 * under the cursor <i>unless</i> a grab captures it. So this widget is added to {@code ui.root} <b>visible</b> (to get
 * the broadcast moves) and calls {@link haven.UI#grabmouse} (to reliably get the terminating up wherever the cursor
 * is) — exactly the pattern a draggable {@code Window} uses. {@code grabmouse} also routes down/up/wheel to this
 * widget first, so during the drag those never reach the {@code MapView} — no camera pan, no stray click (the
 * "camera stays put" half of the V5 DoD), zero extra core edit.
 *
 * <p><b>Threading.</b> Input dispatch runs on the frame thread under {@code synchronized(ui)}
 * ({@code UILoop.Frame.tick}), so a fire goes straight through {@link Subs#fire}, which itself routes every handler
 * through {@link AddonManager#callLua} — watchdog-armed, error-isolated, CPU-accounted, and serialized against
 * every other addon Lua.
 *
 * <p><b>Lifecycle (P2).</b> The grab is bridge-owned ({@link Addon#mouseGrabs}); the addon gets an opaque
 * {@link LuaGrab} handle whose {@code :release()} calls {@link #release()}. {@link #release()} drops the
 * {@code UI.Grab} immediately (capture stops that instant) and marks the widget {@link #alive dead}; the widget
 * itself is unlinked on the next {@link #tick(double)} — a <b>deferred</b> removal so a {@code g:release()} called
 * from inside the {@code Move} handler never mutates the widget tree mid-broadcast (tick propagation snapshots
 * {@code next} first, so removing there is safe). The grab's own {@code Up} auto-releases. Teardown
 * ({@link LuaGrab#teardownGrabs}) on reload/disable releases any still-active grab.
 */
public final class LuaMouseGrab extends Widget {
    final Addon owner;
    /** This grab's own emitter — "Move"/"Up" — over the same mechanism every {@code X:on(key, fn)} uses. */
    final Subs subs;
    UI.Grab grab;            // the ui.grabmouse capture (down/up/wheel), removed on release
    boolean alive = true;    // false once released: callbacks no-op and the widget unlinks on the next tick

    LuaMouseGrab(Addon owner) {
        super(Coord.z);      // zero size; visible (the field default) so broadcast MouseMoveEvents reach it
        this.owner = owner;
        this.subs = new Subs(owner, Addon.C_HOOK);
    }

    /** Start capturing (after this widget is on {@code ui.root}). Grabs down/up/wheel so the terminating up lands here. */
    void arm(UI u) {
        grab = u.grabmouse(this);
    }

    /** Broadcast move → the "Move" key, gated on {@code hasSub} like every other emitter (spec §2.1). */
    public void mousemove(MouseMoveEvent ev) {
        if(!alive || !subs.has("Move"))
            return;
        subs.fire("Move", LuaEvent.grabMove(owner, ev.c.x, ev.c.y, mods()));
    }

    /** Grabbed up → the "Up" key, then auto-release. Consumes it (drag over). */
    public boolean mouseup(MouseUpEvent ev) {
        if(!alive)
            return true;
        if(subs.has("Up"))
            subs.fire("Up", LuaEvent.grabUp(owner, ev.c.x, ev.c.y, mods(), ev.b));
        release();
        return true;
    }

    /** Swallow any button press during the drag (no new interaction under the drag). */
    public boolean mousedown(MouseDownEvent ev) {
        return alive;
    }

    /** Swallow the wheel during the drag (reserved for a future rotate-by-wheel; not forwarded yet). */
    public boolean mousewheel(MouseWheelEvent ev) {
        return alive;
    }

    private int mods() {
        UI u = ui;
        return (u == null) ? 0 : u.modflags();
    }

    public void tick(double dt) {
        super.tick(dt);
        if(!alive && (parent != null))
            reqdestroy();    // deferred unlink — tick propagation snapshots `next`, so removing here is safe
    }

    /** Stop capturing now + mark dead (the widget unlinks on the next tick). Idempotent. */
    void release() {
        if(grab != null) {
            grab.remove();
            grab = null;
        }
        if(alive) {
            alive = false;
            owner.mouseGrabs.remove(this);
        }
    }
}
