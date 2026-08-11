# 011-virtual-entities — Plan

> History: this work appears in git history and `learnings/` tagged **V1, V2, V3, V4, V5a,
> V5b, V6** (the V-series). V5 was split: Java primitives (V5a) then the Lua gizmo (V5b) —
> the D-031 boundary made the split natural.

## Approach
- **A ghost is a client-only `Gob`, the `Plob` precedent**: `new Gob(glob, rc)` with id −1
  (virtual → invisible to OCache/server/reads), `ResDrawable` via `Resource.remote()`
  (NOT `local()` — the second early bug), added to the MapView `basic` slot. A virtual gob
  must be ctick'd/gtick'd BY HAND (nothing else does it — the first early bug), so the ONE
  V1 core edit is a centralized MapView seam: `clientGobs` list + add/remove + per-frame
  tick/gtick beside the Plob's, error-isolated per gob.
- **Deferred create** (like Plob/sound): `res.get()` throws `Loading` → build on a loader
  task; handle returned immediately; `:move`/`:destroy` before publish are honoured under
  the ghost monitor (a destroy discards the un-added gob).
- **Clickability = `GhostGob.obstate` prepping a `GobClick` when clickable** — the one Gob
  hook `virtual` doesn't gate — WITHOUT flipping `virtual`. `Clicklist` decides membership
  at slot-ADD time → a live toggle/look change = remove+re-add the slot
  (`refreshGhostScene`). The `Click.hit` intercept (V2's one core edit, beside the voice
  hooks; `cg.virtual` fast path) consumes the click before any `wdgmsg`. `GhostClicked` is
  **owner-scoped** (a handle is a private capability — broadcasting would leak it cross-addon).
- **Look**: tint = `MixColor` (the GobHealth state); alpha<1 = `BaseColor` + blend +
  `maskdepth` (the engine's translucent-overlay recipe; documented x-ray caveat); colour has
  ONE canonical shape (`{r,g,b[,a]}` named keys via `luaColor`) despite the spec's positional
  example.
- **Persistence**: anchors are `{gridId, offset}` (world coords are login-relative);
  `fromGridPos` resolves a stable grid id via `AddonWidgets.gridWorldUL` (one package-private
  read, zero MCache edit) adding back the same `g.ul` basis `gridPos` subtracts — exact
  round-trip. Handles can't persist → runtime list + serializable mirror, resolve-with-retry
  as grids stream in.
- **Gizmo primitives**: `screenToWorld` = the engine's `Maptest` GPU readback → inherently
  async (callback, coalesced one-in-flight); `snapPlace` = `StdPlace`'s math factored
  verbatim into a shared static (D-033, zero drift); `hook.grab` = a visible zero-size root
  widget (MouseMoveEvent broadcasts only to VISIBLE widgets) + `ui.grabmouse` (the up lands;
  camera captured), deferred self-unlink.
- **The gizmo is pure Lua** (`planner/gizmo.lua`, a global — files share one env, `require`
  is sandboxed away): 2D-projected handles (maintainer's call — no arrow resource needed;
  `g:poly` filled-triangle added to LuaGOut, zero core edit), arm the drag on the REAL
  mousedown via `hook.input`+preventDefault, axis-lock via per-component snapping, rotate
  ring RELATIVE (anchor the swept angle at grab — absolute would snap-face the cursor),
  `atan2` built from 1-arg `math.atan` (LuaJ omits atan2), scale = `Location.scale` in
  obstate (T·R·S: scales in place below the Placed translate/rotation).

## Files created / modified
- `src/haven/MapView.java` — the client-gob seam (V1), the `Click.hit` intercept (V2), the
  shared `placeSnap` static (V5a) — the three `// addon:` edits of the series
- `src/haven/AddonWidgets.java` — `gridWorldUL`
- `src/io/brodgar/addon/GhostGob.java`, `LuaGhost.java`, `LuaMouseGrab.java` — new;
  `LuaGOut.java` — `g:poly`; `AddonManager.java` (→ `RenderApi`/`WorldApi`) — ghost facade,
  fromGridPos/screenToWorld/snapPlace/snapAngle, grab
- `addons/planner/` — new example (v0.1.0→v0.4.0: place/select/rotate/persist, grab
  move-mode, gizmo move/rotate/scale); `addons/hello/` — V1-V3 ghost regression demos

## Risks & gotchas hit (detail: learnings/ghosts.md, threading.md)
- RenderTree slot ops are thread-safe (tree's own lock) but `cstate`/`ostate` REPLACE, and a
  live obstate change needs remove+re-add; swapping a drawable on a live gob needs
  `synchronized(gob)` (the engine's own res-swap shows the lock).
- Every `callLua` site (frame thread, render-thread readback, click dispatch) holds
  `synchronized(ui)` → all addon Lua serializes; `screenToWorld` never blocks.
- `Gob.removed()` is package-private — only `dispose()` is public.
- MouseUpEvent is not broadcast — a drag needs the grab; releases mid-callback must defer
  the tree unlink.

## Discarded alternatives
- A gob-id-based ghost address — no server id exists; handles (D-030).
- 3D-native arrow-handle meshes — needs a resource that doesn't ship; 2D-projected chosen.
- A synchronous screenToWorld — would fence the UI thread on the GPU.
- Broadcasting `GhostClicked` — cross-addon capability leak.
