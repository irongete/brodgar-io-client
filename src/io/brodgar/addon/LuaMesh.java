package io.brodgar.addon;

import haven.TexI;

import org.luaj.vm2.LuaValue;

/**
 * A loaded, bridge-owned custom <b>3D model</b> (spec {@code 18-custom-models-gltf.md}, R3a) — the Java half of
 * {@code hafen.asset("chair.glb")} (028.1; was {@code hafen.render.model}). A <b>glTF 2.0 static mesh</b> ({@code .glb}/{@code .gltf}) read from the
 * addon's own folder and parsed by {@link Gltf} into baked, H&amp;H-local geometry — the mesh-data sibling of a
 * {@link LuaImage} (which wraps a PNG in a {@code TexI}). Because it is client-side geometry that never reaches the
 * server and grants no gameplay advantage, it is <b>SAFE-tier, NOT protected</b> (D-034) — like a world ghost.
 *
 * <p><b>Handle, not a ref.</b> A model has no server identity, so it is addressed by a bridge-owned <b>handle</b>:
 * this object itself, crossing into Lua as {@code LuaValue.userdataOf(this, mt)} with this addon's
 * {@link AssetApi.Kind#MESH} metatable, exposing the shared asset verbs, {@code :bounds()} &rarr;
 * {@code {min={x,y,z}, max={x,y,z}, extent={x,y,z}}} (world units) and {@code :info()}.
 * {@code hafen.vr():object():add(asset, p)} {@link #resolve}s the value back to the parsed {@link #mesh}. Same
 * facade-safe round-trip as {@link LuaImage} (principle P1): no Java method is reachable through the
 * vocabulary, the value cannot be written to from Lua, and it cannot be forged (the sandbox omits
 * {@code luajava}).
 *
 * <p><b>Ownership (P2) / GPU.</b> Bridge-owned: it lives only in the addon's registry ({@link Addon#meshes}). The
 * parsed {@link Gltf} geometry is pure CPU data — each {@link LuaObject} builds its <b>own</b> engine
 * {@link haven.render.Model}s from it ({@link MeshSprite}). In R3b the mesh also owns the <b>shared base-colour
 * textures</b> ({@link #textures} — one {@link TexI} per referenced glTF image, decoded once and referenced by every
 * primitive/object that uses it): these are the first GPU state a mesh holds, so a remove / teardown
 * ({@link AssetApi#teardownAssets}) now disposes each {@code TexI} in addition to marking it {@link #dead} (a
 * later {@code render.object} on a disposed handle errors). Teardown order guarantees safety: {@code teardownObjects}
 * (frees each object's own {@code Model}s) runs <b>before</b> {@code teardownMeshes} (frees the shared textures), so a
 * live object never references a freed texture.
 *
 * <p><b>What a manual {@code hafen.asset():remove(mdl)} under a live object actually does</b> (measured 028.2 — the earlier
 * R3b note predicted the opposite and was wrong): the object keeps drawing, <b>textured and unchanged</b>. It
 * never re-reads the {@link TexI}: {@link MeshSprite#texRender} captures {@code tex.st().data} <b>once</b>, at
 * mill time, into the material state, so nothing is pulled out from under it. What is lost is the <i>freeing</i>
 * — the sampler is still referenced by that live object, so its GPU memory is not reclaimed until the object is
 * destroyed, and the handle is {@link #dead} so the mesh can never be stood again. That is what makes the
 * teardown order load-bearing: objects first means nothing holds a sampler when the {@code TexI} goes. Still:
 * dispose a mesh only when no live object draws it — it buys nothing while one does.
 */
public final class LuaMesh implements AssetApi.Loaded {
    final Addon  owner;
    final String name;      // the addon-relative path — for load-dedup, the error text, and the list filter
    final Gltf   mesh;      // the parsed, baked (H&H-local) geometry; MeshSprite builds engine Models per object
    final TexI[] textures;  // R3b: the shared base-colour textures, indexed by Gltf.Prim.texImage (empty if untextured); freed in disposeMesh
    volatile boolean dead;  // disposed/torn down → render.object refuses it
    LuaValue handle;        // the stable Lua handle (so a re-load of the same path returns the same one)
    /** What the shared asset verbs answer for this model, and how it frees itself. On the record, because the
     *  metatable that reads it is shared by every mesh this addon holds. */
    AssetApi.Asset asset;

    LuaMesh(Addon owner, String name, Gltf mesh, TexI[] textures) {
        this.owner = owner;
        this.name = name;
        this.mesh = mesh;
        this.textures = textures;
    }

    public AssetApi.Asset asset() {
        return asset;
    }

    /**
     * Resolve a Lua value passed to {@code hafen.vr():object():add(asset, p)} back to its {@link LuaMesh}: a
     * mesh handle. {@code null} for anything else (a nil, a typo, a foreign value — a hand-built table
     * included).
     */
    static LuaMesh resolve(LuaValue v) {
        if((v == null) || !v.isuserdata())
            return null;
        Object o = v.touserdata();
        return (o instanceof LuaMesh) ? (LuaMesh)o : null;
    }
}
