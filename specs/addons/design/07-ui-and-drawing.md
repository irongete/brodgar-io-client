# UI & Drawing

> **Status:** 🟠 Outline · **Spec:** AddOns
> **Related:** [06-lua-api.md](06-lua-api.md), [08-widget-replacement.md](08-widget-replacement.md),
> [24-gob-overlays.md](24-gob-overlays.md) — which **supersedes** §"World-space overlays over gobs" below

How addons create their own UI: custom widgets/windows, HUD overlays, world-space overlays over
gobs, input handling, and custom assets. Backed by [`Widget`](src/haven/Widget.java),
[`Window`](src/haven/Window.java), [`GOut`](src/haven/GOut.java), [`UI`](src/haven/UI.java).

## LuaWidget / LuaWindow

Two Java classes forward the widget lifecycle to Lua callbacks:

- **`LuaWidget extends Widget`** — a generic client-side widget. Overrides
  `tick`/`draw`/`mousedown`/`mouseup`/`mousemove`/`mousewheel`/`keydown` and forwards each to the
  addon's Lua callbacks (through the bridge, with error isolation + the watchdog).
- **`LuaWindow extends Window`** — a `LuaWidget` wrapped in the client's window chrome
  (title bar, drag, close), created via the `@RName("wnd")` machinery or directly.

Lua creation:
```lua
local win = hafen.ui.window{
  title = "My Panel",
  size  = { 200, 140 },          -- content size (scaled by UI.scale by the bridge)
  onDraw = function(g, w, h) ... end,
  onTick = function(dt) ... end,
  onClick = function(x, y, button, mods) ... end,   -- mods = {shift, ctrl, alt}  (D-040)
  onDrop  = function(x, y, drop) ... end,           -- something dropped on the widget (D-038)
}
win:move(100, 100); win:hide(); win:show(); win:pack()
```

### Drops (`onDrop`) — [D-038](../decisions/widgets-ui.md)

`LuaWidget` **implements [`DropTarget`](src/haven/DropTarget.java)**, so an addon widget can be a **drop
target** for the client's own drag gesture. When a "thing" is dropped over the widget, the bridge calls
`onDrop(x, y, drop)` (widget-local pixels); **returning truthy consumes** the drop. In v1 the only "thing"
is a **menu-grid action** ([`MenuGrid.Pagina`](src/haven/MenuGrid.java:63)) — the engine already dispatches
it generically ([`MenuGrid.mouseup`](src/haven/MenuGrid.java:588) → `DropTarget.dropthing(ui.root, ui.mc,
dragging)`, walked down the widget tree), so **no `haven` core edit** is needed. It arrives as a **neutral
descriptor** `drop = { kind = "pagina", res = "<resource name>" }`. Draw its icon with
[`g:resource(res, …)`](#the-gout-drawing-wrapper); persist `res` via `hafen.store`. *(Firing the dropped
action is not part of `onDrop` — that needs the deferred menu-ability primitive: a resolvable ability handle
+ a gated `use`. id-only paginae carry no stable `res` and are not reliably persistable.)*

- The handle `win` is a **bridge-owned proxy** ([P2](01-architecture.md)); creating it registers
  it in the addon's owned-resource registry so reload/disable destroys it.
- Placement: by default added to `ui.root`; `parent = "gameui"` adds under the HUD. Tree
  mutation happens under `synchronized(ui)` on the UI thread ([P5](01-architecture.md)).
- **Client-side only:** LuaWidgets are not bound to a server id, so they **cannot** `wdgmsg` to
  the server. That is correct for custom UI. Interacting with the game goes through
  `hafen.act` (gated) or, for replaced native widgets, by delegating to the real model
  ([08-widget-replacement.md](08-widget-replacement.md)).

## The GOut drawing wrapper

Addon draw callbacks receive a Lua wrapper `g` over [`GOut`](src/haven/GOut.java). Proposed
surface (maps 1:1 to `GOut` methods):

```lua
g:image(img, x, y [, w, h])         -- draw the addon's OWN png (a hafen.render.image handle)
g:resource(name, x, y [, w, h])     -- draw an engine .res image BY NAME (D-039)
g:text(str, x, y)                   -- draw text
g:atext(str, x, y, ax, ay)          -- anchored text
g:rect(x, y, w, h)                  -- outline rect
g:frect(x, y, w, h)                 -- filled rect
g:line(x1, y1, x2, y2, width)
g:poly(x1, y1, x2, y2, x3, y3, ...) -- filled convex polygon
g:prect(cx, cy, ..., fraction)      -- pie/progress wedge (cooldowns, meters)
g:color(r, g, b, a) / g:color()     -- set/reset draw color
```

