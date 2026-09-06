"""Resolve every verb a `Refusal` message NAMES AS THE REPLACEMENT against the type it names it on.

A refusal message is a promise: *this is gone, write that instead*. Nothing checked that `that` exists.
Three did not, and following the message landed on a second refusal instead of on the verb it promised.

The check is the same shape as tools/docverbs.py and for the same reason: a message that names a verb on
the wrong type is invisible to any check that only asks whether the NAME exists somewhere.

    python tools/refusalverbs.py

Exit code 1 when a message names a verb its receiver does not answer, and exit code 1 when the scanner
finds NO ROW AT ALL -- a coverage that collapses to nothing is what let a stale promise stand for a
feature and a half, so zero rows is red rather than a green over nothing.

A ROW IS NOT ALWAYS WRITTEN AS A ROW. Two of `Refusal.java`'s twenty-five arrive as a literal
`put("<key>", "<message>")`; the rest are built by a HELPER -- `uiKept(verb, why)` concatenates its key
and fills a template, sixteen of those from a loop over control names -- and a scanner matching a literal
`put("…",` saw one row where the file declares twenty-five. So the helper is not RECOGNISED, it is
EVALUATED: every `void`-returning method whose body carries a `put` is read as a row builder, its call
sites are found, their arguments (and any `for(String c : new String[]{…})` that binds one) are resolved
against the file's own `String` constants, and the key and the message the helper would build are handed
to the resolver exactly as a literal row's are. The next helper of that shape is picked up with no edit
here; the next one of a different shape drops the row count, which is now red.

WHAT IT CANNOT SEE, stated so the green is not read as more than it is:

  * A promise written as a SECTION verb. `hafen.ui():window(…)` and `s:console():run(line)` are what
    every row today promises, and a section's vocabulary is not a `closedIndex` one -- it lives in the
    method table the section object closes over -- so there is nothing here to resolve them against.
    That, and not a blind scanner, is why the MENTION count is zero while the ROW count is twenty-five:
    the resolver is dormant, not blind. Seed a row that promises an ENTITY verb the receiver has not
    got and it fires, through the helper as readily as through a literal.
  * A chain that hops through a section. `s:ui():window(` roots at a Session and hops through `s:ui()`,
    so the walk stops at that hop and the verb is never typed. `docverbs.RETURNS` walks the hops it
    knows; a section is not one of them.
  * A replacement written BARE, with no receiver. `uiKept`'s template ends by listing the session half
    of `hafen.ui` as `:match, :matchAll, :on, :root, :node, :inventory and :equipment` -- seven verbs
    naming no type, so nothing resolves them.
  * A MOVED row, because there are none: that map was emptied on the argument that nothing is
    published, and the entity-verb promise (`credo:cost()`) it used to carry is the shape this resolves.
  * A verb called ON A COLLECTION outside the core six, and a receiver this file's TAIL does not map --
    both inherited from `docverbs.resolve_line`, which does the resolving.
"""
import io, os, re, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import docverbs

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BRIDGE = os.path.join(ROOT, "src", "io", "brodgar", "addon")

# The receiver spelling a message uses -> the entity that owns that vocabulary. Same map as docverbs',
# kept separate because a message writes fuller chains (`session:char():credo()`) than a page does.
TAIL = {
    "credo": "credo", "skill": "skill", "meter": "meter", "buff": "buff", "food": "food",
    "hunger": "hunger", "fep": "fep", "wound": "wound", "quest": "quest", "kin": "kin",
    "gob": "gob", "marker": "marker", "item": "item", "contents": "contents", "pagina": "pagina",
    "widget": "widget", "sheet": "sheet", "rule": "rule", "petal": "petal", "binding": "binding",
    "request": "request", "res": "res", "role": "role", "segment": "segment", "grid": "grid",
}

def vocabularies():
    out = {}
    for f in sorted(os.listdir(BRIDGE)):
        if not f.endswith(".java"):
            continue
        src = io.open(os.path.join(BRIDGE, f), encoding="utf-8", errors="replace").read()
        ents = re.findall(r'closedIndex\(\s*"([^"]+)"', src)
        if not ents:
            continue
        verbs = set(re.findall(r'\b\w+\.set\(\s*"([A-Za-z_]\w*)"\s*,', src))
        for e in ents:
            out.setdefault(e.lower(), set()).update(verbs)
    return out

# ----------------------------------------------------------------------------- reading Java, as Java

