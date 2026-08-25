package io.brodgar.addon;

import haven.Coord;
import haven.Widget;

/**
 * The invisible, zero-size "addon-root" widget, attached to {@code ui.root} once per session
 * ({@link AddonManager#sessionArrived}). What is left of it is the <b>global-hotkey seam</b>: a hotkey is
 * matched by walking the widget tree, so answering one needs a widget in that tree, and this is it.
 *
 * <p><b>It no longer drives that session's step</b> (112.4). {@link haven.UI#tick()} broadcasts its
 * {@code TickEvent} under {@code synchronized(ui)} — {@code UILoop.Frame.tick}'s block for the session on
 * screen, {@code Sessions.tick}'s for every background member — so a step reached through this widget began
 * with that tree's monitor already held, and every drain below it with it. Writing another tree from an
 * {@code s:ui():on(sel, "Removed")} handler took a second monitor under the first, which is the one nesting
 * {@code docs/client/multi-session.md}'s lock direction forbids. So the step left the tree the way the
 * layer's did in 112.1: both drivers call {@link AddonManager#tick(haven.UI)} directly, <b>after</b> their
 * own {@code synchronized} block closes, and what it drains may reach any tree because it holds none.
 *
 * <p>It is never drawn (invisible children are skipped by {@link haven.Widget#draw}) and it is still
 * ticked by the broadcast — {@link haven.Widget#tick} advances its empty animation list and nothing else.
 * {@link AddonManager.SessionState#addonRoot} being set is also what says that session's init finished, so
 * the widget stays attached for its whole life.
 */
public final class AddonRoot extends Widget {

    public AddonRoot() {
        super(Coord.z);        // zero size; never affects layout
        this.visible = false;  // never drawn, but still ticked
    }

    /**
     * Global-hotkey seam ({@code keybindings:register}, Phase 2e-2). {@link haven.UI#keydown} fires a
     * {@link haven.Widget.GlobKeyEvent} — only after an unconsumed focused {@code KeyDownEvent}, so hotkeys never
     * fire while a text field has focus — and that event walks the widget tree calling {@code globtype} on every
     * widget. This invisible root is an early child of {@code ui.root}, hence walked <b>last</b>, so a client
     * binding on the same key is matched first and an addon hotkey is the fallback. Returning {@code true}
     * consumes the key (stops the walk). Zero core edit: this reuses the engine's own {@code globtype} seam.
     *
     * <p><b>It runs under this tree's monitor</b> (112.4) — input dispatch holds it — so it is family A of
     * {@code specs/112-one-tree-monitor-at-a-time/plan.md}: a hotkey handler may reach the tree it fired in
     * and meets 112.2's refusal if it reaches another. The step is where a handler is free of that, and
     * {@code hafen.client():stepping()} is what tells the two apart.
     */
    public boolean globtype(GlobKeyEvent ev) {
        if(AddonManager.onGlobKey(ev))
            return true;
        return super.globtype(ev);
    }
}
