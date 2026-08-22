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
 * <p><b>And a fourth kind, which is not retired at all</b> (078.2): where a namespace <b>splits</b>, half
 * its verbs grow an address and half keep their global spelling, and the mistake goes both ways — a sweep
 * that addresses {@code hafen.ui():window()} writes code that compiles, runs and is wrong. So the verbs
 * that KEPT their spelling carry a row too, in {@link #MISPLACED}: it fires from the side the verb is
 * absent from and says which half the verb is in. Those spellings are <b>live</b> and must be kept out of
 * {@link #NAMES}, which is the list a documentation sweep derives to check that no retired name is written
 * on a page.
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

    /**
     * <b>Live spellings reached through the wrong door</b> (078.2) — keyed exactly as {@link #NAMES} is, and
     * consulted after it. A verb of a namespace that SPLIT is missing from one of its two section objects,
     * and reading it there would otherwise fail with the generic "has no verb", which says the call is wrong
     * without saying which half the verb is in. <b>Nothing here is retired</b>: every name in this map is a
     * spelling a page is supposed to write, which is why it is not in {@link #NAMES}.
     */
    private static final Map<String, String> MISPLACED = new HashMap<String, String>();

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
        // 085.7: neither of these re-lists the member's verbs any more. A row's job is the ADDRESS — where the
        // spelling went — and the member's vocabulary is one refusal away on the object itself, where
        // closedIndex writes it once. The copy here had already drifted: it named a Wound's verbs without the
        // :label() 085.4 added, so a reader porting an addon was told the truth about the address and a stale
        // half-truth about the type. Patching a copy leaves the copy; deleting it leaves the one place.
        put("hafen.quests", "hafen.quests is now session:quest(), which IS the collection over both tabs:"
            + " hafen.quests.list(f) is s:quest():list(f), hafen.quests.selected() is s:quest():selected(),"
            + " and s:quest():get(id) is one quest by its id — each member is a Quest object, and a name it"
            + " does not answer names what it does");
        put("hafen.wounds", "hafen.wounds is now session:wound(), which IS the collection:"
            + " hafen.wounds.list(f) is s:wound():list(f) and hafen.wounds.has(needle) is"
            + " s:wound():find(needle), which hands back the Wound rather than a boolean — still truthy."
            + " Each member is a Wound object, and a name it does not answer names what it does");

        // ---- hafen.gob is DELETED into the live world (D-066): a gob lives IN the world ------------------
        put("hafen.gob", "hafen.gob(id) is now session:world():gob():get(id) — still never nil, and"
            + " gob:exists() is still the liveness test");

        // ---- the eight verb-only sections: every dotted verb is now a colon call on the section ---------
        section("time", "clock", "dayFraction", "isNight", "season", "moon", "yearFraction");
        section("json", "parse", "encode");
        section("timer", "after", "every");

        // ---- 086.1: the three registries that did not use the API's ONE notification verb. A slash command,
        // ---- a hotkey and a selector watch are subscriptions, so each hands back a Sub and each ends with
        // ---- sub:off(). hafen.slash():register is a SECTION verb, so its row covers the dotted pre-039
        // ---- spelling too (moved()); the keybindings pair are verbs of an OBJECT, keyed the way a call site
        // ---- spells them. handle:remove() needs no row of its own: the handle is a Sub now, and the closed
        // ---- vocabulary of a Sub already answers "a subscription has no verb 'remove'".
        moved("slash", "register", "hafen.slash():register(name, fn) is now hafen.slash():on(name, fn) — a"
            + " slash command is a subscription like every other :on in the API, and what it hands back is a"
            + " Sub: sub:key() is the command name and sub:off() ends it, where the old handle had :remove()");
        put("keybindings:register", "kb:register(name, fn) is now kb:on(name, fn) — a hotkey is a"
            + " subscription like every other :on in the API. It hands back a Sub rather than the keybindings"
            + " handle, so registrations no longer chain: keep the Sub and end it with sub:off()");
        put("keybindings:unregister", "kb:unregister(name) is now sub:off() on the Sub kb:on(name, fn) handed"
            + " back — one ending for every subscription in the API. The key the user assigned survives it,"
            + " exactly as it survived unregister");

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

        // ---- 077.3: the four that ACT, and every key among them. Two of them report a WINDOW THE GAME PUT --
        // ---- UP rather than a fact about a body -- a background session keeps its GameUI, so its recipe
        // ---- window is open and its action menu answers. Every key is unchanged and now addressable.
        put("hafen.actionbar", "hafen.actionbar() is now session:actionbar()" + addr + ". A slot index names"
            + " one character's bar: slot 11 on two characters is two different buttons.");
        put("hafen.speed", "hafen.speed() is now session:speed()" + addr + ". A speed selector is one"
            + " character's, and so is which speed it has unlocked.");
        put("hafen.craft", "hafen.craft() is now session:craft()" + addr + ". A recipe window is open on the"
            + " character that opened it, drawn or not.");
        put("hafen.menugrid", "hafen.menugrid() is now session:menugrid()" + addr + ". A catalogue is one"
            + " character's: two characters know different actions, through two grids.");
        sectionObj("actionbar", "list", "count", "find", "get");
        sectionObj("speed", "list", "count", "find", "get", "current", "set");
        sectionObj("craft", "current");
        sectionObj("menugrid", "list", "count", "find", "get", "roots", "add", "remove");

        // ---- 077.4: and the last two, which close the family. `flowermenu` is the one that had to be -----
        // ---- argued rather than repeated: a right-click is a mouse gesture and there is one mouse, so the
        // ---- section looks like the screen's -- but the section IS THE OPEN MENU, and a menu is a widget in
        // ---- one session's tree. So a ring left up on a character the player tabbed away from is still
        // ---- open, still readable and still selectable, and both its keys stay the one key each.
        put("hafen.fight", "hafen.fight() is now session:fight()" + addr + ". A combat school is configured"
            + " on one character and a fight is fought by one body: the deck, the budget and the opponent"
            + " are all that character's own.");
        put("hafen.flowermenu", "hafen.flowermenu() is now session:flowermenu()" + addr + ". The section IS"
            + " the open menu, and a menu is a widget in one session's tree rather than the right-click that"
            + " raised it — so one left open on a character you tabbed away from still reads and still picks.");
        sectionObj("fight", "maneuver", "deck", "summary", "target");
        sectionObj("flowermenu", "list", "count", "gob", "select", "cancel");

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
            + " session:world():click(gob, button, mods) clicks an object, item:use(mods) / :take() / :drop(n)"
            + " / :transfer(n) act on an item, session:world():place(p, angle, button, mods) /"
            + " :select(p1, p2, mods) act on the world,"
            + " session:menugrid():get(name):use() fires a menu action and widget:send(msg, ...) is the escape"
            + " hatch. hafen.act():flower(label) is session:flowermenu():select(label|n), which raises instead of"
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
        act("clickGob", "hafen.act():clickGob(gob, button, mods) is now"
            + " session:world():click(gob, button, mods) — a click is something a CHARACTER does, so the"
            + " session acts and the object is the target");
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
            + " session:menugrid():get(\"Dig\"):use(), or get(\"paginae/act/dig\"):use() by resource name. There"
            + " is no path-based door: the menu grid addresses the entries it HOLDS, and pag:use() is now"
            + " protected in its own right, by the \"menugrid.use\" permission");
        act("raw", "hafen.act():raw(target, msg, ...) is now widget:send(msg, ...) — the RECEIVER is the"
            + " target, so the target vocabulary is gone rather than rehoused: a numeric widget id is the"
            + " widget it named (s:ui():node(id)), \"mapview\" is s:ui():match(\"@MapView\"),"
            + " \"gameui\" is s:ui():match(\"@GameUI\") and \"root\" is s:ui():root(). Bound widgets"
            + " only, exactly as before — widget:id() is nil on one your addon built — and the arguments"
            + " marshal unchanged");
        act("flower", "hafen.act():flower(label) is now session:flowermenu():select(label|n) — the radial menu"
            + " owns its own petal selection (047), and that door is strictly better: it RAISES naming what is"
            + " open where this answered a bare false, it takes the petal's 1-based ring position as well as"
            + " its caption, and it has session:flowermenu():cancel() beside it. Pick from a FlowerMenuOpened"
            + " handler rather than a guessed timer — session:flowermenu():list() is the ring");
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
        // 079.3: a Gob is the server's OBJECT rather than one character's reading of it, so it has nobody to
        // send a click as -- the one verb on the handle that ACTED moves onto the world of the session that
        // makes the gesture. The permission key is unchanged: a key names the action, and this one still
        // names clicking an object.
        put("gob:click", "gob:click(button, mods) is now s:world():click(gob, button, mods), where s is a"
            + " Session (hafen.session():current() for the character on screen): a Gob names the OBJECT,"
            + " which every character looking at it shares, and a click is something one character does."
            + " The permission key is still \"gob.click\"");
        put("overlay:clickable", "overlay:clickable(b) does not exist — the thing under an overlay is the GOB,"
            + " and a click on a gob is the client's own (session:world():click(gob))");
        put("overlay:onClick", "overlay:onClick(fn) does not exist — the thing under an overlay is the GOB, and"
            + " a click on a gob is the client's own (session:world():click(gob))");
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
                + " :draw(fn), :text(s), :color(c) and :offset(x, y) in screen pixels");
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
            + " m:color(c), m:onMap(true)");
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
        put("grid:overlays", "grid:overlays() is now grid:mask():list() — grid:mask() is the collection"
            + " of the recorded masks on this grid, and the verb says how many");
        put("marker:tc", "marker:tc() is now marker:segmentTile()");
        put("marker:pos", "marker:pos() is now marker:position(), and it hands back a Position rather than an"
            + " {x, y} table — the same Position :anchor() used to have to build");
        put("marker:anchor", "marker:anchor() is gone: marker:position() IS the anchor. A Position is durable"
            + " by construction, so p:info() is the {gridId, x, y} form and hafen.store keeps the Position"
            + " itself — there is nothing to convert");
        put("marker:dist", "marker:dist() is now marker:distance()");

        // ---- hafen.ui: the lookups move onto the section, and the ROOT stops being the call itself -------
        put("hafen.ui.at", "hafen.ui.at(x, y) is now hafen.ui():hit(x, y)");
        put("hafen.ui.mouse", "hafen.ui.mouse() is now hafen.ui():mouse()");
        // 048.2: the cursor LEFT hafen.ui() for the character it belongs to, so this row stopped being a
        // re-spelling and became a move — under both field reads (D-216), the dotted pre-039 one and the
        // colon call every shipped addon actually wrote.
        moved("ui", "hand", "hafen.ui():hand() is now session:player():hand():item() — the cursor became an"
            + " object of its own (a Hand) because it carries a verb no Item can: hand:use(target, mods)"
            + " applies what you are holding to an Item, a Position or a Gob. session:player():hand() is nil"
            + " while the cursor is empty, which is the guard the old read could not give you");

        // ---- 078.2: hafen.ui SPLITS, and this is the one table that has to say which half a verb is in. ----
        // ---- The seven below reach a widget THE GAME PLACED for one character, so each grows an address;
        // ---- everything left on hafen.ui() builds something of YOURS in the layer, restyles the client's
        // ---- surfaces, or asks about the screen. Both directions are answered from here, and each key fires
        // ---- on exactly the side where the verb is absent: a moved verb is missing from the global section,
        // ---- so `hafen.ui():find` reaches this table; a kept verb is missing from the session's, so
        // ---- `s:ui():window` reaches the same row from the other door. A verb present on the section it is
        // ---- called on is found by rawget and never gets here, which is what keeps one row per verb honest.
        String twoTrees = " — your window and the client's window are not the same thing, and they stand in"
            + " two trees";
        uiMoved("match", "match(selector)", "THE widget matching a selector in that character's tree");
        uiMoved("matchAll", "matchAll(selector)", "every widget matching it, in tree order");
        uiMoved("on", "on(selector, event, fn)", "a watch on that character's own tree, which scans it at"
                + " registration and so fires for what that character already has open");
        uiMoved("root", "root()", "the top of that character's whole tree");
        uiMoved("node", "node(id)", "the widget with that server id — an id counts inside one tree, and the"
                + " same number names a different widget on the other character");
        uiMoved("inventory", "inventory()", "that character's own backpack");
        uiMoved("equipment", "equipment()", "what that character is wearing");
        uiKept("window", "builds a window of YOURS, in the addon layer above every session" + twoTrees);
        uiKept("widget", "builds a bare container of YOURS, in the addon layer" + twoTrees);
        uiKept("overlay", "is the collection of the HUD painters YOUR addon installed" + twoTrees);
        uiKept("sheet", "is a declaration of rules owned by your addon, applied live to whatever matches in"
               + " every session at once — a theme is not one character's");
        uiKept("mouse", "is the POINTER, and there is one pointer however many characters are logged in");
        uiKept("hit", "hit-tests a point on the SCREEN, and there is one coordinate space");
        uiKept("tipAt", "asks who would speak for a point on the SCREEN");
        uiKept("scale", "is the device factor the client is running at");
        for(String c : new String[] {"button", "label", "entry", "check", "radio", "slider", "scroll",
                                     "scrollbar", "dropdown", "menu", "listbox", "table", "grid", "image",
                                     "progress", "separator"})
            uiKept(c, "mints a control of YOURS, in the addon layer" + twoTrees);

        // ---- the three UI builders: no config table survives, so each key is a setter on what you get back --
        put("hafen.ui.window", "hafen.ui.window{…} is now hafen.ui():window() plus chained setters:"
            + " :title(s) :parent(w) :position(x, y) :size(w, h) :font(h) and the notifications"
            + " :on(\"MouseDown\"/\"MouseUp\"/\"MouseMove\"/\"Wheel\", fn) :onDraw(fn) :onTick(fn) :onDrop(fn)"
            + " :onClose(fn). Each has a matching bare read, and `pos` is spelt `position`");
        put("hafen.ui.widget", "hafen.ui.widget{…} is now hafen.ui():widget() plus chained setters:"
            + " :parent(w) :position(x, y) :size(w, h) :font(h) and the notifications"
            + " :on(\"MouseDown\"/\"MouseUp\"/\"MouseMove\"/\"Wheel\", fn) :onDraw(fn) :onTick(fn) :onDrop(fn)."
            + " A bare widget has no caption, so :title(s) is the window builder's");
        put("hafen.ui.overlay", "hafen.ui.overlay(fn) is now hafen.ui():overlay():add(key):draw(fn) — the HUD"
            + " painters are a keyed COLLECTION, so hafen.ui():overlay():get(key) reads one back and"
            + " hafen.ui():overlay():remove(key) ends it");

        // ---- the stylesheet: a Sheet of Rules, so a selector NAMES a rule and its properties are setters ----
        put("hafen.ui.skin", "hafen.ui.skin{…} is now hafen.ui():sheet(): s:rule(selector) hands back the rule"
            + " for that key and its properties are setters (:font(h) :color(c) :bg{…} :border{…}"
            + " :padding(n) :position(x, y) :anchor{…} :size(w, h)), s:load(t) takes a whole sheet as data, and"
            + " s:install() / s:release() apply and drop it — hafen.ui.skin(nil) is s:release()");
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
            + " back: its properties are setters (:font(h) :color(c) :bg{…} :border{…} :padding(n)),"
            + " widget:rule():info() reads your whole level back, and widget:rule():release() drops it."
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
            + " plus the chained setters :position(p [, a]) :scale(k) :alpha(a) :tint(c) :clickable(b)"
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

        // ---- 084.6: the FOURTH kind, and the one whose receiver is not the word its collection is spelled
        // ---- with. A standing widget answers to `panel`, because `widget` is the flat entity's and the rows
        // ---- below have to be a different sentence from the ones keyed `widget:` a few lines up: a panel
        // ---- stands in the WORLD, so its place is a Position, where a widget's is pixels within its parent.
        // ---- The two boolean ones say the same thing on both types, so they are stated on both.
        put("panel:pos", "panel:pos() is now panel:position(), and a panel stands in the WORLD: it hands back a"
            + " Position, the place it is standing at, the same kind of value gob:position() answers. The widget"
            + " INSIDE it keeps its own screen coordinates, on panel:widget():position(); the facing and the size"
            + " out here are panel:rotate() and panel:scale()");
        put("panel:move", "panel:move(x, y [, a]) is now panel:position(p [, a]), where p is a Position"
            + " (gob:position(), or session:world():position(x, y)) — one name reads it and writes it. A panel"
            + " standing ON a gob has no place of its own: what it sits at relative to that gob is"
            + " panel:offset(x, y, z)");
        put("panel:show", "panel:show() is now panel:visible(true) — a boolean property is a property, so the"
            + " value is the argument rather than the verb's name");
        put("panel:hide", "panel:hide() is now panel:visible(false) — a boolean property is a property, so the"
            + " value is the argument rather than the verb's name");
        put("panel:destroy", "panel:destroy() is now hafen.vr():widget():remove(x) — the collection stood it,"
            + " so the collection takes it down, and the widget goes back where it was standing from."
            + " Destroying the WIDGET itself is panel:widget():destroy(), which ends the panel with it");

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
        put("hafen.speed.get", "hafen.speed.get() is now session:speed():current(), which hands back a Speed"
            + " OBJECT rather than a number — sp:index() is that number, sp:name() its display name");
        put("hafen.speed.set", "hafen.speed.set(n) is now session:speed():set(n), and the write needs the"
            + " 'speed.set' permission (it was 'speed.current'). It takes a Speed, an index 0..3 or a display"
            + " name: s:speed():set(s:speed():get(\"Run\"))");
        movedObj("speed", CharApi.SP, "max", "s:speed():max() is gone: s:speed():list() IS the speeds that"
            + " character can pick right now, so there is no bound left to turn back into a range —"
            + " everything it hands you is something :set accepts. s:speed():count() is how many, and"
            + " sp:available() answers it for one speed (s:speed():get(3):available() is \"is sprint"
            + " unlocked?\")");
        movedObj("speed", CharApi.SP, "name", "s:speed():name(n) is now s:speed():get(n):name() — a speed is"
            + " an object, and its display name is a verb on it. s:speed():get(\"Run\") addresses one by"
            + " that name too, and the one that character is on is s:speed():current():name()");

        // ---- hafen.store: the ONE section whose access pattern changed, not just its spelling -------------
        // A declared saved variable was a FIELD (hafen.store.cfg), so the per-owner half of this refusal is
        // built from the manifest in StoreApi.index — this row is only the verb the section always had.
        put("hafen.store.flush", "hafen.store.flush() is now hafen.store():flush(), and a saved variable is"
            + " hafen.store():get(\"<name>\") — the same live table, so writing into it still persists");

        // ---- hafen.client: the API's one colon-on-the-namespace becomes a section like every other --------
        put("hafen.client.options", "hafen.client:options() is now hafen.client():options()");
        put("hafen.client.profiling", "hafen.client:profiling() is now hafen.client():profiling()");

        // ---- the keybinding registry: the address-plus-value form, and the one :list() that was a MAP ----
        // ---- (086.3). A binding is an OBJECT now, reached through the collection, so the four spellings
        // ---- that addressed one by name all name the same replacement.
        put("keybindings:get", "kb:get(name) is now kb:binding():get(id):key() — a binding is an object,"
            + " and the one name on it reads its key and writes it");
        put("keybindings:set", "kb:set(name, key) is now kb:binding():get(id):key(key) — a binding is"
            + " an object, and the one name on it reads its key and writes it");
        put("keybindings:key", "kb:key(name) and kb:key(name, key) are now kb:binding():get(id):key() and"
            + " :key(key) — the binding IS the address, so there is no name-plus-value form. It also"
            + " answers :default() and :assigned(), and :key(nil) puts it back on the client's own default,"
            + " which the pair could not do");
        put("keybindings:list", "kb:list() is now kb:binding():list(filter) — a plain ARRAY of Binding"
            + " objects, so ipairs walks it, where the old table was keyed by id and ipairs walked nothing."
            + " b:id() is what the key was and b:key() what the value was");

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
            + " session:fight():maneuver():list(filter)");
        put("hafen.fight.deck", "hafen.fight.deck() is now session:fight():deck()");
        put("hafen.fight.summary", "hafen.fight.summary() is now session:fight():summary()");

        // ---- crafting: the recipe is an entity, and the Craft button belongs to the recipe ---------------
        put("hafen.craft.current", "hafen.craft.current() is now session:craft():current(), and it hands"
            + " back a Craft object rather than a table: c:name() is the recipe, c:inputs()/:outputs() the"
            + " slots, c:qualityInputs()/:tools() the rest, and c:info() is the old snapshot");
        put("hafen.craft.make", "hafen.craft.make(all) is now session:craft():current():make(all) — the"
            + " button belongs to the recipe. s:craft():current() is nil while no recipe is open, so test it"
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

        // ---- 085.3: a SIZE is {w=, h=} wherever it is a size, and a span is named. These are the first rows ----
        // ---- keyed on an anonymous SHAPE rather than a namespace or an entity: nothing here has verbs, so
        // ---- the field a caller wrote is the whole of what can be caught.
        field("size", "x", "a size is {w=, h=}: widget:size(), widget:info().size, rule:size() and a"
            + " stylesheet snapshot all read back .w and .h, the same two keys widget:cell(), img:size()"
            + " and mapImg:size() have always answered. A place and a pixel keep {x=, y=}");
        field("size", "y", "a size is {w=, h=}: widget:size(), widget:info().size, rule:size() and a"
            + " stylesheet snapshot all read back .w and .h, the same two keys widget:cell(), img:size()"
            + " and mapImg:size() have always answered. A place and a pixel keep {x=, y=}");
        field("bounds", "size", "mdl:bounds() names its span extent — .extent.x/.y/.z, the reach of the"
            + " box in world units. .min and .max are the corners, unchanged");

        // ---- three endings take the teardown vocabulary's own word. A LAYER you took is given back with
        // ---- :release(), and a MEMBER of a collection is destroyed by the collection — so the rule a reader
        // ---- can hold is the receiver's kind, not the verb's history.
        put("rule:remove", "rule:remove() is now rule:release(): a rule is a LAYER you took over what the"
            + " client draws — on one widget with widget:rule(), or on a selector through your sheet — and"
            + " ending it gives that layer back. :remove(x) is what a COLLECTION does to a member. The handle"
            + " goes on working either way: setting a property on it says the level again");
        put("sheet:drop", "sheet:drop() is now sheet:release() — the same act rule:release() is, one level up:"
            + " a sheet is the set of layers this addon took over the client's look, and this gives them back."
            + " The document is untouched, so :install() puts it back");
        assetVerb("dispose", "asset:dispose() is now hafen.asset():remove(a): the asset collection exists and"
            + " owns the files this addon loaded, and where a collection exists the destroy verb is on it."
            + " Pass the handle — hafen.asset():remove(hafen.asset():get(\"icon.png\")). A built-in font, a"
            + " :derive()d variant and a file another addon loaded are not members of yours, and each says so"
            + " when you try. A map drawing keeps img:dispose(): grid:image(lvl) hands it back and no"
            + " collection lists it");

        // ---- 088.1: :list() ENUMERATES, everywhere. Two spellings meant something else, and one of them had a
        // ---- side effect on the screen: hafen.ui():list() built a control and attached it, so a reader who had
        // ---- learned four enumerating :list()s wrote the fifth and got an empty listbox drawn. This row hangs
        // ---- off the SECTION's own index, so it fires on the field read -- before any builder runs and before
        // ---- anything is in the tree, which is the whole of what the refusal is worth here.
        moved("ui", "list", "hafen.ui():list() is now hafen.ui():listbox() — it BUILDS a control, and every"
            + " other :list() in the API enumerates a set. It is built bare and configured by chained setters:"
            + " hafen.ui():listbox():rowHeight(20):rows{\"A\", \"B\"}:on(\"Changed\", fn)");
        moved("vr", "list", "hafen.vr():list(filter) is now hafen.vr():entity():list(filter) — the cross-kind"
            + " set is a COLLECTION like the four under it, so hafen.vr():entity() also answers :count(filter),"
            + " :find(filter) and :remove(x) over everything this addon has standing, in the order you stood it");
        moved("vr", "pointer", "hafen.vr():pointer(key, x, y [, a]) is now hafen.vr():click(key, x, y [, a]) —"
            + " a verb rather than a noun, so it does not read as \"where is the pointer\", which is"
            + " hafen.ui():mouse(). It still hands back whether a standing panel took the point");

        // ---- 088.2: the selector language gets its own verb. :find takes a FILTER on a collection -- nil, a
        // ---- substring, a predicate -- and took a SELECTOR here, which is a grammar: s:kin():find("Bo")
        // ---- matched by substring while s:ui():find("Cupboard") parsed as a ROLE that does not exist and
        // ---- answered nil. Two query languages under one verb, and the miss was silent. One key serves both
        // ---- doors (Section.meta looks a section's retired verb up by the section's NAME, and both halves of
        // ---- hafen.ui are named "ui"), so the message says the rename AND which half the verb is in.
        moved("ui", "find", "the ui's :find(selector) is now :match(selector) — the argument is a SELECTOR, a"
            + " grammar, where a collection's :find(filter) is a substring or a predicate, and the verb now"
            + " says which language it speaks. It is a verb of the SESSION's half:"
            + " hafen.session():current():ui():match(selector) is THE widget matching it in that character's"
            + " tree, and hafen.session():get(user) is any other character. The windows your addon BUILDS stay"
            + " on hafen.ui() — they are yours, in the layer above every session");
        moved("ui", "all", "the ui's :all(selector) is now :matchAll(selector) — it runs a SELECTOR, where"
            + " :list() enumerates a collection, so \"give me all of them\" is one word per language. It is a"
            + " verb of the SESSION's half: hafen.session():current():ui():matchAll(selector) is every widget"
            + " matching it in that character's tree, in tree order, and hafen.session():get(user) is any other"
            + " character. The windows your addon BUILDS stay on hafen.ui()");
        put("widget:find", "widget:find(selector) is now widget:match(selector) — the argument is a SELECTOR"
            + " and the verb says so, leaving :find(filter) to mean a collection's substring-or-predicate"
            + " search. It is the same strict answer: nil for no match, the widget for exactly one, and a"
            + " refusal naming widget:matchAll(selector)[i] for two or more");
        put("widget:all", "widget:all(selector) is now widget:matchAll(selector) — every match inside this"
            + " widget's subtree, in tree order, as a 1-based array (empty, never nil)");

        // ---- 088.3: a size, a place and a hit test are three words. widget:cell() was a SIZE wearing the name
        // ---- of a place -- item:cell() one type away is the inventory cell an item sits IN -- and both are
        // ---- two-number tables, so grid:cell(c.w, c.h) fed from an item read nil, nil and was TAKEN. And :at
        // ---- meant both "address a member by a place" (s:world():grid():at(p)) and "search the screen".
        put("widget:cell", "widget:cell(w, h) is now widget:cellSize(w, h) — it is a grid control's cell BOX,"
            + " a size, and it sits beside widget:rowHeight(n). item:cell() keeps the word, because there it is"
            + " a PLACE: the inventory cell an item sits in, read as {x =, y =}");
        moved("ui", "at", "hafen.ui():at(x, y) is now hafen.ui():hit(x, y) — it hit-tests the SCREEN for the"
            + " deepest widget under a point, which is a search, and :at(x) addresses a member by a place"
            + " (s:world():grid():at(p)). It pairs with hafen.ui():mouse():over(), which is the same test at"
            + " the pointer");
        put("widget:at", "widget:at(coord) is now widget:hit(coord) — the deepest widget under a {x =, y =}"
            + " root-coord point inside this widget's subtree. A hit test SEARCHES; :at(x) addresses a member"
            + " by a place, which is what s:world():grid():at(p) means");

        // ---- 088.4: :overlay() named four unrelated things, two of which look alike from Lua --
        // ---- hafen.map():overlay():get("claim") was a display SWITCH and grid:overlay():get("claim") a
        // ---- recorded MASK, both succeeding and answering objects with different verbs. Each is now named for
        // ---- what it is, and the HUD painter takes gob:overlay()'s own shape, so the word means ONE thing:
        // ---- keyed decorations bound to a thing.
        moved("map", "overlay", "hafen.map():overlay() is now hafen.map():display() — these are the client's"
            + " own display SWITCHES (claim, province), a closed set, and toggle:hold(true) asks for one to be"
            + " drawn. The recorded MASKS on one grid are grid:mask(), which is a different set with its own"
            + " tags");
        put("grid:overlay", "grid:overlay() is now grid:mask() — the recorded masks on this grid, which is what"
            + " its members are (mask:covers(c), mask:area()). grid:mask():list() is the census of the tags"
            + " this ground carries and grid:mask():get(tag) is one of them. The client's display switches are"
            + " hafen.map():display(), and they are a different set");
        put("hafen.ui():overlay():onDraw", "hafen.ui():overlay() is a COLLECTION of keyed painters, so the"
            + " painter is added and then says what it draws: hafen.ui():overlay():add(key):draw(fn), with"
            + " fn(g, w, h) every frame. :get(key) reads one back, :remove(key) ends it, and :list() is the"
            + " draw order — the same vocabulary gob:overlay() has");
        put("uioverlay:onDraw", "overlay:onDraw(fn) is now overlay:draw(fn) — the same fn(g, w, h), on the"
            + " member hafen.ui():overlay():add(key) hands back, and the same verb an overlay on a gob takes");
        put("uioverlay:destroy", "overlay:destroy() is now hafen.ui():overlay():remove(key) — the painters are"
            + " a collection now, and where a collection exists the destroy verb is on it. overlay:key() is"
            + " the key, and :remove takes the member itself too");

        // ---- 088.5: three words freed. :close() means "end this" exactly once in this API, on a Session;
        // ---- :path() means a file path; and ev:sender()/ev:target() were the SAME widget under two names,
        // ---- named for a direction the stream you subscribed on already says.
        put("rule:close", "rule:close(...) is now rule:closeButton(...) — it is the BUTTON a window's"
            + " decoration draws in a corner (its art, its hover and pressed faces, and the at/offset that"
            + " place it), not an ending. In a sheet written as data the key is \"closeButton\" too");
        put("pagina:path", "pagina:path() is now pagina:categories() — the categories this entry sits under, as"
            + " an ARRAY of strings, where :path() elsewhere in the API is one string naming a file"
            + " (asset:path()). Empty for a category and for an entry the server invokes by id");
        put("ev:sender", "ev:sender() is now ev:widget() — what sent the action and what is about to receive a"
            + " message are the same widget, and which of the two you are looking at is already said by the"
            + " stream you subscribed on (hafen.event():action() or hafen.event():message())");
        put("ev:target", "ev:target() is now ev:widget() — what is about to receive the message and what sent"
            + " an action are the same widget, and which of the two you are looking at is already said by the"
            + " stream you subscribed on (hafen.event():action() or hafen.event():message())");
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
     * As {@link #moved}, for a section that has since moved onto the Session (077): the row is registered
     * under the <b>live</b> spelling too ({@code session:speed():max}), because a collection mounted as its
     * own section object looks a retired verb up by the name the section is reached under — and that name is
     * no longer the {@code hafen.} one. The two older keys stay as the inventory.
     */
    private static void movedObj(String section, String how, String verb, String message) {
        moved(section, verb, message);
        put(how + ":" + verb, message);
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

    /**
     * One verb of {@code hafen.ui} that moved <b>onto the session</b> (078.2), under both field reads: the
     * dotted pre-039 spelling and the colon call. A sibling of {@link #sectionObj} for a section that SPLITS —
     * the message has to name the whole replacement rather than a section, because the section it was called
     * on is still there and still right for the other half.
     */
    private static void uiMoved(String verb, String call, String what) {
        moved("ui", verb, "hafen.ui():" + call + " is now hafen.session():current():ui():" + call + " — "
            + what + ". s:ui() reads any session you name, drawn or not: hafen.session():current() is the"
            + " character on screen and hafen.session():get(user) is any other. The windows your addon BUILDS"
            + " stay on hafen.ui() — they are yours, in the layer above every session.");
    }

    /**
     * One verb of {@code hafen.ui} that <b>kept</b> its global spelling (078.2). The row is keyed the same way
     * a moved one is, and fires from the other side: the verb is absent from the session's own section, so
     * {@code s:ui():window()} lands here instead of on a bare "has no verb". This is the half a sweep gets
     * wrong — moving too much compiles, runs, and is wrong — so the refusal says which half the verb is in.
     */
    private static void uiKept(String verb, String why) {
        MISPLACED.put("hafen.ui():" + verb, "s:ui():" + verb + "(…) does not exist: hafen.ui():" + verb
            + "(…) " + why
            + ". The session's half of hafen.ui is the widgets THE CLIENT put up — :match, :matchAll, :on,"
            + " :root, :node, :inventory and :equipment.");
    }

    /**
     * Register one verb retired on <b>every loaded-file kind at once</b>: the four entity names a file's
     * handle wears ({@link AssetApi#fileMeta}), which are what a {@link #closedIndex} row is keyed on.
     *
     * <p>{@code "mapimage"} is deliberately absent. A map drawing wears the same asset facet and answers the
     * same shared verbs, but it is the member of no collection and keeps its own {@code :dispose()} — so a row
     * keyed on it would retire a verb that is still there.
     */
    private static void assetVerb(String verb, String message) {
        for(String entity : new String[] {"image", "mesh", "data", "font"})
            put(entity + ":" + verb, message);
    }

    /** Register the plain {@code hafen.<section>.<verb>(…)} → {@code hafen.<section>():<verb>(…)} rows. */
    private static void section(String section, String... verbs) {
        for(String verb : verbs) {
            put("hafen." + section + "." + verb,
                "hafen." + section + "." + verb + "(…) is now hafen." + section + "():" + verb + "(…)");
        }
    }

    /**
     * Register one retired <b>field of an anonymous shape</b> (085.3): {@code size.x} &rarr; the message naming
     * {@code .w}. Keyed {@code "<shape>.<field>"}, which collides with neither a section's dotted spelling nor
     * an entity's colon one — a shape has no verbs, so the field is the whole of what a caller wrote.
     */
    private static void field(String shape, String old, String message) {
        put(shape + "." + old, message);
    }

    private static void put(String name, String message) {
        NAMES.put(name, message);
    }

    /**
     * The message for a spelling this table answers for, or {@code null} when the name was never registered:
     * a retired one first ({@link #NAMES}), then a live one read through the wrong door ({@link #MISPLACED}).
     */
    static String message(String name) {
        String m = NAMES.get(name);
        return (m != null) ? m : MISPLACED.get(name);
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
     * its replacement, and <b>anything else throws too</b>, naming what this type does answer. The retired rows
     * are keyed {@code "<entity>:<verb>"} ({@code "gob:pos"}), which is how they are spelled at the call site
     * that has to be fixed.
     *
     * <p><b>Why an object has no third outcome, where a section has.</b> A section reads a miss as plain
     * {@code nil} so a feature probe ({@code if hafen.something then}) keeps working — but a probe is asked of
     * the {@code hafen} table, never of an object already in hand. On an object a name that is not a verb is a
     * typo, and answering it with silence (and "attempt to call a nil value" one character later) is the worst
     * answer available: it names neither the verb nor the line that wrote it. So the vocabulary is
     * <b>closed</b>, and the refusal carries the {@code hint} written beside the methods table it guards —
     * the key list plus the sentence saying what the type is for, which is what a reader needs and what a
     * list generated at refusal time would drop.
     *
     * <p>The same shape {@link Section} and {@link LuaCollection} already have, one level down: it throws on a
     * FIELD read ({@code x.nosuch}) exactly as on a call ({@code x:nosuch()}), because LuaJ routes both here.
     * {@code __name}/{@code __tostring} are exempt — a metamethod is {@code rawget} off the metatable.
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

    /**
     * The {@code __index} of an anonymous <b>shape</b> table (085.3) — the data-table sibling of
     * {@link #closedIndex}. LuaJ consults {@code __index} only for a key the table does <b>not</b> carry, so a
     * real field costs nothing and every other key raises: one this API retired by name ({@code size.x} &rarr;
     * "a size is {w=, h=}") with that name, and any other with the {@code hint} listing what the shape carries.
     *
     * <p>Hang it off <b>one</b> metatable per shape, built once as a static and shared by every table of that
     * shape: a per-call metatable would double the allocation on a path ({@code w:size()} inside a draw
     * callback) whose whole cost is meant to be two field writes. {@code pairs}, {@code next} and
     * {@link Json#write} walk the raw fields and never reach here.
     */
    static LuaValue closedFields(final String shape, final String hint) {
        return new TwoArgFunction() {
            public LuaValue call(LuaValue self, LuaValue key) {
                if(key.isstring()) {
                    String msg = NAMES.get(shape + "." + key.tojstring());
                    if(msg != null)
                        throw new LuaError(msg);
                }
                throw new LuaError("a " + shape + " table has no field '" + key.tojstring() + "' — " + hint);
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
