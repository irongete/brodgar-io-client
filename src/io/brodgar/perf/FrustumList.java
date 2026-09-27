package io.brodgar.perf;

import java.util.*;

import haven.*;
import haven.render.*;

/**
 * Frustum culling for a view's main draw list: what the camera cannot see is not drawn.
 *
 * <p>It stands between the view's {@link InstanceList} and its {@link DrawList}, and hands on only the
 * slots whose mesh reaches the camera's frustum. The renderer itself tests nothing -- every slot in the
 * tree is a draw call every frame, on the one thread that talks to GL, and a scene of a few thousand
 * slots is bound by exactly that. Nothing is taken out of the tree: the shadow list is fed by the same
 * instancer and keeps every caster, so an object behind the camera still throws its shadow into view.
 *
 * <p>A slot is tested when it has a {@link Homo3D#loc} and its object is a {@link FastMesh}, whose bounds
 * are taken to the world once per location and to clip space through the slot's OWN camera and
 * projection every frame. It is left out only when all eight corners fall outside the same side plane, or
 * all eight lie behind the near plane -- never merely because none is inside, which is the way a box
 * wider than the screen vanishes. The side planes are widened by a margin: a slot comes in once its box
 * is within {@link #ENTER} of the frustum and goes out only past {@link #LEAVE}, so what enters on a turn
 * of the camera is already drawn when it reaches the edge, and one on the edge does not flicker in and
 * out. The margins are fractions of the half-width of the view at that distance: 0.1 widens a frustum
 * of 90 degrees across by about 3 degrees on each side, 0.2 by about 5. The far plane is not tested.
 *
 * <p>An instanced batch is tested as one box around all its members ({@link InstanceList#batchbox}): the
 * instancer puts one map grid's members in a batch, so the box is a grid wide and a batch off screen is
 * left out whole. A slot with no location, a non-mesh object (the sky, the weather, an outline), a batch
 * of something else than located meshes, or anything whose bounds cannot be had is always drawn. Every
 * call here is under the tree's lock, as the instancer's are.
 */
public class FrustumList implements RenderList<Rendered> {
    public static final double ENTER = 0.1, LEAVE = 0.2;
    /* Taking a slot out saves a draw call and costs a draw slot's disposal; putting one back costs its
     * compilation. Putting back is never deferred -- a slot turning into view has to be there -- but
     * taking out is, past this many a frame, and the rest are taken on the frames after. */
    private static final int REMOVES = 256;

    /* LEVELS OF DETAIL. A batch of a mesh (InstanceList.batchmesh) is drawn at the level its size on screen
     * calls for: the mesh's box diagonal over the nearest depth of the batch's box -- its nearest member, so
     * no member is drawn coarser than its own size asks -- as a share of the screen's height, the "screen
     * size" engines pick levels by. Below each of LODAT the next coarser level (haven.FastMesh.lod) is drawn,
     * and below TINY nothing is: a member of a batch that small is a pixel or two. A level boundary is crossed
     * only ten per cent past it, so a batch on the edge does not flip every frame. `:lod on|off` and
     * `:lodbias <x>` (more than 1 keeps detail further out) set them live. */
    private static final double[] LODAT = {0.10, 0.05, 0.025};
    private static final double TINY = 0.0015, HYST = 0.1;
    public static volatile boolean lodon = true;
    public static volatile double lodbias = 1.0;
    /* The last pass's batches of a mesh by the level they draw, full first, and those left out as tiny. */
    private static volatile int[] lodcounts = new int[LODAT.length + 2];

    static {
	haven.Console.setscmd("lod", (cons, args) -> {
		if(args.length > 1)
		    lodon = haven.Utils.parsebool(args[1]);
		int[] c = lodcounts;
		cons.out.println("lod: " + (lodon ? "on" : "off") + ", bias " + lodbias + "; batches by level (full, 1, 2, 3): "
				 + c[0] + " " + c[1] + " " + c[2] + " " + c[3] + ", left out as tiny: " + c[4]);
	    });
	haven.Console.setscmd("lodbias", (cons, args) -> {
		double b = Double.parseDouble(args[1]);
		if(!(b > 0))
		    throw(new Exception("lodbias: a positive number"));
		lodbias = b;
	    });
    }

    private static int lodlevel(double s, int cur) {
	int lvl = 0;
	for(int i = 0; i < LODAT.length; i++) {
	    double edge = LODAT[i] * ((cur > i) ? (1 + HYST) : (1 - HYST));
	    if(s < edge)
		lvl = i + 1;
	}
	return(lvl);
    }

