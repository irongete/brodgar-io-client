# Data types: the widget layer

The snapshot shapes that come out of the client's own windows: a widget in the tree, a HUD meter and one
band of its bar, the recipe one has open, a hotbar slot, an entry in the action menu, and a chat channel
with the lines in it. Each is what `:info()` copies out of a live object, so it never updates — the live
reads are verbs on that object. The model, and what *optional* means on the tables below, is on
[the catalogue](README.md).

## Widget

From [`w:info()`](../ui/widget.md#read), the snapshot escape hatch for one widget of the client's tree;
`nil` once that widget is stale, because there is nothing left to copy. [`s:ui()`](../ui/widget.md) and
every selector search hand you live `Widget` objects, not this table.

| Field | Type | Notes |
|---|---|---|
| `type` | string | the client's own class name for it, the live `w:type()` — always present |
| `owned` | bool | whether **your** addon made it, so the write verbs answer on it — always present |
| `role` | string | the [selector role](../ui/selectors.md) it classifies as; optional (absent when nothing classifies it) |
| `res` | string | the resource behind it; optional |
| `id` | number | the number the server knows it by; optional (absent on a client-side widget) |
| `pos` | `{x, y}` | its top-left in its parent, [design pixels](../ui/pixels.md); optional |
| `size` | `{w, h}` | its box, design pixels; optional |
| `visible` | bool | whether it and its parents are showing — always present |
| `enabled` | bool | whether it takes input — its own flag, the live [`w:enabled()`](../ui/widget.md#read); `true` on the client's own — always present |
| `text` | string | the label or content it draws; optional (most widgets carry none) |

The live reads are `w:type()`, `:role()`, `:res()`, `:id()`, `:position()`, `:size()`, `:visible()`,
`:enabled()`, `:text()` and `:owned()`. `pos` and `size` are spelled for the snapshot; the verbs that read
them one at a time are `:position()` and `:size()`.

## Meter

From [`meter:info()`](../meter.md#read), the one snapshot escape hatch. `s:meter():list()` and the
`MeterAdded`/`MeterRemoved`/`MeterChanged` events hand you live [`Meter` objects](../meter.md), not this
table.

| Field | Type | Notes |
|---|---|---|
| `res` | string | the background resource name, the meter's identity; optional |
| `index` | number | its 1-based HUD position; absent once the meter is gone |
| `value` | number | the **first** segment's fill fraction, 0..1; optional |
| `color` | [colour](../shapes.md#colours) | the **first** segment's colour; optional (content-defined) |
| `segments` | `{value, color?}[]` | the whole bar, 1-based and gap-free — always present, may be empty |

## MeterSegment

From [`seg:info()`](../meter.md#a-segment), the snapshot escape hatch for one band of a meter's bar;
`nil` once that band has gone, which is a meter the client shortened or took down.

`{ index = number, value = number, color = colour? }` — `index` is the 1-based place in the bar and
`value` its fill fraction, 0..1, both always present because both are properties of the band. `color` is
absent where the content defines none. It is not the same shape as the `segments` entries of the
[Meter](#meter) above, which carry no `index`: there the place is the array position. The live reads are
`seg:index()`, `:value()` and `:color()`.

## Craft and CraftSpec

What `s:craft():info()` hands back on [`session:craft`](../craft.md); the live reads are the verbs beside
it, where `qmod` is `s:craft():qualityInputs()`. The four slot reads on the section hand back
[spec objects](../craft.md#a-spec), not these tables.

**Craft** —
`{ recipe = string?, inputs = CraftSpec[], outputs = CraftSpec[], qmod = ResRef[], tools = ResRef[] }`,
where a `ResRef` is `{ res = string?, name = string? }`. `recipe` is absent while the window carries no
name, which is the absence `s:craft():recipe()` states with `nil`.

**CraftSpec** — `{ res = string?, name = string?, num = number?, opt = bool? }`, what
[`spec:info()`](../craft.md#a-spec) copies. `num` is the required or produced count in the server's own
spelling, where `-1` means unspecified and behaves as 1 — `spec:count()` answers that `1`. `opt` marks an
optional ingredient or a chance byproduct. Both are absent on a quality input and a tool, which carry
neither.

## Petal

From [`petal:info()`](../flowermenu.md#a-petal), the snapshot escape hatch for one petal of a radial menu.

`{ index = number, wire = number, label = string? }` — `index` is the 1-based place on the ring and
`wire` the 0-based number the menu itself sends, both always present because both are properties of the
object. `label` is absent once that petal's ring has closed, which is the absence `petal:label()` states
with `nil`. The live reads are `petal:index()`, `:wire()` and `:label()`.

## ActionbarSlot

From [`slot:info()`](../actionbar.md#read), the snapshot escape hatch; `nil` for an empty slot.
`{ res = string?, name = string?, cooldown = number? }` — `cooldown`, 0..1, is present only for an
ability slot with a meter, and is **not** seconds. On a slot
[held](../actionbar.md#hold-a-slot-unprotected) for an addon's own menu entry, `res` is that entry's
`addon/<addon id>/<id>` identity and `name` the name the addon gave it. The live reads are `slot:res()`,
`:name()` and `:cooldown()`.

## Pagina

From [`pag:info()`](../menugrid.md#read), the snapshot escape hatch for an action-menu entry.
[`s:menugrid():list()`](../menugrid.md#read) hands you live `Pagina` objects, not this table.

`{ res = string, exists = bool, addon = string?, name = string?, tooltip = string?, hotkey = string?,
path = string[]?, parent = string?, isnew = bool? }` — `res` is the identity and is always present,
**`parent` is the parent's resource name** rather than an object, and `path` is what the live
`pag:categories()` reads. `addon` is the id of the addon that added the entry, and is absent on the game's
own. Every other field is absent when the menu cannot answer it: the entry is gone, or its resource has
not loaded. The live reads are `pag:res()`, `:addon()`, `:name()`, `:parent()`, `:unseen()` and the rest.

## Channel

From [`channel:info()`](../chat.md#a-channel), the snapshot escape hatch for one tab of the chat window;
`nil` once that tab has gone, because there is nothing left to copy.

`{ name = string?, kind = string, urgency = number }` — `kind` is one of the four words
[`channel:kind()`](../chat.md#the-four-kinds) answers and is always present, `urgency` is `0` for a channel
with nothing unread, and `name` is absent while the client cannot state one, which is a private conversation
whose other person this character's kin roster does not carry yet. The live reads are `channel:name()`,
`:kind()` and `:urgency()`.

## Message

From [`msg:info()`](../chat.md#a-line), the snapshot escape hatch for one line of a chat channel; `nil`
once that line's channel has gone, because the scrollback goes with the tab.

| Field | Type | Notes |
|---|---|---|
| `text` | string | the line as it was written, markup and all; optional (a line whose kind carries none) |
| `kind` | string | the [site key](../chat.md#the-kind-a-line-wears) it is drawn at — always present |
| `color` | [colour](../shapes.md#colours) | the colour the line carries of itself; optional |
| `time` | number | when the client took the line, in epoch **seconds** — always present |
| `mine` | bool | whether this character said it — always present |
| `speaker` | number | the **kin id** of whoever said it; optional (most lines name nobody) |

`speaker` is the id [`s:kin():get(id)`](../kin.md) takes, not a name and not the Kin itself — the live
`msg:speaker()` hands you the object. `time` is a number with a fraction, so `string.format("%d", …)` is
how it is written down. The other live reads are `msg:text()`, `:kind()`, `:color()`, `:mine()` and
`:channel()`.

## See also

- [the catalogue](README.md) — every snapshot shape, and what a snapshot is
- [`session:chat`](../chat.md) — the live channels and lines these copy, and saying a line
- [`session:meter`](../meter.md) — the live meter bars and bands these copy
- [`session:craft`](../craft.md) — the open recipe window, and its Craft button
- [`session:actionbar`](../actionbar.md) — the hotbar, and holding a slot for an entry of your own
- [`session:menugrid`](../menugrid.md) — the action menu, and invoking an entry
- [`s:ui()`](../ui/widget.md) — the live widget tree these copy, and the verbs that read one
