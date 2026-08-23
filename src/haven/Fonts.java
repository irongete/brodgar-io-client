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
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        "hud.belt",         // C2 — the plate under the number belt (065.13; a `picture`, no text)
        "hud.menu.left",    // C2 — ...the map-menu plate in the bottom-left corner
        "hud.menu.right",   // C2 — ...the main-menu plate in the bottom-right one
        "hud.search",       // C2 — ...the plate the action-search button sits on
        "minimap.frame",    // C2 — ...and the frame drawn around the corner minimap
        "chat.system",    // F3  — the System log's lines (065.16; a UI.Notice, white, or an error's dark red)
        "chat.mine",      // F3  — ...your OWN line in any multi-chat
        "chat.private",   // F3  — ...a private message, received or sent
        "chat.party",     // F3  — ...a party line, whose stock is the member's own colour
        "chat.urgent",    // F3  — ...the urgency indicator, a colour PER LEVEL rather than one
        "chat.speaker",   // F3  — ...and the hue the client WALKS, one speaker at a time
    };

    /**
     * The scope a scope falls back to before {@code "default"} (065.16), or {@code null} for the great majority
     * that fall straight to it. <b>Declared, never derived from the dot</b>, and that is the whole point: a
     * dotted name means "a part of" at some keys and "a kind of" at others, and only the second cascades.
     * {@code checkbox.mark} must NOT inherit {@code checkbox}'s art — a part that took the whole's would make
     * naming one of the two impossible (065.10) — while {@code chat.private} must inherit {@code chat}'s,
     * because a kind of chat line IS a chat line and a theme that says nothing about the kinds still means all
     * of them.
     */
    private static final Map<String, String> SCOPE_PARENT = new HashMap<String, String>();
    static {
        for(String s : new String[] {"chat.system", "chat.mine", "chat.private",
                                     "chat.party", "chat.urgent", "chat.speaker"})
            SCOPE_PARENT.put(s, "chat");
    }

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

    /**
     * The whole <b>picture</b> a rule paints in place of the one a site shows (065.12) — one surface rather
     * than the {@code bg}-under-{@code border} pair {@link Chrome} carries, because a plate is not a frame
     * around anything: it <i>is</i> what the site draws.
     *
     * <p>Opaque, like every other value the addon layer parks in a style, and read at the <b>draw</b>: the
     * server re-points the picture an {@link Img} shows ({@code uimsg "ch"}), so a write into the widget would
     * be clobbered and would fight the restore when the rule goes away.
     */
    public interface Picture {
        /** Paint it over {@code [ul, ul+sz)} — device pixels, the box the site was going to draw in. */
        public void draw(GOut g, Coord ul, Coord sz);
    }

    /**
     * What a rule says about an <b>embossed</b> site's relief (065.14) — the one property here that is neither
     * a surface nor a frame, but the texture the client tiles through its own glyph <i>mask</i>.
     *
     * <p>Five of this client's text surfaces are drawn that way ({@link #emboss}), which is exactly why a
     * {@code color} rule cannot reach them: there is no glyph colour left by the time anything is blitted, only
     * a picture cut to the shape of the letters. So the property that names the relief is also the property
     * that <b>turns it off</b>, and turning it off is what hands those glyphs back to the foundry — and to the
     * rule's own colour.
     *
     * <p>Opaque like every other value the addon layer parks in a style: this class carries it and asks it one
     * question at the moment a furnace is built.
     */
    public interface Relief {
        /**
         * The texture tiled through the glyph mask: the theme's own, {@code stock} where the art it named is
         * gone, or {@code null} — <b>no relief at all</b>, so the letters keep the colour the foundry rendered
         * them in.
         */
        public BufferedImage texture(BufferedImage stock);
    }

    /**
     * The <b>halo</b> a rule draws behind a surface's glyphs (065.15) — the other decorator every embossed site
     * already wraps its foundry in ({@link PUtils.BlurFurn}), and the second of the two properties that are
     * neither a surface nor a frame.
     *
     * <p>A blur is two radii and a colour, and a rule says <b>one</b> radius for both: the stock pairs differ by
     * a quarter of a pixel where they differ at all, which is a distinction no theme can see and none should
     * have to write. A radius of zero is a value rather than an omission — it means <i>no halo</i>, where saying
     * nothing means the client's own.
     *
     * <p>Opaque like every other value the addon layer parks in a style, and asked at the one moment a furnace
     * is built, exactly as {@link Relief} is.
     */
    public interface Halo {
        /** How far the halo reaches, in <b>device</b> pixels — {@code 0} for no halo at all. */
        public int radius();
        /** The colour it is drawn in. Never {@code null}: a glow that names no colour is refused at its rule. */
        public Color color();
    }

    /**
     * A colour a site does not <b>hold</b> but <b>walks</b> (065.16) — one per speaker, one per urgency level —
     * said as the sequence it is rather than as the colours it happens to emit.
     *
     * <p>Two of this client's chat colours are of that shape, and neither can be expressed by a {@code color}
     * property without destroying the thing it is for: flattening the per-speaker hue to one colour stops
     * telling speakers apart, and flattening the urgency triple stops telling <i>how</i> urgent. So the rule
     * names the sequence — a palette it lists, or a generator it parameterises — and the site asks it for the
     * {@code n}th answer exactly where it used to walk its own.
     *
     * <p>Opaque like every other value the addon layer parks in a style: this class carries it and asks it one
     * question, at the one moment a site needs a colour it has not got yet.
     */
    public interface Sequence {
        /** The {@code n}th colour of the sequence, {@code n >= 0}. Never {@code null}, and total: a palette wraps. */
        public Color color(int n);
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
        /** The picture {@code wdg} resolves to at {@code scope} ({@code null} = the per-widget cascade alone), or {@code null}. */
        public Picture picture(String scope, Widget wdg);
        /** What {@code scope}'s rule says about its relief, or {@code null} when it says nothing (065.14). */
        public Relief emboss(String scope);
        /** ...and about the halo behind it, or {@code null} when it says nothing (065.15). */
        public Halo glow(String scope);
        /** The colour SEQUENCE {@code scope}'s rule names, or {@code null} when it names none (065.16). */
        public Sequence sequence(String scope);
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

    /**
     * The whole picture a rule paints in place of {@code wdg}'s own (065.12) — {@code null} when no rule names
     * it, which is the site's cue to draw exactly the art it always drew.
     *
     * <p><b>The scope is optional here, and it is the whole of what distinguishes the two callers</b> (065.13).
     * A picture the <i>server</i> placed is one widget showing one image and nothing more, so it passes
     * {@code null} and is named by a tree key alone — {@code ["@Img"]}, or a chain naming the window it sits in
     * — which is what stops the global {@code "*"} rule repainting every picture in the client because somebody
     * named a plate. A plate the <i>client</i> blits at a fixed place is a site like any other: the HUD's belt,
     * its two menu backgrounds, its action-search plate and the minimap's frame each pass their own scope, so a
     * theme names the one it means and the ordinary cascade answers.
     */
    public static Picture picture(String scope, Widget wdg) {
        if(!active)
            return null;                  // fast path: no override anywhere
        Chromes src = chromes;
        return (src == null) ? null : src.picture(scope, wdg);
    }

    /**
     * The <b>relief</b> an embossed site builds its furnace around (065.14): {@code bk} with {@code stock}
     * tiled through its glyph mask — what this client has always drawn — or the texture a rule names instead,
     * or {@code bk} <b>bare</b> where the rule says {@code emboss = false}.
     *
     * <p>Five surfaces are drawn this way, and each of them says so at the one place it builds its furnace over
     * the foundry {@link #foundry(String, Text.Foundry)} just resolved: a window's caption
     * ({@code Window.DefaultDeco}), a section heading and its failed twin ({@code CharWnd}), a group heading
     * ({@code GridList}) and a button's caption ({@code Button}). Each therefore rebuilds on the same
     * {@link #gen()} check it already performs for its font, and needs no second one.
     *
     * <p><b>Dropping the {@link PUtils.TexFurn} is what lets a {@code color} rule reach those glyphs</b>, which
     * is the whole point of the property: a texture cut to the shape of the letters leaves no colour to
     * override, so the only way to paint a caption is to stop tiling one. The blur behind them is a separate
     * furnace and is untouched here.
     *
     * <p>Resolved through the site half of the cascade with the ambient per-widget frame above it — the very
     * chain the foundry beneath it resolved through — so the two halves of one caption cannot disagree about
     * which rule won. A client with no addon pays one {@code volatile} read and builds the furnace it always
     * built.
     */
    public static Text.Forge emboss(String scope, Text.Forge bk, BufferedImage stock) {
        BufferedImage tex = stock;
        if(active) {
            Chromes src = chromes;
            Relief r = (src == null) ? null : src.emboss(scope);
            if(r != null) {
                tex = r.texture(stock);
                if(tex == null)
                    return bk;            // `emboss = false`: no relief, so the foundry's own colour survives
            }
        }
        return new PUtils.TexFurn(bk, tex);
    }

    /**
     * The <b>halo</b> an embossed site blurs behind its text (065.15): {@code bk} behind {@code grad}/{@code
     * brad} of {@code col} — what this client has always drawn — or the one radius and colour a rule names
     * instead, or {@code bk} <b>bare</b> where that radius is zero.
     *
     * <p>It sits directly outside {@link #emboss}, which is how the client itself stacks the two
     * ({@code BlurFurn(TexFurn(foundry, tex), …)}) at each of the five surfaces that wear them, and it is asked
     * on the same {@link #gen()} check each of them already performs for its font. The two properties are
     * independent in every direction: dropping the relief leaves the halo where it was, and naming a halo leaves
     * the relief tiling exactly what it tiled.
     *
     * <p><b>A radius of zero is an answer, and not the same answer as silence.</b> No {@code glow} property
     * leaves the stock blur untouched to the pixel — the shadow under a window caption is part of this client's
     * look and a theme that says nothing about it keeps it — while {@code radius = 0} removes the decorator
     * outright, which is the only way to have letters sit on a surface with nothing behind them.
     *
     * <p>Resolved through the site half of the cascade with the ambient per-widget frame above it, the same
     * chain the foundry and the relief beneath it resolved through, so the three cannot answer to three
     * different rules. A client with no addon pays one {@code volatile} read and builds the furnace it always
     * built.
     */
    public static Text.Forge glow(String scope, Text.Forge bk, int grad, int brad, Color col) {
        int g = grad, b = brad;
        Color c = col;
        if(active) {
            Chromes src = chromes;
            Halo h = (src == null) ? null : src.glow(scope);
            if(h != null) {
                int r = h.radius();
                if(r <= 0)
                    return bk;        // `radius = 0`: no halo, so the glyphs sit on whatever is behind them
                g = b = r;
                c = h.color();
            }
        }
        return new PUtils.BlurFurn(bk, g, b, c);
    }

    /**
     * The colour <b>sequence</b> {@code scope}'s rule names (065.16), or {@code null} — the site walks its own,
     * then, exactly as it always has.
     *
     * <p>Two sites ask: the per-speaker hue a multi-chat mints on first sight ({@code ChatUI.MultiChat}) and the
     * urgency colour the chat indicator and the channel tabs are drawn in. Both used to walk a constant they held
     * privately; both now ask here first and fall back to it.
     *
     * <p>Resolved through the site half of the cascade, like every other chrome value. Only the two keys that
     * take one can carry one — a sequence is refused on every other key at its rule — so the cascade beneath them
     * can never supply a sequence they did not ask for.
     */
    public static Sequence sequence(String scope) {
        if(!active)
            return null;                  // fast path: no override anywhere
        Chromes src = chromes;
        return (src == null) ? null : src.sequence(scope);
    }

    private static synchronized Text.Foundry resolve(String scope, Text.Foundry stock) {
        // F5/C1b: the frame (this widget's own skin, its tree rule, or an enclosing widget's) outranks
        // every scope -- but only for the properties it names; combine() lets the scope fill the rest.
        Spec o = combine(frameTop(), scopeTop(scope));
        return (o == null) ? stock : o.foundry(stock);
    }

    /**
     * The override a scope resolves to on its own: its own stack, else its declared {@link #SCOPE_PARENT}'s
     * (065.16), else the {@code "default"} cascade. The walk is a fallback rather than a fold — within the site
     * half a key either has a rule or takes the one beneath it whole, exactly as it has always taken
     * {@code "default"}'s — so a refining key adds a rung to that ladder and changes nothing about it.
     */
    private static Spec scopeTop(String scope) {
        for(String s = scope; s != null; s = SCOPE_PARENT.get(s)) {
            Spec o = top(s);
            if(o != null)
                return o;
        }
        return "default".equals(scope) ? null : top("default");
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
        // addon: (102.1) a routed TEXT site declares its scope on every render, and a client with no addon at
        // all must not pay for that. Nothing can read this stack while no override and no catalogue exists, so
        // there is nothing to push -- and the skip is taken only while the stack is EMPTY, which is what keeps
        // the pair balanced: an unmatched exit() then finds an empty stack and does nothing.
        if(!active && ((st == null) || st.isEmpty()))
            return;
        if(st == null)
            dynscope.set(st = new ArrayList<String>(4));
        st.add(scope);
    }

    /* ---- a surface that draws WHAT THE USER TYPED (102.2) --------------------------------------------
     *
     * Two halves read the scope a site declares: the STYLE half, which asks what this surface looks like,
     * and the CATALOGUE, which asks what this client displays for the string. At almost every site the two
     * want the same answer. At a text field they do not: the chat's quick line is styled as `chat` -- it
     * lives in the chat window and is built from the chat's own recipe -- while what it DRAWS is the line
     * the player is typing, and a catalogue that could rewrite that would rewrite a word as it was typed.
     *
     * `textentry` says both at once and is the whole answer at a field the client styles as one. It is not
     * the answer here, because moving the quick line's style key is a change to a surface a theme already
     * names. So the site declares its scope and, separately, that its text is the user's -- and the
     * catalogue is the only half that hears the second thing.
     *
     * The mark is per FRAME, not per thread: a frame that carries it remembers where it sits, so exit()
     * takes it down with the scope it belongs to and a render nested inside one -- there is none today, and
     * that is not something to depend on -- is covered exactly as far as its own frame reaches.
     */
    private static final ThreadLocal<List<Integer>> typedscope = new ThreadLocal<List<Integer>>();

    /**
     * {@link #enter(String)}, for a surface whose text is <b>what the user typed</b>: the scope is declared
     * exactly as it would be, and {@link #display} refuses the frame outright — so a catalogue neither
     * rewrites the string nor records it as one somebody could translate, an entry written under
     * {@link #EVERY} included. Paired with {@link #exit()} like its twin.
     */
    public static void enterTyped(String scope) {
        enter(scope);
        List<String> st = dynscope.get();
        if((st == null) || st.isEmpty())
            return;                       // enter() took its inactive fast path: nothing is asking anyway
        List<Integer> ty = typedscope.get();
        if(ty == null)
            typedscope.set(ty = new ArrayList<Integer>(2));
        ty.add(st.size() - 1);            // the frame this mark belongs to
    }

    /** Is the innermost scope one {@link #enterTyped} opened? */
    private static boolean typed() {
        List<Integer> ty = typedscope.get();
        if((ty == null) || ty.isEmpty())
            return false;
        List<String> st = dynscope.get();
        return (st != null) && (ty.get(ty.size() - 1).intValue() == st.size() - 1);
    }

    /** Leave the innermost {@link #enter(String)} scope. */
    public static void exit() {
        List<String> st = dynscope.get();
        if((st == null) || st.isEmpty())
            return;
        List<Integer> ty = typedscope.get();
        if((ty != null) && !ty.isEmpty() && (ty.get(ty.size() - 1).intValue() == st.size() - 1))
            ty.remove(ty.size() - 1);     // ...and the mark that frame carried, with it
        st.remove(st.size() - 1);
    }

    /**
     * Render {@code text} through {@code f} with {@code scope} declared — the {@link #enter}/{@link #exit} pair,
     * written once (102.2).
     *
     * <p>Every routed site owes the pair, because {@link #display} reads the scope the SITE declared rather than
     * the one its foundry was resolved for: a furnace is handed around (a heading's is a {@code Supplier} four
     * classes ask for) and the string it is given arrives from somewhere that never named a scope. Sites whose
     * render is one call take this; a site that renders more than one string, or renders with a colour, opens the
     * pair itself around the lot.
     */
    public static Text render(String scope, Text.Furnace f, String text) {
        enter(scope);
        try {
            return f.render(text);
        } finally {
            exit();
        }
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

    /* ---- what the client DISPLAYS (102.1) -------------------------------------------------------------
     *
     * A string becomes pixels at one place per foundry -- Text.Foundry.render(String, Color) and its RichText
     * twin -- and that is where an addon's CATALOGUE lands: display(scope, text) answers what to draw for the
     * string the site was about to draw, under the scope that site declared with the enter()/exit() pair
     * above. Nothing higher up is touched, which is the whole invariant: every string this client hands back
     * or matches on is its own English, and a catalogue changes what you SEE and nothing else.
     *
     * The stack is a twin of `overrides`: one member per owner, in install order, the top one winning PER
     * ENTRY, so a string a catalogue does not name falls through to the one beneath rather than to English.
     * It is a volatile ARRAY rather than a guarded List because display() is called from inside a render: a
     * lock taken there would be held across a call into the addon layer, and copy-on-write costs an install
     * what it saves every draw.
     */

    /**
     * One addon's catalogue, as the addon layer holds it. This class carries it and asks it two questions;
     * what an entry <i>is</i> — an exact key, a pattern, the owner it belongs to — is that layer's business,
     * exactly as a {@link Style}'s properties are.
     */
    public interface Catalogue {
        /** What to draw for {@code text} at {@code scope}, or {@code null} when this catalogue names nothing for it. */
        public String display(String scope, String text);

        /** {@code text} reached the routed surface {@code scope} and this catalogue named nothing for it. */
        public void missed(String scope, String text);
    }

    // The installed catalogues, in install order (last = top). Replaced whole under `Fonts.class`; read
    // unlocked from inside a render.
    private static volatile Catalogue[] catalogues = new Catalogue[0];

    /**
     * Install {@code c} at the top of the catalogue stack, replacing the entry it already had there — an addon
     * owns at most one, and re-installing re-raises it. Bumps {@link #gen()}, so every site that caches a
     * {@link Text} re-renders on the very compare it already performs.
     */
    public static synchronized void installCatalogue(Catalogue c) {
        List<Catalogue> st = new ArrayList<Catalogue>(Arrays.asList(catalogues));
        st.remove(c);
        st.add(c);
        catalogues = st.toArray(new Catalogue[0]);
        active = true;                // ...so enter() pushes and scope() answers the site's own key
        bumped();
    }

    /**
     * Drop {@code c} from the stack — every string it named falls back to the catalogue beneath it, else to
     * the client's own English. Returns whether it was installed; bumps {@link #gen()} only when it was.
     */
    public static synchronized boolean releaseCatalogue(Catalogue c) {
        List<Catalogue> st = new ArrayList<Catalogue>(Arrays.asList(catalogues));
        if(!st.remove(c))
            return false;             // a removal that already happened is not an error
        catalogues = st.toArray(new Catalogue[0]);
        prune();
        bumped();
        return true;
    }

    /** An installed catalogue changed what it says: every routed site re-renders on the same compare. */
    public static synchronized void catalogueChanged() {
        bumped();
    }

    /**
     * <b>What the client draws for {@code text} at {@code scope}</b> — the one seam a translation lands at, and
     * the last thing that happens to a string before it becomes a raster. Answers {@code text} itself
     * whenever nothing names it, which is the overwhelming case and costs a single {@code volatile} read.
     *
     * <p>{@code scope} is the site's own key ({@link #scope()}), and a scope that is not a <b>locale key</b>
     * is answered unchanged: the surfaces that draw no text have nothing to translate, and {@code textentry}
     * is refused here as well as at the document, so what the user types is never matched — not even by an
     * entry written under {@code "*"}.
     *
     * <p>Every catalogue is asked, top first, and the topmost answer wins. The ones that answer nothing are
     * told so ({@link Catalogue#missed}), because a miss is what <i>your own</i> catalogue did not name, and
     * that is what an author writing their first file reads back.
     */
    public static String display(String scope, String text) {
        Catalogue[] cs = catalogues;
        if(cs.length == 0)
            return text;              // fast path: no catalogue anywhere
        if((text == null) || (text.length() == 0) || !isDisplayScope(scope) || typed())
            return text;
        String out = null;
        for(int i = cs.length - 1; i >= 0; i--) {
            String d = cs[i].display(scope, text);
            if(d == null)
                cs[i].missed(scope, text);
            else if(out == null)
                out = d;              // the top one that names it wins; the rest are asked to record only
        }
        return (out == null) ? text : out;
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
        // addon: (034.2) a per-widget-only sheet still needs the slow path, and (102.1) so does a catalogue
        // with no style rule at all: `active` is what makes enter() push, and a catalogue is read at the scope
        // that pushes.
        active = any || treed || (catalogues.length > 0);
    }

    /* ---- what a site's own look is MADE OF (065.17) --------------------------------------------------
     *
     * Every seam above answers "what does the RULE say about this site". This one answers the other half:
     * what the site draws when no rule says anything -- its stock font, its stock surface, the frame it
     * paints, the place it puts an ornament. A routed site declares it once, beside the very lookup it
     * already performs, and the addon layer reads it back as the catalogue behind sheet:stock().
     *
     * It is DECLARED rather than derived, and it is declared BY THE DRAW: the code that paints a surface is
     * the only thing that knows what that surface is made of, so a catalogue transcribed anywhere else is
     * right the day it is written and silently wrong after the next art change. What travels is the site's
     * own objects -- a Tex, an IBox, a Color, a Foundry -- so a resource NAME is never typed here either:
     * the addon layer resolves one through the picture registry it already fills.
     *
     * A site that has not drawn yet has declared nothing, and answers nothing. That is honest rather than
     * unfortunate: a window nobody has opened has no look to read back.
     */

    /**
     * One piece of a site's own look (065.17): the art it is drawn with, where that art is pinned, and what
     * it does with the room left over.
     *
     * <p>{@link #art} is one of the client's own objects — a {@link Tex}, a {@link BufferedImage}, an
     * {@link IBox}, or a {@link Color} — and it is what the addon layer NAMES; the rest is the placement a
     * theme would have to write to draw the same thing. Every distance here is <b>device</b> pixels, the
     * space a site's own constants are already in; the addon layer converts on the way out, at the one seam
     * it converts everything else.
     *
     * <p>Mutable while it is built and read-only from the moment it is handed over — a site builds one, says
     * where it goes, and passes it to {@link #stock} in the same expression.
     */
    public static final class Piece {
	/**
	 * The client's own art: a {@link Tex}, a {@link BufferedImage}, an {@link IBox} or a {@link Color} —
	 * or {@code null} where the piece is a <b>place</b> and nothing else, which is what a window's caption
	 * is (it has no art of its own; the letters are the picture).
	 */
	public final Object art;
	/** One of the nine corner names it is pinned to, or {@code null} — it covers the whole surface, then. */
	public String at;
	/** How far from that corner, in <b>device</b> px, or {@code null}. */
	public Coord offset;
	/** Does it repeat across the room left over, rather than being scaled across it? */
	public boolean tile;
	/** A line's thickness, or a halo's reach — <b>device</b> px, {@code 0} where the piece is neither. */
	public int width;

	private Piece(Object art) {this.art = art;}

	/** Pin it to one of the nine corners. */
	public Piece at(String corner) {this.at = corner; return(this);}
	/** ...at an offset from that corner, in device px. */
	public Piece at(String corner, Coord offset) {this.at = corner; this.offset = offset; return(this);}
	/** It repeats across the room left over. */
	public Piece tile() {this.tile = true; return(this);}
	/** Its thickness, in device px — a line frame, or the radius of a halo. */
	public Piece width(int w) {this.width = w; return(this);}
    }

    /** One piece of a site's own look — see {@link Piece}. */
    public static Piece piece(Object art) {
	return(new Piece(art));
    }

    /* scope -> property -> the site's own value for it. Written by the sites, read by the addon layer. */
    private static final Map<String, Map<String, Object[]>> stocks =
	new java.util.concurrent.ConcurrentHashMap<String, Map<String, Object[]>>();

    /**
     * Declare what {@code scope}'s own {@code prop} is made of (065.17) — the property spelled exactly as a
     * rule spells it ({@code "font"}, {@code "bg"}, {@code "border"}, {@code "padding"}, …), and the value as
     * the site holds it: a {@link Text.Foundry}, a {@link Color}, a pair of {@link Coord}s, or one or more
     * {@link Piece}s.
     *
     * <p><b>Idempotent and cheap</b>, because a site may declare from its own draw: an unchanged declaration
     * is a comparison and no write at all. A site whose stock genuinely varies per instance — a checkbox is
     * built large or small — declares what it is drawing, so the last one drawn is what the catalogue
     * carries, and the addon layer's page says so.
     *
     * <p>A site declares a property only where the declaration is <b>what it draws</b>. Half of a surface,
     * or a piece placed near enough, would make a catalogue that reads right and installs wrong, which is
     * worse than a key the catalogue leaves out.
     */
    public static void stock(String scope, String prop, Object... value) {
	if((scope == null) || (prop == null) || (value == null))
	    return;
	Map<String, Object[]> m = stocks.get(scope);
	if(m == null)
	    stocks.putIfAbsent(scope, m = new java.util.concurrent.ConcurrentHashMap<String, Object[]>());
	m = stocks.get(scope);
	Object[] had = m.get(prop);
	if((had != null) && java.util.Arrays.equals(had, value))
	    return;                       // the same declaration again: no write, so a per-draw call is free
	m.put(prop, value.clone());
    }

    /** What {@code scope} declared its own look to be, property by property, or {@code null} for nothing. */
    public static Map<String, Object[]> stockOf(String scope) {
	Map<String, Object[]> m = (scope == null) ? null : stocks.get(scope);
	return((m == null) ? null : Collections.unmodifiableMap(m));
    }

    /** Every scope that has declared anything, in {@link #SCOPES} order. */
    public static List<String> stocked() {
	List<String> out = new ArrayList<String>();
	for(String s : SCOPES) {
	    if(stocks.containsKey(s))
		out.add(s);
	}
	return(out);
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

    /* ---- which scopes a LOCALE key may name (102.1) --------------------------------------------------
     *
     * A catalogue is keyed on a surface, and half of SCOPES is not a surface that draws text: the boxes,
     * plates and rails a rule paints chrome on, and the two chat keys that are a colour SEQUENCE rather than
     * a line. Naming one of those in a document could only ever match nothing, so it is refused at the
     * document instead of accepted and left inert.
     *
     * The set is derived from SCOPES rather than re-typed beside it, so the two cannot disagree about which
     * scopes exist; what is written here is the exclusion, which is the part that carries a reason.
     */

    /** The scopes that draw no text of their own — chrome, rails, plates, and the two chat colour sequences. */
    private static final Set<String> NOTEXT = new HashSet<String>(Arrays.asList(
        "window.frame", "panel", "inventory.slot", "checkbox", "checkbox.mark",
        "scrollbar", "scrollbar.knob", "slider", "slider.knob",
        "hud.belt", "hud.menu.left", "hud.menu.right", "hud.search", "minimap.frame",
        "chat.urgent", "chat.speaker"));

    /** The locale key that names every text surface at once — the {@code "default"} scope's twin, as {@code "*"} is a sheet's. */
    public static final String EVERY = "*";

    /**
     * Is {@code name} a key a <b>catalogue</b> may be written under? Every scope that draws text, plus
     * {@link #EVERY}.
     *
     * <p>{@code textentry} is a text surface and is <b>not</b> one of them, for its own reason rather than
     * this one: what the user types is theirs, and a catalogue that could rewrite it would rewrite a name
     * being typed into a search field as the user typed it. {@link #display} asks this too, so the refusal
     * holds at the render as well as at the document — {@code "*"} does not reach an entry field either.
     */
    public static boolean isDisplayScope(String name) {
        if(EVERY.equals(name))
            return true;
        return isScope(name) && !NOTEXT.contains(name) && !"textentry".equals(name);
    }

    /** Every key a catalogue may be written under, {@link #EVERY} first and the rest in {@link #SCOPES} order. */
    public static List<String> displayScopes() {
        List<String> out = new ArrayList<String>();
        out.add(EVERY);
        for(String s : SCOPES) {
            if(isDisplayScope(s))
                out.add(s);
        }
        return out;
    }
}
