package io.brodgar.addon;

import haven.FlowerMenu;
import haven.UI;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;

import java.util.Map;
import java.util.WeakHashMap;


/**
 * {@code hafen.flowermenu()} — the <b>open radial context menu</b> (spec {@code 047-flowermenu}), the ring of
 * petals a right-click puts up, plus the two events that say when one comes and goes.
 *
 * <p><b>The section IS the open menu</b>, not a wrapper around one. A petal set is frozen from the moment the
 * menu opens until it dies, so {@code :list()} hands back plain <b>labels</b> rather than entities: there is
 * nothing for the live-object machinery to track, and a petal that outlived its menu would answer questions
 * nobody asks. With no menu open every read answers ({@code {}}, {@code 0}) rather than throwing — a menu is
 * the player's, and "none is up" is the normal state, not an error.
 *
 * <p><b>{@code :gob()} is a correlation, not a message</b> (047.3). The server's {@code "sm"} carries captions
 * and nothing else, so <i>which object this ring belongs to</i> is something the client works out for itself:
 * {@link ClickToken} records the gob a press resolved to, keyed on the press point the menu will place itself
 * at, and the menu claims it once when it opens. Everything the correlation cannot vouch for answers
 * {@code nil} — an inventory item's menu, the Kin window's, one the player's next click intervened on.
 *
 * <p><b>The write half is protected</b> (047.2): {@code :select(label|n)} and {@code :cancel()} commit a choice, so
 * they sit behind their own per-addon {@code flowermenu.*} keys like every other write, and they drive
 * {@link FlowerMenu#choose} rather than re-encoding {@code wdgmsg("cl", num)} (D-009) — which is the only reason
 * a client-side petal keeps handling itself. They are also the one half that <b>throws</b> instead of answering:
 * see {@link #required}.
 *
 * <p><b>The finder lives here</b>, and since 048.7 it is the only thing that does (D-103, one mechanism one
 * door): the single open menu is the first {@link FlowerMenu} in a recursive walk of the UI root, which is
 * exact rather than approximate because an open menu grabs mouse <i>and</i> keyboard, so only one is ever
 * really up. {@code ActApi} used to borrow it for {@code hafen.act():flower(label)}, the older door onto the
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

    /**
     * Build {@code hafen.flowermenu()} for {@code owner}. From installHafen. A plain section object whose
     * verbs all answer with no menu open.
     */
    static void installFlowerMenu(LuaTable hafen, final Addon owner) {
        LuaTable menu = new LuaTable();
        // list() — the open menu's petal captions, as strings, in ring order (the order the ring is numbered
        // in, which is the order a petal is addressed by). An empty array when no menu is open.
        menu.set("list", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "flowermenu", "list");
                if(Args.passed(a, 2))
                    throw new LuaError("hafen.flowermenu():list() takes no arguments: a petal is a bare"
                        + " label with no field to filter on, and a string here would read as \"pick this"
                        + " one\" — picking a petal is hafen.flowermenu():select(label)");
                String[] names = names(open());
                LuaTable t = new LuaTable();
                for(int i = 0; i < names.length; i++)
                    t.set(i + 1, LuaValue.valueOf((names[i] == null) ? "" : names[i]));
                return t;
            }
        });
        // count() — how many petals the open menu has; 0 when none is open. The arity sibling of list().
        menu.set("count", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "flowermenu", "count");
                if(Args.passed(a, 2))
                    throw new LuaError("hafen.flowermenu():count() takes no arguments: there is nothing to"
                        + " filter on — a petal is a bare label");
                return LuaValue.valueOf(names(open()).length);
            }
        });
        // gob() — the game object the open menu was opened ON, or nil. A CORRELATION, not something the server
        // sends (see ClickToken): the answer is the gob the press that put this ring up resolved to, and it is
        // nil for a menu opened from an inventory item, for the Kin window's own menu, and whenever any other
        // press intervened. Unprotected: it names what is already on screen.
        menu.set("gob", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "flowermenu", "gob");
                if(Args.passed(a, 2))
                    throw new LuaError("hafen.flowermenu():gob() takes no arguments: there is one open menu"
                        + " and it was opened on one object — to read that object, call it bare");
                long id = gobOf(open());
                return (id < 0) ? LuaValue.NIL : LuaGob.of(owner, id);
            }
        });
        // select(label | n) — pick a petal of the OPEN menu, exactly as a click on it does: by its caption
        // (matched exactly, case-insensitively) or by its 1-based position on the ring, the same index :list()
        // hands back and the same number the menu's own 1..9 keys use. PROTECTED (D-027): it commits a choice.
        menu.set("select", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "flowermenu", "select");
                AddonManager.requirePermission(owner, Permission.FLOWERMENU_SELECT);
                select(Args.required(a, 2, "hafen.flowermenu():select", "key"));
                return LuaValue.NIL;
            }
        });
        // cancel() — close the open menu with nothing chosen, exactly as Esc and a click away do. PROTECTED: it
        // takes the player's menu off the screen, which is as much a commitment as picking from it.
        menu.set("cancel", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "flowermenu", "cancel");
                AddonManager.requirePermission(owner, Permission.FLOWERMENU_CANCEL);
                if(Args.passed(a, 2))
                    throw new LuaError("hafen.flowermenu():cancel() takes no arguments: there is one open"
                        + " menu and cancelling it chooses nothing — to pick a petal, use"
                        + " hafen.flowermenu():select(label|n)");
                required("hafen.flowermenu():cancel").choose(null);   // the very call Esc makes
                return LuaValue.NIL;
            }
        });
        Section.install(hafen, "flowermenu", menu);
    }

    // ---- the open menu ---------------------------------------------------------------------------

    /**
     * The single OPEN radial context menu, or {@code null} if none is up — the first {@link FlowerMenu} in a
     * recursive walk of the UI root. Shared with {@code ActApi}'s {@code flower(label)}, which is the older
     * door onto the same widget.
     */
    static FlowerMenu open() {
        UI u = AddonManager.host();
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
     * without the manager's live one; {@link AddonManager#host()} is the fallback for a menu asked before it is
     * parented.
     */
    private static haven.Coord lcc(FlowerMenu fm) {
        if((fm != null) && (fm.ui != null))
            return fm.ui.lcc;
        UI u = AddonManager.host();
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
     * The open menu, or a refusal naming {@code verb} — the "no menu is open" door both write verbs take.
     * Reads answer with no menu up because none being up is the ordinary state; a write cannot, because there
     * is no petal to commit to and silently doing nothing is the failure an automation never notices.
     */
    static FlowerMenu required(String verb) {
        FlowerMenu fm = open();
        if(fm == null)
            throw new LuaError(verb + ": no radial menu is open (hafen.flowermenu():count() is 0). A menu is"
                + " put up by a right-click and lives about a second, so pick from a FlowerMenuOpened handler"
                + " or a timer armed from one — nothing you type can reach the client while one is up.");
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
     * {@code hafen.flowermenu():select} backing — resolve {@code key} against the open menu and choose that
     * petal. A <b>string</b> is a caption, matched exactly and case-insensitively (an action commits, so it
     * matches precisely where a read would take a substring); a <b>number</b> is the 1-based position on the
     * ring, which is real identity here — it is the index {@code :list()} answers with and the {@code 1}..{@code 9}
     * key the menu itself accepts. The two are told apart by {@link LuaValue#type()}, never {@code isstring()}:
     * in LuaJ a number answers {@code isstring()} too, and {@code :select("3")} means the petal <i>labelled</i>
     * "3".
     */
    private static void select(LuaValue key) {
        selectOn(required("hafen.flowermenu():select"), key);
    }

    /**
     * {@link #select} with the menu already found — the whole resolution, split off so it can be driven against a
     * hand-built {@link FlowerMenu} with no {@code UI} behind it (the finder is the one part that needs one).
     */
    static void selectOn(FlowerMenu fm, LuaValue key) {
        final String verb = "hafen.flowermenu():select";
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
                + " hafen.flowermenu():list() again");
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
        AddonManager.fireFlowerMenu("FlowerMenuOpened", names(fm), null);
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
        AddonManager.fireFlowerMenu("FlowerMenuClosed", null, (label != null) ? label : chosen);
    }
}
