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
| `options:performance()` | How much world is drawn, and whether its relief is: flavor objects, crop and forageable density, ground blending and transitions, flat terrain, tree effects, smoke, weather. | [Below](#performance) |
| `options:interface()` | UI scale, fine-placement granularity. | [Below](#interface) |
| `options:video()` | Shadows, render scale, vsync, framerate, lighting. | [Below](#video) |
| `options:audio()` | Volumes and output latency. | [Below](#audio) |
| `options:camera()` | The camera in force, and drag inversion. | [Below](#camera) |
| `options:client()` | Client-wide toggles, the view distance. | [Below](#client) |
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

## The frame rate and the heap

```lua
local heap = hafen.client():memory()
local megabytes = math.floor(heap.heapUsed / 1048576)
hafen.log():write(hafen.client():fps() .. " fps, " .. megabytes .. " MB used")
```

| Method | Returns | Permission | Description |
|---|---|---|---|
| `hafen.client():fps()` | `number` | Unprotected | Frames per second over the last second, the figure the client's stats HUD shows. A whole number. Takes no argument. |
| `hafen.client():memory()` | `table` | Unprotected | The JVM heap and its collections: the table [`profiling:memory()`](profiling/counters.md#memory) answers, key for key. Takes no argument. |

| Rule | Detail |
|---|---|
| Always answers | Neither needs [profiling](profiling/README.md) armed. The client keeps both numbers whether anything reads them or not, so a HUD polling them from a draw callback adds no measuring of its own. Both answer at the login screen too. |
| What `fps()` follows | The frames the client actually drew: capped by [`fpsLimit`](#video), and by `bgFpsLimit` while the window is unfocused. |
| A fresh table each read | `memory()` is a snapshot of the moment. Its sizes are bytes, and `gcCount`/`gcMs` are cumulative, meaningful as a delta between two reads. |
| Read-only | An argument raises. The framerate cap is `options:video():fpsLimit(value)`. |

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
| Invalid values raise | Not clipped. A number option refuses `"60"`, a string option refuses `60`, a switch refuses anything but `true` or `false`. A numeric string is [still a string](../conventions.md#a-number-is-not-a-string-and-a-numeric-string-is-not-a-number). `shadows("no")` or `exploredGround(0)` raise rather than turning the setting on (every Lua value but `false` and `nil` is true). |
| The refusal comes first | It names the option and parameter and fires before the option is looked up, so it is the same before the UI is up. |
| An explicit `nil` raises | Not a read: `shadows(value)` with a `nil` value would otherwise read and leave a write nobody made. Test the value first. |

> **Every write into a client panel needs the `client.settings` [permission](../../guides/permissions.md).** These settings persist to the user's own preference stores. [`binding:key(key)`](keybindings.md) reaches the client's own bindings. The consent dialog says *"change your client settings and hotkeys"*. Reading needs nothing.

## `performance()`

How much world is drawn, and whether its relief is drawn: flavor objects, crop and forageable density, ground blending and transitions, flat terrain, tree effects, smoke, weather. Nothing here changes what the server knows or what a click reaches — every setting is client-local and purely visual.

| Method | Type | Permission | Description |
|---|---|---|---|
| `flavor()` / `flavor(percent)` | `number` | read Unprotected / write `client.settings` | How many of the flavor objects a tile seeds are drawn: the tufts, pebbles and flowers a tileset scatters over its ground. Whole `0`..`100`, default `100`. |
| `crops()` / `crops(percent)` | `number` | read Unprotected / write `client.settings` | How many of a crop tile's sprouts are drawn, field crops and trellis crops alike. Whole `1`..`100`, default `100`. |
| `forage()` / `forage(percent)` | `number` | read Unprotected / write `client.settings` | The same for forageables that grow as a clump. Whole `1`..`100`, default `100`. |
| `groundBlend()` / `groundBlend(passes)` | `number` | read Unprotected / write `client.settings` | How softly the ground blends its texture variants: the number of smoothing passes. Fewer passes leave harder edges between variants and draw fewer layers; `0` draws every tile's base texture alone. Whole `0`..`12`, default `12`. |
| `transitions()` / `transitions(flag)` | `boolean` | read Unprotected / write `client.settings` | Whether the skirts between two tile types are drawn. Default `true`. |
| `flatTerrain()` / `flatTerrain(flag)` | `boolean` | read Unprotected / write `client.settings` | Draw the terrain flat: every tile corner at one height, objects standing on that plane, cliffs standing on that plane at their real height, water keeping its depth. Default `false`. |
| `treeEffects()` / `treeEffects(flag)` | `boolean` | read Unprotected / write `client.settings` | Whether trees and bushes sway in the wind. Default `true`. |
| `smoke()` / `smoke(flag)` | `boolean` | read Unprotected / write `client.settings` | Whether smoke plumes are drawn: kilns, furnaces, ovens, chimneys, fires. Default `true`. |
| `clouds()` / `clouds(flag)` | `boolean` | read Unprotected / write `client.settings` | The game's own cloud shadows moving over the ground. Default `true`. |
| `rain()` / `rain(flag)` | `boolean` | read Unprotected / write `client.settings` | The game's own rain particles and their splashes. Default `true`. |
| `snow()` / `snow(flag)` | `boolean` | read Unprotected / write `client.settings` | The game's own snow particles. Default `true`. |
| `wetGround()` / `wetGround(flag)` | `boolean` | read Unprotected / write `client.settings` | The game's own sheen on the ground after rain. Default `true`. |
| `seasonTint()` / `seasonTint(flag)` | `boolean` | read Unprotected / write `client.settings` | The game's own seasonal tint of the ground. Default `true`. |

| Rule | Detail |
|---|---|
| Whole numbers | `flavor`, `crops` and `forage` are whole percentages in the unit the panel shows, never a `0.0`..`1.0` fraction; `groundBlend` is a whole count of passes. |
| The floor differs | `0` is the floor of `flavor`: at `0` a tile still seeds the pieces that carry ambient sound. `0` is the floor of `groundBlend`, which the panel shows as *Off*. `1` is the floor of `crops` and `forage`: a plant tile never draws nothing, so its growth stage stays readable. |
| The defaults draw the upstream picture | Every setting at its default is an exact no-op: the scene is what it has always been. |
| Applies live | A write takes effect with no relogin. `flavor`, `groundBlend`, `transitions` and `flatTerrain` rebuild the ground lazily, cut by cut, as the scene draws it; `crops` and `forage` re-create the plants already in view; `treeEffects` shows on the next tick; `smoke`, `clouds`, `rain`, `snow`, `wetGround` and `seasonTint` show within a frame. |
| The weather switches are the game's | `clouds`, `rain`, `snow`, `wetGround` and `seasonTint` switch the weather the game itself draws; their boxes stand on the Options ▸ Game ▸ Sky & weather page while *The game's own weather effects* is picked there, and `wetGround` and `seasonTint` under *Improved sky and weather effects* too. Where that page draws the clouds, or the rain and snow, its own way, the game's are not drawn, and these switches do not reach what the page draws: it has switches of its own. |
| Flat terrain changes the picture only | The server's heights, the recorded map and [`session:world():height`](../world.md#terrain-and-coordinates) stay real; the minimap and the map window draw their cliff lines as before. A click lands on the tile under the cursor. It applies live, cut by cut: objects reach the plane a moment before their hill does. |
| `smoke` is symmetric | Turning it back on shows a plume already burning without the server re-sending anything. A scent trail's smoke is never withheld: it is information, not decoration. |
| Answers before the world is up | Its backing is the client's own statics, built with the class — like `interface()`, `camera()` and `client()`. |
| The view distance is `client()`'s | The page's **View distance** section is [`client():exploredGround()` and `client():viewDistance()`](#client). |

```lua
local performance = hafen.client():options():performance()
performance:flavor(50):groundBlend(4)                    -- needs client.settings
hafen.log():write("crop density: " .. performance:crops() .. " %")
```

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
| `shadows()` / `shadows(flag)` | `boolean` | read Unprotected / write `client.settings` | Shadow rendering: the *Render shadows* box, which stands on the Options ▸ Game ▸ Performance page. |
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

Client-wide toggles: the Options ▸ Game ▸ Client panel, and the **View distance** section of the Performance panel.

| Method | Type | Permission | Description |
|---|---|---|---|
| `profiling()` / `profiling(flag)` | `boolean` | read Unprotected / write `client.settings` | Arm the client's profiler. |
| `exploredGround()` / `exploredGround(flag)` | `boolean` | read Unprotected / write `client.settings` | Draw the ground the character has already explored past what the server streams, from the client's own record. Default `true`. |
| `viewDistance()` / `viewDistance(grids)` | `number` | read Unprotected / write `client.settings` | How far around the camera that ground reaches, in grids, `1`..`64`. Default `2`. |

| Rule | Detail |
|---|---|
| Profiling is the client's own profiler | Arming it is what `:profile on` does: the client builds its per-frame CPU and GPU trees, which every [profiling read](profiling/README.md) is built on. The checkbox, `:profile` and this option agree. The state persists. Default off. Off it costs nothing, on it is live instrumentation of every frame. A write moves an open panel's checkbox at once. Arming takes effect on the next frame. |
| View distance | Every tile the character has walked, drawn back into the world in the colours it was recorded in, under every [camera](#camera): around where the `"rts"` camera looks, around the character under the others. Nothing is asked of the server for it and nothing alive stands on it. The default camera's view reaches as far as the range while it is on. The Performance panel's **View distance** section is these two settings. |
| `viewDistance` is a maximum | In grids of 100 tiles. The nearest two grids are drawn in full detail; past them the client draws the map's zoomed-out record, coarser with distance, with no objects on it. What is drawn is bounded by the view. Outside `1`..`64`, or not a whole number, is refused naming the bounds and leaves the range as it was. The panel's slider runs `2`..`64`; a `1` you write stands, and the slider shows it at its lowest end. |
| The client's, not a character's | Each applies live, moves an open panel, persists, and moves every session up. The cost is the `recall` keys on [`render()`](profiling/counters.md#render). |

```lua
local client_options = hafen.client():options():client()
client_options:exploredGround(true):viewDistance(4)     -- needs client.settings
hafen.log():write("the view distance reaches " .. client_options:viewDistance() .. " grids")
```

## Before the client is up

`video()` and `audio()` read `nil` until the client's UI exists, since their backing systems are built with it. A write in that window is ignored after its argument is checked. `performance()`, `interface()`, `camera()` and `client()` always answer. Guard the value, or act from `SessionEnteredWorld`.

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
