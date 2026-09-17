# session:flowermenu: The Radial Menu

The ring of petals a right-click puts up, the game's main context gesture. `session:flowermenu()` is the menu one character has open. It says what it offers, which petal to pick, whether the client paints it, and a petal of your own on it. Picking and dismissing are protected. Adding a petal and painting the ring are not.

```lua
hafen.event():on("FlowerMenuAdded", function(petals, session)
  local labels = {}
  for index, petal in ipairs(petals) do labels[index] = petal:label() end
  hafen.log():write(session:user() .. " menu: " .. table.concat(labels, ", "))   -- Chop, Pick branch, …
end)

hafen.event():on("FlowerMenuRemoved", function(label)
  hafen.log():write(label and ("picked " .. label) or "cancelled")
end)
```

---

| Rule | Detail |
|---|---|
| One character's, not the screen's | A ring only goes up on the character you are looking at, but the menu is a widget in that character's tree. Tab away with a ring up and it is still up, readable and pickable through `hafen.session():get("alt"):flowermenu()`. Every other character answers what it answers with nothing open: an empty array and `0`. |
| The events carry the session | Both hand that character's session as their last argument, so a handler needs no lookup. |
| Two menus at once | Should not happen: an open menu grabs the mouse and keyboard, and a menu that ends is gone at once. If it does, the verbs answer for the first one that character's tree holds. |

## Read

