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

public class ILabel extends Widget {
    public final Text.Furnace f;
    public Text text;
    /* addon: (102.2) the caption this label was WRITTEN. `text.text` is the string it DREW, and a
     * catalogue makes the two different -- so both the read below and its guard take this instead. */
    private String texts;

    public ILabel(String text, Text.Furnace f) {
	super(Coord.z);
	this.f = f;
	this.text = f.render(texts = text);
	resize(this.text.sz());
    }

    public void draw(GOut g) {
	g.image(text.tex(), Coord.z);
    }

    public String text() {
	return(texts);   // addon: (102.2) the caption this label was WRITTEN, not the string it drew
    }

    public void settext(String text) {
	// addon: (102.2) ...and the guard compares that same field, exactly as Label.settext does: an
	// unchanged write measured against the raster would stop short-circuiting under a catalogue.
	if(text.equals(this.texts))
	    return;
	this.text.dispose();
	this.text = f.render(texts = text);
	resize(this.text.sz());
    }

    public void dispose() {
	super.dispose();
	this.text.dispose();
    }

    public void uimsg(String msg, Object... args) {
	if(msg == "set") {
	    settext((String)args[0]);
	} else {
	    super.uimsg(msg, args);
	}
    }
}
