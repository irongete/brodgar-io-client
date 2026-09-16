# hafen.event: The Catalogue

Every event the client fires on the bus and what each hands your handler, one family to a page. Subscribe with [`hafen.event():on(key, fn)`](../README.md#subscribe); the keys are a closed set.

```lua
hafen.event():on("MeterChanged", function(meter, session)
  hafen.log():write(session:user() .. ": " .. tostring(meter:res()))
end)
```

---

## The pages

| Page | Holds |
|---|---|
| [Your addon and the sessions](lifecycle.md) | Your addon being loaded, ticked and disabled; a character connecting, reaching the world, taking the screen and ending. |
| [The world](world.md) | A game object coming and going, what is attached to one, a click on an entity of your own. |
| [The character and the rosters](character.md) | Meters, buffs, food, study, equipment, action bar, wounds, the kin roster, quests, the map's pins, the radial menu. |
| [The chat](chat.md) | A channel appearing, going away or taking the tab, and a line landing in one. |

## Whose character it was

Most events are one character's (meters, buffs, food, study slots, equipment, action bar, wounds, roster, quests, radial menu, chat channels and lines). Each of those hands your handler the [`Session`](../../session.md) it was about as its last argument: `function(meter, session)` reads the bar that moved and the character it belongs to, `session:user()` the account. Last, not first, so a handler that does not care declares no second parameter. There is a [worked example](../../../guides/events-and-timers.md) in the guide.

| Events without a session | Why |
|---|---|
| `GobAdded`, `GobRemoved`, `GobOverlayAdded`, `GobOverlayRemoved`, `GobSdtChanged` | A game object is the world's, and each fires once for it ([the world](world.md)). |
| `SessionAdded`, `SessionEnteredWorld`, `SessionSelected`, `SessionRemoved` | The session is the payload. |
| `Load`, `Update`, `Disable` | Your addon's own; one of it however many characters are up. |
| `MarkerChanged` | The recorded map is one database for the client. |
| `GhostClicked`, `SpriteClicked`, `ObjectClicked`, `PatchClicked` | A thing you put in the world is in it once, for whichever character looks at it. |

## Widgets appearing and disappearing

A widget is not a global fact, so there is no `WidgetCreated`. Name the widget with the [selector](../../ui/selectors.md) a lookup uses:

```lua
hafen.session():current():ui():on("window[title=Cupboard]", "Added", function(window)
  hafen.log():write(window:items():count() .. " items")
end)
```

`fn` receives the [Widget](../../ui/widget.md). `Added` also covers what is already open, since registering scans the live tree, so an addon reloaded with the window up still sees it. [Watching for a widget](../../ui/replace.md#watching-for-a-widget) has the two rules: neither event is about visibility, and at `Removed` the widget is a key to match, not something to read.

## Deliberately not an event

| Absent | Reason |
|---|---|
| Skills, credos, lore, crafting recipes, combat schools, minimap icon categories, movement speed | Change only on an explicit, infrequent player action: read on demand from their own section's verbs. |
| `hafen.event():on("*", fn)` | Throws. Each bus key hands the fact itself (a [Gob](../../gob.md), a [`Meter`](../../meter.md), a [`Session`](../../session.md), a list), so a handler for all of them could not name its key. `*` is every message on [a message stream](../streams.md), where the key set is open. |

---

## See Also

- [`hafen.event()`](../README.md) — subscribing, and why the key set is closed.
- [The message streams](../streams.md) — the two open-keyed doors, for a message rather than a fact.
- [Data types](../../types/README.md) — what `:info()` copies out of a payload, shape by shape.
- [`hafen.session`](../../session.md) — the payload the session family hands you.
- [`hafen.timer`](../../timer.md) — polling on your own schedule.
- [When your code runs](../../../runtime.md) — the life of an addon, of which these are the moments.
