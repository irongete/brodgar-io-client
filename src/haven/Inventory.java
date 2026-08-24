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

import java.util.*;
import java.awt.image.WritableRaster;

public class Inventory extends Widget implements DTarget {
    public static final Coord sqsz = UI.scale(new Coord(32, 32)).add(1, 1);
    public static final Tex invsq;
    public boolean dropul = true;
    public Coord isz;
    public boolean[] sqmask = null;
    Map<GItem, WItem> wmap = new HashMap<GItem, WItem>();

    static {
	Coord sz = sqsz.add(1, 1);
	WritableRaster buf = PUtils.imgraster(sz);
	for(int i = 1, y = sz.y - 1; i < sz.x - 1; i++) {
	    buf.setSample(i, 0, 0, 20); buf.setSample(i, 0, 1, 28); buf.setSample(i, 0, 2, 21); buf.setSample(i, 0, 3, 167);
	    buf.setSample(i, y, 0, 20); buf.setSample(i, y, 1, 28); buf.setSample(i, y, 2, 21); buf.setSample(i, y, 3, 167);
	}
	for(int i = 1, x = sz.x - 1; i < sz.y - 1; i++) {
	    buf.setSample(0, i, 0, 20); buf.setSample(0, i, 1, 28); buf.setSample(0, i, 2, 21); buf.setSample(0, i, 3, 167);
	    buf.setSample(x, i, 0, 20); buf.setSample(x, i, 1, 28); buf.setSample(x, i, 2, 21); buf.setSample(x, i, 3, 167);
	}
	for(int y = 1; y < sz.y - 1; y++) {
	    for(int x = 1; x < sz.x - 1; x++) {
		buf.setSample(x, y, 0, 36); buf.setSample(x, y, 1, 52); buf.setSample(x, y, 2, 38); buf.setSample(x, y, 3, 125);
	    }
	}
	invsq = new TexI(PUtils.rasterimg(buf));
    }

    @RName("inv")
    public static class $_ implements Factory {
	public Widget create(UI ui, Object[] args) {
	    return(new Inventory((Coord)args[0]));
	}
    }

    public void draw(GOut g) {
	Coord c = new Coord();
	int mo = 0;
	// addon: (065.7) the empty square is the "inventory.slot" rule's -- the SAME box at every cell, so the
	// rule is resolved once here and painted per square rather than resolved per square. Null is the
	// answer a stock client always gets, and then every cell is the static raster at the coordinate it
	// always had. The mask's dimming brackets whichever of the two paints, exactly as it always did.
	Fonts.Chrome sq = Fonts.chrome("inventory.slot", this);
	stocksq();   // addon: (065.17) the two colours the raster above is built out of, said as a rule says them
	for(c.y = 0; c.y < isz.y; c.y++) {
	    for(c.x = 0; c.x < isz.x; c.x++) {
		if((sqmask != null) && sqmask[mo++]) {
		    g.chcolor(64, 64, 64, 255);
		    drawsq(g, sq, c.mul(sqsz));
		    g.chcolor();
		} else {
		    drawsq(g, sq, c.mul(sqsz));
		}
	    }
	}
	super.draw(g);
    }

    /* addon: (065.17) what an empty square is made of. This surface is built pixel by pixel above rather than
     * loaded from a resource, so there is no art to name -- and none is needed: the two samples the loop sets
     * ARE a fill and a one-pixel outline, which is a flat bg and a line border said the long way round. */
    private static final java.awt.Color sqbg = new java.awt.Color(36, 52, 38, 125);
    private static final java.awt.Color sqbd = new java.awt.Color(20, 28, 21, 167);
    /* addon: (106) ...and the ACTION menu's grid is paved with this very raster, so it declares itself
     * through here under its OWN scope rather than repeating the two colours. The code that BUILDS a surface
     * is the only thing that knows what it is made of, so a second site drawing this one names its key and
     * asks this method -- and a change to the raster still moves exactly one declaration. */
    static void stocksq(String scope) {
	Fonts.stock(scope, "bg", Fonts.piece(sqbg));
	Fonts.stock(scope, "border", Fonts.piece(sqbd).width(1));
    }
    private static void stocksq() {
	stocksq("inventory.slot");
    }

    /* addon: (065.7) one square, the rule's or the client's own, at the size the client's own always was --
     * so a themed grid keeps its stock pitch and every item icon stays where it is. */
    private static void drawsq(GOut g, Fonts.Chrome sq, Coord c) {
	if(sq == null)
	    g.image(invsq, c);
	else
	    sq.draw(g, c, invsq.sz());
    }
	
    public Inventory(Coord sz) {
	super(sqsz.mul(sz).add(1, 1));
	isz = sz;
    }
    
    public boolean mousewheel(MouseWheelEvent ev) {
	if(ui.modshift) {
	    Inventory minv = getparent(GameUI.class).maininv;
	    if(minv != this) {
		if(ev.a < 0)
		    wdgmsg("invxf", minv.wdgid(), 1);
		else if(ev.a > 0)
		    minv.wdgmsg("invxf", this.wdgid(), 1);
	    }
	}
	return(true);
    }
    
    public void addchild(Widget child, Object... args) {
	add(child);
	Coord c = (Coord)args[0];
	if(child instanceof GItem) {
	    GItem i = (GItem)child;
	    wmap.put(i, add(new WItem(i), c.mul(sqsz).add(1, 1)));
	}
    }
    
    public void cdestroy(Widget w) {
	super.cdestroy(w);
	if(w instanceof GItem) {
	    GItem i = (GItem)w;
	    ui.destroy(wmap.remove(i));
	}
    }
    
    public boolean drop(Coord cc, Coord ul) {
	Coord dc;
	if(dropul)
	    dc = ul.add(sqsz.div(2)).div(sqsz);
	else
	    dc = cc.div(sqsz);
	wdgmsg("drop", dc);
	return(true);
    }
	
    public boolean iteminteract(Coord cc, Coord ul) {
	return(false);
    }
	
    public void uimsg(String msg, Object... args) {
	if(msg == "sz") {
	    isz = (Coord)args[0];
	    resize(invsq.sz().add(UI.scale(new Coord(-1, -1))).mul(isz).add(UI.scale(new Coord(1, 1))));
	    sqmask = null;
	} else if(msg == "mask") {
	    boolean[] nmask;
	    if(args[0] == null) {
		nmask = null;
	    } else {
		nmask = new boolean[isz.x * isz.y];
		byte[] raw = (byte[])args[0];
		for(int i = 0; i < isz.x * isz.y; i++)
		    nmask[i] = (raw[i >> 3] & (1 << (i & 7))) != 0;
	    }
	    this.sqmask = nmask;
	} else if(msg == "mode") {
	    dropul = !Utils.bv(args[0]);
	} else {
	    super.uimsg(msg, args);
	}
    }
}
