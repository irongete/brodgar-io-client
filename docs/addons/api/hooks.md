# hafen.hook — intercept & alter

Hooks let you intercept client behaviour *before* it happens and cancel or change it — not just
observe. Every hook is a **pre-hook**: your `fn(ev)` runs before the default, and `ev:preventDefault()`
cancels that default. Each returns a handle with `:remove()` (also auto-removed on reload/disable).

| Function | Level | Intercepts |
|---|---|---|
| `hafen.hook.input(target, event, fn)` | widget input | a client widget's mouse input, before the widget handles it |
| `hafen.hook.action(msg, fn)` | outbound action | a player action, before it is sent to the server |
| `hafen.hook.message(msg, fn)` | inbound message | a server update, before the widget applies it |
| `hafen.hook.grab{move, up}` | mouse capture | *(not a pre-hook)* capture the mouse for a press-drag-release loop |

Register hooks in `OnEnterWorld` (an input target widget must exist by then). Handlers run on the UI
thread — keep them light.

## `hafen.hook.input(target, event, fn)`

Fires before a client widget's own input handler. `target` is `"mapview"` (alias `"map"`), `"gameui"`
(alias `"hud"`), or `"root"`; `event` is `"mousedown"`, `"mouseup"`, `"mousemove"`, or `"mousewheel"`.

`ev` fields: `x`, `y` (widget-local pixels), plus `button` (down/up) or `amount` (wheel).
`ev:preventDefault()` cancels the input (which also stops it reaching child widgets — so there is no
separate `stopPropagation`).

```lua
-- swallow map clicks while "locked":
hafen.hook.input("mapview", "mousedown", function(ev)
  if locked then ev:preventDefault() end
end)
```

## `hafen.hook.action(msg, fn)`

Fires when a widget is about to send an action `msg` to the server, with the arguments **fully
resolved** (for a move `"click"`, the destination world coord — which doesn't exist yet at input time).

`ev` fields and methods:

| Member | Description |
|---|---|
| `ev.msg` | the message name |
| `ev.sender` | the sending widget's class name |
| `ev.args` | 1-based array snapshot of the arguments (a coord is `{x, y}`) |
| `ev:preventDefault()` | cancel the send |
| `ev:resend()` | re-send the original arguments verbatim (implies preventDefault) |
| `ev:send(t)` | send a new argument table (implies preventDefault) |

`resend`/`send` bypass the hook chain, so re-issuing an action can't loop — the "intercept my move, do
something, then move" pattern.

```lua
hafen.hook.action("click", function(ev)
  hafen.log("moving to " .. ev.args[2].x .. "," .. ev.args[2].y)
  ev:resend()   -- let the move happen anyway
end)
```

## `hafen.hook.message(msg, fn)`

The inbound mirror of `action`: fires when a server update `msg` is about to be applied to a widget.

`ev` fields and methods:

| Member | Description |
|---|---|
| `ev.msg` | the message name |
| `ev.target` | the receiving widget's class name |
| `ev.args` | 1-based array snapshot of the arguments |
| `ev:preventDefault()` | **swallow** the update (the widget never applies it) |
| `ev:rewrite(t)` | apply the update with new arguments |

`preventDefault` wins over `rewrite` if both are called.

```lua
-- freeze the vitals bars by swallowing meter updates:
hafen.hook.message("set", function(ev)
  if frozen and ev.target == "IMeter" then ev:preventDefault() end
end)
```

## `hafen.hook.grab`

`hafen.hook.grab{ move = fn, up = fn }` — **not a pre-hook**: it takes over the mouse for a
press-drag-release loop (the drag primitive a [ghost](ghost.md) gizmo is built on). While a grab is
active the map view neither pans nor clicks, so a drag leaves the **camera put**.

| Handler | Fires | Arguments |
|---|---|---|
| `move` | on every mouse move | `(x, y, mods)` — game-window pixels + `mods = {shift, ctrl, alt}` |
| `up` | once, on release (then auto-releases) | `(x, y, button, mods)` |

Returns a handle `{ :release() }` to end the grab early (the `up` handler also releases automatically).
Both handlers are optional. Pair it with [`hafen.map.screenToWorld`](map.md#screen--world--placement-snapping-v5)
(pixel → world) and [`snapPlace`](map.md#screen--world--placement-snapping-v5) (placegrid snapping) to drag
something along the ground:

```lua
-- move `ghost` with the mouse, snapped to the placegrid; click to drop:
local pending = false
local g = hafen.hook.grab{
  move = function(sx, sy, mods)
    if pending then return end                 -- coalesce: one raycast in flight at a time
    pending = true
    hafen.map.screenToWorld(sx, sy, function(w)
      pending = false
      if w then
        local s = hafen.map.snapPlace(w.x, w.y, mods.shift)   -- SHIFT = fine grid
        ghost:move(s.x, s.y)
      end
    end)
  end,
  up = function() hafen.log("dropped") end,    -- the grab auto-releases here
}
```

> Global hotkeys are not a hook level — see [`hafen.key`](keys.md).
