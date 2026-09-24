# hafen.ui: Position, Size, Anchor and Margin

The [sheet](README.md) properties about a widget's place: `position`, `size` and `anchor` lay a widget out, `margin` is the room a [column](../column.md) keeps around one. They are the only properties a [site key](keys.md#site-keys) may not carry.

```lua
local sheet = hafen.ui():sheet()
sheet:rule("window[title=Equipment]"):position(40, 200)
sheet:rule("window[title=Cupboard]"):position(400, 60):size(300, 220)
sheet:rule("window[title=Inventory]"):anchor{ to = "screen", at = "bottomright", offset = {-8, -8} }
sheet:install()
```

---

## position and size

| Method | Value | Permission | Description |
|---|---|---|---|
| `rule:position(x, y)` | Design pixels | Unprotected | Where the matched widget sits within its parent. Reads back `{x=, y=}`. A load table takes `{40, 200}` or `{x = 40, y = 200}`. |
| `rule:size(w, h)` | Design pixels | Unprotected | How big. A window's content box, as [the verb](../native.md) takes it. Reads back `{w=, h=}`. A load table takes `{300, 220}` or `{w = 300, h = 220}`, and `{x=, y=}` under `size` raises naming the two keys — [the shape a size has](../../shapes.md#the-anonymous-shapes). |

