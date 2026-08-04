# Area: addons — the Lua (LuaJ) AddOn system

> Manifest read by `/plan`, `/implement` and `/end` before anything else. Everything that is
> specific to this area lives HERE, not in the commands. Max 30 lines.

- **Scope**: WoW-style Lua AddOn system in `src/io/brodgar/addon/`, its `hafen.*` API, and the
  addons under `addons/`. Core `haven` edits stay minimal, centralized and tagged `// addon:`.
- **Branch**: `feature/addons`.
- **Docs tier (ONE)**: `docs/addons/**` — the user-facing reference, nested where a namespace is
  a directory (`api/map/grids.md`), plus `runtime.md` and `examples.md`. A new section updates its
  `api/README.md` row and the `docs/addons/README.md` "API at a glance" table. No narrative notes.
- **Build check**: `ant hafen-client` → `BUILD SUCCESSFUL` (`ant get-luaj` for deps,
  `ant bin` to package, `ant run` to launch).
- **Verification**: in-game, by the maintainer. Java (engine) changes require an `ant` rebuild
  **plus a full client restart** — the JVM does not hot-reload; only Lua addon *files* reload
  live with `:reload`. Logic can be pre-checked with `jshell` or the in-game `:lua` REPL.
- **Test protocol**: **`specs/addons/TESTING.md` — READ IT** before writing or verifying a task's
  tests. One self-checking addon per task (`addons/<NNN>-<feature>.<X>/`), asserting through
  `hafen.*` and printing `[pass]`/`[fail]`/`[manual]` lines the maintainer pastes back.
- **API design rules**: one canonical way per operation (no dual styles); namespaced `hafen.*`;
  reference-based accessors (`hafen.gob.health(ref)`).
- **Commit paths** (for `/end`'s single commit): `src docs addons specs` — plus anything else the
  task genuinely touched (e.g. `build.xml`).
- **Contract file**: none separate — **the docs tier IS the contract**. `docs/addons/api/` is the
  `hafen.*` surface; there is no second "designed" copy to keep in sync.
- **Design docs**: `specs/addons/design/` — the standing design set, 1–3 relevant per feature.
