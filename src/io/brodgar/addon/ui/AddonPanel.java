package io.brodgar.addon.ui;

import haven.Button;
import haven.CheckBox;
import haven.Coord;
import haven.Label;
import haven.OptWnd;
import haven.RichText;
import haven.Scrollport;
import haven.Tabs;
import haven.TextEntry;
import haven.UI;
import haven.Widget;

import io.brodgar.addon.AddonRegistry;
import io.brodgar.addon.ApiVersion;
import io.brodgar.addon.PermissionSet;
import io.brodgar.addon.AddonRegistry.AddonInfo;
import io.brodgar.addon.registry.Entry;
import io.brodgar.addon.registry.Registry;

import java.util.ArrayList;
import java.util.List;

/**
 * The in-game <b>AddOns</b> options panel (spec {@code 10-options-panel.md}, D-004 / D-006) — the
 * WoW-style addon manager, an {@code OptWnd.Panel} like the client's own, in <b>two tabs</b> since 145.1.
 * It lives in the addon package (it needs no package-private {@code haven} access): it drives the all-static
 * {@link AddonRegistry} facade and the hub client {@link Registry}.
 *
 * <p><b>Installed</b> is the manager as it was: each row is one discovered addon — an <b>enable/disable</b>
 * checkbox (WoW "apply on reload": {@link AddonRegistry#setEnabled}), name/version/author with the description
 * as a tooltip, and a live status (loaded / disabled / error / outdated / auto-disabled) — plus a "changes
 * pending" hint, the <b>Load out of date AddOns</b> checkbox (WoW's, {@link AddonRegistry#setLoadOutdated}: one
 * persisted stance that lets every {@code outdated (…)} row load on the next reload, no permission and no
 * per-row grant), and the global <b>Reload UI</b>, <b>Enable all</b> and <b>Open addons folder</b> controls.
 *
 * <p><b>Browse</b> searches the hub ({@link Registry#search}): a field whose text is searched once it has been
 * still for a third of a second, or at once on Enter — polled from {@link #tick}, because a write the API
 * makes ({@code w:value(s)} is {@code rsettext}) is silent and typing, paste and a driven write all deserve the
 * same door — over a list of {@link BrowseRow}s and the tab's own line ({@code searching}, {@code no addon
 * matches}, or why the hub did not answer). Every answer is read from {@code tick} and only the latest
 * request is ever read, so a late answer to an earlier search is dropped. A row is the hub's item — name,
 * version, owner, the same markers and tooltip an Installed row composes — and a status: {@code in addons/ by
 * hand} where the folder exists (the client never replaces a player's own folder), the out-of-date label where
 * its {@code api_version} is not this client's, else empty. The button column at {@link #BUTTON_X} is empty
 * on this tab for now.
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
 * {@link CheckBox}, {@link Button}, {@link Label}, {@link TextEntry}, {@link Tabs}) is a public {@code haven}
 * type. It holds <b>no</b> listener or subscription — it polls the facade and the hub client in
 * {@link #tick(double)} — so there is nothing to leak when it closes. Rows are rebuilt when a reload changes
 * the addon set (watched via {@link AddonRegistry#reloadGen()}); the per-row status is refreshed cheaply each
 * frame.
 */
public class AddonPanel extends OptWnd.Panel {
    /** The list width, both tabs: a row's metadata to {@link #STATUS_X}, its status from there, a button column at {@link #BUTTON_X}. */
    static final int LIST_W = 560, STATUS_X = 290, BUTTON_X = 480;
    /** How long the search field's text has to be still before it is searched, in seconds. */
    static final double SEARCH_STILL = 1.0 / 3;

    private final Tabs tabs;
    private final Tabs.Tab installed, browse;
    // -- Installed
    private final Scrollport list;
    private final Label hint;
    private final List<Row> rows = new ArrayList<Row>();
    private int builtGen = Integer.MIN_VALUE;
    private PermissionConsentWnd consent;   // the live enable-time permission consent dialog (4c), or null/destroyed
    // -- Browse
    private final TextEntry field;
    private final Scrollport results;
    private final Label line;               // the tab's own line: searching / no addon matches / why not
    private final List<BrowseRow> found = new ArrayList<BrowseRow>();
    private String lastText = "";           // the field's text as tick last saw it
    private double still = 0;               // how long it has been that, in seconds
    private boolean searchDue = false;      // the text moved and no search has gone out for it yet
    private Registry.Request<List<Entry>> pending;   // the latest request, the only one ever read

