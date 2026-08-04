package io.brodgar.addon;

import java.util.HashMap;
import java.util.Map;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.TwoArgFunction;

/**
 * The <b>retired-name table</b> (spec {@code 039-uniform-api} §2.10): every spelling the uniform grammar
 * replaced, mapped to a message that <b>names its replacement</b>, and the {@code __index} metamethods that
 * throw it.
 *
 * <p><b>Why it exists.</b> Deleting a field from a Lua table makes it read as plain {@code nil}, so the old
 * spelling fails later and elsewhere as <i>"attempt to call a nil value"</i> — a message that names neither
 * the verb nor the file that used it. Across a surface this size that is the difference between porting an
 * addon and hunting one. So a retired name is not absent: it is a field read that <b>throws</b>, at the exact
 * line that wrote it, saying what to write instead.
 *
 * <p><b>Two levels, one table.</b> A retired <i>section</i> is keyed {@code "hafen.<name>"} and hangs off the
 * {@code hafen} table's own {@code __index}; a retired <i>verb</i> is keyed {@code "hafen.<section>.<name>"}
 * and hangs off that section's callable table. Anything not in the table reads as plain {@code nil}, so a
 * feature probe ({@code if hafen.something then}) keeps working and only a name that genuinely moved is loud.
 *
 * <p>The entries are pure data, generated from the feature's before/after inventory, so coverage is
 * mechanical rather than remembered: a spelling that moved with no row here is a porting error nobody is
 * told about.
 */
final class Retired {
    private Retired() {
    }

    /** {@code "hafen.events"} / {@code "hafen.time.clock"} → the message naming the replacement. */
    private static final Map<String, String> NAMES = new HashMap<String, String>();

