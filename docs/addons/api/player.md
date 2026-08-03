# hafen.player: the local character

`hafen.player()` returns the **Player object** for the character you are logged in as. Its main job is
being the anchor for your own [Gob](gob.md).

```lua
local me = hafen.player():gob()          -- nil until you are in the world
if me then hafen.log(string.format("at %.0f, %.0f", me:pos().x, me:pos().y)) end
```

Player deliberately forwards **nothing** from the Gob: position, health, movement and facing are read
on `hafen.player():gob()`, so there is exactly one way to reach each of them. What lives on Player is
only what has no per-gob equivalent.

## Read

| Method | Returns | Description |
|---|---|---|
| `hafen.player():gob()` | [Gob](gob.md) \| nil | your own game object; `nil` before you are in the world |
| `hafen.player():name()` | string \| nil | the local character's name |
| `hafen.player():worldToScreen(x, y)` | `{x, y}` \| nil | project a world point to a map-view screen pixel |

`hafen.player()` always hands back the same object, and `hafen.player():gob()` is the same object as
`hafen.gob(<your id>)` — so `gob == hafen.player():gob()` is how you tell "is this me?" from any other
gob, with no id comparison. Nothing here throws, and nothing is gated.

`worldToScreen` returns coordinates relative to the map view, which is what a
[gob overlay](ui/custom.md#overlays) or a HUD overlay wants. It answers `nil` before the map view exists, and
for a point the view cannot project. The inverse is [`hafen.world.screenToWorld`](world.md#screen-to-world-and-placement-snapping).

> There is no `exists()` and no `id()` on Player: `hafen.player():gob()`, `nil` or not, and `gob:id()`
> answer both questions.

The hp, stamina and energy bars are not here. They are a HUD slot the server fills rather than
per-player state, so they live in [`hafen.meter`](meter.md).

## See also

- [`hafen.gob`](gob.md) — everything positional about your character
- [`hafen.meter`](meter.md) — the HUD bars
- [`hafen.char`](char.md) — attributes, skills and food
- [`hafen.world`](world.md#screen-to-world-and-placement-snapping) — `screenToWorld`, the inverse projection
