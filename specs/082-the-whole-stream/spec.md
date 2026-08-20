# 082 — The whole stream: a wildcard on the two message streams

## What and why

`hafen.event():action():on(msg, fn)` and `hafen.event():message():on(msg, fn)` reach one message
name each. There is no spelling for *every* message: `Subs` keys handlers in a map and
`AddonManager.anyStreamSub` looks the name up exactly, so an addon that wants the whole stream has
to enumerate it by hand — and a hand-written list is the one thing these two key sets are open to
avoid. A message name is protocol the server can introduce; the list is unknowable by construction.

Worse, `"*"` is **accepted today and never fires**. The key set is open, so the subscription
registers, reads as correct, and is a silent no-op — the most expensive way there is to learn a name,
which is exactly what the bus's closed set refuses in order to prevent.

This feature reserves `"*"` on the two streams as *every message on this stream*. It cannot collide:
a protocol name is an identifier, so no message is ever called `*`, and the same character already
means *any* in a [selector](../../docs/addons/api/ui/selectors.md) and in a permission group.

What it unlocks is the class of addon that cannot name what it is looking for in advance: a live log
of the client's traffic, a filter list that populates itself from what actually arrives, an audit of
what a window sends before anyone has read its source.

## Acceptance criteria

1. `hafen.event():action():on("*", fn)` fires for **every** outbound message, whatever its name, with
   the same `ev` an exact-key handler is given — `ev:msg()` names what fired, and `ev:sender()`,
   `ev:args()`, `ev:preventDefault()`, `ev:resend()` and `ev:send(t)` behave exactly as they do on a
   named key.
2. `hafen.event():message():on("*", fn)` is the inbound mirror: it fires for every server update,
   `ev:msg()` names it, and `ev:target()`, `ev:args()`, `ev:preventDefault()` and `ev:rewrite(t)`
   behave as on a named key.
3. One addon holding both `"click"` and `"*"` on one stream has **both** handlers run for a `click`,
   the named key first, over **one** `ev` — the very value both are handed, so a cancel from either
   stops the send exactly once.
4. `sub:off()` on a wildcard ends that subscription alone: an exact-key subscription on the same
   stream goes on firing, and the reverse holds.
5. An addon holding only an exact key is **never** called for another name. A wildcard belonging to
   one addon changes nothing for any other.
6. A message sent from **inside** an action handler is not reported to the streams — the re-entrancy
   guard already in `dispatchAction` — so a wildcard cannot recurse on its own traffic.
7. `hafen.event():on("*", fn)` on the **bus** is refused, and the refusal names the two streams as
   where `"*"` does mean everything.

## Out of scope

- **A wildcard on the bus.** Its 31 keys are a closed set of *facts*, each with its own payload
  shape — a `Gob`, a `Meter`, a `Session` — and one handler cannot be handed a key it has no
  parameter for without changing the bus's callback shape for all 31. The streams stand whole
  without it: an addon can already name every bus key it wants. *The other half would be reading the
  catalogue back from Lua (`hafen.event():keys()`), so a subscriber to all of them need not
  hard-code a list that a later feature silently outgrows.*
- **Making the streams cheaper.** The `hasSub` gate stays exactly as it is for everyone who names a
  key; what a wildcard costs its own subscriber is a documented cost, not an optimisation target.
- **The `ev:resend()`/`ev:send(t)` permission gap** (ROADMAP, filed: 055). This feature widens what
  it reaches from a named message to every message, and closing it is a decision about the
  permission model rather than about the streams.

## Docs impact

Written: `docs/addons/api/event/streams.md` (the wildcard, both halves, its cost, the re-entrancy
fact) · `docs/addons/api/event/README.md` · `docs/addons/api/event/bus.md` ·
`docs/addons/guides/events-and-timers.md` · `docs/addons/guides/debugging.md`.

Derived impact set — `grep -rn "any string is accepted\|may simply never fire\|never fires\|open,
because" docs/` and `grep -rln "action()\|message()\|message stream\|open key\|key set" docs/`:

| Page | Verdict |
|---|---|
| `api/event/streams.md:13`, `:72`, `:100` | **revise** — "any string is accepted and one that never arrives simply never fires" is the claim `"*"` breaks |
| `api/event/README.md:44` | **revise** — states why the two key sets are open; the wildcard belongs in that same paragraph |
| `api/event/bus.md` | **revise** — the closed set is where `"*"` is refused, and the refusal points here |
| `guides/events-and-timers.md:23` | **revise** — the routing table's stream row |
| `guides/debugging.md` | **revise** — the wildcard is a diagnostic before it is anything else |
| `api/player.md:105`, `api/README.md`, `api/time.md`, `api/client/profiling/attribution.md`, `docs/client/ui-controls.md` | **discharge** — incidental matches on the verb names; no claim about the key set |

## Context files

- `src/io/brodgar/addon/AddonManager.java` — 1, 2, 3
- `src/io/brodgar/addon/Subs.java` — 1, 2
- `src/io/brodgar/addon/Addon.java` — 1, 2
- `src/io/brodgar/addon/LuaEvent.java` — 1, 2
- `src/io/brodgar/addon/LuaSub.java` — 1
- `src/io/brodgar/addon/LuaWidget.java` — 1
- `src/io/brodgar/addon/Section.java` — 1, 2
- `src/haven/UI.java` — 1, 2
- `docs/client/network.md` — 1, 2
- `docs/client/services.md` — 1, 2, 3 (which thread a `:command` body runs on, and with which lock)
- `docs/addons/api/timer.md` — 2
- `docs/addons/runtime.md` — 2, 3 (the CPU-budget anchor the wildcard's cost callout links)
- `docs/addons/api/event/streams.md` — 1, 2
- `docs/addons/api/event/README.md` — 3
- `docs/addons/api/event/bus.md` — 3
- `docs/addons/guides/events-and-timers.md` — 3
- `docs/addons/guides/debugging.md` — 3
- `DOCUMENTATION.md`
