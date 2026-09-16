# hafen.event: The Message Streams

The client's widgets talk to the server in messages, and these two doors put your addon in the middle: `hafen.event():action()` for a message on its way out, `hafen.event():message()` for an update on its way in. Stop, rewrite or re-issue what a widget is about to send; swallow an update before the widget applies it.

```lua
-- snap every walk to the centre of the tile you clicked:
hafen.event():action():on("click", function(event)
  if event:widget():type() ~= "MapView" then return end
  local arguments = event:args()
  arguments[2] = hafen.session():current():world():snapPlace(event:position(2))
  event:send(arguments)
end)
```

---

| Rule | Detail |
|---|---|
| What reaches the server | `event:resend()` and `event:send(table)` issue a message in place of the one the widget was about to send. Everything else observes, or cancels the client's own behaviour. |
| Open key sets | Unlike [the bus catalogue](bus/README.md), any string is accepted: a message name is protocol the server can introduce. `*` is reserved for [the whole stream](#the-whole-stream). |
| Two handlers on one name | Both run; either calling `preventDefault` cancels. |

## Intercepting an outbound action

`hafen.event():action():on(msg, fn)` fires when a widget is about to send action `msg`, with the arguments fully resolved (for a move `"click"`, the destination world coordinate, which does not exist at input time): the door for stopping or rewriting before the server hears, where a bus event arrives too late.

> **`event:resend()` and `event:send(table)` need the `widget.send` [permission](../../guides/permissions.md)**, the key [`widget:send`](../ui/widget.md#send-a-message-protected) needs: they put a message on the same wire, and `send` carries arguments of your choosing. `event:preventDefault()` needs nothing. The key read is the key of the addon whose handler is running: an `event` handed to another addon through a shared table is measured against that addon's manifest, as a [`Widget`](../ui/widget.md) or a [`Session`](../session.md) is.

| Method | Returns | Permission | Description |
|---|---|---|---|
| `event:msg()` | `string` | Unprotected | The message name. |
| `event:widget()` | [Widget](../ui/widget.md) | Unprotected | The widget sending it; a live handle, so `:type()` reads the class and `:parent()` navigates. |
| `event:gob()` | [Gob](../gob.md) `\| nil` | Unprotected | The game object a map click landed on; `nil` for ground ([below](#the-object-a-click-landed-on)). |
| `event:args()` | `table` | Unprotected | A 1-based array snapshot of the raw protocol arguments, in wire units; a coordinate is `{x=, y=}`. |
| `event:position(i)` | [Position](../position.md) | Unprotected | Argument `i` as a Position; throws when that argument is not a coordinate. |
| `event:pixel(i)` | `{x=, y=}` | Unprotected | Argument `i` as design pixels in the sending widget's own space; throws when not a coordinate. |
| `event:preventDefault()` | — | Unprotected | Cancel the send. |
| `event:resend()` | — | `widget.send` | Re-send the original arguments verbatim; implies `preventDefault`. |
| `event:send(table)` | — | `widget.send` | Send a new argument table, a Position where a coordinate goes; implies `preventDefault`. |

| Rule | Detail |
|---|---|
| For the handler you are in | A cancel is worth something only while the client waits for the answer: an `event` stashed and used later raises, as does one whose widget has left the tree. Hold the [widget](../ui/widget.md) and use `widget:send(msg, ...)` outside the moment. |
| Two coordinate spaces | A `click` carries the press point at 1 and the world destination at 2, both `{x=, y=}`; nothing in the shape says which. Name the space at the index you mean; each verb throws naming the other on an index holding anything else. `event:args()` stays raw because `resend` and `send` round-trip through it. |
| `click` is not the map's alone | A portrait, an item box, a party member's tile send it too, with other arguments at other indices. Check `event:widget()` before reading an index. |
| Writing a coordinate back | A Position is accepted wherever a coordinate goes and the client encodes the wire form; a `{x=, y=}` table is taken verbatim, since only a Position says which space it is. A place this session cannot locate throws rather than walking you somewhere else. |
| No loop | `resend` and `send` bypass every `action` handler, so re-issuing an action cannot recurse: intercept, act, `event:resend()`. |
| Common names | `click`, `itemact`, `drop`, `place`, `sel`, `act`, `use`, `take`, `transfer`. |

```lua
hafen.event():action():on("click", function(event)
  equipBoots()
  event:resend()
end)
```

### The object a click landed on

`event:gob()` is the [Gob](../gob.md) a `click` from the `MapView` resolved to; `nil` for ground and for any action that is not a map click.

```lua
hafen.event():action():on("click", function(event)
  local gob = event:gob()
  if gob then hafen.log():write("clicked " .. (gob:name() or "?")) end
end)
```

The id is in `event:args()` too, and that copy is not to be read: the wire carries it as a sign-truncated 32-bit number while the server's ids are unsigned, so comparing `args[6]` against `gob:id()` is wrong for every id above 2^31.

## Filtering an inbound update

`hafen.event():message():on(msg, fn)` fires when a server update `msg` is about to be applied to a widget.

| Method | Returns | Permission | Description |
|---|---|---|---|
| `event:msg()` | `string` | Unprotected | The message name. |
| `event:widget()` | [Widget](../ui/widget.md) | Unprotected | The widget about to receive it. |
| `event:args()` | `table` | Unprotected | A 1-based array snapshot of the raw protocol arguments, in wire units. |
| `event:position(i)` | [Position](../position.md) | Unprotected | Argument `i` as a Position, as on `action`. |
| `event:pixel(i)` | `{x=, y=}` | Unprotected | Argument `i` as design pixels in the receiving widget's own space. |
| `event:preventDefault()` | — | Unprotected | Swallow the update, so the widget never applies it. |
| `event:rewrite(table)` | — | Unprotected | Apply the update with new arguments, a Position included, as on `action`. |

| Rule | Detail |
|---|---|
| `preventDefault` wins over `rewrite` | When both are called. |
| One update carries one rewrite | The last `event:rewrite(table)` before the widget applies the update is applied; two addons rewriting one update do not compose and neither is told. One addon swallowing swallows for everybody. |
| Common names | `set`, `add`, `del`. |

```lua
-- freeze the HUD meter bars by swallowing their updates:
hafen.event():message():on("set", function(event)
  if frozen and event:widget():type() == "IMeter" then event:preventDefault() end
end)
```

## The whole stream

`*` is the key both streams reserve for every message on the stream: the one subscription you cannot write by hand, since the list of names is the server's to grow. No message is called `*`.

| Rule | Detail |
|---|---|
| The `event` is whole | `event:msg()` says which message fired; every other verb behaves as under a named key. |
| A name and `*` together | Both handlers run for that name, the named one first, over one `event`; a `preventDefault` from either cancels once. `subscription:off()` on the wildcard leaves a named one firing. A wildcard is your addon's own: an addon holding only `click` is never called for another name. |
| Cost | Your handler runs on every message where a named key runs it on one. Keep the body short and read [threading](../threading.md) before making one wait. An addon that named its key pays nothing for another's wildcard. |

### Every message, on the way out

```lua
hafen.event():action():on("*", function(event)
  hafen.log():write(event:widget():type() .. " -> " .. event:msg() .. " (" .. #event:args() .. " args)")
end)
```

| Not reported | Detail |
|---|---|
| A send from inside an `action` handler into the character whose message it is | The stream is not re-entered while dispatching that character's traffic, which stops a handler recursing; the send reaches the server unreported, your own wildcard included. The guard is one character's: a send into another login's widget is reported on that login's stream. `event:resend()` and `event:send(table)` bypass the stream for the same reason. |
| A send from outside a character's UI | An outbound handler runs inside the tree the message leaves, which is every player action and every widget send; a send from anywhere else passes through unreported. |

An outbound handler is inside a tree while an inbound one is not, so when your Lua is busy on another thread the inbound one waits its turn and the outbound one is skipped, on the terms [threading](../threading.md) states.

### Every update, on the way in

The one to reach for first: make the thing happen on screen, read what arrived, then subscribe by name.

```lua
hafen.event():message():on("*", function(event)
  hafen.log():write(event:widget():type() .. " <- " .. event:msg() .. " (" .. #event:args() .. " args)")
end)
```

> **`event:preventDefault()` on an inbound wildcard stops the client.** A wildcard swallows every update, so the widget tree stops hearing from the server, and the client's change detection reads applied updates, so [the bus](bus/README.md) goes quiet with it. Swallow inside an `if` on `event:msg()`, never at the top of the handler.

| Rule | Detail |
|---|---|
| Where it runs | Where the update arrived, before the widget applies it, which makes `preventDefault` and `rewrite` possible and means the handler cannot be deferred. It holds no character's UI, so it may build a window and write any tree: the one row of [threading](../threading.md)'s table that is neither the step nor inside a tree. |
| No guarding needed | The client lets one entry into your Lua at a time, so this handler and the step's `Update` never run together. |
| Order is not promised | The two are moments the client puts in no sequence: anything that must happen in order belongs in one place. |
| Time | Time spent here is time the client is not spending elsewhere, on every update for a wildcard. The [CPU budget](../../runtime.md#budgets-and-the-watchdog) disables an addon that sustains an overrun. Read a field, count something, append to a table you drain on a [timer](../timer.md); do the work from the step. |

---

## See Also

- [`hafen.event()`](README.md) — subscribing, and the handle that ends one.
- [The catalogue](bus/README.md) — the closed set of client-wide facts, for what a message is not.
- [The Widget object](../ui/widget.md) — what `event:widget()` hands you.
- [Position](../position.md) — the place type both streams take and answer with.
- [Threading](../threading.md) — where each handler runs, and what it may reach.
