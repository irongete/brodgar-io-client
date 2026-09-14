# hafen.store():var(): Persistent Key-Value Vars

Transparently persisted Lua tables that automatically synchronize with the addon's SQLite database.

## Quick Example

```lua
-- Global addon settings (shared across all accounts)
local global_settings = hafen.store():var("settings")
if global_settings.sound_volume == nil then
  global_settings.sound_volume = 80
end

-- Character-scoped settings (unique per character)
local session = hafen.session():current()
if session then
  local character_settings = session:store():var("hud_layout")
  character_settings.radar_visible = true
  character_settings.radar_pos_x = 140
  character_settings.radar_pos_y = 60
end
```

---

## Methods

| Method | Parameters | Returns | Description |
|---|---|---|---|
| `hafen.store():var(name)` | `string` | `table` | Retrieves an addon-wide global persistent table. |
| `session:store():var(name)`| `string` | `table` | Retrieves a persistent table scoped to that character. |
| `hafen.store():flush()` | None | `self` | Force-flushes all pending dirty tables to disk immediately. |

---

## Supported Data Types

Persistent vars support:
* Primitive values: `string`, `number`, `boolean`.
* Nested tables: nested arrays and dictionaries of supported primitives.
* `nil`: assigning `nil` deletes the corresponding key from the persistent store.

Functions, threads, userdata, and cyclic table references cannot be stored.
