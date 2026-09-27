package io.brodgar.perf;

import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.*;

/**
 * Levels of detail for a mesh, as index lists over its OWN vertices: each one draws fewer triangles through the
 * very vertex buffer (and, instanced, the very instance buffer) the full mesh draws through, so a level is one
 * more index buffer and nothing else.
 *
 * <p>Made by quadric edge collapse (Garland and Heckbert; what meshoptimizer's simplifier does): the edge whose
 * removal moves the surface least goes first, one end folded onto the other, until the level's share of the
 * triangles is left. It runs over the mesh with the vertices at one point WELDED, because the models split a
 * vertex at every hard edge and texture seam and an edge collapse that honoured every split could fold almost
 * nothing; each corner is then handed back the original vertex, of those at its new point, whose normal is
 * nearest the one the corner had, so a hard edge stays hard. A collapse is refused when it would turn a
 * triangle over, fold an edge that more than two triangles share, or leave an open border by any way but
 * along it, and a border is weighted so it keeps its outline.
 *
 * <p>A mesh of loose little pieces -- leaves on cards, grass -- is left alone: folding those away is exactly
 * what makes a tree bald, and there is no triangle count in them worth taking.
 */
public final class MeshLod {
    /** The share of the full triangle count each level aims at, finest first. */
    public static final float[] TARGET = {0.5f, 0.25f, 0.1f};
    /**
     * How far each level may move the surface, as a share of the mesh's box diagonal: a level stops at its
     * share of the triangles or at this, whichever comes first. FrustumList draws level k below a screen size
     * of LODAT[k-1], so each bound comes to about a pixel and a half on a screen 1440 high -- a boxy mesh that
     * cannot come down further without losing a piece of itself keeps the piece.
     */
    public static final float[] MAXERR = {0.01f, 0.02f, 0.04f};
    /** A mesh of fewer triangles has no levels. */
    public static final int MINTRIS = 48;
    /** A mesh whose connected pieces average fewer triangles than this is loose pieces, and has no levels. */
    public static final int MINPIECE = 6;
    /** A level must take at least this share off the one before it, or it and the coarser ones are not made. */
    private static final float STEP = 0.8f;
    /** How much an open border's constraint weighs against the surface's own planes. */
    private static final double BORDER = 10.0;

    private MeshLod() {}

    /**
     * The levels of the mesh drawn by {@code ind} over the positions {@code pos} (three floats a vertex) and the
     * normals {@code nrm} (three a vertex, or null), finest first, each an index list over the same vertices.
     * None at all when nothing came out usefully smaller; otherwise one per level, where a level that could not
     * go usefully further than the one before repeats it (the same array), and a first level that could not
     * go usefully below the full mesh is null.
     */
    public static short[][] build(FloatBuffer pos, FloatBuffer nrm, ShortBuffer ind) {
	int ni = ind.capacity() - (ind.capacity() % 3), nt = ni / 3;
	if(nt < MINTRIS)
	    return(new short[0][]);
	int nv = pos.capacity() / 3;
	float[] p = new float[nv * 3];
	for(int i = 0; i < nv * 3; i++)
	    p[i] = pos.get(i);
	float[] n = null;
	if((nrm != null) && (nrm.capacity() >= nv * 3)) {
	    n = new float[nv * 3];
	    for(int i = 0; i < nv * 3; i++)
		n[i] = nrm.get(i);
	}
	int[] otri = new int[ni];
	for(int i = 0; i < ni; i++)
	    otri[i] = ind.get(i) & 0xffff;
	return(new Collapse(p, n, otri).run());
    }

    private static final class Collapse {
	final float[] p, n;
	final int[] otri;	// the original corner vertices, which never change
	final int nt;
	/* Welded vertices: one per point the mesh's vertices stand at. */
	int nw;
	int[] wid;		// original vertex -> welded
	double[] wp;		// welded positions
	List<List<Integer>> wedges = new ArrayList<>();
	/* The live mesh over welded vertices. */
	int[] tri;
	boolean[] tdead;
	int alive;
	List<List<Integer>> vtris = new ArrayList<>();
	double[] q, qw;		// plane quadrics, and the surface weight in them
	double diag;
	boolean[] locked, border, vdead;
	int[] ver;
	Set<Long> bedges = new HashSet<>();

