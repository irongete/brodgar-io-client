package io.brodgar.addon;

import haven.Coord2d;

/**
 * A client-only world <b>sprite</b> (spec {@code 17-custom-rendering.md}, R2) — the Java half of
 * {@code hafen.render.sprite{image=, x, y, ...}}. A {@link LuaWorldEntity} whose visual is a custom PNG (an
 * addon's own file, decoded to a {@link haven.TexI} by {@code hafen.render.image}) standing in the 3D world as a
 * textured quad — the non-{@code .res} sibling of a {@link LuaGhost}. Client-only ⇒ <b>SAFE-tier, NOT gated</b>
 * (D-034), like a HUD overlay or a ghost: it never reaches the server and grants no gameplay advantage.
 *
 * <p><b>Fixed quad (R2a).</b> The visual is a {@code TexI}-textured 4-vertex {@code TRIANGLE_STRIP} standing
 * upright at the entity's feet, facing {@link #a}, wrapped in a {@link SpriteQuad} → {@code SprDrawable} — a
 * <i>resource-free</i> {@code Drawable}. Because it is a real world gob with a {@code Drawable}, it gets the full
 * transform ({@code :move}/{@code :rotate}/{@code :scale}), look ({@code :alpha}/{@code :tint}), scene lifecycle,
 * and gizmo from the shared core for free (spec 17 §2). The camera-facing <b>billboard</b> form (R2b) will attach
 * a screen-space {@code Render2D} visual to this same entity instead.
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
    final boolean  billboard;      // R2b: true = camera-facing screen blit (LuaSpriteBillboard); false = fixed world quad (SpriteQuad)

    LuaSprite(Addon owner, LuaImage img, Coord2d rc, double a, boolean billboard) {
        super(owner, rc, a);
        this.img = img;
        this.imgName = img.name;
        this.billboard = billboard;
    }

    void unregister() { owner.sprites.remove(this); }

    String visualName() { return imgName; }

    String clickEvent() { return "SpriteClicked"; }   // R2b: the sprite analog of a ghost's GhostClicked
    String clickKey()   { return "sprite"; }
}
