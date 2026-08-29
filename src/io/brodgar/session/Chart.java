package io.brodgar.session;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import haven.AddonWidgets;
import haven.Area;
import haven.Coord;
import haven.Coord2d;
import haven.Coord3f;
import haven.Defer;
import haven.FastMesh;
import haven.GOut;
import haven.Loading;
import haven.MCache;
import haven.MapFile;
import haven.MapSource;
import haven.Material;
import haven.MeshBuf;
import haven.MiniMap;
import haven.Session;
import haven.TexI;
import haven.TexRender;
import haven.render.Location;
import haven.render.RenderTree;

/**
 * SPIKE — the chart: the world in three dimensions with the map database's own picture for a ground.
 *
 * <p>The relief is real and the camera is the ordinary one — it turns, tilts and zooms. What changes is
 * what the ground is <b>made of</b>: instead of {@code MapMesh} laying every tile through its tileset's
 * {@code Tiler}, with transitions and flavour, a cell is one heightfield mesh wearing the very image the
 * map window blits. One draw call and one 100&times;100 texture a cell, and the client's own terrain is out
 * of the scene while this is up.
 *
 * <p><b>The mesh is this class's own, and that is what makes it cheap and exact.</b> Nothing here goes
 * through {@code MCache} or {@code MapMesh}, so there is no tile-id space to mint, no {@code dotrans} pass
 * to feed neighbours to, and — the part that matters — <b>no scale trick</b>: the sample spacing is written
 * into the vertices as {@code tilesz << lvl}, so the geometry is the right size on its own and the normals
 * are the normals of the surface actually drawn.
 *
 * <p><b>Which picture.</b> The corner minimap's, drawn over a 3&times;3 {@link MapFile.View} so that the
 * outline pass sees a tile's neighbours across the grid border instead of stopping at it — less the pass
 * that inks the cliffs black, which is notation a flat map needs and relief makes into a solid wall. See
 * {@link #chartmap}.
 *
 * <p><b>Which heights.</b> They are the recorded per-tile heights, which are the live map's own numbers —
 * so a gob standing on this ground stands where it would stand on the real one. The chart stays at level 0
 * however far the camera pulls back ({@link #maxlvl}); what grows with the distance is the block, not the
 * coarseness.
 *
 * <p><b>Unlit, on purpose.</b> The picture already carries the colour of the ground; running it through the
 * scene's lights would shade a map by the time of day. One state on the material is all that separates the
 * two if that turns out to be the wrong call.
 */
public class Chart extends RenderTree.Node.Track1 {
    /**
     * The coarsest level the chart will fall back to, and it is <b>0</b>: the picture never goes coarser,
     * however far the camera is pulled back.
     *
     * <p>Level 0 is a real recorded grid, so this is also what keeps the chart at its best in every respect
     * at once — the picture is the blended 3&times;3 render the corner minimap draws, and the heights are
     * the recorded per-tile ones, which are the live map's own numbers. Every level above it trades both
     * away for reach: the picture becomes one texel a sample with no blending, and the heights become
     * {@code ZoomGrid.from}'s {@code minz}, the minimum of {@code 4^n} real tiles, which sinks the ground
     * and flattens the hills.
     *
     * <p>What pays for it is the block following the view rather than the level: pulling back widens the
     * square and the frustum decides what of it is built, so the cost tracks what is on screen. Raising
     * this number is all that is needed to bring the levels back — the thresholds, the dead band and the
     * build-beside-then-swap are all still here and still correct, they simply never fire at zero.
     */
    public static final int maxlvl = 0;
    /** Fewest cells the block reaches from its centre, however close the camera is. */
    private static final int minr = 2;
    /**
     * Most cells the block reaches from its centre.
     *
     * <p>The block is not a fixed square any more, and it could not stay one: it has to cover what the view
     * covers, or the chart simply ends in mid-air before the level ever changes — which is what tied the
     * old thresholds to 550 and made them fire so early. What keeps a wide block affordable is that a cell
     * outside the frustum is never read and never built, so the cost follows what is on screen rather than
     * what is in range.
     */
    private static final int maxr = 12;
    /** How many cells one tick may start reading and building. */
    private static final int maxbuild = 3;

