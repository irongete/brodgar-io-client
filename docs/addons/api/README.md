# hafen API Reference

Every `hafen.*` namespace, one page each, and a directory where a namespace is large. Start with the conventions, then take the name you came for from the tables below; every page in the tree is listed here.

| Page | Holds |
|---|---|
| [Conventions](conventions.md) | The grammar, snapshots vs handles, filters, `nil`, and the permission model. |
| [Threading](threading.md) | Where each handler runs, which trees it may reach, and the refusal when it reaches too far. |
| [References](references.md) | Every kind of thing a verb takes: a Gob, a kin, a slot, an asset, an item, a widget, a selector. |
| [Shapes](shapes.md) | What a plain table of numbers looks like: places, pixels, sizes, spans, colours, ids and units. |
| [Data types](types/README.md) | Every snapshot shape a read hands back, field by field. |
| [`hafen.session`](session.md) | The logins this client holds, the one on screen, and the world and character that hang off one. |
| [`hafen.event`](event/README.md) | The hub: subscribing, the closed keys and the open ones. |
| [The catalogue](event/bus/README.md) | Every event the client fires, and what each hands your handler. |
| [The message streams](event/streams.md) | A message on its way to the server, and an update on its way in. |

## The snapshot shapes

| Page | Holds |
|---|---|
| [The session and the world](types/world.md) | One login, an object in it, the people beside you, the ground, a place, your own things standing there. |
| [The item and what holds it](types/items.md) | One item, and what a container states about its inside. |
| [The character sheet](types/character.md) | Attributes, food, learning, movement speed, quests, wounds, buffs. |
| [The fight](types/fight.md) | A maneuver, a card in the deck, the deck's totals. |
| [The map](types/map.md) | A pin on the recorded map, a minimap icon category. |
| [The widget layer](types/ui.md) | A widget, a HUD meter, the open recipe, a hotbar slot, an action-menu entry, a chat channel and its lines. |

## The event families

| Page | Holds |
|---|---|
| [Your addon and the sessions](event/bus/lifecycle.md) | Your addon loaded, ticked and disabled; a character connecting, reaching the world, taking the screen, ending. |
| [The world](event/bus/world.md) | A game object coming and going, what is attached to one, a click on an entity of your own. |
| [The character and the rosters](event/bus/character.md) | Meters, buffs, food, study, equipment, action bar, wounds, kin, quests, map pins, the radial menu, Steam. |
| [The chat](event/bus/chat.md) | A channel appearing, going away or taking the tab, and a line landing in one. |

## Reading the world

| Page | Holds |
|---|---|
| [`session:world`](world.md) | One character's live world: the objects it has loaded, the terrain, and the clicks and drags on both. |
| [Gob](gob.md) | One object in the world, read with methods. |
| [Look](look.md) | How a gob is drawn: `gob:scale(k)`, `gob:visible(flag)`, `gob:tint(color)`, client-local and written on the object. |
| [Materials](materials.md) | The variable-material slots a gob is drawn in: read the server's, dress one in another resource, release it. |
| [Placing](placing.md) | The ghost on the cursor: what you are about to place, where it sits, the ground it will take. |
| [Position](position.md) | The one place type, computable and saveable, that every spatial verb takes. |
| [Overlay](overlay.md) | What is drawn at a gob: the game's own, the labels and painters you attach, what you stood there. |
| [`hafen.map`](map/README.md) | The map you have explored, kept on disk. |
| [Segments and grids](map/grids.md) | The shape of the database, the one Grid entity both halves hand back, storing a place. |
| [Overlays](map/overlays.md) | The recorded claim and province masks, and the switches that draw them. |
| [Drawings](map/drawings.md) | A grid as an image handle: the minimap picture, its levels, its cache. |
| [Markers](map/markers.md) | The map pins: reading them, adding your own, the Position one travels as. |
| [Icons](map/icons.md) | The minimap icon registry: which gob icons are drawn, and which announce themselves. |

## The player and character

