package io.brodgar.addon;

import haven.Coord;
import haven.MapView;
import haven.UI;
import haven.Widget;

/**
 * <b>Where the pointer lands on a widget standing in the world</b> ({@code hafen.vr():widget()}, 044.4) — the
 * screen-point &rarr; widget-pixel map, the front-to-back resolution between several standing panels, and the
 * four entries {@code haven.MapView} calls before it does anything of its own with a mouse event.
 *
 * <p><b>It is a homography, and that is the whole trick.</b> A surface is a flat quad, so however it is placed,
 * turned or scaled, and whichever of the three {@code :facing} modes it is in, its picture reaches the screen
 * through a projective map of the plane — and a projective map is fixed by four point pairs. The quad's four
 * corners are projected during the world draw ({@link SurfaceDrawable}, or the blit rectangle itself in
 * {@code "screen"} mode), and inverting the {@code 3&times;3} they determine turns a screen pixel back into a
 * widget pixel exactly, perspective foreshortening included. No projection or view matrix is inverted, nothing
 * new is exposed to Lua, and the {@code "screen"} mode falls out as the degenerate (affine) case of the same
 * arithmetic rather than as a second code path.
 *
 * <p><b>Synchronous, because a click is a gesture and not an event.</b> The engine's own pick pass
 * ({@code MapView.Hittest}) answers a frame later, on the render thread's callback — fine for "the player
 * clicked that tree", useless for a button, which needs the press, whatever drag follows and the release to be
 * one uninterrupted sequence dispatched from inside the very event the press arrived in. So the corner map,
 * which is arithmetic over numbers recorded last frame, is what answers here; the pick pass is left to the
 * world. The cost is that a panel hidden behind a hill still takes the pointer: input does not consult the
 * depth buffer, only the projected quad and, between two overlapping panels, which is nearer the camera.
 *
 * <p><b>A miss is a fall-through, never a consumed click.</b> Every entry returns {@code false} when the point
 * is on no surface, and {@code MapView} then does exactly what it always did — which is what makes "a click
 * that misses still reaches the world beneath it" true by construction rather than by a second test.
 */
final class SurfaceInput {
    private SurfaceInput() {}

    /** Below this a determinant is noise: the quad is edge-on and has no interior to land on. */
    private static final float EPS = 1e-6f;

    /**
     * The surface a button press landed on, so the release and whatever drag came between go to the SAME
     * panel even after the pointer has left it — the flat UI's own grab behaviour, which a widget standing in
     * the world has to keep for a scrollbar or a slider to be usable at all.
     */
    private static volatile WidgetSurface held;

    /**
     * The frame the held surface was last handed something. A widget that takes the mouse on its press — every
     * button does — is fed the REST of the gesture by the UI's own grab, which never comes past {@code MapView},
     * so the release that ends it is one this class simply never sees. Without a clock the gesture would look
     * eternal and {@link #gesturing} would repaint that panel every frame for the rest of the session.
     */
    private static volatile long heldFrame = Long.MIN_VALUE;

    /** A surface has ended: it must not go on receiving the rest of a gesture. */
    static void forget(WidgetSurface s) {
        if(held == s)
            held = null;
    }

    /**
     * Is a gesture in flight on {@code s} <i>right now</i>? While one is, the panel repaints every frame: a
     * control being dragged — a scrollbar, a slider — has no cached face to say it changed, it simply draws its
     * new position, so the drag itself is the only signal that covers all of them. Bounded by the frame the
     * last event arrived in, for the reason {@link #heldFrame} gives: a still pointer is not changing anything
     * anyway, and a gesture that ended out of sight must not cost anything forever.
     */
    static boolean gesturing(WidgetSurface s) {
        return (held == s) && ((WidgetSurface.frames() - heldFrame) <= 2);
    }

    // ---------------------------------------------------------------- the homography (pure arithmetic)

