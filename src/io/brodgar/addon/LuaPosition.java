package io.brodgar.addon;

import haven.AddonWidgets;
import haven.Coord;
import haven.Coord2d;
import haven.MapFile;
import haven.MCache;
import haven.MiniMap;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.VarArgFunction;

/**
 * A <b>Position</b> — the one place type in the API (spec {@code 039-uniform-api} §2.7). A position used to
 * be a plain {@code {x, y}} table, which can only be <b>one</b> of the two things a position has to be: a
 * point you can do arithmetic on, or a place you can save and send. An object is both, and that is the whole
 * reason this class exists.
 *
 * <p><b>A grid is 1100 world units, so the arithmetic belongs in here.</b> {@code p.y + 22} is correct on a
 * continuous coordinate and a bug on a grid-relative one — at {@code y = 1095} it yields 1117, past the grid
 * edge, when the honest answer is a different grid id. {@link #offset} crosses the boundary and re-derives the
 * grid, which is what lets one {@code position()} be computable <i>and</i> durable.
 *
 * <p><b>A value, not an entity.</b> It is not interned and has no lifetime: two Positions naming one point are
 * two objects, and {@code ==} is object identity rather than a comparison of places. Compare what a Position
 * <i>names</i> — {@link #tileCoord} for the tile, {@code :info()} for the durable form, {@code :distance()} for
 * nearness — never the objects. (An {@code __eq} over the numbers would be a trap either way: a Position that
 * has been through the store carries the anchor and rebuilds its world coordinate by adding back what was
 * subtracted, which is exact to the tile and not to the last bit of a double.)
 *
 * <p><b>An ANCHORED Position carries no session, and that is the rule this type sets</b> (spec
 * {@code 076-the-session-is-the-address}). A ref that names one character's things carries its session; an
 * anchored place does not, because a grid id is the server's own naming of a patch of ground and is answerable
 * in whichever session you ask. What follows is that <b>the session belongs to the DERIVATION, never to the
 * anchor</b>: {@code gob:position()} derives its anchor through the gob's own session, at the one point where
 * that session is known, and hands back a value that crosses freely; a verb that consumes it resolves it in
 * <i>its</i> session's frame. Both directions take a {@code user} — the account name, which is the whole of a
 * session's address.
 *
 * <p><b>A place with NO anchor keeps the login it was read in</b> (audit2 B05), because the world form is the
 * one form that does not cross. Its two numbers are relative to where that character logged in, so the same
 * pair read by another login names ground as far away as the two of them are apart. Such a Position therefore
 * <b>records</b> its login: its own verbs ({@code :x()}, {@code :offset}, {@code :distance}, {@code :durable})
 * re-derive in that login rather than in whichever character is on screen, and an addressed door handed it for
 * a different character <b>refuses</b>, naming the anchor as the way to carry the place across. That is the
 * whole of the difference between the two forms, and the reason to prefer the anchored one.
 *
 * <p><b>Two forms, one type.</b> A Position knows either a <i>session world</i> coordinate (what a gob reports,
 * what the map view takes) or a <i>durable anchor</i> — a server-published grid id plus the offset within that
 * grid — and derives the other on demand. The world form is what {@code gob:position()} and
 * {@code s:world():position(x, y)} produce; the anchor form is what comes back out of {@code hafen.store}
 * and {@code s:world():position(info)}. Neither form is primary and both answer the same verbs.
 *
 * <p><b>Durability is EXPLORED, not loaded.</b> Deriving the anchor takes two steps: the streamed grid's id from
 * {@link MCache}, and otherwise session coord → segment coord → the grid id the map database recorded there
 * ({@link MapApi#recordedGridId}). Step two needs nothing streamed, so a Position is durable anywhere the
 * character has <i>been</i>, and fails only on ground never visited — which is also ground nothing can act on.
 * Neither step ever asks the server for map data: {@link AddonWidgets#loadedGrid} is a plain lookup where
 * {@code MCache.getgrid} would have queued a request, because asking <i>where a place is</i> must not put
 * traffic on the wire.
 * The reverse direction is the same pair, {@link AddonWidgets#gridWorldUL} then {@link MapApi#gridUL}, and it
 * answers only for the segment the character is standing in: a place recorded in <i>another</i> segment has no
 * world coordinate this session at all, so it keeps its anchor and answers {@code nil} for {@code :x()}.
 */
