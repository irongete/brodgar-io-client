# 044 — Spatial UI (widgets standing in the world)

> Caps lifted for this feature (maintainer directive). Builds on
> [043-vr-namespace](../043-vr-namespace/), which settles where this lands and must ship first.

## What

A widget stops being drawn flat on the UI layer and is drawn **in the world**.
`hafen.vr():widget()` is the fourth collection of the VR section, beside `:ghost()`, `:sprite()`
and `:object()`, and it takes the same two anchors they do:

```lua
local win = hafen.ui():window():title("Ore Smelter"):size(128, 96)
win:on("Draw", function(ev) ev:g():text("Fuel: 3/8", 8, 8) end)

hafen.vr():widget():add(win, smelter):facing("camera")          -- standing on the smelter
hafen.vr():widget():add(board, p):facing("camera")              -- floating in your farm
hafen.vr():widget():add(hafen.ui():find("window[title=Ore Smelter]"), smelter)
```

It takes any [Widget](../../../docs/addons/api/ui/widget.md) — what `hafen.ui():window()` or a
control builder returns, or **one of the client's own** — and stands it as a quad: `Draw`/`Tick`
render it into a texture instead of onto the screen, and clicks on that quad forward into its own
`MouseDown`/`MouseUp`/`MouseMove`/`Wheel`, in widget-local pixels, exactly as on screen.

**One door, because 043 made the anchor an argument.** There is no sixth `gob:overlay()` kind —
that section no longer creates world-space things at all.

## The rule the whole feature is held to: transparency

**If it works on screen, it works in the world.** That is the acceptance bar, not an aspiration.

The addon writes the same code it already writes. The same `:on("Draw", …)`, the same
`:on("MouseDown", …)`, the same button callbacks, the same controls from
[040](../040-ui-controls/), the same [stylesheet](../../../docs/addons/api/ui/style/README.md)
rules and themes. Clicking a button in a world-rendered window runs the same handler it ran on
screen. Dropdowns open, tooltips appear, hover states light up, text entries take the keyboard,
and items go in and out — putting ore into a smelter window standing on the smelter is the case
this feature exists for.

**Nothing is a new concept.** One new collection, `hafen.vr():widget()`, on a section the addon
author already knows from placing sprites. There is nothing else to learn.

**Moving the window does not carry over.** A widget anchored to a gob has no place of its own —
its place is the gob's — so the title-bar drag is inert while it stands there, exactly as it is
for a sprite. A widget standing at a **point** carries `:position(p, a)` like any other free
entity, so it can be moved; the title bar still does not move it, because that is a flat-UI
gesture and its place is now a world coordinate.

### What that demands of the implementation

Transparency is only true if the surface in the world is a **real UI root**, not a texture with
clicks forwarded into it. The client's hit-testing, focus, hover, popup placement, tooltips and
drag gesture are all resolved relative to a root; a widget hosted under a spatial root inherits
every one of them by construction. Re-implementing them one by one against a texture is how each
of them ends up subtly broken in a different way.

So the surface is a root, and standing a widget reparents it into that root. The widget stays
**live** in every other sense — still bound to its server id, still receiving updates, still
filling with items — exactly as a hidden native widget does today. `plan.md` verifies the one
thing this rests on: that the client resolves popups and tooltips against the nearest root rather
than a fixed `ui.root`. If it turns out to be fixed, that is the single seam this feature needs
there.

### Standing the client's own windows

Taking a native window off the flat UI is the same family of write as
[`:position`/`:size`/`:visible`](../../../docs/addons/api/ui/native.md) and
[`w:replace(view)`](../../../docs/addons/api/ui/replace.md): **a layer over the client's state,
never a write into it.** Reload, disable or remove the entity and the window comes back to the
flat UI under 031's rule (D-070) — *it ends up as the user was seeing it*.

It is **ungated**. The clicks that reach the server are the ones the user makes with their own
hand; only where the button is drawn has changed. Same footing as moving a native window.

### Going back: standing a widget remembers where it came from

`hafen.vr():widget():remove(x)` puts it back, and so do `:reload` and disable. One rule covers
owned and borrowed alike: **standing a widget records where it was, removing it puts it back
there.** A native window returns to the flat UI under D-070. A window your addon built and stood
immediately goes back to its default parent — visible on the flat UI, which is where an unstood
window of yours belongs; hide or destroy it yourself if that is not what you want.

