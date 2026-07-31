# Data types (snapshot shapes)

The read APIs return plain Lua tables called **snapshots** — point-in-time copies. Fields are listed
with their type; a field marked *optional* is absent (Lua `nil`) when the underlying data isn't
available yet or is still resolving, so guard for it. See [conventions](conventions.md#snapshots-vs-handles).

## GobInfo

A game object's fields as one plain table — the snapshot [`gob:info()`](gob.md) returns. It is the
escape hatch for logging/serialising; to *read* a gob, call its [methods](gob.md#methods), which are
always fresh. ([`hafen.world.*`](world.md) and the `GobAdded`/`GobRemoved` events hand out Gob
**objects**, not this table.)

| Field | Type | Notes |
|---|---|---|
| `id` | number | stable gob id |
| `x`, `y` | number | world position; optional (absent before the position is known) |
| `angle` | number | facing, radians |
| `name` | string | the **resource/type** identity, e.g. `"gfx/kritter/rabbit/rabbit"` — *not* a display name; optional (Loading-guarded) |
| `isplayer` | bool | true if the gob is a player body; present only when `name` is |
| `hp` | number | 0..1 remaining object integrity (1 = undamaged); optional |
| `moving` | bool | whether it is moving |
| `speed` | number | movement speed; present only while moving |
| `speech` | string | current floating speech text; optional |
| `icon` | string | minimap icon/category name; optional |
| `overlays` | string[] | active overlay resource names (crop stage, fire, …); optional |

> Other players' **display names** are not available (a client/protocol limitation). `name` is the
> body resource. A name resolves only for the local player ([`hafen.player():name()`](player.md)) or a kin
> ([`hafen.kin`](kin.md)).

## Item

An inventory or equipment item. From [`hafen.items.*`](items.md) and a model's
[`:items()`](ui.md#model-handle). Every field is optional.

| Field | Type | Notes |
|---|---|---|
| `res` | string | resource name (stable identity) |
| `name` | string | display name (`ItemInfo.Name`) |
| `num` | number | stack count (absent for a non-stack) |
| `wear` | number | 0..100 wear/progress % (absent when 0) |
| `handle` | number | the item's server widget id — the [ItemRef](conventions.md#itemref--an-inventoryequipment-item) `hafen.act.item` takes |
| `pos` | table \| string | inventory: `{x, y}` grid cell. equipment: the slot name string. Absent for the cursor item. |
| `slot` | number | equipment only: the raw equipment slot index |

`quality` and container `contents` are not exposed (no typed client field).

## Tile

From [`hafen.map.tile`](map.md). `{ id = number, name = string? }` — tileset id + resource name.

## Attr

A character attribute. From [`hafen.char.attr`](char.md)/`attrs`. `{ base = number, comp = number }` —
raw base value vs computed/buffed value.

## Vitals

From [`hafen.player():vitals()`](player.md) and `VitalsChanged`. `{ hp, stamina, energy }`, each a bar
fraction 0..1. No absolute values exist.

## food

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

From [`hafen.study.slots`](char.md#hafenstudy) and `StudyChanged`.

| Field | Type | Notes |
|---|---|---|
| `res`, `name` | string | the curiosity item; optional |
| `lp` | number | learning points |
| `attention` | number | mental weight |
| `cost` | number | experience cost |
| `time` | number | **total** study time, seconds (there is no per-item countdown) |
| `progress` | number | 0..1 study progress; best-effort, optional |

`hafen.study.summary()` returns the live totals `{ lp, attention, cost }`.

## Skill / Credo / Experience

- **Skill** — `{ name = string, res = string? }`. [`skillsAvailable`](char.md) adds `cost` (LP price).
- **Credos** — `{ acquired = Skill[], available = Skill[], cost = number, pursuing = {...}? }` where
  `pursuing` (present only while pursuing one) is
  `{ name, res?, level, levelTotal, quest, questTotal, questId }`.
- **Experience** — `{ name = string?, res = string?, score = number, mtime = number }`.

## PartyMember

From [`hafen.party.*`](party.md). There is **no name** field for party members.

| Field | Type | Notes |
|---|---|---|
| `id` | number | member gob id |
| `x`, `y` | number | live position if in view, else last-known; optional |
| `color` | [Color](#color) | party colour; optional |
| `leader` | bool | whether this member is the party leader |

## Buff

From [`hafen.buffs.list`](buffs.md) and `BuffAdded`/`BuffRemoved`/`BuffChanged`.

| Field | Type | Notes |
|---|---|---|
| `res`, `name` | string | resource + display name; optional |
| `amount` | number | 0..1 fraction; content-defined, often absent |
| `cooldown` | number | 0..1 fraction; content-defined, often absent — **not** seconds |
| `number` | number | integer overlay; content-defined, often absent |

## KinEntry

From [`kin:info()`](kin.md#read) — the one snapshot escape hatch. The roster and `KinChanged` hand you
live [`Kin` objects](kin.md), not this table.

| Field | Type | Notes |
|---|---|---|
| `id` | number | kin id |
| `name` | string | kin name/nick; optional |
| `group` | number | the kin's group, 0..254 |
| `color` | [Color](#color) | the group's colour; **absent for a group ≥ 8** (the client draws 8 colours) |
| `online` | bool | whether the kin is online |

## Quest / Condition

From [`hafen.quests.*`](quests.md) and `QuestAdded`/`QuestDone`.

**Quest** — `{ id, name?, res?, status, mtime }` where `status` is `"pending"` | `"done"` |
`"failed"` | `"disabled"`. [`hafen.quests.selected()`](quests.md) additionally sets
`conds = Condition[]`:

**Condition** — `{ desc = string?, status = "pending"|"done"|"failed", text = string? }`.

## Wound

From [`hafen.wounds.list`](wounds.md) and `WoundChanged`. Wounds form a **tree**.

| Field | Type | Notes |
|---|---|---|
| `id` | number | wound id |
| `name`, `res` | string | wound type; optional |
| `severity` | string | magnitude the client shows (usually a number, **not** seconds); optional |
| `parentid` | number | parent wound id, or `-1` for a root wound |
| `level` | number | tree depth (indent) |

## Craft / CraftSpec

From [`hafen.craft.current`](craft.md).

**Craft** — `{ recipe = string, inputs = CraftSpec[], outputs = CraftSpec[], qmod = ResRef[], tools = ResRef[] }`
where a `ResRef` is `{ res = string?, name = string? }`.

**CraftSpec** — `{ res = string?, name = string?, num = number, opt = bool }`. `num` is the
required/produced count; `-1` means unspecified (≈ 1). `opt` marks an optional ingredient or chance
byproduct.

## Maneuver / DeckCard / FightSummary

From [`hafen.fight.*`](fight.md).

- **Maneuver** — `{ res?, name?, avail = number, used = number }` (`avail`/`used` = slottable/slotted).
- **DeckCard** — `{ slot = number, key = string, res?, name?, used = number }` (`slot` = raw 0-based
  deck index, `key` = hotkey label such as `"1"` or `"⇧1"`).
- **FightSummary** — `{ maxact, used, nact, nsave, usesave }`.

## ActionbarSlot

From [`hafen.actionbar.slot`](actionbar.md) and `ActionbarChanged`.
`{ res = string?, name = string?, cooldown = number? }` — `cooldown` (0..1) is present only for an
ability slot with a meter; **not** seconds.

## Marker

From [`hafen.markers.*`](markers.md) and `MarkersChanged`.

| Field | Type | Notes |
|---|---|---|
| `id` | number | session-local marker ref (pass to `remove`) |
| `name` | string | marker label; optional |
| `type` | string | `"player"` (a user pin) or `"system"` (a server/quest pin) |
| `seg` | string | segment id (64-bit, as a decimal string) — the persistent anchor |
| `tc` | `{x, y}` | segment tile coord — the persistent anchor |
| `color` | [Color](#color) | player markers only; optional |
| `onmap` | bool | player markers only |
| `icon` | string | system markers only; optional |
| `x`, `y` | number | session-local world position; present only when the marker is in your current segment |
| `dist` | number | distance from the player; present with `x`,`y` |

## RadarCategory

From [`hafen.radar.categories`](radar.md).
`{ name = string, res = string, show = bool, notify = bool }` — `res` is the stable id, `name` the
icon tooltip, `show`/`notify` the minimap-draw / spawn-notify flags.

## Color

A `{ r, g, b, a }` table, each 0..255. Used by party, kin, and marker colours.
