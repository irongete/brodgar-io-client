# hafen.event: The World

A game object coming into view and leaving it. What the game or your addon attaches to one. A click on an entity you put in the world. None carries a session: the world is the client's, so each fires once however many of your characters are looking. Part of [the catalogue](README.md).

```lua
hafen.event():on("GobAdded", function(gob)
  local name = gob:name()
  if name and name:find("trees/", 1, true) then
    gob:overlay():add("tree"):text("tree"):color{200, 210, 220}      -- labelled from the first frame
  end
end)
```

---

## World

| Event | Payload | Fires |
|---|---|---|
| `GobAdded` | [Gob](../../gob.md) | A game object enters the view of the first of your characters to see it. |
| `GobRemoved` | [Gob](../../gob.md) | It leaves the view of the last one that could. |
| `GobOverlayAdded` | `event`: `:gob()` `:key()` `:native()` | Something is attached to a game object ([`gob:overlay()`](../../overlay.md)). |
| `GobOverlayRemoved` | `event`: `:gob()` `:key()` `:native()` | Something attached to a game object goes away. |
| `GobSdtChanged` | `event`: `:gob()` `:sdt()` | The state bytes on a resource-drawn object change ([`gob:sdt()`](../../gob.md#state)). |

| Rule | Detail |
|---|---|
| Instead of scanning | Prefer these over [`session:world():gob():list`](../../world.md) every frame. `event:gob()` is a live [Gob](../../gob.md). |
| `GobRemoved` is already gone | Only `gob:id()` answers there. Index the name on `GobAdded`. |
| One object, one event | Five characters in front of one tree produce one `GobAdded`. A character walking away from an object another still sees fires nothing. [`gob:sessions()`](../../gob.md) reads who sees it now. A session ending is its objects leaving their last view, so what only that character saw is reported gone. The overlay events follow the same rule one level down. |

### Before the first drawn frame

`GobAdded` runs before the object it announces is drawn. The client holds a newly arrived object out of the scene until every handler has seen it. A size or an overlay written in the handler is in force on the object's first drawn frame.

| Rule | Detail |
|---|---|
| The hold changes no data | What answers inside the handler is what answers a step later: [`gob:name()`](../../gob.md), [`gob:sdt()`](../../gob.md#state), [`gob:hitbox()`](../../gob.md) for a resource-drawn object. What has not resolved answers `nil`. A composed object (a character or an animal carrying equipment) answers `nil` a moment longer and the client does not wait for it. Ask again from a [timer](../../timer.md). |
| Only while somebody is listening | Until an addon subscribes to `GobAdded` nothing is held. The cost begins with the first subscription and ends at the next `:reload`, not at `subscription:off()`. The seam stays armed. A hold with nobody to hand the object to is released by the next frame. [`GobOverlayAdded`](#overlays-coming-and-going) and [`GobSdtChanged`](#the-state-changing) arm seams of their own. |
| Drawn anyway after a second | A handler that takes longer, or an addon disabled mid-flight, costs a late frame and never a missing object. |
| Only objects the game sends | A [thing of your own](../../virtual/README.md) (a ghost, a sprite, a model, a standing widget, a patch) fires no `GobAdded`. It is drawn the moment you place it. |
| `GobRemoved` holds nothing | It reports an object that has already left. |

### Overlays coming and going

`GobOverlayAdded` and `GobOverlayRemoved` cover both halves of what [`gob:overlay()`](../../overlay.md) reads.

| `event` | Returns | Permission | Description |
|---|---|---|---|
| `event:gob()` | [Gob](../../gob.md) | Unprotected | The gob the overlay is attached to. |
| `event:key()` | `string` | Unprotected | The overlay's key. A resource name for a native one. |
| `event:native()` | `boolean` | Unprotected | `false` for one you attached, `true` for one the game put there (a lit fire's flame). |

```lua
hafen.event():on("GobOverlayAdded", function(event)
  if event:native() then hafen.log():write(event:gob():id() .. " now carries " .. event:key()) end
end)
```

| Rule | Detail |
|---|---|
| Yours are private, the game's are public | A `native = false` event goes only to the addon that attached it. Native events broadcast, since a resource name means the same to everyone. |
| Next frame | Not inside the `:add` itself: the game's overlays arrive on loader threads, and both halves use one moment. A handler runs on the [step](../../threading.md) and reads the truth: already there on an add, already gone on a removal. |
| Re-attaching under the same key | Fires both, the removal then the add. |
| Counted by key | Several game overlays may share one resource and collapse to one key, so a second of that resource arriving is not an add. [`overlay:count()`](../../overlay.md) has the multiplicity. |
| When a gob leaves | Yours on it are reported gone before its `GobRemoved`. The game's are not: the client drops a departing gob whole, and a native removal is reported only while the gob is there. A `:reload` fires neither. |

### The state changing

`GobSdtChanged` fires when [`gob:sdt()`](../../gob.md#state) changes: a crop advancing, a gate swinging open, a stockpile's count moving.

| `event` | Returns | Permission | Description |
|---|---|---|---|
| `event:gob()` | [Gob](../../gob.md) | Unprotected | The gob whose state changed. |
| `event:sdt()` | `number[]` | Unprotected | The new bytes, the shape `gob:sdt()` answers. |

```lua
hafen.event():on("GobSdtChanged", function(event)
  hafen.log():write(event:gob():id() .. " now carries " .. #event:sdt() .. " state byte(s)")
end)
```

| Rule | Detail |
|---|---|
| Once per object | The rule of `GobAdded`/`GobRemoved`. Fires for the first state a gob is given as well, so no handler polls `gob:sdt()` while a resource loads. A gob whose resource carries no state never fires. |
| Next frame | Like the overlay events, and after the `GobAdded` that introduced the object when both land in one frame. |
| `event:sdt()` is that firing's own bytes | Never a fresh `gob:sdt()` read: two changes in one frame would otherwise both answer the last one. |
| Never owner-scoped | The state belongs to the server's object. Every subscriber is told alike. |

## World ghosts and sprites

| Event | Payload | Fires |
|---|---|---|
| `GhostClicked` | `event`: `:ghost()` `:button()` `:x()` `:y()` | A clickable [ghost](../../virtual/ghosts.md) of your addon is clicked. |
| `SpriteClicked` | `event`: `:sprite()` `:button()` `:x()` `:y()` | A clickable [sprite](../../virtual/sprites.md#clickability) of your addon is clicked. |
| `ObjectClicked` | `event`: `:object()` `:button()` `:x()` `:y()` | A clickable [glTF object](../../virtual/models.md#clickability) of your addon is clicked. |
| `PatchClicked` | `event`: `:patch()` `:button()` `:x()` `:y()` | A clickable [patch](../../virtual/patches.md#clickability) of your addon is clicked. |

| `event` | Returns | Permission | Description |
|---|---|---|---|
| `event:ghost()` / `:sprite()` / `:object()` / `:patch()` | the [entity](../../virtual/README.md#one-vocabulary-every-kind) | Unprotected | The clicked entity. Only the one matching the event reads non-nil. |
| `event:button()` | `number` | Unprotected | `1` left, `3` right. |
| `event:x()`, `event:y()` | `number` | Unprotected | The world point the click resolved to. |

| Rule | Detail |
|---|---|
| Owner-scoped | Fires only to the addon that owns the clicked entity. A thing you put in the world is private, and its handle never leaves your addon. |
| Consumed | No server click, no character walk. |
| Clickable only | A non-clickable entity is click-through and silent. A sprite facing `"screen"` has no world mesh and is never picked. |
| A patch is hit-tested, not picked | Against each of the [pieces](../../virtual/pieces.md) it is the union of. It answers whether or not you can see that ground (behind a hill, under a house), wherever any piece is under the pointer. The others are found by the client's pick pass and answer only where visible. |

---

## See Also

- [The catalogue](README.md) — the other families, and whose character an event was.
- [The Gob object](../../gob.md) — what the payload of `GobAdded` and `GobRemoved` answers.
- [`gob:overlay()`](../../overlay.md) — the collection the two overlay events report on.
- [The world entities](../../virtual/README.md) — the ghosts, sprites, models and patches a click lands on.
- [`session:world()`](../../world.md) — reading the world on demand instead of listening to it.
