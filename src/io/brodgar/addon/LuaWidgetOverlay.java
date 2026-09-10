package io.brodgar.addon;

import haven.Coord;
import haven.GOut;
import haven.UI;
import haven.Widget;

import java.awt.Color;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.VarArgFunction;

/**
 * <b>What an addon draws over ONE widget</b> — the third receiver of {@code overlay} (spec
 * {@code 103-drawn-over-a-widget}, task 103.3), beside {@code hafen.ui():overlay()} (the screen) and
 * {@code gob:overlay()} (a game object). A record hangs on the widget, paints straight after it, inside
 * that widget's own box, and dies with it. It says exactly one of two things: {@code :draw(fn)}, a painter
 * called back with that widget's box, and {@code :text(s)}, a label the engine draws through the addon's own
 * text cache (103.4) — so a label costs one rasterisation for its lifetime and no Lua at all per frame.
 *
 * <p><b>One record set, two lists, neither derived from the other.</b> The <b>widget's</b> own list
 * ({@link Widget#addonovs}, {@code // addon:}) is the draw order and is what the paint walks; the
 * <b>addon's</b> ({@link Addon#widgetOverlays}) is the census and the teardown. That is exactly the pair
 * {@code hafen.ui():overlay()} already stands on, and the reason the widget's half is a <b>field</b> rather
 * than a map is written on {@link Widget#prof}: the draw traversal touches every widget every frame, so a
 * hash lookup per widget per frame is what makes the obvious version unaffordable — and this feature is
 * armed for the whole session by construction, which is precisely the case that note rules out. An unarmed
 * client pays one null check inside a loop it already runs.
 *
 * <p><b>The record holds its widget WEAKLY.</b> The addon's list would otherwise pin a destroyed subtree
 * until {@code :reload} — the no-pin rule {@link LuaWidget} implements on purpose (D-041). Liveness is
 * therefore derived the same way a Widget object's is ({@link #alive}), never stored: an overlay
 * {@code :exists()} while its record is active <i>and</i> the widget it hangs on is still in a tree, so a
 * destroyed window takes its decorations with it with nothing to hook and nothing to sweep.
 *
 * <p><b>Copy-on-write, because the reader is the draw pass.</b> The writes are Lua verbs on the UI thread
 * and teardown may run off it (a session bind), while the paint iterates inside {@code UI.draw} under
 * {@code synchronized(ui)}. The per-widget list is a {@link CopyOnWriteArrayList} and the field's
 * null-to-list transition is taken under this class's own monitor, so the paint never sees a half-built
 * list and never takes a lock.
 *
 * <p>Never throws into the draw pass: {@link AddonManager#callLua} already isolates a Lua error, and the
 * bracket below guards the dispatch itself.
 */
public final class LuaWidgetOverlay {
    private LuaWidgetOverlay() {
    }


    // ---- one attached record ----------------------------------------------------------------------

    /**
     * One thing an addon attached to one widget under one key. Built <b>bare</b> and configured by the
     * setters on the Overlay object {@code widget:overlay():add(key)} hands back, exactly like a gob
     * overlay's {@link LuaGobOverlay.Attach}: there is nothing to paint until the record says what it is,
     * which is what makes "a half-configured overlay never paints" a property of the shape rather than a
     * rule to remember.
     *
     * <p>Mutable and read from the draw pass one frame later, so every configured field is
     * {@code volatile}: a field is a whole value in either.
     */
    public static final class Rec {
        final Addon owner;
        final String key;
        /** The widget it hangs on, held weakly — see the class note. */
        private final WeakReference<Widget> wdg;

