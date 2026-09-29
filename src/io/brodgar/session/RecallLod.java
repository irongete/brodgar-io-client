package io.brodgar.session;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
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
import java.util.function.Predicate;

import haven.Coord;
import haven.Coord2d;
import haven.Coord3f;
import haven.Console;
import haven.Defer;
import haven.FColor;
import haven.Indir;
import haven.Light;
import haven.Loading;
import haven.MCache;
import haven.MapFile;
import haven.MapView;
import haven.Matrix4f;
import haven.ClickLocation;
import haven.Material;
import haven.Mipmapper;
import haven.Resource;
import haven.TexRender;
import haven.GOut;
import haven.Utils;
import haven.Warning;
import haven.render.DataBuffer;
import haven.render.FillBuffer;
import haven.render.Homo3D;
import haven.render.Location;
import haven.render.Model;
import haven.render.NumberFormat;
import haven.render.Pipe;
import haven.render.RenderTree;
import haven.render.Tex2D;
import haven.render.Texture;
import haven.render.Texture2D;
import haven.render.VectorFormat;
import haven.render.VertexArray;

import io.brodgar.perf.Performance;

/**
 * The far ground of the view distance: the recorded ground the view does not draw whole, drawn out of the
 * map database's zoom grids ({@link MapFile.ZoomGrid}), each piece at the detail its size on screen calls for.
 *
 * <p>A zoom grid of level {@code L} covers {@code 2^L × 2^L} grids in one {@code cmaps}-sized array — the
 * majority tile and the lowest height of each block — so a cell of any level costs the same to read, to
 * mesh and to draw, and each level covers four times the ground of the one below it.
 *
 * <p><b>Which cells</b> is a quadtree over segment grid coords, walked every tick from the top level down
 * over the view distance around where the view is centred, and decided from where the camera's EYE is,
 * never from where it looks: a cell splits into its four children while one of its samples ({@code 2^L}
 * tiles) would cover more than {@link #texelpx} pixels at the cell's nearest depth, and a level-one cell
 * one of whose tiles would cover more than {@link #fullpx} pixels there is not a far cell at all but
 * <b>whole</b>: its four grids are handed to the recorded ground's own raster ({@link #detail}), which draws
 * them as real cut meshes. That is what is DRAWN, for what is on screen. What is BUILT and kept is decided the
 * same way over every view a turn of the camera about the centre gives ({@link #keep}), on screen now or not:
 * a turn of the camera only swaps in what is built already, and builds nothing and merges nothing, while the
 * ground is drawn no finer than the view itself calls for. So a camera high over the map draws it all from
 * zoom grids however it pans, and one down at the ground draws whole only the grids close to it. A boundary
 * is crossed a share past where
 * it lies ({@link #HYST}), on the side of it the ground is DRAWN on, so a camera standing on one does not
 * flicker; and no piece of ground goes finer while the camera goes away from it, nor coarser while the camera
 * comes closer, so what is built late -- the camera having turned back meanwhile -- waits for the camera to
 * stop rather than being swapped in and out again. A cell over the live terrain is always whole, since the
 * live terrain draws it. The walk is in SEGMENT coords because a zoom grid is
 * aligned there, and placed through the recorded ground's own {@link Recall.Base} offset.
 *
 * <p><b>Nothing is taken away before what replaces it is there.</b> A cell that splits goes on being drawn
 * until all four of its children are built -- and a level-one cell to be drawn whole, until the raster has
 * each of its grids merged whole, the cell itself standing in meanwhile -- while a cell that is to stand for
 * the finer ground drawn last tick, the camera going away, is drawn as that finer ground until it is built
 * itself. A cell over the live terrain can stand in for nothing, since the live ground is drawn there, so
 * every level-one cell within reach of the live terrain ({@code near}) is held whole in advance, whatever
 * the camera does: built and merged out of the scene while the far ground is drawn over it, so the live
 * ground walking into it, or a camera turning to it, finds it whole already. Only ground seen for the first
 * time is ever a hole, for as long as its first cell takes.
 *
 * <p><b>A cell</b> is one mesh: a height map of {@link #QUADS}² quads over the zoom grid's heights, lit
 * through normals of its own, textured with the minimap's own colours of its tiles -- one texel per sample,
 * drawn as crisp squares the way the map window draws a zoom grid ({@link #texture}) -- and skirted: each
 * edge hangs a strip down, as deep as the slope there, so two cells whose sampled
 * heights disagree along a shared edge show no crack between them. It is built on a {@link Defer} thread,
 * cached by level, place and flat-terrain state, and dropped least-recently-wanted first past {@link
 * #CACHECAP} more than the tick draws and wants. A cell follows the record: when a grid under it is taken in
 * again with different ground, it is built again and swapped in for the build it replaces ({@link #refresh}).
 *
 * <p>Nothing stands on it and nothing reaches it: no objects, no overlays, no shadow cast. A click on it is a
 * click on the ground there.
 */
public class RecallLod implements RenderTree.Node {
    /** Quads per side of a cell's mesh: a zoom grid's 100 samples, two to a quad. */
    public static final int QUADS = 50;
    /** The coarsest level walked: a cell of 128 × 128 grids. */
    public static final int MAXLVL = 7;
    /** How many built cells are kept besides the ones the tick draws and wants, for the view coming back. */
    private static final int CACHECAP = 384;
    /** How many cells may be loading or building at once: the Defer pool is shared with everything else. */
    private static final int MAXBUSY = 3;
    /**
     * How many pixels one sample of a far cell may cover on screen before the cell splits, and how many one
     * tile of a level-one cell may cover before its grids are drawn whole. {@code :terrainlod} sets them
     * live.
     */
    public static volatile double texelpx = 6, fullpx = 5;
    /** How far past a boundary it is crossed, as a share of it, either way. */
    private static final double HYST = 0.15;
    /** How much wider than that the keep walk takes each boundary: a box seen between two of the turns it
     * weighs (View) is a little nearer there than at either of them. */
    private static final double TURNSLACK = 1.05;
    /** Texels a side of a cell's texture: the zoom grid's samples in its corner, and the rest of a power of
     * two, which only a mipmapped texture must be. */
    private static final int TEXSZ = 128;

    static {
	Console.setscmd("terrainlod", (cons, args) -> {
		if(args.length >= 2) {
		    double px = Double.parseDouble(args[1]);
		    double full = (args.length >= 3) ? Double.parseDouble(args[2]) : fullpx;
		    if(!(px > 0) || !(full > 0))
			throw(new Exception("terrainlod: a number of pixels above zero, and optionally a second"));
		    texelpx = px;
		    fullpx = full;
		}
		cons.out.println(String.format("terrainlod: a far cell splits past %s pixels a sample; its grids are drawn whole past %s pixels a tile",
					       texelpx, fullpx));
	    });
    }

