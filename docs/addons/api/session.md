# hafen.session: the logins this client holds

The client can hold several accounts logged in at once, and draws one of them. `hafen.session()` **is**
the collection of them, and a **Session** is how you name one character rather than whichever is on
screen.

```lua
local s = hafen.session():current()                       -- the session on screen, nil on the login screen
if s then
  hafen.log():write(s:user() .. " is playing " .. (s:character() or "nobody yet"))
end
```

| Call | Returns |
|---|---|
| `hafen.session():current()` | the `Session` on screen, or `nil` |
| `hafen.session():get(user)` | the `Session` for that **account** — always an object, even for an account nobody is logged in as |
| `hafen.session():list(filter)` | the sessions the client holds, in the order they joined |

Every session the client holds is whole: connected, ticked, answering the server, with a character and
a world of its own. One of them is drawn and the rest are not, and which one that is changes when the
player tabs between them.

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
a table key.

> **`:current()` changes under you.** It answers whichever session holds the screen at the moment you
> ask, so take it inside your handler rather than keeping one from load time. What you may keep is a
> Session itself: it names one account and never becomes another.

## Read

The first five are called on the collection, the rest on a `Session`. Nothing here is protected, and a
`Session`'s own verbs never throw.

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
| `s:info()` | [`Session`](types.md#session) | a plain-table **snapshot**, the escape hatch for logging |

**`:get` addresses, it does not search.** The account name is the whole of a Session, so there is
nothing to miss: a name read out of [saved variables](store.md) hands back an object before that account
logs in and after it goes, and `:exists()` says which. The key is the account and only the account, so a
character name hands back a session that does not exist, and anything that is not a string — a number, a
Session — raises. To search, use the ordinary [filter](conventions.md#the-filter-argument):
`hafen.session():find("bo")` matches part of an account name.

**`:current()` reads and never writes.** Taking the screen is a gesture of the player's — the switcher
window, an Alt-click, `:session anchor` — so the verb refuses an argument rather than accepting one you
could not have meant.

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

## See also

- [events](event/bus.md#sessions) — the four moments a session announces
- [`hafen.player`](player.md) — the character, and the anchor for your own Gob
- [`hafen.store`](store.md) — saved variables, per character and per account
- [conventions](conventions.md#the-grammar) — collections, interned objects and the filter argument
- [data types](types.md#session) — the snapshot shape `:info()` returns