	Collapse(float[] p, float[] n, int[] otri) {
	    this.p = p; this.n = n; this.otri = otri; this.nt = otri.length / 3;
	}

	static long ekey(int a, int b) {
	    return((a < b) ? (((long)a << 32) | b) : (((long)b << 32) | a));
	}

	short[][] run() {
	    weld();
	    if(pieces() * MINPIECE > alive)
		return(new short[0][]);
	    edges();
	    quadrics();
	    PriorityQueue<double[]> pq = new PriorityQueue<>((x, y) -> Double.compare(x[0], y[0]));
	    for(int t = 0; t < nt; t++) {
		if(tdead[t])
		    continue;
		for(int k = 0; k < 3; k++)
		    push(pq, tri[t * 3 + k], tri[t * 3 + ((k + 1) % 3)]);
	    }
	    short[][] out = new short[TARGET.length][];
	    short[] last = null;
	    int prev = alive;
	    boolean any = false;
	    for(int l = 0; l < TARGET.length; l++) {
		int target = Math.max(1, (int)(nt * TARGET[l]));
		double limit = MAXERR[l] * diag;
		while((alive > target) && !pq.isEmpty()) {
		    double[] c = pq.peek();
		    if(c[0] > limit)
			break;		/* the next fold moves the surface too far for this level: the next one's */
		    pq.poll();
		    int u = (int)c[1], v = (int)c[2];
		    if(vdead[u] || vdead[v] || (ver[u] != (int)c[3]) || (ver[v] != (int)c[4]))
			continue;
		    if(!legal(u, v))
			continue;
		    fold(u, v, pq);
		}
		if((alive > 0) && (alive <= prev * STEP)) {
		    last = emit();
		    prev = alive;
		    any = true;
		}
		out[l] = last;
	    }
	    return(any ? out : new short[0][]);
	}

	void weld() {
	    Map<Long, Integer> at = new HashMap<>();
	    wid = new int[p.length / 3];
	    Arrays.fill(wid, -1);
	    List<Double> pl = new ArrayList<>();
	    for(int i = 0; i < otri.length; i++) {
		int v = otri[i];
		if(wid[v] >= 0)
		    continue;
		long k = ((((long)Float.floatToIntBits(p[v * 3]) * 1000003L) + Float.floatToIntBits(p[v * 3 + 1])) * 1000003L) + Float.floatToIntBits(p[v * 3 + 2]);
		Integer w = at.get(k);
		if(w == null) {
		    at.put(k, w = nw++);
		    pl.add((double)p[v * 3]); pl.add((double)p[v * 3 + 1]); pl.add((double)p[v * 3 + 2]);
		    wedges.add(new ArrayList<>());
		    vtris.add(new ArrayList<>());
		}
		wid[v] = w;
		wedges.get(w).add(v);
	    }
	    wp = new double[nw * 3];
	    for(int i = 0; i < wp.length; i++)
		wp[i] = pl.get(i);
	    double[] lo = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE}, hi = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
	    for(int i = 0; i < nw; i++) {
		for(int k = 0; k < 3; k++) {
		    lo[k] = Math.min(lo[k], wp[i * 3 + k]);
		    hi[k] = Math.max(hi[k], wp[i * 3 + k]);
		}
	    }
	    diag = (nw == 0) ? 0 : Math.sqrt(((hi[0] - lo[0]) * (hi[0] - lo[0])) + ((hi[1] - lo[1]) * (hi[1] - lo[1])) + ((hi[2] - lo[2]) * (hi[2] - lo[2])));
	    tri = new int[otri.length];
	    tdead = new boolean[nt];
	    for(int t = 0; t < nt; t++) {
		int a = wid[otri[t * 3]], b = wid[otri[t * 3 + 1]], c = wid[otri[t * 3 + 2]];
		tri[t * 3] = a; tri[t * 3 + 1] = b; tri[t * 3 + 2] = c;
		if((a == b) || (b == c) || (a == c)) {
		    tdead[t] = true;
		    continue;
		}
		alive++;
		vtris.get(a).add(t); vtris.get(b).add(t); vtris.get(c).add(t);
	    }
	    q = new double[nw * 10];
	    qw = new double[nw];
	    locked = new boolean[nw];
	    border = new boolean[nw];
	    vdead = new boolean[nw];
	    ver = new int[nw];
	}

