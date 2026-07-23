# AddOns — Implementation & Usage Docs

This directory documents the AddOn system **as it is actually implemented**, feature by feature,
built up **progressively** as each piece lands. It is the counterpart to the design spec:

- **[`specs/addons/`](../../specs/addons/README.md)** — the *design* (the plan, decisions, audit).
- **`docs/addons/`** (this folder) — the *implementation & usage* docs: what's built, how to use it.

## Working process

- Each change (feature/phase) is **documented here individually before its commit**.
- The **maintainer verifies everything before any commit** — the assistant prepares code + docs and
  stops; it does not commit on its own.

## Index

| Doc | Status | What it covers |
|---|---|---|
| [phase-0-spike.md](phase-0-spike.md) | ✅ Implemented & verified | LuaJ engine spike, `:lua` REPL, `hafen.gob.pos` |
| [phase-1a-loading.md](phase-1a-loading.md) | ✅ Implemented (in-game check pending) | Loading addons from disk: `manifest.json`, per-addon env, `hafen.log`, `:addons` |

_(Grows as phases land — see [`specs/addons/15-implementation-plan.md`](../../specs/addons/15-implementation-plan.md) for the build order.)_