    /**
     * The {@code 3&times;3} that maps the unit square onto the projected quad {@code q} — 8 floats, the corner
     * screen positions in the order {@link SurfaceQuad#quadVerts} lays the quad out: BL {@code (0,0)},
     * BR {@code (1,0)}, TL {@code (0,1)}, TR {@code (1,1)}, with {@code v} counted UP from the widget's bottom
     * edge. Row-major, the last element being 1; {@code null} when the four points do not determine one
     * (an edge-on quad). Pure — headless-testable.
     */
    static float[] forward(float[] q) {
        if((q == null) || (q.length < 8))
            return null;
        float x0 = q[0], y0 = q[1];        // uv (0, 0)
        float x1 = q[2], y1 = q[3];        // uv (1, 0)
        float x3 = q[4], y3 = q[5];        // uv (0, 1)
        float x2 = q[6], y2 = q[7];        // uv (1, 1)
        float sx = (x0 - x1) + (x2 - x3), sy = (y0 - y1) + (y2 - y3);
        float a, b, c, d, e, f, g, h;
        if((Math.abs(sx) < EPS) && (Math.abs(sy) < EPS)) {
            // The projected quad is a parallelogram: no perspective term, so the map is plainly affine. This is
            // the case a "screen" blit is always in, and the case a world quad seen face-on tends toward.
            a = x1 - x0; b = x3 - x0; c = x0;
            d = y1 - y0; e = y3 - y0; f = y0;
            g = 0f;      h = 0f;
        } else {
            float dx1 = x1 - x2, dx2 = x3 - x2, dy1 = y1 - y2, dy2 = y3 - y2;
            float den = (dx1 * dy2) - (dx2 * dy1);
            if(Math.abs(den) < EPS)
                return null;
            g = ((sx * dy2) - (sy * dx2)) / den;
            h = ((dx1 * sy) - (dy1 * sx)) / den;
            a = (x1 - x0) + (g * x1); b = (x3 - x0) + (h * x3); c = x0;
            d = (y1 - y0) + (g * y1); e = (y3 - y0) + (h * y3); f = y0;
        }
        return new float[] { a, b, c, d, e, f, g, h, 1f };
    }

    /** The inverse of a row-major {@code 3&times;3}, or {@code null} when it is singular. Pure. */
    static float[] invert(float[] m) {
        if(m == null)
            return null;
        float a = m[0], b = m[1], c = m[2], d = m[3], e = m[4], f = m[5], g = m[6], h = m[7], i = m[8];
        float ca = (e * i) - (f * h), cb = (f * g) - (d * i), cc = (d * h) - (e * g);
        float det = (a * ca) + (b * cb) + (c * cc);
        if(Math.abs(det) < 1e-9f)
            return null;
        float id = 1f / det;
        return new float[] {
            ca * id,                     ((c * h) - (b * i)) * id, ((b * f) - (c * e)) * id,
            cb * id,                     ((a * i) - (c * g)) * id, ((c * d) - (a * f)) * id,
            cc * id,                     ((b * g) - (a * h)) * id, ((a * e) - (b * d)) * id };
    }

    /** Apply a row-major {@code 3&times;3} to {@code (x, y, 1)} and divide through, or {@code null}. Pure. */
    static float[] apply(float[] m, float x, float y) {
        if(m == null)
            return null;
        float w = (m[6] * x) + (m[7] * y) + m[8];
        if(Math.abs(w) < 1e-9f)
            return null;
        return new float[] { (((m[0] * x) + (m[1] * y)) + m[2]) / w, (((m[3] * x) + (m[4] * y)) + m[5]) / w };
    }

    // ---------------------------------------------------------------- one surface, both directions

    /**
     * The widget pixel a point in the map view's own pixels lands on, or {@code null}. With {@code bound} the
     * point must be ON the panel; without it the map is extrapolated past the edges, which is what a drag that
     * has left the panel needs (and exactly what the flat UI does with a grabbed pointer).
     */
    static Coord local(WidgetSurface s, float sx, float sy, boolean bound) {
        float[] uv = apply(invert(forward(s.corners())), sx, sy);
        if(uv == null)
            return null;
        if(bound && ((uv[0] < 0f) || (uv[0] > 1f) || (uv[1] < 0f) || (uv[1] > 1f)))
            return null;
        return new Coord(Math.round(uv[0] * s.sz.x), Math.round((1f - uv[1]) * s.sz.y));
    }

