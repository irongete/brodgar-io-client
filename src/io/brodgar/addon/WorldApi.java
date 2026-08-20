package io.brodgar.addon;

import haven.AddonWidgets;
import haven.Astronomy;
import haven.Coord;
import haven.Coord2d;
import haven.Glob;
import haven.Gob;
import haven.MapFile;
import haven.MapView;
import haven.MCache;
import haven.OCache;
import haven.Resource;
import haven.UI;
import haven.Utils;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.VarArgFunction;

import java.util.ArrayList;
import java.util.List;


import static io.brodgar.addon.AddonManager.*;

/**
 * The LIVE half of the world — {@code s:world()} — and {@code hafen.time()}. Gobs (the per-gob reads are
 * the Gob class, {@link LuaGob}), the terrain under them, and the coordinate spaces they live in. The
 * low-level gob-read substrate (getgob/gobMatches/gobSnapshot/allGobs/oc/mcache/astro) lives in
 * {@link AddonManager}.
 *
 * <p><b>The axis is LIVE vs RECORDED</b> (037): everything here answers against {@link haven.MCache}, the
 * terrain streamed around the player — {@code nil} off-stream, gone at logout. The <i>explored</i> map, the
 * on-disk {@link MapFile} database (segments, grids, overlays, images, markers and the minimap icon registry),
 * is {@code hafen.map} and lives in {@link MapApi}.
 *
 * <p><b>{@code hafen.gob} is gone into this section</b> (spec {@code 039-uniform-api} §3.3, D-066): a gob lives
 * in the world, so {@code s:world():gob()} is the one read-only Gob collection and absorbs all five entry
 * points, {@code :get(id)} included. Keeping {@code :gob(id)} beside {@code :gobs(filter)} would have left
 * standing the exact singular/plural pair the grammar removes everywhere else.
 *
 * <p><b>Every spatial verb takes a {@link LuaPosition}</b> rather than a pair of numbers, and the four position
 * verbs the API used to have — {@code gridPos}, {@code fromGridPos}, {@code marker:anchor()} and the plain
 * {@code {x, y}} table — collapse into it. The Lua verbs {@code hafen.world.placeGrid()} /
 * {@code hafen.world.placeAngle()} are cut outright: they read the same two {@link MapView} fields as
 * {@code options():interface():posGran()}/{@code :angGran()}, which also write them, and the two doors did not
 * even agree on units. (The {@link #placeAngle} below is a different thing entirely — 048.4's pure encoder for
 * the {@code "place"} message's angle field.)
 *
 * <p><b>And the world is where the character ACTS on it</b> (048.4): {@code :place(p, angle, button, mods)}
 * and {@code :select(p1, p2, mods)} arrived from the dissolving {@code hafen.act()} — a verb lives with what it
 * CHANGES, and both change the world. {@code place} in particular lands beside the {@code snapPlace} /
 * {@code snapAngle} that exist to prepare its two arguments and until now sat a whole section away from it.
 * {@code :click(gob, button, mods)} joins them from the other direction (079.3): a {@link LuaGob} is the
 * server's object rather than one character's reading of it, so it has nobody to send a click as, and the
 * session that makes the gesture is what supplies one.
 */
final class WorldApi {
    private WorldApi() {}

    /** How this section is reached, and so how every one of its messages spells itself. */
    static final String W = "session:world()";

