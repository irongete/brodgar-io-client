/* Preprocessed source code */
/* -*- Java -*- */

package haven.res.ui.land;

import haven.*;
import haven.render.*;
import java.util.*;
import java.awt.Color;

/*
 * >wdg: Landwindow
 */
/* addon: local copy of the resource's own source (`haven.Resource get-code ui/land`), adopted for ONE line:
 * the claim's permission table, which the published source sizes at the eight groups that have a COLOUR
 * while the group space is 0..254. The window keys everything off that index -- `updflags` reads it, the
 * "shared" wdgmsg a permission box sends carries it, and the "shared" uimsg writes it back -- so with the
 * eight-long array a group above the eighth threw ArrayIndexOutOfBoundsException out of the selector's own
 * `changed` hook, and a claim could grant nothing to one. The array answers for the whole space instead,
 * exactly as `BuddyWnd.gc` does and for the same reason: every reader here indexes it bare.
 *   The @FromResource line above is what makes the engine prefer this copy, and it is version-PINNED: when
 * the server publishes a newer `ui/land` this file is ignored and the served eight-long one runs again. */
@haven.FromResource(name = "ui/land", version = 51)
public class Landwindow extends Window {
    public static final int width = UI.scale(300);
    Widget bn, be, bs, bw, refill, buy, reset, dst, rebond;
    BuddyWnd.GroupSelector group;
    Label area, cost;
    Widget authmeter;
    int auth, acap, adrain;
    boolean offline;
    Area ca, sa;
    MCache.Overlay ol;
    MCache map;
    int bflags[] = new int[255];   // addon: the whole group space, not the eight with a colour
    PermBox perms[] = new PermBox[4];
    CheckBox homeck;
    double power;
    private static final String fmt = "Area: %d m" + ((char)0xB2);

    public static Widget mkwidget(UI ui, Object... args) {
	Coord c1 = (Coord)args[0];
	Coord c2 = (Coord)args[1];
	return(new Landwindow(new Area(c1, c2.add(1, 1))));
    }

    private void fmtarea() {
	area.settext(String.format(fmt, ca.area()));
    }

    private void updatecost() {
	cost.settext(String.format("Cost: %d", 10 * (sa.area() - ca.area())));
    }

    private void updflags() {
	int fl = bflags[group.group];
	for(PermBox w : perms)
	    w.a = (fl & w.fl) != 0;
    }

    private class PermBox extends CheckBox {
	int fl;
	
	PermBox(String lbl, int fl) {
	    super(lbl);
	    this.fl = fl;
	}
	
	public void changed(boolean val) {
	    int fl = 0;
	    for(PermBox w : perms) {
		if(w.a)
		    fl |= w.fl;
	    }
	    Landwindow.this.wdgmsg("shared", group.group, fl);
	    bflags[group.group] = fl;
	}
    }

    private Tex rauth = null;

    public Landwindow(Area ca) {
	super(new Coord(0, 0), "Stake", true);
	this.sa = this.ca = ca;
	Widget prev = area = add(new Label(""), Coord.z);
	fmtarea();
	prev = authmeter = add(new Widget(new Coord(width, UI.scale(20))) {
		public void draw(GOut g) {
		    int auth = Landwindow.this.auth;
		    int acap = Landwindow.this.acap;
		    if(acap > 0) {
			g.chcolor(0, 0, 0, 255);
			g.frect(Coord.z, sz);
			g.chcolor(128, 0, 0, 255);
			Coord isz = sz.sub(2, 2);
			isz.x = (auth * isz.x) / acap;
			g.frect(new Coord(1, 1), isz);
			g.chcolor();
			if(rauth == null) {
			    Color col = offline ? Color.RED : Color.WHITE;
			    rauth = new TexI(Utils.outline2(Text.render(String.format("%s/%s", auth, acap), col).img, Utils.contrast(col)));
			}
			g.aimage(rauth, sz.div(2), 0.5, 0.5);
		    }
		}
	    }, prev.pos("bl").adds(0, 5)).settip(TT_AUTH, true);
	prev = refill = add(new Button(UI.scale(140), "Refill"), prev.pos("bl").adds(0, 5));
	refill.settip(TT_REFILL, true);
	prev = cost = add(new Label("Cost: 0"), prev.pos("bl").adds(0, 5));
	int y = addhl(prev.pos("bl").adds(0, 10), width,
		      bn = new Button(UI.scale(120), "Extend North"));
	int sd = Button.hl - Button.hs;
	y = addhl(new Coord(0, y + UI.scale(2) - sd), width,
		  bw = new Button(UI.scale(120), "Extend West"),
		  be = new Button(UI.scale(120), "Extend East"));
	y = addhl(new Coord(0, y + UI.scale(2) - sd), width,
		  bs = new Button(UI.scale(120), "Extend South"));
	y = addhl(new Coord(0, y + UI.scale(10)), width,
		  buy = new Button(UI.scale(140), "Buy"),
		  reset = new Button(UI.scale(140), "Reset"));
	y = addhl(new Coord(0, y + UI.scale(2)), width,
		  dst = new Button(UI.scale(140), "Declaim"),
		  rebond = new Button(UI.scale(140), "Renew bond"));
	rebond.settip(TT_REBOND, true);
	prev = add(new Label("Assign permissions to memorized people:"), 0, y + UI.scale(15));
	group = add(new BuddyWnd.GroupSelector(0) {
		protected void changed(int g) {
		    super.changed(g);
		    updflags();
		}
	    }, prev.pos("bl").adds(0, 2));
	prev = perms[0] = add(new PermBox("Trespassing", 1), group.pos("bl").adds(0, 5).xs(10));
	prev = perms[3] = add(new PermBox("Rummaging", 8), prev.pos("bl").adds(0, 2));
	prev = perms[1] = add(new PermBox("Theft", 2), prev.pos("bl").adds(0, 2));
	prev = perms[2] = add(new PermBox("Vandalism", 4), prev.pos("bl").adds(0, 2));
	prev = add(new Label("White permissions also apply to non-memorized people."), prev.pos("bl").adds(0, 5).x(0));
	pack();
    }

