# 147 — plan

## Approach

**The declaration goes, and every loop over it becomes a loop over what is live.** `Manifest.SavedVar`,
`Manifest.savedVariables` and `Manifest.savedvars` are deleted; `Manifest.load` refuses a manifest that
still carries the key — `'saved_variables' is not a manifest field: a document exists when
hafen.store():get(name) first names it, and the door is its scope — remove it` — which is the hard cut's
refusal, in the manifest's own vocabulary (an `IllegalArgumentException`, shown on the panel row like
every other manifest error). The sibling addons' 15 manifests lose the field in the same task.

**`get(name)` reads on demand.** `StoreApi.installStore` mints `owner.store` empty; `get` — both doors —
goes through one helper, `document(a, LuaTable vars, String scope, Map<String,String> last, String name)`:
the table under `name` if `vars` holds one; else a new table filled from `Db.document(scope, name)` (an
empty table when there is no row), set into `vars`, its JSON primed into `last` so an unchanged document
is never rewritten, and answered. `name` is taken by `Args.str` and refused when empty, naming what a
name is. The per-character door is `store(owner, user)` as today: `session(user, "get()")` still raises
before the world and for an ended session, and `charStore` still mints the `CharStore` — now with empty
`vars`, nothing read until asked.

**Scopes stay; loading them stops.** `loadAccount` and `loadChar` no longer read rows: `loadAccount` goes,
and `loadChar` — the tab, `enterWorld` — refills **in place** every table `cs.vars` already holds from
the new character's rows (a reference an addon cached stays the same object, now that character's) and
resets `cs.last`; a name not yet asked is read when it is. A row that cannot be read marks the scope
read-only exactly as `loadInto` does today, from inside the helper. `hasScope` goes.

**Writes iterate the live tables.** `scopeRows(a, vars, client)` walks `vars`' string keys instead of the
manifest — a document nobody asked for this session has no table and no row touched; `changed(rows,
last)` and `Db.documents(scope, changed)` are unchanged. `uncarriable`, `carriable` and `degraded` walk the
same keys. `unloadChar`, `writeClient`, `writeChar`, `save`, `flush` lose their `savedVariables.isEmpty()`
early returns; the placements' `LuaWidget.rememberCapture`/`writePlacements` path is untouched.

**`:list()` answers what exists.** New primitive `Db.names(scope)` — `SELECT name FROM hafen_documents
WHERE scope = ?` — unioned with `vars`' keys, sorted, a string array; `names(a, client)` and `declared(a)`
are rewritten and deleted respectively. `StoreApi.index` — the per-owner `__index` that turned
`hafen.store.cfg` into a refusal naming `:get` from the manifest — goes whole with the manifest that fed
it; the section falls back to `Refusal.sectionIndex("store")`, plain `nil` for a feature probe. Nothing is
released, so the spelling it refused is nobody's.

**The pages.** `documents.md` is rewritten around one sentence — a document exists when `get(name)` first
names it, and the door is its scope — and states, once, what that costs: a misspelt name is an empty
document, and one name through both doors is two. The heading *The one thing saved without being
declared* becomes *Where a widget sits is saved for you*, and its three inbound anchors move with it.
`manifest.md` loses the row and says nothing of the field; the tutorial stops declaring; the two guides,
`position.md`'s comment, `runtime.md:16`'s "account saved variables", the hub's five sites and the index
row follow.

## Files to create/modify

- `src/io/brodgar/addon/Manifest.java` — `SavedVar`, `savedVariables`, `savedvars` deleted; the refusal in
  `load`
- `src/io/brodgar/addon/StoreApi.java` — `installStore`, `store`, `nameArg` → the `document` helper,
  `declaredVar`/`declared`/`index`/`hasScope`/`loadAccount` deleted, `loadChar`, `loadInto` → the helper,
  `names`, `scopeRows`, `uncarriable`, `carriable`, `degraded`, `unloadChar`, `writeClient`, `writeChar`,
  `save`, `flush`
- `src/io/brodgar/addon/SqliteApi.java` — `Db.names(scope)`
- `src/io/brodgar/addon/Addon.java` — `store` minted empty; `lastClientDocs` primed per name
- `../brodgar-io-client-addons/*/manifest.json` — the 15 that declare the field; the commit is the
  maintainer's
- `docs/addons/api/store/documents.md`, `api/store/README.md`, `api/README.md`, `manifest.md`,
  `getting-started.md`, `guides/saved-data.md`, `guides/translating.md`, `api/position.md`, `runtime.md`,
  `api/event/bus/lifecycle.md`, `api/ui/native.md` — the spec's impact set

No `docs/client/` page: nothing upstream is read.

## Risks and gotchas

- **The in-place refill is the promise the page makes** ("a restore refills it in place rather than
  replacing it, so a table captured at load time is still valid"): `loadChar` must `clearTable` and refill
  every table `cs.vars` holds, never `vars.set(name, new LuaTable())`, or a cached reference goes stale on
  a tab.
- **Prime the write-skip cache at first `get`.** `lastClientDocs`/`cs.last` are keyed by name; a document
  read and never written must land in them as read, or the next autosave rewrites every row it touched.
- **The read-only state is per scope and set from inside `get` now**: the first unreadable row makes the
  whole scope read-only, as `loadInto` did — keep the log line naming the row and the file.
- **`Db.document` may run on the message thread** (a `get` from an inbound handler); it is
  `synchronized` on the connection like every primitive, and stays so.
- **The tutorial's addon is `myaddon`, built by the reader as they go**; nothing ships it, so its
  manifest blocks change on the page and nowhere else.
- **The archived 146 suites declared documents**; they are frozen and never run again. The 147.1 suite
  declares nothing.
- **`refusalverbs.py` reads every message string** and resolves `hafen.store():get(` against `StoreApi`'s
  `.set`s: the manifest refusal's spelling must be exactly that.
- **The 15 sibling edits are one line each**, but `ant bin` copies the sibling repo into `bin/addons`:
  run it before the in-game check, or the panel shows fifteen `manifest error` rows.

## Discarded alternatives

- **Keeping the declaration as an optional guard** — two ways to have a document; a guard that exists
  only when remembered is the one nobody has when they need it.
- **A `:document(name)` builder, declared in code like a table** — a document has no columns to declare;
  a builder with one setter is `get` with a longer name.
- **Refusing one name through both doors at runtime** — the refusal would depend on which door was used
  first in this session, a state no author can see.
- **Loading every existing row at install, as today** — the on-demand read is what stops a catalogue
  nobody asked for from being read at every launch and serialized on every tick.
- **A reserved prefix for document names** — a name is a row key in the client's own table; there is
  nothing for it to collide with.
- **Keeping `StoreApi.index`** — it named replacements for a spelling read from the manifest; without the
  manifest it can name nothing, and the spelling was never released.
