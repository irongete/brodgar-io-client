# 024-audio-oop — Plan

## Approach

The migration is the D-056/D-045 mechanism verbatim (`021-actionbar-oop` is the closest prior art):
a **callable table** on `hafen`, entities as **userdata + a per-addon metatable**, interned in a
per-`Addon` weak-valued cache with a drained `ReferenceQueue` — here keyed by the **resource name
string** (like `023`'s res-name key, but with no catalogue behind it: sound resources are not
enumerable, so a Sound simply exists on demand — and, having no lifetime, carries no `:exists()`).
What the handle changes, mechanically:

- **Play.** `AddonManager.playSound(name)` already does the right thing — `Resource.local().load` +
  `glob.loader.defer` so a cold resource never throws `Loading` into Lua. It becomes a Sound method
  that (a) takes a volume, wrapping the stream in `Audio.VolAdjust` when it is not `1.0`, and
  (b) **registers the resulting `Audio.CS` on the Sound** before handing it to `UI.sfx`.
- **Stop / playing.** Both are public engine surface, no core edit: `ui.audio.aui.remove(cs)` stops
  ([`ActAudio.RootChannel.remove`](src/haven/ActAudio.java:158) → `Mixer.stop`) and
  `ui.audio.aui.mixer().playing(cs)` tests ([`Audio.Mixer.playing`](src/haven/Audio.java:125)).
  A finished clip is dropped from the mixer lazily by `Mixer.get`, so `playing()` is also how the
  Sound prunes its own CS list — there is no end-of-clip callback and none is needed.
- **`hafen.sound()`** = the addon's Sounds that still have a live CS, through that same prune; fed
  by a per-`Addon` registry of Sounds with pending or live clips. **Teardown**: disable/`:reload`
  walks it and stops everything, like the addon's widgets and timers.
- **Music** is a separate subsystem, not a mixer client: `Music.Player` is a `HackThread` running a
  `javax.sound.midi` `Sequencer` into a `Synthesizer` it opens itself
  ([Music.java:45](src/haven/Music.java:45)) — nothing reaches `Audio`/`ActAudio`, so there is no
  `CS` to wrap and the Audio panel misses it. Play/stop stay `Music.play(Indir, loop)` /
  `Music.play(null, false)` (no defer — it resolves on its own thread).
- **Music volume ⇒ one `// addon:` block in `haven/Music.java`** (D-011): a static level +
  `volume(double)` applied to the live `Player`'s synth and re-applied in `Player.run` after
  `synth.open()`, as the **Universal SysEx master volume** (`F0 7F 7F 04 01 lsb msb F7` to
  `synth.getReceiver()`), *not* per-channel CC7 — sequences carry their own CC7 and stomp it.
  The same block exposes the player's state (`playing()` + the current `Indir<Resource>`, whose
  name is public on [`Resource.Named`](src/haven/Resource.java:66) — no `.get()`, no `Loading`),
  so `hafen.music()` interns the Track for whatever is really playing.

`hafen.sound.play` / `hafen.music.play` are deleted outright (D-013 hard cut); indexing the
namespace reads as plain `nil`.

## Files to create / modify

- **create** `src/io/brodgar/addon/LuaSound.java` — the Sound userdata: intern cache, CS registry,
  `:res/:play/:stop/:playing/:info`, per-`Addon` teardown hook.
- **create** `src/io/brodgar/addon/LuaMusic.java` — the Track userdata over the `Music` statics.
- **modify** `src/haven/Music.java` — the one `// addon:` block: master-volume level + `volume()`,
  re-applied in `Player.run`, and the two state readers `hafen.music()` needs.
