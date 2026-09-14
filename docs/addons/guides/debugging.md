# Debugging and Profiling

Effective debugging workflows for writing and testing client addons without restarting the game client.

---

## 1. The Fast Development Loop

1. Edit your source file in `addons/<id>/main.lua`.
2. In-game, press `:` to open the console and type `:reload`.
3. Check the console or system chat tab for logs and errors.

All event listeners, timers, windows, and state are cleanly rebuilt on each `:reload` without disconnecting your character.

---

## 2. Logging and Output

### In-Game Console & Terminal Logging
Use [`hafen.log():write()`](../api/log.md) to output messages tagged with your addon ID:

```lua
local session = hafen.session():current()
if session then
  local character_name = session:character() or "Unknown"
  hafen.log():write("Inspecting character: " .. character_name)
end
```

### Serializing Tables for Inspection
To inspect complex tables or nested structures, serialize them to JSON using [`hafen.json()`](../api/json.md):

```lua
local session = hafen.session():current()
local saved_settings = session:store():var("ui_settings")

local serialized_json = hafen.json():encode(saved_settings)
hafen.log():write("Current settings state: " .. serialized_json)
```

---

## 3. Live REPL with `:lua`

Press `:` in-game to execute arbitrary Lua statements directly against the live client state:

```text
:lua hafen.session():current():world():gob():count("terobjs/tree")
:lua hafen.session():current():meter():get("stamina"):value()
```

The returned value is printed to the chat/terminal formatted as JSON.

---

## 4. UI and Event Inspection

Use the maintainers' inspection tools (included with the client):

* **UI Inspector**: Type `:widgetstack` to inspect visual elements under your cursor and reveal their selector syntax.
* **Event Stream**: Type `:eventstack` to monitor all events, server messages, and UI dispatches in real time.

---

## 5. Profiling Addon Performance

To check whether your callbacks or timers are impacting client frame rates:

```lua
local profiling_service = hafen.client():profiling()
local addon_stats = profiling_service:addon("my_addon")

if addon_stats then
  hafen.log():write("Frame execution time: " .. addon_stats:cpu_time_ms() .. " ms")
end
```

Remember that any individual callback consuming more than **10 million instructions** or averaging more than **10ms per frame** over 30 consecutive frames will trigger the watchdog and be auto-disabled.
