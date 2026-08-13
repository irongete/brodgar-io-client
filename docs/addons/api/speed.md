# hafen.speed: movement speed

`hafen.speed()` **is** the collection of movement speeds, and it holds exactly the ones you can pick right
now. Reach for it to read the speed your character is on, and to change it — the write is protected, and its
key is in [permissions](../guides/permissions.md).

```lua
-- the speed you are on, then the ones you can pick
local speed = hafen.speed()
local cur = speed:current()
if cur then
  hafen.log():write("on " .. cur:name() .. " (" .. cur:index() .. ")")
end
for _, sp in ipairs(speed:list()) do
  hafen.log():write(sp:index() .. " " .. sp:name())
end
```

> **The collection enumerates what you can pick; `:get` addresses a speed by its key.** `:list()` is
> *exactly* the selectable speeds, so everything it hands you is something `:set` accepts. `:get(key)` reaches
> all four, selectable or not, which is how "is sprint unlocked yet?" has something to ask about:
> `hafen.speed():get(3):available()`.

## Read

| Method | Returns | Description |
|---|---|---|
| `hafen.speed():list(filter)` | array | the selectable speeds, a 1-based array of `Speed` objects, crawl first |
| `hafen.speed():count(filter)` | number | how many match |
| `hafen.speed():find(filter)` | Speed \| nil | the first selectable speed that matches, else `nil` |
| `hafen.speed():get(key)` | Speed \| nil | any of the four by key, selectable or not, else `nil` |
| `hafen.speed():current()` | Speed \| nil | the speed your character is on |

A **key** is the index `0..3` — `0` crawl, `1` walk, `2` run, `3` sprint — or the whole display name,
case-insensitively, so `:get(2)` and `:get("run")` address the same speed. A key of the right shape that
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
| `sp:index()` | number | the wire number, `0..3` — its identity, and always answers |
| `sp:name()` | string | the display name, such as `"Run"`; it is known before the selector is |
| `sp:available()` | boolean | whether it can be picked right now, which is what `:list()` filters on |
| `sp:exists()` | boolean | whether the speed selector is up at all |
| `sp:info()` | [`Speed`](types.md#speed) | a plain-table **snapshot**, the escape hatch for logging |

Speed objects are **interned per addon**, so `hafen.speed():get(2) == hafen.speed():get(2)` and one works as
a table key. That makes `==` the whole of "am I on this one":

```lua
if hafen.speed():current() == hafen.speed():get("Sprint") then
  hafen.log():write("sprinting")
end
```

## Write (protected)

| Method | Key | Description |
|---|---|---|
| `hafen.speed():set(speed)` | `speed.set` | pick a speed; returns the collection, so writes chain |

`speed` is a `Speed` object, an index `0..3` or a display name — whatever `:get` takes, plus the object
itself. The call drives the client's own selector and sends exactly what clicking that icon sends, so the
server has the last word on whether the speed is allowed.

**The read-back is a round trip.** The selected speed changes when the server says so, so `:current()` still
answers the speed you were on for a beat after the call. Poll it, rather than reading it on the next line.

It raises for each of these, before anything is sent:

- an addon that did not declare `speed.set` in its [manifest](../guides/permissions.md), naming the key
- a speed that is not selectable, listing the ones that are
- an index outside `0..3`, or a name no speed has
- a `nil`, or a value that is neither a `Speed`, a number nor a string
- no speed selector, which is any moment before it has streamed in

```lua
-- your manifest declares "speed.set", and the user granted it when they enabled you
hafen.slash():register("run", function()
  local speed = hafen.speed()
  local pick = speed:get("Run")
  if pick and pick:available() then
    speed:set(pick)
  else
    hafen.log():write("Run is locked; " .. speed:count() .. " speeds are selectable")
  end
end)
```

## See also

- [permissions](../guides/permissions.md) — the key this write needs, and how the user grants it
- [`Speed`](types.md#speed) — the snapshot shape `:info()` returns
- [conventions](conventions.md#collections-the-noun-is-the-kind-the-verb-is-how-many) — the collection verbs
- [Gob](gob.md) — `gob:speed()`, the speed a body is actually moving at
- [keybindings](client/keybindings.md) — the client's own speed hotkeys, which you can remap
