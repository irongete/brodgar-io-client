# session:meter: The HUD Meter Bars

The bars in one character's HUD meter slot (health, stamina, energy, whatever else the server puts there), read through its [session](session.md); `session:meter()` is that character's meter slot.

```lua
local session = hafen.session():current()                    -- the character on screen
local health_meter = session and session:meter():find("hp")
local fill = health_meter and health_meter:segment():list()[1]
if fill and ((fill:value() or 1) < 0.3) then hafen.log():write("low health!") end
```

---

| Method | Returns | Permission | Description |
|---|---|---|---|
| `session:meter():list(filter)` | `Meter[]` | Unprotected | Every bar in that HUD, a 1-based array in layout order. |
| `session:meter():count(filter)` | `number` | Unprotected | How many match. |
| `session:meter():find(filter)` | `Meter \| nil` | Unprotected | The first meter that matches. |

| Rule | Detail |
|---|---|
| `filter` | A string [filter](conventions.md#the-filter-argument) is a substring match against the resource name, not trimmed. A miss is `nil`. |
| No `:get` | A meter has no key, only a server-published resource name several bars can share. `session:meter():get("hp")` raises: `session:meter() has no verb 'get' — a meter has no key, only a server-published background resource name several bars can share: session:meter():find(needle) is the search and session:meter():list()[n] takes a position`. |
| Whose bars | `hafen.session():get("alt"):meter():find("hp")` answers for that character while you watch another. |
| One object | `session:meter()` is the same object every call, minted once per session: a draw callback reading it at 60 fps allocates nothing. A session the client no longer holds answers an empty array. |
| Interned per addon | `session:meter():find("hp") == session:meter():list()[1]` and `seen[meter] = true` work. `meter:segment()` and the segments in it are not: both are [views](conventions.md#collections-the-noun-is-the-kind-the-verb-is-how-many) off the bar ([a segment](#a-segment)). A `Meter` wraps the meter widget and re-reads it on every call ([snapshots vs handles](conventions.md#snapshots-vs-handles)); it carries its own character, so `meter:exists()` and `meter:index()` are about the slot it stands in. |
| No write side | Meters are server-pushed presentation. Swallowing their updates through an [`IMeter` message filter](event/streams.md#filtering-an-inbound-update) freezes the bars client-side; the server still knows the real values. |

## No fixed hp, stamina and energy triple

The meter slot takes any number of bars, and a bar is identified by the resource its background is drawn from, which the server publishes. `"hp"` is a substring that identifies a bar on this server, not a key the API knows. Read the real names with `:res()`:

```lua
for _, meter in ipairs(hafen.session():current():meter():list()) do hafen.log():write(tostring(meter:res())) end
```

| `:res()` | What it is | When present |
|---|---|---|
| `gfx/hud/meter/hp` | Health | Always. |
| `gfx/hud/meter/stam` | Stamina | Always. |
| `gfx/hud/meter/nrj` | Energy | Always. |
| `gfx/hud/meter/häst` | The horse's own bar | While mounted. |
| `gfx/hud/meter/mount` | The mount bar | While mounted. |

The table is observed on this server, not a contract; re-derive it with `:res()`. One name is non-ASCII, so use an ASCII needle (`"st"`, `"mount"`, `"hp"`) or compare `:res()` yourself rather than typing an accented literal.

## Read

| Method | Returns | Permission | Description |
|---|---|---|---|
| `meter:res()` | `string \| nil` | Unprotected | The background resource name, the identity. |
| `meter:index()` | `number \| nil` | Unprotected | Its 1-based position in its HUD; `nil` once the meter is gone. |
| `meter:segment()` | collection | Unprotected | The bar as [Segment](#a-segment) objects in draw order; never `nil`, may be empty. |
| `meter:widget()` | [Widget](ui/widget.md) `\| nil` | Unprotected | The widget that draws it: the crossing back into the tree. |
| `meter:exists()` | `boolean` | Unprotected | Whether this meter is still in its HUD slot; always answers. |
| `meter:info()` | [`Meter`](types/ui.md#meter) `\| nil` | Unprotected | A plain-table snapshot, for logging and serialising. |

| Rule | Detail |
|---|---|
| Guarded reads | Every read may answer `nil`: a new meter is nameless for a beat and its segments stream in after it appears. Only `:exists()` always answers. Nothing throws once you hold a `Meter`. |
| Multi-segment bars | A whole-bar reading is the first segment, `meter:segment():list()[1]`. The vital bars use one segment each; a bar with more shows them all in `:segment():list()`. |
| Compacted | An empty slot the server has published nothing into is not a segment: `:segment():list()` and the `segments` array in `meter:info()` are gap-free, `ipairs` walks every entry, and a segment's place is its place in that list. |

## A segment

| Method | Returns | Permission | Description |
|---|---|---|---|
| `segment:index()` | `number` | Unprotected | Its 1-based place in `meter:segment():list()`: `list()[n]:index()` is `n`. |
| `segment:value()` | `number \| nil` | Unprotected | Its fill fraction, `0..1`. |
| `segment:color()` | [colour](shapes.md#colours) `\| nil` | Unprotected | Its colour, `{r=, g=, b=, a=}`. |
| `segment:info()` | [`MeterSegment`](types/ui.md#metersegment) `\| nil` | Unprotected | A plain-table snapshot; `nil` once the band is gone. |

| Rule | Detail |
|---|---|
| The bar's fill | `meter:segment():list()[1]:value()`, and it says which segment it is. There is no `meter:value()`: a whole-bar name over segment one is wrong the first time a server publishes a split bar. |
| No `==` | A band carries no key (a place in a bar the server rewrites whole), so `meter:segment()` mints a fresh collection and fresh Segments per call: `meter:segment() ~= meter:segment()`. The handle is live and re-reads its band; `segment:index()` identifies a band, and the Meter above it is the interned thing to key a table by. |
| Fractions only | There are no absolute hp, stamina or energy numbers and no hunger figure in the client. The one absolute reading is FEP: [`session:char():food()`](char.md#food). |

## Events

| Event | Payload | Fires |
|---|---|---|
| [`MeterAdded`](event/bus/character.md#character-and-status) | `Meter` | A bar appears in the HUD slot. |
| [`MeterRemoved`](event/bus/character.md#character-and-status) | `Meter` | A bar goes away; the object still reads and `:exists()` is false. |
| [`MeterChanged`](event/bus/character.md#character-and-status) | `Meter` | A bar's value or colour changes. |

| Rule | Detail |
|---|---|
| Startup | The meters stream in a beat after `SessionEnteredWorld`: `:list()` is empty for a moment and the bars arrive as a burst of `MeterAdded`. Mounting adds two bars mid-session and dismounting removes them. |
| `MeterChanged` | Only on a real change: the whole segment array is compared, so a value push and a pure recolour both count; standing still is silent. |
| A `MeterAdded` payload can be younger than its resource | The bar that appears when you mount fires while its background is loading: inside the handler `:res()` is `nil` and `tostring(meter)` reads `Meter(?)`. The same object answers a beat later. Name-match on a later tick or in `MeterChanged`, never inside `MeterAdded`. |
| A removed meter keeps answering | `:exists()` is `false` and `:index()` is `nil`, while `:res()` and `:segment()` read the values it had; `:exists()` is the predicate `:list()` filters on. |

```lua
hafen.event():on("MeterChanged", function(meter)
  if meter:res() == "gfx/hud/meter/hp" then
    local segment = meter:segment():list()[1]
    hafen.log():write(("hp %.0f%%"):format(((segment and segment:value()) or 0) * 100))
  end
end)
```

---

## See Also

- [`Meter`](types/ui.md#meter) — the snapshot shape `:info()` returns.
- [`session:char`](char.md) — `:food()`, the one absolute reading about your character.
- [`session:buff`](buff.md) — the other keyless status collection.
- [Events](event/bus/character.md#character-and-status) — the meter events.