    /**
     * The camera for one tick, as the walk asks it: which boxes of map ground it sees, and how many pixels a
     * world unit covers at a box's nearest depth -- now, and in every view a turn of the camera about the centre
     * would give ({@link #turned}). Made by the view once a tick, from its own projection and camera.
     */
    public static final class View {
	/** How many turns of the camera about the centre stand for all of them, at even steps, and how much wider
	 * than the screen each turn sees. The eye goes round the centre as the camera turns, so ground close to it
	 * crosses the screen fast and is on it for a few degrees of the turn only: over a sweep of the real
	 * camera a degree at a time, twelve bare turns missed boxes that twenty-four turns a sixth wider did not
	 * (camera-turn test, default and RTS cameras, near and far). */
	private static final int TURNS = 24;
	private static final float TURNMARGIN = 0.15f;
	private final float[] m, p;
	private final float scale;
	/* The eye, in the scene's coords; and the way it looks, as an angle below the horizontal and a direction
	 * across the ground. */
	private final float ex, ey, ez;
	private final double pitch, fx, fy;
	/* This view turned about the centre it is oriented on (orient): the canonical one, its eye due east of the
	 * centre, and that one turned all the way round; and what a turn about that centre leaves as it is -- the
	 * eye's distance across the ground from it, and the way the eye looks across the ground against the way to
	 * it. */
	private Coord2d oc = null;
	private float[] canon = null;
	private float[][] turns = null;
	private double orbit, yaw;

	/**
	 * @param prj the scene's projection
	 * @param cam the scene's transform to the camera's own coords
	 * @param h   the viewport's height, in pixels
	 */
	public View(Matrix4f prj, Matrix4f cam, int h) {
	    this.m = prj.mul(cam).m;
	    this.p = prj.m.clone();
	    /* How far clip y moves for a world unit at depth one, times half the viewport's height. */
	    this.scale = (float)(Math.sqrt((m[1] * m[1]) + (m[5] * m[5]) + (m[9] * m[9])) * h / 2);
	    /* The eye is the point the camera's transform takes to the origin, and it looks down the camera's -z. */
	    Coord3f eye = cam.invert().mul4(Coord3f.o);
	    this.ex = eye.x;
	    this.ey = eye.y;
	    this.ez = eye.z;
	    double dx = -cam.m[2], dy = -cam.m[6], dz = -cam.m[10];
	    this.pitch = Math.atan2(dz, Math.hypot(dx, dy));
	    this.fx = dx;
	    this.fy = dy;
	}

	/* Clip transform `v` with the world turned first by `a` about the vertical through scene point (cx, cy):
	 * the camera turned the other way about it. */
	private static float[] turn(float[] v, double a, float cx, float cy) {
	    float c = (float)Math.cos(a), s = (float)Math.sin(a);
	    float[] r = {c, s, 0, 0, -s, c, 0, 0, 0, 0, 1, 0, cx - (c * cx) + (s * cy), cy - (s * cx) - (c * cy), 0, 1};
	    float[] o = new float[16];
	    for(int col = 0; col < 4; col++) {
		for(int row = 0; row < 4; row++) {
		    float sum = 0;
		    for(int k = 0; k < 4; k++)
			sum += v[(k * 4) + row] * r[(col * 4) + k];
		    o[(col * 4) + row] = sum;
		}
	    }
	    return(o);
	}

	/**
	 * Orient this view on the centre it turns about, {@code c} in map coords. Turned until its eye stands due
	 * east of the centre it is the canonical view, which two cameras differing only by a turn about the centre
	 * share; and the turns are taken from that, so what they cover is the same whichever way the camera faces.
	 */
	void orient(Coord2d c) {
	    if(c.equals(oc))
		return;
	    /* The scene's y runs the other way from the map's. */
	    float cx = (float)c.x, cy = -(float)c.y;
	    canon = turn(m, Math.atan2(ey - cy, ex - cx), cx, cy);
	    turns = new float[TURNS][];
	    for(int i = 0; i < TURNS; i++)
		turns[i] = turn(canon, (2 * Math.PI * i) / TURNS, cx, cy);
	    orbit = Math.hypot(ex - cx, ey - cy);
	    yaw = Math.atan2((fx * (cy - ey)) - (fy * (cx - ex)), (fx * (cx - ex)) + (fy * (cy - ey)));
	    oc = c;
	}

	/** Whether this is the very camera {@code o} was: the same transform and the same viewport height. */
	public boolean same(View o) {
	    return((o != null) && (o.scale == scale) && java.util.Arrays.equals(o.m, m));
	}

	private static boolean near(double a, double b, double rel) {
	    return(Math.abs(a - b) <= (rel * Math.max(1, Math.max(Math.abs(a), Math.abs(b)))));
	}

	/**
	 * Whether what is kept under {@code o} is what would be kept under this view: both oriented on the same
	 * centre, and all a turn about it leaves as it is the same -- the projection, the eye's height and distance
	 * across the ground from the centre, how far down it looks and which way against the centre. Within what a
	 * float of a world coordinate holds, since a turned camera's matrices are worked out afresh every frame: half
	 * a unit of the eye, which is also what the way against the centre is known to over the eye's distance from
	 * it, moves a sample by nothing a pixel shows, and neither does a hundredth of a radian of the view's edge.
	 */
	boolean sameturns(View o) {
	    if((o == null) || (oc == null) || !oc.equals(o.oc))
		return(false);
	    if(!near(o.scale, scale, 1e-5) || (Math.abs(o.ez - ez) > 0.5) || (Math.abs(o.orbit - orbit) > 0.5) ||
	       (Math.abs(o.pitch - pitch) > 1e-4) || (Math.abs(o.yaw - yaw) > Math.max(0.01, 0.5 / Math.max(1, orbit))))
		return(false);
	    for(int i = 0; i < 16; i++) {
		if(!near(o.p[i], p[i], 1e-5))
		    return(false);
	    }
	    return(true);
	}

	/**
	 * Pixels a world unit covers at the nearest depth of this rectangle of map ground (map coords in world
	 * units) over heights {@code mlo..mhi}, infinite where that reaches the eye; and negative when no part
	 * of it over heights {@code vlo..vhi} is on screen. A box is off screen only when all eight corners are
	 * outside one same side of the view, or behind it, never merely when none is inside: that is how a box
	 * larger than the screen vanishes.
	 */
	public double sight(Coord2d ul, Coord2d br, float vlo, float vhi, float mlo, float mhi) {
	    return(sight(m, 1, ul, br, vlo, vhi, mlo, mhi));
	}

	/**
	 * The same through every turn of the camera about the centre it is oriented on, one a turn: negative for a
	 * turn that does not have the box on screen.
	 */
	double[] turned(Coord2d ul, Coord2d br, float vlo, float vhi, float mlo, float mhi) {
	    double[] ret = new double[turns.length];
	    for(int i = 0; i < turns.length; i++)
		ret[i] = sight(turns[i], 1 + TURNMARGIN, ul, br, vlo, vhi, mlo, mhi);
	    return(ret);
	}

