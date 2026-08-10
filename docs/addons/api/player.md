# hafen.player: the local character

`hafen.player()` returns the **Player object** for the character you are logged in as. Its main job is
being the anchor for your own [Gob](gob.md).

```lua
local me = hafen.player():gob()          -- nil until you are in the world
local p = me and me:position()
if p then hafen.log():write(string.format("at %.0f, %.0f", p:x(), p:y())) end
```

Player deliberately forwards **nothing** from the Gob: position, health, movement and facing are read
on `hafen.player():gob()`, so there is exactly one way to reach each of them. What lives on Player is
only what has no per-gob equivalent.

## Read

| Method | Returns | Description |
|---|---|---|
| `hafen.player():gob()` | [Gob](gob.md) \| nil | your own game object; `nil` before you are in the world |
| `hafen.player():name()` | string \| nil | the local character's name |
| `hafen.player():worldToScreen(p)` | `{x, y}` \| nil | project a [Position](world.md#the-position-type) to a map-view screen pixel |

`hafen.player()` always hands back the same object, and `hafen.player():gob()` is the same object as
`hafen.world():gob():get(<your id>)` — so `gob == hafen.player():gob()` is how you tell "is this me?"
from any other gob, with no id comparison. None of these reads is protected.

`worldToScreen` takes a place in the world and answers **plain pixels**, relative to the map view, which
is what a [gob overlay](gob.md#overlays) or a HUD overlay wants. What comes back is not a Position: a
pixel is not a place in the world, and only the direction that has an answer will type-check. It answers
`nil` before the map view exists, and for a point the view cannot project; anything that is not a
Position going in is an error. The inverse is
[`hafen.world():screenToWorld`](world.md#screen-to-world-and-placement-snapping).

> There is no `exists()` and no `id()` on Player: `hafen.player():gob()`, `nil` or not, and `gob:id()`
> answer both questions.

The hp, stamina and energy bars are not here. They are a HUD slot the server fills rather than
per-player state, so they live in [`hafen.meter`](meter.md).

## Write (protected: `actions`)

### `hafen.player():move(p)`

Walk to a [Position](world.md#the-position-type) — the click a left-click on that patch of ground sends, so
an **off-screen destination is fine**. Returns the Player, so a move chains.

`p` is required. Anything that is not a Position raises, a plain `{x, y}` table and a
[widget's pixel position](ui/widget.md) included: a place in the world and a point on the screen are
different kinds of thing, and the verb refuses the wrong one rather than walking you somewhere else. A
Position this session cannot locate raises too. Before you are in the world there is no map view, and it
raises saying so.

There is no `gob:move()` beside it. The server accepts a walk command for **your** character only, so
there is nothing a general Gob could do with the verb; [`gob:moving()`](gob.md#read) is the other
direction, a property of any gob rather than an order to one.

## The Hand

`hafen.player():hand()` is **the cursor**: the Hand you are carrying something on, and **`nil` whenever
you are not. That `nil` is the point** — `if h then h:use(x) end` is the guard, and there is no state in
which you are holding nothing and a held-item action still means something.

```lua
local h = hafen.player():hand()
if h then
  hafen.log():write("carrying " .. (h:item():name() or h:item():res() or "?"))
  h:use(hafen.world():gob():nearest("terobjs/plants"))     -- apply it to that plant
end
```

| Call | Returns | Description |
|---|---|---|
| `hafen.player():hand()` | Hand \| nil | the cursor while something is on it, `nil` while it is empty |
| `hand:item()` | [`Item`](ui/items.md#the-item-object) \| nil | what you are carrying |
| `hand:use(target, mods)` | the Hand | **protected: `actions`** — apply what you are carrying to `target` |

The two reads are not protected and neither throws. `hafen.player():hand()` hands back the same object
every call, so `==` works and there is nothing to release; it is the *cursor* rather than a snapshot of
it, so one you kept across a drop answers `nil` from `:item()` instead of naming what you used to hold.
Read it again rather than holding one.

**Taking an item does not carry your handle onto the cursor.** The client destroys the container's item
and builds a new one in the hand, so the [`Item`](ui/items.md#the-item-object) you held goes stale and
`hand:item()` is a different object. Read the hand for what is on the cursor.

### `hafen.player():hand():use(target, mods)`

Apply what you are carrying **to** something. `target` dispatches by type, and the three types are the
three the client itself has:

| `target` | What it does |
|---|---|
| an [`Item`](ui/items.md#the-item-object) | apply it onto that item, wherever the item is |
| a [Position](world.md#the-position-type) | apply it to the ground there |
| a [Gob](gob.md) | apply it to that object — the waterskin onto the plant, not onto the dirt beside it |

`mods` is optional and defaults to `0`: a bitfield, Shift = 1, Ctrl = 2, Alt = 4, added together. It
returns the Hand, so a run of uses chains.

> **`use()` with no target raises**, naming the three types, and it is not how you activate what you are
> holding. Every held-item action targets something; activating is
> [`hafen.player():hand():item():use()`](ui/items.md#write-protected-actions).

It raises with an empty cursor, with no map view, and for a target that has gone — an item that was moved,
used or consumed, or a gob that left view. Nothing is sent in any of those cases.

## See also

- [Gob](gob.md) — everything positional about your character, and `gob:click`
- [items](ui/items.md) — the Item the hand carries, and the verbs on one in a container
- [`hafen.meter`](meter.md) — the HUD bars
- [`hafen.char`](char.md) — attributes, skills and food
- [`hafen.world`](world.md#screen-to-world-and-placement-snapping) — `screenToWorld`, the inverse projection
