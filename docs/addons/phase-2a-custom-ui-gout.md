# Phase 2a — Custom widgets & windows + GOut draw wrapper (`hafen.ui`)

> **Status:** ✅ Implemented; compile (`ant hafen-client` → BUILD SUCCESSFUL), 13/13 headless input-plumbing
> checks (arg order, consume-on-truthy, `dead` guard after teardown) + LuaJ parse of the harness.
> First in-game run surfaced an `OnEnterWorld`-timing bug (window hidden until `:reload`) — **fixed** (gate
> on GameUI attached to root; see below). **Clean-login in-game re-verification pending.**
> **Design:** [specs/addons/07-ui-and-drawing.md](../../specs/addons/07-ui-and-drawing.md) (LuaWidget/LuaWindow,
> the GOut wrapper), [specs/addons/api-reference.md](../../specs/addons/api-reference.md) (`hafen.ui`),
> [specs/addons/code-map.md](../../specs/addons/code-map.md) (`Widget`/`Window`/`GOut` backings), decisions
> **D-013** (one canonical way) + **D-018** (watchdog).

The first slice of **Phase 2 (Custom UI + hooks)**: addons can now create their **own client-side UI** — a
draggable, titled **window** or a bare **widget** — and draw into it every frame through a thin **`GOut`
wrapper**. This is the foundation the rest of Phase 2 (HUD overlays, gob overlays, hooks) builds on. It
delivers half the Phase 2 DoD: **a draggable custom window**.

## The API — `hafen.ui`

```lua
local win = hafen.ui.window{
  title   = "My Panel",
  size    = { 200, 140 },              -- content size {w, h} (raw pixels)
  pos     = { 100, 100 },              -- initial top-left {x, y} (optional)
  onDraw  = function(g, w, h) ... end, -- every frame; g = the draw wrapper, w/h = content size
  onTick  = function(dt) ... end,      -- every frame, before draw
  onClick = function(x, y, button) ... return true end,  -- mousedown; truthy = consume
  onClose = function() ... end,        -- the window's X was clicked (then it is destroyed)
}

win:move(120, 80)    -- move it
win:hide(); win:show()
win:size(220, 160)   -- resize the content (repacks a window)
win:visible()        -- → boolean
win:destroy()        -- remove it now (also happens automatically on reload/disable)
```

Two constructors, **one** forwarding path (D-013):

| Call | What you get |
|---|---|
| `hafen.ui.window(opts)` | a `haven.Window` (title bar, **drag**, close button) wrapping the content widget |
| `hafen.ui.widget(opts)` | a bare content widget (no chrome) — for HUD elements you position/draw yourself |

Both return a **handle** with `:move(x,y)`, `:show()`, `:hide()`, `:visible()`, `:pack()`, `:size(w,h)`,
`:destroy()`. Geometry ops target the root (the window chrome, or the widget); `:size` resizes the drawable
content; `:pack()` is a no-op for a bare widget (a leaf has nothing to fit).

### opts

| Key | Meaning | Default |
|---|---|---|
| `size` | content size, the array `{w, h}` | `{200, 140}` |
| `pos` | initial top-left, the array `{x, y}` | `{100, 100}` |
| `parent` | `"root"` (over everything) or `"gameui"` (under the HUD) | `"root"` |
| `title` | window caption (window only) | `""` |
| `onDraw(g, w, h)` | draw callback (every frame) | — |
| `onTick(dt)` | per-frame update | — |
| `onClick(x, y, button)` | mousedown; return truthy to **consume** | — |
| `onMouseUp(x, y, button)` | mouseup; truthy consumes | — |
| `onMouseMove(x, y)` | pointer move over the widget | — |
| `onWheel(x, y, amount)` | scroll; truthy consumes | — |
| `onClose()` | window close-button (then the window is destroyed) | — |

All coordinates are the **widget's own pixel space** (top-left = `0,0`). Callback errors are logged and
isolated (a bad `onDraw` never breaks the frame); every callback is **watchdog-armed** (D-018 layer 1), so
a runaway loop in a draw/tick handler aborts rather than freezing the client.

## The `g` draw wrapper

`onDraw` receives `g`, a thin wrapper over the client's [`GOut`](../../src/haven/GOut.java) — the 2D drawing
context. It maps 1:1 to `GOut`:

