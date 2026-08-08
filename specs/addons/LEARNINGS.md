# Learnings — index

> Hard-won, non-obvious facts (479 entries), split by category under `learnings/`. **Never read
> the whole set** — pick the 1-2 relevant files below and `grep` them (e.g.
> `grep -i -B1 -A4 "Loading" learnings/ghosts.md`, or `grep -ri <term> learnings/` when unsure).
> **Append-only:** new learnings are appended to the matching category file; add an index line
> here only when a new category file is created.

- [threading.md](learnings/threading.md) — the thread model: UI lock, `tick→draw→swap` frame loop, loader threads, `callLua` serialization.
- [engine-lifecycle.md](learnings/engine-lifecycle.md) — loading/manifest, tick pump, reload, enabled set, saved-vars store, console/log, `OnEnterWorld` timing + data-streaming races, adopting published resource code.
- [luaj-bridge.md](learnings/luaj-bridge.md) — LuaJ quirks (coercion, `isstring`, userdata), sandbox + watchdog, bridge calling conventions, marshalling.
- [world-reads.md](learnings/world-reads.md) — Glob-backed reads: gobs, map/grids/**positioning** (no global coord), items, char, party.
- [widget-tree-reads.md](learnings/widget-tree-reads.md) — the adapter mechanism (uimsg tap vs per-tick poll) and its surfaces: the HUD meters, buffs, FEP, study, skills, actionbar, equip.
- [ui-widgets.md](learnings/ui-widgets.md) — custom windows/widgets, `LuaGOut`, HUD/gob overlays, drops, widget internals (`add` vs `addchild`, the close-animation destroy), the W-series introspection/hit-testing, the Widget entity (interning, provenance, container items) and the selector grammar + role classifier + selector events.
- [widget-replacement.md](learnings/widget-replacement.md) — replacement (`adopt` and `onWidgetCreate` are gone): the `w:replace(view)` verb and the enclosing-window hop, the substitution's lifetime, the legacy placement descriptor and why it outlived its observer, scans, restore-on-remove.
- [hooks-hotkeys.md](learnings/hooks-hotkeys.md) — input/action/message hooks (L1/L2/L3), `Widget.listen`, `UI.wdgmsg`/`uimsg` taps, global hotkeys, `KeyBinding`/keybind panel.
- [gap-subsystems.md](learnings/gap-subsystems.md) — markers/MapFile, radar, slash/console, kin, craft, quests, wounds, fight.
- [actions-gated.md](learnings/actions-gated.md) — `hafen.act` verbs, wdgmsg encodings, the permission model (D-027/D-028), write-verb design.
- [ghosts.md](learnings/ghosts.md) — client-only gobs, render slots/states, picking, gizmo, placement snapping.
- [rendering.md](learnings/rendering.md) — custom images/sprites/billboards, glTF (parse, textures, materials, normals/lighting).
- [network-data.md](learnings/network-data.md) — `hafen.json`, `hafen.http` (async, allowlist, redirects), hostile-input caps.
- [fonts.md](learnings/fonts.md) — font provider/scopes, per-site routing patterns, text caches + invalidation, RichText.
- [testing-tooling.md](learnings/testing-tooling.md) — headless test patterns (`<clinit>` traps, prefs isolation, reflection), jshell, dry-running Lua against a stub bridge, docs link/anchor checking, MSYS/Windows, build/encoding.
- [profiling.md](learnings/profiling.md) — the client's own frame instrumentation (`uprof`/`rprof`/`gprof`), late GPU timestamps, hot-path rules for the probes and the read surface.
- [client-limits.md](learnings/client-limits.md) — what the client/protocol CANNOT give (other players' names, absolute vitals, typed quality, buff seconds) — check before promising a surface.
- [process-method.md](learnings/process-method.md) — task splitting, test/demo design (its pre-034 entries speak of the old single `hello` harness; the protocol is now `TESTING.md`), API-design judgement calls, decision-process lessons.
