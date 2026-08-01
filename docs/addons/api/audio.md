# hafen.sound / hafen.music — audio

Play client sound effects and background music by resource name.

## hafen.sound(name) — a Sound object

`hafen.sound` is **callable**: `hafen.sound(resname)` hands back a **Sound object** for that
resource name. The same name always gives the *same* object (`hafen.sound("sfx/msg") ==
hafen.sound("sfx/msg")`), so you can stash one and use it as a table key.

| Method | Returns | Description |
|---|---|---|
| `sound:res()` | string | the resource name this Sound addresses |
| `sound:play([volume])` | **self** | play the clip once. `volume` is `0..1` (default `1`); outside that range is an error |
| `sound:info()` | table | a flat snapshot (`res`) |

```lua
hafen.sound("sfx/msg"):play()          -- a client-bundled notification blip
hafen.sound("sfx/msg"):play(0.2)       -- the same blip, quietly
local ping = hafen.sound("sfx/error")  -- stash it; the handle is just the name
ping:play()
```

**Volume is an argument of the play, not state on the Sound.** Sounds are shared — a stored volume
would leak between unrelated uses of the same clip — so every `:play` says how loud *that* blip is.

There is no `:exists()`: a resource name has no lifetime to go stale. A name that does not resolve
is simply silent (the client logs a line; Lua never sees an error). Resources resolve off the UI
thread, so a not-yet-loaded one never errors into Lua. Client-bundled effect names (e.g. `"sfx/msg"`,
`"sfx/error"`) resolve locally.

Playback is **ungated** — it is client-local and sends nothing to the server.

## hafen.music

| Function | Returns | Description |
|---|---|---|
| `hafen.music.play(resname [, loop])` | — | play background music; `loop` defaults to `false`. A `nil`/empty `resname` stops playback |

```lua
hafen.music.play("music/theme", true)
hafen.music.play(nil)                -- stop music
```

Music streams from content (the remote resource pool), not the client jar.
