# hafen.map():marker(): Map Markers

Create, inspect, recolor, and delete custom pin markers on the persistent world map.

## Quick Example

```lua
local marker_manager = hafen.map():marker()

-- Read existing markers
for _, active_marker in ipairs(marker_manager:list()) do
  hafen.log():write("Marker: " .. (active_marker:text() or active_marker:id()))
end

-- Add a new marker (requires "map.marker" permission)
-- local session = hafen.session():current()
-- local current_position = session and session:player():gob():position()
-- if current_position then
--   local new_marker = marker_manager:add("camp", current_position)
--   new_marker:text("Camp Site"):color({ 0, 255, 100 })
-- end
```

---

## Read Methods on `hafen.map():marker()`

| Method | Parameters | Returns | Description |
|---|---|---|---|
| `:list(filter?)` | `[string \| function]` | `MapMarker[]` | Array of all persistent markers on the map. |
| `:get(marker_id)` | `string` | `MapMarker \| nil` | Finds a marker by its unique key identifier. |
| `:count()` | None | `number` | Total number of markers. |

---

## Methods on `MapMarker`

| Method | Parameters | Returns | Description |
|---|---|---|---|
| `:id()` | None | `string` | Unique identifier string of the marker. |
| `:text()` | None | `string \| nil` | Display label of the marker. |
| `:color()` | None | `{r, g, b, a}` | Marker pin color. |
| `:position()` | None | `Position` | Geographic map location. |

---

## Protected Actions

Modifying map markers requires the `map.marker` permission in `manifest.json`:

| Method | Parameters | Permission | Description |
|---|---|---|---|
| `:add(marker_id, position)` | `string, Position` | `map.marker` | Creates a new marker at `position` with key `marker_id`. |
| `marker:remove()` | None | `map.marker` | Deletes this marker from the persistent map. |
| `marker:text(new_label)` | `string` | `map.marker` | Updates the text label. |
| `marker:color(color_table)` | `{r, g, b, [a]}` | `map.marker` | Updates the marker color. |
