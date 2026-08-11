# 009-gap-subsystems — Plan

> History: this work appears in git history and `learnings/` tagged **A1, A4, A2, A11, A6,
> A7, A8, A9-1, A9-2, A10** (the gap-subsystem series; build order per D-026). All ten tasks
> are zero-`haven`-edit.

## Approach (the per-subsystem recipe, and where each deviates)
- **Markers (A1)**: the map is a SEPARATE coordinate universe — the bridge is the minimap's
  live `sessloc` (`world↔segment`: `segTc = sessloc.tc + floor(world/tilesz)`, floor for
  negatives). Copy the marker list under `MapFile.lock.readLock()`, snapshot/filter OUTSIDE
  the lock (a Lua filter could call `markers.add`). Refs = session-scoped `IdentityHashMap`
  (facade-safe, P1). `markerseq` is the poll signal — but disk-loaded markers don't bump it
  (primed, not fired). Demo cleans up its own pin (a write to a shared persistent store).
- **Lore completion (A4)**: pure public reads off `SkillWnd` tabs (`nsk`, `credos`, `exps`);
  composite `credos()` table (one canonical read); `resTipName`/`resIdent` extracted as the
  shared resource-name helpers. No event — the data changes only on explicit player action.
- **Radar (A2)**: the first WRITE onto an existing client registry — "drive the settings
  window's model", not build a new one: flip `Setting.show/notify` + the same debounced
  `dsave()` the checkboxes call. Matched-count return; `hello` stays read-only (a persisting
  mutator in the harness would corrupt the user's real config).
- **Slash (A11)**: `Console.setscmd` has NO unregister (audit C1) → ONE engine-lifetime
  dispatcher per name routing to `slashHandlers` (live state); reload swaps the handler,
  teardown is identity-checked (a takeover by another addon is left alone); reserved/existing
  names refused.
- **Kin (A6)**: uimsg-driven adapter (`add`/`rm`/`upd`/`chst` ARE uimsgs — the opposite of
  buffs/study) with a full snapshot diff (`BuddyWnd.serial` under-reports `chst`); tri-state
  `online` exposed as boolean (one canonical way); `BuddyWnd.iterator()` copies under its own
  lock.
- **Speed (A7) / Craft (A8) / Fight (A10)**: read-on-demand, no event; Locator via
  `children(Class)` (Speedget/Makewindow have no named GameUI field) or the public `CharWnd`
  fields; "copy under `synchronized(ui)`, snapshot outside" for loader-swapped lists; craft
  exposes the **constraint** resource when a slot shows a category; deck slots are the raw
  0-based engine index (the future gated `use` takes the same).
- **Quests (A9-1)**: uimsg adapter + a **pure** `questDiff` (headless-testable semantics);
  already-finished history is recorded silently (no login spam); one `QuestDone` with
  `status` distinguishing done/failed. **Wounds (A9-2)**: poll-driven (severity is
  resource-info that resolves a beat after the row — a uimsg refresh would fire once with
  nil and never re-fire); tree via `parentid`/`level`; single `WoundChanged` (KinChanged
  analog).

## Files created / modified
- `src/io/brodgar/addon/AddonManager.java` (→ split homes `WorldApi`/`CharApi`/`HookApi`) —
  ten facades + `KinAdapter`/`QuestAdapter`/`WoundAdapter` + shared helpers
  (`resTipName`/`resIdent`, `matches`, `clampMsg`)
- `src/io/brodgar/addon/LuaSlashCommand.java` — new
- `addons/hello/` — v0.22.0→v0.31.0 (readMarkers/Lore/Radar/Kin/Speed/Craft/Quests/Wounds/
  Fight + `:hello` sub-commands craft/quest/wound/fight + Ctrl+Shift+M marker toggle)
- No `haven` edits in the whole series.

## Risks & gotchas hit (detail: learnings/gap-subsystems.md, testing-tooling.md)
- The REPL in-game notice renders ONE text texture — radar's ~40 KB single-line JSON blew
  `GL_MAX_TEXTURE_SIZE`; fixed at the choke point (`clampMsg`, 500 chars; terminal keeps full).
- `.class` literals don't trigger `<clinit>`, but reading a static does — several snapshot
  shapes are headless resource skips (Makewindow/FightWnd load fraktur), covered in-game.
- `public static final int` constants are inlined (JLS 13.1) → status helpers never load
  `Quest` headless.
- Change-detection DECISIONS extracted as pure functions (`questDiff`, `woundListEqual`,
  `kinListEqual`) — the headless-testable core of each adapter.

## Discarded alternatives
- A new radar registry — driving `GobIcon.Settings` gets the client's own minimap+persistence.
- Per-`hafen.slash` Console registrations — leaks/duplicates on every reload (C1).
- `BuddyWnd.serial` as the kin change signal — under-reports online flips.
- uimsg-driven wounds — misses the severity nil→value resolution.
