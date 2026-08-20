# 084 — Every refusal names the fix

Discharges: A-001, A-002, A-003, A-004, A-005, A-006, A-007, A-008, A-009,
A-010, A-012, A-013, A-014, A-015, A-016, A-017, A-018, A-019.

## What and why

`Retired`'s javadoc states the standard: an absent name *"fails later and elsewhere as 'attempt to
call a nil value'"*, so a retired one **throws, at the line that wrote it, saying what to write
instead.**

The API keeps that for a section name, a section verb and an event key, and breaks it for a typo on an
**object**. `Section.meta` and `LuaCollection.meta` throw, `Retired.closedIndex` throws on eight types
— the other **49 read `nil`**: Gob, Item, Widget, Kin, Marker, Position, every type an addon holds.
The best refusal in the API sits on its rarest types, the worst on its commonest.

Twelve of the 49 are worse than poorer: their metatable is the bare methods table, so it **never
consults `Retired`**. A retirement row on a `Buff`, a `Meter`, a `Sound`, an `IconCategory` or a
`Mask` would be registered and silently never fire. **That is why this feature is first** — every
feature after it renames something, and a rename is cheap only where the mechanism reaches.

One level down, the same defect: twelve sites hand-write a LuaJ type test instead of reaching the
house helpers, two of them wrong in opposite directions — `s:kin():add(1234)` sends `"1234"` as a
hearth secret, `entry:value("42")` refuses a row that scans as a number — and a `LuaError` inside a
filter predicate is caught and dropped.

**Nothing here changes a Lua spelling**, which is what makes it safe to go first.

## Acceptance criteria

1. **No object type reads an unknown key as `nil`** — it throws naming the receiver and what it does
   answer, called (`x:nosuch()`) or read as a field (`x.nosuch`). All 49, and a known verb still
   answers. The message spells the receiver as the author wrote it: `session:world()`.
2. **The twelve produce a message at all**, in the `closedIndex` shape — which only the index that
   also consults `Retired` can produce.
3. **A collection with no `:get` names its own entry verb**, and that verb exists on it.
4. **`:get`'s three miss-behaviours are declared, not implemented** — `NIL`/`MINT`/`RAISE` per
   collection — and `conventions.md` carries the table and its membership.
5. **No LuaJ `bad argument` reaches an author**: every refusal names verb and parameter, a `nil` that
   should have been a value included.
6. **A number fails a string check, a numeric string passes a number check.** `s:kin():add(1234)` and
   `item:drop("2")` refuse; `entry:value("42")` and `radio:value("061.8")` are taken.
7. **A `LuaError` from a predicate propagates**, while a read that is merely not ready still does not
   throw out of the call containing it.
8. **Refusals fire in the order the author needs**: `widget:on` names the tree before the key, and the
   permission gate stays first at all 24 sites.
9. **Three silences become refusals, one a log**: `hafen.session():current(s)` on a session with no
   screen, a font handle carrying a `color` installed on a client surface, and the store's timer
   degrading a value it cannot hold.

## Out of scope

- **Every rename and every shape change.** 085 does the shapes — a return needs a page line, not a
  `Retired` row — and 087–089 the names. Nothing moves here, which is what lets this land under every
  addon that exists.
- **`s:ui():find(sel)` refusing an unknown bare role.** The rename to `:match`/`:matchAll` was chosen
  instead (088); the silent miss survives it and belongs to the verb replacing it.
- **The eleven plain-table handles** — timer, slash, watch, request, asset, font, map image, options.
  They have **no metatable**, so nothing can point at `Retired`: they become userdata in 086 and their
  refusals arrive with it. Doing it here would write the mechanism twice.
- **Which verbs the tier guards** (093). The gate's *ordering* is in scope, its membership is not.

## Docs impact

Written: `conventions.md` · `buff.md` · `meter.md` · `study.md` · `session.md` · `store.md` ·
`font.md` · `map/markers.md` · `ui/controls/interactive.md`.

Derived impact set —
`grep -rn "has no such verb\|raises, saying\|no-op\|Re-read\|placeholder\|ignored" docs/addons/`:

