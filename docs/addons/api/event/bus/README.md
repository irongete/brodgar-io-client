# hafen.event: the catalogue

Every event the client fires on the bus, and what each one hands your handler. Subscribe with
[`hafen.event():on(key, fn)`](../README.md#subscribe), which is where the rules for a subscription are; the
pages below are the closed set of keys it accepts, one family to a page.

## The pages

| Page | What it holds |
|---|---|
| [your addon and the sessions](lifecycle.md) | your addon being loaded, ticked and disabled, and a character connecting, reaching the world, taking the screen and ending |
| [the world](world.md) | a game object coming and going, what is attached to one, and a click on an entity of your own |
| [the character and the rosters](character.md) | the meters, buffs, food, study, equipment, action bar and wounds, the kin roster, the quests, the map's pins, and the radial menu |
| [the chat](chat.md) | a channel appearing, going away or taking the tab, and a line landing in one |

## Whose character it was

Most of the events below are **one character's** — the meters, buffs, food, study slots, equipment, action
bar, wounds, roster, quests, radial menu, chat channels and the lines in them. Five characters' meters are
five different facts, so five firings are right, and each of those events hands your handler the
[`Session`](../../session.md) it was about as its **last** argument: a `MeterChanged` handler written
`function(m, s)` reads the bar that moved and the character it belongs to, and `s:user()` is the account it
is on. There is a [worked one](../../../guides/events-and-timers.md) in the guide.

Last, and not first, so a handler that does not care which character an event came from takes no second
parameter and reads exactly as it did — Lua drops an argument the function did not declare.

The rest carry no session, and each group has its own reason:

| Events | Why they carry none |
|---|---|
| `GobAdded`, `GobRemoved`, `GobOverlayAdded`, `GobOverlayRemoved`, `GobSdtChanged` | a game object is the world's rather than a character's, and each of these fires **once** for it — see [the world](world.md) |
| `SessionAdded`, `SessionEnteredWorld`, `SessionSelected`, `SessionRemoved` | the session **is** the payload |
| `Load`, `Update`, `Disable` | your addon's own, and there is one of it however many characters are up |
| `MarkerChanged` | the recorded map is one database for the client |
| `GhostClicked`, `SpriteClicked`, `ObjectClicked` | a thing you stood in the world stands in it once, for whichever character looks at it |

## Widgets appearing and disappearing

A widget is not a global fact either, so there is no `WidgetCreated` event. You say *which* widget you
care about, with the same [selector](../../ui/selectors.md) a lookup uses:

```lua
hafen.session():current():ui():on("window[title=Cupboard]", "Added", function(w)
  hafen.log():write(w:items():count() .. " items")
end)
```

`fn` receives the [Widget](../../ui/widget.md) itself, and **`Added` also covers what is already open**,
because registering scans the live tree — so an addon reloaded with the window up still sees it. See
[watching for a widget](../../ui/replace.md#watching-for-a-widget) for the two rules that matter: neither
event is about *visibility*, and at `Removed` the widget is a key to match, not something to read.

## What is deliberately not an event

Data that changes only on an explicit, infrequent player action has no change event: available skills,
credos, lore, crafting recipes, combat schools, minimap icon categories, movement speed. Read those on
demand, from their own section's verbs.

There is no wildcard either: `hafen.event():on("*", fn)` throws. Each key on these pages hands your handler
the **fact itself** — a [Gob](../../gob.md), a [`Meter`](../../meter.md), a [`Session`](../../session.md), a
list — so a handler for all of them would have nothing to name the key it was fired on. `*` is every message
on [a message stream](../streams.md) instead, where the key set is open and the names are the server's to
invent.

## See also

- [`hafen.event()`](../README.md) — subscribing, and why the key set is closed
- [the message streams](../streams.md) — the two open-keyed doors, for a message rather than a fact
- [data types](../../types/README.md) — what `:info()` copies out of a payload, shape by shape
- [`hafen.session`](../../session.md) — the payload the session family hands you, and the collection of the rest
- [`hafen.timer`](../../timer.md) — for what the bus cannot tell you: polling on your own schedule
- [when your code runs](../../../runtime.md) — the whole life of an addon, of which these are the moments
