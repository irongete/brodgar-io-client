package io.brodgar.addon.ui;

import haven.Coord;
import haven.GOut;
import haven.Label;
import haven.Tex;
import haven.TexI;
import haven.Text;
import haven.Widget;

import io.brodgar.addon.registry.Registry;

import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;

/**
 * <b>A picture the hub serves</b>, in a box of the caller's: an item's icon on a card and on its page, or
 * one screenshot of the gallery. The fetch is {@link Registry#image}, on the hub client's picture worker
 * and kept there, and this widget <b>polls</b> it from {@link #tick} like every other answer the panel
 * reads: nothing calls back into the tree. Until the picture is in — or when there is none, or the fetch
 * failed — the box shows what the hub's own page shows in its place: a plain field with the first letter
 * of the name, for an icon; nothing but the field, for a screenshot. The picture is drawn centred in the
 * box at the size the worker scaled it to, which is the box or smaller, never stretched.
 *
 * <p>The texture is this widget's own and goes with it ({@link #dispose}); the decoded image stays in the
 * hub client's cache, so a card rebuilt for the same item costs no fetch.
 */
final class HubImage extends Widget {
    /** The field an icon-less item shows, and the letter on it — the hub page's own placeholder. */
    static final Color FIELD = new Color(0, 0, 0, 96), LETTER = new Color(200, 200, 200);

    private final String url;                 // or null: nothing to fetch, the placeholder alone
    private final Coord fit;                  // the most the picture may be, device pixels
    private Registry.Request<BufferedImage> req;
    private Tex tex;
    private final Label letter;               // the placeholder's letter, or null for a screenshot

    /**
     * @param url    the picture's URL, or {@code null} for an item that has none
     * @param sz     the box, in device pixels
     * @param letter what to show while there is no picture: the name whose first letter the placeholder
     *               carries, or {@code null} for a bare field
     */
    HubImage(String url, Coord sz, String letter) {
        super(sz);
        this.url = ((url == null) || url.isEmpty()) ? null : url;
        this.fit = this.sz;
        if((letter != null) && !letter.isEmpty()) {
            // The letter is sized to the box, as the hub's page sizes its own: 20 px on a 48 px icon, 30 on 72.
            // The box is device pixels already, so the face is derived at its size rather than scaled again.
            Text.Foundry f = new Text.Foundry(Text.sans.deriveFont(Font.BOLD, (float)Math.max(10, sz.y * 5 / 12))).aa(true);
            Label l = new Label(letter.substring(0, 1).toUpperCase(), f);
            l.setcolor(LETTER);
            this.letter = add(l, this.sz.sub(l.sz).div(2));
        } else {
            this.letter = null;
        }
        if(this.url != null)
            req = Registry.image(this.url, fit);
    }

    /** Whether a picture is on screen, as opposed to the placeholder. */
    boolean shown() {
        return tex != null;
    }

    public void tick(double dt) {
        super.tick(dt);
        if((req != null) && req.done()) {
            BufferedImage img = req.result();
            req = null;
            if(img != null) {
                tex = new TexI(img);
                if(letter != null)
                    letter.hide();
            }
        }
    }

    public void draw(GOut g) {
        if(tex == null) {
            g.chcolor(FIELD);
            g.frect(Coord.z, sz);
            g.chcolor();
        } else {
            g.image(tex, sz.sub(tex.sz()).div(2));
        }
        super.draw(g);
    }

    public void dispose() {
        super.dispose();
        if(tex != null) {
            tex.dispose();
            tex = null;
        }
    }
}
