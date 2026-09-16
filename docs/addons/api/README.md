# API Reference

Complete reference for all `hafen.*` namespaces, method signatures, parameters, return types, and permission requirements.

## Core Conventions
Before calling API methods, review the core conventions:
* **[Conventions](conventions.md)**: Calling syntax, property getters/setters, collection verbs, and error handling.
* **[References](references.md)**: Object types, handles vs snapshots, and identity.
* **[Shapes](shapes.md)**: Data structures for coordinates, dimensions, bounds, and colors.
* **[Threading & Execution](threading.md)**: How addon callbacks execute across client frame ticks.

---

## Namespaces Directory

### Character & World
| Namespace | Description |
|---|---|
| **[`session`](session.md)** | Character logins, active session selection, and multi-character management. |
| **[`player`](player.md)** | Character stats, current action, inventory, movement, and cursor hand. |
| **[`world`](world.md)** | World entity queries, terrain coordinate conversion, and area interactions. |
| **[`gob`](gob.md)** | Game Object (Gob) inspection, position, velocity, and visual overlays. |
| **[`gob/look`](look.md)** | Visual overrides on game objects (model scaling, tinting, and visibility toggling). |
| **[`gob/overlay`](overlay.md)** | Floating overhead text labels and custom canvas drawings attached to game objects. |
| **[`position`](position.md)** | Spatial coordinate representation and distance calculations. |
| **[`map`](map/README.md)** | Minimap and world map tiles, pins, markers, and path overlays. |

### Gameplay Subsystems
| Namespace | Description |
|---|---|
| **[`char`](char.md)** | Character attributes, abilities, beliefs, and experience. |
| **[`meter`](meter.md)** | Health, stamina, energy, and water meters. |
| **[`buff`](buff.md)** | Active status effects, debuffs, and duration timers. |
| **[`wound`](wound.md)** | Character physical damage, treated wounds, and medical status. |
| **[`fight`](fight.md)** | Combat stance, combat cards, opponents, and target locking. |
| **[`study`](study.md)** | Curiosities study desk, attention capacity, and learning points. |
| **[`craft`](craft.md)** | Crafting recipes, ingredients, and recipe tracking. |
| **[`quest`](quest.md)** | Personal quests, quest tree conditions, and completion progress. |
| **[`kin`](kin.md)** | Friends list, village members, villages, and kinship groupings. |
| **[`party`](party.md)** | Active adventuring party members and health sharing. |
| **[`actionbar`](actionbar.md)** | Hotbar action slots, key allocations, and triggers. |
| **[`menugrid`](menugrid.md)** | Main gameplay radial action menu (adventure, build, craft). |
| **[`flowermenu`](flowermenu.md)** | Contextual radial menus when right-clicking objects. |
| **[`placing`](placing.md)** | Blueprint placement and construction grid snapping. |
| **[`speed`](speed.md)** | Character movement speed modes (crawl, walk, run, sprint). |
| **[`chat`](chat.md)** | In-game chat tabs, message history, channel selection, and posting lines. |

### UI & Styling
| Namespace | Description |
|---|---|
| **[`ui`](ui/README.md)** | Windows, custom canvas widgets, buttons, checkboxes, text fields, and layout columns. |
| **[`ui/style`](ui/style/README.md)** | CSS-like stylesheet engine for styling native and custom widgets. |
| **[`font`](font.md)** | TrueType font loading and text measurement. |
| **[`client`](client/README.md)** | Native client settings, window properties, and keybinding management. |

### Data, Networking & Utilities
| Namespace | Description |
|---|---|
| **[`event`](event/README.md)** | Event bus subscriptions (`hafen.event():on(...)`). |
| **[`timer`](timer.md)** | Periodic timers (`:every`) and delayed one-shots (`:after`). |
| **[`store`](store/README.md)** | Addon and character persistent storage backed by SQLite. |
| **[`log`](log.md)** | Log output to game chat and system terminal. |
| **[`console`](console.md)** | Custom in-game `:` console commands. |
| **[`time`](time.md)** | Game world clock, calendar seasons, and daytime tracking. |
| **[`locale`](locale.md)** | Translation dictionaries and multi-language support. |
| **[`json`](json.md)** | Fast JSON encoding and decoding. |
| **[`http`](http.md)** | Outbound HTTP/HTTPS requests (GET, POST). |
| **[`websocket`](websocket.md)** | Full-duplex WebSocket connections. |
| **[`sound`](sound.md)** | Playing custom sound effects and audio clips. |
| **[`asset`](asset/README.md)** | Loading images, textures, and assets shipped in the addon folder. |
| **[`resource`](resource/README.md)** | The client's own resources by name, and their [layers](resource/layers.md). |
| **[`virtual`](virtual/README.md)** | Spawning client-side visual ghosts and custom map models. |
| **[`voice`](voice/README.md)** | Proximity voice chat audio and peer streaming. |
| **[`steam`](steam.md)** | Steam client integration, player identity, and Steam achievements. |

### Data Types & Snapshots
| Catalog | Description |
|---|---|
| **[`types`](types/README.md)** | Overview and index of plain table data snapshots decoupled from live engine state. |
| **[`types/character`](types/character.md)** | Character attributes, skills, credos, experience, food, meters, and wounds. |
| **[`types/world`](types/world.md)** | Game objects, spatial coordinates, and terrain tile snapshots. |
| **[`types/ui`](types/ui.md)** | Widget, chat channel, action page, and craft recipe descriptors. |
| **[`types/items`](types/items.md)** | Item and container layout snapshots. |
| **[`types/map`](types/map.md)** | Grid coordinates, map markers, and icon categories. |
| **[`types/fight`](types/fight.md)** | Combat maneuvers, deck cards, fight summaries, and opponents. |

