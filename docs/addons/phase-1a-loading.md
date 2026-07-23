# Phase 1a — Loading addons from disk

> **Status:** ✅ Implemented; compile + runtime (JSON/manifest) + packaging verified. In-game check pending.
> **Design:** [specs/addons/15-implementation-plan.md](../../specs/addons/15-implementation-plan.md) (Phase 1a).

Addons are now **real folders on disk** that the client discovers, loads, and runs — each in its
own Lua environment. Adds `hafen.log` and a `:addons` console command.

## Where addons live
Default: the **`addons/` folder inside the client** (beside `hafen.jar` → `bin/addons/` at runtime),
resolved automatically — nothing to configure. The build copies the repo's `addons/` (first-party
addons) into `bin/addons/`. You can drop more addon folders into `bin/addons/`.

Override (optional): `-Dhaven.addondir=<path>` points the client at a different folder.

## Anatomy of an addon
```
addons/hello/
  manifest.json      required — metadata + which Lua files to run
  main.lua           the addon code
```

### `manifest.json`
```json
{
  "name": "Hello",
  "id": "hello",                 // must match the folder name
  "version": "0.1.0",
  "author": "brodgar",
  "description": "…",
  "api_version": 1,
  "files": ["main.lua"]          // run in this order
}
```
`id` (must equal the folder) and a non-empty `files` are required; the rest is optional.
`dependencies` / `optional_dependencies` are parsed but not yet ordered (that's a later phase).
Malformed manifests are reported and the addon is skipped — other addons still load.

## What an addon gets
- **`hafen`** — the API facade. So far: `hafen.gob.pos(ref)` (from Phase 0) and **`hafen.log(msg)`**.
- **`ADDON`** — a table describing the running addon: `{ id, dir }`.
- A standard Lua stdlib (per-addon environment; the strict sandbox comes in a later phase).

### `hafen.log(msg)`
Prints to the client's stdout/terminal (always) and shows an in-game system message (once the HUD
exists). Use it to trace what your addon is doing.

## The `hello` example (`addons/hello/main.lua`)
```lua
hafen.log("hello from the '" .. ADDON.id .. "' addon (v0.1.0)")
local p = hafen.gob.pos("player")
if p then hafen.log(("player is at %.1f, %.1f"):format(p.x, p.y))
else      hafen.log("no player yet (loaded before entering the world)") end
```
> Addons currently load at **session start**, before the world/HUD exist, so `pos("player")` is
> `nil` at load time (you'll see "no player yet"). Reading state *after* the world is ready waits
> for the `OnEnterWorld`/`OnUpdate` events in **Phase 1b**.

## How to test
```
ant run
```
- In the **terminal** you should see, on login:
  `[addon] addons dir: …/bin/addons`, `[addon] loaded hello v0.1.0`,
  `[addon] hello from the 'hello' addon (v0.1.0)`, `[addon] 1 addon(s) loaded`.
- In-game, the `:addons` console command lists loaded addons: `addons: hello`.
- `:lua hafen.log("test")` shows `test` in-game and in the terminal.

## Files
- `src/io/brodgar/addon/Json.java` — minimal dependency-free JSON reader.
- `src/io/brodgar/addon/Manifest.java` — parse + validate `manifest.json`.
- `src/io/brodgar/addon/Addon.java` — one loaded addon (manifest + dir + Lua env + status).
- `src/io/brodgar/addon/AddonManager.java` — discovery/loading, `hafen.log`, `:addons`, `:lua`.
- `src/haven/RemoteUI.java` — one-line `AddonManager.init(ui)` hook (per-session load).
- `src/haven/Console.java` — exposes the raw command line (`rawcmd()`) so `:lua` string literals survive.
- `build.xml` — copies `addons/` → `bin/addons/`.

## Limitations (next phases)
- No tick pump / events yet → addons run once at load; no `OnUpdate`/`OnEnterWorld` (Phase 1b).
- No sandbox hardening yet (full stdlib exposed) — Phase 1e.
- No enable/disable, no options panel, no Reload UI — Phase 1e.
- Only `hafen.gob.pos` + `hafen.log` exposed — the full read API is Phase 1c.
