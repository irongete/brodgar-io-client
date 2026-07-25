# hafen.speed — movement speed

Read and set the crawl/walk/run/sprint speed selector. Speed is `0` = crawl, `1` = walk, `2` = run,
`3` = sprint. `set` is **gated** — it requires the [`actions` permission](actions.md).

## Read

| Function | Returns | Description |
|---|---|---|
| `hafen.speed.get()` | number \| nil | the current speed, 0..3 |
| `hafen.speed.max()` | number \| nil | the highest currently selectable speed (0..max are available) |
| `hafen.speed.name([n])` | string \| nil | the display name of speed `n` (default = current) |

## Write *(gated — requires the `actions` permission)*

| Function | Description |
|---|---|
| `hafen.speed.set(n)` | select speed `n` (0..3) |

```lua
-- a speed-toggle: bump to the next available speed, wrapping
local cur, max = hafen.speed.get(), hafen.speed.max()
if cur and max then hafen.speed.set((cur + 1) % (max + 1)) end
```

There is no `SpeedChanged` event — read on demand.