- **modify** `src/io/brodgar/addon/WorldApi.java` — `installSound`/`installMusic` become the two
  callable namespaces (or move out entirely if the file's audio share stops paying rent).
- **modify** `src/io/brodgar/addon/AddonManager.java` — `playSound` folds into `LuaSound` (no other
  caller); wire the disable/reload sweep next to the existing per-addon teardown.
- **modify** `addons/hello/main.lua` — 3 existing `hafen.sound.play` call sites + the new exercise.
- **modify** `docs/addons/api/audio.md` (rewrite), `api/README.md`, `docs/addons/README.md` (glance
  row), `api/ghost.md:86` + `api/client.md:97` (they name `hafen.sound.play`).
- **modify** `specs/codebase/services.md` — the audio rows (coverage toll: `Mixer.stop/playing`,
  `RootChannel.remove/mixer()`, the lazy-drain rule, `Music` = MIDI + the new seam).
- **modify** `specs/addons/decisions/architecture-api.md` — **D-058** (a singleton's `hafen.x()`
  answers the one entity or `nil`), **D-059** (playback parameters are call arguments, not entity
  state) and **D-060** (`:exists()` is for entities with a lifetime).
- **modify** `specs/addons/learnings/engine-lifecycle.md` — the mixer's lazy drain, the
  stop-before-resolve race, and whatever the MIDI work teaches.

## Risks & gotchas

- **Stop-before-resolve.** `:play()` returns before the loader has produced the CS, so a `:stop()`
  in between must cancel the *pending* play, not just the live clips — a per-Sound pending counter
  or generation stamp checked inside the deferred task. Without it, `:play():stop()` still blips.
- **Two threads on the CS list**: appended from a **loader thread**, read/cleared from the **UI
  thread** (`learnings/threading.md`). Synchronize on the Sound; never resolve or call `sfx` while
  holding a Lua-side lock.
- **`ui.audio` is null before the UI exists** (`AudioOptions` guards this way) — answer `nil`/no-op,
  never throw. **`Resource.local()` vs `remote()`** is not cosmetic: local = the client jar
  (bundled sfx), remote = the server pool (music), `learnings/ghosts.md`; each keeps its own pool.
- **The MIDI synth is not always there.** `Player.run` returns early on `MidiUnavailableException`
  / bad data / the soft-synth's "No line matching" — `player != null` does not prove sound is
  coming out, and `volume()` must no-op on a null or closed synth.
- **Music races itself.** `Music.play` interrupts the old player and the new one `join`s it; the
  player nulls itself out on exit under `synchronized(Music.class)`. Use that same monitor, and
  re-apply volume in `run` (not only in `volume()`), or a level set between tracks is lost.
- **`Music.enabled`** (the `bgmen` pref) is checked by the *callers*, not by `Music.play` — an
  addon can start music while the user has it off. Respect it: refuse the play.
- The engine's own blips (chat, errors) live in the same `aui` channel and are **not** ours to
  enumerate or stop — `hafen.sound()` is scoped to the addon's clips.

## Discarded alternatives

- **`:exists()` on a Sound/Track.** Dropped by the maintainer: elsewhere it answers a *staleness*
  question, which a resource name cannot have. It would be a typo check that cannot answer
  synchronously — my first draft had it returning `false` before the resolve landed.
- **Volume as entity state (`:volume(v)` chainable).** Rejected by the maintainer: the Sound is
  interned and shared, so a stored volume leaks between unrelated uses of the same clip.
- **One `hafen.audio(name)` section for both.** The paths (mixer vs MIDI thread, local vs remote
  pool, one-shot vs looping) differ in every respect; one entity would hide which is which.
- **`hafen.music()` as a 0-or-1 array**, for D-057 symmetry. `hafen.music()[1]` reads badly and
  makes the common "what is playing" question indirect.
- **Auto-stop only on `:reload`, not on disable.** Rejected: a disabled addon making noise is a bug.
- **Per-channel CC7 for music volume** — simpler, but sequences carry their own CC7 and overwrite
  it partway through a song. **Music volume without a core edit** — impossible: the synth is
  private to `Music.Player` and never passes through `Audio`.
- **An addon-side record of "what music is playing"** — the core edit answers honestly instead; a
  record would be blind to client- and server-started music.
