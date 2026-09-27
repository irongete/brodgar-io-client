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

package haven.render;

import java.util.*;
import java.util.concurrent.atomic.*;
import haven.*;
import haven.render.Rendered.Instancable;
import haven.render.Rendered.Instanced;
import haven.render.State.Instancer;

public class InstanceList implements RenderList<Rendered>, RenderList.Adapter, Disposable {
    private final List<RenderList<Rendered>> clients = new ArrayList<>();
    private final Adapter master;
    private final Map<InstKey, Object> instreg = new HashMap<>();
    private final Map<Slot<? extends Rendered>, Object> bypassed = new IdentityHashMap<>(), invalid = new IdentityHashMap<>();
    private final Map<Slot<? extends Rendered>, InstKey> uslotmap = new IdentityHashMap<>();
    private final Map<Slot<? extends Rendered>, InstancedSlot.Instance> islotmap = new IdentityHashMap<>();
    private final Map<Pipe, Object> ipipemap = new IdentityHashMap<>();
    private final Map<Pipe, Object> upipemap = new IdentityHashMap<>();
    private final Set<InstancedSlot> dirty = new HashSet<>();
    private int nbypass, ninvalid, nuinst, nbatches, ninst;

    private static int[][][] _stcounts = {};
    private static int[][] stcounts(int n) {
	int[][][] c = _stcounts;
	if((c != null) && (c.length > n) && (c[n] != null))
	    return(c[n]);
	synchronized(InstanceList.class) {
	    if((c == null) || (c.length <= n))
		_stcounts = c = Arrays.copyOf(_stcounts, n + 1);
	    int[][] ret = new int[2][n];
	    State.Slot.Slots si = State.Slot.slots;
	    int un = 0, in = 0;
	    for(int i = 0; i < n; i++) {
		if(si.idlist[i].instanced == null)
		    ret[0][un++] = i;
		else
		    ret[1][in++] = i;
	    }
	    ret[0] = Arrays.copyOf(ret[0], un);
	    ret[1] = Arrays.copyOf(ret[1], in);
	    return(c[n] = ret);
	}
    }

    private static Pipe[] uinststate(GroupPipe st, int ls) {
	int[] us = stcounts(ls + 1)[0];
	Pipe[] ret = new Pipe[us.length];
	for(int i = 0; i < ret.length; i++) {
	    int gn = st.gstate(us[i]);
	    if(gn >= 0)
		ret[i] = st.group(gn);
	}
	return(ret);
    }

    private static <T extends State> Instancer<T> instid0(Pipe buf, State.Slot<T> slot) {
	return(slot.instanced.instid(buf.get(slot)));
    }

    private static Instancer[] instids(GroupPipe st, int ls) {
	int[] is = stcounts(ls + 1)[1];
	Instancer[] ret = new Instancer[is.length];
	for(int i = 0; i < ret.length; i++) {
	    int gn = st.gstate(is[i]);
	    State.Slot<?> slot = State.Slot.byid(is[i]);
	    if(gn >= 0)
		ret[i] = instid0(st.group(gn), slot);
	    else
		ret[i] = slot.instanced.instid(null);
	}
	return(ret);
    }

    /* addon: A BATCH IS ONE MAP GRID'S. Upstream batches every slot of one mesh and one state wherever it
     * stands, so a batch is one draw call over the whole scene: it can never be left out for being off
     * screen, and every instance of it is drawn every frame -- thirteen thousand palisade pieces were ten
     * million triangles a frame with the camera on the ground. The grid its location puts a slot in is part
     * of the key, so a batch holds one grid's members, has a box a frustum can test (batchbox), and costs a
     * draw call per grid it covers instead of one. A slot with no location is in no grid. */
    private static final float CELL = (float)(haven.MCache.cmaps.x * haven.MCache.tilesz.x);
    private static final long NOCELL = Long.MIN_VALUE;

    private static long cellof(GroupPipe st) {
	Location.Chain loc = st.get(Homo3D.loc);
	if(loc == null)
	    return(NOCELL);
	float[] m = loc.fin(Matrix4f.id).m;
	long cx = (long)Math.floor(m[12] / CELL), cy = (long)Math.floor(m[13] / CELL);
	return((cx << 32) ^ (cy & 0xffffffffL));
    }

    /* addon: whether a location change in `mask` took the slot to another grid than its key's. */
    private static boolean movedcell(Slot<? extends Rendered> slot, InstKey key, int[] mask) {
	int lid = Homo3D.loc.id;
	for(int i = 0; i < mask.length; i++) {
	    if(mask[i] == lid)
		return(cellof(slot.state()) != key.cell);
	}
	return(false);
    }

    private static class InstKey {
	final Object instid;
	final Pipe[] ust;
	/* It may be argued that instids should be compared by equals
	 * rather than by identity, if need be. */
	final Instancer[] instids;
	final int[] instidmap;
	final long cell;	// addon: see CELL

