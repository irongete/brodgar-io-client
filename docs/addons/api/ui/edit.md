# hafen.ui: editing the client's own windows

**Editing** is changing one part of a window the client built and leaving the rest of it alone — taking
over what one of its buttons does, without rebuilding anything around it. [Replacing](replace.md) is the
other answer to the same question: it hides the window and hands the whole job to you. Reach for editing
when the change is smaller than the window.

Nothing here is a new section, a new object or a new verb. Every name below is one you already use on a
widget your addon built; what this page says is where the same name also answers on a widget you did not.

```lua
hafen.session():current():ui():on("window[title=Options]", "appear", function(win)
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
local win = hafen.session():current():ui():find("window[title=Options]")
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

## Your own controls inside one of the client's windows

`w:parent(win)` builds a [control](controls/README.md) of yours into one of the client's windows, and
`w:pack()` refits that window around what is now inside it:

```lua
local win = hafen.session():current():ui():find("window[title=Options]")
hafen.ui():button():text("Reload"):parent(win):position(0, win:size().h)
win:pack()
```

| Call | Does |
|---|---|
| `w:parent(win)` | puts the control **you are building** inside one of the client's windows; chains |
| `w:pack()` | refits a native **window** around what is inside it, your control included; chains |
| `w:size(nil)` | drops your level — the [stock outer box](native.md#moving-and-resizing-unprotected) comes back; chains |

**Adoption is a build-time verb.** `:parent(w)` chooses where a control is born, so it answers while the
control is still being built and refuses on one already on screen, naming `w:position(x, y)` — moving a
widget the user is looking at is what that verb has always been. You build a control *into* one of the
client's windows; you do not re-home one that is standing somewhere else.

**A child's place is the content area**, the same space the window's own controls sit in: `(0, 0)` is under
the caption bar, not the window's outer corner. The window's own height is therefore a place below
everything it is showing, which is the pair above — put the control there, then `:pack()` to bring the frame
down around it. For anything more exact, a [geometry rule](style/geometry.md) anchors your control to one of
the window's own widgets, so it follows what it sits under rather than a pixel the client is free to move.

**`:pack()` is a level over the window's box**, exactly as `:size(w, h)` is: what the pack came out at is
what your addon is holding, and `w:size(nil)`, disabling your addon and `:reload` all give the stock outer
box back. A window that packs itself around its own contents — the main inventory is one — takes the call
and undoes it before it returns: **inert, never an error**, the same rule `:size(w, h)` follows there.
Anything of the client's that is **not** a window refuses it, naming `:size(w, h)`: what box a widget the
client laid out is drawn in is the client's to choose, and the window around it is what refits.

**A control of yours dies with the window you built it into.** Its `Destroy` fires when that window is
destroyed and `:exists()` is `false` from that moment, with nothing to clean up — while a window the client
merely hides has not gone anywhere, so nothing fires and your control comes back with it.

## Taking over what a control does

A [control](controls/README.md)'s capability key answers on a **borrowed** control too. Same
[`:on(key, fn)`](widget.md#subscribing), same `sub:off()`, same rule that two handlers both fire:

| Key | Fires on | `ev` answers |
|---|---|---|
| `Pressed` | a button of the client's own — from a click, and from its keybinding | `:preventDefault()` `:resend()` |
| `Changed` | a checkbox or a radio button of the client's own — from a click, and from its keybinding | `:value()` `:preventDefault()` `:resend()` |
| `Changed` | a [listbox or dropdown](lists.md) of the client's own — a row picked, or the selection cleared by a click on empty space | `:value()` `:preventDefault()` `:resend()` |
| `Selected` | a [menu](lists.md#menu) of the client's own | `:value()` `:preventDefault()` `:resend()` |
| `Cell` | a [grid](lists.md#grid) of the client's own, **for the selecting button only** | `:value()` `:preventDefault()` `:resend()` |
| `Submitted` | a [text entry](controls/interactive.md#text-entry) of the client's own, when Enter is pressed in it | `:value()` `:preventDefault()` `:resend()` |
| `Changed` | a [slider or scrollbar](controls/interactive.md#slider) of the client's own — a drag step, and a scrollbar's wheel and steps | `:value()` |

Name the control the ordinary way, with a [selector](selectors.md). Every window carries a close button,
so `win:find("@IButton")` is the one control you can reach without knowing what a window is made of.

**A grid fires only for the button that selects**: a right-click on a cell opens the client's own menu and
moves nothing, so it is no selection, and a key that fired for it would let one handler swallow that menu.

**Enter is the whole of `Submitted`**, on a borrowed entry as on one you built: a keystroke that merely
changes the text is not a submission, and there is no key for one. Cancelling it means **the server never
hears the line** — the client's own handling of that entry does not run at all, so the chat line stays in
the field rather than being sent and cleared.

## The key that only reports

A slider and a scrollbar are the one family that writes its value **before** it says anything, and a drag
emits a stream of these. So their `Changed` is a report rather than a question: `ev:preventDefault()` and
`ev:resend()` both **raise** there, naming that the value has already moved.

```lua
local vol = hafen.session():current():ui():find("window[title=Options]"):all("@HSlider")[1]
vol:on("Changed", function(ev) hafen.log():write("now at " .. ev:value()) end)
```

`ev:value()` is where the control landed, and `w:value()` reads the same number a moment later. Putting the
thumb back is a write rather than a cancel, so nothing here pretends otherwise.

## Which widget a list's key belongs to

A [dropdown](lists.md#dropdown)'s rows live in a popup list of their own, and a [menu](lists.md#menu)'s in an
inner list — neither of which is the control you hold, and a dropdown's popup is not even inside it. The key
fires on the **control**: `s:ui():all("@SDropBox")[1]:on("Changed", fn)` is where a dropdown's row
arrives. Subscribing on the list of rows instead raises, naming the control the key fires on — the address
picks the door, and there is exactly one door per control. A list that is a control in its own right is its
own address, which is the ordinary case.

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
[The one key that reports](#the-key-that-only-reports) is where *about to* stops applying — a slider and a
scrollbar have already moved when they tell you, and `ev:value()` is where they moved to.

## Reading what a borrowed control holds

`w:value()` answers on one of the client's own controls, the same verb it answers with on a
[control you built](controls/README.md#setters) — a checkbox's boolean, a radio's row, a slider's and a
scrollbar's number, a text field's string, a list's or dropdown's row. You point at a radio **button** and
read the row its whole set holds, for the same reason its `Changed` carries one. A widget that holds nothing
reads `nil` rather than raising, exactly like [`:text()`](widget.md#read).

It is a read: unprotected, no layer, nothing to restore — and it is what makes everything on this page
*checkable*, since what a cancelled tick left the box at is a question with an answer. A row of one of the
client's **own** lists comes back as an opaque handle: hold it, compare it with `==`, tell one selection from
the next — but there is nothing inside it to read. The rows you can read are the ones you gave a control.

## Driving one (protected)

`w:value(v)` writes what one of the client's own controls holds, by doing what the user would do: it runs
the control through the very method their gesture ends in, so what the client sends the server it sends,
and whatever that particular control was built to do is what happens.

```lua
local box = hafen.session():current():ui():find("window[title=Options]"):all("@CheckBox")[1]
box:value(not box:value())             -- ticked, exactly as a click would have ticked it
```

| Control | `v` is | The drive |
|---|---|---|
| a checkbox | a boolean | ticks it or clears it |
| a radio button | one of its group's row labels | moves the whole group's selection to that row |
| a slider, a scrollbar | a number | moves it there, clamped into the bounds the control carries |
| a text entry | a string | replaces the line in the field |
| a list, a dropdown | a row **of that list** | picks it |

It needs the `widget.value` [permission key](../../guides/permissions.md) declared in your manifest, and
without it the call raises naming that key **before** it looks at the value you passed. It is the one
protected verb on this page, and it falls on the line stated at the top: what a widget **says** never leaves
the client, and what a control **holds** does.

**It is an act, not a layer** — the opposite of `:text(s)` in every respect. There is no `:value(nil)`,
nothing is recorded, and neither `:reload` nor disabling your addon puts a driven control back: the write
went to the server as a real interaction, and putting the box back is another interaction, not an undo.

**And a write is not an interaction, so it fires nothing.** No `Changed` of yours runs from a `:value(v)`,
whichever control it lands on — the capability keys report what the *user* did, and a handler woken by your
own write is a loop waiting to happen. Read the control back to see where it landed.

**What refuses**: a value of the wrong shape for the control, a row that is not in the radio's set — naming
the rows that are — a row that is not the list's, and a widget that holds nothing at all, naming what does.
So does one of the client's own progress bars: what it draws is a value the client re-reads every frame, so
a write there would be gone before it was seen.

## Running the action yourself

`ev:resend()` runs the action the control already had — the client's own method, exactly as the gesture
would have reached it. A button's is its click; a checkbox's is the flip, and a radio button's the pick
that moves the set's selection. A list's is the selection change, run on the very list the click went
through, so a dropdown you let through still closes its popup and a menu still fires its own choice. A text
entry's is the submission, run on the entry itself, so a chat line you let through goes as it was written:

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
[outbound action](../event/streams.md#intercepting-an-outbound-action), where there is a message with
arguments to rewrite. What is held back here is a **method**, so there is nothing to say with it, and the
spelling refuses naming `resend`.

A resent gesture does what the user's own would have done, the message the client sends the server included.
It stays unprotected because it cannot invent one: it re-issues the gesture the user just made, and it only
exists because they made it — the whole difference from [`w:value(v)`](#driving-one-protected), which acts
from nothing and is keyed for it.

## A native control inside one of yours

A [control you built](controls/README.md) is often made of the client's own smaller ones: a dropdown's drop
arrow is a checkbox the client builds inside it. Those read **borrowed** — `:info().owned` is `false` — and
this page applies to them, in the middle of a control that is yours.

The rule that keeps that from doubling up: **the addon that owns a control keeps the dispatch it already had
and never also receives it here.** A `Changed` on a dropdown you built is still the row the user picked; the
arrow inside it is a separate widget with a key of its own, which you reach by pointing at it. Its popup
list has no key of its own and is covered by [the address rule](#which-widget-a-lists-key-belongs-to) above.

## Four writes to one window

Everything above, on one of the client's windows, in one subscription: what it says, a control of your own
inside it, the frame refitted around that control, and its close button doing what you say instead.

```lua
hafen.session():current():ui():on("window[title=Options]", "appear", function(win)
  win:title("Options, edited")                    -- what the window says
  local go = hafen.ui():button()                  -- ...a control of yours, inside the client's frame
    :text("Reload addons")
    :parent(win)
    :position(0, win:size().h)                    -- below everything it is showing
  go:on("Pressed", function() hafen.log():write("pressed") end)
  win:pack()                                      -- ...and the frame comes down around it
  win:find("@IButton"):on("Pressed", function(ev)
    ev:preventDefault()                           -- ...while its X does nothing at all
  end)
end)
```

That is the whole argument for editing over [replacing](replace.md): the window is still the client's, it
still fills itself, and everything above comes off again when your addon does.

## Taking the whole edit back

`w:revert()` gives back in one call everything **your addon** holds on a widget and on everything inside it:
the text, the place, the size, the hide, your own [`w:rule()`](style/README.md#restyle-one-widget) level,
every subscription you hold anywhere in that subtree, and every control you adopted into it, destroyed.
Unprotected, like every undo of an unprotected write, and it chains.

```lua
local keys = hafen.client():options():keybindings()
local armed = false

