# hafen.events — the event bus

Subscribe to client events. Handlers run on the UI thread.

| Function | Returns | Description |
|---|---|---|
| `hafen.events.on(name, fn)` | subscription handle | run `fn(...)` each time event `name` fires |

The returned handle has one method:

| Method | Description |
|---|---|
| `sub:off()` | unsubscribe (also done automatically on reload/disable) |

```lua
local sub = hafen.events.on("GobAdded", function(gob)
  hafen.log("appeared: " .. (gob.name or "?"))
end)
-- later:
sub:off()
```

Subscribe once (e.g. in the file body or `OnLoad`); the subscription is owned by your addon and is
released automatically when it is reloaded or disabled. A handler that errors is isolated — it won't
break other addons or the client.

---

## Event catalogue

### Lifecycle

| Event | Payload | Fires |
|---|---|---|
| `OnLoad` | — | once, when the addon is loaded (before entering the world) |
| `OnEnterWorld` | — | each time you enter the world (login, and re-fired on `:reload` while in-world) |
| `OnUpdate` | `dt` (number) | every frame; `dt` = seconds since the last frame |
| `OnDisable` | — | when the addon is disabled, reloaded, or the session ends |

`OnEnterWorld` fires once the HUD exists, but much character-sheet data still streams in for a few
seconds afterward (see [conventions](conventions.md#missing-data-returns-nil)). Keep `OnUpdate`
handlers cheap — they run on the UI thread every frame.

### World

| Event | Payload | Fires |
|---|---|---|
| `GobAdded` | [Gob](gob.md) | a game object enters the world/view |
| `GobRemoved` | [Gob](gob.md) | a game object leaves |

Prefer these over scanning [`hafen.world.gobs`](world.md) every frame.

The payload is a live [Gob object](gob.md). On `GobRemoved` the gob is **already gone**, so only
`gob:id()` answers there — if you need its name, index it on `GobAdded`.

### Character & status *(widget-tree backed)*

| Event | Payload | Fires |
|---|---|---|
| `MeterAdded` | [`Meter`](meters.md) | a HUD meter bar appears |
| `MeterRemoved` | [`Meter`](meters.md) | a HUD meter bar goes away — the object still reads, `:exists()` is false |
| `MeterChanged` | [`Meter`](meters.md) | a HUD meter bar's value or colour changes |
| `BuffAdded` | [`Buff`](buffs.md) | a buff appears |
| `BuffRemoved` | [`Buff`](buffs.md) | a buff goes away — the object still reads, `:exists()` is false |
| `BuffChanged` | [`Buff`](buffs.md) | a buff's content updates |
| `FepChanged` | [`food`](types.md#food) | FEP or hunger changes |
| `StudyChanged` | [`StudySlot`](types.md#studyslot)`[]` | the study slots change (add/remove/resolve) |
| `EquipChanged` | [`Item`](types.md#item)`[]` | worn equipment changes |
| `ActionbarChanged` | [`Slot`](actionbar.md) | an action-bar slot is set/cleared/changed |
| `WoundChanged` | [`Wound`](types.md#wound)`[]` | a wound is added/healed or its severity changes |

Items entering or leaving a **container** are not on this bus — a chest is not a global fact, so you
subscribe to the container itself:
[`widget:onItemAdded/:onItemRemoved/:onDestroy`](ui.md#the-container-lifecycle). `EquipChanged` above
stays global because your worn gear is one fixed surface.

### Roster, quests, markers

| Event | Payload | Fires |
|---|---|---|
| `KinChanged` | [`Kin`](kin.md)`[]` | a kin is added/removed/edited or flips online/offline |
| `QuestAdded` | [`Quest`](types.md#quest--condition) | a new active quest appears |
| `QuestDone` | [`Quest`](types.md#quest--condition) | an active quest is completed or failed |
| `MarkersChanged` | `{ count = number }` | a map marker is added or removed |

`KinChanged` hands you the **whole roster** as live [`Kin` objects](kin.md), in Kin-window sort order —
the same interned objects `hafen.kin()` returns, so `payload[1] == hafen.kin(payload[1]:id())` and you can
key a table by one. It tells you *that* the roster changed, not *what* changed: keep your own map of the
last state if you want to name who just came online — key it **by the `Kin` itself**, not by `:name()`,
so a rename doesn't read as one kin leaving and another arriving. `kin:info()` is there when you want a
plain-table [snapshot](types.md#kinentry) instead.

`ActionbarChanged` hands you the **changed slot** as a live [`Slot` object](actionbar.md) — the same interned
object `hafen.actionbar(n)` returns, so `payload == hafen.actionbar(payload:index())` and you can key a table
by one. `slot:index()` is the raw 0-based game index. It fires on a set/clear/drag or when a slot's data
resolves — not on `:cooldown()` ticking, which would fire every frame; read the cooldown live off the object.
At login the occupied slots stream in as a burst, one fire each.

### World ghosts & sprites

| Event | Payload | Fires |
|---|---|---|
| `GhostClicked` | `{ ghost, button, x, y }` | a **clickable** [ghost](ghost.md) of *your* addon is clicked |
| `SpriteClicked` | `{ sprite, button, x, y }` | a **clickable** fixed [sprite](render.md#clickability--the-spriteclicked-event) of *your* addon is clicked |
| `ObjectClicked` | `{ object, button, x, y }` | a **clickable** [glTF object](render.md#clickability--the-objectclicked-event) of *your* addon is clicked |

All three are **owner-scoped** — they fire only to the addon that owns the clicked entity (a ghost/sprite/object
is private to its addon, so its handle never leaks to others), unlike the world/roster events above which
broadcast to everyone. `ghost`/`sprite`/`object` = the clicked [handle](render.md#sprite-handle); `button` = 1
(left) / 3 (right); `x, y` = the world point the click resolved to. The click is **consumed** (no server
click, no character walk) — see [`hafen.ghost`](ghost.md#clickability--the-ghostclicked-event-v2) /
[`hafen.render`](render.md#clickability--the-spriteclicked-event). An entity fires this only while
**clickable**; a non-clickable one is click-through and never fires it. (A **billboard** sprite has no
world mesh, so it is never picked — only ghosts and **fixed** sprites fire these events.)

> For `*Changed` list events (`StudyChanged`, `EquipChanged`, `KinChanged`, `WoundChanged`) the payload
> is the **full new list**. Read the initial state once with the section's `list()`/`slots()` verb,
> then listen for deltas.

There is deliberately **no** change event for data that only changes on explicit, infrequent player
action — available skills, credos, lore, crafting recipes, combat schools, radar categories. Read
those on demand.