	InstKey(Slot<? extends Rendered> slot) {
	    this.instid = ((Instancable)slot.obj()).instanceid();
	    GroupPipe st = slot.state();
	    this.cell = cellof(st);
	    int ls;
	    for(ls = st.nstates() - 1; (ls >= 0) && (st.gstate(ls) < 0); ls--);
	    if(ls < 0) {
		this.ust = new Pipe[0];
		this.instids = new Instancer[0];
		this.instidmap = new int[0];
	    } else {
		this.ust = uinststate(st, ls);
		this.instids = instids(st, ls);
		this.instidmap = stcounts(ls + 1)[1];
	    }
	}

	boolean valid() {
	    if(instid == null)
		return(false);
	    for(int i = 0; i < instids.length; i++) {
		if(instids[i] == null)
		    return(false);
	    }
	    return(true);
	}

	public int hashCode() {
	    int ret = System.identityHashCode(instid) ^ Long.hashCode(cell);	// addon: the grid
	    for(int i = 0; i < ust.length; i++)
		ret = (ret * 31) + System.identityHashCode(ust[i]);
	    for(int i = 0; i < instids.length; i++)
		ret = (ret * 31) + System.identityHashCode(instids[i]);
	    return(ret);
	}

	private boolean equals(InstKey that) {
	    if(this.instid != that.instid)
		return(false);
	    if(this.cell != that.cell)		// addon: the grid
		return(false);
	    if(this.ust.length != that.ust.length)
		return(false);
	    if(this.instids.length != that.instids.length)
		return(false);
	    for(int i = 0; i < ust.length; i++) {
		if(this.ust[i] != that.ust[i])
		    return(false);
	    }
	    for(int i = 0; i < instids.length; i++) {
		if(this.instids[i] != that.instids[i])
		    return(false);
	    }
	    return(true);
	}

	public boolean equals(Object x) {
	    return((x instanceof InstKey) ? equals((InstKey)x) : false);
	}

	public String toString() {
	    StringBuilder buf = new StringBuilder();
	    buf.append("#<instkey ");
	    buf.append(instid);
	    for(int i = 0; i < ust.length; i++)
		buf.append(String.format(" %x", System.identityHashCode(ust[i])));
	    buf.append(">");
	    return(buf.toString());
	}
    }

    private static class InstanceState extends BufPipe {
	final int[] mask;

	private <T extends State> void inststate0(State.Slot<T> slot, GroupPipe from, InstancedSlot batch) {
	    this.put(slot, slot.instanced.instid(from.get(slot)).inststate(from.get(slot), batch));
	}

	InstanceState(GroupPipe from, InstancedSlot batch) {
	    int ns = 0, fn;
	    for(fn = from.nstates() - 1; (fn >= 0) && (from.gstate(fn) < 0); fn--);
	    fn++;
	    int[] mask = new int[fn];
	    for(int i = 0; i < fn; i++) {
		State.Slot<?> slot = State.Slot.byid(i);
		if(slot.instanced != null) {
		    inststate0(slot, from, batch);
		    mask[ns++] = i;
		}
	    }
	    this.mask = Arrays.copyOf(mask, ns);
	}

	public static int compare(InstanceState x, InstanceState y) {
	    int c;
	    if((c = x.mask.length - y.mask.length) != 0)
		return(c);
	    for(int i = 0; i < x.mask.length; i++) {
		if((c = x.mask[i] - y.mask[i]) != 0)
		    return(c);
		State.Slot<?> slot = State.Slot.byid(x.mask[i]);
		if((c = Utils.idcmp.compare(x.get(slot), x.get(slot))) != 0)
		    return(c);
	    }
	    return(0);
	}

	public String toString() {
	    StringBuilder buf = new StringBuilder();
	    buf.append("[");
	    for(int i = 0; i < mask.length; i++) {
		int id = mask[i];
		if(i > 0)
		    buf.append(", ");
		buf.append(String.format("%d(%s)=%s", id, State.Slot.byid(id).scl.getSimpleName(), get(State.Slot.byid(id))));
	    }
	    buf.append("]");
	    return(buf.toString());
	}
    }

    private class InstancedSlot implements RenderList.Slot<Rendered>, InstanceBatch {
	final InstKey key;
	final Instanced rend;
	final InstanceState ist;
	final GroupPipe ust;
	Instance[] insts;
	int ni;
	boolean backdirty, selfdirty;

	class Instance {
	    final Slot<? extends Rendered> slot;
	    final Pipe[] rpipes;
	    int idx;

	    Instance(Slot<? extends Rendered> slot) {
		this.slot = slot;
		this.rpipes = new Pipe[ist.mask.length];
	    }

	    @SuppressWarnings("unchecked")
	    void register() {
		GroupPipe st = slot.state();
		for(int i = 0; i < ist.mask.length; i++) {
		    int gn = st.gstate(ist.mask[i]);
		    if(gn < 0)
			continue;
		    Pipe p = st.group(gn);
		    Object cur = ipipemap.get(p);
		    if(cur == null) {
			ipipemap.put(p, this);
		    } else if(cur instanceof Instance) {
			List<Instance> nl = new ArrayList<>(2);
			nl.add((Instance)cur);
			nl.add(this);
			ipipemap.put(p, nl);
		    } else if(cur instanceof List) {
			List<Instance> ls = (List<Instance>)cur;
			ls.add(this);
		    } else {
			throw(new AssertionError());
		    }
		    rpipes[i] = p;
		}
	    }

