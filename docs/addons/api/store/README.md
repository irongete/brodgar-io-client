# hafen.store: Persistent SQLite Storage

Store persistent data, character settings, queryable records, and custom SQLite tables.

Every addon has a dedicated SQLite database located at `savedata/<addon_id>/<addon_id>.sqlite`.

## Storage Subsystems

| Storage Model | Documentation | Description |
|---|---|---|
| **Key-Value Vars** | **[vars.md](vars.md)** | Auto-persisting Lua tables for character-scoped and addon-scoped settings. |
| **Structured Tables** | **[tables.md](tables.md)** | Structured records with schema definitions, queries, and filtering. |
| **Raw SQL Statements** | **[statements.md](statements.md)** | Custom prepared SQL statements for advanced queries and indexing. |

---

## Quick Example

```lua
-- Addon-wide global persistent settings table
local global_vars = hafen.store():var("config")
global_vars.notifications = true

-- Character-specific persistent settings table
local session = hafen.session():current()
if session then
  local character_vars = session:store():var("tracker_state")
  character_vars.window_open = true
end
```

All modifications to persistent tables are automatically written to disk asynchronously and flushed during `:reload` or client shutdown.
