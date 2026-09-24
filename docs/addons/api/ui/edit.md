# hafen.ui: Editing the Client's Windows

Editing changes one part of a window the client built and leaves the rest alone. That part is what it says, a control of yours inside it, or what its own controls do. [Replacing](replace.md) hides the window and hands the whole job to you.

```lua
hafen.session():current():ui():on("window[title=Options]", "Added", function(options_window)
  options_window:match("@IButton"):on("Pressed", function(press)
    press:preventDefault()                -- the X on this window does nothing while your addon is loaded
  end)
end)
```

Nothing here is a new verb: every name is one you use on a widget your addon built, answering on one you did not.

---

## What a window says

| Method | Returns | Permission | Description |
|---|---|---|---|
| `widget:text()` | `string \| nil` | Unprotected | What a widget says, best-effort, on any widget. `nil` where it says nothing. |
| `widget:text(s)` | `self` | Unprotected | Writes a label, a button's caption or a checkbox's label. |
| `widget:text(nil)` | `self` | Unprotected | Drops your level. The stock text comes back. |
| `widget:title()` | `string \| nil` | Unprotected | A window's caption. `nil` on anything that is not a window. |
| `widget:title(s)`, `widget:title(nil)` | `self` | Unprotected | Writes or drops a window's caption, as `:text` does. |

```lua
local options_window = hafen.session():current():ui():match("window[title=Options]")
options_window:title("Options, edited")
options_window:matchAll("@Button")[1]:text("Go")
```

