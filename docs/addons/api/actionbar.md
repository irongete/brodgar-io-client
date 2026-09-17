# session:actionbar: The Action Bar

One character's action bar (the F-key and number-key hotbar), read, activated and written through its [session](session.md). `session:actionbar()` is that character's bar, and a slot can be held for one of your own [menu entries](menugrid.md#write-unprotected).

```lua
local session = hafen.session():current()                      -- the character on screen
for _, slot in ipairs(session:actionbar():list()) do           -- every slot, occupied or not
  if not slot:empty() then
    hafen.log():write(slot:index() .. ": " .. (slot:name() or slot:res()))
  end
end
session:actionbar():get(1):use()                               -- protected: activate the first slot
```

---

| Method | Returns | Permission | Description |
|---|---|---|---|
| `session:actionbar():get(n)` | `Slot` | Unprotected | The slot at position `n`, `1..144`, the number `slot:index()` answers. |
| `session:actionbar():list(filter)` | `Slot[]` | Unprotected | Every slot, a 1-based array in game-index order. |
| `session:actionbar():count(filter)` | `number` | Unprotected | How many match. |
| `session:actionbar():find(filter)` | `Slot \| nil` | Unprotected | The first that matches. |
| `session:actionbar():page()` | `number` | Unprotected | Which of the twelve pages that character's bar is showing, `1..12`. |
| `session:actionbar():page(n)` | the collection | Unprotected | Turn to that page. |

| Rule | Detail |
|---|---|
| Whose bar | Every character has its own hotbar and the server fills each on its own: slot 11 on two characters is two buttons. `hafen.session():get("alt"):actionbar():count()` answers for that character, drawn or not. |
| One object | `session:actionbar()` is the same object every call, minted once per session. A session the client no longer holds reads as 144 empty slots. |
| One number addresses a slot | `:get(n)` takes the number `slot:index()` answers: `session:actionbar():list()[n] == session:actionbar():get(n)`. `:get(0)` raises naming the change. An index outside `1..144` raises: the bar is a fixed array. `slot:wire()` is the server's raw 0-based number, for comparing against something the server said. |
| Always 144, never sparse | An empty slot is a `Slot` answering `:empty()`. A string [filter](conventions.md#the-filter-argument) matches a slot's resource name, so `:list("act/")` is the occupied ability slots. No `:add`, no `:remove`: what changes is a slot's content. |
| Interned per addon | On character and index: `session:actionbar():get(1) == session:actionbar():get(1)` and `seen[slot] = true` work. The same number through two sessions is two objects. A `Slot` re-reads the bar on every call, so a stashed one tracks the slot being set, cleared or dragged ([snapshots vs handles](conventions.md#snapshots-vs-handles)). |
| Before the hotbar exists | Before the character is in the world, and briefly after a reload, every slot reads as empty. At login the occupied slots stream in shortly after, as a burst of `ActionbarChanged`. |

## The page

The client draws twelve buttons at a time and pages through the 144. Page `p` is slots `(p-1)*12+1 .. p*12`. It is turned by the client's `Go to page N` keys or `:page(n)`, both 1-based.

```lua
local actionbar = hafen.session():current():actionbar()
local first_shown = ((actionbar:page() - 1) * 12) + 1      -- the slot the leftmost button is showing
actionbar:page(3)                                          -- ...and now it is 25
```

| Rule | Detail |
|---|---|
| Changes nothing about the bar | A slot's address is absolute: `:get(1)` is slot 1 whatever page is up, `:list()` is all 144, contents untouched. The page says which twelve the player sees and the client's button keys reach. |
| Unprotected both ways | The page is a field of one widget: turning it sends nothing, so the client's own page keys need no permission either. |
| No HUD yet | Reads page `1`. A write is a silent no-op. A page outside `1..12` raises, so does a non-number. |
| No event | Read it where you draw. |

## Read

| Method | Returns | Permission | Description |
|---|---|---|---|
| `slot:index()` | `number` | Unprotected | Its 1-based position, the number `:get(n)` takes. Always answers. |
| `slot:wire()` | `number` | Unprotected | The raw 0-based game index the server's message carries. |
| `slot:empty()` | `boolean` | Unprotected | Whether the slot has no content. `true` before the hotbar exists. |
| `slot:res()` | `string \| nil` | Unprotected | The resource name of the slot's action or item. On a [held](#hold-a-slot-unprotected) slot, the entry's identity. |
| `slot:name()` | `string \| nil` | Unprotected | The display name, once the action's data has resolved. |
| `slot:cooldown()` | `number \| nil` | Unprotected | The meter fraction, a [`0..1` fraction](shapes.md#units). Present only for an ability with a meter. |
| `slot:info()` | [`ActionbarSlot`](types/ui.md#actionbarslot) `\| nil` | Unprotected | A plain-table snapshot, for logging and serialising. |

| Rule | Detail |
|---|---|
| An empty slot | Every reader except `:index()` and `:empty()` answers `nil`. None throws. None is protected. |
| Event | [`ActionbarChanged`](event/bus/character.md#character-and-status), payload the changed `Slot`: set, cleared or changed. Not on a cooldown ticking. Read `:cooldown()` live. |
| No `slot:widget()` | The bar is one widget painting all 144 slots, so there is nothing per slot to hand back. Reach the bar by role, `session:ui():match("hud.belt")`, and place against `widget:rootPos()`. |

## Write (protected)

The client sends only shapes a player could compose.

| Method | Returns | Permission | Description |
|---|---|---|---|
| `slot:use(mods)` | the `Slot` | `actionbar.use` | Activate the slot, as a left-click on that button does. |
| `slot:res(name)` | the `Slot` | `actionbar.res` | Assign an action by resource name, as dragging it off the menu grid does. |
| `slot:clear()` | the `Slot` | `actionbar.clear` | Empty the slot, as a right-click on that button does. |

| Rule | Detail |
|---|---|
| Permission | Each key [declared](../guides/permissions.md) in your manifest, or the group `actionbar.*`. An undeclared key raises naming it ([the permission model](conventions.md#the-permission-model)). One key covers every character ([a key names the action, not the target](../guides/permissions.md#a-key-names-the-action-not-the-target)). Each acts on the character whose bar the slot is on, watched or not. |
| Kept by the server | `slot:res(name)` and `slot:clear()` outlive your addon being disabled, reloaded or uninstalled, and the session ending. To draw over a slot and give it back untouched, [hold](#hold-a-slot-unprotected) it. |
| `mods` | Optional bitfield: Shift = 1, Ctrl = 2, Alt = 4. A value that is not a number raises naming the verb and the parameter. A numeric string is [still a string](conventions.md#a-number-is-not-a-string-and-a-numeric-string-is-not-a-number). The refusal comes before the slot is looked at. |
| `use` | Raises on an empty slot: check `:empty()` first. A ground-targeted ability enters targeting mode. Supply the target with [`session:world():click`](world.md#write-protected) or [`place`](world.md#write-protected). |
| `slot:res()` is one name for the pair | No argument reads, one assigns. The name it takes is the string it reads back, so put the action on the bar by hand once and read it. Works on any slot, overwriting an occupied one. A non-string or empty name raises. An unknown resource name is silently ignored by the server, as dragging something that does not exist would be. |
| The name is enough for every action | The server addresses its pushed abilities by an id of its own and a drag sends that id. So does this verb, looking the name up in that character's menu and sending whichever message the client's own drop would. The id is never a thing an addon holds. |
| `slot:clear()` takes no arguments | An argument raises naming both the assignment and `slot:hold(nil)`. Clearing an empty slot is inert. It clears what the server has, where `slot:hold(nil)` releases your entry and hands the server's content back. On a held slot it is legal, and a beat later the server's write [ends the hold](#when-a-hold-ends). |
| Asynchronous | `res` and `clear` send to the server, which echoes before the slot changes. The next line reads the old content. `:res(name):use()` in one chain activates what was there before. React to `ActionbarChanged` on that slot, or wait a beat. |

```lua
local session = hafen.session():current()
session:actionbar():get(1):res("gfx/hud/act/mine")             -- protected: put "Mine" on the first slot
hafen.timer():after(0.5, function()
  session:actionbar():get(1):use()
end)
```

## Hold a slot (unprotected)

A [menu entry an addon added](menugrid.md#write-unprotected) sits on the bar too. It draws its icon in the slot and runs its Lua when the slot's key is pressed. The server owns the bar and knows nothing of the entry, so the client holds the slot. It draws over the server's content and hands it back untouched when the hold ends.

```lua
local session = hafen.session():current()
local dig_entry = session:menugrid():add("dig"):name("Auto-dig"):icon(hafen.asset():get("dig.png"))
dig_entry:on("Pressed", function() hafen.log():write("dug") end)
session:actionbar():get(11):hold(dig_entry)            -- the entry now draws in that slot, and fires from it
```

| Method | Returns | Permission | Description |
|---|---|---|---|
| `slot:hold()` | `Pagina \| nil` | Unprotected | The entry this slot is held for. `nil` for every slot the server owns, a slot holding one of the game's own actions included (`slot:res()` names that, [`session:menugrid():get(name)`](menugrid.md) is its `Pagina`). |
| `slot:hold(pagina)` | the `Slot` | Unprotected | Hold this slot for an entry an addon added to that character's menu, yours or another's. |
| `slot:hold(nil)` | the `Slot` | Unprotected | End the hold, whoever took it. Inert on a slot nobody holds. |

| Rule | Detail |
|---|---|
| Immediate, no permission | Nothing reaches the server, where `slot:res(name)` is a round trip. [`ActionbarChanged`](event/bus/character.md#character-and-status) fires on both edges. |
| One character's pair | A slot is held for an entry in that same character's menu. An entry not in it (added on another login, or removed) is refused naming which addon's entry it is and why it is not there. |
| Whose entry does not matter | The bar is one shared surface: [`session:menugrid():get(res)`](menugrid.md) hands you any entry's `Pagina` and `slot:hold(pagina)` places it, as the player's drag does. A hold places an entry and writes nothing on it. Renaming, re-iconing and handlers stay [the entry's owner's](menugrid.md#write-unprotected). |
| While held | `slot:res()` is the entry's `addon/…` identity, `slot:name()` the name you gave it, `slot:empty()` is false. Pressing the slot (key, click, `slot:use()`) runs your [`pagina:on("Pressed", fn)`](menugrid.md#a-click-runs-your-lua) handlers: one button in two places through the same code. |
| Dragging does the same | Drag one of your entries off the action menu onto a slot and the client holds it, sending nothing. |
| Stacked holds | Holding a slot another addon holds is allowed and the last write wins. What is carried through is the server's content, so one release puts the game's action back. A slot held for another addon's entry follows that entry: it goes back when that addon removes the entry, reloads or is disabled. |

| Refusal | Message names |
|---|---|
| `slot:hold(pagina)` for one of the game's own entries | That it is the client's own entry, pointing at `slot:res(name)`. |
| `slot:hold(pagina)` for an entry you removed | That the entry is no longer in the menu. |
| `slot:hold(pagina)` for an entry another addon removed | Not in that character's menu, naming that addon. |
| `slot:hold(7)`, `slot:hold("dig")` | Expected the `Pagina` object, or `nil`. |
| `slot:res("addon/myaddon/dig")` | That it is an entry an addon added, pointing back at `slot:hold`. |

### When a hold ends

| What happened | The slot goes back to | The record is |
|---|---|---|
| `slot:hold(nil)` | The server's own content. | Forgotten. |
| A right-click on the slot | The server's own content. The right-click is not sent, so nothing is cleared. | Forgotten. |
| `session:menugrid():remove(pagina)` | The server's own content. | Remembered. |
| Your addon reloads, or you log out | The server's own content. | Remembered. |
| Your addon is disabled | The server's own content. | Remembered. |
| The server writes that slot | What the server wrote: that is the slot's content now. | Forgotten. |

Every row but the last puts back what the server has in the slot, unchanged. In the last row the message being handled is the server assigning or clearing that slot, so what it wrote stands and the hold ends. `slot:res(name)` on a slot you hold is that row, once the server's echo arrives.

### A hold is remembered

The client keeps, per character, which entry belongs in which slot, and puts it back the moment that entry exists again. The call that re-applies it is [`session:menugrid():add(id)`](menugrid.md#sessionmenugridaddid), the one your addon already makes.

```lua
hafen.event():on("SessionEnteredWorld", function(session)
  local dig_entry = session:menugrid():add("dig"):name("Auto-dig"):icon(hafen.asset():get("dig.png"))
  dig_entry:on("Pressed", function() hafen.log():write("dug") end)
end)                                         -- if it was on that bar, it is on that bar again
```

| Rule | Detail |
|---|---|
| Inside the `add` | The entry lands in its slot before `add` returns, so the next line reads `slot:hold()`. Your addon stores nothing: a row of the client's own file, keyed by character, written when the hold is taken or ended. `slot:hold(pagina)` is remembered as a drag is. |
| One entry per slot | The live hold chains, the record does not. Two addons holding one slot in turn are one remembered entry, the last written. The bar comes back with that one. |
| Two endings, remembered differently | Ending by hand (`slot:hold(nil)`, a right-click, the server taking the slot) says the entry no longer belongs there and the record goes. The entry going away (`:remove`, a reload, a logout) says nothing about the slot, so it waits. A `:reload` puts every button back. A player who right-clicked one off keeps it off. |
| Disabling keeps the slots | They go back to the server's content as the addon is torn down and the rows are kept, with its options and hotkeys. Enabling puts every button back as your `:add` runs. A right-click on a kept slot forgets it. |
| Per character | The same entry can stand in a different slot on another character. A slot held on one is not held on the next. |

---

## See Also

- [Session](session.md) — the address every read here goes through.
- [`session:menugrid`](menugrid.md) — where the names the write takes come from, and where your entries live.
- [Permissions](../guides/permissions.md) — the keys the protected writes share.
- [`ActionbarSlot`](types/ui.md#actionbarslot) — the snapshot shape `:info()` returns.
- [Events](event/bus/character.md#character-and-status) — `ActionbarChanged`.
