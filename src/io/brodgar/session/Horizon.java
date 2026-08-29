package io.brodgar.session;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import haven.AddonWidgets;
import haven.Coord;
import haven.Coord2d;
import haven.Defer;
import haven.Loading;
import haven.MCache;
import haven.MapFile;
import haven.MiniMap;
import haven.Session;

/**
 * SPIKE — the far horizon, drawn out of the map database's zoom pyramid, through the client's own terrain.
 *
 * <p>A geometry clipmap. Around the character {@code MapView.Terrain} goes on drawing live ground at tile
 * resolution; beyond it this lays a ring of {@link MapFile.ZoomGrid} cells per zoom level, each level
 * covering four times the area of the one inside it for the same triangle count.
 *
 * <p><b>It is the real terrain path, not a picture of one.</b> A {@code ZoomGrid} is a
 * {@link MapFile.DataGrid} exactly like a recorded grid — a {@code cmaps}-sized {@code int[]} of tiles and
 * {@code float[]} of heights — so it goes into an {@link MCache} of this source's own and is meshed by
 * {@code MapMesh} with the tilesets' own {@code Tiler}s, their textures, their transitions and the scene's
 * light. {@link Recall} fills such a cache from the record at level 0; this does it at level {@code n}, and
 * the whole of the difference is a scale.
 *
 * <p><b>One cache per level, and that is what makes the transitions work.</b> {@code MapMesh.dotrans} reads
 * a tile across the cut edge, so a cell meshed alone would throw {@code LoadingMap} on every one of its cuts.
 * Neighbouring cells of the same level are neighbouring <i>grids</i> in one cache, so only the outermost
 * ring of what is loaded has no neighbour — which is why {@link #load} cells are read and {@link #draw} are
 * drawn.
 *
 * <p><b>The scale is a slot, not a mesh.</b> {@code MapMesh} lays every tile at {@code tilesz} and has no
 * say in the matter, so a level-{@code n} cache is meshed at one tile per sample and the level's own slot
 * scales it by {@code 2^n} in x and y, leaving z alone. Two consequences, both visible: the tileset's own
 * texture is stretched by the same factor — which is what a LOD looks like — and the mesh's normals were
 * computed for a surface {@code 2^n} times steeper than the one drawn, so far ground shades a little more
 * dramatically than its silhouette.
 *
 * <p><b>The heights are minima.</b> {@code ZoomGrid.from} downsamples with {@code minz} over each
 * 2&times;2, so a level-{@code n} sample is the lowest of {@code 4^n} real tiles: the horizon sinks toward
 * the local floor and hills flatten. It is also what lets each ring be laid under the one inside it — see
 * {@link #sink} — and it is the thing to judge this spike on.
 */
public class Horizon {
    /** Zoom levels drawn, outward from the character. Level 0 is the live map and is not ours. */
    public static final int maxlvl = 1;
    /** Cells a side READ per level: {@link #draw} plus the ring {@code dotrans} needs a neighbour in. */
    public static final int load = 7;
    /**
     * Cells a side DRAWN per level, and it is <b>odd on purpose</b>: the block is the centre cell plus
     * {@code draw / 2} each way, so it reaches as far east as west. An even side gives a radius that only
     * divides one way — four cells reach two back and one forward — and the missing side is a whole cell
     * of ground that is read, never asked for, and never drawn.
     */
    public static final int draw = 5;
    /**
     * How far each ring is laid under the one inside it, in world units, times its level.
     *
     * <p>The rings are cut to overlap by one cut rather than to meet, because meeting is where the crack
     * would be: a level's heightfield is the minimum of its children's and disagrees with the finer ground
     * beside it, so an exact join shows daylight through the seam wherever the coarse side is lower. An
     * overlap has the finer ground drawn over the coarser, and the sink makes which is which a fact of the
     * geometry rather than of whether two {@code minz} samples happen to agree — which on flat ground they
     * do.
     */
    private static final float sink = 2f;
    /** How many cells one sweep may read off the disk. */
    private static final int maxread = 4;

