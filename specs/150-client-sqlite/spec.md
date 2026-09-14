# 150 — The client's own file

## What & why

What the client keeps for itself lives in two places, neither its own. Its **preferences** — options,
keybinds, window sizes, login tokens, the enabled set, the consent record — are `java.util.prefs`: on
Windows the registry node `HKCU\Software\JavaSoft\Prefs\haven\hafen`, which the official client writes
too. The two records it keeps **about an addon** — where the user put its windows (`w:remember`) and
which action-bar slots hold its menu entries (`slot:hold`) — are rows in the addon's own file.

The rule becomes **by owner**: the client writes one file of its own, `savedata/client.sqlite` — its
preferences, the placements, the holds — and an addon's file holds only what the addon stores through
`hafen.store()`. The registry is never opened; `haven-errors.log` moves to the client folder. **Nothing is
migrated**: no registry value is read, no row carried over (the maintainer deletes every `savedata/`).

An addon's lifecycle is then two sentences. **Disable keeps everything**: options, hotkeys, consent,
placements and holds stay dormant and come back when the addon is enabled again. **Remove forgets the
addon**: the panel's Remove deletes the folder and every row the client keeps about it; the addon's own
file is the player's and stays. No addon-callable verb is added, renamed or reshaped.

## Acceptance criteria

1. Every `Utils.getpref*`/`setpref*` reads and writes `savedata/client.sqlite`, table `prefs`: an addon
   option written through `hafen.client():options():addon()` is a row there and reaches no registry.
   `src/` contains no call to `Preferences.userRoot()`, `systemRoot()` or `userNodeForPackage()`, and
   printing the node opens nothing.
2. The limits hold: an option value over `Preferences.MAX_VALUE_LENGTH` is refused naming the limit, a
   key over `MAX_KEY_LENGTH` likewise; `-Dhaven.prefs` and `-Dhaven.prefs.<key>` still install the in-memory
   override.
3. A file that cannot be opened, or a read or write that fails later, leaves the client running:
   preferences answer defaults and keep writes in memory, placements and holds are read-only for the
   session, one warning names the file and the cause, nothing overwrites the file.
4. `haven-errors.log` is written beside the client, not in the user's home.
5. A hold — `slot:hold(pag)` or a drag — is a row of `client.sqlite` keyed by the character; it comes back
   after a `:reload`; only `slot:hold(nil)`, a right-click and a server write onto a held slot forget it;
   the rows of an addon that is disabled or not loaded are kept and re-applied when it loads; the last
   gesture before a quit is in the file.
6. A placement — `w:remember(name)` — is a row of `client.sqlite` keyed by the addon and the scope,
   written when the gesture lands (a `:draggable` drag, a title-bar drag, a resize) and when the screen
   changes; a remembered window comes back where it was dropped after `:reload`.
7. `hafen.store():flush()` and `s:store():flush()` write documents alone.
8. An addon's file holds no table of the client's but `hafen_documents`: a fresh file has no other, an
   older file is opened with the two dropped and `user_version` 3, and the `hafen_` prefix is refused in a
   declaration and in a statement naming `hafen_documents` alone.
9. `savedata/` holds `<id>/` folders and `client.sqlite` (its `-wal`/`-shm` while the client runs), nothing
   else the client writes.
10. Applying the panel's Remove deletes, with the folder, every row of the addon's in `client.sqlite` —
    options, hotkey assignments, consent, its entry in the disabled set, placements, holds — and no other
    addon's; `savedata/<id>/` stays. A folder deleted by hand leaves its rows dormant.

## Out of scope

- `%APPDATA%\Haven and Hearth` — the `data` cache, which is also the map file, and its
  `haven-config.properties` — stays where upstream puts it.
- Reading the registry, ever: an import would create the key. Per-character keybinds. A Lua read of the
  client's rows. A sweep of rows whose addon is absent: removed and moved away look the same.
- The debug dumps behind explicit flags; the natives sqlite-jdbc, JOGL and LWJGL extract into `%TEMP%`.
- `-p`/`haven.prefspec`: read by nothing once the node is a file.
- The splits of `ui/native.md` and `actionbar.md`, both over the ceiling: edited in place, line-neutral.

## Docs impact

Pages written: `docs/client/prefs-and-options.md`, `docs/client/services.md:10,15-16`,
`docs/addons/manifest.md` (the `savedata/` tree — the by-owner rule's home), `api/store/README.md` (opening,
*The file*, *When it is written*, sandbox fact 3), `api/store/documents.md` (scope table, `flush` rows,
*Where a widget sits is saved for you*), `store/statements.md:93`, `store/tables.md:31,113`,
`api/actionbar.md` (*A hold is remembered*, the `disabled` row at 236), `api/ui/native.md` (*Remembering
where the user put it*), `panel.md:142-146` (what Remove deletes).

Derived set, run from `docs/`:

- `grep -rn "hafen_placements\|hafen_holds\|hafen_documents" --include=*.md .` →
  `addons/api/store/README.md:109-110` only.
- `grep -rn "savedata" --include=*.md .` → `store/README.md:3-4,64` (true as they are), `manifest.md:18,26`,
  `panel.md:144`.
- `grep -rni "preference store\|java.util.prefs\|Utils.prefs\|prefspec\|haven\.prefs" --include=*.md .` →
  `client/addon.md:4`, `client/README.md:20,129` (true as they are), `docs/client/prefs-and-options.md:7-16`.
- `grep -rni "held slot\|hold is remembered\|remembered placement\|save timer\|for good" --include=*.md .`
  → `actionbar.md:160`, `api/README.md:169` (true as they are), `actionbar.md:251,283`,
  `store/documents.md:42`, `store/README.md:95-96,110`, `store/statements.md:93`, `ui/native.md:240`.
- `grep -rni "install again\|comes back as you had\|uninstall" --include=*.md .` → `panel.md:145`;
  `actionbar.md:116`, `ui/native.md:45` (true as they are).
- `grep -rn "repair()" --include=*.md .` → `client/services.md:10`.

## Context files

- `src/haven/{Utils,MapPrefs,KeyBinding,Warning,Client}.java` — 1
- `src/haven/GameUI.java` — 2
- `src/io/brodgar/addon/{SqliteApi,StoreApi}.java` — 1, 2, 3
- `src/io/brodgar/addon/ClientDb.java` — 2, 3, 4
- `src/io/brodgar/addon/AddonRegistry.java` — 1, 2, 3, 4
- `src/io/brodgar/addon/AddonManager.java` — 2, 3
- `src/io/brodgar/addon/{BeltHold,LuaSlot}.java` — 2
- `src/io/brodgar/addon/LuaOption.java` — 1, 4
- `src/io/brodgar/addon/{Addon,LuaWidget,UiApi,Gesture}.java` — 3
- `src/io/brodgar/addon/{Staging,KeybindingsOptions,HookApi}.java` — 4
- `docs/client/{prefs-and-options,services}.md`, `docs/addons/manifest.md` — 1
- `docs/addons/api/actionbar.md` — 2
- `docs/addons/api/store/{README,documents}.md` — 2, 3
- `docs/addons/api/store/{statements,tables}.md`, `docs/addons/api/ui/native.md` — 3
- `docs/addons/panel.md` — 4
- `DOCUMENTATION.md`, `CLAUDE.md`
