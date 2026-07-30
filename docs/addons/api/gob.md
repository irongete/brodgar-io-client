# hafen.gob — game objects, as objects

`hafen.gob(id)` mints a **Gob object**; you read it with methods:

```lua
local gob = hafen.gob(4711)
hafen.log(tostring(gob:name()) .. " hp=" .. tostring(gob:health()))
```

A Gob wraps **only the id**. Every method re-resolves the object against the client's live object cache,
so a handle you keep in a variable is **always fresh** — it tracks a gob as it moves, and its methods
return `nil` once the gob is gone (`:id()` keeps answering). Nothing is cached, nothing goes stale.

`hafen.gob(id)` always returns a Gob, even for an id that is not loaded (or never existed) — that is
what lets you anchor to a gob before it streams in. Use `:exists()` to test liveness. A non-number
argument is an error.

Use a Gob to read one object; to read many at once use [`hafen.world`](world.md); to react to objects
appearing/leaving use the `GobAdded`/`GobRemoved` [events](events.md#world) (whose payload is a Gob).

## Getting a Gob

| Expression | Returns |
|---|---|
| `hafen.gob(id)` | the Gob for that id (never nil) |
| `hafen.player():gob()` | your own Gob, or nil before you are in the world — see [player](player.md) |
| `hafen.world.gobs([filter])` | an array of Gobs |
| `hafen.world.nearest([filter])` | the nearest Gob, or nil |
| `hafen.world.within(radius [, filter])` | an array of Gobs |
| `GobAdded` / `GobRemoved` handlers | the Gob that spawned/despawned |
| `hafen.gob(m.id)` for a [party](party.md) member `m` | that member's Gob |

## Methods

| Method | Returns | Description |
|---|---|---|
| `gob:id()` | number | the gob id — answers even after the gob is gone |
| `gob:exists()` | bool | whether the gob is currently loaded |
| `gob:pos()` | `{x, y}` \| nil | world position |
| `gob:facing()` | number \| nil | facing angle, radians |
| `gob:name()` | string \| nil | resource/type identity (not a display name) |
| `gob:health()` | number \| nil | 0..1 remaining object integrity (1 = undamaged) |
| `gob:moving()` | bool \| nil | whether it is moving |
| `gob:speed()` | number \| nil | movement speed, or nil if not moving |
| `gob:speech()` | string \| nil | current floating speech text |
| `gob:icon()` | string \| nil | minimap icon/category name |
| `gob:overlays()` | string[] \| nil | active overlay resource names |
| `gob:isplayer()` | bool \| nil | whether it is a player body |
| `gob:distance([other])` | number \| nil | world distance to `other` (a Gob); defaults to the player |
| `gob:info()` | [`GobInfo`](types.md#gobinfo) \| nil | everything above as one plain snapshot table |

`:info()` is the **snapshot escape hatch** — use it for logging, serialising, or passing gob data
around as data ([`hafen.json`](json.md) can encode it; a Gob object itself cannot). For reading, prefer
the methods: they are always fresh, a snapshot is frozen at the moment you took it.

```lua
local me = hafen.player():gob()
if me then
  local p = me:pos()
  hafen.log(string.format("at %.0f, %.0f facing %.2f", p.x, p.y, me:facing()))
end

local tree = hafen.world.nearest("terobjs/tree")
if tree then
  hafen.log(string.format("%s is %.1f away", tree:name(), tree:distance()))
end
```

## Identity

Two Gobs for the same id are **the same object**, so equality and table keys just work:

```lua
hafen.gob(4711) == hafen.gob(4711)            --> true
hafen.player():gob() == hafen.gob(myId)       --> true

local seen = {}
for _, g in ipairs(hafen.world.gobs()) do
  if not seen[g] then seen[g] = true end      -- de-dupes across sweeps, no id juggling
end
```

Identity is per addon: your Gob objects are yours, never shared with another addon.

A Gob is read-only — `gob.foo = 1` is an error, and `gob.pos` is the method itself (call it with a
colon: `gob:pos()`). `tostring(gob)` gives `Gob(<id>)`.

## Passing a Gob to the rest of the API

Anything that acts on a gob takes the **Gob object**, not an id:

```lua
hafen.act.clickGob(tree, 3)                                   -- actions.md
hafen.render.sprite{ image = icon, follow = me, offset = { z = 18 } }   -- render.md
hafen.ui.gobOverlay(function(g) return g:isplayer() end, draw) -- ui.md
```

> `gob:name()` returns the **type** resource (e.g. `"gfx/borka/body"` for a player body), not a
> character's display name — those aren't available for arbitrary gobs. Use `gob:isplayer()` to test
> for a player body.
