# hafen.map: markers

Read, add and remove markers in the map database — the same markers the map window shows. Two kinds
exist: **player** markers, your own pins, which carry a name and a colour, and **system** markers, the
server and quest pins, which carry a name and an icon.

| Function | Returns | Description |
|---|---|---|
| `hafen.map.markers.list(filter)` | [`Marker`](#the-marker-object)`[]` | every marker matching the [filter](../conventions.md#the-filter-argument) |
| `hafen.map.markers.nearest(filter)` | [`Marker`](#the-marker-object) \| nil | the closest match to the player, within your current segment |

Both answer an empty array or `nil` before the map database is ready, and neither throws. A string
filter matches the marker's name; a function filter is called with the Marker itself.

## The Marker object

| Method | Returns | Description |
|---|---|---|
| `marker:name()` | string \| nil | the label the map shows |
| `marker:type()` | string | `"player"` or `"system"` |
| `marker:anchor()` | `{gridId, x, y}` \| nil | the position you may **save or send**; `nil` while the grid it needs is still loading |
| `marker:tc()` | `{x, y}` | its segment tile coord — where it really lives in the database |
| `marker:segment()` | [`Segment`](grids.md#the-segment-object) | the segment it is recorded in |
| `marker:pos()` | `{x, y}` \| nil | its world position this session; `nil` outside your current segment |
| `marker:dist()` | number \| nil | how far the player is from it |
| `marker:color()` | [Color](../types.md#color) \| nil | player markers only |
| `marker:onmap()` | bool \| nil | player markers only: also drawn on the main map |
| `marker:icon()` | string \| nil | system markers only: the icon resource name |
| `marker:exists()` | bool | is it still in the database? |
| `marker:info()` | [`Marker` snapshot](../types.md#marker) | the snapshot escape hatch |

**`marker:anchor()` is how a marker leaves this client.** It converts the marker's own position — which
is client-local and re-based by a merge, see [saving a position](grids.md#saving-a-position) — into the
same `{gridId, x, y}` [a Position's `:info()`](../world.md#the-position-type) hands
out. For a marker in your current segment it answers straight away; for one in another explored area it
has to read that grid off the disk, so it answers `nil` and then answers.

```lua
local mark = hafen.map.markers.nearest("Camp")
local a = mark and mark:anchor()
if a then hafen.store.camp = a end                    -- survives the relog; means the same to a friend
```

## Write (ungated)

| Function | Returns | Description |
|---|---|---|
| `hafen.map.markers.add(name, x, y, opts)` | [`Marker`](#the-marker-object) \| nil | create a **player** marker at world `x, y`; `nil` when the map is not ready |
| `hafen.map.markers.remove(marker)` | bool | remove a marker `list`, `nearest` or `add` gave you; whether one was removed |

`opts` for `add`, all optional:

| Key | Type | Default | Meaning |
|---|---|---|---|
| `color` | `{r, g, b, a}`, `0..255` | gold | pin colour |
| `onmap` | bool | `false` | also show it on the main map |

> **These two verbs write, and they need no permission.** Unlike the [`hafen.act`](../act.md) tier, adding
> and removing markers is not gated: it edits the user's own on-disk map database, which is
> client-local and reversible by hand. Remove only what your addon added.

The [`MarkersChanged`](../event.md#roster-quests-markers) event, payload `{ count }`, fires on any add or
remove, including ones the player makes.

## See also

- [segments and grids](grids.md) — `seg:markers()`, and why the anchor is what you store
- [`Marker`](../types.md#marker) — the snapshot `marker:info()` hands back
- [events](../event.md#roster-quests-markers) — `MarkersChanged`
- [`hafen.world`](../world.md#the-position-type) — the other source of an anchor
