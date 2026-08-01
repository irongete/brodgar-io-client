# hafen.player — the local player

`hafen.player()` returns the **Player object** for the local character. Its main job is being the
anchor for your own [Gob](gob.md):

```lua
local me = hafen.player():gob()          -- nil until you are in the world
if me then hafen.log(tostring(me:pos().x)) end
```

Player deliberately forwards **nothing** from the Gob: position, health, movement, facing and the rest
are read on `hafen.player():gob()`, so there is exactly one way to reach each of them. What lives on
Player is only what has no per-gob equivalent.

| Method | Returns | Description |
|---|---|---|
| `hafen.player():gob()` | [Gob](gob.md) \| nil | your own game object; nil before you are in the world |
| `hafen.player():name()` | string \| nil | the local character's name |
| `hafen.player():worldToScreen(x, y)` | `{x, y}` \| nil | project a world point to a map-view screen pixel |

`hafen.player()` always hands back the same object, and `hafen.player():gob()` is the same object as
`hafen.gob(<your id>)` — so `gob == hafen.player():gob()` is how you tell "is this me?" apart from any
other gob (no id comparison needed).

> The hp/stamina/energy bars are **not** here: they are a HUD slot the server fills, not per-player
> state, so they live in their own section — [`hafen.meter`](meters.md).
>
> `worldToScreen` returns coordinates relative to the map view — handy inside a
> [gob overlay](ui.md#overlays) or HUD overlay.
>
> There is no `exists()` and no `id()`: `hafen.player():gob()` (nil or not) and `gob:id()` answer both.
