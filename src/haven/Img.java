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

public class Img extends Widget {
    private Tex img;
    private BufferedImage rimg;
    public boolean hit = false, opaque = false;
	
    @RName("img")
    public static class $_ implements Factory {
	public Widget create(UI ui, Object[] args) {
	    Indir<Resource> res;
	    int a = 0;
	    if(args[a] instanceof String) {
		String nm = (String)args[a++];
		int ver = (args.length > a) ? Utils.iv(args[a++]) : -1;
		res = new Resource.Spec(Resource.remote(), nm, ver);
	    } else {
		res = ui.sess.getresv(args[a++]);
	    }
	    Img ret = new Img(res.get().flayer(Resource.imgc).tex());
	    if(args.length > a) {
		int fl = Utils.iv(args[a++]);
		ret.hit = (fl & 1) != 0;
		ret.opaque = (fl & 2) != 0;
	    }
	    return(ret);
	}
    }

    public void setimg(Tex img) {
	this.img = img;
	resize(img.sz());
	if(img instanceof TexI)
	    rimg = ((TexI)img).back;
	else
	    rimg = null;
    }

    // addon: 063.3 -- widget:picture() asks a widget WHICH picture object it shows, and this one is private.
    // Read live on every call: setimg is public and the server re-points it (uimsg "ch"), so nothing caches it.
    public Tex img() {
	return(img);
    }

    // addon: 065.13 -- the style SITE this picture is one of, for the few the CLIENT itself places at a fixed
    // spot (the minimap's frame, the action-search plate). Null on every other Img -- every one the server
    // places -- which are named by a tree rule alone, one @Img being indistinguishable from the next.
    private String site = null;
    public Img site(String scope) {
	this.site = scope;
	return(this);
    }

    public void draw(GOut g) {
	// addon: 065.12 -- a `picture` rule paints the whole plate instead, read HERE rather than written into
	// the widget: setimg is the server's (uimsg "ch"), so a write would be clobbered and would fight the
	// restore when the rule goes away. Null -- the answer a stock client always gives -- draws the client's own.
	Fonts.Picture p = Fonts.picture(site, this);
	if(p != null)
	    p.draw(g, Coord.z, sz);
	else
	    g.image(img, Coord.z);
    }

    public Img(Tex img) {
	super(img.sz());
	setimg(img);
    }

    public void uimsg(String name, Object... args) {
	if(name == "ch") {
	    Indir<Resource> res;
	    if(args[0] instanceof String) {
		String nm = (String)args[0];
		int ver = (args.length > 1) ? Utils.iv(args[1]) : -1;
		res = new Resource.Spec(Resource.remote(), nm, ver);
	    } else {
		res = ui.sess.getresv(args[0]);
	    }
	    setimg(res.get().flayer(Resource.imgc).tex());
	} else if(name == "cl") {
	    hit = Utils.bv(args[0]);
	} else {
	    super.uimsg(name, args);
	}
    }
    
    public boolean checkhit(Coord c) {
	if(!c.isect(Coord.z, sz))
	    return(false);
	if(opaque || (rimg == null) || (rimg.getRaster().getNumBands() < 4))
	    return(true);
	return(rimg.getRaster().getSample(c.x, c.y, 3) >= 128);
    }

    public boolean mousedown(MouseDownEvent ev) {
	if(hit && checkhit(ev.c)) {
	    wdgmsg("click", ev.c, ev.b, ui.modflags());
	    return(true);
	}
	return(super.mousedown(ev));
    }
}
