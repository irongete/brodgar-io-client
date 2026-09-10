"""Every per-widget map in `src/io/brodgar/**` states what retires it, and no weak map holds its own key.

128 closed a leak that was six maps wide, and every one of them was found by reading. `Addon.widgetSubs`
kept a `WidgetSubs` -- and every engine listener in it -- for the rest of the session whenever a window
closed; `Layout.dragListeners` and `Gesture.arms` were `WeakHashMap`s whose value was the handler installed
on the very widget it was stored under, so neither could ever collect; `LuaItem.Cache.live` pinned every
`GItem` an addon ever interned. The sixth site is the one nobody is looking for, and the seventh is the map
somebody adds next year.

Two mechanical checks, in the shape tools/docverbs.py and tools/refusalverbs.py are written in.

  CHECK 1 -- every FIELD whose declared type is a map keyed by a `haven.Widget` -- a `*Map<Widget, V>`, or
  the bridge's own `Interned<Widget, V>`, which is a map by another name -- carries a note:

      // retired: <method>      the method that retires it. It must exist, and it must be reachable from
                                AddonManager.drainDisposedWidgets -- called there, or called by something
                                called there. One level, which is as deep as the drain actually goes.
      // retained: <reason>     ...or the reason it is not retired, in words, on the line above the field.

  CHECK 2 -- no `WeakHashMap<K, V>` where `V` declares a field that can hold the `K`. That entry's value
  holds its own key, so it is never weakly unreachable and the declared type promises a collection it
  cannot perform. `V`'s own type arguments are read too, so a `List<Skin>` value is the `Skin`; the one
  exception is a `java.lang.ref` reference, whose whole job is to break the chain, and which is not
  followed.

The key types are not a list kept here: `extends` is read out of `src/**` and closed transitively from two
roots, so `GItem`, `IMeter`, `Buff` and `ChatUI.Channel` are covered because they ARE widgets, and a map
keyed on a widget class written next year is covered because it will be one too. The second root is
`ItemInfo.SpriteOwner`, the engine's name for a thing an icon DRAWS: one of those is either a widget itself
or a field of the widget drawing it, so it dies exactly when a widget dies and a map keyed on it leaks in
exactly the same way.

    python tools/widgetstate.py            # report
    python tools/widgetstate.py --verbose  # ...and list every field with the note it carries

Exit code 1 when a field carries no note, when a `// retired:` note names a method that does not exist or
that the drain does not reach, or when check 2 finds a value holding its key.

WHAT IT CANNOT SEE, stated so the green is not read as more than it is:

  * A map behind a helper or a generic wrapper. Both checks match DECLARED TYPES textually, so a
    `Registry<Widget, X>` of one's own, or a `Map<Object, X>` a widget is put into, is invisible here.
    `Interned` is the one wrapper CHECK 1 is taught by name, because it is where the bridge's own caches
    live; the next wrapper written has to be added beside it or its maps drop out of the count.
  * WHICH STRENGTH an `Interned` was built at. CHECK 2 reads `WeakHashMap<K, V>` declarations, and the only
    one `Interned` writes is over its own type variables -- so what covers an `Interned<Widget, V>` is
    CHECK 1's note and nothing else. The cycle CHECK 2 hunts cannot form at `Interned.identity()`, whose
    value is held weakly; at `Interned.held()` the value holds whatever it likes, and the note is the whole
    answer.
  * A value that reaches its key through a CAPTURED LOCAL or through a Lua closure -- which is how all
    three of the maps that actually leaked did it: `installDragListener(t)` closes over `t` and is stored
    under `t`, `Gesture.listen`'s handler closes over the grip, and an `item:on` handler closes over the
    item it watches. Check 2 is one level deep and reads fields, so it finds NONE of them. Check 1 is what
    covers them, by asking for the retirement rather than for the absence of the cycle.
  * WHETHER THE NAMED METHOD TOUCHES THAT MAP. A `// retired:` note is a claim; this checks that the claim
    is not dead -- the method exists, and the drain reaches it. It matches the method by NAME, so a note
    naming `retire` is satisfied by any reachable `retire`; write `<Type>.<method>` where the bare name is
    ambiguous and the declaring type is checked too.
  * Whether a `// retained:` reason is TRUE. Nothing can check that. What the note buys is that the reason
    was written down once, next to the map, by whoever knew it.
  * A LOCAL, and a map held anywhere but a field. A per-widget record parked in a list, a set or a queue is
    not a map and is not read here.
  * A field typed `Object`, or one whose type this tree does not declare. Check 2 resolves a value class
    against `src/io/brodgar/**` and reads its DECLARED field types; a JDK type is counted and skipped, and
    `Object` is not treated as able to hold the key, because it is and the report would be noise.
  * WHICH of two types share a simple name, once the name is written in neither's file. A value class is
    resolved the way javac resolves it -- its OWN file first, since more than thirty caches in this package
    each declare a private `Ref` -- and only a name declared nowhere in the map's file falls back to every
    declaration of it in the tree, where a hit on any one of them is reported.
"""
import io, os, re, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "src")
BRIDGE = os.path.join(ROOT, "src", "io", "brodgar")
DRAIN_FILE, DRAIN_METHOD = "AddonManager.java", "drainDisposedWidgets"