    /** The other direction: where widget pixel {@code (wx, wy)} is drawn, in the map view's own pixels. */
    static Coord screen(WidgetSurface s, int wx, int wy) {
        float[] m = forward(s.corners());
        if(m == null)
            return null;
        float u = (s.sz.x <= 0) ? 0f : (wx / (float)s.sz.x);
        float v = (s.sz.y <= 0) ? 0f : (1f - (wy / (float)s.sz.y));
        float[] p = apply(m, u, v);
        return (p == null) ? null : new Coord(Math.round(p[0]), Math.round(p[1]));
    }

    /** {@code panel:screen(x, y)} — the same, in the SCREEN coordinates the pointer itself reports. */
    static Coord screenOf(LuaWidgetEntity we, int wx, int wy) {
        Coord mr = viewOrigin();
        if((mr == null) || (we == null) || we.dead)
            return null;
        Coord p = screen(we.surface, wx, wy);
        return (p == null) ? null : p.add(mr);
    }

    // ---------------------------------------------------------------- which surface, and where on it

    /** One resolved landing: the surface the pointer is on and the widget pixel it is on. */
    private static final class Hit {
        final WidgetSurface s;
        final Coord local;

        Hit(WidgetSurface s, Coord local) {
            this.s = s;
            this.local = local;
        }
    }

    /**
     * The frontmost surface under a point in the map view's own pixels. Nearest wins, by the depth its centre
     * projected to — so two panels that overlap on screen answer the way they look, and a {@code "screen"} blit
     * (which records a depth of {@code -1}, being drawn over the whole scene) wins over every world quad, again
     * the way it looks.
     */
    private static Hit hit(MapView mv, Coord pc) {
        Hit best = null;
        float bd = Float.MAX_VALUE;
        for(WidgetSurface s : WidgetSurface.all(mv.ui)) {   // 073.2: the panels of the scene being pointed at
            if(!s.takesPointer())
                continue;
            Coord l = local(s, pc.x, pc.y, true);
            if(l == null)
                continue;
            float d = s.depth();
            if((best == null) || (d < bd)) {
                best = new Hit(s, l);
                bd = d;
            }
        }
        return best;
    }

    // ---------------------------------------------------------------- the four entries MapView calls

    static boolean mouseDown(MapView mv, Widget.MouseDownEvent ev) {
        Hit h = hit(mv, ev.c);
        held = (h == null) ? null : h.s;
        heldFrame = WidgetSurface.frames();
        return deliver(mv, h, ev);
    }

    static boolean mouseUp(MapView mv, Widget.MouseUpEvent ev) {
        Hit h = grabbed(ev.c);
        held = null;
        if(h == null)
            h = hit(mv, ev.c);
        return deliver(mv, h, ev);
    }

    static boolean mouseMove(MapView mv, Widget.MouseMoveEvent ev) {
        Hit h = grabbed(ev.c);
        return deliver(mv, (h == null) ? hit(mv, ev.c) : h, ev);
    }

    static boolean mouseWheel(MapView mv, Widget.MouseWheelEvent ev) {
        return deliver(mv, hit(mv, ev.c), ev);
    }

    /** The gesture in progress, if a press is still down on a surface — extrapolated, so it survives leaving it. */
    private static Hit grabbed(Coord pc) {
        WidgetSurface s = held;
        if((s == null) || !s.takesPointer()) {
            held = null;
            return null;
        }
        Coord l = local(s, pc.x, pc.y, false);
        return (l == null) ? null : new Hit(s, l);
    }

