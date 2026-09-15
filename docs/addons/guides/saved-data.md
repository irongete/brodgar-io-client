# Saved Data & Persistence

Addons can persist state across game sessions without managing raw disk files. The client automatically manages an SQLite database per addon located at `savedata/<addon_id>/<addon_id>.sqlite`.

## Storage Scopes

| Scope | Method | Survives | Best For |
|---|---|---|---|
| **Character-Scoped** | `session:store():var("key")` | Logouts, restarts, character switches. | Character-specific window positions, track lists, and preferences. |
| **Addon-Scoped** | `hafen.store():var("key")` | Client restarts and addon reloads. | Global addon configurations shared across all accounts and characters. |
| **Structured Tables** | `hafen.store():table("name")` | Client restarts and addon reloads. | Large datasets, logs, transaction records, and queryable rows. |

---

## 1. Character Variables (`session:store():var`)

Access persistent key-value tables scoped to the character currently playing:

```lua
hafen.event():on("SessionEnteredWorld", function(session)
  local character_settings = session:store():var("ui_settings")

  -- Read existing values (or initialize defaults if nil)
  if character_settings.window_open == nil then
    character_settings.window_open = true
  end

  hafen.log():write("Window setting for character: " .. tostring(character_settings.window_open))

  -- Any modifications to the table are automatically persisted to disk
  character_settings.last_login_timestamp = os.time()
end)
```

## 2. Global Addon Variables (`hafen.store():var`)

Access persistent key-value tables shared across the entire client:

```lua
local global_settings = hafen.store():var("general")

if global_settings.show_notifications == nil then
  global_settings.show_notifications = true
end

-- Update a setting
global_settings.show_notifications = false
```

## 3. Structured Data with Tables (`hafen.store():table`)

For larger datasets that require querying or filtering, use structured store tables:

```lua
local death_log_table = hafen.store():table("death_log")

-- Record a new entry
death_log_table:put({
  character_name = "Bjorn",
  cause = "Bear attack",
  timestamp = os.time()
})

-- Query rows matching criteria
local recent_events = death_log_table:list("character_name = ?", "Bjorn")
for _, record in ipairs(recent_events) do
  hafen.log():write("Logged death: " .. record.cause .. " at " .. os.date("%Y-%m-%d %H:%M:%S", record.timestamp))
end
```

## Automatic Flushing
State tables are flushed to SQLite:
* Automatically every **30 seconds**.
* When the addon is disabled or during `:reload`.
* Cleanly when closing the game client.
