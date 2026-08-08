package io.brodgar.addon;

import haven.Area;
import haven.Coord3f;
import haven.GOut;
import haven.Gob;
import haven.HomoCoord4f;
import haven.PView;
import haven.SprDrawable;
import haven.Sprite;
import haven.render.Homo3D;
import haven.render.Pipe;

/**
 * The <b>{@link Drawable} slot of a widget standing in the world</b> in either of its two world-geometry modes
 * ({@code :facing("fixed")} and {@code :facing("camera")}, 044.3/044.4) — an ordinary {@link SprDrawable} over
 * a {@link SurfaceQuad}, with two things added and nothing else changed.
 *
 * <p><b>It knows how it is turned</b>: {@code "camera"} hands it the view-plane {@link Gob.Placer}
 * {@link CameraFacing} defines, and {@code "fixed"} leaves it the ground placer every gob has. One class for
 * both, because the difference between the two modes really is one method's answer.
 *
 * <p><b>And it says where it ended up</b> (044.4). It is also a {@link PView.Render2D}, which draws nothing at
 * all: the 2D overlay pass is simply the one moment per frame where a node is handed the {@link Pipe} its own
 * slot resolved to, and therefore the only place {@link Homo3D#obj2clip} can be asked where this quad's four
 * corners landed on screen — with every transform above it already applied, the world placement, the facing
 * rotation and the entity's {@code :scale} alike. Those four points are the whole of {@link SurfaceInput}'s
 * hit test, so the pointer follows the picture by construction rather than by a second calculation that could
 * disagree with it.
 *
 * <p>A corner behind the eye has no screen position at all ({@code w <= 0} in clip space, where the projective
 * divide is meaningless), so the record is dropped whole rather than filled with a plausible-looking number —
 * a panel half behind the camera takes no clicks, which is the honest answer and the safe one.
 */
final class SurfaceDrawable extends SprDrawable implements PView.Render2D {
    private final WidgetSurface surf;
    private final float hw, h;            // the quad's half-width and height, in world units
    private final Gob.Placer place;       // the view-plane turn, or null for the gob's own ground placer

    SurfaceDrawable(Gob gob, Sprite.Mill<?> mk, WidgetSurface surf, float w, float h, boolean camera) {
        super(gob, mk);
        this.surf = surf;
        this.hw = w * 0.5f;
        this.h = h;
        this.place = camera ? CameraFacing.viewPlane(gob) : null;
    }

    /** {@code "camera"} turns to the screen plane; {@code "fixed"} stands at the entity's own angle. */
    public Gob.Placer placer() {
        return (place != null) ? place : super.placer();
    }

    /**
     * Draws nothing — this is the corner record. Never throws into the render pass: a surface that cannot be
     * projected this frame simply takes no pointer until it can.
     */
    public void draw(GOut g, Pipe state) {
        try {
            Area a = Area.sized(g.sz());
            float[] q = new float[8];
            if(project(q, 0, 0f, -hw, 0f, state, a) && project(q, 2, 0f, hw, 0f, state, a)
               && project(q, 4, 0f, -hw, h, state, a) && project(q, 6, 0f, hw, h, state, a))
                surf.corners(q, depth(state), a);      // 044.7: the view they landed in IS the culling test
            else
                surf.corners(null, 0f, null);
        } catch(RuntimeException e) {
            surf.corners(null, 0f, null);
        }
    }

    /** One corner, in the map view's own pixels; {@code false} when it is behind the eye. */
    private static boolean project(float[] out, int i, float x, float y, float z, Pipe state, Area a) {
        HomoCoord4f c = Homo3D.obj2clip(new Coord3f(x, y, z), state);
        if((c == null) || !(c.w > 1e-4f))
            return false;
        Coord3f v = c.toview(a);
        out[i] = v.x;
        out[i + 1] = v.y;
        return true;
    }

    /** The quad's centre depth — the one number that orders two panels overlapping on screen. */
    private float depth(Pipe state) {
        HomoCoord4f c = Homo3D.obj2clip(new Coord3f(0f, 0f, h * 0.5f), state);
        return ((c == null) || !(c.w > 1e-4f)) ? 0f : (c.z / c.w);
    }
}