    /** One cache per level, indexed 1..maxlvl. Never ticked and never sent for, exactly as {@link Recall}. */
    private final MCache[] maps = new MCache[maxlvl + 1];
    @SuppressWarnings("unchecked")
    private final Map<String, Integer>[] tileids = new Map[maxlvl + 1];
    private final int[] nexttile = new int[maxlvl + 1];
    private final Session sess;

    public Horizon(Session sess) {
	this.sess = sess;
	for(int i = 1; i <= maxlvl; i++) {
	    maps[i] = new MCache(sess);
	    tileids[i] = new HashMap<String, Integer>();
	    nodraw[i] = new HashSet<Coord>();
	}
    }

    /** The cache holding level {@code lvl}'s cells, whose grid coords ARE that level's zoom coords. */
    public MCache map(int lvl) {return(maps[lvl]);}

    /** What the cells' coordinates are relative to; replaced whole, never mutated ({@code Recall.Base}'s reason). */
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

    private volatile Base base = null;
    private volatile boolean proven = false;
    private volatile boolean mustrelease = false;
    private volatile String unbased = "no session location yet";
    private volatile boolean sweeping = false;

    /** Where each level's block is centred, in that level's own zoom coords. Read by the rasters. */
    private final Coord[] centres = new Coord[maxlvl + 1];
    /** Cells asked for and not yet installed, so the read budget bounds NEW work (120.2's lesson). */
    private final Set<String> pending = new HashSet<String>();
    /**
     * Cells the record has nothing under, which is most of them: a block reaches three cells past the
     * character and the map only holds ground that was walked.
     *
     * <p>Without this the budget never gets past them. A sweep walks the block in coord order and stops at
     * {@link #maxread}, so the same handful of unrecorded cells at the near corner are asked again every
     * tick, answer {@code null} again every tick, and the cells that DO hold ground are never reached at
     * all. Nothing is drawn and nothing says why except a counter climbing into the tens of thousands.
     *
     * <p>It is dropped when a level's centre moves to another cell, which is the cheapest honest re-ask:
     * ground you have newly walked is recorded, and you cannot walk without moving.
     */
    private final Set<String> empty = new HashSet<String>();
    private volatile int nread = 0, nempty = 0, nfailed = 0, nstale = 0, npoison = 0, nhealed = 0;
    private volatile String lasterr = null;

    /** Whether the rings may be drawn: there is a base, a sweep proved it, and nothing stale is held. */
    public boolean ready() {
	return((base != null) && proven && !mustrelease);
    }

    /** The session tile coord of the segment origin — what each level's slot translates by. */
    public Coord tc() {
	Base b = this.base;
	return((b == null) ? Coord.z : b.tc);
    }

    /** Where level {@code lvl}'s block is centred, in its own zoom coords, or {@code null} before a tick. */
    public Coord centre(int lvl) {return(centres[lvl]);}

    /** How far under the live ground level {@code lvl} is laid. */
    public static float sink(int lvl) {return(sink * lvl);}

