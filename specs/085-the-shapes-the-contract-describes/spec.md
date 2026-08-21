# 085 — The shapes the contract describes

Discharges: A-020, A-021, A-022, A-023, A-024, A-025, A-026, A-027, A-028, A-029, A-030, A-031,
A-032, A-033, A-034, A-035, A-036, A-037.

## What and why

084 made a wrong **name** say what to write instead. This feature is the wrong **shape**: the object
is there, the field is not, and `nil` comes back from a line that reads exactly like the page.

**Colour.** `conventions.md` §Colours says a colour is keyed `{r=, g=, b=}` — "what every reader
hands back". Three readers do not. `FontApi`'s `h:color()`, `LuaOverlay`'s `ov:color()` and `VrApi`'s
`e:tint()` go through `AddonManager.colorValue`, which builds `{[1],[2],[3],[4]}` with **no `r` key
at all**. So `ov:color().r` is `nil`, always, silently, and `kin:color()[1]` is `nil` the other way.

Going in it is worse. A colour is accepted in **three** spellings — a keyed table, a positional
table, and loose components `(r, g, b[, a])` — through **three** separate parsers that each
re-implement the loose branch: `AddonManager.colorArg`, `LuaMarker.colorArg` and `LuaRule.colorArg`.
And there is a fourth door nobody counted: `LuaGOut`'s `g:color` reads its arguments with `toint()`
and has no table branch at all, so `g:color(kin:color())` — a colour this API just handed you —
calls `chcolor(0, 0, 0, 255)` and draws **black**. In LuaJ `toint()` on a table is `0`, not an error.

`specs/011-virtual-entities/plan.md` already settled the canonical shape: *"colour has ONE canonical
shape (`{r,g,b[,a]}` named keys via `luaColor`) despite the spec's positional example."* The loose
form arrived later and the canonical claim was never withdrawn — `conventions.md` still makes it, and
three readers still break it.

**Size.** `w:size()` answers `{x=, y=}` — `LuaWidget.xyTable`, the helper `:position()` uses — while
`w:cell()`, `img:size()` and `mapImg:size()` answer `{w=, h=}`. So does `rule:size()`, and so does
the `size` key of `w:info()` and of the sheet snapshots. One word, two shapes, and the wrong guess
reads `nil` rather than raising. One level down, `mdl:bounds().size` is a **three**-number span
wearing the word every two-number size uses.

**A value that stands for a thing instead of being it.** `hafen.time():season()` is the raw
`Astronomy.is`, so `== "winter"` is false forever and "season index" does not say which index is
which. `s:study():summary()` is a plain table where `s:fight():summary()` is a live object with
`:exists()` and `:info()` — one verb, two kinds of answer, and after 084 the wrong guess raises
loudly from `closedIndex` rather than handing back a function, which is progress and still a
collision. `w:severity()` is a string that is *usually* a number, so the only correct code is
`(tonumber(w:severity()) or 0)` and nothing on the page says so.

**Identity.** `hafen.client():options() == hafen.client():options()` is **false**. Every one of the
seven handles is minted per call — `OptionsHandle.create`, then `InterfaceOptions.create()`,
`VideoOptions.create()`, `AudioOptions.create()`, `CameraOptions.create()`, `ClientOptions.create()`,
`KeybindingsOptions.create(owner)`, `ProfHandle.create(owner)` — against `conventions.md`'s "The
object is the same one every time, so `hafen.time() == hafen.time()`" and `Section`'s own javadoc,
"a section called inside a draw callback at 60 fps allocates nothing". A HUD reading
`opts:video():fpsLimit()` allocates six tables and six metatables **per frame**.

**Why now.** `Retired` cannot carry any of this: the spelling does not move, only the value. These
land with a page line and a refusal, and every addon written before them is a consumer. Five exist
today. Every feature after this one adds more.

## The decisions this feature takes

Three calls the audit rows did not make. Each is reversible with one word from the maintainer.

1. **The draw context keeps its loose components.** `g:color(r, g, b)` sits among `g:line(x, y, x,
   y)` and `g:frect(x, y, w, h)`; loose numbers are the language of every verb on `g`, and
   `addons/profiler` alone has thirty such calls. So `g:color` **gains** the table form —
   `g:color(kin:color())` stops drawing black — and keeps the components. It is the one stated
   exception, written down on `shapes.md` rather than left as a habit. The other five colour writes
   (`font:color`, `marker:color`, `overlay:color`, `rule:color`, a vr `:tint`) lose the loose form.

2. **`types.md` is not split — A-031 is struck.** `DOCUMENTATION.md` §9: *"split by subject, never by
   line count — a 300-line page that is one subject beats two 150-line halves of one."* `types.md` is
   one subject, every table `:info()` hands back, and its opening sentence does not have to say
   "and". At 324 lines with §Color leaving it sits near 320 of a 350 hard stop. Twenty-six pages link
   into it and forty-five of those links are anchors, which is what a split costs. `specs/ROADMAP.md`
   (filed 064) keeps the line; striking it is the maintainer's.

