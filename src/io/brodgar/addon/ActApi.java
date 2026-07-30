package io.brodgar.addon;

import haven.Coord;
import haven.Coord2d;
import haven.FlowerMenu;
import haven.GameUI;
import haven.GItem;
import haven.Gob;
import haven.Indir;
import haven.Loading;
import haven.Makewindow;
import haven.MapView;
import haven.MCache;
import haven.MiniMap;
import haven.OCache;
import haven.Resource;
import haven.Speedget;
import haven.UI;
import haven.WItem;
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
 * The gated automation subsystem (Phase 4: {@code hafen.act}) + crafting read/make ({@code hafen.craft}) +
 * movement speed ({@code hafen.speed}). Every act verb is a {@link haven.Widget#wdgmsg} from a bound widget
 * (literally what a player click sends, so the client stays server-authoritative), gated by
 * {@code requireActions} (the declared per-addon permission). No lifecycle/tick/teardown state — these are
 * invoked only from Lua callbacks. Not instantiable.
 */
final class ActApi {
    private ActApi() {}

    /** Build {@code hafen.act} (the gated MapView/menu/flower/item verbs) for {@code owner}. From installHafen. */
    static void installAct(LuaTable hafen, final Addon owner) {
        LuaTable act = new LuaTable();
        act.set("enabled", new ZeroArgFunction() {
            public LuaValue call() {
                return LuaValue.valueOf(AddonManager.actionsGranted(owner));
            }
        });
        act.set("moveTo", new TwoArgFunction() {
            public LuaValue call(LuaValue x, LuaValue y) {
                AddonManager.requireActions(owner, "hafen.act.moveTo");
                if(!x.isnumber() || !y.isnumber())
                    throw new LuaError("hafen.act.moveTo(x, y): x and y must be numbers (world coordinates)");
                actMoveTo(x.todouble(), y.todouble());
                return LuaValue.NIL;
            }
        });
        // clickGob(gob [, button [, mods]]) — click a game object: exactly the MapView "click" that a
        // left/right-click on that gob sends. gob = a Gob OBJECT from the read API (hafen.gob(id),
        // hafen.world.nearest(...), hafen.player():gob()); raw ids and the old GobRef tokens are NOT accepted
        // (D-044 — one canonical way). button: 1 = left (default; select/interact), 3 = right (the context/
        // flower-menu click). mods = a modifier bitfield (0 default; Shift=1 Ctrl=2 Alt=4, matching hafen.key).
        // Sends the bare gob-click encoding {…, 0, gobid, gobrc, 0, -1} — a generic "click the whole object",
        // faithful for world objects (trees/containers/…); a specific sub-mesh / composite body part is not
        // targeted (deferred). Throws if the gob is out of view or the map view is gone.
        act.set("clickGob", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                AddonManager.requireActions(owner, "hafen.act.clickGob");
                actClickGob(a.arg1(), a.arg(2).optint(1), a.arg(3).optint(0));
                return LuaValue.NIL;
            }
        });
        // useItemOn(x, y [, mods]) — use the item on your cursor on the GROUND at world (x, y): the MapView
        // "itemact". With nothing on the cursor the server ignores it. mods optional (0 default).
        act.set("useItemOn", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                AddonManager.requireActions(owner, "hafen.act.useItemOn");
                if(!a.arg1().isnumber() || !a.arg(2).isnumber())
                    throw new LuaError("hafen.act.useItemOn(x, y): x and y must be numbers (world coordinates)");
                actUseItemOn(a.arg1().todouble(), a.arg(2).todouble(), a.arg(3).optint(0));
                return LuaValue.NIL;
            }
        });
        // place(x, y, angle [, button [, mods]]) — place the object currently on your cursor at world (x, y),
        // rotated by `angle` RADIANS (the MapView "place"; the engine encodes angle as round(angle*32768/PI)).
        // With nothing being placed the server ignores it. button 1 = confirm (default); mods 0 default.
        act.set("place", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                AddonManager.requireActions(owner, "hafen.act.place");
                if(!a.arg1().isnumber() || !a.arg(2).isnumber() || !a.arg(3).isnumber())
                    throw new LuaError("hafen.act.place(x, y, angle): x, y and angle must be numbers");
                actPlace(a.arg1().todouble(), a.arg(2).todouble(), a.arg(3).todouble(),
                         a.arg(4).optint(1), a.arg(5).optint(0));
                return LuaValue.NIL;
            }
        });
        // select(x1, y1, x2, y2 [, mods]) — area-select the tile rectangle spanned by world corners
        // (x1,y1)–(x2,y2): the MapView "sel" (world → tile via hafen.map.worldToTile). Drives tile-area tools.
        act.set("select", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                AddonManager.requireActions(owner, "hafen.act.select");
                if(!a.arg1().isnumber() || !a.arg(2).isnumber() || !a.arg(3).isnumber() || !a.arg(4).isnumber())
                    throw new LuaError("hafen.act.select(x1, y1, x2, y2): all four must be numbers (world coordinates)");
                actSelect(a.arg1().todouble(), a.arg(2).todouble(), a.arg(3).todouble(), a.arg(4).todouble(),
                          a.arg(5).optint(0));
                return LuaValue.NIL;
            }
        });
        // raw(target, msg, ...) — the escape hatch: send an arbitrary wdgmsg from a BOUND widget. target = a
        // server widget id (number; e.g. model:raw() from hafen.ui.adopt, or a 3a desc.id) or a token
        // "mapview"/"gameui". The trailing args are marshalled exactly like the action/message hooks
        // (a {x=,y=} table ↔ Coord; numbers/strings/bools direct). For power users — the typed verbs above
        // cover the common cases; raw covers messages they don't.
        act.set("raw", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                AddonManager.requireActions(owner, "hafen.act.raw");
                actRaw(a);
                return LuaValue.NIL;
            }
        });
        // menu(path...) — invoke a menu/pagina action by its path tokens, via GameUI.act (the "act" wdgmsg
        // the action-bar menu grid sends when you click through a pagina tree; the client itself uses it,
        // e.g. act("lo","cs") = log out to character select). CAVEAT (coverage-gaps C3): paginae are
        // server-fetched and their names are content-defined / localized / versioned — this is NOT a stable
        // address space, and a path resolves only if that page is currently loaded. Some paths COMMIT real
        // actions (e.g. "lo" logs out), so the addon supplies the tokens deliberately.
        act.set("menu", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                AddonManager.requireActions(owner, "hafen.act.menu");
                actMenu(a);
                return LuaValue.NIL;
            }
        });
        // flower(label) — select a petal of the OPEN radial context menu (FlowerMenu) by its label (the petal
        // name, matched case-insensitively), driving the client's own FlowerMenu.choose (wrap-not-reimplement,
        // D-009: reuses the client's selection, including its client-side petals). Returns true if a matching
        // petal was chosen, false if no flower menu is open or no petal matched (never throws for those — an
        // addon can just test the result). The classic use is automation: an addon right-clicks a target
        // (clickGob button 3) and then auto-picks a petal — while a flower menu is open it grabs the mouse +
        // keyboard, so a programmatic pick (from a timer / event) is the only way to select without a click.
        act.set("flower", new OneArgFunction() {
            public LuaValue call(LuaValue label) {
                AddonManager.requireActions(owner, "hafen.act.flower");
                if(!label.isstring())
                    throw new LuaError("hafen.act.flower(label): label must be a string (a petal name)");
                return LuaValue.valueOf(actFlower(label.tojstring()));
            }
        });
        // item(item, verb [, n]) — the gated item verbs. `item` = an item you got from a READ: a snapshot from
        // hafen.items.* (inventory/equipment/hand/find) or model:items(), OR its numeric `handle` field directly.
        // The handle (the item's server widget id) re-resolves the LIVE GItem each call (a stale/used/moved item →
        // a guiding error, like a GobRef that no longer resolves), then sends exactly the GItem.wdgmsg a click on
        // the item sends (WItem.mousedown / iteminteract) — so the client stays server-authoritative. `verb`:
        //   "take"     pick it up onto your cursor/hand (from a container, or unequip a worn item).
        //   "drop"     drop it on the ground; `n` = how many of a stack (default -1 = the whole stack/item).
        //   "transfer" move it to the linked container (an open container / your inventory); `n` as for drop.
        //   "iact"     right-click / activate it (its default context action: eat, open, light, …).
        //   "itemact"  apply the item on your cursor ONTO this item (e.g. pour a waterskin onto a plant).
        // `n` is ignored for take/iact/itemact (no count). iact/itemact send no modifiers; for a MODIFIED item
        // interaction use the escape hatch: hafen.act.raw(item.handle, "iact", {x=0,y=0}, mods).
        act.set("item", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                AddonManager.requireActions(owner, "hafen.act.item");
                LuaValue verb = a.arg(2);
                if(!verb.isstring())
                    throw new LuaError("hafen.act.item(item, verb): verb must be a string"
                        + " (\"take\", \"drop\", \"transfer\", \"iact\" or \"itemact\")");
                actItem(a.arg1(), verb.tojstring(), a.arg(3).optint(-1));
                return LuaValue.NIL;
            }
        });
        hafen.set("act", act);
    }

    /** Build {@code hafen.craft} (crafting read + gated make) for {@code owner}. From installHafen. */
    static void installCraft(LuaTable hafen, final Addon owner) {
        LuaTable craft = new LuaTable();
        craft.set("current", new ZeroArgFunction() {
            public LuaValue call() {
                return readCraft();
            }
        });
        // make([all]) — the gated write verb (4g): craft the OPEN recipe (all → Craft All). requireActions-gated
        // (D-027/D-028). all is a boolean (Lua truthiness: nil/false → one, anything else → all).
        craft.set("make", new OneArgFunction() {
            public LuaValue call(LuaValue all) {
                AddonManager.requireActions(owner, "hafen.craft.make");
                actCraftMake(all.toboolean());
                return LuaValue.NIL;
            }
        });
        hafen.set("craft", craft);
    }

    /** Build {@code hafen.speed} (movement-speed read + gated set, A7) for {@code owner}. From installHafen. */
    static void installSpeed(LuaTable hafen, final Addon owner) {
        LuaTable speed = new LuaTable();
        speed.set("get", new ZeroArgFunction() {
            public LuaValue call() {
                Speedget s = speedget();
                return (s == null) ? LuaValue.NIL : LuaValue.valueOf(s.cur);
            }
        });
        speed.set("max", new ZeroArgFunction() {
            public LuaValue call() {
                Speedget s = speedget();
                return (s == null) ? LuaValue.NIL : LuaValue.valueOf(s.max);
            }
        });
        speed.set("name", new OneArgFunction() {
            public LuaValue call(LuaValue n) {
                int idx;
                if(n.isnumber()) {
                    idx = n.toint();
                } else {                       // no/absent arg → the current speed
                    Speedget s = speedget();
                    if(s == null)
                        return LuaValue.NIL;
                    idx = s.cur;
                }
                return speedName(idx);
            }
        });
        // set(n) — the gated write verb (4g): select movement speed n (0..3). requireActions-gated like every
        // hafen.act.* verb (D-027/D-028): only an addon that declared "actions" may call it.
        speed.set("set", new OneArgFunction() {
            public LuaValue call(LuaValue n) {
                AddonManager.requireActions(owner, "hafen.speed.set");
                if(!n.isnumber())
                    throw new LuaError("hafen.speed.set(n): n must be a number (0=crawl 1=walk 2=run 3=sprint)");
                actSpeedSet(n.toint());
                return LuaValue.NIL;
            }
        });
        hafen.set("speed", speed);
    }

    // ---- actions tier (Phase 4: hafen.act) -------------------------------------------------------
    // The GATED automation surface (gate: requireActions / the declared per-addon permission, above). Every verb is a
    // Widget.wdgmsg from a bound widget — literally what a player click would send, so the client stays
    // server-authoritative (an addon can do only what a player could do; the permission is about user control,
    // not a client exploit — spec 12). moveTo sends the MapView "click" that a left-click on the ground sends:
    // {pc (screen coord), mc (world coord floored to posres), button, mods}. For a PROGRAMMATIC move the
    // destination is the world coord (2nd arg); the screen coord (pc) is a dummy — the current mouse position
    // — exactly as MiniMap.mvclick does when you click the minimap to walk (MiniMap.java:1218), so an
    // off-screen destination is fine. button 1 = walk; mods 0 = no modifier. Runs on the UI thread (addon
    // callback / REPL); wdgmsg queues to the session, and any 2d "click" action-hook sees it (it is a real
    // action) — the 2d re-entrancy guard prevents a hook-issued moveTo from looping.

    /** The world "click" destination Coord for a move to world (x, y) — MapView floors world coords to posres. */
    static Coord moveClickCoord(double x, double y) {
        return new Coord2d(x, y).floor(OCache.posres);
    }

    /** {@code hafen.act.moveTo} backing — send the ground-"click" that walks the character to world (x, y). */
    private static void actMoveTo(double x, double y) {
        MapView m = AddonManager.view;
        if(m == null)
            throw new LuaError("hafen.act.moveTo: no map view (not in the world yet)");
        Coord pc = (m.ui != null) ? m.ui.mc : Coord.z;   // dummy screen coord (current mouse), like MiniMap.mvclick
        m.wdgmsg("click", pc, moveClickCoord(x, y), 1, 0);
    }

    // -- 4d: the rest of the MapView action verbs (clickGob / useItemOn / place / select) + raw --------------
    // Each is the SAME kind of send as moveTo — a Widget.wdgmsg from the MapView, exactly what the matching
    // mouse gesture produces (MapView.Click.hit / iteminteract / mousedown-place / Selector.mmouseup). They
    // reuse moveTo's world→Coord encoding (moveClickCoord = Coord2d.floor(posres)) and its dummy screen coord
    // (pc = the current mouse, meaningless for a programmatic action but part of the wire shape). The arg-array
    // BUILDERS below are pure (no live state) so they are headless-testable; the act* SENDERS grab the live
    // MapView, fill pc, and wdgmsg. All run on the UI thread (addon callback / REPL); wdgmsg queues to the
    // session, and a 2d "click" action-hook sees a clickGob (it is a real "click") — the 2d re-entrancy guard
    // stops a hook-issued verb from looping.

    /**
     * The full MapView {@code "click"} args for a generic click on the gob {@code (gobId, gobRc)} — the
     * {@code {pc, mc, button, mods}} prefix extended with {@link haven.Gob.GobClick#clickargs}'
     * {@code {0, gobid, gobrc, 0, -1}} (no overlay, no specific sub-mesh). {@code mc} = the gob's own floored
     * position, as a click landing on its base would carry. Pure/testable.
     */
    static Object[] clickGobArgs(Coord pc, int button, int mods, int gobId, Coord gobRc) {
        return new Object[] {pc, gobRc, button, mods, 0, gobId, gobRc, 0, -1};
    }

    /** {@code hafen.act.clickGob} backing — click the gob a read-API {@link LuaGob} object names (D-044). */
    private static void actClickGob(LuaValue ref, int button, int mods) {
        MapView m = AddonManager.view;
        if(m == null)
            throw new LuaError("hafen.act.clickGob: no map view (not in the world yet)");
        LuaGob h = LuaGob.resolve(ref);
        if(h == null)
            throw new LuaError("hafen.act.clickGob(gob [, button, mods]): expected a Gob object (hafen.gob(id) / hafen.world.nearest(...)) — raw ids and the old GobRef tokens are gone");
        Gob g = AddonManager.getgob(h.id);
        if(g == null)
            throw new LuaError("hafen.act.clickGob: no such gob (that Gob is not in view — check gob:exists())");
        Coord2d rc;
        synchronized(g) { rc = g.rc; }                   // OCache discipline: copy under the gob lock
        if(rc == null)
            throw new LuaError("hafen.act.clickGob: the gob has no position yet");
        Coord pc = (m.ui != null) ? m.ui.mc : Coord.z;
        m.wdgmsg("click", clickGobArgs(pc, button, mods, (int)g.id, rc.floor(OCache.posres)));
    }

    /** The MapView {@code "itemact"} args (use held item on the ground at world x,y). Pure/testable. */
    static Object[] itemactArgs(Coord pc, double x, double y, int mods) {
        return new Object[] {pc, moveClickCoord(x, y), mods};
    }

    /** {@code hafen.act.useItemOn} backing — apply the cursor item to the ground at world (x, y). */
    private static void actUseItemOn(double x, double y, int mods) {
        MapView m = AddonManager.view;
        if(m == null)
            throw new LuaError("hafen.act.useItemOn: no map view (not in the world yet)");
        Coord pc = (m.ui != null) ? m.ui.mc : Coord.z;
        m.wdgmsg("itemact", itemactArgs(pc, x, y, mods));
    }

    /** The MapView {@code "place"} angle encoding: radians → the server's {@code round(angle*32768/PI)}. Pure. */
    static int placeAngle(double radians) {
        return (int)Math.round(radians * 32768 / Math.PI);
    }

    /** The MapView {@code "place"} args ({@code {rc, angleInt, button, mods}}). Pure/testable. */
    static Object[] placeArgs(double x, double y, double angle, int button, int mods) {
        return new Object[] {moveClickCoord(x, y), placeAngle(angle), button, mods};
    }

    /** {@code hafen.act.place} backing — place the cursor object at world (x, y) rotated by {@code angle} rad. */
    private static void actPlace(double x, double y, double angle, int button, int mods) {
        MapView m = AddonManager.view;
        if(m == null)
            throw new LuaError("hafen.act.place: no map view (not in the world yet)");
        m.wdgmsg("place", placeArgs(x, y, angle, button, mods));
    }

    /**
     * The MapView {@code "sel"} args ({@code {tc1, tc2, mods}}) — world corners floored to TILE coords, the
     * same conversion {@code hafen.map.worldToTile} exposes ({@code Coord2d.floor(MCache.tilesz)}). Pure/testable.
     */
    static Object[] selArgs(double x1, double y1, double x2, double y2, int mods) {
        Coord tc1 = Coord2d.of(x1, y1).floor(MCache.tilesz);
        Coord tc2 = Coord2d.of(x2, y2).floor(MCache.tilesz);
        return new Object[] {tc1, tc2, mods};
    }

    /** {@code hafen.act.select} backing — area-select the tile rectangle between world corners. */
    private static void actSelect(double x1, double y1, double x2, double y2, int mods) {
        MapView m = AddonManager.view;
        if(m == null)
            throw new LuaError("hafen.act.select: no map view (not in the world yet)");
        m.wdgmsg("sel", selArgs(x1, y1, x2, y2, mods));
    }

    /**
     * {@code hafen.act.raw} backing — send an arbitrary wdgmsg from a bound widget. {@code a.arg1()} = the
     * target (a numeric server widget id, or a "mapview"/"gameui"/"root" token); {@code a.arg(2)} = the message
     * name; the rest are the message args (marshalled via {@link LuaMarshal#toJava}). "Bound widgets only" — a
     * looked-up widget id and the core-widget tokens are all server-bound.
     */
    private static void actRaw(Varargs a) {
        LuaValue msgv = a.arg(2);
        if(!msgv.isstring())
            throw new LuaError("hafen.act.raw(target, msg, ...): msg must be a string");
        Widget w = rawTarget(a.arg1());
        if(w == null)
            throw new LuaError("hafen.act.raw: target did not resolve to a live widget"
                + " (expected a bound widget id, \"mapview\", or \"gameui\")");
        int n = a.narg();
        Object[] args = new Object[Math.max(0, n - 2)];
        for(int i = 3; i <= n; i++)
            args[i - 3] = LuaMarshal.toJava(a.arg(i), "hafen.act.raw");
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

    // -- 4e: menu + flower verbs -----------------------------------------------------------------------
    // menu goes through GameUI.act (the "act" wdgmsg by path — what the action-bar menu grid sends); flower
    // through the OPEN FlowerMenu's own choose (wrap-not-reimplement, D-009 — reuses the client's petal
    // selection, including its client-side petals). Both run on the UI thread (addon callback / REPL / timer),
    // like the MapView verbs above, and locate their target by walking the live widget tree (AddonManager.gui() / the
    // recursive children(FlowerMenu.class)). flower is non-throwing on "no menu / no match" (returns false).

    /** Build the menu path {@code String[]} from the 1-based varargs; throws on an empty path or a non-string
     *  token (numbers coerce to their string form, like a console token). Pure/testable. */
    static String[] menuPath(Varargs a) {
        int n = a.narg();
        if(n < 1)
            throw new LuaError("hafen.act.menu(path...): at least one path token is required");
        String[] path = new String[n];
        for(int i = 1; i <= n; i++) {
            LuaValue v = a.arg(i);
            if(!v.isstring())
                throw new LuaError("hafen.act.menu(path...): every path token must be a string");
            path[i - 1] = v.tojstring();
        }
        return path;
    }

    /** {@code hafen.act.menu} backing — send the "act" menu-path message via {@link GameUI#act(String...)}. */
    private static void actMenu(Varargs a) {
        GameUI g = AddonManager.gui();
        if(g == null)
            throw new LuaError("hafen.act.menu: no game UI (not in the world yet)");
        g.act(menuPath(a));
    }

    /** The single OPEN radial context menu ({@link FlowerMenu}), or {@code null} if none is up. */
    private static FlowerMenu openFlower() {
        UI u = AddonManager.ui;
        if((u == null) || (u.root == null))
            return null;
        for(FlowerMenu fm : u.root.children(FlowerMenu.class))   // recursive walk; only one is ever open (it grabs input)
            return fm;
        return null;
    }

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
     * {@code hafen.act.flower} backing — select the open flower menu's petal whose name equals {@code label}
     * (case-insensitive), via the client's own {@link FlowerMenu#choose}. Returns whether a petal matched.
     */
    private static boolean actFlower(String label) {
        FlowerMenu fm = openFlower();
        if(fm == null)
            return false;                        // no menu open
        FlowerMenu.Petal[] opts = fm.opts;
        if(opts == null)
            return false;
        String[] names = new String[opts.length];
        for(int i = 0; i < opts.length; i++)
            names[i] = (opts[i] == null) ? null : opts[i].name;
        int idx = flowerPetalIndex(names, label);
        if(idx < 0)
            return false;                        // no petal matched
        fm.choose(opts[idx]);                    // wrap-not-reimplement: the client's own petal selection
        return true;
    }

    // -- 4f: item verbs (hafen.act.item) ---------------------------------------------------------------
    // The item half of the gated tier. Unlike the MapView verbs (which act on world coords) an item verb acts
    // on a specific item, addressed by a HANDLE = the item's server widget id (GItem.wdgid(), carried on every
    // item snapshot as `handle` — D-022: handle-only). We re-resolve the live GItem from that id each call
    // (ui.getwidget(id); a stale/used/moved item no longer maps to a GItem → a guiding error, exactly like a
    // GobRef that no longer resolves) and send the SAME GItem.wdgmsg the corresponding click sends
    // (WItem.mousedown: take/drop/transfer/iact; WItem.iteminteract: itemact) — the client stays server-
    // authoritative. The coord these messages carry is the intra-item grab point; Coord.z (the item's corner)
    // is a faithful, deterministic substitute for a programmatic action. The arg BUILDER is pure/testable; the
    // sender resolves the live GItem and wdgmsgs. Runs on the UI thread (addon callback / REPL / timer), like
    // every act verb; a 2d "take"/… action-hook can still see it (it is a real wdgmsg).

    /**
     * The {@link GItem} {@code wdgmsg} args for an item {@code verb}, or {@code null} for an unknown verb.
     * {@code n} is the stack count for {@code drop}/{@code transfer} ({@code -1} = the whole stack). The others
     * carry no count: {@code take} is a bare grab; {@code iact}/{@code itemact} send modifiers {@code 0} (a
     * modified interaction goes through {@code hafen.act.raw}). Pure/testable — the grab coord is a fixed corner.
     */
    static Object[] itemVerbArgs(String verb, int n) {
        switch(verb) {
            case "take":     return new Object[] {Coord.z};
            case "drop":     return new Object[] {Coord.z, n};
            case "transfer": return new Object[] {Coord.z, n};
            case "iact":     return new Object[] {Coord.z, 0};   // mods 0 — plain right-click / activate
            case "itemact":  return new Object[] {0};            // mods 0 — apply the held item onto this one
            default:         return null;
        }
    }

    /**
     * Resolve an {@code ItemRef} to the live {@link GItem}: {@code item} is either a numeric handle (the item's
     * server widget id) or an item snapshot table carrying a numeric {@code handle} field. Throws a guiding
     * {@link LuaError} when it is neither (a programmer error), and returns {@code null} when the handle no
     * longer maps to a live {@link GItem} (a stale/used item — the caller turns that into an action error).
     */
    private static GItem resolveItemHandle(LuaValue item) {
        int id;
        if(item.isnumber()) {
            id = item.toint();
        } else if(item.istable()) {
            LuaValue h = item.get("handle");
            if(!h.isnumber())
                throw new LuaError("hafen.act.item: the item table has no numeric 'handle' field"
                    + " (pass an item from hafen.items.* / model:items(), or its .handle)");
            id = h.toint();
        } else {
            throw new LuaError("hafen.act.item(item, verb): item must be an item snapshot (a table) or a"
                + " handle id (a number)");
        }
        UI u = AddonManager.ui;
        if(u == null)
            return null;
        Widget w = u.getwidget(id);
        return (w instanceof GItem) ? (GItem)w : null;
    }

    /** {@code hafen.act.item} backing — resolve the live {@link GItem} by its handle and send the verb's wdgmsg. */
    private static void actItem(LuaValue item, String verb, int n) {
        Object[] args = itemVerbArgs(verb, n);
        if(args == null)
            throw new LuaError("hafen.act.item(item, verb): verb must be one of \"take\", \"drop\","
                + " \"transfer\", \"iact\", \"itemact\" (got \"" + verb + "\")");
        GItem g = resolveItemHandle(item);
        if(g == null)
            throw new LuaError("hafen.act.item: the item did not resolve to a live item — its handle is stale"
                + " (it was moved/used/consumed, or you are not in the world). Re-read hafen.items.* and retry.");
        g.wdgmsg(verb, args);
    }

    // ---- movement speed (A7: hafen.speed) --------------------------------------------------------
    // The speed selector is a Speedget widget (crawl/walk/run/sprint) the server places under the HUD.
    // It has no named GameUI field, so we locate it with the 1d-1 Locator (a children(Class) subtree
    // walk from the HUD) — the same way vitals finds its IMeters. Both fields we read (cur = current
    // speed, max = highest currently-selectable speed) are public ints, so this is a zero-haven-edit
    // read. All calls run on the UI thread (addon tick / REPL). Changing speed is the gated Phase-4 tier.

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
     * {@code hafen.speed.set} backing (4g, gated) — select movement speed {@code n} (0..3) via the client's own
     * {@link Speedget#set} (wrap-not-reimplement, D-009 → {@code wdgmsg("set", n)}). The server is authoritative
     * on whether a speed is currently allowed (e.g. sprint may be locked); this only sends the request, exactly
     * as clicking/hotkeying that speed would. Throws for out-of-range {@code n} or before the selector exists.
     */
    private static void actSpeedSet(int n) {
        if((n < 0) || (n > 3))
            throw new LuaError("hafen.speed.set(n): n must be 0..3 (0=crawl 1=walk 2=run 3=sprint), got " + n);
        Speedget s = speedget();
        if(s == null)
            throw new LuaError("hafen.speed.set: no speed selector (not in the world yet)");
        s.set(n);
    }

    // ---- crafting (A8: hafen.craft) --------------------------------------------------------------
    // The crafting/recipe window is a Makewindow (@RName("make")) the server places under the HUD when
    // the player opens a recipe. It is wrapped in GameUI.makewnd (a private Window), so — like A7's speed
    // selector — we locate the content widget with the 1d-1 Locator (a children(Class) subtree walk from
    // the HUD), not a named GameUI field. A recipe carries: rcpnm (the recipe name), inputs (ingredient
    // slots), outputs (product slots), qmod (quality-affecting input resources) and tools (required tool
    // resources). Read-only here — craft.make is the gated Phase-4 action tier.
    //
    // Threading: inputs/outputs/qmod are List references the "inpop"/"opop"/"qmod" uimsgs swap WHOLESALE
    // off the UI thread (on a Loader thread, under synchronized(ui)); tools is mutated IN PLACE ("tool"
    // uimsg → tools.add). So we copy all four lists under the ui monitor (the marker "copy under the lock,
    // snapshot outside it" discipline), then resolve resource names outside the lock (res.get() may Loading).
    // All backings are public (Makewindow.rcpnm/inputs/outputs/qmod/tools, SpecWidget.spec, Spec.item/
    // constraint/num/opt(), ResData.res) → zero haven edit, like A7/A6/A4/A2.

    /** The (unique) crafting window content under the HUD, or {@code null} if no recipe is open. */
    private static Makewindow makewindow() {
        GameUI g = AddonManager.gui();
        if(g == null)
            return null;
        for(Makewindow m : g.children(Makewindow.class))   // recursive subtree walk; take the first
            return m;
        return null;
    }

    /**
     * {@code hafen.craft.make} backing (4g, gated) — press the open recipe's Craft button ({@code all=false} →
     * {@code wdgmsg("make", 0)}, one item) or Craft All ({@code all=true} → {@code wdgmsg("make", 1)}), exactly
     * what the two buttons send ({@link Makewindow} :147/:148). CONSUMES the ingredients like a manual craft.
     * Throws when no crafting window is open.
     */
    private static void actCraftMake(boolean all) {
        Makewindow mw = makewindow();
        if(mw == null)
            throw new LuaError("hafen.craft.make: no crafting window open (open a recipe first)");
        mw.wdgmsg("make", all ? 1 : 0);
    }

    /** {@code hafen.craft.current()} — a snapshot of the open recipe, or {@code nil}. */
    private static LuaValue readCraft() {
        Makewindow mw = makewindow();
        UI u = AddonManager.ui;
        if((mw == null) || (u == null))                    // mw is found via AddonManager.gui() (needs ui) → u!=null here
            return LuaValue.NIL;
        String recipe;
        List<Makewindow.Input> inputs;
        List<Makewindow.SpecWidget> outputs;
        List<Indir<Resource>> qmod, tools;
        synchronized(u) {                                  // copy the off-thread-mutated lists under the lock
            recipe = mw.rcpnm;
            inputs = new ArrayList<Makewindow.Input>(mw.inputs);
            outputs = new ArrayList<Makewindow.SpecWidget>(mw.outputs);
            qmod = new ArrayList<Indir<Resource>>(mw.qmod);
            tools = new ArrayList<Indir<Resource>>(mw.tools);
        }
        LuaTable t = new LuaTable();                       // ...then snapshot outside it (names may Loading)
        t.set("recipe", LuaValue.valueOf(recipe == null ? "" : recipe));
        t.set("inputs", craftSpecs(inputs));
        t.set("outputs", craftSpecs(outputs));
        t.set("qmod", craftReses(qmod));
        t.set("tools", craftReses(tools));
        return t;
    }

    /** An array (1-based) of crafting-spec snapshots for the given input/output widgets. */
    private static LuaTable craftSpecs(List<? extends Makewindow.SpecWidget> widgets) {
        LuaTable out = new LuaTable();
        int i = 0;
        for(Makewindow.SpecWidget w : widgets)
            out.set(++i, craftSpec(w.spec));
        return out;
    }

    /** A crafting spec (one input or output slot) as {@code {res, name, num, opt}}. Loading-guarded. */
    private static LuaValue craftSpec(Makewindow.Spec spec) {
        LuaTable t = new LuaTable();
        // The displayed resource is the constraint (a category, e.g. "any board") when the recipe accepts
        // one, else the concrete item — mirroring Makewindow.Spec.display(): that is what fills the slot.
        Indir<Resource> res = (spec.constraint != null) ? spec.constraint.res : spec.item.res;
        String id = AddonManager.resIdent(res);
        if(id != null)
            t.set("res", LuaValue.valueOf(id));
        String name = AddonManager.resTipName(res, id);
        if(name != null)
            t.set("name", LuaValue.valueOf(name));
        t.set("num", LuaValue.valueOf(spec.num));          // -1 = unspecified (≈ 1); exposed faithfully
        boolean opt;
        try {
            opt = spec.opt();                              // reads info() — may Loading before resources land
        } catch(RuntimeException e) {
            opt = false;
        }
        t.set("opt", LuaValue.valueOf(opt));
        return t;
    }

    /** An array (1-based) of {@code {res, name}} snapshots for bare resource lists (qmod / tools). */
    private static LuaTable craftReses(List<Indir<Resource>> reses) {
        LuaTable out = new LuaTable();
        int i = 0;
        for(Indir<Resource> res : reses)
            out.set(++i, craftRes(res));
        return out;
    }

    /** A bare resource reference as {@code {res, name}} (a quality modifier or a tool). Loading-guarded. */
    private static LuaValue craftRes(Indir<Resource> res) {
        LuaTable t = new LuaTable();
        String id = AddonManager.resIdent(res);
        if(id != null)
            t.set("res", LuaValue.valueOf(id));
        String name = AddonManager.resTipName(res, id);
        if(name != null)
            t.set("name", LuaValue.valueOf(name));
        return t;
    }
}
