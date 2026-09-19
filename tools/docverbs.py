"""Resolve every `<receiver>:<verb>(` in docs/addons/** against THAT RECEIVER'S OWN verb table.

The audit shipped a check that read "every `x:verb(` under docs/addons/api/ exists as a `.set("verb", …)`
in the bridge -- 264 of 264" and it was green while six pages taught code that raises. It was green
because it asked only whether the NAME exists SOMEWHERE: `find`, `value`, `label`, `leader` and `slot` all
still exist, just not on the type the page calls them on. A name-level check cannot see a verb on the wrong
receiver, and that is the failure mode that actually happens after a rename.

This one is receiver-typed. Each entity in the bridge declares its own vocabulary in one place --
`Refusal.closedIndex("<entity>", methods(...), hint)` -- and the methods table is built in the same file.
So: read each `Lua*.java`, take the entity name from its closedIndex, take the verbs from its `.set("x",`
calls, and check the docs against the pair.

Receivers in the docs are conventional (`meter:`, `food:`, `w:`, `req:`), so RECEIVERS below maps the
spelling a page uses to the entity that owns it. A receiver not in that map is skipped and counted, so the
map's own coverage is visible rather than assumed.

    python tools/docverbs.py            # report
    python tools/docverbs.py --verbose  # ...and list the skipped receivers

Exit code 1 when anything is unresolved, so it can gate a change.

The key passes run in BOTH directions. `key_mentions` resolves a key written in the docs against the set
the bridge fires; `undocumented_keys` resolves each key the bridge fires against the pages under
`docs/addons/api/event/`, which is the half no suite can walk -- nothing in Lua enumerates the bus, so a
key that fell off a page in a move is a row nobody can think to ask about.

WHAT IT CANNOT SEE, stated so the green is not read as more than it is:

  * A verb called ON A COLLECTION that is not one of the core six. A collection's `extra` verbs are
    per-site, so anything else is skipped rather than guessed at -- which is why `credo:cost()`, advertised
    by two refusal messages for a feature and a half, had to be found by reading.
  * A chain rooted at a session beyond the hops RETURNS seeds. `session:kin():get(7)` walks one hop into a
    collection and checks the core verbs; `session:world():gob():nearest(...)` stops at `world`, a section
    object with no closedIndex vocabulary, and is counted rather than guessed at. The old shorthand `s` was
    the most overloaded name in the tree -- a Session, a profiling scope, a Sound, a string -- and stays
    unmapped; `session` is only ever a Session.
  * A receiver spelled ambiguously. `p` is a Position and a profiling handle; `sp` is a Speed and a
    scrollport; `segment` is a map Segment and a band of a meter's bar; `summary` a study summary and a
    fight summary. Those are mapped per file where a page is unambiguous and skipped otherwise, and
    --verbose lists what was skipped so the map's coverage is visible instead of assumed. A local name
    built on an entity word (`scout_window`, `draw_event`, `alt_session`) resolves by its suffix, so
    the pages' own naming widens the map without a row per variable.
  * WHICH of a page's receivers a call belongs to, where one page documents two types under one
    spelling. `ov:` on the UI overlay page is a HUD painter in one section and a widget's overlay in
    the next, so that file maps `ov` to BOTH and a verb resolves if either type answers it. A verb
    written in the wrong section of that one page therefore still resolves -- which is the price of
    checking it at all, and far less than the alternative the map had before: `ov:` there resolved
    against the GOB overlay, a third type neither section is about, and passed on the verbs the
    three happen to share.
  * An event key on an OPEN emitter -- a console command, a hotkey, a wdgmsg, an action, req:on("done").
    Those key sets are protocol or user-chosen, so there is nothing to check against; the key pass reads
    upper-case keys only, which is the convention that separates the two. Its other blind spots are
    written above `event_keys` itself.
  * A claim with no call in it. "This is a plain array and not a collection" is prose; a javadoc that
    contradicts the method beneath it needs a reader, not a regex.
  * Anything about the API version but its number. `api_version` holds the one sentence the docs state the
    client's version in -- "this client implements API `1.0`", once, on the manifest page -- and every
    example of the out-of-date label and tooltip that repeats the number, to the literal `ApiVersion.CURRENT`
    is built from. It holds nothing of the RULE: whether a release that removed a name raised the generation,
    or one that added a verb raised the edition, is the maintainer's editorial call and no regex reads a diff
    of two vocabularies.
"""
import io, os, re, sys, collections

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BRIDGE = os.path.join(ROOT, "src", "io", "brodgar", "addon")
DOCS = os.path.join(ROOT, "docs", "addons")

