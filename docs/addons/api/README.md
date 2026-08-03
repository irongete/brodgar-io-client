# hafen API reference

The complete `hafen.*` API available to addons. Start with the [conventions](conventions.md) — they
apply everywhere — then jump to a section below. Shared data shapes are in [types](types.md); all
events are in [events](events.md).

- **[Conventions](conventions.md)** — references (objects, handles, [UI selectors](conventions.md#selector-naming-a-piece-of-the-ui)),
  snapshots vs handles, filters, coordinates, gating.
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
| [`hafen.player`](player.md) | local player data — the anchor for your own Gob |
| [`hafen.time`](time.md) | game clock & astronomy |
| [`hafen.char`](char.md) | attributes, LP, food, skills, credos, lore |
| [`hafen.study`](study.md) | curiosities being studied |
| [`hafen.party`](party.md) | party roster |
| [`hafen.buff`](buff.md) | buffs — `hafen.buff()` is the bar, `hafen.buff(needle)` is one of them |
| [`hafen.meter`](meter.md) | the HUD meter bars — health, stamina, energy and whatever else the server puts there |

### Character-sheet subsystems

| Section | Purpose |
|---|---|
| [`hafen.kin`](kin.md) | kin / buddy roster (read + gated writes) |
| [`hafen.speed`](speed.md) | movement speed (read + gated write) |
| [`hafen.craft`](craft.md) | crafting (read + gated craft) |
| [`hafen.quests`](quests.md) | quest log |
| [`hafen.wounds`](wounds.md) | wounds |
| [`hafen.fight`](fight.md) | combat schools |
| [`hafen.actionbar`](actionbar.md) | action bar / hotbar (read + gated use/set) |

### Acting

| Section | Purpose |
|---|---|
| [`hafen.act`](act.md) | drive the character — move, click, use items, menus. Requires the `actions` permission |
| [`hafen.menugrid`](menugrid.md) | the action menu — enumerate every action the character knows, and invoke one |

### UI & input

| Section | Purpose |
|---|---|
| [`hafen.ui`](ui/README.md) | the Widget object — [selectors](ui/selectors.md), custom windows, overlays, the items in any container, replacing native widgets, [laying them out](ui/native.md), the [stylesheet](ui/style/README.md) that restyles the client (text, the [chrome it draws](ui/style/chrome.md), and [where its widgets sit](ui/style/geometry.md), [anchored](ui/style/geometry.md#anchor) or absolute — and [where all of that ends](ui/style/README.md#where-the-skinning-system-ends)), and walking + hit-testing the tree |
| [`hafen.ghost`](ghost.md) | client-only world props ("ghosts") — base/city planning |
| [`hafen.asset`](asset.md) | load the files your addon ships — images, fonts, glTF models, data — through one door |
| [`hafen.render`](render/README.md) | stand your own (non-`.res`) images and glTF models **in the world** |
| [`hafen.hook`](hook.md) | intercept & alter input / actions / server messages |
| [`hafen.client`](client/README.md) | client settings (interface / video / audio / camera / client), hotkeys, the frame profiler, its counters, per-addon, per-widget and per-render-pass cost, your own named scopes, and what the profiler itself costs |
| [`hafen.font`](font.md) | per-addon typography — get a font handle, draw with it, restyle one widget with [`widget:skin{…}`](ui/style/README.md#restyle-one-widget), and what each client surface does when a [stylesheet](ui/style/README.md) rule lands on it |

### Audio & infrastructure

| Section | Purpose |
|---|---|
| [`hafen.sound`](sound.md) | play sound effects by resource name — stop them, ask what is still playing (there is no `hafen.music`) |
| [`hafen.events`](events.md) | subscribe to events |
| [`hafen.timer`](timer.md) | schedule one-shot / repeating callbacks |
| [`hafen.store`](store.md) | saved variables (persistent storage) |
| [`hafen.json`](json.md) | parse / encode JSON |
| [`hafen.http`](http.md) | external HTTP requests (gated by a `network` allowlist) |
| [`hafen.slash`](slash.md) | console (`:name`) commands |
| [`hafen.log`](log.md) | logging |

---

New to addons? Read the [getting-started guide](../getting-started.md) first.