def blank_comments(src):
    """`src` with every comment replaced by spaces — offsets, and so line numbers, are unchanged.

    A comment is not a row: the class javadoc spells `s:ui():window(…)` to explain the mechanism, and a
    scanner that reads it as a declaration counts a row nobody wrote.
    """
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

def scan(code, i, closers="", stop=""):
    """From `i`, the index just past the balanced text — stopping on `stop` at depth 0, strings skipped."""
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
    """`expr` split on `sep` at depth 0, outside string literals — a message writes both in its prose."""
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
ESCAPE = {"n": "\n", "t": "\t", "r": "\r"}

def jeval(expr, env):
    """A Java String expression — literals and named `String`s joined by `+` — or None if a part is unknown."""
    out = []
    for part in split_top(expr, "+"):
        m = LITERAL.match(part.strip())
        if m:
            out.append(re.sub(r'\\(.)', lambda e: ESCAPE.get(e.group(1), e.group(1)), m.group(1)))
        elif part.strip() in env:
            out.append(env[part.strip()])
        else:
            return None
    return "".join(out)

STRDEF = re.compile(r'\bString\s+(\w+)\s*=\s*')
METHOD = re.compile(r'(?:^|[\s;}])(?:(?:private|protected|public|static|final)\s+)*void\s+(\w+)\s*\(([^)]*)\)\s*\{')
PUT = re.compile(r'\b\w+\.put\s*\(')
FOREACH = re.compile(r'\bfor\s*\(\s*String\s+(\w+)\s*:\s*new\s+String\s*\[\s*\]\s*\{')

def constants(code):
    """Every `String <name> = <literals joined by +>;` in the file — a template's shared clause lives here."""
    env = {}
    for m in STRDEF.finditer(code):
        v = jeval(code[m.end():scan(code, m.end(), stop=";")], env)
        if v is not None:
            env[m.group(1)] = v
    return env

def loops(code):
    """Every `for(String c : new String[]{…})` as (start, end, name, values) — sixteen rows are one of these.

    `end` is the end of the loop's own BODY, braced or bare. Reading it as anything wider hands the
    binding to the statement after the loop, which is how one literal row reads as sixteen.
    """
    out = []
    for m in FOREACH.finditer(code):
        arr = scan(code, m.end() - 1, closers="}")            # past the array literal
        values = [jeval(v, {}) for v in split_top(code[m.end():arr - 1], ",")]
        i = arr
        while (i < len(code)) and code[i].isspace():
            i += 1
        i += 1 if (code[i:i + 1] == ")") else 0               # the `)` that closes the for
        while (i < len(code)) and code[i].isspace():
            i += 1
        end = scan(code, i, closers="}") if (code[i:i + 1] == "{") else (scan(code, i, stop=";") + 1)
        out.append((m.start(), end, m.group(1), [v for v in values if v is not None]))
    return out

def call_args(code, i):
    """The arguments of the call whose `(` is at `i`, split at depth 0, and the index past its `)`."""
    end = scan(code, i, closers=")")
    return split_top(code[i + 1:end - 1], ","), end

def rows(src):
    """Every refusal ROW the file declares, as (line, key, message, how) — plus what it could not read.

    A row is a `<map>.put(<key>, <message>)`. Where that `put` sits inside a `void` method, the method is
    a BUILDER: its parameters are bound from each of its call sites and the key and the message it would
    build are synthesised, which is the only way the twenty-four `uiKept` rows are rows at all. A call
    site standing inside a `for(String c : new String[]{…})` yields one row per value.

    A `put` whose two halves do not evaluate to strings is COUNTED rather than dropped: a row this cannot
    read is coverage lost, and losing it quietly is the whole of what went wrong here before.
    """
    code = blank_comments(src)
    env = constants(code)
    loop = loops(code)
    builders, taken = [], []
    for m in METHOD.finditer(code):
        end = scan(code, m.end() - 1, closers="}")
        body, at = code[m.start():end], m.start()
        puts = [call_args(body, p.end() - 1)[0] for p in PUT.finditer(body)]
        if not puts:
            continue
        params = [a.strip().split()[-1] for a in m.group(2).split(",") if a.strip()]
        builders.append((m.group(1), params, puts))
        taken.append((at, end))

    def outside(pos):
        return all(not (a <= pos < b) for a, b in taken)

    def bind(pos, base):
        """`base` plus one binding per enclosing loop — a cartesian product, so a nest still expands."""
        envs = [dict(base)]
        for a, b, name, values in loop:
            if a <= pos < b:
                envs = [dict(e, **{name: v}) for e in envs for v in values]
        return envs

    out, unread = [], []
    def emit(pos, args, e, how):
        line = code.count("\n", 0, pos) + 1
        key = msg = None
        if (e is not None) and (len(args) == 2):
            key, msg = jeval(args[0], e), jeval(args[1], e)
        if (key is None) or (msg is None):
            unread.append(line)
        else:
            out.append((line, key, msg, how))

    for m in PUT.finditer(code):
        if outside(m.start()):
            for e in bind(m.start(), env):
                emit(m.start(), call_args(code, m.end() - 1)[0], e, "a literal put")
    for name, params, puts in builders:
        for m in re.finditer(r'\b' + re.escape(name) + r'\s*\(', code):
            if not outside(m.start()):
                continue
            actual = call_args(code, m.end() - 1)[0]
            for e in bind(m.start(), env):
                vals = [jeval(a, e) for a in actual]
                ok = (len(vals) == len(params)) and (None not in vals)
                for args in puts:
                    emit(m.start(), args, dict(e, **dict(zip(params, vals))) if ok else None, name + "()")
    out.sort()
    return out, sorted(set(unread))

