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
import java.awt.Font;
import static java.lang.Math.PI;

public class FlowerMenu extends Widget {
    public static final Color pink = new Color(255, 0, 128);
    public static final Color ptc = Color.YELLOW;
    public static final Text.Foundry ptf = new Text.Foundry(Text.dfont, 12);
    // addon: "menu" font scope (F3d, D-043). `ptf` above stays the STOCK foundry; every petal caption goes through
    // the provider instead: Fonts.foundry("menu", ptf) resolves an addon override, or -- when none -- the stock
    // foundry, cascading through "default". Rebuilt lazily whenever Fonts.gen() moves; each open petal re-renders
    // (and re-sizes around its own centre) on the same check (see Petal.draw).
    private static Text.Foundry bptf;
    private static int fontgen = -1;
    /** addon: the current foundry for the {@code "menu"} scope (an override, else stock {@link #ptf}). */
    public static Text.Foundry ptfont() {
	int g = Fonts.gen();
	if((bptf == null) || (fontgen != g)) {
	    Fonts.stock("menu", "font", ptf);   // addon: (065.17) the face a petal's own label is set in
	    bptf = Fonts.foundry("menu", ptf);
	    fontgen = g;
	}
	return(bptf);
    }
    public static final IBox pbox = Window.wbox;
    public static final Tex pbg = Window.bg;
    public static final int ph = UI.scale(30), ppl = 8;
    public Petal[] opts;
    private UI.Grab mg, kg;

    @RName("sm")
    public static class $_ implements Factory {
	public Widget create(UI ui, Object[] args) {
	    String[] opts = new String[args.length];
	    for(int i = 0; i < args.length; i++)
		opts[i] = (String)args[i];
	    return(new FlowerMenu(opts));
	}
    }

    public class Petal extends Widget {
	public String name;
	public double ta, tr;
	public int num;
	public long voiceMuteGob = -1;   // brodgar voice: >=0 marks a client-side Mute/Unmute petal
	private Text text;
	private int textgen = -1;   // addon: Fonts.gen() at the last caption render (F3d)
	private double a = 1;

	public Petal(String name) {
	    super(Coord.z);
	    this.name = name;
	    render();               // addon: was `text = ptf.render(name, ptc)` -- now through the "menu" provider
	}

	/* addon: (re)render this petal's caption through the "menu" scope provider and re-size around its own
	 * CENTRE -- a petal is positioned centre-first (move(Coord)/move(a, r)), and after the opening animation
	 * finishes nothing re-places it, so growing it from the top-left would visibly shift it off its ring. */
	private void render() {
	    Coord mid = (text == null) ? null : c.add(sz.div(2));
	    if(text != null)
		text.dispose();
	    // addon: (102.2) the caption is rendered UNDER "menu", so an addon's catalogue reaches a petal by
	    // name -- and the petal's own `name` is untouched, which is what `s:flowermenu():list()`, the
	    // FlowerMenuRemoved event and picking one by label all go on reading.
	    Fonts.enter("menu");
	    try {
		text = ptfont().render(name, ptc);
	    } finally {
		Fonts.exit();
	    }
	    textgen = Fonts.gen();
	    resize(text.sz().x + UI.scale(25), ph);
	    if(mid != null)
		this.c = mid.sub(sz.div(2));
	}

	public void move(Coord c) {
	    this.c = c.sub(sz.div(2));
	}

	public void move(double a, double r) {
	    move(Coord.sc(a, r));
	}

	public void draw(GOut g) {
	    if(textgen != Fonts.gen())   // addon: re-render the caption when the "menu" font override moves (F3d)
		render();
	    g.chcolor(new Color(255, 255, 255, (int)(255 * a)));
	    IBox box = Fonts.box("panel", this, pbox);   // addon: (035.3) sheet-fed petal chrome, else the stock box
	    if(!Fonts.drawbg(box, g, Coord.z, sz))       // addon: (035.3) a `bg` rule replaces the stock surface...
		g.image(pbg, new Coord(3, 3), new Coord(3, 3), sz.add(new Coord(-6, -6)), UI.scale(pbg.sz()));
	    box.draw(g, Coord.z, sz);                    // addon: (035.3) was pbox.draw(...)
	    g.image(text.tex(), sz.div(2).sub(text.sz().div(2)));
	}

	public boolean mousedown(MouseDownEvent ev) {
	    choose(this);
	    return(true);
	}

