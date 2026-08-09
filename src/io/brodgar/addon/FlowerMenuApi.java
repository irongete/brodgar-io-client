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
 * <p><b>The finder lives here</b> and {@code ActApi} calls it (D-103, one mechanism one door): the single open
 * menu is the first {@link FlowerMenu} in a recursive walk of the UI root, which is exact rather than
 * approximate because an open menu grabs mouse <i>and</i> keyboard, so only one is ever really up.
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
                        + " one\" — picking a petal is hafen.act():flower(label)");
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
        Section.install(hafen, "flowermenu", menu);
    }

    // ---- the open menu ---------------------------------------------------------------------------

    /**
     * The single OPEN radial context menu, or {@code null} if none is up — the first {@link FlowerMenu} in a
     * recursive walk of the UI root. Shared with {@code ActApi}'s {@code flower(label)}, which is the older
     * door onto the same widget.
     */
    static FlowerMenu open() {
        UI u = AddonManager.ui;
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

    // ---- the seams -------------------------------------------------------------------------------

    /**
     * A menu finished opening — the end of {@code FlowerMenu.added()}, the only point where the petal set is
     * complete. Fires {@code FlowerMenuOpened} with the captions, once per menu.
     */
    static void opened(FlowerMenu fm) {
        if((fm == null) || live.containsKey(fm))
            return;
        live.put(fm, null);
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