# The receiver spelling a page uses -> the entity whose closedIndex owns that vocabulary.
#
# The pages write descriptive names (DOCUMENTATION.md §6): `position:`, `segment:`, `message:`. The shorthand
# they replaced (`p:`, `seg:`, `msg:`) stays mapped, so a page or a comment that still spells one is checked
# rather than skipped. A local name built on an entity word -- `scout_window`, `draw_event`, `alt_session` --
# resolves through SUFFIXES below, so a well-named variable needs no row of its own here.
RECEIVERS = {
    "gob": "gob", "p": "position", "position": "position", "ov": "overlay", "overlay": "overlay",
    "marker": "marker", "pin": "marker", "m": None,
    "seg": "segment", "segment": "segment", "grid": "grid", "cat": "cat", "category": "cat",
    "toggle": "toggle", "mask": "mask",
    "buff": "buff", "meter": "meter", "food": "food", "fep": "fep", "hunger": "hunger",
    "wound": "wound", "quest": "quest", "q": "quest", "cond": "condition", "condition": "condition", "c": None,
    "kin": "kin", "member": "partymember", "slot": None, "card": "deckcard", "maneuver": "maneuver",
    "channel": "channel", "ch": "channel", "msg": "message", "message": "message",
    "sp": "speed", "speed": "speed", "skill": "skill", "credo": "credo", "attr": "attr",
    "exp": "experience", "experience": "experience",
    "pag": "pagina", "pagina": "pagina", "item": "item", "contents": "contents", "hand": "hand",
    "w": "widget", "widget": "widget", "win": "widget", "window": "widget", "col": "widget", "column": "widget",
    "row": "widget", "control": "widget", "inventory": "widget", "root": "widget", "node": "widget",
    "hovered": "widget", "entry": "widget", "check": "widget", "slider": "widget", "radio": "widget",
    "listbox": "widget", "table": "widget", "menu": "widget", "button": "widget", "scrollbar": "widget",
    "label": "widget", "picture": "widget", "bar": "widget", "group": "widget",
    # An event object. Three files register verbs under `ev` -- the UI's shapes, a connection's and a voice
    # link's -- and their union is what a spelling resolves against: a verb no event of any kind answers is
    # caught, a verb from another kind's shape is not. The shorthand stays None: `ev` was also a local for
    # anything, where the descriptive spellings are only ever an event.
    "ev": None, "event": "ev", "press": "ev", "payload": None,
    "sub": "sub", "subscription": "sub", "poll": "timer",
    "h": None, "handle": "font", "face": "font",
    "req": "request", "request": "request", "res": "res", "result": "res",
    "conn": "connection", "connection": "connection", "opened": "connection", "sheet": "sheet",
    "voice": "voice", "link": "voice", "peer": "peer",
    "rule": "rule", "petal": "petal", "spec": "craftspec", "role": "role",
    "binding": "binding", "b": None, "section": "section", "sound": "sound", "bell": "sound", "timer": "timer",
    "miss": "miss", "opt": "option", "option": "option", "rows": "option", "pl": "placing", "placing": "placing",
    "target": "opponent", "grab": "grab", "scope": "scope", "profiling": "profiling", "declaration": "declaration",
    "mouse": "hafen.ui():mouse()",
    # The session the pages read one character through. Its verbs are a closedIndex vocabulary (LuaSession),
    # so a bare `session:verb()` resolves; a chain rooted at it walks only the hops RETURNS names, as before.
    "session": "session", "viewer": "session",
    # A gob under a name a page gives it once, and a place under one: the world pages call the object they
    # found `tree`, `prey`, `boar`, and the ground pages the place they stand `here`.
    "tree": "gob", "prey": "gob", "boar": "gob", "here": "position", "corner": "position",
    "agility": "resource", "sun": "resource", "first_frame": "layer", "command": "sub", "strength": "attr",
    "backpack": "widget", "advanced_switch": "widget", "badge": "widget", "view": "widget",
    "held": "contents", "inner": "item", "jug": "item",
    # Prose the call regex reads as a receiver (`name:find(` is a Lua string method; `and`, `only`, `a` are
    # words), and the client option handles a page names after their panel.
    "name": None, "and": None, "only": None, "a": None, "video": None, "camera": None, "client_options": None,
    # The client's resources (151). `res:` stays the HTTP result; the pages spell the handle `resource:`.
    "resource": "resource", "layer": "layer",
    # A SECTION object, whose verbs are not a closedIndex vocabulary: `hafen.locale()` is the catalogue
    # itself, so its verbs are enumerable only by reading LocaleApi, exactly as `s:char()`'s are.
    "locale": None, "options": None, "opts": None, "keybindings": None, "world": None, "store": None,
    "steam": None, "menugrid": None, "actionbar": None, "speeds": None, "slots": None, "layers": None,
    "resources": None, "collection": None, "gobs": None, "answers": None,
    # The draw wrapper `draw_event:g()` hands a Draw handler. Its verbs are built in LuaGraphics through the
    # section machinery rather than a closedIndex literal, so there is nothing to resolve against.
    "graphics": None,
    # A loaded file. `hafen.asset():get(path)` answers a handle typed by the file's extension, and none of
    # the four kinds declares a closedIndex, so an asset's verbs are not enumerable here.
    "asset": None, "image": None, "img": None, "data": None, "mesh": None, "chair": None, "icon": None,
    # A virtual KIND. Every one of them is handed out by VirtualApi.entityHandle, whose closedIndex is built from
    # a `kind` VARIABLE rather than a literal, so no vocabulary is extractable for any of them and the
    # whole family is skipped here. `patch` is spelled out because `p` is a Position everywhere else.
    "patch": None, "entity": None, "ghost": None, "sprite": None, "object": None, "panel": None,
    "footprint": None, "footprint_patch": None, "plan": None, "field": None,
    # A piece of a patch. Its closedIndex IS a literal, but `bridge_vocabularies` unions a file's verbs, and
    # VirtualApi.java sets thirty of them for the five kinds beside it -- so `piece` would resolve `:border()`
    # and `:ring()`, which no piece answers. A miss costs a check; a false positive costs trust.
    "piece": None, "middle": None,
    "seg2": None, "g": None, "s": None, "t": None, "v": None, "x": None, "X": None,
}

