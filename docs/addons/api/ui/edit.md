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

## Taking over what a control does

A [control](controls/README.md)'s capability key answers on a **borrowed** control too. Same
[`:on(key, fn)`](widget.md#subscribing), same `sub:off()`, same rule that two handlers both fire:

| Key | Fires on | `ev` answers |
|---|---|---|
| `Pressed` | a button of the client's own — from a click, and from its keybinding | `:preventDefault()` `:resend()` |

Name the control the ordinary way, with a [selector](selectors.md). Every window carries a close button,
so `win:find("@IButton")` is the one control you can reach without knowing what a window is made of.

**And on a borrowed control the key is cancelable, because there is something underneath to cancel.**
`ev:preventDefault()` stops the client's own action: the button was pressed, and what the client would have
done about it does not happen. On a control **you built** the same key carries no `ev` at all — your
handler *is* the action, so there is nothing under it to stop. One key, two provenances, and
[the subscribing table](widget.md#subscribing) is where that is written down.

Cancelling is **OR across every handler and every addon** of one press: any one of them cancels, all of
them still run, and the outcome never depends on which addon loaded first.

## Running the action yourself

`ev:resend()` runs the action the control already had — the client's own method, exactly as the press
would have reached it:

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

A resent press does what the user's own press would have done, the message the client sends the server
included. It stays unprotected because it cannot invent one: it re-issues the gesture the user just made,
and it only exists because they made it.

## See also

- [replace](replace.md) — the other thing you can do to one of the client's windows
- [widget](widget.md#subscribing) — `:on(key, fn)`, the `ev`, and which writes answer on a borrowed widget
- [controls](controls/README.md) — the same capability keys on a control your addon built
- [native](native.md) — moving and hiding one of the client's widgets
- [selectors](selectors.md) — naming the control you are about to take over
