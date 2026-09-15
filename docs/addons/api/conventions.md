# API Conventions

The `hafen.*` API follows a consistent, predictable design across all subsystems.

---

## 1. Subsystems and Method Invocation

* Subsystems on the root global are invoked as zero-argument functions:
  ```lua
  local timer_service = hafen.timer()
  local event_bus = hafen.event()
  ```
* Character-specific subsystems are invoked from an active `Session` object:
  ```lua
  local current_session = hafen.session():current()
  local player_subsystem = current_session:player()
  local world_subsystem = current_session:world()
  ```

---

## 2. Getters, Setters, and Chaining (Arity)

The API does not use `getXYZ` / `setXYZ` prefixes:
* **Calling with no arguments reads (Getter)**:
  ```lua
  local current_title = window_handle:title()
  local is_visible = window_handle:visible()
  ```
* **Calling with arguments writes (Setter)**:
  Setters return the receiver object, allowing call chaining:
  ```lua
  window_handle:title("Radar Window")
    :size(200, 100)
    :position(50, 50)
    :visible(true)
  ```

---

## 3. Collections and Queries

Collections provide consistent search and enumeration methods:

| Method | Return Type | Description |
|---|---|---|
| `:list(filter?)` | `table[]` | Returns an array of all matching items (empty array if none match). |
| `:count(filter?)` | `number` | Returns the total count of matching items. |
| `:find(filter)` | `item \| nil` | Returns the first item matching the filter. |
| `:get(id)` | `item \| nil` | Returns the item associated with a specific key or ID. |

### Filter Syntax
Filters accept either:
1. **A substring string**: matches against the resource name or identifier.
   ```lua
   local tree_count = session:world():gob():count("terobjs/tree")
   ```
2. **A predicate function**: receives the candidate item and returns `true` or `false`.
   ```lua
   local injured_players = session:world():gob():within(30, function(game_object)
     return game_object:player() and (game_object:health() or 1) < 1.0
   end)
   ```

---

## 4. Strict Boolean Types

Boolean parameters require strict `true` or `false` booleans. Non-boolean values (e.g. `1`, `0`, `"true"`, `nil`) will raise an error.

```lua
-- Correct
window_handle:visible(true)
window_handle:visible(false)

-- Error: raises type mismatch
window_handle:visible(1)
```

---

<a id="snapshots-vs-handles"></a>
## 5. Live Handles vs. Snapshots

* **Live Handles (`Gob`, `Widget`, `Session`)**: Object instances that maintain an internal reference to engine state. They update automatically as game entities move or change. If the underlying entity despawns or closes, calls return `nil` or `false` on `:exists()`.
* **Data Snapshots (`Position`, `MeterInfo`, `BuffInfo`)**: Plain, immutable Lua tables representing values at a specific instant in time.