    /**
     * Hand the event to the widget in the panel's own pixels. The surface is a real place in the widget tree,
     * so this is the client's own {@code PointerEvent} traversal from the surface down — the same one the flat
     * UI runs from {@code ui.root} — and every widget below it sees the coordinates, the propagation and the
     * handling it would have seen on screen. {@link WidgetSurface#origin} is refreshed first so that anything
     * the widget grabs mid-gesture resolves against the pointer where it actually is.
     */
    private static boolean deliver(MapView mv, Hit h, Widget.PointerEvent ev) {
        if(h == null)
            return false;
        Coord mr = viewOrigin(mv);
        if(mr != null)
            h.s.origin(ev.c.add(mr).sub(h.local));
        if(held == h.s)
            heldFrame = WidgetSurface.frames();
        try {
            ev.derive(h.local).dispatch(h.s);
        } catch(RuntimeException e) {
            AddonManager.log("standing widget input error: " + e);
        }
        return true;
    }

    /**
     * <b>The other things the client asks at a point</b> (044.5) — a tooltip, a cursor, a hover state. Each is
     * an ordinary {@code PointerEvent} the client dispatches from {@code ui.root} once a frame, and each would
     * step straight over a standing panel: the surface hosting it is an invisible child of the root, which is
     * exactly what takes it out of the flat UI's hit-testing, and the point is in screen coordinates rather
     * than the panel's pixels. So the same corner map that answers a click answers these, and the event is
     * dispatched from the surface — the client's own traversal, from a real root, in the panel's own pixels.
     *
     * <p>{@code true} means a panel took it and the caller must not also walk the flat tree. The gate is
     * {@link WidgetSurface#takesPointer()}, the same one a click passes, so {@code panel:clickable(false)}
     * makes a panel transparent to a hover and a tooltip exactly as it does to a press: it is there to look at.
     *
     * <p><b>The hover flag has to be carried by hand.</b> {@code MouseHoverEvent}'s derive constructor leaves
     * {@code hovering} false — the flat propagation sets it per child, on purpose — so a derived event handed
     * straight to a surface would report "not hovering" and un-hover the very widget the pointer is on.
     */
    static boolean query(Widget.PointerEvent ev, Coord c) {
        if((ev == null) || (c == null))
            return false;
        MapView mv = AddonManager.screenView();   // 073.2: this is the flat UI asking, so it is the drawn scene
        Coord mr = viewOrigin(mv);
        if(mr == null)
            return false;
        Hit h = hit(mv, new Coord(c.x - mr.x, c.y - mr.y));
        if(h == null)
            return false;
        Widget.PointerEvent dev = ev.derive(h.local);
        if((dev instanceof Widget.MouseHoverEvent) && (ev instanceof Widget.MouseHoverEvent))
            ((Widget.MouseHoverEvent)dev).hovering(((Widget.MouseHoverEvent)ev).hovering);
        try {
            dev.dispatch(h.s);
        } catch(RuntimeException e) {
            AddonManager.log("standing widget query error: " + e);
        }
        return true;
    }

    /**
     * <b>Dropping something onto a panel in the world</b> (044.6) — putting ore into a smelter window standing
     * on the smelter, which is the case this whole feature exists for. A drop is a {@link Widget.PointerEvent}
     * like the three queries above, but it is dispatched neither from {@code ui.root} nor through
     * {@code MapView}: the dragged item hands its {@code DTarget.Drop} to its own parent (the HUD), and a
     * standing panel is not under the HUD any more — so without this the item lands on the map underneath the
     * panel it was aimed at, which is the one place the player did not want it.
     *
     * <p><b>What comes back is whether a widget ACCEPTED it, not whether a panel was there</b> — and that
     * difference is the transparency rule. Dropping onto a part of a window that takes no items falls through
     * to whatever is behind it, on the flat UI and in the world alike; answering "a panel was under the
     * pointer" would swallow those drops instead, and the client's own fall-through is what the caller then
     * goes on to run.
     */
    static boolean drop(Widget.PointerEvent ev, Coord c) {
        if((ev == null) || (c == null))
            return false;
        MapView mv = AddonManager.screenView();   // 073.2: the dragged item is on the drawn HUD
        Coord mr = viewOrigin(mv);
        if(mr == null)
            return false;
        Hit h = hit(mv, new Coord(c.x - mr.x, c.y - mr.y));
        if(h == null)
            return false;
        try {
            return ev.derive(h.local).dispatch(h.s);
        } catch(RuntimeException e) {
            AddonManager.log("standing widget drop error: " + e);
            return false;
        }
    }