# A local name built on an entity word resolves by its last word: `scout_window` is a widget, `draw_event`
# an event, `alt_session` a session, `toggle_binding` a binding. Only suffixes that name ONE type are here;
# `_entry` is not, because `dig_entry` is an action-menu entry and `search_entry` a text control, and neither is
# `_grid`, because `icon_grid` is a control and `current_grid` a map Grid.
SUFFIXES = {
    "_window": "widget", "_button": "widget", "_label": "widget", "_check": "widget", "_checkbox": "widget",
    "_slider": "widget", "_radio": "widget", "_column": "widget", "_scrollbar": "widget", "_box": "widget",
    "_list": "widget",
    "_event": "ev", "_session": "session", "_gob": "gob", "_position": "position", "_binding": "binding",
    "_hotkey": "sub", "_command": "sub", "_timer": "timer", "_speed": "speed", "_font": "font",
    "_meter": "meter", "_scope": "scope", "_patch": None,
}

def receiver(base, over):
    """The entity a receiver spelling names on one page: the page's override, the map, then the suffix rule."""
    if base in over:
        return over[base]
    if base in RECEIVERS:
        return RECEIVERS[base]
    for suffix, ent in SUFFIXES.items():
        if base.endswith(suffix) and len(base) > len(suffix):
            return ent
    return "?"

# A receiver spelling that means something else on one page. `w:` is a widget nearly everywhere, a Wound on
# wound.md and the world SECTION on world.md -- three types, one letter, which is the docs' own shorthand
# rather than a defect. Keyed by the file's name, or by as much of its path as it takes to be unambiguous:
# `overlay.md` is two pages, the gob's and the UI's, and they mean different types by `ov`. A tuple maps one
# spelling to SEVERAL types, for a page that documents more than one of them and calls them all `ov`.
PER_FILE = {
    "wound.md":  {"w": "wound"},
    "world.md":  {"w": None},                 # the world section object: its verbs live on WorldApi
    "player.md": {"gob": None},               # the page discusses gob verbs that deliberately do NOT exist
    "conventions.md": {"gob": None},          # ...and prints a typo on purpose, to show the refusal
    "shapes.md": {"w": None},
    # `w:` is the world section on every page that draws in it; `p:` is the profiling handle under
    # client/profiling/ and a progress control on the control pages; `sp:` is a scrollport there too.
    "mouse.md":  {"w": None},
    "pixels.md": {"w": None},
    "sprites.md": {"w": None},
    "ghosts.md": {"w": None},
    "custom-ui.md": {"w": None},
    "README.md": {"p": None, "w": None},
    "attribution.md": {"p": None},
    "counters.md": {"p": None},
    "display.md": {"p": None},
    "flowermenu.md": {"p": "petal"},          # `p` is a petal on that page, not a Position
    "drawings.md": {"grid": None},
    # The UI overlays are one page and two receivers -- the HUD painter and a widget's overlay -- both
    # spelled `overlay`. The gob's page keeps the bare name, so this one is keyed by its directory too.
    "ui/overlay.md": {"ov": ("uioverlay", "widgetoverlay"), "overlay": ("uioverlay", "widgetoverlay"),
                      "painters": "@collection"},      # `hafen.ui():overlay()` itself: the collection, not a painter
    # One word, two entities: `segment` is a map Segment on the map pages and a band of a meter's bar on the
    # meter pages, `summary` a study summary or a fight summary, `icon` a loaded image on the asset pages
    # and the widget that draws an item on the item pages, `chest` and `cupboard` a gob on the world pages
    # and a container window on the UI pages, `first`/`second` two markers or two labels.
    "meter.md": {"segment": "meter", "fill": "meter", "health_meter": "meter"},
    "types/ui.md": {"segment": "meter"},
    "study.md": {"summary": "studysummary"},
    "types/character.md": {"w": "wound", "summary": "studysummary"},
    "fight.md": {"summary": "fightsummary"},
    "items.md": {"icon": "widget", "first": "item"},
    "references.md": {"icon": "widget"},
    "container.md": {"chest": "widget"},
    "look.md": {"chest": "gob", "boar": "gob"},
    "replace.md": {"cupboard": "widget"},
    "selectors.md": {"cupboard": "widget"},
    "markers.md": {"first": "marker", "second": "marker", "camp": "marker"},
    "column.md": {"sp": None, "first": "widget", "second": "widget", "labels": "widget"},
    "icons.md": {"candidate": "cat", "boars": "cat"},
    "event/README.md": {"candidate": "sub"},
    "patches.md": {"candidate": None},
    "keybindings.md": {"sprint": "binding"},
    "chat.md": {"newest": "message", "area": "channel", "party": "channel"},
    "steam.md": {"swan": "achievement", "ach": "achievement"},
    "gob.md": {"crop": "gob", "wall": "gob", "log": "gob"},
    "menugrid.md": {"child": "pagina", "dig": "pagina", "tools": "pagina", "harvest": "pagina",
                    "category": "pagina"},           # a category of the action menu, not an icon category
    "actionbar.md": {"dig_entry": "pagina"},
    "native.md": {"chat": "widget", "minimap": "widget"},
    "guides/saved-data.md": {"nodes": "table", "chat": "widget"},
    "edit.md": {"volume": "widget"},
    "interactive.md": {"sp": None, "p": None, "volume": "widget", "search_entry": "widget"},
    "lists.md": {"grid": None, "kind_filter": "widget", "actions": "widget", "icon_grid": "widget"},
    "overlays.md": {"claim": "mask", "claims": "toggle"},
    "position.md": {"home": "position"},
    "grids.md": {"current_grid": "grid"},
    # An addon's own options: the page names each row after what it configures, which is what an author
    # writes, so the spellings are mapped here rather than the page renaming its variables to suit a tool.
    # `opts` is the handle itself, whose vocabulary is not a closedIndex one (it is built through
    # OptionsHandle.close), so it is skipped and counted like every other section object.
    "client/addon.md": {"opts": None, "show": "option", "size": "option", "mode": "option",
                        "sort": "option", "title": "option", "state": "option", "o": "option",
                        "show_timer": "option"},      # an option named for what it shows, not a timer
    # ...and the guide that puts a setting beside the hotkey and the command names its one row the same way.
    "guides/hotkeys-and-commands.md": {"opts": None, "rows": "option"},
    # The store's tables: `nodes`, `trees` and `prices` are Tables on the pages that declare them, and `decl`
    # is the Declaration `hafen.store():table(name)` answers. Both closedIndex literals live in one file, so
    # the two vocabularies are that file's union -- a `:put` written on a declaration would resolve here, and
    # the pages are the only reader that catches it.
    "store/README.md": {"trees": "table"},
    "store/tables.md": {"nodes": "table", "decl": "declaration"},
    "store/statements.md": {"prices": "table", "decl": "declaration"},
    # A gob's material slots (152). `slot` is an action-bar slot on the pages that skip it; here it is the
    # MaterialSlot, whose closedIndex is the one literal in LuaMaterialSlot.java.
    "materials.md": {"slot": "materialslot", "chest": "gob", "cupboard": "gob"},
    # 156.1: the collection hafen.client():addons() mints, and the Addon handle it mints per id.
    # 156.3: "handle" is a second name the page uses for that same Addon handle -- the global map default
    # ("handle" -> font, a face/keybindings handle on other pages) is wrong here.
    "client/addons.md": {"addons": "@collection", "addon": "addon", "handle": "addon"},
}

