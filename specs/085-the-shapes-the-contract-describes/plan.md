# 085 — The shapes the contract describes: plan

## Approach

Five tasks. One is the page structure, three are shape changes in the bridge, one is identity.

**The page task goes first, and it writes today's truth.** `conventions.md` is at exactly 300 lines
and this feature adds six rows to it, so `DOCUMENTATION.md` §11.2 forces the split in the task that
writes it. Doing that split first means every later task lands its page rows in a file with headroom
instead of re-splitting under a deadline. It also means task 1 writes the anonymous-shape table with
`w:size()` in the `{x=, y=}` row, because that is what the client answers on the day task 1 commits;
**task 3 moves that row.** Each commit is a tree whose pages are true — that is the rule, and a task
that pre-documented a change would break it.

### 1 — Where a shape is written down

Create `docs/addons/api/shapes.md`. Move `conventions.md` §Coordinates and §Colours into it verbatim,
and `types.md` §Color with them, folded into §Colours. The headings do not change, so **the slugs do
not change either** — `#coordinates` and `#colours` survive the move and only the page half of every
anchor is re-pointed. `types.md#color` becomes `shapes.md#colours`, which is the one link whose text
also moves.

Then the rules that are already true and written nowhere, or written in the wrong place:

- **The anonymous shapes**, as one table on `shapes.md`: `{x=, y=}` a place in a lattice
  (`item:cell()`, `p:tileCoord()`, `grid:segmentCoord()`, `marker:segmentTile()`), `{x=, y=}` a
  screen pixel (`widget:position()`, `widget:rootPos()`, `s:player():worldToScreen(p)`, `ev:pixel(i)`),
  `{w=, h=}` a size (`widget:cell()`, `img:size()`, `mapImg:size()` — and `widget:size()`, which task
  3 moves out of the pixel row), `{cur=, max=}` a pair of counts (`item:durability()`,
  `contents:level()`), `{x=, y=, z=}` a world span (`mdl:bounds()`). Say plainly that a place and a
  pixel wear the same table and only the verb tells them apart.
- **Ids too big for a number**, under §Coordinates: `p:info().gridId`, `grid:id()`, `seg:id()` and a
  marker's `seg` are 64-bit values as **decimal strings**, because a Lua number cannot hold one
  exactly. This is said in three places today and contradicted in a fourth — `types.md` §Position
  reads `{ gridId = number, x = number, y = number }`, and the bridge (`LuaPosition`'s `info`,
  `LuaMapGrid`'s `id`, `LuaSegment`'s `id`, all `Long.toString`) is right. Fix the page.
- **Units**, on `shapes.md`: a name ending `Fraction` is `0..1`. Then `buff:duration()`,
  `slot:cooldown()`, `w:severity()` and `slot:time()` stop correcting the reader in bold on their own
  pages and link here instead.
- **The stylesheet path exception**, in `conventions.md` §A table is a value: a stylesheet is a
  *document*, so it names a file by path (`{asset = "img/panel.png"}`); everywhere a Lua call takes a
  file it takes the handle. `asset.md`'s two paragraphs shorten to a link.
- **The third value category**, in `conventions.md` §Snapshots vs handles: beside a snapshot and a
  handle there is a **table the bridge owns and you write into** — what `hafen.store():get(name)`
  hands back. It is not a copy, it is not a proxy, and it is the one place in the API where a typo on
  a key is silent and even persisted.

`api/README.md` gains a `shapes.md` row and its `conventions.md` row drops "coordinates, colours".

### 2 — One colour

**Out.** Delete `AddonManager.colorValue` and point its three readers at `AddonManager.color`:
`FontApi`'s `color` property read, `LuaOverlay`'s `ov:color()` read (through its own one-line
`colorValue` wrapper, which goes with it), and `VrApi`'s `e:tint()` read. Nothing else calls it.

**In.** Three parsers each carry the same loose branch; all three lose it and raise instead:

