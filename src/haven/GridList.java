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
import static haven.PUtils.*;

public abstract class GridList<T> extends Widget {
    // addon: the smaller in-window heading furnace (group captions). Same "heading" scope as CharWnd.catf, with
    // its own stock size (fraktur 18) -- the provider keeps a per-stock foundry, so one override fronts both and
    // each keeps its own size (F3e, D-043). `dcatf` itself stays stock.
    public static final Text.Foundry dcatfnd = new Text.Foundry(Text.fraktur, 18).aa(true);
    public static final Text.Furnace dcatf = new BlurFurn(new TexFurn(dcatfnd, Window.ctex), 2, 1, new Color(96, 48, 0));
    private static Text.Furnace bdcatf;
    private static int fontgen = -1;
    /** addon: the current group-heading furnace ({@code "heading"} scope, else stock {@link #dcatf}). */
    public static Text.Furnace dcatfont() {
	int g = Fonts.gen();
	if((bdcatf == null) || (fontgen != g)) {
	    Text.Foundry f = Fonts.foundry("heading", dcatfnd);
	    // addon: (065.14) ...and the RELIEF it is cut out of, so a `heading` rule that drops the emboss reaches
	    // a group caption too. The fast path widens with it: an emboss rule moves nothing about the foundry.
	    // addon: (065.15) ...and the HALO behind it, which the same `heading` rule names for both sizes.
	    bdcatf = ((f == dcatfnd) && !Fonts.styled()) ? dcatf
		: Fonts.glow("heading", Fonts.emboss("heading", f, Window.ctex), 2, 1, new Color(96, 48, 0));
	    fontgen = g;
	}
	return(bdcatf);
    }
    public final Text.Furnace catf;
    public final Scrollbar sb;
    public T sel = null;
    public int gmarg = 10;
    private final List<Group> groups = new ArrayList<>();

    public class Group {
	public final Coord itemsz, marg;
	public final String name;
	public List<T> items;
	private int sy, ey;
	private Text rname;

	public Group(Coord itemsz, Coord marg, String name, List<T> items) {
	    this.itemsz = itemsz;
	    this.marg = marg;
	    this.name = name;
	    this.items = items;
	    groups.add(this);
	}

	public void update(List<T> items) {
	    this.items = items;
	    GridList.this.update();
	}

	private int rnamegen = -1;   // addon: Fonts.gen() at the last rname render (F3e)
	public Text rname() {
	    /* addon: re-render this group's caption when a "heading" override moves. A list built on the default
	     * furnace follows the scope; one given an explicit furnace keeps it (like an explicit-foundry Label). */
	    int gen = Fonts.gen();
	    if((rname != null) && (rnamegen != gen)) {
		rname.dispose();
		rname = null;
	    }
	    if(rname == null) {
		// addon: (102.2) ...and under the "heading" scope, so a catalogue reaches a group caption by name.
		rname = Fonts.render("heading", (catf == dcatf) ? dcatfont() : catf, name);   // addon: was `catf.render(name)`
		rnamegen = gen;
	    }
	    return(rname);
	}
    }

    public GridList(Coord sz) {
	super(sz);
	this.catf = dcatf;
	this.sb = adda(new Scrollbar(sz.y, 0, 0), sz.x, 0, 1, 0);
    }

    protected void update() {
	int y = 0;
	int w = sz.x - sb.sz.x;
	for(Group grp : groups) {
	    if(grp.items.size() == 0)
		continue;
	    if(y > 0)
		y += gmarg;
	    grp.sy = y;
	    if(grp.name != null)
		y += grp.rname().sz().y;
	    int rw = Math.max((w - grp.itemsz.x) / (grp.itemsz.x + Math.max(grp.marg.x, 0)), 0) + 1;
	    int nr = (grp.items.size() + (rw - 1)) / rw;
	    y += nr * grp.itemsz.y;
	    if(nr > 1)
		y += (nr - 1) * grp.marg.y;
	    grp.ey = y;
	}
	sb.max = y - sz.y;
    }

    public void resize(Coord sz) {
	super.resize(sz);
	sb.resize(sz.y);
	sb.c = new Coord(sz.x - sb.sz.x, 0);
	update();
    }

    protected void drawbg(GOut g) {}

    protected void drawsel(GOut g) {
	g.chcolor(255, 255, 0, 128);
	g.frect(Coord.z, g.sz());
	g.chcolor();
    }

    protected abstract void drawitem(GOut g, T item);

