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

public class ItemDrag extends WItem {
    public Coord doff;
    
    public ItemDrag(Coord dc, GItem item) {
	super(item);
	z(100);
	this.doff = dc;
    }

    protected void added() {
	this.c = parent.ui.mc.add(doff.inv());
	ui.grabmouse(this);
    }
    
    public void drawmain(GOut g, GSprite spr) {
	g.chcolor(255, 255, 255, 128);
	super.drawmain(g, spr);
	g.chcolor();
    }

    public boolean mousedown(MouseDownEvent ev) {
	if(!ev.grabbed)
	    return(false);
	if(ui.modctrl && !ui.modshift && !ui.modmeta) {
	    /* XXX */
	    GameUI gui = getparent(GameUI.class);
	    if((gui != null) && (gui.map != null)) {
		ui.modctrl = false;
		return(ev.derive(gui.map.rootxlate(ev.c.add(rootpos()))).dispatch(gui.map));
	    }
	}
	if(ev.b == 1) {
	    /* addon: a widget standing in the 3D world (044.6) is no longer under this parent, and the
	     * drop below would go straight past it to the map underneath -- so the panel under the pointer
	     * is asked first, in its own pixels. False = nothing there took it, and the client's own
	     * dispatch runs unchanged. */
	    if(io.brodgar.addon.AddonManager.surfaceDrop(new Drop(ev.c.add(this.c), this), ui.mc))
		return(true);
	    if(ui.dispatchq(parent, new Drop(ev.c.add(this.c), this)).handled)
		return(true);
	} else if(ev.b == 3) {
	    if(io.brodgar.addon.AddonManager.surfaceDrop(new Interact(ev.c.add(this.c), this), ui.mc))	// addon: see above (044.6)
		return(true);
	    if(ui.dispatchq(parent, new Interact(ev.c.add(this.c), this)).handled)
		return(true);
	}
	return(false);
    }

    public void mousemove(MouseMoveEvent ev) {
	this.c = this.c.add(ev.c.sub(doff));
    }
}