This matters because **swapping is the normal case**: an addon that shows the server's real window
while it is open and its own panel while it is closed does this several times a minute.

### It composes with `replace`

```lua
hafen.ui():on("window[title=Ore Smelter]", "appear", function(w)
  local mine = hafen.ui():window():size(120, 90)
  w:replace(mine)                                    -- the native toggle now drives yours
  hafen.vr():widget():add(mine, smelter):facing("camera")
end)
```

`replace` decides *what stands in for the native window on the flat UI*; standing it decides
*where that thing is drawn*. Different questions, so they do not fight — but the ordering and the
teardown interaction are asserted rather than assumed, including the awkward case: the server
destroying the replaced window while its stand-in is standing in the world.

## Orientation: the third mode arrives

043 ships `:facing()` with the two modes that already existed. This feature adds the one the
spatial framing actually asks for:

| Mode | What it is | Reach for it when |
|---|---|---|
| `"fixed"` | a quad in the world at the angle `:rotate(a)` sets | it belongs to a place — a sign on a wall, a plaque on a building |
| **`"camera"`** | **new** — a quad **in the world** that turns to face the viewer, in yaw *and* pitch | the spatial panel: real world size, perspective, occluded by terrain, shrinking with distance, and always square-on |
| `"screen"` | a constant-size blit at the anchor's projected point, drawn over the scene | it must stay legible at any distance — a nameplate, a status strip |

Pitch is not a detail: this client looks down at the world from an angle, so an upright panel is
foreshortened and hard to read while a camera-facing one is not. `:rotate(a)` sets the angle in
`"fixed"` and is stored-but-unused in the other two.

**`"camera"` is still world geometry, and that simplifies the feature rather than complicating
it**: the ordinary 3D pick already resolves it, so it is interactive through the same path
`"fixed"` uses, with none of the click-through problem a screen-space blit has. Only `"screen"`
needs a hit-test of its own, and that one is a rectangle in pixels — the easiest of the three.
The new mode reaches **sprites too**, since they share the entity core: an image that faces you
but keeps its world size is something no addon can ask for today.

## Performance

An extra draw pass per standing widget (bind its texture, run `Draw`, unbind), so cost is made
proportional to what changed:

- **Redraw on dirty, not every frame** — re-upload only when content changed, the principle
  [026-text-cache](../026-text-cache/) already applies to text. A static panel costs one upload.
- **Frustum culling** — a surface outside the camera view is not drawn, reusing the culling the
  engine already applies to gobs and sprites. `Tick` keeps firing while culled (it is logic, not
  drawing); only `Draw` stops, so nothing inside the widget drifts out of date.

**Measured, not assumed**: a spatial-widget counter (uploads performed, frames elapsed, live and
culled surfaces) joins the render/loader/text-cache counters [019-profiling](../019-profiling/)
already exposes, so a suite reads it back and asserts on it instead of eyeballing a framerate —
the way 036 proved its own draw cost before closing.

## Acceptance Criteria

Each `:t044-<X>` suite proves this through `hafen.*`, **automated wherever a counter or an event
can answer it**; `[manual]` only where a human is genuinely required.

- **044.1 — it renders, and only when it must.** `hafen.vr():widget():add(w, p)` stands one; its
  `Draw` fires; the widget is gone from the flat UI's hit-testing (`hafen.ui():at()`) while still
  in the tree and `:exists()`; content unchanged over N ticks holds the upload counter at 1;
  changing a label bumps it by exactly 1; 026's text cache is confirmed still hit from the
  offscreen `GOut`.
- **044.2 — both anchors, and the collection.** `:add(w, gob)` stands on a game object and tracks
  it; `:add(w, p)` stands at a point and carries `:position(p, a)`; `:list`/`:count`/`:find`/
  `:remove` answer; a gob-anchored one dies with its gob; a non-widget first argument raises
  naming the widget builders; `hafen.vr():list()` includes standing widgets alongside the other
  three kinds.
