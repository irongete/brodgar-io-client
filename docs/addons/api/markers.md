# hafen.markers: map markers

Read, add and remove markers in the client's on-disk map database — the same markers the map window
shows. Two kinds exist: **player** markers, your own pins, which carry a name and a colour, and
**system** markers, the server and quest pins, which carry a name and an icon.

```lua
local p = hafen.player():gob():pos()
local ref = hafen.markers.add("Camp", p.x, p.y, { color = {0, 200, 0}, onmap = true })
hafen.markers.remove(ref)
```

## Read

| Function | Returns | Description |
|---|---|---|
| `hafen.markers.list(filter)` | [`Marker`](types.md#marker)`[]` | every marker matching the [filter](conventions.md#the-filter-argument) |
| `hafen.markers.nearest(filter)` | [`Marker`](types.md#marker) \| nil | the closest match to the player, within your current segment |

Both answer an empty array or `nil` before the map database is ready, and neither throws.

## Write (ungated)

| Function | Returns | Description |
|---|---|---|
| `hafen.markers.add(name, x, y, opts)` | ref (number) \| nil | create a **player** marker at world `x, y`; `nil` when the map is not ready |
| `hafen.markers.remove(ref)` | bool | remove a marker by the ref `list` or `add` gave you; whether one was removed |

`opts` for `add`, all optional:

| Key | Type | Default | Meaning |
|---|---|---|---|
| `color` | `{r, g, b, a}`, `0..255` | gold | pin colour |
| `onmap` | bool | `false` | also show it on the main map |

> **These two verbs write, and they need no permission.** Unlike the [`hafen.act`](act.md) tier, adding
> and removing markers is not gated: it edits the user's own on-disk map database, which is
> client-local and reversible by hand. Remove only what your addon added.

The [`MarkersChanged`](events.md#roster-quests-markers) event, payload `{ count }`, fires on any add or
remove, including ones the player makes.

> **A marker's position is persistent, not session-local.** Its stable anchor is `seg`, the segment id,
> a string, plus `tc`, the segment tile coord — those survive a relog. The `x`, `y` and `dist` fields
> are session-local conveniences, present only while the marker is in your current segment.

## See also

- [`Marker`](types.md#marker) — the snapshot shape both readers return
- [coordinates](conventions.md#coordinates) — why `seg` and `tc` are the anchor
- [`hafen.map`](map.md) — the grid anchor, the equivalent for a position you save yourself
- [events](events.md#roster-quests-markers) — `MarkersChanged`
