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

public class ICheckBox extends ACheckBox {
    public final Tex up, down, hoverup, hoverdown;
    private final BufferedImage img;
    public boolean h;

    @RName("ichk")
    public static class $_ implements Factory {
	public Widget create(UI ui, Object[] args) {
	    Tex up = Loading.waitfor(ui.sess.getresv(args[0])).flayer(Resource.imgc).tex();
	    Tex down = Loading.waitfor(ui.sess.getresv(args[1])).flayer(Resource.imgc).tex();
	    Tex hoverup = (args.length > 2) ? Loading.waitfor(ui.sess.getresv(args[1])).flayer(Resource.imgc).tex() : up;
	    Tex hoverdown = (args.length > 3) ? Loading.waitfor(ui.sess.getresv(args[1])).flayer(Resource.imgc).tex() : down;
	    ICheckBox ret = new ICheckBox(up, down, hoverup, hoverdown);
	    ret.canactivate = true;
	    return(ret);
	}
    }

    public ICheckBox(Tex up, Tex down, Tex hoverup, Tex hoverdown) {
	super(up.sz());
	this.up = up;
	this.down = down;
	this.hoverup = hoverup;
	this.hoverdown = hoverdown;
	if(up instanceof TexI)
	    this.img = ((TexI)up).back;
	else
	    this.img = null;
    }

    public ICheckBox(Tex up, Tex down, Tex hover) {
	this(up, down, hover, down);
    }

    public ICheckBox(Tex up, Tex down) {
	this(up, down, up);
    }

    public ICheckBox(String base, String up, String down, String hoverup, String hoverdown) {
	this(Resource.loadtex(base + up), Resource.loadtex(base + down), Resource.loadtex(base + hoverup), Resource.loadtex(base + hoverdown));
    }
    public ICheckBox(String base, String up, String down, String hover) {
	this(Resource.loadtex(base + up), Resource.loadtex(base + down), Resource.loadtex(base + hover));
    }
    public ICheckBox(String base, String up, String down) {
	this(Resource.loadtex(base + up), Resource.loadtex(base + down));
    }

    /* addon: (065.10) DELIBERATELY not routed through the "checkbox" key, for the reason IButton is not routed
     * through "button": a picture checkbox IS its picture -- a claim overlay, a map icon, a dropdown arrow --
     * and a rule replaces a surface the client already paints rather than inventing one over a meaning. The
     * geometry says the same thing twice over: GameUI.MenuCheckBox stacks five of these at (0,0), each carrying
     * the WHOLE menu panel's art with only its own button opaque and checkhit sampling `up`'s alpha to route
     * the click, so a fill over `sz` would paint the entire HUD panel once per button. The box-and-tick
     * checkbox is CheckBox, and that one is routed. */
    public void draw(GOut g) {
	if(!state())
	    g.image(h ? hoverup : up, Coord.z);
	else
	    g.image(h ? hoverdown : down, Coord.z);
        super.draw(g);
    }

    public boolean checkhit(Coord c) {
	if(!c.isect(Coord.z, sz))
	    return(false);
	if((img == null) || img.getRaster().getNumBands() < 4)
	    return(true);
	return(img.getRaster().getSample(c.x, c.y, 3) >= 128);
    }

    public boolean mousedown(MouseDownEvent ev) {
	if((ev.b == 1) && checkhit(ev.c)) {
	    // addon: 061 -- the Changed seam, where the client receives the CLICK, before its own click().
	    // The value is what the box is ABOUT to take, so cancelling means it never flipped.
	    if(AddonWidgets.activate(this, "Changed", AddonWidgets.checkValue(this)))
		click();
	    return(true);
	}
	return(super.mousedown(ev));
    }

    public void mousemove(MouseMoveEvent ev) {
	this.h = checkhit(ev.c);
    }
}
