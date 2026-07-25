# hafen.markers — map markers

Read, add, and remove markers in the client's on-disk map database (the same markers the map window
shows). Two kinds exist: **player** markers (your own pins — a name + colour) and **system** markers
(server/quest pins — a name + icon).

| Function | Returns | Description |
|---|---|---|
| `hafen.markers.list([filter])` | [`Marker`](types.md#marker)`[]` | all markers matching the [filter](conventions.md#the-filter-argument) |
| `hafen.markers.nearest([filter])` | [`Marker`](types.md#marker) \| nil | the closest match to the player (in your current segment) |
| `hafen.markers.add(name, x, y [, opts])` | ref (number) \| nil | create a **player** marker at world `x,y`; returns its ref, or nil if the map isn't ready |
| `hafen.markers.remove(ref)` | bool | remove a marker by the ref from `list`/`add`; returns whether one was removed |

`opts` for `add` (all optional):

| Key | Type | Default | Meaning |
|---|---|---|---|
| `color` | `{r, g, b [, a]}` (0..255) | gold | pin colour |
| `onmap` | bool | `false` | also show it on the main map |

The [`MarkersChanged`](events.md#roster-quests-markers) event (`{ count }`) fires on any add/remove.

```lua
local ref = hafen.markers.add("Camp", p.x, p.y, { color = {0, 200, 0}, onmap = true })
-- ...
hafen.markers.remove(ref)
```

> **Marker positions are persistent, not session-local.** A marker's stable anchor is its `seg`
> (segment id, a string) + `tc` (segment tile coord) — those survive a relog. The `x`, `y`, and `dist`
> fields are session-local conveniences, present only when the marker is in your current segment. See
> [conventions](conventions.md#coordinates).
