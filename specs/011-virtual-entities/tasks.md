# 011-virtual-entities — Tasks

- [x] 011.1 — Minimal ghosts: `hafen.ghost.new/list` + `:move/:pos/:res/:destroy`; the
      MapView client-gob seam; deferred create.
- [x] 011.2 — Clickable ghosts: `clickable`/`onClick` + owner-scoped `GhostClicked`;
      `GhostGob.obstate` pick surface; the `Click.hit` consume intercept.
- [x] 011.3 — Look & orientation: `:rotate/:setRes/:alpha/:tint/:show/:hide` + `sdt`;
      live-change via scene re-add.
- [x] 011.4 — Grid-anchored layouts: `hafen.map.fromGridPos` (+ `AddonWidgets.gridWorldUL`);
      the `planner` example (place/select/rotate/persist, relog restore with retry).
- [x] 011.5 — Gizmo primitives: `hafen.map.screenToWorld` (async raycast), `snapPlace`/
      `placeGrid` (shared `MapView.placeSnap`), `hafen.hook.grab`; planner move-mode.
- [x] 011.6 — The transform gizmo (bundled Lua, 2D-projected): axis-locked snapped move;
      `g:poly` added to the draw wrapper.
- [x] 011.7 — Gizmo rotate + scale + polish: `hafen.map.snapAngle/placeAngle`, relative
      rotate ring, `g:scale` (`Location.scale`), constant-size draw-on-top handles;
      position+facing+scale persisted.