    /**
     * Called from the view's tick, on the UI thread. Re-derives the base, drops everything if it moved, and
     * starts one sweep at a time; everything that touches the disk is on the sweep's thread.
     */
    public void tick(MiniMap mm, Coord2d center) {
	if((mm == null) || (mm.file == null)) {
	    unbased = "no map database yet";
	    return;
	}
	MiniMap.Location loc = mm.sessloc;
	if(loc == null) {
	    unbased = "no session location yet";
	    return;
	}
	/* sessloc.tc is grid-aligned, or the offset below is a truncation and every cell lands short. */
	if(!loc.tc.mod(MCache.cmaps).equals(Coord.z)) {
	    unbased = "session location " + loc.tc + " is not grid-aligned";
	    return;
	}
	Base cur = this.base;
	if((cur == null) || !cur.sameas(loc)) {
	    /* An offset is never carried across a re-base: a grid coord that meant somewhere before means
	     * somewhere else afterwards, so everything read through the old one is wrong everywhere. */
	    proven = false;
	    mustrelease = true;
	    this.base = cur = new Base(mm.file, loc);
	}
	unbased = null;
	if(center == null)
	    return;
	Coord sgc = center.floor(MCache.tilesz).div(MCache.cmaps).add(cur.off);   // the character's segment grid
	for(int lvl = 1; lvl <= maxlvl; lvl++) {
	    Coord zc0 = sgc.div(1 << lvl);
	    if(!zc0.equals(centres[lvl])) {
		/* The block moved, so what was unrecorded under it is worth asking about again -- ground the
		 * character has walked since is in the map now, and moving is the only way to have walked it. */
		synchronized(pending) {
		    empty.clear();
		}
	    }
	    centres[lvl] = zc0;
	    /* Trim to what is READ, which is a whole cell wider than what is drawn -- so trimming never
	     * disposes a cell the raster is holding a cut of, the one way this could reach through a
	     * disposed mesh (120.4's rule, and the ROADMAP defect it keeps out of reach). */
	    maps[lvl].trim(zc0.sub(load / 2, load / 2), zc0.add(load / 2, load / 2));
	}
	if(sweeping)
	    return;
	sweeping = true;
	final Base fbase = cur;
	final Coord fsgc = sgc;
	Defer.later(new Defer.Callable<Object>() {
		public Object call() {
		    try {
			sweep(fbase, fsgc);
		    } catch(RuntimeException e) {
			lasterr = String.valueOf(e);
		    } finally {
			sweeping = false;
		    }
		    return(null);
		}

		public String toString() {return("Reading the horizon...");}
	    });
    }

