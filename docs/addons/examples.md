# Developer Tools & Example Addons

The maintainers provide open-source reference addons in the [brodgar-io-client-addons](https://github.com/irongete/brodgar-io-client-addons) repository. These addons serve both as practical examples of the `hafen.*` API and as debugging tools during development.

## Included Inspection Tools

| Tool | Console Command | Primary Purpose |
|---|---|---|
| **[widgetstack](https://github.com/irongete/brodgar-io-client-addons/blob/HEAD/widgetstack/main.lua)** | `:widgetstack` | Live UI inspector: inspect widget hierarchy, selectors, positions, and bounds under the cursor. |
| **[eventstack](https://github.com/irongete/brodgar-io-client-addons/blob/HEAD/eventstack/main.lua)** | `:eventstack` | Event bus & network stream monitor: track game events, server messages, and UI updates in real time. |
| **[session-manager](https://github.com/irongete/brodgar-io-client-addons/blob/HEAD/session-manager/main.lua)** | `:sessions` | Multi-session switcher: test multi-character environments and session switching behavior. |
| **[voice](https://github.com/irongete/brodgar-io-client-addons/blob/HEAD/voice/main.lua)** | None | Reference implementation of the proximity audio and voice streaming subsystem. |

---

## 1. UI Inspector (`widgetstack`)

The `widgetstack` tool reveals the widget hierarchy beneath the mouse cursor and outputs the exact selector syntax required to target specific elements in code.

* **Open Inspector**: Type `:widgetstack` in the console.
* **Freeze Inspection**: Use the `freeze` keybinding to lock the inspection window while moving the mouse over the hierarchy.
* **Log Selector**: Type `:selector` to print the selector of the currently hovered element to the console.

### Practical Use
When customizing an existing game window (e.g. the inventory or character sheet), use `widgetstack` to find the exact selector query:

```lua
-- Example selector discovered using widgetstack
local inventory_window = hafen.session():current():ui():match("window[title=Inventory]")
if inventory_window then
  local window_size = inventory_window:size()
  hafen.log():write("Inventory dimensions: " .. window_size.w .. "x" .. window_size.h)
end
```

---

## 2. Event Stream Monitor (`eventstack`)

The `eventstack` tool visualizes game events, incoming server updates, and UI interactions in real time.

* **Open Event Monitor**: Type `:eventstack` in the console.
* **Filtering**: Use the filter dropdowns to isolate specific event names, sessions, or event sources.
* **Inspect Payloads**: Click any event row in the list to view its complete payload structure and arguments.

### Practical Use
Use `eventstack` to determine which event to hook when a character performs an action:

```lua
-- Subscribing to an event identified in eventstack
hafen.event():on("GobAdded", function(game_object)
  local resource_name = game_object:name() or "Unknown"
  hafen.log():write("Spawned object: " .. resource_name)
end)
```