	private double sight(float[] m, float wide, Coord2d ul, Coord2d br, float vlo, float vhi, float mlo, float mhi) {
	    boolean left = true, right = true, down = true, up = true, behind = true;
	    float minw = Float.POSITIVE_INFINITY;
	    for(int i = 0; i < 4; i++) {
		float x = (float)(((i & 1) == 0) ? ul.x : br.x);
		/* The scene's y runs the other way from the map's. */
		float y = -(float)(((i & 2) == 0) ? ul.y : br.y);
		float bx = (m[0] * x) + (m[4] * y) + m[12];
		float by = (m[1] * x) + (m[5] * y) + m[13];
		float bw = (m[3] * x) + (m[7] * y) + m[15];
		for(int k = 0; k < 2; k++) {
		    float z = (k == 0) ? vlo : vhi;
		    float cx = bx + (m[8] * z), cy = by + (m[9] * z), cw = bw + (m[11] * z);
		    if(cw > 0) behind = false;
		    if(!(cx < -wide * cw)) left = false;
		    if(!(cx >  wide * cw)) right = false;
		    if(!(cy < -wide * cw)) down = false;
		    if(!(cy >  wide * cw)) up = false;
		}
		minw = Math.min(minw, Math.min(bw + (m[11] * mlo), bw + (m[11] * mhi)));
	    }
	    if(behind || left || right || up || down)
		return(-1);
	    if(!(minw > 1e-3f))
		return(Double.POSITIVE_INFINITY);
	    return(scale / minw);
	}
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
	final TexRender tex;
	final RenderTree.Node node;
	/* The same ground for the click pass: the cell's surface without its skirts, carrying
	 * ClickLocation's 0..1 place over the cell, and none of the holes where nothing was recorded. */
	final Model click;
	final float zlo, zhi;
	/* Which quarters of the cell hold any recorded ground, a bit each (x + 2y): a level-one cell's quarter
	 * is a grid, and one never recorded is nothing to wait for before the cell is drawn whole. */
	final int quads;

	Built(Model model, TexRender tex, RenderTree.Node node, Model click, float zlo, float zhi, int quads) {
	    this.model = model;
	    this.tex = tex;
	    this.node = node;
	    this.click = click;
	    this.zlo = zlo;
	    this.zhi = zhi;
	    this.quads = quads;
	}

	void dispose() {
	    model.dispose();
	    click.dispose();
	    tex.dispose();
	}
    }

    private static final class Cell {
	Indir<? extends MapFile.DataGrid> src = null;
	/* The zoom grid `built` -- or the build in flight -- was made from, once `src` has answered (`fetched`):
	 * what a later answer is told apart from. MapFile relaunches a zoom grid whenever a grid under it is
	 * recorded again and the same Indir then answers the new object, so the identity is the version. */
	MapFile.DataGrid from = null;
	boolean fetched = false;
	Defer.Future<Built> building = null;
	Built built = null;
	boolean empty = false;
	/* Its zoom grid failed to load, which it goes on answering: asked no more. */
	boolean failed = false;
    }

    /** What is in the scene for a cell: its slot, and which of its builds that slot draws. */
    private static final class Shown {
	final RenderTree.Slot slot;
	final Built built;

	Shown(RenderTree.Slot slot, Built built) {
	    this.slot = slot;
	    this.built = built;
	}
    }

    /**
     * A cell the tick wants, whether any of it is on screen, and how many pixels a world unit of it covers: one on
     * screen is built before one off it, and the larger, the sooner.
     */
    private static final class Leaf {
	final Key key;
	final boolean seen;
	final double px;

	Leaf(Key key, boolean seen, double px) {
	    this.key = key;
	    this.seen = seen;
	    this.px = px;
	}
    }

    /** Built and pending cells, least recently wanted first. */
    private final Map<Key, Cell> cells = new LinkedHashMap<Key, Cell>(64, 0.75f, true);
    /** What is in the scene right now, and the slot it is in. */
    private final Map<Key, Shown> inscene = new HashMap<Key, Shown>();
    private RenderTree.Slot slot = null;
    /** The same cells in the view's click pass, so a click on far ground is a click on the ground. */
    private final Map<Key, Shown> inclick = new HashMap<Key, Shown>();
    /** Builds a rebuild has replaced this tick: disposed once neither pass draws them. */
    private final List<Built> retired = new ArrayList<Built>();
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

    /**
     * The grids the recorded ground's raster is to hold whole, in SESSION grid coords: the ones this tick
     * wants whole, and the ones drawn whole while what replaces them is built. Replaced whole every tick.
     */
    public Set<Coord> detail = Collections.emptySet();
    /** Of {@link #detail}, the grids drawn whole this tick; the rest are built and not drawn. */
    public Set<Coord> shown = Collections.emptySet();
    /** Cells this tick wanted, cells it had in the scene, and cells loading or building, for {@code :recall}. */
    public int nwanted = 0, ndrawn = 0, nbusy = 0;
    /** Grids drawn whole this tick and wanted whole, for {@code :recall}. */
    public int nwhole = 0, nwholewanted = 0;

    public void added(RenderTree.Slot slot) {
	this.slot = slot;
    }

    public void removed(RenderTree.Slot slot) {
	this.slot = null;
	inscene.clear();
    }

    /* The walk's own state, for the one tick it runs in. */
    private Recall.Base wbase;
    private View wview;
    private Predicate<Coord> wready;
    private Set<Coord> wlive;
    private Coord wcg;
    private int wrange;
    private boolean wflat;
    /* What the walk draws, in the order it found them: cells, and grids drawn whole in SEGMENT coords; and what
     * it wants built. */
    private final List<Key> dcells = new ArrayList<Key>();
    private final List<Coord> dfulls = new ArrayList<Coord>();
    private final List<Leaf> wants = new ArrayList<Leaf>();
    /* Cells found to hold nothing, which the cap leaves alone as long as the walk keeps finding them. */
    private final List<Key> dempty = new ArrayList<Key>();
    /* Which cells the last walk drew split -- finer ground drawn under them -- and which level-one cells it drew
     * whole: the side of a boundary each is on, which its hysteresis is taken from. What was DRAWN, never what
     * was wanted: a cell wanted split or whole while a coarser one stands in for it has crossed nothing on
     * screen yet, and judged as if it had it would go on wanting the finer ground as a zoom went back out -- to
     * be swapped in the moment it was built, and back out a few frames later. Nor what was decided: ground a
     * cell is drawn over as the finer pieces it stands for has not left them on screen. */
    private Set<Key> splitlast = new HashSet<Key>();
    private Set<Coord> fulllast = new HashSet<Coord>();
    /* The level-one cells wanted whole this tick, drawn or not: what the raster is to hold. */
    private Set<Coord> wantnow = null;
    /* How many pixels a world unit of each cell the walk reached covered, this tick and the last: whether the
     * camera is coming closer to a cell or going away from it. */
    private Map<Key, Double> pxlast = new HashMap<Key, Double>(), pxnow = null;
    /* What last tick drew, and the same filed under every cell above each piece (made when a tick first
     * needs it): what a cell wanted and not built yet is drawn as meanwhile. */
    private List<Key> lastcells = Collections.emptyList();
    private Set<Key> lastcellset = Collections.emptySet();
    private List<Coord> lastfulls = Collections.emptyList();
    private boolean lastflat = false;
    private Map<Key, List<Object>> lastunder = null;
    /* What the last walk was made from. Everything a walk decides is a function of these, of the cells' own
     * states and of what the raster has whole: a tick that finds all of them where they were, no cell changed
     * since (cellchange) and nothing the last one wanted left to start (unfinished), has nothing to do. */
    private View lastview = null;
    private Set<Coord> lastlive = null, lastnear = null;
    private Coord lastcg = null;
    private int lastrange = -1, lastreadygen = -1;
    private double lasttexelpx = -1, lastfullpx = -1;
    private boolean cellchange = true, unfinished = true;
    /* What the keep walk last decided (keep): the cells to build and keep, the ones found empty, and the
     * level-one cells whose grids are held whole; and the view it decided under, whose turns it covered. */
    private final List<Leaf> kwants = new ArrayList<Leaf>();
    private final List<Key> kempty = new ArrayList<Key>();
    private Set<Coord> kwhole = new HashSet<Coord>();
    private View lastkeep = null;

