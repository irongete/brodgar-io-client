# hafen.session: Sessions and Accounts

Access logged-in character sessions, manage multi-character environments, and retrieve character-scoped subsystems.

## Quick Example

```lua
-- Access character currently visible on screen
local current_session = hafen.session():current()
if current_session then
  local character_name = current_session:character() or "In Login Queue"
  hafen.log():write("Active character: " .. character_name)
end

-- Iterate over all connected accounts
for _, active_session in ipairs(hafen.session():list()) do
  hafen.log():write("Connected user: " .. active_session:user())
end
```

## Methods on `hafen.session()`

| Method | Parameters | Returns | Description |
|---|---|---|---|
| `:current()` | None | `Session \| nil` | The session currently being viewed on screen. |
| `:list()` | None | `Session[]` | Array of all active sessions connected in the client. |
| `:get(username)` | `string` | `Session \| nil` | Finds a session matching the account username. |
| `:count()` | None | `number` | Total number of connected sessions. |

## Methods on `Session`

| Method | Parameters | Returns | Permission | Description |
|---|---|---|---|---|
| `:user()` | None | `string` | `-` | Account username. |
| `:character()` | None | `string \| nil` | `-` | Character name (or `nil` if still at character select screen). |
| `:world()` | None | `World` | `-` | World subsystem for this character. |
| `:player()` | None | `Player` | `-` | Player entity, stats, and inventory. |
| `:ui()` | None | `UI` | `-` | Root of the character's in-game HUD widget tree. |
| `:chat()` | None | `Chat` | `-` | Chat channels subsystem. |
| `:store()` | None | `Store` | `-` | Character-scoped persistent storage (`:var()`). |
| `:close()` | None | `self` | `session.close` | Logs out this character session. |

## Events

* `SessionEnteredWorld`: Dispatched when a session finishes loading into the game world (`function(session)`).
* `SessionSelected`: Dispatched when the active screen view switches to this session (`function(session)`).
* `SessionRemoved`: Dispatched when a session logs out or disconnects (`function(session)`).
