package io.brodgar.session;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import haven.Coord;
import haven.Coord2d;
import haven.Coord3f;
import haven.Defer;
import haven.FColor;
import haven.Indir;
import haven.Light;
import haven.Loading;
import haven.MCache;
import haven.MapFile;
import haven.MapView;
import haven.ClickLocation;
import haven.Material;
import haven.Resource;
import haven.TexI;
import haven.TexRender;
import haven.GOut;
import haven.Utils;
import haven.render.DataBuffer;
import haven.render.Homo3D;
import haven.render.Location;
import haven.render.Model;
import haven.render.NumberFormat;
import haven.render.Pipe;
import haven.render.RenderTree;
import haven.render.Tex2D;
import haven.render.Texture;
import haven.render.VectorFormat;
import haven.render.VertexArray;

import io.brodgar.perf.Performance;

/**
 * The far rings of the view distance: recorded ground past the full-detail square, drawn out of the
 * map database's zoom grids ({@link MapFile.ZoomGrid}) at a level of detail that falls with distance.
 *
 * <p>A zoom grid of level {@code L} covers {@code 2^L × 2^L} grids in one {@code cmaps}-sized array — the
 * majority tile and the lowest height of each block — so a cell of any level costs the same to read, to
 * mesh and to draw, and each level covers four times the ground of the one below it. Doubling the reach
 * adds one ring of cells, not four times the ground.
 *
 * <p><b>Which cells</b> is a quadtree over segment grid coords, walked every tick from the top level down
 * around where the view is centred: a cell splits into its four children while it is closer than
 * {@link #SPLIT} of its own widths, and a level-one cell that close to the centre — or over live ground —
 * is not a far cell at all but <b>full detail</b>: its four grids are handed to the recorded ground's own
 * raster ({@link #detail}), which draws them as real cut meshes. So the full-detail square is whole
 * level-one cells, the rings fit around it with no overlap and no gap, and neither side draws what the
 * other does. The walk is in SEGMENT coords because a zoom grid is aligned there, and placed through the
 * recorded ground's own {@link Recall.Base} offset.
 *
 * <p><b>A cell</b> is one mesh: a height map of {@link #QUADS}² quads over the zoom grid's heights, lit
 * through normals of its own, textured with the minimap's own colours of its tiles (one texel per sample),
 * and skirted: each edge hangs a strip down, as deep as the slope there, so two cells whose sampled
 * heights disagree along a shared edge show no crack between them. It is built on a {@link Defer} thread,
 * cached by level, place and flat-terrain state, and dropped least-recently-drawn first past
 * {@link #CACHECAP}.
 *
 * <p>Nothing stands on it and nothing reaches it: no objects, no overlays, no clicks, no shadow cast.
 */
public class RecallLod implements RenderTree.Node {
    /** Quads per side of a cell's mesh: a zoom grid's 100 samples, two to a quad. */
    public static final int QUADS = 50;
    /** A cell splits while it is closer to the centre than this many of its own widths. */
    public static final int SPLIT = 2;
    /** The coarsest level walked: a cell of 128 × 128 grids. */
    public static final int MAXLVL = 7;
    /** How many built cells are kept, in scene or not. */
    private static final int CACHECAP = 384;
    /** How many cells may be loading or building at once: the Defer pool is shared with everything else. */
    private static final int MAXBUSY = 3;

    /** Is any part of this box on screen: map coords in world units, and a height range. */
    public interface Frustum {
	boolean visible(Coord2d ul, Coord2d br, float zlo, float zhi);
    }

    private static final class Key {
	final long seg;
	final int lvl;
	final Coord sc;
	final boolean flat;

	Key(long seg, int lvl, Coord sc, boolean flat) {
	    this.seg = seg;
	    this.lvl = lvl;
	    this.sc = sc;
	    this.flat = flat;
	}

	public int hashCode() {
	    return((((Long.hashCode(seg) * 31) + lvl) * 31 + sc.hashCode()) * 2 + (flat ? 1 : 0));
	}

