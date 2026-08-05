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

        // ---- hafen.ui: the lookups move onto the section, and the ROOT stops being the call itself -------
        put("hafen.ui.all", "hafen.ui.all(selector) is now hafen.ui():all(selector)");
        put("hafen.ui.node", "hafen.ui.node(id) is now hafen.ui():node(id)");
        put("hafen.ui.at", "hafen.ui.at(x, y) is now hafen.ui():at(x, y)");
        put("hafen.ui.mouse", "hafen.ui.mouse() is now hafen.ui():mouse()");
        put("hafen.ui.inventory", "hafen.ui.inventory() is now hafen.ui():inventory()");
        put("hafen.ui.equipment", "hafen.ui.equipment() is now hafen.ui():equipment()");
        put("hafen.ui.hand", "hafen.ui.hand() is now hafen.ui():hand()");
        put("hafen.ui.on", "hafen.ui.on(selector, event, fn) is now hafen.ui():on(selector, event, fn)");

        // ---- the three UI builders: no config table survives, so each key is a setter on what you get back --
        put("hafen.ui.window", "hafen.ui.window{…} is now hafen.ui():window() plus chained setters:"
            + " :title(s) :parent(w) :position(x, y) :size(w, h) :font(h) and the callbacks :onDraw(fn)"
            + " :onTick(fn) :onClick(fn) :onMouseUp(fn) :onMouseMove(fn) :onWheel(fn) :onDrop(fn) :onClose(fn)."
            + " Each has a matching bare read, and `pos` is spelt `position`");
        put("hafen.ui.widget", "hafen.ui.widget{…} is now hafen.ui():widget() plus chained setters:"
            + " :parent(w) :position(x, y) :size(w, h) :font(h) and the callbacks :onDraw(fn) :onTick(fn)"
            + " :onClick(fn) :onMouseUp(fn) :onMouseMove(fn) :onWheel(fn) :onDrop(fn). A bare widget has no"
            + " caption, so :title(s) is the window builder's");
        put("hafen.ui.overlay", "hafen.ui.overlay(fn) is now hafen.ui():overlay():onDraw(fn), and the overlay"
            + " it hands back ends with :destroy() rather than :remove()");

        // ---- the stylesheet: a Sheet of Rules, so a selector NAMES a rule and its properties are setters ----
        put("hafen.ui.skin", "hafen.ui.skin{…} is now hafen.ui():sheet(): s:rule(selector) hands back the rule"
            + " for that key and its properties are setters (:font(h) :color(r,g,b) :bg{…} :border{…} :pad(n)"
            + " :position(x, y) :anchor{…} :size(w, h)), s:load(t) takes a whole sheet as data, and"
            + " s:install() / s:drop() apply and remove it — hafen.ui.skin(nil) is s:drop()");
        // hafen.ui.root is deliberately NOT here, though hafen.ui():root() now exists: 030.1 cut it, this feature
        // did not move it, and the table's rule is that it carries what THIS grammar renamed. A name nothing here
        // touched goes on reading nil, which is what keeps a feature probe (`if hafen.something then`) honest.

        // ---- the Widget entity: two renames, one hard cut, and the read that needed a noun ---------------
        put("widget:pos", "widget:pos() is now widget:position(), and it still answers in PIXELS within the"
            + " parent — a widget lives on the screen, so this is not a Position and hafen.act():moveTo refuses it");
        put("widget:rootpos", "widget:rootpos() is now widget:rootPos()");
        put("widget:show", "widget:show() is now widget:visible(true) — a boolean property is a property, so the"
            + " value is the argument rather than the verb's name");
        put("widget:hide", "widget:hide() is now widget:visible(false) — a boolean property is a property, so the"
            + " value is the argument rather than the verb's name");
        put("widget:skin", "widget:skin{…} is now widget:rule(), the same Rule object a sheet's selectors hand"
            + " back: its properties are setters (:font(h) :color(r,g,b) :bg{…} :border{…} :pad(n)),"
            + " widget:rule():info() reads your whole level back, and widget:rule():remove() drops it."
            + " widget:style() still answers what the widget RESOLVES to");
        put("marker:onmap", "marker:onmap() is now marker:onMap(), and it writes too: marker:onMap(true)");

        // ---- the world entities: two collections under hafen.render(), and hafen.ghost() IS one -------------
        put("hafen.render.sprite", "hafen.render.sprite{image=..., x=, y=} is now"
            + " hafen.render():sprite():add(imageAsset, p) plus chained setters: :position(p [, a]) :scale(k)"
            + " :alpha(a) :tint(r, g, b) :billboard(b) :clickable(b) :onClick(fn). An image ON A GAME OBJECT is"
            + " gob:overlay():add(key):image(asset), which keys it and dies with the gob");
        put("hafen.render.object", "hafen.render.object{model=..., x=, y=} is now"
            + " hafen.render():object():add(meshAsset, p) plus chained setters: :position(p [, a]) :scale(k)"
            + " :alpha(a) :tint(r, g, b) :clickable(b) :onClick(fn). A model ON A GAME OBJECT is"
            + " gob:overlay():add(key):model(asset), which keys it and dies with the gob");
        put("hafen.ghost.new", "hafen.ghost.new{res=..., x=, y=} is now hafen.ghost():add(res, p) plus chained"
            + " setters: :position(p [, a]) :scale(k) :alpha(a) :tint(r, g, b) :clickable(b) :onClick(fn)."
            + " A .res model ON A GAME OBJECT is gob:overlay():add(key):ghost(res)");
        put("hafen.ghost.list", "hafen.ghost.list(filter) is now hafen.ghost():list(filter) — hafen.ghost() IS"
            + " the collection of the ghosts this addon placed");

        // ---- Sprite / Object / Ghost: one vocabulary, and every property a read/write pair on one name ------
        for(String kind : new String[] { "sprite", "object", "ghost" }) {
            put(kind + ":pos", kind + ":pos() is now " + kind + ":position(), and it hands back a Position"
                + " rather than an {x, y, a, scale} table: the facing and the size are " + kind + ":rotate()"
                + " and " + kind + ":scale()");
            put(kind + ":move", kind + ":move(x, y [, a]) is now " + kind + ":position(p [, a]), where p is a"
                + " Position (gob:position(), or hafen.world():position(x, y)) — one name reads it and writes it");
            put(kind + ":show", kind + ":show() is now " + kind + ":visible(true) — a boolean property is a"
                + " property, so the value is the argument rather than the verb's name");
            put(kind + ":hide", kind + ":hide() is now " + kind + ":visible(false) — a boolean property is a"
                + " property, so the value is the argument rather than the verb's name");
        }
        put("sprite:destroy", "sprite:destroy() is now hafen.render():sprite():remove(s) — the collection"
            + " placed it, so the collection ends it");
        put("object:destroy", "object:destroy() is now hafen.render():object():remove(o) — the collection"
            + " placed it, so the collection ends it");
        put("ghost:destroy", "ghost:destroy() is now hafen.ghost():remove(g) — the collection placed it, so the"
            + " collection ends it");
        put("ghost:setRes", "ghost:setRes(res, sdt) is now ghost:res(res, spawnData) — ghost:res() already read"
            + " it, so the pair was one name too many");

        // ---- the six entity collections: the sections move, and four verbs with them ---------------------
        put("kin:setGroup", "kin:setGroup(g) is now kin:group(g) — kin:group() already read it, so the pair"
            + " was one name too many");
        put("kin:endkin", "kin:endkin() is now kin:endKin()");
        put("slot:set", "slot:set(res) is now slot:res(name) — slot:res() already read it, so the pair was one"
            + " name too many");
        put("pagina:isnew", "pagina:isnew() is now pagina:isNew()");

        // ---- hafen.speed: the get/set pair collapses onto one name whose arity is the verb ----------------
        put("hafen.speed.get", "hafen.speed.get() is now hafen.speed():current()");
        put("hafen.speed.set", "hafen.speed.set(n) is now hafen.speed():current(n) — one name reads the speed"
            + " and writes it, and the write still needs the 'actions' permission");
        section("speed", "max", "name");

        // ---- hafen.store: the ONE section whose access pattern changed, not just its spelling -------------
        // A declared saved variable was a FIELD (hafen.store.cfg), so the per-owner half of this refusal is
        // built from the manifest in StoreApi.index — this row is only the verb the section always had.
        put("hafen.store.flush", "hafen.store.flush() is now hafen.store():flush(), and a saved variable is"
            + " hafen.store():get(\"<name>\") — the same live table, so writing into it still persists");

        // ---- hafen.client: the API's one colon-on-the-namespace becomes a section like every other --------
        put("hafen.client.options", "hafen.client:options() is now hafen.client():options()");
        put("hafen.client.profiling", "hafen.client:profiling() is now hafen.client():profiling()");

        // ---- the keybinding registry: the LAST get/set pair in the whole API ------------------------------
        put("keybindings:get", "kb:get(name) is now kb:key(name) — one name reads a binding's key and writes"
            + " it, like every other property in the API");
        put("keybindings:set", "kb:set(name, key) is now kb:key(name, key) — one name reads a binding's key"
            + " and writes it, like every other property in the API");

        // ---- the character sheet: four flat readers become four collections, and one of them a sub-list ----
        put("hafen.char.attr", "hafen.char.attr(name) is now hafen.char():attr():get(name), and what it"
            + " hands back is an Attr object: a:base() and a:composite() are the two numbers");
        put("hafen.char.attrs", "hafen.char.attrs() is now hafen.char():attr():list() — a plain array of"
            + " Attr objects rather than a table keyed by name, and a:name() says which one it is");
        put("hafen.char.skills", "hafen.char.skills() is now hafen.char():skill():list()");
        put("hafen.char.skill", "hafen.char.skill(name) is now hafen.char():skill():find(name), and it"
            + " hands back the Skill rather than a boolean — still truthy, so `if ... then` is unchanged");
        put("hafen.char.skillsAvailable", "hafen.char.skillsAvailable() is now"
            + " hafen.char():skill():available() — the buyable skills are a verb on the skill collection,"
            + " and each one carries s:cost()");
        put("hafen.char.credos", "hafen.char.credos() is now hafen.char():credo():list() — acquired and"
            + " available in one collection with cr:acquired() saying which, hafen.char():credo():pursuing()"
            + " for the one being pursued, and hafen.char():credo():cost() for the price of beginning one");
        put("hafen.char.experiences", "hafen.char.experiences() is now hafen.char():experience():list()");
        section("char", "lp", "weight", "food");

        // ---- the study window: the slots become a collection, the totals stay one read ------------------
        put("hafen.study.slots", "hafen.study.slots() is now hafen.study():slot():list()");
        section("study", "summary");

        // ---- the party: the section object IS the roster, and a member finally resolves its gob ---------
        put("hafen.party.members", "hafen.party.members() is now hafen.party():list(), and every member"
            + " hands back a live gob with member:gob()");
        put("hafen.party.member", "hafen.party.member(id) is now hafen.party():get(gobId)");
        put("hafen.party.leader", "hafen.party.leader() is now hafen.party():leader()");

        // ---- combat: three projections of the schools tab, plus who you are fighting ---------------------
        put("hafen.fight.maneuvers", "hafen.fight.maneuvers(filter) is now"
            + " hafen.fight():maneuver():list(filter)");
        section("fight", "deck", "summary");

        // ---- the HUD overlay: a two-line handle table became a builder, so it ends the way the other two do --
        put("uioverlay:remove", "hafen.ui():overlay() hands back something you created and hold, so it ends with"
            + " ov:destroy() — :remove() is the collection verb, and a HUD painter is in no collection");
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

    /**
     * As {@link #methodIndex}, for an entity whose vocabulary is <b>closed</b>: an unknown verb <b>throws</b>
     * naming what does exist, instead of reading {@code nil}. That is D-072 one shape along — the style
     * properties became verbs, and a misspelt property has no future meaning to wait for, so answering it with
     * silence (and a "attempt to call a nil value" one character later) is the worst available answer. Used
     * where the verb set is the whole of a value's grammar rather than a growing surface a feature probe might
     * ask about: {@link LuaRule} and {@link LuaSheet}, as {@link Section} and {@link LuaCollection} already do.
     */
    static LuaValue closedIndex(final String entity, final LuaTable methods, final String hint) {
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
                throw new LuaError(entity + " has no verb '" + key.tojstring() + "' — " + hint);
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
