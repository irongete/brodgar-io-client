# hafen.client — client settings & hotkeys

`hafen.client:options()` opens the settings the client's **Options window** edits, plus the hotkey
registry. Five subsystems hang off it:

```lua
local opts = hafen.client:options()

opts:interface()      -- UI scale, fine-placement granularity
opts:video()          -- shadows, render scale, vsync, framerate, lighting
opts:audio()          -- volumes and output latency
opts:camera()         -- camera drag inversion
opts:keybindings()    -- register / inspect / remap hotkeys
```

Every handle is a **stateless proxy** over the client's live preference stores — nothing is cached, so
a handle you keep in a variable never goes stale, and a write from Lua is indistinguishable from the
same edit made in the Options window (same stores, same persistence, and the panel shows your value the
next time it is opened).

## Reading and writing

**The arity is the verb.** Calling an option with no argument reads it; calling it with one argument
writes it and returns the subsystem handle, so writes chain:

```lua
local scale = opts:interface():scale()            -- read  -> 1.0
opts:interface():scale(1.2):posGran(5)            -- write, write, chained

opts:video():shadows(true):vsync(false):lightLimit(8)
```

There is no `get`/`set` pair — one name per option, one canonical way. Always use a **colon** call with
at most one argument; anything else is an error.

Invalid values raise a Lua error rather than being clipped (`scale(-1)`, `masterVolume(3)`,
`lightingMode("fancy")`), so a bad write fails loudly instead of silently doing nothing.

## `interface()`

| Method | Type | Description |
|---|---|---|
| `scale()` / `scale(v)` | number | UI scale, `1.0` = native. **Requires a restart** — read once at startup |
| `posGran()` / `posGran(v)` | number | object fine-placement position granularity: subdivisions per tile (`2`..`17`), or `0` for infinite (unsnapped). Applies live |
| `angGran()` / `angGran(deg)` | number | object fine-placement angle granularity, **in degrees** per step. Applies live |

`scale` is the one option here that a restart gates: the write is persisted immediately (exactly like
the panel's "requires restart" slider), and the client picks it up on the next launch. `angGran` crosses
this API as degrees — the value the Options panel displays — not the divisor the client stores
internally.

```lua
hafen.log("ui scale: " .. opts:interface():scale())
opts:interface():angGran(15)                       -- 24 steps per full turn
```

## `video()`

Backed by the client's graphics settings, over the same seam the Options window uses.

| Method | Type | Description |
|---|---|---|
| `shadows()` / `shadows(b)` | bool | shadow rendering |
| `renderScale()` / `renderScale(v)` | number | render resolution multiplier |
| `vsync()` / `vsync(b)` | bool | vertical sync |
| `fpsLimit()` / `fpsLimit(v)` | number | foreground framerate cap; `math.huge` = no limit |
| `bgFpsLimit()` / `bgFpsLimit(v)` | number | background (unfocused) framerate cap; `math.huge` = no limit |
| `lightingMode()` / `lightingMode(s)` | string | `"simple"` or `"zoned"` |
| `lightLimit()` / `lightLimit(n)` | number | maximum simultaneous dynamic lights |

`math.huge` round-trips verbatim on both framerate limits — reading an uncapped limit gives you
`math.huge`, and writing it removes the cap.

A rejected value (one the renderer refuses) raises a Lua error carrying the client's own message.

```lua
local v = opts:video()
hafen.log("shadows: " .. tostring(v:shadows()) .. ", lighting: " .. v:lightingMode())
v:lightingMode("zoned"):lightLimit(16)
```

## `audio()`

| Method | Type | Description |
|---|---|---|
| `masterVolume()` / `masterVolume(v)` | number | master volume, `0.0`..`1.0` |
| `uiVolume()` / `uiVolume(v)` | number | interface sounds, `0.0`..`1.0` |
| `eventVolume()` / `eventVolume(v)` | number | in-game event sounds, `0.0`..`1.0` |
| `ambientVolume()` / `ambientVolume(v)` | number | ambient sound, `0.0`..`1.0` |
| `latency()` / `latency(ms)` | number | output buffer, in **milliseconds** |

Volumes are `0.0`..`1.0` here, not the panel's 0..1000 slider units; a value outside that range is an
error. `latency` is milliseconds (what the panel shows), not the sample count the engine stores, and
writing it reopens the audio output line.

To *play* sounds, see [`hafen.sound` / `hafen.music`](audio.md) — this subsystem only sets levels.

## `camera()`

| Method | Type | Description |
|---|---|---|
| `invertHorizontal()` / `invertHorizontal(b)` | bool | invert horizontal camera drag |
| `invertVertical()` / `invertVertical(b)` | bool | invert vertical camera drag |

Both apply live, on the very next drag.

## Before the client is up

`video()` and `audio()` read **`nil`** until the client's UI exists (their backing systems are built
with it), and a write in that window is ignored. `interface()` and `camera()` always answer. In practice
this only matters if you touch options at load time on the login screen — guard the value, or do it from
`OnEnterWorld` ([events](events.md)):