| Page | Holds |
|---|---|
| [`session:player`](player.md) | One of the characters you are logged in as, its own Gob, the walk and the cursor. |
| [`hafen.time`](time.md) | The game clock, the day, the night and the season. |
| [`session:char`](char.md) | Attributes, learning points, weight, food, skills, credos, lore. |
| [`session:study`](study.md) | The study window: the curiosities in it, their LP and attention. |
| [`session:party`](party.md) | The party roster, in party sequence order. |
| [`session:chat`](chat.md) | The chat channels, the one on screen, the lines in them, saying a line. |
| [`session:buff`](buff.md) | The buffs on the buff bar. |
| [`session:meter`](meter.md) | The HUD meter bars. |
| [`session:kin`](kin.md) | The kin roster, and the writes that add, rename and re-group. |
| [`session:speed`](speed.md) | The crawl, walk, run and sprint selector. |
| [`session:craft`](craft.md) | The open recipe window, and its Craft button. |
| [`session:quest`](quest.md) | The quest log, current and completed, and a quest's objectives. |
| [`session:wound`](wound.md) | The wounds on the Health and Wounds tab, as a tree. |
| [`session:fight`](fight.md) | The maneuver-deck builder, and who the character is fighting. |
| [`session:actionbar`](actionbar.md) | The hotbar: read a slot, use it, assign one, hold one for an entry of your own. |
| [`session:menugrid`](menugrid.md) | The action menu: every action the character knows, invoking one, entries of your own. |
| [`session:flowermenu`](flowermenu.md) | The radial menu one character has open: its petals, picking one, a petal of your own, whether it is painted. |

The verbs that act are on the pages of what they change, under a **Write (protected)** heading with the key beside the verb; the catalogue of keys is [permissions](../guides/permissions.md).

## The UI

| Page | Holds |
|---|---|
| [`hafen.ui`](ui/README.md) | The hub: what is on screen, and the reading order. |
| [Custom](ui/custom.md) | Your own windows and bare rectangles. |
| [Overlays](ui/overlay.md) | Painting over the screen, or over one widget, without owning either. |
| [Controls](ui/controls/README.md) | What a control is, the roster, the reading order. |
| [Display controls](ui/controls/display.md) | A label, a picture, a separator, a progress bar. |
| [Interactive controls](ui/controls/interactive.md) | A button, a text entry, a checkbox, a radio, a slider, a scroll, a scrollbar. |
| [Columns and rows](ui/column.md) | A surface that lays its children out along one axis and sizes itself to them. |
| [Lists](ui/lists.md) | A listbox, dropdown or menu of rows, and the row source they share with a radio. |
| [The Widget object](ui/widget.md) | What every widget answers, subscribing on one, tooltips and focus. |
| [Writes](ui/writes.md) | Which writes owned and borrowed widgets take, and greying out one you built. |
| [The mouse](ui/mouse.md) | Where the pointer is, what is under it, the modifier keys, the grab that makes a drag yours. |
| [The pixel](ui/pixels.md) | The unit every coordinate and size is measured in, and the scale in force. |
| [Selectors](ui/selectors.md) | Naming a widget: the grammar, the lookups, roles, hit-testing, the inspector. |
| [Items](ui/items.md) | The items the client draws, found through the icons that draw them. |
| [Contents](ui/contents.md) | What one item holds, and how a stack differs from a bucket. |
| [Container](ui/container.md) | An item entering or leaving a container. |
| [Native widgets](ui/native.md) | Placing and hiding the client's own widgets, handing one to the user to drag or size, and the restore. |
| [Edit](ui/edit.md) | Changing one part of one of the client's windows: taking over what a control does. |
| [Replace](ui/replace.md) | Waiting for a widget to appear, and standing your own window in its place. |
| [Drawing](ui/drawing.md) | The `graphics` wrapper: shapes, images, text, and the cache text goes through. |

## The stylesheet

