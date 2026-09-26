# Preferences and the Options window

> Where a setting is written and read: the two disjoint stores behind it, the file one of them is, the
> byte budget it has, and what each Options panel actually writes. Everything else cross-cutting is
> [services.md](services.md).

## The preference store

**Two disjoint stores.** Most settings are plain prefs — `Utils.getpref*`/`setpref*`
(`java.util.prefs`, string-keyed, written immediately). Graphics settings are **not**: they live in
`GSettings`, a render `State` value object.

| Store | Where |
|---|---|
| Preferences (base client only) | `Utils.getpref/setpref` and the typed pairs beside it (`getprefi`/`setprefi`, `…d`, `…b` for both a boolean and a `byte[]`, `…c` for a `Coord`); the node itself is `Utils.prefs()`. **The node is the client's own file**, `savedata/client.sqlite` beside the jar, table `prefs` (`key TEXT PRIMARY KEY, value TEXT`), one row per preference. **The login secrets are the one exception** (`ClientDb.Prefs.secret`, by key prefix: `savedtoken-`, `lasttoken-`, `saved-tokens@`, `tokenname@`, `token-`): they never reach the file, and are read, written and removed on the node upstream opens — `Preferences.userNodeForPackage(Utils.class).node(prefspec)`, `HKCU\Software\JavaSoft\Prefs\haven\hafen` on Windows, `~/.java` elsewhere — so a copied or zipped client folder carries no token. That node is opened at the first secret touched (the login screen) and for nothing else; a row with a secret key already in the file is dropped at the load, and a platform node that fails holds the secrets in memory for the session, never in the file. The seam is the `else` branch of `Utils.prefs()` (fork), which installs the fork's `AbstractPreferences` root (`ClientDb.prefs()`) instead of the `Preferences.userNodeForPackage` node upstream opens; the `sysprefs()` branch above it still wins, so `-Dhaven.prefs=<file>` and `-Dhaven.prefs.<key>=<value>` install the in-memory `MapPrefs`. The node loads every row at its open and answers reads from memory, writing each change through at once — so a second client writing the same file is not seen until the next start. `-p`/`-Dhaven.prefspec` is parsed into `Utils.prefspec` (`Config.cmdline`, `HeadlessClient`, `Streamer`) and names the secrets' node alone |
| A LIST of strings in one pref | `Utils.getprefsl`/`setprefsl`. `setprefsl` frames the rows as **NUL-separated UTF-8** into one `byte[]` (each row's bytes then a `0`) and hands it to `setprefb` → `Preferences.putByteArray`; `getprefsl` splits the array back on those NULs. The framing is **not escaped**, so a row that itself contains a NUL comes back as two rows, and a row's own delimiters are the writer's problem, not the store's. **The budget for the whole list is 6144 bytes** — see the gotcha below |

## What the Options window writes

| Setting group | Backing |
|---|---|
| Panels (read these for the authoritative write) | `VideoPanel`, `AudioPanel`, `InterfacePanel`, `BindingPanel`, `CameraPanel` (fork). Fork: `VideoPanel.CPanel` no longer builds the *Render shadows* (`lshadow`) and *Frustum culling* (`frustumcull`, stored as `cullter`) boxes; the Performance page writes those two through `ui.setgprefs(ui.gprefs.update(…))` and re-reads them every tick, and the Video page is rebuilt on every visit so its `CPanel` never holds a copy of `gprefs` older than theirs |
| Performance (fork) | The `perf-*` prefs, each written live-and-persisted in one statement. Read by the seams in `Tileset`, `TerrainTile`, `MapMesh`, `MCache`, `Glob`, `Gob` and the `lib/svaj` copy; `perf-flatterrain` by `MCache.getfz` ([terrain-height.md](terrain-height.md)). Ground blending is `perf-groundblend-passes`, a whole `0`..`12` ([ground-detail.md](ground-detail.md)), seeded from the former boolean `perf-groundblend` where no count is stored. |
| Sky & weather (fork) | The `ambience*` prefs, each written live-and-persisted in one statement: `ambience-on` (the one switch over all of it, seeded from the sky's three parts and written back at once), `ambience-sky`, `ambience-clouds` and `ambience-fogon` (the sky's three parts, each on or off on its own, each defaulting to the older single switch `ambience`, itself default off), `ambience-precip` (the rain and snow part, seeded on where the game's own rain and snow both are), `ambience-cloudshadows` (the sky's clouds' shadows, seeded from the game's cloud-shadow switch `perf-clouds`), `ambience-fog`, `ambience-cloudamount` (multipliers), `ambience-cloudalt` (world units above the player's ground) and `ambience-stars`. Read by the seams in `Glob`, `MapView.tick` and `MapView.amblight` ([world-effects.md](world-effects.md)) |
| Video | `GSettings` **named fields**, not constants: `lshadow`, `vsync`, `hz`/`bghz` (/), `rscale`, `lightmode`, `maxlights`. ⚠️ **`gprefs` is per `UI`**, exactly as the audio sub-mix below is: `UI`'s field initialiser runs `GSettings.load(true)` in every tree the loop builds, `UI.setgprefs` publishes into that tree alone, and `UI.tick` flushes the dirty one to the single shared `gconf/*` pref store. So a tree nobody published into keeps what it loaded at construction, and the last one to publish is what every tree reads at the next client start |
| UI scale | pref `uiscale` (restart to take effect) |
| Placement granularity | `MapView.plobpgran` / `plobagran`  statics + like-named prefs |
| Camera inversion | `MapView.invcamx` / `invcamy`  statics + like-named prefs; consumed by `Camera.invdx`/`invdy` |
| The default camera's options (fork) | `MapView.dcamzoom`, `dcamzsmooth`, `dcamfov`, `dcamgnd`, `dcamobj`, `dcamfp`, `dcamup` — statics + like-named prefs, written live-and-persisted by `CameraPanel`'s "Default camera" group, which it shows only while `default` is the camera picked. `DefaultCam` reads them every tick. Its place — `dcamdist`, `dcamelev`, `dcamangl` — is written by the camera itself on every wheel notch and at the end of every drag, and has no control ([camera.md](camera.md)) |
| Camera choice | prefs `defcam`/`camargs`, written only by `MapView.setcam` ([camera.md](camera.md)); `CameraPanel.CamSelector` calls it. ⚠️ **A panel's constructor runs before it is in the tree** — `PButton.click` does `tgt.get()` and only then `add`s the result — so `ui` and `getparent(GameUI.class)` are both null there, and `PButton` caches the panel in `actual` and reuses it forever after. A control whose value the client can move behind its back (this one: `:cam` and the RTS mode both do) therefore re-reads in `show()`, which `chpanel` calls every time the panel is opened; construction is far too early and happens once |
| Audio master / buffer | `Audio.Root.volume()` (persists `sfxvol`), `bufsize()` (**in samples** @44100 Hz, persists `audiobuf`) |
| Audio channels | `ActAudio.Root` `.aui`/`.pos`/`.amb` → `RootChannel.setvolume` + public `volume` field. The three `AudioPanel` sliders map 1:1: "Interface volume"→`aui`, "In-game event volume"→`pos`, "Ambient volume"→`amb`. **There is no music slider** — `Music` is a separate MIDI player, see above. ⚠️ **A sub-mix level is per `ActAudio.Root`, and there is one of those per `UI`** (`UI`'s constructor does `new ActAudio.Root(audio)` over the one shared `Audio.Root` the loop hands every tree): `RootChannel`'s constructor reads `Utils.getpref("sfxvol-" + name, "1.0")`, so each starts at the saved level, but `setvolume` writes the pref **and only its own channel**. `OptWnd`'s slider is `ui.audio.aui.setvolume(...)` on the panel's own `UI`, so moving it leaves every other tree at the level it read when it was built, until the next client start. The **master** volume is not like this — `Audio.Root.volume()` is one `VolAdjust` on the shared mixer, so it reaches everything at once. ⚠️ **Every write to a `RootChannel` takes the channel's own monitor**: `setvolume` and `mute` are `synchronized`, which is the edge `mixer()`'s double-checked block needs — it builds `volc` and reads `muted` under that same monitor, so a channel first played after a `mute(true)` would otherwise read a stale `muted`, build itself audible and stay so for good, `mute` returning on `m == muted` from then on ([multi-session.md](multi-session.md) is what mutes them). The monitor is a **leaf**: nothing under it reaches a `UI`, a widget or the render tree. ⚠️ **`Root.clear` stops all three channels, and `UI.destroy` is its only caller** — a `RootChannel` that has ever played holds a `VolAdjust` on the shared `Audio.Root` mixer, and `clear()` is the only thing that takes it off, so one missed there goes on mixing for the rest of the process, a further one per login |

**Gotchas that cost time.**
- **The earliest preference read is on a Loader thread, inside an image decode, before `Client.<init>`.**
  `Client.main2` → `Client.setupres` → `Resource.loadlist` → a "Haven resource loader" thread decodes the
  first `Resource.Image`, whose constructor scales it → `UI.scale` → `UI.loadscale` →
  `Utils.getprefd("uiscale")`. Whatever backs `Utils.prefs()` is therefore opened on that thread, at that
  moment: it may initialise no class whose static initialiser needs the main thread or a session, and it may
  wait on nothing. This is why the fork's node owns its own path logic and reaches no other class of its
  layer.
- **A file that cannot be opened leaves the client running.** A directory in its place, a lock another
  process holds past the busy timeout, a `user_version` a newer client wrote, a driver that fails to load,
  or a read or write that fails later: one `Warning` at ERROR on stderr names the file and the cause, and the
  node is unavailable for the session — `Utils.getpref*` answer their defaults (what was loaded before the
  failure, for one that failed later), `Utils.setpref*` keep the value in memory, and nothing overwrites
  the file. `Warning.issue()` reads no preference, so the warning cannot recurse into the store it reports.
- **`AbstractPreferences.toString()` and `isUserNode()` open the registry.** Both compare the node's root
  against `Preferences.userRoot()`, and on Windows that call **creates** `HKCU\Software\JavaSoft\Prefs` when
  it is not there. A node meant to keep the registry closed overrides both; `MapPrefs`, the
  `-Dhaven.prefs` override, overrides neither, so printing that node — a log line, a debugger — would
  create the key.
- **The file is closed by a shutdown hook, because the client exits through `System.exit`**
  (`Client.main2`, [boot-and-loop.md](boot-and-loop.md)): nothing on the way out would otherwise checkpoint
  the write-ahead log and remove `client.sqlite-wal`/`-shm`. A crash or a kill leaves them, and the next
  open recovers them the way SQLite always does.
- **`GSettings` is immutable.** `update()`  returns a **new** `GSettings`;
  nothing changes until you publish it with `UI.setgprefs`. Read via `ui.gprefs.<field>.val`.
  There are no `GSettings.SHADOWS`-style constants — the settings are instance fields with short wire names
  (`"sdw"`, `"rscale"`, `"lighting"`…).
- **`lightmode` is `simple` / `zoned`** (the `LightMode` enum), *not* "global".
- **`Utils.setpref*` catches only `SecurityException`.** `Preferences.put*` also throws
  `IllegalArgumentException` past `MAX_KEY_LENGTH` (80 chars) / `MAX_VALUE_LENGTH` (8192) — the checks are
  `AbstractPreferences.put`'s, so they hold whatever node is installed — and that escapes every `setpref*`
  as a raw Java error from whatever wrote it. A writer minting a key out of names it does not control has to
  bound the length itself.
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

- [services](services.md) — keybindings, resources, and the rest of the cross-cutting map
- [audio](audio.md) — the channels and clips the Audio panel's sliders reach
- [the camera](camera.md) — `MapView.setcam`, the one writer of the `defcam`/`camargs` prefs
- [several sessions at once](multi-session.md) — why a sub-mix level and a `GSettings` publish are
  per `UI` rather than per client
