"""Resolve every verb a refusal in the bridge NAMES against the receiver it names it on.

A refusal message is a promise: *this is gone, write that instead* -- or *use a colon call, like this*. Nothing
checked that `that` exists, and nineteen of them named a verb the receiver had lost in a rename, so following
the message landed on a second refusal instead of on the verb it promised.

The check is the same shape as tools/docverbs.py and for the same reason: a message that names a verb on
the wrong type is invisible to any check that only asks whether the NAME exists somewhere. This one is
receiver-typed: every `x:verb(` a message spells is resolved against x's OWN vocabulary.

    python tools/refusalverbs.py            # report
    python tools/refusalverbs.py --verbose  # ...and list the receivers and hops it could not map

Exit code 1 when a message names a verb its receiver does not answer, and exit code 1 when the scan resolves
NOTHING -- a coverage that collapses to zero is what let a stale promise stand for a feature and a half, so
zero is red rather than a green over nothing.

WHAT IT READS. Every `*.java` under src/io/brodgar/addon/, as Java rather than as lines:

  * Every string EXPRESSION -- literals joined by `+` across any number of lines -- is evaluated. A named
    `String` constant resolves, in its own file or as `CharApi.ST` from another; a `String`-returning helper
    whose body is one `return <expr>;` (`hafen(nm)`) is evaluated on its arguments; a ternary yields both arms.
    Anything else is an opaque hole, and a mention that runs through a hole is not a mention.
  * A message BUILT BY A HELPER is read at every call of the helper. `handle(self, "isNew")` throws
    `"pagina:" + method + "() -- use a COLON call"`, and the label is the verb the message promises: the
    helper's `String` parameters are bound from each call site (through a helper that calls a helper, three
    deep) and the message it would build is resolved exactly as a literal one is. So a label that names a
    verb the entity does not install fails here, at the call site that wrote it.
  * The refusal TABLE in Refusal.java (MOVED, KEYS, MISPLACED) is read row by row, including the rows a
    builder puts in a loop; the key IS the moved spelling, so its own mention in the message is skipped and
    everything else the row names is a promise. A KEYS row's promise is an event key, resolved against the
    key sets the bridge fires (docverbs.event_keys).

WHAT IT RESOLVES AGAINST. Three kinds of receiver, each declared in one place in the bridge:

  * An ENTITY, `Refusal.closedIndex("<entity>", …)`, whose verbs are the `.set("x", …)` calls of that file.
  * A SECTION, `Section.install(hafen, "<name>", …)` / `.mount(…)` (spelled `hafen.<name>()`) or
    `Section.object("<name>", m, HOW)` (spelled as HOW evaluates: `session:char()`).
  * A COLLECTION, `LuaCollection.create(<spelling>, …)`: the core six plus the file's `extra` verbs -- and
    NOT the enumerating three where its `members()` opens with a `throw`, because `hafen.sound():list()`
    is a refusal too, and a message that names it sends the reader to a second one.

  A chain (`hafen.session():current():ui():root(`) is walked hop by hop: a hop is resolved where the next
  spelling is itself declared (`session` + `ui` -> `session:ui()`), through the RETURNS map docverbs keeps for
  an entity's crossings (`gob:kin()` -> kin), and through MEMBER below for what a collection hands out.

WHAT IT CANNOT SEE, stated so the green is not read as more than it is:

  * A vocabulary is per FILE. A file that declares several receivers (CharApi declares a dozen sections)
    lends every `.set` in it to each of them, so a verb named on one section but installed on its neighbour
    in the same file resolves. The miss this tool exists for -- a verb no longer installed anywhere near --
    is caught; a verb on the wrong sibling of one file is not.
  * A receiver it has no name for. Single-letter receivers (`p`, `w`, `g`, `h`) mean different things in
    different messages and are skipped, as docverbs skips them; `s` resolves only where `session:<hop>()`
    is a declared spelling. `--verbose` lists what was skipped, so the map's coverage is visible.
  * A verb written BARE, with no receiver (`:reset() needs profiling armed`), and a verb named in a comment
    -- a comment may legitimately name a dead spelling ("there is no gob:move()"), a message may not.
  * A collection spelled at runtime. `LuaCollection.create(verb, …)` with `verb` a local is not a spelling
    this can read; the four such collections are named by hand in HAND_SPELLINGS, which is a list to keep.
    A verb set from an enum's field (`m.set(kind.word, …)`) is the same blindness one level down, and the
    four such verbs are named in HAND_VERBS.
  * A hole in an expression hides the mention that ran through it: `coll.name + ":" + verb + "()"` is not
    a mention, because neither half is known here. Such a message is resolved at the call that BUILT the
    name, where the literal was written -- which is the whole reason every `LuaCollection.create` spelling
    is a literal or a constant.
"""
import io, os, re, sys, collections

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import docverbs

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BRIDGE = os.path.join(ROOT, "src", "io", "brodgar", "addon")