- **`g:image` vs `g:resource` ([D-039](../decisions/widgets-ui.md)).** `g:image` blits the addon's **own** PNGs (loaded via
  `hafen.render.image`, D-034); `g:resource(name, …)` blits an **engine `.res`** by name — the resource's default
  image layer (`Resource.imgc`), resolved async + cached + `Loading`-guarded (draws nothing until ready). Use it
  to render real game icons, e.g. the `res` a widget receives from [`onDrop`](#drops-ondrop--d-038). It draws the
  **static icon only** — a live sprite / cooldown sweep is the deferred menu-ability primitive (`g:ability`). The
  native empty-slot square (`Inventory.invsq`) is a code-built `TexI`, not a plain `.res`, so an addon draws its
  own slot background.

- Colors accept `{r,g,b,a}` 0–255 or a named palette. The wrapper resets state per callback so
  addons can't corrupt the client's draw pipeline.
- Text uses the client's text foundry; rich/styled text is a later addition.

## HUD overlays (no widget)

For "paint on top of the HUD" without owning a widget:

```lua
local ov = hafen.ui.overlay(function(g) ... end)   -- returns a handle
ov:remove()                                        -- also auto-removed on teardown
```

**Implementation note (verified).** [`UI.drawafter`](src/haven/UI.java:365) is **one-shot** — the
list is cleared every frame in [`UI.draw`](src/haven/UI.java:391). So overlays are **not**
registered with `drawafter` directly. Instead the engine keeps a **persistent overlay list** and
paints it from the addon-root widget's `draw` each frame; `hafen.ui.overlay` adds to that list,
`:remove()` (and teardown) removes from it. This makes disable/reload trivially clean
([05-lifecycle-and-reload.md](05-lifecycle-and-reload.md)).

## World-space overlays over gobs

> **SUPERSEDED by [24-gob-overlays.md](24-gob-overlays.md)** (shipped as 038-gob-overlays). The
> `SpeakerIcon` mechanism below is kept — it is `LuaGobOverlay` — but the *filter form* and its sweep are
> gone: the verb is on the gob (`gob:overlay(key, spec)`), the state lives on the gob, and `hafen.ui.gobOverlay`
> reads `nil`. The section is left as written for the record.

For labels/markers pinned above game objects in the 3D view (health bars, names, timers),
model on the existing voice **`SpeakerIcon`** pattern: a `GAttrib` that also implements
`RenderTree.Node` + `PView.Render2D`, attached to a gob and disposed with it, swept once per
frame.

```lua
hafen.ui.gobOverlay(
  function(gob) return gob.name:find("kritter") end,   -- filter
  function(g, gob, sx, sy) g:text(gob.name, sx, sy) end -- draw at the gob's screen pos
)
```

- The bridge manages attach/detach of the underlying `GAttrib` per matching gob and ties them to
  the addon for teardown.
- This is the correct way to "draw on the world" — the 3D `MapView` itself is not replaceable
  ([N2](00-vision-scope.md)).

## Input

- **Widget input:** LuaWidget callbacks (`onClick`, `onKey`, …) receive events already
  translated to widget-local coordinates. Returning truthy **consumes** the event (maps to
  returning `true` from the Java handler). Modal capture (drag) via a `grab` helper.
- **Modifiers ([D-040](../decisions/widgets-ui.md)):** the mouse callbacks carry a trailing `mods = {shift, ctrl, alt}`
  table (from `ui.modflags()`): `onClick(x, y, button, mods)`, `onMouseUp(x, y, button, mods)`,
  `onMouseMove(x, y, mods)`, `onWheel(x, y, amount, mods)`. Additive/back-compatible; mirrors the `mods` that
  [`hafen.hook.grab`](13-hooks-and-interception.md) already delivers, so the addon can branch at **press time**
  (e.g. Shift+drag to move a bar).
- **Global hotkeys:** `hafen.client:options():keybindings():register` over [`KeyBinding`](src/haven/KeyBinding.java) — remappable
  and shown in the client's keybind panel; handlers are addon-owned.

## Custom assets (resources)

Addons may ship `.res` files under `<addon>/res/`. Loading options (the client's resource system
already supports classpath and local-dir sources):

- The engine registers each addon's `res/` as a resource source (or provides
  `hafen.resource(ADDON, name)` that loads addon-relative `.res`).
- Alternatively, images can be provided as raw pixel data and wrapped into a `Tex` by the
  bridge (avoids the `.res` format for simple icons) — TBD.

Details of resource-source registration and whether addon resources can shadow client resources:
TBD in review.

## Open items

- Exact `GOut` wrapper surface (which methods, color model, text styling).
- Resource loading ergonomics (`.res` vs raw images).
- Layout helpers (anchors, auto-size) beyond raw coordinates.
- Whether `LuaWindow` position persists via saved-vars automatically.
