/*
 *  This file is part of the Haven & Hearth game client.
 *  Copyright (C) 2009 Fredrik Tolf <fredrik@dolda2000.com>, and
 *                     Björn Johannessen <johannessen.bjorn@gmail.com>
 *
 *  Redistribution and/or modification of this file is subject to the
 *  terms of the GNU Lesser General Public License, version 3, as
 *  published by the Free Software Foundation.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  Other parts of this source tree adhere to other copying
 *  rights. Please see the file `COPYING' in the root directory of the
 *  source tree for details.
 *
 *  A copy the GNU Lesser General Public License is distributed along
 *  with the source tree of which this file is a part in the file
 *  `doc/LPGL-3'. If it is missing for any reason, please see the Free
 *  Software Foundation's website at <http://www.fsf.org/>, or write
 *  to the Free Software Foundation, Inc., 59 Temple Place, Suite 330,
 *  Boston, MA 02111-1307 USA
 */

package haven;

import java.awt.*;
import java.awt.image.BufferedImage;

public class TextEntry extends Widget implements ReadLine.Owner {
    public static final Color defcol = new Color(255, 205, 109), dirtycol = new Color(255, 232, 209);
    public static final Color selcol = new Color(24, 80, 192);
    public static final Text.Foundry fnd = new Text.Foundry(Text.serif, 12).aa(true);
    // addon: "textentry" font scope (F3c, D-043). `fnd` above stays the STOCK foundry (other code may still use
    // it directly); the text a TextEntry renders itself goes through the provider instead:
    // Fonts.foundry("textentry", fnd) resolves an addon override, or -- when none -- the stock foundry, cascading
    // through "default". The resolved foundry is rebuilt lazily whenever Fonts.gen() moves, and each entry drops
    // its cached Text.Line on the same check (see draw(GOut)), so a live setFont restyles every field on screen.
    private static Text.Foundry efnd = null;
    private static int fontgen = -1;
    /** addon: the current foundry for the {@code "textentry"} scope (an override, else stock {@link #fnd}). */
    static Text.Foundry tfont() {
	int g = Fonts.gen();
	if((efnd == null) || (fontgen != g)) {
	    /* addon: (065.17) the face a field's text is set in, and the art it is painted on, which is one
	     * picture stretched across the field. Its FRAME is not said: the two end caps are pinned to the
	     * left and the right at their own size and are no nine-slice, so a `border` could not draw them. */
	    Fonts.stock("textentry", "font", fnd);
	    Fonts.stock("textentry", "bg", Fonts.piece(mext));
	    efnd = Fonts.foundry("textentry", fnd);
	    fontgen = g;
	}
	return(efnd);
    }
    private int tcgen = -1;    // addon: Fonts.gen() at the last tcache render
    public static final Tex lcap = Resource.loadtex("gfx/hud/text/l");
    public static final Tex rcap = Resource.loadtex("gfx/hud/text/r");
    public static final Tex mext = Resource.loadtex("gfx/hud/text/m");
    public static final Tex caret = Resource.loadtex("gfx/hud/text/caret");
    public static final int toffx = lcap.sz().x;
    public static final Coord coff = UI.scale(new Coord(-2, 0));
    public static final int wmarg = lcap.sz().x + rcap.sz().x + UI.scale(1);
    /* addon: (065.9) the "textentry" chrome -- what a field is painted ON and framed WITH. The four textures
     * above stay the STOCK value and split three ways: `mext` is the surface, `lcap`/`rcap` are the frame, and
     * `caret` is neither, so it is never the rule's -- it marks where you type rather than what the field is
     * made of, and is drawn over whichever of the two painted. Each half is skipped only where the rule
     * answers for it, so a bg-only rule keeps the stock end caps and a border-only rule the stock field. */
    private Fonts.Chrome face() {
	return(Fonts.chrome("textentry", this));
    }

