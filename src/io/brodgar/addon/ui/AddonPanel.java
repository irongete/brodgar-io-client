package io.brodgar.addon.ui;

import haven.Button;
import haven.CheckBox;
import haven.Coord;
import haven.Label;
import haven.OptWnd;
import haven.RichText;
import haven.Scrollport;
import haven.Tabs;
import haven.UI;
import haven.Widget;

import io.brodgar.addon.AddonManager;
import io.brodgar.addon.AddonRegistry;
import io.brodgar.addon.PermissionSet;
import io.brodgar.addon.AddonRegistry.AddonInfo;
import io.brodgar.addon.registry.Entry;
import io.brodgar.addon.registry.Registry;
import io.brodgar.addon.registry.Semver;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * The in-game <b>AddOns</b> options panel (spec {@code 10-options-panel.md}, D-004 / D-006) — the
 * WoW-style addon manager, an {@code OptWnd.Panel} like the client's own, in <b>two tabs</b> since 145.1.
 * It lives in the addon package (it needs no package-private {@code haven} access): it drives the all-static
 * {@link AddonRegistry} facade and the hub client {@link Registry}.
 *
 * <p><b>Installed</b> is the manager as it was: each row is one discovered addon — an <b>enable/disable</b>
 * checkbox (WoW "apply on reload": {@link AddonRegistry#setEnabled}), name/version/author with the description
 * as a tooltip, and a live status (loaded / disabled / error / outdated / auto-disabled / staged / removed) — plus
 * a "changes pending" hint, the <b>Load out of date AddOns</b> checkbox (WoW's, {@link AddonRegistry#setLoadOutdated}:
 * one persisted stance that lets every {@code outdated (…)} row load on the next reload, no permission and no
 * per-row grant), and the global <b>Reload UI</b>, <b>Enable all</b> and <b>Open addons folder</b> controls.
 * A row the hub installed — one whose folder carries the hub's {@code InstallRecord} — carries <b>Remove</b>
 * ({@link AddonRegistry#markRemove}: the folder is marked and the next reload deletes it) and, once a check has
 * found the hub's latest greater than the record's version ({@link Semver#compare}, the hub's own order),
 * <b>Update</b>, which is the install path ({@link AddonRegistry#install}) run on the hub's item (145.3). The
 * check is {@link Registry#lookup} of the hub-installed ids, run whenever the tab comes on screen — watched
 * from {@link #tick}, one transition, so the manager being opened and the tab being switched to are the one
 * door — and by <b>Check for updates</b>, whose own line says {@code checking}, how many, or why the hub did
 * not answer; with nothing installed from the hub, nothing goes out. A by-hand row carries neither button:
 * the client never replaces or deletes a folder the player put there.
 *
 * <p><b>Browse</b> is the hub's own front page in the client's widgets — {@link BrowsePanel}: a search
 * field with the order beside it, the tags as chips, one {@link AddonCard} per addon and a pager, and a
 * card opens the addon's page, {@link AddonDetail}, in the same box. A card's right-hand side is this
 * client's own word on the item — {@code installed v…} where the folder carries the hub's
 * {@code InstallRecord}, {@code in addons/ by hand} where it carries none (the client never replaces a
 * player's own folder), the out-of-date label where its {@code api_version} is not this client's — and
 * <b>Install</b> where the folder is absent, on the card and on the page both. A press is
 * {@link AddonRegistry#install}, and the rest is the registry's — the download runs on the hub client's
 * worker and the layer's step stages what lands — with the card reading which step it is at every tick
 * ({@code downloading n%}, {@code staged v - Reload UI to apply}, {@code failed: why}); the folder itself
 * changes at the next reload, which is when every other change on this panel is applied too, and an
 * Installed row names a stage waiting on it the same way. Everything the tab reads from the hub is polled
 * from its own tick, as this panel polls the facade; the two statics a card and a row share
 * ({@link #failTip}, {@link #pendingStatus}) live here.
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
 * {@link CheckBox}, {@link Button}, {@link Label}, {@link Tabs}) is a public {@code haven}
 * type. It holds <b>no</b> listener or subscription — it polls the facade and the hub client in
 * {@link #tick(double)} — so there is nothing to leak when it closes. Rows are rebuilt when a reload changes
 * the addon set (watched via {@link AddonRegistry#reloadGen()}); the per-row status is refreshed cheaply each
 * frame.
 */
public class AddonPanel extends OptWnd.Panel {
    /**
     * The list width, both tabs: an Installed row's metadata to {@link #STATUS_X}, its status from there, and
     * two button columns — {@link #BUTTON_X} (Update) and {@link #BUTTON2_X} (Remove) — each
     * {@link #BUTTON_W} wide, the last ending short of the port's scrollbar. The Browse tab is
     * {@link BrowsePanel#W} wide, which is this, and its cards lay their own columns out.
     */
    static final int LIST_W = 624, STATUS_X = 290, BUTTON_X = 480, BUTTON2_X = 544, BUTTON_W = 60;
    /** The Installed list's height: as tall as the Browse tab leaves room for under its own controls. */
    static final int LIST_H = 260;
    /** How long the search field's text has to be still before it is searched, in seconds. */
    static final double SEARCH_STILL = 1.0 / 3;

    private final Tabs tabs;
    private final Tabs.Tab installed, browse;
    // -- Installed
    private final Scrollport list;
    private final Label hint;
    private final List<Row> rows = new ArrayList<Row>();
    private Label empty;                    // "No addons installed", centred in the list, exactly while it is empty
    private int builtGen = Integer.MIN_VALUE;
    private PermissionConsentWnd consent;   // the live enable-time permission consent dialog (4c), or null/destroyed
    // -- the update check (145.3)
    private final Label checked;            // the check's own line: checking / how many / why the hub did not answer
    private final Map<String, Entry> updates = new HashMap<String, Entry>();   // id -> the hub's item, newer than the record
    private Registry.Request<List<Entry>> checking;   // the lookup in flight, the only one ever read
    private boolean checkedOk = false;      // the line shows a count (else nothing, or a failure)
    private boolean showing = false;        // the Installed tab was on screen at the last tick
    // -- Browse
    private final BrowsePanel browsing;     // the whole tab: the front page, and the addon's page over it

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
        // a button column stands at BUTTON_X beside the status, which is what widened it from 480; 145.3: a
        // second at BUTTON2_X, for Update and Remove side by side, which widened it to LIST_W.
        // The list is clipped at its edge, not wrapped, so a row wider than this reads as a status cut mid-word.
        list = installed.add(new Scrollport(UI.scale(new Coord(LIST_W, LIST_H))), prev.pos("bl").adds(0, 8));
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
        // 145.3: the check, on a row of its own with its line beside it -- the pair a search field and its line
        // make on Browse. The same check runs by itself whenever the tab comes on screen (see tick).
        Button check = installed.add(new Button(UI.scale(150), "Check for updates", false).action(this::checkUpdates),
                                     reload.pos("bl").adds(0, 8));
        Label word = new Label("");
        checked = installed.add(word, new Coord(check.pos("ur").x + UI.scale(8), check.c.y + (check.sz.y - word.sz.y) / 2));

        // ---- Browse: the hub's front page, and the addon's page over it
        browsing = browse.add(new BrowsePanel(), Coord.z);

        // Tabs.pack gives both tabs the union box, so Browse is laid out to Installed's height and Back sits
        // below both at the same place whichever is showing.
        tabs.pack();
        add(opt.new PButton(UI.scale(200), "Back", 27, back), installed.pos("bl").adds(0, 8));
        rebuild();
        pack();
    }

    /**
     * (Re)build the Installed rows from {@link AddonRegistry#describeAddons()} (reads manifests from disk), and
     * re-read every Browse card's folder status: a reload is the moment {@code addons/} may have changed. The
     * updates the last check found are held to the folders as they are now — one whose record has caught up
     * with the hub's item, or whose folder is gone, is dropped — and the line says what is left. An empty
     * list says so in its middle, and says it exactly while it is empty: the word goes with the rows it stood
     * in for, so an install never leaves it standing under the first row.
     */
    private void rebuild() {
        for(Row r : rows)
            r.destroy();
        rows.clear();
        if(empty != null) {
            empty.destroy();
            empty = null;
        }
        int y = 0;
        for(AddonInfo ai : AddonRegistry.describeAddons()) {
            Row r = list.cont.add(new Row(ai), new Coord(0, y));
            rows.add(r);
            y += r.sz.y + UI.scale(2);
        }
        if(rows.isEmpty()) {
            empty = list.cont.add(new Label("No addons installed"), Coord.z);
            empty.c = list.cont.sz.sub(empty.sz).div(2);
        }
        builtGen = AddonRegistry.reloadGen();
        browsing.rescan();
        for(Iterator<Map.Entry<String, Entry>> it = updates.entrySet().iterator(); it.hasNext();) {
            Map.Entry<String, Entry> u = it.next();
            if(!newer(u.getValue(), row(u.getKey())))
                it.remove();
        }
        if(checkedOk)
            checkedLine();
    }

    /** The Installed row of {@code id}, or {@code null}. */
    private Row row(String id) {
        for(Row r : rows) {
            if(r.id.equals(id))
                return r;
        }
        return null;
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
        // 145.3: the Installed tab coming on screen is the one transition the check hangs on -- the manager
        // opened on it, or the tab switched to -- read here rather than from show() and the tab button both,
        // because a Panel is shown by chpanel and a Tab by showtab and this is the one place that sees either.
        boolean on = tvisible() && installed.visible();
        if(on && !showing)
            checkUpdates();
        showing = on;
        pollCheck();
    }

    // ------------------------------------------------------------- the update check (145.3)

    /**
     * Ask the hub for the latest of every hub-installed id — {@link Registry#lookup}, one request, read by
     * {@link #pollCheck}. A check still out is cancelled first: only the latest is ever read. Nothing goes out
     * when no row carries the hub's record: there is nothing to ask about, and the line says so.
     */
    private void checkUpdates() {
        if(checking != null) {
            checking.cancel();
            checking = null;
        }
        List<String> ids = new ArrayList<String>();
        for(Row r : rows) {
            if(r.hub != null)
                ids.add(r.id);
        }
        checkedOk = false;
        if(ids.isEmpty()) {
            checked.settext("no addon installed from the hub");
            return;
        }
        checked.settext("checking");
        checking = Registry.lookup(ids);
    }

    /**
     * The check's answer, once it is in: every item whose version is greater than the record's, by the hub's
     * order, becomes an update the row offers; a record that is not a version at all is logged and skipped, so
     * a tampered record never reads as an update. An id the hub no longer carries is simply not in the answer.
     * The line then says how many, or why the hub did not answer.
     */
    private void pollCheck() {
        if((checking == null) || !checking.done())
            return;
        Registry.Request<List<Entry>> r = checking;
        checking = null;
        if(r.error() != null) {
            checked.settext(r.error());
            return;
        }
        updates.clear();
        for(Entry e : r.result()) {
            if(newer(e, row(e.id)))
                updates.put(e.id, e);
        }
        checkedOk = true;
        checkedLine();
    }

    /** Whether the hub's item {@code e} is a greater version than the record {@code row} carries; never for a by-hand row. */
    private static boolean newer(Entry e, Row row) {
        if((row == null) || (row.hub == null))
            return false;
        try {
            return Semver.compare(e.version, row.hub) > 0;
        } catch(IllegalArgumentException x) {
            AddonManager.log("update check of " + e.id + ": " + x.getMessage());
            return false;
        }
    }

    /** The line for a count: how many rows offer an update. */
    private void checkedLine() {
        int n = updates.size();
        checked.settext((n == 0) ? "no update available" : (n + ((n == 1) ? " update available" : " updates available")));
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
     * row itself has room for how many. {@code needs}/{@code optional}/{@code usedBy} are the manifest's
     * dependency lists (156.2). {@code null} when there is nothing to say.
     */
    static String tip(String lead, String description, String permissions, List<String> hosts,
                      List<String> needs, List<String> optional, List<String> usedBy) {
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
        if((needs != null) && !needs.isEmpty()) {
            if(tip.length() > 0) tip.append("\n\n");
            tip.append("Needs: ").append(String.join(", ", needs));
        }
        if((optional != null) && !optional.isEmpty()) {
            if(tip.length() > 0) tip.append("\n\n");
            tip.append("Optional: ").append(String.join(", ", optional));
        }
        if((usedBy != null) && !usedBy.isEmpty()) {
            if(tip.length() > 0) tip.append("\n\n");
            tip.append("Used by: ").append(String.join(", ", usedBy));
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

    /**
     * A row's button, in the tree exactly while it is offered — added when the row starts offering it and
     * destroyed when it stops, never hidden: a selector walks hidden widgets too, so a row that "carries no
     * button" has to carry none. Answers the button as it now stands, for the field that holds it. Narrower
     * than its column, because the port's content is the list less its scrollbar and a widget past that edge
     * is clipped. Safe from a tick and from the press itself — a button's own {@code click} is the last thing
     * its {@code mouseup} does.
     */
    private static Button offer(Widget row, Button b, boolean on, String text, int x, Runnable action) {
        if(on == (b != null))
            return b;
        if(on)
            return row.add(new Button(UI.scale(BUTTON_W), text, false).action(action), new Coord(UI.scale(x), 0));
        b.destroy();
        return null;
    }

    /**
     * The status label's own tooltip: the whole failure where the label clips it, none otherwise. Answers what
     * it now holds, for the field that remembers it, so the tip is re-rendered only when the sentence moves.
     */
    static String failTip(Label status, String why, String held) {
        if(why == null)
            status.tooltip = null;
        else if(!why.equals(held))
            rowTip(status, why);
        return why;
    }

    /**
     * The status a row carries for what waits on its folder ({@link AddonRegistry#pending}): a stage's
     * version, or the removal, and the gesture that applies it — the next reload, or a restart where the last
     * reload could not move the folder.
     */
    static String pendingStatus(AddonRegistry.Pending p) {
        String gesture = p.restart ? "restart" : "Reload UI";
        return p.removal ? ("removed on " + gesture) : ("staged " + p.version + " - " + gesture + " to apply");
    }

    /**
     * One addon row: an enable checkbox, the manifest metadata, a live status label — and, on a row the hub
     * installed, <b>Remove</b> at {@link #BUTTON2_X} and <b>Update</b> at {@link #BUTTON_X} once a check has
     * found a greater version (145.3). The status is the first of these that is true: {@code downloading n%}
     * while an update's download runs; the stage or the removal waiting on the folder ({@link #pendingStatus});
     * {@code failed: why} after a download or a stage the registry refused, the whole why in the label's own
     * tooltip; {@code manifest error (hover)}; {@code update v} where the hub's item is newer than the record;
     * else {@link AddonRegistry#liveStatus}. The buttons stand only while nothing waits on the folder and
     * nothing is in flight for it: what is staged, marked or downloading is applied on reload, and a second
     * word on the same folder before then would be one the reload could not keep.
     */
    private final class Row extends Widget {
        final String id;
        final String hub;                     // the hub's record in the folder, or null for a by-hand folder
        private final Label status;
        private final String manifestError;   // why this row has no manifest at all, or null
        private Button update, remove;        // in the tree exactly while offered (offer)
        private String tipped;                // the failure the status label's tooltip holds, or null

        Row(AddonInfo ai) {
            // As tall as a button, which is taller than a line of text: Button.hs is its images' own height.
            super(new Coord(UI.scale(LIST_W), Math.max(UI.scale(18), Button.hs)));
            final String rid = ai.id;
            final boolean writes = ai.declaresPermissions();   // D-027: enabling this addon needs consent (4c)
            final PermissionSet declared = ai.permissions;
            final String aname = ai.name;
            // A manifest that does not parse leaves NOTHING to enable: the addon cannot load whatever the
            // persisted bit says, so the box is shown unticked and does not answer — a ticked box beside a row
            // that will never load is the panel claiming a state the client cannot reach.
            final boolean broken = (ai.manifestError != null);
            this.manifestError = ai.manifestError;
            // Every child is placed at a FRESH Coord, computed before the add: Widget.add keeps the very object
            // it is handed, and a `child.c.y = …` on one added at Coord.z would move the client's shared zero.
            CheckBox box = new CheckBox("") {
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
                };
            add(box, new Coord(0, (sz.y - box.sz.y) / 2));
            Label nm = new Label(meta(ai.name, ai.version, ai.author,
                                      ai.declaresPermissions() ? ai.permissions.size() : 0, ai.declaresNetwork()));
            add(nm, new Coord(UI.scale(22), (sz.y - nm.sz.y) / 2));
            // For a broken manifest the tip opens with the REASON, first and whole (an unknown permission key
            // names the valid ones), because a row that says only "manifest error" sends the author to the
            // terminal for something the panel already knows. An out-of-date addon opens the same way (141.1):
            // the row has room for the two numbers, the tip says which side to move — and it says so on a
            // disabled row too, where the status cannot.
            rowTip(nm, tip(broken ? ai.manifestError : ((ai.outdated != null) ? "Out of date: " + ai.outdated : null),
                           ai.description, ai.declaresPermissions() ? ai.permissions.toString() : null,
                           ai.networkHosts, ai.needs, ai.optional, ai.usedBy));
            status = add(new Label(""), new Coord(UI.scale(STATUS_X), nm.c.y));
            this.id = rid;
            this.hub = ai.hub;
            refresh();
        }

        private void refresh() {
            // liveStatus is the cheap per-frame read and does no manifest I/O, so it can only report
            // "not loaded" for an addon whose manifest is the thing that failed. The row read it once at
            // build time and keeps it: the reason is in the tooltip, the label just says which kind of row
            // this is. 145.2: a stage waiting on this folder outranks the live state -- what is loaded is
            // about to be replaced, and the row says by what and by which gesture. 145.3: so does an update's
            // download, and a removal; and an update the check found outranks the live state too, because
            // the press it offers is about what is loaded.
            Entry latest = updates.get(id);
            int progress = AddonRegistry.downloading(id);
            AddonRegistry.Pending p = AddonRegistry.pending(id);
            String why = AddonRegistry.failed(id);
            String s;
            boolean act = false;             // the buttons stand while nothing waits on the folder
            if(progress >= 0) {
                s = "downloading " + progress + "%";
            } else if(p != null) {
                s = pendingStatus(p);
            } else {
                act = (hub != null);
                if(why != null)
                    s = "failed: " + why;    // ...and a retry is one press away
                else if(manifestError != null)
                    s = "manifest error (hover)";
                else if(latest != null)
                    s = "update " + latest.version;
                else
                    s = AddonRegistry.liveStatus(id);
            }
            status.settext(s);
            tipped = failTip(status, why, tipped);
            // The press reads the map at press time, not the item the button was built for: a later check
            // may have found a newer version still, and the button stands through it.
            update = offer(this, update, act && (latest != null), "Update", BUTTON_X, () -> {
                    Entry e = updates.get(id);
                    if(e != null)
                        AddonRegistry.install(e);
                });
            remove = offer(this, remove, act, "Remove", BUTTON2_X, () -> AddonRegistry.markRemove(id));
        }

        public void tick(double dt) {
            super.tick(dt);
            refresh();
        }
    }
}
