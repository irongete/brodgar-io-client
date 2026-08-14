/*
 *  This file is part of the Haven & Hearth game client.
 *  Copyright (C) 2009 Fredrik Tolf <fredrik@dolda2000.com>, and
 *                     Björn Johannessen <johannessen.bjorn@gmail.com>
 *
 *  Redistribution and/or modification of this file is subject to the
 *  terms of the GNU Lesser General Public License, version 3, as
 *  published by the Free Software Foundation.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  Other parts of this source tree adhere to other copying
 *  rights. Please see the file `COPYING' in the root directory of the
 *  source tree for details.
 *
 *  A copy the GNU Lesser General Public License is distributed along
 *  with the source tree of which this file is a part in the file
 *  `doc/LPGL-3'. If it is missing for any reason, please see the Free
 *  Software Foundation's website at <http://www.fsf.org/>, or write
 *  to the Free Software Foundation, Inc., 59 Temple Place, Suite 330,
 *  Boston, MA 02111-1307 USA
 */

package haven;

import java.awt.Color;
import java.awt.Font;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// addon: font provider facade (F-series, D-043) — driven from io.brodgar.addon.FontApi.
/**
 * The per-addon font provider (spec {@code specs/addons/21-fonts.md}, D-043). This is the
 * <b>{@code haven}-reachable facade</b> that routed render sites call: they replace their inline
 * {@code new Text.Foundry(...)} with {@link #foundry(String, Text.Foundry)}, tagged {@code // addon:}, and
 * the provider resolves the current foundry for that named <b>scope</b> — an addon-installed override, or the
 * site's stock foundry when none is set. It also holds the <b>owner-tagged override registry</b> and the
 * <b>generation counter</b>; the Lua surface is the <b>stylesheet</b> {@code hafen.ui.skin{…}}
 * ({@code io.brodgar.addon.Sheet}, 033.1 — a font is one property of a rule, and {@code hafen.font.setFont} is a
 * hard cut), which drives this class ({@link #push}/{@link #reset}/{@link #removeOwner}); an addon's entries are
 * reverted on teardown by {@code io.brodgar.addon.FontApi} (owned-resource model, spec 05). <b>Nothing about the
 * render sites changed when the sheet arrived</b> — only who fills the stack.
 *
 * <p><b>Resolution</b> is most-specific first: the <b>per-widget style</b> of the widget being drawn or of one of
 * its ancestors (F5, {@link #frame(Widget)} — its {@code widget:skin{…}} over the <b>tree rule</b> it matches,
 * folded into one style by {@code io.brodgar.addon.Sheet} before it ever gets here) &rarr; a scope's own
 * override (top of its owner-tagged stack) &rarr; the {@code "default"} override &rarr; the site's stock foundry. So a
 * sheet's {@code ["*"]} rule cascades to every routed surface that has no more-specific rule, a rule keyed on one
 * site refines that surface, a rule keyed on a widget refines that widget's subtree, and {@code widget:skin{…}}
 * refines one widget subtree by hand.
 *
 * <p><b>Every step of that chain composes PER PROPERTY</b> ({@link #combine}), which is what makes it a cascade
 * rather than a series of replacements: a tree rule that sets only {@code color} leaves the scope rule's font in
 * place, and a {@code widget:skin{font=…}} leaves the sheet's colour alone. A level only ever takes the properties
 * it actually names.
 *
 * <p><b>Cost.</b> When no addon has installed <i>any</i> override (the overwhelmingly common case) {@link #foundry}
 * returns the stock foundry after a single {@code volatile} read — no lock, no allocation. Only once an override
 * exists does it take the registry lock to resolve. Routed sites that cache a rendered {@code Text} (a
 * {@link Label}, a window caption) re-render when {@link #gen()} moves; {@code Text.render(…)}-style statics
 * rebuild every call anyway, so they simply ask the provider each time.
 *
 * <p><b>Owner tokens are opaque.</b> An override is tagged with the owning addon as a bare {@link Object} (its
 * {@code io.brodgar.addon.Addon} instance), so this core class carries <b>no</b> dependency on the addon package —
 * it only ever compares owners by identity. Not instantiable.
 */
public class Fonts {
    private Fonts() {}

    /**
     * The full enumerated set of named client surfaces (declared in full from F1, D-043). A scope becomes
     * <i>effective</i> only once its render site is routed through {@link #foundry} in its slice; until then an
     * override on it is simply inert (the API never changes, only coverage grows). {@code "default"} (F1) is the
     * broad hammer — the cascade fallback for every unset scope.
     */
    public static final String[] SCOPES = {
        "default",        // F1  — global fallback (Text.std / Text.render / Label default)
        "window.title",   // F3  — window captions (Window.DefaultDeco)
        "window.frame",   // C2  — the window CHROME the deco paints (035; bg/border, not a text site)
        "panel",          // C2  — the window-LESS 9-slice panels (035.3; an IBox, not a text site)
        "heading",        // F3e — in-window section headings (CharWnd.catf/failf, GridList.dcatf)
        "button",         // F3  — button captions
        "label",          // F3  — explicit non-default labels
        "tooltip",        // F3  — tooltips
        "menu",           // F3  — flower/context menus
        "chat",           // F3  — chat text
        "textentry",      // F3  — text-entry fields
        "world.nick",     // F4  — floating player/kin names
        "world.speech",   // F4  — speech bubbles
        "inventory.slot", // C2  — the empty square an inventory grid is paved with (065.7; bg/border, no text)
        "checkbox",       // C2  — the box a checkbox draws (065.10; bg/border, no text)
        "checkbox.mark",  // C2  — ...and the tick inside it
        "scrollbar",      // C2  — the rail a scrollbar draws
        "scrollbar.knob", // C2  — ...and the thumb that runs along it
        "slider",         // C2  — the rail a slider draws
        "slider.knob",    // C2  — ...and its thumb
    };

