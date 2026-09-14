# hafen.asset: Addon Assets

Load custom images, textures, sound files, and static assets packaged inside your addon folder.

## Quick Example

```lua
-- Load a custom icon packaged in addons/my_addon/assets/icon.png
local icon_asset = hafen.asset():load("assets/icon.png")

-- Render asset in a custom UI window
custom_window:on("Draw", function(draw_event)
  draw_event:graphics():image(icon_asset, 10, 10)
end)
```

---

## Asset Subsystems

| Subsystem | Reference Page | Description |
|---|---|---|
| **Asset Handles** | **[handles.md](handles.md)** | Image and sound asset handle methods, dimensions, and caching. |
| **Asset Collection**| **[collection.md](collection.md)**| Listing and querying all loaded addon assets. |

---

## Methods on `hafen.asset()`

| Method | Parameters | Returns | Description |
|---|---|---|---|
| `:load(relative_path)` | `string` | `AssetHandle` | Loads an image (`.png`, `.jpg`), sound (`.ogg`, `.wav`), or model file relative to your addon directory. |
| `:get(relative_path)` | `string` | `AssetHandle \| nil` | Retrieves a previously loaded asset handle. |