    /**
     * One pass: prove the base, then read what the levels want and have not got.
     *
     * <p>The lock is a {@code tryLock} and never a {@code lock}: {@link MapFile}'s processor thread holds the
     * write lock across disk I/O, and waiting for it here would park a {@code Defer} worker for as long as a
     * segment save takes.
     */
    private void sweep(Base base, Coord sgc) {
	Map<String, haven.Indir<? extends MapFile.DataGrid>> got =
	    new HashMap<String, haven.Indir<? extends MapFile.DataGrid>>();
	Map<String, Coord> where = new HashMap<String, Coord>();
	Map<String, Integer> level = new HashMap<String, Integer>();
	Map<String, boolean[]> cover = new HashMap<String, boolean[]>();
	if(!base.file.lock.readLock().tryLock())
	    return;
	try {
	    /* Prove the offset before reading a cell through it. sessloc goes STALE rather than null -- for a
	     * window after the server drops the map it still names the segment just left, while the session
	     * coordinate space underneath has been re-based -- and a grid id is the server's and means the
	     * same in every frame, so asking the record what it has at a LIVE grid's coord and comparing the
	     * two is the offset checking itself. Recall proves it the same way, and for the same reason. */
	    int checked = 0, wrong = 0;
	    for(MCache.Grid lg : AddonWidgets.loadedGrids(sess.glob.map)) {
		if(lg.id == 0)
		    continue;
		Long rid = base.seg.gridid(lg.gc.add(base.off));
		if(rid == null)
		    continue;
		checked++;
		if(rid.longValue() != lg.id)
		    wrong++;
	    }
	    if((checked == 0) || (wrong > 0)) {
		nstale++;
		if(proven)
		    mustrelease = true;
		proven = false;
		return;
	    }
	    proven = true;
	    /* Every cell the blocks want and have not got, NEAREST FIRST and the finer level first. The order
	     * is the whole of what the budget spends itself on: walked in coord order it starts at the far
	     * corner of the outermost ring, which is the ground least likely to be recorded and the least
	     * worth having if it is. */
	    List<Want> cand = new ArrayList<Want>();
	    for(int lvl = 1; lvl <= maxlvl; lvl++) {
		Coord zc0 = sgc.div(1 << lvl);
		for(int dy = -(load / 2); dy <= (load / 2); dy++) {
		    for(int dx = -(load / 2); dx <= (load / 2); dx++) {
			Coord zc = zc0.add(dx, dy);
			String k = key(lvl, zc);
			if(AddonWidgets.loadedGrid(maps[lvl], zc) != null)
			    continue;
			synchronized(pending) {
			    if(pending.contains(k) || empty.contains(k))
				continue;
			}
			/* What the record has UNDER this cell, grid by grid. A cell with none of them is
			 * ground you have never walked and there is nothing to draw; a cell with some is the
			 * frontier, and the ones it has are drawn while the rest are held out by `nodraw`. */
			boolean[] have = coverage(base, lvl, zc);
			boolean any = false;
			for(int i = 0; i < have.length; i++)
			    any |= have[i];
			if(!any) {
			    synchronized(pending) {
				empty.add(k);
			    }
			    nempty++;
			    continue;
			}
			cand.add(new Want(lvl, zc, k, (dx * dx) + (dy * dy), have));
		    }
		}
	    }
	    Collections.sort(cand, new Comparator<Want>() {
		    public int compare(Want a, Want b) {
			if(a.lvl != b.lvl)
			    return(a.lvl - b.lvl);
			return(a.dist - b.dist);
		    }
		});
	    for(Want w : cand) {
		if(got.size() >= maxread)
		    break;
		got.put(w.key, base.seg.grid(w.lvl, w.zc.mul(1 << w.lvl)));
		where.put(w.key, w.zc);
		level.put(w.key, Integer.valueOf(w.lvl));
		cover.put(w.key, w.have);
	    }
	} finally {
	    base.file.lock.readLock().unlock();
	}
	synchronized(pending) {
	    pending.addAll(got.keySet());
	}
	for(Map.Entry<String, haven.Indir<? extends MapFile.DataGrid>> ent : got.entrySet()) {
	    String k = ent.getKey();
	    int lvl = level.get(k).intValue();
	    Coord zc = where.get(k);
	    boolean[] have = cover.get(k);
	    /* Whether the record is whole under this cell, from the coverage taken UNDER THE LOCK above.
	     * Asking the segment again here would be asking it from outside the lock, and Segment.checklock
	     * answers that with an IllegalMonitorStateException -- which this loop catches, counts as a
	     * failed cell and never installs. Every frontier cell reaches this branch, so getting it wrong
	     * costs exactly the ground this is for. */
	    boolean allhave = (have != null);
	    if(have != null) {
		for(int i = 0; i < have.length; i++)
		    allhave &= have[i];
	    }
	    try {
		MapFile.DataGrid g = ent.getValue().get();
		if((g != null) && poisoned(g) && allhave) {
		    /* A saved cell built from an INCOMPLETE record: ZoomGrid.from fills a missing child with
		     * DataGrid.nogrid and then saves the result, so the file on disk carries gfx/tiles/notile
		     * for ever. The coverage cannot see it -- that reads the record, and fetch answers from
		     * the file without rechecking -- and ZoomGrid.inval only fires when a NEW grid is recorded, which
		     * never happens again for ground walked long ago. Blank the file so it is rebuilt from the
		     * record that is whole now, and take the cell out of this run: Segment's own zcache still
		     * holds the poisoned object, and it is private. */
		    npoison++;
		    try {
			MapFile.ZoomGrid.inval(base.file, base.segid, zc.mul(1 << lvl));
			nhealed++;
		    } catch(RuntimeException e) {
			lasterr = String.valueOf(e);
		    }
		    synchronized(pending) {
			empty.add(k);
		    }
		} else if(g == null) {
		    /* Nothing recorded under this cell at all, and remembering that is what lets the budget
		     * move on to a cell that has something. */
		    nempty++;
		    synchronized(pending) {
			empty.add(k);
		    }
		} else {
		    install(lvl, zc, g, have);
		    nread++;
		}
	    } catch(Loading l) {
		/* The pyramid is still being built off the disk -- ZoomGrid.from combines four cells of the
		 * level below and saves the result, so the first ask for a level is the whole subtree under
		 * it. Nothing to do but ask again; the Indir is cached in the segment, so the second ask is
		 * free. */
	    } catch(RuntimeException e) {
		nfailed++;
		lasterr = String.valueOf(e);
	    } finally {
		synchronized(pending) {
		    pending.remove(k);
		}
	    }
	}
    }

    private static String key(int lvl, Coord zc) {
	return(lvl + ":" + zc.x + "," + zc.y);
    }

