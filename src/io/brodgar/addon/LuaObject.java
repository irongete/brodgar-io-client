package io.brodgar.addon;

import haven.Coord2d;

/**
 * A client-only world <b>3D object</b> (spec {@code 18-custom-models-gltf.md}, R3a) — the Java half of
 * {@code hafen.render.object{model=, x, y, ...}}. A {@link LuaWorldEntity} whose visual is a custom glTF model (an
 * addon's own {@code .glb}/{@code .gltf}, parsed to baked geometry by {@link Gltf} and loaded via
 * {@code hafen.render.model}) standing in the 3D world — the mesh sibling of a {@link LuaSprite} (a PNG quad) and
 * of a {@link LuaGhost} (a {@code .res} game model). Client-only ⇒ <b>SAFE-tier, NOT gated</b> (D-034): it never
 * reaches the server and grants no gameplay advantage.
 *
 * <p><b>Mesh visual (R3a).</b> The visual is a {@link MeshSprite} → {@code SprDrawable} — a resource-free
 * {@code Drawable} that adds one engine {@link haven.render.Model} per glTF primitive, each a flat, unlit
 * {@code baseColorFactor} surface. Because it is a real world gob with a {@code Drawable}, it gets the full
 * transform ({@code :move}/{@code :rotate}/{@code :scale}), look ({@code :alpha}/{@code :tint}), scene lifecycle,
 * and gizmo from the shared core for free (spec 17 §2). Textures, normals, and lighting are R3b/R3c.
 *
 * <p><b>Mesh ownership.</b> {@link #mesh} is the {@link LuaMesh} the object was built from; it is bridge-owned by
 * {@link Addon#meshes} (whether passed as a handle or auto-loaded from a path). The object does <b>not</b> free the
 * mesh on teardown — {@link MeshSprite#dispose()} frees only <i>this</i> object's own engine {@code Model}s, and
 * {@code teardownMeshes} frees the mesh handle. {@link #meshName} backs {@link #visualName()}
 * ({@code :mesh()} / the list string-filter).
 *
 * <p><b>Synchronous create.</b> Unlike a ghost (whose {@code res.get()} throws {@code Loading}), a model's geometry
 * is already decoded (by {@link Gltf}), so the gob + {@code MeshSprite} + {@code addClientGob} happen immediately
 * on the calling UI thread ({@link AddonManager}'s {@code newObject}); the handle's {@link #gob} is live before it
 * is returned. Teardown, ownership (P2), and the {@link #dead} no-op guard are inherited from {@link LuaWorldEntity}.
 */
public final class LuaObject extends LuaWorldEntity {
    final LuaMesh mesh;      // the geometry source (bridge-owned by Addon.meshes; NOT freed by the object)
    final String  meshName;  // the addon-relative model path, for :mesh() and the list string-filter

    LuaObject(Addon owner, LuaMesh mesh, Coord2d rc, double a) {
        super(owner, rc, a);
        this.mesh = mesh;
        this.meshName = mesh.name;
    }

    void unregister() { owner.objects.remove(this); }

    String visualName() { return meshName; }

    String clickEvent() { return "ObjectClicked"; }   // the object analog of a ghost's GhostClicked / a sprite's SpriteClicked
    String clickKey()   { return "object"; }
}