- **044.3 — `"camera"`, on widgets and on sprites.** `:facing("camera")` reads back and no longer
  raises; it is a world quad — it shrinks with distance and is occluded, unlike `"screen"`; a
  click lands in **all three** modes; the mode reaches a sprite as well as a widget. `[manual]`:
  rotate the camera and confirm `"camera"` stays square-on while `"fixed"` does not.
- **044.4 — input, asserted numerically.** A synthetic click at a known point fires the widget's
  `MouseDown`/`MouseUp` with the expected widget-local `x, y` — not merely "an event fired";
  `MouseMove` and `Wheel` likewise; a button's callback runs; a click that misses still reaches
  the world beneath; `ev:preventDefault()` still cancels. Driven through the same internal
  dispatch a real click takes, so no click hardware is needed.
- **044.5 — transparency: focus, popups, tooltips.** A standing text entry takes keyboard focus
  when clicked and typed text arrives; a dropdown opens its list **inside** the surface rather
  than on the flat UI, asserted by where the popup's root resolves; a tooltip resolves against the
  surface; hover state changes on a standing button.
- **044.6 — native windows, going back, and `replace`.** The client's own window stands, stays
  live and server-bound, and returns to the flat UI on `:remove`, `:reload` and disable under
  D-070's rule, in both the was-visible and was-hidden cases. An owned widget goes back to its
  default parent. A second addon standing the same window is refused naming the first. Standing is
  confirmed **ungated**. And `replace` composes: a stand-in view stands in the world, is driven by
  the native toggle, and survives the server destroying the window it replaced.
- **044.7 — culling and the ends of a surface.** With the anchor off-camera the upload counter
  stays flat while a `Tick` counter inside the widget keeps rising; `:remove` or the gob leaving
  releases the surface; re-adding does not error; the server destroying a standing window removes
  it from the collection cleanly; `:reload` leaves the live-surface count at exactly 0.
- **044.8 — the example addon.** The maintainer's own scenario, and it is **entirely
  event-driven**: watch for the smelter's window to appear, stand it on the smelter; when the
  server closes it, swap in your own panel built from the contents you snapshotted; when it
  appears again, swap back. No distance checks, no timers — only widget open and close, which is
  the shape 042 left the area in. `[manual]`: it looks right, clicking feels like clicking a
  normal window, walking away and back swaps cleanly, and ore goes into it and comes back out.

**Verification** (per `AREA.md`): `ant hafen-client` → full client restart → `:t044-1` .. `:t044-8`.

## Not part of this system

Refused, with an error — not deferred:

- **A spatial surface inside another spatial surface.** Render recursion with no use case. A
  widget that stands *another* widget on *another* anchor is fine — those are two siblings.
- **Controls that pop up at the UI root** are *not* refused; they are the point of 044.5. What is
  out is redirecting a popup that has genuinely escaped to the flat UI, if 044.5 finds one the
  root rule cannot reach.

Separate features if ever wanted: shaders or post-processing over the surface, spatial audio, and
the two gaps 043 filed on the ROADMAP — world-space text and world-space shapes.

## Context Files

- `specs/addons/043-vr-namespace/` — the namespace this lands in; **read its spec first**
- `docs/addons/api/vr/README.md` (as 043 leaves it) — the section and its collection grammar
- `docs/addons/api/ui/custom.md`, `ui/widget.md`, `ui/native.md`, `ui/replace.md` — the Widget,
  input and native-hosting surfaces reused unchanged
- `docs/addons/api/ui/controls/README.md`, `ui/lists.md` — the controls transparency must cover
- `docs/addons/api/client/profiling/counters.md` — where the new counter joins the rest
- `specs/addons/design/07-ui-and-drawing.md` — LuaWidget/LuaWindow, the Draw event, GOut wrapper
- `specs/addons/031-window-lifecycle/` — D-070, the restore rule native standing inherits
- `specs/addons/026-text-cache/`, `019-profiling/` — the dirty-cache and counter precedents
- `specs/codebase/world-3d.md` — the client-only entity core, the textured world quad recipe, the
  pick pass; extended by 043 if that feature had to read past it
- `specs/codebase/widgets.md` — widget draw/tick dispatch and root resolution; if it does not
  cover popup targeting, read the source and have `plan.md` list the extension for `/end`
