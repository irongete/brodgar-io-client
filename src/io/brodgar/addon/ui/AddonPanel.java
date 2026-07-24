package io.brodgar.addon.ui;

import haven.Button;
import haven.CheckBox;
import haven.Coord;
import haven.Label;
import haven.OptWnd;
import haven.Scrollport;
import haven.UI;
import haven.Widget;

import io.brodgar.addon.AddonManager;
import io.brodgar.addon.AddonManager.AddonInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * The in-game <b>AddOns</b> options panel (spec {@code 10-options-panel.md}, D-004 / D-006) — the
 * WoW-style addon manager, a clone of the voice {@code OptWnd.VoiceChatPanel} pattern. It lives in the
 * addon package (it needs no package-private {@code haven} access): it drives the all-static
 * {@link AddonManager} facade exactly as the voice panel drives {@code Voice}. Each row is one
 * discovered addon — an <b>enable/disable</b> checkbox (WoW "apply on reload": {@link
 * AddonManager#setEnabled}), name/version/author with the description as a tooltip, and a live status
 * (loaded / disabled / error / auto-disabled) — plus global <b>Reload UI</b>, <b>Enable all</b>, and
 * <b>Open addons folder</b> controls and a "changes pending" hint.
 *
 * <p>It extends {@code OptWnd.Panel} (a non-static inner class) from this package via the qualified
 * {@code opt.super()} / {@code opt.new PButton(...)} forms; every widget it uses ({@link Scrollport},
 * {@link CheckBox}, {@link Button}, {@link Label}) is a public {@code haven} type. It holds <b>no</b>
 * listener or subscription — it polls the facade in {@link #tick(double)} — so there is nothing to leak
 * when it closes. Rows are rebuilt when a reload changes the addon set (watched via
 * {@link AddonManager#reloadGen()}); the per-row status is refreshed cheaply each frame.
 */
public class AddonPanel extends OptWnd.Panel {
    private final Scrollport list;
    private final Label hint;
    private final List<Row> rows = new ArrayList<Row>();
    private int builtGen = Integer.MIN_VALUE;

    public AddonPanel(OptWnd opt, OptWnd.Panel back) {
        opt.super();
        Widget prev = add(new Label("AddOns"), 0, 0);
        prev = add(new Label("Enable or disable addons. Changes apply on reload."), prev.pos("bl").adds(0, 2));
        list = add(new Scrollport(UI.scale(new Coord(360, 220))), prev.pos("bl").adds(0, 8));
        hint = add(new Label(""), list.pos("bl").adds(0, 6));
        Button reload = add(new Button(UI.scale(120), "Reload UI", false).action(AddonManager::requestReload),
                            hint.pos("bl").adds(0, 8));
        add(new Button(UI.scale(110), "Enable all", false).action(this::enableAll), reload.pos("ur").adds(8, 0));
        add(new Button(UI.scale(170), "Open addons folder", false).action(AddonManager::openAddonsFolder),
            reload.pos("ur").adds(8, 0).add(UI.scale(118), 0));
        add(opt.new PButton(UI.scale(200), "Back", 27, back), reload.pos("bl").adds(0, 8));
        rebuild();
        pack();
    }

    /** (Re)build the row list from {@link AddonManager#describeAddons()} (reads manifests from disk). */
    private void rebuild() {
        for(Row r : rows)
            r.destroy();
        rows.clear();
        int y = 0;
        for(AddonInfo ai : AddonManager.describeAddons()) {
            Row r = list.cont.add(new Row(ai), new Coord(0, y));
            rows.add(r);
            y += r.sz.y + UI.scale(2);
        }
        if(rows.isEmpty())
            list.cont.add(new Label("No addons found."), new Coord(0, 0));
        builtGen = AddonManager.reloadGen();
    }

    /** Bulk-enable every discovered addon (applied on the next reload), then reflect the checkboxes. */
    private void enableAll() {
        for(AddonInfo ai : AddonManager.describeAddons())
            AddonManager.setEnabled(ai.id, true);
        rebuild();
    }

    public void tick(double dt) {
        super.tick(dt);
        if(AddonManager.reloadGen() != builtGen)   // a :reload / Reload UI rebuilt the addon layer
            rebuild();
        hint.settext(AddonManager.reloadNeeded() ? "Changes pending - Reload UI to apply." : "");
    }

    /** One addon row: an enable checkbox, the manifest metadata, and a live status label. */
    private final class Row extends Widget {
        final String id;
        private final Label status;

        Row(AddonInfo ai) {
            super(UI.scale(new Coord(360, 18)));
            final String rid = ai.id;
            add(new CheckBox("") {
                    { a = ai.enabled; }
                    public void set(boolean v) { AddonManager.setEnabled(rid, v); a = v; }
                }, UI.scale(new Coord(0, 1)));
            String meta = ai.name
                + ((ai.version != null) ? ("  v" + ai.version) : "")
                + ((ai.author != null) ? ("  " + ai.author) : "");
            Label nm = add(new Label(meta), UI.scale(new Coord(22, 3)));
            if(ai.description != null)
                nm.settip(ai.description, false);
            status = add(new Label(""), UI.scale(new Coord(200, 3)));
            this.id = rid;
            refresh();
        }

        private void refresh() {
            status.settext(AddonManager.liveStatus(id));
        }

        public void tick(double dt) {
            super.tick(dt);
            refresh();
        }
    }
}
