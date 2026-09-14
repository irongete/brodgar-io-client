# Items & Containers Type Snapshots

Table schemas for inventory items and container grids.

---

## `ItemInfo` (Item Snapshot)

Returned by `item:info()`:

```lua
{
  name = "Stone Axe",         -- string: item name
  res = "gfx/invobjs/axe",    -- string: resource path
  quality = 24.5,             -- number | nil: quality score
  amount = 1,                 -- number: stack count (or 1)
  position = { x = 0, y = 0 },-- table: grid slot coordinate
  size = { w = 2, h = 2 }     -- table: width and height in inventory squares
}
```

---

## `ContainerInfo` (Inventory Grid Snapshot)

Returned by `container:info()`:

```lua
{
  name = "Backpack",          -- string: container window title
  size = { w = 6, h = 4 },    -- table: total slots (width x height)
  itemCount = 8               -- number: occupied slots
}
```
