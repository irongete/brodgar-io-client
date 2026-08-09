# hafen.vr: everything of yours standing in the world

`hafen.vr()` is the one section for **client-only things standing in the 3D world** — a translucent copy of
one of the game's own props, your addon's own PNG, your addon's own glTF model, a whole window drawn out
there instead of on the screen. What separates one of these from a real game object is not where it is,
since both are in the world, but **whose** it is: nothing here ever reaches the server, so nothing here is
gated.

```lua
local p = hafen.player():gob():position()
hafen.vr():ghost():add("gfx/terobjs/arch/logcabin", p)     -- the game's own prop, standing at a point
hafen.vr():sprite():add(hafen.asset():get("icon.png"), p)  -- your own image
hafen.vr():object():add(chair, p)                          -- your own glTF model
hafen.vr():widget():add(win, p)                            -- a window, drawn in the world

local rabbit = hafen.world():gob():nearest("rabbit")
hafen.vr():sprite():add(icon, rabbit)                      -- following a game object
```

> **Ungated.** These are visualizations with no server id: the server never learns one exists and none of
> them grants a gameplay advantage, so they need no `actions` permission and no consent dialog. They sit
> alongside [a HUD overlay](../ui/custom.md#overlays), not [`hafen.act`](../act.md). Committing a *real*
> build is still the gated [`hafen.act():place`](../act.md).

Everything here is **bridge-owned**: every entity your addon stands is torn down automatically on reload,
disable and relogin, leaking neither a scene slot nor a GPU texture.

## The collections (ungated)

| Call | What it holds |
|---|---|
| `hafen.vr():ghost()` | the game's `.res` props this addon has stood — see [ghosts](ghosts.md) |
| `hafen.vr():sprite()` | its own images — see [sprites](sprites.md) |
| `hafen.vr():object()` | its own glTF models — see [models](models.md) |
| `hafen.vr():widget()` | the UI it has standing out there — see [widgets](widgets.md) |

Each is a [collection](../conventions.md#collections-the-noun-is-the-kind-the-verb-is-how-many) with the same five verbs, and each is the same object
every call, so you can keep it in an upvalue.

| Call | Returns | Description |
|---|---|---|
| `:add(what, anchor)` | the entity | stand one and hand it back, ready to configure |
| `:list(filter)` | entity`[]` | this addon's live ones of that kind |
| `:count(filter)` | number | how many, without building the array |
| `:find(filter)` | entity \| nil | the first one that matches |
| `:remove(x)` | the collection | end one now; also automatic on reload, disable and relogin |

`filter` is the canonical [filter](../conventions.md#the-filter-argument): `nil` is all of them, a **string**
is a substring match on what the thing draws — a ghost's resource name, a sprite's or an object's
addon-relative path, a standing widget's caption — and a **function** is called with the entity, a truthy
return keeping it.

## The anchor is an argument

`:add(what, anchor)` takes **two** things and both are required. `what` is the thing to draw, and `anchor` is
one of exactly two values:

- a **[Position](../world.md#the-position-type)** — it stands there and stays there.
- a **[Gob](../gob.md)** — it follows that game object every frame, wherever it goes.

Anything else raises, naming both forms. The place is an argument rather than a setter with a default
because the scene resolves the tile under a thing as it enters it, so **one with no place cannot be built at
all**.

**A Position anchor has to be a place that can be kept.** What one standing at a point holds is the
[durable](../world.md#the-position-type) form of that place — the grid the ground belongs to, and where
inside that grid the point sits — because a plain world coordinate is this session's answer and nothing
more: the client re-bases those numbers whenever the server drops the map. Ground nobody has ever walked
has no durable form at all, so `:add(what, p)` and `e:position(p)` refuse such a place rather than stand
something on a number that will stop meaning anywhere. `p:durable()` is the same question asked ahead of
time.

```lua
hafen.vr():ghost():add("gfx/terobjs/arch/logcabin", p)     -- a plan on the ground
hafen.vr():sprite():add(icon, prey):offset(0, 0, 14)       -- a marker floating over a creature
hafen.vr():widget():add(win, cupboard):facing("camera")    -- a panel standing on a game object
```

**An anchored one dies with its gob.** A felled tree takes the thing following it with it, and nothing is
kept in case the gob comes back: a gob that returns is bare, and re-anchoring is your own call from
[`GobAdded`](../event.md#world). One standing at a point is untouched by any of that.

> **`:add` raises when you are not in the world.** A thing in the 3D scene needs that scene, so placing one
> before you have entered the world is an error rather than a `nil` you would discover one setter later.
> Place from `EnterWorld` onward. Ground you have walked but that is not drawn right now is *not* an error:
> the thing waits and appears with [its ground](#the-ground-under-one-that-stands-still).

An [image](sprites.md) or a [model](models.md) is passed as a [`hafen.asset`](../asset.md) **handle**, never
a path string; a path raises an error naming `hafen.asset` as the way in. There is nothing to save by
allowing one, since assets are [interned](../asset.md#interning) and loading the same path again is free.

## One vocabulary, four kinds

Every entity of every kind answers the same verbs, each a read/write pair on one name: calling it bare
**reads**, calling it with a value **writes** and hands the entity back, so a whole placement is one chain.
Each kind then adds the one or two verbs only it has — [`g:res`](ghosts.md#the-ghost),
[`s:image`](sprites.md#the-sprite) and [`s:facing`](sprites.md#facing), [`o:mesh`](models.md#the-object),
[`x:widget`, `x:facing` and `x:screen`](widgets.md#the-standing-widget).

| Method | Description |
|---|---|
| `e:position()` | where it actually is, as a [Position](../world.md#the-position-type) |
| `e:position(p, a)` | stand it at `p`, optionally setting facing — **for one that stands still** |
| `e:offset()` / `e:offset(x, y, z)` | where it sits relative to the gob it follows, world units, `z` up — **for one that follows** |
| `e:rotate()` / `e:rotate(a)` | its own facing in radians, keeping position |
| `e:scale()` / `e:scale(k)` | uniform scale, `1` being original size |
| `e:alpha()` / `e:alpha(a)` | opacity `0..1`, where `1` is opaque |
| `e:tint()` / `e:tint(r, g, b, a)` | colour overlay `0..255`, the fourth component being blend strength; `nil` clears it |
| `e:visible()` / `e:visible(b)` | whether you have this one showing; `false` takes it out and keeps the entity |
| `e:drawn()` | is it in the 3D scene right now? — see [the ground under it](#the-ground-under-one-that-stands-still) |
| `e:clickable()` / `e:clickable(b)` | the pick surface — opt-in, and client-side only |
| `e:onClick()` / `e:onClick(fn)` | `fn(e, button, x, y)` fired on click |
| `e:exists()` | is it still in the world? `false` once the collection removed it |

**`:position` and `:offset` are the two halves of "where", one for each anchor.** A thing that follows a gob
has the gob's place, so writing `:position(p)` on it would be undone on the next frame — it raises instead,
naming `:offset`. A thing that stands still is offset from nothing, so `:offset` on it raises naming
`:position`. The **read** side of `:position()` always answers: for one that follows it is the gob's live
point, and for one that stands still it is the place being held — so `:info()` on it reads back the same
grid and offset for as long as it stands there, while `:x()` and `:y()` stay this session's answer to where
that is.

`:tint(nil)` stays legal: "no tint" is a real value, not an accident.

**A [standing widget](widgets.md) is the one kind that is not a picture, so it answers a click as a widget.**
Three kinds have `:onClick(fn)`, because "it was clicked" is the whole of what a picture has to say; a panel
fires its own `MouseDown` at the pixel the pointer landed on, so `:onClick` on one raises naming that
subscription instead, and `:clickable(b)` there means *does this panel take the pointer at all*.

## The ground under one that stands still

Something standing at a **point** is in the scene only while the terrain under it is drawn. Walk far enough
and the ground it stands on stops being drawn; it goes with that ground, and it is back — whole, in the same
place — the moment the ground returns. Nothing is lost meanwhile: it keeps its handle, its place, its look
and its `:exists()`, and every verb goes on answering.

There is nothing to turn off here, because a thing hanging over the edge of the world is never what you
asked for. Nor is it a write over anything of yours: `e:visible()` still reads back exactly what you last
told it while the thing itself waits for its ground.

One that **follows a gob** is not subject to any of this. Its place is the gob's, so it comes and goes
exactly as that game object does, and it ends with it.

`e:drawn()` is the one answer to *why can I not see it*. It is `false` while you have hidden it, while the
whole section is off, while its visual is still streaming in, and while a free one's ground is not drawn.

```lua
local e = hafen.vr():ghost():add("gfx/terobjs/arch/logcabin", faraway)
e:exists()      --> true    it is yours and it is placed
e:visible()     --> true    you never hid it
e:drawn()       --> false   there is no ground out there to stand on yet
```

## The whole section at once

Two verbs read and write the section as a whole, and both answer a question no single collection can be
asked.

| Call | Returns | Description |
|---|---|---|
| `hafen.vr():list(filter)` | entity`[]` | everything this addon has standing, across the kinds, in the order it was stood |
| `hafen.vr():visible()` | bool | is the section on screen? |
| `hafen.vr():visible(b)` | the section | take the whole section off screen, or put it back |

`hafen.vr():list()` takes the same canonical filter a per-kind list takes, and returns the same entities
those lists do — a ghost, a sprite, an object and a standing widget side by side, in **creation order**,
which is the order you placed them.

```lua
for _, e in ipairs(hafen.vr():list()) do
  hafen.log():write(tostring(e:position()))
end
hafen.vr():visible(false)          -- the lot, off screen
hafen.vr():visible(true)           -- back exactly as it was
```

**`:visible(false)` destroys nothing.** Every entity keeps its gob, its transform and its handle, so
`:exists()` stays true and every verb still answers while the section is off. Only the scene slot goes.

**Showing it again restores what was visible, not everything.** The section switch is a *second* boolean
beside each entity's own, never a write over it, so:

- one you had hidden with `e:visible(false)` stays hidden when the section comes back;
- an `e:visible(b)` written while the section is off is remembered and takes effect when it returns;
- one placed while the section is off is created and listed, and waits.

`e:visible()` and `hafen.vr():visible()` are therefore different questions — *is this one hidden* and *is
the section switched off* — and `e:drawn()` is a third: what those two and
[the ground](#the-ground-under-one-that-stands-still) come to. None of them is "is it on screen right now",
which also depends on where the camera is pointing.

## Pages

| Page | What it covers |
|---|---|
| [ghosts](ghosts.md) | one of the game's own `.res` props, standing where you put it |
| [sprites](sprites.md) | an image in the world: its facing modes, clicks |
| [models](models.md) | glTF: the supported subset, the object's verbs, clicks |
| [widgets](widgets.md) | a window standing in the world: its facing, its clicks, and the client's own |
| [gizmo](gizmo.md) | the drag handles that move, rotate and scale any of them |

## See also

- [`hafen.asset`](../asset.md) — the one door for the images and meshes these collections take
- [`hafen.world`](../world.md#the-position-type) — the Position type, and placement snapping
- [`gob:overlay()`](../gob.md#overlays) — what is drawn *at* a gob, including these read-only
- [the Widget object](../ui/widget.md) — what a standing widget goes on answering, unchanged
- [drawing](../ui/drawing.md) — the same images, drawn on screen instead
- [events](../event.md#world-ghosts-and-sprites) — `GhostClicked`, `SpriteClicked` and `ObjectClicked`
