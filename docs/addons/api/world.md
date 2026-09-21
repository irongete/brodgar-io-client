# session:world: One Character's World

The game objects, terrain and coordinate spaces one character has loaded right now, reached through the [session](session.md) of the character you mean. A single object is a [Gob](gob.md).

```lua
local session = hafen.session():current()                  -- the character on screen
local prey = session and session:world():gob():nearest(function(gob)
  local name = gob:name()
  return name and name:find("rabbit")
end)
if prey then
  local position = prey:position()
  hafen.log():write(string.format("nearest rabbit at grid %s", position:info().gridId))
end
```

---

> **Live, not recorded.** Every read on this page is the world as it streams around that character: `nil` off-stream, gone at logout. The explored map (segments, grids, markers, minimap icons) is on disk and is [`hafen.map`](map/README.md).

## Whose world it is

`session:world()` is the world that character stands in. Two characters disagree about all of it, so every read names the character it is about.

```lua
hafen.session():current():world():gob():count("terobjs/tree")   -- trees the drawn character can see
hafen.session():get("alt"):world():gob():count("terobjs/tree")  -- trees that character can see
```

| Rule | Detail |
|---|---|
| One object | `session:world()` and `session:world():gob()` are the same object every call, minted once per session: a draw callback reading them at 60 fps allocates nothing. |
| A session the client no longer holds | Answers `nil`-shaped instead of raising, the same shape as before entering the world. [`session:exists()`](session.md#read) tells the two apart. |
| What belongs to the screen | [`worldToScreen` and `screenToWorld`](#the-screen-and-the-world) name a pixel. [`click`](#write-protected), `place` and `select` are pointer gestures. Asked of a session not on screen, the gestures and `screenToWorld` raise naming `hafen.session():current()`. `worldToScreen` answers `nil`, since a read has a value shape. Walking is the one write a character you are not looking at takes: [`move`](player.md#write-protected). |

## Which world it is

The server names the world with an opaque string (the *genus*): different per world, constant within one. It is the world half of the character key [`session:store()`](store/vars.md) files rows under. It is the key for anything that must not leak across worlds: the same character name on another world is another character.

```lua
local world_id = hafen.session():current():world():id()   -- e.g. "fd63ddee958da329"
```

| Method | Returns | Permission | Description |
|---|---|---|---|
| `session:world():id()` | `string \| nil` | Unprotected | The id of the world that character is in. `nil` until its HUD is up (the moment [`session:character()`](session.md#read) answers) and `nil` when the server named none. |

## Objects

`session:world():gob()` is the read-only collection of the game objects that character has loaded, and the only way to address one. Every verb hands out live [Gob](gob.md) objects, not snapshots.

| Method | Returns | Permission | Description |
|---|---|---|---|
| `session:world():gob():get(id)` | [Gob](gob.md) | Unprotected | The object with that id. Never `nil`. |
| `session:world():gob():list(filter)` | [Gob](gob.md)`[]` | Unprotected | Every matching object. |
| `session:world():gob():count(filter)` | `number` | Unprotected | How many match. |
| `session:world():gob():find(filter)` | [Gob](gob.md) `\| nil` | Unprotected | The first match, in load order. |
| `session:world():gob():nearest(filter)` | [Gob](gob.md) `\| nil` | Unprotected | The closest match to that character. |
| `session:world():gob():within(radius, filter)` | [Gob](gob.md)`[]` | Unprotected | Matches within `radius` world units of that character. |

```lua
for _, gob in ipairs(session:world():gob():within(50, "gfx/borka/body")) do
  hafen.log():write("player within 50 units: " .. tostring(gob:id()))   -- gob is a live Gob
end
```

| Rule | Detail |
|---|---|
| `filter` | The canonical [filter](conventions.md#the-filter-argument): `nil`, a name substring, or a predicate receiving a Gob. `nearest` and `within` measure from that character and skip its own gob. None of the verbs throws. |
| `:get(id)` never answers `nil` | Not for an id that character has not loaded, not for one that never existed. Anchor to a gob before it streams in. `gob:exists()` is the liveness test. |
| A Gob is a table key | `seen[gob] = true` de-duplicates across sweeps without ids. Two of your characters looking at one tree find the same value ([identity](gob.md#identity)). |
| Reacting instead of polling | The `GobAdded`/`GobRemoved` [events](event/bus/world.md#world), not a scan every frame. |

## The Position type

A place is a [Position](position.md). `session:world():position(x, y)` builds one, `session:world():tile(position)` reads under one, `gob:position()` is where most come from.

## Terrain and coordinates

Terrain reads take a Position and answer `nil` when the map at that spot has not streamed in for that character. Lattice conversions are pure arithmetic and always answer. Nothing here is protected. Nothing throws on a point off the map.

```lua
local position = session:player():gob():position()
local tile = session:world():tile(position)
if tile then hafen.log():write("standing on " .. (tile.name or tile.id)) end
```

| Method | Returns | Permission | Description |
|---|---|---|---|
| `session:world():position(x, y)` | Position | Unprotected | A place from that session's world components. |
| `session:world():position(saved)` | Position | Unprotected | A place rebuilt from a `{gridId, x, y}` table. |
| `session:world():tile(position)` | [`Tile`](types/world.md#tile) `\| nil` | Unprotected | Tileset id and resource name at a Position. |
| `session:world():height(position)` | `number \| nil` | Unprotected | Terrain height there. |
| `session:world():components(position)` | `{x, y} \| nil` | Unprotected | Where `position` is, in this character's frame. |
| `session:world():tileCoord(position)` | `{x, y} \| nil` | Unprotected | The tile `position` sits in, on this character's lattice. |
| `session:world():distance(position [, other])` | `number \| nil` | Unprotected | How far `position` is from `other`, or from this character. |
| `session:world():grid():at(position)` | [`Grid`](map/grids.md#the-grid-object) `\| nil` | Unprotected | The map grid covering a Position. |
| `session:world():grid():get(id)` | [`Grid`](map/grids.md#the-grid-object) `\| nil` | Unprotected | That grid by the server's id, when streamed in. |
| `session:world():grid():list()` | `Grid[]` | Unprotected | Every grid that character has streamed in now. |
| `session:world():tileToWorld(tile_x, tile_y)` | `{x, y}` | Unprotected | Tile coordinate to world, at its upper-left corner. |
| `session:world():tileToGrid(tile_x, tile_y)` | `{x, y}` | Unprotected | Tile coordinate to grid coordinate. |
| `session:world():worldToScreen(position)` | `{x, y} \| nil` | Unprotected | Project a Position to a root [design pixel](ui/pixels.md). Drawn session only. |
| `session:world():screenToWorld(point, fn)` | nothing. Calls `fn` | Unprotected | Raycast the ground under a root design pixel. Asynchronous. Drawn session only. |
| `session:world():focus(position)` | the world | Unprotected | Aim the view at a place. Drawn session only. `rts` camera only. |
| `session:world():snapPlace(position, fine)` | Position | Unprotected | Snap a Position to the client's placement grid. |
| `session:world():snapAngle(angle, fine)` | `number` | Unprotected | Snap a facing in radians to the client's placement-angle grid. |
| `session:world():placing()` | [Placing](placing.md) `\| nil` | Unprotected | The ghost on that character's cursor. `nil` when it is placing nothing. |

| Rule | Detail |
|---|---|
| Remembered ground | The client also draws ground it remembers from disk, greyed, wherever the camera looks past the stream. `:tile` and `:height` never read that record: over remembered ground they answer `nil`, as over the void beside it. [`hafen.map`](map/README.md) reads the record, by grid. |
| `:height` is the streamed height | What the server sent for that ground, whatever the Performance panel's [flat terrain](client/README.md#performance) draws. Nothing in the API reads the drawn height. |
| Placement settings | Sub-tile divisions and rotation steps are read and written through [`hafen.client():options():interface()`](client/README.md#interface): `posGran()` and `angGran()`. |
| Addressed reads | `components`, `tileCoord` and `distance` answer for the character `session` names. A [Position](position.md) carries no session, so `position:x()`, `position:tileCoord()` and a bare `position:distance()` resolve in the drawn character's frame. With one login the two agree. With two they differ and nothing raises. |
| A place that character cannot locate | Recorded in another part of the world, or off its streamed ground while the [base](position.md) it resolves through is unproved: `nil`, as `position:x()` gives. |

```lua
local alt_session = hafen.session():get("alt")
local tree = alt_session:world():gob():nearest("terobjs/tree")
local distance_from_alt = alt_session:world():distance(tree:position())   -- how far the alt is from it
local distance_from_drawn = tree:position():distance()                    -- how far the character on screen is
```

## The screen and the world

`worldToScreen` and `screenToWorld` are the two directions of one conversion over the same shape, so a round trip composes. `snapPlace` and `snapAngle` are the client's own placement snappers. Together they are the primitives of a drag-on-the-ground tool.

```lua
local world = hafen.session():current():world()
local mouse = hafen.ui():mouse()
world:screenToWorld({x = mouse:x(), y = mouse:y()}, function(position)
  if position then hafen.log():write(("ground under cursor: %d, %d"):format(position:x(), position:y())) end
  -- position is nil when the pixel hit no terrain (sky, or off the map)
end)
local anchor_position = hafen.session():current():player():gob():position()
world:screenToWorld(world:worldToScreen(anchor_position), function(round_trip) end)   -- the round trip closes
```

| Verb | Rule | Detail |
|---|---|---|
| `worldToScreen(position)` | Answer | A root screen point in [design pixels](ui/pixels.md). That is the space [the mouse](ui/mouse.md), `hafen.ui():hit(x, y)`, [`widget:rootPos()`](ui/widget.md#read-methods) and a [HUD overlay](ui/overlay.md) painter share. It feeds a [`g:` verb](ui/drawing.md) or a hit test at any interface scale. Not a Position: a pixel is not a place. |
| `worldToScreen(position)` | Projects at the ground under `position` | A point up a hillside answers where it is, and a raycast back down returns to it. |
| `worldToScreen(position)` | `nil` | Before the map view exists. For a point the view cannot project. For ground that character has not streamed in (no height to project at). For a session not on screen. For a place that character cannot locate (an alt in another part of the world, a cave or a house), the same `nil` [`components`](#terrain-and-coordinates) gives. |
| `worldToScreen(position)` | Behind the camera | The projective divide answers a plausible pixel mirrored through the middle of the view for a place behind it, so that case is `nil`. Pulled all the way in on the `bad` camera, the camera sits beside the character looking along the ground. Everything behind the character is behind the camera. A painter projecting several corners of one shape drops the whole shape when one corner answers `nil`. |
| `screenToWorld(point, fn)` | Asynchronous | Reads the true terrain point from the GPU, the pass the client places a building with. The answer arrives a frame later through `fn` as a Position, `nil` when the pixel hit no terrain. Leaving `fn` out raises. |
| `screenToWorld(point, fn)` | `point` | `{x = , y = }` in root design pixels: the shape `worldToScreen` hands back, the space [`mouse:x()`/`mouse:y()`](ui/mouse.md#read-methods) reports and a grab's `event:x()`/`event:y()` carries. |
| `screenToWorld(point, fn)` | During a drag | Feed it the coordinates from [the mouse's grab](ui/mouse.md#the-grab) and coalesce. Issue the next raycast only after the previous `fn` fired, so at most one is in flight per frame. |
| Both | Drawn session only | There is one screen however many characters are logged in. `screenToWorld` raises for any other session, naming `hafen.session():current()`. `worldToScreen` answers `nil`. Before that session is in the world `fn` is never called. |
| `snapPlace(position, fine)` | The client's snapper | Without `fine`, the tile centre. With `fine = true`, the sub-tile grid (`posGran()` divisions, or free when that is `0`). A ghost dropped through it lands where a real building would. |
| `snapAngle(angle, fine)` | The rotation counterpart | Without `fine`, 45° steps. With `fine = true`, the placement-angle grid (`angGran()` steps). The result is normalised to `(-π, π]`. Both snappers are arithmetic over the client's settings and answer for any session. |

```lua
local ghost = hafen.virtual():ghost():add("gfx/terobjs/arch/logcabin", hafen.session():current():player():gob():position())
hafen.ui():mouse():grab():on("Move", function(event)
  local world = hafen.session():current():world()
  world:screenToWorld({x = event:x(), y = event:y()}, function(position)   -- a frame later
    local snapped = world:snapPlace(position, event:shift())                -- SHIFT picks the fine grid
    ghost:position(snapped)                                                 -- a Position in, a Position out
  end)
end)
```

### Aiming the view

`session:world():focus(position)` centres the view on a place: the one verb of this family that writes, and it moves the view, not the character.

```lua
local session = hafen.session():current()
session:world():focus(session:player():gob():position())   -- back onto the character
```

| Rule | Detail |
|---|---|
| No permission | Nothing reaches the server. Only what you are shown changes. |
| `rts` camera only | Every other camera is bolted to the character and has nothing to aim, so `focus` raises under one instead of doing nothing. [`hafen.client():options():camera():mode("rts")`](client/README.md#camera) installs it. |
| Drawn session only | Asked of another session it raises naming `hafen.session():current()`. |

## Write (protected)

The verbs below change the world, each sending exactly the message the matching mouse gesture sends. The client sends only shapes a player could compose. Each hands the section back, so writes chain.

| Method | Returns | Permission | Description |
|---|---|---|---|
| `session:world():click(gob, button, mods)` | the world | `gob.click` | Click a game object. |
| `session:world():place(position, angle, button, mods)` | the world | `world.place` | Place the object on the pointer. |
| `session:world():select(position_a, position_b, mods)` | the world | `world.select` | Area-select a tile rectangle. |

| Rule | Detail |
|---|---|
| Permission | Each key is [declared](../guides/permissions.md) in your manifest. The group `world.*` covers `world.place` and `world.select`. An undeclared key raises naming it. |
| Drawn character only | Clicking and placing need the pointer. Selecting is a drag with it. Each raises for a session not on screen, naming `hafen.session():current()`. |
| `button`, `mods` | Optional, and checked: a value that is not a number raises naming the verb and the parameter. A numeric string is [still a string](conventions.md#a-number-is-not-a-string-and-a-numeric-string-is-not-a-number). An omitted argument takes its default. An explicit `nil` in a passed slot [raises](conventions.md#nil-is-an-error-unless-it-means-something). |
| Every refusal | Fires before anything goes out. |

### `session:world():click(gob, button, mods)`

The click a left- or right-click on the object sends. The session acts and the object is the target. A click is something a character does. A [Gob](gob.md) names the object, not one character's view of it.

```lua
local world = hafen.session():current():world()
local tree = world:gob():nearest("terobjs/tree")
if tree then world:click(tree, 3) end        -- right-click: the radial menu
```

| Parameter | Default | Detail |
|---|---|---|
| `gob` | required | A Gob. The whole object is the target. A composite body part or a sub-mesh is not addressable. |
| `button` | `1` | `1` left (select, interact). `3` right, the one that opens the [radial menu](flowermenu.md). |
| `mods` | `0` | Shift = 1, Ctrl = 2, Alt = 4, added together. |

| Rule | Detail |
|---|---|
| The key | `gob.click`: a key names the action, and the action is clicking an object. |
| An object this character cannot see raises | It left view, despawned, or only another of your characters has it loaded ([`gob:sessions()`](gob.md#gobsessions) says which). So does one with no position yet, and a call before that session is in the world. Nothing goes out in any of those cases. |

### `session:world():place(position, angle, button, mods)`

Place the object on the pointer at a [Position](#the-position-type), rotated by `angle` radians.

```lua
local world = hafen.session():current():world()
local position = world:snapPlace(hafen.session():current():player():gob():position())
world:place(position, world:snapAngle(0))
```

| Parameter | Default | Detail |
|---|---|---|
| `position` | required | A Position. Anything else raises. Prepare it with [`snapPlace`](#the-screen-and-the-world) to land where a real building would. |
| `angle` | required | Radians. Missing or not a number raises. Prepare it with `snapAngle`. |
| `button` | `1` | Confirm. |
| `mods` | `0` | Shift = 1, Ctrl = 2, Alt = 4, added together. |

> **With nothing on the pointer the server ignores the message and nothing comes back.** Placement is started by the server, so this verb cannot report what it did. Ask beforehand with [`session:world():placing()`](placing.md): `nil` is nothing on the cursor. Otherwise it says what is on it, where it sits and [what ground it will take](placing.md#the-footprint).

### `session:world():select(position_a, position_b, mods)`

Area-select the tile rectangle spanned by two Positions, the message the tile-area tools send.

| Rule | Detail |
|---|---|
| Corners | Each is floored to the tile it falls in, the conversion [`position:tileCoord()`](#the-position-type) exposes, so the two Positions name whole tiles. |
| `mods` | Optional, `0` by default. |
| Refusals | `place` and `select` raise before that session is in the world, and for a Position it cannot locate. |

---

## See Also

- [Gob](gob.md) — what the object readers hand back, and which character does the reading.
- [Placing](placing.md) — the ghost on the cursor: what `place` is about to commit.
- [Position](position.md) — the place type every spatial verb here takes.
- [`hafen.session`](session.md) — the address every verb here hangs off.
- [`hafen.map`](map/README.md) — the recorded map: segments, grids, markers, icon categories.
- [The `filter` argument](conventions.md#the-filter-argument) — the forms the object readers accept.
- [`hafen.store`](store/README.md) — where a Position is saved.
- [Coordinates](shapes.md#coordinates) — the spaces, side by side.