	    @SuppressWarnings("unchecked")
	    void unregister() {
		for(int i = 0; i < ist.mask.length; i++) {
		    Pipe p = rpipes[i];
		    if(p == null)
			continue;
		    Object cur = ipipemap.get(p);
		    if(cur == null) {
			throw(new AssertionError());
		    } else if(cur == this) {
			ipipemap.remove(p);
		    } else if(cur instanceof List) {
			List<Instance> ls = (List<Instance>)cur;
			ls.remove(this);
			if(ls.size() < 2)
			    ipipemap.put(p, ls.get(0));
		    } else {
			throw(new AssertionError());
		    }
		}
	    }

	    void update(Pipe group, int[] mask) {
		if(movedcell(slot, key, mask)) {	// addon: into another grid's batch
		    InstanceList.this.remove(slot);
		    InstanceList.this.add(slot);
		    return;
		}
		for(int i = 0; i < key.instids.length; i++) {
		    for(int o = 0; o < mask.length; o++) {
			if(mask[o] == key.instidmap[i]) {
			    if(instid0(group, State.Slot.byid(key.instidmap[i])) != key.instids[i]) {
				InstanceList.this.remove(slot);
				InstanceList.this.add(slot);
				return;
			    }
			}
		    }
		}
		/* XXX: There should be a way to only update the
		 * relevant states, by mask. */
		iupdate(idx);
	    }
	}

	InstancedSlot(InstKey key, Slot<? extends Rendered>[] slots) {
	    this.key = key;
	    this.ust = slots[0].state();
	    this.ist = new InstanceState(ust, this);
	    this.rend = ((Instancable)slots[0].obj()).instancify(this);
	    Instance[] insts = new Instance[slots.length];
	    for(int i = 0; i < slots.length; i++) {
		insts[i] = new Instance(slots[i]);
		insts[i].idx = i;
		if(i > 0) {
		    InstanceState test = new InstanceState(slots[i].state(), this);
		    if(InstanceState.compare(this.ist, test) != 0)
			throw(new RuntimeException(String.format("instantiation-IDs not yet implemented (states=%s vs %s)", this.ist, test)));
		}
	    }
	    this.insts = insts;
	    this.ni = slots.length;
	}

	void register() {
	    for(int i = 0; i < ni; i++) {
		insts[i].register();
		if(islotmap.put(insts[i].slot, insts[i]) != null)
		    throw(new AssertionError());
		iupdate(i);
	    }
	}

	void unregister() {
	    for(int i = 0; i < ni; i++) {
		insts[i].unregister();
		if(islotmap.remove(insts[i].slot) != insts[i])
		    throw(new AssertionError());
	    }
	}

	/* addon: the box around every member's mesh at its own location, for batchbox: made again only after
	 * a member was added, moved or taken out (iupdate, itrim). */
	private float[] wbox = null;
	private boolean boxdirty = true, nobox = false;

	InstanceList owner() {return(InstanceList.this);}

	float[] worldbox() {
	    if(!boxdirty)
		return(nobox ? null : wbox);
	    boxdirty = false;
	    float nx = Float.POSITIVE_INFINITY, ny = nx, nz = nx, px = Float.NEGATIVE_INFINITY, py = px, pz = px;
	    for(int i = 0; i < ni; i++) {
		Slot<? extends Rendered> s = insts[i].slot;
		Location.Chain loc = s.state().get(Homo3D.loc);
		if((loc == null) || !(s.obj() instanceof haven.FastMesh)) {
		    nobox = true;
		    return(null);
		}
		haven.Volume3f b = ((haven.FastMesh)s.obj()).bounds();
		float[] m = loc.fin(Matrix4f.id).m;
		for(int c = 0; c < 8; c++) {
		    float x = ((c & 1) == 0) ? b.n.x : b.p.x, y = ((c & 2) == 0) ? b.n.y : b.p.y, z = ((c & 4) == 0) ? b.n.z : b.p.z;
		    float wx = (m[0] * x) + (m[4] * y) + (m[ 8] * z) + m[12];
		    float wy = (m[1] * x) + (m[5] * y) + (m[ 9] * z) + m[13];
		    float wz = (m[2] * x) + (m[6] * y) + (m[10] * z) + m[14];
		    nx = Math.min(nx, wx); px = Math.max(px, wx);
		    ny = Math.min(ny, wy); py = Math.max(py, wy);
		    nz = Math.min(nz, wz); pz = Math.max(pz, wz);
		}
	    }
	    if(ni < 1) {
		nobox = true;
		return(null);
	    }
	    float[] w = new float[24];
	    for(int c = 0; c < 8; c++) {
		w[c * 3]     = ((c & 1) == 0) ? nx : px;
		w[c * 3 + 1] = ((c & 2) == 0) ? ny : py;
		w[c * 3 + 2] = ((c & 4) == 0) ? nz : pz;
	    }
	    nobox = false;
	    return(wbox = w);
	}

	private void iupdate(int idx) {
	    boxdirty = true;	// addon: see worldbox
	    rend.iupdate(idx);
	    for(int i = 0; i < ist.mask.length; i++) {
		State st = ist.get(State.Slot.byid(ist.mask[i]));
		if(st instanceof InstanceBatch.Client)
		    ((InstanceBatch.Client)st).iupdate(idx);
	    }
	    selfdirty = true;
	    dirty.add(this);
	}

