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
 * <b>One live drag, and the bindings that start one</b> (spec {@code 062-drag-handles}) — the Java half of
 * {@code widget:draggable(h)}. The client has a drag for exactly one kind of widget (a {@link haven.Window},
 * by its caption); this hands the same gesture to the <i>user</i> on any widget at all, with any widget as
 * the handle they press.
 *
 * <p><b>Two halves, two doors, and they are the ones {@link LuaMouseGrab} already uses.</b> A gesture is a
 * <b>zero-size, {@code visible()}</b> widget on {@code ui.root}, because:
 * <ul>
 *   <li>{@link UI#grabmouse} carries {@code MouseDownEvent}/{@code MouseUpEvent}/{@code MouseWheelEvent}/
 *       {@code CursorQuery} and <b>nothing else</b>, and {@code UI.dispatch} walks the grabs newest-first
 *       and returns on the first that handles — so while a drag runs, no press of the user's reaches the
 *       tree underneath it. That, and only that, is <i>the client's own click never fires underneath</i>;</li>
 *   <li>a {@code MouseMoveEvent} is <b>broadcast to every visible child with no rect test</b>
 *       ({@code MouseMoveEvent.propagation}), handing each an out-of-box coordinate — which is why the
 *       widget must be {@code visible}, why zero size costs nothing, and why the drag survives the pointer
 *       outrunning the handle and leaving the window. <b>Do not "fix" the visibility.</b></li>
 * </ul>
 * {@code Window.drag} is the same split in the client's own code ({@code dm = ui.grabmouse(this)} plus a
 * {@code mousemove}), and {@link #doff} is its {@code doff}: the offset between the press and the target's
 * own origin, so the target does not jump on the first move.
 *
 * <p><b>It writes the layout level, and nothing by hand.</b> Each move does exactly what
 * {@code widget:position(x, y)} does — {@code wantPos} + a fresh {@link Layout#nextSeq()} + {@link
 * Layout#apply} — so the off-screen clamp, {@code widget:position(nil)}, {@code widget:revert()},
 * {@code :reload} and disable all come from the layer that was already there, and {@code GameUI.savewndpos}
 * goes on writing what the <i>user</i> placed.
 *
 * <p><b>Every armed owner's level is written, and the target moves once</b>: one gesture per target however
 * many addons armed it, each owner getting its own record and its own {@code seq}, so a {@code nil} from
 * either addon is invisible on screen and each drops only its own binding.
 *
 * <p><b>Arming is a {@code Widget.listen} on the HANDLE</b> — the same zero-edit engine seam {@link
 * WidgetSubs} and {@link Layout} already use — one listener per handle however many bindings name it,
 * installed with the first and dropped with the last. It reads no geometry the press itself can move, and
 * a press that <b>starts</b> a drag is consumed there and then — see {@link #press} for why that is the
 * gesture working rather than a courtesy.
 *
 * <p>Package-private, carries no Lua, and every tree write happens under the {@code ui} monitor.
 */
final class Gesture extends Widget {
    /**
     * One addon's arming of one target: <i>this owner wants {@code target} dragged by {@code handle}</i>. Held
     * on the {@link Addon} ({@link Addon#gestures}) like every other bridge-owned record, so teardown is the
     * addon's own list and never a sweep of the tree. One per (owner, target): arming again replaces it,
     * because a target has one handle per addon and a second call is a change of mind, not a second binding.
     */
    static final class Bind {
        final Addon owner;
        final Widget target;
        final Widget handle;

        Bind(Addon owner, Widget target, Widget handle) {
            this.owner = owner;
            this.target = target;
            this.handle = handle;
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

    /** The gestures running right now — normally none, and at most one per target being dragged. */
    private static final List<Gesture> running = new CopyOnWriteArrayList<Gesture>();

    // ---- the bindings: arm, read, drop ---------------------------------------------------------------

    /** {@code w:draggable()} — the handle THIS addon armed on {@code target}, or {@code null}. */
    static Widget handleOf(Addon owner, Widget target) {
        Bind b = find(owner, target);
        return (b == null) ? null : b.handle;
    }

    /** {@code w:draggable(h)} — arm (or re-arm) {@code target}, and make sure {@code handle} is listening. */
    static void arm(Addon owner, Widget target, Widget handle) {
        Bind prev = find(owner, target);
        owner.gestures.add(new Bind(owner, target, handle));
        if(prev != null)
            forget(owner, prev);              // a change of mind: the old handle may now be listening for nobody
        listen(handle);
    }

    /** {@code w:draggable(nil)} — drop THIS addon's binding on {@code target}; another addon's is untouched. */
    static void drop(Addon owner, Widget target) {
        Bind b = find(owner, target);
        if(b != null)
            forget(owner, b);
    }

    /**
     * {@code widget:revert()} reached {@code w}: drop this addon's binding wherever {@code w} stands in it —
     * as the target, and as the handle of some other target, since a revert destroys the controls it adopted
     * and a binding pressing a widget that is going away is a binding nobody can start.
     */
    static void release(Addon owner, Widget w) {
        for(Bind b : owner.gestures) {
            if((b.target == w) || (b.handle == w))
                forget(owner, b);
        }
    }

    /** Teardown ({@code :reload}/disable): end any drag this addon is in, then drop every binding it holds. */
    static void teardown(Addon a) {
        if(a == null)
            return;
        for(Gesture g : running) {
            if(g.owners.contains(a))
                g.release();
        }
        for(Bind b : a.gestures)
            forget(a, b);
        a.gestures.clear();
    }

    /** Session init / relog: the tree of the session just ended, so nothing is armed and nothing is dragging. */
    static void resetSession() {
        for(Gesture g : running)
            g.release();
        running.clear();
        synchronized(Gesture.class) { arms.clear(); }
    }

    private static Bind find(Addon owner, Widget target) {
        for(Bind b : owner.gestures) {
            if(b.target == target)
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
                    return press(handle, ev);   // a press that STARTED a drag is spent; see press()
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
     * The handle was pressed: start one gesture per distinct target named through it, each carrying every
     * owner that named that target. Left button only, like {@code Window.DragDeco.mousedown}.
     *
     * <p><b>A press that started a drag is SPENT</b>, and answering so is not a nicety — it is what makes
     * the gesture work at all. {@code Widget.handle} returning {@code true} ends the propagation there, and
     * what sits under a handle is very often something that grabs the pointer itself: a grip inside one of
     * the client's windows is walked before the window's own {@code DragDeco}, whose {@code checkhit} covers
     * the whole frame, so letting the press through would start {@code Window.drag} as well — a <b>newer</b>
     * {@code UI.Grab} than this one, which would then swallow the release and leave the drag stuck to the
     * pointer until the next click. So the rule is the client's own: pressing a drag handle drags, and does
     * nothing else. A press this method does not act on — any other button, a binding whose target has left
     * the tree — is left entirely alone.
     *
     * <p>The two geometry reads here are the press point and the target's origin, and neither is one the
     * press itself can move ({@code Window.mousedown} raises and focuses; it does not place). Everything the
     * gesture needs afterwards comes off the move event's own coordinate.
     */
    private static boolean press(Widget handle, Widget.MouseDownEvent ev) {
        if(ev.b != 1)
            return false;
        UI u = AddonManager.ui;
        if((u == null) || (u.root == null) || !handle.hasparent(u.root))
            return false;
        List<Widget> targets = new ArrayList<Widget>(2);
        List<List<Addon>> owners = new ArrayList<List<Addon>>(2);
        List<Addon> as = AddonManager.addons;
        for(int i = 0, n = as.size(); i < n; i++)
            collect(as.get(i), handle, u, targets, owners);
        collect(AddonManager.consoleOwner, handle, u, targets, owners);
        if(targets.isEmpty())
            return false;
        Coord at = handle.rootpos().add(ev.c);            // the press, in root pixels
        for(int i = 0; i < targets.size(); i++) {
            Widget t = targets.get(i);
            Gesture g = new Gesture(t, owners.get(i), at.sub(t.rootpos()));
            running.add(g);
            u.root.add(g);
            g.arm(u);
        }
        return true;
    }

    /** Add every target {@code a} drags through {@code handle} to the per-target owner lists. */
    private static void collect(Addon a, Widget handle, UI u, List<Widget> targets, List<List<Addon>> owners) {
        if(a == null)
            return;
        for(Bind b : a.gestures) {
            if((b.handle != handle) || !b.target.hasparent(u.root) || (b.target.parent == null))
                continue;
            int i = targets.indexOf(b.target);
            if(i < 0) {
                targets.add(b.target);
                owners.add(new ArrayList<Addon>(2));
                i = targets.size() - 1;
            }
            owners.get(i).add(a);
        }
    }

    // ---- one live drag ------------------------------------------------------------------------------

    private final Widget target;
    private final List<Addon> owners;
    /** The press, minus the target's own origin — {@code Window.doff}, so the target does not jump. */
    private final Coord doff;
    private UI.Grab grab;
    private boolean alive = true;
    /** Has the pointer actually moved the target? A press that never moved is a click, and says nothing. */
    private boolean moved;

    private Gesture(Widget target, List<Addon> owners, Coord doff) {
        super(Coord.z);   // zero size; visible (the field default) so broadcast MouseMoveEvents reach it
        this.target = target;
        this.owners = owners;
        this.doff = doff;
    }

    /** Start capturing (after this widget is on {@code ui.root}) — the up lands here wherever the pointer is. */
    private void arm(UI u) {
        grab = u.grabmouse(this);
    }

    /** Broadcast move → the target follows, one-for-one, wherever the pointer went. */
    public void mousemove(MouseMoveEvent ev) {
        if(alive)
            write(ev.c);
    }

    /** The grabbed up ends it: the key fires with what landed, and the gesture is over. Consumes it. */
    public boolean mouseup(MouseUpEvent ev) {
        if(!alive)
            return true;
        write(ev.c);
        fire();
        release();
        return true;
    }

    /** Swallow any button press during the drag (no new interaction under it). */
    public boolean mousedown(MouseDownEvent ev) {
        return alive;
    }

    /** Swallow the wheel during the drag, exactly as a grabbed {@link LuaMouseGrab} does. */
    public boolean mousewheel(MouseWheelEvent ev) {
        return alive;
    }

    public void tick(double dt) {
        super.tick(dt);
        UI u = ui;
        if(alive && ((u == null) || (u.root == null) || !target.hasparent(u.root)))
            release();      // the target left the tree mid-drag: there is nothing left to move
        if(!alive && (parent != null))
            reqdestroy();   // deferred unlink — tick propagation snapshots `next`, so removing here is safe
    }

    /**
     * Put the target where the pointer says, through the very four lines {@code widget:position(x, y)} runs:
     * every armed owner's hand-named level, each with its own fresh {@code seq}, and one {@link Layout#apply}
     * over the fold they compete in. The value is computed from the event's own coordinate and never by
     * reading the target back, so a listener seeing one event stale could not shift it.
     */
    private void write(Coord at) {
        Widget p = target.parent;
        UI u = ui;
        if((p == null) || (u == null) || (u.root == null))
            return;
        synchronized(u) {
            Coord pp = p.rootpos();
            if(pp == null)
                return;
            Coord to = Px.out(at.sub(doff).sub(pp));   // parent-local, and the level speaks DESIGN pixels
            for(int i = 0, n = owners.size(); i < n; i++) {
                LuaWidget.Moved rec = LuaWidget.recordMoved(owners.get(i), target);
                rec.wantPos = Layout.Anchor.at(to);
                rec.posSeq = Layout.nextSeq();
            }
            Layout.apply(target);                      // the clamp, the fold and the followers, all from there
        }
        moved = true;
    }

    /**
     * {@code w:on("Dragged", fn)} — once, on release, answering where the widget <b>landed</b> rather than
     * where the pointer was, so {@code ev:x()} is the number {@code widget:position()} reads in that same
     * frame (the clamp may have had the last word). Gated on {@code hasSub} like every other emitter, and
     * fired from here and nowhere else — which is why an addon's own {@code widget:position(x, y)} does not
     * fire it: {@link Layout#apply} has no path to {@link Subs#fire}.
     *
     * <p>A press that never moved anything is a click, and a click is not a drag: nothing fires.
     */
    private void fire() {
        if(!moved || (target.c == null))
            return;
        for(int i = 0, n = owners.size(); i < n; i++) {
            Addon a = owners.get(i);
            WidgetSubs ws = a.widgetSubsOrNull(target);
            if((ws != null) && ws.subs.has("Dragged"))
                ws.subs.fire("Dragged", LuaEvent.gesture(a, target.c.x, target.c.y));
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
