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

WHAT IT CANNOT SEE, stated so the green is not read as more than it is:

  * A verb called ON A COLLECTION that is not one of the core six. A collection's `extra` verbs are
    per-site, so anything else is skipped rather than guessed at -- which is why `credo:cost()`, advertised
    by two refusal messages for a feature and a half, had to be found by reading.
  * A chain rooted at `s`. It is the most overloaded name in the tree -- a Session on most pages, a
    profiling scope, a Sound, a string, a local for anything -- so mapping it reported 78 collisions and
    zero defects. A session-rooted chain is therefore not resolved at all, which is why `credo:cost()`
    had to be found by reading rather than by this.
  * A receiver spelled ambiguously. `p` is a Position and a profiling handle; `sp` is a Speed and a
    scrollport. Those are mapped per file where a page is unambiguous and skipped otherwise, and
    --verbose lists what was skipped so the map's coverage is visible instead of assumed.
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
"""
import io, os, re, sys, collections

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BRIDGE = os.path.join(ROOT, "src", "io", "brodgar", "addon")
DOCS = os.path.join(ROOT, "docs", "addons")

# The receiver spelling a page uses -> the entity whose closedIndex owns that vocabulary.
RECEIVERS = {
    "gob": "gob", "p": "position", "ov": "overlay", "marker": "marker", "m": None,
    "seg": "segment", "grid": "grid", "cat": "iconcat", "toggle": "toggle",
    "buff": "buff", "meter": "meter", "food": "food", "fep": "fep", "hunger": "hunger",
    "wound": "wound", "quest": "quest", "q": "quest", "cond": "condition", "c": None,
    "kin": "kin", "member": "partymember", "slot": None, "card": "deckcard",
    "channel": "channel", "ch": "channel", "msg": "message",
    "sp": "speed", "skill": "skill", "credo": "credo", "attr": "attr", "exp": "experience",
    "pag": "pagina", "item": "item", "contents": "contents", "hand": "hand",
    "w": "widget", "widget": "widget", "win": "widget", "ev": None, "sub": "sub",
    "h": None, "asset": "asset", "req": "request", "res": "res", "sheet": "sheet",
    "rule": "rule", "petal": "petal", "spec": "craftspec", "role": "role",
    "binding": "binding", "b": None, "sound": "sound", "timer": "timer",
    "miss": "miss",
    # A SECTION object, whose verbs are not a closedIndex vocabulary: `hafen.locale()` is the catalogue
    # itself, so its verbs are enumerable only by reading LocaleApi, exactly as `s:char()`'s are.
    "locale": None,
    "seg2": None, "g": None, "s": None, "t": None, "v": None, "x": None,
}

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
    "types/character.md": {"w": "wound"},   # the Wound snapshot section, where `w:` is a Wound
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
    "interactive.md": {"sp": None, "p": None},
    "flowermenu.md": {"p": "petal"},          # `p` is a petal on that page, not a Position
    "lists.md":  {"grid": None},              # a UI grid control, not the map's Grid
    "drawings.md": {"grid": None},
    # The UI overlays are one page and two receivers -- the HUD painter and a widget's overlay -- both
    # spelled `ov`. The gob's page keeps the bare name, so this one is keyed by its directory too.
    "ui/overlay.md": {"ov": ("uioverlay", "widgetoverlay")},
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
    ("gob", "sessions"): "@collection",
    ("widget", "children"): "@collection",
    ("widget", "items"): "@collection",
    ("contents", "items"): "@collection",
    ("wound", "children"): "@collection",
    ("pagina", "children"): "@collection",
    ("meter", "segment"): "@collection",
    ("channel", "message"): "@collection",
    ("fep", "entry"): "@collection",
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
        ent = over[base] if (base in over) else (RECEIVERS.get(base, "?") if whole is None else "?")
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
                    # the receiver may itself be a call, as in `inventory():items()`, so `)` is in the class
                    for pat, how in ((r'#\s*[\w.\[\]"():]*:' + v + r'\(\s*\)', "# on"),
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
#   - open emitters (hafen.console(), keybindings(), action(), message(), pag:on("use"), req:on("done"))
#     are not checked at all. Their key sets are PROTOCOL or user-chosen; there is nothing to check
#     against, and a typo there is the author's own.
#   - a key built from a variable rather than written as a literal is invisible here.
#   - suites under addons/<NNN>-*/ are skipped: they call moved spellings ON PURPOSE, to prove the
#     refusal raises. A suite is re-run every round, which is its own guard.
def event_keys():
    """Every key a CLOSED emitter fires, read out of the bridge's own key sets."""
    def arr(text, name):
        m = re.search(r'String\[\]\s+' + name + r'\s*=\s*\{(.*?)\}', text, re.S)
        return set(re.findall(r'"([A-Za-z]+)"', m.group(1))) if m else set()
    am = io.open(os.path.join(BRIDGE, "AddonManager.java"), encoding="utf-8", errors="replace").read()
    lw = io.open(os.path.join(BRIDGE, "LuaWidget.java"), encoding="utf-8", errors="replace").read()
    live = arr(am, "BUS_KEYS") | arr(lw, "UNIVERSAL_KEYS") | arr(lw, "SURFACE_KEYS")
    # widgetKeys() adds these by interface rather than from an array, so they are named here.
    live |= {"Pressed", "Changed", "Submitted", "Selected", "Cell", "ItemAdded", "ItemRemoved"}
    live |= {"Added", "Removed"}     # the selector watch, s:ui():on(sel, event, fn)
    live |= {"Move", "Up"}           # the pointer grab, closed to exactly these two
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
            if mode == "suite" and re.search(r'/\d{3}-[^/]+$', rel_dir):
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

    if verbose and skipped:
        print("\n== receivers not mapped (add to RECEIVERS to widen coverage) ==")
        for r, n in skipped.most_common(30):
            print("  %-16s %d" % (r, n))
    return 1 if (bad or arrayish or jbad or kbad) else 0

if __name__ == "__main__":
    sys.exit(main())
