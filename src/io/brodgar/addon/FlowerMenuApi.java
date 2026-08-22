package io.brodgar.addon;

import haven.FlowerMenu;
import haven.UI;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;


/**
 * {@code s:flowermenu()} — the <b>open radial context menu</b> (spec {@code 047-flowermenu}), the ring of
 * petals a right-click puts up, plus the two events that say when one comes and goes.
 *
 * <p><b>The section IS the open menu</b>, not a wrapper around one. A petal set is frozen from the moment the
 * menu opens until it dies, so {@code :list()} hands back plain <b>labels</b> rather than entities: there is
 * nothing for the live-object machinery to track, and a petal that outlived its menu would answer questions
 * nobody asks. With no menu open every read answers ({@code {}}, {@code 0}) rather than throwing — a menu is
 * the player's, and "none is up" is the normal state, not an error.
 *
 * <p><b>And a menu is one session's</b> (077.4). It looks screen-shaped, because a right-click is a mouse
 * gesture and the client has one pointer — but the section is the open <b>menu</b>, and a menu is a widget in
 * one session's tree rather than the gesture that raised it. So the finder walks the <i>named</i> session's
 * own root: a ring left up on a character the player then tabbed away from is still open, still readable, and
 * still selectable, and the section on every other session answers the same nothing it answers with no menu
 * up. That is also what makes {@code :gob()} resolve in the right object cache — the id the click recorded is
 * an id in the tree the menu stands in.
 *
 * <p><b>{@code :gob()} is a correlation, not a message</b> (047.3). The server's {@code "sm"} carries captions
 * and nothing else, so <i>which object this ring belongs to</i> is something the client works out for itself:
 * {@link ClickToken} records the gob a press resolved to, keyed on the press point the menu will place itself
 * at, and the menu claims it once when it opens. Everything the correlation cannot vouch for answers
 * {@code nil} — an inventory item's menu, the Kin window's, one the player's next click intervened on.
 *
 * <p><b>The write half is protected</b> (047.2): {@code :select(label|n)} and {@code :cancel()} commit a choice, so
 * they sit behind their own per-addon {@code flowermenu.*} keys like every other write. Each keeps the
 * <b>one</b> key it has whichever character it is addressed at (077.4) — a key names the action, not the
 * target, and the player could have tabbed to that character and picked the petal themselves. They drive
 * {@link FlowerMenu#choose} rather than re-encoding {@code wdgmsg("cl", num)} (D-009) — which is the only reason
 * a client-side petal keeps handling itself. They are also the one half that <b>throws</b> instead of answering:
 * see {@link #required}.
 *
 * <p><b>The finder lives here</b>, and since 048.7 it is the only thing that does (D-103, one mechanism one
 * door): that session's open menu is the first {@link FlowerMenu} in a recursive walk of <b>its</b> UI root,
 * which is exact rather than approximate because an open menu grabs mouse <i>and</i> keyboard, so one tree
 * only ever really holds one. {@code ActApi} used to borrow it for {@code hafen.act():flower(label)}, the older door onto the
 * same {@code choose}; that verb is gone and {@link #select} is the one way onto a petal.
 *
 * <p><b>The events.</b> Three {@code // addon:} seams in {@link FlowerMenu} drive them, and the choice of seam
 * is the design: {@code added()}'s <i>end</i> is the only point where the petal set is complete (the fork's
 * voice petal replaces {@code opts} two lines earlier); both {@code uimsg} branches are the commit point, and
 * the one seam a <i>client-side</i> menu also takes ({@code BuddyWnd} overrides {@code choose} and calls
 * {@code uimsg} by hand, never {@code super}); and a {@code destroy()} override is the fallback that keeps
 * <i>every Opened is followed by exactly one Closed</i> true when the widget simply dies. A fourth line at the
 * head of {@code choose(Petal)} records the petal being chosen, so the client-side voice petal — which cancels
 * the server's menu and handles itself — reports its own label instead of reading as "nothing chosen".
 *
 * <p>All of this runs on the UI thread under the monitor the caller already holds ({@code added} from
 * {@code AddWidget.run}, {@code uimsg} from {@code UiMessage.run}'s {@code synchronized(ui)}, {@code destroy}
 * from the closing animation's tick), so the Lua raised here never races other Lua. Not instantiable.
 */
