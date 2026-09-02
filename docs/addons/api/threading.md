# Threading: where your code runs

Your Lua runs in one of two kinds of place: on the **step**, which is inside no character's UI at all, or
**answering something**, inside the one character's UI that dispatched it. Which one you are in decides how
far you may reach, and it is the only threading fact the API asks you to hold.

The reason is that the client has more than one widget tree. Every character you are logged in as has its
own, and your own windows live in a tree of their own beside them. Each tree is guarded separately, and the
client's rule is that nothing ever holds two of those guards at once. Code on the step holds none, so it
reaches all of them; code answering a click holds exactly one, so it reaches that one.

```lua
-- the step: this may write any character's UI, and your own windows
hafen.event():on("Update", function(dt)
  for _, s in ipairs(hafen.session():list()) do
    local inv = s:ui():match("window[title=Inventory]")
    if inv then inv:visible(true) end
  end
end)
```

## Where each handler runs

| What you subscribed to | When it runs | Trees it may reach |
|---|---|---|
| [`hafen.event():on(key, fn)`](event/bus/README.md) — every event in the catalogue | the step, and for `GobAdded` before the object is drawn | any |
| [`hafen.timer()`](timer.md) — `:after`, `:every` | the step | any |
| [`widget:on("Update", fn)`](ui/custom.md) | the step | any |
| [`s:ui():on(sel, "Added"/"Removed", fn)`](ui/replace.md) | the step, after the widget arrived or left | any |
| [`item:on("Changed", fn)`](ui/items.md) | the step, after the description resolved | any |
| an [HTTP](http.md) reply | the step | any |
| [`hafen.event():message():on(msg, fn)`](event/streams.md) — inbound | as the update arrives, before the widget applies it | any |
| [`widget:on("Draw", fn)`](ui/custom.md) | the pass that paints that widget | its own only |
| `widget:on("MouseDown"/"MouseUp"/"MouseMove"/"Wheel", fn)` | the input that dispatched it | its own only |
| [`widget:on("Drop", fn)`](ui/custom.md), `"Close"` | the drop, the close button | its own only |
| a [control's](ui/controls/README.md) `"Pressed"`, `"Changed"`, `"Submitted"`, `"Selected"` | the press | its own only |
| a [drag or resize](ui/style/geometry.md) gesture, a [mouse grab's](ui/mouse.md) `"Move"`/`"Up"` | the gesture | its own only |
| [`keybindings():on(name, fn)`](client/keybindings.md) — a hotkey | the key press that fired it | the **drawn character's** only |
| [`hafen.event():action():on(msg, fn)`](event/streams.md) — outbound | the code that sent the message, before the server hears it | the **sender's** only |
| [`hafen.console():on(name, fn)`](console.md) | the line being typed | the console's own only |

Everything in the first group runs on the step, and everything in it may build a window, read one character
while writing another, and reach across every login the client holds. Everything in the last group is
answering a thing that is already in progress — a pass that is painting, a press that is waiting for an
answer, a message on its way out — and each of those is inside one tree and may touch only that one.

**The step is ordered ahead of the client's own drawing, for one event.**
[`GobAdded`](event/bus/world.md#before-the-first-drawn-frame) runs before the object it announces reaches
the scene: the client holds a newly arrived object out of the render tree until every handler has seen it,
so a label or a size written there is in force on that object's **first** drawn frame. Nothing else on the
step promises that. Every other handler in the first group runs on the next step after the thing it is
about, and the client has gone on drawing in between.

**A hotkey of yours runs in the character's tree, not in your own layer.** The client matches a key by
walking the widget tree of the character on screen, so your handler is answering inside *that* tree and may
write it freely — while your own window, which lives in the layer, is the second tree it may not reach.
Record what you want and let the step do it, exactly as a `Draw` handler does.

The inbound message stream is the one row in neither group: it holds no tree, so it reaches any of them,
but it is not the step. It answers the server's update **before** the widget applies it, which is what makes
[`ev:preventDefault()`](event/streams.md) and `ev:rewrite(t)` possible at all, and that answer has to be
given where the update arrived.

[`hafen.client():stepping()`](client/README.md#where-your-code-is-running) is how a helper called from both
groups tells which it is in.

## One tree at a time

Reaching a second tree while you hold one is refused, at the line that tried it. It is not a warning and not
a wait: the call raises, `pcall` catches it, and nothing is written.

```lua
local win = hafen.ui():window():title("Bags"):size(200, 120)     -- your own tree

win:on("Draw", function(ev)
  local inv = hafen.session():current():ui():match("window[title=Inventory]")
  if inv then inv:visible(false) end                             -- raises: a second tree
end)
```

The message names both trees, and the doors that do the same work holding neither:

```text
one tree monitor at a time: this handler already holds the widget tree of the addon layer, and
writing a widget of the character "yourname" would take a second one — two trees held at once is
the shape this client deadlocks in. A Draw handler, a control's own notification, a gesture, a
drop and a console line each run under one tree's monitor and may reach only that tree. Do the
work that crosses trees where no monitor is held: widget:on("Update", fn),
hafen.event():on("Update", fn) or hafen.timer():after(0, fn), all of which run on the engine
step, holding none.
```

> **It is the nesting that is refused, never the crossing.** The same write lands from the step, and a
> handler holding a tree may always write **that** tree — its own window, the widget it was handed, anything
> under the character whose press it is answering. Reaching one character's UI from another character's
> handler is exactly as legal as anything else, from the step.

`the addon layer` is where your own windows live — everything you build with
[`hafen.ui()`](ui/README.md) that you did not parent onto a character. A character's tree is named by the
account it is logged in as.

## Getting onto the step from a handler that holds a tree

Two doors, and both are one line:

```lua
win:on("Draw", function(ev)
  hafen.timer():after(0, function()                  -- the next step, holding nothing
    local inv = hafen.session():current():ui():match("window[title=Inventory]")
    if inv then inv:visible(false) end
  end)
end)
```

| Door | Use it when |
|---|---|
| [`hafen.timer():after(0, fn)`](timer.md) | you are inside a handler now and want this once, on the next step |
| [`hafen.event():on("Update", fn)`](event/bus/lifecycle.md) | the work is per-frame anyway, or you want it off the handler entirely |

Both hold no tree, so the body may reach anything. `widget:on("Update", fn)` is the same door addressed at
one of your own surfaces: it runs on the step like the bus's, not in the pass that draws that surface.

A `Draw` handler that wants to change another tree records what it wants and lets the step do it. That is
the shape for every one of these: decide in the handler, act on the step.

## What runs beside you

Your addon is one Lua state, and the step is not the only thing that enters it. An
[action](event/streams.md) handler runs where the message was sent, and an inbound
[message](event/streams.md) handler runs where the update arrived — so either can be running your Lua while
the step is running your Lua too. Two inbound updates aimed at different widgets can do it to each other.

What that means in practice is small, because it is the only case:

- **A table one of those handlers writes and the step reads can be read half-written.** Keep such a handler
  to recording what it saw — a field, a counter, an append — and do the work from the step.
- **A write on a game object is safe from either group.** [`gob:scale(k)`](gob.md#size-unprotected),
  [`gob:visible(b)`](gob.md#drawn-or-not-unprotected) and
  [`gob:overlay():add`/`:remove`](overlay.md#the-collection) may be made from a `Draw` handler and from the
  step at once, on the same object, and neither the object's place in the scene nor the value you wrote is
  the worse for it. The client serialises them per object.
- **Nothing else needs guarding.** Everything in the first group of the table above runs on the step, one
  after another, so an addon that only subscribes to events, timers and `Update` never meets this at all.

## Nothing blocks

There is no `sleep`, no waiting on a reply, no joining a thread. You schedule a callback and return, and
that holds in every row of the table. A handler that runs long is a frame the client did not draw, and one
that never returns would be the client stopping — so two limits catch it: an instruction budget per entry
into your Lua — one apiece for two of your callbacks running at once on the threads above — and a sustained
per-frame time budget that disables the addon rather than letting the client stutter on. Both
are on [budgets and the watchdog](../runtime.md#budgets-and-the-watchdog).

## See also

- [conventions](conventions.md) — the grammar every reference page assumes
- [`hafen.client()`](client/README.md#where-your-code-is-running) — `stepping()`, which of the two
  you are in
- [the message streams](event/streams.md) — the two handlers that do not run on the step
- [events and timers](../guides/events-and-timers.md) — choosing between an event, a timer and `Update`
- [custom windows](ui/custom.md) — `Update` and `Draw`, and what each may reach
