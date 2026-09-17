# hafen.ui: Interactive Controls

A button, a text entry, a checkbox, a radio, a slider, a scroll and a scrollbar: the [controls](README.md) the user drives. Each is built bare, configured by chained setters and dressed by the [stylesheet](../style/README.md).

```lua
local harvest_window = hafen.ui():window():title("Harvest"):size(200, 100)
local search_entry = hafen.ui():entry():parent(harvest_window):position(0, 0):size(160):value("")
search_entry:on("Submitted", function(text) hafen.log():write("search: " .. text) end)

local ripe_check = hafen.ui():check():parent(harvest_window):position(0, 28):text("Only ripe"):value(true)
ripe_check:on("Changed", function(checked) hafen.store():var("cfg").ripe = checked end)

local volume = hafen.ui():slider():parent(harvest_window):position(0, 56):size(140, 20):range(0, 100):value(50)
volume:on("Changed", function(event) if event:final() then hafen.store():var("cfg").volume = event:value() end end)
```

---

## A caption or a picture

A button shows text or pictures. The setter decides which.

| Method | Returns | Permission | Description |
|---|---|---|---|
| `button:text(caption)` | `self` | Unprotected | A captioned button. Rewritable at any time. |
| `button:image(up, down [, hover])` | `self` | Unprotected | A picture button: `up` at rest, `down` while held, `hover` under the cursor (defaults to `up`). Building-only. |
| `button:image()` | `table \| nil` | Unprotected | `{ up =, down =, hover = }` as named. `nil` on a captioned button. |

| Rule | Detail |
|---|---|
| A face | An [image asset](../../asset/README.md) handle, drawn at its own pixels. Or a string naming a client image (`"gfx/hud/buttons/addu"`), scaled as the client scales it, so it matches the buttons beside it. |
| Building-only | The client draws a captioned and a picture button with two different widgets, so `:image` chooses which widget this is. Once on screen it refuses, saying so. |
| `:type()` | `"Button"` or `"IButton"`. `:role()` is `button` for both, so one selector matches every button you built. |

```lua
local up, down = hafen.asset():get("up.png"), hafen.asset():get("down.png")
local refresh_button = hafen.ui():button():parent(harvest_window):position(8, 8):image(up, down)
refresh_button:on("Pressed", function() hafen.log():write("refreshing") end)
```

## Text entry