3. **A-037 is struck as already true.** The audit said `vr/widgets.md` does not state that a standing
   widget stays with the character that stood it. It does — §"It stands with the character you stood
   it from", added by 075.3, in the present tense, with `panel:drawn()` named as the read that
   answers. The audit finding was wrong; nothing is written.

And one the ceiling forces: **`conventions.md` is at exactly 300 lines** and this feature adds six
rows to it. So §Coordinates and §Colours move to a new `api/shapes.md`, and `types.md` §Color joins
them. The split by subject is clean: `conventions.md` is **how you call and what a call answers**,
`shapes.md` is **what a value looks like**, `types.md` is **the catalogue of named snapshots**.

## Acceptance criteria

1. **One colour out.** `AddonManager.colorValue` is deleted and every colour reader in the API hands
   back `{r=, g=, b=, a=}` — `ov:color()`, `h:color()`, `e:tint()` included. `.r` answers on all of
   them and `[1]` answers on none.
2. **One colour in.** A table, keyed or positional, at all five write verbs. Loose components raise a
   message naming the table form. The draw context is the one exception, stated on the page:
   `g:color(r, g, b)` still works **and** `g:color(c)` takes a value read back from the API.
3. **A read passes straight back into a write.** `ov:color(kin:color())`, `marker:color(m:color())`
   and `rule:color(other)` are each one expression.
4. **A size is `{w=, h=}` wherever it is a size** — `w:size()`, `w:info().size`, `rule:size()` and
   the sheet snapshots — while a pixel and a place keep `{x=, y=}`. A span keeps `{x=, y=, z=}` and
   `mdl:bounds()` names it `extent`.
5. **A key this feature retires on a table raises**, naming its replacement, rather than reading
   `nil`: `w:size().x` and `mdl:bounds().size` both say what to write instead.
6. **`hafen.time():season()` is one of four strings**, and which index carries which name is
   confirmed in-game before the page states it.
7. **`s:study():summary()` is a live object** with `:lp()`, `:attention()`, `:cost()`, `:exists()`
   and `:info()`, interned like every other object, so it matches `s:fight():summary()`.
8. **`w:severity()` is a number or `nil`, and `w:label()` is the string the client shows.**
9. **A `hafen.client()` handle is the same object every call** — all seven, `==` says so, one works
   as a table key, and reading one in a draw callback allocates nothing.
10. **`h:size(nil)` and `h:aa(nil)` undo the layer**, the meaning `w:size(nil)` already carries, and
    `conventions.md`'s nil table lists them.
11. **Every anonymous table shape is written down once** — which is a place, which a pixel, which a
    size, which a span, which a pair of counts — and so is the rule that a grid id, a segment id and
    a marker's `seg` are 64-bit values as **decimal strings**.
12. **Three unstated rules are stated**: the stylesheet path exception, the third value category
    beside snapshots and handles, and the unit convention behind `…Fraction`.
13. **The five addons under `addons/` run** — `eventstack` reads `w:size()` at four sites — and no
    page under `docs/` describes a shape this feature moved.

## Out of scope

- **Which box a window's `:size()` is.** `w:size(w, h)` writes the **content** box and `w:size()`
  reads `Widget.sz`, the **outer** box the chrome draws, so the pair does not round-trip and
  `Window.csz()` is reachable from nothing (`ROADMAP`, filed 065). This feature changes the **keys**;
  which box is a behaviour change with an engine seam behind it, and the key change is whole without
  it. The two are independent: `{w=, h=}` is right for either box.
- **Named types for the lattice places** — a `TileCoord`, a `SegmentCoord`, so that a place and a
  pixel stop type-checking as each other. The shapes are written down here; minting the types is
  086's ground, and it is `shapes.md` that 086 would then shorten.
- **Making an asset handle userdata**, so `img:sizes()` refuses instead of reading `nil` (086). The
  image, mesh and data handles are plain tables with no metatable, so nothing here can give them one
  without writing 086's mechanism twice.
- **`hafen.font():list()` enumerating the four built-ins** rather than what the addon asked for
  (091). It is a collection question, not a shape one.
- **The per-session overlay registry** behind `gob:overlay()` and `gob:scale()` (092).
- **`s:world():distance(p)` and the addressed Position twins** (092).

## Docs impact

**Written:** `api/shapes.md` (new) · `api/conventions.md` · `api/types.md` · `api/README.md` ·
`api/overlay.md` · `api/font.md` · `api/meter.md` · `api/map/markers.md` · `api/ui/drawing.md` ·
`api/ui/style/README.md` · `api/ui/style/text.md` · `api/vr/README.md` · `api/vr/models.md` ·
`guides/theming.md` · `api/ui/widget.md` · `api/ui/pixels.md` · `api/ui/style/geometry.md` ·
`api/asset.md` · `api/time.md` · `api/study.md` · `api/fight.md` · `api/wound.md` ·
`api/client/README.md` · `api/store.md` · `docs/client/state.md`.