    static {
        // ---- sections whose NAME changed (§2.3: the three surviving plurals go singular) ----------------
        put("hafen.events", "hafen.events is now hafen.event() — subscribe with hafen.event():on(name, fn)");

        // ---- hafen.gob is DELETED into the live world (D-066): a gob lives IN the world ------------------
        put("hafen.gob", "hafen.gob(id) is now hafen.world():gob():get(id) — still never nil, and"
            + " gob:exists() is still the liveness test");

        // ---- the eight verb-only sections: every dotted verb is now a colon call on the section ---------
        section("time", "clock", "dayFraction", "isNight", "season", "moon", "yearFraction");
        section("slash", "register");
        section("json", "parse", "encode");
        section("hook", "input", "action", "message", "grab");
        section("timer", "after", "every");

        // http keeps its verb names but loses its options table, so the message says both halves.
        put("hafen.http.get", "hafen.http.get(url, opts, cb) is now hafen.http():get(url, cb) — opts.headers"
            + " and opts.timeout are setters on the request it hands back: req:header(name, value),"
            + " req:timeout(ms)");
        put("hafen.http.post", "hafen.http.post(url, body, opts, cb) is now hafen.http():post(url, body, cb)"
            + " — opts.headers and opts.timeout are setters on the request it hands back:"
            + " req:header(name, value), req:timeout(ms)");

        // ---- hafen.world: the gob verbs fold into one read-only collection, and every spatial verb -------
        // ---- takes a Position instead of a pair of numbers (§2.7).
        put("hafen.world.gobs", "hafen.world.gobs(filter) is now hafen.world():gob():list(filter)");
        put("hafen.world.count", "hafen.world.count(filter) is now hafen.world():gob():count(filter)");
        put("hafen.world.nearest", "hafen.world.nearest(filter) is now hafen.world():gob():nearest(filter)");
        put("hafen.world.within", "hafen.world.within(r, filter) is now hafen.world():gob():within(r, filter)");
        put("hafen.world.tile", "hafen.world.tile(x, y) is now hafen.world():tile(p), where p is a Position"
            + " (gob:position(), or hafen.world():position(x, y))");
        put("hafen.world.height", "hafen.world.height(x, y) is now hafen.world():height(p), where p is a"
            + " Position (gob:position(), or hafen.world():position(x, y))");
        put("hafen.world.grid", "hafen.world.grid(x, y) is now hafen.world():grid():at(p), where p is a"
            + " Position (gob:position(), or hafen.world():position(x, y))");
        put("hafen.world.gridPos", "hafen.world.gridPos(x, y) is gone: a Position IS the anchor."
            + " hafen.world():position(x, y) builds one and p:info() is the {gridId, x, y} form —"
            + " and hafen.store keeps a Position itself, so there is nothing to convert");
        put("hafen.world.fromGridPos", "hafen.world.fromGridPos(saved) is now hafen.world():position(saved)"
            + " — and a Position read back out of hafen.store is already one, so there is nothing to convert");
        put("hafen.world.worldToTile", "hafen.world.worldToTile(x, y) is now p:tileCoord(), on the Position"
            + " itself");
        put("hafen.world.tileToWorld", "hafen.world.tileToWorld(tx, ty) is now"
            + " hafen.world():tileToWorld(tx, ty)");
        put("hafen.world.tileToGrid", "hafen.world.tileToGrid(tx, ty) is now hafen.world():tileToGrid(tx, ty)");
        put("hafen.world.screenToWorld", "hafen.world.screenToWorld(sx, sy, fn) is now"
            + " hafen.world():screenToWorld(sx, sy, fn), and fn receives a Position");
        put("hafen.world.snapPlace", "hafen.world.snapPlace(x, y, fine) is now"
            + " hafen.world():snapPlace(p, fine), and it hands back a Position");
        put("hafen.world.snapAngle", "hafen.world.snapAngle(a, fine) is now hafen.world():snapAngle(a, fine)");
        put("hafen.world.placeGrid", "hafen.world.placeGrid() is gone — it read the same setting as"
            + " hafen.client():options():interface():posGran(), which also writes it");
        put("hafen.world.placeAngle", "hafen.world.placeAngle() is gone — it read the same setting as"
            + " hafen.client():options():interface():angGran(), which also writes it (in DEGREES per step)");

        // ---- hafen.act: R1 throughout, and the four spatial verbs take Positions -----------------------
        section("act", "enabled", "clickGob", "item", "menu", "flower", "raw");
        put("hafen.act.moveTo", "hafen.act.moveTo(x, y) is now hafen.act():moveTo(p), where p is a Position"
            + " (gob:position(), or hafen.world():position(x, y))");
        put("hafen.act.useItemOn", "hafen.act.useItemOn(x, y, mods) is now hafen.act():useItemOn(p, mods),"
            + " where p is a Position");
        put("hafen.act.place", "hafen.act.place(x, y, angle, button, mods) is now"
            + " hafen.act():place(p, angle, button, mods), where p is a Position");
        put("hafen.act.select", "hafen.act.select(x1, y1, x2, y2, mods) is now"
            + " hafen.act():select(p1, p2, mods), where p1 and p2 are Positions");

        // ---- entity methods (keyed "<entity>:<verb>", hung off that entity's own metatable) -------------
        put("gob:pos", "gob:pos() is now gob:position(), and it hands back a Position rather than a"
            + " {x, y} table: p:x()/p:y() are the components, p:offset(dx, dy) moves, p:info() saves");
        put("gob:isplayer", "gob:isplayer() is now gob:isPlayer()");
        put("gob:overlays", "gob:overlays() is now gob:overlay():list() — gob:overlay() is the collection of"
            + " everything attached to the gob, and the verb says how many");
        put("overlay:pos", "overlay:pos() is now overlay:position(), and it hands back a Position rather than"
            + " an {x, y, a, scale} table: the facing and the size are ov:rotate() and ov:scale()");
        put("overlay:clickable", "overlay:clickable(b) does not exist — the thing under an overlay is the GOB,"
            + " and a click on a gob is the client's own (hafen.act():clickGob)");
        put("overlay:onClick", "overlay:onClick(fn) does not exist — the thing under an overlay is the GOB, and"
            + " a click on a gob is the client's own (hafen.act():clickGob)");
        put("overlay:move", "overlay:move(x, y) does not exist — an overlay's position IS its gob's, and what"
            + " you set is where it sits relative to the gob: ov:offset(x, y[, z])");

        // ---- hafen.map: five collections, and the two surfaces that sat beside it become two of them ----
        put("hafen.map.segment", "hafen.map.segment() is now hafen.map():segment():current() and"
            + " hafen.map.segment(id) is hafen.map():segment():get(id)");
        put("hafen.map.segments", "hafen.map.segments() is now hafen.map():segment():list()");
        put("hafen.map.grid", "hafen.map.grid(gridId) is now hafen.map():grid():get(gridId), and what it"
            + " hands back is the same Grid object hafen.world():grid() does");
        put("hafen.map.markers", "hafen.map.markers is now the collection hafen.map():marker():"
            + " :list(filter) :find(filter) :nearest(filter) :add(name, p) :remove(m). The add takes a"
            + " POSITION, and the colour and the on-map flag are setters on the marker it hands back:"
            + " m:color(r, g, b), m:onMap(true)");
        put("hafen.map.icons", "hafen.map.icons is now the collection hafen.map():icon(): :get(res) for one"
            + " category by its resource name, :list(filter)/:find(filter) to search by display name — so"
            + " there is no longer a rule about which argument shape means which");
        put("hafen.map.overlay", "hafen.map.overlay(tag[, on]) is now hafen.map():overlay():get(tag), whose"
            + " verbs are t:shown() (is it displayed, by anyone), t:hold() (ask for it) and t:release()"
            + " (stop asking) — a hold was never a boolean you could write");
        put("hafen.map.overlays", "hafen.map.overlays() is now hafen.map():overlay():list(), and each member"
            + " is an object: t:tag() :where() :what() :shown() :held()");

        // ---- the map entities: N1/N2/N3 renames, and the position verbs collapsing onto Position --------
        put("segment:grids", "seg:grids(area) is now seg:grid():list(area) — seg:grid() is the collection of"
            + " this segment's grids, and the verb says how many");
        put("grid:sc", "grid:sc() is now grid:segmentCoord()");
        put("grid:pos", "grid:pos() is now grid:position(), and it hands back a Position rather than an"
            + " {x, y} table: p:x()/p:y() are this session's components, p:info() is what you save");
        put("grid:mtime", "grid:mtime() is now grid:modified()");
        put("grid:overlays", "grid:overlays() is now grid:overlay():list() — grid:overlay() is the collection"
            + " of the recorded masks on this grid, and the verb says how many");
        put("marker:tc", "marker:tc() is now marker:segmentTile()");
        put("marker:pos", "marker:pos() is now marker:position(), and it hands back a Position rather than an"
            + " {x, y} table — the same Position :anchor() used to have to build");
        put("marker:anchor", "marker:anchor() is gone: marker:position() IS the anchor. A Position is durable"
            + " by construction, so p:info() is the {gridId, x, y} form and hafen.store keeps the Position"
            + " itself — there is nothing to convert");
        put("marker:dist", "marker:dist() is now marker:distance()");
        put("marker:onmap", "marker:onmap() is now marker:onMap(), and it writes too: marker:onMap(true)");
    }

