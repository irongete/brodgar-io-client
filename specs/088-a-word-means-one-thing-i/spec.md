# 088 — A word means one thing I: ui · map · world · vr

Discharges: A-050, A-051, A-052, A-053, A-054, A-055, A-056, A-057, A-119, A-120.

**All ten are rows in `audit/INVENTORY.md`'s own 088 block, and this feature adds nothing to them.**
Where a row offered two spellings, the row's own choice is taken; where a Step-0 decision chose, that
choice is taken. §"Where the audit or a decision already chose" names every one.

**The audit is readable, and every task names its part of it.** `audit/` is not in `CLAUDE.md`'s tree
table, so nothing otherwise permits an `/implement` session to open it. It is permitted here: each
task's *Audit* line names its ids, quotes the row, and names the finding page. **Read those pages
before starting.** **Read nothing else there** — the rest of the audit is other features' ground.

## What and why

One spelling, several meanings, on the half of the API a user meets first. Two rows are `severe`
because the wrong guess is silently wrong; one of those has a **side effect on the user's screen**.

**`hafen.ui():list()` builds a listbox.** Every other `:list()` in the API enumerates — 34 collections,
`hafen.timer()`, `hafen.sound()`, `s:kin()`, `hafen.vr()`. This one calls `Controls.list` →
`UiApi.attach` → `u.root.add(rootw)`, so a user who writes the fifth after learning four gets **an
empty control drawn on screen before the next statement runs**. The error they eventually see is
`ipairs` on userdata, one line later, naming nothing about listboxes.

**`widget:cell()` is a size and `item:cell()` is a place.** Both are two-number keyed tables, neither
labelled, and the wrong read looks right:

```lua
local c = item:cell()
grid:cell(c.w, c.h)     --> nil, nil — taken, and the grid ends up default-sized
```

**`:overlay()` names four unrelated things** — the minimap's display switches, a grid's recorded
masks, a gob's decorations, and a HUD painter you install. `hafen.map():overlay():get("claim")` is a
switch and `grid:overlay():get("claim")` is a mask; both succeed, both return an object, and the
objects have different verbs.

**`:at()` addresses by a place on a collection and hit-tests the screen on the ui.**
`s:world():grid():at(p)` is the grid covering a Position; `hafen.ui():at(x, y)` is the **deepest**
widget under a point — a search, not an address.

**`:find` takes a filter on a collection and a selector on a widget.** `s:kin():find("Bo")` is a
substring; `s:ui():find("Cupboard")` is a **role** selector matching nothing, where the user meant
`window[title=Cupboard]`. Two query languages under one verb, and the failure is an empty result
rather than a refusal. The second half: "give me all" is `:list()` on a collection and `:all()` on the
UI.

**And four more words are spent badly.** `rule:close(…)` is the close **button**'s art, not an ending.
`pag:path()` is an array of category names where `a:path()` is a file path. `ev:sender()` and
`ev:target()` are `LuaEvent`'s own comment's words for *the same widget, named for the direction it is
on*. `hafen.vr():list()` is a plain array while every `:list()` under it is a collection's, so
`hafen.vr():ghost():count()` works and `hafen.vr():count()` throws.

## Where the audit or a decision already chose

| Row | The audit offered | Taken here |
|---|---|---|
| A-052 | `hafen.ui():hit(x, y)` | `:hit(x, y)` — the row names it, and it pairs with the mouse's `:over()` |
| A-054 | `hafen.map():display()` **or** `:switch()` | `:display()` — the row names it |
| A-054 | `hafen.ui():overlay()` → `:painter()` **or** keep | **neither**: A-120 supersedes it, and the maintainer chose the word `overlay` so that the HUD reads like the world |
| A-053 | `rule:closeButton(…)` **or** `rule:button("close", …)` | `:closeButton(…)` — the row names it |
| A-055 | `pag:ancestry()` **or** `pag:categories()` | `:categories()` — the row names it, and it is `menugrid.md`'s own word |
| A-057 | `hafen.vr():click(…)` **or** `:deliver(…)` | `:click(…)` — the row names it |
| A-119 | `:select`/`:selectAll` **or** `:match`/`:matchAll`, **or** keep `:find`/`:all` | `:match`/`:matchAll` — **D3** chose it in Step 0; `select` is taken by `s:world():select` and `s:flowermenu():select` |