**Derived impact set.** The prose names of this surface, greped across the whole of `docs/`:

```
grep -rnE "r, g, b|\{r=|\{r,|positional|season index|gridId|bounds\(\)|severity|:size\(\)|== hafen|same one every time" docs/
```

31 pages under `docs/addons/`, plus `docs/client/state.md`. The verdicts:

| Page and row | Verdict |
|---|---|
| `conventions.md` §Coordinates, §Colours | **move** to `shapes.md`, then rewrite §Colours to one output shape and two input spellings |
| `conventions.md` §Snapshots vs handles, §nil is an error, §A table is a value | **revise** — the third category, `h:size(nil)`/`h:aa(nil)`, the stylesheet path exception |
| `types.md` §Position `{ gridId = number }` | **revise** — `gridId` is a **string**; the bridge is right and the page is wrong |
| `types.md` §Wound `severity` row, §Color, the `s:study():summary()` line | **revise** / **move** / **revise** |
| `font.md` `h:color()` row, the `rule:color(r, g, b)` sentence, the `g:text` examples, "positional, the same as every other `g:` call" | **revise** — the reader is keyed, the write takes a table, and the `g:` sentence becomes the stated exception |
| `font.md` `h:size()` / `h:aa()` rows | **revise** — `nil` is writable, not only readable |
| `overlay.md` `ov:color(r, g, b, a)` row | **revise** |
| `map/markers.md` `marker:color()` row | **revise** |
| `meter.md` `meter:color()` row | **revise** — `{r, g, b, a}` written as the keyed shape it already is |
| `ui/drawing.md` the `g:color` row, the `g:text` options, the `color` option | **revise** — the exception, and the table form `g:color` now takes |
| `ui/style/README.md` `rule:color` row; `ui/style/text.md` §color and the font blockquote; `guides/theming.md` | **revise** — the loose form goes, both table spellings stay |
| `ui/style/chrome.md`, `ui/style/chat.md`, `ui/style/keys.md` | **discharge** — every hit is `{color = {r, g, b}}`, a positional **table** inside a document, which stays legal |
| `vr/README.md` `e:tint()` row | **revise** |
| `ui/widget.md` `:size()` row, the `:rootPos()` cross-reference, the arity paragraph | **revise** — `{w=, h=}` |
| `ui/pixels.md` the two `{x = 100, y = 40}` literals | **revise** |
| `ui/style/geometry.md` the `size` caveat | **revise** — it reads back `{w=, h=}` |
| `asset.md` `img:size()` row, `mdl:bounds()` row, the `icon:size(), chair:bounds()` example | **revise** — keyed notation, and `size` → `extent` |
| `vr/models.md` the `mdl:bounds().size.z` blockquote | **revise** — `.extent.z` |
| `time.md` `season()` row; `api/README.md` the `hafen.time` line | **revise** — a string of four |
| `study.md` the `:summary()` row, the two examples, the "answers `nil` until" line | **revise** — a live object |
| `fight.md` §The summary | **revise** — one line saying the two summaries are now the same kind |
| `wound.md` `:severity()` row, its blockquote, the tutorial line | **revise** — plus a `:label()` row |
| `client/README.md` the stateless-proxy paragraph | **revise** — a handle is also the **same** handle, so `==` works and a frame allocates nothing |
| `store.md` the live-table example | **revise** — one line pointing at the third category |
| `position.md`, `map/grids.md`, `world.md`, `session.md`, `player.md`, `guides/reading-the-world.md`, `guides/saved-data.md` | **revise** — their `{x, y}` and 64-bit-id sentences point at `shapes.md` instead of restating it |
| `references.md`, `sound.md`, `json.md`, `event/bus.md` | **discharge** — each hit is a link or an unrelated word |

**`docs/client/state.md`** gains one gotcha on its existing astronomy row: `Astronomy.is` is a season
index that `Cal` uses to pick one of **four** `gfx/hud/calendar/dayscape-<i>` textures (`Tex[4]`), so
the domain is `0..3`; `Glob` defaults it to `1` when the server omits the field; and nothing upstream
names the four — `Cal` is the only reader in the client.

## Closing the inventory

`audit/INVENTORY.md` is the sweep's working sheet and the only thing that knows when the sweep is
done. **At `/end`, and only after the maintainer's verification, this feature ticks its own rows:**

- For each of the eighteen ids above, the box becomes `☒` and the id is struck:
  `| ☒ | ~~**A-020**~~ | … |`.