	int pieces() {
	    int[] up = new int[nw];
	    for(int i = 0; i < nw; i++)
		up[i] = i;
	    for(int t = 0; t < nt; t++) {
		if(tdead[t])
		    continue;
		union(up, tri[t * 3], tri[t * 3 + 1]);
		union(up, tri[t * 3], tri[t * 3 + 2]);
	    }
	    Set<Integer> roots = new HashSet<>();
	    for(int t = 0; t < nt; t++) {
		if(!tdead[t])
		    roots.add(find(up, tri[t * 3]));
	    }
	    return(roots.size());
	}

	void edges() {
	    Map<Long, Integer> uses = new HashMap<>();
	    for(int t = 0; t < nt; t++) {
		if(tdead[t])
		    continue;
		for(int k = 0; k < 3; k++)
		    uses.merge(ekey(tri[t * 3 + k], tri[t * 3 + ((k + 1) % 3)]), 1, Integer::sum);
	    }
	    for(Map.Entry<Long, Integer> e : uses.entrySet()) {
		int a = (int)(e.getKey() >>> 32), b = (int)(e.getKey() & 0xffffffffL);
		if(e.getValue() == 1) {
		    bedges.add(e.getKey());
		    border[a] = border[b] = true;
		} else if(e.getValue() > 2) {
		    locked[a] = locked[b] = true;
		}
	    }
	}

	double[] fnormal(int a, int b, int c) {
	    double ax = wp[b * 3] - wp[a * 3], ay = wp[b * 3 + 1] - wp[a * 3 + 1], az = wp[b * 3 + 2] - wp[a * 3 + 2];
	    double bx = wp[c * 3] - wp[a * 3], by = wp[c * 3 + 1] - wp[a * 3 + 1], bz = wp[c * 3 + 2] - wp[a * 3 + 2];
	    return(new double[] {(ay * bz) - (az * by), (az * bx) - (ax * bz), (ax * by) - (ay * bx)});
	}

	void addplane(int v, double ux, double uy, double uz, double d, double w) {
	    int o = v * 10;
	    q[o]     += w * ux * ux; q[o + 1] += w * ux * uy; q[o + 2] += w * ux * uz; q[o + 3] += w * ux * d;
	    q[o + 4] += w * uy * uy; q[o + 5] += w * uy * uz; q[o + 6] += w * uy * d;
	    q[o + 7] += w * uz * uz; q[o + 8] += w * uz * d;
	    q[o + 9] += w * d * d;
	}