KEYWORDS = set(("if for while switch catch return new synchronized do else try throw assert super this "
                "instanceof case break continue void int long float double boolean char byte short")
               .split())

MAPDECL = re.compile(r"\b(\w*Map|Interned)\s*<\s*([\w.]+)\s*[,>]")
TYPEDECL = re.compile(r"\b(?:class|interface|enum)\s+(\w+)")
ANONBODY = re.compile(r"\bnew\s+[\w.]+\s*(?:<[^;{}]*>)?\s*\([^;{}]*\)\s*$")
METHDECL = re.compile(r"\b(\w+)\s*\([^()]*\)\s*(?:throws\s[\w.,\s]+)?$")
EXTENDS = re.compile(r"\b(?:class|interface)\s+(\w+)\s*(?:<[^{;]*>)?\s+extends\s+([\w.]+)")
RETIRED = re.compile(r"//\s*retired:\s*([\w.]+)")
RETAINED = re.compile(r"//\s*retained:\s*(\S)")
TRAILING_NAME = re.compile(r"(\w+)\s*$")
FIELD_TYPE = re.compile(r"\b([\w.]+)\s*(?:<[^;]*>)?\s+\w+\s*$")


def javafiles(root):
    for dirpath, _, names in os.walk(root):
        for n in sorted(names):
            if n.endswith(".java"):
                yield os.path.join(dirpath, n)


def read(path):
    return io.open(path, encoding="utf-8", errors="replace").read()


def blank(src):
    """A copy with comments and string/char literals blanked out, line count preserved."""
    out, i, n = [], 0, len(src)
    while i < n:
        c = src[i]
        nxt = src[i + 1] if (i + 1 < n) else ""
        if (c == "/") and (nxt == "/"):
            while (i < n) and (src[i] != "\n"):
                out.append(" ")
                i += 1
        elif (c == "/") and (nxt == "*"):
            while (i < n) and not ((src[i] == "*") and (i + 1 < n) and (src[i + 1] == "/")):
                out.append("\n" if src[i] == "\n" else " ")
                i += 1
            out.append("  ")
            i += 2
        elif (c == '"') or (c == "'"):
            out.append(" ")
            i += 1
            while (i < n) and (src[i] != c):
                if src[i] == "\\":
                    out.append(" ")
                    i += 1
                if i < n:
                    out.append("\n" if src[i] == "\n" else " ")
                    i += 1
            out.append(" ")
            i += 1
        else:
            out.append(c)
            i += 1
    return "".join(out)


def scan(code):
    """Walk one file's braces once, over a blanked copy.

    Returns (fields, methods). A FIELD is a declaration statement whose innermost enclosing brace is a type
    body; a METHOD is a `name(...)` that opens one inside a type body. Both carry the 0-based line their
    text starts on and the name of the type that encloses them.
    """
    fields, methods, stack = [], [], []
    seg, segline, line = [], None, 0

    def enclosing():
        for istype, name in reversed(stack):
            if istype:
                return name
        return None

    for ch in code:
        if ch == "\n":
            line += 1
            seg.append(ch)
            continue
        if ch in "{};":
            body = "".join(seg).strip()
            infield = bool(stack) and stack[-1][0] and bool(body)
            if ch == ";":
                if infield:
                    fields.append((segline, body, enclosing()))
            elif ch == "{":
                m = TYPEDECL.search(body)
                if infield:
                    mm = METHDECL.search(body)
                    if (mm is not None) and (mm.group(1) not in KEYWORDS) and (m is None):
                        methods.append((segline, mm.group(1), enclosing()))
                    else:
                        fields.append((segline, body, enclosing()))   # a field with a braced initialiser
                istype = (m is not None) or (ANONBODY.search(body) is not None)
                stack.append((istype, m.group(1) if m else enclosing()))
            elif stack:
                stack.pop()
            seg, segline = [], None
            continue
        if (segline is None) and not ch.isspace():
            segline = line
        seg.append(ch)
    return fields, methods