	public boolean equals(Object o) {
	    if(!(o instanceof Key))
		return(false);
	    Key k = (Key)o;
	    return((k.seg == seg) && (k.lvl == lvl) && k.sc.equals(sc) && (k.flat == flat));
	}
    }

    private static final class Built {
	final Model model;
	final TexI tex;
	final RenderTree.Node node;
	/* The same ground for the click pass: the cell's surface without its skirts, carrying
	 * ClickLocation's 0..1 place over the cell, and none of the holes where nothing was recorded. */
	final Model click;
	final float zlo, zhi;

	Built(Model model, TexI tex, RenderTree.Node node, Model click, float zlo, float zhi) {
	    this.model = model;
	    this.tex = tex;
	    this.node = node;
	    this.click = click;
	    this.zlo = zlo;
	    this.zhi = zhi;
	}

	void dispose() {
	    model.dispose();
	    click.dispose();
	    tex.dispose();
	}
    }

    private static final class Cell {
	Indir<? extends MapFile.DataGrid> src = null;
	Defer.Future<Built> building = null;
	Built built = null;
	boolean empty = false;
    }

    private static final class Leaf {
	final Key key;
	final int dist;

	Leaf(Key key, int dist) {
	    this.key = key;
	    this.dist = dist;
	}
    }

    /** Built and pending cells, least recently wanted first. */
    private final Map<Key, Cell> cells = new LinkedHashMap<Key, Cell>(64, 0.75f, true);
    /** What is in the scene right now, and the slot it is in. */
    private final Map<Key, RenderTree.Slot> inscene = new HashMap<Key, RenderTree.Slot>();
    private RenderTree.Slot slot = null;
    /** The same cells in the view's click pass, so a click on far ground is a click on the ground. */
    private final Map<Key, RenderTree.Slot> inclick = new HashMap<Key, RenderTree.Slot>();
    private RenderTree.Slot cslot = null;
    public final RenderTree.Node clicks = new RenderTree.Node() {
	    public void added(RenderTree.Slot slot) {
		cslot = slot;
	    }

	    public void removed(RenderTree.Slot slot) {
		cslot = null;
		inclick.clear();
	    }
	};
    /** The base the cells in the scene were placed through. */
    private Recall.Base placed = null;

    /** The grids to draw at full detail, in SESSION grid coords: this tick's answer, replaced whole. */
    public Set<Coord> detail = Collections.emptySet();
    /** Cells this tick wanted and cells it had in the scene, for {@code :recall}. */
    public int nwanted = 0, ndrawn = 0;

    public void added(RenderTree.Slot slot) {
	this.slot = slot;
    }

    public void removed(RenderTree.Slot slot) {
	this.slot = null;
	inscene.clear();
    }

