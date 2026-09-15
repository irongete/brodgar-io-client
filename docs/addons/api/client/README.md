<a id="client"></a>
# hafen.client: Client Settings & State

`hafen.client()` exposes global client configuration panels, execution state queries, and profiling diagnostics.

```lua
local options = hafen.client():options()

-- Read current video settings
local current_vsync = options:video():vsync()
local current_limit = options:video():fpsLimit()

hafen.log():write(string.format("VSync: %s, FPS Limit: %s", tostring(current_vsync), tostring(current_limit)))
```

---

## Permission Requirements

- **Reading** settings is **unprotected**.
- **Writing** client preferences requires the [`client.settings`](../../guides/permissions.md) permission in `manifest.json`:
  ```json
  {
    "permissions": ["client.settings"]
  }
  ```
- Addon-specific settings ([`options:addon()`](addon.md)) require **no** permissions for both reads and writes.

---

## Options Panels

Access configuration panels via `hafen.client():options()`:

| Panel Method | Returns | Description |
|---|---|---|
| `options:interface()` | `InterfaceOptions` | UI scale and object placement snapping. |
| `options:video()` | `VideoOptions` | Graphics quality, framerate caps, lighting, and sync. |
| `options:audio()` | `AudioOptions` | Audio volume channels and driver buffer latency. |
| `options:camera()` | `CameraOptions` | World camera mode and drag inversion. |
| `options:client()` | `ClientOptions` | General toggles (profiling, remembered ground). |
| `options:keybindings()` | `Keybindings` | Hotkey registry and remapping. See [Keybindings](keybindings.md). |
| `options:addon()` | `AddonOptionRegistry` | Addon options and Options dialog page. See [Addon Options](addon.md). |

---

## Read/Write Convention

All option methods use arity to determine operation:
- **Zero arguments**: Reads the active configuration value.
- **One argument**: Updates the setting and returns the panel handle for chaining.

```lua
local video_options = hafen.client():options():video()

-- Read
local has_shadows = video_options:shadows()

-- Write (requires client.settings permission)
video_options:shadows(true):vsync(false):lightLimit(12)
```

Passing `nil` as a write argument produces an error.

---

## Execution Context: `stepping()`

`hafen.client():stepping()` returns `true` when code executes during the client's global frame update step (`Update` event, timers, async network completion callbacks).

```lua
if hafen.client():stepping() then
  -- Safe to access or modify any active session tree
end
```

- During `stepping()`, code is not bound to a specific character's widget tree.
- Inside widget callbacks (e.g. `Draw`, `MouseDown`), code runs within that specific session's tree.

---

## Interface Options

Accessed via `options:interface()`:

| Method | Type | Description |
|---|---|---|
| `interface:scale(value?)` | `number` | Target UI scale multiplier (`1.0` native). Takes effect on client restart. |
| `interface:posGran(value?)` | `number` | Placement position grid subdivisions per tile (`2`–`17`, or `0` for unsnapped). |
| `interface:angGran(degrees?)` | `number` | Placement rotation angle step in degrees. |

---

## Video Options

Accessed via `options:video()`. Values update the active renderer immediately.

| Method | Type | Description |
|---|---|---|
| `video:shadows(enabled?)` | `boolean` | Enable or disable dynamic shadow mapping. |
| `video:renderScale(multiplier?)` | `number` | Render resolution scaling multiplier. |
| `video:vsync(enabled?)` | `boolean` | Vertical synchronization. |
| `video:fpsLimit(limit?)` | `number` | Foreground framerate cap (`math.huge` for uncapped). |
| `video:bgFpsLimit(limit?)` | `number` | Unfocused background framerate cap (`math.huge` for uncapped). |
| `video:lightingMode(mode?)` | `string` | Lighting quality mode: `"simple"` or `"zoned"`. |
| `video:lightLimit(count?)` | `number` | Maximum active dynamic light sources. |

---

## Audio Options

Accessed via `options:audio()`:

| Method | Type | Description |
|---|---|---|
| `audio:masterVolume(volume?)` | `number` | Master audio volume (`0.0` to `1.0`). |
| `audio:uiVolume(volume?)` | `number` | Interface sound volume (`0.0` to `1.0`). |
| `audio:eventVolume(volume?)` | `number` | World event sound volume (`0.0` to `1.0`). |
| `audio:ambientVolume(volume?)` | `number` | Ambient environment loop volume (`0.0` to `1.0`). |
| `audio:latency(milliseconds?)` | `number` | Audio buffer output latency in milliseconds. |

---

## Camera Options

Accessed via `options:camera()`:

| Method | Type | Description |
|---|---|---|
| `camera:mode(name?)` | `string` | Active camera mode: `"follow"`, `"worse"`, `"bad"`, `"ortho"`, or `"rts"`. |
| `camera:invertHorizontal(enabled?)` | `boolean` | Invert horizontal mouse drag rotation. |
| `camera:invertVertical(enabled?)` | `boolean` | Invert vertical mouse drag pitch. |

---

## Client Options

Accessed via `options:client()`:

| Method | Type | Description |
|---|---|---|
| `client:profiling(enabled?)` | `boolean` | Enable client frame profiler instrumentation. See [Profiling](profiling/README.md). |
| `client:recall(enabled?)` | `boolean` | Render explored terrain cache in RTS camera mode. |
| `client:recallRange(grids?)` | `number` | Explored terrain render radius in grids (`1` to `8`). |
| `client:recallGrey(enabled?)` | `boolean` | Render explored terrain in greyscale. |

---

## Initialization Lifecycle

`video()` and `audio()` return `nil` if accessed before the client user interface has initialized (e.g. early during startup prior to the login screen). Query them after `SessionEnteredWorld` or check for `nil`:

```lua
local video_options = hafen.client():options():video()
if video_options ~= nil then
  hafen.log():write("Shadows: " .. tostring(video_options:shadows()))
end
```

---

## See Also

- [Addon Options](addon.md) — Register custom persistent options and preference UI pages.
- [Keybindings](keybindings.md) — Declare and rebind keyboard shortcuts.
- [Profiling](profiling/README.md) — CPU/GPU frame profiling and hardware metrics.
- [`hafen.sound`](../sound.md) — Playback triggers for positional and event sounds.