public final class LuaPosition {
    /** Does this Position hold a session world coordinate ({@link #wx}/{@link #wy}) or an anchor? */
    private final boolean located;
    /** Session world components — meaningful only when {@link #located}. */
    private final double wx, wy;
    /** The durable anchor — meaningful only when <b>not</b> {@link #located}. */
    private final long gridId;
    private final double gx, gy;
    /**
     * <b>The login this place was READ IN</b> (audit2 B05), or {@code null} for one rebuilt from a durable
     * form — which belongs to no login, because a grid id names the same ground to everybody. It is what the
     * world form is worth: a world coordinate is relative to where its session logged in, so the pair of
     * numbers means that character's ground and nobody else's. The Position's own verbs re-derive in it; an
     * addressed door handed such a place for another character refuses (see {@link #world(String, String)}).
     */
    private final String login;

    private LuaPosition(boolean located, double wx, double wy, long gridId, double gx, double gy, String login) {
        this.located = located;
        this.wx = wx;
        this.wy = wy;
        this.gridId = gridId;
        this.gx = gx;
        this.gy = gy;
        this.login = login;
    }

    /** {@link #login} — the login this place was read in, or {@code null} for a durable one. */
    String login() {
        return login;
    }

    /** {@code tostring(p)} — the form it actually holds, so a log line says which one it is. */
    public String toString() {
        if(located)
            return String.format("Position(%.2f, %.2f)", wx, wy);
        return String.format("Position(%d@%.2f, %.2f)", gridId, gx, gy);
    }

    // ---- the two lookups, and the grid they meet on -----------------------------------------------

    /** A grid id plus the offset within that grid — the durable form, and what {@code :info()} publishes. */
    static final class Anchor {
        final long id;
        final double x, y;

        Anchor(long id, double x, double y) {
            this.id = id;
            this.x = x;
            this.y = y;
        }
    }

    /**
     * The anchor for a session world point — the streamed grid's id first, then the id the map database
     * recorded at that segment coord. {@code null} for ground that is neither streamed nor explored.
     */
    private static Anchor anchorAt(String user, double x, double y) {
        if(user == null)
            user = AddonManager.drawnUser();
        MCache mc = AddonManager.mcache(user);
        if(mc != null) {
            // A plain lookup, never MCache.getgrid: asking where a place IS must not send a map request for
            // ground the caller only asked about. Off-stream falls through to the recorded map below.
            Coord tc = Coord2d.of(x, y).floor(MCache.tilesz);
            MCache.Grid g = AddonWidgets.loadedGrid(mc, tc.div(MCache.cmaps));
            if(g != null)
                return new Anchor(g.id, x - (g.ul.x * MCache.tilesz.x), y - (g.ul.y * MCache.tilesz.y));
        }
        MiniMap.Location sl = MapApi.sessloc(user);
        MapFile file = MapApi.mapfile(user);
        if((sl == null) || (file == null))
            return null;
        // world -> segment tile (the conversion a marker's own x,y uses) -> the segment grid coord
        Coord sc = sl.tc.add(Coord2d.of(x, y).floor(MCache.tilesz)).div(MCache.cmaps);
        Long id = MapApi.recordedGridId(file, sl.seg.id, sc);
        if(id == null)
            return null;
        Coord2d ul = MapApi.segGridUL(sl, sc);
        return new Anchor(id.longValue(), x - ul.x, y - ul.y);
    }

    /** The world coordinate of a grid's upper-left corner in <b>that</b> session, or {@code null}. */
    private static Coord2d ulOf(long id, String user) {
        if(user == null)
            user = AddonManager.drawnUser();
        Coord2d ul = AddonWidgets.gridWorldUL(AddonManager.mcache(user), id);
        if(ul != null)
            return ul;
        return MapApi.gridUL(MapApi.gridInfoIn(MapApi.mapfile(user), id), user);
    }

    /**
     * <b>The session world coordinate of a durable place</b>, or {@code null} while this session cannot locate
     * that grid — a place recorded in another segment, and every place at all while the player is in a cave.
     * Public to the package because a free world entity holds its anchor and derives its coordinate from it
     * (045.1), so the derivation the anchor form does for itself is the derivation the entity re-runs whenever
     * the world moves under it.
     */
    static Coord2d worldOf(long gridId, double gx, double gy, String user) {
        Coord2d ul = ulOf(gridId, user);
        return (ul == null) ? null : Coord2d.of(ul.x + gx, ul.y + gy);
    }

    /** {@link #worldOf(long, double, double, String)} for an {@link Anchor} already derived. */
    static Coord2d worldOf(Anchor an, String user) {
        return worldOf(an.id, an.x, an.y, user);
    }

