package io.brodgar.addon;

import haven.ACheckBox;
import haven.Button;
import haven.ChatUI;
import haven.CheckBox;
import haven.Coord;
import haven.Equipory;
import haven.FlowerMenu;
import haven.FromResource;
import haven.GameUI;
import haven.HSlider;
import haven.IButton;
import haven.ICheckBox;
import haven.IMeter;
import haven.ISBox;
import haven.Img;
import haven.Inventory;
import haven.Label;
import haven.MenuGrid;
import haven.Progress;
import haven.RadioGroup;
import haven.Resource;
import haven.SListWidget;
import haven.Scrollbar;
import haven.Text;
import haven.TextEntry;
import haven.UI;
import haven.WItem;
import haven.Widget;
import haven.Window;

import java.awt.Color;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.VarArgFunction;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * A <b>Widget object</b> — the ONE entity {@code hafen.ui} hands back for a widget (spec {@code 029-widget-oop},
 * feature B1). It replaces the transient {@code WidgetNode} of spec {@code 20} and is, from 029.2 on, also what
 * {@code hafen.ui():window()}/{@code :widget()} return: <b>what you create and what you find are the same type</b>.
 * Built on exactly the mechanism {@link LuaGob} (017), {@link LuaKin} (020), {@link LuaSlot} (021),
 * {@link LuaPagina} (023), {@link LuaSound} (024), {@link LuaBuff} (025) and {@link LuaMeter} (027) established:
 * userdata + a per-addon metatable + a per-addon intern cache, with {@code :info()} as the one snapshot hatch.
 *
 * <p><b>Interned, so {@code ==} is the identity test.</b> Two lookups of the same live widget are the same Lua
 * value ({@code hafen.ui():hit(m.x,m.y) == hafen.ui():hit(m.x,m.y)}), and a widget kept across frames stays {@code ==}.
 * That is what let {@code node:same(other)} be <b>hard cut</b> (D-012/D-013): it only ever existed because nothing
 * was interned.
 *
 * <p><b>The intern cache is weak on BOTH sides</b> — {@link Interned#identity} on {@link Addon#widgetObjs} —
 * and that is what the strength choice is there for. {@link LuaMeter}/{@link LuaBuff} key a strong map and
 * drain a {@link java.lang.ref.ReferenceQueue}; over a handful of meters that is bounded, but over a
 * <b>widget tree</b> a strong key would pin every destroyed widget until the next drain — breaking the D-041
 * no-pin rule this type implements on purpose. {@code haven.Widget} overrides neither {@code equals} nor
 * {@code hashCode}, so weak keys are <b>identity</b> keying for free; the value is held weakly too, so it
 * never strongly reaches its own key (the classic self-reference leak), and one cleared while its widget is
 * still alive is simply minted again on the next lookup.
 *
 * <p><b>Owned vs borrowed (029.2).</b> The same type covers a widget the addon <i>created</i>
 * ({@code hafen.ui():window()}/{@code :widget()} — OWNED) and one it merely <i>found</i> (a native widget, or another
 * addon's — BORROWED). Every read answers on both, and so do the visibility write ({@code :hide()}/{@code :show()})
 * and — since 036.1, feature E — the geometry writes ({@code :pos(x,y)}/{@code :size(w,h)}); {@code :pack()} and
 * {@code :destroy()} stay OWNED-only and raise a clear error otherwise, because destroying the client's own widget
 * is not the addon's to do. The one protected verb, {@code :send(msg, ...)} (048.6), is the opposite case: a
 * BORROWED widget is exactly what it is for, since only a server-bound one has anywhere to send. Provenance is <b>derived from the tree</b> ({@link #ownedContent}), never stored on the
 * handle, because the cache below may collect and re-mint an entity at any moment. A write on a BORROWED widget
 * records what it was first — {@code :hide()} on {@link Addon#hiddenNative}, a move or a resize on
 * {@link Addon#movedNative} — and teardown gives it back. That, and not a separate handle type, is the whole
 * of taking a borrowed widget over.
 *
 * <p><b>Liveness is {@code hasparent(ui.root)}</b> (the node rule, not the model's {@code getwidget(id) != wdg},
 * which only covers server-bound widgets). A widget detached from the tree is stale: every read answers
 * {@code nil}/empty, {@code :exists()} is {@code false}, and the handle <b>nulls its {@link #wdg} reference on
 * the first stale access — so a stashed Widget object can never pin a dead subtree in memory (D-041). No teardown
 * hook, no leak: the cache is an identity map over engine-owned widgets, never an owned-resource registry.
 *
 * <p><b>Facade-safe (P1 / D-017).</b> The raw {@link haven.Widget} never crosses into Lua — the handle is opaque
 * userdata whose metatable exposes only this method set, and it cannot be forged (the sandbox omits
 * {@code luajava}). The cache is <b>per-addon</b> like every other one, so no Lua value crosses a sandbox
 * boundary and the whole thing dies with its {@link Addon} on {@code :reload}/disable.
 *
 * <p><b>Threading.</b> Every access runs on the UI thread; {@code :children()}/{@code :walk()} copy the child list
 * under {@code synchronized(ui)} before handing it to Lua, so a walk never races tree mutation, and the
 * {@link Cache} guards its own map (the UI thread and the {@code :lua} REPL both touch it).
 */
public final class LuaWidget {
    /** The wrapped widget, or {@code null} once {@link #live} detects it left the tree (the no-pin rule). */
    Widget wdg;

    private LuaWidget(Widget w) {
        this.wdg = w;
    }

    /** {@code tostring(w)} (also {@code __tostring}): {@code Widget(Inventory#42)} / {@code Widget(dead)}. */
    public String toString() {
        Widget w = live(this);
        if(w == null)
            return "Widget(dead)";
        int id = w.wdgid();
        return "Widget(" + typeName(w) + ((id < 0) ? "" : ("#" + id)) + ")";
    }

    /** An interned Widget object for {@code w} in {@code owner}'s env — the one way a widget reaches Lua. */
    static LuaValue of(final Addon owner, final Widget w) {
        if(w == null)
            return LuaValue.NIL;
        return owner.widgetObjs.of(w, () -> LuaValue.userdataOf(new LuaWidget(w), meta(owner)));
    }

    /**
     * <b>The monitor that guards a mutation of {@code w}</b> (072.1) — {@link Widget#ui}, the {@code UI} whose
     * widget tree {@code w} is in. Every site in this layer that writes a widget, or reads one that the server
     * mutates off-thread, takes it from the widget in hand: the client holds a {@code UI} per session
     * ({@code docs/client/multi-session.md}) and locking one session's monitor while writing another session's
     * widget serialises against the wrong tick. The widget always knows, because {@code Widget.attach(UI)} sets
     * the field down the whole subtree as it enters a tree.
     *
     * <p><b>A widget that has never been in a tree has none</b>, and {@code Widget.remove()} does not take it
     * back, so this is null for a widget built and not yet added and for nothing else — every door in this
     * section hands out a widget that {@link UiApi#attach} has already put on a root, a pending one included.
     * There is nothing there to race: such a widget is reachable from the code constructing it and from nowhere
     * else, no tick walks it and no message is addressed to it. They share {@link #UNATTACHED} rather than each
     * getting a lock no second thread could take, so every site keeps one shape — mutate under a monitor — with
     * no branch and no null.
     *
     * <p><b>It refuses a SECOND tree</b> (112.2). This is called immediately before every
     * {@code synchronized(monitor(w))} in the package, which makes it the one place the client's own rule —
     * <i>the tick never holds two UI monitors at once, the addon layer's included</i>
     * ({@code docs/client/multi-session.md}) — can be held to without touching a call site. Where
     * {@link Thread#holdsLock} says this thread is already inside another live tree's monitor, the call throws
     * {@link #nested} instead of waiting on the second: the wait is one half of the ABBA the addon layer had,
     * and a message an addon can {@code pcall} is worth more than a freeze nothing can report. What is refused
     * is the <b>nesting</b> and never the crossing — a handler holding no monitor at all reaches any tree, which
     * is what the engine step is for, and {@link #UNATTACHED} is not a tree and never refuses.
     */
    static Object monitor(Widget w) {
        UI u = (w == null) ? null : w.ui;
        return (u != null) ? monitorOf(u) : UNATTACHED;
    }

    /**
     * <b>The same acquisition, addressed at a tree rather than a widget</b> (112.2) — for the two sites that
     * take a {@code UI} monitor without a widget in hand ({@code Gesture.write}, {@code Layout.sweep}), so that
     * nothing in this layer enters a tree's monitor without passing the check above.
     *
     * <p>{@link AddonManager#awaitIdle} is deliberately not routed through it: it holds none of its own and
     * takes each tree's in turn, and it runs on the shutdown's thread, where a thrown refusal would be the hang
     * that method exists to make impossible.
     */
    static Object monitorOf(UI u) {
        if(u == null)
            return UNATTACHED;
        UI held = heldOther(u);
        if(held != null)
            throw new LuaError(nested(held, u));
        return u;
    }

    /**
     * <b>Would writing {@code w} be the second tree?</b> (112.6) — the question {@link #monitor} answers by
     * throwing, asked without throwing, for the one caller that has somewhere else to put the work: the anchor
     * cascade, which hands a follower in another tree to the engine step rather than refusing a write the
     * author never made. Every other site wants the refusal — there is nothing else it could do with the
     * answer.
     */
    static boolean wouldNest(Widget w) {
        UI u = (w == null) ? null : w.ui;
        return((u != null) && (heldOther(u) != null));
    }

    /**
     * <b>A live tree this thread already holds the monitor of, that is not {@code u}</b> — {@code null} when it
     * holds none, which is the ordinary answer and the whole cost of the check on a good frame.
     *
     * <p>The same tree is never reported: {@code synchronized} is re-entrant, and a verb re-entering the tree it
     * is already inside is correct — a {@code Draw} handler writing its own window, a control's notification
     * answering on its own widget. What is refused is the <i>second</i> tree.
     *
     * <p>Only LIVE trees are walked — the addon layer and every session the engine holds state for. A {@code UI}
     * the client has already taken down is nobody's tree and cannot be part of a cycle, and the login screen's
     * is reached by nothing in this layer.
     *
     * <p><b>{@code null} asks about every tree</b> (audit2 B06) — "does this thread hold ANY live tree's
     * monitor", which is the question {@link AddonManager#callLua} asks before it waits for an addon's lock
     * and {@link AddonManager#log(Addon, String)} asks before it posts a notice. No tree is {@code null}, so
     * the exclusion simply never fires and the walk answers the first one held.
     */
    static UI heldOther(UI u) {
        UI l = AddonManager.layer();
        if((l != null) && (l != u) && Thread.holdsLock(l))
            return l;
        for(AddonManager.SessionState st : AddonManager.allStates()) {
            UI t = st.ui;
            if((t != null) && (t != u) && Thread.holdsLock(t))
                return t;
        }
        return null;
    }

    /**
     * The refusal (112.2), and it names the fix rather than the fault: which tree is already held, which one the
     * call would have taken, and where the same work is done holding neither. The verb is not named because the
     * error is raised at the acquisition and carries the Lua {@code file:line} of the call that made it, which
     * is the line the author has to move.
     */
    private static String nested(UI held, UI want) {
        return "one tree monitor at a time: this handler already holds the widget tree of " + treeName(held)
            + ", and writing a widget of " + treeName(want) + " would take a second one — two trees held at"
            + " once is the shape this client deadlocks in. A Draw handler, a control's own notification, a"
            + " gesture, a drop and a console line each run under one tree's monitor and may reach only that"
            + " tree. Do the work that crosses trees where no monitor is held: widget:on(\"Update\", fn),"
            + " hafen.event():on(\"Update\", fn) or hafen.timer():after(0, fn), all of which run on the"
            + " engine step, holding none.";
    }

    /** How a tree is named to an addon author: the layer, a character by the account it is logged in as. */
    private static String treeName(UI u) {
        if(u == null)
            return "a tree that is gone";
        if(u == AddonManager.layer())
            return "the addon layer";
        String user = io.brodgar.session.Sessions.nameof(u);
        return (user != null) ? ("the character \"" + user + "\"") : "the login screen";
    }

    /** The stand-in monitor for a widget that is in no tree — see {@link #monitor(Widget)}. */
    private static final Object UNATTACHED = new Object();

    /** The {@code LuaWidget} behind a Lua value, or {@code null} for anything that is not a Widget object. */
    static LuaWidget resolve(LuaValue v) {
        if((v == null) || !v.isuserdata())
            return null;
        Object o = v.touserdata();
        return (o instanceof LuaWidget) ? (LuaWidget)o : null;
    }

    // ---- the per-addon intern cache + metatable ---------------------------------------------------

    /**
     * <b>Re-point this addon's handle from one widget to the widget that replaced it</b> — the face setter's
     * rebuild (040.2, {@link UiApi#rebuild}), and the one thing that may ever move an entry in
     * {@link Addon#widgetObjs}.
     *
     * <p>It is the {@link #wdg} field that matters: {@code hafen.ui():button()} handed a userdata to Lua, the
     * author is chaining setters onto it, and the widget under it is being swapped mid-statement. Re-pointing
     * the field keeps that value <i>the same object</i>, so {@code ==} still holds and the very next verb in
     * the chain addresses the new widget; moving the cache entry keeps the intern promise, so a fresh lookup of
     * the new widget through any other door hands back that same value rather than minting a second one.
     *
     * <p>A collected (or never-minted) entry is nothing to move: the next lookup mints one on the new widget,
     * which is the same answer. Other addons' caches are deliberately untouched — one of them holding the old
     * widget sees it go stale, which is exactly what happened to it.
     */
    static void rekey(Addon owner, Widget from, Widget to) {
        LuaWidget h = resolve(owner.widgetObjs.rekey(from, to));
        if(h != null)
            h.wdg = to;
    }

    /**
     * This addon's <b>Widget metatable</b> ({@link Addon#widgetMeta}), built on the first handle it mints.
     *
     * <p>Called from inside the mint and from nowhere else, so the {@link Interned} lock is what makes the
     * check and the build one act and what publishes the finished table. The other per-addon metatables get
     * that from {@link Addon#luaLock}, and this one may not: a widget handle is minted for an event payload
     * as well, and that runs <i>beside</i> this addon's Lua rather than inside it.
     */
    private static LuaValue meta(Addon owner) {
        if(owner.widgetMeta == null)
            owner.widgetMeta = buildMeta(owner);
        return owner.widgetMeta;
    }

    // ---- the Widget metatable ----------------------------------------------------------------------

    /**
     * The per-addon metatable: {@code __index} = the methods table <b>through {@link Refusal#closedIndex}</b>,
     * plus {@code __tostring}/{@code __name}. The indirection is what makes a verb this type does not answer
     * throw naming the ones it does, instead of reading as plain {@code nil} and failing one line later as
     * "attempt to call a nil value"
     * — pointing {@code __index} straight at the methods table is the mistake that hid the cut on two earlier
     * entities.
     */
    private static LuaValue buildMeta(Addon owner) {
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, Refusal.closedIndex("widget", methods(owner),
            "a widget"));
        mt.set("__name", LuaValue.valueOf("Widget"));
        mt.set("__tostring", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaWidget h = resolve(self);
                return LuaValue.valueOf((h == null) ? "Widget(?)" : h.toString());
            }
        });
        return mt;
    }

    /**
     * The method set (029.1 the reads + 029.2 creation, geometry and visibility + 029.3 the items relation and the
     * container lifecycle). Every reader re-reads through the widget and answers {@code nil}/empty once it is stale;
     * {@code :exists()} always answers. The metatable is per-addon, so the closures can capture the {@code owner}
     * the child handles, the walk callback, the font override and the OWNED/BORROWED test all need.
     *
     * <p><b>Writes on a stale widget are a silent no-op that still chains</b> — there is nothing to move, size or
     * destroy, and refusing would make every write site guard {@code :exists()} first. The OWNED check runs only
     * on a live widget, for the same reason: a dead widget's provenance is no longer knowable from the tree.
     */
    private static LuaTable methods(final Addon owner) {
        LuaTable m = new LuaTable();
        // type() — the class simple name ("Inventory", "Label", "Button"), climbing past anonymous subclasses.
        m.set("type", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Widget w = live(handle(self, "type"));
                return (w == null) ? LuaValue.NIL : LuaValue.valueOf(typeName(w));
            }
        });
        // role() — 030.1: WHAT this widget is, in the selector vocabulary ("window", "inventory", "button", …), or
        // nil when nothing classifies it. The inverse question to the one the font scopes answer, and the half a
        // selector needs; see LuaWidget.role for why an unknown widget answers nil rather than a guess.
        m.set("role", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Widget w = live(handle(self, "role"));
                if(w == null)
                    return LuaValue.NIL;
                String r = role(w);
                return (r == null) ? LuaValue.NIL : LuaValue.valueOf(r);
            }
        });
        // ---- what a widget can say about ITSELF (094, A-105/A-106/A-107/A-108) -----------------------
        // Four facts the bridge already had and did not publish. Each is one closure, and each deletes a
        // workaround measured in a shipped addon (audit 16-consumer-evidence.md).

        // session() — W2: the Session whose TREE this widget stands in, or nil for one in the addon layer,
        // which belongs to no character. The model's headline is that the session is the address, and a
        // widget is looked up THROUGH a session (s:ui():find(sel)) -- but until 094 it could not say which
        // one it came back from, so `eventstack` mapped every session's root and walked up to 64 parents per
        // message, with a bounded memo in front of it because "a per-message walk is a per-message loop".
        m.set("session", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Widget w = live(handle(self, "session"));
                String user = AddonManager.userOf(w);
                return (user == null) ? LuaValue.NIL : LuaSession.of(owner, user);
            }
        });
        // events() — W3: the event keys this widget answers, as a plain array of strings (a list of names
        // stays an array, 091's A-074 rule). widgetKeys() already computes exactly this list on every :on
        // call, to BUILD THE REFUSAL -- so the answer existed and only the error message could see it, and
        // the only way to ask was to subscribe and catch the failure, which leaves a live subscription
        // behind as the side effect of a question.
        m.set("events", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Widget w = live(handle(self, "events"));
                LuaTable t = new LuaTable();
                if(w != null) {
                    List<String> keys = widgetKeys(owner, w);
                    for(int i = 0; i < keys.size(); i++)
                        t.set(i + 1, LuaValue.valueOf(keys.get(i)));
                }
                return t;
            }
        });
        // owned() — W5: is this widget YOURS (built through hafen.ui()) or the client's? Provenance is the
        // rule that decides what you may write to it -- ui/widget.md builds a section on it -- and it was a
        // field of the snapshot alone, so `widgetstack` read it as w:info().owned. Every other fact a widget
        // carries has a verb. The snapshot field stays.
        m.set("owned", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Widget w = live(handle(self, "owned"));
                return LuaValue.valueOf((w != null) && (ownedContent(owner, w) != null));
            }
        });
        // is(sel) — W6: does THIS widget match that selector? The predicate the selector language never had,
        // so `widgetstack` built candidate selectors and ran each one over the whole live tree to see which
        // hit, reporting "what the answer cost, in tree walks" because there was no cheaper way to ask.
        //   Named `is` and not `matches` by D3: 088 took w:match(sel) for the search-inside verb, and the two
        // would be one letter apart with opposite meanings -- :match searches BELOW w, :is asks about w. It
        // is not the `is` prefix D1 banned either: that rule is about boolean PROPERTIES, which are read/write
        // pairs, and this takes an argument and can never be one.
        m.set("is", new VarArgFunction() {
            public Varargs invoke(Varargs a) {            // w:is(sel) → self=arg1, sel=arg2
                Widget w = live(handle(a.arg1(), "is"));
                Selector sel = UiApi.selArg(Args.required(a, 2, "widget:is", "selector"),
                                            "widget:is(selector)");
                if(w == null)
                    return LuaValue.FALSE;
                // Under the widget's own tree, which is what Selector.matches asks for: a match walks
                // parents and reads captions a Loader thread re-links and rewrites.
                synchronized(monitor(w)) {
                    return LuaValue.valueOf(sel.matches(w));
                }
            }
        });
        // res() — 030.1: the widget's RESOURCE name ("gfx/hud/…"), the stable server-published key [title=] only
        // approximates (D-063), or nil where the widget has none. This is what [res=] matches (by substring), so it
        // is also how you find out what to write there.
        m.set("res", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Widget w = live(handle(self, "res"));
                if(w == null)
                    return LuaValue.NIL;
                String r = resName(w);
                return (r == null) ? LuaValue.NIL : LuaValue.valueOf(r);
            }
        });
        // picture() — 063.3: the resource name of the picture a widget SHOWS, or nil where it shows none. The
        // read :res() is not: :res() names the resource a widget's own CODE came from, which is a handful of
        // server-published widgets, while most of the client's chrome is an ordinary Java class holding an
        // ordinary picture out of the game's art. This is that name, and it is the one line the inspector was
        // missing on nearly every widget it hovers.
        //   No argument, ever. A picture is CHOSEN by hafen.ui():image():source(h) and a button's faces by
        // widget:image(up, down), so an arity here would be a third spelling of a write two verbs already own
        // — the refusal names both rather than silently ignoring what was passed.
        m.set("picture", new VarArgFunction() {
            public Varargs invoke(Varargs a) {            // w:picture() → narg 1, and there is no other arity
                if(Args.passed(a, 2))
                    throw new LuaError("widget:picture() takes no arguments — it NAMES the picture a widget"
                        + " shows and does not choose it. widget:res() is the resource a widget's own code came"
                        + " from, and widget:image(up, down) gives a button you are building its faces;"
                        + " hafen.ui():image():source(h) is what sets a picture control's content.");
                Widget w = live(handle(a.arg1(), "picture"));
                String p = pictureName(w);
                return (p == null) ? LuaValue.NIL : LuaValue.valueOf(p);
            }
        });
        // id() — the SERVER widget id, or nil for a client-only widget (the facade-safe WidgetRef, P1).
        m.set("id", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Widget w = live(handle(self, "id"));
                if(w == null)
                    return LuaValue.NIL;
                int id = w.wdgid();
                return (id < 0) ? LuaValue.NIL : LuaValue.valueOf(id);
            }
        });
        // children() — a 1-based array of child Widget objects in tree order (empty for a leaf / a stale widget).
        m.set("children", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                final LuaWidget h = handle(self, "children");
                return LuaCollection.create("widget:children()", new LuaCollection.Source() {
                    public List<LuaValue> members() {
                        List<LuaValue> out = new ArrayList<LuaValue>();
                        Widget w = live(h);
                        if(w != null) {
                            for(Widget c : kids(w))
                                out.add(of(owner, c));
                        }
                        return out;
                    }

                    public String noGet() {
                        return "a child has no key of its own: widget:children():find(filter) is the search,"
                            + " widget:children():list()[n] takes a position, and a SELECTOR is"
                            + " widget:match(sel) / widget:matchAll(sel)";
                    }
                }, null);
            }
        });
        // parent() / parent(w) — the enclosing Widget object, and (039.6) the builder setter that chooses it.
        // The read is unchanged: nil at the root and once stale. The write re-homes a surface this addon built,
        // and it is what replaced the old `parent = "gameui"` string — a widget is named by a Widget, not by a
        // word, which is the second vocabulary 032.2 deleted from this section for exactly the same reason.
        // 074.1: THE ADDON LAYER is the default — a surface of yours is built into the tree above the
        // sessions — and s:ui():match("@GameUI") is the HUD, which is one session's.
        //
        // Legal only while the surface is still being BUILT (before its arming tick). Re-homing one the user is
        // already looking at is a capability this API never had, and the honest place to refuse it is here: the
        // message names widget:position(x, y), which is what moving a window on screen has always been.
        //
        // ...AND THE OTHER DIRECTION, WHICH IS THE SAME VERB. On a widget the CLIENT built, w:parent(p) takes it
        // INTO a surface of yours and w:parent(nil) puts it back — the fourth write of the native family
        // (:position, :size, :visible, and standing one in the world), carrying a restore like every one of
        // them. It is one verb because it is one operation: "which widget does this hang under". The client's
        // own drawing comes with it, which is the whole point — a minimap, a portrait, a meter is a picture no
        // addon can reproduce, and this is the only reach there is to putting one inside chrome of your own.
        m.set("parent", new VarArgFunction() {
            public Varargs invoke(Varargs a) {            // w:parent() → narg 1 · w:parent(p) → narg 2
                LuaValue self = a.arg1();
                Widget w = live(handle(self, "parent"));
                // NOT Args.written: an explicit nil MEANS something here (put a native widget back), and the
                // read arity is the absent one. The refusal for a nil on one of the addon's OWN surfaces is
                // written below, where it is true.
                LuaValue v = Args.passed(a, 2) ? a.arg(2) : null;
                if(v == null) {
                    Widget p = (w == null) ? null : w.parent;
                    // audit2 B08 (pk-02): AND THE KIN WINDOW IS NOT WALKABLE FROM INSIDE. kin:widget() hands
                    // back a row that lives in the BuddyWnd, whose `charpass` sibling the server fills with
                    // the character's hearth secret; the walk up and back down was the whole reach. The row
                    // has no parent, so the crossing back into the widget tree ends where it arrived. The
                    // window itself still answers :parent() — it is what is INSIDE it that is not addressable
                    // from a handle minted in there.
                    if(insideCredentialWnd(p))
                        return LuaValue.NIL;
                    return (p == null) ? LuaValue.NIL : of(owner, p);
                }
                if(w == null)                             // a write on a stale widget: the 029.2 chaining no-op
                    return self;
                if(ownedContent(owner, w) == null)        // one of the CLIENT's: the native direction
                    return rehomeNative(owner, self, w, v);
                if(v.isnil())
                    throw Args.nilRefused("widget:parent", "w");
                Owned c = owned(owner, w, "parent(w)");
                if(!c.pending())
                    throw new LuaError("widget:parent(w) chooses the parent while the widget is being BUILT, and"
                        + " this one is already on screen — move it with widget:position(x, y) instead");
                LuaWidget h = resolve(v);
                if(h == null)
                    throw new LuaError("widget:parent(w) expects a Widget — a surface of yours is in the addon"
                        + " layer unless you name one, and s:ui():match(\"@GameUI\") is the HUD");
                Widget p = live(h);
                // 125.1: THE PARENT LEFT THE TREE, and that is not a mistake anyone can guard against. The
                // handler holding it runs on the step AFTER the widget arrived and holds no tree monitor, so
                // :exists() ahead of this call is check-then-use with nothing across it — the client destroys
                // item icons in batches, and every queued handler then holds a dead one. So the write STOPS,
                // as every other write on a stale handle does. Stopping here means ABANDONING the receiver:
                // it is a surface still being built, it has painted nothing, and left in the layer it would
                // answer :exists() true at the client's default place with a caller's label built into it.
                // This is :destroy()'s own body over the Owned this verb already holds.
                if(p == null) {
                    UiApi.homeInside(w);                  // anything of the client's inside goes home first
                    synchronized(monitor(w)) { c.kill(); }
                    UiApi.dropPending(c);
                    owner.widgets.remove(c);
                    return self;                          // ...and the chain behind it takes the 029.2 no-op
                }
                if(p == w.parent)
                    return self;
                // 072.1: the DESTINATION's monitor, and this is the one site that has to choose — a re-home
                // writes two parents' child lists, and the lock direction forbids taking both (see
                // docs/client/multi-session.md). `p` is the tree the widget is in when the block ends, so it is
                // the one that names the mutation.
                //   074.1: AND THE TWO ARE NOT ONE UI. `w` was built into the addon layer and `p` may be one of
                // the client's own windows, which is a session's tree — so the subtree is carried across with
                // Widget.reattach, or `ui` would go on naming the layer while the widget lives in a session and
                // dies with it. Legal only while the surface is pending, which is checked above: nothing moving
                // here holds a grab, the focus, or a server widget id.
                synchronized(monitor(p)) {
                    Coord at = w.c;
                    rehome(w);                            // detach from ui.root — a move, and NOT a death (061.7)
                    if(w.ui != p.ui)
                        w.reattach(p.ui);
                    // Widget.add does NOT route through addchild, and a Scrollport-shaped parent only overrides
                    // addchild -- a plain add() here would drop the child beside the bar instead of inside the
                    // scrolling area (040.8's whole trap), so this one control redirects into its own container.
                    if(p instanceof CScrollport)
                        ((CScrollport)p).cont.add(w, at);
                    else
                        p.add(w, at);                     // ...and re-home it, keeping the place it was given
                }
                return self;
            }
        });
        // position() / position(x, y) / position(nil) — ARITY IS THE VERB (the 018 options shape, 029.2; the same
        // three arities as :size): no args READS the position within the parent as {x=,y=} (widget-local DESIGN
        // px), two numbers MOVE the widget, and nil DROPS your move and puts back what the widget was at before
        // you first touched it. Both writes chain. :move() is hard cut.
        //
        // THE UNIT IS THE DESIGN PIXEL (058.1) — the space the client's own art is authored in, converted at this
        // very edge by Px and nowhere below it. w:position(30, 20) reads back {30, 20} at every UI scale, which is
        // the whole claim: an addon's numbers are the same numbers on every client.
        //
        // SINCE 036.1 IT ANSWERS ON A NATIVE WIDGET (feature E) — it moves `c`, the very field the user's own drag
        // writes, never a draw-time offset (which would make the widget draw where it cannot be clicked). Touching
        // one records the stock value first (Addon.movedNative), so :reload/disable gives it back and, above all,
        // the client's own position store never learns about us: GameUI.savewndpos asks for the stock coordinate.
        //
        // AND SINCE 036.2 IT IS A LEVEL OF THE CASCADE, not a write beside it (D-077): the verb is the HAND-NAMED
        // top of the same fold a sheet's `pos` rule feeds, so it wins over every rule that merely matched the
        // widget -- and position(nil) drops back to THE RULE when one still names it, reaching the stock value
        // only when no level does. Layout.apply is what decides; this verb only says what this addon wants.
        // position() — DESIGN PIXELS within the parent, and deliberately NOT a Position (spec 039 §2.7): the verb asks
        // "where is this thing, in the space it lives in", and a widget lives on the screen. Now that a place in
        // the world is a TYPE, handing this to s:player():move throws instead of walking you somewhere wrong.
        m.set("position", new VarArgFunction() {
            public Varargs invoke(Varargs a) {            // :position() → narg 1 · (nil) → narg 2 · (x,y) → narg 3
                LuaValue self = a.arg1();
                Widget w = live(handle(self, "position"));
                if(a.narg() < 2)
                    return ((w == null) || (w.c == null)) ? LuaValue.NIL : xyTable(Px.out(w.c));
                if(a.narg() < 3) {                        // w:position(nil) — undo OUR move, back to the stock value
                    if(!a.arg(2).isnil())                 // w:position(x) is a mistake, not an undo
                        throw new LuaError("widget:position(x, y) takes BOTH coordinates; widget:position() reads"
                            + " the position and widget:position(nil) drops your addon's move and restores the"
                            + " stock one");
                    if(w != null)                         // a stale widget: the 029.2 silent chaining no-op
                        UiApi.releaseMoved(owner, w, true);
                    return self;
                }
                Coord to = pixels(a, "widget:position", "x", "y");          // DESIGN pixels, as written
                if(w != null) {
                    boolean own;
                    synchronized(monitor(w)) {
                        own = ownedContent(owner, w) != null;
                        if(!own) {
                            Moved rec = recordMoved(owner, w);     // BORROWED: name the level, then resolve it
                            rec.wantPos = Layout.Anchor.at(to);    // 036.3: the parent's top-left, plus (x, y)
                            rec.posSeq = Layout.nextSeq();         // 058.3: the level speaks the sheet's own space,
                        } else {                                   //   and Anchor.resolve is where it converts
                            w.move(Px.in(to));                     // your own widget: no layer, no cascade...
                        }
                    }
                    // 112.6: the fold and the followers BELOW the block — a follower's anchor may point
                    // into another tree, and that is a second monitor while this one is still held.
                    if(!own)
                        Layout.apply(w);
                    else
                        Layout.moved(w);                           // ...but an anchor may still hang off it (036.3)
                }
                return self;
            }
        });
        // size() / size(w) / size(w, h) / size(nil) — FOUR arities now, and the same DESIGN PIXELS :position
        // speaks (058.1). The write resizes the CONTENT and repacks the chrome around it (so a window's frame
        // follows), which is why what :size() reads back on a window is the outer box and not the pair you
        // passed; the undo restores that outer box exactly (LuaWidget.sizeArg — and it restores the DEVICE value
        // it recorded, so the stock box never round-trips through design and back).
        //
        // :size(w) — ONE NUMBER — IS THE HEIGHT THE ART GIVES IT (058.4), and it is the arity this whole feature
        // exists for. A control's height is a fact of the client's own pictures: a Button is exactly `hs` tall
        // because that is where its bottom border is drawn, and an addon that guesses 20 loses that border. So
        // the width is the addon's and the height is Owned.minsz()'s, and a two-number write UNDER that minimum
        // RAISES naming both the number and this arity — rather than clamping up to it, which would leave a
        // meaningless integer in the source, or silently clipping, which is what happens today.
        m.set("size", new VarArgFunction() {
            public Varargs invoke(Varargs a) {  // w:size() → narg 1 · w:size(nil)/(w) → narg 2 · w:size(w,h) → narg 3
                LuaValue self = a.arg1();
                Widget w = live(handle(self, "size"));
                if(a.narg() < 2)
                    return ((w == null) || (w.sz == null)) ? LuaValue.NIL : whTable(Px.out(w.sz));
                if(a.narg() < 3) {
                    if(a.arg(2).isnil()) {                // w:size(nil) — undo OUR resize, back to the stock value
                        if(w != null)
                            UiApi.releaseMoved(owner, w, false);
                        return self;
                    }
                    // w:size(w) — the width; the art answers for the height
                    int width = Args.integer(a, 2, "widget:size", "w", "a width in design pixels");
                    if(w == null)                         // a write on a stale widget: the 029.2 chaining no-op
                        return self;
                    Owned content = ownedContent(owner, w);
                    Coord min = (content == null) ? null : content.minsz();
                    if(min == null)
                        throw new LuaError("widget:size(w) sets the width and leaves the height to the control's"
                            + " own ART, and " + typeName(w) + " has none to ask — widget:size(w, h) sets both,"
                            + " and widget:pack() sizes a widget to what is inside it.");
                    Coord dmin = Px.out(min);
                    if(width < dmin.x)
                        throw tooSmall(w, "widget:size(w)", width, dmin.x, "wide");
                    synchronized(monitor(w)) {
                        // The DEVICE height, not Px.in of the design one: in(out(d)) may land a device pixel
                        // under the art's own box, and a pixel under is a border that does not draw.
                        content.widget().resize(Coord.of(Px.in(width), min.y));
                        if(content.widget() != w)         // a control that is a small tree: refit what wraps it
                            w.pack();
                    }
                    // 036.3: a corner anchor reads the box that just changed — and 112.6: below the
                    // block, since that anchor's own widget may stand in another tree.
                    Layout.moved(w);
                    return self;
                }
                Coord to = pixels(a, "widget:size", "w", "h");              // DESIGN pixels, as written
                if(w != null) {
                    Owned content = ownedContent(owner, w);
                    Coord dev = Px.in(to);
                    if(content != null) {
                        Coord min = content.minsz();
                        if(min != null) {                 // a control: the art has a box it will not fit under
                            Coord dmin = Px.out(min);
                            if(to.y < dmin.y)
                                throw tooSmall(w, "widget:size(w, h)", to.y, dmin.y, "tall");
                            if(to.x < dmin.x)
                                throw tooSmall(w, "widget:size(w, h)", to.x, dmin.x, "wide");
                            if(to.y == dmin.y)            // exactly the minimum ⇒ exactly the art's own height
                                dev = Coord.of(dev.x, min.y);
                        }
                    }
                    synchronized(monitor(w)) {
                        if(content == null) {             // BORROWED (036.1): the layer remembers, then resizes
                            Moved rec = recordMoved(owner, w);
                            rec.wantSize = to;            // 036.2: ...and the resize is the cascade's to make
                            rec.sizeSeq = Layout.nextSeq();   // 058.3: in design px, like the rule beneath it
                        } else {
                            content.widget().resize(dev);
                            if(content.widget() != w)     // a window: refit the chrome around the resized content
                                w.pack();
                        }
                    }
                    if(content == null)                   // 112.6: below the block — see :position above
                        Layout.apply(w);
                    else
                        Layout.moved(w);                  // 036.3: a corner anchor reads the box that just changed
                }
                return self;
            }
        });
        // draggable() / draggable(h) / draggable(nil) — 062: HAND THIS WIDGET TO THE USER, and say what they
        // press to move it. Arity is the verb, the same three the placement verbs have: the bare call reads the
        // handle YOUR addon armed (nil when it armed none), one argument arms, and nil drops it. Both writes
        // chain, and the read hands back the very Widget object you passed, so `w:draggable() == grip` holds.
        //
        // THE HANDLE IS A WIDGET, which is what keeps this one verb instead of a vocabulary of edges and zones:
        // the target itself drags the whole thing, a grip adopted into it (widget:parent(w) takes any widget in
        // the tree) drags only from there, and so does a button of yours somewhere else entirely.
        //
        // WHAT A DRAG WRITES IS YOUR :position LEVEL — not a field beside it. So the verbs read where it landed,
        // widget:position(nil), widget:revert(), :reload and disable all give the stock place back, the client's
        // own off-screen clamp has the last word, and GameUI's position store goes on writing what the USER
        // placed. Unprotected, like every other thing an addon says about where the client's own widgets sit.
        //
        // TWO ADDONS MAY ARM ONE TARGET (a position is not a toggle): the gesture moves it ONCE and writes both
        // levels, so a nil from either is invisible on screen, each nil drops only its own, and both Dragged
        // handlers fire. A STALE target is the 029.2 silent chaining no-op, and 125.2 gives a stale HANDLE the
        // same answer: nothing is armed, widget:draggable() reads nil, and the call chains.
        m.set("draggable", new VarArgFunction() {
            public Varargs invoke(Varargs a) {        // :draggable() → narg 1 · (nil)/(h) → narg 2
                LuaValue self = a.arg1();
                Widget w = live(handle(self, "draggable"));
                if(!Args.passed(a, 2)) {
                    Widget h = (w == null) ? null : Gesture.handleOf(owner, w, Gesture.Mode.DRAG);
                    return (h == null) ? LuaValue.NIL : of(owner, h);
                }
                LuaValue v = a.arg(2);
                if(v.isnil()) {                       // w:draggable(nil) — drop OUR binding, and only ours
                    if(w != null)                     // a stale widget: the 029.2 chaining no-op
                        Gesture.drop(owner, w, Gesture.Mode.DRAG);
                    return self;
                }
                LuaWidget hh = resolve(v);
                if(hh == null)
                    throw new LuaError("widget:draggable(h) expects a Widget — the handle the user presses to"
                        + " drag this one. widget:draggable() reads it, widget:draggable(nil) drops it");
                Widget hw = live(hh);
                // 125.2: THE HANDLE LEFT THE TREE, which is not a mistake anyone can guard against — the grip
                // you armed is a widget like any other and the client may have destroyed it between the step
                // that handed it to you and this call, with no :exists() of yours able to sit inside that
                // instant. So the write STOPS: nothing is armed, nothing recorded, and widget:draggable()
                // answers nil — which is the read that was always the way to ask what is armed.
                if(hw == null)
                    return self;
                if(w == null)                         // a write on a stale widget: the 029.2 chaining no-op
                    return self;
                // The ONE handle a Window refuses is the window itself — its caption already drags it, and two
                // drags on one press would move it twice. Any OTHER handle on a Window is the case this feature
                // exists for, so it is accepted.
                if((hw == w) && (w instanceof Window))
                    throw new LuaError("widget:draggable(h) with the window ITSELF is what a Window's caption"
                        + " already does — pass a handle of your own (build one with :parent(win)) to drag it"
                        + " from somewhere else");
                Gesture.arm(owner, w, hw, Gesture.Mode.DRAG);
                return self;
            }
        });
        // resizable() / resizable(h) / resizable(nil) — 062: the SAME three arities as :draggable, over the same
        // gesture with the other half of the layout level under it. The client gives this one to exactly one
        // window in the game (the map, through DefaultDeco.dragsize), so almost nothing else on screen can be
        // sized by the person using it.
        //
        // THE TOP-LEFT STAYS PUT, which is the client's own rule — Window.resize sizes and never places, which is
        // why its single grip is the bottom right. So the gesture writes the SIZE half and only that, and it never
        // goes under one design pixel each way: a box of nothing is a widget the user can no longer find.
        //
        // WHAT IT WRITES IS YOUR :size LEVEL, so widget:size() reads the box it landed at, widget:size(nil),
        // widget:revert(), :reload and disable all give the stock box back, and a window that packs itself around
        // its own contents makes the whole gesture INERT rather than an error — the same answer widget:size(w, h)
        // gives on those windows, honoured and undone by the client before the write returns.
        //
        // There is no refusal for the target as its own handle here: a Window's caption drags it and nothing in
        // the client's chrome resizes it from the whole frame, so `win:resizable(win)` says something new. Where
        // the client's own corner sizer IS live, the two gestures both run and the last one to write wins.
        m.set("resizable", new VarArgFunction() {
            public Varargs invoke(Varargs a) {        // :resizable() → narg 1 · (nil)/(h) → narg 2
                LuaValue self = a.arg1();
                Widget w = live(handle(self, "resizable"));
                if(!Args.passed(a, 2)) {
                    Widget h = (w == null) ? null : Gesture.handleOf(owner, w, Gesture.Mode.SIZE);
                    return (h == null) ? LuaValue.NIL : of(owner, h);
                }
                LuaValue v = a.arg(2);
                if(v.isnil()) {                       // w:resizable(nil) — drop OUR binding, and only ours
                    if(w != null)                     // a stale widget: the 029.2 chaining no-op
                        Gesture.drop(owner, w, Gesture.Mode.SIZE);
                    return self;
                }
                LuaWidget hh = resolve(v);
                if(hh == null)
                    throw new LuaError("widget:resizable(h) expects a Widget — the handle the user presses to"
                        + " resize this one. widget:resizable() reads it, widget:resizable(nil) drops it");
                Widget hw = live(hh);
                if(hw == null)                        // 125.2: a dead HANDLE, exactly as :draggable takes one
                    return self;                      // — nothing armed, widget:resizable() reads nil, chains
                if(w == null)                         // a write on a stale widget: the 029.2 chaining no-op
                    return self;
                Gesture.arm(owner, w, hw, Gesture.Mode.SIZE);
                return self;
            }
        });
        // remember() / remember(name) / remember(nil) — 062: MAKE THIS WIDGET'S PLACE SURVIVE THE SESSION, under
        // a name of yours. The third verb of the same family and the same three arities: the bare call reads the
        // name your addon remembers this widget under (nil when it remembers it under none), a string remembers
        // it, and nil stops.
        //
        // IT APPLIES WHAT THE NAME HOLDS AT THE INSTANT IT IS CALLED, which is the whole verb: there is no
        // second call to pair it with and no moment to schedule, because the only correct moment to put a place
        // back is the moment you say the place is remembered. What it applies is your :position and :size
        // LEVELS, written exactly as the two verbs write them — so everything true of those is true here, the
        // clamp and the two nil undos included, and a widget:position(x, y) AFTER it wins by being the later
        // level.
        //
        // WHAT IS SAVED IS WHERE YOUR LEVELS STAND, whenever the layer writes to disk — after a gesture, on the
        // save timer, and at teardown. So the user dragging it is remembered with no handler of yours, and so is
        // a place you wrote yourself. It is PER CHARACTER, like a per-character saved variable and for the same
        // reason, so before SessionEnteredWorld there is nothing to put back and the call says so rather
        // than applying an
        // empty record. No manifest declaration: the slot is the layer's own file beside the addon's store.
        //
        // ONE NAME, ONE WIDGET, which is what makes the name answerable: a second widget under a name this addon
        // already holds RAISES, naming the one holding it. Renaming a widget you already remember is a change of
        // mind and is accepted, and the record the old name held stands — DROPPING IS NOT FORGETTING. That is
        // the rule the whole verb turns on: widget:revert(), :reload and disable drop the binding and keep the
        // record, and widget:remember(nil) is the only thing that deletes it.
        m.set("remember", new VarArgFunction() {
            public Varargs invoke(Varargs a) {        // :remember() → narg 1 · (nil)/(name) → narg 2
                LuaValue self = a.arg1();
                Widget w = live(handle(self, "remember"));
                if(!Args.passed(a, 2)) {
                    String nm = (w == null) ? null : rememberedName(owner, w);
                    return (nm == null) ? LuaValue.NIL : LuaValue.valueOf(nm);
                }
                LuaValue v = a.arg(2);
                if(v.isnil()) {                       // w:remember(nil) — the name goes, and so does the record
                    if(w != null)                     // a stale widget: the 029.2 chaining no-op
                        rememberForget(owner, w);
                    return self;
                }
                // Args.str, not isstring(): a NUMBER answers isstring() in Lua, so the laxer test would let a
                // name that is a number through and remember a window under "42" — a key nothing in the addon
                // would ever spell that way again.
                Args.str(v, "widget:remember", "name",
                         "the name YOUR addon saves this widget's place and box under");
                if(w == null)                         // a write on a stale widget: the 029.2 chaining no-op
                    return self;
                rememberAs(owner, w, v.tojstring());
                return self;
            }
        });
        // visible() / visible(b) — A BOOLEAN PROPERTY IS A PROPERTY (spec 039 §2.2, R6): the read says whether it is
        // currently drawn (false once stale) and the write says what it should be. :show() and :hide() are a HARD
        // CUT — two spellings for one write is the dual style the grammar removes, and they were the last pair in
        // the API where the value lived in the verb's NAME instead of its argument.
        //
        // It is the ONLY PROPERTY write that answers on a native widget (029.2) — :on(key, fn) reaches one too
        // (041.3), but a subscription is not a property (see below). Hiding a NATIVE widget registers it on the
        // addon's restore list, so :reload/disable puts it back exactly as it was (UiApi.teardownHidden) — that is
        // what replaces hafen.ui.adopt, which used to hide a window just so you could read it. A hidden server
        // widget stays bound to its id (still receiving uimsg/addchild), so it remains a perfectly live model.
        // visible(true) gives it back and drops the record — ending a substitution first, if the window carried one,
        // and refusing outright when ANOTHER addon holds the record (audit2 B08): one window has one owner in
        // both directions. The write chains; visible(nil) is refused (§2.9 — there
        // is nothing here to undo, and a nil that silently became a READ is the bug that rule exists for). One
        // receiver refuses the write in BOTH directions and names a verb of its own — the radial menu, below.
        m.set("visible", new VarArgFunction() {
            public Varargs invoke(Varargs a) {            // w:visible() → narg 1 · w:visible(b) → narg 2
                LuaValue self = a.arg1();
                Widget w = live(handle(self, "visible"));
                LuaValue v = Args.written(a, 2, "widget:visible", "b");
                if(v == null)
                    return LuaValue.valueOf((w != null) && w.visible());
                if(w == null)                             // a write on a stale widget: the 029.2 chaining no-op
                    return self;
                // 116.3: THE RING HAS ITS OWN VERB, and this door names it. A FlowerMenu is not a window an
                // addon keeps: it lives about a second, and the generic write records a restore entry holding
                // the widget that is pruned only when the whole tree dies — so a ring hidden per right-click
                // would pile up dead records, under a one-owner rule built for something an addon holds.
                // BOTH DIRECTIONS are refused, before the borrowed/owner branch, and the READ above still
                // answers: the two doors agree on the fact and disagree only on who may write it. It is a
                // refusal written INSIDE the verb, not a Refusal row — the name did not move, one receiver
                // gained a rule.
                if(w instanceof FlowerMenu)
                    throw new LuaError("widget:visible(b): a FlowerMenu is painted or not through its own"
                        + " verb — " + FlowerMenuApi.FM + ":visible(b). The ring dies about a second after it"
                        + " opens, and this write keeps a restore record that would outlive it;"
                        + " widget:visible() still reads.");
                boolean show = Args.bool(v, "widget:visible", "b", null);
                if(show) {
                    // audit2 B08 (un-01): THE ONE-WINDOW-ONE-OWNER RULE GUARDS BOTH DIRECTIONS. This arm used
                    // to show the widget and drop only THIS addon's record, with no owner check at all — so a
                    // second addon un-hid a window the first owns and is standing in for, and the rule the
                    // `false` arm enforces three lines down was enforceable in one direction only.
                    refuseSecondOwner(owner, w, "widget:visible(true)");
                    // audit2 B08 (un-02): ...and a REPLACED window's SUBSTITUTION ends here, view and all.
                    // dropHidden removes the record and nothing else — it never reads h.view — so showing a
                    // replaced window left the stand-in standing over the restored one, and the later
                    // w:replace(nil) found no record and was inert. This is that ending, and it is a no-op on
                    // a window that carries no view.
                    UiApi.unreplace(owner, w);
                    synchronized(monitor(w)) { w.show(); }
                    dropHidden(owner, w);                 // restored by hand: teardown has nothing left to undo
                } else {
                    boolean borrowed = (ownedContent(owner, w) == null);
                    if(borrowed)
                        refuseSecondOwner(owner, w, "widget:visible(false)");   // 031.2: one window, one owner
                    synchronized(monitor(w)) { w.hide(); }
                    if(borrowed)                          // BORROWED: remember to give it back on teardown
                        recordHidden(owner, w);
                }
                return self;
            }
        });
        // on(key, fn) — THE ONE address for everything a widget can say: input on ANY widget, found or built
        // (041.3, over Widget.listen/deafen rather than the three magic hafen.hook():input tokens), plus the
        // REST of the widget vocabulary (041.4) — a control's own notifications, a surface's Draw/Tick/Drop/
        // Close, a container's ItemAdded/ItemRemoved, and Destroy on any widget at all. The vocabulary is
        // WIDGET-SPECIFIC and computed fresh each call (widgetKeys, below): a Button answers Pressed and the
        // universal five, a Label only the five, a surface adds Draw/Tick/Drop/Close, a non-control adds
        // ItemAdded/ItemRemoved. An unknown key throws naming what THIS widget does answer, at the line that
        // wrote it rather than one line later (D-125). preventDefault() is on the ev this hands
        // the handler where a key cancels, never a return value (spec R3) — arity is NOT the verb here, because
        // a subscription is not a property: :on(key, fn) always registers and returns a Sub, and :on(key) with
        // no function is a missing-argument error, not a read.
        m.set("on", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = a.arg1();
                Widget w = live(handle(self, "on"));
                LuaValue keyArg = Args.required(a, 2, "widget:on", "key");
                LuaValue fnArg = Args.required(a, 3, "widget:on", "fn");
                if(!keyArg.isstring() || !fnArg.isfunction())
                    throw new LuaError("widget:on(key, fn) expects (string, function)");
                String key = keyArg.tojstring();
                // THE TREE BEFORE THE KEY (084.5). A widget that is gone has no vocabulary of its own left to
                // read -- widgetKeys(owner, null) answers the universal five and nothing else -- so asking the
                // key first told an author their Button "has no event 'Pressed'", which is false about a
                // Button and sends them looking for a spelling that was right. The staleness is the whole
                // fault and is what the message has to say; the key is only unknown BECAUSE of it.
                if(w == null)
                    throw new LuaError("widget:on(key, fn) — this widget is no longer in the tree");
                // 097: a key that MOVED is caught before the key SET, for the same reason the bus catches
                // its own there — "a Button has no event 'Destroy'" is true and useless, while the row says
                // where the spelling went. It comes after the staleness check above, which outranks it.
                String moved = Refusal.eventKey("widget", key);
                if(moved != null)
                    throw new LuaError(moved);
                List<String> keys = widgetKeys(owner, w);
                if(!keys.contains(key)) {
                    throw new LuaError("widget:on(key, fn): a " + typeName(w)
                        + " has no event '" + key + "' — it has: " + join(keys)
                        + Controls.keyElsewhere(w, key));   // 061.3: the key is real, the address is not
                }
                return owner.widgetSubs(w).on(key, fnArg);
            }
        });
        // replacement() / replace(view) / replace(nil) — 032.1, RENAMED at 039.5 (spec §2.2): the one place in the
        // whole surface where the read and the write of one property do not mean the same thing. "Replace" is an
        // ACT; the thing standing in is a replacement, so the read takes its own noun and the write keeps the verb.
        // The one way to put your OWN window in place of one of the client's: replacement() READS the view standing
        // in for this window (or nil), replace(view) INSTALLS one, replace(nil) undoes it there and then. Both
        // writes chain, and replace() with no argument throws naming the read.
        //
        // THE TRAP IT OWNS, and the whole reason it is a verb of its own: installing hides the ENCLOSING WINDOW
        // (nativeWindowOf), not the widget you point at — replace the inventory GRID and the whole stock window
        // goes, rather than leaving its frame around a hole. widget:visible(false) still hides exactly what you point at;
        // that is the difference between the two.
        //
        // Hiding a native window TAKES ITS TOGGLE (031, D-069), and from here that toggle drives YOUR view: Tab and
        // the menu button show and hide it, and the menu tick reads its visibility. The view's fate follows the
        // substitution — replace(nil), :reload/disable, or the server destroying the window all destroy it, since a
        // custom window left standing over a container that is gone is worse than no window. One window has one
        // view: installing a different one ends the previous substitution (and destroys that view).
        //
        // WAITING IS NOT PART OF IT: s:ui():on(selector, "Added", fn) already waits, and already fires for what
        // is ALREADY open (D-068) — so the whole pattern is
        //     s:ui():on("inventory[title=Inventory]", "Added", function(w) w:replace(buildMyView(w)) end)
        m.set("replacement", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Widget w = live(handle(self, "replacement"));
                return (w == null) ? LuaValue.NIL : UiApi.installedView(owner, w);
            }
        });
        m.set("replace", new VarArgFunction() {
            public Varargs invoke(Varargs a) {            // w:replace(view|nil) → narg 2
                LuaValue self = a.arg1();
                Widget w = live(handle(self, "replace"));
                if(a.narg() < 2)
                    throw new LuaError("widget:replace() is now widget:replacement() — replace(view) installs a"
                        + " view and replace(nil) undoes it, so the read has a name of its own");
                if(w != null) {                           // a write on a stale widget: the 029.2 silent chaining no-op
                    LuaValue v = a.arg(2);
                    if(v.isnil())
                        UiApi.unreplace(owner, w);
                    else
                        UiApi.replaceWith(owner, w, v);
                }
                return self;
            }
        });
        // pack() — SIZE THIS WIDGET TO WHAT IS INSIDE IT, so an addon never computes a container's box (058.4).
        // Window or bare: the `content.widget() != w` guard that made this a no-op on a hafen.ui():widget() is
        // gone, and with it the only reason an addon still had to add up its own rows. OWNED-only, and chains.
        //
        // THE TWO CASES ARE ONE CALL, and the order is the point. Widget.pack() is resize(contentsz()), the max
        // bottom-right over the children — so a BARE surface simply packs, its children being its content. A
        // WINDOW's controls are children of the CHROME (widget:parent(win) adds them there), siblings of the
        // painted canvas rather than children of it, so packing the canvas first empties it — it has no children
        // of its own, by construction — and stops it flooring the measurement at the box it was built with. The
        // chrome then measures the controls alone (Window.contentsz skips the deco, and now meets a 0x0 canvas),
        // and the canvas is given the content area that came out of it, so a window that BOTH paints and holds
        // controls still has its full surface to paint on afterwards.
        //   061.7: AND IT ANSWERS ON ONE OF THE CLIENT'S OWN WINDOWS, where it is the same LEVEL :size(w, h)
        // is — the box the pack came out at becomes this addon's size level on Moved, so :size(nil), :reload
        // and disable all give the stock outer box back (nativePack, below). A borrowed widget that is not a
        // window refuses: what a control's box is is the client's to choose, and the window around it is what
        // refits. A window that packs itself around its own contents makes the call INERT, never an error —
        // the rule :size(w, h) already carries on those same windows.
        m.set("pack", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = a.arg1();
                Widget w = live(handle(self, "pack"));
                if(w != null) {
                    Owned content = ownedContent(owner, w);
                    if(content == null) {          // BORROWED: the client's own window, refitted as a level
                        nativePack(owner, w);
                        return self;
                    }
                    synchronized(monitor(w)) {
                        Widget cw = content.widget();
                        cw.pack();
                        if(cw != w) {              // a window: refit the chrome, then give the canvas what is left
                            w.pack();
                            cw.resize(sizeArg(w));
                        }
                    }
                    // 036.3: a corner anchor reads the box that just changed — and 112.6: below the
                    // block, since that anchor's own widget may stand in another tree.
                    Layout.moved(w);
                }
                return self;
            }
        });
        // revert() — 061.9: GIVE BACK EVERYTHING THIS ADDON HOLDS ON THIS WIDGET AND WHAT IS INSIDE IT, in one
        // call: the text level, the position and size levels, the hide record, this addon's w:rule() level,
        // every subscription it holds anywhere in that subtree, and every control it adopted into it,
        // destroyed. Each of those already has an undo of its own (:text(nil), :size(nil), sub:off(),
        // :destroy()) and teardown runs all of them on :reload and disable; what none of them does is undo a
        // WHOLE EDIT at a moment the addon chooses, which is what an addon that arms its edits by hotkey needs.
        //   THE SUBTREE IS THE SCOPE, as the tree stands right now, and that is what makes the scope
        // answerable at all: an edit is never confined to one widget — the worked example writes a caption on
        // a window, adopts a button into it and takes over the CLOSE button, which is neither of those two.
        // Being per-widget it also lets an addon that edited two windows give back one of them.
        //   IT IS A VERB, NOT A HANDLE. An object whose only method is revert() is an object standing in for a
        // verb, and it does NOT undo widget:value(v) (an act has nothing to give back) nor end a
        // widget:replace(view) (that is the alternative to editing, and widget:replace(nil) ends it). On a
        // widget this addon holds nothing on it is a no-op, and like every write here it chains.
        m.set("revert", new VarArgFunction() {
            public Varargs invoke(Varargs a) {            // w:revert() → narg 1
                LuaValue self = a.arg1();
                Widget w = live(handle(self, "revert"));
                if(Args.passed(a, 2))
                    throw new LuaError("widget:revert() gives back everything YOUR addon holds on this widget"
                        + " and what is inside it, and takes no argument — there is one edit to undo, whatever"
                        + " it was made of. widget:value(v) is an act and is not undone by it, and a"
                        + " substitution is ended by widget:replace(nil).");
                if(w != null)                             // a stale widget: the 029.2 chaining no-op
                    UiApi.revert(owner, w);
                return self;
            }
        });
        // destroy() — remove a widget this addon created (its chrome and everything in it) and drop it from the
        // owned registry. OWNED-only: a native widget is the client's, and killing it is not the addon's to do.
        m.set("destroy", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Widget w = live(handle(self, "destroy"));
                if(w != null) {
                    Owned content = owned(owner, w, "destroy()");
                    // Anything of the CLIENT's that we took into this surface goes home FIRST: kill() disposes
                    // recursively, so a minimap left inside would be destroyed with the panel around it.
                    UiApi.homeInside(w);
                    synchronized(monitor(w)) { content.kill(); }
                    UiApi.dropPending(content);   // 039.6: one built and ended in the same statement is never placed
                    owner.widgets.remove(content);
                }
                return self;              // the receiver: every ending chains
            }
        });
        // send(msg, ...) — 048.6: send an arbitrary wdgmsg FROM this widget. The escape hatch, and the
        // RECEIVER is the target, so there is no private target vocabulary beside it — no numeric server
        // widget id, and no "mapview" / "gameui" / "root" tokens. Every one of those is an ordinary handle
        // already: s:ui():node(id) for an id, s:ui():match("@MapView") for the map view and
        // s:ui():match("@GameUI") for the HUD (@Class resolves through typeName, and MapView is not
        // subclassed in this fork). The trailing args marshal exactly as the two message streams do
        // (LuaMarshal.toJava: a {x=,y=} table becomes a Coord; numbers, strings and booleans pass through).
        //   PROTECTED by the per-addon "widget.send" permission, and the gate runs FIRST (D-213) — before the
        // message name is looked at and before the widget is resolved, so an addon that never declared it is
        // told THAT rather than that it mistyped an argument.
        //   BOUND WIDGETS ONLY. A widget with no server id — one this addon built — has nothing for the server
        // to deliver to: UI.rawWdgmsg DROPS a sender whose id is < 0 with a Warning nobody reads, so the send
        // would silently go nowhere. It REFUSES instead, naming that; widget:id() is the read that answers the
        // same question ahead of the call. And unlike every other write here, a STALE widget throws instead
        // of chaining as a no-op (D-217): a message about one specific widget has nothing honest to send once
        // that widget has left the tree. Hands the Widget back, so a send chains.
        m.set("send", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = a.arg1();
                AddonManager.requirePermission(AddonManager.current(), Permission.WIDGET_SEND);
                // Args.str, not isstring(): in Lua a NUMBER answers isstring() (the coercion), so the laxer
                // test would quietly put "42" on the wire as a message name — which is exactly the silent
                // misread this grammar refuses. A message name is a string or it is a mistake.
                LuaValue msgv = Args.str(a, 2, "widget:send", "msg",
                    "the message name the server knows this widget by, \"click\", \"activate\", …");
                Widget w = live(handle(self, "send"));
                if(w == null)
                    throw new LuaError("widget:send(msg, ...): this widget is gone — it left the tree"
                        + " (widget:exists() is false). Nothing was sent.");
                if(w.wdgid() < 0)
                    throw new LuaError("widget:send(msg, ...): this widget is not BOUND — it has no server id"
                        + " (widget:id() is nil), so there is no one to send to. Only a widget the SERVER"
                        + " placed can be sent from: s:ui():match(\"@MapView\") is the map view and"
                        + " s:ui():match(\"@GameUI\") is the HUD.");
                int n = a.narg();
                Object[] args = new Object[Math.max(0, n - 2)];
                for(int i = 3; i <= n; i++)
                    args[i - 3] = LuaMarshal.toJava(a.arg(i), "widget:send");
                // audit2 B07: through the one door, like every other send here. This is the ONE verb
                // whose message name is the caller's rather than this API's, and Wire's own escape-hatch
                // row says so: it takes the tree, the monitor and the rate bound, and checks no shape,
                // because a row keyed by name would be about some other widget's message of that name.
                Wire.send(owner, AddonManager.userOf(w), "widget:send", w, msgv.tojstring(), args);
                return self;
            }
        });
        // ---- the builder setters (039.6, spec 039-uniform-api §2.5) -----------------------------------------
        // The thirteen builder keys, as verbs on the widget the builder handed back — each
        // with a matching bare read, so a surface's properties are readable after construction with no second
        // vocabulary. :position/:size/:parent are above (they already existed as reads); the rest are here.
        //
        // All of them are OWNED-only, and the reason is not symmetry: a caption, a default font and eight Lua
        // callbacks are things an AddonWidget HAS, and a native widget has nowhere to put them. The reads
        // answer nil on a borrowed widget rather than throwing, which is what every other read here does.
        //
        // title() / title(s) / title(nil) — a window's caption. A bare :widget() has no chrome to write it on, so
        // the write refuses naming the builder that does; the read answers nil there.
        //   061.5: IT ANSWERS ON ONE OF THE CLIENT'S OWN WINDOWS TOO, and there it is the same LEVEL :text(s) is
        // on a control — one record, because a widget is a window or it is a control and never both. The split
        // is the one the API already has: a TITLE is a window's caption, TEXT is everything else, and the two
        // refusals go on pointing at each other rather than one verb learning to write both.
        m.set("title", new VarArgFunction() {
            public Varargs invoke(Varargs a) {            // w:title() → narg 1 · w:title(s)/(nil) → narg 2
                LuaValue self = a.arg1();
                Widget w = live(handle(self, "title"));
                if(!Args.passed(a, 2)) {
                    if((w == null) || !(w instanceof Window))
                        return LuaValue.NIL;
                    String cap = ((Window)w).cap;
                    return (cap == null) ? LuaValue.NIL : LuaValue.valueOf(cap);
                }
                if(w == null)                             // a write on a stale widget: the 029.2 chaining no-op
                    return self;
                LuaValue v = a.arg(2);
                if(v.isnil()) {                           // w:title(nil) — drop OUR level, back to the stock cap
                    UiApi.releaseText(owner, w);
                    return self;
                }
                if(!(w instanceof Window))
                    throw new LuaError("widget:title(s) is a WINDOW's caption, and " + typeName(w) + " has no"
                        + " chrome to write it on — hafen.ui():window() is the builder that makes one. A"
                        + " control's own caption is widget:text(s), on one of the client's controls exactly as"
                        + " on one you built.");
                if(ownedContent(owner, w) != null) {
                    synchronized(monitor(w)) { ((Window)w).chcap(v.tojstring()); }
                    return self;
                }
                recordText(owner, w, v.tojstring());      // BORROWED: the caption is a level, and it restores
                return self;
            }
        });
        // font() / font(h) — the default font for THIS widget's g:text/g:atext draws (F2), not for its caption.
        // A handle from hafen.font(name) or hafen.asset(path), optionally derived; anything else resolves to the
        // stock font, exactly as the old font= key did.
        m.set("font", new VarArgFunction() {
            public Varargs invoke(Varargs a) {            // w:font() → narg 1 · w:font(h) → narg 2
                LuaValue self = a.arg1();
                Widget w = live(handle(self, "font"));
                LuaValue v = Args.written(a, 2, "widget:font", "h");
                AddonWidget c = surfaceOrNull(owner, w);
                if(v == null)
                    return (c == null) ? LuaValue.NIL : c.font();
                if(w == null)                             // a write on a stale widget: the 029.2 chaining no-op
                    return self;
                surface(owner, w, "font(h)").font(v);
                return self;
            }
        });
        // Draw/Tick/Drop/Close and ItemAdded/ItemRemoved/Destroy are GONE as chained-setter verbs (041.4): they
        // answer through the one door every other key does now, widget:on(key, fn) above — Draw/Tick/Drop/Close
        // on an owned surface, ItemAdded/ItemRemoved/Destroy on any widget. See AddonWidget (the first three)
        // and WidgetSubs (the tree-key three, event-driven off placement/removal since 042.7).
        // items() — 029.3: the items INSIDE this widget, as an array of Item OBJECTS. A RELATION on the
        // container, exactly like :children() — an Inventory (the backpack, a chest, a cupboard), an Equipory
        // (whose worn items say which slots they fill), or any widget with WItems under it (children(WItem.class)
        // is a DEEP traversal, so a whole window answers for its grid). Each item appears ONCE however many slots
        // it occupies. Read with the window VISIBLE and interactive: nothing is hidden, nothing is registered —
        // which is the whole point of deleting hafen.ui.adopt. Empty for a leaf, a non-container or a stale
        // widget. Read-only: MOVING an item is the item's own protected tier (item:take() / :drop(n) /
        // :transfer(n)).
        m.set("items", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                final LuaWidget h = handle(self, "items");
                return LuaCollection.create("widget:items()", new LuaCollection.Source() {
                    public List<LuaValue> members() {
                        Widget w = live(h);
                        return (w == null) ? new ArrayList<LuaValue>()
                                           : LuaCollection.fromArray(items(owner, w));
                    }

                    public String noGet() {
                        return "an item in a container has no key: widget:items():find(filter) is the search"
                            + " and widget:items():list()[n] takes a position";
                    }
                }, null);
            }
        });
        // item() — 103.1: the ONE item this widget DRAWS, and nil on every other widget and on a stale one.
        // The join :items() cannot make: Widget.children(Class) is a deep traversal that EXCLUDES the receiver,
        // so an item icon's own :items() is empty by construction — an addon handed an icon by a selector match
        // or a MouseDown subscription could read WHERE it is and never WHAT it is. WItem.item is public, final
        // and set in the constructor, so the answer stands from the moment the placement seam offers the widget.
        // The Item handed back is the INTERNED one, so it is == the entry the container around it lists.
        // Unprotected: a read of what is already drawn on the screen.
        m.set("item", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = a.arg1();
                if(Args.passed(a, 2))
                    throw new LuaError("widget:item() takes no arguments — an icon draws exactly one item and"
                        + " this reads it. The items INSIDE a container are widget:items(), a collection whose"
                        + " :find(filter) searches and whose :list()[n] takes a position.");
                Widget w = live(handle(self, "item"));
                if(!(w instanceof WItem))
                    return LuaValue.NIL;
                return LuaItem.of(owner, ((WItem)w).item);
            }
        });
        // group() — THE GROUP A ROW DRAWS ITS NAME IN, and nil on every other widget. The join the row itself
        // cannot make: the three lists that colour a name by group lay their rows out as
        // SListWidget.ItemWidgets, and the only thing a row says about the group is that COLOUR — which above
        // the eighth group is the ungrouped one, the same for all of them. Two kinds of row answer, and the
        // number means what the row's own list means by it:
        //   · the KIN roster's (BuddyWnd.Buddy.group) — the kin's group, the one kin:group() writes;
        //   · a village's or a realm's member row (haven.Polity.Member.group) — that polity's group for that
        //     member, which is a DIFFERENT number from their kin group, and which answers for a member the
        //     roster does not know ("???") as much as for one it does.
        // The polity's is read off the wire beside the id, because the two panels that have one keep it in a
        // field of published resource code; -1 there is a polity with no groups (a Generic one) and reads nil
        // rather than a number no group has. Unprotected: a read of what is on screen.
        m.set("group", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = a.arg1();
                if(Args.passed(a, 2))
                    throw new LuaError("widget:group() takes no arguments — it reads the polity group the"
                        + " row it is called on draws its name in. To MOVE a kin between groups, kin:group(n)"
                        + " is the write, and a colour row is driven with widget:value(n).");
                Widget w = live(handle(self, "group"));
                if(!(w instanceof haven.SListWidget.ItemWidget))
                    return LuaValue.NIL;
                Object row = ((haven.SListWidget.ItemWidget<?>)w).item;
                int g;
                if(row instanceof haven.BuddyWnd.Buddy)
                    g = ((haven.BuddyWnd.Buddy)row).group;
                else if(row instanceof haven.Polity.Member)
                    g = ((haven.Polity.Member)row).group;
                else
                    return LuaValue.NIL;
                return (g < 0) ? LuaValue.NIL : LuaValue.valueOf(g);
            }
        });
        // text() / text(s) — WHAT THE WIDGET DISPLAYS, and arity is the verb here as everywhere else (R2). The
        // read is unchanged and answers on ANY text-bearing widget, the client's own included
        // (Label/Button/Window/TextEntry), else nil — NEVER throwing, since docs/addons/api/ui/widget.md
        // publishes it as the safe best-effort read a tree-walking introspector (widgetstack) relies on for
        // every widget alike. The write (040.1) is new and answers on a CONTROL your addon built —
        // hafen.ui():button() is the first of them — because that is the only text in the tree that is yours
        // to change. A window's caption is widget:title(s), and a widget that displays nothing refuses NAMING
        // what does, rather than failing one line later as a nil call.
        //   040.7: a text entry's content has exactly one door to WRITE it through, :value(s). The read stays
        // exactly as above (best-effort, never throwing) — it is the contract this comment documents for
        // every OTHER widget, on the one type this feature happens to touch.
        //   061.5: AND IT ANSWERS ON A BORROWED CONTROL — a native Label, a Button's caption, a CheckBox's
        // label. There it is a LEVEL rather than a write: the stock caption is recorded at the first touch
        // (LuaWidget.Cap on Moved, the record :position/:size already use), a second write REPLACES the level
        // rather than stacking, and :text(nil) drops it and gives the stock one back — as do :reload and
        // disable. Unprotected, like every other thing an addon says about a widget rather than does to one:
        // what a widget SAYS never leaves the client, and what a control HOLDS is widget:value(v).
        m.set("text", new VarArgFunction() {
            public Varargs invoke(Varargs a) {            // w:text() → narg 1 · w:text(s)/(nil) → narg 2
                LuaValue self = a.arg1();
                Widget w = live(handle(self, "text"));
                if(!Args.passed(a, 2)) {
                    if(w == null)
                        return LuaValue.NIL;
                    String t = text(w);
                    return (t == null) ? LuaValue.NIL : LuaValue.valueOf(t);
                }
                if(w == null)                             // a write on a stale widget: the 029.2 chaining no-op
                    return self;
                LuaValue v = a.arg(2);
                if(v.isnil()) {                           // w:text(nil) — drop OUR level, back to the stock text
                    UiApi.releaseText(owner, w);
                    return self;
                }
                if(w instanceof CEntry)
                    throw new LuaError(Refusal.message("entry:text"));
                Owned c = ownedContent(owner, w);
                if(c != null) {
                    Controls.text(c, w, v.tojstring());
                    return self;
                }
                nativeText(owner, w, v.tojstring());      // BORROWED: the text level, or the refusal
                return self;
            }
        });
        // tooltip() / tooltip(s) — 044.5: THE LINE THAT APPEARS WHEN THE POINTER RESTS ON IT. The read answers on
        // ANY widget, the client's own included, and never throws: a plain string as given, the text of one of the
        // client's own keybound tips (the shortcut it appends is the keymap's, not the text's), else nil. The write
        // is a control YOU built, like :text(s) and for the same reason — a native widget's tooltip is the
        // client's own words about its own button. Which widget's tooltip the client would actually SHOW at a
        // point is hafen.ui():tipAt(x, y), because that is a question about a place rather than about a widget.
        m.set("tooltip", new VarArgFunction() {
            public Varargs invoke(Varargs a) {            // w:tooltip() → narg 1 · w:tooltip(s) → narg 2
                LuaValue self = a.arg1();
                Widget w = live(handle(self, "tooltip"));
                LuaValue v = Args.written(a, 2, "widget:tooltip", "s");
                if(v == null) {
                    String t = (w == null) ? null : tip(w);
                    return (t == null) ? LuaValue.NIL : LuaValue.valueOf(t);
                }
                if(w == null)                             // a write on a stale widget: the 029.2 chaining no-op
                    return self;
                Args.str(v, "widget:tooltip", "s", "the line the tooltip shows");
                owned(owner, w, "tooltip(s)");
                String s = v.tojstring();
                synchronized(monitor(w)) { w.tooltip = s.isEmpty() ? null : s; }
                return self;
            }
        });
        // focused() — 044.5: WOULD A KEYSTROKE REACH THIS WIDGET? The client resolves the keyboard down a chain of
        // focus controllers from the root, so "focused" is a property of a path and not of one widget, and this
        // answers the whole question in one read: true for a text entry the player is typing into, and true for
        // the window around it, since the key passes through it on the way. A widget standing in the 3D world
        // answers exactly as it did on the flat UI — its surface is a real place in the tree, so focus, and the
        // keys that follow it, resolve through it unchanged, which is this task's whole claim.
        //   Read-only, and an argument is refused rather than ignored: focus follows the pointer and the client's
        // own rules, and a verb that stole it would be a second way to do what clicking already does.
        m.set("focused", new VarArgFunction() {
            public Varargs invoke(Varargs a) {            // w:focused() → narg 1
                LuaValue self = a.arg1();
                if(Args.passed(a, 2))
                    throw new LuaError("widget:focused() reads whether the keyboard reaches this widget and does"
                        + " not write it — focus follows the click, exactly as it does on the flat UI");
                return LuaValue.valueOf(focusPath(live(handle(self, "focused"))));
            }
        });
        // onPress/onChange/onSubmit/onSelect/onCell are GONE as chained-setter verbs (041.4): a control's own
        // notification answers through widget:on("Pressed"/"Changed"/"Submitted"/"Selected"/"Cell", fn) above,
        // like every other key — see Controls#fire and widgetKeys.
        // image(up, down[, hover]) / image() — 040.2: THE FACE SETTER, and the second engine class behind one
        // builder. hafen.ui():button():text("Go") completes as a Button and :image(u, d) as an IButton, because
        // they are one control to an author and two widgets to the client; the I prefix is the client's own
        // implementation detail and stays out of the vocabulary (D-061), while :type() still reads "IButton" for
        // whoever wants the engine's name. Two or three faces, `hover` defaulting to `up` as the engine's own
        // two-argument constructor does. Each face is a hafen.asset handle (your file, at its own pixels) or a
        // string naming one of the CLIENT's resources ("gfx/hud/buttons/addu", taken scaled like every IButton
        // the client builds) -- the one place in the API where a string is not a path the loader refuses.
        //   Building-only, like :parent(w) and for a sharper reason: an IButton's faces are final and its box is
        // the picture, so this is not a property of a button but WHICH button it is. While the control is still
        // pending the widget is rebuilt under the same Lua handle; once armed it is refused, naming that a face
        // is chosen while the control is built. A CAPTION is not a face: :text(s) is live at any time.
        //   The read hands back { up =, down =, hover = } exactly as they were named -- and nil on a control that
        // has no face, like the captioned button it might have become instead.
        //   040.4: hafen.ui():check() completes to ICheckBox the same building-only way, but with FOUR faces --
        // widget:image(up, down, hoverUp, hoverDown) -- because a checkbox carries two persistent states rather
        // than a button's one gesture; the read hands back { up=, down=, hoverUp=, hoverDown= } there instead.
        m.set("image", new VarArgFunction() {
            public Varargs invoke(Varargs a) {            // w:image() → narg 1 · w:image(u, d[, h[, h2]]) → narg 3/4/5
                LuaValue self = a.arg1();
                Widget w = live(handle(self, "image"));
                if(!Args.passed(a, 2))
                    return Controls.faces((w == null) ? null : ownedContent(owner, w));
                if(w == null)                             // a write on a stale widget: the 029.2 chaining no-op
                    return self;
                Controls.image(owner, w, owned(owner, w, "image(up, down)"), a);
                return self;
            }
        });
        // value() / value(v) — 040.3: WHAT THE CONTROL HOLDS, the second of the six names spec 040 §1 gives the
        // whole roster: a checkbox's is a boolean, a slider's a number, a text field's a string, a progress
        // bar's a fraction — one name, read at any control, answering nil where a control has no value (a
        // label, a separator, a picture). The write dispatches to the control's own Value implementation,
        // which does its own type/range check and throws naming it — 040.3 ships the first of them,
        // hafen.ui():progress(), whose value is a number in 0..1.
        //   061.2: THE READ ANSWERS ON A BORROWED CONTROL TOO. It routed through the owned adapter alone,
        // so it read nil on every one of the client's own controls — and with it nothing an addon does to
        // one of them was observable: what a cancelled tick left the box at, what value a drag arrived at.
        // It gains the second half :text()'s read has always had (LuaWidget.value(Widget) below): a
        // best-effort class switch tried when there is no owned adapter, nil where a widget holds nothing,
        // never throwing. Still a read — unprotected, no layer, nothing to restore.
        //   061.8: AND THE WRITE ANSWERS ON ONE TOO — the one ACT in the editing surface, and the only
        // PROTECTED verb in it ("widget.value"). It drives the control through the very method the client's
        // own gesture ends in (Controls.drive), so the server sees exactly what it would have seen from the
        // user: which is why writing what a control HOLDS is keyed where writing what a widget SAYS
        // (widget:text(s)) is not. It is an act and not a layer — nothing is recorded, there is no
        // :value(nil), and neither :reload nor disable puts a driven control back.
        //   The gate is the FIRST statement on that path (D-213), before the value is looked at, so an
        // addon that declared nothing is told THAT rather than that its argument was the wrong type. It
        // cannot come earlier than the provenance: a control the addon BUILT is its own UI, and writing
        // what your own progress bar holds has never left the client.
        m.set("value", new VarArgFunction() {
            public Varargs invoke(Varargs a) {            // w:value() → narg 1 · w:value(v) → narg 2
                LuaValue self = a.arg1();
                LuaWidget h = handle(self, "value");
                if(!Args.passed(a, 2)) {                  // w:value() — the read, on either provenance
                    Widget rw = live(h);
                    if(rw == null)
                        return LuaValue.NIL;
                    Owned rc = ownedContent(owner, rw);
                    return (rc == null) ? value(rw) : Controls.value(rc);
                }
                // audit2 B08 (uw-12): THE GATE BEFORE THE STALE NO-OP (D-213). The write used to answer the
                // 029.2 chaining no-op on a stale receiver BEFORE it looked at the manifest, so an addon that
                // declared nothing was refused or silently ignored depending on whether the widget happened
                // still to be in the tree — a state-dependent refusal, which is the one thing D-213 exists to
                // stop. Provenance still comes first, because a control the addon BUILT is its own UI and has
                // never been protected: it is read off the handle's own reference, which survives the widget
                // leaving the tree, so the answer no longer depends on liveness either.
                Owned c = ownedContent(owner, h.wdg);
                if(c == null)                             // BORROWED (or gone): the act, and its gate
                    AddonManager.requirePermission(AddonManager.current(), Permission.WIDGET_VALUE);
                Widget w = live(h);
                if(w == null)                             // a write on a stale widget: the 029.2 chaining no-op
                    return self;
                if(c == null) {
                    Controls.drive(w, Args.written(a, 2, "widget:value", "v"));
                    WidgetSurface.touch(w);               // 044.1: standing in the world? its picture moved
                    return self;
                }
                Controls.value(c, w, Args.written(a, 2, "widget:value", "v"));
                return self;
            }
        });
        // source(h) / source() — 040.3: a PICTURE's own content, decision E's other half — hafen.ui():image()
        // is the builder, and its setter is :source(h) rather than :image(h) so the widget never reads as
        // image():image(h). Unlike a button's face this is NOT building-only: Img.setimg is a live setter, so
        // the picture may be replaced at any time. h is a hafen.asset image handle or a client resource name,
        // the same two doors widget:image(up, down) resolves; the bare read hands back exactly what was given.
        m.set("source", new VarArgFunction() {
            public Varargs invoke(Varargs a) {            // w:source() → narg 1 · w:source(h) → narg 2
                LuaValue self = a.arg1();
                Widget w = live(handle(self, "source"));
                LuaValue v = Args.written(a, 2, "widget:source", "h");
                if(v == null)
                    return Controls.source((w == null) ? null : ownedContent(owner, w));
                if(w == null)                             // a write on a stale widget: the 029.2 chaining no-op
                    return self;
                Controls.source(owner, w, owned(owner, w, "source(h)"), v);
                return self;
            }
        });
        // rows(t) / rows() — 040.5: the ROW SOURCE of a model-backed control (spec 040 §1) — an array. Reads
        // back exactly the table last given. hafen.ui():radio() is the first builder that answers it: three
        // labels become three RadioButtons stacked under it. A control with no row source reads nil and a
        // write there throws naming what does, exactly like :value()/:onChange().
        m.set("rows", new VarArgFunction() {
            public Varargs invoke(Varargs a) {            // w:rows() → narg 1 · w:rows(t) → narg 2
                LuaValue self = a.arg1();
                Widget w = live(handle(self, "rows"));
                LuaValue v = Args.written(a, 2, "widget:rows", "t");
                if(v == null)
                    return Controls.rows((w == null) ? null : ownedContent(owner, w));
                if(w == null)                             // a write on a stale widget: the 029.2 chaining no-op
                    return self;
                Controls.rows(owned(owner, w, "rows(t)"), w, v);
                return self;
            }
        });
        // range(min, max) / range() — 040.6: the value BOUNDS of a slider or scrollbar. The bare read hands
        // back {min=, max=} as they stand; a control with none reads nil, and a write there throws naming the
        // builders that take one. Changing the range RE-CLAMPS a value that no longer fits, WITHOUT firing
        // :onChange — narrowing is not a user interaction, the same direct-field-write discipline 040.3/040.4
        // pinned for a programmatic :value(v). An explicit widget:range(nil) is refused (R5) like any other
        // required argument, naming "min" — there is no "undo" meaning for a control's own bounds the way
        // :position(nil)/:size(nil) undo a layer on a possibly-borrowed widget.
        m.set("range", new VarArgFunction() {
            public Varargs invoke(Varargs a) {            // w:range() → narg 1 · w:range(min, max) → narg 3
                LuaValue self = a.arg1();
                Widget w = live(handle(self, "range"));
                if(!Args.passed(a, 2))
                    return Controls.range((w == null) ? null : ownedContent(owner, w));
                if(w == null)                             // a write on a stale widget: the 029.2 chaining no-op
                    return self;
                Controls.range(owned(owner, w, "range(min, max)"), w, a);
                return self;
            }
        });
        // rowHeight(n) / rowHeight() — 040.9: the ROW HEIGHT of a model-backed list, in DESIGN pixels — defaults to
        // the client's own label height. Building-only, like :image() (spec 040 decision G): the client's own
        // SListBox fixes its row height at construction, so choosing a different one rebuilds the widget under
        // the same Lua handle exactly as a face setter does. A control with no rows reads nil on this and a
        // write there throws naming what does.
        m.set("rowHeight", new VarArgFunction() {
            public Varargs invoke(Varargs a) {            // w:rowHeight() → narg 1 · w:rowHeight(n) → narg 2
                LuaValue self = a.arg1();
                Widget w = live(handle(self, "rowHeight"));
                LuaValue v = Args.written(a, 2, "widget:rowHeight", "n");
                if(v == null)
                    return Controls.rowHeight((w == null) ? null : ownedContent(owner, w));
                if(w == null)                             // a write on a stale widget: the 029.2 chaining no-op
                    return self;
                Controls.rowHeight(owner, w, owned(owner, w, "rowHeight(n)"), v);
                return self;
            }
        });
        // cellSize(w, h) / cellSize() — 040.11: a GRID's cell box, in DESIGN pixels. Building-only, like
        // :rowHeight(n) (spec 040 §5's shape again): the client's own GridList fixes a group's cell box
        // (Group.itemsz) at construction, so choosing a different one rebuilds the widget under the same Lua
        // handle. The bare read hands back {w=, h=}; a control with no cells reads nil on this and a write
        // there throws naming what does.
        //   It is a SIZE, and the verb says so — item:cell() one type away is a PLACE, the inventory cell an
        // item sits in, and both are two-number tables. One word over the two made grid:cell(c.w, c.h) fed
        // from item:cell() read nil, nil and be TAKEN, leaving the grid its default box.
        m.set("cellSize", new VarArgFunction() {
            public Varargs invoke(Varargs a) {            // w:cellSize() → narg 1 · w:cellSize(w, h) → narg 3
                LuaValue self = a.arg1();
                Widget w = live(handle(self, "cellSize"));
                if(!Args.passed(a, 2))
                    return Controls.cellSize((w == null) ? null : ownedContent(owner, w));
                if(w == null)                             // a write on a stale widget: the 029.2 chaining no-op
                    return self;
                Controls.cellSize(owner, w, owned(owner, w, "cellSize(w, h)"), a);
                return self;
            }
        });
        // columns(t) / columns() — 040.12: a TABLE's column descriptors ({title=, width=, of=} per column).
        // Building-only, like :cellSize(w, h)/:rowHeight(n): the client's own TableBox fixes its columns (cols,
        // main) at construction, so choosing a different set rebuilds the widget under the same Lua handle,
        // re-resolving the current rows against the new columns. The bare read hands back exactly the table
        // last given; a control with no columns reads nil on this and a write there throws naming what does.
        m.set("columns", new VarArgFunction() {
            public Varargs invoke(Varargs a) {            // w:columns() → narg 1 · w:columns(t) → narg 2
                LuaValue self = a.arg1();
                Widget w = live(handle(self, "columns"));
                LuaValue v = Args.written(a, 2, "widget:columns", "t");
                if(v == null)
                    return Controls.columns((w == null) ? null : ownedContent(owner, w));
                if(w == null)                             // a write on a stale widget: the 029.2 chaining no-op
                    return self;
                Controls.columns(owner, w, owned(owner, w, "columns(t)"), v);
                return self;
            }
        });
        // exists() — is this widget still attached to the tree? The one read that always answers (D-060: a widget
        // HAS a lifetime, unlike a name-keyed Sound). False after a destroy and false across a relog.
        m.set("exists", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(live(handle(self, "exists")) != null);
            }
        });
        // info() — the one SNAPSHOT escape hatch ({type,role,res,id,pos,size,visible,text,owned}), for logging.
        // An absent value is simply an unset key; nil for a stale widget (there is nothing to snapshot). `owned`
        // is the provenance 029.2 introduced — true iff THIS addon created the widget, i.e. iff the write verbs
        // answer on it — and it is how you ask instead of provoking the error.
        m.set("info", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return snapshot(owner, live(handle(self, "info")));
            }
        });
        // walk(fn) — depth-first: fn(widget, depth) on this widget, then its children; return false to PRUNE the
        // subtree. Returns self, so it chains. Each callback is error-isolated + watchdog-armed via callLua.
        m.set("walk", new VarArgFunction() {
            public Varargs invoke(Varargs a) {            // w:walk(fn) → self=arg1, fn=arg2
                LuaValue self = a.arg1();
                LuaValue fn = a.arg(2);
                handle(self, "walk");
                if(fn.isfunction())
                    walk(owner, self, fn, 0);
                return self;
            }
        });
        // find(selector) / all(selector) — 049.2: THE SAME SEARCH the section does, from THIS widget instead of the
        // root. The scope decides which widgets are CANDIDATES (this one and everything under it); the selector is
        // still matched against the whole tree, so an ancestor step may name a widget ABOVE the scope — exactly
        // what element.querySelector does in CSS. Inside an :on(sel, "Added", fn) callback this is the only
        // correct lookup: the root-anchored form asks "the Cupboard's grid" of a client that may have two open,
        // and the one you were handed is not necessarily the one it meets first.
        //   :match is STRICT, like s:ui():match — nil for no match, the widget for exactly one, and a REFUSAL
        // naming widget:matchAll(sel)[i] for two or more. :matchAll is the array form: empty, never nil.
        //   A STALE widget REFUSES at both doors, and it is the one read in this section that does not answer
        // nil/empty (029.2). A search inside a subtree that no longer exists has no honest empty answer: "no
        // button in this window" and "this window is gone" are different facts, and telling them apart is the
        // whole reason to hold a widget across the lifetime of the thing you are searching.
        m.set("match", new VarArgFunction() {
            public Varargs invoke(Varargs a) {            // w:match(sel) → self=arg1, sel=arg2
                Widget w = live(handle(a.arg1(), "match"));
                Selector sel = UiApi.selArg(Args.required(a, 2, "widget:match", "selector"),
                                            "widget:match(selector)");
                return UiApi.scopedMatch(owner, searched(w, "match"), sel);
            }
        });
        m.set("matchAll", new VarArgFunction() {
            public Varargs invoke(Varargs a) {            // w:matchAll(sel) → self=arg1, sel=arg2
                Widget w = live(handle(a.arg1(), "matchAll"));
                Selector sel = UiApi.selArg(Args.required(a, 2, "widget:matchAll", "selector"),
                                            "widget:matchAll(selector)");
                return UiApi.scopedMatchAll(owner, searched(w, "matchAll"), sel);
            }
        });
        // hit(coord) — W2: the DEEPEST Widget object under a {x=,y=} ROOT-coord point within this subtree, or
        // nil. A hit test SEARCHES the screen, which is not what :at(x) means anywhere else in the API —
        // s:world():grid():at(p) ADDRESSES a member by a place — so the two questions get two words, and this
        // one pairs with the pointer's own m:over().
        m.set("hit", new VarArgFunction() {
            public Varargs invoke(Varargs a) {            // w:hit(coord) → self=arg1, coord=arg2 (root coords)
                Widget w = live(handle(a.arg1(), "hit"));
                if(w == null)
                    return LuaValue.NIL;
                Coord pt = Px.in(coordArg(a.arg(2), "widget:hit(coord)"));   // a point is design px, like a size
                Widget hit;
                synchronized(monitor(w)) { hit = hitTest(w, w.rootxlate(pt)); }
                return (hit == null) ? LuaValue.NIL : of(owner, hit);
            }
        });
        // rootPos() — W2: {x=,y=} this widget's top-left in root coords (with :size() = a highlight box). DESIGN
        // PIXELS, like :position() and for the same reason: a widget's place is on the screen (spec 039 §2.7),
        // and one space is what makes the pair a box you can draw and hit-test with (058.1).
        m.set("rootPos", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Widget w = live(handle(self, "rootPos"));
                if(w == null)
                    return LuaValue.NIL;
                Coord rp;
                synchronized(monitor(w)) { rp = w.rootpos(); }
                return (rp == null) ? LuaValue.NIL : xyTable(Px.out(rp));
            }
        });
        // chrome() — 065.4: WHERE a window's decoration drew its ornaments, in design px from the window's own
        // outer top-left: { caption = {x=,y=}, plate = {x=,y=,w=,h=}, sizer = {x=,y=}, close = {x=,y=,w=,h=} },
        // each present only once that ornament has been drawn. nil for anything that is not a window, and for a
        // window that built a decoration of its own. It answers where the ornament WENT, which a rule cannot: a
        // window with no rule reads the client's own numbers, and the plate's box is the client's either way.
        m.set("chrome", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return chromeTable(live(handle(self, "chrome")));
            }
        });
        // overlay() — 103.3: WHAT YOU DRAW OVER THIS WIDGET, the third receiver of a word that means one
        // thing in this API — keyed decorations bound to a thing. hafen.ui():overlay() binds them to the
        // screen and gob:overlay() to a game object; this binds them to one widget, so a mark on a button,
        // a number on an item icon or a bar under a slot is attached where it belongs instead of being
        // re-derived every frame by a screen-wide painter searching the tree for a rectangle.
        //   The standard collection, keyed per addon, whose members paint AFTER the widget, translated and
        // clipped to its own box, and die with it. It is a VIEW minted per call over the widget's own
        // record list, so the collection is not == itself twice while :get(key) is: the identity that
        // matters is the member's. Unprotected: what you paint is your own drawing over a widget the client
        // already drew, and it changes nothing anybody else owns.
        m.set("overlay", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaWidget h = handle(a.arg1(), "overlay");
                if(Args.passed(a, 2))
                    throw new LuaError("widget:overlay() takes no arguments — it IS the collection of what"
                        + " you draw over this widget, and widget:overlay():add(key) attaches one whose"
                        + " :draw(fn) says what it paints");
                return LuaWidgetOverlay.collection(owner, h);
            }
        });
        // style() — 034.1/034.3: the style THIS widget RESOLVES to — { font = <handle>, color = {r=,g=,b=,a=} },
        // each field present only where a level of the cascade set it — or nil when nothing overrides it. "nil
        // means stock": on a client with no sheet installed every widget reads nil, which is the same contract the
        // identity fast path in the font provider rests on. What it folds is the PER-WIDGET cascade — this widget's
        // own :rule() over the sheet's TREE keys (sheet:rule("window[title=…]")), most specific first. A
        // site key like ["button"] is not a property of any one widget (a window contains buttons, labels and chat,
        // each drawn at its own site) and is deliberately not folded in here; nor is an ancestor's style, which is
        // inherited at the DRAW rather than resolved (a read answers for the thing you point at, D-075).
        // The font comes back as the very handle your own sheet named, so w:style().font == body holds.
        m.set("style", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Widget w = live(handle(self, "style"));
                return (w == null) ? LuaValue.NIL : Sheet.styleTable(owner, w);
            }
        });
        // rule() (034.3, F5 widened; 039.7's shape) — YOUR OWN level of the cascade on THIS widget and everything
        // drawn inside it, while its siblings keep the tree/site/"default" cascade. It hands back the same Rule
        // object a sheet's selectors do, so the properties are said the same way — r:font(h), r:color(c),
        // r:bg{…}, r:border{…}, r:padding(n), each with a bare read — and r:info() is the whole level as a table.
        // The undo is r:remove() (R7), and it drops only this addon's level, never another's. The layout three
        // are refused here naming widget:position(x, y): the hand-named level of THAT cascade is the verb.
        // widget:setFont/:resetFont are a HARD CUT: a font was never a special case, only the first property that
        // existed. Owner-tagged (reverted on :reload/disable) and held weakly against the widget, so it dies with
        // the window; on a stale widget the reads answer nil and a write is the 029.2 silent no-op.
        m.set("rule", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaWidget h = handle(self, "rule");
                return LuaRule.ofWidget(owner, h);
            }
        });
        /* name(s) / name() (107) — what THIS addon calls a widget it built, so a theme can name it back:
         * ["[name=actionbars/bar]"]. The engine writes the addon's own id in front, which is what makes two
         * addons unable to collide and what makes a theme's selector read as the thing it points at.
         *   WRITE-ONCE. A name is identity, not state: the four states a surface enters ride inside a VALUE
         * (bg = {..., hover = ...}), and renaming to express a fifth would be a per-state selector by the back
         * door, which the stylesheet's own page rules out. A second name would also leave every rule pointing
         * at the first naming nothing, silently, which is the worst way for a theme to break. */
        m.set("name", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaWidget h = handle(a.arg1(), "name");
                Widget w = live(h);
                if(!Args.passed(a, 2))
                    return (w == null) ? LuaValue.NIL : str(nameOf(w));
                if(w == null)
                    return a.arg1();               // 029.2: a write on a stale widget is a silent no-op
                mine(owner, w, "name");
                LuaValue nv = a.arg(2);
                if(!nv.isstring() || nv.isnumber())
                    throw new LuaError("widget:name(name): expected a string — the word your addon calls this"
                        + " widget by, which a theme then names back as [name=<your addon>/<the word>]");
                String n = nv.tojstring().trim();
                if(n.isEmpty() || (n.indexOf('/') >= 0) || (n.indexOf(']') >= 0) || (n.indexOf(' ') >= 0))
                    throw new LuaError("widget:name(\"" + n + "\"): a name is one word of your own, with no"
                        + " space, no \"]\" (a selector step ends on one) and no \"/\" — the engine writes"
                        + " your addon's id there, so that two addons naming a bar cannot collide");
                if(!nameSet(w, owner.manifest.id + "/" + n))
                    throw new LuaError("widget:name(\"" + n + "\"): this widget is already called \""
                        + nameOf(w) + "\". A name is written once: it is the identity a theme's [name=...]"
                        + " points at, and a second one would leave every rule naming the first pointing at"
                        + " nothing at all");
                return a.arg1();
            }
        });
        /* stock(t) / stock() (107) — what a widget you BUILT looks like when no rule says otherwise: the
         * addon-side twin of the client's own stock look, and the BOTTOM of the cascade. Every rule beats it,
         * a theme's included, which is exactly why an addon's default belongs here rather than in a rule of
         * its own: widget:rule() would sit at the top where no theme could reach past it, and a tree rule
         * would leave who wins to which sheet installed last. Written with the same properties a rule is,
         * minus the three that lay a widget out. A table with nothing in it drops the declaration. */
        m.set("stock", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaWidget h = handle(a.arg1(), "stock");
                Widget w = live(h);
                if(!Args.passed(a, 2))
                    return (w == null) ? LuaValue.NIL : Sheet.stockTable(owner, w);
                if(w == null)
                    return a.arg1();               // 029.2
                mine(owner, w, "stock");
                Sheet.setWidgetStock(owner, w, Sheet.stockProps(owner, "widget:stock", a.arg(2)));
                return a.arg1();
            }
        });
        return m;
    }

    // ---- widget:on(key, fn)'s vocabulary (041.3/041.4) --------------------------------------------------

    /**
     * The keys every LIVE widget answers — the universal four inputs (041.3), Removed (041.4), and the two
     * gesture keys {@code widget:draggable(h)}/{@code widget:resizable(h)} arm (062). They are here rather
     * than beside a control's own capability keys because being dragged or resized is a fact about a widget's
     * <i>place</i> and its <i>box</i>, and every widget has both.
     */
    private static final String[] UNIVERSAL_KEYS =
        { "MouseDown", "MouseUp", "MouseMove", "Wheel", "Removed", "Dragged", "Resized" };
    /** The four keys ONLY an addon's own surface answers ({@code hafen.ui():widget()}/{@code :window()}). */
    private static final String[] SURFACE_KEYS = { "Draw", "Update", "Drop", "Close" };

    /**
     * The keys {@code w} answers, in the order a refusal lists them — computed fresh each call, since it
     * depends on WHAT {@code w} is (a control's own capability, a surface, a container) rather than on a fixed
     * catalogue. A control notification comes first (there is at most one relevant interface a control
     * implements per verb — {@link Controls.Press}/{@link Controls.Change}/{@link Controls.Submit}/
     * {@link Controls.Select}/{@link Controls.OnCell}), then {@link #UNIVERSAL_KEYS}, then
     * {@code ItemAdded}/{@code ItemRemoved} (any widget that is not one of the sixteen control adapters — a
     * {@link haven.Button} structurally never carries item children, a native window or an addon's own surface
     * might), then {@code Draw}/{@code Tick}/{@code Drop}/{@code Close} on an owned surface alone.
     */
    private static List<String> widgetKeys(Addon owner, Widget w) {
        List<String> keys = new ArrayList<String>();
        Owned c = (w == null) ? null : ownedContent(owner, w);
        boolean control = (c != null) && !(c instanceof AddonWidget);
        if(control) {
            if(c instanceof Controls.Press)
                keys.add("Pressed");
            if(c instanceof Controls.Change)
                keys.add("Changed");
            if(c instanceof Controls.Submit)
                keys.add("Submitted");
            if(c instanceof Controls.Select)
                keys.add("Selected");
            if(c instanceof Controls.OnCell)
                keys.add("Cell");
        } else if(w != null) {
            // 061.1: the same question of a control the addon did NOT build — a native button answers Pressed,
            // and the refusal has to say so or the roster and the interception seam disagree about one widget.
            for(String k : Controls.borrowedKeys(w))
                keys.add(k);
        }
        for(String k : UNIVERSAL_KEYS)
            keys.add(k);
        if(!control) {
            keys.add("ItemAdded");
            keys.add("ItemRemoved");
        }
        if(c instanceof AddonWidget) {
            for(String k : SURFACE_KEYS)
                keys.add(k);
        }
        return keys;
    }

    /** {@code "a, b, c"} — the refusal's key listing, with no trailing separator. */
    private static String join(List<String> keys) {
        StringBuilder sb = new StringBuilder();
        for(String k : keys) {
            if(sb.length() > 0)
                sb.append(", ");
            sb.append(k);
        }
        return sb.toString();
    }

    /**
     * <b>A box the control's art will not fit in</b> (058.4) — the refusal behind {@code widget:size(w, h)}'s
     * minimum, and the one place its wording lives. It names three things, because an author who hit it knows
     * none of them: the number the art needs, that the number is the <i>art's</i> and not a policy, and the
     * arity that means <i>you do not have to know it</i>.
     *
     * <p>It refuses rather than clamping up to the minimum (spec 058's discarded alternative): a slider's range
     * is something the addon set and may narrow, so pinning a value into it is honest, but an art height is a
     * fact the addon cannot see — silence there leaves a number in the source that means nothing.
     */
    private static LuaError tooSmall(Widget w, String verb, int got, int min, String axis) {
        return new LuaError(verb + " — a " + typeName(w) + " is " + min + " design px " + axis + ", which is its"
            + " own ART's box: " + got + " clips it. A control's height is the one measurement an addon cannot"
            + " make, so widget:size(w) takes the width alone and keeps the height the art gives it.");
    }

    /** The handle behind a method's {@code self}, or a guiding error (a dot-call passes the wrong self). */
    private static LuaWidget handle(LuaValue self, String method) {
        LuaWidget h = resolve(self);
        if(h == null)
            throw new LuaError("widget:" + method + "() — use a COLON call on a Widget object"
                + " (hafen.session():current():ui():root(), :match(selector) or :node(id) on the same s:ui()"
                + " for the client's own widgets, hafen.ui():hit(x, y) for a point on the screen, and the"
                + " handle hafen.ui():window() gave you for one of yours)");
        return h;
    }

    // ---- provenance: OWNED vs BORROWED (029.2) -----------------------------------------------------

    /**
     * The addon's own {@link AddonWidget} behind an <b>OWNED</b> widget — {@code null} when {@code w} is
     * <b>BORROWED</b> (a native widget, or another addon's). This is what decides whether the geometry writes,
     * {@code :pack()}, {@code :destroy()} and the builder setters answer.
     *
     * <p><b>Derived, never stored.</b> The intern cache is weak on both axes, so an entity can be collected and
     * re-minted at any time; a provenance flag on the handle would silently be lost. Instead the tree itself is
     * the record: {@code hafen.ui():window()}/{@code :widget()} and the control builders intern the entity on the
     * widget's <b>root</b> (the chrome, or the content itself), and an {@link Owned} already knows both its
     * {@link Owned#profOwner owner} and its {@link Owned#rootw root} — so {@code w} is owned exactly when
     * {@code w} is, or directly contains, this addon's content whose root is {@code w}. Per-addon by
     * construction: addon B looking at addon A's window gets a BORROWED entity, which is the correct answer.
     *
     * <p><b>The test is a CONTRACT, not a class</b> (040.1). It used to ask {@code instanceof AddonWidget}, which
     * is the addon's own painted surface — so a {@code haven.Button} an addon built would have read as borrowed
     * and the owned half would have refused on it. {@link Owned} is the same three questions against a wider
     * type; the mechanism keeps its shape, and every control adapter answers them.
     */
    static Owned ownedContent(Addon owner, Widget w) {
        if(w == null)
            return null;
        if(w instanceof Owned)
            return isOwn(owner, (Owned)w, w) ? (Owned)w : null;
        for(Widget c = w.child; c != null; c = c.next) {   // the chrome case: our content is a direct child
            if((c instanceof Owned) && isOwn(owner, (Owned)c, w))
                return (Owned)c;
        }
        return null;
    }

    /**
     * <b>Is {@code w} the kin window, or inside it?</b> (audit2 B08, pk-02) — the boundary
     * {@code widget:parent()} stops at. {@link haven.BuddyWnd} holds the character's hearth secret in a plain
     * {@code TextEntry} child, so a handle minted <i>inside</i> that window may not climb out of it and back
     * down a sibling branch. Reaching the window from outside is untouched — it is an ordinary window, and
     * what it holds is unreadable on its own account ({@link #secret}).
     */
    private static boolean insideCredentialWnd(Widget w) {
        for(Widget p = w; p != null; p = p.parent) {
            if(p instanceof haven.BuddyWnd)
                return true;
        }
        return false;
    }

    /** A LuaValue for a possibly-null string. */
    private static LuaValue str(String s) {
        return (s == null) ? LuaValue.NIL : LuaValue.valueOf(s);
    }

    /**
     * Refuse a write on a widget this addon did not build (107). {@code name} and {@code stock} are the two
     * things an addon says about its OWN surface: naming the client's chat window, or declaring what it is
     * made of, would be one addon writing shared state another addon's theme then reads as a fact.
     */
    private static void mine(Addon owner, Widget w, String verb) {
        if(ownedContent(owner, w) == null)
            throw new LuaError("widget:" + verb + "(): this widget is not one your addon built, and " + verb
                + " is what an addon says about its OWN surface. Say it on a widget from hafen.ui():widget(),"
                + " :window() or one of the controls; to restyle a widget the CLIENT put up, use"
                + " widget:rule(), which is your own level and is reverted with your addon");
    }

    /** Is {@code c} this addon's live content, rooted at {@code root}? (A killed widget owns nothing any more.) */
    private static boolean isOwn(Addon owner, Owned c, Widget root) {
        return (c.profOwner() == owner) && (c.rootw() == root) && !c.dead();
    }

    /**
     * The OWNED content behind {@code w}, or a clear error naming what the addon may do instead. One message
     * since 036.1: the geometry writes stopped needing it — {@code :position}/{@code :size} answer on a native widget
     * now (feature E) and route through {@link Addon#movedNative} instead — leaving {@code :pack()} and
     * {@code :destroy()}, which are simply not the addon's to do on a widget the client owns.
     */
    private static Owned owned(Addon owner, Widget w, String verb) {
        Owned c = ownedContent(owner, w);
        if(c == null)
            throw new LuaError("widget:" + verb + " — " + typeName(w) + " is a NATIVE widget (your addon did not"
                + " create it); the builder verbs answer only on a widget you created with hafen.ui():window(),"
                + " hafen.ui():widget() or one of the control builders. To lay a native widget out, use"
                + " widget:position(x, y) / widget:size(w, h) — which restore themselves when your addon goes away.");
        return c;
    }

    /**
     * The addon's own <b>surface</b> behind {@code w} — an {@link AddonWidget}, the thing an addon <i>paints</i>
     * — or a clear error. The narrower half of {@link #owned}: a caption, a default font and eight Lua draw/input
     * callbacks are things a surface HAS, and a <b>control</b> has nowhere to put them, because the client draws
     * and drives it (040.1). The matching reads answer {@code nil} on a control, exactly as they do on a native
     * widget, rather than throwing.
     */
    private static AddonWidget surface(Addon owner, Widget w, String verb) {
        Owned c = owned(owner, w, verb);
        if(!(c instanceof AddonWidget))
            throw new LuaError("widget:" + verb + " belongs to a SURFACE you painted yourself (hafen.ui():window()"
                + " or hafen.ui():widget()); " + typeName(w) + " is a control, which the client draws and drives."
                + " A button's activation is widget:on(\"Pressed\", fn), and a control's look comes from the"
                + " stylesheet:"
                + " hafen.ui():sheet():rule(selector).");
        return (AddonWidget)c;
    }

    /** The surface behind {@code w} for a READ, or {@code null} — a native widget and a control both answer nil. */
    private static AddonWidget surfaceOrNull(Addon owner, Widget w) {
        Owned c = (w == null) ? null : ownedContent(owner, w);
        return (c instanceof AddonWidget) ? (AddonWidget)c : null;
    }

    // ---- the hidden-native restore list (029.2, what replaced hafen.ui.adopt) -----------------------

    /**
     * One native widget an addon hid with {@code w:hide()} — and, since 031, the record that it <b>owns</b> that
     * window: its toggle is looked up here ({@link UiApi#toggleWnd}/{@link UiApi#wndState}) and teardown gives
     * both back. It carries the owner, the widget, and its server id ({@code -1} for a client-only widget, which
     * is what the {@code Hidewnd} wrappers around {@code maininv}/the equipory are).
     *
     * <p><b>The {@link #view} is the whole state of the toggle</b> (031.2). {@code widget:replace(view)} is the one
     * place that knows both halves — the window it hides and the view put in its place — so it fills this in
     * itself; a bare {@code w:hide()} leaves it {@code null} and the toggle is simply swallowed. There is no
     * bookkeeping boolean beside it: "is it open?" is {@code view.visible()}, so the menu checkbox cannot drift
     * out of sync with what is on screen, and teardown's one rule (<i>the window ends up as the user was seeing
     * it</i>) reads the same field. That is also why the visibility the window had before the addon touched it is
     * <b>not</b> recorded any more — the user was not seeing that window, they were seeing the view.
     */
    static final class Hidden {
        final Addon owner;
        final Widget wdg;
        final int id;
        AddonWidget view;       // 031.2: the addon's stand-in, or null (a bare w:hide() ⇒ the toggle is swallowed)

        Hidden(Addon owner, Widget wdg, int id) {
            this.owner = owner;
            this.wdg = wdg;
            this.id = id;
        }

        /**
         * The view's top-level widget (its window chrome) while the view is alive, or {@code null} — never bound,
         * or already destroyed (teardown, the chrome's own X). A dead view stands in for nothing, so the toggle
         * falls back to swallowing and the tick to {@code false}. Allocates nothing: this is on the frame path.
         */
        Widget liveView() {
            AddonWidget v = view;
            return ((v == null) || v.dead()) ? null : v.rootw();
        }
    }

    /**
     * A native widget this addon has taken into a surface of its own with {@code widget:parent(p)} — <b>where it
     * came from</b>, which is the whole of what {@code widget:parent(nil)} and the teardown need to put it back.
     *
     * <p>Three fields say the home, and the third is the one that is easy to leave out. {@link #from} is the
     * parent, {@link #at} the place in it, and {@link #after} <b>the sibling it followed</b> — because a parent's
     * child list is a paint order, and {@link Widget#add} appends. The corner minimap is the case that names it:
     * {@code GameUI} {@code lower()}s it so the carved plate above it paints over the map, so a map put back by
     * adding alone would come back on top of its own frame.
     *
     * <p>Not in the record: the widget's place and size <i>as the user had them</i>. Those are
     * {@link Moved}'s, which restores later in the same teardown and therefore has the last word; {@link #at} is
     * only what the widget's own {@code c} was at the moment we took it, which is what an untouched widget goes
     * back to. And not the visibility either: taking a widget in hides nothing, so what the user was seeing is
     * what they go on seeing.
     */
    static final class Rehomed {
        final Addon owner;
        final Widget wdg;
        final Widget from;
        final Widget after;      // null ⇒ it was the FIRST child of `from`
        final Coord at;
        final UI ui;             // the tree it belongs to: after a relog there is nothing of it to put back
        /**
         * <b>The id {@code GameUI} tracked this window by</b>, or {@code null} for anything that is not one
         * of its tracked windows (audit2 B08, un-03). Captured at the take, because taking it is what makes
         * the client forget: {@code WidgetSurface.reparent} calls the old parent's {@code cdestroy}, and
         * {@code GameUI.cdestroy} drops the id and writes the window's place to disk. {@link UiApi#home}
         * hands it back with the window.
         */
        final String wndid;

        Rehomed(Addon owner, Widget wdg, Widget from, Widget after, Coord at, UI ui, String wndid) {
            this.owner = owner;
            this.wdg = wdg;
            this.from = from;
            this.after = after;
            this.at = at;
            this.ui = ui;
            this.wndid = wndid;
        }
    }

    /**
     * Put {@code w} back among its siblings where the record says it stood: first child, or right behind the one
     * it followed. Called with the widget already re-added to that parent, so the fallbacks are both "leave it
     * where {@link Widget#add} put it" — the sibling is gone, or it sat in another {@code z} band, where the
     * client's own ordering rule already decides and ours would break it.
     */
    static void relink(Widget w, Widget after) {
        Widget p = (w == null) ? null : w.parent;
        if(p == null)
            return;
        if(after == null) {                        // the client's own verb for exactly this: unlink + linkfirst
            w.lower();
            return;
        }
        if((after.parent != p) || (after.z != w.z))
            return;
        w.unlink();
        if((w.next = after.next) != null)
            w.next.prev = w;
        else
            p.lchild = w;
        (w.prev = after).next = w;
    }

    /**
     * {@code widget:parent(p)} / {@code widget:parent(nil)} on one of the CLIENT's widgets — the native half of
     * the verb (the addon's own half is the builder setter above). Takes it into a surface this addon built, or
     * puts it back where the client had it.
     *
     * <p><b>Why the whole subtree comes with it and nothing is redrawn</b>: this is a tree move, so the widget
     * goes on being the client's — it ticks, it draws itself, it answers its own clicks, and a server-bound one
     * is still bound. That is what makes it the only reach there is to a surface an addon <i>cannot</i>
     * reproduce: the minimap's rendered ground, the portrait's 3D avatar, a meter's server-coloured fill.
     */
    private static Varargs rehomeNative(Addon owner, LuaValue self, Widget w, LuaValue v) {
        if(v.isnil()) {                            // put it back — inert when we are not holding it
            Rehomed r = UiApi.rehomedIn(owner, w);
            if(r != null)
                UiApi.home(r, true);
            return self;
        }
        LuaWidget h = resolve(v);
        if(h == null)
            throw new LuaError("widget:parent(p) on one of the client's own widgets expects a surface YOUR addon"
                + " built to take it into — hafen.ui():widget() or hafen.ui():window(). Got " + v.typename());
        Widget p = live(h);
        // 125.1: the DESTINATION left the tree. The other end of the same split as the builder direction
        // above, and the receiver here is one of the client's own widgets, which stays exactly where it is:
        // nothing moves, and the write chains. That is also what leaves the type name above useful: the
        // branch that prints it is reached only by a genuine non-Widget, never by "Got userdata".
        if(p == null)
            return self;
        Owned pc = ownedContent(owner, p);
        if(pc == null)
            throw new LuaError("widget:parent(p) — " + typeName(w) + " is the client's own and so is "
                + typeName(p) + ": moving one of the client's widgets into another of them is not this verb."
                + " Lay it out where it stands with widget:position(x, y) / widget:size(w, h), or build a"
                + " surface of your own and name that");
        Widget dest = pc.widget();
        if(dest instanceof CScrollport)            // the 040.8 trap: a scrollport's children go in its container
            dest = ((CScrollport)dest).cont;
        if(w.parent == null)
            throw new LuaError("widget:parent(p) — " + typeName(w) + " is the root of its own tree: it hangs"
                + " under nothing, so there is nothing to take it out of and nothing to put it back into");
        if((dest == w) || dest.hasparent(w))
            throw new LuaError("widget:parent(p) — " + typeName(p) + " is inside " + typeName(w) + ", and a"
                + " widget cannot come to hang under itself. Build the surface outside the widget you are taking");
        if(w.parent instanceof WidgetSurface)
            throw new LuaError("widget:parent(p) — " + typeName(w) + " is standing in the 3D world, held by the"
                + " addon \"" + AddonManager.ownerName(((WidgetSurface)w.parent).owner) + "\"; a widget hangs in"
                + " one place. Take it back with hafen.virtual():widget():remove(x) first");
        if(w.ui != dest.ui)
            throw new LuaError("widget:parent(p) — " + typeName(w) + " belongs to a character's tree and reads"
                + " it: its session, its HUD, its map. " + typeName(p) + " stands in the addon layer, where there"
                + " is no character behind it, so the widget would go dark there. Build the surface into that"
                + " character's own tree instead: hafen.ui():widget():parent(s:ui():match(\"@GameUI\"))");
        Rehomed ex = UiApi.rehomedOwner(w);
        if((ex != null) && (ex.owner != owner))
            throw new LuaError("widget:parent(p) — " + typeName(w) + " is already held by the addon \""
                + AddonManager.ownerName(ex.owner) + "\"; one widget hangs in one place. Disable that addon"
                + " first, or point at a widget it does not hold");
        if(w.parent == dest)                       // already there: the write is a chaining no-op
            return self;
        if(ex == null) {                           // the FIRST touch is what records the home; a later move
            // audit2 B08 (un-03): ...and the window id goes into the record with it, BEFORE the reparent
            // below makes GameUI.cdestroy forget it. Read here and nowhere else: after the move there is
            // nothing left to read it from.
            GameUI gui = AddonManager.gui(w.ui);
            UiApi.rehomedAdd(new Rehomed(owner, w, w.parent, w.prev, new Coord(w.c), w.ui,   // keeps it
                                         (gui == null) ? null : gui.wndid(w)));
        }
        // ONE tree, so one monitor, and reparent takes it: the same-session check above is what makes that
        // true. Widget.remove() is the wrong call here and reparent says why — it would announce a death that
        // is not happening to widget:on("Removed"), to every selector watch, and to a live substitution.
        WidgetSurface.reparent(w.ui, w, dest, Coord.z);
        Layout.apply(w);                           // our own pos/size level, and any rule, re-resolve in the new parent
        return self;
    }

    /**
     * Does <b>any</b> live owner hold a hidden-native record right now? The global-empty fast path for the 031
     * window-toggle seam ({@link UiApi#toggleWnd}/{@link UiApi#wndState}), which the client polls <i>per frame,
     * per menu checkbox</i> — six of them. A plain volatile read is the whole cost for a client that hides
     * nothing, which is every client until an addon calls {@code w:hide()} on a native widget.
     *
     * <p>Maintained by {@link #recountHidden()} at the four places a restore list changes (hide, show,
     * {@link UiApi#teardownHidden}, {@link UiApi#pruneDeadTrees}). A stale <i>true</i> costs only the walk, which
     * then finds no record and falls through to stock behaviour; a stale <i>false</i> would be a silent
     * mis-answer, so the flag is only ever cleared by a recount that actually looked.
     */
    static volatile boolean anyHidden = false;

    /** Recompute {@link #anyHidden} over every live owner — the loaded addons plus the {@code :lua} REPL. */
    static void recountHidden() {
        boolean any = false;
        List<Addon> as = AddonManager.addons;
        for(int i = 0, n = as.size(); !any && (i < n); i++)
            any = !as.get(i).hiddenNative.isEmpty();
        Addon c = AddonManager.consoleOwner;
        if(!any && (c != null))
            any = !c.hiddenNative.isEmpty();
        anyHidden = any;
    }

    /**
     * Record a BORROWED widget as hidden-by-us and hand back the record — the entry {@code replace} then binds its
     * view to (031.2). Idempotent for the SAME owner (the first hide wins, and a later {@code replace} of the same
     * window joins that one record rather than making a second). Returns {@code null} when another live addon
     * already owns the widget: <b>one window, one owner</b>, because the toggle goes with it and two addons cannot
     * both drive it. The caller decides how to refuse — {@code w:hide()} throws, {@code replace} skips and logs.
     *
     * <p>The addon's own list is checked first, so this is correct even while the addon is still loading and has
     * not yet joined {@link AddonManager#addons} (which is all {@link UiApi#hiddenOwner} can see).
     */
    static Hidden recordHidden(Addon owner, Widget w) {
        Hidden mine = findHidden(owner, w);
        if(mine != null)
            return mine;
        if(UiApi.hiddenOwner(w) != null)
            return null;                      // 031.2: somebody else's window
        UI u = w.ui;                          // the id counts inside the tree the window stands in
        Hidden h = new Hidden(owner, w, (u == null) ? -1 : u.widgetid(w));
        owner.hiddenNative.add(h);
        anyHidden = true;                     // 031.1: this addon now owns that window's toggle
        return h;
    }

    /**
     * Refuse a second owner for a native widget somebody else already hid, naming the first (031.2). A hidden
     * window carries its toggle, and a toggle can only drive one view — so the second addon is stopped here rather
     * than left to fight over a window whose menu tick would then lie about both. Silent when the widget is free
     * or already this addon's own.
     */
    static void refuseSecondOwner(Addon owner, Widget w, String verb) {
        Hidden ex = UiApi.hiddenOwner(w);
        if((ex == null) || (ex.owner == owner))
            return;
        throw new LuaError(verb + " — " + typeName(w) + " is already hidden by the addon \""
            + AddonManager.ownerName(ex.owner) + "\", which owns its toggle too; one window has one owner."
            + " Disable that addon first, or point at a widget it does not hold.");
    }

    /**
     * The nearest enclosing {@link Window} of a widget, or {@code w} itself when nothing encloses it — <b>the
     * enclosing-window hop</b>, which 032.1 moved here from the old {@code hafen.ui.replace} because it is what
     * {@code widget:replace(view)} is for: the window to hide is the stock <i>frame</i> around the widget you
     * matched (the {@code Hidewnd "Inventory"} around {@code GameUI.maininv}), not the widget itself, or the
     * replacement leaves an empty frame on screen. It is also the object {@code GameUI} toggles, which is how the
     * substitution inherits the client's own key and menu button (031, D-069).
     *
     * <p>The "nothing encloses it" answer is deliberately not an error here: the two callers mean different things
     * by it — {@code widget:replace} refuses (there is no window to stand in for), while the placement path of the
     * legacy {@code hafen.ui.replace} can only log, since it must not throw into the engine.
     */
    static Widget nativeWindowOf(Widget w) {
        for(Widget p = w; p != null; p = p.parent) {
            if(p instanceof Window)
                return p;
        }
        return w;
    }

    /** This owner's own record for a widget, or {@code null} (identity-keyed; the list is per-addon tiny). */
    static Hidden findHidden(Addon owner, Widget w) {
        for(Hidden h : owner.hiddenNative) {
            if(h.wdg == w)
                return h;
        }
        return null;
    }

    /** Drop a widget from the restore list — the addon showed it again itself, so teardown has nothing to undo. */
    private static void dropHidden(Addon owner, Widget w) {
        for(Hidden h : owner.hiddenNative) {
            if(h.wdg == w) {
                owner.hiddenNative.remove(h);
                recountHidden();              // 031.1: ...and gives the toggle back, if that was the last record
                return;
            }
        }
    }

    // ---- the moved-native restore list (036.1, feature E) ------------------------------------------

    /**
     * One native widget an addon has <b>moved or resized</b> — {@link Hidden}'s shape one property along:
     * <i>what it was before we touched it</i>. Minted at the first touch, it carries the two halves
     * independently, because they are touched by different verbs and dropped by different calls: {@link #pos}
     * is the widget's {@code c} before the first {@code widget:position(x,y)}, {@link #size} is the argument that
     * reproduces its size through {@link Widget#resize(Coord)} before the first {@code widget:size(w,h)}. A
     * {@code null} half means <i>this addon never touched that</i> and there is nothing there to give back.
     *
     * <p><b>Why the size half is not simply {@code sz}.</b> {@code Window.resize} takes the CONTENT size and
     * derives the outer {@code sz} from the deco around it, so the value that restores a window is its
     * {@code csz()}, not its {@code sz} — see {@link #sizeArg}. Recording the argument rather than the result
     * makes the restore the exact inverse of the write for a window and a bare widget alike.
     *
     * <p><b>Two units live here, and which is which is the point.</b> {@link #pos}/{@link #size} are the
     * <i>client's</i> own numbers and stay <b>device</b>: rounding them into design and back would make the restore
     * inexact and hand the user a window a pixel off. {@link #wantPos}/{@link #wantSize} are what an <i>addon</i>
     * asked for and are <b>design</b> (058.3), the same space the rule below them in the cascade carries — so
     * {@link Layout} folds the two levels without converting between them and writes once, at the edge.
     *
     * <p>{@link #id} is the server widget id ({@code -1} for a client-only widget), so the restore can use the
     * same two-branch death test the hide records use ({@link UiApi#stillMovable}).
     */
    static final class Moved {
        final Addon owner;
        final Widget wdg;
        final int id;
        Coord pos;       // the stock c, DEVICE — null: this addon's layer is not standing on the position
        Coord size;      // the stock size ARGUMENT (a Window's content size), DEVICE — null: nor on the size
        /**
         * This addon's <b>hand-named</b> position (036.2) — what {@code widget:position(x, y)} asked for, and the top
         * level of the layout cascade ({@link Layout}). Separate from {@link #pos} because they answer different
         * questions: one is what the user had, one is what this addon wants. {@code widget:position(nil)} clears this
         * and leaves the cascade to say what happens next — a rule that also names the widget takes over, and only
         * when nothing does at all is {@link #pos} given back and the half dropped.
         *
         * <p>An {@link Layout.Anchor} since 036.3, and the degenerate one: {@code widget:position(x, y)} is the anchor
         * to this widget's own parent's top-left. The verb keeps the whole hand-named level — a rule is where an
         * anchor to the screen or to another widget is said — but it goes down the one resolution path all the
         * same, which is what makes "the verb wins" a statement about a fold rather than about two mechanisms.
         */
        Layout.Anchor wantPos;
        /** This addon's hand-named size, in design pixels — see {@link #wantPos}. */
        Coord wantSize;
        /**
         * The stock caption (061.5), recorded at the text level's first touch — {@code null}: this addon's level
         * is not standing on what the widget says. One slot serves {@code widget:text(s)} and
         * {@code widget:title(s)} alike, since a widget is a window or a control and never both.
         */
        Cap text;
        /** This addon's hand-named caption — what {@code widget:text(s)}/{@code :title(s)} asked for. */
        String wantText;
        /** When each half was named, so the latest hand-named level wins between two addons ({@link Layout#nextSeq}). */
        long posSeq, sizeSeq, textSeq;

        Moved(Addon owner, Widget wdg, int id) {
            this.owner = owner;
            this.wdg = wdg;
            this.id = id;
        }

        /** Nothing of ours left on this widget ⇒ the record is dropped. */
        boolean idle() {
            return (pos == null) && (size == null) && (text == null)
                && (wantPos == null) && (wantSize == null) && (wantText == null);
        }
    }

    /**
     * Does <b>any</b> live owner hold a moved-native record right now? The global-empty fast path for the 036.1
     * persistence seam ({@link UiApi#stockPos}/{@link UiApi#stockSizeArg}), which {@code GameUI.savewndpos} asks
     * for six windows every 60 s and again at logout. A plain volatile read is the whole cost for a client no
     * addon has laid out — which is every client until one calls {@code w:position(x,y)} on a native widget.
     *
     * <p>Maintained exactly like {@link #anyHidden}: recomputed at the places a layout list changes (the two
     * verbs, their {@code nil} undo, {@link UiApi#teardownMoved}, {@link UiApi#pruneDeadTrees}). A stale
     * <i>true</i> costs only the walk, which then finds no record and hands back the widget's own value; a
     * stale <i>false</i> would silently persist our position as the user's, so it is only ever cleared by a
     * recount that actually looked.
     */
    static volatile boolean anyMoved = false;

    /** Recompute {@link #anyMoved} over every live owner — the loaded addons plus the {@code :lua} REPL. */
    static void recountMoved() {
        boolean any = false;
        List<Addon> as = AddonManager.addons;
        for(int i = 0, n = as.size(); !any && (i < n); i++)
            any = !as.get(i).movedNative.isEmpty();
        Addon c = AddonManager.consoleOwner;
        if(!any && (c != null))
            any = !c.movedNative.isEmpty();
        anyMoved = any;
    }

    /**
     * <b>The widgets some addon holds a layout level on, directly inside {@code parent}</b> (062) — each once,
     * however many owners are standing on it. It backs {@link Layout#reapply}, which is what a hand-named
     * place needs to survive {@code GameUI} re-placing its own children on a screen resize.
     *
     * <p>The record list is the bound, not the tree: a client with nothing laid out answers on one volatile
     * read, and one with two laid-out windows walks two entries.
     */
    static List<Widget> movedUnder(Widget parent) {
        List<Widget> out = new ArrayList<Widget>(2);
        if(!anyMoved)
            return out;
        List<Addon> as = AddonManager.addons;
        for(int i = 0, n = as.size(); i < n; i++)
            movedUnderIn(as.get(i), parent, out);
        movedUnderIn(AddonManager.consoleOwner, parent, out);
        return out;
    }

    private static void movedUnderIn(Addon a, Widget parent, List<Widget> out) {
        if(a == null)
            return;
        List<Moved> ms = a.movedNative;
        for(int i = 0, n = ms.size(); i < n; i++) {
            Moved m = ms.get(i);
            if((m.wdg.parent == parent) && !out.contains(m.wdg))
                out.add(m.wdg);
        }
    }

    /**
     * The size <b>argument</b> that reproduces {@code w}'s current geometry through {@link Widget#resize(Coord)}
     * — a {@link Window}'s content size ({@code csz()}, since its {@code resize} sizes the content and derives
     * the outer box from the deco), and plain {@code sz} for everything else. Both the record and the write go
     * through this one answer, which is what makes the restore an exact inverse.
     */
    static Coord sizeArg(Widget w) {
        return (w instanceof Window) ? ((Window)w).csz() : w.sz;
    }

    /** This owner's own layout record for a widget, or {@code null} (identity-keyed; the list is per-addon tiny). */
    static Moved findMoved(Addon owner, Widget w) {
        for(Moved m : owner.movedNative) {
            if(m.wdg == w)
                return m;
        }
        return null;
    }

    /**
     * The record for a BORROWED widget this addon is about to move or resize, minting it on first touch. Unlike
     * {@link #recordHidden} there is <b>no</b> one-widget-one-owner refusal: a position is not a toggle, two
     * addons can each hold a layer over the same window, and each restores what <i>it</i> found (D-070's rule
     * read one level down). The last writer wins on screen, which is the same answer the cascade gives.
     */
    static Moved recordMoved(Addon owner, Widget w) {
        Moved m = findMoved(owner, w);
        if(m != null)
            return m;
        UI u = w.ui;                          // the id counts inside the tree the widget stands in
        m = new Moved(owner, w, (u == null) ? -1 : u.widgetid(w));
        owner.movedNative.add(m);
        anyMoved = true;
        return m;
    }

    /**
     * <b>Detach {@code w} from its parent for a RE-HOME</b> (061.7) — which is a move, and not a death.
     *
     * <p>{@code Widget.remove()} is the client's <b>death notice</b>: it fires the removal seam, and it clears
     * {@code canfocus} (through {@code setcanfocus(false)}), which {@code add0} never puts back. Both are wrong
     * for {@code widget:parent(w)}, and each is wrong in a way an addon would meet immediately: a control built
     * into one of the client's windows would have its own {@code widget:on("Removed", fn)} fire on the next
     * tick — while it is alive, drawn and clickable, and with every other subscription on it dropped with it —
     * and a text entry built into one could never be typed into again.
     *
     * <p>So this is the re-home the engine map states: unlink, tell the old parent, clear the link, and correct
     * that parent's focus bookkeeping by hand. {@code ui.removed} is skipped for the reason the recipe skips
     * it — it drops the {@code UI.Grab}s a still-live widget should keep. Caller holds the {@code ui} monitor.
     */
    private static void rehome(Widget w) {
        Widget p = w.parent;
        if(p == null)
            return;
        if(w.canfocus)
            p.delfocusable(w);      // ...without clearing the flag, so p.add re-registers it on the new parent
        w.unlink();
        p.cdestroy(w);
        w.parent = null;
    }

    /**
     * <b>{@code widget:pack()} on a widget this addon did not build</b> (061.7) — the client's own window,
     * refitted around what is inside it, or the refusal that names the verb to use instead.
     *
     * <p><b>It is a level, not a write into the client's box.</b> The stock size is recorded first, exactly as
     * {@code widget:size(w, h)} records it, and the box the pack came out at becomes this addon's
     * {@link Moved#wantSize} — so one record serves both verbs, {@code widget:size(nil)} gives the stock outer
     * box back, and {@code :reload}/disable do the same. The stock half must be taken <b>before</b> the pack:
     * {@link Layout#applyHalf} would otherwise record the packed box as what the user had.
     *
     * <p><b>A window is the only borrowed widget it answers on.</b> What box one of the client's own widgets
     * has is the client's to choose — an inventory grid packed to its items' bounding box is a grid with no
     * empty slots left to drop into — so anything else refuses, naming the verbs that answer there. A window that
     * packs itself around its own contents (the main inventory is one) makes the call <b>inert, never an
     * error</b>: the pack is honoured and undone by the client before the call returns, which is the rule
     * {@code widget:size(w, h)} already follows on those same windows.
     */
    private static void nativePack(Addon owner, Widget w) {
        if(!(w instanceof Window))
            throw new LuaError("widget:pack() refits a WINDOW around what is inside it, and " + typeName(w)
                + " is not one — a widget the client laid out is drawn in the box the client chose, and the"
                + " window around it is what refits. widget:size(w, h) sets a borrowed widget's box by hand,"
                + " and widget:size(nil) gives it back.");
        synchronized(monitor(w)) {
            Moved rec = recordMoved(owner, w);
            if(rec.size == null)
                rec.size = UiApi.stockSizeArg(w);      // the box the USER had, read before the pack moves it
            w.pack();
            rec.wantSize = Px.out(sizeArg(w));         // ...and what it came out at IS this addon's size level
            rec.sizeSeq = Layout.nextSeq();
        }
        // The fold, so a rule and a second addon still compete — and 112.6: below the block, since a
        // follower of this window may stand in another tree.
        Layout.apply(w);
    }

    // ---- widget:remember(name): the placement that survives the session (062) ----------------------

    /** The name THIS addon remembers {@code w} under, or {@code null} — the read arity of the verb. */
    private static String rememberedName(Addon owner, Widget w) {
        for(Map.Entry<String, Widget> e : owner.remembered.entrySet()) {
            if(e.getValue() == w)
                return e.getKey();
        }
        return null;
    }

    /**
     * {@code widget:remember(name)} — bind the name, then put back whatever it holds. One name holds one
     * widget: a <b>second</b> widget asking for a name this addon already holds raises, because the alternative
     * is two windows sharing one saved place and each overwriting the other every time the user moves either.
     * A name whose widget has left the tree is free again — the binding was on that widget, and it is gone.
     *
     * <p>Renaming a widget this addon already remembers moves the binding and <b>leaves the old record</b>:
     * only {@code widget:remember(nil)} deletes one.
     */
    private static void rememberAs(Addon owner, Widget w, String name) {
        Widget held = owner.remembered.get(name);
        if((held != null) && (held != w) && inTree(held))
            throw new LuaError("widget:remember(\"" + name + "\"): this addon already remembers a "
                + typeName(held) + " under that name — one name, one widget. Pick another name, or drop that"
                + " one with widget:remember(nil) first.");
        String prev = rememberedName(owner, w);
        if((prev != null) && !prev.equals(name))
            owner.remembered.remove(prev);            // a change of NAME: the record it held is left standing
        owner.remembered.put(name, w);
        rememberApply(owner, w, name);
    }

    /**
     * Put back what one name holds, <b>through the verbs' own write</b>: this addon's hand-named position and
     * size levels, each with a fresh {@code seq}, and one {@link Layout#apply} over the fold they compete in.
     * So the off-screen clamp, a window that packs itself around its contents, the two {@code nil} undos and
     * {@code widget:revert()} all answer here exactly as they answer for {@code widget:position(x, y)} — and a
     * write made <i>after</i> this one wins, being the later level.
     *
     * <p>A half the record does not hold is not written at all, so remembering a widget the user only ever
     * dragged does not pin a box they never chose.
     */
    private static void rememberApply(Addon owner, Widget w, String name) {
        if(!StoreApi.placementScope(w)) {
            // Criterion 9: it has nothing to apply, and saying so beats applying an empty record — the addon
            // called it too early, and the answer is a moment rather than a different verb.
            // 092.8: "no character is in world yet" is now about THIS widget's own session. A window the addon
            // built itself is in the layer and is filed under the account, so it never reaches this branch.
            AddonManager.logAbout(owner, "widget:remember(\"" + name + "\"): a saved place is per CHARACTER and the"
                + " session this widget stands in has no character in world yet, so there is nothing to put"
                + " back. Call it from that session's SessionEnteredWorld onwards; what happens to the widget"
                + " from here is saved under that name all the same.");
            return;
        }
        StoreApi.Placement p = StoreApi.placement(owner, w, name);
        if(p == null)
            return;                                   // nothing saved under it yet: the name is where it will go
        synchronized(monitor(w)) {
            Moved rec = recordMoved(owner, w);
            if(p.pos != null) {
                rec.wantPos = Layout.Anchor.at(p.pos);
                rec.posSeq = Layout.nextSeq();
            }
            if(p.size != null) {
                rec.wantSize = p.size;
                rec.sizeSeq = Layout.nextSeq();
            }
        }
        Layout.apply(w);        // 112.6: below the block — a follower of this window may be another tree's
    }

    /** {@code widget:remember(nil)} — drop the name AND delete the record, which is the whole difference. */
    private static void rememberForget(Addon owner, Widget w) {
        String nm = rememberedName(owner, w);
        if(nm == null)
            return;
        owner.remembered.remove(nm);
        StoreApi.forget(owner, w, nm);
    }

    /**
     * {@code widget:revert()} reached {@code w}: this addon stops remembering it, and <b>what is saved stays
     * saved</b>. The teardown instinct is wrong here and this is the one place it has to be said: a revert
     * gives back what the addon took, and where the user dragged a window is not something it took.
     */
    static void rememberDrop(Addon owner, Widget w) {
        String nm = rememberedName(owner, w);
        if(nm != null)
            owner.remembered.remove(nm);
    }

    /** Teardown ({@code :reload}/disable): the same, for every name at once — and the records stay on disk. */
    static void rememberTeardown(Addon a) {
        if(a != null)
            a.remembered.clear();
    }

    /**
     * A gesture just ended on {@code w}: save the half it drove, if this addon remembers the widget. The value
     * is read off the widget rather than off the level, so what is saved is where it <b>landed</b> — the clamp,
     * and a window that re-packed itself, both having had their word.
     */
    static void rememberLanded(Addon owner, Widget w, boolean pos) {
        String nm = rememberedName(owner, w);
        if(nm != null)
            StoreApi.land(owner, w, nm, pos ? Px.out(w.c) : null, pos ? null : Px.out(sizeArg(w)));
    }

    /**
     * Every remembered widget of one addon, as it stands right now — run by {@link StoreApi#flush} before it
     * writes, which is what makes the save timer, {@code hafen.store():flush()} and teardown all record the
     * same thing. A half this addon holds no level on is left as it was: an addon that never sized a window has
     * nothing to say about its box, and nothing to erase either.
     *
     * <p><b>It does not ask whether the widget is still in the tree</b>, and that is the case it exists for: a
     * relog tears every addon down with the <i>new</i> {@code UI} already installed, so the last session's
     * windows answer "not in this tree" while still carrying the coordinate the user dropped them at. Reading
     * a stale widget's last geometry is exactly right here — where it was when it went is where it was.
     *
     * <p>092.8: each lands in the scope of <b>its own</b> tree, so one capture over an addon's remembered
     * widgets fills as many folders as the addon has trees with a window in them.
     */
    static void rememberCapture(Addon a) {
        if((a == null) || a.remembered.isEmpty())
            return;
        for(Map.Entry<String, Widget> e : a.remembered.entrySet()) {
            Widget w = e.getValue();
            Moved rec = findMoved(a, w);
            if(rec == null)
                continue;                             // nothing of ours is standing on it: nothing to record
            Coord pos = ((rec.wantPos == null) || (w.c == null)) ? null : Px.out(w.c);
            Coord size = ((rec.wantSize == null) || (w.sz == null)) ? null : Px.out(sizeArg(w));
            StoreApi.land(a, w, e.getKey(), pos, size);
        }
    }

    /** Is this widget still hanging under its own tree's root? (A raw {@link Widget}, so not {@link #live}.) */
    private static boolean inTree(Widget w) {
        UI u = (w == null) ? null : w.ui;      // 074.1: this widget's tree, which may be the addon layer
        return (u != null) && !u.destroyed && (u.root != null) && (w.parent != null) && w.hasparent(u.root);
    }

    /**
     * The winning <b>hand-named</b> layer on {@code w} for one half (036.2) — the record with the latest
     * {@code seq}, over every live owner, or {@code null} when no addon has named this half by hand. This is the
     * top level of {@link Layout}'s fold, above every rule that merely <i>matched</i> the widget (D-077).
     */
    static Moved topWant(Widget w, boolean pos) {
        Moved best = null;
        List<Addon> as = AddonManager.addons;
        for(int i = 0, n = as.size(); i < n; i++)
            best = topWantIn(as.get(i), w, pos, best);
        return topWantIn(AddonManager.consoleOwner, w, pos, best);
    }

    private static Moved topWantIn(Addon a, Widget w, boolean pos, Moved best) {
        if(a == null)
            return best;
        List<Moved> ms = a.movedNative;
        for(int i = 0, n = ms.size(); i < n; i++) {
            Moved m = ms.get(i);
            if((m.wdg != w) || ((pos ? m.wantPos : m.wantSize) == null))
                continue;
            long s = pos ? m.posSeq : m.sizeSeq;
            if((best == null) || (s > (pos ? best.posSeq : best.sizeSeq)))
                best = m;
        }
        return best;
    }

    /**
     * The winning <b>hand-named</b> caption on {@code w} (061.5), or {@code null} when no addon is standing on
     * what this widget says — {@link #topWant}'s shape one property along, and the same tie-break: the latest
     * level wins, since two addons may each write a caption and neither is a toggle.
     */
    static Moved topWantText(Widget w) {
        Moved best = null;
        List<Addon> as = AddonManager.addons;
        for(int i = 0, n = as.size(); i < n; i++)
            best = topWantTextIn(as.get(i), w, best);
        return topWantTextIn(AddonManager.consoleOwner, w, best);
    }

    private static Moved topWantTextIn(Addon a, Widget w, Moved best) {
        if(a == null)
            return best;
        List<Moved> ms = a.movedNative;
        for(int i = 0, n = ms.size(); i < n; i++) {
            Moved m = ms.get(i);
            if((m.wdg != w) || (m.wantText == null))
                continue;
            if((best == null) || (m.textSeq > best.textSeq))
                best = m;
        }
        return best;
    }

    /** {@link #dropStock} for the text half — no level says what {@code w} says any more (061.5). */
    static boolean dropStockText(Widget w) {
        if(!anyMoved)
            return false;
        boolean held = false;
        List<Addon> as = AddonManager.addons;
        for(int i = 0, n = as.size(); i < n; i++)
            held |= dropStockTextIn(as.get(i), w);
        held |= dropStockTextIn(AddonManager.consoleOwner, w);
        if(held)
            recountMoved();
        return held;
    }

    private static boolean dropStockTextIn(Addon a, Widget w) {
        if(a == null)
            return false;
        boolean held = false;
        for(Moved m : a.movedNative) {
            if((m.wdg != w) || (m.text == null))
                continue;
            m.text = null;
            held = true;
            if(m.idle())
                a.movedNative.remove(m);
        }
        return held;
    }

    /**
     * <b>What the widget says right now becomes the stock caption</b> (061.6) — every owner standing on
     * {@code w} re-records it, which is how a text level survives the server rewriting what is under it and
     * still gives back the <i>server's</i> latest value rather than the one from before the update.
     *
     * <p>Called at the one instant where that re-read is honest: the drain after the update landed, before
     * this addon's level goes back on top ({@link Layout#serverWroteText}). The inbound tap carries no
     * arguments, so reading the widget back at exactly that moment <i>is</i> having had them.
     */
    static void restockText(Widget w) {
        if(!anyMoved)
            return;
        Cap now = readCap(w);
        if(now == null)
            return;                                   // nothing to read back: leave every record as it stands
        List<Addon> as = AddonManager.addons;
        for(int i = 0, n = as.size(); i < n; i++)
            restockTextIn(as.get(i), w, now);
        restockTextIn(AddonManager.consoleOwner, w, now);
    }

    private static void restockTextIn(Addon a, Widget w, Cap now) {
        if(a == null)
            return;
        for(Moved m : a.movedNative) {
            if((m.wdg == w) && (m.text != null))
                m.text = now;
        }
    }

    /**
     * No level names this half of {@code w} any more: forget the stock value every owner recorded for it, dropping
     * a record that has nothing left. Answers whether anything was actually being held — which is what tells
     * {@link Layout} whether there is a value to give back or the widget was never ours to begin with.
     */
    static boolean dropStock(Widget w, boolean pos) {
        if(!anyMoved)
            return false;
        boolean held = false;
        List<Addon> as = AddonManager.addons;
        for(int i = 0, n = as.size(); i < n; i++)
            held |= dropStockIn(as.get(i), w, pos);
        held |= dropStockIn(AddonManager.consoleOwner, w, pos);
        if(held)
            recountMoved();
        return held;
    }

    private static boolean dropStockIn(Addon a, Widget w, boolean pos) {
        if(a == null)
            return false;
        boolean held = false;
        for(Moved m : a.movedNative) {
            if((m.wdg != w) || ((pos ? m.pos : m.size) == null))
                continue;
            if(pos)
                m.pos = null;
            else
                m.size = null;
            held = true;
            if(m.idle())
                a.movedNative.remove(m);
        }
        return held;
    }

    /**
     * Drop the records of one widget that just left the tree (036.2, from {@link Layout#dispatchRemoved}, M1,
     * 042.10). Nothing is restored — there is nothing left to restore it to — but the record would otherwise
     * outlive its widget and, worse, hold a strong reference to it: a rule that names a kind of window records
     * one per window that opens. Replaces the per-tick sweep over every owner's whole list (which used {@link
     * Layout#alive} to ask each record whether its widget was still reachable): the removal seam already
     * KNOWS which widget just died, so this targets it directly rather than re-deriving liveness for every
     * record on every tick.
     */
    static void pruneRemoved(Widget w) {
        if(!anyMoved)
            return;
        boolean gone = false;
        List<Addon> as = AddonManager.addons;
        for(int i = 0, n = as.size(); i < n; i++)
            gone |= pruneRemovedIn(as.get(i), w);
        gone |= pruneRemovedIn(AddonManager.consoleOwner, w);
        if(gone)
            recountMoved();
    }

    private static boolean pruneRemovedIn(Addon a, Widget w) {
        if((a == null) || a.movedNative.isEmpty())
            return false;
        boolean gone = false;
        for(Moved m : a.movedNative) {
            if(m.wdg != w)
                continue;
            a.movedNative.remove(m);
            gone = true;
        }
        return gone;
    }

    // ---- items: the relation (029.3) — the lifecycle notifications live on WidgetSubs since 041.4 -----

    /**
     * The items inside a container widget, as an array of <b>Item objects</b> ({@code widget:items()}). {@code
     * children(WItem.class)} is a <b>deep</b> traversal, so a whole window answers for the grid inside it, and
     * {@link LuaItem#items} de-duplicates — an {@link Equipory} draws one worn item once per slot it fills, and
     * two entries that are {@code ==} would make {@code #items} a lie. Where each one sits is read off the item
     * ({@code :cell()}, {@code :slots()}). Taken under the {@code ui} monitor, like every other tree read.
     */
    static LuaValue items(Addon owner, Widget w) {
        return LuaItem.list(owner, w);
    }

    /** A copy of a container's {@link WItem} children (deep), taken under the {@code ui} monitor. */
    static List<WItem> witems(Widget w) {
        if(w == null)
            return new ArrayList<WItem>();
        synchronized(monitor(w)) { return new ArrayList<WItem>(w.children(WItem.class)); }
    }

    // ---- liveness + the reads ----------------------------------------------------------------------

    /**
     * Resolve a handle's widget, checking liveness (spec 20, W1): a widget still attached to the tree is live, one
     * detached (destroyed &rarr; {@code parent} nulled) is stale. Reachability is
     * {@link Widget#hasparent(Widget) hasparent(ui.root)} (O(depth), the {@code GobRef}-per-access discipline);
     * once stale we <b>null the handle's reference</b> so it cannot pin a dead subtree, and every read then answers
     * {@code nil}/empty. Returns {@code null} when there is no UI yet (transient — the ref is kept, not killed) or
     * the handle is stale/{@code null}.
     *
     * <p><b>The root is this widget's own tree's</b> (074.1). An addon's own windows live in the addon layer and
     * the client's live in a session, so "is it still in the tree" has to name <i>which</i> tree, and the widget
     * carries the answer. Asked against the session on screen instead, every handle to a window this addon built
     * would read stale the instant it was built. A tree that has been taken down is the case the root test no
     * longer catches by itself, and {@code UI.destroyed} is the flag that says so.
     */
    static Widget live(LuaWidget n) {
        if(n == null)
            return null;
        Widget w = n.wdg;
        if(w == null)
            return null;
        UI u = w.ui;
        if((u == null) || (u.root == null))
            return null;                       // no UI yet: unresolvable now, but not proven dead — keep the ref
        // audit2 B06: the walk runs under that tree's own monitor. A Loader thread re-links parent/child
        // under it, and the failure here is PERMANENT — a walk that raced a re-link and ended early nulls
        // the handle's reference and retires a widget that is still on screen for the rest of the session.
        boolean gone;
        synchronized(monitor(w)) {
            gone = u.destroyed || !w.hasparent(u.root);
        }
        if(gone) {                             // its tree is gone, or it left it → destroyed
            n.wdg = null;                      // drop the ref so a dead subtree can be GC'd (no pin)
            return null;
        }
        return w;
    }

    /**
     * The receiver of a scoped search ({@code widget:match}/{@code :matchAll}, 049.2), or the refusal. This is
     * the one place the section does not fall back on the 029.2 nil/empty answer for a stale widget: a search
     * of a subtree that has left the tree would report "nothing matched", which is a different fact from "there
     * is nothing there", and the pair exists precisely to be used on a handle whose widget may have closed.
     */
    private static Widget searched(Widget w, String verb) {
        if(w == null)
            throw new LuaError("widget:" + verb + "(selector) searches INSIDE a widget, and this one is not in the"
                + " tree (widget:exists() is false) — nothing was searched. An empty answer here would read as"
                + " \"no match\", which is not what happened");
        return w;
    }

    /** A copy of {@code w}'s child list, taken under the {@code ui} monitor so a walk never races tree mutation. */
    private static List<Widget> kids(Widget w) {
        synchronized(monitor(w)) { return new ArrayList<Widget>(w.children()); }
    }

    /**
     * The class name for {@code :type()} (spec 20, W1). {@code getClass().getSimpleName()} — but Hafen builds a
     * great many widgets as <b>anonymous subclasses</b> (e.g. {@code new TextEntry(...) {...}}), whose simple name
     * is the empty string; so for an anonymous/local class we climb to the nearest <b>named</b> superclass (a
     * {@code new Button(...){}} reports {@code "Button"}), which is the useful identity for building adapters.
     * Falls back to {@code "?"} only in the impossible case of no named ancestor.
     *
     * <p><b>A control adapter is climbed past for the same reason</b> (040.1): {@link CtlButton} is a
     * {@link haven.Button}, and the {@code Ctl} prefix is an implementation detail of this bridge that must
     * never become a name in the API — {@code w:type()} reads {@code "Button"} whether the addon built the
     * button or found one, so every selector, role and stylesheet key that names the engine's class keeps
     * matching. The marker is {@link Owned.Control}, which is exactly the set of adapter classes.
     */
    static String typeName(Widget w) {
        Class<?> c = w.getClass();
        String n = c.getSimpleName();
        while((n.isEmpty() || Owned.Control.class.isAssignableFrom(c)) && (c.getSuperclass() != null)) {
            c = c.getSuperclass();
            n = c.getSimpleName();
        }
        return n.isEmpty() ? "?" : n;
    }

    /**
     * <b>The widget&rarr;role classifier</b> ({@code widget:role()}, and the first half of every {@link Selector}) —
     * spec {@code 030-ui-selectors}, and the heart of that feature. ONE {@code instanceof} chain in ONE method, the
     * same discipline {@link #typeName}/{@link #text} already use for fragile upstream knowledge: upstream churn
     * breaks this method, not addons.
     *
     * <p><b>The question is the inverse of the one the font scopes answer.</b> Fonts resolve <i>scope &rarr;
     * override</i> at each render site, which is a lookup the site performs on itself; a selector must answer
     * <i>what role is THIS widget?</i>, and nothing in {@code haven} answers that — hence this chain.
     *
     * <p><b>Never a wrong answer in place of no answer.</b> An unrecognised widget is {@code null} (&rarr; Lua
     * {@code nil}), and a role is only claimed where the class genuinely <i>is</i> that thing: {@code Inventory}/
     * {@code Equipory} are the containers, every {@link Window} (including {@code GameUI.Hidewnd} and every
     * subclass) is the frame. The five remaining promoted font scopes ({@code window.title}, {@code heading},
     * {@code tooltip}, {@code world.nick}, {@code world.speech}) are deliberately absent: they name render sites
     * that are drawn, not widgets that are placed (a caption belongs to {@code Window.Deco}), and guessing a
     * {@link Label} was a heading would be exactly the wrong answer this rule forbids. The role set is expected to
     * grow; what must not happen is a role that matches the wrong widget.
     *
     * <p>Ordered most-specific first, and {@code null}-safe.
     */
    static String role(Widget w) {
        if(w == null)
            return null;
        if((w instanceof Inventory) || (w instanceof Equipory))
            return "inventory";
        // 104: the icon ONE item is drawn as, wherever it is drawn -- a slot in a container, a slot of the
        // equipment grid, or the cursor while the item is being carried. A role is an `instanceof`, so it covers
        // the subclasses `@Class` deliberately does not: the cursor's icon is an ItemDrag, a WItem all the same,
        // and an addon decorating item icons wants both without having to know that name.
        if(w instanceof WItem)
            return "item";
        if(w instanceof Window)
            return "window";
        if((w instanceof Button) || (w instanceof IButton))
            return "button";
        if(w instanceof Label)
            return "label";
        if(w instanceof TextEntry)
            return "textentry";
        if((w instanceof ChatUI) || (w instanceof ChatUI.Channel))
            return "chat";
        if((w instanceof FlowerMenu) || (w instanceof MenuGrid))
            return "menu";
        return null;
    }

    /* ---- the name an addon gives a widget it built (107) --------------------------------------------
     *
     * The one part of a selector step an ADDON owns. Every other part is somebody else's: a role and a
     * class come off the Java type, a caption and a text off what the widget displays, a resource off what
     * the server named. None of them can tell one addon's box from another's -- every bare widget any addon
     * builds reports the same class -- so without this a theme could reach "all addon boxes" and nothing
     * finer.
     *
     * WRITE-ONCE, and deliberately: a name is identity, not state. The four states a surface can be in ride
     * inside the VALUE (`bg = {..., hover = ...}`), and renaming to express a fifth would be a per-state
     * selector by the back door -- which the stylesheet's own page rules out in as many words. Held weakly
     * against the widget, so a name dies with the thing it named.
     */
    // retained: weak keys over a String value -- nothing in the entry reaches the widget, so it collects.
    private static final Map<Widget, String> names = new WeakHashMap<Widget, String>();

    /** The name {@code w} was given, {@code "<addon>/<name>"}, or {@code null} for the great majority. */
    static synchronized String nameOf(Widget w) {
        return (w == null) ? null : names.get(w);
    }

    /**
     * Name {@code w} once. {@code false} if it already carries one — the caller raises naming what it is
     * already called, rather than letting a second name silently win and leaving a theme pointing at a
     * widget that stopped answering to it.
     */
    static synchronized boolean nameSet(Widget w, String full) {
        if((w == null) || (full == null) || names.containsKey(w))
            return false;
        names.put(w, full);
        return true;
    }

    /**
     * The widget's <b>resource name</b> ({@code widget:res()}, and what {@code [res=]} matches by substring) — the
     * stable server-published key of D-063, where the widget has one; {@code null} otherwise, never a throw.
     *
     * <p>Three sources, because "the res of a widget" is three different things in {@code haven} and no field on
     * {@link Widget} holds any of them:
     * <ol>
     *   <li>a widget whose <b>code came from a resource</b> ({@code Widget.gettype3} on a {@code /}-name &rarr;
     *       {@code Resource.getcode}) — its class was defined by a {@link Resource.ResClassLoader}, or carries the
     *       {@link FromResource} annotation a {@code get-code} copy is stamped with. We read the loader/annotation
     *       directly rather than calling {@code Resource.classres}, which <b>blocks</b> on a
     *       {@code remote().loadwait} for the annotated case and throws for everything else;</li>
     *   <li>a {@link WItem} &rarr; its item's resource (the same key {@code widget:items()} entries carry);</li>
     *   <li>an {@link IMeter} &rarr; its background resource — the identity {@code s:meter():find(needle)} already
     *       matches on (027, D-063).</li>
     * </ol>
     *
     * <p>Anonymous subclasses are the norm, so the class walk climbs superclasses the way {@link #typeName} does.
     * A resource still loading resolves to {@code null} for now ({@link AddonManager#resIdent} is Loading-guarded)
     * and answers on a later call — the same "nameless for a beat" behaviour meters have.
     */
    static String resName(Widget w) {
        if(w == null)
            return null;
        if(w instanceof WItem) {
            WItem it = (WItem)w;
            return (it.item == null) ? null : AddonManager.resIdent(it.item.res);
        }
        if(w instanceof IMeter)
            return AddonManager.resIdent(((IMeter)w).bg);
        if(w instanceof ISBox)                     // a building site's material box: the material it counts
            return AddonManager.resIdent(((ISBox)w).res());
        for(Class<?> c = w.getClass(); c != null; c = c.getSuperclass()) {
            ClassLoader l = c.getClassLoader();
            if(l instanceof Resource.ResClassLoader) {
                try {
                    Resource r = ((Resource.ResClassLoader)l).getres();
                    return (r == null) ? null : r.name;
                } catch(RuntimeException e) {       // Loading, or a half-built code entry
                    return null;
                }
            }
            FromResource src = Resource.ResClassLoader.getsource(c);
            if(src != null)
                return src.name();
        }
        return null;
    }

    /**
     * The widget's <b>picture</b> ({@code widget:picture()}) — the resource name of the art it is showing, or
     * {@code null} where it shows none; never a throw.
     *
     * <p>A different question from {@link #resName}, which names the resource a widget's own <i>code</i> came
     * out of. This one asks the widget which picture OBJECT it is holding and hands that object to
     * {@link AddonManager#pictureName}, the registry {@link haven.Resource.Image} fills as it mints one. ONE
     * {@code instanceof} chain in ONE method, the discipline {@link #typeName}/{@link #role}/{@link #resName}
     * already use for fragile upstream knowledge.
     *
     * <p><b>The RESTING face</b>, on the two controls that carry several: a button is identified by the art it
     * sits at, and its pressed and hovered faces are the same name with a suffix.
     *
     * <p><b>Three classes, and no fourth.</b> {@code Avaview} and the meters compose their picture at runtime
     * rather than holding one that came out of a {@code .res}, so a branch for them could only ever answer
     * {@code null} — which it already does from outside the chain, honestly, instead of claiming it was asked.
     */
    static String pictureName(Widget w) {
        if(w == null)
            return null;
        if(w instanceof Img)                       // Img.img is private, and live: Img.img() (// addon:) reads it
            return AddonManager.pictureName(((Img)w).img());
        if(w instanceof IButton)
            return AddonManager.pictureName(((IButton)w).up);
        if(w instanceof ICheckBox)
            return AddonManager.pictureName(((ICheckBox)w).up);
        return null;
    }

    /**
     * <b>Is this a field the user's own secret goes into?</b> (audit2 B08, pk-02) — a {@link TextEntry} the
     * client hides what is typed into, by either of the two flags it has for it: {@code pw} (the classic
     * password field, rendered as dots) and {@code dshow} (shown until it is committed, then hidden — what
     * {@code BuddyWnd} builds its {@code charpass} and {@code opass} entries with).
     *
     * <p><b>Every read of a text entry's buffer asks this first.</b> {@code widget:text()},
     * {@code widget:value()} and the {@code :info()} snapshot that is built out of the first all used to end
     * in {@code TextEntry.text()} with no exclusion of any kind, so an ordinary parent/children walk read the
     * character's hearth secret out of the kin window — the one credential {@code kin:add} exists to consume,
     * which the server itself fills that field with on a {@code "pwd"} message. They answer {@code nil} now,
     * which is what a read answers for anything else it cannot see.
     *
     * <p>The WRITE is untouched: {@code widget:value(v)} is protected by {@code widget.value} and typing into
     * a field is exactly what that key is for. It is reading one back that no key ever bought.
     */
    static boolean secret(Widget w) {
        if(!(w instanceof TextEntry))
            return false;
        TextEntry t = (TextEntry)w;
        return t.pw || t.dshow;
    }

    /**
     * Best-effort text for a text-bearing widget ({@code :text()}, spec 20, W1) — the one upstream-volatile bit,
     * localized in THIS switch (like the spec-14 adapters): {@link Label#texts}, {@link Button} caption,
     * {@link Window#cap}, {@link TextEntry#text()}, {@link ISBox#label()}. An unknown type returns {@code null} (&rarr; Lua {@code nil}),
     * never throws — upstream churn breaks only this method, not addons.
     *
     * <p>A {@link #secret} entry answers {@code nil}, whatever it holds.
     */
    static String text(Widget w) {
        if(secret(w))
            return null;                           // audit2 B08 (pk-02): a credential is not text
        if(w instanceof Label)
            return ((Label)w).texts;
        if(w instanceof Button) {
            // addon: (102.1) the caption this button was WRITTEN, not the raster it drew. A catalogue makes
            // the two different strings, and every read in this API answers the client's own English. A
            // button built from a Text or a picture has no `rtext`, and its raster is the only caption there
            // is -- which is the same best-effort this method has always been.
            Button b = (Button)w;
            if(b.rtext != null)
                return b.rtext;
            Text t = b.text;
            return (t == null) ? null : t.text;
        }
        if(w instanceof Window)
            return ((Window)w).cap;
        if(w instanceof TextEntry)
            return ((TextEntry)w).text();
        if(w instanceof CheckBox)                  // 040.4: CheckBox.lbls is public for exactly this read
            return ((CheckBox)w).lbls;             // addon: (102.2) the caption it was WRITTEN -- see Button above
        if(w instanceof ISBox)                     // a building site's material box: the "have/total" it draws
            return ((ISBox)w).label();
        return null;
    }

    // ---- the text level (061.5): what a BORROWED widget says, and giving it back ---------------------

    /**
     * <b>A stock caption, as the client holds it</b> (061.5) — the stock half of the text level, and
     * deliberately <b>not</b> a {@link String}. A {@link Button}'s caption is three fields ({@link Button#rtext},
     * {@link Button#rcol}, {@link Button#rwrap}) and {@code change(String)} zeroes the last two, so a coloured
     * or a wrapped ({@code ltbtn}) caption given back through it comes back rendered wrong; a {@link Label}'s
     * wrap width is the same story one class along. The record keeps what the arm it came from needs, and
     * {@link #writeCap} goes back through that same arm.
     *
     * <p>{@link #wrap} is kept <b>exactly as the widget's own field holds it</b> — a {@code Button} means
     * {@code 0} by "no wrap" and a {@code Label} means {@code -1} — because a {@code Cap} is only ever written
     * back to the widget it was read from, and normalising it here would be a second convention to get wrong.
     */
    static final class Cap {
        final String text;
        final Color col;      // a Button's rcol; null on every other arm
        final int wrap;

        Cap(String text, Color col, int wrap) {
            this.text = text;
            this.col = col;
            this.wrap = wrap;
        }
    }

    /**
     * <b>What {@code w} says, in the form that puts it back</b> — {@code null} on a widget with nothing to say,
     * which is what {@code widget:text(s)} refuses on. One {@code instanceof} chain in one method, the same
     * discipline {@link #text(Widget)} and {@link #value(Widget)} use for fragile upstream knowledge.
     *
     * <p>A {@link Button} built from a {@link Text} or a picture has no {@code rtext} at all: its face was
     * rendered by whoever made it and there is nothing to render back, so it reads as having nothing to say.
     */
    static Cap readCap(Widget w) {
        if(w instanceof Label) {
            Label l = (Label)w;
            return new Cap(l.texts, null, l.wrapw());
        }
        if(w instanceof Button) {
            Button b = (Button)w;
            return (b.rtext == null) ? null : new Cap(b.rtext, b.rcol, b.rwrap);
        }
        if(w instanceof CheckBox) {                // an ICheckBox is a picture, and is not one of these
            Text t = ((CheckBox)w).lbl;
            return new Cap((t == null) ? "" : t.text, null, 0);
        }
        if(w instanceof Window)                    // ...and a window's is widget:title(s)'s half of the level
            return new Cap(((Window)w).cap, null, 0);
        return null;
    }

    /**
     * <b>Is this server update one that rewrites a caption a level can stand on?</b> (061.6) — a {@link Label}'s
     * {@code "set"}, a {@link Button}'s {@code "ch"} and a {@link Window}'s {@code "cap"} are the three the
     * server has, one per arm of {@link #readCap} that it can reach at all (a {@link CheckBox}'s label is
     * written by the client that built it and by nothing on the wire).
     *
     * <p>The class <b>and</b> the message, never either alone: {@code "ch"} is also how a checkbox is told its
     * state and {@code "set"} how a meter is told its bar, and this gates the client's hottest inbound path.
     * One method, like the switches above, so upstream renaming one of them breaks this and nothing else.
     */
    static boolean rewritesText(Widget w, String msg) {
        if(w instanceof Label)
            return "set".equals(msg);
        if(w instanceof Button)
            return "ch".equals(msg);
        if(w instanceof Window)
            return "cap".equals(msg);
        return false;
    }

    /** Put a stock caption back, through the arm it came from. Caller holds the {@code ui} monitor. */
    static void writeCap(Widget w, Cap c) {
        if((w == null) || (c == null))
            return;
        if(w instanceof Label)
            ((Label)w).settext(c.text, c.wrap);
        else if(w instanceof Button)
            ((Button)w).caption(c.text, c.col, c.wrap);
        else if(w instanceof CheckBox)
            ((CheckBox)w).settext(c.text);
        else if(w instanceof Window)
            ((Window)w).chcap(c.text);
        WidgetSurface.touch(w);                    // 044.1: standing in the world? its picture is out of date
    }

    /**
     * Write <b>this addon's level</b> onto {@code w} — a plain string, which is all a level is. A wrapped
     * {@link Label} keeps its wrap (the level is what it says, not how it is laid out); a {@link Button} goes
     * through {@code change(String)}, which re-renders <i>and</i> {@code redraw()}s, because an
     * {@code SIWidget} keeps its old raster otherwise. Caller holds the {@code ui} monitor.
     */
    static void writeText(Widget w, String s) {
        if(w instanceof Label)
            ((Label)w).settext(s, ((Label)w).wrapw());
        else if(w instanceof Button)
            ((Button)w).change(s);
        else if(w instanceof CheckBox)
            ((CheckBox)w).settext(s);
        else if(w instanceof Window)
            ((Window)w).chcap(s);
        WidgetSurface.touch(w);
    }

    /**
     * {@code widget:text(s)} on a widget this addon did not build — the level, or the refusal that names the
     * verb to use instead. The two refusals that already pointed at each other keep pointing: a text entry's
     * content is what it HOLDS, and a window's caption is its own verb.
     */
    private static void nativeText(Addon owner, Widget w, String s) {
        if(w instanceof TextEntry)
            throw new LuaError("widget:text(s) writes what a widget SAYS, and a text entry's content is what it"
                + " HOLDS — widget:value(v) is the one door that writes it, and the server sees what you typed."
                + " widget:text() still reads the line back.");
        if(w instanceof Window)
            throw new LuaError("widget:text(s) writes a CONTROL's caption, and " + typeName(w) + " is a window —"
                + " a window's caption is widget:title(s), on one of the client's exactly as on one you built.");
        if(readCap(w) == null)
            throw new LuaError("widget:text(s) writes what a widget says, and " + typeName(w) + " has nothing to"
                + " say — a Label, a Button's caption and a CheckBox's label are what carry text. A button whose"
                + " face is a PICTURE has no caption at all, and neither has a checkbox that shows one.");
        recordText(owner, w, s);
    }

    /** Name this addon's text level on a borrowed widget, and let {@link Layout} resolve what is on screen. */
    private static void recordText(Addon owner, Widget w, String s) {
        synchronized(monitor(w)) {
            Moved rec = recordMoved(owner, w);
            rec.wantText = s;
            rec.textSeq = Layout.nextSeq();        // the latest hand-named level wins, as it does for a position
            Layout.applyText(w);
        }
    }

    /**
     * <b>Best-effort value for a control the addon did NOT build</b> ({@code widget:value()} on a borrowed
     * widget, 061.2) — the second half {@link #text(Widget)} has always had, and the same discipline: one
     * {@code instanceof} chain in one method, {@code nil} on a widget that holds nothing, never throwing, so
     * upstream churn breaks this method and nothing else.
     *
     * <p><b>A radio button answers its GROUP's row, not its own tick.</b> What a radio holds is <i>which row
     * is checked</i>, and the group that holds it is not a widget to point at — so the button is the address
     * and the row is the answer, which is also what its {@code "Changed"} says it is about to become.
     *
     * <p>A native list's row is an arbitrary Java object, so it goes through the one canonical marshal
     * ({@link LuaMarshal#toLua}): a plain value crosses as itself, anything else as an opaque handle that
     * still compares {@code ==} and can be handed straight back.
     */
    static LuaValue value(Widget w) {
        if(secret(w))
            return LuaValue.NIL;                   // audit2 B08 (pk-02): a credential is not a value either
        try {
            if(w instanceof RadioGroup.RadioButton) {
                String row = ((RadioGroup.RadioButton)w).checked();
                return (row == null) ? LuaValue.NIL : LuaValue.valueOf(row);
            }
            if(w instanceof ACheckBox)             // CheckBox and ICheckBox alike: what the tick says
                return LuaValue.valueOf(((ACheckBox)w).state());
            if(w instanceof HSlider)
                return LuaValue.valueOf(((HSlider)w).val);
            if(w instanceof Scrollbar)
                return LuaValue.valueOf(((Scrollbar)w).val);
            if(w instanceof TextEntry)
                return LuaValue.valueOf(((TextEntry)w).text());
            if(w instanceof SListWidget)           // a list, a dropbox and a menu's inner list: the picked row
                return LuaMarshal.toLua(((SListWidget<?, ?>)w).sel);
            if(w instanceof Progress)
                return LuaValue.valueOf(((Progress)w).fraction());
            // A kin/village colour row (BuddyWnd.GroupSelector) holds the GROUP it is showing, which is what
            // the highlighted square means -- and it holds it past the eight colours, where no square is
            // highlighted and the number is the only thing there is to read. -1 is the engine's own "nothing
            // selected" (the polity rows are built with it), and that is a nil here.
            if(w instanceof haven.BuddyWnd.GroupSelector) {
                int group = ((haven.BuddyWnd.GroupSelector)w).group;
                return (group < 0) ? LuaValue.NIL : LuaValue.valueOf(group);
            }
        } catch(RuntimeException e) {
            return LuaValue.NIL;   // a Supplier still Loading, say: a read answers nil rather than throwing
        }
        return LuaValue.NIL;
    }

    /**
     * Best-effort tooltip text ({@code widget:tooltip()}, 044.5) — the same shape and the same discipline as
     * {@link #text(Widget)}: one switch over the two things the client's {@code Widget.tooltip} field actually
     * holds as words (a plain {@link String}, and the {@link Widget.KeyboundTip} that {@code settip} builds,
     * whose {@code base} is the text before the keymap appends a shortcut to it). A rendered {@code Tex} is a
     * picture and has no text to give back, so it reads {@code nil} rather than a guess.
     */
    static String tip(Widget w) {
        Object t = (w == null) ? null : w.tooltip;
        if(t instanceof String)
            return (String)t;
        if(t instanceof Widget.KeyboundTip)
            return ((Widget.KeyboundTip)t).base;
        if(t instanceof Text)
            return ((Text)t).text;
        return null;
    }

    /**
     * <b>Is {@code w} on the client's focus path?</b> ({@code widget:focused()}, 044.5.) The keyboard is not
     * delivered to "the focused widget" but walked down from {@code ui.root} through a chain of focus
     * controllers — {@code Widget.FocusedKeyEvent.propagation}: a {@code focusctl} hands the event to its one
     * {@code focused} child, and anything else offers it to every VISIBLE child in turn. This mirrors that walk
     * exactly, so what it answers is the question worth asking — <i>would a keystroke reach this widget</i> —
     * rather than the raw {@code hasfocus} flag, which the client only maintains below a controller that has
     * focus itself and is therefore false on almost everything that is in fact typing.
     *
     * <p>A widget standing in the 3D world needs no special case here, and that is the point: its host surface
     * is an ordinary non-{@code focusctl} child of the root, so {@code setfocus} bubbles straight past it to the
     * root exactly as it does from a window on the flat UI, and the key comes back down the same chain.
     */
    static boolean focusPath(Widget w) {
        UI u = (w == null) ? null : w.ui;      // 074.1: the chain that would reach IT, in the tree it is in
        if((w == null) || (u == null) || (u.root == null))
            return false;
        synchronized(monitorOf(u)) {           // 112.2: the acquisition every site in this layer makes
            for(Widget p = u.root; p != null; p = p.focused) {
                if(p == w)
                    return true;
                if(!p.focusctl) {                  // the broadcast: every visible child is offered the key
                    for(Widget q = w; q != null; q = q.parent) {
                        if(q == p)
                            return true;
                        if(!q.visible())
                            return false;
                    }
                    return false;
                }
            }
        }
        return false;
    }

    /**
     * <b>Which widget's tooltip the client would show at a root-coord point</b> ({@code hafen.ui():tipAt(x, y)},
     * 044.5) — the client's own resolution, run on demand: the panel standing under that point answers first
     * (044.5's seam in {@link UI#tooltip}), and otherwise the ordinary walk from {@code ui.root}. Answers the
     * widget rather than the text so the two questions stay separate — {@code w:tooltip()} is the text, and a
     * tooltip that is a picture still has an owner worth naming.
     *
     * <p>The one difference from the tooltip actually on screen is {@code last}: the live query carries the
     * widget that answered on the previous frame, which a couple of the client's own widgets use to keep a
     * hovering tip stable. A question asked out of the blue has no previous frame, so it passes none.
     */
    static Widget tipAt(UI u, Coord c) {
        Widget.TooltipQuery q = new Widget.TooltipQuery(c, null);
        synchronized(monitorOf(u)) {           // 112.2: the acquisition every site in this layer makes
            if(!AddonManager.surfaceQuery(q, c))
                u.dispatch(u.root, q);
        }
        return q.from;
    }

    /**
     * A Widget snapshot — {@code widget:info()}, the escape hatch for logging/serialising:
     * {@code {type,role,res,id,pos,size,visible,text,owned}}. Expressed over the same accessors the methods use, so there is
     * one source of truth per field; an absent value is simply an unset key. {@code owned} is per-addon (029.2):
     * the same widget is {@code owned=true} for the addon that created it and {@code false} for every other one.
     */
    static LuaValue snapshot(Addon owner, Widget w) {
        if(w == null)
            return LuaValue.NIL;
        LuaTable t = new LuaTable();
        t.set("type", LuaValue.valueOf(typeName(w)));
        t.set("owned", LuaValue.valueOf(ownedContent(owner, w) != null));
        String rl = role(w);                       // 030.1: the selector vocabulary, absent when nothing classifies it
        if(rl != null)
            t.set("role", LuaValue.valueOf(rl));
        String rs = resName(w);
        if(rs != null)
            t.set("res", LuaValue.valueOf(rs));
        int id = w.wdgid();
        if(id >= 0)
            t.set("id", LuaValue.valueOf(id));
        if(w.c != null)
            t.set("pos", xyTable(Px.out(w.c)));      // the same design pixels :position()/:size() answer (058.1)
        if(w.sz != null)
            t.set("size", whTable(Px.out(w.sz)));
        t.set("visible", LuaValue.valueOf(w.visible()));
        String tx = text(w);
        if(tx != null)
            t.set("text", LuaValue.valueOf(tx));
        return t;
    }

    /**
     * Depth-first walk for {@code widget:walk(fn)} (spec 20, W1): call {@code fn(widget, depth)}, then — unless
     * {@code fn} returned exactly {@code false} (prune) — recurse into each child. Interned, so the callback gets
     * the SAME Lua value {@code :walk} was called on and every child is {@code ==}-comparable across calls. Each
     * callback runs isolated + watchdog-armed via {@link AddonManager#callLua}. A widget that goes stale mid-walk
     * simply stops descending.
     */
    private static void walk(Addon owner, LuaValue self, LuaValue fn, int depth) {
        Widget w = live(resolve(self));
        if(w == null)
            return;
        LuaValue r = AddonManager.callLua(owner, Addon.C_WIDGET, fn, self, LuaValue.valueOf(depth)).arg1();
        if(r.isboolean() && !Args.truthy(r))      // fn returned false → prune this subtree
            return;
        for(Widget c : kids(w))
            walk(owner, of(owner, c), fn, depth + 1);
    }

    // ---- W2 hit-testing + the small shared helpers --------------------------------------------------

    /**
     * The deepest widget under {@code c} (given in {@code from}'s local coords), for {@code hafen.ui():hit} /
     * {@code widget:at} (spec 20, W2). It <b>mirrors the engine's own pointer dispatch</b>
     * ({@link Widget.PointerEvent#propagation}, {@code Widget.java:981}): walk children {@code lchild → prev}
     * (topmost-first — the last child draws on top), skip {@code !visible()}, descend by
     * {@code from.xlate(child.c, true)} (so a scrolled {@code Scrollport} offsets correctly) + a rectangle
     * intersect, and at the leaf honour {@link Widget#checkhit(Coord)} (so a non-rectangular hit area resolves as a
     * real click would). Returns the deepest hit, {@code from} itself when the point is in its own hit area but no
     * child claims it, or {@code null} when the point misses {@code from} entirely. Must be called under the
     * {@code ui} monitor.
     */
    static Widget hitTest(Widget from, Coord c) {
        for(Widget wdg = from.lchild; wdg != null; wdg = wdg.prev) {
            if(!wdg.visible())
                continue;
            Coord cc = from.xlate(wdg.c, true);
            if((wdg.sz != null) && c.isect(cc, wdg.sz)) {
                Widget hit = hitTest(wdg, c.sub(cc));
                if(hit != null)
                    return hit;
            }
        }
        return from.checkhit(c) ? from : null;
    }

    /**
     * The two DESIGN-pixel numbers a geometry write takes, as the {@link Coord} it means. It goes through
     * {@link Args#integer} rather than LuaJ's {@code checkint}, which answers <i>bad argument: number expected,
     * got nil</i> and names neither the verb nor which of the two was missing — so {@code w:position(nil, 10)}
     * reads as the house nil refusal, and a number-shaped string is refused rather than coerced.
     */
    private static Coord pixels(Varargs a, String verb, String px, String py) {
        int x = Args.integer(a, 2, verb, px, "a design pixel");
        int y = Args.integer(a, 3, verb, py, "a design pixel");
        return Coord.of(x, y);
    }

    /**
     * A {@code {x=,y=}} table from a {@link Coord}, for {@code :position()}/{@code :size()} and every other pair
     * the API hands back. It converts <b>nothing</b>: a widget's geometry is put through {@link Px#out} by the
     * verb that reads it, and a sheet's own numbers were never anything but design pixels.
     */
    static LuaValue xyTable(Coord c) {
        LuaTable t = new LuaTable();
        t.set("x", LuaValue.valueOf(c.x));
        t.set("y", LuaValue.valueOf(c.y));
        return t;
    }

    /**
     * The metatable every {@code {w=,h=}} size table shares (085.3): built once, hung off each table with one
     * {@code setmetatable}, so a size costs two field writes and no allocation of its own. {@code .w} and
     * {@code .h} never reach it; {@code .x} and {@code .y} do, and say what a size is spelled now.
     */
    private static final LuaTable SIZE_META = shapeMeta("size");

    /**
     * A {@code {w=,h=}} table from a {@link Coord} that is a <b>size</b> — {@code widget:size()},
     * {@code widget:info().size}, {@code rule:size()} and the stylesheet snapshots — the twin of
     * {@link #xyTable}, which keeps every position, offset, anchor and {@code :rootPos()}. It converts
     * <b>nothing</b>, for the same reason {@link #xyTable} does not.
     *
     * <p>{@code .x} and {@code .y} on one of these <b>raise</b> naming {@code .w}/{@code .h} rather than
     * reading {@code nil}: {@link Refusal#closedFields} is what turns a silent {@code nil} into a line
     * naming what the shape carries.
     */
    static LuaValue whTable(Coord c) {
        LuaTable t = new LuaTable();
        t.set("w", LuaValue.valueOf(c.x));
        t.set("h", LuaValue.valueOf(c.y));
        t.setmetatable(SIZE_META);
        return t;
    }

    /** One shared metatable for an anonymous shape: {@link Refusal#closedFields} under {@code __index}. */
    static LuaTable shapeMeta(String shape) {
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, Refusal.closedFields(shape));
        return mt;
    }

    /** A {@code {x=,y=,w=,h=}} table from a device-pixel box, converted on the way out — see {@link #xyTable}. */
    private static LuaValue boxTable(Coord ul, Coord sz) {
        Coord o = Px.out(ul), s = Px.out(sz);
        LuaTable t = new LuaTable();
        t.set("x", LuaValue.valueOf(o.x));
        t.set("y", LuaValue.valueOf(o.y));
        t.set("w", LuaValue.valueOf(s.x));
        t.set("h", LuaValue.valueOf(s.y));
        return t;
    }

    /**
     * {@code window:chrome()} (065.4) — the boxes a window's <b>decoration</b> actually drew its ornaments at,
     * measured from the window's own outer top-left, or {@code nil} for anything that is not a window wearing
     * the client's own decoration.
     *
     * <p>It reads the deco rather than the rule, which is the point: a {@code caption} or a {@code sizer} spot
     * is what a theme <i>asked</i> for, and this is where the ornament went — the client's own constant when no
     * rule names it, and the caption plate's box whether or not one does, since the plate is sized by the client
     * from the caption's own width and a theme only says what it looks like.
     *
     * <p>A field is present only once its ornament has been drawn at least once: the plate's box is computed
     * when a window first renders its caption, so a window built this instant answers without one.
     */
    private static LuaValue chromeTable(Widget w) {
        if(!(w instanceof Window))
            return LuaValue.NIL;
        Window wnd = (Window)w;
        Window.Deco d;
        synchronized(monitor(wnd)) { d = wnd.deco; }
        if(!(d instanceof Window.DefaultDeco))
            return LuaValue.NIL;              // a window that built a decoration of its own: not ours to read
        Window.DefaultDeco dd = (Window.DefaultDeco)d;
        LuaTable t = new LuaTable();
        if(dd.cap != null)
            t.set("caption", xyTable(Px.out(dd.capc())));
        if((dd.plsz.x > 0) && (dd.plsz.y > 0)) {
            LuaValue pl = boxTable(Coord.z, dd.plsz);
            // ...and whether the last frame painted it from a RULE rather than from the client's own art. The
            // box is the client's either way, which is the whole division of labour; this is the other half.
            pl.set("styled", LuaValue.valueOf(dd.platestyled));
            t.set("plate", pl);
        }
        if(dd.dragsize)
            t.set("sizer", xyTable(Px.out(dd.sizerc())));
        if(dd.cbtn != null)
            t.set("close", boxTable(dd.cbtn.c, dd.cbtn.sz));
        return t;
    }

    /** Parse a Lua {@code {x=,y=}} table into a {@link Coord} (root coords for W2); a clear error otherwise. */
    static Coord coordArg(LuaValue v, String where) {
        if(!v.istable())
            throw new LuaError(where + " expects a {x=,y=} coord table");
        LuaValue x = v.get("x"), y = v.get("y");
        if(x.isnil() || y.isnil())
            throw new LuaError(where + " expects a {x=,y=} coord table");
        return new Coord(Args.integer(x, where, "x", "a design pixel"),
                         Args.integer(y, where, "y", "a design pixel"));
    }
}
