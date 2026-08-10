package io.brodgar.addon;

import haven.Coord;
import haven.Coord2d;
import haven.UI;
import haven.Widget;

/**
 * A <b>widget standing in the 3D world</b> (spec {@code 044-spatial-ui}, task 044.1) — the Java half of
 * {@code hafen.vr():widget():add(w, p)}, and the fourth kind on the client-only world-entity core beside a
 * {@link LuaGhost}, a {@link LuaSprite} and a {@link LuaObject}. Client-only ⇒ <b>unprotected</b> like its three
 * siblings: standing a widget changes where it is drawn and nothing else — the clicks that reach the server
 * are still the ones the user makes with their own hand.
 *
 * <p><b>Two objects, one entity.</b> The {@link #surface} is where the widget is drawn (an offscreen colour
 * target and a real place in the widget tree — see {@link WidgetSurface}); the inherited {@code gob} is where
 * it stands (a virtual {@link GhostGob} carrying a {@link SurfaceQuad} in its {@code Drawable} slot). Splitting
 * them is what makes the whole feature small: everything about a place in the world — the transform, the look,
 * the scene lifecycle, the anchor, the teardown — is the shared core's, unchanged, and everything about being a
 * widget is the client's own, unchanged.
 *
 * <p><b>{@link #content} is not this entity's to destroy.</b> Standing a widget is a re-home, not a
 * construction: the widget existed before, and it goes back where it was when the entity ends — on {@code
 * :remove}, on {@code :reload}, on disable and on teardown alike, through {@link #destroyed()}. That is why the
 * previous parent and place are recorded at stand time rather than derived later: by the time an entity ends,
 * the tree it was standing out of may have moved on.
 *
 * <p><b>And it is not always the addon's own widget</b> (044.6). A window of the client's stands on exactly the
 * same record: it is re-homed, not adopted, so it stays bound to its server id and goes on filling with items
 * while it stands, and the record is what puts it back on the flat UI when the entity ends. That is the whole of
 * what standing a borrowed widget is — the same layer-that-restores as {@code widget:position(x, y)} and
 * {@code widget:replace(view)}, and unprotected for the same reason: the clicks that reach the server are still the
 * ones the user makes with their own hand.
 */
public final class LuaWidgetEntity extends LuaWorldEntity {
    /** The offscreen surface the widget is drawn on, and the tree node it lives under while it stands. */
    final WidgetSurface surface;
    /** The widget itself — the root the addon addresses ({@code window chrome}, or the widget/control). */
    final Widget content;
    /** Where it came from, so removing it puts it back (null ⇒ {@code ui.root}). Set at stand time. */
    Widget prevParent;
    /** ...and the place it had there. */
    Coord prevPos = Coord.z;
    /**
     * <b>This entity is ending because its content is DYING</b> (044.8), not because the addon, a reload or the
     * gob took it away — so there is nowhere to put the widget back to, and {@link #destroyed()} must not try.
     *
     * <p>It is a flag rather than a test on the widget because the widget cannot be asked. A {@link
     * haven.Window} announces its removal at the <i>start</i> of its hide animation ({@code reqdestroy}, which
     * is the path the server's own destroy takes) and only unlinks when the fade ends, so at the moment the
     * removal drain reaches us it is still a perfectly ordinary child of the surface and every guard below
     * passes. Putting it back then dropped a dead, empty window onto the flat UI — owned by nobody, so no
     * teardown ever collected it and it outlived a {@code :reload}. Set by the one door that means
     * <i>the content is gone</i>.
     */
    volatile boolean contentGone;

    LuaWidgetEntity(Addon owner, WidgetSurface surface, Widget content, Coord2d rc, double a) {
        super(owner, rc, a);
        this.surface = surface;
        this.content = content;
    }

    void unregister() {
        owner.surfaces.remove(this);
    }

