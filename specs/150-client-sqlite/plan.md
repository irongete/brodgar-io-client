# 150 — plan

## Approach

One new class, `io.brodgar.addon.ClientDb`, **is the client's file**: `savedata/client.sqlite`, opened
once and lazily on the first call from any thread. It hands out an `AbstractPreferences` root
(`ClientDb.Prefs`) that `Utils.prefs()` installs in place of the registry node, the placements rows for
`StoreApi`, the holds rows for `BeltHold`, and `forget(id)` for the panel's Remove. `SqliteApi.Db`, the addon's file, loses its two client tables.

### `ClientDb`

- **Path**: `-Dhaven.savedatadir`; else the parent of `-Dhaven.addondir`; else
  `Utils.srcpath(ClientDb.class).resolveSibling("savedata")`, falling back to `new File("savedata")` as
  `AddonRegistry.addonDir()` does; `StoreApi.saveDir()` delegates to it. The path logic lives **here** and
  initialises no other class: the first prefs read runs on a "Haven resource loader" thread inside
  `Client.setupres()` (`Resource.Image` → `UI.scale` → `Utils.getprefd("uiscale")`), before
  `Client.<init>`, and `AddonRegistry.<clinit>` (its `STEPS`) must not run inside that image decode.
  `ClientDb.file(name)`, path-only, gives `Warning` its `haven-errors.log`.
