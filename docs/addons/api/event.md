# hafen.event: the event bus

Subscribe to something the client does, instead of polling for it every frame. `hafen.event()` is
**ungated**: subscribing observes, it changes nothing. Handlers run on the UI thread, so keep them
short.

```lua
local sub = hafen.event():on("GobAdded", function(gob)
  hafen.log():write("appeared: " .. (gob:name() or "?"))
end)
-- later:
sub:off()
```

## Subscribe

| Function | Returns | Description |
|---|---|---|
| `hafen.event():on(name, fn)` | subscription handle | run `fn(...)` each time the event `name` fires |

| Method | Description |
|---|---|
| `sub:off()` | unsubscribe; also done for you on reload or disable |

Subscribe once, in the file body or in `OnLoad`. The subscription is owned by your addon and released
when it is reloaded or disabled, so you never have to unsubscribe by hand. A name no event uses is
accepted and simply never fires — nothing validates it against the catalogue below. A handler that
errors is isolated: the error is logged and it breaks neither other addons nor the client.

## Lifecycle

| Event | Payload | Fires |
|---|---|---|
| `OnLoad` | — | once, when the addon is loaded, before entering the world |
| `OnEnterWorld` | — | each time you enter the world: login, and again on `:reload` while in-world |
| `OnUpdate` | `dt` (number) | every frame; `dt` is seconds since the last frame |
| `OnDisable` | — | when the addon is disabled or reloaded, or the session ends |

