# hafen.buff — buffs

Read the buffs on the player's buff bar. `hafen.buff` is a function, and the arity is the verb:

| Call | Returns |
|---|---|
| `hafen.buff()` | every **active** buff — a 1-based array of `Buff` objects, in bar order |
| `hafen.buff(needle)` | the **first** active buff whose res *or* name contains `needle`, else `nil` |

```lua
if hafen.buff("poison") then hafen.log("poisoned!") end

for _, buff in ipairs(hafen.buff()) do
  hafen.log(buff:name() or buff:res())
end
```

The lookup is a plain substring match against both the resource name and the display name, so
`hafen.buff("poison")` reads as the old `has()` did — except it now hands back the buff itself, which is
what you wanted next anyway. A miss is plain `nil`; a **number** raises an error (positions are not
addresses — use `hafen.buff()[n]`), and so does the empty string.

Buff objects are **interned per addon**, so `hafen.buff("poison") == hafen.buff("poison")` and
`seen[buff] = true` work as long as the buff is up. A `Buff` wraps **only the buff widget** and re-reads it
on every call, so a stashed one tracks its own meters as the server updates it — see
[snapshots vs handles](conventions.md#snapshots-vs-handles).

The array is the buffs *on the bar*, in the order they are drawn (which is arrival order, not a sort). A buff
the server has just removed is already excluded, even though it is still fading out on screen.

Right after a buff appears it is often **res-only** for a beat — the display name and the meters arrive in a
second server message — so every reader may answer `nil`. That is normal, never an error.

## Read

| Method | Returns | Description |
|---|---|---|
| `buff:res()` | string \| nil | the resource name, e.g. `"paginae/buff/poison"` |
| `buff:name()` | string \| nil | the display name, once the resource has resolved |
| `buff:amount()` | number \| nil | the buff's own meter fraction, `0..1` |
| `buff:duration()` | number \| nil | the radial overlay fraction, `0..1` — how much of the buff is left |
| `buff:number()` | number \| nil | the integer badge drawn on the icon |
| `buff:exists()` | boolean | is this buff still on the bar — always answers |
| `buff:info()` | [`Buff`](types.md#buff) \| nil | a plain-table **snapshot** — the escape hatch for logging/serialising |

> `amount`/`duration`/`number` are content-defined 0..1 fractions / integers published by the buff's
> resource — they are often absent and are **not** seconds. `duration` is a *fraction of the whole*, so
> `0.25` means a quarter left, not 0.25 s: there is no seconds-based buff timer in the client.

Subscribe to [`BuffAdded`, `BuffRemoved`, `BuffChanged`](events.md#character--status-widget-tree-backed) —
each payload is the `Buff` object itself — for updates. The buffs the character already has arrive as a
burst of `BuffAdded` shortly after entering the world.

```lua
hafen.events.on("BuffRemoved", function(buff)
  hafen.log((buff:name() or buff:res()) .. " wore off")   -- still readable, and :exists() is false
end)
```

**A removed buff keeps answering.** Once it is off the bar, `:exists()` is `false` but `:res()`, `:name()`
and the meters still read the values it had — which is what makes a `BuffRemoved` payload (or a buff you
stashed) worth holding on to. `:exists()` is exactly the predicate `hafen.buff()` filters on.

> **There is no `buff:click()`.** Clicking a buff icon does send a message to the server, but no buff is
> known to do anything with it, so there is no behaviour to expose — the buff bar is a read-only surface.
