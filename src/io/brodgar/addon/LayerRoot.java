package io.brodgar.addon;

import haven.Coord;
import haven.Widget;

/**
 * The invisible, zero-size root of the <b>addon layer</b> (074.1) — the tick pump of the tree an addon's own
 * windows live in. It is attached once, to the layer's {@code ui.root}, when {@code haven.UILoop} builds that
 * tree, and it lives as long as the client does.
 *
 * <p>The layer is a {@code UI} of its own with no {@code Session} behind it, drawn above whichever session
 * holds the screen and above the login screen when none does. So this pump is <b>not</b> {@link AddonRoot}:
 * that one is a session's, drives that session's engine step and is asked which login it is stepping. This
 * one belongs to no login, and what it drives is what the layer's own tree needs each frame — the arming
 * tick of the surfaces built into it.
 *
 * <p>Same zero-core-edit shape as {@link AddonRoot} and for the same reason: {@link haven.UI#tick()}
 * broadcasts a {@code TickEvent} to every widget under {@code synchronized(ui)}, invisible ones included,
 * so a widget in the tree is a per-frame callback with the tree's own monitor already held.
 */
public final class LayerRoot extends Widget {

    public LayerRoot() {
        super(Coord.z);        // zero size; never affects layout
        this.visible = false;  // never drawn, but still ticked
    }

    public void tick(double dt) {
        super.tick(dt);
        AddonManager.layerTick(ui);   // the tree's own step, on the UI thread; errors are isolated inside
    }
}
