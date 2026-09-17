# Data Types: The Snapshot Shapes

The plain Lua tables `:info()` copies out of a live object, field by field, grouped by the subsystem each comes from: a point-in-time copy for logging, serialising or diffing that never updates. A field marked *optional* is absent (`nil`) when the data is not available or still resolving; guard for it ([snapshots vs handles](../conventions.md#snapshots-vs-handles)).

```lua
local gob = hafen.session():current():player():gob()
local snapshot = gob:info()                                   -- a plain table, frozen now
hafen.log():write(hafen.json():encode(snapshot))              -- what a live Gob cannot do
```

---

| Rule | Detail |
|---|---|
| A catalogue, not a census | Every object answers `:info()`; a shape one page reads (a timer, a keybinding, an option row) is stated in that page's verb row. A shape lands here when more than one page names it, or when a table beats a sentence. |
| A snapshot keeps the client's own spelling | The verbs are camelCase (`:modified()`, `:qualityInputs()`, `:questsDone()`); a snapshot is the shape the client holds (`isplayer`, `mtime`, `qmod`). Each table names the verb beside the field where the two differ. |

## The pages

| Page | Holds |
|---|---|
| [The session and the world](world.md) | One login, an object in it, the people beside you and the ones a voice link relates you to, the ground, a place, your own things standing there. |
| [The item and what holds it](items.md) | One item, and what a container states about its inside. |
| [The character sheet](character.md) | Attributes, food with its FEP and hunger halves, learning, movement speed, quests, wounds, buffs. |
| [The fight](fight.md) | A maneuver, a card in the deck, the deck's totals. |
| [The map](map.md) | A pin on the recorded map, a minimap icon category. |
| [The widget layer](ui.md) | A widget in the tree, a HUD meter and one band of its bar, the open recipe, a hotbar slot, an action-menu entry, a chat channel and its lines. |

## Every shape

| Shape | Page |
|---|---|
| `ActionbarSlot` | [The widget layer](ui.md#actionbarslot) |
| `Attr` | [The character sheet](character.md#attr) |
| `Buff` | [The character sheet](character.md#buff) |
| `Channel` | [The widget layer](ui.md#channel) |
| `Condition` | [The character sheet](character.md#quest-and-condition) |
| `Contents` | [The item and what holds it](items.md#contents) |
| `Craft` | [The widget layer](ui.md#craft-and-craftspec) |
| `CraftSpec` | [The widget layer](ui.md#craft-and-craftspec) |
| `Credo` | [The character sheet](character.md#skill-credo-experience) |
| `DeckCard` | [The fight](fight.md#maneuver-deckcard-fightsummary) |
| `Experience` | [The character sheet](character.md#skill-credo-experience) |
| `Fep` | [The character sheet](character.md#food) |
| `FepEntry` | [The character sheet](character.md#food) |
| `FightSummary` | [The fight](fight.md#maneuver-deckcard-fightsummary) |
| `Food` | [The character sheet](character.md#food) |
| `GobInfo` | [The session and the world](world.md#gobinfo) |
| `Hunger` | [The character sheet](character.md#food) |
| `IconCategory` | [The map](map.md#iconcategory) |
| `Item` | [The item and what holds it](items.md#item) |
| `KinEntry` | [The session and the world](world.md#kinentry) |
| `Maneuver` | [The fight](fight.md#maneuver-deckcard-fightsummary) |
| `Marker` | [The map](map.md#marker) |
| `Message` | [The widget layer](ui.md#message) |
| `Meter` | [The widget layer](ui.md#meter) |
| `MeterSegment` | [The widget layer](ui.md#metersegment) |
| `Pagina` | [The widget layer](ui.md#pagina) |
| `PartyMember` | [The session and the world](world.md#partymember) |
| `Peer` | [The session and the world](world.md#peer) |
| `Petal` | [The widget layer](ui.md#petal) |
| `Position` | [The session and the world](world.md#position) |
| `Quest` | [The character sheet](character.md#quest-and-condition) |
| `ResRef` | [The widget layer](ui.md#craft-and-craftspec) |
| `Session` | [The session and the world](world.md#session) |
| `Skill` | [The character sheet](character.md#skill-credo-experience) |
| `Speed` | [The character sheet](character.md#speed) |
| `StudySlot` | [The character sheet](character.md#studyslot-and-studysummary) |
| `StudySummary` | [The character sheet](character.md#studyslot-and-studysummary) |
| `Tile` | [The session and the world](world.md#tile) |
| `Widget` | [The widget layer](ui.md#widget) |
| `Wound` | [The character sheet](character.md#wound) |
| `WorldEntity` | [The session and the world](world.md#worldentity) |

---

## See Also

- [Conventions](../conventions.md#snapshots-vs-handles) — why readers hand back an object instead.
- [Shapes](../shapes.md) — the anonymous tables these fields carry: places, sizes, colours, units.
- [Events](../event/bus/README.md) — which of these shapes arrives as an event payload.
- [The API reference](../README.md) — every namespace, and the live reads these copies come off.