        /** {@code "draw"} or {@code "text"}, or null while the record is still bare. */
        volatile String kind;
        /** {@code :draw(fn)} — {@code fn(g, w, h)}, or null for a label. */
        volatile LuaValue draw;
        /** {@code :text(s)} — the label, or null for a painter. */
        volatile String text;
        /**
         * {@code :anchor(ax, ay)} — ONE pair of fractions, read twice: it picks the point of the widget's box
         * the label sits at, and the point of the label that lands there. So {@code (1, 1)} is the box's
         * bottom-right corner and the label's own, which is what makes an anchor mean "in that corner" with no
         * width to subtract. {@code (0, 0)}, the top-left of both, until the verb says otherwise.
         */
        volatile double ax, ay;
        /** {@code :offset(x, y)} — <b>design</b> pixels, added after the anchor has placed the label. */
        volatile double offX, offY;
        /** {@code :color(c)} — the glyphs' tint; null = the stock white. */
        volatile Color color;
        /** {@code :background(c)} — filled behind the measured raster; null = nothing behind it. */
        volatile Color bg;
        /** {@code :font(h)} — resolved for the draw; null = the client's stock font. */
        volatile FontHandle font;
        /** The very handle {@code :font(h)} was given, for the read to hand back unchanged. */
        volatile LuaValue fontVal;
        /** False once it is removed, replaced, or torn down: the record has stopped painting. */
        volatile boolean active = true;
        /** The interned Lua handle, minted on the first hand-out ({@link #of}). */
        LuaValue lua;

        Rec(Addon owner, String key, Widget w) {
            this.owner = owner;
            this.key = key;
            this.wdg = new WeakReference<Widget>(w);
        }

        /** The widget it hangs on, or {@code null} once the client has let go of it entirely. */
        Widget widget() {
            return wdg.get();
        }
    }

    // ---- the per-widget store ---------------------------------------------------------------------

    /**
     * Is {@code w} still hanging in a tree? The same test {@link LuaWidget#live} makes, over a raw widget
     * and without the {@code parent != null} clause a <i>child</i> read carries: a root is its own tree's
     * top, and it takes overlays like any other widget.
     */
    private static Widget alive(Widget w) {
        UI u = (w == null) ? null : w.ui;
        if((u == null) || u.destroyed || (u.root == null))
            return null;
        return w.hasparent(u.root) ? w : null;
    }

    /** Put {@code r} at the end of {@code w}'s own list, minting the list on the first overlay. */
    private static synchronized void hang(Widget w, Rec r) {
        List<Rec> l = w.addonovs;
        if(l == null)
            w.addonovs = l = new CopyOnWriteArrayList<Rec>();
        l.add(r);
    }

    /** Take {@code r} off {@code w}'s list, and the list off the widget once nothing is left on it. */
    private static synchronized void unhang(Widget w, Rec r) {
        List<Rec> l = (w == null) ? null : w.addonovs;
        if(l == null)
            return;
        l.remove(r);
        if(l.isEmpty())
            w.addonovs = null;
    }

    /** This addon's record under {@code key} on {@code w}, or {@code null}. A scan: the list is short. */
    private static Rec find(Addon owner, Widget w, String key) {
        List<Rec> l = (w == null) ? null : w.addonovs;
        if(l == null)
            return null;
        for(Rec r : l) {
            if(r.active && (r.owner == owner) && r.key.equals(key))
                return r;
        }
        return null;
    }

    /** End this addon's record under {@code key} on {@code w}, if there is one. Inert otherwise. */
    private static void drop(Addon owner, Widget w, String key) {
        Rec r = find(owner, w, key);
        if(r == null)
            return;
        r.active = false;
        unhang(w, r);
        owner.widgetOverlays.remove(r);
    }

    /**
     * Drop the records whose widget has gone. The census is the addon's own list and a widget dies without
     * telling it, so the list is swept at the one moment it grows — which bounds it without a hook on a
     * destroy path this feature has no other reason to touch.
     */
    private static void sweep(Addon owner) {
        for(Rec r : owner.widgetOverlays) {
            if(!r.active || (alive(r.widget()) == null)) {
                r.active = false;
                unhang(r.widget(), r);
                owner.widgetOverlays.remove(r);
            }
        }
    }

    /**
     * Drop every overlay {@code a} attached to any widget ({@code :reload}/disable). <b>Teardown has two
     * ends</b>: clearing the addon's list is not enough, since each record must also leave the widget's own
     * field — a disabled addon would otherwise go on painting until that widget died.
     */
    static void teardown(Addon a) {
        for(Rec r : a.widgetOverlays) {
            r.active = false;
            unhang(r.widget(), r);
        }
        a.widgetOverlays.clear();
    }

    // ---- the draw seam ----------------------------------------------------------------------------

