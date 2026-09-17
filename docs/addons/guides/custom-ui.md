# Custom UI

Build a window or a bare canvas and paint it. Lay out a panel of the client's own controls. Paint over the HUD, a widget or a game object. Take mouse input. All unprotected, all torn down with your addon. To restyle the client's own surfaces instead, see [theming](theming.md).

---

## A window

[`hafen.ui():window()`](../api/ui/custom.md) is a framed, captioned, draggable window around content you paint. `hafen.ui():widget()` is the same without the chrome. Build it when the world is up, and keep the handle. It is a [Widget](../api/ui/widget.md), the type the client's own windows are. [Ownership](../api/ui/writes.md#owned-vs-borrowed) decides which writes answer.

```lua
local scout_window
hafen.event():on("SessionEnteredWorld", function(session)
  scout_window = hafen.ui():window():title("Scout"):size(180, 48):position(80, 120)
  scout_window:on("Draw", function(draw_event)
    local graphics = draw_event:g()
    graphics:color(220, 220, 220)
    graphics:text("players nearby: " .. session:world():gob():count("gfx/borka/body"), 6, 6)
  end)
  scout_window:on("Close", function(close_event)
    close_event:preventDefault()             -- the X hides; the window and its handle stay
    scout_window:visible(false)
  end)
end)
```

## Draw

A draw handler receives an event answering `:g()`, [the drawing surface](../api/ui/drawing.md), and `:w()`/`:h()`, the area to paint, in the widget's own coordinates.

```lua
scout_window:on("Draw", function(draw_event)
  local graphics, width, height = draw_event:g(), draw_event:w(), draw_event:h()
  graphics:color(0, 0, 0, 160)
  graphics:frect(0, 0, width, height)                     -- a dim panel behind the text
  graphics:color(255, 210, 120)
  graphics:text("hello", 6, 6)
  graphics:line(0, height - 1, width, height - 1)
end)
```

