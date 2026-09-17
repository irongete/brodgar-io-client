# Gob: Game Objects

A Gob is one thing in the world: a tree, a boulder, an animal, a player's body. It is addressed through the world of the character that sees it. [`session:world():gob()`](world.md#objects) is the collection. `:get(id)` names one by id. The rest of the collection finds the ones you have no id for.

```lua
local session = hafen.session():current()
local tree = session and session:world():gob():nearest("terobjs/tree")
if tree then
  hafen.log():write(string.format("%s is %s away", tree:name(), math.floor(tree:distance() * 10 + 0.5) / 10))
end
```

---

| Rule | Detail |
|---|---|
| A Gob wraps the gob id and nothing else | The id comes from the server and names one object however many of your characters see it. Every method re-resolves against the live object cache: a handle tracks the gob as it moves and answers `nil` once it is gone. Nothing is cached ([snapshots vs handles](conventions.md#snapshots-vs-handles)). |
| Where a gob is, is where it is drawn | The server names a place a few times a second. The client interpolates at frame rate. Every spatial read (`gob:position()`, `:distance()`, `:hitbox()`, the ranking `:nearest`/`:within` do, the coordinate [`session:world():click(gob)`](world.md#write-protected) sends) takes the drawn point and moves through a walk. On ground not streamed in it is the last place the server named. |
| `:get(id)` always returns a Gob | Even for an id no character has loaded or that never existed, so you can anchor before it streams in. `:exists()` is the liveness test. An id that is not a [whole, finite number](conventions.md#a-number-is-finite-and-an-index-is-whole) raises: `1.5` is not an id. |

## Which character does the reading

Each character holds its own copy of the object against its own map. A read is computed by one of them: the character through whose session you reached the Gob.

| Handed by | Reads in |
|---|---|
| `session:world():gob():get(id)`, `session:player():gob()` | `session`. |
| `member:gob()` | The character whose party you read. |
| A `GobAdded` payload | The character the object came into view for. |

| Rule | Detail |
|---|---|
| `:exists()` | "Can that character see it": `false` the moment the object leaves that character's view, even while another of yours still looks at it. Every other read answers `nil` in the same moment, as for a despawned object. |
| The answers do not otherwise depend on the reader | `:name()`, `:health()` and the rest read the server's object. `:position()` is a [Position](position.md) anchored on a server grid id, so two characters looking at one tree compute the same place from two frames. |
| Writes go the other way | [`gob:scale(k)`, `gob:visible(flag)`, `gob:tint(color)`](look.md) and [`gob:overlay()`](overlay.md)`:add(key)` change how the object looks, and one object looks one way: written to every character that sees it. |

### `gob:sessions()`

The [sessions](session.md) that can see this object now, as a collection in login order. Unprotected.

```lua
local tree = hafen.session():current():world():gob():nearest("terobjs/tree")
for _, viewer in ipairs(tree:sessions():list()) do
  hafen.log():write(viewer:user() .. " can see it")
end
```

| Rule | Detail |
|---|---|
| Live | The object caches are asked at the call. A character that logs out is not in the next answer. |
| Nobody sees it | The empty array, never `nil`: the answer inside a [`GobRemoved`](event/bus/world.md#world) handler. |

## Getting a Gob

| Expression | Returns | Permission |
|---|---|---|
| `session:world():gob():get(id)` | The Gob for that id. Never `nil`. | Unprotected |
| `session:player():gob()` | That character's own Gob. `nil` before its session is in the world ([`session:player`](player.md)). | Unprotected |
| `session:world():gob():list(filter)`, `:within(radius, filter)` | An array of Gobs. | Unprotected |
| `session:world():gob():nearest(filter)` | The nearest Gob to that character, or `nil`. | Unprotected |
| A `GobAdded` or `GobRemoved` handler | The Gob that spawned or despawned ([events](event/bus/world.md#world)). | Unprotected |
| `member:gob()` on a [party](party.md) member, `target:gob()` on the [combat](fight.md) target | That creature's Gob. | Unprotected |

## Read

Every method answers `nil` once the gob is gone, except `:id()`, `:exists()` and `:sessions()`, which always answer. None throws.

| Method | Returns | Permission | Description |
|---|---|---|---|
| `gob:id()` | `number` | Unprotected | The gob id. Answers after the gob is gone. |
| `gob:exists()` | `boolean` | Unprotected | Whether the reading character has it loaded. |
| `gob:sessions()` | collection | Unprotected | Which of your characters see it ([above](#gobsessions)). |
| `gob:position()` | [Position](position.md) `\| nil` | Unprotected | Where it is: a place to offset, measure and save. |
| `gob:facing()` | `number \| nil` | Unprotected | Facing angle, radians. |
| `gob:name()` | `string \| nil` | Unprotected | Resource identity (`"gfx/borka/body"` for any player body), not a display name. |
| `gob:health()` | `number \| nil` | Unprotected | Remaining object integrity, `0..1`. `1` is undamaged. |
| `gob:moving()` | `boolean \| nil` | Unprotected | Whether it is moving. |
| `gob:speed()` | `number \| nil` | Unprotected | Movement speed. `nil` when not moving. |
| `gob:speech()` | `string \| nil` | Unprotected | The floating speech text above it. |
| `gob:icon()` | `string \| nil` | Unprotected | Minimap icon category name. |
| `gob:player()` | `boolean \| nil` | Unprotected | Whether it is a player body. |
| `gob:kin()` | [`Kin`](kin.md) `\| nil` | Unprotected | The kin standing here, on the reading character's roster. |
| `gob:party()` | [`PartyMember`](party.md) `\| nil` | Unprotected | The party member standing here, in the reading character's party. |
| `gob:distance(other)` | `number \| nil` | Unprotected | World distance to another Gob. `other` defaults to the reading character. |
| `gob:sdt()` | `number[] \| nil` | Unprotected | State bytes the server sent with its resource ([State](#state)). |
| `gob:pose()` | `string[] \| nil` | Unprotected | Animation poses of its composed body ([poses](#the-poses-it-is-in)). |
| `gob:hitbox()` | `Position[][] \| nil` | Unprotected | The ground it stands on ([footprint](#the-ground-it-stands-on)). |
| `gob:info()` | [`GobInfo`](types/world.md#gobinfo) `\| nil` | Unprotected | A plain snapshot of the reads above, `hitbox` excepted. |

| Rule | Detail |
|---|---|
| Display names | Not available for arbitrary gobs. `gob:player()` tests for a player body. |
| `:info()` | The snapshot for logging, serialising and passing data around: [`hafen.json`](json.md) encodes a plain table, not a Gob. Read the methods otherwise. A snapshot is frozen at the call. |
| `:info()` carries no `hitbox` | A snapshot holds numbers and strings, and a footprint rebuilt per call would be paid by every sweep that wanted a name. Read `gob:hitbox()` itself. |
| `gob:distance(other)` | Measured inside one character's world: a pair is measured by a character that sees both. Two objects no single character sees together answer `nil`. |

## State

`gob:sdt()` answers the raw state bytes the server sent with the gob's resource, as a 1-based array of `0..255` numbers. They carry a crop's stage, a gate's leaf, a stockpile's count. What the bytes mean belongs to the resource's own published code. The client never decodes them.

```lua
local crop = hafen.session():current():world():gob():nearest("gfx/terobjs/plants/carrot")
local state_bytes = crop and crop:sdt()
if state_bytes then
  hafen.log():write(#state_bytes .. " state byte(s)")
end
```

| Answer | Meaning |
|---|---|
| `nil` | The gob is gone, or its body is composed rather than drawn from a resource (a player carries no state bytes). |
| The empty array | A resource-drawn gob the server sent no state for. [`gob:info().sdt`](types/world.md#gobinfo) carries whichever the live read does. |

Reading every frame to catch a change is a sweep. [`GobSdtChanged`](event/bus/world.md#the-state-changing) fires when the bytes change, once per object however many characters see it, for the first state as well as every one after.

## The poses it is in

`gob:pose()` answers the animation poses in force on a composed body (a player, an animal) as an array of pose resource names.

```lua
local my_gob = hafen.session():current():player():gob()
for _, pose in ipairs(my_gob:pose() or {}) do
  hafen.log():write(pose)                      -- "gfx/borka/idle", "gfx/borka/walking", ...
end
```

| Rule | Detail |
|---|---|
| The counterpart of [`gob:sdt()`](#state) | A composed body has poses and no state bytes. A resource-drawn object has bytes and no poses. A tree answers `nil` here and an array there, a player the other way round. `nil` also once the gob is gone. |
| The empty array | A composed body whose poses have not arrived yet, not the same answer as `nil`. |
| A one-shot wins while it plays | The server sends a standing set and transients (a swing, a bite, a bow) laid over it for a fixed time. The verb names what the eye sees: the transient while one runs, the standing set otherwise. |
| Names, not decoding | These are the pose resources the server named. What a name means is the resource's business. |
| An unresolved pose is left out | Not reported as a hole. The array is what the body draws, so it can be shorter than the set sent for the moment a resource takes to load. |
| No `GobPoseChanged` | A live read, like [`gob:name()`](#read): an animating body changes pose more often than a frame. Read it where you draw. |

## The ground it stands on

`gob:hitbox()` answers the shape the object occupies on the ground. It is an array of polygons, each an array of [Positions](position.md), rotated by the object's facing and placed where it stands. There is one ring per shape the resource carries, and every one of them. A tree's trunk, a fence's whole run, a gate's leaf and post both.

```lua
local wall = hafen.session():current():world():gob():nearest("gfx/terobjs/arch/fencing/wattle")
local footprint = wall and wall:hitbox()
if footprint then
  hafen.log():write(#footprint .. " polygon(s), " .. #footprint[1] .. " point(s) in the first")
end
```

| Rule | Detail |
|---|---|
| Every point is a place | `position:x()`, `position:distance()` and [`session:world():worldToScreen(position)`](world.md#the-screen-and-the-world) answer for it, and it sits on the object, not at the frame's origin, so a ring goes straight into [`hafen.virtual():patch()`](virtual/patches.md). |
| `nil` | Once the gob is gone. Before its resource has resolved. For an object none of the sources below has a shape for (a decoration, most flooring, anything nothing walks into). |
| Left out on purpose | A buildable resource's `build` box: the clearance a placement ghost checks, not the footprint of what ends up there. |
| Not what `gob:scale(k)` draws | Scale changes how big the object looks. The footprint is the game's own ([Look](look.md#size-unprotected)). |

> **Three facts share this answer, and the verb does not say which you got.** Usually the rings are the resource's collision shapes. A resource may carry no collision shape and still carry a click-box: a felled log (`gfx/terobjs/log`) blocks nothing and answers an 18×4 rectangle. An object may carry no shape of its own and be sent one per object by the server ([a building site](#a-shape-the-server-sent)). What the three share: world units, turned by the facing, placed where it stands. Never read a shape as proof that the object blocks movement.

```lua
local log = hafen.session():current():world():gob():nearest("gfx/terobjs/log")
local click_box = log and log:hitbox()   -- not nil: its click-box, not a collision shape it does not have
```

### A shape the server sent

A building site (`gfx/terobjs/consobj`, the same resource for every building) has no footprint of its own. What goes up on the spot is sent with the object in its [state bytes](#state). The stakes you see are the resource's own code drawing that shape. `gob:hitbox()` reads it, so a site answers the outline of the building it will become.

| Rule | Detail |
|---|---|
| The one decoded `sdt` | The bytes are parsed by the library the site's own code parses them with, held at the version the server serves. |
| Declared only | A resource is read this way only when it declares that library as its own code's. |
| Dropped when unclean | A shape that does not come out of the bytes cleanly is dropped rather than drawn. |
| The ghost has the same verb | [`placing:hitbox()`](placing.md#the-footprint) answers in this shape and these units for the thing on your cursor. |

## How it is drawn

| Write | Page | Detail |
|---|---|---|
| `gob:scale(k)`, `gob:visible(flag)`, `gob:tint(color)` | [Look](look.md) | How big, whether drawn, what colour is laid over it. |
| `gob:materials()` | [Materials](materials.md) | The variable-material slots the server dressed it in, each readable and dressable in another resource by name. |
| `gob:overlay()` | [Overlay](overlay.md) | What stands at it. |

All are unprotected, written on the object rather than a character, and change nothing on this page. A resized, hidden, tinted or re-dressed object answers every read above. `gob:visible(false)` withholds the model alone: a hidden object goes on drawing your overlays and the game's own (name label, health bar) over empty ground. The click goes through to that ground, the label does not.

## Clicking one

Clicking is something a character does, so the verb is [`session:world():click(gob, button, mods)`](world.md#sessionworldclickgob-button-mods) on the world of the character making the gesture, the shape [`session:player():move(position)`](player.md#write-protected) has.

## Kin

`gob:kin()` answers the [`Kin`](kin.md) this gob belongs to on the reading character's roster. `kin:gob()` goes back. A buddy id counts inside one roster, so ask a character you name with [`session:kin()`](kin.md) when you mean a particular one.

```lua
local player_gob = hafen.session():current():world():gob():nearest(function(gob) return gob:player() end)
local kin = player_gob and player_gob:kin()
hafen.log():write(kin and ("that is " .. kin:name()) or "nobody you know")
```

| Rule | Detail |
|---|---|
| Server-side | The game marks a kinned player's gob for that character: a single attribute read, never a guess from a name. A kin's hearth fire carries the mark too. |
| `nil` is ambiguous | Not on that character's roster, or not a player at all. |

## Identity

Two Gobs for the same id read through the same character are the same object, however reached: equality and table keys work directly.

```lua
local main_session, alt_session = hafen.session():get("main"), hafen.session():get("alt")
assert(main_session:world():gob():get(4711) == main_session:world():gob():get(4711))
assert(main_session:player():gob() == main_session:world():gob():get(main_session:player():gob():id()))
assert(main_session:world():gob():get(4711) ~= alt_session:world():gob():get(4711))   -- two characters, two answers

local seen = {}
for _, gob in ipairs(main_session:world():gob():list()) do
  if not seen[gob] then seen[gob] = true end      -- de-duplicates across sweeps, no id juggling
end
```

| Rule | Detail |
|---|---|
| One handle per id per login | A set keyed by Gobs is a set of objects. Across two characters the handles are two answers about two frames. `gob:id()` compares them, and is the form for a file or a message. |
| Why not `__eq` | Lua table keys compare objects directly and ignore `__eq`. A cleverer `==` would count one gob twice as a key while claiming to be one. |
| Per addon | Your Gob objects are yours, never shared with another addon. |
| Immutable | `gob.foo = 1` raises. `gob.position` is the method, call it `gob:position()`. `tostring(gob)` is `Gob(<id>)`. |

## Passing a Gob to the rest of the API

Anything acting on a gob takes the Gob object, not an id: `my_gob:overlay():add("tag"):text("here")`, `session:player():hand():use(tree)`, `hafen.virtual():sprite():add(icon, my_gob):offset(0, 0, 18)`.

---

## See Also

- [`session:world`](world.md) — finding the gobs to read, and clicking one.
- [Look](look.md) — size, visibility and tint.
- [Materials](materials.md) — the material slots it is drawn in.
- [Overlay](overlay.md) — everything drawn at a gob, and the labels and painters you add.
- [`session:kin`](kin.md) — the roster side of `gob:kin()`.
- [`session:player`](player.md#write-protected) — walking to a gob, and the cursor you aim at one.
- [`GobInfo`](types/world.md#gobinfo) — the shape `:info()` returns.
- [Events](event/bus/world.md#world) — reacting to gobs appearing and leaving.
