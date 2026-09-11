# hafen.ui: position, size, anchor and margin

The [sheet](README.md) can say **where** a widget is, not only what it looks like. Three properties lay a
widget out, and a fourth, [`margin`](#margin), is the room a [column](../column.md) keeps around one; the
four are the only properties that are about a widget's place, and the only ones a
[site key](keys.md#site-keys) may not carry.

```lua
local s = hafen.ui():sheet()
s:rule("window[title=Equipment]"):position(40, 200)
s:rule("window[title=Cupboard]"):position(400, 60):size(300, 220)
s:install()
```

## position and size

- **They are said about a widget, so only a [tree key](keys.md#tree-keys) may carry them.** `["chat"]`,
  `["window.title"]`, `["*"]` — every *site* key — name a place the client draws text, and text has no
  position of its own to move, so `:position()` or `:size()` there is an **error** naming the fix rather
  than a rule that silently does nothing. (`*` is the default *site*, not "every widget"; select widgets
  with `["window"]` or a refiner.)
- **`widget:rule():position(…)` is an error too**, for the opposite reason: the hand-named level of the
  layout cascade already exists and is the **verb**, [`w:position(x, y)`](../native.md). One way per
  operation.
- **The rule and the verb are one cascade, not two mechanisms.** A position from the verb outranks one from
  any rule, however specific, and `w:position(nil)` drops *your level*, falling back to the rule when one
  still names the widget and only reaching the stock value when nothing does.
- **[Design pixels](../pixels.md)**, like `padding`, a border's slice and the verb beside them: the pair a rule says
  is the pair `:position()` reads back, on every client. A position is within the **parent**; `size` on a window
  is its **content** size, exactly as [the verb](../native.md) takes it.
- **A place is `x`/`y` and a size is `w`/`h`**, written and read back — `position = {40, 200}` or
  `{x = 40, y = 200}`, `size = {300, 220}` or `{w = 300, h = 220}`, and an `{x=, y=}` under `size` is an
  **error** naming the two keys. That is the [shape a size has](../../shapes.md#the-anonymous-shapes)
  everywhere, so `rule:size()` and `widget:style().size` read back `.w` and `.h`.
- **Applied when the sheet is, and when a widget appears** — including a window whose caption arrives a
  moment after it opens. Never per frame, and never at the draw.
- **Dropping the rule restores the exact numbers it found**, the same discipline `padding` follows, and the
  client's own saved positions stay [the user's](../native.md).

```lua
local sheet = hafen.ui():sheet()
local w = hafen.session():current():ui():match("window[title=Equipment]")
sheet:rule("window[title=Equipment]"):position(40, 200)
sheet:install()
w:position()            --> {x = 40,  y = 200}    -- the rule
w:position(12, 12)      --                        -- named by hand: the top of the same fold
w:position()            --> {x = 12,  y = 12}
w:position(nil)         --                        -- your level goes...
w:position()            --> {x = 40,  y = 200}    -- ...and the RULE is what is underneath
sheet:release()         --                        -- ...and now nothing is: the user's again
```

`size` carries [the same caveat as the verb](../native.md): a client window that packs itself around its
contents, the main inventory among them, is honoured and then re-packs before the call returns. That is
inert, never an error; read `:size()` back — `{w=, h=}` — if you need to know which kind you are holding.

## anchor

An absolute position is a number you wrote down once. **An anchor is a relationship**, re-derived every time
what it hangs off changes — so it survives a resized game window, a widget that moved under it, or a window
that packed itself around new contents:

```lua
local s   = hafen.ui():sheet()
local cur = hafen.session():current()
s:rule("window[title=Inventory]"):anchor{ to = "screen", at = "bottomright", offset = {-8, -8} }
s:rule("window[title=Equipment]"):anchor{ to = cur:ui():match("window[title=Inventory]"),
                                          at = "topright" }
s:rule("window[title=Cupboard]"):anchor{ at = "center" }        -- every field has a default
s:install()
```

| Field | Default | Meaning |
|---|---|---|
| `to` | `"screen"` | the screen, or any widget — **a character's, from a window of yours, and the other way about**: every tree in this client covers the same screen, so the two corners mean the same place. A widget target is held **weakly**: when it closes the anchor stops resolving and the widget simply stays where it is — inert, never a snap back, and one that has *already* closed by the moment the rule is written installs the same way |
| `at` | `"topleft"` | one of nine corners: `topleft`, `top`, `topright`, `left`, `center`, `right`, `bottomleft`, `bottom`, `bottomright`. Anything else is an **error** naming all nine |
| `offset` | `{0, 0}` | `{dx, dy}`, [design px](../pixels.md), added after the corners meet |

The corner is the widget's **own** as well as the target's — `at = "bottomright"` puts its bottom-right
corner on the target's, which is what makes `offset = {-8, -8}` read as *8 px in from the edge*.

**A `to` that is neither is an error.** A number, a table, a string other than `"screen"` — the anchor has
no widget to hang off and no future meaning to wait for, so the rule is refused as you write it rather than
installed inert. That is the one fault here worth telling you about: naming a widget that has gone is the
ordinary case, and it is what the weak hold above already answers.

**A position is the degenerate anchor** — to the widget's own parent, at its top-left, with that offset —
which is exactly the coordinate `:position()` reads. So they are *one* property with two spellings, writing
one slot: the later setter replaces the earlier, the read that was not written answers `nil`, and a rule
[loaded from data](README.md#a-sheet-from-data) saying both is an error rather than a winner picked at
random, because there the two have no order to be read in.

```lua
r:position(40, 200)
r:anchor{ to = "screen", at = "topleft", offset = {40, 200} }   -- same place, top-level window
r:position()                                                   --> nil: the anchor holds the slot
```

**A chain of anchors is followed eight deep.** One widget anchored to another anchored to a third is an
ordinary thing to write and the cascade follows it; what the depth is really there for is a **cycle** —
two widgets anchored to each other, or a longer ring — which is not. A cycle stops at the eighth step
with the widgets it reached moved and the rest where they were, silently: nothing is refused and
nothing is logged, because a legitimate chain and a ring look the same going in. If a widget of yours
will not settle, look for a ring in what you anchored.

**Re-derived on the events that change what it reads**, never per frame and never at the draw: the game
window is resized, the target moves or resizes, the widget itself changes size. A move made through this
API — a verb, a rule — has moved everything hanging off it **by the time the call returns**; a move the
*user* makes by dragging is picked up on the next tick. Only anchored widgets are re-derived, so a plain
position costs nothing at rest.

**A follower in another tree can be one frame behind, and only from one place.** Write the move from an
[`Update` handler](../../../guides/events-and-timers.md), a timer or any bus event and the whole cascade
lands inside the call, whichever trees it crosses. Write it from a handler the client dispatched *into* a
tree — a `Draw`, a `Drop`, a control's `Pressed`, a drag armed with [`widget:draggable()`](../native.md)
— and a follower **in a different tree** re-derives on the next step instead: within that tree the move is
immediate as ever. Read `:position()` back on the frame after if you need the number from there.

**Off-screen is clamped, by the client's own rule.** A window the HUD or the root holds directly is handed
to the same clamp the client uses when it places or toggles one, so at least a corner of it stays inside
and a bad offset can never make a window unreachable. [`hafen.ui():hit()`](../selectors.md#hit-testing) still
finds it where it is drawn; read `:position()` back if you need the number that survived. A widget *inside* a
window is laid out by that window and is not the client's to clamp — there you get the pixels you asked for.

[`widget:style()`](README.md#restyle-one-widget) reports the property as it was **written** — `anchor` for
an anchor, `position` for a plain one — while [`widget:position()`](../widget.md#read) answers where the
widget actually is right now.

## margin

`position` and `anchor` say where a widget is. `margin` says how much room a [column or a row](../column.md)
keeps **around** it: the same four insets [`padding`](chrome.md#padding) is, said about the outside of the
widget's box rather than the inside of a frame. Only a column honours it — on a widget placed by hand the
property resolves, reads back and moves nothing.

```lua
local s = hafen.ui():sheet()
s:rule("[name=myaddon/two]"):margin(16, 2, 0, 3)   -- left, top, right, bottom
s:rule("[name^=myaddon/row]"):margin(4)             -- all four sides
s:install()
```

| Call | Value | Meaning |
|---|---|---|
| `rule:margin(n)` | [design px](../pixels.md), `>= 0` | the room the column keeps around the widget, on all four sides |
| `rule:margin(l, t, r, b)` | [design px](../pixels.md), `>= 0` each | the same room, said one side at a time |
| `rule:margin()` | — | reads it back as `{l =, t =, r =, b =}`, or `nil` when this rule says nothing; the setter takes that table again |

- **One property and one slot.** One number is all four sides and four are `left, top, right, bottom`; the
  later call replaces the earlier; two or three numbers is an error naming both spellings, and so is a
  negative side — every one of the four is a distance. `widget:style().margin` reads the resolved answer in
  the same `{l =, t =, r =, b =}`.
- **A site key refuses it**, with the error `position` gets there: a site is where the client draws, not a
  widget a column lays out. **A tree key carries it — and so do the two hand-named levels**,
  `widget:rule()` and a [`:stock`](../custom.md#naming-and-dressing-your-own-surfaces), which is where it
  parts from the three: no verb spells the room around a widget, so `widget:rule():margin(…)` *is* the
  hand-named level, exactly as it is for `padding`.
- **Honoured by the column the widget stands in**, which re-lays itself before the call that wrote the
  property returns — a sheet installed, a level written, a stock declared — and drops it the same way when
  the rule goes. [What the arithmetic is](../column.md#the-room-around-a-child) is the column's page.
  Everywhere else the property is inert, never an error, like `padding` on a surface that does not own its
  layout.

## See also

- [native](../native.md) — the verb above every rule in this cascade
- [the pixel](../pixels.md) — what a coordinate in a rule is counted in
- [keys](keys.md#what-each-key-accepts) — why a site key may not carry these four
- [chrome](chrome.md#padding) — `padding`, the drawing property that also moves a window, and the insets
  `margin` shares
- [column](../column.md) — the one surface that honours a `margin`, and what it does with one
- [style](README.md#the-cascade) — how the verb and the rules fold together