	private void itrim(int idx) {
	    boxdirty = true;	// addon: see worldbox
	    rend.itrim(idx);
	    for(int i = 0; i < ist.mask.length; i++) {
		State st = ist.get(State.Slot.byid(ist.mask[i]));
		if(st instanceof InstanceBatch.Client)
		    ((InstanceBatch.Client)st).itrim(idx);
	    }
	    selfdirty = true;
	    dirty.add(this);
	}

	Instance add(Slot<? extends Rendered> ns, InstancedSlot replace) {
	    Instance inst = new Instance(ns);
	    inst.register();
	    Instance prev = islotmap.put(ns, inst);
	    if(replace == null) {
		if(prev != null)
		    throw(new AssertionError());
	    } else {
		if((prev == null) || (prev.slot != ns))
		    throw(new AssertionError());
	    }
	    if(insts.length == ni)
		insts = Arrays.copyOf(insts, insts.length * 2);
	    insts[inst.idx = ni++] = inst;
	    iupdate(inst.idx);
	    return(inst);
	}

	Instance remove(Instance inst) {
	    int ri = inst.idx;
	    if(insts[ri] != inst)
		throw(new AssertionError());
	    /* De-instancify when ni goes from 2 to 1? It's not
	     * *obviously* better to do so, and if the slot has once
	     * been instancified, it's probably not unreasonable to
	     * expect it to become so again in the future, in which
	     * case the updating overhead can be avoided. */
	    inst.unregister();
	    (insts[ri] = insts[--ni]).idx = inst.idx;
	    inst.idx = -1;
	    if(ni < 0)
		throw(new AssertionError());
	    if(ri < ni)
		iupdate(ri);
	    itrim(ni);
	    return(inst);
	}

	void dispose() {
	    rend.dispose();
	}

	void update(Slot<? extends Rendered> ns) {
	    /* XXX? Is this really necessary? Can't I just iupdate
	     * this? Also, if it is necessary, doesn't add() run the
	     * risk of throwing exceptions after remove()? */
	    InstanceList.this.remove(ns);
	    InstanceList.this.add(ns);
	}

	private class StateSum implements GroupPipe {
	    public Pipe group(int idx) {
		if(idx == 0)
		    return(ist);
		return(ust.group(idx - 1));
	    }

	    public int gstate(int id) {
		if(State.Slot.byid(id).instanced != null)
		    return(0);
		int ret = ust.gstate(id);
		return((ret < 0) ? ret : (ret + 1));
	    }

	    public int nstates() {
		return(ust.nstates());
	    }
	}

	public Rendered obj() {
	    return(rend);
	}

	private GroupPipe state = null;
	public GroupPipe state() {
	    if(state == null)
		state = new StateSum();
	    return(state);
	}

	public State.Slot<?>[] batchstates() {
	    State.Slot<?>[] ret = new State.Slot<?>[ist.mask.length];
	    for(int i = 0; i < ist.mask.length; i++)
		ret[i] = State.Slot.byid(ist.mask[i]);
	    return(ret);
	}

	public <T extends State> T batchstate(State.Slot<T> slot) {
	    return(ist.get(slot));
	}

	public int instances() {
	    return(ni);
	}

	public Pipe inststate(int idx) {
	    if(idx >= ni)
		throw(new ArrayIndexOutOfBoundsException(idx));
	    return(insts[idx].slot.state());
	}

	public void instupdate() {
	    backdirty = true;
	    dirty.add(this);
	}

	public <T extends State> void update(State.Slot<? super T> slot, T state) {
	    ist.put(slot, state);
	    backdirty = true;
	    dirty.add(this);
	}

	private void commit(Render g) {
	    if(backdirty) {
		clupdate(this);
		backdirty = false;
	    }
	    if(selfdirty) {
		rend.commit(g);
		selfdirty = false;
	    }
	}
    }

    private class Sole {
	final InstKey key;
	final Slot<? extends Rendered> slot;
	final Pipe[] rpipes;

	Sole(InstKey key, Slot<? extends Rendered> slot) {
	    this.key = key;
	    this.slot = slot;
	    this.rpipes = new Pipe[key.instids.length];
	}

	@SuppressWarnings("unchecked")
	void register() {
	    GroupPipe st = slot.state();
	    int nst = st.nstates();
	    int[] ism = key.instidmap;
	    for(int i = 0; (i < ism.length) && (ism[i] < nst); i++) {
		int gn = st.gstate(ism[i]);
		if(gn < 0)
		    continue;
		Pipe p = st.group(gn);
		Object cur = upipemap.get(p);
		if(cur == null) {
		    upipemap.put(p, this);
		} else if(cur instanceof Sole) {
		    List<Sole> nl = new ArrayList<>(2);
		    nl.add((Sole)cur);
		    nl.add(this);
		    upipemap.put(p, nl);
		} else if(cur instanceof List) {
		    List<Sole> ls = (List<Sole>)cur;
		    ls.add(this);
		} else {
		    throw(new AssertionError());
		}
		rpipes[i] = p;
	    }
	}