    private final Session sess;
    private final haven.MapView mv;

    public Chart(Session sess, haven.MapView mv) {
	this.sess = sess;
	this.mv = mv;
    }

    /** What the cells' coordinates are relative to; replaced whole, never mutated. */
    private static final class Base {
	final MapFile file;
	final MapFile.Segment seg;
	final long segid;
	final Coord tc;
	final Coord off;    // segment grid coord = session grid coord + off

	Base(MapFile file, MiniMap.Location loc) {
	    this.file = file;
	    this.seg = loc.seg;
	    this.segid = loc.seg.id;
	    this.tc = loc.tc;
	    this.off = loc.tc.div(MCache.cmaps);
	}

	boolean sameas(MiniMap.Location loc) {
	    return((segid == loc.seg.id) && tc.equals(loc.tc));
	}
    }

    private Base base = null;
    private boolean proven = false;
    private String unbased = "no session location yet";
    /** The level whose cells are in the scene. */
    private int lvl = 0;
    /** The level being prepared off the scene, or {@code -1} when there is none. */
    private int building = -1;

    /**
     * The distance at which level {@code lvl + 1} starts to be worth it, and the band under it that has to
     * be crossed to come back down.
     *
     * <p>It is not sharpness. A texel of the picture is {@code 11 << lvl} world units and the ground at the
     * look-at point is about {@code dist / rsz.x} units to the pixel, so at any distance a camera standing
     * on the ground is ever pulled to, one level-0 texel is <b>hundreds</b> of pixels across — going coarser
     * never buys resolution back, because there was never any to save.
     *
     * <p>So the levels are spaced <b>five to one</b> and the block reaches as far as the view instead of
     * staying a fixed square. Those two go together: a fixed block covers {@code 2200 << lvl} units and the
     * view runs out of it at about four times the camera's distance, which is what pinned the first
     * threshold at 550 and made a level change something you met by scrolling a little. Let the block
     * follow the view and the level can wait until the picture itself has nothing left to show.
     *
     * <p><b>The band is the point.</b> {@code FreeCam.dist} eases toward its target, so a threshold with no
     * dead zone under it is crossed twice on the way and once more on the way back, and every crossing used
     * to be every cell destroyed and rebuilt.
     */
    private static double up(int lvl) {return(2750.0 * Math.pow(5, lvl));}
    private static final double band = 0.7;

    /** One cell: its level, its coord at that level, and what it has got to. */
    private static final class Cell {
	final int lvl;
	final Coord zc;
	Defer.Future<RenderTree.Node> job = null;
	RenderTree.Node node = null;
	RenderTree.Slot slot = null;
	boolean blank = false;      // nothing recorded here

	Cell(int lvl, Coord zc) {
	    this.lvl = lvl;
	    this.zc = zc;
	}
    }

    private final Map<String, Cell> cells = new HashMap<String, Cell>();
    private int ndrawn = 0, nblank = 0, nfailed = 0;
    private String lasterr = null;

    private static String key(int lvl, Coord zc) {
	return(lvl + ":" + zc.x + "," + zc.y);
    }

