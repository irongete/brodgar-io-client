# hafen.map: markers

Read, add and remove markers in the map database — the same markers the map window shows. Two kinds
exist: **player** markers, your own pins, which carry a name and a colour, and **system** markers, the
server and quest pins, which carry a name and an icon.

| Call | Returns | Description |
|---|---|---|
| `hafen.map():marker():list(filter)` | [`Marker`](#the-marker-object)`[]` | every marker matching the [filter](../conventions.md#the-filter-argument) |
| `hafen.map():marker():find(filter)` | [`Marker`](#the-marker-object) \| nil | the first match |
| `hafen.map():marker():count(filter)` | number | how many match |
| `hafen.map():marker():nearest(filter)` | [`Marker`](#the-marker-object) \| nil | the closest match to the character **on screen**, within that character's segment |

All four answer empty, `0` or `nil` before the map database is ready, and none throws. A string filter
matches the marker's name; a function filter is called with the Marker itself.

## The Marker object

| Method | Returns | Description |
|---|---|---|
| `marker:name()` | string \| nil | the label the map shows |
| `marker:type()` | string | `"player"` or `"system"` |
| `marker:position()` | [Position](../position.md) \| nil | where it is — the form you may **store or send** |
| `marker:segmentTile()` | `{x, y}` | its segment tile coord — where it really lives in the database |
| `marker:segment()` | [`Segment`](grids.md#the-segment-object) | the segment it is recorded in |
| `marker:distance()` | number \| nil | how far the character **on screen** is from it |
| `marker:icon()` | string \| nil | system markers only: the icon resource name |
| `marker:exists()` | bool | is it still in the database? |
| `marker:info()` | [`Marker` snapshot](../types.md#marker) | the snapshot escape hatch |

**`marker:position()` is how a marker leaves this client.** A marker's own coordinates are a segment id
and a tile inside it — client-local, and re-based by a merge, see
[storing a place](grids.md#storing-a-place) — while a Position anchors on the server's grid id. It names
the **centre of the tile** the marker sits on, so storing it and reading it back lands on that tile rather
than beside it. For a marker in your current segment it also answers `:x()`/`:y()`; for one in another
explored area it keeps its anchor and `:x()` is `nil`, because this session has no coordinate for that
place at all.

```lua
local mark = hafen.map():marker():find("Camp")
if mark then hafen.store():get("cfg").camp = mark:position() end   -- survives the relog
```

## Write (unprotected)

| Call | Returns | Description |
|---|---|---|
| `hafen.map():marker():add(name, p)` | [`Marker`](#the-marker-object) \| nil | create a **player** marker at a Position; `nil` when the map is not ready |
| `hafen.map():marker():remove(m)` | the collection | remove a marker; removing one already gone is inert |
| `marker:color()` / `:color(r, g, b [, a])` | [Color](../types.md#color) \| nil / self | player markers only: the pin colour |
| `marker:onMap()` / `:onMap(on)` | bool \| nil / self | player markers only: also drawn on the main map |

A pin is created **bare**, with the client's own gold and off the main map, and configured by chaining —
which is also how you read it back, since arity is the verb:

```lua
local here = hafen.session():current():player():gob():position()
local pin = hafen.map():marker():add("Camp", here)
pin:color(0, 200, 0):onMap(true)
print(pin:color().g, pin:onMap())                    -- 200  true
```

`:color` also takes a colour value straight back out of a read, so `a:color(b:color())` copies one.
Writing either property on a system marker is refused: those are the server's own pins.

> **These verbs write, and they need no permission.** Unlike a verb that reaches the server, adding,
> removing and recolouring markers is not protected: it edits the user's own on-disk map database, which
> is client-local and reversible by hand. Remove only what your addon added.

A pin goes into [the client's one map](README.md#one-map-for-the-client), not into the character that
dropped it: your other characters in that world see it on their own maps, and it stays there when the
session that added it ends.

The [`MarkersChanged`](../event/bus.md#roster-quests-markers) event, payload the marker count, fires on any
add, remove or edit, including ones the player makes.

## See also

- [segments and grids](grids.md) — `seg:markers()`, and why a Position is what you store
- [`Marker`](../types.md#marker) — the snapshot `marker:info()` hands back
- [events](../event/bus.md#roster-quests-markers) — `MarkersChanged`
- [Position](../position.md) — the place type, and rebuilding one from a stored form
