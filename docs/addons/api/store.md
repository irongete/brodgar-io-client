# hafen.store: saved variables

Keep data across sessions. A **saved variable** is a Lua table you declare in your manifest; the engine
restores it on load and writes it back to disk for you. Saved variables are **unprotected** — they are
written only inside your addon's own save folder.

**A scope is an address.** A character's saved variables are that character's own folder, so they are reached
through [its session](session.md) and answer about the character that session is playing, on screen or not;
an account's are your addon's — one file for the client, whichever character is up — so they are reached
without naming anyone.

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

Any scope other than `"account"` is per-character. A name declared **twice is an error**: one name is
one table, so the second entry could only have been ignored, and a manifest that will not load says so
at the moment you can fix it.

**A character's folder is named by the server**, so that name is checked before anything is written under
it: it is reduced to characters a filesystem can hold, and then it has to be a folder *inside* `savedata/`.
A `..`, an absolute name or a link pointing out of it is refused like any other path outside, and a character
whose name is refused has no folder — the same state as a character who has not reached the world, and it
raises the same way.

## Read and write

| Method | Description |
|---|---|
| `s:store():get(name)` | that character's persisted table for one declared variable |
| `s:store():list()` | the per-character names **this addon** declared, as a string array |
| `s:store():flush()` | write that character's changed tables to disk now |
| `hafen.store():get(name)` | the account's persisted table for one declared variable |
| `hafen.store():list()` | the account-scope names **this addon** declared, as a string array |
| `hafen.store():flush()` | write the account's changed tables to disk now |

**What `get` hands back is the table itself, not a copy**, so writing into it is the whole of saving: there
is no "put it back" step, and a reference you keep in a local goes on being the one written to disk. It is
also stable for the addon's whole life — a restore refills it in place rather than replacing it — so a table
captured at load time is still valid an hour later. Assign *into* it; you cannot assign over it.

