package io.brodgar.ui;

import haven.Coord;
import haven.Widget;
import io.brodgar.addon.AddonManager;

/**
 * <b>One hand-painted element of the HUD, standing as a widget</b> (173). The client paints a few things no
 * widget stands for: the combat display's eight elements round the character, the bottom-left line. A region is
 * an empty widget on the HUD ({@link #home}) standing for one of them, named by its {@link #role}, so a
 * selector, a sheet rule, an anchor and every read and write an addon has reach the element as they reach any
 * widget.
 *
 * <ul>
 * <li><b>Unheld</b>, it stands where the element is painted: its {@link #painter} reports the element's box
 *     every frame through {@link #off}, which writes the region's {@code c} to it.</li>
 * <li><b>Held</b> -- a position level on it (a verb's, a rule's, a remembered place's, a drag's), or taken out
 *     of its home by {@code :parent(p)} -- it keeps its own place, and {@link #off} answers the offset the
 *     element is painted by to stand there.</li>
 * <li><b>Hidden</b>, {@link #off} answers {@code null} and the painter skips the element.</li>
 * </ul>
 *
 * <p>It paints nothing and takes no hit ({@link #checkhit}): a press over it reaches whatever is behind it,
 * and the addon layer's hit tests, which honour {@code checkhit} at the leaf, answer the same.
 */
public class Region extends Widget {
    /** The role the selector language names this element by, {@code fight.cooldown}, {@code hud.cmdline}. */
    public final String role;
    /** The HUD it stands on while unheld. */
    public final Widget home;
    /** The widget that paints the element, and whose coordinates {@link #off} is given the box in. */
    public final Widget painter;

    private Region(String role, Widget home, Widget painter) {
    super(Coord.z);
    this.role = role;
    this.home = home;
    this.painter = painter;
    }

    /**
     * Stand one region per role on {@code home}, for elements {@code painter} paints, and hand each to the
     * layout, so a rule naming the role places it the moment it appears. Each takes a fresh {@code Coord}:
     * {@code Widget.add(T, Coord)} keeps the one it is given. Runs inside the tree's monitor, where the painter
     * is being added.
     */
    public static Region[] stand(Widget home, Widget painter, String... roles) {
    Region[] rs = new Region[roles.length];
    for(int i = 0; i < roles.length; i++)
        rs[i] = home.add(new Region(roles[i], home, painter), new Coord(0, 0));
    for(int i = 0; i < rs.length; i++)
        AddonManager.regionStood(rs[i]);
    return(rs);
    }

    /** Take the regions down with the element they stand for. */
    public static void drop(Region[] rs) {
    if(rs == null)
        return;
    for(Region r : rs) {
        if(r.parent != null)
        r.destroy();
    }
    }

    /** Does an addon hold this region's place: a position level on it, or a home other than its own? */
    public boolean held() {
    return((parent != home) || AddonManager.posHeld(this));
    }

    public boolean checkhit(Coord c) {
    return(false);
    }

    /**
     * The painter's one call per element per frame. {@code ul} and {@code sz} are the element's box in the
     * painter's coordinates. Answers the offset to paint the element by: {@code Coord.z} itself unheld, so a
     * painter tells "at its default" by identity, the region's place less {@code ul} held, and {@code null}
     * while the region is not visible.
     */
    public Coord off(Coord ul, Coord sz) {
    if(parent == null)
        return(Coord.z);
    resize(sz);
    boolean held = held();
    if(!held) {
        int x, y;
        if(painter == parent) {
        x = ul.x;
        y = ul.y;
        } else if(painter.parent == parent) {
        x = painter.c.x + ul.x;
        y = painter.c.y + ul.y;
        } else {
        Coord at = painter.rootpos().add(ul).sub(parent.xlate(parent.rootpos(), true));
        x = at.x;
        y = at.y;
        }
        if((c.x != x) || (c.y != y)) {
        c = new Coord(x, y);
        AddonManager.regionMoved(this);
        }
    }
    if(!tvisible())
        return(null);
    if(!held)
        return(Coord.z);
    if(parent == painter)
        return(Coord.of(c.x - ul.x, c.y - ul.y));
    if(parent == painter.parent)
        return(Coord.of(c.x - painter.c.x - ul.x, c.y - painter.c.y - ul.y));
    return(rootpos().sub(painter.rootpos()).sub(ul));
    }
}
