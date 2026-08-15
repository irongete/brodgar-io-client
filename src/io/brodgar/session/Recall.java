package io.brodgar.session;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import haven.AddonWidgets;
import haven.Coord;
import haven.Coord2d;
import haven.Defer;
import haven.Loading;
import haven.MCache;
import haven.MapFile;
import haven.MiniMap;
import haven.Session;
import haven.Utils;

/**
 * The remembered ground: a map source filled from the map database instead of from the wire.
 *
 * <p>Everything the character has explored is on disk in {@link MapFile}, at full tile resolution,
 * in the same shape a live {@link MCache.Grid} carries — an {@code int[]} of tile indices and a
 * {@code float[]} of heights. This reads it back into an {@link MCache} of its own so that the rest
 * of the client's terrain machinery, which knows how to rasterize an {@code MCache} and nothing
 * else, can draw it.
 *
 * <p><b>It never touches the wire.</b> That is structural, not a promise: the cache built here is
 * never ticked and {@code sendreqs()} is never called on it, so {@code request()}'s bookkeeping has
 * no way out. Asking the server for a grid the character is nowhere near is exactly what
 * {@link MCache#getgrid} does on a miss, and it is the one thing this must not do — which is why
 * the remembered ground gets a cache of its own rather than filling the live one, whose contents
 * every terrain verb, the click path and the durable-place resolver read as the server's own model
 * of what the character can see.
 *
 * <p><b>The bridge</b> is {@code MiniMap.sessloc}: {@code sessloc.tc} is the segment tile coord of
 * session tile {@code (0, 0)} and is grid-aligned, so a segment grid coord is a session grid coord
 * plus {@code sessloc.tc / cmaps}. That offset is re-derived every tick and nothing caches it across
 * a change: the session coordinate space is re-based mid-play (a cave, a house), and a grid coord
 * that meant somewhere before means somewhere else afterwards. When the base moves, every recalled
 * grid is dropped rather than redrawn in the wrong place.
 *
 * <p><b>Tile ids are this cache's own.</b> A live tile id is an index the server assigned for this
 * session; the map database records tileset resource <i>names</i> and versions instead. So each
 * distinct name+version seen gets an id minted here, in first-seen order, and installed through
 * {@code MCache.settileset}. The ids therefore mean nothing outside this source, which is fine —
 * nothing but this source's own meshes ever reads them.
 */
public class Recall {
    /** How far around the centre the record is read, in grids. */
    public static final int radius = 3;
    /**
     * The grid cap, and it is the whole of the budget on this side: a square of {@link #radius}
     * around the centre and not one grid more, released down to that square every tick. A grid is an
     * array copy and a handful of kilobytes — what costs is meshing one, and that cap is the drawn
     * raster's, a ring further in.
     */
    public static final int gridcap = ((radius * 2) + 1) * ((radius * 2) + 1);
    /** How many grids one sweep may start reading off the disk. */
    private static final int maxread = 8;
    /** How often the sweep runs, in seconds. */
    private static final double period = 0.25;

    /** The recalled source. Never ticked, never sent for; read by whatever rasterizes it. */
    public final MCache map;
    private final Session sess;

    /**
     * What the recalled grid coords are relative to. Immutable and replaced whole, because the sweep
     * reads it from a {@link Defer} thread while the tick writes it: a half-updated base would place
     * ground in the wrong world.
     */
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
    private volatile String unbased = "no session location yet";

    /**
     * Whether a sweep has shown the offset to be the right one, and whether what is held must go.
     *
     * <p>A base that has merely been derived is not a base that has been <i>proved</i>. {@code sessloc}
     * goes stale rather than null: for a window after the server drops the map it still names the
     * segment just left, while the session coordinate space underneath has already been re-based — and
     * everything read through it in that window is placed somewhere it never was. So nothing is drawn
     * until a sweep has checked the offset against a live grid's id, and the moment one fails, what
     * was read through the old offset is dropped rather than left standing.
     *
     * <p>Dropping is {@link #release()}'s job and never done here, because the order matters: the
     * raster comes out of the scene first, and only then are the meshes it was holding disposed.
     */
    private volatile boolean proven = false;
    private volatile boolean mustrelease = false;

    /* The per-source tile remap. Touched by the sweep alone, and only one sweep runs at a time. */
    private final Map<String, Integer> tileids = new HashMap<String, Integer>();
    private int nexttile = 0;

    private volatile boolean sweeping = false;
    private double lastsweep = 0;

    private volatile int nread = 0, nfailed = 0, nrebase = 0, nblank = 0, nwaiting = 0, nstale = 0;
    private volatile int nreleased = 0;
    private volatile String lasterr = null;

    /**
     * The cache is built on the live {@link Session} so that {@code sess.glob} and the resource pool
     * are the ones already running — a tileset resolved for the remembered ground is the same object
     * the live map resolved, and costs nothing twice.
     */
    public Recall(Session sess) {
	this.sess = sess;
	this.map = new MCache(sess);
    }

