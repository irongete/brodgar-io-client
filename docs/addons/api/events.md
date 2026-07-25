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
| `GobAdded` | [`Gob`](types.md#gob) | a game object enters the world/view |
| `GobRemoved` | [`Gob`](types.md#gob) | a game object leaves |

Prefer these over scanning [`hafen.world.gobs`](world.md) every frame.

### Character & status *(widget-tree backed)*

| Event | Payload | Fires |
|---|---|---|
| `VitalsChanged` | [`Vitals`](types.md#vitals) | an hp/stamina/energy bar changes |
| `BuffAdded` | [`Buff`](types.md#buff) | a buff/debuff appears |
| `BuffRemoved` | [`Buff`](types.md#buff) | a buff/debuff goes away |
| `BuffChanged` | [`Buff`](types.md#buff) | a buff's content updates |
| `FepChanged` | [`food`](types.md#food) | FEP or hunger changes |
| `StudyChanged` | [`StudySlot`](types.md#studyslot)`[]` | the study slots change (add/remove/resolve) |
| `EquipChanged` | [`Item`](types.md#item)`[]` | worn equipment changes |
| `ActionbarChanged` | `n` (number) | action-bar slot `n` is set/cleared/changed |
| `WoundChanged` | [`Wound`](types.md#wound)`[]` | a wound is added/healed or its severity changes |

### Roster, quests, markers

| Event | Payload | Fires |
|---|---|---|
| `KinChanged` | [`KinEntry`](types.md#kinentry)`[]` | a kin is added/removed/edited or flips online/offline |
| `QuestAdded` | [`Quest`](types.md#quest--condition) | a new active quest appears |
| `QuestDone` | [`Quest`](types.md#quest--condition) | an active quest is completed or failed |
| `MarkersChanged` | `{ count = number }` | a map marker is added or removed |

### World ghosts

| Event | Payload | Fires |
|---|---|---|
| `GhostClicked` | `{ ghost, button, x, y }` | a **clickable** [ghost](ghost.md) of *your* addon is clicked |

`GhostClicked` is **owner-scoped** — it fires only to the addon that owns the clicked ghost (a ghost is
private to its addon, so its handle never leaks to others), unlike the world/roster events above which
broadcast to everyone. `ghost` = the [ghost handle](ghost.md#ghost-handle); `button` = 1 (left) / 3
(right); `x, y` = the world point the click resolved to. The click is **consumed** (no server click, no
character walk) — see [`hafen.ghost`](ghost.md#clickability--the-ghostclicked-event-v2). A ghost fires
this only while [`clickable`](ghost.md); a non-clickable ghost is click-through and never fires it.

> For `*Changed` list events (`StudyChanged`, `EquipChanged`, `KinChanged`, `WoundChanged`) the payload
> is the **full new list**. Read the initial state once with the section's `list()`/`slots()` verb,
> then listen for deltas.

There is deliberately **no** change event for data that only changes on explicit, infrequent player
action — available skills, credos, lore, crafting recipes, combat schools, radar categories. Read
those on demand.
