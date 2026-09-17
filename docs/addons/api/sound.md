# hafen.sound: Sound Effects

Play a client sound effect by resource name, stop it, and ask what is still sounding. The client's bundled effects (`"sfx/msg"`, `"sfx/error"`) resolve locally. Any other name resolves as every resource does. Volume levels are [`hafen.client():options():audio()`](client/README.md).

```lua
hafen.sound():get("sfx/msg"):play()          -- a client-bundled notification blip
hafen.sound():get("sfx/msg"):play(0.2)       -- the same blip, quietly
```

---

## Read

| Method | Returns | Permission | Description |
|---|---|---|---|
| `hafen.sound():get(name)` | `Sound` | Unprotected | The Sound for that resource name. |
| `hafen.sound():sounding(filter)` | collection | Unprotected | Your addon's still-playing Sounds. Empty when none. |
| `hafen.sound():sounding():count(filter)` | `number` | Unprotected | How many are still sounding. |
| `hafen.sound():sounding():find(filter)` | `Sound \| nil` | Unprotected | The first still-sounding one that matches. |
| `sound:res()` | `string` | Unprotected | The resource name this Sound addresses. |
| `sound:playing()` | `boolean` | Unprotected | Whether a clip of this name is still sounding, or still starting. |
| `sound:info()` | `table` | Unprotected | `{ res, playing }`. |

| Rule | Detail |
|---|---|
| One object per name | `hafen.sound():get("sfx/msg") == hafen.sound():get("sfx/msg")`: stash one, use it as a table key. The name is trimmed, so `:get(" sfx/msg ")` is the same object. A name of only spaces is refused as empty. |
| A Sound is the name | No `:exists()`: a name has no lifetime. A name that does not resolve is silent: the client logs a line and Lua sees no error. Resources resolve off the UI thread, so a not-yet-loaded one never throws. |
| Two halves, two sets | `:get(name)` reaches any clip the game owns, since sound resources are not enumerable and a Sound exists on demand. `:sounding()` is your clips in the air. The client's own blips share the channel but are not yours. `hafen.sound()` itself does not enumerate: asking raises naming both halves. It prunes as you ask, so the count falls to zero as clips end. |
| `filter` | A string [filter](conventions.md#the-filter-argument) matches the resource name. No `:add` (playing is `sound:play(volume)`), no `:remove` (silencing is `sound:stop()`). |

```lua
local live = hafen.sound():sounding():list()
for index = 1, #live do live[index]:stop() end   -- silence everything this addon started
```

## Play and stop (unprotected)

| Method | Returns | Permission | Description |
|---|---|---|---|
| `sound:play(volume)` | the Sound | Unprotected | Play the clip once. `volume` is `0..1`, default `1`, a fraction of the clip's own base loudness. Reads the resource's current `audio2` layer, so a [layer write](resource/writes.md) changes what plays. |
| `sound:stop()` | the Sound | Unprotected | Cut every clip of this name your addon has in the air, a play not yet started included. |

```lua
local bell = hafen.sound():get("sfx/hud/mmap/bell3")
bell:play(0.6)
if bell:playing() then bell:stop() end    -- cut it mid-clip
```

| Rule | Detail |
|---|---|
| No permission | Client-local. Nothing is sent. |
| `volume` | Not a number, or outside `0..1`, raises rather than clamping. An argument of the play, not state on the Sound: Sounds are shared, so a stored volume would leak between uses. |
| 64 clips at once | Sounding or still loading, per addon. A `:play()` past that raises rather than queueing. A loop that reaches it plays one sound many times over. A clip whose resource never resolves gives up after 30 seconds and stops counting. |
| Heard whichever character is on screen | The client silences characters you are not looking at. Your addon is not one of them, so its sound carries whichever character holds the screen. Nothing plays before you have logged in. |
| `:play()` returns before the clip starts | The resource resolves off the UI thread. `:stop()` accounts for that, so `sound:play():stop()` makes no sound. |
| Teardown | Anything left playing is silenced when your addon is disabled or reloaded. |

## There is no hafen.music

`hafen.music` is absent: indexing it reads `nil`. The client carries a MIDI background-music player driven only by a `"bgm"` message from the server, which this server never sends. No resource in the cache carries a MIDI layer. What you hear as music is an ambient loop published by the world resources around you, on the same mechanism as the crickets. The ambient volume in Options ▸ Game ▸ Audio governs it. It is a scene node with a lifetime, not a clip handle.

---

## See Also

- [`hafen.client():options():audio()`](client/README.md) — master, UI, event and ambient volumes.
- [`hafen.asset`](asset/README.md) — the files your addon ships, as opposed to the client's resources.
- [Conventions](conventions.md#collections-the-noun-is-the-kind-the-verb-is-how-many) — the collection shape this shares.
