package io.brodgar.addon;

import java.awt.Color;

import haven.Area;
import haven.Coord;
import haven.Coord3f;
import haven.Drawable;
import haven.GOut;
import haven.Gob;
import haven.PView;
import haven.Resource;
import haven.UI;
import haven.render.Homo3D;
import haven.render.Pipe;

/**
 * The <b>camera-facing billboard</b> visual behind a {@link LuaSprite} ({@code hafen.vr():sprite() with :facing("screen")},
 * spec {@code 17-custom-rendering.md} §5, R2b) — a custom PNG standing in the world that always faces the camera,
 * the odd sibling of the fixed {@link SpriteQuad}. It is a screen-space blit anchored at the entity's projected
 * world point, exactly the {@code haven.SpeakerIcon} / {@link LuaGobOverlay} pattern: a {@link PView.Render2D}
 * node whose {@code draw(GOut, Pipe)} projects the gob's origin to the screen (via {@link Homo3D#obj2view}) and
 * blits the {@link haven.TexI} there. Because the gob's {@code Placed} slot supplies the world transform, moving
 * the entity (or a followed gob) moves the anchor — so <b>position and gizmo-move apply</b>; <b>world-rotate and
 * world-scale do not</b> (it is a flat 2D image, always squarely facing the viewer). It draws in the 2D overlay
 * pass, <b>on top</b> of the 3D scene (no depth test), and is <b>screen-sized</b> (constant pixels at any zoom).
 *
 * <p><b>Why a {@link Drawable}, not a bare {@code GAttrib}.</b> Unlike {@code SpeakerIcon}/{@code LuaGobOverlay}
 * (which sit <i>beside</i> a gob's real visual), a billboard <b>is</b> the entity's only visual, so it is attached
 * as the gob's {@code Drawable} — a resource-free one ({@link #getres()} {@code == null}, like {@link haven.SprDrawable}).
 * A {@code Drawable} is a {@link haven.render.RenderTree.Node}, so {@code Gob.added} adds it under the {@code Placed}
 * world-transform and the render tree's 2D pass picks up its {@link PView.Render2D}. Being a {@code Drawable} also
 * keeps {@code getattr(Drawable.class) != null}, so the gob is <b>not</b> mistaken for an empty virtual gob and
 * garbage-collected out of the scene ({@code Gob.ctick}'s {@code virtual && no-drawable} cleanup).
 *
 * <p><b>Look.</b> The billboard reads the {@link GhostGob}'s live look fields itself (the {@code obstate}
 * render-state recipe applies to a 3D mesh, of which a billboard has none): {@link GhostGob#alpha} → the blit's
 * opacity, {@link GhostGob#tint} → a colour multiply (lerped toward the tint by its alpha = blend strength, the
 * closest {@code chcolor} analogue of a ghost's {@code MixColor}), and {@link GhostGob#scale} → a screen-size
 * multiplier (so {@code :scale}/the gizmo scale-box resize it on screen). It is anchored <b>bottom-centre</b> at
 * the world point, so it "stands" there like a fixed sprite; a {@code follow} offset ({@code z} up) floats it.
 *
 * <p><b>Ownership.</b> The {@link haven.TexI} belongs to the {@link LuaImage} handle ({@link Addon#images}); this
 * visual only references it, so {@link #dispose()} is a no-op and {@code teardownImages} frees the texture. A
 * disposed image ({@link LuaImage#dead}) blits nothing. Never throws into the render pass (the projection is
 * guarded, mirroring {@link LuaGobOverlay}).
 */
public final class LuaSpriteBillboard extends Drawable implements PView.Render2D {
    private final LuaImage img;      // the texture source (bridge-owned by Addon.images; NOT disposed here)
    private final GhostGob gg;       // the virtual gob whose live look fields (alpha/tint/scale) we read, or null

    LuaSpriteBillboard(Gob gob, LuaImage img) {
        super(gob);
        this.img = img;
        this.gg = (gob instanceof GhostGob) ? (GhostGob)gob : null;
    }

    /** Resource-free (like {@link haven.SprDrawable}) — no backing {@code .res}. */
    public Resource getres() {
        return null;
    }

    /** The shared texture is owned by the {@link LuaImage}; nothing to free here (see {@code teardownImages}). */
    public void dispose() {
    }

    /**
     * The 2D overlay pass (once per frame, UI thread, in the same {@code UI.draw} traversal as every widget): project
     * the gob's origin {@code (0,0,0)} — its placed world point — to the screen and blit the image bottom-centred
     * there, screen-sized, with the entity's live opacity/tint. {@code state} carries the {@code Placed} world
     * transform, so a moved (or followed) entity blits at its new screen position. Never throws into the render pass.
     */
    public void draw(GOut g, Pipe state) {
        if((img == null) || img.dead)
            return;                                  // disposed image → draw nothing
        Coord sc;
        try {
            Coord3f v = Homo3D.obj2view(new Coord3f(0f, 0f, 0f), state, Area.sized(g.sz()));
            if(v == null)
                return;                              // not projectable this frame
            sc = v.round2();
        } catch(RuntimeException e) {
            return;                                  // never throw into the render pass (mirrors LuaGobOverlay)
        }
        float scale = (gg != null) ? gg.scale : 1f;
        if(scale <= 0f)
            return;
        Coord base = UI.scale(img.sz);               // DPI-scaled like the rest of the HUD
        Coord sz = new Coord(Math.max(1, Math.round(base.x * scale)), Math.max(1, Math.round(base.y * scale)));
        Coord pos = new Coord(sc.x - (sz.x / 2), sc.y - sz.y);   // bottom-centre at the world point ("stands" there)
        float alpha = (gg != null) ? gg.alpha : 1f;
        Color tint = (gg != null) ? gg.tint : null;
        int a8 = clampByte(Math.round(alpha * 255f));
        if(tint == null) {
            g.chcolor(255, 255, 255, a8);
        } else {
            float str = tint.getAlpha() / 255f;      // the tint's alpha = blend strength (ghost MixColor convention)
            g.chcolor(lerpByte(tint.getRed(), str), lerpByte(tint.getGreen(), str), lerpByte(tint.getBlue(), str), a8);
        }
        try {
            g.image(img.tex, pos, sz);
        } catch(RuntimeException e) {
            /* a torn-down texture between the dead-check and the blit: draw nothing */
        }
        g.chcolor();
    }

    /** Lerp a colour channel from white (255) toward {@code c} by {@code str} (0..1), clamped to a byte. */
    private static int lerpByte(int c, float str) {
        return clampByte(Math.round((255f * (1f - str)) + (c * str)));
    }

    private static int clampByte(int v) {
        return (v < 0) ? 0 : ((v > 255) ? 255 : v);
    }
}
