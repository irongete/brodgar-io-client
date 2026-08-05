# hafen.store: saved variables

Keep data across sessions. A **saved variable** is a Lua table you declare in your manifest; the engine
restores it on load and writes it back to disk for you. `hafen.store` is **ungated** — it writes only
inside your addon's own save folder.

```lua
local settings = hafen.store():get("settings")   -- the live persisted table, not a copy

settings.enabled = true
settings.count = (settings.count or 0) + 1
```

## Declare a variable

Every saved variable is named in `manifest.json`, and nothing else is persisted:

```json
"saved_variables": ["settings", { "name": "account", "scope": "account" }]
```

| Declaration | Scope | Where it lands |
|---|---|---|
| a bare name | per character | `savedata/<genus>_<char>/<addon>.json` |
| `{ "name": …, "scope": "account" }` | account-wide, shared by all your characters | `savedata/account/<addon>.json` |

Any scope other than `"account"` is per-character. A name declared twice keeps the first declaration.

## Read and write

| Method | Description |
|---|---|
| `hafen.store():get(name)` | the persisted table for one declared variable; read and write it like any table |
| `hafen.store():flush()` | write the changed tables to disk now |

**What `get` hands back is the table itself, not a copy**, so writing into it is the whole of saving:
there is no "put it back" step, and a reference you keep in a local goes on being the one written to
disk. It is also stable for the addon's whole life — a restore refills it in place rather than replacing
it — so a table captured at load time is still valid an hour later. Assign *into* it; you cannot assign
over it.

A declared name is **always a usable table**, empty when there is nothing saved yet, so you never have to
create it. A name your manifest does not declare is an error naming the ones it does, because the set of
saved variables is fixed when your addon loads and a misspelt one has no later meaning to wait for.

**When each scope is ready.** Account tables are filled before your files run, so they are readable in
the file body and in `OnLoad`. Per-character tables are filled just before `OnEnterWorld` fires,
because the character's folder is not known until then: read them there, not in `OnLoad`.

**What survives.** The tables are stored as JSON, so tables, strings, numbers and booleans round-trip
and nothing else does — with one exception, and it is the one worth having: a
[Position](world.md#the-position-type) is written as its durable form and comes back **a Position**, so
a place you save is a place you get. A position on ground you have never visited has no durable form and
is not written at all; `p:durable()` is how you know. Anything else live — a function, a widget handle —
is written as a placeholder string and comes back as that string. Keys become strings unless the table is
a `1..n` array, and a `nil` value is simply an absent key. Store plain data and a rebuild on load.

## When it is written

Changes are saved on a timer, roughly every 30 seconds, and again when your addon is disabled or
reloaded and when the session ends. A file whose content has not changed is not rewritten, and writes
are atomic, so an interrupted write cannot leave a half-file behind. `flush()` forces the write
immediately — worth calling after a change the user would be upset to lose, and unnecessary otherwise.

A file the engine cannot read or parse leaves your tables as they are, and the failure is logged rather
than raised: your addon starts with empty settings instead of not starting.

## See also

- [`hafen.json`](json.md) — the same serializer, when you want the string yourself
- [events](event.md#lifecycle) — `OnEnterWorld`, where per-character data becomes readable
- [`hafen.http`](http.md) — fetching what you cache here
