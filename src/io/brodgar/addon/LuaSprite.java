package io.brodgar.addon;

import haven.Coord2d;

/**
 * A client-only world <b>sprite</b> (spec {@code 17-custom-rendering.md}, R2) — the Java half of
 * {@code hafen.vr():sprite():add(asset, p)}. A {@link LuaWorldEntity} whose visual is a custom PNG (an
 * addon's own file, decoded to a {@link haven.TexI} by {@code hafen.asset}) standing in the 3D world as a
 * textured quad — the non-{@code .res} sibling of a {@link LuaGhost}. Client-only ⇒ <b>SAFE-tier, NOT gated</b>
 * (D-034), like a HUD overlay or a ghost: it never reaches the server and grants no gameplay advantage.
 *
 * <p><b>Fixed quad (R2a).</b> The visual is a {@code TexI}-textured 4-vertex {@code TRIANGLE_STRIP} standing
 * upright at the entity's feet, facing {@link #a}, wrapped in a {@link SpriteQuad} → {@code SprDrawable} — a
 * <i>resource-free</i> {@code Drawable}. Because it is a real world gob with a {@code Drawable}, it gets the full
 * transform ({@code :move}/{@code :rotate}/{@code :scale}), look ({@code :alpha}/{@code :tint}), scene lifecycle,
 * and gizmo from the shared core for free (spec 17 §2). The {@code "screen"} facing (R2b) attaches a screen-space
 * {@code Render2D} visual to this same entity instead.
 *
 * <p><b>Image ownership.</b> {@link #img} is the {@link LuaImage} the quad samples; it is bridge-owned by
 * {@link Addon#images} (whether passed as a handle or auto-loaded from a path), so the sprite <b>does not</b>
 * dispose the {@code TexI} on teardown — {@link SpriteQuad#dispose()} frees only the quad geometry, and
 * {@code teardownImages} frees the shared texture. {@link #imgName} backs {@link #visualName()} ({@code :image()}).
 *
 * <p><b>Synchronous create.</b> Unlike a ghost (whose {@code res.get()} throws {@code Loading}), a sprite's
 * texture is already decoded, so the gob + quad + {@code addClientGob} happen immediately on the calling UI
 * thread ({@link AddonManager}'s {@code newSprite}); the handle's {@link #gob} is live before it is returned.
 * Teardown, ownership (P2), and the {@link #dead} no-op guard are all inherited from {@link LuaWorldEntity}.
 */
public final class LuaSprite extends LuaWorldEntity {
    final LuaImage img;            // the texture source (bridge-owned by Addon.images; NOT disposed by the sprite)
    final String   imgName;        // the addon-relative image path, for :image() and the list string-filter

    LuaSprite(Addon owner, LuaImage img, Coord2d rc, double a, String facing) {
        super(owner, rc, a);
        this.img = img;
        this.imgName = img.name;
        this.facing = facing;      // the shared core's field: the three modes are one vocabulary (044.3)
    }

    void unregister() { owner.sprites.remove(this); }

    /**
     * A sprite's three visuals, from the one image: an upright world quad at its own facing, the same quad
     * turned to the screen plane ({@link CameraFacing}, 044.3), or a constant-size screen blit
     * ({@link LuaSpriteBillboard}). The first two are world geometry and keep the sprite's world size; the
     * third gives that up for constant pixels.
     */
    haven.Drawable visual(haven.Gob gob, String mode) {
        if(VrApi.SCREEN.equals(mode))
            return new LuaSpriteBillboard(gob, img);
        float[] wh = VrApi.spriteWorldDims(img.sz);
        haven.Sprite.Mill<SpriteQuad> mill = SpriteQuad.mill(img.tex, wh[0], wh[1]);
        return VrApi.CAMERA.equals(mode) ? new CameraFacing(gob, mill) : new haven.SprDrawable(gob, mill);
    }

    String visualName() { return imgName; }

    String clickEvent() { return "SpriteClicked"; }   // R2b: the sprite analog of a ghost's GhostClicked
    String kind()       { return "sprite"; }          // the hafen.vr() collection this one belongs to
}