**A-120 is the maintainer's own decision, recorded in the inventory.** `audit/ns-map.md` F4 offered
`hafen.ui():painter()` or keeping the word; the maintainer chose to **keep `overlay` and make it mean
one thing** — *"so the name is the same as the one in the world and it is easy for developers to know
the API"*. So the HUD painter takes `gob:overlay()`'s shape rather than a new name.

## Acceptance criteria

1. **`:list()` enumerates everywhere.** `hafen.ui():listbox()` builds the control;
   `hafen.vr():entity()` is a collection over every standing entity, answering the quartet and
   `:remove(x)`; `hafen.ui():list()` and `hafen.vr():list()` each raise naming the replacement, and
   **`hafen.ui():list()` builds nothing** — the refusal fires before any widget is attached.
2. **`:find`/`:all` are `:match`/`:matchAll`** on `s:ui()` and on a widget, so the verb names the
   selector grammar; the four old spellings raise, and the two existing `Retired.uiMoved` keys for
   `find`/`all` still fire from the global half.
3. **A size, a place and a hit test are three words.** `widget:cellSize(w, h)`, `item:cell()`
   unchanged, and `hafen.ui():hit(x, y)` / `w:hit(x, y)` — leaving `:at(x)` to mean *address a member
   by a place*, which `s:world():grid():at(p)` keeps.
4. **`:overlay()` means keyed decorations bound to a thing, and nothing else.**
   `hafen.map():display()` is the switches, `grid:mask()` is the recorded masks, `gob:overlay()` is
   unchanged, and `hafen.ui():overlay()` is a **collection**: `:add(key)`, `:get(key)`,
   `:remove(key)`, `:list/count/find(filter)`, with `:draw(fn)` on the member. `:list()` is the draw
   order.
5. **`ov:onDraw(fn)` and `ov:destroy()` raise**, naming `:draw(fn)` and
   `hafen.ui():overlay():remove(key)`.
6. **Three words are freed.** `rule:closeButton(…)`, `pag:categories()`, and `ev:widget()` on both
   event kinds.
7. **`hafen.vr():click(…)` replaces `:pointer(…)`**, keeping its boolean return, which is
   information the caller needs and is documented as such.
8. **Every retired spelling raises naming its replacement**, every snapshot keeps the client's own
   field spelling, and the five addons under `addons/` run.

## Out of scope

- **`w:overlay()`** — a widget's own decorations. A-120 says this feature *prepares* it and names
  **096** as where it lands.
- **`hafen.ui():list()` growing back as "the windows this addon built".** `audit/ns-ui.md` F1 floats
  it and `specs/ROADMAP.md` (filed 074) records the gap; **A-113** (094) is the row that makes
  `hafen.ui()` enumerable. This feature only frees the word.
- **`ov:destroy()`'s return value.** 087's A-048 says in as many words to skip it here, because this
  feature replaces the verb with `:remove(key)`, which returns the collection like every other one.
- **The other `:level()`, `:slot()` and `:available()` collisions** — those are the character sheet
  and are 089's block.
- **`s:ui():match(sel)` refusing an unknown bare role.** **D3** chose the rename instead and struck
  A-011; the silent miss survives the rename, and the inventory records that it returns as a
  `ROADMAP` line if it is not acceptable.

## Docs impact