	void quadrics() {
	    for(int t = 0; t < nt; t++) {
		if(tdead[t])
		    continue;
		int a = tri[t * 3], b = tri[t * 3 + 1], c = tri[t * 3 + 2];
		double[] fn = fnormal(a, b, c);
		double len = Math.sqrt((fn[0] * fn[0]) + (fn[1] * fn[1]) + (fn[2] * fn[2]));
		if(len <= 0)
		    continue;
		double ux = fn[0] / len, uy = fn[1] / len, uz = fn[2] / len;
		double d = -((ux * wp[a * 3]) + (uy * wp[a * 3 + 1]) + (uz * wp[a * 3 + 2]));
		double w = len * 0.5;
		addplane(a, ux, uy, uz, d, w); addplane(b, ux, uy, uz, d, w); addplane(c, ux, uy, uz, d, w);
		qw[a] += w; qw[b] += w; qw[c] += w;
		/* An open edge: a plane through it, standing across the face, so it keeps its place. */
		for(int k = 0; k < 3; k++) {
		    int e0 = tri[t * 3 + k], e1 = tri[t * 3 + ((k + 1) % 3)];
		    if(!bedges.contains(ekey(e0, e1)))
			continue;
		    double ex = wp[e1 * 3] - wp[e0 * 3], ey = wp[e1 * 3 + 1] - wp[e0 * 3 + 1], ez = wp[e1 * 3 + 2] - wp[e0 * 3 + 2];
		    double px = (ey * uz) - (ez * uy), py = (ez * ux) - (ex * uz), pz = (ex * uy) - (ey * ux);
		    double pl = Math.sqrt((px * px) + (py * py) + (pz * pz));
		    if(pl <= 0)
			continue;
		    px /= pl; py /= pl; pz /= pl;
		    double pd = -((px * wp[e0 * 3]) + (py * wp[e0 * 3 + 1]) + (pz * wp[e0 * 3 + 2]));
		    double bw = BORDER * ((ex * ex) + (ey * ey) + (ez * ez));
		    addplane(e0, px, py, pz, pd, bw); addplane(e1, px, py, pz, pd, bw);
		}
	    }
	}

	/* The error of folding u onto v: both vertices' planes, measured where v stands. */
	double cost(int u, int v) {
	    double x = wp[v * 3], y = wp[v * 3 + 1], z = wp[v * 3 + 2];
	    double e = 0;
	    for(int s : new int[] {u, v}) {
		int o = s * 10;
		e += (q[o] * x * x) + (2 * q[o + 1] * x * y) + (2 * q[o + 2] * x * z) + (2 * q[o + 3] * x)
		   + (q[o + 4] * y * y) + (2 * q[o + 5] * y * z) + (2 * q[o + 6] * y)
		   + (q[o + 7] * z * z) + (2 * q[o + 8] * z) + q[o + 9];
	    }
	    /* As a distance: the root of the mean squared distance to the planes, weighted by their area. */
	    return(Math.sqrt(Math.max(0, e) / Math.max(1e-12, qw[u] + qw[v])));
	}

	void push(PriorityQueue<double[]> pq, int a, int b) {
	    if(!locked[a] && (!border[a] || bedges.contains(ekey(a, b))))
		pq.add(new double[] {cost(a, b), a, b, ver[a], ver[b]});
	    if(!locked[b] && (!border[b] || bedges.contains(ekey(a, b))))
		pq.add(new double[] {cost(b, a), b, a, ver[b], ver[a]});
	}

	/* Whether u may be folded onto v: the link condition, and no triangle left of u turned over. */
	boolean legal(int u, int v) {
	    Set<Integer> nu = new HashSet<>(), nv = new HashSet<>();
	    int shared = 0;
	    for(int t : vtris.get(u)) {
		boolean hasv = false;
		for(int k = 0; k < 3; k++) {
		    int x = tri[t * 3 + k];
		    if(x == v) hasv = true;
		    else if(x != u) nu.add(x);
		}
		if(hasv) shared++;
	    }
	    if(shared == 0)
		return(false);
	    for(int t : vtris.get(v)) {
		for(int k = 0; k < 3; k++) {
		    int x = tri[t * 3 + k];
		    if((x != u) && (x != v)) nv.add(x);
		}
	    }
	    nu.retainAll(nv);
	    if(nu.size() > shared)
		return(false);
	    for(int t : vtris.get(u)) {
		int a = tri[t * 3], b = tri[t * 3 + 1], c = tri[t * 3 + 2];
		if((a == v) || (b == v) || (c == v))
		    continue;
		double[] o = fnormal(a, b, c);
		double[] m = fnormal((a == u) ? v : a, (b == u) ? v : b, (c == u) ? v : c);
		double ol = Math.sqrt((o[0] * o[0]) + (o[1] * o[1]) + (o[2] * o[2]));
		double ml = Math.sqrt((m[0] * m[0]) + (m[1] * m[1]) + (m[2] * m[2]));
		if((ml <= 1e-12) || ((o[0] * m[0]) + (o[1] * m[1]) + (o[2] * m[2])) < (0.2 * ol * ml))
		    return(false);
	    }
	    return(true);
	}

