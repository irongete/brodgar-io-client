# hafen.event: the world

A game object coming into view and leaving it, what the game or your addon attaches to one, and a click on
an entity you put in the world yourself. None of these carries a session: the world is the client's, not
one character's, so each fires once however many of your characters are looking. Everything here is part of
[the catalogue](README.md), so `hafen.event():on(key, fn)` is the door.

## World

| Event | Payload | Fires |
|---|---|---|
| `GobAdded` | [Gob](../../gob.md) | a game object enters the view of the first of your characters to see it |
| `GobRemoved` | [Gob](../../gob.md) | it leaves the view of the last one that could |
| `GobOverlayAdded` | `ev` — `:gob()` `:key()` `:native()` | something is attached to a game object — see [`gob:overlay()`](../../overlay.md) |
| `GobOverlayRemoved` | `ev` — `:gob()` `:key()` `:native()` | something attached to a game object goes away |
| `GobSdtChanged` | `ev` — `:gob()` `:sdt()` | the state bytes on a resource-drawn object change — see [`gob:sdt()`](../../gob.md#state) |

Prefer these over scanning [`s:world():gob():list`](../../world.md) every frame. `ev:gob()` is a live
[Gob object](../../gob.md). On `GobRemoved` the gob is **already gone**, so only `gob:id()` answers there;
if you need its name, index it on `GobAdded`.

### Before the first drawn frame

> **`GobAdded` runs before the object it announces is drawn.** The client holds a newly arrived object out
> of the scene until every handler has seen it, so a size or an overlay written in the handler is in force
> on the object's **first** drawn frame, not one frame late.

That is what makes the handler the place to decide how an object looks:

```lua
hafen.event():on("GobAdded", function(gob)
  local name = gob:name()
  if name and name:find("trees/", 1, true) then
    gob:overlay():add("tree"):text("tree"):color{200, 210, 220}      -- labelled from the first frame
  end
end)
```

The hold costs the object no data and gains it none. What answers inside the handler is exactly what
answers a step later — [`gob:name()`](../../gob.md), [`gob:sdt()`](../../gob.md#state) and
[`gob:hitbox()`](../../gob.md) for a resource-drawn object — and what has not resolved yet answers `nil`
there as it does anywhere else. An object whose visual is **composed** out of several resources, a
character or an animal carrying equipment, is one of those: it answers `nil` for a moment longer, and the
client does not wait for it. It waits for **your handler** and for nothing else, so a handler that needs
the name of such an object asks again from a [timer](../../timer.md).

The limits below are what keep the world drawable:

- **Only while somebody is listening.** Until an addon subscribes to `GobAdded`, nothing is held and the
  client draws exactly as it would with no addon loaded at all. The cost begins with the first
  subscription, not with the first object.
- **A held object is drawn anyway after a second.** A handler that takes longer than that, or an addon
  disabled mid-flight, costs a late frame and never a missing object.
- **Only objects the game sends.** A [thing of your own](../../vr/README.md) — a ghost, a sprite, a model,
  a standing widget, a patch — is yours already, fires no `GobAdded`, and is drawn the moment you place it.

`GobRemoved` makes no such promise: it reports an object that has already left, and there is nothing left
to hold.

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
flame), and `key` is then its resource name.

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
  loader threads, and both halves use one moment. A handler runs on the
  [step](../../threading.md) and reads the truth:
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

### The state changing

`GobSdtChanged` fires when [`gob:sdt()`](../../gob.md#state) changes — a crop advancing a stage, a gate
swinging open, a stockpile's count moving.

| `ev` on `GobSdtChanged` | Description |
|---|---|
| `ev:gob()` | the [Gob](../../gob.md) whose state changed |
| `ev:sdt()` | the new bytes — the same shape `gob:sdt()` answers |

```lua
hafen.event():on("GobSdtChanged", function(ev)
  hafen.log():write(ev:gob():id() .. " now carries " .. #ev:sdt() .. " state byte(s)")
end)
```

It fires once for the object, the same rule as `GobAdded`/`GobRemoved` above, and it fires **for the first
state a gob is given, too** — the moment its resource hands it bytes to report, not only on a later change
— so a handler never has to poll `gob:sdt()` while a resource is still loading. A gob whose resource
carries no state at all never fires, on that first delta or any after it: there is nothing to report. It
arrives on the next frame, like the two overlay events above, and after the `GobAdded` that introduced the
object if the two land in the same one.

`ev:sdt()` is **that firing's own bytes**, never a fresh `gob:sdt()` read: two changes landing in the same
frame would otherwise both answer the *last* one, and the state in between would never have happened as
far as a handler could tell.

Unlike the overlay events, `GobSdtChanged` is never owner-scoped — the state belongs to the server's
object, not to any one addon's attachment, so every subscriber is told alike.

## World ghosts and sprites

| Event | Payload | Fires |
|---|---|---|
| `GhostClicked` | `ev` — `:ghost()` `:button()` `:x()` `:y()` | a **clickable** [ghost](../../vr/ghosts.md) of *your* addon is clicked |
| `SpriteClicked` | `ev` — `:sprite()` `:button()` `:x()` `:y()` | a **clickable** [sprite](../../vr/sprites.md#clickability) of *your* addon is clicked |
| `ObjectClicked` | `ev` — `:object()` `:button()` `:x()` `:y()` | a **clickable** [glTF object](../../vr/models.md#clickability) of *your* addon is clicked |
| `PatchClicked` | `ev` — `:patch()` `:button()` `:x()` `:y()` | a **clickable** [patch](../../vr/patches.md#clickability) of *your* addon is clicked |

Each of them is **owner-scoped**: it fires only to the addon that owns the clicked entity, unlike the
world events above and the roster's, which broadcast. That is because a thing you put in the world is
private to your addon and its handle never leaves it.

| `ev` on the four | Description |
|---|---|
| `ev:ghost()` / `ev:sprite()` / `ev:object()` / `ev:patch()` | the clicked [entity](../../vr/README.md#one-vocabulary-every-kind) — only the one matching the event fires reads non-nil |
| `ev:button()` | 1 for left, 3 for right |
| `ev:x()` `ev:y()` | the world point the click resolved to |

The click is **consumed** — no server click, no character walk. An entity fires this only while
clickable; a non-clickable one is click-through and silent, and a sprite facing `"screen"` has no
world mesh, so it is never picked at all.

**A patch is hit-tested rather than picked**, against the ring it was laid as, so it answers whether or not
you can see that ground — behind a hill, under a house. The other three are found by the engine's own pick
pass and so answer only where they are visible.

## See also

- [the catalogue](README.md) — the other families, and whose character an event was
- [the Gob object](../../gob.md) — what the payload of the first two answers
- [`gob:overlay()`](../../overlay.md) — the collection the two overlay events report on
- [the world entities](../../vr/README.md) — the ghosts, sprites, models and patches these clicks are about
- [`s:world()`](../../world.md) — reading the world on demand instead of listening to it