    /**
     * Walk the rings for this tick: decide the full-detail grids, bring the far cells in view into the
     * scene and take every other one out.
     *
     * @param base    the recorded ground's proved base
     * @param center  where the view is centred, in map coords (world units)
     * @param range   the view distance, in grids
     * @param dreach  the full-detail reach, in grids
     * @param live    the grids the live terrain is drawing, in session grid coords
     * @param fr      the camera's frustum test
     */
    public void tick(Recall.Base base, Coord2d center, int range, int dreach, Set<Coord> live, Frustum fr) {
	if(base != placed) {
	    for(RenderTree.Slot s : inscene.values())
		s.remove();
	    inscene.clear();
	    for(RenderTree.Slot s : inclick.values())
		s.remove();
	    inclick.clear();
	    placed = base;
	}
	boolean flat = Performance.flatTerrain;
	Coord cg = center.floor(MCache.tilesz).div(MCache.cmaps).add(base.off);
	Set<Coord> slive = new HashSet<Coord>();
	for(Coord g : live)
	    slive.add(g.add(base.off));
	Set<Coord> det = new HashSet<Coord>();
	List<Leaf> leaves = new ArrayList<Leaf>();
	int top = 1;
	while((top < MAXLVL) && ((1 << top) < ((range * 2) + 2)))
	    top++;
	int tn = 1 << top;
	Coord lo = cg.sub(range, range), hi = cg.add(range, range);
	for(int y = Math.floorDiv(lo.y, tn) * tn; y <= hi.y; y += tn) {
	    for(int x = Math.floorDiv(lo.x, tn) * tn; x <= hi.x; x += tn)
		visit(top, Coord.of(x, y), base, cg, range, dreach, slive, fr, flat, det, leaves);
	}
	this.detail = det;
	Collections.sort(leaves, new Comparator<Leaf>() {
		public int compare(Leaf a, Leaf b) {
		    return(Integer.compare(a.dist, b.dist));
		}
	    });
	nwanted = leaves.size();

	Set<Key> want = new HashSet<Key>();
	int busy = 0;
	for(Cell c : cells.values()) {
	    if((c.building != null) || ((c.built == null) && !c.empty && (c.src != null)))
		busy++;
	}
	for(Leaf l : leaves) {
	    Cell c = cells.get(l.key);   // the touch that makes it recent
	    if(c == null) {
		if(busy >= MAXBUSY)
		    continue;
		cells.put(l.key, c = new Cell());
	    }
	    if((c.built == null) && !c.empty) {
		if(c.building != null) {
		    if(c.building.done()) {
			try {
			    c.built = c.building.get();
			} catch(Exception e) {
			    c.empty = true;
			}
			c.building = null;
			busy--;
		    }
		} else {
		    if(c.src == null) {
			if(busy >= MAXBUSY)
			    continue;
			c.src = base.seg.grid(l.key.lvl, l.key.sc);
			busy++;
		    }
		    MapFile.DataGrid g;
		    try {
			g = c.src.get();
		    } catch(Loading e) {
			continue;
		    }
		    if(g == null) {
			c.empty = true;
			busy--;
		    } else {
			final MapFile.DataGrid fg = g;
			final int lvl = l.key.lvl;
			final boolean fflat = l.key.flat;
			c.building = Defer.later(() -> build(fg, lvl, fflat));
		    }
		}
	    }
	    if(c.built != null)
		want.add(l.key);
	}

	/* Out first, then in: what leaves the scene frees nothing it would draw, and nothing is added twice. */
	for(Iterator<Map.Entry<Key, RenderTree.Slot>> i = inscene.entrySet().iterator(); i.hasNext();) {
	    Map.Entry<Key, RenderTree.Slot> e = i.next();
	    if(!want.contains(e.getKey())) {
		e.getValue().remove();
		i.remove();
	    }
	}
	for(Iterator<Map.Entry<Key, RenderTree.Slot>> i = inclick.entrySet().iterator(); i.hasNext();) {
	    Map.Entry<Key, RenderTree.Slot> e = i.next();
	    if(!want.contains(e.getKey())) {
		e.getValue().remove();
		i.remove();
	    }
	}
	for(Key k : want) {
	    Built b = cells.get(k).built;
	    Coord tc = k.sc.sub(base.off).mul(MCache.cmaps);
	    Coord3f at = Coord3f.of((float)(tc.x * MCache.tilesz.x), -(float)(tc.y * MCache.tilesz.y), 0);
	    if((slot != null) && !inscene.containsKey(k))
		inscene.put(k, slot.add(b.node, Location.xlate(at)));
	    /* The click's 0..1 place spans the cell's tiles, in session tile coords: the view turns it into
	     * the ground position the click is sent with. */
	    if((cslot != null) && !inclick.containsKey(k))
		inclick.put(k, cslot.add(MapView.farclick(tc, MCache.cmaps.mul(1 << k.lvl), b.click), Location.xlate(at)));
	}
	ndrawn = inscene.size();
	trim();
    }

