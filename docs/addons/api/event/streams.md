# hafen.event: the message streams

The client's own widgets talk to the server in **messages**, and these two doors put your addon in the
middle of that conversation: one for a message on its way out, one for an update on its way in. Reach for
them to stop, rewrite or re-issue something a widget is about to send, or to swallow an update before the
widget applies it.

> **The one thing here that reaches the server.** `ev:resend()` and `ev:send(t)` issue a message in place
> of the one the widget was about to send. Everything else on this page observes, or cancels the client's
> own behaviour.

**Both key sets are open**, unlike [the bus catalogue](bus.md): a message name is protocol the server can
introduce, not a catalogue the client owns, so any string is accepted and one that never arrives simply
never fires.

## Intercepting an outbound action

`hafen.event():action():on(msg, fn)` fires when a widget is about to send an action `msg` to the server,
with the arguments **fully resolved** — for a move `"click"`, that is the destination world coordinate,
which does not exist yet at input time. This is the door for stopping or rewriting something *before* it
reaches the server, which an event on [the bus](bus.md) would arrive too late to do.

| `ev` on `action` | Description |
|---|---|
| `ev:msg()` | the message name |
| `ev:sender()` | the sending [Widget](../ui/widget.md) |
| `ev:args()` | a 1-based array snapshot of the raw protocol arguments, in the units the wire carries; a coordinate is `{x=, y=}` |
| `ev:position(i)` | argument `i` as a [Position](../world.md#the-position-type); throws when that argument is not a coordinate |
| `ev:pixel(i)` | argument `i` as `{x=, y=}` design pixels in the sending widget's own space; throws when that argument is not a coordinate |
| `ev:preventDefault()` | cancel the send |
| `ev:resend()` | re-send the original arguments verbatim; implies `preventDefault` |
| `ev:send(t)` | send a new argument table, a [Position](../world.md#the-position-type) where a coordinate goes; implies `preventDefault` |

A coordinate argument is in one of two spaces and nothing in its shape says which: a `click` carries the
press point at 1 and the destination in the world at 2, both `{x=, y=}`. Name the space at the index you
mean, and each verb throws naming the other on an index holding anything else. `ev:args()` stays raw,
because `resend` and `send` round-trip through it to the server.

**`click` is not the map's alone.** A message name is protocol, and several widgets send that one — a
portrait, an item box, a party member's tile — carrying arguments of their own, at other indices and
often not coordinates at all. Check `ev:sender()` before reading an index, as the example below does; a
handler that assumes the map reads argument 2 of a message that never had one.

Writing one back takes those two spaces just as seriously. **A Position is accepted wherever a coordinate
argument goes**, and the client encodes the wire form for you, so rewriting a destination needs no
arithmetic; a `{x=, y=}` table in the same list is still taken verbatim, because a table is as likely to
be a screen pixel as a place and only a Position says which. A place this session cannot locate throws
rather than sending a number that would walk you somewhere else.

```lua
-- snap every walk to the centre of the tile you clicked:
hafen.event():action():on("click", function(ev)
  if ev:sender():type() ~= "MapView" then return end
  local a = ev:args()
  a[2] = hafen.world():snapPlace(ev:position(2))
  ev:send(a)
end)
```

`resend` and `send` bypass every `action` handler, so re-issuing an action cannot loop — the "intercept my
move, do something, then move" pattern:

```lua
hafen.event():action():on("click", function(ev)
  equipBoots()
  ev:resend()
end)
```

`ev:sender()` is a live handle, so `ev:sender():type()` reads the class and `ev:sender():parent()`
navigates from it. Common `msg` names: `click` · `itemact` · `drop` · `place` · `sel` · `act` · `use` ·
`take` · `transfer`. An `action` key is **not** in [the closed set](bus.md): any string is accepted, because a
message name is protocol the server can introduce, and refusing an unknown one would refuse a legitimate
one tomorrow. Two handlers on one `msg` both run; either one calling `preventDefault` cancels the send.

## Filtering an inbound update

`hafen.event():message():on(msg, fn)` is the inbound mirror: it fires when a server update `msg` is about
to be applied to a widget.

| `ev` on `message` | Description |
|---|---|
| `ev:msg()` | the message name |
| `ev:target()` | the receiving [Widget](../ui/widget.md) |
| `ev:args()` | a 1-based array snapshot of the raw protocol arguments, in the units the wire carries |
| `ev:position(i)` | argument `i` as a [Position](../world.md#the-position-type), as on `action` above |
| `ev:pixel(i)` | argument `i` as `{x=, y=}` design pixels in the receiving widget's own space |
| `ev:preventDefault()` | **swallow** the update, so the widget never applies it |
| `ev:rewrite(t)` | apply the update with new arguments, a [Position](../world.md#the-position-type) included, as on `action` above |

`preventDefault` wins over `rewrite` if both are called. Common `msg` names: `set` · `add` · `del`.

```lua
-- freeze the HUD meter bars by swallowing their updates:
hafen.event():message():on("set", function(ev)
  if frozen and ev:target():type() == "IMeter" then ev:preventDefault() end
end)
```

Like `action`, a `message` key is open: any string is accepted and may never fire.

## See also

- [`hafen.event()`](README.md) — subscribing, and the handle that ends one
- [the catalogue](bus.md) — the closed set of client-wide facts, for what a message is not
- [the Widget object](../ui/widget.md) — what `ev:sender()` and `ev:target()` hand you
- [`hafen.world`](../world.md#the-position-type) — the Position type both streams take and answer with
- [conventions](../conventions.md#threading) — why a handler must not block