def per_file(rel):
    """The receiver overrides in force for one page: its name's, plus any keyed by more of its path."""
    over = dict(PER_FILE.get(rel.rsplit("/", 1)[-1], {}))
    for k, v in PER_FILE.items():
        if ("/" in k) and (rel == k or rel.endswith("/" + k)):
            over.update(v)
    return over

# What one verb hands back, where a page or a comment then calls a verb ON it. Without this a chained
# receiver -- `credo:pursuing():cost()` -- resolves to `pursuing`, which is not a type, and the mention is
# skipped: exactly the blind spot that let a verb be advertised as live by two refusal messages
# while this tool printed green. Seeded rather than derived, and every unresolved chain is COUNTED below,
# so the map's own coverage is visible instead of assumed.
RETURNS = {
    ("credo", "pursuing"): "credo",
    # The session chain: a message writes the whole address, `s:char():credo():cost()`, so the base is a
    # session and the hops have to be walked or the mention is invisible -- which is how two refusals
    # advertised `credo:cost()` for a feature and a half.
    ("session", "char"): "@charsection",
    # The collections a session hands out: a bare `session:kin():get(7)` walks one hop and checks the core
    # verbs; a section that is not a collection (`world`, `ui`, `player`, `study`, `craft`, `fight`, `store`,
    # `console`) has no closedIndex vocabulary and stays a counted hop.
    ("session", "kin"): "@collection", ("session", "actionbar"): "@collection",
    ("session", "meter"): "@collection", ("session", "buff"): "@collection",
    ("session", "party"): "@collection", ("session", "quest"): "@collection",
    ("session", "wound"): "@collection", ("session", "menugrid"): "@collection",
    ("session", "chat"): "@collection", ("session", "speed"): "@collection",
    ("session", "flowermenu"): "@collection",
    ("widget", "rule"): "rule", ("sheet", "rule"): "rule", ("ev", "widget"): "widget",
    ("partymember", "gob"): "gob", ("opponent", "gob"): "gob", ("peer", "gob"): "gob",
    ("hand", "item"): "item", ("grid", "mask"): "@collection",
    ("charsection", "credo"): "@collection",
    ("charsection", "skill"): "@collection",
    ("widget", "parent"): "widget",
    ("gob", "kin"): "kin",
    ("kin", "gob"): "gob",
    ("marker", "position"): "position",
    ("gob", "position"): "position",
    ("item", "contents"): "contents",
    ("message", "channel"): "channel",
    ("buff", "widget"): "widget",
    ("meter", "widget"): "widget",
    ("kin", "widget"): "widget",
    ("wound", "parent"): "wound",
    ("pagina", "parent"): "pagina",
    ("food", "hunger"): "hunger",
    ("food", "fep"): "fep",
    ("request", "send"): "request",
    # A verb that hands back a COLLECTION, not an entity: what follows it is collection vocabulary, and a
    # collection's `extra` verbs are per-site, so anything outside the core six is skipped rather than
    # guessed at. A miss costs a check; a false positive costs trust.
    ("segment", "grid"): "@collection",
    ("segment", "markers"): "@collection",
    ("gob", "overlay"): "@collection",
    ("gob", "materials"): "@collection",
    ("gob", "sessions"): "@collection",
    ("widget", "children"): "@collection",
    ("widget", "items"): "@collection",
    ("contents", "items"): "@collection",
    ("wound", "children"): "@collection",
    ("pagina", "children"): "@collection",
    ("meter", "segment"): "@collection",
    ("channel", "message"): "@collection",
    ("voice", "peer"): "@collection",
    ("fep", "entry"): "@collection",
    ("resource", "layers"): "@collection",
}

