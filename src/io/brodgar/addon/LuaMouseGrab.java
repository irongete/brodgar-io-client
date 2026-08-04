package io.brodgar.addon;

import haven.Coord;
import haven.UI;
import haven.Widget;

import org.luaj.vm2.LuaValue;

/**
 * A <b>modal mouse-drag capture</b> (spec {@code 16-virtual-entities.md} §2/§4, the "grab helper") — the Java
 * half of {@code hafen.hook():grab{move=fn, up=fn}}, V5. It is the drag primitive the ghost gizmo (and any addon
 * that needs a press-drag-release loop) builds on: while a grab is active, mouse <b>move</b> and <b>up</b> are
 * forwarded to Lua and the map view neither pans nor clicks.
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
 * ({@code UILoop.Frame.tick}), so the Lua callbacks go straight through {@link AddonManager#callLua} — like the
 * 2c input hook — watchdog-armed, error-isolated, CPU-accounted, and serialized against every other addon Lua.
 *
 * <p><b>Lifecycle (P2).</b> The grab is bridge-owned ({@link Addon#mouseGrabs}); the addon gets an opaque
 * {@code :release()} handle. {@link #release()} drops the {@code UI.Grab} immediately (capture stops that instant)
 * and marks the widget {@link #alive dead}; the widget itself is unlinked on the next {@link #tick(double)} — a
 * <b>deferred</b> removal so a {@code handle:release()} called from inside the {@code move} callback never mutates
 * the widget tree mid-broadcast (tick propagation snapshots {@code next} first, so removing there is safe). The
 * grab's own {@code up} auto-releases. Teardown on reload/disable releases any still-active grab.
 */
public final class LuaMouseGrab extends Widget {
    final Addon owner;
    final LuaValue onMove;   // fn(x, y, mods) — mods = {shift,ctrl,alt}; null if not supplied
    final LuaValue onUp;     // fn(x, y, button, mods) — fired once, then the grab auto-releases; null if not supplied
    UI.Grab grab;            // the ui.grabmouse capture (down/up/wheel), removed on release
    boolean alive = true;    // false once released: callbacks no-op and the widget unlinks on the next tick

    LuaMouseGrab(Addon owner, LuaValue onMove, LuaValue onUp) {
        super(Coord.z);      // zero size; visible (the field default) so broadcast MouseMoveEvents reach it
        this.owner = owner;
        this.onMove = onMove;
        this.onUp = onUp;
    }

    /** Start capturing (after this widget is on {@code ui.root}). Grabs down/up/wheel so the terminating up lands here. */
    void arm(UI u) {
        grab = u.grabmouse(this);
    }

    /** Broadcast move → Lua {@code onMove(x, y, mods)}. Coords are game-window pixels (this widget sits at root origin). */
    public void mousemove(MouseMoveEvent ev) {
        if(!alive || (onMove == null))
            return;
        AddonManager.callLua(owner, Addon.C_HOOK, onMove, LuaValue.valueOf(ev.c.x), LuaValue.valueOf(ev.c.y), AddonManager.modsTable(mods()));
    }

    /** Grabbed up → Lua {@code onUp(x, y, button, mods)}, then auto-release. Consumes it (drag over). */
    public boolean mouseup(MouseUpEvent ev) {
        if(!alive)
            return true;
        LuaValue up = onUp;
        if(up != null)
            AddonManager.callLua(owner, Addon.C_HOOK, up, LuaValue.valueOf(ev.c.x), LuaValue.valueOf(ev.c.y),
                                 LuaValue.valueOf(ev.b), AddonManager.modsTable(mods()));
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
