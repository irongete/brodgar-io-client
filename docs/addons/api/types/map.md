# Map Type Snapshots

Table schemas for map grid, pin marker, and icon category snapshots.

---

## `GridInfo` (Map Grid Snapshot)

Returned by `grid:info()`:

```lua
{
  id = "g_01a",               -- string: server grid ID
  failed = false              -- boolean: true if minimap render failed
}
```

---

## `MarkerInfo` (Map Marker Snapshot)

Returned by `marker:info()`:

```lua
{
  id = "camp_01",             -- string: unique identifier
  text = "Base Camp",         -- string | nil: display caption
  color = { 0, 200, 255 },    -- table: {r, g, b, a}
  position = { x = 120, y = 450 } -- table: world coordinate
}
```