    /**
     * Called from the view's tick, on the UI thread.
     *
     * @param mm     the corner minimap, whose {@code sessloc} is the session&rarr;segment bridge
     * @param centre where the camera is looking, in session world coords
     * @param dist   how far back the camera is pulled, which is what the level follows
     */
    public void tick(MiniMap mm, Coord2d centre, double dist) {
	if((mm == null) || (mm.file == null)) {
	    unbased = "no map database yet";
	    dropall();
	    return;
	}
	MiniMap.Location loc = mm.sessloc;
	if((loc == null) || !loc.tc.mod(MCache.cmaps).equals(Coord.z)) {
	    unbased = (loc == null) ? "no session location yet"
		: ("session location " + loc.tc + " is not grid-aligned");
	    dropall();
	    return;
	}
	Base cur = this.base;
	if((cur == null) || !cur.sameas(loc)) {
	    /* The offset is never carried across a re-base: a grid coord that meant somewhere before means
	     * somewhere else afterwards, so every cell already placed is now in the wrong world. */
	    dropall();
	    this.base = cur = new Base(mm.file, loc);
	    proven = false;
	}
	unbased = null;
	if((centre == null) || !prove(cur)) {
	    dropall();
	    return;
	}
	/* The level follows how far the camera is pulled back, with a dead band either side of every
	 * threshold. A cell holds the same hundred samples whatever its level, so pulling out costs coarser
	 * ground rather than more of it -- which is what the pyramid is for. */
	int want = (building >= 0) ? building : lvl;
	while((want < maxlvl) && (dist > up(want)))
	    want++;
	while((want > 0) && (dist < (up(want - 1) * band)))
	    want--;
	if((want != lvl) && (want != building)) {
	    /* Prepare the new level BESIDE the one on screen rather than in place of it. A coord means
	     * different ground at each level, so the two can never be drawn together -- one surface laid on
	     * its twin is z-fighting, not a blend -- but they can be BUILT together, and the swap is then one
	     * frame with ground in it rather than several seconds without. Anything of a third level goes:
	     * two are already one more than is ever shown. */
	    for(Iterator<Map.Entry<String, Cell>> i = cells.entrySet().iterator(); i.hasNext();) {
		Cell c = i.next().getValue();
		if((c.lvl != lvl) && (c.lvl != want)) {
		    drop(c);
		    i.remove();
		}
	    }
	    building = want;
	}
	/* While a level is being prepared it is the one that is read for; the level on screen keeps the
	 * cells it has, which is what the camera goes on looking at until the swap. */
	int target = (building >= 0) ? building : lvl;
	int side = MCache.cmaps.x << target;                    // segment tiles a cell covers
	double cellw = side * MCache.tilesz.x;                  // world units a cell covers
	Coord sc = centre.floor(MCache.tilesz).add(cur.tc);     // where we look, in segment tiles
	Coord zc0 = sc.div(side);
	/* As far as the view reaches, in cells, and no further than the cap. A ground-level camera sees to
	 * roughly four times its own distance before the frustum's far edge or the ground's own curve away
	 * takes over. */
	int d = (int)Math.ceil((4.0 * dist) / cellw);
	d = Math.max(minr, Math.min(d, maxr));
	Set<String> want2 = new HashSet<String>();
	List<Cell> tobuild = new ArrayList<Cell>();
	for(int dy = -d; dy <= d; dy++) {
	    for(int dx = -d; dx <= d; dx++) {
		Coord zc = zc0.add(dx, dy);
		/* Off screen is never read and never built, which is the whole of what makes a block this
		 * wide affordable. Kept if it is already built, though: dropping a cell the camera merely
		 * turned away from would have it rebuilt the moment it turns back. */
		Coord ul = zc.mul(side).sub(cur.tc);
		if(!mv.worldboxvisible(new Coord2d(ul).mul(MCache.tilesz),
				       new Coord2d(ul.add(side, side)).mul(MCache.tilesz))
		   && (cells.get(key(target, zc)) == null))
		    continue;
		String k = key(target, zc);
		want2.add(k);
		Cell c = cells.get(k);
		if(c == null) {
		    c = new Cell(target, zc);
		    cells.put(k, c);
		}
		if(!c.blank && (c.node == null))
		    tobuild.add(c);
	    }
	}
	for(Iterator<Map.Entry<String, Cell>> i = cells.entrySet().iterator(); i.hasNext();) {
	    Map.Entry<String, Cell> ent = i.next();
	    /* The level on screen keeps its cells through a transition whatever the new level wants, or the
	     * swap would be into a hole it just made. */
	    if(!want2.contains(ent.getKey()) && (ent.getValue().lvl != lvl)) {
		drop(ent.getValue());
		i.remove();
	    }
	}
	int started = 0;
	for(Cell c : tobuild) {
	    if(c.job != null) {
		if(!c.job.done())
		    continue;
		RenderTree.Node n;
		try {
		    n = c.job.get();
		} catch(RuntimeException e) {
		    nfailed++;
		    lasterr = String.valueOf(e);
		    c.job = null;
		    c.blank = true;
		    continue;
		}
		c.job = null;
		if(n == null)
		    continue;              // blank, or still coming off the disk -- the job says which
		c.node = n;
		if(c.lvl == lvl)
		    place(cur, c);      // a level being prepared is built, never shown
		ndrawn++;
		continue;
	    }
	    if(started >= maxbuild)
		continue;
	    started++;
	    start(cur, c);
	}
	/* The swap, and it happens in one frame or not at all. Every cell the new level wants is either
	 * built or known to be blank, so the level on screen can go and the new one take its place with no
	 * frame in between that has neither -- which is what a level change looked like before. */
	if(building >= 0) {
	    boolean ready = true;
	    for(String k : want2) {
		Cell c = cells.get(k);
		if((c != null) && (c.node == null) && !c.blank) {
		    ready = false;
		    break;
		}
	    }
	    if(ready) {
		for(Iterator<Map.Entry<String, Cell>> i = cells.entrySet().iterator(); i.hasNext();) {
		    Cell c = i.next().getValue();
		    if(c.lvl == lvl) {
			drop(c);
			i.remove();
		    }
		}
		lvl = building;
		building = -1;
		for(Cell c : cells.values()) {
		    if((c.lvl == lvl) && (c.node != null) && (c.slot == null))
			place(cur, c);
		}
	    }
	}
    }

