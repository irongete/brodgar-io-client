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
public class AttrMats extends VarMats {
    public final Map<Integer, Material> mats;

    public AttrMats(Gob gob, Map<Integer, Material> mats) {
	super(gob);
	this.mats = mats;
    }

    public Material varmat(int id) {
	return(mats.get(id));
    }

    public static Map<Integer, Material> decode(Resource.Resolver rr, Message sdt) {
	Map<Integer, Material> ret = new IntMap<Material>();
	int idx = 0;
	while(!sdt.eom()) {
	    Indir<Resource> mres = rr.getres(sdt.uint16());
	    int mid = sdt.int8();
	    Material.Res mat;
	    if(mid >= 0)
		mat = mres.get().layer(Material.Res.class, mid);
	    else
		mat = mres.get().layer(Material.Res.class);
	    ret.put(idx++, mat.get());
	}
	return(ret);
    }

    public static void parse(Gob gob, Message dat) {
	gob.setattr(new AttrMats(gob, decode(gob.context(Resource.Resolver.class), dat)));
    }
}

/* >spr: VarSprite */
