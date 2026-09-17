# session:speed: Movement Speed

The speed one character walks at, read and set through its [session](session.md). `session:speed()` is that character's collection of movement speeds, the ones it can pick right now included. The write is protected under `speed.set`.

```lua
local session = hafen.session():current()                      -- the character on screen
local speeds = session:speed()
local current_speed = speeds:current()
if current_speed then
  hafen.log():write("on " .. current_speed:name() .. " (" .. current_speed:index() .. ")")
end
for _, speed in ipairs(speeds:list()) do
  hafen.log():write(speed:index() .. " " .. speed:name())
end
```

---

| Rule | Detail |
|---|---|
| Whose speed | Every character has its own selector and its own unlocks: sprint earned on one says nothing about another. `hafen.session():get("alt"):speed():get(4):available()` answers for that character, drawn or not. |
| One object | `session:speed()` is the same object every call, minted once per session. A session the client no longer holds has an empty list and a `nil` `:current()`. |
| `:list()` is every speed. `:available()` is the pickable ones | Everything `session:speed():available():list()` hands you is something `:set` accepts. `:list()` and `:get(key)` reach every speed, selectable or not, which is how "is sprint unlocked yet" has something to ask: `session:speed():get(4):available()`. |
| No event | Read on demand: a command or a hotkey that reads, decides and sets in one go. |

## Read

| Method | Returns | Permission | Description |
|---|---|---|---|
| `session:speed():list(filter)` | `Speed[]` | Unprotected | Every speed, crawl first. `speed:available()` says which can be picked. |
| `session:speed():available(filter)` | collection | Unprotected | The ones that can be picked right now. |
| `session:speed():count(filter)` | `number` | Unprotected | How many match. |
| `session:speed():find(filter)` | `Speed \| nil` | Unprotected | The first speed that matches. |
| `session:speed():get(key)` | `Speed \| nil` | Unprotected | Any of the four by key, selectable or not. |
| `session:speed():current()` | `Speed \| nil` | Unprotected | The speed that character is on. |

| Rule | Detail |
|---|---|
| A key | The 1-based position `speed:index()` answers (`1` crawl, `2` walk, `3` run, `4` sprint), or the whole display name, case-insensitively. `:get(3)` and `:get("run")` address the same speed. A key of the right shape naming no speed is `nil`. A key of another type raises, and an explicit `nil` raises as [everywhere else](conventions.md#nil-is-an-error-unless-it-means-something). |
| `filter` | A string [filter](conventions.md#the-filter-argument) is a substring match against the display name. |
| Never throws | Nothing here is protected. |
| `:list()` is empty in one situation | Before the client's speed selector streams in, shortly after entering the world: nothing at all, and `:get` answers `nil` too. The server locking every speed is different: `:list()` answers four, `:available()` is empty, `:set` refuses everything naming what is pickable. |

## The Speed object

One of the four speeds, live: it re-reads the selector on every call, so a stashed `Speed` tracks the server locking and unlocking it.

| Method | Returns | Permission | Description |
|---|---|---|---|
| `speed:index()` | `number` | Unprotected | Its 1-based position, `1..4`, the number `:get` and `:set` take. |
| `speed:wire()` | `number` | Unprotected | The raw number the selector's own message carries, `0..3`. |
| `speed:name()` | `string` | Unprotected | The display name, such as `"Run"`. Known before the selector is. |
| `speed:available()` | `boolean` | Unprotected | Whether it can be picked right now, what `session:speed():available()` partitions on. |
| `speed:exists()` | `boolean` | Unprotected | Whether that character's speed selector is up at all. |
| `speed:info()` | [`Speed`](types/character.md#speed) | Unprotected | A plain-table snapshot, for logging. |

Interned per addon on character and index: `session:speed():get(2) == session:speed():get(2)` and one works as a table key. The same number through two sessions is two objects. `==` is the whole of "am I on this one":

```lua
local session = hafen.session():current()
if session:speed():current() == session:speed():get("Sprint") then
  hafen.log():write("sprinting")
end
```

## Write (protected)

The client sends only shapes a player could compose.

| Method | Returns | Permission | Description |
|---|---|---|---|
| `session:speed():set(speed)` | the collection | `speed.set` | Pick a speed: a `Speed` object, the `1..4` position or a display name. |

| Rule | Detail |
|---|---|
| A `Speed` names one character's selector | One read off another session is refused rather than taken for the number on it. The position and the name name no character and work anywhere. |
| Drives the client's own selector | Sends exactly what clicking that icon sends. The server has the last word on whether the speed is allowed. |
| The read-back is a round trip | The selected speed changes when the server says so: `:current()` answers the old speed until the server answers. Poll it. |
| One key, every character | `speed.set` changes the speed of any of your logins, drawn or not ([a key names the action, not the target](../guides/permissions.md#a-key-names-the-action-not-the-target)). |
| Raises before anything is sent | An addon that did not declare `speed.set` in its [manifest](../guides/permissions.md), naming the key. A speed that is not selectable, listing the ones that are. A position that is not a [whole number](conventions.md#a-number-is-finite-and-an-index-is-whole) `1..4`, or a name no speed has. A `nil`, or a value that is neither a `Speed`, a number nor a string. No speed selector on that character yet. |

```lua
-- your manifest declares "speed.set", and the user granted it when they enabled you
hafen.console():on("run", function()
  local speeds = hafen.session():current():speed()
  local run_speed = speeds:get("Run")
  if run_speed and run_speed:available() then
    speeds:set(run_speed)
  else
    hafen.log():write("Run is locked; " .. speeds:count() .. " speeds are selectable")
  end
end)
```

---

## See Also

- [Session](session.md) — the address every read here goes through.
- [Permissions](../guides/permissions.md) — the key this write needs, and how the user grants it.
- [`Speed`](types/character.md#speed) — the snapshot shape `:info()` returns.
- [Conventions](conventions.md#collections-the-noun-is-the-kind-the-verb-is-how-many) — the collection verbs.
- [Gob](gob.md) — `gob:speed()`, the speed a body is moving at.
- [Keybindings](client/keybindings.md) — the client's own speed hotkeys, which you can remap.