    /**
     * Is the offset the right one? {@code sessloc} goes stale rather than null after a re-base, so a grid id
     * — the server's own, the same in every frame — is asked of the record at a LIVE grid's coord and
     * compared. {@link Recall} proves the same thing the same way, and for the same reason.
     */
    private boolean prove(Base base) {
	if(proven)
	    return(true);
	if(!base.file.lock.readLock().tryLock())
	    return(false);
	try {
	    int checked = 0;
	    for(MCache.Grid lg : AddonWidgets.loadedGrids(sess.glob.map)) {
		if(lg.id == 0)
		    continue;
		Long rid = base.seg.gridid(lg.gc.add(base.off));
		if(rid == null)
		    continue;
		checked++;
		if(rid.longValue() != lg.id)
		    return(false);
	    }
	    if(checked == 0)
		return(false);
	    proven = true;
	    return(true);
	} finally {
	    base.file.lock.readLock().unlock();
	}
    }

    /**
     * Read one cell and build it, on a {@code Defer} thread.
     *
     * <p>The lock is a {@code tryLock} and never a {@code lock}: {@link MapFile}'s processor thread holds
     * the write lock across disk I/O, and waiting for it here would park a worker for as long as a segment
     * save takes. Everything that asks the segment anything happens inside it — {@code Segment.checklock}
     * answers a read from outside with an {@code IllegalMonitorStateException}, and that is not the kind of
     * mistake that shows up as anything but missing ground.
     */
    private void start(final Base base, final Cell c) {
	c.job = Defer.later(new Defer.Callable<RenderTree.Node>() {
		public RenderTree.Node call() {
		    if(!base.file.lock.readLock().tryLock())
			return(null);           // the processor has the write lock; ask again next tick
		    MapFile.DataGrid g;
		    BufferedImage img;
		    try {
			if(c.lvl == 0) {
			    if(base.seg.gridid(c.zc) == null) {
				c.blank = true;
				nblank++;
				return(null);   // never walked
			    }
			    g = base.seg.grid(c.zc).get();
			    img = (g == null) ? null : level0(base, c.zc);
			} else {
			    g = base.seg.grid(c.lvl, c.zc.mul(1 << c.lvl)).get();
			    if(g == null) {
				c.blank = true;
				nblank++;
				return(null);   // nothing recorded under this cell at all
			    }
			    img = g.render(c.zc.mul(MCache.cmaps));
			}
		    } catch(Loading l) {
			return(null);           // still coming off the disk; asked again next tick
		    } finally {
			base.file.lock.readLock().unlock();
		    }
		    if((g == null) || (img == null))
			return(null);
		    return(build(g, img, c.lvl));
		}

		public String toString() {return("Drawing the chart...");}
	    });
    }

