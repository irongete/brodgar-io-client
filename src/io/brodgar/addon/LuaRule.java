package io.brodgar.addon;

import haven.Coord;
import haven.Widget;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.VarArgFunction;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * A <b>Rule object</b> — one level of the styling cascade, and the whole of what used to be a Lua table of
 * properties (spec {@code 039-uniform-api} §5.4, task 039.7). Its properties are <b>setters</b>, each
 * returning the rule so a level is one expression:
 *
 * <pre>
 *   hafen.ui():sheet():rule("chat"):font(mono):color(200, 210, 200)
 *   hafen.ui():find("window[title=Cupboard]"):rule():padding(6)
 * </pre>
 *
 * <p><b>Two bindings, one type.</b> A rule reached through a {@link LuaSheet} is named by a <b>selector</b> and
 * reaches every widget (or render site) that selector matches; a rule reached through {@code widget:rule()} is
 * the hand-named level on <b>one</b> widget (034.3), above every matched rule. They differ in which widgets
 * they reach and in nothing else, which is why they are the same object with the same verbs — and why the two
 * that cannot apply say so: layout is refused on a site key and on a widget's own level, naming the verb
 * ({@code widget:position(x, y)}) that already is the hand-named level of the layout cascade.
 *
 * <p><b>A rule is a NAME for a level, never the record itself</b> (D-065's shape). What it says lives in the
 * sheet (or, for a widget's own level, in {@link Sheet}'s per-widget map), and every read and write goes there
 * through the binding — so a handle kept across a {@code :remove()} is not stale: setting a property on it
 * simply says that level again. That is also what lets {@code sheet:rule(sel)} hand back the same object every
 * time without the object having to hold anything.
 *
 * <p><b>{@code position} and {@code anchor} are ONE property said two ways</b> (036.3), so the two setters
 * write the same slot: the later call replaces the earlier, exactly as a second {@code :position()} does, and
 * the read that was not written answers {@code nil}. Inside a {@code sheet:load(t)} table there is no "later"
 * — the keys have no order — so a rule saying both there is refused rather than resolved by iteration order.
 *
 * <p><b>An explicit {@code nil} is refused</b> (§2.9): a rule property has no undo of its own, and a {@code nil}
 * that silently became a read is the accident the discipline exists for. What ends a level is {@code :remove()}.
 */
public final class LuaRule {
    /** The sheet this rule belongs to, or {@code null} when it is a widget's own level. */
    private final LuaSheet sheet;
    /** The selector this rule is named by, or {@code null} on a widget's own level. */
    private final String selector;
    /** The widget this rule is the hand-named level of, or {@code null} on a sheet rule. */
    private final LuaWidget wdg;

    private LuaRule(LuaSheet sheet, String selector, LuaWidget wdg) {
        this.sheet = sheet;
        this.selector = selector;
        this.wdg = wdg;
    }

    /** {@code tostring(r)}: {@code Rule("chat")} / {@code Rule(widget)}. */
    public String toString() {
        return (selector == null) ? "Rule(widget)" : ("Rule(\"" + selector + "\")");
    }

    /** How this rule spells itself in an error — the call that reaches it. */
    String where() {
        return (selector == null) ? "widget:rule()" : ("sheet:rule(\"" + selector + "\")");
    }

    /** The {@code LuaRule} behind a Lua value, or {@code null} for anything that is not one. */
    static LuaRule resolve(LuaValue v) {
        if((v == null) || !v.isuserdata())
            return null;
        Object o = v.touserdata();
        return (o instanceof LuaRule) ? (LuaRule)o : null;
    }

    /** A sheet rule, minted by {@link LuaSheet} when it first names a selector (it interns it from then on). */
    static LuaValue ofSheet(Addon owner, LuaSheet sheet, String selector) {
        return LuaValue.userdataOf(new LuaRule(sheet, selector, null), owner.styleRules.meta());
    }

    /** {@code widget:rule()} — the interned hand-named level of {@code w} in {@code owner}'s env. */
    static LuaValue ofWidget(Addon owner, LuaWidget h, Widget w) {
        return owner.styleRules.of(h, w);
    }

    // ---- the per-addon intern cache for WIDGET rules + the shared metatable -------------------------

    /**
     * One addon's {@code widget:rule()} cache and the Rule metatable both bindings share ({@link
     * Addon#styleRules}). Weak keys <b>and</b> weak values, the {@link LuaWidget.Cache} shape and for its
     * reasons: {@code Widget} overrides neither {@code equals} nor {@code hashCode}, so this is an identity map
     * for free, and a strong key would pin every widget an addon ever styled.
     */
    static final class Cache {
        private final Addon owner;
        private final Map<Widget, WeakReference<LuaValue>> live =
            new WeakHashMap<Widget, WeakReference<LuaValue>>();
        private LuaValue mt;

        Cache(Addon owner) {
            this.owner = owner;
        }

        synchronized LuaValue of(LuaWidget h, Widget w) {
            if((h == null) || (w == null))          // a stale widget: a rule with nothing to hold on to
                return LuaValue.userdataOf(new LuaRule(null, null, h), meta());
            WeakReference<LuaValue> r = live.get(w);
            if(r != null) {
                LuaValue v = r.get();
                if(v != null)
                    return v;
            }
            LuaValue v = LuaValue.userdataOf(new LuaRule(null, null, h), meta());
            live.put(w, new WeakReference<LuaValue>(v));
            return v;
        }

        synchronized LuaValue meta() {
            if(mt == null)
                mt = buildMeta(owner);
            return mt;
        }

        /**
         * Move an interned widget Rule to the widget that replaced it (040.2, {@link UiApi#rebuild}). The
         * {@link LuaRule} itself needs no fixing — it holds the {@link LuaWidget} <b>handle</b> and resolves it
         * at every read and write, so re-pointing the handle already re-aimed it — but the entry has to follow,
         * or {@code w:rule()} would mint a second Rule object for what is still one widget's one level.
         */
        synchronized void rekey(Widget from, Widget to) {
            WeakReference<LuaValue> r = live.remove(from);
            if((r != null) && (r.get() != null))
                live.put(to, r);
        }
    }

    // ---- the binding: where this level's properties actually live ----------------------------------

    /** What this level says right now, or {@code null} when it says nothing (or its widget is gone). */
    private Sheet.Props read(Addon owner) {
        if(sheet != null)
            return sheet.props(selector);
        Sheet.Props p = Sheet.widgetProps(owner, LuaWidget.live(wdg));
        return ((p == null) || p.empty()) ? null : p;
    }

    /** The record a setter writes into — the live one on a sheet rule, a copy on a widget's own level. */
    private Sheet.Props edit(Addon owner) {
        if(sheet != null)
            return sheet.props(selector);
        Sheet.Props p = Sheet.widgetProps(owner, LuaWidget.live(wdg));
        return (p == null) ? new Sheet.Props() : p;
    }

    /** ...and where a write lands: the sheet re-applies if it is installed, a widget level applies at once. */
    private void commit(Addon owner, Sheet.Props p) {
        if(sheet != null)
            sheet.changed();
        else
            Sheet.setWidgetProps(owner, LuaWidget.live(wdg), p);
    }

    /**
     * Refuse a layout property this level can never apply — a <b>site</b> key (a render site is where the client
     * draws text, and text has no position), or a <b>widget's own</b> level (whose layout spelling is the verb).
     * The two messages are {@link Sheet}'s own, so the refusal reads the same however the rule was written.
     */
    private void layoutable(String prop) {
        if(sheet != null)
            Sheet.layoutable(where(), prop, sheet.site(selector), sheet.selector(selector));
        else
            Sheet.layoutable(where(), prop, null, null);
    }

    // ---- the metatable -----------------------------------------------------------------------------

    private static LuaValue buildMeta(final Addon owner) {
        LuaTable mt = new LuaTable();
        // A CLOSED vocabulary (D-072, one shape along): a misspelt property is a typo with no future
        // meaning, so it throws naming the ones that exist rather than reading nil and failing a character
        // later as "attempt to call a nil value".
        mt.set(LuaValue.INDEX, Retired.closedIndex("rule", methods(owner),
            "the properties a rule carries are " + Sheet.PROPS
            + ", and its other verbs are :selector() :sheet() :remove() and :info()"));
        mt.set("__name", LuaValue.valueOf("Rule"));
        mt.set("__tostring", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaRule r = resolve(self);
                return LuaValue.valueOf((r == null) ? "Rule(?)" : r.toString());
            }
        });
        return mt;
    }

    private static LuaTable methods(final Addon owner) {
        LuaTable m = new LuaTable();
        // font(face) — the face this rule draws its text with: the handle hafen.font():get(name) or
        // hafen.asset():get(path) hands back, optionally :derive()d, or the same face NAMED (065.3),
        // { builtin = "mono", size = 11 } / { asset = "fonts/Inter.ttf" }, which is what a theme.json says.
        // A handle's OWN colour is ignored on a client surface (D-073): a surface's colour is said as a
        // colour, where the sheet can be read, and a handle's colour is for the addon's own pixels.
        m.set("font", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = a.arg1();
                LuaRule r = handle(self, "font");
                Sheet.Props cur = r.read(owner);
                LuaValue v = Args.written(a, 2, r.where() + ":font", "face");
                if(v == null)
                    return ((cur == null) || (cur.font == null)) ? LuaValue.NIL
                        : FontApi.handleFor(owner, cur.font, owner);
                Sheet.Props p = r.edit(owner);
                p.font = Sheet.font(owner, r.where(), v);
                r.commit(owner, p);
                return self;
            }
        });
        // color(r, g, b[, a]) — positional components, and a colour VALUE passes straight back through (§2.8),
        // so rule:color(other:color()) is one expression. Reads back as {r=,g=,b=,a=}, the API's own shape.
        m.set("color", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = a.arg1();
                LuaRule r = handle(self, "color");
                Sheet.Props cur = r.read(owner);
                if(!Args.passed(a, 2))
                    return ((cur == null) || (cur.color == null)) ? LuaValue.NIL
                        : AddonManager.color(cur.color);
                if(a.arg(2).isnil())
                    throw Args.nilRefused(r.where() + ":color", "color");
                Sheet.Props p = r.edit(owner);
                p.color = colorArg(a, 2, r.where() + ":color");
                r.commit(owner, p);
                return self;
            }
        });
        // bg{color=…} / bg{image=…} / bg{asset=…} / bg{res=…} — the surface something is painted on (035.1), and
        // since 065.2 an ARRAY of those, painted in order. A structured VALUE, not named arguments: a background
        // is one thing said as a table, exactly as a colour is.
        m.set("bg", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = a.arg1();
                LuaRule r = handle(self, "bg");
                Sheet.Props cur = r.read(owner);
                LuaValue v = Args.written(a, 2, r.where() + ":bg", "background");
                if(v == null)
                    return ((cur == null) || (cur.bg == null)) ? LuaValue.NIL : cur.bg.toLua(owner);
                Sheet.Props p = r.edit(owner);
                p.bg = Chrome.parseBg(owner, r.where(), v);
                r.commit(owner, p);
                return self;
            }
        });
        // border{image=…, slice={l,t,r,b}} — a 9-slice frame (035.1), or border{box="gfx/hud/wnd"}, one of the
        // client's own (065.2). On the slice form both fields are required: art with no slice cannot be cut into
        // a frame, and there is no default worth guessing at.
        m.set("border", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = a.arg1();
                LuaRule r = handle(self, "border");
                Sheet.Props cur = r.read(owner);
                LuaValue v = Args.written(a, 2, r.where() + ":border", "border");
                if(v == null)
                    return ((cur == null) || (cur.border == null)) ? LuaValue.NIL : cur.border.toLua(owner);
                Sheet.Props p = r.edit(owner);
                p.border = Chrome.parseBorder(owner, r.where(), v);
                r.commit(owner, p);
                return self;
            }
        });
        // padding(n) / padding(l, t, r, b) — the room a surface keeps between its frame and its content (065.1),
        // in design pixels, on each of the four sides. One number says all four; the read hands the four back
        // keyed, which is a shape the setter takes again.
        m.set("padding", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = a.arg1();
                LuaRule r = handle(self, "padding");
                Sheet.Props cur = r.read(owner);
                LuaValue v = Args.written(a, 2, r.where() + ":padding", "pixels");
                if(v == null)
                    return ((cur == null) || (cur.padding == null)) ? LuaValue.NIL : cur.padding.toLua();
                Sheet.Props p = r.edit(owner);
                p.padding = Chrome.parsePadding(r.where(), a, 2);
                r.commit(owner, p);
                return self;
            }
        });
        // caption{at=, offset=} — where a window's decoration draws its caption (065.4): one of the nine
        // corners of the frame plus an offset in design pixels. With no rule the client's own place is used, to
        // the pixel. It carries no art: a caption is the window's own text, drawn in the "window.title" font,
        // and what it SITS on is that key's own bg/border -- the plate.
        m.set("caption", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = a.arg1();
                LuaRule r = handle(self, "caption");
                Sheet.Props cur = r.read(owner);
                LuaValue v = Args.written(a, 2, r.where() + ":caption", "spot");
                if(v == null)
                    return ((cur == null) || (cur.caption == null)) ? LuaValue.NIL : cur.caption.toLua();
                Sheet.Props p = r.edit(owner);
                p.caption = Chrome.parseSpot(r.where(), ".caption", v);
                r.commit(owner, p);
                return self;
            }
        });
        // sizer{<art>, at=, offset=} — the corner grip a resizable window draws (065.4). It is an ordinary
        // surface, so it is named the four ways every picture is and its own `at`/`offset` place it against the
        // frame's box; with no rule the client's own art sits at the client's own place.
        m.set("sizer", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = a.arg1();
                LuaRule r = handle(self, "sizer");
                Sheet.Props cur = r.read(owner);
                LuaValue v = Args.written(a, 2, r.where() + ":sizer", "sizer");
                if(v == null)
                    return ((cur == null) || (cur.sizer == null)) ? LuaValue.NIL : cur.sizer.toLua(owner);
                Sheet.Props p = r.edit(owner);
                p.sizer = Chrome.parseArt(owner, r.where(), ".sizer", v);
                r.commit(owner, p);
                return self;
            }
        });
        // position(x, y) — where the matched widget sits inside its parent, in raw px (036.2). It is the anchor
        // whose target is the widget's own parent, at its top-left, so it and :anchor() are the same slot: the
        // later of the two wins, and the one that was not written reads nil.
        m.set("position", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = a.arg1();
                LuaRule r = handle(self, "position");
                Sheet.Props cur = r.read(owner);
                if(!Args.passed(a, 2)) {
                    return ((cur == null) || (cur.pos == null) || !cur.pos.plain) ? LuaValue.NIL
                        : LuaWidget.xyTable(cur.pos.offset);
                }
                if(a.arg(2).isnil())
                    throw Args.nilRefused(r.where() + ":position", "position");
                r.layoutable("position");
                Sheet.Props p = r.edit(owner);
                p.pos = Layout.Anchor.at(coordArg(a, 2, r.where(), "position"));
                r.commit(owner, p);
                return self;
            }
        });
        // anchor{to=, at=, offset=} — the same place said as a RELATIONSHIP (036.3): a corner of the screen (or
        // of another widget) plus an offset, re-derived whenever what it hangs off changes.
        m.set("anchor", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = a.arg1();
                LuaRule r = handle(self, "anchor");
                Sheet.Props cur = r.read(owner);
                LuaValue v = Args.written(a, 2, r.where() + ":anchor", "anchor");
                if(v == null) {
                    if((cur == null) || (cur.pos == null) || cur.pos.plain)
                        return LuaValue.NIL;
                    LuaTable t = new LuaTable();
                    cur.pos.toLua(owner, t);
                    return t.get("anchor");
                }
                r.layoutable("anchor");
                Sheet.Props p = r.edit(owner);
                p.pos = Layout.parseAnchor(r.where(), v);
                r.commit(owner, p);
                return self;
            }
        });
        // size(w, h) — how big the matched widget is; a window's CONTENT box (036.2).
        m.set("size", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = a.arg1();
                LuaRule r = handle(self, "size");
                Sheet.Props cur = r.read(owner);
                if(!Args.passed(a, 2))
                    return ((cur == null) || (cur.size == null)) ? LuaValue.NIL
                        : LuaWidget.xyTable(cur.size);
                if(a.arg(2).isnil())
                    throw Args.nilRefused(r.where() + ":size", "size");
                r.layoutable("size");
                Sheet.Props p = r.edit(owner);
                p.size = coordArg(a, 2, r.where(), "size");
                r.commit(owner, p);
                return self;
            }
        });
        // selector() — the key this rule is named by, or nil on a widget's own level (which names no selector:
        // it IS the widget).
        m.set("selector", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaRule r = handle(self, "selector");
                return (r.selector == null) ? LuaValue.NIL : LuaValue.valueOf(r.selector);
            }
        });
        // sheet() — climb back, so a whole sheet is one expression. nil on a widget's own level: it belongs to
        // no sheet, which is why dropping the sheet leaves it standing.
        m.set("sheet", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaRule r = handle(self, "sheet");
                return (r.sheet == null) ? LuaValue.NIL : r.sheet.handle();
            }
        });
        // remove() — this level stops saying anything (R7). On a widget's own level that is the undo of
        // widget:rule(); on a sheet rule it is how one rule leaves a sheet without rebuilding it. The handle
        // stays usable: a rule is a NAME for a level, so setting a property on it says that level again.
        m.set("remove", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaRule r = handle(self, "remove");
                if(r.sheet != null) {
                    r.sheet.clear(r.selector);
                } else {
                    Sheet.setWidgetProps(owner, LuaWidget.live(r.wdg), null);
                }
                return LuaValue.NIL;
            }
        });
        // info() — the snapshot hatch: every property this rule sets, plus its selector, or nil when it says
        // nothing. On a widget's own level that is exactly "what have I written on this widget", which reads
        // differently from widget:style() — the resolved answer the whole cascade makes of it.
        m.set("info", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaRule r = handle(self, "info");
                Sheet.Props p = r.read(owner);
                if((p == null) || p.empty())
                    return LuaValue.NIL;
                LuaTable t = new LuaTable();
                if(r.selector != null)
                    t.set("selector", LuaValue.valueOf(r.selector));
                p.toLua(owner, t);
                return t;
            }
        });
        return m;
    }

    /** The rule behind a method's {@code self}, or a guiding error (a dot call passes the wrong self). */
    private static LuaRule handle(LuaValue self, String method) {
        LuaRule r = resolve(self);
        if(r == null)
            throw new LuaError("rule:" + method + "() — use a COLON call on a Rule object"
                + " (hafen.ui():sheet():rule(selector), or widget:rule())");
        return r;
    }

    /**
     * A colour argument: the positional components a setter is written with, or a colour <b>value</b> read back
     * out of the API, which passes straight through (§2.8) so {@code r:color(other:color())} is one expression.
     */
    private static java.awt.Color colorArg(Varargs a, int i, String verb) {
        LuaValue v = a.arg(i);
        if(v.istable()) {
            java.awt.Color c = AddonManager.luaColor(v, null);
            if(c == null)
                throw new LuaError(verb + "(color): a colour value is {r, g, b[, a]} (0..255)");
            return c;
        }
        LuaTable t = new LuaTable();
        int n = 0;
        for(int j = i; (j <= a.narg()) && a.arg(j).isnumber(); j++)
            t.set(++n, a.arg(j));
        java.awt.Color c = AddonManager.luaColor(t, null);
        if(c == null)
            throw new LuaError(verb + "(r, g, b[, a]) expects three or four numbers 0..255, or a colour value"
                + " read back from the API");
        return c;
    }

    /**
     * A coordinate argument, the same two ways: {@code (x, y)} as a setter is written, or the {@code {x=,y=}}
     * table {@code widget:position()} hands back, so a read round-trips into a write unchanged.
     */
    private static Coord coordArg(Varargs a, int i, String ctx, String prop) {
        LuaValue v = a.arg(i);
        if(v.istable())
            return Layout.parseCoord(ctx, prop, v);
        LuaTable t = new LuaTable();
        t.set(1, a.arg(i));
        t.set(2, Args.required(a, i + 1, ctx + ":" + prop, "size".equals(prop) ? "height" : "y"));
        return Layout.parseCoord(ctx, prop, t);
    }
}
