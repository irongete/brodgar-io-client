# Events and Timers

An addon is a set of callbacks. Your files run once. Then nothing happens until the client fires an [event](../api/event/bus/README.md), a [timer](../api/timer.md) comes due, a hotkey is pressed or a window draws. This guide picks the right one.

```lua
hafen.event():on("SessionEnteredWorld", function(session)
  hafen.timer():after(2, function()                       -- ask again in a moment...
    local health_meter = session:meter():find("hp")        -- that character's own bars
    hafen.log():write("hp: " .. tostring(health_meter and health_meter:segment():list()[1]:value()))
  end)
end)
hafen.event():on("MeterChanged", function(meter, session)  -- ...or let the client tell you
  if meter:res() == "gfx/hud/meter/hp" then hafen.log():write(session:user() .. " hp changed") end
end)
```

---

## One door, wherever you subscribe

Every reactive surface is `X:on(key, fn)` handing back a subscription, and `subscription:off()` ending it (idempotent). Only who `X` is differs, and whether you already hold the thing you care about picks it.

| You hold | You write |
|---|---|
| Nothing. It is a client-wide fact | `hafen.event():on(key, fn)`. |
| Nothing. It is a message stream | `hafen.event():action():on(msg, fn)` / `:message():on(msg, fn)`, and `"*"` for [every message on one](../api/event/streams.md#the-whole-stream). |
| A widget, yours or one you found | `widget:on(key, fn)`. |
| A control you built | `control:on(key, fn)`. |

| Rule | Detail |
|---|---|
| Owned by your addon | Released on `:reload` or disable. Nothing to unsubscribe by hand. |
| Several handlers | Two on one key both fire in registration order. `off()` on one leaves the other. An erroring handler is logged with your addon's id and isolated. One failing the client itself stops [your addon](../runtime.md#when-a-failure-is-fatal). |

## The moments every addon has

```lua
hafen.event():on("Load", function() end)                     -- every file has run; not in the world yet
hafen.event():on("Update", function(dt) end)                 -- every frame; dt is seconds since the last one
hafen.event():on("Disable", function() end)                  -- reload, disable, or the client closing
hafen.event():on("SessionEnteredWorld", function(session)    -- a character's HUD, map and player exist
end)
```

| Rule | Detail |
|---|---|
| Your addon's own | `Load`, `Update` and `Disable` fire once each for the client however many characters are logged in. `Disable` is your last chance to write. The client flushes your [vars](saved-data.md) afterwards. Subscribe in your file body or `Load`, never inside another handler. |
| A session's | `SessionEnteredWorld` fires once per character reaching the world, handing that [`Session`](../api/session.md). It fires again on a `:reload` for every login in the world. A window built there is correct after an edit, and for the characters you are not looking at. The other [session events](../api/event/bus/lifecycle.md#sessions) say when one connects, takes the screen and ends. |

## The bus, a timer, or every frame

| You want | Use |
|---|---|
| To know when something changed | The [event](../api/event/bus/README.md) for it. |
| To know a value that has no event | A [timer](../api/timer.md), at the slowest interval you can live with. |
| To do something per frame | `Update`, and nothing that scans. |

```lua
local poll = hafen.timer():every(2, function()     -- polling, every two seconds
  local session = hafen.session():current()
  if session then hafen.log():write("trees: " .. session:world():gob():count("terobjs/tree")) end
end)
hafen.log():write("next poll in " .. poll:due() .. "s")   -- the handle answers for its own schedule
poll:cancel()
```

| Rule | Detail |
|---|---|
| `Update` costs sixty times a second | It runs on the client's [step](../api/threading.md), inside no character's UI, so it may reach every login. Guard it: compute a key, return when it has not changed, then do the work. That is how [hit-testing](../api/ui/selectors.md#hit-testing) tracks the cursor without walking the tree every frame. |
| Nothing blocks | No `sleep`, no waiting on a request: schedule a callback and return. An addon overrunning the frame budget long enough is [auto-disabled](../runtime.md#budgets-and-the-watchdog). |

## Character data arrives after the HUD

`SessionEnteredWorld` fires when the HUD exists, not when it is full. Meters, skills, food, quests, wounds and the kin roster stream in over the next seconds. A read at the top of the handler answers `nil`. Ask again on a timer, or subscribe to the event (the opening example does both). The event is better whenever one exists.

| Rule | Detail |
|---|---|
| An event about a character says which | `MeterChanged` and every other event about one character hands its [`Session`](../api/session.md) as the last argument. `function(meter)` goes on working, since Lua drops an undeclared argument. The [catalogue](../api/event/bus/README.md#whose-character-it-was) lists which carry one. |
| Lists arrive whole | `StudyChanged`, `EquipChanged`, `KinChanged`, `WoundChanged` hand the new list, not the change. Read the initial state once from the section's verb, keep your copy and diff it. |

## Widgets are not on the bus

There is no `WidgetCreated`: you say which widget with a [selector](../api/ui/selectors.md), and [`session:ui():on`](../api/ui/replace.md#watching-for-a-widget) waits for it, one already open included. Containers work the same way: subscribe to the container with [`:on("ItemAdded", fn)`](../api/ui/container.md).

```lua
hafen.session():current():ui():on("window[title=Cupboard]", "Added", function(window)
  hafen.log():write(window:items():count() .. " items")
end)
```

**Next:** [custom UI](custom-ui.md) — a window of your own, and what to draw in it.
