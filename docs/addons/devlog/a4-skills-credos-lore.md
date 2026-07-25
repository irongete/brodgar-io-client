# A4 (completion) — Buyable skills, credos & lore/experiences

> **Status:** ✅ Implemented & **in-game verified**; compile (`ant hafen-client` → BUILD SUCCESSFUL),
> headless guard test (5/5, `resTipName`/`resIdent` Loading- & null-guards) + LuaJ parse of the harness.
> **Design:** [specs/addons/api-reference.md](../../specs/addons/api-reference.md) (`hafen.study` /
> `hafen.char` gap-subsystem A4), [specs/addons/coverage-gaps.md](../../specs/addons/coverage-gaps.md)
> (A4 — "Study / curiosity / FEP / experience / credo"), [specs/addons/code-map.md](../../specs/addons/code-map.md)
> (`CharWnd.skill` → `SkillWnd` `skg`/`credos`/`exps`).

This **finishes gap-subsystem A4**. Its headline trackers already shipped in Phase 1d — the study
window + `StudyChanged` ([1d-3](phase-1d3-study-skills.md)), FEP/food + `FepChanged`
([1d-2](phase-1d2-buffs-food.md)), and **known** skills ([1d-3](phase-1d3-study-skills.md)). What
remained deferred was the **rest of the "Lore & Skills" window** (`SkillWnd`): the **buyable**
skills, the **Credos** tab, and the **Lore/experiences** tab. This slice adds those three read
surfaces to `hafen.char`, completing A4.

Like the known-skills read, all three are bound to a `GameUI` widget tree (audit B1), not `Glob`.

## Zero core edits — every read is public

As in 1d-3, **no `haven` file changes**. `CharWnd.skill` (public `SkillWnd`) exposes everything:

- **Buyable skills:** `SkillWnd.skg.nsk` (public `GridList.Group`) → `items` (`List<Skill>`);
  `Skill.nm`/`res`/`cost` are public. (Known skills are the sibling `skg.csk`, read in 1d-3.)
- **Credos:** `SkillWnd.credos` (public `CredoGrid`) → `ccr`/`ncr` (public `List<Credo>`, acquired /
  available), `pcr` + `pcl`/`pclt`/`pcql`/`pcqlt`/`pqid` (public — the pursued credo and its level /
  quest progress), `cost` (public — LP to begin pursuing). `Credo.nm`/`res` are public.
- **Experiences / lore:** `SkillWnd.exps` (public `ExpGrid`) → `seen.items` (`List<Experience>`);
  `Experience.res`/`score`/`mtime` are public.

So the only file changed is `AddonManager.java` (the bridge). No `UI.java`, no `AddonWidgets`, no new
adapter.

## `hafen.char.skillsAvailable()` — buyable skills

```lua
for _, sk in ipairs(hafen.char.skillsAvailable()) do
  -- sk = { name, res, cost }   -- cost = LP price to learn
end
```

The counterpart to `hafen.char.skills()` (known). Same shape plus `cost`: `name` is the resource
tooltip (falls back to the internal token), `res` is the resource name (Loading-guarded), `cost` is
the learning-point price (`Skill.cost`).

## `hafen.char.credos()` — the Credos tab

```lua
local cr = hafen.char.credos()   -- a composite table, or nil until the window exists
-- cr = {
--   acquired  = { {name, res}, ... },   -- credos already completed  (CredoGrid.ccr)
--   available = { {name, res}, ... },   -- credos you could pursue    (CredoGrid.ncr)
--   pursuing  = { name, res, level, levelTotal, quest, questTotal, questId } | nil,
--   cost      = <int>,                  -- LP to begin pursuing a new credo (CredoGrid.cost)
-- }
```

A single composite (like `hafen.char.food()`), not four functions — one canonical read of the tab.

| field | meaning |
|---|---|
| `acquired` | array of `{name, res}` — credos you have completed (`ccr`) |
| `available` | array of `{name, res}` — credos you can start pursuing (`ncr`) |
| `pursuing` | the credo currently being pursued, **absent (nil) when none** (`pcr`) |
| `pursuing.level` / `levelTotal` | pursued-credo level progress (`pcl` / `pclt`) |
| `pursuing.quest` / `questTotal` | its current quest progress (`pcql` / `pcqlt`) |
| `pursuing.questId` | the quest id (`pqid`) — pass to a quest surface later |
| `cost` | LP cost to begin pursuing a new credo (`CredoGrid.cost`) |

Returns **nil** until the "Lore & Skills" window exists (it streams in a beat after enter-world, like
the rest of the character sheet).

## `hafen.char.experiences()` — the Lore tab

```lua
for _, ex in ipairs(hafen.char.experiences()) do
  -- ex = { name, res, score, mtime }
end
```

The "Lore" tab (`SkillWnd.exps.seen`) — the experiences/lore entries the character has seen. An
`Experience` has **no internal token**, so `name` is purely the resource tooltip (falling back to the
resource path, else absent); `res` is the resource name; `score` = experience points
(`Experience.score`); `mtime` = the server-supplied time field (`Experience.mtime`), a faithful
passthrough (not interpreted).

