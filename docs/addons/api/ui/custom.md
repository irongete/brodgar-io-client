# hafen.ui: Your Own Windows and Widgets

A builder makes a surface of your own, a framed window or a bare canvas. It is configured by chained setters, painted on `Draw`, and torn down with your addon.

```lua
local clock_window = hafen.ui():window()
  :title("Clock")
  :size(160, 40)
  :position(50, 50)

clock_window:on("Draw", function(draw_event)
  local graphics = draw_event:g()
  graphics:color(255, 255, 0)
  graphics:text(string.format("%d", hafen.time():clock() or 0), 6, 12)
end)

clock_window:position(320, 200)      -- the handle the builder gave you is the widget
```

To put the client's own controls in a surface instead of painting them, see [controls](controls/README.md). To paint over the screen or over an existing widget without building one, see [overlays](overlay.md).

---

## Your windows live in the layer

A surface you build stands in the **addon layer**. That is a widget tree of its own, above every character the client holds, drawn over whichever is on screen and over the login screen. It is in no character's tree, so `session:ui():match`, `:matchAll`, `:root` and an [`Added` subscription](replace.md#watching-for-a-widget) never reach it. Hold the handle the builder gave you. `==` is its identity and `widget:match(selector)` searches inside it. A logout leaves it where it was.

---

## Builders