    /**
     * Paint the overlays hanging on {@code w}. Called from the client's ONE draw traversal — the child loop
     * of {@code Widget.draw(GOut, boolean)}, plus the root's own call in {@code UI.draw}, since the root is
     * reached by no loop — <b>after</b> {@code wdg.draw(g2)} and <b>outside</b> the per-widget style frame:
     * an overlay paints over its widget, and is not restyled by the sheet the user put on that widget.
     *
     * <p>{@code g} is already translated and clipped to the widget's own box by the loop that built it, so a
     * painter draws in widget-local coordinates and is cut off at the widget's edge with nothing to compute.
     * {@code w, h} are that box, in <b>design</b> pixels — the pair {@code widget:size()} answers, and the
     * space every {@code g:} coordinate the painter then writes is read in.
     *
     * <p><b>A {@code :text(s)} record never enters Lua here.</b> It is blitted straight through
     * {@link LuaGOut#label}, the same per-addon rendered-text cache {@code g:text} goes through, so a label
     * costs one rasterisation for its lifetime and a lookup a frame — where a painter drawing the same words
     * pays a Lua call on top of it every frame. The record's own dressing is applied at that one call: its
     * font is part of the cache key, its colour and its background are not.
     */
    public static void paint(Widget w, GOut g) {
        List<Rec> recs = w.addonovs;
        if(recs == null)
            return;                                        // the null check an unarmed client pays
        Coord sz = Px.out(w.sz);
        LuaValue lw = LuaValue.valueOf(sz.x), lh = LuaValue.valueOf(sz.y);
        for(Rec r : recs) {
            LuaValue fn = r.draw;                          // bare until :draw(fn) or :text(s) — an incomplete
            String txt = r.text;                           //   overlay paints nothing rather than painting badly
            if(!r.active || ((fn == null) && (txt == null)))
                continue;
            // audit2 B01: the OWNER'S wrapper, not one for the client. It carries that addon's text cache, and
            // holding it per addon is what makes the nesting safe: a painter of this addon that draws a widget
            // whose own overlay belongs to another addon no longer rebinds the wrapper out from under itself.
            LuaGOut gwrap = r.owner.gout;
            LuaTable gt = gwrap.bind(g, r.owner);
            try {
                if(fn != null)
                    AddonManager.callLua(r.owner, Addon.C_DRAW, fn, gt, lw, lh);
                else
                    gwrap.label(g, txt, at(w, r), r.ax, r.ay, r.font, r.color, r.bg);
            } catch(RuntimeException e) {
                /* never throw into the draw pass — callLua already isolates a Lua error */
            } finally {
                gwrap.unbind();
            }
        }
    }

    /**
     * Where a label lands, in the <b>device</b> pixels the blit below this seam is in: the anchor's fraction of
     * the widget's own box, plus the offset. The fraction is taken over {@code w.sz} itself rather than over the
     * design size handed to Lua, since a fraction of the box is the same fraction in either space and the device
     * one has not been rounded; the offset is written in design pixels, so it is the one term that converts.
     */
    private static Coord at(Widget w, Rec r) {
        return new Coord((int)Math.round((w.sz.x * r.ax) + Px.in(r.offX)),
                         (int)Math.round((w.sz.y * r.ay) + Px.in(r.offY)));
    }

    // ---- the collection ---------------------------------------------------------------------------

