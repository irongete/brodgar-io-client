package io.brodgar.addon;

import haven.Coord;
import haven.UI;
import haven.Widget;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaValue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The <b>layout layer</b> — where the stylesheet's {@code pos} and {@code size} properties reach a widget
 * (spec {@code 036-ui-layout}, feature E, task 036.2). 036.1 made the verbs work on a native widget; this is
 * the same layer with a <b>cascade</b> above it:
 *
 * <pre>
 *   hafen.ui.skin{ ["window[title=Equipment]"] = { pos = {40, 200} } }   -- matched
 *   hafen.ui("window[title=Equipment]"):pos(40, 200)                     -- named by hand
 * </pre>
 *
 * <p><b>One fold, two levels</b> (D-077 verbatim, one property along): a tree rule that names a widget carries
 * its {@code pos}/{@code size} through the very fold {@link Sheet} already runs for {@code font}/{@code color}/
 * {@code bg}/{@code border}/{@code pad} — most specific rule wins, per property — and the <b>verbs sit on top of
 * it</b>, hand-named, latest applied winning. So {@code widget:pos(x, y)} on a widget a rule also names wins, and
 * {@code widget:pos(nil)} drops back to <i>the rule</i> rather than to the stock value: the undo removes a level,
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
 *   <li>a verb is called or undone ({@link #apply} on that one widget).</li>
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
     * that each called {@code widget:pos(x, y)} on one widget the last one wins (D-043, and D-087's <i>a position
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

    /** Is there anything to enforce at all — an installed layout rule, or a widget somebody is standing on? */
    static boolean active() {
        return Sheet.anyLayout || LuaWidget.anyMoved;
    }

    /** Session init / relog: the tree of the session just ended, so nothing is waiting for a caption any more. */
    static void resetSession() {
        pending.clear();
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
        if(w == null)
            return;
        UI u = AddonManager.ui;
        if(u == null)
            return;
        synchronized(u) {
            Sheet.Resolved r = Sheet.styleOf(w);      // ONE fold, read once and used for both halves
            applyHalf(w, r, false);
            applyHalf(w, r, true);
        }
    }

    /**
     * One half of one widget's layout: the winning value from the cascade (a tree rule, then the hand-named verb
     * on top), written through the same call the verb makes — or, when no level names this half any more, the
     * stock value handed back and the record's half forgotten. Caller holds the {@code ui} monitor.
     */
    private static void applyHalf(Widget w, Sheet.Resolved r, boolean pos) {
        Coord want = null;
        Addon owner = null;
        if(r != null) {                               // the MATCHED level: every tree rule that names this widget
            want = pos ? r.pos : r.size;
            owner = pos ? r.posOwner : r.sizeOwner;
        }
        LuaWidget.Moved top = LuaWidget.topWant(w, pos);   // ...and the HAND-NAMED level above it (D-077)
        if(top != null) {
            want = pos ? top.wantPos : top.wantSize;
            owner = top.owner;
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
        if(LuaWidget.anyMoved)
            LuaWidget.pruneMoved(u);
    }

    /** Is a pending candidate still the same live widget? (Server-bound: by id; client-only: by reachability.) */
    static boolean alive(UI u, Widget w, int id) {
        return (id >= 0) ? (u.getwidget(id) == w) : w.hasparent(u.root);
    }

    // ---- parsing -----------------------------------------------------------------------------------

    /**
     * Parse a rule's {@code pos = {x, y}} / {@code size = {w, h}} — the same two spellings a colour and a border's
     * slice take ({@code {40, 200}} or {@code {x = 40, y = 200}}), for the same reason: the positional form is what
     * a hand-written rule and a {@code theme.json} say, the keyed form is what {@code widget:pos()} and
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
}
