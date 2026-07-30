# 003-widget-tree-reads — Tasks

- [x] 003.1 — Mechanism foundation + vitals: `TreeAdapter`, the `UI.java` inbound-uimsg tap,
      `AddonWidgets` accessor; `hafen.player.vitals()` + `VitalsChanged`.
- [x] 003.2 — Buffs + FEP/food: per-tick `poll()` path; `hafen.buffs.list/has` +
      `BuffAdded`/`BuffRemoved`/`BuffChanged`; `hafen.char.food()` + `FepChanged`;
      `AddonWidgets.buffDest`.
- [x] 003.3 — Study + skills (zero-edit): `hafen.study.slots/summary` + `StudyChanged`;
      `hafen.char.skills/skill`.
- [x] 003.4 — Action bar (read) + equip event (zero-edit): `hafen.actionbar.slot(n)` (0-based
      game index) + `ActionbarChanged{n}`; `EquipChanged` over the equipment read.
