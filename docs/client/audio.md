# Audio: playing a sound, and stopping one

> The client's sound: the three channels, how a clip is played and stopped, per-clip volume, ambient
> sound, and why the mixer's list drains only when you ask it something. The Audio panel's own settings
> are on [prefs-and-options.md](prefs-and-options.md).

## Where it lives

| What | Where |
|---|---|
| The three channels | `ActAudio.Root` holds `aui` (interface blips), `pos` (positional world sound) and `amb` (ambience), each a `RootChannel` over its own `Audio.Mixer`. One `Root` per `UI`, built in the `UI` constructor and reachable as `UI.audio` |
| Playing a clip | `UI.sfx(Audio.CS)` — it is `audio.aui.add(clip)` and nothing else. The two overloads take an `Audio.Clip` (`clip.stream()`) and a `Resource`. `Audio.fromres(Resource)` is the resource → `CS` conversion |
| The clip a resource plays | `Audio.resclip(Resource)`: the one `Audio.Clip` (an `audio2` layer) when the resource carries one, else a synthetic `Clip` picking among them at random, **cached per resource in `Audio.resclips`** for the pool's lifetime. `Audio.forget(Resource)` drops that entry after the layer list is swapped. The layer's own `bvol` (`vol` in the `audio2` v3 key block) is applied by `ActAudio.Ambience`/`ClipAmbiance` only — `UI.sfx` and `fromres` play the clip at full gain |
| The master volume | `Audio.Root.volume()` / `volume(double)`, the number the Audio panel's slider writes |
| Per-clip volume | Wrap the `CS` in `Audio.VolAdjust(cs, vol)`. Its `vol` and `bal` are **public mutable fields**, so it adjusts a clip that is already playing, and `UI.sfx` takes any `Audio.CS`, so the wrapper goes in transparently |
| Stopping one | `ActAudio.RootChannel.remove(cs)` → `Mixer.stop`, an **identity match on the very `CS` you added**. Keep the object you passed or you cannot stop it |
| Is it still playing | `RootChannel.mixer()` → `Audio.Mixer.playing(cs)`, beside `size`, `current` and `clear` |
| Ambient sound (what people call the music) | `ActAudio.Ambience`, a `RenderTree.Node` carrying an `"amb"` `Audio.clip` layer. The sound is made by its static `Glob` — one per resource, a looping `Audio.Repeater` on the `amb` channel, its volume fading with how many slots are in view. Published by world resources through `AudioSprite`, `StaticSprite` and `RenderLink`. It is a **lifetime-bound scene node, not a clip handle** |
| Music (MIDI) — **dead on this server** | `Music` is `javax.sound.midi` and sits entirely outside `Audio`/`ActAudio`; the Audio panel does not touch it. `Music.play` has exactly one caller, `RootWidget`'s `"bgm"` uimsg, which this server never sends. Do not build on it |
| Positional sound: where a clip is, and how it is panned | `ActAudio.PosClip`, a `RenderTree.Node` that is also a `TickList.Ticking`: added under a slot, it puts its `VolAdjust` on the `pos` channel and, every tick, reads the slot's position through `ActAudio.spos(Pipe)` — the slot's `Homo3D.loc` chain then the camera's `Homo3D.cam`, so the point comes out in **eye space**. Volume is `min(1, 50 / hypot(x, y))` in eye-space units; balance is `atan2(pos.x, -pos.z) / (π/8)` clipped to `-1..1` — the horizontal azimuth from the camera's facing, `0` ahead, `+` to the right, full pan at 22.5°. ⚠️ It is the ONLY panning the client has, and it is per slot on the render tree: a sound with no scene node (a `UI.sfx` clip) is never panned. Anything else that pans by the camera copies this formula |
| Muting one session (**fork**) | `ActAudio.RootChannel.mute(boolean)` writes the channel's `muted` flag and its live `VolAdjust`; `ActAudio.Root.mute` does all three channels at once. It is `synchronized` against `mixer()` for a real race — a first clip built while `muted` was stale sets itself audible, and every later `mute()` returns early on `m == muted`, so a background session that had made no sound yet stays audible for the rest of its life |

