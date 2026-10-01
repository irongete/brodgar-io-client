# session:fight: Combat Schools and the Fight in Progress

One character's manoeuvre-deck builder (its Martial Arts and Combat Schools tab) and the fight it is in, reached through its [session](session.md). It answers what the character knows, its saved schools, what the loaded one has dealt to each hotkey and what that costs, and, while it fights, every opponent, the numbers the fight paints between you, the openings drawn beside each side, the combat row with its cooldowns and the manoeuvre each side used last.

```lua
local session = hafen.session():current()                      -- the character on screen
local target = session:fight():opponent():current()            -- nil out of a fight
if target then
  local ip = target:ip()                                       -- { mine = …, theirs = … }
  hafen.log():write("IP " .. ip.mine .. " against " .. ip.theirs .. " on " .. target:id())
end
hafen.event():on("OpponentAdded", function(opponent, fight_session)
  hafen.log():write(fight_session:character() .. " is fighting " .. opponent:id())
end)
```

---

| Rule | Detail |
|---|---|
| One character's | Every character configures its own deck against its own budget and fights its own fight: `hafen.session():get("alt"):fight():opponent():current()` answers about the alt. A hotkey slot, a school's slot and an opponent's gob id count inside one character, so a `DeckCard`, a `School` and an `Opponent` carry their character beside their key. |
| `opponent:gob()` answers in the login asked through | That character's own world, where the id came from: `opponent:gob():exists()` is that character's line of sight, not the screen's. |
| Before the tab has built, and out of a fight | `:maneuver():list()`, `:deck():list()` and `:school():list()` are empty, and `:summary()` and `:school():current()` are `nil`, until the tab has built. Out of a fight, `:opponent()` and `:opening()` are empty, every `:action()` is `:empty()`, and `:opponent():current()`, `:cooldown()` and `:last()` are `nil`. |
| Reads are unprotected | Nothing throws, and the verbs that act are under [Write (protected)](#write-protected). The opponents, the manoeuvres used, the openings and the combat row fire [the fight events](event/bus/fight.md), and the schools are read on demand. |

## Read

| Method | Returns | Permission | Description |
|---|---|---|---|
| `session:fight():maneuver():list(filter)` | `Maneuver[]` | Unprotected | Every manoeuvre and attack that character knows. |
| `session:fight():maneuver():count(filter)` | `number` | Unprotected | How many match. |
| `session:fight():maneuver():find(filter)` | `Maneuver \| nil` | Unprotected | The first that matches. |
| `session:fight():deck()` | collection | Unprotected | The loaded school's layout, the filled hotkey slots: `:list(filter)`, `:count(filter)`, `:find(filter)`. No `:get`. |
| `session:fight():summary()` | `FightSummary \| nil` | Unprotected | The action-point budget and the deck's size. |
| `session:fight():school()` | collection | Unprotected | The tab's save list: [School](#schools) objects, `:list(filter)`, `:count(filter)`, `:find(filter)`, `:get(n)`. |
| `session:fight():school():current()` | `School \| nil` | Unprotected | The school the tab marks as loaded. `nil` before the tab has built. |

| Rule | Detail |
|---|---|
| `filter` | A string [filter](conventions.md#the-filter-argument) matches the resource name and the display name. On the deck it matches the manoeuvre in the slot, so one needle finds the same manoeuvre through either door. |
| No `:get` | A manoeuvre is addressed by nothing you hold: a string is a search, a position is `:list()[n]`. `#deck()`, `deck()[n]` and `ipairs(deck())` are [refused](conventions.md#collections-the-noun-is-the-kind-the-verb-is-how-many). `deck():list()` is the array. |
| Empty slots are left out | `card:index()` is the position in that list (`deck():list()[n]:index()` is `n` whatever the gaps). `card:wire()` is the hotkey's place in the school, gaps counted. |

## A manoeuvre

| Method | Returns | Permission | Description |
|---|---|---|---|
| `maneuver:res()` | `string \| nil` | Unprotected | The resource name, its identity. |
| `maneuver:name()` | `string \| nil` | Unprotected | The display name. |
| `maneuver:dealable()` | `number` | Unprotected | How many copies that character may deal into a deck. |
| `maneuver:used()` | `number` | Unprotected | How many the loaded school has dealt. |
| `maneuver:exists()` | `boolean` | Unprotected | Whether that character still knows it. Always answers. |
| `maneuver:info()` | [`Maneuver`](types/fight.md#maneuver-deckcard-fightsummary) `\| nil` | Unprotected | A plain-table snapshot. |

## A deck card

A card is a place in the layout, not the manoeuvre in it. It keeps answering `:wire()` and `:key()` when the hotkey is emptied, while the manoeuvre half goes `nil` and `:exists()` goes `false`.

| Method | Returns | Permission | Description |
|---|---|---|---|
| `card:index()` | `number \| nil` | Unprotected | Its 1-based position in `:deck():list()`. `nil` for an emptied slot, which that list leaves out. |
| `card:wire()` | `number` | Unprotected | The raw 0-based deck index the write path takes. Always answers. |
| `card:key()` | `string \| nil` | Unprotected | The hotkey label the window paints. `nil` for a slot past the labels the window has, since the window paints a fixed set. |
| `card:maneuver()` | `Maneuver \| nil` | Unprotected | The manoeuvre dealt here. |
| `card:res()` | `string \| nil` | Unprotected | That manoeuvre's resource name. |
| `card:name()` | `string \| nil` | Unprotected | That manoeuvre's display name. |
| `card:used()` | `number \| nil` | Unprotected | How many copies the deck holds. |
| `card:exists()` | `boolean` | Unprotected | Whether the slot is filled. Always answers. |
| `card:info()` | [`DeckCard`](types/fight.md#maneuver-deckcard-fightsummary) `\| nil` | Unprotected | A plain-table snapshot. |

## The summary

| Method | Returns | Permission | Description |
|---|---|---|---|
| `summary:used()` | `number \| nil` | Unprotected | Action points the loaded school spends: the total the window paints beside the cap, the sum of `maneuver:used()` over every known manoeuvre. |
| `summary:maxActions()` | `number \| nil` | Unprotected | The action-point budget. |
| `summary:deckSize()` | `number \| nil` | Unprotected | How many hotkey slots the deck has. |
| `summary:exists()` | `boolean` | Unprotected | Whether that character's tab is still up. |
| `summary:info()` | [`FightSummary`](types/fight.md#maneuver-deckcard-fightsummary) `\| nil` | Unprotected | A plain-table snapshot. |

[`session:study():summary()`](study.md#the-summary) is a summary of the same kind. It is a live object with its own `:exists()` and `:info()`, interned on its window, `nil` while the window is not up.

## Schools

The tab's save list: one School per slot the server gives that character, in the list's order, whether the slot holds a school or not.

| Method | Returns | Permission | Description |
|---|---|---|---|
| `school:index()` | `number` | Unprotected | Its 1-based position in the save list, the `n` of `:get(n)`. Always answers. |
| `school:wire()` | `number` | Unprotected | The server's 0-based slot number. Always answers. |
| `school:empty()` | `boolean` | Unprotected | Whether the slot is unused. Always answers. |
| `school:name()` | `string \| nil` | Unprotected | The name the tab paints. `nil` when empty. |
| `school:info()` | [`School`](types/fight.md#school) `\| nil` | Unprotected | A plain-table snapshot. `nil` when empty. |

| Rule | Detail |
|---|---|
| `:get(n)` | Takes the 1-based position. Any other number answers `nil`, `0` included, and so does every `n` before the tab has built. A string raises, naming `:find(filter)`. |
| `filter` | A string [filter](conventions.md#the-filter-argument) matches the name, and an unused slot matches none. A name is a search, never a key: names are the player's and need not be unique. |
| `:current()` | The school the tab marks with a check, compared with `==`: `session:fight():school():current() == school`. It can be an unused slot, since the tab marks the first until the server names one. `:current(x)` raises. |
| The name | A used slot the server sent no name for reads `"Saved school n"`, as the tab paints it. A rename typed into the tab reads at once, before a save carries it. |
| An unused slot | `:empty()` is `true`, and `:name()` and `:info()` are `nil`. |
| Identity | Interned on character and position: `session:fight():school():get(1) == session:fight():school():list()[1]`, and `session:fight():school()` is the same object every call. |

```lua
local schools = hafen.session():current():fight():school()
for _, school in ipairs(schools:list()) do
  local mark = (school == schools:current()) and " (loaded)" or ""
  hafen.log():write(school:index() .. ": " .. (school:name() or "unused") .. mark)
end
```

## The fight in progress

| Method | Returns | Permission | Description |
|---|---|---|---|
| `session:fight():opponent()` | collection | Unprotected | Every opponent that character is fighting, in the order the fight keeps: `:list(filter)`, `:count(filter)`, `:find(filter)`, `:get(gobId)`. |
| `session:fight():opponent():current()` | `Opponent \| nil` | Unprotected | The target: the opponent the fight has picked. `nil` out of a fight. |
| `session:fight():action()` | collection | Unprotected | The combat row: ten [actions](#a-combat-action), one per combat key, `:list(filter)`, `:count(filter)`, `:find(filter)`, `:get(n)`. |
| `session:fight():cooldown()` | `number \| nil` | Unprotected | How much of the global cooldown is left, a [`0..1` fraction](shapes.md#units): `0` when the character can act. `nil` out of a fight. |
| `session:fight():last()` | `string \| nil` | Unprotected | The resource name of the manoeuvre that character used last. `nil` before its first and out of a fight. |
| `session:fight():opening()` | collection | Unprotected | Your openings: the row of icons the client paints over the map, to the left of that character. [Opening](#an-opening) objects, `:list(filter)`, `:count(filter)`, `:find(filter)`. No `:get`. |

| Rule | Detail |
|---|---|
| One object | `session:fight():opponent()`, `:action()` and `:opening()` are each the same object every call. |
| `:last()` | A resource name rather than a [Maneuver](#a-manoeuvre): an opponent's need not be one the character knows, and [`ManeuverUsed`](event/bus/fight.md) hands the same string. |
| `:get(gobId)` | Takes a gob id, the one thing the server publishes about an opponent. A miss is `nil`. A string [filter](conventions.md#the-filter-argument) is refused naming the function form: an opponent has no name of its own, and the creature's is `opponent:gob():name()`. |
| The order | The fight's own: a new opponent joins at the front and the target is moved to the front, so `:list()[1]` is usually the target but not always. The client's "Switch targets" key reorders the list without a message, and no event says so. |
| `:current()` | The distinguished member, compared with `==`: `session:fight():opponent():current() == opponent`. `:current(x)` raises: switching the target is [`:set(opponent)`](#write-protected). |
| Openings | Each icon is an [Opening](#an-opening). The target's row is painted to the right of that character, and `opponent:opening()` lists any opponent's while you fight them. |

## An opponent

| Method | Returns | Permission | Description |
|---|---|---|---|
| `opponent:id()` | `number` | Unprotected | The creature's gob id. Always answers. |
| `opponent:gob()` | [Gob](gob.md) | Unprotected | The creature, in the login whose fight this is. Never `nil`. |
| `opponent:exists()` | `boolean` | Unprotected | Whether that character is still fighting them. Always answers. |
| `opponent:ip()` | [`{mine=, theirs=}`](shapes.md#the-anonymous-shapes) `\| nil` | Unprotected | The initiative the fight paints between you, as whole numbers: `mine` beside your character, `theirs` beside them. |
| `opponent:give()` | [`{mine=, theirs=}`](shapes.md#the-anonymous-shapes) `\| nil` | Unprotected | The give button beside their portrait, as its two halves: `mine` the left, `theirs` the right. Booleans. |
| `opponent:last()` | `string \| nil` | Unprotected | The resource name of the manoeuvre they used last. `nil` before their first. |
| `opponent:opening()` | collection | Unprotected | Their openings: the row of icons the client paints over the map, to the right of your character while they are the target. [Opening](#an-opening) objects, a view minted per call. |
| `opponent:info()` | [`Opponent`](types/fight.md#opponent) | Unprotected | A plain-table snapshot. |

| Rule | Detail |
|---|---|
| The creature is the gob | Name, health and position belong to the gob. What the fight holds about the relation is read here. |
| Once the fight with them ends | `:ip()`, `:give()` and `:last()` read `nil`, `:opening()` is empty, `:exists()` is `false` and `:info()` is `{ id }`. `:id()` and `:gob()` go on answering. |
| `opponent:gob()` is never `nil` | Like [`session:world():gob():get(id)`](gob.md): ask `opponent:gob():exists()`. |
| Identity | Interned on character and gob id: `session:fight():opponent():get(id) == session:fight():opponent():get(id)` and `seen[opponent] = true` work. A later fight with the same creature hands back the same object. |
| Events | [`OpponentAdded`, `OpponentRemoved`, `OpponentChanged` and `OpponentSelected`](event/bus/fight.md) hand this object, and [`ManeuverUsed`](event/bus/fight.md) hands it beside a manoeuvre they used. |

## An opening

One icon the fight draws over the map: beside your character, listed by `session:fight():opening()`, or beside an opponent, listed by `opponent:opening()`.

| Method | Returns | Permission | Description |
|---|---|---|---|
| `opening:res()` | `string \| nil` | Unprotected | The resource name. |
| `opening:name()` | `string \| nil` | Unprotected | The display name, once the resource has resolved. |
| `opening:amount()` | `number \| nil` | Unprotected | Its own meter, a [`0..1` fraction](shapes.md#units). |
| `opening:remaining()` | `number \| nil` | Unprotected | The radial overlay, a [`0..1` fraction](shapes.md#units): how much of its run is left. |
| `opening:number()` | `number \| nil` | Unprotected | The integer badge drawn on the icon. |
| `opening:opponent()` | [`Opponent`](#an-opponent) `\| nil` | Unprotected | The opponent it is drawn beside. `nil` for one of yours, and once the fight no longer draws it. |
| `opening:widget()` | [Widget](ui/widget.md) `\| nil` | Unprotected | The widget that holds it: the crossing back into the tree. The fight keeps that list hidden and paints the icon elsewhere, so the widget does not mark where the icon is drawn. |
| `opening:exists()` | `boolean` | Unprotected | Whether the fight still draws it. Always answers. |
| `opening:info()` | [`Opening`](types/fight.md#opening) `\| nil` | Unprotected | A plain-table snapshot. |

| Rule | Detail |
|---|---|
| `filter` | A string [filter](conventions.md#the-filter-argument) on either door matches the resource name and the display name. A miss is `nil`. |
| No `:get` | An opening has no key: two can share a resource, and the server can replace a live opening's resource under it. `session:fight():opening():get(1)` raises: `session:fight():opening() has no verb 'get' — an opening has no key, since two can share a resource and the server can replace one under a live opening: session:fight():opening():find(needle) is the search and session:fight():opening():list()[n] takes a position`. |
| Identity | Interned per addon: both doors and the events hand the same object, so `seen[opening] = true` works. It re-reads its icon on every call, so a stashed one tracks its meters. |
| Res-only at first | The display name and the meters arrive in a second server message, so every reader may answer `nil` just after an opening appears. Content-defined: `amount`, `remaining` and `number` are often absent. |
| Only while the fight draws them | The rows exist while the fight lasts. One of yours that outlasts the fight is no longer drawn once it is over: it is not listed, `opening:exists()` is `false`, and `OpeningRemoved` fires for it when the fight ends. If it is still there when the next fight starts, it is drawn again and fires `OpeningAdded` again. |
| Once it is gone | `:exists()` is `false` while `:res()`, `:name()` and the meters read the values it had, so an `OpeningRemoved` payload still reads. |
| Events | [`OpeningAdded`, `OpeningChanged` and `OpeningRemoved`](event/bus/fight.md) hand this object, then the opponent it is drawn beside (`nil` for yours), then the session. `OpeningRemoved` still names the opponent after the fight with them has ended, when `opening:opponent()` answers `nil`. When the fight with an opponent ends, each of its openings fires `OpeningRemoved` once. |

```lua
hafen.event():on("OpeningAdded", function(opening, opponent, session)
  local whose = opponent and ("on opponent " .. opponent:id()) or "on you"
  hafen.log():write((opening:name() or opening:res() or "an opening") .. " " .. whose)
end)
```

## A combat action

The place a combat key presses: `session:fight():action():get(n)` is the one "Combat action n" presses, keys `1` to `5` and Shift+`1` to `5` unless you rebound them. The row is the fight's own, so every place is empty out of a fight.

| Method | Returns | Permission | Description |
|---|---|---|---|
| `action:index()` | `number` | Unprotected | Its 1-based position, the `n` of `:get(n)` and of "Combat action n". Always answers. |
| `action:wire()` | `number` | Unprotected | The server's 0-based slot number. Always answers. |
| `action:empty()` | `boolean` | Unprotected | Whether the place holds nothing. Always answers. |
| `action:res()` | `string \| nil` | Unprotected | The manoeuvre's resource name. |
| `action:name()` | `string \| nil` | Unprotected | Its display name. |
| `action:maneuver()` | [`Maneuver`](#a-manoeuvre) `\| nil` | Unprotected | The same manoeuvre in `session:fight():maneuver()`, `nil` when that list has none. |
| `action:cooldown()` | `number \| nil` | Unprotected | How much of its own cooldown is left, a [`0..1` fraction](shapes.md#units): `0` when it can be used. |
| `action:info()` | [`CombatAction`](types/fight.md#combataction) `\| nil` | Unprotected | A plain-table snapshot. `nil` for an empty place. |

| Rule | Detail |
|---|---|
| Ten places, always | `:list()` is ten actions and `:get(n)` takes `1` to `10`: the client's ten combat keys. `:get(0)` raises naming `action:wire()`, and a position past ten names the range. A place past the server's row is `:empty()`. |
| The fight's row, not the deck | What the server put up for this fight. `session:fight():deck()` is the schools tab's layout, which the tab keeps to itself until it is saved. |
| An empty place | Every reader but `:index()`, `:wire()` and `:empty()` answers `nil`. |
| `filter` | A string matches the manoeuvre's resource and display name, as on `:maneuver()`, so one needle finds the same manoeuvre through either door. |
| Identity | Interned on character and place: `session:fight():action():get(1) == session:fight():action():list()[1]`. A stashed action follows its key from fight to fight. |
| Events | [`CombatActionChanged`](event/bus/fight.md) when a place is set, cleared or its name resolves, never as a cooldown runs. The moment a manoeuvre is used is [`ManeuverUsed`](event/bus/fight.md). |
| Using it | [`action:use(mods, position)`](#write-protected), under `fight.use`. |

## Write (protected)

The client sends only shapes a player could compose.

| Method | Returns | Permission | Description |
|---|---|---|---|
| `action:use(mods, position)` | the `CombatAction` | `fight.use` | Use that action, as tapping its combat key does. |
| `session:fight():opponent():set(opponent)` | the collection | `fight.set` | Make that opponent the target, as the client's "Switch targets" key does. |
| `session:fight():pursue(opponent)` | `session:fight()` | `fight.pursue` | Press Pursue beside that opponent's portrait. |
| `session:fight():give(opponent, button)` | `session:fight()` | `fight.give` | Click the give button beside that opponent's portrait. |

| Rule | Detail |
|---|---|
| Permission | Each key [declared](../guides/permissions.md) in your manifest, or the group `fight.*`. An undeclared key raises naming it. One key covers every character ([a key names the action, not the target](../guides/permissions.md#a-key-names-the-action-not-the-target)). |
| A tap | The row's `use` and then its release, sent back to back from that character's combat row: the key pressed and let go. There is no verb for holding one down. |
| `mods` | Optional and first: Shift = 1, Ctrl = 2, Alt = 4, as for [`slot:use(mods)`](actionbar.md#write-protected). The client's own keys for actions 6 to 10 are Shift+`1` to Shift+`5`, so pressing one of them carries Shift. |
| `position` | Optional and second: a [Position](position.md) in that character's world, the ground the client adds when a key is pressed with the pointer over the map. `action:use(0, position)` passes one without modifiers. |
| The opponent | `:set` takes an `Opponent` or its gob id, the key `:get` takes, as [`session:speed():set`](speed.md#write-protected) takes what its `:get` takes. `:pursue` and `:give` take the `Opponent`. |
| `button` | Optional: `1`, the default, is the left button and `3` the right, as a click on the give button sends. What each does is the server's. |
| Raises before anything is sent | An addon that did not declare the verb's key, naming it. A surplus argument. For `action:use`: a `mods` that is not a whole number `0..7`, a `position` that is not a Position, no fight, an empty action (`action:empty()`) and one past the server's row. For the three that take an opponent: anything but an `Opponent` (or, for `:set`, its gob id), one of another character's fight, one that character no longer fights (`opponent:exists()` is `false`), and a `button` outside `1..3`. |
| Asynchronous | The server answers every write. A use: the cooldowns start, [`ManeuverUsed`](event/bus/fight.md) fires and `session:fight():last()` names it. A switch: `:current()` answers the old target until then, and [`OpponentSelected`](event/bus/fight.md) fires when it moves. Nothing moves when the server refused one. |
| The outbound stream sees them | Every message passes [`hafen.event():action()`](event/streams.md#intercepting-an-outbound-action) like the client's own, so a handler can stop or rewrite it. |

---

## See Also

- [`hafen.session`](session.md) — the address every read here is reached through.
- [Gob](gob.md) — what `opponent:gob()` hands back, and every read on it.
- [The fight](types/fight.md) — the snapshot shapes `:info()` returns.
- [The fight events](event/bus/fight.md) — an opponent coming and going, its numbers moving, the target changing, a manoeuvre used, the combat row changing.
- [`session:actionbar`](actionbar.md) — the other hotkey surface.
- [Permissions](../guides/permissions.md) — the `fight.*` keys.
- [`session:char`](char.md) — the skills that unlock manoeuvres.
