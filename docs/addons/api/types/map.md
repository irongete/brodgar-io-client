# Data types: the map

The two snapshot shapes off the recorded map: a pin on it, and a minimap icon category. Each is what
`:info()` copies out of a live object, so it never updates — the live reads are verbs on that object. The
model, and what *optional* means on the tables below, is on [the catalogue](README.md).

## Marker

From [`marker:info()`](../map/markers.md#the-marker-object), the snapshot escape hatch for a map
marker. The live reads are `marker:name()`, `:type()`, `:segmentTile()` and the rest — and
`marker:position()` is the place to store, not the `seg` + `tc` below.

| Field | Type | Notes |
|---|---|---|
| `name` | string | marker label; optional |
| `type` | string | `"player"`, a user pin, or `"system"`, a server or quest pin |
| `seg` | string | segment id, a [64-bit decimal string](../shapes.md#coordinates) — client-local, [never stored](../map/grids.md#storing-a-place) |
| `tc` | `{x, y}` | segment tile coord — client-local, never stored |
| `color` | [colour](../shapes.md#colours) | player markers only; optional |
| `onmap` | bool | player markers only, read and written live as `marker:onMap(b)` |
| `icon` | string | system markers only; optional |
| `x`, `y` | number | session-local world position; present only while the marker is in your current segment |
| `dist` | number | distance from the player; present with `x`, `y` |

## IconCategory

From [`cat:info()`](../map/icons.md#the-iconcat-object), the snapshot escape hatch for a minimap icon
category; `nil` while the registry carries no setting for that resource, which is the same absence
[`cat:exists()`](../map/icons.md#the-iconcat-object) reads as `false` — the registry grows as the
character meets new icon types, so a handle can start answering later.

`{ name = string, res = string, show = bool, notify = bool }` — `res` is the identity, `name` the icon
tooltip, and `show` and `notify` the minimap-draw and spawn-notify flags. The live reads are
`cat:res()`, `:name()`, `:show()` and `:notify()`.

## See also

- [the catalogue](README.md) — every snapshot shape, and what a snapshot is
- [markers](../map/markers.md) — the live `Marker` objects, and the Position one travels as
- [icons](../map/icons.md) — the live icon categories, and the two flags on each
- [shapes](../shapes.md) — the anonymous tables these fields carry: places, sizes, colours, units
