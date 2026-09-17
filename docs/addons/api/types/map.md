# Data Types: The Map

The two snapshot shapes off the recorded map: a pin on it, and a minimap icon category. Each is what `:info()` copies out of a live object. What *optional* means is on [the catalogue](README.md).

```lua
for _, marker in ipairs(hafen.map():marker():list()) do
  local snapshot = marker:info()
  hafen.log():write((snapshot.name or "?") .. " [" .. snapshot.type .. "]")
end
```

---

## Marker

From [`marker:info()`](../map/markers.md#the-marker-object). The live reads are `marker:name()`, `:type()`, `:segmentTile()` and the rest. `marker:position()` is the place to store, not the `seg` + `tc` below.

| Field | Type | Notes |
|---|---|---|
| `name` | `string` | Marker label. Optional. |
| `type` | `string` | `"player"`, a user pin, or `"system"`, a server or quest pin. |
| `seg` | `string` | Segment id, a [64-bit decimal string](../shapes.md#coordinates). Client-local, [never stored](../map/grids.md#storing-a-place). |
| `tc` | `{x, y}` | Segment tile coordinate. Client-local, never stored. |
| `color` | [colour](../shapes.md#colours) | Player markers only. Optional. |
| `onmap` | `boolean` | Player markers only. The live pair is `marker:onMap(flag)`. |
| `icon` | `string` | System markers only. Optional. |
| `x`, `y` | `number` | Session-local world position. Present only while the marker is in your current segment. |
| `dist` | `number` | Distance from the player. Present with `x`, `y`. |

## IconCategory

From [`category:info()`](../map/icons.md#the-iconcat-object). `nil` while the registry carries no setting for that resource, the absence [`category:exists()`](../map/icons.md#the-iconcat-object) reads as `false`. The registry grows as the character meets new icon types, so a handle can start answering later. `{ name = string, res = string, show = bool, notify = bool }`: `res` the identity, `name` the icon tooltip, `show` and `notify` the minimap-draw and spawn-notify flags.

---

## See Also

- [The catalogue](README.md) — every snapshot shape, and what a snapshot is.
- [Markers](../map/markers.md) — the live `Marker` objects, and the Position one travels as.
- [Icons](../map/icons.md) — the live icon categories, and the flags on each.
- [Shapes](../shapes.md) — the anonymous tables these fields carry.
