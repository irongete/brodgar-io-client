package io.brodgar.addon;

import haven.Coord;
import haven.EventHandler;
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

/**
 * The <b>layout layer</b> — where the stylesheet's {@code position} and {@code size} properties reach a widget
 * (spec {@code 036-ui-layout}, feature E, task 036.2). 036.1 made the verbs work on a native widget; this is
 * the same layer with a <b>cascade</b> above it:
 *
 * <pre>
 *   hafen.ui():sheet():rule("window[title=Equipment]"):position(40, 200)   -- matched
 *   s:ui():find("window[title=Equipment]"):position(40, 200)            -- named by hand
 * </pre>
 *
 * <p><b>One fold, two levels</b> (D-077 verbatim, one property along): a tree rule that names a widget carries
 * its {@code position}/{@code size} through the very fold {@link Sheet} already runs for {@code font}/{@code color}/
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
 *   <li>a widget is placed into the tree ({@link #placed}, off the same seam {@code s:ui():on} uses — plus
 *       030.2's bounded re-check, because a {@code [title=]} caption arrives by {@code uimsg} a tick late);</li>
 *   <li>a verb is called or undone ({@link #apply} on that one widget);</li>
 *   <li>the geometry an {@link Anchor} <i>derives from</i> changed (036.3, event-driven since 042.10/D-181) —
 *       the screen was resized, the widget it hangs off resized or packed itself ({@link #dispatchResized},
 *       the {@code Widget.resize} core tap — {@code move()} is never hooked, and is not a chokepoint anyway:
 *       a drag writes {@code c} directly), or was dragged ({@link #installDragListener}, a {@code Widget.listen}
 *       on that one target — a zero-edit seam, not the client's hottest path), or left the tree
 *       ({@link #dispatchRemoved}, M1). A move made <i>through this API</i> is still synchronous: {@link #apply}
 *       re-derives whatever hangs off the widget it just wrote.</li>
 * </ul>
 * Nothing here is per frame: a client with no layout rule and nothing laid out pays no seam at all, and one with
 * nothing anchored pays a fast {@code derived.isEmpty()} on the geometry/removal seams it never triggers.
 *
 * <p><b>What is written down stays the user's</b> (D-086, 036.1): whichever level wins, the widget's stock value
 * is recorded at the layer's first touch ({@link LuaWidget.Moved}) and {@code GameUI}'s position store is answered
 * with <i>that</i>, so the client never persists a rule's position as the user's own preference. Restoring is the
 * same act read backwards — when no level names a half any more, the stock value goes back and the record's half
 * is dropped.
 *
 * <p>Package-private, carries no Lua, and every tree write happens under the monitor of the {@code UI} the
 * widget being written belongs to — {@link LuaWidget#monitor}, never an ambient one. Not instantiable.
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
    static final class Pending {
        final Widget wdg;
        final int id;                 // server widget id, or -1 (client-only) — the two-branch death test
        int ticks = RECHECK_TICKS;

        Pending(Widget wdg, int id) {
            this.wdg = wdg;
            this.id = id;
        }
    }

    // 073.2: the list is ONE TREE'S ({@code SessionState.layoutPending}). It holds widgets waiting for a
    // caption of their own session, filled at the placement seam from w.ui — the thread there is a Loader
    // thread of whichever session sent the message, so screen() would queue one session's window for another
    // session's re-check, and that re-check would then walk a tree the widget is not in.


    /**
     * The widgets an anchor is holding <b>derived</b> (036.3) — the ones whose position is a function of geometry
     * that can change under them: the screen's size, another widget's place, or their own. A plain {@code position}
     * is
     * never here, because {@code {40, 200}} is {@code {40, 200}} whatever else moves.
     *
     * <p>This is the whole cost of the anchor mechanism at rest (042.10): {@link #dispatchResized}/{@link
     * #installDragListener} re-derive <i>these</i> widgets, on their own inputs' events, and nothing else, so a
     * HUD with two anchored windows pays two folds per actual move and a HUD with none pays one
     * {@code isEmpty()} per resize/removal and no per-tick cost at all. Weak keys, like every other per-widget
     * map in this layer ({@code Sheet.cache},
     * {@code LuaWidget.Cache}): {@code Widget} overrides neither {@code equals} nor {@code hashCode}, so it is an
     * identity map for free, and a strong one would pin every window an anchor ever named. Guarded by
     * {@code Layout.class}, always taken <b>inside</b> the widget's own {@code UI} monitor.
     */
    private static final Map<Widget, Anchor> derived = new WeakHashMap<Widget, Anchor>();

    /**
     * The ONE {@code MouseMoveEvent} listener installed per WIDGET target a live anchor names — never per
     * follower (042.10, M4's drag half). {@code Widget.listen}/{@code deafen} is the same zero-edit engine
     * seam {@link WidgetSubs} already uses for its own input keys (041.3): a target's own drag writes {@code
     * c} directly (D-091's own observation, still true), so there is no "moved" event to wait for — only the
     * pointer events the drag itself generates on the widget being dragged. The handler never cancels
     * anything (always returns {@code false}), so it is purely an observer riding alongside whatever the
     * target's own {@code handle} already does. Weak keys: a dead target needs no explicit teardown, since
     * its {@code listening} list dies with it. Guarded by {@link Layout#class}, always mutated together with
     * {@link #derived} by {@link #retarget}.
     */
    private static final Map<Widget, EventHandler<Widget.MouseMoveEvent>> dragListeners =
        new WeakHashMap<Widget, EventHandler<Widget.MouseMoveEvent>>();

    /** How deep a chain of anchors is followed when one of its links is written (a cycle is a user's to make). */
    private static final int MAXDEPTH = 8;

    /** Is there anything to enforce at all — an installed layout rule, or a widget somebody is standing on? */
    static boolean active() {
        return Sheet.anyLayout || LuaWidget.anyMoved;
    }

    // ---- where a widget is placed: ONE property, two spellings (036.3) ------------------------------

    /**
     * <b>Where a widget goes</b> — the one value the position half of the cascade carries.
     * {@code position = {x, y}} is not a second property beside {@code anchor}: it is the anchor whose target is the widget's <b>own parent</b>,
     * at its top-left, with that offset. Which is exactly the coordinate {@code widget:position()} reads and the client's
     * own {@code c} — so the degenerate case is a real case, there is one resolution path rather than two, and a
     * rule saying {@code position} and one saying {@code anchor} compete for the <i>same</i> half of the same fold
     * instead of each winning one of two.
     *
     * <pre>
     *   anchor = { to = "screen", at = "bottomright", offset = {-8, -8} }   -- 8 px in from the screen's corner
     *   anchor = { to = otherWindow, at = "topright" }                      -- ...or off another widget
     *   position = { 40, 200 }                                              -- = to the parent, at = "topleft"
     * </pre>
     *
     * <p><b>The corner is the widget's own as well as the target's</b>: {@code at = "bottomright"} puts the
     * widget's bottom-right corner on the target's bottom-right corner, which is what makes {@code offset =
     * {-8, -8}} read as "8 px in from the edge" rather than "the widget is mostly off-screen". Nine corners, the
     * three positions on each axis, so {@code "center"} is available and needs no separate concept.
     *
     * <p><b>The offset is DESIGN pixels</b> (058.3, one property along from {@code pad} and a border's
     * {@code slice}): the space the whole of {@code hafen.ui} measures in, so {@code offset = {-8, -8}} is 8 of the
     * same pixels {@code widget:position(x, y)} takes, on every client. It is held in that space and converted
     * once, in {@link #resolve} — the one place it meets the client's own geometry, which is device throughout (the
     * root's size, the target's box, the widget's own size). So the <i>derivation</i> is what tracks a resize, and
     * the offset is the only term in it with a unit to convert.
     *
     * <p>Immutable, and the widget target is held <b>weakly</b>: an installed rule outlives the windows it names,
     * and an anchor to a window that closed is inert (the widget stays where it is) rather than a pin or a snap.
     */
    static final class Anchor {
        /** What the anchor hangs off: the widget's own parent ({@code position}), the screen, or another widget. */
        static final int PARENT = 0, SCREEN = 1, WIDGET = 2;
        final int to;
        private final WeakReference<Widget> tgt;   // WIDGET only
        /** The aligned corner, per axis: 0 = left/top, 1 = centre, 2 = right/bottom. */
        final int ax, ay;
        final Coord offset;
        /** Written as {@code position}: reported back the same way, and never re-derived — it cannot change. */
        final boolean plain;

        Anchor(int to, Widget tgt, int ax, int ay, Coord offset, boolean plain) {
            this.to = to;
            this.tgt = (tgt == null) ? null : new WeakReference<Widget>(tgt);
            this.ax = ax;
            this.ay = ay;
            this.offset = offset;
            this.plain = plain;
        }

        /**
         * {@code widget:position(x, y)} and a rule's own {@code position}: the parent's top-left, plus that offset,
         * in the design pixels both of them are written in.
         */
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
         * target comes back. Caller holds {@code w}'s own monitor ({@link LuaWidget#monitor}).
         *
         * <p><b>The answer is DEVICE pixels</b>, because it is a coordinate the client's own {@code c} takes: this
         * is where {@link #offset} — the one design number in the derivation — converts (058.3), and every other
         * term is read off the tree in the space the tree is laid out in.
         */
        Coord resolve(UI u, Widget w) {
            Coord off = Px.in(offset);        // design → device, once: everything below is the client's own space
            Widget p = w.parent;
            if(plain && (p == null))
                return off;                   // a plain pos is a coordinate, not a relationship: it needs no
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
            Coord want = Coord.of(tp.x + ((tsz.x * ax) / 2) + off.x - ((w.sz.x * ax) / 2),
                                  tp.y + ((tsz.y * ay) / 2) + off.y - ((w.sz.y * ay) / 2));
            return want.sub(pp);
        }

        /** {@code widget:style()}: the property as it was written — {@code position} for the degenerate case. */
        void toLua(Addon reader, LuaTable t) {
            if(plain) {
                t.set("position", LuaWidget.xyTable(offset));
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

    /**
     * The nine corners, indexed {@code (ay * 3) + ax}. They live in {@link Chrome} because a corner is not a
     * layout idea: a rule's {@code anchor} picks one of a <i>widget</i>, and a surface's {@code at} picks one of
     * the box it is painted on. One vocabulary, one error message, one place to read it from.
     */
    private static final String[] CORNERS = Chrome.CORNERS;

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

    /**
     * <b>The {@code UI} is {@code w}'s own</b> (072.2), and it is one read answering both questions this method
     * asks of it: which monitor guards the write, and which tree the anchor derivation measures in. Neither is a
     * question about the session on screen — the widget being written is not always the drawn one — so
     * {@link AddonManager#screen()} would be the wrong answer to both, and the widget has carried the right one
     * all along. An unattached widget has a null {@code ui} and gets {@link LuaWidget#monitor}'s stand-in,
     * exactly as it did before this task: it still takes its text and its style, and every step of the geometry
     * that needs a tree already guards on {@code u} being null.
     */
    private static void apply(Widget w, int depth) {
        if(w == null)
            return;
        UI u = w.ui;
        synchronized(LuaWidget.monitor(w)) {
            textHalf(w);                              // 061.5: WHAT it says, before the box it says it in
            Sheet.Resolved r = Sheet.styleOf(w);      // ONE fold, read once and used for both halves
            applyHalf(u, w, r, false);
            applyHalf(u, w, r, true);
            // 036.3: ...and whatever hangs off this widget follows it in the same call, so a move made through
            // this API has moved its followers by the time it returns. A user's own drag has no such moment —
            // it is seen instead through the target's own MouseMoveEvent (042.10, installDragListener).
            if(depth < MAXDEPTH)
                applyDependents(w, depth);
        }
    }

    /**
     * A widget was moved or resized <b>outside</b> the layer (036.3): an addon's own window, which the verbs write
     * directly because nothing is layered over it. Whatever hangs off it still follows, in the same call — an
     * anchor's target is any widget, and a target the API itself just wrote is the one case that has a moment to
     * hang the re-derive on.
     */
    static void moved(Widget w) {
        if(w == null)
            return;
        synchronized(LuaWidget.monitor(w)) { applyDependents(w, 0); }
    }

    /**
     * Re-derive every anchor that hangs off {@code w} (036.3). Bounded by {@link #MAXDEPTH} rather than by a
     * visited set: a chain of anchors is a legitimate thing to write and a cycle is not, so the depth limit ends
     * the cycle without making the ordinary case carry a set. Caller holds {@code w}'s own monitor
     * ({@link LuaWidget#monitor}).
     */
    private static void applyDependents(Widget w, int depth) {
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
     * stock value handed back and the record's half forgotten. Caller holds {@code w}'s own monitor
     * ({@link LuaWidget#monitor}).
     *
     * <p><b>Both levels of the cascade carry DESIGN pixels</b> (058.3) — a rule's {@code size} as it was parsed,
     * the verb's as it was written — and each half converts at the one line where it meets the client: an
     * {@link Anchor} inside {@link Anchor#resolve}, since the rest of that derivation is the tree's own device
     * geometry, and a size right here, on its way into {@link Widget#resize}. The <b>stock</b> values are the
     * client's own and stay device throughout ({@link LuaWidget.Moved}), so a restore never round-trips through
     * design and back.
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
                want = place.resolve(u, w);           // ...and it comes back in the client's own device pixels
                if(want == null)
                    return;                           // an anchor that cannot be resolved right now is INERT
                want = fit(u, w, want);               // ...and the client's own clamp has the last word
            }
        } else if(want != null) {
            want = Px.in(want);                       // design → device, where a rule's size meets Widget.resize
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

    /**
     * <b>What a widget SAYS</b> (061.5) — the text level, resolved the way the two geometry halves above are and
     * with one level fewer: there is no rule beneath it, because content is not style. The winning level is the
     * latest one any addon named ({@link LuaWidget#topWantText}); when none is left the stock caption goes back
     * and every owner's record forgets it.
     *
     * <p><b>The stock half is recorded at the LAYER's first touch</b> and read through {@link UiApi#stockText},
     * so a second addon writing over the first records what the <i>user</i> had rather than what the first addon
     * wrote — the same answer {@code stockPos} gives one property along.
     *
     * <p>Idempotent, like the halves below: a widget already saying what the cascade says gets no write at all,
     * which is what lets the placement seam and the sweep call {@link #apply} freely — a {@code Button} rebuilds
     * its raster on every {@code change(String)}, so the comparison is not an optimisation but the thing that
     * keeps a caption off the per-placement path. Caller holds {@code w}'s own monitor ({@link LuaWidget#monitor}).
     */
    private static void textHalf(Widget w) {
        if(!LuaWidget.anyMoved)
            return;                                   // nobody is holding anything: one volatile read, and out
        LuaWidget.Moved top = LuaWidget.topWantText(w);
        if(top != null) {
            LuaWidget.Moved rec = LuaWidget.recordMoved(top.owner, w);
            if(rec.text == null)
                rec.text = UiApi.stockText(w);        // what the user had, whoever asks about it later
            if(!top.wantText.equals(LuaWidget.text(w)))
                LuaWidget.writeText(w, top.wantText);
            return;
        }
        LuaWidget.Cap stock = UiApi.stockText(w);     // READ before the records are dropped
        if(!LuaWidget.dropStockText(w))
            return;                                   // nothing of ours was standing here: not our business
        LuaWidget.writeCap(w, stock);
    }

    /**
     * The text level on its own, for the verb that names it and the one that drops it — {@code widget:text(s)} /
     * {@code :text(nil)} and {@code widget:title(s)} / {@code :title(nil)}. Takes the {@code ui} monitor itself.
     */
    static void applyText(Widget w) {
        if(w == null)
            return;
        synchronized(LuaWidget.monitor(w)) { textHalf(w); }
    }

    /**
     * <b>The server rewrote what {@code w} says</b> (061.6) — the UI-thread half of the inbound tap
     * ({@link AddonManager#onUimsg}, which runs on a Loader thread outside the {@code ui} monitor and so may
     * only record the widget). Two writes, in this order and in this one frame: what the widget is showing
     * becomes the <b>stock</b> caption every standing owner gives back, and then this addon's level goes on
     * top of it again. So an addon's caption does not vanish minutes later from a message nobody saw, and
     * {@code widget:text(nil)} afterwards hands back the server's <i>latest</i> value rather than a stale one.
     *
     * <p><b>A widget already saying what the level says is left alone</b>, and that guard is the whole safety
     * of the re-read: between the update landing and this drain, a placement or a sweep may have put the level
     * back on already, and re-reading then would record <i>our own</i> caption as the user's. The cost is the
     * degenerate case where the server sends exactly the string the level holds, which changes nothing on
     * screen and leaves the stock at the value that update replaced.
     */
    static void serverWroteText(Widget w) {
        if(w == null)
            return;
        synchronized(LuaWidget.monitor(w)) {
            LuaWidget.Moved top = LuaWidget.topWantText(w);
            if((top == null) || top.wantText.equals(LuaWidget.text(w)))
                return;                               // nothing of ours here, or nothing landed on top of it
            LuaWidget.restockText(w);                 // what it says at THIS instant is the server's own value
            textHalf(w);                              // ...and this addon's level goes back over it
        }
    }

    /**
     * Start (or stop) re-deriving {@code w}'s place on its target's own events — see {@link #derived}. Under
     * the {@code ui} monitor.
     */
    private static void track(Widget w, Anchor a) {
        synchronized(Layout.class) { retarget(w, a); }
    }

    /**
     * The one place {@link #derived} and {@link #dragListeners} change together (042.10): record (or drop)
     * {@code w}'s resolved anchor, and make sure exactly one drag listener exists on whatever WIDGET target a
     * live anchor still names — installing one the moment a first anchor points there, dropping it the moment
     * the last one stops. {@code next == null} is "nothing governs {@code w} any more", the same call {@link
     * #dispatchRemoved} makes for a widget that just left the tree. Caller holds {@link Layout#class}.
     */
    private static void retarget(Widget w, Anchor next) {
        Anchor prev = derived.get(w);
        Widget prevTarget = dragTargetOf(prev);
        if((next != null) && next.dynamic())
            derived.put(w, next);
        else
            derived.remove(w);
        Widget nextTarget = dragTargetOf(next);
        if(nextTarget == prevTarget)
            return;
        if(nextTarget != null)
            dragListeners.computeIfAbsent(nextTarget, Layout::installDragListener);
        if((prevTarget != null) && !dragTargetStillNamed(prevTarget))
            dropDragListener(prevTarget);
    }

    /** The WIDGET {@code a} anchors to, or {@code null} for a plain/screen anchor or none at all. */
    private static Widget dragTargetOf(Anchor a) {
        return ((a != null) && a.dynamic() && (a.to == Anchor.WIDGET)) ? a.target() : null;
    }

    /** Does any entry left in {@link #derived} still anchor to {@code t}? Caller holds {@link Layout#class}. */
    private static boolean dragTargetStillNamed(Widget t) {
        for(Anchor a : derived.values()) {
            if(dragTargetOf(a) == t)
                return true;
        }
        return false;
    }

    /**
     * Install {@code t}'s one drag-observing listener (042.10) — never cancels anything, only re-derives.
     *
     * <p><b>Marshalled, not inline — and not for threading.</b> {@code Widget.handle(Event)} checks {@code
     * listening} BEFORE {@code ev.shandle(this)}, which is what actually runs {@code Window.mousemove} ->
     * {@code move(...)}: a listener that re-derives inline here reads the target's position from BEFORE this
     * event moves it, one event stale for the whole drag. Enqueuing onto the SAME queue {@link
     * AddonManager#onWidgetResized} already drains fixes it for free: {@code UILoop.Frame.tick} runs {@code
     * loop.dispatch(ui)} (input, including this mousemove) BEFORE {@code ui.tick()} (which drains this
     * queue), so by the time the drain runs the move this event caused has already landed — same frame, not
     * a frame later.
     */
    private static EventHandler<Widget.MouseMoveEvent> installDragListener(final Widget t) {
        EventHandler<Widget.MouseMoveEvent> h = new EventHandler<Widget.MouseMoveEvent>() {
            public boolean handle(Widget.MouseMoveEvent ev) {
                AddonManager.onWidgetResized(t);
                return false;
            }
        };
        t.listen(Widget.MouseMoveEvent.class, h);
        return h;
    }

    /** Drop {@code t}'s drag listener — the last anchor pointing at it just went. Best-effort: {@code t} may
     *  already be gone, in which case there is nothing left to deafen. */
    private static void dropDragListener(Widget t) {
        EventHandler<Widget.MouseMoveEvent> h = dragListeners.remove(t);
        if(h == null)
            return;
        try {
            t.deafen(h);
        } catch(RuntimeException e) { /* t is already gone: its own listener list went with it */ }
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
        if((p == null) || (u == null) || ((p != u.root) && !(p instanceof GameUI)))
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
     *
     * <p><b>The tree on screen</b> ({@link AddonManager#screen()}), and that is a limit rather than an address: a
     * sheet is the addon's own declaration and matches in every session, but the re-derivation of what is ALREADY
     * placed runs here alone, while {@link #placed} carries a new widget in any session. A window a background
     * character already had open takes a rule installed now on its next placement, not on this sweep.
     */
    static void sweep() {
        if(!active())
            return;                                   // no rule, nothing held: a stock client sweeps nothing
        UI u = AddonManager.screen();
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
        if(!Sheet.lateLayoutCandidate(w))
            return;
        AddonManager.SessionState st = AddonManager.state(w.ui);   // 073.2: the tree it was placed into
        if(st != null)
            st.layoutPending.add(new Pending(w, id));
    }

    // 042.10: `pending`'s late caption/res is woken by the window "cap" uimsg (CharApi.dispatchUimsg ->
    // markCaptionChanged), exactly like UiApi's own selector re-check (030.2/042.9) does for the same tap. That
    // tap runs off the UI thread (UI.java:730-732 closes synchronized(ui) before calling AddonManager.onUimsg),
    // so it may only set a flag — the actual widget reads happen on the tick, under synchronized(ui).
    // 073.2: the FLAG stays one for the client (census.md: it says some caption changed and gates the
    // re-check, never says whose or decides what it finds) — the list it gates is the per-session one above.
    private static volatile boolean capDirty;

    /** Mark that some window's caption changed (from {@code CharApi.dispatchUimsg}, window "cap" message). */
    static void markCaptionChanged() {
        capDirty = true;
    }

    /**
     * Tick-side drain (UI thread, {@link AddonManager#tick}): if a caption changed since the last tick,
     * re-offer every pending widget once — the same shape as {@link UiApi#drainSelectorCaptionCheck}. Gated on
     * both the flag and the session's own pending list being non-empty, so an idle client — or one with no
     * late-refiner
     * layout rule at all — pays only the flag check and one {@code isEmpty()}.
     */
    static void drainPendingCaption(AddonManager.SessionState st) {
        if(!capDirty)
            return;
        capDirty = false;
        if(st.layoutPending.isEmpty())
            return;
        UI u = st.ui;                             // 073.2: the tree whose tick this is
        if(u.root == null)
            return;
        for(Pending p : st.layoutPending) {       // copy-on-write: entries drop out as we go
            if(!alive(u, p.wdg, p.id)) {
                st.layoutPending.remove(p);       // it died before its caption arrived
                continue;
            }
            if(--p.ticks <= 0)
                st.layoutPending.remove(p);       // ...still offered this one last time
            apply(p.wdg);
        }
    }

    /**
     * The widget-removal seam's offer (M1, from {@link AddonManager#drainRemovedWidgets}): {@code w} just left
     * the tree, so (a) it can stop waiting for a late caption, (b) {@link LuaWidget#pruneRemoved} drops just
     * this widget's record instead of every owner's whole list needing a per-tick sweep, and (c) if {@code w}
     * was itself an anchored
     * widget, {@link #retarget} drops its {@link #derived} entry and, if nothing else names the same target,
     * the drag listener installed for it (042.10 — {@code redrive}'s per-tick fold over every anchor is gone;
     * this is the one place a departure is handled instead).
     */
    static void dispatchRemoved(AddonManager.SessionState st, Widget w) {
        for(Pending p : st.layoutPending) {
            if(p.wdg == w) {
                st.layoutPending.remove(p);
                break;
            }
        }
        LuaWidget.pruneRemoved(w);
        synchronized(Layout.class) {
            if(!derived.isEmpty())
                retarget(w, null);
        }
    }

    /**
     * The geometry seam's offer (M4, from {@link AddonManager#drainResizedWidgets}): {@code w} just resized —
     * an anchor target, a window that packed itself, or the screen. {@link #moved} handles the first two (it
     * re-derives whatever hangs off {@code w} as a WIDGET target) and fast-paths on {@link #derived} being
     * empty, so a client with nothing anchored pays one volatile-backed check per resize.
     *
     * <p><b>The screen is not a WIDGET target</b> (an {@code anchor = {to = "screen", …}} carries no {@code
     * target()} at all — {@link Anchor#SCREEN}, not {@link Anchor#WIDGET}), so {@link #moved}'s {@code
     * to != WIDGET} filter skips every screen-anchored widget by construction. {@code UILoop} resizing {@code
     * ui.root} is exactly this case, so it is handled here, separately: every SCREEN-anchored widget is
     * re-applied — the same widgets {@code redrive()} used to walk every tick, now touched only on an actual
     * screen resize.
     */
    static void dispatchResized(Widget w) {
        UI u = w.ui;   // 073.2: "is this the SCREEN?" is a question about the widget's own tree, not about
        if((u != null) && (w == u.root))   //   whether it is the DRAWN one's root, which a background
            rederiveScreenAnchored();      //   session's root never is however often it resizes
        moved(w);
    }

    /** Re-apply every widget anchored to the SCREEN (never the tree, never a WIDGET target) — the screen half
     *  {@link #moved}'s WIDGET-target filter cannot see. Snapshotted under {@link Layout#class} before any
     *  {@link #apply} call, exactly like {@link #sweep}, since applying writes {@code c}/{@code sz}, never the
     *  {@link #derived} map itself, but a snapshot is what makes that a guarantee rather than a hope. */
    private static void rederiveScreenAnchored() {
        List<Widget> ws = null;
        synchronized(Layout.class) {
            if(derived.isEmpty())
                return;
            for(Map.Entry<Widget, Anchor> e : derived.entrySet()) {
                if(e.getValue().to != Anchor.SCREEN)
                    continue;
                if(ws == null)
                    ws = new ArrayList<Widget>(4);
                ws.add(e.getKey());
            }
        }
        for(int i = 0; (ws != null) && (i < ws.size()); i++)
            apply(ws.get(i));
    }

    /**
     * <b>The client just re-laid its own screen out</b> (062) — {@code GameUI.resize} re-places {@code chat},
     * {@code beltwdg}, {@code prog} and the map unconditionally on every screen resize, so put every
     * hand-named level over one of its children back on top of what it wrote.
     *
     * <p>It is a separate entry rather than a case of {@link #dispatchResized} because a hand-named level is
     * <b>not</b> in {@link #derived}: that map holds anchors, and neither the {@code Anchor.WIDGET} filter nor
     * the {@code Anchor.SCREEN} one names a plain {@code position}. {@code Widget.move} is not hooked either,
     * and deliberately so — it is on every drag of every window in the client.
     *
     * <p><b>It runs after the client's own placement</b> (the call is the last line of that method), so it
     * overwrites rather than the reverse, and it cannot recurse: {@link #apply} is idempotent — a widget
     * already where the cascade says gets no write at all — so a {@code resize} it makes cannot come back
     * round and ask for another. Bounded by the held set, and one volatile read on a client with nothing
     * laid out, which is every client until an addon lays something out.
     */
    static void reapply(Widget parent) {
        if(!LuaWidget.anyMoved || (parent == null))
            return;
        List<Widget> ws = LuaWidget.movedUnder(parent);
        for(int i = 0; i < ws.size(); i++)
            apply(ws.get(i));
    }

    /** Is a pending candidate still the same live widget? (Server-bound: by id; client-only: by reachability.) */
    static boolean alive(UI u, Widget w, int id) {
        return (id >= 0) ? (u.getwidget(id) == w) : w.hasparent(u.root);
    }

    // ---- parsing -----------------------------------------------------------------------------------

    /**
     * Parse a rule's {@code position = {x, y}} / {@code size = {w, h}} — the two spellings a colour and a border's
     * slice take ({@code {40, 200}} or {@code {x = 40, y = 200}}), for the same reason: the positional form is what
     * a hand-written rule and a {@code theme.json} say, the keyed form is what {@code widget:position()} and
     * {@code widget:style()} hand back, so a read round-trips into a write unchanged.
     *
     * <p><b>Which two keys is the property's</b> (085.3): {@code size} reads {@code w}/{@code h} and everything
     * else reads {@code x}/{@code y}, matching the readers exactly — and an {@code {x=, y=}} written under
     * {@code size} raises naming {@code w}/{@code h} rather than being taken, because it is the one mistake the
     * positional fallback would otherwise swallow. This is the <b>document</b> parser as well as the Lua one, so
     * a {@code theme.json} and a {@code rule:size(t)} say a size the same way.
     *
     * <p><b>Design pixels</b> (058.3), like every other number {@code hafen.ui} takes: the pair is kept exactly as
     * the rule said it, and converts once at the edge where it is applied — {@link #applyHalf} for a size,
     * {@link Anchor#resolve} for a place. Which is also what makes the read round-trip: {@code rule:size()} and
     * {@code widget:style()} answer the numbers that were written, on every client.
     */
    static Coord parseCoord(String ctx, String prop, LuaValue v) {
        boolean size = "size".equals(prop);
        // 085.3: a SIZE reads w/h and a place reads x/y — the same split the readers make, so the keyed form a
        // rule says is the keyed form rule:size()/widget:size() hands back. This is the DOCUMENT parser too, so
        // the JSON and the Lua spellings moved together.
        String ka = size ? "w" : "x", kb = size ? "h" : "y";
        String shape = size ? "{width, height}" : "{x, y}";
        String keyed = size ? "{ w = 300, h = 200 }" : "{ x = 40, y = 200 }";
        if(!v.istable())
            throw new LuaError(ctx + "." + prop + ": expected " + shape + " — " + prop + " = "
                + (size ? "{300, 200}" : "{40, 200}") + " or " + prop + " = " + keyed
                + ", got " + v.typename());
        // type() rather than isnumber(): in LuaJ a STRING that looks like a number answers isnumber() (the 028
        // asset lesson) — position = {"40", "200"} is a typo, not a position.
        LuaValue x = v.get(ka), y = v.get(kb);
        if((x.type() != LuaValue.TNUMBER) || (y.type() != LuaValue.TNUMBER)) {
            if(size && (((LuaTable)v).rawget("x").type() == LuaValue.TNUMBER) && (((LuaTable)v).rawget("y").type() == LuaValue.TNUMBER))
                throw new LuaError(ctx + ".size: a size is spelled w and h — size = { w = 300, h = 200 } or"
                    + " size = {300, 200}. x and y are a place, which is what position says");
            x = v.get(1);
            y = v.get(2);
            if((x.type() != LuaValue.TNUMBER) || (y.type() != LuaValue.TNUMBER))
                throw new LuaError(ctx + "." + prop + ": expected two numbers — " + shape + " or " + keyed);
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
                        + " — s:ui():find(\"window[title=Inventory]\"), got " + pv.typename());
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
        return Chrome.cornerOf(ctx, ".anchor.at", v);
    }
}