    /**
     * This Position's world coordinate <b>in session {@code user}</b>, or {@code null} when that session
     * cannot locate it — a place recorded in another segment, and every place at all while that character is
     * in a cave. {@code null} for {@code user} asks the session on screen.
     *
     * <p><b>A world-form Position belongs to the login it was read in, and asked for another one it RAISES</b>
     * (audit2 B05, naming {@code verb}). It has no anchor, so there is nothing to re-derive: its two numbers
     * are relative to where <i>that</i> character logged in, and handing them to another session names ground
     * as far away as the two logins are apart. Answering them silently was the one place this API let a
     * coordinate cross a login — the very thing the durable form exists to prevent — so the refusal says how
     * to carry the place across instead. A Position <b>with</b> an anchor crosses freely: a grid id is the
     * server's own naming of a patch of ground, and {@link #worldOf} re-derives it in whichever login asks.
     */
    Coord2d world(String user, String verb) {
        if(located) {
            if((user != null) && (login != null) && !user.equals(login))
                throw new LuaError(((verb == null) ? "position" : verb) + ": this place was read in another"
                    + " login; anchor it (position.md) to carry it across");
            return Coord2d.of(wx, wy);
        }
        return worldOf(gridId, gx, gy, user);
    }

    /** {@link #world(String, String)} with no verb to name — an internal derivation, never a Lua door. */
    Coord2d world(String user) {
        return world(user, null);
    }

    /** {@link #world(String, String)} in this place's OWN login — what the Position's own verbs ask. */
    Coord2d world() {
        return world(login, null);
    }

    /**
     * This Position's durable anchor <b>as session {@code user} derives it</b>, or {@code null} for ground that
     * character has never been over. {@code null} for {@code user} asks the session on screen.
     */
    Anchor anchor(String user) {
        return located ? anchorAt(user, wx, wy) : new Anchor(gridId, gx, gy);
    }

    /** {@link #anchor(String)} in this place's OWN login — what the Position's own verbs ask. */
    Anchor anchor() {
        return anchor(login);
    }

    // ---- construction ------------------------------------------------------------------------------

    /**
     * <b>A Position at a world coordinate read in session {@code user}</b> — what every live reader hands back.
     *
     * <p>The anchor is derived <b>here</b>, in that session, and what escapes is the durable form: a world
     * coordinate is relative to where its session logged in, so a pair of numbers handed out of one session and
     * read in another names different ground. Deriving it costs a grid lookup the caller was going to pay for
     * on the first {@code :info()} anyway, and the ground under a reader is streamed, so the lookup is a hit.
     *
     * <p><b>Ground with no durable form keeps the world form</b>, rather than becoming {@code nil}: that is
     * ground the character has never been over, which is not reachable for a gob it can see, and a reader that
     * has a coordinate must not start answering nothing. Such a Position means what it always did — this
     * session's coordinate — and {@code :durable()} says so.
     */
    static LuaValue ofWorld(Addon owner, String user, double x, double y) {
        Anchor an = anchorAt(user, x, y);
        if(an != null)
            return ofAnchor(owner, an.id, an.x, an.y);
        // No anchor: it keeps the world form AND the login that form is in, so nothing downstream has to
        // guess whose ground the two numbers name (audit2 B05).
        return LuaValue.userdataOf(new LuaPosition(true, x, y, 0, 0, 0, user), owner.positions.meta());
    }

    /** A Position from a durable anchor — what the store, {@code hafen.json} and {@code :position(info)} rebuild. */
    static LuaValue ofAnchor(Addon owner, long id, double x, double y) {
        return LuaValue.userdataOf(new LuaPosition(false, 0, 0, id, x, y, null), owner.positions.meta());
    }

    /** A Position at {@code rc} in session {@code user}, or {@code NIL} when the caller had no coordinate. */
    static LuaValue of(Addon owner, String user, Coord2d rc) {
        return (rc == null) ? LuaValue.NIL : ofWorld(owner, user, rc.x, rc.y);
    }

    /** The Position behind a Lua value, or {@code null} for anything that is not one. */
    static LuaPosition resolve(LuaValue v) {
        if((v == null) || !v.isuserdata())
            return null;
        Object o = v.touserdata();
        return (o instanceof LuaPosition) ? (LuaPosition)o : null;
    }

