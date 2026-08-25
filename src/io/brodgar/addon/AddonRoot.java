package io.brodgar.addon;

import haven.Coord;
import haven.Widget;

/**
 * The invisible, zero-size "addon-root" widget. It is attached to {@code ui.root} once per session
 * ({@link AddonManager#sessionArrived}) and its per-frame {@link #tick(double)} drives that session's own
 * step of the addon engine: its gobs, its HUD adapters, its widgets, its world and its store.
 *
 * <p><b>Not the whole engine</b> (074.2). {@code Update}, the timers, the Lua budget and the engine clock are
 * the <i>client's</i> and run on {@link AddonManager#layerTick}, once a frame, because an addon has one of
 * each however many sessions it is watching. That step is <b>not</b> a widget (112.1): the frame calls it
 * outside both trees' monitors, so what it drives may reach any tree.
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
        super.tick(dt);               // harmless: advances this widget's (empty) animation list
        AddonManager.tick(ui, dt);    // session step, on the UI thread; errors are isolated inside. 073.1: the
                                      //   pump says which session it is stepping — this widget is on that
                                      //   session's own root, so the tree it hangs in IS the answer
    }

    /**
     * Global-hotkey seam ({@code keybindings:register}, Phase 2e-2). {@link haven.UI#keydown} fires a
     * {@link haven.Widget.GlobKeyEvent} — only after an unconsumed focused {@code KeyDownEvent}, so hotkeys never
     * fire while a text field has focus — and that event walks the widget tree calling {@code globtype} on every
     * widget. This invisible root is an early child of {@code ui.root}, hence walked <b>last</b>, so a client
     * binding on the same key is matched first and an addon hotkey is the fallback. Returning {@code true}
     * consumes the key (stops the walk). Zero core edit: this reuses the engine's own {@code globtype} seam.
     */
    public boolean globtype(GlobKeyEvent ev) {
        if(AddonManager.onGlobKey(ev))
            return true;
        return super.globtype(ev);
    }
}
