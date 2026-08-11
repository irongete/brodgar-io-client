# 011-virtual-entities — Spec

## What & why
**Client-only virtual world objects ("ghosts")** — translucent props an addon places in the 3D
world at arbitrary coordinates, never sent to the server (SAFE-tier, D-029). Motivating use:
city/base planning — lay out ghost buildings over real terrain, click-select them, drag them
with a Unity-style transform gizmo (move/rotate/scale, snapped to the client's own placegrid/
placeangle, D-033), and persist the layout grid-anchored so it reloads at the same physical
spot after a relog. Ships the `planner` example addon (D-031: the gizmo is a bundled Lua
library over Java primitives). Design: [design/16-virtual-entities.md](../design/16-virtual-entities.md).

## Acceptance criteria (verified in-game)
- [x] `hafen.ghost.new{res,x,y,…}` → a visible prop at a world coord (the `Plob` pattern:
      `id=-1` virtual `Gob` + `ResDrawable`, deferred create dodging `Loading`); `:move`
      repositions; `:destroy` and `:reload` remove cleanly; `list([filter])`.
- [x] Clickable ghosts (D-032): `clickable=true` + `onClick` / owner-scoped `GhostClicked` —
      clicking fires the handlers and is CONSUMED at `MapView.Click.hit` (no server click, no
      walk); a non-clickable ghost is genuinely click-through.
- [x] Look & orientation: `:rotate`, `:setRes` (live res-swap), `:alpha` (translucent
      BaseColor+blend+maskdepth recipe), `:tint` (MixColor), `:show/:hide`, `sdt`; chainable;
      desired state honoured at publish time.
- [x] Grid-anchored persistence: `hafen.map.fromGridPos(anchor)` (the exact inverse of
      `gridPos`); `planner` places/selects/rotates ghosts and a **relog restores the layout
      at the same physical grid position** (retry while grids stream in; out-of-range anchors
      kept, never dropped).
- [x] Gizmo primitives: `hafen.map.screenToWorld(sx,sy,fn)` (async GPU raycast — the Maptest
      pass), `snapPlace/placeGrid` (the client's own snapper, factored into a shared
      `MapView.placeSnap`), `hafen.hook.grab` (drag capture; camera stays put).
- [x] The gizmo (2D-projected, drawn with `g:poly` triangles): axis-locked snapped move,
      **relative** rotate ring snapping on `:placeangle` (45° coarse / SHIFT fine), scale box
      (screen-distance math, `Location.scale` in obstate = T·R·S in-place resize); relog
      restores position + facing + scale. `:planner gizmo [mode]`.

## Out of scope
- Multi-select/group transform; per-axis scale; promoting `gizmo` to a shared
  `hafen.ghost.gizmo`; the `iteminteract`-on-ghost guard (flagged, harmless — server ignores
  gob −1); CSprite-style resources ignore rotation/scale by construction (`goback("gobx")`).

## Context files
- `design/16-virtual-entities.md` — the whole design (mechanism, clickability, gizmo, snapping)
- Decisions D-029..D-033 in `../decisions/virtual-entities.md`
- `src/haven/MapView.java` — the client-gob seam (addClientGob/removeClientGob + ctick/gtick),
  the `Click.hit` intercept, the shared `placeSnap` static (the three V-series core edits)
- `src/haven/AddonWidgets.java` — `gridWorldUL` (grid-by-stable-id)
- `src/io/brodgar/addon/GhostGob.java`, `LuaGhost.java`, `LuaMouseGrab.java`,
  `RenderApi.java` (post-split home)
- `docs/addons/api/ghost.md`, `map.md` (fromGridPos/screenToWorld/snap*), `hooks.md` (grab)
- `addons/planner/` (gizmo.lua + main.lua) — the example; `../006-custom-ui/` (overlay/draw),
  `../007-hooks-hotkeys/` (input hooks), `../004-saved-variables/` (the layout store)
