# Phase 1d (part 3) — Study/curiosity + skills

> **Status:** ✅ Implemented & **in-game verified**; compile (`ant hafen-client` → BUILD SUCCESSFUL),
> headless change-detection test (15/15) + LuaJ parse of the harness.
> **Design:** [specs/addons/14-widget-tree-reads.md](../../specs/addons/14-widget-tree-reads.md)
> (the mechanism — `StudyAdapter`/`SkillsAdapter` rows), [specs/addons/api-reference.md](../../specs/addons/api-reference.md)
> (`hafen.study`, `hafen.char.skills`), [specs/addons/code-map.md](../../specs/addons/code-map.md)
> (`CharWnd.sattr`/`skill`, `SAttrWnd`, `SkillWnd`, `resutil.Curiosity`).

The **third slice of Phase 1d**. It adds two more character-sheet surfaces on the
[1d-1 widget-tree mechanism](phase-1d1-vitals-widget-tree.md): the **study window** (curiosities)
via `hafen.study.*` + the `StudyChanged` event, and **known skills** via `hafen.char.skills()` /
`hafen.char.skill(name)`. Both read state bound to `GameUI` widget trees, not `Glob` (audit B1).

## Zero core edits — every read is public

Unlike 1d-1 (vitals needed the `AddonWidgets` accessor for a `protected` field) and 1d-2 (buffs
needed `buffDest`), **1d-3 touches no `haven` file**. Everything it reads is already public:

- **Study:** `CharWnd.sattr` (public `SAttrWnd`) → its `SAttrWnd.StudyInfo` child (public
  `children()` walk) → `StudyInfo.study` (public `Widget`, the study inventory) and
  `StudyInfo.texp`/`tw`/`tenc` (public totals). Each study item is a `GItem` (public `res`/`meter`/
  `info()`) carrying a `resutil.Curiosity` info (public `exp`/`mw`/`enc`/`time`).
- **Skills:** `CharWnd.skill` (public `SkillWnd`) → `skg.csk` (public `GridList.Group`) → `items`
  (public `List<Skill>`); `Skill.nm`/`res` are public.

So the only file that changed is `AddonManager.java` (the bridge). No `UI.java`, no `AddonWidgets`.
The 1d-1 inbound-`uimsg` tap is not even used here (see below).

## Study add/remove is structural → poll-driven (like buffs)

A curiosity being placed in / finishing in the study window is a **widget create/`cdestroy`** on the
study inventory, and study data (the `Curiosity` info) resolves a beat after the item appears — none
of that is a targeted `uimsg`. So `StudyAdapter` uses the **`poll()`** path (added in 1d-2 for
buffs), not the uimsg tap: each tick it re-reads `hafen.study.slots()` and fires `StudyChanged` only
when the snapshot differs from its cached one (change-detected, so it does not fire every frame).

```
curiosity added / removed / progresses  ─▶ [no uimsg]  ─▶ poll() diff ─▶ "StudyChanged"   [UI thread, per tick]
```

`interested()` returns `false` (nothing to tap); `refresh()` is a no-op; all the work is in `poll()`.

## `hafen.study.*` — the study window

```lua
for _, s in ipairs(hafen.study.slots()) do   -- array of curiosity snapshots
  -- s = { res, name, lp, attention, cost, time, progress? }
end
local sum = hafen.study.summary()            -- { lp, attention, cost } live totals, or nil
```

Study-slot snapshot:

| field | meaning |
|---|---|
| `res` | curiosity item resource name — stable identity (Loading-guarded) |
| `name` | item display name (`ItemInfo.Name`, Loading-guarded) |
| `lp` | learning points the curiosity grants (`Curiosity.exp`) |
| `attention` | mental weight / attention it consumes (`Curiosity.mw`) |
| `cost` | experience-point cost (`Curiosity.enc`) |
| `time` | **total** study time in seconds (`Curiosity.time`) |
| `progress` | `0..1` study progress from the item meter — **best-effort**, present only when the client tracks it |