**Written:** `api/ui/lists.md` · `api/ui/README.md` · `api/ui/controls/README.md` ·
`api/ui/selectors.md` · `api/ui/widget.md` · `api/ui/replace.md` · `api/ui/items.md` ·
`api/ui/drawing.md` · `api/ui/mouse.md` · `api/ui/style/chrome.md` · `api/ui/style/README.md` ·
`api/map/overlays.md` · `api/map/grids.md` · `api/map/README.md` · `api/menugrid.md` ·
`api/event/streams.md` · `api/vr/README.md` · `api/vr/widgets.md` · `api/references.md` ·
`api/conventions.md` · `api/types.md` · `guides/custom-ui.md` · `guides/theming.md`.

**Derived impact set:**

```
grep -rlE "ui\(\):list|:cell\(|:at\(|rule:close|:overlay\(\)|pag:path|ev:sender|ev:target|vr\(\):pointer|vr\(\):list|:find\(|:all\(|onDraw" docs/
```

| Page | Verdict |
|---|---|
| `ui/lists.md` | **rewrite** — the page is named for the verb; every heading and the title move to `listbox` |
| `ui/README.md`, `ui/controls/README.md` | **revise** — the builder index row |
| `ui/selectors.md`, `ui/widget.md`, `ui/replace.md`, `references.md` §Selector | **revise** — `:match`/`:matchAll` |
| `ui/items.md`, `ui/lists.md` §Grid | **revise** — `item:cell()` stays a place, `widget:cellSize()` is the box |
| `ui/mouse.md`, `ui/README.md` | **revise** — `:hit(x, y)` beside `mouse:over()` |
| `ui/style/chrome.md`, `ui/style/README.md`, `guides/theming.md` | **revise** — `rule:closeButton(…)` |
| `map/overlays.md`, `map/grids.md`, `map/README.md` | **rewrite** / **revise** — `hafen.map():display()` and `grid:mask()`; the page opens by separating two things that now have two names |
| `ui/drawing.md`, `guides/custom-ui.md` | **rewrite** — the HUD painter is a keyed collection |
| `menugrid.md`, `types.md` §Pagina | **revise** — `pag:categories()`; the snapshot field `path` stays |
| `event/streams.md` | **revise** — `ev:widget()` replaces two near-identical tables' one differing row |
| `vr/README.md`, `vr/widgets.md` | **revise** — `hafen.vr():entity()` and `:click(…)` |
| `conventions.md` | **revise** — §Collections gains the sentence that `:list()` enumerates, now that nothing contradicts it |

**No `docs/client/` page is created.** Every rename sits on a bridge verb; the one engine class this
feature reads past its page is `haven.Listbox`, already named on `docs/client/ui-lists.md`.


## Pages two planned features share

Three of the pages this feature edits are also edited by another planned feature, and each is **at or
over `DOCUMENTATION.md`'s 300-line ceiling** today. None of the three should grow: every change here
is a row edit. Whichever feature runs second inherits §11.2's split if it does grow one.

| Page | Lines today | Also edited by |
|---|---|---|
| `types.md` | 323 | **089**, whose thirteen renames move its live-read columns |
| `ui/widget.md` | 305 | **087.2**, which changes `w:destroy()`'s return |
| `menugrid.md` | 300 | **089.4**, which adds the line about `s:menugrid():get(slot:res())` |
| `references.md` | 135 | **087.3** (the teardown row) and **089.2** (§Slot) |

## Closing the inventory

`/end` runs **per task** and ticks that task's own rows:

| Rows | Ticked by |
|---|---|
| A-050, A-057 | 088.1 |
| A-119 | 088.2 |
| A-051, A-052 | 088.3 |
| A-054, A-120 | 088.4 |
| A-053, A-055, A-056 | 088.5 |

Nothing else in the file is touched. An id never moves. **No row of another feature's block is
implemented here**, and none of these is implemented elsewhere — 087 owns the teardown family and is
told to skip `ov:destroy()` for this feature, 089 owns the character sheet, 094 owns `hafen.ui()`
becoming enumerable (A-113).

```bash
grep -c '^| ☐' audit/INVENTORY.md
```

```bash
comm -23 <(grep -oE 'A-[0-9]{3}' audit/INVENTORY.md | sort -u) <(grep -rhoE 'A-[0-9]{3}' specs/*/spec.md | sort -u)
```

