# 149 — The held slots live in the store

## What & why

146 put everything an addon saves into `savedata/<id>/<id>.sqlite`. One record of the layer's own is
still a JSON file: **which action-bar slot is held for which addon entry, per character** — the
placement a drag or `slot:hold(pag)` makes and `s:menugrid():add(id)` re-applies after a relog.
`BeltHold` writes it to `savedata/<genus>_<char>/client/actionbar-holds.json` through
`StoreApi.readClientFile`/`writeClientFile`, two helpers that survived 146 and have no other caller. So the
client still mints a `<genus>_<char>/` folder per character, and "everything under `savedata/` that is not
`.sqlite` can go" is not yet true.

This feature moves that record into the store, where every other client-kept record already is:
**a third client-owned table, `hafen_holds`, in the file of the addon whose entry is placed** — beside
`hafen_documents` and `hafen_placements`, and shaped like the latter: rows the client writes for the
addon, per character, that the addon neither reads nor writes. The JSON helpers are deleted whole, and
the client writes nothing under `savedata/` but `<id>/` folders.

What the record *means* does not change — one entry per slot, the last one written; remembered across
a relog, a reload and a logout; forgotten by `slot:hold(nil)`, a right-click, a server write and a
persisted disable — and no Lua verb is added, renamed or reshaped. What changes is where it is, and one
consequence of that: since each addon's file holds its own slice, a slot two files both claim (one file
gone stale — its store read-only, its addon uninstalled while another took the slot) is settled by the
**newer placement**, which is the same "last one written" the page already promises.

## Acceptance criteria

1. A hold taken by `slot:hold(pag)` or by a drag is in the addon's own file, under that character's
   key, and nothing is written under `savedata/` outside `<id>/` folders: no `<genus>_<char>/`
   folder is created, and `readClientFile`, `writeClientFile`, `clientDir`, `readFile`, `writeFile` are
   gone from `StoreApi`.
2. After a `:reload`, the addon's `s:menugrid():add(id)` re-applies every slot placed for that entry —
   read back from the file, not from memory that survived the reload — and a slot ended by
   `slot:hold(nil)` before the reload is **not** re-applied.
3. The rows are written where a document of that character is: on the tick a placement changes, on the
   30-second timer, when the addon is reloaded or disabled, when the client quits, and on
   `s:store():flush()`; and always **before** the addon's file is closed, so no placement is lost to a
   reload or a quit.
4. A persisted disable (`AddonRegistry.setEnabled(id, false)`) deletes every character's rows in that
   addon's file, not only the live sessions' — the button comes back on no character.
5. A file whose `hafen_holds` cannot be read (store unavailable, or the read failed) makes that addon's
   slice read-only for the session, logged once naming the file — the empty set is never written over
   the only copy — as `hafen_placements` already rules.
6. `hafen_holds` is refused in a statement and in a declaration like the other two client tables, and
   the refusal names it; the file's `user_version` is `2`, and a `1` file opens with the table added.
7. The character key is a row key and nothing else: `StoreApi.scopeKey` keeps its spelling (the rows
   already written under it stay found) and loses the "inside `savedata/`" folder check whose folder
   no longer exists.

## Out of scope

- **A client-owned file** (`savedata/hafen.sqlite`): the record is keyed by the addon's entry and
  follows the addon's file, as the remembered placements do. The boundary is the file-per-addon rule
  of 146, which this feature does not reopen.
- **Reading the record from Lua** (`slot:placed()` or the like): the page says "your addon stores
  nothing", and no read is asked for. Where a read is wanted it is a verb of `hafen.actionbar`, a feature
  of its own.
- **Migrating the JSON files' contents**: nothing is released, and the maintainer is deleting them.
- **Splitting `docs/addons/api/actionbar.md`**: it stands at 308 lines before this feature. The one
  sentence this feature changes there is changed in place, line-neutral; the split is reported at
  the close.

## Docs impact

Pages written: `docs/addons/api/store/README.md` (the *When it is written* table; sandbox fact 3),
`docs/addons/api/store/statements.md` (the `hafen_` row), `docs/addons/api/store/documents.md` (the
character-key sentence), `docs/addons/api/actionbar.md` (*A hold is remembered*, in place),
`docs/addons/manifest.md` (the `savedata/` tree comment), `docs/client/glossary.md` (**genus**: the
`savedata/<genus>_<char>/` clause is `io.brodgar`'s and stops being true; the entry keeps upstream's own
use, the map-file name).

Derived set, run from `docs/`:

- `grep -rn "hafen_placements\|hafen_documents" --include=*.md .` → `addons/api/store/README.md:108` only.
- `grep -rni "actionbar-holds\|holds.json\|record of a slot" --include=*.md .` → `addons/api/actionbar.md:274`
  only (`client/README.md:205`'s "client's own record" is the explored map, untouched).
- `grep -rn "savedata/<\|_<char>" --include=*.md .` → `addons/api/store/README.md:3,64` (true as they
  are) and `client/glossary.md:37` (fixed above).

## Context files

- `specs/149-holds-in-the-store/spec.md`, `plan.md`, `tasks.md`
- `src/io/brodgar/addon/BeltHold.java` — 1
- `src/io/brodgar/addon/StoreApi.java` — 1
- `src/io/brodgar/addon/SqliteApi.java` — 1 (the `Db` class and the two `hafen_` refusal texts)
- `src/io/brodgar/addon/AddonManager.java` — 1 (`SessionState`'s belt fields, ~606; the tick at ~1699/1718)
- `src/io/brodgar/addon/AddonRegistry.java` — 1 (`setEnabled` ~778, `findLoaded` ~1154, `flushAll` ~560)
- `src/io/brodgar/addon/AddonPagina.java` — 1 (`PREFIX`, the identity `addon/<id>/<rel>`)
- `docs/addons/api/store/README.md`, `store/statements.md`, `store/documents.md`, `api/actionbar.md`,
  `docs/addons/manifest.md`, `docs/client/glossary.md` — 1
- `DOCUMENTATION.md` — 1
- `specs/147-undeclared-documents/addons/147-undeclared-documents.1/main.lua` — 1 (the suite pattern: a
  phase kept in the suite's own document across `:reload`)
