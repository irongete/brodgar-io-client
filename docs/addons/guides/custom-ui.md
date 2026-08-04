# Custom UI

Your own pixels: a window the user can drag, a bare rectangle, or a layer painted over the HUD and the 3D
world. All of it is ungated, and all of it disappears cleanly when your addon does. To restyle the
*client's* surfaces instead of drawing your own, see [theming](theming.md).

## A window

[`hafen.ui():window()`](../api/ui/custom.md) gives you chrome, a caption and a draggable frame around
content you paint yourself. Build it when the world is up, and keep the handle:

```lua
local window

hafen.event():on("OnEnterWorld", function()
  window = hafen.ui():window()
    :title("Scout")
    :size(180, 48)
    :position(80, 120)
    :onDraw(function(g, w, h)
      g:color(220, 220, 220)
      g:text("players nearby: " .. hafen.world():gob():count("gfx/borka/body"), 6, 6)
    end)
    :onClose(function() hafen.log():write("closed") end)
end)
```

`hafen.ui():widget()` is the same thing without the chrome, for something that should not look like a
window. Every setter is optional, and what you get back is a [Widget](../api/ui/widget.md) — the same
type the client's own windows are, so `:position`, `:size`, `:visible` and `:destroy` all answer on it.
The difference between yours and the client's is
[ownership](../api/ui/widget.md#owned-vs-borrowed), and it decides which writes are allowed.

## Draw

Every draw callback receives `g`, [the drawing surface](../api/ui/drawing.md). Coordinates are local: the
widget's own top-left for a widget, the screen for a HUD overlay. Set a colour, then draw.

```lua
onDraw = function(g, w, h)
  g:color(0, 0, 0, 160)
  g:frect(0, 0, w, h)                     -- a dim panel behind the text
  g:color(255, 210, 120)
  g:text("hello", 6, 6)
  g:line(0, h - 1, w, h - 1)
end
```

`g` lives only for the length of the callback: stash it and draw later and nothing happens. To draw an
image you ship, load it once with [`hafen.asset`](../api/asset.md) — in `OnLoad`, never inside a draw —
and blit the handle with `g:image`. `g:resource(name, …)` draws the client's own art by name.

**Cost.** Geometry is nearly free; text is not, so the engine
[caches every rendered string](../api/ui/drawing.md#text-is-cached-across-frames) per addon. Redrawing the
same words in the same font every frame is one rasterisation total; a string whose characters change every
frame — a clock, a coordinate readout — is a rasterisation every frame. Budget a live readout by how often
its *text* changes, and round anything you do not need to the digit you do.

## Overlays

An overlay paints without being in the tree: nothing to place, nothing to size, nothing for the user to
drag. On the **HUD** it is a painter you build and end with `:destroy()`:

```lua
hafen.ui():overlay():onDraw(function(g, w, h)            -- over the whole HUD; w, h is the screen
  g:color(255, 255, 255)
  g:atext(os.date("%H:%M"), w - 8, 8, 1, 0)              -- anchored to the top-right corner
end)
```

Over a **game object** the verb is on the object — [`gob:overlay()`](../api/gob.md#overlays) — and you
name the gob rather than describing a set of them:

```lua
local function tag(gob)
  if gob:isPlayer() then gob:overlay():add("tag"):text("player"):color(0, 255, 0) end
end
hafen.event():on("GobAdded", tag)                         -- everyone who walks in...
for _, g in ipairs(hafen.world():gob():list()) do tag(g) end     -- ...and everyone already here
```

The label is drawn at that object's projected screen point, just above the head, and it follows the gob
because it is attached to it — no projection to do and nothing to poll. A `:draw(fn)` overlay gets that
point as `sx, sy` when you want to paint it yourself. Standing something **in** the world instead of over
it is [`hafen.render`](../api/render/README.md) or [`hafen.ghost`](../api/ghost.md).

## Input

Mouse callbacks are options on the widget, alongside `onDraw`: `onClick`, `onMouseUp`, `onMouseMove`,
`onWheel`, `onDrop`. Coordinates are widget-local, and every one of them ends with `mods`, the
`{shift, ctrl, alt}` state at press time, so a Shift-click is a branch rather than a second callback.

```lua
onClick = function(x, y, button, mods)
  if button == 3 then return end              -- leave the right button alone
  if mods.shift then reset() else step() end
  return true                                 -- truthy consumes the click
end
```

`onDrop` is the one that opts you into the client's own drag gesture: drag an action off the menu grid onto
your widget and you get a neutral descriptor for it, which you can draw with `g:resource` and persist with
[`hafen.store`](../api/store.md). Keyboard input is not a widget option — a
[hotkey](hotkeys-and-commands.md) is.

## What the client already built

Your window and the client's are the same kind of object, so the rest of `hafen.ui` is about *its* widgets
rather than yours: [naming one](../api/ui/selectors.md), [reading what is inside it](../api/ui/items.md),
[moving or hiding it](../api/ui/native.md), and
[standing your own window in its place](../api/ui/replace.md) — which is how you rebuild a piece of the
client's interface without reimplementing what it does. Start from a selector, and use the
[inspector](debugging.md#name-a-widget-you-are-pointing-at) to find one.

**Next:** [saved data](saved-data.md) — keeping the window's position, and everything else you learn.