# -------------------------------------------------------------------------------------- the check

def main():
    # A refusal message is prose, and every one of these carries an em-dash. A Windows console is cp1252,
    # so printing a finding verbatim would raise instead of reporting it — exactly when the tool has
    # something to say. Reading is already errors="replace"; writing has to be too.
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(errors="replace")
    vocab = vocabularies()
    src = io.open(os.path.join(BRIDGE, "Refusal.java"), encoding="utf-8", errors="replace").read()
    found, unread = rows(src)
    bad, checked = [], 0
    for lineno, key, text, _ in found:
        # The key IS the moved spelling, so a mention of it inside its own message is the thing that
        # moved rather than the replacement. Everything else the message names is a promise.
        dead = key.split(":")[-1].split(".")[-1]
        deadrecv = key.split(":")[-2].split(".")[-1].replace("()", "") if (":" in key) else ""
        # A CHAINED receiver -- `credo:pursuing():cost()` -- goes through docverbs' resolver, which walks
        # the chain through its RETURNS map. Taking the identifier next to the colon would read `pursuing`
        # as the type and skip the mention, and that was the blind spot: two messages advertised
        # `credo:cost()` as the replacement while this check printed green.
        for spelling, verb, ent, ok in docverbs.resolve_line(text, TAIL, vocab, whole=True):
            base = spelling.split(":")[0]
            if (verb == dead) and ((base == deadrecv) or (base == key.split(":")[0]) or (deadrecv == "")):
                continue                              # the row naming itself
            if ent is None:
                continue
            checked += 1
            if not ok:
                snippet = " ".join(text.split())[:120]
                bad.append((lineno, spelling, verb, ent, snippet))

    by = {}
    for _, _, _, how in found:
        by[how] = by.get(how, 0) + 1
    print("%d refusal row(s) in Refusal.java: %s"
          % (len(found), ", ".join("%d from %s" % (n, h) for h, n in sorted(by.items())) or "none"))
    if unread:
        print("%d put(s) this could not evaluate, at Refusal.java:%s -- that many rows are NOT covered below"
              % (len(unread), ",".join(str(l) for l in unread)))
    if not found:
        print("\n== no refusal row found at all ==")
        print("  Either Refusal.java declares none, or a builder puts them that this scanner cannot")
        print("  evaluate. A check whose coverage collapses to nothing is not a green -- see the")
        print("  module docstring for the shape it reads.")
        return 1
    print("checked %d receiver-typed replacement mention(s) against %d vocabularies" % (checked, len(vocab)))
    if bad:
        print("\n== %d message(s) naming a replacement the receiver does not answer ==" % len(bad))
        for lineno, recv, verb, ent, text in bad:
            print("  Refusal.java:%d  %s:%s()  -- `%s` has no such verb\n      %s" % (lineno, recv, verb, ent, text))
        return 1
    if checked == 0:
        print("no row promises a verb on a type this can resolve: every one of them names a SECTION verb,")
        print("and a section's vocabulary is not a closedIndex one. The resolver is dormant, not blind --")
        print("the module docstring says what it would take to wake it.")
    else:
        print("every replacement a refusal names is a verb that type answers")
    return 0

if __name__ == "__main__":
    sys.exit(main())