	@SuppressWarnings("unchecked")
	void unregister() {
	    for(int i = 0; i < rpipes.length; i++) {
		Pipe p = rpipes[i];
		if(p == null)
		    continue;
		Object cur = upipemap.get(p);
		if(cur == null) {
		    throw(new AssertionError());
		} else if(cur == this) {
		    upipemap.remove(p);
		} else if(cur instanceof List) {
		    List<Sole> ls = (List<Sole>)cur;
		    ls.remove(this);
		    if(ls.size() < 2)
			upipemap.put(p, ls.get(0));
		} else {
		    throw(new AssertionError());
		}
	    }
	}

	void update(Pipe group, int[] mask) {
	    if(movedcell(slot, key, mask)) {	// addon: into another grid, where it may batch
		InstanceList.this.remove(slot);
		InstanceList.this.add(slot);
		return;
	    }
	    for(int i = 0; i < key.instids.length; i++) {
		for(int o = 0; o < mask.length; o++) {
		    if(mask[o] == key.instidmap[i]) {
			if(instid0(group, State.Slot.byid(key.instidmap[i])) != key.instids[i]) {
			    InstanceList.this.remove(slot);
			    InstanceList.this.add(slot);
			    return;
			}
			break;
		    }
		}
	    }
	}
    }

    private void cladd(Slot<? extends Rendered> slot) {
	synchronized(clients) {
	    clients.forEach(cl -> cl.add(slot));
	}
    }

    private void clremove(Slot<? extends Rendered> slot) {
	synchronized(clients) {
	    clients.forEach(cl -> cl.remove(slot));
	}
    }

    private void clupdate(Slot<? extends Rendered> slot) {
	synchronized(clients) {
	    clients.forEach(cl -> cl.update(slot));
	}
    }

    private void clupdate(Pipe group, int[] mask) {
	synchronized(clients) {
	    clients.forEach(cl -> cl.update(group, mask));
	}
    }

    public InstanceList(Adapter master) {
	this.master = master;
    }

    @SuppressWarnings("unchecked")
    private void add0(Slot<? extends Rendered> slot, InstKey key, boolean prevsole, InstancedSlot previnst) {
	Object cur = instreg.get(key);
	if(cur == null) {
	    if(prevsole)
		clupdate(slot);
	    else
		cladd(slot);
	    if(previnst != null)
		remove0(previnst, islotmap.get(slot), true);
	    Sole reg = new Sole(key, slot);
	    instreg.put(key, reg);
	    uslotmap.put(slot, key);
	    reg.register();
	    nuinst++;
	} else if(cur instanceof InstancedSlot) {
	    InstancedSlot curbat = (InstancedSlot)cur;
	    InstancedSlot.Instance prev = null;
	    if(previnst != null)
		prev = islotmap.get(slot);
	    curbat.add(slot, previnst);
	    if(prevsole)
		clremove(slot);
	    if(previnst != null)
		remove0(previnst, prev, false);
	    uslotmap.put(slot, curbat.key);
	    ninst++;
	} else if(cur instanceof Sole) {
	    Slot<? extends Rendered> cs = ((Sole)cur).slot;
	    InstKey curkey = uslotmap.get(cs);
	    if(!curkey.equals(key))
		throw(new AssertionError());
	    InstancedSlot ni = new InstancedSlot(curkey, new Slot[] {cs, slot});
	    try {
		cladd(ni);
	    } catch(RuntimeException e) {
		ni.dispose();
		throw(e);
	    }
	    clremove(cs);
	    if(prevsole)
		clremove(slot);
	    if(previnst != null)
		remove0(previnst, islotmap.get(slot), true);
	    instreg.put(curkey, ni);
	    uslotmap.put(slot, curkey);
	    ni.register();
	    ((Sole)cur).unregister();
	    nuinst--; nbatches++; ninst += 2;
	} else {
	    throw(new AssertionError());
	}
    }

    @SuppressWarnings("unchecked")
    public void add(Slot<? extends Rendered> slot) {
	if(!(slot.obj() instanceof Instancable)) {
	    cladd(slot);
	    synchronized(bypassed) {
		bypassed.put(slot, Boolean.TRUE);
	    }
	    nbypass++;
	    return;
	}
	InstKey key = new InstKey(slot);
	synchronized(this) {
	    if(!key.valid()) {
		/* XXX: Slots excluded in this manner aren't registered
		 * for in-pipe updates which might bring them back into
		 * instantiation. I don't really foresee that happening,
		 * but it is also perhaps not so purely theoretical as to
		 * be of purely academic interest. It shouldn't
		 * technically break anything, however; it should just
		 * mean that they can't be re-instantiated if their instid
		 * only changes in-pipe. */
		cladd(slot);
		invalid.put(slot, Boolean.TRUE);
		ninvalid++;
		return;
	    }
	    add0(slot, key, false, null);
	}
    }

    private void remove0(InstancedSlot b, InstancedSlot.Instance inst, boolean unreg) {
	b.remove(inst);
	if(b.ni < 1) {
	    dirty.remove(b);
	    clremove(b);
	    b.unregister();
	    b.dispose();
	    if(instreg.remove(b.key) != b)
		throw(new AssertionError());
	    nbatches--;
	}
	ninst--;
	if(unreg && (islotmap.remove(inst.slot) != inst))
	    throw(new AssertionError());
    }

