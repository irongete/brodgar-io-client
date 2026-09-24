# API Conventions: The Shared Vocabulary

The rules that hold across the whole `hafen.*` API: how it is spelled, what a read gives back, what a write costs. Every reference page assumes this one. The catalogue of things a verb can be handed is [references](references.md). What a plain table of numbers looks like is [shapes](shapes.md).

```lua
local session = hafen.session():current()          -- a section is called; everything after is a colon verb
local window = hafen.ui():window():title("Scout")  -- one argument writes, and hands the object back
hafen.log():write(window:title())                  -- no argument reads
for _, gob in ipairs(session:world():gob():list("rabbit")) do   -- :list(filter) is the array
  hafen.log():write(tostring(gob:position()))
end
```

---

## The grammar

A section is called, everything after it is a colon verb, and arity is the verb. A section groups one subsystem and owns one reference page. One large enough for several pages owns a folder with a hub.

### Sections: you call one

| Rule | Detail |
|---|---|
| The section object | `hafen.time` is the section, `hafen.time()` the section object, every verb a colon call on it (`hafen.time():clock()`). The same object every time: `hafen.time() == hafen.time()`, and calling a section in a draw callback allocates nothing. |
| No arguments | Where a section holds one thing, the section object is that thing. `hafen.timer()` is the collection of your timers, `hafen.console()` your console commands, `hafen.session()` the logins the client holds. |
| Reached, not mounted | What names one character's state hangs off the [Session](session.md) that names the character, one call further in: `session:world()`, `session:player()`. Everything else about it is the rule above unchanged. |

### Verbs: arity is the verb

A verb with no argument reads. The same verb with one writes and hands the object back, so writes chain: `window:title()` reads, `window:title("Scout"):size(180, 48)` writes twice.

| Rule | Detail |
|---|---|
| One name per property | No `getX`, `setX` or `clearX`. A boolean is written `window:visible(true)` and named as a bare adjective, never `isX`: `kin:online()`, `gob:player()`. |
| A boolean argument is `true` or `false` | In Lua `0` and `"no"` are true, so a verb taking Lua's word would read `window:visible(0)` as *show it* and `video:shadows("no")` as *shadows on*. The type is what is asked, as for a [string and a number](#a-number-is-not-a-string-and-a-numeric-string-is-not-a-number). What your own callback returns keeps Lua's answer: a filter ending in `return gob:name()` is an ordinary filter. |
| A surplus argument raises | `kin:online(1)` and `binding:key("F5", "x")` raise naming how many arguments the verb takes and how many it got. |
| Arguments that are not writes | Addressing (`collection:get(key)`, `request:header(name)`, `session:world():grid():at(position)`). Actions (`item:drop(count)`, `session:world():place(position, angle, button, mods)`, `sound:play(volume)`). Conversions (`position:offset(dx, dy)`, `position:distance(other)`, `grid:tile(cell)`). |

### Collections: the noun is the kind, the verb is how many

A set you can address into is reached by the singular kind name and hands back a collection object, never a bare array. `:list()` is the enumerating verb wherever it appears (the control named for a list is `hafen.ui():listbox()`).

