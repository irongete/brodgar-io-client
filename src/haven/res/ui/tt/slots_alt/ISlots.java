/* Preprocessed source code */
package haven.res.ui.tt.slots_alt;

import haven.*;
import static haven.PUtils.*;
import java.awt.image.*;
import java.awt.Graphics;
import java.awt.Font;
import java.awt.Color;
import java.util.*;

/* >tt: Fac */
@haven.FromResource(name = "ui/tt/slots-alt", version = 4)
public class ISlots extends ItemInfo.Tip implements GItem.NumberInfo {
    /* addon: (F3d, D-043) the ONE row a font override could not reach. Stock rasterises this heading into a
     * `static final Text` when the CLASS is loaded, and layout() only blits its finished pixels -- nothing re-runs a
     * static initialiser and the JDK forbids replacing a static final, so the "tooltip" scope had no way in. This is
     * a LOCAL COPY of the resource's own code (doc/resource-code; see the @FromResource annotation above), so the
     * fix is simply to render the heading ON DEMAND: ch() re-renders whenever Fonts.gen() moves, and since a
     * tooltip composition already declares the "tooltip" scope (ItemInfo.longtip), the plain Text.render below
     * resolves it. Cached on the SCOPE as well as the generation, so a render made outside a composition is never
     * served inside one. `ch` is kept as the stock-rendered field so any other caller still compiles. */
    public static final Text ch = Text.render("Gilding:");
    private static Text bch = null;
    private static int chgen = -1;
    private static String chscope = null;
    public static Text ch() {
	int g = Fonts.gen();
	String sc = Fonts.scope();
	if((bch == null) || (chgen != g) || !sc.equals(chscope)) {
	    bch = Text.render("Gilding:");
	    chgen = g;
	    chscope = sc;
	}
	return(bch);
    }
    public static final Color fcol = new Color(0, 169, 224), scol = new Color(255, 192, 64);
    public static final Text.Foundry progf = new Text.Foundry(Text.dfont.deriveFont(Font.ITALIC), 10);
    public final Collection<SItem> s = new ArrayList<SItem>();
    public final int uses, used;
    public final double pmin, pmax;
    public final Resource[] attrs;
    public final boolean ignol;

    public ISlots(Owner owner, int uses, int used, double pmin, double pmax, Resource[] attrs) {
	super(owner);
	this.uses = uses;
	this.used = used;
	this.pmin = pmin;
	this.pmax = pmax;
	this.attrs = attrs;
	// XXX? Should the format be changed instead?
	ignol = owner.fcontext(MenuGrid.class, false) != null;
    }

    public static final String chc = "192,192,255";
    public void layout(Layout l) {
	l.cmp.add(ch().img, new Coord(0, l.cmp.sz.y));   // addon: (F3d) was `ch.img` -- rendered at class init
	if(attrs.length > 0) {
	    BufferedImage head = RichText.render(String.format("Chance: $col[%s]{%d%%} to $col[%s]{%d%%}", chc, Math.round(100 * pmin), chc, Math.round(100 * pmax)), 0).img;
	    int h = head.getHeight();
	    int x = UI.scale(10), y = l.cmp.sz.y;
	    l.cmp.add(head, new Coord(x, y));
	    x += head.getWidth() + UI.scale(10);
	    for(int i = 0; i < attrs.length; i++) {
		BufferedImage icon = convolvedown(attrs[i].layer(Resource.imgc).img, new Coord(h, h), CharWnd.iconfilter);
		l.cmp.add(icon, new Coord(x, y));
		x += icon.getWidth() + UI.scale(2);
	    }
	} else {
	    BufferedImage head = RichText.render(String.format("Chance: $col[%s]{%d%%}", chc, (int)Math.round(100 * pmin)), 0).img;
	    l.cmp.add(head, new Coord(UI.scale(10), l.cmp.sz.y));
	}
	for(SItem si : s)
	    si.layout(l);
	if(used < uses)
	    l.cmp.add(progf.render(String.format("Gildable (%d/%d)", uses - used, uses), fcol).img, new Coord(UI.scale(10), l.cmp.sz.y));
	else
	    l.cmp.add(progf.render(String.format("Not further gildable (0/%d)", uses), scol).img, new Coord(UI.scale(10), l.cmp.sz.y));
    }

    public static final Object[] defn = {Loading.waitfor(Resource.classres(ISlots.class).pool.load("ui/tt/defn", 7))};
    public class SItem {
	public final Resource res;
	public final GSprite spr;
	public final List<ItemInfo> info;
	public final String name;

	public SItem(ResData sdt, Object[] raw) {
	    this.res = sdt.res.get();
	    ItemSpec spec1 = new ItemSpec(owner, sdt, Utils.extend(new Object[] {defn}, raw));
	    this.spr = spec1.spr();
	    this.name = spec1.name();
	    ItemSpec spec2 = new ItemSpec(owner, sdt, raw);
	    this.info = spec2.info();
	}

	private BufferedImage img() {
	    if(spr instanceof GSprite.ImageSprite)
		return(((GSprite.ImageSprite)spr).image());
	    return(res.layer(Resource.imgc).img);
	}

	public void layout(Layout l) {
	    int h = ch().sz().y;   // addon: (F3d) the row height follows the heading it aligns with
	    BufferedImage icon = PUtils.convolvedown(img(), Coord.of(h), CharWnd.iconfilter);
	    BufferedImage lbl = Text.render(name).img;
	    BufferedImage sub = longtip(info);
	    int x = UI.scale(10), y = l.cmp.sz.y;
	    l.cmp.add(icon, new Coord(x, y));
	    l.cmp.add(lbl, new Coord(x + h + UI.scale(3), y + ((h - lbl.getHeight()) / 2)));
	    if(sub != null)
		l.cmp.add(sub, new Coord(x + h, y + h));
	}
    }

    public int order() {
	return(200);
    }

    public int itemnum() {
	return(s.size());
    }

    public static final Color avail = new Color(128, 192, 255);
    public Color numcolor() {
	return((used < uses) ? avail : Color.WHITE);
    }

    public void drawoverlay(GOut g, Tex tex) {
	if(!ignol)
	    GItem.NumberInfo.super.drawoverlay(g, tex);
    }
}
