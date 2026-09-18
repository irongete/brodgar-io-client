# AddOns

A Lua addon system for the client. An addon is a folder of Lua files the client loads at start and runs in a sandbox. Everything it may touch arrives through one `hafen.*` API. Read the game state and react to events. Draw your own UI. Add hotkeys and console commands. Restyle the client. With the user's permission, drive the character.

```lua
-- addons/myaddon/main.lua
hafen.event():on("SessionEnteredWorld", function(session)
  hafen.log():write("hello from " .. (session:character() or "?"))
end)

hafen.timer():every(5, function()
  local session = hafen.session():current()
  if session then hafen.log():write("trees nearby: " .. session:world():gob():count("terobjs/tree")) end
end)
```

That is a whole addon beside a [`manifest.json`](manifest.md) naming it. Nothing above needs a permission, a build step or a restart: drop the folder into `addons/`, type `:reload`, and it runs.

## Where to go

| Page | Read it when |
|---|---|
| [Getting started](getting-started.md) | You have not written one yet: from an empty folder to a window with a hotkey that remembers its state. |
| [The guides](guides/README.md) | You know the shape and want to do a thing: read the world, schedule work, draw UI, save data, add hotkeys, act, theme, translate, debug. |
| [The API reference](api/README.md) | You want a name: every namespace, verb, argument, return and error. |
| [The manifest](manifest.md) | What names an addon: the folder, the manifest field by field, the API version it declares. |
| [The runtime](runtime.md) | When your code runs, the sandbox, the CPU budgets, the console commands. |
| [The AddOns manager](panel.md) | The Installed tab that enables, updates and removes an addon, and the Browse tab that searches the hub. |
| [Dev tools](examples.md) | Three addons to point at your own while you write it, from the addons repository. |

## The API at a glance

One page per namespace, and a directory where a namespace is large. The [reference index](api/README.md) lists every page.

| Area | Namespaces |
|---|---|
| World | [`world`](api/world.md) · [Gob](api/gob.md) · [Position](api/position.md) · [`map`](api/map/README.md) |
| Character | [`player`](api/player.md) · [`time`](api/time.md) · [`char`](api/char.md) · [`study`](api/study.md) · [`party`](api/party.md) · [`buff`](api/buff.md) · [`meter`](api/meter.md) |
| Subsystems | [`kin`](api/kin.md) · [`speed`](api/speed.md) · [`craft`](api/craft.md) · [`quest`](api/quest.md) · [`wound`](api/wound.md) · [`fight`](api/fight.md) · [`actionbar`](api/actionbar.md) · [`chat`](api/chat.md) |
| Acting | [`menugrid`](api/menugrid.md) · [`flowermenu`](api/flowermenu.md), and the protected verbs on [`player`](api/player.md), [`world`](api/world.md) and [items](api/ui/items.md) |
| UI | [`ui`](api/ui/README.md) · [the stylesheet](api/ui/style/README.md) · [`font`](api/font.md) · [`client`](api/client/README.md) |
| Your own content | [`asset`](api/asset/README.md) · [`resource`](api/resource/README.md) · [`virtual`](api/virtual/README.md) · [entries in the action menu](api/menugrid.md#write-unprotected) |
| Data and network | [`json`](api/json.md) · [`http`](api/http.md) · [`websocket`](api/websocket.md) · [`voice`](api/voice/README.md) · [`steam`](api/steam.md) |
| Infrastructure | [`event`](api/event/README.md) · [`timer`](api/timer.md) · [`store`](api/store/README.md) · [`locale`](api/locale.md) · [`console`](api/console.md) · [`log`](api/log.md) · [`sound`](api/sound.md) |

| Page every other page assumes | Covers |
|---|---|
| [Conventions](api/conventions.md) | How the API is spelled and what a read gives back. |
| [Threading](api/threading.md) | Where your handler runs and what it may reach. |
| [References](api/references.md) | Every kind of thing a verb takes. |
| [Shapes](api/shapes.md) | What a plain table of numbers looks like. |
| [Data types](api/types/README.md) | Every snapshot shape. |
| [Events](api/event/bus/README.md) | The catalogue of what the client tells you about. |
