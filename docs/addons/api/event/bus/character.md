# hafen.event: the character and the rosters

What one character's own HUD reports: the bars and buffs, what it has eaten and is learning, what it wears
and what it has on the hotbar, its wounds, the people it knows, the quests it carries, the pins on its map,
and the radial menu it has open. Every key here hands your handler the thing it is about and that
character's [`Session`](../../session.md) last, bar `MarkerChanged`: the recorded map is one database for
the client rather than one character's, so it carries none. That last argument is `nil` where the
client cannot name the login the widget stands in — a handler that took the parameter guards it rather
than being handed a `Session` that answers about nobody. Everything here is part of
[the catalogue](README.md), so `hafen.event():on(key, fn)` is the door.

## Character and status

These come from the HUD's own widgets, so they start once the HUD is up.

| Event | Payload | Fires |
|---|---|---|
| `MeterAdded` | [`Meter`](../../meter.md) | a HUD meter bar appears |
| `MeterRemoved` | [`Meter`](../../meter.md) | a HUD meter bar goes away — the object still reads, `:exists()` is false |
| `MeterChanged` | [`Meter`](../../meter.md) | a meter bar's value or colour changes |
| `BuffAdded` | [`Buff`](../../buff.md) | a buff appears |
| `BuffRemoved` | [`Buff`](../../buff.md) | a buff goes away — the object still reads, `:exists()` is false |
| `BuffChanged` | [`Buff`](../../buff.md) | a buff's content updates |
| `FepChanged` | [`Food`](../../char.md#food) | FEP or hunger changes |
| `StudyChanged` | [`StudySlot`](../../study.md#a-slot)`[]` | the study slots change: an add, a removal, or data resolving |
| `EquipChanged` | [`Item`](../../ui/items.md#the-item-object)`[]` | worn equipment changes |
| `ActionbarChanged` | [`Slot`](../../actionbar.md) | an action-bar slot is set, cleared or changed |
| `WoundChanged` | [`Wound`](../../wound.md#a-wound)`[]` | a wound is added or healed, or its severity changes |

Items entering or leaving a **container** are not on this bus: a chest is not a global fact, so you
subscribe to the container itself with
[`widget:on("ItemAdded"/"ItemRemoved"/"Removed", fn)`](../../ui/items.md#the-container-lifecycle).
`EquipChanged` stays global because your worn gear is one fixed surface.

`ActionbarChanged` hands you the **changed slot** as a live [`Slot` object](../../actionbar.md) — the same
interned object `s:actionbar():get(n)` returns, so `payload` and
`s:actionbar():get(payload:index())` are one object and you can key a table by it. `slot:index()` is
its 1-based position, and `slot:wire()` the raw number the server carries. It fires on a set, a clear, a drag, or when a slot's data resolves, and
**not** on `:cooldown()` ticking, which would fire every frame — read the cooldown live off the object
instead. At login the occupied slots stream in as a burst, one fire each.

A slot [held](../../actionbar.md#hold-a-slot-unprotected) for one of your own menu entries fires it on
**both edges**: once when the hold takes the slot, and once when it ends and the server's own content comes
back. The payload is the same `Slot`, and while the hold is on it `slot:res()` is the entry's identity.

> For the list events — `StudyChanged`, `EquipChanged`, `KinChanged`, `WoundChanged` — the payload is
> the **full new list**, not a delta. Read the initial state once with the section's own `:list()`
> verb, then listen.

## Roster, quests, markers

| Event | Payload | Fires |
|---|---|---|
| `KinChanged` | [`Kin`](../../kin.md)`[]` | a kin is added, removed or edited, or flips online or offline |
| `QuestAdded` | [`Quest`](../../quest.md#a-quest) | a new active quest appears |
| `QuestCompleted` | [`Quest`](../../quest.md#a-quest) | an active quest is completed |
| `QuestFailed` | [`Quest`](../../quest.md#a-quest) | an active quest is failed |
| `MarkerChanged` | the [marker collection](../../map/markers.md) | a map marker is added or removed |

`KinChanged` hands you the **whole roster** as live [`Kin` objects](../../kin.md), in Kin-window sort order —
the same interned objects `s:kin():list()` returns, so `payload[1]` and
`s:kin():get(payload[1]:id())` are one object and you can key a table by it. It tells you *that* the
roster changed, not *what* changed: keep your own map of the last state if you want to name who just came
online, and key it **by the `Kin` itself** rather than by `:name()`, so a rename does not read as one kin
leaving and another arriving.
[`kin:info()`](../../types/world.md#kinentry) is there when you want a plain table instead.

**The outcome is the key, not a field to check.** `QuestCompleted` fires when the quest is done and
`QuestFailed` when it is failed, so a handler that only cares about success is one subscription and no
`if`. Subscribe to both when you want either. A finished status the client does not recognise fires
neither — it does not guess.

The three quest events hand you the [`Quest`](../../quest.md#a-quest) itself, which matters most on a
completion: it fires *because* the status changed, so a handler that keeps the object goes on reading it —
including `q:status()`, which is the field the event is about.

## The radial menu

| Event | Payload | Fires |
|---|---|---|
| `FlowerMenuAdded` | [`Petal`](../../flowermenu.md#a-petal)`[]` — in ring order | a right-click puts up a radial menu |
| `FlowerMenuRemoved` | `string` \| nil — the label picked | that menu goes away |

**Every `FlowerMenuAdded` is followed by exactly one `FlowerMenuRemoved`**, whether you picked a petal,
pressed Esc, clicked away, or the menu died under you; the payload is `nil` for everything but a pick. Both
cover the menus the client puts up itself, such as the Kin window's, as well as the server's. Read the ring
from the payload or from [`s:flowermenu()`](../../flowermenu.md), which is the menu one character has open
and also names the object it was opened on — and `s` is the session the event carries, so nothing has to be
looked up. A ring goes up on the character the pointer is on, and it stays up, and readable, if you tab
away.

## See also

- [the catalogue](README.md) — the other families, and whose character an event was
- [`s:char()`](../../char.md) — the sheet these keys report changes to, read on demand
- [`s:kin()`](../../kin.md) — the roster `KinChanged` hands you whole
- [`s:actionbar()`](../../actionbar.md) — the hotbar, and the hold that fires on both edges
- [data types](../../types/README.md) — what `:info()` copies out of each of these payloads
