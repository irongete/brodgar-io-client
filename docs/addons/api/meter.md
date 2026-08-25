# session:meter: the HUD meter bars

Read the bars in one character's HUD meter slot — health, stamina, energy, and whatever else the server
puts there. You reach it through the [session](session.md) whose character you mean, and `s:meter()`
**is** that character's meter slot.

```lua
local s = hafen.session():current()                    -- the character on screen
local hp = s and s:meter():find("hp")
local fill = hp and hp:segment():list()[1]
if fill and ((fill:value() or 1) < 0.3) then hafen.log():write("low health!") end
```

| Call | Returns |
|---|---|
| `s:meter():list(filter)` | every bar in that HUD — a 1-based array of `Meter` objects, in layout order |
| `s:meter():count(filter)` | how many match |
| `s:meter():find(filter)` | the first meter that matches, else `nil` |

A string [filter](conventions.md#the-filter-argument) is a plain substring match against the resource
name, and is not trimmed. A miss is plain `nil`.

**There is no `:get`**: a meter has no key, only a server-published resource name several bars could
share, so a needle is a *search*. Asking for one says so, and says what to write:

```lua
s:meter():get("hp")
-- session:meter() has no verb 'get' — a meter has no key, only a server-published background
-- resource name several bars can share: session:meter():find(needle) is the search and
-- session:meter():list()[n] takes a position
```

## Whose bars they are

Two characters have two meter slots, and a health bar read off the wrong one is the wrong body's. So the
read says which character it is about:

```lua
hafen.session():current():meter():find("hp")     -- the health of the character on screen
hafen.session():get("alt"):meter():find("hp")    -- that character's, while you watch someone else
```

`s:meter()` is the same object every call, minted once for that session, so a draw callback reading it at
60 fps allocates nothing. A session the client no longer holds answers an empty array rather than raising.

Meter objects are **interned per addon**, so `s:meter():find("hp") == s:meter():list()[1]` and
`seen[m] = true` work. A `Meter` wraps only the meter widget and re-reads it on every call, so a
stashed one tracks its bar as the server updates it — see
[snapshots vs handles](conventions.md#snapshots-vs-handles). It also carries its own character with it:
`meter:exists()` and `meter:index()` answer about the slot that bar is standing in, whichever session
that is.

## There is no fixed hp, stamina and energy triple

The meter slot takes an arbitrary number of bars, and a bar is identified by the **resource its
background is drawn from** — which the *server* publishes, not the client. `"hp"` is therefore not a
key this API knows: it is a substring that happens to identify a bar on this server. `:res()` is how
you read the real names off a live client:

```lua
:lua for _, m in ipairs(hafen.session():current():meter():list()) do hafen.log():write(tostring(m:res())) end
```

What this server publishes:

| `:res()` | What it is | When present |
|---|---|---|
| `gfx/hud/meter/hp` | health | always |
| `gfx/hud/meter/stam` | stamina | always |
| `gfx/hud/meter/nrj` | energy | always |
| `gfx/hud/meter/häst` | the horse's own bar | while mounted |
| `gfx/hud/meter/mount` | the mount bar | while mounted |

Treat that table as observed rather than as a contract — re-derive it with `:res()` on the server you
are on. One of the names is **non-ASCII**, so prefer an ASCII needle such as `"st"`, `"mount"` or
`"hp"`, or iterate `s:meter():list()` and compare `:res()` yourself, rather than typing an accented
literal into your Lua source.

## Read

| Method | Returns | Description |
|---|---|---|
| `meter:res()` | string \| nil | the background resource name — the identity |
| `meter:index()` | number \| nil | its 1-based position in its own HUD; `nil` once the meter is gone |
| `meter:segment()` | collection | the bar, as [Segment](#a-segment) objects in draw order — never `nil`, may be empty |
| `meter:widget()` | [Widget](ui/widget.md) \| nil | **the widget that draws it** — the crossing from the domain back into the tree |
| `meter:exists()` | boolean | whether this meter is still in its HUD slot — always answers |
| `meter:info()` | [`Meter`](types/ui.md#meter) \| nil | a plain-table **snapshot**, the escape hatch for logging and serialising |

Every read is guarded and may answer `nil`: a brand-new meter is nameless for a beat and its segments
stream in after it appears. Only `:exists()` always answers. Nothing throws once you hold a `Meter`.

A bar is genuinely multi-segment in the engine, and the first segment is what a whole-bar reading means —
`meter:segment():list()[1]`. The vital bars use one segment each, so the two shorthands are all you normally need, but a
bar with more shows them all in `:segment():list()`.

## A segment

| Method | Returns | Description |
|---|---|---|
| `seg:index()` | number | its 1-based place in the bar |
| `seg:value()` | number \| nil | its fill fraction, `0..1` |
| `seg:color()` | [colour](shapes.md#colours) \| nil | its colour, `{r=, g=, b=, a=}` |
| `seg:info()` | table \| nil | a plain-table **snapshot** |

The bar's fill is `meter:segment():list()[1]:value()`, and it says which segment it is. There was a
`meter:value()` that read segment one under a whole-bar name: right on every meter the client ships,
and silently wrong the first time a server publishes a split bar.

> `seg:value()` is a **bar fraction only**. There are no absolute hp, stamina or energy numbers, and no
> hunger figure, in the client. The one place absolute numbers exist is FEP:
> see [`s:char():food()`](char.md#food).

There is no write side. Meters are server-pushed presentation and there is nothing to set. You can
freeze the bars client-side by swallowing their updates through an
[`IMeter` message filter](event/streams.md#filtering-an-inbound-update) — purely cosmetic, since the server
still knows your real values.

## Events

| Event | Payload | Fires |
|---|---|---|
| [`MeterAdded`](event/bus/character.md#character-and-status) | `Meter` | a bar appears in the HUD slot |
| [`MeterRemoved`](event/bus/character.md#character-and-status) | `Meter` | a bar goes away; the object still reads, and `:exists()` is false |
| [`MeterChanged`](event/bus/character.md#character-and-status) | `Meter` | a bar's value **or** colour changes |

The meters stream in a beat after `SessionEnteredWorld`, so `:list()` is legitimately empty for a moment
and the bars arrive as a burst of `MeterAdded`. Mounting a horse adds two more mid-session and
dismounting removes them, which is what the lifecycle pair is for.

`MeterChanged` fires only on a real change: the whole segment array is compared, so both a value push
and a pure recolour count, and standing still is silent.

```lua
hafen.event():on("MeterChanged", function(m)
  if m:res() == "gfx/hud/meter/hp" then
    local seg = m:segment():list()[1]
    hafen.log():write(("hp %.0f%%"):format(((seg and seg:value()) or 0) * 100))
  end
end)
```

> **A `MeterAdded` payload can be younger than its resource.** The bar that appears when you mount
> fires while its background is still loading, so inside the handler `:res()` is `nil` and
> `tostring(m)` reads `Meter(?)`. The same object answers a beat later, because a handle re-reads the
> widget on every call. Name-match a meter on a later tick, or in `MeterChanged` — never inside
> `MeterAdded`.

**A removed meter keeps answering.** Once it is out of the slot `:exists()` is `false` and `:index()`
is `nil`, but `:res()` and `:segment()` still read the values it had. That is
what makes a `MeterRemoved` payload, or a meter you stashed, worth holding on to; `:exists()` is
exactly the predicate `:list()` filters on.

## See also

- [`Meter`](types/ui.md#meter) — the snapshot shape `:info()` returns
- [`session:char`](char.md) — `:food()`, the one absolute reading about your character
- [`session:buff`](buff.md) — the other keyless status collection
- [events](event/bus/character.md#character-and-status) — the three meter events