    /**
     * Called from the drawn view's tick, on the UI thread. Re-derives the base, drops everything if
     * it moved, and starts one sweep at a time. Everything expensive is on the sweep's thread.
     *
     * @param mm     the corner minimap — the one instance whose {@code sessloc} the rest of the
     *               client reads; {@code null} before the HUD is up
     * @param center where the ground should be read around, in world coords, or {@code null} when
     *               nothing can be placed yet
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
	/* sessloc.tc is derived from a live grid's GridInfo and is grid-aligned. If it ever is not,
	 * the offset below would be a truncation and every recalled grid would land a fraction of a
	 * grid out -- so refuse the base rather than draw the record somewhere it is not. */
	if(!loc.tc.mod(MCache.cmaps).equals(Coord.z)) {
	    unbased = "session location " + loc.tc + " is not grid-aligned";
	    return;
	}
	Base cur = this.base;
	if((cur == null) || !cur.sameas(loc)) {
	    if(cur != null)
		nrebase++;
	    /* The offset is re-derived, never carried across the move: a grid coord that meant somewhere
	     * before means somewhere else afterwards, so what was read through the old one is now wrong
	     * everywhere. Nothing is drawn again until a sweep has proved the new offset. */
	    proven = false;
	    mustrelease = true;
	    this.base = cur = new Base(mm.file, loc);
	}
	unbased = null;
	if(center == null)
	    return;
	/* Release, every tick and not merely at the end of a sweep that got the file lock. The kept
	 * square is concentric with the drawn one and a whole grid wider, so trimming never disposes a
	 * grid the raster is holding a cut of -- which is the one way this could reach through a
	 * disposed mesh. A sweep's own centre lags this one under a fast pan; this centre is the one the
	 * raster is about to be given, so the two cannot disagree. */
	Coord gc = center.floor(MCache.tilesz).div(MCache.cmaps);
	map.trim(gc.sub(radius, radius), gc.add(radius, radius));
	double now = Utils.rtime();
	if(sweeping || ((now - lastsweep) < period))
	    return;
	lastsweep = now;
	sweeping = true;
	final Base fbase = cur;
	final Coord fc = center.floor(MCache.tilesz).div(MCache.cmaps);
	Defer.later(new Defer.Callable<Object>() {
		public Object call() {
		    try {
			sweep(fbase, fc);
		    } catch(RuntimeException e) {
			lasterr = String.valueOf(e);
		    } finally {
			sweeping = false;
		    }
		    return(null);
		}

		public String toString() {return("Recalling map...");}
	    });
    }

    /**
     * One pass: which grids of the wanted rectangle the record has, read off the disk, installed.
     *
     * <p>The lock is a {@code tryLock} and never a {@code lock}: {@link MapFile}'s processor thread
     * holds the write lock across disk I/O, and waiting for it here would stall a worker for as long
     * as a segment save takes. A sweep that cannot have the lock simply does nothing and the next
     * one gets it. Everything under the lock is an in-memory map lookup — the whole coord-to-id map
     * arrives with the segment — and the actual grid read happens outside it.
     */
    private void sweep(Base base, Coord center) {
	Map<Coord, MapFile.Grid> ready = new HashMap<Coord, MapFile.Grid>();
	Map<Coord, Long> ids = new HashMap<Coord, Long>();
	Map<Coord, haven.Indir<MapFile.Grid>> got = new HashMap<Coord, haven.Indir<MapFile.Grid>>();
	int blank = 0;
	/* Snapshot the live grids OUTSIDE the file lock: MapFile's own writers walk a map cache while
	 * holding it, and taking the two in the other order here is how that becomes a deadlock. */
	List<MCache.Grid> live = AddonWidgets.loadedGrids(sess.glob.map);
	if(!base.file.lock.readLock().tryLock())
	    return;
	try {
	    /* Prove the base before reading a single grid through it. sessloc goes STALE rather than
	     * null -- for a window after the server drops the map (a cave, a house) it still names the
	     * segment just left, while the session coordinate space underneath has already been
	     * re-based -- and reading through a stale offset is how remembered ground ends up drawn
	     * somewhere it never was. A grid id is the server's and means the same thing in every
	     * frame, so asking the record what it has at a LIVE grid's coord and comparing the two ids
	     * is the offset checking itself. Nothing recorded anywhere near proves nothing either way,
	     * and that is a refusal too: the player's own grid is recorded within a second of arriving. */
	    int checked = 0, wrong = 0;
	    for(MCache.Grid lg : live) {
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
		/* Not merely a refusal to read: what was already read came through an offset that has just
		 * failed its own check, so it goes. `checked == 0` counts as a failure and not as an
		 * unknown -- the character's own grid is recorded within a second of arriving, so nothing
		 * to compare against means the record does not know where the session is standing, which
		 * is exactly the window a re-base opens and exactly when drawing is at its most wrong. */
		nstale++;
		if(proven)
		    mustrelease = true;
		proven = false;
		return;
	    }
	    proven = true;
	    for(int y = -radius; y <= radius; y++) {
		for(int x = -radius; x <= radius; x++) {
		    Coord gc = center.add(x, y);
		    if(AddonWidgets.loadedGrid(map, gc) != null)
			continue;
		    Long id = base.seg.gridid(gc.add(base.off));
		    if(id == null) {
			/* Ground the character has never walked. Not an error and not a miss to
			 * retry into -- there is simply nothing recorded there. */
			blank++;
			continue;
		    }
		    if(got.size() >= maxread)
			continue;
		    got.put(gc, base.seg.grid(id));
		    ids.put(gc, id);
		}
	    }
	} finally {
	    base.file.lock.readLock().unlock();
	}
	int waiting = 0;
	for(Map.Entry<Coord, haven.Indir<MapFile.Grid>> ent : got.entrySet()) {
	    try {
		MapFile.Grid g = ent.getValue().get();
		if(g == null) {
		    blank++;
		    continue;
		}
		ready.put(ent.getKey(), g);
	    } catch(Loading l) {
		/* Defer has not got the file off the disk yet. Nothing to do but ask again next
		 * sweep -- the Indir is cached in the segment, so the second ask is free. */
		waiting++;
	    } catch(RuntimeException e) {
		/* A tileset loaded out of the res cache can carry illegal references, and Loading is
		 * a RuntimeException everywhere on this path. One grid's failure is not the sweep's. */
		nfailed++;
		lasterr = String.valueOf(e);
	    }
	}
	nwaiting = waiting;
	nblank = blank;
	for(Map.Entry<Coord, MapFile.Grid> ent : ready.entrySet()) {
	    try {
		install(ent.getKey(), ids.get(ent.getKey()).longValue(), ent.getValue());
		nread++;
	    } catch(RuntimeException e) {
		nfailed++;
		lasterr = String.valueOf(e);
	    }
	}
    }

    /**
     * Whether the remembered ground may be drawn: there is a base, a sweep has proved it, and nothing
     * read through a base that has since failed is still held.
     */
    public boolean ready() {
	return((base != null) && proven && !mustrelease);
    }

    /**
     * Drop everything read through a base that is gone or was never proved. Called by whatever draws
     * this source, <b>after</b> it has taken its raster out of the scene: {@code trimall} disposes
     * every cut mesh in the cache, and a raster still holding one goes on drawing it.
     */
    public void release() {
	if(!mustrelease)
	    return;
	mustrelease = false;
	nreleased++;
	map.trimall();
    }

    /** Remap the recorded grid's tile indices onto this cache's own ids, and install it. */
    private void install(Coord gc, long id, MapFile.Grid g) {
	int[] gmap = new int[g.tilesets.length];
	for(int i = 0; i < gmap.length; i++)
	    gmap[i] = tileid(g.tilesets[i]);
	int[] tiles = new int[g.tiles.length];
	for(int i = 0; i < tiles.length; i++)
	    tiles[i] = gmap[g.tiles[i]];
	AddonWidgets.putgrid(map, gc, id, tiles, g.zmap);
    }

    /**
     * This cache's id for a recorded tileset, minting one if it is new. Keyed on the resource name
     * and version, because that is the whole of what the record carries and two grids recorded weeks
     * apart must agree about which tileset is which.
     *
     * <p>The order ids are minted in is the order tilesets are first seen, which is not the order the
     * server assigned — and {@code MapMesh.dotrans} reads a tile id as its transition priority. So
     * the ground reads correctly and its tile-to-tile transitions are laid in an order of this
     * source's own.
     */
    private int tileid(MapFile.TileInfo ti) {
	String key = ti.res.name + ":" + ti.res.ver;
	Integer id = tileids.get(key);
	if(id == null) {
	    id = Integer.valueOf(nexttile++);
	    tileids.put(key, id);
	    AddonWidgets.settileset(map, id.intValue(), ti.res);
	}
	return(id.intValue());
    }

    /** What {@code :recall} prints: what this source is based on, what it holds, and what it costs. */
    public List<String> report() {
	List<String> out = new ArrayList<String>();
	Base b = this.base;
	if(b == null) {
	    out.add("recall: no base -- " + unbased);
	} else {
	    out.add(String.format("recall: segment %s, session tile %s, grid offset %s, %s",
				  Long.toUnsignedString(b.segid, 16), b.tc, b.off,
				  proven ? "proved against a live grid" : "NOT PROVED -- nothing is drawn"));
	}
	out.add(String.format("recall: grids read %d, in cache %d of %d, unrecorded %d, waiting %d, failed %d",
			      nread, map.numgrids(), gridcap, nblank, nwaiting, nfailed));
	out.add(String.format("recall: rebases %d, sweeps refused on a stale session location %d, releases %d",
			      nrebase, nstale, nreleased));
	/* "sent" is zero by construction and not by a counter: nothing ticks this source, so
	 * sendreqs() never runs on it. What is worth reading is the number beside it -- a queue above
	 * zero says something called getgrid() on this cache and would put that grid on the wire the
	 * moment anything did tick it. */
	out.add(String.format("recall: cuts live %d, requests sent 0, queued %d (live map queued %d)",
			      map.numcuts(), map.numreqs(), sess.glob.map.numreqs()));
	if(lasterr != null)
	    out.add("recall: last error " + lasterr);
	return(out);
    }
}
