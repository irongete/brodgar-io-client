# session:actionbar: the action bar

Read and activate one character's action bar — the F-key and number-key hotbar — and put one of your own
[menu entries](menugrid.md#write-unprotected) on it. You reach it through the [session](session.md) whose
character you mean, and `s:actionbar()` **is** that character's bar.

```lua
local s = hafen.session():current()                      -- the character on screen
for _, slot in ipairs(s:actionbar():list()) do           -- every slot, occupied or not
  if not slot:empty() then
    hafen.log():write(slot:index() .. ": " .. (slot:name() or slot:res()))
  end
end
s:actionbar():get(1):use()                               -- protected: activate the first slot
```

## Whose bar it is

Every character has its own hotbar, and the server fills each one on its own: slot 11 on two characters is
two different buttons. So the read says which character it is about, and it answers for one you are not
looking at exactly as it answers for the drawn one:

```lua
hafen.session():current():actionbar():get(1):name()   -- the first slot of the character on screen
hafen.session():get("alt"):actionbar():count()        -- that character's bar, while you watch someone else
```

`s:actionbar()` is the same object every call, minted once for that session. A session the client no longer
holds reads as 144 empty slots rather than raising.

| Call | Returns |
|---|---|
| `s:actionbar():get(n)` | the `Slot` at position `n`, `1..144` — the same number `slot:index()` answers |
| `s:actionbar():list(filter)` | every slot — a 1-based array of `Slot` objects, in game-index order |
| `s:actionbar():count(filter)` | how many match |
| `s:actionbar():find(filter)` | the first that matches, or `nil` |
| `s:actionbar():page()` | which of the twelve pages that character's bar is **showing**, `1..12` |
| `s:actionbar():page(n)` | turn to that page; chains |

**One number addresses a slot, and it is the position.** `:get(n)` takes the same number
`slot:index()` answers, so **`s:actionbar():list()[n] == s:actionbar():get(n)`** — the invariant a
reader assumes on first contact. `:get(0)` raises, naming the change.

The server's own message carries a **raw 0-based** number, and that is `slot:wire()`. You need it only
to compare against something the server said; every verb here takes the position.

## The page is what the client is drawing, not what the bar is

The client draws **twelve buttons at a time** and pages through the 144: page `p` is slots
`(p-1)*12+1 .. p*12`, and the client's own `Go to page N` keys turn it. `s:actionbar():page()` is which
page that character is on, and `:page(n)` turns to one — both 1-based, like every index here.

```lua
local ab = hafen.session():current():actionbar()
local first = ((ab:page() - 1) * 12) + 1        -- the slot the leftmost button is showing
ab:page(3)                                       -- ...and now it is 25
```

**It changes nothing about the bar.** A slot's address is absolute and stays absolute: `:get(1)` is slot 1
whatever page is up, `:list()` is all 144 in order, and what a slot holds is untouched. The page says which
twelve the player is looking at — and therefore which twelve the client's own button keys reach.

**Both arities are unprotected**, and for the same reason: the page is a field of one widget in this client.
Turning it sends nothing, moves nothing and tells the server nothing, which is why the client's own page keys
need no permission either. A character whose HUD is not up yet reads page `1`, and a write on one is a silent
no-op — there is no bar to turn. A page outside `1..12` raises, and so does a non-number.

There is no event for it. Read it where you draw: a page is a thing the player is holding, not a thing that
happens.

The array is always 144 entries and never sparse. An empty slot is a `Slot` object like any other; it
just answers `:empty()`. A string [filter](conventions.md#the-filter-argument) matches a slot's
**resource name**, so `:list("act/")` is the occupied ability slots and an empty slot matches nothing.
An index outside `1..144` **raises an error** — the bar is a fixed array, so an out-of-range index is a
bug rather than a slot that does not exist yet. There is no `:add` and no `:remove`: the bar is a fixed
set of slots, and what changes is a slot's *content*.

Slot objects are **interned per addon** on the character *and* the index, so
`s:actionbar():get(1) == s:actionbar():get(1)` and `seen[slot] = true` work as a table key — while the same
number reached through two sessions gives you two objects, because it names two buttons. A `Slot` carries the
character and the index and re-reads the bar on every call, so a stashed one tracks the slot being set,
cleared or dragged, and goes `:empty()` the moment it is cleared — see
[snapshots vs handles](conventions.md#snapshots-vs-handles).

Before a character is in the world, and briefly after a reload, it has no hotbar: every slot reads as empty.
At login the occupied slots stream in a beat later, as a burst of `ActionbarChanged`.

## Read

| Method | Returns | Description |
|---|---|---|
| `slot:index()` | number | its **1-based** position, the number `:get(n)` takes — always answers |
| `slot:wire()` | number | the raw 0-based game index the server's own message carries |
| `slot:empty()` | boolean | whether the slot has no content; also `true` before the hotbar exists |
| `slot:res()` | string \| nil | the resource name of the slot's action or item — the identity of the entry, on a [held](#hold-a-slot-unprotected) slot |
| `slot:name()` | string \| nil | the display name, once the action's data has resolved |
| `slot:cooldown()` | number \| nil | the meter fraction, `0..1` |
| `slot:info()` | [`ActionbarSlot`](types/ui.md#actionbarslot) \| nil | a plain-table **snapshot**, the escape hatch for logging and serialising |

Every reader except `:index()` and `:empty()` answers `nil` for an empty slot. None of them throws, and
none is protected.

> A slot's `cooldown` is present only for an ability with a meter, and it is a
> [`0..1` fraction](shapes.md#units).

Subscribe to [`ActionbarChanged`](event/bus/character.md#character-and-status), whose payload is the
changed `Slot` itself, to react to a slot being set, cleared or changed. It does **not** fire on a
cooldown ticking, which would be every frame; read `:cooldown()` live off the object instead.

## Write (protected)

The client sends only shapes a player could compose, and what the server does with more than that is
the server's.

**`slot:res(name)` and `slot:clear()` are kept by the server**: the bar is the character's own, so an
assignment or a clear outlives your addon being disabled, reloaded or uninstalled, and the session ending.
To draw over a slot and give it back untouched, hold it instead — see below.

| Method | Key | Description |
|---|---|---|
| `slot:use(mods)` | `actionbar.use` | activate the slot, exactly as a left-click on that button does |
| `slot:res(name)` | `actionbar.res` | assign an action to the slot **by resource name**, exactly as dragging it off the menu grid does |
| `slot:clear()` | `actionbar.clear` | empty the slot, exactly as a right-click on that button does |

All three return the `Slot`, so they chain, and each acts on the character whose bar the slot is on, watched
or not. Each needs its own permission key declared in your manifest — or the group `actionbar.*`, which covers
all three — and called from an addon that did not declare it, each raises an error naming that key; see
[the permission model](conventions.md#the-permission-model). One key covers every character: see
[a key names the action, not the target](../guides/permissions.md#a-key-names-the-action-not-the-target).
`mods` is the optional modifier bitfield — Shift = 1, Ctrl = 2, Alt = 4. Optional is not unchecked: a value
that is not a number raises naming the verb and the parameter, one that merely scans as a number is
[still a string](conventions.md#a-number-is-not-a-string-and-a-numeric-string-is-not-a-number), and the
refusal comes before the slot is looked at.

`use` raises an error on an empty slot, so check `:empty()` first. A ground-targeted ability enters
targeting mode when used, just as clicking the button would; supply the target with
[`session:world():click`](world.md#write-protected) or [`place`](world.md#write-protected).

**`slot:res()` is one name for the pair**: with no argument it reads the slot's resource name, with one
it assigns that action. The name it takes is the same string it reads back — so the way to learn a name
is to put the action on the bar by hand once and read it. The write works on any slot, empty or
occupied, overwriting an occupied one, and a non-string or empty name raises an error. An unknown
resource name is **silently ignored** by the server, exactly as dragging something that does not exist
would be: the slot does not change, and no error comes back.

**The name is enough for every action in the menu**, the server-pushed abilities included. The server
addresses those by an id of its own rather than by a resource name, and a drag sends that id — so does this
verb: it looks the name up in that character's menu and sends whichever message the client's own drop would
send for that entry. Nothing about it shows from Lua. The name you read off a slot is the name that puts the
action back, whichever kind of entry it is, and the id is never a thing an addon holds.

**`slot:clear()` is `:res(name)`'s opposite, and it takes no arguments** — an argument is an error naming
both the assignment and `slot:hold(nil)`. It empties the slot whether or not anything is in it: clearing an
empty slot is a moment rather than a mistake, and nothing comes back to say so. It is the same message a
right-click on that button sends.

It clears **what the server has**, which is what makes it a separate verb from
[`slot:hold(nil)`](#hold-a-slot-unprotected). A hold is your entry drawn *over* the server's content;
releasing one hands that content back untouched, and this takes the content away. Calling it on a slot you
are holding is legal and does exactly that — a beat later the server's write lands on a held slot, which
[ends the hold](#when-a-hold-ends) as any server write to one does.

> **The two writes are asynchronous.** Each sends to the server, which echoes it back before the slot
> changes — so the very next line still reads the old content, and `:res(name):use()` in one chain
> would activate whatever was there before. React to `ActionbarChanged` on that slot, or wait a beat,
> when you need the new content.

```lua
local s = hafen.session():current()
s:actionbar():get(1):res("gfx/hud/act/mine")             -- protected: put "Mine" on the first slot
hafen.timer():after(0.5, function()
  s:actionbar():get(1):use()
end)
```

> **A slot has no `:widget()`.** The other domain objects cross back into the tree — a
> [Buff](buff.md), a [Meter](meter.md) — because the client draws each with a widget of its own. The bar
> does not: it is **one** widget that paints all 144 slots itself, so there is nothing per-slot to hand
> back and a `slot:widget()` would answer the same bar for every index. Reach the bar by role instead,
> `s:ui():match("hud.belt")`, and place against `w:rootPos()`.

## Hold a slot (unprotected)

**A [menu entry an addon added](menugrid.md#write-unprotected) sits on the bar too**, drawing its icon in
the slot and running its Lua when that slot's key is pressed. It does not go *into* the slot: the server
owns the bar and has never heard of the entry's name, so the client **holds** the slot instead — it draws
over what the server has there and hands it back untouched when the hold ends.

```lua
local s   = hafen.session():current()
local dig = s:menugrid():add("dig"):name("Auto-dig"):icon(hafen.asset():get("dig.png"))
dig:on("use", function() hafen.log():write("dug") end)
s:actionbar():get(11):hold(dig)            -- the entry now draws in that slot, and fires from it
```

The entry and the slot are **one character's pair**: a slot is held for an entry in that same character's
menu, and holding one for an entry that is not in it — added on another login, or removed again — is refused
naming which addon's entry it is and why it is not there.

**Whose entry it is does not matter.** The bar is one shared surface, and a slot is held for another addon's
entry exactly as for your own: [`s:menugrid():get(res)`](menugrid.md) hands you the `Pagina` for any entry in
the menu, and `slot:hold(pag)` places it. That is the drag's own rule — the client's bar takes whatever entry
the player drops on it without asking who added it — and an addon that draws a bar of its own does the same
for them. A hold *places* an entry and writes nothing on it; renaming it, re-iconing it or giving it handlers
stays [the entry's owner's](menugrid.md#write-unprotected).

| Method | Returns | Description |
|---|---|---|
| `slot:hold()` | `Pagina` \| nil | the entry this slot is being held for; `nil` for every slot the server owns |
| `slot:hold(pag)` | the `Slot` | hold this slot for an entry an addon added to that character's menu — yours or another's |
| `slot:hold(nil)` | the `Slot` | end the hold, whoever took it; inert on a slot nobody is holding |

Nothing reaches the server, so this needs **no permission** and it lands **immediately** — where
`slot:res(name)` below is a round trip. [`ActionbarChanged`](event/bus/character.md#character-and-status)
fires on both edges, taking the hold and ending it.

While a slot is held it reads as the entry: `slot:res()` is that entry's `addon/…` identity, `slot:name()`
the name you gave it, `slot:empty()` is false, and pressing the slot — its key, a click, or `slot:use()` —
runs your [`pag:on("use", fn)`](menugrid.md#a-click-runs-your-lua) handlers. It is one button in two places,
through the same code, so the grid and the bar can never answer differently.

**Dragging does the same thing.** Drag one of your entries off the action menu onto a slot and the client
holds that slot for it, exactly as the call above does and sending nothing.

The read half is the hold alone. A slot holding one of the game's own actions answers `nil` — that action is
already named by `slot:res()`, and [`s:menugrid():get(name)`](menugrid.md) is the `Pagina` for it.

### When a hold ends

| What happened | What the slot goes back to | The slot is |
|---|---|---|
| `slot:hold(nil)` | the server's own content | forgotten |
| a **right-click** on the slot | the server's own content — the right-click is not sent, so nothing is cleared | forgotten |
| `s:menugrid():remove(pag)` | the server's own content | remembered |
| your addon reloads, or you log out | the server's own content | remembered |
| your addon is **disabled** | the server's own content | forgotten |
| the **server** writes that slot | what the server just wrote — that is the slot's content now | forgotten |

Every row but the last puts back exactly what the server has in the slot, unchanged and never having left
it. The last row is the one that cannot: the message being handled *is* the server assigning or clearing
that slot, so what it wrote stands and the hold simply ends. `slot:res(name)` on a slot you are holding is
that row — your own write ends your own hold, a beat later, when the server echoes it back.

> **The bar is one shared surface.** A slot belongs to nobody: the player drags what they like onto it, and
> so does every other addon. Holding a slot another addon holds is allowed and the last write wins — what is
> carried through the whole pile is the *server's* own content, so one release puts the game's action back
> however many addons took that slot in turn. Nor does the entry belong to the hold: a slot held for another
> addon's entry follows *that* entry — it goes back when that addon removes the entry, reloads or is disabled,
> whichever addon placed it, exactly as a slot the player filled by dragging does.

### A hold is remembered

**The slot stays yours across a relog.** The client keeps, per character, which entry belongs in which slot,
and puts it back the moment that entry exists again — so a button the player dragged onto the bar last night
is on the bar tonight, and neither you nor they have to place it a second time.

That record holds **one entry per slot**, so the shared-surface rule above reaches across the relog too: the
live hold chains, and the record does not. Two addons holding one slot in turn are one remembered entry —
the last one written — and tonight the bar comes back with that one alone, however many addons were stacked
on it when the player logged out.

The call that re-applies it is [`s:menugrid():add(id)`](menugrid.md#smenugridaddid), the one your
addon already makes:

```lua
hafen.event():on("SessionEnteredWorld", function(s)
  local dig = s:menugrid():add("dig"):name("Auto-dig"):icon(hafen.asset():get("dig.png"))
  dig:on("use", function() hafen.log():write("dug") end)
end)                                         -- if it was on that bar, it is on that bar again
```

Nothing about that is timed, and you wait for nothing: the entry lands in its slot inside the `add`, so the
line after it already reads `slot:hold()`. Your addon stores nothing — this is not
[saved variables](store.md), it is the client's own record of a slot, and a `slot:hold(pag)` call is
remembered exactly as a drag is.

**The two ways a hold ends are remembered differently**, as the table above says. Ending it by hand —
`slot:hold(nil)`, a right-click, the server taking the slot — says the entry no longer belongs there, and
the record goes with it. The entry merely *going away* — `:remove`, a reload, a logout — says nothing
about the slot, so the slot waits. A `:reload` therefore puts every one of your buttons straight back, while a
player who right-clicked one off the bar keeps it off.

**Disabling an addon takes its buttons off the bar for good.** The slots go back to the server's own content
as the addon is torn down, and no later restart brings them back: an addon the player switched off leaves
nothing of itself on the bar, and enabling it again starts with an empty bar and the entries you add.

The record is the character's rather than the addon's: the same entry can stand in a different slot on
another character, and a slot you hold on one is not held on the next.

| What you did | What you get |
|---|---|
| `slot:hold(pag)` for one of the game's own entries | that *is the client's own entry* — pointing at `slot:res(name)` |
| `slot:hold(pag)` for an entry you removed | that entry *is no longer in the menu* |
| `slot:hold(pag)` for an entry another addon removed | *not in that character's menu*, naming that addon |
| `slot:hold(7)`, `slot:hold("dig")` | expected the **`Pagina` object**, or `nil` |
| `slot:res("addon/myaddon/dig")` | that *is an entry an addon added* — pointing back at `slot:hold` |

The last row is the pair's dividing line: `slot:res(name)` assigns one of the game's actions, by a name the
server publishes and stores; `slot:hold(pag)` holds a slot for an entry an addon added, which the server never
sees. One string could never mean both.

## See also

- [session](session.md) — the address every read here goes through
- [`session:menugrid`](menugrid.md) — where the names the write takes come from, and where your entries live
- [permissions](../guides/permissions.md) — the permission the two protected writes share
- [`ActionbarSlot`](types/ui.md#actionbarslot) — the snapshot shape `:info()` returns
- [events](event/bus/character.md#character-and-status) — `ActionbarChanged`
