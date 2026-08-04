# hafen.buff: buffs

Read the buffs on the player's buff bar. `hafen.buff` is a function, and the arity is the verb.

```lua
if hafen.buff("poison") then hafen.log():write("poisoned!") end

for _, buff in ipairs(hafen.buff()) do
  hafen.log():write(buff:name() or buff:res())
end
```

| Call | Returns |
|---|---|
| `hafen.buff()` | every **active** buff — a 1-based array of `Buff` objects, in bar order |
| `hafen.buff(needle)` | the first active buff whose res or name contains `needle`, else `nil` |

The lookup is a plain substring match against both the resource name and the display name. A miss is
plain `nil`. A **number** raises an error — positions are not addresses, so use `hafen.buff()[n]` — and
so does the empty string.

Buff objects are **interned per addon**, so `hafen.buff("poison") == hafen.buff("poison")` and
`seen[buff] = true` work as long as the buff is up. A `Buff` wraps only the buff widget and re-reads it
on every call, so a stashed one tracks its own meters as the server updates it — see
[snapshots vs handles](conventions.md#snapshots-vs-handles).

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
| `buff:duration()` | number \| nil | the radial overlay fraction, `0..1`: how much of the buff is left |
| `buff:number()` | number \| nil | the integer badge drawn on the icon |
| `buff:exists()` | boolean | whether this buff is still on the bar — always answers |
| `buff:info()` | [`Buff`](types.md#buff) \| nil | a plain-table **snapshot**, the escape hatch for logging and serialising |

Nothing on this page is gated and nothing throws once you hold a `Buff`. There is no write side: the
buff bar is a display of server state, and clicking a buff icon sends a message no buff is known to act
on, so there is nothing to expose.

> `amount`, `duration` and `number` are content-defined fractions and integers published by the buff's
> resource. They are often absent, and they are **not** seconds: `duration` is a fraction of the whole,
> so `0.25` means a quarter left. There is no seconds-based buff timer in the client.

**A removed buff keeps answering.** Once it is off the bar `:exists()` is `false`, but `:res()`,
`:name()` and the meters still read the values it had — which is what makes a `BuffRemoved` payload, or
a buff you stashed, worth holding on to. `:exists()` is exactly the predicate `hafen.buff()` filters on.

Subscribe to [`BuffAdded`, `BuffRemoved` and
`BuffChanged`](event.md#character-and-status); each payload is the `Buff` object
itself. The buffs the character already has arrive as a burst of `BuffAdded` shortly after entering the
world.

```lua
hafen.event():on("BuffRemoved", function(buff)
  hafen.log():write((buff:name() or buff:res()) .. " wore off")   -- still readable, and :exists() is false
end)
```

## See also

- [`Buff`](types.md#buff) — the snapshot shape `:info()` returns
- [`hafen.meter`](meter.md) — the HUD bars, read the same way
- [snapshots vs handles](conventions.md#snapshots-vs-handles) — why a stashed `Buff` stays current
- [events](event.md#character-and-status) — the three buff events
