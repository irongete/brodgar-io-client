# hafen.ui: your own windows and widgets

Two builders make a surface of your own — a window with chrome, or a bare rectangle. Each is built **bare**
and configured by chained setters. Both are unprotected, and both are torn down with your addon.

This page is about the surfaces you **paint**. To put one of the client's own controls in one instead of
drawing it, see [controls](controls/README.md); to draw over the screen, or over one widget the client
has already put there, rather than build a surface at all, see [overlays](overlay.md).

```lua
local win = hafen.ui():window()
  :title("Clock")
  :size(160, 40)
  :position(50, 50)

win:on("Draw", function(ev)
  local g = ev:g()
  g:color(255, 255, 0)
  g:text(string.format("%.0f", hafen.time():clock() or 0), 6, 12)
end)

win:position(320, 200)                        -- the handle the builder gave you IS the widget
```

## Your windows live in the layer

**A surface you build goes in the addon layer** — a widget tree of its own, above every character the client
holds, drawn over whichever one is on screen and over the login screen when none is. It is in no character's
tree: nothing about it is a window of the client's, and a logout leaves it exactly where it was.

That is why the [search verbs](widget.md#getting-a-widget) are addressed at a character —
`s:ui():match`, `:matchAll`, `:root` and an [`"Added"` subscription](replace.md#watching-for-a-widget) all
search one session's tree, never the layer — while a hit test, which asks about a point on the screen,
is not addressed at all. None of them reaches a window of yours. Hold the handle the builder gave you: it
is the widget, `==` is its identity, and `w:match(selector)` searches **inside** it.

## Windows and widgets

| Verb | Returns | Description |
|---|---|---|
| `hafen.ui():window()` | [Widget](widget.md) | a draggable, titled window wrapping your content |
| `hafen.ui():widget()` | [Widget](widget.md) | a bare content rectangle, no chrome |

Neither takes an argument. A surface is born with the client's own defaults — no caption, a place and a
size it did not choose — and every property is a setter on the [Widget](widget.md) it hands back:

| Setter | Read | Meaning |
|---|---|---|
| `:title(s)` | `:title()` | window caption — a bare widget has no chrome to write it on and refuses; it answers on [one of the client's windows](edit.md#what-a-window-says) too |
| `:parent(w)` | `:parent()` | which widget it hangs under; the default is the layer, and [one of the client's own windows](edit.md#your-own-controls-inside-one-of-the-clients-windows) may be named — which puts it in that character's tree, where it ends with them. The same verb says the other direction too: [one of the client's widgets, taken into a surface of yours](native.md#taking-one-into-a-surface-of-your-own-unprotected) |
| `:position(x, y)` | `:position()` | place within the parent, in [design pixels](pixels.md) |
| `:size(w, h)` | `:size()` | content size; a window's chrome is fitted around it |
| `:font(h)` | `:font()` | default font for this widget's `g:text`/`g:atext` draws, not for the title bar |

## Naming and dressing your own surfaces

Two more setters, and they are what makes a surface you built **themeable by somebody else**: one says what
you call it, the other what it looks like when nobody says otherwise.

> **Painting is final; a stock is a default anybody can beat.** Pixels you lay down in your `Draw` handler
> with [`g:frect`, `g:image`, `g:text`](drawing.md) are yours and nothing can override them — a rule is a
> value the client reads, not a painter that reaches into your callback. The *same look* declared as a
> `stock` renders identically and stays replaceable. So the question is never "shall I paint or declare",
> it is **which parts of my surface do I want somebody else to be able to change**.

| Setter | Read | Meaning |
|---|---|---|
| `:name(s)` | `:name()` | what your addon calls this widget. A [`[name=…]` selector](selectors.md#the-one-refiner-an-addon-owns) names it back, as `<your addon>/<s>` |
| `:stock(t)` | `:stock()` | what it looks like when **no rule** names it: the same properties a [rule](style/README.md#properties) carries, minus the three that lay a widget out |

```lua
local bar = hafen.ui():widget():parent(hud):name("bar")
bar:stock{ bg = {color = {0, 0, 0, 90}}, padding = 2 }

for i = 1, 12 do
  local slot = hafen.ui():widget():parent(bar):name("slot" .. i):size(34, 34)
  slot:stock{ bg = {color = {36, 52, 38, 125}},
              border = {color = {20, 28, 21, 167}, width = 1} }
end
```

That addon now has a default look, and knows nothing about themes. A theme dresses it by naming it:

```json
"[name^=actionbars/slot]": {
  "bg": { "asset": "themes/cyberpunk/slot.png", "mode": "stretch" }
}
```

### Which of the two does what

They are **independent**, and only one of them decides whether a theme can reach you at all:

| `:name` | `:stock` | What a theme can do |
|:---:|:---:|---|
| ✗ | ✗ | Almost nothing. `["@AddonWidget"]` reaches it — along with every bare surface every *other* addon built |
| ✗ | ✓ | The same. It has a default look, but still nothing to single it out by |
| ✓ | ✗ | **Everything.** Named, so a rule finds it; bare until one does |
| ✓ | ✓ | **Everything**, and it starts from the look you chose |

**`:name` is the one that enables.** `:stock` only decides the starting point — it neither opens nor closes
the door. And neither is required: a surface with neither is exactly what a bare widget has always been.

Widgets you build out of the client's own pieces — [`:window()`, `:button()`, `:label()`,
`:entry()`](controls/README.md) and the rest — need none of this. They *are* client widgets, so the
[site keys](style/keys.md#site-keys) already reach them and always did. This section is about
`hafen.ui():widget()`, the bare rectangle, which was the one surface no rule could name.

### Worth knowing

- **A stock is the BOTTOM of the [cascade](style/README.md#the-cascade)**, under every rule, and that is the
  whole reason to use it rather than a rule of your own. A `widget:rule()` would sit at the *top*, where no
  theme could ever reach past it; a tree rule in your own sheet would **tie** with the theme's and leave the
  winner to whichever sheet installed last, which nothing orders. A level beneath every rule has neither
  problem: your default shows, and anybody's rule beats it, per property.
- **A rule beats it per property, not wholesale.** A theme that names only your slot's `bg` leaves the
  `border` you declared standing.
- **`{}` drops the declaration**, and `:stock()` reads back what you wrote, or `nil`.
- **Your surface wears it.** A widget with a stock, or that some rule names, paints its `bg` under whatever
  your `Draw` handler draws and its `border` over the lot — the order the client's own panels use. One
  with neither stays exactly what it always was: a bare rectangle that paints nothing of its own.
- **No site key falls into it.** A widget you built is a widget, not one of the places the client draws, so
  `["*"]` and the other [site keys](style/keys.md#site-keys) never reach it. Only a rule that *names* it
  does, which is what keeps an unnamed surface bare.
- **Both are yours alone.** Naming or declaring a stock on one of the client's own widgets is refused; the
  level you have on somebody else's widget is [`widget:rule()`](style/README.md#restyle-one-widget).
- **The layout three are refused**, with the same message `widget:rule()` gives: where a widget *sits* is the
  verb, [`widget:position(x, y)`](native.md).

Every setter returns the widget, so a whole surface is one expression; every one has a matching bare read,
so nothing you configured needs a variable of its own to be readable later. They answer on a surface
**your** addon painted, and two of them reach further: `:title(s)` writes
[one of the client's windows](edit.md#what-a-window-says) as well, where it is a level that restores, and
`:parent(w)` takes one as the place your control is
[built into](edit.md#your-own-controls-inside-one-of-the-clients-windows). A native widget has nowhere to
put a default font of yours, and neither does a [control](controls/README.md), which the client draws and
drives.

Sizes and positions are [design pixels](pixels.md): what you write is what you read back, on every client
whatever the user's interface scale. `:position` is within the parent; on a window `:size` is the
**content** size, so the outer box it reads back is that plus the chrome.

**A surface has no art of its own**, so the one-number `:size(w)` a [control](controls/README.md#sizing)
takes refuses here, naming the two-number write and `:pack()` — which sizes a window or a bare widget to
the controls inside it, so a panel's box is read rather than added up:

```lua
local win = hafen.ui():window():title("Harvest"):position(80, 120)
hafen.ui():button():parent(win):position(0, 0):size(120):text("Go")
win:pack()                                       -- the window is now exactly that button
```

## Subscribing

A window or a bare widget answers the five universal [`:on(key, fn)`](widget.md#subscribing) keys every
widget does — `MouseDown`, `MouseUp`, `MouseMove`, `Wheel`, `Removed` — plus four more of its own, since
it is a surface with content to paint and a lifetime to report:

| Key | handler receives | Cancelable | Fires |
|---|---|---|---|
| `Draw` | `ev` — `:g()` `:w()` `:h()` | no | every frame; `:w()`/`:h()` is the box you sized, in [design pixels](pixels.md) — see [the `g` wrapper](drawing.md) |
| `Update` | `dt` | no | every frame, before `Draw` |
| `Drop` | `ev` — `:x()` `:y()` `:thing()` `:preventDefault()` | yes | the client's drag gesture drops something on it |
| `Close` | — | no | the window's close button; a bare widget has none, so it never fires |

`:on(key, fn)` is its own statement, after the builder chain that made the widget finishes — it hands back
a subscription, not the widget, so it cannot sit mid-chain or be a chain's last call. Two handlers on
`Draw` both paint, in registration order; two on any of these both fire.

### Where a press lands

A window is your content inside the client's own frame, and everything you subscribe to speaks the
**content**. `Draw` paints from its top-left corner, and `MouseDown`, `MouseUp`, `MouseMove` and `Wheel`
call that same corner `0, 0` — so `ev:x(), ev:y()` on a press is the pixel `g:text(s, x, y)` writes at, and
a hit test written against what you drew is right by construction:

```lua
win:on("Draw", function(ev) ev:g():frect(40, 40, 48, 48) end)
win:on("MouseDown", function(ev)
  if (ev:x() >= 40) and (ev:x() < 88) and (ev:y() >= 40) and (ev:y() < 88) then
    hafen.log():write("in the square")
  end
  ev:preventDefault()                         -- yours now: see below
end)
```

**A press you do not cancel falls through to the frame underneath**, which is what drags the window — so a
surface that answers clicks at all ends its handler with `ev:preventDefault()`. Cancel it and the press is
yours and stops there.

**The caption, the frame and the close button are the client's**, not yours: a press on any of them reaches
no handler of yours at all, and dragging the title moves the window. There is nothing to filter and nothing
to subtract. What the close button does reach is `Close`, and `:size()` is the content box, so the frame
never enters your arithmetic either.

`MouseMove` is the one key that also fires for a pointer **outside** the box — the client hands a move to
every widget, which is how a control un-hovers when the pointer leaves it — so its `ev:x(), ev:y()` can be
negative or past `:size()`. Test the coordinate where that matters.

A bare `:widget()` has no chrome, and a [control](controls/README.md) has none either, so the rule reads the
same on all three. One of **the client's** widgets speaks its own coordinates, which for one of its windows
means the outer box, caption included.

### A surface never paints half-configured

A bare `:window()` is in the layer's tree the instant it is built — its `:parent()`, its `:children()` and
`w:match(selector)` inside it all answer at once — but it **draws nothing until the tick after the statement
that built it**. So a caption, a size and a place you set across several lines are all in place before the
first pixel, whatever falls between them, and there is no "commit" verb to forget.

Two things follow. A surface you build and destroy in the same breath never appears at all. And
`:parent(w)` is a *building* verb: it answers while the surface is still being built and refuses once it is
on screen, where the way to move a widget is `:position(x, y)`.

```lua
local hud = hafen.session():current():ui():match("@GameUI")   -- the HUD is just another widget
local panel = hafen.ui():widget():parent(hud):size(120, 40)
```

### `Drop` makes a widget a drop target

`:on("Drop", fn)` opts the widget into the client's own drag gesture: drag a menu-grid action onto it and
`fn(ev)` fires with `ev:x()`/`ev:y()` in widget-local [design pixels](pixels.md) and `ev:thing()` a neutral descriptor,
`{ kind = "pagina", res = "<resource name>" }`. `res` is a plain resource name — draw its icon with
[`g:resource`](drawing.md), persist it with [`hafen.store`](../store.md). It is present only for
resource-based actions; an id-only action carries `kind` alone, which is usable in-session but not reliably
persistable. Firing the dropped action is not part of it.

An entry an addon [added to the menu](../menugrid.md#write-unprotected) carries its own
`addon/<the addon's id>/<the id>` identity instead, which is stable across a relog and is what
[`s:menugrid():get(res)`](../menugrid.md) resolves. It is not a client resource, so `g:resource` has
nothing to draw for it: read the entry and ask it what it looks like.

## See also

- [drawing](drawing.md) — what `g` can do, and why text is nearly free to redraw
- [widget](widget.md) — the object both builders return, and what you can do to it afterwards
- [overlays](overlay.md) — painting over the screen, or over one widget, without owning either
- [`hafen.font`](../font.md) — the handle `:font(h)` takes
- [style](style/README.md) — restyling the client's surfaces rather than drawing your own
- [`hafen.vr`](../vr/README.md) — the same idea in the 3D world
