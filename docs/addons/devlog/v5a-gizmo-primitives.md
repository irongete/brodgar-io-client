# V5a — Gizmo primitives (screen→world raycast, placegrid snap, mouse grab) + planner move-mode

> **Status:** ✅ Implemented; compile (`ant hafen-client` → BUILD SUCCESSFUL) + `ant bin` packages the updated
> `planner` into `bin/addons/`. **Headless-verified:** the `placeSnap` snap math (9/9 cases — tile-centre incl.
> negatives, sub-tile placegrid at `plobpgran=8`, free at `plobpgran=0`) replicated on the real `Coord2d`; LuaJ
> parse of the new `planner/main.lua`. `screenToWorld` (GPU raycast) + the mouse grab need a live GL/session, so
> they are **in-game DoD-verified**. **One tiny `// addon:` `haven` core edit** (factor `StdPlace`'s snap math into
> a shared static `MapView.placeSnap`); the rest is `io.brodgar.addon` + a new grab widget + Lua. **Java engine
> change ⇒ `ant` rebuild + a full client restart** before testing. **In-game DoD pending.**
> **Design:** [specs/addons/16-virtual-entities.md](../../../specs/addons/16-virtual-entities.md) (§2, §3 table,
> §4.1), decisions **D-031** (gizmo = Lua over Java primitives) / **D-033** (reuse the client's placegrid snapper) /
> **D-029** (client-only, safe-tier).

## Why V5 was split into V5a + V5b

The queued **V5** ("Gizmo: move + placegrid snapping") is genuinely two layers, and they map cleanly onto
**[D-031](../../../specs/addons/decisions.md)** ("Java exposes **primitives**, the gizmo **behaviour** lives in a
**bundled Lua library**"):

- **V5a (this slice) — the Java primitives + a move-mode proof.** `hafen.map.screenToWorld` (the raycast inverse of
  `worldToScreen`), `hafen.map.snapPlace`/`placeGrid` (the client's own placement snapper), and `hafen.hook.grab`
  (the drag-capture primitive). Proven end-to-end by a `planner` **move-mode**: `:planner grab` drags the selected
  ghost along the terrain, snapped to the placegrid, camera fixed. This exercises every primitive and delivers all of
  the V5 DoD **except** "by its arrows".
- **V5b (next) — the 3D arrow-handle gizmo.** The bundled Lua gizmo library: clickable arrow-handle ghosts (reusing
  the V2 pick), axis constraint, press-an-arrow-to-drag. Built entirely on V5a's primitives — no new Java.

Splitting keeps each a single, independently in-game-verifiable increment (like 1c/1d/4), and front-loads the hard
infrastructure (async GPU raycast, mouse capture, snap reuse) so V5b is pure Lua.

## The three primitives

### `hafen.map.screenToWorld(sx, sy, fn)` — asynchronous by necessity

The gizmo drags on the ground, so it needs the inverse of `worldToScreen`: *what world point is under this pixel?*
The engine's only accurate answer is [`MapView.Maptest`](../../../src/haven/MapView.java) — the same GPU pass the
client's own building placement (`Plob.Adjust`) uses, reading the true terrain point from the click-location buffer.
That is a **GPU→CPU readback**, so it is **inherently asynchronous**: a synchronous `screenToWorld` returning `{x,y}`
would have to fence the UI thread on the GPU. So the primitive takes a **callback**:

```lua
hafen.map.screenToWorld(sx, sy, function(w)   -- w = {x,y} world, or nil (pixel hit no terrain)
  ...
end)
```

The result arrives a frame later through `fn` — exactly the one-frame lag a placement `Plob` has (it, too, adjusts on
the `Maptest` callback). The bridge subclasses `Maptest` inline (it is `public`, so `io.brodgar.addon` can
`mv.new Maptest(pc){…}.run()` with **no core edit**) and forwards `hit`/`nohit` to Lua via `callLua`. The callback
runs on the render thread under `synchronized(ui)` — the same footing as the V2 ghost-click dispatch, so `callLua`
is safe there and serialized against all other addon Lua (see Threading).

### `hafen.map.snapPlace(x, y [, fine])` + `placeGrid()` — reuse the client's snapper (D-033)

Moving a ghost must feel **identical to placing a real building**, so it reuses the client's placement snapper rather
than inventing one. The engine snaps a placement ghost through
[`StdPlace.adjust`](../../../src/haven/MapView.java), governed by the public, persisted `:placegrid`
(`MapView.plobpgran`). We took the **preferred D-033 option**: a tiny `// addon:` refactor factoring `StdPlace`'s
position-snap math **verbatim** into a shared static helper both the client and the bridge call — **zero drift**:

```java
// haven/MapView.java — addon: shared placement-snap math (factored out of StdPlace.adjust; spec 16 §4.1, D-033)
public static Coord2d placeSnap(Coord2d mc, int modflags) {
    if((modflags & UI.MOD_SHIFT) == 0)
        return(mc.floor(tilesz).mul(tilesz).add(tilesz.div(2)));                      // no SHIFT -> tile centre
    else if(plobpgran > 0)
        return(mc.div(tilesz).mul(plobpgran).roundf().div(plobpgran).mul(tilesz));    // SHIFT -> sub-tile placegrid
    else
        return(mc);                                                                   // placegrid 0 -> free
}
```

`StdPlace.adjust` now opens with `Coord2d nc = placeSnap(mc, modflags);` (was the inline `if/else` — behaviour
unchanged for the client). `snapPlace(x, y, fine)` maps `fine` → `UI.MOD_SHIFT` and calls the same static, so the
bridge and the engine snap through **one** code path. `placeGrid()` reads `plobpgran` so an addon can show / follow
the live setting. Both are pure statics — no map data, no in-world requirement.

### `hafen.hook.grab{move, up}` — the drag-capture primitive

A press-drag-release loop needs two things the plain event system doesn't hand out together: **every** mouse move
(even off the origin widget) and the **terminating mouse-up wherever it lands**. Two engine facts (verified in the
event code) shape the solution:

- A [`MouseMoveEvent`](../../../src/haven/Widget.java) is **broadcast to every *visible* widget** — its
  `propagation` dispatches to all children with no cursor-area test. So a **visible** widget on `ui.root` receives
  every move. (The invisible `AddonRoot` tick pump does **not** — `visible == false` skips it — so the grab needs
  its own widget.)
- A `MouseUpEvent` only reaches the widget under the cursor **unless** [`UI.grabmouse`](../../../src/haven/UI.java)
  captures it (grabs are checked before normal dispatch; `grabmouse` also captures down/wheel).

So `hafen.hook.grab` is a new **`io.brodgar.addon.LuaMouseGrab extends Widget`** added to `ui.root` **visible**
(zero-size, draws nothing) that (a) receives broadcast moves and forwards `move(x, y, mods)`, and (b) calls
`ui.grabmouse(this)` so the release `up(x, y, button, mods)` always lands — then auto-releases. Because `grabmouse`
routes down/up/wheel to the grab first, during the drag the `MapView` never sees them → **no camera pan, no stray
click** (the "camera stays put" half of the DoD), **zero core edit** — exactly the pattern a draggable `Window` uses.
`mods` is a `{shift, ctrl, alt}` table (from `UI.modflags()`), so Lua needs no bitwise ops (Lua 5.2 has none).

The widget is bridge-owned (`Addon.mouseGrabs`); the addon gets an opaque `{ :release() }`. `release()` drops the
`UI.Grab` immediately and marks the widget dead; the **widget itself unlinks on its next `tick`** — a deferred
removal so a `handle:release()` called from *inside* the `move` callback never mutates the tree mid-broadcast (tick
propagation snapshots `next` first, so removing there is safe). Teardown releases any active grab.

## Threading — all Lua stays serialized

The subtle bit is that the two callback sites run on **different threads**, yet must not run Lua concurrently:

- the grab's `move`/`up` fire from input dispatch, which `UILoop.Frame.tick` runs **under `synchronized(ui)`**;
- `screenToWorld`'s callback fires from the GPU readback on the render thread, which **takes `synchronized(ui)`
  itself** before calling `hit`/`nohit`.

Because every `callLua` site holds the `ui` monitor, the frame thread and the readback thread **serialize** — addon
Lua never runs concurrently (the same guarantee the L3 message hook and V2 click dispatch rely on). And
`screenToWorld` does **not** block: `Maptest.run()` submits the readback and returns while the frame still holds
`ui`; the callback lands after the frame releases it (identical to how `Plob` adjusts on mousemove).

## The planner move-mode (`:planner grab`)

The example wiring, proving the primitives compose into a real drag:

```lua
drag.grab = hafen.hook.grab{
  move = function(sx, sy, mods)
    if not drag or drag.pending then return end           -- coalesce: one raycast in flight at a time
    drag.pending = true
    hafen.map.screenToWorld(sx, sy, function(w)
      if not drag then return end
      drag.pending = false
      if not w then return end
      local s = hafen.map.snapPlace(w.x, w.y, mods.shift)  -- SHIFT = the fine placegrid (D-033)
      if it.ghost then it.ghost:move(s.x, s.y, it.a) end    -- keep facing; snapped
    end)
  end,
  up = function() commitDrag() end,                        -- drop: re-anchor gridPos + persist
}
```

The **coalescing** (`drag.pending`) issues at most one raycast per in-flight callback, so a fast drag doesn't queue a
backlog of GPU passes (the same cadence the client's placement has). Start with `:planner grab` (a command, so there
is no async-click race to install the grab), the ghost follows the cursor snapped, and a **click drops it**
(committing re-anchors the record to the new grid position and persists it). `commitDrag` is idempotent and is also
run on `OnEnterWorld` so a half-finished drag never crosses a relog.

## Files changed

- **`src/haven/MapView.java`** — **one `// addon:` core edit**: new `public static Coord2d placeSnap(Coord2d, int)`
  (StdPlace's snap math, factored out verbatim) + `StdPlace.adjust` now calls it. (`screenToWorld` reuses the public
  `Maptest` — no edit.)
- **`src/io/brodgar/addon/LuaMouseGrab.java`** — new: the `hafen.hook.grab` widget (visible root child + `grabmouse`;
  forwards move/up; deferred self-unlink).
- **`src/io/brodgar/addon/Addon.java`** — new owned list `mouseGrabs` (+ teardown discipline, P2).
- **`src/io/brodgar/addon/AddonManager.java`** — `hafen.map.screenToWorld`/`snapPlace`/`placeGrid`;
  `hafen.hook.grab` + `newMouseGrab`/`teardownMouseGrabs`; the `screenToWorld` Maptest helper; `modsTable`.
- **`addons/planner/`** (v0.2.0) — `:planner grab` move-mode + `commitDrag`; help/manifest updated. `hello`
  untouched (read-only harness).

## Try it in-game (V5a DoD)

Rebuild (`ant hafen-client`) and **fully restart** the client (Java engine change — no hot-reload). Then, in the
world (`planner` is default-enabled):

1. `:planner place` a couple of blueprint ghosts, then **click** one to select it (warm highlight).
2. `:planner grab` — the selected ghost now **follows the cursor**, snapping to **tile centres** as you move. **Hold
   SHIFT** → it snaps to the finer sub-tile placegrid. Try `:placegrid 4` (or `2`, `16`) in the console first — the
   fine grid **follows your setting** (D-033). Move the mouse around the terrain: the ghost tracks the ground under
   the cursor (a frame behind, like real placement).
3. **The camera does not pan** while dragging — middle-drag/scroll are captured. **Click** to drop it; the log shows
   the drop position + grid, and it's **persisted** (relog → it reloads there — the V4 guarantee, now at the moved
   spot).
4. `:reload` (or disable `planner`) mid-drag leaves no stuck grab and no leaked ghost.

## Deferred

- **The 3D arrow-handle gizmo** (`hafen.ghost.gizmo`, "by its arrows", axis constraint) → **V5b**, a bundled Lua
  library over exactly these primitives (no new Java).
- **`snapAngle`/`placeAngle` + the rotate ring** (reuse `plobagran`/`:placeangle`) and **`:scale`** → **V6**.
- **`screenToWorld` ordering guarantee.** Callbacks are coalesced but not sequenced; in practice GPU readbacks
  resolve in submission order and the drag is dominated by the latest cursor position (the `Plob` has the same
  property). A generation guard is unnecessary at this cadence.
- **2D-projected handle fallback** (spec §4) — unneeded now that the 3D pick is free (V2); kept as a note.