    /**
     * addon: (065.9) the height a field is <b>built</b> at — its own background's, which is what it has always
     * been. A rule's {@code bg} is a background too, so a field built while one is installed measures from
     * that art instead of from {@link #mext}, and a taller art makes a taller field. A flat colour has no size
     * of its own to give and leaves the stock height alone.
     *
     * <p>Asked <b>without</b> a widget, because there is none yet where it is asked — {@code super(…)}, before
     * this field exists — so the site rule and the {@code "*"} cascade are what answer, never a tree rule.
     * Nothing re-measures a field afterwards: a control's box is decided when it is built, exactly as every
     * other one in the client is.
     */
    public static int bgheight() {
	Coord n = Fonts.chromesz("textentry");
	return((n == null) ? mext.sz().y : n.y);
    }

    /* addon: (065.9) where the text starts -- the left cap the client has always inset it by, plus the room
     * the rule's padding asks for. The click-to-character maps read the same number, so a padded field still
     * puts the caret under the glyph that was pointed at. */
    private static int toff(Coord[] pad) {
	return((pad == null) ? toffx : (toffx + pad[0].x));
    }

    private int toff() {
	return(toff(Fonts.chromepad("textentry", this)));
    }

    public boolean dshow = false;
    public ReadLine buf;
    public int sx;
    public boolean pw = false;
    private boolean dirty = false;
    private double focusstart;
    private Text.Line tcache = null;
    private UI.Grab d = null;

    @RName("text")
    public static class $_ implements Factory {
	public Widget create(UI ui, Object[] args) {
	    return(new TextEntry(UI.scale(Utils.iv(args[0])), (String)args[1]));
	}
    }

    public void settext(String text) {
	buf.setline(text);
	redraw();
    }

    public void rsettext(String text) {
	buf = ReadLine.make(this, text);
	redraw();
    }

    public void commit() {
	dirty = false;
	redraw();
    }

    public void uimsg(String name, Object... args) {
	if(name == "settext") {
	    settext((String)args[0]);
	} else if(name == "sel") {
	    if(args.length == 0) {
		buf.select(0, buf.length());
	    } else {
		int f = (args[0] == null) ? buf.length() : Utils.clip(Utils.iv(args[0]), 0, buf.length());
		int t = (args[1] == null) ? buf.length() : Utils.clip(Utils.iv(args[1]), 0, buf.length());
		buf.select(f, t);
	    }
	} else if(name == "get") {
	    wdgmsg("text", buf.line());
	} else if(name == "pw") {
	    pw = Utils.bv(args[0]);
	} else if(name == "dshow") {
	    dshow = Utils.bv(args[0]);
	} else if(name == "cmt") {
	    commit();
	} else {
	    super.uimsg(name, args);
	}
    }

    protected String dtext() {
	if(pw) {
	    char[] dp = new char[buf.length()];
	    java.util.Arrays.fill(dp, '\u2022');
	    return(new String(dp));
	} else {
	    return(buf.line());
	}
    }

    protected void redraw() {
	if(tcache != null) {
	    tcache.tex().dispose();
	    tcache = null;
	}
    }

