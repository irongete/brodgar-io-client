package io.brodgar.addon;

import haven.Coord2d;
import haven.Coord3f;
import haven.Gob;
import haven.MapView;
import haven.Matrix4f;
import haven.SprDrawable;
import haven.Sprite;

/**
 * The <b>camera-facing world quad</b> ({@code hafen.virtual():sprite()} / {@code hafen.virtual():widget()} with
 * {@code :facing("camera")}, 044.3) — the same resource-free {@link SprDrawable} the {@code "fixed"} mode
 * uses, over the same {@link SpriteQuad} / {@link SurfaceQuad} geometry, differing in <b>one</b> thing: where
 * its rotation comes from. It is still world geometry, so it keeps its world size, is drawn in the 3D pass,
 * shrinks with distance, occludes and is occluded, and is picked by the ordinary pick — everything a
 * {@code "screen"} blit gives up in exchange for constant pixels.
 *
 * <p><b>The turn is a {@link Gob.Placer}, not a per-frame write.</b> {@code Gob.placer()} asks the
 * {@code Drawable} for one ({@link haven.Drawable#placer()}) and the render tree rebuilds each client gob's
 * {@code Placement} every frame ({@code Gob.Placed.autotick}), taking the rotation from
 * {@code placer().getr(...)} — so overriding that one method makes the quad turn with the camera with no tick
 * loop, no polling and no core edit beyond reading the camera. The placement is only pushed down the scene
 * when it actually changed ({@code Placement.equals} compares the matrix), so a still camera costs nothing.
 *
 * <p><b>View-plane aligned, in yaw and pitch.</b> The quad is made parallel to the screen rather than aimed at
 * the eye point: every panel is then square-on wherever it sits on screen, which is what "always readable"
 * means, and two panels side by side stay parallel instead of splaying. The columns of the inverse view matrix
 * are the camera's own axes in render space, so the rotation is read off, not solved for: the quad's local
 * {@code x} (its normal — see {@link SpriteQuad#quadVerts}) becomes the camera's <i>back</i> axis, its local
 * {@code y} (width) the camera's right and its local {@code z} (height) the camera's up. With the camera due
 * east of a quad standing at {@code :rotate(0)} that composes to the identity, which is exactly where the
 * {@code "fixed"} quad already faces — the two modes agree at the one angle where they must.
 *
 * <p><b>Where it stands is untouched</b>: {@code getc} delegates to the ground placer, so a camera-facing
 * entity sits on the terrain exactly as its fixed twin does. Its {@code :rotate(a)} is <b>stored but unused</b>
 * while it faces the camera — the entity keeps the angle and honours it again the moment it is put back to
 * {@code "fixed"}.
 */
final class CameraFacing extends SprDrawable {
    private final Gob.Placer place;

    CameraFacing(Gob gob, Sprite.Mill<?> mk) {
        super(gob, mk);
        this.place = viewPlane(gob);
    }

    /**
     * The turn on its own, so the kind that needs it <i>and</i> something else — a standing widget, which also
     * has to report where its corners landed ({@link SurfaceDrawable}, 044.4) — takes the same rotation rather
     * than a second copy of it. One implementation, two visuals.
     */
    static Gob.Placer viewPlane(Gob gob) {
        return new Place(gob.glob.map.trnplace);
    }

    /** The whole of this class: the visual is a fixed quad, placed by a rotation the camera decides. */
    public Gob.Placer placer() {
        return place;
    }

    private static final class Place implements Gob.Placer {
        private final Gob.Placer ground;      // where it stands is not this mode's business

        Place(Gob.Placer ground) {
            this.ground = ground;
        }

        public Coord3f getc(Coord2d rc, double ra) {
            return ground.getc(rc, ra);
        }

        public Matrix4f getr(Coord2d rc, double ra) {
            Matrix4f inv = camxf();
            if(inv == null)
                return ground.getr(rc, ra);   // no camera yet (or a degenerate one): stand as a fixed quad
            // Column-major (m[col*4 + row]), and the view matrix is rigid, so the inverse's first three
            // columns are the camera's unit axes in render space.
            float rx = inv.m[0], ry = inv.m[1], rz = inv.m[ 2];   // eye +x — screen right
            float ux = inv.m[4], uy = inv.m[5], uz = inv.m[ 6];   // eye +y — screen up
            float bx = inv.m[8], by = inv.m[9], bz = inv.m[10];   // eye +z — toward the viewer
            return new Matrix4f(bx, rx, ux, 0,                    // local x → back, y → right, z → up
                                by, ry, uy, 0,
                                bz, rz, uz, 0,
                                 0,  0,  0, 1);
        }
    }

    /**
     * The inverse of the camera's current view matrix, memoized across every camera-facing entity in the
     * frame — one 4&times;4 invert per camera movement rather than one per entity per frame.
     * {@code Transform.fin} hands back the same matrix object while the camera has not moved, so identity is
     * the whole cache key. Held as one immutable pair because the placement tick is not the only thread that
     * may read it.
     */
    private static volatile Cam cache;

    private static final class Cam {
        final Matrix4f src, inv;

        Cam(Matrix4f src, Matrix4f inv) {
            this.src = src;
            this.inv = inv;
        }
    }

    private static Matrix4f camxf() {
        MapView mv = AddonManager.screenView();
        if(mv == null)
            return null;
        Matrix4f v;
        try {
            v = mv.camview();
        } catch(RuntimeException e) {
            return null;                      // never throw out of the placement tick
        }
        if(v == null)
            return null;
        Cam c = cache;
        if((c != null) && (c.src == v))
            return c.inv;
        Matrix4f inv = v.invert();            // null when singular — then we fall back to the fixed rotation
        cache = new Cam(v, inv);
        return inv;
    }
}
