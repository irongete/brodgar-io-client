# Data types: the session and the world

The snapshot shapes one login hands back: the session itself, an object in the world, the people beside
you, the ground under them, a place to store, and the things of your own standing out there. Each is what
`:info()` copies out of a live object, so it never updates — the live reads are verbs on that object. The
model, and what *optional* means on every table below, is on [the catalogue](README.md).

## Session

From [`s:info()`](../session.md#read), the snapshot escape hatch for one of the logins this client holds.
[`hafen.session`](../session.md) hands out live `Session` objects, not this table.

| Field | Type | Notes |
|---|---|---|
| `user` | string | the account name, and the session's identity; present for a session that has ended |
| `character` | string | the character this login is playing; optional (absent until its HUD is up) |
| `exists` | bool | whether the client still holds this session |
| `current` | bool | whether it is the one on screen; the live read is `hafen.session():current() == s` |

## GobInfo

A game object's fields as one plain table, the snapshot [`gob:info()`](../gob.md) returns. It is the
escape hatch for logging and serialising; to *read* a gob, call its [methods](../gob.md#read), which are
always fresh. [`session:world`](../world.md) and the `GobAdded`/`GobRemoved` events hand out Gob
**objects**, not this table.

| Field | Type | Notes |
|---|---|---|
| `id` | number | stable gob id |
| `x`, `y` | number | world position; optional (absent before the position is known) |
| `angle` | number | facing, radians |
| `name` | string | the **resource** identity, e.g. `"gfx/kritter/rabbit/rabbit"` — *not* a display name; optional |
| `isplayer` | bool | true if the gob is a player body, read live as `gob:player()`; present only when `name` is |
| `hp` | number | 0..1 remaining object integrity (1 = undamaged); optional |
| `moving` | bool | whether it is moving |
| `speed` | number | movement speed; present only while moving |
| `speech` | string | current floating speech text; optional |
| `icon` | string | minimap icon category name; optional |
| `overlays` | string[] | active overlay resource names; optional |
| `sdt` | number[] | 1-based `0..255` state bytes the resource's own code interprets; optional — present only for a resource-drawn gob, empty array included |
| `visible` | bool | whether the client draws it, read live as [`gob:visible()`](../gob.md#drawn-or-not-unprotected); `true` for an object nobody hid |

> **Other players' display names are not available**, a limit of the client and the protocol. `name` is
> the body resource. A name resolves only for a character of your own,
> [`s:character()`](../session.md#read), or for a kin, [`session:kin`](../kin.md).

> **`GobInfo` carries no `hitbox`.** A snapshot holds no objects, only plain data, and a footprint
> rebuilt on every `:info()` call would be paid by every sweep that only wanted a name or a position.
> Read [`gob:hitbox()`](../gob.md#the-ground-it-stands-on) for the footprint itself.

## PartyMember

From [`member:info()`](../party.md#a-member), the one snapshot escape hatch. `s:party():list()` hands
you live [`PartyMember` objects](../party.md), not this table. There is **no name** field: the client is
never sent one.

| Field | Type | Notes |
|---|---|---|
| `id` | number | member gob id |
| `x`, `y` | number | live position if in view, else last-known; optional |
| `color` | [colour](../shapes.md#colours) | party colour; optional |
| `leader` | bool | whether this member is the party leader |

The live reads are `member:id()`, `:position()` — a [Position](../position.md), not the two
loose numbers — `:color()`. Whether a member leads is `s:party():leader() == member`, not a flag on them.

## KinEntry

From [`kin:info()`](../kin.md#read), the one snapshot escape hatch. The roster and `KinChanged` hand you
live [`Kin` objects](../kin.md), not this table.

| Field | Type | Notes |
|---|---|---|
| `id` | number | kin id |
| `name` | string | kin name or nick; optional |
| `group` | number | the kin's group, 0..254 |
| `color` | [colour](../shapes.md#colours) | the group's colour; **absent for a group of 8 or higher** — the client draws eight |
| `online` | bool | whether the kin is online |

## Tile

From [`s:world():tile`](../world.md#terrain-and-coordinates). `{ id = number, name = string? }` —
tileset id plus resource name.

## Position

From [`p:info()`](../position.md). `{ gridId = string, x = number, y = number }` — a grid id, a
64-bit value as a [decimal string](../shapes.md#coordinates), and the offset **within** that grid,
which is the durable form and not the same numbers as `p:x()`/`p:y()`. It is `nil` for a place that
is not durable, and it is what `s:world():position(saved)` rebuilds from.

## WorldEntity

From [`e:info()`](../virtual/README.md#the-snapshot), the snapshot escape hatch for a ghost, sprite, object or
panel you have standing in the world. The live reads are `e:position()`, `:alpha()`, `:drawn()` and the
rest, each spelled the way its field here is.

| Field | Type | Notes |
|---|---|---|
| `kind` | string | `"ghost"`, `"sprite"`, `"object"` or `"panel"` |
| `position` | `{gridId, x, y}` | the [Position snapshot](#position) — the place to store |
| `rotate` | number | its own facing, radians |
| `scale` | number | uniform scale, `1` being original size |
| `alpha` | number | opacity `0..1` |
| `visible` | bool | whether you have this one showing |
| `clickable` | bool | opted into the client-side pick |
| `exists` | bool | still in the world |
| `drawn` | bool | in the 3D scene right now |
| `tint` | [colour](../shapes.md#colours) | optional — absent when nothing is laid over it |
| `anchor` | number | the gob id it follows; absent for one that stands still |
| `offset` | `{x, y, z}` | where it sits relative to that gob, world units; present with `anchor` |
| `res` | string | ghosts only — the resource it is a picture of |
| `mesh` | string | objects only |
| `image` | string | sprites only |
| `facing` | string | sprites and panels — `"fixed"`, `"camera"` or `"screen"` |

`panel:screen(x, y)` has no field: it projects a point you pass in, so there is no value of it to snapshot.

## See also

- [the catalogue](README.md) — every snapshot shape, and what a snapshot is
- [Gob](../gob.md) — the live-object counterpart of `GobInfo`, and the usual way to read a gob
- [Position](../position.md) — the live place, and the verbs that compute one
- [shapes](../shapes.md) — the anonymous tables these fields carry: places, sizes, colours, units