def widget_types():
    """Every type in src/** that dies with a widget -- a `haven.Widget`, or an `ItemInfo.SpriteOwner`, which
    is drawn by one -- closed transitively over `extends`, and the graph."""
    parent = {}
    for path in javafiles(SRC):
        for m in EXTENDS.finditer(blank(read(path))):
            parent.setdefault(m.group(1), m.group(2).split(".")[-1])
    types, changed = set(["Widget", "SpriteOwner"]), True
    while changed:
        changed = False
        for child, up in parent.items():
            if (up in types) and (child not in types):
                types.add(child)
                changed = True
    return types, parent


def holds(fieldtype, key, parent):
    """Can a field declared `fieldtype` hold a `key`? It is the key's type, or one it inherits from."""
    seen, t = set(), key
    while (t is not None) and (t not in seen):
        if t == fieldtype:
            return True
        seen.add(t)
        t = parent.get(t)
    return False


def bodies_of(code, name):
    """Every body of a method called `name` in one blanked file, as source text."""
    out = []
    for m in re.finditer(r"\b" + re.escape(name) + r"\s*\(", code):
        i = code.find("{", m.start())
        if (i < 0) or (";" in code[m.start():i]):
            continue
        depth, j = 0, i
        while j < len(code):
            if code[j] == "{":
                depth += 1
            elif code[j] == "}":
                depth -= 1
                if depth == 0:
                    break
            j += 1
        out.append(code[i:j])
    return out


def calls(text):
    return set(m.group(1) for m in re.finditer(r"\b(\w+)\s*\(", text)) - KEYWORDS


def reachable(files):
    """What the disposal drain calls, plus what THOSE call. One level, as advertised."""
    drain = None
    for rel, code in files:
        if rel.endswith("/" + DRAIN_FILE):
            found = bodies_of(code, DRAIN_METHOD)
            if found:
                drain = found[-1]
    if drain is None:
        return None, None
    direct = calls(drain)
    reach = set(direct)
    for _, code in files:
        for name in direct:
            for b in bodies_of(code, name):
                reach |= calls(b)
    return reach, direct


def declared_methods(files):
    """method name -> the set of types declaring it, over the whole bridge."""
    out = {}
    for _, code in files:
        for _, name, owner in scan(code)[1]:
            out.setdefault(name, set()).add(owner)
    return out


def generic_args(code, lt):
    """The top-level type arguments of the `<...>` opening at index `lt`, and the index after it."""
    depth, j = 0, lt
    while j < len(code):
        if code[j] == "<":
            depth += 1
        elif code[j] == ">":
            depth -= 1
            if depth == 0:
                break
        j += 1
    args, depth, cur = [], 0, []
    for ch in code[lt + 1:j]:
        if ch == "<":
            depth += 1
        elif ch == ">":
            depth -= 1
        if (ch == ",") and (depth == 0):
            args.append("".join(cur).strip())
            cur = []
        else:
            cur.append(ch)
    if "".join(cur).strip():
        args.append("".join(cur).strip())
    return args


def simple(t):
    return t.split("<")[0].split(".")[-1].strip()


def note_region(lines, start):
    """The field's own line, plus the contiguous comment/annotation block directly above it."""
    i = start - 1
    while i >= 0:
        s = lines[i].strip()
        if s.startswith(("//", "*", "/*", "@")) or s.endswith("*/"):
            i -= 1
            continue
        break
    return "\n".join(lines[i + 1:start + 1])


def field_types(code, owner):
    """Every field type declared inside the type named `owner`, as simple names."""
    out = set()
    for _, body, encl in scan(code)[0]:
        if encl != owner:
            continue
        m = FIELD_TYPE.search(body.split("=", 1)[0].strip())
        if m:
            out.add(m.group(1).split(".")[-1])
    return out