keys:on("edit", function()               -- the user assigns the key in Options > Keybindings
  local win = hafen.session():current():ui():find("window[title=Options]")
  if not win then return end
  if armed then win:revert() else win:title("Options, edited") end
  armed = not armed
end)
```

**The scope is that widget and everything under it, as the tree stands when you call it**, because an edit is
never confined to one widget: [the example above](#four-writes-to-one-window) writes on the window, adopts a
control into it and takes over its close button, which is neither of the other two. It is per widget, so an
addon that edited two windows gives one of them back and keeps the other — and on a widget you hold nothing
on it does nothing and raises nothing, which is what lets a toggle like the one above keep no record of what
it wrote.

**A control you adopted is destroyed rather than dropped**, so its `Destroy` fires exactly as it would have
when the window closed; a widget you had hidden comes back under
[the hide's own rule](native.md#hiding-a-native-widget-carries-a-restore) — as the user was seeing it.

**Two things it leaves standing**, each having an undo of its own. `w:value(v)` is an act the server has
already seen, so putting the control back is another interaction rather than an undo. And
[replacing](replace.md) a window is the alternative to editing rather than a part of it, and
`w:replace(nil)` ends it — a stand-in window of yours is left alone even where a revert reaches it.

**It is not a small `:reload`.** Disabling your addon or reloading it gives all of this back too, because
every undo on this page runs at teardown; `revert()` is how an addon gives one window back **while it goes on
running**.

## See also

- [replace](replace.md) — the other thing you can do to one of the client's windows
- [widget](widget.md#subscribing) — `:on(key, fn)`, the `ev`, and which writes answer on a borrowed widget
- [controls](controls/README.md) — the same capability keys on a control your addon built
- [native](native.md) — moving and hiding one of the client's widgets, and letting the user drag or size it
- [selectors](selectors.md) — naming the control you are about to take over
