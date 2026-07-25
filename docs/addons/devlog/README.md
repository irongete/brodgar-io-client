# Development log (archived)

These are the original **per-task development notes**, written as each piece of the AddOn system was
built. They are kept for history — design rationale, threading notes, and the exact code changes per
task — and are **not** the usage docs.

> For how to write and use addons, see the reference instead:
> **[Getting started](../getting-started.md)** · **[API reference](../api/README.md)**.

## Notes by phase

**Phase 0–1 — engine, loading, the read API, storage, sandbox**

- [phase-0-spike](phase-0-spike.md) — LuaJ engine spike, `:lua` REPL
- [phase-1a-loading](phase-1a-loading.md) — loading addons from disk (manifest, per-addon env)
- [phase-1b-events-timers](phase-1b-events-timers.md) — tick pump, event bus, timers
- [phase-1c-read-gobs](phase-1c-read-gobs.md) — `hafen.gob` / `hafen.world`
- [phase-1c2-map-player-time-sound](phase-1c2-map-player-time-sound.md) — `hafen.map` / `player` / `time` / `sound`
- [phase-1c3-items-char-party](phase-1c3-items-char-party.md) — `hafen.items` / `char` / `party`
- [phase-1d1-vitals-widget-tree](phase-1d1-vitals-widget-tree.md) — the widget-tree read mechanism + vitals
- [phase-1d2-buffs-food](phase-1d2-buffs-food.md) — `hafen.buffs` + `char.food`
- [phase-1d3-study-skills](phase-1d3-study-skills.md) — `hafen.study` + `char.skills`
- [phase-1d4-actionbar-equip](phase-1d4-actionbar-equip.md) — `hafen.actionbar` + equip events
- [phase-1e-saved-variables](phase-1e-saved-variables.md) — `hafen.store`
- [phase-1f1-sandbox](phase-1f1-sandbox.md) — the Lua sandbox
- [phase-1f2-reload-enabled-set](phase-1f2-reload-enabled-set.md) — `:reload` + the enabled set
- [phase-1f3-options-panel-soft-budget](phase-1f3-options-panel-soft-budget.md) — the AddOns panel + CPU budget

**Phase 2 — custom UI, overlays, hooks, hotkeys**

- [phase-2a-custom-ui-gout](phase-2a-custom-ui-gout.md) — `hafen.ui.window`/`widget` + the `g` wrapper
- [phase-2b-overlays](phase-2b-overlays.md) — HUD + gob overlays
- [phase-2c-input-hooks](phase-2c-input-hooks.md) — `hafen.hook.input`
- [phase-2d-action-hooks](phase-2d-action-hooks.md) — `hafen.hook.action`
- [phase-2e1-message-hooks](phase-2e1-message-hooks.md) — `hafen.hook.message`
- [phase-2e2-global-hotkeys](phase-2e2-global-hotkeys.md) — `hafen.key.bind`
- [phase-2e3-keybind-panel](phase-2e3-keybind-panel.md) — hotkeys in the keybind panel

**Phase 3 — widget interception & replacement**

- [phase-3a-widget-interception](phase-3a-widget-interception.md) — `hafen.ui.onWidgetCreate`
- [phase-3b-model-handle](phase-3b-model-handle.md) — `hafen.ui.adopt` (the model handle)
- [phase-3c-replace-bags](phase-3c-replace-bags.md) — `hafen.ui.replace` + the bags example

**Gap subsystems (A-series)**

- [a1-markers](a1-markers.md) · [a2-radar](a2-radar.md) · [a4-skills-credos-lore](a4-skills-credos-lore.md) ·
  [a6-kin](a6-kin.md) · [a7-speed](a7-speed.md) · [a8-craft](a8-craft.md) ·
  [a9-1-quests](a9-1-quests.md) · [a9-2-wounds](a9-2-wounds.md) · [a10-fight](a10-fight.md) ·
  [a11-slash-commands](a11-slash-commands.md)

**Phase 4 — the gated actions tier**

- [phase-4a-actions-gate-moveto](phase-4a-actions-gate-moveto.md) — the permission + `hafen.act.moveTo`
- [phase-4b-actions-panel-default-disabled](phase-4b-actions-panel-default-disabled.md) — panel + default-disabled
- [phase-4c-enable-consent-dialog](phase-4c-enable-consent-dialog.md) — the consent dialog (per-addon permission)
- [phase-4d-mapview-verbs](phase-4d-mapview-verbs.md) — `clickGob` / `useItemOn` / `place` / `select` / `raw`
- [phase-4e-menu-flower](phase-4e-menu-flower.md) — `hafen.act.menu` / `flower`
- [phase-4f-item-verbs](phase-4f-item-verbs.md) — `hafen.act.item`
- [phase-4g-per-subsystem-verbs](phase-4g-per-subsystem-verbs.md) — `speed.set` / `craft.make` / `actionbar.use` / `kin.*`

**V-series — virtual entities (world ghosts) & the transform gizmo**

- [v1-ghosts](v1-ghosts.md) — `hafen.ghost.new`/`list` + the handle (`:move`/`:pos`/`:res`/`:destroy`); the MapView client-gob seam
- [v2-clickable-ghosts](v2-clickable-ghosts.md) — opt-in clickable ghosts (`clickable`/`g:clickable`/`onClick`) + the `GhostClicked` event; the `GhostGob.obstate` pick surface + the `Click.hit` consume-before-`wdgmsg` intercept
- [v3-look-orientation](v3-look-orientation.md) — `:rotate`/`:setRes`/`:alpha`/`:tint`/`:show`/`:hide` + `new{a,sdt,alpha,tint}`; the `obstate` look states (`MixColor`/`BaseColor`+blend), the re-add-to-apply pattern, the deferred res-swap — zero core edit
- [v4-layouts-planner](v4-layouts-planner.md) — grid-anchored layouts: `hafen.map.fromGridPos` (inverse of `gridPos`, via the one `AddonWidgets.gridWorldUL` grid-by-id accessor — zero `MCache` edit) + the new `planner` example addon (place/select/rotate/persist blueprint ghosts, reload at the same grid after a relog)
- [v5a-gizmo-primitives](v5a-gizmo-primitives.md) — the gizmo's Java primitives (D-031): `hafen.map.screenToWorld` (async `Maptest` ground raycast), `hafen.map.snapPlace`/`placeGrid` (reuse the client's placegrid snapper via a shared `MapView.placeSnap` refactor — D-033), and `hafen.hook.grab` (a visible `LuaMouseGrab` root child + `grabmouse` — the drag-capture primitive, camera stays put) + `planner` move-mode (`:planner grab`); one `// addon:` core edit
