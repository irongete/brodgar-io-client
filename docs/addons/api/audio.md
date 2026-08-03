# hafen.sound — audio

Play client sound effects by resource name: start them, stop them, and ask what is still sounding.
This is the whole audio section — there is deliberately no `hafen.music` ([why](#there-is-no-hafenmusic)).

## hafen.sound(name) — a Sound object

`hafen.sound` is **callable**: `hafen.sound(resname)` hands back a **Sound object** for that
resource name. The same name always gives the *same* object (`hafen.sound("sfx/msg") ==
hafen.sound("sfx/msg")`), so you can stash one and use it as a table key.

| Method | Returns | Description |
|---|---|---|
| `sound:res()` | string | the resource name this Sound addresses |
| `sound:play([volume])` | **self** | play the clip once. `volume` is `0..1` (default `1`); outside that range is an error |
| `sound:stop()` | **self** | cut every clip of this name **your addon** has in the air (and cancel a `:play` that has not started yet) |
| `sound:playing()` | boolean | is a clip of this name still sounding (or still starting)? |
| `sound:info()` | table | a flat snapshot (`res`, `playing`) |

```lua
hafen.sound("sfx/msg"):play()          -- a client-bundled notification blip
hafen.sound("sfx/msg"):play(0.2)       -- the same blip, quietly
local ping = hafen.sound("sfx/error")  -- stash it; the handle is just the name
ping:play()

local bell = hafen.sound("sfx/hud/mmap/bell3")
bell:play(0.6)
if bell:playing() then bell:stop() end -- cut it mid-clip
```

## hafen.sound() — what your addon is playing

Called with **no argument**, `hafen.sound()` is the array of your addon's **still-playing** Sounds
(1-based; empty when there are none). It prunes as you ask, so the count drops back to `0` by itself
as clips end.

```lua
local live = hafen.sound()
for i = 1, #live do live[i]:stop() end   -- silence everything this addon started
```

It lists **your** clips only — the client's own blips share the same channel but are not yours to
enumerate or stop. Anything you leave playing is silenced for you when the addon is disabled or on
`:reload`: a disabled addon making noise is a bug.

`:play()` returns before the clip has actually started (the resource resolves off the UI thread), and
`:stop()` accounts for that — `sound:play():stop()` makes no sound at all.

**Volume is an argument of the play, not state on the Sound.** Sounds are shared — a stored volume
would leak between unrelated uses of the same clip — so every `:play` says how loud *that* blip is.

There is no `:exists()`: a resource name has no lifetime to go stale. A name that does not resolve
is simply silent (the client logs a line; Lua never sees an error). Resources resolve off the UI
thread, so a not-yet-loaded one never errors into Lua. Client-bundled effect names (e.g. `"sfx/msg"`,
`"sfx/error"`) resolve locally.

Playback is **ungated** — it is client-local and sends nothing to the server.

## There is no `hafen.music`

`hafen.music` is **absent** — not flattened, not stubbed. Indexing it reads as plain `nil`, and that is
on purpose.

The client *does* carry a background-music player (`haven.Music`), but it is a **MIDI** player, and the
only thing that ever starts it is a `"bgm"` message from the server. This server never sends one: of
the 132,777 files in a full resource cache, **zero** carry a `midi` layer (36 carry `audio` — all sound
effects). A `hafen.music` API would therefore answer `nil` forever. It was built to spec, tested
in-game, and removed whole.

What you actually hear as "music" in the world is something else: an **ambient loop**
(`ActAudio.Ambience`) published by the world resources around you, on the `amb` channel — the same
mechanism as the crickets, and what Options ▸ Audio ▸ "Ambient volume" governs
([`hafen.client:options():audio()`](client/README.md)). That is a render-tree node with a lifetime rather than
a clip handle, so it does not fit a Sound; if it is ever exposed it will be its own section
(`hafen.ambience`), not a retrofit here.

To *set* volumes (master, UI, event, ambient), see [`hafen.client:options():audio()`](client/README.md).