| Rule | Detail |
|---|---|
| Tree keys only | A site key names a place the client draws text, which has no position to move: `:position()` or `:size()` there raises naming the fix. `*` is the default site, not "every widget". Select widgets with `["window"]` or a refiner. |
| `widget:rule()` refuses them | The hand-named level of the layout cascade is the verb, [`widget:position(x, y)`](../native.md). |
| One cascade | The verb outranks any rule. `widget:position(nil)` drops your level and falls back to a rule that still names the widget, reaching the stock value when none does. A [surface of yours](../custom.md) runs the same cascade: its stock is the builder's default place and box, and a `size` under a [control](../controls/README.md#sizing)'s art lands on the art's box. |
| When it applies | When the sheet is installed and when a widget appears: a client window, its caption landing a moment after it opens included, and a [surface of yours](../custom.md) on the tick after the statement that built it, name, box and parent configured. A [`:name(word)`](../custom.md#naming-and-dressing-your-own-surfaces) written after that lands the rule before the call returns. Never per frame. |
| Dropping the rule | Gives back the stock: the numbers it found, or, for a window on the HUD that follows the screen, the user's place for the size the screen is now ([Re-layout](../native.md#letting-the-user-drag-it-unprotected)). The client's own saved positions stay [the user's](../native.md#moving-and-resizing-unprotected). |
| `size` on a box that is its own | A window that packs itself is honoured and re-packed before the call returns, as [the verb](../native.md): inert, never an error. A [column](../column.md), a [packed](../custom.md#packing-a-surface-around-what-is-inside-it) surface of yours, a [picture](../controls/display.md#picture) and a [mirror](../mirror.md) take no `size` from a rule: inert. Read `:size()` back. |

```lua
local equipment_window = hafen.session():current():ui():match("window[title=Equipment]")
sheet:rule("window[title=Equipment]"):position(40, 200)
sheet:install()
equipment_window:position()          -- {x = 40, y = 200}: the rule
equipment_window:position(12, 12)    -- named by hand: the top of the same fold
equipment_window:position()          -- {x = 12, y = 12}
equipment_window:position(nil)       -- your level goes...
equipment_window:position()          -- {x = 40, y = 200}: the rule is underneath
sheet:release()                      -- and now nothing is: the user's again
```

---

## anchor

An anchor is a relationship, re-derived whenever what it hangs off changes. It survives a resized game window, a moved target, a window that packed itself around new contents.

```lua
local session = hafen.session():current()
sheet:rule("window[title=Inventory]"):anchor{ to = "screen", at = "bottomright", offset = {-8, -8} }
sheet:rule("window[title=Equipment]"):anchor{ to = session:ui():match("window[title=Inventory]"), at = "topright" }
sheet:rule("window[title=Cupboard]"):anchor{ at = "center" }        -- every field has a default
```

| Field | Default | Meaning |
|---|---|---|
| `to` | `"screen"` | The screen, or any widget — a character's from a window of yours, or the other way about. Every tree covers the same screen. A widget target is held weakly: when it closes the anchor stops resolving and the widget stays where it is. One already closed when the rule is written installs the same way. A `to` that is neither raises. |
| `at` | `"topleft"` | One of nine corners: `topleft`, `top`, `topright`, `left`, `center`, `right`, `bottomleft`, `bottom`, `bottomright`. Anything else raises naming all nine. The corner is the widget's own as well as the target's. `bottomright` puts its bottom-right corner on the target's, so `offset = {-8, -8}` reads as 8 px in from the edge. |
| `offset` | `{0, 0}` | `{dx, dy}` in design pixels, added after the corners meet. |

| Rule | Detail |
|---|---|
| A position is the degenerate anchor | To the widget's own parent, at its top-left, with that offset — the coordinate `:position()` reads. One property, two spellings, one slot: the later setter replaces the earlier, the read not written answers `nil`, and a [loaded](README.md#a-sheet-from-data) rule saying both raises. |
| Chains | Followed eight deep. A cycle stops at the eighth step with the widgets reached moved and the rest where they were, silently. |
| Re-derived on the events that change what it reads | The game window resized, the target moved or resized, the widget itself resized — by the verb, a rule's `size`, a pack or the client's corner grip, so a widget anchored `bottomright` keeps its corner on the target's through its own resize. A move made through this API lands on everything hanging off it by the time the call returns. A user's drag, a pack and the grip are picked up on the next tick. A plain position costs nothing at rest. |
| A follower in another tree | From an [`Update` handler](../../../guides/events-and-timers.md), a timer or a bus event the whole cascade lands inside the call. From a handler dispatched into a tree, a follower in a different tree re-derives on the next step. Such a handler is `Draw`, `Drop`, a control's `Pressed`, a [`draggable`](../native.md) drag. |
| Off-screen | A window the HUD or the root holds is clamped by the client's own rule, so a corner stays inside. [`hafen.ui():hit()`](../selectors.md#hit-testing) finds it where drawn. A widget inside a window gets the pixels asked for. |
| Read-back | [`widget:style()`](README.md#restyle-one-widget) reports the property as written (`anchor` or `position`). [`widget:position()`](../widget.md#read-methods) answers where the widget is now. |

```lua
local rule = sheet:rule("window[title=Equipment]")
rule:position(40, 200)
rule:anchor{ to = "screen", at = "topleft", offset = {40, 200} }   -- the same place, said as an anchor
rule:position()                                                   -- nil: the anchor holds the slot
```

---

## margin

The room a [column or row](../column.md) keeps around the matched widget: the four insets [`padding`](chrome.md#padding) is, said about the outside of the widget's box. Only a column honours it. On a widget placed by hand it resolves, reads back and moves nothing.

| Method | Value | Permission | Description |
|---|---|---|---|
| `rule:margin(n)` | Design pixels, `>= 0` | Unprotected | The room on all four sides. |
| `rule:margin(l, t, r, b)` | Design pixels, `>= 0` each | Unprotected | One side at a time. |
| `rule:margin()` | `{l=, t=, r=, b=} \| nil` | Unprotected | Reads it back. The setter takes that table again. `widget:style().margin` reads the resolved answer in the same shape. |

```lua
sheet:rule("[name=myaddon/two]"):margin(16, 2, 0, 3)    -- left, top, right, bottom
sheet:rule("[name^=myaddon/row]"):margin(4)              -- all four sides
```

| Rule | Detail |
|---|---|
| One slot | The later call replaces the earlier. Two or three numbers, or a negative side, raise. |
| Who carries it | A tree key, `widget:rule()` and a [`:stock`](../custom.md#naming-and-dressing-your-own-surfaces): no verb spells the room around a widget, so `widget:rule():margin(…)` is the hand-named level, as for `padding`. A site key refuses it. |
| When it lands | The column re-lays before the call that wrote the property returns: a sheet installed, a level written, a stock declared. It drops it when the rule goes. [The arithmetic](../column.md#the-room-around-a-child) is the column's page. |

---

## See Also

- [Native](../native.md) — the verb above every rule in this cascade.
- [Pixels](../pixels.md) — what a coordinate in a rule is counted in.
- [Keys](keys.md#what-each-key-accepts) — why a site key may not carry these properties.
- [Chrome](chrome.md#padding) — `padding`, the drawing property that also moves a window.
- [Column](../column.md) — the one surface that honours a `margin`.
- [Style](README.md#the-cascade) — how the verb and the rules fold together.
