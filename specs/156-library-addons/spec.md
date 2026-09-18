# 156 — Library addons

## What and why

An addon can be a **library**: it exports a table of functions and any other addon reads it through the
client. Today `dependencies` is parsed and never read (`ROADMAP.md`, filed 051) and shared code is
vendored. Three halves: `hafen.client():addons()`, the collection of every
discovered addon with a handle per id; dependency lists that order the load and refuse what is missing; and
the export door, where everything crossing between two addons is a **view** — a function crosses as a
wrapper entering its owner's door, a table as a read-only copy, a plain value as itself, a bridge handle
not at all.

## Acceptance criteria

1. `hafen.client():addons()` is one collection object per addon: `:list(filter)`, `:count(filter)`,
   `:find(filter)` (a string is a substring test on the id), `:get(id)`. `pairs`, `#` and `[n]` refuse naming `:list()`.
2. `:get(id)` always hands back a handle, the same object per id: `:id()`; `:exists()` (a folder with a
   manifest at the last load); `:info()` — the `Addon` shape `{id, name, version, author, description,
   status, reason}`, `nil` for an id that does not exist; `:api()`. `status` is `loaded`, `disabled`,
   `not loaded`, `error`, `outdated`, `auto-disabled` or `manifest error`; `reason` the row's sentence or
   `nil`. `tostring` is `Addon(<id>)`.
3. A dependency entry is `<id>` or `<id>>=<MAJOR.MINOR.PATCH>`; any other form is a manifest error naming
   the form. Every addon runs after the installed, loading addons its two lists name, so a dependency's
   `export` precedes the dependant's file body. A cycle of hard dependencies is a load error on each member
   naming it; an optional entry that would close one is not ordered, with a log line.
4. A hard dependency that is not installed, disabled, out of date, a manifest error, or that failed to
   load makes the dependant a load error: `needs <id>, which is not installed` (`is disabled`, `is out of
   date`, `has a manifest error`, `failed to load`). A minimum not met: `needs <id> >= 1.2.0, 1.0.0
   installed`. A dependency whose `version` is not a version: `<id>'s version '<v>' is not a version`,
   naming the hub's form. An absent optional dependency changes nothing; one below its minimum is, to that
   addon, absent: `:api()` is `nil`.
5. An auto-disable of a library auto-disables every loaded addon naming it as a hard dependency, in the
   same sweep, reading `auto-disabled (needs <id>)`. Teardown runs in reverse load order.
6. The Installed row's tooltip gains `Needs: …`, `Optional: …` and `Used by: …`, each only when it has
   members.
7. `hafen.client():addons():export(t)` reads the export once, at the call; a second call refuses `already
   exported`. `t` holds functions, strings, numbers, booleans and tables of the same; anything else refuses
   naming its key path (`export: 'icon' is a Widget — export functions and plain values`). A write into `t`
   after the call changes nothing.
8. `handle:api()` is the receiver's own copy, the same table every call while the library is loaded,
   `nil` when it is not loaded or exported nothing. Read-only (a write refuses), `pairs`
   walks it, a function in it is a wrapper: `api.f == api.f`, `rawequal(api.f, f)` false.
9. A wrapper enters its owner's door: the owner is on the running-addon stack (a protected verb inside is
   gated by the **library's** consent), a fresh instruction budget, its time charged to the owner under a
   new `exports` category (`attribution.md`) and to the caller's own entry. A Lua
   error inside reaches the caller as `<id>.<key>: <reason>`; owner torn down: `<id>.<key>: <id> is
   disabled`; declined under a tree monitor: `<id>.<key>: <id> is busy on another thread`.
10. Arguments and returns cross by one rule in both directions: a function becomes a wrapper entering
    *its* owner's door (the same function, the same wrapper), a table a read-only copy (recursive), a
    string, number or boolean itself, and a bridge handle refuses naming position and kind:
    `toast.show: argument 2 is a Widget — a handle does not cross to another addon; hand it a function`.
11. `guides/libraries.md` exists and its library and consumer examples run as written.

## Out of scope — the boundaries

- **The hub's half.** Its JSON carries no `dependencies`: Browse's **Install** stages the pressed addon
  alone; a missing library is a load error naming its id. When the hub serves the field, one client task stages them.
- **Enabling one addon without a layer reload** (`ROADMAP.md`, filed 005): the loaded set changes only at
  a reload and an auto-disable, so `addons()` fires no `Added`/`Removed`.
- **`require`**: a library is another addon; your own files are `files`. **Side-by-side versions of one
  id**: a breaking change is a new id. **A library holding a consumer's data**: data stays with its
  owner's `hafen.store()`. Guide rules, not loader logic.

## Docs impact

New: `api/client/addons.md`, `api/types/client.md`, `guides/libraries.md`.
Modified: `manifest.md`, `panel.md`, `runtime.md`, `api/conventions.md`,
`api/client/README.md`, `api/README.md`, `api/types/README.md`, `api/client/profiling/attribution.md`,
`guides/README.md`, `guides/debugging.md`, `guides/permissions.md`, `guides/saved-data.md`.

Derived impact, `grep -rniE "dependenc|another addon's|see another|nothing to import|cooperate|librar|hands another|between addons|two addons" docs/addons`:
`manifest.md:56-57,61` rewritten; `guides/debugging.md:80` gains the `needs` sentence; `runtime.md:44` gains the
pointer to `addons()`. Every other hit stays true.

## Context files

- `src/io/brodgar/addon/OptionsHandle.java` — 1, 3
- `src/io/brodgar/addon/LuaAddon.java` — 1, 3
- `src/io/brodgar/addon/LuaCollection.java`, `LuaBinding.java` — 1
- `src/io/brodgar/addon/Interned.java`, `Refusal.java`, `Args.java`, `Section.java` — 1, 3
- `src/io/brodgar/addon/AddonRegistry.java`, `Addon.java` — 1, 2, 3
- `src/io/brodgar/addon/AddonManager.java` — 2, 3
- `src/io/brodgar/addon/Manifest.java`, `registry/Semver.java`, `ui/AddonPanel.java` — 2
- `src/io/brodgar/addon/Sandbox.java`, `ProfHandle.java` — 3
- `docs/addons/api/conventions.md`, `docs/addons/api/client/keybindings.md` — 1
- `docs/addons/manifest.md`, `docs/addons/panel.md`, `docs/addons/guides/debugging.md` — 2
- `docs/addons/runtime.md`, `docs/addons/api/threading.md`, `docs/addons/api/client/profiling/attribution.md` — 3
- `docs/addons/guides/permissions.md`, `docs/addons/guides/saved-data.md`, `docs/addons/guides/README.md` — 4
- `DOCUMENTATION.md`
