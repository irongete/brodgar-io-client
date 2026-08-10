package io.brodgar.addon;

import haven.GameUI;
import haven.Makewindow;
import haven.Speedget;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.VarArgFunction;


/**
 * Crafting read/make ({@code hafen.craft}) + movement speed ({@code hafen.speed}) — the two sections this file
 * still installs. Each carries a protected write ({@code craft():current():make(all)},
 * {@code speed():current(n)}) behind {@code requirePermission}, each with its own declared per-addon key. No
 * lifecycle/tick/teardown state — these are invoked only from Lua callbacks. Not instantiable.
 *
 * <p><b>{@code hafen.act()} is gone</b> (048). It was the one section grouped by PERMISSION rather than by what
 * it acts on, and 048 dissolved it verb by verb onto the things each verb changes: walking the character is
 * {@code hafen.player():move(p)} and clicking an object {@code gob:click(button, mods)} (048.1); the held-item
 * gesture is {@code hafen.player():hand():use(target, mods)}, on a cursor that is <i>nil</i> when it is empty
 * (048.2); what you can do TO an item is on the item — {@code item:use/:take/:drop/:transfer} (048.3); placing
 * and area-selecting are {@code hafen.world():place/:select}, beside the {@code snapPlace}/{@code snapAngle}
 * that prepare their arguments (048.4); a menu action is {@code hafen.menugrid():get(name):use()}, which gained
 * the same permission (048.5); the escape hatch is {@code widget:send(msg, ...)}, where the receiver IS the
 * target (048.6); and a petal is {@code hafen.flowermenu():select(label|n)} (048.7, which also deleted
 * {@code enabled()} — a running addon is granted exactly what it declared, so the question answered
 * itself). Every one of those spellings, and {@code hafen.act} itself, throws from {@link Retired} naming its
 * new home.
 */
final class ActApi {
    private ActApi() {}