# The receiver spelling a message uses -> the entity that owns that vocabulary, where the spelling is not the
# entity's own name. docverbs' page map is reused; a message writes fuller chains than a page does, so the
# rest of the resolution is the walk below rather than that map.
RECEIVERS = dict(docverbs.MSG_RECEIVERS)
RECEIVERS.update({
    "seg": "segment", "cat": "iconcat", "man": "maneuver", "card": "deckcard", "member": "partymember",
    "cond": "condition", "condition": "condition", "sub": "sub", "opts": "options", "options": "options",
    "profiling": "profiling", "miss": "miss", "petal": "petal", "binding": "binding", "sheet": "sheet",
    "rule": "rule", "channel": "channel", "msg": "message", "message": "message", "font": "font",
    "d": None, "toggle": "toggle", "slot": "slot", "pag": "pagina", "pagina": "pagina", "hand": "hand",
    "entry": "fepentry", "e": None, "exp": "experience", "skill": "skill", "credo": "credo",
    "buff": "buff", "meter": "meter", "wound": "wound", "quest": "quest", "kin": "kin", "gob": "gob",
    "marker": "marker", "item": "item", "contents": "contents", "widget": "widget", "asset": "asset",
    "req": "request", "res": "res", "role": "role", "grid": "grid", "segment": "segment", "mask": "mask",
    "overlay": "overlay", "ov": None, "sound": "sound", "timer": "timer", "session": "session",
    "position": "position", "speed": "speed", "sp": None, "attr": "attr", "food": "food", "fep": "fep",
    "hunger": "hunger", "spec": "craftspec", "craft": None, "summary": None, "opponent": "opponent",
    "placing": "placing", "pl": "placing", "mouse": "mouse", "m": None, "ghost": "@virtual",
    "sprite": "@virtual", "object": "@virtual", "patch": "@virtual", "panel": "@virtual", "w": None, "g": None, "p": None, "h": None, "b": None,
    "c": None, "s": "@session", "t": None, "v": None, "x": None, "r": None, "k": None, "q": None,
    "ev": None, "f": None, "a": None, "n": None, "u": None, "self": None, "this": None,
})

# A receiver word that means another type in ONE file: the study window's slot is a StudySlot, and the two UI
# overlays share a word with the gob's. Keyed by file; a value of None skips the word there.
PER_FILE = {
    "LuaStudySlot.java": {"slot": "studyslot"},
    "LuaWidgetOverlay.java": {"overlay": "widgetoverlay"},
    "LuaHudOverlay.java": {"overlay": "uioverlay"},
}

# What a collection hands out (`:get`, `:find`, `:add`, `:list()[n]`): the entity a hop lands on.
MEMBER = {
    "hafen.session()": "session", "session:world():gob()": "gob", "session:kin()": "kin",
    "session:actionbar()": "slot", "session:menugrid()": "pagina", "session:buff()": "buff",
    "session:meter()": "meter", "session:wound()": "wound", "session:quest()": "quest",
    "session:party()": "partymember", "session:speed()": "speed", "session:chat()": "channel",
    "session:flowermenu()": "petal", "session:study():curiosity()": "studyslot",
    "session:fight():deck()": "deckcard", "session:fight():maneuver()": "maneuver",
    "session:char():skill()": "skill", "session:char():credo()": "credo", "session:char():attr()": "attr",
    "session:char():experience()": "experience", "hafen.map():marker()": "marker",
    "hafen.map():segment()": "segment", "hafen.map():grid()": "grid", "hafen.map():icon()": "iconcat",
    "hafen.map():display()": "toggle", "hafen.asset()": "asset", "hafen.font()": "font",
    "hafen.sound()": "sound", "hafen.timer()": "timer", "hafen.http()": "request",
    "widget:children()": "widget", "widget:items()": "item", "contents:items()": "item",
    "channel:message()": "message", "hafen.locale():miss()": "miss", "seg:grid()": "grid",
    "segment:markers()": "marker", "grid:mask()": "mask", "quest:conditions()": "condition",
    "wound:children()": "wound", "session:wound():roots()": "wound", "gob:overlay()": "overlay",
    "keybindings:binding()": "binding", "session:char():skill():buyable()": "skill",
}