    /* The share of the screen's height `diam` world units take at the nearest depth of box `wb`. */
    private static double screensize(float[] m, float[] wb, double diam) {
	float minw = Float.POSITIVE_INFINITY;
	for(int i = 0; i < 24; i += 3) {
	    float cw = (m[3] * wb[i]) + (m[7] * wb[i + 1]) + (m[11] * wb[i + 2]) + m[15];
	    minw = Math.min(minw, cw);
	}
	if(!(minw > 1e-3f))
	    return(Double.POSITIVE_INFINITY);
	double sy = Math.sqrt((m[1] * m[1]) + (m[5] * m[5]) + (m[9] * m[9]));
	return((diam * sy) / (2 * minw));
    }

    private final DrawList back;
    private final Map<Slot<? extends Rendered>, Entry> slots = new HashMap<>();
    private final List<Entry> order = new ArrayList<>();
    private boolean enabled = false;
    private int nculled = 0, ncullable = 0;
    /* How many world boxes have been taken since the list was made: for a slot seen the first time, and
     * for one whose location moved. Two reads apart, with nothing moving, both should stand still. */
    public long nnewbox = 0, nmovedbox = 0;

    /* The one clip matrix of the frame, for the camera and projection nearly every slot shares. */
    private Camera ccam = null;
    private Projection cprj = null;
    private Matrix4f cclip = null;

    private static class Entry {
	final Slot<? extends Rendered> slot;
	int oidx;
	boolean drawn;
	/* The world box, for the location it was taken under: eight corners, x y z each. */
	Location.Chain wloc;
	float[] wbox;
	boolean nobox;
	/* A batch's: its mesh's box diagonal (0: no mesh, no levels; -1: not taken yet), its size on screen
	 * this frame, and the level it was last given. */
	double diam = -1, scr;
	int lod;
	boolean tiny;	// left out this pass for being too small to see, rather than off screen

	Entry(Slot<? extends Rendered> slot) {
	    this.slot = slot;
	}
    }

    public FrustumList(DrawList back) {
	this.back = back;
    }

    /** How many slots are left out of the draw this frame. */
    public int culled() {return(nculled);}
    /** How many slots are tested at all: the rest are always drawn. */
    public int cullable() {return(ncullable);}

    public void add(Slot<? extends Rendered> slot) {
	Entry e = new Entry(slot);
	/* Put in `back` whether or not it is in view, and taken out again at once if it is not: the add is
	 * what prepares the slot's textures, and throws Loading until they are ready, before anything here is
	 * recorded. Whoever adds a slot counts on that -- RUtils.readd puts a sprite's old parts back when the
	 * new ones throw, and a part never prepared because it was out of view threw again there. */
	back.add(slot);
	boolean want = !enabled || (visible(e, ENTER) != Boolean.FALSE);
	if(!want)
	    back.remove(slot);
	e.drawn = want;
	if(slots.put(slot, e) != null)
	    throw(new AssertionError());
	e.oidx = order.size();
	order.add(e);
	if(!want)
	    nculled++;
    }

    public void remove(Slot<? extends Rendered> slot) {
	Entry e = slots.remove(slot);
	if(e == null)
	    return;
	if(e.drawn)
	    back.remove(slot);
	else
	    nculled--;
	Entry last = order.remove(order.size() - 1);
	if(last != e) {
	    order.set(e.oidx, last);
	    last.oidx = e.oidx;
	}
    }

    public void update(Slot<? extends Rendered> slot) {
	Entry e = slots.get(slot);
	if((e != null) && e.drawn)
	    back.update(slot);
    }

    public void update(Pipe group, int[] statemask) {
	back.update(group, statemask);
    }

    /**
     * The frame's pass, from the view's draw under the tree's lock: every slot asked, what came into view
     * put back and what left it taken out. Off, it puts everything back and then does nothing.
     */
    public void cull(boolean on) {
	if(!on && !enabled && (nculled == 0))
	    return;
	enabled = on;
	ccam = null; cprj = null; cclip = null;
	int removes = 0, testable = 0;
	boolean lod = on && lodon;
	double bias = lodbias;
	int[] counts = new int[LODAT.length + 2];
	for(int i = 0; i < order.size(); i++) {
	    Entry e = order.get(i);
	    Boolean vis = on ? visible(e, e.drawn ? LEAVE : ENTER) : null;
	    if(vis != null)
		testable++;
	    boolean want = (vis != Boolean.FALSE);
	    if(want != e.drawn) {
		if(want) {
		    try {
			back.add(e.slot);
			e.drawn = true;
			nculled--;
		    } catch(RuntimeException exc) {
			/* Not compilable yet -- a texture still loading. Asked again next frame. */
		    }
		} else if(removes < REMOVES) {
		    removes++;
		    back.remove(e.slot);
		    e.drawn = false;
		    nculled++;
		}
	    }
	    if(e.drawn && (e.slot instanceof InstanceBatch) && (lod || (e.lod != 0))) {
		int lvl = (lod && (e.diam > 0) && (vis != null)) ? lodlevel(e.scr * bias, e.lod) : 0;
		e.lod = lvl;
		/* Every frame, not only on a change: the levels are made off the frame, and until they are
		 * the batch goes on drawing all of itself. */
		InstanceList.batchlod(e.slot, lvl);
		counts[lvl]++;
	    } else if(!e.drawn && e.tiny && (vis == Boolean.FALSE)) {
		counts[LODAT.length + 1]++;
	    }
	}
	ncullable = testable;
	if(on)
	    lodcounts = counts;
    }