def bridge_vocabularies():
    """entity name -> the verbs its own file registers."""
    out = collections.defaultdict(set)
    for f in sorted(os.listdir(BRIDGE)):
        if not f.endswith(".java"):
            continue
        src = io.open(os.path.join(BRIDGE, f), encoding="utf-8", errors="replace").read()
        ents = re.findall(r'closedIndex\(\s*"([^"]+)"', src)
        if not ents:
            continue
        verbs = set(re.findall(r'\b\w+\.set\(\s*"([A-Za-z_]\w*)"\s*,', src))
        for e in ents:
            # a section spelled "session:player()" owns its verbs under that whole name
            out[e.lower()] |= verbs
    return out

# `a:b():c():d(` -- the base, the steps between, and the verb being called on the last of them.
CHAIN = re.compile(r'\b([a-zA-Z_]\w*)((?:\s*:\s*[a-zA-Z_]\w*\s*\([^()]*\))*)\s*:\s*([a-zA-Z_]\w*)\s*\(')
# Every collection answers these; anything else on one is a per-site `extra`.
COLLECTION_CORE = {"list", "count", "find", "get", "add", "remove"}

STEP = re.compile(r':\s*([a-zA-Z_]\w*)\s*\(')

def resolve_line(line, over, vocab, whole=None):
    """Yield (spelling, verb, entity-or-None, ok) for every receiver-typed call on one line."""
    out = []
    for m in CHAIN.finditer(line):
        base, mid, verb = m.group(1), m.group(2) or "", m.group(3)
        # a page resolves through its overrides, the map and the suffix rule; a bridge MESSAGE through the
        # unambiguous spellings alone, since its prose reuses the short names for other things
        ent = receiver(base, over) if whole is None else (over[base] if base in over else "?")
        if ent == "@session":
            ent = "session"
        if ent is None:
            continue                                   # deliberately ambiguous base
        if ent == "?":
            out.append((base, verb, None, False))
            continue
        spelling = base
        for step in STEP.findall(mid):                 # walk the chain, one hop at a time
            if ent == "@charsection":
                ent = RETURNS.get(("charsection", step))
                spelling += ":" + step + "()"
                if ent is None:
                    break
                continue
            spelling += ":" + step + "()"
            nxt = RETURNS.get((ent, step))
            if nxt is None:
                ent = None
                break
            ent = nxt
        if ent is None:
            out.append((spelling, verb, None, False))
            continue
        if ent == "@charsection":
            # s:char() is a section object, not a closedIndex entity: its own verbs are not enumerable here,
            # so only the hops named in RETURNS resolve and anything else is skipped rather than guessed at.
            nxt = RETURNS.get(("charsection", verb))
            out.append((spelling, verb, None, False)) if nxt is None else out.append((spelling, verb, "@charsection", True))
            continue
        if ent == "@collection":
            if verb in COLLECTION_CORE:
                out.append((spelling, verb, ent, True))
            else:
                out.append((spelling, verb, None, False))   # an `extra` verb: not knowable here
            continue
        if isinstance(ent, tuple):
            # one spelling, several types on that page: a chain off it is not resolvable, a bare call is
            if STEP.findall(mid):
                out.append((spelling, verb, None, False))
                continue
            known = set()
            for e in ent:
                known |= vocab.get(e, set())
            out.append((spelling, verb, "/".join(ent), verb in known))
            continue
        known = vocab.get(ent)
        if not known:
            out.append((spelling, verb, None, False))
            continue
        out.append((spelling, verb, ent, verb in known))
    return out

def collection_spellings():
    """Every call the bridge mints a collection for, spelled as the call site writes it.

    `LuaCollection.create("<name>", …)` names itself exactly as a page would write it -- "widget:children()",
    "gob:sessions()" -- so this is a direct list of the calls whose result is a collection, and a collection
    refuses `#` and `ipairs` on purpose (LuaCollection's __len and its numeric __index both raise).
    """
    out = set()
    for f in sorted(os.listdir(BRIDGE)):
        if not f.endswith(".java"):
            continue
        src = io.open(os.path.join(BRIDGE, f), encoding="utf-8", errors="replace").read()
        for m in re.finditer(r'LuaCollection\.create\(\s*"([^"]+)"', src):
            nm = m.group(1)
            if nm.endswith("()"):
                out.add(nm)
    # LuaCraft builds its four names at runtime (CharApi.CR + ":" + verb + "()"), so no literal survives the
    # scan. Named here rather than left invisible -- a collection the check cannot see is a page it cannot
    # check, and that is exactly the blindness this tool exists to remove.
    for v in ("inputs", "outputs", "qualityInputs", "tools"):
        out.add("session:craft():" + v + "()")
    return out

