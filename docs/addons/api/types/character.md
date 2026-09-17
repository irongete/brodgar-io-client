# Data Types: The Character Sheet

The snapshot shapes off one character's sheet: attributes, food, learning, movement speed, quests, wounds and buffs. Each is what `:info()` copies out of a live object; what *optional* means is on [the catalogue](README.md).

```lua
local food = hafen.session():current():char():food()
local snapshot = food and food:info()
if snapshot and snapshot.fep then hafen.log():write(("fep %.0f/%.0f"):format(snapshot.fep.total, snapshot.fep.cap)) end
```

---

## Attr

From [`attr:info()`](../char.md#attributes): `{ base = number, comp = number }`, the raw base value against the computed, buffed value. `session:char():attr()` hands live [`Attr` objects](../char.md#attributes), not this table. `nil` while the server has published nothing for that attribute; an attribute of exactly zero on both halves reads as the same `nil`, since the client keeps no published flag and builds a zero record for any name asked. A living character carries no attribute of zero.

## Food

From [`food:info()`](../char.md#food). `session:char():food()` and `FepChanged` hand a live [`Food` object](../char.md#food), not this table. Either half is absent until its meter arrives.

```lua
local shape = {
  fep = {
    cap   = 0,                 -- FEP bar capacity
    total = 0,                 -- sum of the entry amounts
    entries = {                -- one per food-event group
      { res = "", name = "", amount = 0 },    -- res and name optional
    },
  },
  hunger = { level = 0, label = "", efficacy = 0 },   -- label optional
}
```

## Skill, Credo, Experience

From `:info()` on each; [`session:char`](../char.md) hands the live objects.

| Shape | Fields |
|---|---|
| `Skill` | `{ name = string, res = string?, cost = number, known = bool }`; `known` distinguishes a learnt skill from one buyable. `name` is always a string, the server's token for the skill while its resource loads, settling to the display name once the resource does. |
| `Credo` | `{ name = string, res = string?, acquired = bool, pursuing = bool }`, plus `{ level, levelTotal, quest, questTotal, questId }` on the pursued credo only; `level` is the live `credo:rank()`, `quest` the live `credo:questsDone()`. |
| `Experience` | `{ name = string?, res = string, score = number, mtime = number }`; `mtime` is the server's change stamp, `experience:modified()`. |

## StudySlot and StudySummary

From [`slot:info()`](../study.md#a-slot). `session:study():curiosity()` and `StudyChanged` hand live [`StudySlot` objects](../study.md#a-slot), not this table.

| Field | Type | Notes |
|---|---|---|
| `res` | `string` | The curiosity item's resource, its identity; optional while loading. |
| `name` | `string` | Display name; optional. |
| `lp` | `number` | Learning points; optional. |
| `attention` | `number` | Mental weight; optional. |
| `cost` | `number` | Experience cost; optional. |
| `time` | `number` | Total study time in seconds, no per-item countdown; optional. |
| `progress` | `number` | `0..1` study progress; best-effort, optional. |

`StudySummary` is `{ lp, attention, cost }`, the totals across the window, from [`summary:info()`](../study.md#the-summary); `session:study():summary()` hands the live object.

## Speed

From [`speed:info()`](../speed.md#the-speed-object). `session:speed()` hands live [`Speed` objects](../speed.md#the-speed-object), not this table. `{ index = number, wire = number, name = string, available = bool, current = bool }`: `index` the 1-based position `1..4`, `wire` the raw number `0..3`, `available` whether it can be picked now, `current` whether your character is on it (live: `session:speed():current() == speed`).

## Quest and Condition

From `quest:info()` and `condition:info()` on [`session:quest`](../quest.md)'s objects.

| Shape | Fields |
|---|---|
| `Quest` | `{ id, title?, res?, status?, mtime }`; `status` is `"pending"`, `"done"`, `"failed"` or `"disabled"`, absent for a status this client has no word for; `mtime` is the server's change stamp, `quest:modified()`. |
| `Condition` | `{ desc = string?, status = "pending"\|"done"\|"failed", text = string? }`; `desc` is `condition:description()`, `text` the tooltip line. |

## Wound

From `wound:info()` on [`session:wound`](../wound.md)'s objects. Wounds form a tree; `parentid` is the id `wound:parent()` resolves to the wound.

| Field | Type | Notes |
|---|---|---|
| `id` | `number` | Wound id. |
| `name`, `res` | `string` | Wound type; optional. |
| `severity` | `number` | The magnitude (`wound:severity()`), not seconds; absent where the client spells it as a word, which `wound:label()` answers. |
| `parentid` | `number` | Parent wound id, or `-1` for a root. |
| `level` | `number` | Tree depth (indent). |

## Buff

From [`buff:info()`](../buff.md#read). `session:buff():list()` and the buff events hand live [`Buff` objects](../buff.md), not this table.

| Field | Type | Notes |
|---|---|---|
| `res`, `name` | `string` | Resource plus display name; optional. |
| `amount` | `number` | `0..1` fraction; content-defined, often absent. |
| `duration` | `number` | `0..1` fraction of the run left, the live `buff:remaining()`; content-defined, often absent; not seconds. |
| `number` | `number` | Integer overlay; content-defined, often absent. |

---

## See Also

- [The catalogue](README.md) — every snapshot shape, and what a snapshot is.
- [`session:char`](../char.md) — the live `Attr`, `Food`, `Skill`, `Credo` and `Experience` objects.
- [`session:study`](../study.md) — the live study window these shapes copy.
- [Shapes](../shapes.md) — the anonymous tables these fields carry.
