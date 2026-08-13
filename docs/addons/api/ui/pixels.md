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

**And for every question about drawing.** Every coordinate, width and radius a [`g:` verb](drawing.md)
takes, the `:w()`/`:h()` a `Draw` callback reports, the `w, h` a [HUD overlay's](custom.md#overlays)
painter is handed, and the `sx, sy` at a [gob overlay](../overlay.md) are the same unit — so the box you
sized is the box you paint into:

```lua
win:on("Draw", function(ev)
  ev:g():rect(0, 0, ev:w(), ev:h())              -- on the edge of the box :size(w, h) gave it
end)
```

**A [stylesheet](style/README.md) says the same space.** A rule's `position` and `size`, an
[`anchor`](style/geometry.md#anchor)'s `offset`, a [`padding`](style/chrome.md#padding) and a `border`'s four `slice`
insets are all design pixels, and so are a [list's](lists.md#list) `:rowHeight(n)` and a
[grid's](lists.md#grid) `:cell(w, h)`. Each reads back the number the rule wrote, so a theme is a set of numbers
that means one thing on every client:

```lua
hafen.ui():sheet():rule("window[title=Equipment]"):position(40, 200):install()
hafen.ui():find("window[title=Equipment]"):position()   -- {x = 40, y = 200}, at any scale
```

**A control's own height is not a number you write at all.** It is a fact of the client's pictures — a
button is as tall as its art — so [`:size(w)`](controls/README.md#sizing) takes the width alone and leaves
the height to it, and [`:pack()`](widget.md#owned-vs-borrowed) sizes the box around a column of them.

**An image you ship is measured in it too.** A 32×32 PNG is 32×32 to
[`img:size()`](../asset.md#image) and covers 32 design pixels when drawn, so your art and the client's
sit at the same size at every scale.

**The client's own art carries its own scale**, and a rule that [names one](style/chrome.md#naming-a-picture)
still speaks this unit: the HUD is drawn from art authored several times larger, resampled once per interface
scale, so a `{res = …}` in a `bg` or a `border` is measured — and sliced — in the same design pixels your own
file is, while staying crisper than a file of that apparent size on a scaled-up client.

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
- [drawing](drawing.md) — the `g:` verbs, which take these numbers
- [style](style/README.md) — a rule's own coordinates, sizes and insets
- [`hafen.font`](../font.md) — a type size, in the same space