    /**
     * A recorded grid's picture, drawn the way the corner minimap draws it: through a 3&times;3
     * {@link MapFile.View}, so tile transitions blend across the grid's border instead of stopping at it.
     * Neighbours the record has not got simply contribute nothing. The caller holds the read lock.
     */
    private BufferedImage level0(Base base, Coord gc) {
	MapFile.View view = new MapFile.View(base.seg);
	for(int y = -1; y <= 1; y++) {
	    for(int x = -1; x <= 1; x++) {
		if(base.seg.gridid(gc.add(x, y)) != null)
		    view.addgrid(gc.add(x, y));
	    }
	}
	view.fin();
	return(chartmap(view, Area.sized(gc.mul(MCache.cmaps), MCache.cmaps)));
    }

    /**
     * {@code MapSource.drawmap} less the pass that inks the cliffs.
     *
     * <p>That function draws three things and only two of them are ground. First every tile's own colour,
     * sampled from its tileset's image. Then <b>every {@code Ridges.RidgeTile} that {@code brokenp} calls a
     * cliff, blended all the way to black</b> — factor 1 on the tile itself and 0.1 on the ring around it.
     * Then a black outline wherever a neighbouring tile has a higher index.
     *
     * <p>On a flat map that middle pass is notation: a black line is how you are told there is a drop
     * there, because a picture from directly above cannot show you one. On a chart with the real relief
     * under it the drop is already drawn — it is a wall of geometry — and inking it as well leaves a wall
     * that is solid black. So the cliffs keep their own tileset's colour and the relief says the rest,
     * which is what the terrain renderer does.
     *
     * <p>The outline pass stays: it is what gives the map its look, and it says something the geometry
     * does not — where one kind of ground ends and another begins.
     */
    private static BufferedImage chartmap(MapSource m, Area a) {
	Coord sz = a.sz();
	BufferedImage[] texes = new BufferedImage[256];
	BufferedImage buf = TexI.mkbuf(sz);
	Coord c = new Coord();
	for(c.y = 0; c.y < sz.y; c.y++) {
	    for(c.x = 0; c.x < sz.x; c.x++) {
		int t = m.gettile(a.ul.add(c));
		if(t < 0) {
		    buf.setRGB(c.x, c.y, 0);
		    continue;
		}
		BufferedImage tex = MapSource.tileimg(m, texes, t);
		int rgb = 0;
		if(tex != null) {
		    rgb = tex.getRGB(haven.Utils.floormod(c.x + a.ul.x, tex.getWidth()),
				     haven.Utils.floormod(c.y + a.ul.y, tex.getHeight()));
		}
		buf.setRGB(c.x, c.y, rgb);
	    }
	}
	for(c.y = 0; c.y < sz.y; c.y++) {
	    for(c.x = 0; c.x < sz.x; c.x++) {
		int t = m.gettile(a.ul.add(c));
		/* A tile the record has nothing for is not the low side of a border, it is absent -- and
		 * drawing one would ink the whole frontier of what you have walked. */
		if(t < 0)
		    continue;
		if((m.gettile(a.ul.add(c).add(-1, 0)) > t) ||
		   (m.gettile(a.ul.add(c).add( 1, 0)) > t) ||
		   (m.gettile(a.ul.add(c).add(0, -1)) > t) ||
		   (m.gettile(a.ul.add(c).add(0,  1)) > t))
		    buf.setRGB(c.x, c.y, 0xff000000);
	    }
	}
	return(buf);
    }

