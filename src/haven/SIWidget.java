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

import java.awt.image.*;

public abstract class SIWidget extends Widget {
    private Tex surf = null;

    public SIWidget(Coord sz) {
	super(sz);
    }

    protected abstract void draw(BufferedImage buf);

    public BufferedImage draw() {
	BufferedImage buf = TexI.mkbuf(sz);
	draw(buf);
	return(buf);
    }

    public void draw(GOut g) {
	if(this.surf == null) {
	    this.surf = new TexI(draw());
	}
	g.image(surf, Coord.z);
    }

    public void redraw() {
	if(surf != null)
	    surf.dispose();
	surf = null;
    }

    // addon: 044.4 spatial UI (hafen.virtual():widget()) — has this widget thrown away its cached face, i.e. will its
    //        next draw rasterize a new picture? On the flat UI nobody needs to ask: the screen is redrawn every
    //        frame regardless. A widget standing in the WORLD is drawn into a texture that is re-uploaded only
    //        when its content changed, and redraw() is the client's own statement that it did — a button
    //        depressing under a click, arming and disarming as the pointer leaves and re-enters it, being
    //        disabled, having its caption changed. None of that is visible in the widget's place, size,
    //        visibility or caption, so without this the panel in the world would go on showing the picture from
    //        before the click. Same shape and same reason as Window.animating() (044.3).
    public boolean redrawing() {
	return(surf == null);
    }

    public void dispose() {
	super.dispose();
	if(surf != null)
	    surf.dispose();
    }
}
