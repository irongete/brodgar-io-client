# hafen.ui: the pixel

Every coordinate and every size a widget takes or gives is a **design pixel** — the unit the client's own
art is drawn in, and the one this API measures in. Reach for this page once, to learn what the numbers in
`:position(x, y)` and `:size(w, h)` mean; nothing here needs doing.

```lua
local w = hafen.ui():widget():size(100, 40):position(30, 20)
w:size()                      -- {x = 100, y = 40}, on every client
hafen.ui():scale()            -- 1.5 on a client the user scaled up; you never multiply by it
```

## What a design pixel is

The user sets an **Interface scale** in the client's own Options. Above `1.0` the client draws everything
larger: its windows, its buttons, its art and its text. A design pixel is one pixel *before* that
enlargement, so the numbers your addon writes are the numbers the client's own layout is written in, and a
window you place at `30, 20` sits where the client would have placed its own.

**What you write is what you read back.** `w:size(100, 40)` reads back `{x = 100, y = 40}` at any scale, and
so does `:position`, `:rootPos`, `:info().pos` and `:info().size`. The conversion happens once, at the edge,
and it is exact in that direction — which is what lets an addon compute with the pair it just read.

> The inverse is not exact. A widget the *client* placed does not generally sit on a whole design pixel, so
> reading a native widget's position and writing that same pair straight back may shift it by less than one
> design pixel. Read it, keep it, and hand it back with [`:position(nil)`](native.md) rather than by writing
> the numbers again.

**One space, for every question about the screen.** The box a widget occupies, the point
[`hafen.ui():at(x, y)`](selectors.md#hit-testing) tests, where [the pointer](mouse.md) is, and the `:x()` /
`:y()` an [input event](widget.md#subscribing) carries are all the same unit — so the rectangle you laid out
is the rectangle you hit-test, with nothing to convert between them:

```lua
local m = hafen.ui():mouse()
hafen.ui():at(m:x(), m:y()) == m:over()          -- true: one pair, asked two ways
```

**A font's `size` is a design pixel too**, and always was — see [`hafen.font`](../font.md). A type size is
not a coordinate, but it lives in the same space as one, so a `14` px caption fits a `20` px row on every
client.

## Read

Reading the scale is unprotected client-side data.

| Verb | Returns |
|---|---|
| `hafen.ui():scale()` | the interface scale **in force**, `1.0` or more |

**It is not a unit, and nothing multiplies by it.** It is here to be printed — in a log line, or a
diagnostic that says what the client is running at. An addon that reaches for it to convert a coordinate has
found a place where this page is wrong; the numbers are already right.

`hafen.ui():scale(v)` raises, naming the setting that *does* write it:
[`hafen.client():options():interface():scale(v)`](../client/README.md#interface). That one is the user's
preference and takes a client restart, so the two are different numbers until the client is next launched —
this verb is what the client is drawing at right now.

## See also

- [widget](widget.md#read) — `:position()`, `:size()` and `:rootPos()`, the verbs that speak this unit
- [native](native.md) — writing the same two on a widget the client built, and taking the write back
- [mouse](mouse.md) — the pointer, in these coordinates
- [custom](custom.md) — a surface you paint, and the size its `Draw` reports
- [`hafen.font`](../font.md) — a type size, in the same space