| Rule | Detail |
|---|---|
| A level, not a write into the client | The first write records what the widget said. `:text(nil)`, `:title(nil)`, disable and `:reload` give it back, colour and wrapping included. A second write replaces your level, so one `nil` is always enough. Two addons may each hold a caption: the last wins on screen, each gives back what it found. |
| The server rewriting the text | Your level goes back on within a frame of the update. What `:text(nil)` gives back afterwards is the server's latest value. |
| Held on the widget | [The client reuses its windows](native.md#the-client-reuses-its-windows), so a caption can outlive what its window means. |
| Not style | There is no `text` property in the [stylesheet](style/README.md): what one widget says is a fact about that widget. |
| Refusals | A text entry, naming `:value(v)`: its content reaches the server. A window on `:text(s)`, naming `:title(s)`. A non-window on `:title(s)`, naming `:text(s)`. A widget with nothing to say: a window's close button is three pictures. |

---

## Your own controls inside one of the client's windows

| Method | Returns | Permission | Description |
|---|---|---|---|
| `control:parent(window)` | `self` | Unprotected | Builds the control you are building into a client window. Build-time: refused on a control already on screen, naming `:position(x, y)`. |
| `window:pack()` | `self` | Unprotected | Refits the client window around what is inside it, your control included. |
| `window:size(nil)` | `self` | Unprotected | Drops your level. The [stock box](native.md#moving-and-resizing-unprotected) comes back. |

```lua
hafen.ui():button():text("Reload"):parent(options_window):position(0, options_window:size().h)
options_window:pack()
```

| Rule | Detail |
|---|---|
| A child's place is the content area | `(0, 0)` is under the caption bar, so the window's own height is a place below everything it shows. A [geometry rule](style/geometry.md) anchors your control to one of the window's own widgets for anything more exact. |
| `:pack()` is a level | What the pack came out at is your size level: `:size(nil)`, disable and `:reload` give the stock box back. A window that packs itself (the main inventory) undoes it before the call returns, inert. A client widget that is not a window refuses, naming `:size(w, h)`. |
| Your control dies with the window | Its `Removed` fires when the window is destroyed and `:exists()` is `false` from then. A window the client merely hides brings your control back with it. |
| The other direction | A client widget into a surface of yours is [`widget:parent(p)`](native.md#taking-one-into-a-surface-of-your-own-unprotected), and `:parent(nil)` gives it back. |

---

## Taking over what a control does

A [control](controls/README.md)'s capability key answers on a borrowed control with the same `:on(key, fn)`, the same `sub:off()`, two handlers both firing. It adds an `event` that can stop or run the client's own action.

| Key | Fires on | `event` answers |
|---|---|---|
| `Pressed` | A client button, from a click and from its keybinding. | `:preventDefault()`, `:resend()` |
| `Changed` | A client checkbox or radio button, from a click and its keybinding. | `:value()`, `:preventDefault()`, `:resend()` |
| `Changed` | A client [listbox or dropdown](lists.md): a row picked, or the selection cleared by a click on empty space. | `:value()`, `:preventDefault()`, `:resend()` |
| `Selected` | A client [menu](lists.md#menu). | `:value()`, `:preventDefault()`, `:resend()` |
| `Cell` | A client [grid](lists.md#grid), for the selecting button only: a right-click opens the client's menu and is no selection. | `:value()`, `:preventDefault()`, `:resend()` |
| `Submitted` | A client [text entry](controls/interactive.md#text-entry) on Enter, and Enter alone. Cancelled, the server never hears the line: the chat line stays in the field. | `:value()`, `:preventDefault()`, `:resend()` |
| `Changed` | A client [slider or scrollbar](controls/interactive.md#slider): a drag step, a scrollbar's wheel and steps. Reports only — [below](#the-key-that-only-reports). | `:value()` |

| Rule | Detail |
|---|---|
| Naming the control | A [selector](selectors.md). Every window has a close button, so `window:match("@IButton")` reaches a control without knowing what the window is made of. |
| A borrowed control can be rebuilt under you | The client remakes whole columns of its windows when a setting changes (Options rebuilds its video column). A control dying as a descendant fires no `Removed`. Your takeover stops on a control that looks the same. Re-arm from [`session:ui():on(sel, "Added", fn)`](replace.md). |
| Cancelling | `event:preventDefault()` stops the client's own action. It is OR across every handler and every addon of one press: any one cancels, all run, order does not matter. On a control you built the key carries no `event`: your handler is the action. |
| Which widget a list's key belongs to | A dropdown's rows live in a popup list and a menu's in an inner list. The key fires on the control (`session:ui():matchAll("@SDropBox")[1]:on("Changed", fn)`), and subscribing on the list of rows raises, naming the control. |

### The key that only reports

A slider and a scrollbar write their value before they report, so their `Changed` is a report. `event:preventDefault()` and `event:resend()` raise there, naming that the value has already moved. `event:value()` is where the control landed. Putting the thumb back is a write.

```lua
local volume = hafen.session():current():ui():match("window[title=Options]"):matchAll("@HSlider")[1]
volume:on("Changed", function(event) hafen.log():write("now at " .. event:value()) end)
```

### What the control is about to hold

`event:value()` is the value the control takes if the client goes on, read before the change, so cancelling means it did not happen.

| Control | `event:value()` |
|---|---|
| A checkbox | The flipped tick. |
| A radio button | The row the selection is about to move to. |
| A list, a dropdown, a menu, a grid | The row or cell the click landed on. `nil` for a click on empty space that would clear the selection. |
| A text entry | The line about to be submitted. |
| A button | `nil`: `Pressed` is an activation. |
| A slider, a scrollbar | Where it moved to. |

```lua
local options_checkbox = options_window:matchAll("@CheckBox")[1]
options_checkbox:on("Changed", function(event)
  if event:value() == true then event:preventDefault() end   -- this box may be cleared, never ticked
end)
```

---

## Reading what a borrowed control holds

`widget:value()` answers on a client control as on [one you built](controls/README.md#setters). A checkbox's boolean. A radio's row, read from any of its buttons. A slider's or scrollbar's number. A text field's string. A list's or dropdown's row. A [key button](controls/interactive.md#key-button)'s key, spelled as [`binding:key()`](../client/keybindings.md#the-binding-object) spells it, `nil` for unbound. A widget that holds nothing reads `nil`. Unprotected, no layer. A row of one of the client's own lists is a [Row](lists.md#reading-one): compare it with `==`, and read it with `row:text()`, `row:group()` and `row:info()`.

---

## Driving one (protected)

| Method | Returns | Permission | Description |
|---|---|---|---|
| `widget:value(v)` | `self` | `widget.value` | Drives a client control as the user would. The control runs through the method the user's gesture ends in, so the client sends the server what it sends. The key is checked before the value is read. |

```lua
local options_checkbox = hafen.session():current():ui():match("window[title=Options]"):matchAll("@CheckBox")[1]
options_checkbox:value(not options_checkbox:value())    -- ticked, exactly as a click would tick it
```

| Control | `v` is | The drive |
|---|---|---|
| A checkbox | A boolean | Ticks or clears it. |
| A radio button | One of its group's row labels | Moves the whole group's selection to that row. |
| A slider, a scrollbar | A number | Moves it there, clamped into the control's bounds. |
| A text entry | A string | Replaces the line in the field. |
| A list, a dropdown | A row of that list | Picks it. |
| A colour row (the kin colours) | A number, `0..254` | Picks that group, as clicking a square does. `widget:value()` reads the group it shows, `nil` for none. |

| Rule | Detail |
|---|---|
| An act, not a layer | No `:value(nil)`. Nothing is recorded. Neither `:reload` nor disable puts a driven control back. The write went to the server as an interaction. |
| It fires nothing | No `Changed` of yours runs from a `:value(v)`. Read the control back to see where it landed. |
| Refusals | A value of the wrong shape. A row not in the radio's set (naming the rows). A row not the list's. A widget that holds nothing (naming what does). A client progress bar, whose value the client re-reads every frame. A key button, before the permission is asked: its key is its binding's, written with [`binding:key(key)`](../client/keybindings.md#the-binding-object) under `client.settings`. |

---

## Running the action yourself

| Method | Returns | Permission | Description |
|---|---|---|---|
| `event:resend()` | — | `ui.resend` | Runs the action the control already had, as the gesture would have reached it. A button's click. A checkbox's flip. A radio's pick. A list's selection change on the very list the click went through (a dropdown closes its popup, a menu fires its choice). An entry's submission on the entry itself. |

```lua
local close_button = options_window:match("@IButton")
close_button:on("Pressed", function(press)
  if hafen.ui():mouse():shift() then
    press:resend()                        -- shift-click goes through, this once
  else
    press:preventDefault()
  end
end)
```

| Rule | Detail |
|---|---|
| Its own key | `ui.resend`, narrower than `widget.send`: it re-runs the press the user already made, the message the client sends included. |
| Implies `preventDefault()` | The action happens exactly once however many handlers ask. It re-enters no handler for that key. |
| Once per event | Callable from a later frame: the client has released its mouse grab, and the handler may destroy the window. A second `resend()` on the same event raises. |
| A stale widget | Raises, naming that: nothing was re-sent. |
| No `event:send(t)` | That verb belongs to an [outbound action](../event/streams.md#intercepting-an-outbound-action), where a message has arguments to rewrite. Here a method is held back, and the spelling refuses naming `resend`. |

---

## A native control inside one of yours

A control you built is often made of the client's smaller ones: a dropdown's arrow is a client checkbox. Those read borrowed (`:info().owned` is `false`), so this page applies to them. The addon that owns a control keeps the dispatch it had and never also receives it here. Your `Changed` on the dropdown is the picked row. The arrow is a separate widget with a key of its own. Its popup list is covered by [the address rule](#taking-over-what-a-control-does).

## The edits on one window

```lua
hafen.session():current():ui():on("window[title=Options]", "Added", function(options_window)
  options_window:title("Options, edited")                  -- what the window says
  local reload_button = hafen.ui():button()                -- a control of yours, inside the client's frame
    :text("Reload addons")
    :parent(options_window)
    :position(0, options_window:size().h)                  -- below everything it is showing
  reload_button:on("Pressed", function() hafen.log():write("pressed") end)
  options_window:pack()                                    -- the frame comes down around it
  options_window:match("@IButton"):on("Pressed", function(press)
    press:preventDefault()                                 -- its X does nothing
  end)
end)
```

The window stays the client's, still fills itself, and everything above comes off when your addon does.

---

## Taking the whole edit back

| Method | Returns | Permission | Description |
|---|---|---|---|
| `widget:revert()` | `self` | Unprotected | Gives back everything your addon holds on the widget and on everything under it, as the tree stands. That is the text, the place, the size, the hide, your [`widget:rule()`](style/README.md#restyle-one-widget) level, and every subscription you hold in that subtree. Every control you adopted into it is destroyed (its `Removed` fires). A widget you hold nothing on is a no-op. |

```lua
local keybindings = hafen.client():options():keybindings()
local armed = false
keybindings:on("edit", function()          -- the user assigns the key in Options > Game > Keybindings
  local options_window = hafen.session():current():ui():match("window[title=Options]")
  if not options_window then return end
  if armed then options_window:revert() else options_window:title("Options, edited") end
  armed = not armed
end)
```

| Rule | Detail |
|---|---|
| Scope | That widget and everything under it, so an addon that edited two windows gives one back. A hidden widget comes back under [the hide's own rule](native.md#hiding-a-native-widget-carries-a-restore). |
| Left standing | `widget:value(v)`, an act the server has seen. And a [replacement](replace.md), which `widget:replace(nil)` ends. |
| Not a small `:reload` | Disable and `:reload` run every undo here at teardown. `revert()` gives one window back while the addon keeps running. |

---

## See Also

- [Replace](replace.md) — the other thing to do to a client window.
- [Widget](widget.md#subscribing) — `:on(key, fn)` and the `event`.
- [Controls](controls/README.md) — the same capability keys on a control you built.
- [Native](native.md) — moving and hiding a client widget, and letting the user drag or size it.
- [Selectors](selectors.md) — naming the control you are about to take over.
