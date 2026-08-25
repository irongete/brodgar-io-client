# session:buff: buffs

Read the buffs on one character's buff bar. You reach it through the [session](session.md) whose character
you mean, and `s:buff()` **is** that character's bar.

```lua
local s = hafen.session():current()                    -- the character on screen
if s and s:buff():find("poison") then hafen.log():write("poisoned!") end

for _, buff in ipairs(s and s:buff():list() or {}) do
  hafen.log():write(buff:name() or buff:res())
end
```

| Call | Returns |
|---|---|
| `s:buff():list(filter)` | every **active** buff on that bar — a 1-based array of `Buff` objects, in bar order |
| `s:buff():count(filter)` | how many match |
| `s:buff():find(filter)` | the first active buff that matches, else `nil` |

A string [filter](conventions.md#the-filter-argument) is a plain substring match against the resource
name **and** the display name. A miss is plain `nil`.

**There is no `:get`, and that is the shape rather than an omission.** A buff has no key: two buffs can
share a resource, and the server can replace a live buff's resource under it, so a needle is a *search*
and never an address. Asking for one says so, and says what to write:

```lua
s:buff():get("poison")
-- session:buff() has no verb 'get' — a buff has no key, since two can share a resource and
-- the server can replace one under a live buff: session:buff():find(needle) is the search
-- and session:buff():list()[n] takes a position
```

## Whose buffs they are

Each of your characters carries its own. So the read says which one it is about:

```lua
hafen.session():current():buff():find("poison")    -- is the character on screen poisoned
hafen.session():get("alt"):buff():find("poison")   -- is that one, while you watch someone else
```

`s:buff()` is the same object every call, minted once for that session. A session the client no longer
holds answers an empty array rather than raising.

Buff objects are **interned per addon**, so `s:buff():find("poison") == s:buff():find("poison")`
and `seen[buff] = true` work as long as the buff is up. A `Buff` wraps only the buff widget and re-reads it
on every call, so a stashed one tracks its own meters as the server updates it — see
[snapshots vs handles](conventions.md#snapshots-vs-handles). It carries its own character with it too:
`buff:exists()` answers about the bar that buff is standing on, whichever session that is.

The array is the buffs *on the bar*, in the order they are drawn, which is arrival order rather than a
sort. A buff the server has removed is already excluded, even while it is still fading out on screen.

Just after a buff appears it is often res-only for a beat, because the display name and the meters
arrive in a second server message — so every reader may answer `nil`. That is normal, not an error.

## Read

| Method | Returns | Description |
|---|---|---|
| `buff:res()` | string \| nil | the resource name, such as `"paginae/buff/poison"` |
| `buff:name()` | string \| nil | the display name, once the resource has resolved |
| `buff:amount()` | number \| nil | the buff's own meter fraction, `0..1` |
| `buff:remaining()` | number \| nil | the radial overlay fraction, `0..1`: how much of the buff is left |
| `buff:number()` | number \| nil | the integer badge drawn on the icon |
| `buff:widget()` | [Widget](ui/widget.md) \| nil | **the widget that draws it** — the crossing from the domain back into the tree |
| `buff:exists()` | boolean | whether this buff is still on its bar — always answers |
| `buff:info()` | [`Buff`](types/character.md#buff) \| nil | a plain-table **snapshot**, the escape hatch for logging and serialising |

Nothing on this page is protected and nothing throws once you hold a `Buff`. There is no write side: the
buff bar is a display of server state, and clicking a buff icon sends a message no buff is known to act
on, so there is nothing to expose.

> `amount`, `duration` and `number` are content-defined and published by the buff's resource, so they are
> often absent. `duration` is a [`0..1` fraction](shapes.md#units) of the whole run — `0.25` means a
> quarter left — and there is no seconds-based buff timer in the client.

**A removed buff keeps answering.** Once it is off the bar `:exists()` is `false`, but `:res()`,
`:name()` and the meters still read the values it had — which is what makes a `BuffRemoved` payload, or
a buff you stashed, worth holding on to. `:exists()` is exactly the predicate `:list()` filters on.

Subscribe to [`BuffAdded`, `BuffRemoved` and
`BuffChanged`](event/bus/character.md#character-and-status); each payload is the `Buff` object
itself. The buffs a character already has arrive as a burst of `BuffAdded` shortly after it enters the
world.

```lua
hafen.event():on("BuffRemoved", function(buff)
  hafen.log():write((buff:name() or buff:res()) .. " wore off")   -- readable, :exists() is false
end)
```

## See also

- [`Buff`](types/character.md#buff) — the snapshot shape `:info()` returns
- [`session:meter`](meter.md) — the HUD bars, read the same way
- [snapshots vs handles](conventions.md#snapshots-vs-handles) — why a stashed `Buff` stays current
- [events](event/bus/character.md#character-and-status) — the three buff events
