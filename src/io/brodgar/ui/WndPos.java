package io.brodgar.ui;

import haven.Coord;
import haven.GItem;
import haven.UI;
import haven.Utils;
import haven.Widget;
import haven.Window;
import io.brodgar.addon.AddonManager;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * <b>Where a window stands, relative to the screen</b> (166). A window's place is a <b>fraction of its free
 * space</b> per axis, {@code f = c / (parent size - window size)}: 0 the left or top edge, 1 the right or bottom,
 * 0.5 centred -- the server's own rule for a {@code Coord2d} placement ({@code GameUI.addchild}, {@code misc}).
 * A fraction has no unit, so the interface scale cancels out.
 *
 * <ul>
 * <li><b>The rule</b> -- {@link #frac} and {@link #place}. A fraction is clamped to {@code 0..1}, a gap of
 *     {@code UI.scale(10)} or less to an edge is the edge, and an axis with no free space keeps the fraction
 *     it had.</li>
 * <li><b>The value</b> -- the client's own {@code wndc-*} keys carry {@code fx/fy}, each number by
 *     {@code Double.toString} (locale-free, round-trips). An old {@code NxM} value is pixels and loads as it
 *     always did; the next drop rewrites it. A pre-feature build reading the slash falls back to its default.</li>
 * <li><b>The one write</b> -- {@link #dropped}: a place is written when the user puts the window down, and at no
 *     other moment. The stored value is a fraction, so a screen of another size changes nothing there, and there
 *     is nothing for a clock, a character switch or the client closing to catch up on.</li>
 * <li><b>The registry</b> -- each top-level window the rule has seen: its fraction, the {@code c} the rule last
 *     put it at, the parent's size then, whether it follows, and the key it is stored under. A window an addon
 *     built follows only once the user has moved it. A {@code c} other than the recorded one is a move, and the
 *     fraction is retaken from it, unless an addon's layout holds the place: then the place is the layout's and
 *     the fraction stays the user's.</li>
 * </ul>
 *
 * <p><b>Threading.</b> Called under the tree's own monitor (a resize, a server message, a tick); the registry
 * is shared by every session's tree, so it is guarded by this class. Nothing here takes a tree's monitor, and
 * the moved windows are handed to the geometry seam outside the lock.
 */
public final class WndPos {
    private WndPos() {}

    /** A place as the store holds it: a fraction ({@code px == null}) or the old pixels. */
    public static final class Val {
        final double fx, fy;
        final Coord px;

        private Val(double fx, double fy, Coord px) {
            this.fx = fx;
            this.fy = fy;
            this.px = px;
        }

        public String toString() {
            return (px != null) ? (px.x + "x" + px.y) : text(new double[] {fx, fy});
        }
    }

    private static final class Rec {
        double fx = Double.NaN, fy = Double.NaN;   // NaN: not taken yet
        Coord at;                                   // where the rule last put it
        Coord psz;                                  // the parent's size then; null: waiting for a size
        boolean follows;
        String key;                                 // what a drop writes; null: a window nothing stores
        WeakReference<Widget> parent;

        Widget parent() {
            return (parent == null) ? null : parent.get();
        }
    }

    // retained: weak keys over a Rec -- the Rec holds its parent through a WeakReference and nothing else that
    //   reaches a widget, so nothing in the entry reaches the window it is keyed on, and it collects.
    private static final Map<Widget, Rec> recs = new WeakHashMap<Widget, Rec>();

    // ---- the rule ------------------------------------------------------------------------------------

    /** The magnet: a gap this small or smaller to an edge is taken as on it. */
    private static int magnet() {
        return UI.scale(10);
    }

    /**
     * One axis of the rule: {@code c} in a parent {@code p} wide for a window {@code w} wide, as a fraction of
     * the free space. {@code was} is the fraction the axis had, kept when there is no free space (NaN: none, and
     * then a window wider than its parent takes the quotient, clamped, and one exactly as wide takes 0).
     */
    public static double frac(int c, int p, int w, double was) {
        int free = p - w;
        if(free <= 0) {
            if(!Double.isNaN(was))
                return was;
            return (free == 0) ? 0.0 : clamp((double)c / free);
        }
        int mag = magnet();
        if(c <= mag)
            return 0.0;
        if(c >= free - mag)
            return 1.0;
        return clamp((double)c / free);
    }

    /** One axis back: the {@code c} a fraction stands at in a parent {@code p} wide for a window {@code w} wide. */
    public static int place(double f, int p, int w) {
        return (int)Math.round(f * (p - w));
    }

    /**
     * Both axes of the rule: {@code c} in a parent {@code psz} for a widget {@code wsz}, as {@code {fx, fy}}.
     * {@code was} is the fraction it had ({@code null}: none), kept per axis with no free space. {@code null}
     * when the parent has no area yet.
     */
    public static double[] frac(Coord c, Coord psz, Coord wsz, double[] was) {
        if((c == null) || (wsz == null) || !area(psz))
            return null;
        return new double[] {frac(c.x, psz.x, wsz.x, (was == null) ? Double.NaN : was[0]),
                             frac(c.y, psz.y, wsz.y, (was == null) ? Double.NaN : was[1])};
    }

    /** Both axes back: the {@code c} a fraction {@code {fx, fy}} stands at in a parent {@code psz} for a widget {@code wsz}. */
    public static Coord place(double[] f, Coord psz, Coord wsz) {
        return Coord.of(place(f[0], psz.x, wsz.x), place(f[1], psz.y, wsz.y));
    }

    private static double clamp(double f) {
        return Math.max(0.0, Math.min(1.0, f));
    }

    private static boolean area(Coord sz) {
        return (sz != null) && (sz.x > 0) && (sz.y > 0);
    }

    // ---- the value -----------------------------------------------------------------------------------

    /**
     * The place stored under {@code key}: a fraction ({@code fx/fy}), the old pixels ({@code NxM}), or
     * {@code null} for nothing, an empty value or anything that does not parse (NaN and Infinity included).
     */
    public static Val read(String key) {
        return parse(Utils.getpref(key, null));
    }

    /** A fraction {@code {fx, fy}} as the store writes it: {@code fx/fy}, each number by {@code Double.toString}. */
    public static String text(double[] f) {
        return Double.toString(f[0]) + "/" + Double.toString(f[1]);
    }

    /**
     * A stored {@code fx/fy} back as {@code {fx, fy}}, clamped to {@code 0..1} -- {@code null} for the old pixels,
     * nothing, or anything that does not parse.
     */
    public static double[] fraction(String v) {
        Val p = parse(v);
        return ((p == null) || (p.px != null)) ? null : new double[] {p.fx, p.fy};
    }

    static Val parse(String v) {
        if(v == null)
            return null;
        try {
            int s = v.indexOf('/');
            if(s >= 0) {
                double fx = Double.parseDouble(v.substring(0, s)), fy = Double.parseDouble(v.substring(s + 1));
                if(Double.isNaN(fx) || Double.isNaN(fy) || Double.isInfinite(fx) || Double.isInfinite(fy))
                    return null;
                return new Val(clamp(fx), clamp(fy), null);
            }
            int x = v.indexOf('x');
            if(x >= 0)
                return new Val(Double.NaN, Double.NaN, Coord.of(Integer.parseInt(v.substring(0, x)), Integer.parseInt(v.substring(x + 1))));
        } catch(NumberFormatException e) {
        }
        return null;
    }

    // ---- the entry points ----------------------------------------------------------------------------

    /** {@link #load(Widget, Widget, String, Val, Coord)} of what {@code key} holds. */
    public static Coord load(Widget parent, Widget w, String key, Coord def) {
        return load(parent, w, key, read(key), def);
    }

    /**
     * <b>Where to add {@code w} to {@code parent}</b>, from the stored {@code v}: a fraction is placed at the
     * parent's size, pixels are used as they are, nothing gives {@code def}. Registers the window under
     * {@code key}, which its drop writes ({@code null}: nothing stores it). A parent with no size yet
     * ({@code GameUI}'s constructor) leaves a fraction waiting: {@code def} now, and the first {@link #relayout}
     * places it. {@code null} when there is neither a value nor a default.
     */
    public static Coord load(Widget parent, Widget w, String key, Val v, Coord def) {
        if((parent == null) || (w == null))
            return (v != null && v.px != null) ? v.px : def;
        Coord psz = parent.sz;
        synchronized(WndPos.class) {
            Rec r = new Rec();
            r.parent = new WeakReference<Widget>(parent);
            r.follows = !AddonManager.built(w);
            r.key = key;
            Coord c;
            if((v != null) && (v.px == null)) {
                r.fx = v.fx;
                r.fy = v.fy;
                if(area(psz)) {
                    c = Coord.of(place(r.fx, psz.x, w.sz.x), place(r.fy, psz.y, w.sz.y));
                    r.psz = psz;
                } else {
                    c = (def != null) ? def : Coord.z;
                }
            } else {
                c = (v != null) ? v.px : def;
                if(c == null)
                    return null;
                if(area(psz)) {
                    r.fx = frac(c.x, psz.x, w.sz.x, Double.NaN);
                    r.fy = frac(c.y, psz.y, w.sz.y, Double.NaN);
                    r.psz = psz;
                }
            }
            r.at = c;
            recs.put(w, r);
            return c;
        }
    }

    /**
     * <b>Name the key {@code w}'s drop writes</b>, for a window registered before it is placed -- an item's
     * contents window, which has an id from birth and a place only once pinned. {@code null} unnames it.
     */
    public static void key(Widget w, String key) {
        if(w == null)
            return;
        synchronized(WndPos.class) {
            Rec r = recs.get(w);
            if(r == null) {
                r = new Rec();
                r.follows = !AddonManager.built(w);
                recs.put(w, r);
            }
            r.key = key;
        }
    }

    /**
     * <b>The user just put {@code w} down</b> -- a drag of it ended somewhere else ({@code Window.mouseup}), or
     * its grip was let go ({@code Window.resizedByHand}). The one moment a place is written: the fraction is
     * taken from where the window stands now, it follows the screen from here on, and its key, when it has one,
     * is written in this call. Nothing while an addon's layout holds the place: that place is the layout's, and
     * the next fold puts the window back.
     */
    public static void dropped(Widget w) {
        Widget p = (w == null) ? null : w.parent;
        if((p == null) || (w.c == null) || (w.sz == null) || !area(p.sz))
            return;
        String key;
        double[] f;
        synchronized(WndPos.class) {
            if(AddonManager.posHeld(w))
                return;
            Rec r = recs.get(w);
            if(r == null) {
                r = new Rec();
                recs.put(w, r);
            }
            if(r.parent() != p)
                r.parent = new WeakReference<Widget>(p);
            r.fx = frac(w.c.x, p.sz.x, w.sz.x, r.fx);
            r.fy = frac(w.c.y, p.sz.y, w.sz.y, r.fy);
            r.at = w.c;
            r.psz = p.sz;
            r.follows = true;
            key = r.key;
            f = new double[] {r.fx, r.fy};
        }
        if(key != null)
            Utils.setpref(key, text(f));
    }

    /**
     * <b>The user's place for {@code w} at the current size</b> -- what a dropped addon level gives back
     * ({@code UiApi.stockPos}), recorded as placed. {@code null} for a window the rule does not place: one it has
     * not seen, one not on the parent it was seen on, and an addon's window the user never moved.
     */
    public static Coord stock(Widget w) {
        if(w == null)
            return null;
        synchronized(WndPos.class) {
            Rec r = recs.get(w);
            if((r == null) || !r.follows || (r.psz == null))
                return null;
            Widget p = r.parent();
            if((p == null) || (w.parent != p) || !area(p.sz))
                return null;
            sync(w, r);
            if(Double.isNaN(r.fx) || Double.isNaN(r.fy))
                return null;
            Coord c = Coord.of(place(r.fx, p.sz.x, w.sz.x), place(r.fy, p.sz.y, w.sz.y));
            r.at = c;
            r.psz = p.sz;
            return c;
        }
    }

    /**
     * <b>The screen changed size</b>: {@code parent} was {@code was} and is now {@code parent.sz}. Every
     * top-level {@link Window} no addon's layout holds is synced, and each that follows is placed at the new
     * size. A window seen for the first time takes its fraction from where it stands against {@code was}, or
     * against the new size when {@code was} has no area. An item's contents window that is not pinned sits
     * beside its item and is left alone. Every window moved is handed to the geometry seam, so what an addon
     * anchored to it follows on the next step.
     */
    public static void relayout(Widget parent, Coord was) {
        if((parent == null) || !area(parent.sz))
            return;
        Coord psz = parent.sz;
        Coord base = area(was) ? was : psz;
        List<Widget> moved = null;
        synchronized(WndPos.class) {
            for(Widget w = parent.child; w != null; w = w.next) {
                if(!(w instanceof Window))
                    continue;
                if((w instanceof GItem.ContentsWindow) && !((GItem.ContentsWindow)w).pinned())
                    continue;
                if(AddonManager.posHeld(w))
                    continue;
                Rec r = recs.get(w);
                if((r == null) || (r.parent() != parent)) {
                    r = new Rec();
                    r.parent = new WeakReference<Widget>(parent);
                    r.follows = !AddonManager.built(w);
                    r.fx = frac(w.c.x, base.x, w.sz.x, Double.NaN);
                    r.fy = frac(w.c.y, base.y, w.sz.y, Double.NaN);
                    recs.put(w, r);
                } else if(r.psz == null) {
                    if(Double.isNaN(r.fx) || Double.isNaN(r.fy) || !w.c.equals(r.at)) {
                        r.fx = frac(w.c.x, psz.x, w.sz.x, Double.NaN);
                        r.fy = frac(w.c.y, psz.y, w.sz.y, Double.NaN);
                    }
                } else {
                    sync(w, r);
                }
                if(r.follows) {
                    Coord c = Coord.of(place(r.fx, psz.x, w.sz.x), place(r.fy, psz.y, w.sz.y));
                    if(!c.equals(w.c)) {
                        w.move(c);
                        if(moved == null)
                            moved = new ArrayList<Widget>();
                        moved.add(w);
                    }
                }
                r.at = w.c;
                r.psz = psz;
            }
        }
        if(moved != null) {
            for(Widget w : moved)
                AddonManager.onWidgetResized(w);
        }
    }

    /**
     * A {@code c} other than the one the rule recorded means the window was moved: the fraction is retaken from
     * it against the recorded size, and an addon's window follows from then on. Not while an addon's layout
     * holds the place, and not off the parent the window was seen on. Caller holds this class.
     */
    private static void sync(Widget w, Rec r) {
        if((r.psz == null) || (r.at == null) || w.c.equals(r.at))
            return;
        if(w.parent != r.parent())
            return;
        if(AddonManager.posHeld(w))
            return;
        r.fx = frac(w.c.x, r.psz.x, w.sz.x, r.fx);
        r.fy = frac(w.c.y, r.psz.y, w.sz.y, r.fy);
        r.at = w.c;
        r.follows = true;
    }
}
