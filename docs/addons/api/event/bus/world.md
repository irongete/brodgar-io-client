# hafen.event: the world

A game object coming into view and leaving it, what the game or your addon attaches to one, and a click on
an entity you stood in the world yourself. None of these carries a session: the world is the client's, not
one character's, so each fires once however many of your characters are looking. Everything here is part of
[the catalogue](README.md), so `hafen.event():on(key, fn)` is the door.

## World

| Event | Payload | Fires |
|---|---|---|
| `GobAdded` | [Gob](../../gob.md) | a game object enters the view of the first of your characters to see it |
| `GobRemoved` | [Gob](../../gob.md) | it leaves the view of the last one that could |
| `GobOverlayAdded` | `ev` — `:gob()` `:key()` `:native()` | something is attached to a game object — see [`gob:overlay()`](../../overlay.md) |
| `GobOverlayRemoved` | `ev` — `:gob()` `:key()` `:native()` | something attached to a game object goes away |

Prefer these over scanning [`s:world():gob():list`](../../world.md) every frame. `ev:gob()` is a live
[Gob object](../../gob.md). On `GobRemoved` the gob is **already gone**, so only `gob:id()` answers there;
if you need its name, index it on `GobAdded`.

**One object, one event.** A tree is one tree however many of your characters are standing in front of
it, so five characters together produce one `GobAdded` for it and not five. A character walking away from
an object another one can still see fires nothing at all: [`gob:sessions()`](../../gob.md) reads who can see
it right now, so an addon that cares asks at the moment it cares rather than following an event stream to
find out. The two overlay events are the same fact one level down — a decoration on an object, yours or
the game's own, is reported when it reaches the first character who can see it and when it leaves the
last. A
session **ending** is its objects leaving their last view, so what only that character could see is
reported gone.

### Overlays coming and going

`GobOverlayAdded` and `GobOverlayRemoved` cover both halves of what
[`gob:overlay()`](../../overlay.md) reads.

| `ev` on `GobOverlayAdded`/`GobOverlayRemoved` | Description |
|---|---|
| `ev:gob()` | the [Gob](../../gob.md) the overlay is attached to |
| `ev:key()` | the overlay's key |
| `ev:native()` | `false` for one **you** attached, `true` for one the **game** put there |

`native` is `false` for one **you** attached and `true` for one the **game** put there (a lit fire's
flame, a crop's growth stage), and `key` is then its resource name.

```lua
hafen.event():on("GobOverlayAdded", function(ev)
  if ev:native() then hafen.log():write(ev:gob():id() .. " now carries " .. ev:key()) end
end)
```

The rules below make these predictable:

- **Yours are private, the game's are public.** An overlay key belongs to your addon, so a
  `native = false` event goes **only** to the addon that attached it — a key another addon cannot read
  is a name it cannot act on. Native events broadcast, because a resource name means the same thing to
  everyone.
- **They arrive on the next frame**, not inside the `:add` itself — the game's own overlays arrive on
  loader threads, and both halves use one moment. A handler runs on the UI thread and reads the truth:
  the overlay is already there on an add, already gone on a removal.
- **Re-attaching under the same key fires both** — the removal, then the add. The key survives; the thing
  under it does not.
- **The game's overlays are counted by key.** Several of them may share one resource and collapse to one
  key, so a second one of that resource arriving is not an add — read
  [`ov:count()`](../../overlay.md) for the multiplicity.

When a gob leaves, **yours** on it are reported gone *before* that gob's own `GobRemoved`, so a handler
already reads the truth. The game's are not: the client drops a departing gob whole rather than taking its
overlays off one by one, and a native removal is reported only while the gob is still there. A `:reload`
fires neither: the addon that would hear it is the one going away.

## World ghosts and sprites

| Event | Payload | Fires |
|---|---|---|
| `GhostClicked` | `ev` — `:ghost()` `:button()` `:x()` `:y()` | a **clickable** [ghost](../../vr/ghosts.md) of *your* addon is clicked |
| `SpriteClicked` | `ev` — `:sprite()` `:button()` `:x()` `:y()` | a **clickable** [sprite](../../vr/sprites.md#clickability) of *your* addon is clicked |
| `ObjectClicked` | `ev` — `:object()` `:button()` `:x()` `:y()` | a **clickable** [glTF object](../../vr/models.md#clickability) of *your* addon is clicked |

All three are **owner-scoped**: they fire only to the addon that owns the clicked entity, unlike the
world events above and the roster's, which broadcast. That is because a ghost, sprite or object is private
to its addon and its handle never leaves it.

| `ev` on `GhostClicked`/`SpriteClicked`/`ObjectClicked` | Description |
|---|---|
| `ev:ghost()` / `ev:sprite()` / `ev:object()` | the clicked [entity](../../vr/README.md#one-vocabulary-four-kinds) — only the one matching the event fires reads non-nil |
| `ev:button()` | 1 for left, 3 for right |
| `ev:x()` `ev:y()` | the world point the click resolved to |

The click is **consumed** — no server click, no character walk. An entity fires this only while
clickable; a non-clickable one is click-through and silent, and a sprite facing `"screen"` has no
world mesh, so it is never picked at all.

## See also

- [the catalogue](README.md) — the other families, and whose character an event was
- [the Gob object](../../gob.md) — what the payload of the first two answers
- [`gob:overlay()`](../../overlay.md) — the collection the two overlay events report on
- [the world entities](../../vr/README.md) — the ghosts, sprites and models the three click events are about
- [`s:world()`](../../world.md) — reading the world on demand instead of listening to it