def as_array(vocab):
    """Docs that put `#` in front of a collection, or walk one with ipairs, or index it."""
    colls = collection_spellings()
    # the verb half of each, so a page writing a different receiver spelling is still caught
    verbs = set()
    for c in colls:
        tail = c.split(":")[-1]
        if tail.endswith("()"):
            verbs.add(tail[:-2])
    bad = []
    for dirpath, _, files in os.walk(DOCS):
        for f in sorted(files):
            if not f.endswith(".md"):
                continue
            p = os.path.join(dirpath, f)
            rel = os.path.relpath(p, ROOT).replace("\\", "/")
            for i, line in enumerate(io.open(p, encoding="utf-8", errors="replace"), 1):
                for v in verbs:
                    # the receiver may itself be a call, as in `inventory():items()`, so `)` is in the class;
                    # `#` counts what the whole chain answers, so `#x:coll():unlocked()` is an array's length
                    for pat, how in ((r'#\s*[\w.\[\]"():]*:' + v + r'\(\s*\)(?!\s*[:.])', "# on"),
                                     (r'ipairs\(\s*[\w.\[\]"():]*:' + v + r'\(\s*\)\s*\)', "ipairs over"),
                                     (r':' + v + r'\(\s*\)\s*\[', "indexing")):
                        if re.search(pat, line):
                            bad.append((rel, i, how, v, line.strip()[:100]))
                            break
    return colls, bad

# The receivers a bridge MESSAGE spells unambiguously. The single letters are dropped here on purpose:
# in the bridge's own prose `p` is a profiling handle as often as a Position and `sp` is a scrollport as
# often as a Speed, so mapping them would report collisions rather than defects.
MSG_RECEIVERS = {k: v for k, v in RECEIVERS.items() if (v is not None) and (len(k) > 2)}
# ...and the words a message uses as prose rather than as a receiver: "the handle :request(url) handed you",
# "here :find(needle) searches". `overlay` in a message may be any of the three overlay kinds.
for _prose in ("handle", "here"):
    MSG_RECEIVERS.pop(_prose, None)
MSG_RECEIVERS["overlay"] = ("overlay", "uioverlay", "widgetoverlay")

def java_mentions(vocab):
    """Every receiver-typed call spelled inside a MESSAGE the bridge raises.

    A refusal is the contract a user reads at the moment they are stuck, and it went stale after every
    rename with nothing resolving it: `LuaSkill`'s points at `:available()[i]` and `LuaStudySlot`'s at
    A message that names a verb the receiver has not got sends the reader to a
    second refusal.

    String literals only. A COMMENT may legitimately name a dead spelling -- "there is no gob:move()" is
    true and useful -- so comments are left to a human; a message has no such reading.
    """
    bad, checked = [], 0
    for f in sorted(os.listdir(BRIDGE)):
        # Refusal.java names the DEAD spelling in every message by design; tools/refusalverbs.py owns it,
        # and only it knows which half of a message is the dead spelling and which is the promise.
        if (not f.endswith(".java")) or (f == "Refusal.java"):
            continue
        p = os.path.join(BRIDGE, f)
        for i, line in enumerate(io.open(p, encoding="utf-8", errors="replace"), 1):
            t = line.strip()
            if t.startswith(("*", "//", "/*")) or ('"' not in line):
                continue
            for lit in re.findall(r'"((?:[^"\\]|\\.)*)"', line):
                for recv, verb, ent, ok in resolve_line(lit, MSG_RECEIVERS, vocab, whole=True):
                    if ent is None:
                        continue
                    checked += 1
                    if not ok:
                        bad.append(("src/io/brodgar/addon/" + f, i, recv, verb, ent, t[:104]))
    return checked, bad

# ---------------------------------------------------------------------------------------------------
# EVENT KEYS -- the other half of the contract, and the half a verb check cannot see.
#
# A subscription names its key with a STRING, so a page that still writes a key the bridge stopped
# firing reads perfectly and fails only when someone runs it. 097 moved eleven of them, which is
# exactly the move that leaves that kind of rot behind.
#
# The rule that makes this checkable: every CLOSED key set in the API is PascalCase, and every OPEN
# emitter's key is a name the addon author chose -- a console command, a hotkey, a wdgmsg, an action --
# which every page writes in lower case. So an upper-case key must be one the bridge fires, and a
# lower-case one is skipped.
#
# BLIND SPOTS, stated rather than implied:
#   - open emitters (hafen.console(), keybindings(), action(), message()) are not checked at all. Their
#     key sets are PROTOCOL or user-chosen; there is nothing to check against, and a typo there is the
#     author's own. A menu entry's `Pressed` and a request's `Done` are closed and read below.
#   - a key built from a variable rather than written as a literal is invisible here.
#   - suites under addons/<NNN>-*/ and addons/R<nn>-*/ are skipped: they call moved spellings ON PURPOSE, to prove the
#     refusal raises. A suite is re-run every round, which is its own guard.
def event_keys():
    """Every key a CLOSED emitter fires, read out of the bridge's own key sets."""
    def arr(text, name):
        m = re.search(r'String\[\]\s+' + name + r'\s*=\s*\{(.*?)\}', text, re.S)
        return set(re.findall(r'"([A-Za-z]+)"', m.group(1))) if m else set()
    am = io.open(os.path.join(BRIDGE, "AddonManager.java"), encoding="utf-8", errors="replace").read()
    lw = io.open(os.path.join(BRIDGE, "LuaWidget.java"), encoding="utf-8", errors="replace").read()
    ws = io.open(os.path.join(BRIDGE, "LuaWebSocket.java"), encoding="utf-8", errors="replace").read()
    vo = io.open(os.path.join(BRIDGE, "LuaVoice.java"), encoding="utf-8", errors="replace").read()
    pg = io.open(os.path.join(BRIDGE, "AddonPagina.java"), encoding="utf-8", errors="replace").read()
    hr = io.open(os.path.join(BRIDGE, "LuaHttpRequest.java"), encoding="utf-8", errors="replace").read()
    # conn:on(key, fn) / voice:on(key, fn) / pagina:on(key, fn) / request:on(key, fn) -- the edges of a
    # connection, a voice link, a menu entry of yours and a request, each declared as an array like the
    # bus's own
    live = (arr(am, "BUS_KEYS") | arr(lw, "UNIVERSAL_KEYS") | arr(lw, "SURFACE_KEYS") | arr(ws, "KEYS")
            | arr(vo, "KEYS") | arr(pg, "KEYS") | arr(hr, "KEYS"))
    # widgetKeys() adds these by interface rather than from an array, so they are named here.
    live |= {"Pressed", "Changed", "Submitted", "Selected", "Cell", "ItemAdded", "ItemRemoved"}
    live |= {"Added", "Removed"}     # the selector watch, s:ui():on(sel, event, fn)
    live |= {"Move", "Up"}           # the pointer grab, closed to exactly these two
    live |= {"PickChanged"}          # the pointer's pick pass, m:on(key, fn), closed to exactly this one
    return live

