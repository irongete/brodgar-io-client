# hafen.map: Map Subsystem

Interact with the persistent world map, minimap tiles, grid coordinates, custom map markers, path drawings, and map icon overlays.

## Quick Example

```lua
local map_subsystem = hafen.map()

-- Add a custom pin marker to the world map
local home_marker = map_subsystem:marker():add("base_camp", { x = 1200, y = -450 })
home_marker:color({ 0, 200, 255 })
  :text("Main Base")
```

---

## Subsystem Navigation

| Subsystem | Reference Page | Description |
|---|---|---|
| **Markers** | **[markers.md](markers.md)** | Map pins, marker creation, colors, deletion (`marker:add`, `:remove`). |
| **Grids** | **[grids.md](grids.md)** | World map grid coordinates, grid segments, and cached tile data. |
| **Drawings** | **[drawings.md](drawings.md)** | Lines, paths, and freeform vector drawings on the map surface. |
| **Icons** | **[icons.md](icons.md)** | Custom icon blits rendered onto the minimap and main map. |
| **Overlays** | **[overlays.md](overlays.md)** | Polygon overlays and territory borders rendered on the map. |