# Hops that are neither a declared spelling nor an entity crossing docverbs knows.
HOPS = {
    ("hafen.session()", "current"): "session",
    ("session", "player"): "session:player()",
    ("session:player()", "hand"): "hand",
    ("session:player()", "gob"): "gob",
    ("hafen.client()", "options"): "options",
    ("hafen.client()", "keybindings"): "keybindings",
    ("keybindings", "binding"): "keybindings:binding()",
    ("hafen.ui()", "window"): "widget", ("hafen.ui()", "widget"): "widget", ("hafen.ui()", "hit"): "widget",
    ("session:ui()", "match"): "widget", ("session:ui()", "root"): "widget", ("session:ui()", "node"): "widget",
    ("session:ui()", "inventory"): "widget", ("session:ui()", "equipment"): "widget",
    ("session:world():gob()", "nearest"): "gob",
    ("widget", "overlay"): "widget:overlay()",
    ("hafen.map():segment()", "current"): "segment",
    ("session:speed()", "current"): "speed",
    ("session:quest()", "selected"): "quest",
    ("session:party()", "leader"): "partymember",
    ("session:char():credo()", "pursuing"): "credo",
    ("session:chat()", "selected"): "channel",
    ("session:menugrid()", "roots"): "session:menugrid():roots()",
    ("session:wound()", "roots"): "session:wound():roots()",
    ("hafen.virtual():entity()", "list"): None,
    ("meter", "segment"): "meter:segment()",
    ("hafen.font()", "get"): "font",
    ("font", "derive"): "font",
    ("segment", "grid"): "seg:grid()",
    ("options", "addon"): "addon options", ("options", "keybindings"): "keybindings",
    ("options", "video"): "video", ("options", "audio"): "audio", ("options", "camera"): "camera",
    ("options", "client"): "client", ("options", "interface"): "interface",
    ("hafen.ui()", "sheet"): "sheet", ("hafen.ui()", "overlay"): "hafen.ui():overlay()",
    ("session:char()", "food"): "food", ("partymember", "gob"): "gob", ("session", "craft"): "session:craft()",
    ("hafen.client()", "profiling"): "profiling", ("client", "profiling"): "profiling",
}
# every control hafen.ui() mints is a widget of yours
for _c in ("window", "widget", "button", "label", "entry", "check", "radio", "slider", "scroll", "scrollbar",
           "dropdown", "menu", "listbox", "table", "grid", "image", "progress", "separator"):
    HOPS[("hafen.ui()", _c)] = "widget"

# Collections whose spelling is built at runtime (a local, a parameter) -- named here, a list to keep.
HAND_SPELLINGS = {
    "session:craft()": "LuaCraft.java", "session:craft():inputs()": "LuaCraft.java",
    "session:craft():outputs()": "LuaCraft.java", "session:craft():qualityInputs()": "LuaCraft.java",
    "session:craft():tools()": "LuaCraft.java", "session:wound():roots()": "LuaWound.java",
    "wound:children()": "LuaWound.java", "session:menugrid():roots()": "LuaPagina.java",
    "pagina:children()": "LuaPagina.java",
    # the five virtual kinds share one handle whose closedIndex is built from a `kind` variable
    "@virtual": "VirtualApi.java",
}
# Verbs installed from an ENUM's field rather than a literal -- `m.set(kind.word, …)` over LuaOption.Kind --
# which no literal scan can see. Named by hand, beside the list above, and a list to keep.
HAND_VERBS = {
    "addon options": {"boolean", "number", "choice", "text"},
}
COLLECTION_CORE = {"list", "count", "find", "get", "add", "remove"}
ENUMERATE = {"list", "count", "find"}

# ----------------------------------------------------------------------------- reading Java, as Java

def blank_comments(src):
    """`src` with every comment replaced by spaces -- offsets, and so line numbers, are unchanged."""
    out = list(src)
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        if (c == '"') or (c == "'"):
            i += 1
            while i < n and src[i] != c:
                i += 2 if (src[i] == "\\") else 1
            i += 1
        elif src.startswith("//", i):
            while i < n and src[i] != "\n":
                out[i] = " "
                i += 1
        elif src.startswith("/*", i):
            while i < n and not src.startswith("*/", i):
                if src[i] != "\n":
                    out[i] = " "
                i += 1
            for j in range(i, min(i + 2, n)):
                out[j] = " "
            i += 2
        else:
            i += 1
    return "".join(out)

def literal_spans(code):
    """(start, end) of every string literal, end exclusive -- the one token the rest of the reading skips."""
    out, i, n = [], 0, len(code)
    while i < n:
        c = code[i]
        if c == "'":
            i += 1
            while i < n and code[i] != "'":
                i += 2 if (code[i] == "\\") else 1
            i += 1
        elif c == '"':
            j = i + 1
            while j < n and code[j] != '"':
                j += 2 if (code[j] == "\\") else 1
            out.append((i, j + 1))
            i = j + 1
        else:
            i += 1
    return out

def scan(code, i, closers="", stop=""):
    """From `i`, the index just past the balanced text -- stopping on `stop` at depth 0, strings skipped."""
    depth, n = 0, len(code)
    while i < n:
        c = code[i]
        if c == '"':
            i += 1
            while i < n and code[i] != '"':
                i += 2 if (code[i] == "\\") else 1
            i += 1
            continue
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
            if (depth == 0) and (c in closers):
                return i + 1
            if depth < 0:
                return i
        elif (depth == 0) and (c in stop):
            return i
        i += 1
    return n

def split_top(expr, sep):
    """`expr` split on `sep` at depth 0, outside string literals."""
    parts, buf, depth, i, n = [], [], 0, 0, len(expr)
    while i < n:
        c = expr[i]
        if c == '"':
            j = i + 1
            while j < n and expr[j] != '"':
                j += 2 if (expr[j] == "\\") else 1
            buf.append(expr[i:j + 1])
            i = j + 1
            continue
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        if (c == sep) and (depth == 0):
            parts.append("".join(buf))
            buf = []
        else:
            buf.append(c)
        i += 1
    parts.append("".join(buf))
    return parts

