# hafen.event: the message streams

The client's own widgets talk to the server in **messages**, and these two doors put your addon in the
middle of that conversation: one for a message on its way out, one for an update on its way in. Reach for
them to stop, rewrite or re-issue something a widget is about to send, or to swallow an update before the
widget applies it.

> **The one thing here that reaches the server.** `ev:resend()` and `ev:send(t)` issue a message in place
> of the one the widget was about to send. Everything else on this page observes, or cancels the client's
> own behaviour.

**Both key sets are open**, unlike [the bus catalogue](bus.md): a message name is protocol the server can
introduce, not a catalogue the client owns, so any string is accepted. One string is reserved: `*` is
[the whole stream](#the-whole-stream), every message on it.

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
| `ev:position(i)` | argument `i` as a [Position](../position.md); throws when that argument is not a coordinate |
| `ev:pixel(i)` | argument `i` as `{x=, y=}` design pixels in the sending widget's own space; throws when that argument is not a coordinate |
| `ev:preventDefault()` | cancel the send |
| `ev:resend()` | re-send the original arguments verbatim; implies `preventDefault` |
| `ev:send(t)` | send a new argument table, a [Position](../position.md) where a coordinate goes; implies `preventDefault` |

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
  a[2] = hafen.session():current():world():snapPlace(ev:position(2))
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
`take` · `transfer`. An `action` key is **not** in [the closed set](bus.md): any string is accepted,
because a message name is protocol the server can introduce, and refusing an unknown one would refuse a
legitimate one tomorrow — and `*` reaches [all of them at once](#the-whole-stream). Two handlers on one
`msg` both run; either one calling `preventDefault` cancels the send.

## Filtering an inbound update

`hafen.event():message():on(msg, fn)` is the inbound mirror: it fires when a server update `msg` is about
to be applied to a widget.

| `ev` on `message` | Description |
|---|---|
| `ev:msg()` | the message name |
| `ev:target()` | the receiving [Widget](../ui/widget.md) |
| `ev:args()` | a 1-based array snapshot of the raw protocol arguments, in the units the wire carries |
| `ev:position(i)` | argument `i` as a [Position](../position.md), as on `action` above |
| `ev:pixel(i)` | argument `i` as `{x=, y=}` design pixels in the receiving widget's own space |
| `ev:preventDefault()` | **swallow** the update, so the widget never applies it |
| `ev:rewrite(t)` | apply the update with new arguments, a [Position](../position.md) included, as on `action` above |

`preventDefault` wins over `rewrite` if both are called. Common `msg` names: `set` · `add` · `del`.

```lua
-- freeze the HUD meter bars by swallowing their updates:
hafen.event():message():on("set", function(ev)
  if frozen and ev:target():type() == "IMeter" then ev:preventDefault() end
end)
```

Like `action`, a `message` key is open: any string is accepted, because an update's name is protocol
just as an action's is — and `*` reaches [every one of them at once](#the-whole-stream).

## The whole stream

`*` is the key both streams reserve for **every message on this stream**, and it is the one subscription
you cannot write by hand: the key set is open because a name is protocol the server can introduce, so the
list of names is unknowable and enumerating it is exactly what that openness exists to avoid. The key
collides with nothing, because a message name is an identifier and no message is called `*`.

### Every message, on the way out

`hafen.event():action():on("*", fn)` fires for every message the client sends, whatever its name. Reach
for it when you cannot name what you are after in advance — a live log of the client's traffic, a filter
list that fills itself from what actually arrives, an audit of what a window sends before you have read a
line of it.

```lua
-- every action the client sends, as it goes out:
hafen.event():action():on("*", function(ev)
  hafen.log():write(ev:sender():type() .. " -> " .. ev:msg() .. " (" .. #ev:args() .. " args)")
end)
```

The `ev` is the one a named key is handed, whole: `ev:msg()` says which message fired, and `ev:sender()`,
`ev:args()`, `ev:position(i)`, `ev:pixel(i)`, `ev:preventDefault()`, `ev:resend()` and `ev:send(t)` behave
exactly as they do above.

Hold a name **and** `*`, and both handlers run for that name, **the named one first**, over **one** `ev`:
the specific claim on a message sees it before the ambient one, and a `preventDefault` from either cancels
the send once. `sub:off()` on the wildcard ends that subscription alone, leaving a named one on the same
stream firing. A wildcard is your addon's own: an addon holding only `click` is never called for another
name, whatever anyone else subscribed to.

> **A wildcard costs you the whole stream.** Your handler runs on every message the client sends, where a
> named key runs it on one — so keep the body short, and read
> [threading](../conventions.md#threading) before you make one wait on anything. An addon that named its
> key pays nothing for someone else's wildcard.

Two sends the `action` stream does not report, and a wildcard is where you would notice:

- **A message sent from inside an `action` handler.** The stream is not re-entered while it is
  dispatching, which is what stops a handler recursing on its own traffic — so that send reaches the
  server without being reported to anyone, your own wildcard included. `ev:resend()` and `ev:send(t)`
  bypass the stream for the same reason.
- **A send made off the UI-locked path.** Handlers run only where the sending code already holds the
  client's UI lock, which is every player action and every message a widget sends while the client is
  running. A send from a thread outside it passes straight through.

### Every update, on the way in

`hafen.event():message():on("*", fn)` is the inbound mirror: it fires for every update the server sends,
whatever its name and whichever widget it is aimed at. This is the one to reach for first, because the
name you want is usually a window of watching away — make the thing happen on screen, read what arrived,
then subscribe to it by name.

```lua
-- every update the server sends, before the widget applies it:
hafen.event():message():on("*", function(ev)
  hafen.log():write(ev:target():type() .. " <- " .. ev:msg() .. " (" .. #ev:args() .. " args)")
end)
```

The `ev` is the one a named key is handed, whole: `ev:msg()` says which update fired, and `ev:target()`,
`ev:args()`, `ev:position(i)`, `ev:pixel(i)`, `ev:preventDefault()` and `ev:rewrite(t)` behave exactly as
they do above. Holding a name **and** `*` behaves here as it does on the way out: both handlers run for
that name, the named one first, over one `ev`, and `sub:off()` on the wildcard leaves a named
subscription on the same stream firing.

> **`ev:preventDefault()` on an inbound wildcard stops the client.** A named key swallows one update; a
> wildcard swallows **every** update, so the widget tree stops hearing from the server altogether — and
> the client's own change detection reads the updates that were applied, so [the bus](bus.md) goes quiet
> with it. Swallow inside an `if` on `ev:msg()`, never at the top of the handler.

An inbound handler also runs where the client can feel it. It runs under the very lock the client takes
to tick and to draw, so the time your handler spends is time the frame is not being drawn — and a
wildcard spends it on every update the server sends rather than on one name. What stands between a slow
handler and a visible stutter is the [CPU budget](../../runtime.md#budgets-and-the-watchdog), which
disables an addon that sustains the overrun rather than letting the client stutter on.

> **Keep an inbound wildcard's body short.** Read a field, count something, append to a table you drain
> on a [timer](../timer.md) — and read [threading](../conventions.md#threading) before you make one wait
> on anything at all.

## See also

- [`hafen.event()`](README.md) — subscribing, and the handle that ends one
- [the catalogue](bus.md) — the closed set of client-wide facts, for what a message is not
- [the Widget object](../ui/widget.md) — what `ev:sender()` and `ev:target()` hand you
- [Position](../position.md) — the place type both streams take and answer with
- [conventions](../conventions.md#threading) — why a handler must not block