## No `*Changed` event — read on demand

Consistent with the deferred-`SkillsChanged` decision (1d-3): buyable skills, credos, and lore change
only on **explicit, infrequent player actions** — buying a skill, pursuing a credo, or quest progress
— not per-frame or via a stream of targeted `uimsg`s that a poll could cheaply diff. So this slice
adds **no adapter and no event**; an addon reads these on demand (e.g. after its own action, on a
timer, or when it opens its UI). If a live credo-progress event proves wanted, it can be added later
as a poll-driven adapter (like `StudyChanged`).

## Streaming — read on a timer, not in `OnEnterWorld`

Same as the known skills: the `SkillWnd` tabs are created hidden at login but their contents arrive a
beat **after** `OnEnterWorld`. On the immediate read, `skillsAvailable()`/`experiences()` are empty
and `credos()` is nil; they populate ~seconds later. Read them off a timer/tick, not synchronously in
the `OnEnterWorld` handler.

## Try it from the `:lua` console

```
:lua hafen.char.skillsAvailable()
:lua hafen.char.credos()
:lua hafen.char.experiences()
```

Before the window exists these are `[]` / `null` / `[]`, never an error.

## The `hello` example (`addons/hello/main.lua`)

A new `readLore(tag)` helper logs `skillsAvailable()` (count + first + its `cost`), `credos()`
(acquired / available counts, `cost`, and the pursued credo's level/quest progress if any), and
`experiences()` (count + first + `score`) — in both the `[now]` pass (usually empty/nil — streaming)
and the `[+3s]` pass (populated). It stays our standing regression harness — one login re-checks every
prior slice **and** this one. Bumped to **v0.23.0**.

## How to test in-game

**Java changed → full rebuild + restart is required** (the JVM does not hot-reload classes):

```bash
ant run
```

After `[hello] entered the world`, expect (a beat after enter-world, at `[+3s]`):

- `[hello] [+3s] skillsAvailable=<n>, first=<skill> cost=<n> LP` — `skillsAvailable=0` if you can
  already buy nothing new,
- `[hello] [+3s] credos: acquired=<a> available=<b> cost=<n>, pursuing=<credo> (lvl x/y, quest p/q)`
  — `pursuing=none` if you are not pursuing a credo,
- `[hello] [+3s] experiences=<n>, first=<lore> score=<n>` — `experiences=0` on a fresh character.

Then poke it live: `:lua hafen.char.skillsAvailable()`, `:lua hafen.char.credos()`,
`:lua hafen.char.experiences()`. Open the character sheet's **Lore & Skills** window and compare the
Skills / Credos / Lore tabs against the reads.

## Files

- `src/io/brodgar/addon/AddonManager.java` — **the only file changed**: the `hafen.char.
  skillsAvailable`/`credos`/`experiences` facades; new helpers `readAvailableSkills`, `credoSnapshot`,
  `readCredoList`, `readCredos`, `experienceSnapshot`, `readExperiences`; and the generic
  `resTipName`/`resIdent` resource-name helpers (extracted from `skillName`/`skillRes`, now
  delegating — DRY across skill/credo/experience). No new imports (`SkillWnd`, `Indir`, `Resource`,
  `List`, `ArrayList` already present).
- `addons/hello/` — `readLore` helper + manifest bumped to **v0.23.0**.

**No `haven` core edit** — the 1d-1 `UI.java` tap and `AddonWidgets` accessor are untouched.

## Threading & safety

- All three reads run on the **UI thread** (the `:lua` console tick or an addon timer/tick), under
  `synchronized(ui)`, so the `children()`/field reads see no concurrent tree mutation.
- The `SkillWnd` list references (`Group.items`, `CredoGrid.ccr`/`ncr`) are swapped wholesale
  off-thread by the `nsk`/`ncr`/`ccr`/`exps` uimsgs, so each is copied (`new ArrayList<>(...)`) before
  iterating — snapshot-safe, no `ConcurrentModificationException`.
- Every read swallows `Loading`/null via `resTipName`/`resIdent` → a partial or empty result, never an
  error into Lua. Names are `nil` while the resource is loading, exactly like the existing reads.
- No adapter, no cache, no listener → nothing to leak across a `:reload`/relog.

## Limitations / deferred

- **Read-only, no `*Changed` event** — see above. Read on demand.
- **`mtime` is passed through un-interpreted** — it is the server's raw time field on an
  `Experience`; the API does not convert it to wall-clock or "time ago".
- **Credo pursue / skill buy are actions** — starting a credo (`crpursue`) or buying a skill (`buy`)
  are outbound `wdgmsg`s → the **gated Phase-4 action tier** (D-010/D-025), not this read slice.
- **This completes A4.** The gap queue continues with **A3** (action bar — mostly done in
  [1d-4](phase-1d4-actionbar-equip.md); only the gated `actionbar.use` remains), **A2** (radar /
  GobIcon categories), **A11** (slash commands), then **A6–A10**.
