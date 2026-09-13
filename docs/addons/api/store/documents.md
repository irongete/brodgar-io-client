# hafen.store: documents

A **document** is a saved variable: a Lua table you name in your manifest, which the client fills from your
[file](README.md) when your addon loads and writes back for you. You assign into it, and that is the whole of
saving. It is the shape for settings — a handful of values read at load and changed now and then; a record
that grows is a [table](tables.md). Every verb here is **unprotected**.

**A scope is an address.** A character's documents are that character's own rows, so they are reached
through [its session](../session.md) and answer about the character that session is playing, on screen or
not; your addon's own are one set of rows for the whole client, whichever account or character is up, so
they are reached without naming anyone.

```lua
local settings = hafen.session():current():store():get("settings")   -- the live table, not a copy
settings.enabled = true
settings.count = (settings.count or 0) + 1

local seen = hafen.store():get("seen")                                -- your addon's own
seen.lastLogin = os.time()
```

## Declare a document

Every document is named in `manifest.json` under `saved_variables`, and nothing else on this page is
persisted:

```json
"saved_variables": ["settings", { "name": "seen", "scope": "client" }]
```

| Declaration | Whose | Where it lands | Reached through |
|---|---|---|---|
| a bare name, or `{ "name": …, "scope": "character" }` | one character's, a row per character | your file, keyed by the character | the session whose character it is |
| `{ "name": …, "scope": "client" }` | your addon's own, one for the whole client | your file, keyed by nobody | `hafen.store()` |

Any other word is a manifest error naming the two. A name declared **twice is an error**: one name is one
table, so the second entry could only have been ignored, and a manifest that will not load says so at the
moment you can fix it.

**A character's key is named by the server**, so that name is checked before anything is written under it:
it is reduced to the characters a file name can hold, and then it has to be a name *inside* `savedata/`. A
`..`, an absolute name or a link pointing out of it is refused like any other path outside, and a character
whose name is refused has no key — the same state as a character who has not reached the world, and it
raises the same way.

## Read and write

| Method | Description |
|---|---|
| `s:store():get(name)` | that character's persisted table for one declared document |
| `s:store():list()` | the per-character names **this addon** declared, as a string array |
| `s:store():flush()` | write that character's changed documents and placements now; the store |
| `hafen.store():get(name)` | your addon's own persisted table for one declared document |
| `hafen.store():list()` | the client-scope names **this addon** declared, as a string array |
| `hafen.store():flush()` | write your addon's own changed documents and placements now; the store |

**What `get` hands back is the table itself, not a copy**, so writing into it is the whole of saving: there
is no "put it back" step, and a reference you keep in a local goes on being the one written to the file. It
is also stable for the addon's whole life — a restore refills it in place rather than replacing it — so a
table captured at load time is still valid an hour later. Assign *into* it; you cannot assign over it.

