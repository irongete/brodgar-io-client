# Addon Format

> **Status:** 🟡 Draft · **Spec:** AddOns
> **Related:** [02-filesystem-and-build.md](02-filesystem-and-build.md), [05-lifecycle-and-reload.md](05-lifecycle-and-reload.md), [Q-005](../DECISIONS.md), [Q-010](../DECISIONS.md)

## Anatomy of an addon

An addon is a **single folder** under `<client>/addons/`. The folder name is the addon's
canonical id (lowercase, no spaces): `addons/partyframes/`.

```
addons/partyframes/
  manifest.toml        required — metadata + file list + load info
  main.lua             entry chunk(s), in load order
  frames.lua           additional chunks
  res/                 optional — custom .res assets (icons/images)
    frame.res
  locale/              optional — future: string tables
```

Minimum viable addon = a folder with `manifest.json` + one `.lua` file.

## Manifest

The manifest declares metadata, the ordered list of Lua files to run, dependencies, and which
saved-variable keys to persist. Format is **JSON** (`manifest.json`), parsed by the engine's
bundled JSON facility ([D-016](../decisions/filesystem-build.md)):

```json
{
  "name": "PartyFrames",
  "id": "partyframes",
  "version": "0.1.0",
  "author": "gonzalo",
  "description": "Compact party member frames with health bars.",
  "api_version": 1,

  "files": ["main.lua", "frames.lua"],

  "dependencies": [],
  "optional_dependencies": [],

  "saved_variables": ["settings", { "name": "account_prefs", "scope": "account" }]
}
```

A `saved_variables` entry is either a table name (per-character) or `{ "name": ..., "scope":
"account" }` for account-wide storage ([D-023](../decisions/filesystem-build.md)).

### Manifest fields

| Field | Required | Meaning |
|---|---|---|
| `name` | yes | Human-readable name shown in the AddOns panel. |
| `id` | yes | Canonical id; must equal the folder name. |
| `version` | yes | Free-form version string. |
| `author` | no | Shown in the panel. |
| `description` | no | Shown as a tooltip in the panel. |
| `api_version` | yes | Integer API level the addon targets; see compatibility below. |
| `files` | yes | Ordered Lua chunks to execute. |
| `dependencies` | no | Addon ids that must load first. |
| `optional_dependencies` | no | Addon ids to load first if present. |
| `saved_variables` | no | Global Lua table names the engine persists/restores. |

### API compatibility (`api_version`)

The engine exposes an integer **API level** (bumped when the `hafen.*` surface changes in a
breaking way). If an addon's `api_version` is newer than the engine's, it is shown as
"out of date" and (WoW-style) not loaded unless the user overrides. If older, the engine may
still load it (backwards-compatible surface) but flag it. Exact policy: TBD.

## Load model

- **Discovery:** the engine scans `addons/` for subfolders containing a valid manifest.
- **Enabled set:** only addons in the enabled set ([02-filesystem-and-build.md](02-filesystem-and-build.md))
  are loaded. Disabled addons are discovered (listed in the panel) but not executed.
- **Load order:** topologically sorted by `dependencies` / `optional_dependencies`, then a
  stable tiebreak (alphabetical by id). No `LoadOnDemand` initially ([Q-010](../DECISIONS.md)).
- **Execution:** for each enabled addon, in order, the engine creates its Lua environment
  ([04-engine.md](04-engine.md)) and runs each file in `files` in sequence. A file error aborts
  that addon (logged, addon marked errored) but does not abort other addons.
- **Lifecycle events:** after all files run, the addon receives `OnLoad`; when the world is
  entered (or already in-world at Reload), it receives an in-world init event. See
  [05-lifecycle-and-reload.md](05-lifecycle-and-reload.md) and
  [09-events-catalog.md](09-events-catalog.md).

## Addon-visible globals

Within an addon's Lua environment, the engine injects:

- **`hafen`** — the API facade ([06-lua-api.md](06-lua-api.md)).
- **`ADDON`** — a small table describing the running addon: `{ id, name, version, dir }`.
  `ADDON.dir` allows loading addon-relative assets/resources.
- A safe subset of the Lua stdlib ([12-security-and-permissions.md](12-security-and-permissions.md), [Q-007](../DECISIONS.md)).

Addons **do not** share a global table by default (each has its own environment). Cross-addon
communication goes through the event bus or an explicit shared registry — TBD.

## Custom assets (icons/images)

Addons may ship `.res` files (the client's native resource format) under `res/`. These are
loaded through the client's resource system, which already supports classpath and local-dir
resources. The engine registers the addon's `res/` directory as a resource source (or exposes a
loader keyed on `ADDON.dir`). Details in [07-ui-and-drawing.md](07-ui-and-drawing.md).

## Example: a minimal addon

`addons/hello/manifest.toml`
```toml
[addon]
name = "Hello"
id = "hello"
version = "0.1.0"
api_version = 1
files = ["main.lua"]
```

`addons/hello/main.lua`
```lua
hafen.events.on("OnLoad", function()
  hafen.log("hello addon loaded")
end)

hafen.ui.overlay(function(g)
  local p = hafen.gob.pos("player")
  if p then g:text(("x=%.1f y=%.1f"):format(p.x, p.y), 10, 10) end
end)
```
