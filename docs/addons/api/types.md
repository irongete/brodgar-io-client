# Data types: the snapshot shapes

Every plain Lua table the read APIs hand back, field by field. A **snapshot** is a point-in-time copy,
so it never updates; a field marked *optional* is absent (Lua `nil`) when the underlying data is not
available yet or is still resolving, so guard for it. See
[snapshots vs handles](conventions.md#snapshots-vs-handles) for when you get one of these and when you
get a live object instead.

## GobInfo

A game object's fields as one plain table, the snapshot [`gob:info()`](gob.md) returns. It is the
escape hatch for logging and serialising; to *read* a gob, call its [methods](gob.md#read), which are
always fresh. [`hafen.world`](world.md) and the `GobAdded`/`GobRemoved` events hand out Gob
**objects**, not this table.

| Field | Type | Notes |
|---|---|---|
| `id` | number | stable gob id |
| `x`, `y` | number | world position; optional (absent before the position is known) |
| `angle` | number | facing, radians |
| `name` | string | the **resource** identity, e.g. `"gfx/kritter/rabbit/rabbit"` — *not* a display name; optional |
| `isplayer` | bool | true if the gob is a player body; present only when `name` is |
| `hp` | number | 0..1 remaining object integrity (1 = undamaged); optional |
| `moving` | bool | whether it is moving |
| `speed` | number | movement speed; present only while moving |
| `speech` | string | current floating speech text; optional |
| `icon` | string | minimap icon category name; optional |
| `overlays` | string[] | active overlay resource names (crop stage, fire, …); optional |

> **Other players' display names are not available**, a limit of the client and the protocol. `name` is
> the body resource. A name resolves only for the local player,
> [`hafen.player():name()`](player.md), or for a kin, [`hafen.kin`](kin.md).

## Item

An item inside a container. From any widget's [`:items()`](ui/items.md) — your backpack
(`hafen.ui.inventory()`), your worn gear (`hafen.ui.equipment()`), a chest, a cupboard — and from
[`hafen.ui.hand()`](ui/widget.md) for the cursor item. Every field is optional.

| Field | Type | Notes |
|---|---|---|
| `res` | string | resource name (stable identity) |
| `name` | string | display name |
| `num` | number | stack count (absent for a non-stack) |
| `wear` | number | 0..100 wear or progress percentage (absent when 0) |
| `handle` | number | the item's server widget id — the [ItemRef](conventions.md#itemref-an-inventory-or-equipment-item) [`hafen.act():item`](act.md) takes |
| `pos` | table \| string | inventory: the `{x, y}` grid cell. equipment: the slot name. Absent for the cursor item. |
| `slot` | number | equipment only: the raw equipment slot index |

Quality, and the contents of a container held as an item, are not exposed: the client has no typed
field for either.

## Tile

From [`hafen.world():tile`](world.md#terrain-and-coordinates). `{ id = number, name = string? }` —
tileset id plus resource name.

## Attr

A character attribute. From [`hafen.char.attr`](char.md) and `attrs`.
`{ base = number, comp = number }` — the raw base value against the computed, buffed value.

## Food

From [`hafen.char.food`](char.md) and `FepChanged`.

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

## StudySlot

From [`hafen.study.slots`](study.md) and `StudyChanged`.

| Field | Type | Notes |
|---|---|---|
| `res`, `name` | string | the curiosity item; optional |
| `lp` | number | learning points |
| `attention` | number | mental weight |
| `cost` | number | experience cost |
| `time` | number | **total** study time in seconds; there is no per-item countdown |
| `progress` | number | 0..1 study progress; best-effort, optional |

`hafen.study.summary()` returns the live totals `{ lp, attention, cost }`.

## Skill, Credo, Experience

- **Skill** — `{ name = string, res = string? }`. [`skillsAvailable`](char.md) adds `cost`, the LP
  price.
- **Credos** — `{ acquired = Skill[], available = Skill[], cost = number, pursuing = {...}? }`, where
  `pursuing`, present only while pursuing one, is
  `{ name, res?, level, levelTotal, quest, questTotal, questId }`.
- **Experience** — `{ name = string?, res = string?, score = number, mtime = number }`.

## PartyMember

From [`hafen.party`](party.md). There is **no name** field for party members.

| Field | Type | Notes |
|---|---|---|
| `id` | number | member gob id |
| `x`, `y` | number | live position if in view, else last-known; optional |
| `color` | [Color](#color) | party colour; optional |
| `leader` | bool | whether this member is the party leader |

## Buff

From [`buff:info()`](buff.md#read), the one snapshot escape hatch. `hafen.buff()` and the
`BuffAdded`/`BuffRemoved`/`BuffChanged` events hand you live [`Buff` objects](buff.md), not this table.

| Field | Type | Notes |
|---|---|---|
| `res`, `name` | string | resource plus display name; optional |
| `amount` | number | 0..1 fraction; content-defined, often absent |
| `duration` | number | 0..1 fraction of the buff's run that is left; content-defined, often absent — **not** seconds |
| `number` | number | integer overlay; content-defined, often absent |

## Meter

From [`meter:info()`](meter.md#read), the one snapshot escape hatch. `hafen.meter()` and the
`MeterAdded`/`MeterRemoved`/`MeterChanged` events hand you live [`Meter` objects](meter.md), not this
table.

| Field | Type | Notes |
|---|---|---|
| `res` | string | the background resource name, the meter's identity; optional |
| `index` | number | its 1-based HUD position; absent once the meter is gone |
| `value` | number | the **first** segment's fill fraction, 0..1; optional |
| `color` | [Color](#color) | the **first** segment's colour; optional (content-defined) |
| `segments` | `{value, color?}[]` | the whole bar, 1-based — always present, may be empty |

## KinEntry

From [`kin:info()`](kin.md#read), the one snapshot escape hatch. The roster and `KinChanged` hand you
live [`Kin` objects](kin.md), not this table.

| Field | Type | Notes |
|---|---|---|
| `id` | number | kin id |
| `name` | string | kin name or nick; optional |
| `group` | number | the kin's group, 0..254 |
| `color` | [Color](#color) | the group's colour; **absent for a group of 8 or higher** — the client draws eight |
| `online` | bool | whether the kin is online |

## Quest and Condition

From [`hafen.quests`](quests.md) and `QuestAdded`/`QuestDone`.

**Quest** — `{ id, name?, res?, status, mtime }`, where `status` is `"pending"`, `"done"`, `"failed"`
or `"disabled"`. [`hafen.quests.selected()`](quests.md) additionally sets `conds = Condition[]`.

**Condition** — `{ desc = string?, status = "pending"|"done"|"failed", text = string? }`.

## Wound

From [`hafen.wounds.list`](wounds.md) and `WoundChanged`. Wounds form a **tree**.

| Field | Type | Notes |
|---|---|---|
| `id` | number | wound id |
| `name`, `res` | string | wound type; optional |
| `severity` | string | the magnitude the client shows, usually a number and **not** seconds; optional |
| `parentid` | number | parent wound id, or `-1` for a root wound |
| `level` | number | tree depth (indent) |

## Craft and CraftSpec

From [`hafen.craft.current`](craft.md).

**Craft** —
`{ recipe = string, inputs = CraftSpec[], outputs = CraftSpec[], qmod = ResRef[], tools = ResRef[] }`,
where a `ResRef` is `{ res = string?, name = string? }`.

**CraftSpec** — `{ res = string?, name = string?, num = number, opt = bool }`. `num` is the required
or produced count, and `-1` means unspecified, which behaves as 1. `opt` marks an optional ingredient
or a chance byproduct.

## Maneuver, DeckCard, FightSummary

From [`hafen.fight`](fight.md).

- **Maneuver** — `{ res?, name?, avail = number, used = number }`, `avail` slottable against `used`
  slotted.
- **DeckCard** — `{ slot = number, key = string, res?, name?, used = number }`, `slot` the raw 0-based
  deck index and `key` the hotkey label such as `"1"` or `"⇧1"`.
- **FightSummary** — `{ maxact, used, nact, nsave, usesave }`.

## ActionbarSlot

From [`slot:info()`](actionbar.md#read), the snapshot escape hatch; `nil` for an empty slot.
`{ res = string?, name = string?, cooldown = number? }` — `cooldown`, 0..1, is present only for an
ability slot with a meter, and is **not** seconds. The live reads are `slot:res()`, `:name()` and
`:cooldown()`.

## Pagina

From [`pag:info()`](menugrid.md#read) and [`hafen.menugrid():list()`](menugrid.md#read), the snapshot
escape hatch for an action-menu entry.

`{ res = string, exists = bool, name = string?, tooltip = string?, hotkey = string?, path = string[]?,
parent = string?, isnew = bool? }` — `res` is the identity and is always present, and **`parent` is
the parent's resource name**, not an object. Every other field is absent when the menu cannot answer
it: the entry is gone, or its resource has not loaded. The live reads are `pag:res()`, `:name()`,
`:parent()` and the rest.

## Marker

From [`marker:info()`](map/markers.md#the-marker-object), the snapshot escape hatch for a map marker. The live
reads are `marker:name()`, `:type()`, `:segmentTile()` and the rest — and `marker:position()` is the
place to store, not the `seg` + `tc` below.

| Field | Type | Notes |
|---|---|---|
| `name` | string | marker label; optional |
| `type` | string | `"player"`, a user pin, or `"system"`, a server or quest pin |
| `seg` | string | segment id, a 64-bit value as a decimal string — client-local, [never stored](map/grids.md#storing-a-place) |
| `tc` | `{x, y}` | segment tile coord — client-local, never stored |
| `color` | [Color](#color) | player markers only; optional |
| `onmap` | bool | player markers only |
| `icon` | string | system markers only; optional |
| `x`, `y` | number | session-local world position; present only while the marker is in your current segment |
| `dist` | number | distance from the player; present with `x`, `y` |

## IconCategory

From [`cat:info()`](map/icons.md#the-iconcat-object), the snapshot escape hatch for a minimap icon category.
`{ name = string, res = string, show = bool, notify = bool }` — `res` is the identity, `name` the
icon tooltip, and `show` and `notify` the minimap-draw and spawn-notify flags. The live reads are
`cat:res()`, `:name()`, `:show()` and `:notify()`.

## Color

A `{ r, g, b, a }` table, each component 0..255. Used by party, kin and marker colours, and accepted
positionally wherever a colour goes in; see [colours](conventions.md#colours).

## See also

- [conventions](conventions.md#snapshots-vs-handles) — why some readers hand back an object instead
- [events](event.md) — which of these shapes arrives as an event payload
- [Gob](gob.md) — the live-object counterpart of `GobInfo`, and the usual way to read a gob
