# hafen.ui: editing the client's own windows

**Editing** is changing one part of a window the client built and leaving the rest of it alone — taking
over what one of its buttons does, without rebuilding anything around it. [Replacing](replace.md) is the
other answer to the same question: it hides the window and hands the whole job to you. Reach for editing
when the change is smaller than the window.

Nothing here is a new section, a new object or a new verb. Every name below is one you already use on a
widget your addon built; what this page says is where the same name also answers on a widget you did not.

```lua
hafen.ui():on("window[title=Options]", "appear", function(win)
  win:find("@IButton"):on("Pressed", function(ev)
    ev:preventDefault()                  -- the X on this window does nothing while your addon is loaded
  end)
end)
```

## What a window says

`w:text(s)` writes what one of the client's own widgets says — a label, a button's caption, a checkbox's
label — and `w:title(s)` writes a window's caption. It is the split you already use on a widget you built:
a **title** is a window's caption, **text** is everything else, and each verb refuses on the other's widget
naming the one that answers there.

```lua
local win = hafen.ui():find("window[title=Options]")
win:title("Options, edited")
win:all("@Button")[1]:text("Go")
```

| Call | Does |
|---|---|
| `w:text()` | reads what a widget says, best-effort, on any widget at all; `nil` where it says nothing |
| `w:text(s)` | writes a label, a button's caption or a checkbox's label; chains |
| `w:text(nil)` | drops **your** level — the stock text comes back; chains |
| `w:title()` | reads a window's caption, `nil` on anything that is not a window |
| `w:title(s)` | writes a window's caption; chains |
| `w:title(nil)` | drops your level, exactly as `:text(nil)` does; chains |

**It is a level over the client's text, never a write into it.** The first time you write, what the widget
said is recorded; `:text(nil)`, `:title(nil)`, disabling your addon and `:reload` all give it back —
including the colour and the wrapping a caption was rendered with, which are part of it and not decoration
you can put back yourself. A **second write replaces your level rather than stacking on it**, so one `nil`
is always enough however many times you wrote. Two addons may each hold a caption on one widget: the last
one wins on screen and each gives back what *it* found, the same rule [placing one](native.md) follows.

**The server rewriting the text does not take your level off.** When an update from the server writes what
one of these widgets says — a window's caption, a label, a button's — your level goes back on within a
frame of it landing, and what `:text(nil)` or `:title(nil)` gives back afterwards is the **server's latest**
value rather than the one it replaced. So a caption of yours does not vanish minutes later from a message
nobody saw, and dropping a level hands the user the text they are meant to be reading, never a stale one.

