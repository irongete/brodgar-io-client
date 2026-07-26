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

public class Label extends Widget {
    public Text.Foundry f;   // addon: non-final so a default label can live-restyle when a font override moves (F1, D-043)
    public Text text;
    public String texts;
    public Color col = Color.WHITE;
    // addon: live font scope (F1, D-043). A label built through the DEFAULT constructors follows the "default"
    // scope, so a hafen.font.setFont("default", h) re-renders it live; a label built with an explicit foundry is
    // fixed (fontscope == null). fontwrapw >= 0 selects renderwrap (a width-wrapped label); fontgen is the last
    // provider generation this label rendered at (a cheap int compare in draw skips work when nothing changed).
    private String fontscope = null;
    private int fontwrapw = -1;
    private int fontgen = -1;

    @RName("lbl")
    public static class $_ implements Factory {
	public Widget create(UI ui, Object[] args) {
	    if(args.length > 1)
		return(new Label(Utils.sv(args[0]), UI.scale(Utils.iv(args[1]))));
	    else
		return(new Label(Utils.sv(args[0])));
	}
    }

    public Label(String text, int w, Text.Foundry f) {
	super(Coord.z);
	this.f = f;
	this.text = f.renderwrap(texts = text, this.col, w);
	resize(this.text.sz());
    }

    public Label(String text, Text.Foundry f) {
	super(Coord.z);
	this.f = f;
	this.text = f.render(texts = text, this.col);
	resize(this.text.sz());
    }

    public Label(String text, int w) {
	this(text, w, Fonts.foundry("default", Text.std));   // addon: the default follows the "default" font scope (F1)
	this.fontscope = "default"; this.fontwrapw = w; this.fontgen = Fonts.gen();
    }

    public Label(String text) {
	this(text, Fonts.foundry("default", Text.std));      // addon: the default follows the "default" font scope (F1)
	this.fontscope = "default"; this.fontgen = Fonts.gen();
    }

    public void draw(GOut g) {
	restyle();   // addon: re-render if a font override on this label's scope moved (F1, D-043)
	g.image(text.tex(), Coord.z);
    }

    /* addon: live font re-style (F1, D-043). A default label follows the "default" scope; when
     * hafen.font.setFont/reset bumps the provider generation the label re-resolves its foundry and re-renders
     * (preserving its wrap width + colour). A no-op — one int compare — when nothing changed or for a fixed
     * explicit-foundry label. */
    private void restyle() {
	if(fontscope == null)
	    return;
	int gen = Fonts.gen();
	if(gen == fontgen)
	    return;
	fontgen = gen;
	Text.Foundry nf = Fonts.foundry(fontscope, Text.std);
	if(nf == f)
	    return;
	f = nf;
	this.text.dispose();
	this.text = (fontwrapw >= 0) ? f.renderwrap(texts, col, fontwrapw) : f.render(texts, col);
	resize(this.text.sz());
    }

    public void settext(String text) {
	if(text.equals(this.text.text))
	    return;
	this.text.dispose();
	this.text = f.render(texts = text, col);
	resize(this.text.sz());
    }

    public void setcolor(Color color) {
	if(color.equals(col))
	    return;
	this.text.dispose();
	this.text = f.render(texts, col = color);
	resize(this.text.sz());
    }

    public void dispose() {
	super.dispose();
	this.text.dispose();
    }

    public void uimsg(String msg, Object... args) {
	if(msg == "set") {
	    settext(Utils.sv(args[0]));
	} else if(msg == "col") {
	    setcolor((Color)args[0]);
	} else {
	    super.uimsg(msg, args);
	}
    }
}