ON_KEY = re.compile(r':on\(\s*(?:[^,()]*,\s*)?"([A-Za-z][A-Za-z]*)"')

def key_mentions(live):
    """Every upper-case :on( key written in docs/ or in one of the five tools."""
    checked, bad = 0, []
    roots = [(DOCS, None), (os.path.join(ROOT, "addons"), "suite")]
    for base, mode in roots:
        for dirpath, dirnames, files in os.walk(base):
            rel_dir = os.path.relpath(dirpath, ROOT).replace("\\", "/")
            # a suite folder is <NNN>-<feature>.<X> -- it calls dead spellings on purpose
            if mode == "suite" and re.search(r'/(?:\d{3}|R\d{2})-[^/]+$', rel_dir):
                continue
            for f in files:
                if not f.endswith((".md", ".lua")):
                    continue
                p = os.path.join(dirpath, f)
                rel = os.path.relpath(p, ROOT).replace("\\", "/")
                for i, line in enumerate(io.open(p, encoding="utf-8", errors="replace"), 1):
                    for k in ON_KEY.findall(line):
                        if not k[0].isupper():
                            continue          # an open emitter's own name -- see the blind spots
                        checked += 1
                        if k not in live:
                            bad.append((rel, i, k, line.strip()[:100]))
    return checked, bad


# The other direction of the key pass, and the one nothing else can walk: every key the bridge FIRES is
# written somewhere under docs/addons/api/event/.
#
# `key_mentions` above resolves DOCS -> BRIDGE, so a page still teaching a key the client stopped firing
# fails. The opposite failure had nothing resolving it: a key the client fires that fell OFF a page. That is
# what splitting the catalogue into a folder of pages can do to one table row, invisibly, inside a diff of
# three hundred moved lines. A suite in Lua cannot see it either -- there is no verb that enumerates the
# bus, so a key no page names is a key nothing can think to ask about.
#
# The catalogue writes every key in backticks, so that is what is looked for; a key named only in running
# prose is not documented, it is mentioned.
def undocumented_keys():
    """(every BUS_KEY, those no page under docs/addons/api/event/ writes in backticks)."""
    am = io.open(os.path.join(BRIDGE, "AddonManager.java"), encoding="utf-8", errors="replace").read()
    m = re.search(r'String\[\]\s+BUS_KEYS\s*=\s*\{(.*?)\}', am, re.S)
    keys = re.findall(r'"([A-Za-z]+)"', m.group(1)) if m else []
    written = set()
    for dirpath, _, files in os.walk(os.path.join(DOCS, "api", "event")):
        for f in sorted(files):
            if not f.endswith(".md"):
                continue
            text = io.open(os.path.join(dirpath, f), encoding="utf-8", errors="replace").read()
            written |= set(re.findall(r'`([A-Za-z]+)`', text))
    return keys, [k for k in keys if k not in written]