    /**
     * Walk the far ground for this tick: decide which grids are drawn whole, bring the cells to draw into the
     * scene and take every other one out.
     *
     * @param base     the recorded ground's proved base
     * @param center   where the view is centred, in map coords (world units): what the view distance is around
     * @param range    the view distance, in grids
     * @param live     the grids the live terrain is drawing, in session grid coords
     * @param near     the grids the live terrain can reach before one could be built, in session grid coords:
     *                 its area grown by a margin. Every level-one cell over one is held whole.
     * @param view     the camera, for this tick
     * @param ready    whether the recorded ground's raster can draw a grid whole now (session grid coords):
     *                 it has it merged with every cut it can build, or has nothing of it to show
     * @param readygen moves whenever what {@code ready} answers may have
     */
    public void tick(Recall.Base base, Coord2d center, int range, Set<Coord> live, Set<Coord> near, View view,
		     Predicate<Coord> ready, int readygen) {
	if(base != placed) {
	    for(Shown s : inscene.values())
		s.slot.remove();
	    inscene.clear();
	    for(Shown s : inclick.values())
		s.slot.remove();
	    inclick.clear();
	    lastcells = Collections.emptyList();
	    lastcellset = Collections.emptySet();
	    lastfulls = Collections.emptyList();
	    placed = base;
	    cellchange = true;
	}
	boolean flat = Performance.flatTerrain;

	/* Every cell in flight moves on first, wanted or not, so the walk sees what has just been built. Only a
	 * cell's own progress gives back its share of MAXBUSY, so a cell moved on only while it is wanted holds
	 * that share for as long as the view looks elsewhere -- a camera turn, a walk, a flat-terrain switch or
	 * a re-base mid-load -- and MAXBUSY of them start nothing ever again. One left behind is finished like
	 * any other, and cached for when the view comes back to it. */
	int busy = 0;
	for(Map.Entry<Key, Cell> e : cells.entrySet()) {   // no get(): access order moves on a get
	    if(advance(e.getKey(), e.getValue()))
		busy++;
	}
	/* And every cell at rest asks its zoom grid again, out of what MAXBUSY leaves: the record may have taken
	 * in again a grid under it (refresh). */
	for(Map.Entry<Key, Cell> e : cells.entrySet()) {
	    if(busy >= MAXBUSY)
		break;
	    if(refresh(e.getKey(), e.getValue()))
		busy++;
	}
	nbusy = busy;
	Coord cg = center.floor(MCache.tilesz).div(MCache.cmaps).add(base.off);
	double tpx = texelpx, fpx = fullpx;
	/* What is kept is decided over every turn of the camera about the centre, so nothing a turn changes moves
	 * it; what is drawn, by the view itself. */
	view.orient(center);
	boolean keepsame = !cellchange && view.sameturns(lastkeep) && cg.equals(lastcg) && (range == lastrange) &&
	    (flat == lastflat) && (tpx == lasttexelpx) && (fpx == lastfullpx);
	if(keepsame && !unfinished && view.same(lastview) && live.equals(lastlive) && near.equals(lastnear) &&
	   (readygen == lastreadygen))
	    return;
	cellchange = false;
	lastview = view;
	lastlive = new HashSet<Coord>(live);
	lastnear = new HashSet<Coord>(near);
	lastcg = cg;
	lastrange = range;
	lastreadygen = readygen;
	lasttexelpx = tpx;
	lastfullpx = fpx;

	wbase = base;
	wview = view;
	wready = ready;
	wflat = flat;
	wrange = range;
	wcg = cg;
	Set<Coord> slive = new HashSet<Coord>();
	for(Coord g : live)
	    slive.add(g.add(base.off));
	wlive = slive;
	dcells.clear();
	dfulls.clear();
	wants.clear();
	dempty.clear();
	wantnow = new HashSet<Coord>();
	pxnow = new HashMap<Key, Double>();
	lastunder = null;
	int top = 1;
	while((top < MAXLVL) && ((1 << top) < ((range * 2) + 2)))
	    top++;
	int tn = 1 << top;
	Coord lo = wcg.sub(range, range), hi = wcg.add(range, range);
	if(!keepsame) {
	    kwants.clear();
	    kempty.clear();
	    kwhole = new HashSet<Coord>();
	    for(int y = Math.floorDiv(lo.y, tn) * tn; y <= hi.y; y += tn) {
		for(int x = Math.floorDiv(lo.x, tn) * tn; x <= hi.x; x += tn)
		    keep(top, Coord.of(x, y));
	    }
	    lastkeep = view;
	}
	for(int y = Math.floorDiv(lo.y, tn) * tn; y <= hi.y; y += tn) {
	    for(int x = Math.floorDiv(lo.x, tn) * tn; x <= hi.x; x += tn)
		visit(top, Coord.of(x, y));
	}
	/* The next walk's hysteresis, from what this one draws: every cell above a piece drawn is drawn split,
	 * and a level-one cell a grid of which is drawn whole is drawn whole. */
	Set<Key> spl = new HashSet<Key>();
	Set<Coord> ful = new HashSet<Coord>();
	for(Key k : dcells) {
	    for(int l = k.lvl + 1; l <= MAXLVL; l++) {
		if(!spl.add(new Key(k.seg, l, align(k.sc, l), k.flat)))
		    break;   // and so is every cell above it
	    }
	}
	for(Coord g : dfulls) {
	    ful.add(align(g, 1));
	    for(int l = 2; l <= MAXLVL; l++) {
		if(!spl.add(new Key(base.seg.id, l, align(g, l), flat)))
		    break;
	    }
	}
	splitlast = spl;
	fulllast = ful;
	Set<Coord> wantwhole = wantnow;
	wantnow = null;
	pxlast = pxnow;
	pxnow = null;
	wview = null;
	wready = null;
	wlive = null;

	/* What is wanted: what the view draws first, then what is kept for the turns of the camera; and of each the
	 * largest first. */
	Map<Key, Leaf> wanted = new HashMap<Key, Leaf>();
	for(Leaf l : wants)
	    wanted.put(l.key, l);
	for(Leaf l : kwants)
	    wanted.putIfAbsent(l.key, l);
	List<Leaf> order = new ArrayList<Leaf>(wanted.values());
	Collections.sort(order, new Comparator<Leaf>() {
		public int compare(Leaf a, Leaf b) {
		    if(a.seen != b.seen)
			return(a.seen ? -1 : 1);
		    return(Double.compare(b.px, a.px));
		}
	    });
	nwanted = order.size();
	boolean left = false;
	for(Leaf l : order) {
	    Cell c = cells.get(l.key);   // the touch that makes it recent
	    if(c == null) {
		if(busy >= MAXBUSY) {
		    left = true;
		    continue;
		}
		c = new Cell();
		c.src = base.seg.grid(l.key.lvl, l.key.sc);
		cells.put(l.key, c);
		if(advance(l.key, c))
		    busy++;
	    }
	}
	unfinished = left;

	/* Out first, then in: what leaves the scene frees nothing it would draw, and nothing is added twice. */
	Set<Key> draw = new HashSet<Key>(dcells);
	for(Iterator<Map.Entry<Key, Shown>> i = inscene.entrySet().iterator(); i.hasNext();) {
	    Map.Entry<Key, Shown> e = i.next();
	    Cell c = cells.get(e.getKey());
	    if(!draw.contains(e.getKey()) || (c == null) || (c.built != e.getValue().built)) {
		e.getValue().slot.remove();
		i.remove();
	    }
	}
	for(Iterator<Map.Entry<Key, Shown>> i = inclick.entrySet().iterator(); i.hasNext();) {
	    Map.Entry<Key, Shown> e = i.next();
	    Cell c = cells.get(e.getKey());
	    if(!draw.contains(e.getKey()) || (c == null) || (c.built != e.getValue().built)) {
		e.getValue().slot.remove();
		i.remove();
	    }
	}
	/* What a rebuild replaced is in neither pass any more, and goes: its successor is added below. */
	for(Built b : retired)
	    b.dispose();
	retired.clear();
	for(Key k : dcells) {
	    Built b = cells.get(k).built;
	    Coord tc = k.sc.sub(base.off).mul(MCache.cmaps);
	    Coord3f at = Coord3f.of((float)(tc.x * MCache.tilesz.x), -(float)(tc.y * MCache.tilesz.y), 0);
	    if((slot != null) && !inscene.containsKey(k))
		inscene.put(k, new Shown(slot.add(b.node, Location.xlate(at)), b));
	    /* The click's 0..1 place spans the cell's tiles, in session tile coords: the view turns it into
	     * the ground position the click is sent with. */
	    if((cslot != null) && !inclick.containsKey(k))
		inclick.put(k, new Shown(cslot.add(MapView.farclick(tc, MCache.cmaps.mul(1 << k.lvl), b.click), Location.xlate(at)), b));
	}
	/* The raster's share, in session coords: every grid of a cell wanted whole, every grid drawn whole, and
	 * which are drawn. */
	Set<Coord> det = new HashSet<Coord>(), shw = new HashSet<Coord>();
	for(Coord sc : wantwhole) {
	    for(int y = 0; y < 2; y++) {
		for(int x = 0; x < 2; x++)
		    det.add(sc.add(x, y).sub(base.off));
	    }
	}
	for(Coord g : dfulls) {
	    det.add(g.sub(base.off));
	    shw.add(g.sub(base.off));
	}
	/* And every level-one cell the keep walk holds whole, on screen or not: a turn of the camera brings it on
	 * screen whole already, with nothing to build or merge. */
	for(Coord sc : kwhole) {
	    for(int y = 0; y < 2; y++) {
		for(int x = 0; x < 2; x++)
		    det.add(sc.add(x, y).sub(base.off));
	    }
	}
	/* And every level-one cell within reach of the live terrain, on screen or not: what the live ground walks
	 * into next, or a camera turns to, is whole already -- a cell over the live ground is drawn whole at once
	 * (visit), with nothing that may stand in for it meanwhile. Within the view distance, as everything the
	 * walk draws is: an RTS camera looking far away holds nothing around the character. */
	for(Coord g : near) {
	    Coord sc = align(g.add(base.off), 1);
	    if((sc.x > cg.x + range) || (sc.x + 1 < cg.x - range) || (sc.y > cg.y + range) || (sc.y + 1 < cg.y - range))
		continue;
	    for(int y = 0; y < 2; y++) {
		for(int x = 0; x < 2; x++)
		    det.add(sc.add(x, y).sub(base.off));
	    }
	}
	this.detail = det;
	this.shown = shw;
	ndrawn = inscene.size();
	nbusy = busy;
	nwhole = shw.size();
	nwholewanted = wantwhole.size() * 4;
	lastcells = new ArrayList<Key>(dcells);
	lastcellset = new HashSet<Key>(dcells);
	lastfulls = new ArrayList<Coord>(dfulls);
	lastflat = flat;
	Set<Key> inuse = new HashSet<Key>(draw);
	inuse.addAll(wanted.keySet());
	inuse.addAll(dempty);
	inuse.addAll(kempty);
	wbase = null;
	trim(inuse);
    }

