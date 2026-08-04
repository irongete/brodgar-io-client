# hafen API reference

Every `hafen.*` namespace, one page each, and a directory of pages where a namespace is large. Start with
the conventions — how you address a thing, what a read gives back, what a write costs — then take the name
you came for from the tables below. Every page in the tree is listed here.

| Page | What it holds |
|---|---|
| [conventions](conventions.md) | references, snapshots vs handles, filters, coordinates, colours, `nil`, threading, gating |
| [data types](types.md) | every snapshot shape a read hands back, field by field |
| [`hafen.event`](event.md) | the bus, and the catalogue of everything the client tells you about |

## Reading the world

| Page | What it holds |
|---|---|
| [`hafen.world`](world.md) | the live world: every object loaded, the terrain, and a position that survives a relog |
| [Gob](gob.md) | one object in the world — `hafen.world():gob():get(id)` gives a Gob you read with methods, and `gob:overlay()` attaches things to it |
| [`hafen.map`](map/README.md) | the hub: the map you have explored, kept on disk, and the order to read these pages in |
| [segments and grids](map/grids.md) | the shape of the database, the Segment and Grid objects, and saving a position |
| [overlays](map/overlays.md) | the recorded claim and province masks, and the switches that draw them |
| [drawings](map/drawings.md) | a grid as an image handle: the minimap picture, its levels and its cache |
| [markers](map/markers.md) | the map pins: reading them, adding your own, and the anchor one travels as |
| [icons](map/icons.md) | the minimap icon registry: which gob icons are drawn, and which announce themselves |

## The player and character

| Page | What it holds |
|---|---|
| [`hafen.player`](player.md) | the character you are logged in as, and the anchor for your own Gob |
| [`hafen.time`](time.md) | the game clock, the day, the night and the season |
| [`hafen.char`](char.md) | attributes, learning points, weight, food, skills, credos, lore |
| [`hafen.study`](study.md) | the study window: the curiosities in it, and their LP and attention |
| [`hafen.party`](party.md) | the party roster, in party sequence order |
| [`hafen.buff`](buff.md) | the buffs on the buff bar |
| [`hafen.meter`](meter.md) | the HUD meter bars — health, stamina, energy, and whatever else the server puts there |

## Character-sheet subsystems

| Page | What it holds |
|---|---|
| [`hafen.kin`](kin.md) | the kin roster, and the writes that add, rename and re-group |
| [`hafen.speed`](speed.md) | the crawl, walk, run and sprint selector |
| [`hafen.craft`](craft.md) | the open recipe window, and its Craft button |
| [`hafen.quests`](quests.md) | the quest log, current and completed |
| [`hafen.wounds`](wounds.md) | the wounds on the Health and Wounds tab |
| [`hafen.fight`](fight.md) | the out-of-combat maneuver-deck builder |
| [`hafen.actionbar`](actionbar.md) | the hotbar: read a slot, use it, set it |

## Acting

| Page | What it holds |
|---|---|
| [`hafen.act`](act.md) | drive the character — move, click, use items, pick menu entries. Gated by the `actions` permission |
| [`hafen.menugrid`](menugrid.md) | the action menu: every action the character knows, and invoking one |

## The UI

| Page | What it holds |
|---|---|
| [`hafen.ui`](ui/README.md) | the hub: what is on screen, and the order to read these pages in |
| [custom](ui/custom.md) | your own windows, bare rectangles and HUD overlays |
| [the Widget object](ui/widget.md) | what every widget answers, and which writes owned and borrowed ones take |
| [selectors](ui/selectors.md) | naming a widget: the grammar, roles, hit-testing, and the inspector |
| [items](ui/items.md) | the items inside a container, while the window stays live |
| [native widgets](ui/native.md) | placing and hiding the client's own widgets, and the restore that comes with it |
| [replace](ui/replace.md) | waiting for a widget to appear, and standing your own window in its place |
| [drawing](ui/drawing.md) | the `g` wrapper: shapes, images, text, and the cache text goes through |

## The stylesheet

| Page | What it holds |
|---|---|
| [the sheet](ui/style/README.md) | `hafen.ui.skin{…}`, one widget's own skin, the cascade, and where skinning ends |
| [keys](ui/style/keys.md) | site keys and tree keys: which surfaces a rule reaches, and what each honours |
| [surfaces](ui/style/surfaces.md) | every surface the client ships, and what it does with a rule |
| [text](ui/style/text.md) | `font` and `color` |
| [chrome](ui/style/chrome.md) | `bg`, `border` and `pad` |
| [geometry](ui/style/geometry.md) | `pos`, `size` and `anchor` |

## The files your addon ships

| Page | What it holds |
|---|---|
| [`hafen.asset`](asset.md) | one loader for every file in your folder — images, fonts, models, data |
| [`hafen.font`](font.md) | a font handle: the built-ins, your own `.ttf`, and drawing with it |

## Your own things in the world

| Page | What it holds |
|---|---|
| [`hafen.ghost`](ghost.md) | the game's own props, standing where you put them, translucent and tinted |
| [`hafen.render`](render/README.md) | the hub: your own images and models in the 3D world, and the handle-only rule |
| [sprites](render/sprites.md) | a PNG in the world: fixed or camera-facing, clickable, anchored |
| [models](render/models.md) | a glTF model: the subset that loads, the handle, and clicks |

## Intercepting what the client does

| Page | What it holds |
|---|---|
| [`hafen.hook`](hook.md) | pre-hooks over input, actions and server messages, each one cancellable |

## The client itself

| Page | What it holds |
|---|---|
| [`hafen.client`](client/README.md) | the settings the Options window edits: interface, video, audio, camera, client |
| [keybindings](client/keybindings.md) | the hotkey registry: declare your own, read or remap any |
| [profiling](client/profiling/README.md) | arming the frame profiler, and reading a frame, a history and its overhead |
| [the counters](client/profiling/counters.md) | memory, net, loader, render and text-cache counters, readable with it off |
| [attribution](client/profiling/attribution.md) | who spent the frame: addons, your own scopes, widgets, passes, GL |

## Infrastructure

| Page | What it holds |
|---|---|
| [`hafen.timer`](timer.md) | run a function later, once or repeatedly |
| [`hafen.store`](store.md) | saved variables, per character and per account |
| [`hafen.json`](json.md) | parse and encode JSON |
| [`hafen.http`](http.md) | fetch a URL, against the host allowlist your manifest declares |
| [`hafen.slash`](slash.md) | register a `:name` console command |
| [`hafen.log`](log.md) | print a line to the console and the terminal |
| [`hafen.sound`](sound.md) | play a sound effect, stop it, ask what is still playing |

---

New to addons? Write one first: [getting started](../getting-started.md) takes about ten minutes.
