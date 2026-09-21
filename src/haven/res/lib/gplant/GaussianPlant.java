/* Preprocessed source code */
package haven.res.lib.gplant;

import haven.*;
import haven.render.*;
import haven.resutil.*;
import java.util.*;
import java.util.function.*;
import haven.Sprite.Owner;

/* >spr: GaussianPlant */
/* rts: a LOCAL COPY of the resource's own code (doc/resource-code), adopted for ONE change -- see
 * create(): the number of sprouts drawn is scaled by the Performance panel's forage setting
 * (io.brodgar.perf.Performance.sprouts), so a player on a weak machine draws fewer parts per plant.
 * At 100 this is upstream's code path exactly. The engine prefers this copy only while the name and
 * version below match the resource actually served; on a server-side bump it is ignored with a
 * warning and the served code draws the full plant, the setting inert (spec 160-performance, 160.5). */
@haven.FromResource(name = "lib/gplant", version = 1)
public class GaussianPlant implements Sprite.Factory {
    public final int numl, numh;
    public final float r;
    public final List<Collection<Function<Owner, RenderTree.Node>>> var;

    public GaussianPlant(Resource res, int numl, int numh, float r) {
	this.numl = numl;
	this.numh = numh;
	this.r = r;
	Map<Integer, Collection<Function<Owner, RenderTree.Node>>> vars = new HashMap<>();
	for(FastMesh.MeshRes mr : res.layers(FastMesh.MeshRes.class)) {
	    RenderTree.Node w = mr.mat.get().apply(mr.m);
	    vars.computeIfAbsent(mr.id, k -> new ArrayList<>()).add(o -> w);
	}
	for(RenderLink.Res lr : res.layers(RenderLink.Res.class)) {
	    vars.computeIfAbsent(lr.id, k -> new ArrayList<>()).add(lr.l::make);
	}
	this.var = new ArrayList<>(vars.values());
    }

    public GaussianPlant(Resource res, Object[] args) {
	this(res, ((Number)args[0]).intValue(), ((Number)args[1]).intValue(), ((Number)args[2]).floatValue());
    }

    public Sprite create(Sprite.Owner owner, Resource res, Message sdt) {
	Random rnd = owner.mkrandoom();
	CSprite spr = new CSprite(owner, res);
	int num = rnd.nextInt(numh - numl + 1) + numl;
	int drawn = io.brodgar.perf.Performance.sprouts(num, io.brodgar.perf.Performance.forage);   // addon: 160.5
	for(int i = 0; i < drawn; i++) {
	    float x = (float)rnd.nextGaussian() * r, y = (float)rnd.nextGaussian() * r;
	    for(Function<Owner, RenderTree.Node> mk : var.get(rnd.nextInt(var.size())))
		spr.addpart(x, y, Pipe.Op.nil, mk.apply(owner));
	}
	return(spr);
    }
}
