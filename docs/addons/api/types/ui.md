# Data types: the widget layer

The snapshot shapes across the UI: widgets, meters, craft recipes, hotbar slots, action-menu entries, and chat lines.
Each is what `:info()` copies out of a live object, so it never updates — the live reads are verbs on that
object. The model, and what *optional* means on the tables below, is on [the catalogue](README.md).

## Widget

From [`widget:info()`](../ui/widget.md#methods-on-widget).

| Field | Type | Notes |
|---|---|---|
| `type` | string | internal widget class name |
| `role` | string | role name; optional |
| `res` | string | UI resource name; optional |
| `id` | number | server widget id; optional |
| `pos` | `{x, y}` | position relative to its parent in design pixels |
| `size` | `{w, h}` | dimensions in design pixels |
| `visible` | bool | whether drawn |
| `enabled` | bool | whether interactive |
| `owned` | bool | whether created by your addon |
| `text` | string | the label or content it draws; optional |

## Meter

From [`meter:info()`](../meter.md), the snapshot escape hatch.

| Field | Type | Notes |
|---|---|---|
| `res` | string | the background resource name, the meter's identity; optional |
| `index` | number | its 1-based HUD position; absent once the meter is gone |
| `value` | number | the **first** segment's fill fraction, 0..1; optional |
| `color` | [colour](../shapes.md#colours) | the **first** segment's colour; optional |
| `segments` | `{value, color?}[]` | the whole bar, 1-based and gap-free — always present |

## MeterSegment

From [`segment:info()`](../meter.md), the snapshot escape hatch for one band of a meter's bar.

`{ index = number, value = number, color = colour? }` — `index` is the 1-based place in the bar and
`value` its fill fraction, 0..1. `color` is absent where the content defines none.

## Craft and CraftSpec

What `session:craft():info()` hands back on [`session:craft`](../craft.md).

**Craft** —
`{ recipe = string?, inputs = CraftSpec[], outputs = CraftSpec[], qmod = ResRef[], tools = ResRef[] }`,
where a `ResRef` is `{ res = string?, name = string? }`.

**CraftSpec** — `{ res = string?, name = string?, num = number?, opt = bool? }`, what
[`spec:info()`](../craft.md) copies. `num` is the required count (`-1` means unspecified, behaving as 1). `opt` marks an optional ingredient or chance byproduct.

## Petal

From [`petal:info()`](../flowermenu.md), the snapshot escape hatch for one petal of a radial menu.

`{ index = number, wire = number, native = bool, label = string? }` — `index` is the 1-based place on the
ring, `wire` the 0-based number the menu sends and `native` whether the server sent the petal.

## ActionbarSlot

From [`slot:info()`](../actionbar.md), the snapshot escape hatch; `nil` for an empty slot.
`{ res = string?, name = string?, cooldown = number? }` — `cooldown`, 0..1, is present only for an
ability slot with a meter, and is **not** seconds.

## Pagina

From [`pagina:info()`](../menugrid.md), the snapshot escape hatch for an action-menu entry.

`{ res = string, exists = bool, addon = string?, name = string?, tooltip = string?, hotkey = string?,
path = string[]?, parent = string?, isnew = bool? }` — `res` is the identity and is always present,
`parent` is the parent's resource name, and `path` is what `pagina:categories()` reads.

## Channel

From [`channel:info()`](../chat.md), the snapshot escape hatch for one tab of the chat window.

`{ name = string?, kind = string, urgency = number }` — `kind` is one of the four words
(`"area"`, `"party"`, `"village"`, `"pm"`), `urgency` is `0` for a channel with nothing unread.

## Message

From [`message:info()`](../chat.md), the snapshot escape hatch for one line of a chat channel.

| Field | Type | Notes |
|---|---|---|
| `text` | string | the line as rendered, markup quoted; optional |
| `kind` | string | site key style (`"chat"`, `"chat.mine"`, etc.) |
| `color` | [colour](../shapes.md#colours) | the colour the line carries of itself; optional |
| `time` | number | epoch timestamp in **seconds** |
| `mine` | bool | whether this character said it |
| `speaker` | number | the kin id of whoever said it; optional |

## See also

- [the catalogue](README.md) — every snapshot shape, and what a snapshot is
- [`session:chat`](../chat.md) — the live channels and lines
- [`session:meter`](../meter.md) — the live meter bars and bands
- [`session:craft`](../craft.md) — the open recipe window
- [`session:actionbar`](../actionbar.md) — the hotbar
- [`session:menugrid`](../menugrid.md) — the action menu
- [`session:ui()`](../ui/widget.md) — the live widget tree