LITERAL = re.compile(r'^"((?:[^"\\]|\\.)*)"$', re.S)
# A method header: modifiers, a type with no space outside its generics, the name, a parameter list holding
# no parentheses, an optional throws clause, the opening brace. Written without a lazy class over spaces,
# which backtracks across a whole file.
METHOD = re.compile(r'(?:^|[\s;}])(?:(?:private|protected|public|static|final|synchronized|abstract)\s+)*'
                    r'([\w.]+(?:<[^<>{};()]*(?:<[^<>{};()]*>[^<>{};()]*)*>)?(?:\[\])*)\s+(\w+)\s*\(([^(){};]*)\)'
                    r'\s*(?:throws [\w., ]+)?\{')
ESCAPE = {"n": "\n", "t": "\t", "r": "\r"}
IDENT = re.compile(r'^[A-Za-z_][\w.]*$')
CALL = re.compile(r'^([A-Za-z_][\w.]*)\s*\((.*)\)$', re.S)
HOLE = "\0"

def unquote(lit):
    return re.sub(r'\\(.)', lambda e: ESCAPE.get(e.group(1), e.group(1)), lit)

class Reader(object):
    """One file, read once: its constants, its String helpers, its methods, and the chains in each."""

    def __init__(self, name, src):
        self.name = name
        self.code = blank_comments(src)
        self.spans = literal_spans(self.code)
        self.consts = {}
        self.strfuncs = {}      # name -> (params, expr)
        self.methods = []       # (name, params, start, end) -- params as [(type, name)]
        self.lit_end = dict(self.spans)
        self.calls = set(re.findall(r'(?<![\w.])(\w+)\s*\(', self.code))
        self.qualified = set(re.findall(r'(\w+)\.(\w+)\s*\(', self.code))
        self._chains = {}
        self._read()
        self.builders = [(mn, ps, s, e) for mn, ps, s, e in self.methods if any(t == "String" for t, _ in ps)]

    # ---- what the file declares
    def _read(self):
        code = self.code
        for m in re.finditer(r'\bString\s+(\w+)\s*=\s*', code):
            self.consts[m.group(1)] = code[m.end():scan(code, m.end(), stop=";")]
        for m in METHOD.finditer(code):
            rtype, name, params = m.group(1), m.group(2), m.group(3)
            if name in ("if", "for", "while", "switch", "catch", "synchronized", "return", "new", "else"):
                continue
            if rtype in ("new", "return", "else", "throw"):
                continue
            end = scan(code, m.end() - 1, closers="}")
            ps = []
            for p in split_top(params, ","):
                p = p.strip()
                if p:
                    bits = p.replace("final ", "").split()
                    ps.append((bits[0], bits[-1]))
            self.methods.append((name, ps, m.start(), end))
            body = code[m.end():end - 1].strip()
            if (rtype == "String") and body.startswith("return ") and body.endswith(";") and (body.count(";") == 1):
                self.strfuncs[name] = ([n for _, n in ps], body[len("return "):-1])

    def innermost(self, pos):
        best = None
        for meth in self.methods:
            if meth[2] <= pos < meth[3] and ((best is None) or (meth[2] > best[2])):
                best = meth
        return best

    # ---- chains: every string expression, once
    def chains(self, lo=0, hi=None):
        """(start, end, text) of every maximal `+` chain holding a literal, between lo and hi."""
        code = self.code
        hi = len(code) if hi is None else hi
        if (lo, hi) in self._chains:
            return self._chains[(lo, hi)]
        out, taken = [], -1
        ends = dict((e, s) for s, e in self.spans)
        for s, e in self.spans:
            if (s < lo) or (e > hi) or (s < taken):
                continue
            start = self._left(s, ends)
            end = self._right(start)
            out.append((start, end, code[start:end]))
            taken = end
        self._chains[(lo, hi)] = out
        return out

    def _left(self, pos, ends):
        code = self.code
        while True:
            i = pos
            while i > 0 and code[i - 1].isspace():
                i -= 1
            if i == 0 or code[i - 1] != "+" or (i >= 2 and code[i - 2] == "+"):
                return pos
            i -= 1
            while i > 0 and code[i - 1].isspace():
                i -= 1
            j = self._operand_back(i, ends)
            if j is None:
                return pos
            pos = j

    def _operand_back(self, i, ends):
        """The start of the operand ending at index i (exclusive), or None."""
        code = self.code
        if i == 0:
            return None
        c = code[i - 1]
        if c == '"':
            return ends.get(i)
        if c == ")":
            depth, k = 0, i - 1
            while k >= 0:
                ch = code[k]
                if ch == '"':
                    k = ends[k + 1] if (k + 1) in ends else k
                    k -= 1
                    continue
                if ch == ")":
                    depth += 1
                elif ch == "(":
                    depth -= 1
                    if depth == 0:
                        break
                k -= 1
            if k < 0:
                return None
            j = k
            while j > 0 and code[j - 1].isspace():
                j -= 1
            m = j
            while m > 0 and (code[m - 1].isalnum() or code[m - 1] in "_."):
                m -= 1
            return m if m < j else k
        if c.isalnum() or c == "_":
            j = i
            while j > 0 and (code[j - 1].isalnum() or code[j - 1] in "_."):
                j -= 1
            return j
        return None

    def _right(self, start):
        code, n = self.code, len(self.code)
        i = start
        while True:
            i = self._operand_fwd(i)
            if i is None:
                return start
            j = i
            while j < n and code[j].isspace():
                j += 1
            if j < n and code[j] == "+" and code[j + 1:j + 2] != "+":
                k = j + 1
                while k < n and code[k].isspace():
                    k += 1
                nxt = self._operand_fwd(k)
                if nxt is None:
                    return i
                i = k
                continue
            return i

    def _operand_fwd(self, i):
        """The end (exclusive) of the operand starting at i, or None."""
        code, n = self.code, len(self.code)
        if i >= n:
            return None
        c = code[i]
        if c == '"':
            return self._lit_end(i)
        if c == "(":
            return scan(code, i, closers=")")
        if c.isalnum() or c == "_":
            j = i
            while j < n and (code[j].isalnum() or code[j] in "_."):
                j += 1
            k = j
            while k < n and code[k].isspace():
                k += 1
            if k < n and code[k] == "(":
                return scan(code, k, closers=")")
            return j
        return None

    def _lit_end(self, i):
        return self.lit_end.get(i)

