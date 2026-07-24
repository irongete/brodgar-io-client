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
| [phase-1b-events-timers.md](phase-1b-events-timers.md) | ✅ Implemented (in-game check pending) | Tick pump, event bus (`hafen.events`: OnLoad/OnEnterWorld/OnUpdate/GobAdded/GobRemoved/OnDisable), timers (`hafen.timer`) |
| [phase-1c-read-gobs.md](phase-1c-read-gobs.md) | ✅ Implemented & verified | Read API part 1: `hafen.gob.*` (per-gob accessor) + `hafen.world.*` (enumerate/nearest/within); full gob snapshots |
| [phase-1c2-map-player-time-sound.md](phase-1c2-map-player-time-sound.md) | ✅ Implemented & verified | Read API part 2: `hafen.map.*`, `hafen.player.*`, `hafen.time.*`, `hafen.sound`/`hafen.music` |
| [phase-1c3-items-char-party.md](phase-1c3-items-char-party.md) | ✅ Implemented & verified | Read API part 3: `hafen.items.*`, `hafen.char.*`, `hafen.party.*`; the `"partyN"` GobRef token |
| [phase-1d1-vitals-widget-tree.md](phase-1d1-vitals-widget-tree.md) | ✅ Implemented & verified | Widget-tree read mechanism (Locator + Adapter + inbound-`uimsg` tap) + `hafen.player.vitals` + `VitalsChanged` |
| [phase-1d2-buffs-food.md](phase-1d2-buffs-food.md) | ✅ Implemented (in-game check pending) | Widget-tree part 2: `hafen.buffs.*` (+ BuffAdded/Removed/Changed) + `hafen.char.food()` (+ FepChanged); per-tick `poll()` path |
| [phase-1d3-study-skills.md](phase-1d3-study-skills.md) | ✅ Implemented & verified | Widget-tree part 3: `hafen.study.*` (slots/summary + StudyChanged) + `hafen.char.skills()`/`skill(name)` — zero core edit |
| [phase-1d4-actionbar-equip.md](phase-1d4-actionbar-equip.md) | ✅ Implemented (in-game check pending) | Widget-tree part 4: `hafen.actionbar.slot(n)` (+ ActionbarChanged) + `EquipChanged` — zero core edit; completes Phase 1d |
| [phase-1e-saved-variables.md](phase-1e-saved-variables.md) | ✅ Implemented (in-game check pending) | Saved variables: `hafen.store.<name>` + `flush()`, JSON under `savedata/` (per-char + account scope) — zero core edit |
| [phase-1f1-sandbox.md](phase-1f1-sandbox.md) | ✅ Implemented & verified | Lua sandbox: strict env whitelist (no `io`/`luajava`/`require`/`load`/`debug`) + instruction hard-stop watchdog (D-017/D-018) — zero `haven` edit |
| [a1-markers.md](a1-markers.md) | ✅ Implemented & verified | Gap subsystem A1: `hafen.markers` (list/nearest/add/remove) over the client-side map DB + `MarkersChanged` — zero `haven` edit |
| [a4-skills-credos-lore.md](a4-skills-credos-lore.md) | ✅ Implemented & verified | Gap subsystem A4 (completion): `hafen.char.skillsAvailable()`/`credos()`/`experiences()` — buyable skills, credos, lore — zero `haven` edit |

_(Grows as phases land — see [`specs/addons/15-implementation-plan.md`](../../specs/addons/15-implementation-plan.md) for the build order.)_
