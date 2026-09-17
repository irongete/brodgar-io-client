# hafen.map: Markers

Read, add and remove the markers the map window shows. Player markers are your own pins, a name and a colour. System markers are the server's and quest pins, a name and an icon.

```lua
local here = hafen.session():current():player():gob():position()
local pin = hafen.map():marker():add("Camp", here)
pin:color{0, 200, 0}:onMap(true)
hafen.log():write(pin:color().g .. " " .. tostring(pin:onMap()))     -- 200 true
```

---

## Read

| Method | Returns | Permission | Description |
|---|---|---|---|
| `hafen.map():marker():list(filter)` | [`Marker`](#the-marker-object)`[]` | Unprotected | Every marker matching the [filter](../conventions.md#the-filter-argument). |
| `hafen.map():marker():find(filter)` | [`Marker`](#the-marker-object) `\| nil` | Unprotected | The first match. |
| `hafen.map():marker():count(filter)` | `number` | Unprotected | How many match. |
| `hafen.map():marker():nearest(filter)` | [`Marker`](#the-marker-object) `\| nil` | Unprotected | The closest match to the character on screen, within that character's segment. |

| Rule | Detail |
|---|---|
| Before the database is ready | Empty, `0` or `nil`. None throws. |
| `filter` | A string matches the marker's name. A function is called with the Marker. |
| No `:get` | A marker's only id is a per-session ref this client mints. `hafen.map():marker():get(1)` raises: `hafen.map():marker() has no verb 'get' — a marker's only id is a per-session ref this client mints, which is not a key anything could hold on to: hafen.map():marker():find(filter) is the search and hafen.map():marker():nearest(filter) the closest one`. |

## The Marker object

| Method | Returns | Permission | Description |
|---|---|---|---|
| `marker:name()` | `string \| nil` | Unprotected | The label the map shows. Read only: `marker:name(text)` raises. |
| `marker:type()` | `string \| nil` | Unprotected | `"player"` or `"system"`. `nil` once the pin is gone, as `:name()` is. |
| `marker:position()` | [Position](../position.md) `\| nil` | Unprotected | Where it is: the form to store or send. |
| `marker:segmentTile()` | `{x, y}` | Unprotected | Its segment tile coordinate, where it lives in the database. |
| `marker:segment()` | [`Segment`](grids.md#the-segment-object) | Unprotected | The segment it is recorded in. |
| `marker:distance()` | `number \| nil` | Unprotected | How far the character on screen is from it. |
| `marker:icon()` | `string \| nil` | Unprotected | System markers only: the icon resource name. |
| `marker:exists()` | `boolean` | Unprotected | Whether it is still in the database. |
| `marker:info()` | [`Marker` snapshot](../types/map.md#marker) | Unprotected | The snapshot. |

| Rule | Detail |
|---|---|
| `marker:position()` is how a marker leaves this client | Its own coordinates are a segment id and a tile inside it, client-local and re-based by a merge ([storing a place](grids.md#storing-a-place)). A Position anchors on the server's grid id and names the centre of the marker's tile, so storing and reading back lands on that tile. In your current segment it answers `:x()`/`:y()`. In another explored area it keeps its anchor and `:x()` is `nil`. |

```lua
local camp = hafen.map():marker():find("Camp")
if camp then hafen.store():var("cfg").camp = camp:position() end   -- survives the relog
```

## Write (protected)

| Method | Returns | Permission | Description |
|---|---|---|---|
| `hafen.map():marker():add(name, position)` | [`Marker`](#the-marker-object) `\| nil` | `map.marker` | Create a player marker at a Position. `nil` when the map is not ready. Bare: the client's own gold, off the main map. |
| `hafen.map():marker():remove(marker)` | the collection | `map.marker` | Remove a marker. One already gone is inert. |
| `marker:color()` / `marker:color(color)` | [colour](../shapes.md#colours) `\| nil` / the marker | `map.marker` | Player markers only: the pin colour. A colour read is one of the two spellings `:color` takes, so `first:color(second:color())` copies one. |
| `marker:onMap()` / `marker:onMap(flag)` | `boolean \| nil` / the marker | `map.marker` | Player markers only: also drawn on the main map. |

| Rule | Detail |
|---|---|
| Why keyed | These reach no server. They edit the player's on-disk map database, and `:remove(marker)` permanently deletes a pin that took real play to place. The consent dialog says *"add and delete pins on your map, and recolour them"*. Reading needs nothing. Remove only what your addon added. |
| The player's data | Disabling, reloading or uninstalling your addon removes no pin: it is the player's from the moment it is added. Remove yours before you stop. |
| System markers | Writing either property is refused: the server's own pins. |
| One map | A pin goes into [the client's one map](README.md), not the character that dropped it. Your other characters in that world see it. It stays when the session ends. |
| `MarkerChanged` | [The event](../event/bus/character.md#roster-quests-markers), payload the marker collection, fires on any add, remove or edit, the player's included. One change is one event however many characters share the map: the first login to claim the database on its tick carries the announcement. |
| Before any login | A change made before a login has claimed the database (between the client opening the map file and a character's first tick) announces nothing. Read `hafen.map():marker():list()` once when you start. |

---

## See Also

- [Segments and grids](grids.md) — `segment:markers()`, and why a Position is what you store.
- [`Marker`](../types/map.md#marker) — the snapshot `marker:info()` hands back.
- [Events](../event/bus/character.md#roster-quests-markers) — `MarkerChanged`.
- [Position](../position.md) — the place type, and rebuilding one from a stored form.
