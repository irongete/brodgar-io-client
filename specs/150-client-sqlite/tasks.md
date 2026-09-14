# 150 — tasks

- [x] **150.1 — The client's file, and the prefs in it.** `ClientDb` (the path, the open recipe, the
      schema at `user_version` 1, one connection under its monitor, the shutdown hook, the unavailable
      flag, `file(name)`) and `ClientDb.Prefs`, installed by the `else` branch of `Utils.prefs()` (one
      `// addon:` block, the field `volatile`); `StoreApi.saveDir()` delegates to it; `KeyBinding.repair()`,
      its `<clinit>` call and the `keybind-repair/menu-hotkeys` pref go; `Warning.issue()` writes
      `haven-errors.log` through `ClientDb.file`. Pages: `docs/client/prefs-and-options.md` (the store
      section: the file, the seam, the earliest read on a loader thread, the fallback, `prefspec` unread),
      `docs/client/services.md:10` (the `repair()` sentence goes) and `:15-16` (the client's file beside
      `Config.localdir`), `docs/addons/manifest.md` (the tree gains `client.sqlite`, and the by-owner rule);
      `client/addon.md:4` and `client/README.md:20,129` discharged. Criteria 1–4.
      *Its suite* (`:t150`) declares a string option through `hafen.client():options():addon()`, writes a
      value and reads it back — the store works; writes a value of `MAX_VALUE_LENGTH + 1` characters and
      asserts the refusal names the limit, `LuaOption`'s text — the `AbstractPreferences` limit holds.
      Restores the option at the end. Headless first: put/get/keys through `Utils.prefs()` under
      `-Dhaven.savedatadir=<scratch>`, `"" + Utils.prefs()` opening nothing, the fallback when the path is
      a directory; `grep -rn "userRoot\|systemRoot\|userNodeForPackage" src/` empty.
      `[manual]`: `sqlite3 savedata/client.sqlite "select value from prefs where key like
      'addon/150-client-sqlite.1/%'"` — expect: the value the suite wrote.
      `[manual]`: `reg query HKCU\Software\JavaSoft\Prefs\haven\hafen | findstr 150-client` — expect: nothing.

- [x] **150.2 — The holds live in the client's file.** `ClientDb.holds(scope)` and `holds(scope, rows)`;
      `BeltHold` on them: `restore` one read, `flush` on the tick, `beltPlaced` a `Map<Integer, String>`;
      gone: `Placed`, `beltLast`, `beltReadOnly`, `changed`, `slice`, `serialize`, `write(a)`,
      `write(a, st)` and their calls in `StoreApi.flush`, `save` and `s:store():flush()`, `addonDisabled`
      and its call in `AddonRegistry.setEnabled`; `release(GameUI, n)` unplaces a dormant row;
      `AddonRegistry.flushAll` and `AddonManager.uiDestroyed` flush holds; `SqliteApi.Db` loses the
      `hafen_holds` CREATE, `holds*`, `clearHolds` and that name in `tableNameRefused`, `Scan.name` and the
      Javadocs — no `SCHEMA` bump yet. Pages: `actionbar.md` *A hold is remembered* and the `disabled` row
      (236), in place and line-neutral; `store/README.md:96`; `store/documents.md:42`. Criterion 5, and
      criterion 8's holds half.
      *Its suite* adds a menu entry, `slot:hold(pag)` on a free slot and reads `slot:hold()` back;
      `slot:hold(nil)` then reads `nil`; a hold on slot 145 is refused naming the range; the addon's own
      file lists no `hafen_holds` — `sqlite_master` through `hafen.store():query`, the name bound as `?`
      so the scan reads no `hafen_` text. Headless first: the `ClientDb.holds` round trip.
      `[manual]`: `:reload`, then `:t150` again — expect: the slot still holds the entry (the second run
      releases it and cleans up).
      `[manual]`: disable the suite in the panel, `:reload`, enable it, `:reload` — expect: the entry is
      back on its slot.

- [x] **150.3 — The placements live in the client's file, and the addon's file closes to the client.**
      `ClientDb.placements(id, scope)` and `placements(id, scope, rows)`; `StoreApi.loadPlacements` and
      `writePlacements` on them, `PlaceSet.readOnly` the file's flag; `land()` writes; the `UiApi` window's
      `mouseup` calls `rememberLanded` after a title-bar drag; `rememberCapture` stays at teardown,
      `destroy`, the chrome close and `rescope` only; the `writePlacements`/`rememberCapture` calls and the
      `SqliteApi.require` gate go from `StoreApi.flush`, `save` and the two `:flush()` verbs;
      `SqliteApi.Db`: the `hafen_placements` CREATE and `placements*` go, `SCHEMA = 3`, both
      `DROP TABLE IF EXISTS` on a lower file, the texts and Javadocs name `hafen_documents` alone. Pages:
      `store/README.md` (3-6, 36-40, 52, 76-81, 85-87, 95, 109-113, 149), `store/documents.md` (28-29, 45,
      132-154), `store/statements.md:93`, `store/tables.md:31,113`, `ui/native.md:240-244` in place;
      `api/README.md:169`, `runtime.md:241`, `ui/writes.md:47`, `guides/saved-data.md:118` revised or
      discharged. Criteria 6, 7, 8, 9.
      *Its suite* builds a window, `w:remember("p")`, `w:position(x, y)`, destroys it, builds another and
      remembers it under `"p"` — it answers `w:position()` as `{x, y}` (the record, applied through
      `rememberApply`); `sqlite_master` through `:query` with `?` bound answers no `hafen_placements` and
      one `hafen_documents`; `hafen.store():exec("CREATE TABLE hafen_x (a)")` and
      `hafen.store():table("hafen_x")` are refused naming `hafen_documents`. Forgets `"p"` at the end.
      Headless first: a `user_version` 2 file with both tables opened at 3 lists neither.
      `[manual]`: drag the suite's window by its title bar, `:reload` — expect: it comes back where it
      was dropped.
      `[manual]`: quit the client, `ls savedata/` — expect: `<id>/` folders and `client.sqlite`, nothing else.

- [x] **150.4 — Remove forgets the addon.** `ClientDb.forget(id)` — the `prefs` rows under
      `addon/<id>/opt/` and `keybind/` + the addon's `keyBindId` prefix, the `placements` rows of the addon,
      the `holds` rows whose entry starts with `addon/<id>/`, the two packed lists rewritten without the id
      through `writeDisabled` and the consent writer; `Staging.apply` answers the ids it removed and
      `AddonRegistry.applyStaged` calls `forget` for each. Page: `panel.md:142-146` (what Remove deletes,
      what it keeps, a folder deleted by hand). Criterion 10.
      *Its suite* declares an option, a hotkey and a remembered window and asserts each reads back — the
      rows Remove deletes exist and are the addon's. Remove is the hub's, refused for a folder the hub did
      not install, so the suite cannot remove itself: the deletion is proven headless — rows for two ids
      in every table and both lists, `forget(a)`, `b`'s rows intact, `a`'s gone — and by reading
      `applyStaged`.
      `[manual]`: install one addon from the hub, set one of its options, Remove it in the panel, `:reload`,
      then `sqlite3 savedata/client.sqlite "select count(*) from prefs where key like 'addon/<id>/%'"` —
      expect: `0`, and `savedata/<id>/` still there.
