# hafen.sound: sound effects

Play a client sound effect by resource name, stop it, and ask what is still sounding. This is the whole
audio section; the client's own bundled effects, such as `"sfx/msg"` and `"sfx/error"`, resolve
locally, and any other resource name resolves the way every resource does.
To set volume levels rather than play anything, see
[`hafen.client():options():audio()`](client/README.md).

```lua
hafen.sound():get("sfx/msg"):play()          -- a client-bundled notification blip
hafen.sound():get("sfx/msg"):play(0.2)       -- the same blip, quietly
```

`hafen.sound()` **is** the collection: `:get(name)` hands back a **Sound object** for that resource
name, and the same name always gives the *same* object, so
`hafen.sound():get("sfx/msg") == hafen.sound():get("sfx/msg")` and you can stash one or use it as a
table key. A Sound is just the name, so it has no `:exists()` — a name has no lifetime to go stale. A
name that does not resolve is simply silent: the client logs a line and Lua never sees an error.
Resources resolve off the UI thread, so a not-yet-loaded one never throws either.

## Read

| Method | Returns | Description |
|---|---|---|
| `hafen.sound():get(name)` | Sound | the Sound for that resource name |
| `hafen.sound():list(filter)` | Sound[] | your addon's **still-playing** Sounds, 1-based; empty when there are none |
| `hafen.sound():count(filter)` | number | how many are still sounding |
| `hafen.sound():find(filter)` | Sound \| nil | the first still-sounding one that matches |
| `sound:res()` | string | the resource name this Sound addresses |
| `sound:playing()` | bool | whether a clip of this name is still sounding, or still starting |
| `sound:info()` | table | a flat snapshot, `{ res, playing }` |

**The two halves address different sets, on purpose.** `:get(name)` reaches *any* clip the game owns,
played or not, because sound resources are not enumerable and a Sound simply exists on demand.
`:list()` is *your* clips in the air: the client's own blips share the channel but are not yours to
enumerate or stop. It prunes as you ask, so the count falls back to zero by itself as clips end. A
string [filter](conventions.md#the-filter-argument) matches the resource name. There is no `:add` —
playing is `sound:play(volume)` — and no `:remove`, since silencing one is `sound:stop()`.

```lua
local live = hafen.sound():list()
for i = 1, #live do live[i]:stop() end   -- silence everything this addon started
```

## Play and stop (unprotected)

| Function | Returns | Description |
|---|---|---|
| `sound:play(volume)` | **self** | play the clip once; `volume` is `0..1`, default `1` |
| `sound:stop()` | **self** | cut every clip of this name **your addon** has in the air |

Playing is client-local and sends nothing to the server, which is why it needs no permission. A
`volume` that is not a number, or is outside `0..1`, raises an error rather than being clamped.

**Volume is an argument of the play, not state on the Sound.** Sounds are shared, so a stored volume
would leak between unrelated uses of the same clip; every `:play` says how loud that blip is.

`:play()` returns before the clip has actually started, because the resource resolves off the UI
thread, and `:stop()` accounts for that — `sound:play():stop()` makes no sound at all. `:stop()` also
cancels a play that has not started yet. Anything you leave playing is silenced when your addon is
disabled or reloaded: a disabled addon making noise is a bug.

```lua
local bell = hafen.sound():get("sfx/hud/mmap/bell3")
bell:play(0.6)
if bell:playing() then bell:stop() end    -- cut it mid-clip
```

## There is no hafen.music

`hafen.music` is **absent**, not stubbed: indexing it reads as plain `nil`. The client carries a
background-music player, but it is a MIDI player driven by one thing only, a `"bgm"` message from the
server, and this server never sends one — no resource in the cache carries a MIDI layer, only sound
effects. An API over it would answer `nil` forever.

What you hear as "music" in the world is something else: an **ambient loop** published by the world
resources around you, on the same mechanism as the crickets, and governed by the ambient volume in
Options ▸ Audio. That is a scene node with a lifetime rather than a clip handle, so it does not fit a
Sound; exposing it would be its own section rather than a retrofit here.

## See also

- [`hafen.client():options():audio()`](client/README.md) — master, UI, event and ambient volumes
- [`hafen.asset`](asset.md) — the files *your* addon ships, as opposed to engine resources
- [conventions](conventions.md#collections-the-noun-is-the-kind-the-verb-is-how-many) — the collection
  shape this shares
