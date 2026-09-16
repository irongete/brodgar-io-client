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
public class VarSprite extends ModSprite {
    public VarSprite(Owner owner, Resource res, Message sdt) {
	super(owner, res, sdt);
    }
}