	public Area ta(Coord tc) {
	    return(Area.sized(tc.sub(sz.div(2)), sz));
	}

	public Area ta(double a, double r) {
	    return(ta(Coord.sc(a, r)));
	}
    }

    private static double nxf(double a) {
	return(-1.8633 * a * a + 2.8633 * a);
    }

    public class Opening extends NormAnim {
	Opening() {super(0.25);}
	
	public void ntick(double s) {
	    double ival = 0.8;
	    double off = (opts.length == 1) ? 0.0 : ((1.0 - ival) / (opts.length - 1));
	    for(int i = 0; i < opts.length; i++) {
		Petal p = opts[i];
		double a = Utils.clip((s - (off * i)) * (1.0 / ival), 0, 1);
		double b = nxf(a);
		p.move(p.ta + ((1 - b) * PI), p.tr * b);
		p.a = a;
	    }
	}
    }

    public class Chosen extends NormAnim {
	Petal chosen;
		
	Chosen(Petal c) {
	    super(0.75);
	    chosen = c;
	}
		
	public void ntick(double s) {
	    double ival = 0.8;
	    double off = ((1.0 - ival) / (opts.length - 1));
	    for(int i = 0; i < opts.length; i++) {
		Petal p = opts[i];
		if(p == chosen) {
		    if(s > 0.6) {
			p.a = 1 - ((s - 0.6) / 0.4);
		    } else if(s < 0.3) {
			double a = nxf(s / 0.3);
			p.move(p.ta, p.tr * (1 - a));
		    }
		} else {
		    if(s > 0.3) {
			p.a = 0;
		    } else {
			double a = s / 0.3;
			a = Utils.clip((a - (off * i)) * (1.0 / ival), 0, 1);
			p.a = 1 - a;
		    }
		}
	    }
	    if(s == 1.0)
		ui.destroy(FlowerMenu.this);
	}
    }

    public class Cancel extends NormAnim {
	Cancel() {super(0.25);}

	public void ntick(double s) {
	    double ival = 0.8;
	    double off = (opts.length == 1) ? 0.0 : ((1.0 - ival) / (opts.length - 1));
	    for(int i = 0; i < opts.length; i++) {
		Petal p = opts[i];
		double a = Utils.clip((s - (off * i)) * (1.0 / ival), 0, 1);
		double b = 1.0 - nxf(1.0 - a);
		p.move(p.ta + (b * PI), p.tr * (1 - b));
		p.a = 1 - a;
	    }
	    if(s == 1.0)
		ui.destroy(FlowerMenu.this);
	}
    }

    private void organize(Petal[] opts) {
	Area bounds = parent.area().xl(c.inv());
	int l = 1, p = 0, i = 0, mp = 0, ml = 1, t = 0, tt = -1;
	boolean muri = false;
	while(i < opts.length) {
	    place: {
		double ta = (PI / 2) - (p * (2 * PI / (l * ppl)));
		double tr = UI.scale(75) + (UI.scale(50) * (l - 1));
		if(!muri && !bounds.contains(opts[i].ta(ta, tr))) {
		    if(tt < 0) {
			tt = ppl * l;
			t = 1;
			mp = p;
			ml = l;
		    } else if(++t >= tt) {
			muri = true;
			p = mp;
			l = ml;
			continue;
		    }
		    break place;
		}
		tt = -1;
		opts[i].ta = ta;
		opts[i].tr = tr;
		i++;
	    }
	    if(++p >= (ppl * l)) {
		l++;
		p = 0;
	    }
	}
    }

    public FlowerMenu(String... options) {
	super(Coord.z);
	opts = new Petal[options.length];
	for(int i = 0; i < options.length; i++) {
	    add(opts[i] = new Petal(options[i]));
	    opts[i].num = i;
	}
    }

    protected void added() {
	if(c.equals(-1, -1))
	    c = parent.ui.lcc;
	mg = ui.grabmouse(this);
	kg = ui.grabkeys(this);
	addVoicePetal();
	organize(opts);
	new Opening().ntick(0);
	io.brodgar.addon.AddonManager.flowerOpened(this);   // addon: 047.1 -> FlowerMenuAdded. HERE, at the END: addVoicePetal above REPLACES `opts`, so this is the only point the petal set is complete.
    }

