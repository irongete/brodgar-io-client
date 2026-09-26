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
 * <p>An instanced batch is always drawn -- its members stand all over the scene and it is one draw call
 * however many of them are in view -- and so is a slot with no location, a non-mesh object (the sky, the
 * weather, an outline) or one whose bounds cannot be had. Every call here is under the tree's lock, as
 * the instancer's are.
 */
public class FrustumList implements RenderList<Rendered> {
    public static final double ENTER = 0.1, LEAVE = 0.2;
    /* Taking a slot out saves a draw call and costs a draw slot's disposal; putting one back costs its
     * compilation. Putting back is never deferred -- a slot turning into view has to be there -- but
     * taking out is, past this many a frame, and the rest are taken on the frames after. */
    private static final int REMOVES = 256;

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
	for(int i = 0; i < order.size(); i++) {
	    Entry e = order.get(i);
	    Boolean vis = on ? visible(e, e.drawn ? LEAVE : ENTER) : null;
	    if(vis != null)
		testable++;
	    boolean want = (vis != Boolean.FALSE);
	    if(want == e.drawn)
		continue;
	    if(want) {
		try {
		    back.add(e.slot);
		} catch(RuntimeException exc) {
		    /* Not compilable yet -- a texture still loading. Asked again next frame. */
		    continue;
		}
		e.drawn = true;
		nculled--;
	    } else {
		if(removes >= REMOVES)
		    continue;
		removes++;
		back.remove(e.slot);
		e.drawn = false;
		nculled++;
	    }
	}
	ncullable = testable;
    }

    /* TRUE in view, FALSE out of it, null when this slot cannot be tested and is always drawn. */
    private Boolean visible(Entry e, double margin) {
	try {
	    Slot<? extends Rendered> slot = e.slot;
	    if(slot instanceof InstanceBatch)
		return(null);
	    GroupPipe st = slot.state();
	    Location.Chain loc = st.get(Homo3D.loc);
	    if(loc == null)
		return(null);
	    Camera cam = st.get(Homo3D.cam);
	    Projection prj = st.get(Homo3D.prj);
	    if((cam == null) || (prj == null))
		return(null);
	    if(e.nobox)
		return(null);
	    if(e.wloc != loc) {
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