    /**
     * One cell's ground: a heightfield mesh at the sample spacing of its own level, wearing the picture.
     *
     * <p>Positions are local to the cell's corner and y is negated, the way every mesh in this world is;
     * the slot carries where that corner is. The last row and column read their own neighbour rather than
     * the next cell's, so adjacent cells do not quite meet — the <b>skirt</b> hanging from all four edges
     * is what keeps daylight out of that seam, and out of the one between two levels.
     */
    private static RenderTree.Node build(MapFile.DataGrid g, BufferedImage img, int lvl) {
	final int n = MCache.cmaps.x;
	final float step = (float)(MCache.tilesz.x * (1 << lvl));
	final float skirt = step * 3f;
	MeshBuf buf = new MeshBuf();
	MeshBuf.Tex tex = buf.layer(MeshBuf.tex);
	MeshBuf.Vertex[] vs = new MeshBuf.Vertex[(n + 1) * (n + 1)];
	float[] z = new float[(n + 1) * (n + 1)];
	Coord tc = new Coord();
	for(int y = 0; y <= n; y++) {
	    for(int x = 0; x <= n; x++) {
		tc.x = Math.min(x, n - 1); tc.y = Math.min(y, n - 1);
		z[x + (y * (n + 1))] = (float)g.getfz(tc);
	    }
	}
	for(int y = 0; y <= n; y++) {
	    for(int x = 0; x <= n; x++) {
		int i = x + (y * (n + 1));
		/* Central differences over the heightfield, in the mesh's own space: +x east, -y south.
		 * The spacing is the real one, so these are the normals of the surface drawn. */
		float zl = z[Math.max(x - 1, 0) + (y * (n + 1))];
		float zr = z[Math.min(x + 1, n) + (y * (n + 1))];
		float zu = z[x + (Math.max(y - 1, 0) * (n + 1))];
		float zd = z[x + (Math.min(y + 1, n) * (n + 1))];
		Coord3f nrm = new Coord3f(-(zr - zl), (zd - zu), 2f * step).norm();
		vs[i] = buf.new Vertex(new Coord3f(x * step, -y * step, z[i]), nrm);
		tex.set(vs[i], new Coord3f((float)x / n, (float)y / n, 0));
	    }
	}
	for(int y = 0; y < n; y++) {
	    for(int x = 0; x < n; x++) {
		MeshBuf.Vertex a = vs[x + (y * (n + 1))];
		MeshBuf.Vertex b = vs[(x + 1) + (y * (n + 1))];
		MeshBuf.Vertex c = vs[(x + 1) + ((y + 1) * (n + 1))];
		MeshBuf.Vertex d = vs[x + ((y + 1) * (n + 1))];
		buf.new Face(a, b, c);
		buf.new Face(a, c, d);
	    }
	}
	for(int i = 0; i < n; i++) {
	    edge(buf, tex, vs[i + (0 * (n + 1))],       vs[(i + 1) + (0 * (n + 1))],       skirt);
	    edge(buf, tex, vs[(i + 1) + (n * (n + 1))], vs[i + (n * (n + 1))],             skirt);
	    edge(buf, tex, vs[0 + ((i + 1) * (n + 1))], vs[0 + (i * (n + 1))],             skirt);
	    edge(buf, tex, vs[n + (i * (n + 1))],       vs[n + ((i + 1) * (n + 1))],       skirt);
	}
	FastMesh mesh = buf.mkmesh();
	/* No power-of-two rounding, so the mesh's own [0, 1] texture coords land on the whole image rather
	 * than on its corner. */
	TexI ti = new TexI(img, false);
	TexRender tr = new TexRender(ti.st().data) {
		public void render(GOut out, float[] gc, float[] tc) {}   // drawn in 3D, never blitted
	    };
	/* Textured, alpha-cut where the record has nothing, double-sided, and UNLIT: the picture already
	 * carries the ground's colour, and the scene's lights would shade a map by the time of day. */
	return(new Material(tr.draw, tr.clip, Material.nofacecull).apply(mesh));
    }

