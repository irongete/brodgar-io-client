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
import java.util.function.*;
import java.awt.image.BufferedImage;

public abstract class SListWidget<I, W extends Widget> extends Widget {
    public I sel;

    public SListWidget(Coord sz) {
	super(sz);
    }

    protected abstract List<? extends I> items();
    protected abstract W makeitem(I item, int idx, Coord sz);

    public static interface ItemFactory<I, W extends Widget> {
	public W makeitem(I item, int idx, Coord sz);
    }

    public void change(I item) {
	this.sel = item;
    }

    /* addon: 061 -- WHOSE selection a row click is. The list a row funnels through is not always the control
     * an addon holds: a dropdown's rows live in its popup (which adds itself to ui.root and is not even a
     * child of the box) and a menu's in its own inner list. Those two override this to name the enclosing
     * control; every other list is its own owner. One accessor, three families, no upward walk that a
     * root-parented popup would break. */
    public Widget slistowner() {
	return(this);
    }

    public static class ItemWidget<I> extends Widget {
	public final SListWidget<I, ?> list;
	public final I item;

	public ItemWidget(SListWidget<I, ?> list, Coord sz, I item) {
	    super(sz);
	    this.list = list;
	    this.item = item;
	}

	protected boolean toggle() {return(false);}

	protected boolean clicked(MouseDownEvent ev) {
	    if(toggle() && (list.sel == item))
		list.change(null);
	    else
		list.change(item);
	    return(true);
	}

	public boolean mousedown(MouseDownEvent ev) {
	    if(ev.propagate(this) || super.mousedown(ev))
		return(true);
	    if(ev.b == 1) {
		// addon: 061 -- the Changed/Selected seam, where the client RECEIVES the click and before its
		// own change(): SListWidget.change is overridden by SDropBox and by a menu's inner list without
		// calling super, and clicked() is likewise a subclass's to override, so a seam in either is one
		// a subclass can skip. This one site serves all three families. It sits INSIDE the button gate
		// because a press that will not change the selection has no Changed to report.
		if(!AddonWidgets.listActivate(list, item))
		    return(true);
		return(clicked(ev));
	    }
	    return(false);
	}
    }

    public static abstract class TextItem extends Widget {
	public TextItem(Coord sz) {super(sz);}
	public TextItem(int w) {
	    super();
	    resize(Coord.of(w, foundry().height()));
	}

	protected abstract String text();
	protected int margin() {return(-1);}
	protected Text.Forge foundry() {return(CharWnd.attrfont());}   // addon: the "label" font scope (F3c, D-043)
	protected boolean valid(String text) {return(true);}

	private Text.Slug text = null;
	/* addon: (102.2) the row text this raster was rendered FROM. `Text.text` is what was DRAWN, and a
	 * catalogue makes the two different strings -- so `valid` is asked about the source (its overrides
	 * compare it against text(), which is the model) and the ellipsis is cut out of the raster's own. */
	private String textsrc = null;
	private int fontgen = -1;    // addon: Fonts.gen() at the last render
	protected void drawtext(GOut g) {
	    dropfont();   // addon: re-render this item when a "label" font override moves (F3c)
	    try {
		if((this.text == null) || !valid(textsrc)) {
		    this.text = foundry().render(textsrc = text());
		    if(this.text.sz().x > sz.x) {
			int len = this.text.charat(sz.x - foundry().strsize("...").x);
			this.text = foundry().render(this.text.text.substring(0, len) + "...");
		    }
		}
		int m = margin();
		int y = (sz.y - this.text.sz().y) / 2;
		g.image(this.text.tex(), Coord.of((m < 0) ? y : m, y));
	    } catch(Loading l) {
		Tex missing = foundry().render("...").tex();
		g.image(missing, Coord.of(UI.scale(5), (sz.y - missing.sz().y) / 2));
		missing.dispose();
	    }
	}

	public void draw(GOut g) {
	    drawtext(g);
	}

	public void dispose() {
	    super.dispose();
	    invalidate();
	}

	/* addon: drop only the cached TEXT when the "label" font override moved (F3c, D-043) -- the next drawtext
	 * re-renders it through the provider. Kept separate from invalidate() so a font change does not throw away
	 * anything else a subclass caches. */
	private void dropfont() {
	    int gen = Fonts.gen();
	    if(gen == fontgen)
		return;
	    fontgen = gen;
	    if(text != null) {
		text.dispose();
		text = null;
	    }
	}

	public void invalidate() {
	    if(text != null) {
		text.dispose();
		text = null;
	    }
	}

