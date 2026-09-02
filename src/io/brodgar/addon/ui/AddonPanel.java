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
import io.brodgar.addon.PermissionSet;
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
 * <p>The protected verbs are a <b>per-addon</b> permission (D-027; D-028 — no global master switch): an
 * addon that declares any of them shows the {@code [protected: N]} row marker with its declared entries in the
 * row tooltip — the shape {@code [net]} already uses for its hosts, so how much an addon asked for is legible
 * in the list and exactly what it asked for is one hover away — defaults to disabled, and enabling it raises
 * the {@link PermissionConsentWnd} consent dialog (via {@link #confirmEnablePermissions}, slice 4c) — so it
 * only ever runs after the user knowingly grants it, for exactly the keys it asked for.
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
    private PermissionConsentWnd consent;   // the live enable-time permission consent dialog (4c), or null/destroyed

    public AddonPanel(OptWnd opt, OptWnd.Panel back) {
        // addon: (115.1) "AddOns" is this panel's own caption on the window, written by OptWnd.chpanel when it
        // is swapped in. The game menu that opens it carries none.
        opt.super("AddOns");
        Widget prev = add(new Label("AddOns"), 0, 0);
        prev = add(new Label("Enable or disable addons. Changes apply on reload."), prev.pos("bl").adds(0, 2));
        // D-027/D-028: the protected verbs are a PER-ADDON permission (no global switch). An addon that declares
        // any carries the [protected: N] row marker, is disabled by default, and enabling it raises the consent
        // dialog (confirmEnablePermissions / PermissionConsentWnd, slice 4c) — this line just points the user at
        // that, and says what the number and the hover are for.
        prev = add(new Label("An addon marked [protected: N] asked for N permissions to act on your behalf"
            + " (hover to read them); enabling one asks you to approve the list."),
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
     * D-027 (4c): permission-declaring addons are <b>skipped</b> — they stay opt-in per addon behind the
     * enable-time consent gate ({@link #confirmEnablePermissions}), so a bulk "Enable all" can never turn
     * one on without the user knowingly consenting to it. The skip widens with the predicate: it is now
     * "declared ANY key", so a bulk enable cannot grant one either.
     */
    private void enableAll() {
        for(AddonInfo ai : AddonRegistry.describeAddons())
            if(!ai.declaresPermissions())
                AddonRegistry.setEnabled(ai.id, true);
        rebuild();
    }

    /**
     * D-027 (4c): the enable-time consent gate for a permission-declaring addon. Pops an
     * {@link PermissionConsentWnd} as a <b>top-level floating window</b> (a {@code ui.root} child, centered on
     * screen and raised to the front — so it drags freely like any window, not clipped inside this panel) and
     * <b>records what was consented to</b> + enables the addon (persisted; applied on reload) + rebuilds the
     * rows <b>only</b> if the user confirms. The record is the door: consent is granted for the keys this
     * manifest declared <b>and for the {@code hosts} this dialog showed beside them</b>, so one that later asks
     * for more is disabled again and asks again — and the dialog is handed that record as well as the
     * declaration, so the re-prompt can mark what is NEW in it (050.2) rather than repeating a list the user
     * has already read once. The same {@code hosts} go on the screen and into the record, from the one variable,
     * because a record of hosts the user was not shown is not a record of anything they agreed to.
     * One dialog at a time: re-ticking while a consent is already open is a no-op. Because it is top-level, it
     * is closed explicitly when this panel leaves the screen — see {@link #tick(double)}.
     */
    private void confirmEnablePermissions(String id, String name, PermissionSet declared, List<String> hosts) {
        if((consent != null) && (consent.parent != null))
            return;
        consent = ui.root.adda(new PermissionConsentWnd(name, declared, AddonRegistry.consentedKeys(id), hosts,
                                                       () -> { AddonRegistry.grantConsent(id, declared, hosts); rebuild(); }),
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
        private final String manifestError;   // why this row has no manifest at all, or null

        Row(AddonInfo ai) {
            super(UI.scale(new Coord(360, 18)));
            final String rid = ai.id;
            final boolean writes = ai.declaresPermissions();   // D-027: enabling this addon needs consent (4c)
            final PermissionSet declared = ai.permissions;
            final String aname = ai.name;
            // A manifest that does not parse leaves NOTHING to enable: the addon cannot load whatever the
            // persisted bit says, so the box is shown unticked and does not answer — a ticked box beside a row
            // that will never load is the panel claiming a state the client cannot reach.
            final boolean broken = (ai.manifestError != null);
            this.manifestError = ai.manifestError;
            add(new CheckBox("") {
                    { a = ai.enabled && !broken; }
                    public void set(boolean v) {
                        if(broken) {
                            return;
                        } else if(v && writes) {
                            // Enabling a permission-declaring addon: ask for consent first, and leave the box
                            // unticked (a stays false) until the user confirms in the dialog — which then
                            // records the grant, enables it and rebuilds the rows. Disabling (v=false) and
                            // read-only addons fall straight through with no prompt.
                            confirmEnablePermissions(rid, aname, declared, ai.networkHosts);
                        } else {
                            AddonRegistry.setEnabled(rid, v);
                            a = v;
                        }
                    }
                }, UI.scale(new Coord(0, 1)));
            String meta = ai.name
                + ((ai.version != null) ? ("  v" + ai.version) : "")
                + ((ai.author != null) ? ("  " + ai.author) : "")
                // D-027 (050.2): this addon asked for N protected entries — the COUNT, because "it can act on
                // your behalf" is the one thing the marker used to say about an addon wanting to change the
                // movement speed and one wanting to send any message the client can. A group is one entry, the
                // same one line the consent dialog renders for it; the entries themselves are in the tooltip.
                + (ai.declaresPermissions() ? ("  [protected: " + ai.permissions.size() + "]") : "")
                + (ai.declaresNetwork() ? "  [net]" : "");     // D-037: this addon can reach the declared hosts
            Label nm = add(new Label(meta), UI.scale(new Coord(22, 3)));
            // Tooltip: the description plus, for a declaring addon, exactly which permissions it asked for and
            // which hosts it may reach (§5.3) — the user sees what it wants to do and the servers it talks to
            // BEFORE enabling it, where the row itself only has room for how many. For a broken manifest it is
            // the REASON, first and whole (an unknown permission key names the valid ones), because a row that
            // says only "manifest error" sends the author to the terminal for something the panel already knows.
            StringBuilder tip = new StringBuilder();
            if(broken)
                tip.append(ai.manifestError);
            if(ai.description != null)
                tip.append(ai.description);
            if(ai.declaresPermissions()) {
                if(tip.length() > 0) tip.append("\n\n");
                tip.append("Permissions: ").append(ai.permissions.toString());
            }
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
            // liveStatus is the cheap per-frame read and does no manifest I/O, so it can only report
            // "not loaded" for an addon whose manifest is the thing that failed. The row read it once at
            // build time and keeps it: the reason is in the tooltip, the label just says which kind of row
            // this is.
            status.settext((manifestError != null) ? "manifest error (hover)" : AddonRegistry.liveStatus(id));
        }

        public void tick(double dt) {
            super.tick(dt);
            refresh();
        }
    }
}
