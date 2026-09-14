# World Type Snapshots

Table schemas for entity, position, and terrain snapshots.

---

## `GobInfo` (Entity Snapshot)

Returned by `gob:info()`:

```lua
{
  id = 104281,           -- number: server entity ID
  name = "terobjs/tree", -- string | nil: resource path
  position = {           -- table | nil: world position
    gridId = "g_01",
    x = 142.5,
    y = -80.2,
    z = 10.0
  },
  health = 1.0,          -- number | nil: 0.0..1.0
  moving = false,        -- boolean
  exists = true          -- boolean
}
```

---

## `PositionInfo` (Coordinate Snapshot)

Returned by `position:info()`:

```lua
{
  x = 142.5,             -- number: X coordinate
  y = -80.2,             -- number: Y coordinate
  z = 10.0,              -- number: Z elevation
  gridId = "g_01"        -- string: server grid ID
}
```
