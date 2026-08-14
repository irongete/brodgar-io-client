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
import java.lang.reflect.*;
import java.awt.image.BufferedImage;
import java.awt.Graphics;

public abstract class ItemInfo {
    public final Owner owner;

    public interface Owner extends OwnerContext {
	public List<ItemInfo> info();
    }

    public interface ResOwner extends Owner {
	public Resource resource();
    }

    public interface SpriteOwner extends ResOwner {
	public GSprite sprite();
    }

    public static class Raw {
	public static final Raw nil = new Raw(new Object[0], 0);
	public final Object[] data;
	public final double time;

	public Raw(Object[] data, double time) {
	    this.data = data;
	    this.time = time;
	}

	public Raw(Object[] data) {
	    this(data, Utils.rtime());
	}
    }

    @Resource.PublishedCode(name = "tt", instancer = FactMaker.class)
    public static interface InfoFactory {
	public ItemInfo build(Owner owner, Raw raw, Object... args);
    }

    public static class FactMaker extends Resource.PublishedCode.Instancer.Chain<InfoFactory> {
	public FactMaker() {super(InfoFactory.class);}
	{
	    add(new Direct<>(InfoFactory.class));
	    add(new StaticCall<>(InfoFactory.class, "mkinfo", ItemInfo.class, new Class<?>[] {Owner.class, Object[].class},
				 (make) -> new InfoFactory() {
					 public ItemInfo build(Owner owner, Raw raw, Object... args) {
					     return(make.apply(new Object[]{owner, args}));
					 }
				     }));
	    add(new StaticCall<>(InfoFactory.class, "mkinfo", ItemInfo.class, new Class<?>[] {Owner.class, Raw.class, Object[].class},
				 (make) -> new InfoFactory() {
					 public ItemInfo build(Owner owner, Raw raw, Object... args) {
					     return(make.apply(new Object[]{owner, raw, args}));
					 }
				     }));
	    add(new Construct<>(InfoFactory.class, ItemInfo.class, new Class<?>[] {Owner.class, Object[].class},
				(cons) -> new InfoFactory() {
					public ItemInfo build(Owner owner, Raw raw, Object... args) {
					    return(cons.apply(new Object[] {owner, args}));
					}
				    }));
	    add(new Construct<>(InfoFactory.class, ItemInfo.class, new Class<?>[] {Owner.class, Raw.class, Object[].class},
				(cons) -> new InfoFactory() {
					public ItemInfo build(Owner owner, Raw raw, Object... args) {
					    return(cons.apply(new Object[] {owner, raw, args}));
					}
				    }));
	}
    }

    public ItemInfo(Owner owner) {
	this.owner = owner;
    }

    public static class Layout {
	public final Owner owner;
	public final CompImage cmp = new CompImage();
	public int width = 0;
	private final List<Tip> tips = new ArrayList<>();
	private final Map<TipID, Tip> itab = new HashMap<>();

	public Layout(Owner owner) {
	    this.owner = owner;
	}

	public interface TipID<T extends Tip> {
	    public T make(Owner owner);
	}

	@SuppressWarnings("unchecked")
	public <T extends Tip> T intern(TipID<T> id) {
	    T ret = (T)itab.get(id);
	    if(ret == null) {
		itab.put(id, ret = id.make(owner));
		add(ret);
	    }
	    return(ret);
	}

	public void add(Tip tip) {
	    tips.add(tip);
	    tip.prepare(this);
	}

	public BufferedImage render() {
	    Collections.sort(tips, new Comparator<Tip>() {
		    public int compare(Tip a, Tip b) {
			return(a.order() - b.order());
		    }
		});
	    for(Tip tip : tips)
		tip.layout(this);
	    return(cmp.compose());
	}
    }

    public static abstract class Tip extends ItemInfo {
	public Tip(Owner owner) {
	    super(owner);
	}

	public BufferedImage tipimg() {return(null);}
	public BufferedImage tipimg(int w) {return(tipimg());}
	public Tip shortvar() {return(null);}
	public void prepare(Layout l) {}
	public void layout(Layout l) {
	    BufferedImage t = tipimg(l.width);
	    if(t != null)
		l.cmp.add(t, new Coord(0, l.cmp.sz.y));
	}
	public int order() {return(100);}
    }

