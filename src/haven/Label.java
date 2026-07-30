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
    // addon: live font scope (F1 + F3c, D-043). A label built through the DEFAULT constructors follows the
    // "default" scope; a label built with an EXPLICIT foundry follows the "label" scope (F3c) with that foundry as
    // its stock -- so an override on "label" refines only those, while setFont("default", h) still cascades to
    // both. Either way a hafen.font.setFont/reset re-renders the label live. fontstock is the site's own foundry
    // (the fallback handed to the provider), fontwrapw >= 0 selects renderwrap (a width-wrapped label), and
    // fontgen is the last provider generation this label rendered at (a cheap int compare in draw skips work when
    // nothing changed).
    private String fontscope = null;
    private Text.Foundry fontstock = null;
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
	this(text, w, f, "label");        // addon: an explicit-foundry label follows the "label" font scope (F3c)
    }

    public Label(String text, Text.Foundry f) {
	this(text, -1, f, "label");       // addon: an explicit-foundry label follows the "label" font scope (F3c)
    }

    public Label(String text, int w) {
	this(text, w, Text.std, "default");    // addon: the default follows the "default" font scope (F1)
    }

    public Label(String text) {
	this(text, -1, Text.std, "default");   // addon: the default follows the "default" font scope (F1)
    }

    /* addon: the common constructor (F1/F3c, D-043). Resolves `stock` through the font provider for `scope` and
     * remembers both, so restyle() can re-render this label live when an override on that scope is installed or
     * dropped. `w` < 0 = an unwrapped label. */
    private Label(String text, int w, Text.Foundry stock, String scope) {
	super(Coord.z);
	this.fontscope = scope;
	this.fontstock = stock;
	this.fontwrapw = w;
	this.fontgen = Fonts.gen();
	this.f = Fonts.foundry(scope, stock);
	this.text = (w >= 0) ? f.renderwrap(texts = text, this.col, w) : f.render(texts = text, this.col);
	resize(this.text.sz());
    }

    public void draw(GOut g) {
	restyle();   // addon: re-render if a font override on this label's scope moved (F1, D-043)
	g.image(text.tex(), Coord.z);
    }

    /* addon: live font re-style (F1 + F3c, D-043). A default label follows the "default" scope, an
     * explicit-foundry one the "label" scope (with its own foundry as the stock fallback); when
     * hafen.font.setFont/reset bumps the provider generation the label re-resolves its foundry and re-renders
     * (preserving its wrap width + colour). A no-op — one int compare — when nothing changed. */
    private void restyle() {
	if(fontscope == null)
	    return;
	int gen = Fonts.gen();
	if(gen == fontgen)
	    return;
	fontgen = gen;
	Text.Foundry nf = Fonts.foundry(fontscope, fontstock);
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
