# 010-write-actions — Tasks

- [x] 010.1 — Permission mechanism + `hafen.act.moveTo` (manifest `permissions` declaration,
      `requireActions`, the click-macro encoding).
- [x] 010.2 — Panel master switch + default-disabled write addons (the seen-set policy,
      `[actions]` markers; `hello` made read-only, `walker` born).
- [x] 010.3 — Enable-time consent dialog + **D-028**: permission is per-addon ONLY (master
      switch removed; consent = the grant).
- [x] 010.4 — MapView verbs: `clickGob`/`useItemOn`/`place`/`select` + the `raw` escape hatch.
- [x] 010.5 — Menu verbs: `hafen.act.menu(path…)` + `hafen.act.flower(label)`.
- [x] 010.6 — Item verbs: `hafen.act.item(item, verb[, n])` via snapshot handles (D-022);
      delivers the Phase-3-deferred model item actions.
- [x] 010.7 — Per-subsystem gated verbs (completes the tier + the action-bar A3 gap):
      `speed.set`, `craft.make`, `actionbar.use`, `kin.add/remove/forget/rename/setGroup`.