    private void visit(int lvl, Coord sc, Recall.Base base, Coord cg, int range, int dreach, Set<Coord> live,
		       Frustum fr, boolean flat, Set<Coord> det, List<Leaf> leaves) {
	int n = 1 << lvl;
	if((sc.x > cg.x + range) || (sc.x + n - 1 < cg.x - range) ||
	   (sc.y > cg.y + range) || (sc.y + n - 1 < cg.y - range))
	    return;
	int dx = Math.max(0, Math.max(sc.x - cg.x, cg.x - (sc.x + n - 1)));
	int dy = Math.max(0, Math.max(sc.y - cg.y, cg.y - (sc.y + n - 1)));
	int d = Math.max(dx, dy);
	boolean overlive = false;
	for(Coord g : live) {
	    if((g.x >= sc.x) && (g.x < sc.x + n) && (g.y >= sc.y) && (g.y < sc.y + n)) {
		overlive = true;
		break;
	    }
	}
	Key key = new Key(base.seg.id, lvl, sc, flat);
	float zlo = -1000, zhi = 3000;
	Cell known = cells.get(key);
	if((known != null) && (known.built != null)) {
	    zlo = known.built.zlo;
	    zhi = known.built.zhi;
	}
	Coord tc = sc.sub(base.off).mul(MCache.cmaps);
	Coord2d ul = new Coord2d(tc).mul(MCache.tilesz);
	Coord2d br = ul.add(new Coord2d(MCache.cmaps.mul(n)).mul(MCache.tilesz));
	if(!fr.visible(ul, br, zlo, zhi))
	    return;
	if(lvl == 1) {
	    if((d <= dreach) || overlive) {
		for(int y = 0; y < 2; y++) {
		    for(int x = 0; x < 2; x++)
			det.add(sc.add(x, y).sub(base.off));
		}
		return;
	    }
	    leaves.add(new Leaf(key, d));
	    return;
	}
	if((d < SPLIT * n) || overlive) {
	    int h = n / 2;
	    for(int y = 0; y < 2; y++) {
		for(int x = 0; x < 2; x++)
		    visit(lvl - 1, sc.add(x * h, y * h), base, cg, range, dreach, live, fr, flat, det, leaves);
	    }
	    return;
	}
	leaves.add(new Leaf(key, d));
    }

    /** Past the cap, the least recently wanted cells go -- never one in the scene. */
    private void trim() {
	int over = cells.size() - CACHECAP;
	for(Iterator<Map.Entry<Key, Cell>> i = cells.entrySet().iterator(); i.hasNext() && (over > 0);) {
	    Map.Entry<Key, Cell> e = i.next();
	    if(inscene.containsKey(e.getKey()) || inclick.containsKey(e.getKey()))
		continue;
	    Cell c = e.getValue();
	    if(c.building != null)
		continue;
	    if(c.built != null)
		c.built.dispose();
	    i.remove();
	    over--;
	}
    }

    /** Take everything out of the scene and free every mesh, for good. */
    public void dispose() {
	for(RenderTree.Slot s : inscene.values())
	    s.remove();
	inscene.clear();
	for(RenderTree.Slot s : inclick.values())
	    s.remove();
	inclick.clear();
	for(Cell c : cells.values()) {
	    if(c.building != null)
		c.building.cancel();
	    if(c.built != null)
		c.built.dispose();
	}
	cells.clear();
    }

    /* ---------------------------------------------------------------- the mesh */

    /** Which of the grid's tilesets is ground never recorded: a zoom grid fills what it has not got with
     * MapFile's notile, at height zero. */
    private static boolean[] unrecorded(MapFile.DataGrid g) {
	boolean[] ret = new boolean[g.tilesets.length];
	for(int i = 0; i < ret.length; i++)
	    ret[i] = g.tilesets[i].res.name.equals(MapFile.DataGrid.notile.name);
	return(ret);
    }

    /** The height at a mesh corner: the mean of the recorded samples around it (up to four). */
    private static float cornerz(MapFile.DataGrid g, boolean[] nil, int cx, int cy) {
	int w = MCache.cmaps.x, h = MCache.cmaps.y;
	float sum = 0;
	int n = 0;
	for(int y = cy - 1; y <= cy; y++) {
	    for(int x = cx - 1; x <= cx; x++) {
		int i = Utils.clip(x, 0, w - 1) + (Utils.clip(y, 0, h - 1) * w);
		if(nil[g.tiles[i]])
		    continue;
		sum += g.zmap[i];
		n++;
	    }
	}
	return((n == 0) ? Float.NaN : (sum / n));
    }

