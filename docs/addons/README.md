# AddOn Development

The client features a sandboxed **Lua addon system**. Addons are modular folders containing a `manifest.json` and one or more `.lua` files. They interact with the game state, UI, world, events, and networking exclusively through the global `hafen` API.

## Quick Look

```lua
-- addons/my_addon/main.lua

-- Log entering the world with character name
hafen.event():on("SessionEnteredWorld", function(session)
  local character_name = session:character() or "Unknown"
  hafen.log():write("Hello from character: " .. character_name)
end)

-- Scan for nearby trees every 5 seconds
hafen.timer():every(5, function()
  local current_session = hafen.session():current()
  if current_session then
    local tree_count = current_session:world():gob():count("terobjs/tree")
    hafen.log():write("Nearby trees: " .. tree_count)
  end
end)
```

To run this:
1. Place the folder inside the `addons/` directory.
2. In-game, press `:` to open the console and run `:reload`.

## Documentation Map

| Resource | Description |
|---|---|
| **[Getting Started](getting-started.md)** | Step-by-step tutorial: build a working addon with a window, hotkey, and persistent state. |
| **[Guides](guides/README.md)** | Practical task-oriented guides (custom UI, world interaction, events, permissions, data storage). |
| **[API Reference](api/README.md)** | Technical reference for all `hafen.*` namespaces, methods, signatures, and returns. |
| **[Manifest Specification](manifest.md)** | Schema and fields for `manifest.json` (metadata, permissions, network access). |
| **[Runtime & Sandbox](runtime.md)** | Lifecycle hooks (`Load`, `SessionEnteredWorld`, `Disable`), sandbox limits, and console commands. |
| **[AddOns Manager](panel.md)** | In-game addon management UI, installation workflow, and options integration. |
| **[Developer Tools](examples.md)** | Inspection tools included with the client (`widgetstack`, `eventstack`, `session-manager`). |

## API Subsystems Overview

| Subsystem | Namespaces | Purpose |
|---|---|---|
| **World & Objects** | [`world`](api/world.md), [`gob`](api/gob.md), [`position`](api/position.md), [`map`](api/map/README.md) | Query objects, terrain coordinates, grid tiles, and map markers. |
| **Character & Stats** | [`player`](api/player.md), [`char`](api/char.md), [`meter`](api/meter.md), [`buff`](api/buff.md), [`wound`](api/wound.md), [`study`](api/study.md) | Character attributes, energy/stamina meters, buffs, and study desk. |
| **Gameplay Systems** | [`actionbar`](api/actionbar.md), [`menugrid`](api/menugrid.md), [`flowermenu`](api/flowermenu.md), [`craft`](api/craft.md), [`quest`](api/quest.md), [`kin`](api/kin.md), [`party`](api/party.md) | Menus, crafting actions, combat, action bar slots, and village/kin lists. |
| **User Interface** | [`ui`](api/ui/README.md), [`font`](api/font.md), [`style`](api/ui/style/README.md), [`client`](api/client/README.md) | Custom windows, UI controls, styling rules, keybindings, and settings. |
| **Networking & IO** | [`http`](api/http.md), [`websocket`](api/websocket.md), [`json`](api/json.md), [`asset`](api/asset/README.md) | External HTTP/WebSocket communication, JSON parsing, and loading custom images/sounds. |
| **Core Utilities** | [`event`](api/event/README.md), [`timer`](api/timer.md), [`store`](api/store/README.md), [`log`](api/log.md), [`console`](api/console.md) | Event listeners, recurring timers, SQLite persistence, and logging. |
