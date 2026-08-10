package io.brodgar.addon;

import haven.FlowerMenu;
import haven.GameUI;
import haven.Indir;
import haven.Loading;
import haven.Makewindow;
import haven.MiniMap;
import haven.Resource;
import haven.Speedget;
import haven.UI;
import haven.Widget;
import haven.Window;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.TwoArgFunction;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.ZeroArgFunction;

import java.util.ArrayList;
import java.util.List;


/**
 * The protected automation subsystem (Phase 4: {@code hafen.act}) + crafting read/make ({@code hafen.craft}) +
 * movement speed ({@code hafen.speed}). Every act verb is a {@link haven.Widget#wdgmsg} from a bound widget
 * (literally what a player click sends, so the client stays server-authoritative), protected by
 * {@code requireActions} (the declared per-addon permission). No lifecycle/tick/teardown state — these are
 * invoked only from Lua callbacks. Not instantiable.
 */
final class ActApi {
    private ActApi() {}

    /**
     * Build {@code hafen.act()} (what is left of it) for {@code owner}. From installHafen.
     *
     * <p>A plain section object that is <b>emptying</b> (D-117): 048 moves every verb onto the thing it changes,
     * one task at a time, and the section stays mounted for whatever has not moved yet so that no verb ever
     * works under two names. What is left here is {@code enabled}, {@code raw} and {@code flower};
     * 048.7 deletes the section itself once they are gone. Every spelling that has already left throws from
     * {@link Retired}, naming its new home.
     */
    static void installAct(LuaTable hafen, final Addon owner) {
        LuaTable act = new LuaTable();
        act.set("enabled", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Section.self(self, "act", "enabled");
                return LuaValue.valueOf(AddonManager.actionsGranted(owner));
            }
        });
        // 048.1: moveTo and clickGob have LEFT — a verb lives with what it changes, so walking the character is
        // hafen.player():move(p) and clicking an object is gob:click(button, mods). Both old spellings throw
        // from Retired naming their replacement; the section stays mounted for the verbs still here (D-117).
        // 048.2: useItemOn has LEFT too, and it took the whole held-item gesture with it. The cursor is an
        // object now — hafen.player():hand(), nil when you are carrying nothing — and hafen.player():hand()
        // :use(target, mods) applies what you hold to an Item, a Position or a Gob. The Gob arm is a message
        // this verb could not send: it aimed only at bare ground, where the client's own iteminteract extends
        // the args with the object's click args when the hit resolves to one.
        // 048.4: place and select have LEFT — they are the world's first protected verbs now
        // (hafen.world():place(p, angle, button, mods) and hafen.world():select(p1, p2, mods)), which puts
        // place directly beside the hafen.world():snapPlace(p) / :snapAngle(a) that exist to prepare its two
        // arguments and until now sat a whole section away from it. Same messages, same gate, on the thing
        // they change; their pure arg builders went with them.
        // raw(target, msg, ...) — the escape hatch: send an arbitrary wdgmsg from a BOUND widget. target = a
        // server widget id (number; e.g. widget:id()) or a token "mapview"/"gameui". The trailing args are
        // marshalled exactly like the action/message hooks (a {x=,y=} table ↔ Coord; numbers/strings/bools
        // direct). For power users — the typed verbs above cover the common cases; raw covers the rest.
        act.set("raw", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "act", "raw");
                AddonManager.requireActions(owner, "hafen.act():raw");
                actRaw(a.subargs(2));
                return LuaValue.NIL;
            }
        });
        // 048.5: menu(path...) is GONE, and nothing took its place. 023-menugrid-oop already absorbed the
        // mechanism — hafen.menugrid() addresses the entries it holds, and hafen.menugrid():get("Dig"):use()
        // (or get("paginae/act/dig"):use() by resource name) is the door — so this was the old one D-103
        // requires closing. The pagina-PATH address space goes with it by maintainer directive: a second,
        // path-shaped way to say the same thing is exactly the dual style D-013 refuses, and paths were never
        // a stable address space anyway (server-fetched, content-defined, resolvable only while loaded).
        // pag:use() gained the "actions" gate in the same task, so nothing that acted behind a permission
        // stopped doing so.
        // flower(label) — select a petal of the OPEN radial context menu (FlowerMenu) by its label (the petal
        // name, matched case-insensitively), driving the client's own FlowerMenu.choose (wrap-not-reimplement,
        // D-009: reuses the client's selection, including its client-side petals). Returns true if a matching
        // petal was chosen, false if no flower menu is open or no petal matched (never throws for those — an
        // addon can just test the result). The classic use is automation: an addon right-clicks a target
        // (gob:click(3)) and then auto-picks a petal — while a flower menu is open it grabs the mouse +
        // keyboard, so a programmatic pick (from a timer / event) is the only way to select without a click.
        act.set("flower", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "act", "flower");
                AddonManager.requireActions(owner, "hafen.act():flower");
                LuaValue label = Args.required(a, 2, "hafen.act():flower", "label");
                if(!label.isstring())
                    throw new LuaError("hafen.act():flower(label): label must be a string (a petal name)");
                return LuaValue.valueOf(actFlower(label.tojstring()));
            }
        });
        // 048.3: item(item, verb [, n]) has LEFT, and with it the last of the five verb strings. What you can do
        // TO an item is on the item now — item:use(mods) (was "iact"), item:take(), item:drop(n),
        // item:transfer(n) — and applying what is on your cursor onto one is hafen.player():hand():use(item).
        // A fifth argument was never a vocabulary: it was a switch statement standing where four verb names go.
        Section.install(hafen, "act", act);
    }

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
     * is unprotected and the write half keeps the {@code actions} permission it always had.
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
                AddonManager.requireActions(owner, "hafen.speed():current");
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

    // ---- actions tier (Phase 4: hafen.act) -------------------------------------------------------
    // The PROTECTED automation surface (gate: requireActions / the declared per-addon permission, above). Every
    // verb is a Widget.wdgmsg from a bound widget — literally what a player click would send, so the client stays
    // server-authoritative (an addon can do only what a player could do; the permission is about user control,
    // not a client exploit — spec 12). Runs on the UI thread (addon callback / REPL); wdgmsg queues to the
    // session, and a 2d action-hook sees the send (it is a real action) — the 2d re-entrancy guard prevents a
    // hook-issued verb from looping.
    //
    // 048.1: the two CLICK verbs are gone from here. Walking the character is hafen.player():move(p) (CharApi)
    // and clicking an object is gob:click(button, mods) (LuaGob) — each sends the same message it always did,
    // from the thing it changes.
    // 048.2: the MapView "itemact" left with useItemOn — its three wire shapes are LuaHand's pure builders now.
    // 048.4: place and select left for hafen.world() (WorldApi), and took placeArgs/placeAngle/selArgs with
    // them — still pure, still headless-testable, just beside the verbs that send them. moveClickCoord went
    // with them too: it had one caller left, and what it spelled (Coord2d.floor(OCache.posres)) is what both
    // LuaHand and WorldApi now write inline at the one line that needs it.
    //
    // -- 4d: what is left of the MapView action verbs — raw ---------------------------------------------------
    // The escape hatch outlives the typed verbs above it because it is not about the map at all: it sends an
    // arbitrary wdgmsg from any BOUND widget, and 048.6 moves it onto the widget itself.

    /**
     * {@code hafen.act():raw} backing — send an arbitrary wdgmsg from a bound widget. {@code a.arg1()} = the
     * target (a numeric server widget id, or a "mapview"/"gameui"/"root" token); {@code a.arg(2)} = the message
     * name; the rest are the message args (marshalled via {@link LuaMarshal#toJava}). "Bound widgets only" — a
     * looked-up widget id and the core-widget tokens are all server-bound.
     */
    private static void actRaw(Varargs a) {
        LuaValue msgv = a.arg(2);
        if(!msgv.isstring())
            throw new LuaError("hafen.act():raw(target, msg, ...): msg must be a string");
        Widget w = rawTarget(a.arg1());
        if(w == null)
            throw new LuaError("hafen.act():raw: target did not resolve to a live widget"
                + " (expected a bound widget id, \"mapview\", or \"gameui\")");
        int n = a.narg();
        Object[] args = new Object[Math.max(0, n - 2)];
        for(int i = 3; i <= n; i++)
            args[i - 3] = LuaMarshal.toJava(a.arg(i), "hafen.act():raw");
        w.wdgmsg(msgv.tojstring(), args);
    }

    /** Resolve a {@code raw} target: a numeric server widget id ({@code UI.getwidget}), or a hook-style token. */
    private static Widget rawTarget(LuaValue target) {
        if(target.isnumber()) {
            UI u = AddonManager.ui;
            return (u == null) ? null : u.getwidget(target.toint());
        }
        if(target.isstring()) {
            String tok = target.tojstring().toLowerCase();
            if(HookApi.isKnownTarget(tok))
                return HookApi.hookTarget(tok);  // "mapview"/"gameui"/"root" → the live bound widget (reuse 2c)
        }
        return null;
    }

    // -- 4e: the flower verb ---------------------------------------------------------------------------
    // flower goes through the OPEN FlowerMenu's own choose (wrap-not-reimplement, D-009 — reuses the client's
    // petal selection, including its client-side petals). It runs on the UI thread (addon callback / REPL /
    // timer), like the MapView verbs did, and locates its target by walking the live widget tree
    // (FlowerMenuApi.open(), the finder hafen.flowermenu() owns since 047.1). It is non-throwing on "no menu /
    // no match" (returns false); reading a menu's petals, the two menu events and the 047.2 write half
    // (hafen.flowermenu():select(label|n) / :cancel(), which REFUSE naming what is open) live in that section.
    // The two doors coexist by maintainer directive until 048.7 closes this one.
    // 048.5: the menu path builder went with act():menu — GameUI.act is the client's own by-path door and no
    // hafen.* verb opens it any more.

    /** Index of the first petal name equal to {@code label} (case-insensitive), or {@code -1}. Pure/testable. */
    static int flowerPetalIndex(String[] names, String label) {
        if(names == null)
            return -1;
        for(int i = 0; i < names.length; i++) {
            if((names[i] != null) && names[i].equalsIgnoreCase(label))
                return i;
        }
        return -1;
    }

    /**
     * {@code hafen.act():flower} backing — select the open flower menu's petal whose name equals {@code label}
     * (case-insensitive), via the client's own {@link FlowerMenu#choose}. Returns whether a petal matched.
     */
    private static boolean actFlower(String label) {
        FlowerMenu fm = FlowerMenuApi.open();     // 047.1: the finder lives with the section now (D-103)
        if(fm == null)
            return false;                        // no menu open
        FlowerMenu.Petal[] opts = fm.opts;
        if(opts == null)
            return false;
        int idx = flowerPetalIndex(FlowerMenuApi.names(fm), label);
        if(idx < 0)
            return false;                        // no petal matched
        fm.choose(opts[idx]);                    // wrap-not-reimplement: the client's own petal selection
        return true;
    }

    // 048.3: the item verbs are GONE from here. They were the one half of this section that did not even act on
    // world coords — each acted on a specific item, addressed by the Item ENTITY — so they are four verbs on
    // LuaItem now (item:use / :take / :drop / :transfer), sending the same GItem.wdgmsgs from the thing they
    // change. Their pure arg builders went with them.

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
