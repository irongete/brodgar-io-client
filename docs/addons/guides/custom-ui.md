# Custom UI

Your own pixels: a window the user can drag, a bare rectangle, or a layer painted over the HUD and the 3D
world. All of it is unprotected, and all of it disappears cleanly when your addon does. To restyle the
*client's* surfaces instead of drawing your own, see [theming](theming.md).

## A window

[`hafen.ui():window()`](../api/ui/custom.md) gives you chrome, a caption and a draggable frame around
content you paint yourself. Build it when the world is up, and keep the handle:

```lua
local window

hafen.event():on("SessionEnteredWorld", function(s)
  window = hafen.ui():window():title("Scout"):size(180, 48):position(80, 120)

  window:on("Draw", function(ev)
    local g = ev:g()
    g:color(220, 220, 220)
    g:text("players nearby: " .. s:world():gob():count("gfx/borka/body"), 6, 6)
  end)
  window:on("Close", function() hafen.log():write("closed") end)
end)
```

`hafen.ui():widget()` is the same thing without the chrome, for something that should not look like a
window. Every setter is optional, and what you get back is a [Widget](../api/ui/widget.md) — the same
type the client's own windows are, so `:position`, `:size`, `:visible` and `:destroy` all answer on it.
The difference between yours and the client's is
[ownership](../api/ui/widget.md#owned-vs-borrowed), and it decides which writes are allowed.

## Draw

Every draw handler receives `ev`, answering `:g()` — [the drawing surface](../api/ui/drawing.md) — and
`:w()`/`:h()`, the area to paint. Coordinates are local: the widget's own top-left for a widget, the screen
for a HUD overlay. Set a colour, then draw.

```lua
window:on("Draw", function(ev)
  local g, w, h = ev:g(), ev:w(), ev:h()
  g:color(0, 0, 0, 160)
  g:frect(0, 0, w, h)                     -- a dim panel behind the text
  g:color(255, 210, 120)
  g:text("hello", 6, 6)
  g:line(0, h - 1, w, h - 1)
end)
```

`g` lives only for the length of the callback: stash it and draw later and nothing happens. To draw an
image you ship, load it once with [`hafen.asset`](../api/asset.md) — in `Load`, never inside a draw —
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

Over a **game object** the verb is on the object — [`gob:overlay()`](../api/overlay.md) — and you
name the gob rather than describing a set of them:

```lua
local function tag(gob)
  if gob:isPlayer() then gob:overlay():add("tag"):text("player"):color{0, 255, 0} end
end
hafen.event():on("GobAdded", tag)                         -- everyone who walks in...
local w = hafen.session():current():world()
for _, g in ipairs(w:gob():list()) do tag(g) end          -- ...and everyone already here
```

The label is drawn at that object's projected screen point, just above the head, and it follows the gob
because it is attached to it — no projection to do and nothing to poll. A `:draw(fn)` overlay gets that
point as `sx, sy` when you want to paint it yourself. Standing something **in** the world instead of over
it is [`hafen.vr`](../api/vr/README.md).

## Input

Mouse input is five more [`:on(key, fn)`](../api/ui/widget.md#subscribing) keys, the same door `Draw` is —
and they answer on **any** widget, not only one you painted: `MouseDown`, `MouseUp`, `MouseMove`, `Wheel`
and `Destroy`. Coordinates are widget-local, and `ev:preventDefault()` is the one way to consume the
input; no handler's return value is ever read.

```lua
window:on("MouseDown", function(ev)
  if ev:button() == 3 then return end                       -- leave the right button alone
  if hafen.ui():mouse():shift() then reset() else step() end
  ev:preventDefault()                                        -- stop the widget seeing it too
end)
```

Reading a modifier key is `hafen.ui():mouse():shift()`/`:ctrl()`/`:alt()`, live, from inside the handler —
an input `ev` carries none of its own, since the pointer already answers them at any time.

A window built with [`:window()`/`:widget()`](../api/ui/custom.md) answers four more of its own: `Tick`
every frame, `Drop` when the client's drag gesture drops something on it (`ev:thing()` is the neutral
descriptor, drawable with `g:resource` and persistable with [`hafen.store`](../api/store.md)), and `Close`
on the window's close button. Keyboard input is not a widget option — a
[hotkey](hotkeys-and-commands.md) is.

## What the client already built

Your window and the client's are the same kind of object, so the rest of `hafen.ui` is about *its* widgets
rather than yours: [naming one](../api/ui/selectors.md), [reading what is inside it](../api/ui/items.md),
[moving it, hiding it or letting the user drag and size it](../api/ui/native.md), and
[standing your own window in its place](../api/ui/replace.md) — which is how you rebuild a piece of the
client's interface without reimplementing what it does. Start from a selector, and use the
[inspector](debugging.md#name-a-widget-you-are-pointing-at) to find one.

**Next:** [saved data](saved-data.md) — keeping the window's position, and everything else you learn.