    @SuppressWarnings("unchecked")
    public void remove(Slot<? extends Rendered> slot) {
	if(!(slot.obj() instanceof Instancable)) {
	    clremove(slot);
	    synchronized(bypassed) {
		if(bypassed.remove(slot) != Boolean.TRUE)
		    throw(new IllegalStateException("removing non-present slot"));
	    }
	    nbypass--;
	    return;
	}
	synchronized(this) {
	    InstKey key = uslotmap.get(slot);
	    if(key == null) {
		if(invalid.remove(slot) != Boolean.TRUE)
		    throw(new IllegalStateException("removing non-present slot"));
		ninvalid--;
		clremove(slot);
		if(new InstKey(slot).valid())
		    Warning.warn("removing non-present slot with valid inst-key");
		return;
	    }
	    Object cur = instreg.get(key);
	    if(cur == null) {
		throw(new IllegalStateException("removing non-present slot"));
	    } else if(cur instanceof InstancedSlot) {
		InstancedSlot b = (InstancedSlot)cur;
		remove0((InstancedSlot)cur, islotmap.get(slot), true);
	    } else if(cur instanceof Sole) {
		Sole uinst = (Sole)cur;
		if(uinst.slot != slot)
		    throw(new IllegalStateException("removing non-present slot"));
		clremove(slot);
		instreg.remove(key);
		uinst.unregister();
		nuinst--;
	    } else {
		throw(new AssertionError());
	    }
	    uslotmap.remove(slot);
	}
    }

    public void update(Slot<? extends Rendered> slot) {
	if(!(slot.obj() instanceof Instancable)) {
	    clupdate(slot);
	    return;
	}
	InstKey key = new InstKey(slot);
	synchronized(this) {
	    InstKey prevkey = uslotmap.get(slot);
	    if(prevkey == null) {
		if(invalid.get(slot) != Boolean.TRUE)
		    throw(new IllegalStateException("updating non-present slot"));
		if(key.valid()) {
		    invalid.remove(slot);
		    add0(slot, key, true, null);
		    ninvalid--;
		} else {
		    clupdate(slot);
		}
		return;
	    }
	    Object prev = instreg.get(prevkey);
	    if(prev == null) {
		throw(new IllegalStateException("updating non-present slot"));
	    } else if(prev instanceof InstancedSlot) {
		InstancedSlot b = (InstancedSlot)prev;
		if(key.equals(prevkey)) {
		    b.update(slot);
		} else if(!key.valid()) {
		    cladd(slot);
		    remove0(b, islotmap.get(slot), true);
		    uslotmap.remove(slot);
		    invalid.put(slot, Boolean.TRUE);
		    ninvalid++;
		} else {
		    add0(slot, key, false, b);
		}
	    } else if(prev instanceof Sole) {
		Sole uinst = (Sole)prev;
		if(uinst.slot != slot)
		    throw(new IllegalStateException("updating non-present slot"));
		if(key.equals(prevkey)) {
		    clupdate(slot);
		} else if(!key.valid()) {
		    clupdate(slot);
		    instreg.remove(prevkey);
		    uslotmap.remove(slot);
		    uinst.unregister();
		    invalid.put(slot, Boolean.TRUE);
		    nuinst--;
		    ninvalid++;
		} else {
		    add0(slot, key, true, null);
		    instreg.remove(prevkey);
		    uinst.unregister();
		    nuinst--;
		}
	    } else {
		throw(new AssertionError());
	    }
	}
    }

    @SuppressWarnings("unchecked")
    public void update(Pipe group, int[] mask) {
	clupdate(group, mask);
	synchronized(this) {
	    Object insts = ipipemap.get(group);
	    if(insts instanceof InstancedSlot.Instance) {
		((InstancedSlot.Instance)insts).update(group, mask);
	    } else if(insts instanceof List) {
		for(InstancedSlot.Instance inst : new ArrayList<>((List<InstancedSlot.Instance>)insts))
		    inst.update(group, mask);
	    }
	    Object lone = upipemap.get(group);
	    if(lone instanceof Sole) {
		((Sole)lone).update(group, mask);
	    } else if(lone instanceof List) {
		for(Sole slot : new ArrayList<>((List<Sole>)lone))
		    slot.update(group, mask);
	    }
	}
    }

    public void commit(Render g) {
	synchronized(this) {
	    for(Iterator<InstancedSlot> i = dirty.iterator(); i.hasNext();) {
		InstancedSlot slot = i.next();
		slot.commit(g);
		i.remove();
	    }
	}
    }

    public Locked lock() {
	return(master.lock());
    }

    public Iterable<Slot<?>> slots() {
	return(new Iterable<Slot<?>>() {
		public Iterator<Slot<?>> iterator() {
		    Collection<Slot<?>> ret = new ArrayList<>();
		    for(Object slot : instreg.values()) {
			if(slot instanceof Sole) {
			    ret.add(((Sole)slot).slot);
			} else if(slot instanceof Slot) {
			    ret.add((Slot<?>)slot);
			} else {
			    throw(new AssertionError());
			}
		    }
		    for(Slot<?> slot : master.slots()) {
			if(!uslotmap.containsKey(slot))
			    ret.add(slot);
		    }
		    return(ret.iterator());
		}
	    });
    }