    // brodgar voice: append a client-side Mute/Unmute petal when a player was just clicked.
    private void addVoicePetal() {
	long gob = VoiceTarget.recent();
	if(gob < 0)
	    return;
	String label = io.brodgar.voice.Voice.isPlayerMuted(gob) ? "Unmute voice" : "Mute voice";
	Petal p = add(new Petal(label));
	p.num = opts.length;
	p.voiceMuteGob = gob;
	Petal[] na = new Petal[opts.length + 1];
	System.arraycopy(opts, 0, na, 0, opts.length);
	na[opts.length] = p;
	opts = na;
    }

    public boolean mousedown(MouseDownEvent ev) {
	if(!anims.isEmpty())
	    return(true);
	/* addon: (116.2) a hidden ring is one the pointer cannot be over. Nothing above here tests
	 * visibility: a grab is checked before the tree and reaches its owner whatever that widget's own
	 * flag says, and only PointerEvent.propagation tests one -- on the CHILD it steps into. The Petals
	 * are themselves still visible, so without this a press on an unpainted ring's own footprint fires
	 * whatever petal was under it: an invisible ring spending a click on Chop. choose(null) is the door
	 * Esc and a click away already share, so a ring an addon hid ends exactly as an unwanted one does,
	 * and the press is still eaten -- the ring holds the mouse, drawn or not. */
	if(!visible()) {
	    choose(null);
	    return(true);
	}
	if(!ev.propagate(this))
	    choose(null);
	return(true);
    }

    public void uimsg(String msg, Object... args) {
	if(msg == "cancel") {
	    new Cancel();
	    mg.remove();
	    kg.remove();
	    io.brodgar.addon.AddonManager.flowerClosed(this, null);   // addon: 047.1 -> FlowerMenuRemoved with nothing chosen -- unless choose() recorded a CLIENT-SIDE petal, which cancels the server's menu and handles itself.
	} else if(msg == "act") {
	    int num = Utils.iv(args[0]);   // addon: 047.1 -- was inline; the seam below needs the same index
	    new Chosen(opts[num]);
	    mg.remove();
	    kg.remove();
	    io.brodgar.addon.AddonManager.flowerClosed(this, opts[num].name);   // addon: 047.1 -> FlowerMenuRemoved with the label the server committed. This branch is also the one seam a CLIENT-SIDE menu takes (BuddyWnd calls uimsg by hand).
	}
    }

    public void draw(GOut g) {
	super.draw(g, false);
    }

    public boolean keydown(KeyDownEvent ev) {
	if((ev.c >= '0') && (ev.c <= '9')) {
	    /* addon: (116.2) the same rule for the keyboard: a digit picks nothing on a ring nobody can
	     * see. The key is still eaten, because the ring holds the keyboard hidden or not, and key_esc
	     * below is deliberately untouched -- it is the player's own way out of a ring an addon hid and
	     * then did not decide. */
	    if(!visible())
		return(true);
	    int opt = (ev.c == '0') ? 9 : (ev.c - '1');
	    if(opt < opts.length) {
		choose(opts[opt]);
		kg.remove();
	    }
	    return(true);
	} else if(key_esc.match(ev)) {
	    choose(null);
	    kg.remove();
	    return(true);
	}
	return(super.keydown(ev));
    }

    public void choose(Petal option) {
	io.brodgar.addon.AddonManager.flowerChoosing(this, option);   // addon: 047.1 -- record the petal, so the client-side voice petal (which cancels the server's menu) closes carrying its own label instead of reading as "nothing chosen"
	if(option != null && option.voiceMuteGob >= 0) {   // brodgar voice: client-side petal, handled locally
	    io.brodgar.voice.Voice.togglePlayerMuted(option.voiceMuteGob);
	    wdgmsg("cl", -1);   // cancel the server's menu; no server petal was chosen
	    return;
	}
	if(option == null) {
	    wdgmsg("cl", -1);
	} else {
	    wdgmsg("cl", option.num, ui.modflags());
	}
    }

    /* addon: 047.1 -- the FALLBACK seam. The two uimsg branches above are the commit points, but a menu can
     * also just die (a relog, a server destroy), and then nothing has been committed. Firing here keeps
     * "every FlowerMenuAdded is followed by exactly ONE FlowerMenuRemoved" true either way: the seam is
     * one-shot per open, so whichever of the three gets here first is the one that fires. BuddyWnd's
     * subclass overrides destroy() and DOES call super, so a client-side menu is covered too. */
    public void destroy() {
	io.brodgar.addon.AddonManager.flowerClosed(this, null);
	super.destroy();
    }
}
