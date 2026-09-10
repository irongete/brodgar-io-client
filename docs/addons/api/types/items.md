# Data types: the item and what holds it

The two snapshot shapes an item hands back: the item, and what a container states about its inside. Each
is what `:info()` copies out of a live object, so it never updates — the live reads are verbs on that
object. The model, and what *optional* means on the tables below, is on [the catalogue](README.md).

## Item

From [`item:info()`](../ui/items.md#the-item-object), the one snapshot escape hatch. Any widget's
[`:items()`](../ui/items.md) — that character's backpack (`s:ui():inventory()`), its worn gear
(`s:ui():equipment()`), a chest, a cupboard — [`s:player():hand()`](../player.md#the-hand) for the cursor
item, and [`widget:item()`](../ui/widget.md#read) for whatever one icon draws all hand you a live
[`Item` object](../ui/items.md#the-item-object), not this table. Every field is optional, and the last four
are absent together on [something the client only draws](../ui/items.md#a-depiction-that-is-not-an-item) —
a recipe slot, a listing — which is put nowhere and so has nowhere to name.

| Field | Type | Notes |
|---|---|---|
| `res` | string | resource name (stable identity) |
| `name` | string | display name |
| `quantity` | number | how many this one item is — the [number on its icon](../ui/items.md#the-two-numbers-on-an-icon) (absent for one showing none) |
| `progress` | number | `0..1`, the [arc](../ui/items.md#the-two-numbers-on-an-icon) painted over the icon (absent for one painting none) |
| `durability` | table | the [two counts](../ui/items.md#durability-the-counts-a-wear-row-prints) its wear row prints, `{cur, max}` (absent for one printing none) |
| `quality` | number | the quality the tooltip shows (absent for an item that has none) |
| `contents` | table | what it holds, as the [Contents](#contents) snapshot (absent for an item holding nothing) |
| `handle` | number | its server widget id, the number it is addressed by on the wire (absent once the item is gone, and on one the client only draws) |
| `cell` | table | the **1-based** `{x, y}` grid cell it sits in, `item:cell()` (absent for a worn or cursor item, and for one inside another item) |
| `slots` | string[] | the equipment slots it fills, by name (absent when it is not worn) |

A snapshot holds no live objects. That is why there is no `container` field here, though
[`item:container()`](../ui/contents.md) reads one, and why the `contents` snapshot
below carries no `items`: a snapshot of a bag would otherwise nest snapshots of bags without end.

## Contents

From [`contents:info()`](../ui/contents.md), the snapshot of what one item holds;
[`item:contents()`](../ui/contents.md) hands you the live object, and its
[Item objects](../ui/items.md#the-item-object) are read off that with `contents:items()`.
`{ name = string?, text = string?, quality = number?, level = table? }` — the caption the server gave this
inside, the line its tooltip states about what is in there, the **content's** own quality, and the fill
meter's `{cur, max}`, which the live `contents:fill()` reads. A container carrying items states the first,
one that states what it holds states the rest, so which fields are present is what tells the two apart.

## See also

- [the catalogue](README.md) — every snapshot shape, and what a snapshot is
- [items](../ui/items.md) — the live `Item` object this copies
- [what an item holds](../ui/contents.md) — the live `Contents` object the other copies
- [shapes](../shapes.md) — the anonymous tables these fields carry: places, sizes, colours, units
