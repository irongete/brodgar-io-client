package io.brodgar.ui;

import haven.CheckBox;
import haven.Label;
import haven.OptWnd;
import haven.UI;
import haven.Widget;

import io.brodgar.prof.Prof;

/**
 * The in-game <b>Client</b> options panel (spec 019-profiling, task 019.1) — a clone of the
 * {@code OptWnd.VoiceChatPanel} / {@code AddonPanel} pattern, and the future home for client-wide toggles.
 * 019 ships exactly one setting: <b>Enable profiling</b>, the master switch behind
 * {@link Prof#arm(boolean)}.
 *
 * <p>This is a <b>client</b> panel, not an addon panel, which is why it sits in {@code io.brodgar.ui} rather
 * than beside {@code addon.ui.AddonPanel} (the addon manager) — the setting it edits is the client's, and no
 * addon has to be loaded for it to mean anything. Like that panel it needs no package-private {@code haven}
 * access, and extends the non-static {@code OptWnd.Panel} through the qualified {@code opt.super()} /
 * {@code opt.new PButton(...)} forms.
 *
 * <p>The checkbox <b>re-reads {@link Prof#on} every frame</b> instead of caching it, so
 * {@code hafen.client:options():client():profiling(true)} — or a future toggle from anywhere else — visibly
 * moves an open panel's box with no listener to register and nothing to leak when the panel closes.
 */
public class ClientPanel extends OptWnd.Panel {
    public ClientPanel(OptWnd opt, OptWnd.Panel back) {
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
        add(opt.new PButton(UI.scale(200), "Back", 27, back), prev.pos("bl").adds(0, 30));
        pack();
    }
}
