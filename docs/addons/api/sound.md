# hafen.sound: Sound Effects

Play client sound effects, trigger audio notifications, and manage playing clips.

## Quick Example

```lua
-- Play a built-in game notification sound
local alert_sound = hafen.sound():get("sfx/msg")
alert_sound:play(0.5) -- Volume level: 0.0 (silent) to 1.0 (full)

-- Check if sound is actively playing, then stop
if alert_sound:playing() then
  alert_sound:stop()
end
```

---

## Methods on `hafen.sound()`

| Method | Parameters | Returns | Description |
|---|---|---|---|
| `:get(resource_name)` | `string` | `Sound` | Retrieves a sound effect handle for `resource_name` (e.g. `"sfx/msg"`, `"sfx/error"`). |
| `:sounding()` | None | `SoundCollection` | Collection of sounds currently being played by your addon. |

---

## Methods on `Sound`

| Method | Parameters | Returns | Description |
|---|---|---|---|
| `:play([volume])` | `[number]` | `self` | Plays the sound clip once. `volume` is a float between `0.0` and `1.0` (default `1.0`), a fraction of the clip's own base loudness. Reads the resource's current `audio2` layer — a [layer write](resource/writes.md) changes what plays. |
| `:stop()` | None | `self` | Stops all active playback instances of this sound started by your addon. |
| `:playing()` | None | `boolean` | Returns `true` if this sound is currently playing audio. |
| `:res()` | None | `string` | The resource identifier string. |

> All sounds started by your addon are automatically silenced if the addon is reloaded or disabled.
