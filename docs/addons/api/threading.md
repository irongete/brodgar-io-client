# Threading: Where Your Code Runs

Your Lua runs in one of two kinds of place. On the step, inside no character's UI. Or answering something, inside the one character's UI that dispatched it. Which one decides how far you may reach. It is the only threading rule in the API.

```lua
-- the step: this may write any character's UI, and your own windows
hafen.event():on("Update", function(dt)
  for _, session in ipairs(hafen.session():list()) do
    local inventory = session:ui():match("window[title=Inventory]")
    if inventory then inventory:visible(true) end
  end
end)
```

---

The client has more than one widget tree: one per logged-in character, and one of your own beside them for your windows. Each is guarded separately and nothing ever holds two guards at once. Code on the step holds none and reaches all. Code answering a click holds one and reaches that one.

## Where each handler runs

| Subscribed to | Runs | Reaches |
|---|---|---|
| [`hafen.event():on(key, fn)`](event/bus/README.md), every catalogue event but the rows marked below | The step. For `GobAdded`, before the object is drawn. | Any tree. |
| [`hafen.timer()`](timer.md) `:after`, `:every` | The step. | Any. |
| [`widget:on("Update", fn)`](ui/custom.md) | The step. | Any. |
| [`session:ui():on(selector, "Added"/"Removed", fn)`](ui/replace.md) | The step, after the widget arrived or left. | Any. |
| [`item:on("Changed", fn)`](ui/items.md) | The step, after the description resolved. | Any. |
| An [HTTP](http.md) reply | The step. | Any. |
| A [connection](websocket.md)'s `Open`, `Message`, `Close`, `Error` | The step. | Any. |
| A [voice link](voice/link.md)'s `Open`, `Close`, `Error` and the Peer keys | The step. | Any. |
| [`options:panel(fn)`](client/addon.md#the-page) | The step, one frame after the page is opened. | Any. |
| [`hafen.event():message():on(msg, fn)`](event/streams.md), inbound | As the update arrives, before the widget applies it. | Any, not the step. |
| [`widget:on("Draw", fn)`](ui/custom.md) | The pass that paints that widget. | Its own only. |
| `widget:on("MouseDown"/"MouseUp"/"MouseMove"/"Wheel", fn)` | The input that dispatched it. | Its own only. |
| [`widget:on("Drop", fn)`](ui/custom.md), `"Close"` | The drop, the close button. | Its own only. |
| A [control's](ui/controls/README.md) `"Pressed"`, `"Changed"`, `"Submitted"`, `"Selected"` | The press. | Its own only. |
| The `fn` of [a petal you added](flowermenu.md#write-unprotected) | The pick. | The ring's own only. |
| A [drag or resize](ui/style/geometry.md) gesture, a [mouse grab's](ui/mouse.md) `"Move"`/`"Up"` | The gesture. | Its own only. |
| [`keybindings():on(name, fn)`](client/keybindings.md), a hotkey | The key press that fired it. | The drawn character's only. |
| [`hafen.event():action():on(msg, fn)`](event/streams.md), outbound | The code that sent the message, before the server hears it. | The sender's only. |
| [`hafen.console():on(name, fn)`](console.md) | The line being typed. | The console's own only. |
| [`FlowerMenuAdded`, `FlowerMenuRemoved`](event/bus/character.md) | The ring going up or down, before its first drawn frame. | The ring's own only. |
| [`GhostClicked`, `SpriteClicked`](event/bus/world.md#world-ghosts-and-sprites) and an entity's `onClick` | The click pass that resolved the pick. | The clicked scene's only. |
| [`session:world():screenToWorld(point, fn)`](world.md) and its `fn` | That pass, once the ground under the point is known. | The scene's only. |

| Rule | Detail |
|---|---|
| The step group | May build a window, read one character while writing another, reach every login. Every other row answers something in progress (a pass painting, a press waiting, a message leaving, a ring the server put up) inside one tree. |
| `GobAdded` is ordered ahead of drawing | The client holds a new object out of the render tree until every handler has seen it. A label or a size written there is in force on its first drawn frame ([before the first drawn frame](event/bus/world.md#before-the-first-drawn-frame)). Every other step handler runs on the step after the thing it is about. |
| A hotkey runs in the character's tree | The client matches a key by walking the drawn character's tree. Your handler may write that tree, and not your own window in the layer. Record what you want and let the step do it. |
| The inbound stream is in neither group | Holds no tree, so it reaches any, but is not the step. It answers before the widget applies the update, where the update arrived. That makes [`event:preventDefault()`](event/streams.md) and `event:rewrite(table)` possible. |
| Which am I in | [`hafen.client():stepping()`](client/README.md#where-your-code-is-running). |

## One tree at a time

Reaching a second tree while you hold one is refused at the line that tried it: the call raises, `pcall` catches it, nothing is written.

```lua
local bags_window = hafen.ui():window():title("Bags"):size(200, 120)     -- your own tree
bags_window:on("Draw", function(draw_event)
  local inventory = hafen.session():current():ui():match("window[title=Inventory]")
  if inventory then inventory:visible(false) end                         -- raises: a second tree
end)
```

The message names both trees and the doors that do the same work holding neither:

```text
one tree monitor at a time: this handler already holds the widget tree of the addon layer, and
writing a widget of the character "yourname" would take a second one — two trees held at once is
the shape this client deadlocks in. A Draw handler, a control's own notification, a gesture, a
drop and a console line each run under one tree's monitor and may reach only that tree. Do the
work that crosses trees where no monitor is held: widget:on("Update", fn),
hafen.event():on("Update", fn) or hafen.timer():after(0, fn), all of which run on the engine
step, holding none.
```

| Rule | Detail |
|---|---|
| The nesting is refused, never the crossing | The same write lands from the step. A handler holding a tree may write that tree: its own window, the widget it was handed, anything under the character whose press it answers. |
| The names | `the addon layer` is where your own windows live, everything built with [`hafen.ui()`](ui/README.md) not parented onto a character. A character's tree is named by its account. |

## Getting onto the step from a handler that holds a tree

```lua
bags_window:on("Draw", function(draw_event)
  hafen.timer():after(0, function()                  -- the next step, holding nothing
    local inventory = hafen.session():current():ui():match("window[title=Inventory]")
    if inventory then inventory:visible(false) end
  end)
end)
```

| Door | Use it when |
|---|---|
| [`hafen.timer():after(0, fn)`](timer.md) | You are inside a handler and want this once, on the next step. |
| [`hafen.event():on("Update", fn)`](event/bus/lifecycle.md) | The work is per-frame anyway, or you want it off the handler. |

Both hold no tree. `widget:on("Update", fn)` is the same door on one of your surfaces: it runs on the step, not in the pass that draws the surface. Decide in the handler, act on the step.

## One at a time, in your own Lua

| Rule | Detail |
|---|---|
| One entry at a time | Your addon is one Lua state entered from several client threads. The client lets one be inside your code at a time, so shared tables and upvalues are never read half-written. Holds across every row: the step, an inbound update, an HTTP reply, a connection's `Message`, a `Draw` pass. |
| Serialised is not sequenced | An inbound update and the step's `Update` are two moments in no order. Anything needing a fixed order belongs in one place. |
| A tree handler may be skipped rather than made to wait | The second group holds a tree's guard when it reaches you, and waiting there deadlocks. So when your Lua is busy on another thread that call does not happen. A `Draw` keeps the previous frame's picture. A click or a `screenToWorld` answer is not delivered. It takes your code running on two threads at once. A short handler never meets it. A `Draw` that must not miss a frame draws what the step worked out. |
| A write on a game object is safe from every row | [`gob:scale(k)`](look.md#size-unprotected), [`gob:visible(flag)`](look.md#drawn-or-not-unprotected), [`gob:tint(color)`](look.md#tint-unprotected), [`gob:outline(color, width)`](look.md#outline-unprotected) and [`gob:overlay():add`/`:remove`](overlay.md#the-collection) from a `Draw` handler and the step at once are serialised per object. |
| A logged line from inside a tree lands a frame later | The chat is a widget tree. A [line](log.md) from a `Draw` handler, a control's notification or a gesture is held and posted by the next step, whole and in order. |

## Nothing blocks

No `sleep`, no waiting on a reply, no joining a thread: schedule a callback and return. A handler that runs long is a dropped frame. One that never returns would stop the client. The limits that catch it are on [budgets and the watchdog](../runtime.md#budgets-and-the-watchdog). One is an instruction budget per entry into your Lua, one apiece for two callbacks running at once. The other is a sustained per-frame time budget that disables the addon.

---

## See Also

- [Conventions](conventions.md) — the grammar every reference page assumes.
- [`hafen.client()`](client/README.md#where-your-code-is-running) — `stepping()`, which of the two you are in.
- [The message streams](event/streams.md) — the handlers that do not run on the step.
- [Events and timers](../guides/events-and-timers.md) — choosing between an event, a timer and `Update`.
- [Custom windows](ui/custom.md) — `Update` and `Draw`, and what each may reach.
