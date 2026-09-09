# Data types: the snapshot shapes

Every plain Lua table `:info()` hands back, field by field, grouped by the subsystem it comes out of. A
read gives you a live object; `:info()` is the one escape hatch that copies it, for logging, serialising
or diffing. A **snapshot** is a point-in-time copy, so it never updates; a field marked *optional* is
absent (Lua `nil`) when the underlying data is not available yet or is still resolving, so guard for it.
See [snapshots vs handles](../conventions.md#snapshots-vs-handles).

> **A snapshot field keeps the client's own spelling; the live read is the verb.** The API's verbs are
> camelCase (`:modified()`, `:qualityInputs()`, `:questsDone()`) because you write them. A snapshot is the
> shape the client holds, handed over as it is — `isplayer`, `mtime`, `qmod` — so what you serialise is
> what the client said. Each table names the verb beside the field wherever the two differ.

## The pages

| Page | What it holds |
|---|---|
| [the session and the world](world.md) | one login, an object in it, the people beside you, the ground, a place, and your own things standing there |
| [the item and what holds it](items.md) | one item, and what a container states about its inside |
| [the character sheet](character.md) | attributes, food, learning, movement speed, quests, wounds and buffs |
| [the fight](fight.md) | a maneuver, a card in the deck, and the deck's totals |
| [the map](map.md) | a pin on the recorded map, and a minimap icon category |
| [the widget layer](ui.md) | a HUD meter, the open recipe, a hotbar slot, an action-menu entry, and a chat channel and its lines |

## Every shape

| Shape | Where it is |
|---|---|
| `ActionbarSlot` | [the widget layer](ui.md#actionbarslot) |
| `Attr` | [the character sheet](character.md#attr) |
| `Buff` | [the character sheet](character.md#buff) |
| `Channel` | [the widget layer](ui.md#channel) |
| `Condition` | [the character sheet](character.md#quest-and-condition) |
| `Contents` | [the item and what holds it](items.md#contents) |
| `Craft` | [the widget layer](ui.md#craft-and-craftspec) |
| `CraftSpec` | [the widget layer](ui.md#craft-and-craftspec) |
| `Credo` | [the character sheet](character.md#skill-credo-experience) |
| `DeckCard` | [the fight](fight.md#maneuver-deckcard-fightsummary) |
| `Experience` | [the character sheet](character.md#skill-credo-experience) |
| `FightSummary` | [the fight](fight.md#maneuver-deckcard-fightsummary) |
| `Food` | [the character sheet](character.md#food) |
| `GobInfo` | [the session and the world](world.md#gobinfo) |
| `IconCategory` | [the map](map.md#iconcategory) |
| `Item` | [the item and what holds it](items.md#item) |
| `KinEntry` | [the session and the world](world.md#kinentry) |
| `Maneuver` | [the fight](fight.md#maneuver-deckcard-fightsummary) |
| `Marker` | [the map](map.md#marker) |
| `Message` | [the widget layer](ui.md#message) |
| `Meter` | [the widget layer](ui.md#meter) |
| `Pagina` | [the widget layer](ui.md#pagina) |
| `PartyMember` | [the session and the world](world.md#partymember) |
| `Petal` | [the widget layer](ui.md#petal) |
| `Position` | [the session and the world](world.md#position) |
| `Quest` | [the character sheet](character.md#quest-and-condition) |
| `ResRef` | [the widget layer](ui.md#craft-and-craftspec) |
| `Session` | [the session and the world](world.md#session) |
| `Skill` | [the character sheet](character.md#skill-credo-experience) |
| `Speed` | [the character sheet](character.md#speed) |
| `StudySlot` | [the character sheet](character.md#studyslot-and-studysummary) |
| `StudySummary` | [the character sheet](character.md#studyslot-and-studysummary) |
| `Tile` | [the session and the world](world.md#tile) |
| `Wound` | [the character sheet](character.md#wound) |
| `WorldEntity` | [the session and the world](world.md#worldentity) |

## See also

- [conventions](../conventions.md#snapshots-vs-handles) — why some readers hand back an object instead
- [shapes](../shapes.md) — the anonymous tables these fields carry: places, sizes, colours, units
- [events](../event/bus/README.md) — which of these shapes arrives as an event payload
- [the API reference](../README.md) — every namespace, and the live reads these copies come off
