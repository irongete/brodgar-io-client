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

import java.awt.image.BufferedImage;

public class HSlider extends Widget {
    static final Tex sflarp = Resource.loadtex("gfx/hud/sflarp");
    static final Tex schain;
    static final int chcut = UI.scale(7);
    public int val, min, max;
    private UI.Grab drag = null;

    static {
	BufferedImage vc = Resource.loadsimg("gfx/hud/schain");
	BufferedImage hc = TexI.mkbuf(new Coord(vc.getHeight(), vc.getWidth()));
	for(int y = 0; y < vc.getHeight(); y++) {
	    for(int x = 0; x < vc.getWidth(); x++)
		hc.setRGB(y, x, vc.getRGB(x, y));
	}
	schain = new TexI(hc);
    }

    public HSlider(int w, int min, int max, int val) {
	super(new Coord(w, sflarp.sz().y));
	this.val = val;
	this.min = min;
	this.max = max;
    }

    public void draw(GOut g) {
	// addon: (065.10) the rail is the "slider" rule's, painted over this slider's own box; the same shape
	// Scrollbar wears one axis along. Null on a stock client, and then the chain runs exactly as it did.
	Fonts.Chrome rail = Fonts.chrome("slider", this);
	if(rail == null) {
	    int ew = sz.x + chcut, cw = schain.sz().x;
	    int n = Math.max((ew + cw - 1) / cw, 2);
	    int cy = (sflarp.sz().y - schain.sz().y) / 2;
	    for(int i = 0; i < n; i++)
		g.image(schain, Coord.of(((ew - cw) * i) / (n - 1), cy));
	} else {
	    rail.draw(g, Coord.z, sz);
	}
	int fx = ((sz.x - sflarp.sz().x) * (val - min)) / (max - min);
	// addon: (065.10) ...and the thumb is "slider.knob"'s, at the place and the size the client's own has.
	// addon: (065.17) the thumb's own picture; the rail is the same spread chain a scrollbar draws, and is
	// no more sayable one axis along than it is the other.
	Fonts.stock("slider.knob", "bg", Fonts.piece(sflarp));
	Fonts.Chrome knob = Fonts.chrome("slider.knob", this);
	if(knob == null)
	    g.image(sflarp, new Coord(fx, 0));
	else
	    knob.draw(g, new Coord(fx, 0), sflarp.sz());
    }
    
    private void update(Coord c) {
	double a = (double)(c.x - (sflarp.sz().x / 2)) / (double)(sz.x - sflarp.sz().x);
	if(a < 0)
	    a = 0;
	if(a > 1)
	    a = 1;
	int nval = (int)Math.round(a * (max - min)) + min;
	if(val != nval) {
	    val = nval;
	    // addon: 061 -- the Changed seam, where the client WRITES the value (changed() below is an empty
	    // hook meant for overriding, so it is no seam at all). It reports rather than asks: the value is
	    // already written one line up, so there is nothing left to hold back and the AddOn's own
	    // preventDefault raises. A drag emits one of these per step that actually moves.
	    AddonWidgets.report(this, "Changed", Integer.valueOf(val));
	    changed();
	}
    }

    public boolean mousedown(MouseDownEvent ev) {
	if(ev.b != 1)
	    return(super.mousedown(ev));
	drag = ui.grabmouse(this);
	update(ev.c);
	return(true);
    }
    
    public void mousemove(MouseMoveEvent ev) {
	super.mousemove(ev);
	if(drag != null)
	    update(ev.c);
    }
    
    public boolean mouseup(MouseUpEvent ev) {
	if(ev.b != 1)
	    return(super.mouseup(ev));
	if(drag == null)
	    return(super.mouseup(ev));
	drag.remove();
	drag = null;
	fchanged();
	return(true);
    }

    public void changed() {}
    public void fchanged() {}
    
    public void resize(int w) {
	super.resize(new Coord(w, sflarp.sz().y));
    }
}
