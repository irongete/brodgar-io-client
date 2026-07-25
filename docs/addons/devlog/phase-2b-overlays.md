# Phase 2b — HUD overlays & world-space gob overlays (`hafen.ui.overlay` / `gobOverlay`)

> **Status:** ✅ Implemented; compile (`ant hafen-client` → BUILD SUCCESSFUL), 21/21 headless logic checks
> (registration/removal lifecycle, string+function filter matching, error isolation, nil-guard, invalid-arg
> rejection) + LuaJ parse of the harness under the sandbox. **In-game DoD pending.**
> **Design:** [specs/addons/07-ui-and-drawing.md](../../specs/addons/07-ui-and-drawing.md) (HUD overlays,
> world-space overlays), [specs/addons/api-reference.md](../../specs/addons/api-reference.md) (`hafen.ui`),
> [specs/addons/code-map.md](../../specs/addons/code-map.md) (`UI.drawafter`, `SpeakerIcon`/`PView.Render2D`),
> decision **D-013** (one canonical way) + **D-018** (watchdog / CPU budget).

The second slice of **Phase 2 (Custom UI + hooks)**: addons can now draw **outside a widget** — on top of the
HUD, and over game objects in the 3D world. Together with 2a's windows/widgets this completes the addon
**drawing** surface (hooks come next: 2c input, 2d action, 2e message). Both overlay kinds reuse 2a's `GOut`
wrapper `g`, so the draw API is identical everywhere (D-013).

## The API — `hafen.ui`

```lua
-- HUD overlay: paint on top of the HUD without owning a widget.
local ov = hafen.ui.overlay(function(g, w, h)
  g:text("hello", 10, 10)          -- absolute SCREEN coords; w,h = screen size
end)
ov:remove()                        -- also auto-removed on reload/disable

-- World-space gob overlay: label/mark game objects (the SpeakerIcon pattern).
local go = hafen.ui.gobOverlay(
  function(gob) return gob.isplayer end,          -- filter: a function (or a name substring string)
  function(g, gob, sx, sy)                         -- draw at the gob's projected screen point
    g:atext("player", sx, sy, 0.5, 1.0)            -- (sx, sy) = just above the gob's head
  end)
go:remove()                        -- also auto-removed on reload/disable
```

| Call | Returns | Draws |
|---|---|---|
| `hafen.ui.overlay(fn)` | handle (`:remove()`) | `fn(g, w, h)` every frame, on top of the whole HUD |
| `hafen.ui.gobOverlay(filter, fn)` | handle (`:remove()`) | `fn(g, gob, sx, sy)` over each matching gob |

- **`fn` gets the same `g`** as a widget's `onDraw` (`g:text/atext/rect/frect/line/prect/color`; `g:image`
  is still deferred). It is inert outside the callback — you cannot stash `g` and draw later.
- **`filter`** is either a **function** `filter(gob) -> truthy` or a **name substring `string`** (matched
  against the gob's drawable-resource name, exactly like `hafen.world.gobs("...")`). The `gob` passed to
  both `filter` and `fn` is the **same snapshot** as `hafen.gob.info` (`{id, name, x, y, hp, isplayer, …}`).
  A function filter should **nil-guard** `gob.name` (it can be absent while the gob streams in):
  `gob.name and gob.name:find("kritter")`.
- Both handles auto-remove on **reload/disable** (bridge-owned, P2) — no `OnDisable` cleanup needed.

### Coordinates

- **HUD overlay** — absolute **screen** pixels; `w, h` are the screen size. Drawn **after** the entire HUD.
- **Gob overlay** — `sx, sy` is the gob's anchor **projected to the screen** (the head height the buddy
  name label uses). Offset from it as you like; text/markers are in screen pixels around that point.

## How it works

### HUD overlays — a re-queued one-shot `UI.drawafter`

[`UI.drawafter`](../../src/haven/UI.java) is **one-shot**: the list is cleared every `UI.draw`. So overlays
aren't each a `drawafter`; instead the engine keeps a **persistent per-addon list** and, each `tick`, queues
**one** afterdraw that paints the whole list. The frame loop runs `tick()` **then** `display()`/`ui.draw`
([`UILoop.Frame.run`](../../src/haven/UILoop.java)), so the afterdraw registered in `tick` fires that same
frame — **after `root.draw`**, i.e. **on top of the HUD** (which a widget added to `ui.root` before `GameUI`
could not be — see the 2a login/reload learning). The afterdraw's `g` is the full-screen root GOut.

### Gob overlays — a shared `GAttrib` + `PView.Render2D` per gob (the SpeakerIcon pattern)

[`LuaGobOverlay`](../../src/io/brodgar/addon/LuaGobOverlay.java) is a `GAttrib` that is also a
`RenderTree.Node` + `PView.Render2D`, modelled 1:1 on [`haven.SpeakerIcon`](../../src/haven/SpeakerIcon.java):
the render tree keeps it pinned over the gob in every camera and disposes it with the gob. Its 2D pass runs
once per frame from `PView.draw` → `list2d.draw` (`ScreenList`) — inside the **same `UI.draw` traversal** as
every widget, so a Lua draw callback here **never races** the tick. `draw` projects the anchor with
`Homo3D.obj2view(new Coord3f(0,0,15), state, Area.sized(g.sz())).round2()` and hands off to the engine.