# The version the client implements, held to the version the docs state. The client's number is ONE literal,
# `ApiVersion.CURRENT`, and the docs state it in ONE sentence, on the manifest page's `api_version` row --
# "this client implements API `1.0`" -- which is what a reader writes into their manifest. A bump that moved
# the literal and not the page would have every reader declaring a version the client calls out of date, and
# nothing in the docs' own grammar can see a number go stale. The examples of the out-of-date label and the
# tooltip repeat the number in the open, so those are held too, by the two forms they take.
def api_version():
    """(the literal, [(page, line, version)] of the one sentence, [(page, line, version)] of every example).

    The literal is None when ApiVersion.java carries no `CURRENT = new ApiVersion(X, Y)`; main() reads the
    sentence list for exactly one hit equal to the literal, and the example list for nothing unequal to it."""
    src = io.open(os.path.join(BRIDGE, "ApiVersion.java"), encoding="utf-8", errors="replace").read()
    m = re.search(r'CURRENT\s*=\s*new ApiVersion\((\d+),\s*(\d+)\)', src)
    current = ("%s.%s" % m.groups()) if m else None
    sentence, examples = [], []
    for dirpath, _, files in os.walk(DOCS):
        for f in sorted(files):
            if not f.endswith(".md"):
                continue
            p = os.path.join(dirpath, f)
            rel = os.path.relpath(p, ROOT).replace("\\", "/")
            for i, line in enumerate(io.open(p, encoding="utf-8", errors="replace"), 1):
                for v in re.findall(r'implements API `(\d+\.\d+)`', line):
                    sentence.append((rel, i, v))
                # `outdated (API 9.0, client 1.0)` and `..., this client implements 1.0` -- the label and the
                # tooltip as the panel writes them, each with the client's number in the second position.
                for v in re.findall(r'client (\d+\.\d+)\)', line) + re.findall(r'client implements (\d+\.\d+)\b', line):
                    examples.append((rel, i, v))
    return current, sentence, examples


def main():
    verbose = "--verbose" in sys.argv
    vocab = bridge_vocabularies()
    bad, skipped, checked = [], collections.Counter(), 0
    for dirpath, _, files in os.walk(DOCS):
        for f in sorted(files):
            if not f.endswith(".md"):
                continue
            p = os.path.join(dirpath, f)
            rel = os.path.relpath(p, ROOT).replace("\\", "/")
            for i, line in enumerate(io.open(p, encoding="utf-8", errors="replace"), 1):
                over = per_file(rel)
                for recv, verb, ent, ok in resolve_line(line, over, vocab):
                    if ent is None:
                        skipped[recv] += 1
                        continue
                    checked += 1
                    if not ok:
                        bad.append((rel, i, recv, verb, ent, line.strip()[:100]))

    print("checked %d receiver-typed calls across %d entity vocabularies" % (checked, len(vocab)))
    if bad:
        print("\n== %d call(s) the receiver's own type does not answer ==" % len(bad))
        for rel, i, recv, verb, ent, text in bad:
            print("  %s:%d  %s:%s()  -- `%s` has no such verb\n      %s" % (rel, i, recv, verb, ent, text))
    else:
        print("nothing unresolved")
    jchecked, jbad = java_mentions(vocab)
    print("\nchecked %d receiver-typed mentions in the messages the bridge raises" % jchecked)
    if jbad:
        print("== %d mention(s) naming a verb the receiver does not answer ==" % len(jbad))
        for rel, i, recv, verb, ent, text in jbad:
            print("  %s:%d  %s:%s()  -- `%s` has no such verb\n      %s" % (rel, i, recv, verb, ent, text))
    else:
        print("every message the bridge raises names verbs its receiver answers")

    colls, arrayish = as_array(vocab)
    print("\n%d collection-returning calls in the bridge" % len(colls))
    if arrayish:
        print("== %d place(s) using a collection as an array (both are refused at runtime) ==" % len(arrayish))
        for rel, i, how, v, text in arrayish:
            print("  %s:%d  %s :%s()\n      %s" % (rel, i, how, v, text))
    else:
        print("no collection is used as an array")

    live = event_keys()
    kchecked, kbad = key_mentions(live)
    print("\nchecked %d upper-case event keys against %d the bridge fires" % (kchecked, len(live)))
    if kbad:
        print("== %d place(s) subscribing to a key the bridge does not fire ==" % len(kbad))
        for rel, i, k, text in kbad:
            print("  %s:%d  \"%s\"\n      %s" % (rel, i, k, text))
    else:
        print("every event key written outside a suite is one the bridge fires")

    keys, missing = undocumented_keys()
    print("\nchecked %d bus keys the bridge fires against the pages under docs/addons/api/event/" % len(keys))
    if missing:
        print("== %d key(s) the client fires that no page of the catalogue writes ==" % len(missing))
        for k in missing:
            print("  %s" % k)
    else:
        print("every key the bridge fires is written on a page of the catalogue")

    current, sentence, examples = api_version()
    vbad = []
    if current is None:
        vbad.append("ApiVersion.java carries no `CURRENT = new ApiVersion(X, Y)` literal to hold the docs to")
    if len(sentence) != 1:
        vbad.append("the docs state \"this client implements API `X.Y`\" %d time(s); it is stated once, on the"
                    " manifest page's `api_version` row" % len(sentence))
        for rel, i, v in sentence:
            vbad.append("  %s:%d  `%s`" % (rel, i, v))
    for rel, i, v in sentence + examples:
        if (current is not None) and (v != current):
            vbad.append("  %s:%d  states %s, the client implements %s" % (rel, i, v, current))
    print("\nheld the API version the docs state (%d sentence, %d example(s)) to ApiVersion.CURRENT = %s"
          % (len(sentence), len(examples), current))
    if vbad:
        print("== the version the docs state is not the client's ==")
        for line in vbad:
            print("  " + line)
    else:
        print("every version the docs state is the client's")

    if verbose and skipped:
        print("\n== receivers not mapped (add to RECEIVERS to widen coverage) ==")
        for r, n in skipped.most_common(30):
            print("  %-16s %d" % (r, n))
    return 1 if (bad or arrayish or jbad or kbad or missing or vbad) else 0

if __name__ == "__main__":
    sys.exit(main())