    /**
     * {@code hafen.vr():pointer(key, x, y [, a])} — the same four entries, entered from Lua at a SCREEN point.
     * This is the client's own path from the map view inward and nothing more: it never falls through to the
     * world, so it can neither move the character nor reach the server, and a point on no panel is answered by
     * {@code false} — which is precisely the moment {@code MapView} goes on to do what it always did.
     */
    static boolean pointer(String key, int rx, int ry, int arg) {
        MapView mv = AddonManager.screenView();
        Coord mr = viewOrigin(mv);
        if(mr == null)
            return false;
        Coord p = new Coord(rx - mr.x, ry - mr.y);
        if("MouseDown".equals(key))
            return mouseDown(mv, new Widget.MouseDownEvent(p, arg));
        if("MouseUp".equals(key))
            return mouseUp(mv, new Widget.MouseUpEvent(p, arg));
        if("MouseMove".equals(key))
            return mouseMove(mv, new Widget.MouseMoveEvent(p));
        if("Wheel".equals(key))
            return mouseWheel(mv, new Widget.MouseWheelEvent(p, arg, arg));
        return false;
    }

    // ---------------------------------------------------------------- the surface's place, for grabs

    /**
     * Keep {@link WidgetSurface#origin} following the pointer. A widget that takes the mouse
     * ({@code ui.grabmouse} — every button, scrollbar and slider does) is then fed by the UI's own grab
     * machinery, which reaches it by {@code rootpos()} arithmetic and so never comes past {@code MapView}
     * again; refreshing here, once per surface per frame, is what keeps that arithmetic landing on the same
     * pixel the corner map would have. Off-panel is fine and deliberate: a drag that has left the panel wants
     * the extrapolated point, not a clamp.
     *
     * <p><b>Following the pointer is only right while a gesture is in flight on this surface.</b> Outside one
     * it would mean every widget in every standing panel appeared, to anything asking where it is, to be
     * permanently under the cursor wherever the cursor went. At rest the honest answer is the panel's own
     * top-left, projected — which is what the corner map says the widget-local origin is drawn at, and which
     * is exact for a {@code "screen"} blit and for any world quad seen square-on. 044.5 needs a resting answer
     * because a popup opened INSIDE a surface (a dropdown's list) grabs the mouse and is then fed by
     * {@code rootpos()} arithmetic, which is this number; leaving it at whatever the last gesture happened to
     * set would put that list's own clicks somewhere else entirely.
     */
    static void refreshOrigin(WidgetSurface s) {
        Coord mr = viewOrigin();
        if(mr == null)
            return;
        if(held != s) {
            Coord tl = screen(s, 0, 0);
            if(tl != null)
                s.origin(tl.add(mr));
            return;
        }
        UI u = AddonManager.screen();
        if((u == null) || (u.mc == null))
            return;
        Coord l = local(s, u.mc.x - mr.x, u.mc.y - mr.y, false);
        if(l != null)
            s.origin(u.mc.sub(l));
    }

    /** Where the map view sits in the UI's own coordinates — screen point &harr; map-view point. */
    private static Coord viewOrigin() {
        return viewOrigin(AddonManager.screenView());
    }

    private static Coord viewOrigin(MapView mv) {
        if((mv == null) || (mv.ui == null) || (mv.ui.root == null) || !mv.hasparent(mv.ui.root))
            return null;
        try {
            return mv.rootpos();
        } catch(RuntimeException e) {
            return null;                               // the tree moved under us mid-frame: no answer this frame
        }
    }
}