```lua
local shadows = opts:video():shadows()
if shadows ~= nil then hafen.log("shadows: " .. tostring(shadows)) end
```

## `keybindings()`

The client's hotkey registry: declare your addon's hotkeys, and read or remap any binding, yours or the
client's own.

| Method | Returns | Description |
|---|---|---|
| `register(name, fn)` | the handle | declare a hotkey owned by your addon; `fn` runs when it fires |
| `get(name)` | string \| nil | current key as a display string (`"Ctrl+M"`), or nil if unbound/unknown |
| `set(name, key)` | the handle | remap a binding; `"None"` unbinds it |
| `unregister(name)` | the handle | drop one of *your* hotkeys |
| `list()` | `{ [id] = key }` | every binding in the client, unbound ones reading `"None"` |

`register`, `set` and `unregister` return the handle, so they chain.

### Addon hotkeys start unbound

`register` takes **no default key**. Your addon names an action; **the user assigns the key** in
Options ▸ Keybindings, where every addon that registered a hotkey gets its own section, listed by
addon name. This is the WoW model, and the only one consistent with the client's one-key-one-action
exclusivity — an addon-chosen default could not claim a key already in use anyway, it would just lose
the collision and leave you with a hotkey that never fires.

So **advertise a suggested key in your README instead of claiming one**:

> *Suggested key: `Ctrl+H` — assign it in Options ▸ Keybindings ▸ hello.*

The user's assignment is persisted by the client and survives `:reload` and restarts; re-registering the
same name after a reload picks the existing binding back up.

### Names

Your own hotkeys are namespaced to your addon, so `register("test", fn)` and `get("test")` refer to the
same binding without you ever spelling your addon id. A name that is not one of yours falls back to the
client's own registry id — that is how you reach a built-in hotkey (`get("inv")`,
`set("inv", "Ctrl+I")`). Your scope is tried first, so a client binding can never shadow yours.

`list()` uses the **full registry ids**, so your hotkeys appear there as `addon/<your-addon-id>/<name>`.

`set` on a name that matches no binding is an error; `unregister` only touches your own hotkeys —
client bindings are not an addon's to drop.

### Key strings

A key is the last `+`-separated token, with optional modifiers before it: `Ctrl`/`Control`/`Ctl`,
`Shift`, `Alt`/`Meta` (case-insensitive). Named keys are `F1`..`F12`, `Space`, `Enter`/`Return`, `Tab`,
`Esc`, `Backspace`, `Delete`, `Insert`, `Home`, `End`, `PageUp`, `PageDown`, `Up`, `Down`, `Left`,
`Right`; anything else is a single character. `"None"` means unbound.

```lua
"F5"   "Ctrl+M"   "Shift+Alt+Left"   "None"
```

Modifier matching is exact: `"M"` fires only on a bare `M`, never on `Ctrl+M`.

### Example

```lua
local keys = hafen.client:options():keybindings()

keys:register("toggle", function()
  hafen.log("toggled")
end)

hafen.log("my key: " .. tostring(keys:get("toggle")))     -- nil until the user assigns one
hafen.log("inventory: " .. tostring(keys:get("inv")))     -- a client binding, e.g. "Tab"

for id, key in pairs(keys:list()) do
  if key ~= "None" then hafen.log(id .. " = " .. key) end
end
```

Hotkeys are torn down with your addon on reload or disable — you do not need to `unregister` in
`OnDisable`. Use `unregister` only to drop a hotkey while your addon keeps running.

> Global hotkeys are not an input hook: they run through the client's binding registry, after the
> client's own bindings. To intercept raw keys and mouse input before any widget sees them, use
> [`hafen.hook`](hooks.md).