| Method | Returns | Permission | Description |
|---|---|---|---|
| `hafen.ui():entry()` | [`Widget`](../widget.md) | Unprotected | A single-line text field. |
| `entry:value(text)` | `self` | Unprotected | The one way to write its content. Any string, `"42"` included ([a numeric string is a string](../../conventions.md#a-number-is-not-a-string-and-a-numeric-string-is-not-a-number)). A number is refused. Fires neither key. |
| `entry:value()`, `entry:text()` | `string` | Unprotected | The content. |
| `entry:text(text)` | — | — | Refused, naming `:value(s)`. |
| `entry:on("Changed", fn)` | `Sub` | Unprotected | `fn(text)` on every keystroke that changes the text. |
| `entry:on("Submitted", fn)` | `Sub` | Unprotected | `fn(text)` once, on Enter, with the whole text. |

| Rule | Detail |
|---|---|
| Focus | While the field has focus a keystroke goes to it only — never to your character, a hotkey or the chat line. |
| Height | Its field art's, which `:size(w)` leaves to the control. A [`textentry`](../style/surfaces.md#textentry) rule whose `bg` is a picture, installed before the entry is built, is that art. |
| Client entries | `Submitted` answers on the client's own entries, the chat line included, and there it can be cancelled: the server never hears it — [edit](../edit.md). |

## Checkbox

| Method | Returns | Permission | Description |
|---|---|---|---|
| `hafen.ui():check()` | `Widget` | Unprotected | A boolean toggle. |
| `check:text(caption)` | `self` | Unprotected | Its caption. |
| `check:value(checked)` | `self` | Unprotected | The tick. |
| `check:value()` | `boolean` | Unprotected | Whether it is ticked. |
| `check:image(up, down, hoverUp, hoverDown)` | `self` | Unprotected | Four faces, all required: the two states at rest and each under the cursor, resolved as a button's [face](#a-caption-or-a-picture) is. Building-only. |
| `check:image()` | `table \| nil` | Unprotected | `{up=, down=, hoverUp=, hoverDown=}`. `nil` on a captioned checkbox. |
| `check:on("Changed", fn)` | `Sub` | Unprotected | `fn(checked)` when the user changes it. |

`:type()` reads `"CheckBox"` or `"ICheckBox"`. A checkbox with no picture takes the theme's [`checkbox`](../style/surfaces.md#checkbox-scrollbar-and-slider) (the box) and `checkbox.mark` (the tick). One given pictures is dressed by neither.

## Radio

| Method | Returns | Permission | Description |
|---|---|---|---|
| `hafen.ui():radio()` | `Widget` | Unprotected | One control: a set of buttons of which exactly one is checked. |
| `radio:rows(labels)` | `self` | Unprotected | The choices, laid out in one column below the control's `:position`. Writing again replaces the set and drops the checked row. `:rows{}` is an empty control, not an error. |
| `radio:value(label)` | `self` | Unprotected | Checks that row. A label not in the current set is refused, naming the rows that are. |
| `radio:value()` | `string` | Unprotected | The checked row. |
| `radio:size()` | `{w=, h=}` | Unprotected | The box of the whole stack. |
| `radio:on("Changed", fn)` | `Sub` | Unprotected | `fn(label)` when the user picks a different row. |

```lua
local sort_radio = hafen.ui():radio():rows{"Quality", "Amount", "Name"}:value("Amount")
sort_radio:on("Changed", function(pick) hafen.store():var("cfg").sort = pick end)
```

## Slider

| Method | Returns | Permission | Description |
|---|---|---|---|
| `hafen.ui():slider()` | `Widget` | Unprotected | A draggable position within a range. |
| `slider:range(min, max)` | `self` | Unprotected | The bounds, numbers. Narrowing re-clamps a value outside the new bounds without firing `Changed`. `:range(nil)` is refused naming the missing bound: there is no undo for a control's own bounds. |
| `slider:range()` | `{min=, max=}` | Unprotected | The bounds. |
| `slider:value(n)` | `self` | Unprotected | The position. A value outside `:range` clamps to the nearer bound. `"50"` is refused: a value is a number. |
| `slider:value()` | `number` | Unprotected | The position. |
| `slider:on("Changed", fn)` | `Sub` | Unprotected | `fn(event)` on every step of the drag: `event:value()` the position, `event:final()` `false` while dragging and `true` once, on release. |

```lua
volume:on("Changed", function(event)
  hafen.log():write("volume " .. event:value())                                    -- every step of the drag
  if event:final() then hafen.store():var("cfg").volume = event:value() end        -- once, on release
end)
```

A client slider (an options-window volume slider) reports the same key. There it is the one capability key that cannot be cancelled: the control writes its value before it reports it ([edit](../edit.md)). A slider is two [surfaces](../style/surfaces.md#checkbox-scrollbar-and-slider), `slider` (the rail) and `slider.knob` (the thumb).

## Scroll

| Method | Returns | Permission | Description |
|---|---|---|---|
| `hafen.ui():scroll()` | `Widget` | Unprotected | A scrolling container. Give it `:size(w, h)`. Anything `:parent()`'d into it lands in the scrolling area. |

A scrollbar appears down its right edge once the content outgrows the box and disappears when it fits. It is a control in its own right, one of `scroll_box:children()`. It answers `:range()`, `:value(n)` and `Changed` as a bare [scrollbar](#scrollbar) does.

## Scrollbar

| Method | Returns | Permission | Description |
|---|---|---|---|
| `hafen.ui():scrollbar()` | `Widget` | Unprotected | A bare scroll thumb, for driving something yourself. |
| `scrollbar:range(min, max)`, `scrollbar:value(n)` | as a [slider](#slider)'s | Unprotected | The same bounds and position. |
| `scrollbar:on("Changed", fn)` | `Sub` | Unprotected | `fn(value)` on every step: no `final` flag, since a bare scrollbar has no separate drag end. |

```lua
local items, visible_rows, first_row = { "oak", "birch", "spruce", "fir", "elm" }, 3, 0
local row_scrollbar = hafen.ui():scrollbar():size(14, 160):range(0, #items - visible_rows):value(0)
row_scrollbar:on("Changed", function(value) first_row = value end)
```

| Rule | Detail |
|---|---|
| A client scrollbar | Reports `Changed` from both of its writes, the thumb drag and the wheel or step buttons, and cannot be cancelled — [edit](../edit.md). Its rail and thumb are `scrollbar` and `scrollbar.knob`. |
| A list's own scrollbar | Reads its position off the list every frame, so `widget:value(n)` on it is refused rather than reverted. Drive the list: `widget:value(row)` on a listbox scrolls to that row, and the bar follows. |

---

## See Also

- [Controls](README.md) — the shared model: `:parent`, `:position`, sizing, owned vs borrowed.
- [Display](display.md) — the controls with nothing to click.
- [Widget](../widget.md) — everything a control answers before it adds its own.
- [Style](../style/README.md) — the rules that dress it.