| Page | Holds |
|---|---|
| [The sheet](ui/style/README.md) | `hafen.ui():sheet()`, one widget's own rule, the cascade, where skinning ends. |
| [Keys](ui/style/keys.md) | Site keys and tree keys: which surfaces a rule reaches, and what each honours. |
| [Surfaces](ui/style/surfaces.md) | Every surface the client ships, and what it does with a rule. |
| [The chat](ui/style/chat.md) | The chat window, its kinds of line, and the colours it walks. |
| [The HUD's plates](ui/style/hud.md) | The surfaces the client blits whole, and the property that replaces one. |
| [Text](ui/style/text.md) | `font` and `color`. |
| [Chrome](ui/style/chrome.md) | `bg`, `border`, `padding`, `picture`, and a window's ornaments. |
| [Geometry](ui/style/geometry.md) | `position`, `size`, `anchor` and `margin`. |

## The files your addon ships

| Page | Holds |
|---|---|
| [`hafen.asset`](asset/README.md) | One loader for every file in your folder (images, fonts, models, data), the sandbox, interning. |
| [The collection](asset/collection.md) | Loading, listing and freeing your files, and naming what a folder holds. |
| [The handles](asset/handles.md) | What each kind of loaded file answers. |
| [`hafen.font`](font.md) | A font handle: the built-ins, your own `.ttf`, drawing with it. |
| [`hafen.resource`](resource/README.md) | The client's own resources by name, their [layers](resource/layers.md), and the [writes](resource/writes.md) that change them. |

## Your own things in the world

| Page | Holds |
|---|---|
| [`hafen.virtual`](virtual/README.md) | The anchor, the place a thing keeps, the shared verbs, the switch for the whole section. |
| [Ghosts](virtual/ghosts.md) | The game's own props, standing where you put them, translucent and tinted. |
| [Sprites](virtual/sprites.md) | A PNG in the world: facing modes, clicks, following a gob. |
| [Models](virtual/models.md) | A glTF model: the subset that loads, the object's verbs, clicks. |
| [Widgets](virtual/widgets.md) | A window standing in the world: facing, clicks, standing the client's own. |
| [Patches](virtual/patches.md) | A shape lying flat on the terrain: place, look, border, clicks. |
| [Pieces](virtual/pieces.md) | The convex rings a patch is the union of: what a ring may be, the budget, taking one up. |

## The client itself

| Page | Holds |
|---|---|
| [`hafen.client`](client/README.md) | The settings the Options window edits: interface, video, audio, camera, client. |
| [Keybindings](client/keybindings.md) | The hotkey registry: declare your own, read or remap any. |
| [Your addon's options](client/addon.md) | The options your addon declares, and the page it fills. |
| [Profiling](client/profiling/README.md) | Arming the frame profiler, and reading a frame and its history. |
| [The counters](client/profiling/counters.md) | Memory, net, loader, render, what stands in the world, the other sessions, the text cache; readable with profiling off. |
| [Attribution](client/profiling/attribution.md) | Who spent the frame: addons, your own scopes, widgets, passes, GL, the overhead. |

## Infrastructure

| Page | Holds |
|---|---|
| [`hafen.timer`](timer.md) | Run a function later, once or repeatedly. |
| [`hafen.store`](store/README.md) | Your addon's one file, the three shapes it holds, when it is written and closed, the sandbox, the caps. |
| [Vars](store/vars.md) | A live table you name at `var`, saved for you: the two scopes, what survives, the placements saved for you. |
| [Tables](store/tables.md) | A record: the builder, the Table and its rows, the types both ways, how a declaration evolves. |
| [Statements](store/statements.md) | SQL: `:exec` and `:query`, binding, what is refused, `:transaction`. |
| [`hafen.json`](json.md) | Parse and encode JSON. |
| [`hafen.http`](http.md) | Fetch a URL, against the host allowlist the user approved. |
| [`hafen.websocket`](websocket.md) | Keep a connection to a server on that allowlist, speak on it and hear what it says. |
| [`hafen.voice`](voice/README.md) | A link to a proximity voice server on that allowlist, the declaration it needs, what the server is told, the limits. |
| [The link](voice/link.md) | Building a link, opening it, what it says, what is live, ending it. |
| [The mic and the mix](voice/audio.md) | What you send and what you hear: the settings, whether you are speaking, the counters. |
| [Peers](voice/peers.md) | The players a link relates you to, addressed by Gob, and the keys that follow them. |
| [`hafen.locale`](locale.md) | What the client displays: one catalogue of what to draw for the text it would have drawn. |
| [`hafen.console`](console.md) | Register a `:name` console command, and run a line at one character's console. |
| [`hafen.log`](log.md) | Print a line to the console and the terminal. |
| [`hafen.sound`](sound.md) | Play a sound effect, stop it, ask what is still playing. |
| [`hafen.steam`](steam.md) | The Steam client under the game: the player's identity, and the achievements it holds. |

---

New to addons? [Getting started](../getting-started.md) writes one in about ten minutes.
