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

import java.awt.Graphics;
import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;

public class Button extends SIWidget {
    public static final BufferedImage bl = Resource.loadsimg("gfx/hud/buttons/tbtn/left");
    public static final BufferedImage br = Resource.loadsimg("gfx/hud/buttons/tbtn/right");
    public static final BufferedImage bt = Resource.loadsimg("gfx/hud/buttons/tbtn/top");
    public static final BufferedImage bb = Resource.loadsimg("gfx/hud/buttons/tbtn/bottom");
    public static final BufferedImage dt = Resource.loadsimg("gfx/hud/buttons/tbtn/dtex");
    public static final BufferedImage ut = Resource.loadsimg("gfx/hud/buttons/tbtn/utex");
    public static final BufferedImage bm = Resource.loadsimg("gfx/hud/buttons/tbtn/mid");
    public static final int hs = bl.getHeight(), hl = bm.getHeight();
    public static final Resource click = Loading.waitfor(Resource.local().load("sfx/hud/btn"));
    public static final Audio.Clip clbtdown = Loading.waitfor(Resource.local().load("sfx/hud/lbtn")).layer(Resource.audio, "down");
    public static final Audio.Clip clbtup = Loading.waitfor(Resource.local().load("sfx/hud/lbtn")).layer(Resource.audio, "up");
    public static final int margin = UI.scale(10);
    public boolean lg;
    public Text text;
    public BufferedImage cont;
    public Runnable action = null;
    static Text.Foundry tf = new Text.Foundry(Text.serif.deriveFont(Font.BOLD, UI.scale(12f))).aa(true);
    static Text.Furnace nf = new PUtils.BlurFurn(new PUtils.TexFurn(tf, Window.ctex), UI.rscale(0.75), UI.rscale(0.75), new Color(80, 40, 0));
    // addon: "button" font scope (F3b, D-043). `tf`/`nf` above stay the STOCK foundry/furnace (Charlist.df still
    // derives from `tf`); every caption Button renders itself goes through the provider instead:
    // Fonts.foundry("button", tf) resolves an addon override, or -- when none -- the stock foundry, cascading
    // through "default". The pair is rebuilt lazily whenever Fonts.gen() moves, and each button re-renders its
    // caption (and re-rasterizes its `cont`) on the same check (see draw(GOut)/render()).
    private static Text.Foundry btf;
    private static Text.Furnace bnf;
    private static int fontgen = -1;
    private static void checkfont() {
	int g = Fonts.gen();
	if((btf == null) || (fontgen != g)) {
	    btf = Fonts.foundry("button", tf);
	    // addon: (065.14) ...and the RELIEF the caption's letters are cut out of, which a rule may re-texture or
	    // drop -- the latter being what lets a `color` rule reach an ordinary button caption. The stock-identity
	    // fast path widens with it: an emboss rule leaves the foundry exactly where it was.
	    bnf = ((btf == tf) && !Fonts.styled()) ? nf
		: new PUtils.BlurFurn(Fonts.emboss("button", btf, Window.ctex), UI.rscale(0.75), UI.rscale(0.75), new Color(80, 40, 0));
	    fontgen = g;
	}
    }
    /** addon: the current foundry for the {@code "button"} scope (an override, else stock {@link #tf}). */
    static Text.Foundry tfont() {checkfont(); return(btf);}
    /** addon: the current blur furnace for the {@code "button"} scope (an override, else stock {@link #nf}). */
    static Text.Furnace nfont() {checkfont(); return(bnf);}
    // addon: how THIS button's caption was rendered, so it can be re-rendered when the override moves.
    // `rtext` null = the caption came from the caller as a Text/BufferedImage (not ours to restyle).
    // Public since 061: a caption is these THREE fields, and an addon's text level records all three so it can
    // give the stock one back exactly -- see caption(String, Color, int) below.
    public String rtext = null;
    public Color rcol = null;     // non-null -> the plain tfont() path (change(text, col)); null -> the nfont() blur
    public int rwrap = 0;         // >0 -> the renderwrap path (wrapped())
    private int contgen = -1;     // Fonts.gen() at the last caption render
    private int facegen = -1;     // addon: (065.8) ...and at the last RASTER, which the chrome half moves too
    private boolean a = false, dis = false;
    // addon: (065.8) is the pointer on this button? The stock face has no hover state, so nothing tracked one;
    // a rule may give it one, and then the raster is rebuilt on the crossing (IButton.h is the same field).
    private boolean h = false;
    private UI.Grab d = null;
	
