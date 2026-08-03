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
  hafen.log("hello from " .. (hafen.player():name() or "?"))
end)

hafen.client:options():keybindings():register("wave", function()
  hafen.log("nearby players: " .. hafen.world.count("gfx/borka/body"))
end)
```

## The API at a glance

| Area | Sections |
|---|---|
| **World** | [`gob`](api/gob.md) · [`world`](api/world.md) · [`map`](api/map.md) · [`markers`](api/markers.md) · [`radar`](api/radar.md) |
| **Character** | [`player`](api/player.md) · [`time`](api/time.md) · [`char`](api/char.md) · [`study`](api/study.md) · [`party`](api/party.md) · [`buff`](api/buff.md) · [`meter`](api/meter.md) |
| **Subsystems** | [`kin`](api/kin.md) · [`speed`](api/speed.md) · [`craft`](api/craft.md) · [`quests`](api/quests.md) · [`wounds`](api/wounds.md) · [`fight`](api/fight.md) · [`actionbar`](api/actionbar.md) |
| **Acting** | [`act`](api/act.md) *(gated)* · [`menugrid`](api/menugrid.md) |
| **UI & input** | [`ui`](api/ui.md) · [`ghost`](api/ghost.md) · [`asset`](api/asset.md) · [`render`](api/render.md) · [`hook`](api/hooks.md) · [`font`](api/fonts.md) · [`client`](api/client.md) |
| **Data & network** | [`json`](api/json.md) · [`http`](api/http.md) *(gated)* |
| **Infrastructure** | [`events`](api/events.md) · [`timer`](api/timer.md) · [`store`](api/store.md) · [`slash` / `log`](api/console.md) · [`sound`](api/audio.md) |

See [conventions](api/conventions.md), [data types](api/types.md), and the [event catalogue](api/events.md)
for the cross-cutting rules — including how you address things: a [Gob](api/gob.md) or a
[Kin](api/kin.md) by object, an item by handle, and a piece of the UI by
[selector](api/ui.md#selectors--naming-a-widget) (`hafen.ui("window[title=Cupboard]")`) — which is also the
key of the [stylesheet](api/ui.md#the-stylesheet--restyling-the-client) that says what the client looks like:
its fonts and colours, the [backgrounds, borders and window chrome](api/ui.md#bg-and-border--the-surfaces-that-paint)
it draws, and [where its windows sit](api/ui.md#pos-and-size--laying-widgets-out-from-the-sheet) — absolutely, or
[anchored](api/ui.md#anchor--a-position-that-is-derived) to a screen edge or another widget. A whole theme can be
a data file rather than code; [where the skinning system ends](api/ui.md#where-the-skinning-system-ends) says what
it deliberately leaves alone.
