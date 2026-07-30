# Decisions — Filesystem, build & persistence formats

> Part of the decision log (ADR-lite). Index: [../DECISIONS.md](../DECISIONS.md). Entries are
> verbatim; each `### D-xxx` header line doubles as the decision's one-liner (grep them).
> Append new decisions of this kind here.
> Legend: ✅ Accepted - 🔄 Revisit later - ❌ Rejected - 💤 Superseded.

### D-001 — Addons live in a client-local `addons/` directory ✅
**Decision.** Addons are folders under an `addons/` directory resolved relative to the client
install (next to `hafen.jar`), **not** under `%APPDATA%`. Each addon is one subfolder, e.g.
`addons/partyframes/`, `addons/betterfishing/`.
**Rationale.** User preference; matches WoW's `Interface/AddOns/<name>/` model; keeps the client
self-contained and inspectable. The client already resolves jar-relative paths via
[`Utils.srcpath`](src/haven/Utils.java:122).
**Alternatives.** `Config.localdir()` = `%APPDATA%\Haven and Hearth` — rejected by user.
**See.** [02-filesystem-and-build.md](../design/02-filesystem-and-build.md).

### D-002 — Saved data in a separate `savedata/<genus>_<char>/` tree as JSON ✅
**Decision.** Addon persistent data lives under a **separate** `savedata/` directory (sibling of
`addons/`), with one subfolder per **`<genus>_<character>`** combination, containing `.json`
files written by addons: `savedata/<genus>_<charname>/<addon>.json`.
**Rationale.** User preference; per-character scoping matches how the client already namespaces
state; JSON is portable and human-inspectable. `genus` and `chrid` are real fields on
[`GameUI`](src/haven/GameUI.java:257).
**Open.** Account-wide (shared) saved data location — see [Q-002](../DECISIONS.md).
**See.** [02-filesystem-and-build.md](../design/02-filesystem-and-build.md).

### D-003 — LuaJ runtime jar built via Ant, not committed ✅
**Decision.** The Lua runtime is **LuaJ**, shipped as a jar handled exactly like the voice jar:
built/placed via Ant for local testing and releases, **not** committed to the repo.
**Rationale.** User preference; consistent with the existing `lib/brodgar/*.jar` handling, which
is already gitignored ([`.gitignore`](.gitignore)).
**Implication.** The jar must be fetched/placed by an Ant target (mirroring the `extlib`
pattern) and referenced on the classpath; see [Q-003](../DECISIONS.md) for exact placement.
**See.** [02-filesystem-and-build.md](../design/02-filesystem-and-build.md), [04-engine.md](../design/04-engine.md).

### D-014 — LuaJ jar in `lib/brodgar/`, Ant-fetched, one manifest token ✅ (closes Q-003)
`org.luaj:luaj-jse:3.0.1` placed in `lib/brodgar/` (auto compile-classpath + auto-copy to
`bin/lib/`); a new Ant `extlib`-style target fetches it (not committed, like the voice jar); append
`lib/luaj-jse-3.0.1.jar` to the manifest `Class-Path` at [build.xml:147](build.xml:147) — the one
non-glob build line, so this token is mandatory.

### D-015 — Addon-dir default = the client's `addons/` (jar-sibling); `-Dhaven.addondir` override only ✅ (closes Q-004)
**Default, with no config: the `addons/` folder inside the client** —
`Utils.srcpath(...).resolveSibling("addons")` (= `bin/addons/` at runtime). `-Dhaven.addondir=<path>`
is an **optional override only** — it is **NOT** baked into the `run` target, so nothing needs to be
typed normally. The `bin` build step copies the repo `addons/` (first-party addons) into `bin/addons/`.
Same scheme for `savedata/`. Resolution order: override → jar-sibling → `./addons`.

### D-016 — Manifest and saved data are JSON; one small bundled JSON facility ✅ (closes Q-005, Q-006)
`manifest.json` (not TOML) and saved data are **JSON**. Ship one minimal JSON library (Java,
Ant-managed like the other jars) used for both manifest parsing and saved-var (de)serialization,
exposed to Lua as `hafen.json`. One format, one parser, no TOML dependency. **Supersedes the TOML
placeholder in [03-addon-format.md](../design/03-addon-format.md).**

### D-023 — Saved-data scope: per-char + account-wide ✅ (closes Q-002)
Per-character: `savedata/<genus>_<char>/<addon>.json` (default). Account-wide:
`savedata/account/<addon>.json`. A saved-var declared in the manifest can be marked
`"scope": "account"`; default is per-character.
