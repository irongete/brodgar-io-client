# 094 — The widget and the thing: plan

## Approach

Eleven rows, one unit, one suite. Almost all of it is additive and almost all of it reads back, so almost
all of it is scored: this is the feature with the fewest manuals of the sweep, because a crossing you can
call is a crossing you can assert.

**Nothing here is a rename.** Every row is a capability the bridge could already answer and did not expose —
`LuaBuff` *is* its widget, `widgetKeys` already builds the event list, `Selector.WIDGET_ROLES` already holds
the vocabulary, `Manifest.SavedVar` already holds the declared set. So the whole feature is closures over
data that was one field away, which is why a `severe` row cost five of them.

## Gotchas found while doing it

**A third empty `Cache(Addon owner) {}`.** `LuaStudySlot`'s took the owner and dropped it on the floor,
exactly as `LuaFood`'s did in 091 and `LuaBinding`'s in 093. Worth a grep before starting any feature that
needs an owner inside a metatable: `grep -n 'Cache(Addon owner) {$' -A 1`.

**`w:match(sel)` is inclusive of `w`.** `selectors.md` says so — "`w` included" — so the first suite drafted
`theirs:match("window") == nil` as the contrast with `w:is(sel)` and it was simply false. The two differ on
a selector that names something **below** `w` only, which is deterministic on a window the suite builds
itself with a label inside.

**`"094"` is a number.** In LuaJ a numeric string passes `isnumber()`, and the overlay key guard is
`!kv.isstring() || kv.isnumber()` precisely to stop a painter being registered under `"42"`. The suite's own
key tripped the guard the bridge put there on purpose.

**A block outside `section()` takes the summary with it.** The manual's overlay setup was not wrapped, so
its error ended the run before `[summary]` printed. Everything a suite does belongs inside a `pcall`.

**`Slot` cannot cross.** See the spec: the belt is one widget for 144 slots. Reading `FKeyBelt.draw` is the
whole of the check.

## Discarded alternatives

- **`slot:widget()` answering the belt.** It would satisfy the row's letter and lie: every index would hand
  back the same widget, and the finding's own use ("place a badge over it") needs the slot's rectangle,
  which the bar cannot give.
- **`w:reads()` beside `w:events()`** — W4's other half. The finding says the borrowed-adapter gap behind it
  "is already queued" on the ROADMAP, and no row of this sweep carries it.
- **`w:matches(sel)`**, the name W6 proposes. **D3** renamed it to `w:is(sel)` when 088 took `w:match(sel)`
  for the search verb; `:match` and `:matches` one letter apart with opposite meanings is the collision the
  rename exists to avoid.
- **A `Role` that is not interned**, as 091's value-objects are. Those have no key; a role's name *is* its
  key, so `hafen.ui():role():get("window") == hafen.ui():role():get("window")` has to hold like every other
  addressable thing. The cache is a plain map rather than a weak one because the set is closed, fixed at
  compile time and never dies.
- **`hafen.ui():role()` answering an array of strings.** The finding names a collection of objects with two
  verbs, and the second one carries the only distinction the list has: a role that classifies a widget
  versus one that names a render site. A string array could not say it.
- **`s:menugrid():get(id)` trying the short id LAST.** Tried first, and only for a name with no `/` — which
  a resource name always has — so nothing that resolved before resolves differently now.
- **Reading an addon's own manifest id**, A-114's other branch. The finding says "the second is more
  generally useful and belongs to a feature. Report the second; propose the first."