    private static int adjx(int col, int iw, int rw, int ww) {
	return((col * iw) + (((ww - (rw * iw)) * col) / (rw - 1)));
    }

    public void draw(GOut g) {
	drawbg(g);
	int yo = sb.val;
	int W = sz.x - sb.sz.x, w = sz.x - (sb.vis() ? sb.sz.x : 0);
	for(Group grp : groups) {
	    if(grp.items.size() == 0)
		continue;
	    if((grp.ey - yo < 0) || (grp.sy - yo >= sz.y))
		continue;
	    int iy = grp.sy - yo;
	    if(grp.name != null) {
		g.image(grp.rname().tex(), new Coord(0, grp.sy - yo));
		iy += grp.rname().sz().y;
	    }
	    int rw = Math.max((W - grp.itemsz.x) / (grp.itemsz.x + Math.max(grp.marg.x, 0)), 0) + 1;
	    int sr = Math.max(-iy, 0) / (grp.itemsz.y + grp.marg.y);
	    for(int i = sr * rw; i < grp.items.size(); i++) {
		Coord c = new Coord(0, iy + ((i / rw) * (grp.itemsz.y + grp.marg.y)));
		if(grp.marg.x >= 0)
		    c.x = (i % rw) * (grp.itemsz.x + grp.marg.x);
		else
		    c.x = adjx(i % rw, grp.itemsz.x, rw, w);
		GOut ig = g.reclip(c, grp.itemsz);
		T item = grp.items.get(i);
		if(item == sel)
		    drawsel(ig);
		try {
		    drawitem(ig, item);
		} catch(Loading l) {
		    ig.image(WItem.missing.layer(Resource.imgc).tex(), Coord.z, g.sz());
		}
	    }
	}
	super.draw(g);
    }

    public boolean mousewheel(MouseWheelEvent ev) {
	sb.ch(ev.s * 20);
	return(true);
    }

    public T itemat(Coord c) {
	int ay = c.y + sb.val;
	int W = sz.x - sb.sz.x, w = sz.x - (sb.vis() ? sb.sz.x : 0);
	for(Group grp : groups) {
	    if((ay >= grp.sy) && (ay < grp.ey)) {
		int gy = ay - grp.sy;
		if(grp.name != null)
		    gy -= grp.rname().sz().y;
		if(gy < 0)
		    return(null);
		int rw = Math.max((W - grp.itemsz.x) / (grp.itemsz.x + Math.max(grp.marg.x, 0)), 0) + 1;
		int row = gy / (grp.itemsz.y + grp.marg.y);
		if(gy >= (row * (grp.itemsz.y + grp.marg.y)) + grp.itemsz.y)
		    return(null);
		int col;
		if(grp.marg.x >= 0) {
		    col = c.x / (grp.itemsz.x + grp.marg.x);
		    if(c.x >= (col * (grp.itemsz.x + grp.marg.x)) + grp.itemsz.x)
			return(null);
		} else {
		    for(col = 0; col < rw; col++) {
			int cx = adjx(col, grp.itemsz.x, rw, w);
			if((c.x >= cx) && (c.x < cx + grp.itemsz.x))
			    break;
		    }
		    if(col == rw)
			return(null);
		}
		int idx = (row * rw) + col;
		if(idx >= grp.items.size())
		    return(null);
		return(grp.items.get(idx));
	    }
	}
	return(null);
    }

    public void change(T item) {
	this.sel = item;
    }

    protected void itemclick(T item, int button) {
	if(button == 1)
	    change(item);
    }

    public boolean mousedown(MouseDownEvent ev) {
	if(ev.propagate(this) || super.mousedown(ev))
	    return(true);
	T item = itemat(ev.c);
	// addon: 061 -- the Cell seam, on the SELECTING button only. itemclick moves `sel` for button 1 alone,
	// so a seam above this line would fire for a right-click that selects nothing and let a cancel eat the
	// client's own context menu. The click-away below is the same selection change SListBox.unselect makes.
	if((item == null) && (ev.b == 1)) {
	    if(AddonWidgets.activate(this, "Cell", null))
		change(null);
	} else if(item != null) {
	    if((ev.b != 1) || AddonWidgets.activate(this, "Cell", item))
		itemclick(item, ev.b);
	}
	return(true);
    }

    public java.util.function.Function<T, Object> itemtooltip = null;
    public Object tooltip(Coord c, Widget prev) {
	if(itemtooltip != null) {
	    T item = itemat(c);
	    if(item != null)
		return(itemtooltip.apply(item));
	}
	return(null);
    }
}