    /** One step of a cell in flight: collect its zoom grid and start its mesh, or collect its mesh. Whether it
     * is still in flight after it. */
    private boolean advance(Key key, Cell c) {
	if(c.building != null) {
	    if(!c.building.done())
		return(true);
	    Built prev = c.built;
	    try {
		c.built = c.building.get();
		learn(key, c.built);
		/* A rebuild (refresh): the build it replaces stays in the scene until the tick swaps them. */
		if(prev != null)
		    retired.add(prev);
	    } catch(Exception e) {
		/* A rebuild that failed leaves the cell drawn as it was; a first build, empty. */
		if(prev == null)
		    c.empty = true;
	    }
	    c.building = null;
	    cellchange = true;
	    return(false);
	}
	if(c.fetched)
	    return(false);
	MapFile.DataGrid g;
	try {
	    g = c.src.get();
	} catch(Loading e) {
	    return(true);
	} catch(RuntimeException e) {
	    /* The zoom grid's fetch failed -- ZoomGrid.from saving what it built into a store that would not
	     * take it -- and its future answers every later ask with the same failure. The cell is empty, as
	     * unrecorded ground is: out of MapView.tick, this ends the UI thread. */
	    new Warning(e, String.format("far cell %s at level %d: its zoom grid failed: %s", key.sc, key.lvl, e)).issue();
	    g = null;
	    c.failed = true;
	}
	c.fetched = true;
	c.from = g;
	if(g == null) {
	    c.empty = true;
	    cellchange = true;
	    return(false);
	}
	c.building = start(key, g);
	return(true);
    }

    /**
     * Whether a cell at rest has had its zoom grid moved under it, and if so its rebuild started. The record
     * took in again a grid the cell covers ({@code MapFile.Segment.include}), which fetches the zoom grids
     * over it anew, and the cell's own {@code Indir} answers the new one once it is there -- the previous one
     * until then, never {@code Loading}. What is drawn stays drawn until the rebuild is built (advance); where
     * the new zoom grid draws the same cell, nothing is built at all -- the grids around the character are
     * taken in again the first time each is seen, most of them unchanged.
     */
    private boolean refresh(Key key, Cell c) {
	if((c.building != null) || !c.fetched || c.failed)
	    return(false);
	MapFile.DataGrid g;
	try {
	    g = c.src.get();
	} catch(RuntimeException e) {
	    /* Fetched anew and failed, and answering the failure from now on: what is drawn stays. */
	    new Warning(e, String.format("far cell %s at level %d: its zoom grid failed: %s", key.sc, key.lvl, e)).issue();
	    c.failed = true;
	    return(false);
	}
	if(g == c.from)
	    return(false);
	MapFile.DataGrid prev = c.from;
	c.from = g;
	if(g == null) {
	    /* Nothing recorded under it any more. */
	    if(c.built != null)
		retired.add(c.built);
	    c.built = null;
	    c.empty = true;
	    cellchange = true;
	    return(false);
	}
	if((c.built != null) && (prev != null) && samedraw(prev, g))
	    return(false);
	c.empty = false;
	c.building = start(key, g);
	return(true);
    }

    private static Defer.Future<Built> start(Key key, MapFile.DataGrid g) {
	final int lvl = key.lvl;
	final boolean flat = key.flat;
	return(Defer.later(() -> build(g, lvl, flat)));
    }

