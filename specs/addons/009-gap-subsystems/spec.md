# 009-gap-subsystems — Spec

## What & why
The spec-vs-client audit found whole client subsystems the API didn't cover. This feature closes
every **read** gap, one subsystem per task, each with the by-then-standard recipe: locate a
public widget/registry → snapshot with the canonical filter (nil / name-substring / predicate)
→ optional change event (uimsg-driven or poll-driven adapter) → `hello` regression coverage.
Surfaces: map **markers**, **skills/credos/lore** completion, **radar** icon categories (first
mutator), **slash commands**, **kin**, **speed**, **craft**, **quests**, **wounds**, **fight**
(combat-school builder). Contract: [../API-REFERENCE.md](../API-REFERENCE.md) "Gap subsystems".

## Acceptance criteria (verified in-game)
- [x] `hafen.markers`: list/nearest/add/remove over the client-side `MapFile` DB — markers
      stored as **segment id + tile coord** (the persistent anchor; `x,y` only in your current
      segment), `MarkersChanged` via the `markerseq` poll; `hello`'s Ctrl+Shift+M pin appears
      on map+minimap, `OnDisable` cleans it up.
- [x] `hafen.char.skillsAvailable()/credos()/experiences()` complete the Lore & Skills window
      (buyable skills with LP cost, acquired/available/pursuing credos with level+quest
      progress, lore entries with score).
- [x] `hafen.radar.categories/setVisible/setNotify` over `GobIcon.Settings` (~400 categories
      read correctly in-game): live + persisted (dsave), matched-count return, nil filter =
      all. Plus the REPL notice clamp fix (a 40 KB one-line result crashed the GPU texture).
- [x] `hafen.slash.register(name, fn)`: `:hello` with sub-command dispatch; the **C1
      reload-leak fix** — ONE engine-lifetime Console dispatcher per name routing to the live
      handler (reload swaps, never re-registers; removed → "no addon currently handles").
- [x] `hafen.kin.list/find` + `KinChanged` (uimsg-driven, snapshot diff — `serial`
      under-reports `chst`); who-came-online derivable from payload diffs.
- [x] `hafen.speed.get/max/name` (0..3, Speedget); `hafen.craft.current()` (recipe/inputs/
      outputs/qmod/tools, constraint-aware, `num=-1` faithful); `hafen.quests.list/selected`
      + `QuestAdded`/`QuestDone` (conditions only for the selected quest — client limitation);
      `hafen.wounds.list/has` + `WoundChanged` (tree via parentid/level; severity = the
      QuickInfo display string, poll-driven so nil→value resolution re-fires);
      `hafen.fight.maneuvers/deck/summary` (0-based deck slots, budget, saved-school state).

## Out of scope
- Every **write** verb (marker icons, radar mark/sounds, kin add/rename, speed.set,
  craft.make, fight edit) → [010-write-actions](../010-write-actions/spec.md).
- A custom map view (the map is a client-side subsystem, not a reskinnable widget);
  `SkillsChanged`/`CraftChanged`/`FightChanged` events (read-on-demand data).

## Context files
- `../API-REFERENCE.md` §"Gap subsystems"; `design/14-widget-tree-reads.md` (adapter patterns)
- `src/haven/MapFile.java`, `MiniMap.java` (sessloc), `GobIcon.java` (Settings),
  `Console.java`, `BuddyWnd.java`, `Speedget.java`, `Makewindow.java`, `QuestWnd.java`,
  `WoundWnd.java`, `FightWnd.java`, `SkillWnd.java`, `CharWnd.java` — the backings
- `src/io/brodgar/addon/WorldApi.java` (markers/radar), `CharApi.java` (char/kin/quests/
  wounds/fight adapters), `HookApi.java` (slash), `LuaSlashCommand.java`
- `docs/addons/api/markers.md`, `radar.md`, `console.md`, `kin.md`, `speed.md`, `craft.md`,
  `quests.md`, `wounds.md`, `fight.md`, `char.md` — shipped surface
- `../003-widget-tree-reads/` — the Locator/adapter mechanism every task here reuses
