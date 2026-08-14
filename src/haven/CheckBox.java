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

public class CheckBox extends ACheckBox {
    public static final Tex lbox = Resource.loadtex("gfx/hud/chkbox");
    public static final Tex lmark = Resource.loadtex("gfx/hud/chkmark");
    public static final Tex sbox = Resource.loadtex("gfx/hud/chkboxs");
    public static final Tex smark = Resource.loadtex("gfx/hud/chkmarks");
    public final Tex box, mark;
    public final Coord loff;
    // addon: public, not package-private (spec 040-ui-controls, task 040.4) -- LuaWidget's best-effort
    // :text() read matches Button's own public `text` field; see settext() below for the write half.
    public Text lbl;

    @RName("chk")
    public static class $_ implements Factory {
	public Widget create(UI ui, Object[] args) {
	    CheckBox ret = new CheckBox((String)args[0]);
	    ret.canactivate = true;
	    return(ret);
	}
    }

    public CheckBox(String lbl, boolean lg) {
	this.lbl = (lbl.length() > 0) ? Text.std.render(lbl, java.awt.Color.WHITE) : null;
	if(lg) {
	    box = lbox; mark = lmark;
	    loff = UI.scale(0, 6);
	} else {
	    box = sbox; mark = smark;
	    loff = UI.scale(5, 0);
	}
	if(this.lbl != null)
	    sz = Coord.of(box.sz().x + UI.scale(5) + this.lbl.sz().x, Math.max(box.sz().y, this.lbl.sz().y));
	else
	    sz = box.sz();
    }

    public CheckBox(String lbl) {
	this(lbl, false);
    }

    // addon: a live caption setter (spec 040-ui-controls, task 040.4) -- the constructor above bakes `lbl`/`sz`
    // once with no public way to touch either afterward, which hafen.ui():check():text(s) needs. Mirrors
    // Label.settext's shape: dispose the old raster, re-render, resize (which repacks the parent for free).
    public void settext(String s) {
	if(lbl != null)
	    lbl.dispose();
	lbl = (s.length() > 0) ? Text.std.render(s, java.awt.Color.WHITE) : null;
	Coord nsz = (lbl != null)
	    ? Coord.of(box.sz().x + UI.scale(5) + lbl.sz().x, Math.max(box.sz().y, lbl.sz().y))
	    : box.sz();
	resize(nsz);
    }

    public void draw(GOut g) {
	if(lbl != null)
	    g.image(lbl.tex(), loff.add(box.sz().x, (sz.y - lbl.sz().y) / 2));
        // addon: (065.10) the box and the tick are two keys, and each is asked for the state the box is in --
        // so a `checked` face is what a ticked box wears, and the mark, which is drawn in no other state,
        // always resolves that one. Null is the answer a stock client always gets, and then the two statics
        // are blitted at exactly the coordinates they always were. Neither is an SIWidget: nothing caches
        // this, so a changed rule lands on the next frame with nothing to invalidate.
        String st = state() ? "checked" : null;
        // addon: (065.17) what this box and its tick are made of. A checkbox is built large or small and the
        // two wear different art, so what the catalogue carries is the pair the LAST one drawn was wearing.
        Fonts.stock("checkbox", "bg", Fonts.piece(box));
        Fonts.stock("checkbox.mark", "bg", Fonts.piece(mark));
        Fonts.Chrome cb = Fonts.chrome("checkbox", this, st);
        Coord bc = Coord.z.add(0, (sz.y - box.sz().y) / 2);
        if(cb == null)
            g.image(box, bc);
        else
            cb.draw(g, bc, box.sz());
        if(state()) {
            Fonts.Chrome cm = Fonts.chrome("checkbox.mark", this, st);
            Coord mc = Coord.z.add(0, (sz.y - mark.sz().y) / 2);
            if(cm == null)
                g.image(mark, mc);
            else
                cm.draw(g, mc, mark.sz());
        }
        super.draw(g);
    }
    public boolean mousedown(MouseDownEvent ev) {
	if(ev.b == 1) {
	    // addon: 061 -- the Changed seam, where the client receives the CLICK, before its own click().
	    // The value is what the box is ABOUT to take, so cancelling means it never flipped.
	    if(AddonWidgets.activate(this, "Changed", AddonWidgets.checkValue(this)))
		click();
	    return(true);
	}
	return(super.mousedown(ev));
    }
}
