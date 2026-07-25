# hafen.sound / hafen.music — audio

Play client sound effects and background music by resource name.

| Function | Returns | Description |
|---|---|---|
| `hafen.sound.play(resname)` | — | play a one-shot sound effect |
| `hafen.music.play(resname [, loop])` | — | play background music; `loop` defaults to `false`. A `nil`/empty `resname` stops playback |

```lua
hafen.sound.play("sfx/msg")          -- a client-bundled notification blip
hafen.music.play("music/theme", true)
hafen.music.play(nil)                -- stop music
```

Resources resolve safely — a not-yet-loaded resource never errors into Lua. Client-bundled effect
names (e.g. `"sfx/msg"`, `"sfx/error"`) resolve locally; music streams from content.
