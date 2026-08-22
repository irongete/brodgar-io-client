# hafen.event: the bus and the message streams

Subscribe to something the client does, instead of polling for it every frame. `hafen.event()` is where
you subscribe when there is no widget or control to hold — a client-wide fact, or a message stream any
widget can produce. Subscribing and cancelling are **unprotected**: one observes and the other stops the
client's own behaviour. The one thing here that reaches the server is
[intercepting an outbound action](streams.md#intercepting-an-outbound-action), where `ev:resend()` and
`ev:send(t)` issue that same message in place of the one the widget was about to send — and those two need
the [`widget.send`](../../guides/permissions.md) permission, the same key `widget:send` needs.

```lua
local sub = hafen.event():on("GobAdded", function(gob)
  hafen.log():write("appeared: " .. (gob:name() or "?"))
end)
-- later:
sub:off()
```

> Nothing on this bus is polled. Every event fires from the change itself, not from a scan of what is
> different since the last frame.

## Subscribe

| Function | Returns | Description |
|---|---|---|
| `hafen.event():on(key, fn)` | a subscription | run `fn(...)` each time `key` fires |

| Method | Description |
|---|---|
| `sub:key()` | what this subscription was registered under — the event key, the message name, the command name |
| `sub:off()` | unsubscribe and hand the subscription back; idempotent, and also done for you on reload or disable |

**Every `:on` in the API hands back one of these**, and every one of them ends with `sub:off()`: the bus
and the [message streams](streams.md) here, a [widget](../ui/widget.md) or a mouse grab you hold, a
[console command](../slash.md), a [hotkey](../client/keybindings.md), and
[waiting for a widget](../ui/replace.md#watching-for-a-widget). A `Sub` answers those two verbs and
nothing else, so a name it does not carry raises at the line that wrote it.

Subscribe once, in the file body or in `Load`. The subscription is owned by your addon and released when
it reloads or is disabled, so there is nothing to unsubscribe by hand. **Two handlers on one key both
fire**, in the order they registered; `off()` on one leaves the other running. A handler that errors is
isolated: the error is logged and it breaks neither your other handlers nor the client.

**The bus keys are a closed set** — a name that is not one of them throws at the line that wrote it,
pointing at [the catalogue](bus.md) rather than reading as a subscription that never fires:

```lua
hafen.event():on("GobAdded ", fn)
-- unknown event 'GobAdded ' — see the catalogue
```

The two [message streams](streams.md) are the exception: their keys are open, because a message name is
protocol the server can introduce, not a catalogue the client owns. What that openness buys is `*`,
[the whole stream](streams.md#the-whole-stream) — the one subscription you cannot write by hand, because
the list it stands for is the server's to grow.

## Read what you are listening to

| Function | Gives you |
|---|---|
| `hafen.event():count(filter)` | how many of your subscriptions are live here |
| `hafen.event():list(filter)` | those subscriptions, as an array of `Sub`s |

Both answer over all three doors this page covers — the bus and the two [message streams](streams.md) —
in that order, and over your addon's subscriptions alone. A string `filter` is a substring match on
`sub:key()`; a function filter is called with each `Sub`. Both are **unprotected**.

```lua
hafen.log():write("listening on " .. hafen.event():count() .. " keys")

for _, sub in ipairs(hafen.event():list(function(s) return s:key() == "GobAdded" end)) do
  sub:off()
end
```

The members are the very `Sub`s `:on` handed you, so `==` finds the one you are holding, and one that
has been ended is gone from the next read. A subscription you made on a **widget** or a mouse grab
belongs to that widget and is not here; it dies with it.

## Pages

| Page | What it covers |
|---|---|
| [the catalogue](bus.md) | every key `:on` accepts: your addon's own life, the sessions under it, the world, the character, the roster, your own entities |
| [the message streams](streams.md) | `hafen.event():action()` and `hafen.event():message()` — a message leaving for the server, and an update arriving from it |

## See also

- [when your code runs](../../runtime.md) — the life of an addon, and what a switch and a reload keep
- [data types](../types.md) — what `:info()` copies out of a payload, shape by shape
- [`hafen.timer`](../timer.md) — for what the bus cannot tell you: polling on your own schedule
- [the Widget object](../ui/widget.md) — subscribing on a widget you hold, and the mouse and its grab
- [conventions](../conventions.md#threading) — why a handler must not block