    /** Register the plain {@code hafen.<section>.<verb>(…)} → {@code hafen.<section>():<verb>(…)} rows. */
    private static void section(String section, String... verbs) {
        for(String verb : verbs) {
            put("hafen." + section + "." + verb,
                "hafen." + section + "." + verb + "(…) is now hafen." + section + "():" + verb + "(…)");
        }
    }

    private static void put(String name, String message) {
        NAMES.put(name, message);
    }

    /** The message for a retired spelling, or {@code null} when the name was never registered. */
    static String message(String name) {
        return NAMES.get(name);
    }

    /**
     * The {@code __index} for the {@code hafen} table itself: a retired <b>section</b> name throws naming its
     * replacement, and every other miss reads as plain {@code nil} (so a feature probe still works).
     */
    static LuaValue hafenIndex() {
        return index("hafen");
    }

    /**
     * The {@code __index} for one section's callable table: a retired <b>verb</b> of that section throws
     * naming its replacement, every other miss reads as plain {@code nil}. This is what makes the cut visible
     * from Lua at all — {@code hafen.time.clock} is a field read, and the refusal hangs off the read.
     */
    static LuaValue sectionIndex(String section) {
        return index("hafen." + section);
    }

    /**
     * The {@code __index} for one <b>entity</b>'s metatable: a live verb answers, a retired one throws naming
     * its replacement, and anything else reads as plain {@code nil} — the same three outcomes a section has,
     * one level down. The retired rows are keyed {@code "<entity>:<verb>"} ({@code "gob:pos"}), which is how
     * they are spelled at the call site that has to be fixed.
     */
    static LuaValue methodIndex(final String entity, final LuaTable methods) {
        return new TwoArgFunction() {
            public LuaValue call(LuaValue self, LuaValue key) {
                LuaValue m = methods.rawget(key);
                if(!m.isnil())
                    return m;
                if(key.isstring()) {
                    String msg = NAMES.get(entity + ":" + key.tojstring());
                    if(msg != null)
                        throw new LuaError(msg);
                }
                return LuaValue.NIL;
            }
        };
    }

    /** The shared metamethod: {@code prefix + "." + key} in the table throws, anything else reads nil. */
    private static LuaValue index(final String prefix) {
        return new TwoArgFunction() {
            public LuaValue call(LuaValue self, LuaValue key) {
                if(key.isstring()) {
                    String msg = NAMES.get(prefix + "." + key.tojstring());
                    if(msg != null)
                        throw new LuaError(msg);
                }
                return LuaValue.NIL;
            }
        };
    }

    /** Hang {@link #hafenIndex()} on the {@code hafen} table (from {@code installHafen}, once per env). */
    static void install(LuaTable hafen) {
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, hafenIndex());
        hafen.setmetatable(mt);
    }
}