def operands(expr):
    return [p.strip() for p in split_top(expr, "+") if p.strip()]

def evaluate(expr, env, files, here, depth=0):
    """Every string `expr` can be, with `env` binding names -- a hole for anything unreadable."""
    variants = [""]
    for part in operands(expr):
        alts = eval_operand(part, env, files, here, depth)
        variants = [v + a for v in variants for a in alts][:8]
    return variants

def eval_operand(part, env, files, here, depth):
    m = LITERAL.match(part)
    if m:
        return [unquote(m.group(1))]
    if part.startswith("(") and part.endswith(")"):
        inner = part[1:-1].strip()
        q = split_top(inner, "?")
        if len(q) == 2:
            arms = split_top(q[1], ":")
            if len(arms) == 2:
                return evaluate(arms[0], env, files, here, depth) + evaluate(arms[1], env, files, here, depth)
        return evaluate(inner, env, files, here, depth)
    if IDENT.match(part):
        if part in env:
            return [env[part]]
        if part in here.consts:
            return evaluate(here.consts[part], dict(env), files, here, depth)
        if "." in part:
            cls, _, name = part.rpartition(".")
            other = files.get(cls + ".java")
            if other and name in other.consts:
                return evaluate(other.consts[name], {}, files, other, depth)
        return [HOLE]
    c = CALL.match(part)
    if c and depth < 3:
        name, args = c.group(1), c.group(2)
        cls, _, fn = name.rpartition(".")
        target = files.get(cls + ".java") if cls else here
        if target and fn in target.strfuncs:
            params, body = target.strfuncs[fn]
            actual = [a.strip() for a in split_top(args, ",") if a.strip()]
            if len(actual) == len(params):
                bound = {}
                for p, a in zip(params, actual):
                    vals = evaluate(a, env, files, here, depth + 1)
                    bound[p] = vals[0] if len(vals) == 1 else HOLE
                return evaluate(body, bound, files, target, depth + 1)
        return [HOLE]
    if re.match(r'^\d[\w.]*$', part):
        return [part]
    return [HOLE]

# ----------------------------------------------------------------------------- the vocabularies