    /**
     * A standing widget's three visuals, all sampling the <b>same</b> surface — the offscreen pass is
     * identical in every mode and only what reads the texture changes (044.3): an upright world quad at the
     * entity's own facing, the same quad turned to the screen plane ({@link CameraFacing}), or a constant-size
     * screen blit ({@link LuaSurfaceBillboard}). Sized from the widget's pixels either way, so a panel is the
     * same panel whichever way it meets the viewer.
     */
    haven.Drawable visual(haven.Gob gob, String mode) {
        if(VrApi.SCREEN.equals(mode))
            return new LuaSurfaceBillboard(gob, surface);
        float[] wh = VrApi.surfaceWorldDims(surface.sz);
        haven.Sprite.Mill<SurfaceQuad> mill = SurfaceQuad.mill(surface.texture(), wh[0], wh[1]);
        return new SurfaceDrawable(gob, mill, surface, wh[0], wh[1], VrApi.CAMERA.equals(mode));
    }

    /**
     * What a string filter matches on a standing widget: its caption when it has one — the "Ore Smelter"
     * window is found by its title, which is what anybody looking for it knows — and its widget type
     * otherwise, so a bare surface is still addressable.
     */
    String visualName() {
        String t = LuaWidget.text(content);
        return ((t != null) && (t.length() > 0)) ? t : LuaWidget.typeName(content);
    }

    /**
     * <b>Never fires</b> (044.4). The other three kinds are pictures, so "it was clicked" is the whole of what
     * they have to say; a widget answers a click the way it always did — its own {@code MouseDown} at the pixel
     * the pointer landed on — so this entity is not in the world pick at all and nothing reaches this name.
     * The method stays because every kind must answer it, and answering it with a lie would be worse.
     */
    String clickEvent() {
        return "WidgetClicked";
    }

    /** The {@code hafen.vr()} collection this one belongs to. */
    String kind() {
        return "widget";
    }

    /**
     * The entity ended: put the widget back where it stood from, <b>then</b> free the surface. The order is the
     * whole of it — a surface destroyed with the widget still inside it would take the widget's own subtree
     * down with it ({@code Widget.destroy} disposes recursively), which is the one outcome "removing it puts it
     * back" must never produce.
     *
     * <p><b>One rule for both provenances</b> (044.6): the record is <i>where the widget was</i>, and this puts
     * it back there — a window of the client's returns to the flat UI under the frame it came out of, one of the
     * addon's own to its default parent. <b>Visibility is not part of the record</b>, and that is what makes this
     * D-070's rule rather than an exception to it: standing is the one write in that family which hides nothing,
     * so the widget's own {@code visible} is what the user was seeing the whole time it stood, and carrying it
     * through unwritten is the only answer that is right in both the was-visible and the was-hidden case. A
     * standing window the user toggled off comes back off; one they were looking at in the world comes back on
     * screen.
     *
     * <p>Four guards, each for a case that really happens: the content must not be <b>dying</b>
     * ({@link #contentGone} — a window announces its removal before it unlinks, so "is it still in the surface"
     * cannot tell a live widget from one halfway through its own destruction); the surface must still be under
     * the <b>live</b> root (after a relogin {@code AddonManager.ui} is already the NEW session's, and the old
     * tree's widget must not be re-homed into it — the whole tree it belongs to is gone); the recorded parent
     * must still be in that tree (else the widget goes to the root rather than into a dead frame); and the
     * content must still be in the surface at all.
     */
    void destroyed() {
        UI u = AddonManager.ui;
        WidgetSurface s = surface;
        if(!contentGone && (u != null) && (u.root != null) && (content != null) && (content.parent == s)
           && s.hasparent(u.root)) {
            Widget np = ((prevParent != null) && prevParent.hasparent(u.root)) ? prevParent : u.root;
            try {
                // A fresh Coord again (see where it was recorded): what goes into the widget's own c must not
                // be the record itself, or the two become one object and the next reader of either is wrong.
                WidgetSurface.reparent(u, content, np, new Coord(prevPos));
            } catch(RuntimeException e) {
                AddonManager.log("standing widget could not be put back: " + e);
            }
        }
        if(s != null) {
            s.free();                                  // the texture goes, whatever happens to the tree below
            try {
                if(s.parent != null)
                    s.destroy();
            } catch(RuntimeException e) {
                /* the tree is already gone (a relog) — the surface goes with it */
            }
        }
    }
}