    @SuppressWarnings("unchecked")
    public <R> void add(RenderList<R> list, Class<? extends R> type) {
	if(type != Rendered.class)
	    throw(new IllegalArgumentException("instance-list can only reasonably handle rendering clients"));
	if(list == null)
	    throw(new NullPointerException());
	synchronized(clients) {
	    clients.add((RenderList<Rendered>)list);
	}
    }

    public void remove(RenderList<?> list) {
	synchronized(clients) {
	    clients.remove(list);
	}
    }

    public void dispose() {
	/* XXXRENDER */
    }

    /* addon: structured getters beside stats() (spec 019, task 019.3) -- the batching effectiveness
     * counters the list already maintains, as numbers instead of the "%,d+%,d(%,d) %d %d" the HUD shows.
     * No new counting and no behaviour change, so they answer with profiling off. They are written on the
     * render side and may be one frame stale, which is what a per-frame counter is worth anyway. */
    public int nuinst()   {return(nuinst);}     // slots drawn on their own (un-instanced)
    public int nbatches() {return(nbatches);}   // instanced batches
    public int ninst()    {return(ninst);}      // total instances across those batches
    public int ninvalid() {return(ninvalid);}   // slots whose instancing is pending revalidation
    public int nbypass()  {return(nbypass);}    // slots that cannot be instanced at all

    public String stats() {
	return(String.format("%,d+%,d(%,d) %d %d", nuinst, nbatches, ninst, ninvalid, nbypass));
    }

    /* addon: WHY A SLOT IS DRAWN ON ITS OWN -- the census behind the `instcensus` console command. Every
     * slot the list holds alone (a Sole: one draw call each) is counted under the object it draws, and
     * split in two: ALONE, no other slot of the list draws the same instance id, so there is nothing to
     * batch it with; SPLIT, some other slot does -- another Sole or a batch -- and the keys differ. For a
     * split one the reason is named: the state slots whose values differ from the first slot seen with
     * that instance id (by value, or "same value, other object" when equal but not identical), the
     * instancers that differ, or "groups only" when every state is the same and only the pipe groups are
     * other objects. Read-only; the caller holds the tree's lock. */
    public List<String> census() {
	synchronized(this) {
	    Map<Object, Slot<? extends Rendered>> first = new IdentityHashMap<>();
	    Map<Object, Integer> seen = new IdentityHashMap<>();
	    Set<Object> batched = Collections.newSetFromMap(new IdentityHashMap<>());
	    List<Sole> soles = new ArrayList<>();
	    for(Object reg : instreg.values()) {
		if(reg instanceof Sole)
		    soles.add((Sole)reg);
		else if(reg instanceof InstancedSlot)
		    batched.add(((InstancedSlot)reg).key.instid);
	    }
	    for(Sole s : soles) {
		seen.merge(s.key.instid, 1, Integer::sum);
		first.putIfAbsent(s.key.instid, s.slot);
	    }
	    Map<String, int[]> byobj = new TreeMap<>();      // label -> {alone, split, batched instances}
	    Map<String, Integer> reasons = new HashMap<>();
	    Map<String, Set<String>> reasonobjs = new HashMap<>();
	    int alone = 0, split = 0;
	    for(Sole s : soles) {
		String lbl = censuslabel(s.slot);
		int[] c = byobj.computeIfAbsent(lbl, k -> new int[4]);
		if(s.slot.obj() instanceof haven.FastMesh)
		    c[3] += ((haven.FastMesh)s.slot.obj()).indb.capacity() / 3;
		boolean shared = batched.contains(s.key.instid) || (seen.get(s.key.instid) > 1);
		if(!shared) {
		    c[0]++; alone++;
		    continue;
		}
		c[1]++; split++;
		Slot<? extends Rendered> ref = first.get(s.key.instid);
		if(ref == s.slot) {
		    /* The first of its instance id: compared against a batch's own slot if there is one. */
		    ref = null;
		    for(Object reg : instreg.values()) {
			if((reg instanceof InstancedSlot) && (((InstancedSlot)reg).key.instid == s.key.instid)) {
			    ref = batchsample((InstancedSlot)reg);
			    break;
			}
		    }
		    if(ref == null) {
			/* Compared against the second Sole of its id. */
			for(Sole o : soles) {
			    if((o != s) && (o.key.instid == s.key.instid)) {ref = o.slot; break;}
			}
		    }
		}
		String why = (ref == null) ? "?" : censusdiff(s.slot, ref);
		reasons.merge(why, 1, Integer::sum);
		reasonobjs.computeIfAbsent(why, k -> new TreeSet<>()).add(lbl);
	    }
	    for(Map.Entry<Slot<? extends Rendered>, InstancedSlot.Instance> e : islotmap.entrySet())
		byobj.computeIfAbsent(censuslabel(e.getKey()), k -> new int[4])[2]++;
	    List<String> out = new ArrayList<>();
	    out.add(String.format("instcensus: %,d drawn alone (%,d with nothing to batch with, %,d split from a same-mesh slot),"
				  + " %,d batches holding %,d, %,d bypassed, %,d invalid",
				  soles.size(), alone, split, nbatches, ninst, nbypass, ninvalid));
	    out.add("-- why the split ones did not batch (count: differing states -- objects)");
	    List<Map.Entry<String, Integer>> rs = new ArrayList<>(reasons.entrySet());
	    rs.sort((a, b) -> b.getValue() - a.getValue());
	    for(Map.Entry<String, Integer> e : rs) {
		Set<String> objs = reasonobjs.get(e.getKey());
		String ol = String.join(", ", objs);
		if(ol.length() > 300) ol = ol.substring(0, 300) + " ...";
		out.add(String.format("%6d: %s -- %d objects: %s", e.getValue(), e.getKey(), objs.size(), ol));
	    }
	    out.add("-- by object (alone / split / batched instances / triangles per draw of the alone+split), most draw calls first");
	    List<Map.Entry<String, int[]>> os = new ArrayList<>(byobj.entrySet());
	    os.sort((a, b) -> (b.getValue()[0] + b.getValue()[1]) - (a.getValue()[0] + a.getValue()[1]));
	    for(Map.Entry<String, int[]> e : os) {
		int[] c = e.getValue();
		int nd = c[0] + c[1];
		out.add(String.format("%6d %6d %6d %6s  %s", c[0], c[1], c[2], (nd > 0) ? String.valueOf(c[3] / nd) : "-", e.getKey()));
	    }
	    return(out);
	}
    }

