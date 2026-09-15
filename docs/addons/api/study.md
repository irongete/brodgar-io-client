# session:study: Study Desk & Curiosities

Read the curiosities currently being studied on a character's study desk, along with total Learning Points, Attention, and Experience costs.

```lua
local session = hafen.session():current()
if not session then return end

local study_desk = session:study()
local totals = study_desk:summary()
if totals then
  hafen.log():write(string.format("Attention: %d | Total LP: %d", totals:attention() or 0, totals:lp() or 0))
end

for _, slot in ipairs(study_desk:curiosity():list()) do
  hafen.log():write(string.format("%s: %d LP (cost: %d exp)", slot:name() or slot:res(), slot:lp() or 0, slot:cost() or 0))
end
```

---

## Methods on `session:study()`

| Method | Parameters | Returns | Description |
|---|---|---|---|
| `:curiosity()` | None | `StudySlotCollection` | Collection of curiosities currently placed in study slots. |
| `:summary()` | None | `StudySummary \| nil` | Totals across all curiosities in the window (`nil` if study tab is closed). |

---

## Methods on `StudySlotCollection` (`session:study():curiosity()`)

A slot has no server address or key, as identical curiosities can occupy multiple slots simultaneously.

| Method | Parameters | Returns | Description |
|---|---|---|---|
| `:list(filter?)` | `[string \| function]` | `StudySlot[]` | Array of all curiosities currently in the window. |
| `:count(filter?)` | `[string \| function]` | `number` | Total number of matching curiosities in study. |
| `:find(filter)` | `string \| function` | `StudySlot \| nil` | First curiosity matching resource or display name. |

---

## Methods on `StudySlot`

| Method | Returns | Description |
|---|---|---|
| `:res()` | `string \| nil` | Curiosity item resource path. |
| `:name()` | `string \| nil` | Display name of the curiosity. |
| `:lp()` | `number \| nil` | Total Learning Points yielded upon completion. |
| `:attention()` | `number \| nil` | Mental weight (attention cost) consumed. |
| `:cost()` | `number \| nil` | Experience point cost. |
| `:time()` | `number \| nil` | Total study time in seconds (not remaining countdown). |
| `:progress()` | `number \| nil` | Completion progress fraction (`0.0..1.0`). |
| `:widget()` | `Widget \| nil` | The UI widget rendering this slot. |
| `:exists()` | `boolean` | `true` if this curiosity remains in the study desk. |
| `:info()` | `table \| nil` | Plain table snapshot. See [`StudySlot`](types/character.md#studyslot-and-studysummary). |

---

## Methods on `StudySummary`

| Method | Returns | Description |
|---|---|---|
| `:lp()` | `number \| nil` | Total Learning Points the study desk will yield. |
| `:attention()` | `number \| nil` | Total Attention consumed. |
| `:cost()` | `number \| nil` | Total Experience cost required. |
| `:exists()` | `boolean` | `true` if the character's study desk tab remains open. |
| `:info()` | `table \| nil` | Plain table snapshot. See [`StudySummary`](types/character.md#studyslot-and-studysummary). |

---

## Events

Subscribe to `StudyChanged` on `hafen.event()`:

```lua
hafen.event():on("StudyChanged", function(slot_list)
  hafen.log():write(string.format("Study desk updated: %d curiosities active", #slot_list))
end)
```

---

## See Also

- [`session:char`](char.md) — Character attributes, intelligence, and learning points.
- [`StudySlot` & `StudySummary` Types](types/character.md#studyslot-and-studysummary) — Snapshot schemas.
