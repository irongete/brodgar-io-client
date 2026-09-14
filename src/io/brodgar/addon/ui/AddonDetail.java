package io.brodgar.addon.ui;

import haven.Button;
import haven.CharWnd;
import haven.Coord;
import haven.Frame;
import haven.GridList;
import haven.Label;
import haven.Scrollport;
import haven.Text;
import haven.UI;
import haven.Widget;
import haven.Window;

import io.brodgar.addon.AddonManager;
import io.brodgar.addon.AddonRegistry;
import io.brodgar.addon.ApiVersion;
import io.brodgar.addon.PermissionSet;
import io.brodgar.addon.registry.Detail;
import io.brodgar.addon.registry.Entry;
import io.brodgar.addon.registry.Registry;

import java.awt.Font;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * <b>One addon's page</b>, in the Browse tab's box — what the hub shows at {@code brodgar.io/addons/<id>},
 * drawn with the client's widgets: <b>Back to list</b> over a scrolling page whose head is the icon, the
 * name with its version and author, the tags as chips (a press filters the list by that tag), the
 * {@code [net]} mark, the summary, and <b>Install</b> beside this client's own word on the addon — the very
 * status a card shows — with <b>Open at brodgar.io</b> for the page in a browser. Under the head, once the
 * hub has answered ({@link Registry#detail}, polled from {@link #tick}): the screenshots in a
 * {@link Gallery}, the long description as text, every published version with its changelog, the
 * permissions it asks for in the words this client's consent dialog uses, and the facts the hub's
 * <i>About</i> box lists. The head is built at once from the list's own item, so the page is never blank;
 * {@code Loading the page…} stands under it until the rest lands, or the hub's own sentence when it did
 * not answer.
 *
 * <p><b>The page is a column of {@link Row}s, placed from what its widgets measure</b> ({@link #place}):
 * built once, and placed again whenever a size on it moved — a sheet's rule reaches a label when it is
 * <i>drawn</i>, so a label built at the client's stock size may be another size a frame later, and a place
 * computed once at build would leave the rows on top of one another. A row is as tall as its tallest
 * member; a member stands at a fixed x, after the one before it, or against the right edge, and on the
 * row's top, its foot, its middle, or its <b>baseline</b> — the name line and the version table set a
 * small face beside a large one on the one line. The pass is one loop over the page's widgets a tick,
 * comparing sizes.
 *
 * <p>Nothing here is a copy of the list's state: <b>Install</b> is {@link AddonRegistry#install} on the
 * hub's item, exactly as a card's is, and the status reads the registry's maps every tick and the two
 * facts on disk at build and at every reload ({@link #rescan}).
 */
final class AddonDetail extends Widget {
    /** The page's geometry, design pixels: the big icon, the gallery's height, the widths of its buttons. */
    static final int ICON = 72, GALLERY_H = 240, BACK_W = 110, INSTALL_W = 100, SITE_W = 150;
    /** The About box's columns: where a value starts. */
    static final int VALUE_X = 120;
    /** The version table's columns: API, size, published, downloads. */
    static final int COL_API = 110, COL_SIZE = 170, COL_DATE = 250, COL_DL = 370;
    /**
     * The most of a description, and of a changelog, the page draws: a label is one raster, and a README the
     * length of a manual would be a texture taller than the card can upload. The whole is one press away, at
     * the hub.
     */
    static final int DESC_MAX = 3000, LOG_MAX = 1000;
    /** Where a row's member stands: at an x of its own, after the one before it, or against the right edge. */
    static final int LEFT = 0, AFTER = 1, RIGHT = 2;
    /** ...and how it sits on the row: on its top, on its foot, in its middle, or on the row's baseline. */
    static final int TOP = 0, FOOT = 1, MID = 2, BASE = 3;

    private final BrowsePanel owner;
    private final Entry item;
    private final Scrollport port;
    private Registry.Request<Detail> req;
    private Detail page;                  // the hub's page, once in
    private String failed;                // why it did not come, once known
    private Widget content;               // everything the port holds, rebuilt when the page lands
    private final List<Row> rows = new ArrayList<Row>();
    private int sizes = -1;               // the sum of every size on the page, as last placed
    // -- the head's status
    private Row actions;
    private Label status;
    private Button install;               // in the tree exactly while it is offered, as a row's is
    private final String outdated;        // the label an Installed row would carry for this api_version, or null
    private boolean folder;               // addons/<id>/ exists -- a fact on disk, read by rescan()
    private String hub;                   // ...and the hub's record in it, or null for a by-hand folder
    private String tipped;                // the failure the status label's tooltip holds, or null

    AddonDetail(BrowsePanel owner, Entry e) {
        super(UI.scale(new Coord(BrowsePanel.W, BrowsePanel.H)));
        this.owner = owner;
        this.item = e;
        Button back = add(new Button(UI.scale(BACK_W), "Back to list", false).action(owner::back), Coord.z);
        Label where = HubText.muted(site() + "/" + e.id);
        add(where, new Coord(back.sz.x + UI.scale(10), (back.sz.y - where.sz.y) / 2));
        int y = back.sz.y + UI.scale(8);
        port = add(new Scrollport(new Coord(sz.x, sz.y - y)), new Coord(0, y));
        String label;
        try {
            ApiVersion v = ApiVersion.parse(e.apiVersion);
            label = ApiVersion.label(v);
        } catch(IllegalArgumentException x) {
            label = "manifest error (hover)";
        }
        this.outdated = label;
        folder = AddonRegistry.hasFolder(e.id);
        hub = folder ? AddonRegistry.hubVersion(e.id) : null;
        req = Registry.detail(e.id);
        build();
    }

    /** The hub's own site, for a page's address: its API base less the {@code /api}. */
    static String site() {
        String b = Registry.base();
        return b.endsWith("/api") ? b.substring(0, b.length() - 4) : b;
    }

    /** Re-read the two facts on disk: the folder, and the hub's record in it. At every reload. */
    void rescan() {
        folder = AddonRegistry.hasFolder(item.id);
        hub = folder ? AddonRegistry.hubVersion(item.id) : null;
        refresh();
    }

    public void tick(double dt) {
        super.tick(dt);
        if((req != null) && req.done()) {
            Registry.Request<Detail> r = req;
            req = null;
            if(r.error() != null)
                failed = r.error();
            else
                page = r.result();
            build();
        }
        refresh();
        // A size moved -- a label drawn under a sheet's face for the first time, a picture in -- and the
        // rows are placed again from what everything measures now.
        if(measure() != sizes)
            place();
    }

    // ------------------------------------------------------------- the rows

    /**
     * One row of the page: its members left to right, each with where it stands and how it sits, the row as
     * tall as its tallest member. {@link #floor} is where the row must end at the earliest, for the head's
     * rows beside the icon; {@link #gap} the room under it.
     */
    private final class Row {
        final List<Widget> ws = new ArrayList<Widget>();
        final List<int[]> at = new ArrayList<int[]>();
        final int gap;
        int floor;

        Row(int gap) {
            this.gap = gap;
        }

        <T extends Widget> T add(T w, int xmode, int xval, int ymode) {
            content.add(w, Coord.z);
            ws.add(w);
            at.add(new int[] {xmode, xval, ymode});
            return w;
        }

        <T extends Widget> T first(T w, int xmode, int xval, int ymode) {
            content.add(w, Coord.z);
            ws.add(0, w);
            at.add(0, new int[] {xmode, xval, ymode});
            return w;
        }

        void remove(Widget w) {
            int i = ws.indexOf(w);
            if(i >= 0) {
                ws.remove(i);
                at.remove(i);
            }
        }

        /**
         * Place the members on the row at {@code top}; answers where the next row starts. The row is as
         * tall as its tallest member, or as the members on its baseline need together -- the deepest
         * baseline among them above it, the deepest descent below -- and that group is centred in it.
         */
        int place(int top, int cw) {
            int h = 0, above = 0, below = 0;
            for(int i = 0; i < ws.size(); i++) {
                Widget w = ws.get(i);
                if(at.get(i)[2] == BASE) {
                    int b = HubText.baseline(w);
                    above = Math.max(above, b);
                    below = Math.max(below, w.sz.y - b);
                } else {
                    h = Math.max(h, w.sz.y);
                }
            }
            h = Math.max(h, above + below);
            int base = top + (h - (above + below)) / 2 + above, x = 0;
            for(int i = 0; i < ws.size(); i++) {
                Widget w = ws.get(i);
                int[] a = at.get(i);
                int wx = (a[0] == LEFT) ? a[1] : (a[0] == AFTER) ? x + a[1] : cw - w.sz.x - a[1];
                int wy = (a[2] == TOP) ? top : (a[2] == FOOT) ? top + h - w.sz.y
                    : (a[2] == MID) ? top + (h - w.sz.y) / 2 : base - HubText.baseline(w);
                Coord c = new Coord(wx, wy);
                if(!c.equals(w.c))
                    w.c = c;
                x = wx + w.sz.x;
            }
            return Math.max(top + h + gap, floor);
        }
    }

    private Row row(int gap) {
        Row r = new Row(UI.scale(gap));
        rows.add(r);
        return r;
    }

    /** A section heading, the client's own group caption, on a row of its own. */
    private void heading(String text) {
        row(4).add(new CharWnd.Heading(text, GridList::dcatfont), LEFT, 0, TOP);
    }

    /** Every size on the page, folded into one number: what tells a tick that something re-rendered. */
    private int measure() {
        int s = 0;
        for(Widget w = content.child; w != null; w = w.next)
            s = s * 31 + w.sz.x * 7 + w.sz.y;
        return s;
    }

    /** The rows, one under the other, from what their members measure now; then the content's own height. */
    private void place() {
        int cw = port.cont.sz.x, y = 0;
        for(Row r : rows)
            y = r.place(y, cw);
        Coord c = new Coord(cw, y + UI.scale(4));
        if(!c.equals(content.sz))
            content.resize(c);
        sizes = measure();
    }

    // ------------------------------------------------------------- the page

    /** The whole page, from the item and — once it is in — the hub's page: the head, then the sections. */
    private void build() {
        if(content != null)
            content.destroy();
        rows.clear();
        install = null;
        int cw = port.cont.sz.x;
        content = port.cont.add(new Widget(new Coord(cw, 0)), Coord.z);
        Entry e = (page != null) ? page : item;
        head(e, cw);
        if(page != null) {
            if(!page.images.isEmpty())
                row(12).add(new Gallery(cw, page.images), LEFT, 0, TOP);
            String desc = cut(HubText.plain(page.descriptionHtml), DESC_MAX);
            if(desc != null) {
                heading("Description");
                row(12).add(new Label(desc, cw), LEFT, 0, TOP);
            }
            versions(page, cw);
            permissions(e, cw);
            about(page, cw);
        } else if(failed != null) {
            row(0).add(new Label("The hub did not answer: " + failed, cw), LEFT, 0, TOP);
        } else {
            row(0).add(HubText.muted("Loading the page…"), LEFT, 0, TOP);
        }
        refresh();
        place();
    }

    /** The head: the icon, and beside it the name line, the tags and the mark, the summary, and the actions. */
    private void head(Entry e, int cw) {
        int px = UI.scale(ICON), left = px + UI.scale(12), tw = cw - left;
        content.add(new HubImage(e.icon, new Coord(px, px), e.name), Coord.z);
        Row title = row(6);
        title.add(HubText.in(e.name, HubText.TITLE, null), LEFT, left, BASE);
        if(e.version != null)
            title.add(HubText.muted("v" + e.version), AFTER, UI.scale(8), BASE);
        if(e.by() != null)
            title.add(HubText.muted("by " + e.by()), AFTER, UI.scale(10), BASE);
        // The tags as chips, each a press that filters the list by it, then the mark beside them.
        if(!e.tags.isEmpty() || !e.hosts.isEmpty()) {
            Row tags = row(8);
            Text.Foundry capf = new Text.Foundry(Text.serif.deriveFont(Font.BOLD, UI.scale(12f))).aa(true);
            boolean first = true;
            for(final String t : e.tags) {
                int w = capf.strsize(t).x + UI.scale(20);
                tags.add(new Button(w, t, false).action(() -> owner.filter(t)), first ? LEFT : AFTER, first ? left : UI.scale(4), MID);
                first = false;
            }
            if(!e.hosts.isEmpty())
                tags.add(AddonCard.mark(e), first ? LEFT : AFTER, first ? left : UI.scale(10), MID);
        }
        String summary = ((e.summary == null) || e.summary.trim().isEmpty()) ? null : e.summary.trim();
        row(10).add((summary == null) ? HubText.muted("No summary yet.", tw) : new Label(summary, tw), LEFT, left, TOP);
        // The actions: Install where the folder is absent, the status beside it, the site at the far right.
        // The row reaches below the icon, so what follows the head starts under both.
        actions = row(14);
        actions.floor = px + UI.scale(14);
        Button site = actions.add(new Button(UI.scale(SITE_W), "Open at brodgar.io", false).action(this::browse), RIGHT, 0, MID);
        int statusW = cw - site.sz.x - UI.scale(10) - (left + UI.scale(INSTALL_W) + UI.scale(10));
        status = actions.first(new Label("", statusW), LEFT, left, MID);
    }

    /** The versions, newest first: a header row, one row each, and the changelog under a version that has one. */
    private void versions(Detail d, int cw) {
        heading("Versions");
        int[] cols = {0, UI.scale(COL_API), UI.scale(COL_SIZE), UI.scale(COL_DATE), UI.scale(COL_DL)};
        String[] heads = {"Version", "API", "Size", "Published", "Downloads"};
        Row h = row(3);
        for(int i = 0; i < heads.length; i++)
            h.add(HubText.muted(heads[i]), LEFT, cols[i], BASE);
        if(d.versions.isEmpty()) {
            row(12).add(HubText.muted("none"), LEFT, 0, TOP);
            return;
        }
        for(Detail.Version v : d.versions) {
            String[] cells = {v.version, api(v.apiVersion), HubText.bytes(v.size), HubText.date(v.publishedAt),
                              Long.toString(v.downloads)};
            Row r = row(2);
            for(int i = 0; i < cells.length; i++)
                r.add(new Label(cells[i]), LEFT, cols[i], BASE);
            if((v.changelog != null) && !v.changelog.trim().isEmpty())
                row(4).add(HubText.muted(cut(v.changelog.trim(), LOG_MAX), cw - UI.scale(16)), LEFT, UI.scale(16), TOP);
        }
        row(10);   // the room under the table
    }

    /** What it asks to do, one line per declared entry in the consent dialog's words, or that it asks nothing. */
    private void permissions(Entry e, int cw) {
        heading("Permissions");
        if(e.permissions.isEmpty()) {
            row(12).add(HubText.muted("None: it reads the game and writes only what is client-local.", cw), LEFT, 0, TOP);
            return;
        }
        for(String entry : e.permissions) {
            String line = PermissionSet.describe(entry, e.hosts, null);
            String text = "• " + entry + (line.isEmpty() ? "  (not in this client's catalogue)" : " — " + line);
            row(3).add(new Label(text, cw - UI.scale(8)), LEFT, UI.scale(4), TOP);
        }
        row(10);
    }

    /** {@code s} at most {@code max} characters long, cut at a word with an ellipsis; {@code null} stays {@code null}. */
    static String cut(String s, int max) {
        if((s == null) || (s.length() <= max))
            return s;
        int at = s.lastIndexOf(' ', max);
        return s.substring(0, (at > max / 2) ? at : max).trim() + "…";
    }

    /** An {@code api_version} as the page writes it: the version, {@code none} where the manifest declares none, else the field as it is. */
    static String api(Object v) {
        try {
            ApiVersion a = ApiVersion.parse(v);
            return (a == null) ? "none" : a.toString();
        } catch(IllegalArgumentException x) {
            return String.valueOf(v);
        }
    }

    /** The facts the hub's About box lists: id, author, API, downloads, the dates, the digest, the links. */
    private void about(Detail d, int cw) {
        heading("About");
        fact("id", d.id, cw);
        fact("author", d.by(), cw);
        fact("API", api(d.apiVersion), cw);
        fact("downloads", Long.toString(d.downloads), cw);
        fact("first published", HubText.date(d.createdAt), cw);
        fact("last update", HubText.date(d.updatedAt), cw);
        fact("size", HubText.bytes(d.size), cw);
        fact("sha256", d.sha256, cw);
        for(Map.Entry<String, String> l : d.links.entrySet())
            fact(l.getKey(), l.getValue(), cw);
    }

    /** One row of the About box: the name muted, the value beside it, wrapped to the rest of the width; a dash for none. */
    private void fact(String name, String value, int cw) {
        Row r = row(3);
        r.add(HubText.muted(name), LEFT, 0, TOP);
        int vx = UI.scale(VALUE_X);
        r.add(new Label(((value == null) || value.isEmpty()) ? "—" : value, cw - vx), LEFT, vx, TOP);
    }

    // ------------------------------------------------------------- the actions

    /** The page in the player's browser: the hub's own, at this addon's address. */
    private void browse() {
        String url = site() + "/" + item.id;
        try {
            java.awt.Desktop.getDesktop().browse(new java.net.URI(url));
        } catch(Exception e) {
            AddonManager.log("could not open " + url + ": " + e);
        }
    }

    /** The status and the button, as a card refreshes its own: the same words, from the same maps. */
    private void refresh() {
        if(status == null)
            return;
        int progress = AddonRegistry.downloading(item.id);
        AddonRegistry.Pending p = AddonRegistry.pending(item.id);
        String why = AddonRegistry.failed(item.id);
        String s;
        boolean button = false;
        if(progress >= 0) {
            s = "downloading " + progress + "%";
        } else if(p != null) {
            s = AddonPanel.pendingStatus(p);
        } else if(why != null) {
            s = "failed: " + why;
            button = !folder;               // ...and a retry is one press away
        } else if(hub != null) {
            s = "installed v" + hub;
        } else if(folder) {
            s = "in addons/ by hand";
        } else {
            s = (outdated != null) ? outdated : "";
            button = true;
        }
        status.settext(s, status.wrapw());
        tipped = AddonPanel.failTip(status, why, tipped);
        // The button stands in the tree exactly while it is offered, as a row's does: first on the actions
        // row with the status after it, or gone and the status back at the head's edge. The item the press
        // installs is the hub's page where it is in -- its latest -- else the list's item.
        int left = UI.scale(ICON) + UI.scale(12);
        if(button && (install == null)) {
            install = actions.first(new Button(UI.scale(INSTALL_W), "Install", false)
                                    .action(() -> AddonRegistry.install((page != null) ? page : item)), LEFT, left, MID);
            actions.at.set(1, new int[] {AFTER, UI.scale(10), MID});
            sizes = -1;
        } else if(!button && (install != null)) {
            actions.remove(install);
            install.destroy();
            install = null;
            actions.at.set(0, new int[] {LEFT, left, MID});
            sizes = -1;
        }
    }

    /**
     * <b>The screenshots</b>, one at a time in a box as wide as the page: the picture scaled to fit, centred,
     * with ‹ and › to step through them and a counter at the foot, the hub page's own slider without its
     * thumbnails. Each picture is a {@link HubImage} of its own, built when it is stepped to and kept by the
     * hub client, so stepping back costs no fetch. A panel, dressed like every other.
     */
    private final class Gallery extends Frame {
        private final List<String> images;
        private int at = 0;
        private HubImage shown;
        private final Label counter;

        Gallery(int w, List<String> images) {
            super(new Coord(w, UI.scale(GALLERY_H)), false, Window.wbox);
            this.images = images;
            // A Frame's children stand in its inner box (Frame.xlate), so (0, 0) is inside the edge already.
            Coord in = inner();
            int nav = UI.scale(28);
            if(images.size() > 1) {
                add(new Button(nav, "‹", false).action(() -> go(at - 1)), new Coord(UI.scale(6), (in.y - Button.hs) / 2));
                add(new Button(nav, "›", false).action(() -> go(at + 1)),
                    new Coord(in.x - UI.scale(6) - nav, (in.y - Button.hs) / 2));
            }
            counter = add(HubText.muted(""), Coord.z);
            go(0);
        }

        private void go(int i) {
            at = ((i % images.size()) + images.size()) % images.size();
            if(shown != null)
                shown.destroy();
            Coord in = inner();
            shown = add(new HubImage(images.get(at), in, null), Coord.z);
            shown.lower();
            counter.settext((at + 1) + " / " + images.size());
            counter.c = new Coord((in.x - counter.sz.x) / 2, in.y - counter.sz.y - UI.scale(4));
            counter.raise();
        }

        public boolean mousedown(MouseDownEvent ev) {
            if(ev.propagate(this))
                return true;
            if((ev.b == 1) && (images.size() > 1)) {
                go(at + 1);
                return true;
            }
            return false;
        }
    }
}
