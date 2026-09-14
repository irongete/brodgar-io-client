# hafen.asset: Asset Collection

`hafen.asset()` manages resources loaded by the current addon. All paths are relative to the addon root directory and restricted by sandbox rules.

```lua
local asset_collection = hafen.asset()

for _, file_path in ipairs(asset_collection:files("audio")) do
  local asset = asset_collection:get(file_path)
  hafen.log():write(string.format("%s: %d bytes", file_path, asset:info().bytes))
end
```

---

## API Methods

All methods in `hafen.asset()` are unprotected.

| Method | Returns | Description |
|---|---|---|
| `hafen.asset():get(path)` | `Asset` | Loads, parses, and interns an asset by relative path. |
| `hafen.asset():list(filter?)` | `Asset[]` | Returns all active assets loaded by this addon in load order. |
| `hafen.asset():count(filter?)` | `number` | Returns number of active assets matching optional filter. |
| `hafen.asset():find(filter)` | `Asset \| nil` | Returns the first asset matching filter. |
| `hafen.asset():remove(asset)` | `hafen.asset` | Explicitly frees an asset handle. Chains. |
| `hafen.asset():files(directory?)` | `string[]` | Lists files directly contained in the specified addon directory. |

---

## Loading and Listing Assets

### `get(path)`

Loads a file from the addon directory. Subsequent calls with the same path return the interned asset handle.

```lua
local icon_asset = hafen.asset():get("textures/icon.png")
```

### `files(directory?)`

Scans the local addon directory and returns sorted, relative path strings:

```lua
local texture_files = hafen.asset():files("textures")
for _, file_path in ipairs(texture_files) do
  hafen.log():write("Found asset: " .. file_path)
end
```

- If `directory` is omitted, lists files in the addon root directory.
- Subdirectories inside the target directory are not traversed recursively.
- Symbolic links escaping the addon root directory are excluded.

---

## Removing Assets

### `remove(asset)`

Explicitly releases resources associated with the handle:

```lua
local asset_collection = hafen.asset()
local temporary_image = asset_collection:get("textures/large_splash.png")

-- Use asset...
asset_collection:remove(temporary_image)
```

- Removing an asset invalidates its handle and clears its interned cache entry.
- Next call to `:get(path)` will reload the file from disk.
- You can only remove assets loaded by your own addon. Passing built-in fonts, map drawings, or assets from other addons produces a Lua error.

---

## Error Handling

Asset calls validate paths and raise descriptive errors on failure:

| Condition | Raised Error |
|---|---|
| Absolute path passed (`"/icon.png"`) | Path must be relative to addon root. |
| Path traversal (`"../shared/icon.png"`) | Path leaves addon directory. |
| Non-existent file | File not found in addon directory. |
| Invalid file content | File decoding failed (e.g. malformed PNG, corrupt GLTF). |
| Non-string argument to `:get()` | Expected path string. |
| Calling `:get()` from interactive console | Interactive `:lua` console lacks an addon root directory. |
| Removing non-handle object | Parameter must be an asset handle. |

---

## Complete Example

```lua
local icon_asset = nil
local custom_font = nil

hafen.event():on("Load", function()
  icon_asset = hafen.asset():get("textures/indicator.png")
  custom_font = hafen.asset():get("fonts/Inter.ttf"):derive():size(12)
end)

local main_window = hafen.ui():window():title("Tracker"):size(180, 80)
main_window:on("Draw", function(draw_event)
  local graphics = draw_event:g()
  if icon_asset then
    graphics:image(icon_asset, 8, 8, 16, 16)
  end
  if custom_font then
    graphics:font(custom_font)
    graphics:text("Status: Active", 32, 10)
  end
end)
```

---

## See Also

- [`hafen.asset`](README.md) — Asset formats and caching architecture.
- [Asset Handles](handles.md) — Properties and methods of individual asset types.
- [`hafen.font`](../font.md) — Font management and typography.
