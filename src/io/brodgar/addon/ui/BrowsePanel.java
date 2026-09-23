package io.brodgar.addon.ui;

import haven.Button;
import haven.CharWnd;
import haven.Coord;
import haven.Label;
import haven.SDropBox;
import haven.SListWidget;
import haven.Scrollport;
import haven.Text;
import haven.TextEntry;
import haven.UI;
import haven.Widget;

import io.brodgar.addon.AddonManager;
import io.brodgar.addon.registry.Entry;
import io.brodgar.addon.registry.Registry;

import java.awt.Color;
import java.awt.Font;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * <b>The Browse tab</b> — the hub's own front page, drawn with the client's widgets: a search field with the
 * order beside it, the tags as a row of chips, one {@link AddonCard} per addon in a scrolling list, and on the
 * manager's own bottom line, right-aligned beside its <b>Back</b>, a line saying which of how many and
 * <b>Previous</b> / <b>Next</b> ({@link #pagerBar}, which {@link AddonPanel} places there). A card opens the addon's page,
 * {@link AddonDetail}, in the same box; <b>Back to list</b> on the page puts the list back exactly as it
 * was — the field, the page and the scroll all kept, the list being hidden rather than rebuilt.
 *
 * <p>Everything the hub answers is read from {@link #tick}, never called back: the page in flight
 * ({@link Registry#page}), the tags ({@link Registry#meta}, asked once and kept for the client's life), and
 * on the page its own detail and pictures. Only the latest page request is ever read, so a late answer to
 * an earlier search is dropped. The field is polled rather than hooked: a text that moved is searched once
 * it has been still for {@link AddonPanel#SEARCH_STILL}, or at once on Enter, whether the user typed it,
 * pasted it or an addon wrote it. The first page is asked for the moment the tab first comes on screen —
 * everything the hub publishes, most downloaded first, as its front page shows — and again on every change
 * of the field, the tag, the order or the page.
 *
 * <p>{@link #rescan} is the reload's word: every card and the open page re-read the two facts that are a
 * folder on disk, because a reload is the moment {@code addons/} may have changed.
 */
final class BrowsePanel extends Widget {
    /** The tab's box, design pixels: as wide as the Installed list, and as tall as that tab is with its controls. */
    static final int W = AddonPanel.LIST_W, H = 400;
    /** How many addons a page holds — the hub's own front page asks for 24. */
    static final int LIMIT = 24;
    /** The order picker's width, and the pager's two buttons. */
    static final int SORT_W = 170, PREV_W = 80, NEXT_W = 60;
    /** Where the pager's strip starts on the manager's bottom line: past Back (200 wide) and a gap. */
    static final int PAGER_X = 208;
    /** The room around a card, design pixels: between its frame and the port's edges, and between one card and the next. */
    static final int MARGIN = 2, ROW_GAP = 4;
    /** The orders, as the picker names them — one per {@link Registry#SORTS}, in the same order. */
    static final List<String> ORDERS = Collections.unmodifiableList(Arrays.asList(
        "Relevance", "Most downloaded", "Recently updated", "Name"));
    /** The caption colour of the chip that is on -- the one state a button shows. */
    static final Color CHIP_ON = new Color(255, 224, 128);

    private final Widget listing;            // the front page: hidden while a detail is open
    private final TextEntry field;
    private final SDropBox<String, Widget> order;
    private final Chips chips;
    private final Label count, pageLbl;
    private final Button prev, next;
    private final Widget pagerBar;           // the pager's own strip: the manager places it on Back's line
    private final Scrollport port;
    private final List<AddonCard> cards = new ArrayList<AddonCard>();
    private Label notice;                    // the port's own word while it holds no cards, or null
    private AddonDetail detail;              // the page open over the list, or null
    // -- the search
    private String q = "", tag = "";
    private int sort = 0, page = 1;
    private String lastText = "";            // the field's text as tick last saw it
    private double still = 0;                // how long it has been that, in seconds
    private boolean searchDue = false;       // the text moved and no search has gone out for it yet
    private Registry.Request<Registry.Page> pending;   // the latest request, the only one ever read
    private boolean shown = false;           // the tab has been on screen once: the first page was asked for
    // -- the tags
    private static volatile List<String> tags;         // the hub's, once read; kept for the client's life
    private Registry.Request<Registry.Meta> meta;

    BrowsePanel() {
        super(UI.scale(new Coord(W, H)));
        listing = add(new Widget(sz), Coord.z);
        Widget prevw = listing.add(new Label("Search the addons published at brodgar.io/addons: a name, an author, a tag."),
                                   Coord.z);
        // The field is an anonymous subclass so that its class name stays TextEntry for a selector, and so
        // that Enter -- which the client delivers to activate(String) through done()/gkeytype -- searches at
        // once. Everything else about it is read by tick: text() polled, searched when still.
        field = listing.add(new TextEntry(UI.scale(W - SORT_W - 8), "") {
                public void activate(String text) {
                    lastText = text;
                    typed(text, true);
                }
            }, prevw.pos("bl").adds(0, 4));
        // The order, the picker the client's own Options use (the camera's): the closed box names the order
        // in force, the list drops under it. The row height is the item font's own, as its rows render with it.
        order = listing.add(new SDropBox<String, Widget>(UI.scale(SORT_W), UI.scale(120), CharWnd.attrfont().height()) {
                protected List<String> items() {return ORDERS;}
                protected Widget makeitem(String nm, int idx, Coord sz) {
                    return SListWidget.TextItem.of(sz, () -> (nm == null) ? "" : nm);
                }
                public void change(String nm) {
                    boolean same = (nm == this.sel);
                    super.change(nm);
                    if((nm == null) || same)
                        return;
                    sort = ORDERS.indexOf(nm);
                    page = 1;
                    if(shown)               // the first pick is the build's own, before the tab has shown
                        load();
                }
            }, new Coord(field.c.x + field.sz.x + UI.scale(8), field.c.y));
        order.change(ORDERS.get(0));
        // The two stand on one line whatever their heights: the shorter is centred on the taller.
        int rowh = Math.max(field.sz.y, order.sz.y);
        field.c = new Coord(field.c.x, field.c.y + (rowh - field.sz.y) / 2);
        order.c = new Coord(order.c.x, order.c.y + (rowh - order.sz.y) / 2);
        int y = prevw.pos("bl").adds(0, 4).y + rowh + UI.scale(6);
        chips = listing.add(new Chips(), new Coord(0, y));
        chips.set(tags);
        // The pager is built here, where its state lives, but in a strip of its own that the manager adds on
        // its bottom line: as wide as the tab less Back and its gap, so it never stands over Back's clicks.
        pagerBar = new Widget(new Coord(UI.scale(W - PAGER_X), Button.hs));
        count = pagerBar.add(new Label(""), Coord.z);
        next = pagerBar.add(new Button(UI.scale(NEXT_W), "Next", false).action(() -> turn(1)), Coord.z);
        pageLbl = pagerBar.add(new Label(""), Coord.z);
        prev = pagerBar.add(new Button(UI.scale(PREV_W), "Previous", false).action(() -> turn(-1)), Coord.z);
        port = listing.add(new Scrollport(UI.scale(new Coord(W, 100))), Coord.z);
        bar(null);
        layout();
    }

    /** The pager's strip, for the manager to place on its bottom line, flush with the tab's right edge. */
    Widget pagerBar() {
        return(pagerBar);
    }

    /** The list is on screen, not an addon's page over it: the one time the pager stands. */
    boolean listing() {
        return(detail == null);
    }

    /**
     * Place the port under the chips, which are as tall as the tags need: a hub with more tags than fit a row
     * wraps them, and the port is what gives the room. Run at build and whenever the chips change.
     */
    private void layout() {
        int y = chips.c.y + chips.sz.y + UI.scale(6);
        port.c = new Coord(0, y);
        port.resize(new Coord(sz.x, sz.y - y));
        port.cont.update();                 // the bar's range follows the box, which resize alone does not move
        if(notice != null)
            notice.c = port.cont.sz.sub(notice.sz).div(2);
    }

    // ------------------------------------------------------------- the search

    public void tick(double dt) {
        super.tick(dt);
        if(!shown && tvisible()) {          // the tab's first moment on screen: the front page, and the tags
            shown = true;
            if(tags == null)
                meta = Registry.meta();
            load();
        }
        pollMeta();
        pollSearch(dt);
        // Every tick, from what the widgets measure now: a sheet re-renders a label at another size when it
        // is drawn, and a card grows with its own -- so the pager keeps its right edge and the cards their
        // stack whatever face the labels wear this frame. A handful of comparisons.
        pager();
        stack();
    }

    /** The pager, right-aligned in its strip: the labels' widths place the buttons and the line before them. */
    private void pager() {
        int barh = pagerBar.sz.y;
        Coord nc = new Coord(pagerBar.sz.x - next.sz.x, (barh - next.sz.y) / 2);
        if(!nc.equals(next.c))
            next.c = nc;
        Coord pc = new Coord(nc.x - UI.scale(8) - pageLbl.sz.x, (barh - pageLbl.sz.y) / 2);
        if(!pc.equals(pageLbl.c))
            pageLbl.c = pc;
        Coord vc = new Coord(pc.x - UI.scale(8) - prev.sz.x, (barh - prev.sz.y) / 2);
        if(!vc.equals(prev.c))
            prev.c = vc;
        Coord cc = new Coord(vc.x - UI.scale(8) - count.sz.x, (barh - count.sz.y) / 2);
        if(!cc.equals(count.c))
            count.c = cc;
    }

    /** The cards under one another, each as tall as it is now; the bar's range follows when one moved. */
    private void stack() {
        int m = UI.scale(MARGIN), gap = UI.scale(ROW_GAP), y = m;
        boolean moved = false;
        for(AddonCard c : cards) {
            if((c.c.x != m) || (c.c.y != y)) {
                c.c = new Coord(m, y);
                moved = true;
            }
            y += c.sz.y + gap;
        }
        if(moved)
            port.cont.update();
    }

    /** The tags, once the hub has answered: the chips are rebuilt and the rest laid out under them. */
    private void pollMeta() {
        if((meta == null) || !meta.done())
            return;
        Registry.Request<Registry.Meta> r = meta;
        meta = null;
        if(r.error() != null) {
            AddonManager.log("registry: the tags were not read: " + r.error());
            return;
        }
        tags = r.result().tags;
        chips.set(tags);
        layout();
    }

    /**
     * The search field, read every frame: a text that moved is searched once it has been still for
     * {@link AddonPanel#SEARCH_STILL}. Then the request in flight, if it has ended: its answer becomes the
     * cards, or its error the port's own line.
     */
    private void pollSearch(double dt) {
        String t = field.text();
        if(!t.equals(lastText)) {
            lastText = t;
            still = 0;
            searchDue = true;
        } else if(searchDue) {
            still += dt;
            if(still >= AddonPanel.SEARCH_STILL)
                typed(t, false);
        }
        if((pending != null) && pending.done()) {
            Registry.Request<Registry.Page> r = pending;
            pending = null;
            if(r.error() != null)
                notice("The hub did not answer: " + r.error());
            else
                show(r.result());
        }
    }

    /**
     * The field's text is the query now; a new query starts at page one. A text that is the query already
     * is nothing -- except on Enter ({@code force}), which asks again: the one gesture that retries a hub
     * that did not answer.
     */
    private void typed(String text, boolean force) {
        searchDue = false;
        still = 0;
        String nq = text.trim();
        if(nq.equals(q) && !force)
            return;
        q = nq;
        page = 1;
        load();
    }

    /** The tag chip pressed — the one on, or none — from the strip, or from an addon's page. */
    void filter(String t) {
        tag = (t == null) ? "" : t;
        page = 1;
        chips.select(tag);
        load();
        back();
    }

    /** One page on, or back; the buttons are greyed at either end, so this is never asked past them. */
    private void turn(int by) {
        page = Math.max(1, page + by);
        load();
    }

    /**
     * Ask the hub for the page the state names, now. A request still in flight is cancelled first: its
     * answer is nobody's now, and a hub that is slow or down would otherwise hold the one worker for the
     * rest of that request's timeout before this one went out.
     */
    private void load() {
        if(pending != null) {
            pending.cancel();
            pending = null;
        }
        notice("Searching…");
        pending = Registry.page(q, tag, Registry.SORTS[sort], page, LIMIT);
    }

    /** The cards for an answer, in the hub's order, and the line and the pager for it. */
    private void show(Registry.Page p) {
        clear();
        if(p.items.isEmpty()) {
            notice((q.isEmpty() && tag.isEmpty()) ? "Nothing published yet." : "No addon matches.");
            bar(p);
            return;
        }
        int m = UI.scale(MARGIN), w = port.cont.sz.x - 2 * m;
        for(Entry e : p.items)
            cards.add(port.cont.add(new AddonCard(this, e, w), Coord.z));
        stack();
        bar(p);
    }

    /** The line and the pager: which of how many, and whether there is a page either side. */
    private void bar(Registry.Page p) {
        if((p == null) || p.items.isEmpty()) {
            count.settext("");
            pageLbl.settext("");
            prev.disable(true);
            next.disable(true);
        } else {
            int from = (p.page - 1) * p.limit + 1, to = from + p.items.size() - 1;
            count.settext(from + "–" + to + " of " + HubText.count(p.total, "addon"));
            pageLbl.settext("page " + p.page + " of " + p.pages());
            prev.disable(p.page <= 1);
            next.disable(p.page >= p.pages());
        }
        pager();
    }

    /** The port's own word while it holds no cards, centred: searching, nothing, or why the hub did not answer. */
    private void notice(String text) {
        clear();
        notice = port.cont.add(new Label(text, port.cont.sz.x - UI.scale(40)), Coord.z);
        notice.c = port.cont.sz.sub(notice.sz).div(2);
    }

    /** The cards and the notice go, and the list is back at its top: a shorter answer under an old scroll offset would draw blank. */
    private void clear() {
        for(AddonCard c : cards)
            c.destroy();
        cards.clear();
        if(notice != null) {
            notice.destroy();
            notice = null;
        }
        port.bar.val = 0;                                // the fields, not bar.ch(): a refill is no Changed of anyone's
        port.cont.sy = 0;
    }

    // ------------------------------------------------------------- the page

    /** Open the addon's page over the list. */
    void open(Entry e) {
        if(detail != null)
            detail.destroy();
        listing.hide();
        detail = add(new AddonDetail(this, e), Coord.z);
    }

    /** Back to the list, as it was. */
    void back() {
        if(detail != null) {
            detail.destroy();
            detail = null;
        }
        listing.show();
    }

    /** The reload's word: every card and the open page re-read what is on disk. */
    void rescan() {
        for(AddonCard c : cards)
            c.rescan();
        if(detail != null)
            detail.rescan();
    }

    // ------------------------------------------------------------- the chips

    /**
     * The tags as a row of chips — <b>All</b> first, then the hub's, in its order — each a button, the one
     * on drawn with its caption in colour. They wrap when a row is full, and the strip is as tall as the
     * rows it took; nothing here knows the tags before the hub has said them, so the strip is rebuilt on
     * {@link #set} and its owner lays the rest out under it.
     */
    private final class Chips extends Widget {
        /** The caption face a chip is measured with: the client's own button caption, bold serif 12. */
        private final Text.Foundry capf = new Text.Foundry(Text.serif.deriveFont(Font.BOLD, UI.scale(12f))).aa(true);
        private final List<Button> buttons = new ArrayList<Button>();
        private final List<String> names = new ArrayList<String>();

        Chips() {
            super(new Coord(UI.scale(W), Button.hs));
        }

        void set(List<String> tags) {
            for(Button b : buttons)
                b.destroy();
            buttons.clear();
            names.clear();
            names.add("");
            if(tags != null)
                names.addAll(tags);
            int x = 0, y = 0, gap = UI.scale(4);
            for(final String t : names) {
                String cap = t.isEmpty() ? "All" : t;
                int w = capf.strsize(cap).x + UI.scale(20);
                if((x > 0) && (x + w > sz.x)) {
                    x = 0;
                    y += Button.hs + gap;
                }
                Button b = add(new Button(w, cap, false).action(() -> filter(t.equals(tag) ? "" : t)), new Coord(x, y));
                buttons.add(b);
                x += w + gap;
            }
            resize(new Coord(sz.x, y + Button.hs));
            select(tag);
        }

        /** Mark the chip of {@code t} on, every other off — by caption colour, the one state a button shows. */
        void select(String t) {
            for(int i = 0; i < buttons.size(); i++) {
                String n = names.get(i), cap = n.isEmpty() ? "All" : n;
                if(n.equals(t))
                    buttons.get(i).change(cap, CHIP_ON);
                else
                    buttons.get(i).change(cap);
            }
        }
    }
}