| Parser | Verbs behind it |
|---|---|
| `AddonManager.colorArg(a, i, verb)` | `font:color`, `overlay:color` (via `LuaOverlay`'s wrapper), a vr `:tint` |
| `LuaMarker.colorArg(a)` | `marker:color` |
| `LuaRule.colorArg(a, i, verb)` | `rule:color` and every colour a sheet rule sets |

`AddonManager.luaColor(t, dflt)` is untouched — keyed first, positional table as the fallback — and
stays the one place a colour table is read. The refusal each parser raises names the table:
`overlay:color(color): a colour is a table — {200, 210, 220} or {r = 200, g = 210, b = 220[, a]},
0..255 each, or a colour value read back from the API`.

**The draw context** is the exception and gains the half it never had. `LuaGOut`'s `g:color` reads
`a.arg(2)` with `toint()`; give it a table branch first (`AddonManager.luaColor`, refusing a table
that is not a colour) and leave the loose components untouched. That is the whole of
`g:color(kin:color())` drawing black. `g:text`'s `opts.color` already goes through `luaColor` and
needs nothing.

**`Chrome.seqShape`'s `positional` flag** is the sheet's own record of which spelling a colour arrived
in, used to refuse a sequence where a single colour was written positionally. It survives: a
positional **table** is still a spelling. Check its call sites still pass the right flag once the
loose form is gone from `LuaRule.colorArg`.

### 3 — One size

Add `LuaWidget.whTable(Coord)`, the `{w=, h=}` twin of `xyTable`, and point the four size readers at
it: `LuaWidget`'s `size` verb, the `size` key of `LuaWidget`'s `:info()` snapshot, `LuaRule`'s `size`
read, and `Sheet`'s two `size` snapshot keys. `xyTable` keeps every position, offset, anchor and
`rootPos` — it is the pixel/place helper and it is right for those.

`AssetApi.meshHandle`'s `bounds` renames its `size` key to `extent`, keeping `min` and `max` and the
`{x, y, z}` shape.

**A retired key on a table raises.** `Retired` grows a sibling of `closedIndex` for data tables:

```java
/** A retired FIELD of an anonymous shape: {@code size.x} → the message naming {@code .w}. */
private static void field(String shape, String old, String message) { put(shape + "." + old, message); }

/** The {@code __index} of a shape table: every key it does not carry raises, a retired one by name. */
static LuaValue closedFields(final String shape, final String hint) { … }
```

`__index` is consulted only for keys the table does **not** carry, so `t.w` reads the real field and
`t.x` falls through and raises. One metatable per shape, built once as a static and shared by every
table of that shape, so a `w:size()` inside a draw callback costs one extra field write and no
allocation. Two shapes get one: `size` (`.x` → "a size is `{w=, h=}`") and `bounds` (`.size` →
"`mdl:bounds()` names its span `extent`; `.min` and `.max` are the corners").

**The write side round-trips.** `rule:size(t)` goes through `LuaRule.coordArg` → `Layout.parseCoord`,
which reads `x`/`y` or `[1]`/`[2]`. For `prop == "size"` it must read `w`/`h` or `[1]`/`[2]`, and an
`{x=, y=}` under `size` must raise naming `w`/`h` rather than being taken. `Sheet`'s document path
uses the same helper, so the JSON and Lua sheet spellings move together — and nothing under `docs/`
or `addons/` writes `size = {x = …}` today, so the only cost is the error text and two page rows.
`w:size(w, h)` takes no table at all and is unaffected.

### 4 — The answer is the thing

**`season()`.** `WorldApi.installTime`'s `season` closure returns `Astronomy.is` raw. Upstream names
the seasons nowhere: `Cal` is the only reader and it uses `is` to index four textures
(`Tex[4] dlnd`, `gfx/hud/calendar/dayscape-<i>`), and `Glob`'s `"astro"` branch defaults it to `1`
when the server omits the field. So the bridge carries a four-entry table, `0..3`, and an index
outside it answers `nil` — as does the whole verb before the first astro update, which is already
true. **Which index is which is an observation, not a fact in the source**: the suite prints the raw
index beside the name it chose and a `[manual]` asks the maintainer what the calendar shows. The page
row is written after that comes back, and the table is reordered first if it disagrees.

**`s:study():summary()`.** New `LuaStudySummary`, a copy of `LuaFightSummary`'s shape: a
`private final SAttrWnd.StudyInfo si` field, `of(Addon owner, SAttrWnd.StudyInfo si)` delegating to a
per-addon `Cache` on `Addon` (an `IdentityHashMap` keyed by the widget plus a `ReferenceQueue`, the
same `Cache` inner class), `:lp()`, `:attention()`, `:cost()` reading `si.texp`, `si.tw`, `si.tenc`,
plus `:exists()`, `:info()` and a `__tostring` of `StudySummary()`. `Retired.closedIndex` for the
vocabulary. `CharApi.studySummary(user)` becomes `LuaStudySummary.of(owner, studyInfo(user))` and
keeps answering `NIL` when there is no window — **exactly** what `s:fight():summary()` does, which is
the point of the row. `types.md`'s "returns the live totals `{ lp, attention, cost }`" becomes the
`:info()` shape.

**`w:severity()`.** `LuaWound.severityOf(w)` produces the string the client painted. Split the verb:
`severity` parses it (a Lua-style number parse — `Double.parseDouble` inside a try, `nil` when it does
not parse) and `label` returns the string unchanged. `wound.md` gains the `:label()` row and its
blockquote stops apologising; `types.md` §Wound keeps `severity` as the client's own spelling in the
snapshot and gains nothing, because a snapshot field keeps the client's spelling by the rule at the
top of that page — state which verb reads it, as every other row there does.

### 5 — The same handle every time

`Addon` grows seven lazily-built fields beside `subMeta` and `grabMeta`, and follows their pattern
exactly — `if(owner.x != null) return owner.x;` with no lock, because two threads racing build two
equal values and one wins, which is what the existing metatable fields already accept.
`OptionsHandle.install`'s `options` and `profiling` closures and `OptionsHandle.create`'s six
sub-closures hand back the held value instead of calling `create()`.

The handles are stateless proxies over the client's live preference stores — `client/README.md` says
so and it stays true — so there is nothing to invalidate and nothing to tear down; the field is
dropped with the `Addon`.

`FontApi.property`'s write path accepts an explicit `nil` on `size` and `aa` alone, clearing
`FontHandle.size` / `FontHandle.aa` back to the surface's stock, and the ownership guard
(`draft`/`used`) applies to it exactly as to any other write. `conventions.md`'s nil table gains the
two rows; `font.md`'s two property rows say the write, not only the read.

## Files to create/modify

**New:**

- `docs/addons/api/shapes.md` (1)
- `src/io/brodgar/addon/LuaStudySummary.java` (4)
- `addons/085-the-shapes-the-contract-describes.1` … `.5` — the five suites

**Bridge**, all under `src/io/brodgar/addon/` unless said:

| File | Change | Task |
|---|---|---|
| `AddonManager` | delete `colorValue`; `colorArg` loses its loose branch and renames its message | 2 |
| `LuaOverlay` | delete its `colorValue` wrapper; `ov:color()` reads through `AddonManager.color` | 2 |
| `FontApi` | the `color` property read uses `AddonManager.color`; `property`'s write accepts `nil` for `size`/`aa` | 2, 5 |
| `VrApi` | `e:tint()` reads through `AddonManager.color` | 2 |
| `LuaMarker` | `colorArg(Varargs)` loses its loose branch | 2 |
| `LuaRule` | `colorArg` loses its loose branch; the `size` read uses `whTable` | 2, 3 |
| `LuaGOut` | `g:color` gains a table branch before the components | 2 |
| `Chrome` | check `seqShape`'s `positional` call sites after the loose form goes | 2 |
| `LuaWidget` | add `whTable`; the `size` verb and `:info()`'s `size` key use it | 3 |
| `Sheet` | its two `size` snapshot keys use `whTable` | 3 |
| `Layout` | `parseCoord` reads `w`/`h` for `prop == "size"`, and raises on `x`/`y` there | 3 |
| `AssetApi` | `meshHandle`'s `bounds`: `size` → `extent` | 3 |
| `Retired` | add `field(...)` and `closedFields(...)`; register the two retired keys | 3 |
| `WorldApi` | `installTime`'s `season` maps `Astronomy.is` through a four-name table | 4 |
| `CharApi` | `studySummary` returns `LuaStudySummary.of(...)` | 4 |
| `Addon` | a `LuaStudySummary.Cache` field; seven lazy client-handle fields | 4, 5 |
| `LuaWound` | `severity` parses; new `label` verb; the `closedIndex` hint gains it | 4 |
| `OptionsHandle` | `install` and `create` hand back the held handles | 5 |
| `ProfHandle`, `AudioOptions`, `CameraOptions`, `ClientOptions`, `InterfaceOptions`, `VideoOptions`, `KeybindingsOptions` | their `create` is called once, from `Addon` | 5 |

**Pages** — the list and the row each task owns is in `spec.md` §Docs impact. `docs/client/state.md`
gains the `Astronomy.is` gotcha (4).

**Consumers:** `addons/eventstack/main.lua` reads `w:size()` at four sites — `tall(w)` reads `sz.y`,
and three call sites read `l:size().x` / `le:size().x` — all four become `.h` / `.w` (3).
`addons/profiler` and `addons/clickpath` call `g:color` with loose components thirty times between
them and are **unaffected**, which is the point of decision 1; run them anyway.

## Risks and gotchas

**`w:size()` is on a hot path and now carries a metatable.** Build the shape metatables as
`private static final` values, once, and share them — a per-call `LuaTable` for the metatable would
double the allocation this feature is partly meant to reduce. `LuaValue.tableOf` plus one
`setmetatable` is the whole cost.

**`__index` fires only for absent keys, and `pairs` does not go through it.** So `t.w` reads the real
field, `t.x` raises, and `next(t)` / `pairs(t)` / `Json.write` still walk `w` and `h` normally. Check
`Json` and the `:lua` REPL echo do not *probe* a key on a shape table before landing task 3 — the same
hazard 084's plan flagged for `closedIndex`, and the same grep answers it: `\.get\(` across the bridge.

**Three colour parsers, not one.** `AddonManager.colorArg` is the obvious one; `LuaMarker.colorArg`
and `LuaRule.colorArg` are private and duplicate the loose branch. A change that only touches
`AddonManager` leaves `marker:color(200, 210, 220)` and `rule:color(200, 210, 220)` working and the
feature's own claim false. Grep `colorArg` across the bridge, not `AddonManager.colorArg`.

**`g:color` is deliberately forgiving.** `LuaGOut`'s contract is that a draw callback never throws
into the render thread. The table branch must raise only on a table that is not a colour, and even
that should be weighed against the surrounding style — read the `g:text` fallback comment in
`LuaGOut` ("Malformed markup falls back to the literal string via the fast path — never throwing into
the render thread") before choosing.

**`Layout.parseCoord` is the sheet's parser too.** `Sheet`'s `size` property and `LuaRule.coordArg`
both go through it, so changing the `size` branch changes the **document** spelling as well as the
Lua one. Nothing under `docs/` or `addons/` writes `size = {x = …}`, so the move is safe — but the
error message that page authors will hit must name `{w = …, h = …}` and `{300, 200}`, not `{x, y}`.

**`rule:size()` and `w:size()` are different boxes.** `rule:size(w, h)` is documented as a window's
*content* size and `w:size()` reads `Widget.sz`, the outer box. That difference is out of scope and
must survive the key change untouched: this feature renames `x`/`y` to `w`/`h` and changes nothing
about which box either verb speaks for. Do not "fix" it in passing.

**Which season index is which is not in the source.** `Astronomy.is` arrives off the wire, `Cal`
indexes four textures with it, and no name appears anywhere in `src/haven`. The mapping is a
`[manual]` observation, and the page must not be written before it comes back. `Glob` defaulting `is`
to `1` is a hint that index 1 is the neutral season, not proof.

**`LuaStudySummary` must key on the widget, not the user.** `s:fight():summary()` answers `NIL` when
there is no window, and matching it is the whole content of the row. Keying on the user would make
the verb never answer `nil`, which is a *different* change (a `MINT` collection promise) and would
leave the two summaries still unlike each other in a second way.

**`SAttrWnd.StudyInfo` is a `Widget` with mutable `texp`/`tw`/`tenc`.** `CharApi.studyInfo(user)`
finds it by walking `CharWnd.sattr`'s children, so it is stable per session while the tab is up — an
`IdentityHashMap` key, exactly like `FightWnd`. Its fields update in place, which is what makes the
object live rather than a snapshot.

**`w:severity()` narrowing is loud in one direction and quiet in the other.** Code that did
`tonumber(w:severity())` keeps working. Code that did `w:severity():find("…")` now raises "attempt to
index a number" — loud, and correct. Nothing can catch `if w:severity() == "Grievous"`, which
silently stops matching; `wound.md` names `:label()` as the replacement in the same row.

**Nothing can catch a season comparison either.** `if hafen.time():season() == 1` becomes false
forever with no error available. It is a two-line page change and a `minor` row for that reason; say
so on `time.md` rather than pretending the refusal machinery reaches it.

**`conventions.md`'s headroom after the split is about twelve lines.** 300 today, minus the 29 that
move, plus this feature's own six rows leaves it near 288. A later feature adding to it must check
`wc -l` again — §11.2 is not discharged for good by this split.

**The build hides a moved symbol.** Tasks 2 and 3 delete a helper (`colorValue`) and add two
(`whTable`, `closedFields`); `ant hafen-client` is incremental, so `rm -rf build/classes` before
believing a green build.

**Existing refusals must not be swallowed.** `AddonManager.colorArg`'s clamp behaviour ("a component
outside `0..255` is clamped"), `LuaMarker`'s player-marker-only refusal, `Chrome.seqShape`'s sequence
refusal and `FontHandle`'s `draft`/`used` ownership refusal all sit next to the code these tasks
touch. Each task's suite asserts one of them still fires.

## Discarded alternatives

- **Making `g:color` table-only, like the other five.** It is one word, so a dual style there is a
  real cost — but `g:color(r, g, b)` stands among `g:line(x, y, x, y)`, `g:frect(x, y, w, h)` and
  `g:text(s, x, y)`, and loose numbers are what every verb on the draw context takes. Table-only
  would rewrite the drawing half of every addon that exists to make one word match five it does not
  otherwise resemble. The exception is stated on the page, which is the difference between an
  exception and a wart.
- **Leaving `g:color` alone entirely.** Then the one thing the colour readers hand back is the one
  thing the draw context cannot take, and the failure is a black rectangle rather than an error. The
  table branch is four lines.
- **Keeping `colorValue` for the three readers "because positional is what a literal looks like".**
  A literal is an *input*; this is the output shape, and `conventions.md` has claimed the keyed one
  since 011. Three readers disagreeing with the page is what makes `.r` silently `nil`.
- **Retiring the positional input table too, so a colour goes in exactly one way.** The positional
  table is what a hand-written literal looks like (`{200, 210, 220}`) and it is what every stylesheet
  document already contains; it costs nothing because `luaColor` distinguishes it by key, not by
  guess. What was worth killing is the third spelling and the second output shape.
- **Splitting `types.md`** — argued in `spec.md`, decision 2: one subject, and `DOCUMENTATION.md`
  splits by subject rather than by line count.
- **Putting the anonymous shapes on `types.md` instead of a new page.** `types.md` is the catalogue
  of what `:info()` hands back — every section there is a named type with a live counterpart. An
  anonymous two-number table has no `:info()` and no counterpart, and the page over the ceiling is
  the wrong one to grow.
- **Giving every anonymous shape a closed vocabulary, not only the two whose keys move.** A refusal
  is worth its cost where a *previously working* spelling now reads `nil`; nowhere else does this
  feature retire a key, so nowhere else is there anything to catch. 086 is where the handles get
  vocabularies, and it can widen this if it turns out to be worth it.
- **Naming `mdl:bounds()`'s span `.dimensions` or `.span`.** `extent` is the word used for an
  axis-aligned box's reach in every graphics vocabulary the reader is likely to have met, and it is
  short enough to sit in a table row beside `min` and `max`.
- **Making `s:study():summary()` never answer `nil`, with `:exists()` as the liveness test.** That is
  the better long-term shape and it is what `hafen.session():get()` and `s:world():gob():get()` do —
  but `s:fight():summary()` answers `nil`, and this row exists to make the two the same. Changing
  both is a second decision, on ground the audit did not survey.
- **Keying the client handles on a `WeakHashMap` rather than seven fields on `Addon`.** Seven is a
  closed set known at compile time; a map buys nothing and costs a lookup on a path whose whole
  complaint is allocation.
- **Locking the seven lazy fields.** `Addon.subMeta` and `Addon.grabMeta` already accept the benign
  race — two threads build two equal values and one wins — and a lock on a per-frame read is worse
  than the duplicate it prevents.
- **Renaming `w:severity()` to `w:label()` and leaving one verb.** There are two facts: what the
  client painted, and the number it usually is. `food:label()` and `marker:name()`/`marker:type()`
  already carry that split under those names.
- **Fixing which box `w:size()` reads while the keys are moving.** Out of scope in `spec.md` and
  worth the reason twice: the round-trip needs `Window.csz()` reachable from the bridge, which is an
  engine seam, and a behaviour change hidden inside a key rename is the kind of thing a reader finds
  out about from a bug.
