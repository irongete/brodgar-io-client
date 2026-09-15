# Events and Timers

Addon code should react to specific moments in the game rather than executing heavy operations inside render loops. This guide covers the event bus and the timer scheduler.

## 1. The Event Bus

Subscribe to global game events using [`hafen.event():on(event_name, handler)`](../api/event/README.md):

```lua
-- Runs when the addon loads
hafen.event():on("Load", function()
  hafen.log():write("Addon system initialized.")
end)

-- Runs whenever a character enters the game world
hafen.event():on("SessionEnteredWorld", function(session)
  local character_name = session:character() or "Unknown"
  hafen.log():write("Character entered world: " .. character_name)
end)

-- Runs when a session disconnects or logs out
hafen.event():on("SessionRemoved", function(session)
  hafen.log():write("Session disconnected: " .. session:user())
end)
```

### Common Game Events

| Event Name | Handler Argument | Description |
|---|---|---|
| `Load` | None | Dispatched once after all addon files have run. |
| `Disable` | None | Dispatched when the addon is unloading or the client is shutting down. |
| `SessionEnteredWorld` | `session` | A character has completed loading into the world. |
| `SessionSelected` | `session` | The active screen view switched to this character. |
| `SessionRemoved` | `session` | A character session disconnected. |
| `GobAdded` | `game_object` | An entity or object spawned within render distance. |
| `GobRemoved` | `game_object` | An entity or object left render distance or despawned. |
| `MessageAdded` | `message` | A new chat line was received. |

---

## 2. Timers

Schedule periodic work or delayed tasks using [`hafen.timer()`](../api/timer.md).

### Recurring Timers (`:every`)
Runs a callback repeatedly at a specified interval (in seconds):

```lua
-- Execute every 3 seconds
local heartbeat_timer = hafen.timer():every(3, function()
  local current_session = hafen.session():current()
  if current_session then
    local stamina_meter = current_session:meter():get("stamina")
    if stamina_meter then
      hafen.log():write("Current stamina: " .. math.floor(stamina_meter:value() * 100) .. "%")
    end
  end
end)
```

### One-Shot Timers (`:after`)
Runs a callback once after a delay:

```lua
-- Execute once after 10 seconds
hafen.timer():after(10, function()
  hafen.log():write("10 seconds have passed since startup.")
end)
```

### Cancelling Timers
Timers return a handle that can be cancelled at any time:

```lua
local alert_timer = hafen.timer():every(1, function()
  hafen.log():write("Alert check...")
end)

-- Later, cancel the timer:
alert_timer:cancel()
```

> All active timers and event subscriptions are automatically cleaned up when the addon is reloaded (`:reload`) or disabled.
