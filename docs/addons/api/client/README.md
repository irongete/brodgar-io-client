# hafen.client: The Client

`hafen.client():options()` opens the settings the client's Options window edits, one handle per panel, plus the hotkey registry and your addon's own options, and the addons it discovered. Reading is unprotected. Every write into the client's own settings needs `client.settings`. Your addon's own options need nothing.

```lua
local options = hafen.client():options()
hafen.log():write("shadows: " .. tostring(options:video():shadows()))
options:interface():angGran(15)                       -- write: needs client.settings
```

---

| Handle | Covers | Page |
|---|---|---|
| `options:interface()` | UI scale, fine-placement granularity. | [Below](#interface) |
| `options:video()` | Shadows, render scale, vsync, framerate, lighting. | [Below](#video) |
| `options:audio()` | Volumes and output latency. | [Below](#audio) |
| `options:camera()` | The camera in force, and drag inversion. | [Below](#camera) |
| `options:client()` | Client-wide toggles, remembered ground. | [Below](#client) |
| `options:keybindings()` | Declare, inspect and remap hotkeys; hide a section of the client's own from the panel. | [Keybindings](keybindings.md) |
| `options:addon()` | Options of your own, and the page the window shows them on. | [Addon options](addon.md) |
| `hafen.client():profiling()` | The frame profiler. | [Profiling](profiling/README.md) |
| `hafen.client():addons()` | Every addon installed, its export, and yours. | [Addons and libraries](addons.md) |

| Rule | Detail |
|---|---|
| Stateless proxies | The client panels hold no value of their own. A kept handle never goes stale. A write from Lua is the same edit as one made in the Options window (same stores, same persistence). The panel shows your value when next opened. |
| One handle | `options:video() == options:video()`, the identity [a section](../conventions.md#sections-you-call-one) has: a table key, and polling from a draw callback allocates nothing. |
| Closed | A misspelt panel or option raises naming what the handle answers ([handles](../conventions.md#snapshots-vs-handles)). Nothing can be written onto one. |

## Where your code is running

| Method | Returns | Permission | Description |
|---|---|---|---|
| `hafen.client():stepping()` | `boolean` | Unprotected | Whether the code you are in runs on the step: the pass that fires [`Update`](../event/bus/lifecycle.md), runs your [timers](../timer.md) and hands over everything the client queued. Takes no argument. |

| Rule | Detail |
|---|---|
| What the step hands over | A widget that [appeared or went](../ui/selectors.md). An [item](../ui/items.md) whose contents changed. A [buff, meter or gear change](../event/bus/character.md#character-and-status). An [HTTP](../http.md) reply. A [connection](../websocket.md) or [voice link](../voice/README.md) message. A [chat line](../event/bus/chat.md). |
| On the step | Your code is inside no character's tree, so a handler there may reach any of them. The client makes one step a frame, and each character one of its own. |
| Inside a tree | Answering a [`Draw`](../ui/custom.md), a control's press, a drop or a console line, you are inside the tree that dispatched it. Writing a different character's tree from there is refused naming both. |
| Neither | An inbound [message](../event/streams.md) handler holds no tree and reaches any. `stepping()` is `false` in it. The whole of it is [threading](../threading.md). |

## Reading and writing

The arity is the verb: no argument reads, one argument writes and returns the handle, so writes chain. There is no `get`/`set` pair.

```lua
local scale = options:interface():scale()          -- read  -> 1.0
options:interface():scale(1.2):posGran(5)          -- write, write, chained
options:video():shadows(true):vsync(false):lightLimit(8)
```

| Rule | Detail |
|---|---|
| Colon call, at most one argument | Anything else raises. |
| Invalid values raise | Not clipped. A number option refuses `"60"`, a string option refuses `60`, a switch refuses anything but `true` or `false`. A numeric string is [still a string](../conventions.md#a-number-is-not-a-string-and-a-numeric-string-is-not-a-number). `shadows("no")` or `recall(0)` raise rather than turning the setting on (every Lua value but `false` and `nil` is true). |
| The refusal comes first | It names the option and parameter and fires before the option is looked up, so it is the same before the UI is up. |
| An explicit `nil` raises | Not a read: `shadows(value)` with a `nil` value would otherwise read and leave a write nobody made. Test the value first. |

> **Every write into a client panel needs the `client.settings` [permission](../../guides/permissions.md).** These settings persist to the user's own preference stores. [`binding:key(key)`](keybindings.md) reaches the client's own bindings. The consent dialog says *"change your client settings and hotkeys"*. Reading needs nothing.

## `interface()`

| Method | Type | Permission | Description |
|---|---|---|---|
| `scale()` / `scale(value)` | `number` | read Unprotected / write `client.settings` | The stored UI scale preference. `1.0` is native. Requires a restart. |
| `posGran()` / `posGran(value)` | `number` | read Unprotected / write `client.settings` | Fine-placement position granularity: subdivisions per tile, `2`..`17`, or `0` for unsnapped. Applies live. |
| `angGran()` / `angGran(degrees)` | `number` | read Unprotected / write `client.settings` | Fine-placement angle granularity, in degrees per step. Applies live. |

| Rule | Detail |
|---|---|
| `scale` is restart-gated | Persisted immediately like the panel's slider and picked up on the next launch: the scale that will apply, not the one in force. On a fresh install it reads `1.0` while the client picks its starting scale from the display. What the client draws at now is [`hafen.ui():scale()`](../ui/pixels.md#read). |
| `angGran` | Crosses as degrees, the value the panel displays, not the divisor stored internally. |

## `video()`

| Method | Type | Permission | Description |
|---|---|---|---|
| `shadows()` / `shadows(flag)` | `boolean` | read Unprotected / write `client.settings` | Shadow rendering. |
| `renderScale()` / `renderScale(value)` | `number` | read Unprotected / write `client.settings` | Render resolution multiplier. |
| `vsync()` / `vsync(flag)` | `boolean` | read Unprotected / write `client.settings` | Vertical sync. |
| `fpsLimit()` / `fpsLimit(value)` | `number` | read Unprotected / write `client.settings` | Foreground framerate cap. `math.huge` is no limit. |
| `bgFpsLimit()` / `bgFpsLimit(value)` | `number` | read Unprotected / write `client.settings` | Background (unfocused) framerate cap. `math.huge` is no limit. |
| `lightingMode()` / `lightingMode(name)` | `string` | read Unprotected / write `client.settings` | `"simple"` or `"zoned"`. |
| `lightLimit()` / `lightLimit(count)` | `number` | read Unprotected / write `client.settings` | Maximum simultaneous dynamic lights. |

| Rule | Detail |
|---|---|
| `math.huge` round-trips | Reading an uncapped limit gives `math.huge`. Writing it removes the cap. |
| A value the renderer refuses | Raises carrying the client's own message. |
| A write reaches every session | The renderer keeps settings per tree and every tree persists to one file, so a write moves every scene. The read is the drawn tree's. |

## `audio()`

| Method | Type | Permission | Description |
|---|---|---|---|
| `masterVolume()` / `masterVolume(value)` | `number` | read Unprotected / write `client.settings` | Master volume, `0.0`..`1.0`. |
| `uiVolume()` / `uiVolume(value)` | `number` | read Unprotected / write `client.settings` | Interface sounds, `0.0`..`1.0`. |
| `eventVolume()` / `eventVolume(value)` | `number` | read Unprotected / write `client.settings` | In-game event sounds, `0.0`..`1.0`. |
| `ambientVolume()` / `ambientVolume(value)` | `number` | read Unprotected / write `client.settings` | Ambient sound, the world's ambient loops, `0.0`..`1.0`. |
| `latency()` / `latency(milliseconds)` | `number` | read Unprotected / write `client.settings` | Output buffer, in milliseconds. Writing it reopens the audio output line. |

| Rule | Detail |
|---|---|
| Units | Volumes are `0.0`..`1.0`, not the panel's slider units. Outside that range raises. `latency` is milliseconds, what the panel shows, not the client's sample count. |
| The volumes are the character on screen's | Each character has a scene with its own mixer: a read reports the drawn one, a write moves the drawn one. `latency` is the output line, the client's, so shared. Move a background character's volumes by handing it the screen with [`hafen.session():current(session)`](../session.md). |
| Playing sounds | [`hafen.sound`](../sound.md). This panel sets levels. |

## `camera()`

| Method | Type | Permission | Description |
|---|---|---|---|
| `mode()` / `mode(name)` | `string` | read Unprotected / write `client.settings` | The camera the world is drawn through: `"follow"`, `"worse"`, `"bad"`, `"ortho"` or `"rts"`. |
| `invertHorizontal()` / `invertHorizontal(flag)` | `boolean` | read Unprotected / write `client.settings` | Invert horizontal camera drag. |
| `invertVertical()` / `invertVertical(flag)` | `boolean` | read Unprotected / write `client.settings` | Invert vertical camera drag. |

| Rule | Detail |
|---|---|
| Live | The inversions take effect on the next drag. `mode` installs the camera as you write it and persists it, the act of Options ▸ Game ▸ Camera or `:cam <name>`. |
| The names are the whole set | No list verb. A name outside them raises naming what you passed and every camera the client has. |
| `mode()` reads the camera installed | Multi-session mode puts its own camera on without disturbing the stored choice, and this reads what is on screen. Before the world is up it reads the stored choice, and a write there stores the camera the next session comes up on. `nil` only when neither answers a camera the client has. |

```lua
local camera = hafen.client():options():camera()
if camera:mode() ~= "rts" then camera:mode("rts") end
```

## `client()`

Client-wide toggles, the Options ▸ Game ▸ Client panel.

| Method | Type | Permission | Description |
|---|---|---|---|
| `profiling()` / `profiling(flag)` | `boolean` | read Unprotected / write `client.settings` | Arm the client's profiler. |
| `recall()` / `recall(flag)` | `boolean` | read Unprotected / write `client.settings` | Draw the ground the character has already explored, from the client's own record. |
| `recallRange()` / `recallRange(grids)` | `number` | read Unprotected / write `client.settings` | How far around the camera that ground reaches, in grids, `1`..`8`. |
| `recallGrey()` / `recallGrey(flag)` | `boolean` | read Unprotected / write `client.settings` | Draw that ground without colour. |

| Rule | Detail |
|---|---|
| Profiling is the client's own profiler | Arming it is what `:profile on` does: the client builds its per-frame CPU and GPU trees, which every [profiling read](profiling/README.md) is built on. The checkbox, `:profile` and this option agree. The state persists. Default off. Off it costs nothing, on it is live instrumentation of every frame. A write moves an open panel's checkbox at once. Arming takes effect on the next frame. |
| Remembered ground | Every tile the character has walked, drawn back into the world where the [`"rts"` camera](#camera) looks: nothing asked of the server, nothing alive on it. The panel's **Remembered ground** section is these settings. |
| `recallRange` is a maximum | In grids of 100 tiles. What is drawn is bounded by the view and the client's mesh budget. Outside `1`..`8`, or not a whole number, is refused naming the bounds and leaves the range as it was. |
| `recallGrey` | A switch, not a strength: an off state and a wash of nothing are the same picture. |
| The client's, not a character's | Each applies live, moves an open panel, persists, and moves every session up. The cost is the `recall` keys on [`render()`](profiling/counters.md#render). |

```lua
local client_options = hafen.client():options():client()
client_options:recall(true):recallRange(4):recallGrey(true)     -- needs client.settings
hafen.log():write("remembered ground reaches " .. client_options:recallRange() .. " grids")
```

## Before the client is up

`video()` and `audio()` read `nil` until the client's UI exists, since their backing systems are built with it. A write in that window is ignored after its argument is checked. `interface()`, `camera()` and `client()` always answer. Guard the value, or act from `SessionEnteredWorld`.

```lua
local shadows = options:video():shadows()
if shadows ~= nil then hafen.log():write("shadows: " .. tostring(shadows)) end
```

---

## See Also

- [Your addon's own options](addon.md) — a setting the client stores and answers reads for, and its page.
- [Keybindings](keybindings.md) — declaring your addon's hotkeys, remapping any binding, and hiding a client section of the panel.
- [Profiling](profiling/README.md) — the frame profiler this panel arms.
- [`hafen.sound`](../sound.md) — playing sounds, as opposed to setting levels.
- [Events](../event/bus/lifecycle.md#sessions) — `SessionEnteredWorld`, the guard for the options not up yet.
