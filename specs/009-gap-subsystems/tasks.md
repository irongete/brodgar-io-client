# 009-gap-subsystems — Tasks

- [x] 009.1 — `hafen.markers` (map DB): list/nearest/add/remove + `MarkersChanged`
      (segment-anchored persistence, sessloc bridge, ref map).
- [x] 009.2 — Lore & Skills completion: `hafen.char.skillsAvailable/credos/experiences`.
- [x] 009.3 — `hafen.radar` (icon categories): categories/setVisible/setNotify over
      `GobIcon.Settings` + the REPL notice clamp fix.
- [x] 009.4 — `hafen.slash` (console commands): register + handle, one engine-lifetime
      dispatcher per name (the C1 reload-leak fix).
- [x] 009.5 — `hafen.kin` (buddy roster): list/find + `KinChanged` (uimsg adapter,
      snapshot diff).
- [x] 009.6 — `hafen.speed` (read): get/max/name over `Speedget`.
- [x] 009.7 — `hafen.craft` (read): `current()` — recipe/inputs/outputs/qmod/tools.
- [x] 009.8 — `hafen.quests` (read): list/selected + `QuestAdded`/`QuestDone`
      (uimsg adapter + pure `questDiff`).
- [x] 009.9 — `hafen.wounds` (read): list/has + `WoundChanged` (poll adapter; wound tree,
      severity strings).
- [x] 009.10 — `hafen.fight` (combat-school builder, read): maneuvers/deck/summary.
