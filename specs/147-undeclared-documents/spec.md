# 147 — A document exists when `get(name)` first names it

## What & why

A document — the live Lua table `hafen.store():get(name)` and `s:store():get(name)` hand back — has to be
announced in the manifest first: `"saved_variables": ["settings", { "name": "seen", "scope": "client" }]`.
The declaration was the engine's list of what to load and what to save. Since the store is one SQLite
file, a document is a row of `hafen_documents` keyed by scope and name: `get(name)` can read its row the
moment it is asked, and the engine holds every table it handed out, which is the whole list a save needs.
What the declaration still does is repeat the door as a word (`"scope": "client"` says what `hafen.store()`
already says) and refuse a misspelt name — the one guard a declared table does not have either.

Two ways of declaring what an addon persists — a table in code, a document in the manifest — become one:
**a document exists when `get(name)` first names it, and the door is its scope.** The manifest keeps what
identifies an addon and what asks the user's consent, and nothing else.

## Acceptance criteria

1. `hafen.store():get(name)`, with no declaration anywhere, answers a usable empty table, the same object
   every call for the addon's life; a value assigned into it and flushed is read back by the same `get`
   after a `:reload`.
2. `s:store():get(name)` does the same for a character, and keeps raising before `SessionEnteredWorld`
   and for an ended session, as today.
3. The same name through the two doors is two documents: a value written through one is not in the
   other.
4. `:list()` on each door answers the names that exist in its scope — a row in the file, or a table
   handed out this session — sorted, as a string array, and none of the other scope's.
5. `get(nil)`, `get(42)` and `get("")` are refused naming the parameter and what a name is.
6. A manifest carrying `saved_variables` is a manifest error naming `hafen.store():get(name)`; the
   addon's row in the panel reads it.
7. The maintainer's addons that declared documents load without the field and read them back.
8. `documents.md` states the model with no declaration on it; the manifest page, the tutorial, the two
   guides and every page of the impact set are revised; both checkers exit 0.

## Out of scope

- **Dropping documents, or renaming the client's tables.** `get(name)`, `hafen_documents` and
  `hafen_placements` stay as they are; only the announcement goes.
- **A reserved name.** A document's name is a row key, so nothing collides with it: no prefix is refused.
- **A refusal for a misspelt name, or for one name through both doors.** Without a declaration there is
  nothing to hold a name against; a typo is an empty document, as `:table("nodse")` is an empty table. The
  page says so, once.
- **Placements.** `w:remember` already needs no declaration, and its rows do not move.

## Docs impact

`api/store/documents.md` is rewritten around the model; `manifest.md` loses the field's row.

```bash
grep -rn "saved_variables\|declared document\|declared saved\|you declared\|manifest does not declare\|Declare a" docs/addons/
grep -rn "the-one-thing-saved-without-being-declared" docs/addons/    # 3 links into a heading that must change
grep -rn "account saved variables" docs/addons/                       # runtime.md:16, a word 146 left
```

- `api/store/documents.md` — the opening definition, *Declare a document*, the verb table's "declared",
  the "name your manifest does not declare" paragraph, "the wrong door names the right one" (now two
  documents), and the heading *The one thing saved without being declared*, which no longer singles
  anything out — retitled, its 3 inbound anchors (`api/store/README.md:95`, `event/bus/lifecycle.md:54`,
  `ui/native.md:244`) re-pointed.
- `api/store/README.md:25,37,50,51,150` — "declared in the manifest", "declared per character", "declared
  `"scope": "client"`", "names you declared", the manifest link.
- `api/README.md:169` — the index row.
- `manifest.md:42` — the row goes; the tree and the error list say nothing of it.
- `getting-started.md:142-146,182` — the tutorial declares a saved variable; it stops.
- `guides/saved-data.md:9-26`, `guides/translating.md:35,51` — the declaration block and "declared
  `"scope": "client"`".
- `api/position.md:102` — "a declared saved variable" in a comment.
- `runtime.md:16` — "account saved variables are filled": the addon's own, and read on demand.
- `api/ui/native.md:222` — "nothing declared in your manifest" stays true; discharged.

No `docs/client/` page: everything read is `io.brodgar`.

## Context files

- `src/io/brodgar/addon/StoreApi.java` — 1 (`installStore`, `nameArg`, `declaredVar`, `declared`, `names`,
  `index`, `loadAccount`, `loadChar`, `loadInto`, `scopeJson`, `uncarriable`, `hasScope`, `unloadChar`,
  `writeAccount`, `writeChar`, `save`, `flush`)
- `src/io/brodgar/addon/SqliteApi.java` — 1 (`Db.document`, `Db.documents`: the row primitives)
- `src/io/brodgar/addon/Manifest.java` — 1 (`savedvars`, `SavedVar`, `savedVariables`, `load`)
- `src/io/brodgar/addon/Addon.java` — 1 (`store`, `accountReadOnly`, `lastAccountJson`)
- `src/io/brodgar/addon/Args.java`, `Refusal.java` — 1
- `../brodgar-io-client-addons/*/manifest.json` — 1 (the 15 that declare the field)
- `docs/addons/api/store/documents.md`, `README.md`, `api/README.md`, `manifest.md`,
  `getting-started.md`, `guides/saved-data.md`, `guides/translating.md`, `api/position.md`, `runtime.md`,
  `api/event/bus/lifecycle.md`, `api/ui/native.md` — 2
- `tools/docverbs.py`, `tools/refusalverbs.py` — 2 (run bare)
- `DOCUMENTATION.md` — 2
