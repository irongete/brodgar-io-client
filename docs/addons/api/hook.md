# hafen.hook: intercept and alter

Hooks let you intercept client behaviour *before* it happens and cancel or change it, rather than only
observing it. Reach for one when an event on the [bus](events.md) would arrive too late to matter. Every
hook is a **pre-hook**: your `fn(ev)` runs before the default, and `ev:preventDefault()` cancels that
default.

| Function | Level | Intercepts |
|---|---|---|
| [`hafen.hook.input(target, event, fn)`](#hafenhookinputtarget-event-fn) | widget input | a client widget's mouse input, before the widget handles it |
| [`hafen.hook.action(msg, fn)`](#hafenhookactionmsg-fn) | outbound action | a player action, before it is sent to the server |
| [`hafen.hook.message(msg, fn)`](#hafenhookmessagemsg-fn) | inbound message | a server update, before the widget applies it |
| [`hafen.hook.grab{move, up}`](#hafenhookgrabmove-up) | mouse capture | *not a pre-hook*: capture the mouse for a press-drag-release loop |

Each returns a handle with `:remove()`, and each is removed automatically on reload or disable. Register
hooks in `OnEnterWorld`, since an input target widget must exist by then. Handlers run on the UI thread, so
keep them light.

Hooking is **ungated**: it observes and cancels the client's own behaviour rather than sending anything.
Re-sending an action through [`ev:send`](#hafenhookactionmsg-fn) reissues what the client was already
about to send.

## `hafen.hook.input(target, event, fn)`

Fires before a client widget's own input handler. `target` is `"mapview"` (alias `"map"`), `"gameui"`
(alias `"hud"`) or `"root"`; `event` is `"mousedown"`, `"mouseup"`, `"mousemove"` or `"mousewheel"`.

`ev` carries `x` and `y` in widget-local pixels, plus `button` on a down or up and `amount` on a wheel.
`ev:preventDefault()` cancels the input, which also stops it reaching child widgets — so there is no
separate propagation verb.

```lua
-- swallow map clicks while "locked":
hafen.hook.input("mapview", "mousedown", function(ev)
  if locked then ev:preventDefault() end
end)
```

## `hafen.hook.action(msg, fn)`

Fires when a widget is about to send an action `msg` to the server, with the arguments **fully resolved** —
for a move `"click"`, that is the destination world coordinate, which does not exist yet at input time.

| Member | Description |
|---|---|
| `ev.msg` | the message name |
| `ev.sender` | the sending widget's class name |
| `ev.args` | a 1-based array snapshot of the arguments; a coordinate is `{x, y}` |
| `ev:preventDefault()` | cancel the send |
| `ev:resend()` | re-send the original arguments verbatim; implies `preventDefault` |
| `ev:send(t)` | send a new argument table; implies `preventDefault` |

`resend` and `send` bypass the hook chain, so re-issuing an action cannot loop. That is the "intercept my
move, do something, then move" pattern.

```lua
hafen.hook.action("click", function(ev)
  hafen.log("moving to " .. ev.args[2].x .. "," .. ev.args[2].y)
  ev:resend()   -- let the move happen anyway
end)
```

## `hafen.hook.message(msg, fn)`

The inbound mirror of `action`: fires when a server update `msg` is about to be applied to a widget.

| Member | Description |
|---|---|
| `ev.msg` | the message name |
| `ev.target` | the receiving widget's class name |
| `ev.args` | a 1-based array snapshot of the arguments |
| `ev:preventDefault()` | **swallow** the update, so the widget never applies it |
| `ev:rewrite(t)` | apply the update with new arguments |

`preventDefault` wins over `rewrite` if both are called.

```lua
-- freeze the HUD meter bars by swallowing their updates:
hafen.hook.message("set", function(ev)
  if frozen and ev.target == "IMeter" then ev:preventDefault() end
end)
```

## `hafen.hook.grab{move, up}`

Not a pre-hook: it takes over the mouse for a press-drag-release loop, the drag primitive a
[ghost](ghost.md#the-transform-gizmo) gizmo is built on. While a grab is active the map view neither pans
nor clicks, so a drag leaves the **camera put**.

| Handler | Fires | Arguments |
|---|---|---|
| `move` | on every mouse move | `(x, y, mods)` — game-window pixels, and `mods = {shift, ctrl, alt}` |
| `up` | once, on release, which also auto-releases | `(x, y, button, mods)` |

Both handlers are optional. It returns a handle with `:release()` to end the grab early; the `up` handler
releases automatically. Pair it with
[`hafen.world.screenToWorld`](world.md#screen-to-world-and-placement-snapping), pixel to world, and
[`snapPlace`](world.md#screen-to-world-and-placement-snapping), placement-grid snapping, to drag something
along the ground:

```lua
-- move `ghost` with the mouse, snapped to the placement grid; click to drop:
local pending = false
local g = hafen.hook.grab{
  move = function(sx, sy, mods)
    if pending then return end                 -- coalesce: one raycast in flight at a time
    pending = true
    hafen.world.screenToWorld(sx, sy, function(w)
      pending = false
      if w then
        local s = hafen.world.snapPlace(w.x, w.y, mods.shift)   -- Shift = fine grid
        ghost:move(s.x, s.y)
      end
    end)
  end,
  up = function() hafen.log("dropped") end,    -- the grab auto-releases here
}
```

## See also

- [`hafen.client:options():keybindings()`](client/keybindings.md) — global hotkeys, which are not a hook
  level: they run through the client's binding registry, after the client's own bindings
- [`hafen.world`](world.md#screen-to-world-and-placement-snapping) — the coordinate half of a drag
- [`hafen.ghost`](ghost.md#the-transform-gizmo) — what a grab is normally dragging
- [events](events.md) — the observe-only bus, for everything a hook does not need to cancel
- [`hafen.act`](act.md) — sending an action yourself, which is gated