`OnEnterWorld` fires once the HUD exists, but much character-sheet data streams in for a few seconds
afterwards — see [missing data returns nil](conventions.md#missing-data-returns-nil). Keep `OnUpdate`
handlers cheap: they run on the UI thread on every frame.

## World

| Event | Payload | Fires |
|---|---|---|
| `GobAdded` | [Gob](gob.md) | a game object enters the world or your view |
| `GobRemoved` | [Gob](gob.md) | a game object leaves |
| `GobOverlayAdded` | `{ gob, key, native }` | something is attached to a game object — see [`gob:overlay()`](gob.md#overlays) |
| `GobOverlayRemoved` | `{ gob, key, native }` | something attached to a game object goes away |

Prefer these over scanning [`hafen.world():gob():list`](world.md) every frame. The payload is a live
[Gob object](gob.md). On `GobRemoved` the gob is **already gone**, so only `gob:id()` answers there; if
you need its name, index it on `GobAdded`.

### Overlays coming and going

`GobOverlayAdded` and `GobOverlayRemoved` cover both halves of what
[`gob:overlay()`](gob.md#overlays) reads. `native = false` is one **you** attached; `native = true` is one
the **game** put there (a lit fire's flame, a crop's growth stage), and `key` is then its resource name.

```lua
hafen.event():on("GobOverlayAdded", function(e)
  if e.native then hafen.log():write(e.gob:id() .. " now carries " .. e.key) end
end)
```

The rules below make these predictable:

- **Yours are private, the game's are public.** An overlay key belongs to your addon, so a `native = false`
  event goes **only** to the addon that attached it — a key another addon cannot read is a name it cannot
  act on. Native events broadcast, because a resource name means the same thing to everyone.
- **They arrive on the next frame**, not inside the `:add` itself — the game's own overlays arrive on
  loader threads, and both halves use one moment. A handler runs on the UI thread and reads the truth:
  the overlay is already there on an add, already gone on a removal.
- **Re-attaching under the same key fires both** — the removal, then the add. The key survives; the thing
  under it does not.
- **The game's overlays are counted by key.** Several of them may share one resource and collapse to one
  key, so a second one of that resource arriving is not an add — read
  [`ov:count()`](gob.md#overlays) for the multiplicity.

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
| `FepChanged` | [`Food`](types.md#food) | FEP or hunger changes |
| `StudyChanged` | [`StudySlot`](types.md#studyslot)`[]` | the study slots change: an add, a removal, or data resolving |
| `EquipChanged` | [`Item`](types.md#item)`[]` | worn equipment changes |
| `ActionbarChanged` | [`Slot`](actionbar.md) | an action-bar slot is set, cleared or changed |
| `WoundChanged` | [`Wound`](types.md#wound)`[]` | a wound is added or healed, or its severity changes |

Items entering or leaving a **container** are not on this bus: a chest is not a global fact, so you
subscribe to the container itself with
[`widget:onItemAdded`, `:onItemRemoved` and `:onDestroy`](ui/items.md#the-container-lifecycle).
`EquipChanged` stays global because your worn gear is one fixed surface.

`ActionbarChanged` hands you the **changed slot** as a live [`Slot` object](actionbar.md) — the same
interned object `hafen.actionbar(n)` returns, so `payload == hafen.actionbar(payload:index())` and you
can key a table by one. `slot:index()` is the raw 0-based game index. It fires on a set, a clear, a
drag, or when a slot's data resolves, and **not** on `:cooldown()` ticking, which would fire every
frame — read the cooldown live off the object instead. At login the occupied slots stream in as a
burst, one fire each.

> For the list events — `StudyChanged`, `EquipChanged`, `KinChanged`, `WoundChanged` — the payload is
> the **full new list**, not a delta. Read the initial state once with the section's own `list()` or
> `slots()` verb, then listen.

## Roster, quests, markers

| Event | Payload | Fires |
|---|---|---|
| `KinChanged` | [`Kin`](kin.md)`[]` | a kin is added, removed or edited, or flips online or offline |
| `QuestAdded` | [`Quest`](types.md#quest-and-condition) | a new active quest appears |
| `QuestDone` | [`Quest`](types.md#quest-and-condition) | an active quest is completed or failed |
| `MarkersChanged` | `{ count = number }` | a map marker is added or removed |

`KinChanged` hands you the **whole roster** as live [`Kin` objects](kin.md), in Kin-window sort order —
the same interned objects `hafen.kin()` returns, so `payload[1] == hafen.kin(payload[1]:id())` and you
can key a table by one. It tells you *that* the roster changed, not *what* changed: keep your own map
of the last state if you want to name who just came online, and key it **by the `Kin` itself** rather
than by `:name()`, so a rename does not read as one kin leaving and another arriving.
[`kin:info()`](types.md#kinentry) is there when you want a plain table instead.

## Widgets appearing and disappearing

A widget is not a global fact either, so there is no `WidgetCreated` event. You say *which* widget you
care about, with the same [selector](ui/selectors.md) a lookup uses:

```lua
hafen.ui.on("window[title=Cupboard]", "appear", function(w) hafen.log():write(#w:items() .. " items") end)
```

`fn` receives the [Widget](ui/widget.md) itself, and **`appear` also covers what is already open**,
because registering scans the live tree — so an addon reloaded with the window up still sees it. See
[watching for a widget](ui/replace.md#watching-for-a-widget) for the two rules that matter: neither
event is about *visibility*, and at `disappear` the widget is a key to match, not something to read.

## World ghosts and sprites

| Event | Payload | Fires |
|---|---|---|
| `GhostClicked` | `{ ghost, button, x, y }` | a **clickable** [ghost](ghost.md) of *your* addon is clicked |
| `SpriteClicked` | `{ sprite, button, x, y }` | a **clickable** fixed [sprite](render/sprites.md#clickability) of *your* addon is clicked |
| `ObjectClicked` | `{ object, button, x, y }` | a **clickable** [glTF object](render/models.md#clickability) of *your* addon is clicked |

All three are **owner-scoped**: they fire only to the addon that owns the clicked entity, unlike the
world and roster events above, which broadcast. That is because a ghost, sprite or object is private
to its addon and its handle never leaves it. `ghost`, `sprite` and `object` are the clicked
[handle](render/sprites.md#sprite-handle); `button` is 1 for left and 3 for right; `x, y` is the world
point the click resolved to. The click is **consumed** — no server click, no character walk. An entity
fires this only while clickable; a non-clickable one is click-through and silent, and a **billboard**
sprite has no world mesh, so it is never picked at all.

## What is deliberately not an event

Data that changes only on an explicit, infrequent player action has no change event: available skills,
credos, lore, crafting recipes, combat schools, radar categories, movement speed. Read those on
demand, from their own section's verbs.

## See also

- [data types](types.md) — the payload shapes the list events hand you
- [`hafen.timer`](timer.md) — for what the bus cannot tell you: polling on your own schedule
- [`hafen.hook`](hook.md) — intercepting client behaviour *before* it happens, and cancelling it
- [conventions](conventions.md#threading) — why a handler must not block