That makes it the [third kind of value](conventions.md#snapshots-vs-handles) in this API, beside a snapshot
and a handle: a table the bridge owns and you write into, and the one place where a typo on a key is silent
and then persisted.

A declared name is **always a usable table**, empty when there is nothing saved yet, so you never have to
create it. A name your manifest does not declare is an error naming the ones it does, because the set of
saved variables is fixed when your addon loads and a misspelt one has no later meaning to wait for.

**The name picks the door, and the wrong door names the right one.** Scope is a manifest declaration rather
than something you choose at the call, so asking for a per-character variable without an address is an error
naming the session spelling, and asking for an account variable through a session is an error naming the
other. Neither answers anything: a scope you did not mean is a file you did not mean.

## Each character's variables are their own

The client can hold several characters logged in at once and draws one of them, and every one of them has
its own tables. `s:store()` answers for the session you named — the character on screen and the character
behind it alike — so two characters read and write two folders, neither sees the other's keys, and the
handle you cached from one session goes on being that character's for as long as that session lives.

Two states have no variables to answer with, and both **raise** rather than hand back an empty table that
would take your writes and never save them: a session that is not live, whose data went to disk when it
ended, and one that has not reached the world yet, which has no character and therefore no folder at all.
`s:exists()` tells you the first; `SessionEnteredWorld` is when the second stops being true.

**When each scope is ready.** Account tables are filled before your files run, so they are readable in the
file body and in `Load`. A session's tables are filled when that session enters the world, just before its
`SessionEnteredWorld` fires, because the character's folder is not known until then. Read them from there,
not in `Load`.

**What survives.** The tables are stored as JSON, so tables, strings, numbers and booleans round-trip and
nothing else does — with one exception, and it is the one worth having: a
[Position](position.md) is written as its durable form and comes back **a Position**, so a
place you save is a place you get. A position on ground you have never visited has no durable form and is not
written at all; `p:durable()` is how you know. Anything else live — a function, a widget handle — is written
as a placeholder string and comes back as that string, and so is a table nested deeper than
[`hafen.json`](json.md)'s depth cap, because one unwritable corner must not cost you the file. Keys become
strings unless the table is a `1..n` array, and a `nil` value is simply an absent key. Store plain data and
a rebuild on load.

## When it is written

| Scope | Written |
|---|---|
| account | on the timer, when your addon is disabled or reloaded, and when the client quits |
| per character | on the timer, when that session **ends** or picks another character, and when the client quits |

Quitting writes both scopes before the process ends, so a value you set and never flushed is there when you
log back in. The timer runs roughly every 30 seconds and is what covers the other way out — a crash or a
kill, where the client gets to write nothing at all. A file whose content has not changed is not rewritten,
and writes are atomic, so an interrupted write cannot leave a half-file behind.

Your addon outlives every character switch, so a character's data cannot wait for it to be unloaded: their
moment is when the session holding it stops playing them. Tabbing between characters writes nothing and
loses nothing, because each session keeps its own tables the whole time. Dropping a session writes it — log
that character back in and what you left is there.

`flush()` writes the scope you call it on immediately, and it is worth calling after a change the user would
be upset to lose and unnecessary the rest of the time. It is also the one write that **refuses**: it names
the path of the first value a saved variable cannot hold instead of writing it. The timer and the teardown do
not refuse — a write you did not ask for must not cost you the rest of your file, so they write the
placeholder above and carry on — but they **say so**: each of those writes logs the path of the first value
it degraded, so a variable that quietly turned into text is reported whether or not you ever call `flush()`.

A file the engine cannot read or parse leaves your tables as they are, and the failure is logged rather than
raised: your addon starts with empty settings instead of not starting. A file that parses cleanly into
something that is **not** a JSON object — an array, a number, `null` — is the same failure and is
reported the same way, rather than loading nothing in silence.

**A cycle is one of the things a saved variable cannot hold**, and `flush()` names it like any other: a
table that contains itself has no JSON spelling, and the forgiving writes put the literal text
`"<cycle>"` where it was, which reads back as text. So do the **keys**: a saved table is keyed by
strings and numbers, and a function, table or boolean key is named at the same door its values are.

> **A scope whose file could not be read is read-only for the rest of the session.** The tables are empty
> because the client could not read the file, not because you have saved nothing — and a write is a whole,
> atomic replacement, so the first changed value would replace the only copy of your data with that empty
> set. So nothing is written back to that file: the timer, `flush()` and the teardown all skip it, and the
> log names the file and says the scope is read-only. A successful load lifts it, which is a `:reload` or
> the next launch once the file is readable again. A file that is simply **not there yet** is not this
> case — nothing has been saved, an empty scope is the whole truth, and the first write creates it. The
> [remembered placements](#the-one-thing-saved-without-being-declared) beside it are kept the same way.

## The one thing saved without being declared

[`w:remember(name)`](ui/native.md#remembering-where-the-user-put-it-unprotected) keeps where a widget sits
and how big it is, and it needs no declaration and no code of yours. That is deliberate: a placement is
saved by the *user* moving something, not by your addon deciding to write it down, so making them declare
a variable to allow it would be asking permission for a gesture they made themselves.

**It is filed under the tree the widget stands in**, in a file of its own beside the one above:

| The widget | Its file | Because |
|---|---|---|
| one of a session's own, `s:ui():match("@ChatUI")` | `savedata/<genus>_<char>/<addon>.layout.json` | where the user dragged **that character's** chat window is a fact about that character |
| one you built, `hafen.ui():window()` | `savedata/account/<addon>.layout.json` | it stands in your layer, which belongs to no character and outlives all of them |

So a session's own window has nothing to put back until **that** session is in world, and tabbing moves
nothing: each record was already under the character it belongs to. The two `flush()` verbs each write the
file they name — `hafen.store():flush()` the account's placements, `s:store():flush()` that character's —
and the timer, a tab and the close write every one of them, so an addon that only remembers places still
saves though it declares nothing at all.

## See also

- [`hafen.session`](session.md) — the address a character's saved variables are reached through
- [`hafen.json`](json.md) — the same serializer, when you want the string yourself
- [events](event/bus/lifecycle.md#sessions) — `SessionEnteredWorld`, where per-character data becomes readable
- [`hafen.http`](http.md) — fetching what you cache here