    @RName("btn")
    public static class $Btn implements Factory {
	public Widget create(UI ui, Object[] args) {
	    if(args.length > 2)
		return(new Button(UI.scale(Utils.iv(args[0])), (String)args[1], Utils.bv(args[2])));
	    else
		return(new Button(UI.scale(Utils.iv(args[0])), (String)args[1]));
	}
    }
    @RName("ltbtn")
    public static class $LTBtn implements Factory {
	public Widget create(UI ui, Object[] args) {
	    return(wrapped(UI.scale(Utils.iv(args[0])), (String)args[1]));
	}
    }
	
    public static Button wrapped(int w, String text) {
	Button ret = new Button(w, largep(w));   // addon: render below, so the caption can be restyled ("button" scope)
	ret.rtext = text;
	ret.rwrap = w - margin;
	ret.render();
	return(ret);
    }
        
    private static boolean largep(int w) {
	return(w >= (bl.getWidth() + bm.getWidth() + br.getWidth()));
    }

    private Button(int w, boolean lg) {
	super(new Coord(w, lg?hl:hs));
	this.lg = lg;
    }

    public Button(int w, String text, boolean lg, Runnable action) {
	this(w, lg);
	this.rtext = text;   // addon: remember the caption so it can be re-rendered on a "button" font change
	render();            // addon: was `this.text = nf.render(text)` -- now through the provider
	this.action = action;
    }

    // addon: (re)render this button's own caption through the "button" scope provider.
    private void render() {
	if(rwrap > 0)
	    this.text = tfont().renderwrap(rtext, rwrap);
	else if(rcol != null)
	    this.text = tfont().render(rtext, rcol);
	else
	    this.text = nfont().render(rtext);
	this.cont = this.text.img;
	this.contgen = Fonts.gen();
    }

    public Button(int w, String text, boolean lg) {
	this(w, text, lg, null);
	this.action = () -> wdgmsg("activate");
    }

    public Button(int w, String text, Runnable action) {
	this(w, text, largep(w), action);
    }

    public Button(int w, String text) {
	this(w, text, largep(w));
    }

    public Button(int w, Text text) {
	this(w, largep(w));
	this.text = text;
	this.cont = text.img;
    }
	
    public Button(int w, BufferedImage cont) {
	this(w, largep(w));
	this.cont = cont;
    }
	
    public Button action(Runnable action) {
	this.action = action;
	return(this);
    }

    /* addon: (065.8) the "button" rule's own face, in the state this button is in -- null on a stock client,
     * and then the seven statics above are the whole of the button. The two halves answer two different
     * pieces: a bg replaces the centre texture the caption is set on, a border replaces the four edge caps.
     * The caption itself is never the rule's -- it is rasterized between them, which is why the paint is split
     * around super.draw(g) rather than done in one call. */
    private Fonts.Chrome face() {
	return(Fonts.chrome("button", this, dis ? "disabled" : (a ? "pressed" : (h ? "hover" : null))));
    }

    /** addon: (065.8) the box the FRAME occupies -- the whole button, less the ears a large one grows. */
    private Area fbox() {
	int yo = lg?((hl - hs) / 2):0;
	return(Area.sized(Coord.of(0, yo), Coord.of(sz.x, hs)));
    }

    public void draw(BufferedImage img) {
	Graphics g = img.getGraphics();
	int yo = lg?((hl - hs) / 2):0;
	// addon: (065.8) what the rule paints, this button does not: each half is skipped only where a rule
	// answers for it, so a bg-only rule keeps the stock frame and a border-only rule the stock fill.
	Fonts.Chrome face = face();
	boolean fill = (face != null) && face.bg(), frame = (face != null) && face.border();

	if(!fill)
	    g.drawImage(a?dt:ut, UI.scale(4), yo + UI.scale(4), sz.x - UI.scale(8), hs - UI.scale(8), null);

	Coord tc = sz.sub(Utils.imgsz(cont)).div(2);
	if(a)
	    tc = tc.add(UI.scale(1), UI.scale(1));
	g.drawImage(cont, tc.x, tc.y, null);

	if(!frame) {
	    g.drawImage(bl, 0, yo, null);
	    g.drawImage(br, sz.x - br.getWidth(), yo, null);
	    g.drawImage(bt, bl.getWidth(), yo, sz.x - bl.getWidth() - br.getWidth(), bt.getHeight(), null);
	    g.drawImage(bb, bl.getWidth(), yo + hs - bb.getHeight(), sz.x - bl.getWidth() - br.getWidth(), bb.getHeight(), null);
	    if(lg)
		g.drawImage(bm, (sz.x - bm.getWidth()) / 2, 0, null);
	}

	g.dispose();

	if(dis)
	    PUtils.monochromize(img, Color.LIGHT_GRAY);
	facegen = Fonts.gen();   // addon: this raster is the rule's as of this generation, caption and face both
    }
	