```lua
g:text(str, x, y)                 -- text (client font), top-left at (x, y)
g:atext(str, x, y, ax, ay)        -- anchored text (ax/ay 0..1 = which point sits at x,y)
g:rect(x, y, w, h)                -- 1px outline rectangle
g:frect(x, y, w, h)               -- filled rectangle
g:line(x1, y1, x2, y2 [, width])  -- a line (width default 1)
g:prect(cx, cy, radius, fraction) -- clockwise pie/progress wedge (0..1) — cooldowns/meters
g:color(r, g, b [, a])            -- set draw color (0..255; a default 255)
g:color()                         -- reset to default (white)
```

- **The wrapper is inert outside `onDraw`.** It is bound to the live `GOut` only for the duration of each
  draw call; if an addon stashes `g` and calls it later (e.g. from a timer), the methods no-op — an addon
  cannot corrupt the client's draw pipeline.
- **Color is per-primitive state**: `g:color(...)` sets it until you `g:color()` (reset) or change it again.
  Reset after a colored fill so you don't tint later draws.
- **`g:image(...)` is deferred** — image/`Tex` drawing needs resource resolution (the `Loading` dance) and a
  texture cache; it arrives in a later UI slice. 2a covers text + vector primitives + color.

## How it works (implementation)

- **`io.brodgar.addon.LuaWidget extends haven.Widget`** — a leaf widget whose `tick`/`draw`/`mousedown`/
  `mouseup`/`mousemove`/`mousewheel` each forward to the addon's matching Lua callback through
  **`AddonManager.callLua`** — the same watchdog-armed, error-isolated, CPU-accounted choke point events and
  timers use. `draw` binds the live `GOut` into the wrapper, calls `onDraw(g, w, h)`, then unbinds it.
- **Windows are composition, not a subclass.** `hafen.ui.window` creates a plain `haven.Window` (which
  already provides the title bar, **drag**, and close button) and adds a `LuaWidget` as its content child —
  so the Lua-forwarding logic lives in exactly one class (the spec's separate `LuaWindow` subclass would
  duplicate it). The window's `reqclose` is redirected to fire `onClose` then destroy (a client-side widget
  has no server to send the default `wdgmsg("close")` to).
- **Bridge-owned (P2).** Each content widget is registered in the addon's owned-resource registry
  (`Addon.widgets`, alongside `subs`/`timers`); `teardown` (relog / `:reload` / disable / CPU auto-disable)
  destroys every one under `synchronized(ui)`, cascading to the window chrome. **No leaks, reload-safe.**
- **Not bound to a server id** — a LuaWidget cannot `wdgmsg` the server (its message falls through to
  `ui.root` and is dropped). That is correct for custom UI ([07](../../specs/addons/07-ui-and-drawing.md));
  game interaction is `hafen.act` (Phase 4).
- **`OnEnterWorld` timing fix (so a window created there is visible on a fresh login).** During bring-up the
  demo window was invisible until a `:reload`. Cause (found by tracing the widget protocol): `OnEnterWorld`
  fired as soon as `GameUI` **existed** (`gui() != null`, resolved via the map view) — a beat **before**
  GameUI is attached to `ui.root`. A window added to `ui.root` then is a sibling placed *before* GameUI, so
  the full-screen HUD draws over it. Fixed by gating `OnEnterWorld` on `gui().parent != null` (GameUI is in
  the tree), which fires the tick after the HUD mounts — so `ui.root` windows land on top. This is a
  one-line engine change in `AddonManager.tick` and makes `OnEnterWorld` the correct "the HUD is up" signal
  (≈ WoW `PLAYER_ENTERING_WORLD`). GameUI's *children* (inventory/meters/char) still stream in a beat later,
  as before — read those on a timer, not synchronously.
- **`callLua` now returns `Varargs`** (was `void`) so the input forwards can read the callback's return for
  "consume" (`.arg1().toboolean()`). Existing callers ignore the return — behavior unchanged.

### Threading & the draw-budget gap

Creation/geometry run from Lua (UI thread); tree links go through `Widget.add`, which locks on `ui`, so even
a window created in the file body / `OnLoad` (potentially off the UI thread) attaches safely. **Known gap:**
`onDraw` runs during `UI.draw`, *after* the tick's soft-CPU-budget window (D-018 layer 2), so **draw time is
not counted toward the per-tick budget** — only the per-call instruction cap (layer 1) guards a runaway draw.
A sustained draw hog is therefore caught per-call but not auto-disabled; folding draw time into the budget is
a later refinement.

## The `hello` example (`addons/hello/main.lua`)

At `OnEnterWorld` the harness now creates a small **draggable window** that draws live state through the
wrapper and counts clicks:

```lua
local function drawPanel(g, w, h)
  g:color(0, 0, 0, 150); g:frect(0, 0, w, h); g:color()        -- translucent backdrop
  g:text(("clock %.0f"):format(hafen.time.clock() or 0), 6, 6)
  g:text(("clicks %d"):format(clicks), 6, 22)
  local v = hafen.player.vitals()                               -- draw hp/stam/energy as 0..1 bars
  ...
  g:color(170, 170, 170); g:rect(0, 0, w, h); g:color()        -- 1px border
end

panel = hafen.ui.window{
  title = "Hello 2a", size = { 168, 90 }, pos = { 80, 120 },
  onDraw = drawPanel,
  onClick = function(x, y, button) clicks = clicks + 1; ...; return true end,
  onClose = function() hafen.log("panel closed (X) -- :reload to bring it back") end,
}
```

It stays our standing regression harness — one login re-checks Phase 0 / 1a–1f **and** this slice. Bumped to
**v0.13.0** (Lua string + `manifest.json`). The window is bridge-owned, so **`:reload` or disabling `hello`
destroys it automatically** — no `OnDisable` cleanup in the addon.

## How to test in-game

**Java changed → full rebuild + restart is required** (the JVM does not hot-reload classes):

```bash
ant run
```

1. Enter the world. A small **"Hello 2a"** window appears near the top-left, drawing `clock`, a `clicks`
   counter, and three live vitals bars (hp / stamina / energy) that fill in a beat after login.
2. **Drag** it by the title bar — it moves. **(This is the DoD — a draggable custom window.)**
3. **Click the body** — the `clicks` count increments and the console logs
   `panel click #N at x,y (button B)`; the click is consumed (doesn't fall through to the game).
4. Watch the bars change as stamina drains / energy shifts (proves `onDraw` runs every frame off live state).
5. **Close** it with the X — the console logs `panel closed (X)`; the window is destroyed.
6. Bring it back / prove teardown: `:reload` re-creates it (and would destroy any old one first — no leak).
   `:addons disable hello` + `:reload` removes it entirely; `:addons enable hello` + `:reload` restores it.
7. Poke it live: `:lua local w = hafen.ui.window{ title="repl", size={120,60}, onDraw=function(g) g:text("hi",8,8) end }`
   then `w:move(300,300)`, `w:hide()`, `w:show()`, `w:destroy()`.

## Files

- `src/io/brodgar/addon/LuaWidget.java` — **new.** The client-side widget that forwards draw/tick/mouse to
  Lua callbacks + builds the `g` draw wrapper.
- `src/io/brodgar/addon/AddonManager.java` — the `hafen.ui` facade (`window`/`widget` → `newUi` + `uiHandle`);
  `teardown` now destroys owned widgets (`destroyWidgets`, under `synchronized(ui)`); `callLua` returns
  `Varargs`; the `OnEnterWorld` gate in `tick` now requires `gui().parent != null` (HUD attached to root, see
  the timing-fix note above); imports `Window`/`Varargs`/`VarArgFunction`.
- `src/io/brodgar/addon/Addon.java` — new `widgets` owned-resource list (`List<LuaWidget>`).
- `addons/hello/` — a draggable demo window; bumped to **v0.13.0** (+ `hafen.ui` in the manifest description).

**No `haven` core edit** — every widget/window/GOut member used is public; the forwarding lives entirely in
the addon package (like 1d-3 / 1e).

## Limitations / deferred (later Phase 2 slices)

- **`g:image(...)` / custom resources** — image drawing (Tex/resource resolution) and addon `res/` sources.
- **UI scaling** — sizes and draw coords are raw pixels; `UI.scale` (HiDPI) integration is not applied yet.
- **Keyboard input** — no `onKey` in 2a; reliable input is via `hafen.key.bind` (global hotkeys, a later
  slice) rather than per-widget focus. Widget focus/`grab` (modal drag inside content) is also deferred.
- **HUD overlays** (`hafen.ui.overlay`) and **world-space gob overlays** (`hafen.ui.gobOverlay`) — next slices.
- **Hooks** (`hafen.hook.input`/`action`/`message`) — the other half of Phase 2; the `hook.action("click")`
  demo completes the Phase 2 DoD.
- **Draw-time CPU budget** — draw callbacks are instruction-capped per call but not counted in the soft
  per-tick budget (see above).
- **Window position persistence** via saved-vars is not automatic (an addon can do it with `hafen.store`).
