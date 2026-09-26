package io.brodgar.ui;

import haven.CheckBox;
import haven.Label;
import haven.OptWnd;
import haven.Widget;

import io.brodgar.prof.Prof;

/**
 * The in-game <b>Client</b> options panel (spec 019-profiling, task 019.1) — a clone of the
 * {@code AddonPanel} pattern, and the home for client-wide toggles: the
 * profiling switch behind {@link Prof#arm(boolean)}. The view distance is {@link PerformancePanel}'s.
 *
 * <p>This is a <b>client</b> panel, not an addon panel, which is why it sits in {@code io.brodgar.ui} rather
 * than beside {@code addon.ui.AddonPanel} (the addon manager) — the settings it edits are the client's, and no
 * addon has to be loaded for them to mean anything. Like that panel it needs no package-private {@code haven}
 * access, and extends the non-static {@code OptWnd.Panel} through the qualified {@code opt.super()} /
 * {@code opt.new PButton(...)} forms.
 *
 * <p>Every control here <b>re-reads its own switch every frame</b> instead of caching it, so
 * {@code hafen.client():options():client():profiling(true)} — or a write from anywhere else — visibly moves an
 * open panel's box with no listener to register and nothing to leak when the panel closes.
 */
public class ClientPanel extends OptWnd.Panel {
    public ClientPanel(OptWnd opt) {
        opt.super();
        Widget prev = add(new Label("Client"), 0, 0);
        prev = add(new CheckBox("Enable profiling") {
                {a = Prof.armed();}
                public void set(boolean val) {Prof.arm(val); a = val;}
                public void tick(double dt) {
                    super.tick(dt);
                    a = Prof.armed();   // follow a write from Lua (or anywhere else) while the panel is open
                }
            }, prev.pos("bl").adds(0, 10));
        prev.settip("Arms the client's profiler: frame, CPU, GPU, addon and widget timings, readable from an"
                    + " addon through hafen.client:profiling(). Same switch as the :profile console command."
                    + " Off costs nothing; leave it off unless you are measuring something.", true);

        pack();
    }
}
