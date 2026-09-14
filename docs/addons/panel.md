# AddOns Manager

The AddOns window (accessible via `Ctrl+O ▸ AddOns` or the in-game menu) manages installed addons, permissions, and hub updates.

## Addon Statuses

The manager displays discovered addons alongside their current runtime state:

| Status | Meaning | Developer Action |
|---|---|---|
| `loaded v<version>` | Addon is active and running. | Normal operating state. |
| `disabled` | Addon is installed but toggled off. | Check the box and press **Reload UI** or run `:addons enable <id>`. |
| `error: <message>` | A syntax or runtime error occurred during startup. | Check the client log/terminal output for the full stack trace. |
| `manifest error` | `manifest.json` could not be parsed or is missing required fields. | Validate JSON syntax and ensure `id`, `api_version`, and `files` are present. |
| `outdated (...)` | The declared `api_version` does not match the client's supported edition. | Update `api_version` in `manifest.json` to `"1.0"`. |
| `auto-disabled (...)` | The addon exceeded the watchdog instruction limit or tick budget. | Profile your callbacks to eliminate heavy operations from render loops. |
| `[protected: N]` | Addon declares `N` protected permissions. | The user will be prompted with a confirmation dialog before the addon is enabled. |
| `[net]` | Addon declares outgoing network access. | The user can inspect the list of declared hostnames in the tooltip. |

## Registering Addon Settings in Options

To provide a configuration interface for your addon inside the client's native Options dialog, use [`hafen.client():addon()`](api/client/addon.md):

```lua
-- addons/my_addon/main.lua

local addon_entry = hafen.client():addon("my_addon")
local options_page = addon_entry:options()

options_page:title("My Addon Settings")

-- Add standard option controls
options_page:check("enable_audio", "Enable Sound Alerts", true)
options_page:slider("alert_distance", "Detection Range (Tiles)", 5, 50, 15)

-- Read options in your code
local sound_enabled = options_page:get("enable_audio")
local detection_range = options_page:get("alert_distance")
```

Values saved through the client's Options panel persist in `savedata/client.sqlite` and are retained across addon reloads.

## Development Reload Workflow

1. Edit your source files in `addons/<id>/`.
2. Open the in-game console with `:` and enter `:reload`, or press the **Reload UI** button in the AddOns panel.
3. If new permissions were added to `manifest.json`, the client will prompt the user to review the updated permissions before re-enabling.