def check_notes(files, types, reach, methods):
    """CHECK 1, over every widget-keyed map FIELD in the bridge."""
    seen, missing, dead, listed = 0, [], [], []
    for rel, code, lines in files:
        for start, body, owner in scan(code)[0]:
            left = body.split("=", 1)[0]
            m = MAPDECL.search(left)
            if (m is None) or (simple(m.group(2)) not in types):
                continue
            seen += 1
            name = TRAILING_NAME.search(left.strip())
            where = "%s:%d  %s.%s<%s>" % (rel, start + 1, owner, name.group(1) if name else "?",
                                          simple(m.group(2)))
            region = note_region(lines, start)
            r = RETIRED.search(region)
            if r is None:
                (listed if RETAINED.search(region) else missing).append((where, "retained"))
                continue
            who = r.group(1).split(".")
            meth, ty = who[-1], (who[-2] if len(who) > 1 else None)
            if meth not in methods:
                dead.append((where, r.group(1), "no method of that name in src/io/brodgar"))
            elif (ty is not None) and (ty not in methods[meth]):
                dead.append((where, r.group(1), "declared on " + ", ".join(sorted(methods[meth]))))
            elif meth not in reach:
                dead.append((where, r.group(1), "the disposal drain does not reach it"))
            listed.append((where, "retired: " + r.group(1)))
    return seen, [w for w, _ in missing], dead, listed


def value_classes(expr):
    """`V`'s own class, plus the classes inside its type arguments -- a `java.lang.ref` reference is where
    the walk stops, because breaking the chain to what it points at is the whole of what one is for."""
    head = simple(expr)
    out = [head]
    if head.endswith("Reference"):
        return out
    lt = expr.find("<")
    if lt >= 0:
        for a in generic_args(expr, lt):
            out.extend(value_classes(a))
    return out


def check_weak(files, parent):
    """CHECK 2, over every `WeakHashMap<K, V>` the bridge writes."""
    weak, unresolved, cycles = 0, 0, []
    decls = {}
    for rel, code, _ in files:
        for m in TYPEDECL.finditer(code):
            decls.setdefault(m.group(1), []).append((rel, code))
    for rel, code, _ in files:
        for m in re.finditer(r"\bWeakHashMap\s*<", code):
            args = generic_args(code, code.index("<", m.start()))
            if len(args) != 2:
                continue
            weak += 1
            k, resolved = simple(args[0]), False
            for v in value_classes(args[1]):
                if v not in decls:
                    continue
                resolved = True
                # The name resolves in ITS OWN FILE first, exactly as javac resolves it: a nested `Ref` is
                # private to the cache that declares it, and this package declares one per cache -- so a
                # tree-wide match by simple name reads one class's fields into another class's map.
                here = [d for d in decls[v] if d[0] == rel] or decls[v]
                for vrel, vcode in here:
                    for ft in field_types(vcode, v):
                        if holds(ft, k, parent):
                            cycles.append((rel, code.count("\n", 0, m.start()) + 1, k, v, ft, vrel))
                            break
            if not resolved:
                unresolved += 1
    return weak, unresolved, cycles


def main():
    verbose = "--verbose" in sys.argv
    types, parent = widget_types()
    files = []
    for p in javafiles(BRIDGE):
        src = read(p)
        files.append((os.path.relpath(p, ROOT).replace("\\", "/"), blank(src), src.splitlines()))
    plain = [(rel, code) for rel, code, _ in files]

    reach, direct = reachable(plain)
    if reach is None:
        print("FATAL: %s.%s not found -- the drain this tool is written around has moved"
              % (DRAIN_FILE, DRAIN_METHOD))
        return 1
    methods = declared_methods(plain)

    seen, missing, dead, listed = check_notes(files, types, reach, methods)
    print("%d widget-keyed map fields, over %d types that die with a widget" % (seen, len(types)))
    print("the disposal drain calls %d methods directly and reaches %d in all" % (len(direct), len(reach)))
    if missing:
        print("\n== %d field(s) with neither a `// retired:` nor a `// retained:` note ==" % len(missing))
        for w in missing:
            print("  " + w)
    else:
        print("every one of them says what retires it, or why nothing does")
    if dead:
        print("\n== %d `// retired:` note(s) the drain cannot reach ==" % len(dead))
        for w, who, why in dead:
            print("  %s\n      names `%s` -- %s" % (w, who, why))

    weak, unresolved, cycles = check_weak(files, parent)
    print("\nchecked %d WeakHashMap<K, V> declarations; %d had a V this tree does not declare"
          % (weak, unresolved))
    if cycles:
        print("== %d weak map(s) whose VALUE can hold its own KEY (the entry can never collect) ==" % len(cycles))
        for rel, ln, k, v, ft, vrel in cycles:
            print("  %s:%d  WeakHashMap<%s, %s>\n      %s declares a `%s` field (%s)" % (rel, ln, k, v, v, ft, vrel))
    else:
        print("no weak map holds its own key")

    if verbose:
        print("\n== every widget-keyed field, and the note it carries ==")
        for where, note in sorted(listed):
            print("  %-64s %s" % (where, note))

    return 1 if (missing or dead or cycles) else 0


if __name__ == "__main__":
    sys.exit(main())
