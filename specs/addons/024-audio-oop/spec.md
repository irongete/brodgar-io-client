# 024-audio-oop — Spec

## What & why

Audio is the smallest surviving flat namespace: `hafen.sound.play(res)` and
`hafen.music.play(res, loop)` — two fire-and-forget functions that hand back nothing, so an addon
can never ask *is it still playing*, *stop it*, or *play it quieter*. The ROADMAP's OOP migration
reaches it now that Gob/Kin/Actionbar/MenuGrid have set the pattern: both become D-056 callable
namespaces — **`hafen.sound(resName)` = an interned Sound**, **`hafen.music(resName)` = an interned
Track** — the flat tables are hard-cut (D-013), and the handle ships what the flat form
structurally could not: `:stop()`, `:playing()`, volume, and "what is playing right now". Playback
stays **ungated**: client-local, sends nothing to the server, ungated today.

Shape:
- `hafen.sound(name)` → Sound: `:res() :play([volume]) :stop() :playing() :info()`, chaining on
  self. **Volume is an argument of the play call**, not entity state — the Sound is interned and
  shared, so a stored volume would leak between unrelated uses of the same clip (maintainer,
  2026-08-01). Omitted ⇒ `1.0`; outside `0..1` errors naming the method (`AudioOptions`'
  convention). **No `:exists()`** (maintainer, 2026-08-01): the other sections carry it because
  their entities have a *lifetime* and a held handle goes stale; a resource name has none.
- `hafen.sound()` → the array of **this addon's** still-playing Sounds (the engine's own blips are
  not ours to enumerate): the sweep — stop them all, auto-stopped on disable/`:reload`.
- `hafen.music(name)` → Track: `:res() :play([volume], [loop]) :stop() :playing() :volume(v)
  :info()`. **Volume is `:play`'s first argument in both sections** — one rule; music's loop
  follows it. Music is long-lived, so `:volume(v)` also adjusts it live **on the playing Track**;
  on any other Track it errors, because that is player state, not entity state.
- `hafen.music()` → the Track playing, or `nil` — a **singleton** player, so the collection form
  degenerates to one-or-nothing rather than an array (the one departure from D-056/D-057's array
  shape; it needs a decision entry).
- **Music is MIDI, not mixed audio** (`javax.sound.midi` on its own thread — it never touches
  `Audio.Root`/`ActAudio`, so the Audio panel's volumes miss it and no `Audio.VolAdjust` can
  reach it). Volume therefore needs a small centralized **`// addon:` edit to `haven/Music.java`**
  (D-011). The same edit exposes the player's real state, so `hafen.music()` is honest about
  **client- and server-started music too**. Sfx keep their own path (`Resource.local()` →
  `UI.sfx` → the `aui` mixer).

## Acceptance criteria

- [ ] `:lua hafen.sound("sfx/msg"):play()` blips; `hafen.sound.play` is `nil` (hard cut) and
      `hafen.music.play` is `nil`.
- [ ] `:res()` prints the name; a bogus name `:play()`s silently (no Lua error, no crash);
      `hafen.sound("sfx/msg") == hafen.sound("sfx/msg")` (interned, D-045).
- [ ] A long clip: `:play()` then `:playing()` → `true`, `:stop()` → `false` and audibly cut.
- [ ] `:play(0.2)` is audibly quieter than `:play()`; `:play(2)` errors naming the method.
- [ ] `#hafen.sound()` counts the addon's own live clips and drops back to 0 when they end;
      disabling the addon (AddOns panel) or `:reload` silences anything it left playing.
- [ ] `hafen.music("music/…"):play(1, true)` loops; `hafen.music()` returns that same Track object
      (`==`), `hafen.music():stop()` stops it, and `hafen.music()` is then `nil`.
- [ ] `:play(0.3, true)` is audibly quieter, and `hafen.music():volume(1)` raises it **without
      restarting** the track; `:volume()` on a Track that is not playing errors.
- [ ] Music the client/server starts itself (`:bgm <res>`) also shows up as `hafen.music()` — the
      state is the player's, not a record of addon calls. `:info()` gives a flat snapshot.
- [ ] `hello` exercises all of it each login (ping via the new form + a printed contract check
      that the flat tables are gone); full regression still passes.
- [ ] `ant hafen-client` → `BUILD SUCCESSFUL`.

## Out of scope

- **Positional / world-anchored sfx** (`ActAudio.PosClip`, `Ambience`) — a render-tree node with a
  lifetime, not a clip handle; its own feature if wanted. **Per-channel routing** (`pos`/`amb`
  instead of `aui`) — addon sound stays UI sound.
- **Volume/latency settings** — already `hafen.client:options():audio()` (018). **Looping sfx**
  (`Audio.Repeater`) — music loops; one-shot effects do not.
- Enumerating the client's own sounds, or a catalogue of sound resources (resources are not
  enumerable — Sounds exist on demand, like MenuGrid keys but with no discovery list).
- **Validating a resource name** (`:exists()`, or a play callback reporting the resolve). If an
  addon must warn about a user-configured name, that wants an honest async answer, not a probe.

## Context files

- `specs/addons/design/06-lua-api.md` — the design owning `hafen.sound`/`hafen.music` ·
  `specs/codebase/services.md` — the audio rows; this feature extends them (coverage toll)
- `src/haven/Audio.java` — `CS`/`Clip`, `Mixer.add/stop/playing/current`, `VolAdjust`, `fromres`
- `src/haven/UI.java` (`sfx`) · `src/haven/ActAudio.java` (`Root.aui`, `RootChannel`) ·
  `src/haven/Music.java` (MIDI `Player`, `play(Indir, loop)` — the file the `// addon:` edit lands in)
- `src/io/brodgar/addon/WorldApi.java` — `installSound`/`installMusic`, the flat form being cut ·
  `AddonManager.java` — `playSound`, the `installHafen` wiring, per-addon disable/reload teardown
- `src/io/brodgar/addon/LuaSlot.java` + `021-actionbar-oop/` — closest prior art (callable
  namespace, interned entity, `:info()`, hard cut); `023-menugrid-oop/` — string-keyed entity
- `docs/addons/api/audio.md` — the page rewritten (+ `api/README.md`, `README.md` glance row) ·
  `addons/hello/main.lua` — the harness (3 existing `hafen.sound.play` call sites)
