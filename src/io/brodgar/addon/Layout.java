package io.brodgar.addon;

import haven.Coord;
import haven.GameUI;
import haven.UI;
import haven.Widget;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The <b>layout layer</b> — where the stylesheet's {@code pos} and {@code size} properties reach a widget
 * (spec {@code 036-ui-layout}, feature E, task 036.2). 036.1 made the verbs work on a native widget; this is
 * the same layer with a <b>cascade</b> above it:
 *
 * <pre>
 *   hafen.ui.skin{ ["window[title=Equipment]"] = { pos = {40, 200} } }   -- matched
 *   hafen.ui():find("window[title=Equipment]"):position(40, 200)          -- named by hand
 * </pre>
 *
 * <p><b>One fold, two levels</b> (D-077 verbatim, one property along): a tree rule that names a widget carries
 * its {@code pos}/{@code size} through the very fold {@link Sheet} already runs for {@code font}/{@code color}/
 * {@code bg}/{@code border}/{@code pad} — most specific rule wins, per property — and the <b>verbs sit on top of
 * it</b>, hand-named, latest applied winning. So {@code widget:position(x, y)} on a widget a rule also names wins, and
 * {@code widget:position(nil)} drops back to <i>the rule</i> rather than to the stock value: the undo removes a level,
 * it does not empty the cascade.
 *
 * <p><b>Layout is a WRITE, and that is the whole difference from every other property.</b> The five that came
 * before are read by the provider at the moment a surface draws; a position is a field the client itself owns and
 * a user's drag writes. So this class does not answer a question at draw time — it <b>enforces</b> the resolved
 * answer at the moments it can change, and never inside a draw (035.1's {@code chdeco} lesson):
 * <ul>
 *   <li>a sheet is installed, replaced or dropped, and at teardown ({@link #sweep} over the live tree);</li>
 *   <li>a widget is placed into the tree ({@link #placed}, off the same seam {@code hafen.ui.on} uses — plus
 *       030.2's bounded re-check, because a {@code [title=]} caption arrives by {@code uimsg} a tick late);</li>
 *   <li>a verb is called or undone ({@link #apply} on that one widget);</li>
 *   <li>the geometry an {@link Anchor} <i>derives from</i> changed (036.3) — the screen was resized, or the widget
 *       it hangs off moved, resized or died. That one has no event to hang on: a widget's {@code c} is a public
 *       field the client and the user's own drag write directly, and hooking {@link Widget#move} would put addon
 *       code in the client's hottest path. So the answer is {@link #poll}, a per-tick re-derive over
 *       <b>the widgets an anchor actually names</b> — never the tree — which makes a screen resize, a target
 *       resize and a target move one code path instead of three seams. A move made <i>through this API</i> is
 *       still synchronous: {@link #apply} re-derives whatever hangs off the widget it just wrote.</li>
 * </ul>
 * Nothing here is per frame, and a client with no layout rule and nothing laid out pays two volatile reads a tick.
 *
 * <p><b>What is written down stays the user's</b> (D-086, 036.1): whichever level wins, the widget's stock value
 * is recorded at the layer's first touch ({@link LuaWidget.Moved}) and {@code GameUI}'s position store is answered
 * with <i>that</i>, so the client never persists a rule's position as the user's own preference. Restoring is the
 * same act read backwards — when no level names a half any more, the stock value goes back and the record's half
 * is dropped.
 *
 * <p>Package-private, carries no Lua, and every tree write happens under the {@code ui} monitor. Not instantiable.
 */
final class Layout {
    private Layout() {}

    /**
     * The apply-order stamp of the hand-named level. A rule's rank orders the levels below; between two addons
     * that each called {@code widget:position(x, y)} on one widget the last one wins (D-043, and D-087's <i>a position
     * is not a toggle</i>: two addons may layer over one widget, so the tie has to be broken rather than refused).
     */
    private static long seq = 0;

    static synchronized long nextSeq() {
        return ++seq;
    }

    /** How long a late {@code [title=]}/{@code [res=]} has to land before a placed widget stops being re-checked. */
    private static final int RECHECK_TICKS =
        Integer.getInteger("haven.addon.layoutrecheck", 20).intValue();

    /** One recently-placed widget whose layout rule may still start matching (030.2's re-check, this time for layout). */
    private static final class Pending {
        final Widget wdg;
        final int id;                 // server widget id, or -1 (client-only) — the two-branch death test
        int ticks = RECHECK_TICKS;

        Pending(Widget wdg, int id) {
            this.wdg = wdg;
            this.id = id;
        }
    }

    private static final List<Pending> pending = new CopyOnWriteArrayList<Pending>();

    /**
     * The widgets an anchor is holding <b>derived</b> (036.3) — the ones whose position is a function of geometry
     * that can change under them: the screen's size, another widget's place, or their own. A plain {@code pos} is
     * never here, because {@code {40, 200}} is {@code {40, 200}} whatever else moves.
     *
     * <p>This is the whole cost of the anchor mechanism at rest: {@link #poll} re-derives <i>these</i> widgets and
     * nothing else, so a HUD with two anchored windows pays two folds a tick and a HUD with none pays one
     * {@code isEmpty()}. Weak keys, like every other per-widget map in this layer ({@code Sheet.cache},
     * {@code LuaWidget.Cache}): {@code Widget} overrides neither {@code equals} nor {@code hashCode}, so it is an
     * identity map for free, and a strong one would pin every window an anchor ever named. Guarded by
     * {@code Layout.class}, always taken <b>inside</b> the {@code ui} monitor.
     */
    private static final Map<Widget, Anchor> derived = new WeakHashMap<Widget, Anchor>();

    /** How deep a chain of anchors is followed when one of its links is written (a cycle is a user's to make). */
    private static final int MAXDEPTH = 8;

    /** Is there anything to enforce at all — an installed layout rule, or a widget somebody is standing on? */
    static boolean active() {
        return Sheet.anyLayout || LuaWidget.anyMoved;
    }

    /** Session init / relog: the tree of the session just ended, so nothing is waiting for a caption any more. */
    static void resetSession() {
        pending.clear();
        synchronized(Layout.class) { derived.clear(); }
    }

    // ---- where a widget is placed: ONE property, two spellings (036.3) ------------------------------

    /**
     * <b>Where a widget goes</b> — the one value the position half of the cascade carries. {@code pos = {x, y}} is
     * not a second property beside {@code anchor}: it is the anchor whose target is the widget's <b>own parent</b>,
     * at its top-left, with that offset. Which is exactly the coordinate {@code widget:position()} reads and the client's
     * own {@code c} — so the degenerate case is a real case, there is one resolution path rather than two, and a
     * rule saying {@code pos} and a rule saying {@code anchor} compete for the <i>same</i> half of the same fold
     * instead of each winning one of two.
     *
     * <pre>
     *   anchor = { to = "screen", at = "bottomright", offset = {-8, -8} }   -- 8 px in from the screen's corner
     *   anchor = { to = otherWindow, at = "topright" }                      -- ...or off another widget
     *   pos    = { 40, 200 }                                                -- = to the parent, at = "topleft"
     * </pre>
     *
     * <p><b>The corner is the widget's own as well as the target's</b>: {@code at = "bottomright"} puts the
     * widget's bottom-right corner on the target's bottom-right corner, which is what makes {@code offset =
     * {-8, -8}} read as "8 px in from the edge" rather than "the widget is mostly off-screen". Nine corners, the
     * three positions on each axis, so {@code "center"} is available and needs no separate concept.
     *
     * <p><b>The offset is raw pixels</b> (D-081, one property along from {@code pad} and a border's {@code slice}):
     * a coordinate you write is a pixel you get. That is also what makes an anchor survive a rescale rather than
     * making a second thing that has to: the <i>derivation</i> is what tracks the change, because every quantity it
     * reads — the root's size, the target's box, the widget's own size — is already in the pixels the client is
     * currently drawing in.
     *
     * <p>Immutable, and the widget target is held <b>weakly</b>: an installed rule outlives the windows it names,
     * and an anchor to a window that closed is inert (the widget stays where it is) rather than a pin or a snap.
     */
    static final class Anchor {
        /** What the anchor hangs off: the widget's own parent ({@code pos}), the screen, or another widget. */
        static final int PARENT = 0, SCREEN = 1, WIDGET = 2;
        final int to;
        private final WeakReference<Widget> tgt;   // WIDGET only
        /** The aligned corner, per axis: 0 = left/top, 1 = centre, 2 = right/bottom. */
        final int ax, ay;
        final Coord offset;
        /** Written as {@code pos}: reported back as {@code pos}, and never re-derived — it cannot change. */
        final boolean plain;

        Anchor(int to, Widget tgt, int ax, int ay, Coord offset, boolean plain) {
            this.to = to;
            this.tgt = (tgt == null) ? null : new WeakReference<Widget>(tgt);
            this.ax = ax;
            this.ay = ay;
            this.offset = offset;
            this.plain = plain;
        }

        /** {@code widget:position(x, y)} and a rule's {@code pos = {x, y}}: the parent's top-left, plus that offset. */
        static Anchor at(Coord c) {
            return new Anchor(PARENT, null, 0, 0, c, true);
        }

        /** The widget this anchor hangs off, or {@code null} (not a widget anchor, or the widget is gone). */
        Widget target() {
            return (tgt == null) ? null : tgt.get();
        }

        /** Does this anchor read geometry that can change under it? Then {@link #derived} tracks its widget. */
        boolean dynamic() {
            return !plain;
        }

        /**
         * The parent-relative coordinate this anchor puts {@code w} at right now, or {@code null} when it cannot be
         * resolved at all — no parent, no size yet, or a widget target that has left the tree. Unresolvable is
         * deliberately <b>inert</b>: the widget stays where it is, because snapping it back to stock the moment its
         * anchor's target closed would be a worse answer than leaving it, and the next tick places it again if the
         * target comes back. Caller holds the {@code ui} monitor.
         */
        Coord resolve(UI u, Widget w) {
            Widget p = w.parent;
            if(plain && (p == null))
                return offset;                // a plain pos is a coordinate, not a relationship: it needs no
                                              // target, so a parentless widget (the root) keeps 036.1's answer
            if((u == null) || (u.root == null) || (p == null) || (w.sz == null) || (p.sz == null))
                return null;
            Coord pp = rootPos(u, p);
            if(pp == null)
                return null;
            Coord tp, tsz;
            if(to == SCREEN) {
                tp = Coord.z;                     // the root IS the screen, and its own root position is the origin
                tsz = u.root.sz;
            } else if(to == WIDGET) {
                Widget t = target();
                if((t == null) || (t == w) || (t.sz == null))
                    return null;                  // gone, or anchored to itself: nothing to derive from
                tp = rootPos(u, t);
                tsz = t.sz;
                if(tp == null)
                    return null;
            } else {
                tp = pp;
                tsz = p.sz;
            }
            if(tsz == null)
                return null;
            // The target's aligned corner, then back off the widget's own — so "bottomright" means corner ON
            // corner. Root coordinates throughout, converted to the parent-relative c the client itself keeps.
            Coord want = Coord.of(tp.x + ((tsz.x * ax) / 2) + offset.x - ((w.sz.x * ax) / 2),
                                  tp.y + ((tsz.y * ay) / 2) + offset.y - ((w.sz.y * ay) / 2));
            return want.sub(pp);
        }

        /** {@code widget:style()}: the property as it was written — {@code pos} for the degenerate case. */
        void toLua(Addon reader, LuaTable t) {
            if(plain) {
                t.set("pos", LuaWidget.xyTable(offset));
                return;
            }
            LuaTable a = new LuaTable();
            Widget tw = target();
            a.set("to", (to == WIDGET)
                  ? ((tw == null) ? LuaValue.NIL : LuaWidget.of(reader, tw)) : LuaValue.valueOf("screen"));
            a.set("at", LuaValue.valueOf(CORNERS[(ay * 3) + ax]));
            a.set("offset", LuaWidget.xyTable(offset));
            t.set("anchor", a);
        }
    }

    /** The nine corners, indexed {@code (ay * 3) + ax} — the three positions on each axis, named the CSS way. */
    private static final String[] CORNERS = {
        "topleft",    "top",    "topright",
        "left",       "center", "right",
        "bottomleft", "bottom", "bottomright",
    };

    /**
     * {@code w}'s top-left in root coordinates, or {@code null} when it is not in the tree. {@code parentpos} rather
     * than {@code rootpos()}, which reads the widget's own {@code ui} field — this layer already has the {@link UI}
     * in hand, and the field is one more thing to be null on a widget that is halfway anywhere.
     */
    private static Coord rootPos(UI u, Widget w) {
        if((u == null) || (u.root == null) || (w == null))
            return null;
        if(w == u.root)
            return Coord.z;
        return w.hasparent(u.root) ? w.parentpos(u.root) : null;
    }

    // ---- enforcing one widget's resolved layout ----------------------------------------------------

    /**
     * Resolve {@code w}'s layout and make it so — the one place a position or a size is written. Idempotent: a
     * widget already sitting where the cascade says gets no write at all, which is what lets the placement seam,
     * the re-check and the sweep all call it freely.
     *
     * <p><b>The size half goes first.</b> {@link Widget#resize} notifies {@code parent.cresize}, which is free to
     * re-place the child (036.1 found the inventory's {@code Hidewnd} doing exactly that), so the position must
     * have the last word.
     */
    static void apply(Widget w) {
        apply(w, 0);
    }

    private static void apply(Widget w, int depth) {
        if(w == null)
            return;
        UI u = AddonManager.ui;
        if(u == null)
            return;
        synchronized(u) {
            Sheet.Resolved r = Sheet.styleOf(w);      // ONE fold, read once and used for both halves
            applyHalf(u, w, r, false);
            applyHalf(u, w, r, true);
            // 036.3: ...and whatever hangs off this widget follows it in the same call, so a move made through
            // this API has moved its followers by the time it returns. A user's own drag has no such moment, and
            // that is what poll() is for.
            if(depth < MAXDEPTH)
                applyDependents(u, w, depth);
        }
    }

    /**
     * A widget was moved or resized <b>outside</b> the layer (036.3): an addon's own window, which the verbs write
     * directly because nothing is layered over it. Whatever hangs off it still follows, in the same call — an
     * anchor's target is any widget, and a target the API itself just wrote is the one case that has a moment to
     * hang the re-derive on.
     */
    static void moved(Widget w) {
        UI u = AddonManager.ui;
        if((u == null) || (w == null))
            return;
        synchronized(u) { applyDependents(u, w, 0); }
    }

    /**
     * Re-derive every anchor that hangs off {@code w} (036.3). Bounded by {@link #MAXDEPTH} rather than by a
     * visited set: a chain of anchors is a legitimate thing to write and a cycle is not, so the depth limit ends
     * the cycle without making the ordinary case carry a set. Caller holds the {@code ui} monitor.
     */
    private static void applyDependents(UI u, Widget w, int depth) {
        List<Widget> deps = null;
        synchronized(Layout.class) {
            if(derived.isEmpty())
                return;
            for(Map.Entry<Widget, Anchor> e : derived.entrySet()) {
                if((e.getValue().to != Anchor.WIDGET) || (e.getValue().target() != w) || (e.getKey() == w))
                    continue;
                if(deps == null)
                    deps = new ArrayList<Widget>(2);
                deps.add(e.getKey());
            }
        }
        for(int i = 0; (deps != null) && (i < deps.size()); i++)
            apply(deps.get(i), depth + 1);
    }

    /**
     * One half of one widget's layout: the winning value from the cascade (a tree rule, then the hand-named verb
     * on top), written through the same call the verb makes — or, when no level names this half any more, the
     * stock value handed back and the record's half forgotten. Caller holds the {@code ui} monitor.
     */
    private static void applyHalf(UI u, Widget w, Sheet.Resolved r, boolean pos) {
        Anchor place = null;
        Coord want = null;
        Addon owner = null;
        if(r != null) {                               // the MATCHED level: every tree rule that names this widget
            place = pos ? r.pos : null;
            want = pos ? null : r.size;
            owner = pos ? r.posOwner : r.sizeOwner;
        }
        LuaWidget.Moved top = LuaWidget.topWant(w, pos);   // ...and the HAND-NAMED level above it (D-077)
        if(top != null) {
            place = pos ? top.wantPos : null;
            want = pos ? null : top.wantSize;
            owner = top.owner;
        }
        if(pos) {
            track(w, place);                          // 036.3: is this widget's place DERIVED from something?
            if(place != null) {
                want = place.resolve(u, w);
                if(want == null)
                    return;                           // an anchor that cannot be resolved right now is INERT
                want = fit(u, w, want);               // ...and the client's own clamp has the last word
            }
        }
        if(want != null) {
            LuaWidget.Moved rec = LuaWidget.recordMoved(owner, w);
            if(pos) {
                if(rec.pos == null)                   // the stock value, at the LAYER's first touch and only then:
                    rec.pos = UiApi.stockPos(w);      //   what the user had, whoever asks about it later
                if(!want.equals(w.c))
                    w.move(want);
            } else {
                if(rec.size == null)
                    rec.size = UiApi.stockSizeArg(w);
                if(!want.equals(LuaWidget.sizeArg(w)))
                    w.resize(want);
            }
            return;
        }
        Coord stock = pos ? UiApi.stockPos(w) : UiApi.stockSizeArg(w);   // READ before the halves are dropped
        if(!LuaWidget.dropStock(w, pos))              // nothing of ours was standing here: not our business
            return;
        if(pos) {
            if(!stock.equals(w.c))
                w.move(stock);
        } else if(!stock.equals(LuaWidget.sizeArg(w))) {
            w.resize(stock);
        }
    }

    /** Start (or stop) re-deriving {@code w}'s place each tick — see {@link #derived}. Under the {@code ui} monitor. */
    private static void track(Widget w, Anchor a) {
        synchronized(Layout.class) {
            if((a != null) && a.dynamic())
                derived.put(w, a);
            else if(!derived.isEmpty())
                derived.remove(w);
        }
    }

    /**
     * <b>The client's own clamp, on the widgets the client itself clamps</b> (036.3). {@code GameUI.fitwdg} keeps
     * a window graspable — at least {@code min(100 px, its own size)} of it stays inside the parent — and runs when
     * the client places or toggles one. A layout rule is another way of placing those same windows, so it is handed
     * to the same rule rather than to a second answer to "is this on screen": the formula is {@link UiApi#fitc},
     * shared with the one 031 already re-derived for a replacement view.
     *
     * <p>It is applied where the client applies it — to a widget the HUD or the root holds directly, which is what
     * a top-level window is. A widget <i>inside</i> a window is laid out by that window and is not the client's to
     * clamp, so an addon that moves one gets the pixels it asked for.
     */
    private static Coord fit(UI u, Widget w, Coord c) {
        Widget p = w.parent;
        if((p == null) || ((p != u.root) && !(p instanceof GameUI)))
            return c;
        return UiApi.fitc(w, c);
    }

    // ---- the events layout re-derives on ------------------------------------------------------------

    /**
     * Re-derive <b>everything</b>: the installed rules changed (a sheet was applied, replaced or dropped), so every
     * widget in the tree is offered to the cascade again — the ones a new rule now names, and the ones an old rule
     * no longer does, which is what makes dropping a sheet restore the exact numbers it found.
     *
     * <p>Called from {@link Sheet} <b>outside</b> its own lock: matching takes {@code Sheet.class} while holding the
     * {@code ui} monitor (the order the draw pass established), so a sweep that already held {@code Sheet.class}
     * would be the one path able to invert it.
     */
    static void sweep() {
        if(!active())
            return;                                   // no rule, nothing held: a stock client sweeps nothing
        UI u = AddonManager.ui;
        if((u == null) || (u.root == null))
            return;
        synchronized(u) {
            List<Widget> all = new ArrayList<Widget>();
            collect(u.root, all);                     // collected first: applying writes c/sz, never the tree, but
            for(int i = 0; i < all.size(); i++)       //   a snapshot is what makes that a guarantee rather than a hope
                apply(all.get(i));
        }
    }

    private static void collect(Widget w, List<Widget> out) {
        out.add(w);
        for(Widget c = w.child; c != null; c = c.next)
            collect(c, out);
    }

    /**
     * A widget was just placed into the tree ({@code UiApi.onWidgetPlaced}, inside {@code AddWidget.run}'s
     * {@code synchronized(ui)}): lay it out now, and queue it for the bounded re-check when a layout rule could
     * still start matching it — role and class are fixed for a widget's life, but a caption arrives by
     * {@code uimsg} and a resource resolves asynchronously, so {@code ["window[title=Equipment]"]} would otherwise
     * miss the very window it names.
     */
    static void placed(Widget w, int id) {
        apply(w);
        if(Sheet.lateLayoutCandidate(w))
            pending.add(new Pending(w, id));
    }

    /**
     * Per-tick work (UI thread): re-offer the recently-placed candidates still waiting for a late caption, then
     * drop the records of widgets that have left the tree. Both halves are gated, so an idle client pays two
     * {@code isEmpty()}-shaped reads a tick and the cost of the mechanism scales with widget creation, not frames.
     */
    static void poll() {
        UI u = AddonManager.ui;
        if((u == null) || (u.root == null))
            return;
        if(!pending.isEmpty()) {
            for(Pending p : pending) {                // copy-on-write: entries drop out as we go
                if(!alive(u, p.wdg, p.id)) {
                    pending.remove(p);                // it died before its caption arrived
                    continue;
                }
                if(--p.ticks <= 0)
                    pending.remove(p);                // ...still offered this one last time
                apply(p.wdg);
            }
        }
        redrive(u);
        if(LuaWidget.anyMoved)
            LuaWidget.pruneMoved(u);
    }

    /**
     * Re-derive the anchored widgets (036.3): the screen may have been resized, a target may have been dragged, a
     * window may have packed itself around new contents. Over {@link #derived} alone — never the tree — and
     * {@link #apply} writes only when the answer actually changed, so a HUD whose anchors are all where the
     * cascade wants them does a handful of folds and no writes at all.
     */
    private static void redrive(UI u) {
        List<Widget> ws;
        synchronized(Layout.class) {
            if(derived.isEmpty())
                return;
            ws = new ArrayList<Widget>(derived.keySet());
        }
        for(int i = 0; i < ws.size(); i++) {
            Widget w = ws.get(i);
            if(w.hasparent(u.root))
                apply(w);
            else
                synchronized(Layout.class) { derived.remove(w); }   // it left the tree: nothing to derive for
        }
    }

    /** Is a pending candidate still the same live widget? (Server-bound: by id; client-only: by reachability.) */
    static boolean alive(UI u, Widget w, int id) {
        return (id >= 0) ? (u.getwidget(id) == w) : w.hasparent(u.root);
    }

    // ---- parsing -----------------------------------------------------------------------------------

    /**
     * Parse a rule's {@code pos = {x, y}} / {@code size = {w, h}} — the same two spellings a colour and a border's
     * slice take ({@code {40, 200}} or {@code {x = 40, y = 200}}), for the same reason: the positional form is what
     * a hand-written rule and a {@code theme.json} say, the keyed form is what {@code widget:position()} and
     * {@code widget:style()} hand back, so a read round-trips into a write unchanged.
     *
     * <p><b>Raw pixels</b> (D-081): a coordinate you write is a pixel you get, exactly as {@code pad}, a border's
     * slice and {@code ui.window{size=}} already are.
     */
    static Coord parseCoord(String ctx, String prop, LuaValue v) {
        boolean size = "size".equals(prop);
        String shape = size ? "{width, height}" : "{x, y}";
        if(!v.istable())
            throw new LuaError(ctx + "." + prop + ": expected " + shape + " — " + prop + " = "
                + (size ? "{300, 200}" : "{40, 200}") + " or " + prop + " = "
                + (size ? "{ x = 300, y = 200 }" : "{ x = 40, y = 200 }") + ", got " + v.typename());
        // type() rather than isnumber(): in LuaJ a STRING that looks like a number answers isnumber() (the 028
        // asset lesson) — pos = {"40", "200"} is a typo, not a position.
        LuaValue x = v.get("x"), y = v.get("y");
        if((x.type() != LuaValue.TNUMBER) || (y.type() != LuaValue.TNUMBER)) {
            x = v.get(1);
            y = v.get(2);
            if((x.type() != LuaValue.TNUMBER) || (y.type() != LuaValue.TNUMBER))
                throw new LuaError(ctx + "." + prop + ": expected two numbers — " + shape + " or "
                    + (size ? "{ x = …, y = … }" : "{ x = …, y = … }"));
        }
        if(size && ((x.toint() < 0) || (y.toint() < 0)))
            throw new LuaError(ctx + ".size: a size cannot be negative (got " + x.toint() + "x" + y.toint() + ")");
        return Coord.of(x.toint(), y.toint());
    }

    /**
     * Parse a rule's {@code anchor = {to = "screen" | <widget>, at = "bottomright", offset = {dx, dy}}} (036.3).
     * Every field has a default, because each one has an obvious one: the screen, its top-left, and no offset —
     * so {@code anchor = {at = "center"}} centres a window on the screen and says nothing it does not mean.
     *
     * <p>An unknown field or an unknown corner is an <b>error</b>, for D-072's reason: a misspelt
     * {@code "bottomrigth"} has no future meaning to wait for, and silently placing the window in the top-left is
     * the worst possible answer to a typo.
     */
    static Anchor parseAnchor(String ctx, LuaValue v) {
        if(!v.istable())
            throw new LuaError(ctx + ".anchor: expected { to = \"screen\", at = \"bottomright\","
                + " offset = {-8, -8} }, got " + v.typename()
                + " — every field has a default: the screen, its top-left, and no offset");
        int to = Anchor.SCREEN;
        Widget tgt = null;
        int corner = 0;
        Coord offset = Coord.z;
        LuaValue k = LuaValue.NIL;
        while(true) {
            Varargs n = v.next(k);
            k = n.arg1();
            if(k.isnil())
                break;
            String p = (!k.isnumber() && k.isstring()) ? k.tojstring() : null;
            LuaValue pv = n.arg(2);
            if("to".equals(p)) {
                LuaWidget h = LuaWidget.resolve(pv);
                if(h != null) {
                    to = Anchor.WIDGET;
                    tgt = h.wdg;
                    if(tgt == null)
                        throw new LuaError(ctx + ".anchor.to: that widget no longer exists — an anchor holds its"
                            + " target weakly, so name a live one (widget:exists() says which)");
                } else if("screen".equals((pv.isstring() && !pv.isnumber()) ? pv.tojstring() : null)) {
                    to = Anchor.SCREEN;
                } else {
                    throw new LuaError(ctx + ".anchor.to: expected \"screen\" or a widget"
                        + " — hafen.ui():find(\"window[title=Inventory]\"), got " + pv.typename());
                }
            } else if("at".equals(p)) {
                corner = cornerOf(ctx, pv);
            } else if("offset".equals(p)) {
                offset = parseCoord(ctx + ".anchor", "offset", pv);
            } else {
                throw new LuaError(ctx + ".anchor: \"" + k.tojstring() + "\" is not an anchor field"
                    + " — an anchor is { to = …, at = …, offset = {dx, dy} }");
            }
        }
        return new Anchor(to, tgt, corner % 3, corner / 3, offset, false);
    }

    /** One of the nine corner names, as its {@link #CORNERS} index. Anything else is a typo, and says so. */
    private static int cornerOf(String ctx, LuaValue v) {
        String s = (v.isstring() && !v.isnumber()) ? v.tojstring() : null;
        for(int i = 0; (s != null) && (i < CORNERS.length); i++) {
            if(CORNERS[i].equals(s))
                return i;
        }
        StringBuilder sb = new StringBuilder();
        for(int i = 0; i < CORNERS.length; i++)
            sb.append((i == 0) ? "" : ", ").append('"').append(CORNERS[i]).append('"');
        throw new LuaError(ctx + ".anchor.at: expected one of " + sb + ", got "
            + ((s == null) ? v.typename() : ("\"" + s + "\"")));
    }
}
