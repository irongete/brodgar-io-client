# 003-widget-tree-reads — Spec

## What & why
The **widget-tree read mechanism** and its surfaces. Most high-value client state does NOT hang
off `Glob` — it lives in `GameUI` widget trees updated by targeted server `uimsg` (audit finding
B1): vitals, buffs, FEP/food, study, skills, the action bar, worn equipment. This feature builds
the general mechanism (Locator + per-tree Adapter + inbound-`uimsg` tap → semantic events) and
ships every read surface on it. Design: [design/14-widget-tree-reads.md](../design/14-widget-tree-reads.md).

## Acceptance criteria (verified in-game)
- [x] `hafen.player.vitals()` → `{hp,stamina,energy}` 0..1 with the positional IMeter mapping
      confirmed correct (hp=1 full, stamina draining live, energy=0.886); `VitalsChanged` fires
      per bar on stream-in and on live drain; nil before the meters exist.
- [x] `hafen.buffs.list()/has(q)` (snapshot `{res,name,amount,cooldown,number}` — fractions/int,
      never seconds; fading `dest` buffs excluded) + `BuffAdded`/`BuffRemoved`/`BuffChanged`;
      `hafen.char.food()` (`fep` cap/total/entries + `hunger` level/label/efficacy — the ONE
      place absolute FEP/hunger numbers exist) + `FepChanged`.
- [x] `hafen.study.slots()/summary()` + `StudyChanged` (streamed 0→1→3 at login; totals
      lp=25567/att=8/cost=1 matched the game's Study Report; a Hearth-Magic bond with no
      `Curiosity` info faithfully reads `{res,name}` only) and `hafen.char.skills()/skill(name)`
      (40 known skills; `skill("Alchemy")`→true).
- [x] `hafen.actionbar.slot(n)` (raw **0-based game index 0..143** — the same index the gated
      `use` verb takes later) + `ActionbarChanged{n}` (fires on stream-in and manual slot ops;
      cooldown excluded from change-detection) and `EquipChanged` (full equipment array; `wear`
      excluded so durability drift doesn't spam).
- [x] All surfaces stream in a beat after `OnEnterWorld` — nil/empty at `[now]`, populated at
      `[+3s]` in the `hello` harness (v0.6.0→v0.9.0); full prior regression intact.

## Out of scope
- Action-bar **use** (write) → [010-write-actions](../010-write-actions/spec.md); message hooks
  on the same tap → [007-hooks-hotkeys](../007-hooks-hotkeys/spec.md).
- Per-item study "time left" (the client has no countdown — only total `time`), `SkillsChanged`,
  credos/experiences (→ 009), per-slot `EquipChanged` granularity (→ ROADMAP).

## Context files
- `design/14-widget-tree-reads.md` — the mechanism design (Locator/Adapter/tap, adapter rows)
- `design/13-hooks-and-interception.md` — the L3 inbound-uimsg seam this tap anticipates
- `src/haven/UI.java` (the `UiMessage.run` tap), `src/haven/AddonWidgets.java` (the
  `haven`-package accessor) — the two core edits
- `src/io/brodgar/addon/AddonManager.java` — `TreeAdapter` + the six adapters + facades
- `src/haven/GameUI.java`, `IMeter.java`, `Buff.java`, `BAttrWnd.java`, `SAttrWnd.java`,
  `SkillWnd.java`, `Equipory.java` — the widget trees read
- `docs/addons/api/player.md`, `buffs.md`, `char.md`, `actionbar.md`, `events.md` — shipped surface
- `../002-read-api/` — the Glob-backed reads this complements; `../001-bootstrap-engine/` — tick/events
