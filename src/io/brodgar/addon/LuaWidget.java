package io.brodgar.addon;

import haven.Button;
import haven.Coord;
import haven.Label;
import haven.Text;
import haven.TextEntry;
import haven.UI;
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
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * A <b>Widget object</b> — the ONE entity {@code hafen.ui} hands back for a widget (spec {@code 029-widget-oop},
 * feature B1). It replaces the transient {@code WidgetNode} of spec {@code 20} and is, from 029.2 on, also what
 * {@code hafen.ui.window{}}/{@code widget{}} return: <b>what you create and what you find are the same type</b>.
 * Built on exactly the mechanism {@link LuaGob} (017), {@link LuaKin} (020), {@link LuaSlot} (021),
 * {@link LuaPagina} (023), {@link LuaSound} (024), {@link LuaBuff} (025) and {@link LuaMeter} (027) established:
 * userdata + a per-addon metatable + a per-addon intern cache, with {@code :info()} as the one snapshot hatch.
 *
 * <p><b>Interned, so {@code ==} is the identity test.</b> Two lookups of the same live widget are the same Lua
 * value ({@code hafen.ui.at(m.x,m.y) == hafen.ui.at(m.x,m.y)}), and a widget kept across frames stays {@code ==}.
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
    }

    // ---- the Widget metatable ----------------------------------------------------------------------

    /** The per-addon metatable: {@code __index} = the methods table, plus {@code __tostring}/{@code __name}. */
    private static LuaValue buildMeta(Addon owner) {
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, methods(owner));
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
     * The method set (029.1: the READS — creation, the geometry writes and {@code :items()} arrive in 029.2/029.3).
     * Every reader re-reads through the widget and answers {@code nil}/empty once it is stale; {@code :exists()}
     * always answers. The metatable is per-addon, so the closures can capture the {@code owner} the child
     * handles, the walk callback and the font override all need.
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
        // parent() — the enclosing Widget object, or nil at the root / once stale.
        m.set("parent", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Widget w = live(handle(self, "parent"));
                if(w == null)
                    return LuaValue.NIL;
                Widget p = w.parent;
                return (p == null) ? LuaValue.NIL : of(owner, p);
            }
        });
        // pos() — {x=,y=} position within the parent (widget-local px). 029.2 adds the :pos(x,y) write arity.
        m.set("pos", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Widget w = live(handle(self, "pos"));
                return ((w == null) || (w.c == null)) ? LuaValue.NIL : xyTable(w.c);
            }
        });
        // size() — {x=,y=}. 029.2 adds the :size(w,h) write arity.
        m.set("size", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Widget w = live(handle(self, "size"));
                return ((w == null) || (w.sz == null)) ? LuaValue.NIL : xyTable(w.sz);
            }
        });
        // visible() — is it currently drawn? False once stale.
        m.set("visible", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Widget w = live(handle(self, "visible"));
                return LuaValue.valueOf((w != null) && w.visible());
            }
        });
        // text() — best-effort text for a text-bearing widget (Label/Button/Window/TextEntry), else nil.
        m.set("text", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Widget w = live(handle(self, "text"));
                if(w == null)
                    return LuaValue.NIL;
                String t = text(w);
                return (t == null) ? LuaValue.NIL : LuaValue.valueOf(t);
            }
        });
        // exists() — is this widget still attached to the tree? The one read that always answers (D-060: a widget
        // HAS a lifetime, unlike a name-keyed Sound). False after a destroy and false across a relog.
        m.set("exists", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(live(handle(self, "exists")) != null);
            }
        });
        // info() — the one SNAPSHOT escape hatch ({type,id,pos,size,visible,text}), for logging/serialising. An
        // absent value is simply an unset key; nil for a stale widget (there is nothing to snapshot).
        m.set("info", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return snapshot(live(handle(self, "info")));
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
        // rootpos() — W2: {x=,y=} this widget's top-left in root coords (with :size() = a highlight box).
        m.set("rootpos", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Widget w = live(handle(self, "rootpos"));
                if(w == null)
                    return LuaValue.NIL;
                UI u = AddonManager.ui;
                Coord rp;
                synchronized(u) { rp = w.rootpos(); }
                return (rp == null) ? LuaValue.NIL : xyTable(rp);
            }
        });
        // F5 (spec 21): setFont(h) — restyle THIS widget and everything drawn inside it with a font handle
        // (hafen.asset / hafen.font), while its siblings keep the scope/"default" font (the top of the font
        // resolution chain). Owner-tagged: reverted on :reload/disable, and it dies with the widget. Chains.
        m.set("setFont", new VarArgFunction() {
            public Varargs invoke(Varargs a) {            // w:setFont(h) → self=arg1, handle=arg2
                FontApi.setNodeFont(owner, live(handle(a.arg1(), "setFont")), a.arg(2));
                return a.arg1();
            }
        });
        // F5: resetFont() — drop THIS addon's per-instance override on this widget (it falls back to the
        // scope/"default" chain, or to another addon's override beneath). A no-op if there was none. Chains.
        m.set("resetFont", new VarArgFunction() {
            public Varargs invoke(Varargs a) {            // w:resetFont() → self=arg1
                FontApi.resetNodeFont(owner, live(handle(a.arg1(), "resetFont")));
                return a.arg1();
            }
        });
        return m;
    }

    /** The handle behind a method's {@code self}, or a guiding error (a dot-call passes the wrong self). */
    private static LuaWidget handle(LuaValue self, String method) {
        LuaWidget h = resolve(self);
        if(h == null)
            throw new LuaError("widget:" + method + "() — use a COLON call on a Widget object"
                + " (hafen.ui.root(), hafen.ui.node(id), hafen.ui.at(x, y))");
        return h;
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
     */
    static String typeName(Widget w) {
        Class<?> c = w.getClass();
        String n = c.getSimpleName();
        while(n.isEmpty() && (c.getSuperclass() != null)) {
            c = c.getSuperclass();
            n = c.getSimpleName();
        }
        return n.isEmpty() ? "?" : n;
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
        return null;
    }

    /**
     * A Widget snapshot — {@code widget:info()}, the escape hatch for logging/serialising:
     * {@code {type,id,pos,size,visible,text}}. Expressed over the same accessors the methods use, so there is one
     * source of truth per field; an absent value is simply an unset key.
     */
    static LuaValue snapshot(Widget w) {
        if(w == null)
            return LuaValue.NIL;
        LuaTable t = new LuaTable();
        t.set("type", LuaValue.valueOf(typeName(w)));
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
     * The deepest widget under {@code c} (given in {@code from}'s local coords), for {@code hafen.ui.at} /
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
