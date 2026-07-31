# hafen API reference

The complete `hafen.*` API available to addons. Start with the [conventions](conventions.md) — they
apply everywhere — then jump to a section below. Shared data shapes are in [types](types.md); all
events are in [events](events.md).

- **[Conventions](conventions.md)** — references, snapshots vs handles, filters, coordinates, gating.
- **[Data types](types.md)** — every snapshot shape (Gob, Item, Buff, …).
- **[Events](events.md)** — `hafen.events` + the full event catalogue.

## Sections

### Reading the world

| Section | Purpose |
|---|---|
| [`hafen.gob`](gob.md) | game objects — `hafen.gob(id)` gives a Gob object you read with methods |
| [`hafen.world`](world.md) | enumerate / scan game objects |
| [`hafen.map`](map.md) | terrain reads and coordinate conversions |
| [`hafen.markers`](markers.md) | map markers (read / add / remove) |
| [`hafen.radar`](radar.md) | minimap icon categories |

### The player & character

| Section | Purpose |
|---|---|
| [`hafen.player`](player.md) | local player data, including vitals |
| [`hafen.time`](time.md) | game clock & astronomy |
| [`hafen.char`](char.md) | attributes, LP, food, skills, credos, lore |
| [`hafen.study`](char.md#hafenstudy) | curiosities being studied |
| [`hafen.party`](party.md) | party roster |
| [`hafen.buffs`](buffs.md) | buffs & debuffs |

### Character-sheet subsystems

| Section | Purpose |
|---|---|
| [`hafen.kin`](kin.md) | kin / buddy roster (read + gated writes) |
| [`hafen.speed`](speed.md) | movement speed (read + gated write) |
| [`hafen.craft`](craft.md) | crafting (read + gated craft) |
| [`hafen.quests`](quests.md) | quest log |
| [`hafen.wounds`](wounds.md) | wounds |
| [`hafen.fight`](fight.md) | combat schools |
| [`hafen.actionbar`](actionbar.md) | action bar / hotbar (read + gated use) |

### Acting *(gated)*

| Section | Purpose |
|---|---|
| [`hafen.act`](actions.md) | drive the character — move, click, use items, menus. Requires the `actions` permission |

### UI & input

| Section | Purpose |
|---|---|
| [`hafen.ui`](ui.md) | custom windows, overlays, replacing native widgets, and walking + hit-testing the widget tree |
| [`hafen.ghost`](ghost.md) | client-only world props ("ghosts") — base/city planning |
| [`hafen.render`](render.md) | draw custom (non-`.res`) assets — the addon's own PNGs (on screen or in the world) and glTF 3D models |
| [`hafen.hook`](hooks.md) | intercept & alter input / actions / server messages |
| [`hafen.client`](client.md) | client settings (interface / video / audio / camera / client), hotkeys, the frame profiler and its counters |
| [`hafen.font`](fonts.md) | per-addon typography — load fonts, restyle client surfaces (or one single widget) |

### Audio & infrastructure

| Section | Purpose |
|---|---|
| [`hafen.sound` / `hafen.music`](audio.md) | play sound effects and music |
| [`hafen.events`](events.md) | subscribe to events |
| [`hafen.timer`](timer.md) | schedule one-shot / repeating callbacks |
| [`hafen.store`](store.md) | saved variables (persistent storage) |
| [`hafen.json`](json.md) | parse / encode JSON |
| [`hafen.http`](http.md) | external HTTP requests (gated by a `network` allowlist) |
| [`hafen.slash`](console.md) | console (`:name`) commands |
| [`hafen.log`](console.md#hafenlog--logging) | logging |

---

New to addons? Read the [getting-started guide](../getting-started.md) first.