    /**
     * The resolved style for a scope, for render sites whose font is <b>not</b> a {@link Text.Foundry} — F3d's
     * {@link RichText.Foundry} sites (the chat foundry, the menu-grid tooltip foundry), which are built from AWT
     * text attributes and, in chat's case, from a {@code Parser} subclass a generic {@code derive} would drop. Such
     * a site asks {@link Fonts#style(String)} for its scope: {@code null} means <i>nothing overrides this</i> (keep
     * the stock foundry, identity intact), otherwise it composes its own foundry from the three resolved scalars,
     * each of which falls back to the value the site passes in. The same per-stock caching as
     * {@link Fonts#foundry(String, Text.Foundry)} applies, so a site may call this every frame.
     */
    public interface Style {
        /** The font to render with, at the size the override asks for — or, when it carries none, {@code stock}'s size. */
        Font font(Font stock);
        /**
         * The override's colour, or {@code stock} when it carries none. Pass {@code null} to <b>ask whether</b> the
         * sheet set one at all (033.2): a rich site that must decide between deriving a {@code FOREGROUND}
         * attribute and leaving its own defaults alone reads {@code color(null)} and skips the attribute on
         * {@code null}.
         */
        Color color(Color stock);
        /** The override's antialias flag, or {@code stock} when it carries none. */
        boolean aa(boolean stock);
        /**
         * The rule's <b>chrome</b> property named {@code key} — {@code "bg"}, {@code "border"},
         * {@code "padding"} — or {@code null} when the rule names none (065.1). <b>Opaque</b>: every one of them
         * is plain parsed data the addon layer defines and only the addon layer reads back; this class never
         * looks inside one, exactly as it never looks inside an owner token, and it does not know the key set
         * either. What consumes them is the sheet-fed chrome ({@code io.brodgar.addon.Chrome} and its two
         * consumers); every text site ignores them.
         *
         * <p><b>A bag rather than a field per property</b>, because that is what keeps a <i>new</i> theme
         * property out of this class: a parser, a slot in the addon layer's own record and a reader at the site,
         * with nothing here to widen. The half of a style {@code haven} itself reads — the font and its colour —
         * stays typed above, because building a foundry out of string lookups would buy nothing.
         *
         * <p>The values are in the units the addon layer wrote them in ({@code padding} is <b>design</b> px, like
         * a border's slice), which is that layer's business too: this class only carries them.
         */
        Object prop(String key);
    }

    /**
     * One installed override: the resolved style of a <b>stylesheet rule</b> for one scope, tagged with its owner.
     * Immutable once pushed. Every field is independently optional, because a rule is: {@code base}/{@code size}/
     * {@code aa} come from its {@code font} property and {@code color} from its {@code color} property (033.2), so a
     * colour-only rule carries a {@code null} {@code base} and the site simply keeps its own font.
     */
    private static final class Spec implements Style {
        final Object owner;      // the owning io.brodgar.addon.Addon (compared by identity only)
        final Font   base;       // the AWT font, with bold/italic already baked in (size applied per-site), or null = keep the site's own font
        final Integer size;      // logical px (UI.scale is applied when a foundry is built), or null = use the site's stock size
        final Boolean aa;        // or null = inherit the site's stock antialias flag
        final Color  color;      // the rule's `color` property, or null = inherit the site's stock default colour
        // addon: (065.1) the rule's CHROME half, one immutable bag keyed by property name and opaque to this
        // class -- see Style.prop(). Null when the rule names no chrome property at all.
        final Map<String, Object> props;
        // A per-Spec stamp mixed into gen() while this override is the active per-widget FRAME (F5). It is what
        // makes a site's `gen != mygen` check fire for a widget CONSTRUCTED outside the frame and first drawn inside
        // it (and vice versa) -- without it, a label created after the skin would keep its stock font forever,
        // since the global counter had not moved since its construction.
        final int    stamp;
        // Lazily-built foundries, keyed by the STOCK foundry identity (one override may front several sites whose
        // stock size/aa/colour differ). Guarded by `this`.
        private final Map<Text.Foundry, Text.Foundry> cache = new IdentityHashMap<Text.Foundry, Text.Foundry>();
        // Likewise for the raw fonts handed out through Style.font(Font) (F3d's RichText sites), keyed by the
        // STOCK font identity so each site keeps its own size. Guarded by `this`.
        private final Map<Font, Font> fcache = new IdentityHashMap<Font, Font>();

        Spec(Object owner, Font base, Integer size, Boolean aa, Color color, Map<String, Object> props) {
            this.owner = owner; this.base = base; this.size = size; this.aa = aa; this.color = color;
            // Immutable from here on: a Spec is shared by every site it fronts and read from the draw thread.
            this.props = ((props == null) || props.isEmpty()) ? null
                : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(props));
            this.stamp = (++stampseq) * 0x9E3779B1;   // a distinct odd multiplier per Spec (built under Fonts.class)
        }

        synchronized Text.Foundry foundry(Text.Foundry stock) {
            // addon: (035.1/065.1) a rule that names only chrome says nothing about text: hand the site its OWN
            // foundry back, so a `["*"] = {bg=…}` sheet leaves every text surface byte-for-byte stock. The test is
            // the four TYPED fields, which are the whole of what a foundry is built from.
            if((base == null) && (size == null) && (aa == null) && (color == null))
                return stock;
            Text.Foundry c = cache.get(stock);
            if(c != null)
                return c;
            float px = (size != null) ? UI.scale((float)size.intValue())
                                      : stock.font.getSize2D();   // the stock font is already UI.scale'd
            Font f = derive(stock.font, px);
            Color col = (color != null) ? color : stock.defcol;
            boolean a = (aa != null) ? aa.booleanValue() : stock.aa;
            Text.Foundry made = new Text.Foundry(f, col).aa(a);   // (Font, Color) ctor does NOT re-scale — px is final
            made.noresolve = true;   // a provider-built foundry must not resolve itself again (would recurse)
            made.fixcol = color;     // addon: (033.2) a `color` rule outranks the colour the SITE passes to render()
            cache.put(stock, made);
            return made;
        }