| Method | Returns | Permission | Description |
|---|---|---|---|
| `hafen.ui():window()` | [`Widget`](widget.md) | Unprotected | A draggable, titled window wrapping your content. 200x140 at (100, 100) by default, no caption. |
| `hafen.ui():widget()` | [`Widget`](widget.md) | Unprotected | A bare content rectangle, no chrome. Same defaults. |
| `hafen.ui():column()`, `hafen.ui():row()` | [`Widget`](widget.md) | Unprotected | The bare rectangle with an axis: it [lays its children out](column.md) and sizes itself to them. |
| `hafen.ui():mirror()` | [`Widget`](widget.md) | Unprotected | The bare rectangle showing [another widget's picture](mirror.md), any tree, drawn or not. |

None takes an argument. Every property is a setter on the Widget. Every setter chains and has a bare read.

## Setters

| Method | Returns | Permission | Description |
|---|---|---|---|
| `:title(caption)` / `:title()` | `self` / `string \| nil` | Unprotected | The window caption. A bare widget has no chrome and refuses. Answers on [a client window](edit.md#what-a-window-says) too. |
| `:parent(w)` / `:parent()` | `self` / `Widget \| nil` | Unprotected | What it hangs under. The layer by default. Naming [a client window](edit.md#your-own-controls-inside-one-of-the-clients-windows) puts it in that character's tree, where it ends with the window. Legal while the surface is [pending](#a-surface-never-paints-half-configured). The same verb [takes a client widget into a surface of yours](native.md#taking-one-into-a-surface-of-your-own-unprotected). |
| `:position(x, y)` / `:position()` | `self` / `{x=, y=}` | Unprotected | Place within the parent, in [design pixels](pixels.md). Your level of the [cascade](style/geometry.md#position-and-size), above any rule naming the surface. `:position(nil)` drops it: the rule, else the default place. A surface the layer or the HUD holds is clamped like a client window, 100 design pixels staying on screen. A child of a surface of yours goes where it is sent. |
| `:size(w, h)` / `:size()` | `self` / `{w=, h=}` | Unprotected | The content box, in design pixels. A window's chrome refits around it. The read answers the same content box. The frame is [`:chrome().frame`](widget.md#read-methods). The same level as `:position`: `:size(nil)` lands on a rule's `size`, else on the default box. A [control](controls/README.md#sizing) lands no lower than its art. |
| `:font(handle)` / `:font()` | `self` / `FontHandle \| nil` | Unprotected | The default font of this widget's `g:text`/`g:atext`, not of its caption. A [font handle](../font.md). |
| `:name(word)` / `:name()` | `self` / `string \| nil` | Unprotected | What your addon calls it. A [`[name=…]` selector](selectors.md#the-one-refiner-an-addon-owns) names it back as `<addon>/<word>`. One word, no space, `]` or `/`. Written once. |
| `:stock(t)` / `:stock()` | `self` / `table \| nil` | Unprotected | Its look when no rule names it — [below](#naming-and-dressing-your-own-surfaces). |
| `:resizable(true)` / `:resizable()` | `self` / `boolean \| Widget \| nil` | Unprotected | The client's corner grip on a window of yours — [below](#letting-the-user-resize-a-window-of-yours). `false` removes it. |
| `:draggable(h)`, `:resizable(h)`, `:remember(name)` / the same, bare | `self` / `Widget \| nil`, `boolean \| Widget \| nil`, `string \| nil` | Unprotected | Dragging, resizing and the saved place — [native](native.md#letting-the-user-drag-it-unprotected). |
| `:visible(shown)` / `:visible()` | `self` / `boolean` | Unprotected | Drawn or hidden. |
| `:pack()` | `self` | Unprotected | Sized to what is inside it — [below](#packing-a-surface-around-what-is-inside-it). Forgets your size level. |
| `:destroy()` | `self` | Unprotected | Removed with everything in it. |

| Rule | Detail |
|---|---|
| Owned only | Every setter answers on a surface your addon built. `:title(s)` also writes [a client window](edit.md#what-a-window-says) as a restoring level, and `:parent(w)` also takes one as the place a control is [built into](edit.md#your-own-controls-inside-one-of-the-clients-windows). A client widget and a [control](controls/README.md) have nowhere to put a font of yours. |
| Units | [Design pixels](pixels.md): what you write is what you read back at every interface scale. |

---

## Naming and dressing your own surfaces

Pixels a `Draw` handler lays down with [`g:frect`, `g:image`, `g:text`](drawing.md) are final: no rule overrides them. The same look declared as a `stock` renders identically and stays replaceable by a theme. Declare what somebody else may change, and paint the rest.

```lua
local bar = hafen.ui():widget():name("bar")
bar:stock{ bg = {color = {0, 0, 0, 90}}, padding = 2 }

for index = 1, 12 do
  local slot = hafen.ui():widget():parent(bar):name("slot" .. index):size(34, 34)
  slot:stock{ bg = {color = {36, 52, 38, 125}},
              border = {color = {20, 28, 21, 167}, width = 1} }
end
```

A theme dresses that surface by naming it, without the addon knowing the theme:

```json
{ "rules": {
    "[name^=myaddon/slot]": { "bg": { "asset": "themes/cyberpunk/slot.png", "mode": "stretch" } }
} }
```

| `:name` | `:stock` | What a theme can do |
|:---:|:---:|---|
| No | No | Almost nothing: `["@AddonWidget"]` reaches it, together with every bare surface every other addon built. |
| No | Yes | The same. It has a default look, and nothing to single it out by. |
| Yes | No | Everything: named, so a rule finds it. Bare until one does. |
| Yes | Yes | Everything, starting from the look you chose. |

| Rule | Detail |
|---|---|
| `:name` enables, `:stock` starts | Neither is required. A surface with neither is a bare rectangle. |
| Client-built widgets need neither | `:window()`, `:button()`, `:label()`, `:entry()` and the rest are client widgets, so the [site keys](style/keys.md#site-keys) already reach them. This section is about `hafen.ui():widget()`. |
| The stock is the bottom of the [cascade](style/README.md#the-cascade) | Every rule beats it, per property: a theme naming only `bg` leaves your `border` standing. A `widget:rule()` would sit at the top, out of every theme's reach. A tree rule of your own would tie with the theme's. |
| Properties | The same a [rule](style/README.md#properties) carries, minus the layout three (`position`, `anchor`, `size`), which are refused naming [`widget:position(x, y)`](native.md). `{}` drops the declaration. `:stock()` reads back what you wrote, or `nil`. |
| Painting order | A surface with a stock, or one a rule names, paints its `bg` under your `Draw` and its `border` over it. No site key (`["*"]` included) falls into a widget you built. Only a rule that names it reaches it. |
| Yours alone | Naming or declaring a stock on a client widget is refused. Your level on somebody else's widget is [`widget:rule()`](style/README.md#restyle-one-widget). |

---

## Packing a surface around what is inside it

A surface has no art of its own, so the one-number `:size(w)` a [control](controls/README.md#sizing) takes is refused, naming `:size(w, h)` and `:pack()`.

```lua
local harvest_window = hafen.ui():window():title("Harvest"):position(80, 120)
hafen.ui():button():parent(harvest_window):position(0, 0):size(120):text("Go")
harvest_window:pack()                          -- the window is now exactly that button
```

| Rule | Detail |
|---|---|
| `:pack()` | Sizes a window or a bare widget to the controls inside it. Chains. From then on the surface follows its content. A child that enters, leaves, moves, resizes, hides or shows re-packs it before the call that changed it returns. |
| `:size(w, h)` after a pack | Takes the box back. The surface keeps that size until the next `:pack()`, and the packed box is the stock `:size(nil)` then gives back. |
| `:size(nil)` after a pack | Changes nothing: the pack forgot your size level, and a rule's `size` on a packed surface is inert. |
| A [column](column.md) | Packed by construction: `:pack()` refuses on it, and `:size(w)` pins its width alone. |

---

## Subscribing

A surface answers the universal keys every [widget](widget.md#subscribing) does — `MouseDown`, `MouseUp`, `MouseMove`, `Wheel`, `Removed`, `Dragged`, `Resized` — plus its own, below. `:on(key, fn)` returns a subscription, not the widget, so it is its own statement after the builder chain. Two handlers on one key both fire, in registration order.

| Key | Handler receives | Cancelable | Fires |
|---|---|---|---|
| `Draw` | `event` — `:g()`, `:w()`, `:h()` | No | Every frame, in the pass that paints this widget. `:w()`/`:h()` is the content box in design pixels — [the `g` wrapper](drawing.md). |
| `Update` | `delta_seconds` | No | Every frame, on the [step](../threading.md), before the pass that draws it. |
| `Drop` | `event` — `:x()`, `:y()`, `:thing()`, `:preventDefault()` | Yes | The client's drag gesture drops something on it — [below](#drop-makes-a-widget-a-drop-target). |
| `Close` | `event` — `:preventDefault()` | Yes | The window's close button. A bare widget has none. Left alone, the window is destroyed when the handlers return. Cancelled, it stands — [below](#the-close-button-destroys-the-window-unless-you-say-otherwise). |
| `Resized` | `event` — `:w()`, `:h()` | No | The user released the [corner grip](#letting-the-user-resize-a-window-of-yours) or a [`:resizable(h)`](native.md#letting-the-user-resize-it-unprotected) handle. The content box, as `:size()` reads it. |

`Update` runs on the step and may reach any character's tree. `Draw`, and every press, drop and close, runs inside the tree this widget stands in and may reach only that one. A `Draw` handler that changes something elsewhere records it for the step — [threading](../threading.md).

### Where a press lands

A window is your content inside the client's frame, and every key is in **content** coordinates. `Draw` paints from its top-left corner. `MouseDown`, `MouseUp`, `MouseMove` and `Wheel` call that corner `0, 0`. A hit test written against what you drew is right by construction.

```lua
clock_window:on("Draw", function(draw_event) draw_event:g():frect(40, 40, 48, 48) end)
clock_window:on("MouseDown", function(press)
  if (press:x() >= 40) and (press:x() < 88) and (press:y() >= 40) and (press:y() < 88) then
    hafen.log():write("in the square")
  end
  press:preventDefault()                         -- the press is yours and stops here
end)
```

| Rule | Detail |
|---|---|
| Uncancelled presses fall through | To the frame underneath, which drags the window. A surface that answers clicks ends its handler with `event:preventDefault()`. |
| The chrome is the client's | The caption, the frame and the close button reach no handler of yours. Dragging the title moves the window. The close button reaches `Close`. |
| `MouseMove` fires outside the box too | The client hands a move to every widget (how a control un-hovers), so `event:x()`/`:y()` can be negative or past `:size()`. |
| Other widgets | A bare widget and a control have no chrome, so the rule reads the same. A client window speaks its own coordinates: the outer box, caption included. |

### The close button destroys the window, unless you say otherwise

The X fires `Close`, then destroys the window, its controls and every subscription on it. A toggle by `:visible()` fails at the first X, its handle stale. A handler that cancels keeps the window standing:

```lua
clock_window:on("Close", function(close_event)
  close_event:preventDefault()
  clock_window:visible(false)                  -- the X hides; :visible(true) brings it back
end)
```

### Letting the user resize a window of yours

`window:resizable(true)` switches on the client's own corner grip, the sizer the frame draws at its bottom right. The user drags the corner and the content box follows live. `Draw` paints the new box on the next frame, and `:size()` reads it. `Resized` fires once on release.

```lua
clock_window:resizable(true)
clock_window:on("Resized", function(resize_event)
  hafen.log():write(("now %dx%d"):format(resize_event:w(), resize_event:h()))
end)
```

| Rule | Detail |
|---|---|
| Where the grip is | The bottom-right corner of the content area, about 25 design pixels along each edge. A control of yours standing there takes the press first: leave that corner to the canvas. |
| Read and undo | `:resizable()` reads `true` while the grip is on. `:resizable(false)` removes it. |
| A bare widget | Has no frame to draw a grip on and refuses, naming [`:resizable(h)`](native.md#letting-the-user-resize-it-unprotected), the handle of your own that sizes any surface. The floor (one design pixel each way), the size level and [`:remember`](native.md#remembering-where-the-user-put-it-unprotected) answer the same for both. |

### A surface never paints half-configured

A surface is in the layer's tree the instant it is built: `:parent()`, `:children()` and `widget:match(selector)` answer at once. It draws nothing until the tick after the statement that built it. A caption, a size and a place set across several lines are all in place before the first pixel. There is no commit verb.

| Rule | Detail |
|---|---|
| Build and destroy in one statement | The surface never appears. |
| `:parent(w)` is a building verb | It answers while the surface is pending and refuses once it is on screen, naming `:position(x, y)`. |
| A parent that has left the tree | The build stops: nothing is placed, the rest of the chain runs inert, and the handle answers `:exists()` false. An icon handed to you by a subscription may be destroyed on the client's step before your call runs. No `:exists()` of yours sits inside that instant. |
| A value that is not a Widget | Raises, naming what a Widget is. |

```lua
local hud = hafen.session():current():ui():match("@GameUI")      -- the HUD is a widget like any other
local panel = hafen.ui():widget():parent(hud):size(120, 40)

hafen.session():current():ui():on("item", "Added", function(icon)
  local badge = hafen.ui():widget():parent(icon):size(12, 12)
  if not badge:exists() then return end          -- the icon closed: nothing was built
  badge:on("Draw", function(draw_event) draw_event:g():frect(0, 0, 12, 12) end)
end)
```

### `Drop` makes a widget a drop target

`:on("Drop", fn)` opts the widget into the client's drag gesture. Dragging a menu-grid action onto it fires `fn(event)`:

| `event` | Returns | Permission | Description |
|---|---|---|---|
| `:x()`, `:y()` | `number` | Unprotected | Widget-local design pixels. |
| `:thing()` | `table` | Unprotected | `{ kind = "pagina", res = "<resource name>" }`. `res` is absent only while the action's resource is still loading. |
| `:preventDefault()` | — | Unprotected | Consumes the drop. |

`res` is a plain resource name: draw its icon with [`g:resource`](drawing.md), persist it with [`hafen.store`](../store/README.md), put it on the bar with [`slot:res(name)`](../actionbar.md#write-protected). An entry an addon [added to the menu](../menugrid.md#write-unprotected) carries `addon/<addon id>/<id>` instead, stable across relogs and what [`s:menugrid():get(res)`](../menugrid.md) resolves. `g:resource` has nothing to draw for it, so read the entry's own look. Firing the dropped action is not part of `Drop`.

---

## See Also

- [Drawing](drawing.md) — what `g` draws, and the raster cache behind text.
- [Widget](widget.md) — the object both builders return.
- [Overlays](overlay.md) — painting over the screen or over one widget without owning either.
- [`hafen.font`](../font.md) — the handle `:font(h)` takes.
- [Style](style/README.md) — restyling the client's surfaces.
- [`hafen.virtual`](../virtual/README.md) — surfaces standing in the 3D world.
