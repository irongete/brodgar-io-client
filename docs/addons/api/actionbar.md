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
s:actionbar():get(0):use()                               -- protected: activate the first slot
```

## Whose bar it is

Every character has its own hotbar, and the server fills each one on its own: slot 11 on two characters is
two different buttons. So the read says which character it is about, and it answers for one you are not
looking at exactly as it answers for the drawn one:

```lua
hafen.session():current():actionbar():get(0):name()   -- the first slot of the character on screen
hafen.session():get("alt"):actionbar():count()        -- that character's bar, while you watch someone else
```

`s:actionbar()` is the same object every call, minted once for that session. A session the client no longer
holds reads as 144 empty slots rather than raising.

| Call | Returns |
|---|---|
| `s:actionbar():get(n)` | the `Slot` at the raw 0-based game index `n`, `0..143` |
| `s:actionbar():list(filter)` | every slot — a 1-based array of `Slot` objects, in game-index order |
| `s:actionbar():count(filter)` | how many match |
| `s:actionbar():find(filter)` | the first that matches, or `nil` |

**The index is 0-based, and an array position is not an index.** `:get(n)` takes the raw game index —
the same number `use` takes and the same one the server uses — and that is the one way to address a
slot. `:list()` is the iteration view, and a Lua array starts at 1, so
`s:actionbar():list()[1] == s:actionbar():get(0)`. Never do the arithmetic yourself: a Slot
knows its own index, and `slot:index()` is the way back.

The array is always 144 entries and never sparse. An empty slot is a `Slot` object like any other; it
just answers `:empty()`. A string [filter](conventions.md#the-filter-argument) matches a slot's
**resource name**, so `:list("act/")` is the occupied ability slots and an empty slot matches nothing.
An index outside `0..143` **raises an error** — the bar is a fixed array, so an out-of-range index is a
bug rather than a slot that does not exist yet. There is no `:add` and no `:remove`: the bar is a fixed
set of slots, and what changes is a slot's *content*.

Slot objects are **interned per addon** on the character *and* the index, so
`s:actionbar():get(0) == s:actionbar():get(0)` and `seen[slot] = true` work as a table key — while the same
number reached through two sessions gives you two objects, because it names two buttons. A `Slot` carries the
character and the index and re-reads the bar on every call, so a stashed one tracks the slot being set,
cleared or dragged, and goes `:empty()` the moment it is cleared — see
[snapshots vs handles](conventions.md#snapshots-vs-handles).

Before a character is in the world, and briefly after a reload, it has no hotbar: every slot reads as empty.
At login the occupied slots stream in a beat later, as a burst of `ActionbarChanged`.

## Read

| Method | Returns | Description |
|---|---|---|
| `slot:index()` | number | the raw 0-based game index this Slot addresses — always answers |
| `slot:empty()` | boolean | whether the slot has no content; also `true` before the hotbar exists |
| `slot:res()` | string \| nil | the resource name of the slot's action or item — the identity of the entry, on a [held](#hold-a-slot-unprotected) slot |
| `slot:name()` | string \| nil | the display name, once the action's data has resolved |
| `slot:cooldown()` | number \| nil | the meter fraction, `0..1` |
| `slot:info()` | [`ActionbarSlot`](types.md#actionbarslot) \| nil | a plain-table **snapshot**, the escape hatch for logging and serialising |

Every reader except `:index()` and `:empty()` answers `nil` for an empty slot. None of them throws, and
none is protected.

> A slot's `cooldown` is present only for an ability with a meter, and it is a `0..1` **fraction, not
> seconds**.

Subscribe to [`ActionbarChanged`](event/bus.md#character-and-status), whose payload is the
changed `Slot` itself, to react to a slot being set, cleared or changed. It does **not** fire on a
cooldown ticking, which would be every frame; read `:cooldown()` live off the object instead.

## Write (protected)

| Method | Key | Description |
|---|---|---|
| `slot:use(mods)` | `actionbar.use` | activate the slot, exactly as a left-click on that button does |
| `slot:res(name)` | `actionbar.res` | assign an action to the slot **by resource name**, exactly as dragging it off the menu grid does |

Both return the `Slot`, so they chain, and both act on the character whose bar the slot is on, watched or
not. Each needs its own permission key declared in your manifest — or the group `actionbar.*`, which covers
both — and called from an addon that did not declare it, each raises an error naming that key; see
[the permission model](conventions.md#the-permission-model). One key covers every character: see
[a key names the action, not the target](../guides/permissions.md#a-key-names-the-action-not-the-target).
`mods` is the optional modifier bitfield — Shift = 1, Ctrl = 2, Alt = 4.

`use` raises an error on an empty slot, so check `:empty()` first. A ground-targeted ability enters
targeting mode when used, just as clicking the button would; supply the target with
[`session:world():click`](world.md#write-protected) or [`place`](world.md#write-protected).

**`slot:res()` is one name for the pair**: with no argument it reads the slot's resource name, with one
it assigns that action. The name it takes is the same string it reads back — so the way to learn a name
is to put the action on the bar by hand once and read it. The write works on any slot, empty or
occupied, overwriting an occupied one, and a non-string or empty name raises an error. An unknown
resource name is **silently ignored** by the server, exactly as dragging something that does not exist
would be: the slot does not change, and no error comes back. There is no way to assign by pagina id,
since those are session-local and opaque to addons.

> **The write is asynchronous.** It sends the assignment to the server, which echoes it back before the
> slot changes — so the very next line still reads the old content, and `:res(name):use()` in one chain
> would activate whatever was there before. React to `ActionbarChanged` on that slot, or wait a beat,
> when you need the new action.

```lua
local s = hafen.session():current()
s:actionbar():get(0):res("gfx/hud/act/mine")             -- protected: put "Mine" on the first slot
hafen.timer():after(0.5, function()
  s:actionbar():get(0):use()
end)
```

## Hold a slot (unprotected)

**One of your own [menu entries](menugrid.md#write-unprotected) sits on the bar too**, drawing its icon in
the slot and running its Lua when that slot's key is pressed. It does not go *into* the slot: the server
owns the bar and has never heard of your entry's name, so the client **holds** the slot instead — it draws
over what the server has there and hands it back untouched when the hold ends.

```lua
local s   = hafen.session():current()
local dig = s:menugrid():add("dig"):name("Auto-dig"):icon(hafen.asset():get("dig.png"))
dig:on("use", function() hafen.log():write("dug") end)
s:actionbar():get(11):pagina(dig)          -- the entry now draws in that slot, and fires from it
```

The entry and the slot are **one character's pair**: a slot is held for an entry in that same character's
menu, and holding one for an entry you added on another login is refused naming whose menu it is in.

| Method | Returns | Description |
|---|---|---|
| `slot:pagina()` | `Pagina` \| nil | the entry this slot is being held for; `nil` for every slot the server owns |
| `slot:pagina(pag)` | the `Slot` | hold this slot for one of the entries your addon added |
| `slot:pagina(nil)` | the `Slot` | end the hold, whoever took it; inert on a slot nobody is holding |

Nothing reaches the server, so this needs **no permission** and it lands **immediately** — where
`slot:res(name)` below is a round trip. [`ActionbarChanged`](event/bus.md#character-and-status) fires on both
edges, taking the hold and ending it.

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
| `slot:pagina(nil)` | the server's own content | forgotten |
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
> however many addons took that slot in turn.

### A hold is remembered

**The slot stays yours across a relog.** The client keeps, per character, which entry belongs in which slot,
and puts it back the moment that entry exists again — so a button the player dragged onto the bar last night
is on the bar tonight, and neither you nor they have to place it a second time.

The call that re-applies it is [`s:menugrid():add(id)`](menugrid.md#smenugridaddid), the one your
addon already makes:

```lua
hafen.event():on("SessionEnteredWorld", function(s)
  local dig = s:menugrid():add("dig"):name("Auto-dig"):icon(hafen.asset():get("dig.png"))
  dig:on("use", function() hafen.log():write("dug") end)
end)                                         -- if it was on that bar, it is on that bar again
```

Nothing about that is timed, and you wait for nothing: the entry lands in its slot inside the `add`, so the
line after it already reads `slot:pagina()`. Your addon stores nothing — this is not
[saved variables](store.md), it is the client's own record of a slot, and a `slot:pagina(pag)` call is
remembered exactly as a drag is.

**The two ways a hold ends are remembered differently**, as the table above says. Ending it by hand —
`slot:pagina(nil)`, a right-click, the server taking the slot — says the entry no longer belongs there, and
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
| `slot:pagina(pag)` for one of the game's own entries | that *is the client's own entry* — pointing at `slot:res(name)` |
| `slot:pagina(pag)` for another addon's entry | it *belongs to* that addon, named |
| `slot:pagina(pag)` for an entry you removed | that entry *is no longer in the menu* |
| `slot:pagina(7)`, `slot:pagina("dig")` | expected the **`Pagina` object**, or `nil` |
| `slot:res("addon/myaddon/dig")` | that *is an entry an addon added* — pointing back at `slot:pagina` |

The last row is the pair's dividing line: `slot:res(name)` assigns one of the game's actions, by a name the
server publishes and stores; `slot:pagina(pag)` holds a slot for one of yours, which the server never sees.
One string could never mean both.

## See also

- [session](session.md) — the address every read here goes through
- [`session:menugrid`](menugrid.md) — where the names the write takes come from, and where your entries live
- [permissions](../guides/permissions.md) — the permission the two protected writes share
- [`ActionbarSlot`](types.md#actionbarslot) — the snapshot shape `:info()` returns
- [events](event/bus.md#character-and-status) — `ActionbarChanged`