def vocabularies(files):
    """spelling -> verbs, for every entity, section and collection the bridge declares in one place."""
    vocab, enumerates, file_verbs, declared_in = {}, {}, {}, {}
    for name, r in sorted(files.items()):
        code = r.code
        verbs = set(re.findall(r'\b\w+\.set\(\s*"([A-Za-z_]\w*)"\s*,', code))
        # a verb set through a helper's String parameter -- collection(m, "ghost", …) -- is a verb at each
        # literal the helper is called with
        for mn, ps, s, e in r.methods:
            body = code[s:e]
            for pm in re.finditer(r'\.set\(\s*(\w+)\s*,', body):
                idx = [i for i, (t, n) in enumerate(ps) if (n == pm.group(1)) and (t == "String")]
                if not idx:
                    continue
                for cm in re.finditer(r'(?<![\w.])' + re.escape(mn) + r'\s*\(', code):
                    args = [a.strip() for a in split_top(code[cm.end():scan(code, cm.end() - 1, closers=")") - 1], ",")]
                    if len(args) > idx[0] and LITERAL.match(args[idx[0]]):
                        verbs.add(unquote(LITERAL.match(args[idx[0]]).group(1)))
        spellings = set()
        for m in re.finditer(r'closedIndex\(\s*([^,()]+?)\s*,', code):
            ent = evaluate(m.group(1), {}, files, r)[0]
            if HOLE not in ent:
                spellings.add(ent.lower())
        for m in re.finditer(r'(?<![\w.])(?:OptionsHandle\.)?close\(', code):   # an options handle's vocabulary
            args = [a.strip() for a in split_top(code[m.end():scan(code, m.end() - 1, closers=")") - 1], ",")]
            if len(args) >= 2 and LITERAL.match(args[1]):
                spellings.add(unquote(LITERAL.match(args[1]).group(1)))
        for m in re.finditer(r'Section\.(?:install|mount)\(\s*\w+\s*,\s*"(\w+)"', code):
            spellings.add("hafen." + m.group(1) + "()")
        for m in re.finditer(r'Section\.object\(\s*"(\w+)"\s*,\s*([^,()]+?)\s*(?:,\s*([^()]+?))?\s*\)', code):
            how = evaluate(m.group(3), {}, files, r)[0] if m.group(3) else ("hafen." + m.group(1) + "()")
            if HOLE not in how:
                spellings.add(how)
        for m in re.finditer(r'LuaCollection\.create\(', code):
            args, end = split_top(code[m.end():scan(code, m.end() - 1, closers=")") - 1], ","), None
            sp = evaluate(args[0], {}, files, r)[0] if args else HOLE
            if HOLE in sp:
                continue
            spellings.add(sp)
            body = code[m.end():m.end() + 4000]
            mm = re.search(r'members\(\)\s*\{\s*(\w+)', body)
            enumerates[sp] = not (mm and mm.group(1) == "throw")
        for sp in spellings:
            vocab.setdefault(sp, set()).update(verbs)
            declared_in[sp] = name
            if sp.startswith("s:"):                   # a section spelled `s:console()` is `session:console()`
                vocab.setdefault("session" + sp[1:], set()).update(verbs)
                enumerates["session" + sp[1:]] = enumerates.get(sp, True)
        file_verbs[name] = verbs
    for sp, f in HAND_SPELLINGS.items():
        vocab.setdefault(sp, set()).update(file_verbs.get(f, set()))
    for sp, vs in HAND_VERBS.items():
        vocab.setdefault(sp, set()).update(vs)
    for sp in list(vocab):
        if sp.endswith("()"):
            vocab[sp] |= COLLECTION_CORE
            if not enumerates.get(sp, True):
                vocab[sp] -= (ENUMERATE - file_verbs.get(declared_in.get(sp), set()))
    return vocab

MENTION = re.compile(r'(?<![\w.:])(hafen\.[A-Za-z_]\w*\(\)|[A-Za-z_]\w*)((?::[A-Za-z_]\w*\([^()]*\))*):([A-Za-z_]\w*)\(')
STEP = re.compile(r':([A-Za-z_]\w*)\(')

def hop(spelling, step, vocab):
    """The spelling one hop on, or None where this cannot say what `step` hands back."""
    nxt = spelling + ":" + step + "()"
    if nxt in vocab:
        return nxt
    if (spelling, step) in HOPS:
        return HOPS[(spelling, step)]
    if (spelling in MEMBER) and (step in ("get", "find", "add", "nearest", "current")):
        return MEMBER[spelling]
    r = docverbs.RETURNS.get((spelling, step))
    if r and not r.startswith("@"):
        return r
    return None

def resolve(text, vocab, where=None):
    """(spelling, verb, receiver-or-None, ok) for every receiver-typed call a message spells."""
    out = []
    over = PER_FILE.get(where, {})
    for m in MENTION.finditer(text):
        base, mid, verb = m.group(1), m.group(2) or "", m.group(3)
        if base in over:
            sp = over[base]
        elif base.startswith("hafen."):
            sp = base if base in vocab else None
        elif base in vocab:
            sp = base
        else:
            sp = RECEIVERS.get(base, "?")
            if sp == "@session":
                sp = "session"
            if sp == "?":
                sp = None
        if sp is None:
            out.append((base, verb, None, False))
            continue
        spelling = sp
        literal = base
        for step in STEP.findall(mid):
            literal += ":" + step + "()"
            spelling += ":" + step + "()"
            if literal in vocab:
                sp = literal
                continue
            sp = hop(sp, step, vocab)
            if sp is None:
                break
        if sp is None:
            out.append((spelling, verb, None, False))
            continue
        known = vocab.get(sp)
        if not known:
            out.append((spelling, verb, None, False))
            continue
        out.append((spelling, verb, sp, verb in known))
    return out

# ----------------------------------------------------------------------------- the messages