	public static TextItem of(Coord sz, Text.Forge fnd, Supplier<String> text) {
	    return(new TextItem(sz) {
		    public String text() {return(text.get());}
		    public Text.Forge foundry() {return(fnd);}
		});
	}

	public static TextItem of(Coord sz, Supplier<String> text) {
	    return(new TextItem(sz) {
		    public String text() {return(text.get());}
		});
	}
    }

    public static abstract class IconText extends Widget {
	public IconText(Coord sz) {super(sz);}
	public IconText(int w) {
	    super();
	    resize(Coord.of(w, foundry().height()));
	}

	protected abstract BufferedImage img();
	protected abstract String text();
	protected int margin() {return(0);}
	protected Text.Forge foundry() {return(CharWnd.attrfont());}   // addon: the "label" font scope (F3c, D-043)
	protected boolean valid(String text) {return(true);}
	protected PUtils.Convolution filter() {return(GobIcon.filter);}

	private Tex img = null;
	protected void drawicon(GOut g) {
	    int m = margin(), h = sz.y - (m * 2);
	    try {
		if(this.img == null) {
		    BufferedImage img = img();
		    if(img == null) {
			this.img = Tex.nil;
		    } else {
			if(img.getWidth() > img.getHeight()) {
			    if(img.getWidth() != h)
				img = PUtils.convolve(img, Coord.of(h, (h * img.getHeight()) / img.getWidth()), filter());
			} else {
			    if(img.getHeight() != h)
				img = PUtils.convolve(img, Coord.of((h * img.getWidth()) / img.getHeight(), h), filter());
			}
			this.img = new TexI(img);
		    }
		}
		g.image(this.img, Coord.of(sz.y).sub(this.img.sz()).div(2));
	    } catch(Loading l) {
		g.image(WItem.missing.layer(Resource.imgc).tex(), Coord.of(m), Coord.of(sz.y - (m * 2)));
	    }
	}

	private Text.Slug text = null;
	private String textsrc = null;   // addon: (102.2) see the TextItem twin above
	private int fontgen = -1;    // addon: Fonts.gen() at the last render
	protected void drawtext(GOut g) {
	    dropfont();   // addon: re-render this item when a "label" font override moves (F3c)
	    int tx = sz.y + UI.scale(5);
	    try {
		if((this.text == null) || !valid(textsrc)) {
		    this.text = foundry().render(textsrc = text());
		    if(tx + this.text.sz().x > sz.x) {
			int len = this.text.charat(sz.x - tx - foundry().strsize("...").x);
			this.text = foundry().render(this.text.text.substring(0, len) + "...");
		    }
		}
		g.image(this.text.tex(), Coord.of(tx, (sz.y - this.text.sz().y) / 2));
	    } catch(Loading l) {
		Tex missing = foundry().render("...").tex();
		g.image(missing, Coord.of(sz.y + UI.scale(5), (sz.y - missing.sz().y) / 2));
		missing.dispose();
	    }
	}

	public void draw(GOut g) {
	    drawicon(g);
	    drawtext(g);
	}

	public void dispose() {
	    super.dispose();
	    invalidate();
	}

	/* addon: drop only the cached TEXT when the "label" font override moved (F3c, D-043) -- the icon is
	 * unaffected by a font change, so it is deliberately NOT re-convolved here (unlike invalidate()). */
	private void dropfont() {
	    int gen = Fonts.gen();
	    if(gen == fontgen)
		return;
	    fontgen = gen;
	    if(text != null) {
		text.dispose();
		text = null;
	    }
	}

	public void invalidate() {
	    if(img != null) {
		img.dispose();
		img = null;
	    }
	    if(text != null) {
		text.dispose();
		text = null;
	    }
	}

	public static class FromRes extends IconText {
	    public final Indir<Resource> res;

	    public FromRes(Coord sz, Indir<Resource> res) {
		super(sz);
		this.res = res;
	    }

	    public BufferedImage img() {
		return(res.get().flayer(Resource.imgc).img);
	    }

	    public String text() {
		Resource.Tooltip name = res.get().layer(Resource.tooltip);
		return((name == null) ? "???" : name.t);
	    }
	}

	public static IconText of(Coord sz, Indir<Resource> res) {
	    return(new FromRes(sz, res));
	}

	public static IconText of(Coord sz, Supplier<BufferedImage> img, Supplier<String> text) {
	    return(new IconText(sz) {
		    public BufferedImage img() {return(img.get());}
		    public String text() {return(text.get());}
		});
	}
    }
}
