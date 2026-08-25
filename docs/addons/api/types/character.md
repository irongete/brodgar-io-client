# Data types: the character sheet

The snapshot shapes off one character's own sheet: its attributes, what it has eaten, what it is
learning, the speed it moves at, its quests, its wounds and the buffs on it. Each is what `:info()`
copies out of a live object, so it never updates — the live reads are verbs on that object. The model,
and what *optional* means on the tables below, is on [the catalogue](README.md).

## Attr

From [`attr:info()`](../char.md#attributes). `{ base = number, comp = number }` — the raw base value against
the computed, buffed value. `s:char():attr()` hands out live [`Attr` objects](../char.md#attributes),
not this table.

## Food

From [`food:info()`](../char.md#food), the one snapshot escape hatch. `s:char():food()` and the
`FepChanged` event hand you a live [`Food` object](../char.md#food), not this table. Either half is absent
until its meter arrives.

```lua
{
  fep = {
    cap   = number,            -- FEP bar capacity
    total = number,            -- sum of the entry amounts
    entries = {                -- one per food-event group
      { res = string?, name = string?, amount = number },
    },
  },
  hunger = { level = number, label = string?, efficacy = number },
}
```

## Skill, Credo, Experience

From `:info()` on each. [`session:char`](../char.md) hands out the live objects; these are the snapshots.

- **Skill** — `{ name = string, res = string?, cost = number, known = bool }`, where `known`
  distinguishes a learnt skill from one that can still be bought.
- **Credo** — `{ name = string, res = string?, acquired = bool, pursuing = bool }`, plus
  `{ level, levelTotal, quest, questTotal, questId }` on the credo being pursued and on no other.
- **Experience** — `{ name = string?, res = string, score = number, mtime = number }`, where `mtime` is
  the server's change stamp, which `exp:modified()` reads.

## StudySlot and StudySummary

From [`slot:info()`](../study.md#a-slot), the one snapshot escape hatch. `s:study():curiosity()` and the
`StudyChanged` event hand you live [`StudySlot` objects](../study.md#a-slot), not this table.

| Field | Type | Notes |
|---|---|---|
| `res` | string | the curiosity item's resource, its identity |
| `name` | string | display name; optional |
| `lp` | number | learning points; optional |
| `attention` | number | mental weight; optional |
| `cost` | number | experience cost; optional |
| `time` | number | **total** study time in seconds; there is no per-item countdown; optional |
| `progress` | number | 0..1 study progress; best-effort, optional |

**StudySummary** — `{ lp, attention, cost }`, the totals across the whole window, from
[`sum:info()`](../study.md#the-summary). `s:study():summary()` itself hands you the live
[`StudySummary` object](../study.md#the-summary), not this table.

## Speed

From [`sp:info()`](../speed.md#the-speed-object), the snapshot escape hatch for one movement speed.
`s:speed()` hands out live [`Speed` objects](../speed.md#the-speed-object), not this table.

`{ index = number, wire = number, name = string, available = bool, current = bool }` — `index` is the
1-based position `1..4` and `wire` the raw number `0..3`
and the speed's identity, `available` says whether it can be picked right now, and `current` whether it is
the one your character is on. The live reads are `sp:index()`, `:wire()`, `:name()` and
`:available()` — a boolean here, unlike `man:dealable()`; whether you are on it is
`s:speed():current() == sp`, since the objects are interned.

## Quest and Condition

What `q:info()` and `c:info()` hand back on [`session:quest`](../quest.md)'s objects; the reads themselves
are verbs on those objects.

**Quest** — `{ id, title?, res?, status, mtime }`, where `status` is `"pending"`, `"done"`, `"failed"`
or `"disabled"` and `mtime` is the server's change stamp, which `q:modified()` reads.

**Condition** — `{ desc = string?, status = "pending"|"done"|"failed", text = string? }`, where `desc`
is what `c:description()` reads.

## Wound

What `w:info()` hands back on [`session:wound`](../wound.md)'s objects. Wounds form a **tree**, and the
`parentid` here is the id `w:parent()` resolves to the wound itself.

| Field | Type | Notes |
|---|---|---|
| `id` | number | wound id |
| `name`, `res` | string | wound type; optional |
| `severity` | string | the magnitude as the client spells it, `w:label()`; **not** seconds; optional |
| `parentid` | number | parent wound id, or `-1` for a root wound |
| `level` | number | tree depth (indent) |

## Buff

From [`buff:info()`](../buff.md#read), the one snapshot escape hatch. `s:buff():list()` and the
`BuffAdded`/`BuffRemoved`/`BuffChanged` events hand you live [`Buff` objects](../buff.md), not this table.

| Field | Type | Notes |
|---|---|---|
| `res`, `name` | string | resource plus display name; optional |
| `amount` | number | 0..1 fraction; content-defined, often absent |
| `duration` | number | 0..1 fraction of the buff's run that is left; content-defined, often absent — **not** seconds |
| `number` | number | integer overlay; content-defined, often absent |

## See also

- [the catalogue](README.md) — every snapshot shape, and what a snapshot is
- [`session:char`](../char.md) — the live `Attr`, `Food`, `Skill`, `Credo` and `Experience` objects
- [`session:study`](../study.md) — the live study window these two shapes copy
- [shapes](../shapes.md) — the anonymous tables these fields carry: places, sizes, colours, units
