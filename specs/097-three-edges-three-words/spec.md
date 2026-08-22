# 097 — three edges, three words

Discharges: `audit/ns-event.md` finding 2, and the "went away" row of `audit/02-naming.md` §S7.

**Neither is a row in `audit/INVENTORY.md`.** The sweep's 122 rows closed at 095; this finding was reported
by the audit and never made one, and the independent re-audit found it still standing. So the line above
cites the finding rather than an `A-` id — the first spec in the sweep that has to.

## What and why

The event vocabulary used **five word-pairs for three edges**. Across `BUS_KEYS`, the widget key sets and
the selector watch:

| Edge | Spellings in use before 097 |
|---|---|
| it appeared | `…Added` (Gob, Meter, Buff, Quest, Session, GobOverlay, Item) · `…Opened` (FlowerMenu) · `appear` (the watch) |
| it went | `…Removed` (Gob, Meter, Buff, GobOverlay, Item) · `…Destroyed` (Session) · `Destroy` (widget) · `…Closed` (FlowerMenu) · `…Done` (Quest) · `disappear` (the watch) |
| it changed | `…Changed` (nine subjects) · `Update` (the addon's frame) · `Tick` (a surface's frame) |

Six spellings for one edge. Every one of them costs a lookup and then **throws**, which is cheap and
self-correcting.

**One of them did not throw.** `QuestDone` fired for a quest that **failed** as well as one completed —
`CharApi.questDiff` fires it when a previously-active quest becomes *finished*, and finished means `done`
**or** `failed`. `q:status()` was the only thing that told them apart. So this reads correctly, runs on a
failure, and says nothing about it:

```lua
hafen.event():on("QuestDone", function(q)
  hafen.log():write("completed! " .. q:title())     -- also fires when you FAIL the quest
end)
```

That is the only silent wrong answer left anywhere in the bus, and it is the reason this feature exists.
The vocabulary is what it was hiding in.

Two more oddities fall out of the same rule: `MarkersChanged` was the only **plural** subject in the set,
and `Update`/`Tick` are the same edge one object apart — both fire once a frame and both hand the handler
the same `dt` ([AddonManager.java:1031](../../src/io/brodgar/addon/AddonManager.java),
[AddonWidget.java:181](../../src/io/brodgar/addon/AddonWidget.java)).

## The rule

**Three edges, three words** — `Added`, `Removed`, `Changed`, whatever the subject in front of them; the
subject is **singular**; and where an **outcome** differs, the key differs rather than the handler checking
a field afterwards. Written on `docs/addons/api/conventions.md`, which had no events rule at all.

## What changes

| Was | Is | Kind |
|---|---|---|
| `SessionDestroyed` | `SessionRemoved` | rename |
| `FlowerMenuOpened` | `FlowerMenuAdded` | rename |
| `FlowerMenuClosed` | `FlowerMenuRemoved` | rename — payload unchanged (the label picked, or nil) |
| `MarkersChanged` | `MarkerChanged` | rename — payload unchanged (the marker collection) |
| `QuestDone` | `QuestCompleted` **+** `QuestFailed` | **split** |
| `widget:on("Tick")` | `widget:on("Update")` | rename |
| `widget:on("Destroy")` | `widget:on("Removed")` | rename |
| `s:ui():on(sel, "appear")` | `…, "Added"` | rename — the last lower-case pair in the API |
| `s:ui():on(sel, "disappear")` | `…, "Removed"` | rename |

`SessionEnteredWorld` stays. It is the only key that is a sentence, and the audit notes that without
proposing a replacement — because entering the world is not one of the three edges and no shorter name says
it. `Load`/`Update`/`Disable` stay: they are the addon's own lifecycle, which the finding leaves alone.

## Why the quest word had to go rather than narrow

`CLAUDE.md`: **a rename is free and a reshape is not.** `Retired` keys on **names**. Keeping `QuestDone`
for the success half would leave every handler already written registered under a key that still resolves
and quietly stops running for half its firings — no error, no refusal, nothing to key a retirement on. So
the name is what moves, and `QuestDone` raises naming both replacements and saying what it used to do.

A finished status the client does not recognise now fires **neither** key. That is the same call
`LuaQuest.status()` already makes when it declines to name one, and it is narrower than the old behaviour,
which fired `QuestDone` for anything not active.

## Where it is caught

`Retired.eventKey` already existed and already carried the four pre-041 lifecycle keys; it fires **at the
door, before the key-set test**, so every old spelling is catchable. 097 adds eleven rows and two new doors:

- the **bus** already consulted it (`AddonManager.installHafen`).
- **`widget:on`** now consults it, after the staleness check and before the key set — "a Button has no
  event 'Destroy'" is true and useless.
- **`s:ui():on(sel, event, fn)`** now consults it, before the event is decoded. It is a three-argument
  call, so its rows write their own opening rather than borrow an arity they have not got.