With 087 closed the first printed **70**, and 088.1 took it to **68**. When the last task closes it
must print **60**, and the second must no longer name A-050 … A-057, A-119 or A-120. **A-011** stays unclaimed for the whole
sweep — D3 struck it at Step 0.

## Context files

Under `src/io/brodgar/addon/`, tagged with the tasks that need each:

- `UiApi` (`list` at the builder, `attach`), `Controls` (`list`) — 1
- `VrApi` (`list`, `pointer`, `allEntities`, and its javadoc on cross-kind verbs), `LuaCollection`
  (`create`, `Source`, `destroyable`, `removeMember`), `SurfaceInput` (`pointer`) — 1
- `UiApi` (`find`, `all`), `LuaWidget` (`find`, `all`), `Retired` (`uiMoved`, `uiKept` — the two
  existing `find`/`all` keys must go on firing from the global half) — 2
- `LuaWidget` (`cell` → `Controls.cell`, and `at`), `LuaItem` (`cell` — **read only**, it keeps the
  word), `Controls` (`cell`), `UiApi` (`at`), `WorldApi` (`extra.at` — **read only**, it keeps the
  word) — 3
- `MapApi` (`overlay` = `section(owner, "overlay", toggles)`, `toggleCollection`), `LuaMapGrid`
  (`overlay`), `LuaOverlayToggle`, `LuaMask` — 4
- `UiApi` (`overlay`, `newHudOverlay`), `LuaHudOverlay` (`onDraw`, `destroy`, `exists`), `Addon`
  (`hudOverlays`), `LuaOverlay` and `LuaGob` (`overlay` — **read only**, the shape being copied) — 4
- `LuaRule` (`close`), `Sheet`/`Chrome` (the `close` property key) — 5
- `LuaPagina` (`path`, and its `info` snapshot) — 5
- `LuaEvent` (the `Kind` enum's `ACTION`/`MESSAGE` hints, `sender`, `target`) — 5
- `Retired` (`put`, `moved`, `movedObj`) — every task

Under `audit/`, permitted for this feature and named per task on its *Audit* line: `INVENTORY.md`
(every task) · `ns-ui.md` — 1, 2, 3, 5 · `ns-vr.md` — 1 · `ns-world.md` — 3 · `ns-map.md` — 4 ·
`ns-menugrid.md`, `ns-event.md` — 5.

Pages, by task: `ui/lists.md`, `ui/README.md`, `ui/controls/README.md`, `vr/README.md`,
`vr/widgets.md` — 1 · `ui/selectors.md`, `ui/widget.md`, `ui/replace.md`, `references.md` — 2 ·
`ui/items.md`, `ui/lists.md`, `ui/mouse.md` — 3 · `map/overlays.md`, `map/grids.md`, `map/README.md`,
`ui/drawing.md`, `guides/custom-ui.md` — 4 · `ui/style/chrome.md`, `ui/style/README.md`,
`guides/theming.md`, `menugrid.md`, `event/streams.md` — 5 · `conventions.md`, `types.md` and
`DOCUMENTATION.md` — every task.

**The list above is where each task's own surface is documented, and it is not the sweep.** A retired
name may not appear anywhere under `docs/`, so a rename is swept over the whole tree and lands on pages
no task's list names — `ui/edit.md`, `ui/native.md`, `ui/pixels.md`, `ui/custom.md`, `ui/style/keys.md`,
`ui/style/geometry.md`, `player.md`, `shapes.md`, `runtime.md`, `guides/debugging.md`,
`guides/saved-data.md`. Thirty-three pages carried one of these ten spellings; derive the refusal table
from the engine and grep, rather than working the list.

Consumers: **`addons/widgetstack` and `addons/eventstack` both use the selector verbs**, and
`addons/clickpath` uses the HUD painter — `audit/16-consumer-evidence.md` names the sites. Grep all
five for every renamed spelling before landing a task.
