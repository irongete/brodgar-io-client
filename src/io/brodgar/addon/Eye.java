package io.brodgar.addon;

import haven.Area;
import haven.Coord3f;
import haven.HomoCoord4f;
import haven.MapView;
import haven.render.Homo3D;
import haven.render.Pipe;

/**
 * <b>Where a world point lands on screen — and nothing where it lands nowhere.</b> Every 2D pass that hangs
 * something over a place in the world ends in the same projective divide, and that divide has one input it
 * cannot answer for: a point <b>behind the eye</b>. {@link HomoCoord4f#toview} divides x, y and z by w with no
 * guard, so a negative w — which is every point behind the camera plane — comes back <b>mirrored through the
 * centre of the view</b>, at a pixel that is on screen and means nothing.
 *
 * <p><b>The artifact is not a corner case, it is what the "bad" camera does when you zoom in.</b> That camera
 * ({@code MapView.FreeCam}) clamps its distance at 5 units and its elevation at 0, so pulled all the way in it
 * sits beside the character looking along the ground — and then everything behind the player is behind the eye.
 * The mirror image does not fly off the edge either: the mirrored offset is divided by the distance it is
 * mirrored across, so a thing far behind lands <i>near the middle of the screen</i>. A label thirty tiles back
 * draws thirty tiles forward; a traced footprint draws as lines across the whole view. At the default elevation
 * the same points mirror to well outside the frustum, which is why it is only ever seen zoomed in.
 *
 * <p><b>Neither of the client's own two doors guards it.</b> {@code Homo3D.obj2view} divides and hands back a
 * {@code Coord3f} whatever w was; {@code MapView.screenxf} calls {@code MapView.clipxf(mc, false)}, and clipping
 * is the argument it passes false for. Both are upstream and both are used unguarded upstream too (the chat
 * bubble, the player-name label). So the guard lives here, once, in the shape every caller already wants: a
 * point, or {@code null} when there is no point to be had — the same {@code null} the surface quads have
 * answered since 044.7 ({@link SurfaceDrawable}), now the only spelling of it.
 *
 * <p>Only w is tested. A point that is <i>in front</i> of the eye but outside the frustum projects correctly and
 * simply lands off the edge, where the painter clips it for free; taking the frustum's sides into account here
 * would drop a label whose anchor is off screen but whose text reaches back onto it.
 */
public final class Eye {
    private Eye() {}

    /**
     * How near the eye plane a point may sit and still be worth projecting. Not zero: w is the distance the
     * divide is by, so a point a millionth of a unit in front of the eye is "visible" and projects to a
     * coordinate several million pixels out — off screen either way, and a number nothing downstream should
     * have to round.
     */
    private static final float NEAR = 1e-4f;

    /**
     * The screen point an <b>object-space</b> point projects to through the pipe of the slot being drawn — view-
     * local device pixels, z in [0, 1] — or {@code null} when it is behind the eye. The pipe carries the object's
     * own placement, so {@code (0, 0, 0)} is wherever the client has put the thing this frame.
     */
    public static Coord3f view(Coord3f objc, Pipe state, Area view) {
        HomoCoord4f c = Homo3D.obj2clip(objc, state);
        return ((c == null) || !(c.w > NEAR)) ? null : c.toview(view);
    }

    /**
     * The screen point a <b>world</b> point projects to in a given view — view-local device pixels, the space
     * {@code MapView.screenxf} answers in — or {@code null} when it is behind the eye. The y flip {@code screenxf}
     * does on the way in is {@code MapView.clipxf}'s, so this takes the same coordinate {@code screenxf} does.
     */
    public static Coord3f view(MapView mv, Coord3f mc) {
        if(mv == null)
            return null;
        HomoCoord4f c = mv.clipxf(mc, false);
        return ((c == null) || !(c.w > NEAR)) ? null : c.toview(Area.sized(mv.sz));
    }
}