## Instruments: the keyboard, and what the server plays back

The instrument in a character's hands is played through published resource code, `ui/music` (v35), and
heard through overlays the server puts on the musician's gob.

| What | Where |
|---|---|
| The keyboard | `MusicWnd`, a `Window` the server opens with `mkwidget(name, maxpoly)`; it `grabkeys` while up. A press sends `wdgmsg("play", key, t)` and a release `wdgmsg("stop", key, t)`: `key` is `0..35` — three octaves, C3 to B5 — and `t` is seconds since the window's construction plus `latcomp` (`0.15`), sent as a `float`. `maxpoly` is the polyphony the server allows this instrument; past it the widget releases its oldest note before striking |
| What comes back | Every note is relayed to everyone in earshot as an overlay on the musician's gob. `Decoder` reads a `key` — `255` is the `MusicOverlay` base, anything else a note — then `bid`, `ns` and `ne`, `float32` seconds on the base's clock. The instrument's own resource subclasses `Decoder` and hands `NoteOverlay` its clip |
| Scheduling | `MusicOverlay.get` mixes each `NoteOverlay` in sample-accurately at `ns` against its own `start` — and **re-bases `start`** when a note's `ns` is more than a second ahead of it or already past, so a note dated badly plays now rather than never. What a sender times is the timestamps, not the moment it sends |
| The sound of a key | `NoteOverlay.tuneclip`: the instrument's sample, re-pitched by `Audio.Resampler` at `2^((key - basekey) / 12)`. A key two octaves above the sample plays it four times faster, and so four times **shorter**. A `rep` audio layer loops until `stop` (a sustained instrument); without one the `cl` clip plays once and decays on its own, and holding the key sustains nothing |
| The end of a note | `NoteOverlay.setvol` fades over `dt = 0.1` s past `ne`; there is no other release envelope |

## Gotchas

- **The keyboard shows one octave and the wire takes three.** The published `MusicWnd` draws twelve keys,
  `ZSXDCVGBHNJM`, and reaches the other twenty-four with Shift (up) and Ctrl (down); the server's `key`
  is `0..35` whichever way it was reached.
- **`t` is the one `float` the client sends the server.** `Message` writes a `Float` as `T_FLOAT32` and a
  `Double` as `T_FLOAT64`; the server plays a note dated with either.
- **A finished clip is dropped LAZILY, by the mixer thread.** `Audio.Mixer.get` removes a `CS` the moment
  its `get()` returns `< 0`, and that is the *only* end-of-clip signal: there is no callback and no
  `CS.done()`. So `Mixer.playing(cs)` is how you find out a clip has ended, and asking is also what drains
  the list — a caller that never asks holds a list that never shrinks.
- **`UI.msg(String)` blips, and `null` is not "silent".** It builds an `InfoMessage`, whose `defsfx` is
  `sfx/msg`, and both notice handlers (`GameUI.msg` in the world, `RootWidget.msg` on the login screen)
  play the notice's clip through `ui.sfxrl` — so a line posted the convenient way chimes like a server
  notice, with no `GameUI` up too. A `null` clip on a `SimpleMessage` means "use the default", so a silent
  line is `msg(text, color, UI.SimpleMessage.nosfx)`: the sentinel is the only way to say none. Mistake
  the chime for your own clip and you will debug a non-bug.
- **A name that resolves nowhere is silent, not an error.** A clip is a resource like any other, so it
  arrives through `Loading` and a caller that resolves it inline blocks or throws; the loader answers
  later, and a bad name simply never arrives.

## What is not mapped

The mixer's own threading and its backend (`javax.sound.sampled`), how a `Clip` is decoded, and the
Audio panel's controls — those are on [prefs-and-options.md](prefs-and-options.md).

## See also

- [services](services.md) — keybindings, resources, and the rest of the cross-cutting map
- [preferences and the Options window](prefs-and-options.md) — where the volume settings are written
- [the 3D world](world-3d.md) — the scene the ambient nodes hang in