| Method | Returns | Permission | Description |
|---|---|---|---|
| `session:flowermenu():list(filter)` | [`Petal`](#a-petal)`[]` | Unprotected | The petals in ring order. Empty when no menu is open. |
| `session:flowermenu():count()` | `number` | Unprotected | How many petals are on the ring. `0` when none is open. |
| `session:flowermenu():get(n)` | `Petal \| nil` | Unprotected | The petal at that 1-based ring position. `nil` past the ring. Raises for a number that is not whole. |
| `session:flowermenu():find(filter)` | `Petal \| nil` | Unprotected | The first petal whose caption matches the [filter](conventions.md#the-filter-argument). |
| `session:flowermenu():gob()` | [Gob](gob.md) `\| nil` | Unprotected | The object the ring was opened on. |

| Rule | Detail |
|---|---|
| An empty ring throws nothing | No menu open is the ordinary state. Every read answers before the character has entered the world. `:get(n)` alone can raise, on a non-whole argument. The position is the one `petal:index()` answers and the ring's `1`–`9` keys take. |
| `:gob()` answers in the login asked through | That character's own world, where the click happened. |
| Where `:gob()` comes from | The menu carries no object. The client matches the ring against the click that opened it. Exact for a click on an object. `nil` for a menu opened from an inventory item (a click on a window). `nil` for the Kin window's menu and every menu the client puts up for itself. `nil` too for a menu not opened by the click it would be matched to. You clicked something else in between, or it arrived long after. |
| When to read `:gob()` | Inside `FlowerMenuAdded`. It still answers inside `FlowerMenuRemoved`, and is `nil` once the ring is gone. |

```lua
hafen.event():on("FlowerMenuAdded", function(petals)
  local gob = hafen.session():current():flowermenu():gob()
  local name = gob and gob:name()
  if name and name:find("tree") then
    for _, petal in ipairs(petals) do hafen.log():write("a tree offers: " .. petal:label()) end
  end
end)
```

## A petal

A petal belongs to the ring it came off. One held past the close reports `:exists()` false rather than pointing at the ring on screen now. Picking it raises rather than committing another ring's petal at the same place.

| Method | Returns | Permission | Description |
|---|---|---|---|
| `petal:label()` | `string \| nil` | Unprotected | The caption its ring paints. `nil` once that ring has closed. |
| `petal:index()` | `number` | Unprotected | Its 1-based place on the ring. Always answers. |
| `petal:wire()` | `number` | Unprotected | The 0-based number the menu itself sends for it. Always answers. |
| `petal:select()` | the petal | `flowermenu.select` | Pick it. Raises once its ring has closed. |
| `petal:exists()` | `boolean` | Unprotected | Whether the ring this petal is on is still the open one. |
| `petal:native()` | `boolean` | Unprotected | Whether the server sent it. `false` for [one you added](#write-unprotected). Always answers. |
| `petal:info()` | [`Petal`](types/ui.md#petal) | Unprotected | A plain-table snapshot. |

| Rule | Detail |
|---|---|
| Position is identity | The `1`–`9` key the menu accepts and the number [`select(n)`](#write-protected) takes. `petal:wire()` is what the client puts in the message. The opposite of [`session:menugrid`](menugrid.md), whose catalogue grows and refuses positions. A ring is frozen once announced. |
| Read, or keep | A ring is open only while the player decides, so read a petal rather than keep it. Keeping one is safe and says so through `:exists()`. |
| The menu answers until its `FlowerMenuRemoved` handlers return | A pick, an Esc or a click away ends the ring the same instant. Those handlers run first with the ring still there, so `:count()` inside one is not yet `0`. Read what you need from the event's payload. |

## Write (protected)

The client sends only shapes a player could compose.

| Method | Returns | Permission | Description |
|---|---|---|---|
| `session:flowermenu():select(label)` | the section | `flowermenu.select` | Pick the petal captioned `label`, matched whole and case-insensitively. |
| `session:flowermenu():select(n)` | the section | `flowermenu.select` | Pick the petal at position `n`, counting from `1`. |
| `session:flowermenu():cancel()` | the section | `flowermenu.cancel` | Close the menu with nothing chosen, as Esc does. |

| Rule | Detail |
|---|---|
| Permission | Separate keys, so an addon may declare one without the other. `flowermenu.*` covers both. An undeclared key raises naming it ([the permission model](conventions.md#the-permission-model)). One key covers every character: the player could have tabbed there and picked. |
| String is caption, number is position | `select("3")` picks the petal captioned `3`, never the third one. |
| Captions are the client's own English | A [catalogue](locale.md) lands at the render, so `petal:label()`, the event payloads and the spelling `select(label)` matches name what you wrote, translated client or not. |
| Through the client's own selection | A petal the client handles by itself (the Kin window's entries, [one you added](#write-unprotected)) is handled locally. The server hears only the dismissal. |
| Raises where the reads answer | `select` raises when no menu is open and when no caption matches. It raises when the position is outside `1`..the petal count, and when the key is neither string nor number. `cancel()` raises when no menu is open. Each error names the character and lists the open ring, numbered. |
| Pick inside `FlowerMenuAdded` | The ring is laid out whole and nothing is painted yet, so a pick there is made before the player sees a ring. |

> **One ring takes one pick, and nothing marks it as taken.** Every addon subscribed to `FlowerMenuAdded` receives the same menu. The order between them is undefined. The client sends each pick as made. An addon that picks unconditionally decides the ring for every other. Pick on a ring you recognise (`:gob()` and the payload) and read `FlowerMenuRemoved` for the label committed.

## Write (unprotected)

`session:flowermenu():add(label, fn)` puts a petal of your own on the ring, laid out after the server's, painted and picked like them. It changes what the client paints and sends nothing, so it needs no key.

| Method | Returns | Permission | Description |
|---|---|---|---|
| `session:flowermenu():add(label, fn)` | [`Petal`](#a-petal) | Unprotected | Append a petal captioned `label`. Picking it runs `fn(petal, session)` and ends the ring. |

```lua
hafen.event():on("FlowerMenuAdded", function(petals, session)
  local gob = session:flowermenu():gob()
  if gob and gob:name():find("tree") then
    session:flowermenu():add("Remember", function(petal, ring_session)
      hafen.log():write("remembered " .. gob:name() .. " for " .. ring_session:user())
    end)
  end
end)
```

| Rule | Detail |
|---|---|
| Inside `FlowerMenuAdded` and nowhere else | A ring is laid out once, when announced. Called later, ring up or not, `add` raises naming that event. The petal is on `session:flowermenu():list()` from that call on with `petal:native()` `false`. The array the event carried does not grow. |
| Picking it | The player's click or digit, or `petal:select()` (still under `flowermenu.select`), runs `fn` with the petal and the session. Then it ends the ring with nothing chosen on the server's side. Only the dismissal is sent, and `FlowerMenuRemoved` carries your `label`. |
| Where `fn` runs | [Under the ring's tree](threading.md#where-each-handler-runs), as a control's `Pressed` does: it reads that character freely and reaches no other tree. An error in it is a line in the log and the ring still ends. |
| Arguments | `label` a non-empty string, `fn` a function. Either raises naming the argument. The caption is what `petal:label()` answers, `select(label)` matches and the ring paints. |
| Addon disabled with its ring up | The petal stays painted, still ends the ring when picked, and runs nothing. |

## Drawn or not (unprotected)

`session:flowermenu():visible(flag)` says whether the client paints that character's open ring. It is the read/write pair [`gob:visible(flag)`](look.md#drawn-or-not-unprotected) is, and the one verb here that changes something without a key.

| Method | Returns | Permission | Description |
|---|---|---|---|
| `session:flowermenu():visible()` | `boolean \| nil` | Unprotected | Whether the open ring is painted. `nil` when no menu is open. |
| `session:flowermenu():visible(flag)` | the section | Unprotected | Paint it, or stop painting it. |

```lua
hafen.event():on("FlowerMenuAdded", function(petals, session)
  for _, petal in ipairs(petals) do
    if petal:label() == "Pick" then
      session:flowermenu():visible(false):select("Pick")   -- hands the section back, so this chains
      return
    end
  end
end)
```

| Rule | Detail |
|---|---|
| A hidden ring is still open | `:list()`, `:count()` and `:gob()` answer as painted, `select(label)` picks from it, it ends with a `FlowerMenuRemoved`. |
| No blind click | A hidden ring holds the mouse and keyboard as a painted one does. A click on it can only end it (nothing chosen, as clicking away does). Its `1`–`9` keys do nothing. Esc is unchanged. A ring an addon hid and did not decide is never a trap. |
| `flag` | `true` or `false`. Anything else raises naming the argument, a number most of all since `0` is true in Lua. The read answers `nil` with no menu open. The write raises there, naming the character, as [picking and cancelling](#write-protected) do. |
| `visible(true)` | Paints a ring mid-life from the next frame. The flag dies with the ring: the next menu on any character is painted. A pick is a message to the server, so a ring you picked from is up while that goes out and back. `visible(true)` after `select()` paints that round trip. |

## The events

| Event | Payload | Fires |
|---|---|---|
| `FlowerMenuAdded` | [`Petal`](#a-petal)`[]`, the ring in ring order. The session as the last argument | A radial menu appears, at the moment the server's petal set is complete. |
| `FlowerMenuRemoved` | `string \| nil`, the label picked. The session as the last argument | That menu goes away. |

| Rule | Detail |
|---|---|
| One-to-one | Every `FlowerMenuAdded` is followed by exactly one `FlowerMenuRemoved`, however the menu ended: a pick, Esc, a click away, a dropped connection. The payload is the label on a pick and `nil` otherwise. |
| The payload is the ring as sent | The same Petals `session:flowermenu():list()` answers inside the handler, until a handler [adds one](#write-unprotected). `session` is [the session the event carries](event/bus/README.md#whose-character-it-was). |
| Client menus too | The Kin window's right-click menu never reaches the server and still opens and closes here. |
| Input is held | An open menu holds the mouse and the keyboard, so a console command or a hotkey cannot react to a menu. A handler on these events, or a [timer](timer.md) armed from one, is how an addon acts on a menu. |

```lua
local pending_petals
hafen.event():on("FlowerMenuAdded", function(petals)
  pending_petals = petals
end)
hafen.event():on("FlowerMenuRemoved", function(label)
  if not label and pending_petals then
    local labels = {}
    for _, petal in ipairs(pending_petals) do labels[#labels + 1] = petal:label() or "?" end
    hafen.log():write("walked away from: " .. table.concat(labels, ", "))
  end
  pending_petals = nil
end)
```

---

## See Also

- [`hafen.session`](session.md) — the address the section is reached through.
- [Gob](gob.md) — what `:gob()` hands you.
- [`session:world`](world.md#write-protected) — `session:world():click(gob, 3)`, the right-click that puts the ring up.
- [`session:menugrid`](menugrid.md) — the other menu: the catalogue of what a character can do.
- [`hafen.event`](event/bus/character.md#the-radial-menu) — the bus these events sit on.
