# hafen.store: documents

A **document** is a Lua table you name at `get`, saved for you: it exists the first time `get(name)` names
it, the client fills it from your [file](README.md) then and there, and writes it back when it changes.
You assign into it, and that is the whole of saving. It is the shape for settings — a handful of values
read once and changed now and then; a record that grows is a [table](tables.md). Every verb here is
**unprotected**.

**The door is the scope.** `hafen.store()` reaches your addon's own documents: one set of rows for the
whole client, whichever account or character is up. A character's documents are that character's own
rows, so they are reached through [its session](../session.md), `s:store()`, and answer about the
character that session is playing, on screen or not. Nothing else names a scope: the same name through
the two doors is two documents.

```lua
local settings = hafen.session():current():store():get("settings")   -- the live table, not a copy
settings.enabled = true
settings.count = (settings.count or 0) + 1

local seen = hafen.store():get("seen")                                -- your addon's own
seen.lastLogin = os.time()
```

## The two doors are the two scopes

| Door | Whose | Where it lands |
|---|---|---|
| `hafen.store()` | your addon's own, one for the whole client | a row of your file, keyed by nobody |
| `s:store()` | one character's, a row per character | a row of your file, keyed by that character |

**A character's key is named by the server** and is a row key and nothing else: the world's name and the
character's, reduced to letters, digits, `.` and `-`, joined by `_`. One character is one key in every file;
one who has not reached the world has none yet, and asking for their rows
[raises](#each-characters-documents-are-their-own).

## Read and write

| Method | Description |
|---|---|
| `s:store():get(name)` | the live table of that character's document `name`, empty until something is saved under it |
| `s:store():list()` | the names that exist in that character's scope, sorted, as a string array |
| `s:store():flush()` | write that character's changed documents now; the store |
| `hafen.store():get(name)` | the live table of your addon's own document `name`, empty until something is saved under it |
| `hafen.store():list()` | the names that exist in your addon's own scope, sorted, as a string array |
| `hafen.store():flush()` | write your addon's own changed documents now; the store |

**What `get` hands back is the table itself, not a copy**, so writing into it is the whole of saving: there
is no "put it back" step, and a reference you keep in a local goes on being the one written to the file. It
is also stable for the addon's whole life — the same object on every call, and a restore refills it in place
rather than replacing it — so a table captured at load time is still valid an hour later. Assign *into* it;
you cannot assign over it.

That makes it the [third kind of value](../conventions.md#snapshots-vs-handles) in this API, beside a
snapshot and a handle: a table the bridge owns and you write into, and the one place where a typo on a key
is silent and then persisted.

**A name is what you call the document, and there is nothing to hold it against.** So a misspelt name is
an empty document, as a misspelt table name is an empty table, and the same name through both doors is
two documents that never meet: a value written through one is not in the other. A document nobody names
in a session is neither read nor written. `name` is a non-empty string and nothing else: `nil`, a number
and `""` are each refused naming the parameter and what a name is.

**A name exists when it has a row in your file, or a table handed out this session.** That is what
`list()` answers for its own scope — a document written and not yet flushed is on it, and nothing of the
other scope is.

## Each character's documents are their own

The client can hold several characters logged in at once and draws one of them, and every one of them has
its own rows. `s:store()` answers for the session you named — the character on screen and the character
behind it alike — so two characters read and write two sets of rows, neither sees the other's keys, and the
handle you cached from one session goes on being that character's for as long as that session lives.

Two states have no documents to answer with, and both **raise** rather than hand back an empty table that
would take your writes and never save them: a session that is not live, whose rows were written when it
ended, and one that has not reached the world yet, which has no character and therefore no key at all.
`s:exists()` tells you the first; `SessionEnteredWorld` is when the second stops being true.

**When each scope is ready.** Your addon's own documents are readable in the file body and in `Load`. A
character's are readable from that session's `SessionEnteredWorld`, because the character's key is not
known until then, so read them from there, not in `Load`. A session that picks another character refills
every table it has handed out from the new character's rows, in place, and the reference you cached is
now that character's.

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

## Where a widget sits is saved for you

[`w:remember(name)`](../ui/native.md#remembering-where-the-user-put-it-unprotected) keeps where a widget
sits and how big it is, with no document and no code of yours. That is deliberate: a placement is saved by
the *user* moving something, not by your addon deciding to write it down, so it is written the moment the
gesture lands, under a name you gave once — **a row of the client's own file**, `savedata/client.sqlite`,
keyed by your addon, and not of yours: where the user put your window is something the client keeps about
your addon, as it keeps the slots your entries are held on, and nothing in your file holds it.

**It is filed under the tree the widget stands in**, the way a document is filed under its door:

| The widget | Its row | Because |
|---|---|---|
| one of a session's own, `s:ui():match("@ChatUI")` | keyed by **that character** | where the user dragged that character's chat window is a fact about that character |
| one you built, `hafen.ui():window()` | keyed by nobody, like your addon's own documents | it stands in your layer, which belongs to no character and outlives all of them |

So a session's own window has nothing to put back until **that** session is in world, and tabbing moves
nothing: each record was already under the character it belongs to. Neither `flush()` writes a placement
and no timer does: the row is written when a drag or a resize lands, when the screen changes and when the
widget goes — destroyed, closed, or torn down with your addon — so an addon that only remembers places
saves though it names no document at all, and a place you wrote yourself with `w:position(x, y)` lands
with the widget.

## See also

- [the file](README.md) — where it is, when it is written and closed, and the state when it cannot be opened
- [tables](tables.md) — the shape for a record, where a document would grow
- [`hafen.session`](../session.md) — the address a character's documents are reached through
- [`hafen.json`](../json.md) — the same serializer, when you want the string yourself
- [events](../event/bus/lifecycle.md#sessions) — `SessionEnteredWorld`, where a character's documents are readable