- **Open**: `SqliteApi.Db`'s recipe without the Lua sandbox — `SQLiteConfig` WAL, `synchronous=NORMAL`,
  busy timeout 3000 ms, `user_version` as the schema (`1`; a higher number refuses the file as a newer
  client's); no attach limit, no `ProgressHandler`; `Exception | LinkageError` caught as `SqliteApi.open`
  does. One connection, every access under the instance monitor. A `Runtime.addShutdownHook` closes it:
  the client leaves through `System.exit` (`Client.main2`), and nothing else would remove its `-wal`/`-shm`.
- **Schema**, `WITHOUT ROWID`: `prefs(key TEXT PRIMARY KEY, value TEXT NOT NULL)`; `placements(addon,
  scope, name TEXT, x, y, w, h INTEGER, PRIMARY KEY(addon, scope, name))`; `holds(scope TEXT, slot
  INTEGER, entry TEXT NOT NULL, PRIMARY KEY(scope, slot))`.
- **Unavailable**: an open that fails, or a read or write that fails later, issues one `Warning` at
  ERROR naming the file and the cause (never `AddonManager.log`, which needs a session; `Warning.issue()`
  touches no pref) and flips the file unavailable for the session: `prefs()` answers the
  in-memory node, `holds(scope)` and `placements(id, scope)` answer `null`, writers keep memory only.

### The prefs — `ClientDb.Prefs`

A root node (`parent == null`, name `""`, as `MapPrefs.ROOT`): `getSpi/putSpi/removeSpi/keysSpi` over a
`ConcurrentHashMap` loaded whole at open, write-through (UPSERT / DELETE), `flushSpi`/`syncSpi` no-ops,
no children (`childSpi` an in-memory child, as `MapPrefs`). Overrides **`isUserNode()`**
(→ `true`) and **`toString()`**: `AbstractPreferences.isUserNode()` is `root == Preferences.userRoot()`,
`toString()` calls it, and `WindowsPreferences.getUserRoot()` *creates* `HKCU\Software\JavaSoft\Prefs`.
**`putSpi`/`removeSpi` never throw**: `AbstractPreferences.put` does not swallow, and `Utils.setpref*`
catches `SecurityException` only. `keysSpi` answers keys (`MapPrefs.keysSpi` answers values). `Utils.prefs()`: the `sysprefs()`/`MapPrefs` branch stays first, the `else` branch becomes
`ClientDb.prefs()`, the field `volatile`, one `// addon:` block; `prefspec` stays declared, unread.
`KeyBinding.repair()`, its `<clinit>` call and the `keybind-repair/menu-hotkeys` pref go.

### The holds — `BeltHold`

The live half stays. `restore(st)`: after writing the outgoing scope (`beltScope` stays), one
`ClientDb.holds(scope)`, the `LuaSlot.SLOTS` range check, into `beltPlaced` — now `Map<Integer, String>`;
`Placed`, `at`, the newest-wins merge and `lost` go. `flush(st)`: when `beltDirty`, `ClientDb.holds(scope,
rows)` — DELETE scope + INSERT, one transaction — on the session tick. Gone:
`beltLast`, `beltReadOnly`, `changed()`, `slice()`, `serialize()`, `write(a)`, `write(a, st)` and their
calls in `StoreApi.flush(a)`, `save(a, st)`, `s:store():flush()`. `addonDisabled` and its call in
`AddonRegistry.setEnabled` go: `teardownHolds` already returns the displaced content, the rows stay
dormant, `entryAdded` re-applies them through `slotsPlaced`. `release(GameUI, n)` (the right-click)
unplaces a row with no live `Hold` too, still answering `false`. `AddonRegistry.flushAll` calls
`BeltHold.flush(st)` for every `AddonManager.allStates()` — `shutdown()` quiesces and `quiet()` stops the
ticks, so nothing else writes the last gesture — and `AddonManager.uiDestroyed` flushes a dying session's
state first.

### The placements — `StoreApi`, `LuaWidget`, `UiApi`

`loadPlacements(a, scope)` reads `ClientDb.placements(a.id, scope)`; `writePlacements(a, scope, ps)`
writes `ClientDb.placements(a.id, scope, rows)`; `PlaceSet.readOnly` becomes the file's flag. **Written
on the gesture**: `land()` writes as `forget()` does. `rememberLanded` fires once at `Gesture.mouseup`
(`:draggable`/`:resizable`); the owned window's title-bar drag lands nowhere — `Window.mousemove` moves
`c`, `UiApi`'s window override calls `chromeDragged` per move — so that window overrides `mouseup` and
calls `rememberLanded(owner, this, true)` after a drag. `rememberCapture` stays where a widget is about to
vanish (teardown Step "saved variables", `LuaWidget.destroy()`, the chrome close) and in `rescope()`; its
calls with `writePlacements` in `StoreApi.flush(a)`, `save(a, st)` and the two `:flush()` verbs go, with
the `SqliteApi.require` gate on them.

### The addon file — `SqliteApi.Db`

The `hafen_holds` and `hafen_placements` CREATEs, `holds(scope)`, `holds(scope, rows)`, `clearHolds()`
and `placements(...)` go; `SCHEMA = 3`, and a file below 3 opens with `DROP TABLE IF EXISTS` of both, then
`user_version = 3`. `tableNameRefused` and `Scan.name` key on `startsWith("hafen_")` already: only their
message strings and two Javadocs name the tables; they name `hafen_documents` alone, still offering
`hafen.store():get(name)`.

### Remove — `Staging.apply`, `AddonRegistry.applyStaged`

`Staging.apply` answers the ids whose folder it removed; `applyStaged` calls `ClientDb.forget(id)` for
each: DELETE from `prefs` where the key starts with `LuaOption.key`'s `addon/<id>/opt/` or with
`keybind/` + `HookApi.keyBindId`'s prefix, from `placements` where `addon = id`, from `holds` where `entry`
starts with `addon/<id>/` — prefix compares, an id may contain `_` — and the two packed lists
(`PREF_DISABLED`, `PREF_CONSENTED`) rewritten without the id through their writers. It runs between the
teardown and the load, so nothing of the addon is live.

## Files to create/modify

- create `src/io/brodgar/addon/ClientDb.java`
- `src/haven/`: `Utils.java`, `KeyBinding.java`, `Warning.java`; `GameUI.java` (the right-click site)
- `src/io/brodgar/addon/`: `StoreApi`, `SqliteApi`, `BeltHold`, `AddonManager`, `AddonRegistry`,
  `LuaWidget`, `UiApi`, `Gesture`, `Addon`, `Staging`
- docs: the pages `spec.md` names; `docs/client/prefs-and-options.md` takes what this plan read in
  `src/haven`: the earliest prefs read, the seam, the fallback.

## Risks & gotchas

- **The registry opens through `toString()`**: the override is load-bearing, and
  `grep -rn "userRoot\|systemRoot\|userNodeForPackage" src/` is the check.
- **`serverWrote` keeps its guard**: the login burst sends `setbelt` for every slot before any entry
  exists; unplacing without a live `Hold` there would wipe every placement at login.
- **Task order**: the `SCHEMA` bump and the drops come last — dropping `hafen_placements` while
  `StoreApi` still reads it breaks the build between.
- **In-code claims to reword** with the task that voids them: `StoreApi` (the "nothing the client keeps
  for a character lives outside the addons' files" note; `flush`/`save` Javadoc), `BeltHold` (the class
  and lock-order notes), `AddonManager` (the `SessionState` belt fields), `AddonRegistry` (the teardown
  Steps, `setEnabled`), `LuaWidget`, `Gesture` ("rides the store's own throttle").
- Headless pre-checks run against `build/classes` with `-Dhaven.savedatadir=<scratch>`.

## Discarded alternatives

- **A prefs API of our own instead of `AbstractPreferences`** — 150 call sites and the
  `MAX_KEY_LENGTH`/`MAX_VALUE_LENGTH` checks stay exact only because `AbstractPreferences.put` enforces them.
- **The client's rows staying in the addon's file** — the record is the client's, it died with the
  addon's data, and the only wall between it and a raw `:exec` was a text scan.
- **`journal_mode=DELETE` so no sidecar needs closing** — an fsync per commit on the UI thread, under a
  slider.
- **Closing the file in `flushAll`** — window state is saved after it.
- **A registry import** — `Preferences.userRoot()` creates the key; nothing is released.
- **A sweep of rows whose addon is absent** — a folder moved away, a developer's own addon and a failed
  load all look like a removal.
- **Consent and the disabled set as tables** — a reshape beyond this feature; the packed lists work.
- **Deleting `savedata/<id>/` on Remove** — the player's data, not the client's bookkeeping.
- **A disable forgetting the holds** — one rule, nothing deleted, is the lifecycle the maintainer chose.
- **A timestamp on a hold row** — one row per (character, slot) needs no arbiter.
- **Placements written on the timer** — a gesture is rare and the file is always open.