    /**
     * {@code widget:overlay()} — this addon's painters over one widget, keyed. A <b>view</b>: it reads the
     * widget's own list on every call and holds nothing between them, so it empties itself when the widget
     * leaves the tree and needs no pruning of its own. Minted per call, like every other collection a widget
     * hands out, so two {@code :overlay()} calls are two objects while two {@code :get(key)} are one.
     */
    static LuaValue collection(final Addon owner, final LuaWidget h) {
        return LuaCollection.create("widget:overlay()", new LuaCollection.Source() {
            public List<LuaValue> members() {
                List<LuaValue> out = new ArrayList<LuaValue>();
                Widget w = LuaWidget.live(h);
                List<Rec> l = (w == null) ? null : w.addonovs;
                if(l != null) {
                    for(Rec r : l) {
                        if(r.active && (r.owner == owner))
                            out.add(of(r));
                    }
                }
                return out;
            }

            public String needle(LuaValue member) {
                Rec r = rec(member);
                return (r == null) ? null : r.key;
            }

            /** These have a name — their key — so a string filter is a substring test over it. */
            public boolean named() {
                return true;
            }

            public boolean addressable() {
                return true;
            }

            public LuaValue getMember(LuaValue key) {
                Rec r = find(owner, LuaWidget.live(h), keyArg(key, "widget:overlay():get"));
                return (r == null) ? LuaValue.NIL : of(r);
            }

            public boolean creatable() {
                return true;
            }

            // add(key) — hang a BARE painter and hand it back, for :draw(fn) to say what it paints. The same
            // key twice is a REPLACE: the old record stops painting and the new one takes its place at the
            // END of the draw order, which is where a painter you just installed belongs.
            public LuaValue addMember(Varargs a) {
                String k = keyArg(a.arg(2),
                                  "widget:overlay():add");
                Widget w = LuaWidget.live(h);
                if(w == null)
                    throw new LuaError("widget:overlay():add(key): that widget has left the tree, so there"
                        + " is nothing left to draw over — an overlay hangs on a widget and dies with it,"
                        + " and widget:exists() is the question to ask first");
                drop(owner, w, k);
                sweep(owner);
                Rec r = new Rec(owner, k, w);
                hang(w, r);
                owner.widgetOverlays.add(r);
                return of(r);
            }

            public boolean destroyable() {
                return true;
            }

            // remove(keyOrOverlay) — stop painting and take it off the widget. Removing what is not there is
            // INERT: a key you never attached, one already removed, or one on a widget that has gone is a
            // moment and not a mistake — the same answer gob:overlay():remove gives for a departed gob.
            public void removeMember(LuaValue x) {
                Rec r = rec(x);
                drop(owner, LuaWidget.live(h),
                     (r != null) ? r.key : keyArg(x, "widget:overlay():remove"));
            }

            /** The key is YOUR name for this painter. */
            public String keyName() {
                return "key";
            }
        }, null);
    }

    /**
     * The ONE-KIND rule: an overlay says exactly one thing, and a second, different kind is refused naming the
     * first. Picking a winner between two is how one of them silently stops meaning anything, and the way back
     * is the replace the collection already has.
     */
    private static void becomes(Rec r, String kind, String method) {
        String had = r.kind;
        if((had != null) && !had.equals(kind))
            throw new LuaError("overlay:" + method + "(): this overlay already draws '" + had + "', and an"
                + " overlay says ONE thing — widget:overlay():add(\"" + r.key + "\") again to replace it");
    }

    /**
     * One anchor fraction (un-12): finite by {@link Args#num}, and then <b>0..1</b> — refused outside it,
     * never clamped. {@code 0} is the left (top) edge of the widget and of the label alike, {@code 1} the
     * right (bottom) one, so there is no anchor above 1 to mean: a label anchored at {@code 1e9} is drawn a
     * billion widget-widths away, which is a mistake with no symptom. What a caller wanting a label beside
     * the widget actually wants is {@code overlay:offset(x, y)}, in pixels.
     */
    private static double fraction(Varargs a, int i, String param, String hint) {
        double v = Args.num(a, i, "overlay:anchor", param, hint).todouble();
        if((v < 0.0) || (v > 1.0))
            throw new LuaError("overlay:anchor: " + param + " must be 0..1 — the point across the widget and"
                + " across the label the pair addresses, 0 being one edge and 1 the other. Got " + v
                + ". overlay:offset(x, y) is what moves a label off the corner it is anchored to.");
        return v;
    }

    /** A pair of numbers read back, in the {@code {x=, y=}} shape every place in this API is read in. */
    private static LuaValue pair(double x, double y) {
        LuaTable t = new LuaTable();
        t.set("x", LuaValue.valueOf(x));
        t.set("y", LuaValue.valueOf(y));
        return t;
    }

    /** A key argument: a string, and yours — keys are per addon, so two addons' {@code "tag"} never collide. */
    private static String keyArg(LuaValue kv, String verb) {
        if(kv.type() != LuaValue.TSTRING)
            throw new LuaError(verb + "(key): the key must be a string — it is YOUR name for this painter,"
                + " and keys are per addon");
        return kv.tojstring();
    }

