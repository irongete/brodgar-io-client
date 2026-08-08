package io.brodgar.addon;

import haven.Coord;
import haven.Coord2d;
import haven.UI;
import haven.Widget;

/**
 * A <b>widget standing in the 3D world</b> (spec {@code 044-spatial-ui}, task 044.1) — the Java half of
 * {@code hafen.vr():widget():add(w, p)}, and the fourth kind on the client-only world-entity core beside a
 * {@link LuaGhost}, a {@link LuaSprite} and a {@link LuaObject}. Client-only ⇒ <b>ungated</b> like its three
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

    LuaWidgetEntity(Addon owner, WidgetSurface surface, Widget content, Coord2d rc, double a) {
        super(owner, rc, a);
        this.surface = surface;
        this.content = content;
    }

    void unregister() {
        owner.surfaces.remove(this);
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
     */
    void destroyed() {
        UI u = AddonManager.ui;
        WidgetSurface s = surface;
        if((u != null) && (u.root != null) && (content != null) && (content.parent == s)) {
            Widget np = ((prevParent != null) && prevParent.hasparent(u.root)) ? prevParent : u.root;
            try {
                WidgetSurface.reparent(u, content, np, prevPos);
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
