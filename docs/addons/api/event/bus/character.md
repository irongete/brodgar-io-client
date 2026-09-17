# hafen.event: The Character and the Rosters

What one character's own HUD reports: bars and buffs, food and study, equipment and hotbar, wounds, kin, quests, map pins and the radial menu. Every key hands the thing it is about and that character's [`Session`](../../session.md) last, except `MarkerChanged` (the recorded map is one database for the client). Part of [the catalogue](README.md).

```lua
hafen.event():on("BuffAdded", function(buff, session)
  hafen.log():write(session:character() .. " gained " .. (buff:name() or buff:res()))
end)
```

---

| Rule | Detail |
|---|---|
| The session argument | `nil` where the client cannot name the login the widget stands in. A handler that took the parameter guards it. |
| Diff, one frame | The client marks the subject dirty when the server messages about it and re-reads once on the next step. A value that changes and reverts inside that frame fires nothing. State the server never messages about (a buff's meter running down against a clock) moves silently and is announced by the next message that arrives. Read such a number live off the object. |
| List keys | For `StudyChanged`, `EquipChanged`, `KinChanged`, `WoundChanged` the payload is the full new list, not a delta. Read the initial state once with the section's own `:list()`, then listen. A list key covers adds, removals and changes: `WoundChanged` fires for a wound appearing, healing and worsening. A buff arrives one at a time, and its edge is named. |

## Character and status

From the HUD's own widgets, so they start once the HUD is up. Attributes, learning points, weight, skills, credos and lore have no key: [`session:char()`](../../char.md) reads them after the action that changes them.

| Event | Payload | Fires |
|---|---|---|
| `MeterAdded` | [`Meter`](../../meter.md) | A HUD meter bar appears. |
| `MeterRemoved` | [`Meter`](../../meter.md) | A HUD meter bar goes away. The object still reads, `:exists()` is false. |
| `MeterChanged` | [`Meter`](../../meter.md) | A meter bar's value or colour changes. |
| `BuffAdded` | [`Buff`](../../buff.md) | A buff appears. |
| `BuffRemoved` | [`Buff`](../../buff.md) | A buff goes away. The object still reads, `:exists()` is false. |
| `BuffChanged` | [`Buff`](../../buff.md) | A buff's content updates. |
| `FepChanged` | [`Food`](../../char.md#food) | FEP or hunger changes. |
| `StudyChanged` | [`StudySlot`](../../study.md#a-slot)`[]` | The study slots change: an add, a removal, or data resolving. |
| `EquipChanged` | [`Item`](../../ui/items.md#the-item-object)`[]` | Worn equipment changes. |
| `ActionbarChanged` | [`Slot`](../../actionbar.md) | An action-bar slot is set, cleared or changed. |
| `WoundChanged` | [`Wound`](../../wound.md#a-wound)`[]` | A wound is added or healed, or its severity changes. |

| Rule | Detail |
|---|---|
| Containers are not here | A chest is not a global fact: subscribe to the container with [`widget:on("ItemAdded"/"ItemRemoved"/"Removed", fn)`](../../ui/container.md). `EquipChanged` is global because worn gear is one fixed surface. |
| `ActionbarChanged` payload | The changed slot as the interned [`Slot`](../../actionbar.md) `session:actionbar():get(n)` returns, so `payload` and `session:actionbar():get(payload:index())` are one object. Fires on a set, a clear, a drag, or a slot's data resolving, not on `:cooldown()` ticking. At login the occupied slots stream in as a burst, one fire each. |
| A held slot fires on both edges | Once when the [hold](../../actionbar.md#hold-a-slot-unprotected) takes the slot, once when it ends and the server's content returns. While held, `slot:res()` is the entry's identity. |

## Roster, quests, markers

| Event | Payload | Fires |
|---|---|---|
| `KinChanged` | [`Kin`](../../kin.md)`[]` | A kin is added, removed or edited, or flips online or offline. |
| `QuestAdded` | [`Quest`](../../quest.md#a-quest) | A new active quest appears. |
| `QuestCompleted` | [`Quest`](../../quest.md#a-quest) | An active quest is completed. |
| `QuestFailed` | [`Quest`](../../quest.md#a-quest) | An active quest is failed. |
| `MarkerChanged` | The [marker collection](../../map/markers.md) | A map marker is added or removed. |

| Rule | Detail |
|---|---|
| `KinChanged` payload | The whole roster as the interned [`Kin`](../../kin.md) objects `session:kin():list()` returns, in Kin-window sort order, so `payload[1]` and `session:kin():get(payload[1]:id())` are one object. It says that the roster changed, not what. Keep your own map of the last state, keyed by the `Kin` itself rather than `:name()`. A rename then does not read as one kin leaving and another arriving. [`kin:info()`](../../types/world.md#kinentry) is the plain table. |
| The outcome is the key | `QuestCompleted` on done, `QuestFailed` on failed: a handler for success is one subscription and no `if`. Subscribe to both for either. A finished status the client does not recognise fires neither. |
| The quest payload | The [`Quest`](../../quest.md#a-quest) itself: a handler that keeps it goes on reading it, `quest:status()` included. |

## The radial menu

| Event | Payload | Fires |
|---|---|---|
| `FlowerMenuAdded` | [`Petal`](../../flowermenu.md#a-petal)`[]`, in ring order | A right-click puts up a radial menu. |
| `FlowerMenuRemoved` | `string \| nil`, the label picked | That menu goes away. |

Every `FlowerMenuAdded` is followed by exactly one `FlowerMenuRemoved` (a pick, Esc, a click away, a dropped connection). The payload is `nil` for everything but a pick. Both cover the client's own menus (the Kin window's) as well as the server's. Read the ring from the payload or from [`session:flowermenu()`](../../flowermenu.md), which also names the object it was opened on. `session` is the event's last argument. A ring goes up on the character the pointer is on and stays up, readable, if you tab away.

## Steam

| Event | Payload | Fires |
|---|---|---|
| `SteamStatsLoaded` | — | The Steam client has finished loading the player's stats and achievements, so [`hafen.steam()`](../../steam.md) reads them. |
| `AchievementUnlocked` | [`Achievement`](../../steam.md#the-achievement-object) | An achievement is stored as unlocked. |

Both are the Steam client's moments, not a character's. They fire once per client, whichever session is on screen, and not at all on a client started outside Steam.

---

## See Also

- [The catalogue](README.md) — the other families, and whose character an event was.
- [`session:char()`](../../char.md) — the sheet these keys report changes to, read on demand.
- [`session:kin()`](../../kin.md) — the roster `KinChanged` hands you whole.
- [`session:actionbar()`](../../actionbar.md) — the hotbar, and the hold that fires on both edges.
- [Data types](../../types/README.md) — what `:info()` copies out of each payload.