    /**
     * A <b>required</b> Position argument. A plain {@code {x, y}} table is the mistake this message exists for:
     * screen pixels and world coordinates have the same shape, so a widget's position handed to a world verb
     * would walk the character somewhere wrong instead of failing.
     */
    static LuaPosition posArg(Varargs a, int i, String verb, String param) {
        LuaValue v = Args.required(a, i, verb, param);
        LuaPosition p = resolve(v);
        if(p == null) {
            throw new LuaError(verb + ": " + param + " must be a Position — gob:position(),"
                + " session:world():position(x, y), or session:world():position(saved). A plain {x, y} table is"
                + " not one: screen pixels are not a place in the world.");
        }
        return p;
    }

    /** The world coordinate a spatial verb needs, or a refusal naming why this Position has none. */
    static Coord2d worldArg(Varargs a, int i, String verb, String param) {
        return worldArg(a, i, verb, param, null);
    }

    /**
     * {@link #worldArg(Varargs, int, String, String)} resolved in <b>session {@code user}</b> — the door every
     * verb under {@code s:world()} / {@code s:player()} uses. The refusal is the one it already had with a new
     * subject: a place is unreachable <b>for that character</b>, which is a different question from whether it
     * is unreachable for the one on screen. {@code null} for {@code user} asks the session on screen.
     */
    static Coord2d worldArg(Varargs a, int i, String verb, String param, String user) {
        LuaPosition p = posArg(a, i, verb, param);
        Coord2d rc = p.world(user, verb);
        if(rc == null)
            throw new LuaError(verb + ": " + param + " " + unreachable(user));
        return rc;
    }

    /**
     * <b>The durable place a verb that HOLDS one needs</b> (045.1), or a refusal naming why this Position has
     * none. The other door, {@link #worldArg}, asks for a coordinate <i>here and now</i> — which is the right
     * question for walking somewhere or reading the terrain, and the wrong one for a thing that has to still be
     * where it was put tomorrow: a session coordinate is re-based every time the server drops the map, so a
     * place kept as one quietly stops naming anywhere. What can be kept is the anchor, and ground nobody has
     * ever recorded has none — the client cannot invent a grid id, so that place is refused rather than pinned
     * to a number that will lie.
     *
     * <p><b>Whether the place can be REACHED is a different question and is not asked here</b> (045.2). A
     * durable place with no coordinate this session — recorded in another segment, or anywhere at all while
     * the player is in a cave — is perfectly holdable; {@link #worldOf(Anchor)} answers {@code null} for it and
     * the caller waits. Only {@link #worldArg}, the door for verbs that need somewhere to go <i>now</i>,
     * refuses that.
     */
    static Anchor anchorArg(Varargs a, int i, String verb, String param) {
        LuaPosition p = posArg(a, i, verb, param);
        Anchor an = p.anchor();
        if(an == null) {
            throw new LuaError(verb + ": " + param + " is a place with no durable form — it is a raw coordinate"
                + " over ground this character has never been over, so there is no grid id to hold it by, and a"
                + " session coordinate on its own is re-based whenever the map is dropped (walking into a cave"
                + " or a house), which would leave the thing standing on a number that has stopped meaning"
                + " anywhere. p:durable() says whether a place can be held; ground you have walked can");
        }
        return an;
    }

    /**
     * <b>Why a Position can have nowhere to go</b> — the one wording for it, shared by {@link #worldArg} and by
     * {@link LuaMarshal#toJava}, which refuses the same place for the same reason on the way to the server
     * (067.3). An author who hits it from either door reads one sentence, not two that half agree.
     */
    static final String UNREACHABLE =
        "is a place this session cannot reach — it was recorded in another part of the world, so it has no"
        + " coordinate here (p:x() reports it as nil)";

    /**
     * {@link #UNREACHABLE} with the character it is unreachable <b>for</b> named, where the caller addressed a
     * session — the whole point of an address being that "cannot reach" is now a question with more than one
     * answer, and the one the author has to act on is which character was asked.
     */
    static String unreachable(String user) {
        if(user == null)
            return UNREACHABLE;
        return "is a place " + user + " cannot reach — that character has it recorded in another part of the"
            + " world, or is somewhere its coordinates do not span (a cave, a house), so it has no coordinate"
            + " in that session. Another character may well be able to reach it.";
    }

    // ---- the per-addon metatable -------------------------------------------------------------------

    /**
     * One addon's Position metatable. A Position is a value and is never interned, so this holds nothing but
     * the metatable itself — built once, lazily, and per addon so no metatable is shared across sandboxes.
     */
    static final class Meta {
        private final Addon owner;
        private LuaValue mt;

        Meta(Addon owner) {
            this.owner = owner;
        }

