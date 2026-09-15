# hafen.map: Map Markers

Create, inspect, recolor, and delete custom pin markers on the persistent world map.

## Quick Example

```lua
local marker_manager = hafen.map():marker()

-- Read existing markers
for _, active_marker in ipairs(marker_manager:list()) do
  hafen.log():write("Marker: " .. (active_marker:name() or "?"))
end

-- Add a new marker (requires "map.marker" permission)
-- local session = hafen.session():current()
-- local current_position = session and session:player():gob():position()
-- if current_position then
--   local new_marker = marker_manager:add("Camp Site", current_position)
--   new_marker:color({ r = 0, g = 255, b = 100 })
-- end
```

---

## Methods on `hafen.map():marker()`

| Method | Parameters | Returns | Permission | Description |
|---|---|---|---|---|
| `:list(filter?)` | `[string \| function]` | `Marker[]` | Unprotected | All markers matching the filter. |
| `:count(filter?)` | `[string \| function]` | `number` | Unprotected | Count of markers matching the filter. |
| `:find(filter)` | `string \| function` | `Marker \| nil` | Unprotected | First marker satisfying the filter. |
| `:nearest(filter?)` | `[string \| function]` | `Marker \| nil` | Unprotected | Marker closest to the current player position. |
| `:add(name, position)` | `string, Position` | `Marker` | `map.marker` | Creates a new marker at the given position. |
| `:remove(marker)` | `Marker` | `nil` | `map.marker` | Deletes the marker from the map database. |

---

## Methods on `Marker`

| Method | Parameters | Returns | Permission | Description |
|---|---|---|---|---|
| `:name()` | None | `string \| nil` | Unprotected | Marker display name. |
| `:type()` | None | `string` | Unprotected | Type identifier (`"player"` or `"system"`). |
| `:segmentTile()` | None | `{x, y} \| nil` | Unprotected | Local segment tile coordinate. |
| `:position()` | None | `Position \| nil` | Unprotected | Durable geographic map position. |
| `:distance()` | None | `number \| nil` | Unprotected | Distance to player in world units (if in current segment). |
| `:color()` | None | `{r, g, b, a} \| nil` | Unprotected | Pin color (player markers only). |
| `:color(color_table)` | `table` | `self` | `map.marker` | Sets pin color (`{r, g, b, [a]}`). Chains. |
| `:onMap()` | None | `boolean \| nil` | Unprotected | `true` if visible on the main map. |
| `:onMap(is_on_map)` | `boolean` | `self` | `map.marker` | Toggles main map visibility. Chains. |
| `:icon()` | None | `string \| nil` | Unprotected | System icon resource name (system markers only). |
| `:segment()` | None | `Segment \| nil` | Unprotected | Segment containing this marker. |
| `:exists()` | None | `boolean` | Unprotected | `true` if marker still exists in database. |
| `:info()` | None | `table \| nil` | Unprotected | Snapshot `{ name, type, seg, tc, color, onmap, icon, x, y, dist }`. |