    /**
     * Build {@code hafen.craft()} for {@code owner}. From installHafen. A section of <b>one verb</b>:
     * {@code :current()} is the recipe the player has open, as a {@link LuaCraft} entity that carries the
     * recipe's slots <i>and</i> its protected {@code :make(all)} — the button belongs to the recipe, not to a
     * namespace hovering above it.
     *
     * <p><b>{@code :current()} is {@code nil} when no recipe is open</b>, which is §2.2's own rule for a
     * distinguished member and not a coin toss. The alternative — an inert Craft whose {@code :exists()} is
     * false — reads tidier and is worse where it counts: {@code if hafen.craft():current() then} is the guard
     * every crafting addon already writes, and an always-truthy entity turns each of them into a guard that
     * passes and then reads nothing, which is the silent failure this grammar exists to delete. The section
     * itself is not the collection (§2.1's <i>a section of one thing IS that thing</i>) for the same reason:
     * the section object is minted once at install and must always answer, and an open recipe usually is not
     * there.
     */
    static void installCraft(LuaTable hafen, final Addon owner) {
        LuaTable craft = new LuaTable();
        // current() — the open recipe, or nil. The protected make() lives on what this hands back.
        craft.set("current", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "craft", "current");
                if(Args.passed(a, 2))
                    throw new LuaError("hafen.craft():current() takes no arguments — it reads the recipe you"
                        + " have open, and which recipe that is is the player's choice");
                return LuaCraft.of(owner, makewindow());
            }
        });
        Section.install(hafen, "craft", craft);
    }

    /**
     * Build {@code hafen.speed()} (movement-speed read + protected write, A7) for {@code owner}. From installHafen.
     * The {@code get}/{@code set} pair collapses onto <b>one name</b> whose arity is the verb (R2):
     * {@code :current()} reads the selected speed and {@code :current(n)} selects it and chains. The read half
     * is unprotected and the write half keeps the permission it always had, now the {@code speed.current} key.
     */
    static void installSpeed(LuaTable hafen, final Addon owner) {
        LuaTable speed = new LuaTable();
        // current() / current(n) — the whole of the old get()/set() pair. The write is protected (D-027/D-028) and
        // returns the section object, so a run of writes chains like every other setter in the API.
        speed.set("current", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = a.arg1();
                Section.self(self, "speed", "current");
                LuaValue n = Args.written(a, 2, "hafen.speed():current", "n");
                if(n == null) {                            // the read arity
                    Speedget s = speedget();
                    return (s == null) ? LuaValue.NIL : LuaValue.valueOf(s.cur);
                }
                AddonManager.requirePermission(owner, Permission.SPEED_CURRENT);
                if(!n.isnumber())
                    throw new LuaError("hafen.speed():current(n): n must be a number"
                        + " (0=crawl 1=walk 2=run 3=sprint)");
                actSpeedSet(n.toint());
                return self;
            }
        });
        speed.set("max", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Section.self(self, "speed", "max");
                Speedget s = speedget();
                return (s == null) ? LuaValue.NIL : LuaValue.valueOf(s.max);
            }
        });
        // name([n]) — the display name of a speed. n is an ADDRESS, not a value being written: with none, the
        // one currently selected. An explicit nil is still an accident (§2.9) and is refused rather than read
        // as "the current one", which is the silent misread the discipline exists for.
        speed.set("name", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "speed", "name");
                LuaValue n = Args.written(a, 2, "hafen.speed():name", "n");
                int idx;
                if(n != null) {
                    if(!n.isnumber())
                        throw new LuaError("hafen.speed():name(n): n must be a number 0..3");
                    idx = n.toint();
                } else {                                   // no argument → the current speed
                    Speedget s = speedget();
                    if(s == null)
                        return LuaValue.NIL;
                    idx = s.cur;
                }
                return speedName(idx);
            }
        });
        Section.install(hafen, "speed", speed,
                        "hafen.speed.get() is now hafen.speed():current() and hafen.speed.set(n) is"
                        + " hafen.speed():current(n)");
    }

    // ---- what the protected tier left behind (048) --------------------------------------------------
    // Nothing. The PROTECTED automation surface is still exactly what it always was — a Widget.wdgmsg from a
    // bound widget, literally what a player click would send, so the client stays server-authoritative (an addon
    // can do only what a player could do; the permission is about user control, not a client exploit — spec 12)
    // — but every one of those sends now leaves from the thing it changes rather than from a section named for
    // the permission they shared. The last two verbs went in 048.7:
    //   enabled() is DELETED rather than moved. AddonManager.actionsGranted(owner) was literally
    // a read of the CALLER's own manifest file — a static fact about it — and D-028 had already
    // removed the global switch it was built to report, so the only caller it could ever answer `false` was one
    // that can read the same answer in its own manifest.json. A feature-detection verb whose answer is a fact
    // about the caller is not a feature detector.
    //   flower(label) is DELETED because 047.2 already built the better door: hafen.flowermenu():select(label|n)
    // RAISES where flower returned a bare false, takes a ring position as well as a caption, and has :cancel()
    // beside it. actFlower had been delegating to FlowerMenuApi since 047.1, so what stood here was the old door
    // D-103 requires closing — and its one pure helper (the case-insensitive petal lookup) moved to its single
    // remaining caller, FlowerMenuApi.petalIndex.
    // The two surviving halves of this file — hafen.craft() and hafen.speed() — never belonged to that section:
    // they are named for what they act on, which is the shape 048 gave the other nine verbs.

    // ---- movement speed (A7: hafen.speed()) ------------------------------------------------------
    // The speed selector is a Speedget widget (crawl/walk/run/sprint) the server places under the HUD.
    // It has no named GameUI field, so we locate it with the 1d-1 Locator (a children(Class) subtree
    // walk from the HUD) — the same way vitals finds its IMeters. Both fields we read (cur = current
    // speed, max = highest currently-selectable speed) are public ints, so this is a zero-haven-edit
    // read. All calls run on the UI thread (addon tick / REPL). Selecting a speed is :current(n), the
    // write half of the one name that replaced the get()/set() pair, and it is the protected Phase-4 tier.

    /** The (unique) movement-speed widget under the HUD, or {@code null} before it has streamed in. */
    private static Speedget speedget() {
        GameUI g = AddonManager.gui();
        if(g == null)
            return null;
        for(Speedget s : g.children(Speedget.class))   // recursive subtree walk; take the first
            return s;
        return null;
    }

    /** The display name of speed {@code n} (0..3) from the widget's own tooltips, or nil if out of range. */
    private static LuaValue speedName(int n) {
        String[] tips = Speedget.tips;                 // "Crawl"/"Walk"/"Run"/"Sprint" (resource tooltips)
        if((tips == null) || (n < 0) || (n >= tips.length))
            return LuaValue.NIL;
        String t = tips[n];
        return (t == null) ? LuaValue.NIL : LuaValue.valueOf(t);
    }

    /**
     * {@code hafen.speed():current(n)} backing (4g, protected) — select movement speed {@code n} (0..3) via the
     * client's own {@link Speedget#set} (wrap-not-reimplement, D-009 → {@code wdgmsg("set", n)}). The server is
     * authoritative on whether a speed is currently allowed (e.g. sprint may be locked); this only sends the
     * request, exactly as clicking/hotkeying that speed would. Throws for out-of-range {@code n} or before the
     * selector exists.
     */
    private static void actSpeedSet(int n) {
        if((n < 0) || (n > 3))
            throw new LuaError("hafen.speed():current(n): n must be 0..3 (0=crawl 1=walk 2=run 3=sprint), got " + n);
        Speedget s = speedget();
        if(s == null)
            throw new LuaError("hafen.speed():current(n): no speed selector (not in the world yet)");
        s.set(n);
    }

    // ---- crafting (A8: hafen.craft) --------------------------------------------------------------
    // The crafting/recipe window is a Makewindow (@RName("make")) the server places under the HUD when
    // the player opens a recipe. It is wrapped in GameUI.makewnd (a private Window), so — like A7's speed
    // selector — we locate the content widget with the 1d-1 Locator (a children(Class) subtree walk from
    // the HUD), not a named GameUI field. A recipe carries: rcpnm (the recipe name), inputs (ingredient
    // slots), outputs (product slots), qmod (quality-affecting input resources) and tools (required tool
    // resources). All backings are public → zero haven edit, like A7/A6/A4/A2.
    //
    // The READS and the protected make() moved onto LuaCraft with 039.13 (the entity owns them, keyed by the
    // WINDOW: the server builds a fresh one per recipe, so opening another recipe ends this Craft rather
    // than changing it). What stays here is locating that window.

    /** The (unique) crafting window content under the HUD, or {@code null} if no recipe is open. */
    static Makewindow makewindow() {
        GameUI g = AddonManager.gui();
        if(g == null)
            return null;
        for(Makewindow m : g.children(Makewindow.class))   // recursive subtree walk; take the first
            return m;
        return null;
    }
}
