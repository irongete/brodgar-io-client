package io.brodgar.addon.ui;

import haven.Button;
import haven.Coord;
import haven.Fonts;
import haven.Frame;
import haven.GOut;
import haven.Label;
import haven.RichText;
import haven.Text;
import haven.UI;
import haven.Widget;
import haven.Window;

import io.brodgar.addon.AddonRegistry;
import io.brodgar.addon.ApiVersion;
import io.brodgar.addon.registry.Entry;

import java.awt.Color;

/**
 * <b>One card of the Browse list</b> — the hub's item as its own front page shows one, on two lines: the
 * icon, then the name with the version and the author beside it and the {@code [net]} mark a row on the
 * Installed tab carries too; under them the summary, cut to the line with an ellipsis, and at the line's
 * end how many times it was downloaded and when it was last updated. The whole card is the button that
 * opens the addon's page ({@link BrowsePanel#open}); its right-hand side is this client's own word on it —
 * the status a Browse row had, at the end of the name line, and <b>Install</b> where the folder is absent
 * — so an addon is installed from the list in one press, as it was, and read about in another.
 *
 * <p><b>The card lays itself out every tick, from what its labels measure then</b> ({@link #layout}), and
 * is as tall as they need: a sheet's rule reaches a label when it is <i>drawn</i> — a tree rule resolves
 * through the frame the label is drawn in — so a label built at the client's stock size may be another
 * size a frame later, and a place computed once at build would leave it clipped at the frame and the lines
 * off their centre. The pass is a handful of comparisons a card; a label's text is cut again only when its
 * room or its size moved. The list stacks the cards again as they grow ({@link BrowsePanel}).
 *
 * <p>A card is a {@link Frame}: the box the client's own list and info panels are drawn with, dressed by a
 * sheet's {@code panel} rule like every one of them, and — unlike the boxed panels, whose contents are not
 * their own — one that paints its surface <b>under</b> its contents, so a rule's {@code bg} lands on it as
 * on a petal or a dropdown, in a {@code hover} face under the pointer where the rule names one. Under the
 * pointer it lifts a little either way, the hub page's own hover.
 *
 * <p>The status is the first of these that is true, exactly as a row's was: {@code downloading n%} while
 * the registry's download runs; the stage or the removal waiting on the folder; {@code failed: why} after a
 * download or a stage the registry refused, the whole why in the label's own tooltip; {@code installed v…}
 * where the folder carries the hub's record; {@code in addons/ by hand} where it carries none; the
 * out-of-date label where the item's {@code api_version} is not one this client implements; else empty. It
 * is cut to the room the name line leaves it, the whole of it one hover away where it is. The two facts
 * that are a folder on disk are read at build and at every rebuild ({@link #rescan}); the rest are the
 * registry's maps, read every tick.
 */
final class AddonCard extends Frame {
    /**
     * The card's own geometry, design pixels: the padding inside the box, the icon, the room between the
     * icon and the text, between the name and what follows it, and between one thing on a line and the
     * next, the room between the two lines, and the Install button's width.
     */
    static final int PAD = 4, ICON = 36, GAP = 8, AFTER = 6, SPACE = 12, LEAD = 2, BUTTON_W = 60;
    static final Color HOVER = new Color(255, 255, 255, 18);

    final Entry entry;
    private final BrowsePanel owner;
    private final HubImage icon;
    private final Label name, version, by, net, status, summary, meta;   // version, by, net: null where the item has none
    private final String summaryText;     // the summary whole, cut again when the room changes
    private Button install;               // in the tree exactly while it is offered
    private final String outdated;        // the label an Installed row would carry for this api_version, or null
    private boolean folder;               // addons/<id>/ exists -- a fact on disk, read by rescan()
    private String hub;                   // ...and the hub's record in it, or null for a by-hand folder
    private String tipped;                // the failure the status label's tooltip holds, or null
    private String statusText = "";       // the status as the registry last said it
    private final Fit fitStatus = new Fit(), fitSummary = new Fit();
    private boolean hover;