    public AddonPanel(OptWnd opt, OptWnd.Panel back) {
        // addon: (115.1) "AddOns" is this panel's own caption on the window, written by OptWnd.chpanel when it
        // is swapped in. The game menu that opens it carries none.
        opt.super("AddOns");
        Widget prev = add(new Label("AddOns"), 0, 0);
        // 145.1: two tabs, exactly as OptWnd.SettingsPanel lays its own out. Tabs is built with a placeholder c
        // because a tab's button needs its Tab and a Tab is placed where Tabs was told, so the bodies are moved
        // under the buttons once both exist. The buttons are plain Buttons whose action is the swap rather
        // than Tabs.TabButtons: a widget's class is its selector name, and `@Button[text=Browse]` is what a
        // reader of the tree expects a button to answer to.
        tabs = new Tabs(Coord.z, Coord.z, this);
        installed = tabs.add();
        browse = tabs.add();
        Button ib = add(new Button(UI.scale(120), "Installed", false).action(() -> tabs.showtab(installed)),
                        prev.pos("bl").adds(0, 6));
        add(new Button(UI.scale(120), "Browse", false).action(() -> tabs.showtab(browse)), ib.pos("ur").adds(5, 0));
        Coord tc = ib.pos("bl").adds(0, 8);
        tabs.c = tc;
        installed.move(tc);
        browse.move(tc);

        // ---- Installed: today's widgets, inside a tab
        prev = installed.add(new Label("Enable or disable addons. Changes apply on reload."), Coord.z);
        // D-027/D-028: the protected verbs are a PER-ADDON permission (no global switch). An addon that declares
        // any carries the [protected: N] row marker, is disabled by default, and enabling it raises the consent
        // dialog (confirmEnablePermissions / PermissionConsentWnd, slice 4c) — this line just points the user at
        // that, and says what the number and the hover are for.
        prev = installed.add(new Label("An addon marked [protected: N] asked for N permissions to act on your behalf"
            + " (hover to read them); enabling one asks you to approve the list."),
            prev.pos("bl").adds(0, 2));
        // 141.1: the list is as wide as its longest row needs -- a row's metadata label runs to STATUS_X and its
        // status label from there, so `outdated (no api_version, client 1.0)`, the longest status a row carries,
        // and a long name with `[protected: N]  [net]` after it both fit whole beside the scrollbar. 145.1: and
        // a button column stands at BUTTON_X beside the status, which is what widened it from 480 to LIST_W.
        // The list is clipped at its edge, not wrapped, so a row wider than this reads as a status cut mid-word.
        list = installed.add(new Scrollport(UI.scale(new Coord(LIST_W, 220))), prev.pos("bl").adds(0, 8));
        hint = installed.add(new Label(""), list.pos("bl").adds(0, 6));
        // 141.2: LOAD OUT OF DATE ADDONS -- the WoW checkbox of the same name, one stance over the whole list
        // rather than a per-row grant. It sits between the hint and the buttons because it is applied exactly
        // as a row's box is: ticking it flags "changes pending" and Reload UI is what lets the out-of-date
        // addons in. Seeded from the pref and written straight back through the facade, the Row box's own
        // pattern; `a = v` is owed because overriding set(boolean) replaces the default that wrote it.
        CheckBox outdated = installed.add(new CheckBox("Load out of date AddOns") {
                { a = AddonRegistry.loadOutdated(); }
                public void set(boolean v) {
                    AddonRegistry.setLoadOutdated(v);
                    a = v;
                }
            }, hint.pos("bl").adds(0, 6));
        Button reload = installed.add(new Button(UI.scale(120), "Reload UI", false).action(AddonRegistry::requestReload),
                                      outdated.pos("bl").adds(0, 8));
        installed.add(new Button(UI.scale(110), "Enable all", false).action(this::enableAll), reload.pos("ur").adds(8, 0));
        installed.add(new Button(UI.scale(170), "Open addons folder", false).action(AddonRegistry::openAddonsFolder),
                      reload.pos("ur").adds(8, 0).add(UI.scale(118), 0));

        // ---- Browse: the field, the results, the line
        prev = browse.add(new Label("Search the addons published at brodgar.io/addons: a name, an author, a tag."),
                          Coord.z);
        // The field is an anonymous subclass so that its class name stays TextEntry for a selector, and so
        // that Enter -- which the client delivers to activate(String) through done()/gkeytype -- searches at
        // once. Everything else about it is read by tick: text() polled, searched when still.
        field = browse.add(new TextEntry(UI.scale(LIST_W), "") {
                public void activate(String text) {
                    lastText = text;
                    search(text);
                }
            }, prev.pos("bl").adds(0, 4));
        results = browse.add(new Scrollport(UI.scale(new Coord(LIST_W, 220))), field.pos("bl").adds(0, 8));
        line = browse.add(new Label(""), results.pos("bl").adds(0, 6));

        // Tabs.pack gives both tabs the union box, so Browse is laid out to Installed's height and Back sits
        // below both at the same place whichever is showing.
        tabs.pack();
        add(opt.new PButton(UI.scale(200), "Back", 27, back), installed.pos("bl").adds(0, 8));
        rebuild();
        pack();
    }