    /* addon: the "tooltip" font scope (F3d, D-043). ItemInfo is the client's tooltip ENGINE -- longtip/shorttip
     * compose every item, buff, meter, craft-spec, minimap-object, character-attribute and action-menu tooltip out
     * of these Tips -- so the plain-text ones render through Fonts.foundry("tooltip", Text.std) (an override, else
     * stock Text.std, cascading through "default") and the rich ones through Widget.tipfoundry(). Each Tip that
     * cached its rendering at construction now remembers the source string and re-renders when Fonts.gen() moves;
     * the widgets that cache the composed tooltip IMAGE re-compose on the same check (WItem, Buff, LayerMeter,
     * Makewindow, MiniMap, CharWnd, MenuGrid). */
    private static Text.Foundry tipfnd() {
	Fonts.stock("tooltip", "font", Text.std);   // addon: (065.17) the face every plain tip is set in
	return(Fonts.foundry("tooltip", Text.std));
    }

    public static class AdHoc extends Tip {
	public Text str;               // addon: non-final -- re-rendered on a "tooltip" font change
	private final String rtext;    // addon: the recipe
	private int fontgen;           // addon: Fonts.gen() at the last render

	public AdHoc(Owner owner, String str) {
	    super(owner);
	    this.rtext = str;
	    this.str = tipfnd().render(str);   // addon: was Text.render(str)
	    this.fontgen = Fonts.gen();        // addon:
	}

	public BufferedImage tipimg() {
	    if(fontgen != Fonts.gen()) {       // addon: re-render when the "tooltip" override moves (F3d)
		fontgen = Fonts.gen();
		str = tipfnd().render(rtext);
	    }
	    return(str.img);
	}
    }

    public static class Name extends Tip {
	public Text str;               // addon: non-final -- re-rendered on a "tooltip" font change
	private final String rtext;    // addon: the recipe; null = the caller supplied a rendered Text (left alone)
	private int fontgen;           // addon: Fonts.gen() at the last render

	public Name(Owner owner, Text str) {
	    super(owner);
	    this.str = str;
	    this.rtext = null;         // addon: caller-supplied face -- not ours to restyle (as in Button, F3b)
	    this.fontgen = Fonts.gen();
	}

	public Name(Owner owner, String str) {
	    super(owner);
	    this.rtext = str;                  // addon: remember the recipe...
	    this.str = tipfnd().render(str);   // addon: ...and render through the provider (was Text.render(str))
	    this.fontgen = Fonts.gen();
	}

	public BufferedImage tipimg() {
	    checkfont();   // addon:
	    return(str.img);
	}

	/* addon: re-render this name when the "tooltip" override moves (F3d). Also called from shortvar()'s tip, so
	 * the short (name-only) variant follows too. */
	private void checkfont() {
	    if((rtext != null) && (fontgen != Fonts.gen())) {
		fontgen = Fonts.gen();
		str = tipfnd().render(rtext);
	    }
	}

	public int order() {return(0);}

	public Tip shortvar() {
	    return(new Tip(owner) {
		    public BufferedImage tipimg() {checkfont(); return(str.img);}   // addon: (F3d)
		    public int order() {return(0);}
		});
	}

	public static interface Dynamic {
	    public String name();
	}

	@Resource.PublishedCode.Builtin(type = InfoFactory.class, name = "defn")
	public static class Default implements InfoFactory {
	    public static String get(Owner owner) {
		if(owner instanceof Dynamic)
		    return(((Dynamic)owner).name());
		if(owner instanceof SpriteOwner) {
		    GSprite spr = ((SpriteOwner)owner).sprite();
		    if(spr instanceof Dynamic)
			return(((Dynamic)spr).name());
		}
		if(!(owner instanceof ResOwner))
		    return(null);
		Resource res = ((ResOwner)owner).resource();
		Resource.Tooltip tt = res.layer(Resource.tooltip);
		if(tt == null)
		    throw(new RuntimeException("Item resource " + res + " is missing default tooltip"));
		return(tt.t);
	    }

	    public ItemInfo build(Owner owner, Raw raw, Object... args) {
		String nm = get(owner);
		return((nm == null) ? null : new Name(owner, nm));
	    }
	}
    }

    public static class Pagina extends Tip {
	public final RichText.Document doc;

	public Pagina(Owner owner, RichText.Document doc) {
	    super(owner);
	    this.doc = doc;
	}
	public Pagina(Owner owner, String str) {
	    this(owner, new RichText.Document(str));
	}

	public BufferedImage tipimg(int w) {
	    return(Widget.tipfoundry().render(doc, w).img);   // addon: the "tooltip" scope (F3d)
	}

