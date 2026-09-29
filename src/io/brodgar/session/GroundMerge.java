package io.brodgar.session;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.*;

import haven.*;
import haven.render.*;

/**
 * The remembered ground of one grid, drawn as one mesh per material rather than one per material per cut.
 *
 * <p>A cut ({@link MapMesh}) is 25 by 25 tiles and holds a mesh for every material its tiles are laid in, a
 * few hundred triangles each; the remembered ground puts hundreds of cuts on screen, and every one of those
 * meshes is a draw call of its own on the one thread that talks to GL. This concatenates the meshes of a
 * grid's cuts that share a material and a vertex layout into one, each cut's positions moved by where that
 * cut stands in the grid, so what was a draw call per material per cut is one per material per grid. The
 * cuts themselves are untouched and stay the source's: what is built here is a mesh of its own, owned and
 * disposed by the {@link Node} that draws it, which <b>keeps no copy</b> of the cuts' data. Its vertices and
 * indices are written from the cuts' own meshes straight into the buffers the GPU is filled from, when it is
 * uploaded ({@link Joined}, {@link #indices}), and again if another GL environment ever asks: a copy kept here
 * would be the whole of the ground's data held twice, once on the GPU that draws it and once on the heap for
 * nothing.
 *
 * <p>Two meshes are merged only when their materials are equal (the key a cut itself groups its tiles by,
 * {@code MapMesh.Model.MatKey}) and their vertex buffers carry the same attributes in the same formats. A
 * mesh past 65,535 vertices in total starts another -- the indices are 16 bits -- and a mesh whose buffers
 * this cannot read is drawn as it was, from the cut's own mesh, moved to where its cut stands.
 */
public final class GroundMerge {
    private static final int MAXVERT = 65535;

    private GroundMerge() {}

    /** One cut of the grid: its mesh and where it stands, in the grid's own coordinates. */
    public static final class Cut {
	public final MapMesh mesh;
	public final float dx, dy;

	public Cut(MapMesh mesh, float dx, float dy) {
	    this.mesh = mesh;
	    this.dx = dx;
	    this.dy = dy;
	}
    }

    /** What one grid draws, in the grid's own coordinates. Disposing it disposes only what it built. */
    public static final class Node implements RenderTree.Node, Disposable {
	private final List<RenderTree.Node> parts = new ArrayList<>();
	private final List<FastMesh> owned = new ArrayList<>();
	/** How many meshes of the cuts went into it, and how many it draws. */
	public int nsource, ndrawn;

	public void added(RenderTree.Slot slot) {
	    for(RenderTree.Node p : parts)
		slot.add(p);
	}

	public void dispose() {
	    for(FastMesh m : owned)
		m.dispose();
	    owned.clear();
	}
    }

    private static final class Piece {
	final FastMesh mesh;
	final float dx, dy;

	Piece(FastMesh mesh, float dx, float dy) {
	    this.mesh = mesh;
	    this.dx = dx;
	    this.dy = dy;
	}
    }

    private static final class Key {
	final NodeWrap mat;
	final String layout;
	final int hash;

	Key(NodeWrap mat, String layout) {
	    this.mat = mat;
	    this.layout = layout;
	    this.hash = (mat.hashCode() * 31) + layout.hashCode();
	}

	public int hashCode() {return(hash);}

	public boolean equals(Object o) {
	    if(!(o instanceof Key))
		return(false);
	    Key that = (Key)o;
	    return(this.mat.equals(that.mat) && this.layout.equals(that.layout));
	}
    }

    /* The vertex layout, or null when a buffer is of a kind this cannot read. */
    private static String layout(VertexBuf vb) {
	StringBuilder buf = new StringBuilder();
	for(VertexBuf.AttribData a : vb.bufs) {
	    if(!(a instanceof VertexBuf.FloatData) && !(a instanceof VertexBuf.IntData))
		return(null);
	    buf.append(a.getClass().getName()).append('/')
		.append(System.identityHashCode(a.attr)).append('/')
		.append(a.elfmt.nc).append((a instanceof VertexBuf.FloatData) ? 'f' : 'i').append(';');
	}
	return(buf.toString());
    }

    /** Builds the node for one grid's cuts. */
    public static Node merge(List<Cut> cuts) {
	Node ret = new Node();
	Map<Key, List<Piece>> groups = new LinkedHashMap<>();
	for(Cut c : cuts) {
	    for(MapMesh.Part mp : c.mesh.parts()) {
		FastMesh fm = mp.mesh;
		ret.nsource++;
		String lay = layout(fm.vert);
		if(lay == null) {
		    /* Not readable: the cut's own mesh, where the cut stands. */
		    ret.parts.add(xlated(mp.mat.apply(fm), c.dx, c.dy));
		    ret.ndrawn++;
		    continue;
		}
		groups.computeIfAbsent(new Key(mp.mat, lay), k -> new ArrayList<>()).add(new Piece(fm, c.dx, c.dy));
	    }
	}
	for(Map.Entry<Key, List<Piece>> e : groups.entrySet()) {
	    List<Piece> ps = e.getValue();
	    int from = 0;
	    while(from < ps.size()) {
		int to = from, nv = 0;
		while((to < ps.size()) && ((to == from) || (nv + ps.get(to).mesh.vert.num <= MAXVERT))) {
		    nv += ps.get(to).mesh.vert.num;
		    to++;
		}
		FastMesh m = concat(ps.subList(from, to), nv);
		ret.owned.add(m);
		ret.parts.add(e.getKey().mat.apply(m));
		ret.ndrawn++;
		from = to;
	    }
	}
	return(ret);
    }