- **A-031 and A-037 are struck as not-done**, and each says so in its own row text — A-031
  *"struck: `types.md` is one subject, and `DOCUMENTATION.md` splits by subject, never by line
  count"*, A-037 *"struck: already true — `vr/widgets.md` §It stands with the character you stood it
  from"*. A row struck because it shipped and a row struck because it was wrong both carry a ticked
  box, and only the text tells them apart.
- Nothing else in the file is touched. An id never moves.

Then the two greps say where the sweep stands — the first counts rows still open, the second prints
every id no `spec.md` has claimed:

```bash
grep -c '^| ☐' audit/INVENTORY.md
```

```bash
comm -23 <(grep -oE 'A-[0-9]{3}' audit/INVENTORY.md | sort -u) <(grep -rhoE 'A-[0-9]{3}' specs/*/spec.md | sort -u)
```

After 084 the first prints `101`. After this feature it must print `83`, and the second must no
longer name any id between A-020 and A-037.

## Context files

Under `src/io/brodgar/addon/`, tagged with the tasks that need each:

- `AddonManager` — `color`, `colorValue`, `colorArg`, `luaColor`, `clampByte` — 2
- `LuaOverlay` (its `colorValue`/`colorArg` wrappers and the `ov:color` verb), `FontApi` (the `color`
  property read and write), `VrApi` (`e:tint`, and its table-only tint reader), `LuaMarker`
  (`colorArg`), `LuaRule` (`colorArg`), `LuaGOut` (`g:color`, and `g:text`'s `opts.color`),
  `Chrome` (`seqShape`'s `positional` flag, `parsePalette`) — 2
- `LuaWidget` — `xyTable`, `boxTable`, the `size` verb, the `:info()` snapshot's `size` key — 3
- `LuaRule` (the `size` read), `Sheet` (its two `size` snapshot keys), `AssetApi` (`meshHandle`'s
  `bounds`, `vec3Table`) — 3
- `Retired` — `closedIndex`, `put`, `message`, `NAMES` — 3
- `WorldApi` (`installTime`'s `season`), `haven/Astronomy`, `haven/Glob` (the `"astro"` branch),
  `haven/Cal` (`dlnd`) — 4
- `CharApi` (`studySummary`, `studyInfo`, the `study` section), `LuaFightSummary` (the pattern to
  copy: `of(owner, wnd)`, its `number(...)` helper, `exists`, `info`), `LuaWound` (`severity`,
  `severityOf`) — 4
- `OptionsHandle` (`install`, `create`), `ProfHandle`, `AudioOptions`, `CameraOptions`,
  `ClientOptions`, `InterfaceOptions`, `VideoOptions`, `KeybindingsOptions` (their `create`),
  `Addon` (the lazy-field pattern at `subMeta` / `grabMeta`), `Section` (`install`, and its identity
  javadoc) — 5
- `FontApi` (`property`'s write path for `size` and `aa`), `FontHandle` (`size`, `aa`, `draft`,
  `used`) — 5

Pages, by task:

- **1** — `api/shapes.md` (new), `api/conventions.md`, `api/types.md`, `api/README.md`,
  `api/store.md`, `api/position.md`, `api/map/grids.md`, `api/world.md`, `api/session.md`,
  `api/player.md`, `api/asset.md`, `guides/reading-the-world.md`, `guides/saved-data.md`,
  `DOCUMENTATION.md`
- **2** — `api/overlay.md`, `api/font.md`, `api/meter.md`, `api/map/markers.md`,
  `api/ui/drawing.md`, `api/ui/style/README.md`, `api/ui/style/text.md`, `api/vr/README.md`,
  `guides/theming.md`, `api/shapes.md`
- **3** — `api/ui/widget.md`, `api/ui/pixels.md`, `api/ui/style/geometry.md`, `api/asset.md`,
  `api/vr/models.md`, `api/shapes.md`
- **4** — `api/time.md`, `api/study.md`, `api/fight.md`, `api/wound.md`, `api/types.md`,
  `api/README.md`, `api/shapes.md` (its §Units names `w:severity()`), `docs/client/state.md`
- **5** — `api/client/README.md`, `api/font.md`, `api/conventions.md`

Every task: `audit/INVENTORY.md`, for the ids `/end` ticks.

A suite reaches a live handle of every type it proves, and the reach spellings are on the reference
pages rather than in the bridge: `api/README.md`, `api/overlay.md`, `api/font.md`,
`api/ui/custom.md`, `api/ui/drawing.md`, `api/asset.md`, `api/vr/models.md`, `api/study.md`,
`api/wound.md`, `api/client/README.md`, and `guides/permissions.md` for whether a verb the suite
calls is gated.

Consumers to keep running: `addons/eventstack` (four `w:size()` reads), `addons/profiler` and
`addons/clickpath` (thirty `g:color` calls between them) — 2, 3, 5.
