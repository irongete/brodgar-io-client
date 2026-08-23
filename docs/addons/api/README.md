# hafen API reference

Every `hafen.*` namespace, one page each, and a directory of pages where a namespace is large. Start with
the conventions — how the API is spelled, what a read gives back, what a write costs — then take the name
you came for from the tables below. Every page in the tree is listed here.

| Page | What it holds |
|---|---|
| [conventions](conventions.md) | the grammar, snapshots vs handles, filters, `nil`, threading, and the permission model |
| [references](references.md) | every kind of thing a verb takes: a Gob, a kin, a slot, an asset, an item, a widget, a selector |
| [shapes](shapes.md) | what a plain table of numbers looks like: places, pixels, sizes, spans, colours, ids and units |
| [data types](types.md) | every snapshot shape a read hands back, field by field |
| [`hafen.session`](session.md) | the logins this client holds, the one on screen, the account each is named by, and the world and character that hang off one |
| [`hafen.event`](event/README.md) | the hub: subscribing, the closed keys and the open ones |
| [the catalogue](event/bus.md) | every event the client fires, and what each one hands your handler |
| [the message streams](event/streams.md) | a message on its way to the server, and an update on its way in |

## Reading the world

| Page | What it holds |
|---|---|
| [`session:world`](world.md) | one character's live world: every object it has loaded, the terrain it stands on, and the clicks and drags it makes on both |
| [Gob](gob.md) | one object in the world — `s:world():gob():get(id)` gives a Gob you read with methods, and `gob:scale(k)` says how big it is drawn |
| [Position](position.md) | a place: the one position type, computable and saveable, that every spatial verb takes |
| [Overlay](overlay.md) | what is drawn at a gob: the game's own, the labels and painters you attach, and what you stood there |
| [`hafen.map`](map/README.md) | the hub: the map you have explored, kept on disk, and the order to read these pages in |
| [segments and grids](map/grids.md) | the shape of the database, the one Grid entity both halves hand back, and storing a place |
| [overlays](map/overlays.md) | the recorded claim and province masks, and the switches that draw them |
| [drawings](map/drawings.md) | a grid as an image handle: the minimap picture, its levels and its cache |
| [markers](map/markers.md) | the map pins: reading them, adding your own, and the Position one travels as |
| [icons](map/icons.md) | the minimap icon registry: which gob icons are drawn, and which announce themselves |

## The player and character

| Page | What it holds |
|---|---|
| [`session:player`](player.md) | one of the characters you are logged in as, and the anchor for its own Gob |
| [`hafen.time`](time.md) | the game clock, the day, the night and the season |
| [`session:char`](char.md) | attributes, learning points, weight, food, skills, credos, lore |
| [`session:study`](study.md) | the study window: the curiosities in it, and their LP and attention |
| [`session:party`](party.md) | the party roster, in party sequence order |
| [`session:buff`](buff.md) | the buffs on the buff bar |
| [`session:meter`](meter.md) | the HUD meter bars — health, stamina, energy, and whatever else the server puts there |

## Character-sheet subsystems

| Page | What it holds |
|---|---|
| [`session:kin`](kin.md) | the kin roster, and the writes that add, rename and re-group |
| [`session:speed`](speed.md) | the crawl, walk, run and sprint selector |
| [`session:craft`](craft.md) | the open recipe window, and its Craft button |
| [`session:quest`](quest.md) | the quest log, current and completed, and a quest's objectives |
| [`session:wound`](wound.md) | the wounds on the Health and Wounds tab, as a tree |
| [`session:fight`](fight.md) | one character's maneuver-deck builder, and who it is fighting |
| [`session:actionbar`](actionbar.md) | the hotbar: read a slot, use it, assign one, hold one for an entry of your own |

## Acting

The verbs that act are on the pages of what they change — [`session:player`](player.md),
[`session:world`](world.md), [items](ui/items.md) and [the Widget object](ui/widget.md) — under a
`Write (protected)` heading, each stating the permission key it needs. The whole catalogue of keys is in
[permissions](../guides/permissions.md). Two catalogues have a page of their own:

| Page | What it holds |
|---|---|
| [`session:menugrid`](menugrid.md) | the action menu: every action the character knows, invoking one, and entries of your own that run your Lua |
| [`session:flowermenu`](flowermenu.md) | the radial menu one character has open: its petals, the object it belongs to, picking one, and when one opens and closes |

## The UI

