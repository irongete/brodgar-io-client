# hafen.store: saved variables

Keep data across sessions. A **saved variable** is a Lua table you declare in your manifest; the engine
restores it on load and writes it back to disk for you. Saved variables are **unprotected** — they are
written only inside your addon's own save folder.

**A scope is an address.** A character's saved variables are that character's own folder, so they are reached
through [its session](session.md); an account's are your addon's — one file for the client, whichever
character is up — so they are reached without naming anyone.

```lua
local settings = hafen.session():current():store():get("settings")   -- the live table, not a copy

settings.enabled = true
settings.count = (settings.count or 0) + 1
```

## Declare a variable

Every saved variable is named in `manifest.json`, and nothing else here is persisted:

```json
"saved_variables": ["settings", { "name": "account", "scope": "account" }]
```

| Declaration | Scope | Where it lands | Reached through |
|---|---|---|---|
| a bare name | per character | `savedata/<genus>_<char>/<addon>.json` | the session whose character it is |
| `{ "name": …, "scope": "account" }` | account-wide, shared by all your characters | `savedata/account/<addon>.json` | no address |

Any scope other than `"account"` is per-character. A name declared twice keeps the first declaration.

## Read and write

| Method | Description |
|---|---|
| `s:store():get(name)` | that character's persisted table for one declared variable |
| `s:store():flush()` | write that character's changed tables to disk now |
| `hafen.store():get(name)` | the account's persisted table for one declared variable |
| `hafen.store():flush()` | write the account's changed tables to disk now |

**What `get` hands back is the table itself, not a copy**, so writing into it is the whole of saving: there
is no "put it back" step, and a reference you keep in a local goes on being the one written to disk. It is
also stable for the addon's whole life — a restore refills it in place rather than replacing it — so a table
captured at load time is still valid an hour later. Assign *into* it; you cannot assign over it.

A declared name is **always a usable table**, empty when there is nothing saved yet, so you never have to
create it. A name your manifest does not declare is an error naming the ones it does, because the set of
saved variables is fixed when your addon loads and a misspelt one has no later meaning to wait for.

**The name picks the door, and the wrong door names the right one.** Scope is a manifest declaration rather
than something you choose at the call, so asking for a per-character variable without an address is an error
naming the session spelling, and asking for an account variable through a session is an error naming the
other. Neither answers anything: a scope you did not mean is a file you did not mean.

## The character has to be the one on screen

The client can hold several characters logged in at once and draws one of them. There is **one** set of
per-character tables and they hold the character you are looking at, so `s:store()` answers for that session
and **raises for every other**: another character's variables are in their own file on disk and nothing of
them is in memory. Tab to that character and the same tables — the same table objects, so a reference you
cached is still the live one — hold its saved data instead, and holding nothing is what no character on
screen looks like.

**When each scope is ready.** Account tables are filled before your files run, so they are readable in the
file body and in `Load`. A character's tables are filled when that character comes to the screen — for the
first character that is just before `SessionEnteredWorld` fires, because the character's folder is not known
until then. Read them from there, not in `Load`.

**What survives.** The tables are stored as JSON, so tables, strings, numbers and booleans round-trip and
nothing else does — with one exception, and it is the one worth having: a
[Position](world.md#the-position-type) is written as its durable form and comes back **a Position**, so a
place you save is a place you get. A position on ground you have never visited has no durable form and is not
written at all; `p:durable()` is how you know. Anything else live — a function, a widget handle — is written
as a placeholder string and comes back as that string. Keys become strings unless the table is a `1..n`
array, and a `nil` value is simply an absent key. Store plain data and a rebuild on load.

## When it is written

| Scope | Written |
|---|---|
| account | on the timer, and when your addon is disabled or reloaded |
| per character | on the timer, and when that character **leaves the screen** — tabbed away from, or logged out |

The timer runs roughly every 30 seconds, so an ordinary quit loses nothing. A file whose content has not
changed is not rewritten, and writes are atomic, so an interrupted write cannot leave a half-file behind.

Your addon outlives every character switch, so a character's data cannot wait for it to be unloaded: their
moment is when they stop being the one on screen, and that is when their file is written and their tables are
handed over to whoever comes next. Dropping a session writes it too — log that character back in and what you
left is there.

`flush()` writes the scope you call it on immediately, and it is worth calling after a change the user would
be upset to lose and unnecessary the rest of the time. It is also the one write that **refuses**: it names
the path of the first value a saved variable cannot hold instead of writing it. The timer and the teardown do
not refuse — a write you did not ask for must not cost you the rest of your file, so they write the
placeholder above and carry on.

A file the engine cannot read or parse leaves your tables as they are, and the failure is logged rather than
raised: your addon starts with empty settings instead of not starting.

## The one thing saved without being declared

[`w:remember(name)`](ui/native.md#remembering-where-the-user-put-it-unprotected) keeps where a widget sits
and how big it is, and it needs no declaration and no code of yours. That is deliberate: a placement is
saved by the *user* moving something, not by your addon deciding to write it down, so making them declare
a variable to allow it would be asking permission for a gesture they made themselves.

It lands in a file of its own beside the one above, `savedata/<genus>_<char>/<addon>.layout.json`, and is
therefore **per character**, with the same timing: it arrives with the character it belongs to and there is
nothing to put back before one is on screen. Every write here writes it too, `s:store():flush()` included, so
an addon that only remembers places still saves on the timer and when its character goes though it declares
nothing at all.

## See also

- [`hafen.session`](session.md) — the address a character's saved variables are reached through
- [`hafen.json`](json.md) — the same serializer, when you want the string yourself
- [events](event/bus.md#sessions) — `SessionEnteredWorld`, where per-character data becomes readable
- [`hafen.http`](http.md) — fetching what you cache here