    /**
     * Whether two zoom grids draw the same cell: the same tileset, by name and version, and the same height at
     * every sample, as the record keeps heights ({@link Recall#samez}). A grid's tileset indices are its own,
     * numbered afresh each time it is made -- a grid taken in again numbers its tilesets in the order the live
     * tile ids first appear -- so the index arrays of the same ground need not be equal.
     */
    private static boolean samedraw(MapFile.DataGrid a, MapFile.DataGrid b) {
	if(!Recall.samez(a.zmap, b.zmap))
	    return(false);
	int[] map = new int[a.tilesets.length];
	for(int i = 0; i < map.length; i++) {
	    Resource.Saved r = a.tilesets[i].res;
	    map[i] = -1;
	    for(int o = 0; o < b.tilesets.length; o++) {
		if(r.name.equals(b.tilesets[o].res.name) && (r.ver == b.tilesets[o].res.ver)) {
		    map[i] = o;
		    break;
		}
	    }
	}
	for(int i = 0; i < a.tiles.length; i++) {
	    if(map[a.tiles[i]] != b.tiles[i])
		return(false);
	}
	return(true);
    }

    /**
     * One cell of the walk: what it wants built, and what it draws meanwhile, added to this tick's lists.
     * Whether what it drew covers every part of it on screen -- what lets the cell above it stop standing in.
     */
    private boolean visit(int lvl, Coord sc) {
	int n = 1 << lvl;
	Coord cg = wcg;
	int range = wrange;
	if((sc.x > cg.x + range) || (sc.x + n - 1 < cg.x - range) ||
	   (sc.y > cg.y + range) || (sc.y + n - 1 < cg.y - range))
	    return(true);
	Key key = new Key(wbase.seg.id, lvl, sc, wflat);
	Cell known = cells.get(key);
	/* A zoom grid of nothing: there is nothing under it to draw. */
	if((known != null) && known.empty) {
	    dempty.add(key);
	    return(true);
	}
	/* Seen over its own heights once it is built, and until then over any height ground can stand at, so
	 * nothing in view is left out for a guess; but judged for its detail over the heights the cells built
	 * under and around it have shown, or a guess past those is a level finer than the ground calls for. */
	float vlo = -1000, vhi = 3000, mlo = vlo, mhi = vhi;
	if((known != null) && (known.built != null)) {
	    vlo = mlo = known.built.zlo;
	    vhi = mhi = known.built.zhi;
	} else {
	    float[] r = zguess(key);
	    if(r != null) {
		mlo = r[0];
		mhi = r[1];
	    }
	}
	Coord tc = sc.sub(wbase.off).mul(MCache.cmaps);
	Coord2d ul = new Coord2d(tc).mul(MCache.tilesz);
	Coord2d br = ul.add(new Coord2d(MCache.cmaps.mul(n)).mul(MCache.tilesz));
	/* Drawn as the view itself calls for, and only what is on screen: what a turn of the camera brings on
	 * screen the keep walk has built already (keep). */
	double px = wview.sight(ul, br, vlo, vhi, mlo, mhi);
	if(px < 0)
	    return(true);
	pxnow.put(key, px);
	boolean overlive = false;
	for(Coord g : wlive) {
	    if((g.x >= sc.x) && (g.x < sc.x + n) && (g.y >= sc.y) && (g.y < sc.y + n)) {
		overlive = true;
		break;
	    }
	}
	if(lvl == 1) {
	    /* Only judged whole over its own heights, never a guess: taken as nearer than it is, a cell would be
	     * drawn whole for good, since a cell drawn whole is never built and so never shows its heights. Until
	     * it has, it is a far cell, which is built in a moment. */
	    boolean own = ((known != null) && (known.built != null)) || zknown.containsKey(key);
	    double lim = fullpx * (fulllast.contains(sc) ? (1 - HYST) : (1 + HYST));
	    /* And drawn whole, it stays whole while the camera comes closer: the far cell, built only now, would be
	     * swapped in for the frames until the camera reached the ground it had just left (see receding). */
	    boolean keep = fulllast.contains(sc) && approaching(key, px);
	    if(!overlive && !keep && (!own || ((MCache.tilesz.x * px) <= lim)))
		return(leaf(key, known, px));
	    /* Whole: its grids drawn by the live terrain where that is, and by the recorded ground's raster --
	     * over live ground at once, since nothing coarser may stand there, and anywhere else once the
	     * raster has every one of them whole. */
	    wantnow.add(sc);
	    boolean all = true;
	    for(int i = 0; i < 4; i++) {
		Coord g = sc.add(i & 1, i >> 1);
		boolean none = (known != null) && (known.built != null) && ((known.built.quads & (1 << i)) == 0);
		if(!none && !wlive.contains(g) && !wready.test(g.sub(wbase.off)))
		    all = false;
	    }
	    /* Swapped in once it is -- but not while the camera goes away from it with its far cell on screen:
	     * built only as a zoom turns back out, the grids would be drawn whole for the few frames until it
	     * took them back out. The far cell goes on standing in until the camera stops or comes closer. */
	    boolean hold = !fulllast.contains(sc) && lastcellset.contains(key) && receding(key, px);
	    if(overlive || (all && !hold)) {
		for(int i = 0; i < 4; i++)
		    dfulls.add(sc.add(i & 1, i >> 1));
		return(true);
	    }
	    /* Meanwhile the cell itself stands in: wanted for that, and drawn once it is built. */
	    wants.add(new Leaf(key, true, px));
	    if((known != null) && (known.built != null)) {
		dcells.add(key);
		return(true);
	    }
	    /* Nothing to stand in yet: what of it the raster has whole is drawn, and the rest is a hole for now. */
	    for(int i = 0; i < 4; i++) {
		Coord g = sc.add(i & 1, i >> 1);
		if(wlive.contains(g) || wready.test(g.sub(wbase.off)))
		    dfulls.add(g);
	    }
	    return(false);
	}
	double lim = texelpx * (splitlast.contains(key) ? (1 - HYST) : (1 + HYST));
	/* And drawn split, it stays split while the camera comes closer, whatever it would ask at rest: the finer
	 * ground it was drawn as is where the camera is heading, and this cell, built only now, would be swapped
	 * in for the frames until the camera reached that ground again. Its coarser detail waits for the camera to
	 * stop. */
	boolean keep = splitlast.contains(key) && approaching(key, px);
	if(!overlive && !keep && ((MCache.tilesz.x * n * px) <= lim))
	    return(leaf(key, known, px));
	int c0 = dcells.size(), f0 = dfulls.size();
	boolean covered = true;
	int h = n / 2;
	for(int y = 0; y < 2; y++) {
	    for(int x = 0; x < 2; x++)
		covered &= visit(lvl - 1, sc.add(x * h, y * h));
	}
	/* All of it has come -- and is drawn instead of this cell, unless this cell was drawn itself last tick and
	 * the camera is going away from it: the finer ground it stood for, built only as a zoom turns back out,
	 * would be drawn for the few frames until the zoom took it back. */
	boolean hold = !overlive && (known != null) && (known.built != null) && lastcellset.contains(key) && receding(key, px);
	if(covered && !hold)
	    return(true);
	/* Not all of it has come yet, or it is held: this cell, while it is built, stands in for all four -- never
	 * over live ground, which the live terrain draws, and never over finer ground last tick drew. It stands in
	 * where it was drawn itself last tick, or where nothing under it was, ground seen for the first time. Over
	 * the finer ground a camera going away leaves behind, it would flash coarse for the frames the cell to
	 * replace that ground takes to build, and fine again: what of this cell is not drawn then is a hole for
	 * those frames, and only ever ground no frame has shown yet. */
	if(!overlive && (known != null) && (known.built != null) && (lastcellset.contains(key) || !drewunder(key))) {
	    dcells.subList(c0, dcells.size()).clear();
	    dfulls.subList(f0, dfulls.size()).clear();
	    dcells.add(key);
	    return(true);
	}
	return(false);
    }

