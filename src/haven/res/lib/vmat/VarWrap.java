/* Preprocessed source code */
/* addon: a LOCAL COPY of the resource's own code (doc/resource-code), verbatim and unmodified but for
 * the @FromResource line get-code writes. It is here only because VarMats beside it is adopted and does
 * not compile without this type; the engine prefers a local copy only when the @FromResource name and
 * version match the resource actually served, and otherwise warns and uses the fetched code, so a
 * server-side bump degrades to the original behaviour rather than breaking. The reason the four are
 * here is on VarMats. */
package haven.res.lib.vmat;

import haven.*;
import haven.render.*;
import haven.ModSprite.*;
import java.util.*;
import java.util.function.Consumer;

@haven.FromResource(name = "lib/vmat", version = 39)
public class VarWrap extends Pipe.Op.Wrapping {
    public final int mid;

    public VarWrap(RenderTree.Node r, Pipe.Op st, int mid) {
	super(r, st, true);
	this.mid = mid;
    }

    public String toString() {
	return(String.format("#<vmat %s %s>", mid, op));
    }

    public static class Applier implements NodeWrap {
	public final Material mat;
	public final int mid;

	public Applier(Material mat, int mid) {
	    this.mat = mat;
	    this.mid = mid;
	}

	public VarWrap apply(RenderTree.Node node) {
	    return(new VarWrap(node, mat, mid));
	}
    }
}
