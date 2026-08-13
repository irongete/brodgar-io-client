# Data types: the snapshot shapes

Every plain Lua table `:info()` hands back, field by field. A read gives you a live object; `:info()` is
the one escape hatch that copies it, for logging, serialising or diffing. A **snapshot** is a
point-in-time copy, so it never updates; a field marked *optional* is absent (Lua `nil`) when the
underlying data is not available yet or is still resolving, so guard for it. See
[snapshots vs handles](conventions.md#snapshots-vs-handles).

> **A snapshot field keeps the client's own spelling; the live read is the verb.** The API's verbs are
> camelCase (`:isPlayer()`, `:modified()`, `:qualityInputs()`) because you write them. A snapshot is the
> shape the client holds, handed over as it is — `isplayer`, `mtime`, `qmod` — so what you serialise is
> what the client said. Each table below names the verb beside the field wherever the two differ.

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
| `isplayer` | bool | true if the gob is a player body, read live as `gob:isPlayer()`; present only when `name` is |
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

From [`item:info()`](ui/items.md#the-item-object), the one snapshot escape hatch. Any widget's
[`:items()`](ui/items.md) — your backpack (`hafen.ui():inventory()`), your worn gear
(`hafen.ui():equipment()`), a chest, a cupboard — and [`hafen.player():hand()`](player.md#the-hand) for
the cursor item hand you a live [`Item` object](ui/items.md#the-item-object), not this table. Every field
is optional.

| Field | Type | Notes |
|---|---|---|
| `res` | string | resource name (stable identity) |
| `name` | string | display name |
| `num` | number | stack count (absent for a non-stack) |
| `wear` | number | 0..100 wear or progress percentage (absent when 0) |
| `quality` | number | the quality the tooltip shows (absent for an item that has none) |
| `contents` | table | what it holds, as the [Contents](#contents) snapshot (absent for an item holding nothing) |
| `handle` | number | its server widget id, the number it is addressed by on the wire (absent once the item is gone) |
| `cell` | table | the `{x, y}` grid cell it sits in (absent for a worn or cursor item, and for one inside another item) |
| `slots` | string[] | the equipment slots it fills, by name (absent when it is not worn) |

A snapshot holds no live objects. That is why there is no `container` field here, though
[`item:container()`](ui/items.md#what-an-item-holds) reads one, and why the `contents` snapshot below carries
no `items`: a snapshot of a bag would otherwise nest snapshots of bags without end.

## Contents

From [`contents:info()`](ui/items.md#what-an-item-holds), the snapshot of what one item holds;
[`item:contents()`](ui/items.md#what-an-item-holds) hands you the live object, and its
[Item objects](ui/items.md#the-item-object) are read off that with `contents:items()`.
`{ name = string? }` — what the server calls this inside, the caption its own window carries, absent when it
gave none.

## Tile

From [`hafen.world():tile`](world.md#terrain-and-coordinates). `{ id = number, name = string? }` —
tileset id plus resource name.

## Position

From [`p:info()`](world.md#the-position-type). `{ gridId = number, x = number, y = number }` — a grid id
and the offset **within** that grid, which is the durable form and not the same numbers as `p:x()`/`p:y()`.
It is `nil` for a place that is not durable, and it is what `hafen.world():position(saved)` rebuilds from.

## Attr

From [`attr:info()`](char.md#attributes). `{ base = number, comp = number }` — the raw base value against
the computed, buffed value. `hafen.char():attr()` hands out live [`Attr` objects](char.md#attributes),
not this table.

## Food

From [`food:info()`](char.md#food), the one snapshot escape hatch. `hafen.char():food()` and the
`FepChanged` event hand you a live [`Food` object](char.md#food), not this table. Either half is absent
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

## StudySlot

From [`slot:info()`](study.md#a-slot), the one snapshot escape hatch. `hafen.study():slot()` and the
`StudyChanged` event hand you live [`StudySlot` objects](study.md#a-slot), not this table.

| Field | Type | Notes |
|---|---|---|
| `res` | string | the curiosity item's resource, its identity |
| `name` | string | display name; optional |
| `lp` | number | learning points; optional |
| `attention` | number | mental weight; optional |
| `cost` | number | experience cost; optional |
| `time` | number | **total** study time in seconds; there is no per-item countdown; optional |
| `progress` | number | 0..1 study progress; best-effort, optional |

`hafen.study():summary()` returns the live totals `{ lp, attention, cost }`.

## Skill, Credo, Experience

From `:info()` on each. [`hafen.char`](char.md) hands out the live objects; these are the snapshots.

- **Skill** — `{ name = string, res = string?, cost = number, known = bool }`, where `known`
  distinguishes a learnt skill from one that can still be bought.
- **Credo** — `{ name = string, res = string?, acquired = bool, pursuing = bool }`, plus
  `{ level, levelTotal, quest, questTotal, questId }` on the credo being pursued and on no other.
- **Experience** — `{ name = string?, res = string, score = number, mtime = number }`, where `mtime` is
  the server's change stamp, which `exp:modified()` reads.

## PartyMember

From [`member:info()`](party.md#a-member), the one snapshot escape hatch. `hafen.party():list()` hands
you live [`PartyMember` objects](party.md), not this table. There is **no name** field: the client is
never sent one.

| Field | Type | Notes |
|---|---|---|
| `id` | number | member gob id |
| `x`, `y` | number | live position if in view, else last-known; optional |
| `color` | [Color](#color) | party colour; optional |
| `leader` | bool | whether this member is the party leader |

The live reads are `member:id()`, `:position()` — a [Position](world.md#the-position-type), not the two
loose numbers — `:color()` and `:leader()`.

## Buff

From [`buff:info()`](buff.md#read), the one snapshot escape hatch. `hafen.buff():list()` and the
`BuffAdded`/`BuffRemoved`/`BuffChanged` events hand you live [`Buff` objects](buff.md), not this table.

| Field | Type | Notes |
|---|---|---|
| `res`, `name` | string | resource plus display name; optional |
| `amount` | number | 0..1 fraction; content-defined, often absent |
| `duration` | number | 0..1 fraction of the buff's run that is left; content-defined, often absent — **not** seconds |
| `number` | number | integer overlay; content-defined, often absent |

## Meter

From [`meter:info()`](meter.md#read), the one snapshot escape hatch. `hafen.meter():list()` and the
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

## Speed

From [`sp:info()`](speed.md#the-speed-object), the snapshot escape hatch for one movement speed.
`hafen.speed()` hands out live [`Speed` objects](speed.md#the-speed-object), not this table.

`{ index = number, name = string, available = bool, current = bool }` — `index` is the wire number `0..3`
and the speed's identity, `available` says whether it can be picked right now, and `current` whether it is
the one your character is on. The live reads are `sp:index()`, `:name()` and `:available()`; whether you are
on it is `hafen.speed():current() == sp`, since the objects are interned.

## Quest and Condition

What `q:info()` and `c:info()` hand back on [`hafen.quest`](quest.md)'s objects; the reads themselves
are verbs on those objects.

**Quest** — `{ id, title?, res?, status, mtime }`, where `status` is `"pending"`, `"done"`, `"failed"`
or `"disabled"` and `mtime` is the server's change stamp, which `q:modified()` reads.

**Condition** — `{ desc = string?, status = "pending"|"done"|"failed", text = string? }`, where `desc`
is what `c:description()` reads.

## Wound

What `w:info()` hands back on [`hafen.wound`](wound.md)'s objects. Wounds form a **tree**, and the
`parentid` here is the id `w:parent()` resolves to the wound itself.

| Field | Type | Notes |
|---|---|---|
| `id` | number | wound id |
| `name`, `res` | string | wound type; optional |
| `severity` | string | the magnitude the client shows, usually a number and **not** seconds; optional |
| `parentid` | number | parent wound id, or `-1` for a root wound |
| `level` | number | tree depth (indent) |

## Craft and CraftSpec

What `c:info()` hands back on [`hafen.craft`](craft.md)'s Craft; the reads themselves are verbs on it,
where `qmod` is `c:qualityInputs()`.

**Craft** —
`{ recipe = string, inputs = CraftSpec[], outputs = CraftSpec[], qmod = ResRef[], tools = ResRef[] }`,
where a `ResRef` is `{ res = string?, name = string? }`.

**CraftSpec** — `{ res = string?, name = string?, num = number, opt = bool }`. `num` is the required
or produced count, and `-1` means unspecified, which behaves as 1. `opt` marks an optional ingredient
or a chance byproduct.

## Maneuver, DeckCard, FightSummary

From the `:info()` escape hatch on each of [`hafen.fight`](fight.md)'s objects; the reads themselves hand
you the live objects.

- **Maneuver** — `{ res?, name?, avail = number, used = number }`, `avail` dealable against `used`
  dealt. The live reads are `man:res()`, `:name()`, `:available()` and `:used()`.
- **DeckCard** — `{ slot = number, key = string, res?, name?, used? }`, `slot` the raw 0-based deck index
  and `key` the hotkey label such as `"1"` or `"⇧1"`. The maneuver half is absent for an empty slot,
  where the place itself still reads.
- **FightSummary** — `{ maxact, used, nact, nsave, usesave }`, in the window's own spelling; the live
  reads spell them out as `sum:maxActions()`, `:used()`, `:deckSize()`, `:saveCount()` and
  `:activeSave()`.

The combat target has no shape of its own: `target:info()` is `{ id }`, and everything else about the
creature is read off its [Gob](gob.md).

## ActionbarSlot

From [`slot:info()`](actionbar.md#read), the snapshot escape hatch; `nil` for an empty slot.
`{ res = string?, name = string?, cooldown = number? }` — `cooldown`, 0..1, is present only for an
ability slot with a meter, and is **not** seconds. On a slot
[held](actionbar.md#hold-a-slot-unprotected) for an addon's own menu entry, `res` is that entry's
`addon/<addon id>/<id>` identity and `name` the name the addon gave it. The live reads are `slot:res()`,
`:name()` and `:cooldown()`.

## Pagina

From [`pag:info()`](menugrid.md#read) and [`hafen.menugrid():list()`](menugrid.md#read), the snapshot
escape hatch for an action-menu entry.

`{ res = string, exists = bool, addon = string?, name = string?, tooltip = string?, hotkey = string?,
path = string[]?, parent = string?, isnew = bool? }` — `res` is the identity and is always present, and
**`parent` is the parent's resource name**, not an object. `addon` is the id of the addon that added the
entry, and is absent on the game's own. Every other field is absent when the menu cannot answer it: the
entry is gone, or its resource has not loaded. The live reads are `pag:res()`, `:addon()`, `:name()`,
`:parent()`, `:isNew()` and the rest.

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
| `onmap` | bool | player markers only, read and written live as `marker:onMap(b)` |
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
