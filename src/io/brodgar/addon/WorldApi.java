package io.brodgar.addon;

import haven.AddonWidgets;
import haven.Astronomy;
import haven.Coord;
import haven.Coord2d;
import haven.Coord3f;
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
        // ---- the ADDRESSED twins of a Position's own verbs (092.1, A-085) ---------------------------
        // A Position carries no session -- that is the type's own rule -- so its verbs were asked without one
        // and answer for the character on SCREEN. position.md has always said so, and said the other half too:
        // "a verb reached through a session resolves the place in ITS frame". These are that other half. They
        // are READS, so an unreachable place is nil here exactly as p:x() is nil, rather than the refusal the
        // act verbs raise: asking where a place is for a character that cannot locate it is a question with an
        // answer, and the answer is "nowhere it can see".
        //   components(p) -- what p:x()/p:y() answer, in THIS character's frame, as one {x=, y=} pair.
        m.set("components", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "world", "components", W);
                Coord2d rc = here(a, 2, W + ":components", user);
                return (rc == null) ? LuaValue.NIL : xy(rc.x, rc.y);
            }
        });
        // tileCoord(p) -- the tile p sits in on THIS character's lattice. A session coordinate is re-based
        // whenever the server drops the map, so two characters name one patch of ground by two tile pairs.
        m.set("tileCoord", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "world", "tileCoord", W);
                Coord2d rc = here(a, 2, W + ":tileCoord", user);
                if(rc == null)
                    return LuaValue.NIL;
                Coord tc = rc.floor(MCache.tilesz);
                return xy(tc.x, tc.y);
            }
        });
        // distance(p [, other]) -- how far p is from `other`, or from THIS character when `other` is left out.
        // The bare form is the one the gap was about: p:distance() measures from whoever holds the screen, so
        // "how far is my alt from that tree" had no spelling at all until this one.
        m.set("distance", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "world", "distance", W);
                Coord2d from = here(a, 2, W + ":distance", user);
                Coord2d to;
                if(!Args.passed(a, 3)) {
                    to = playerPos(user);           // THIS character, not the drawn one: that is the whole row
                } else {
                    to = here(a, 3, W + ":distance", "other", user);
                }
                return ((from == null) || (to == null)) ? LuaValue.NIL : LuaValue.valueOf(from.dist(to));
            }
        });
        // Pure coordinate conversions between the two lattice spaces (no map data needed). The world<->tile
        // direction ALSO lives on the Position itself (p:tileCoord()), which answers for the screen.
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
        // ---- the two halves of ONE conversion (092.3, A-093 / 092.2, A-092) --------------------------
        // worldToScreen(p) -> {x=, y=}; screenToWorld(pt, fn) -> fn({x=, y=} in, Position out). They live
        // together, they speak one shape, and the round trip closes:
        //   s:world():screenToWorld(s:world():worldToScreen(p), function(back) ... end)
        // with nothing in between. Before 092 one sat on s:player() and answered a table, the other took two
        // loose numbers, and the page said "the inverse is" over three differences a reader had to learn one
        // at a time.

        // worldToScreen(p) -- project a PLACE IN THE WORLD to a SCREEN POINT. It takes a Position (SS2.7) and
        // answers a plain {x, y} in px, which is deliberately NOT one: the two spaces have the same shape and
        // used to be the same type, so a widget's pixel position walked the character somewhere wrong instead
        // of failing. Now only the direction that has an answer type-checks.
        //   067.1: it answers ROOT DESIGN pixels -- the space hafen.ui():hit(), the mouse, widget:rootPos() and a
        // HUD overlay's painter already share, and the space Px exists to name. MapView.screenxf answers
        // VIEW-LOCAL DEVICE pixels (it ends in HomoCoord4f.toview over Area.sized(this.sz)), so two things are
        // undone here rather than by every caller: the view's own corner is added, and the pair goes through
        // Px.out. Unrounded, because a projected point has no pixel to round to. The same conversion
        // UiApi.paintGobOverlays already does for a gob overlay's projected point (058.2).
        //   076.3: there is ONE screen however many sessions are live, so this answers nil for a session that
        // is not the drawn one. A projection through a dormant view would name a pixel in a scene nobody is
        // looking at, which is a number the caller cannot use and cannot tell apart from one it can.
        //   092.2: AT THE POINT'S OWN HEIGHT. MapView.screenxf(Coord2d) fills the z in from getcc(), which is
        // the PLAYER's altitude -- so a place on a hillside answered where it would be if it were level with
        // the player, wrong by an amount that grows with the slope and silently. The Coord3f overload beside
        // it takes the height, and MCache.getzp is the same read s:world():height(p) already exposes. Off-
        // stream ground has no height to project at, so it is nil rather than a number measured from
        // somewhere else -- the same nil this verb already answers for a scene nobody is drawing.
        //   AND A PLACE THAT CHARACTER CANNOT LOCATE IS ONE MORE OF THEM, not a refusal: this is a READ, so
        // its Position comes through `here` like every other read's here (components, tileCoord, distance) and
        // answers null where the anchor does not resolve in this session. It used to come through
        // LuaPosition.worldArg -- the door for verbs that need somewhere to GO -- so an alt walking into
        // another segment, a cave or a house threw once a frame at whoever was drawing its ground into their
        // scene, where the honest answer is the nil this verb already has four other reasons to give. Nothing
        // ELSE moves: a missing p, an explicit nil and a {x, y} table still raise from posArg, word for word,
        // and every act verb still refuses an unreachable place, which is where getting it wrong costs
        // something. snapPlace keeps refusing too -- it answers a Position and has no nil to say it in.
        //   AND A PLACE BEHIND THE EYE IS ONE MORE OF THEM. The projective divide MapView.screenxf ends in
        // answers a pixel for a point behind the camera plane too -- a MIRRORED one, through the centre of the
        // view, which is on screen and means nothing (Eye). Zoomed all the way in on the "bad" camera
        // the eye sits beside the character looking flat along the ground, so everything behind the player is
        // behind it, and an addon tracing a footprint drew its ring across the whole screen instead of dropping
        // it. The projection goes through Eye, which has no point to give where there is none, and this verb
        // says so with the nil it already had four reasons to say.
        m.set("worldToScreen", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "world", "worldToScreen", W);
                Coord2d rc = here(a, 2, W + ":worldToScreen", user);
                MapView mv = drawn(user) ? screenView() : null;
                MCache mc = mcache(user);
                if((rc == null) || (mv == null) || (mc == null))
                    return LuaValue.NIL;
                try {
                    Coord3f sc = Eye.view(mv, mc.getzp(rc));
                    if(sc == null)
                        return LuaValue.NIL;         // behind the eye -- no pixel to name (see Eye)
                    Coord rp = mv.rootpos();         // view-local -> root, in the view's own device pixels
                    return xy(Px.out(sc.x + rp.x), Px.out(sc.y + rp.y));
                } catch(RuntimeException e) {
                    return LuaValue.NIL;             // Loading (off-stream ground) and an unattached view alike
                }
            }
        });
        // screenToWorld(pt, fn) -- the RAYCAST INVERSE of :worldToScreen: fn(p) is called with a Position on
        // the ground under ROOT DESIGN pixel pt = {x=, y=}, or fn(nil) if the pixel hit no terrain (sky/off-
        // map). ASYNCHRONOUS by necessity -- the engine reads the true terrain point from the GPU
        // (MapView.Maptest, the pass the client's own building placement uses), so a synchronous return would
        // stall the UI thread on a GPU fence; the answer arrives a frame later, exactly the lag a placement
        // ghost has. Requires being in the world.
        //   067.1: pt is the same space :worldToScreen ANSWERS and the same one ev:x()/ev:y() speak, so the
        // mouse feeds this door with no arithmetic in between. MapView.Maptest takes VIEW-LOCAL DEVICE pixels,
        // so the trip in is the exact mirror of the trip out: Px.in, then subtract the view's own corner. The
        // pair is rounded only here, at the end, because a readback names one device pixel and nothing finer.
        //   What comes BACK out of Maptest.hit is a Coord2d in world units already -- posres is not in this
        // path, and nothing is converted on that side.
        //   092.3: ONE ARGUMENT, and it is the {x, y} the other half hands back. Two loose numbers made the
        // round trip a rewrite rather than a composition; a mouse event's x and y still go in as {x = ev:x(),
        // y = ev:y()}, which is one table literal and says which is which.
        m.set("screenToWorld", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "world", "screenToWorld", W);
                LuaValue pt = Args.required(a, 2, W + ":screenToWorld", "pt");
                if(!pt.istable())
                    throw new LuaError(W + ":screenToWorld(pt, fn): pt must be a {x = , y = } table of ROOT"
                        + " DESIGN pixels -- the shape s:world():worldToScreen(p) hands back, so the round"
                        + " trip composes; got " + pt.typename());
                double sx = Args.num(pt.get("x"), W + ":screenToWorld", "pt.x", "a root design pixel").todouble();
                double sy = Args.num(pt.get("y"), W + ":screenToWorld", "pt.y", "a root design pixel").todouble();
                // ASYNC is what the missing argument is about, so BOTH branches say so: a caller who left fn
                // out is exactly the caller who wrote this as if it returned a Position, and the generic
                // "fn is required" told them the one thing they already knew (ROADMAP, filed 072).
                LuaValue fn = a.arg(3);
                if(fn.isnil())
                    throw new LuaError(W + ":screenToWorld(pt, fn): fn is required -- the answer comes back a"
                        + " frame later, through fn(p), because the engine reads the terrain point off the"
                        + " GPU. There is nothing to return here, so a Position cannot be assigned from it.");
                if(!fn.isfunction())
                    throw new LuaError(W + ":screenToWorld(pt, fn): fn must be a function -- the"
                        + " answer comes back a frame later, so there is nothing to return here");
                // The pixel is on the SCREEN, and there is one screen: reading it off a session the client
                // is not drawing is a mistake at the call site rather than a timing case, so it is refused
                // rather than answered with a callback that never comes.
                if(!drawn(user))
                    throw new LuaError(W + ":screenToWorld: that session is not on screen, and pt is a"
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
                return LuaValue.NIL;   // async -- the answer arrives through fn
            }
        });
        // focus(p) -- AIM THE VIEW at a place. The third verb of the screen-and-world family and the only one
        // that writes: the two above convert between the spaces, this one moves the space itself. Nobody walks
        // anywhere -- it is where the world is looked FROM that changes -- so it is client-local and carries no
        // permission, like everything else that only changes what you are shown.
        //   The DRAWN session's, for the same reason as its two neighbours: there is one screen, and aiming a
        // scene nobody is looking at is not a thing that can happen.
        //   It answers on a camera with a CENTRE OF ITS OWN, which is `rts` alone -- every other camera the
        // client has is bolted to the character and has nothing to aim. That is a refusal rather than a no-op:
        // a hotkey that silently did nothing under the wrong camera is the one that gets reported as broken.
        m.set("focus", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = a.arg1();
                Section.self(self, "world", "focus", W);
                Coord2d rc = LuaPosition.worldArg(a, 2, W + ":focus", "p", user);
                if(!drawn(user))
                    throw new LuaError(W + ":focus: that session is not on screen, and the view is the"
                        + " screen's. hafen.session():current():world() is the one that can be aimed.");
                MapView mv = screenView();
                if(mv == null)
                    return self;                   // between views: there is nothing to aim this frame
                if(!(mv.camera instanceof MapView.RTSCam))
                    throw new LuaError(W + ":focus: the camera in force has no centre of its own to move —"
                        + " it follows the character. The `rts` camera is the one that can be aimed;"
                        + " hafen.client():options():camera():mode(\"rts\") installs it.");
                ((MapView.RTSCam)mv.camera).focus(rc);
                return self;
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
                // The two optional ones are read HERE, beside the required pair and before the view is
                // looked up: an argument the caller got wrong is the caller's to hear about, whatever
                // state the world is in. Args.optint and not LuaJ's optint — a numeric string coerces
                // through that one and would be SENT, and anything else surfaces as a bare "bad argument".
                int button = Args.optint(a, 4, W + ":place", "button", null, 1);
                int mods = Args.optint(a, 5, W + ":place", "mods", null, 0);
                MapView mv = sendView(user, W + ":place");
                mv.wdgmsg("place", placeArgs(rc, ang, button, mods));
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
                int button = Args.optint(a, 3, W + ":click", "button", null, 1);   // before the view, as place
                int mods = Args.optint(a, 4, W + ":click", "mods", null, 0);
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
                mv.wdgmsg("click", clickGobArgs(pc, button, mods, (int)g.id, rc.floor(OCache.posres)));
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
                int mods = Args.optint(a, 4, W + ":select", "mods", null, 0);   // before the view, as place does
                MapView mv = sendView(user, W + ":select");
                mv.wdgmsg("sel", selArgs(p1, p2, mods));
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
        return here(a, i, verb, "p", user);
    }

    /** {@link #here(Varargs, int, String, String)} for a read whose Position argument is not called {@code p}. */
    private static Coord2d here(Varargs a, int i, String verb, String param, String user) {
        return LuaPosition.posArg(a, i, verb, param).world(user);
    }

    // ---- the two wire shapes, pure so they are testable without a session --------------------------
    // They moved here from ActApi with the verbs that send them (048.4) and stayed pure: they hold no live
    // state, so the encoding can be asserted without being in the world. Both floor a world Coord2d, and the
    // two lattices they floor to are the whole difference between the messages — "place" lands at a POINT
    // (posres, the server's ~1/93-of-a-tile position grid) and "sel" names TILES (MCache.tilesz).

    /**
     * The MapView {@code "place"} angle encoding: radians → the server's {@code round(angle*32768/PI)}.
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
     * The four seasons {@link Astronomy#is} indexes, in its own order. <b>The engine names none of them</b>:
     * {@link haven.Cal} is the only reader and it uses the index to pick one of four
     * {@code gfx/hud/calendar/dayscape-N} textures, and {@link Glob}'s {@code "astro"} branch defaults the
     * field to {@code 1} when the server omits it. So the domain is {@code 0..3}, the names are read off the
     * client's own calendar, and an index outside the four answers {@code nil} — the same answer the whole
     * verb gives before the first astronomy update lands.
     */
    private static final String[] SEASONS = {"spring", "summer", "autumn", "winter"};

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
        // night() — is it night right now? A bare adjective, like every other boolean (D1).
        m.set("night", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                timeRead(a, "night");
                Astronomy t = astro();
                return (t == null) ? LuaValue.NIL : LuaValue.valueOf(t.night);
            }
        });
        // season() — which of the four seasons it is, by name.
        m.set("season", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                timeRead(a, "season");
                Astronomy t = astro();
                if(t == null)
                    return LuaValue.NIL;
                return ((t.is < 0) || (t.is >= SEASONS.length)) ? LuaValue.NIL
                    : LuaValue.valueOf(SEASONS[t.is]);
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
