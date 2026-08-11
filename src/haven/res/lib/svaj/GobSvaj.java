/* Preprocessed source code */
package haven.res.lib.svaj;

import haven.*;
import haven.render.*;
import haven.render.sl.*;
import static haven.render.sl.Cons.*;
import static haven.render.sl.Type.*;
import static haven.render.sl.Function.PDir.*;

/* rts: a LOCAL COPY of the resource's own code (doc/resource-code), with ONE line changed -- see st().
 *
 * This is the foliage sway. Its shader displaces each vertex by
 *
 *     charm.xyz * sin(t * charm.w) * max(length((in.xyz - s_orig.xyz).xy) - 5, 0)
 *
 * so the amplitude is proportional to the distance between the vertex and `s_orig`, which is what makes
 * a treetop sway and a trunk stand still. `s_orig` is the gob's own position, and the two must be
 * measured in the SAME space or that distance means nothing.
 *
 * A fleet member's objects are drawn in the ANCHOR's scene, under a translation of the offset between
 * the two sessions' coordinate frames -- so their vertices arrive here already translated while this
 * origin, taken from the gob's own session, is not. The subtraction then misses by exactly that offset,
 * and since the miss IS the amplitude, a tree hundreds of tiles' worth of offset away thrashes instead
 * of swaying. Only trees, because only they sway; only horizontally, because the translation has no z
 * component and the vertical term reads off.z; and only ever for a session that logged in on a
 * different grid, because that is exactly when the offset is non-zero.
 *
 * The engine prefers this copy only while the name and version below match the resource actually
 * served. If the server bumps it the copy is ignored with a warning and the sway goes back to being
 * wrong for merged sessions -- which is the safe direction, and the warning says so. */
@haven.FromResource(name = "lib/svaj", version = 25)
public class GobSvaj extends GAttrib implements Gob.SetupMod {
    public static final float v1 = 0.5f, v2 = 0.25f;
    public final Coord3f zhvec, chvec;
    public final float zhfreq, chfreq;

    private static float r(float a, float b) {
	return(a + ((float)Math.random() * (b - a)));
    }

    public GobSvaj(Gob gob, float v1, float v2) {
	super(gob);
	this.zhvec = new Coord3f(r(-0.05f * v1, 0.05f * v1), r(-0.05f * v1, 0.05f * v1), r(-0.01f * v1, 0.01f * v1));
	this.zhfreq = r(0.05f, 0.2f);
	this.chvec = new Coord3f(r(-0.02f * v2, 0.02f * v2), r(-0.02f * v2, 0.02f * v2), r(-0.03f * v2, 0.03f * v2));
	this.chfreq = r(0.5f, 1.5f);
    }

    public GobSvaj(Gob gob) {
	this(gob, v1, v2);
    }

    public static void parse(Gob gob, Message sdt) {
	float V1 = v1, V2 = v2;
	if(!sdt.eom()) {
	    int fl = sdt.uint8();
	    V1 = sdt.float8();
	    V2 = sdt.float8();
	}
	gob.setattr(new GobSvaj(gob, V1, V2));
    }

    private Svaj cur = null;
    private State st() {
	Coord3f origin;
	try {
	    origin = gob.getc();
	} catch(Loading l) {
	    return(cur);
	}
	origin.y = -origin.y;
	/* rts: the one changed line. Put the origin in the same scene space the vertices will arrive in.
	 * Null for the anchor's own objects and for a plain single-session client, where this is a
	 * no-op and the whole file behaves exactly as the fetched original does. */
	Coord2d off = io.brodgar.rts.Fleet.offsetfor(gob.glob.map);
	if(off != null) {
	    origin.x -= (float)off.x;
	    origin.y += (float)off.y;
	}
	if((cur == null) || !Utils.eq(origin, cur.origin)) {
	    cur = new Svaj(zhvec, zhfreq, chvec, chfreq, origin);
	}
	return(cur);
    }

    public Pipe.Op placestate() {
	return(st());
    }
}
