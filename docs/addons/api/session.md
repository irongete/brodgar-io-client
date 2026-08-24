# hafen.session: the logins this client holds

The client can hold several accounts logged in at once, and draws one of them. `hafen.session()` **is**
the collection of them, and a **Session** is how you name one character rather than whichever is on
screen.

```lua
local s = hafen.session():current()               -- the session on screen, nil on the login screen
if s then
  hafen.log():write(s:user() .. " is playing " .. (s:character() or "nobody yet"))
end
```

| Call | Returns |
|---|---|
| `hafen.session():current()` | the `Session` on screen, or `nil` |
| `hafen.session():current(s)` | hand the screen to that session |
| `hafen.session():current(nil)` | hand it to the **login screen**, with every login left running — how another account is logged in |
| `hafen.session():get(user)` | the `Session` for that **account** — always an object, even for an account nobody is logged in as |
| `hafen.session():list(filter)` | the sessions the client holds, in the order they joined |
| `hafen.session():remove(s)` | end that login — the same act as `s:close()`, and protected by the same key |

Every session the client holds is whole: connected, ticked, answering the server, with a character and
a world of its own. One of them is drawn and the rest are not, and which one that is changes when the
player tabs between them and when an addon writes the screen.

## What hangs on a session

A Session is the **address**, so the reads that are about one character hang off it rather than off `hafen`:

| Verb | What it gives you |
|---|---|
| [`s:world()`](world.md) | that character's world — the objects it can see, the ground it stands on, the grids it has streamed |
| [`s:player()`](player.md) | that character itself — its own [Gob](gob.md), its cursor, and the walk |
| [`s:char()`](char.md) | its sheet: attributes, learning points, weight, food, skills, credos, lore |
| [`s:meter()`](meter.md) | its HUD meter bars — health, stamina, energy, and whatever else the server puts there |
| [`s:buff()`](buff.md) | the buffs on its buff bar |
| [`s:study()`](study.md) | its study window: the curiosities in it, and their LP and attention |
| [`s:quest()`](quest.md) | its quest log, current and completed |
| [`s:wound()`](wound.md) | its wounds, as the Health and Wounds tab shows them |
| [`s:kin()`](kin.md) | its kin roster, and the writes that add, rename and re-group |
| [`s:party()`](party.md) | the party it is in, in party sequence order |
| [`s:actionbar()`](actionbar.md) | its hotbar: read a slot, use it, assign one, hold one for an entry of your own |
| [`s:speed()`](speed.md) | its crawl, walk, run and sprint selector |
| [`s:craft()`](craft.md) | the recipe window it has open, and its Craft button |
| [`s:menugrid()`](menugrid.md) | its action menu: every action it knows, invoking one, and entries of your own |
| [`s:fight()`](fight.md) | its combat schools, its maneuver deck, and who it is fighting |
| [`s:flowermenu()`](flowermenu.md) | the radial menu it has open, and the petal to pick |
| [`s:ui()`](ui/README.md) | the widgets the client put up for it: find one, watch for one, read its backpack |
| [`s:store()`](store.md) | its own saved variables, in its own folder on disk |

```lua
for _, s in ipairs(hafen.session():list()) do
  local me = s:player():gob()
  if me then
    hafen.log():write(s:user() .. " stands on grid " .. me:position():info().gridId)
  end
end
```

Each is minted once for that session and handed back by identity, so `s:world() == s:world()` and a draw
callback that reads them costs nothing. Every one of them is reached only this way, and only ever about one
character: *the* world, *the* kin roster and *the* action bar are not things a client holding two logins
has. The windows among them — a recipe, an action menu — belong to the character that put them up, so they
are readable and usable on a session you tabbed away from.

**Two of them are half of a namespace rather than all of it.** [`s:ui()`](ui/README.md) is the client's own
widgets, which stand in one character's tree; the windows your addon *builds* stand in a layer above every
session and stay [`hafen.ui():window()`](ui/custom.md), because your window and the client's window are not
the same thing. [`s:store()`](store.md) is the saved variables of one character, in that character's own
folder; an account's are your addon's single file and are reached without an address.

