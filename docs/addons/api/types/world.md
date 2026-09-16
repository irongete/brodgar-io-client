# Data types: the session and the world

The snapshot shapes that describe a session and the world around it: objects in the world, the party,
peers on a voice link, kin, the ground tiles, a position, virtual entities, and session states.
Each is what `:info()` copies out of a live object, so it never updates — the live reads are verbs on that
object. The model, and what *optional* means on the tables below, is on [the catalogue](README.md).

## GobInfo

From [`gob:info()`](../gob.md), the snapshot escape hatch for an object in the world.

| Field | Type | Notes |
|---|---|---|
| `id` | number | gob id — the server's identity |
| `name` | string | the body resource name; optional (absent while the resource is still loading) |
| `type` | string | `"player"` or `"object"` |
| `x`, `y` | number | world position; optional (absent while off-grid) |
| `rot` | number | facing in radians; optional |
| `dist` | number | distance to the player character; optional |
| `icon` | string | minimap icon category name; optional |
| `overlays` | string[] | active overlay resource names; optional |
| `sdt` | number[] | state bytes the resource interprets; optional |
| `pose` | string[] | animation poses of a composed body; optional |
| `visible` | bool | whether the client draws it; `true` by default |
| `scale` | number | scale factor; `1` by default |
| `tint` | [colour](../shapes.md#colours) | color tint; optional |
| `materials` | string[] | the resource name in force per [material slot](../materials.md), in slot order; absent for an object with none |

## PartyMember

From [`member:info()`](../party.md), the one snapshot escape hatch.

| Field | Type | Notes |
|---|---|---|
| `id` | number | member gob id |
| `x`, `y` | number | live position if in view, else last-known; optional |
| `color` | [colour](../shapes.md#colours) | party colour |
| `leader` | bool | whether this member is the party leader |

## Peer

From [`peer:info()`](../voice/peers.md), the one snapshot escape hatch.

| Field | Type | Notes |
|---|---|---|
| `id` | number | the gob id |
| `exists` | bool | whether currently linked |
| `audible` | bool | whether you hear them |
| `hears` | bool | whether they hear you |
| `speaking` | bool | whether voice is arriving right now |
| `muted` | bool | whether you muted them locally |
| `volume` | number | playback volume `0..4` |

## KinEntry

From [`kin:info()`](../kin.md), the one snapshot escape hatch.

| Field | Type | Notes |
|---|---|---|
| `id` | number | kin id |
| `name` | string | kin name or nickname; optional |
| `group` | number | kin group (0..254) |
| `color` | [colour](../shapes.md#colours) | group color; absent for group >= 8 |
| `online` | bool | whether the kin is currently online |

## Tile

From [`session:world():tile`](../world.md). `{ id = number, name = string? }` — tileset id plus resource name.

## Position

From [`position:info()`](../position.md). `{ gridId = string, x = number, y = number }` — durable position with 64-bit decimal grid ID and in-grid coordinates.

## WorldEntity

From [`virtual_entity:info()`](../virtual/README.md), the snapshot escape hatch for virtual world elements.

| Field | Type | Notes |
|---|---|---|
| `kind` | string | `"ghost"`, `"sprite"`, `"object"`, `"panel"`, or `"patch"` |
| `position` | `{gridId, x, y}` | the [Position snapshot](#position); optional |
| `rotate` | number | facing in radians |
| `scale` | number | uniform scale (`1` = normal size) |
| `alpha` | number | opacity (`0.0..1.0`) |
| `visible` | bool | visibility flag |
| `clickable` | bool | interactive pick flag |
| `exists` | bool | whether still attached in the world |
| `drawn` | bool | currently rendered in the 3D scene |
| `tint` | [colour](../shapes.md#colours) | optional overlay color |
| `anchor` | number | gob id it follows; absent for static entities |
| `offset` | `{x, y, z}` | offset relative to anchor gob |
| `res` | string | ghosts only: target resource path |
| `mesh` | string | objects only: mesh model path |
| `image` | string | sprites only: image path |
| `facing` | string | sprites and panels: `"fixed"`, `"camera"`, or `"screen"` |
| `pieces` | `{gridId, x, y}[][]` | patches only: coordinate rings |
| `border` | `{color, width}` | patches only: boundary line style |
| `occluded` | bool | patches only: terrain occlusion flag |

## Session

From [`session:info()`](../session.md). `{ user = string, character = string?, iscurrent = bool }` — account username, character name, and whether this session is currently on screen.

## See also

- [the catalogue](README.md) — every snapshot shape, and what a snapshot is
- [Gob](../gob.md) — live game object methods
- [Position](../position.md) — coordinate math and durable positions
- [peers](../voice/peers.md) — live voice peer objects
- [shapes](../shapes.md) — places, sizes, colors, and units
