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

import java.awt.*;
import haven.render.*;

public class Speaking extends GAttrib implements RenderTree.Node, PView.Render2D {
    public static final IBox.Images sb = new IBox.Scaled("gfx/hud/emote", "tl", "tr", "bl", "br", "el", "er", "et", "eb");
    public static final Tex svans = Resource.loadtex("gfx/hud/emote/svans");
    public static final int sx = UI.scale(5);
    public float zo;
    public Text text;

    /* addon: (F4, D-043) the "world.speech" font scope -- speech bubbles above characters. Stock renders the
     * bubble text through the generic Text.render static, which F1 bound to the "default" scope; asking the
     * provider for "world.speech" instead makes the bubbles refinable on their own while an unset scope still
     * cascades to a "default" override. The rendered Text is cached (it is a texture), so the source string is
     * kept and re-rendered when Fonts.gen() moves -- lazily, in draw(), i.e. only for bubbles on screen. The
     * bubble frame is measured from text.sz() every frame, so it grows/shrinks with the font by itself. */
    private String str;
    private int fontgen = Fonts.gen();

    private static Text.Foundry font() {
	return(Fonts.foundry("world.speech", Text.std));
    }

    public Speaking(Gob gob, float zo, String text) {
	super(gob);
	this.zo = zo;
	this.str = text;                                 // addon: (F4) the recipe, for a re-render on a gen move
	this.text = font().render(text, Color.BLACK);    // addon: (F4) was Text.render(...) = the "default" scope
    }

    public void update(String text) {
	this.str = text;                                 // addon: (F4)
	this.text = font().render(text, Color.BLACK);    // addon: (F4)
    }

    /* addon: (F4) re-render this bubble when a font override moved the generation. */
    private void checkfont() {
	int gen = Fonts.gen();
	if((fontgen != gen) && (str != null)) {
	    fontgen = gen;
	    this.text = font().render(str, Color.BLACK);
	}
    }

    public void draw(GOut g, Coord c) {
	checkfont();   // addon: (F4)
	Coord sz = text.sz();
	sz.x = Math.max(sz.x, UI.scale(15));
	Coord tl = c.sub(sx, sb.cisz().y + sz.y + svans.sz().y - sb.bb.sz().y);
	Coord ftl = tl.add(sb.btloff());
	g.chcolor(Color.WHITE);
	g.frect(ftl, sz);
	sb.draw(g, tl, sz.add(sb.cisz()));
	g.chcolor(Color.BLACK);
	g.image(text.tex(), ftl);
	g.chcolor(Color.WHITE);
	g.image(svans, c.add(0, -svans.sz().y));
    }

    public void draw(GOut g, Pipe state) {
	Coord sc = Homo3D.obj2view(new Coord3f(0, 0, zo), state, Area.sized(g.sz())).round2();
	draw(g, sc.add(sx, 0));
    }

    @OCache.DeltaType(OCache.OD_SPEECH)
    public static class $speak implements OCache.Delta {
	public void apply(Gob g, OCache.AttrDelta msg) {
	    float zo = msg.int16() / 100.0f;
	    String text = msg.string();
	    if(text.length() < 1) {
		g.delattr(Speaking.class);
	    } else {
		Speaking m = g.getattr(Speaking.class);
		if(m == null) {
		    g.setattr(new Speaking(g, zo, text));
		} else {
		    m.zo = zo;
		    m.update(text);
		}
	    }
	}
    }
}