| Page | What it holds |
|---|---|
| [`hafen.ui`](ui/README.md) | the hub: what is on screen, and the order to read these pages in |
| [custom](ui/custom.md) | your own windows and bare rectangles |
| [overlays](ui/overlay.md) | painting over the screen without owning a widget |
| [controls](ui/controls/README.md) | the hub: what a control is, the roster, and the order to read these pages in |
| [display controls](ui/controls/display.md) | a label, a picture, a separator and a progress bar |
| [interactive controls](ui/controls/interactive.md) | a button, a text entry, a checkbox, a radio, a slider, a scroll and a scrollbar |
| [lists](ui/lists.md) | a listbox, dropdown or menu of rows, and the row source they share with a radio |
| [the Widget object](ui/widget.md) | what every widget answers, which writes owned and borrowed ones take, subscribing on one, tooltips and focus |
| [the mouse](ui/mouse.md) | where the pointer is, what is under it, the modifier keys, and the grab that makes a drag yours |
| [the pixel](ui/pixels.md) | the unit every coordinate and size is measured in, and the scale in force |
| [selectors](ui/selectors.md) | naming a widget: the grammar, the lookups, roles, hit-testing, and the inspector |
| [items](ui/items.md) | the items inside a container, while the window stays live |
| [native widgets](ui/native.md) | placing and hiding the client's own widgets, handing one to the user to drag or size, and the restore that comes with all of it |
| [edit](ui/edit.md) | changing one part of one of the client's windows: taking over what a control does |
| [replace](ui/replace.md) | waiting for a widget to appear, and standing your own window in its place |
| [drawing](ui/drawing.md) | the `g` wrapper: shapes, images, text, and the cache text goes through |

## The stylesheet

| Page | What it holds |
|---|---|
| [the sheet](ui/style/README.md) | `hafen.ui():sheet()`, one widget's own rule, the cascade, and where skinning ends |
| [keys](ui/style/keys.md) | site keys and tree keys: which surfaces a rule reaches, and what each honours |
| [surfaces](ui/style/surfaces.md) | every surface the client ships, and what it does with a rule |
| [the chat](ui/style/chat.md) | the chat window, its five kinds of line, and the two colours it walks |
| [the HUD's plates](ui/style/hud.md) | the five surfaces the client blits whole, and the property that replaces one |
| [text](ui/style/text.md) | `font` and `color` |
| [chrome](ui/style/chrome.md) | `bg`, `border`, `padding`, `picture`, and a window's ornaments |
| [geometry](ui/style/geometry.md) | `position`, `size` and `anchor` |

## The files your addon ships

| Page | What it holds |
|---|---|
| [`hafen.asset`](asset.md) | one loader for every file in your folder — images, fonts, models, data |
| [`hafen.font`](font.md) | a font handle: the built-ins, your own `.ttf`, and drawing with it |

## Your own things in the world

| Page | What it holds |
|---|---|
| [`hafen.vr`](vr/README.md) | the hub: the anchor, the place a thing keeps, the shared verbs, and the switch for the whole section |
| [ghosts](vr/ghosts.md) | the game's own props, standing where you put them, translucent and tinted |
| [sprites](vr/sprites.md) | a PNG in the world: its facing modes, clicks, following a gob |
| [models](vr/models.md) | a glTF model: the subset that loads, the object's verbs, and clicks |
| [widgets](vr/widgets.md) | a window standing in the world: its facing, its clicks, and standing the client's own |

## The client itself

| Page | What it holds |
|---|---|
| [`hafen.client`](client/README.md) | the settings the Options window edits: interface, video, audio, camera, client |
| [keybindings](client/keybindings.md) | the hotkey registry: declare your own, read or remap any |
| [profiling](client/profiling/README.md) | arming the frame profiler, and reading a frame, a history and its overhead |
| [the counters](client/profiling/counters.md) | memory, net, loader, render, what stands in the world, the other sessions, the text cache — readable with it off |
| [attribution](client/profiling/attribution.md) | who spent the frame: addons, your own scopes, widgets, passes, GL |

## Infrastructure

| Page | What it holds |
|---|---|
| [`hafen.timer`](timer.md) | run a function later, once or repeatedly |
| [`hafen.store`](store.md) | saved variables, per character and per account |
| [`hafen.json`](json.md) | parse and encode JSON |
| [`hafen.http`](http.md) | fetch a URL, against the host allowlist your manifest declares |
| [`hafen.locale`](locale.md) | what the client displays: one catalogue of what to draw for the text it would have drawn |
| [`hafen.slash`](slash.md) | subscribe to a `:name` console command |
| [`hafen.log`](log.md) | print a line to the console and the terminal |
| [`hafen.sound`](sound.md) | play a sound effect, stop it, ask what is still playing |

---

New to addons? Write one first: [getting started](../getting-started.md) takes about ten minutes.
