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
 * <b>generation counter</b>; the Lua surface ({@code hafen.font.*}) is built by {@code io.brodgar.addon.FontApi},
 * which drives this class ({@link #push}/{@link #reset}/{@link #removeOwner}) and reverts an addon's overrides on
 * teardown (owned-resource model, spec 05).
 *
 * <p><b>Resolution</b> is most-specific first: a scope's own override (top of its owner-tagged stack) &rarr; the
 * {@code "default"} override &rarr; the site's stock foundry. So {@code setFont("default", h)} cascades to every
 * routed surface that has no more-specific override, while a per-scope override refines any one surface.
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
        "heading",        // F3e — in-window section headings (CharWnd.catf/failf, GridList.dcatf)
        "button",         // F3  — button captions
        "label",          // F3  — explicit non-default labels
        "tooltip",        // F3  — tooltips
        "menu",           // F3  — flower/context menus
        "chat",           // F3  — chat text
        "textentry",      // F3  — text-entry fields
        "world.nick",     // F4  — floating player/kin names
        "world.speech",   // F4  — speech bubbles
    };

    /** One installed override: an addon's chosen font for a scope, tagged with its owner. Immutable once pushed. */
    private static final class Spec {
        final Object owner;      // the owning io.brodgar.addon.Addon (compared by identity only)
        final Font   base;       // the AWT font, with bold/italic already baked in (size applied per-site)
        final Integer size;      // logical px (UI.scale is applied when a foundry is built), or null = use the site's stock size
        final Boolean aa;        // or null = inherit the site's stock antialias flag
        final Color  color;      // or null = inherit the site's stock default colour
        // Lazily-built foundries, keyed by the STOCK foundry identity (one override may front several sites whose
        // stock size/aa/colour differ). Guarded by `this`.
        private final Map<Text.Foundry, Text.Foundry> cache = new IdentityHashMap<Text.Foundry, Text.Foundry>();

        Spec(Object owner, Font base, Integer size, Boolean aa, Color color) {
            this.owner = owner; this.base = base; this.size = size; this.aa = aa; this.color = color;
        }

        synchronized Text.Foundry foundry(Text.Foundry stock) {
            Text.Foundry c = cache.get(stock);
            if(c != null)
                return c;
            float px = (size != null) ? UI.scale((float)size.intValue())
                                      : stock.font.getSize2D();   // the stock font is already UI.scale'd
            Font f = base.deriveFont(px);
            Color col = (color != null) ? color : stock.defcol;
            boolean a = (aa != null) ? aa.booleanValue() : stock.aa;
            Text.Foundry made = new Text.Foundry(f, col).aa(a);   // (Font, Color) ctor does NOT re-scale — px is final
            cache.put(stock, made);
            return made;
        }
    }

    // scope -> owner-tagged override stack (last = top = current). Guarded by `Fonts.class`.
    private static final Map<String, List<Spec>> overrides = new LinkedHashMap<String, List<Spec>>();
    private static volatile int gen = 0;
    private static volatile boolean active = false;   // any override installed anywhere → foundry() takes the slow path

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

    private static synchronized Text.Foundry resolve(String scope, Text.Foundry stock) {
        Spec o = top(scope);
        if((o == null) && !"default".equals(scope))
            o = top("default");           // cascade: an unset scope falls back to the "default" override
        return (o == null) ? stock : o.foundry(stock);
    }

    /** The current top-of-stack override for {@code scope}, or {@code null}. Caller holds {@code Fonts.class}. */
    private static Spec top(String scope) {
        List<Spec> st = overrides.get(scope);
        return ((st == null) || st.isEmpty()) ? null : st.get(st.size() - 1);
    }

    /** The current generation counter; bumped on every {@link #push}/{@link #reset}/{@link #removeOwner}. */
    public static int gen() {
        return gen;
    }

    /**
     * Install {@code owner}'s override on {@code scope} (its {@code hafen.font.setFont(scope, h)}). An addon owns
     * at most one override per scope — a repeat {@code setFont} replaces its previous one and re-raises it to the
     * top (last-wins). Bumps {@link #gen()} so routed sites/widgets rebuild. {@code base} already carries any
     * bold/italic; {@code size} is <b>logical</b> px (UI-scaled when the foundry is built), {@code aa}/{@code color}
     * are {@code null} to inherit the site's stock.
     */
    public static synchronized void push(String scope, Object owner, Font base, Integer size, Boolean aa, Color color) {
        List<Spec> st = overrides.get(scope);
        if(st == null)
            overrides.put(scope, st = new ArrayList<Spec>());
        removeOwnerFrom(st, owner);       // an addon owns at most one override per scope
        st.add(new Spec(owner, base, size, aa, color));   // re-raise to the top (last applied wins)
        active = true;
        gen++;
    }

    /**
     * Drop {@code owner}'s override on {@code scope} (its {@code hafen.font.reset(scope)}) — the surface falls back
     * to the next owner beneath, or the stock foundry. Returns whether anything was removed; bumps {@link #gen()}
     * only when it was.
     */
    public static synchronized boolean reset(String scope, Object owner) {
        List<Spec> st = overrides.get(scope);
        boolean rm = (st != null) && removeOwnerFrom(st, owner);
        if(rm) {
            prune();
            gen++;
        }
        return rm;
    }

    /**
     * Remove every override owned by {@code owner} across all scopes (its teardown — reload/disable, spec 05). The
     * stock UI is always restorable this way: an addon's entries leave every scope stack and the surfaces fall back
     * beneath. Bumps {@link #gen()} when anything was removed.
     */
    public static synchronized void removeOwner(Object owner) {
        boolean rm = false;
        for(List<Spec> st : overrides.values())
            rm |= removeOwnerFrom(st, owner);
        if(rm) {
            prune();
            gen++;
        }
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

    /** Recompute {@link #active} after a removal (drop empty stacks; clear the flag when nothing remains). */
    private static void prune() {
        boolean any = false;
        for(List<Spec> st : overrides.values())
            any |= !st.isEmpty();
        active = any;
    }

    /** The full scope enum ({@link #SCOPES}), for {@code hafen.font.scopes()} discovery. */
    public static String[] scopes() {
        return SCOPES.clone();
    }

    /** Is {@code name} a valid scope (a member of {@link #SCOPES})? Used to validate {@code setFont}/{@code reset}. */
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
