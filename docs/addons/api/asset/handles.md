# hafen.asset: Asset Handles

Asset handles represent loaded resources (images, fonts, meshes, and raw data). All handle methods are unprotected and query in-memory data without performing disk I/O.

```lua
local icon = hafen.asset():get("icon.png")
hafen.log():write(string.format("%s %s (%dx%d)", icon:type(), icon:path(), icon:size().w, icon:size().h))
```

---

## Common Methods

Every asset handle implements the following methods:

| Method | Returns | Description |
|---|---|---|
| `asset:type()` | `string` | Asset type: `"image"`, `"font"`, `"mesh"`, or `"data"`. |
| `asset:path()` | `string` | Relative path the asset was loaded from. |

> Assets are bridge-owned and automatically freed on addon reload, disable, and logout. To explicitly free an asset early, call [`hafen.asset():remove(asset)`](collection.md).

Asset handles are immutable userdata objects:
```lua
local icon = hafen.asset():get("icon.png")
hafen.log():write(tostring(icon)) -- Asset(image, icon.png)
```

---

## Image Asset

Returned when loading `.png`, `.jpg`, or `.jpeg` files.

| Method | Returns | Description |
|---|---|---|
| `image:size()` | `{w: number, h: number}` | Dimensions in design pixels. |
| `image:info()` | `{width: number, height: number}` | Image dimension snapshot. |

### Usage

Image dimensions correspond directly to [design pixels](../ui/pixels.md) and scale with the UI:

```lua
local icon = hafen.asset():get("textures/badge.png")
local window = hafen.ui():window():title("Status"):size(200, 100)

window:on("Draw", function(event)
  local graphics = event:g()
  graphics:image(icon, 10, 10, 32, 32)
end)
```

Stylesheets can also reference images by relative path: `{asset = "textures/badge.png"}`.

---

## Font Asset

Returned when loading `.ttf` or `.otf` files. Extends [`FontHandle`](../font.md) with asset methods.

| Method | Returns | Description |
|---|---|---|
| `font:derive()` | `FontBuilder` | Create a derived font variant. |
| `font:family()` | `string` | Font family name. |
| `font:size()` | `number` | Font point size. |

```lua
local custom_font = hafen.asset():get("fonts/Inter.ttf"):derive():size(14)
local label = hafen.ui():label():font(custom_font):text("Custom Typography")
```

Stylesheets can reference fonts by path: `{asset = "fonts/Inter.ttf", size = 12}`.

---

## Mesh Asset

Returned when loading `.glb` or `.gltf` 3D model files.

| Method | Returns | Description |
|---|---|---|
| `mesh:bounds()` | `{min: Vector3, max: Vector3, extent: Vector3}` | Axis-aligned bounding box in world units. |
| `mesh:info()` | `table` | `{prims, textured, lit, textures, verts, tris}` breakdown. |

```lua
local model = hafen.asset():get("models/crate.glb")
local bounds = model:bounds()
hafen.log():write(string.format("Model height: %.2f world units", bounds.extent.z))
```

To display a mesh in the 3D world, pass the handle to [`hafen.virtual():model()`](../virtual/models.md).

---

## Data Asset

Returned for all non-media extensions (`.json`, `.txt`, `.csv`, `.bin`, etc.).

| Method | Returns | Description |
|---|---|---|
| `data:bytes()` | `string` | Raw binary contents. `#data:bytes()` gives byte count. |
| `data:text()` | `string` | Contents decoded as UTF-8 (leading BOM stripped). |
| `data:info()` | `{bytes: number}` | File byte size without loading data strings into Lua. |

### Usage

```lua
-- Parsing JSON configuration
local data_asset = hafen.asset():get("config/settings.json")
local parsed_data = hafen.json():parse(data_asset:text())

-- Reading raw binary data
local binary_asset = hafen.asset():get("data/sound.raw")
local raw_bytes = binary_asset:bytes()
hafen.log():write(string.format("Loaded %d bytes", #raw_bytes))
```

---

## See Also

- [`hafen.asset`](README.md) — Asset subsystem overview, supported formats, and security rules.
- [Collection](collection.md) — Managing, listing, and querying loaded assets.
- [`hafen.font`](../font.md) — Client font handles and styling.
- [`hafen.virtual`](../virtual/models.md) — Placing 3D meshes in the world.
- [`hafen.json`](../json.md) — Parsing JSON data strings.