	public void layout(Layout l) {
	    BufferedImage t = tipimg((l.width == 0) ? Math.max(UI.scale(200), l.cmp.sz.x) : l.width);
	    if(t != null)
		l.cmp.add(t, new Coord(0, l.cmp.sz.y + UI.scale(10)));
	}

	public int order() {return(10000);}
    }

    public static class Contents extends Tip {
	public final List<ItemInfo> sub;
	// addon: was a class-init `static final Text.Line` -- baked at class load, it could never follow an override
	// (the F3e lesson), so it is rendered per tooltip now (only when you hover a container).
	private static Text.Line ch() {return(tipfnd().render("Contents:"));}
	
	public Contents(Owner owner, List<ItemInfo> sub) {
	    super(owner);
	    this.sub = sub;
	}
	
	public BufferedImage tipimg() {
	    BufferedImage stip = longtip(sub);
	    BufferedImage img = TexI.mkbuf(Coord.of(stip.getWidth(), stip.getHeight()).add(UI.scale(10, 15)));
	    Graphics g = img.getGraphics();
	    g.drawImage(ch().img, 0, 0, null);   // addon: (F3d)
	    g.drawImage(stip, UI.scale(10), UI.scale(15), null);
	    g.dispose();
	    return(img);
	}

	public Tip shortvar() {
	    return(new Tip(owner) {
		    public BufferedImage tipimg() {return(shorttip(sub));}
		    public int order() {return(100);}
		});
	}
    }

    public static BufferedImage catimgs(int margin, BufferedImage... imgs) {
	int w = 0, h = -margin;
	for(BufferedImage img : imgs) {
	    if(img == null)
		continue;
	    if(img.getWidth() > w)
		w = img.getWidth();
	    h += img.getHeight() + margin;
	}
	BufferedImage ret = TexI.mkbuf(new Coord(w, h));
	Graphics g = ret.getGraphics();
	int y = 0;
	for(BufferedImage img : imgs) {
	    if(img == null)
		continue;
	    g.drawImage(img, 0, y, null);
	    y += img.getHeight() + margin;
	}
	g.dispose();
	return(ret);
    }

    public static BufferedImage catimgsh(int margin, BufferedImage... imgs) {
	int w = -margin, h = 0;
	for(BufferedImage img : imgs) {
	    if(img == null)
		continue;
	    if(img.getHeight() > h)
		h = img.getHeight();
	    w += img.getWidth() + margin;
	}
	BufferedImage ret = TexI.mkbuf(new Coord(w, h));
	Graphics g = ret.getGraphics();
	int x = 0;
	for(BufferedImage img : imgs) {
	    if(img == null)
		continue;
	    g.drawImage(img, x, (h - img.getHeight()) / 2, null);
	    x += img.getWidth() + margin;
	}
	g.dispose();
	return(ret);
    }

    /* addon: composing a tooltip is declared to the font provider (F3d, D-043), so that text rendered from here
     * down through the generic Text.render / RichText.render statics resolves the "tooltip" scope instead of
     * "default". That is the ONLY way to reach the rows drawn by PUBLISHED CODE -- classes that ship inside the
     * resources themselves (`ui/tt/q/qbuff` draws "Quality: 31.7", `ui/tt/wear`, `ui/tt/attrmod`, ...) and render
     * through those statics; we cannot edit them, and they need no cooperation. Text rendered outside a
     * composition is unaffected. */
    public static BufferedImage longtip(List<ItemInfo> info) {
	if(info.isEmpty())
	    return(null);
	Fonts.enter("tooltip");   // addon:
	try {
	    Layout l = new Layout(info.get(0).owner);
	    for(ItemInfo ii : info) {
		if(ii instanceof Tip) {
		    Tip tip = (Tip)ii;
		    l.add(tip);
		}
	    }
	    if(l.tips.size() < 1)
		return(null);
	    return(l.render());
	} finally {
	    Fonts.exit();   // addon:
	}
    }

    public static BufferedImage shorttip(List<ItemInfo> info) {
	Fonts.enter("tooltip");   // addon: shortvar() itself may render (F3d) -- longtip() enters again, it nests
	try {
	    List<ItemInfo> sinfo = new ArrayList<>();
	    for(ItemInfo ii : info) {
		if(ii instanceof Tip) {
		    Tip tip = ((Tip)ii).shortvar();
		    if(tip != null)
			sinfo.add(tip);
		}
	    }
	    return(longtip(sinfo));
	} finally {
	    Fonts.exit();   // addon:
	}
    }

