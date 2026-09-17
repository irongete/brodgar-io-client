# Data Types: The Session and the World

The snapshot shapes one login hands back. The session. An object in the world. The people beside you, and the ones a voice link relates you to. The ground. A place to store. Your own things standing out there. Each is what `:info()` copies out of a live object. The live reads are verbs on that object, and what *optional* means is on [the catalogue](README.md).

```lua
local session = hafen.session():current()
local tree = session:world():gob():nearest("terobjs/tree")
local snapshot = tree and tree:info()
if snapshot and snapshot.name then hafen.log():write(snapshot.name .. " hp=" .. tostring(snapshot.hp)) end
```

---

## Session

From [`session:info()`](../session.md#read). [`hafen.session`](../session.md) hands out live `Session` objects, not this table.

| Field | Type | Notes |
|---|---|---|
| `user` | `string` | The account name, the session's identity. Present for a session that has ended. |
| `character` | `string` | The character this login is playing. Optional (absent until its HUD is up). |
| `exists` | `boolean` | Whether the client still holds this session. |
| `current` | `boolean` | Whether it is the one on screen. The live read is `hafen.session():current() == session`. |

## GobInfo

From [`gob:info()`](../gob.md), for logging and serialising. To read a gob call its [methods](../gob.md#read). [`session:world`](../world.md) and the `GobAdded`/`GobRemoved` events hand out Gob objects, not this table.

| Field | Type | Notes |
|---|---|---|
| `id` | `number` | Stable gob id. |
| `x`, `y` | `number` | World position, the point it is drawn at (`gob:position()`). Optional. |
| `angle` | `number` | Facing, radians. |
| `name` | `string` | The resource identity (`"gfx/kritter/rabbit/rabbit"`), not a display name. Optional. |
| `isplayer` | `boolean` | Whether the gob is a player body (`gob:player()`). Present only when `name` is. |
| `hp` | `number` | `0..1` remaining object integrity, `1` undamaged. Optional. |
| `moving` | `boolean` | Whether it is moving. |
| `speed` | `number` | Movement speed. Present only while moving. |
| `speech` | `string` | Current floating speech text. Optional. |
| `icon` | `string` | Minimap icon category name. Optional. |
| `overlays` | `string[]` | Active overlay resource names. Optional. |
| `sdt` | `number[]` | 1-based `0..255` state bytes. Optional, present only for a resource-drawn gob, empty array included. |
| `pose` | `string[]` | The animation poses a composed body is in, as resource names. Optional, never present beside `sdt`. |
| `visible` | `boolean` | Whether the client draws it ([`gob:visible()`](../look.md#drawn-or-not-unprotected)). `true` for an object nobody hid. |
| `scale` | `number` | How big it is drawn ([`gob:scale()`](../look.md#size-unprotected)). `1` for an object nobody sized. |
| `tint` | [colour](../shapes.md#colours) | The colour laid over it ([`gob:tint()`](../look.md#tint-unprotected)). Optional. |
| `materials` | `string[]` | The resource name in force per [material slot](../materials.md), in slot order. Absent for an object with none. |

| Rule | Detail |
|---|---|
| Best-effort | Built in one pass over an object the world is still resolving, keeping what it filled before a piece was missing. Every field but `id` can be absent, so read the field you want rather than counting keys. |
| No display names | A limit of the client and the protocol: `name` is the body resource. A name resolves for your own character ([`session:character()`](../session.md#read)) or a kin ([`session:kin`](../kin.md)). |
| No `hitbox` | A snapshot holds plain data, and a footprint rebuilt per call would be paid by every sweep. Read [`gob:hitbox()`](../gob.md#the-ground-it-stands-on). |

## PartyMember

From [`member:info()`](../party.md#a-member). `session:party():list()` hands live [`PartyMember` objects](../party.md), not this table. No name field: the client is never sent one.

| Field | Type | Notes |
|---|---|---|
| `id` | `number` | Member gob id. |
| `x`, `y` | `number` | Live position if in view, else last-known. Optional. The live read is `member:position()`, a [Position](../position.md). |
| `color` | [colour](../shapes.md#colours) | Party colour, black until the server names one. |
| `leader` | `boolean` | Whether this member leads. The live test is `session:party():leader() == member`. |

## Peer

From [`peer:info()`](../voice/peers.md#the-peer-object). `voice:peer()` and the Peer keys hand live [`Peer` objects](../voice/peers.md), not this table. No name and no position: the player is their gob, `peer:gob()`.

| Field | Type | Notes |
|---|---|---|
| `id` | `number` | The gob id. |
| `exists` | `boolean` | Whether the server relates you right now, either way round. |
| `audible` | `boolean` | Whether you hear them. |
| `hears` | `boolean` | Whether they hear you. |
| `speaking` | `boolean` | Whether their voice is arriving right now. |
| `muted` | `boolean` | Whether you discard their voice on this link. `false` unless you set it. |
| `volume` | `number` | The gain you play them at, `0..4`. `1` unless you set it. |

## KinEntry

From [`kin:info()`](../kin.md#read). The roster and `KinChanged` hand live [`Kin` objects](../kin.md), not this table.

| Field | Type | Notes |
|---|---|---|
| `id` | `number` | Kin id. |
| `name` | `string` | Kin name or nick. Optional. |
| `group` | `number` | The kin's group, `0..254`. |
| `color` | [colour](../shapes.md#colours) | The group's colour. Absent for a group of 8 or higher, since the client draws eight. |
| `online` | `boolean` | Whether the kin is online. |

## Tile

From [`session:world():tile`](../world.md#terrain-and-coordinates): `{ id = number, name = string? }`, tileset id plus resource name.

## Position

From [`position:info()`](../position.md): `{ gridId = string, x = number, y = number }`. `gridId` is a 64-bit value as a [decimal string](../shapes.md#coordinates), `x` and `y` the offset within that grid. This is the durable form, not the numbers `position:x()`/`position:y()` answer. `nil` for a place that is not durable. What `session:world():position(saved)` rebuilds from.

## WorldEntity

From [`entity:info()`](../virtual/README.md#the-snapshot), for a ghost, sprite, object, panel or patch of yours. The live reads are `entity:position()`, `:alpha()`, `:drawn()` and the rest, spelled as the field is.

| Field | Type | Notes |
|---|---|---|
| `kind` | `string` | `"ghost"`, `"sprite"`, `"object"`, `"panel"` or `"patch"`. |
| `position` | `{gridId, x, y}` | The [Position snapshot](#position), the place to store. Optional (absent while the place does not resolve). |
| `rotate` | `number` | Its own facing, radians. |
| `scale` | `number` | Uniform scale, `1` original. |
| `alpha` | `number` | Opacity `0..1`. |
| `visible` | `boolean` | Whether you have it showing. |
| `clickable` | `boolean` | Opted into the client-side pick. |
| `exists` | `boolean` | Still in the world. |
| `drawn` | `boolean` | In the 3D scene right now. |
| `tint` | [colour](../shapes.md#colours) | Optional. Absent when nothing is laid over it. |
| `anchor` | `number` | The gob id it follows. Absent for one that stands still. |
| `offset` | `{x, y, z}` | Where it sits relative to that gob, world units. Present with `anchor`. `{x, y}` for a patch, which has no height. |
| `res` | `string` | Ghosts only: the resource it is a picture of. |
| `mesh` | `string` | Objects only. |
| `image` | `string` | Sprites only. |
| `facing` | `string` | Sprites and panels: `"fixed"`, `"camera"` or `"screen"`. A patch has none. |
| `pieces` | `{gridId, x, y}[][]` | [Patches](../virtual/patches.md#the-snapshot) only: one ring per [piece](../virtual/pieces.md) in laid order. Absent while the character on screen cannot locate the ground, empty for a patch holding no pieces. |
| `border` | `{color, width}` | Patches only: the line round the shape. Present once you have drawn one. |
| `occluded` | `boolean` | Patches only: whether the world may hide it. Always present. |

`panel:screen(x, y)` has no field: it projects a point you pass in.

---

## See Also

- [The catalogue](README.md) — every snapshot shape, and what a snapshot is.
- [Gob](../gob.md) — the live counterpart of `GobInfo`, and the usual way to read a gob.
- [Position](../position.md) — the live place, and the verbs that compute one.
- [Peers](../voice/peers.md) — the live counterpart of `Peer`, and the keys that follow them.
- [Shapes](../shapes.md) — the anonymous tables these fields carry.