- `lp`/`attention`/`cost`/`time` come from the `Curiosity` info, which needs the item resolved — so
  right after a curiosity appears only `res` may be set, then the rest streams in (another
  `StudyChanged`).
- **There is no true "time left"** — the client carries only the *total* study time (`time`), not a
  per-item countdown, so the API does not invent one. Use `progress` (when present) with `time`.

`summary()` is the live aggregate from `SAttrWnd.StudyInfo` (the "Study Report" the game itself
shows): `lp` = total learning points, `attention` = total mental weight used (compare with
`hafen.char.attr("int").comp`, the attention cap), `cost` = total experience cost.

### `StudyChanged`

```lua
hafen.events.on("StudyChanged", function(slots) end)   -- slots = same array as hafen.study.slots()
```

Fires when the study slots change — a curiosity added/finished, or its study data resolving as it
streams in after enter-world (a few fires at login). Change-detected on `res`/`name`/`lp`/`attention`/
`cost`/`time`/`progress` (positional), so a no-op re-read is suppressed.

## `hafen.char.skills()` / `skill(name)` — known skills

```lua
for _, sk in ipairs(hafen.char.skills()) do   -- array of KNOWN skills
  -- sk = { name, res }
end
hafen.char.skill("Cook")   -- bool: substring match on name OR res, over known skills
```

- `skills()` returns the character's **known** skills (`SkillWnd.skg.csk`), each `{name, res}` —
  `name` is the resource tooltip (falls back to the internal skill token), `res` is the resource
  name (Loading-guarded).
- `skill(name)` is a **substring** membership test over the known skills (name or resource),
  matching `hafen.buffs.has`. It is forgiving, not an exact match — document accordingly.
