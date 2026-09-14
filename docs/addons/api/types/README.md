# Data Types & Snapshots

Index of immutable plain-table data snapshots returned by `:info()` methods across the `hafen.*` API.

Snapshots are decoupled from live engine state and safe to store in persistent variables or serialize to JSON.

## Type Reference Catalogs

| Subsystem | Types Reference | Included Types |
|---|---|---|
| **Character** | **[character.md](character.md)** | `Attr`, `Skill`, `Credo`, `Experience`, `Food`, `Speed`, `Buff`, `Meter`, `Wound`. |
| **World** | **[world.md](world.md)** | `GobInfo`, `PositionInfo`, `TerrainTile`. |
| **UI & Windows** | **[ui.md](ui.md)** | `WidgetInfo`, `ChannelInfo`, `PaginaInfo`, `CraftInfo`. |
| **Items & Containers** | **[items.md](items.md)** | `ItemInfo`, `ContainerInfo`. |
| **Map & Navigation** | **[map.md](map.md)** | `GridInfo`, `MarkerInfo`, `IconCategory`. |
| **Combat** | **[fight.md](fight.md)** | `Maneuver`, `DeckCard`, `FightSummary`, `Opponent`. |