        public synchronized Font font(Font stock) {
            Font c = fcache.get(stock);
            if(c != null)
                return c;
            float px = (size != null) ? UI.scale((float)size.intValue())
                                      : stock.getSize2D();   // the stock font is already UI.scale'd
            Font made = derive(stock, px);
            fcache.put(stock, made);
            return made;
        }

        /**
         * This override's font at {@code px}, over the site's {@code stock} font. A colour-only rule has no
         * {@code base}, so it keeps the site's own font — and, when it asks for no size either, hands back
         * {@code stock} <b>itself</b>, which is what lets a rich site tell "nothing to re-derive" by identity.
         */
        private Font derive(Font stock, float px) {
            if(base != null)
                return base.deriveFont(px);
            return (size != null) ? stock.deriveFont(px) : stock;
        }

        public Color color(Color stock)  {return((color != null) ? color : stock);}
        public boolean aa(boolean stock) {return((aa != null) ? aa.booleanValue() : stock);}
        public Object prop(String key)   {return((props == null) ? null : props.get(key));}
    }

    // scope -> owner-tagged override stack (last = top = current). Guarded by `Fonts.class`.
    private static final Map<String, List<Spec>> overrides = new LinkedHashMap<String, List<Spec>>();
    private static volatile int gen = 0;
    private static volatile boolean active = false;   // any override installed anywhere → foundry() takes the slow path
    private static int stampseq = 0;                  // Spec.stamp source (guarded by `Fonts.class`)
    // addon: (034.2) `inner ⊕ outer` interned by the identity of the two, so the composed Spec -- and therefore its
    // stamp -- is the SAME object every frame for the same pair. Minting one per resolve would give every routed
    // site a new generation every frame and rebuild the whole client 60 times a second (the F5 stamp rule).
    // Guarded by `Fonts.class`, dropped whenever `gen` moves (everything rebuilds then anyway).
    private static final Map<Spec, Map<Spec, Spec>> combos = new IdentityHashMap<Spec, Map<Spec, Spec>>();

    /**
     * {@code inner} over {@code outer}, <b>property by property</b>: {@code inner} keeps every property it names and
     * {@code outer} fills the rest. This is the one place the resolution chain is composed, and the reason a level of
     * it never silently drops the level beneath — a tree rule of {@code {color=…}} inside a sheet whose {@code ["*"]}
     * sets the font must not put that subtree back on the stock font. Interned (see {@link #combos}) because the
     * result's {@link Spec#stamp} has to be stable across frames. Caller need not hold the lock.
     */
    private static synchronized Spec combine(Spec inner, Spec outer) {
        if(inner == null)
            return outer;
        if(outer == null)
            return inner;                 // nothing left for the outer one to fill in
        Map<Spec, Spec> m = combos.get(inner);
        if(m == null)
            combos.put(inner, m = new IdentityHashMap<Spec, Spec>());
        Spec c = m.get(outer);
        if(c == null) {
            m.put(outer, c = new Spec(inner.owner,
                                      (inner.base   != null) ? inner.base   : outer.base,
                                      (inner.size   != null) ? inner.size   : outer.size,
                                      (inner.aa     != null) ? inner.aa     : outer.aa,
                                      (inner.color  != null) ? inner.color  : outer.color,
                                      fold(inner.props, outer.props)));
        }
        return c;
    }

    /**
     * The chrome half of {@link #combine}, <b>key by key</b> (065.1): {@code inner} keeps every property it
     * names and {@code outer} fills the rest — the very fold the typed fields above get, now honest for any key
     * a theme property is ever added under, and the reason a bag costs the cascade nothing. Neither argument is
     * modified: both are already shared by whatever is holding them.
     */
    private static Map<String, Object> fold(Map<String, Object> inner, Map<String, Object> outer) {
        if((outer == null) || outer.isEmpty())
            return inner;
        if((inner == null) || inner.isEmpty())
            return outer;
        Map<String, Object> out = new LinkedHashMap<String, Object>(outer);
        out.putAll(inner);
        return out;
    }

    /**
     * The provider primitive a routed render site calls (F1: {@code "default"} consumers). Resolves the current
     * foundry for {@code scope} given the site's {@code stock} foundry as the fallback: a scope override, else the
     * {@code "default"} cascade, else {@code stock} itself. Cheap when no addon has overridden any font (a single
     * {@code volatile} read → {@code stock}).
     */
    public static Text.Foundry foundry(String scope, Text.Foundry stock) {
        if(!active)
            return stock;                 // fast path: no override anywhere
        return resolve(scope, stock);
    }

    /** {@link #foundry(String, Text.Foundry)} with the built-in stock for {@code scope} ({@code "default"} → {@link Text#std}). */
    public static Text.Foundry foundry(String scope) {
        return foundry(scope, Text.std);
    }

    /**
     * The resolved {@link Style} for {@code scope}, or {@code null} when <b>no</b> override applies (neither the
     * scope's own nor the {@code "default"} cascade) — the primitive for render sites whose foundry is not a
     * {@link Text.Foundry} (F3d: {@link RichText.Foundry} chat / tooltip sites). A {@code null} return is the
     * site's cue to keep its stock foundry untouched (identity preserved), exactly like
     * {@link #foundry(String, Text.Foundry)} returning the stock it was given.
     */
    public static Style style(String scope) {
        if(!active)
            return null;                  // fast path: no override anywhere
        return resolveStyle(scope);
    }

    private static synchronized Style resolveStyle(String scope) {
        return combine(frameTop(), scopeTop(scope));
    }

    /**
     * The style {@code scope} resolves to <b>for one named widget</b> — {@code wdg}'s own per-widget style (its
     * tree rule / {@code widget:skin}) over the scope's stack, {@code null} when nothing applies. Added by 035.1
     * for the sheet-fed window chrome, which has to ask the question <b>outside</b> a draw ({@code Window.tick}
     * decides whether to swap the deco) and therefore cannot read the thread-local frame {@link #style(String)}
     * uses. Same chain, same per-property {@link #combine}; only the frame is named rather than ambient.
     *
     * <p>{@code treeTop} is resolved <b>before</b> the lock is taken: it calls into the style source, and this
     * class never holds {@code Fonts.class} across that call.
     */
    public static Style styleFor(String scope, Widget wdg) {
        if(!active)
            return null;                  // fast path: no override anywhere
        Spec t = treed ? treeTop(wdg) : null;
        return resolveWith(t, scope);
    }

    private static synchronized Spec resolveWith(Spec tree, String scope) {
        return combine(tree, scopeTop(scope));
    }

    /**
     * Has <b>any</b> addon installed <b>any</b> override (a scope entry or a per-widget style)? The addon
     * layer's own cheap gate: with nothing installed there is nothing this class could resolve, so a caller that
     * polls per widget per frame (035.1's chrome check) can skip the question entirely.
     */
    public static boolean styled() {
        return active;
    }

    /* ---- the window-LESS panels (035.3/C2) ---------------------------------------------------------------
     *
     * 035.1 gave the window chrome to the sheet through Window.Deco, a seam that only windows have. The rest of
     * the client's framed surfaces -- a Frame around a list, a flower-menu petal, a dropdown menu, an ISBox --
     * own no Deco: they draw an IBox themselves, and IBox is ALREADY an interface (draw(g, tl, sz)), so 9-slice
     * is a first-class engine concept here and a sheet-fed implementation simply drops in where they build
     * theirs. That is the whole mechanism: a routed panel asks box() for the box it should draw with, and gets
     * its OWN stock one back whenever no rule names it.
     *
     * Two things it deliberately does NOT do. It does not move anything: the six geometry methods of the
     * returned box answer exactly what the stock box answers, because a panel's size and its children's places
     * were decided when it was built and nothing re-lays it out -- so `pad`, and a border's own insets, are
     * inert here (the doctrine's geometry row: a size-changing property applies only where the surface owns its
     * geometry). And it does not paint the SURFACE at the same moment as the frame: several panels draw their
     * contents before their box (Frame draws its children first), so the `bg` half is a separate call the site
     * places where its own draw order needs it.
     */

    /**
     * The sheet-fed 9-slice a routed panel draws with (035.3). It is an {@link IBox}, so the site's own
     * {@code box.draw(g, tl, sz)} is unchanged; what it adds is the {@code bg} half, which a panel has to paint
     * at a different moment than its frame.
     */
    public interface Box extends IBox {
        /**
         * Paint the rule's {@code bg} over this panel and answer {@code true}; {@code false} when the rule names
         * none, which is the site's cue to draw its own stock surface exactly as before.
         */
        public boolean drawbg(GOut g, Coord tl, Coord sz);
    }

    /** The panel-box source (035.3) — installed once by the addon layer; {@code null} in a stock client. */
    public interface Boxes {
        /** The box {@code wdg}'s panel should draw with, or {@code null} when no rule names it. */
        public Box box(String scope, Widget wdg, IBox stock);
    }
    private static volatile Boxes boxes = null;

    /** Install the panel-box source (the addon layer, once). */
    public static void boxes(Boxes src) {
        boxes = src;
    }

    /**
     * The box a routed panel should draw with: a sheet-fed one when a rule names this widget's panel, else
     * {@code stock} <b>itself</b> — so an addon-less client draws byte-for-byte the box it always drew, after a
     * single {@code volatile} read. Resolution is the same chain the window chrome uses
     * ({@link #styleFor(String, Widget)}): the widget's own per-widget style over the scope's stack, folded per
     * property. Never {@code null}, and the sheet-fed boxes are <b>interned</b> per (style, stock box), so a
     * panel that asks every frame allocates nothing.
     */
    public static IBox box(String scope, Widget wdg, IBox stock) {
        if(!active)
            return stock;                 // fast path: no override anywhere
        Boxes src = boxes;
        if(src == null)
            return stock;
        Box b = src.box(scope, wdg, stock);
        return (b == null) ? stock : b;
    }

    /**
     * Paint the sheet's own surface under a panel, if its rule names one — {@code false} means it does not, and
     * the site draws whatever background it always drew. Written as a static over the {@link IBox} the site is
     * already holding so a routed panel needs no null test and no cast of its own.
     */
    public static boolean drawbg(IBox box, GOut g, Coord tl, Coord sz) {
        return (box instanceof Box) && ((Box)box).drawbg(g, tl, sz);
    }

    /* ---- a box the CLIENT sizes and a rule dresses (065.4) ------------------------------------------------
     *
     * A few of the client's surfaces paint a box whose GEOMETRY they compute themselves and whose art is a
     * constant -- the plate behind a window's caption is the first, sized by DefaultDeco.checkcap from the
     * caption's own width. Such a site owns no IBox to stand in for, so box() above cannot serve it: it hands
     * over the rectangle instead and asks whether a rule painted it.
     */

    /**
     * The paint one site's rule does, <b>held</b> rather than spent (065.7) — what {@link #chrome} hands a site
     * that draws the same box many times in one pass. An inventory's grid is one rectangle per square, and
     * asking {@link #drawchrome} per square would resolve the rule (and take the registry lock) per square per
     * frame; so the site resolves once, outside its loop, and paints from the answer.
     *
     * <p>Opaque, like every other value the addon layer parks in a style: this class only carries it. The
     * instances are interned per resolved style by the layer that makes them, so a site that asks every frame
     * allocates nothing.
     */
    public interface Chrome {
        /** Paint this site's own surface and frame over {@code [ul, ul+sz)} — device pixels. */
        public void draw(GOut g, Coord ul, Coord sz);

        /*
         * 065.8 -- ...and the two halves apart, for a site whose own content sits BETWEEN them. A control
         * rasterises its caption over its fill and under its frame (Button.draw(BufferedImage) draws the centre
         * texture, then the caption, then the four edges), so it paints the surface, blits its own picture and
         * paints the frame over it. The two predicates are asked where there is no GOut yet: a site composing
         * its raster has to know which of its own pieces the rule replaces before it draws any of them.
         */

        /** Does this rule paint a surface of its own? Then the site skips whatever fill it draws itself. */
        public boolean bg();
        /** ...and a frame of its own? Then the site skips its own edges. */
        public boolean border();
        /** Paint the surface alone over {@code [ul, ul+sz)} — device pixels. */
        public void drawbg(GOut g, Coord ul, Coord sz);
        /** Paint the frame alone over {@code [ul, ul+sz)} — device pixels. */
        public void drawborder(GOut g, Coord ul, Coord sz);
    }

    /** The chrome-paint source (065.4) — installed once by the addon layer; {@code null} in a stock client. */
    public interface Chromes {
        /** Paint {@code scope}'s own surface and frame over {@code [ul, ul+sz)}; {@code false} when no rule names it. */
        public boolean draw(String scope, Widget wdg, GOut g, Coord ul, Coord sz);
        /** The room {@code scope}'s own padding asks for, as {@code {tl, br}} in device px, or {@code null}. */
        public Coord[] pad(String scope, Widget wdg);
        /** {@code scope}'s own paint in {@code state} ({@code null} = at rest), or {@code null} when no rule names it. */
        public Chrome chrome(String scope, Widget wdg, String state);
        /** {@code scope}'s own paint with <b>no widget</b> to resolve against, or {@code null} when no rule names it. */
        public Chrome chrome(String scope);
        /** The size {@code scope}'s own background art asks for, in device px, or {@code null}. */
        public Coord size(String scope);
    }
    private static volatile Chromes chromes = null;

    /** Install the chrome-paint source (the addon layer, once). */
    public static void chromes(Chromes src) {
        chromes = src;
    }

    /**
     * Paint the rule's own surface and frame over a rectangle the site computed — {@code false} means no rule
     * names this site and the site paints whatever it always painted, which is the answer a stock client always
     * gets, after a single {@code volatile} read.
     */
    public static boolean drawchrome(String scope, Widget wdg, GOut g, Coord ul, Coord sz) {
        if(!active)
            return false;                 // fast path: no override anywhere
        Chromes src = chromes;
        return (src != null) && src.draw(scope, wdg, g, ul, sz);
    }

    /**
     * The room a rule's {@code padding} asks for at {@code scope}, as {@code {tl, br}} in <b>device</b> pixels —
     * {@code null} means no rule names one and the site keeps whatever margin it always kept (065.6).
     *
     * <p>It is {@link #drawchrome}'s partner for a site that computes its own box: the box has to be widened
     * before there is a rectangle to paint, so the two answers are read one after the other rather than
     * together. Behind the same {@code active} volatile read, so a client with no addon pays one.
     */
    public static Coord[] chromepad(String scope, Widget wdg) {
        if(!active)
            return null;                  // fast path: no override anywhere
        Chromes src = chromes;
        return (src == null) ? null : src.pad(scope, wdg);
    }

    /**
     * The paint {@code scope}'s rule does, resolved <b>once</b> for a site that draws many boxes in one pass —
     * {@code null} means no rule names it and the site paints exactly what it always painted (065.7).
     *
     * <p>Same answer as {@link #drawchrome}, read at the top of a loop rather than inside it. A site with one
     * box to paint has nothing to gain from it and asks the other; a grid does, and asks this. Behind the same
     * {@code active} volatile read, so a client with no addon pays one per draw rather than one per cell.
     */
    public static Chrome chrome(String scope, Widget wdg) {
        return chrome(scope, wdg, null);
    }

    /**
     * The paint {@code scope}'s rule does in one <b>state</b> (065.8) — {@code "hover"}, {@code "pressed"},
     * {@code "disabled"}, {@code "checked"}, or {@code null} for the surface at rest.
     *
     * <p>A state face rides inside the value a rule wrote rather than in the key it wrote it under, so a site
     * asks for the state it is in and gets one answer, already resolved; a state the rule says nothing about
     * hands back the value it varies. Nothing publishes a state to the cascade, and a state a surface never
     * enters costs it nothing at all.
     */
    public static Chrome chrome(String scope, Widget wdg, String state) {
        if(!active)
            return null;                  // fast path: no override anywhere
        Chromes src = chromes;
        return (src == null) ? null : src.chrome(scope, wdg, state);
    }

    /**
     * The paint {@code scope}'s rule does with <b>no widget</b> to resolve it against (065.11) — {@code null}
     * when no rule names it and the site paints exactly what it always painted.
     *
     * <p>The two above take the widget whose tree rule and whose draw frame outrank the scope; this one is for
     * a surface that <b>is no widget</b>. A speech bubble is drawn by a {@link Speaking}, a {@link GAttrib} on
     * a {@link Gob} in the 3D view, so there is no widget to name and nothing to hand over — it asks the site
     * half of the cascade alone, the path {@link #style(String)} takes and the very path its own font already
     * resolves through {@link #foundry(String, Text.Foundry)}. So both halves of such a key answer off one
     * chain, and neither can drift from the other.
     */
    public static Chrome chrome(String scope) {
        if(!active)
            return null;                  // fast path: no override anywhere
        Chromes src = chromes;
        return (src == null) ? null : src.chrome(scope);
    }

    /**
     * The size {@code scope}'s own background asks for, in <b>device</b> pixels — {@code null} when no rule
     * names one, and when the one it names is a flat colour, which has no size of its own to give (065.9).
     *
     * <p>It is the <b>widget-less</b> member of the three above, and that is the whole of what distinguishes
     * it: a control that measures itself from its own background does so in its <b>constructor</b>
     * ({@link TextEntry}), where there is no widget yet to resolve a tree rule against and no draw in
     * progress to carry a frame. So it asks the site half of the cascade alone, the path {@link #style(String)}
     * takes, and a site that has a widget in hand asks {@link #chrome} instead.
     */
    public static Coord chromesz(String scope) {
        if(!active)
            return null;                  // fast path: no override anywhere
        Chromes src = chromes;
        return (src == null) ? null : src.size(scope);
    }

    private static synchronized Text.Foundry resolve(String scope, Text.Foundry stock) {
        // F5/C1b: the frame (this widget's own skin, its tree rule, or an enclosing widget's) outranks
        // every scope -- but only for the properties it names; combine() lets the scope fill the rest.
        Spec o = combine(frameTop(), scopeTop(scope));
        return (o == null) ? stock : o.foundry(stock);
    }

    /** The override a scope resolves to on its own: its own stack, else the {@code "default"} cascade. */
    private static Spec scopeTop(String scope) {
        Spec o = top(scope);
        if((o == null) && !"default".equals(scope))
            o = top("default");           // cascade: an unset scope falls back to the "default" override
        return o;
    }

    /** The current top-of-stack override for {@code scope}, or {@code null}. Caller holds {@code Fonts.class}. */
    private static Spec top(String scope) {
        List<Spec> st = overrides.get(scope);
        return ((st == null) || st.isEmpty()) ? null : st.get(st.size() - 1);
    }

    /**
     * The current generation counter; bumped on every {@link #push}/{@link #reset}/{@link #removeOwner}. A routed
     * site caches the value it last rendered at and rebuilds when it moves.
     *
     * <p>While a <b>per-widget frame</b> is active (F5 — the widget being drawn, or an ancestor, carries a tree rule
     * or a {@code widget:skin}) the reported generation additionally carries that style's
     * {@link Spec#stamp}. That is what makes the very same {@code gen != mygen} check every routed site already
     * performs also detect <i>where</i> it is being drawn: a widget built outside the frame (the common case — a
     * label constructed long before, or one created inside an already-styled window) sees a different
     * generation on its first draw inside the frame, re-resolves, and picks the style up. It is stable
     * across frames, so there is no per-frame rebuild.
     */
    public static int gen() {
        Spec f = frameTop();
        return (f == null) ? gen : (gen ^ f.stamp);
    }

    /* ---- the PER-WIDGET frame (F5, widened by 034.2/C1b) ---------------------------------------------------
     *
     * A style may name ONE widget rather than a render site -- a stylesheet TREE rule (`["window[title=X]"]`) or an
     * addon's `widget:skin{…}` on a widget it picked by hand. Either way it restyles that widget AND everything
     * drawn inside it, while its siblings keep the scope/default style, and it sits at the top of the resolution
     * chain.
     *
     * The mechanism is dynamic, like F3d's composition scope, rather than a per-widget field: the UI draw pass
     * already descends the tree parent-first, so the ONE place that knows "we are now inside widget W" is the
     * child-draw loop (Widget.draw(GOut, boolean)). It opens a FRAME around each child that carries a style
     * (`frame(Widget)`), the frame stays in force for the child's whole subtree -- a child with no style of its
     * own simply inherits the enclosing one -- and every routed site resolves through it because resolve() consults
     * frameTop() first. So no render site needs a second edit: every scope routed by F1..F4 is per-widget capable
     * for free, and even text drawn by PUBLISHED resource code follows (via `dynamic()` below).
     *
     * Where the styles themselves LIVE is the addon layer's business (io.brodgar.addon.Sheet, the TreeStyles source
     * below): both levels are resolved per widget and folded into one style there, so this class holds no per-widget
     * registry at all and cannot disagree with the one that does.
     */
    private static final ThreadLocal<List<Spec>> frames = new ThreadLocal<List<Spec>>();

    /**
     * A per-widget style frame opened around one widget's draw (F5) — {@code close()} ends it. Not
     * {@code AutoCloseable} itself so a caller needs no {@code catch}: it is meant to be used as
     * {@code try(Fonts.Frame f = Fonts.frame(wdg)) {…}} in the widget draw loop.
     */
    public interface Frame extends AutoCloseable {
        public void close();
    }
    /** The frame for a widget with no style of its own: pushes nothing, so an enclosing frame stays in force. */
    private static final Frame NOFRAME = new Frame() {
        public void close() {}
    };
    /** The frame for a widget that DOES carry a style: {@link #frame} pushed it, {@code close()} pops it. */
    private static final Frame POPFRAME = new Frame() {
        public void close() {
            List<Spec> st = frames.get();
            if((st != null) && !st.isEmpty())
                st.remove(st.size() - 1);
        }
    };

    /**
     * Open the per-widget style frame for {@code wdg} (F5) — called by the widget draw loop around every child's
     * {@code draw}, and by {@link UI#draw} around the root. Everything rendered until the returned {@link Frame} is
     * closed (so {@code wdg} <i>and its whole subtree</i>) resolves through {@code wdg}'s style, if it has one;
     * otherwise the enclosing frame (a styled ancestor), if any, stays in force. <b>Always</b> use it in a
     * {@code try}-with-resources. Free when no addon has installed any per-widget style (one {@code volatile}
     * read → a shared no-op frame).
     */
    public static Frame frame(Widget wdg) {
        if(!treed)
            return NOFRAME;                     // fast path: no tree rule and no widget:skin anywhere
        Spec s = treeTop(wdg);
        if(s == null)
            return NOFRAME;                     // nothing styles THIS widget → inherit the enclosing frame
        List<Spec> st = frames.get();
        if(st == null)
            frames.set(st = new ArrayList<Spec>(4));
        if(!st.isEmpty())
            s = combine(s, st.get(st.size() - 1));   // an enclosing frame still fills what this one does not name
        st.add(s);
        return POPFRAME;
    }

    /** The innermost frame style in force on this thread right now, or {@code null}. */
    private static Spec frameTop() {
        if(!treed)
            return null;
        List<Spec> st = frames.get();
        return ((st == null) || st.isEmpty()) ? null : st.get(st.size() - 1);
    }

    /* ---- the PER-WIDGET style source (034.2/034.3, C1b) ---------------------------------------------------
     *
     * A style that names WIDGETS rather than a render site -- a stylesheet key like `["window[title=Cupboard]"]`, or
     * an addon's `widget:skin{…}` on one widget -- is resolved per widget against the live tree by
     * io.brodgar.addon.Sheet, which folds every level that reaches one widget into a single style. This is where
     * that style meets the draw: the frame the child-draw loop already opens for F5 asks the source for the widget
     * it is about to draw, so the style covers that widget AND its subtree, every routed site becomes per-widget
     * capable with no second edit, and there is no second resolution path to disagree with this one.
     *
     * The source hands back an OPAQUE Style built by treeSpec() below -- a Spec, but the addon layer never says so:
     * this class keeps carrying no dependency on the addon package, exactly as with the owner tokens. It must be a
     * CACHE LOOKUP: it is called for every visible widget on every frame.
     */

    /** The per-widget style source (034.2) — installed once by the addon layer; {@code null} in a stock client. */
    public interface TreeStyles {
        /** The style {@code wdg} resolves to, as built by {@link Fonts#treeSpec}, or {@code null} for none. */
        public Style styleFor(Widget wdg);
    }
    private static volatile TreeStyles trees = null;
    private static volatile boolean treed = false;    // any per-widget style installed → frame() asks the source

    /** Install the per-widget style source (the addon layer, once). */
    public static void treeStyles(TreeStyles src) {
        trees = src;
    }

    /**
     * The addon layer telling the provider whether <b>any</b> per-widget style is installed right now (a tree rule
     * or a {@code widget:skin}). It is the whole of what this class knows about them: with nothing installed,
     * {@link #frame} and {@link #frameTop} are a {@code volatile} read and the client is byte-for-byte stock again.
     */
    public static synchronized void treeActive(boolean any) {
        treed = any;
        if(any)
            active = true;
        else
            prune();
        bumped();
    }

    /**
     * Build the provider's own representation of one resolved per-widget style — the value
     * {@link TreeStyles#styleFor} hands back. The addon layer <b>interns</b> these per resolved style (same
     * properties ⇒ the same object), which is what makes the {@link Spec#stamp} mixed into {@link #gen()} stable
     * across frames.
     */
    public static synchronized Style treeSpec(Object owner, Font base, Integer size, Boolean aa, Color color,
                                              Map<String, Object> props) {
        return new Spec(owner, base, size, aa, color, props);
    }

    /** {@code wdg}'s resolved per-widget style, or {@code null}. Takes the source's lock, never {@code Fonts.class}. */
    private static Spec treeTop(Widget wdg) {
        TreeStyles src = trees;
        if(src == null)
            return null;
        Style st = src.styleFor(wdg);
        return (st instanceof Spec) ? (Spec)st : null;
    }

    /* ---- the dynamic COMPOSITION scope (F3d amendment) ----------------------------------------------------
     *
     * Some client text is rendered by code we cannot route: the ItemInfo tooltips are composed partly by
     * PUBLISHED CODE that ships inside the resources themselves (e.g. `ui/tt/q/qbuff`, which draws the
     * "Quality: 31.7" row), and it renders through the generic {@link Text#render(String, Color)} /
     * {@link RichText#render(Document, int)} statics -- which F1 bound to the {@code "default"} scope. A
     * per-scope override could therefore never reach it.
     *
     * The answer is a scope that is dynamic rather than lexical: the composer ({@code ItemInfo.longtip} and
     * friends) declares "everything rendered from here down is tooltip text" by entering the scope, and the
     * generic statics ask {@link #scope()} instead of hard-coding {@code "default"}. Published code needs no
     * cooperation and stays untouched; text rendered anywhere else is unaffected. The stack is per-thread
     * (composition happens on the UI thread, but a ThreadLocal makes that an assumption we do not depend on)
     * and is only ever consulted when an override actually exists.
     */
    private static final ThreadLocal<List<String>> dynscope = new ThreadLocal<List<String>>();

    /**
     * Declare that text rendered from here until the matching {@link #exit()} belongs to {@code scope} — so the
     * generic {@code Text.render}/{@code RichText.render} statics resolve it instead of {@code "default"}.
     * <b>Always</b> pair it in a {@code try/finally}. Nests.
     */
    public static void enter(String scope) {
        List<String> st = dynscope.get();
        if(st == null)
            dynscope.set(st = new ArrayList<String>(4));
        st.add(scope);
    }

    /** Leave the innermost {@link #enter(String)} scope. */
    public static void exit() {
        List<String> st = dynscope.get();
        if((st != null) && !st.isEmpty())
            st.remove(st.size() - 1);
    }

    /**
     * The scope the generic statics should resolve right now: the innermost {@link #enter(String)} scope, else
     * {@code "default"}. Cheap — a {@code volatile} read while no addon has installed any override.
     */
    public static String scope() {
        if(!active)
            return "default";
        List<String> st = dynscope.get();
        return ((st == null) || st.isEmpty()) ? "default" : st.get(st.size() - 1);
    }

    /**
     * The innermost {@link #enter(String)} scope, or {@code null} when there is none (or when no override exists
     * at all). This is the form a <b>foundry</b> asks for: a {@link Text.Foundry} built by code we cannot route —
     * published tip code inside a {@code .res} keeps its own private foundry — resolves through the provider
     * <i>while a composition scope is active</i>, with itself as the stock (so it keeps its own size and colour).
     * Outside a composition {@code null} means "render exactly as before", which is what keeps this invisible to
     * the rest of the client.
     *
     * <p>A <b>per-widget frame</b> (F5) claims such a foundry too, and reports {@code "default"} for it: inside
     * a styled widget the resolution chain returns the frame's style whatever scope is asked for, so this
     * is how a tree rule or a {@code widget:skin} reaches even the text a {@code .res}'s own code draws with its own
     * private foundry, without a second mechanism.
     */
    public static String dynamic() {
        if(!active)
            return null;
        List<String> st = dynscope.get();
        if((st != null) && !st.isEmpty())
            return st.get(st.size() - 1);
        return (frameTop() != null) ? "default" : null;   // addon: (F5) a per-widget frame claims unroutable foundries
    }

    /**
     * Install {@code owner}'s override on {@code scope} (one <b>site rule</b> of its {@code hafen.ui.skin} sheet).
     * An addon owns at most one override per scope — re-applying its sheet replaces the previous entry and
     * re-raises it to the top (last-wins). Bumps {@link #gen()} so routed sites/widgets rebuild. {@code base}
     * already carries any bold/italic, and is {@code null} when the rule sets no {@code font} at all (a
     * colour-only rule — the site keeps its own font); {@code size} is <b>logical</b> px (UI-scaled when the
     * foundry is built), {@code aa}/{@code color} are {@code null} to inherit the site's stock. {@code props} is
     * the opaque <b>chrome</b> bag (065.1), {@code null} on a rule that names none — a scope whose site paints no
     * chrome and owns no geometry simply never asks for a key in it, exactly as a chrome-only rule leaves every
     * text site alone.
     */
    public static synchronized void push(String scope, Object owner, Font base, Integer size, Boolean aa, Color color,
                                         Map<String, Object> props) {
        List<Spec> st = overrides.get(scope);
        if(st == null)
            overrides.put(scope, st = new ArrayList<Spec>());
        removeOwnerFrom(st, owner);       // an addon owns at most one override per scope
        st.add(new Spec(owner, base, size, aa, color, props));   // re-raise to the top (last applied wins)
        active = true;
        bumped();
    }

    /**
     * Drop {@code owner}'s override on {@code scope} (a site rule leaving its sheet, or the whole sheet being
     * dropped) — the surface falls back to the next owner beneath, or the stock foundry. Returns whether anything
     * was removed; bumps {@link #gen()} only when it was.
     */
    public static synchronized boolean reset(String scope, Object owner) {
        List<Spec> st = overrides.get(scope);
        boolean rm = (st != null) && removeOwnerFrom(st, owner);
        if(rm) {
            prune();
            bumped();
        }
        return rm;
    }

    /**
     * Remove every override owned by {@code owner} across all scopes (its teardown — reload/disable, spec 05). The
     * stock UI is always restorable this way: an addon's entries leave every scope stack and the surfaces fall back
     * beneath. Bumps {@link #gen()} when anything was removed. Its <b>per-widget</b> styles are not here — they live
     * in the source ({@code io.brodgar.addon.Sheet}), which drops them in the same teardown.
     */
    public static synchronized void removeOwner(Object owner) {
        boolean rm = false;
        for(List<Spec> st : overrides.values())
            rm |= removeOwnerFrom(st, owner);
        if(rm) {
            prune();
            bumped();
        }
    }

    /**
     * A rule changed: bump the generation every routed site rebuilds on, and drop the composed specs with it —
     * they are the OLD chain, and their stamps must not outlive it. Caller holds {@code Fonts.class}.
     */
    private static void bumped() {
        combos.clear();
        gen++;
    }

    private static boolean removeOwnerFrom(List<Spec> st, Object owner) {
        boolean rm = false;
        for(int i = st.size() - 1; i >= 0; i--) {
            if(st.get(i).owner == owner) {   // identity — owner tokens are opaque
                st.remove(i);
                rm = true;
            }
        }
        return rm;
    }

    /**
     * Recompute {@link #active} after a removal. It clears when nothing remains anywhere — no scope override and no
     * per-widget style — putting every routed site back on its zero-cost fast path.
     */
    private static void prune() {
        boolean any = false;
        for(List<Spec> st : overrides.values())
            any |= !st.isEmpty();
        active = any || treed;   // addon: (034.2) a per-widget-only sheet still needs the slow path
    }

    /**
     * Is {@code name} a valid scope (a member of {@link #SCOPES})? This is how a <b>stylesheet key</b> is
     * classified (033.1): a bare selector role that names one of these is a <b>site key</b> and fills this
     * provider; anything else is a tree key, resolved against the live widget tree instead. ({@code scopes()},
     * the discovery list behind {@code hafen.font.scopes()}, went with that function's hard cut — a sheet key is
     * a selector, so the vocabulary an addon discovers is the selector grammar, not a second enum.)
     */
    public static boolean isScope(String name) {
        if(name == null)
            return false;
        for(String s : SCOPES) {
            if(s.equals(name))
                return true;
        }
        return false;
    }
}