    private Slot<? extends Rendered> batchsample(InstancedSlot b) {
	for(Map.Entry<Slot<? extends Rendered>, InstancedSlot.Instance> e : islotmap.entrySet()) {
	    if(uslotmap.get(e.getKey()) == b.key)
		return(e.getKey());
	}
	return(null);
    }

    /* addon: the object a batch draws, for the shadow list's census (ShadowMap.ShadowList.census) -- the
     * instance id every member shares, which for a mesh is the mesh itself. Null for any other slot. */
    public static Object batchobj(RenderList.Slot<?> slot) {
	if(slot instanceof InstanceList.InstancedSlot)
	    return(((InstanceList.InstancedSlot)slot).key.instid);
	return(null);
    }

    /* addon: the world box a batch's members stand in -- eight corners, x y z each, as FrustumList keeps
     * a mesh's -- or null for any other slot, and for a batch whose members are not all located meshes.
     * One grid's members at most (see CELL), so the box is a grid wide plus how far its meshes reach. */
    public static float[] batchbox(RenderList.Slot<?> slot) {
	if(!(slot instanceof InstanceList.InstancedSlot))
	    return(null);
	InstanceList.InstancedSlot b = (InstanceList.InstancedSlot)slot;
	synchronized(b.owner()) {
	    return(b.worldbox());
	}
    }

    private static String censuslabel(Slot<? extends Rendered> slot) {
	return(censuslabel(slot.obj(), slot.state().get(haven.ShadowMap.maskshadow.slot) != null));
    }

    public static String censuslabel(Object obj, boolean masked) {
	if((obj != null) && (obj.getClass() == haven.FastMesh.class)) {
	    /* A mesh the client built rather than a resource's: ground. The remembered ground and the far
	     * rings carry maskshadow at their slot, the live ground does not. */
	    return(masked ? "haven.FastMesh (built, maskshadow: remembered ground)" : "haven.FastMesh (built, casts: live ground)");
	}
	if(obj instanceof haven.FastMesh.ResourceMesh) {
	    haven.FastMesh.ResourceMesh m = (haven.FastMesh.ResourceMesh)obj;
	    return(m.res.name + "#" + m.id);
	}
	return((obj == null) ? "null" : obj.getClass().getName());
    }

    private static String censusdiff(Slot<? extends Rendered> a, Slot<? extends Rendered> b) {
	GroupPipe sa = a.state(), sb = b.state();
	int n = Math.max(sa.nstates(), sb.nstates());
	TreeSet<String> diff = new TreeSet<>();
	for(int i = 0; i < n; i++) {
	    State.Slot<?> slot = State.Slot.byid(i);
	    Object va = (i < sa.nstates()) ? sa.get(slot) : null;
	    Object vb = (i < sb.nstates()) ? sb.get(slot) : null;
	    if(va == vb)
		continue;
	    String nm = slot.scl.getSimpleName();
	    if(slot.instanced != null) {
		Instancer<?> ia = (va == null) ? null : instid1(slot, va);
		Instancer<?> ib = (vb == null) ? null : instid1(slot, vb);
		if(ia != ib)
		    diff.add(nm + "(instancer)");
		continue;
	    }
	    String what = (va == null) ? "absent/" : "";
	    what += (vb == null) ? "absent" : "";
	    if(what.isEmpty())
		what = Objects.equals(va, vb) ? "same value, other object" : censusval(va);
	    diff.add(nm + "(" + what + ")");
	}
	if(diff.isEmpty())
	    return("groups only");
	return(String.join(" ", diff));
    }

    @SuppressWarnings("unchecked")
    private static <T extends State> Instancer<?> instid1(State.Slot<T> slot, Object v) {
	return(slot.instanced.instid((T)v));
    }

    private static String censusval(Object v) {
	String cl = v.getClass().getSimpleName();
	if(cl.isEmpty())
	    cl = v.getClass().getName();
	return(cl);
    }
}