    /**
     * How many of this cell's own real grids the record has no id for, and the first one it names.
     *
     * <p>{@link #whole} refuses a cell on this count being anything but zero, so it is the whole of why a
     * cell is missing when nothing failed and nothing was poisoned. Takes the lock itself: the console asks
     * from the UI thread, where the sweep's own is long released.
     */
    private int missing(Base base, int lvl, Coord zc) {
	if(!base.file.lock.readLock().tryLock())
	    return(-1);
	try {
	    Coord gc = zc.mul(1 << lvl);
	    int n = 1 << lvl, out = 0;
	    for(int y = 0; y < n; y++) {
		for(int x = 0; x < n; x++) {
		    if(base.seg.gridid(gc.add(x, y)) == null) {
			if(out == 0)
			    firstmissing = gc.add(x, y);
			out++;
		    }
		}
	    }
	    return(out);
	} finally {
	    base.file.lock.readLock().unlock();
	}
    }

    private Coord firstmissing = null;

    /**
     * Was this cell built over a hole? {@code ZoomGrid.from} fills a missing child with
     * {@code DataGrid.nogrid}, whose one tileset is {@code gfx/tiles/notile} — a resource with no
     * {@code Tileset} layer, which {@code MapMesh} cannot mesh and the map window never notices.
     *
     * <p>The name is the whole test, and it is exact: nothing else in the record is ever that resource.
     */
    private static boolean poisoned(MapFile.DataGrid g) {
	for(int i = 0; i < g.tilesets.length; i++) {
	    if("gfx/tiles/notile".equals(g.tilesets[i].res.name))
		return(true);
	}
	return(false);
    }

    /**
     * Which of this cell's own real grids the record holds, row-major over {@code 2^lvl} a side. The caller
     * holds the read lock {@code gridid} requires.
     */
    private boolean[] coverage(Base base, int lvl, Coord zc) {
	Coord gc = zc.mul(1 << lvl);
	int n = 1 << lvl;
	boolean[] out = new boolean[n * n];
	for(int y = 0; y < n; y++) {
	    for(int x = 0; x < n; x++)
		out[x + (y * n)] = (base.seg.gridid(gc.add(x, y)) != null);
	}
	return(out);
    }


    /** One cell a sweep would like, how far out of the block's centre it is, and what the record has. */
    private static final class Want {
	final int lvl, dist;
	final Coord zc;
	final String key;
	/** Which of the cell's own {@code 2^lvl} x {@code 2^lvl} real grids the record holds, row-major. */
	final boolean[] have;

	Want(int lvl, Coord zc, String key, int dist, boolean[] have) {
	    this.lvl = lvl;
	    this.zc = zc;
	    this.key = key;
	    this.dist = dist;
	    this.have = have;
	}
    }

    /**
     * Cache cuts that must not be drawn: the ground under them was never walked.
     *
     * <p>A cell is 2^lvl real grids a side and the record's frontier is ragged at grid granularity, so a
     * cell at the edge of what you have explored holds some of its grids and not others — one, two or three
     * of four, which is what {@code :horizon}'s digits say. Refusing the whole cell for that throws away the
     * grids it does have, and at 200 tiles a cell that is most of the missing horizon.
     *
     * <p>The cut is the right unit instead, and it divides exactly: a cell's 4x4 cuts map onto its
     * {@code 2^lvl} x {@code 2^lvl} grids with {@code 4 / 2^lvl} cuts to a side of each, so every real grid
     * is a whole block of cuts and no cut straddles two.
     */
    @SuppressWarnings("unchecked")
    private final Set<Coord>[] nodraw = new Set[maxlvl + 1];

    /** Is this cache cut over ground the record never had? Asked by the raster, on the UI thread. */
    public boolean nodraw(int lvl, Coord cc) {
	Set<Coord> s = nodraw[lvl];
	if(s == null)
	    return(false);
	synchronized(s) {
	    return(s.contains(cc));
	}
    }