    /** One sample's colour: the tileset's minimap image, as the map window draws it. Ground never recorded
     * is transparent, and the material discards it: a hole, not a black floor. */
    private static BufferedImage texture(MapFile.DataGrid g, boolean[] nil) {
	int w = MCache.cmaps.x, h = MCache.cmaps.y;
	BufferedImage[] texes = new BufferedImage[g.tilesets.length];
	boolean[] got = new boolean[g.tilesets.length];
	BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
	for(int y = 0; y < h; y++) {
	    for(int x = 0; x < w; x++) {
		int t = g.tiles[x + (y * w)];
		if(nil[t]) {
		    img.setRGB(x, y, 0);
		    continue;
		}
		if(!got[t]) {
		    Resource r = null;
		    try {
			r = g.tilesets[t].res.get();
		    } catch(Loading l) {
			throw(l);
		    } catch(Exception e) {
			r = null;
		    }
		    if(r != null) {
			Resource.Image ir = r.layer(Resource.imgc);
			if(ir != null)
			    texes[t] = ir.img;
		    }
		    got[t] = true;
		}
		BufferedImage tex = texes[t];
		int rgb = 0x808080;
		if(tex != null)
		    rgb = tex.getRGB(Utils.floormod(x, tex.getWidth()), Utils.floormod(y, tex.getHeight()));
		img.setRGB(x, y, 0xff000000 | (rgb & 0x00ffffff));
	    }
	}
	/* A hole's texels take a recorded neighbour's colour, still transparent: linear filtering blends
	 * across the shore, and blending toward black would darken every edge of recorded ground. */
	int[] dx = {1, -1, 0, 0}, dy = {0, 0, 1, -1};
	for(int y = 0; y < h; y++) {
	    for(int x = 0; x < w; x++) {
		if(!nil[g.tiles[x + (y * w)]])
		    continue;
		for(int k = 0; k < 4; k++) {
		    int nx = x + dx[k], ny = y + dy[k];
		    if((nx < 0) || (ny < 0) || (nx >= w) || (ny >= h) || nil[g.tiles[nx + (ny * w)]])
			continue;
		    img.setRGB(x, y, img.getRGB(nx, ny) & 0x00ffffff);
		    break;
		}
	    }
	}
	return(img);
    }

