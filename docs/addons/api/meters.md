# hafen.meter — the HUD meter bars

Read the bars in the HUD's meter slot — health, stamina, energy, and whatever else the server puts
there. `hafen.meter` is a function, and the arity is the verb:

| Call | Returns |
|---|---|
| `hafen.meter()` | every HUD meter — a 1-based array of `Meter` objects, in HUD (layout) order |
| `hafen.meter(needle)` | the **first** meter whose resource name contains `needle`, else `nil` |

```lua
local hp = hafen.meter("hp")
if hp and (hp:value() or 1) < 0.3 then hafen.log("low health!") end

for _, m in ipairs(hafen.meter()) do
  hafen.log(("%s = %.0f%%"):format(tostring(m:res()), (m:value() or 0) * 100))
end
```

## There is no fixed hp/stamina/energy triple

The meter slot takes an **arbitrary number** of bars, and a bar is identified by the **resource its
background is drawn from** — which the *server* publishes, not the client. `"hp"` is therefore not a
key this API knows: it is a substring that happens to identify a bar on this server. `:res()` is how
you read the real names off a live client:

```lua
:lua (function() local t = {} for _, m in ipairs(hafen.meter()) do t[#t+1] = m:res() end return t end)()
```

Observed on this server today:

| `:res()` | what it is | when present |
|---|---|---|
| `gfx/hud/meter/hp` | health | always |
| `gfx/hud/meter/stam` | stamina | always |
| `gfx/hud/meter/nrj` | energy | always |
| `gfx/hud/meter/häst` | the horse's own bar | while mounted |
| `gfx/hud/meter/mount` | the mount bar | while mounted |

Treat that table as *observed*, not as contract — re-derive it with `:res()` on the server you are on.
Note that one of the names is **non-ASCII** (`häst`): prefer an ASCII needle (`"st"`, `"mount"`,
`"hp"`), or iterate `hafen.meter()` and compare `:res()` yourself, rather than typing an accented
literal into your Lua source.

The lookup is a plain substring match, not trimmed. A miss is plain `nil`; a **number** raises an error
(positions are not addresses — use `hafen.meter()[n]`), and so does the empty string.

Meter objects are **interned per addon**, so `hafen.meter("hp") == hafen.meter()[1]` and `seen[m] = true`
work. A `Meter` wraps **only the meter widget** and re-reads it on every call, so a stashed one tracks its
bar as the server updates it — see [snapshots vs handles](conventions.md#snapshots-vs-handles).

## Read

| Method | Returns | Description |
|---|---|---|
| `meter:res()` | string \| nil | the background resource name, e.g. `"gfx/hud/meter/hp"` — the identity |
| `meter:index()` | number \| nil | its 1-based HUD position; `nil` once the meter is gone |
| `meter:value()` | number \| nil | the first segment's fill fraction, `0..1` |
| `meter:color()` | `{r,g,b,a}` \| nil | the first segment's colour, `0..255` per channel |
| `meter:segments()` | `{{value=, color=}, …}` | the whole bar, 1-based — never nil, may be empty |
| `meter:exists()` | boolean | is this meter still in the HUD slot — always answers |
| `meter:info()` | [`Meter`](types.md#meter) \| nil | a plain-table **snapshot** `{res, index, value, color, segments}` — the escape hatch for logging/serialising |

> `:value()` is a **bar fraction only** — there are no absolute hp/stamina/energy numbers, and no hunger,
> in the client. The one place absolute numbers exist is FEP: see [`hafen.char.food`](char.md).

A bar is genuinely **multi-segment** in the engine; `:value()`/`:color()` are simply its first segment.
The vital bars use one segment each, so the two shorthands are all you normally need — but a bar with
more will show them all in `:segments()`.

Every read is `Loading`-guarded and may answer `nil`: a brand-new meter is nameless for a beat and its
segments stream in after it appears. Only `:exists()` always answers.

## Events

| Event | Payload | Fires |
|---|---|---|
| [`MeterAdded`](events.md#character--status-widget-tree-backed) | `Meter` | a bar appears in the HUD slot |
| [`MeterRemoved`](events.md#character--status-widget-tree-backed) | `Meter` | a bar goes away — the object still reads, `:exists()` is false |
| [`MeterChanged`](events.md#character--status-widget-tree-backed) | `Meter` | a bar's value **or colour** changes |

The meters stream in a beat after `OnEnterWorld`, so `hafen.meter()` is legitimately empty for a moment
and the bars arrive as a burst of `MeterAdded`. Mounting a horse adds two more mid-session, and
dismounting removes them — which is what the lifecycle pair is for.

```lua
hafen.events.on("MeterChanged", function(m)
  if m:res() == "gfx/hud/meter/hp" then hafen.log(("hp %.0f%%"):format((m:value() or 0) * 100)) end
end)
```

`MeterChanged` fires only on a **real** change — the whole segment array is compared, so both a value
push and a pure recolour count, and standing still is silent.

> **A `MeterAdded` payload can be younger than its resource.** The bar that appears when you mount fires
> while its background is still loading, so *inside the handler* `:res()` is `nil` and `tostring(m)` reads
> `Meter(?)`. The **same object** answers a beat later, because a handle re-reads the widget on every
> call. So: name-match a meter on a later tick (or in `MeterChanged`), never inside `MeterAdded`.

**A removed meter keeps answering.** Once it is out of the slot, `:exists()` is `false` and `:index()` is
`nil`, but `:res()`, `:value()`, `:color()` and `:segments()` still read the values it had — which is what
makes a `MeterRemoved` payload (or a meter you stashed) worth holding on to. `:exists()` is exactly the
predicate `hafen.meter()` filters on.

> **There is no write verb.** Meters are server-pushed presentation; there is nothing to set. You *can*
> freeze the bars client-side by swallowing their updates with an [`IMeter` message hook](hooks.md#hafenhookmessagemsg-fn)
> — purely cosmetic, the server still knows your real values.