    /**
     * One cell of the keep walk: every piece of the ground here some turn of the camera about the centre draws --
     * this cell, finer ones, its grids whole, each as its own turns call for -- built and kept whether it is on
     * screen now or not, so the walk that draws (visit) only ever swaps in, as the camera turns, what is built
     * already. Each boundary is taken with the whole of its hysteresis on both sides, since which side the draw
     * is on depends on what it drew last.
     */
    private void keep(int lvl, Coord sc) {
	int n = 1 << lvl;
	Coord cg = wcg;
	int range = wrange;
	if((sc.x > cg.x + range) || (sc.x + n - 1 < cg.x - range) ||
	   (sc.y > cg.y + range) || (sc.y + n - 1 < cg.y - range))
	    return;
	Key key = new Key(wbase.seg.id, lvl, sc, wflat);
	Cell known = cells.get(key);
	if((known != null) && known.empty) {
	    kempty.add(key);
	    return;
	}
	/* Over the same heights the draw judges it over (visit). */
	float vlo = -1000, vhi = 3000, mlo = vlo, mhi = vhi;
	if((known != null) && (known.built != null)) {
	    vlo = mlo = known.built.zlo;
	    vhi = mhi = known.built.zhi;
	} else {
	    float[] r = zguess(key);
	    if(r != null) {
		mlo = r[0];
		mhi = r[1];
	    }
	}
	Coord tc = sc.sub(wbase.off).mul(MCache.cmaps);
	Coord2d ul = new Coord2d(tc).mul(MCache.tilesz);
	Coord2d br = ul.add(new Coord2d(MCache.cmaps.mul(n)).mul(MCache.tilesz));
	double[] px = wview.turned(ul, br, vlo, vhi, mlo, mhi);
	/* What the draw weighs against its limit: one sample of the cell, and at level one one tile (visit). And a
	 * level-one cell is whole only over its own heights, as the draw has it: until they are known it is kept as
	 * a far cell, which shows them. */
	double edge = MCache.tilesz.x * ((lvl == 1) ? 1 : n), lim = (lvl == 1) ? fullpx : texelpx;
	boolean own = (lvl > 1) || ((known != null) && (known.built != null)) || zknown.containsKey(key);
	boolean seen = false, self = false, finer = false;
	double most = 0;
	for(double p : px) {
	    if(p < 0)
		continue;
	    seen = true;
	    most = Math.max(most, p);
	    double s = edge * p;
	    if(!own || (s <= lim * (1 + HYST) * TURNSLACK))
		self = true;
	    if(own && (s > lim * (1 - HYST) / TURNSLACK))
		finer = true;
	}
	if(!seen)
	    return;
	if(finer && (lvl == 1) && !self) {
	    /* Whole -- and while one of its grids is not whole yet, the cell itself as well, which is what the draw
	     * stands in with meanwhile (visit). */
	    for(int i = 0; i < 4; i++) {
		Coord g = sc.add(i & 1, i >> 1);
		if(!wlive.contains(g) && !wready.test(g.sub(wbase.off))) {
		    self = true;
		    break;
		}
	    }
	}
	if(self)
	    kwants.add(new Leaf(key, false, most));
	if(finer) {
	    if(lvl == 1) {
		kwhole.add(sc);
	    } else {
		int h = n / 2;
		for(int y = 0; y < 2; y++) {
		    for(int x = 0; x < 2; x++)
			keep(lvl - 1, sc.add(x * h, y * h));
		}
	    }
	}
    }

    /** A cell the walk ends at: wanted, and drawn -- itself once built, and until then what drew its ground last tick. */
    private boolean leaf(Key key, Cell known, double px) {
	wants.add(new Leaf(key, true, px));
	if((known != null) && (known.built != null)) {
	    dcells.add(key);
	    return(true);
	}
	return(fallback(key));
    }

    /**
     * A cell wanted and not built yet, drawn meanwhile as what drew its ground last tick: the finer cells, or
     * the grids drawn whole, it stands for now that the camera has gone away. Whether they cover all of it.
     */
    private boolean fallback(Key key) {
	if(lastunder == null)
	    lastunder = under(key.seg);
	List<Object> under = lastunder.get(key);
	if(under == null)
	    return(false);
	long area = 0;
	for(Object o : under) {
	    if(o instanceof Key) {
		Key k = (Key)o;
		Cell c = cells.get(k);
		if((c == null) || (c.built == null))
		    continue;
		dcells.add(k);
		area += 1L << (2 * k.lvl);
	    } else {
		Coord g = (Coord)o;
		if(!wlive.contains(g) && !wready.test(g.sub(wbase.off)))
		    continue;
		dfulls.add(g);
		area++;
	    }
	}
	return(area >= (1L << (2 * key.lvl)));
    }

    /** Whether the camera is coming closer to this cell: it covers more pixels than last tick. */
    private boolean approaching(Key key, double px) {
	Double last = pxlast.get(key);
	return((last != null) && (px > (last * (1 + 1e-6))));
    }

    /** Whether the camera is going away from this cell: it covers fewer pixels than last tick. */
    private boolean receding(Key key, double px) {
	Double last = pxlast.get(key);
	return((last != null) && (px < (last * (1 - 1e-6))));
    }

    /** Whether last tick drew anything under this cell. */
    private boolean drewunder(Key key) {
	if(lastunder == null)
	    lastunder = under(key.seg);
	return(lastunder.containsKey(key));
    }

    /** What last tick drew, filed under every cell above each piece of it. */
    private Map<Key, List<Object>> under(long seg) {
	Map<Key, List<Object>> ret = new HashMap<Key, List<Object>>();
	for(Key k : lastcells) {
	    for(int l = k.lvl + 1; l <= MAXLVL; l++)
		file(ret, new Key(k.seg, l, align(k.sc, l), k.flat), k);
	}
	for(Coord g : lastfulls) {
	    for(int l = 1; l <= MAXLVL; l++)
		file(ret, new Key(seg, l, align(g, l), lastflat), g);
	}
	return(ret);
    }

    private static void file(Map<Key, List<Object>> m, Key k, Object o) {
	List<Object> l = m.get(k);
	if(l == null)
	    m.put(k, l = new ArrayList<Object>());
	l.add(o);
    }

    /* The heights built cells have shown, filed under every cell above each: what a cell not built yet is
     * judged over. Only ever widened, and kept for the session: a key and two numbers a cell. */
    private final Map<Key, float[]> zknown = new HashMap<Key, float[]>();

    private void learn(Key key, Built b) {
	for(int l = key.lvl; l <= MAXLVL; l++) {
	    Key a = (l == key.lvl) ? key : new Key(key.seg, l, align(key.sc, l), key.flat);
	    float[] r = zknown.get(a);
	    if(r == null) {
		zknown.put(a, new float[] {b.zlo, b.zhi});
	    } else {
		r[0] = Math.min(r[0], b.zlo);
		r[1] = Math.max(r[1], b.zhi);
	    }
	}
    }

