# FEATURES — index of `NNN-` folders

> ONE line per feature folder: `NNN-name — STATUS — summary (design doc) — tasks`.
> STATUS ∈ ACTIVE / DONE / PENDING. Folders are never archived — they are the addressable
> history of the project; closed ones are only opened when named.

- 001-bootstrap-engine — DONE — LuaJ engine + `:lua` REPL, disk loading (manifest), tick pump + events + timers (design/01,02,03,04,09) — tasks 001.1..001.3
- 002-read-api — DONE — Glob-backed read API: gob/world/map/player/time/sound/items/char/party + GobRef resolve (design/06) — tasks 002.1..002.3
- 003-widget-tree-reads — DONE — adapter mechanism (uimsg tap + poll) + vitals/buffs/food/study/skills/actionbar/equip reads & events (design/14) — tasks 003.1..003.4
- 004-saved-variables — DONE — hafen.store JSON persistence, per-char + account scopes, auto-save + flush lifecycle (design/02,05) — task 004.1
- 005-sandbox-reload-panel — DONE — strict sandbox + 2-layer watchdog, :reload + enabled set, AddOns panel + tooltip fix (design/05,10,12) — tasks 005.1..005.4
- 006-custom-ui — DONE — hafen.ui windows/widgets + LuaGOut draw wrapper + HUD & gob overlays (design/07) — tasks 006.1..006.2
- 007-hooks-hotkeys — DONE — hook levels L1/L2/L3 (input/action/message) + hafen.key hotkeys + keybind-panel integration (design/13) — tasks 007.1..007.5
- 008-widget-replacement — DONE — onWidgetCreate/adopt/replace + bags example (wrap-don't-reimplement) (design/08) — tasks 008.1..008.3
- 009-gap-subsystems — DONE — markers, lore, radar, slash, kin, speed, craft, quests, wounds, fight (all read; api-reference "Gap subsystems") — tasks 009.1..009.10
- 010-write-actions — DONE — per-addon permission + consent (D-027/D-028), hafen.act verbs + per-subsystem writes, walker demo (design/12) — tasks 010.1..010.7
- 011-virtual-entities — DONE — hafen.ghost client-only gobs: clickable, look, grid-anchored layouts, transform gizmo; planner example (design/16) — tasks 011.1..011.7
- 012-custom-rendering — DONE — hafen.render images/sprites/billboards + glTF models (textured, lit) on the shared entity core (design/17,18) — tasks 012.1..012.7
- 013-data-network — DONE — hafen.json (one serializer) + hafen.http async get/post, allowlist + IP block + safe redirects; netdemo (design/19) — tasks 013.1..013.3
- 014-ui-extensions — DONE — widget onDrop + g:resource + mouse mods (D-038/39/40) (design/07) — task 014.1
- 015-widget-introspection — DONE — WidgetNode tree walk + engine-true hit-testing; widgetstack framestack/inspector (design/20) — tasks 015.1..015.2
- 016-fonts — DONE — hafen.font: handles, own-drawing, all 11 scopes (chrome + world) + per-instance node:setFont (design/21, D-043) — tasks 016.1..016.9
