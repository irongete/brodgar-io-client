# Event Bus Catalog

The event bus (`hafen.event():on(event_name, callback)`) dispatches high-level semantic notifications across client subsystems.

## Event Categories

| Category | Events Reference | Description |
|---|---|---|
| **[Lifecycle](lifecycle.md)** | `Load`, `Disable`, `Update`, `SessionEnteredWorld`, `SessionSelected`, `SessionRemoved` | Client startup, shutdown, frame ticks, and character logins. |
| **[World](world.md)** | `GobAdded`, `GobRemoved`, `FlowerMenuAdded`, `FlowerMenuRemoved` | Object spawning, despawning, and radial flower menu interactions. |
| **[Character](character.md)** | `MeterChanged`, `BuffAdded`, `BuffRemoved`, `FepChanged`, `WoundAdded`, `StudyChanged` | Player vital stats, food buffs, wounds, and curiosity learning. |
| **[Chat](chat.md)** | `ChatMessage` | Incoming chat lines and private messages. |
