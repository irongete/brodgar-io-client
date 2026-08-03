# hafen.meter: the HUD meter bars

Read the bars in the HUD's meter slot — health, stamina, energy, and whatever else the server puts
there. `hafen.meter` is a function, and the arity is the verb.

```lua
local hp = hafen.meter("hp")
if hp and (hp:value() or 1) < 0.3 then hafen.log("low health!") end
```

| Call | Returns |
|---|---|
| `hafen.meter()` | every HUD meter — a 1-based array of `Meter` objects, in HUD layout order |
| `hafen.meter(needle)` | the first meter whose resource name contains `needle`, else `nil` |

The lookup is a plain substring match and is not trimmed. A miss is plain `nil`. A **number** raises an
error — positions are not addresses, so use `hafen.meter()[n]` — and so does the empty string.

Meter objects are **interned per addon**, so `hafen.meter("hp") == hafen.meter()[1]` and
`seen[m] = true` work. A `Meter` wraps only the meter widget and re-reads it on every call, so a
stashed one tracks its bar as the server updates it — see
[snapshots vs handles](conventions.md#snapshots-vs-handles).

## There is no fixed hp, stamina and energy triple

The meter slot takes an arbitrary number of bars, and a bar is identified by the **resource its
background is drawn from** — which the *server* publishes, not the client. `"hp"` is therefore not a
key this API knows: it is a substring that happens to identify a bar on this server. `:res()` is how
you read the real names off a live client:

```lua
:lua for _, m in ipairs(hafen.meter()) do hafen.log(tostring(m:res())) end
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
`"hp"`, or iterate `hafen.meter()` and compare `:res()` yourself, rather than typing an accented
literal into your Lua source.

## Read

| Method | Returns | Description |
|---|---|---|
| `meter:res()` | string \| nil | the background resource name — the identity |
| `meter:index()` | number \| nil | its 1-based HUD position; `nil` once the meter is gone |
| `meter:value()` | number \| nil | the first segment's fill fraction, `0..1` |
| `meter:color()` | `{r, g, b, a}` \| nil | the first segment's colour, `0..255` per channel |
| `meter:segments()` | `{{value=, color=}, …}` | the whole bar, 1-based — never `nil`, may be empty |
| `meter:exists()` | boolean | whether this meter is still in the HUD slot — always answers |
| `meter:info()` | [`Meter`](types.md#meter) \| nil | a plain-table **snapshot**, the escape hatch for logging and serialising |

Every read is guarded and may answer `nil`: a brand-new meter is nameless for a beat and its segments
stream in after it appears. Only `:exists()` always answers. Nothing throws once you hold a `Meter`.

A bar is genuinely multi-segment in the engine, and `:value()` and `:color()` are simply its first
segment. The vital bars use one segment each, so the two shorthands are all you normally need, but a
bar with more shows them all in `:segments()`.

> `:value()` is a **bar fraction only**. There are no absolute hp, stamina or energy numbers, and no
> hunger figure, in the client. The one place absolute numbers exist is FEP:
> see [`hafen.char.food`](char.md).

There is no write side. Meters are server-pushed presentation and there is nothing to set. You can
freeze the bars client-side by swallowing their updates with an
[`IMeter` message hook](hook.md#hafenhookmessagemsg-fn) — purely cosmetic, since the server still
knows your real values.

## Events

| Event | Payload | Fires |
|---|---|---|
| [`MeterAdded`](events.md#character--status-widget-tree-backed) | `Meter` | a bar appears in the HUD slot |
| [`MeterRemoved`](events.md#character--status-widget-tree-backed) | `Meter` | a bar goes away; the object still reads, and `:exists()` is false |
| [`MeterChanged`](events.md#character--status-widget-tree-backed) | `Meter` | a bar's value **or** colour changes |

The meters stream in a beat after `OnEnterWorld`, so `hafen.meter()` is legitimately empty for a moment
and the bars arrive as a burst of `MeterAdded`. Mounting a horse adds two more mid-session and
dismounting removes them, which is what the lifecycle pair is for.

`MeterChanged` fires only on a real change: the whole segment array is compared, so both a value push
and a pure recolour count, and standing still is silent.

```lua
hafen.events.on("MeterChanged", function(m)
  if m:res() == "gfx/hud/meter/hp" then hafen.log(("hp %.0f%%"):format((m:value() or 0) * 100)) end
end)
```

> **A `MeterAdded` payload can be younger than its resource.** The bar that appears when you mount
> fires while its background is still loading, so inside the handler `:res()` is `nil` and
> `tostring(m)` reads `Meter(?)`. The same object answers a beat later, because a handle re-reads the
> widget on every call. Name-match a meter on a later tick, or in `MeterChanged` — never inside
> `MeterAdded`.

**A removed meter keeps answering.** Once it is out of the slot `:exists()` is `false` and `:index()`
is `nil`, but `:res()`, `:value()`, `:color()` and `:segments()` still read the values it had. That is
what makes a `MeterRemoved` payload, or a meter you stashed, worth holding on to; `:exists()` is
exactly the predicate `hafen.meter()` filters on.

## See also

- [`Meter`](types.md#meter) — the snapshot shape `:info()` returns
- [`hafen.char`](char.md) — `food()`, the one absolute reading about your character
- [`hafen.buff`](buff.md) — the other needle-keyed status surface
- [events](events.md#character--status-widget-tree-backed) — the three meter events
