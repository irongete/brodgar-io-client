# 172 — The combat schools

## What & why

`session:fight()` reads the deck builder (the character sheet's Martial Arts & Combat Schools tab) through three numbers on the summary: `activeSave()` (a 0-based slot), `saveCount()` and `deckSize()`. It cannot name, list, load or save a school. Its `deck()` lists only the filled hotkeys, with `card:exists()`. On the same page, the combat row lists all ten hotkeys with `:empty()`, `:get(n)`, `:index()` and `:wire()`, so one set of keys has two shapes.

This feature publishes the schools as a collection, shaped like the speed selector and the combat row: `school():current()` is the loaded school, and `school:load()` and `school:save()` press the tab's own buttons. `deck()` takes the combat row's shape, and the summary keeps only the action-point budget.

**Maintainer's ruling (2026-09-30, approved 2026-10-01):**
- `summary:activeSave()`, `summary:saveCount()` and `summary:deckSize()` are cut, with no alias and no announcement. Their places are `school():current()`, `school():count()` and `deck():count()`.
- `card:exists()` is cut, and its place is `card:empty()`.
- This breaks what v11.1-beta published, which is accepted.
- **The API edition moves to `1.4`**, for the new verbs. The generation does not move, and `manifest.md`'s sentence about the generation is left as it is.

## Acceptance criteria

1. `session:fight():school()` holds one `School` per saved-school slot the server gives that character.
   - `:get(n)` takes the 1-based position. `nil` answers any other number, `0` included, and before the tab has built. A string raises, naming `:find(filter)`.
   - A string filter matches a school's name.
   - `:current()` is the school the tab marks as loaded, which may be empty, and `nil` before the tab has built. `:current(x)` raises, naming `school:load()` once that verb exists *(172.3)*.

   *(172.1)*
2. A school answers:
   - `:index()` (1-based) and `:wire()` (0-based), always;
   - `:empty()`, true for an unused slot;
   - `:name()`, the name the tab paints, and `nil` when empty;
   - `:info()`, `{ name }`, and `nil` when empty.

   `School` objects are interned on character and position. *(172.1)*
3. The summary answers `:maxActions()`, `:used()`, `:exists()` and `:info()`, which is `{ maxact, used }`. `activeSave`, `saveCount` and `deckSize` raise as unknown verbs whose message lists what the summary answers. *(172.1, 172.2)*
4. `session:fight():deck()` holds every hotkey place of the layout.
   - `:get(n)` is hotkey `n`, and `nil` outside the layout, `0` included.
   - `card:index()` is `n`, and `card:wire()` is `n - 1`.
   - `card:empty()` replaces `card:exists()`, which raises as an unknown verb listing `:empty()`.
   - An empty card answers `nil` from every reader but `:index()`, `:wire()`, `:key()` and `:empty()`.
   - `card:info()` is `nil` when empty, and otherwise `{ key?, res?, name?, used }`, with no `slot`.

   *(172.2)*
5. Every verb of `School`, `DeckCard`, `Maneuver` and `FightSummary` refuses a surplus argument, naming the verb. *(172.1, 172.2)*
6. `school:load()` (key `fight.load`) sends what the tab's Load button sends: `load` and then `use`, with the school's `wire`. `school:save()` (key `fight.save`) sends what the Save button sends: `save`, carrying the slot, the name when the slot has one, and the tab's layout, and then `use`.
   - Both return the school.
   - Before anything is sent, both refuse when the tab has not built, and `:load()` refuses an empty school, naming `school:empty()`.
   - The outbound action stream sees every message, so a handler can cancel them.

   *(172.3)*
7. An addon declaring `api_version "1.4"` loads. `manifest.md` names what needs `1.4`, and every example manifest declares `"1.4"`. *(172.1)*
8. The pages are rewritten to criteria 1–7. `tools/docverbs.py` and `tools/refusalverbs.py` exit `0`. *(172.1, 172.2, 172.3)*

## Out of scope

- **Composing the deck**: dealing a manoeuvre into a hotkey (the tab's drag and `itemact`). A save carries whatever the tab holds.
- **Naming a school.** The tab's rename is a local edit that the next save carries. A name argument to `:save()` would be a design of its own. A save to an unused slot takes the name the server gives it.
- **A school event.** Schools change only on the player's own act, and are read on demand.
- **A school builder**: a second working copy beside the tab.

## Docs impact

**Pages written:**
- `docs/addons/api/`: `fight.md`, `types/fight.md`, `types/README.md`, `http.md`, `websocket.md`, `voice/README.md`.
- `docs/addons/`: `manifest.md`, `getting-started.md`, `guides/bundles.md`, `guides/libraries.md`, `guides/permissions.md`.
- `docs/client/character-sheet.md`.

**Derived impact set.** The command:

```bash
grep -rn -i -E "deck\(\)|DeckCard|card:|summary:|FightSummary|activeSave|saveCount|deckSize|nsave|usesave|school|api_version\": \"1\.3\"|\`1\.3\`" docs --include=*.md
```

It finds (excluding the study page's own summary):
- `fight.md:1,3,21,23,24,33,34,39,40,49,51,59–67,73–79,175`
- `types/fight.md:7,13,18,19`
- `types/README.md:45,49,56`
- `manifest.md:8,52,81`
- `getting-started.md:25,30,145`
- `guides/bundles.md:8`
- `guides/libraries.md:10,44`
- `http.md:25`, `websocket.md:23`, `voice/README.md:30`
- `client/character-sheet.md:3,14,38`

Still true as written, so discharged:
- `conventions.md:71`: the `card:index()`/`card:wire()` pair.
- `event/bus/README.md:51`: schools are read on demand.
- `session.md:37`.
- `ui/style/surfaces.md:148`.
- `client/README.md:59`.

Found by reading:
- `manifest.md:85–92`, the edition table.
- `guides/permissions.md:34–37,81`, the `fight.*` rows.

## Context files

- `DOCUMENTATION.md` — 1, 2, 3
- `src/haven/FightWnd.java` — 1, 3
- `src/io/brodgar/addon/CharApi.java` (`fight()`, `fightwnd`) — 1, 2
- `src/io/brodgar/addon/LuaSchool.java` — 1, 3
- `src/io/brodgar/addon/LuaWidget.java` (`monitor`) — 1, 3
- `src/io/brodgar/addon/LuaFightSummary.java` — 1, 2
- `src/io/brodgar/addon/LuaDeckCard.java`, `LuaManeuver.java`, `LuaCombatAction.java` — 2
- `src/io/brodgar/addon/LuaSpeed.java` — 1
- `src/io/brodgar/addon/Addon.java` (the fight caches) — 1
- `src/io/brodgar/addon/ApiVersion.java` — 1
- `src/io/brodgar/addon/LuaCollection.java`, `Args.java`, `Refusal.java` (`closedIndex`) — 1, 2, 3
- `src/io/brodgar/addon/Wire.java`, `Permission.java` — 3
- `tools/docverbs.py`, `tools/refusalverbs.py` — 1, 2, 3
- `docs/addons/api/fight.md` — 1, 2, 3
- `docs/addons/api/types/fight.md` — 1, 2
- `docs/addons/manifest.md` — 1, 2, 3
- `docs/addons/api/conventions.md` — 1, 2
- `docs/addons/api/types/README.md` — 1
- `docs/client/character-sheet.md` — 1, 3
- the example manifests under *Pages written* — 1
- `docs/addons/guides/permissions.md` — 3
- `docs/addons/api/event/streams.md` (outbound actions; read only) — 3
- `specs/170-live-fight/addons/170-live-fight.5/main.lua` (a suite that cancels its own sends; read only) — 3
