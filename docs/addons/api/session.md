# hafen.session: Sessions and Accounts

Access logged-in character sessions, manage multi-character environments, and retrieve character-scoped subsystems.

## Quick Example

```lua
-- Access character currently visible on screen
local current_session = hafen.session():current()
if current_session then
  local character_name = current_session:character() or "In Login Queue"
  hafen.log():write("Active character: " .. character_name)
end

-- Iterate over all connected accounts
for _, active_session in ipairs(hafen.session():list()) do
  hafen.log():write("Connected user: " .. active_session:user())
end
```

## Methods on `hafen.session()`

| Method | Parameters | Returns | Permission | Description |
|---|---|---|---|---|
| `:current()` | None | `Session \| nil` | `-` | The session currently being viewed on screen. |
| `:list()` | None | `Session[]` | `-` | Array of all active sessions connected in the client. |
| `:get(username)` | `string` | `Session` | `-` | The session for an account username. Always an object; `:exists()` reports whether that account is connected. |
| `:count()` | None | `number` | `-` | Total number of connected sessions. |
| `:saved()` | None | `string[]` | `-` | Account usernames the login screen remembered ("Remember me"). Empty table when there are none. Never a token. |
| `:add(username)` | `string` | `Session` | `session.add` | Logs a remembered account in as a new session, in the background. Returns that account's `Session` at once. |
| `:forget(username)` | `string` | `self` | `session.forget` | Forgets a remembered account: deletes its saved login and removes it from `:saved()`. Chains. |

## Methods on `Session`

| Method | Parameters | Returns | Permission | Description |
|---|---|---|---|---|
| `:user()` | None | `string` | `-` | Account username. |
| `:exists()` | None | `boolean` | `-` | `true` while this account is connected; `false` before `:add()` completes and after the session ends. |
| `:character()` | None | `string \| nil` | `-` | Character name (or `nil` if still at character select screen). |
| `:world()` | None | `World` | `-` | World subsystem for this character. |
| `:player()` | None | `Player` | `-` | Player entity, stats, and inventory. |
| `:ui()` | None | `UI` | `-` | Root of the character's in-game HUD widget tree. |
| `:chat()` | None | `Chat` | `-` | Chat channels subsystem. |
| `:store()` | None | `Store` | `-` | Character-scoped persistent storage (`:var()`). |
| `:close()` | None | `self` | `session.close` | Logs out this character session. |

## Remembered accounts

`hafen.session():add(username)` connects an account without the login screen, using the login the client saved when the player ticked "Remember me". `hafen.session():saved()` lists the accounts that have one, and `hafen.session():forget(username)` is the login screen's "Forget me" button.

```lua
-- Connect every remembered account that is not logged in yet
for _, account_name in ipairs(hafen.session():saved()) do
  local remembered_session = hafen.session():get(account_name)
  if not remembered_session:exists() then
    hafen.session():add(account_name)
  end
end

hafen.event():on("SessionEnteredWorld", function(session)
  hafen.log():write("In the world: " .. session:user())
end)
```

* **The login runs in the background.** `:add()` returns before the connection exists. The returned `Session` is the same object `:get(username)` gives; its `:exists()` turns `true` when the server accepts the login, `SessionAdded` fires at that moment and `SessionEnteredWorld` when the character is in the world.
* **The session plays the first character the server offers.** There is no argument to pick another.
* **One account is one session.** The login screen and `:add()` share the rule.
* **`:saved()` returns usernames only.** The token an account is remembered by is never exposed; no method returns it.
* **`:forget()` acts on the client only.** The saved login is deleted at once and the account leaves `:saved()`; a session that account has open stays connected, and `:add(username)` refuses until the player logs in again with "Remember me" ticked.

### Error cases

| Call | Result |
|---|---|
| `:add()` without the `session.add` permission | Raises, naming the permission. |
| `:add(username)` with a non-string or empty `username` | Raises. |
| `:add(username)` for an account not in `:saved()` | Raises. Log the account in once on the login screen with "Remember me" ticked. |
| `:add(username)` for an account that already has a session | Raises. `:get(username)` is that session. |
| `:forget()` without the `session.forget` permission | Raises, naming the permission. |
| `:forget(username)` with a non-string or empty `username`, or for an account not in `:saved()` | Raises. |
| The server rejects the saved login, is unreachable, or the same account is still connecting from an earlier `:add()` | No error at the call. The client writes `hafen.session():add("<username>") failed: <reason>` on your addon's log line, and the returned `Session` keeps `:exists() == false`. |

## Events

* `SessionAdded`: Dispatched when the server accepts a login, before the character is in the world (`function(session)`).
* `SessionEnteredWorld`: Dispatched when a session finishes loading into the game world (`function(session)`).
* `SessionSelected`: Dispatched when the active screen view switches to this session (`function(session)`).
* `SessionRemoved`: Dispatched when a session logs out or disconnects (`function(session)`).
