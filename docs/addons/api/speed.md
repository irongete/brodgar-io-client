# hafen.speed: movement speed

Read and set the crawl, walk, run and sprint selector. The speed is a number: `0` crawl, `1` walk, `2`
run, `3` sprint.

```lua
-- bump to the next available speed, wrapping
local speed = hafen.speed()
local cur, max = speed:current(), speed:max()
if cur and max then speed:current((cur + 1) % (max + 1)) end
```

`current()` reads and `current(n)` writes — one name for the property, with the argument saying which you
meant. There is no `SpeedChanged` event — read on demand.

## Read

| Method | Returns | Description |
|---|---|---|
| `hafen.speed():current()` | number \| nil | the current speed, `0..3` |
| `hafen.speed():max()` | number \| nil | the highest selectable speed; `0..max` are available |
| `hafen.speed():name(n)` | string \| nil | the display name of speed `n`, defaulting to the current one |

All three answer `nil` before the HUD's speed widget exists, which is until a beat after
`EnterWorld`. None throws. `name()` with no argument is the speed you are on; `name(nil)` is an error
rather than a shorthand for it.

## Write (protected)

| Method | Key | Description |
|---|---|---|
| `hafen.speed():current(n)` | `speed.current` | select speed `n`, `0..3`; returns the section, so writes chain |

Called from an addon that did not declare the `speed.current` key, it raises an error naming that key; see
[the permission model](conventions.md#the-permission-model). It also raises one for an `n` outside
`0..3`, and before the speed selector exists. Within that range it drives the client's own control and
sends exactly what a click would, so
the server decides whether the speed is currently allowed — a locked sprint is refused there, silently.

## See also

- [permissions](../guides/permissions.md) — the permission this write shares
- [Gob](gob.md) — `gob:speed()`, the speed a body is actually moving at
