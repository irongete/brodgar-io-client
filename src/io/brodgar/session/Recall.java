package io.brodgar.session;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import haven.AddonWidgets;
import haven.Coord;
import haven.Defer;
import haven.Loading;
import haven.MCache;
import haven.MapFile;
import haven.MapView;
import haven.MiniMap;
import haven.Session;

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
    /**
     * How far a read may reach around what the raster asked for, in grids: the user's own drawn range
     * ({@link MapView#recallrange}) and one grid more.
     *
     * <p>That extra ring is the fill margin the drawn raster needs rather than reach of its own.
     * {@code MapMesh.dotrans} reads a tile across the cut edge and the corner heights need the same
     * tile, so a cut at the fill's own edge throws {@code MCache.LoadingMap} and never completes —
     * which is why what is read is always one grid wider than what is drawn. {@link #want} is where
     * that ring is actually added, so what is read stays inside this square without being a square.
     *
     * <p>It is a method and not a constant because the range is a setting: a write from the panel or
     * from Lua is answered by the next set the raster hands to {@link #want}, and by the cap that set is
     * trimmed against, with nothing to rebuild and nothing to tell.
     */
    public static int radius() {
	return(MapView.recallrange + 1);
    }

    /**
     * How many read squares' worth of grids {@link #gridcap()} is, and the whole of what keeping buys:
     * one square is the ground the camera is standing over, and the other two are how far it may travel
     * and still come back to ground that is already there.
     */
    private static final int keepsquares = 3;

    /**
     * How many grids this source may hold — the whole of the budget on this side, and not a square.
     *
     * <p>What is read is kept until this cap says otherwise, in the order it was last wanted (see
     * {@link #trim}). A square around the camera cannot keep anything at all: one grid of travel puts a
     * whole rank of grids outside it, so panning one way and back re-reads and re-meshes every one of
     * them, which is what made the second visit cost as much as the first.
     *
     * <p>The cap is {@link #keepsquares} times the read square, so at the default range it is a hundred
     * and forty-seven grids and two whole squares — fourteen grids — of travel come back free. A grid is
     * an array copy and some eighty kilobytes; what is expensive is meshing one, and that cap is the
     * drawn raster's, a ring further in.
     */
    public static int gridcap() {
	int r = radius();
	return(((r * 2) + 1) * ((r * 2) + 1) * keepsquares);
    }

    /**
     * How many grids one sweep may <b>start</b> reading off the disk, which is not how many may be
     * outstanding: a grid already asked for costs nothing to ask again (see {@link #pending}), so this
     * bounds new asks alone and the whole wanted set is asked for in a few ticks rather than a second.
     *
     * <p>It stays a small number all the same, and that is not about the disk. An ask is
     * {@code MapFile.Segment.loadgrid} — a {@link Defer} task that takes the file's read lock with
     * {@code lock()} and therefore <b>parks a worker</b> for as long as a segment save holds the write
     * lock. Raise this past what the pool can spare and the mesh builds queued behind it in that same
     * pool are what pays.
     */
    private static final int maxread = 8;

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

    /**
     * What to read: every grid the drawn raster asked for and one grid more in every direction, which is
     * the fill margin {@link #radius()} describes. Immutable and replaced whole, because the sweep reads
     * it from a {@link Defer} thread while the raster hands it over from the UI thread.
     *
     * <p>Empty is the ordinary state and not a failure — with the raster out of the scene nothing is
     * wanted, and <b>nothing is read</b>. The sweep still runs, because proving the base is what lets the
     * raster come back at all.
     */
    private volatile Set<Coord> readset = Collections.emptySet();

    /**
     * Grid coords asked for and not yet installed — what makes {@link #maxread} a bound on <b>new</b>
     * asks. Without it a grid whose {@code Indir} is still {@code Loading} consumes one of the sweep's
     * slots again on every sweep, so the cap is that many <i>in flight</i> and merely asking for the
     * wanted set takes as long as reading it.
     *
     * <p>Asking a second time is free and is still done, because that is how the answer is collected: the
     * {@code Indir} is cached in the segment, so the second {@code get()} is a check on a future that is
     * already running. What a pending coord does not do is take a slot from a grid never asked for.
     *
     * <p>It is cleared with {@code proven}/{@code mustrelease}: a coord asked for through a base that has
     * since moved names somewhere else now, and nothing read through it may install behind the
     * {@code trimall} that dropped its neighbours.
     */
    private final Set<Coord> pending = Collections.newSetFromMap(new ConcurrentHashMap<Coord, Boolean>());

    /**
     * The grids this source holds, least recently wanted first — the keep set, and the reason a pan back
     * over ground already read draws it with nothing to rebuild.
     *
     * <p>Access-ordered, so wanting a grid is what makes it recent and {@link #trim} drops from this end.
     * Its membership is the cache's and is reconciled with it on every trim rather than tracked: a coord
     * wanted over ground the record has nothing at is never installed and must not count against the cap,
     * and a grid installed by a sweep whose wanted set has since moved must.
     *
     * <p>Both threads reach it — the tick wants and the sweep makes room — so every use is under its own
     * monitor, and {@code map}'s is always taken inside it and never the other way about.
     */
    private final LinkedHashMap<Coord, Boolean> lru = new LinkedHashMap<Coord, Boolean>(64, 0.75f, true);

    /**
     * What the raster was holding a cut of when it last spoke, and what {@link #trim} may never drop.
     *
     * <p>A field rather than an argument because the sweep trims too, from its own thread, and it has no
     * raster to ask. One tick stale there at worst, and that is sound: a grid whose cut has just entered
     * the scene was wanted on the tick that read it, so it sits at the recent end of {@link #lru} while a
     * trim drops from the other — which can only reach that end when the whole cache was wanted at once,
     * and that is a third of the cap. The pin is what makes that an invariant rather than an argument.
     */
    private volatile Set<Coord> pinned = Collections.emptySet();

    private volatile boolean sweeping = false;

    private volatile int nread = 0, nfailed = 0, nrebase = 0, nblank = 0, nstale = 0;
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
     * Called from the drawn view's tick, on the UI thread, and <b>first</b>: re-derives the base and
     * drops everything if it moved, so a re-base takes the ground out of the scene in the frame it is
     * found rather than the next one. What is read is decided after that, by {@link #want}, and the
     * reading itself is started by {@link #read} once the raster has said what it wants.
     *
     * <p>It centres nothing and releases nothing. Where the ground is read around is the raster's own
     * question and reaches this source through {@link #want}; what is kept is an LRU over the grids that
     * set has named, trimmed there too. A square released around a centre every tick is what made panning
     * one grid dispose a rank of them and panning back rebuild every mesh in it.
     *
     * @param mm the corner minimap — the one instance whose {@code sessloc} the rest of the client
     *           reads; {@code null} before the HUD is up
     */
    public void tick(MiniMap mm) {
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
	    /* And the asks in flight go with them: a coord asked for through the offset just left names
	     * somewhere else under the new one. */
	    pending.clear();
	    this.base = cur = new Base(mm.file, loc);
	}
	unbased = null;
    }

    /**
     * Hand over the grids the drawn raster wants, in session grid coords — the one place that decides
     * what this source is for, and {@code null} or an empty set for <b>nothing</b>, which is what the
     * raster wants while it is out of the scene.
     *
     * <p>The margin is added here rather than asked for: {@code MapMesh.dotrans} reads a tile across the
     * cut edge and the corner heights need the same tile, so a cut at the fill's own edge throws
     * {@code MCache.LoadingMap} and never completes — see {@link #radius()}. So what is read is always
     * the wanted set dilated by one grid, and the raster asks for exactly what it means to draw.
     *
     * <p>Wanting is also what makes a grid <b>recent</b>, so this is where the keep set is decided and
     * where it is trimmed. Nothing wanted is not nothing kept: with the raster out of the scene what was
     * built stands exactly where it was, and the camera coming back to it draws it with nothing to
     * rebuild. It is the cap alone that ever drops a grid.
     *
     * <p>Called on the UI thread, <b>after</b> the raster has ticked, and the set is built here and never
     * touched again, so the sweep may read it from its own thread.
     *
     * @param grids what the raster means to draw, or {@code null} for nothing
     * @param held  the grids the raster is holding a cut of, which are never dropped whatever the LRU
     *              says, or {@code null} when it is holding none
     */
    public void want(Set<Coord> grids, Set<Coord> held) {
	pinned = (held == null) ? Collections.<Coord>emptySet() : held;
	if((grids == null) || grids.isEmpty()) {
	    readset = Collections.emptySet();
	} else {
	    Set<Coord> out = new HashSet<Coord>();
	    for(Coord gc : grids) {
		for(int y = -1; y <= 1; y++) {
		    for(int x = -1; x <= 1; x++)
			out.add(gc.add(x, y));
		}
	    }
	    readset = out;
	    /* Every coord of what is read and not merely of what is drawn, because what is read is what
	     * is held and this cap is on what is held. An access-ordered map, so this is the touch that
	     * makes a grid the most recent one there is. */
	    synchronized(lru) {
		for(Coord gc : out)
		    lru.put(gc, Boolean.TRUE);
	    }
	}
	trim(0);
    }

    /**
     * Drop the least recently wanted grids down to {@link #gridcap()} — and never a grid the raster is
     * holding a cut of ({@link #pinned}), whatever the order says.
     *
     * <p>That exception is the whole of the safety here, and it is not the LRU being polite. A
     * {@code MCache.Grid} disposes its cut meshes with itself, and {@code MapRaster.Grid} holds a scene
     * slot per cut it has drawn — so a grid dropped while the raster holds one of its cuts leaves that
     * slot drawing a disposed mesh, with nothing to notice. The raster's own cut map is therefore the
     * authority over this budget rather than the other way about, and it can never cost more than the cap:
     * what holds a cut is inside the drawn square, which is a ring smaller than the read square this cap
     * is a multiple of.
     *
     * <p>What goes is named rather than what stays ({@code MCache.drop}), because a grid may arrive from a
     * {@link Defer} thread at any moment and naming what stays would dispose one read off the disk before
     * anything could draw it.
     *
     * @param room how many grids the caller is about to install, so that the cap bounds what is
     *             <b>held</b> and not what was held one tick ago: an install that makes its own room
     *             first can never carry the count above the cap, while a trim on the tick alone leaves it
     *             there until the next one, which is as long as anything reading the gauge cares to look.
     */
    private void trim(int room) {
	List<Coord> drop = new ArrayList<Coord>();
	synchronized(lru) {
	    /* The order is this map's; the membership is the cache's. A wanted coord the record has nothing
	     * at is never installed and must not count against a cap on what is held, and a grid a sweep
	     * installed after the wanted set moved off it must -- or it would be held by nothing and
	     * dropped by nothing. */
	    Set<Coord> have = new HashSet<Coord>();
	    for(MCache.Grid g : AddonWidgets.loadedGrids(map))
		have.add(g.gc);
	    for(Coord gc : have) {
		if(!lru.containsKey(gc))
		    lru.put(gc, Boolean.TRUE);
	    }
	    lru.keySet().retainAll(have);
	    int over = (lru.size() + room) - gridcap();
	    if(over <= 0)
		return;
	    Set<Coord> pin = this.pinned;
	    for(Iterator<Coord> i = lru.keySet().iterator(); i.hasNext() && (over > 0);) {
		Coord gc = i.next();
		if(pin.contains(gc))
		    continue;
		i.remove();
		drop.add(gc);
		over--;
	    }
	    map.drop(drop);
	}
    }

    /**
     * Start one pass of reading, if none is running. Called from the drawn view's tick and <b>last</b>,
     * after {@link #want} has said what there is to read.
     *
     * <p>Once a ctick and not once a quarter second: {@code sweeping} already serialises, so the pass
     * that matters is the one that is not running yet, and what a pass may start is bounded by
     * {@link #maxread} rather than by a clock. It runs with nothing wanted too, and must — a sweep is
     * what proves the base, and until one has, nothing is drawn at all.
     */
    public void read() {
	final Base fbase = this.base;
	if((fbase == null) || sweeping)
	    return;
	sweeping = true;
	Defer.later(new Defer.Callable<Object>() {
		public Object call() {
		    try {
			sweep(fbase);
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
     * One pass: which grids of the wanted set the record has, read off the disk, installed.
     *
     * <p>The lock is a {@code tryLock} and never a {@code lock}: {@link MapFile}'s processor thread
     * holds the write lock across disk I/O, and waiting for it here would stall a worker for as long
     * as a segment save takes. A sweep that cannot have the lock simply does nothing and the next
     * one gets it. Everything under the lock is an in-memory map lookup — the whole coord-to-id map
     * arrives with the segment — and the actual grid read happens outside it.
     */
    private void sweep(Base base) {
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
	    /* What the raster asked for, and one grid more -- not a square around a centre of this
	     * sweep's own. One owner decides what is wanted, and it is the one that knows where the
	     * camera is pointing and what of that is on screen; a square around the centre reads ground
	     * behind the camera that no pan will ever want. Read once, because the raster may hand over
	     * a new set under a sweep that is already running. */
	    Set<Coord> want = this.readset;
	    int newask = 0;
	    for(Coord gc : want) {
		if(AddonWidgets.loadedGrid(map, gc) != null) {
		    pending.remove(gc);
		    continue;
		}
		Long id = base.seg.gridid(gc.add(base.off));
		if(id == null) {
		    /* Ground the character has never walked. Not an error and not a miss to
		     * retry into -- there is simply nothing recorded there. */
		    blank++;
		    pending.remove(gc);
		    continue;
		}
		if(!pending.contains(gc)) {
		    /* A NEW ask, and this is the one thing the budget bounds. Asking again for a grid
		     * already in flight is a check on a future that is already running and costs
		     * nothing, so it never takes a slot from a grid nobody has asked for yet. */
		    if(newask >= maxread)
			continue;
		    pending.add(gc);
		    newask++;
		}
		got.put(gc, base.seg.grid(id));
		ids.put(gc, id);
	    }
	} finally {
	    base.file.lock.readLock().unlock();
	}
	for(Map.Entry<Coord, haven.Indir<MapFile.Grid>> ent : got.entrySet()) {
	    try {
		MapFile.Grid g = ent.getValue().get();
		if(g == null) {
		    blank++;
		    pending.remove(ent.getKey());
		    continue;
		}
		ready.put(ent.getKey(), g);
	    } catch(Loading l) {
		/* Defer has not got the file off the disk yet, so the ask STANDS: it stays pending, it
		 * is asked again next sweep for nothing, and it takes no new slot in the meantime. */
	    } catch(RuntimeException e) {
		/* A tileset loaded out of the res cache can carry illegal references, and Loading is
		 * a RuntimeException everywhere on this path. One grid's failure is not the sweep's --
		 * and the ask is over, however it went, so the coord stops being pending. */
		pending.remove(ent.getKey());
		nfailed++;
		lasterr = String.valueOf(e);
	    }
	}
	nblank = blank;
	for(Map.Entry<Coord, MapFile.Grid> ent : ready.entrySet()) {
	    /* The base moved while this sweep was running, so every coord here means somewhere else now
	     * -- and release() has already disposed what was read through the old one. Installing behind
	     * that trimall is exactly how the record gets drawn where it never was. */
	    if(this.base != base)
		break;
	    pending.remove(ent.getKey());
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
     * How many grids this source has installed, cumulative since it was built. What
     * {@code MapView.recallgridsread()} answers, and the read side's whole claim as a number: it climbs
     * while a pan is being answered and stops the moment the record has caught up with the camera.
     */
    public int gridsread() {
	return(nread);
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
	pending.clear();
	pinned = Collections.emptySet();
	synchronized(lru) {
	    lru.clear();
	}
	map.trimall();
    }

    /**
     * Remap the recorded grid's tile indices onto this cache's own ids, and install it.
     *
     * <p>Room first, and that is what makes {@link #gridcap()} a bound on what is <b>held</b> rather than
     * on what the last tick trimmed. Installing happens here, on a {@link Defer} thread, while the trim
     * runs on the tick; between the two, a count read from outside would stand as far above the cap as one
     * sweep can install, and a budget that is only true at the instant it is enforced is not one.
     */
    private void install(Coord gc, long id, MapFile.Grid g) {
	trim(1);
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
	out.add(String.format("recall: grids held %d of %d, grids read %d, unrecorded %d, asked %d, failed %d",
			      map.numgrids(), gridcap(), nread, nblank, pending.size(), nfailed));
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
