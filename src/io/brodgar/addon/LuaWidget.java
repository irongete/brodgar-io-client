package io.brodgar.addon;

import haven.Button;
import haven.ChatUI;
import haven.CheckBox;
import haven.Coord;
import haven.Equipory;
import haven.FlowerMenu;
import haven.FromResource;
import haven.GItem;
import haven.IButton;
import haven.IMeter;
import haven.Inventory;
import haven.Label;
import haven.MenuGrid;
import haven.Resource;
import haven.Text;
import haven.TextEntry;
import haven.UI;
import haven.WItem;
import haven.Widget;
import haven.Window;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.VarArgFunction;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.IdentityHashMap;
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
 * value ({@code hafen.ui():at(m.x,m.y) == hafen.ui():at(m.x,m.y)}), and a widget kept across frames stays {@code ==}.
 * That is what let {@code node:same(other)} be <b>hard cut</b> (D-012/D-013): it only ever existed because nothing
 * was interned.
 *
 * <p><b>The intern cache is weak on BOTH sides</b> — {@code WeakHashMap<Widget, WeakReference<LuaValue>>} — and
 * that is the one deliberate deviation from the prior art. {@link LuaMeter}/{@link LuaBuff} key an
 * {@code IdentityHashMap} <i>strongly</i> and drain a {@link java.lang.ref.ReferenceQueue}; over a handful of
 * meters that is bounded, but over a <b>widget tree</b> a strong key would pin every destroyed widget until the
 * next drain — breaking the D-041 no-pin rule this type implements on purpose. {@code haven.Widget} overrides
 * neither {@code equals} nor {@code hashCode}, so {@code WeakHashMap} gives <b>identity</b> keying <i>and</i> weak
 * keys with no custom map. The map value is a {@link WeakReference}, so it never strongly reaches its own key (the
 * classic {@code WeakHashMap} self-reference leak); a value cleared while its widget is still alive is simply
 * replaced on the next lookup.
 *
 * <p><b>Owned vs borrowed (029.2).</b> The same type covers a widget the addon <i>created</i>
 * ({@code hafen.ui():window()}/{@code :widget()} — OWNED) and one it merely <i>found</i> (a native widget, or another
 * addon's — BORROWED). Every read answers on both, and so do the visibility write ({@code :hide()}/{@code :show()})
 * and — since 036.1, feature E — the geometry writes ({@code :pos(x,y)}/{@code :size(w,h)}); {@code :pack()} and
 * {@code :destroy()} stay OWNED-only and raise a clear error otherwise, because destroying the client's own widget
 * is not the addon's to do. Provenance is <b>derived from the tree</b> ({@link #ownedContent}), never stored on the
 * handle, because the cache below may collect and re-mint an entity at any moment. A write on a BORROWED widget
 * records what it was first — {@code :hide()} on {@link Addon#hiddenNative}, a move or a resize on
 * {@link Addon#movedNative} — and teardown gives it back; that, and not a separate handle type, is what
 * {@code hafen.ui.adopt} used to be for.
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
    static LuaValue of(Addon owner, Widget w) {
        return owner.widgetObjs.of(w);
    }

    /** The {@code LuaWidget} behind a Lua value, or {@code null} for anything that is not a Widget object. */
    static LuaWidget resolve(LuaValue v) {
        if((v == null) || !v.isuserdata())
            return null;
        Object o = v.touserdata();
        return (o instanceof LuaWidget) ? (LuaWidget)o : null;
    }

    // ---- the per-addon intern cache + metatable ---------------------------------------------------

    /**
     * One addon's Widget interning cache and metatable (its {@link Addon#widgetObjs}). Weak keys <b>and</b> weak
     * values — see the class comment for why this one does not use the {@link LuaMeter} {@code IdentityHashMap} +
     * {@link java.lang.ref.ReferenceQueue} shape. The metatable is built once, lazily.
     */
    static final class Cache {
        private final Addon owner;
        private final Map<Widget, WeakReference<LuaValue>> live =
            new WeakHashMap<Widget, WeakReference<LuaValue>>();
        private LuaValue mt;

        Cache(Addon owner) {
            this.owner = owner;
        }

        /** The interned handle for {@code w} — a cache hit, or a freshly minted (and inserted) one. */
        synchronized LuaValue of(Widget w) {
            if(w == null)
                return LuaValue.NIL;
            WeakReference<LuaValue> r = live.get(w);
            if(r != null) {
                LuaValue v = r.get();
                if(v != null)
                    return v;
            }
            LuaValue v = LuaValue.userdataOf(new LuaWidget(w), meta());
            live.put(w, new WeakReference<LuaValue>(v));
            return v;
        }

        private LuaValue meta() {
            if(mt == null)
                mt = buildMeta(owner);
            return mt;
        }

        /**
         * <b>Re-point this addon's handle from one widget to the widget that replaced it</b> — the face setter's
         * rebuild (040.2, {@link UiApi#rebuild}), and the one thing that may ever move an entry in this map.
         *
         * <p>It is the {@link #wdg} field that matters: {@code hafen.ui():button()} handed a userdata to Lua, the
         * author is chaining setters onto it, and the widget under it is being swapped mid-statement. Re-pointing
         * the field keeps that value <i>the same object</i>, so {@code ==} still holds and the very next verb in
         * the chain addresses the new widget; moving the map entry keeps the intern promise, so a fresh lookup of
         * the new widget through any other door hands back that same value rather than minting a second one.
         *
         * <p>A collected (or never-minted) entry is nothing to move: the next lookup mints one on the new widget,
         * which is the same answer. Other addons' caches are deliberately untouched — one of them holding the old
         * widget sees it go stale, which is exactly what happened to it.
         */
        synchronized void rekey(Widget from, Widget to) {
            WeakReference<LuaValue> r = live.remove(from);
            LuaValue v = (r == null) ? null : r.get();
            if(v == null)
                return;
            LuaWidget h = resolve(v);
            if(h != null)
                h.wdg = to;
            live.put(to, r);
        }
    }

    // ---- the Widget metatable ----------------------------------------------------------------------

    /**
     * The per-addon metatable: {@code __index} = the methods table <b>through {@link Retired#methodIndex}</b>,
     * plus {@code __tostring}/{@code __name}. The indirection is what makes a retired verb ({@code w:pos},
     * {@code w:show}) throw naming its replacement instead of reading as plain {@code nil} and failing one line
     * later as "attempt to call a nil value" — pointing {@code __index} straight at the methods table is the
     * mistake that hid the cut on two earlier entities.
     */
    private static LuaValue buildMeta(Addon owner) {
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, Retired.methodIndex("widget", methods(owner)));
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
                LuaTable out = new LuaTable();
                Widget w = live(handle(self, "children"));
                if(w != null) {
                    int i = 0;
                    for(Widget c : kids(w))
                        out.set(++i, of(owner, c));
                }
                return out;
            }
        });
        // parent() / parent(w) — the enclosing Widget object, and (039.6) the builder setter that chooses it.
        // The read is unchanged: nil at the root and once stale. The write re-homes a surface this addon built,
        // and it is what replaced the old `parent = "gameui"` string — a widget is named by a Widget, not by a
        // word, which is the second vocabulary 032.2 deleted from this section for exactly the same reason.
        // hafen.ui():root() is the default and hafen.ui():find("@GameUI") is the HUD.
        //
        // Legal only while the surface is still being BUILT (before its arming tick). Re-homing one the user is
        // already looking at is a capability this API never had, and the honest place to refuse it is here: the
        // message names widget:position(x, y), which is what moving a window on screen has always been.
        m.set("parent", new VarArgFunction() {
            public Varargs invoke(Varargs a) {            // w:parent() → narg 1 · w:parent(p) → narg 2
                LuaValue self = a.arg1();
                Widget w = live(handle(self, "parent"));
                LuaValue v = Args.written(a, 2, "widget:parent", "w");
                if(v == null) {
                    Widget p = (w == null) ? null : w.parent;
                    return (p == null) ? LuaValue.NIL : of(owner, p);
                }
                if(w == null)                             // a write on a stale widget: the 029.2 chaining no-op
                    return self;
                Owned c = owned(owner, w, "parent(w)");
                if(!c.pending())
                    throw new LuaError("widget:parent(w) chooses the parent while the widget is being BUILT, and"
                        + " this one is already on screen — move it with widget:position(x, y) instead");
                LuaWidget h = resolve(v);
                Widget p = (h == null) ? null : live(h);
                if(p == null)
                    throw new LuaError("widget:parent(w) expects a Widget that is in the tree — hafen.ui():root()"
                        + " is the default, and hafen.ui():find(\"@GameUI\") is the HUD");
                if(p == w.parent)
                    return self;
                UI u = AddonManager.ui;
                synchronized(u) {
                    Coord at = w.c;
                    w.remove();                           // unlink from ui.root; nothing else holds a fresh widget
                    p.add(w, at);                         // ...and re-home it, keeping the place it was given
                }
                return self;
            }
        });
        // position() / position(x, y) / position(nil) — ARITY IS THE VERB (the 018 options shape, 029.2; the same
        // three arities as :size): no args READS the position within the parent as {x=,y=} (widget-local px), two
        // numbers MOVE the widget, and nil DROPS your move and puts back what the widget was at before you first
        // touched it. Both writes chain. :move() is hard cut.
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
        // position() — PIXELS within the parent, and deliberately NOT a Position (spec 039 §2.7): the verb asks
        // "where is this thing, in the space it lives in", and a widget lives on the screen. Now that a place in
        // the world is a TYPE, handing this to hafen.act():moveTo throws instead of walking you somewhere wrong.
        m.set("position", new VarArgFunction() {
            public Varargs invoke(Varargs a) {            // :position() → narg 1 · (nil) → narg 2 · (x,y) → narg 3
                LuaValue self = a.arg1();
                Widget w = live(handle(self, "position"));
                if(a.narg() < 2)
                    return ((w == null) || (w.c == null)) ? LuaValue.NIL : xyTable(w.c);
                if(a.narg() < 3) {                        // w:position(nil) — undo OUR move, back to the stock value
                    if(!a.arg(2).isnil())                 // w:position(x) is a mistake, not an undo
                        throw new LuaError("widget:position(x, y) takes BOTH coordinates; widget:position() reads"
                            + " the position and widget:position(nil) drops your addon's move and restores the"
                            + " stock one");
                    if(w != null)                         // a stale widget: the 029.2 silent chaining no-op
                        UiApi.releaseMoved(owner, w, true);
                    return self;
                }
                Coord to = Coord.of(a.checkint(2), a.checkint(3));
                if(w != null) {
                    UI u = AddonManager.ui;
                    synchronized(u) {
                        if(ownedContent(owner, w) == null) {
                            Moved rec = recordMoved(owner, w);     // BORROWED: name the level, then resolve it
                            rec.wantPos = Layout.Anchor.at(to);    // 036.3: the parent's top-left, plus (x, y)
                            rec.posSeq = Layout.nextSeq();
                            Layout.apply(w);
                        } else {
                            w.move(to);                            // your own widget: no layer, no cascade...
                            Layout.moved(w);                       // ...but an anchor may still hang off it (036.3)
                        }
                    }
                }
                return self;
            }
        });
        // size() / size(w, h) / size(nil) — same three arities. The write resizes the CONTENT and repacks the
        // chrome around it (so a window's frame follows), which is why what :size() reads back on a window is the
        // outer box and not the pair you passed; the undo restores that outer box exactly (LuaWidget.sizeArg).
        m.set("size", new VarArgFunction() {
            public Varargs invoke(Varargs a) {            // w:size() → narg 1 · w:size(nil) → narg 2 · w:size(w,h) → narg 3
                LuaValue self = a.arg1();
                Widget w = live(handle(self, "size"));
                if(a.narg() < 2)
                    return ((w == null) || (w.sz == null)) ? LuaValue.NIL : xyTable(w.sz);
                if(a.narg() < 3) {                        // w:size(nil) — undo OUR resize, back to the stock value
                    if(!a.arg(2).isnil())
                        throw new LuaError("widget:size(w, h) takes BOTH dimensions; widget:size() reads the size"
                            + " and widget:size(nil) drops your addon's resize and restores the stock one");
                    if(w != null)
                        UiApi.releaseMoved(owner, w, false);
                    return self;
                }
                Coord to = Coord.of(a.checkint(2), a.checkint(3));
                if(w != null) {
                    Owned content = ownedContent(owner, w);
                    UI u = AddonManager.ui;
                    synchronized(u) {
                        if(content == null) {             // BORROWED (036.1): the layer remembers, then resizes
                            Moved rec = recordMoved(owner, w);
                            rec.wantSize = to;            // 036.2: ...and the resize is the cascade's to make
                            rec.sizeSeq = Layout.nextSeq();
                            Layout.apply(w);
                        } else {
                            content.widget().resize(to);
                            if(content.widget() != w)     // a window: refit the chrome around the resized content
                                w.pack();
                            Layout.moved(w);              // 036.3: a corner anchor reads the box that just changed
                        }
                    }
                }
                return self;
            }
        });
        // visible() / visible(b) — A BOOLEAN PROPERTY IS A PROPERTY (spec 039 §2.2, R6): the read says whether it is
        // currently drawn (false once stale) and the write says what it should be. :show() and :hide() are a HARD
        // CUT — two spellings for one write is the dual style the grammar removes, and they were the last pair in
        // the API where the value lived in the verb's NAME instead of its argument.
        //
        // It is the ONLY write that answers on a native widget (029.2). Hiding a NATIVE widget registers it on the
        // addon's restore list, so :reload/disable puts it back exactly as it was (UiApi.teardownHidden) — that is
        // what replaces hafen.ui.adopt, which used to hide a window just so you could read it. A hidden server
        // widget stays bound to its id (still receiving uimsg/addchild), so it remains a perfectly live model.
        // visible(true) gives it back and drops the record. The write chains; visible(nil) is refused (§2.9 — there
        // is nothing here to undo, and a nil that silently became a READ is the bug that rule exists for).
        m.set("visible", new VarArgFunction() {
            public Varargs invoke(Varargs a) {            // w:visible() → narg 1 · w:visible(b) → narg 2
                LuaValue self = a.arg1();
                Widget w = live(handle(self, "visible"));
                LuaValue v = Args.written(a, 2, "widget:visible", "b");
                if(v == null)
                    return LuaValue.valueOf((w != null) && w.visible());
                if(w == null)                             // a write on a stale widget: the 029.2 chaining no-op
                    return self;
                if(v.toboolean()) {
                    UI u = AddonManager.ui;
                    synchronized(u) { w.show(); }
                    dropHidden(owner, w);                 // restored by hand: teardown has nothing left to undo
                } else {
                    boolean borrowed = (ownedContent(owner, w) == null);
                    if(borrowed)
                        refuseSecondOwner(owner, w, "widget:visible(false)");   // 031.2: one window, one owner
                    UI u = AddonManager.ui;
                    synchronized(u) { w.hide(); }
                    if(borrowed)                          // BORROWED: remember to give it back on teardown
                        recordHidden(owner, w);
                }
                return self;
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
        // WAITING IS NOT PART OF IT: hafen.ui():on(selector, "appear", fn) already waits, and already fires for what
        // is ALREADY open (D-068) — so the whole pattern is
        //     hafen.ui():on("inventory[title=Inventory]", "appear", function(w) w:replace(buildMyView(w)) end)
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
        // pack() — shrink the chrome to fit its content. OWNED-only; a no-op for a bare hafen.ui():widget() (a leaf has
        // no children to fit). Chains.
        m.set("pack", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = a.arg1();
                Widget w = live(handle(self, "pack"));
                if(w != null) {
                    Owned content = owned(owner, w, "pack()");
                    if(content.widget() != w) {
                        UI u = AddonManager.ui;
                        synchronized(u) { w.pack(); }
                    }
                }
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
                    UI u = AddonManager.ui;
                    synchronized(u) { content.kill(); }
                    UiApi.dropPending(content);   // 039.6: one built and ended in the same statement is never placed
                    owner.widgets.remove(content);
                }
                return LuaValue.NIL;
            }
        });
        // ---- the builder setters (039.6, spec 039-uniform-api §2.5) -----------------------------------------
        // The thirteen keys of the retired opts table, as verbs on the widget the builder handed back — each
        // with a matching bare read, so a surface's properties are readable after construction with no second
        // vocabulary. :position/:size/:parent are above (they already existed as reads); the rest are here.
        //
        // All of them are OWNED-only, and the reason is not symmetry: a caption, a default font and eight Lua
        // callbacks are things an AddonWidget HAS, and a native widget has nowhere to put them. The reads
        // answer nil on a borrowed widget rather than throwing, which is what every other read here does.
        //
        // title() / title(s) — a window's caption. A bare :widget() has no chrome to write it on, so the write
        // refuses naming the builder that does; the read answers nil there.
        m.set("title", new VarArgFunction() {
            public Varargs invoke(Varargs a) {            // w:title() → narg 1 · w:title(s) → narg 2
                LuaValue self = a.arg1();
                Widget w = live(handle(self, "title"));
                LuaValue v = Args.written(a, 2, "widget:title", "s");
                if(v == null) {
                    if((w == null) || !(w instanceof Window))
                        return LuaValue.NIL;
                    String cap = ((Window)w).cap;
                    return (cap == null) ? LuaValue.NIL : LuaValue.valueOf(cap);
                }
                if(w == null)                             // a write on a stale widget: the 029.2 chaining no-op
                    return self;
                owned(owner, w, "title(s)");
                if(!(w instanceof Window))
                    throw new LuaError("widget:title(s) is a WINDOW's caption, and " + typeName(w) + " has no"
                        + " chrome to write it on — hafen.ui():window() is the builder that does. A control's"
                        + " own caption is widget:text(s).");
                UI u = AddonManager.ui;
                synchronized(u) { ((Window)w).chcap(v.tojstring()); }
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
        // The eight callback setters, one loop rather than eight blocks: they differ only in which slot they
        // write, and AddonWidget.CALLBACKS is the single list of the names (which are also the argument names
        // the docs use). onDraw(fn) / onDraw() — arity is the verb here too, so a callback reads back.
        for(int i = 0; i < AddonWidget.CALLBACKS.length; i++) {
            final int slot = i;
            final String verb = AddonWidget.CALLBACKS[i];
            m.set(verb, new VarArgFunction() {
                public Varargs invoke(Varargs a) {
                    LuaValue self = a.arg1();
                    Widget w = live(handle(self, verb));
                    LuaValue v = Args.written(a, 2, "widget:" + verb, "fn");
                    AddonWidget c = surfaceOrNull(owner, w);
                    if(v == null) {
                        LuaValue fn = (c == null) ? null : c.callback(slot);
                        return (fn == null) ? LuaValue.NIL : fn;
                    }
                    if(w == null)                         // a write on a stale widget: the 029.2 chaining no-op
                        return self;
                    if(!v.isfunction())
                        throw new LuaError("widget:" + verb + "(fn) expects a function, got " + v.typename());
                    surface(owner, w, verb + "(fn)").callback(slot, v);
                    return self;
                }
            });
        }
        // items() — 029.3: the items INSIDE this widget, as an array of Item OBJECTS. A RELATION on the
        // container, exactly like :children() — an Inventory (the backpack, a chest, a cupboard), an Equipory
        // (whose worn items say which slots they fill), or any widget with WItems under it (children(WItem.class)
        // is a DEEP traversal, so a whole window answers for its grid). Each item appears ONCE however many slots
        // it occupies. Read with the window VISIBLE and interactive: nothing is hidden, nothing is registered —
        // which is the whole point of deleting hafen.ui.adopt. Empty for a leaf, a non-container or a stale
        // widget. Read-only: MOVING items is the gated hafen.act tier.
        m.set("items", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Widget w = live(handle(self, "items"));
                return (w == null) ? new LuaTable() : items(owner, w);
            }
        });
        // onItemAdded(fn) / onItemRemoved(fn) / onDestroy(fn) — 029.3: the lifecycle of a container, on the entity
        // itself. fn(item) gets the same Item object :items() produces; onDestroy takes no argument and fires
        // once, when the widget leaves the tree (server-destroyed, window closed, relog). An item add/remove is a
        // WItem create/cdestroy and NOT a uimsg, so these are a per-tick diff (the BuffsAdapter shape) — but the
        // poll is hasSub-GATED: the subscription IS the registration, so a widget nobody subscribed to is never
        // polled, and passing nil (or anything not a function) unsubscribes. Drop the last callback and the widget
        // leaves the poll entirely. All three chain on self. NB the items already inside a container fire
        // onItemAdded on the first poll after you subscribe — the state arrives as events, like BuffAdded.
        m.set("onItemAdded", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                UiApi.setItemCallback(owner, live(handle(a.arg1(), "onItemAdded")), Watch.ADDED, a.arg(2));
                return a.arg1();
            }
        });
        m.set("onItemRemoved", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                UiApi.setItemCallback(owner, live(handle(a.arg1(), "onItemRemoved")), Watch.REMOVED, a.arg(2));
                return a.arg1();
            }
        });
        m.set("onDestroy", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                UiApi.setItemCallback(owner, live(handle(a.arg1(), "onDestroy")), Watch.DESTROYED, a.arg(2));
                return a.arg1();
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
        //   040.7: entry:text(s) — the WRITE only — is retired, throwing and naming :value(s): a text entry's
        // content has exactly one door to WRITE it through. The read stays exactly as above (best-effort,
        // never throwing) — retiring it too would have broken the very contract this comment documents for
        // every OTHER widget, on the one type this feature happens to touch.
        m.set("text", new VarArgFunction() {
            public Varargs invoke(Varargs a) {            // w:text() → narg 1 · w:text(s) → narg 2
                LuaValue self = a.arg1();
                Widget w = live(handle(self, "text"));
                LuaValue v = Args.written(a, 2, "widget:text", "s");
                if(v == null) {
                    if(w == null)
                        return LuaValue.NIL;
                    String t = text(w);
                    return (t == null) ? LuaValue.NIL : LuaValue.valueOf(t);
                }
                if(w == null)                             // a write on a stale widget: the 029.2 chaining no-op
                    return self;
                if(w instanceof CEntry)
                    throw new LuaError(Retired.message("entry:text"));
                Controls.text(owned(owner, w, "text(s)"), w, v.tojstring());
                return self;
            }
        });
        // onPress(fn) / onPress() — 040.1: A BUTTON FIRED, and it holds nothing. Distinct from :onClick(fn) on
        // purpose: :onClick is the raw mouse event every widget you built carries (x, y, button, mods), while
        // :onPress is the ACTIVATION, which the keyboard raises too. Reads nil on anything with nothing to
        // press; a write there throws naming the builder that has one.
        m.set("onPress", new VarArgFunction() {
            public Varargs invoke(Varargs a) {            // w:onPress() → narg 1 · w:onPress(fn) → narg 2
                LuaValue self = a.arg1();
                Widget w = live(handle(self, "onPress"));
                LuaValue v = Args.written(a, 2, "widget:onPress", "fn");
                if(v == null)
                    return Controls.onPress((w == null) ? null : ownedContent(owner, w));
                if(w == null)                             // a write on a stale widget: the 029.2 chaining no-op
                    return self;
                if(!v.isfunction())
                    throw new LuaError("widget:onPress(fn) expects a function, got " + v.typename());
                Controls.onPress(owned(owner, w, "onPress(fn)"), w, v);
                return self;
            }
        });
        // onChange(fn) / onChange() — 040.4: THE VALUE CHANGED, and only from a real interaction. The other half
        // of the value spine :value()/:value(v) began in 040.3 -- every control that answers :value() answers
        // this too (spec 040 §1's sixth name), and a programmatic :value(v) writes the implementation's field
        // directly and never re-enters it, which is the whole feedback-loop guarantee the spine promises. Reads
        // nil on a control with no value; a write there throws naming that.
        m.set("onChange", new VarArgFunction() {
            public Varargs invoke(Varargs a) {            // w:onChange() → narg 1 · w:onChange(fn) → narg 2
                LuaValue self = a.arg1();
                Widget w = live(handle(self, "onChange"));
                LuaValue v = Args.written(a, 2, "widget:onChange", "fn");
                if(v == null)
                    return Controls.onChange((w == null) ? null : ownedContent(owner, w));
                if(w == null)                             // a write on a stale widget: the 029.2 chaining no-op
                    return self;
                if(!v.isfunction())
                    throw new LuaError("widget:onChange(fn) expects a function, got " + v.typename());
                Controls.onChange(owned(owner, w, "onChange(fn)"), w, v);
                return self;
            }
        });
        // onSubmit(fn) / onSubmit() — 040.7: the ENTRY's Enter, distinct from :onChange(fn) on purpose (spec 040
        // §1's :onSubmit(fn)) -- :onChange fires on every keystroke, :onSubmit once, when Enter is pressed. Reads
        // nil on anything that has nothing to submit; a write there throws naming the builder that does.
        m.set("onSubmit", new VarArgFunction() {
            public Varargs invoke(Varargs a) {            // w:onSubmit() → narg 1 · w:onSubmit(fn) → narg 2
                LuaValue self = a.arg1();
                Widget w = live(handle(self, "onSubmit"));
                LuaValue v = Args.written(a, 2, "widget:onSubmit", "fn");
                if(v == null)
                    return Controls.onSubmit((w == null) ? null : ownedContent(owner, w));
                if(w == null)                             // a write on a stale widget: the 029.2 chaining no-op
                    return self;
                if(!v.isfunction())
                    throw new LuaError("widget:onSubmit(fn) expects a function, got " + v.typename());
                Controls.onSubmit(owned(owner, w, "onSubmit(fn)"), w, v);
                return self;
            }
        });
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
        m.set("value", new VarArgFunction() {
            public Varargs invoke(Varargs a) {            // w:value() → narg 1 · w:value(v) → narg 2
                LuaValue self = a.arg1();
                Widget w = live(handle(self, "value"));
                LuaValue v = Args.written(a, 2, "widget:value", "v");
                if(v == null)
                    return Controls.value((w == null) ? null : ownedContent(owner, w));
                if(w == null)                             // a write on a stale widget: the 029.2 chaining no-op
                    return self;
                Controls.value(owned(owner, w, "value(v)"), w, v);
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
        // at(coord) — W2: the DEEPEST Widget object under a {x=,y=} ROOT-coord point within this subtree, or nil.
        m.set("at", new VarArgFunction() {
            public Varargs invoke(Varargs a) {            // w:at(coord) → self=arg1, coord=arg2 (root coords)
                Widget w = live(handle(a.arg1(), "at"));
                if(w == null)
                    return LuaValue.NIL;
                Coord pt = coordArg(a.arg(2), "widget:at(coord)");
                UI u = AddonManager.ui;
                Widget hit;
                synchronized(u) { hit = hitTest(w, w.rootxlate(pt)); }
                return (hit == null) ? LuaValue.NIL : of(owner, hit);
            }
        });
        // rootPos() — W2: {x=,y=} this widget's top-left in root coords (with :size() = a highlight box). PIXELS,
        // like :position() and for the same reason: a widget's place is on the screen (spec 039 §2.7).
        m.set("rootPos", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Widget w = live(handle(self, "rootPos"));
                if(w == null)
                    return LuaValue.NIL;
                UI u = AddonManager.ui;
                Coord rp;
                synchronized(u) { rp = w.rootpos(); }
                return (rp == null) ? LuaValue.NIL : xyTable(rp);
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
        // object a sheet's selectors do, so the properties are said the same way — r:font(h), r:color(r,g,b),
        // r:bg{…}, r:border{…}, r:pad(n), each with a bare read — and r:info() is the whole level as a table.
        // The undo is r:remove() (R7), and it drops only this addon's level, never another's. The layout three
        // are refused here naming widget:position(x, y): the hand-named level of THAT cascade is the verb.
        // widget:setFont/:resetFont are a HARD CUT: a font was never a special case, only the first property that
        // existed. Owner-tagged (reverted on :reload/disable) and held weakly against the widget, so it dies with
        // the window; on a stale widget the reads answer nil and a write is the 029.2 silent no-op.
        m.set("rule", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaWidget h = handle(self, "rule");
                return LuaRule.ofWidget(owner, h, live(h));
            }
        });
        return m;
    }

    /** The handle behind a method's {@code self}, or a guiding error (a dot-call passes the wrong self). */
    private static LuaWidget handle(LuaValue self, String method) {
        LuaWidget h = resolve(self);
        if(h == null)
            throw new LuaError("widget:" + method + "() — use a COLON call on a Widget object"
                + " (hafen.ui():root(), hafen.ui():find(selector), hafen.ui():node(id), hafen.ui():at(x, y))");
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
                + " A button's activation is widget:onPress(fn), and a control's look comes from the stylesheet:"
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
     * Does <b>any</b> live owner hold a hidden-native record right now? The global-empty fast path for the 031
     * window-toggle seam ({@link UiApi#toggleWnd}/{@link UiApi#wndState}), which the client polls <i>per frame,
     * per menu checkbox</i> — six of them. A plain volatile read is the whole cost for a client that hides
     * nothing, which is every client until an addon calls {@code w:hide()} on a native widget.
     *
     * <p>Maintained by {@link #recountHidden()} at the four places a restore list changes (hide, show,
     * {@link UiApi#teardownHidden}, {@link UiApi#resetSession}). A stale <i>true</i> costs only the walk, which
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
        UI u = AddonManager.ui;
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
     * <p>{@link #id} is the server widget id ({@code -1} for a client-only widget), so the restore can use the
     * same two-branch death test the hide records use ({@link UiApi#stillMovable}).
     */
    static final class Moved {
        final Addon owner;
        final Widget wdg;
        final int id;
        Coord pos;       // the stock c   — null: this addon's layer is not standing on the position
        Coord size;      // the stock size ARGUMENT (a Window's content size) — null: nor on the size
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
        /** This addon's hand-named size — see {@link #wantPos}. */
        Coord wantSize;
        /** When each half was named, so the latest hand-named level wins between two addons ({@link Layout#nextSeq}). */
        long posSeq, sizeSeq;

        Moved(Addon owner, Widget wdg, int id) {
            this.owner = owner;
            this.wdg = wdg;
            this.id = id;
        }

        /** Nothing of ours left on this widget ⇒ the record is dropped. */
        boolean idle() {
            return (pos == null) && (size == null) && (wantPos == null) && (wantSize == null);
        }
    }

    /**
     * Does <b>any</b> live owner hold a moved-native record right now? The global-empty fast path for the 036.1
     * persistence seam ({@link UiApi#stockPos}/{@link UiApi#stockSizeArg}), which {@code GameUI.savewndpos} asks
     * for six windows every 60 s and again at logout. A plain volatile read is the whole cost for a client no
     * addon has laid out — which is every client until one calls {@code w:position(x,y)} on a native widget.
     *
     * <p>Maintained exactly like {@link #anyHidden}: recomputed at the places a layout list changes (the two
     * verbs, their {@code nil} undo, {@link UiApi#teardownMoved}, {@link UiApi#resetSession}). A stale
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
        UI u = AddonManager.ui;
        m = new Moved(owner, w, (u == null) ? -1 : u.widgetid(w));
        owner.movedNative.add(m);
        anyMoved = true;
        return m;
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
     * Drop the records of widgets that have left the tree (036.2, from {@link Layout#poll}). Nothing is restored —
     * there is nothing left to restore it to — but the record would otherwise outlive its widget and, worse, hold a
     * strong reference to it: a rule that names a kind of window records one per window that opens.
     */
    static void pruneMoved(UI u) {
        boolean gone = false;
        List<Addon> as = AddonManager.addons;
        for(int i = 0, n = as.size(); i < n; i++)
            gone |= pruneMovedIn(as.get(i), u);
        gone |= pruneMovedIn(AddonManager.consoleOwner, u);
        if(gone)
            recountMoved();
    }

    private static boolean pruneMovedIn(Addon a, UI u) {
        if((a == null) || a.movedNative.isEmpty())
            return false;
        boolean gone = false;
        for(Moved m : a.movedNative) {
            if(Layout.alive(u, m.wdg, m.id))
                continue;
            a.movedNative.remove(m);
            gone = true;
        }
        return gone;
    }

    // ---- items: the relation + the per-tick subscription (029.3) ------------------------------------

    /**
     * One addon's subscription to a container's item lifecycle ({@code :onItemAdded}/{@code :onItemRemoved}/
     * {@code :onDestroy}) — the record {@link UiApi#pollWatches} diffs each tick. It exists <b>only while at least
     * one callback is set</b> (that is the {@code hasSub} gate: no subscription, no record, no poll), so the strong
     * {@link #wdg} reference here is a deliberate, explicit one — an addon asked to be told about this widget — and
     * it is dropped on the first tick that finds the widget gone, right after {@code onDestroy} fires.
     *
     * <p>{@link #id} is the server widget id captured at subscription time ({@code -1} for a client-only widget):
     * the death test is the same two-branch guard the restore list uses — by id when server-bound, by tree
     * reachability otherwise — because a container can be either.
     */
    static final class Watch {
        static final int ADDED = 0, REMOVED = 1, DESTROYED = 2;

        final Addon owner;
        final Widget wdg;
        final int id;
        LuaValue onItemAdded, onItemRemoved, onDestroy;   // null = unset (all three null ⇒ the Watch is dropped)
        boolean alive = true;
        /**
         * Present items → this owner's Item object. Keyed by the item WIDGET, so an {@link Equipory}'s two-slot
         * item is one member and fires once, and the value is the very object {@code :items()} hands back — held
         * strongly here so that the {@code onItemRemoved} payload is the same object the add reported.
         */
        final Map<GItem, LuaValue> items = new IdentityHashMap<GItem, LuaValue>();

        Watch(Addon owner, Widget wdg, int id) {
            this.owner = owner;
            this.wdg = wdg;
            this.id = id;
        }

        /** Set one callback slot; {@code null} clears it. */
        void set(int slot, LuaValue fn) {
            switch(slot) {
            case ADDED:   onItemAdded = fn;   break;
            case REMOVED: onItemRemoved = fn; break;
            default:      onDestroy = fn;     break;
            }
        }

        /** Is anybody still listening? (False ⇒ drop the record and stop polling — the hasSub gate.) */
        boolean subscribed() {
            return (onItemAdded != null) || (onItemRemoved != null) || (onDestroy != null);
        }
    }

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
        UI u = AddonManager.ui;
        if(u == null)
            return new ArrayList<WItem>();
        synchronized(u) { return new ArrayList<WItem>(w.children(WItem.class)); }
    }

    // ---- liveness + the reads ----------------------------------------------------------------------

    /**
     * Resolve a handle's widget, checking liveness (spec 20, W1): a widget still attached to the tree is live, one
     * detached (destroyed &rarr; {@code parent} nulled) is stale. Reachability is
     * {@link Widget#hasparent(Widget) hasparent(ui.root)} (O(depth), the {@code GobRef}-per-access discipline);
     * once stale we <b>null the handle's reference</b> so it cannot pin a dead subtree, and every read then answers
     * {@code nil}/empty. Returns {@code null} when there is no UI yet (transient — the ref is kept, not killed) or
     * the handle is stale/{@code null}.
     */
    static Widget live(LuaWidget n) {
        if(n == null)
            return null;
        Widget w = n.wdg;
        if(w == null)
            return null;
        UI u = AddonManager.ui;
        if((u == null) || (u.root == null))
            return null;                       // no UI yet: unresolvable now, but not proven dead — keep the ref
        if(!w.hasparent(u.root)) {             // detached from the tree → destroyed
            n.wdg = null;                      // drop the ref so a dead subtree can be GC'd (no pin)
            return null;
        }
        return w;
    }

    /** A copy of {@code w}'s child list, taken under the {@code ui} monitor so a walk never races tree mutation. */
    private static List<Widget> kids(Widget w) {
        UI u = AddonManager.ui;
        synchronized(u) { return new ArrayList<Widget>(w.children()); }
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
     *   <li>an {@link IMeter} &rarr; its background resource — the identity {@code hafen.meter():find(needle)} already
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
     * Best-effort text for a text-bearing widget ({@code :text()}, spec 20, W1) — the one upstream-volatile bit,
     * localized in THIS switch (like the spec-14 adapters): {@link Label#texts}, {@link Button} caption,
     * {@link Window#cap}, {@link TextEntry#text()}. An unknown type returns {@code null} (&rarr; Lua {@code nil}),
     * never throws — upstream churn breaks only this method, not addons.
     */
    static String text(Widget w) {
        if(w instanceof Label)
            return ((Label)w).texts;
        if(w instanceof Button) {
            Text t = ((Button)w).text;
            return (t == null) ? null : t.text;
        }
        if(w instanceof Window)
            return ((Window)w).cap;
        if(w instanceof TextEntry)
            return ((TextEntry)w).text();
        if(w instanceof CheckBox) {                // 040.4: CheckBox.lbl is public for exactly this read
            Text t = ((CheckBox)w).lbl;
            return (t == null) ? null : t.text;
        }
        return null;
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
            t.set("pos", xyTable(w.c));
        if(w.sz != null)
            t.set("size", xyTable(w.sz));
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
        if(r.isboolean() && !r.toboolean())       // fn returned false → prune this subtree
            return;
        for(Widget c : kids(w))
            walk(owner, of(owner, c), fn, depth + 1);
    }

    // ---- W2 hit-testing + the small shared helpers --------------------------------------------------

    /**
     * The deepest widget under {@code c} (given in {@code from}'s local coords), for {@code hafen.ui():at} /
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

    /** A {@code {x=,y=}} table from a {@link Coord} (widget-local px), for {@code :pos()}/{@code :size()}. */
    static LuaValue xyTable(Coord c) {
        LuaTable t = new LuaTable();
        t.set("x", LuaValue.valueOf(c.x));
        t.set("y", LuaValue.valueOf(c.y));
        return t;
    }

    /** Parse a Lua {@code {x=,y=}} table into a {@link Coord} (root coords for W2); a clear error otherwise. */
    static Coord coordArg(LuaValue v, String where) {
        if(!v.istable())
            throw new LuaError(where + " expects a {x=,y=} coord table");
        LuaValue x = v.get("x"), y = v.get("y");
        if(!x.isnumber() || !y.isnumber())
            throw new LuaError(where + " expects a {x=,y=} coord table");
        return new Coord(x.toint(), y.toint());
    }
}