    // ---- the member object -------------------------------------------------------------------------

    /** The interned Lua object for one record — cached on the record, so two lookups are the same value. */
    private static LuaValue of(Rec r) {
        LuaValue v = r.lua;
        if(v == null) {
            v = LuaValue.userdataOf(r, META);
            r.lua = v;
        }
        return v;
    }

    /** The record behind a Lua value, or {@code null} for anything that is not one of these. */
    private static Rec rec(LuaValue v) {
        if((v == null) || !v.isuserdata())
            return null;
        Object o = v.touserdata();
        return (o instanceof Rec) ? (Rec)o : null;
    }

    /** The receiver of a colon call, or a guiding error (a dot call passes the wrong self). */
    private static Rec handle(LuaValue v, String method) {
        Rec r = rec(v);
        if(r == null)
            throw new LuaError("overlay:" + method + "() — use a COLON call on the overlay object"
                + " (widget:overlay():get(key), widget:overlay():add(key))");
        return r;
    }

    /** Is it still painting? Its record is active AND the widget it hangs on is still in a tree. */
    private static boolean painting(Rec r) {
        return r.active && (alive(r.widget()) != null);
    }

    /** {@code tostring(ov)} — the key, and whether it is still painting. */
    private static String str(Rec r) {
        return "Overlay(\"" + r.key + "\")" + (painting(r) ? "" : " removed");
    }

    private static final LuaValue META = buildMeta();