def messages(files):
    """(file, line, text, how) for every string expression a refusal could be, with helpers instantiated."""
    out = []
    for name, r in sorted(files.items()):
        if name == "Refusal.java":
            continue
        for start, end, text in r.chains():
            line = r.code.count("\n", 0, start) + 1
            meth = r.innermost(start)
            params = set(n for t, n in meth[1] if t == "String") if meth else set()
            if params & set(op for op in operands(text) if IDENT.match(op)):
                continue                                   # read at each call of its helper, below
            for v in evaluate(text, {}, files, r):
                out.append((name, line, v, "a literal"))
        # every call of a helper with String parameters, from wherever it is made
        for callee, r2 in sorted(files.items()):
            cls = callee[:-5]
            for mn, ps, s, e in r2.builders:
                arity = len(ps)
                if (r2 is r) and (mn not in r.calls):
                    continue
                if (r2 is not r) and ((cls, mn) not in r.qualified):
                    continue
                pat = (r'(?<![\w.])' + re.escape(mn) + r'\s*\(') if (r2 is r) else (re.escape(cls) + r'\.' + re.escape(mn) + r'\s*\(')
                for cm in re.finditer(pat, r.code):
                    args = split_top(r.code[cm.end():scan(r.code, cm.end() - 1, closers=")") - 1], ",")
                    args = [a.strip() for a in args if a.strip()]
                    if len(args) != arity:
                        continue
                    if (r2 is r) and (s <= cm.start() < e):
                        continue                           # a recursive call inside its own body
                    inner = r.innermost(cm.start())
                    if inner and any(t == "String" for t, _ in inner[1]) and inner[0] != mn:
                        continue                           # bound when THAT helper is instantiated
                    line = r.code.count("\n", 0, cm.start()) + 1
                    out.extend(instantiate(files, r2, (mn, ps, s, e), args, r, {}, line, name, 0))
    return out

def instantiate(files, r2, meth, args, caller, env, line, site, depth):
    """The messages a helper builds for one call of it, and of the helpers it calls with them."""
    mn, ps, s, e = meth
    bound = {}
    for (t, pn), a in zip(ps, args):
        if t != "String":
            continue
        vals = evaluate(a, env, files, caller, depth)
        bound[pn] = vals[0] if len(vals) == 1 else HOLE
    if all(v == HOLE for v in bound.values()):
        return []
    out = []
    params = set(bound)
    for start, end, text in r2.chains(s, e):
        if not (params & set(op for op in operands(text) if IDENT.match(op))):
            continue
        for v in evaluate(text, bound, files, r2, depth):
            out.append((site, line, v, mn + "()"))
    if depth >= 3:
        return out
    body = r2.code[s:e]
    calls = set(re.findall(r'(?<![\w.])(\w+)\s*\(', body))
    qualified = set(re.findall(r'(\w+)\.(\w+)\s*\(', body))
    for r3name, r3 in files.items():
        for mn3, ps3, s3, e3 in r3.builders:
            if (r3 is r2) and (mn3 == mn):
                continue
            if (r3 is r2) and (mn3 not in calls):
                continue
            if (r3 is not r2) and ((r3name[:-5], mn3) not in qualified):
                continue
            pat = (r'(?<![\w.])' + re.escape(mn3) + r'\s*\(') if (r3 is r2) else (re.escape(r3name[:-5]) + r'\.' + re.escape(mn3) + r'\s*\(')
            for cm in re.finditer(pat, body):
                a3 = [x.strip() for x in split_top(body[cm.end():scan(body, cm.end() - 1, closers=")") - 1], ",") if x.strip()]
                if len(a3) != len(ps3):
                    continue
                if not any(set(op for op in operands(x) if IDENT.match(op)) & params for x in a3):
                    continue
                out.extend(instantiate(files, r3, (mn3, ps3, s3, e3), a3, r2, bound, line, site, depth + 1))
    return out

# ----------------------------------------------------------------------------- the table in Refusal.java

def rows(r, files):
    """Every refusal ROW Refusal.java declares, as (line, key, message, how): a literal put, or one a
    builder puts at each of its calls (a call standing inside a for(String c : new String[]{…}) yields one
    row per value)."""
    code = r.code
    loops = []
    for m in re.finditer(r'\bfor\s*\(\s*String\s+(\w+)\s*:\s*new\s+String\s*\[\s*\]\s*\{', code):
        arr = scan(code, m.end() - 1, closers="}")
        values = [unquote(LITERAL.match(v.strip()).group(1)) for v in split_top(code[m.end():arr - 1], ",") if LITERAL.match(v.strip())]
        i = arr
        while code[i:i + 1].isspace():
            i += 1
        i += 1 if (code[i:i + 1] == ")") else 0
        while code[i:i + 1].isspace():
            i += 1
        end = scan(code, i, closers="}") if (code[i:i + 1] == "{") else (scan(code, i, stop=";") + 1)
        loops.append((m.start(), end, m.group(1), values))

    def bind(pos, base):
        envs = [dict(base)]
        for a, b, name, values in loops:
            if a <= pos < b:
                envs = [dict(e, **{name: v}) for e in envs for v in values]
        return envs

    builders = [(mn, ps, s, e) for mn, ps, s, e in r.methods if ".put(" in code[s:e] or ".put (" in code[s:e]]
    out, unread = [], []
    def emit(pos, args, env, how):
        line = code.count("\n", 0, pos) + 1
        if len(args) != 2:
            unread.append(line)
            return
        k, v = evaluate(args[0], env, files, r), evaluate(args[1], env, files, r)
        if (HOLE in k[0]) or any(HOLE in x for x in v):
            unread.append(line)
            return
        for msg in v:
            out.append((line, k[0], msg, how))
    for m in re.finditer(r'\b\w+\.put\s*\(', code):
        inner = r.innermost(m.start())
        if inner and inner in builders:
            continue
        args = [a.strip() for a in split_top(code[m.end():scan(code, m.end() - 1, closers=")") - 1], ",")]
        for env in bind(m.start(), {}):
            emit(m.start(), args, env, "a literal put")
    for mn, ps, s, e in builders:
        body = code[s:e]
        puts = [[a.strip() for a in split_top(body[p.end():scan(body, p.end() - 1, closers=")") - 1], ",")]
                for p in re.finditer(r'\b\w+\.put\s*\(', body)]
        for cm in re.finditer(r'(?<![\w.])' + re.escape(mn) + r'\s*\(', code):
            if s <= cm.start() < e:
                continue
            args = [a.strip() for a in split_top(code[cm.end():scan(code, cm.end() - 1, closers=")") - 1], ",") if a.strip()]
            if len(args) != len(ps):
                continue
            for env in bind(cm.start(), {}):
                bound = dict(env)
                ok = True
                for (t, pn), a in zip(ps, args):
                    vals = evaluate(a, env, files, r)
                    if len(vals) != 1 or HOLE in vals[0]:
                        ok = False
                    bound[pn] = vals[0]
                if not ok:
                    unread.append(code.count("\n", 0, cm.start()) + 1)
                    continue
                for pa in puts:
                    emit(cm.start(), pa, bound, mn + "()")
    out.sort()
    return out, sorted(set(unread))