    /**
     * @param w the card's outer width, device pixels
     */
    AddonCard(BrowsePanel owner, Entry e, int w) {
        super(new Coord(w, height()), false, Window.wbox);
        this.owner = owner;
        this.entry = e;
        int px = UI.scale(ICON);
        icon = add(new HubImage(e.icon, new Coord(px, px), e.name), Coord.z);
        // Every label is added at Coord.z and placed by layout(): the client's shared zero is never written
        // to, only replaced -- a card assigns fresh Coords and mutates none.
        name = add(HubText.in(e.name, HubText.NAME, null), Coord.z);
        version = (e.version != null) ? add(HubText.muted("v" + e.version), Coord.z) : null;
        by = (e.by() != null) ? add(HubText.muted("by " + e.by()), Coord.z) : null;
        net = e.hosts.isEmpty() ? null : add(mark(e), Coord.z);
        status = add(new Label(""), Coord.z);
        String s = ((e.summary == null) || e.summary.trim().isEmpty()) ? null : e.summary.trim();
        summaryText = (s == null) ? "No summary yet." : s;
        summary = add((s == null) ? HubText.muted("") : new Label(""), Coord.z);
        String ago = HubText.ago(e.updatedAt);
        meta = add(HubText.muted(HubText.count(e.downloads, "download") + (ago.isEmpty() ? "" : " · updated " + ago)),
                   Coord.z);

        String lead, label;
        try {
            // The one decision the client makes about every manifest's api_version, made about the hub's
            // copy of it: ApiVersion.why is the sentence, ApiVersion.label the state. A field that is not a
            // version at all is refused by parse naming the form, and the card shows that instead.
            ApiVersion v = ApiVersion.parse(e.apiVersion);
            String why = ApiVersion.why(v);
            lead = (why != null) ? "Out of date: " + why : null;
            label = ApiVersion.label(v);
        } catch(IllegalArgumentException x) {
            lead = x.getMessage();
            label = "manifest error (hover)";
        }
        this.outdated = label;
        if(lead != null)
            name.settip(RichText.Parser.quote(lead), true);
        rescan();
        layout();
    }

    /** The height a card is born at: the frame, the padding, and the taller of the two stock lines and the icon. */
    static int height() {
        int lines = HubText.NAME.height() + UI.scale(LEAD) + Text.std.height();
        return Window.wbox.bisz().y + 2 * UI.scale(PAD) + Math.max(Math.max(lines, UI.scale(ICON)), Button.hs);
    }

    /**
     * The one mark the hub's own page puts on every item — {@code [net]}, in the hub's badge colour, with
     * the hosts as its tooltip. What it asks for beyond that is on its page, under <i>Permissions</i>, as
     * on the hub.
     */
    static Label mark(Entry e) {
        Label net = HubText.in("[net]", Text.std, HubText.NET);
        net.settip(RichText.Parser.quote("Reaches: " + String.join(", ", e.hosts)), true);
        return net;
    }

    /**
     * A one-line label's text, cut to its room: {@link #fit} writes {@code text} and cuts it at the end with
     * an ellipsis where it runs wider than {@code w}, a few renders at most, each keeping the share that
     * fitted — and remembers what it was cut for, so {@link #layout} asks again only when the text, the
     * room or the label's own size moved, the last being a sheet re-rendering the label under another face.
     * An Installed row ({@link AddonPanel}) cuts its cells with it too.
     */
    static final class Fit {
        String text;
        int w = -1, szx = -1;

        void fit(Label l, String text, int w) {
            if(text.equals(this.text) && (w == this.w) && (l.sz.x == szx))
                return;
            l.settext(text);
            String s = text;
            while((l.sz.x > w) && (s.length() > 1)) {
                int keep = (int)Math.min(s.length() - 1, ((long)s.length() * Math.max(0, w)) / l.sz.x);
                s = s.substring(0, Math.max(1, keep)).replaceAll("\\s+$", "");
                l.settext(s + "…");
            }
            this.text = text;
            this.w = w;
            this.szx = l.sz.x;
        }
    }

    /** Re-read the two facts on disk: the folder, and the hub's record in it. At build and at every rebuild. */
    void rescan() {
        folder = AddonRegistry.hasFolder(entry.id);
        hub = folder ? AddonRegistry.hubVersion(entry.id) : null;
        refresh();
    }

    /** The status and the button, from the registry's maps. */
    private void refresh() {
        int progress = AddonRegistry.downloading(entry.id);
        AddonRegistry.Pending p = AddonRegistry.pending(entry.id);
        String why = AddonRegistry.failed(entry.id);
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
        statusText = s;
        tipped = AddonPanel.failTip(status, why, tipped);
        // The button stands in the tree exactly while it is offered, as a row's does; layout() places it.
        if(button && (install == null)) {
            install = add(new Button(UI.scale(BUTTON_W), "Install", false).action(() -> AddonRegistry.install(entry)),
                          Coord.z);
        } else if(!button && (install != null)) {
            install.destroy();
            install = null;
        }
    }