| Rule | Detail |
|---|---|
| `g` lives for the callback | Stashed and used later it draws nothing. |
| Images | Load a file you ship once with [`hafen.asset`](../api/asset/README.md), in `Load` and never inside a draw, and blit the handle with `g:image`. `g:resource(name, …)` draws the client's own art by name. |
| Cost | Geometry is cheap. Text is [cached per string and font](../api/ui/drawing.md#text-is-cached-across-frames), so the same words every frame rasterise once. A string that changes every frame (a clock, a coordinate readout) rasterises every frame. Round a live readout to the digit you need. |

## A panel of controls

A window holding the client's own [controls](../api/ui/controls/README.md) is laid out by a [column](../api/ui/column.md). Parent each control into it and it stacks them, sizes itself to them and re-lays when one changes. A row puts two on one line. A column with a left `padding` is an indent. [`:enabled(false)`](../api/ui/writes.md#enabled-and-disabled) on an inner column greys a whole group.

```lua
local harvest_window = hafen.ui():window():title("Harvest"):position(80, 120)
local panel = hafen.ui():column():gap(4):parent(harvest_window):position(0, 0)

local header_row = hafen.ui():row():gap(4):parent(panel)
hafen.ui():image():source("gfx/hud/chr/farming"):size(24, 24):parent(header_row)
hafen.ui():check():parent(header_row):text("Only ripe")

local advanced_switch = hafen.ui():check():parent(panel):text("Advanced")
local group = hafen.ui():column():gap(4):parent(panel):stock{ padding = {16, 0, 0, 0} }
hafen.ui():check():parent(group):text("Also unripe")
hafen.ui():entry():parent(group):size(120)
group:enabled(false)
advanced_switch:on("Changed", function(checked) group:enabled(checked) end)

harvest_window:pack()                      -- the window fits the panel, and follows it from here on
```

Nothing inside the panel is positioned or measured by hand. A child of a column has no `:position` to write. `:pack()` reads the panel's box, so a row added later grows the window. A list too long for the window goes in a [scroll](../api/ui/controls/interactive.md#scroll) with a column inside — [a panel, composed](../api/ui/column.md#a-panel-composed).

## Let somebody else restyle it

Pixels laid down in `Draw` are final. The same look declared as a stock renders identically and stays replaceable by a theme.

```lua
local bar = hafen.ui():widget():name("bar")
bar:stock{ bg = {color = {0, 0, 0, 90}}, border = {box = "gfx/hud/wnd", mode = "tile"} }
```

| Rule | Detail |
|---|---|
| `:name(word)` opens the door | The client writes your addon's id in front, so a theme names it back as `["[name=youraddon/bar]"]`. Unnamed, every bare widget looks alike to a selector. |
| `:stock(t)` sets the starting point | The bottom of the [cascade](../api/ui/style/README.md#the-cascade): every rule beats it per property. A rule of your own would sit above every theme. |
| Neither is required | Name what is part of how your addon looks. Leave scaffolding unnamed, since a name is a published contract. |
| Client-built widgets need neither | `:window()`, `:button()`, `:label()` and the rest are client widgets, reached by the [site keys](../api/ui/style/keys.md#site-keys). |

The reads and refusals are on [custom](../api/ui/custom.md#naming-and-dressing-your-own-surfaces).

## Overlays

An overlay paints without being in the tree: nothing to place, size or drag. One vocabulary serves the [HUD](../api/ui/overlay.md), [one widget](../api/ui/overlay.md#over-one-widget) and a [game object](../api/overlay.md): keyed decorations you add, read back and remove.

```lua
hafen.ui():overlay():add("clock"):draw(function(graphics, width, height)   -- width, height is the screen
  graphics:color(255, 255, 255)
  graphics:atext(os.date("%H:%M"), width - 8, 8, 1, 0)                     -- anchored top-right
end)
-- hafen.ui():overlay():remove("clock") takes it off

local function tag(gob)
  if gob:player() then gob:overlay():add("tag"):text("player"):color{0, 255, 0} end
end
hafen.event():on("GobAdded", tag)                                          -- everyone who walks in...
for _, other_gob in ipairs(hafen.session():current():world():gob():list()) do tag(other_gob) end   -- ...and everyone here

local backpack = hafen.session():current():ui():inventory()
backpack:overlay():add("frame"):draw(function(graphics, width, height)      -- width, height is the grid
  graphics:color(255, 90, 90)
  graphics:rect(0, 0, width, height)
end)
```

| Receiver | What the painter gets |
|---|---|
| `hafen.ui():overlay()` | The screen, in root design pixels. |
| `gob:overlay()` | The object's projected screen point, `screen_x, screen_y`. A label is drawn above the head by default and `:height(0)` stands it on the ground. It follows the gob with no projection to do. Standing something in the world is [`hafen.virtual`](../api/virtual/README.md). |
| `widget:overlay()` | That widget's own box, clipped to it and hidden with it. The one for a button, a slot or an item icon, since nothing is searched per frame. |

## Input

Mouse input is more [`:on(key, fn)`](../api/ui/widget.md#subscribing) keys on any widget, painted or the client's: `MouseDown`, `MouseUp`, `MouseMove`, `Wheel`, `Removed`. Coordinates are widget-local. `event:preventDefault()` consumes the input. No handler's return value is read.

```lua
scout_window:on("MouseDown", function(press)
  if press:button() == 3 then return end                       -- leave the right button alone
  if hafen.ui():mouse():shift() then hafen.log():write("shift-click") end   -- modifiers are the pointer's, read live
  press:preventDefault()                                       -- stop the widget seeing it too
end)
```

A surface of yours also answers the keys below. Keyboard input is a [hotkey](hotkeys-and-commands.md), not a widget key.

| Key | Fires |
|---|---|
| `Update` | Every frame, on the step. |
| `Drop` | Something was dropped on it: `event:thing()` is the neutral descriptor, drawable with `g:resource` and persistable with [`hafen.store`](../api/store/README.md). |
| `Close` | The X was pressed. It destroys the window unless [cancelled](../api/ui/custom.md#the-close-button-destroys-the-window-unless-you-say-otherwise). |
| `Resized` | The user let go of the [corner grip](../api/ui/custom.md#letting-the-user-resize-a-window-of-yours). |

## What the client already built

Your window and the client's are one kind of object. The rest of `hafen.ui` is about the client's. [Naming one](../api/ui/selectors.md). [Reading what is inside it](../api/ui/items.md). [Moving, hiding or letting the user drag and size it](../api/ui/native.md). [Standing your own window in its place](../api/ui/replace.md). Start from a selector, found with the [inspector](debugging.md#name-a-widget-you-are-pointing-at).

**Next:** [saved data](saved-data.md) — keeping the window's position, and everything else you learn.
