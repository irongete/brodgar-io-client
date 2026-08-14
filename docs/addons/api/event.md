# hafen.event: the bus and the message streams

Subscribe to something the client does, instead of polling for it every frame. `hafen.event()` is where
you subscribe when there is no widget or control to hold — a client-wide fact, or a message stream any
widget can produce. `hafen.event()` is **unprotected**: subscribing observes, and cancelling stops the
client's own behaviour. The one thing here that reaches the server is
[intercepting an outbound action](#intercepting-an-outbound-action), where `ev:resend()` and `ev:send(t)`
issue that same message in place of the one the widget was about to send.

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
| `sub:off()` | unsubscribe; idempotent, and also done for you on reload or disable |

Subscribe once, in the file body or in `Load`. The subscription is owned by your addon and released when
it reloads or is disabled, so there is nothing to unsubscribe by hand. **Two handlers on one key both
fire**, in the order they registered; `off()` on one leaves the other running. A handler that errors is
isolated: the error is logged and it breaks neither your other handlers nor the client.

**The bus keys below are a closed set** — a name that is not one of them throws at the line that wrote
it, pointing at the catalogue rather than reading as a subscription that never fires:

```lua
hafen.event():on("GobAdded ", fn)
-- unknown event 'GobAdded ' — see the catalogue below
```

The two message streams further down are the exception: their keys are open, because a message name is
protocol the server can introduce, not a catalogue the client owns.

## Lifecycle

| Event | Payload | Fires |
|---|---|---|
| `Load` | — | once, when the addon is loaded, before entering the world |
| `EnterWorld` | — | each time you enter the world: login, and again on `:reload` while in-world |
| `Update` | `dt` (number) | every frame; `dt` is seconds since the last frame |
| `Disable` | — | when the addon is disabled or reloaded, or the session ends |

`EnterWorld` fires once the HUD exists — the [action menu](menugrid.md) included, so the entries your
addon adds go in from there — but much character-sheet data streams in for a few seconds afterwards, see
[missing data returns nil](conventions.md#missing-data-returns-nil). Keep `Update` handlers cheap: they
run on the UI thread on every frame.

## World

| Event | Payload | Fires |
|---|---|---|
| `GobAdded` | [Gob](gob.md) | a game object enters the world or your view |
| `GobRemoved` | [Gob](gob.md) | a game object leaves |
| `GobOverlayAdded` | `ev` — `:gob()` `:key()` `:native()` | something is attached to a game object — see [`gob:overlay()`](overlay.md) |
| `GobOverlayRemoved` | `ev` — `:gob()` `:key()` `:native()` | something attached to a game object goes away |

Prefer these over scanning [`hafen.world():gob():list`](world.md) every frame. `ev:gob()` is a live
[Gob object](gob.md). On `GobRemoved` the gob is **already gone**, so only `gob:id()` answers there; if
you need its name, index it on `GobAdded`.

### Overlays coming and going

`GobOverlayAdded` and `GobOverlayRemoved` cover both halves of what
[`gob:overlay()`](overlay.md) reads.

| `ev` on `GobOverlayAdded`/`GobOverlayRemoved` | Description |
|---|---|
| `ev:gob()` | the [Gob](gob.md) the overlay is attached to |
| `ev:key()` | the overlay's key |
| `ev:native()` | `false` for one **you** attached, `true` for one the **game** put there |

`native` is `false` for one **you** attached and `true` for one the **game** put there (a lit fire's
flame, a crop's growth stage), and `key` is then its resource name.

```lua
hafen.event():on("GobOverlayAdded", function(ev)
  if ev:native() then hafen.log():write(ev:gob():id() .. " now carries " .. ev:key()) end
end)
```

The rules below make these predictable:

- **Yours are private, the game's are public.** An overlay key belongs to your addon, so a
  `native = false` event goes **only** to the addon that attached it — a key another addon cannot read
  is a name it cannot act on. Native events broadcast, because a resource name means the same thing to
  everyone.
- **They arrive on the next frame**, not inside the `:add` itself — the game's own overlays arrive on
  loader threads, and both halves use one moment. A handler runs on the UI thread and reads the truth:
  the overlay is already there on an add, already gone on a removal.
- **Re-attaching under the same key fires both** — the removal, then the add. The key survives; the thing
  under it does not.
- **The game's overlays are counted by key.** Several of them may share one resource and collapse to one
  key, so a second one of that resource arriving is not an add — read
  [`ov:count()`](overlay.md) for the multiplicity.

When a gob leaves, **yours** on it are reported gone *before* that gob's own `GobRemoved`, so a handler
already reads the truth. The game's are not: the client drops a departing gob whole rather than taking its
overlays off one by one, and a native removal is reported only while the gob is still there. A `:reload`
fires neither: the addon that would hear it is the one going away.

## Character and status

These come from the HUD's own widgets, so they start once the HUD is up.

| Event | Payload | Fires |
|---|---|---|
| `MeterAdded` | [`Meter`](meter.md) | a HUD meter bar appears |
| `MeterRemoved` | [`Meter`](meter.md) | a HUD meter bar goes away — the object still reads, `:exists()` is false |
| `MeterChanged` | [`Meter`](meter.md) | a meter bar's value or colour changes |
| `BuffAdded` | [`Buff`](buff.md) | a buff appears |
| `BuffRemoved` | [`Buff`](buff.md) | a buff goes away — the object still reads, `:exists()` is false |
| `BuffChanged` | [`Buff`](buff.md) | a buff's content updates |
| `FepChanged` | [`Food`](char.md#food) | FEP or hunger changes |
| `StudyChanged` | [`StudySlot`](study.md#a-slot)`[]` | the study slots change: an add, a removal, or data resolving |
| `EquipChanged` | [`Item`](ui/items.md#the-item-object)`[]` | worn equipment changes |
| `ActionbarChanged` | [`Slot`](actionbar.md) | an action-bar slot is set, cleared or changed |
| `WoundChanged` | [`Wound`](wound.md#a-wound)`[]` | a wound is added or healed, or its severity changes |

Items entering or leaving a **container** are not on this bus: a chest is not a global fact, so you
subscribe to the container itself with
[`widget:on("ItemAdded"/"ItemRemoved"/"Destroy", fn)`](ui/items.md#the-container-lifecycle).
`EquipChanged` stays global because your worn gear is one fixed surface.

`ActionbarChanged` hands you the **changed slot** as a live [`Slot` object](actionbar.md) — the same
interned object `hafen.actionbar():get(n)` returns, so `payload` and
`hafen.actionbar():get(payload:index())` are one object and you can key a table by it. `slot:index()` is
the raw 0-based game index. It fires on a set, a clear, a drag, or when a slot's data resolves, and
**not** on `:cooldown()` ticking, which would fire every frame — read the cooldown live off the object
instead. At login the occupied slots stream in as a burst, one fire each.

A slot [held](actionbar.md#hold-a-slot-unprotected) for one of your own menu entries fires it on **both
edges**: once when the hold takes the slot, and once when it ends and the server's own content comes back.
The payload is the same `Slot`, and while the hold is on it `slot:res()` is the entry's identity.

> For the list events — `StudyChanged`, `EquipChanged`, `KinChanged`, `WoundChanged` — the payload is
> the **full new list**, not a delta. Read the initial state once with the section's own `:list()`
> verb, then listen.

## Roster, quests, markers

| Event | Payload | Fires |
|---|---|---|
| `KinChanged` | [`Kin`](kin.md)`[]` | a kin is added, removed or edited, or flips online or offline |
| `QuestAdded` | [`Quest`](quest.md#a-quest) | a new active quest appears |
| `QuestDone` | [`Quest`](quest.md#a-quest) | an active quest is completed or failed |
| `MarkersChanged` | `n` (number) | a map marker is added or removed |

`KinChanged` hands you the **whole roster** as live [`Kin` objects](kin.md), in Kin-window sort order —
the same interned objects `hafen.kin():list()` returns, so `payload[1]` and
`hafen.kin():get(payload[1]:id())` are one object and you can key a table by it. It tells you *that* the
roster changed, not *what* changed: keep your own map of the last state if you want to name who just came
online, and key it **by the `Kin` itself** rather than by `:name()`, so a rename does not read as one kin
leaving and another arriving.
[`kin:info()`](types.md#kinentry) is there when you want a plain table instead.

The two quest events hand you the [`Quest`](quest.md#a-quest) itself, which matters most on `QuestDone`:
it fires *because* the status changed, so a handler that keeps the object goes on reading it — including
`q:status()`, which is the field the event is about.

## Widgets appearing and disappearing

A widget is not a global fact either, so there is no `WidgetCreated` event. You say *which* widget you
care about, with the same [selector](ui/selectors.md) a lookup uses:

```lua
hafen.ui():on("window[title=Cupboard]", "appear", function(w)
  hafen.log():write(#w:items() .. " items")
end)
```

`fn` receives the [Widget](ui/widget.md) itself, and **`appear` also covers what is already open**,
because registering scans the live tree — so an addon reloaded with the window up still sees it. See
[watching for a widget](ui/replace.md#watching-for-a-widget) for the two rules that matter: neither
event is about *visibility*, and at `disappear` the widget is a key to match, not something to read.

## The radial menu

| Event | Payload | Fires |
|---|---|---|
| `FlowerMenuOpened` | `string[]` — the petal captions, in ring order | a right-click puts up a radial menu |
| `FlowerMenuClosed` | `string` \| nil — the label picked | that menu goes away |

**Every `FlowerMenuOpened` is followed by exactly one `FlowerMenuClosed`**, whether you picked a petal,
pressed Esc, clicked away, or the menu died under you; the payload is `nil` for everything but a pick. Both
cover the menus the client puts up itself, such as the Kin window's, as well as the server's. Read the ring
from the payload or from [`hafen.flowermenu()`](flowermenu.md), which is the open menu and also names the
object it was opened on.

## World ghosts and sprites

| Event | Payload | Fires |
|---|---|---|
| `GhostClicked` | `ev` — `:ghost()` `:button()` `:x()` `:y()` | a **clickable** [ghost](vr/ghosts.md) of *your* addon is clicked |
| `SpriteClicked` | `ev` — `:sprite()` `:button()` `:x()` `:y()` | a **clickable** [sprite](vr/sprites.md#clickability) of *your* addon is clicked |
| `ObjectClicked` | `ev` — `:object()` `:button()` `:x()` `:y()` | a **clickable** [glTF object](vr/models.md#clickability) of *your* addon is clicked |

All three are **owner-scoped**: they fire only to the addon that owns the clicked entity, unlike the
world and roster events above, which broadcast. That is because a ghost, sprite or object is private
to its addon and its handle never leaves it.

| `ev` on `GhostClicked`/`SpriteClicked`/`ObjectClicked` | Description |
|---|---|
| `ev:ghost()` / `ev:sprite()` / `ev:object()` | the clicked [entity](vr/README.md#one-vocabulary-four-kinds) — only the one matching the event fires reads non-nil |
| `ev:button()` | 1 for left, 3 for right |
| `ev:x()` `ev:y()` | the world point the click resolved to |

The click is **consumed** — no server click, no character walk. An entity fires this only while
clickable; a non-clickable one is click-through and silent, and a sprite facing `"screen"` has no
world mesh, so it is never picked at all.

## Intercepting an outbound action

`hafen.event():action():on(msg, fn)` fires when a widget is about to send an action `msg` to the server,
with the arguments **fully resolved** — for a move `"click"`, that is the destination world coordinate,
which does not exist yet at input time. This is the door for stopping or rewriting something *before* it
reaches the server, which an event on the bus above would arrive too late to do.

| `ev` on `action` | Description |
|---|---|
| `ev:msg()` | the message name |
| `ev:sender()` | the sending [Widget](ui/widget.md) |
| `ev:args()` | a 1-based array snapshot of the raw protocol arguments, in the units the wire carries; a coordinate is `{x=, y=}` |
| `ev:position(i)` | argument `i` as a [Position](world.md#the-position-type); throws when that argument is not a coordinate |
| `ev:pixel(i)` | argument `i` as `{x=, y=}` design pixels in the sending widget's own space; throws when that argument is not a coordinate |
| `ev:preventDefault()` | cancel the send |
| `ev:resend()` | re-send the original arguments verbatim; implies `preventDefault` |
| `ev:send(t)` | send a new argument table; implies `preventDefault` |

A coordinate argument is in one of two spaces and nothing in its shape says which: a `click` carries the
press point at 1 and the destination in the world at 2, both `{x=, y=}`. Name the space at the index you
mean, and each verb throws naming the other on an index holding anything else. `ev:args()` stays raw,
because `resend` and `send` round-trip through it to the server.

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
`take` · `transfer`. An `action` key is **not** in the closed set above: any string is accepted, because a
message name is protocol the server can introduce, and refusing an unknown one would refuse a legitimate
one tomorrow. Two handlers on one `msg` both run; either one calling `preventDefault` cancels the send.

## Filtering an inbound update

`hafen.event():message():on(msg, fn)` is the inbound mirror: it fires when a server update `msg` is about
to be applied to a widget.

| `ev` on `message` | Description |
|---|---|
| `ev:msg()` | the message name |
| `ev:target()` | the receiving [Widget](ui/widget.md) |
| `ev:args()` | a 1-based array snapshot of the raw protocol arguments, in the units the wire carries |
| `ev:position(i)` | argument `i` as a [Position](world.md#the-position-type), as on `action` above |
| `ev:pixel(i)` | argument `i` as `{x=, y=}` design pixels in the receiving widget's own space |
| `ev:preventDefault()` | **swallow** the update, so the widget never applies it |
| `ev:rewrite(t)` | apply the update with new arguments |

`preventDefault` wins over `rewrite` if both are called. Common `msg` names: `set` · `add` · `del`.

```lua
-- freeze the HUD meter bars by swallowing their updates:
hafen.event():message():on("set", function(ev)
  if frozen and ev:target():type() == "IMeter" then ev:preventDefault() end
end)
```

Like `action`, a `message` key is open: any string is accepted and may never fire.

## What is deliberately not an event

Data that changes only on an explicit, infrequent player action has no change event: available skills,
credos, lore, crafting recipes, combat schools, minimap icon categories, movement speed. Read those on
demand, from their own section's verbs.

## See also

- [data types](types.md) — what `:info()` copies out of a payload, shape by shape
- [`hafen.timer`](timer.md) — for what the bus cannot tell you: polling on your own schedule
- [the Widget object](ui/widget.md) — subscribing on a widget you hold, and the mouse and its grab
- [conventions](conventions.md#threading) — why a handler must not block
