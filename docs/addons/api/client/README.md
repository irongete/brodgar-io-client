# hafen.client: settings

`hafen.client():options()` opens the settings the client's **Options window** edits, plus the hotkey
registry. Reach for it to read or change what the user has configured — one handle per Options panel.
Reading is unprotected; **every write needs `client.settings`**.

```lua
local opts = hafen.client():options()

opts:interface()      -- UI scale, fine-placement granularity
opts:video()          -- shadows, render scale, vsync, framerate, lighting
opts:audio()          -- volumes and output latency
opts:camera()         -- the camera in force, and drag inversion
opts:client()         -- client-wide toggles
opts:keybindings()    -- declare, inspect and remap hotkeys
```

Every handle is a **stateless proxy** over the client's live preference stores — it holds no value of its
own, so a handle you keep in a variable never goes stale, and a write from Lua is indistinguishable from
the same edit made in the Options window: same stores, same persistence, and the panel shows your value
the next time it is opened. Each one is also **the same handle every time** you ask for it,
`opts:video() == opts:video()`, the identity [a section](../conventions.md#sections-you-call-one) has: a
handle works as a table key, and polling a setting from a draw callback allocates nothing.

Each is also a [handle in the API's one shape](../conventions.md#snapshots-vs-handles): a misspelt panel or
option raises naming what the handle does answer, rather than reading `nil` and failing a call later, and
nothing can be written onto one.

The frame profiler is the other half of this namespace: [`hafen.client():profiling()`](profiling/README.md).

## Where your code is running

`hafen.client():stepping()` answers whether the code you are in is running on the **step** — the pass that
fires [`Update`](../event/bus/lifecycle.md), runs your [timers](../timer.md), and hands over everything the
client has queued for it: a widget that [appeared or went](../ui/selectors.md), an
[item](../ui/items.md) whose contents changed, a [buff, a meter or a change of
gear](../event/bus/character.md#character-and-status), an [HTTP](../http.md) reply, a
[line of chat](../event/bus/chat.md). The client makes one a frame, and each character one of its own, so a
handler woken by one of them is on the step whichever character it was about. Reading is unprotected, and
it takes no argument.

```lua
if hafen.client():stepping() then
  hafen.log():write("this is the step")
end
```

The step is the one place your code is inside **no** character's tree, so a handler running there may reach
any of them. Everywhere else you are answering something — a [`Draw`](../ui/custom.md), a control's press,
a drop, an inbound [message](../event/streams.md) — and you are inside the one tree that dispatched it:
writing a widget of a *different* character's tree from there is refused, naming both. `stepping()` is how
a helper called from both places tells which it is in.

## Reading and writing

**The arity is the verb.** Calling an option with no argument reads it; calling it with one argument writes
it and returns the subsystem handle, so writes chain:

```lua
local scale = opts:interface():scale()            -- read  -> 1.0
opts:interface():scale(1.2):posGran(5)            -- write, write, chained

opts:video():shadows(true):vsync(false):lightLimit(8)
```

There is no `get`/`set` pair — one name per option. Always use a **colon** call with at most one argument;
anything else is an error. Invalid values raise a Lua error rather than being clipped, so a bad write fails
loudly instead of silently doing nothing.

A **number** option refuses `"60"` and a **string** option refuses `60`: what is checked is the value's
type, and a numeric string is
[still a string](../conventions.md#a-number-is-not-a-string-and-a-numeric-string-is-not-a-number). The
refusal names the option and its parameter, and it fires before the option is looked up at all, so it is the
same refusal whether or not the client's UI is up yet.

**An explicit `nil` is an error**, not a read. Because the argument is what makes a call a write,
`opts:video():shadows(v)` with a `v` that turns out to be `nil` would otherwise read the option and
report nothing wrong, leaving a write nobody made and a bug with no symptom. Test the value before you
pass it — an option has no undo for the refusal to cost anything against.

## `interface()`

| Method | Type | Description |
|---|---|---|
| `scale()` / `scale(v)` | number | the **stored** UI scale preference, `1.0` is native. **Requires a restart** |
| `posGran()` / `posGran(v)` | number | object fine-placement position granularity: subdivisions per tile, `2`..`17`, or `0` for unsnapped. Applies live |
| `angGran()` / `angGran(deg)` | number | object fine-placement angle granularity, **in degrees** per step. Applies live |

`scale` is the one option here that a restart gates: the write is persisted immediately, exactly like the
panel's own slider, and the client picks it up on the next launch. So it is the scale that *will* apply and
not the one in force — those differ until the next launch, and they differ on a fresh install, where this
reads `1.0` while the client picks its own starting scale from the display. What the client is drawing at
right now is [`hafen.ui():scale()`](../ui/pixels.md#read); nothing your addon measures in needs either.
`angGran` crosses this API as degrees — the value the Options panel displays — not the divisor the client
stores internally.

```lua
hafen.log():write("ui scale: " .. opts:interface():scale())
opts:interface():angGran(15)                       -- 24 steps per full turn
```

## `video()`

| Method | Type | Description |
|---|---|---|
| `shadows()` / `shadows(b)` | bool | shadow rendering |
| `renderScale()` / `renderScale(v)` | number | render resolution multiplier |
| `vsync()` / `vsync(b)` | bool | vertical sync |
| `fpsLimit()` / `fpsLimit(v)` | number | foreground framerate cap; `math.huge` is no limit |
| `bgFpsLimit()` / `bgFpsLimit(v)` | number | background (unfocused) framerate cap; `math.huge` is no limit |
| `lightingMode()` / `lightingMode(s)` | string | `"simple"` or `"zoned"` |
| `lightLimit()` / `lightLimit(n)` | number | maximum simultaneous dynamic lights |

`math.huge` round-trips verbatim on both framerate limits: reading an uncapped limit gives you `math.huge`,
and writing it removes the cap. A value the renderer refuses raises a Lua error carrying the client's own
message.

> **Every write here needs the `client.settings`
> [permission](../../guides/permissions.md).** These settings persist to the user's own preference stores —
> indistinguishable from the same edit made in the Options window — and
> [`binding:key(k)`](keybindings.md) reaches the **client's own** bindings, so an addon can take the
> inventory key. The consent dialog says *"change your client settings and hotkeys"*. **Reading needs
> nothing**, so a settings-aware addon that only adapts to what it finds declares no key at all.

**A write reaches every session up.** The renderer keeps its settings per tree, and all of those trees
persist to one file — so a per-tree write would move the scene you were looking at, leave the others at
whatever they loaded, and let whichever wrote last decide what survives a restart. `hafen.client()` is the
client rather than a character, so there is one answer here and every scene holds it. The read is the drawn
tree's, which after a write is the same as any other's.

```lua
local v = opts:video()
hafen.log():write("shadows: " .. tostring(v:shadows()) .. ", lighting: " .. v:lightingMode())
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

Volumes are `0.0`..`1.0` here, not the panel's slider units, and a value outside that range is an error.
`latency` is milliseconds, what the panel shows, not the sample count the engine stores, and writing it
reopens the audio output line.

To *play* sounds, see [`hafen.sound`](../sound.md) — this subsystem only sets levels. `ambientVolume`
governs the world's ambient loops, which is what sounds like background music here.

## `camera()`

| Method | Type | Description |
|---|---|---|
| `mode()` / `mode(name)` | string | the camera the world is drawn through: `"follow"`, `"worse"`, `"bad"`, `"ortho"` or `"rts"` |
| `invertHorizontal()` / `invertHorizontal(b)` | bool | invert horizontal camera drag |
| `invertVertical()` / `invertVertical(b)` | bool | invert vertical camera drag |

Every option here applies live. The inversions take effect on the very next drag; `mode` installs the
camera as you write it, and persists it, so the next session comes up on it too — the same act as picking
one in Options ▸ Camera or typing `:cam <name>`, and the panel shows your camera the next time it is opened.

The names above are the whole set, so there is no list verb to call: a name outside them raises, and the
message names both what you passed and every camera the client has.

```lua
local cam = hafen.client():options():camera()

if cam:mode() ~= "rts" then cam:mode("rts") end
```

`mode()` reads the camera **installed**, which is not always the one that was chosen: multi-session mode
puts its own camera on for as long as it is on, without disturbing the stored choice, and this reads what
is on screen. Before the world is up there is nothing installed, so it reads the stored choice instead,
and writing it there stores the camera the next session will come up on. It reads `nil` only when neither
answers a camera the client has.

## `client()`

Client-wide toggles, the Options ▸ Client panel.

| Method | Type | Description |
|---|---|---|
| `profiling()` / `profiling(b)` | bool | arm the client's profiler |

**Profiling is the client's own profiler, not a second one.** Arming it is exactly what the console's
`:profile on` does: it makes the client build its per-frame CPU and GPU trees, which is what every
[profiling read](profiling/README.md) is built on. One switch — the checkbox, `:profile` and this option
always agree — and the state is persisted like every other option.

It defaults **off** and should stay off unless you are measuring something. Off it costs nothing; on it is
a live instrumentation of every frame.

```lua
local c = hafen.client():options():client()

if not c:profiling() then c:profiling(true) end       -- the write needs client.settings
```

A write moves an **open** panel's checkbox immediately, since the panel re-reads the switch every frame.

> Arming takes effect on the **next** frame: the client decides at the start of each frame whether to build
> its profile trees, so the frame during which you flip the switch has none.

## Before the client is up

`video()` and `audio()` read **`nil`** until the client's UI exists, because their backing systems are built
with it, and a write in that window is ignored — its argument is checked first either way, so a bad one
raises there as it does anywhere else. `interface()`, `camera()` and `client()` always answer. In
practice this only matters if you touch options at load time on the login screen — guard the value, or do it
from `SessionEnteredWorld`:

```lua
local shadows = opts:video():shadows()
if shadows ~= nil then hafen.log():write("shadows: " .. tostring(shadows)) end
```

## See also

- [keybindings](keybindings.md) — declaring your addon's hotkeys, and remapping any binding
- [profiling](profiling/README.md) — the frame profiler this panel arms
- [`hafen.sound`](../sound.md) — playing sounds, as opposed to setting levels
- [events](../event/bus/lifecycle.md#sessions) — `SessionEnteredWorld`, the guard for the options that are not
  up yet
