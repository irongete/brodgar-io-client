# 024-audio-oop — Tasks

- [x] 024.1 — **LuaSound: the entity and the play verb.** `hafen.sound(name)` → an interned Sound
      (userdata + per-addon metatable + weak intern cache, the D-045 mechanism from `LuaSlot`),
      with `:res()`, `:play([volume])` → self (the folded
      `AddonManager.playSound` defer + `Audio.VolAdjust` when volume ≠ 1, volume outside `0..1`
      errors naming the method) and `:info()`. **Hard-cut `hafen.sound.play`**; fix `hello`'s three
      existing call sites to the new form so the harness still loads (the full exercise is 024.3).
      Verify in-game: the login ping still blips, `hafen.sound("sfx/msg") == hafen.sound("sfx/msg")`,
      `hafen.sound.play` is `nil`, `:play(0.2)` is quieter, a bogus name is silent.

- [x] 024.2 — **Stop, playing, the live set and teardown.** Register each started `Audio.CS` on its
      Sound; `:stop()` → self (`ui.audio.aui.remove` + cancel a play still pending in the loader),
      `:playing()` (`aui.mixer().playing`, pruning drained clips), `hafen.sound()` → the array of
      the addon's still-playing Sounds, and the disable/`:reload` sweep that silences them all.
      Verify in-game with a long clip: `:playing()` flips true→false, `:stop()` cuts it audibly,
      `#hafen.sound()` counts and drains, `:play():stop()` never blips, and disabling the addon
      from the AddOns panel silences it mid-clip.
      <!-- extra context: `specs/addons/learnings/threading.md` (loader vs UI thread on the CS
           list); the per-addon teardown path in `AddonManager` (widgets/timers already do this) -->

- [x] 024.3 — **CUT: there is no music section.** Built as specified (Track userdata + the `// addon:`
      master-volume/state seam in `haven/Music.java`), then **removed whole at the maintainer's call,
      2026-08-01**, once in-game testing showed `haven.Music` never plays: it is MIDI, driven only by
      `RootWidget`'s `"bgm"` server message, which this server never sends (zero `midi` layers in
      132,777 cached resource files). The "music" players hear is `ActAudio.Ambience` on the `amb`
      channel — Options ▸ Audio ▸ "Ambient volume", the same path as the crickets — which the spec
      puts out of scope. `LuaMusic.java` deleted, `Addon.tracks` and the `hafen.music` wiring removed,
      `haven/Music.java` reverted to pristine. Reasoning + evidence: the revision note in `spec.md`.

- [ ] 024.4 — **Docs, harness, decisions, tolls.** Rewrite `docs/addons/api/audio.md` as a
      **sound-only** page (volume-first `:play`, the auto-silence rule) + the `api/README.md` and
      `README.md` rows + the two stale `hafen.sound.play` references in `ghost.md`/`client.md`; the
      page must also state plainly that **there is no `hafen.music`** and why (`haven.Music` = MIDI
      with no content on this server; ambient music is `ActAudio.Ambience` on the `amb` channel, its
      own feature if ever wanted). Extend `hello` to exercise the whole surface each login (ping via
      the new form, a printed contract check that `hafen.sound.play` AND `hafen.music` are both gone,
      the live-set count); write **D-059** (playback parameters are call arguments, not entity state)
      and **D-060** (`:exists()` belongs to entities with a lifetime, not to name-keyed handles) into
      `decisions/architecture-api.md` — the *singleton-player* decision is dropped with the music
      section and **D-058 is already taken** by 024.3's cut (verify a subsystem has content before
      designing an API over it). The `services.md` audio toll and 024.3's learnings were **paid at
      024.3's close**; only sound-specific learnings from 024.1/024.2 remain to append.
      Verify: one login re-checks every prior feature.