    /**
     * Put one zoom cell into its level's cache, as the grid at its own zoom coord.
     *
     * <p>The tile ids are that cache's own, minted in first-seen order, exactly as {@link Recall} mints
     * them: the record carries a tileset resource name and version, and the index the server assigned for
     * this session means nothing to it. Nothing but this level's own meshes ever reads them.
     */
    private void install(int lvl, Coord zc, MapFile.DataGrid g, boolean[] have) {
	/* A stand-in for gfx/tiles/notile, which ZoomGrid.from writes wherever a child was missing and which
	 * has no Tileset layer to mesh with. The cuts over it are held out of the scene by `nodraw` below, so
	 * this is never ground anyone sees -- what it buys is that MCache.tiler always resolves, because
	 * dotrans reads one tile ACROSS a cut edge and would otherwise take the frame from the good cut
	 * beside the hole. */
	int stand = -1;
	for(int i = 0; i < g.tilesets.length; i++) {
	    if(!"gfx/tiles/notile".equals(g.tilesets[i].res.name)) {
		stand = tileid(lvl, g.tilesets[i]);
		break;
	    }
	}
	if(stand < 0)
	    return;                       // nothing but holes; there is no ground here to draw
	int[] gmap = new int[g.tilesets.length];
	for(int i = 0; i < gmap.length; i++) {
	    gmap[i] = "gfx/tiles/notile".equals(g.tilesets[i].res.name) ? stand : tileid(lvl, g.tilesets[i]);
	}
	int[] tiles = new int[g.tiles.length];
	for(int i = 0; i < tiles.length; i++)
	    tiles[i] = gmap[g.tiles[i]];
	/* A zoom cell carries no server grid id -- it is four of them combined -- so one is minted from the
	 * cell's own address. It is the mesh's random seed and this cache's identity for the cell, no more. */
	long id = (((long)lvl) << 56) ^ (((long)zc.x) << 28) ^ (zc.y & 0xfffffffL) ^ 1L;
	AddonWidgets.putgrid(maps[lvl], zc, id, tiles, g.zmap);
	/* And which of its cuts stand over ground the record never had. A cell's 4x4 cuts divide exactly
	 * among its 2^lvl x 2^lvl grids, so a missing grid is a whole block of cuts and no cut straddles. */
	if(have != null) {
	    int n = 1 << lvl, per = MCache.cutn.x / n;
	    Coord base = zc.mul(MCache.cutn);
	    synchronized(nodraw[lvl]) {
		for(int gy = 0; gy < n; gy++) {
		    for(int gx = 0; gx < n; gx++) {
			if(have[gx + (gy * n)])
			    continue;
			for(int cy = 0; cy < per; cy++) {
			    for(int cx = 0; cx < per; cx++)
				nodraw[lvl].add(base.add((gx * per) + cx, (gy * per) + cy));
			}
		    }
		}
	    }
	}
    }

    private int tileid(int lvl, MapFile.TileInfo ti) {
	String key = ti.res.name + ":" + ti.res.ver;
	Integer id = tileids[lvl].get(key);
	if(id == null) {
	    id = Integer.valueOf(nexttile[lvl]++);
	    tileids[lvl].put(key, id);
	    AddonWidgets.settileset(maps[lvl], id.intValue(), ti.res);
	}
	return(id.intValue());
    }

    /**
     * Drop everything read through a base that is gone or was never proved. Called by whatever draws this
     * source, <b>after</b> it has taken its rasters out of the scene: {@code trimall} disposes every cut
     * mesh, and a raster still holding one goes on drawing it.
     */
    public void release() {
	if(!mustrelease)
	    return;
	mustrelease = false;
	for(int lvl = 1; lvl <= maxlvl; lvl++) {
	    maps[lvl].trimall();
	    synchronized(nodraw[lvl]) {
		nodraw[lvl].clear();
	    }
	}
	synchronized(pending) {
	    pending.clear();
	    empty.clear();
	}
    }