    private static Built build(MapFile.DataGrid g, int lvl, boolean flat) {
	int n = QUADS, nv = n + 1;
	int step = MCache.cmaps.x / n;                                  // samples per quad
	float q = (float)(step * (1 << lvl) * MCache.tilesz.x);         // world units per quad
	boolean[] nil = unrecorded(g);
	float[] z = new float[nv * nv];
	float zlo = Float.POSITIVE_INFINITY, zhi = Float.NEGATIVE_INFINITY;
	for(int j = 0; j < nv; j++) {
	    for(int i = 0; i < nv; i++) {
		float v = flat ? 0 : cornerz(g, nil, i * step, j * step);
		z[i + (j * nv)] = v;
		if(!Float.isNaN(v)) {
		    zlo = Math.min(zlo, v);
		    zhi = Math.max(zhi, v);
		}
	    }
	}
	if(zlo > zhi)
	    zlo = zhi = 0;
	/* A corner with no recorded sample around it stands in a hole, which the texture discards; it takes
	 * its level from the recorded ground's lowest so no edge of the hole juts up or down. */
	for(int k = 0; k < z.length; k++) {
	    if(Float.isNaN(z[k]))
		z[k] = zlo;
	}

	/* Interleaved position, normal, texcoord: the main grid, then one ring of skirt vertices. */
	int nskirt = 4 * n;
	int nvert = (nv * nv) + nskirt;
	float[] vert = new float[nvert * 8];
	for(int j = 0; j < nv; j++) {
	    for(int i = 0; i < nv; i++) {
		float zl = z[Math.max(i - 1, 0) + (j * nv)], zr = z[Math.min(i + 1, n) + (j * nv)];
		float zu = z[i + (Math.max(j - 1, 0) * nv)], zd = z[i + (Math.min(j + 1, n) * nv)];
		float dzdx = (zr - zl) / (q * (Math.min(i + 1, n) - Math.max(i - 1, 0)));
		/* Local y runs the other way from j. */
		float dzdy = -(zd - zu) / (q * (Math.min(j + 1, n) - Math.max(j - 1, 0)));
		float nx = -dzdx, ny = -dzdy, nz = 1;
		float nl = (float)Math.sqrt((nx * nx) + (ny * ny) + (nz * nz));
		int o = (i + (j * nv)) * 8;
		vert[o]     = i * q;
		vert[o + 1] = -(j * q);
		vert[o + 2] = z[i + (j * nv)];
		vert[o + 3] = nx / nl;
		vert[o + 4] = ny / nl;
		vert[o + 5] = nz / nl;
		vert[o + 6] = (float)i / n;
		vert[o + 7] = (float)j / n;
	    }
	}
	/* The skirt's ring walks the edge in order, one vertex under each edge vertex, a corner once. */
	int[] ring = new int[nskirt];
	{
	    int k = 0;
	    for(int i = 0; i < n; i++) ring[k++] = i;                          // top edge, left to right
	    for(int j = 0; j < n; j++) ring[k++] = n + (j * nv);               // right edge, downward
	    for(int i = n; i > 0; i--) ring[k++] = i + (n * nv);               // bottom edge, right to left
	    for(int j = n; j > 0; j--) ring[k++] = j * nv;                     // left edge, upward
	}
	int sbase = nv * nv;
	/* Each skirt vertex hangs only as far as the ground around it moves: a crack between two cells is
	 * the two disagreeing about a height the slope there puts within that reach. A fixed deep skirt
	 * stands as a wall wherever no neighbour is drawn -- unexplored ground, a cell still loading. */
	float deepest = 0;
	for(int k = 0; k < nskirt; k++) {
	    int v = ring[k], vi = v % nv, vj = v / nv;
	    float zc = z[v], dz = 0;
	    if(vi > 0) dz = Math.max(dz, Math.abs(zc - z[v - 1]));
	    if(vi < n) dz = Math.max(dz, Math.abs(zc - z[v + 1]));
	    if(vj > 0) dz = Math.max(dz, Math.abs(zc - z[v - nv]));
	    if(vj < n) dz = Math.max(dz, Math.abs(zc - z[v + nv]));
	    float depth = 10 + (dz * 2);
	    deepest = Math.max(deepest, depth);
	    int src = v * 8, o = (sbase + k) * 8;
	    System.arraycopy(vert, src, vert, o, 8);
	    vert[o + 2] -= depth;
	}
	zlo -= deepest;

	int nidx = (n * n * 6) + (nskirt * 6);
	short[] idx = new short[nidx];
	int p = 0;
	for(int j = 0; j < n; j++) {
	    for(int i = 0; i < n; i++) {
		int a = i + (j * nv), b = a + 1, c = a + nv, d = c + 1;
		idx[p++] = (short)a; idx[p++] = (short)c; idx[p++] = (short)b;
		idx[p++] = (short)b; idx[p++] = (short)c; idx[p++] = (short)d;
	    }
	}
	for(int k = 0; k < nskirt; k++) {
	    int k2 = (k + 1) % nskirt;
	    int a = ring[k], b = ring[k2], c = sbase + k, d = sbase + k2;
	    idx[p++] = (short)a; idx[p++] = (short)c; idx[p++] = (short)b;
	    idx[p++] = (short)b; idx[p++] = (short)c; idx[p++] = (short)d;
	}

	int stride = 32;
	VertexArray.Layout fmt = new VertexArray.Layout(
	    new VertexArray.Layout.Input(Homo3D.vertex, new VectorFormat(3, NumberFormat.FLOAT32), 0, 0, stride),
	    new VertexArray.Layout.Input(Homo3D.normal, new VectorFormat(3, NumberFormat.FLOAT32), 0, 12, stride),
	    new VertexArray.Layout.Input(Tex2D.texc, new VectorFormat(2, NumberFormat.FLOAT32), 0, 24, stride));
	VertexArray vao = new VertexArray(fmt, new VertexArray.Buffer(vert.length * 4, DataBuffer.Usage.STATIC,
								      DataBuffer.Filler.of(vert)));
	Model model = new Model(Model.Mode.TRIANGLES, vao,
				new Model.Indices(nidx, NumberFormat.UINT16, DataBuffer.Usage.STATIC,
						  DataBuffer.Filler.of(idx)),
				0, nidx);

	/* Unrounded: a TexI rounds to a power of two by default, padding 100 samples to 128, and the mesh's
	 * 0..1 texcoords would reach into the padding. */
	TexI tex = new TexI(texture(g, nil), false);
	tex.magfilter(Texture.Filter.LINEAR).minfilter(Texture.Filter.LINEAR).wrapmode(Texture.Wrapping.CLAMP);
	TexRender tr = new TexRender(tex.st().data) {
		public void render(GOut gout, float[] gc, float[] tc) {}
	    };
	Material mat = new Material(new Pipe.Op[] {
		tr.draw,
		tr.clip,
		new Light.PhongLight(true, Light.PhongLight.defamb, Light.PhongLight.defdif,
				     Light.PhongLight.defspc, new FColor(0, 0, 0), 0f),
		Material.nofacecull,
	    });
	/* The click mesh: the surface's vertices with their texcoords as ClickLocation's place, and every
	 * quad that has a recorded sample in it -- a click on a hole reaches whatever lies behind. */
	float[] cvert = new float[nv * nv * 5];
	for(int v = 0; v < nv * nv; v++) {
	    System.arraycopy(vert, v * 8, cvert, v * 5, 3);
	    cvert[(v * 5) + 3] = vert[(v * 8) + 6];
	    cvert[(v * 5) + 4] = vert[(v * 8) + 7];
	}
	short[] cidx = new short[n * n * 6];
	int cp = 0;
	for(int j = 0; j < n; j++) {
	    for(int i = 0; i < n; i++) {
		boolean any = false;
		for(int sy = j * step; (sy < (j + 1) * step) && !any; sy++) {
		    for(int sx = i * step; (sx < (i + 1) * step) && !any; sx++)
			any = !nil[g.tiles[sx + (sy * MCache.cmaps.x)]];
		}
		if(!any)
		    continue;
		int a = i + (j * nv), b = a + 1, c = a + nv, d = c + 1;
		cidx[cp++] = (short)a; cidx[cp++] = (short)c; cidx[cp++] = (short)b;
		cidx[cp++] = (short)b; cidx[cp++] = (short)c; cidx[cp++] = (short)d;
	    }
	}
	VertexArray.Layout cfmt = new VertexArray.Layout(
	    new VertexArray.Layout.Input(Homo3D.vertex, new VectorFormat(3, NumberFormat.FLOAT32), 0, 0, 20),
	    new VertexArray.Layout.Input(ClickLocation.vertex, new VectorFormat(2, NumberFormat.FLOAT32), 0, 12, 20));
	VertexArray cvao = new VertexArray(cfmt, new VertexArray.Buffer(cvert.length * 4, DataBuffer.Usage.STATIC,
								       DataBuffer.Filler.of(cvert)));
	short[] cidxf = java.util.Arrays.copyOf(cidx, Math.max(cp, 3));
	Model click = new Model(Model.Mode.TRIANGLES, cvao,
				new Model.Indices(cidxf.length, NumberFormat.UINT16, DataBuffer.Usage.STATIC,
						  DataBuffer.Filler.of(cidxf)),
				0, Math.max(cp, 3));   // a cell of holes alone: one degenerate triangle, no pixel

	return(new Built(model, tex, mat.apply(model), click, zlo, zhi));
    }
}
