# session:speed: movement speed

Read the speed one character is walking at, and change it. You reach it through the [session](session.md)
whose character you mean, and `s:speed()` **is** that character's collection of movement speeds — exactly
the ones it can pick right now. The write is protected, and its key is in
[permissions](../guides/permissions.md).

```lua
local s = hafen.session():current()                      -- the character on screen
local speed = s:speed()
local cur = speed:current()
if cur then
  hafen.log():write("on " .. cur:name() .. " (" .. cur:index() .. ")")
end
for _, sp in ipairs(speed:list()) do
  hafen.log():write(sp:index() .. " " .. sp:name())
end
```

## Whose speed it is

Every character has its own speed selector, and its own idea of what is unlocked: sprint earned on one says
nothing about the other. So the read says which character it is about, and it answers for one you are not
looking at exactly as it answers for the drawn one:

```lua
hafen.session():current():speed():current()      -- the speed of the character on screen
hafen.session():get("alt"):speed():get(3):available()   -- has that character unlocked sprint?
```

`s:speed()` is the same object every call, minted once for that session. A session the client no longer holds
has an empty list and a `nil` `:current()` rather than raising.

> **The collection enumerates what you can pick; `:get` addresses a speed by its key.** `:list()` is
> *exactly* the selectable speeds, so everything it hands you is something `:set` accepts. `:get(key)` reaches
> all four, selectable or not, which is how "is sprint unlocked yet?" has something to ask about:
> `s:speed():get(3):available()`.

## Read

| Method | Returns | Description |
|---|---|---|
| `s:speed():list(filter)` | array | the selectable speeds, a 1-based array of `Speed` objects, crawl first |
| `s:speed():count(filter)` | number | how many match |
| `s:speed():find(filter)` | Speed \| nil | the first selectable speed that matches, else `nil` |
| `s:speed():get(key)` | Speed \| nil | any of the four by key, selectable or not, else `nil` |
| `s:speed():current()` | Speed \| nil | the speed that character is on |

A **key** is the **1-based** position `sp:index()` answers — `1` crawl, `2` walk, `3` run, `4` sprint
— or the whole display name, case-insensitively, so `:get(3)` and `:get("run")` address the same
speed. A key of the right shape that
names no speed is a plain `nil`; a key of another type raises, because there is no reading of it that could
ever hit, and an explicit `nil` raises as it does
[everywhere else](conventions.md#nil-is-an-error-unless-it-means-something). A string
[filter](conventions.md#the-filter-argument) is a substring match against the display name.

Nothing here is protected, and none of these reads throws. **The list is empty in two different
situations**, and both are states rather than errors. Before the client's speed selector streams in, a beat
after entering the world, there is nothing to pick from and `:get` answers `nil` as well. And the server can
lock every speed at once, which leaves a live selector on which `:list()` is empty and `:set` refuses
everything — `:get` still answers a `Speed` there, because a locked speed is a speed.

There is no speed event. Read on demand: the classic use is a command or a hotkey that reads, decides and
sets in one go.

## The Speed object

One of the four speeds, live: it re-reads the selector on every call, so a `Speed` you stash tracks the
server locking and unlocking it.

| Method | Returns | Description |
|---|---|---|
| `sp:index()` | number | its **1-based** position, `1..4`, the number `:get` and `:set` take |
| `sp:wire()` | number | the raw number the selector's own message carries, `0..3` |
| `sp:name()` | string | the display name, such as `"Run"`; it is known before the selector is |
| `sp:available()` | boolean | whether it can be picked right now, which is what `:list()` filters on |
| `sp:exists()` | boolean | whether that character's speed selector is up at all |
| `sp:info()` | [`Speed`](types.md#speed) | a plain-table **snapshot**, the escape hatch for logging |

Speed objects are **interned per addon** on the character *and* the index, so
`s:speed():get(2) == s:speed():get(2)` and one works as a table key — while the same number reached through
two sessions gives you two objects, because it names two characters' speeds. That makes `==` the whole of
"am I on this one":

```lua
local s = hafen.session():current()
if s:speed():current() == s:speed():get("Sprint") then
  hafen.log():write("sprinting")
end
```

## Write (protected)

| Method | Key | Description |
|---|---|---|
| `s:speed():set(speed)` | `speed.set` | pick a speed; returns the collection, so writes chain |

`speed` is a `Speed` object, the `1..4` position or a display name — whatever `:get` takes, plus the object
itself. The call drives the client's own selector and sends exactly what clicking that icon sends, so the
server has the last word on whether the speed is allowed.

**The read-back is a round trip.** The selected speed changes when the server says so, so `:current()` still
answers the speed that character was on for a beat after the call. Poll it, rather than reading it on the
next line.

One key covers every character: `speed.set` lets you change the speed of any of your logins, drawn or
not — see
[a key names the action, not the target](../guides/permissions.md#a-key-names-the-action-not-the-target).

It raises for each of these, before anything is sent:

- an addon that did not declare `speed.set` in its [manifest](../guides/permissions.md), naming the key
- a speed that is not selectable, listing the ones that are
- a position outside `1..4`, or a name no speed has
- a `nil`, or a value that is neither a `Speed`, a number nor a string
- no speed selector on that character, which is any moment before it has streamed in

```lua
-- your manifest declares "speed.set", and the user granted it when they enabled you
hafen.slash():on("run", function()
  local speed = hafen.session():current():speed()
  local pick = speed:get("Run")
  if pick and pick:available() then
    speed:set(pick)
  else
    hafen.log():write("Run is locked; " .. speed:count() .. " speeds are selectable")
  end
end)
```

## See also

- [session](session.md) — the address every read here goes through
- [permissions](../guides/permissions.md) — the key this write needs, and how the user grants it
- [`Speed`](types.md#speed) — the snapshot shape `:info()` returns
- [conventions](conventions.md#collections-the-noun-is-the-kind-the-verb-is-how-many) — the collection verbs
- [Gob](gob.md) — `gob:speed()`, the speed a body is actually moving at
- [keybindings](client/keybindings.md) — the client's own speed hotkeys, which you can remap
