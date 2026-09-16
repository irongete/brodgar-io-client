/* Preprocessed source code */
/* addon: a LOCAL COPY of the resource's own code (doc/resource-code), verbatim and unmodified but for
 * the @FromResource line get-code writes. It is here so that the addon bridge can read which material
 * the server dressed each slot of an object in -- gob:materials() -- through the resource's OWN attribute
 * (gob.getattr(VarMats.class), an AttrMats whose `mats` map is the server's dressing) rather than by
 * decoding the OD_RESATTR bytes itself. The three classes beside it (AttrMats, VarWrap, VarSprite) are the
 * rest of the same resource, copied because this one does not compile without them.
 *
 * The engine prefers a local copy only when the @FromResource name and version match the resource
 * actually served, and otherwise warns and uses the fetched code -- so a server-side bump degrades to
 * "no object has slots" rather than breaking. Nothing here is modified; the readers that call it are
 * io.brodgar.addon.LuaMaterials and io.brodgar.addon.LuaMaterialSlot. */
package haven.res.lib.vmat;

import haven.*;
import haven.render.*;
import haven.ModSprite.*;
import java.util.*;
import java.util.function.Consumer;

@haven.FromResource(name = "lib/vmat", version = 39)
public abstract class VarMats extends GAttrib implements Mod {
    public VarMats(Gob gob) {
	super(gob);
    }

    public abstract Material varmat(int id);

    public void operate(Cons cons) {
	for(Part part : cons.parts) {
	    if(part.obj instanceof FastMesh.ResourceMesh) {
		FastMesh.ResourceMesh m = (FastMesh.ResourceMesh)part.obj;
		String sid = m.info.rdat.get("vm");
		int mid = (sid == null) ? -1 : Integer.parseInt(sid);
		if(mid >= 0) {
		    Material vm = varmat(mid);
		    if(vm != null)
			part.wraps.addFirst(new VarWrap.Applier(vm, mid));
		}
	    }
	}
    }

    public int order() {return(100);}
}

/* >objdelta: AttrMats */
