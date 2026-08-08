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
import haven.TexRender;
import haven.render.Homo3D;
import haven.render.Pipe;

/**
 * The <b>constant-size blit</b> of a standing widget ({@code hafen.vr():widget()} with
 * {@code :facing("screen")}, 044.3) — {@link LuaSpriteBillboard} with a render target in place of a PNG, and
 * the third of the three ways a surface can meet the viewer. The widget is drawn into its
 * {@link WidgetSurface} exactly as in the other two modes (nothing about the offscreen pass changes); only
 * what samples the texture does. Instead of a world quad there is a screen-space blit anchored at the
 * entity's projected world point: bottom-centred like a sprite's, drawn in the 2D overlay pass on top of the
 * scene, at the widget's own pixel size whatever the zoom. So it is legible at any distance and it is
 * <b>not</b> world geometry — no perspective, no occlusion, no shrinking, and no pick.
 *
 * <p><b>Why a {@link Drawable}.</b> The same reason {@link LuaSpriteBillboard} is one: it is the entity's only
 * visual, and a virtual gob with no {@code Drawable} is garbage-collected out of the scene every {@code ctick}
 * ({@code learnings/rendering.md} R2b). Resource-free ({@link #getres()} {@code == null}, like
 * {@link haven.SprDrawable}).
 *
 * <p><b>Ownership.</b> The texture belongs to the {@link WidgetSurface}, which frees it when the entity ends;
 * this visual only samples it, so {@link #dispose()} is a no-op and a freed surface ({@code texture() == null})
 * simply blits nothing.
 */
public final class LuaSurfaceBillboard extends Drawable implements PView.Render2D {
    private final WidgetSurface surf;   // the offscreen target (owned by the entity; NOT disposed here)
    private final GhostGob gg;          // the virtual gob whose live look fields (alpha/tint/scale) we read, or null

    LuaSurfaceBillboard(Gob gob, WidgetSurface surf) {
        super(gob);
        this.surf = surf;
        this.gg = (gob instanceof GhostGob) ? (GhostGob)gob : null;
    }

    /** Resource-free (like {@link haven.SprDrawable}) — no backing {@code .res}. */
    public Resource getres() {
        return null;
    }

    /** The texture is the surface's; nothing to free here. */
    public void dispose() {
    }

    /**
     * The 2D overlay pass: project the gob's origin to the screen and blit the surface there, bottom-centred,
     * at its own pixel size times the entity's {@code :scale}, with its live opacity and tint. Never throws
     * into the render pass (mirrors {@link LuaSpriteBillboard}).
     */
    public void draw(GOut g, Pipe state) {
        TexRender tr = surf.texture();
        if(tr == null)
            return;                                  // the surface has been freed → draw nothing
        Coord sc;
        try {
            Coord3f v = Homo3D.obj2view(new Coord3f(0f, 0f, 0f), state, Area.sized(g.sz()));
            if(v == null)
                return;                              // not projectable this frame
            sc = v.round2();
        } catch(RuntimeException e) {
            return;
        }
        float scale = (gg != null) ? gg.scale : 1f;
        if(scale <= 0f)
            return;
        Coord base = surf.sz;                        // 1:1 with the texture — the widget drew itself at exactly
                                                     // this size, already DPI-scaled, so UI.scale would double it
        Coord sz = new Coord(Math.max(1, Math.round(base.x * scale)), Math.max(1, Math.round(base.y * scale)));
        Coord pos = new Coord(sc.x - (sz.x / 2), sc.y - sz.y);   // bottom-centre at the world point
        float alpha = (gg != null) ? gg.alpha : 1f;
        Color tint = (gg != null) ? gg.tint : null;
        int a8 = clampByte(Math.round(alpha * 255f));
        if(tint == null) {
            g.chcolor(255, 255, 255, a8);
        } else {
            float str = tint.getAlpha() / 255f;      // the tint's alpha = blend strength (the ghost convention)
            g.chcolor(lerpByte(tint.getRed(), str), lerpByte(tint.getGreen(), str), lerpByte(tint.getBlue(), str), a8);
        }
        try {
            g.image(tr, pos, sz);                    // the surface's own render() flips t (see WidgetSurface)
        } catch(RuntimeException e) {
            /* a surface freed between the null check and the blit: draw nothing */
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
