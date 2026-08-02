package io.brodgar.addon;

import haven.Fonts;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

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
 *       widget rather than a site ({@code window}, {@code inventory}) — is resolved per widget against the live
 *       tree, which is <b>C1b</b>. Here it parses fine and is then <b>silently inert</b>: never an error, so a
 *       sheet written for C1b loads today, unstyled, instead of blowing up. Such a rule is dropped after
 *       validation; C1b is what keeps it.</li>
 * </ul>
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
    /** The style properties a rule may carry. {@code bg}/{@code border}/{@code pad} and the textures arrive in C2. */
    private static final String PROPS = "\"font\" and \"color\"";

    /** One kept rule: a SITE key and the properties it fills that site with. Each is independently optional. */
    private static final class Rule {
        final String site;        // a Fonts.SCOPES name ("default" for the `*` key)
        final FontHandle font;    // the rule's `font` property, or null (a rule may carry only a colour)
        final Color color;        // the rule's `color` property, or null (a rule may carry only a font)

        Rule(String site, FontHandle font, Color color) {
            this.site = site;
            this.font = font;
            this.color = color;
        }
    }

    /** The site rules, in the order the sheet listed them. Distinct sites, so the order never decides a winner. */
    private final List<Rule> rules;

    private Sheet(List<Rule> rules) {
        this.rules = rules;
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
            return LuaValue.NIL;
        }
        if(!arg.istable())
            throw new LuaError("hafen.ui.skin(sheet): expected a table of [\"selector\"] = { font = h } rules, got "
                + arg.typename() + " — hafen.ui.skin(nil) drops this addon's sheet");
        Sheet s = parse(arg);
        drop(owner);          // an addon owns ONE sheet: the previous one's entries leave first...
        s.install(owner);     // ...then this one fills its site keys (so a site it no longer names falls back)
        owner.skin = s;
        return LuaValue.NIL;
    }

    /**
     * Drop {@code owner}'s sheet: each site it filled falls back to whatever is beneath it (another addon's
     * sheet, else the stock foundry). Deliberately per-scope rather than {@code Fonts.removeOwner}, which would
     * also sweep this addon's per-instance {@code widget:setFont} overrides — those are not part of the sheet.
     */
    static void drop(Addon owner) {
        Sheet s = owner.skin;
        if(s == null)
            return;
        owner.skin = null;
        for(int i = 0; i < s.rules.size(); i++)
            Fonts.reset(s.rules.get(i).site, owner);   // bumps gen only when something was actually removed
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
        for(int i = 0; i < rules.size(); i++) {
            Rule r = rules.get(i);
            if((r.font == null) && (r.color == null))
                continue;      // nothing to fill the stack with — keep the identity fast path intact
            FontHandle f = r.font;
            Fonts.push(r.site, owner,
                       (f == null) ? null : f.font,
                       (f == null) ? null : f.size,
                       (f == null) ? null : f.aa,
                       r.color);
        }
    }

    // ---- parsing -----------------------------------------------------------------------------------

    /** Parse a Lua sheet table into its site rules. Every key is validated; only the site ones are kept (C1a). */
    private static Sheet parse(LuaValue t) {
        List<Rule> rules = new ArrayList<Rule>();
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
            String site = siteOf(Selector.parse(key));    // a bad key errors exactly as it does in hafen.ui(sel)
            Rule r = propsOf(key, v, site);               // validated for EVERY key: what is inert is the key, not the typo
            if(site == null)
                continue;        // a TREE key: valid grammar, resolves nowhere YET (C1b) — silently inert, never an error
            rules.add(r);
        }
        return new Sheet(rules);
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
     * The properties of one rule ({@code font}, {@code color}), each {@code null} when the rule carries none. An
     * unknown property is an <b>error</b> — unlike an unresolved key, a misspelt property has no future meaning to
     * wait for, and silently doing nothing is the worst way to answer a typo (D-072). It is checked for <b>every</b>
     * key, including a tree key whose rule is then dropped: what C1a defers is resolving the key, not reading the
     * rule, so a sheet written for C1b still has its typos caught today.
     */
    private static Rule propsOf(String key, LuaValue props, String site) {
        FontHandle font = null;
        Color color = null;
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
                    throw new LuaError("hafen.ui.skin[\"" + key + "\"].font: expected a font handle — hafen.font(\"sans\")"
                        + " or hafen.asset(\"fonts/Inter.ttf\"), optionally :derive{size=…}");
            } else if("color".equals(p)) {
                color = pv.istable() ? AddonManager.luaColor(pv, null) : null;
                if(color == null)
                    throw new LuaError("hafen.ui.skin[\"" + key + "\"].color: expected a colour table with 0..255"
                        + " components — { 200, 210, 200 } or { r = 200, g = 210, b = 200, a = 255 }");
            } else {
                throw new LuaError("hafen.ui.skin[\"" + key + "\"]: \"" + pk.tojstring()
                    + "\" is not a style property — the properties this client ships are " + PROPS);
            }
        }
        return new Rule(site, font, color);
    }
}
