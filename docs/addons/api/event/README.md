# hafen.event: The Bus and the Message Streams

Subscribe to something the client does instead of polling for it. A client-wide fact is on the bus. A message stream any widget can produce is for when there is no widget or control to hold.

```lua
local subscription = hafen.event():on("GobAdded", function(gob)
  hafen.log():write("appeared: " .. (gob:name() or "?"))
end)
subscription:off()
```

---

## Subscribe

| Method | Returns | Permission | Description |
|---|---|---|---|
| `hafen.event():on(key, fn)` | `Sub` | Unprotected | Run `fn(...)` each time `key` fires. |
| `subscription:key()` | `string` | Unprotected | What this subscription was registered under: the event key, the message name, the command name. |
| `subscription:off()` | the `Sub` | Unprotected | Unsubscribe. Idempotent, and done for you on reload or disable. |

| Rule | Detail |
|---|---|
| Every `:on` in the API hands back a `Sub` | The bus and the [message streams](streams.md). A [widget](../ui/widget.md) or a mouse grab. A [console command](../console.md). A [hotkey](../client/keybindings.md). A [connection](../websocket.md) or a [voice link](../voice/README.md). [Waiting for a widget](../ui/replace.md#watching-for-a-widget). A `Sub` answers `:key()` and `:off()` and nothing else. Another name raises at the line that wrote it. |
| Subscribe once | In the file body or in `Load`. The subscription is owned by your addon and released when it reloads or is disabled. |
| Fired from the change | Nothing on this bus is polled: every event fires from the change itself, not from a scan of what differs since the last frame. |
| Several handlers | Two handlers on one key both fire, in registration order. `off()` on one leaves the other. A handler that errors is logged and breaks neither your other handlers nor the client. One that fails the client itself (stack, memory) stops [your addon](../../runtime.md#when-a-failure-is-fatal). |
| The bus keys are a closed set | A name not in [the catalogue](bus/README.md) throws at the line that wrote it: `hafen.event():on("GobAdded ", fn)` raises `unknown event 'GobAdded ' — see the catalogue`. |
| The streams are open | A message name is protocol the server can introduce, so the [message streams](streams.md) accept any key, and `*` is [the whole stream](streams.md#the-whole-stream). |
| What reaches the server | [Intercepting an outbound action](streams.md#intercepting-an-outbound-action): `event:resend()` and `event:send(table)` need the [`widget.send`](../../guides/permissions.md) permission, the key `widget:send` needs. Subscribing and cancelling are unprotected. |

## Read what you are listening to

| Method | Returns | Permission | Description |
|---|---|---|---|
| `hafen.event():count(filter)` | `number` | Unprotected | How many of your subscriptions are live here. |
| `hafen.event():list(filter)` | `Sub[]` | Unprotected | Those subscriptions. |

```lua
hafen.log():write("listening on " .. hafen.event():count() .. " keys")
for _, subscription in ipairs(hafen.event():list(function(candidate) return candidate:key() == "GobAdded" end)) do
  subscription:off()
end
```

| Rule | Detail |
|---|---|
| Scope | The bus and the two [message streams](streams.md), in that order, over your addon's subscriptions alone. A string `filter` is a substring match on `subscription:key()`. A function filter is called with each `Sub`. |
| Identity | The members are the `Sub`s `:on` handed you, so `==` finds the one you hold. An ended one is gone from the next read. |
| Not here | A subscription on a widget or a mouse grab belongs to that widget and dies with it. |

## Pages

| Page | Covers |
|---|---|
| [The catalogue](bus/README.md) | Every key `:on` accepts: your addon's own life, the sessions under it, the world, the character, the rosters, your own entities. |
| [The message streams](streams.md) | `hafen.event():action()` and `hafen.event():message()`: a message leaving for the server, and an update arriving from it. |

---

## See Also

- [When your code runs](../../runtime.md) — the life of an addon, and what a switch and a reload keep.
- [Data types](../types/README.md) — what `:info()` copies out of a payload, shape by shape.
- [`hafen.timer`](../timer.md) — polling on your own schedule, for what the bus cannot tell you.
- [The Widget object](../ui/widget.md) — subscribing on a widget you hold, and the mouse and its grab.
- [Threading](../threading.md) — where a handler runs, and which trees it may reach.
