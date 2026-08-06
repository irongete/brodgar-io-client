# Events and timers

An addon is a set of callbacks. Your files run once and then nothing happens until something asks for
you: the client fires an [event](../api/event.md), a [timer](../api/timer.md) comes due, a hotkey is
pressed, a window draws. This guide is about picking the right one of those.

## One door, wherever you subscribe

Every reactive surface in the API is the same shape: `X:on(key, fn)` hands back a subscription, and
`sub:off()` ends it — idempotent, so a second call is harmless.

```lua
local sub = X:on(key, fn)
sub:off()
```

What differs is only **who `X` is**, and one question picks it: *do you already hold the thing you care
about?*

| You hold | You write |
|---|---|
| nothing — it is a client-wide fact | `hafen.event():on(key, fn)` |
| nothing — it is a message stream | `hafen.event():action():on(msg, fn)` / `:message():on(msg, fn)` |
| a widget, yours or one you found | `widget:on(key, fn)` |
| a control you built | `control:on(key, fn)` |

A subscription on any of these is owned by your addon and released on `:reload` or disable, so there is
nothing to unsubscribe by hand. **Two handlers on one key both fire**, in registration order; `off()` on
one leaves the other running. A handler that errors is isolated: the error is logged with your addon's
id, and your other handlers, the other addons and the client all keep going.

## The four moments every addon has

```lua
hafen.event():on("Load", function() end)          -- every file has run; not in the world yet
hafen.event():on("EnterWorld", function() end)     -- the HUD, the map and the player exist
hafen.event():on("Update", function(dt) end)       -- every frame; dt is seconds since the last one
hafen.event():on("Disable", function() end)        -- reload, disable, or the session ending
```

`EnterWorld` is where most addons really start: it fires at login **and again on every `:reload` while
you are in-world**, so an addon that builds its window there is correct after an edit as well as after a
login. `Disable` is your last chance to write anything you care about; the engine flushes your
[saved variables](saved-data.md) straight afterwards. Subscribe to these in your file body or in `Load`,
never inside another handler.

## The bus, a timer, or every frame

Ask for the cheapest thing that answers the question.

| You want | Use |
|---|---|
| to know when something changed | the [event](../api/event.md) for it |
| to know a value that has no event | a [timer](../api/timer.md), at the slowest interval you can live with |
| to do something *per frame* | `Update`, and nothing that scans |

```lua
hafen.timer():every(2, function()                  -- polling, twice as slow as it feels
  hafen.log():write("trees: " .. hafen.world():gob():count("terobjs/tree"))
end)

local handle = hafen.timer():after(5, function() end)
handle:cancel()
```

`Update` runs on the client's UI thread, in the middle of the frame it is drawing, so what it does you
pay for sixty times a second. The pattern that keeps it honest is a guard: compute a key, return
immediately when it has not changed, and only then do the work — that is how
[hit-testing](../api/ui/selectors.md#hit-testing) tracks the cursor without walking the tree every frame.

> Nothing here blocks. There is no `sleep` and no waiting on a request: you schedule a callback and return.
> An addon that overruns the frame budget for long enough is
> [auto-disabled](../runtime.md#budgets-and-the-watchdog).

## Character data arrives a beat late

`EnterWorld` fires when the HUD exists, not when it is full. Meters, skills, food, quests, wounds and the
kin roster stream in over the next few seconds, so a read at the top of `EnterWorld` legitimately answers
`nil`. Two ways round it, both ordinary:

```lua
hafen.event():on("EnterWorld", function()
  hafen.timer():after(2, function()                       -- ask again in a moment...
    local hp = hafen.meter():find("hp")
    hafen.log():write("hp: " .. tostring(hp and hp:value()))
  end)
end)

hafen.event():on("MeterChanged", function(m)             -- ...or let the client tell you
  if m:res() == "gfx/hud/meter/hp" then hafen.log():write("hp: " .. tostring(m:value())) end
end)
```

The second is better whenever an event exists, and one exists for most of what streams in.

## Lists arrive whole

The list events — `StudyChanged`, `EquipChanged`, `KinChanged`, `WoundChanged` — hand you the **new
list**, not what changed in it. Read the initial state once from the section's own verb, then keep your
own copy and diff it if you need to name the difference. The [catalogue](../api/event.md) says which
events carry what.

## Widgets are not on the bus

There is no `WidgetCreated` event, because a window is not a global fact: you say *which* widget you care
about, with a [selector](../api/ui/selectors.md), and
[`hafen.ui():on`](../api/ui/replace.md#watching-for-a-widget) waits for it — including one that is already
open when you subscribe.

```lua
hafen.ui():on("window[title=Cupboard]", "appear", function(w)
  hafen.log():write(#w:items() .. " items")
end)
```

Containers work the same way: subscribe to the container itself with
[`:on("ItemAdded", fn)`](../api/ui/items.md#the-container-lifecycle) rather than looking for an event about
chests — the same door you would reach for on any widget you hold, per the table above.

**Next:** [custom UI](custom-ui.md) — a window of your own, and what to draw in it.