    private static LuaValue buildMeta() {
        LuaTable m = new LuaTable();
        // key() — what it answers to. From the record alone, so it still reads after the overlay is removed.
        m.set("key", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(handle(self, "key").key);
            }
        });
        // draw() / draw(fn) — arity is the verb. fn(g, w, h) runs every frame with the shared GOut wrapper
        // and the WIDGET's box, in widget-local design pixels. Until one is set the overlay paints nothing.
        m.set("draw", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Rec r = handle(a.arg1(), "draw");
                LuaValue fn = Args.written(a, 2, "overlay:draw", "fn");
                if(fn == null) {
                    LuaValue cur = r.draw;
                    return (cur == null) ? LuaValue.NIL : cur;
                }
                if(!fn.isfunction())
                    throw new LuaError("overlay:draw(fn) expects a function fn(g, w, h), got " + fn.typename());
                becomes(r, "draw", "draw");
                r.draw = fn;
                r.kind = "draw";
                return a.arg1();
            }
        });
        // text(s) / text() — the OTHER kind, and the cheap one: the engine draws the label itself, through the
        // very per-addon text cache g:text goes through, so it costs one rasterisation for its lifetime and no
        // Lua at all per frame. :text("…") on a live label relabels it rather than replacing the record.
        m.set("text", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Rec r = handle(a.arg1(), "text");
                LuaValue sv = Args.written(a, 2, "overlay:text", "s");
                if(sv == null)
                    return (r.text == null) ? LuaValue.NIL : LuaValue.valueOf(r.text);
                becomes(r, "text", "text");
                r.text = Args.str(sv, "overlay:text", "s", "the label to draw").tojstring();
                r.kind = "text";
                return a.arg1();
            }
        });
        // ---- how the label is placed and dressed -----------------------------------------------------
        // Five properties, each with a bare read of the same name. They are the LABEL's: a painter is handed the
        // whole box and paints it with g itself, so a value set on one is stored and nothing reads it.
        //
        // anchor(ax, ay) — ONE pair of fractions read twice (see Rec.ax): the point of the widget's box the label
        // sits at, and the point of the label that lands there. The pair is the verb, so ONE number is refused
        // naming both — an anchor with an axis missing is the accident a default would swallow.
        // Each fraction is REFUSED outside 0..1 rather than taken (un-12): the pair addresses a point of a
        // box that is one unit wide, so 1e9 is not a far-away anchor — it is a label placed a million widths
        // off the widget, drawn nowhere and reported by nothing. offset(x, y) is the verb that does take any
        // finite pixel, and it is what moves a label off the corner it is anchored to.
        m.set("anchor", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Rec r = handle(a.arg1(), "anchor");
                if(!Args.passed(a, 2))
                    return pair(r.ax, r.ay);
                if(!Args.passed(a, 3))
                    throw new LuaError("overlay:anchor(ax, ay) takes BOTH fractions: ax picks the point across"
                        + " the widget and across the label, ay the point down them — 0..1 each, so (0, 0) is"
                        + " the top-left corner of both and (0.5, 1) centres the label on the bottom edge");
                double ax = fraction(a, 2, "ax", "a fraction 0..1 across");
                double ay = fraction(a, 3, "ay", "a fraction 0..1 down");
                r.ax = ax; r.ay = ay;
                return a.arg1();
            }
        });
        // offset(x, y) — design pixels, added after the anchor has placed the label. What moves a label off a
        // corner it is anchored to, and the one place a length is written here.
        m.set("offset", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Rec r = handle(a.arg1(), "offset");
                if(!Args.passed(a, 2))
                    return pair(r.offX, r.offY);
                double x = Args.num(a, 2, "overlay:offset", "x", "design pixels across").todouble();
                double y = Args.num(a, 3, "overlay:offset", "y", "design pixels down").todouble();
                r.offX = x; r.offY = y;
                return a.arg1();
            }
        });
        // color(c) — the glyphs' own colour, as the TABLE a colour is. Out of the cache key by construction: it
        // is a tint over a white raster, so a colour that changes every frame rasterises nothing.
        m.set("color", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Rec r = handle(a.arg1(), "color");
                if(!Args.passed(a, 2))
                    return AddonManager.color(r.color);
                r.color = AddonManager.colorArg(a, 2, "overlay:color");
                return a.arg1();
            }
        });
        // background(c) — filled behind the label's measured raster, and nothing wider: a strip under a number
        // on an icon, so it reads over whatever art is beneath it. Out of the cache key like the colour.
        m.set("background", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Rec r = handle(a.arg1(), "background");
                if(!Args.passed(a, 2))
                    return AddonManager.color(r.bg);
                r.bg = AddonManager.colorArg(a, 2, "overlay:background");
                return a.arg1();
            }
        });
        // font(h) — a font handle, and the one property that IS part of the cache key: a label and a g:text of
        // the same string in the same font are one entry. A value that is not a handle is refused rather than
        // resolving to the stock font, which is a label silently in the wrong face.
        m.set("font", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Rec r = handle(a.arg1(), "font");
                LuaValue hv = Args.written(a, 2, "overlay:font", "h");
                if(hv == null) {
                    LuaValue cur = r.fontVal;
                    return (cur == null) ? LuaValue.NIL : cur;
                }
                FontHandle fh = FontHandle.resolve(hv);
                if(fh == null)
                    throw new LuaError("overlay:font(h) expects a font handle — hafen.font():get(\"serif\") or"
                        + " hafen.asset():get(\"fonts/mine.ttf\") — got " + hv.typename());
                r.font = fh;
                r.fontVal = hv;
                return a.arg1();
            }
        });
        // kind() — "draw" or "text", or nil while it is bare. An overlay says exactly one thing.
        m.set("kind", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                String k = handle(self, "kind").kind;
                return (k == null) ? LuaValue.NIL : LuaValue.valueOf(k);
            }
        });
        m.set("exists", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(painting(handle(self, "exists")));
            }
        });
        // info() — the one SNAPSHOT escape hatch. `kind` is absent while the overlay is still bare.
        m.set("info", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Rec r = handle(self, "info");
                LuaTable t = new LuaTable();
                t.set("key", LuaValue.valueOf(r.key));
                if(r.kind != null)
                    t.set("kind", LuaValue.valueOf(r.kind));
                if(r.text != null)
                    t.set("text", LuaValue.valueOf(r.text));
                return t;
            }
        });
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, Refusal.closedIndex("widgetoverlay", m,
            "one overlay over a widget",
            "widget:overlay():remove(key) ends it"));
        mt.set("__name", LuaValue.valueOf("Overlay"));
        mt.set("__tostring", new OneArgFunction() {
            public LuaValue call(LuaValue v) {
                Rec r = rec(v);
                return LuaValue.valueOf((r == null) ? "Overlay(?)" : str(r));
            }
        });
        return mt;
    }
}