    public void draw(GOut g) {
	if((this.tcache != null) && (tcgen != Fonts.gen()))   // addon: the "textentry" override moved -- re-render (F3c)
	    redraw();
	Text.Line tcache = this.tcache;
	if(tcache == null) {
	    /* addon: (102.2) a field declares "textentry", which is the one scope Fonts.display refuses outright --
	     * an entry written under "*" included. Without the pair the render would resolve under "default" and a
	     * "*" catalogue would rewrite a word as the user typed it, which is the whole reason the key does not
	     * exist. The style half is unaffected: tfont() is already the provider's product for this scope. */
	    Fonts.enter("textentry");
	    try {
		this.tcache = tcache = tfont().render(dtext(), (dshow && dirty) ? dirtycol : defcol);   // addon: was `fnd.render(...)`
	    } finally {
		Fonts.exit();
	    }
	    this.tcgen = Fonts.gen();   // addon:
	}
	// addon: (065.9) the rule's own surface and frame, and the room its padding keeps between the two and
	// the text. A field's own box is the width its caller asked for, so the room comes out of that width
	// and the text moves in -- the client goes on owning how wide a field is, the rule where its text sits.
	Fonts.Chrome face = face();
	Coord[] pad = Fonts.chromepad("textentry", this);
	int toff = toff(pad);
	int ty = (pad == null) ? 0 : pad[0].y;
	int th = sz.y - ty - ((pad == null) ? 0 : pad[1].y);
	int point = buf.point(), mark = buf.mark();
	if((face != null) && face.bg())
	    face.drawbg(g, Coord.z, sz);
	else
	    g.image(mext, Coord.z, sz);
	if(mark >= 0) {
	    int px = tcache.advance(point) - sx, mx = tcache.advance(mark) - sx;
	    g.chcolor(selcol);
	    g.frect2(Coord.of(Math.min(px, mx) + toff, ty + ((th - tcache.sz().y) / 2)),
		     Coord.of(Math.max(px, mx) + toff, ty + ((th + tcache.sz().y) / 2)));
	    g.chcolor();
	}
	g.image(tcache.tex(), Coord.of(toff - sx, ty + ((th - tcache.sz().y) / 2)));
	if((face != null) && face.border()) {
	    face.drawborder(g, Coord.z, sz);
	} else {
	    g.image(lcap, Coord.z);
	    g.image(rcap, Coord.of(sz.x - rcap.sz().x, 0));
	}
	if(hasfocus) {
	    int cx = tcache.advance(point);
	    int room = sz.x - wmarg - ((pad == null) ? 0 : (pad[0].x + pad[1].x));   // addon: (065.9)
	    if(cx < sx) {sx = cx;}
	    if(cx > sx + room) {sx = cx - room;}
	    int lx = cx - sx;
	    if(((Utils.rtime() - Math.max(focusstart, buf.mtime())) % 1.0) < 0.5)
		g.image(caret, coff.add(toff + lx, ty + ((th - tcache.img.getHeight()) / 2)));
	}
    }

    public TextEntry(int w, String deftext) {
	super(new Coord(w, bgheight()));   // addon: (065.9) was `mext.sz().y` -- a field measures from its bg
	rsettext(deftext);
	setcanfocus(true);
    }

    protected void changed() {
	dirty = true;
    }

    public void activate(String text) {
	if(canactivate)
	    wdgmsg("activate", text);
    }

    public void done(ReadLine buf) {
	// addon: 061 -- the Submitted seam, where the client RECEIVES the Enter. NOT activate(String): that one
	// is public and ChatUI's own entry overrides it without calling super, so a seam there would be silent
	// on the one text entry every player types into. Cancelling here means the server hears nothing, and a
	// resend calls activate VIRTUALLY, so that override is what sends the line.
	String line = buf.line();
	if(AddonWidgets.activate(this, "Submitted", line))
	    activate(line);
    }

    public void changed(ReadLine buf) {
	redraw();
	TextEntry.this.changed();
    }

    public boolean gkeytype(GlobKeyEvent ev) {
	// addon: 061 -- the Submitted seam, the keybinding's half of done() above and for the same reason
	String line = buf.line();
	if(AddonWidgets.activate(this, "Submitted", line))
	    activate(line);
	return(true);
    }

    public boolean keydown(KeyDownEvent e) {
	return(buf.key(e.awt));
    }

    public void mousemove(MouseMoveEvent ev) {
	if((d != null) && (tcache != null)) {
	    int p = tcache.charat(ev.c.x + sx - toff());   // addon: (065.9) the padded offset the draw used
	    if(buf.mark() < 0)
		buf.mark(buf.point());
	    buf.point(p);
	}
    }

    public boolean mousedown(MouseDownEvent ev) {
	parent.setfocus(this);
	if((ev.b == 1) && (tcache != null)) {
	    buf.point(tcache.charat(ev.c.x + sx - toff()));   // addon: (065.9)
	    buf.mark(-1);
	    d = ui.grabmouse(this);
	}
	return(true);
    }

    public boolean mouseup(MouseUpEvent ev) {
	if((ev.b == 1) && (d != null)) {
	    d.remove();
	    d = null;
	    return(true);
	}
	return(false);
    }

    public void gotfocus() {
	focusstart = Utils.rtime();
    }

    public void resize(int w) {
	resize(w, sz.y);
	redraw();
    }

    public String text() {
	return(buf.line());
    }
}
