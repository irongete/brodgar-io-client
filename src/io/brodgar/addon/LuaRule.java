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
 *   hafen.ui():sheet():rule("chat"):font(mono):color({200, 210, 200})
 *   s:ui():find("window[title=Cupboard]"):rule():padding(6)
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
 * through the binding — so a handle kept across a {@code :release()} is not stale: setting a property on it
 * simply says that level again. That is also what lets {@code sheet:rule(sel)} hand back the same object every
 * time without the object having to hold anything.
 *
 * <p><b>{@code position} and {@code anchor} are ONE property said two ways</b> (036.3), so the two setters
 * write the same slot: the later call replaces the earlier, exactly as a second {@code :position()} does, and
 * the read that was not written answers {@code nil}. Inside a {@code sheet:load(t)} table there is no "later"
 * — the keys have no order — so a rule saying both there is refused rather than resolved by iteration order.
 *
 * <p><b>An explicit {@code nil} is refused</b> (§2.9): a rule property has no undo of its own, and a {@code nil}
 * that silently became a read is the accident the discipline exists for. What ends a level is {@code :release()}.
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

    /**
     * The SITE key this rule names (065.16), or {@code null} on a tree key and on a widget's own level — the
     * question {@code color} has to ask, because two site keys take a colour SEQUENCE where every other surface
     * takes a colour. The sheet worked it out when the rule was first named, and {@link #layoutable} already
     * asks it the same way, so this is a lookup rather than a second parse of one selector.
     */
    String scope() {
        if(sheet == null)
            return null;                  // widget:rule() is the hand-named level, and names no site
        return sheet.site(selector);
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
            + ", and its other verbs are :selector() :sheet() :release() and :info()"));
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
        // color(c) — the TABLE a colour is, keyed or positional, so a colour VALUE read back out of the API is
        // one of them and rule:color(other:color()) is one expression. Reads back as {r=,g=,b=,a=} either way.
        //
        // 065.16 — and on the two keys whose colour the client WALKS rather than holds, `chat.speaker` and
        // `chat.urgent`, the same verb takes the SEQUENCE instead: {palette=…} or {generate=…}. It is one
        // property because it answers one question, "what colour is this drawn in"; the two keys differ in
        // owing an answer per speaker and per urgency level rather than one for the surface. Which shape a key
        // takes is Chrome's to say, so both doors into a rule — this one and sheet:load — refuse alike.
        m.set("color", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = a.arg1();
                LuaRule r = handle(self, "color");
                Sheet.Props cur = r.read(owner);
                if(!Args.passed(a, 2)) {
                    if(cur == null)
                        return LuaValue.NIL;
                    if(cur.seq != null)
                        return cur.seq.toLua();
                    return (cur.color == null) ? LuaValue.NIL : AddonManager.color(cur.color);
                }
                if(a.arg(2).isnil())
                    throw Args.nilRefused(r.where() + ":color", "color");
                String verb = r.where() + ":color";
                // A third argument is loose components, which a colour write no longer takes: the flag says so,
                // Chrome skips the sequence guess, and colorArg below raises with what a colour IS.
                boolean sq = Chrome.seqShape(verb, r.scope(), a.arg(2), Args.passed(a, 3));
                Sheet.Props p = r.edit(owner);
                if(sq) {
                    p.seq = Chrome.parseSeq(verb, a.arg(2));
                    p.color = null;       // one property, one slot: the other shape is no longer what it says
                } else {
                    p.color = colorArg(a, 2, verb);
                    p.seq = null;
                }
                r.commit(owner, p);
                return self;
            }
        });
        // emboss(false) / emboss{texture=<art>} — whether this client's own RELIEF is cut through the letters of
        // an embossed surface, and with what (065.14). It is the property that lets `color` reach a window
        // caption, a section heading and a button label at all: a texture tiled through a glyph mask leaves no
        // colour behind to override, so the only way to paint one is to stop tiling. Reads back `false` where
        // the rule dropped the relief and nil where it says nothing — two different answers, and Lua tells them
        // apart, because the second means the client's own.
        m.set("emboss", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = a.arg1();
                LuaRule r = handle(self, "emboss");
                Sheet.Props cur = r.read(owner);
                LuaValue v = Args.written(a, 2, r.where() + ":emboss", "emboss");
                if(v == null)
                    return ((cur == null) || (cur.emboss == null)) ? LuaValue.NIL : cur.emboss.toLua(owner);
                Sheet.Props p = r.edit(owner);
                p.emboss = Chrome.parseEmboss(owner, r.where(), v);
                r.commit(owner, p);
                return self;
            }
        });
        // glow{color=…, radius=n} — the blurred HALO behind an embossed surface's letters (065.15), the other
        // decorator every carved site builds around its foundry. Both fields are required and a radius of 0 is
        // the way to say NO halo; leaving the property out is the other answer, and it is the one that keeps
        // the client's own. One radius stands for the client's two, which differ by a quarter of a pixel.
        m.set("glow", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = a.arg1();
                LuaRule r = handle(self, "glow");
                Sheet.Props cur = r.read(owner);
                LuaValue v = Args.written(a, 2, r.where() + ":glow", "glow");
                if(v == null)
                    return ((cur == null) || (cur.glow == null)) ? LuaValue.NIL : cur.glow.toLua();
                Sheet.Props p = r.edit(owner);
                p.glow = Chrome.parseGlow(r.where(), v);
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
        // border{image=…, slice={l,t,r,b}} — a 9-slice frame (035.1), border{box="gfx/hud/wnd"}, one of the
        // client's own (065.2), or border{color=…, width=n}, a LINE (065.6) — which is what the boxes the client
        // draws in code rather than from a resource are made of. On the slice form both fields are required: art
        // with no slice cannot be cut into a frame, and there is no default worth guessing at. A line is the same
        // discipline at its own arity, and refuses the two fields that only mean something to a picture.
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
        // picture{<art>, hover=, pressed=, …} — the whole plate a surface IS (065.12), where the client blits a
        // picture rather than framing something. ONE surface, never a list: layers are what a bg is painted in,
        // and a second plate under this one could never be seen. It is read at the DRAW, so a rule on a picture
        // the server re-points survives the re-point and gives the client's own art back on :release().
        m.set("picture", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = a.arg1();
                LuaRule r = handle(self, "picture");
                Sheet.Props cur = r.read(owner);
                LuaValue v = Args.written(a, 2, r.where() + ":picture", "picture");
                if(v == null)
                    return ((cur == null) || (cur.picture == null)) ? LuaValue.NIL : cur.picture.toLua(owner);
                Sheet.Props p = r.edit(owner);
                p.picture = Chrome.parsePicture(owner, r.where(), ".picture", v);
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
        // close{<art>, hover=, pressed=, at=, offset=} — the button a window's decoration draws in a corner
        // (065.5). Its art is a surface like every other, with the two faces a button wears riding INSIDE the
        // value rather than in the selector; its `at`/`offset` place the button itself. The two halves are
        // independent, so either alone is a rule, and with neither the client's own button sits where it sits.
        m.set("close", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = a.arg1();
                LuaRule r = handle(self, "close");
                Sheet.Props cur = r.read(owner);
                LuaValue v = Args.written(a, 2, r.where() + ":close", "close");
                if(v == null)
                    return ((cur == null) || (cur.close == null)) ? LuaValue.NIL : cur.close.toLua(owner);
                Sheet.Props p = r.edit(owner);
                p.close = Chrome.parseClose(owner, r.where(), ".close", v);
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
                        : LuaWidget.whTable(cur.size);
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
        // release() — this level stops saying anything (R7). A rule is a LAYER you took over what the client
        // draws, so ending it gives that layer back: on a widget's own level it is the undo of widget:rule(),
        // and on a sheet rule it is how one rule leaves a sheet without rebuilding it. The handle stays usable:
        // a rule is a NAME for a level, so setting a property on it says that level again.
        m.set("release", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaRule r = handle(self, "release");
                if(r.sheet != null) {
                    r.sheet.clear(r.selector);
                } else {
                    Sheet.setWidgetProps(owner, LuaWidget.live(r.wdg), null);
                }
                return self;              // the receiver: every ending chains
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
     * A colour argument: the TABLE a colour is, keyed or positional — so a colour <b>value</b> read back out of
     * the API is one of them and {@code r:color(other:color())} is one expression. The refusal is
     * {@link AddonManager#colorRefusal}, the same words every other colour write raises.
     */
    private static java.awt.Color colorArg(Varargs a, int i, String verb) {
        return AddonManager.colorArg(a, i, verb);
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
