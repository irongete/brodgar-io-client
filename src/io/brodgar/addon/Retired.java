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
 * <p><b>And a third kind, which is not a name at all</b> (041): a retired <i>event key</i> is a string
 * ARGUMENT ({@code hafen.event():on("OnLoad", fn)}), so no field read can carry the refusal and no
 * {@code __index} can be hung off it. Those live in their own table ({@link #eventKey}) and the emitter
 * consults it at the door, before it decides whether the key is one it answers.
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

    /**
     * Retired <b>event keys</b> (spec {@code 041-unified-events}), keyed {@code "<emitter>|<key>"} — the third
     * kind of retired spelling and the one that is not a field read at all: {@code "OnLoad"} is an ARGUMENT,
     * so nothing can hang off reading it and the refusal has to happen where the key is accepted. The emitter
     * checks this table before it checks its own vocabulary, so a moved key says what it is now instead of
     * falling into the generic "unknown event" refusal.
     */
    private static final Map<String, String> KEYS = new HashMap<String, String>();

    static {
        // ---- the bus: the lifecycle keys drop the On prefix (:on already says it). Every other key is ------
        // ---- unchanged, so it carries no row here -- PascalCase was already the bus's own spelling.
        eventKey("hafen.event()", "OnLoad", "Load");
        eventKey("hafen.event()", "OnUpdate", "Update");
        eventKey("hafen.event()", "OnDisable", "Disable");

        // ---- 074.3: an addon does not enter the world -- a SESSION does, and it says which. The addon --
        // ---- layer outlives a character switch now, so "you entered the world" had no subject left: one
        // ---- addon, many logins, and the moment belongs to one of them. Two spellings reach it -- the
        // ---- key itself, and the On-prefixed one 041 retired into it -- and both name the replacement.
        String why = "an addon no longer enters the world, a SESSION does: hafen.event()"
            + ":on(\"SessionEnteredWorld\", fn) fires once per session, and hands your handler THAT"
            + " session, while your addon is loaded once for the client";
        eventKeyWhy("hafen.event()", "EnterWorld", why);
        eventKeyWhy("hafen.event()", "OnEnterWorld", why);
    }

    /** Register one retired event key: {@code emitter:on(old, fn)} is now {@code emitter:on(now, fn)}. */
    private static void eventKey(String emitter, String old, String now) {
        KEYS.put(emitter + "|" + old, emitter + ":on(key, fn): '" + old + "' is now '" + now
            + "' — :on already says \"on\"");
    }

    /**
     * The same, for a key whose replacement is not a rename but a <b>different question</b>: the reason has
     * to carry what changed, because the caller's own handler body is what needs rewriting and not just the
     * string it is registered under.
     */
    private static void eventKeyWhy(String emitter, String old, String why) {
        KEYS.put(emitter + "|" + old, emitter + ":on(key, fn): '" + old + "' is retired — " + why);
    }

    /**
     * The message for a retired event key on {@code emitter} ({@code "hafen.event()"}), or {@code null} when
     * that spelling was never one. Consulted by the emitter's {@code :on} before its own key set.
     */
    static String eventKey(String emitter, String key) {
        return KEYS.get(emitter + "|" + key);
    }

    static {
        // ---- sections whose NAME changed (§2.3: the three surviving plurals go singular) ----------------
        put("hafen.events", "hafen.events is now hafen.event() — subscribe with hafen.event():on(name, fn)");
        put("hafen.quests", "hafen.quests is now session:quest(), which IS the collection over both tabs:"
            + " hafen.quests.list(f) is s:quest():list(f), hafen.quests.selected() is s:quest():selected(),"
            + " and s:quest():get(id) is one quest by its id. Each member is a"
            + " Quest object: q:id() :title() :res() :status() :modified() :selected() :conditions()"
            + " :exists() :info()");
        put("hafen.wounds", "hafen.wounds is now session:wound(), which IS the collection:"
            + " hafen.wounds.list(f) is s:wound():list(f) and hafen.wounds.has(needle) is"
            + " s:wound():find(needle), which hands back the Wound rather than a boolean — still truthy."
            + " Each member is a Wound object: w:id() :name() :res() :severity() :parent() :level()"
            + " :exists() :info()");

        // ---- hafen.gob is DELETED into the live world (D-066): a gob lives IN the world ------------------
        put("hafen.gob", "hafen.gob(id) is now session:world():gob():get(id) — still never nil, and"
            + " gob:exists() is still the liveness test");

        // ---- the eight verb-only sections: every dotted verb is now a colon call on the section ---------
        section("time", "clock", "dayFraction", "isNight", "season", "moon", "yearFraction");
        section("slash", "register");
        section("json", "parse", "encode");
        section("timer", "after", "every");

        // ---- 041.5: hafen.hook() is DELETED as a whole -- input (041.3) and the two message streams (041.2)
        // ---- had already left it for widgets/the bus, and grab was its last remaining verb, so a section with
        // ---- nothing left in it is not kept around as an empty shell. hafen.hook is a SECTION name, so this is
        // ---- one row on the hafen table's own __index (hafenIndex()), not a per-verb row on a callable table
        // ---- that no longer exists -- reading hafen.hook (bare, or on the way to hafen.hook():anything) throws
        // ---- here before any dotted/colon sub-spelling is ever reached.
        put("hafen.hook", "hafen.hook is gone — the drag capture is now hafen.ui():mouse():grab(), slash"
            + " commands are hafen.slash(), and hotkeys are hafen.client():options():keybindings()");

        // http keeps its verb names but loses its options table, so the message says both halves.
        put("hafen.http.get", "hafen.http.get(url, opts, cb) is now hafen.http():get(url, cb) — opts.headers"
            + " and opts.timeout are setters on the request it hands back: req:header(name, value),"
            + " req:timeout(ms)");
        put("hafen.http.post", "hafen.http.post(url, body, opts, cb) is now hafen.http():post(url, body, cb)"
            + " — opts.headers and opts.timeout are setters on the request it hands back:"
            + " req:header(name, value), req:timeout(ms)");

        // ---- 076.3: hafen.world and hafen.player are GONE onto the session. Sixteen namespaces name one --
        // ---- character's state and every one of them was spelled as though the client had one login; these
        // ---- two are the first onto the address. Each is a SECTION name, so one row on the hafen table's own
        // ---- __index (hafenIndex()) answers every spelling at once -- the bare read, the call, and any
        // ---- dotted or colon verb after it -- because reading `hafen.world` throws before the call happens.
        // ---- The per-verb rows below them are the inventory: unreachable in practice, and the guarantee that
        // ---- no verb moved without a row naming where it went.
        String addr = " — hafen.session():current() is the character on screen and"
            + " hafen.session():get(user) is any other, so the read says WHICH character it is about";
        put("hafen.world", "hafen.world() is now session:world()" + addr + ". A world is one character's view"
            + " of it: two characters standing apart see different objects, out of their own eyes.");
        put("hafen.player", "hafen.player() is now session:player()" + addr + ".");
        sectionObj("world", "gob", "grid", "position", "tile", "height", "tileToWorld", "tileToGrid",
                   "screenToWorld", "snapPlace", "snapAngle", "place", "select");
        sectionObj("player", "gob", "move", "hand", "worldToScreen");

        // ---- 077.1: the character sheet's six READ-ONLY sections follow them. Each names one character's ---
        // ---- own state and nothing else, so each is a Session verb: one row per section on the hafen
        // ---- table's own __index answers every spelling at once, and the per-verb rows below are the
        // ---- inventory -- unreachable in practice, and the guarantee that no verb moved without a row.
        put("hafen.char", "hafen.char() is now session:char()" + addr + ". A sheet is one character's:"
            + " its attributes, its learning points, what it is carrying and what it has eaten.");
        put("hafen.meter", "hafen.meter() is now session:meter()" + addr + ". Two characters have two"
            + " HUD meter slots, and a health bar read off the wrong one is the wrong body's.");
        put("hafen.buff", "hafen.buff() is now session:buff()" + addr + ".");
        put("hafen.study", "hafen.study() is now session:study()" + addr + ".");
        put("hafen.quest", "hafen.quest() is now session:quest()" + addr + ". A quest id counts inside one"
            + " character's own log.");
        put("hafen.wound", "hafen.wound() is now session:wound()" + addr + ". A wound is on one body.");
        sectionObj("char", "attr", "skill", "credo", "experience", "food", "lp", "weight");
        sectionObj("meter", "list", "count", "find");
        sectionObj("buff", "list", "count", "find");
        sectionObj("study", "slot", "summary");
        sectionObj("quest", "list", "count", "find", "get", "selected");
        sectionObj("wound", "list", "count", "find", "get");

        // ---- 077.2: the two ROSTERS, and with them the first PROTECTED verbs to be addressed. Each keeps --
        // ---- the one key it has: a key names the ACTION and not the target, and the player could have
        // ---- tabbed to that character and performed it. So what moves is the door, not the grant.
        put("hafen.kin", "hafen.kin() is now session:kin()" + addr + ". A roster is one character's, and so"
            + " are the buddy ids in it: id 7 on two characters is two different people.");
        put("hafen.party", "hafen.party() is now session:party()" + addr + ". Two of your characters in one"
            + " party read two rosters, each with the positions the server sent that login.");
        sectionObj("kin", "list", "count", "find", "get", "add");
        sectionObj("party", "list", "count", "find", "get", "leader");

        // ---- ...and the character a login is playing is the SESSION's read, not the Player's: one fact with
        // ---- two spellings whose only difference was which door you came through is the dual style §2 cuts.
        put("session:player():name", "session:player():name() is now s:character(), on the Session itself: a"
            + " Session names the ACCOUNT and answers what that login is playing, so"
            + " hafen.session():current():character() is the character on screen. Your own character's gob is"
            + " still s:player():gob().");
        put("hafen.player():name", "hafen.player():name() is now hafen.session():current():character()");

        // ---- hafen.world: the gob verbs fold into one read-only collection, and every spatial verb -------
        // ---- takes a Position instead of a pair of numbers (§2.7).
        put("hafen.world.gobs", "hafen.world.gobs(filter) is now session:world():gob():list(filter)");
        put("hafen.world.count", "hafen.world.count(filter) is now session:world():gob():count(filter)");
        put("hafen.world.nearest", "hafen.world.nearest(filter) is now session:world():gob():nearest(filter)");
        put("hafen.world.within", "hafen.world.within(r, filter) is now session:world():gob():within(r, filter)");
        put("hafen.world.tile", "hafen.world.tile(x, y) is now session:world():tile(p), where p is a Position"
            + " (gob:position(), or session:world():position(x, y))");
        put("hafen.world.height", "hafen.world.height(x, y) is now session:world():height(p), where p is a"
            + " Position (gob:position(), or session:world():position(x, y))");
        put("hafen.world.grid", "hafen.world.grid(x, y) is now session:world():grid():at(p), where p is a"
            + " Position (gob:position(), or session:world():position(x, y))");
        put("hafen.world.gridPos", "hafen.world.gridPos(x, y) is gone: a Position IS the anchor."
            + " session:world():position(x, y) builds one and p:info() is the {gridId, x, y} form —"
            + " and hafen.store keeps a Position itself, so there is nothing to convert");
        put("hafen.world.fromGridPos", "hafen.world.fromGridPos(saved) is now session:world():position(saved)"
            + " — and a Position read back out of hafen.store is already one, so there is nothing to convert");
        put("hafen.world.worldToTile", "hafen.world.worldToTile(x, y) is now p:tileCoord(), on the Position"
            + " itself");
        put("hafen.world.tileToWorld", "hafen.world.tileToWorld(tx, ty) is now"
            + " session:world():tileToWorld(tx, ty)");
        put("hafen.world.tileToGrid", "hafen.world.tileToGrid(tx, ty) is now session:world():tileToGrid(tx, ty)");
        put("hafen.world.screenToWorld", "hafen.world.screenToWorld(sx, sy, fn) is now"
            + " session:world():screenToWorld(sx, sy, fn), and fn receives a Position");
        put("hafen.world.snapPlace", "hafen.world.snapPlace(x, y, fine) is now"
            + " session:world():snapPlace(p, fine), and it hands back a Position");
        put("hafen.world.snapAngle", "hafen.world.snapAngle(a, fine) is now session:world():snapAngle(a, fine)");
        put("hafen.world.placeGrid", "hafen.world.placeGrid() is gone — it read the same setting as"
            + " hafen.client():options():interface():posGran(), which also writes it");
        put("hafen.world.placeAngle", "hafen.world.placeAngle() is gone — it read the same setting as"
            + " hafen.client():options():interface():angGran(), which also writes it (in DEGREES per step)");

        // ---- 048: hafen.act() is DISSOLVED -- a verb lives with WHAT IT CHANGES, not with what it costs -------
        // ---- (D-187 generalised), so the one section grouped by its PERMISSION emptied one task at a time and
        // ---- 048.7 removed the section itself. hafen.act is a SECTION name, so this row hangs off the hafen
        // ---- table's own __index (hafenIndex()) and is what reading `hafen.act` at all -- bare, or on the way to
        // ---- any sub-spelling -- throws, instead of the "attempt to call a nil value" a plain deletion leaves.
        // ---- It NAMES ALL TEN replacements, because it is the row every one of them is now reached through.
        put("hafen.act", "hafen.act() is gone: every verb moved to what it changes — session:player():move(p) walks"
            + " and session:player():hand():use(target, mods) applies what you are holding (nil cursor = nil hand),"
            + " gob:click(button, mods) clicks an object, item:use(mods) / :take() / :drop(n) / :transfer(n) act"
            + " on an item, session:world():place(p, angle, button, mods) / :select(p1, p2, mods) act on the world,"
            + " hafen.menugrid():get(name):use() fires a menu action and widget:send(msg, ...) is the escape"
            + " hatch. hafen.act():flower(label) is hafen.flowermenu():select(label|n), which raises instead of"
            + " answering false and takes a ring position too; hafen.act():enabled() is gone — a running addon"
            + " is granted what it declared, and that is a fact its own manifest.json already states."
            + " The model is unchanged, its granularity is not: every protected verb has its own key"
            + " (\"permissions\": [\"player.move\", \"item.*\"]), per addon, D-027/D-028");
        // ---- ...and every verb keeps its OWN row, under BOTH spellings: the pre-039 dotted one and the colon
        // ---- call a shipped addon actually wrote (D-216). The section row above shadows them all now -- reading
        // ---- `hafen.act` throws before any verb name is looked at -- but Retired is pure data generated from
        // ---- the before/after inventory, and a spelling that moved with no row is a porting error nobody is
        // ---- told about. The rows are the inventory; which __index happens to fire first is not.
        act("moveTo", "hafen.act():moveTo(p) is now session:player():move(p) — the verb lives on the character"
            + " it moves");
        act("clickGob", "hafen.act():clickGob(gob, button, mods) is now gob:click(button, mods) — the verb"
            + " lives on the object it clicks");
        act("useItemOn", "hafen.act():useItemOn(p, mods) is now session:player():hand():use(p, mods) — the"
            + " gesture belongs to what is ON THE CURSOR, and session:player():hand() is nil when nothing is,"
            + " so it can no longer be sent blind. The target is an Item, a Position or a Gob:"
            + " session:player():hand():use(gob) applies the held item to that object, which this verb could"
            + " not do");
        act("item", "hafen.act():item(item, verb, n) is gone: the verbs are on the item — item:use(mods)"
            + " (was \"iact\"), item:take(), item:drop(n), item:transfer(n), and"
            + " session:player():hand():use(item) (was \"itemact\"). A verb string was never a vocabulary,"
            + " and only \"iact\" ever carried modifiers — for take/drop/transfer the modifier keys select"
            + " the COUNT, which n states directly");
        act("place", "hafen.act():place(p, angle, button, mods) is now"
            + " session:world():place(p, angle, button, mods), beside the session:world():snapPlace(p) that"
            + " prepares its Position and the session:world():snapAngle(a) that prepares its angle — which is"
            + " still in RADIANS");
        act("select", "hafen.act():select(p1, p2, mods) is now session:world():select(p1, p2, mods) — p1 and p2"
            + " are still Positions, and the selection is still the tile rectangle they span");
        act("menu", "hafen.act():menu(path...) is gone: a menu action is invoked through the entry itself —"
            + " hafen.menugrid():get(\"Dig\"):use(), or get(\"paginae/act/dig\"):use() by resource name. There"
            + " is no path-based door: the menu grid addresses the entries it HOLDS, and pag:use() is now"
            + " protected in its own right, by the \"menugrid.use\" permission");
        act("raw", "hafen.act():raw(target, msg, ...) is now widget:send(msg, ...) — the RECEIVER is the"
            + " target, so the target vocabulary is gone rather than rehoused: a numeric widget id is the"
            + " widget it named (hafen.ui():node(id)), \"mapview\" is hafen.ui():find(\"@MapView\"),"
            + " \"gameui\" is hafen.ui():find(\"@GameUI\") and \"root\" is hafen.ui():root(). Bound widgets"
            + " only, exactly as before — widget:id() is nil on one your addon built — and the arguments"
            + " marshal unchanged");
        act("flower", "hafen.act():flower(label) is now hafen.flowermenu():select(label|n) — the radial menu"
            + " owns its own petal selection (047), and that door is strictly better: it RAISES naming what is"
            + " open where this answered a bare false, it takes the petal's 1-based ring position as well as"
            + " its caption, and it has hafen.flowermenu():cancel() beside it. Pick from a FlowerMenuOpened"
            + " handler rather than a guessed timer — hafen.flowermenu():list() is the ring");
        act("enabled", "hafen.act():enabled() is gone: it answered whether THIS addon declared the write"
            + " permission, which is a fact about your own manifest.json rather than anything the client"
            + " decides (D-028 removed the global switch it was built to report). A protected verb is granted"
            + " to an addon that declared that verb's own key (or the group covering it); if yours did not,"
            + " the verb says so by name, and names the key, when you call it");

        // ---- 040.7: a text entry's content is WRITTEN through :value(s), the one door -- entry:text(s) would --
        // ---- be a second way to write the same property, which the area's one-door rule does not allow. The
        // ---- READ half is untouched: widget:text() keeps answering best-effort on an entry exactly as it
        // ---- always has on every other text-bearing widget (docs/addons/api/ui/widget.md), since a tree-
        // ---- walking introspector (widgetstack) depends on that read never throwing on ANY widget it finds.
        put("entry:text", "entry:text(s) is retired — a text entry's content is written through :value(s), the"
            + " one door. widget:text() still READS it, best-effort, like any text-bearing widget; entry:text(s)"
            + " would have been a second way to WRITE the same property, which the area's one-door rule does"
            + " not allow.");

        // ---- entity methods (keyed "<entity>:<verb>", hung off that entity's own metatable) -------------
        put("gob:pos", "gob:pos() is now gob:position(), and it hands back a Position rather than a"
            + " {x, y} table: p:x()/p:y() are the components, p:offset(dx, dy) moves, p:info() saves");
        put("gob:isplayer", "gob:isplayer() is now gob:isPlayer()");
        put("gob:overlays", "gob:overlays() is now gob:overlay():list() — gob:overlay() is the collection of"
            + " everything attached to the gob, and the verb says how many");
        put("overlay:clickable", "overlay:clickable(b) does not exist — the thing under an overlay is the GOB,"
            + " and a click on a gob is the client's own (gob:click)");
        put("overlay:onClick", "overlay:onClick(fn) does not exist — the thing under an overlay is the GOB, and"
            + " a click on a gob is the client's own (gob:click)");
        put("overlay:move", "overlay:move(x, y) does not exist — an overlay's position IS its gob's, and what"
            + " you set is where it sits relative to the gob, in SCREEN PIXELS: ov:offset(x, y)");

        // ---- 043.3: the three WORLD kinds leave gob:overlay(), and the verb set that served only them goes -----
        // ---- with them. What they built was never an engine overlay -- it was a client gob of its own standing
        // ---- in the scene -- so it is created, listed and ended in hafen.vr(), and gob:overlay() keeps the one
        // ---- identity it always had a right to: what is DRAWN at this gob. An anchored vr entity is still
        // ---- listed here, read-only, so "what is at this gob?" keeps one complete answer.
        for(String[] r : new String[][] { { "image", "sprite", "asset" }, { "model", "object", "asset" },
                                          { "ghost", "ghost", "res" } }) {
            put("overlay:" + r[0], "overlay:" + r[0] + "(" + r[2] + ") is now hafen.vr():" + r[1] + "():add("
                + r[2] + ", gob) — it never was an engine overlay, it was a client gob of its own standing in"
                + " the world, so it lives in hafen.vr() with the rest of them and the anchor is the second"
                + " argument. It is still listed by gob:overlay():list(), read-only; you address it through"
                + " hafen.vr():" + r[1] + "()");
        }
        for(String v : new String[] { "scale", "alpha", "tint", "rotate", "billboard", "spawnData" }) {
            put("overlay:" + v, "overlay:" + v + "() belonged to the world kinds, which are now"
                + " hafen.vr():sprite() / :object() / :ghost() — so it is a verb on the handle"
                + " hafen.vr():<kind>():add(what, gob) hands back, beside :position() :offset(x, y, z)"
                + " :visible(b) :clickable(b). An overlay is painted at the gob's projected point and has"
                + " :draw(fn), :text(s), :color(r, g, b) and :offset(x, y) in screen pixels");
        }
        put("overlay:position", "overlay:position() belonged to the world kinds, which are now hafen.vr() —"
            + " an overlay is painted AT its gob, so where it is, is where the gob is: ov:gob():position()."
            + " A thing standing in the world answers its own :position(), on the handle"
            + " hafen.vr():<kind>():add(what, gob) hands back");
        // ---- 043.5: a sprite has a FACING, not a boolean. "fixed" and "screen" are two of the ways a flat thing
        // ---- can meet the viewer, and a third ("camera": a world quad that turns to face them, keeping its world
        // ---- size, perspective and occlusion) is neither the true nor the false of a flag. So the property is
        // ---- named for what it answers and takes the mode by name.
        put("sprite:billboard", "sprite:billboard(b) is now sprite:facing(mode): \"fixed\" is an upright world"
            + " quad (what billboard(false) drew) and \"screen\" is a constant-size blit that squares up to the"
            + " camera (what billboard(true) drew). It reads back the mode string");

        put("overlay:pos", "overlay:pos() is gone: an overlay is painted AT its gob, so where it is, is where"
            + " the gob is — ov:gob():position(), which hands back a Position. A thing standing in the world"
            + " is hafen.vr():sprite() / :object() / :ghost(), and answers its own :position()");

        // ---- hafen.map: five collections, and the two surfaces that sat beside it become two of them ----
        put("hafen.map.segment", "hafen.map.segment() is now hafen.map():segment():current() and"
            + " hafen.map.segment(id) is hafen.map():segment():get(id)");
        put("hafen.map.segments", "hafen.map.segments() is now hafen.map():segment():list()");
        put("hafen.map.grid", "hafen.map.grid(gridId) is now hafen.map():grid():get(gridId), and what it"
            + " hands back is the same Grid object session:world():grid() does");
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
        // 048.2: the cursor LEFT hafen.ui() for the character it belongs to, so this row stopped being a
        // re-spelling and became a move — under both field reads (D-216), the dotted pre-039 one and the
        // colon call every shipped addon actually wrote.
        moved("ui", "hand", "hafen.ui():hand() is now session:player():hand():item() — the cursor became an"
            + " object of its own (a Hand) because it carries a verb no Item can: hand:use(target, mods)"
            + " applies what you are holding to an Item, a Position or a Gob. session:player():hand() is nil"
            + " while the cursor is empty, which is the guard the old read could not give you");
        put("hafen.ui.on", "hafen.ui.on(selector, event, fn) is now hafen.ui():on(selector, event, fn)");

        // ---- the three UI builders: no config table survives, so each key is a setter on what you get back --
        put("hafen.ui.window", "hafen.ui.window{…} is now hafen.ui():window() plus chained setters:"
            + " :title(s) :parent(w) :position(x, y) :size(w, h) :font(h) and the notifications"
            + " :on(\"MouseDown\"/\"MouseUp\"/\"MouseMove\"/\"Wheel\", fn) :onDraw(fn) :onTick(fn) :onDrop(fn)"
            + " :onClose(fn). Each has a matching bare read, and `pos` is spelt `position`");
        put("hafen.ui.widget", "hafen.ui.widget{…} is now hafen.ui():widget() plus chained setters:"
            + " :parent(w) :position(x, y) :size(w, h) :font(h) and the notifications"
            + " :on(\"MouseDown\"/\"MouseUp\"/\"MouseMove\"/\"Wheel\", fn) :onDraw(fn) :onTick(fn) :onDrop(fn)."
            + " A bare widget has no caption, so :title(s) is the window builder's");
        put("hafen.ui.overlay", "hafen.ui.overlay(fn) is now hafen.ui():overlay():onDraw(fn), and the overlay"
            + " it hands back ends with :destroy() rather than :remove()");

        // ---- the stylesheet: a Sheet of Rules, so a selector NAMES a rule and its properties are setters ----
        put("hafen.ui.skin", "hafen.ui.skin{…} is now hafen.ui():sheet(): s:rule(selector) hands back the rule"
            + " for that key and its properties are setters (:font(h) :color(r,g,b) :bg{…} :border{…}"
            + " :padding(n) :position(x, y) :anchor{…} :size(w, h)), s:load(t) takes a whole sheet as data, and"
            + " s:install() / s:drop() apply and remove it — hafen.ui.skin(nil) is s:drop()");
        // ---- 065.1: a rule's room around its content is said on all FOUR sides, so the property is named for
        // ---- what it is rather than abbreviated. It is a Rule verb, so the row hangs off LuaRule's own closed
        // ---- __index; Sheet.propsOf consults this same table for the DATA door, where `pad = 4` is a key in a
        // ---- plain table with no metamethod to fire.
        put("rule:pad", "rule:pad(n) is now rule:padding(n) — the same room between a frame and its content,"
            + " said on all four sides: padding(6) is all of them and padding(l, t, r, b) is each. It reads back"
            + " as { l =, t =, r =, b = }, which is a shape the setter takes again");
        // hafen.ui.root is deliberately NOT here, though hafen.ui():root() now exists: 030.1 cut it, this feature
        // did not move it, and the table's rule is that it carries what THIS grammar renamed. A name nothing here
        // touched goes on reading nil, which is what keeps a feature probe (`if hafen.something then`) honest.

        // ---- the Widget entity: two renames, one hard cut, and the read that needed a noun ---------------
        put("widget:pos", "widget:pos() is now widget:position(), and it still answers in PIXELS within the"
            + " parent — a widget lives on the screen, so this is not a Position and session:player():move refuses it");
        put("widget:rootpos", "widget:rootpos() is now widget:rootPos()");
        put("widget:show", "widget:show() is now widget:visible(true) — a boolean property is a property, so the"
            + " value is the argument rather than the verb's name");
        put("widget:hide", "widget:hide() is now widget:visible(false) — a boolean property is a property, so the"
            + " value is the argument rather than the verb's name");
        put("widget:skin", "widget:skin{…} is now widget:rule(), the same Rule object a sheet's selectors hand"
            + " back: its properties are setters (:font(h) :color(r,g,b) :bg{…} :border{…} :padding(n)),"
            + " widget:rule():info() reads your whole level back, and widget:rule():remove() drops it."
            + " widget:style() still answers what the widget RESOLVES to");

        // ---- 041.3: the four input slots join the one vocabulary every widget answers, ANY widget included ----
        // (native, found by selector — the reach that did not exist before). Reading the handler back is cut
        // like every other N-subscriber verb (§5.4): a subscription is not a property.
        put("widget:onClick", "widget:onClick(fn) is now widget:on(\"MouseDown\", fn) — renamed, not just"
            + " re-spelled: it always bound MouseDownEvent, so \"click\" was never the right name");
        put("widget:onMouseUp", "widget:onMouseUp(fn) is now widget:on(\"MouseUp\", fn)");
        put("widget:onMouseMove", "widget:onMouseMove(fn) is now widget:on(\"MouseMove\", fn)");
        put("widget:onWheel", "widget:onWheel(fn) is now widget:on(\"Wheel\", fn)");

        // ---- 041.4: the other 12 retired verbs, all through the same widget:on(key, fn) door. Reading the -----
        // handler back is cut for every one of them too, for the same reason as the four above.
        put("widget:onPress", "widget:onPress(fn) is now widget:on(\"Pressed\", fn)");
        put("widget:onChange", "widget:onChange(fn) is now widget:on(\"Changed\", fn) — on a slider the ev answers"
            + " :value() :final() instead of two loose arguments");
        put("widget:onSubmit", "widget:onSubmit(fn) is now widget:on(\"Submitted\", fn)");
        put("widget:onSelect", "widget:onSelect(fn) is now widget:on(\"Selected\", fn)");
        put("widget:onCell", "widget:onCell(fn) is now widget:on(\"Cell\", fn), and the ev answers :g() :item()"
            + " :w() :h() instead of four loose arguments");
        put("widget:onDraw", "widget:onDraw(fn) is now widget:on(\"Draw\", fn), and the ev answers :g() :w() :h()"
            + " instead of three loose arguments");
        put("widget:onTick", "widget:onTick(fn) is now widget:on(\"Tick\", fn)");
        put("widget:onDrop", "widget:onDrop(fn) is now widget:on(\"Drop\", fn); consuming the drop is"
            + " ev:preventDefault() now, not a truthy return");
        put("widget:onClose", "widget:onClose(fn) is now widget:on(\"Close\", fn)");
        put("widget:onItemAdded", "widget:onItemAdded(fn) is now widget:on(\"ItemAdded\", fn)");
        put("widget:onItemRemoved", "widget:onItemRemoved(fn) is now widget:on(\"ItemRemoved\", fn)");
        put("widget:onDestroy", "widget:onDestroy(fn) is now widget:on(\"Destroy\", fn) — on ANY widget now, not"
            + " only a container's");

        put("marker:onmap", "marker:onmap() is now marker:onMap(), and it writes too: marker:onMap(true)");

        // ---- 043.1: the two world-entity sections are DELETED into ONE, hafen.vr() — the section for --------
        // ---- client-only things standing in the 3D world. Both are SECTION names, so each is one row on the
        // ---- hafen table's own __index (hafenIndex()): reading hafen.ghost / hafen.render at all — bare, or on
        // ---- the way to any sub-spelling, dotted or colon — throws here before anything else is reached, which
        // ---- is why the per-verb rows those two used to carry are gone rather than kept unreachable.
        put("hafen.ghost", "hafen.ghost is now hafen.vr():ghost() — hafen.vr() is the one section for"
            + " client-only things standing in the 3D world, and the .res props are one of its collections:"
            + " hafen.vr():ghost():add(res, p) places one, :list(filter) reads this addon's, :remove(g) ends"
            + " one. A .res model ON A GAME OBJECT is gob:overlay():add(key):ghost(res)");
        put("hafen.render", "hafen.render is now hafen.vr() — one section for everything client-only you stand"
            + " in the 3D world, named for the place rather than the mechanism: hafen.render():sprite() is"
            + " hafen.vr():sprite() and hafen.render():object() is hafen.vr():object(), each :add(asset, p)"
            + " plus the chained setters :position(p [, a]) :scale(k) :alpha(a) :tint(r, g, b) :clickable(b)"
            + " :onClick(fn). The .res props are hafen.vr():ghost() beside them");

        // ---- Sprite / Object / Ghost: one vocabulary, and every property a read/write pair on one name ------
        for(String kind : new String[] { "sprite", "object", "ghost" }) {
            put(kind + ":pos", kind + ":pos() is now " + kind + ":position(), and it hands back a Position"
                + " rather than an {x, y, a, scale} table: the facing and the size are " + kind + ":rotate()"
                + " and " + kind + ":scale()");
            put(kind + ":move", kind + ":move(x, y [, a]) is now " + kind + ":position(p [, a]), where p is a"
                + " Position (gob:position(), or session:world():position(x, y)) — one name reads it and writes it");
            put(kind + ":show", kind + ":show() is now " + kind + ":visible(true) — a boolean property is a"
                + " property, so the value is the argument rather than the verb's name");
            put(kind + ":hide", kind + ":hide() is now " + kind + ":visible(false) — a boolean property is a"
                + " property, so the value is the argument rather than the verb's name");
        }
        put("sprite:destroy", "sprite:destroy() is now hafen.vr():sprite():remove(s) — the collection"
            + " placed it, so the collection ends it");
        put("object:destroy", "object:destroy() is now hafen.vr():object():remove(o) — the collection"
            + " placed it, so the collection ends it");
        put("ghost:destroy", "ghost:destroy() is now hafen.vr():ghost():remove(g) — the collection placed it, so the"
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

        // ---- hafen.speed: the section IS the collection of speeds, and a speed is an OBJECT (060) ----------
        // The two bounds-and-numbers verbs are gone rather than re-spelled: :max() was a bound every caller
        // turned back into a range by hand, and :name(n) read a property of a member the API can now hand you.
        // Both spellings of each carry the row (D-216) — the pre-039 dotted field and the colon call.
        put("hafen.speed.get", "hafen.speed.get() is now hafen.speed():current(), which hands back a Speed"
            + " OBJECT rather than a number — sp:index() is that number, sp:name() its display name");
        put("hafen.speed.set", "hafen.speed.set(n) is now hafen.speed():set(n), and the write needs the"
            + " 'speed.set' permission (it was 'speed.current'). It takes a Speed, an index 0..3 or a display"
            + " name: hafen.speed():set(hafen.speed():get(\"Run\"))");
        moved("speed", "max", "hafen.speed():max() is gone: hafen.speed():list() IS the speeds you can pick"
            + " right now, so there is no bound left to turn back into a range — everything it hands you is"
            + " something :set accepts. hafen.speed():count() is how many, and sp:available() answers it for"
            + " one speed (hafen.speed():get(3):available() is \"is sprint unlocked?\")");
        moved("speed", "name", "hafen.speed():name(n) is now hafen.speed():get(n):name() — a speed is an"
            + " object, and its display name is a verb on it. hafen.speed():get(\"Run\") addresses one by"
            + " that name too, and the one you are on is hafen.speed():current():name()");

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
        put("hafen.char.attr", "hafen.char.attr(name) is now s:char():attr():get(name), and what it"
            + " hands back is an Attr object: a:base() and a:composite() are the two numbers");
        put("hafen.char.attrs", "hafen.char.attrs() is now s:char():attr():list() — a plain array of"
            + " Attr objects rather than a table keyed by name, and a:name() says which one it is");
        put("hafen.char.skills", "hafen.char.skills() is now s:char():skill():list()");
        put("hafen.char.skill", "hafen.char.skill(name) is now s:char():skill():find(name), and it"
            + " hands back the Skill rather than a boolean — still truthy, so `if ... then` is unchanged");
        put("hafen.char.skillsAvailable", "hafen.char.skillsAvailable() is now"
            + " s:char():skill():available() — the buyable skills are a verb on the skill collection,"
            + " and each one carries s:cost()");
        put("hafen.char.credos", "hafen.char.credos() is now s:char():credo():list() — acquired and"
            + " available in one collection with cr:acquired() saying which, s:char():credo():pursuing()"
            + " for the one being pursued, and s:char():credo():cost() for the price of beginning one");
        put("hafen.char.experiences", "hafen.char.experiences() is now s:char():experience():list()");
        section("char", "lp", "weight", "food");

        // ---- the study window: the slots become a collection, the totals stay one read ------------------
        put("hafen.study.slots", "hafen.study.slots() is now s:study():slot():list()");
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

        // ---- crafting: the recipe is an entity, and the Craft button belongs to the recipe ---------------
        put("hafen.craft.current", "hafen.craft.current() is now hafen.craft():current(), and it hands back"
            + " a Craft object rather than a table: c:name() is the recipe, c:inputs()/:outputs() the slots,"
            + " c:qualityInputs()/:tools() the rest, and c:info() is the old snapshot");
        put("hafen.craft.make", "hafen.craft.make(all) is now hafen.craft():current():make(all) — the button"
            + " belongs to the recipe. hafen.craft():current() is nil while no recipe is open, so test it"
            + " first; it still needs the 'craft.make' permission");

        // ---- the Item entity: the snapshot's two PLACE fields become the two verbs that say which you meant ----
        // The rest of the old table's keys (`res`, `name`, `handle`) are live verbs, so a dotted read of one
        // silently hands back the METHOD rather than throwing — there is no metamethod that can tell `it.name`
        // from `it:name()`. These two can be caught, because nothing answers to their old spelling.
        put("item:pos", "an item's place is two verbs now, because a backpack cell and an equipment slot are"
            + " not one shape: item:cell() is the {x, y} grid cell it sits in, and item:slots() names the"
            + " equipment slots it fills (a worn item can fill more than one)");
        put("item:slot", "item:slots() names the equipment slots this item fills — a list, because one worn"
            + " item can fill several, and the names are the ones the equipment window shows");

        // ---- 064.4: the two reads that answer WHAT THE CLIENT DRAWS. Both old verbs read one field each, and
        // ---- the icon is painted from the item's published tooltip info as well, so each answered nil on the
        // ---- very items that visibly show the thing it named. The replacements fold both sources in the order
        // ---- WItem.draw folds them.
        put("item:num", "item:num() is now item:quantity(), which answers the number the icon SHOWS rather"
            + " than the one field the server happens to have written: a counted item (42 seeds of Hemp) and a"
            + " stack alike, and nil for one showing none. On a stack it equals #item:contents():items(), the"
            + " things it counts");
        // The one retired verb that CANNOT name a single replacement: it was two things at once behind a name
        // belonging to neither, so the message names both and says which is which.
        put("item:wear", "item:wear() is retired and has TWO replacements, because it was two different"
            + " things behind one name. item:progress() is the ARC the client paints over the icon — a"
            + " fraction with no units, spelled 0..1 like every other fraction in the API (the old 0..100"
            + " went with the name). item:durability() is the two ABSOLUTE counts the item's tooltip prints,"
            + " {cur, max}, which is the only pair that says what is left. Neither converts into the other,"
            + " and an item may answer both");

        // ---- the HUD overlay: a two-line handle table became a builder, so it ends the way the other two do --
        put("uioverlay:remove", "hafen.ui():overlay() hands back something you created and hold, so it ends with"
            + " ov:destroy() — :remove() is the collection verb, and a HUD painter is in no collection");
    }

    /**
     * Register one verb that <b>left</b> {@code hafen.act()} (048), under the <b>two</b> spellings a caller could
     * reach it by. They were two different reads with their own {@code __index}: {@code hafen.act.moveTo} is a
     * field on the section's callable table ({@link #sectionIndex}), while {@code hafen.act():moveTo} is a field
     * on the section OBJECT ({@code Section.meta}, keyed with the {@code "():"} spelling) — the one a shipped
     * addon actually wrote, and the one a generic <i>"hafen.act() has no verb 'moveTo'"</i> would otherwise have
     * answered. Both carry the same message naming the verb's new home.
     *
     * <p>Since 048.7 the section itself is retired, so its own row fires first and these are unreachable in
     * practice. They stay because this table is the feature's before/after <b>inventory</b> — the guarantee that
     * no spelling moved without one — and because the section row is the only thing in front of them.
     */
    private static void act(String verb, String message) {
        moved("act", verb, message);
    }

    /** {@link #act} for any section: the dotted field read and the colon call both carry {@code message}. */
    private static void moved(String section, String verb, String message) {
        put("hafen." + section + "." + verb, message);
        put("hafen." + section + "():" + verb, message);
    }

    /**
     * Register the verb inventory of a section that has moved <b>onto the session</b> (076.3): every
     * {@code hafen.<section>():<verb>} a shipped addon could have written, each naming the same replacement.
     * Only the colon spelling, because the dotted one is a pre-039 row this table already carries and
     * overwriting it would lose that half of the inventory — and the section's own row fires in front of both.
     */
    private static void sectionObj(String section, String... verbs) {
        for(String verb : verbs) {
            put("hafen." + section + "():" + verb, "hafen." + section + "():" + verb + "(…) is now s:"
                + section + "():" + verb + "(…), where s is a Session: hafen.session():current() for the"
                + " character on screen, hafen.session():get(user) for any other");
        }
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
