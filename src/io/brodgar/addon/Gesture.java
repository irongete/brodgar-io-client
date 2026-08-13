package io.brodgar.addon;

import haven.Coord;
import haven.EventHandler;
import haven.UI;
import haven.Widget;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * <b>One live gesture, and the bindings that start one</b> (spec {@code 062-drag-handles}) — the Java half of
 * {@code widget:draggable(h)} and {@code widget:resizable(h)}. The client has a drag for exactly one kind of
 * widget (a {@link haven.Window}, by its caption) and a resize for exactly one window in the game; this hands
 * both gestures to the <i>user</i> on any widget at all, with any widget as the handle they press.
 *
 * <p><b>Two halves, two doors, and they are the ones {@link LuaMouseGrab} already uses.</b> A gesture is a
 * <b>zero-size, {@code visible()}</b> widget on {@code ui.root}, because:
 * <ul>
 *   <li>{@link UI#grabmouse} carries {@code MouseDownEvent}/{@code MouseUpEvent}/{@code MouseWheelEvent}/
 *       {@code CursorQuery} and <b>nothing else</b>, and {@code UI.dispatch} walks the grabs newest-first
 *       and returns on the first that handles — so while a gesture runs, no press of the user's reaches the
 *       tree underneath it. That, and only that, is <i>the client's own click never fires underneath</i>;</li>
 *   <li>a {@code MouseMoveEvent} is <b>broadcast to every visible child with no rect test</b>
 *       ({@code MouseMoveEvent.propagation}), handing each an out-of-box coordinate — which is why the
 *       widget must be {@code visible}, why zero size costs nothing, and why the gesture survives the pointer
 *       outrunning the handle and leaving the window. <b>Do not "fix" the visibility.</b></li>
 * </ul>
 * {@code Window.drag} is the same split in the client's own code ({@code dm = ui.grabmouse(this)} plus a
 * {@code mousemove}), and so is {@code DefaultDeco}'s corner sizer.
 *
 * <p><b>One press, one gesture object, one grab</b> — however many bindings that press starts. A handle may
 * name several targets, and each target may be armed for a drag and for a resize, so a press is a <i>set</i>
 * of {@link Move}s. It cannot be a widget each: only the newest grab is offered the release
 * ({@code UI.dispatch} returns on the first that handles), so a second grabbed widget would never hear its own
 * up and would stay stuck to the pointer.
 *
 * <p><b>Every {@link Move} is the same subtraction</b>: {@link Move#doff} is the press <i>minus the value that
 * move drives</i> — the target's own origin for a drag, its own size for a resize — so each pointer position
 * answers {@code at - doff} and the target neither jumps on the first move nor drifts. A resize therefore
 * leaves the top-left exactly where it was, which is the client's own rule ({@code Window.resize} sizes only),
 * and it is {@code DefaultDeco.szdragc}'s trick with the two operands swapped.
 *
 * <p><b>It writes the layout level, and nothing by hand.</b> Each move does exactly what
 * {@code widget:position(x, y)} and {@code widget:size(w, h)} do — {@code wantPos}/{@code wantSize} + a fresh
 * {@link Layout#nextSeq()} + {@link Layout#apply} — so the off-screen clamp, the two {@code nil} undos,
 * {@code widget:revert()}, {@code :reload} and disable all come from the layer that was already there, a
 * window that packs itself around its contents makes a resize <i>inert</i> rather than an error, and
 * {@code GameUI.savewndpos} goes on writing what the <i>user</i> placed.
 *
 * <p><b>Every armed owner's level is written, and the target moves once</b>: one {@link Move} per (target,
 * mode) however many addons armed it, each owner getting its own record and its own {@code seq}, so a
 * {@code nil} from either addon is invisible on screen and each drops only its own binding.
 *
 * <p><b>Arming is a {@code Widget.listen} on the HANDLE</b> — the same zero-edit engine seam {@link
 * WidgetSubs} and {@link Layout} already use — one listener per handle however many bindings name it,
 * installed with the first and dropped with the last. It reads no geometry the press itself can move, and
 * a press that <b>starts</b> a gesture is consumed there and then — see {@link #press} for why that is the
 * gesture working rather than a courtesy.
 *
 * <p>Package-private, carries no Lua, and every tree write happens under the {@code ui} monitor.
 */
final class Gesture extends Widget {
    /**
     * What a binding hands over: the <b>place</b> of a widget or its <b>box</b>. One mechanism, one difference
     * — which half of the layout level the pointer writes — so the two verbs are one code path with this
     * enum where they differ, rather than two that will drift.
     */
    enum Mode {
        DRAG("draggable", "Dragged"),
        SIZE("resizable", "Resized");

        /** The Lua verb that arms it, for a refusal that has to name it. */
        final String verb;
        /** The key it fires on release ({@code w:on("Dragged", fn)}). */
        final String key;

        Mode(String verb, String key) {
            this.verb = verb;
            this.key = key;
        }
    }

    /**
     * One addon's arming of one target in one mode: <i>this owner wants {@code target} dragged (or resized) by
     * {@code handle}</i>. Held on the {@link Addon} ({@link Addon#gestures}) like every other bridge-owned
     * record, so teardown is the addon's own list and never a sweep of the tree. One per (owner, target, mode):
     * arming again replaces it, because a target has one handle per addon per mode and a second call is a change
     * of mind, not a second binding. The two modes are independent — one grip may drag a window and another
     * resize it.
     */
    static final class Bind {
        final Addon owner;
        final Widget target;
        final Widget handle;
        final Mode mode;

        Bind(Addon owner, Widget target, Widget handle, Mode mode) {
            this.owner = owner;
            this.target = target;
            this.handle = handle;
            this.mode = mode;
        }
    }

    /**
     * The ONE {@code MouseDownEvent} listener installed per handle widget, whatever bindings name it. Weak
     * keys: a handle that leaves the tree takes its own {@code listening} list with it, so a dead one needs
     * no explicit deafen — the same discipline {@link Layout}'s drag listeners keep. Guarded by
     * {@code Gesture.class}.
     */
    private static final Map<Widget, EventHandler<Widget.MouseDownEvent>> arms =
        new WeakHashMap<Widget, EventHandler<Widget.MouseDownEvent>>();

    /** The gesture running right now — normally none, and at most one, since a press is one object. */
    private static final List<Gesture> running = new CopyOnWriteArrayList<Gesture>();

    // ---- the bindings: arm, read, drop ---------------------------------------------------------------

    /** {@code w:draggable()} / {@code w:resizable()} — the handle THIS addon armed, or {@code null}. */
    static Widget handleOf(Addon owner, Widget target, Mode mode) {
        Bind b = find(owner, target, mode);
        return (b == null) ? null : b.handle;
    }

    /** The write arity — arm (or re-arm) {@code target}, and make sure {@code handle} is listening. */
    static void arm(Addon owner, Widget target, Widget handle, Mode mode) {
        Bind prev = find(owner, target, mode);
        owner.gestures.add(new Bind(owner, target, handle, mode));
        if(prev != null)
            forget(owner, prev);              // a change of mind: the old handle may now be listening for nobody
        listen(handle);
    }

    /** The {@code nil} arity — drop THIS addon's binding in THIS mode; another addon's, and the other mode's,
     *  are untouched. */
    static void drop(Addon owner, Widget target, Mode mode) {
        Bind b = find(owner, target, mode);
        if(b != null)
            forget(owner, b);
    }

    /**
     * {@code widget:revert()} reached {@code w}: drop this addon's bindings wherever {@code w} stands in one —
     * as the target, and as the handle of some other target, in both modes, since a revert destroys the
     * controls it adopted and a binding pressing a widget that is going away is a binding nobody can start.
     */
    static void release(Addon owner, Widget w) {
        for(Bind b : owner.gestures) {
            if((b.target == w) || (b.handle == w))
                forget(owner, b);
        }
    }

    /** Teardown ({@code :reload}/disable): end any gesture this addon is in, then drop every binding it holds. */
    static void teardown(Addon a) {
        if(a == null)
            return;
        for(Gesture g : running) {
            if(g.owns(a))
                g.release();
        }
        for(Bind b : a.gestures)
            forget(a, b);
        a.gestures.clear();
    }

    /** Session init / relog: the tree of the session just ended, so nothing is armed and nothing is running. */
    static void resetSession() {
        for(Gesture g : running)
            g.release();
        running.clear();
        synchronized(Gesture.class) { arms.clear(); }
    }

    private static Bind find(Addon owner, Widget target, Mode mode) {
        for(Bind b : owner.gestures) {
            if((b.target == target) && (b.mode == mode))
                return b;
        }
        return null;
    }

    /** Drop one binding and, if its handle is named by no other, stop listening on it. */
    private static void forget(Addon owner, Bind b) {
        owner.gestures.remove(b);
        if(!named(b.handle))
            deafen(b.handle);
    }

    /** Does any live owner still name {@code handle}? (The bindings are per-addon tiny; this is a walk.) */
    private static boolean named(Widget handle) {
        List<Addon> as = AddonManager.addons;
        for(int i = 0, n = as.size(); i < n; i++) {
            if(namedIn(as.get(i), handle))
                return true;
        }
        return namedIn(AddonManager.consoleOwner, handle);
    }

    private static boolean namedIn(Addon a, Widget handle) {
        if(a == null)
            return false;
        for(Bind b : a.gestures) {
            if(b.handle == handle)
                return true;
        }
        return false;
    }

    private static void listen(final Widget handle) {
        synchronized(Gesture.class) {
            if(arms.containsKey(handle))
                return;
            EventHandler<Widget.MouseDownEvent> h = new EventHandler<Widget.MouseDownEvent>() {
                public boolean handle(Widget.MouseDownEvent ev) {
                    return press(handle, ev);   // a press that STARTED a gesture is spent; see press()
                }
            };
            handle.listen(Widget.MouseDownEvent.class, h);
            arms.put(handle, h);
        }
    }

    private static void deafen(Widget handle) {
        EventHandler<Widget.MouseDownEvent> h;
        synchronized(Gesture.class) { h = arms.remove(handle); }
        if(h == null)
            return;
        try {
            handle.deafen(h);
        } catch(RuntimeException e) { /* the handle is already gone: its listener list went with it */ }
    }

    // ---- starting one -------------------------------------------------------------------------------

    /**
     * The handle was pressed: start ONE gesture carrying every (target, mode) named through it, each with every
     * owner that named it. Left button only, like {@code Window.DragDeco.mousedown}.
     *
     * <p><b>A press that started a gesture is SPENT</b>, and answering so is not a nicety — it is what makes
     * the gesture work at all. {@code Widget.handle} returning {@code true} ends the propagation there, and
     * what sits under a handle is very often something that grabs the pointer itself: a grip inside one of
     * the client's windows is walked before the window's own {@code DragDeco}, whose {@code checkhit} covers
     * the whole frame, so letting the press through would start {@code Window.drag} as well — a <b>newer</b>
     * {@code UI.Grab} than this one, which would then swallow the release and leave the drag stuck to the
     * pointer until the next click. So the rule is the client's own: pressing a handle drives it, and does
     * nothing else. A press this method does not act on — any other button, a binding whose target has left
     * the tree — is left entirely alone.
     *
     * <p>The two geometry reads here are the press point and the target's own origin or box, and neither is
     * one the press itself can move ({@code Window.mousedown} raises and focuses; it does not place or size).
     * Everything the gesture needs afterwards comes off the move event's own coordinate.
     */
    private static boolean press(Widget handle, Widget.MouseDownEvent ev) {
        if(ev.b != 1)
            return false;
        UI u = AddonManager.ui;
        if((u == null) || (u.root == null) || !handle.hasparent(u.root))
            return false;
        Coord at = handle.rootpos().add(ev.c);            // the press, in root pixels
        List<Move> moves = new ArrayList<Move>(2);
        List<Addon> as = AddonManager.addons;
        for(int i = 0, n = as.size(); i < n; i++)
            collect(as.get(i), handle, u, at, moves);
        collect(AddonManager.consoleOwner, handle, u, at, moves);
        if(moves.isEmpty())
            return false;
        Gesture g = new Gesture(moves, at);
        running.add(g);
        u.root.add(g);
        g.arm(u);
        return true;
    }

    /** Add every (target, mode) {@code a} drives through {@code handle} to this press's move list. */
    private static void collect(Addon a, Widget handle, UI u, Coord at, List<Move> moves) {
        if(a == null)
            return;
        for(Bind b : a.gestures) {
            if((b.handle != handle) || (b.target.parent == null) || !b.target.hasparent(u.root))
                continue;
            Move m = null;
            for(int i = 0, n = moves.size(); (m == null) && (i < n); i++) {
                Move c = moves.get(i);
                if((c.target == b.target) && (c.mode == b.mode))
                    m = c;
            }
            if(m == null)
                moves.add(m = new Move(b.target, b.mode, at));
            m.owners.add(a);
        }
    }

    // ---- one live gesture ---------------------------------------------------------------------------

    /** One thing this press drives: a target, which half of its layout, and the owners that asked for it. */
    private static final class Move {
        final Widget target;
        final Mode mode;
        final List<Addon> owners = new ArrayList<Addon>(2);
        /** The press, minus the value this move drives — so {@code at - doff} IS that value, at every pointer
         *  position. {@code Window.doff} for a drag; {@code DefaultDeco.szdragc} inverted for a resize. */
        final Coord doff;

        Move(Widget target, Mode mode, Coord at) {
            this.target = target;
            this.mode = mode;
            this.doff = at.sub((mode == Mode.DRAG) ? target.rootpos() : LuaWidget.sizeArg(target));
        }
    }

    private final List<Move> moves;
    /** Where the press landed, in root pixels: a release that never left it is a CLICK and says nothing. */
    private final Coord press;
    private UI.Grab grab;
    private boolean alive = true;
    /** Has the pointer actually driven anything? Set by the first move away from {@link #press}. */
    private boolean acted;

    private Gesture(List<Move> moves, Coord press) {
        super(Coord.z);   // zero size; visible (the field default) so broadcast MouseMoveEvents reach it
        this.moves = moves;
        this.press = press;
    }

    /** Start capturing (after this widget is on {@code ui.root}) — the up lands here wherever the pointer is. */
    private void arm(UI u) {
        grab = u.grabmouse(this);
    }

    /** Does {@code a} own any part of what this press is driving? */
    private boolean owns(Addon a) {
        for(int i = 0, n = moves.size(); i < n; i++) {
            if(moves.get(i).owners.contains(a))
                return true;
        }
        return false;
    }

    /** Broadcast move → every target follows, one-for-one, wherever the pointer went. */
    public void mousemove(MouseMoveEvent ev) {
        if(alive)
            write(ev.c);
    }

    /** The grabbed up ends it: the keys fire with what landed, and the gesture is over. Consumes it. */
    public boolean mouseup(MouseUpEvent ev) {
        if(!alive)
            return true;
        write(ev.c);
        save();
        fire();
        release();
        return true;
    }

    /**
     * {@code widget:remember(name)} — where each target <b>landed</b>, into the addon's own placement slot, so
     * the next session puts it back with no handler of the addon's and no line of Lua after the arming. Only an
     * owner that remembers this target has anything to write; a press that never moved is a click and changes
     * nothing. The disk write itself rides the store's own throttle, and teardown flushes.
     */
    private void save() {
        if(!acted)
            return;
        for(int i = 0, n = moves.size(); i < n; i++) {
            Move m = moves.get(i);
            for(int j = 0, o = m.owners.size(); j < o; j++)
                LuaWidget.rememberLanded(m.owners.get(j), m.target, m.mode == Mode.DRAG);
        }
    }

    /** Swallow any button press during the gesture (no new interaction under it). */
    public boolean mousedown(MouseDownEvent ev) {
        return alive;
    }

    /** Swallow the wheel during the gesture, exactly as a grabbed {@link LuaMouseGrab} does. */
    public boolean mousewheel(MouseWheelEvent ev) {
        return alive;
    }

    public void tick(double dt) {
        super.tick(dt);
        UI u = ui;
        if(alive && ((u == null) || (u.root == null) || !anyLive(u)))
            release();      // every target left the tree mid-gesture: there is nothing left to drive
        if(!alive && (parent != null))
            reqdestroy();   // deferred unlink — tick propagation snapshots `next`, so removing here is safe
    }

    private boolean anyLive(UI u) {
        for(int i = 0, n = moves.size(); i < n; i++) {
            if(live(u, moves.get(i)))
                return true;
        }
        return false;
    }

    private static boolean live(UI u, Move m) {
        return (m.target.parent != null) && m.target.hasparent(u.root);
    }

    /**
     * Put every target where the pointer says, through the very lines {@code widget:position(x, y)} and
     * {@code widget:size(w, h)} run: every armed owner's hand-named level, each with its own fresh {@code seq},
     * and one {@link Layout#apply} over the fold they compete in. The value is computed from the event's own
     * coordinate and never by reading the target back, so a listener seeing one event stale could not shift it.
     *
     * <p>A resize writes the size half alone, so the target's own {@code c} is never touched and its
     * <b>top-left stays put</b>; the floor is one design pixel each way, since a box of nothing is a widget
     * the user can no longer find, let alone grab.
     */
    private void write(Coord at) {
        UI u = ui;
        if((u == null) || (u.root == null))
            return;
        if(!at.equals(press))
            acted = true;
        synchronized(u) {
            for(int i = 0, n = moves.size(); i < n; i++) {
                Move m = moves.get(i);
                if(!live(u, m))
                    continue;
                Coord v = at.sub(m.doff);              // the value this move drives, in the client's own pixels
                if(m.mode == Mode.DRAG) {
                    Coord pp = m.target.parent.rootpos();
                    if(pp == null)
                        continue;
                    Coord to = Px.out(v.sub(pp));      // parent-local, and the level speaks DESIGN pixels
                    for(int j = 0, o = m.owners.size(); j < o; j++) {
                        LuaWidget.Moved rec = LuaWidget.recordMoved(m.owners.get(j), m.target);
                        rec.wantPos = Layout.Anchor.at(to);
                        rec.posSeq = Layout.nextSeq();
                    }
                } else {
                    Coord d = Px.out(v);
                    Coord to = Coord.of(Math.max(1, d.x), Math.max(1, d.y));
                    for(int j = 0, o = m.owners.size(); j < o; j++) {
                        LuaWidget.Moved rec = LuaWidget.recordMoved(m.owners.get(j), m.target);
                        rec.wantSize = to;
                        rec.sizeSeq = Layout.nextSeq();
                    }
                }
                Layout.apply(m.target);                // the clamp, the fold and the followers, all from there
            }
        }
    }

    /**
     * {@code w:on("Dragged", fn)} / {@code w:on("Resized", fn)} — once, on release, answering where the widget
     * <b>landed</b> rather than where the pointer was, so {@code ev:x()} is the number
     * {@code widget:position()} (or {@code widget:size()}) reads in that same frame: the clamp and a window
     * that re-packs itself may both have had the last word. The pair goes in as the client's own <b>device</b>
     * pixels, like every other coordinate {@link LuaEvent} carries, and converts once on the way out
     * ({@code LuaEvent.px()}).
     *
     * <p>A resize reports the widget's OUTER box, since that is what {@code widget:size()} reads — not the
     * content size the gesture drove, which is the same asymmetry the verb itself has on a window.
     *
     * <p>Gated on {@code hasSub} like every other emitter, and fired from here and nowhere else — which is why
     * an addon's own {@code widget:position(x, y)} does not fire it: {@link Layout#apply} has no path to
     * {@link Subs#fire}.
     *
     * <p>A press that never moved the pointer is a click, and a click is neither gesture: nothing fires.
     */
    private void fire() {
        if(!acted)
            return;
        for(int i = 0, n = moves.size(); i < n; i++) {
            Move m = moves.get(i);
            Coord at = (m.mode == Mode.DRAG) ? m.target.c : m.target.sz;
            if(at == null)
                continue;
            for(int j = 0, o = m.owners.size(); j < o; j++) {
                Addon a = m.owners.get(j);
                WidgetSubs ws = a.widgetSubsOrNull(m.target);
                if((ws != null) && ws.subs.has(m.mode.key))
                    ws.subs.fire(m.mode.key, LuaEvent.gesture(a, at.x, at.y));
            }
        }
    }

    /** Stop capturing now + mark dead (the widget unlinks on the next tick). Idempotent. */
    private void release() {
        if(grab != null) {
            grab.remove();
            grab = null;
        }
        if(alive) {
            alive = false;
            running.remove(this);
        }
    }
}
