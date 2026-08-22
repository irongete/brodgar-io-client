"""Resolve every verb a `Retired` message NAMES AS THE REPLACEMENT against the type it names it on.

A retirement message is a promise: *this is gone, write that instead*. Nothing checked that `that` exists.
Three did not -- `session:char():credo():cost` pointed at `credo:pursuing():cost()`, which `LuaCredo` does
not register, so following the message landed on a second refusal; and two `hafen.ui.window` rows advertise
`:onDraw(fn)` / `:onTick(fn)` / `:onDrop(fn)` / `:onClose(fn)`, which are themselves retirements.

The check is the same shape as tools/docverbs.py and for the same reason: a message that names a verb on
the wrong type is invisible to any check that only asks whether the NAME exists somewhere.

    python tools/retiredverbs.py

Exit code 1 when a message names a verb its receiver does not answer.
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

def statements(src):
    """Each `put("<key>", "<message>")` as (line number, key, whole text) — messages span several lines."""
    out = []
    starts = [(m.start(), m.group(1)) for m in re.finditer(r'put\(\s*"([^"]+)"\s*,', src)]
    for n, (pos, key) in enumerate(starts):
        end = starts[n + 1][0] if (n + 1 < len(starts)) else len(src)
        # A comment between two puts falls inside the earlier slice, and a comment is not a message.
        body = "\n".join(l for l in src[pos:end].splitlines()
                         if not l.strip().startswith(("//", "*", "/*")))
        out.append((src.count("\n", 0, pos) + 1, key, body))
    return out

def main():
    vocab = vocabularies()
    src = io.open(os.path.join(BRIDGE, "Retired.java"), encoding="utf-8", errors="replace").read()
    bad, checked = [], 0
    for lineno, key, text in statements(src):
        # The key IS the retired spelling, so a mention of it inside its own message is the thing being
        # retired rather than the replacement. Everything else the message names is a promise.
        dead = key.split(":")[-1].split(".")[-1]
        deadrecv = key.split(":")[-2].split(".")[-1].replace("()", "") if (":" in key) else ""
        # A CHAINED receiver -- `credo:pursuing():cost()` -- goes through docverbs' resolver, which walks
        # the chain through its RETURNS map. Taking the identifier next to the colon would read `pursuing`
        # as the type and skip the mention, and that was the blind spot: two messages advertised
        # `credo:cost()` as the replacement while this check printed green.
        for spelling, verb, ent, ok in docverbs.resolve_line(text, TAIL, vocab, whole=True):
            base = spelling.split(":")[0]
            if (verb == dead) and ((base == deadrecv) or (base == key.split(":")[0]) or (deadrecv == "")):
                continue                              # the retirement naming itself
            if ent is None:
                continue
            checked += 1
            if not ok:
                snippet = " ".join(text.split())[:120]
                bad.append((lineno, spelling, verb, ent, snippet))

    print("checked %d receiver-typed replacement mentions in Retired.java" % checked)
    if bad:
        print("\n== %d message(s) naming a replacement the receiver does not answer ==" % len(bad))
        for lineno, recv, verb, ent, text in bad:
            print("  Retired.java:%d  %s:%s()  -- `%s` has no such verb\n      %s" % (lineno, recv, verb, ent, text))
        return 1
    print("every replacement a retirement names is a verb that type answers")
    return 0

if __name__ == "__main__":
    sys.exit(main())
