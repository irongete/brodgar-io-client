# hafen.player — local player data

Data about the local character that has **no** per-gob equivalent. For the player's position, health,
movement, etc., use [`hafen.gob.*("player")`](gob.md) instead.

| Function | Returns | Description |
|---|---|---|
| `hafen.player.exists()` | bool | whether the player gob is in the world |
| `hafen.player.id()` | number \| nil | the player's gob id (the `"player"` GobRef) |
| `hafen.player.name()` | string \| nil | the local character's name |
| `hafen.player.vitals()` | [`Vitals`](types.md#vitals) \| nil | hp/stamina/energy bar fractions (0..1) |
| `hafen.player.worldToScreen(x, y)` | `{x, y}` \| nil | project a world point to a map-view screen pixel |

```lua
local v = hafen.player.vitals()
if v then hafen.log(string.format("hp %.0f%%  stamina %.0f%%", v.hp * 100, v.stamina * 100)) end
```

> `vitals()` returns **bar fractions only** — there are no absolute hp/stamina/energy numbers, and no
> hunger, in the client. Subscribe to [`VitalsChanged`](events.md#character--status-widget-tree-backed)
> for updates; `vitals()` is `nil` until the meters stream in a beat after `OnEnterWorld`.
>
> `worldToScreen` returns coordinates relative to the map view — handy inside a
> [gob overlay](ui.md#overlays) or HUD overlay.
