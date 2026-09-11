# Custom UI

Your own pixels: a window the user can drag, a bare rectangle, or a layer painted over the HUD and the 3D
world. All of it is unprotected, and all of it disappears cleanly when your addon does. To restyle the
*client's* surfaces instead of drawing your own, see [theming](theming.md) — and for the third way, where
you build the surface and let **somebody else** restyle it, see [below](#let-somebody-else-restyle-it).

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
[ownership](../api/ui/writes.md#owned-vs-borrowed), and it decides which writes are allowed.

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
image you ship, load it once with [`hafen.asset`](../api/asset/README.md) — in `Load`, never inside a draw —
and blit the handle with `g:image`. `g:resource(name, …)` draws the client's own art by name.

**Cost.** Geometry is nearly free; text is not, so the engine
[caches every rendered string](../api/ui/drawing.md#text-is-cached-across-frames) per addon. Redrawing the
same words in the same font every frame is one rasterisation total; a string whose characters change every
frame — a clock, a coordinate readout — is a rasterisation every frame. Budget a live readout by how often
its *text* changes, and round anything you do not need to the digit you do.

## Let somebody else restyle it

**Painting is final; a declared look is a default anybody can beat.** Pixels you lay down in `Draw` are
yours and no rule can reach into a callback. The *same look* declared as a **stock** renders identically and
stays replaceable — so the question is not "paint or declare", it is which parts of your surface you want a
theme to be able to change.

```lua
local bar = hafen.ui():widget():parent(hud):name("bar")
bar:stock{ bg = {color = {0, 0, 0, 90}}, border = {box = "gfx/hud/wnd", mode = "tile"} }
```

- **`:name(s)` is the one that opens the door.** The engine writes your addon's id in front, so a theme
  names it back as `["[name=youraddon/bar]"]`. Without a name nothing can single your surface out — every
  bare widget every addon builds looks alike to a selector.
- **`:stock(t)` only sets the starting point.** It sits at the *bottom* of the
  [cascade](../api/ui/style/README.md#the-cascade), so every rule beats it, per property. Put your default
  here rather than in a rule of your own, which would sit above every theme.
- **Neither is required**, and a surface with neither is exactly the bare rectangle it always was. Name what
  is part of how your addon *looks*; leave the scaffolding — containers, drag handles, hit areas — unnamed,
  because a name is a published contract and renaming it breaks somebody's theme.
- **Widgets built from the client's own pieces need none of this.** `:window()`, `:button()`, `:label()`
  and the rest *are* client widgets, so a theme's [site keys](../api/ui/style/keys.md#site-keys) already
  reach them.

The whole of it, with the reads and the refusals, is on
[custom](../api/ui/custom.md#naming-and-dressing-your-own-surfaces).

## Overlays

An overlay paints without being in the tree: nothing to place, nothing to size, nothing for the user to
drag. It is the same vocabulary on the [**HUD**](../api/ui/overlay.md), on **one widget** and on a **game
object** — keyed decorations you add, read back and remove. On the HUD you name the screen:

```lua
hafen.ui():overlay():add("clock"):draw(function(g, w, h)  -- over the whole HUD; w, h is the screen
  g:color(255, 255, 255)
  g:atext(os.date("%H:%M"), w - 8, 8, 1, 0)               -- anchored to the top-right corner
end)
-- ...and hafen.ui():overlay():remove("clock") takes it off again
```

Over a **game object** the verb is on the object — [`gob:overlay()`](../api/overlay.md) — and you
name the gob rather than describing a set of them:

```lua
local function tag(gob)
  if gob:player() then gob:overlay():add("tag"):text("player"):color{0, 255, 0} end
end
hafen.event():on("GobAdded", tag)                         -- everyone who walks in...
local w = hafen.session():current():world()
for _, g in ipairs(w:gob():list()) do tag(g) end          -- ...and everyone already here
```

The label is drawn at that object's projected screen point — just above the head by default, and
`:height(0)` stands it on the ground under the object instead — and it follows the gob because it is
attached to it: no projection to do and nothing to poll. A `:draw(fn)` overlay gets that point as `sx, sy`
when you want to paint it yourself. Standing something **in** the world instead of over it is
[`hafen.virtual`](../api/virtual/README.md).

Over **one widget** the verb is on the widget — [`widget:overlay()`](../api/ui/overlay.md#over-one-widget)
— and the painter is handed that widget's own box, clipped to it and hidden with it:

```lua
local pack = hafen.session():current():ui():inventory()
pack:overlay():add("frame"):draw(function(g, w, h)   -- w, h is the grid, not the screen
  g:color(255, 90, 90)
  g:rect(0, 0, w, h)
end)
```

That is the one to reach for when the thing you want to decorate is a button, a slot or an item icon: you
name the widget, so nothing is searched and no rectangle is re-derived every frame.

## Input

Mouse input is five more [`:on(key, fn)`](../api/ui/widget.md#subscribing) keys, the same door `Draw` is —
and they answer on **any** widget, not only one you painted: `MouseDown`, `MouseUp`, `MouseMove`, `Wheel`
and `Removed`. Coordinates are widget-local, and `ev:preventDefault()` is the one way to consume the
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

A window built with [`:window()`/`:widget()`](../api/ui/custom.md) answers four more of its own: `Update`
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
