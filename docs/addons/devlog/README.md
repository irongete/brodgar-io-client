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
- [v5b-gizmo](v5b-gizmo.md) — the transform gizmo itself (D-031): a **bundled Lua library** `planner/gizmo.lua` that draws Unity-style X/Y arrow handles with `g:draw` (the 2D-projected path, no game resource) + a new **`g:poly`** filled-triangle draw primitive (public `GOut.drawp`/`tx`, zero core edit); press an arrow → `hook.input` preventDefault + `hook.grab` → axis-locked, placegrid-snapped drag (`screenToWorld`+`snapPlace`, camera stays); `:planner gizmo`
- [v6-gizmo-rotate-scale](v6-gizmo-rotate-scale.md) — completes the gizmo (D-031): **rotate** (a cyan ring, snaps on the `:placeangle` via the new **`hafen.map.snapAngle`/`placeAngle`** — the absolute analog of the wheel-relative `StdPlace.rotate`, zero-edit mirror of public `plobagran`) + **uniform scale** (`g:scale` → a `Location.scale` in `GhostGob.obstate`, composing **T·R·S** for in-place scaling, zero core edit; `g:pos()` now `{x,y,a,scale}`) + the **polish** (draw-on-top + constant-screen-size handles fall out of the 2D-projected path); `gizmo.lua` gains `mode` (`move`/`rotate`/`scale`/`all`) + a pure `atan2`; `planner` v0.4.0 (`:planner gizmo [mode]`/`scale`, facing+scale persisted grid-anchored). Zero `haven` core edit

**R-series — custom rendering (non-`.res` images & models)**

- [r1-images-screen](r1-images-screen.md) — `hafen.render.image(path)` loads an addon's own **PNG** into a cached, bridge-owned `TexI` handle (`:size()`/`:dispose()`; sandboxed to the addon folder, D-017) + `g:image(img,x,y[,w,h])` / `g:aimage(img,x,y,ax,ay)` draw verbs on the shared `LuaGOut`; the `.res`-is-already-PNG substrate exposed directly, the handle carries the `TexI` as an **opaque userdata** (facade-safe, the `LuaMarshal` pattern); disposed on reload/disable (P2). **Zero `haven` core edit**; `hello` v0.38.0 draws `icon.png` in the 2a window (native + scaled) and the 2b HUD overlay (anchored)
- [r2a-sprites-world](r2a-sprites-world.md) — `hafen.render.sprite{image,x,y[,a,scale,alpha,tint]}` stands a custom **PNG upright in the 3D world** as a fixed textured quad — the non-`.res` sibling of a ghost. **Generalizes the V-series core** (D-013): a new base **`LuaWorldEntity`** holds the transform/look/scene state + the gizmo, `LuaGhost`/new **`LuaSprite`** are just visuals on it (the `AddonManager` scene helpers renamed `*Ghost`→`*Entity`); the fixed quad is a resource-free **`SpriteQuad`** (`Homo3D.vertex`+`Tex2D.texc` `Model` + `tex.st()`/blend/`maskdepth`/`nofacecull` `Material` via `SprDrawable`), reusing the `GhostGob` `obstate` for tint/alpha/scale and the V1 `addClientGob` seam. Synchronous create (the `TexI` is already decoded — no `Loading`); click-through in R2a. **Zero `haven` core edit**; `hello` v0.39.0 (`:hello sprite` stands + live-transforms `icon.png`)
- [r2b-billboard-sprite](r2b-billboard-sprite.md) — `hafen.render.sprite{…,billboard=true}` stands the PNG as a **camera-facing** screen blit — the second visual on the shared R2a core: a resource-free **`LuaSpriteBillboard extends Drawable implements PView.Render2D`** (the `SpeakerIcon`/`LuaGobOverlay` pattern) whose `draw` projects the gob origin (`Homo3D.obj2view`) and blits the `TexI` bottom-centred, reading the `GhostGob`'s live alpha/tint/scale (it's a `Drawable`, so the gob keeps a Drawable → dodges `Gob.ctick`'s empty-virtual-gob cleanup, and `Gob.added` registers its `Render2D` in the 2D pass). Position/gizmo-move apply; world-rotate/scale do not. Also **generalizes the V2 click dispatch to sprites** (`onGhostClick`/`findGhostByGob`→`findEntityByGob` over `LuaWorldEntity`; a **fixed** sprite is now `clickable`, firing `onClick` + the owner-scoped **`SpriteClicked`** event; billboards have no mesh → never picked). **Zero `haven` core edit** (reuses the V1 `addClientGob` + V2 `Click.hit` seams); `hello` v0.41.0 (`:hello billboard`), `planner` v0.5.0 (records generalized to ghost **or** sprite, `:planner sprite [billboard]`, grid-anchored persistence — the gizmo drives both)