- **One shared attrib per gob** serves *all* addons: it holds no addon state; on each draw it asks
  `AddonManager.paintGobOverlays` to re-check every addon's filter for that gob and paint the matches. So two
  addons overlaying the same gob need one attrib, and removing one addon's overlay just stops painting it.
- **A throttled sweep** (in `tick`, default **5 Hz** — `-Dhaven.addon.gobsweepsec`) attaches the attrib to
  each gob matching an active filter (like `SpeakerIcon.sweep`, but with dynamic Lua filters, so it is
  rate-limited). **Attach-only**: a gob that later stops matching keeps an *idle* attrib that draws nothing
  (the per-frame draw re-checks filters); the idle attribs are **detached wholesale** once no addon wants gob
  overlays (handle `:remove()` / teardown — reload-safe, since a reload keeps the same session/OCache).

### Threading & the CPU budget

Everything runs on the **one UI thread** (the frame loop is `tick → draw → swap` sequentially), so Lua tick
callbacks and Lua draw callbacks never overlap — no concurrent VM access. Every filter/draw call goes through
**`callLua`** (watchdog-armed per call, error-isolated, CPU-accounted). The **sweep** runs in `tick`, so its
filter time **is** covered by the soft per-tick CPU budget (D-018 layer 2) — the expensive all-gobs part is
watched. **Draw callbacks** (HUD overlay + gob overlay, like 2a `onDraw`) run in `UI.draw` *after* the tick's
budget window, so their time **escapes** the soft budget; only the hard per-call **instruction cap** guards a
runaway draw. Keep draw callbacks cheap — don't scan the world every frame (the harness refreshes its gob
count on a 1 s timer, not in the draw).

## Files

**Engine (`io.brodgar.addon`, all package-scoped — no `haven` edit):**
- **`LuaGOut`** *(new)* — the `g` draw wrapper, **extracted** from `LuaWidget` so widgets, HUD overlays and
  gob overlays share one canonical surface (D-013). `bind(GOut)`/`unbind()`; inert when unbound.
- **`LuaGobOverlay`** *(new)* — the per-gob `GAttrib`+`Render2D` overlay (the SpeakerIcon pattern).
- **`LuaWidget`** — refactored onto `LuaGOut` (its inline wrapper removed; behaviour identical).
- **`Addon`** — two owned-resource lists: `hudOverlays`, `gobOverlays` (like `subs`/`timers`/`widgets`, P2).
- **`AddonManager`** — the `hafen.ui.overlay`/`gobOverlay` facade + `HudOverlay`/`GobOverlay` regs; the HUD
  afterdraw (`paintHudOverlays`) queued from `tick`; the gob sweep (`sweepGobOverlays`) + paint
  (`paintGobOverlays`) + filter match (`gobFilterMatch`) + `detachGobOverlays`; teardown clears both lists.

**Zero `haven` edit** — every backing is public: `UI.drawafter`/`AfterDraw`, `GAttrib` (public ctor + `gob`),
`Gob.setattr/getattr/delattr`, `PView.Render2D`, `RenderTree.Node`, `Homo3D.obj2view`, `Area.sized`,
`GOut.sz()`. (Unlike `SpeakerIcon`, which reflects into another gob attribute, a generic overlay needs none.)

## Verification

- **Compile:** `ant hafen-client` → BUILD SUCCESSFUL.
- **Headless (21/21):** registration adds/removes from the owned list; the handle exposes `:remove()`; a
  non-function `overlay`/bad-`gobOverlay` arg is rejected; **string filter** substring hit/miss/no-name;
  **function filter** true/false; a filter that `error()`s is isolated to `false`; a nil-guarded filter is
  safe on a nameless gob; and the extended `hello/main.lua` parses under `Sandbox.create()`.
- **In-game (DoD) — pending.** Log in and check: a **top-centre readout + centre crosshair** paint on top of
  the HUD (`hafen.ui.overlay`); a **green marker + label** floats over your character's head, and over any
  other player, tracking as you move the camera (`hafen.ui.gobOverlay`); `:reload` (or disabling `hello`)
  makes **both vanish** with no leftover (the auto-teardown). The `hello` addon (**v0.14.0**) is the harness.

## Known gaps / deferred

- **Draw-time CPU budget** — draw callbacks escape the soft per-tick budget (frame-boundary accounting; same
  as 2a). The hard instruction cap still applies.
- **Sweep cost** — a gob that never matches has its filter re-evaluated every sweep (5 Hz); fine for typical
  scene sizes and cheap filters, and CPU-budgeted. A future optimisation could cache per-gob match results.
- **Anchor height** is fixed (head, z=15) — a per-overlay anchor/offset option can come later.
- **`g:image`** (icons/textures) still deferred (needs `Tex`/resource resolution), as in 2a.
- Off-screen/behind-camera gobs: overlays are naturally screen-clipped (matches `SpeakerIcon`); no extra
  frustum guard beyond the projection try/catch.
