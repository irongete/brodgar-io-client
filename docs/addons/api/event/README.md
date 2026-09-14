# hafen.event: Events & Listeners

Subscribe to engine notifications, character state changes, world updates, and raw network message streams.

## Quick Example

```lua
-- Global event bus listener
hafen.event():on("SessionEnteredWorld", function(session)
  local character_name = session:character() or "Unknown"
  hafen.log():write("Session logged in: " .. character_name)
end)

-- Entity lifecycle
hafen.event():on("GobAdded", function(game_object)
  local resource_name = game_object:name() or ""
  if resource_name:find("terobjs/tree") then
    hafen.log():write("Tree loaded into view.")
  end
end)
```

---

## Event Subsystems

* **[Event Bus](bus/README.md)**: High-level semantic game events (`SessionEnteredWorld`, `GobAdded`, `GobRemoved`, `MeterChanged`, `ChatMessage`, etc.).
* **[Message Streams](streams.md)**: Low-level server protocol messages (`inbound` and `outbound` protocol inspection).

---

## Methods on `hafen.event()`

| Method | Parameters | Returns | Description |
|---|---|---|---|
| `:on(event_name, handler)` | `string, function(...)` | `EventSubscription` | Subscribes a callback to an event. |
| `:remove(event_name)` | `string` | `self` | Removes all listeners registered by your addon for `event_name`. |

> All event subscriptions registered by your addon are automatically cleaned up when the addon reloads or unloads.
