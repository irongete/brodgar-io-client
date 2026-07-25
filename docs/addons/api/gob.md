# hafen.gob — game objects, by reference

The canonical per-object accessor. Every verb takes a
[GobRef](conventions.md#gobref--a-game-object) `ref` — a gob id, `"player"`/`"me"`, `"partyN"`, or
`nil` (= you) — re-resolves it on each call, and returns `nil` if the gob is gone.

Use these to read one object fresh. To read many at once, use [`hafen.world`](world.md); to react to
objects appearing/leaving, use the `GobAdded`/`GobRemoved` [events](events.md#world).

| Function | Returns | Description |
|---|---|---|
| `hafen.gob.exists(ref)` | bool | whether the gob currently resolves |
| `hafen.gob.info(ref)` | [`Gob`](types.md#gob) \| nil | a full snapshot in one call |
| `hafen.gob.pos(ref)` | `{x, y}` \| nil | world position |
| `hafen.gob.facing(ref)` | number \| nil | facing angle, radians |
| `hafen.gob.name(ref)` | string \| nil | resource/type identity (not a display name) |
| `hafen.gob.health(ref)` | number \| nil | 0..1 remaining object integrity (1 = undamaged) |
| `hafen.gob.moving(ref)` | bool \| nil | whether it is moving |
| `hafen.gob.speed(ref)` | number \| nil | movement speed, or nil if not moving |
| `hafen.gob.speech(ref)` | string \| nil | current floating speech text |
| `hafen.gob.icon(ref)` | string \| nil | minimap icon/category name |
| `hafen.gob.distance(ref [, ref2])` | number \| nil | distance between two gobs; `ref2` defaults to `"player"` |

```lua
if hafen.gob.exists("player") then
  local p = hafen.gob.pos("player")
  hafen.log(string.format("at %.0f, %.0f", p.x, p.y))
end

local d = hafen.gob.distance("party1")   -- how far the first party member is from me
```

> `hafen.gob.name` returns the **type** resource (e.g. `"gfx/borka/body"` for a player body), not a
> character's display name — those aren't available for arbitrary gobs. See [Gob](types.md#gob).