    /**
     * Build the world section object for {@code (owner, user)} — <b>one character's world</b>, reached as
     * {@code s:world()} (076.3). Called once per pair by {@link LuaSession}, which hangs the result on the
     * interned Session handle: the section object, both its collections and every closure in here are minted
     * ONCE for that pair, so {@code s:world() == s:world()}, {@code s:world():gob() == s:world():gob()}, and a
     * draw callback writing {@code s:world():gob():nearest(...)} at 60 fps allocates nothing.
     *
     * <p><b>Every verb reads the session it hangs on, not the screen.</b> {@code user} is the account, which is
     * the whole of the address, and each read re-resolves through it — so a handle kept across a character
     * switch or the session ending answers about that login and goes {@code nil}-shaped when it is gone,
     * rather than quietly turning into whoever holds the screen.
     *
     * <p><b>The exceptions are what belongs to the SCREEN</b>, and they say so: {@code screenToWorld} reads
     * the pixel the pointer is over, and {@code place}/{@code select} are gestures with that pointer. There is
     * one screen however many sessions are live, so those answer for the drawn session and refuse for any
     * other rather than aiming at a scene nobody is looking at.
     */
    static LuaValue world(final Addon owner, final String user) {
        final LuaValue gobs = gobCollection(owner, user);
        final LuaValue grids = gridCollection(owner, user);
        LuaTable m = new LuaTable();
        // gob() — the read-only Gob collection. :get(id) is NEVER nil (it is what lets you anchor to a gob
        // before it streams in; :exists() is the liveness test), while :find/:nearest answer nil and :list
        // answers an empty array. A collection needs no :add/:remove to be one.
        m.set("gob", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Section.self(self, "world", "gob", W);
                return gobs;
            }
        });
        // grid() — the grids streamed in right now, addressed by the point they cover.
        m.set("grid", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Section.self(self, "world", "grid", W);
                return grids;
            }
        });
        // position(x, y) — a Position from session world components; position(saved) — one rebuilt from the
        // {gridId, x, y} durable form. A Position read back out of hafen.store is already a Position, so the
        // second form is for a shape that arrived some other way (a message, a file, another player).
        m.set("position", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "world", "position", W);
                LuaValue first = Args.required(a, 2, W + ":position", "x (or a saved position)");
                if(first.istable())
                    return savedPosition(owner, first);
                Args.num(first, W + ":position", "x",
                         "a session world component, or pass the one table p:info() gave you");
                LuaValue yv = Args.num(a, 3, W + ":position", "y", "a session world component");
                return LuaPosition.ofWorld(owner, user, first.todouble(), yv.todouble());
            }
        });
        // tile(p) — the tileset id + resource name under a Position; nil off-stream.
        m.set("tile", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "world", "tile", W);
                Coord2d rc = here(a, 2, W + ":tile", user);
                MCache mc = mcache(user);
                if((mc == null) || (rc == null))
                    return LuaValue.NIL;
                try {
                    int id = mc.gettile(rc.floor(MCache.tilesz));
                    LuaTable t = new LuaTable();
                    t.set("id", LuaValue.valueOf(id));
                    Resource r = mc.tilesetr(id);
                    if(r != null)
                        t.set("name", LuaValue.valueOf(r.name));
                    return t;
                } catch(RuntimeException e) {   // Loading etc.
                    return LuaValue.NIL;
                }
            }
        });
        // height(p) — terrain height under a Position; nil off-stream.
        m.set("height", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "world", "height", W);
                Coord2d rc = here(a, 2, W + ":height", user);
                MCache mc = mcache(user);
                if((mc == null) || (rc == null))
                    return LuaValue.NIL;
                try {
                    return LuaValue.valueOf(mc.getcz(rc.x, rc.y));
                } catch(RuntimeException e) {
                    return LuaValue.NIL;
                }
            }
        });
        // Pure coordinate conversions between the two lattice spaces (no map data needed). The world<->tile
        // direction lives on the Position itself (p:tileCoord()), because that one is about a place.
        m.set("tileToWorld", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "world", "tileToWorld", W);
                double tx = number(a, 2, W + ":tileToWorld", "tx");
                double ty = number(a, 3, W + ":tileToWorld", "ty");
                return xy(tx * MCache.tilesz.x, ty * MCache.tilesz.y);
            }
        });
        m.set("tileToGrid", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "world", "tileToGrid", W);
                double tx = number(a, 2, W + ":tileToGrid", "tx");
                double ty = number(a, 3, W + ":tileToGrid", "ty");
                Coord gc = Coord.of((int)tx, (int)ty).div(MCache.cmaps);
                return xy(gc.x, gc.y);
            }
        });
        // screenToWorld(sx, sy, fn) — the RAYCAST INVERSE of s:player():worldToScreen: fn(p) is called with
        // a Position on the ground under ROOT DESIGN pixel (sx,sy), or fn(nil) if the pixel hit no terrain
        // (sky/off-map). ASYNCHRONOUS by necessity — the engine reads the true terrain point from the GPU
        // (MapView.Maptest, the pass the client's own building placement uses), so a synchronous return would
        // stall the UI thread on a GPU fence; the answer arrives a frame later, exactly the lag a placement
        // ghost has. Requires being in the world.
        //   067.1: (sx,sy) is the same space worldToScreen ANSWERS and the same one ev:x()/ev:y() speak, so the
        // mouse feeds this door with no arithmetic in between. MapView.Maptest takes VIEW-LOCAL DEVICE pixels,
        // so the trip in is the exact mirror of the trip out: Px.in, then subtract the view's own corner. The
        // pair is rounded only here, at the end, because a readback names one device pixel and nothing finer.
        //   What comes BACK out of Maptest.hit is a Coord2d in world units already — posres is not in this
        // path, and nothing is converted on that side.
        m.set("screenToWorld", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "world", "screenToWorld", W);
                double sx = number(a, 2, W + ":screenToWorld", "sx");
                double sy = number(a, 3, W + ":screenToWorld", "sy");
                LuaValue fn = Args.required(a, 4, W + ":screenToWorld", "fn");
                if(!fn.isfunction())
                    throw new LuaError(W + ":screenToWorld(sx, sy, fn): fn must be a function — the"
                        + " answer comes back a frame later, so there is nothing to return here");
                // The pixel is on the SCREEN, and there is one screen: reading it off a session the client
                // is not drawing is a mistake at the call site rather than a timing case, so it is refused
                // rather than answered with a callback that never comes.
                if(!drawn(user))
                    throw new LuaError(W + ":screenToWorld: that session is not on screen, and (sx, sy) is a"
                        + " point on the screen. hafen.session():current():world() is the one to raycast in.");
                MapView mv = screenView();
                if(mv != null) {
                    Coord rp;
                    try {
                        rp = mv.rootpos();
                    } catch(RuntimeException e) {
                        return LuaValue.NIL;   // an unattached view: no callback, like a readback that failed
                    }
                    screenToWorld(owner, mv, (int)Math.round(Px.in(sx)) - rp.x,
                                  (int)Math.round(Px.in(sy)) - rp.y, fn);
                }
                return LuaValue.NIL;   // async — the answer arrives through fn
            }
        });
        // snapPlace(p [, fine]) — snap a Position to the client's PLACEMENT grid, IDENTICALLY to placing a
        // building (D-033): no fine -> the tile centre; fine=true -> the sub-tile grid the interface option
        // posGran() sets (free when it is 0). Pure static math shared with the engine's own StdPlace, so it
        // always honours the live setting; no map data needed.
        m.set("snapPlace", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "world", "snapPlace", W);
                Coord2d rc = LuaPosition.worldArg(a, 2, W + ":snapPlace", "p", user);
                int modflags = a.arg(3).toboolean() ? UI.MOD_SHIFT : 0;
                Coord2d s = MapView.placeSnap(new Coord2d(rc.x, rc.y), modflags);
                return LuaPosition.ofWorld(owner, user, s.x, s.y);
            }
        });
        // snapAngle(a [, fine]) — snap a facing angle (RADIANS) to the client's placement-ANGLE grid, so a
        // gizmo rotate feels IDENTICAL to rotating a building: no fine -> 45 degree steps; fine=true -> the
        // finer grid the interface option angGran() sets. Normalized to (-pi, pi].
        m.set("snapAngle", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "world", "snapAngle", W);
                double ang = number(a, 2, W + ":snapAngle", "a");
                return LuaValue.valueOf(snapPlaceAngle(ang, a.arg(3).toboolean()));
            }
        });
        // ---- the PROTECTED verbs (048.4, 079.3) ------------------------------------------------------
        // Two arrived from hafen.act(), the one section that grouped its verbs by PERMISSION rather than by
        // what they act on, and the third from the Gob, which stopped being one character's reading of an
        // object and so stopped having anybody to act as. All three change the world, so all three live here —
        // and `place` lands directly beside the snapPlace/snapAngle that exist to prepare its two arguments.
        // Each sends exactly the MapView wdgmsg the matching mouse gesture produces (mousedown's place branch,
        // its click branch, Selector.mmouseup), so the client stays server-authoritative: an addon can only
        // send what a player could.
        //   The gate runs FIRST — before the receiver and the arguments are looked at (D-213) — so an addon
        // that never declared "world.place" is told THAT rather than "angle must be a number". Each hands the
        // section back, so a run of writes chains like every other setter in the API.

        // place(p, angle [, button [, mods]]) — place the object currently ON YOUR CURSOR at a Position,
        // rotated by `angle` RADIANS (s:world():snapAngle(a) hands you one already snapped to the client's
        // own placement grid; the wire encoding round(angle*32768/PI) is the server's, not radians).
        // button 1 = confirm (default), mods 0 default.
        //   Placement is SERVER-INITIATED — what sits on the cursor is a Plob the server put there — so with
        // nothing being placed the message is simply ignored, and nothing comes back to say so. This verb
        // cannot tell you whether it did anything, and there is no read here that could: the client's own
        // MapView.placing is private.
        m.set("place", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = a.arg1();
                AddonManager.requirePermission(owner, Permission.WORLD_PLACE);
                Section.self(self, "world", "place", W);
                Coord2d rc = LuaPosition.worldArg(a, 2, W + ":place", "p", user);
                double ang = number(a, 3, W + ":place", "angle");
                MapView mv = sendView(user, W + ":place");
                mv.wdgmsg("place", placeArgs(rc, ang, a.arg(4).optint(1), a.arg(5).optint(0)));
                return self;
            }
        });
        // click(gob [, button [, mods]]) — click a game object: exactly the MapView "click" a left/right-click
        // on it sends, so the client stays server-authoritative. button 1 = left (default; select/interact),
        // 3 = right (the radial menu); mods = a modifier bitfield (0 default; Shift=1 Ctrl=2 Alt=4, matching
        // the keybind syntax). It sends the bare gob-click encoding {…, 0, gobid, gobrc, 0, -1} — a generic
        // "the WHOLE object", faithful for world objects (trees/containers/…); a specific sub-mesh or
        // composite body part is not targeted (deferred).
        //   079.3: it lives HERE and not on the Gob, because a Gob is the object and an object has nobody to
        // send a click as. A click is something a CHARACTER does, so the session acts and the world is the
        // target — session:player():move(p)'s shape. The key stays "gob.click": a key names the ACTION, and
        // the action is still clicking an object.
        //   PROTECTED, and the gate runs FIRST — before the receiver and the gob are looked at (D-213), so an
        // addon that never declared it is told that rather than "no such gob". Unlike every read on a Gob, an
        // object this character cannot see throws: a click is a message about a specific object, and there is
        // no such thing as sending it to nothing. Hands the section back, so a click chains.
        m.set("click", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = a.arg1();
                AddonManager.requirePermission(owner, Permission.GOB_CLICK);
                Section.self(self, "world", "click", W);
                LuaValue gv = Args.required(a, 2, W + ":click", "gob");
                LuaGob h = LuaGob.resolve(gv);
                if(h == null)
                    throw new LuaError(W + ":click(gob, button, mods): gob must be a Gob object"
                        + " (s:world():gob():get(id), s:world():gob():nearest(...)), got " + gv.typename());
                MapView mv = sendView(user, W + ":click");
                Gob g = getgob(user, h.id);
                if(g == null)
                    throw new LuaError(W + ":click: that character cannot see that object — it left view,"
                        + " despawned, or was never in this character's world (gob:sessions() says who has"
                        + " it). Nothing was sent.");
                Coord2d rc;
                synchronized(g) { rc = g.rc; }              // OCache discipline: copy under the gob lock
                if(rc == null)
                    throw new LuaError(W + ":click: the gob has no position yet");
                Coord pc = (mv.ui != null) ? mv.ui.mc : Coord.z;   // dummy screen coord, like MiniMap.mvclick
                mv.wdgmsg("click", clickGobArgs(pc, a.arg(3).optint(1), a.arg(4).optint(0),
                                                (int)g.id, rc.floor(OCache.posres)));
                // 047.3: the same token the real click records in MapView.Click.hit — and here the gob is not
                // correlated but KNOWN, this being addon code that named it. lcc is untouched by a programmatic
                // click, so a menu the server opens in reply matches on the press point exactly as it does for a
                // mouse click, and a player press in between moves lcc and invalidates it, which is the point.
                ClickToken.note(g.id, (mv.ui != null) ? mv.ui.lcc : null);
                return self;
            }
        });
        // select(p1, p2 [, mods]) — area-select the tile rectangle spanned by two Positions: the MapView
        // "sel" a drag with a tile-area tool sends. The corners are floored to TILE coords — the same
        // conversion p:tileCoord() exposes — so the two Positions name the tiles they fall in, not a
        // sub-tile rectangle. mods 0 default. Drives tile-area tools.
        m.set("select", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = a.arg1();
                AddonManager.requirePermission(owner, Permission.WORLD_SELECT);
                Section.self(self, "world", "select", W);
                Coord2d p1 = LuaPosition.worldArg(a, 2, W + ":select", "p1", user);
                Coord2d p2 = LuaPosition.worldArg(a, 3, W + ":select", "p2", user);
                MapView mv = sendView(user, W + ":select");
                mv.wdgmsg("sel", selArgs(p1, p2, a.arg(4).optint(0)));
                return self;
            }
        });
        return Section.object("world", m, W);
    }

    /**
     * {@code s:world():gob()} — every loaded game object, as a read-only collection of Gob objects (D-044:
     * live handles, never snapshots). The gob list is copied under the {@code OCache} lock by {@link
     * AddonManager#allGobs}; the filters run outside it, because a function filter re-enters Lua.
     */
    private static LuaValue gobCollection(final Addon owner, final String user) {
        LuaTable extra = new LuaTable();
        // nearest(filter) / within(r, filter) measure from the player and skip the player's own gob — the one
        // thing that makes them different from :find()/:list() over the same members.
        extra.set("nearest", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaCollection.receiver(a.arg1(), "nearest");
                LuaValue filter = a.arg(2);
                Gob pl = playerGob(user);
                if(pl == null)
                    return LuaValue.NIL;
                Coord2d prc;
                synchronized(pl) { prc = pl.rc; }
                if(prc == null)
                    return LuaValue.NIL;
                long self = pl.id;
                Gob best = null;
                double bestd = Double.POSITIVE_INFINITY;
                for(Gob g : allGobs(user)) {
                    if(g.id == self)
                        continue;
                    if(!gobMatches(filter, owner, g))
                        continue;
                    double d = distTo(g, prc);
                    if(Double.isNaN(d) || (d >= bestd))
                        continue;
                    bestd = d;
                    best = g;
                }
                return (best == null) ? LuaValue.NIL : LuaGob.of(owner, best.id);
            }
        });
        extra.set("within", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaCollection.receiver(a.arg1(), "within");
                double r = number(a, 2, W + ":gob():within", "radius");
                LuaValue filter = a.arg(3);
                LuaTable out = new LuaTable();
                Gob pl = playerGob(user);
                if(pl == null)
                    return out;
                Coord2d prc;
                synchronized(pl) { prc = pl.rc; }
                if(prc == null)
                    return out;
                long self = pl.id;
                int i = 0;
                for(Gob g : allGobs(user)) {
                    if(g.id == self)
                        continue;
                    if(!gobMatches(filter, owner, g))
                        continue;
                    double d = distTo(g, prc);
                    if(Double.isNaN(d) || (d > r))
                        continue;
                    out.set(++i, LuaGob.of(owner, g.id));
                }
                return out;
            }
        });
        return LuaCollection.create(W + ":gob()", new LuaCollection.Source() {
            public List<LuaValue> members() {
                List<LuaValue> out = new ArrayList<LuaValue>();
                for(Gob g : allGobs(user))
                    out.add(LuaGob.of(owner, g.id));
                return out;
            }

            public String needle(LuaValue member) {
                LuaGob h = LuaGob.resolve(member);
                Gob g = (h == null) ? null : anygob(h.id);
                return (g == null) ? null : gobName(g);
            }

            /** These have a name, so a string filter is a substring test over {@link #needle}. */
            public boolean named() {
                return true;
            }

            public boolean addressable() {
                return true;
            }

            public LuaValue getMember(LuaValue key) {
                if(!key.isnumber())
                    throw new LuaError(W + ":gob():get(id) expects a gob id (a number) — the"
                        + " \"player\"/\"me\"/\"partyN\" tokens are gone; your own gob is session:player():gob()");
                return LuaGob.of(owner, (long)key.todouble());
            }

            /** A gob id is the server's own, so the handle is the identity: gob:exists() is the question. */
            public LuaCollection.Missing missing() {
                return LuaCollection.Missing.MINT;
            }

            /** The key is the gob id the server published. */
            public String keyName() {
                return "id";
            }
        }, extra);
    }

    /**
     * {@code s:world():grid()} — the map grids <b>streamed in right now</b>, as {@link LuaMapGrid}
     * objects. {@code :at(p)} addresses one by the point it covers and {@code :get(id)} by the id the server
     * published; a grid has no name, so a string filter is refused rather than quietly matching nothing.
     *
     * <p><b>This is the same entity {@code hafen.map():grid()} hands back</b>, and that is not a tidy-up: the
     * live half publishes {@code MCache.Grid.id} and the recorded half interns on the very same {@code long},
     * so a grid was never two things. Each door answers {@code nil} for what its own half does not have —
     * ground you are standing on that has not been written down yet is {@code :live()} true and
     * {@code :exists()} false, and ground explored a year ago is the mirror.
     */
    private static LuaValue gridCollection(final Addon owner, final String user) {
        LuaTable extra = new LuaTable();
        // at(p) — the grid covering a Position. A plain lookup of what is streamed, never MCache.getgrid:
        // asking which grid a place is in must not send a map request for ground you only asked about.
        extra.set("at", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaCollection.receiver(a.arg1(), "at");
                Coord2d rc = here(a, 2, W + ":grid():at", user);
                MCache mc = mcache(user);
                if((mc == null) || (rc == null))
                    return LuaValue.NIL;
                MCache.Grid g = AddonWidgets.loadedGrid(mc, rc.floor(MCache.tilesz).div(MCache.cmaps));
                return (g == null) ? LuaValue.NIL : LuaMapGrid.of(owner, g.id);
            }
        });
        return LuaCollection.create(W + ":grid()", new LuaCollection.Source() {
            public List<LuaValue> members() {
                List<LuaValue> out = new ArrayList<LuaValue>();
                for(MCache.Grid g : AddonWidgets.loadedGrids(mcache(user)))
                    out.add(LuaMapGrid.of(owner, g.id));
                return out;
            }

            public boolean addressable() {
                return true;
            }

            public LuaValue getMember(LuaValue key) {
                long id = MapApi.idArg(key, W + ":grid():get(id)", "grid");
                return (AddonWidgets.gridWorldUL(mcache(user), id) == null)
                    ? LuaValue.NIL : LuaMapGrid.of(owner, id);
            }

            /** The key is the grid id the server published, a decimal string. */
            public String keyName() {
                return "id";
            }
        }, extra);
    }

    /** Rebuild a Position from the {@code {gridId, x, y}} durable form, refusing a shape that is not one. */
    private static LuaValue savedPosition(Addon owner, LuaValue saved) {
        LuaValue idv = saved.get("gridId"), xv = saved.get("x"), yv = saved.get("y");
        if((idv.type() != LuaValue.TSTRING) || !xv.isnumber() || !yv.isnumber())
            throw new LuaError(W + ":position(saved): expected the table p:info() gives you —"
                + " {gridId = \"<decimal string>\", x = <number>, y = <number>}");
        long id;
        try {
            id = Long.parseLong(idv.tojstring());
        } catch(NumberFormatException e) {
            throw new LuaError(W + ":position(saved): \"" + idv.tojstring() + "\" is not a decimal"
                + " grid id");
        }
        return LuaPosition.ofAnchor(owner, id, xv.todouble(), yv.todouble());
    }

    /**
     * A Position argument for a <b>read</b>: the world coordinate it names, or {@code null} when this session
     * cannot locate it — which for a terrain read is the same {@code nil} as off-stream. The act verbs use
     * {@link LuaPosition#worldArg} instead and refuse, because there is no acting on a place that is not here.
     */
    private static Coord2d here(Varargs a, int i, String verb, String user) {
        return LuaPosition.posArg(a, i, verb, "p").world(user);
    }

    // ---- the two wire shapes, pure so they are testable without a session --------------------------
    // They moved here from ActApi with the verbs that send them (048.4) and stayed pure: they hold no live
    // state, so the encoding can be asserted without being in the world. Both floor a world Coord2d, and the
    // two lattices they floor to are the whole difference between the messages — "place" lands at a POINT
    // (posres, the server's ~1/93-of-a-tile position grid) and "sel" names TILES (MCache.tilesz).

    /**
     * The MapView {@code "place"} angle encoding: radians → the server's {@code round(angle*32768/PI)}. Not
     * to be confused with the retired Lua verb {@code hafen.world.placeAngle()}, which read a client setting.
     */
    static int placeAngle(double radians) {
        return (int)Math.round(radians * 32768 / Math.PI);
    }

    /** The MapView {@code "place"} args ({@code {rc, angleInt, button, mods}}), {@code rc} floored to posres. */
    static Object[] placeArgs(Coord2d rc, double angle, int button, int mods) {
        return new Object[] {rc.floor(OCache.posres), placeAngle(angle), button, mods};
    }

    /**
     * The MapView {@code "sel"} args ({@code {tc1, tc2, mods}}) — the world corners floored to TILE coords,
     * the same conversion {@code p:tileCoord()} exposes ({@code Coord2d.floor(MCache.tilesz)}).
     */
    static Object[] selArgs(Coord2d p1, Coord2d p2, int mods) {
        return new Object[] {p1.floor(MCache.tilesz), p2.floor(MCache.tilesz), mods};
    }

    /**
     * The full MapView {@code "click"} args for a generic click on the gob {@code (gobId, gobRc)} — the
     * {@code {pc, mc, button, mods}} prefix extended with {@link haven.Gob.GobClick#clickargs}'
     * {@code {0, gobid, gobrc, 0, -1}} (no overlay, no specific sub-mesh). {@code mc} = the gob's own floored
     * position, as a click landing on its base would carry. Pure/testable — it holds no live state, so the wire
     * shape can be asserted without a session.
     */
    static Object[] clickGobArgs(Coord pc, int button, int mods, int gobId, Coord gobRc) {
        return new Object[] {pc, gobRc, button, mods, 0, gobId, gobRc, 0, -1};
    }

    /** A required number argument, refusing an explicit nil like every other write does (§2.9). */
    static double number(Varargs a, int i, String verb, String param) {
        return Args.num(a, i, verb, param, null).todouble();
    }

    /**
     * The receiver and the arity every {@code hafen.time()} verb shares: a colon call, and <b>no argument</b>.
     * All six are reads of a clock the server publishes and the client only interpolates, so there is nothing
     * here to write — and a silently ignored argument is exactly the mistake the grammar's "arity is the verb"
     * exists to catch, since {@code clock(1)} looks like a setter and would answer as though it were one.
     */
    private static void timeRead(Varargs a, String verb) {
        Section.self(a.arg1(), "time", verb);
        if(Args.passed(a, 2))
            throw new LuaError("hafen.time():" + verb + "() takes no arguments — it is a READ of the game"
                + " clock the server publishes, and there is nothing on hafen.time() to set");
    }

    /**
     * Build {@code hafen.time} for {@code owner}. From installHafen. A plain section object: {@code
     * hafen.time()} is the per-addon singleton and every reader is a colon call on it. {@code clock()} always
     * answers; the astronomy readers are nil until the first "astro" update lands.
     *
     * <p><b>It reads {@link AddonManager#anyglob()}, not {@link AddonManager#glob()}</b> — any live session
     * rather than the drawn one. There is one world and one clock in it: every session interpolates the same
     * server time, so which one is asked cannot change the answer, and asking the session that happens to hold
     * the screen only adds a way to get {@code nil} — through a character switch, for a number that did not
     * move. {@code nil} means the client holds no session at all, which is the login screen.
     */
    static void installTime(LuaTable hafen, final Addon owner) {
        LuaTable m = new LuaTable();
        // clock() — the game clock, in game-world seconds (Glob.globtime). Nil when no session is up.
        m.set("clock", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                timeRead(a, "clock");
                Glob g = anyglob();
                return (g == null) ? LuaValue.NIL : LuaValue.valueOf(g.globtime());
            }
        });
        // dayFraction() — 0..1 through the game day.
        m.set("dayFraction", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                timeRead(a, "dayFraction");
                Astronomy t = astro();
                return (t == null) ? LuaValue.NIL : LuaValue.valueOf(t.dt);
            }
        });
        // isNight() — is it night right now?
        m.set("isNight", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                timeRead(a, "isNight");
                Astronomy t = astro();
                return (t == null) ? LuaValue.NIL : LuaValue.valueOf(t.night);
            }
        });
        // season() — the season index the server publishes.
        m.set("season", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                timeRead(a, "season");
                Astronomy t = astro();
                return (t == null) ? LuaValue.NIL : LuaValue.valueOf(t.is);
            }
        });
        // moon() — the moon phase, 0..1.
        m.set("moon", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                timeRead(a, "moon");
                Astronomy t = astro();
                return (t == null) ? LuaValue.NIL : LuaValue.valueOf(t.mp);
            }
        });
        // yearFraction() — 0..1 through the game year.
        m.set("yearFraction", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                timeRead(a, "yearFraction");
                Astronomy t = astro();
                return (t == null) ? LuaValue.NIL : LuaValue.valueOf(t.yt);
            }
        });
        Section.install(hafen, "time", m);
    }

    // ---- raycast/snap helpers (screenToWorld / snapAngle) ----
    /**
     * {@code s:world():screenToWorld}: raycast the terrain under <b>view-local device</b> pixel
     * {@code (px,py)} — the space {@link haven.MapView.Maptest} itself takes, which the caller above has
     * already converted the addon's root design pixels into — via the
     * engine's own {@link haven.MapView.Maptest} (the pass the client's building placement uses), then call
     * {@code fn} with the ground {@link LuaPosition} (or nil for no terrain). Asynchronous: {@code Maptest.run()}
     * submits a GPU readback and its callback fires later under {@code synchronized(ui)} (so {@link #callLua} is
     * safe there, serialized with every other addon Lua). Errors installing the test are swallowed (no callback).
     */
    static void screenToWorld(final Addon owner, MapView mv, int px, int py, final LuaValue fn) {
        final Coord pc = new Coord(px, py);
        try {
            mv.new Maptest(pc) {
                protected void hit(Coord pc, Coord2d mc) {
                    // The readback is off the DRAWN scene, so the point is in that session's frame.
                    callLua(owner, Addon.C_EVENT, fn,
                            LuaPosition.ofWorld(owner, AddonManager.drawnUser(), mc.x, mc.y));
                }
                protected void nohit(Coord pc) {
                    callLua(owner, Addon.C_EVENT, fn, LuaValue.NIL);
                }
            }.run();
        } catch(RuntimeException e) {
            /* couldn't submit the readback (e.g. no render env yet) — the caller simply gets no callback */
        }
    }

    /**
     * {@code s:world():snapAngle}: snap a facing angle (radians) to the client's placement-angle grid — the
     * <b>absolute</b> analog of {@code MapView.StdPlace.rotate} (which is wheel-<i>relative</i>, so there is no
     * verbatim engine code to share, unlike position's {@link haven.MapView#placeSnap}). Coarse (no {@code fine})
     * = 45° (π/4) steps; {@code fine} = the finer grid ({@code MapView.plobagran}). Normalized to (-π, π] via
     * {@link haven.Utils#cangle}. Reads the live public field so it honours the setting with no drift.
     */
    static double snapPlaceAngle(double a, boolean fine) {
        double step = fine ? (Math.PI / MapView.plobagran) : (Math.PI / 4);
        if(step <= 0)
            return Utils.cangle(a);                 // guard a pathological setting (the console clamps it >= 2)
        return Utils.cangle(Math.round(a / step) * step);
    }
}
