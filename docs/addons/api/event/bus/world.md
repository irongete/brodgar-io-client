# World Events

Events triggered when entities spawn, despawn, overlays mutate, or virtual objects receive clicks.

## Events Reference

### `GobAdded`
* **Triggered**: When an entity or object loads into view within render distance.
* **Arguments**: `game_object` ([`Gob`](../../gob.md)).

```lua
hafen.event():on("GobAdded", function(game_object)
  local resource_name = game_object:name() or ""
  if resource_name:find("terobjs/tree") then
    -- New tree appeared in view
  end
end)
```

---

### `GobRemoved`
* **Triggered**: When an entity or object leaves render distance or despawns.
* **Arguments**: `game_object` ([`Gob`](../../gob.md)). Note: only `:id()` is guaranteed to answer reliably once despawned.

```lua
hafen.event():on("GobRemoved", function(game_object)
  hafen.log():write("Entity removed: ID " .. game_object:id())
end)
```

---

### `GobOverlayAdded`
* **Triggered**: When a visual overlay (e.g. equipment, equipment icon, or state marker) is attached to a game object.
* **Arguments**: `game_object` ([`Gob`](../../gob.md)), `overlay` ([`Overlay`](../../overlay.md)).

---

### `GobOverlayRemoved`
* **Triggered**: When a visual overlay is detached from a game object.
* **Arguments**: `game_object` ([`Gob`](../../gob.md)), `overlay` ([`Overlay`](../../overlay.md)).

---

### `GobSdtChanged`
* **Triggered**: When an object's server data table (`sdt`) or sub-state changes.
* **Arguments**: `game_object` ([`Gob`](../../gob.md)).

---

### `MarkerChanged`
* **Triggered**: When a map marker is added, updated, or recolored.
* **Arguments**: `marker` ([`Marker`](../../map/markers.md)).

---

### `FlowerMenuAdded`
* **Triggered**: When a radial right-click context menu opens.
* **Arguments**: `petals` (`Petal[]`), `session` ([`Session`](../../session.md)).

```lua
hafen.event():on("FlowerMenuAdded", function(petals, session)
  hafen.log():write(string.format("Radial menu opened with %d options.", #petals))
end)
```

---

### `FlowerMenuRemoved`
* **Triggered**: When an active radial menu is dismissed or an option is selected.
* **Arguments**: `selected_label` (`string | nil`). `nil` if cancelled without selecting.

---

### `GhostClicked`
* **Triggered**: When the player clicks on a virtual client-side ghost model.
* **Arguments**: `ghost` ([`Ghost`](../../virtual/ghosts.md)), `button` (`number`), `modifiers` (`number`).

---

### `SpriteClicked`
* **Triggered**: When the player clicks on a virtual 2D sprite standing in the world.
* **Arguments**: `sprite` ([`Sprite`](../../virtual/sprites.md)), `button` (`number`), `modifiers` (`number`).

---

### `ObjectClicked`
* **Triggered**: When the player clicks on a virtual 3D glTF model.
* **Arguments**: `object` ([`Model`](../../virtual/models.md)), `button` (`number`), `modifiers` (`number`).

---

### `PatchClicked`
* **Triggered**: When the player clicks on a virtual terrain patch or polygon.
* **Arguments**: `patch` ([`Patch`](../../virtual/patches.md)), `button` (`number`), `modifiers` (`number`).