    public static <T> T find(Class<T> cl, List<ItemInfo> il) {
	for(ItemInfo inf : il) {
	    if(cl.isInstance(inf))
		return(cl.cast(inf));
	}
	return(null);
    }

    public static List<ItemInfo> buildinfo(Owner owner, Raw raw) {
	Fonts.enter("tooltip");   // addon: a Tip (ours or published) may render its text in its CONSTRUCTOR (F3d)
	try {
	return(buildinfo0(owner, raw));
	} finally {
	    Fonts.exit();   // addon:
	}
    }

    private static List<ItemInfo> buildinfo0(Owner owner, Raw raw) {   // addon: the stock body (F3d)
	/* addon: an item whose `tt` message has not arrived yet has no Raw at all. Stock never reached this method in
	 * that state (its callers keep an EMPTY list until the message lands, never a null one), but a font-change
	 * rebuild can, so tolerate it instead of NPEing on raw.data. */
	if((raw == null) || (raw.data == null))
	    return(new ArrayList<ItemInfo>());
	List<ItemInfo> ret = new ArrayList<ItemInfo>();
	Resource.Resolver rr = owner.context(Resource.Resolver.class);
	for(Object o : raw.data) {
	    if(o == null) {
	    } else if(o instanceof Object[]) {
		Object[] a = (Object[])o;
		ItemInfo inf;
		if(a[0] instanceof InfoFactory) {
		    inf = ((InfoFactory)a[0]).build(owner, raw, a);
		} else {
		    Resource ttres;
		    if(a[0] instanceof Resource) {
			ttres = (Resource)a[0];
		    } else if(a[0] instanceof Indir) {
			ttres = (Resource)((Indir)a[0]).get();
		    } else {
			ttres = rr.getresv(a[0]).get();
		    }
		    InfoFactory f = ttres.getcode(InfoFactory.class, true);
		    inf = f.build(owner, raw, a);
		}
		if(inf != null)
		    ret.add(inf);
	    } else if(o instanceof String) {
		ret.add(new AdHoc(owner, (String)o));
	    } else if(o instanceof ItemInfo) {
		ret.add((ItemInfo)o);
	    } else {
		throw(new ClassCastException("Unexpected object type " + o.getClass() + " in item info array."));
	    }
	}
	return(ret);
    }

    public static List<ItemInfo> buildinfo(Owner owner, Object[] rawinfo) {
	return(buildinfo(owner, new Raw(rawinfo)));
    }
    
    private static String dump(Object arg) {
	if(arg instanceof Object[]) {
	    StringBuilder buf = new StringBuilder();
	    buf.append("[");
	    boolean f = true;
	    for(Object a : (Object[])arg) {
		if(!f)
		    buf.append(", ");
		buf.append(dump(a));
		f = false;
	    }
	    buf.append("]");
	    return(buf.toString());
	} else {
	    return(arg.toString());
	}
    }

    public static class AttrCache<R> implements Indir<R> {
	private final Supplier<List<ItemInfo>> from;
	private final Function<List<ItemInfo>, Supplier<R>> data;
	private List<ItemInfo> forinfo = null;
	private Supplier<R> save;

	public AttrCache(Supplier<List<ItemInfo>> from, Function<List<ItemInfo>, Supplier<R>> data) {
	    this.from = from;
	    this.data = data;
	}

	public R get() {
	    try {
		List<ItemInfo> info = from.get();
		if(info != forinfo) {
		    save = data.apply(info);
		    forinfo = info;
		}
		return(save.get());
	    } catch(Loading l) {
		return(null);
	    }
	}

	public static <I, R> Function<List<ItemInfo>, Supplier<R>> map1(Class<I> icl, Function<I, Supplier<R>> data) {
	    return(info -> {
		    I inf = find(icl, info);
		    if(inf == null)
			return(() -> null);
		    return(data.apply(inf));
		});
	}

	public static <I, R> Function<List<ItemInfo>, Supplier<R>> map1s(Class<I> icl, Function<I, R> data) {
	    return(info -> {
		    I inf = find(icl, info);
		    if(inf == null)
			return(() -> null);
		    R ret = data.apply(inf);
		    return(() -> ret);
		});
	}
    }

    public static interface InfoTip {
	public List<ItemInfo> info();
    }
}