    /**
     * (Re)build the Installed rows from {@link AddonRegistry#describeAddons()} (reads manifests from disk), and
     * re-read every Browse row's folder status: a reload is the moment {@code addons/} may have changed.
     */
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
        for(BrowseRow r : found)
            r.refresh();
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
     * for more is disabled again and asks again — and the dialog is handed <b>both halves</b> of that record
     * as well as the declaration, so the re-prompt can mark what is NEW in it (050.2), a host as readily as a
     * key, rather than repeating a list the user has already read once. The same {@code hosts} go on the screen
     * and into the record, from the one variable, because a record of hosts the user was not shown is not a
     * record of anything they agreed to.
     * One dialog at a time: re-ticking while a consent is already open is a no-op. Because it is top-level, it
     * is closed explicitly when this panel leaves the screen — see {@link #tick(double)}.
     */
    private void confirmEnablePermissions(String id, String name, PermissionSet declared, List<String> hosts) {
        if((consent != null) && (consent.parent != null))
            return;
        consent = ui.root.adda(new PermissionConsentWnd(name, declared, AddonRegistry.consentedKeys(id), hosts,
                                                       AddonRegistry.consentedHosts(id),
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
        pollSearch(dt);
    }

    // ------------------------------------------------------------- Browse

    /**
     * The search field, read every frame: a text that moved is searched once it has been still for
     * {@link #SEARCH_STILL}, whether the user typed it, pasted it, or an addon wrote it — the write is
     * {@code rsettext} and fires no {@code changed}, so a hook there would miss it and polling misses nothing.
     * Then the request in flight, if it has ended: its answer becomes the rows, or its error the line. Only
     * {@link #pending} is ever read, so a request a later search replaced is dropped with its answer.
     */
    private void pollSearch(double dt) {
        String t = field.text();
        if(!t.equals(lastText)) {
            lastText = t;
            still = 0;
            searchDue = true;
        } else if(searchDue) {
            still += dt;
            if(still >= SEARCH_STILL)
                search(t);
        }
        if((pending != null) && pending.done()) {
            Registry.Request<List<Entry>> r = pending;
            pending = null;
            if(r.error() != null)
                line.settext(r.error());
            else
                show(r.result());
        }
    }

    /**
     * Search the hub for {@code text} now; an empty text is no search at all — the rows go and the line clears.
     * A request still in flight is cancelled first: its answer is nobody's now, and a hub that is slow or down
     * would otherwise hold the one worker for the rest of that request's timeout before this one went out.
     */
    private void search(String text) {
        searchDue = false;
        still = 0;
        if(pending != null) {
            pending.cancel();
            pending = null;
        }
        String q = text.trim();
        if(q.isEmpty()) {
            clearResults();
            line.settext("");
            return;
        }
        line.settext("searching");
        pending = Registry.search(q);
    }

    /** The rows for an answer, in the hub's order; the line says when there are none. */
    private void show(List<Entry> items) {
        clearResults();
        int y = 0;
        for(Entry e : items) {
            BrowseRow r = results.cont.add(new BrowseRow(e), new Coord(0, y));
            found.add(r);
            y += r.sz.y + UI.scale(2);
        }
        line.settext(items.isEmpty() ? "no addon matches" : "");
    }

    /** The rows go, and the list is back at its top: a shorter answer under an old scroll offset would draw blank. */
    private void clearResults() {
        for(BrowseRow r : found)
            r.destroy();
        found.clear();
        results.bar.val = 0;                             // the fields, not bar.ch(): a refill is no Changed of anyone's
        results.cont.sy = 0;
    }

    // ------------------------------------------------------------- what a row says, on either tab

    /**
     * The metadata a row shows: name, version, who made it, and the markers. {@code protectedCount} is how many
     * permission entries it asked for — the COUNT, because "it can act on your behalf" is the one thing the
     * marker used to say about an addon wanting to change the movement speed and one wanting to send any
     * message the client can; a group is one entry, the same one line the consent dialog renders for it, and
     * the entries themselves are in the tooltip. {@code net} says it declared hosts it can reach (D-037).
     */
    static String meta(String name, String version, String who, int protectedCount, boolean net) {
        return name
            + ((version != null) ? ("  v" + version) : "")
            + ((who != null) ? ("  " + who) : "")
            + ((protectedCount > 0) ? ("  [protected: " + protectedCount + "]") : "")
            + (net ? "  [net]" : "");
    }

    /**
     * The tooltip a row carries: {@code lead} first and whole where there is one — a broken manifest's own
     * reason, or an out-of-date sentence, because a row has room for a state and the tip for the why — then
     * the description, then exactly which permissions it asked for and which hosts it may reach (§5.3), so what
     * an addon wants to do and the servers it talks to are read BEFORE it is enabled or installed, where the
     * row itself has room for how many. {@code null} when there is nothing to say.
     */
    static String tip(String lead, String description, String permissions, List<String> hosts) {
        StringBuilder tip = new StringBuilder();
        if(lead != null)
            tip.append(lead);
        if((description != null) && !description.isEmpty()) {
            if(tip.length() > 0) tip.append("\n\n");
            tip.append(description);
        }
        if((permissions != null) && !permissions.isEmpty()) {
            if(tip.length() > 0) tip.append("\n\n");
            tip.append("Permissions: ").append(permissions);
        }
        if((hosts != null) && !hosts.isEmpty()) {
            if(tip.length() > 0) tip.append("\n\n");
            tip.append("Network hosts: ").append(String.join(", ", hosts));
        }
        return (tip.length() > 0) ? tip.toString() : null;
    }

    /**
     * Set a row's tooltip, WRAPPED: rich mode caps the wrap width at UI.scale(300). settip(_, false) renders it
     * on ONE unwrapped line, so a long addon description becomes a texture wider than GL_MAX_TEXTURE_SIZE and
     * the GL upload fails (GL_INVALID_VALUE 1281 -> crashes the render thread on hover). quote() escapes
     * RichText's $ { } so the description stays literal (descriptions are full of { } [ ] tokens).
     */
    private static void rowTip(Label nm, String tip) {
        if(tip != null)
            nm.settip(RichText.Parser.quote(tip), true);
    }

    /** One addon row: an enable checkbox, the manifest metadata, and a live status label. */
    private final class Row extends Widget {
        final String id;
        private final Label status;
        private final String manifestError;   // why this row has no manifest at all, or null

        Row(AddonInfo ai) {
            super(UI.scale(new Coord(LIST_W, 18)));
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
            Label nm = add(new Label(meta(ai.name, ai.version, ai.author,
                                          ai.declaresPermissions() ? ai.permissions.size() : 0, ai.declaresNetwork())),
                           UI.scale(new Coord(22, 3)));
            // For a broken manifest the tip opens with the REASON, first and whole (an unknown permission key
            // names the valid ones), because a row that says only "manifest error" sends the author to the
            // terminal for something the panel already knows. An out-of-date addon opens the same way (141.1):
            // the row has room for the two numbers, the tip says which side to move — and it says so on a
            // disabled row too, where the status cannot.
            rowTip(nm, tip(broken ? ai.manifestError : ((ai.outdated != null) ? "Out of date: " + ai.outdated : null),
                           ai.description, ai.declaresPermissions() ? ai.permissions.toString() : null,
                           ai.networkHosts));
            status = add(new Label(""), UI.scale(new Coord(STATUS_X, 3)));
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

    /**
     * One row of the Browse tab: the hub's item — name, version, owner, the markers — with the summary, the
     * permissions and the hosts as its tooltip, and a status that is a fact about THIS client: {@code in
     * addons/ by hand} where the folder exists, the out-of-date label where the item's {@code api_version}
     * is not one this client implements (its sentence opening the tooltip, as on an Installed row), else
     * empty. The status is read at build and at every rebuild, not per frame: it is a folder on disk.
     */
    private final class BrowseRow extends Widget {
        final Entry entry;
        private final Label status;
        private final String outdated;        // the label an Installed row would carry for this api_version, or null

        BrowseRow(Entry e) {
            super(UI.scale(new Coord(LIST_W, 18)));
            this.entry = e;
            Label nm = add(new Label(meta(e.name, e.version, e.owner, e.permissions.size(), !e.hosts.isEmpty())),
                           UI.scale(new Coord(0, 3)));
            String lead, label;
            try {
                // The one decision the client makes about every manifest's api_version, made about the hub's
                // copy of it: ApiVersion.why is the sentence, ApiVersion.label the row's state. A field that is
                // not a version at all is refused by parse naming the form, and the row shows that instead.
                ApiVersion v = ApiVersion.parse(e.apiVersion);
                String why = ApiVersion.why(v);
                lead = (why != null) ? "Out of date: " + why : null;
                label = ApiVersion.label(v);
            } catch(IllegalArgumentException x) {
                lead = x.getMessage();
                label = "manifest error (hover)";
            }
            this.outdated = label;
            rowTip(nm, tip(lead, e.summary, e.permissions.isEmpty() ? null : String.join(", ", e.permissions), e.hosts));
            status = add(new Label(""), UI.scale(new Coord(STATUS_X, 3)));
            refresh();
        }

        void refresh() {
            status.settext(AddonRegistry.hasFolder(entry.id) ? "in addons/ by hand"
                           : ((outdated != null) ? outdated : ""));
        }
    }
}
