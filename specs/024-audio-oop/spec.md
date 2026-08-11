# 024-audio-oop — Spec

> **Revised 2026-08-01, mid-024.3 (maintainer): `hafen.music` is CUT** — from this spec and from the
> client — **and the feature is `hafen.sound` alone.** 024.3 was built and then removed. The reason is
> not design but *content*: `haven.Music` is a MIDI player driven by exactly one thing, `RootWidget`'s
> `"bgm"` server message ([RootWidget.java:134](src/haven/RootWidget.java:134)), and **this server
> never sends it** — 132,777 files of resource cache hold **zero** `midi` layers against 36 `audio`
> ones, all sfx. What players hear as "music" is an [`ActAudio.Ambience`](src/haven/ActAudio.java:248)
> loop on the **`amb`** channel — published by world resources ([AudioSprite](src/haven/AudioSprite.java:59),
> [StaticSprite](src/haven/StaticSprite.java:75), [RenderLink](src/haven/RenderLink.java:106)),
> governed by Options ▸ Audio ▸ "Ambient volume" ([OptWnd.java:421](src/haven/OptWnd.java:421)), and
> the same mechanism as the crickets. That is a render-tree node with a lifetime, not a clip handle,
> and stays out of scope below. **An API over a subsystem with no content answers `nil` forever**, so
> the Track section, its `// addon:` volume seam in `haven/Music.java` (reverted — the file is pristine
> again) and D-058 are all gone. `haven.Music` is left entirely alone.

## What & why

Audio is the smallest surviving flat namespace: `hafen.sound.play(res)` — a fire-and-forget function
that hands back nothing, so an addon can never ask *is it still playing*, *stop it*, or *play it
quieter*. The ROADMAP's OOP migration reaches it now that Gob/Kin/Actionbar/MenuGrid have set the
pattern: it becomes a D-056 callable namespace — **`hafen.sound(resName)` = an interned Sound** — the
flat table is hard-cut (D-013), and the handle ships what the flat form structurally could not:
`:stop()`, `:playing()`, volume, and "what is playing right now". Playback stays **ungated**:
client-local, sends nothing to the server, ungated today.

Shape:
- `hafen.sound(name)` → Sound: `:res() :play([volume]) :stop() :playing() :info()`, chaining on
  self. **Volume is an argument of the play call**, not entity state — the Sound is interned and
  shared, so a stored volume would leak between unrelated uses of the same clip (maintainer,
  2026-08-01). Omitted ⇒ `1.0`; outside `0..1` errors naming the method (`AudioOptions`'
  convention). **No `:exists()`** (maintainer, 2026-08-01): the other sections carry it because
  their entities have a *lifetime* and a held handle goes stale; a resource name has none.
- `hafen.sound()` → the array of **this addon's** still-playing Sounds (the engine's own blips are
  not ours to enumerate): the sweep — stop them all, auto-stopped on disable/`:reload`.
- **There is no music section.** Sfx keep their own path (`Resource.local()` → `UI.sfx` → the `aui`
  mixer), and that path is the whole feature — see the revision note at the top.

## Acceptance criteria

- [ ] `:lua hafen.sound("sfx/msg"):play()` blips; `hafen.sound.play` is `nil` (hard cut), and so is
      the whole of `hafen.music` — the namespace is absent, not merely flattened.
- [ ] `:res()` prints the name; a bogus name `:play()`s silently (no Lua error, no crash);
      `hafen.sound("sfx/msg") == hafen.sound("sfx/msg")` (interned, D-045).
- [ ] A long clip: `:play()` then `:playing()` → `true`, `:stop()` → `false` and audibly cut.
- [ ] `:play(0.2)` is audibly quieter than `:play()`; `:play(2)` errors naming the method.
- [ ] `#hafen.sound()` counts the addon's own live clips and drops back to 0 when they end;
      disabling the addon (AddOns panel) or `:reload` silences anything it left playing.
- [ ] `sound:info()` gives a flat snapshot.
- [ ] `hello` exercises all of it each login (ping via the new form + a printed contract check
      that the flat table is gone); full regression still passes.
- [ ] `ant hafen-client` → `BUILD SUCCESSFUL`.

## Out of scope

- **All background/ambient music** — see the revision note: `haven.Music` (MIDI) has no content on
  this server, and what does play is `Ambience`, which is the next bullet.
- **Positional / world-anchored sfx** (`ActAudio.PosClip`, `Ambience`) — a render-tree node with a
  lifetime, not a clip handle; its own feature if wanted, and the one that would actually govern what
  players hear as music. **Per-channel routing** (`pos`/`amb` instead of `aui`) — addon sound stays
  UI sound.
- **Volume/latency settings** — already `hafen.client:options():audio()` (018). **Looping sfx**
  (`Audio.Repeater`) — music loops; one-shot effects do not.
- Enumerating the client's own sounds, or a catalogue of sound resources (resources are not
  enumerable — Sounds exist on demand, like MenuGrid keys but with no discovery list).
- **Validating a resource name** (`:exists()`, or a play callback reporting the resolve). If an
  addon must warn about a user-configured name, that wants an honest async answer, not a probe.

## Context files

- `specs/design/06-lua-api.md` — the design owning `hafen.sound`/`hafen.music` ·
  `specs/codebase/services.md` — the audio rows; this feature extends them (coverage toll)
- `src/haven/Audio.java` — `CS`/`Clip`, `Mixer.add/stop/playing/current`, `VolAdjust`, `fromres`
- `src/haven/UI.java` (`sfx`) · `src/haven/ActAudio.java` (`Root.aui`, `RootChannel`; `Ambience` +
  `Root.amb` = what "music" really is here, and why there is no music section)
- `src/io/brodgar/addon/WorldApi.java` — `installSound`/`installMusic`, the flat forms being cut ·
  `AddonManager.java` — `playSound`, the `installHafen` wiring, per-addon disable/reload teardown
- `src/io/brodgar/addon/LuaSlot.java` + `021-actionbar-oop/` — closest prior art (callable
  namespace, interned entity, `:info()`, hard cut); `023-menugrid-oop/` — string-keyed entity
- `docs/addons/api/audio.md` — the page rewritten, sound-only (+ `api/README.md`, `README.md` glance
  row) · `addons/hello/main.lua` — the harness (3 existing `hafen.sound.play` call sites)