    /** The heights shown under this cell, or else under the nearest cell above it; null while none are known. */
    private float[] zguess(Key key) {
	for(int l = key.lvl; l <= MAXLVL; l++) {
	    float[] r = zknown.get((l == key.lvl) ? key : new Key(key.seg, l, align(key.sc, l), key.flat));
	    if(r != null)
		return(r);
	}
	return(null);
    }

    /** The corner of the cell of level {@code lvl} a segment grid coord is in. */
    private static Coord align(Coord sc, int lvl) {
	return(Coord.of(Math.floorDiv(sc.x, 1 << lvl) << lvl, Math.floorDiv(sc.y, 1 << lvl) << lvl));
    }

    /** Past the cap, the least recently wanted cells go -- never one the tick draws, wants or keeps finding
     * empty, nor one building. */
    private void trim(Set<Key> inuse) {
	int over = cells.size() - inuse.size() - CACHECAP;
	for(Iterator<Map.Entry<Key, Cell>> i = cells.entrySet().iterator(); i.hasNext() && (over > 0);) {
	    Map.Entry<Key, Cell> e = i.next();
	    if(inuse.contains(e.getKey()) || inscene.containsKey(e.getKey()) || inclick.containsKey(e.getKey()))
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
	for(Shown s : inscene.values())
	    s.slot.remove();
	inscene.clear();
	for(Shown s : inclick.values())
	    s.slot.remove();
	inclick.clear();
	for(Cell c : cells.values()) {
	    if(c.building != null)
		c.building.cancel();
	    if(c.built != null)
		c.built.dispose();
	}
	cells.clear();
	for(Built b : retired)
	    b.dispose();
	retired.clear();
	lastcells = Collections.emptyList();
	lastcellset = Collections.emptySet();
	lastfulls = Collections.emptyList();
	cellchange = true;
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

    /**
     * The cell's texture: one texel per sample, the colour the tileset's minimap image has there, as the map
     * window draws a zoom grid. Magnified -- a sample covers a few pixels by design ({@link #texelpx}) -- it is
     * drawn as crisp squares, as the map window draws its grids and the game its own ground tiles; minified, it
     * is mipmapped the way those tiles are ({@code Tileset}'s atlas: {@link Mipmapper#avg}, nearest within a
     * level and linear between two), so ground seen at a grazing angle is averaged rather than shimmering.
     * The samples fill the corner of the texture and the rest repeats the last row and column, so no level
     * reads past the ground. Ground never recorded is transparent, and the material discards it: a hole, not
     * a black floor, and avg leaves it out of every average.
     */
    private static TexRender texture(MapFile.DataGrid g, boolean[] nil) {
	int w = MCache.cmaps.x, h = MCache.cmaps.y;
	BufferedImage[] texes = new BufferedImage[g.tilesets.length];
	boolean[] got = new boolean[g.tilesets.length];
	byte[] px = new byte[TEXSZ * TEXSZ * 4];
	for(int y = 0; y < h; y++) {
	    for(int x = 0; x < w; x++) {
		int t = g.tiles[x + (y * w)];
		if(nil[t])
		    continue;
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
		int o = (x + (y * TEXSZ)) * 4;
		px[o] = (byte)(rgb >> 16);
		px[o + 1] = (byte)(rgb >> 8);
		px[o + 2] = (byte)rgb;
		px[o + 3] = (byte)255;
	    }
	    for(int x = w; x < TEXSZ; x++)
		System.arraycopy(px, ((w - 1) + (y * TEXSZ)) * 4, px, (x + (y * TEXSZ)) * 4, 4);
	}
	for(int y = h; y < TEXSZ; y++)
	    System.arraycopy(px, (h - 1) * TEXSZ * 4, px, y * TEXSZ * 4, TEXSZ * 4);
	VectorFormat fmt = new VectorFormat(4, NumberFormat.UNORM8);
	List<byte[]> levels = new ArrayList<byte[]>();
	levels.add(px);
	for(Coord sz = Coord.of(TEXSZ, TEXSZ); (sz.x > 1) || (sz.y > 1); sz = Mipmapper.nextsz(sz))
	    levels.add(px = Mipmapper.avg.gen4(sz, px, fmt));
	final byte[][] data = levels.toArray(new byte[0][]);
	Texture2D tex = new Texture2D(TEXSZ, TEXSZ, DataBuffer.Usage.STATIC, fmt, fmt, (img, env) -> {
		FillBuffer buf = env.fillbuf(img);
		buf.pull(ByteBuffer.wrap(data[img.level]));
		return(buf);
	    });
	Texture2D.Sampler2D smp = new Texture2D.Sampler2D(tex);
	smp.magfilter(Texture.Filter.NEAREST).minfilter(Texture.Filter.NEAREST).mipfilter(Texture.Filter.LINEAR);
	smp.wrapmode(Texture.Wrapping.CLAMP);
	return(new TexRender(smp) {
		public void render(GOut gout, float[] gc, float[] tc) {}
	    });
    }

    /**
     * How a cell is lit: as the ground it stands for is. The terrain's own materials ask for an ambient of 128/255
     * and a full diffuse ({@code col}), the same as {@code GroundTile}'s; {@code PhongLight}'s defaults, 0.2 and
     * 0.8, draw a cell a third darker than the ground beside it under a high sun and three fifths darker at night.
     */
    private static final Pipe.Op groundlight = new Light.PhongLight(true, new FColor(128 / 255f, 128 / 255f, 128 / 255f),
								     FColor.WHITE, FColor.BLACK, FColor.BLACK, 0f);

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
	int quads = 0;
	for(int y = 0; y < MCache.cmaps.y; y++) {
	    for(int x = 0; x < MCache.cmaps.x; x++) {
		if(!nil[g.tiles[x + (y * MCache.cmaps.x)]])
		    quads |= 1 << (((x * 2) / MCache.cmaps.x) + (((y * 2) / MCache.cmaps.y) * 2));
	    }
	}

	/* Interleaved position, normal, texcoord: the main grid, then one ring of skirt vertices. The texcoords
	 * span the samples, which fill the corner of a larger texture (texture()). */
	float tcs = (float)MCache.cmaps.x / TEXSZ;
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
		vert[o + 6] = (tcs * i) / n;
		vert[o + 7] = (tcs * j) / n;
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

	TexRender tr = texture(g, nil);
	Material mat = new Material(new Pipe.Op[] {
		tr.draw,
		tr.clip,
		groundlight,
		Material.nofacecull,
	    });
	/* The click mesh: the surface's vertices with their 0..1 place over the cell as ClickLocation's, and
	 * every quad that has a recorded sample in it -- a click on a hole reaches whatever lies behind. */
	float[] cvert = new float[nv * nv * 5];
	for(int v = 0; v < nv * nv; v++) {
	    System.arraycopy(vert, v * 8, cvert, v * 5, 3);
	    cvert[(v * 5) + 3] = (float)(v % nv) / n;
	    cvert[(v * 5) + 4] = (float)(v / nv) / n;
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

	return(new Built(model, tr, mat.apply(model), click, zlo, zhi, quads));
    }
}
