# Learnings — Threading & the frame loop

> Append-only (never rewrite old entries). Index: [../LEARNINGS.md](../LEARNINGS.md). Grep this
> file rather than reading it whole; entries keep their original chronological order and tags.

- Widget creation runs `type.create(...)` **off the UI lock** (Loader thread) before `attach`/`bind`;
  do tree work in `added()`/`attached()` (under the lock). Factory-override seam covers builtin type
  names only (not resource-widget `/`-names).
- Threading: read `OCache` under `synchronized(oc)` (copy first), per-`Gob` under `synchronized(g)`,
  swallow `Loading`. Network-thread callbacks must be marshalled to the UI thread before hitting Lua.
- **`MapView.attach` runs on a loader thread** (widget creation is off the UI lock). So it only sets
  a `volatile enterWorldPending` flag; `OnEnterWorld` is actually fired on the next tick (UI thread).
- **The frame loop is `tick → draw → swap`, sequential, on ONE thread (2b — the key threading fact).**
  `UILoop.Frame.run()` calls `tick()` (→ `ui.tick()` → the `AddonRoot` tick pump) then `display()` (→
  `ui.draw`), both under `synchronized(ui)` on the single "Haven UI thread". So **every** Lua callback — tick
  events/timers/`OnUpdate` AND draw callbacks (widget `onDraw`, HUD overlays, gob overlays) — runs on that one
  thread, sequentially; there is **no concurrent LuaJ access** to guard against (LuaJ is not thread-safe, so
  this matters). It also means a `ui.drawafter(cb)` **registered during `tick` fires that same frame** (tick
  precedes draw), and a `PView.Render2D.draw` runs *inside* the `ui.draw` traversal (below), not on a
  separate render thread.
- **V5a: every `callLua` site holds `synchronized(ui)`, so Lua from different threads still serializes.** The grab's
  move/up fire from input dispatch (which `UILoop.Frame.tick` runs under `synchronized(ui)`), while `screenToWorld`'s
  readback callback fires on the render thread and takes `synchronized(ui)` itself — so the two never run addon Lua
  concurrently (the same discipline behind V2 click / L3 message). And `Maptest.run()` does NOT block: it submits the
  readback and returns while the frame still holds `ui`; the callback lands after the frame releases it. When adding a
  new callback surface, keep it under the `ui` monitor and it composes with everything else for free.
