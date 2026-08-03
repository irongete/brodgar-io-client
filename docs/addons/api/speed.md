# hafen.speed: movement speed

Read and set the crawl, walk, run and sprint selector. The speed is a number: `0` crawl, `1` walk, `2`
run, `3` sprint.

```lua
-- bump to the next available speed, wrapping
local cur, max = hafen.speed.get(), hafen.speed.max()
if cur and max then hafen.speed.set((cur + 1) % (max + 1)) end
```

There is no `SpeedChanged` event — read on demand.

## Read

| Function | Returns | Description |
|---|---|---|
| `hafen.speed.get()` | number \| nil | the current speed, `0..3` |
| `hafen.speed.max()` | number \| nil | the highest selectable speed; `0..max` are available |
| `hafen.speed.name(n)` | string \| nil | the display name of speed `n`, defaulting to the current one |

All three answer `nil` before the HUD's speed widget exists, which is until a beat after
`OnEnterWorld`. None throws.

## Write (gated: `actions`)

| Function | Description |
|---|---|
| `hafen.speed.set(n)` | select speed `n`, `0..3` |

Called from an addon that did not declare the permission, it raises an error; see
[`hafen.act`](act.md). It also raises one for an `n` outside `0..3`, and before the speed selector
exists. Within that range it drives the client's own control and sends exactly what a click would, so
the server decides whether the speed is currently allowed — a locked sprint is refused there, silently.

## See also

- [`hafen.act`](act.md) — the permission this write shares
- [`hafen.gob`](gob.md) — `gob:speed()`, the speed a body is actually moving at
