# 024-audio-oop — Tasks

- [x] 024.1 — **LuaSound: the entity and the play verb.** `hafen.sound(name)` → an interned Sound
      (userdata + per-addon metatable + weak intern cache, the D-045 mechanism from `LuaSlot`),
      with `:res()`, `:play([volume])` → self (the folded
      `AddonManager.playSound` defer + `Audio.VolAdjust` when volume ≠ 1, volume outside `0..1`
      errors naming the method) and `:info()`. **Hard-cut `hafen.sound.play`**; fix `hello`'s three
      existing call sites to the new form so the harness still loads (the full exercise is 024.3).
      Verify in-game: the login ping still blips, `hafen.sound("sfx/msg") == hafen.sound("sfx/msg")`,
      `hafen.sound.play` is `nil`, `:play(0.2)` is quieter, a bogus name is silent.

- [ ] 024.2 — **Stop, playing, the live set and teardown.** Register each started `Audio.CS` on its
      Sound; `:stop()` → self (`ui.audio.aui.remove` + cancel a play still pending in the loader),
      `:playing()` (`aui.mixer().playing`, pruning drained clips), `hafen.sound()` → the array of
      the addon's still-playing Sounds, and the disable/`:reload` sweep that silences them all.
      Verify in-game with a long clip: `:playing()` flips true→false, `:stop()` cuts it audibly,
      `#hafen.sound()` counts and drains, `:play():stop()` never blips, and disabling the addon
      from the AddOns panel silences it mid-clip.
      <!-- extra context: `specs/addons/learnings/threading.md` (loader vs UI thread on the CS
           list); the per-addon teardown path in `AddonManager` (widgets/timers already do this) -->

- [ ] 024.3 — **Music: the Track, its volume, and the one core edit.** The `// addon:` block in
      `haven/Music.java` — a master-volume level applied to the live synth as Universal SysEx and
      re-applied in `Player.run`, plus the two state readers — then `hafen.music(name)` → an
      interned Track (`:res/:play([volume],[loop])/:stop/:playing/:volume(v)/:info`) and
      `hafen.music()` → whatever is really playing (client- and server-started music included) or
      `nil`; `:volume()` on a Track that is not playing errors; refuse to play while the `bgmen`
      pref is off. Hard-cut `hafen.music.play`. **Rebuild + full client restart** (core edit).
      Verify in-game: plays/loops/stops, `:play(0.3,…)` is quieter, `hafen.music():volume(1)`
      raises it without restarting, `hafen.music()` catches the client's own login music.
      <!-- extra context: `src/haven/Music.java` in full (MIDI Sequencer/Synthesizer, the private
           Player and its exit race); `haven/Resource.java` `Named.name` -->

- [ ] 024.4 — **Docs, harness, decisions, tolls.** Rewrite `docs/addons/api/audio.md` (both
      sections, volume-first `:play`, the auto-silence rule, and the fact that music is MIDI and
      untouched by the Audio panel's volumes) + the `api/README.md` and `README.md` rows + the two
      stale `hafen.sound.play` references in `ghost.md`/`client.md`; extend `hello` to exercise the
      whole surface each login (ping via the new form, a printed contract check that both flat
      tables are gone, the live-set count); write **D-058** (singleton ⇒ entity-or-`nil`), **D-059**
      (playback parameters are call arguments, not entity state — and where a long-lived player
      makes a live setter honest) and **D-060** (`:exists()` belongs to entities with a lifetime,
      not to name-keyed handles) into `decisions/architecture-api.md`; pay the coverage toll on
      `specs/codebase/services.md`'s audio rows (the mixer's lazy drain, `Music` = MIDI + the new
      seam) and append the build's learnings. Verify: one login re-checks every prior feature.