final class FlowerMenuApi {
    private FlowerMenuApi() {}

    /**
     * The menus this layer has announced and not yet closed, each mapped to the label {@code choose(Petal)}
     * recorded for it ({@code null} until a petal is picked). <b>Key presence is the whole state</b>: it says
     * {@code FlowerMenuOpened} fired and {@code FlowerMenuClosed} has not, which is what makes the three
     * closing seams collapse to exactly one event however the menu ended.
     *
     * <p>Weak-keyed so a session that ends without destroying its widgets leaves nothing behind; identity
     * semantics come free, since {@link haven.Widget} does not override {@code equals}. UI thread only.
     */
    private static final Map<FlowerMenu, String> live = new WeakHashMap<FlowerMenu, String>();

    /**
     * The gob each announced menu was opened <b>on</b>, for {@code :gob()} (047.3) — claimed from
     * {@link ClickToken} once, at the moment the menu opens, and never re-derived. A menu with no attribution
     * (an inventory item's, the Kin window's, one the player's own next click invalidated) simply has no entry.
     *
     * <p><b>Deliberately NOT {@link #live}.</b> That map is the event-pairing state and a close takes the key
     * out; this one is the menu's own identity and dies with the widget, because {@code :gob()} has to describe
     * the same menu {@code :list()} and {@code :count()} do — and those keep answering through the 0.25–0.75 s
     * closing animation, during which the widget is still in the tree. One map removed at the close and one
     * held to the end is what makes all three reads agree about <i>which menu</i> is being described.
     *
     * <p>Weak-keyed for the same reason as {@link #live}, and the value is an id rather than a {@link haven.Gob},
     * so a stashed menu can never pin a despawned object. UI thread only.
     */
    private static final Map<FlowerMenu, Long> clicked = new WeakHashMap<FlowerMenu, Long>();

    /** {@code s:flowermenu()} — how this section is reached, and so how every one of its messages spells itself. */
    static final String FM = "session:flowermenu()";