| Page | Verdict |
|---|---|
| `conventions.md` | **revise** — the `:get` table (§Collections promises `nil`, true of half); the exempt families (one of three named); the `nil` meanings as a table (five of nine, in prose) |
| `buff.md:26`, `meter.md:23`, `study.md:41` | **revise** — all say `:get(…)` "raises, saying the collection has no such verb": written **down** to a message |
| `session.md:142`, `:152` | **revise** — the no-op blockquote goes, the cycling example drops from eleven lines to four |
| `store.md:103` | **revise** — "the timer and the teardown do not refuse" gains the line it logs |
| `font.md:79` | **revise** — `color` on a surface is "**ignored**"; it becomes a refusal naming `rule:color(…)` |
| `map/markers.md` | **revise** — the accessor stops naming a `:get` this collection has not got |
| `ui/controls/interactive.md:40` | **revise** — `entry:value(s)` takes a numeric string |
| `json.md` | **discharge** — the page says `str`, the bridge changes to match |
| `timer.md`, `slash.md`, `http.md`, `client/keybindings.md` | **discharge** — none quotes its message |
| `kin.md`, `world.md`, `ui/items.md`, `ui/widget.md` | **discharge** — no page claims a number is taken for a string |
| `references.md`, `types.md` | **discharge** — no shape moves here |

**No `docs/client/` page is created**: `widgets.md` maps the destroy/staleness seams (task 5),
`ui-controls.md`/`ui-lists.md` the control value spine (task 4).

## Context files

Under `src/io/brodgar/addon/`:

- `Retired` — 1, 2, 3, 6 · `Section` — 1 · `LuaCollection` — 3, 5 · `Args` — 4 · `LuaWidget` — 1, 4, 5, 6
- `LuaImage`, `FontHandle`, `LuaMesh` — 1 (their `resolve` probes a handle's field) · `VrApi` — 1, 6
- the 37 entity types now on `Retired.closedIndex` (33 literal, plus `VrApi`'s four kinds) — 1, 6
- `LuaBuff`, `LuaMeter`, `LuaSound`, `LuaIconCat`, `LuaMask`, `ProfHandle`, `ProfScope`,
  `OptionsHandle`, the five `*Options` — 2
- `SessionApi`, `AssetApi`, `FontApi`, `MapApi` — 3
- `LuaKin`, `LuaItem`, `WorldApi`, `Controls`, `CEntry`, `CRadio`, `HttpApi`, `HookApi`,
  `KeybindingsOptions`, `AddonManager` — 4 · `LuaSession`, `StoreApi` — 5
- `AudioOptions`, `CameraOptions`, `InterfaceOptions`, `VideoOptions`, `ProfHandle`, `CProgress`,
  `CSlider`, `CScrollbar`, `CScrollport`, `CTable`, `LuaPosition` — 7

A suite reaches a live handle of every type it proves, and the reach spellings are on the reference
pages rather than in the bridge: `api/README.md` (the index), `char.md`, `fight.md`, `ui/custom.md`,
`ui/items.md`, `overlay.md`, `client/keybindings.md`, `timer.md`, `vr/*.md`, and
`guides/permissions.md` for whether a verb it calls is gated. Every task's suite pays this.

Pages: `api/conventions.md` — 1 (the object level of "a retired name says what replaced it"), 3, 4, 5 ·
`api/buff.md`, `meter.md`, `study.md`, `map/markers.md` — 3 ·
`api/ui/controls/interactive.md` — 4 · `api/session.md`, `store.md`, `font.md`,
`api/ui/style/text.md` (its blockquote states what a face's `color` does on a surface) — 5 ·
`api/ui/widget.md` (the staleness paragraph lists what raises; the `widget:` receiver is task 6's) — 5, 6 ·
`api/client/README.md`, `api/ui/controls/interactive.md`, `api/position.md` — 7 ·
`docs/client/widgets.md` — 5 · `docs/client/ui-controls.md` — 4 · `DOCUMENTATION.md` — 3, 4, 5 ·
`audit/INVENTORY.md` — every task, for the ids `/end` ticks.

The bundled addons are consumers like any other: `addons/session-manager/main.lua` is written against
`hafen.session():current(s)` and `addons/eventstack/main.lua` against `rule:font`, so a task changing one of
those verbs fixes the demo in the same task.