    /* TRUE in view, FALSE out of it, null when this slot cannot be tested and is always drawn. */
    private Boolean visible(Entry e, double margin) {
	try {
	    Slot<? extends Rendered> slot = e.slot;
	    GroupPipe st = slot.state();
	    float[] wbox;
	    if(slot instanceof InstanceBatch) {
		/* One grid's members (InstanceList.CELL), in the box around them all, which the list keeps
		 * until a member comes, goes or moves. */
		if((wbox = InstanceList.batchbox(slot)) == null)
		    return(null);
	    } else {
		wbox = null;
	    }
	    Location.Chain loc = (wbox == null) ? st.get(Homo3D.loc) : null;
	    if((wbox == null) && (loc == null))
		return(null);
	    Camera cam = st.get(Homo3D.cam);
	    Projection prj = st.get(Homo3D.prj);
	    if((cam == null) || (prj == null))
		return(null);
	    if((wbox == null) && e.nobox)
		return(null);
	    if((wbox == null) && (e.wloc != loc)) {
		if(e.wbox == null) nnewbox++; else nmovedbox++;
		Rendered obj = slot.obj();
		if(!(obj instanceof FastMesh)) {
		    e.nobox = true;
		    return(null);
		}
		Volume3f b = ((FastMesh)obj).bounds();
		Matrix4f lxf = loc.fin(Matrix4f.id);
		float[] w = new float[24];
		for(int i = 0; i < 8; i++) {
		    Coord3f c = lxf.mul4(new Coord3f(((i & 1) == 0) ? b.n.x : b.p.x,
						     ((i & 2) == 0) ? b.n.y : b.p.y,
						     ((i & 4) == 0) ? b.n.z : b.p.z));
		    w[i * 3] = c.x; w[i * 3 + 1] = c.y; w[i * 3 + 2] = c.z;
		}
		e.wbox = w;
		e.wloc = loc;
	    }
	    Matrix4f clip;
	    if((cam == ccam) && (prj == cprj)) {
		clip = cclip;
	    } else {
		clip = prj.fin(Matrix4f.id).mul(cam.fin(Matrix4f.id));
		ccam = cam; cprj = prj; cclip = clip;
	    }
	    if(wbox != null) {
		e.tiny = false;
		if(!inside(clip.m, wbox, (float)(1.0 + margin)))
		    return(Boolean.FALSE);
		if(e.diam < 0) {
		    FastMesh m = InstanceList.batchmesh(slot);
		    e.diam = (m == null) ? 0 : m.bounds().n.dist(m.bounds().p);
		}
		if(e.diam > 0) {
		    e.scr = screensize(clip.m, wbox, e.diam * InstanceList.batchscale(slot));
		    if(lodon && ((e.scr * lodbias) < (TINY * (e.drawn ? (1 - HYST) : (1 + HYST))))) {
			e.tiny = true;
			return(Boolean.FALSE);
		    }
		}
		return(Boolean.TRUE);
	    }
	    return(inside(clip.m, e.wbox, (float)(1.0 + margin)));
	} catch(RuntimeException exc) {
	    return(null);
	}
    }

    private static boolean inside(float[] m, float[] wb, float k) {
	boolean left = true, right = true, down = true, up = true, near = true;
	for(int i = 0; i < 24; i += 3) {
	    float x = wb[i], y = wb[i + 1], z = wb[i + 2];
	    float cx = (m[0] * x) + (m[4] * y) + (m[ 8] * z) + m[12];
	    float cy = (m[1] * x) + (m[5] * y) + (m[ 9] * z) + m[13];
	    float cz = (m[2] * x) + (m[6] * y) + (m[10] * z) + m[14];
	    float cw = (m[3] * x) + (m[7] * y) + (m[11] * z) + m[15];
	    float kw = k * cw;
	    if(!(cx < -kw)) left = false;
	    if(!(cx >  kw)) right = false;
	    if(!(cy < -kw)) down = false;
	    if(!(cy >  kw)) up = false;
	    if(!(cz < -cw)) near = false;
	}
	return(!(left || right || down || up || near));
    }
}