    public void change(String text, Color col) {
	this.rtext = text; this.rcol = col; this.rwrap = 0;   // addon:
	render();                                            // addon: was `tf.render(text, col)`
	redraw();
    }

    public void change(String text) {
	this.rtext = text; this.rcol = null; this.rwrap = 0;  // addon:
	render();                                            // addon: was `nf.render(text)`
	redraw();
    }

    // addon: 061 -- put a caption back EXACTLY as it was found. change(String) sets rcol = null and rwrap = 0,
    // so a coloured or a wrapped (ltbtn) caption restored through it comes back rendered wrong; this is the one
    // write that takes all three fields. Re-renders and redraw()s, like change() does, because an SIWidget
    // keeps its old raster otherwise.
    public void caption(String text, Color col, int wrap) {
	this.rtext = text; this.rcol = col; this.rwrap = wrap;
	render();
	redraw();
    }

    // addon: re-render the caption when the "button" font override moves (Fonts.gen()), then rasterize as usual.
    // 065.8: the check is the STYLE's generation rather than the caption's, because a chrome rule changes no
    // text at all and would otherwise leave the raster -- the face the caption sits on -- at the old rule.
    public void draw(GOut g) {
	int gen = Fonts.gen();
	if(facegen != gen) {
	    if((rtext != null) && (contgen != gen))
		render();
	    redraw();
	}
	// addon: (065.8) the rule's own face, painted AROUND this button's own picture: its surface under the
	// caption, its frame over it, which is the order the stock face composes itself in. With a frame of the
	// rule's own the surface fills the whole box; with the client's own still on, it stays inside it,
	// exactly where the centre texture it stands in for is drawn.
	Fonts.Chrome face = face();
	Area f = (face == null) ? null : fbox();
	if(face != null) {
	    if(face.border())
		face.drawbg(g, f.ul, f.sz());
	    else
		face.drawbg(g, f.ul.add(UI.scale(4), UI.scale(4)), f.sz().sub(UI.scale(8), UI.scale(8)));
	}
	super.draw(g);
	if(face != null)
	    face.drawborder(g, f.ul, f.sz());
    }

    public void disable(boolean dis) {
	this.dis = dis;
	redraw();
    }

    public void click() {
	if(action != null)
	    action.run();
    }

    public boolean gkeytype(GlobKeyEvent ev) {
	// addon: 061 -- the Pressed seam, where the client receives the KEY (click() is overridden all over)
	if(AddonWidgets.activate(this, "Pressed", null))
	    click();
	return(true);
    }
    
    public void uimsg(String msg, Object... args) {
	if(msg == "ch") {
	    if(args.length > 1)
		change((String)args[0], (Color)args[1]);
	    else
		change((String)args[0]);
	} else if(msg == "dis") {
	    disable(Utils.bv(args[1]));
	} else {
	    super.uimsg(msg, args);
	}
    }
    
    public void mousemove(MouseMoveEvent ev) {
	super.mousemove(ev);
	boolean in = ev.c.isect(Coord.z, sz);
	// addon: (065.8) the pointer crossing this button's edge is a face change only where a rule gave it one.
	// The flag is kept either way -- it costs a boolean, and a sheet installed while the pointer already
	// rests on a button has to find it true -- but the raster is rebuilt only for a button that is dressed.
	if(in != this.h) {
	    this.h = in;
	    if(face() != null)
		redraw();
	}
	if(d != null) {
	    if(in != this.a) {
		this.a = in;
		redraw();
	    }
	}
    }

    protected void depress() {
	ui.sfx(click);
    }

    protected void unpress() {
	ui.sfx(click);
    }

    public boolean mousedown(MouseDownEvent ev) {
	if((ev.b != 1) || dis)
	    return(super.mousedown(ev));
	a = true;
	d = ui.grabmouse(this);
	depress();
	redraw();
	return(true);
    }
	
    public boolean mouseup(MouseUpEvent ev) {
	if((d != null) && ev.b == 1) {
	    d.remove();
	    d = null;
	    a = false;
	    redraw();
	    if(ev.c.isect(Coord.z, sz)) {
		unpress();
		// addon: 061 -- the Pressed seam, after the grab is released so a handler may cancel, defer or
		// destroy this window without leaving a UI.Grab outstanding, and before the action runs
		if(AddonWidgets.activate(this, "Pressed", null))
		    click();
	    }
	    return(true);
	}
	return(super.mouseup(ev));
    }
}
