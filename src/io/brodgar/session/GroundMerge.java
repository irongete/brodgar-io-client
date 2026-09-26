package io.brodgar.session;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
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
 * cuts themselves are untouched and stay the source's: what is built here is a copy, owned and disposed by
 * the {@link Node} that draws it.
 *
 * <p>Two meshes are merged only when their materials are equal (the key a cut itself groups its tiles by,
 * {@code MapMesh.Model.MatKey}) and their vertex buffers carry the same attributes in the same formats. A
 * mesh past 65,535 vertices in total starts another -- the indices are 16 bits -- and a mesh whose buffers
 * this cannot copy is drawn as it was, from the cut's own mesh, moved to where its cut stands.
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

    /* The vertex layout, or null when a buffer is of a kind this cannot copy. */
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
	    for(MapMesh.Model mod : c.mesh.models()) {
		FastMesh fm = mod.mesh;
		if(fm == null)
		    continue;
		ret.nsource++;
		String lay = layout(fm.vert);
		if(lay == null) {
		    /* Not copyable: the cut's own mesh, where the cut stands. */
		    ret.parts.add(xlated(mod.mat.apply(fm), c.dx, c.dy));
		    ret.ndrawn++;
		    continue;
		}
		groups.computeIfAbsent(new Key(mod.mat, lay), k -> new ArrayList<>()).add(new Piece(fm, c.dx, c.dy));
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

    private static FastMesh concat(List<Piece> ps, int nv) {
	VertexBuf.AttribData[] proto = ps.get(0).mesh.vert.bufs;
	VertexBuf.AttribData[] out = new VertexBuf.AttribData[proto.length];
	for(int a = 0; a < proto.length; a++) {
	    VertexBuf.AttribData pa = proto[a];
	    int nc = pa.elfmt.nc;
	    if(pa instanceof VertexBuf.FloatData) {
		float[] dst = new float[nv * nc];
		boolean pos = (pa.attr == Homo3D.vertex) && (nc == 3);
		int o = 0;
		for(Piece p : ps) {
		    FloatBuffer src = ((VertexBuf.FloatData)p.mesh.vert.bufs[a]).data;
		    int n = p.mesh.vert.num * nc;
		    for(int i = 0; i < n; i++)
			dst[o + i] = src.get(i);
		    if(pos) {
			for(int i = 0; i < n; i += 3) {
			    dst[o + i] += p.dx;
			    dst[o + i + 1] += p.dy;
			}
		    }
		    o += n;
		}
		out[a] = floatdata(pa, FloatBuffer.wrap(dst));
	    } else {
		int[] dst = new int[nv * nc];
		int o = 0;
		for(Piece p : ps) {
		    IntBuffer src = ((VertexBuf.IntData)p.mesh.vert.bufs[a]).data;
		    int n = p.mesh.vert.num * nc;
		    for(int i = 0; i < n; i++)
			dst[o + i] = src.get(i);
		    o += n;
		}
		out[a] = new VertexBuf.IntData(pa.attr, nc, IntBuffer.wrap(dst)) {};
	    }
	}
	int ni = 0;
	for(Piece p : ps)
	    ni += p.mesh.indb.capacity();
	short[] idx = new short[ni];
	int o = 0, base = 0;
	for(Piece p : ps) {
	    int n = p.mesh.indb.capacity();
	    for(int i = 0; i < n; i++)
		idx[o + i] = (short)((p.mesh.indb.get(i) & 0xffff) + base);
	    o += n;
	    base += p.mesh.vert.num;
	}
	return(new FastMesh(new VertexBuf(out), idx));
    }

    /* The same kind of buffer as the one it copies, where that kind has the one-buffer constructor the
     * standard ones do; any other float layer keeps its attribute and format. */
    private static VertexBuf.AttribData floatdata(VertexBuf.AttribData proto, FloatBuffer data) {
	Class<?> cl = proto.getClass();
	if(cl == VertexBuf.VertexData.class)  return(new VertexBuf.VertexData(data));
	if(cl == VertexBuf.NormalData.class)  return(new VertexBuf.NormalData(data));
	if(cl == VertexBuf.TexelData.class)   return(new VertexBuf.TexelData(data));
	if(cl == VertexBuf.ColorData.class)   return(new VertexBuf.ColorData(data));
	return(new VertexBuf.FloatData(proto.attr, proto.elfmt.nc, data) {});
    }
}