    /**
     * Place everything from what it measures now, and be as tall as it needs. The icon stands at the left,
     * the button at the right, each centred on the card; between them the two lines, centred as a block:
     * the name line — the name, then the version, the author and the mark, each set <b>on the name's
     * baseline</b> beside it, and the status at its end — and under it the summary with the downloads and
     * the update at its end, on a baseline of their own. The two texts that run are cut to the room the
     * rest leaves them. The block is centred by what it <i>shows</i>, from the caps of the name to the
     * descenders of the second line, not by its rasters' boxes: a face's leading and the room for its
     * tallest ascender stand above the caps, and a block centred by its boxes sits low.
     *
     * <p><b>A Frame's children stand in its inner box</b>: {@code Frame.xlate} moves every child by
     * {@code box.btloff()} when it is drawn and when a click is routed, so {@code (0, 0)} here is the first
     * pixel inside the frame's edge, and the room this card places things in is {@code sz} less
     * {@code box.bisz()}. Adding the inset by hand as well put everything a frame's width down and to the
     * right of where it was measured — the right-aligned words under the frame, the block off its centre.
     */
    private void layout() {
        int pad = UI.scale(PAD), gap = UI.scale(GAP), after = UI.scale(AFTER), space = UI.scale(SPACE);
        int innerW = sz.x - box.bisz().x, innerH = sz.y - box.bisz().y;
        int left = pad + icon.sz.x + gap;
        int right = innerW - pad;
        int end = (install != null) ? right - install.sz.x - gap : right;

        // The name line: what follows the name runs from it; the status is cut to what is left after it;
        // the line's baseline is as far down as the deepest baseline on it, and its foot the deepest descent.
        Label[] line1 = {name, version, by, net};
        int x = left;
        for(Label l : line1) {
            if(l != null)
                x += l.sz.x + after;
        }
        fitStatus.fit(status, statusText, end - (x - after + space));
        int above1 = 0, below1 = 0;
        for(Label l : new Label[] {name, version, by, net, status}) {
            if(l != null) {
                int b = HubText.baseline(l);
                above1 = Math.max(above1, b);
                below1 = Math.max(below1, l.sz.y - b);
            }
        }
        // The second line: the downloads and the update at its end, the summary cut to the room before them.
        int metaX = end - meta.sz.x;
        fitSummary.fit(summary, summaryText, metaX - space - left);
        int above2 = 0, below2 = 0;
        for(Label l : new Label[] {summary, meta}) {
            int b = HubText.baseline(l);
            above2 = Math.max(above2, b);
            below2 = Math.max(below2, l.sz.y - b);
        }

        // The card is as tall as the lines, the icon or the button need. The block of lines is centred in
        // it by its ink -- the name's caps at the top, the second line's descent at the bottom.
        int lead = UI.scale(LEAD);
        int block = above1 + below1 + lead + above2 + below2;
        int inner = Math.max(Math.max(block, icon.sz.y), (install != null) ? install.sz.y : 0);
        int h = box.bisz().y + pad + inner + pad;
        if(h != sz.y) {
            resize(new Coord(sz.x, h));
            innerH = sz.y - box.bisz().y;
        }
        // The room above the caps in the name's raster, and under the descenders in the second line's: what
        // the block's boxes hold beyond what shows, left out of the centring.
        int over = Math.max(0, above1 - HubText.capHeight(name));
        int under = Math.max(0, below2 - HubText.descender(summary));
        int top = pad + (inner - (block - over - under)) / 2 - over;
        int base1 = top + above1, base2 = base1 + below1 + lead + above2;
        icon.c = new Coord(pad, (innerH - icon.sz.y) / 2);
        if(install != null)
            install.c = new Coord(right - install.sz.x, (innerH - install.sz.y) / 2);
        x = left;
        for(Label l : line1) {
            if(l != null) {
                l.c = new Coord(x, base1 - HubText.baseline(l));
                x += l.sz.x + after;
            }
        }
        status.c = new Coord(end - status.sz.x, base1 - HubText.baseline(status));
        summary.c = new Coord(left, base2 - HubText.baseline(summary));
        meta.c = new Coord(metaX, base2 - HubText.baseline(meta));
    }

    public void tick(double dt) {
        super.tick(dt);
        refresh();
        layout();
    }

    public void mousemove(MouseMoveEvent ev) {
        hover = ev.c.isect(Coord.z, sz);
        super.mousemove(ev);
    }

    public boolean mousedown(MouseDownEvent ev) {
        if(ev.propagate(this))      // the Install button, and the mark's tooltip, take theirs first
            return true;
        if(ev.b == 1) {
            owner.open(entry);
            return true;
        }
        return false;
    }

    public void draw(GOut g) {
        // The sheet's own surface first, where a rule names one, in the state the card is in -- so a `hover`
        // face in the rule's bg is worn under the pointer, as a button wears its own; the stock box paints
        // none. Then the hover lift; then, Frame's own draw, the contents and the frame over the lot -- the
        // order a self-painting panel composes in. The frame is skinbox()'s, stateless, as on every panel.
        Fonts.Chrome face = Fonts.chrome("panel", this, hover ? "hover" : null);
        if((face != null) && face.bg())
            face.drawbg(g, Coord.z, sz);
        if(hover) {
            g.chcolor(HOVER);
            g.frect(Coord.z, sz);
            g.chcolor();
        }
        super.draw(g);
    }
}