    private static RenderTree.Node xlated(RenderTree.Node n, float dx, float dy) {
	Pipe.Op loc = Location.xlate(new Coord3f(dx, dy, 0));
	return(new RenderTree.Node() {
		public void added(RenderTree.Slot slot) {
		    slot.add(n, loc);
		}
	    });
    }

    /* The pieces as one mesh that keeps nothing of theirs: each attribute a Joined over the pieces' own buffers,
     * the indices written from theirs as they are uploaded, and the box around their positions taken here,
     * since the mesh has nothing to take it from later. */
    private static FastMesh concat(List<Piece> ps, int nv) {
	Piece[] pa = ps.toArray(new Piece[0]);
	VertexBuf.AttribData[] proto = pa[0].mesh.vert.bufs;
	VertexBuf.AttribData[] out = new VertexBuf.AttribData[proto.length];
	for(int a = 0; a < proto.length; a++)
	    out[a] = new Joined(proto[a], pa, a, nv);
	int ni = 0;
	for(Piece p : pa)
	    ni += p.mesh.indb.capacity();
	return(new FastMesh(new VertexBuf(out), ni / 3, indices(pa), bounds(pa)));
    }

    /**
     * One attribute of a merged mesh: the pieces' own buffers of it, one after another, positions moved to where
     * each piece's cut stands in the grid, written straight into the buffer the environment fills
     * ({@code VertexBuf.fill}) and kept nowhere. The pieces are the cuts' own meshes, which every merge of the
     * grid reads anyway, and a cut's mesh never changes once built: a cut built again is a mesh of its own, and
     * the grid is merged again for it.
     */
    private static final class Joined extends VertexBuf.AttribData {
	final Piece[] ps;
	final int a, num;
	final boolean pos;

	Joined(VertexBuf.AttribData proto, Piece[] ps, int a, int num) {
	    super(proto.attr, proto.elfmt);
	    this.ps = ps;
	    this.a = a;
	    this.num = num;
	    this.pos = (proto instanceof VertexBuf.FloatData) && (proto.attr == Homo3D.vertex) && (proto.elfmt.nc == 3);
	}

	public int size() {return(num);}

	public void data(ByteBuffer dst, int offset, int stride) {
	    int nc = elfmt.nc, o = offset;
	    for(Piece p : ps) {
		VertexBuf.AttribData src = p.mesh.vert.bufs[a];
		int n = p.mesh.vert.num;
		if(src instanceof VertexBuf.IntData) {
		    IntBuffer d = ((VertexBuf.IntData)src).data;
		    for(int v = 0, i = 0; v < n; v++, o += stride) {
			for(int e = 0; e < nc; e++, i++)
			    dst.putInt(o + (e * 4), d.get(i));
		    }
		} else {
		    FloatBuffer d = ((VertexBuf.FloatData)src).data;
		    for(int v = 0, i = 0; v < n; v++, o += stride) {
			for(int e = 0; e < nc; e++, i++) {
			    float f = d.get(i);
			    if(pos && (e < 2))
				f += (e == 0) ? p.dx : p.dy;
			    dst.putFloat(o + (e * 4), f);
			}
		    }
		}
	    }
	}
    }

    /* The pieces' indices, each moved past the vertices of the pieces before it, written straight into the buffer
     * the environment fills -- the first time it asks, and again if another one ever does. */
    private static DataBuffer.Filler<haven.render.Model.Indices> indices(Piece[] ps) {
	return((ibuf, env) -> {
		FillBuffer dst = env.fillbuf(ibuf);
		ShortBuffer out = dst.push().asShortBuffer();
		int base = 0;
		for(Piece p : ps) {
		    ShortBuffer src = p.mesh.indb;
		    for(int i = 0, n = src.capacity(); i < n; i++)
			out.put((short)((src.get(i) & 0xffff) + base));
		    base += p.mesh.vert.num;
		}
		return(dst);
	    });
    }

    /* The box around every position of the pieces, where each stands in the grid: what the frustum test takes
     * the merged mesh's place from (FastMesh.bounds). */
    private static Volume3f bounds(Piece[] ps) {
	float nx = Float.POSITIVE_INFINITY, ny = nx, nz = nx;
	float px = Float.NEGATIVE_INFINITY, py = px, pz = px;
	for(Piece p : ps) {
	    FloatBuffer d = p.mesh.vert.buf(VertexBuf.VertexData.class).data;
	    for(int i = 0, n = p.mesh.vert.num * 3; i < n; i += 3) {
		float x = d.get(i) + p.dx, y = d.get(i + 1) + p.dy, z = d.get(i + 2);
		nx = Math.min(nx, x); px = Math.max(px, x);
		ny = Math.min(ny, y); py = Math.max(py, y);
		nz = Math.min(nz, z); pz = Math.max(pz, z);
	    }
	}
	return(Volume3f.corn(Coord3f.of(nx, ny, nz), Coord3f.of(px, py, pz)));
    }
}