| Verb | Gives you |
|---|---|
| `:list(filter)` | A plain array of members, empty rather than `nil`. |
| `:count(filter)` | How many. |
| `:find(filter)` | The first member that matches, or `nil`. |
| `:get(key)` | One member by its key, where members have keys. A miss is [below](#get-what-a-key-that-names-nothing-answers). |
| `:add(...)` | A new member, where the collection can create one. |
| `:remove(key_or_member)` | The collection, so removals chain, where it can destroy one. |

| Rule | Detail |
|---|---|
| A distinguished member is a verb on its collection | `:current()`, `:selected()`, `:leader()`, `:pursuing()`. The member carries no flag of its own, since objects are interned: `session:party():leader() == member` is the exact test. |
| One object, or a view | A section's collection is one object every call (`hafen.map():marker()`). A collection off a thing (`gob:overlay()`, `meter:segment()`, `quest:conditions()`) or a partition (`session:char():skill():buyable(filter)`) is re-derived per call. Two calls are not `==`. Neither outlives what it came off. Identity lives on members that carry a key: `gob:overlay():get("tag")` is one overlay. |
| An object, not a sequence | `#collection`, `collection[1]`, `pairs(collection)` and `ipairs(collection)` are refused naming what to write: `collection:list()` is the array. |
| A store table | A collection whose members are rows and whose filter is a SQL clause: `:list(clause, ...)`, `:count(clause, ...)`, `:find(clause, ...)` take what follows `FROM <table>` with a value per `?`. `:get(key, ...)` addresses a row. `:put(row)` stands where `:add` would ([tables](store/tables.md)). |

### get: what a key that names nothing answers

| A miss gives you | Collections |
|---|---|
| `nil` | Every collection not named below. |
| An object, so [`:exists()`](#objects-and-the-snapshot-hatch) is the question | `hafen.session()`, `hafen.sound()`, `session:world():gob()`, `session:actionbar()`, `keybindings():binding()`, `hafen.client():addons()`. |
| An error naming the keys there are | `hafen.asset()`, `hafen.font()`, `session:char():attr()`, `hafen.map():display()`. |

A collection whose members have no key has no `:get` and says what to reach for. Two buffs can share a resource. A marker's only id is one this client mints. A timer is only the one you were handed. `hafen.timer():get(1)` raises: `hafen.timer() has no verb 'get' — a timer has no key: hafen.timer():after(s, fn) and hafen.timer():every(s, fn) hand you the timer they make, and hafen.timer():list() is every one of yours`.

### Every index is 1-based

`:list()` hands back a 1-based array, and the position a member reports is its position in that array: `session:actionbar():get(1) == session:actionbar():list()[1]`, `session:speed():get(1)` is crawl. Where the wire's number differs it is a verb of its own: `slot:index()` is the position and `slot:wire()` the number the server's `setbelt` carries. `card:index()`/`card:wire()` and `speed:index()`/`speed:wire()` are the same pair. Nothing in this API takes a wire number.

### Endings: the receiver's kind picks the word

Nothing has to be ended: what your addon takes is given back on reload or disable. An ending is what you write to end it now.

| Verb | Ends | Called on |
|---|---|---|
| `:off()` | A subscription. | The `Sub` any [`:on`](event/README.md#subscribe) handed you. |
| `:remove(key_or_member)` | A member of a collection. | The [collection](#collections-the-noun-is-the-kind-the-verb-is-how-many), never the member. |
| `:release()` | A layer or a hold over what the client owns. | A [rule](ui/style/README.md#restyle-one-widget), a [sheet](ui/style/README.md), a [grab](ui/mouse.md), a [map-overlay hold](map/overlays.md). |
| `:destroy()` | A thing your addon built. | A [widget](ui/widget.md). |
| `:cancel()` | Something in flight. | A [timer](timer.md), a [request](http.md), the open [radial menu](flowermenu.md). |
| `:stop()` | A [sound](sound.md) in the air. | The sound. |
| `:close(code, reason)` | A [connection](websocket.md) to a server. | The connection. |
| `:close()` | A [voice link](voice/link.md). | The link. |
| `:finish()` | A [profiling scope](client/profiling/attribution.md). | The scope. |

| Rule | Detail |
|---|---|
| The collection ends its members | `hafen.asset():remove(asset)` frees a file, `hafen.session():remove(session)` ends a login. What is a member of nothing ends on itself. |
| Endings chain | Every ending hands the receiver back. Where a page says one is idempotent, a second call answers the receiver again. |
| Not endings | [`item:drop(count)`](ui/items.md#write-protected) is a game action. [`session:close()`](session.md#write-protected) is the login's own act. [`widget:revert()`](ui/edit.md#taking-the-whole-edit-back), [`widget:replace(nil)`](ui/replace.md) and `widget:size(nil)` undo your layer. |

### Events: a subject and an edge

An event key is a subject and an edge. A new key takes the first edge below true of the moment it names. Where none is (`Load`, `Disable`) the word is the moment.

| Edge | Word | Examples |
|---|---|---|
| It appeared | `Added` | `GobAdded`, `BuffAdded`, `SessionAdded`, `FlowerMenuAdded`, `Added` on a [selector watch](ui/replace.md#watching-for-a-widget). |
| It went | `Removed` | `GobRemoved`, `MeterRemoved`, `SessionRemoved`, `Removed` on a [widget](ui/widget.md#subscribing). |
| It changed | `Changed` | `MeterChanged`, `KinChanged`, `MarkerChanged`. |
| It crossed a threshold | `EnteredWorld` | `SessionEnteredWorld`. |
| It was picked | `Selected` | `SessionSelected`, `ChannelSelected`. |
| It was clicked | `Clicked` | `GhostClicked`, `SpriteClicked`, `ObjectClicked`, `PatchClicked`. |

| Rule | Detail |
|---|---|
| The subject is singular | `MarkerChanged`, though the handler is given the whole collection. |
| An outcome is a key | `QuestCompleted` and `QuestFailed`, not one event and a field to check. |
| One word per edge at every level | `Update` on [the bus](event/bus/lifecycle.md#lifecycle) and on [a surface of yours](ui/custom.md#subscribing) hand the same `dt`. |
| Open sets are lower case | A [console command](console.md), a [hotkey](client/keybindings.md), a [`wdgmsg` or `uimsg` name](event/streams.md) is a name you or the protocol chose. Every key the client fires is PascalCase and closed: an unknown one [raises](#a-name-that-moved-says-where-it-went-and-an-unknown-one-says-what-exists). |

### Objects, and the snapshot hatch

A read hands back a live object: it re-resolves on every call, answers `nil` once what it names is gone, and reports `:exists()`. Objects are interned per addon, so `==` is the identity test and one works as a table key. A point-in-time copy is `:info()` and nothing else. Every shape is in [data types](types/README.md).

### nil is an error unless it means something

An explicit `nil` argument raises: a value that arrived as `nil` would otherwise turn the write into a read.

| `nil` means | Where |
|---|---|
| Undo your layer, back to the rule beneath, else to the stock: the client's own, or your builder's default | `widget:position(nil)`, `widget:size(nil)`, `widget:text(nil)`, `widget:title(nil)`, `widget:replace(nil)`. A [font](font.md) variant's `handle:size(nil)` and `handle:aa(nil)`. |
| End the hold | `slot:hold(nil)`. |
| Withdraw the page | [`options:panel(nil)`](client/addon.md#the-page). |
| Unbind the control | [`widget:bind(nil)`](client/addon.md#binding-a-control-shows-the-option). |
| None | A [virtual entity](virtual/README.md)'s `:tint(nil)`, [`gob:tint(nil)`](look.md#tint-unprotected), [`gob:outline(nil)`](look.md#outline-unprotected). |
| The top of the AddOns category | [`pagina:parent(nil)`](menugrid.md#a-category-is-an-entry-that-has-children). |
| The login screen | [`hafen.session():current(nil)`](session.md#hafensessioncurrentnil). |
| The pointer the client would have drawn | [`mouse:cursor(nil)`](ui/mouse.md). |
| Everything | A [filter](#the-filter-argument): `collection:list(nil)`, `:count(nil)`, `:find(nil)`. |
| `NULL` | A value bound to a `?` of a [statement](store/statements.md) or a store table's [clause](store/tables.md#the-clause). A column a [`:put` row](store/tables.md#write-unprotected) leaves out. |

| Rule | Detail |
|---|---|
| Counted, not guessed | The bridge separates the cases by counting arguments, exact for a value passed directly, a table field included: `widget:size(config.width, config.height)` with a missing key raises. The one gap: `f(g())` where `g` returns nothing arrives as `f()`. A `g` returning an explicit `nil` is refused. |
| Optional arguments | Leaving one out takes the default. `nil` in a slot you did pass raises: `session:world():place(position, angle, nil, 0)` passes a third argument. |

### A number is not a string, and a numeric string is not a number

The type is checked, never what Lua would convert to. `session:kin():add(1234)` is refused, since a hearth secret is a string. `entry:value("42")` is taken. A radio row may be labelled `"061.8"`. The refusal names the verb, the parameter and the conversion you meant (`tostring(n)`, `tonumber(s)`).

### A number is finite, and an index is whole

| Rule | Detail |
|---|---|
| Finite | `0/0` and `math.huge` are numbers by type, and every range test is false for `0/0` on both sides. So a number argument is checked for being finite. |
| Whole | An index, an id, a count or a number of design pixels is a whole number. `session:speed():get(2.7)` raises naming the verb, the parameter and the number. |
| The exception | `x, y` and the lengths the [draw verbs](ui/drawing.md) take are finite without being whole: sub-pixel on purpose, rounded once where they meet the screen. |

### A table is a value, never named arguments

A table you pass is data: a colour, a coordinate, a document. A thing you build is constructed bare and configured by chained setters, so configuration reads in order and a setter can refuse. A request carries `request:header(name, value)` rather than an options table. What a verb returns (a `:list()` array, an `:info()` table) is an ordinary Lua table. A document names a file by path and a Lua call takes the handle. A [stylesheet](ui/style/README.md) is a document, so `{asset = "img/panel.png"}` and `{asset = "fonts/Inter.ttf", size = 12}` load through [`hafen.asset()`](asset/README.md) and intern to the object `:get(path)` hands you. Everywhere else (a [sprite](virtual/sprites.md), an [object](virtual/models.md), a [draw verb](ui/drawing.md)) a path is refused.

### A name that moved says where it went, and an unknown one says what exists

| Rule | Detail |
|---|---|
| A moved name raises | At the line that wrote it, naming what to write instead. |
| An unknown name on an object raises | `gob:pozition()` and `gob.pozition` alike (a field read and a colon call are one lookup): `gob has no verb 'pozition' — a gob is one thing in the world: it answers :id() :exists() :sessions() :info() :position() :facing() :name() … and :distance()`. |
| On `hafen` and on a section a miss reads `nil` | Where a feature probe asks: `if hafen.something then` keeps working. |

## Several logins, one screen

The client holds more than one account and draws one. What belongs to one character is reached through the [Session](session.md) that names it ([`session:world()`](world.md), [`session:player()`](player.md), [`session:kin()`](kin.md)). A read says which character it is about. What belongs to the screen stays where it was: one pointer, one scene. Your addon is the client's, loaded once beside every session, and nothing of yours is torn down when the screen moves.

## Snapshots vs handles

| Kind | Detail |
|---|---|
| Snapshots | Plain Lua tables, point-in-time copies from `:info()` readers (`gob:info()`, `item:info()`). They do not update: re-read rather than caching across ticks. Shapes in [data types](types/README.md). |
| Handles | Live, bridge-owned proxies with methods (`hafen.ui():window()`, `hafen.timer():every`, `hafen.event():on`), released on disable or reload. So is every object a read hands you, re-resolving rather than holding a value. Userdata with a closed vocabulary. An unknown name raises naming what it answers. Nothing can be written onto it (nothing deletes a handle's own `:cancel()`). `tostring(handle)` names the thing (`Timer(every 5s)`, `Options(video)`, `Sub(GobAdded)`). |
| A table the bridge owns and you write into | [`hafen.store():var(name)`](store/vars.md#read-and-write): neither copy nor proxy but the table that goes to disk. Assigning into it is saving. It is the one place a typo on a key is silent and persisted. |

## The filter argument

Every enumerating verb takes one optional filter in the same form.

| `filter` | Keeps |
|---|---|
| `nil` (omitted) | Everything. |
| A string | Entries whose `name` contains the string (substring match). |
| A function | Entries for which `filter(entry)` returns truthy. `entry` is the object, never a snapshot. |

```lua
local gobs = hafen.session():current():world():gob()
local rabbits = gobs:list("rabbit")                                          -- name contains "rabbit"
local injured = gobs:list(function(gob) return (gob:health() or 1) < 1 end)  -- a Gob object
local pins = hafen.map():marker():list(function(marker) return marker:type() == "player" end)
```

| Rule | Detail |
|---|---|
| Members with no name | A party member, a segment, a timer: a string is refused naming the forms that work. Use a function. |
| A store table | The filter is the SQL [clause](store/tables.md#the-clause). A function is refused naming SQL. |
| A name not arrived yet | Does not match and does not spoil the call. A mistake inside your predicate raises out of the verb. |

## Missing data returns nil

A read returns `nil`, or an empty table for a list verb, when the data is not available yet. That is before the world loads, before a HUD widget streams in, while a resource resolves. Reads never throw a loading error. Much character-sheet data (meters, food, skills, quests, wounds) streams in shortly after `SessionEnteredWorld`: read it on a timer or subscribe to its [event](event/bus/README.md).

## Threading

Your Lua runs either on the step or answering something. On the step it is inside no character's UI and reaches every one. Answering something (a draw, a press, a drop, a console line) it is inside the one character's UI that dispatched it. That is the only one it may reach. Taking a second is refused at the line that tries. Nothing blocks. [Threading](threading.md) says which handler is which.

## The permission model

| Rule | Detail |
|---|---|
| Protected | A verb that starts an action the player could have performed runs only with its permission key. Your addon declares the key in the [manifest](../manifest.md#the-manifest) and the user enables it. Such an addon is disabled the first time the client sees it and enabling it raises a consent dialog. One that never declared the key gets an error naming the verb and the key. |
| A key names the action, not the character | One grant covers every character the client holds. |
| Named `<section>.<verb>` | `gob.click`, `item.transfer`. `<prefix>.*` asks for the family in one line. No key grants the tier whole. |
| Outside the client, the same words | `http.get`, `http.post`, `websocket.connect`, `voice.connect`, with the `network` host allowlist as the key's argument. The key says whether, the hosts where, read as one consent line. The approved list gates the call. |
| A protected verb lives with the thing it changes | Walking is on the character, clicking on the gob: the page you look a verb up on holds its key under **Write (protected)**. |
| The line is what a verb does | [`map.marker`](map/markers.md#write-protected) deletes a pin no server restores, [`client.settings`](client/README.md) rewrites every hotkey. Both reach nothing outside the client and are protected. Everything else observes or writes client-local and undoable, `(unprotected)`. |

---

## See Also

- [References](references.md) — every kind of thing a verb takes, and how you name one.
- [Shapes](shapes.md) — what a plain table of numbers looks like: places, sizes, colours, units.
- [Data types](types/README.md) — every snapshot shape the readers return.
- [Events](event/bus/README.md) — the bus, and what each event hands your handler.
- [Permissions](../guides/permissions.md) — the protected tier in full.