        synchronized LuaValue meta() {
            if(mt == null) {
                LuaTable t = new LuaTable();
                t.set(LuaValue.INDEX, Refusal.closedIndex("position", methods(owner),
                    "a place in the world"));
                t.set("__name", LuaValue.valueOf("Position"));
                t.set("__tostring", new OneArgFunction() {
                    public LuaValue call(LuaValue self) {
                        LuaPosition p = resolve(self);
                        return LuaValue.valueOf((p == null) ? "Position(?)" : p.toString());
                    }
                });
                mt = t;
            }
            return mt;
        }
    }

    // ---- the verbs ---------------------------------------------------------------------------------

    private static LuaTable methods(final Addon owner) {
        LuaTable m = new LuaTable();
        // x() / y() — the SESSION world components, nil for a place this session cannot locate. They are not
        // the numbers :info() carries: that one is the grid id plus the offset WITHIN that grid.
        m.set("x", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Coord2d rc = handle(self, "x").world();
                return (rc == null) ? LuaValue.NIL : LuaValue.valueOf(rc.x);
            }
        });
        m.set("y", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Coord2d rc = handle(self, "y").world();
                return (rc == null) ? LuaValue.NIL : LuaValue.valueOf(rc.y);
            }
        });
        // offset(dx, dy) — a NEW Position, dx east and dy south in world units. The engine owns this because a
        // grid is 1100 world units: adding 22 to a within-grid offset near the edge is a different grid.
        //   Both halves run in the RECEIVER'S OWN LOGIN (audit2 B05): the coordinate is read in it and the new
        // place is re-derived in it. It used to read and re-mint through the character on screen, so offsetting
        // a place only a background login could locate answered nil — and position.md says it is arithmetic.
        m.set("offset", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaPosition p = handle(a.arg1(), "offset");
                double dx = num(a, 2, "position:offset", "dx"), dy = num(a, 3, "position:offset", "dy");
                Coord2d rc = p.world();
                return (rc == null) ? LuaValue.NIL : ofWorld(owner, p.login, rc.x + dx, rc.y + dy);
            }
        });
        // distance([other]) — world distance to another Position; `other` defaults to the player, exactly as
        // gob:distance() does. nil when either end has no coordinate this session.
        //   MEASURED IN THE RECEIVER'S OWN LOGIN (audit2 B05), both ends: subtracting two coordinates read in
        // two logins measures nothing, so `other` is resolved in this place's login — which is a refusal when
        // `other` is a world form belonging to another one — and the bare form asks that login's own player.
        m.set("distance", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaPosition p = handle(a.arg1(), "distance");
                Coord2d here = p.world();
                Coord2d there;
                if(!Args.passed(a, 2)) {
                    there = AddonManager.playerPos(p.login);   // the character this place was read in
                } else {
                    there = posArg(a, 2, "position:distance", "other").world(p.login, "position:distance");
                }
                return ((here == null) || (there == null)) ? LuaValue.NIL : LuaValue.valueOf(here.dist(there));
            }
        });
        // tileCoord() — the SESSION tile this position sits in, {x, y}. nil when it cannot be located here.
        m.set("tileCoord", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Coord2d rc = handle(self, "tileCoord").world();
                if(rc == null)
                    return LuaValue.NIL;
                Coord tc = rc.floor(MCache.tilesz);
                return AddonManager.xy(tc.x, tc.y);
            }
        });
        // durable() — can this be saved and still mean something? True anywhere the character has been.
        m.set("durable", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(handle(self, "durable").anchor() != null);
            }
        });
        // info() — the durable form, {gridId = "<decimal string>", x, y}. The x,y here are the offset WITHIN
        // that grid (0..1100), not the session components :x()/:y() answer.
        m.set("info", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Anchor an = handle(self, "info").anchor();
                if(an == null)
                    return LuaValue.NIL;
                LuaTable t = new LuaTable();
                t.set("gridId", LuaValue.valueOf(Long.toString(an.id)));
                t.set("x", LuaValue.valueOf(an.x));
                t.set("y", LuaValue.valueOf(an.y));
                return t;
            }
        });
        return m;
    }

    /** The Position behind a method's {@code self}, or a guiding error (a dot call passes the wrong one). */
    private static LuaPosition handle(LuaValue self, String verb) {
        LuaPosition p = resolve(self);
        if(p == null)
            throw new LuaError("position:" + verb + "() — use a COLON call on a Position");
        return p;
    }

    /** A required number argument, by TYPE and refusing an explicit nil like every other write does
     *  (§2.9) — the house door, so {@code p:offset("1", 2)} is refused here as it is everywhere else. */
    private static double num(Varargs a, int i, String verb, String param) {
        return Args.num(a, i, verb, param, "world units").todouble();
    }
}