    /**
     * Build the flower-menu section object for {@code (owner, user)} — <b>the menu THAT character has
     * open</b>, reached as {@code s:flowermenu()} (077.4). A plain section object whose reads all answer with
     * no menu up.
     *
     * <p><b>077.4: it is built per {@code (addon, session)}</b> and hung on the interned Session handle, the
     * shape {@link WorldApi#world} established — so {@code s:flowermenu() == s:flowermenu()}. Every verb
     * finds the menu in <b>that</b> session's own tree, so a ring left open on a character the player tabbed
     * away from reads and picks exactly as the drawn one's does.
     */
    static LuaValue flowermenu(final Addon owner, final String user) {
        LuaTable menu = new LuaTable();
        // list() — the open menu's petal captions, as strings, in ring order (the order the ring is numbered
        // in, which is the order a petal is addressed by). An empty array when no menu is open.
        menu.set("gob", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "flowermenu", "gob", FM);
                if(Args.passed(a, 2))
                    throw new LuaError(FM + ":gob() takes no arguments: there is one open menu"
                        + " and it was opened on one object — to read that object, call it bare");
                long id = gobOf(open(user));
                // The id was recorded by a press in the tree this menu stands in, so it resolves in THAT
                // session's object cache (077.4) — the same one s:world():gob():get(id) reads.
                return (id < 0) ? LuaValue.NIL : LuaGob.of(owner, id);
            }
        });
        // select(label | n) — pick a petal of the OPEN menu, exactly as a click on it does: by its caption
        // (matched exactly, case-insensitively) or by its 1-based position on the ring, the same index :list()
        // hands back and the same number the menu's own 1..9 keys use. PROTECTED (D-027): it commits a choice.
        // The one key is unchanged whichever character it is addressed at (077.4).
        menu.set("select", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "flowermenu", "select", FM);
                AddonManager.requirePermission(owner, Permission.FLOWERMENU_SELECT);
                select(user, Args.required(a, 2, FM + ":select", "key"));
                return LuaValue.NIL;
            }
        });
        // cancel() — close that character's open menu with nothing chosen, exactly as Esc and a click away
        // do. PROTECTED: it takes a menu the player put up off its screen, which is as much a commitment as
        // picking from it. Its one key is unchanged too.
        menu.set("cancel", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "flowermenu", "cancel", FM);
                AddonManager.requirePermission(owner, Permission.FLOWERMENU_CANCEL);
                if(Args.passed(a, 2))
                    throw new LuaError(FM + ":cancel() takes no arguments: there is one open"
                        + " menu and cancelling it chooses nothing — to pick a petal, use "
                        + FM + ":select(label|n)");
                required(user, FM + ":cancel").choose(null);   // the very call Esc makes
                return a.arg1();          // the section: every ending chains
            }
        });
        // 091/A-078: the section IS the ring, and a ring is a set of PETALS. It was a set of caption
        // STRINGS -- the only :list() in the API whose members could not be passed back to anything, so
        // picking one meant re-spelling its caption or counting its position. A Petal wraps (user, index)
        // and re-resolves, so one held past the close reports :exists() false, exactly as a Buff or a Craft
        // does -- and both of those are shorter-lived than a menu.
        return LuaCollection.create(FM, new LuaCollection.Source() {
            public List<LuaValue> members() {
                List<LuaValue> out = new ArrayList<LuaValue>();
                String[] ns = names(open(user));
                for(int i = 0; i < ns.length; i++)
                    out.add(LuaPetal.of(owner, user, i));
                return out;
            }

            /** A petal is its caption, so a string filter is a substring test over that. */
            public boolean named() {
                return true;
            }

            public String needle(LuaValue member) {
                return LuaPetal.needle(member);
            }

            public boolean addressable() {
                return true;
            }

            public LuaValue getMember(LuaValue key) {
                if(key.type() != LuaValue.TNUMBER)
                    throw new LuaError(FM + ":get(n): the key is a petal's 1-based position, the same"
                        + " number petal:index() answers and the 1..9 key the ring is picked with, got "
                        + key.typename());
                int n = key.toint();
                String[] ns = names(open(user));
                return ((n < 1) || (n > ns.length)) ? LuaValue.NIL : LuaPetal.of(owner, user, n - 1);
            }

            /** The key is a petal's 1-based position on the open ring. */
            public String keyName() {
                return "n";
            }
        }, menu);
    }

    // ---- the open menu ---------------------------------------------------------------------------

    /**
     * <b>That character's</b> open radial context menu, or {@code null} if none is up — the first
     * {@link FlowerMenu} in a recursive walk of <b>its own</b> UI root (077.4).
     *
     * <p>One tree only ever really holds one, because an open menu grabs the mouse and the keyboard. It is
     * the session's root and never {@link AddonManager#screen()}: a ring the player left up on a character and
     * then tabbed away from is still parented to that session's tree, which is what makes it readable and
     * pickable at a distance — and asking the drawn tree would have called it closed. A session the client
     * no longer holds, or one whose UI is between trees, simply has no menu.
     */
    static FlowerMenu open(String user) {
        UI u = AddonManager.sessionui(user);
        if((u == null) || (u.root == null))
            return null;
        for(FlowerMenu fm : u.root.children(FlowerMenu.class))   // only one is ever open (it grabs input)
            return fm;
        return null;
    }

    /**
     * The petal captions of {@code fm}, in ring order, or an empty array when there is no menu. Nulls are
     * preserved: {@code ActApi}'s matcher skips them, and the Lua reader substitutes {@code ""}.
     */
    /** The caption on {@code user}'s open ring at 0-based {@code i}, or {@code null} — no ring, or gone. */
    static String petalLabel(String user, int i) {
        String[] ns = names(open(user));
        return ((i < 0) || (i >= ns.length)) ? null : ns[i];
    }

    /** Pick the petal at 0-based {@code i} on {@code user}'s ring — {@code petal:select()}'s own door. */
    static void selectPetal(String user, int i) {
        selectOn(required(user, FM + ":select"), LuaValue.valueOf(i + 1));
    }

    static String[] names(FlowerMenu fm) {
        FlowerMenu.Petal[] opts = (fm == null) ? null : fm.opts;
        if(opts == null)
            return new String[0];
        String[] names = new String[opts.length];
        for(int i = 0; i < opts.length; i++)
            names[i] = (opts[i] == null) ? null : opts[i].name;
        return names;
    }

    /** The gob id {@code fm} was opened on, or {@code -1} — no menu, or no attribution for this one. */
    static long gobOf(FlowerMenu fm) {
        Long id = (fm == null) ? null : clicked.get(fm);
        return (id == null) ? -1 : id.longValue();
    }

    /**
     * The press point a menu is placed at — {@code added()} does {@code c = parent.ui.lcc}, and that is the very
     * value {@link ClickToken} keys on. Read off the widget's own {@code ui} so a probe can drive the seam
     * without the manager's live one, and so the point is the one taken in the tree the ring stands in;
     * {@link AddonManager#screen()} is the fallback for a menu asked before it is parented, which is the only
     * case with no tree to ask — the press that would raise one lands on the screen.
     */
    private static haven.Coord lcc(FlowerMenu fm) {
        if((fm != null) && (fm.ui != null))
            return fm.ui.lcc;
        UI u = AddonManager.screen();
        return (u == null) ? null : u.lcc;
    }

    // ---- the write half (protected) --------------------------------------------------------------
    // select/cancel go through FlowerMenu.choose(Petal) and NEVER re-encode wdgmsg("cl", num) — the client's
    // own method is the door (D-009, wrap-not-reimplement), which is what makes a CLIENT-SIDE petal (the fork's
    // voice Mute/Unmute, BuddyWnd's whole kin menu) handle itself instead of being wrongly sent to the server.
    // Unlike the read half these THROW rather than answering: a menu lives for about a second, so "there was
    // nothing to pick" is a race the addon has to hear about, and the refusal names what IS open so the caller
    // can see the spelling it missed. (048.7 deleted hafen.act():flower(label), the older door onto the same
    // choose, which answered a bare false instead — so the raise is now the only answer there is.)

    /**
     * Index of the first petal name equal to {@code label} (case-insensitive), or {@code -1}. Pure/testable.
     *
     * <p>It lived in {@code ActApi} while {@code hafen.act():flower(label)} did too, and moved here with 048.7
     * when that verb was deleted: {@link #selectOn} is its only caller now, so the helper sits beside it.
     */
    static int petalIndex(String[] names, String label) {
        if(names == null)
            return -1;
        for(int i = 0; i < names.length; i++) {
            if((names[i] != null) && names[i].equalsIgnoreCase(label))
                return i;
        }
        return -1;
    }

    /**
     * <b>That character's</b> open menu, or a refusal naming {@code verb} — the "no menu is open" door both
     * write verbs take. Reads answer with no menu up because none being up is the ordinary state; a write
     * cannot, because there is no petal to commit to and silently doing nothing is the failure an automation
     * never notices. The refusal names the character asked about, since a ring being up on the screen says
     * nothing about the session the verb was addressed at (077.4).
     */
    static FlowerMenu required(String user, String verb) {
        FlowerMenu fm = open(user);
        if(fm == null)
            throw new LuaError(verb + ": no radial menu is open on " + user + " (" + FM + ":count() is 0). A"
                + " menu is put up by a right-click and lives about a second, so pick from a FlowerMenuOpened"
                + " handler or a timer armed from one — nothing you type can reach the client while one is"
                + " up.");
        return fm;
    }

    /** "1. Chop, 2. Pick branch" — the open ring, numbered, so a refusal names both ways of addressing it. */
    private static String offers(String[] names) {
        StringBuilder sb = new StringBuilder();
        for(int i = 0; i < names.length; i++) {
            if(i > 0)
                sb.append(", ");
            sb.append(i + 1).append(". ").append((names[i] == null) ? "" : names[i]);
        }
        return sb.toString();
    }

    /**
     * {@code s:flowermenu():select} backing — resolve {@code key} against the open menu and choose that
     * petal. A <b>string</b> is a caption, matched exactly and case-insensitively (an action commits, so it
     * matches precisely where a read would take a substring); a <b>number</b> is the 1-based position on the
     * ring, which is real identity here — it is the index {@code :list()} answers with and the {@code 1}..{@code 9}
     * key the menu itself accepts. The two are told apart by {@link LuaValue#type()}, never {@code isstring()}:
     * in LuaJ a number answers {@code isstring()} too, and {@code :select("3")} means the petal <i>labelled</i>
     * "3".
     */
    private static void select(String user, LuaValue key) {
        selectOn(required(user, FM + ":select"), key);
    }

    /**
     * {@link #select} with the menu already found — the whole resolution, split off so it can be driven against a
     * hand-built {@link FlowerMenu} with no {@code UI} behind it (the finder is the one part that needs one).
     */
    static void selectOn(FlowerMenu fm, LuaValue key) {
        final String verb = FM + ":select";
        String[] names = names(fm);
        int idx;
        if(key.type() == LuaValue.TNUMBER) {
            double d = key.todouble();
            idx = (int)d;
            if((d != idx) || (idx < 1) || (idx > names.length))
                throw new LuaError(verb + "(" + key.tojstring() + "): a position is a whole number 1.."
                    + names.length + " on the open menu — " + offers(names));
            idx--;
        } else if(key.type() == LuaValue.TSTRING) {
            idx = petalIndex(names, key.tojstring());
            if(idx < 0)
                throw new LuaError(verb + "(\"" + key.tojstring() + "\"): no petal is labelled that (the match"
                    + " is the whole caption, case-insensitive) — the open menu offers " + offers(names));
        } else {
            throw new LuaError(verb + "(key): key must be a petal's caption (a string, matched whole and"
                + " case-insensitively) or its 1-based position on the ring (a number) — got a "
                + key.typename());
        }
        FlowerMenu.Petal[] opts = fm.opts;
        if((opts == null) || (idx >= opts.length) || (opts[idx] == null))
            throw new LuaError(verb + ": the menu's petals changed while it was being read — read"
                + " " + FM + ":list() again");
        fm.choose(opts[idx]);   // the client's own selection, client-side petals and all (D-009)
    }

    // ---- the seams -------------------------------------------------------------------------------

    /**
     * A menu finished opening — the end of {@code FlowerMenu.added()}, the only point where the petal set is
     * complete. Fires {@code FlowerMenuOpened} with the captions, once per menu.
     */
    static void opened(FlowerMenu fm) {
        if((fm == null) || live.containsKey(fm))
            return;
        live.put(fm, null);
        // 047.3: claim the click that put this ring up, ONCE and here — the guard above is also what stops a
        // second announcement of the same menu from spending a fresh token on it.
        long g = ClickToken.take(lcc(fm));
        if(g >= 0)
            clicked.put(fm, Long.valueOf(g));
        // 079.4: whose ring it is, as the two events' last argument — the tree the menu went up in and
        // not the one on screen, because a ring stays up, and readable, when the player tabs away from it.
        AddonManager.fireFlowerMenu(AddonManager.userOf(fm), "FlowerMenuOpened", names(fm), null);
    }

    /**
     * A petal is being chosen — the head of {@code FlowerMenu.choose(Petal)}. Records the label so the
     * <b>client-side</b> voice petal, which cancels the server's menu rather than picking one of its petals,
     * closes carrying its own name instead of {@code nil}. The server's own {@code uimsg("act")} overrides it
     * with {@code opts[num].name}, which is authoritative.
     */
    static void choosing(FlowerMenu fm, FlowerMenu.Petal petal) {
        if((fm == null) || (petal == null) || !live.containsKey(fm))
            return;
        live.put(fm, petal.name);
    }

    /**
     * A menu ended — either {@code uimsg} branch or the {@code destroy()} fallback. Fires
     * {@code FlowerMenuClosed} <b>exactly once</b> per {@code FlowerMenuOpened}: whichever seam gets here
     * first takes the key out, and the later ones find nothing.
     *
     * @param label the label the server committed ({@code uimsg("act")}), or {@code null} to fall back to
     *              whatever {@link #choosing} recorded — which is {@code nil} for a cancel, an Esc or a click
     *              away, and the petal's own name for a client-side one.
     */
    static void closed(FlowerMenu fm, String label) {
        if((fm == null) || !live.containsKey(fm))
            return;
        String chosen = live.remove(fm);
        AddonManager.fireFlowerMenu(AddonManager.userOf(fm), "FlowerMenuClosed", null,
                                    (label != null) ? label : chosen);
    }
}