# ----------------------------------------------------------------------------- the check

def main():
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(errors="replace")
    verbose = "--verbose" in sys.argv
    files = {}
    for f in sorted(os.listdir(BRIDGE)):
        if f.endswith(".java"):
            files[f] = Reader(f, io.open(os.path.join(BRIDGE, f), encoding="utf-8", errors="replace").read())
    vocab = vocabularies(files)
    live_keys = docverbs.event_keys()

    bad, checked, skipped, sample = [], 0, collections.Counter(), {}
    seen = set()

    def check(where, line, text, skip_key=None):
        nonlocal checked
        for spelling, verb, ent, ok in resolve(text, vocab, where):
            if skip_key and (spelling + ":" + verb == skip_key):
                continue
            if ent is None:
                skipped[spelling] += 1
                sample.setdefault(spelling, "%s:%d  %s" % (where, line, " ".join(text.replace(HOLE, "…").split())[:90]))
                continue
            sig = (where, line, spelling, verb)
            if sig in seen:
                continue
            seen.add(sig)
            checked += 1
            if not ok:
                bad.append((where, line, spelling, verb, ent, " ".join(text.replace(HOLE, "…").split())[:120]))

    # the table
    found, unread = rows(files["Refusal.java"], files)
    keybad = []
    for line, key, text, how in found:
        if "|" in key:
            dead = key.split("|", 1)[1]
            promised = [k for k in re.findall(r'"([A-Z][A-Za-z]+)"', text) if k != dead]
            for k in promised:
                if k not in live_keys:
                    keybad.append((line, k, " ".join(text.split())[:120]))
            check("Refusal.java", line, text, skip_key=None)
        else:
            check("Refusal.java", line, text, skip_key=key)
    by = collections.Counter(how for _, _, _, how in found)
    print("%d refusal row(s) in Refusal.java: %s" % (len(found), ", ".join("%d from %s" % (n, h) for h, n in sorted(by.items())) or "none"))
    if unread:
        print("%d put(s) this could not evaluate, at Refusal.java:%s -- that many rows are NOT covered below"
              % (len(unread), ",".join(str(l) for l in unread)))

    # the messages
    msgs = messages(files)
    for where, line, text, how in msgs:
        check(where, line, text)
    print("read %d string expression(s) across %d files, %d of them through a helper's call"
          % (len(msgs), len(files), sum(1 for m in msgs if m[3] != "a literal")))
    print("checked %d receiver-typed mention(s) against %d vocabularies (%d skipped: receiver or hop not mapped)"
          % (checked, len(vocab), sum(skipped.values())))

    rc = 0
    if not found:
        print("\n== no refusal row found at all ==")
        print("  Either Refusal.java declares none, or a builder puts them that this scanner cannot evaluate.")
        rc = 1
    if keybad:
        print("\n== %d KEYS row(s) promising an event key the bridge does not fire ==" % len(keybad))
        for line, k, text in keybad:
            print("  Refusal.java:%d  \"%s\"\n      %s" % (line, k, text))
        rc = 1
    if bad:
        bad.sort()
        print("\n== %d mention(s) naming a verb the receiver does not answer ==" % len(bad))
        for where, line, spelling, verb, ent, text in bad:
            print("  %s:%d  %s:%s()  -- `%s` has no such verb\n      %s" % (where, line, spelling, verb, ent, text))
        rc = 1
    if checked == 0:
        print("\n== nothing resolved: every mention ran through a hole or an unmapped receiver -- not a green ==")
        rc = 1
    if rc == 0:
        print("every verb a refusal names is one its receiver answers")
    if verbose and skipped:
        print("\n== receivers and hops not mapped (add to RECEIVERS/HOPS/MEMBER to widen coverage) ==")
        for r, n in skipped.most_common(40):
            print("  %-40s %d   %s" % (r, n, sample[r]))
    return rc

if __name__ == "__main__":
    sys.exit(main())
