# Preferences and the Options window

> Where a setting is written and read: the two disjoint stores behind it, the byte budget one of them
> has, and what each Options panel actually writes. Everything else cross-cutting is
> [services.md](services.md).

## The preference store

**Two disjoint stores.** Most settings are plain prefs — `Utils.getpref*`/`setpref*`
(`java.util.prefs`, string-keyed, written immediately). Graphics settings are **not**: they live in
`GSettings`, a render `State` value object.

| Store | Where |
|---|---|
| Preferences (base client only) | `Utils.getpref/setpref` and the typed pairs beside it (`getprefi`/`setprefi`, `…d`, `…b` for both a boolean and a `byte[]`, `…c` for a `Coord`); the node itself is `Utils.prefs()`, `-Dhaven.prefspec` naming it and `-Dhaven.prefs.<key>` overriding one from the command line into an in-memory `MapPrefs` |
| A LIST of strings in one pref | `Utils.getprefsl`/`setprefsl`. `setprefsl` frames the rows as **NUL-separated UTF-8** into one `byte[]` (each row's bytes then a `0`) and hands it to `setprefb` → `Preferences.putByteArray`; `getprefsl` splits the array back on those NULs. The framing is **not escaped**, so a row that itself contains a NUL comes back as two rows, and a row's own delimiters are the writer's problem, not the store's. **The budget for the whole list is 6144 bytes** — see the gotcha below |

## What the Options window writes

| Setting group | Backing |
|---|---|
| Panels (read these for the authoritative write) | `VideoPanel`, `AudioPanel`, `InterfacePanel`, `BindingPanel`, `CameraPanel` (fork) |
| Video | `GSettings` **named fields**, not constants: `lshadow`, `vsync`, `hz`/`bghz` (/), `rscale`, `lightmode`, `maxlights`. ⚠️ **`gprefs` is per `UI`**, exactly as the audio sub-mix below is: `UI`'s field initialiser runs `GSettings.load(true)` in every tree the loop builds, `UI.setgprefs` publishes into that tree alone, and `UI.tick` flushes the dirty one to the single shared `gconf/*` pref store. So a tree nobody published into keeps what it loaded at construction, and the last one to publish is what every tree reads at the next client start |
| UI scale | pref `uiscale` (restart to take effect) |
| Placement granularity | `MapView.plobpgran` / `plobagran`  statics + like-named prefs |
| Camera inversion | `MapView.invcamx` / `invcamy`  statics + like-named prefs; consumed by `Camera.invdx`/`invdy` |
| Camera choice | prefs `defcam`/`camargs`, written only by `MapView.setcam` ([camera.md](camera.md)); `CameraPanel.CamSelector` calls it. ⚠️ **A panel's constructor runs before it is in the tree** — `PButton.click` does `tgt.get()` and only then `add`s the result — so `ui` and `getparent(GameUI.class)` are both null there, and `PButton` caches the panel in `actual` and reuses it forever after. A control whose value the client can move behind its back (this one: `:cam` and the RTS mode both do) therefore re-reads in `show()`, which `chpanel` calls every time the panel is opened; construction is far too early and happens once |
| Audio master / buffer | `Audio.Root.volume()` (persists `sfxvol`), `bufsize()` (**in samples** @44100 Hz, persists `audiobuf`) |
| Audio channels | `ActAudio.Root` `.aui`/`.pos`/`.amb` → `RootChannel.setvolume` + public `volume` field. The three `AudioPanel` sliders map 1:1: "Interface volume"→`aui`, "In-game event volume"→`pos`, "Ambient volume"→`amb`. **There is no music slider** — `Music` is a separate MIDI player, see above. ⚠️ **A sub-mix level is per `ActAudio.Root`, and there is one of those per `UI`** (`UI`'s constructor does `new ActAudio.Root(audio)` over the one shared `Audio.Root` the loop hands every tree): `RootChannel`'s constructor reads `Utils.getpref("sfxvol-" + name, "1.0")`, so each starts at the saved level, but `setvolume` writes the pref **and only its own channel**. `OptWnd`'s slider is `ui.audio.aui.setvolume(...)` on the panel's own `UI`, so moving it leaves every other tree at the level it read when it was built, until the next client start. The **master** volume is not like this — `Audio.Root.volume()` is one `VolAdjust` on the shared mixer, so it reaches everything at once. ⚠️ **Every write to a `RootChannel` takes the channel's own monitor**: `setvolume` and `mute` are `synchronized`, which is the edge `mixer()`'s double-checked block needs — it builds `volc` and reads `muted` under that same monitor, so a channel first played after a `mute(true)` would otherwise read a stale `muted`, build itself audible and stay so for good, `mute` returning on `m == muted` from then on ([multi-session.md](multi-session.md) is what mutes them). The monitor is a **leaf**: nothing under it reaches a `UI`, a widget or the render tree. ⚠️ **`Root.clear` stops all three channels, and `UI.destroy` is its only caller** — a `RootChannel` that has ever played holds a `VolAdjust` on the shared `Audio.Root` mixer, and `clear()` is the only thing that takes it off, so one missed there goes on mixing for the rest of the process, a further one per login |

**Gotchas that cost time.**
- **`GSettings` is immutable.** `update()`  returns a **new** `GSettings`;
  nothing changes until you publish it with `UI.setgprefs`. Read via `ui.gprefs.<field>.val`.
  There are no `GSettings.SHADOWS`-style constants — the settings are instance fields with short wire names
  (`"sdw"`, `"rscale"`, `"lighting"`…).
- **`lightmode` is `simple` / `zoned`** (the `LightMode` enum), *not* "global".
- **`Utils.setpref*` catches only `SecurityException`.** `Preferences.put*` also throws
  `IllegalArgumentException` past `MAX_KEY_LENGTH` (80 chars) / `MAX_VALUE_LENGTH` (8192), and that escapes
  every `setpref*` as a raw Java error from whatever wrote it. A writer minting a key out of names it does
  not control has to bound the length itself.
- **The value cap is a Base64 cap, so `setprefb`/`setprefsl` get 6144 bytes, not 8192.**
  `Preferences.putByteArray` Base64s the array into the value, and refuses one longer than
  `MAX_VALUE_LENGTH * 3 / 4` — 6144 — with `IllegalArgumentException: Value too long: <the whole
  Base64>`. That is the real budget for a `setprefsl` list, **rows and their NUL separators together**,
  and it is a byte count over a string: one non-ASCII character in a row costs more than one byte of it.
  A writer whose rows are minted out of names it does not control has to encode the candidate list and
  measure it before writing, because the throw comes out of `setprefsl` and not out of the store.
- **A pref-only write is a no-op until restart** for anything mirrored in a static. `OptWnd` always writes
  both in one statement — `Utils.setprefb("invcamx", MapView.invcamx = val)` — and so must any other writer.
- **`plobagran` is a divisor, not degrees**: the panel displays `180 / plobagran`.

## See also

- [services](services.md) — keybindings, resources, audio, and the rest of the cross-cutting map
- [the camera](camera.md) — `MapView.setcam`, the one writer of the `defcam`/`camargs` prefs
- [several sessions at once](multi-session.md) — why a sub-mix level and a `GSettings` publish are
  per `UI` rather than per client
