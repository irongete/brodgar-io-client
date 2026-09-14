# hafen.store():table(): Structured Database Tables

Define, query, and index custom typed SQLite tables for scalable structured data storage.

## Quick Example

```lua
-- Declare table schema
local waypoint_table = hafen.store():table("waypoints")
  :column("grid_id", "text")
  :column("x", "integer")
  :column("y", "integer")
  :column("tag", "text")
  :column("visited", "boolean")
  :column("metadata", "json")
  :key("grid_id", "x", "y")
  :index("tag")
  :create()

-- Insert or upsert a row
waypoint_table:put({
  grid_id = "g_01",
  x = 45,
  y = 80,
  tag = "iron_mine",
  visited = true,
  metadata = { quality = 75, depth = 3 }
})

-- Retrieve row by primary key
local waypoint = waypoint_table:get("g_01", 45, 80)
if waypoint then
  hafen.log():write("Found waypoint: " .. waypoint.tag .. ", visited: " .. tostring(waypoint.visited))
end

-- Query rows using SQL WHERE clauses
local mine_records = waypoint_table:list("WHERE tag = ? ORDER BY x", "iron_mine")
for _, record in ipairs(mine_records) do
  hafen.log():write(string.format("Mine at (%d, %d)", record.x, record.y))
end
```

---

## Schema Declaration Methods

| Method | Parameters | Returns | Description |
|---|---|---|---|
| `hafen.store():table(name)` | `string` | `TableBuilder` | Begins a table declaration for `name`. |
| `builder:column(name, type)` | `string, string` | `self` | Adds a column. Types: `"text"`, `"integer"`, `"real"`, `"boolean"`, `"json"`. |
| `builder:key(col1, ...)` | `string, ...` | `self` | Specifies composite primary key columns. |
| `builder:index(col1, ...)` | `string, ...` | `self` | Creates an index over designated columns. |
| `builder:create()` | None | `StoreTable` | Compiles schema and returns the active table handle. |

---

## Methods on `StoreTable`

| Method | Parameters | Returns | Description |
|---|---|---|---|
| `:put(row_table)` | `table` | `self` | Inserts or replaces (upserts) a row. |
| `:get(key1, ...)` | `any, ...` | `table \| nil` | Finds a row by its primary key values. |
| `:remove(key1, ...)` | `any, ...` | `self` | Deletes a row matching the primary key. |
| `:list([sql_clause, ...])` | `[string, ...]` | `table[]` | Queries matching rows. Supports SQL `WHERE`, `ORDER BY`, `LIMIT`. |
| `:count([sql_clause, ...])`| `[string, ...]` | `number` | Counts matching rows. |
