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

public class Frame extends Widget {
    public final IBox box;

    public Frame(Coord sz, boolean inner, IBox box) {
	super(inner ? sz.add(box.bisz()) : sz);
	this.box = box;
    }

    public Frame(Coord sz, boolean inner) {
	this(sz, inner, Window.wbox);
    }

    public static Frame around(Widget parent, Area area, IBox box) {
	return(parent.add(new Frame(area.sz(), true, box),
			  area.ul.sub(box.btloff())));
    }

    public static Frame around(Widget parent, Area area) {
	return(around(parent, area, Window.wbox));
    }

    public static Frame around(Widget parent, Iterable<? extends Widget> wl) {
	Widget f = Utils.el(wl);
	Coord tl = new Coord(f.c), br = new Coord(f.c);
	for(Widget wdg : wl) {
	    Coord wbr = wdg.c.add(wdg.sz);
	    if(wdg.c.x < tl.x) tl.x = wdg.c.x;
	    if(wdg.c.y < tl.y) tl.y = wdg.c.y;
	    if(wbr.x > br.x) br.x = wbr.x;
	    if(wbr.y > br.y) br.y = wbr.y;
	}
	return(around(parent, new Area(tl, br)));
    }

    public static Frame around(Widget parent, Widget... ch) {
	return(around(parent, Arrays.asList(ch)));
    }

    public static Frame with(Widget child, boolean resize) {
	if(resize) {
	    Frame ret = new Frame(child.sz, false);
	    child.resize(child.sz.sub(ret.box.bisz()));
	    ret.add(child, 0, 0);
	    return(ret);
	} else {
	    Frame ret = new Frame(child.sz, true);
	    ret.add(child, 0, 0);
	    return(ret);
	}
    }

    public Coord inner() {
	return(sz.sub(box.bisz()));
    }

    public Coord xlate(Coord c, boolean in) {
	if(in)
	    return(c.add(box.btloff()));
	else
	    return(c.sub(box.btloff()));
    }

    public Position getpos(String nm) {
	switch(nm) {
	case "iul": return(new Position(this.c.add(box.btloff())));
	case "iur": return(new Position(this.c.add(this.sz.x - box.bbroff().x, box.btloff().y)));
	case "ibr": return(new Position(this.c.add(this.sz).sub(box.bbroff())));
	case "ibl": return(new Position(this.c.add(box.btloff().x, this.sz.y - box.bbroff().y)));
	case "icul": return(new Position(box.btloff()));
	case "icur": return(new Position(this.sz.x - box.bbroff().x, box.btloff().y));
	case "icbr": return(new Position(this.sz.sub(box.bbroff())));
	case "icbl": return(new Position(box.btloff().x, this.sz.y - box.bbroff().y));
	default: return(super.getpos(nm));
	}
    }

    /* addon: (035.3/C2) the sheet-fed 9-slice for this panel, or the stock {@link #box} when no rule names it.
     * `Frame` is by far the most reachable IBox panel in the client -- the portrait, the party views, and the
     * list/info boxes in the character, skill, quest, wound, fight and buddy windows all are one -- so the
     * lookup lives here once and its two subclasses that paint their own frame (ProxyFrame, Partyview.MemberView)
     * ask for it rather than repeating it. The stock box is handed straight back when nothing is installed. */
    protected IBox skinbox() {
	return(Fonts.box("panel", this, box));
    }

    public void drawframe(GOut g) {
	skinbox().draw(g, Coord.z, sz);   // addon: (035.3) was box.draw(...)
    }

    public void draw(GOut g) {
	/* addon: (035.3) NO `bg` here, deliberately -- a Frame takes `border` and nothing else. A sheet's `bg`
	 * replaces a surface the panel already paints, and this one paints none: a Frame is a border placed
	 * AROUND a region, drawn AFTER what it frames (`Frame.around`/`addin` leave that content a sibling of the
	 * frame entirely). A stock 9-slice never noticed, painting only its edges; a filled bg would bury the very
	 * rows the frame is drawn around -- the character sheet's attribute list, found in-game. The other routed
	 * panels (Petal, SListMenu, ISBox, DynresWindow.Image) each paint their own surface BEFORE their content,
	 * which is exactly where a bg can go and where it is honoured. Inert, never an error. */
	super.draw(g);
	drawframe(g);
    }

    public boolean checkhit(Coord c) {
	Coord ul = box.btloff();
	if((c.x < ul.x) || (c.y < ul.y))
	    return(true);
	Coord br = sz.sub(box.bisz()).add(ul);
	if((c.x >= br.x) || (c.y >= br.y))
	    return(true);
	return(false);
    }

    public <T extends Widget> T addin(T child) {
	child.resize(inner());
	parent.add(child, this.c.add(box.btloff()));
	return(child);
    }
}