**A read answers for the session you named, whichever one is drawn.** What does not is what belongs to the
**screen** — there is one screen however many characters are logged in — and each of those says so where
it is described: [`worldToScreen` and `screenToWorld`](world.md#the-screen-and-the-world) name a pixel, and
[`click`](world.md#write-protected), `place`/`select` and
[`hand:use`](player.md#the-hand) are gestures with the pointer.

> **Walking is the whole of what a character you are not looking at will take.** That is the client's own
> line rather than this API's: an order to another login carries a destination and never a target. So
> [`s:player():move(p)`](player.md#write-protected) reaches any session, and every other write reaches the
> one on screen and raises for the rest.

## The account is the name

A session is named by the **account** it logged in as — the string `:session add` took and `:session
list` prints — and that is the whole of what a Session object wraps. So it survives everything that
happens to the login behind it: a character switch, a relogin, the session ending. `:user()` answers
for a Session whose session is over, which is what makes it the key you drop your own tables by, and
`:exists()` is the liveness test.

**One account plays one character at a time**, and picking another keeps the session alive — the server
hands it a new world rather than ending it. That is why the account is the key and the character is a
read: `:character()` answers what this login is playing *now*.

Session objects are **interned per addon**, so `hafen.session():get("bob") == hafen.session():get("bob")`,
the object in `:list()` is the same one `:get` and `:current()` hand back, and `seen[s] = true` works as
a table key. So are the things that hang off one: hold the `Session` and `s:world()`, `s:player()` and every
[Gob](gob.md#identity) you read through it keep their identity for as long as you do.

> **`:current()` changes under you.** It answers whichever session holds the screen at the moment you
> ask, so take it inside your handler rather than keeping one from load time. What you may keep is a
> Session itself: it names one account and never becomes another.

## Read

The first five are called on the collection, the rest on a `Session`. Nothing here is protected, and a
`Session`'s own reads never throw — `s:world()` and `s:player()` answer for a session that has ended too,
and everything under them then reads `nil`-shaped.

| Method | Returns | Description |
|---|---|---|
| `hafen.session():list(filter)` | `Session[]` | the sessions the client holds, matching the [filter](conventions.md#the-filter-argument) — a string matches the account name |
| `hafen.session():count(filter)` | number | how many match, without building the array |
| `hafen.session():find(filter)` | `Session` \| nil | the first that matches |
| `hafen.session():get(user)` | `Session` | the session for that account name; always an object |
| `hafen.session():current()` | `Session` \| nil | the session on screen; `nil` on the login screen |
| `s:user()` | string | the account name — answers for a session that has ended |
| `s:character()` | string \| nil | the character this session is playing; `nil` until its HUD is up |
| `s:exists()` | boolean | whether the client still holds this session |
| a namespace verb | the section | the namespaces that hang on a session — see [what hangs on a session](#what-hangs-on-a-session); each is the same object every call |
| `s:info()` | [`Session`](types.md#session) | a plain-table **snapshot**, the escape hatch for logging |

**`:get` addresses, it does not search.** The account name is the whole of a Session, so there is
nothing to miss: a name read out of [saved variables](store.md) hands back an object before that account
logs in and after it goes, and `:exists()` says which. The key is the account and only the account, so a
character name hands back a session that does not exist, and anything that is not a string — a number, a
Session — raises. To search, use the ordinary [filter](conventions.md#the-filter-argument):
`hafen.session():find("bo")` matches part of an account name.

## Write (unprotected)

### `hafen.session():current(s)`

Hand the screen to `s`. It is the whole gesture rather than half of one — the RTS selection and the camera
follow the screen, exactly as they do when the player takes it with `:session anchor` — and it hands the
collection back, so writes chain. Naming the session **already** on screen changes nothing and fires no
[`SessionSelected`](event/bus.md#sessions).

```lua
local list, cur, at = hafen.session():list(), hafen.session():current(), 0   -- go round the logins
for i, s in ipairs(list) do if s == cur then at = i end end
hafen.session():current(list[(at % #list) + 1])
```

It raises, naming what is wrong, on three things: a value that is not a `Session`, a `Session` the client
does not hold, and a session with **no screen of its own yet**. The second is reachable by construction,
since `:get(user)` mints an object for any account name, and `s:exists()` is the test that tells it apart.

The third is a session the client *does* hold, and `s:exists()` is `true` for it: it is still arriving, or
between the character it left and the one it is taking, so there is nothing to hand the screen to and no
[`SessionSelected`](event/bus.md#sessions) would follow. Write the screen from that session's
[`SessionEnteredWorld`](event/bus.md#sessions), which is the moment it has one.

### `hafen.session():current(nil)`

Go to the **login screen** — the write that answers the read's own `nil`, and the one place the explicit
`nil` means something rather than raising.

```lua
hafen.session():current(nil)                  -- log another account in
```

The client's own login screen is live behind every session: the client goes back to waiting on it the
moment a login it performed becomes a session, so it is a place to go to and not merely where dropping the
last session leaves you. **Your characters stay logged in** — every session goes on ticking and answering
the server behind it — and whatever you log in there arrives as a session like any other, takes the screen
because nothing else is holding it, and appears in `:list()` with its own
[`SessionAdded`](event/bus.md#sessions). To come back without logging anything in, write the screen to a
session again.

It is how an account **with no saved token** is logged in, which nothing else here reaches: there is no
`hafen.session():add`, and `:session add` — the console's own — can only connect an account the login
screen has already saved a token for.

Going to the login screen fires **no event**: the [session family](event/bus.md#sessions)' payload *is* a
session, and no session was picked. `hafen.session():current()` reads `nil` while it holds the screen, so a
handler of your own is what tells anything that is watching.

**The screen needs no permission.** The [protected tier](../guides/permissions.md) is for what an addon
does whose effect leaves the client, and taking the screen changes which widget tree is drawn and nothing
else: the server is never told, and nothing about any character is altered. Going to the login screen is
the same act — it logs nobody in and logs nobody out; what happens on it is the player's own doing.

## Write (protected)

Two spellings, one act and one key: `hafen.session():remove(s)` ends a login from the collection, which is
where every other collection in this API keeps the verb that destroys a member, and `s:close()` ends it
from the login itself. Both need the `session.close` permission, and the permission is checked before
either looks at what you handed it.

### `hafen.session():remove(s)`

End the login `s` names, and hand the **collection** back, so removals chain. A reader who has written
`hafen.map():marker():remove(m)` or `gob:overlay():remove(key)` writes this one without being told.

```lua
local live = hafen.session()
live:remove(live:get("alt1")):remove(live:get("alt2"))    -- the collection comes back, so this chains
```

You pass the `Session`, never an account name: `hafen.session():remove("alice")` raises naming the object,
and `:get(user)` is what hands you one. Everything else about it is `s:close()`'s, below: the same refusal
for a session the client does not hold, in the same words, and the same asynchrony.

### `s:close()`

End that session, which is the logout `:session drop` performs, on the character you name. It needs the
`session.close` permission, and it hands the `Session` back so writes chain.

```lua
for _, s in ipairs(hafen.session():list()) do           -- log the alts out, keep the one on screen
  if s ~= hafen.session():current() then s:close() end
end
```

**It is asynchronous.** The verb asks the session to close and returns; that login is still in
`hafen.session():list()` on the next line and leaves a tick or more later, on its own thread. `s:exists()`
is the read that answers and [`SessionRemoved`](event/bus.md#sessions) is the edge, so poll the one or
subscribe to the other rather than reading the list again on the line below.

Closing the session **on screen** is allowed: the screen goes to another live session, or to the login
screen when that was the last one. `s:user()` answers afterwards, as it does for every `Session` whose
login has ended, so the handle you closed is still the key you drop your own tables by.

It raises on a session the client does not hold, naming the account. `:get(user)` mints an object for any
account name and a closed session is one the client no longer holds, so closing the same one twice is the
second call raising rather than a silent nothing.

**Why it is protected.** Logging a character out leaves the client: the server is told, and that
character goes. The line the user reads when they enable your addon is "log out any of your
characters", and it covers every login the client holds — the key names the action rather than the
character it is pointed at.

## Sessions that come and go

The four [session events](event/bus.md#sessions) are where an addon learns that a session connected,
reached the world, took the screen or ended, and each hands your handler the `Session` it is about. They
report changes rather than state: an addon loaded while three characters are up hears about none of the
three, and `hafen.session():list()` is how it learns what is already there.

```lua
for _, s in ipairs(hafen.session():list()) do                -- what the client already holds
  hafen.log():write(s:user() .. ": " .. (s:character() or "not in the world"))
end
```

Key your tables by `s:user()` rather than by the Session object when what you are tracking has to
outlive the session: the string is a plain Lua value that survives your addon being reloaded, while an
object is one addon's handle. Both address the same login.

**The four are not the only events that name a session.** Everything the bus reports about one
character — its meters, buffs, food, study, equipment, action bar, wounds, roster, quests and radial
menu — hands you that character's `Session` as the handler's
[last argument](event/bus.md#whose-character-it-was), so a handler reads the character the event was
about rather than the one on screen.

## See also

- [events](event/bus.md#sessions) — the four moments a session announces
- [`session:world`](world.md) — one character's objects, terrain and coordinates
- [`session:player`](player.md) — one character, its Gob, its cursor and the walk
- [`hafen.store`](store.md) — saved variables, per character and per account
- [conventions](conventions.md#the-grammar) — collections, interned objects and the filter argument
- [data types](types.md#session) — the snapshot shape `:info()` returns
