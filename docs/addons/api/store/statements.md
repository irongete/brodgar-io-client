# hafen.store(): Raw SQL Queries & Statements

Execute direct SQL queries, aggregations, joins, updates, and deletes against your addon's local SQLite database.

## Quick Example

```lua
local store_subsystem = hafen.store()

-- Execute an UPDATE with parameterized bindings
local rows_modified = store_subsystem:exec(
  "UPDATE waypoints SET visited = ? WHERE tag = ?",
  true, "iron_mine"
)
hafen.log():write("Updated rows: " .. rows_modified)

-- Execute a SELECT query with parameterized bindings
local query_results = store_subsystem:query(
  "SELECT grid_id, x, y, tag FROM waypoints WHERE visited = ? LIMIT 10",
  true
)

for _, record in ipairs(query_results) do
  hafen.log():write(string.format("Waypoint %s at (%d, %d)", record.tag, record.x, record.y))
end
```

---

## Methods on `hafen.store()`

| Method | Parameters | Returns | Description |
|---|---|---|---|
| `:exec(sql_string, ...)` | `string, ...` | `number` | Executes an `INSERT`, `UPDATE`, `DELETE`, or `PRAGMA` statement. Returns the number of modified rows. |
| `:query(sql_string, ...)` | `string, ...` | `table[]` | Executes a `SELECT` query. Returns an array of row dictionary tables keyed by column name. |

---

## Parameter Binding (`?`)

Bind parameters to `?` placeholders safely to prevent SQL injection:
* Strings bind as `TEXT`.
* Whole numbers bind as `INTEGER`.
* Floating-point numbers bind as `REAL`.
* `true` and `false` bind as `1` and `0`.
* `nil` binds as `NULL`.