What you write is held on the **widget**, and [the client reuses its windows](native.md#the-client-reuses-its-windows):
a caption can outlive what the window it sits on means.

Both verbs are **unprotected**, and the reason is the line this whole page turns on: what a widget **says**
never leaves the client. What a control **holds** is `w:value(v)`, and the server sees that — which is why
`:text(s)` on one of the client's text entries refuses, naming it.

Nothing here is style. There is no `text` property in the [stylesheet](style/README.md): what one widget
says is a fact about that one widget, not a rule about a kind of them.

**What refuses, and what each refusal names**: a text entry (→ `w:value(v)`), a window (→ `w:title(s)`),
anything that is not a window on `:title(s)` (→ `w:text(s)`), and a widget with nothing to say at all — the
close button on every window is three pictures and has no caption to write.

## Taking over what a control does

A [control](controls/README.md)'s capability key answers on a **borrowed** control too. Same
[`:on(key, fn)`](widget.md#subscribing), same `sub:off()`, same rule that two handlers both fire:

| Key | Fires on | `ev` answers |
|---|---|---|
| `Pressed` | a button of the client's own — from a click, and from its keybinding | `:preventDefault()` `:resend()` |
| `Changed` | a checkbox or a radio button of the client's own — from a click, and from its keybinding | `:value()` `:preventDefault()` `:resend()` |
| `Changed` | a [list or dropdown](lists.md) of the client's own — a row picked, or the selection cleared by a click on empty space | `:value()` `:preventDefault()` `:resend()` |
| `Selected` | a [menu](lists.md#menu) of the client's own | `:value()` `:preventDefault()` `:resend()` |
| `Cell` | a [grid](lists.md#grid) of the client's own, **for the selecting button only** | `:value()` `:preventDefault()` `:resend()` |
| `Submitted` | a [text entry](controls/interactive.md#text-entry) of the client's own, when Enter is pressed in it | `:value()` `:preventDefault()` `:resend()` |
| `Changed` | a [slider or scrollbar](controls/interactive.md#slider) of the client's own — a drag step, and a scrollbar's wheel and steps | `:value()` |

Name the control the ordinary way, with a [selector](selectors.md). Every window carries a close button,
so `win:find("@IButton")` is the one control you can reach without knowing what a window is made of.

**A grid fires only for the button that selects.** A right-click on a cell opens the client's own menu and
moves nothing, so it is not a selection and there is nothing there to cancel — a key that fired for it would
let one handler swallow that menu.

**Enter is the whole of `Submitted`**, on a borrowed entry as on one you built: a keystroke that merely
changes the text is not a submission, and there is no key for one. Cancelling it means **the server never
hears the line** — the client's own handling of that entry does not run at all, so the chat line stays in
the field rather than being sent and cleared.

## The key that only reports

A slider and a scrollbar are the one family that writes its value **before** it says anything, and a drag
emits a stream of these. So their `Changed` is a report rather than a question: `ev:preventDefault()` and
`ev:resend()` both **raise** there, naming that the value has already moved.

```lua
local vol = hafen.ui():find("window[title=Options]"):all("@HSlider")[1]
vol:on("Changed", function(ev) hafen.log():write("now at " .. ev:value()) end)
```

`ev:value()` is where the control landed, and `w:value()` a moment later reads the same number. Putting the
thumb back is a write, not a cancel, so nothing here pretends to be one: a verb that silently did nothing
would be worse than the error.

## Which widget a list's key belongs to

A [dropdown](lists.md#dropdown)'s rows live in a popup list of their own, and a [menu](lists.md#menu)'s in an
inner list — neither of which is the control you hold, and a dropdown's popup is not even inside it. The key
fires on the **control**:

```lua
local box = hafen.ui():all("@SDropBox")[1]
box:on("Changed", function(ev) hafen.log():write("would pick " .. tostring(ev:value())) end)
```

Subscribing on the list of rows instead raises, naming the control the key fires on — the address picks the
door, and there is exactly one door per control. A list that is a control in its own right is its own address,
which is the ordinary case.

**And on a borrowed control the key is cancelable, because there is something underneath to cancel.**
`ev:preventDefault()` stops the client's own action: the button was pressed, and what the client would have
done about it does not happen. On a control **you built** the same key carries no `ev` at all — your
handler *is* the action, so there is nothing under it to stop. One key, two provenances, and
[the subscribing table](widget.md#subscribing) is where that is written down.

Cancelling is **OR across every handler and every addon** of one press: any one of them cancels, all of
them still run, and the outcome never depends on which addon loaded first.

## What the control is about to hold

`ev:value()` is the value the control would take **if the client goes on** — read *before* the change, which
is what makes cancelling mean *it did not happen* rather than *it happened and was undone*:

```lua
box:on("Changed", function(ev)
  if ev:value() == true then ev:preventDefault() end   -- this box may be cleared, never ticked
end)
```

A checkbox's is the flipped tick, and a radio button's is **the row the selection is about to move to** —
because what a radio holds is a row, and it is the button you point at only because the set that holds the
row is not a widget. A list's, a dropdown's, a menu's and a grid's is the row or cell the click landed on, and
`nil` where the click landed on empty space and would clear the selection. A text entry's is the line about
to be submitted. `ev:value()` is on every control event and reads `nil` where the key carries nothing:
`Pressed` is an activation, so there is nothing it is about to hold.

[The one key that reports](#the-key-that-only-reports) is where *about to* stops applying: a slider and a
scrollbar have already moved when they tell you, and `ev:value()` is where they moved to.

## Reading what a borrowed control holds

`w:value()` answers on one of the client's own controls, the same verb it answers with on a
[control you built](controls/README.md#setters) — a checkbox's boolean, a radio's row, a slider's and a
scrollbar's number, a text field's string, a list's or dropdown's row. You point at a radio **button** and
read the row its whole set holds, for the same reason its `Changed` carries one. A widget that holds nothing
reads `nil` rather than raising, exactly like [`:text()`](widget.md#read):

```lua
local box = hafen.ui():find("window[title=Options]"):all("@CheckBox")[1]
box:on("Changed", function(ev)
  hafen.log():write(tostring(box:value()) .. " -> " .. tostring(ev:value()))
end)
```

It is a read: unprotected, no layer, nothing to restore. It is also what makes everything on this page
*checkable* — what a cancelled tick left the box at is a question with an answer.

A row of one of the client's **own** lists is its own private thing rather than something you wrote, so it
comes back as an opaque handle: you can hold it, compare it with `==` and tell one selection from the next,
but there is nothing inside it to read. The rows you can read are the ones you gave a control yourself.

## Running the action yourself

`ev:resend()` runs the action the control already had — the client's own method, exactly as the gesture
would have reached it. A button's is its click; a checkbox's is the flip, and a radio button's the pick that
moves the whole set's selection. A list's is the selection change, run on the very list the click went
through — so a dropdown you let through still closes its popup, and a menu still fires its own choice. A
text entry's is the submission, run on the entry itself, so a chat line you let through leaves the client
exactly as the player wrote it:

```lua
btn:on("Pressed", function(ev)
  if hafen.ui():mouse():shift() then
    ev:resend()                          -- shift-click goes through, this once
  else
    ev:preventDefault()
  end
end)
```

- **It implies `ev:preventDefault()`**, so the action happens exactly once however many handlers ask for it.
- **It does not re-enter any handler for that key**, so re-issuing cannot loop.
- **It may be called from a later frame**, and more than once — each call runs the action once. The
  subscription fires after the client has released its mouse grab, so nothing is left in flight waiting for
  your answer, and a handler may destroy the window the button sits in.
- **It raises on a widget that has left the tree**, naming that, where most writes on a stale widget are a
  silent no-op: the point of re-issuing is that something happens, so silence there would be a lie.

There is no `ev:send(t)` beside it — that verb exists on an
[outbound action](../event.md#intercepting-an-outbound-action), where there is a message with arguments to
rewrite. What is held back here is a **method**, so there is nothing to say with it, and the spelling
refuses naming `resend`.

A resent gesture does what the user's own would have done, the message the client sends the server
included. It stays unprotected because it cannot invent one: it re-issues the gesture the user just made,
and it only exists because they made it.

## A native control inside one of yours

A [control you built](controls/README.md) is often made of the client's own smaller ones: a dropdown's drop
arrow is a checkbox the client builds inside it. Those read **borrowed** — `:info().owned` is `false` — and
this page applies to them, in the middle of a control that is yours.

The rule that keeps that from doubling up: **the addon that owns a control keeps the dispatch it already
had and never also receives it here.** A `Changed` on a dropdown you built is still the row the user picked,
fired the one way it always was; the arrow inside it is a separate widget with a key of its own, and you
reach it by pointing at it. Its popup list, which is not a widget with a key of its own, is covered by
[the address rule](#which-widget-a-lists-key-belongs-to) above rather than by this one.

## See also

- [replace](replace.md) — the other thing you can do to one of the client's windows
- [widget](widget.md#subscribing) — `:on(key, fn)`, the `ev`, and which writes answer on a borrowed widget
- [controls](controls/README.md) — the same capability keys on a control your addon built
- [native](native.md) — moving and hiding one of the client's widgets
- [selectors](selectors.md) — naming the control you are about to take over