That makes it the [third kind of value](../conventions.md#snapshots-vs-handles) in this API, beside a
snapshot and a handle: a table the bridge owns and you write into, and the one place where a typo on a key
is silent and then persisted.

A declared name is **always a usable table**, empty when there is nothing saved yet, so you never have to
create it. A name your manifest does not declare is an error naming the ones it does, because the set of
documents is fixed when your addon loads and a misspelt one has no later meaning to wait for. An explicit
`nil` for the name raises like any other value.

**The name picks the door, and the wrong door names the right one.** Scope is a manifest declaration rather
than something you choose at the call, so asking for a character's document without an address is an error
naming the session spelling, and asking for your addon's own through a session is an error naming
`hafen.store()`. Neither answers anything: a scope you did not mean is a row you did not mean.

## Each character's documents are their own

The client can hold several characters logged in at once and draws one of them, and every one of them has
its own rows. `s:store()` answers for the session you named — the character on screen and the character
behind it alike — so two characters read and write two sets of rows, neither sees the other's keys, and the
handle you cached from one session goes on being that character's for as long as that session lives.

Two states have no documents to answer with, and both **raise** rather than hand back an empty table that
would take your writes and never save them: a session that is not live, whose rows were written when it
ended, and one that has not reached the world yet, which has no character and therefore no key at all.
`s:exists()` tells you the first; `SessionEnteredWorld` is when the second stops being true.

**When each scope is ready.** Your addon's own documents are filled before your files run, so they are
readable in the file body and in `Load`. A character's are filled when that session enters the world, just
before its `SessionEnteredWorld` fires, because the character's key is not known until then. Read them from
there, not in `Load`.

## What survives

A document is stored as JSON, so tables, strings, numbers and booleans round-trip and nothing else does —
with one exception, and it is the one worth having: a [Position](../position.md) is written as its durable
form and comes back **a Position**, so a place you save is a place you get. A position on ground you have
never visited has no durable form and is not written at all; `p:durable()` is how you know. Anything else
live — a function, a widget handle — is written as a placeholder string and comes back as that string, and
so is a table nested deeper than [`hafen.json`](../json.md)'s depth cap, because one unwritable corner
must not cost you the row. Keys become strings unless the table is a `1..n` array, and a `nil` value is an
absent key. Store plain data and rebuild on load.

**A cycle is one of the things a document cannot hold**, and `flush()` names it like any other: a table
that contains itself has no JSON spelling, and the forgiving writes put the literal text `"<cycle>"` where
it was, which reads back as text. So do the **keys**: a document is keyed by strings and numbers, and a
function, table or boolean key is named at the same door its values are.

## When it is written

| Scope | Written |
|---|---|
| your addon's own | on the timer, when your addon is disabled or reloaded, and when the client quits |
| a character's | on the timer, when that session **ends** or picks another character, and when the client quits |

Quitting writes both scopes before the process ends, so a value you set and never flushed is there when you
log back in. The timer runs roughly every thirty seconds and is what covers the other way out — a crash or
a kill, where the client gets to write nothing at all. A row whose content has not changed is not rewritten,
and each scope's rows are written in one transaction, so an interrupted write leaves the row it had.

Your addon outlives every character switch, so a character's data cannot wait for it to be unloaded: their
moment is when the session holding it stops playing them. Tabbing between characters writes nothing and
loses nothing, because each session keeps its own tables the whole time. Dropping a session writes it — log
that character back in and what you left is there.

`flush()` writes the scope you call it on immediately, and it is worth calling after a change the user would
be upset to lose and unnecessary the rest of the time. It is also the one write that **refuses**: it names
the path of the first value a document cannot hold instead of writing it, and it refuses first of all when
the file is [unavailable](README.md#when-the-file-cannot-be-opened). The timer and the teardown do not
refuse — a write you did not ask for must not cost you the rest of your file, so they write the placeholder
above and carry on — but they **say so**: each of those writes logs the path of the first value it degraded,
so a document that quietly turned into text is reported whether or not you ever call `flush()`.

A row the client cannot read or parse leaves your table as it is, and the failure is logged rather than
raised: your addon starts with empty settings instead of not starting, and that scope is
[read-only for the session](README.md#when-the-file-cannot-be-opened). A row that parses cleanly into
something that is **not** a document — a single value, `null` — is the same failure and is reported the same
way, rather than loading nothing in silence.

## The one thing saved without being declared

[`w:remember(name)`](../ui/native.md#remembering-where-the-user-put-it-unprotected) keeps where a widget
sits and how big it is, and it needs no declaration and no code of yours. That is deliberate: a placement
is saved by the *user* moving something, not by your addon deciding to write it down, so making them declare
a document to allow it would be asking permission for a gesture they made themselves.

**It is filed under the tree the widget stands in**, in rows of their own beside the documents:

| The widget | Its rows | Because |
|---|---|---|
| one of a session's own, `s:ui():match("@ChatUI")` | keyed by **that character** | where the user dragged that character's chat window is a fact about that character |
| one you built, `hafen.ui():window()` | keyed by nobody, like your addon's own documents | it stands in your layer, which belongs to no character and outlives all of them |

So a session's own window has nothing to put back until **that** session is in world, and tabbing moves
nothing: each record was already under the character it belongs to. The two `flush()` verbs each write the
rows they name — `hafen.store():flush()` your addon's own placements, `s:store():flush()` that character's —
and the timer, a tab and the close write every one of them, so an addon that only remembers places still
saves though it declares nothing at all.

## See also

- [the file](README.md) — where it is, when it is written and closed, and the state when it cannot be opened
- [tables](tables.md) — the shape for a record, where a document would grow
- [`hafen.session`](../session.md) — the address a character's documents are reached through
- [`hafen.json`](../json.md) — the same serializer, when you want the string yourself
- [events](../event/bus/lifecycle.md#sessions) — `SessionEnteredWorld`, where a character's documents are readable
