# hafen.ui: position, size and anchor

The [sheet](README.md) can say **where** a widget is, not only what it looks like. These three are the only
properties that lay a widget out, and they are the only ones a [site key](keys.md#site-keys) may not carry.

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
  layout cascade already exists and is the **verb**, [`w:position(x, y)`](../native.md). One way per operation.
- **The rule and the verb are one cascade, not two mechanisms.** A position from the verb outranks one from
  any rule, however specific, and `w:position(nil)` drops *your level*, falling back to the rule when one
  still names the widget and only reaching the stock value when nothing does.
- **Raw pixels**, like `pad` and a border's slice. A position is within the **parent**; `size` on a window
  is its **content** size, exactly as [the verb](../native.md) takes it.
- **Applied when the sheet is, and when a widget appears** — including a window whose caption arrives a
  moment after it opens. Never per frame, and never at the draw.
- **Dropping the rule restores the exact numbers it found**, the same discipline `pad` follows, and the
  client's own saved positions stay [the user's](../native.md).

```lua
local sheet = hafen.ui():sheet()
local w = hafen.ui():find("window[title=Equipment]")
sheet:rule("window[title=Equipment]"):position(40, 200)
sheet:install()
w:position()            --> {x = 40,  y = 200}    -- the rule
w:position(12, 12)      --                        -- named by hand: the top of the same fold
w:position()            --> {x = 12,  y = 12}
w:position(nil)         --                        -- your level goes...
w:position()            --> {x = 40,  y = 200}    -- ...and the RULE is what is underneath
sheet:drop()            --                        -- ...and now nothing is: back to where the user had it
```

`size` carries [the same caveat as the verb](../native.md): a client window that packs itself around its
contents, the main inventory among them, is honoured and then re-packs before the call returns. That is
inert, never an error; read `:size()` back if you need to know which kind you are holding.

## anchor

An absolute position is a number you wrote down once. **An anchor is a relationship**, re-derived every time
what it hangs off changes — so it survives a resized game window, a widget that moved under it, or a window
that packed itself around new contents:

```lua
local s = hafen.ui():sheet()
s:rule("window[title=Inventory]"):anchor{ to = "screen", at = "bottomright", offset = {-8, -8} }
s:rule("window[title=Equipment]"):anchor{ to = hafen.ui():find("window[title=Inventory]"),
                                          at = "topright" }
s:rule("window[title=Cupboard]"):anchor{ at = "center" }        -- every field has a default
s:install()
```

| Field | Default | Meaning |
|---|---|---|
| `to` | `"screen"` | the screen, or any widget. A widget target is held **weakly**: when it closes the anchor stops resolving and the widget simply stays where it is — inert, never a snap back |
| `at` | `"topleft"` | one of nine corners: `topleft`, `top`, `topright`, `left`, `center`, `right`, `bottomleft`, `bottom`, `bottomright`. Anything else is an **error** naming all nine |
| `offset` | `{0, 0}` | `{dx, dy}`, raw pixels, added after the corners meet |

The corner is the widget's **own** as well as the target's — `at = "bottomright"` puts its bottom-right
corner on the target's, which is what makes `offset = {-8, -8}` read as *8 px in from the edge*.

**A position is the degenerate anchor** — to the widget's own parent, at its top-left, with that offset —
which is exactly the coordinate `:position()` reads. So they are *one* property with two spellings, writing
one slot: the later setter replaces the earlier, the read that was not written answers `nil`, and a rule
[loaded from data](README.md#a-sheet-from-data) saying both is an error rather than a winner picked at
random, because there the two have no order to be read in.

```lua
r:position(40, 200)
r:anchor{ to = "screen", at = "topleft", offset = {40, 200} }   -- the same place, for a top-level window
r:position()                                                   --> nil: the anchor holds the slot now
```

**Re-derived on the events that change what it reads**, never per frame and never at the draw: the game
window is resized, the target moves or resizes, the widget itself changes size. A move made through this
API — a verb, a rule — has moved everything hanging off it **by the time the call returns**; a move the
*user* makes by dragging is picked up on the next tick. Only anchored widgets are re-derived, so a plain
position costs nothing at rest.

**Off-screen is clamped, by the client's own rule.** A window the HUD or the root holds directly is handed
to the same clamp the client uses when it places or toggles one, so at least a corner of it stays inside
and a bad offset can never make a window unreachable. [`hafen.ui():at()`](../selectors.md#hit-testing) still
finds it where it is drawn; read `:position()` back if you need the number that survived. A widget *inside* a
window is laid out by that window and is not the client's to clamp — there you get the pixels you asked for.

[`widget:style()`](README.md#restyle-one-widget) reports the property as it was **written** — `anchor` for
an anchor, `position` for a plain one — while [`widget:position()`](../widget.md#read) answers where the
widget actually is right now.

## See also

- [native](../native.md) — the verb above every rule in this cascade
- [keys](keys.md#what-each-key-accepts) — why a site key may not carry these three
- [chrome](chrome.md#pad) — `pad`, the drawing property that also moves a window
- [style](README.md#the-cascade) — how the verb and the rules fold together
