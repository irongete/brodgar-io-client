# AddOns

A WoW-style **Lua addon system** for the client. Addons install as folders of Lua files and extend the
client through a stable `hafen.*` API — read the game state, react to events, draw custom UI, add
hotkeys and console commands, and (with permission) drive the character.

## Documentation

- **[Getting started](getting-started.md)** — write your first addon: the manifest, the lifecycle, the
  sandbox, saved variables, permissions, and the developer loop.
- **[API reference](api/README.md)** — the complete `hafen.*` API, one page per section.

## Quick look

```lua
-- addons/hello/main.lua
hafen.events.on("OnEnterWorld", function()
  hafen.log("hello from " .. (hafen.player.name() or "?"))
end)

hafen.key.bind("wave", "Ctrl+W", function()
  hafen.log("nearby players: " .. hafen.world.count("gfx/borka/body"))
end)
```

## The API at a glance

| Area | Sections |
|---|---|
| **World** | [`gob`](api/gob.md) · [`world`](api/world.md) · [`map`](api/map.md) · [`markers`](api/markers.md) · [`radar`](api/radar.md) |
| **Character** | [`player`](api/player.md) · [`time`](api/time.md) · [`char`](api/char.md) · [`study`](api/char.md#hafenstudy) · [`party`](api/party.md) · [`buffs`](api/buffs.md) |
| **Subsystems** | [`kin`](api/kin.md) · [`speed`](api/speed.md) · [`craft`](api/craft.md) · [`quests`](api/quests.md) · [`wounds`](api/wounds.md) · [`fight`](api/fight.md) · [`actionbar`](api/actionbar.md) |
| **Acting** *(gated)* | [`act`](api/actions.md) |
| **UI & input** | [`ui`](api/ui.md) · [`ghost`](api/ghost.md) · [`render`](api/render.md) · [`hook`](api/hooks.md) · [`key`](api/keys.md) · [`font`](api/fonts.md) |
| **Data & network** | [`json`](api/json.md) · [`http`](api/http.md) *(gated)* |
| **Infrastructure** | [`events`](api/events.md) · [`timer`](api/timer.md) · [`store`](api/store.md) · [`slash` / `log`](api/console.md) · [`sound` / `music`](api/audio.md) |

See [conventions](api/conventions.md), [data types](api/types.md), and the [event catalogue](api/events.md)
for the cross-cutting rules.

---

_The [`devlog/`](devlog/) folder holds the original per-task development notes, kept for history. The
docs above are the reference; the devlog is not._
