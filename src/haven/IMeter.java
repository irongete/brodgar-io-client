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

import java.awt.Color;
import java.util.*;

/* addon: (106) the horizontal bar, and one of the two surfaces the "meter" key covers.
 *
 * NEITHER meter DECLARES a stock look, and that is a finding rather than an omission. A site declares a
 * property only where the declaration IS what it draws, and the two kinds of meter are made of different
 * things: this one paints a flat trough and blits a frame the SERVER named, while a VMeter blits a frame
 * of the CLIENT's own and has no trough at all. One catalogue entry under one key would therefore describe
 * neither -- a bg from here beside a picture from there is a surface that exists nowhere -- so the key is
 * routed and sayable while sheet:stock() carries nothing for it, exactly as `panel`, `scrollbar` and
 * `slider` already do. */
public class IMeter extends LayerMeter {
    public static final Coord off = UI.scale(22, 7);
    public static final Coord fsz = UI.scale(101, 24);
    public static final Coord msz = UI.scale(75, 10);
    public final Indir<Resource> bg;

    @RName("im")
    public static class $_ implements Factory {
	public Widget create(UI ui, Object[] args) {
	    Indir<Resource> bg = ui.sess.getresv(args[0]);
	    List<Meter> meters = decmeters(args, 1);
	    return(new IMeter(bg, meters));
	}
    }

    public IMeter(Indir<Resource> bg, List<Meter> meters) {
	super(fsz);
	this.bg = bg;
	set(meters);
    }

    public void draw(GOut g) {
	try {
	    Tex bg = this.bg.get().flayer(Resource.imgc).tex();
	    /* addon: (106) the "meter" rule, resolved once: its `bg` stands in for the trough the fill is drawn
	     * on, its `picture` for the frame blitted over the lot, and its `border` frames the whole bar. The
	     * FILL between them is never a rule's -- that colour is the server's, one per meter, and it is what
	     * tells a hunger bar from a stamina one. */
	    Fonts.Chrome ch = Fonts.chrome("meter", this);
	    if((ch == null) || !ch.bg()) {
		g.chcolor(0, 0, 0, 255);
		g.frect(off, msz);
		g.chcolor();
	    } else {
		ch.drawbg(g, off, msz);
	    }
	    for(Meter m : meters) {
		int w = msz.x;
		w = (int)Math.ceil(w * m.a);
		g.chcolor(m.c);
		g.frect(off, new Coord(w, msz.y));
	    }
	    g.chcolor();
	    Fonts.Picture p = Fonts.picture("meter", this);   // addon: (106) the frame, the rule's or the server's
	    if(p == null)
		g.image(bg, Coord.z);
	    else
		p.draw(g, Coord.z, sz);
	    if((ch != null) && ch.border())
		ch.drawborder(g, Coord.z, sz);   // addon: (106) ...and a frame round the whole bar, if one is named
	} catch(Loading l) {
	}
    }
}