- The **available-to-learn** list (`nsk`) and **credos**/**experiences** (the other `SkillWnd` tabs)
  are deferred — the spec's core row is skills, and this keeps the slice focused.

## Streaming — read on a timer, not in `OnEnterWorld`

Like the 1c char/item data and 1d-1/1d-2 vitals/buffs/food, **the study window and skill list stream
in a beat after `OnEnterWorld`**: `hafen.study.slots()` is empty and `hafen.char.skills()` is empty
on the immediate read, then populate ~seconds later (the `SAttrWnd`/`SkillWnd` tabs are created hidden
at login but their contents arrive after enter-world). Read them off a timer/tick or off
`StudyChanged`, **not** synchronously in the `OnEnterWorld` handler.

## Try it from the `:lua` console

```
:lua hafen.study.slots()
:lua hafen.study.summary()
:lua hafen.char.skills()
:lua hafen.char.skill("Cook")
```

Before the widgets exist these are `[]` / `null` / `[]` / `false`, never an error.

## The `hello` example (`addons/hello/main.lua`)

A new `readStudy(tag)` helper logs `hafen.study.slots()` (count + first curiosity + `summary()`
totals) and `hafen.char.skills()` (count + first known skill) in both the `[now]` pass (usually
empty — streaming) and the `[+3s]` pass (populated). A throttled `StudyChanged` subscription logs the
first few. It stays our standing regression harness — one login re-checks Phase 0 / 1a / 1b / 1c /
1d-1 / 1d-2 **and** this slice. Bumped to **v0.8.0**.

## How to test in-game

**Java changed → full rebuild + restart is required** (the JVM does not hot-reload classes):

```
ant run
```

After `[hello] entered the world`, expect (a beat after enter-world, at `[+3s]` and via `StudyChanged`):

- `[hello] [+3s] study=<n> slot(s), first=<curiosity>, totals=lp=<..> att=<..> cost=<..>` — `study=0`
  if nothing is being studied,
- `[hello] [+3s] skills=<n> known, first=<skill>`,
- `[hello] StudyChanged: <n> slot(s), first=<curiosity>` — as the study data streams in / when you
  add or finish a curiosity.

Then poke it live: `:lua hafen.study.slots()`, `:lua hafen.char.skills()`,
`:lua hafen.char.skill("<a skill you have>")`. To see `StudyChanged` fire on demand: drop a curiosity
into (or pull one out of) the study window.

## Verified in-game ✅

A character with three study slots + 40 known skills:

- `StudyChanged` fired as the data streamed in (`0 → 1 → 3` slots, names resolving), then
  `[+3s] study=3 slot(s), first=A Bond of Blood & Soil, totals=lp=25567 att=8 cost=1`.
- `hafen.study.slots()` → `[{name,res}=bloodsoil], [{name,res}=bloodsoil], {name=Rattle-Tattle-Talisman,
  lp=25567, time=259200, cost=1, attention=8, res=gfx/invobjs/rattletattle}]`. **Not every study-slot
  item carries a `Curiosity`** — the two "A Bond of Blood & Soil" (a Hearth-Magic bond, not a standard
  curiosity) returned `{name, res}` only and contributed **0** to the totals; `summary()`
  (`lp=25567 att=8 cost=1`) matched the single item that *does* have a `Curiosity`, confirming the
  read is faithful (no fabricated fields). `progress` was absent (the item meter was 0 for these) —
  best-effort, as designed.
- `hafen.char.skills()` → 40 `{name, res}` (`Alchemy … Yeomanry`, `paginae/skills/*`);
  `hafen.char.skill("Alchemy")` → `true`.
- Full regression: vitals, buffs (+events), FEP/food, items, char, party, map all still correct.

## Files

- `src/io/brodgar/addon/AddonManager.java` — **the only file changed**: `StudyAdapter` (poll-driven,
  instance cache) registered in `init()`; `hafen.study.slots/summary` and `hafen.char.skills/skill`
  facades; helpers `studyInfo`, `readStudySlots`, `studySnapshot`, `studySlotsEqual`, `studySummary`,
  `skillwnd`, `skillName`, `skillRes`, `readSkills`, `hasSkill`; imports `SAttrWnd`, `SkillWnd`,
  `resutil.Curiosity`.
- `addons/hello/` — example + manifest bumped to **v0.8.0**.

**No `haven` core edit** — the 1d-1 `UI.java` tap and `AddonWidgets` accessor are untouched.

## Threading & safety

- `poll()` runs on the **UI thread** (the tick), under `synchronized(ui)`, so `sattr.children(...)`,
  `study.children(GItem.class)` and the `csk.items` copy see no concurrent tree/list mutation.
- Every read (`studySnapshot`, `readSkills`, `hasSkill`) swallows `Loading`/null → a partial or empty
  result, never an error into Lua. `Curiosity` fields are skipped while `GItem.info()` is Loading.
- The `SkillWnd` skill list (`Group.items`) is swapped wholesale off-thread by the `csk`/`nsk`
  uimsgs, so it is copied (`new ArrayList<>(...)`) before iterating — snapshot-safe, no CME.
- The `StudyAdapter` cache lives in the adapter instance (reset by re-instantiation in `init()`), so a
  relog re-resolves cleanly. No cross-session leak.

## Limitations / deferred

- **No per-item "time left"** — the client tracks only the total study `time`; `progress` (item
  meter) is best-effort and may be absent (it was, in the in-game test).
- **Not every study-slot item has a `Curiosity`** — bond/ritual items (e.g. "A Bond of Blood & Soil")
  occupy a study slot but carry no learning-points profile, so their snapshot is `{res, name}` only
  and they contribute nothing to `summary()`. This is faithful, not a gap.
- **Skills are read-only, no `SkillsChanged` event** — skills change rarely (only when you buy one);
  the spec's event set for this section is `StudyChanged` (+ `FepChanged`, done in 1d-2). Read
  `skills()` on demand.
- **`skill(name)` is a substring match, not exact** — consistent with `hafen.buffs.has`.
- **Credos + experiences deferred** — the pursued-credo progress and the Lore tab are on the same
  `SkillWnd` but out of scope for this slice.
- 1d continues with **belt read + equip-change event** (1d-4). No sandbox/watchdog yet (Phase 1f).
