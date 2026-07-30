# Filesystem Layout & Build

> **Status:** 🟡 Draft · **Spec:** AddOns
> **Related:** [03-addon-format.md](03-addon-format.md), [04-engine.md](04-engine.md), [DECISIONS.md](../DECISIONS.md) (D-001, D-002, D-003)

## On-disk layout

Everything the addon system reads/writes lives **inside the client folder** (next to
`hafen.jar`), not under `%APPDATA%`:

```
<client>/                     (in a release: the bin/ directory; contains hafen.jar)
  hafen.jar
  addons/                     ← addon CODE + assets (installed by copying folders)
    partyframes/
      manifest.toml
      main.lua
      res/frame.res
    betterfishing/
      manifest.toml
      main.lua
      fishing.lua
  savedata/                   ← addon PERSISTENT DATA (written by the engine)
    <genus>_<charname>/       ← one folder per world+character
      partyframes.json
      betterfishing.json
    account/                  ← (proposed) account-wide data — see Q-002
  addons/enabled.txt          ← (proposed) the enabled-addons list — see "Enabled set"
```

- **`addons/`** — one subfolder per addon. Content described in
  [03-addon-format.md](03-addon-format.md). ([D-001](../decisions/filesystem-build.md))
- **`savedata/`** — separate from `addons/`. One subfolder per `<genus>_<charname>` combination
  (world + character). Files are per-addon `.json`. ([D-002](../decisions/filesystem-build.md))
- Base-client preferences (window positions, keybinds) are **unchanged** — they keep using the
  Java `Preferences` store under `%APPDATA%`/registry. Only *addon* data follows the new
  convention.

## Path resolution

The addon root is resolved in this order:

1. **`-Dhaven.addondir=<path>`** — explicit override (used by the dev loop). *(exact property
   name — see [Q-004](../DECISIONS.md))*
2. **`Utils.srcpath(Client.class).resolveSibling("addons")`** — the folder next to the running
   jar. In a release this is `bin/addons`.
3. **`./addons`** — working-directory fallback.

The client already resolves jar-relative paths this way:
[`Utils.srcpath(Class)`](src/haven/Utils.java:122) returns the filesystem path of the jar/classes
dir via `getProtectionDomain().getCodeSource()`, and [`Config`](src/haven/Config.java:126) already
reads a neighboring `haven-config.properties` with `srcpath(...).resolveSibling(...)`. So this is
an established pattern, not a new mechanism.

`savedata` resolves the same way (sibling `savedata` of the jar), with the same override option.

> **Caveat.** If the client is ever installed in a read-only location (e.g. `Program Files`),
> writing under the client folder fails. Running from `bin/` (the normal case for this
> community client) it is user-writable. If we ever need to support read-only installs, addon
> *code* still loads from `<client>/addons`, but *writable* data would fall back to
> `Config.localdir()`. Out of scope for now.

## Dev loop vs. release ([Q-004](../DECISIONS.md))

- **`bin/` and `build/` are gitignored** ([`.gitignore`](.gitignore)); they are build output.
- The **repo-level `addons/`** (tracked) is the source of truth for **first-party** addons
  (e.g. `partyframes`). The build copies it into `bin/addons/` so a fresh release ships them.
- **Problem:** if the build copies `addons/` → `bin/addons/`, editing the repo copy is not
  reflected in the running client (`bin/addons/`) without a rebuild — breaking the fast
  edit→`:reload` loop.
- **Proposed solution:** in the Ant `run` target, pass `-Dhaven.addondir=${basedir}/addons` so
  **development reads the repo folder directly** (live edits + `:reload`, no rebuild), while
  **releases** resolve `bin/addons` next to the jar. Users install extra addons by dropping
  folders into `bin/addons/`.

`savedata/` is runtime data → always under the client folder (`bin/savedata/` in a release);
never copied by the build; gitignored in dev (or written under `${basedir}/savedata` when using
the dev override).

## Enabled set (persistence of on/off state)

Per [D-006](../decisions/lifecycle.md) (WoW model), the enabled/disabled state of each addon is persisted and
applied on Reload. To stay consistent with "everything under the client folder" ([D-001](../decisions/filesystem-build.md)),
the enabled set is stored **as a file under the client folder**, not in Java `Preferences`.

- **Proposed:** `addons/enabled.txt` (one addon name per line) or a small
  `addons/state.json`. Simple, portable, inspectable (WoW-like).
- Alternative considered: `Utils.setprefsl("addon/enabled", …)` (the client's NUL-delimited
  string-list pref) — rejected to keep addon state out of `%APPDATA%`.

Scope of the enabled set (account-wide vs per-character) is open — see [Q-002](../DECISIONS.md).
WoW allows per-character enable; v1 can be account-wide (one `enabled.txt`) with per-character as
a later enhancement.

## Build changes (Apache Ant, `build.xml`)

The build is Ant. Verified facts about the current build:

- `javac srcdir="src"` compiles the **entire** `src` tree, so a new `src/io/brodgar/addon/`
  package **compiles automatically** — no target edit. Non-`.java` files under `src` are copied
  into the jar.
- Any jar in **`lib/brodgar/`** is auto-added to the compile classpath by an existing
  `<fileset dir="lib/brodgar" includes="*.jar"/>` and auto-copied to `bin/lib/` by the `bin`
  target.
- The runtime classpath is the manifest `Class-Path` (resolved relative to `bin/`).

Planned changes:

1. **LuaJ jar** ([D-003](../decisions/filesystem-build.md), [Q-003](../DECISIONS.md)): place `luaj-*.jar` where the
   build picks it up (either reuse `lib/brodgar/` or a new `lib/luaj/` with its own
   `pathelement` + copy step), and append its token to the manifest `Class-Path`. The jar is
   **built/fetched by Ant, not committed** (like the voice jar; `lib/brodgar/*.jar` is
   gitignored).
2. **Ship first-party addons:** add a copy step in the `bin` target: repo `addons/` →
   `bin/addons/` (mirroring how it copies res/config). Do **not** copy `savedata/`.
3. **Dev override:** add `-Dhaven.addondir=${basedir}/addons` to the `run` target JVM args
   ([Q-004](../DECISIONS.md)).
4. **JSON support** for saved data ([Q-006](../DECISIONS.md)): either a small Java JSON jar,
   a bundled pure-Lua json module, or a hand-rolled reader/writer. Undecided.

> The footprint mirrors the voice integration: a new self-contained package, a dependency jar
> handled by Ant, and a couple of small `build.xml` edits. The exact ledger of *core* edits is
> in [11-core-hooks.md](11-core-hooks.md).