    /** What {@code :horizon} prints. */
    public List<String> report() {
	List<String> out = new ArrayList<String>();
	Base b = this.base;
	if(b == null) {
	    out.add("horizon: no base -- " + unbased);
	} else {
	    out.add(String.format("horizon: segment %s, session tile %s, grid offset %s, %s",
				  Long.toUnsignedString(b.segid, 16), b.tc, b.off,
				  proven ? "proved against a live grid" : "NOT PROVED -- nothing is drawn"));
	}
	for(int lvl = 1; lvl <= maxlvl; lvl++) {
	    int nd;
	    synchronized(nodraw[lvl]) {
		nd = nodraw[lvl].size();
	    }
	    out.add(String.format("horizon: L%d %d cuts held out as never walked", lvl, nd));
	    out.add(String.format("horizon: L%d centred %s, cells held %d of %d, cuts live %d,"
				  + " %d tiles a cell, scaled x%d, sunk %d",
				  lvl, centres[lvl], maps[lvl].numgrids(), load * load,
				  maps[lvl].numcuts(), MCache.cmaps.x << lvl, 1 << lvl, (int)sink(lvl)));
	}
	int p, e;
	synchronized(pending) {
	    p = pending.size();
	    e = empty.size();
	}
	out.add(String.format("horizon: cells read %d, waiting %d, not wholly walked %d held (%d found),"
			      + " failed %d, stale sweeps %d",
			      nread, p, e, nempty, nfailed, nstale));
	if(npoison > 0) {
	    out.add(String.format("horizon: %d cells were saved over a hole by an earlier run, %d blanked"
				  + " -- RESTART the client and they rebuild whole", npoison, nhealed));
	}
	/* The read block, cell by cell, because a counter cannot say WHICH cell is missing and that is the
	 * only question worth asking of this. `#` held and meshable, `.` not wholly walked, `?` asked for
	 * and not back yet, `+` the block's own centre. */
	Base rb = this.base;
	if(rb != null) {
	    firstmissing = null;
	    for(int lvl = 1; lvl <= maxlvl; lvl++) {
		Coord zc0 = centres[lvl];
		if(zc0 == null)
		    continue;
		int d = load / 2;
		for(int dy = -d; dy <= d; dy++) {
		    StringBuilder row = new StringBuilder();
		    for(int dx = -d; dx <= d; dx++) {
			Coord zc = zc0.add(dx, dy);
			String k = key(lvl, zc);
			char c;
			if(AddonWidgets.loadedGrid(maps[lvl], zc) != null) {
			    c = ((dx == 0) && (dy == 0)) ? '+' : '#';
			} else {
			    boolean waiting;
			    synchronized(pending) {
				waiting = pending.contains(k);
			    }
			    /* A digit rather than a dot: HOW MANY of the cell's own real grids the record has
			     * no id for. That is the difference between "the map really does not have this
			     * ground" and "the check that asks is wrong", and no counter can tell them apart. */
			    c = waiting ? '?' : (char)('0' + Math.min(missing(rb, lvl, zc), 9));
			}
			/* Outside the drawn block it is read for dotrans alone and never meshed. */
			row.append(((Math.abs(dx) > (draw / 2)) || (Math.abs(dy) > (draw / 2)))
				   ? Character.toLowerCase(c) : c);
		    }
		    out.add(String.format("horizon: L%d %s%s", lvl, row,
					  (dy == -d) ? "   (# held, digit = real grids the record lacks, ? waiting)" : ""));
		}
	    }
	}
	/* Zero by construction rather than by a counter: nothing ticks these caches, so sendreqs() never runs
	 * on one. The number beside it is what is worth reading -- a queue above zero says something called
	 * getcut() for a cell the cache has not got. */
	if(rb != null) {
	    out.add(String.format("horizon: the database holds %d segment(s); this one is %s%s",
				  rb.file.knownsegs.size(), Long.toUnsignedString(rb.segid, 16),
				  (firstmissing == null) ? "" :
				  (", and it has no grid at segment " + firstmissing)));
	}
	int q = 0;
	for(int lvl = 1; lvl <= maxlvl; lvl++)
	    q += maps[lvl].numreqs();
	out.add(String.format("horizon: requests sent 0, queued %d (live map queued %d)",
			      q, sess.glob.map.numreqs()));
	if(lasterr != null)
	    out.add("horizon: last error " + lasterr);
	return(out);
    }
}
