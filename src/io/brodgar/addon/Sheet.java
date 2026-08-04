package io.brodgar.addon;

import haven.Coord;
import haven.Fonts;
import haven.UI;
import haven.Widget;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * One addon's <b>stylesheet</b> — the whole of {@code hafen.ui.skin{…}} (spec {@code 033-ui-stylesheet},
 * feature C1a). A sheet is a Lua table of <b>selector &rarr; properties</b>:
 *
 * <pre>
 *   hafen.ui.skin{
 *     ["*"]            = { font = body },
 *     ["window.title"] = { font = body:derive{ size = 14, bold = true } },
 *     ["chat"]         = { color = { 200, 210, 200 } },
 *   }
 * </pre>
 *
 * <p><b>An addon owns exactly one sheet.</b> A second {@code skin{…}} replaces the first whole (not rule by
 * rule), {@code skin(nil)} drops it, and teardown drops it too — so a {@code :reload}/disable always restores
 * the stock client. What is stored is never the Lua table: it is parsed <b>once</b>, here, into the entries
 * below, so a later mutation of the caller's table changes nothing until it is re-applied.
 *
 * <p><b>A key resolves one of two ways, and C1a ships one of them</b> (D-067 is why there are two). Keys go
 * through the {@link Selector} parser, so the grammar and its errors are exactly {@code hafen.ui(sel)}'s —
 * there is no second thing to learn and no second thing to keep in sync. The parsed selector is then
 * classified ({@link #siteOf}):
 * <ul>
 *   <li>a <b>site key</b> — a bare {@link Fonts#SCOPES} name ({@code "window.title"}, {@code "chat"}, …), or
 *       {@code *} for the {@code "default"} scope — names a <b>render site</b>, and is resolved where that site
 *       draws. It contributes an owner-tagged entry to the {@link Fonts} provider stack. <b>C1a.</b></li>
 *   <li>a <b>tree key</b> — {@code @Class}, {@code [title=…]}, {@code [res=…]}, or a role that classifies a
 *       widget rather than a site ({@code window}, {@code inventory}) — is resolved <b>per widget against the live
 *       tree</b> ({@link #styleOf}, 034.1 / C1b): every tree rule that matches a widget is folded into one
 *       {@link Resolved} style, most specific winning, and {@code widget:style()} reads it back. It reaches the
 *       screen through {@link #specOf} (034.2), which the provider asks for the widget it is about to draw: F5's
 *       per-instance frame, widened from <i>a handle an addon named by hand</i> to <i>the rule a widget matches</i>,
 *       so the style covers that widget and its whole subtree and <b>no render site is re-routed</b>.</li>
 * </ul>
 *
 * <p><b>The two key classes never resolve each other's rules.</b> A site key fills the {@link Fonts} provider and
 * reaches a <i>render site</i> — including text no widget owns; a tree key reaches <i>widgets</i>. So
 * {@code widget:style()} answers with the per-widget cascade alone: a site rule is not a property of any one
 * widget (a window contains buttons, labels and chat, each drawn at its own site), and folding one in would make
 * the read a guess. <b>Where both reach the same pixels the tree rule wins</b> (034.2) — the frame is nearer the
 * draw than the scope stack — but only property by property: what the tree rule does not name, the site rule still
 * fills, so {@code ["*"] = {font=body}} beside {@code ["window[title=X]"] = {color=…}} paints that window's text in
 * {@code body}, in that colour.
 *
 * <p><b>Above every tree rule sits {@code widget:skin{…}}</b> (034.3, {@link #applySkin}): one addon's style on
 * <b>one</b> widget, named by hand rather than matched. It is not a third mechanism — it is the top level of the
 * <i>same</i> per-widget fold, so it reaches the screen through the same frame, composes per property like every
 * other level, and {@code widget:style()} reports it with the rest. {@code widget:setFont}/{@code :resetFont}
 * (F5) are a <b>hard cut</b>: a font was never a special case, only the first property that existed.
 *
 * <p><b>Nothing about the render sites changes.</b> Every routed site keeps calling
 * {@code Fonts.foundry(scope, stock)} / {@code Fonts.style(scope)} exactly as it did for
 * {@code hafen.font.setFont} (a hard cut, 033.1) — what changed is <i>who fills the stack</i>. The conflict
 * model is therefore D-043's, reused literally: last applied wins, an owner's entries are pulled on teardown,
 * the stock foundry is always restorable. And the {@code null}-means-stock fast path survives, because a sheet
 * with no site key (or one whose rules carry no property) pushes <b>nothing</b>: an addon-less — or a
 * tree-keys-only — client stays byte-for-byte stock.
 *
 * <p>Immutable once parsed, package-private, and carries no Lua.
 */
final class Sheet {
    /** The style properties a rule may carry. */
    private static final String PROPS =
        "\"font\", \"color\", \"bg\", \"border\", \"pad\", \"pos\", \"anchor\" and \"size\"";

    /**
     * One kept rule and the properties it fills. Each property is independently optional. A rule is either a
     * <b>site</b> rule ({@link #site} set) or a <b>tree</b> rule ({@link #sel}/{@link #rank} set) — never both,
     * because {@link #siteOf} answers exactly one of the two for a key.
     */
    private static final class Rule {
        final String site;        // a Fonts.SCOPES name ("default" for the `*` key), or null on a tree rule
        final Selector sel;       // the parsed key a tree rule matches widgets with, or null on a site rule
        final int rank;           // a tree rule's SPECIFICITY (030): role 1 · @Class 2 · [title=] 4 · [res=] 8
        final FontHandle font;    // the rule's `font` property, or null (a rule may carry only a colour)
        final Color color;        // the rule's `color` property, or null (a rule may carry only a font)
        final Chrome.Bg bg;       // the rule's `bg` property (035.1), or null
        final Chrome.Border border;   // the rule's `border` property (035.1), or null
        final Integer pad;        // the rule's `pad` property (035.2), or null
        final Layout.Anchor pos;  // where the rule puts it — `pos` or `anchor` (036.2/036.3) — TREE rules only
        final Coord size;         // the rule's `size` property (036.2), or null — TREE rules only

        Rule(String site, Selector sel, FontHandle font, Color color, Chrome.Bg bg, Chrome.Border border,
             Integer pad, Layout.Anchor pos, Coord size) {
            this.site = site;
            this.sel = sel;
            this.rank = (sel == null) ? 0
                : (((sel.role != null) ? 1 : 0) + ((sel.cls != null) ? 2 : 0)
                   + ((sel.title != null) ? 4 : 0) + ((sel.res != null) ? 8 : 0));
            this.font = font;
            this.color = color;
            this.bg = bg;
            this.border = border;
            this.pad = pad;
            this.pos = pos;
            this.size = size;
        }

        /** Does this rule say anything at all? A rule that names no property styles nothing, anywhere. */
        boolean empty() {
            return (font == null) && (color == null) && (bg == null) && (border == null) && (pad == null)
                && (pos == null) && (size == null);
        }

        /** Does it lay anything out (036.2)? The half of a rule that is a WRITE rather than a draw-time read. */
        boolean layout() {
            return (pos != null) || (size != null);
        }

        /** ...and does it say anything the DRAW reads? A layout-only sheet must not reach the draw pass at all. */
        boolean draws() {
            return (font != null) || (color != null) || (bg != null) || (border != null) || (pad != null);
        }
    }

    /** The addon this sheet belongs to — the owner tag on its site entries, and whose Lua the fonts in it are. */
    private final Addon owner;
    /** The site rules, in the order the sheet listed them. Distinct sites, so the order never decides a winner. */
    private final List<Rule> site;
    /** The tree rules, in the order the sheet listed them — folded per widget by {@link #styleOf}. */
    private final List<Rule> tree;

    private Sheet(Addon owner, List<Rule> site, List<Rule> tree) {
        this.owner = owner;
        this.site = site;
        this.tree = tree;
    }

    // ---- the verb ----------------------------------------------------------------------------------

    /**
     * {@code hafen.ui.skin(sheet)} / {@code hafen.ui.skin(nil)} — install this addon's stylesheet, replacing
     * whatever it had, or drop it. Parsing happens <b>before</b> anything is dropped, so a malformed sheet is an
     * error that leaves the client exactly as it was rather than a half-applied restyle.
     */
    static LuaValue skin(Addon owner, Varargs a) {
        LuaValue arg = a.arg1();
        if(arg.isnil()) {
            drop(owner);
            Layout.sweep();   // 036.2: whatever this sheet was laying out goes back where the user had it
            return LuaValue.NIL;
        }
        if(!arg.istable())
            throw new LuaError("hafen.ui.skin(sheet): expected a table of [\"selector\"] = { font = h } rules, got "
                + arg.typename() + " — hafen.ui.skin(nil) drops this addon's sheet");
        Sheet s = parse(owner, arg);
        drop(owner);          // an addon owns ONE sheet: the previous one's entries leave first...
        s.install(owner);     // ...then this one fills its site keys (so a site it no longer names falls back)
        register(s);          // ...and its tree keys join the per-widget resolution (034.1)
        owner.skin = s;
        // 036.2: ...and its LAYOUT half is enforced now. Outside register()'s lock, because matching takes
        // Sheet.class under the ui monitor and a sweep holding Sheet.class would be the one path able to invert
        // that order — and synchronously, because a rule that moved a window has moved it by the time skin{}
        // returns, exactly as one that recoloured it has recoloured it.
        Layout.sweep();
        return LuaValue.NIL;
    }

    /**
     * Drop {@code owner}'s sheet: each site it filled falls back to whatever is beneath it (another addon's
     * sheet, else the stock foundry), and its tree rules stop resolving. Deliberately per-scope rather than
     * {@code Fonts.removeOwner}, which would also sweep this addon's site entries on scopes it still names. Its
     * {@code widget:skin} entries are untouched either way: a per-instance style is not part of the sheet.
     */
    static void drop(Addon owner) {
        Sheet s = owner.skin;
        if(s == null)
            return;
        owner.skin = null;
        for(int i = 0; i < s.site.size(); i++)
            Fonts.reset(s.site.get(i).site, owner);   // bumps gen only when something was actually removed
        unregister(s);
    }

    /**
     * Teardown ({@code :reload}/disable, {@link FontApi#teardownFonts}): forget everything this addon styled
     * <b>per widget</b> — its sheet's tree rules and its {@code widget:skin} entries — without touching
     * {@link Fonts}, whose {@code removeOwner} sweep has already pulled every site entry the addon owned. The whole
     * per-widget cascade lives here, not in the provider, so this is the only place that can say goodbye to it.
     */
    static void forget(Addon owner) {
        boolean changed;
        synchronized(Sheet.class) {
            Sheet s = owner.skin;
            changed = false;
            if(s != null) {
                owner.skin = null;
                changed = installed.remove(s);
            }
            changed |= dropSkins(owner);
            if(changed)
                rulesChanged();
        }
        // 036.2, and OUTSIDE the lock (see skin()): this addon's layout rules have just stopped resolving, so
        // every widget one of them was holding is offered to whatever cascade is left — another addon's rule, or
        // nothing at all, in which case the stock value comes back.
        if(changed)
            Layout.sweep();
    }

    /**
     * Push this sheet's site rules onto the provider stack, owner-tagged. A rule with no property at all pushes
     * <b>nothing</b> — the identity fast path stays intact, so a sheet of empty rules leaves the client
     * byte-for-byte stock. A rule with only one of the two fills that half and inherits the site's own for the
     * other (a colour-only rule keeps the site's font; a font-only rule keeps its colour).
     *
     * <p><b>The colour is the RULE's, never the handle's</b> (033.2). A {@link FontHandle} may carry a colour —
     * {@code hafen.font("serif"):derive{color=…}} — and that colour still applies to the addon's <b>own</b>
     * drawing ({@code g:text}, its own widgets). On a client SURFACE it is ignored: a surface's colour is what the
     * sheet says it is, in one place, visible in the sheet. That is a deliberate change from F1–F5, where a
     * handle installed on a scope tinted it: two answers to "what colour is this text", one of them invisible.
     */
    private void install(Addon owner) {
        for(int i = 0; i < site.size(); i++) {
            Rule r = site.get(i);
            if(r.empty())
                continue;      // nothing to fill the stack with — keep the identity fast path intact
            FontHandle f = r.font;
            Fonts.push(r.site, owner,
                       (f == null) ? null : f.font,
                       (f == null) ? null : f.size,
                       (f == null) ? null : f.aa,
                       r.color, r.bg, r.border, r.pad);
        }
    }

    // ---- parsing -----------------------------------------------------------------------------------

    /** Parse a Lua sheet table into its rules, each key validated and sorted into the site half or the tree half. */
    private static Sheet parse(Addon owner, LuaValue t) {
        List<Rule> site = new ArrayList<Rule>();
        List<Rule> tree = new ArrayList<Rule>();
        LuaValue k = LuaValue.NIL;
        while(true) {
            Varargs n = t.next(k);
            k = n.arg1();
            if(k.isnil())
                break;
            if(k.isnumber())     // BEFORE isstring(): in LuaJ a number IS a string (the hafen.asset lesson, 028)
                throw new LuaError("hafen.ui.skin: a sheet key is a SELECTOR string (e.g. \"*\", \"chat\","
                    + " \"window.title\"), not a number — a sheet is keyed, not an array");
            if(!k.isstring())
                throw new LuaError("hafen.ui.skin: a sheet key is a SELECTOR string, got " + k.typename());
            String key = k.tojstring();
            LuaValue v = n.arg(2);
            if(!v.istable())
                throw new LuaError("hafen.ui.skin[\"" + key + "\"]: the value is a table of style properties "
                    + "{ font = h, color = {r,g,b} }, got " + v.typename());
            Selector sel = Selector.parse(key);           // a bad key errors exactly as it does in hafen.ui(sel)
            String scope = siteOf(sel);
            Rule r = propsOf("hafen.ui.skin[\"" + key + "\"]", v, scope, (scope == null) ? sel : null);
            if(scope == null)
                tree.add(r);     // a TREE key: matched per widget against the live tree (034.1)
            else
                site.add(r);     // a SITE key: an owner-tagged entry in the Fonts provider (033.1)
        }
        return new Sheet(owner, site, tree);
    }

    /**
     * The {@link Fonts} scope a parsed key fills, or {@code null} when it is a <b>tree key</b> (resolved against
     * the live tree, C1b). A refiner can only ever be answered by a widget, so any of them makes the key a tree
     * one; a refiner-less selector with no role can only have been written {@code *}, the twin of the
     * {@code "default"} scope; and of the bare roles only those the font provider knows name a render site
     * ({@code window} and {@code inventory} are widget roles with no site behind them).
     */
    private static String siteOf(Selector sel) {
        if((sel.cls != null) || (sel.title != null) || (sel.res != null))
            return null;
        if(sel.role == null)
            return "default";
        return Fonts.isScope(sel.role) ? sel.role : null;
    }

    /**
     * The properties of one rule ({@code font}, {@code color}, {@code bg}, {@code border}, {@code pad}), each
     * {@code null} when the rule carries none. An
     * unknown property is an <b>error</b> — unlike an unresolved key, a misspelt property has no future meaning to
     * wait for, and silently doing nothing is the worst way to answer a typo (D-072). It is checked for <b>every</b>
     * key, site or tree alike: the two kinds of key differ in where they resolve, never in what a rule may say —
     * and for {@code widget:skin{…}} too ({@code ctx} names the caller), because a level of the cascade differs in
     * <i>which widgets</i> it reaches, never in what it may say either.
     *
     * <p><b>{@code pos}, {@code anchor} and {@code size} are the exception, and deliberately so</b>
     * (036.2/036.3). They are the first properties that are a <i>write</i> rather than something a surface is
     * drawn with, so they can only be said about a <b>widget</b>, and only in a rule that <i>matches</i> one:
     * <ul>
     *   <li>on a <b>site</b> key they are refused — a site is where the client draws text, and text has no
     *       position of its own to move ({@code "*"} is the {@code default} site, not "every widget");</li>
     *   <li>in {@code widget:skin{…}} they are refused too — the hand-named level of the layout cascade already
     *       exists and is the <b>verb</b>, {@code widget:position(x, y)} / {@code widget:size(w, h)}. One canonical way
     *       per operation: a second spelling of the same level is exactly what this API does not ship.</li>
     * </ul>
     *
     * <p><b>{@code pos} and {@code anchor} are ONE property said two ways</b> (036.3): {@code pos = {x, y}} is the
     * anchor to the widget's own parent's top-left, so a rule carrying both is asking one question twice and gets
     * an error rather than a winner picked by iteration order.
     */
    private static Rule propsOf(String ctx, LuaValue props, String site, Selector sel) {
        FontHandle font = null;
        Color color = null;
        Chrome.Bg bg = null;
        Chrome.Border border = null;
        Integer pad = null;
        Layout.Anchor pos = null;
        Coord size = null;
        String posprop = null;
        LuaValue pk = LuaValue.NIL;
        while(true) {
            Varargs n = props.next(pk);
            pk = n.arg1();
            if(pk.isnil())
                break;
            String p = (!pk.isnumber() && pk.isstring()) ? pk.tojstring() : null;
            LuaValue pv = n.arg(2);
            if("font".equals(p)) {
                font = FontHandle.resolve(pv);
                if(font == null)
                    throw new LuaError(ctx + ".font: expected a font handle — hafen.font(\"sans\")"
                        + " or hafen.asset(\"fonts/Inter.ttf\"), optionally :derive{size=…}");
            } else if("color".equals(p)) {
                color = pv.istable() ? AddonManager.luaColor(pv, null) : null;
                if(color == null)
                    throw new LuaError(ctx + ".color: expected a colour table with 0..255"
                        + " components — { 200, 210, 200 } or { r = 200, g = 210, b = 200, a = 255 }");
            } else if("bg".equals(p)) {
                bg = Chrome.parseBg(ctx, pv);
            } else if("border".equals(p)) {
                border = Chrome.parseBorder(ctx, pv);
            } else if("pad".equals(p)) {
                pad = Chrome.parsePad(ctx, pv);
            } else if("pos".equals(p) || "anchor".equals(p) || "size".equals(p)) {
                layoutable(ctx, p, site, sel);
                if("size".equals(p)) {
                    size = Layout.parseCoord(ctx, p, pv);
                } else {
                    if(posprop != null)
                        throw new LuaError(ctx + ": \"pos\" and \"anchor\" are the same property said two ways —"
                            + " pos = {x, y} IS the anchor to the parent's top-left. Say one of them");
                    posprop = p;
                    pos = "pos".equals(p) ? Layout.Anchor.at(Layout.parseCoord(ctx, p, pv))
                                          : Layout.parseAnchor(ctx, pv);
                }
            } else {
                throw new LuaError(ctx + ": \"" + pk.tojstring()
                    + "\" is not a style property — the properties this client ships are " + PROPS);
            }
        }
        return new Rule(site, sel, font, color, bg, border, pad, pos, size);
    }

    /**
     * Refuse a layout property said somewhere that can never lay anything out (036.2) — see {@link #propsOf}. Two
     * messages, because the two are different mistakes: a site key is the <i>wrong kind of key</i> and the fix is
     * to name the widget, while {@code widget:skin} is the <i>wrong spelling of the right level</i> and the fix is
     * the verb that already does it.
     */
    private static void layoutable(String ctx, String prop, String site, Selector sel) {
        if(site != null)
            throw new LuaError(ctx + "." + prop + ": \"" + prop + "\" lays out a WIDGET, and this key names a"
                + " render site (\"" + site + "\"" + ("default".equals(site) ? ", which is what \"*\" means" : "")
                + ") — a site is where the client draws, not something with a position. Name the widget instead:"
                + " [\"window[title=Equipment]\"] = { " + prop + " = "
                + ("anchor".equals(prop) ? "{ at = \"bottomright\" } }" : "{40, 200} }"));
        if(sel == null)
            throw new LuaError(ctx + "." + prop + ": layout is not a skin property — the hand-named level of the"
                + " cascade is the VERB: widget:position(x, y) and widget:size(w, h), undone with widget:position(nil)."
                + " widget:skin{…} says what a widget is drawn WITH; the verbs say where it is");
    }

    // ---- the PER-WIDGET cascade: tree rules (034.1) + widget:skin (034.3) ---------------------------

    /**
     * The style one widget resolves to — the fold of every <b>tree</b> rule that matches it, most specific
     * winning, with this widget's own {@code widget:skin{…}} above them all, <b>per property</b> (a colour-only
     * rule high up does not take the font a lower one sets, exactly as
     * a cascade should). {@code null} is never one of these: {@link #styleOf} answers {@code null} when nothing
     * matches, so "nothing overrides this widget" is a value the caller cannot mistake for an empty style — the
     * contract that keeps the identity fast path honest and lets {@code widget:style()} read as
     * <i>nil means stock</i>.
     */
    static final class Resolved {
        /** The winning {@code font} property, or {@code null}. */
        final FontHandle font;
        /**
         * The addon whose sheet won {@code font}. A {@link FontHandle}'s Lua handle table belongs to the addon that
         * made it, and <b>no Lua value crosses a sandbox boundary</b> (D-017) — so a reader that is not this addon
         * is handed its own interned view instead ({@link FontApi#handleFor}).
         */
        final Addon fontOwner;
        /** The winning {@code color} property, or {@code null}. */
        final Color color;
        /** The winning {@code bg} property (035.1), or {@code null}. */
        final Chrome.Bg bg;
        /** The winning {@code border} property (035.1), or {@code null}. */
        final Chrome.Border border;
        /** The winning {@code pad} property (035.2), or {@code null}. */
        final Integer pad;
        /**
         * Where the winning rule puts this widget (036.2/036.3) — one {@link Layout.Anchor} whether it was written
         * as {@code pos} or as {@code anchor} — or {@code null}; and the addon whose rule won it, which is who
         * records what the widget was before the layer touched it ({@link Addon#movedNative}) and therefore who
         * gives it back. Unlike the five above these are not read at the draw: {@link Layout} enforces them.
         */
        Layout.Anchor pos;
        Addon posOwner;
        /** The winning {@code size} property (036.2) and its owner — see {@link #pos}. */
        Coord size;
        Addon sizeOwner;
        /** The {@link #treegen} this was resolved at — a bump makes the entry stale on its next touch. */
        final int gen;
        /**
         * The provider's own view of this style (034.2) — what the draw-pass frame carries, {@code null} on an empty
         * resolution. <b>Interned per resolved style</b> ({@link #specFor}), so the {@code stamp} the provider mixes
         * into {@code Fonts.gen()} inside the frame is the same value every frame: a stamp that varied would make
         * every routed site inside the frame rebuild 60 times a second.
         */
        Fonts.Style spec;
        /**
         * Re-matches left before this <b>negative</b> answer is settled (0 on a positive one, which the rules alone
         * decide). A {@code [title=]} caption arrives by {@code uimsg} and a {@code [res=]} resource resolves
         * asynchronously, so a widget can genuinely start matching a tick after it was first asked about; 030.2 met
         * the same thing at the event seam and answered it the same bounded way. Without it the very first look at
         * a just-opened window would cache "nothing matches" forever.
         */
        int recheck;

        Resolved(FontHandle font, Addon fontOwner, Color color, Chrome.Bg bg, Chrome.Border border, Integer pad,
                 int gen) {
            this.font = font;
            this.fontOwner = fontOwner;
            this.color = color;
            this.bg = bg;
            this.border = border;
            this.pad = pad;
            this.gen = gen;
        }

        boolean empty() {
            return drawEmpty() && (pos == null) && (size == null);
        }

        /**
         * Does the cascade give this widget nothing to be <b>drawn</b> with? Then it carries no {@link #spec} and
         * the provider opens no frame around it — which is what keeps a layout-only rule out of the draw path
         * entirely, stamp, foundry rebuild and all (036.2).
         */
        boolean drawEmpty() {
            return (font == null) && (color == null) && (bg == null) && (border == null) && (pad == null);
        }
    }

    /** The sheets with tree rules, in INSTALL order — so a later sheet wins an equal-specificity tie (D-043). */
    private static final List<Sheet> installed = new ArrayList<Sheet>();
    /**
     * The resolution cache: one folded style per widget. A {@link WeakHashMap} because {@code Widget} overrides
     * neither {@code equals} nor {@code hashCode} — that is an identity map for free — and because the keys
     * <b>must</b> be weak: a strong list of styled widgets would pin every closed window, the leak F5 already
     * recorded. Guarded by {@code Sheet.class}.
     */
    private static final Map<Widget, Resolved> cache = new WeakHashMap<Widget, Resolved>();
    /**
     * The per-instance level (034.3): {@code widget:skin{…}}, one list per widget, in <b>apply</b> order so the
     * last addon to skin a widget wins the properties it names. Weak keys for the same two reasons as
     * {@link #cache} — identity for free, and a strong list of skinned widgets would pin every closed window.
     * An addon owns at most one entry per widget, and an empty list is removed rather than kept.
     * Guarded by {@code Sheet.class}.
     */
    private static final Map<Widget, List<Skin>> skins = new WeakHashMap<Widget, List<Skin>>();
    /** Any style installed anywhere — a tree rule or a {@code widget:skin}? Without one, {@link #styleOf} is a volatile read. */
    private static volatile boolean anyStyle = false;
    /** Any tree rule installed anywhere? (The {@code skins} half of {@link #anyStyle} is the map's emptiness.) */
    private static volatile boolean anyTree = false;
    /** Any installed tree rule carrying a late refiner ({@link Selector#late})? Gates the negative re-check. */
    private static volatile boolean anyLate = false;
    /**
     * Any installed tree rule carrying {@code pos}/{@code size} (036.2)? The fast path of the whole layout layer:
     * without one — and with nothing laid out by hand — {@link Layout} has nothing to enforce and sweeps nothing.
     */
    static volatile boolean anyLayout = false;
    /** Any installed LAYOUT rule with a late refiner? Gates {@link Layout}'s bounded re-check at the placement seam. */
    private static volatile boolean anyLateLayout = false;
    /** Bumped whenever the installed tree rules change; a cached entry from an older one is stale. */
    private static int treegen = 0;
    /** How many times a negative answer is re-matched before it settles (030.2's bounded re-check, same default). */
    private static final int LATE_RECHECK =
        Integer.getInteger("haven.addon.stylerecheck", 20).intValue();
    /**
     * One {@link Fonts.Style} per distinct resolved style, so widgets that resolve to the same rules share one — and
     * therefore one stamp (034.2). Keyed on the winning font handle's identity and the winning colour: what the fold
     * produces is exactly that pair. Cleared with the rules. Guarded by {@code Sheet.class}.
     */
    private static final Map<SKey, Fonts.Style> specs = new HashMap<SKey, Fonts.Style>();

    /**
     * One addon's {@code widget:skin{…}} on one widget (034.3) — the top level of the per-widget cascade. Immutable;
     * an addon replaces its own entry rather than stacking a second one.
     */
    private static final class Skin {
        final Addon owner;
        final FontHandle font;
        final Color color;
        final Chrome.Bg bg;
        final Chrome.Border border;
        final Integer pad;

        Skin(Addon owner, FontHandle font, Color color, Chrome.Bg bg, Chrome.Border border, Integer pad) {
            this.owner = owner;
            this.font = font;
            this.color = color;
            this.bg = bg;
            this.border = border;
            this.pad = pad;
        }

        boolean empty() {
            return (font == null) && (color == null) && (bg == null) && (border == null) && (pad == null);
        }
    }

    /** The key of {@link #specs}: a resolved style IS the set of properties it won. */
    private static final class SKey {
        final FontHandle font;
        final Color color;
        final Chrome.Bg bg;
        final Chrome.Border border;
        final Integer pad;

        SKey(FontHandle font, Color color, Chrome.Bg bg, Chrome.Border border, Integer pad) {
            this.font = font;
            this.color = color;
            this.bg = bg;
            this.border = border;
            this.pad = pad;
        }

        public int hashCode() {
            return (System.identityHashCode(font) * 31) + ((color == null) ? 0 : color.hashCode())
                + ((bg == null) ? 0 : bg.hashCode() * 7) + ((border == null) ? 0 : border.hashCode() * 13)
                + ((pad == null) ? 0 : pad.hashCode() * 23);
        }

        public boolean equals(Object o) {
            if(!(o instanceof SKey))
                return false;
            SKey k = (SKey)o;
            return (font == k.font)
                && ((color == null) ? (k.color == null) : color.equals(k.color))
                && ((bg == null) ? (k.bg == null) : bg.equals(k.bg))
                && ((border == null) ? (k.border == null) : border.equals(k.border))
                && ((pad == null) ? (k.pad == null) : pad.equals(k.pad));
        }
    }

    /*
     * 034.2 — where the tree half meets the DRAW. The provider asks us for the style of the widget it is about to
     * draw, once per visible widget per frame, and opens F5's frame around it when there is one; so a tree rule
     * covers that widget and its whole subtree, every already-routed site becomes tree-capable with no second edit,
     * and there is no second resolution path. Registered once, when this class is first touched — which is
     * necessarily before any tree rule can exist, since installing one goes through here.
     */
    static {
        Fonts.treeStyles(new Fonts.TreeStyles() {
            public Fonts.Style styleFor(Widget w) {
                return specOf(w);
            }
        });
        // 035.3 — and where the SITE half meets a window-less panel's own IBox. Registered here, beside the
        // draw-pass source, because both are the same answer read at two different moments: a text site resolves
        // through the ambient frame, a panel names itself. Neither can exist before this class is touched.
        Fonts.boxes(new Fonts.Boxes() {
            public Fonts.Box box(String scope, Widget w, haven.IBox stock) {
                return Chrome.box(scope, w, stock);
            }
        });
    }

    private static synchronized void register(Sheet s) {
        if(s.tree.isEmpty())
            return;              // a site-only sheet resolves nothing per widget: nothing to invalidate either
        installed.add(s);
        rulesChanged();
    }

    private static synchronized void unregister(Sheet s) {
        if(installed.remove(s))
            rulesChanged();
    }

    /**
     * Recompute the fast-path flags and invalidate every cached entry — the one place a change to <b>any</b> level
     * of the per-widget cascade lands, a sheet's tree rules and a {@code widget:skin} alike. A skin touches one
     * widget, so invalidating all of them is broader than it needs to be: it is also what a sheet does, it costs one
     * re-fold per widget on its next draw, and one rule for "the cascade changed" cannot drift from itself.
     * Caller holds {@code Sheet.class}.
     */
    private static void rulesChanged() {
        boolean tree = false, late = false, lay = false, latelay = false, draw = false;
        for(int i = 0; i < installed.size(); i++) {
            List<Rule> rs = installed.get(i).tree;
            for(int j = 0; j < rs.size(); j++) {
                Rule r = rs.get(j);
                tree = true;
                late |= r.sel.late();
                draw |= r.draws();
                if(r.layout()) {                  // 036.2: the half of the cascade that is enforced, not drawn
                    lay = true;
                    latelay |= r.sel.late();
                }
            }
        }
        boolean any = tree || !skins.isEmpty();
        anyTree = tree;
        anyStyle = any;
        anyLate = late;
        anyLayout = lay;
        anyLateLayout = latelay;
        treegen++;
        specs.clear();           // the old rules' styles, and their stamps, do not outlive them
        if(!any)
            cache.clear();       // the last style left: hold nothing, so a stock client carries no state at all
        // 034.2: and the provider stops (or starts) asking us at the draw — on the DRAWING half alone (036.2), so
        // a sheet that only lays widgets out never opens a frame, never bumps a stamp and costs the draw nothing.
        Fonts.treeActive(draw || !skins.isEmpty());
    }

    // ---- the per-INSTANCE level: widget:skin{…} (034.3) ---------------------------------------------

    /**
     * {@code widget:skin{…}} / {@code widget:skin(nil)} — install this addon's style on <b>one</b> widget (and, by
     * the same frame every other level uses, its whole subtree), or drop it. Replaces this addon's previous entry on
     * that widget and leaves every other addon's alone; a table carrying <b>no</b> property is the same as none at
     * all, exactly as a sheet rule with no property styles nothing.
     *
     * <p>The properties are a rule's, parsed by the same {@link #propsOf} — so an unknown one is the same error, and
     * a font handle's own {@code color} is ignored here for the same reason it is ignored in a sheet (D-073): a
     * surface's colour is said <i>as a colour</i>, where it can be read, and a handle's colour is for the addon's own
     * pixels. Parsing happens before anything changes, so a bad call leaves the widget exactly as it was — and it
     * happens even on a <b>stale</b> widget, whose write is then the 029.2 silent chaining no-op.
     */
    static void applySkin(Addon owner, Widget w, LuaValue props) {
        Rule r = null;
        if(!props.isnil()) {
            if(!props.istable())
                throw new LuaError("widget:skin(props): expected a table of style properties { font = h,"
                    + " color = {r,g,b} }, got " + props.typename()
                    + " — widget:skin() reads this addon's style back, widget:skin(nil) drops it");
            r = propsOf("widget:skin", props, null, null);
        }
        if(w == null)
            return;                       // a write on a stale widget: nothing to style (029.2)
        setSkin(owner, w, (r == null) ? null : new Skin(owner, r.font, r.color, r.bg, r.border, r.pad));
    }

    /** Install (or, with {@code s} empty or {@code null}, drop) {@code owner}'s entry on {@code w}. */
    private static synchronized void setSkin(Addon owner, Widget w, Skin s) {
        List<Skin> st = skins.get(w);
        boolean changed = false;
        if(st != null) {
            for(int i = st.size() - 1; i >= 0; i--) {     // an addon owns at most ONE entry per widget
                if(st.get(i).owner == owner) {
                    st.remove(i);
                    changed = true;
                }
            }
        }
        if((s != null) && !s.empty()) {
            if(st == null)
                skins.put(w, st = new ArrayList<Skin>(1));
            st.add(s);                                    // re-raised to the top: last applied wins (D-043)
            owner.skinNodes = true;
            changed = true;
        } else if((st != null) && st.isEmpty()) {
            skins.remove(w);                              // hold nothing for a widget nobody skins any more
        }
        if(changed)
            rulesChanged();
    }

    /**
     * {@code widget:skin()} — <b>this addon's own</b> entry on {@code w} as Lua reads it
     * ({@code { font = <handle>, color = {r=,g=,b=,a=} }}), or {@code nil} when it has none. Deliberately not the
     * resolved style: {@code skin} is a value you wrote and can therefore read back unchanged, while
     * {@code widget:style()} answers the different question of what the whole cascade makes of this widget.
     */
    static LuaValue skinOf(Addon reader, Widget w) {
        Skin s = null;
        synchronized(Sheet.class) {
            List<Skin> st = (w == null) ? null : skins.get(w);
            for(int i = (st == null) ? -1 : st.size() - 1; i >= 0; i--) {
                if(st.get(i).owner == reader) {
                    s = st.get(i);
                    break;
                }
            }
        }
        if(s == null)
            return LuaValue.NIL;
        LuaTable t = new LuaTable();
        if(s.font != null)
            t.set("font", FontApi.handleFor(reader, s.font, reader));
        if(s.color != null)
            t.set("color", AddonManager.color(s.color));
        if(s.bg != null)
            t.set("bg", s.bg.toLua(reader));
        if(s.border != null)
            t.set("border", s.border.toLua(reader));
        if(s.pad != null)
            t.set("pad", LuaValue.valueOf(s.pad.intValue()));
        return t;
    }

    /** Drop every {@code widget:skin} entry {@code owner} installed (its teardown). Caller holds {@code Sheet.class}. */
    private static boolean dropSkins(Addon owner) {
        boolean rm = false;
        for(Iterator<Map.Entry<Widget, List<Skin>>> it = skins.entrySet().iterator(); it.hasNext();) {
            List<Skin> st = it.next().getValue();
            for(int i = st.size() - 1; i >= 0; i--) {
                if(st.get(i).owner == owner) {
                    st.remove(i);
                    rm = true;
                }
            }
            if(st.isEmpty())
                it.remove();
        }
        owner.skinNodes = false;
        return rm;
    }

    /**
     * The resolved style for {@code w}, or {@code null} when no tree rule matches it (the common case, and the
     * answer a stock client always gives). Cache-first: a hit is a map lookup, and matching happens only on a miss
     * or when the rules have changed — the whole reason resolution is stored per widget rather than redone.
     *
     * <p><b>Call under the {@code ui} monitor</b>: matching reads the tree (a {@code [title=]} walks to the
     * enclosing window). It never resolves a subtree or scans anything but this one widget, so it cannot turn into
     * a match storm.
     */
    static Resolved styleOf(Widget w) {
        if(!anyStyle || (w == null))
            return null;
        synchronized(Sheet.class) {
            int left = LATE_RECHECK;
            Resolved cur = cache.get(w);
            if((cur != null) && (cur.gen == treegen)) {
                if(!cur.empty())
                    return cur;                      // a positive answer stands until the rules change
                if(cur.recheck <= 0)
                    return null;                     // a negative answer, settled
                left = cur.recheck - 1;              // still worth asking: a late caption/res may have landed
            }
            Resolved r = fold(w);
            r.recheck = (r.empty() && anyLate) ? left : 0;
            r.spec = r.drawEmpty() ? null : specFor(r);    // a layout-only resolution carries no draw payload
            cache.put(w, r);
            return r.empty() ? null : r;
        }
    }

    /**
     * The style {@code w} resolves to as the <b>provider</b> reads it (034.2) — what the draw-pass frame carries, or
     * {@code null} for the overwhelmingly common "nothing names this widget". This is the hot path: it is called for
     * every visible widget on every frame, so it must be, and is, a cache lookup — {@link #styleOf} matches only on a
     * miss or after the rules changed. Called from the widget draw loop, which holds the {@code ui} monitor.
     */
    static Fonts.Style specOf(Widget w) {
        Resolved r = styleOf(w);
        return (r == null) ? null : r.spec;
    }

    /**
     * The interned {@link Fonts.Style} for one resolved (font, colour) pair — one object per distinct style, however
     * many widgets resolve to it, because the provider mixes its stamp into {@code Fonts.gen()} while the frame is
     * open and that value has to be <b>stable across frames</b> (F5's rule: a stamp that varies rebuilds every routed
     * site every frame). Caller holds {@code Sheet.class}.
     */
    private static Fonts.Style specFor(Resolved r) {
        SKey k = new SKey(r.font, r.color, r.bg, r.border, r.pad);
        Fonts.Style s = specs.get(k);
        if(s == null) {
            FontHandle font = r.font;
            // No owner token: unlike a site entry, a tree style never joins an owner-tagged stack in the provider —
            // it is produced on demand and stops existing when the sheet unregisters, which is the whole teardown.
            specs.put(k, s = Fonts.treeSpec(null,
                                            (font == null) ? null : font.font,
                                            (font == null) ? null : font.size,
                                            (font == null) ? null : font.aa,
                                            r.color, r.bg, r.border, r.pad));
        }
        return s;
    }

    /**
     * Fold every matching tree rule into one style: highest {@link Rule#rank} wins each property, and an equal
     * rank goes to the rule applied last — later sheet first, then later key within a sheet, which is D-043's
     * last-applied-wins read one level down. Then this widget's own {@code widget:skin} entries go on top of the
     * lot, in apply order: a style named by hand outranks every style that was <i>matched</i>, however specific the
     * selector that matched it — but, like every other level, only for the properties it names. Caller holds
     * {@code Sheet.class}.
     */
    private static Resolved fold(Widget w) {
        FontHandle font = null;
        Addon fontOwner = null;
        Color color = null;
        Chrome.Bg bg = null;
        Chrome.Border border = null;
        Integer pad = null;
        Layout.Anchor pos = null;
        Coord size = null;
        Addon posOwner = null, sizeOwner = null;
        int frank = -1, crank = -1, grank = -1, brank = -1, prank = -1, xrank = -1, zrank = -1;
        for(int i = 0; i < installed.size(); i++) {
            Sheet s = installed.get(i);
            for(int j = 0; j < s.tree.size(); j++) {
                Rule r = s.tree.get(j);
                if(!r.sel.matches(w))
                    continue;
                if((r.font != null) && (r.rank >= frank)) {
                    font = r.font; fontOwner = s.owner; frank = r.rank;
                }
                if((r.color != null) && (r.rank >= crank)) {
                    color = r.color; crank = r.rank;
                }
                if((r.bg != null) && (r.rank >= grank)) {
                    bg = r.bg; grank = r.rank;
                }
                if((r.border != null) && (r.rank >= brank)) {
                    border = r.border; brank = r.rank;
                }
                if((r.pad != null) && (r.rank >= prank)) {
                    pad = r.pad; prank = r.rank;
                }
                if((r.pos != null) && (r.rank >= xrank)) {          // 036.2: the owner rides along, because a
                    pos = r.pos; posOwner = s.owner; xrank = r.rank;//   layout property is given BACK, not drawn
                }
                if((r.size != null) && (r.rank >= zrank)) {
                    size = r.size; sizeOwner = s.owner; zrank = r.rank;
                }
            }
        }
        List<Skin> sk = skins.get(w);                     // 034.3: the per-instance level, above every rule
        for(int i = 0; (sk != null) && (i < sk.size()); i++) {
            Skin s = sk.get(i);
            if(s.font != null) {
                font = s.font; fontOwner = s.owner;
            }
            if(s.color != null)
                color = s.color;
            if(s.bg != null)
                bg = s.bg;
            if(s.border != null)
                border = s.border;
            if(s.pad != null)
                pad = s.pad;
            // No layout here: widget:skin{} cannot carry pos/size (propsOf refuses it). The hand-named level of
            // the LAYOUT cascade is the verb, and Layout folds it in above this whole result.
        }
        Resolved out = new Resolved(font, fontOwner, color, bg, border, pad, treegen);
        out.pos = pos;
        out.posOwner = posOwner;
        out.size = size;
        out.sizeOwner = sizeOwner;
        return out;
    }

    /**
     * Could an installed <b>layout</b> rule start matching {@code w} once its caption or its resource lands
     * (036.2)? Asked at the placement seam: {@code role}/{@code @Class} are fixed for a widget's life, so only a
     * rule whose structure already matches and whose refiner is a late one is worth re-checking — which is what
     * keeps {@link Layout}'s pending list to the windows a rule actually names.
     */
    static boolean lateLayoutCandidate(Widget w) {
        if(!anyLateLayout || (w == null))
            return false;
        synchronized(Sheet.class) {
            for(int i = 0; i < installed.size(); i++) {
                List<Rule> rs = installed.get(i).tree;
                for(int j = 0; j < rs.size(); j++) {
                    Rule r = rs.get(j);
                    if(r.layout() && r.sel.late() && r.sel.matchesStructure(w))
                        return true;
                }
            }
        }
        return false;
    }

    /**
     * {@code widget:style()} — the resolved style as Lua reads it: {@code { font = <handle>, color = {r=,g=,b=,a=} }},
     * each field present only when a level of the cascade set it (a tree rule, or this widget's own
     * {@code widget:skin}), or {@code nil} when nothing overrides this widget. The font comes
     * back as the very handle the sheet named when {@code reader} is the addon that wrote it, so
     * {@code w:style().font == body} holds; another addon's font arrives as {@code reader}'s own interned view of
     * it, because no Lua value crosses a sandbox boundary (D-017).
     */
    static LuaValue styleTable(Addon reader, Widget w) {
        UI u = AddonManager.ui;
        Resolved r;
        if(u == null)
            r = styleOf(w);
        else
            synchronized(u) { r = styleOf(w); }
        if(r == null)
            return LuaValue.NIL;
        LuaTable t = new LuaTable();
        if(r.font != null)
            t.set("font", FontApi.handleFor(reader, r.font, r.fontOwner));
        if(r.color != null)
            t.set("color", AddonManager.color(r.color));
        if(r.bg != null)
            t.set("bg", r.bg.toLua(reader));
        if(r.border != null)
            t.set("border", r.border.toLua(reader));
        if(r.pad != null)
            t.set("pad", LuaValue.valueOf(r.pad.intValue()));
        if(r.pos != null)                             // 036.2: what the SHEET says this widget's layout is — the
            r.pos.toLua(reader, t);                   //   verb above it is read with widget:position(), which answers
        if(r.size != null)                            //   where the widget actually IS. 036.3: `pos` or `anchor`,
            t.set("size", LuaWidget.xyTable(r.size)); //   whichever the rule was written with
        return t;
    }
}
