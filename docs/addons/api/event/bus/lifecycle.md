# Lifecycle Events

Events fired during client startup, frame updates, shutdown, and character session transitions.

## Events Reference

### `Load`
* **Triggered**: Once after all addon `.lua` files declared in `manifest.json` have finished running.
* **Arguments**: None.
* **Use for**: One-time global initialization.

```lua
hafen.event():on("Load", function()
  hafen.log():write("Addon files loaded.")
end)
```

---

### `Disable`
* **Triggered**: When the addon is unloaded (before reload, disable, or client exit).
* **Arguments**: None.
* **Use for**: Final cleanup before teardown.

```lua
hafen.event():on("Disable", function()
  hafen.log():write("Cleaning up resources before unload.")
end)
```

---

### `Update`
* **Triggered**: Every frame during the main client tick.
* **Arguments**: None.
* **Note**: Keep callbacks lightweight; avoid heavy loops or repetitive queries.

---

### `SessionEnteredWorld`
* **Triggered**: When a character finishes loading and enters the game world.
* **Arguments**: `session` ([`Session`](../../session.md)).
* **Use for**: Initializing character HUD windows and reading world state.

```lua
hafen.event():on("SessionEnteredWorld", function(session)
  local character_name = session:character() or "Unknown"
  hafen.log():write("Entered world with character: " .. character_name)
end)
```

---

### `SessionSelected`
* **Triggered**: When the player switches the active screen view to another character session.
* **Arguments**: `session` ([`Session`](../../session.md)).

---

### `SessionRemoved`
* **Triggered**: When a character logs out or disconnects.
* **Arguments**: `session` ([`Session`](../../session.md)).

---

### `SessionAdded`
* **Triggered**: When a new character session is established before entering the world.
* **Arguments**: `session` ([`Session`](../../session.md)).
