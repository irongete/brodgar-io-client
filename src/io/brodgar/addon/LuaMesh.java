package io.brodgar.addon;

import haven.TexI;

import org.luaj.vm2.LuaValue;

/**
 * A loaded, bridge-owned custom <b>3D model</b> (spec {@code 18-custom-models-gltf.md}, R3a) — the Java half of
 * {@code hafen.render.model(path)}. A <b>glTF 2.0 static mesh</b> ({@code .glb}/{@code .gltf}) read from the
 * addon's own folder and parsed by {@link Gltf} into baked, H&amp;H-local geometry — the mesh-data sibling of a
 * {@link LuaImage} (which wraps a PNG in a {@code TexI}). Because it is client-side geometry that never reaches the
 * server and grants no gameplay advantage, it is <b>SAFE-tier, NOT gated</b> (D-034) — like a world ghost.
 *
 * <p><b>Handle, not a ref.</b> A model has no server identity, so it is addressed by a bridge-owned <b>handle</b>
 * ({@link AddonManager#meshHandle}) exposing {@code :bounds()} &rarr; {@code {min={x,y,z}, max={x,y,z},
 * size={x,y,z}}} (world units) and {@code :dispose()}. The handle table carries this {@code LuaMesh} as an
 * <b>opaque userdata</b> (the {@link #KEY} field) so {@code hafen.render.object{model=…}} can {@link #resolve} it
 * back to the parsed {@link #mesh}. This is the same facade-safe opaque round-trip {@link LuaImage}/{@link
 * LuaMarshal} use (principle P1): the userdata has no metatable, so no Java method is reachable from Lua, and it
 * cannot be forged (the sandbox omits {@code luajava}).
 *
 * <p><b>Ownership (P2) / GPU.</b> Bridge-owned: it lives only in the addon's registry ({@link Addon#meshes}). The
 * parsed {@link Gltf} geometry is pure CPU data — each {@link LuaObject} builds its <b>own</b> engine
 * {@link haven.render.Model}s from it ({@link MeshSprite}). In R3b the mesh also owns the <b>shared base-colour
 * textures</b> ({@link #textures} — one {@link TexI} per referenced glTF image, decoded once and referenced by every
 * primitive/object that uses it): these are the first GPU state a mesh holds, so {@code :dispose()} / teardown
 * ({@link RenderApi#teardownMeshes}) now disposes each {@code TexI} in addition to marking it {@link #dead} (a
 * later {@code render.object} on a disposed handle errors). Teardown order guarantees safety: {@code teardownObjects}
 * (frees each object's own {@code Model}s) runs <b>before</b> {@code teardownMeshes} (frees the shared textures), so a
 * live object never references a freed texture. (Consequently, unlike R3a, calling {@code mesh:dispose()} <b>while an
 * object still uses it</b> frees the shared textures out from under that object — dispose a mesh only when no live
 * object draws it; teardown always does this in the right order.)
 */
public final class LuaMesh {
    /** The handle-table field carrying this object as an opaque userdata (read by {@link #resolve}). */
    static final LuaValue KEY = LuaValue.valueOf("__mesh");

    final Addon  owner;
    final String name;      // the addon-relative path — for load-dedup, the error text, and the list filter
    final Gltf   mesh;      // the parsed, baked (H&H-local) geometry; MeshSprite builds engine Models per object
    final TexI[] textures;  // R3b: the shared base-colour textures, indexed by Gltf.Prim.texImage (empty if untextured); freed in disposeMesh
    volatile boolean dead;  // disposed/torn down → render.object refuses it
    LuaValue handle;        // the stable Lua handle table (so a re-load of the same path returns the same one)

    LuaMesh(Addon owner, String name, Gltf mesh, TexI[] textures) {
        this.owner = owner;
        this.name = name;
        this.mesh = mesh;
        this.textures = textures;
    }

    /**
     * Resolve a Lua value passed to {@code hafen.render.object{model=…}} back to its {@link LuaMesh}: the
     * {@code hafen.render.model} handle table (via its {@link #KEY} userdata field) or the raw backing userdata
     * itself; returns {@code null} for anything else (a nil/typo/foreign value).
     */
    static LuaMesh resolve(LuaValue v) {
        if(v == null)
            return null;
        LuaValue u = v.istable() ? v.get(KEY) : v;
        if(u.isuserdata()) {
            Object o = u.touserdata();
            if(o instanceof LuaMesh)
                return (LuaMesh)o;
        }
        return null;
    }
}
