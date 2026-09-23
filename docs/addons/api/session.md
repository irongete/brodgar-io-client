# hafen.session: The Logins This Client Holds

The client holds several accounts logged in at once and draws one of them. `hafen.session()` is the collection of them, and a Session names one character rather than whichever is on screen.

```lua
local session = hafen.session():current()               -- the session on screen, nil on the login screen
if session then
  hafen.log():write(session:user() .. " is playing " .. (session:character() or "nobody yet"))
end
```

---

Every session the client holds is whole: connected, ticked, answering the server, with a character and a world of its own. One is drawn. Which one changes when the player tabs and when an addon writes the screen.

## What hangs on a session

A Session is the address, so the reads about one character hang off it rather than off `hafen`.

| Verb | Gives you |
|---|---|
| [`session:world()`](world.md) | That character's world: the objects it sees, the ground it stands on, the grids it has streamed. |
| [`session:player()`](player.md) | That character: its own [Gob](gob.md), its cursor, the walk. |
| [`session:char()`](char.md) | Its sheet: attributes, learning points, weight, food, skills, credos, lore. |
| [`session:meter()`](meter.md) | Its HUD meter bars. |
| [`session:buff()`](buff.md) | The buffs on its buff bar. |
| [`session:study()`](study.md) | Its study window: the curiosities in it, their LP and attention. |
| [`session:quest()`](quest.md) | Its quest log, current and completed. |
| [`session:wound()`](wound.md) | Its wounds, as the Health and Wounds tab shows them. |
| [`session:kin()`](kin.md) | Its kin roster, and the writes that add, rename and re-group. |
| [`session:party()`](party.md) | The party it is in, in party sequence order. |
| [`session:chat()`](chat.md) | Its chat channels, the one on screen, their lines, and the line it says. |
| [`session:actionbar()`](actionbar.md) | Its hotbar: read a slot, use it, assign one, hold one for an entry of your own. |
| [`session:speed()`](speed.md) | Its crawl, walk, run and sprint selector. |
| [`session:craft()`](craft.md) | The recipe window it has open, and its Craft button. |
| [`session:menugrid()`](menugrid.md) | Its action menu: every action it knows, invoking one, entries of your own. |
| [`session:fight()`](fight.md) | Its combat schools, its manoeuvre deck, who it is fighting. |
| [`session:flowermenu()`](flowermenu.md) | The radial menu it has open, the petal to pick, a petal of your own, whether it is painted. |
| [`session:ui()`](ui/README.md) | The widgets the client put up for it: find one, watch for one, read its backpack. |
| [`session:store()`](store/vars.md) | Its own vars: that character's rows in your addon's file. |
| [`session:console()`](console.md#run-a-line-protected) | Its own console command line, and the verb that says one at it. |

```lua
for _, session in ipairs(hafen.session():list()) do
  local my_gob = session:player():gob()
  if my_gob then
    hafen.log():write(session:user() .. " stands on grid " .. my_gob:position():info().gridId)
  end
end
```

| Rule | Detail |
|---|---|
| Minted once, by identity | `session:world() == session:world()`. A draw callback reading them costs nothing. Each is only ever about one character: a client holding two logins has no *the* world, *the* roster, *the* action bar. The windows among them (a recipe, an action menu) belong to the character that put them up. They are usable on a session you tabbed away from. |
| Half a namespace | [`session:ui()`](ui/README.md) is the client's own widgets in one character's tree. The windows your addon builds stand in a layer above every session and stay [`hafen.ui():window()`](ui/custom.md). [`session:store()`](store/vars.md) is one character's vars. Your addon's own are reached without an address. |
| The console too | [`session:console()`](console.md#run-a-line-protected) is one character's command line. The commands your addon registers stay [`hafen.console():on()`](console.md#subscribe). |
| A read answers for the session named | Whichever is drawn. What belongs to the screen says so where described: [`worldToScreen` and `screenToWorld`](world.md#the-screen-and-the-world) name a pixel. [`click`](world.md#write-protected), `place`/`select` and [`hand:use`](player.md#the-hand) are pointer gestures. |

> **A character not on screen takes walk orders and nothing else.** An order to another login carries a destination and never a target. [`session:player():move(position)`](player.md#write-protected) reaches any session. Every other write reaches the one on screen and raises for the rest.

## The account is the name

A Session wraps the account it logged in as, the string `:session add` took and `:session list` prints. So it survives a character switch, a relogin and the session ending.

| Rule | Detail |
|---|---|
| One account, one character at a time | Picking another keeps the session alive: the server hands it a new world. The account is the key. `:character()` is a read of what the login plays now. |
| Interned per addon | `hafen.session():get("bob") == hafen.session():get("bob")`. The object in `:list()` is the one `:get` and `:current()` hand back. `seen[session] = true` works. What hangs off one keeps its identity too: `session:world()`, `session:player()` and every [Gob](gob.md#identity) read through it. |
| Not discarded, not stale | Every verb re-resolves: the bundle answers `nil` for a session that is over and answers again for one back under the same account. It lives as long as your reference to the `Session`, so a table keyed by the `SessionRemoved` payload holds it. Drop the key and the bundle goes. |
| `:current()` changes under you | Take it inside your handler rather than keeping one from load time. A Session itself names one account and never becomes another. |

## Read

Nothing here is protected, and a `Session`'s own reads never throw. `session:world()` and `session:player()` answer for a session that has ended. Everything under them reads `nil`-shaped.

| Method | Returns | Permission | Description |
|---|---|---|---|
| `hafen.session():list(filter)` | `Session[]` | Unprotected | The sessions the client holds, in join order, matching the [filter](conventions.md#the-filter-argument). A string matches the account name. |
| `hafen.session():count(filter)` | `number` | Unprotected | How many match, without building the array. |
| `hafen.session():find(filter)` | `Session \| nil` | Unprotected | The first that matches. `hafen.session():find("bo")` matches part of an account name. |
| `hafen.session():get(user)` | `Session` | Unprotected | The session for that account name. Always an object. |
| `hafen.session():current()` | `Session \| nil` | Unprotected | The session on screen. `nil` on the login screen. |
| `hafen.session():saved()` | `string[]` | Unprotected | The account names the login screen remembered ("Remember me"). Empty when none, never a token. The names and their tokens are kept in the system's user store (the registry on Windows), never under `savedata/`, so a copied client folder remembers no one. |
| `session:user()` | `string` | Unprotected | The account name. Answers for a session that has ended. |
| `session:character()` | `string \| nil` | Unprotected | The character this session is playing. `nil` until its HUD is up. |
| `session:exists()` | `boolean` | Unprotected | Whether the client still holds this session. |
| `session:info()` | [`Session`](types/world.md#session) | Unprotected | A plain-table snapshot, for logging. |

`:get` addresses, it does not search. A name read out of a [var](store/vars.md) hands back an object before that account logs in and after it goes. `:exists()` says which. The key is the account only, so a character name hands back a session that does not exist. Anything that is not a string raises.

## Write (unprotected)

| Method | Returns | Permission | Description |
|---|---|---|---|
| `hafen.session():current(session)` | the collection | Unprotected | Hand the screen to that session. |
| `hafen.session():current(nil)` | the collection | Unprotected | Hand it to the login screen, every login left running. |

### `hafen.session():current(session)`

The whole gesture: the RTS selection and the camera follow the screen, as with `:session anchor`. Naming the session already on screen changes nothing and fires no [`SessionSelected`](event/bus/lifecycle.md#sessions).

```lua
local sessions, current_session, current_index = hafen.session():list(), hafen.session():current(), 0   -- go round the logins
for index, session in ipairs(sessions) do if session == current_session then current_index = index end end
hafen.session():current(sessions[(current_index % #sessions) + 1])
```

| Rule | Detail |
|---|---|
| From any handler | One holding a widget tree included. It names which tree is drawn rather than writing a tree. So a `Draw` handler, a control's press, a hotkey and a console line may write it. `hafen.session():current()` reads the new session on the line after. The camera and the scene settle on the next frame. |
| Raises | A value that is not a `Session`. A `Session` the client does not hold (reachable since `:get(user)` mints for any name, `session:exists()` tells). A session with no screen of its own yet (`:exists()` `true`, still arriving or between characters): write the screen from its [`SessionEnteredWorld`](event/bus/lifecycle.md#sessions). |

### `hafen.session():current(nil)`

Go to the login screen: the write that answers the read's own `nil`, the one place an explicit `nil` means something.

| Rule | Detail |
|---|---|
| Your characters stay logged in | Every session ticks and answers the server behind it. What you log in there arrives as a session like any other. It takes the screen because nothing else holds it. It appears in `:list()` with its own [`SessionAdded`](event/bus/lifecycle.md#sessions). Write the screen to a session again to come back. |
| The way in for an account with no saved token | There is no other: `:session add` connects only an account the login screen has saved a token for, and `hafen.session():add` needs a remembered account. |
| No event | The [session family](event/bus/lifecycle.md#sessions)'s payload is a session, and none was picked. `hafen.session():current()` reads `nil` while the login screen holds the screen. |
| No permission | Taking the screen changes which widget tree is drawn and nothing else. The server is never told. The login screen logs nobody in or out. |

## Remembered accounts (protected)

```lua
for _, account_name in ipairs(hafen.session():saved()) do          -- connect every remembered account
  local remembered_session = hafen.session():get(account_name)
  if not remembered_session:exists() then hafen.session():add(account_name) end
end
```

| Method | Returns | Permission | Description |
|---|---|---|---|
| `hafen.session():add(user)` | `Session` | `session.add` | Log the remembered account in as a new session, without the login screen, using the login saved when the player ticked "Remember me". Hands back its `Session` at once, the object `:get(user)` gives. |
| `hafen.session():forget(user)` | the collection | `session.forget` | The login screen's "Forget me": delete the saved login and remove the account from `:saved()`. |

| Rule | Detail |
|---|---|
| The login runs in the background | `:add()` returns before the connection exists. The `Session`'s `:exists()` turns `true` when the server accepts, [`SessionAdded`](event/bus/lifecycle.md#sessions) fires then and `SessionEnteredWorld` when the character is in the world. The session plays the first character the server offers. |
| One account, one session | The login screen's own rule. |
| `:forget()` acts on the client only | A session that account has open stays connected. `:add(user)` refuses until the player logs in again with "Remember me" ticked. |

| Call | Result |
|---|---|
| `:add()`/`:forget()` without its permission | Raises, naming the permission. |
| A non-string or empty `user`, or an account not in `:saved()` | Raises. |
| `:add(user)` for an account that already has a session | Raises. `:get(user)` is that session. |
| The server rejects the saved login, is unreachable, or the account is still connecting from an earlier `:add()` | No error at the call: the client writes `hafen.session():add("<user>") failed: <reason>` on your addon's log line, and the `Session` keeps `:exists() == false`. |

## Write (protected)

| Method | Returns | Permission | Description |
|---|---|---|---|
| `hafen.session():remove(session)` | the collection | `session.close` | End the login `session` names, from the collection. |
| `session:close()` | the `Session` | `session.close` | End that session, the logout `:session drop` performs. |

```lua
for _, session in ipairs(hafen.session():list()) do           -- log the alts out, keep the one on screen
  if session ~= hafen.session():current() then session:close() end
end
```

| Rule | Detail |
|---|---|
| Two spellings, one act | The permission is checked before either looks at its argument. The refusal names the door you wrote. `hafen.session():remove("alice")` raises naming the object: pass the `Session` `:get(user)` hands you. |
| Asynchronous | The verb asks the session to close and returns. The login is in `hafen.session():list()` on the next line and leaves a tick or more later on its own thread. `session:exists()` answers, [`SessionRemoved`](event/bus/lifecycle.md#sessions) is the edge. |
| The session on screen | Allowed: the screen goes to another live session, or to the login screen when that was the last. `session:user()` answers afterwards, so the closed handle is still the key to drop your tables by. |
| Raises | A session the client does not hold, naming the account: closing the same one twice is the second call raising. |
| Why protected | Logging a character out leaves the client. The line the user reads is "log out any of your characters", covering every login the client holds. |

## Sessions that come and go

The [session events](event/bus/lifecycle.md#sessions) report a session connecting, reaching the world, taking the screen or ending, each handing the `Session`. They report changes, not state: an addon loaded while characters are already up receives no event for them, and `hafen.session():list()` reads what is there. A `:reload` announces `SessionEnteredWorld` for every session in the world, the one on screen first.

```lua
for _, session in ipairs(hafen.session():list()) do                -- what the client already holds
  hafen.log():write(session:user() .. ": " .. (session:character() or "not in the world"))
end
```

| Rule | Detail |
|---|---|
| Key by `session:user()` for what outlives the session | The string survives your addon being reloaded. An object is one addon's handle. |
| Every character event names a session | Meters, buffs, food, study, equipment, action bar, wounds, roster, quests and radial menu hand that character's `Session` as the handler's [last argument](event/bus/README.md#whose-character-it-was). |

---

## See Also

- [Events](event/bus/lifecycle.md#sessions) — the moments a session announces.
- [`session:world`](world.md) — one character's objects, terrain and coordinates.
- [`session:player`](player.md) — one character, its Gob, its cursor and the walk.
- [`hafen.store`](store/README.md) — your addon's file: a character's vars, and the addon's own.
- [Conventions](conventions.md#the-grammar) — collections, interned objects and the filter argument.
- [The Session snapshot](types/world.md#session) — the snapshot shape `:info()` returns.