	void fold(int u, int v, PriorityQueue<double[]> pq) {
	    Set<Integer> touched = new HashSet<>();
	    for(int t : new ArrayList<>(vtris.get(u))) {
		if(tdead[t])
		    continue;
		int a = tri[t * 3], b = tri[t * 3 + 1], c = tri[t * 3 + 2];
		if((a == v) || (b == v) || (c == v)) {
		    tdead[t] = true;
		    alive--;
		    for(int k = 0; k < 3; k++)
			vtris.get(tri[t * 3 + k]).remove((Integer)t);
		    continue;
		}
		for(int k = 0; k < 3; k++) {
		    if(tri[t * 3 + k] == u)
			tri[t * 3 + k] = v;
		    else
			touched.add(tri[t * 3 + k]);
		}
		vtris.get(v).add(t);
	    }
	    vtris.get(u).clear();
	    vdead[u] = true;
	    for(int i = 0; i < 10; i++)
		q[v * 10 + i] += q[u * 10 + i];
	    qw[v] += qw[u];
	    /* u's open edges are v's now, and so is its standing as a border vertex. */
	    Set<Long> moved = new HashSet<>();
	    for(Iterator<Long> i = bedges.iterator(); i.hasNext();) {
		long k = i.next();
		int a = (int)(k >>> 32), b = (int)(k & 0xffffffffL);
		if((a == u) || (b == u)) {
		    i.remove();
		    int o = (a == u) ? b : a;
		    if(o != v)
			moved.add(ekey(o, v));
		}
	    }
	    bedges.addAll(moved);
	    border[v] |= border[u];
	    locked[v] |= locked[u];
	    ver[v]++;
	    for(int t : vtris.get(v)) {
		for(int k = 0; k < 3; k++)
		    touched.add(tri[t * 3 + k]);
	    }
	    touched.remove(v);
	    /* Only v's own candidates went stale: its planes grew. Any other edge costs what it did, and whether
	     * it may still be folded is asked again when it comes up. */
	    for(int x : touched)
		push(pq, v, x);
	}

	/* The live triangles as original vertices: an unmoved corner keeps its own, a moved one takes the
	 * vertex at its new point whose normal is nearest the one the corner had. */
	short[] emit() {
	    short[] out = new short[alive * 3];
	    int o = 0;
	    for(int t = 0; t < nt; t++) {
		if(tdead[t])
		    continue;
		for(int k = 0; k < 3; k++) {
		    int orig = otri[t * 3 + k], w = tri[t * 3 + k];
		    int v = (wid[orig] == w) ? orig : wedge(w, orig);
		    out[o++] = (short)v;
		}
	    }
	    return(out);
	}

	int wedge(int w, int orig) {
	    List<Integer> ws = wedges.get(w);
	    if((n == null) || (ws.size() == 1))
		return(ws.get(0));
	    int best = ws.get(0);
	    double bd = Double.NEGATIVE_INFINITY;
	    for(int v : ws) {
		double d = (n[v * 3] * n[orig * 3]) + (n[v * 3 + 1] * n[orig * 3 + 1]) + (n[v * 3 + 2] * n[orig * 3 + 2]);
		if(d > bd) {
		    bd = d;
		    best = v;
		}
	    }
	    return(best);
	}
    }

    private static int find(int[] up, int v) {
	while(up[v] != v)
	    v = up[v] = up[up[v]];
	return(v);
    }

    private static void union(int[] up, int a, int b) {
	a = find(up, a); b = find(up, b);
	if(a != b)
	    up[b] = a;
    }
}
