# hafen.websocket: WebSockets

Establish full-duplex, persistent WebSocket client connections to external services.

## Manifest Requirements

WebSockets require the `websocket.connect` permission and an explicit host entry in `manifest.json`:

```json
{
  "permissions": ["websocket.connect"],
  "network": {
    "hosts": ["ws.example.com:8080", "wss.myservice.org"]
  }
}
```

---

## Quick Example

```lua
local socket_connection = hafen.websocket():connect("wss://ws.example.com/feed")

socket_connection:on("open", function()
  hafen.log():write("WebSocket connection established.")
  socket_connection:send(hafen.json():encode({ action = "subscribe", channel = "alerts" }))
end)

socket_connection:on("message", function(message_payload)
  hafen.log():write("Received WebSocket message: " .. message_payload)
end)

socket_connection:on("error", function(error_message)
  hafen.log():write("WebSocket error occurred: " .. error_message)
end)

socket_connection:on("close", function(close_code, reason)
  hafen.log():write("WebSocket disconnected: " .. tostring(reason))
end)
```

---

## Methods on `hafen.websocket()`

| Method | Parameters | Returns | Permission | Description |
|---|---|---|---|---|
| `:connect(url)` | `string` | `WebSocket` | `websocket.connect` | Initiates an asynchronous WebSocket connection to `url`. |

---

## Methods on `WebSocket`

| Method | Parameters | Returns | Description |
|---|---|---|---|
| `:send(data)` | `string` | `self` | Transmits a text frame over the active connection. |
| `:close([code], [reason])` | `[number], [string]` | `self` | Initiates a graceful close handshake. |
| `:state()` | None | `string` | Current connection state (`"connecting"`, `"open"`, `"closing"`, `"closed"`). |
| `:on(event, handler)` | `string, function` | `self` | Subscribes to connection lifecycle events (`"open"`, `"message"`, `"error"`, `"close"`). |

> All open WebSocket connections are automatically closed when your addon is reloaded or disabled.
