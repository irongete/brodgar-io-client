# hafen.virtual: Everything of Yours Standing in the World

`hafen.virtual()` is the section for client-only things of yours in the 3D world: a translucent copy of one of the game's props, your own PNG, your own glTF model, a window drawn out there instead of on the screen, and a shape lying flat on the terrain. Nothing here reaches the server, so nothing here is protected except [`click`](#clicking-what-stands-in-the-world-protected).

```lua
local session = hafen.session():current()                        -- the character on screen
local position = session:player():gob():position()
hafen.virtual():ghost():add("gfx/terobjs/arch/logcabin", position)     -- the game's own prop, at a point
hafen.virtual():sprite():add(hafen.asset():get("icon.png"), position)  -- your own image
hafen.virtual():object():add(chair_mesh, position)                     -- your own glTF model
hafen.virtual():widget():add(info_window, position)                    -- a window, drawn in the world
hafen.virtual():patch():add(ring, position)                            -- a shape lying on the ground

local rabbit = session:world():gob():nearest("rabbit")
hafen.virtual():sprite():add(icon, rabbit)                             -- following a game object
```

---

> **Unprotected, with one exception.** These are visualisations with no server id: the server never learns one exists and none grants a gameplay advantage, the footing of [a HUD overlay](../ui/overlay.md). Committing a real build is the protected [`session:world():place`](../world.md#write-protected). The exception is [`hafen.virtual():click`](#clicking-what-stands-in-the-world-protected): a [standing widget](widgets.md) can hold one of the client's own controls, and clicking it does what the mouse does.

Everything here is bridge-owned: every entity your addon stands is torn down on reload, disable and relogin, leaking neither a scene slot nor a GPU texture.

## The collections (unprotected)

| Collection | Holds | Page |
|---|---|---|
| `hafen.virtual():ghost()` | The game's `.res` props this addon has stood. | [Ghosts](ghosts.md) |
| `hafen.virtual():sprite()` | Its own images. | [Sprites](sprites.md) |
| `hafen.virtual():object()` | Its own glTF models. | [Models](models.md) |
| `hafen.virtual():widget()` | The UI it has standing out there. | [Widgets](widgets.md) |
| `hafen.virtual():patch()` | Its own shapes lying on the terrain. | [Patches](patches.md), [pieces](pieces.md) |
| `hafen.virtual():entity()` | Every kind at once, in creation order; no `:add`. | [Below](#the-whole-section-at-once) |

Each is a [collection](../conventions.md#collections-the-noun-is-the-kind-the-verb-is-how-many) and the same object every call.

| Method | Returns | Permission | Description |
|---|---|---|---|
| `collection:add(what, anchor)` | the entity | Unprotected | Stand one and hand it back, ready to configure. |
| `collection:list(filter)` | entity`[]` | Unprotected | This addon's live ones of that kind. |
| `collection:count(filter)` | `number` | Unprotected | How many, without building the array. |
| `collection:find(filter)` | entity `\| nil` | Unprotected | The first that matches. |
| `collection:remove(entity)` | the collection | Unprotected | End one now; also automatic on reload, disable and relogin. |

| Rule | Detail |
|---|---|
| `filter` | The canonical [filter](../conventions.md#the-filter-argument): `nil` is all; a string is a substring match on what the thing draws (a ghost's resource name, a sprite's or object's addon-relative path, a standing widget's caption); a function is called with the entity. A [patch](patches.md) is a picture of nothing: `hafen.virtual():patch()` refuses a string filter naming the two forms that work, and no patch matches one passed to `hafen.virtual():entity()`. |
| Assets are handles | An [image](sprites.md) or a [model](models.md) is passed as a [`hafen.asset`](../asset/README.md) handle, never a path; a path raises naming `hafen.asset`. Assets are [interned](../asset/README.md#interning), so loading the same path again is free. |

## The anchor is an argument

`:add(what, anchor)` takes two required things: `what` to draw, and `anchor`, one of two values.

| Anchor | Effect |
|---|---|
| A [Position](../position.md) | It stands there and stays there. |
| A [Gob](../gob.md) | It follows that game object every frame. |

```lua
hafen.virtual():ghost():add("gfx/terobjs/arch/logcabin", position)     -- a plan on the ground
hafen.virtual():sprite():add(icon, prey):offset(0, 0, 14)              -- a marker floating over a creature
hafen.virtual():widget():add(info_window, cupboard):facing("camera")   -- a panel standing on a game object
```

| Rule | Detail |
|---|---|
| Anything else raises | Naming both forms. The place is an argument, not a setter with a default: the scene resolves the tile under a thing as it enters, so one with no place cannot be built. |
| A Position must be keepable | What one standing at a point holds is the [durable](../position.md) form (grid and offset within it), not a world coordinate. Ground nobody has walked has no durable form: `:add(what, position)` and `entity:position(position)` refuse it; `position:durable()` asks ahead. |
| An anchored one dies with its gob | A felled tree takes the thing following it; a gob that returns is bare, and re-anchoring is your call from [`GobAdded`](../event/bus/world.md#world). |
| `:add` raises when you are not in the world | Place from `SessionEnteredWorld` onward. Ground not drawn right now, and ground the character on screen cannot locate, both [wait](#the-ground-under-one-that-stands-still) rather than raising. |

## One vocabulary, every kind

Every entity answers the same read/write pairs (bare reads, with a value writes and hands the entity back), plus the verbs only its kind has: [`ghost:res`](ghosts.md#the-ghost), [`sprite:image`](sprites.md#the-sprite) and [`sprite:facing`](sprites.md#facing), [`object:mesh`](models.md#the-object), [`panel:widget`, `panel:facing`, `panel:screen`](widgets.md#the-standing-widget), [`patch:border`](patches.md#the-border) and [`patch:piece`](pieces.md).

| Method | Returns | Permission | Description |
|---|---|---|---|
| `entity:position()` | [Position](../position.md) | Unprotected | Where it is: the gob's live point for one that follows, [the place held](#the-ground-under-one-that-stands-still) for one that stands still. |
| `entity:position(position, angle)` | the entity | Unprotected | Stand it at `position`, optionally setting facing. For one that stands still; on one that follows it raises naming `:offset`. |
| `entity:offset()` / `entity:offset(x, y, z)` | the entity | Unprotected | Where it sits relative to the gob it follows, world units, `z` up. For one that follows; on one that stands still it raises naming `:position`. A [patch](patches.md) takes `offset(x, y)`, no `z`. |
| `entity:rotate()` / `entity:rotate(angle)` | the entity | Unprotected | Its own facing in radians, keeping position. |
| `entity:scale()` / `entity:scale(k)` | the entity | Unprotected | Uniform scale, `1` original; held to `0.01..100`, so `:scale(0)` gives the smallest size (where [`gob:scale(k)`](../look.md#size-unprotected) refuses a `0`). |
| `entity:alpha()` / `entity:alpha(value)` | the entity | Unprotected | Opacity `0..1`, `1` opaque; clamped. |
| `entity:tint()` / `entity:tint(color)` | the entity | Unprotected | A [colour](../shapes.md#colours) laid over it, its `a` the blend strength; a [patch](patches.md#the-patch)'s own fill opacity. `nil` clears it and is legal. |
| `entity:visible()` / `entity:visible(flag)` | the entity | Unprotected | Whether you have it showing; `false` takes it out and keeps the entity. |
| `entity:drawn()` | `boolean` | Unprotected | Whether it is in the 3D scene right now ([below](#the-ground-under-one-that-stands-still)). |
| `entity:clickable()` / `entity:clickable(flag)` | the entity | Unprotected | The pick surface: opt-in, client-side only. |
| `entity:onClick()` / `entity:onClick(fn)` | the entity | Unprotected | `fn(entity, button, x, y)` fired on click. |
| `entity:exists()` | `boolean` | Unprotected | Still in the world; `false` once the collection removed it. |
| `entity:info()` | `table` | Unprotected | The whole state as a plain table ([the snapshot](#the-snapshot)). |

| Rule | Detail |
|---|---|
| A standing widget is not a picture | The picture kinds have `:onClick(fn)`; a panel fires its own `MouseDown` at the pixel the pointer landed on, so `:onClick` on one raises naming that subscription, and `:clickable(flag)` there means whether the panel takes the pointer at all. |
| A refusal names the kind | A verb none has answers `ghost`, `sprite`, `object`, `panel` or `patch` and lists what that kind answers. `tostring(entity)` is the kind and what it draws (`Ghost(gfx/terobjs/arch/logcabin)`; the bare `Patch`). A standing widget is `panel`, since the [widget](../ui/widget.md) inside it answers to `widget` with a different sentence (a Position out here, pixels within a parent in there). |

### The snapshot

`entity:info()` is the whole state as a plain table, every key spelled as the verb that reads it.

```lua
local sprite = hafen.virtual():sprite():list()[1]
local snapshot = sprite:info()
hafen.log():write(snapshot.kind .. " alpha=" .. snapshot.alpha .. " drawn=" .. tostring(snapshot.drawn))
for key, value in pairs(sprite:info()) do            -- the whole dump, in one call
  hafen.log():write(key .. " = " .. tostring(value))
end
```

| Key | Present |
|---|---|
| `kind`, `rotate`, `scale`, `alpha`, `visible`, `clickable`, `exists`, `drawn`, `failed` | Always. |
| `position` | Always: the `{gridId, x, y}` table [`position:info()`](../position.md) answers. |
| `tint` | When one is laid over it, as a [colour](../shapes.md#colours). |
| `anchor`, `offset` | For one that follows a gob; absent for one that stands still. |
| `res` / `mesh` / `image`, `facing` / `facing` / `pieces` | A ghost's / an object's / a sprite's / a panel's / a patch's own. |

A snapshot: nothing in it updates and nothing in it is a live object. `panel:screen(x, y)` has no entry, since it projects a point you pass in.

## The ground under one that stands still

Something standing at a point is in the scene only while the terrain under it is drawn: walk far enough and it goes with that ground, and returns whole when the ground does. It keeps its handle, place, look and `:exists()` meanwhile.

| Rule | Detail |
|---|---|
| Remembered ground does not hold one up | The greyed ground the client draws from disk is a picture with no height: one standing there is not drawn, and [`session:world`](../world.md) answers `nil` for its tile. `:drawn()` answers whether the ground is locatable. |
| The numbers move; it does not | A cave or a house re-bases the map, so one world coordinate names different ground before and after. The entity holds the durable place: `entity:position():info()` reads the same grid and offset across the trip, while `:x()` and `:y()` are the drawn character's answer and may differ. |
| A place the screen's character cannot locate is legal | A Position out of [`hafen.store`](../store/README.md) or recorded in another part of the world: nothing raises, `:position():info()` answers the grid given, `:x()` answers `nil`, `:drawn()` is `false`, and it stands itself up when that ground resolves. No timeout: a place you never walk to waits forever, the state a cave puts every overworld thing of yours into; [`hafen.client():profiling():entities()`](../client/profiling/counters.md#entities) counts them. `entity:visible()` still reads what you last told it. |
| One that follows a gob | Its place is the gob's: it comes and goes as that object does, and ends with it. |
| `entity:drawn()` | `false` while you hid it, while the section is off, while its visual is streaming in, and while a free one's ground is not drawn. |
| `entity:info().failed` | The fifth case the others cannot be told from: the client asked for the visual and gave up (a mistyped resource name, art that will not load). `drawn()` false with `failed` false is *not yet*; with `failed` true it is *never*. |

```lua
local home = hafen.store():var("spot").home            -- a place saved in an earlier session
local plan = hafen.virtual():ghost():add("gfx/terobjs/arch/logcabin", home)
assert(plan:exists())                    -- it is yours and it is placed
assert(plan:visible())                   -- you never hid it
local held_place = plan:position():info() -- the grid and offset it was given: the place it holds
assert(plan:position():x() == nil)       -- the character on screen has not located that grid
assert(plan:drawn() == false)            -- so there is no ground out there to stand it on, yet
```

## Several characters, one world

A thing you stand belongs to the world, not to the character who stood it: the place is the server's own, so tabbing to a character on that ground finds it there. You read and order across every character; what is drawn is the one on screen.

| Ask | Answers |
|---|---|
| `:list()`, `:count()`, `:find()` | Everything standing, whichever character was looking when you stood it. |
| `entity:exists()`, `entity:visible()`, `entity:position():info()` | The same from every character: facts about the thing. |
| `entity:position():x()`, `:y()` | The drawn character's coordinate for that place, or `nil`. |
| `entity:drawn()` | Whether it is in the scene the character on screen looks at. |

Tab to a character somewhere else and nothing of yours is drawn: that character cannot locate the ground, the [wait](#the-ground-under-one-that-stands-still) above. One that follows a game object is asked the same about that object: two characters looking at it see the same thing standing on it, and it ends when no character has it in view.

> **A standing [widget](widgets.md) is the one kind that does not travel.** It draws a widget in the tree of the character it was stood from, so it is on screen while that character is and not while another is.

## The whole section at once

| Method | Returns | Permission | Description |
|---|---|---|---|
| `hafen.virtual():entity()` | collection | Unprotected | Everything this addon has standing, across the kinds, in creation order; the same canonical filter, the same entities. |
| `hafen.virtual():visible()` | `boolean` | Unprotected | Whether the section is on screen. |
| `hafen.virtual():visible(flag)` | the section | Unprotected | Take the whole section off screen, or put it back. |

```lua
for _, entity in ipairs(hafen.virtual():entity():list()) do
  hafen.log():write(tostring(entity:position()))
end
hafen.log():write(hafen.virtual():entity():count() .. " standing")
hafen.virtual():visible(false)          -- the lot, off screen
hafen.virtual():visible(true)           -- back exactly as it was
```

| Rule | Detail |
|---|---|
| `:visible(false)` destroys nothing | Every entity keeps its gob, transform and handle; `:exists()` stays true and every verb answers. Only the scene slot goes. |
| Showing again restores what was visible | The section switch is a second boolean beside each entity's own: one hidden with `entity:visible(false)` stays hidden; an `entity:visible(flag)` written while the section is off takes effect when it returns; one placed while off is created, listed, and waits. |
| Three questions | `entity:visible()` (is this one hidden), `hafen.virtual():visible()` (is the section off), `entity:drawn()` (what those two and [the ground](#the-ground-under-one-that-stands-still) come to). None is "on screen right now", which also depends on the camera. |

### Clicking what stands in the world (protected)

| Method | Returns | Permission | Description |
|---|---|---|---|
| `hafen.virtual():click(key, x, y [, angle])` | `boolean` | `virtual.click` | Put the pointer on whatever is standing at a screen point ([clicks](widgets.md#clicks-are-the-widgets-own)). `x, y` are design-space screen pixels, the numbers `hafen.ui():mouse()` reports; `false` means no panel took the point, and the client's own world click goes on. |

Why keyed: a panel can hold one of the client's own widgets ([`widget:parent(panel)`](../ui/native.md) takes a minimap, a portrait or a button into a surface of yours), and a pointer event put into a button presses it, with its own `wdgmsg`. The consent line reads *"click the controls it has standing in the world, which act as if you had clicked them"*.

## Pages

| Page | Covers |
|---|---|
| [Ghosts](ghosts.md) | One of the game's own `.res` props, standing where you put it. |
| [Sprites](sprites.md) | An image in the world: facing modes, clicks. |
| [Models](models.md) | glTF: the supported subset, the object's verbs, clicks. |
| [Widgets](widgets.md) | A window standing in the world: facing, clicks, and the client's own. |
| [Patches](patches.md) | A shape lying flat on the terrain: place, look, border, clicks. |
| [Pieces](pieces.md) | The convex rings a patch is the union of: what a ring may be, the budget, taking one up. |

---

## See Also

- [`hafen.asset`](../asset/README.md) — the one door for the images and meshes these collections take.
- [Position](../position.md) — the place an anchor is given, and the placement snapping beside it.
- [`gob:overlay()`](../overlay.md) — what is drawn at a gob, these included, read-only.
- [The Widget object](../ui/widget.md) — what a standing widget goes on answering, unchanged.
- [Drawing](../ui/drawing.md) — the same images, drawn on screen instead.
- [Events](../event/bus/world.md#world-ghosts-and-sprites) — `GhostClicked`, `SpriteClicked`, `ObjectClicked`, `PatchClicked`.
