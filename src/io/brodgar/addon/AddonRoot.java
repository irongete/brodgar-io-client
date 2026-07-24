package io.brodgar.addon;

import haven.Coord;
import haven.Widget;

/**
 * The invisible, zero-size "addon-root" widget. It is attached to {@code ui.root} once per session
 * ({@link AddonManager#init}) and its per-frame {@link #tick(double)} drives the whole addon engine
 * (event dispatch, {@code OnUpdate}, timers).
 *
 * <p>This is the <b>zero-core-edit</b> tick pump described in {@code specs/addons/04-engine.md}:
 * {@link haven.UI#tick()} broadcasts a {@code TickEvent} to every widget on the UI thread, under
 * {@code synchronized(ui)}, right after {@code Glob.ctick()} has advanced game state. Invisible
 * widgets are still ticked (a {@code TickEvent} ignores visibility), so we get a reliable per-frame
 * callback without touching {@code haven}. It is never drawn (invisible children are skipped by
 * {@link haven.Widget#draw}); addon overlays will arrive in a later phase.
 */
public final class AddonRoot extends Widget {

    public AddonRoot() {
        super(Coord.z);        // zero size; never affects layout
        this.visible = false;  // never drawn, but still ticked
    }

    public void tick(double dt) {
        super.tick(dt);           // harmless: advances this widget's (empty) animation list
        AddonManager.tick(dt);    // engine step, on the UI thread; errors are isolated inside
    }
}
