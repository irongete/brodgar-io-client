# hafen.event: Message Streams

Intercept, inspect, cancel, or rewrite low-level protocol messages sent between the client UI and the game server.

## Quick Example

```lua
-- Intercept outgoing MapView clicks
hafen.event():action():on("click", function(event)
  local sending_widget = event:widget()
  if sending_widget:type() == "MapView" then
    local target_gob = event:gob()
    if target_gob then
      hafen.log():write("Player clicked entity: " .. (target_gob:name() or "Object"))
    end
  end
end)
```

---

## Outbound Stream (`hafen.event():action()`)

Fires when a client widget is about to transmit an action message to the server.

### Methods on Action `Event`

| Method | Returns | Description |
|---|---|---|
| `:msg()` | `string` | The protocol message name (e.g. `"click"`, `"use"`, `"drop"`). |
| `:widget()` | `Widget` | The source UI widget sending the message. |
| `:gob()` | `Gob \| nil` | The game object clicked (if originating from a `MapView`). |
| `:args()` | `table[]` | 1-based array of raw protocol arguments. |
| `:position(index)` | `Position` | Converts argument at `index` to a `Position` coordinate. |
| `:preventDefault()` | None | Cancels message dispatch. |
| `:resend()` | None | Re-sends original arguments (requires `widget.send` permission). |
| `:send(new_args_table)` | None | Transmits modified arguments (requires `widget.send` permission). |

---

## Inbound Stream (`hafen.event():message()`)

Fires when an incoming message packet arrives from the server destined for a widget.

```lua
hafen.event():message():on("msg", function(event)
  hafen.log():write("Received server message for widget: " .. event:widget():type())
end)
```
