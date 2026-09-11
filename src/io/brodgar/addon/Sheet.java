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
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * One addon's <b>stylesheet</b> as the engine holds it — the applied form of {@code hafen.ui():sheet()}
 * (spec {@code 033-ui-stylesheet}, feature C1a; reshaped into objects by {@code 039-uniform-api} §5.4). A
 * sheet is <b>selector &rarr; properties</b>, written as {@link LuaRule} objects on a {@link LuaSheet}:
 *
 * <pre>
 *   local s = hafen.ui():sheet()
 *   s:rule("*"):font(body)
 *   s:rule("window.title"):font(body:derive():size(14):bold(true))
 *   s:rule("chat"):color({200, 210, 200})
 *   s:install()                       -- and s:release()
 * </pre>
 *
 * <p><b>An addon owns exactly one sheet.</b> {@link LuaSheet} is its per-addon singleton, {@code s:install()}
 * applies whatever it currently says (replacing what was applied before, whole and not rule by rule),
 * {@code s:release()} removes it, and teardown drops it too — so a {@code :reload}/disable always restores the
 * stock client. What this class holds is never Lua: {@link LuaSheet} hands it a snapshot of its rules, so a
 * setter called a moment later changes nothing until the sheet is applied again.
 *
 * <p><b>A key resolves one of two ways, and C1a ships one of them</b> (D-067 is why there are two). Keys go
 * through the {@link Selector} parser, so the grammar and its errors are exactly {@code s:ui():match(sel)}'s —
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
 * fills, so {@code rule("*"):font(body)} beside {@code rule("window[title=X]"):color(…)} paints that window's
 * text in {@code body}, in that colour.
 *
 * <p><b>Above every tree rule sits {@code widget:rule()}</b> (034.3, {@link #setWidgetProps}): one addon's style
 * on <b>one</b> widget, named by hand rather than matched. It is not a third mechanism — it is the top level of
 * the <i>same</i> per-widget fold, so it reaches the screen through the same frame, composes per property like
 * every other level, and {@code widget:style()} reports it with the rest. {@code widget:setFont}/{@code
 * :resetFont} (F5) are a <b>hard cut</b>: a font was never a special case, only the first property that existed.
 *
 * <p><b>Nothing about the render sites changes.</b> Every routed site keeps calling
 * {@code Fonts.foundry(scope, stock)} / {@code Fonts.style(scope)} exactly as it did for
 * {@code hafen.font.setFont} (a hard cut, 033.1) — what changed is <i>who fills the stack</i>. The conflict
 * model is therefore D-043's, reused literally: last applied wins, an owner's entries are pulled on teardown,
 * the stock foundry is always restorable. And the {@code null}-means-stock fast path survives, because a sheet
 * with no site key (or one whose rules carry no property) pushes <b>nothing</b>: an addon-less — or a
 * tree-keys-only — client stays byte-for-byte stock.
 *
 * <p>Immutable once applied, package-private, and carries no Lua.
 */
final class Sheet {
    /** The style properties a rule may carry — the whole of a rule's vocabulary, in both its shapes. */
    static final String PROPS =
        "\"font\", \"color\", \"emboss\", \"glow\", \"bg\", \"border\", \"padding\", \"picture\","
        + " \"caption\", \"sizer\", \"closeButton\", \"position\", \"anchor\" and \"size\"";

    /**
     * <b>What one rule says</b> — the properties below, each independently optional, and mutable because a
     * {@link LuaRule} is a chain of setters over exactly this record (039.7). It is the one place the property
     * set is spelled out: a sheet rule, a {@code widget:rule()} level and a rule read out of a data table are
     * the same set of fields, differing only in <i>which widgets</i> they reach.
     *
     * <p><b>Mutable here, immutable once applied.</b> {@link LuaSheet} copies a rule's properties into the
     * {@link Rule} it hands this class, so neither the fold nor the draw pass can read a record a setter is
     * halfway through writing.
     */
    static final class Props {
        FontHandle font;          // the rule's `font` property, or null (a rule may carry only a colour)
        Color color;              // the rule's `color` property, or null (a rule may carry only a font)
        Chrome.Seq seq;           // ...or the SEQUENCE `color` named instead (065.16) — never both, and null on both
        Chrome.Emboss emboss;     // the rule's `emboss` property (065.14), or null
        Chrome.Glow glow;         // the rule's `glow` property (065.15), or null
        Chrome.Bg bg;             // the rule's `bg` property (035.1), or null
        Chrome.Border border;     // the rule's `border` property (035.1), or null
        Chrome.Pad padding;       // the rule's `padding` property (065.1), or null
        Chrome.Pic picture;       // the rule's `picture` property (065.12), or null
        Chrome.Spot caption;      // the rule's `caption` property (065.4), or null
        Chrome.Art sizer;         // the rule's `sizer` property (065.4), or null
        Chrome.Close close;       // the rule's `closeButton` property (065.5), or null
        Layout.Anchor pos;        // where the rule puts it — `position` or `anchor` (036.2/036.3): TREE rules only
        Coord size;               // the rule's `size` property (036.2), or null — TREE rules only

        /** Does this rule say anything at all? A rule that names no property styles nothing, anywhere. */
        boolean empty() {
            return (font == null) && (color == null) && (seq == null) && (emboss == null) && (glow == null)
                && (bg == null) && (border == null) && (padding == null) && (picture == null)
                && (caption == null) && (sizer == null) && (close == null) && (pos == null) && (size == null);
        }

        /** Does it lay anything out (036.2)? The half of a rule that is a WRITE rather than a draw-time read. */
        boolean layout() {
            return (pos != null) || (size != null);
        }

        /** ...and does it say anything the DRAW reads? A layout-only sheet must not reach the draw pass at all. */
        boolean draws() {
            return (font != null) || (color != null) || (seq != null) || (emboss != null) || (glow != null)
                || (bg != null) || (border != null) || (padding != null) || (picture != null)
                || (caption != null) || (sizer != null) || (close != null);
        }

        /** A copy — what a write takes before it changes one field, and what an apply freezes. */
        Props copy() {
            Props p = new Props();
            p.set(this);
            return p;
        }

        /** Say exactly what {@code p} says. The one place a property list is copied, so a new one has no second. */
        void set(Props p) {
            font = p.font;
            color = p.color;
            seq = p.seq;
            emboss = p.emboss;
            glow = p.glow;
            bg = p.bg;
            border = p.border;
            padding = p.padding;
            picture = p.picture;
            caption = p.caption;
            sizer = p.sizer;
            close = p.close;
            pos = p.pos;
            size = p.size;
        }

        /** Say nothing — a rule leaving a sheet that is being replaced whole. */
        void clear() {
            set(new Props());
        }

        /**
         * These properties as Lua reads them ({@code rule:info()}, and the per-widget half of
         * {@code widget:style()}): each field present only where the rule sets it, and a place written back as
         * the spelling it was written with ({@code position} or {@code anchor}).
         */
        void toLua(Addon reader, LuaTable t) {
            if(font != null)
                t.set("font", FontApi.handleFor(reader, font, reader));
            if(color != null)
                t.set("color", AddonManager.color(color));
            if(seq != null)
                t.set("color", seq.toLua());   // the same property, in the shape it was written (065.16)
            if(emboss != null)
                t.set("emboss", emboss.toLua(reader));   // `false` where the rule dropped the relief
            if(glow != null)
                t.set("glow", glow.toLua());
            if(bg != null)
                t.set("bg", bg.toLua(reader));
            if(border != null)
                t.set("border", border.toLua(reader));
            if(padding != null)
                t.set("padding", padding.toLua());
            if(picture != null)
                t.set("picture", picture.toLua(reader));
            if(caption != null)
                t.set("caption", caption.toLua());
            if(sizer != null)
                t.set("sizer", sizer.toLua(reader));
            if(close != null)
                t.set("closeButton", close.toLua(reader));
            if(pos != null)
                pos.toLua(reader, t);
            if(size != null)
                t.set("size", LuaWidget.whTable(size));
        }
    }

    /**
     * One kept rule and the properties it fills, as the <b>applied</b> sheet holds them. A rule is either a
     * <b>site</b> rule ({@link #site} set) or a <b>tree</b> rule ({@link #sel}/{@link #rank} set) — never both,
     * because {@link #siteOf} answers exactly one of the two for a key.
     */
    static final class Rule {
        final String site;        // a Fonts.SCOPES name ("default" for the `*` key), or null on a tree rule
        final Selector sel;       // the parsed key a tree rule matches widgets with, or null on a site rule
        final int rank;           // a tree rule's SPECIFICITY (030, summed over the chain since 049)
        final FontHandle font;    // the rule's `font` property, or null (a rule may carry only a colour)
        final Color color;        // the rule's `color` property, or null (a rule may carry only a font)
        final Chrome.Seq seq;     // ...or the SEQUENCE `color` named instead (065.16) — never both (065.16)
        final Chrome.Emboss emboss;   // the rule's `emboss` property (065.14), or null
        final Chrome.Glow glow;   // the rule's `glow` property (065.15), or null
        final Chrome.Bg bg;       // the rule's `bg` property (035.1), or null
        final Chrome.Border border;   // the rule's `border` property (035.1), or null
        final Chrome.Pad padding; // the rule's `padding` property (065.1), or null
        final Chrome.Pic picture; // the rule's `picture` property (065.12), or null
        final Chrome.Spot caption;   // the rule's `caption` property (065.4), or null
        final Chrome.Art sizer;   // the rule's `sizer` property (065.4), or null
        final Chrome.Close close; // the rule's `closeButton` property (065.5), or null
        final Layout.Anchor pos;  // where the rule puts it — `position` or `anchor` (036.2/036.3) — TREE only
        final Coord size;         // the rule's `size` property (036.2), or null — TREE rules only

        Rule(String site, Selector sel, Props p) {
            this.site = site;
            this.sel = sel;
            this.rank = (sel == null) ? 0 : sel.specificity();
            this.font = p.font;
            this.color = p.color;
            this.seq = p.seq;
            this.emboss = p.emboss;
            this.glow = p.glow;
            this.bg = p.bg;
            this.border = p.border;
            this.padding = p.padding;
            this.picture = p.picture;
            this.caption = p.caption;
            this.sizer = p.sizer;
            this.close = p.close;
            this.pos = p.pos;
            this.size = p.size;
        }

        /** Does this rule say anything at all? A rule that names no property styles nothing, anywhere. */
        boolean empty() {
            return (font == null) && (color == null) && (seq == null) && (emboss == null) && (glow == null)
                && (bg == null) && (border == null) && (padding == null) && (picture == null)
                && (caption == null) && (sizer == null) && (close == null) && (pos == null) && (size == null);
        }

        /** Does it lay anything out (036.2)? The half of a rule that is a WRITE rather than a draw-time read. */
        boolean layout() {
            return (pos != null) || (size != null);
        }

        /** ...and does it say anything the DRAW reads? A layout-only sheet must not reach the draw pass at all. */
        boolean draws() {
            return (font != null) || (color != null) || (seq != null) || (emboss != null) || (glow != null)
                || (bg != null) || (border != null) || (padding != null) || (picture != null)
                || (caption != null) || (sizer != null) || (close != null);
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

    // ---- applying a sheet --------------------------------------------------------------------------

    /**
     * {@code sheet:install()}, and every setter on an installed sheet — make {@code rules} this addon's applied
     * stylesheet, replacing whatever it had. {@link LuaSheet} builds the list from its rule objects <b>before</b>
     * calling, so a malformed rule is an error at its own setter and never a half-applied restyle here.
     */
    static void apply(Addon owner, List<Rule> rules) {
        List<Rule> site = new ArrayList<Rule>();
        List<Rule> tree = new ArrayList<Rule>();
        for(int i = 0; i < rules.size(); i++) {
            Rule r = rules.get(i);
            if(r.site == null)
                tree.add(r);     // a TREE key: matched per widget against the live tree (034.1)
            else
                site.add(r);     // a SITE key: an owner-tagged entry in the Fonts provider (033.1)
        }
        Sheet s = new Sheet(owner, site, tree);
        // audit2 B06: THE FOUR STEPS UNDER THE MONITOR forget() takes for the same four. They are one act --
        // the old sheet's entries out, the new one's in, the registry, the addon's own pointer at it -- and a
        // teardown interleaved with an install left the new sheet standing in `installed` with owner.skin
        // unable to name it again, so its tree rules resolved for the life of the client.
        synchronized(Sheet.class) {
            // audit2 B15: WHERE the old sheet stood, so the new one takes its place rather than the end of
            // the queue. `installed` order is the last tie-break of the per-widget cascade, and an addon's
            // sheet is re-applied WHOLE on any single rule edit (LuaSheet.changed) -- so an unrelated
            // rule:color() silently promoted every rule in that sheet over an addon that installed later,
            // and the look of the client depended on who had last touched a colour.
            int at = installed.indexOf(owner.skin);
            drop(owner);          // an addon owns ONE sheet: the previous one's entries leave first...
            s.install(owner);     // ...then this one fills its site keys (so a site it no longer names falls back)
            register(s, at);      // ...and its tree keys join the per-widget resolution (034.1), where it stood
            owner.skin = s;
        }
        // 036.2: ...and its LAYOUT half is enforced now. Outside register()'s lock, because matching takes
        // Sheet.class under the ui monitor and a sweep holding Sheet.class would be the one path able to invert
        // that order — and synchronously, because a rule that moved a window has moved it by the time the call
        // returns, exactly as one that recoloured it has recoloured it.
        Layout.sweep();
    }

    /**
     * {@code sheet:release()} — this addon's sheet stops being applied, and every surface it styled falls back.
     * Inert when nothing was installed (D-084): a removal that already happened is not an error.
     */
    static void dropSheet(Addon owner) {
        drop(owner);
        Layout.sweep();       // 036.2: whatever this sheet was laying out goes back where the user had it
    }

    /** Is {@code owner}'s sheet applied right now? Derived, never stored — teardown nulls the field. */
    static boolean applied(Addon owner) {
        return owner.skin != null;
    }

    /**
     * Drop {@code owner}'s sheet: each site it filled falls back to whatever is beneath it (another addon's
     * sheet, else the stock foundry), and its tree rules stop resolving. Deliberately per-scope rather than
     * {@code Fonts.removeOwner}, which would also sweep this addon's site entries on scopes it still names. Its
     * {@code widget:rule()} levels are untouched either way: a per-widget style is not part of the sheet.
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
     * <b>per widget</b> — its sheet's tree rules and its {@code widget:rule()} levels — without touching
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
                       r.color, Chrome.props(r.bg, r.border, r.padding, r.picture, r.emboss, r.glow,
                                             r.caption, r.sizer, r.close, r.seq));
        }
    }

    // ---- parsing: the DATA door ({@code sheet:load(t)}) ---------------------------------------------

    /** One row of a parsed sheet table: the key as written, what it resolves to, and what it says. */
    static final class Parsed {
        final String key;         // the selector, exactly as the table spelled it
        final Selector sel;       // parsed
        final String site;        // the Fonts scope this key names, or null when it is a tree key
        final Props props;

        Parsed(String key, Selector sel, String site, Props props) {
            this.key = key;
            this.sel = sel;
            this.site = site;
            this.props = props;
        }
    }

    /**
     * {@code sheet:load(t)} — a whole sheet as <b>data</b>, the door a theme comes through (spec 039 §2.8): a
     * table of {@code ["selector"] = { property = value }}, each key validated and each rule parsed exactly as a
     * chain of setters would have written it.
     *
     * <p>The whole table is parsed <b>before</b> a single rule is committed, so a malformed one is an error that
     * leaves the sheet exactly as it was rather than a half-loaded document.
     */
    static List<Parsed> parseSheet(Addon owner, String ctx, LuaValue t) {
        // audit2 B15: THE KEYS ARE SORTED, and the sheet's order is that. `t.next(k)` walks a Lua table in
        // LuaJ's hash order, which is not an order the addon wrote: it is an artefact of the string hashes
        // and of what else the table has held. That order is what the parsed list becomes, and the parsed
        // list IS the equal-specificity tie-break the README makes load-bearing and what sheet:info() reports
        // as "the order it named them" -- so two rules of the same specificity took turns winning between
        // runs of the same addon. A keyed Lua table HAS no order to preserve, so the honest answer is a
        // stated one, the same on every client and every run; a sheet that needs a particular order between
        // equals writes it as rules, where the order is the calls.
        List<String> keys = new ArrayList<String>();
        LuaValue k = LuaValue.NIL;
        while(true) {
            Varargs n = t.next(k);
            k = n.arg1();
            if(k.isnil())
                break;
            if(k.type() == LuaValue.TNUMBER)   // the TYPE: 42 answers isstring(), "42" isnumber()
                throw new LuaError(ctx + ": a sheet key is a SELECTOR string (e.g. \"*\", \"chat\","
                    + " \"window.title\"), not a number — a sheet is keyed, not an array");
            if(k.type() != LuaValue.TSTRING)
                throw new LuaError(ctx + ": a sheet key is a SELECTOR string, got " + k.typename());
            keys.add(k.tojstring());
        }
        Collections.sort(keys);
        List<Parsed> out = new ArrayList<Parsed>();
        for(int i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            LuaValue v = t.get(LuaValue.valueOf(key));
            if(!v.istable())
                throw new LuaError(ctx + "[\"" + key + "\"]: the value is a table of style properties "
                    + "{ font = h, color = {r,g,b} }, got " + v.typename());
            Selector sel = Selector.parse(key);           // a bad key errors exactly as it does in hafen.ui(sel)
            String scope = siteOf(sel);
            Props p = propsOf(owner, ctx + "[\"" + key + "\"]", v, scope, (scope == null) ? sel : null);
            out.add(new Parsed(key, sel, scope, p));
        }
        return out;
    }

    /**
     * The {@link Fonts} scope a parsed key fills, or {@code null} when it is a <b>tree key</b>: resolved against
     * the live tree, C1b). A render site is ONE bare word, so a chain (049) is always a tree key, and so is any
     * class or refiner — those can only ever be answered by a widget. A bare selector with no role can only have
     * been written {@code *}, the twin of the
     * {@code "default"} scope; and of the bare roles only those the font provider knows name a render site
     * ({@code window} and {@code inventory} are widget roles with no site behind them).
     */
    static String siteOf(Selector sel) {
        if(!sel.bare())
            return null;
        String role = sel.role();
        if(role == null)
            return "default";
        return Fonts.isScope(role) ? role : null;
    }

    /**
     * The properties of one rule — the whole of {@link #PROPS}, and no more — each
     * {@code null} when the rule carries none. An
     * unknown property is an <b>error</b> — unlike an unresolved key, a misspelt property has no future meaning to
     * wait for, and silently doing nothing is the worst way to answer a typo (D-072). It is checked for <b>every</b>
     * key, site or tree alike: the two kinds of key differ in where they resolve, never in what a rule may say —
     * and for a {@code widget:rule()} level too ({@code ctx} names the caller), because a level differs in
     * <i>which widgets</i> it reaches, never in what it may say either.
     *
     * <p><b>{@code position}, {@code anchor} and {@code size} are the exception, and deliberately so</b>
     * (036.2/036.3). They are the first properties that are a <i>write</i> rather than something a surface is
     * drawn with, so they can only be said about a <b>widget</b>, and only in a rule that <i>matches</i> one:
     * <ul>
     *   <li>on a <b>site</b> key they are refused — a site is where the client draws text, and text has no
     *       position of its own to move ({@code "*"} is the {@code default} site, not "every widget");</li>
     *   <li>on {@code widget:rule()} they are refused too — the hand-named level of the layout cascade already
     *       exists and is the <b>verb</b>, {@code widget:position(x, y)} / {@code widget:size(w, h)}. One canonical way
     *       per operation: a second spelling of the same level is exactly what this API does not ship.</li>
     * </ul>
     *
     * <p><b>{@code position} and {@code anchor} are ONE property said two ways</b> (036.3):
     * {@code position = {x, y}} is the anchor to the widget's own parent's top-left, so a rule <i>table</i>
     * carrying both is asking one question twice and gets an error rather than a winner picked by iteration
     * order. (Written as setters there is no order to pick from: the second call replaces the first, exactly as
     * a second {@code :position()} does.)
     */
    private static Props propsOf(Addon owner, String ctx, LuaValue props, String site, Selector sel) {
        Props out = new Props();
        String posprop = null;
        LuaValue pk = LuaValue.NIL;
        while(true) {
            Varargs n = props.next(pk);
            pk = n.arg1();
            if(pk.isnil())
                break;
            String p = (pk.type() == LuaValue.TSTRING) ? pk.tojstring() : null;
            LuaValue pv = n.arg(2);
            if("font".equals(p)) {
                out.font = font(owner, ctx, pv);
            } else if("color".equals(p)) {
                // 065.16: which shape this key takes, decided in one place for both doors into a rule
                boolean sq = Chrome.seqShape(ctx + ".color", site, pv, false);
                if(sq) {
                    out.seq = Chrome.parseSeq(ctx + ".color", pv);
                } else {
                    out.color = pv.istable() ? AddonManager.luaColor(pv, null) : null;
                    if(out.color == null)
                        throw new LuaError(ctx + ".color: expected a colour table with 0..255"
                            + " components — { 200, 210, 200 } or { r = 200, g = 210, b = 200, a = 255 }");
                }
            } else if("emboss".equals(p)) {
                out.emboss = Chrome.parseEmboss(owner, ctx, pv);
            } else if("glow".equals(p)) {
                out.glow = Chrome.parseGlow(ctx, pv);
            } else if("bg".equals(p)) {
                out.bg = Chrome.parseBg(owner, ctx, pv);
            } else if("border".equals(p)) {
                out.border = Chrome.parseBorder(owner, ctx, pv);
            } else if("padding".equals(p)) {
                out.padding = Chrome.parsePadding(ctx, pv);
            } else if("picture".equals(p)) {
                out.picture = Chrome.parsePicture(owner, ctx, ".picture", pv);
            } else if("caption".equals(p)) {
                out.caption = Chrome.parseSpot(ctx, ".caption", pv);
            } else if("sizer".equals(p)) {
                out.sizer = Chrome.parseArt(owner, ctx, ".sizer", pv);
            } else if("closeButton".equals(p)) {
                out.close = Chrome.parseClose(owner, ctx, ".closeButton", pv);
            } else if("position".equals(p) || "anchor".equals(p) || "size".equals(p)) {
                layoutable(ctx, p, site, sel);
                if("size".equals(p)) {
                    out.size = Layout.parseCoord(ctx, p, pv);
                } else {
                    if(posprop != null)
                        throw new LuaError(ctx + ": \"position\" and \"anchor\" are the same property said two"
                            + " ways — position = {x, y} IS the anchor to the parent's top-left. Say one of them");
                    posprop = p;
                    out.pos = "position".equals(p) ? Layout.Anchor.at(Layout.parseCoord(ctx, p, pv))
                                                   : Layout.parseAnchor(ctx, pv);
                }
            } else if("pos".equals(p)) {
                // The one key this feature RENAMED, so it says so rather than reading as a typo: a place is
                // spelled `position` everywhere now, because the verb it pairs with is widget:position(x, y).
                throw new LuaError(ctx + ": \"pos\" is now \"position\" — a place is spelled the same way"
                    + " everywhere, and the verb beside this rule is widget:position(x, y)");
            } else if(Refusal.message("rule:" + p) != null) {
                // A moved PROPERTY, through the data door: the same message its setter throws, because the two
                // are one spelling read two ways and a table has no metatable to hang the refusal off.
                throw new LuaError(ctx + ": " + Refusal.message("rule:" + p));
            } else {
                throw new LuaError(ctx + ": \"" + pk.tojstring()
                    + "\" is not a style property — the properties this client ships are " + PROPS);
            }
        }
        return out;
    }

    /**
     * A rule's {@code font} property: a <b>face</b>, said either way (065.3) — the handle
     * ({@code hafen.font():get(name)}, {@code hafen.asset():get(path)}, either {@code :derive()}d) or the same
     * face <b>named</b>, {@code {builtin = "mono", size = 11}} / {@code {asset = "fonts/Inter.ttf"}}, which is
     * the spelling a {@code theme.json} carries. {@link FontApi#face} is the one parser, so the setter and the
     * data door take the same value and refuse the same way.
     */
    static FontHandle font(Addon owner, String ctx, LuaValue v) {
        return FontApi.face(owner, ctx + ".font", v);
    }

    /**
     * Refuse a layout property said somewhere that can never lay anything out (036.2) — see {@link #propsOf} and
     * {@link LuaRule}. Two messages, because the two are different mistakes: a site key is the <i>wrong kind of
     * key</i> and the fix is to name the widget, while {@code widget:rule()} is the <i>wrong spelling of the
     * right level</i> and the fix is the verb that already does it.
     */
    static void layoutable(String ctx, String prop, String site, Selector sel) {
        if(site != null)
            throw new LuaError(ctx + "." + prop + ": \"" + prop + "\" lays out a WIDGET, and this key names a"
                + " render site (\"" + site + "\"" + ("default".equals(site) ? ", which is what \"*\" means" : "")
                + ") — a site is where the client draws, not something with a position. Name the widget instead:"
                + " sheet:rule(\"window[title=Equipment]\"):" + prop + "("
                + ("anchor".equals(prop) ? "{ at = \"bottomright\" })" : "40, 200)"));
        if(sel == null)
            throw new LuaError(ctx + "." + prop + ": layout is not a rule property here — the hand-named level of"
                + " the cascade is the VERB: widget:position(x, y) and widget:size(w, h), undone with"
                + " widget:position(nil). widget:rule() says what a widget is drawn WITH; the verbs say where it is");
    }

    // ---- the PER-WIDGET cascade: tree rules (034.1) + widget:rule() (034.3) -------------------------

    /**
     * The style one widget resolves to — the fold of every <b>tree</b> rule that matches it, most specific
     * winning, with this widget's own {@code widget:rule()} above them all, <b>per property</b> (a colour-only
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
         * The addon whose sheet won {@code font}. A {@link FontHandle}'s Lua handle belongs to the addon that
         * made it, and <b>no Lua value crosses a sandbox boundary</b> (D-017) — so a reader that is not this addon
         * is handed its own interned view instead ({@link FontApi#handleFor}).
         */
        final Addon fontOwner;
        /** The winning {@code color} property, or {@code null}. */
        final Color color;
        /** The winning colour SEQUENCE (065.16), or {@code null} — the `color` property in its other shape. */
        final Chrome.Seq seq;
        /** The winning {@code emboss} property (065.14), or {@code null}. */
        final Chrome.Emboss emboss;
        /** The winning {@code glow} property (065.15), or {@code null}. */
        final Chrome.Glow glow;
        /** The winning {@code bg} property (035.1), or {@code null}. */
        final Chrome.Bg bg;
        /** The winning {@code border} property (035.1), or {@code null}. */
        final Chrome.Border border;
        /** The winning {@code padding} property (065.1), or {@code null}. */
        final Chrome.Pad padding;
        /** The winning {@code picture} property (065.12), or {@code null}. */
        final Chrome.Pic picture;
        /** The winning {@code caption} property (065.4), or {@code null}. */
        final Chrome.Spot caption;
        /** The winning {@code sizer} property (065.4), or {@code null}. */
        final Chrome.Art sizer;
        /** The winning {@code closeButton} property (065.5), or {@code null}. */
        final Chrome.Close close;
        /**
         * Where the winning rule puts this widget (036.2/036.3) — one {@link Layout.Anchor} whether it was written
         * as {@code pos} or as {@code anchor} — or {@code null}; and the addon whose rule won it, which is who
         * records what the widget was before the layer touched it ({@link Addon#movedNative}) and therefore who
         * gives it back. Unlike the drawing properties above these are not read at the draw: {@link Layout} enforces them.
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

        Resolved(FontHandle font, Addon fontOwner, Color color, Chrome.Seq seq, Chrome.Emboss emboss,
                 Chrome.Glow glow, Chrome.Bg bg, Chrome.Border border, Chrome.Pad padding, Chrome.Pic picture,
                 Chrome.Spot caption, Chrome.Art sizer, Chrome.Close close, int gen) {
            this.font = font;
            this.fontOwner = fontOwner;
            this.color = color;
            this.seq = seq;
            this.emboss = emboss;
            this.glow = glow;
            this.bg = bg;
            this.border = border;
            this.padding = padding;
            this.picture = picture;
            this.caption = caption;
            this.sizer = sizer;
            this.close = close;
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
            return (font == null) && (color == null) && (seq == null) && (emboss == null) && (glow == null)
                && (bg == null) && (border == null) && (padding == null) && (picture == null)
                && (caption == null) && (sizer == null) && (close == null);
        }
    }

    /** The sheets with tree rules, in INSTALL order — so a later sheet wins an equal-specificity tie (D-043). */
    private static final List<Sheet> installed = new ArrayList<Sheet>();
    /**
     * The resolution cache: one folded style per widget. A {@link WeakHashMap} because {@code Widget} overrides
     * neither {@code equals} nor {@code hashCode} — that is an identity map for free — and because the keys
     * <b>must</b> be weak: a strong list of styled widgets would pin every closed window, the leak F5 already
     * recorded.
     *
     * <p><b>Guarded by ITSELF, not by {@code Sheet.class}</b> (audit2 B06). This is the draw pass's own
     * lookup — once per visible widget per frame, hundreds of times — and it used to take the one global
     * monitor the rule registry uses, so an addon installing a sheet held the render out across four
     * registry steps and every {@code Fonts} push inside them. The registry is still {@code Sheet.class}:
     * a MISS takes that for the fold and this one for the store, in that order, which is the order
     * {@link #rulesChanged} and {@link #stockChanged} already take them in.
     */
    // retained: weak keys, and a Resolved is folded properties -- it declares no widget, so the entry collects.
    private static final Map<Widget, Resolved> cache = new WeakHashMap<Widget, Resolved>();
    /**
     * The per-instance level (034.3): {@code widget:rule()}, one list per widget, in <b>apply</b> order so the
     * last addon to style a widget wins the properties it names. Weak keys for the same two reasons as
     * {@link #cache} — identity for free, and a strong list of styled widgets would pin every closed window.
     * An addon owns at most one entry per widget, and an empty list is removed rather than kept.
     * Guarded by {@code Sheet.class}.
     */
    // retained: weak keys, and a Skin is an Addon and its Props -- it declares no widget, so the entry collects.
    private static final Map<Widget, List<Skin>> skins = new WeakHashMap<Widget, List<Skin>>();

    /* ---- the widget's own STOCK (107) ---------------------------------------------------------------
     *
     * What a widget an addon BUILT looks like when no rule says otherwise -- the addon-side twin of the
     * client's own {@code Fonts.stock}. It sits at the BOTTOM of the cascade, under every rule, which is
     * the whole point: the author of an addon says what their bar looks like by default, the author of a
     * theme writes rules, and the theme wins mechanically rather than by loading second.
     *
     * That ordering is not a courtesy. Were an addon to express its default with {@code widget:rule()} it
     * would sit at the TOP and no theme could ever reach past it; were it to express it with a tree rule of
     * its own, whether the theme won would come down to which sheet installed last, which the manifest
     * explicitly does not order. A level beneath every rule has neither problem.
     */
    // retained: weak keys, and a Stock is an Addon and its Props -- it declares no widget, so the entry collects.
    private static final Map<Widget, Stock> stocks = new WeakHashMap<Widget, Stock>();
    private static volatile boolean anyStock = false;

    /** One widget's own stock look, and the addon that declared it (so teardown can take it back). */
    private static final class Stock {
        final Addon owner;
        final Props p;

        Stock(Addon owner, Props p) {
            this.owner = owner;
            this.p = p;
        }
    }

    /**
     * Declare (or, with {@code p} empty or {@code null}, drop) what {@code w} looks like when no rule names
     * it. One widget has one stock, belonging to whoever built it — unlike a {@code widget:rule()} level,
     * which every addon may hold one of at once, because that one is an opinion and this one is a fact.
     */
    static synchronized void setWidgetStock(Addon owner, Widget w, Props p) {
        if(w == null)
            return;                                       // a write on a stale widget: nothing to declare
        boolean had = (stocks.remove(w) != null);
        if((p != null) && !p.empty())
            stocks.put(w, new Stock(owner, p));
        else if(!had)
            return;                                       // dropping what was never there changes nothing
        stockChanged(w);
    }

    /**
     * One widget's stock moved — and <b>only</b> that widget's resolution with it, which is the whole of what
     * this method does that {@link #rulesChanged} does not.
     *
     * <p><b>Why the narrow path exists.</b> A stock is the bottom of the fold for one widget, read by
     * {@link #fold} as {@code stocks.get(w)} and from nowhere else: no other widget's answer can change
     * because this one declared a default, and a stock is not a selector attribute, so it cannot start or
     * stop a chain rule matching anything below it either. So the entry that goes stale is {@code w}'s, and
     * {@code treegen} — which invalidates <i>every</i> cached entry — has nothing to say here. Nor has
     * {@link #specs}, which is a lookup-or-create interning of styles rather than a cache of answers.
     *
     * <p><b>And it owes {@link Fonts} nothing</b>, which is the half that matters: {@link #rulesChanged}
     * ends in {@code Fonts.treeActive(draw || !skins.isEmpty())}, an expression {@code anyStock} is
     * deliberately absent from — a stock answers {@link #styleOf} but does not open the client's own
     * draw-pass frame, so it is not in the font chain and no raster anywhere is resolved through it. Raising
     * the generation counter for one would tell all thirty of the client's cache sites that the fonts moved,
     * and each would be right to rebuild: an item's whole {@code ItemInfo} list ({@code GItem.info}), the
     * number overlay rasterised on every icon, every {@code Label}'s text. A surface built widget by widget —
     * one per item in a container — would pay that once per widget. <b>If a stock is ever put into the font
     * chain, it is that {@code treeActive} argument that changes, and this method that has to change with
     * it</b>; what it would then owe is a re-render of {@code w}'s subtree, not a global generation.
     *
     * <p>Caller holds {@code Sheet.class}.
     */
    private static void stockChanged(Widget w) {
        anyStock = !stocks.isEmpty();
        anyStyle = anyTree || !skins.isEmpty() || anyStock;
        synchronized(cache) {
            if(anyStyle)
                cache.remove(w);
            else
                cache.clear();   // the last style left: hold nothing, exactly as rulesChanged() would have
        }
    }

    /** Drop every stock {@code owner} declared (its teardown). Caller holds {@code Sheet.class}. */
    private static boolean dropStocks(Addon owner) {
        boolean rm = false;
        for(Iterator<Map.Entry<Widget, Stock>> it = stocks.entrySet().iterator(); it.hasNext();) {
            if(it.next().getValue().owner == owner) {
                it.remove();
                rm = true;
            }
        }
        return rm;
    }

    /**
     * The chrome {@code w} wears in state {@code state} — its own stock under every rule that names it — or
     * {@code null} when neither says anything. This is what an addon's own widget asks at its draw, and it
     * is deliberately NOT routed through a {@code Fonts} scope: a widget an addon built is a widget, not one
     * of the places the client draws, so no site key falls back into it and an unnamed one stays bare.
     */
    static Fonts.Chrome chromeOf(Widget w, String state) {
        return Chrome.of(specOf(w), state);
    }

    /**
     * {@code widget:stock(t)} (107) — the table a stock is written in: the very vocabulary a rule is
     * written in, minus the three that lay a widget out. Those are refused by the same gate
     * {@code widget:rule()} refuses them with, and for the same reason: where a widget SITS is the verb.
     */
    static Props stockProps(Addon owner, String ctx, LuaValue t) {
        if(!t.istable())
            throw new LuaError(ctx + ": expected a table of properties — { bg = ..., border = ... }, written"
                + " in the vocabulary a rule is written in, minus the three that lay a widget out");
        return propsOf(owner, ctx, t, null, null);
    }

    /** {@code widget:stock()} — what this widget declared its own look to be, as a table, or {@code nil}. */
    static LuaValue stockTable(Addon owner, Widget w) {
        Props p;
        synchronized(Sheet.class) {
            Stock s = (w == null) ? null : stocks.get(w);
            p = (s == null) ? null : s.p;
        }
        if(p == null)
            return LuaValue.NIL;
        LuaTable t = new LuaTable();
        p.toLua(owner, t);
        return t;
    }
    /** Any style installed anywhere — a tree rule or a {@code widget:rule()}? Without one, {@link #styleOf} is a volatile read. */
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
    // audit2 B06: volatile -- styleOf compares against it while holding the cache's monitor alone, and
    // rulesChanged bumps it under Sheet.class.
    private static volatile int treegen = 0;
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
     * One addon's {@code widget:rule()} on one widget (034.3) — the top level of the per-widget cascade. The
     * {@link Props} it carries is a <b>copy</b> and is never written again; a setter reads it, copies it and
     * replaces the whole entry, so an addon has at most one and the fold never sees a half-written one.
     */
    private static final class Skin {
        final Addon owner;
        final Props p;

        Skin(Addon owner, Props p) {
            this.owner = owner;
            this.p = p;
        }
    }

    /** The key of {@link #specs}: a resolved style IS the set of properties it won. */
    private static final class SKey {
        final FontHandle font;
        final Color color;
        final Chrome.Seq seq;
        final Chrome.Emboss emboss;
        final Chrome.Glow glow;
        final Chrome.Bg bg;
        final Chrome.Border border;
        final Chrome.Pad padding;
        final Chrome.Pic picture;
        final Chrome.Spot caption;
        final Chrome.Art sizer;
        final Chrome.Close close;

        SKey(FontHandle font, Color color, Chrome.Seq seq, Chrome.Emboss emboss, Chrome.Glow glow,
             Chrome.Bg bg, Chrome.Border border, Chrome.Pad padding, Chrome.Pic picture, Chrome.Spot caption,
             Chrome.Art sizer, Chrome.Close close) {
            this.font = font;
            this.color = color;
            this.seq = seq;
            this.emboss = emboss;
            this.glow = glow;
            this.bg = bg;
            this.border = border;
            this.padding = padding;
            this.picture = picture;
            this.caption = caption;
            this.sizer = sizer;
            this.close = close;
        }

        public int hashCode() {
            return (System.identityHashCode(font) * 31) + ((color == null) ? 0 : color.hashCode())
                + ((seq == null) ? 0 : seq.hashCode() * 53)
                + ((emboss == null) ? 0 : emboss.hashCode() * 3)
                + ((glow == null) ? 0 : glow.hashCode() * 47)
                + ((bg == null) ? 0 : bg.hashCode() * 7) + ((border == null) ? 0 : border.hashCode() * 13)
                + ((padding == null) ? 0 : padding.hashCode() * 23)
                + ((picture == null) ? 0 : picture.hashCode() * 43)
                + ((caption == null) ? 0 : caption.hashCode() * 29)
                + ((sizer == null) ? 0 : sizer.hashCode() * 37)
                + ((close == null) ? 0 : close.hashCode() * 41);
        }

        public boolean equals(Object o) {
            if(!(o instanceof SKey))
                return false;
            SKey k = (SKey)o;
            return (font == k.font)
                && ((color == null) ? (k.color == null) : color.equals(k.color))
                && ((seq == null) ? (k.seq == null) : seq.equals(k.seq))
                && ((emboss == null) ? (k.emboss == null) : emboss.equals(k.emboss))
                && ((glow == null) ? (k.glow == null) : glow.equals(k.glow))
                && ((bg == null) ? (k.bg == null) : bg.equals(k.bg))
                && ((border == null) ? (k.border == null) : border.equals(k.border))
                && ((padding == null) ? (k.padding == null) : padding.equals(k.padding))
                && ((picture == null) ? (k.picture == null) : picture.equals(k.picture))
                && ((caption == null) ? (k.caption == null) : caption.equals(k.caption))
                && ((sizer == null) ? (k.sizer == null) : sizer.equals(k.sizer))
                && ((close == null) ? (k.close == null) : close.equals(k.close));
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
        // 065.4 - and where a site that draws a box the CLIENT sizes asks for its art: the caption plate is
        // the first of them, and it is the same answer again, at the arity that has no IBox to stand in for.
        Fonts.chromes(new Fonts.Chromes() {
            public boolean draw(String scope, Widget w, haven.GOut g, Coord ul, Coord sz) {
                return Chrome.draw(scope, w, g, ul, sz);
            }

            // 065.6 - ...and the room its padding asks for, which a site that sizes its OWN box needs before
            // there is a rectangle to hand over. The tooltip is the first of those.
            public Coord[] pad(String scope, Widget w) {
                return Chrome.pad(scope, w);
            }

            // 065.7 - ...and the same answer HELD, for a site that paves a surface with one box many times
            // over. The inventory square is the first of those: one rectangle per cell, resolved once.
            // 065.8 - ...in the STATE the site is in, which is where a bg's own hover/pressed/disabled face
            // is spent: the site names the state it knows it is in and the value answers with one face.
            public Fonts.Chrome chrome(String scope, Widget w, String state) {
                return Chrome.chrome(scope, w, state);
            }

            // 065.11 - ...and the same answer for a surface that is NO WIDGET, so there is nothing to resolve
            // a tree rule against: a speech bubble is a GAttrib on a Gob out in the 3D view. It takes the site
            // half alone, which is the very path that bubble's own font already resolves through.
            public Fonts.Chrome chrome(String scope) {
                return Chrome.chrome(scope);
            }

            // 065.9 - ...and how BIG that art is, for a control that measures itself from its own background
            // rather than painting into a box somebody handed it. A text field is the first of those, and it
            // asks in its constructor, which is why this one takes no widget.
            public Coord size(String scope) {
                return Chrome.size(scope);
            }

            // 065.12 - ...and the whole PLATE a rule paints in place of one widget's own art. A null scope is
            // the per-widget half of the cascade whole, which is what a picture the SERVER placed is named by:
            // one widget showing one image, not a kind of surface the client draws.
            // 065.13 - ...and a scope is the ordinary site answer, for the plates the client blits at fixed
            // places of its own: those DO have a name, and naming them is the whole difference.
            public Fonts.Picture picture(String scope, Widget w) {
                return Chrome.picture(scope, w);
            }

            // 065.14 - ...and the one answer here that paints nothing: whether the client's own RELIEF is cut
            // through an embossed surface's letters, and with what. It takes no widget because an embossed
            // site builds its furnace from a static its whole client shares.
            public Fonts.Relief emboss(String scope) {
                return Chrome.emboss(scope);
            }

            // 065.15 - ...and the other one, the HALO those letters are blurred behind. It is built
            // directly outside the relief at every one of those five sites, so it resolves the same way.
            public Fonts.Halo glow(String scope) {
                return Chrome.glow(scope);
            }

            // 065.16 - ...and the one that is not a colour at all but the SEQUENCE a site walks: the hue a
            // multi-chat mints per speaker, and the urgency triple. Widget-less for the same reason as the two
            // above -- the sites that ask are three widgets sharing one answer, and none of them owns it.
            public Fonts.Sequence sequence(String scope) {
                return Chrome.sequence(scope);
            }
        });
    }

    /**
     * Join the per-widget resolution at {@code at} — the index the addon's previous sheet held, or {@code -1}
     * for a first install, which joins at the end. See the note in {@link #apply}: the index is what keeps a
     * sheet's place in the install order across the whole-sheet re-apply that any rule edit performs.
     */
    private static synchronized void register(Sheet s, int at) {
        if(s.tree.isEmpty())
            return;              // a site-only sheet resolves nothing per widget: nothing to invalidate either
        if((at < 0) || (at > installed.size()))
            installed.add(s);
        else
            installed.add(at, s);
        rulesChanged();
    }

    private static synchronized void unregister(Sheet s) {
        if(installed.remove(s))
            rulesChanged();
    }

    /**
     * Recompute the fast-path flags and invalidate every cached entry — where a change to a level of the
     * per-widget cascade that can reach <b>many</b> widgets lands: a sheet's tree rules and a
     * {@code widget:rule()} alike. A {@code widget:rule()} touches one widget, so invalidating all of them is
     * broader than it needs to be: it is also what a sheet does, it costs one re-fold per widget on its next
     * draw, and one rule for "the cascade changed" cannot drift from itself.
     *
     * <p><b>A widget's own stock does not come through here</b> — {@link #stockChanged} is its path, and that
     * method says why. Teardown does ({@link #forget}), because it drops rules and stocks together and is rare.
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
        // 107: a stock makes styleOf answer, but it does NOT open the client's own draw-pass frame below:
        // nothing but the addon's own widget ever asks for it, and it asks Sheet directly.
        anyStock = !stocks.isEmpty();
        boolean any = tree || !skins.isEmpty() || anyStock;
        anyTree = tree;
        anyStyle = any;
        anyLate = late;
        anyLayout = lay;
        anyLateLayout = latelay;
        treegen++;
        specs.clear();           // the old rules' styles, and their stamps, do not outlive them
        if(!any) {
            synchronized(cache) {
                cache.clear();   // the last style left: hold nothing, so a stock client carries no state at all
            }
        }
        // 034.2: and the provider stops (or starts) asking us at the draw — on the DRAWING half alone (036.2), so
        // a sheet that only lays widgets out never opens a frame, never bumps a stamp and costs the draw nothing.
        //   `anyStock` IS ABSENT FROM THIS EXPRESSION ON PURPOSE, and stockChanged() rests on that: a stock is
        // not in the font chain, so declaring one owes the provider no generation. Putting one in here is what
        // would make a stock write owe an invalidation again — and stockChanged() is where that lands.
        Fonts.treeActive(draw || !skins.isEmpty());
    }

    // ---- the per-INSTANCE level: widget:rule() (034.3) ----------------------------------------------

    /**
     * <b>This addon's own</b> level on {@code w} — what {@code widget:rule()}'s setters read, write and remove.
     * {@code null} when this addon says nothing about that widget (and on a stale one, whose write is the 029.2
     * silent chaining no-op). A <b>copy</b>, because the stored record is shared with the fold.
     */
    static Props widgetProps(Addon owner, Widget w) {
        synchronized(Sheet.class) {
            List<Skin> st = (w == null) ? null : skins.get(w);
            for(int i = (st == null) ? -1 : st.size() - 1; i >= 0; i--) {
                if(st.get(i).owner == owner)
                    return st.get(i).p.copy();
            }
        }
        return null;
    }

    /**
     * Install (or, with {@code p} empty or {@code null}, drop) {@code owner}'s level on {@code w} — one addon's
     * style on <b>one</b> widget, and by the same frame every other level uses, its whole subtree. It replaces
     * this addon's previous entry and leaves every other addon's alone; a level carrying <b>no</b> property is
     * the same as none at all, exactly as a sheet rule with no property styles nothing.
     */
    static synchronized void setWidgetProps(Addon owner, Widget w, Props p) {
        if(w == null)
            return;                                       // a write on a stale widget: nothing to style (029.2)
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
        if((p != null) && !p.empty()) {
            if(st == null)
                skins.put(w, st = new ArrayList<Skin>(1));
            st.add(new Skin(owner, p));                   // re-raised to the top: last applied wins (D-043)
            owner.skinNodes = true;
            changed = true;
        } else if((st != null) && st.isEmpty()) {
            skins.remove(w);                              // hold nothing for a widget nobody styles any more
        }
        if(changed)
            rulesChanged();
    }

    /**
     * Move the per-instance level of one widget to the widget that replaced it — the face setter's rebuild
     * (040.2, {@link UiApi#rebuild}), the one event in the client that swaps a live widget for another.
     *
     * <p>Without it a {@code widget:rule()} written before {@code :image(u, d)} would be dropped on the floor:
     * the level is keyed on the widget object, and the rebuild makes a new one. The resolution cache entry goes
     * rather than moves — the new widget is a different class, so what it resolves to is a different answer.
     */
    static synchronized void rekeyWidget(Widget from, Widget to) {
        List<Skin> st = skins.remove(from);
        if(st != null)
            skins.put(to, st);
        synchronized(cache) {
            cache.remove(from);
        }
    }

    /** Drop every {@code widget:rule()} level {@code owner} installed (its teardown). Caller holds {@code Sheet.class}. */
    private static boolean dropSkins(Addon owner) {
        boolean stk = dropStocks(owner);                  // 107: and every stock it declared, with it
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
        return rm || stk;
    }

    /**
     * The resolved style for {@code w}, or {@code null} when no tree rule matches it (the common case, and the
     * answer a stock client always gives). Cache-first: a hit is a map lookup, and matching happens only on a miss
     * or when the rules have changed — the whole reason resolution is stored per widget rather than redone.
     *
     * <p><b>Call under the {@code ui} monitor</b>: matching reads the tree — since 049 a chain key walks this
     * widget's ANCESTORS, bounded by the tree's depth. It still resolves nothing but this one widget and scans no
     * subtree, so it cannot turn into a match storm; what an ancestor step costs is a walk up, once per fold.
     *
     * <p><b>A negative answer settles, and an ancestor's caption re-opens it</b> (049.3). The countdown below is
     * for an attribute that has not landed <i>yet</i>; a caption that changes later — a rename, or one arriving
     * after the countdown ran out — reaches this cache through {@link #markCaptionChanged} instead, which drops
     * the renamed window's whole subtree. Both directions matter: a chain rule can start matching a descendant, and
     * a rule that <i>was</i> matching stops, which the "a positive answer stands until the rules change" line below
     * would otherwise never notice.
     */
    static Resolved styleOf(Widget w) {
        // 107: ...or the widget carries a stock of its own, which is an answer even with no rule installed
        // anywhere -- that is what makes an addon's default look show on a client wearing no theme at all.
        if((!anyStyle && !anyStock) || (w == null))
            return null;
        int left = LATE_RECHECK;
        synchronized(cache) {                        // the HIT, and the draw pass wants nothing else
            Resolved cur = cache.get(w);
            if((cur != null) && (cur.gen == treegen)) {
                if(!cur.empty())
                    return cur;                      // a positive answer stands until the rules change
                if(cur.recheck <= 0)
                    return null;                     // a negative answer, settled
                left = cur.recheck - 1;              // still worth asking: a late caption/res may have landed
            }
        }
        synchronized(Sheet.class) {                  // the MISS: the fold reads the registry
            Resolved r = fold(w);
            r.recheck = (r.empty() && anyLate) ? left : 0;
            r.spec = r.drawEmpty() ? null : specFor(r);    // a layout-only resolution carries no draw payload
            synchronized(cache) {
                cache.put(w, r);
            }
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
        SKey k = new SKey(r.font, r.color, r.seq, r.emboss, r.glow, r.bg, r.border, r.padding, r.picture,
                          r.caption, r.sizer, r.close);
        Fonts.Style s = specs.get(k);
        if(s == null) {
            FontHandle font = r.font;
            // No owner token: unlike a site entry, a tree style never joins an owner-tagged stack in the provider —
            // it is produced on demand and stops existing when the sheet unregisters, which is the whole teardown.
            specs.put(k, s = Fonts.treeSpec(null,
                                            (font == null) ? null : font.font,
                                            (font == null) ? null : font.size,
                                            (font == null) ? null : font.aa,
                                            r.color, Chrome.props(r.bg, r.border, r.padding, r.picture,
                                                                  r.emboss, r.glow, r.caption, r.sizer,
                                                                  r.close, r.seq)));
        }
        return s;
    }

    /**
     * Fold every matching tree rule into one style: highest {@link Rule#rank} wins each property, and an equal
     * rank goes to the rule applied last — later sheet first, then later key within a sheet, which is D-043's
     * last-applied-wins read one level down. Then this widget's own {@code widget:rule()} level goes on top of the
     * lot, in apply order: a style named by hand outranks every style that was <i>matched</i>, however specific the
     * selector that matched it — but, like every other level, only for the properties it names. Caller holds
     * {@code Sheet.class}.
     */
    private static Resolved fold(Widget w) {
        FontHandle font = null;
        Addon fontOwner = null;
        Color color = null;
        Chrome.Seq seq = null;
        Chrome.Emboss emboss = null;
        Chrome.Glow glow = null;
        Chrome.Bg bg = null;
        Chrome.Border border = null;
        Chrome.Pad padding = null;
        Chrome.Pic picture = null;
        Chrome.Spot caption = null;
        Chrome.Art sizer = null;
        Chrome.Close close = null;
        Layout.Anchor pos = null;
        Coord size = null;
        Addon posOwner = null, sizeOwner = null;
        int frank = -1, crank = -1, grank = -1, brank = -1, prank = -1, xrank = -1, zrank = -1;
        int arank = -1, srank = -1, krank = -1, qrank = -1, erank = -1, lrank = -1, wrank = -1;
        /* 107: the widget's own stock goes in FIRST, with every rank left at -1 -- so the first rule that
         * names this widget, at any specificity, wins the property outright, and what the addon declared
         * survives only where no rule spoke. That is the whole ordering, and it costs one lookup. */
        Stock st0 = stocks.get(w);
        if(st0 != null) {
            Props p = st0.p;
            font = p.font; color = p.color; seq = p.seq; emboss = p.emboss; glow = p.glow;
            bg = p.bg; border = p.border; padding = p.padding; picture = p.picture;
            caption = p.caption; sizer = p.sizer; close = p.close;
            if(font != null)
                fontOwner = st0.owner;
        }
        /* audit2 B08 (us-01): ...and a surface the user DECIDES with takes no tree rule at all. Asked once,
         * above the loop, because the answer is a property of the widget and not of the rule. */
        int nsheets = consentSurface(w) ? 0 : installed.size();
        for(int i = 0; i < nsheets; i++) {
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
                if((r.seq != null) && (r.rank >= wrank)) {
                    seq = r.seq; wrank = r.rank;
                }
                if((r.emboss != null) && (r.rank >= erank)) {
                    emboss = r.emboss; erank = r.rank;
                }
                if((r.glow != null) && (r.rank >= lrank)) {
                    glow = r.glow; lrank = r.rank;
                }
                if((r.bg != null) && (r.rank >= grank)) {
                    bg = r.bg; grank = r.rank;
                }
                if((r.border != null) && (r.rank >= brank)) {
                    border = r.border; brank = r.rank;
                }
                if((r.padding != null) && (r.rank >= prank)) {
                    padding = r.padding; prank = r.rank;
                }
                if((r.picture != null) && (r.rank >= qrank)) {
                    picture = r.picture; qrank = r.rank;
                }
                if((r.caption != null) && (r.rank >= arank)) {
                    caption = r.caption; arank = r.rank;
                }
                if((r.sizer != null) && (r.rank >= srank)) {
                    sizer = r.sizer; srank = r.rank;
                }
                if((r.close != null) && (r.rank >= krank)) {
                    close = r.close; krank = r.rank;
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
            if(s.p.font != null) {
                font = s.p.font; fontOwner = s.owner;
            }
            if(s.p.color != null)
                color = s.p.color;
            if(s.p.seq != null)
                seq = s.p.seq;
            if(s.p.emboss != null)
                emboss = s.p.emboss;
            if(s.p.glow != null)
                glow = s.p.glow;
            if(s.p.bg != null)
                bg = s.p.bg;
            if(s.p.border != null)
                border = s.p.border;
            if(s.p.padding != null)
                padding = s.p.padding;
            if(s.p.picture != null)
                picture = s.p.picture;
            if(s.p.caption != null)
                caption = s.p.caption;
            if(s.p.sizer != null)
                sizer = s.p.sizer;
            if(s.p.close != null)
                close = s.p.close;
            // No layout here: widget:rule() cannot carry position/size (LuaRule refuses it). The hand-named level
            // of the LAYOUT cascade is the verb, and Layout folds it in above this whole result.
        }
        Resolved out = new Resolved(font, fontOwner, color, seq, emboss, glow, bg, border, padding, picture,
                                    caption, sizer, close, treegen);
        out.pos = pos;
        out.posOwner = posOwner;
        out.size = size;
        out.sizeOwner = sizeOwner;
        return out;
    }

    /**
     * <b>Is this widget part of a surface the user decides with?</b> (audit2 B08, us-01) — the consent
     * dialog, the AddOns panel and its options panel, and the login screen. A tree rule matches every widget
     * in every tree, so an addon could move, shrink, recolour or blank <i>the very dialog it is being enabled
     * by</i>: {@code window[title=Enable <its own name>?]} is an ordinary selector, and the dialog is an
     * ordinary {@link haven.Window} whose caption says so. What the user reads before they answer has to be
     * what the client drew.
     *
     * <p><b>The widget or any ancestor</b>, because the caption, the buttons and the key lines inside the
     * dialog are what a rule would actually reach — excluding the frame alone would leave every word in it
     * addressable. It excludes <b>tree rules</b> and nothing else: a widget's own {@code widget:rule()} level
     * and the stock it was declared with still resolve, and neither of those can be pointed at a surface the
     * addon did not build.
     *
     * <p>The walk is the tree's depth and runs only where a sheet is installed at all, which is the same
     * condition the rule loop above already pays for.
     */
    private static boolean consentSurface(Widget w) {
        for(Widget p = w; p != null; p = p.parent) {
            if((p instanceof io.brodgar.addon.ui.PermissionConsentWnd)
               || (p instanceof io.brodgar.addon.ui.AddonPanel)
               || (p instanceof io.brodgar.addon.ui.AddonOptionsPanel)
               || (p instanceof haven.LoginScreen))
                return true;
        }
        return false;
    }

    // ---- the caption seam: an ancestor's caption is part of a descendant's answer (049.3) ------------

    /**
     * Windows whose caption changed since the last tick (the caption seam, {@link AddonManager#onCaptionChanged}
     * &larr; {@code Window.chcap}). Appended off the UI thread and drained on it — {@link #drainCaptionInvalidation}
     * walks the tree, which is a {@code ui}-monitor read. Strong references, held for at most one tick; a window
     * destroyed in between is simply walked (or not) and dropped, since invalidating a cache entry for a dead
     * widget costs nothing and the map's keys are weak anyway.
     */
    // 073.2: ONE TREE'S ({@code SessionState.styleCapChanged}) — a renamed window is a window of one session,
    // recorded here from w.ui because chcap runs on whichever Loader thread applied the message and the
    // subtree walk that follows must be of the tree the window is actually in.

    /**
     * Record that {@code w}'s caption changed (049.3). Appends one reference — no widget read, no tree walk — so it
     * is safe on whatever thread wrote the caption. Free unless some installed tree rule actually carries a late
     * refiner: with none, no cached answer can depend on a caption. Installing one is not a race to lose, because
     * {@link #rulesChanged} bumps {@code treegen} and so invalidates every cached entry by itself.
     */
    static void markCaptionChanged(Widget w) {
        if((w == null) || !anyLate)
            return;
        AddonManager.SessionState st = AddonManager.state(w.ui);
        if(st != null)
            st.styleCapChanged.add(w);
    }

    /**
     * Tick-side drain (UI thread, {@link AddonManager#tick}): drop the cached resolution of every renamed window
     * <b>and of everything below it</b>, so the next draw folds them again.
     *
     * <p><b>The subtree is the point.</b> Before 049 a caption could only decide the style of the window carrying
     * it, and the negative-answer countdown in {@link #styleOf} covered the one case that mattered (a caption
     * landing a tick or two after the window was first drawn). A chain key — {@code ["window[title=Cupboard]
     * label"]} — makes an ancestor's caption part of a <i>descendant's</i> answer, and the cache has no ancestor
     * walk to notice that with. Walking down from the window that actually changed is the exact answer to "whose
     * cached style could this have changed", and it costs one walk per rename rather than anything per frame.
     */
    static void drainCaptionInvalidation(AddonManager.SessionState st) {
        if(st.styleCapChanged.isEmpty())
            return;
        UI u = st.ui;                 // 073.2: the tree whose tick this is, which is the tree those windows are in
        if(u.root == null) {
            st.styleCapChanged.clear();   // the tree those windows belonged to is gone; so is anything cached for it
            return;
        }
        synchronized(LuaWidget.monitorOf(u)) {   // 112.2: the acquisition every site in this layer makes
            synchronized(Sheet.class) {          // ui -> Sheet.class, the order every other reader takes
                for(int n = st.styleCapChanged.size(); n > 0; n--) {
                    Widget w = st.styleCapChanged.poll();
                    if(w == null)
                        break;
                    invalidateSubtree(w);
                }
            }
        }
    }

    /** Drop {@code w}'s cached resolution and every one below it. Caller holds {@code ui} and {@code Sheet.class}. */
    private static void invalidateSubtree(Widget w) {
        synchronized(cache) {
            cache.remove(w);
        }
        for(Widget c = w.child; c != null; c = c.next)
            invalidateSubtree(c);
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
     * {@code widget:rule()}), or {@code nil} when nothing overrides this widget. The font comes
     * back as the very handle the sheet named when {@code reader} is the addon that wrote it, so
     * {@code w:style().font == body} holds; another addon's font arrives as {@code reader}'s own interned view of
     * it, because no Lua value crosses a sandbox boundary (D-017).
     */
    static LuaValue styleTable(Addon reader, Widget w) {
        Resolved r;
        synchronized(LuaWidget.monitor(w)) { r = styleOf(w); }
        if(r == null)
            return LuaValue.NIL;
        LuaTable t = new LuaTable();
        if(r.font != null)
            t.set("font", FontApi.handleFor(reader, r.font, r.fontOwner));
        if(r.color != null)
            t.set("color", AddonManager.color(r.color));
        if(r.seq != null)
            t.set("color", r.seq.toLua());   // the same property, in the shape it was written (065.16)
        if(r.emboss != null)
            t.set("emboss", r.emboss.toLua(reader));
        if(r.glow != null)
            t.set("glow", r.glow.toLua());
        if(r.bg != null)
            t.set("bg", r.bg.toLua(reader));
        if(r.border != null)
            t.set("border", r.border.toLua(reader));
        if(r.padding != null)
            t.set("padding", r.padding.toLua());
        if(r.picture != null)
            t.set("picture", r.picture.toLua(reader));
        if(r.caption != null)
            t.set("caption", r.caption.toLua());
        if(r.sizer != null)
            t.set("sizer", r.sizer.toLua(reader));
        if(r.close != null)
            t.set("closeButton", r.close.toLua(reader));
        if(r.pos != null)                             // 036.2: what the SHEET says this widget's layout is — the
            r.pos.toLua(reader, t);                   //   verb above it is read with widget:position(), which answers
        if(r.size != null)                            //   where the widget actually IS. 036.3: `position` or
            t.set("size", LuaWidget.whTable(r.size)); //   `anchor`, whichever the rule was written with
        return t;
    }
}