    public static final MCache.OverlayInfo selol = new MCache.OverlayInfo() {
	    final Material mat = new Material(new BaseColor(0, 255, 0, 32), States.maskdepth);
	    final Material omat = new Material(new BaseColor(0, 255, 0, 128), States.maskdepth);

	    public Collection<String> tags() {
		return(Arrays.asList("show"));
	    }

	    public Material mat() {return(mat);}
	    public Material omat() {return(omat);}
	};

    protected void added() {
	super.added();
	map = ui.sess.glob.map;
	MapView mv = getparent(GameUI.class).map;
	mv.enol("cplot");
	ol = map.new Overlay(sa, selol);
    }

    public void destroy() {
	MapView mv = getparent(GameUI.class).map;
	mv.disol("cplot");
	ol.destroy();
	super.destroy();
    }

    public void uimsg(String msg, Object... args) {
	if(msg == "upd") {
	    Coord c1 = (Coord)args[0];
	    Coord c2 = (Coord)args[1];
	    this.ca = new Area(c1, c2.add(1, 1));
	    fmtarea();
	    updatecost();
	} else if(msg == "shared") {
	    int g = (Integer)args[0];
	    int fl = (Integer)args[1];
	    bflags[g] = fl;
	    if(g == group.group)
		updflags();
	} else if(msg == "auth") {
	    auth = (Integer)args[0];
	    acap = (Integer)args[1];
	    adrain = (Integer)args[2];
	    offline = (Integer)args[3] != 0;
	    rauth = null;
	} else if(msg == "entime") {
	    int entime = (Integer)args[0];
	    authmeter.tooltip = Text.render(String.format("%d:%02d until enabled", entime / 3600, (entime % 3600) / 60));
	} else if(msg == "ppower") {
	    power = ((Number)args[0]).doubleValue() * 0.01;
	} else {
	    super.uimsg(msg, args);
	}
    }

    public void wdgmsg(Widget sender, String msg, Object... args) {
	if(sender == bn) {
	    sa = new Area(sa.ul.sub(0, 1), sa.br);
	    ol.update(sa);
	    updatecost();
	    return;
	} else if(sender == be) {
	    sa = new Area(sa.ul, sa.br.add(1, 0));
	    ol.update(sa);
	    updatecost();
	    return;
	} else if(sender == bs) {
	    sa = new Area(sa.ul, sa.br.add(0, 1));
	    ol.update(sa);
	    updatecost();
	    return;
	} else if(sender == bw) {
	    sa = new Area(sa.ul.sub(1, 0), sa.br);
	    ol.update(sa);
	    updatecost();
	    return;
	} else if(sender == buy) {
	    wdgmsg("take", sa.ul, sa.br.sub(1, 1));
	    return;
	} else if(sender == reset) {
	    ol.update(sa = ca);
	    updatecost();
	    return;
	} else if(sender == dst) {
	    wdgmsg("declaim");
	    return;
	} else if(sender == rebond) {
	    wdgmsg("bond");
	    return;
	} else if(sender == refill) {
	    wdgmsg("refill");
	    return;
	}
	super.wdgmsg(sender, msg, args);
    }

    private static final String TT_AUTH = "$i{Presence}: A claim that is out of Presence no longer upholds any of its protections. It is refilled by earning Learning Points with the claim's Bond in your character's Study.";
    private static final String TT_REFILL = "Refill this claim's presence immediately from your current pool of learning points.";
    private static final String TT_REBOND = "Create a new bond for this claim, destroying the old one. Costs half of this claim's total presence.";
}
