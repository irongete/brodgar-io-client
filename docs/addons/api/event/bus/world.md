# World Events

Events triggered when entities spawn, despawn, or interact with radial menus in the game world.

## Events Reference

### `GobAdded`
* **Triggered**: When an entity or object loads into view within render distance.
* **Arguments**: `game_object` ([`Gob`](../gob.md)).

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
* **Arguments**: `game_object` ([`Gob`](../gob.md)). Note: only `:id()` is guaranteed to answer reliably once despawned.

```lua
hafen.event():on("GobRemoved", function(game_object)
  hafen.log():write("Entity removed: ID " .. game_object:id())
end)
```

---

### `FlowerMenuAdded`
* **Triggered**: When a radial right-click context menu opens.
* **Arguments**: `petals` (`Petal[]`), `session` ([`Session`](../session.md)).

```lua
hafen.event():on("FlowerMenuAdded", function(petals, session)
  hafen.log():write(string.format("Radial menu opened with %d options.", #petals))
end)
```

---

### `FlowerMenuRemoved`
* **Triggered**: When an active radial menu is dismissed or an option is selected.
* **Arguments**: `selected_label` (`string | nil`). `nil` if cancelled without selecting.
