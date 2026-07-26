package io.brodgar.addon.ui;

import haven.Button;
import haven.CheckBox;
import haven.Coord;
import haven.Label;
import haven.OptWnd;
import haven.RichText;
import haven.Scrollport;
import haven.UI;
import haven.Widget;

import io.brodgar.addon.AddonRegistry;
import io.brodgar.addon.AddonRegistry.AddonInfo;

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
 * <p>Write-actions are a <b>per-addon</b> permission (D-027; D-028 — no global master switch): an addon
 * that declares {@code "actions"} shows the {@code [actions]} row marker, defaults to disabled, and
 * enabling it raises the {@link ActionsConsentWnd} consent dialog (via {@link #confirmEnableActions},
 * slice 4c) — so it only ever runs after the user knowingly grants it.
 *
 * <p>It extends {@code OptWnd.Panel} (a non-static inner class) from this package via the qualified
 * {@code opt.super()} / {@code opt.new PButton(...)} forms; every widget it uses ({@link Scrollport},
 * {@link CheckBox}, {@link Button}, {@link Label}) is a public {@code haven} type. It holds <b>no</b>
 * listener or subscription — it polls the facade in {@link #tick(double)} — so there is nothing to leak
 * when it closes. Rows are rebuilt when a reload changes the addon set (watched via
 * {@link AddonRegistry#reloadGen()}); the per-row status is refreshed cheaply each frame.
 */
public class AddonPanel extends OptWnd.Panel {
    private final Scrollport list;
    private final Label hint;
    private final List<Row> rows = new ArrayList<Row>();
    private int builtGen = Integer.MIN_VALUE;
    private ActionsConsentWnd consent;   // the live enable-time write-actions consent dialog (4c), or null/destroyed

    public AddonPanel(OptWnd opt, OptWnd.Panel back) {
        opt.super();
        Widget prev = add(new Label("AddOns"), 0, 0);
        prev = add(new Label("Enable or disable addons. Changes apply on reload."), prev.pos("bl").adds(0, 2));
        // D-027/D-028: write-actions are a PER-ADDON permission (no global switch). An addon that declares
        // "actions" carries the [actions] row marker, is disabled by default, and enabling it raises the consent
        // dialog (confirmEnableActions / ActionsConsentWnd, slice 4c) — this line just points the user at that.
        prev = add(new Label("An addon marked [actions] can act on your behalf; enabling one asks you to confirm."),
            prev.pos("bl").adds(0, 2));
        list = add(new Scrollport(UI.scale(new Coord(360, 220))), prev.pos("bl").adds(0, 8));
        hint = add(new Label(""), list.pos("bl").adds(0, 6));
        Button reload = add(new Button(UI.scale(120), "Reload UI", false).action(AddonRegistry::requestReload),
                            hint.pos("bl").adds(0, 8));
        add(new Button(UI.scale(110), "Enable all", false).action(this::enableAll), reload.pos("ur").adds(8, 0));
        add(new Button(UI.scale(170), "Open addons folder", false).action(AddonRegistry::openAddonsFolder),
            reload.pos("ur").adds(8, 0).add(UI.scale(118), 0));
        add(opt.new PButton(UI.scale(200), "Back", 27, back), reload.pos("bl").adds(0, 8));
        rebuild();
        pack();
    }

    /** (Re)build the row list from {@link AddonRegistry#describeAddons()} (reads manifests from disk). */
    private void rebuild() {
        for(Row r : rows)
            r.destroy();
        rows.clear();
        int y = 0;
        for(AddonInfo ai : AddonRegistry.describeAddons()) {
            Row r = list.cont.add(new Row(ai), new Coord(0, y));
            rows.add(r);
            y += r.sz.y + UI.scale(2);
        }
        if(rows.isEmpty())
            list.cont.add(new Label("No addons found."), new Coord(0, 0));
        builtGen = AddonRegistry.reloadGen();
    }

    /**
     * Bulk-enable every discovered addon (applied on the next reload), then reflect the checkboxes.
     * D-027 (4c): write-declaring addons are <b>skipped</b> — they stay opt-in per addon behind the
     * enable-time consent gate ({@link #confirmEnableActions}), so a bulk "Enable all" can never turn
     * one on without the user knowingly consenting to it.
     */
    private void enableAll() {
        for(AddonInfo ai : AddonRegistry.describeAddons())
            if(!ai.declaresActions)
                AddonRegistry.setEnabled(ai.id, true);
        rebuild();
    }

    /**
     * D-027 (4c): the enable-time consent gate for a write-declaring addon. Pops an
     * {@link ActionsConsentWnd} as a <b>top-level floating window</b> (a {@code ui.root} child, centered on
     * screen and raised to the front — so it drags freely like any window, not clipped inside this panel) and
     * enables the addon (persisted; applied on reload) + rebuilds the rows <b>only</b> if the user confirms.
     * One dialog at a time: re-ticking while a consent is already open is a no-op. Because it is top-level, it
     * is closed explicitly when this panel leaves the screen — see {@link #tick(double)}.
     */
    private void confirmEnableActions(String id, String name) {
        if((consent != null) && (consent.parent != null))
            return;
        consent = ui.root.adda(new ActionsConsentWnd(name, () -> { AddonRegistry.setEnabled(id, true); rebuild(); }),
                               ui.root.sz.div(2), 0.5, 0.5);
        consent.raise();
    }

    public void tick(double dt) {
        super.tick(dt);
        if(AddonRegistry.reloadGen() != builtGen)   // a :reload / Reload UI rebuilt the addon layer
            rebuild();
        hint.settext(AddonRegistry.reloadNeeded() ? "Changes pending - Reload UI to apply." : "");
        // The consent dialog (4c) is a top-level ui.root window, so close it explicitly once this panel
        // leaves the screen (switched away via Back, or Options hidden) — a floating dialog would otherwise
        // linger with no context. This panel keeps ticking while hidden (invisible widgets still tick), and
        // OptWnd is only hidden (never destroyed) on close, so this cleanup always runs.
        if((consent != null) && (consent.parent != null) && !tvisible())
            consent.destroy();
    }

    /** One addon row: an enable checkbox, the manifest metadata, and a live status label. */
    private final class Row extends Widget {
        final String id;
        private final Label status;

        Row(AddonInfo ai) {
            super(UI.scale(new Coord(360, 18)));
            final String rid = ai.id;
            final boolean writes = ai.declaresActions;   // D-027: enabling this addon needs consent (4c)
            final String aname = ai.name;
            add(new CheckBox("") {
                    { a = ai.enabled; }
                    public void set(boolean v) {
                        if(v && writes) {
                            // Enabling a write-declaring addon: ask for consent first, and leave the box
                            // unticked (a stays false) until the user confirms in the dialog — which then
                            // enables it and rebuilds the rows. Disabling (v=false) and read-only addons
                            // fall straight through with no prompt.
                            confirmEnableActions(rid, aname);
                        } else {
                            AddonRegistry.setEnabled(rid, v);
                            a = v;
                        }
                    }
                }, UI.scale(new Coord(0, 1)));
            String meta = ai.name
                + ((ai.version != null) ? ("  v" + ai.version) : "")
                + ((ai.author != null) ? ("  " + ai.author) : "")
                + (ai.declaresActions ? "  [actions]" : "")    // D-027: this addon can drive the character (gated)
                + (ai.declaresNetwork() ? "  [net]" : "");     // D-037: this addon can reach the declared hosts
            Label nm = add(new Label(meta), UI.scale(new Coord(22, 3)));
            // Tooltip: the description plus, for a network addon, exactly which hosts it may reach (§5.3) — the
            // user sees the servers it talks to BEFORE enabling it.
            StringBuilder tip = new StringBuilder();
            if(ai.description != null)
                tip.append(ai.description);
            if(ai.declaresNetwork()) {
                if(tip.length() > 0) tip.append("\n\n");
                tip.append("Network hosts: ").append(String.join(", ", ai.networkHosts));
            }
            if(tip.length() > 0)
                // WRAP the tooltip: rich mode caps the wrap width at UI.scale(300). settip(_, false) renders it on
                // ONE unwrapped line, so a long addon description becomes a texture wider than GL_MAX_TEXTURE_SIZE
                // and the GL upload fails (GL_INVALID_VALUE 1281 -> crashes the render thread on hover). quote()
                // escapes RichText's $ { } so the description stays literal (descriptions are full of { } [ ] tokens).
                nm.settip(RichText.Parser.quote(tip.toString()), true);
            status = add(new Label(""), UI.scale(new Coord(200, 3)));
            this.id = rid;
            refresh();
        }

        private void refresh() {
            status.settext(AddonRegistry.liveStatus(id));
        }

        public void tick(double dt) {
            super.tick(dt);
            refresh();
        }
    }
}