    /** An apron hanging from one edge, deep enough to cover a seam between cells or between levels. */
    private static void edge(MeshBuf buf, MeshBuf.Tex tex, MeshBuf.Vertex a, MeshBuf.Vertex b, float depth) {
	MeshBuf.Vertex da = buf.new Vertex(new Coord3f(a.pos.x, a.pos.y, a.pos.z - depth), a.nrm);
	MeshBuf.Vertex db = buf.new Vertex(new Coord3f(b.pos.x, b.pos.y, b.pos.z - depth), b.nrm);
	tex.set(da, tex.get(a));
	tex.set(db, tex.get(b));
	buf.new Face(a, b, db);
	buf.new Face(a, db, da);
    }

    private void place(Base base, Cell c) {
	RenderTree.Slot root = this.slot;
	if((root == null) || (c.node == null))
	    return;
	int side = MCache.cmaps.x << c.lvl;
	Coord ul = c.zc.mul(side).sub(base.tc);         // session tiles
	Coord2d wc = new Coord2d(ul).mul(MCache.tilesz);
	c.slot = root.add(c.node, Location.xlate(new Coord3f((float)wc.x, -(float)wc.y, 0f)));
    }

    private void drop(Cell c) {
	if(c.slot != null) {
	    c.slot.remove();
	    c.slot = null;
	}
	if(c.job != null) {
	    c.job.cancel();
	    c.job = null;
	}
	if(c.node instanceof haven.Disposable)
	    ((haven.Disposable)c.node).dispose();
	c.node = null;
    }

    /** Everything out of the scene and disposed — a re-base, a level change, or nothing to be based on. */
    public void dropall() {
	for(Cell c : cells.values())
	    drop(c);
	cells.clear();
	building = -1;
    }

    /** What {@code :chart} prints. */
    public List<String> report() {
	List<String> out = new ArrayList<String>();
	Base b = this.base;
	if(b == null) {
	    out.add("chart: no base -- " + unbased);
	} else {
	    out.add(String.format("chart: segment %s, session tile %s, %s",
				  Long.toUnsignedString(b.segid, 16), b.tc,
				  proven ? "proved against a live grid" : "NOT PROVED -- nothing is drawn"));
	}
	int drawn = 0, waiting = 0, blank = 0;
	for(Cell c : cells.values()) {
	    if(c.node != null)
		drawn++;
	    else if(c.blank)
		blank++;
	    else
		waiting++;
	}
	out.add(String.format("chart: level %d, %d tiles a cell -- %d cells held, %d drawn, %d waiting,"
			      + " %d never walked%s",
			      lvl, MCache.cmaps.x << lvl, cells.size(), drawn, waiting, blank,
			      (building < 0) ? "" : (", preparing level " + building)));
	if(maxlvl == 0) {
	    out.add("chart: pinned to level 0 -- the picture never goes coarser, however far you pull back");
	} else {
	    out.add(String.format("chart: level %d holds until dist passes %.0f, and gives way under %.0f",
				  lvl, up(lvl), (lvl == 0) ? 0.0 : (up(lvl - 1) * band)));
	}
	out.add(String.format("chart: %s heights, cells built since start %d, blank %d, failed %d",
			      (lvl == 0) ? "recorded per-tile" : "minz-downsampled", ndrawn, nblank, nfailed));
	if(lasterr != null)
	    out.add("chart: last error " + lasterr);
	return(out);
    }
}
