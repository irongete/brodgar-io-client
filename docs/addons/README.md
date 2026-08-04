# AddOns

A **Lua addon system** for the client. An addon is a folder of Lua files that the client loads at login
and runs in a sandbox, with everything it may touch arriving through one `hafen.*` API: read the game
state, react to events, draw your own UI, add hotkeys and console commands, restyle the client — and,
with the user's permission, drive the character.

## Quick look

```lua
-- addons/myaddon/main.lua
hafen.event():on("OnEnterWorld", function()
  hafen.log():write("hello from " .. (hafen.player():name() or "?"))
end)

hafen.timer():every(5, function()
  hafen.log():write("trees nearby: " .. hafen.world.count("terobjs/tree"))
end)
```

That is a whole addon, beside a [`manifest.json`](runtime.md#the-manifest) naming it. Nothing above needs
a permission, a build step or a restart: drop the folder into `addons/`, type `:reload`, and it runs.

## Where to go

| Page | Read it when |
|---|---|
| [getting started](getting-started.md) | you have not written one yet: eight steps, from an empty folder to a window with a hotkey that remembers its state |
| [the guides](guides/README.md) | you know the shape and want to do a thing — read the world, schedule work, draw UI, save data, add hotkeys, act, theme, debug |
| [the API reference](api/README.md) | you want a name: every namespace, verb, argument, return and error |
| [the runtime](runtime.md) | the manifest field by field, the sandbox, the CPU budgets, the AddOns panel, and the console commands |
| [the examples](examples.md) | example addons ship with the client, installed and running; read the one that already does what you are doing |

## The API at a glance

One page per namespace, and a directory where a namespace is large. The
[reference index](api/README.md) lists every page.

| Area | Namespaces |
|---|---|
| **World** | [`gob`](api/gob.md) · [`world`](api/world.md) · [`map`](api/map/README.md) |
| **Character** | [`player`](api/player.md) · [`time`](api/time.md) · [`char`](api/char.md) · [`study`](api/study.md) · [`party`](api/party.md) · [`buff`](api/buff.md) · [`meter`](api/meter.md) |
| **Subsystems** | [`kin`](api/kin.md) · [`speed`](api/speed.md) · [`craft`](api/craft.md) · [`quests`](api/quests.md) · [`wounds`](api/wounds.md) · [`fight`](api/fight.md) · [`actionbar`](api/actionbar.md) |
| **Acting** | [`act`](api/act.md) *(gated)* · [`menugrid`](api/menugrid.md) |
| **UI** | [`ui`](api/ui/README.md) · [the stylesheet](api/ui/style/README.md) · [`font`](api/font.md) · [`hook`](api/hook.md) · [`client`](api/client/README.md) |
| **Your own content** | [`asset`](api/asset.md) · [`ghost`](api/ghost.md) · [`render`](api/render/README.md) |
| **Data and network** | [`json`](api/json.md) · [`http`](api/http.md) *(gated by your manifest)* |
| **Infrastructure** | [`event`](api/event.md) · [`timer`](api/timer.md) · [`store`](api/store.md) · [`slash`](api/slash.md) · [`log`](api/log.md) · [`sound`](api/sound.md) |

The three pages every other page assumes are [conventions](api/conventions.md), how you address a thing
and what a read gives back, [data types](api/types.md), every snapshot shape, and
[events](api/event.md), the catalogue of what the client tells you about.
