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

public class RadioGroup {
    private Widget parent;
    private ArrayList<RadioButton> btns;
    private HashMap<String, RadioButton> map;
    private HashMap<RadioButton, String> rmap;
    private RadioButton checked;

    public RadioGroup(Widget parent) {
	this.parent = parent;
	btns = new ArrayList<RadioButton>();
	map  = new HashMap<String, RadioButton>();
	rmap = new HashMap<RadioButton, String>();
    }

    public class RadioButton extends CheckBox {
	RadioButton(String lbl) {
	    super(lbl);
	}

	public boolean mousedown(MouseDownEvent ev) {
	    if(a || ev.b != 1)
		return(false);
	    // addon: 061 -- the Changed seam. This method calls check(this) directly, which is the one
	    // activation path the CheckBox.mousedown seam never sees.
	    if(AddonWidgets.activate(this, "Changed", AddonWidgets.checkValue(this)))
		check(this);
	    return(true);
	}

	/* addon: 061 -- the three reads the AddOn seam needs and the group keeps to itself: this button's own
	 * ROW (the value "Changed" carries), the row the GROUP currently holds (what widget:value() answers on
	 * a borrowed radio button -- what a radio holds is a row, and the group is not a widget to point at),
	 * and the group itself, so ev:resend() can run its check(this). */
	public String row() {return(rmap.get(this));}
	public String checked() {return((RadioGroup.this.checked == null) ? null : rmap.get(RadioGroup.this.checked));}
	public RadioGroup group() {return(RadioGroup.this);}

	public void changed(boolean val) {
	    a = val;
	    super.changed(val);
	    lbl = Text.std.render(lbl.text, a ? java.awt.Color.YELLOW : java.awt.Color.WHITE);
	}
    }

    public RadioButton add(String lbl, Coord c) {
	RadioButton rb = new RadioButton(lbl);
	parent.add(rb, c);
	btns.add(rb);
	map.put(lbl, rb);
	rmap.put(rb, lbl);
	if(checked == null)
	    checked = rb;
	return(rb);
    }

    public void check(int index) {
	if(index >= 0 && index < btns.size())
	    check(btns.get(index));
    }

    public void check(String lbl) {
	if(map.containsKey(lbl))
	    check(map.get(lbl));
    }

    public void check(RadioButton rb) {
	if(checked != null)
	    checked.changed(false);
	checked = rb;
	checked.changed(true);
	changed(btns.indexOf(checked), rmap.get(checked));
    }

    public void hide() {
	for(RadioButton rb : btns)
	    rb.hide();
    }

    public void show() {
	for(RadioButton rb : btns)
	    rb.show();
    }

    public void changed(int btn, String lbl) {}
}
